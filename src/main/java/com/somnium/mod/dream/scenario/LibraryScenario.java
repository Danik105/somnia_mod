package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LecternBlock;
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
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тихая библиотека: лабиринт из книжных шкафов под тёмным сводом, редкие люстры.
 * Здесь нельзя шуметь. Бег и прыжки поднимают шкалу шума; когда она полная, Библиотекарь
 * слышит и приходит — за спиной встаёт тень. Найди три раскрытые книги на кафедрах,
 * тогда в дальнем углу зажжётся свет читального зала.
 */
public final class LibraryScenario extends DreamScenario {

    private static final int CELLS = 10;
    private static final int CELL = 3;
    private static final int SHELF = 4;
    private static final int HEIGHT = 7;
    private static final int BOOKS = 3;

    private static final BlockState SHELF_BLOCK = Blocks.BOOKSHELF.getDefaultState();
    private static final BlockState FLOOR = Blocks.DARK_OAK_PLANKS.getDefaultState();
    private static final BlockState CARPET = Blocks.RED_CARPET.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos c;
    private MazeGenerator maze;
    private final Pickups books = new Pickups();
    private final Map<UUID, Float> noise = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();
    private BlockPos exit;
    private boolean open;

    @Override
    public int areaRadius() {
        return (CELLS * 2 + 1) * CELL;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        int size = (CELLS * 2 + 1) * CELL;
        c = session.origin.add(-size / 2, 0, -size / 2);
        maze = new MazeGenerator(CELLS, CELLS, CELL, session.random);
        maze.generate();

        for (int gx = 0; gx < CELLS * 2 + 1; gx++) {
            for (int gz = 0; gz < CELLS * 2 + 1; gz++) {
                boolean border = gx == 0 || gz == 0 || gx == CELLS * 2 || gz == CELLS * 2;
                // Лишние проходы, чтобы лабиринт не был жестоким
                boolean wall = maze.isWall(gx, gz) && (border || session.random.nextInt(9) != 0);
                for (int bx = 0; bx < CELL; bx++) {
                    for (int bz = 0; bz < CELL; bz++) {
                        BlockPos col = c.add(gx * CELL + bx, 0, gz * CELL + bz);
                        setBulk(world, col.down(), wall ? FLOOR : (bx == 1 && bz == 1 ? Blocks.RED_WOOL.getDefaultState() : FLOOR));
                        for (int y = 0; y < HEIGHT; y++) {
                            BlockState state = AIR;
                            if (wall && y < SHELF) state = SHELF_BLOCK;
                            else if (wall && y == SHELF) state = Blocks.DARK_OAK_SLAB.getDefaultState();
                            else if (border) state = Blocks.DARK_OAK_LOG.getDefaultState();
                            setBulk(world, col.up(y), state);
                        }
                        if (!wall && bx == 1 && bz == 1 && session.random.nextInt(4) == 0) setBulk(world, col, CARPET);
                        setBulk(world, col.up(HEIGHT), Blocks.DARK_OAK_PLANKS.getDefaultState());
                    }
                }
            }
        }
        // Редкие люстры на цепях: светлые островки между тёмными проходами
        for (int gx = 1; gx < CELLS * 2; gx += 4) {
            for (int gz = 1; gz < CELLS * 2; gz += 4) {
                if (maze.isWall(gx, gz)) continue;
                BlockPos top = c.add(gx * CELL + 1, HEIGHT - 1, gz * CELL + 1);
                setBulk(world, top, Blocks.CHAIN.getDefaultState());
                setBulk(world, top.down(), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
            }
        }

        BlockPos spawn = maze.findSpawnPosition(c, 0);
        exit = maze.findExitPosition(spawn, c, 0);
        List<BlockPos> spots = maze.findMonsterSpawnPositions(c, 0, BOOKS + 6);
        int placed = 0;
        for (BlockPos spot : spots) {
            if (spot.equals(spawn) || spot.equals(exit) || spot.getSquaredDistance(spawn) < 15 * 15) continue;
            if (placed < BOOKS) {
                setBulk(world, spot, Blocks.LECTERN.getDefaultState()
                        .with(LecternBlock.FACING, Direction.fromHorizontal(session.random.nextInt(4))).with(LecternBlock.HAS_BOOK, true));
                setBulk(world, spot.up(HEIGHT - 1), Blocks.CHAIN.getDefaultState());
                setBulk(world, spot.up(HEIGHT - 2), Blocks.SOUL_LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
                books.add(spot);
                placed++;
            } else {
                session.spawnMob(ModEntities.LURKING_SHADE, spot, 0f);
            }
        }
        sign(world, spawn.north(), Blocks.DARK_OAK_SIGN.getDefaultState(),
                Text.empty(), tr("somnium.library.silence").formatted(Formatting.DARK_RED));
        return spawn.south();
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        int size = (CELLS * 2 + 1) * CELL;
        return new BlockBox(c.getX(), c.getY() - 1, c.getZ(), c.getX() + size, c.getY() + HEIGHT, c.getZ() + size);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.library.start", BOOKS).formatted(Formatting.GRAY), 10);
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = session.world.getBlockState(pos);
        if (books.take(pos)) {
            setBulk(session.world, pos, state.with(LecternBlock.HAS_BOOK, false));
            session.sound(pos, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.BLOCKS, 1.0f, 0.6f);
            player.sendMessage(tr("somnium.library.page." + (1 + session.random.nextInt(5))).formatted(Formatting.GRAY, Formatting.ITALIC), false);
            if (books.remaining() > 0) {
                session.actionBar(tr("somnium.hint.library.book", books.remaining()).formatted(Formatting.GOLD));
            } else {
                open = true;
                setBulk(session.world, exit.down(), Blocks.SEA_LANTERN.getDefaultState());
                session.sound(exit, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT, 3.0f, 1.0f);
                session.hint(tr("somnium.hint.library.open").formatted(Formatting.WHITE), 8);
            }
            return ActionResult.SUCCESS;
        }
        if (state.isOf(Blocks.LECTERN) || state.getBlock() instanceof net.minecraft.block.AbstractSignBlock) return ActionResult.SUCCESS;
        return ActionResult.PASS;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 10 == 0) books.sparkle(world, ParticleTypes.ENCHANT);
        if (open && now % 5 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, exit.getX() + 0.5, exit.getY() + 0.5, exit.getZ() + 0.5, 3, 0.3, 2.0, 0.3, 0.01);
        }
        // Где-то в шкафах сама переворачивается страница или падает книга
        if (now % 140 == 0) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null) {
                Vec3d at = Horror.around(player, session.random.nextDouble() * 360, 6 + session.random.nextInt(6)).add(0, 1.5, 0);
                Horror.sound(player, session.random.nextBoolean() ? SoundEvents.ITEM_BOOK_PAGE_TURN : SoundEvents.ITEM_BOOK_PUT, at, 1.0f, 0.7f);
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (open && reachedExit(session, player, exit, 2.0)) return;
        UUID id = player.getUuid();
        float level = noise.getOrDefault(id, 0f);
        boolean ground = player.isOnGround();
        if (player.isSprinting()) level += 1.4f;
        else if (!player.isSneaking() && player.getVelocity().horizontalLengthSquared() > 0.003) level += 0.25f;
        if (Boolean.TRUE.equals(wasOnGround.get(id)) && !ground && player.getVelocity().y > 0.2) level += 9f;
        wasOnGround.put(id, ground);
        level = Math.max(0f, level - (player.isSneaking() ? 1.0f : 0.45f));

        if (level >= 100f) {
            level = 35f;
            heard(session, player);
        } else if (level > 65f && session.now() % 30 == 0) {
            Horror.sound(player, SoundEvents.ENTITY_SNIFFER_SNIFFING, Horror.behind(player, 3).add(0, 1.4, 0), 0.8f, 0.5f);
            player.sendMessage(tr("somnium.library.shh").formatted(Formatting.DARK_GRAY, Formatting.ITALIC), false);
        }
        noise.put(id, level);
        if (session.now() % 5 == 0) {
            int bars = Math.round(level / 10f);
            Text meter = Text.literal("▮".repeat(bars)).formatted(level > 65 ? Formatting.RED : Formatting.GRAY)
                    .append(Text.literal("▯".repeat(10 - bars)).formatted(Formatting.DARK_GRAY));
            player.sendMessage(tr("somnium.hint.library.noise").formatted(Formatting.GRAY).append(" ").append(meter), true);
        }
    }

    /** Библиотекарь услышал: свет гаснет, за спиной встаёт тень. */
    private void heard(DreamSession session, ServerPlayerEntity player) {
        Horror.sound(player, SoundEvents.ENTITY_WARDEN_ROAR, Horror.behind(player, 6).add(0, 1, 0), 0.7f, 1.4f);
        player.sendMessage(tr("somnium.hint.library.heard").formatted(Formatting.DARK_RED), true);
        BlockPos behind = BlockPos.ofFloored(Horror.behind(player, 4));
        session.spawnMob(ModEntities.LURKING_SHADE, session.groundNear(behind.getX(), player.getBlockY(), behind.getZ()), player.getYaw());
        if (session.random.nextInt(3) == 0) Horror.screamer(player, Horror.FACE_PALE);
        SanityManager.add(player, -3.0f);
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        noise.remove(player.getUuid());
        wasOnGround.remove(player.getUuid());
    }
}
