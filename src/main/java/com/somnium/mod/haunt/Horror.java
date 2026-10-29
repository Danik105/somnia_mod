package com.somnium.mod.haunt;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.registry.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.LookAtS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Общие приёмы ужасов: скример на весь экран, катсцена (чёрные полосы, заморозка,
 * камера смотрит на цель), звуки и блоки, которые видит и слышит только один игрок,
 * и отложенные действия по тикам сервера.
 */
public final class Horror {

    public static final Identifier SCREAMER_ID = SomniumMod.id("screamer");
    public static final Identifier LETTERBOX_ID = SomniumMod.id("letterbox");

    public static final int FACE_THIN = 0;
    public static final int FACE_SMILER = 1;
    public static final int FACE_PALE = 2;
    public static final int FACES = 3;

    private record Task(long tick, Runnable action) {}

    private record Hold(Supplier<Vec3d> target, long until) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static final List<Task> INCOMING = new ArrayList<>();
    private static final Map<UUID, Hold> CUTSCENES = new HashMap<>();
    /** Временные видения этого запуска сервера; всё с тегом vision вне списка — остатки прошлого запуска. */
    private static final java.util.Set<UUID> VISIONS = new java.util.HashSet<>();
    private static final List<Entity> STALE = new ArrayList<>();
    public static final String VISION_TAG = "somnium_vision";
    private static long now;

    private Horror() {}

    // ----- время -----

