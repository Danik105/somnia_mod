package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FrostedIceBlock;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тонкий лёд: ночное замёрзшее озеро, на том берегу горит костёр. Лёд трещит, если стоять
 * на месте, и ломается под ногами. Под ним плавает чёрная тень и идёт следом. В воде ждут
 * Утопленники. Дойди до костра.
 */
public final class FrozenLakeScenario extends DreamScenario {

    private static final int RADIUS = 50;
    private static final int STAND_TO_CRACK = 30;
    private static final int CRACK_STEP = 15;

    private static final BlockState ICE = Blocks.ICE.getDefaultState();
    private static final BlockState WATER = Blocks.WATER.getDefaultState();
    private static final BlockState SHADOW = Blocks.BLACK_CONCRETE.getDefaultState();

    private BlockPos center;
    private BlockPos fire;
    private final Map<UUID, BlockPos> standingOn = new HashMap<>();
    private final Map<UUID, Integer> standTicks = new HashMap<>();
    private final Map<BlockPos, Integer> cracking = new HashMap<>();
    private final List<BlockPos> shadow = new ArrayList<>();
    private BlockPos shadowAt;

    @Override
    public int areaRadius() {
        return RADIUS + 10;
    }

    @Override
    public int boundaryRadius() {
        return RADIUS + 6;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        center = session.origin;
        for (int dx = -RADIUS - 8; dx <= RADIUS + 8; dx++) {
            for (int dz = -RADIUS - 8; dz <= RADIUS + 8; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > RADIUS + 8) continue;
                BlockPos col = center.add(dx, 0, dz);
                setBulk(world, col.down(6), Blocks.GRAVEL.getDefaultState());
                if (d > RADIUS) {
                    // Берег: заснеженная земля
                    fill(world, col.down(5), col.down(2), Blocks.DIRT.getDefaultState());
                    setBulk(world, col.down(), Blocks.SNOW_BLOCK.getDefaultState());
                    if (session.random.nextInt(25) == 0) setBulk(world, col, Blocks.SNOW.getDefaultState());
                } else {
                    fill(world, col.down(5), col.down(2), WATER);
                    // Полыньи: редкие тёмные окна в воду
                    boolean hole = d > 8 && session.random.nextInt(160) == 0;
                    setBulk(world, col.down(), hole ? WATER : session.random.nextInt(12) == 0 ? Blocks.PACKED_ICE.getDefaultState() : ICE);
                }
            }
        }
        // Мёртвые деревья на берегу
        for (int i = 0; i < 14; i++) {
            double a = session.random.nextDouble() * Math.PI * 2;
            BlockPos tree = center.add((int) (Math.cos(a) * (RADIUS + 4)), 0, (int) (Math.sin(a) * (RADIUS + 4)));
            int h = 4 + session.random.nextInt(4);
            fill(world, tree, tree.up(h), Blocks.SPRUCE_LOG.getDefaultState());
            setBulk(world, tree.up(h).east(), Blocks.SPRUCE_FENCE.getDefaultState());
            setBulk(world, tree.up(h - 1).west(), Blocks.SPRUCE_FENCE.getDefaultState());
        }

