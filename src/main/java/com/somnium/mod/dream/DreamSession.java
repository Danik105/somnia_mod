package com.somnium.mod.dream;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.dream.scenario.DreamScenario;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Один экземпляр сна: тип, измерение, слот (участок измерения), участники и сценарий.
 *
 * Всё состояние конкретного сна живёт в объекте сценария, а не в статических картах,
 * поэтому несколько групп могут одновременно видеть один и тот же сон в разных слотах,
 * а пробуждение одного участника не ломает сон остальным.
 */
public final class DreamSession {

    /** Метка всех сущностей, созданных снами. */
    public static final String ENTITY_TAG = "somnium_dream";
    private static final String SESSION_TAG_PREFIX = "somnium_s_";

    public final UUID id = UUID.randomUUID();
    public final DreamType type;
    public final ServerWorld world;
    public final int slot;
    public final BlockPos origin;
    public final long startTick;
    public final DreamScenario scenario;
    public final Random random = new Random();

    private final Set<UUID> members = new LinkedHashSet<>();
    private final String sessionTag;
    private BlockPos spawn;
    private BlockBox buildBox;
    private BlockBox areaBox;
    private boolean ended;

    DreamSession(DreamType type, ServerWorld world, int slot, BlockPos origin, DreamScenario scenario) {
        this.type = type;
        this.world = world;
        this.slot = slot;
        this.origin = origin;
        this.scenario = scenario;
        this.startTick = world.getServer().getTicks();
        this.sessionTag = SESSION_TAG_PREFIX + id;
    }

    // ----- время -----

    public MinecraftServer server() {
        return world.getServer();
    }

    public long now() {
        return world.getServer().getTicks();
    }

    public long elapsed() {
        return now() - startTick;
    }

    public long remaining() {
        return Math.max(0L, type.durationTicks() - elapsed());
    }

    // ----- участники -----

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    void addMember(UUID playerId) {
        members.add(playerId);
    }

    void removeMember(UUID playerId) {
        members.remove(playerId);
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** Онлайн-участники, которые находятся в измерении сна (копия — безопасно будить в цикле). */
    public List<ServerPlayerEntity> players() {
        List<ServerPlayerEntity> list = new ArrayList<>(members.size());
        for (UUID id : members) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
            if (player != null && player.getWorld() == world) list.add(player);
        }
        return list;
    }

    @Nullable
    public ServerPlayerEntity nearestPlayer(Vec3d pos) {
        ServerPlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayerEntity player : players()) {
            double d = player.squaredDistanceTo(pos);
            if (d < bestDist) {
                bestDist = d;
                best = player;
            }
        }
        return best;
    }

    @Nullable
    public ServerPlayerEntity randomPlayer() {
        List<ServerPlayerEntity> list = players();
        return list.isEmpty() ? null : list.get(random.nextInt(list.size()));
    }

    // ----- геометрия -----

    public BlockPos spawn() {
        return spawn;
    }

    void setSpawn(BlockPos spawn) {
        this.spawn = spawn;
    }

    /** Всё, что сценарий построил: эта область зачищается после сна. */
    public BlockBox buildBox() {
        return buildBox;
    }

    /** Игровая область: сущности внутри неё принадлежат сну. */
    public BlockBox areaBox() {
        return areaBox;
    }

    void setBounds(BlockBox build, int areaRadius) {
        this.buildBox = build;
        int r = Math.max(areaRadius, 16);
        this.areaBox = new BlockBox(
                Math.min(build.getMinX(), origin.getX() - r), world.getBottomY(),
                Math.min(build.getMinZ(), origin.getZ() - r),
                Math.max(build.getMaxX(), origin.getX() + r), world.getTopY(),
                Math.max(build.getMaxZ(), origin.getZ() + r));
    }

    public boolean isInArea(BlockPos pos) {
        return areaBox != null && areaBox.contains(pos);
    }

    public boolean isEnded() {
        return ended;
    }

    void markEnded() {
        this.ended = true;
    }

    // ----- сущности -----

    public String sessionTag() {
        return sessionTag;
    }

    public boolean owns(Entity entity) {
        return entity.getCommandTags().contains(sessionTag);
    }

    void tag(Entity entity) {
        entity.addCommandTag(ENTITY_TAG);
        entity.addCommandTag(sessionTag);
    }

    /** Помечает сущность как часть сна и добавляет в мир. */
    public <T extends Entity> T add(T entity) {
        tag(entity);
        world.spawnEntity(entity);
        return entity;
    }

    @Nullable
    public MobEntity spawnMob(Identifier typeId, BlockPos pos, float yaw) {
        var type = Registries.ENTITY_TYPE.getOrEmpty(typeId);
        if (type.isEmpty()) {
            SomniumMod.LOGGER.warn("[Somnium] Неизвестная сущность сна: {}", typeId);
            return null;
        }
        return spawnMob(type.get(), pos, yaw);
    }

    @Nullable
    public MobEntity spawnMob(EntityType<?> type, BlockPos pos, float yaw) {
        Entity entity = type.create(world);
        if (!(entity instanceof MobEntity mob)) {
            if (entity != null) entity.discard();
            return null;
        }
        mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        mob.setPersistent();
        return add(mob);
    }

    /**
     * Ближайшая к targetY точка (ноги), где моб не застрянет: свободны ноги и голова,
     * под ногами — твёрдый блок. Если не нашлось — сама точка на высоте targetY.
     */
    public BlockPos groundNear(int x, int targetY, int z) {
        for (int d = 0; d <= 8; d++) {
            for (int sign : new int[]{1, -1}) {
                int y = targetY + d * sign;
                BlockPos feet = new BlockPos(x, y, z);
                if (world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                        && !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()) {
                    return feet;
                }
                if (d == 0) break;
            }
        }
        return new BlockPos(x, targetY, z);
    }

    /** Случайная точка в кольце вокруг center (на земле около высоты center). */
    public BlockPos ringPos(BlockPos center, double minR, double maxR) {
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = minR + random.nextDouble() * (maxR - minR);
        int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
        int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
        return groundNear(x, center.getY(), z);
    }

    // ----- сообщения и звуки -----

    public void hint(Text text, int seconds) {
        for (ServerPlayerEntity player : players()) {
            DreamHints.show(player, text, seconds);
        }
    }

    public void actionBar(Text text) {
        for (ServerPlayerEntity player : players()) {
            player.sendMessage(text, true);
        }
    }

    public void sound(BlockPos pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {
        world.playSound(null, pos, sound, category, volume, pitch);
    }

    /** Будит всех участников сна (общая победа). Пробуждение выполняется в конце тика. */
    public void wakeAll(DreamOutcome outcome) {
        for (UUID id : new ArrayList<>(members)) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
            if (player != null) DreamManager.requestWake(player, outcome);
        }
    }
}
