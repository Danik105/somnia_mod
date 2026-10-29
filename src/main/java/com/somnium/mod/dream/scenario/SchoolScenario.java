package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Пустая школа ночью: длинный коридор с зелёными стенами, шкафчики, восемь классов по бокам.
 * В пяти классах на кафедре лежат тетради — их надо собрать. В коридоре стоят Сталкеры:
 * пока на них смотрят, не двигаются. С каждой тетрадью их больше. Когда собраны все,
 * решётка в конце коридора поднимается, за ней светлая ниша — шаг туда будит.
 */
public final class SchoolScenario extends DreamScenario {

    private static final int LENGTH = 64;
    private static final int HEIGHT = 5;
    private static final int ROOMS_PER_SIDE = 4;
    private static final int ROOM = 16;
    private static final int NOTEBOOKS = 5;

    private static final BlockState PAINT = Blocks.GREEN_TERRACOTTA.getDefaultState();
    private static final BlockState PLASTER = Blocks.WHITE_TERRACOTTA.getDefaultState();
    private static final BlockState TILE_A = Blocks.WHITE_GLAZED_TERRACOTTA.getDefaultState();
    private static final BlockState TILE_B = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
    private static final BlockState PARQUET = Blocks.SPRUCE_PLANKS.getDefaultState();
    private static final BlockState CEILING = Blocks.SMOOTH_QUARTZ.getDefaultState();
    private static final BlockState LAMP = Blocks.PEARLESCENT_FROGLIGHT.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos c;
    private final Pickups notebooks = new Pickups();
    private final List<BlockPos> boards = new ArrayList<>();
    private final List<BlockPos> gate = new ArrayList<>();
    private final List<BlockPos> corridorLamps = new ArrayList<>();
    private BlockPos exit;
    private boolean open;
    private long nextBell;

    @Override
    public int areaRadius() {
        return LENGTH;
    }

    private BlockPos at(int x, int y, int z) {
        return c.add(x, y, z);
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        c = session.origin.add(-LENGTH / 2, 0, 0);

        // Сплошной блок здания: низ стен крашеный, верх побелён. Потом вырезаем комнаты
        fill(world, at(-1, -1, -14), at(LENGTH + 5, -1, 14), PARQUET);
        fill(world, at(-1, 0, -14), at(LENGTH + 5, 1, 14), PAINT);
        fill(world, at(-1, 2, -14), at(LENGTH + 5, HEIGHT - 1, 14), PLASTER);
        fill(world, at(-1, HEIGHT, -14), at(LENGTH + 5, HEIGHT, 14), CEILING);

        // Коридор: шахматная плитка, шкафчики вдоль стен, лампы
        fill(world, at(0, 0, -2), at(LENGTH - 1, HEIGHT - 1, 2), AIR);
        for (int x = 0; x < LENGTH; x++) {
            for (int z = -2; z <= 2; z++) setBulk(world, at(x, -1, z), (x + z) % 2 == 0 ? TILE_A : TILE_B);
            if (x % 6 == 3) {
                setBulk(world, at(x, HEIGHT, 0), LAMP);
                corridorLamps.add(at(x, HEIGHT, 0));
            }
        }
        for (int x = 1; x < LENGTH - 1; x++) {
            if (x % ROOM >= 9 && x % ROOM <= 12) {
                for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                    int z = side == Direction.NORTH ? -2 : 2;
                    setBulk(world, at(x, 0, z), Blocks.IRON_BLOCK.getDefaultState());
                    setBulk(world, at(x, 1, z), Blocks.IRON_TRAPDOOR.getDefaultState()
                            .with(TrapdoorBlock.FACING, side.getOpposite()).with(TrapdoorBlock.OPEN, true));
                }
            }
        }

        // Классы
        List<int[]> rooms = new ArrayList<>();
        for (int k = 0; k < ROOMS_PER_SIDE; k++) {
            rooms.add(new int[]{k, -1});
            rooms.add(new int[]{k, 1});
        }
        Collections.shuffle(rooms, session.random);
        for (int r = 0; r < rooms.size(); r++) {
            buildClassroom(session, rooms.get(r)[0], rooms.get(r)[1], r < NOTEBOOKS, session.random.nextInt(4) == 0);
        }

