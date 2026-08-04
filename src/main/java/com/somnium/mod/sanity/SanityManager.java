package com.somnium.mod.sanity;

import com.somnium.mod.event.BleedThroughManager;
import com.somnium.mod.network.SanitySyncPayload;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Центральный менеджер рассудка.
 *
 * Правила (согласно ТЗ):
 *  - Более 1 игрового дня (24000 тиков) без сна -> рассудок начинает падать.
 *  - Чем дольше без сна, тем быстрее падение (нелинейная кривая).
 *  - При падении ниже порогов -> BleedThroughManager получает сигнал прорыва кошмаров.
 *  - Успешный сон -> восстановление рассудка (частичное или полное, см. DreamOutcome).
 */
public final class SanityManager {

    // 1 игровой день = 24000 тиков. Порог, после которого рассудок начинает падать.
    public static final long GRACE_PERIOD_TICKS = 24000L;

    // Пороги прорыва кошмаров в реальность
    public static final float THRESHOLD_WHISPERS = 50.0f;   // лёгкие эффекты + редкие "эхо"-мобы
    public static final float THRESHOLD_ECHOES = 25.0f;     // монстры последнего сна начинают появляться
    public static final float THRESHOLD_RUPTURE = 10.0f;    // полноценное вторжение

    // Кэш данных по игрокам в памяти (персистится в NBT игрока при сохранении/загрузке)
    private static final Map<UUID, SanityData> CACHE = new HashMap<>();

    private SanityManager() {}

    public static SanityData get(ServerPlayerEntity player) {
        return CACHE.computeIfAbsent(player.getUuid(), id -> loadFromPlayer(player));
    }

    private static SanityData loadFromPlayer(ServerPlayerEntity player) {
        // TODO: заменить на чтение из player.getPersistentData() / кастомного attachment API 26.1
        // Для 26.1 рекомендуется использовать новый Data Attachment API вместо ручного NBT,
        // но для наглядности здесь показан классический подход.
        return new SanityData();
    }

    public static void persistToPlayer(ServerPlayerEntity player) {
        SanityData data = get(player);
        // TODO: сохранить data.writeNbt(...) в attachment/персистентные данные игрока
    }

    /** Вызывается раз в секунду (20 тиков) для каждого онлайн-игрока. */
    public static void tick(ServerPlayerEntity player) {
        SanityData data = get(player);
        data.setTicksSinceLastSleep(data.getTicksSinceLastSleep() + 20);

        long overGrace = data.getTicksSinceLastSleep() - GRACE_PERIOD_TICKS;
        if (overGrace > 0) {
            float drainPerSecond = computeDrainRate(overGrace);
            // ИСПРАВЛЕНО ("Ловец Снов ничего не делает"): DREAM_CATCHER регистрировался как
            // предмет мода, но ни один кусок кода нигде не проверял, есть ли он у игрока — то
            // есть предмет физически существовал, но не имел ни одного эффекта. Теперь ослабляет
            // падение рассудка, если лежит где-то в инвентаре (не обязательно в руке — предмет
            // задуман как "оберег", который носят с собой, а не активно используют).
            if (hasDreamCatcher(player)) {
                drainPerSecond *= DREAM_CATCHER_DRAIN_MULTIPLIER;
            }
            float previous = data.getSanity();
            data.addSanity(-drainPerSecond);

            checkThresholdCrossed(player, previous, data.getSanity());
        }

        BleedThroughManager.evaluate(player, data);
        SanitySyncPayload.sendTo(player, data.getSanity());
    }

    /**
     * Нелинейная скорость падения рассудка в зависимости от того,
     * сколько тиков прошло СВЕРХ льготного периода (1 день).
     *  0-1 день сверх нормы   -> медленно   (~0.15/сек  ≈ 3 пункта в игровую минуту)
     *  1-3 дня сверх нормы    -> средне     (~0.35/сек)
     *  3+ дня сверх нормы     -> быстро     (~0.6/сек)
     */
    /** Насколько Ловец Снов ослабляет падение рассудка (0.5 = вдвое медленнее) — см. tick(). */
    private static final float DREAM_CATCHER_DRAIN_MULTIPLIER = 0.5f;

    private static boolean hasDreamCatcher(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).getItem() == com.somnium.mod.registry.ModItems.DREAM_CATCHER) {
                return true;
            }
        }
        return false;
    }

    private static float computeDrainRate(long overGraceTicks) {
        long days = overGraceTicks / 24000L;
        if (days < 1) return 0.15f;
        if (days < 3) return 0.35f;
        return 0.60f;
    }

    private static void checkThresholdCrossed(ServerPlayerEntity player, float before, float after) {
        warnIfCrossed(player, before, after, THRESHOLD_WHISPERS, "somnium.warning.whispers");
        warnIfCrossed(player, before, after, THRESHOLD_ECHOES, "somnium.warning.echoes");
        warnIfCrossed(player, before, after, THRESHOLD_RUPTURE, "somnium.warning.rupture");
    }

    private static void warnIfCrossed(ServerPlayerEntity player, float before, float after, float threshold, String langKey) {
        if (before > threshold && after <= threshold) {
            player.sendMessage(net.minecraft.text.Text.translatable(langKey), true);
        }
    }

    /** Вызывается системой снов при успешном/неуспешном пробуждении. */
    public static void onWake(ServerPlayerEntity player, DreamOutcome outcome, String dreamId) {
        SanityData data = get(player);
        data.resetSleepTimer();
        data.setLastDreamId(dreamId);
        data.addSanity(outcome.sanityDelta());
        SanitySyncPayload.sendTo(player, data.getSanity());
    }

    /** Результат прохождения сна — влияет на восстановление рассудка. */
    public enum DreamOutcome {
        SURVIVED_OBJECTIVE(35.0f),   // выполнил цель сна (нашёл артефакт/победил стража)
        SURVIVED_TIMEOUT(15.0f),    // просто пережил таймер сна
        DIED_IN_DREAM(-10.0f),      // умер во сне — штраф, а не восстановление
        WOKE_EARLY(5.0f);           // нашёл "Дверь пробуждения" раньше времени

        private final float sanityDelta;

        DreamOutcome(float sanityDelta) {
            this.sanityDelta = sanityDelta;
        }

        public float sanityDelta() {
            return sanityDelta;
        }
    }
}
