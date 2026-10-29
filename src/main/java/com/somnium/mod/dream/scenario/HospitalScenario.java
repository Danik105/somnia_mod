package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.CrawlerEntity;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.WallMountLocation;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Больница: ночное отделение, кафельный коридор, палаты с пустыми койками. Половина палат
 * без света, в темноте живут Ползуны (на свету сгорают). Лифт в конце коридора обесточен:
 * надо найти в палатах три рубильника. Кардиомонитор пищит чаще, когда Ползун рядом.
 */
public final class HospitalScenario extends DreamScenario {

    private static final int WARDS_PER_SIDE = 7;
    private static final int WARD = 8;
    private static final int LENGTH = WARDS_PER_SIDE * WARD;
    private static final int HEIGHT = 4;
    private static final int SWITCHES = 3;

    private static final BlockState TILE = Blocks.WHITE_CONCRETE.getDefaultState();
    private static final BlockState TILE_LOW = Blocks.LIGHT_BLUE_TERRACOTTA.getDefaultState();
    private static final BlockState FLOOR = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
    private static final BlockState FLOOR_DIRTY = Blocks.GRAY_CONCRETE_POWDER.getDefaultState();
    private static final BlockState CEILING = Blocks.SMOOTH_STONE.getDefaultState();
    private static final BlockState LAMP = Blocks.SEA_LANTERN.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos c;
    private final Pickups switches = new Pickups();
    private final List<BlockPos> lamps = new ArrayList<>();
    private final List<BlockPos> darkWards = new ArrayList<>();
    private final List<BlockPos> liftDoor = new ArrayList<>();
    private BlockPos exit;
    private boolean powered;
    private final Map<UUID, Long> nextBeep = new HashMap<>();

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

        fill(world, at(-1, -1, -11), at(LENGTH + 5, -1, 11), FLOOR);
        fill(world, at(-1, 0, -11), at(LENGTH + 5, 0, 11), TILE_LOW);
        fill(world, at(-1, 1, -11), at(LENGTH + 5, HEIGHT - 1, 11), TILE);
        fill(world, at(-1, HEIGHT, -11), at(LENGTH + 5, HEIGHT, 11), CEILING);

        fill(world, at(0, 0, -2), at(LENGTH - 1, HEIGHT - 1, 2), AIR);
        for (int x = 0; x < LENGTH; x++) {
            for (int z = -2; z <= 2; z++) {
                if (session.random.nextInt(14) == 0) setBulk(world, at(x, -1, z), FLOOR_DIRTY);
            }
            if (x % 5 == 2) {
                setBulk(world, at(x, HEIGHT, 0), LAMP);
                lamps.add(at(x, HEIGHT, 0));
            }
            // Поручень вдоль стен
            if (x % WARD != 3) {
                setBulk(world, at(x, 1, -3), Blocks.STRIPPED_BIRCH_LOG.getDefaultState());
                setBulk(world, at(x, 1, 3), Blocks.STRIPPED_BIRCH_LOG.getDefaultState());
            }
        }
        // Брошенные каталки и кресло-каталка в коридоре
        for (int i = 0; i < 4; i++) {
            int x = 6 + session.random.nextInt(LENGTH - 12);
            int z = session.random.nextBoolean() ? -2 : 2;
            setBulk(world, at(x, 0, z), Blocks.IRON_BARS.getDefaultState());
            setBulk(world, at(x + 1, 0, z), Blocks.IRON_BARS.getDefaultState());
            setBulk(world, at(x, 1, z), Blocks.WHITE_CARPET.getDefaultState());
            setBulk(world, at(x + 1, 1, z), Blocks.WHITE_CARPET.getDefaultState());
        }

        List<int[]> wards = new ArrayList<>();
        for (int k = 0; k < WARDS_PER_SIDE; k++) {
            wards.add(new int[]{k, -1});
            wards.add(new int[]{k, 1});
        }
        Collections.shuffle(wards, session.random);
        for (int i = 0; i < wards.size(); i++) {
            // Рубильники только в тёмных палатах: за ними придётся зайти в темноту
            boolean hasSwitch = i < SWITCHES;
            boolean dark = hasSwitch || session.random.nextInt(5) < 2;
            buildWard(session, wards.get(i)[0], wards.get(i)[1], hasSwitch, dark);
        }