    public static void tick(MinecraftServer server) {
        now = server.getTicks();
        if (!STALE.isEmpty()) {
            List<Entity> stale = new ArrayList<>(STALE);
            STALE.clear();
            stale.forEach(Entity::discard);
        }
        if (!INCOMING.isEmpty()) {
            TASKS.addAll(INCOMING);
            INCOMING.clear();
        }
        Iterator<Task> it = TASKS.iterator();
        List<Runnable> due = new ArrayList<>();
        while (it.hasNext()) {
            Task task = it.next();
            if (task.tick() <= now) {
                due.add(task.action());
                it.remove();
            }
        }
        for (Runnable action : due) {
            try {
                action.run();
            } catch (RuntimeException e) {
                SomniumMod.LOGGER.warn("[Somnium] Ошибка в событии ужаса", e);
            }
        }
        if (now % 2 == 0 && !CUTSCENES.isEmpty()) {
            var entries = CUTSCENES.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null || now >= entry.getValue().until()) {
                    entries.remove();
                    continue;
                }
                Vec3d target = entry.getValue().target().get();
                if (target != null) lookAt(player, target);
            }
        }
    }

    /** Выполнить действие через ticks тиков сервера. */
    public static void later(int ticks, Runnable action) {
        INCOMING.add(new Task(now + Math.max(1, ticks), action));
    }

    /** Пометить сущность как временное видение (до spawnEntity). */
    public static void vision(Entity entity) {
        entity.addCommandTag(VISION_TAG);
        VISIONS.add(entity.getUuid());
    }

    /** Видение, сохранившееся в мире с прошлого запуска, удаляется при загрузке. */
    public static void onEntityLoad(Entity entity) {
        if (entity.getCommandTags().contains(VISION_TAG) && !VISIONS.contains(entity.getUuid())) STALE.add(entity);
    }

    public static void clear() {
        TASKS.clear();
        VISIONS.clear();
        STALE.clear();
        INCOMING.clear();
        CUTSCENES.clear();
    }

    // ----- экран -----

    public static void screamer(ServerPlayerEntity player, int face) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(face);
        buf.writeVarInt(14);
        ServerPlayNetworking.send(player, SCREAMER_ID, buf);
        Vec3d eyes = player.getEyePos();
        sound(player, SoundEvents.ENTITY_GHAST_SCREAM, eyes, 2.0f, 0.6f);
        sound(player, ModSounds.RUPTURE_STINGER, eyes, 2.0f, 1.0f);
    }

    public static void letterbox(ServerPlayerEntity player, boolean on) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(on);
        ServerPlayNetworking.send(player, LETTERBOX_ID, buf);
    }

    public static void lookAt(ServerPlayerEntity player, Vec3d target) {
        player.networkHandler.sendPacket(new LookAtS2CPacket(EntityAnchorArgumentType.EntityAnchor.EYES,
                target.x, target.y, target.z));
    }

    /** Нельзя ни идти, ни прыгать (эффекты без иконок и частиц). */
    public static void freeze(ServerPlayerEntity player, int ticks) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 9, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, ticks, 250, false, false, false));
        player.setVelocity(Vec3d.ZERO);
        player.velocityModified = true;
    }

    /** Катсцена: полосы, заморозка и камера, прикованная к цели. */
    public static void cutscene(ServerPlayerEntity player, int ticks, Supplier<Vec3d> look) {
        CUTSCENES.put(player.getUuid(), new Hold(look, now + ticks));
        letterbox(player, true);
        freeze(player, ticks);
        later(ticks, () -> letterbox(player, false));
    }

    public static boolean inCutscene(UUID playerId) {
        Hold hold = CUTSCENES.get(playerId);
        return hold != null && now < hold.until();
    }

    // ----- личные звуки и блоки -----

    public static void sound(ServerPlayerEntity player, SoundEvent sound, Vec3d pos, float volume, float pitch) {
        sound(player, Registries.SOUND_EVENT.getEntry(sound), pos, volume, pitch);
    }

    public static void sound(ServerPlayerEntity player, RegistryEntry<SoundEvent> sound, Vec3d pos, float volume, float pitch) {
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(sound, SoundCategory.HOSTILE,
                pos.x, pos.y, pos.z, volume, pitch, player.getRandom().nextLong()));
    }

    /** Блок, который видит только этот игрок; через ticks возвращается настоящий. */
    public static void fakeBlock(ServerPlayerEntity player, BlockPos pos, BlockState state, int ticks) {
        player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos.toImmutable(), state));
        ServerWorld world = player.getServerWorld();
        BlockPos fixed = pos.toImmutable();
        later(ticks, () -> {
            if (player.getServerWorld() == world) {
                player.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, fixed));
            }
        });
    }

    // ----- геометрия -----

    /** Точка позади игрока (по горизонтали) на высоте глаз. */
    public static Vec3d behind(ServerPlayerEntity player, double distance) {
        Vec3d look = player.getRotationVector().multiply(1, 0, 1).normalize();
        return player.getEyePos().subtract(look.multiply(distance));
    }

    /** Точка под углом angle (градусы, 0 = прямо по взгляду) на расстоянии distance. */
    public static Vec3d around(ServerPlayerEntity player, double angle, double distance) {
        float yaw = player.getYaw() + (float) angle;
        double rad = Math.toRadians(yaw);
        return new Vec3d(player.getX() - Math.sin(rad) * distance, player.getY(), player.getZ() + Math.cos(rad) * distance);
    }

    /** Земля под/над точкой: свободны ноги и голова, снизу опора. */
    @Nullable
    public static BlockPos ground(ServerWorld world, Vec3d point, int scan) {
        int x = MathHelper.floor(point.x);
        int z = MathHelper.floor(point.z);
        int y0 = MathHelper.floor(point.y);
        for (int d = 0; d <= scan; d++) {
            for (int sign : new int[]{1, -1}) {
                BlockPos feet = new BlockPos(x, y0 + d * sign, z);
                if (world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                        && !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()
                        && world.getFluidState(feet).isEmpty()) {
                    return feet;
                }
                if (d == 0) break;
            }
        }
        return null;
    }

    /** Смотрит ли игрок на сущность (конус dot) и видит ли её. */
    public static boolean isLookingAt(ServerPlayerEntity player, Entity entity, double dot) {
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d to = entity.getPos().add(0, entity.getHeight() * 0.7, 0).subtract(player.getEyePos()).normalize();
        return look.dotProduct(to) > dot && player.canSee(entity);
    }

    public static void face(Entity entity, Vec3d target) {
        double dx = target.x - entity.getX();
        double dy = target.y - entity.getEyeY();
        double dz = target.z - entity.getZ();
        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * MathHelper.DEGREES_PER_RADIAN);
        entity.setYaw(yaw);
        entity.setHeadYaw(yaw);
        entity.setPitch(pitch);
        if (entity instanceof LivingEntity living) living.setBodyYaw(yaw);
    }
}
