package com.somnium.mod.block;

import com.somnium.mod.dream.DreamPortalHelper;
import com.somnium.mod.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * Блок портала в мир снов — "внутренность" рамки из блоков сноведений.
 * Плоская мерцающая плёнка без коллизии (как портал ада): горизонтальная ось,
 * частицы портала, фоновый гул. Если соседняя рамка разрушена — вся плёнка
 * портала осыпается (см. DreamPortalHelper#clearPortal).
 */
public class DreamPortalBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;

    public DreamPortalBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getDefaultState().with(AXIS, Direction.Axis.X));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // Тонкая плёнка по центру блока, как у портала ада
        return state.get(AXIS) == Direction.Axis.X
                ? Block.createCuboidShape(6.0, 0.0, 0.0, 10.0, 16.0, 16.0)
                : Block.createCuboidShape(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty(); // сквозь портал проходят, как сквозь портал ада
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // Если сосед изменился и это не часть портала и не блок рамки — рамка, возможно,
        // разрушена. Перепроверяем рамку целиком; сломана — осыпаем весь портал.
        if (!world.isClient() && neighborState.getBlock() != this
                && neighborState.getBlock() != ModBlocks.DREAM_BLOCK
                && world instanceof ServerWorld serverWorld) {
            if (!DreamPortalHelper.isFrameIntact(serverWorld, pos, state.get(AXIS))) {
                DreamPortalHelper.clearPortal(serverWorld, pos);
            }
        }
        return state;
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        // Мерцание, как у портала ада, только с душами и блёстками
        if (random.nextInt(100) == 0) {
            world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.BLOCK_PORTAL_AMBIENT, SoundCategory.BLOCKS,
                    0.4f, random.nextFloat() * 0.4f + 0.7f, false);
        }
        for (int i = 0; i < 3; i++) {
            double px = pos.getX() + random.nextDouble();
            double py = pos.getY() + random.nextDouble();
            double pz = pos.getZ() + random.nextDouble();
            double vx = (random.nextDouble() - 0.5) * 0.4;
            double vy = (random.nextDouble() - 0.5) * 0.4;
            double vz = (random.nextDouble() - 0.5) * 0.4;
            world.addParticle(random.nextBoolean() ? ParticleTypes.PORTAL : ParticleTypes.END_ROD,
                    px, py, pz, vx, vy, vz);
        }
    }
}
