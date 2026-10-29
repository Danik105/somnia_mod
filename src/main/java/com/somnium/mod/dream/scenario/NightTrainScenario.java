package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.ControllerEntity;
import com.somnium.mod.entity.nightmare.PassengerEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Электричка: последняя ночная электричка из шести вагонов. Надо пройти вагон за вагоном
 * до кабины машиниста и сорвать стоп-кран. Через 10 секунд из заднего тамбура выходит
 * Контролёр и идёт по проходу за игроком. Догнал без билета — штраф: отбрасывает на вагон
 * назад. С билетом (их два, лежат на сиденьях) он компостирует его и отстаёт на 15 секунд.
 * Время от времени в поезде гаснет свет: Контролёр в темноте оказывается ближе, а
 * безликие пассажиры поворачивают головы. В задних вагонах они смотрят всегда.
 */
public final class NightTrainScenario extends DreamScenario {

    private static final int CARS = 6;
    private static final int CAR_STEP = 22;
    private static final int CAR_LENGTH = 18;
    private static final int CONTROLLER_DELAY = 200;
    private static final int PUNCH_PAUSE = 300;
    private static final int FINE_PAUSE = 60;
    private static final int BLACKOUT_TICKS = 40;
    private static final String TICKET_TAG = "somnium_ticket";

    private static final BlockState WALL = Blocks.WHITE_TERRACOTTA.getDefaultState();
    private static final BlockState FLOOR = Blocks.BROWN_TERRACOTTA.getDefaultState();
    private static final BlockState AISLE = Blocks.GRAY_TERRACOTTA.getDefaultState();
    private static final BlockState CEILING = Blocks.SMOOTH_QUARTZ.getDefaultState();
    private static final BlockState LAMP = Blocks.SEA_LANTERN.getDefaultState();
    private static final BlockState TAMBOUR = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private record Seated(PassengerEntity mob, int car, float yaw) {}

    private BlockPos c;
    private final List<BlockPos> lamps = new ArrayList<>();
    private final List<Seated> passengers = new ArrayList<>();
    @Nullable private ControllerEntity controller;
    private long controllerPausedUntil;
    private long nextBlackout;
    private long blackoutEnd;
    private boolean blackout;
    private BlockPos lever;
    @Nullable private Long brakeEnd;
    private final Set<UUID> ticketAnnounced = new HashSet<>();
    private final Map<UUID, Long> lastFine = new HashMap<>();

    @Override
    public int originY(ServerWorld world, int surfaceY) {
        return 100;
    }

    @Override
    public int areaRadius() {
        return CARS * CAR_STEP + 16;
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return session.spawn();
    }

    private int carStart(int car) {
        return c.getZ() + car * CAR_STEP;
    }

    private BlockPos at(int dx, int dy, int z) {
        return new BlockPos(c.getX() + dx, c.getY() + dy, z);
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        c = session.origin;
        // Задний тамбур, откуда выйдет Контролёр
        buildShell(world, c.getZ() - 4, c.getZ() - 1, TAMBOUR);
        wallWithDoor(world, c.getZ() - 1, WALL);
        for (int car = 0; car < CARS; car++) {
            buildCar(session, car);
        }
        buildCab(world);
        nextBlackout = session.now() + 300 + session.random.nextInt(200);
        return at(0, 0, c.getZ() + 1);
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(c.getX() - 8, c.getY() - 2, c.getZ() - 6,
                c.getX() + 8, c.getY() + 5, carStart(CARS) + 10);
    }

    /** Пол, стены и потолок от z0 до z1 включительно (внутри x -2..2, высота 3). */
    private void buildShell(ServerWorld world, int z0, int z1, BlockState wall) {
        for (int z = z0; z <= z1; z++) {
            for (int x = -3; x <= 3; x++) {
                setBulk(world, at(x, -1, z), Math.abs(x) == 3 ? wall : (x == 0 ? AISLE : FLOOR));
                setBulk(world, at(x, 3, z), CEILING);
                for (int y = 0; y <= 2; y++) setBulk(world, at(x, y, z), Math.abs(x) == 3 ? wall : AIR);
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y <= 2; y++) setBulk(world, at(x, y, z0), wall);
        }
    }

