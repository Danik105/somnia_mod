package com.somnium.mod.dream;

import com.somnium.mod.SomniumMod;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/**
 * Управляет совместными сеансами снов для нескольких игроков.
 *
 * ИЗМЕНЕНО (задача мультиплеера "игроков закидывало в один сон"):
 * вместо round-robin индексов — группы. Игрок, засыпающий в течение
 * JOIN_WINDOW_TICKS после входа другого игрока в сон, присоединяется к его
 * ГРУППЕ: один и тот же тип сна, одно измерение, одна сцена (лабиринт/стол/
 * дверь общие, см. DreamManager#sessionKey). Тип сна для группы выбирает
 * лидер — СЛУЧАЙНО (требование "сны не всегда одинаковые ... в один сон").
 *
 * groupId = UUID лидера на момент создания группы. Он СТАБИЛЕН: даже если
 * лидер просыпается первым, группа живёт под тем же id, пока в ней есть
 * игроки — поэтому все per-dream карты в DreamManager, ключированные по
 * sessionKey, не нужно мигрировать при уходе лидера.
 */
public class SharedDreamSession {

    /** Окно объединения: засыпающий присоединяется к группе, если лидер вошёл в сон не позже 10 секунд назад */
    public static final long JOIN_WINDOW_TICKS = 200L;

    /** Информация о группе общего сна */
    public static final class GroupInfo {
        public final Identifier dreamId;
        public final long enterTick;          // тик входа ЛИДЕРА (для окна объединения)
        public final Set<UUID> members = new LinkedHashSet<>();
        public BlockPos sharedSpawn;          // точка спавна лидера в сне (joiner'ы спавнятся рядом)
        public BlockPos doorPos;              // общая дверь пробуждения (может быть null)
        public UUID bossTargetUuid;           // общий босс (может быть null)

        GroupInfo(Identifier dreamId, long enterTick) {
            this.dreamId = dreamId;
            this.enterTick = enterTick;
        }
    }

    /** groupId -> данные группы */
    private static final Map<UUID, GroupInfo> GROUPS = new HashMap<>();
    /** playerId -> groupId его текущей группы */
    private static final Map<UUID, UUID> PLAYER_GROUP = new HashMap<>();

    /**
     * Ищет группу, к которой может присоединиться засыпающий игрок: лидер вошёл
     * в сон не позже JOIN_WINDOW_TICKS назад и в группе ещё есть спящие.
     *
     * @param activePlayers UUID игроков с активным сном (DreamManager.ACTIVE.keySet())
     * @return groupId или null, если подходящей группы нет — игрок начинает свою
     */
    public static UUID findJoinableGroup(Set<UUID> activePlayers, long now) {
        for (var entry : GROUPS.entrySet()) {
            GroupInfo info = entry.getValue();
            if (now - info.enterTick > JOIN_WINDOW_TICKS) continue;
            // В группе должен остаться хотя бы один реально спящий игрок
            boolean anyoneActive = false;
            for (UUID member : info.members) {
                if (activePlayers.contains(member)) { anyoneActive = true; break; }
            }
            if (anyoneActive) return entry.getKey();
        }
        return null;
    }

    /** Создаёт новую группу с лидером-игроком и выбранным для группы сном. */
    public static UUID createGroup(UUID leaderId, Identifier dreamId, long enterTick) {
        GroupInfo info = new GroupInfo(dreamId, enterTick);
        info.members.add(leaderId);
        GROUPS.put(leaderId, info); // groupId == UUID лидера
        PLAYER_GROUP.put(leaderId, leaderId);
        return leaderId;
    }

    /** Присоединяет игрока к существующей группе. */
    public static void joinGroup(UUID playerId, UUID groupId) {
        GroupInfo info = GROUPS.get(groupId);
        if (info == null) return;
        info.members.add(playerId);
        PLAYER_GROUP.put(playerId, groupId);
    }

    /** Убирает игрока из группы; пустую группу удаляет. Возвращает true, если группа опустела. */
    public static boolean leaveGroup(UUID playerId) {
        UUID groupId = PLAYER_GROUP.remove(playerId);
        if (groupId == null) return true;
        GroupInfo info = GROUPS.get(groupId);
        if (info == null) return true;
        info.members.remove(playerId);
        if (info.members.isEmpty()) {
            GROUPS.remove(groupId);
            return true;
        }
        return false;
    }

    /** groupId группы игрока (== sessionKey для карт общего стейта снов), или null если не в группе. */
    public static UUID getGroupId(UUID playerId) {
        return PLAYER_GROUP.get(playerId);
    }

    /** Данные группы по groupId, или null. */
    public static GroupInfo getGroup(UUID groupId) {
        return groupId == null ? null : GROUPS.get(groupId);
    }

    /**
     * ИЗМЕНЕНО (требование "один случайный тип сна на всю группу"): выбор сна лидером
     * группы теперь СЛУЧАЙНЫЙ из всех зарегистрированных — round-robin заменён,
     * т.к. он раскидывал одновременно засыпающих игроков по разным снам.
     * Параметр sanity сохранён в сигнатуре для совместимости вызовов.
     */
    public static DreamType pickNextDream(ServerPlayerEntity player, float sanity) {
        List<DreamType> allDreams = new ArrayList<>(DreamRegistry.all().values());
        if (allDreams.isEmpty()) {
            throw new IllegalStateException("No dreams registered!");
        }
        DreamType selected = allDreams.get(new Random().nextInt(allDreams.size()));
        SomniumMod.LOGGER.debug("[Somnium] Игроку {} выпал сон {}", player.getName().getString(), selected.id());
        return selected;
    }

    // --- Совместимость со старым API (используется в DreamManager.wake/cleanupDreamEntities) ---

    /** @deprecated группы заменили сеансы; оставлено для старых вызовов */
    @Deprecated
    public static void joinSession(UUID playerId, Identifier dreamId) {
        // no-op: членством теперь управляют createGroup/joinGroup/leaveGroup
    }

    /** @deprecated см. выше */
    @Deprecated
    public static void leaveSession(UUID playerId, Identifier dreamId) {
        leaveGroup(playerId);
    }

    /** Есть ли в группе игрока другие члены (для отложенной очистки артефактов сна). */
    public static boolean hasOtherPlayers(UUID playerId, Identifier dreamId) {
        UUID groupId = PLAYER_GROUP.get(playerId);
        GroupInfo info = getGroup(groupId);
        return info != null && info.members.size() > 1;
    }

    /** Очистка при выходе игрока с сервера. */
    public static void clearHistory(UUID playerId) {
        leaveGroup(playerId);
    }
}
