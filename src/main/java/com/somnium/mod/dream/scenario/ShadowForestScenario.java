package com.somnium.mod.dream.scenario;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Лес теней: тьма, ведьмины огни указывают путь к Дереву-Маяку. Выход — простоять
 * 5 секунд в свете дерева. Шёпот звучит строго за спиной (слышит только тот, кому
 * шепчут); обернёшься в течение 4 секунд — перед тобой встанет тень.
 *
 * Раньше Дерево-Маяк вообще не строилось: вместо него ставилась обычная дверь.
 */
public final class ShadowForestScenario extends DreamScenario {

    private static final int GUIDE_LIGHT_INTERVAL = 50;
    private static final int WHISPER_TURN_WINDOW = 80;
    private static final float WHISPER_TURN_ANGLE = 110f;
    private static final double CHANNEL_RADIUS = 3.0;
    private static final int CHANNEL_REQUIRED = 100;
    private static final int TREE_MIN_DIST = 110;
    private static final int TREE_MAX_DIST = 150;

    private BlockPos tree;

    private static final class PlayerState {
        long nextGuide;
        long nextWhisper;
        long whisperTick = -1;
        float whisperYaw;
        int channel;
    }

    private final Map<UUID, PlayerState> players = new HashMap<>();

    @Override
    public int areaRadius() {
        return TREE_MAX_DIST + 50;
    }

    @Override
    public int boundaryRadius() {
        return TREE_MAX_DIST + 40;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        BlockPos origin = session.origin;
        clearTrees(world, origin, 2, 8);

        double angle = session.random.nextDouble() * Math.PI * 2;
        int dist = TREE_MIN_DIST + session.random.nextInt(TREE_MAX_DIST - TREE_MIN_DIST + 1);
        tree = origin.add((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
        clearTrees(world, tree, 4, 14);
        buildBeaconTree(session, tree);

        spawnMonsters(session, origin, 3, 8, 16);
        SomniumMod.LOGGER.debug("[Somnium] Дерево-Маяк в {}", tree.toShortString());
        return origin;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(tree.getX() - 5, tree.getY() - 1, tree.getZ() - 5,
                tree.getX() + 5, tree.getY() + 14, tree.getZ() + 5);
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.SLOWNESS, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        PlayerState state = new PlayerState();
        state.nextWhisper = session.now() + 200;
        players.put(player.getUuid(), state);
        DreamHints.show(player, tr("somnium.hint.forest.start").formatted(Formatting.GRAY), 10);
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        players.remove(player.getUuid());
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        PlayerState state = players.computeIfAbsent(player.getUuid(), id -> new PlayerState());
        ServerWorld world = session.world;
        long now = session.now();

        // 1) Ведьмины огни — частицы видны даже сквозь слепоту
        if (now >= state.nextGuide) {
            state.nextGuide = now + GUIDE_LIGHT_INTERVAL;
            double dx = tree.getX() + 0.5 - player.getX();
            double dz = tree.getZ() + 0.5 - player.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0) {
                for (int i = 1; i <= 3; i++) {
                    world.spawnParticles(player, ParticleTypes.SOUL_FIRE_FLAME, false,
                            player.getX() + dx / len * i * 1.8, player.getY() + 1.2, player.getZ() + dz / len * i * 1.8,
                            1, 0.02, 0.02, 0.02, 0.0);
                }
            }
        }

        // 2) Канал в свете Дерева-Маяка
        double distSq = MathHelper.square(player.getX() - (tree.getX() + 0.5)) + MathHelper.square(player.getZ() - (tree.getZ() + 0.5));
        if (distSq <= CHANNEL_RADIUS * CHANNEL_RADIUS && Math.abs(player.getY() - tree.getY()) < 4) {
            state.channel++;
            if (state.channel == 1) {
                player.sendMessage(tr("somnium.hint.forest.channel_start").formatted(Formatting.AQUA), true);
            } else if (state.channel % 25 == 0 && state.channel < CHANNEL_REQUIRED) {
                player.sendMessage(tr("somnium.hint.forest.channel", state.channel * 100 / CHANNEL_REQUIRED)
                        .formatted(Formatting.AQUA), true);
                player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS,
                        1.0f, 0.8f + 0.4f * state.channel / CHANNEL_REQUIRED);
            }
            world.spawnParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 0.5, player.getZ(),
                    2, 0.3, 0.5, 0.3, 0.01);
            if (state.channel >= CHANNEL_REQUIRED && DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
                player.playSound(SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.5f, 1.2f);
                return;
            }
        } else if (state.channel > 0) {
            state.channel = Math.max(0, state.channel - 2);
        }

