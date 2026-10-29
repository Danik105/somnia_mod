package com.somnium.mod.haunt;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.entity.nightmare.GraspingHandsEntity;
import com.somnium.mod.entity.nightmare.ThinManEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Сонный паралич: игрок лёг, но вместо сна застыл в кровати. Встать нельзя, камера
 * смотрит в изножье. Вдали появляется силуэт и тяжёлыми шагами подходит к кровати,
 * потом исчезает. Тишина, шёпот из-под кровати, по бокам из пола тянутся бледные руки
 * и хватают. Скример, темнота, и игрок просыпается уже утром.
 *
 * Случается вместо сна с шансом от рассудка (4% при высоком, до 30% ниже 20)
 * или по команде /somnium paralysis.
 */
public final class SleepParalysis {

    private static final int LENGTH = 270;
    private static final Random RANDOM = new Random();
    private static final Map<UUID, BlockPos> ACTIVE = new HashMap<>();
    private static final Set<UUID> FORCED_NEXT = new HashSet<>();

    private SleepParalysis() {}

    public static boolean isActive(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    /** Следующий раз, когда игрок ляжет в кровать, точно будет паралич. */
    public static void forceNext(ServerPlayerEntity player) {
        FORCED_NEXT.add(player.getUuid());
    }

    /** Решает, будет ли паралич вместо сна. */
    public static boolean roll(ServerPlayerEntity player) {
        if (player.isCreative() || player.isSpectator()) return false;
        if (FORCED_NEXT.remove(player.getUuid())) return true;
        float sanity = SanityManager.get(player).getSanity();
        float chance = sanity >= 60 ? 0.04f : sanity >= 40 ? 0.10f : sanity >= 20 ? 0.20f : 0.30f;
        return RANDOM.nextFloat() < chance;
    }

    /** Начать паралич у спящего игрока. false — он не в кровати. */
    public static boolean start(ServerPlayerEntity player) {
        if (ACTIVE.containsKey(player.getUuid()) || !player.isSleeping()) return false;
        BlockPos bed = player.getSleepingPosition().orElse(null);
        ServerWorld world = player.getServerWorld();
        if (bed == null) return false;
        BlockState state = world.getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)) return false;
        Direction head = state.get(BedBlock.FACING);
        BlockPos foot = bed.offset(head.getOpposite());
        Direction out = head.getOpposite();
        ACTIVE.put(player.getUuid(), bed.toImmutable());

        // Куда смотрит камера: сначала на силуэт, потом на руки у изножья
        Vec3d[] look = {foot.toCenterPos().add(Vec3d.of(out.getVector()).multiply(12)).add(0, 1.5, 0)};
        Horror.cutscene(player, LENGTH, () -> look[0]);
        Horror.letterbox(player, true);
        player.sendMessage(Text.translatable("somnium.paralysis.start").formatted(Formatting.GRAY, Formatting.ITALIC), true);
        for (int t = 0; t < LENGTH - 30; t += 22) {
            Horror.later(1 + t, () -> Horror.sound(player, SoundEvents.ENTITY_WARDEN_HEARTBEAT, player.getEyePos(), 1.2f, 0.9f));
        }
        Horror.later(20, () -> DreamFx.title(player, Text.empty(),
                Text.translatable("somnium.paralysis.cant_move").formatted(Formatting.GRAY), 10, 50, 10));

        // Силуэт вдали, подходит к кровати
        ThinManEntity figure = ModEntities.THIN_MAN.create(world);
        List<Entity> spawned = new ArrayList<>();
        if (figure != null) {
            BlockPos start = groundAlong(world, foot, out, 14);
            if (start != null) {
                figure.puppet();
                figure.addCommandTag("somnium_haunt");
                figure.refreshPositionAndAngles(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0f, 0f);
                Horror.face(figure, player.getEyePos());
                world.spawnEntity(figure);
                spawned.add(figure);
                look[0] = figure.getPos().add(0, figure.getHeight() * 0.8, 0);
                for (int i = 1; i <= 8; i++) {
                    int dist = 14 - Math.round(i * 1.4f);
                    Horror.later(40 + i * 16, () -> {
                        BlockPos step = groundAlong(world, foot, out, dist);
                        if (step == null || figure.isRemoved()) return;
                        figure.refreshPositionAndAngles(step.getX() + 0.5, step.getY(), step.getZ() + 0.5, 0f, 0f);
                        Horror.face(figure, player.getEyePos());
                        look[0] = figure.getPos().add(0, figure.getHeight() * 0.8, 0);
                        Horror.sound(player, SoundEvents.ENTITY_WARDEN_STEP, figure.getPos(), 1.4f, 0.6f);
                    });
                }
                Horror.later(180, () -> {
                    if (figure.isRemoved()) return;
                    world.spawnParticles(player, ParticleTypes.SMOKE, true, figure.getX(), figure.getBodyY(0.5), figure.getZ(),
                            30, 0.3, 1.2, 0.3, 0.01);
                    Horror.sound(player, SoundEvents.ENTITY_ENDERMAN_TELEPORT, figure.getPos(), 1.5f, 0.4f);
                    figure.discard();
                    look[0] = foot.toCenterPos().add(0, 0.3, 0);
                });
            }
        }

