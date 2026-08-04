package com.somnium.mod.event;

import com.somnium.mod.dream.DreamRegistry;
import com.somnium.mod.dream.DreamType;
import com.somnium.mod.sanity.SanityData;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Реализует "прорыв кошмаров в реальность" — три уровня эскалации,
 * привязанные к порогам рассудка из SanityManager.
 *
 *  < 50% (WHISPERS)  — редкие полупрозрачные "эхо", только ночью, слабый эффект
 *  < 25% (ECHOES)    — монстры ИЗ ПОСЛЕДНЕГО СНА начинают спавниться и днём
 *  < 10% (RUPTURE)   — полноценное вторжение 2-4 монстров прямо рядом с игроком
 *
 * ИСПРАВЛЕНО ("куча багов с разрывом и переходом монстров в реальность"):
 *  1. Кулдаун RUPTURE был глобальным static на весь сервер — при двух игроках
 *     в состоянии разрыва вторжение срабатывало только у первого. Теперь кулдауны
 *     (и RUPTURE, и ECHO) ведутся per-player: Map<UUID, Long>.
 *  2. Мобы спавнились в случайной точке кольца на высоте игрока — в воздухе,
 *     внутри блоков, сквозь пол. Теперь позиция валидируется: ищется твёрдая
 *     опора под ногами и свободное место для тела (по collision shape, аналог
 *     ванильного spawn placement); если точка не найдена — попытка пропускается.
 *  3. Заспавненные мобы нигде не учитывались и копились бессрочно. Теперь ведётся
 *     per-player Set<UUID> активных мобов с лимитом (выбор per-player, а не
 *     глобального лимита: на сервере один "тихий" игрок не должен съедать лимит
 *     другого, у которого разрыв). Мёртвые/деспавнувшиеся вычищаются каждый evaluate.
 *  4. tryEcho не имел кулдауна вообще — при удачных бросках спавнил несколько мобов
 *     подряд за пару секунд. Добавлен per-player кулдаун ECHO_COOLDOWN_TICKS.
 *  5. register() был пустой заглушкой — теперь подписывается на DISCONNECT и чистит
 *     per-player состояние, чтобы карты не разрастались.
 */
public final class BleedThroughManager {

    private static final Random RANDOM = new Random();

    // Антиспам: не чаще раза в ~20 секунд на игрока для RUPTURE,
    // чтобы не заваливать игрока монстрами каждый тик.
    private static final long RUPTURE_COOLDOWN_TICKS = 400L;

    // Минимальный интервал между "эхо"-спавнами (~5 секунд): без него при шансе
    // 2%/сек возможны серии из 2-3 мобов подряд за несколько секунд.
    private static final long ECHO_COOLDOWN_TICKS = 100L;

    // Максимум одновременно живых bleed-through мобов НА ИГРОКА. Сверх лимита новые
    // не спавнятся, пока старые не умрут или не деспавнятся. 4 — достаточно для
    // ощущения вторжения (RUPTURE ставит 2-4 сразу), но не превращает мир в ферму.
    private static final int MAX_ACTIVE_MOBS_PER_PLAYER = 4;

    // Сколько случайных точек кольца пробуем, прежде чем признать попытку спавна неудачной.
    private static final int SPAWN_POSITION_ATTEMPTS = 8;
    // На сколько блоков вниз от уровня игрока ищем твёрдую поверхность.
    private static final int GROUND_SCAN_DEPTH = 10;

    // Per-player кулдауны (исправление п.1 — раньше был один глобальный static long).
    private static final Map<UUID, Long> LAST_RUPTURE_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_ECHO_TICK = new HashMap<>();

    // Per-player трекинг живых bleed-through мобов (исправление п.3).
    private static final Map<UUID, Set<UUID>> ACTIVE_MOBS = new HashMap<>();

    private BleedThroughManager() {}

