package com.somnium.mod.portal;

import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamPortalHelper;
import com.somnium.mod.player.SomniumPlayerState;
import com.somnium.mod.registry.ModBlocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Портал в мир снов: постоять в плёнке 4 секунды (как в портале ада) — переносит в мир снов,
 * встречный портал там возвращает в точку входа. Точка возврата хранится в NBT игрока
 * (раньше терялась при перезапуске и всегда вела в верхний мир, даже из Незера).
 */
public final class DreamPortalManager {

    private static final int STAND_TICKS = 80;
    private static final int COOLDOWN_TICKS = 60;

    private static final Map<UUID, Integer> STAND = new HashMap<>();
    private static final Map<UUID, Long> TRAIL_UNTIL = new HashMap<>();

    private DreamPortalManager() {}

    public static void tick(MinecraftServer server) {
        long now = server.getTicks();
        if (!TRAIL_UNTIL.isEmpty()) {
            Iterator<Map.Entry<UUID, Long>> it = TRAIL_UNTIL.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null || now >= entry.getValue()) {
                    it.remove();
                } else if (now % 2 == 0) {
                    player.getServerWorld().spawnParticles(ParticleTypes.REVERSE_PORTAL,
                            player.getX(), player.getY() + 1.0, player.getZ(), 6, 0.35, 0.6, 0.35, 0.02);
                }
            }
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getServerWorld();
            boolean inPortal = world.getBlockState(player.getBlockPos()).isOf(ModBlocks.DREAM_PORTAL)
                    || world.getBlockState(player.getBlockPos().up()).isOf(ModBlocks.DREAM_PORTAL);
            if (!inPortal || DreamManager.isDreaming(player.getUuid()) || player.isSpectator()) {
                // Отрицательное значение — пауза после перехода, чтобы не прыгать туда-обратно
                STAND.computeIfPresent(player.getUuid(), (id, v) -> v < 0 ? v + 1 : null);
                continue;
            }
            int stand = STAND.merge(player.getUuid(), 1, Integer::sum);
            if (stand <= 0) continue;
            if (stand == 10) player.playSound(SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.BLOCKS, 0.5f, 0.9f);
            if (stand >= 10) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, true, false));
            }
            if (stand == 30) player.sendMessage(Text.translatable("somnium.portal.dissolving").formatted(Formatting.DARK_PURPLE), true);
            if (stand < STAND_TICKS && !player.isCreative()) continue;
            STAND.put(player.getUuid(), -COOLDOWN_TICKS);
            travel(server, player, world);
        }
    }

    private static void travel(MinecraftServer server, ServerPlayerEntity player, ServerWorld from) {
        SomniumPlayerState state = SomniumPlayerState.of(player);
        if (from.getRegistryKey().equals(ModDimensions.DREAM_PORTAL_WORLD)) {
            RegistryKey<World> backKey = state.portalReturnWorld();
            ServerWorld back = backKey != null ? server.getWorld(backKey) : null;
            BlockPos pos = state.portalReturnPos();
            if (back == null || pos == null) {
                back = server.getOverworld();
                pos = back.getSpawnPos();
            }
            state.setPortalReturn(null, null);
            player.teleport(back, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYaw(), player.getPitch());
            player.sendMessage(Text.translatable("somnium.portal.back").formatted(Formatting.GRAY), true);
            travelEffects(player, back, true);
        } else {
            ServerWorld dreamWorld = server.getWorld(ModDimensions.DREAM_PORTAL_WORLD);
            if (dreamWorld == null) return;
            state.setPortalReturn(from.getRegistryKey(), player.getBlockPos());
            DreamPortalHelper.ensureDreamPortalBuilt(dreamWorld);
            double[] arrival = DreamPortalHelper.dreamArrivalPoint();
            player.teleport(dreamWorld, arrival[0], arrival[1], arrival[2], 180.0f, 0.0f);
            DreamHints.show(player, Text.translatable("somnium.portal.arrived").formatted(Formatting.DARK_PURPLE), 9);
            travelEffects(player, dreamWorld, false);
        }
        player.fallDistance = 0;
    }

    private static void travelEffects(ServerPlayerEntity player, ServerWorld world, boolean wakingUp) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 90, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 60, 0, false, false));
        double x = player.getX(), y = player.getY() + 1, z = player.getZ();
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 80, 0.6, 1.0, 0.6, 0.2);
        world.spawnParticles(ParticleTypes.END_ROD, x, y, z, 20, 0.5, 0.9, 0.5, 0.06);
        world.playSound(null, x, y, z, SoundEvents.BLOCK_PORTAL_TRAVEL, SoundCategory.PLAYERS, 0.6f, wakingUp ? 1.2f : 0.8f);
        DreamFx.title(player,
                Text.translatable(wakingUp ? "somnium.portal.title.reality" : "somnium.portal.title.dreams")
                        .formatted(wakingUp ? Formatting.GRAY : Formatting.DARK_PURPLE),
                Text.translatable(wakingUp ? "somnium.portal.subtitle.reality" : "somnium.portal.subtitle.dreams")
                        .formatted(Formatting.DARK_GRAY), 10, 40, 20);
        TRAIL_UNTIL.put(player.getUuid(), player.getServer().getTicks() + 60L);
    }

    public static void onDisconnect(UUID playerId) {
        STAND.remove(playerId);
        TRAIL_UNTIL.remove(playerId);
    }
}