        // 3) Шёпот строго за спиной
        if (now >= state.nextWhisper) {
            state.nextWhisper = now + 180 + session.random.nextInt(140);
            double yawRad = Math.toRadians(player.getYaw());
            DreamFx.soundTo(player, ModSounds.WHISPER_AMBIENT, SoundCategory.HOSTILE,
                    player.getX() + Math.sin(yawRad) * 3.5, player.getY() + 1.0, player.getZ() - Math.cos(yawRad) * 3.5,
                    0.8f, 0.5f + session.random.nextFloat() * 0.3f);
            state.whisperTick = now;
            state.whisperYaw = player.getYaw();
        }

        // 4) Обернулся на шёпот — тень встаёт прямо перед глазами
        if (state.whisperTick >= 0) {
            if (now - state.whisperTick > WHISPER_TURN_WINDOW) {
                state.whisperTick = -1;
            } else if (Math.abs(MathHelper.wrapDegrees(player.getYaw() - state.whisperYaw)) > WHISPER_TURN_ANGLE) {
                state.whisperTick = -1;
                double yawRad = Math.toRadians(player.getYaw());
                BlockPos shadePos = session.groundNear(
                        MathHelper.floor(player.getX() - Math.sin(yawRad) * 2.5), player.getBlockY(),
                        MathHelper.floor(player.getZ() + Math.cos(yawRad) * 2.5));
                session.spawnMob(session.type.monsters().isEmpty() ? SomniumMod.id("lurking_shade")
                        : session.type.monsters().get(0), shadePos, player.getYaw() + 180f);
                DreamFx.soundTo(player, ModSounds.WHISPER_AMBIENT, SoundCategory.HOSTILE,
                        player.getX(), player.getY(), player.getZ(), 1.2f, 0.3f);
            }
        }
    }

    /** Расчищает цилиндр от деревьев (стволы и листва леса генерируются миром). */
    private static void clearTrees(ServerWorld world, BlockPos center, int radius, int height) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + radius) continue;
                for (int dy = 0; dy <= height; dy++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.AIR.getDefaultState(), BULK);
                }
            }
        }
    }

    /** Мёртвый тёмный дуб со светящимся сердцем, фонарями душ на ветвях и кольцом света у корней. */
    private static void buildBeaconTree(DreamSession session, BlockPos pos) {
        ServerWorld world = session.world;
        for (int dy = 0; dy <= 4; dy++) set(world, pos.up(dy), Blocks.DARK_OAK_LOG.getDefaultState());
        set(world, pos.up(5), Blocks.SEA_LANTERN.getDefaultState());
        BlockPos[] branches = {pos.add(1, 4, 0), pos.add(-1, 4, 0), pos.add(0, 4, 1), pos.add(0, 4, -1)};
        for (BlockPos branch : branches) {
            set(world, branch, Blocks.DARK_OAK_LOG.getDefaultState());
            set(world, branch.down(), Blocks.SOUL_LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        }
        for (int[] c : new int[][]{{2, 2}, {-2, 2}, {2, -2}, {-2, -2}}) {
            set(world, pos.add(c[0], 0, c[1]), Blocks.SOUL_LANTERN.getDefaultState());
        }
        ArmorStandEntity marker = new ArmorStandEntity(world, pos.getX() + 0.5, pos.getY() + 6.5, pos.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomName(tr("somnium.dream.beacon_tree").formatted(Formatting.AQUA, Formatting.BOLD));
        marker.setCustomNameVisible(true);
        session.add(marker);
    }
}
