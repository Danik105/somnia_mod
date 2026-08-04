package com.somnium.mod.dream;

import net.minecraft.util.math.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Отслеживает состояние "Двери пробуждения" для каждого игрока в активном сне.
 * Дверь должна быть открыта игроком, и игрок должен ПРОЙТИ через неё (телепортироваться
 * на другую сторону), а не просто подойти близко.
 */
public class WakeDoorTracker {

    /** Для каждого игрока в сне: была ли дверь открыта хотя бы раз */
    private static final Map<UUID, Boolean> DOOR_OPENED = new HashMap<>();

    /** Для каждого игрока: с какой стороны двери он был в последний раз (true = север, false = юг) */
    private static final Map<UUID, Boolean> LAST_SIDE = new HashMap<>();

    /**
     * Вызывается когда игрок входит в сон с целью REACH_DOOR.
     */
    public static void onDreamStart(UUID playerId) {
        DOOR_OPENED.put(playerId, false);
        LAST_SIDE.put(playerId, true); // игрок всегда спавнится с южной стороны двери
    }

    /**
     * Вызывается когда игрок открывает дверь пробуждения.
     */
    public static void onDoorOpened(UUID playerId) {
        DOOR_OPENED.put(playerId, true);
    }

    /**
     * Проверяет, выполнено ли условие выхода: дверь открыта И игрок прошёл через неё.
     * Дверь направлена на север (FACING=NORTH), поэтому проход засчитывается при
     * переходе игрока с одной стороны на другую по оси Z.
     */
    public static boolean isObjectiveComplete(UUID playerId, BlockPos doorPos, BlockPos playerPos) {
        Boolean doorOpened = DOOR_OPENED.get(playerId);
        if (doorOpened == null || !doorOpened) {
            return false; // дверь ещё не открывалась
        }

        Boolean lastSide = LAST_SIDE.get(playerId);
        if (lastSide == null) return false;

        // Дверь стоит на doorPos с FACING=NORTH, т.е. петли вдоль оси X
        // Игрок спавнится с южной стороны (Z > doorPos.Z)
        // Прохождение = переход с южной (Z > doorPos.Z) на северную сторону (Z < doorPos.Z)
        boolean currentSide = playerPos.getZ() > doorPos.getZ();

        // Если игрок перешёл с южной стороны (lastSide=true) на северную (currentSide=false)
        if (lastSide && !currentSide) {
            return true; // успешно прошёл через дверь
        }

        // Обновляем последнюю сторону для следующей проверки
        LAST_SIDE.put(playerId, currentSide);
        return false;
    }

    /**
     * Очистка данных при выходе из сна.
     */
    public static void onDreamEnd(UUID playerId) {
        DOOR_OPENED.remove(playerId);
        LAST_SIDE.remove(playerId);
    }
}
