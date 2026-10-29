package com.somnium.mod.haunt;

import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.entity.nightmare.CrawlerEntity;
import com.somnium.mod.entity.nightmare.SmilerEntity;
import com.somnium.mod.entity.nightmare.ThinManEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.sanity.SanityData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Что происходит с игроком, который давно не спал (вызывается раз в секунду вне снов):
 *  - случайные страшные события (WorldEvents), чем ниже рассудок, тем чаще;
 *  - рассудок ниже 30: из темноты вылезают Ползуны (до двух);
 *  - ниже 25: в тёмных местах подкрадывается Улыбака;
 *  - ниже 20: за игроком начинает ходить Тощий. Первое появление — катсцена.
 */
public final class HauntManager {

    private static final float THIN_SANITY = 20.0f;
    private static final float SMILER_SANITY = 25.0f;
    private static final float CRAWLER_SANITY = 30.0f;
    private static final int EVENT_COOLDOWN = 20 * 20;

    private static final Random RANDOM = new Random();
    private static final Map<UUID, UUID> THIN = new HashMap<>();
    private static final Set<UUID> INTRO_DONE = new HashSet<>();
    private static final Map<UUID, Long> NEXT_EVENT = new HashMap<>();
    private static final Map<UUID, Long> NEXT_THIN = new HashMap<>();

    private HauntManager() {}

    public static void onDisconnect(UUID id) {
        THIN.remove(id);
        NEXT_EVENT.remove(id);
        NEXT_THIN.remove(id);
    }

    public static void tick(ServerPlayerEntity player, SanityData data) {
        ServerWorld world = player.getServerWorld();
        if (player.isCreative() || player.isSpectator() || player.isSleeping()) return;
        if (ModDimensions.isDreamDimension(world.getRegistryKey())) return;
        if (Horror.inCutscene(player.getUuid())) return;
        float sanity = data.getSanity();
        long now = world.getServer().getTicks();

        if (now >= NEXT_EVENT.getOrDefault(player.getUuid(), 0L) && RANDOM.nextFloat() < eventChance(sanity)) {
            if (WorldEvents.fireRandom(player, sanity)) NEXT_EVENT.put(player.getUuid(), now + EVENT_COOLDOWN);
        }
        if (world.getDifficulty() == Difficulty.PEACEFUL) return;

        if (sanity < THIN_SANITY) tickThinMan(player, world, now);
        boolean dark = world.getLightLevel(player.getBlockPos().up()) <= 5;
        if (sanity < CRAWLER_SANITY && dark && RANDOM.nextFloat() < 0.03f
                && count(world, player, CrawlerEntity.class) < 2) {
            spawnInDark(world, player, ModEntities.CRAWLER, 9, 15);
        }
        if (sanity < SMILER_SANITY && world.getLightLevel(player.getBlockPos().up()) <= 3 && RANDOM.nextFloat() < 0.02f
                && count(world, player, SmilerEntity.class) < 1) {
            spawnInDark(world, player, ModEntities.SMILER, 12, 18);
        }
    }

    /** Вероятность события в секунду. */
    private static float eventChance(float sanity) {
        if (sanity > 70) return 0.0015f;
        if (sanity > 50) return 0.004f;
        if (sanity > 30) return 0.01f;
        if (sanity > 20) return 0.018f;
        return 0.03f;
    }

    private static <T extends Entity> int count(ServerWorld world, ServerPlayerEntity player, Class<T> type) {
        return world.getEntitiesByClass(type, player.getBoundingBox().expand(48), Entity::isAlive).size();
    }

    private static void spawnInDark(ServerWorld world, ServerPlayerEntity player,
                                    net.minecraft.entity.EntityType<? extends MobEntity> type, int min, int max) {
        for (int attempt = 0; attempt < 10; attempt++) {
            Vec3d point = Horror.around(player, RANDOM.nextDouble() * 360, min + RANDOM.nextInt(max - min + 1));
            BlockPos pos = Horror.ground(world, point.add(0, 1, 0), 6);
            if (pos == null || world.getLightLevel(pos.up()) > 4) continue;
            MobEntity mob = type.create(world);
            if (mob == null) return;
            mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RANDOM.nextFloat() * 360, 0);
            mob.addCommandTag("somnium_haunt");
            mob.setTarget(player);
            world.spawnEntity(mob);
            return;
        }
    }

    private static void tickThinMan(ServerPlayerEntity player, ServerWorld world, long now) {
        UUID existing = THIN.get(player.getUuid());
        if (existing != null && world.getEntity(existing) instanceof ThinManEntity thin && thin.isAlive()) return;
        THIN.remove(player.getUuid());
        if (now < NEXT_THIN.getOrDefault(player.getUuid(), 0L)) return;
        NEXT_THIN.put(player.getUuid(), now + 600);

        ThinManEntity thin = ModEntities.THIN_MAN.create(world);
        if (thin == null) return;
        thin.addCommandTag("somnium_haunt");
        thin.haunt(player);
        boolean intro = INTRO_DONE.add(player.getUuid());
        BlockPos pos = Horror.ground(world, Horror.around(player, intro ? 0 : 180, intro ? 18 : 32).add(0, 1, 0), 12);
        if (pos == null) {
            if (intro) INTRO_DONE.remove(player.getUuid());
            thin.discard();
            return;
        }
        thin.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        Horror.face(thin, player.getEyePos());
        world.spawnEntity(thin);
        THIN.put(player.getUuid(), thin.getUuid());
        if (intro) intro(player, thin);
    }

    /** Первая встреча: камера прикована к нему, он стоит вдали, потом вдруг оказывается рядом. */
    private static void intro(ServerPlayerEntity player, ThinManEntity thin) {
        Horror.cutscene(player, 110, () -> thin.isRemoved() ? null : thin.getPos().add(0, thin.getHeight() * 0.85, 0));
        for (int i = 0; i < 4; i++) {
            Horror.later(1 + i * 20, () -> Horror.sound(player, SoundEvents.ENTITY_WARDEN_HEARTBEAT, player.getEyePos(), 1.2f, 0.9f));
        }
        Horror.later(25, () -> com.somnium.mod.dream.DreamFx.title(player, Text.empty(),
                Text.translatable("somnium.haunt.thin.intro").formatted(Formatting.GRAY), 10, 40, 10));
        Horror.later(80, () -> Horror.sound(player, SoundEvents.ENTITY_ENDERMAN_STARE, thin.getPos(), 1.5f, 0.5f));
        Horror.later(88, () -> {
            if (thin.isRemoved()) return;
            Vec3d close = Horror.around(player, 0, 2.2);
            thin.refreshPositionAndAngles(close.x, player.getY(), close.z, 0, 0);
            Horror.face(thin, player.getEyePos());
            Horror.screamer(player, Horror.FACE_THIN);
        });
        Horror.later(104, () -> {
            if (!thin.isRemoved()) thin.relocate(player.getServerWorld(), player, 34);
        });
        Horror.later(112, () -> player.sendMessage(Text.translatable("somnium.haunt.thin.after")
                .formatted(Formatting.DARK_GRAY, Formatting.ITALIC), false));
    }
}