    public static void register() {
        // ИСПРАВЛЕНО (п.5 — пустая заглушка): чистим per-player состояние при выходе
        // игрока, иначе карты кулдаунов/трекинга росли бы бесконечно.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            LAST_RUPTURE_TICK.remove(id);
            LAST_ECHO_TICK.remove(id);
            ACTIVE_MOBS.remove(id);
        });
    }

    public static void evaluate(ServerPlayerEntity player, SanityData data) {
        float sanity = data.getSanity();
        ServerWorld world = (ServerWorld) player.getEntityWorld();

        // Вызывается раз в секунду на игрока — заодно вычищаем из трекинга мёртвых
        // и деспавнувшихся мобов, чтобы лимит отражал реальность.
        pruneTrackedMobs(world, player.getUuid());

        // Лимит достигнут — не спавним новых, пока старые живы (исправление п.3).
        if (countActive(player.getUuid()) >= MAX_ACTIVE_MOBS_PER_PLAYER) return;

        if (sanity <= SanityManager.THRESHOLD_RUPTURE) {
            tryRupture(player, world, data);
        } else if (sanity <= SanityManager.THRESHOLD_ECHOES) {
            tryEcho(player, world, data, 0.02f); // ~2% шанс за тик проверки (раз в сек)
        } else if (sanity <= SanityManager.THRESHOLD_WHISPERS) {
            if (world.isNight()) {
                tryEcho(player, world, data, 0.005f); // редко, только ночью
            }
        }
    }

    private static void tryEcho(ServerPlayerEntity player, ServerWorld world, SanityData data, float chance) {
        long now = world.getServer().getTicks();
        // ИСПРАВЛЕНО (п.4): per-player кулдаун между эхо-спавнами.
        if (now - LAST_ECHO_TICK.getOrDefault(player.getUuid(), -ECHO_COOLDOWN_TICKS) < ECHO_COOLDOWN_TICKS) return;
        if (RANDOM.nextFloat() > chance) return;

        Identifier dreamId = Identifier.tryParse(
                data.getLastDreamId().isEmpty() ? "somnium:shadow_forest" : data.getLastDreamId());
        DreamType dream = DreamRegistry.get(dreamId);
        if (dream == null || dream.monsterEntityIds().isEmpty()) return;

        // Кулдаун отсчитываем только от УСПЕШНОГО спавна — если позицию найти не
        // удалось, следующая попытка будет уже на следующем тике, а не через 5 секунд.
        if (spawnMonsterNear(world, player, pickRandom(dream.monsterEntityIds()))) {
            LAST_ECHO_TICK.put(player.getUuid(), now);
        }
    }

    private static void tryRupture(ServerPlayerEntity player, ServerWorld world, SanityData data) {
        long now = world.getServer().getTicks();
        // ИСПРАВЛЕНО (п.1): кулдаун per-player, а не глобальный на весь сервер.
        if (now - LAST_RUPTURE_TICK.getOrDefault(player.getUuid(), -RUPTURE_COOLDOWN_TICKS) < RUPTURE_COOLDOWN_TICKS) return;

        Identifier dreamId = Identifier.tryParse(
                data.getLastDreamId().isEmpty() ? "somnium:shadow_forest" : data.getLastDreamId());
        DreamType dream = DreamRegistry.get(dreamId);
        if (dream == null || dream.monsterEntityIds().isEmpty()) return;

        // Не превышаем per-player лимит даже во время вторжения.
        int freeSlots = MAX_ACTIVE_MOBS_PER_PLAYER - countActive(player.getUuid());
        int count = Math.min(2 + RANDOM.nextInt(3), freeSlots); // 2-4 монстра, но не выше лимита

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            if (spawnMonsterNear(world, player, pickRandom(dream.monsterEntityIds()))) {
                spawned++;
            }
        }
        if (spawned == 0) return; // ни одной валидной позиции — не тратим кулдаун и не пугаем игрока

        LAST_RUPTURE_TICK.put(player.getUuid(), now);

        player.sendMessage(net.minecraft.text.Text.translatable("somnium.warning.rupture_event"), true);
        // ДОБАВЛЕНО: раньше ModSounds.RUPTURE_STINGER был объявлен, но нигде не вызывался —
        // прорыв кошмаров в реальность происходил абсолютно бесшумно.
        player.playSound(
                com.somnium.mod.registry.ModSounds.RUPTURE_STINGER,
                1.0f,
                1.0f);
        // TODO: визуальный эффект "трещины в реальности" (партиклы/шейдер-оверлей) через клиентский payload
        // TODO: временно подавить ванильный спавн мобов рядом с игроком (MobSpawnerEvents.CAN_SPAWN)
    }

    /**
     * Спавнит одного моба рядом с игроком на ВАЛИДНОЙ позиции (исправление п.2).
     * Возвращает true, если моб реально появился в мире.
     */
    private static boolean spawnMonsterNear(ServerWorld world, ServerPlayerEntity player, Identifier entityId) {
        // Тот же баг, что был в DreamManager#spawnOne: Registries.ENTITY_TYPE — DefaultedRegistry
        // (дефолт minecraft:pig), поэтому .get() никогда не вернёт null для неизвестного id.
        var maybeType = Registries.ENTITY_TYPE.getOrEmpty(entityId);
        if (maybeType.isEmpty()) return false;
        EntityType<?> type = maybeType.get();

        BlockPos pos = findSpawnPos(world, player.getBlockPos());
        if (pos == null) return false; // валидной точки в радиусе нет — пропускаем попытку в этом тике

        var entity = type.create(world);
        if (!(entity instanceof MobEntity mob)) return false;

        mob.refreshPositionAndAngles(pos, player.getYaw(), 0);
        if (!world.spawnEntity(mob)) return false;

        ACTIVE_MOBS.computeIfAbsent(player.getUuid(), k -> new HashSet<>()).add(mob.getUuid());
        return true;
    }

    /**
     * Ищет безопасную точку спавна в кольце 4-8 блоков от игрока (исправление п.2):
     *  - под ногами твёрдая опора (непустая collision shape — аналог ванильного
     *    spawn placement, моб не должен появляться в воздухе и падать);
     *  - блоки на уровне ног и головы свободны (моб не застревает в стене/полу;
     *    трава/факелы без коллизии допустимы, как и в ванилле).
     * Возвращает null, если за SPAWN_POSITION_ATTEMPTS попыток ничего не нашлось.
     */
    private static BlockPos findSpawnPos(ServerWorld world, BlockPos center) {
        for (int attempt = 0; attempt < SPAWN_POSITION_ATTEMPTS; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double dist = 4 + RANDOM.nextDouble() * 4;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);

            for (int y = center.getY() + 1; y >= center.getY() - GROUND_SCAN_DEPTH; y--) {
                BlockPos feet = new BlockPos(x, y, z);
                if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) continue;
                if (!world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) continue;
                if (world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()) continue;
                return feet;
            }
        }
        return null;
    }

    /** Убирает из трекинга мобов, которые умерли или деспавнулись (выгрузка чанка). */
    private static void pruneTrackedMobs(ServerWorld world, UUID playerId) {
        Set<UUID> tracked = ACTIVE_MOBS.get(playerId);
        if (tracked == null || tracked.isEmpty()) return;
        tracked.removeIf(id -> {
            var entity = world.getEntity(id);
            return entity == null || !entity.isAlive();
        });
        if (tracked.isEmpty()) {
            ACTIVE_MOBS.remove(playerId);
        }
    }

    private static int countActive(UUID playerId) {
        Set<UUID> tracked = ACTIVE_MOBS.get(playerId);
        return tracked == null ? 0 : tracked.size();
    }

    private static Identifier pickRandom(List<Identifier> list) {
        return list.get(RANDOM.nextInt(list.size()));
    }
}