    /** Торцевая стенка вагона с проходом в тамбур. */
    private void wallWithDoor(ServerWorld world, int z, BlockState wall) {
        for (int x = -3; x <= 3; x++) {
            setBulk(world, at(x, -1, z), x == 0 ? AISLE : FLOOR);
            setBulk(world, at(x, 3, z), CEILING);
            for (int y = 0; y <= 2; y++) {
                boolean gap = x == 0 && y <= 1;
                boolean window = Math.abs(x) == 1 && y == 1;
                setBulk(world, at(x, y, z), gap ? AIR : window ? pane(true) : wall);
            }
        }
    }

    private static BlockState pane(boolean eastWest) {
        return eastWest
                ? Blocks.GLASS_PANE.getDefaultState().with(PaneBlock.EAST, true).with(PaneBlock.WEST, true)
                : Blocks.GLASS_PANE.getDefaultState().with(PaneBlock.NORTH, true).with(PaneBlock.SOUTH, true);
    }

    private void buildCar(DreamSession session, int car) {
        ServerWorld world = session.world;
        int z0 = carStart(car);
        buildShell(world, z0, z0 + CAR_LENGTH - 1, WALL);
        // Сам вагон без задней стенки: её роль играет стенка тамбура
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) setBulk(world, at(x, y, z0), AIR);
        for (int z = z0; z < z0 + CAR_LENGTH; z++) {
            int local = z - z0;
            boolean pillar = local % 4 == 0;
            for (int side : new int[]{-3, 3}) {
                setBulk(world, at(side, 1, z), pillar ? WALL : pane(false));
                setBulk(world, at(side, 2, z), pillar ? WALL : pane(false));
            }
            // Багажные полки над сиденьями
            for (int x : new int[]{-2, 2}) {
                setBulk(world, at(x, 2, z), Blocks.IRON_TRAPDOOR.getDefaultState()
                        .with(TrapdoorBlock.HALF, BlockHalf.TOP).with(TrapdoorBlock.OPEN, false));
            }
            if (local % 4 == 2) {
                BlockPos lamp = at(0, 3, z);
                boolean dead = car == 4 && local != 10;
                setBulk(world, lamp, dead ? CEILING : LAMP);
                if (!dead) lamps.add(lamp);
            }
        }
        // Деревянные диваны лицом друг к другу: [к югу, проход, проход, к северу], спинками впритык
        int seatedHere = 0;
        for (int local = 1; local < CAR_LENGTH - 1; local++) {
            int phase = (local - 1) % 4;
            if (phase == 1 || phase == 2) continue;
            Direction back = phase == 0 ? Direction.NORTH : Direction.SOUTH;
            float yaw = phase == 0 ? 0f : 180f;
            for (int x : new int[]{-2, -1, 1, 2}) {
                BlockPos seat = at(x, 0, z0 + local);
                setBulk(world, seat, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, back));
                double chance = 0.16 + car * 0.04;
                if (car > 0 && seatedHere < 6 && session.random.nextDouble() < chance) {
                    if (seatPassenger(session, seat, yaw, car)) seatedHere++;
                }
            }
        }
        // Тамбур и проход в следующий вагон
        int end = z0 + CAR_LENGTH;
        wallWithDoor(world, end, WALL);
        buildShell(world, end + 1, end + 2, TAMBOUR);
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) setBulk(world, at(x, y, end + 1), AIR);
        wallWithDoor(world, end + 3, WALL);
        // Билеты во 2-м и 4-м вагонах
        if (car == 1 || car == 3) placeTicket(session, z0);
    }

    private boolean seatPassenger(DreamSession session, BlockPos seat, float yaw, int car) {
        ServerWorld world = session.world;
        ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("Marker", true);
        nbt.putBoolean("Invisible", true);
        stand.readCustomDataFromNbt(nbt);
        stand.setNoGravity(true);
        stand.refreshPositionAndAngles(seat.getX() + 0.5, seat.getY() - 0.2, seat.getZ() + 0.5, yaw, 0f);
        session.add(stand);
        if (!(session.spawnMob(ModEntities.PASSENGER, seat, yaw) instanceof PassengerEntity mob)) {
            stand.discard();
            return false;
        }
        mob.setAiDisabled(true);
        mob.setVariant(session.random.nextInt(3) == 0 ? 0 : 1);
        mob.startRiding(stand, true);
        mob.setYaw(yaw);
        mob.setHeadYaw(yaw);
        mob.setBodyYaw(yaw);
        passengers.add(new Seated(mob, car, yaw));
        return true;
    }

    private void placeTicket(DreamSession session, int z0) {
        int local = 1 + 4 * session.random.nextInt(4);
        int x = session.random.nextBoolean() ? -1 : 1;
        ItemStack ticket = new ItemStack(Items.PAPER);
        ticket.setCustomName(tr("somnium.item.train_ticket").formatted(Formatting.YELLOW));
        ticket.getOrCreateNbt().putBoolean(TICKET_TAG, true);
        ItemEntity item = new ItemEntity(session.world, c.getX() + x + 0.5, c.getY() + 0.6, z0 + local + 0.5, ticket, 0, 0, 0);
        item.setNeverDespawn();
        item.setPickupDelay(0);
        session.add(item);
    }

    /** Кабина машиниста: пульт, пустое кресло, лобовое стекло и стоп-кран. */
    private void buildCab(ServerWorld world) {
        int z0 = carStart(CARS);
        buildShell(world, z0, z0 + 5, WALL);
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) setBulk(world, at(x, y, z0), AIR);
        int front = z0 + 6;
        for (int x = -3; x <= 3; x++) {
            setBulk(world, at(x, -1, front), WALL);
            setBulk(world, at(x, 3, front), CEILING);
            for (int y = 0; y <= 2; y++) setBulk(world, at(x, y, front), Math.abs(x) <= 2 && y >= 1 ? pane(true) : WALL);
        }
        BlockState panel = Blocks.POLISHED_BLACKSTONE.getDefaultState();
        for (int x = -2; x <= 2; x++) setBulk(world, at(x, 0, z0 + 5), panel);
        lever = at(0, 1, z0 + 5);
        setBulk(world, lever, Blocks.LEVER.getDefaultState().with(LeverBlock.FACE, WallMountLocation.FLOOR)
                .with(LeverBlock.FACING, Direction.SOUTH));
        setBulk(world, at(-1, 1, z0 + 5), Blocks.RED_CANDLE.getDefaultState());
        setBulk(world, at(0, 0, z0 + 3), Blocks.DARK_OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
        setBulk(world, at(0, 3, z0 + 2), LAMP);
        lamps.add(at(0, 3, z0 + 2));
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.train.start").formatted(Formatting.GRAY), 10);
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hit.getBlockPos().equals(lever) && brakeEnd == null) {
            brakeEnd = session.now() + 50;
            session.hint(tr("somnium.hint.train.brake").formatted(Formatting.GOLD), 5);
            session.sound(lever, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 1.0f, 0.5f);
        }
        return ActionResult.PASS;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();

        if (brakeEnd != null) {
            if (now % 4 == 0) {
                for (ServerPlayerEntity player : session.players()) {
                    player.playSound(SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 1.0f, 0.5f);
                }
            }
            if (now >= brakeEnd) session.wakeAll(DreamOutcome.SURVIVED_OBJECTIVE);
            return;
        }

        tickBlackout(session, now);
        tickController(session, now);

        // Пассажиры: в задних вагонах и в темноте смотрят на ближайшего игрока
        if (now % 2 == 0) {
            for (Seated seated : passengers) {
                PassengerEntity mob = seated.mob();
                if (mob.isRemoved()) continue;
                ServerPlayerEntity near = session.nearestPlayer(mob.getPos());
                boolean stare = blackout || seated.car() >= 3;
                if (stare && near != null && near.squaredDistanceTo(mob) < 20 * 20) {
                    faceTowards(mob, near.getEyePos());
                } else {
                    mob.setHeadYaw(seated.yaw());
                    mob.setPitch(0f);
                }
            }
        }
    }

    private void tickBlackout(DreamSession session, long now) {
        ServerWorld world = session.world;
        if (!blackout && now >= nextBlackout) {
            blackout = true;
            blackoutEnd = now + BLACKOUT_TICKS;
            for (BlockPos lamp : lamps) setBulk(world, lamp, CEILING);
            for (ServerPlayerEntity player : session.players()) {
                player.playSound(SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1.0f, 0.5f);
            }
            session.actionBar(tr("somnium.hint.train.blackout").formatted(Formatting.DARK_GRAY));
            // В темноте Контролёр оказывается совсем рядом
            if (controller != null && now >= controllerPausedUntil) {
                ServerPlayerEntity target = session.nearestPlayer(controller.getPos());
                if (target != null && target.squaredDistanceTo(controller) > 10 * 10) {
                    double z = target.getZ() - 6;
                    controller.refreshPositionAndAngles(c.getX() + 0.5, c.getY(), Math.max(c.getZ() + 0.5, z), 0f, 0f);
                }
            }
        } else if (blackout && now >= blackoutEnd) {
            blackout = false;
            for (BlockPos lamp : lamps) set(world, lamp, LAMP);
            nextBlackout = now + 300 + session.random.nextInt(200);
        }
    }

    private void tickController(DreamSession session, long now) {
        if (controller == null && session.elapsed() >= CONTROLLER_DELAY) {
            if (session.spawnMob(ModEntities.CONTROLLER, at(0, 0, c.getZ() - 2), 0f) instanceof ControllerEntity mob) {
                controller = mob;
                session.hint(tr("somnium.hint.train.controller").formatted(Formatting.DARK_RED), 7);
                session.sound(at(0, 0, c.getZ() - 1), SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1.5f, 0.6f);
            }
        }
        ControllerEntity mob = controller;
        if (mob == null || mob.isRemoved()) return;
        ServerPlayerEntity target = session.nearestPlayer(mob.getPos());
        if (target == null) return;
        if (now < controllerPausedUntil) {
            mob.getNavigation().stop();
            faceTowards(mob, target.getEyePos());
            return;
        }
        if (now % 10 == 0) mob.getNavigation().startMovingTo(target, 1.0);
        if (now % 40 == 0) session.sound(mob.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 0.5f, 0.6f);

        if (mob.squaredDistanceTo(target) < 2.25) {
            if (consumeTicket(target)) {
                controllerPausedUntil = now + PUNCH_PAUSE;
                DreamHints.show(target, tr("somnium.hint.train.punched").formatted(Formatting.YELLOW), 6);
                session.sound(mob.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.HOSTILE, 1.0f, 0.6f);
            } else if (now - lastFine.getOrDefault(target.getUuid(), -1000L) > 40) {
                fine(session, target, mob, now);
            }
        }
    }

    /** Штраф: отбрасывает на начало предыдущего вагона, Контролёр отстаёт. */
    private void fine(DreamSession session, ServerPlayerEntity player, ControllerEntity mob, long now) {
        lastFine.put(player.getUuid(), now);
        int car = Math.max(0, Math.floorDiv((int) player.getZ() - c.getZ(), CAR_STEP));
        int back = Math.max(0, car - 1);
        double z = carStart(back) + 1.5;
        player.teleport(session.world, c.getX() + 0.5, c.getY(), z, 0f, 0f);
        player.damage(session.world.getDamageSources().mobAttack(mob), 2.0f);
        SanityManager.add(player, -6.0f);
        DreamHints.show(player, tr("somnium.hint.train.fined").formatted(Formatting.DARK_RED), 6);
        session.sound(player.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 0.6f);
        double behind = z - 12;
        if (behind > c.getZ() - 2) {
            mob.refreshPositionAndAngles(c.getX() + 0.5, c.getY(), behind, 0f, 0f);
        }
        controllerPausedUntil = now + FINE_PAUSE;
    }

    private static boolean consumeTicket(ServerPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (isTicket(stack)) {
                stack.decrement(1);
                return true;
            }
        }
        return false;
    }

    private static boolean isTicket(ItemStack stack) {
        return stack.isOf(Items.PAPER) && stack.hasNbt() && stack.getNbt().getBoolean(TICKET_TAG);
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        long now = session.now();
        ServerWorld world = session.world;
        // Огни за окнами проносятся назад: поезд едет
        if (now % 5 == 0) {
            for (double side : new double[]{-5.5, 6.5}) {
                world.spawnParticles(player, ParticleTypes.END_ROD, true, c.getX() + side, c.getY() + 1.5 + session.random.nextDouble(),
                        player.getZ() + 26, 0, 0, 0, -1, 1.4);
            }
        }
        if (now % 14 == 0 || now % 14 == 4) {
            player.playSound(SoundEvents.ENTITY_IRON_GOLEM_STEP, SoundCategory.AMBIENT, 0.3f, 0.5f);
        }
        if (now % 80 == 0) player.playSound(SoundEvents.ENTITY_MINECART_RIDING, SoundCategory.AMBIENT, 0.4f, 0.5f);

        if (now % 10 == 0 && !ticketAnnounced.contains(player.getUuid())) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                if (isTicket(player.getInventory().getStack(i))) {
                    ticketAnnounced.add(player.getUuid());
                    DreamHints.show(player, tr("somnium.hint.train.ticket").formatted(Formatting.YELLOW), 6);
                    break;
                }
            }
        }
        if (now % 100 == 0 && player.getZ() > carStart(CARS) - 1 && brakeEnd == null) {
            DreamHints.show(player, tr("somnium.hint.train.cab").formatted(Formatting.GRAY), 5);
        }
    }
}
