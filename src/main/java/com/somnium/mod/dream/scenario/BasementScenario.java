package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.CrawlerEntity;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.registry.ModEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChainBlock;
import net.minecraft.block.LightBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Подвал: кромешная тьма, низкий потолок, трубы, капает вода. В кармане коробок спичек.
 * Спичка горит 10 секунд и освещает всё вокруг — Ползуны на свету сгорают, а в темноте
 * подбираются. Спички на полу можно подобрать. Выход — лестница наверх под лампой
 * в дальнем углу лабиринта.
 */
public final class BasementScenario extends DreamScenario {

    private static final int CELLS = 8;
    private static final int CELL = 3;
    private static final int HEIGHT = 3;
    private static final int START_MATCHES = 8;
    private static final int BURN = 200;
    private static final String MATCH_TAG = "somnium_match";

    private static final BlockState WALL = Blocks.STONE_BRICKS.getDefaultState();
    private static final BlockState FLOOR = Blocks.POLISHED_ANDESITE.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState GLOW = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 15);

    private BlockPos c;
    private MazeGenerator maze;
    private BlockPos exit;
    private final Map<UUID, Long> burningUntil = new HashMap<>();
    private final Map<UUID, BlockPos> glowAt = new HashMap<>();

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
                boolean wall = maze.isWall(gx, gz) && (border || session.random.nextInt(8) != 0);
                for (int bx = 0; bx < CELL; bx++) {
                    for (int bz = 0; bz < CELL; bz++) {
                        BlockPos col = c.add(gx * CELL + bx, 0, gz * CELL + bz);
                        setBulk(world, col.down(), session.random.nextInt(10) == 0 ? Blocks.MUD.getDefaultState() : FLOOR);
                        BlockState wallState = session.random.nextInt(6) == 0 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState()
                                : session.random.nextInt(5) == 0 ? Blocks.MOSSY_STONE_BRICKS.getDefaultState() : WALL;
                        for (int y = 0; y < HEIGHT; y++) setBulk(world, col.up(y), wall ? wallState : AIR);
                        setBulk(world, col.up(HEIGHT), Blocks.DEEPSLATE_TILES.getDefaultState());
                        // Трубы под потолком и паутина по углам
                        if (!wall && bx == 1 && gz % 2 == 1 && session.random.nextInt(3) != 0) {
                            setBulk(world, col.up(HEIGHT - 1), Blocks.CHAIN.getDefaultState().with(ChainBlock.AXIS, Direction.Axis.Z));
                        }
                        if (!wall && session.random.nextInt(60) == 0) setBulk(world, col.up(HEIGHT - 1), Blocks.COBWEB.getDefaultState());
                    }
                }
            }
        }
        BlockPos spawn = maze.findSpawnPosition(c, 0);
        exit = maze.findExitPosition(spawn, c, 0);
        // Выход: открытый люк в потолке, сверху падает свет
        setBulk(world, exit.up(HEIGHT), Blocks.SEA_LANTERN.getDefaultState());
        setBulk(world, exit.up(HEIGHT - 1).north(), Blocks.SPRUCE_TRAPDOOR.getDefaultState()
                .with(TrapdoorBlock.OPEN, true).with(TrapdoorBlock.HALF, BlockHalf.TOP));

        // Рассыпанные спички и спящие Ползуны
        for (BlockPos spot : maze.findMonsterSpawnPositions(c, 0, 12)) {
            if (spot.getSquaredDistance(spawn) < 10 * 10 || spot.equals(exit)) continue;
            if (session.random.nextBoolean()) {
                ItemEntity item = new ItemEntity(world, spot.getX() + 0.5, spot.getY() + 0.3, spot.getZ() + 0.5, matches(2), 0, 0, 0);
                item.setPickupDelay(0);
                session.add(item);
            } else {
                session.spawnMob(ModEntities.CRAWLER, spot, session.random.nextFloat() * 360f);
            }
        }
        return spawn;
    }

    private static ItemStack matches(int count) {
        ItemStack stack = new ItemStack(Items.STICK, count);
        stack.setCustomName(tr("somnium.item.match").formatted(Formatting.GOLD));
        stack.getOrCreateNbt().putBoolean(MATCH_TAG, true);
        return stack;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        int size = (CELLS * 2 + 1) * CELL;
        return new BlockBox(c.getX(), c.getY() - 1, c.getZ(), c.getX() + size, c.getY() + HEIGHT, c.getZ() + size);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        player.getInventory().insertStack(matches(START_MATCHES));
        DreamHints.show(player, tr("somnium.hint.basement.start").formatted(Formatting.GRAY), 10);
    }

    @Override
    public ActionResult onUseItem(DreamSession session, ServerPlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.getNbt() == null || !stack.getNbt().getBoolean(MATCH_TAG)) return ActionResult.PASS;
        if (burningUntil.getOrDefault(player.getUuid(), 0L) > session.now()) return ActionResult.SUCCESS;
        stack.decrement(1);
        burningUntil.put(player.getUuid(), session.now() + BURN);
        session.sound(player.getBlockPos(), SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        player.sendMessage(tr("somnium.hint.basement.lit", count(player)).formatted(Formatting.GOLD), true);
        return ActionResult.SUCCESS;
    }

    private static int count(ServerPlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.getNbt() != null && s.getNbt().getBoolean(MATCH_TAG)) total += s.getCount();
        }
        return total;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 6 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, exit.getX() + 0.5, exit.getY() + 1.5, exit.getZ() + 0.5, 1, 0.3, 0.6, 0.3, 0.0);
        }
        // Ползуны живут минуту: подселяем новых в темноте рядом с игроками
        if (now % 240 == 0) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null && world.getEntitiesByClass(CrawlerEntity.class, new Box(player.getBlockPos()).expand(30), session::owns).size() < 4) {
                List<BlockPos> spots = maze.findMonsterSpawnPositions(c, 0, 4);
                for (BlockPos spot : spots) {
                    double d = spot.getSquaredDistance(player.getBlockPos());
                    if (d > 12 * 12 && d < 28 * 28) {
                        session.spawnMob(ModEntities.CRAWLER, spot, 0f);
                        break;
                    }
                }
            }
        }
        // Котёл где-то гудит
        if (now % 180 == 0) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null) {
                Horror.sound(player, SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, Horror.around(player, session.random.nextDouble() * 360, 8),
                        1.0f, 0.4f);
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (reachedExit(session, player, exit, 2.0)) {
            clearGlow(session, player.getUuid());
            return;
        }
        ServerWorld world = session.world;
        UUID id = player.getUuid();
        long now = session.now();
        boolean burning = burningUntil.getOrDefault(id, 0L) > now;
        if (burning && now % 4 == 0) {
            BlockPos head = player.getBlockPos().up();
            if (!head.equals(glowAt.get(id)) && world.getBlockState(head).isAir()) {
                clearGlow(session, id);
                setBulk(world, head, GLOW);
                glowAt.put(id, head.toImmutable());
            }
            world.spawnParticles(ParticleTypes.SMALL_FLAME, player.getX(), player.getEyeY() - 0.3, player.getZ(), 1, 0.1, 0.05, 0.1, 0.0);
        } else if (!burning && glowAt.containsKey(id)) {
            clearGlow(session, id);
            player.playSound(SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 1.5f);
            player.sendMessage(tr("somnium.hint.basement.out").formatted(Formatting.DARK_GRAY), true);
        }
        if (now % 15 == 0) {
            world.spawnParticles(player, ParticleTypes.DRIPPING_WATER, false, player.getX(), player.getY() + HEIGHT - 0.1, player.getZ(),
                    2, 3, 0, 3, 0.0);
        }
    }

    private void clearGlow(DreamSession session, UUID id) {
        BlockPos old = glowAt.remove(id);
        if (old != null && session.world.getBlockState(old).isOf(Blocks.LIGHT)) setBulk(session.world, old, AIR);
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        clearGlow(session, player.getUuid());
        burningUntil.remove(player.getUuid());
    }
}
