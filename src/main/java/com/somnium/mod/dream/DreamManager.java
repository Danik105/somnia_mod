package com.somnium.mod.dream;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.advancement.ModCriteria;
import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.dream.scenario.DreamScenario;
import com.somnium.mod.dream.scenario.DreamScenarios;
import com.somnium.mod.entity.SleepingBodyEntity;
import com.somnium.mod.network.DreamHudPayload;
import com.somnium.mod.player.SomniumPlayerState;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModItems;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.sanity.SanityData;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ядро снов: засыпание, вход в сон, тик активных снов и пробуждение.
 *
 * Логика конкретных снов живёт в сценариях (dream/scenario), состояние —
 * в DreamSession. Пробуждение всегда выполняется в конце серверного тика
 * (requestWake): если будить прямо из обработчика предмета/клика, ванильный код
 * после этого записал бы предмет сна в руку уже восстановленного инвентаря.
 */
public final class DreamManager {

    /** Сколько игрок лежит в кровати (экран темнеет), прежде чем провалиться в сон. */
    public static final long SLEEP_TRANSITION_TICKS = 60;
    /** Первые 12 секунд сна монстры не атакуют — дать осмотреться. */
    public static final long DREAM_ENTRY_GRACE_TICKS = 240;
    /** Засыпающий в течение 10 секунд после начала чужого сна присоединяется к нему. */
    private static final long JOIN_WINDOW_TICKS = 200;
    private static final long LANDING_IMMUNITY_TICKS = 20;
    private static final double REAL_BELL_RADIUS = 5.0;

    private static final class Participant {
        final UUID playerId;
        final DreamSession session;
        final DreamSnapshot snapshot;
        final long enterTick;
        final List<BlockPos> bedBlocks = new ArrayList<>();
        long nextAmbienceTick;
        @Nullable UUID bodyId;

        Participant(UUID playerId, DreamSession session, DreamSnapshot snapshot, long enterTick) {
            this.playerId = playerId;
            this.session = session;
            this.snapshot = snapshot;
            this.enterTick = enterTick;
        }
    }

    private static final Map<UUID, Participant> PARTICIPANTS = new HashMap<>();
    private static final List<DreamSession> SESSIONS = new ArrayList<>();
    private static final Map<String, DreamSession> SESSIONS_BY_TAG = new HashMap<>();
    private static final Map<UUID, Long> PENDING_SLEEP = new HashMap<>();
    private static final Map<UUID, DreamOutcome> PENDING_WAKE = new LinkedHashMap<>();
    private static final Set<UUID> PENDING_RESTORE = new HashSet<>();
    private static final Map<UUID, Long> LANDING_IMMUNITY = new HashMap<>();
    private static final List<Entity> PENDING_DISCARD = new ArrayList<>();

    private DreamManager() {}

    // =====================================================================
    // Запросы состояния
    // =====================================================================

    public static boolean isDreaming(UUID playerId) {
        return PARTICIPANTS.containsKey(playerId);
    }

    @Nullable
    public static DreamSession sessionOf(UUID playerId) {
        Participant p = PARTICIPANTS.get(playerId);
        return p == null ? null : p.session;
    }

    public static Collection<DreamSession> sessions() {
        return Collections.unmodifiableList(SESSIONS);
    }

    public static boolean isInDreamEntryGrace(UUID playerId, long nowTicks) {
        Participant p = PARTICIPANTS.get(playerId);
        return p != null && nowTicks - p.enterTick < DREAM_ENTRY_GRACE_TICKS;
    }

    public static boolean isLandingImmune(UUID playerId, long nowTicks) {
        Long until = LANDING_IMMUNITY.get(playerId);
        return until != null && nowTicks < until;
    }

    public static boolean isBedProtected(ServerWorld world, BlockPos pos) {
        for (Participant p : PARTICIPANTS.values()) {
            if (p.snapshot.returnWorld.equals(world.getRegistryKey()) && p.bedBlocks.contains(pos)) return true;
        }
        return false;
    }

    // =====================================================================
    // Засыпание и вход в сон
    // =====================================================================

    /** Игрок успешно лёг в кровать (PlayerEntityMixin). */
    public static void onPlayerSlept(ServerPlayerEntity player) {
        if (DreamRegistry.isEmpty() || isDreaming(player.getUuid())) return;
        if (ModDimensions.isDreamDimension(player.getWorld().getRegistryKey())) return;
        PENDING_SLEEP.put(player.getUuid(), player.getServer().getTicks() + SLEEP_TRANSITION_TICKS);
    }

