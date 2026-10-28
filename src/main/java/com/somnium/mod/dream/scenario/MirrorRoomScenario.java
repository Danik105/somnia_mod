package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.MirrorReflectionEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Зеркальная комната: кварцевый зал 30×30, посередине стеклянная стена. 20 секунд отражение
 * повторяет движения игрока за стеклом, потом стекло разлетается и отражение нападает.
 * Победа открывает в стене нишу света — шаг в неё будит.
 */
public final class MirrorRoomScenario extends DreamScenario {

    private static final int SIZE = 30;
    private static final int HEIGHT = 8;
    private static final int GLASS_BREAK_AT = 400;

    private BlockPos corner;
    private final List<BlockPos> glass = new ArrayList<>();
    private boolean glassBroken;
    @Nullable private MirrorReflectionEntity reflection;
    @Nullable private BlockPos exit;

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        corner = session.origin.add(-SIZE / 2, 0, -SIZE / 2);
        BlockState quartz = Blocks.QUARTZ_BLOCK.getDefaultState();
        BlockState smooth = Blocks.SMOOTH_QUARTZ.getDefaultState();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                setBulk(world, corner.add(x, -1, z), smooth);
                setBulk(world, corner.add(x, HEIGHT, z), smooth);
                for (int y = 0; y < HEIGHT; y++) {
                    boolean wall = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                    setBulk(world, corner.add(x, y, z), wall ? quartz : Blocks.AIR.getDefaultState());
                }
            }
        }
        int middle = SIZE / 2;
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 1; z < SIZE - 1; z++) {
                BlockPos pos = corner.add(middle, y, z);
                setBulk(world, pos, Blocks.GLASS.getDefaultState());
                glass.add(pos);
            }
        }
        for (int x = 3; x < SIZE - 3; x += 5) {
            for (int z = 3; z < SIZE - 3; z += 5) {
                setBulk(world, corner.add(x, HEIGHT - 1, z), Blocks.SEA_LANTERN.getDefaultState());
            }
        }

        BlockPos mobPos = corner.add(middle + 8, 0, middle);
        if (session.spawnMob(ModEntities.MIRROR_REFLECTION, mobPos, 90f) instanceof MirrorReflectionEntity mob) {
            mob.setMirrorPlane(corner.add(middle, 0, middle));
            mob.setTargetPlayer(leader);
            reflection = mob;
        }
        return corner.add(middle - 8, 0, middle);
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(corner.getX() - 5, corner.getY() - 1, corner.getZ() - 1,
                corner.getX() + SIZE, corner.getY() + HEIGHT, corner.getZ() + SIZE);
    }

    @Override
    public int areaRadius() {
        return SIZE;
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        player.sendMessage(tr("somnium.hint.mirror_room.start").formatted(Formatting.GRAY), true);
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        if (!glassBroken && session.elapsed() >= GLASS_BREAK_AT) {
            glassBroken = true;
            for (BlockPos pos : glass) {
                setBulk(world, pos, Blocks.AIR.getDefaultState());
                if (session.random.nextInt(4) == 0) {
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.GLASS.getDefaultState()),
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.1);
                }
            }
            session.sound(glass.get(glass.size() / 2), ModSounds.MIRROR_SHATTER, SoundCategory.BLOCKS, 2.0f, 0.8f);
        }

        if (exit == null && (reflection == null || reflection.isRemoved() || !reflection.isAlive())
                && session.elapsed() > 20) {
            exit = buildExit(world);
            session.sound(exit, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT, 1.6f, 1.0f);
            session.hint(tr("somnium.hint.mirror_room.exit").formatted(Formatting.WHITE), 9);
        }
        if (exit != null && session.now() % 10 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, exit.getX() + 0.5, exit.getY() + 1.2, exit.getZ() + 0.5,
                    6, 0.8, 0.9, 0.8, 0.01);
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, exit.getX() + 0.5, exit.getY() + 1.2, exit.getZ() + 0.5,
                    10, 0.9, 1.0, 0.9, 0.02);
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (exit == null) return;
        double dx = player.getX() - (exit.getX() + 0.5);
        double dy = player.getY() - exit.getY();
        double dz = player.getZ() - (exit.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz <= 3.24 && DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
            player.playSound(ModSounds.EXIT_PORTAL, SoundCategory.PLAYERS, 1.0f, 1.0f);
            session.world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(),
                    40, 0.6, 1.0, 0.6, 0.05);
        }
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        // Отражение "принадлежало" ушедшему — переходит к другому участнику
        if (reflection != null && reflection.isTarget(player)) {
            ServerPlayerEntity next = null;
            for (ServerPlayerEntity other : session.players()) {
                if (other != player) {
                    next = other;
                    break;
                }
            }
            reflection.setTargetPlayer(next);
        }
    }

    /** Проём 3×3 в западной стене и ниша за ним, светящаяся морскими фонарями. */
    private BlockPos buildExit(ServerWorld world) {
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState quartz = Blocks.QUARTZ_BLOCK.getDefaultState();
        BlockState smooth = Blocks.SMOOTH_QUARTZ.getDefaultState();
        int midZ = SIZE / 2;
        for (int x = -4; x <= -1; x++) {
            for (int y = -1; y <= 3; y++) {
                for (int z = midZ - 2; z <= midZ + 2; z++) {
                    boolean shell = x == -4 || y == -1 || y == 3 || z == midZ - 2 || z == midZ + 2;
                    set(world, corner.add(x, y, z), shell ? (y == -1 ? smooth : quartz) : air);
                }
            }
        }
        for (int y = 0; y <= 2; y++) {
            for (int z = midZ - 1; z <= midZ + 1; z++) {
                set(world, corner.add(0, y, z), air);
                set(world, corner.add(-4, y, z), Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        return corner.add(-2, 0, midZ);
    }
}
