package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamSession;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * "Дверь пробуждения": берёзовая дверь в светящейся раме на кварцевом основании.
 * Выход засчитывается, когда дверь открыта и игрок стоит в дверном проёме —
 * неважно, с какой стороны он подошёл (раньше засчитывался только проход с юга на север,
 * а при подходе с севера — мгновенно где угодно после открытия).
 */
final class WakeDoor {

    private WakeDoor() {}

    static void build(DreamSession session, BlockPos pos) {
        ServerWorld world = session.world;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.setBlockState(pos.add(x, -1, z), Blocks.SMOOTH_QUARTZ.getDefaultState());
            }
        }
        for (int dy = 0; dy <= 2; dy++) {
            world.setBlockState(pos.add(-1, dy, 0), Blocks.SEA_LANTERN.getDefaultState());
            world.setBlockState(pos.add(1, dy, 0), Blocks.SEA_LANTERN.getDefaultState());
        }
        world.setBlockState(pos.add(0, 2, 0), Blocks.SEA_LANTERN.getDefaultState());
        world.setBlockState(pos, Blocks.BIRCH_DOOR.getDefaultState()
                .with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        world.setBlockState(pos.up(), Blocks.BIRCH_DOOR.getDefaultState()
                .with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Воздух перед и за дверью, чтобы к ней можно было подойти с обеих сторон
        for (int dz : new int[]{-1, 1}) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos side = pos.add(0, dy, dz);
                if (!world.getBlockState(side).isAir()) world.setBlockState(side, Blocks.AIR.getDefaultState());
            }
        }

        ArmorStandEntity marker = new ArmorStandEntity(world, pos.getX() + 0.5, pos.getY() + 2.6, pos.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomName(Text.translatable("somnium.dream.wake_door").formatted(Formatting.WHITE, Formatting.BOLD));
        marker.setCustomNameVisible(true);
        session.add(marker);
    }

    static boolean isPassing(ServerWorld world, BlockPos door, ServerPlayerEntity player) {
        var state = world.getBlockState(door);
        if (!(state.getBlock() instanceof DoorBlock) || !state.get(DoorBlock.OPEN)) return false;
        return Math.abs(player.getX() - (door.getX() + 0.5)) < 0.8
                && Math.abs(player.getZ() - (door.getZ() + 0.5)) < 0.8
                && player.getY() > door.getY() - 0.6 && player.getY() < door.getY() + 1.2;
    }
}
