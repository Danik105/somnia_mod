package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.PassengerEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallSignBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Лифт: старая кабина в пустой шахте, панель из девяти кнопок. На стене нацарапан порядок
 * этажей (по городской легенде про игру в лифт): 4, 2, 6, 2, 9, 5, 1. Правильная кнопка
 * везёт дальше по ритуалу, и каждый этаж за дверями страшнее прошлого. Неправильная
 * сбрасывает ритуал. Выходить из кабины нельзя, пока не приедешь на последний этаж.
 * На пятом входит Попутчица: на неё нельзя смотреть, пока лифт не довезёт до первого.
 */
public final class ElevatorScenario extends DreamScenario {

    private static final int[] SEQUENCE = {4, 2, 6, 2, 9, 5, 1};
    private static final int DOOR_OPEN_TICKS = 160;
    private static final int PASSENGER_ENTER_DELAY = 50;
    private static final float STARE_WHISPER = 20;
    private static final float STARE_ATTACK = 60;

    private enum Theme { NORMAL, FLICKER, RED, RESIDENTS, EMPTY, PASSENGER, FINAL }

    private static final Theme[] THEME_AFTER_STEP = {
            Theme.NORMAL, Theme.FLICKER, Theme.RED, Theme.RESIDENTS, Theme.EMPTY, Theme.PASSENGER, Theme.FINAL};

    private static final BlockState WALL = Blocks.SPRUCE_PLANKS.getDefaultState();
    private static final BlockState WALL_BURNT = Blocks.DARK_OAK_PLANKS.getDefaultState();
    private static final BlockState FLOOR = Blocks.GRAY_TERRACOTTA.getDefaultState();
    private static final BlockState CEILING = Blocks.SMOOTH_STONE.getDefaultState();
    private static final BlockState DOOR = Blocks.IRON_BLOCK.getDefaultState();
    private static final BlockState LAMP = Blocks.OCHRE_FROGLIGHT.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos c;
    private final Map<BlockPos, Integer> buttons = new HashMap<>();
    private int currentFloor = 1;
    private int shownFloor = 1;
    private int step;
    private boolean doorsOpen;
    private long doorsCloseTick;
    @Nullable private Long arriveTick;
    private long travelStart;
    private int travelFrom;
    private int travelTo;
    @Nullable private Theme travelTheme;
    private boolean travelWrong;
    @Nullable private Theme hallTheme;
    private final List<PassengerEntity> residents = new ArrayList<>();
    @Nullable private PassengerEntity passenger;
    private boolean passengerInside;
    private long passengerEnterTick;
    private float anger;
    private boolean whispered;
    private boolean cabLampOn = true;
    private boolean finished;

    @Override
    public int originY(ServerWorld world, int surfaceY) {
        return 100;
    }

