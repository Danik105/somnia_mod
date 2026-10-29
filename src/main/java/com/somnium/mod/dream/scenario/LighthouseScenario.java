package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Маяк: скалистый остров посреди чёрного моря, гроза. Маяк погас. На острове три бочки
 * с маслом — найди их, поднимись по винтовой лестнице и зажги лампу. Из воды на берег
 * выползают Утопленники. Когда лампа загорится, вдали отзовётся корабль, и сон отпустит.
 */
public final class LighthouseScenario extends DreamScenario {

    private static final int ISLAND = 22;
    private static final int SEA = 46;
    private static final int TURNS = 4;
    private static final int STEPS_PER_TURN = 16;
    private static final int TOWER_H = TURNS * STEPS_PER_TURN / 2;
    private static final int OIL = 3;

    private static final BlockState STEP_BOTTOM = Blocks.STONE_BRICK_SLAB.getDefaultState();
    private static final BlockState STEP_TOP = STEP_BOTTOM.with(SlabBlock.TYPE, SlabType.TOP);
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos center;
    private BlockPos lamp;
    private final Pickups oil = new Pickups();
    private boolean lit;
    private long wakeAt = -1;

    @Override
    public int areaRadius() {
        return SEA + 4;
    }

    @Override
    public int boundaryRadius() {
        return SEA - 4;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        center = session.origin;

        for (int dx = -SEA - 1; dx <= SEA + 1; dx++) {
            for (int dz = -SEA - 1; dz <= SEA + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > SEA + 1.5) continue;
                BlockPos col = center.add(dx, 0, dz);
                if (d > SEA) {
                    // Каменный край моря: иначе вода утекла бы в пустоту
                    fill(world, col.down(6), col.down(), Blocks.STONE.getDefaultState());
                    continue;
                }
                setBulk(world, col.down(6), Blocks.GRAVEL.getDefaultState());
                // Остров: скала поднимается к центру, по краю валуны
                double noise = session.random.nextDouble() * 2.5;
                if (d < ISLAND - noise) {
                    fill(world, col.down(5), col.down(2), Blocks.STONE.getDefaultState());
                    int r = session.random.nextInt(10);
                    setBulk(world, col.down(), d < ISLAND - 5 ? (r < 6 ? Blocks.GRASS_BLOCK : r < 8 ? Blocks.COARSE_DIRT : Blocks.STONE).getDefaultState()
                            : (r < 5 ? Blocks.GRAVEL : Blocks.STONE).getDefaultState());
                    if (d > ISLAND - 6 && session.random.nextInt(14) == 0) {
                        setBulk(world, col, Blocks.COBBLESTONE.getDefaultState());
                        if (session.random.nextBoolean()) setBulk(world, col.up(), Blocks.MOSSY_COBBLESTONE.getDefaultState());
                    }
                } else {
                    fill(world, col.down(5), col.down(), Blocks.WATER.getDefaultState());
                }
            }
        }
        buildTower(world);

        // Причал, на котором стоишь в начале
        double a = session.random.nextDouble() * Math.PI * 2;
        int px = (int) Math.round(Math.cos(a));
        int pz = (int) Math.round(Math.sin(a));
        BlockPos spawn = center;
        for (int s = ISLAND - 4; s < ISLAND + 10; s++) {
            BlockPos plank = center.add((int) Math.round(Math.cos(a) * s), -1, (int) Math.round(Math.sin(a) * s));
            fill(world, plank.add(-1, 0, -1), plank.add(1, 0, 1), Blocks.SPRUCE_PLANKS.getDefaultState());
            if (s % 4 == 0) fill(world, plank.add(px == 0 ? 2 : 0, -4, pz == 0 ? 2 : 0), plank.add(px == 0 ? 2 : 0, 1, pz == 0 ? 2 : 0),
                    Blocks.SPRUCE_FENCE.getDefaultState());
            if (s == ISLAND + 8) spawn = plank.up();
        }