        // Лифт в торце коридора: закрытые створки, за ними кабина
        fill(world, at(LENGTH + 1, 0, -1), at(LENGTH + 3, 2, 1), AIR);
        fill(world, at(LENGTH + 1, -1, -1), at(LENGTH + 3, -1, 1), Blocks.POLISHED_DEEPSLATE.getDefaultState());
        fill(world, at(LENGTH + 4, 0, -1), at(LENGTH + 4, 2, 1), Blocks.IRON_BLOCK.getDefaultState());
        for (int z = -1; z <= 1; z++) {
            for (int y = 0; y <= 2; y++) {
                BlockPos door = at(LENGTH, y, z);
                setBulk(world, door, Blocks.IRON_BLOCK.getDefaultState());
                liftDoor.add(door);
            }
        }
        setBulk(world, at(LENGTH + 2, HEIGHT - 1, 0), Blocks.REDSTONE_LAMP.getDefaultState());
        sign(world, at(LENGTH - 1, 3, 0), Blocks.BIRCH_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.WEST),
                tr("somnium.hospital.lift.1"), tr("somnium.hospital.lift.2").formatted(Formatting.DARK_RED));
        exit = at(LENGTH + 2, 0, 0);
        return at(2, 0, 0);
    }

    private void buildWard(DreamSession session, int k, int side, boolean hasSwitch, boolean dark) {
        ServerWorld world = session.world;
        int x0 = k * WARD + 1;
        int x1 = x0 + WARD - 2;
        int zNear = side * 4;
        int zFar = side * 10;
        fill(world, at(x0, 0, Math.min(zNear, zFar)), at(x1, HEIGHT - 1, Math.max(zNear, zFar)), AIR);
        int doorX = k * WARD + 3;
        fill(world, at(doorX, 0, side * 3), at(doorX, 1, side * 3), AIR);
        Direction toCorridor = side < 0 ? Direction.SOUTH : Direction.NORTH;

        // Две койки изголовьем к дальней стене, между ними тумбочка
        for (int bx : new int[]{x0 + 1, x1 - 1}) {
            BlockPos head = at(bx, 0, zFar);
            BlockPos foot = at(bx, 0, zFar - side);
            BlockState bed = Blocks.WHITE_BED.getDefaultState().with(BedBlock.FACING, toCorridor.getOpposite());
            setBulk(world, head, bed.with(BedBlock.PART, BedPart.HEAD));
            setBulk(world, foot, bed.with(BedBlock.PART, BedPart.FOOT));
            if (session.random.nextInt(3) == 0) setBulk(world, at(bx, -1, zFar - side * 2), Blocks.RED_CONCRETE_POWDER.getDefaultState());
        }
        setBulk(world, at((x0 + x1) / 2, 0, zFar), Blocks.BARREL.getDefaultState());
        setBulk(world, at((x0 + x1) / 2, 1, zFar), Blocks.BREWING_STAND.getDefaultState());
        // Капельница
        setBulk(world, at(x0 + 2, 0, zFar - side * 2), Blocks.IRON_BARS.getDefaultState());
        setBulk(world, at(x0 + 2, 1, zFar - side * 2), Blocks.IRON_BARS.getDefaultState());
        setBulk(world, at(x0 + 2, 2, zFar - side * 2), Blocks.GLASS_PANE.getDefaultState());
        // Окно в чёрную ночь
        setBulk(world, at((x0 + x1) / 2, 2, zFar + side), Blocks.BLACK_STAINED_GLASS.getDefaultState());

        BlockPos center = at((x0 + x1) / 2, 0, (zNear + zFar) / 2);
        if (hasSwitch) {
            // Рубильник на боковой стене
            BlockPos lever = at(x1, 1, (zNear + zFar) / 2);
            setBulk(world, lever, Blocks.LEVER.getDefaultState()
                    .with(LeverBlock.FACE, WallMountLocation.WALL).with(LeverBlock.FACING, Direction.WEST));
            switches.add(lever);
        }
        if (dark) {
            darkWards.add(center);
        } else {
            setBulk(world, at((x0 + x1) / 2, HEIGHT, (zNear + zFar) / 2), LAMP);
            lamps.add(at((x0 + x1) / 2, HEIGHT, (zNear + zFar) / 2));
        }
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(c.getX() - 1, c.getY() - 1, c.getZ() - 11, c.getX() + LENGTH + 5, c.getY() + HEIGHT, c.getZ() + 11);
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return session.spawn().add(0, 0, session.random.nextInt(3) - 1);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.hospital.start", SWITCHES).formatted(Formatting.GRAY), 10);
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        ServerWorld world = session.world;
        BlockState state = world.getBlockState(pos);
        if (switches.take(pos)) {
            setBulk(world, pos, state.with(LeverBlock.POWERED, true));
            session.sound(pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1.0f, 0.6f);
            session.sound(pos, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 0.6f, 0.5f);
            int left = switches.remaining();
            if (left > 0) {
                session.actionBar(tr("somnium.hint.hospital.switch", left).formatted(Formatting.YELLOW));
                // Ток пошёл не туда: свет по всему отделению гаснет на пару секунд
                for (BlockPos lamp : lamps) set(world, lamp, CEILING);
                Horror.later(60, () -> {
                    for (BlockPos lamp : lamps) set(world, lamp, LAMP);
                });
                spawnCrawlerNear(session, player);
            } else {
                powerLift(session);
            }
            return ActionResult.SUCCESS;
        }
        if (state.getBlock() instanceof LeverBlock || state.getBlock() instanceof AbstractSignBlock) return ActionResult.SUCCESS;
        return ActionResult.PASS;
    }

    private void powerLift(DreamSession session) {
        powered = true;
        ServerWorld world = session.world;
        for (BlockPos door : liftDoor) set(world, door, AIR);
        set(world, at(LENGTH + 2, HEIGHT - 1, 0), LAMP);
        session.sound(exit, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 3.0f, 1.2f);
        session.sound(exit, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT, 2.0f, 1.0f);
        session.hint(tr("somnium.hint.hospital.lift").formatted(Formatting.WHITE), 8);
    }

    private void spawnCrawlerNear(DreamSession session, ServerPlayerEntity player) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos ward : darkWards) {
            double d = ward.getSquaredDistance(player.getBlockPos());
            if (d > 36 && d < bestDist) {
                bestDist = d;
                best = ward;
            }
        }
        if (best != null) session.spawnMob(ModEntities.CRAWLER, best, session.random.nextFloat() * 360f);
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 10 == 0) switches.sparkle(world, ParticleTypes.ELECTRIC_SPARK);
        if (powered && now % 10 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, exit.getX() + 0.5, exit.getY() + 1.2, exit.getZ() + 0.5, 4, 0.6, 0.8, 0.6, 0.01);
        }
        // Мигающие лампы
        if (now % 25 == 0 && !lamps.isEmpty()) {
            BlockPos lamp = lamps.get(session.random.nextInt(lamps.size()));
            set(world, lamp, CEILING);
            Horror.later(4 + session.random.nextInt(8), () -> set(world, lamp, LAMP));
        }
        // Ползуны живут минуту — подселяем новых в тёмные палаты рядом с игроками
        if (now % 300 == 0) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null) {
                int crawlers = world.getEntitiesByClass(CrawlerEntity.class, new Box(player.getBlockPos()).expand(40),
                        e -> session.owns(e)).size();
                if (crawlers < 3) spawnCrawlerNear(session, player);
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (powered && reachedExit(session, player, exit, 2.5)) return;
        // Кардиомонитор: пищит тем чаще, чем ближе Ползун
        long now = session.now();
        if (now >= nextBeep.getOrDefault(player.getUuid(), 0L)) {
            List<CrawlerEntity> near = session.world.getEntitiesByClass(CrawlerEntity.class,
                    new Box(player.getBlockPos()).expand(16), e -> session.owns(e));
            double dist = 16;
            for (CrawlerEntity crawler : near) dist = Math.min(dist, Math.sqrt(crawler.squaredDistanceTo(player)));
            nextBeep.put(player.getUuid(), now + 6 + (long) (dist * 3));
            DreamFx.soundTo(player, SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.AMBIENT,
                    player.getX(), player.getEyeY(), player.getZ(), 0.25f, dist < 8 ? 1.6f : 1.2f);
        }
    }
}