    @Override
    public int areaRadius() {
        return 16;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        c = session.origin;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                setBulk(world, at(x, -1, z), FLOOR);
                setBulk(world, at(x, 3, z), CEILING);
                for (int y = 0; y <= 2; y++) {
                    boolean wall = Math.abs(x) == 2 || Math.abs(z) == 2;
                    long hash = at(x, y, z).asLong() * 0x9E3779B97F4A7C15L;
                    setBulk(world, at(x, y, z), wall ? (((hash >>> 41) & 7) == 0 ? WALL_BURNT : WALL) : AIR);
                }
            }
        }
        closeDoors(world, false);
        setBulk(world, at(0, 3, 0), LAMP);

        // Панель: 3×3 таблички на восточной стене, снизу 1-2-3, сверху 7-8-9
        BlockState button = Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.WEST);
        for (int y = 0; y <= 2; y++) {
            for (int z = -1; z <= 1; z++) {
                int floor = y * 3 + z + 2;
                BlockPos pos = at(1, y, z);
                sign(world, pos, button, Text.empty(), Text.literal(String.valueOf(floor)).formatted(Formatting.BOLD));
                buttons.put(pos, floor);
            }
        }
        // Нацарапанный порядок этажей на западной стене
        sign(world, at(-1, 1, 0), Blocks.SPRUCE_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.EAST),
                Text.literal("4 2 6 2"), Text.literal("9 5 1"), tr("somnium.elevator.scratch.1"), tr("somnium.elevator.scratch.2"));
        updateDisplay(world, 1);
        return c;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(c.getX() - 5, c.getY() - 3, c.getZ() - 3, c.getX() + 5, c.getY() + 6, c.getZ() + 10);
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return session.spawn();
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.elevator.start").formatted(Formatting.GRAY), 10);
    }

    private BlockPos at(int dx, int dy, int dz) {
        return c.add(dx, dy, dz);
    }

    // ----- кнопки -----

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Integer floor = buttons.get(pos);
        if (floor != null) {
            press(session, player, floor);
            return ActionResult.SUCCESS;
        }
        // Остальные таблички (табло, надпись) не редактируются
        return session.world.getBlockState(pos).getBlock() instanceof AbstractSignBlock ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    private void press(DreamSession session, ServerPlayerEntity player, int floor) {
        ServerWorld world = session.world;
        session.sound(at(1, 1, 0), SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 1.0f, 1.0f);
        if (finished || arriveTick != null) {
            player.sendMessage(tr("somnium.hint.elevator.moving").formatted(Formatting.GRAY), true);
            return;
        }
        if (floor == currentFloor && doorsOpen) return;
        boolean correct = step < SEQUENCE.length && floor == SEQUENCE[step];
        if (correct) {
            step++;
            travelTheme = THEME_AFTER_STEP[step - 1];
            travelWrong = false;
        } else {
            Theme[] wrong = {Theme.RED, Theme.RESIDENTS, Theme.EMPTY};
            travelTheme = wrong[session.random.nextInt(wrong.length)];
            travelWrong = true;
        }
        closeDoors(world, true);
        clearHall(world);
        travelFrom = currentFloor;
        travelTo = floor;
        travelStart = session.now();
        arriveTick = session.now() + 50 + 8L * Math.abs(floor - currentFloor);
        session.sound(c, SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.BLOCKS, 0.8f, 0.5f);
    }

    // ----- тик -----

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();

        if (arriveTick != null) {
            tickTravel(session, now);
        } else if (doorsOpen && hallTheme != Theme.FINAL && now >= doorsCloseTick) {
            closeDoors(world, true);
            clearHall(world);
        }

        // Мигание ламп: в кабине на поздних этапах, в коридоре на "мигающих" этажах
        if (step >= 3 && now % 7 == 0 && session.random.nextInt(4) == 0) setCabLamp(world, !cabLampOn);
        else if (!cabLampOn && now % 7 == 3) setCabLamp(world, true);
        if (doorsOpen && (hallTheme == Theme.FLICKER || hallTheme == Theme.PASSENGER) && now % 5 == 0) {
            setBulk(world, at(0, 3, 5), session.random.nextInt(3) == 0 ? CEILING_HALL : LAMP);
        }

        // Мёртвые жильцы и Попутчица поворачивают головы к игрокам
        if (now % 2 == 0) {
            for (PassengerEntity resident : residents) {
                ServerPlayerEntity near = session.nearestPlayer(resident.getPos());
                if (near != null) faceTowards(resident, near.getEyePos());
            }
            if (passenger != null && !passengerInside) {
                ServerPlayerEntity near = session.nearestPlayer(passenger.getPos());
                if (near != null) faceTowards(passenger, near.getEyePos());
            }
        }

        // Попутчица входит в кабину и двери закрываются
        if (passenger != null && !passengerInside && now >= passengerEnterTick) {
            passengerInside = true;
            passenger.refreshPositionAndAngles(c.getX() - 0.5, c.getY(), c.getZ() + 1.5, 180f, 0f);
            passenger.setHeadYaw(180f);
            passenger.setBodyYaw(180f);
            closeDoors(world, true);
            clearHall(world);
            session.hint(tr("somnium.hint.elevator.she_enters").formatted(Formatting.DARK_RED), 8);
            session.sound(c, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.0f, 0.7f);
        }
        if (passenger != null && passengerInside) tickStare(session);

        if (doorsOpen && hallTheme == Theme.EMPTY && now % 4 == 0) {
            world.spawnParticles(ParticleTypes.ASH, c.getX() + 0.5, c.getY() + 1.5, c.getZ() + 6, 20, 3, 2, 3, 0.01);
        }
        if (doorsOpen && hallTheme == Theme.FINAL && now % 5 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, c.getX() + 0.5, c.getY() + 1.2, c.getZ() + 5.5, 4, 1.5, 1.0, 1.5, 0.01);
        }
    }

    private static final BlockState CEILING_HALL = Blocks.CALCITE.getDefaultState();

    private void tickTravel(DreamSession session, long now) {
        ServerWorld world = session.world;
        long total = arriveTick - travelStart;
        int span = travelTo - travelFrom;
        if (span != 0) {
            int passed = (int) ((now - travelStart) * Math.abs(span) / Math.max(1, total));
            updateDisplay(world, travelFrom + Integer.signum(span) * Math.min(passed, Math.abs(span)));
        }
        if ((now - travelStart) % 20 == 0) {
            session.sound(c, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 1.0f, 0.5f);
        }
        if (step >= 3 && session.random.nextInt(60) == 0) {
            session.sound(at(0, 4, 0), SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.HOSTILE, 1.0f, 0.6f);
        }
        if (now < arriveTick) return;

        arriveTick = null;
        currentFloor = travelTo;
        updateDisplay(world, currentFloor);
        Theme theme = travelTheme == null ? Theme.NORMAL : travelTheme;
        buildHall(session, theme);
        openDoors(world);
        doorsCloseTick = now + DOOR_OPEN_TICKS;
        session.sound(c, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 1.0f, 0.6f);

        if (travelWrong) {
            step = 0;
            removePassenger();
            for (ServerPlayerEntity player : session.players()) SanityManager.add(player, -4.0f);
            session.hint(tr("somnium.hint.elevator.wrong").formatted(Formatting.GRAY), 7);
            setCabLamp(world, false);
        } else if (theme == Theme.FINAL) {
            finished = true;
            removePassenger();
            session.hint(tr("somnium.hint.elevator.final").formatted(Formatting.GOLD), 8);
            session.sound(c, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 0.8f);
        } else if (theme == Theme.PASSENGER) {
            passengerEnterTick = now + PASSENGER_ENTER_DELAY;
        }
    }

    /** Взгляд на Попутчицу копит её злость; если она дозреет, лифт возвращает ритуал к началу. */
    private void tickStare(DreamSession session) {
        PassengerEntity her = passenger;
        if (her == null) return;
        boolean anyStare = false;
        for (ServerPlayerEntity player : session.players()) {
            Vec3d look = player.getRotationVec(1.0f);
            Vec3d to = her.getEyePos().subtract(player.getEyePos()).normalize();
            if (look.dotProduct(to) > 0.8 && player.canSee(her)) {
                anyStare = true;
                anger += 1.0f;
                if (session.now() % 5 == 0) SanityManager.add(player, -0.5f);
            }
        }
        if (!anyStare) anger = Math.max(0, anger - 0.5f);
        if (anger >= STARE_WHISPER && !whispered) {
            whispered = true;
            int line = 1 + session.random.nextInt(3);
            session.hint(tr("somnium.hint.elevator.whisper." + line).formatted(Formatting.DARK_GRAY, Formatting.ITALIC), 5);
            session.sound(c, com.somnium.mod.registry.ModSounds.WHISPER_AMBIENT, SoundCategory.HOSTILE, 1.0f, 0.8f);
        }
        if (anger < STARE_WHISPER) whispered = false;
        if (anger >= STARE_ATTACK) {
            ServerWorld world = session.world;
            for (ServerPlayerEntity player : session.players()) {
                player.damage(world.getDamageSources().magic(), 4.0f);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0));
                SanityManager.add(player, -8.0f);
            }
            session.sound(c, SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 1.5f, 0.6f);
            world.spawnParticles(ParticleTypes.SQUID_INK, her.getX(), her.getBodyY(0.5), her.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
            removePassenger();
            step = 0;
            setCabLamp(world, false);
            session.hint(tr("somnium.hint.elevator.looked").formatted(Formatting.DARK_RED), 8);
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (!doorsOpen) return;
        boolean outside = player.getZ() > c.getZ() + 2.6;
        if (!outside) return;
        if (hallTheme == Theme.FINAL) {
            if (DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
                session.world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(),
                        30, 0.5, 1.0, 0.5, 0.05);
            }
            return;
        }
        // Выходить нельзя: этаж выталкивает обратно в кабину
        player.teleport(session.world, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, player.getYaw(), player.getPitch());
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0));
        SanityManager.add(player, -6.0f);
        DreamHints.show(player, tr("somnium.hint.elevator.step_out").formatted(Formatting.DARK_RED), 6);
        session.sound(c, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1.2f, 0.5f);
        closeDoors(session.world, false);
        clearHall(session.world);
    }

    @Override
    public void onEnd(DreamSession session) {
        removePassenger();
    }

    // ----- кабина -----

    private void closeDoors(ServerWorld world, boolean sound) {
        setBulk(world, at(0, 0, 2), DOOR);
        setBulk(world, at(0, 1, 2), DOOR);
        if (sound && doorsOpen) world.playSound(null, c, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1.0f, 0.7f);
        doorsOpen = false;
    }

    private void openDoors(ServerWorld world) {
        setBulk(world, at(0, 0, 2), AIR);
        setBulk(world, at(0, 1, 2), AIR);
        world.playSound(null, c, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.0f, 0.7f);
        doorsOpen = true;
    }

    private void setCabLamp(ServerWorld world, boolean on) {
        if (cabLampOn == on) return;
        cabLampOn = on;
        set(world, at(0, 3, 0), on ? LAMP : CEILING);
    }

    private void updateDisplay(ServerWorld world, int floor) {
        if (floor == shownFloor && world.getBlockState(at(0, 2, 1)).getBlock() instanceof AbstractSignBlock) return;
        shownFloor = floor;
        sign(world, at(0, 2, 1), Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.NORTH),
                Text.empty(), Text.literal(String.valueOf(floor)).formatted(Formatting.BOLD, Formatting.RED));
    }

    private void removePassenger() {
        if (passenger != null) passenger.discard();
        passenger = null;
        passengerInside = false;
        anger = 0;
    }

    // ----- этаж за дверями -----

    private void clearHall(ServerWorld world) {
        for (PassengerEntity resident : residents) resident.discard();
        residents.clear();
        if (passenger != null && !passengerInside) removePassenger();
        for (int x = -4; x <= 4; x++) {
            for (int z = 2; z <= 9; z++) {
                if (z == 2 && Math.abs(x) <= 2) continue;
                for (int y = -2; y <= 4; y++) setBulk(world, at(x, y, z), AIR);
            }
        }
        hallTheme = null;
    }

    private void box(ServerWorld world, int x0, int x1, int y0, int y1, int z0, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) setBulk(world, at(x, y, z), state);
            }
        }
    }

    /** Коридор этажа: x -2..2, z 3..7, стены x = ±3 и z = 8. */
    private void buildHall(DreamSession session, Theme theme) {
        ServerWorld world = session.world;
        clearHall(world);
        hallTheme = theme;
        switch (theme) {
            case EMPTY -> box(world, -2, 2, -1, -1, 3, 7, Blocks.BLACK_CONCRETE.getDefaultState());
            case FINAL -> {
                BlockState white = Blocks.WHITE_CONCRETE.getDefaultState();
                box(world, -3, 3, -1, -1, 2, 8, white);
                box(world, -3, 3, 3, 3, 2, 8, Blocks.SEA_LANTERN.getDefaultState());
                box(world, -3, -3, 0, 2, 2, 8, white);
                box(world, 3, 3, 0, 2, 2, 8, white);
                box(world, -3, 3, 0, 2, 8, 8, Blocks.SEA_LANTERN.getDefaultState());
            }
            default -> buildStairwellHall(session, theme);
        }
    }

    private void buildStairwellHall(DreamSession session, Theme theme) {
        ServerWorld world = session.world;
        boolean red = theme == Theme.RED;
        BlockState lower = (red ? Blocks.RED_CONCRETE : Blocks.WAXED_OXIDIZED_COPPER).getDefaultState();
        BlockState upper = (red ? Blocks.NETHER_BRICKS : Blocks.CALCITE).getDefaultState();
        BlockState floor = (red ? Blocks.NETHER_WART_BLOCK : Blocks.ANDESITE).getDefaultState();
        box(world, -3, 3, -1, -1, 2, 8, floor);
        box(world, -3, 3, 3, 3, 2, 8, upper);
        for (int y = 0; y <= 2; y++) {
            BlockState wall = y <= 1 ? lower : upper;
            box(world, -3, -3, y, y, 2, 8, wall);
            box(world, 3, 3, y, y, 2, 8, wall);
            box(world, -3, 3, y, y, 8, 8, wall);
        }
        boolean open = theme == Theme.FLICKER || theme == Theme.RED;
        door(world, at(-3, 0, 5), Blocks.DARK_OAK_DOOR, Direction.EAST, red);
        door(world, at(3, 0, 5), Blocks.DARK_OAK_DOOR, Direction.WEST, red);
        door(world, at(0, 0, 8), Blocks.DARK_OAK_DOOR, Direction.NORTH, open);
        BlockState black = Blocks.BLACK_CONCRETE.getDefaultState();
        box(world, -4, -4, 0, 1, 5, 5, black);
        box(world, 4, 4, 0, 1, 5, 5, black);
        box(world, 0, 0, 0, 1, 9, 9, black);

        boolean dark = theme == Theme.RESIDENTS;
        if (!dark) setBulk(world, at(0, 3, 5), (red ? Blocks.SHROOMLIGHT : Blocks.OCHRE_FROGLIGHT).getDefaultState());
        sign(world, at(2, 1, 3), Blocks.BIRCH_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.WEST),
                Text.empty(), Text.literal(String.valueOf(currentFloor)).formatted(Formatting.BOLD), tr("somnium.stairwell.floor"));

        if (theme == Theme.RESIDENTS) {
            int[][] spots = {{-1, 6}, {1, 6}, {0, 7}, {-2, 4}};
            for (int[] spot : spots) {
                if (session.spawnMob(ModEntities.PASSENGER, at(spot[0], 0, spot[1]), 180f) instanceof PassengerEntity resident) {
                    resident.setAiDisabled(true);
                    resident.setVariant(1);
                    residents.add(resident);
                }
            }
        } else if (theme == Theme.PASSENGER) {
            if (session.spawnMob(ModEntities.PASSENGER, at(0, 0, 4), 180f) instanceof PassengerEntity her) {
                her.setAiDisabled(true);
                her.setVariant(0);
                passenger = her;
                passengerInside = false;
                anger = 0;
            }
        }
    }
}