        // Бочки с маслом: в сарае, у скал, на берегу
        for (int i = 0; i < OIL; i++) {
            double ba = a + Math.PI * (0.5 + i * 0.5) + session.random.nextDouble() * 0.6;
            int r = 9 + session.random.nextInt(ISLAND - 14);
            BlockPos ground = session.groundNear(center.getX() + (int) (Math.cos(ba) * r), center.getY(), center.getZ() + (int) (Math.sin(ba) * r));
            setBulk(world, ground, Blocks.BARREL.getDefaultState());
            oil.add(ground);
            if (i == 0) buildShed(world, ground);
        }
        spawnMonsters(session, center, 1, ISLAND - 3, ISLAND);
        return spawn;
    }

    /** Башня: бело-красные кольца стены и винтовая лестница из плит вокруг столба. */
    private void buildTower(ServerWorld world) {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                double angle = Math.atan2(dz, dx) + Math.PI;
                int sector = (int) (angle / (Math.PI * 2) * STEPS_PER_TURN) % STEPS_PER_TURN;
                for (int y = -1; y <= TOWER_H + 5; y++) {
                    BlockPos pos = center.add(dx, y, dz);
                    if (d >= 3.6 && d < 4.6) {
                        boolean red = (y / 5) % 2 == 1;
                        BlockState wall = red ? Blocks.RED_CONCRETE.getDefaultState() : Blocks.WHITE_CONCRETE.getDefaultState();
                        if (y > TOWER_H && y <= TOWER_H + 3) wall = Blocks.GLASS.getDefaultState();
                        if (y == TOWER_H + 4) wall = Blocks.BLACK_CONCRETE.getDefaultState();
                        if (y > TOWER_H + 4) wall = AIR;
                        setBulk(world, pos, wall);
                    } else if (d < 3.6) {
                        setBulk(world, pos, y == -1 || y == TOWER_H + 4 ? Blocks.STONE_BRICKS.getDefaultState() : AIR);
                    }
                }
                if (d < 1.0) {
                    fill(world, center.add(dx, 0, dz), center.add(dx, TOWER_H - 1, dz), Blocks.STONE_BRICKS.getDefaultState());
                } else if (d < 3.6) {
                    // Лестница: каждый сектор — полблока выше предыдущего
                    for (int turn = 0; turn < TURNS; turn++) {
                        int h = turn * STEPS_PER_TURN + sector;
                        setBulk(world, center.add(dx, h / 2, dz), h % 2 == 0 ? STEP_BOTTOM : STEP_TOP);
                    }
                    // Пол фонарной комнаты, кроме сектора, куда выходит лестница
                    if (sector < STEPS_PER_TURN - 6) setBulk(world, center.add(dx, TOWER_H - 1, dz), Blocks.STONE_BRICKS.getDefaultState());
                }
            }
        }
        // Дверной проём внизу и узкие окна
        fill(world, center.add(4, 0, 0), center.add(4, 1, 0), AIR);
        fill(world, center.add(3, 0, 0), center.add(3, 1, 0), AIR);
        for (int y = 6; y < TOWER_H; y += 7) setBulk(world, center.add(0, y, 4), Blocks.GLASS_PANE.getDefaultState());
        lamp = center.up(TOWER_H);
        setBulk(world, lamp, Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, false));
    }

    private void buildShed(ServerWorld world, BlockPos barrel) {
        fill(world, barrel.add(-2, 0, -2), barrel.add(2, 2, 2), Blocks.SPRUCE_PLANKS.getDefaultState());
        fill(world, barrel.add(-1, 0, -1), barrel.add(1, 1, 1), AIR);
        fill(world, barrel.add(-2, 3, -2), barrel.add(2, 3, 2), Blocks.SPRUCE_SLAB.getDefaultState());
        fill(world, barrel.add(2, 0, 0), barrel.add(2, 1, 0), AIR);
        setBulk(world, barrel, Blocks.BARREL.getDefaultState());
        setBulk(world, barrel.add(-1, 0, 1), Blocks.CHEST.getDefaultState());
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(center, SEA + 2, -7, TOWER_H + 6);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamFx.sendWeather(player, 1.0f, 1.0f);
        DreamHints.show(player, tr("somnium.hint.lighthouse.start", OIL).formatted(Formatting.GRAY), 10);
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        ServerWorld world = session.world;
        if (oil.take(pos)) {
            session.sound(pos, SoundEvents.ITEM_BUCKET_FILL, SoundCategory.BLOCKS, 1.0f, 0.6f);
            session.actionBar(oil.remaining() > 0
                    ? tr("somnium.hint.lighthouse.oil", oil.remaining()).formatted(Formatting.GOLD)
                    : tr("somnium.hint.lighthouse.climb").formatted(Formatting.GOLD));
            return ActionResult.SUCCESS;
        }
        if (pos.equals(lamp)) {
            if (lit) return ActionResult.SUCCESS;
            if (oil.remaining() > 0) {
                player.sendMessage(tr("somnium.hint.lighthouse.no_oil", oil.remaining()).formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            lightLamp(session);
            return ActionResult.SUCCESS;
        }
        BlockState state = world.getBlockState(pos);
        return state.isOf(Blocks.BARREL) || state.isOf(Blocks.CHEST) ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    private void lightLamp(DreamSession session) {
        lit = true;
        ServerWorld world = session.world;
        setBulk(world, lamp, Blocks.CAMPFIRE.getDefaultState());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) setBulk(world, lamp.add(dx, 2, dz), Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        session.sound(lamp, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 2.0f, 0.6f);
        session.hint(tr("somnium.hint.lighthouse.lit").formatted(Formatting.WHITE), 8);
        // Утопленники уходят обратно в воду
        for (HostileEntity mob : world.getEntitiesByClass(HostileEntity.class, new Box(center).expand(SEA), session::owns)) {
            world.spawnParticles(ParticleTypes.SPLASH, mob.getX(), mob.getY() + 1, mob.getZ(), 20, 0.4, 0.6, 0.4, 0.1);
            mob.discard();
        }
        Horror.later(60, () -> session.sound(center, SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.AMBIENT, 8.0f, 0.5f));
        wakeAt = session.now() + 140;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 10 == 0) oil.sparkle(world, ParticleTypes.FALLING_HONEY);
        if (lit) {
            // Луч маяка ходит по кругу
            double a = now * 0.08;
            for (int s = 3; s < 40; s += 3) {
                world.spawnParticles(ParticleTypes.END_ROD, lamp.getX() + 0.5 + Math.cos(a) * s, lamp.getY() + 2.5, lamp.getZ() + 0.5 + Math.sin(a) * s,
                        1, 0.1, 0.1, 0.1, 0.0);
            }
            if (now >= wakeAt) {
                session.sound(center, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT, 3.0f, 1.0f);
                session.wakeAll(DreamOutcome.SURVIVED_OBJECTIVE);
            }
            return;
        }
        // Молнии над морем
        if (now % 120 == 0 && session.random.nextInt(2) == 0) {
            double a = session.random.nextDouble() * Math.PI * 2;
            double r = ISLAND + 6 + session.random.nextInt(SEA - ISLAND - 8);
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
            if (bolt != null) {
                bolt.setCosmetic(true);
                bolt.refreshPositionAfterTeleport(center.getX() + Math.cos(a) * r, center.getY(), center.getZ() + Math.sin(a) * r);
                session.add(bolt);
            }
        }
        // Из воды выползают Утопленники
        if (now % 400 == 0 && session.elapsed() > 300) {
            int count = world.getEntitiesByClass(HostileEntity.class, new Box(center).expand(SEA), session::owns).size();
            if (count < 4) {
                for (Identifier id : session.type.monsters()) session.spawnMob(id, session.ringPos(center, ISLAND - 3, ISLAND - 1), 0f);
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (session.now() % 2 == 0) {
            session.world.spawnParticles(player, ParticleTypes.FALLING_WATER, false, player.getX(), player.getY() + 8, player.getZ(),
                    30, 8, 2, 8, 0.0);
        }
        if (session.now() % 100 == 0) DreamFx.sendWeather(player, 1.0f, 1.0f);
    }
}