        // Конец коридора: решётка, за ней ниша со светом
        for (int z = -1; z <= 1; z++) {
            for (int y = 0; y <= 2; y++) {
                BlockPos bar = at(LENGTH, y, z);
                setBulk(world, bar, Blocks.IRON_BARS.getDefaultState());
                gate.add(bar);
            }
        }
        fill(world, at(LENGTH + 1, 0, -1), at(LENGTH + 3, 2, 1), AIR);
        fill(world, at(LENGTH + 4, 0, -1), at(LENGTH + 4, 2, 1), Blocks.SEA_LANTERN.getDefaultState());
        exit = at(LENGTH + 2, 0, 0);
        sign(world, at(LENGTH - 1, 3, 0), Blocks.SPRUCE_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.WEST),
                Text.empty(), tr("somnium.school.exit_sign").formatted(Formatting.DARK_RED));

        // Сталкеры в дальней половине коридора
        for (int i = 0; i < 2; i++) {
            session.spawnMob(ModEntities.STALKER, at(LENGTH / 2 + 8 + i * 12, 0, session.random.nextInt(3) - 1), 90f);
        }
        nextBell = session.now() + 900;
        return at(2, 0, 0);
    }

    private void buildClassroom(DreamSession session, int k, int side, boolean notebook, boolean dark) {
        ServerWorld world = session.world;
        int x0 = k * ROOM + 1;
        int x1 = x0 + ROOM - 3;
        int zNear = side * 4;
        int zFar = side * 12;
        int zMin = Math.min(zNear, zFar);
        int zMax = Math.max(zNear, zFar);
        fill(world, at(x0, 0, zMin), at(x1, HEIGHT - 1, zMax), AIR);
        // Дверной проём со стороны коридора и деревянный косяк
        int doorX = k * ROOM + 4;
        fill(world, at(doorX, 0, side * 3), at(doorX + 1, 2, side * 3), AIR);
        Direction toCorridor = side < 0 ? Direction.SOUTH : Direction.NORTH;

        // Доска на дальней стене и надпись мелом
        int boardZ = zFar + side;
        int mid = (x0 + x1) / 2;
        fill(world, at(mid - 3, 1, boardZ), at(mid + 3, 3, boardZ), Blocks.BLACK_CONCRETE.getDefaultState());
        BlockPos board = at(mid, 2, zFar);
        sign(world, board, Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, toCorridor),
                tr("somnium.school.board." + (1 + session.random.nextInt(6))).formatted(Formatting.WHITE));
        boards.add(board);

        // Учительский стол и кафедра
        int deskZ = zFar - side * 2;
        fill(world, at(mid - 1, 0, deskZ), at(mid + 1, 0, deskZ), Blocks.DARK_OAK_PLANKS.getDefaultState());
        if (notebook) {
            BlockPos lectern = at(mid + 3, 0, deskZ);
            setBulk(world, lectern, Blocks.LECTERN.getDefaultState()
                    .with(LecternBlock.FACING, toCorridor).with(LecternBlock.HAS_BOOK, true));
            notebooks.add(lectern);
        }

        // Парты: забор + плита, перед ними стул-ступенька
        for (int row = 0; row < 2; row++) {
            int z = deskZ - side * (2 + row * 2);
            for (int col = 0; col < 4; col++) {
                int x = x0 + 2 + col * 3;
                setBulk(world, at(x, 0, z), Blocks.OAK_FENCE.getDefaultState());
                setBulk(world, at(x, 1, z), Blocks.OAK_PRESSURE_PLATE.getDefaultState());
                if (session.random.nextInt(7) != 0) {
                    setBulk(world, at(x, 0, z - side), Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, toCorridor));
                }
            }
        }
        if (!dark) {
            setBulk(world, at(mid - 3, HEIGHT, (zNear + zFar) / 2), LAMP);
            setBulk(world, at(mid + 3, HEIGHT, (zNear + zFar) / 2), LAMP);
        }
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(c.getX() - 1, c.getY() - 1, c.getZ() - 14, c.getX() + LENGTH + 5, c.getY() + HEIGHT, c.getZ() + 14);
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return session.spawn().add(0, 0, session.random.nextInt(3) - 1);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        Horror.sound(player, SoundEvents.BLOCK_BELL_USE, player.getEyePos().add(20, 2, 0), 1.0f, 0.5f);
        DreamHints.show(player, tr("somnium.hint.school.start", NOTEBOOKS).formatted(Formatting.GRAY), 10);
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (notebooks.take(pos)) {
            ServerWorld world = session.world;
            setBulk(world, pos, world.getBlockState(pos).with(LecternBlock.HAS_BOOK, false));
            session.sound(pos, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.BLOCKS, 1.0f, 0.8f);
            int left = notebooks.remaining();
            // Мел на всех досках пишет, сколько осталось
            for (BlockPos board : boards) {
                sign(world, board, world.getBlockState(board), tr("somnium.school.board.left", left).formatted(Formatting.WHITE));
            }
            // Ещё один Сталкер появляется где-то за спиной
            BlockPos behind = BlockPos.ofFloored(Horror.behind(player, 10));
            session.spawnMob(ModEntities.STALKER, session.groundNear(behind.getX(), c.getY(), behind.getZ()), 0f);
            if (left > 0) {
                session.actionBar(tr("somnium.hint.school.notebook", left).formatted(Formatting.YELLOW));
            } else {
                openGate(session);
            }
            return ActionResult.SUCCESS;
        }
        BlockState state = session.world.getBlockState(pos);
        return state.getBlock() instanceof AbstractSignBlock || state.isOf(Blocks.LECTERN) ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    private void openGate(DreamSession session) {
        open = true;
        for (BlockPos bar : gate) set(session.world, bar, AIR);
        session.sound(gate.get(gate.size() / 2), SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 2.0f, 0.5f);
        session.sound(exit, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT, 2.0f, 1.0f);
        session.hint(tr("somnium.hint.school.open").formatted(Formatting.WHITE), 8);
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 10 == 0) notebooks.sparkle(world, ParticleTypes.ENCHANT);
        if (open && now % 10 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, exit.getX() + 0.5, exit.getY() + 1.2, exit.getZ() + 0.5, 4, 0.6, 0.8, 0.6, 0.01);
        }
        // Звонок на урок, которого не будет. После звонка коридор на пару секунд гаснет
        if (now >= nextBell) {
            nextBell = now + 900 + session.random.nextInt(600);
            session.sound(at(LENGTH / 2, 3, 0), SoundEvents.BLOCK_BELL_USE, SoundCategory.AMBIENT, 4.0f, 0.6f);
            for (BlockPos lamp : corridorLamps) set(world, lamp, CEILING);
            Horror.later(50, () -> {
                for (BlockPos lamp : corridorLamps) set(world, lamp, LAMP);
            });
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (open) reachedExit(session, player, exit, 2.5);
    }
}