        double a = session.random.nextDouble() * Math.PI * 2;
        BlockPos start = center.add((int) (Math.cos(a) * (RADIUS + 3)), 0, (int) (Math.sin(a) * (RADIUS + 3)));
        fire = center.add((int) (-Math.cos(a) * (RADIUS + 3)), 0, (int) (-Math.sin(a) * (RADIUS + 3)));
        // Костёр в кольце камней, рядом бревно-скамейка
        setBulk(world, fire, Blocks.CAMPFIRE.getDefaultState());
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            setBulk(world, fire.add(o[0], 0, o[1]), Blocks.COBBLESTONE_SLAB.getDefaultState());
        }
        setBulk(world, fire.add(3, 0, 1), Blocks.STRIPPED_SPRUCE_LOG.getDefaultState());

        shadowAt = center.down(3);
        moveShadow(world, shadowAt);
        spawnMonsters(session, center.down(3), 2, 10, 30);
        return start;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(center, RADIUS + 9, -7, 10);
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.SLOWNESS, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.lake.start").formatted(Formatting.AQUA), 10);
    }

    /** Тень подо льдом: тёмное пятно 3×2 в воде, движется к ближайшему игроку. */
    private void moveShadow(ServerWorld world, BlockPos to) {
        for (BlockPos old : shadow) {
            if (world.getBlockState(old).isOf(Blocks.BLACK_CONCRETE)) setBulk(world, old, WATER);
        }
        shadow.clear();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos pos = to.add(dx, 0, dz);
                if (world.getBlockState(pos).isOf(Blocks.WATER)) {
                    setBulk(world, pos, SHADOW);
                    shadow.add(pos);
                }
            }
        }
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();

        if (now % 12 == 0) {
            ServerPlayerEntity target = session.nearestPlayer(shadowAt.toCenterPos());
            if (target != null) {
                int dx = Integer.signum(target.getBlockX() - shadowAt.getX());
                int dz = Integer.signum(target.getBlockZ() - shadowAt.getZ());
                BlockPos next = shadowAt.add(dx, 0, dz);
                if (next.isWithinDistance(center.down(3), RADIUS - 2)) {
                    shadowAt = next;
                    moveShadow(world, shadowAt);
                }
                // Тень прямо под ногами — стучит в лёд снизу
                if (shadowAt.getSquaredDistance(target.getBlockPos().down(3)) < 4 && now % 36 == 0) {
                    Horror.sound(target, SoundEvents.BLOCK_GLASS_HIT, target.getPos().add(0, -1, 0), 1.5f, 0.5f);
                    crack(session, target.getBlockPos().down());
                }
            }
        }

        // Трещины расползаются сами
        if (now % CRACK_STEP == 0 && !cracking.isEmpty()) {
            for (Map.Entry<BlockPos, Integer> entry : new ArrayList<>(cracking.entrySet())) {
                advanceCrack(session, entry.getKey());
            }
        }
        if (now % 10 == 0) {
            world.spawnParticles(ParticleTypes.FLAME, fire.getX() + 0.5, fire.getY() + 0.8, fire.getZ() + 0.5, 3, 0.2, 0.3, 0.2, 0.01);
        }
        if (now % 200 == 0) session.sound(center, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.AMBIENT, 4.0f, 0.3f);
    }

    private void crack(DreamSession session, BlockPos pos) {
        BlockState state = session.world.getBlockState(pos);
        if ((state.isOf(Blocks.ICE) || state.isOf(Blocks.PACKED_ICE)) && !cracking.containsKey(pos)) {
            setBulk(session.world, pos, Blocks.FROSTED_ICE.getDefaultState().with(FrostedIceBlock.AGE, 0));
            cracking.put(pos.toImmutable(), 0);
            session.sound(pos, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.BLOCKS, 1.0f, 0.6f);
        }
    }

    private void advanceCrack(DreamSession session, BlockPos pos) {
        ServerWorld world = session.world;
        int age = cracking.get(pos) + 1;
        if (age > 3) {
            cracking.remove(pos);
            setBulk(world, pos, WATER);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ICE), pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5,
                    20, 0.4, 0.1, 0.4, 0.1);
            session.sound(pos, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1.2f, 0.7f);
            // Трещина бежит дальше
            if (session.random.nextInt(3) == 0) {
                crack(session, pos.offset(Direction.fromHorizontal(session.random.nextInt(4))));
            }
            return;
        }
        cracking.put(pos, age);
        if (world.getBlockState(pos).isOf(Blocks.FROSTED_ICE)) setBulk(world, pos, Blocks.FROSTED_ICE.getDefaultState().with(FrostedIceBlock.AGE, age));
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (reachedExit(session, player, fire, 6.0)) return;
        long now = session.now();
        if (now % 3 == 0) {
            session.world.spawnParticles(player, ParticleTypes.SNOWFLAKE, false, player.getX(), player.getY() + 3, player.getZ(),
                    6, 6, 2, 6, 0.01);
        }
        // Кто стоит на льду на месте — под тем лёд трещит
        BlockPos under = player.getBlockPos().down();
        UUID id = player.getUuid();
        if (under.equals(standingOn.get(id))) {
            int t = standTicks.merge(id, 1, Integer::sum);
            if (t == STAND_TO_CRACK) {
                crack(session, under);
                player.sendMessage(tr("somnium.hint.lake.crack").formatted(Formatting.AQUA), true);
            }
        } else {
            standingOn.put(id, under.toImmutable());
            standTicks.put(id, 0);
            // На бегу лёд иногда трескается позади
            if (session.random.nextInt(18) == 0) crack(session, under);
        }
        // В воде холод быстро убивает
        if (player.isTouchingWater() && now % 20 == 0) {
            player.setFrozenTicks(Math.min(player.getMinFreezeDamageTicks() + 40, player.getFrozenTicks() + 60));
        }
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        standingOn.remove(player.getUuid());
        standTicks.remove(player.getUuid());
    }
}
