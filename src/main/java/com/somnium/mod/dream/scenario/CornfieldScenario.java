package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Кукурузное поле: стены высоких стеблей выше головы, узкие тропинки, ночь. Над полем
 * виден далёкий фонарь на шесте у фермерского дома — к нему и надо выйти. В поле стоят
 * пугала. Пока на пугало смотришь, оно неподвижно. Отвернёшься — оно уже ближе.
 */
public final class CornfieldScenario extends DreamScenario {

    private static final int RADIUS = 48;
    private static final int SCARECROWS = 7;
    private static final double STEP = 2.5;

    private static final BlockState CORN = Blocks.SUGAR_CANE.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos center;
    private BlockPos house;
    private BlockPos exit;
    private final List<ArmorStandEntity> scarecrows = new ArrayList<>();

    @Override
    public int areaRadius() {
        return RADIUS + 10;
    }

    @Override
    public int boundaryRadius() {
        return RADIUS + 4;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        center = session.origin;
        for (int dx = -RADIUS - 6; dx <= RADIUS + 6; dx++) {
            for (int dz = -RADIUS - 6; dz <= RADIUS + 6; dz++) {
                BlockPos col = center.add(dx, 0, dz);
                setBulk(world, col.down(2), Blocks.DIRT.getDefaultState());
                setBulk(world, col.down(), Blocks.GRASS_BLOCK.getDefaultState());
                boolean edge = Math.abs(dx) > RADIUS || Math.abs(dz) > RADIUS;
                // Ряды: через каждые 4 блока — междурядье пошире
                boolean row = !edge && Math.floorMod(dx, 4) != 0;
                if (row || (!edge && session.random.nextInt(3) != 0)) fill(world, col, col.up(2), CORN);
                if (edge) {
                    // По краю — тёмный лес из ёлок, за который не выйти
                    fill(world, col, col.up(4), Blocks.SPRUCE_LEAVES.getDefaultState());
                }
            }
        }
        // Петляющие тропинки
        for (int p = 0; p < 9; p++) {
            int x = session.random.nextInt(RADIUS * 2) - RADIUS;
            int z = session.random.nextInt(RADIUS * 2) - RADIUS;
            int dir = session.random.nextInt(4);
            for (int step = 0; step < 70; step++) {
                if (Math.abs(x) >= RADIUS || Math.abs(z) >= RADIUS) break;
                fill(world, center.add(x, 0, z), center.add(x, 2, z), AIR);
                setBulk(world, center.add(x, -1, z), Blocks.COARSE_DIRT.getDefaultState());
                if (session.random.nextInt(6) == 0) dir = (dir + (session.random.nextBoolean() ? 1 : 3)) % 4;
                Direction d = Direction.fromHorizontal(dir);
                x += d.getOffsetX();
                z += d.getOffsetZ();
            }
        }

        double a = session.random.nextDouble() * Math.PI * 2;
        BlockPos spawn = center.add((int) (Math.cos(a) * (RADIUS - 6)), 0, (int) (Math.sin(a) * (RADIUS - 6)));
        house = center.add((int) (-Math.cos(a) * (RADIUS - 8)), 0, (int) (-Math.sin(a) * (RADIUS - 8)));
        fill(world, spawn.add(-2, 0, -2), spawn.add(2, 2, 2), AIR);
        buildHouse(world);

        for (int i = 0; i < SCARECROWS; i++) {
            BlockPos pos = session.ringPos(center, 6, RADIUS - 4);
            ArmorStandEntity scarecrow = new ArmorStandEntity(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            scarecrow.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            scarecrow.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            scarecrow.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            scarecrow.setInvulnerable(true);
            session.add(scarecrow);
            scarecrows.add(scarecrow);
        }
        return spawn;
    }

    /** Дом фермера: доски, окна светятся, дверь распахнута, высокий шест с фонарём. */
    private void buildHouse(ServerWorld world) {
        fill(world, house.add(-5, 0, -5), house.add(5, 4, 5), AIR);
        fill(world, house.add(-4, -1, -4), house.add(4, -1, 4), Blocks.SPRUCE_PLANKS.getDefaultState());
        fill(world, house.add(-4, 0, -4), house.add(4, 3, 4), Blocks.STRIPPED_SPRUCE_LOG.getDefaultState());
        fill(world, house.add(-3, 0, -3), house.add(3, 2, 3), AIR);
        fill(world, house.add(-4, 4, -4), house.add(4, 4, 4), Blocks.DARK_OAK_PLANKS.getDefaultState());
        for (int x = -2; x <= 2; x += 4) {
            setBulk(world, house.add(x, 1, -4), Blocks.YELLOW_STAINED_GLASS.getDefaultState());
            setBulk(world, house.add(x, 1, 4), Blocks.YELLOW_STAINED_GLASS.getDefaultState());
            setBulk(world, house.add(-4, 1, x), Blocks.YELLOW_STAINED_GLASS.getDefaultState());
            setBulk(world, house.add(4, 1, x), Blocks.YELLOW_STAINED_GLASS.getDefaultState());
        }
        setBulk(world, house.add(0, 3, 0), Blocks.GLOWSTONE.getDefaultState());
        // Двери со всех сторон — чтобы не обходить дом по кукурузе
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos lower = house.offset(side, 4);
            setBulk(world, lower, AIR);
            setBulk(world, lower.up(), AIR);
            door(world, lower, Blocks.SPRUCE_DOOR, side.getOpposite(), true);
        }
        BlockPos pole = house.add(5, 0, 5);
        fill(world, pole, pole.up(10), Blocks.SPRUCE_FENCE.getDefaultState());
        setBulk(world, pole.up(11), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, false));
        exit = house;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(center, RADIUS + 7, -2, 13);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.cornfield.start").formatted(Formatting.GOLD), 10);
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 10 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, house.getX() + 5.5, house.getY() + 12, house.getZ() + 5.5, 1, 0.1, 0.1, 0.1, 0.0);
        }
        if (now % 10 != 0 || session.elapsed() < 200) return;
        for (ArmorStandEntity scarecrow : scarecrows) {
            if (scarecrow.isRemoved()) continue;
            ServerPlayerEntity target = session.nearestPlayer(scarecrow.getPos());
            if (target == null) continue;
            boolean watched = false;
            for (ServerPlayerEntity player : session.players()) {
                if (player.squaredDistanceTo(scarecrow) < 40 * 40 && Horror.isLookingAt(player, scarecrow, 0.6)) watched = true;
            }
            if (watched) continue;
            double dist = Math.sqrt(scarecrow.squaredDistanceTo(target));
            if (dist < 2.0) {
                attack(session, scarecrow, target);
                continue;
            }
            if (dist > 30 || session.random.nextInt(3) != 0) continue;
            Vec3d dir = target.getPos().subtract(scarecrow.getPos()).multiply(1, 0, 1).normalize();
            Vec3d to = scarecrow.getPos().add(dir.multiply(Math.min(STEP, dist - 1.2)));
            scarecrow.refreshPositionAndAngles(to.x, scarecrow.getY(), to.z, 0, 0);
            faceTowards(scarecrow, target.getEyePos());
            // Шорох стеблей — единственное, что выдаёт движение
            Horror.sound(target, SoundEvents.BLOCK_GRASS_BREAK, to.add(0, 1, 0), 0.9f, 0.6f);
        }
    }

    private void attack(DreamSession session, ArmorStandEntity scarecrow, ServerPlayerEntity player) {
        Horror.screamer(player, Horror.FACE_PALE);
        player.damage(session.world.getDamageSources().magic(), 4.0f);
        SanityManager.add(player, -4.0f);
        BlockPos away = session.ringPos(player.getBlockPos(), 22, 30);
        scarecrow.refreshPositionAndAngles(away.getX() + 0.5, center.getY(), away.getZ() + 0.5, 0, 0);
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (reachedExit(session, player, exit, 6.0)) return;
        if (session.now() % 160 == 0) {
            // Вороны и ветер в стеблях
            Horror.sound(player, SoundEvents.ENTITY_PARROT_IMITATE_PHANTOM, Horror.around(player, session.random.nextDouble() * 360, 10)
                    .add(0, 4, 0), 0.8f, 0.6f);
        }
    }
}