        // Тишина, шёпот из-под кровати, руки
        Horror.later(200, () -> Horror.sound(player, ModSounds.WHISPER_AMBIENT, bed.toCenterPos().add(0, -0.6, 0), 1.4f, 0.6f));
        Direction side = head.rotateYClockwise();
        Horror.later(212, () -> {
            for (BlockPos at : new BlockPos[]{foot.offset(side), foot.offset(side.getOpposite()), bed.offset(side)}) {
                GraspingHandsEntity hands = ModEntities.GRASPING_HANDS.create(world);
                if (hands == null) continue;
                hands.refreshPositionAndAngles(at.getX() + 0.5, bed.getY() - 2.6, at.getZ() + 0.5,
                        head.asRotation(), 0f);
                world.spawnEntity(hands);
                spawned.add(hands);
                for (int i = 1; i <= 15; i++) {
                    double y = bed.getY() - 2.6 + i * 0.07;
                    Horror.later(i * 2, () -> hands.refreshPositionAndAngles(hands.getX(), y, hands.getZ(), hands.getYaw(), 0f));
                }
            }
            Horror.sound(player, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, foot.toCenterPos(), 1.2f, 0.5f);
        });
        Horror.later(250, () -> {
            Horror.screamer(player, Horror.FACE_PALE);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, false, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, false, false, false));
        });
        Horror.later(258, () -> spawned.forEach(Entity::discard));
        Horror.later(LENGTH, () -> finish(player));
        return true;
    }

    /** Земля на расстоянии dist от изножья по направлению взгляда (ищем и ближе, если там стена). */
    private static BlockPos groundAlong(ServerWorld world, BlockPos foot, Direction out, int dist) {
        for (int d = dist; d >= 2; d--) {
            BlockPos ground = Horror.ground(world, foot.offset(out, d).toCenterPos(), 4);
            if (ground != null) return ground;
        }
        return null;
    }

    /** Утро: пропускаем ночь (если больше никто не бодрствует), игрок встаёт. */
    private static void finish(ServerPlayerEntity player) {
        ACTIVE.remove(player.getUuid());
        Horror.letterbox(player, false);
        ServerWorld world = player.getServerWorld();
        boolean alone = world.getPlayers().stream().allMatch(other -> other == player || other.isSleeping() || other.isSpectator());
        if (world.getRegistryKey() == World.OVERWORLD && alone) {
            long next = (world.getTimeOfDay() / 24000L + 1) * 24000L;
            world.setTimeOfDay(next);
        }
        if (player.isSleeping()) player.wakeUp(false, true);
        SanityManager.get(player).setTicksSinceLastSleep(0L);
        SanityManager.add(player, -8.0f);
        DreamFx.title(player, Text.translatable("somnium.paralysis.morning").formatted(Formatting.GOLD),
                Text.translatable("somnium.paralysis.morning_sub").formatted(Formatting.GRAY), 20, 60, 20);
    }

    /** Каждый тик: встать нельзя, а ванильный пропуск ночи не должен разбудить раньше времени. */
    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        for (var entry : new ArrayList<>(ACTIVE.entrySet())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                ACTIVE.remove(entry.getKey());
                continue;
            }
            boolean tryingToGetUp = !player.isSleeping();
            if (tryingToGetUp || server.getTicks() % 60 == 0) {
                player.sleep(entry.getValue());
                if (tryingToGetUp) {
                    player.sendMessage(Text.translatable("somnium.paralysis.cant_move").formatted(Formatting.DARK_GRAY), true);
                }
            }
        }
    }

    public static void onDisconnect(UUID id) {
        ACTIVE.remove(id);
        FORCED_NEXT.remove(id);
    }
}
