package com.somnium.mod.sanity;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.event.BleedThroughManager;
import com.somnium.mod.network.SanitySyncPayload;
import com.somnium.mod.player.SomniumPlayerState;
import com.somnium.mod.registry.ModItems;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Рассудок:
 *  - первые сутки (24000 тиков) без сна рассудок не падает, дальше — всё быстрее;
 *  - Ловец Снов в инвентаре вдвое замедляет падение;
 *  - восстанавливается сном (см. DreamOutcome) и Осколком Ясности;
 *  - ниже порогов кошмары прорываются в явь (BleedThroughManager).
 * Данные хранятся в NBT игрока и переживают перезапуск сервера.
 */
public final class SanityManager {

    public static final long GRACE_PERIOD_TICKS = 24000L;

    public static final float THRESHOLD_WHISPERS = 50.0f;
    public static final float THRESHOLD_ECHOES = 25.0f;
    public static final float THRESHOLD_RUPTURE = 10.0f;

    private static final float DREAM_CATCHER_DRAIN_MULTIPLIER = 0.5f;

    private SanityManager() {}

    public static SanityData get(ServerPlayerEntity player) {
        return SomniumPlayerState.of(player).sanity();
    }

    /** Раз в секунду для каждого онлайн-игрока. */
    public static void tick(ServerPlayerEntity player) {
        SanityData data = get(player);
        boolean dreaming = DreamManager.isDreaming(player.getUuid());
        boolean exempt = player.isCreative() || player.isSpectator();

        if (!dreaming && !exempt) {
            data.setTicksSinceLastSleep(data.getTicksSinceLastSleep() + 20);
            long overGrace = data.getTicksSinceLastSleep() - GRACE_PERIOD_TICKS;
            if (overGrace > 0) {
                float drain = computeDrainRate(overGrace);
                if (hasDreamCatcher(player)) drain *= DREAM_CATCHER_DRAIN_MULTIPLIER;
                add(player, -drain, false);
            }
            BleedThroughManager.evaluate(player, data);
        }
        sync(player);
    }

    /** 0-1 день сверх нормы — 0.15/сек, 1-3 дня — 0.35/сек, дальше — 0.6/сек. */
    private static float computeDrainRate(long overGraceTicks) {
        long days = overGraceTicks / 24000L;
        if (days < 1) return 0.15f;
        if (days < 3) return 0.35f;
        return 0.60f;
    }

    private static boolean hasDreamCatcher(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(ModItems.DREAM_CATCHER)) return true;
        }
        return false;
    }

    /** Изменить рассудок; при пересечении порога вниз — предупреждение в экшенбар. */
    public static void add(ServerPlayerEntity player, float delta, boolean syncNow) {
        SanityData data = get(player);
        float before = data.getSanity();
        data.setSanity(before + delta);
        float after = data.getSanity();
        warnIfCrossed(player, before, after, THRESHOLD_WHISPERS, "somnium.warning.whispers");
        warnIfCrossed(player, before, after, THRESHOLD_ECHOES, "somnium.warning.echoes");
        warnIfCrossed(player, before, after, THRESHOLD_RUPTURE, "somnium.warning.rupture");
        if (syncNow) sync(player);
    }

    public static void add(ServerPlayerEntity player, float delta) {
        add(player, delta, true);
    }

    public static void set(ServerPlayerEntity player, float value) {
        get(player).setSanity(value);
        sync(player);
    }

    private static void warnIfCrossed(ServerPlayerEntity player, float before, float after, float threshold, String key) {
        if (before > threshold && after <= threshold) {
            player.sendMessage(Text.translatable(key), true);
        }
    }

    public static void sync(ServerPlayerEntity player) {
        SanitySyncPayload.sendTo(player, get(player).getSanity());
    }

    /** Итог сна: награда/штраф, история снов, сброс бессонницы. */
    public static void onWake(ServerPlayerEntity player, DreamOutcome outcome, Identifier dreamId) {
        SanityData data = get(player);
        if (outcome.countsAsSleep()) {
            data.setTicksSinceLastSleep(0L);
            data.pushDream(dreamId);
        }
        add(player, outcome.sanityDelta(), true);
    }
}
