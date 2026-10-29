package com.somnium.mod.event;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.dream.DreamRegistry;
import com.somnium.mod.dream.DreamType;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.sanity.SanityData;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Прорыв кошмаров в явь при низком рассудке:
 *  ≤ 50 — редкие "эхо" ночью;  ≤ 25 — монстры последнего сна и днём;
 *  ≤ 10 — Разрыв: 2-4 монстра рядом (и Сталкер).
 * Не работает во сне, в творческом режиме/наблюдателе и на мирной сложности.
 * Кулдауны и лимит (4 живых моба) — на каждого игрока отдельно.
 */
public final class BleedThroughManager {

    private static final Random RANDOM = new Random();
    private static final long RUPTURE_COOLDOWN_TICKS = 400L;
    private static final long ECHO_COOLDOWN_TICKS = 100L;
    private static final int MAX_ACTIVE_MOBS_PER_PLAYER = 4;
    private static final int SPAWN_POSITION_ATTEMPTS = 8;
    private static final int GROUND_SCAN_DEPTH = 10;
    private static final Identifier FALLBACK_DREAM = SomniumMod.id("shadow_forest");

    private static final Map<UUID, Long> LAST_RUPTURE_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_ECHO_TICK = new HashMap<>();
    private static final Map<UUID, Set<UUID>> ACTIVE_MOBS = new HashMap<>();

    private BleedThroughManager() {}

    public static void onDisconnect(UUID id) {
        LAST_RUPTURE_TICK.remove(id);
        LAST_ECHO_TICK.remove(id);
        ACTIVE_MOBS.remove(id);
    }

    public static void evaluate(ServerPlayerEntity player, SanityData data) {
        ServerWorld world = player.getServerWorld();
        if (player.isCreative() || player.isSpectator() || player.isSleeping()) return;
        if (world.getDifficulty() == Difficulty.PEACEFUL) return;
        if (ModDimensions.isDreamDimension(world.getRegistryKey())) return;

        pruneTrackedMobs(world, player.getUuid());
        if (countActive(player.getUuid()) >= MAX_ACTIVE_MOBS_PER_PLAYER) return;

        float sanity = data.getSanity();
        if (sanity <= SanityManager.THRESHOLD_RUPTURE) {
            tryRupture(player, world, data);
        } else if (sanity <= SanityManager.THRESHOLD_ECHOES) {
            tryEcho(player, world, data, 0.02f);
        } else if (sanity <= SanityManager.THRESHOLD_WHISPERS && world.isNight()) {
            tryEcho(player, world, data, 0.005f);
        }
    }

    private static List<Identifier> monsterPool(SanityData data) {
        Identifier last = data.getLastDreamId();
        DreamType dream = DreamRegistry.get(last != null ? last : FALLBACK_DREAM);
        if (dream == null || dream.monsters().isEmpty()) dream = DreamRegistry.get(FALLBACK_DREAM);
        return dream == null ? List.of() : dream.monsters();
    }

    private static void tryEcho(ServerPlayerEntity player, ServerWorld world, SanityData data, float chance) {
        long now = world.getServer().getTicks();
        if (now - LAST_ECHO_TICK.getOrDefault(player.getUuid(), -ECHO_COOLDOWN_TICKS) < ECHO_COOLDOWN_TICKS) return;
        if (RANDOM.nextFloat() > chance) return;
        List<Identifier> pool = monsterPool(data);
        if (pool.isEmpty()) return;
        if (spawnMonsterNear(world, player, pool.get(RANDOM.nextInt(pool.size())))) {
            LAST_ECHO_TICK.put(player.getUuid(), now);
        }
    }

    /**
     * На самом дне рассудка вместо толпы монстров (бывший "Разрыв") — только скример.
     * Монстров при низком рассудке приводит HauntManager (Тощий, Ползуны, Улыбаки).
     */
    private static void tryRupture(ServerPlayerEntity player, ServerWorld world, SanityData data) {
        long now = world.getServer().getTicks();
        if (now - LAST_RUPTURE_TICK.getOrDefault(player.getUuid(), -RUPTURE_COOLDOWN_TICKS) < RUPTURE_COOLDOWN_TICKS) return;
        if (RANDOM.nextFloat() > 0.08f) return;
        LAST_RUPTURE_TICK.put(player.getUuid(), now);
        com.somnium.mod.haunt.Horror.screamer(player, RANDOM.nextInt(com.somnium.mod.haunt.Horror.FACES));
    }

    private static boolean spawnMonsterNear(ServerWorld world, ServerPlayerEntity player, Identifier entityId) {
        var maybeType = Registries.ENTITY_TYPE.getOrEmpty(entityId);
        if (maybeType.isEmpty()) return false;
        EntityType<?> type = maybeType.get();
        BlockPos pos = findSpawnPos(world, player.getBlockPos());
        if (pos == null) return false;

        Entity entity = type.create(world);
        if (!(entity instanceof MobEntity mob)) {
            if (entity != null) entity.discard();
            return false;
        }
        mob.refreshPositionAndAngles(pos, RANDOM.nextFloat() * 360f, 0);
        mob.initialize(world, world.getLocalDifficulty(pos), SpawnReason.EVENT, null, null);
        mob.addCommandTag("somnium_bleed");
        mob.setTarget(player);
        if (!world.spawnEntity(mob)) return false;
        ACTIVE_MOBS.computeIfAbsent(player.getUuid(), k -> new HashSet<>()).add(mob.getUuid());
        return true;
    }

    /** Точка в 4-8 блоках: твёрдая опора под ногами и место для тела. */
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
                if (!world.getFluidState(feet).isEmpty()) continue;
                return feet;
            }
        }
        return null;
    }

    private static void pruneTrackedMobs(ServerWorld world, UUID playerId) {
        Set<UUID> tracked = ACTIVE_MOBS.get(playerId);
        if (tracked == null) return;
        tracked.removeIf(id -> {
            Entity entity = world.getEntity(id);
            return entity == null || !entity.isAlive();
        });
        if (tracked.isEmpty()) ACTIVE_MOBS.remove(playerId);
    }

    private static int countActive(UUID playerId) {
        Set<UUID> tracked = ACTIVE_MOBS.get(playerId);
        return tracked == null ? 0 : tracked.size();
    }
}