    private static void processPendingSleeps(MinecraftServer server, long now) {
        if (PENDING_SLEEP.isEmpty()) return;
        Iterator<Map.Entry<UUID, Long>> it = PENDING_SLEEP.entrySet().iterator();
        List<ServerPlayerEntity> ready = new ArrayList<>();
        while (it.hasNext()) {
            var entry = it.next();
            if (now < entry.getValue()) continue;
            it.remove();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player != null && player.isSleeping() && player.isAlive()) ready.add(player);
        }
        for (ServerPlayerEntity player : ready) {
            DreamSession joinable = findJoinableSession(player);
            if (joinable != null) {
                enterDream(player, joinable.type, joinable);
                continue;
            }
            SanityData sanity = SanityManager.get(player);
            DreamType type = DreamRegistry.pick(sanity.getSanity(), sanity.getRecentDreams());
            if (type != null) enterDream(player, type, null);
        }
    }

    @Nullable
    private static DreamSession findJoinableSession(ServerPlayerEntity player) {
        for (int i = SESSIONS.size() - 1; i >= 0; i--) {
            DreamSession session = SESSIONS.get(i);
            if (session.isEnded() || session.elapsed() > JOIN_WINDOW_TICKS) continue;
            if (!session.scenario.allowsJoining()) continue;
            for (UUID member : session.members()) {
                Participant p = PARTICIPANTS.get(member);
                if (p != null && p.snapshot.returnWorld.equals(player.getWorld().getRegistryKey())) return session;
            }
        }
        return null;
    }

    /**
     * Переносит игрока в сон. join == null — начать новый сон (игрок станет лидером),
     * иначе — присоединиться к существующему. Возвращает false, если сон не начался
     * (в этом случае с игроком ничего не происходит).
     */
    public static boolean enterDream(ServerPlayerEntity player, DreamType type, @Nullable DreamSession join) {
        if (isDreaming(player.getUuid()) || !player.isAlive() || player.isSpectator()) return false;
        if (SomniumPlayerState.of(player).dreamSnapshot() != null) return false; // ещё не восстановлен после обрыва
        MinecraftServer server = player.getServer();
        ServerWorld dreamWorld = server.getWorld(type.dimension());
        if (dreamWorld == null) {
            SomniumMod.LOGGER.warn("[Somnium] Измерение {} для сна {} не найдено", type.dimension().getValue(), type.id());
            return false;
        }

        // Сначала встаём с кровати: позиция игрока становится точкой пробуждения рядом с кроватью
        BlockPos bedPos = player.getSleepingPosition().orElse(null);
        if (player.isSleeping()) player.wakeUp(false, true);
        PENDING_SLEEP.remove(player.getUuid());

        boolean leader = join == null;
        DreamSession session = leader ? createSession(type, dreamWorld, player) : join;
        if (session == null) return false;

        DreamSnapshot snapshot = DreamSnapshot.capture(player, type.id(), bedPos);
        SomniumPlayerState.of(player).setDreamSnapshot(snapshot.toNbt());

        long now = server.getTicks();
        Participant participant = new Participant(player.getUuid(), session, snapshot, now);
        participant.nextAmbienceTick = now + 100;
        PARTICIPANTS.put(player.getUuid(), participant);
        session.addMember(player.getUuid());
        protectBed(participant, player);
        spawnSleepingBody(participant, player);

        DreamSnapshot.prepareDreamBody(player);
        BlockPos spawn = leader ? session.spawn() : session.scenario.joinSpawn(session, player);
        teleport(player, dreamWorld, Vec3d.ofBottomCenter(spawn), player.getYaw(), 0.0f);

        DreamScenario scenario = session.scenario;
        if (!scenario.isSilent()) {
            scenario.applyEffects(player, type.durationTicks() + 100);
            DreamFx.title(player, Text.translatable(type.nameKey()).formatted(Formatting.DARK_RED),
                    Text.translatable("somnium.dream.subtitle").formatted(Formatting.GRAY), 10, 50, 20);
            player.sendMessage(Text.translatable(type.nameKey()).formatted(Formatting.RED, Formatting.BOLD), false);
            player.sendMessage(Text.translatable("somnium.dream.objective",
                    Text.translatable(type.objectiveKey())).formatted(Formatting.GRAY), false);
            player.playSound(ModSounds.DREAM_ENTER, SoundCategory.AMBIENT, 1.0f, 0.7f);
            sendHud(player, session);
        }
        scenario.onJoin(session, player, leader);
        ModCriteria.FIRST_DREAM.trigger(player);
        SomniumMod.LOGGER.info("[Somnium] {} {} сон {} (слот {})", player.getName().getString(),
                leader ? "начинает" : "присоединяется к", type.id(), session.slot);
        return true;
    }

    @Nullable
    private static DreamSession createSession(DreamType type, ServerWorld world, ServerPlayerEntity leader) {
        DreamScenario scenario = DreamScenarios.create(type.scenario());
        if (scenario == null) return null;
        int slot = DreamSlots.allocate(world);
        BlockPos origin = DreamSlots.origin(world, slot, scenario.originY(world, DreamSlots.surfaceY(world)));
        DreamSession session = new DreamSession(type, world, slot, origin, scenario);
        // Сессия регистрируется до постройки: сущности, созданные сценарием, сразу признаются своими
        SESSIONS.add(session);
        SESSIONS_BY_TAG.put(session.sessionTag(), session);
        try {
            BlockPos spawn = scenario.setup(session, leader);
            session.setSpawn(spawn);
            session.setBounds(scenario.buildBounds(session), scenario.areaRadius());
            DreamSlots.markDirty(world, slot, session.buildBox());
            return session;
        } catch (RuntimeException e) {
            SomniumMod.LOGGER.error("[Somnium] Не удалось построить сон {}", type.id(), e);
            session.setBounds(new BlockBox(origin).expand(64), 64);
            DreamSlots.markDirty(world, slot, session.buildBox());
            endSession(session);
            return null;
        }
    }

    // =====================================================================
    // Пробуждение
    // =====================================================================

    /** Разбудить игрока в конце текущего тика. Возвращает false, если пробуждение уже запрошено. */
    public static boolean requestWake(ServerPlayerEntity player, DreamOutcome outcome) {
        if (!isDreaming(player.getUuid())) return false;
        return PENDING_WAKE.putIfAbsent(player.getUuid(), outcome) == null;
    }

    public static boolean isWakePending(UUID playerId) {
        return PENDING_WAKE.containsKey(playerId);
    }

    private static void processPendingWakes(MinecraftServer server) {
        if (PENDING_WAKE.isEmpty()) return;
        var copy = new ArrayList<>(PENDING_WAKE.entrySet());
        PENDING_WAKE.clear();
        for (var entry : copy) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player != null) wake(player, entry.getValue());
        }
    }

    /** Немедленное пробуждение. Из обработчиков предметов/кликов используйте requestWake. */
    public static void wake(ServerPlayerEntity player, DreamOutcome outcome) {
        Participant participant = PARTICIPANTS.remove(player.getUuid());
        PENDING_WAKE.remove(player.getUuid());
        if (participant == null) return;
        DreamSession session = participant.session;

        leaveSession(participant, player);
        returnToReality(player, participant.snapshot);
        SomniumPlayerState.of(player).setDreamSnapshot(null);
        removeSleepingBody(player.getServer(), participant);
        DreamHints.clear(player.getUuid());
        DreamHudPayload.sendInactive(player);

        ServerWorld returnWorld = player.getServerWorld();
        if (outcome.countsAsSleep()) maybeSkipNight(returnWorld);
        SanityManager.onWake(player, outcome, session.type.id());
        if (outcome == DreamOutcome.SURVIVED_OBJECTIVE) {
            // Победа над кошмаром оставляет след — единственная добыча, которую можно вынести из сна
            player.getInventory().offerOrDrop(new ItemStack(ModItems.NIGHTMARE_ESSENCE, 1 + session.random.nextInt(2)));
            player.sendMessage(Text.translatable("somnium.dream.essence_reward").formatted(Formatting.DARK_PURPLE), false);
        }

        DreamFx.title(player, Text.translatable("somnium.dream.wake").formatted(Formatting.GRAY),
                Text.translatable("somnium.outcome." + outcome.name().toLowerCase()).formatted(Formatting.DARK_GRAY),
                10, 50, 20);
        player.playSound(ModSounds.DREAM_WAKE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        SomniumMod.LOGGER.info("[Somnium] {} просыпается из {}: {}", player.getName().getString(),
                session.type.id(), outcome);
    }

    /** Смерть во сне отменена миксином — вместо неё пробуждение со штрафом. */
    public static void onDeathInDream(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.clearStatusEffects();
        player.extinguish();
        player.fallDistance = 0;
        requestWake(player, DreamOutcome.DIED_IN_DREAM);
    }

    private static void leaveSession(Participant participant, ServerPlayerEntity player) {
        DreamSession session = participant.session;
        session.removeMember(participant.playerId);
        if (player != null) session.scenario.onLeave(session, player);
        if (session.isEmpty()) endSession(session);
    }

    private static void endSession(DreamSession session) {
        if (session.isEnded()) return;
        session.markEnded();
        SESSIONS.remove(session);
        SESSIONS_BY_TAG.remove(session.sessionTag());
        try {
            session.scenario.onEnd(session);
        } catch (RuntimeException e) {
            SomniumMod.LOGGER.error("[Somnium] Ошибка при завершении сна {}", session.type.id(), e);
        }
        DreamSlots.release(session.world, session.slot, session.areaBox());
    }

    private static void returnToReality(ServerPlayerEntity player, DreamSnapshot snapshot) {
        MinecraftServer server = player.getServer();
        ServerWorld world = server.getWorld(snapshot.returnWorld);
        Vec3d pos = snapshot.returnPos;
        if (world == null) {
            world = server.getOverworld();
            pos = Vec3d.ofBottomCenter(world.getSpawnPos());
        }
        player.stopRiding();
        teleport(player, world, pos, snapshot.yaw, snapshot.pitch);
        snapshot.restore(player);
    }

    private static void teleport(ServerPlayerEntity player, ServerWorld world, Vec3d pos, float yaw, float pitch) {
        player.teleport(world, pos.x, pos.y, pos.z, yaw, pitch);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0;
        LANDING_IMMUNITY.put(player.getUuid(), player.getServer().getTicks() + LANDING_IMMUNITY_TICKS);
    }

    /**
     * Ванильный пропуск ночи обрывается, когда спящий уходит в сон, поэтому делаем его сами —
     * с учётом правила playersSleepingPercentage: спящими считаются и те, кто сейчас во сне.
     */
    private static void maybeSkipNight(ServerWorld world) {
        if (!world.getDimension().hasSkyLight() || world.getDimension().hasFixedTime()) return;
        if (!world.isNight() && !world.isThundering()) return;
        int total = 0;
        int slept = 1; // проснувшийся игрок
        for (ServerPlayerEntity other : world.getPlayers()) {
            if (other.isSpectator()) continue;
            total++;
            if (other.isSleeping()) slept++;
        }
        // Проснувшийся уже в этом мире и посчитан в total; добавляем тех, кто ещё во сне
        for (Participant p : PARTICIPANTS.values()) {
            if (p.snapshot.returnWorld.equals(world.getRegistryKey())) {
                total++;
                slept++;
            }
        }
        slept = Math.min(slept, Math.max(total, 1));
        int percentage = world.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        if (slept * 100 < Math.max(total, 1) * percentage) return;
        if (world.isNight()) {
            long time = world.getTimeOfDay();
            world.setTimeOfDay(time - Math.floorMod(time, 24000L) + 24000L);
        }
        world.setWeather(0, 0, false, false);
    }

    // =====================================================================
    // Тик
    // =====================================================================

    public static void tick(MinecraftServer server) {
        long now = server.getTicks();
        flushDiscards();
        processPendingRestores(server);
        processPendingWakes(server);
        processPendingSleeps(server, now);

        for (DreamSession session : new ArrayList<>(SESSIONS)) {
            tickSession(session);
        }
        processPendingWakes(server);

        DreamHints.tick(server);
        if (!LANDING_IMMUNITY.isEmpty()) LANDING_IMMUNITY.values().removeIf(until -> now >= until);
        if (now % 20 == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                SanityManager.tick(player);
                Participant p = PARTICIPANTS.get(player.getUuid());
                if (p != null && !p.session.scenario.isSilent()) sendHud(player, p.session);
            }
        }
        DreamSlots.tick();
    }

    private static void tickSession(DreamSession session) {
        if (session.isEnded()) return;
        MinecraftServer server = session.server();
        for (UUID id : new ArrayList<>(session.members())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null && player.getWorld() != session.world) {
                // Игрока вынесло из сна другим путём (команда /tp и т.п.)
                requestWake(player, DreamOutcome.WOKE_EARLY);
            }
        }
        DreamScenario scenario = session.scenario;
        try {
            scenario.tick(session);
            for (ServerPlayerEntity player : session.players()) {
                if (isWakePending(player.getUuid())) continue;
                enforceBoundary(session, player);
                scenario.tickPlayer(session, player);
                tickAmbience(session, player);
            }
        } catch (RuntimeException e) {
            // Ошибка сценария не должна ронять сервер: будим всех с исходом "проснулся сам"
            SomniumMod.LOGGER.error("[Somnium] Ошибка в сне {}", session.type.id(), e);
            session.wakeAll(DreamOutcome.WOKE_EARLY);
            return;
        }
        if (session.elapsed() >= session.type.durationTicks()) {
            session.wakeAll(DreamOutcome.SURVIVED_TIMEOUT);
        }
    }

    /** Открытые сны не отпускают за свою границу — иначе игрок ушёл бы в бесконечный плоский мир. */
    private static void enforceBoundary(DreamSession session, ServerPlayerEntity player) {
        int radius = session.scenario.boundaryRadius();
        if (radius <= 0) return;
        double dx = player.getX() - (session.origin.getX() + 0.5);
        double dz = player.getZ() - (session.origin.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= radius) return;
        double scale = (radius - 6) / dist;
        int x = MathHelper.floor(session.origin.getX() + 0.5 + dx * scale);
        int z = MathHelper.floor(session.origin.getZ() + 0.5 + dz * scale);
        BlockPos target = session.groundNear(x, (int) player.getY(), z);
        player.teleport(session.world, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                player.getYaw() + 180.0f, player.getPitch());
        player.fallDistance = 0;
        player.sendMessage(Text.translatable("somnium.dream.boundary").formatted(Formatting.DARK_PURPLE), true);
    }

    private static void tickAmbience(DreamSession session, ServerPlayerEntity player) {
        if (session.scenario.isSilent()) return;
        Participant p = PARTICIPANTS.get(player.getUuid());
        if (p == null || session.now() < p.nextAmbienceTick) return;
        player.playSound(ModSounds.DREAM_DREAD_AMBIENCE, SoundCategory.AMBIENT, 0.5f, 0.7f + session.random.nextFloat() * 0.3f);
        p.nextAmbienceTick = session.now() + 160 + session.random.nextInt(120);
    }

    private static void sendHud(ServerPlayerEntity player, DreamSession session) {
        DreamHudPayload.sendActive(player, session.type.nameKey(), session.type.objectiveKey(),
                (int) session.remaining(), session.type.durationTicks());
    }

    // =====================================================================
    // Подключение / отключение / остановка сервера
    // =====================================================================

    public static void onPlayerJoin(ServerPlayerEntity player) {
        PENDING_RESTORE.add(player.getUuid());
        SanityManager.sync(player);
    }

    /** Через тик после входа: если сон оборвался (выход/падение сервера) — вернуть игрока в явь. */
    private static void processPendingRestores(MinecraftServer server) {
        if (PENDING_RESTORE.isEmpty()) return;
        for (UUID id : new ArrayList<>(PENDING_RESTORE)) {
            PENDING_RESTORE.remove(id);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player == null || isDreaming(id)) continue;
            SomniumPlayerState state = SomniumPlayerState.of(player);
            if (state.dreamSnapshot() != null) {
                DreamSnapshot snapshot = DreamSnapshot.fromNbt(state.dreamSnapshot());
                state.setDreamSnapshot(null);
                if (snapshot != null) {
                    returnToReality(player, snapshot);
                    SanityManager.onWake(player, DreamOutcome.INTERRUPTED, snapshot.dreamId);
                    player.sendMessage(Text.translatable("somnium.dream.interrupted").formatted(Formatting.GRAY), false);
                    SomniumMod.LOGGER.info("[Somnium] {}: сон оборвался, состояние яви восстановлено", player.getName().getString());
                    continue;
                }
            }
            if (ModDimensions.isSessionDimension(player.getWorld().getRegistryKey())) {
                // Застрял в измерении сна без снимка (данные старой версии мода) — домой
                ServerWorld overworld = server.getOverworld();
                BlockPos spawn = player.getSpawnPointPosition() != null
                        && player.getSpawnPointDimension() == World.OVERWORLD
                        ? player.getSpawnPointPosition() : overworld.getSpawnPos();
                teleport(player, overworld, Vec3d.ofBottomCenter(spawn).add(0, 1, 0), player.getYaw(), 0.0f);
                player.sendMessage(Text.translatable("somnium.dream.interrupted").formatted(Formatting.GRAY), false);
            }
        }
    }

    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        PENDING_SLEEP.remove(id);
        PENDING_WAKE.remove(id);
        PENDING_RESTORE.remove(id);
        DreamHints.clear(id);
        Participant participant = PARTICIPANTS.remove(id);
        if (participant == null) return;
        // Снимок яви остаётся в NBT игрока и будет восстановлен при следующем входе
        leaveSession(participant, player);
        removeSleepingBody(player.getServer(), participant);
    }

    public static void onServerStopping() {
        for (Participant participant : new ArrayList<>(PARTICIPANTS.values())) {
            leaveSession(participant, null);
        }
        PARTICIPANTS.clear();
        SESSIONS.clear();
        SESSIONS_BY_TAG.clear();
        PENDING_SLEEP.clear();
        PENDING_WAKE.clear();
        PENDING_RESTORE.clear();
        LANDING_IMMUNITY.clear();
        PENDING_DISCARD.clear();
    }

    // =====================================================================
    // Сущности: принадлежность снам
    // =====================================================================

    /**
     * Каждая загружаемая в измерении сна сущность должна принадлежать активному сну.
     * Чужие и оставшиеся от прошлых снов (в т.ч. после перезапуска) удаляются.
     * Заодно убираем "тела" из стоек для брони, которые оставляла версия 0.2.
     */
    public static void onEntityLoad(Entity entity, ServerWorld world) {
        if (entity instanceof PlayerEntity) return;
        if (entity instanceof ArmorStandEntity stand && isLegacySleepingBody(stand)) {
            PENDING_DISCARD.add(entity);
            return;
        }
        if (!ModDimensions.isSessionDimension(world.getRegistryKey())) return;

        boolean tagged = false;
        for (String tag : entity.getCommandTags()) {
            DreamSession owner = SESSIONS_BY_TAG.get(tag);
            if (owner != null && !owner.isEnded()) return;
            if (tag.equals(DreamSession.ENTITY_TAG)) tagged = true;
        }
        if (!tagged && !(entity instanceof ArmorStandEntity)) {
            for (DreamSession session : SESSIONS) {
                if (session.world == world && session.isInArea(entity.getBlockPos())) {
                    session.tag(entity); // выпавший лут, опыт, снаряды — часть этого сна
                    return;
                }
            }
        }
        PENDING_DISCARD.add(entity);
    }

    private static boolean isLegacySleepingBody(ArmorStandEntity stand) {
        Text name = stand.getCustomName();
        return name != null && stand.isInvisible()
                && name.getContent() instanceof TranslatableTextContent content
                && content.getKey().equals("somnium.dream.sleeping_body");
    }

    /**
     * Удаление сущности может синхронно вызвать загрузку другой (и снова onEntityLoad),
     * поэтому проходим по снимку очереди: всё, что добавится во время прохода, уйдёт в следующий тик.
     */
    private static void flushDiscards() {
        if (PENDING_DISCARD.isEmpty()) return;
        List<Entity> batch = new ArrayList<>(PENDING_DISCARD);
        PENDING_DISCARD.clear();
        Set<String> types = new HashSet<>();
        for (Entity entity : batch) {
            if (entity.isRemoved()) continue;
            types.add(net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()) + " @ "
                    + entity.getWorld().getRegistryKey().getValue());
            entity.discard();
        }
        if (!types.isEmpty() && discardReports < 5) {
            discardReports++;
            SomniumMod.LOGGER.info("[Somnium] Удалены посторонние сущности ({}): {}", batch.size(), types);
        }
    }

    private static int discardReports = 0;

    // =====================================================================
    // Тело в кровати и защита кровати
    // =====================================================================

    private static void protectBed(Participant participant, ServerPlayerEntity player) {
        BlockPos bed = participant.snapshot.bedPos;
        if (bed == null) return;
        BlockState state = player.getWorld().getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)) return;
        participant.bedBlocks.add(bed);
        participant.bedBlocks.add(state.get(BedBlock.PART) == BedPart.HEAD
                ? bed.offset(state.get(BedBlock.FACING).getOpposite())
                : bed.offset(state.get(BedBlock.FACING)));
    }

    private static void spawnSleepingBody(Participant participant, ServerPlayerEntity player) {
        BlockPos bed = participant.snapshot.bedPos;
        if (bed == null || !(player.getWorld() instanceof ServerWorld world)) return;
        SleepingBodyEntity body = ModEntities.SLEEPING_BODY.create(world);
        if (body == null) return;
        body.setOwner(player);
        body.refreshPositionAndAngles(bed.getX() + 0.5, bed.getY() + 0.5625, bed.getZ() + 0.5, player.getYaw(), 0.0f);
        world.spawnEntity(body);
        body.sleep(bed);
        participant.bodyId = body.getUuid();
    }

    private static void removeSleepingBody(MinecraftServer server, Participant participant) {
        if (participant.bodyId == null) return;
        ServerWorld world = server.getWorld(participant.snapshot.returnWorld);
        if (world != null && world.getEntity(participant.bodyId) instanceof SleepingBodyEntity body) {
            body.removeFromBed();
        }
        participant.bodyId = null;
    }

    // =====================================================================
    // Взаимодействия (SomniumEvents)
    // =====================================================================

    public static ActionResult onUseBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        Participant p = PARTICIPANTS.get(player.getUuid());
        if (p == null || player.getWorld() != p.session.world) return ActionResult.PASS;
        ActionResult result = p.session.scenario.onUseBlock(p.session, player, hand, hit);
        if (result != ActionResult.PASS) return result;
        if (player.getWorld().getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock) {
            // В измерениях снов кровати взрываются, как в Незере — глушим и подсказываем
            DreamHints.show(player, Text.translatable("somnium.dream.bed_does_not_work").formatted(Formatting.GRAY), 4);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    public static ActionResult onUseItem(ServerPlayerEntity player, Hand hand) {
        Participant p = PARTICIPANTS.get(player.getUuid());
        if (p == null || player.getWorld() != p.session.world) return ActionResult.PASS;
        return p.session.scenario.onUseItem(p.session, player, hand);
    }

    public static ActionResult onUseEntity(ServerPlayerEntity player, Hand hand, Entity entity) {
        Participant p = PARTICIPANTS.get(player.getUuid());
        if (p == null || player.getWorld() != p.session.world) return ActionResult.PASS;
        return p.session.scenario.onUseEntity(p.session, player, hand, entity);
    }

    /** Игрок доел/допил предмет (ItemStackMixin) — стек ещё не израсходован. */
    public static void onFinishUsing(ServerPlayerEntity player, ItemStack stack) {
        Participant p = PARTICIPANTS.get(player.getUuid());
        if (p == null || player.getWorld() != p.session.world) return;
        p.session.scenario.onFinishUsing(p.session, player, stack);
    }

    /** Звон обычного колокола будит тех, чья кровать в радиусе 5 блоков. */
    public static void onRealBellRung(ServerWorld world, BlockPos bellPos) {
        for (Participant p : new ArrayList<>(PARTICIPANTS.values())) {
            BlockPos bed = p.snapshot.bedPos;
            if (bed == null || !p.snapshot.returnWorld.equals(world.getRegistryKey())) continue;
            if (bed.getSquaredDistance(bellPos) > REAL_BELL_RADIUS * REAL_BELL_RADIUS) continue;
            ServerPlayerEntity sleeper = world.getServer().getPlayerManager().getPlayer(p.playerId);
            if (sleeper != null && requestWake(sleeper, DreamOutcome.WOKE_EARLY)) {
                sleeper.sendMessage(Text.translatable("somnium.dream.bell_woke").formatted(Formatting.YELLOW), false);
            }
        }
    }

    /** Возвращает мир возврата для игрока во сне (для команд/отладки). */
    @Nullable
    public static RegistryKey<World> returnWorldOf(UUID playerId) {
        Participant p = PARTICIPANTS.get(playerId);
        return p == null ? null : p.snapshot.returnWorld;
    }
}
