package com.somnium.mod.dream;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.network.DreamHudPayload;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Отвечает за сам "переход в сон": сохраняет точку возврата, телепортирует
 * игрока в измерение выбранного сна, ставит таймер и цель, а по завершении
 * возвращает игрока обратно и передаёт результат в SanityManager.
 */
public final class DreamManager {

    private DreamManager() {}

    private static final Random RANDOM = new Random();

    /** На каком расстоянии от точки спавна игрока появляются монстры сна при входе. */
    private static final double MONSTER_SPAWN_MIN_RADIUS = 8.0;
    private static final double MONSTER_SPAWN_MAX_RADIUS = 16.0;
    /** Босс BOSS_KILL спавнится ближе, чем рядовые монстры — бой должен начаться быстро. */
    private static final double BOSS_SPAWN_MIN_RADIUS = 6.0;
    private static final double BOSS_SPAWN_MAX_RADIUS = 10.0;
    /** Дверь пробуждения (REACH_DOOR) — дальше, чтобы нужно было идти через локацию. */
    private static final double DOOR_MIN_RADIUS = 180.0;
    private static final double DOOR_MAX_RADIUS = 250.0;
    /** Собираемые предметы (COLLECT_ITEMS) — рассыпаны в среднем радиусе вокруг спавна. */
    private static final double COLLECT_ITEM_MIN_RADIUS = 6.0;
    private static final double COLLECT_ITEM_MAX_RADIUS = 22.0;
    /** На каком расстоянии до маркера REACH_DOOR цель считается достигнутой. */
    private static final double DOOR_REACH_DISTANCE = 2.5;

    /** Активные сны на игрока: куда вернуть игрока, когда сон закончится. */
    private static final Map<UUID, ActiveDream> ACTIVE = new HashMap<>();

    /**
     * ДОБАВЛЕНО (мультиплеер, защита кровати): запоминаем обе половины кровати спящего игрока.
     * Пока игрок во сне, его кровать защищена от разрушения (см. регистрацию в SomniumMod).
     */
    private static final Map<UUID, List<BlockPos>> PROTECTED_BEDS = new HashMap<>();

    /**
     * ДОБАВЛЕНО (мультиплеер, "видимое спящее тело"): хранит UUID спящих тел, которые остаются
     * в реальном мире, пока игрок находится в измерении сна. Другие игроки видят эти тела лежащими.
     * (см. spawnSleepingBody/removeSleepingBody)
     */
    private static final Map<UUID, UUID> SLEEPING_BODIES = new HashMap<>();

    /**
     * ДОБАВЛЕНО (мультиплеер): возвращает sessionKey (groupId) для игрока, или его собственный UUID
     * если игрок не в группе. Используется как ключ для per-dream карт общего стейта (лабиринты,
     * комнаты, столы и т.д.)
     */
    private static UUID sessionKey(UUID playerId) {
        UUID groupId = SharedDreamSession.getGroupId(playerId);
        return groupId != null ? groupId : playerId;
    }

    /** Игроки, которые легли спать и ждут перехода — храним тик, на котором нужно телепортировать. */
    private static final Map<UUID, Long> PENDING_SLEEP = new HashMap<>();

    /**
     * ИСПРАВЛЕНИЕ (мгновенная "смерть" при входе в сон): страховочное окно неуязвимости сразу
     * после телепортации в измерение сна/обратно в явь. Раньше единственной защитой от фатального
     * урона при приземлении была отмена самой смерти в ServerPlayerEntityMixin — но урон всё равно
     * успевал применяться, и при плохо готовом хайтмапе игрок мог упасть с большой высоты и тут же
     * "проснуться", даже не увидев сон. Основная причина (нестабильный хайтмап) исправлена в
     * ModDimensions#platformSurfaceY, а это — дополнительная страховка на случай будущих расхождений
     * (например, когда плейсхолдер-генератор заменят на настоящую процедурную генерацию
     * Приоритета 2, где поверхность заранее не известна арифметически).
     */
    private static final Map<UUID, Long> LANDING_IMMUNITY_UNTIL = new HashMap<>();
    private static final long LANDING_IMMUNITY_TICKS = 20; // 1 секунда

    /**
     * ДОБАВЛЕНО ("добавь тревожную музыку", "никакого шёпота нету"): когда каждому игроку в
     * активном сне пора сыграть следующий звук атмосферы/шёпота — см. tickDreamAmbience().
     * Раньше сны вообще не издавали никаких периодических звуков, только разовые тайтлы.
     */
    private static final Map<UUID, Long> NEXT_AMBIENCE_TICK = new HashMap<>();
    private static final Map<UUID, Long> NEXT_WHISPER_TICK = new HashMap<>();

    // ДОБАВЛЕНО (сон "Лес теней", финальная цель "дойди до Дерева-Маяка, не обернувшись"):
    /** Тик последнего шёпота за спиной игрока — открывает "окно соблазна" обернуться */
    private static final Map<UUID, Long> LAST_WHISPER_TICK = new HashMap<>();
    /** Направление взгляда (yaw) игрока в момент шёпота — шёпот звучит строго сзади */
    private static final Map<UUID, Float> WHISPER_YAW = new HashMap<>();
    /** Когда показывать следующую цепочку ведьминых огней к Дереву-Маяку */
    private static final Map<UUID, Long> NEXT_GUIDE_LIGHT_TICK = new HashMap<>();
    /** Каждые ~2.5 сек перед игроком загорается цепочка огоньков к цели */
    private static final int GUIDE_LIGHT_INTERVAL = 50;
    /** Сколько тиков после шёпота действует "соблазн обернуться" (4 секунды) */
    private static final int WHISPER_TURN_WINDOW = 80;
    /** Поворот больше этого угла в окне соблазна = "обернулся на шёпот" */
    private static final float WHISPER_TURN_ANGLE = 110f;

    // ДОБАВЛЕНО (сон "Лес теней", новый выход "Дерево-Маяк" вместо двери): прогресс "канала" —
    // сколько тиков игрок непрерывно стоит в свете Дерева-Маяка. Ключ — playerId, как и у
    // остальных трекеров этого сна (огни/шёпот тоже per-player).
    private static final Map<UUID, Integer> BEACON_TREE_CHANNEL_TICKS = new HashMap<>();
    /** Радиус света Дерева-Маяка, в котором идёт канал пробуждения */
    private static final double BEACON_TREE_CHANNEL_RADIUS = 3.0;
    /** Сколько тиков нужно простоять в свете дерева, чтобы оно забирало игрока из сна (5 сек) */
    private static final int BEACON_TREE_CHANNEL_REQUIRED = 100;

    /**
     * Сколько тиков игрок лежит в кровати (уже "спит" по мнению ванильной игры — экран
     * плавно темнеет сам, это встроенный ванильный эффект isSleeping()), прежде чем его
     * телепортирует в измерение сна. 60 тиков = 3 секунды.
     */
    public static final long SLEEP_TRANSITION_TICKS = 60;

    private record ActiveDream(
            Identifier dreamId,
            RegistryKey<World> returnDimension,
            BlockPos returnPos,
            long enterTick,
            long durationTicks,
            String objective,
            /**
             * ИСПРАВЛЕНИЕ ("попадаю в сон с вещами из реального мира"): снимок инвентаря игрока
             * (основной инвентарь + броня + офф-хенд — всё, что покрывает PlayerInventory#size())
             * на момент засыпания. Хранится в памяти как копии ItemStack — сериализация в NBT не
             * нужна, т.к. используется только внутри одной серверной сессии (симметрично тому, как
             * уже хранится сама точка возврата/таймер сна — тоже только в памяти, см. ACTIVE).
             * При входе в сон реальный инвентарь очищается (см. enterDream), а при пробуждении
             * восстанавливается из этого списка ЦЕЛИКОМ (см. wake) — то, что игрок подобрал во сне,
             * не переносится в явь.
             */
            List<ItemStack> savedInventory,
            /**
             * ДОБАВЛЕНО (сохранение состояния здоровья и сытости): снимок физического состояния
             * игрока на момент засыпания. При пробуждении восстанавливается полностью — урон и
             * голод, полученные во сне, не переносятся в реальность.
             */
            float savedHealth,
            int savedFoodLevel,
            float savedSaturation,
            /**
             * ДОБАВЛЕНО ("задание сна нельзя выполнить"): как именно проверять цель этого
             * конкретного сна — см. DreamObjectiveType и tickActiveDreams().
             */
            DreamObjectiveType objectiveType,
            /** Для REACH_DOOR — координата маркера "Двери пробуждения"; null для других типов. */
            BlockPos doorPos,
            /** Для BOSS_KILL — UUID гарантированно заспавненного экземпляра цели; null иначе. */
            UUID bossTargetUuid,
            /** Для COLLECT_ITEMS — какой предмет и сколько нужно собрать; null/0 для других типов. */
            Identifier collectItemId,
            int collectItemCount
    ) {}

    /**
     * Вызывается при засыпании игрока (см. PlayerEntityMixin#somnium$onSleep).
     * НЕ телепортирует сразу — ставит игрока в очередь на переход через SLEEP_TRANSITION_TICKS
     * тиков. Пока игрок лежит в кровати, ванильная игра сама плавно затемняет экран
     * (встроенный эффект "сна"), и телепорт происходит уже на чёрном экране, а не рывком.
     * Если игрок встанет раньше времени (проснётся вручную/его разбудят) — переход отменяется
     * автоматически в tickPendingSleepTransitions().
     */
    public static void scheduleDream(ServerPlayerEntity player) {
        MinecraftServer server = ((ServerWorld) player.getEntityWorld()).getServer();
        if (server == null) return;
        PENDING_SLEEP.put(player.getUuid(), server.getTicks() + SLEEP_TRANSITION_TICKS);
    }

    /**
     * ДОБАВЛЕНО (команда /testdream): мгновенно запускает полноценный сон с указанным типом,
     * минуя ожидание в кровати. Используется командой для быстрого тестирования снов.
     * Выполняет все те же действия, что и обычный вход в сон через кровать.
     */
    public static void enterDreamDirectly(ServerPlayerEntity player, DreamType dreamType) {
        if (player == null || dreamType == null) return;
        MinecraftServer server = ((ServerWorld) player.getEntityWorld()).getServer();
        if (server == null) return;
        // Для команды /testdream всегда создаём НОВУЮ группу (не присоединяемся к существующей)
        UUID groupId = SharedDreamSession.createGroup(player.getUuid(), dreamType.id(), server.getTicks());
        enterDreamWithType(player, dreamType, groupId, true);
    }

    /** Вызывается тик-обработчиком: проверяет, не пора ли телепортировать заснувших игроков. */
    public static void tickPendingSleepTransitions(MinecraftServer server) {
        if (PENDING_SLEEP.isEmpty()) return;

        var iterator = PENDING_SLEEP.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (server.getTicks() < entry.getValue()) continue;
            iterator.remove();

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) continue;
            if (!player.isSleeping()) continue; // встал раньше времени — переход отменён

            enterDream(player);
        }
    }

    /** Собственно телепортация в измерение сна — вызывается только из tickPendingSleepTransitions(). */
    private static void enterDream(ServerPlayerEntity player) {
        MinecraftServer server = ((ServerWorld) player.getEntityWorld()).getServer();
        if (server == null) return;

        float sanity = SanityManager.get(player).getSanity();
        long now = server.getTicks();

        // ИЗМЕНЕНО (мультиплеер, задача 8/8): ищем существующую группу в окне объединения
        UUID joinableGroupId = SharedDreamSession.findJoinableGroup(ACTIVE.keySet(), now);

        if (joinableGroupId != null) {
            // Присоединяемся к существующей группе
            SharedDreamSession.GroupInfo groupInfo = SharedDreamSession.getGroup(joinableGroupId);
            if (groupInfo != null) {
                DreamType dream = DreamRegistry.get(groupInfo.dreamId);
                if (dream != null) {
                    SharedDreamSession.joinGroup(player.getUuid(), joinableGroupId);
                    SomniumMod.LOGGER.info("[Somnium] Игрок {} присоединяется к группе {} (сон: {})",
                            player.getName().getString(), joinableGroupId, groupInfo.dreamId);
                    enterDreamWithType(player, dream, joinableGroupId, false);
                    return;
                }
            }
        }

        // Создаём новую группу — игрок становится лидером
        DreamType dream = SharedDreamSession.pickNextDream(player, sanity);
        UUID groupId = SharedDreamSession.createGroup(player.getUuid(), dream.id(), now);
        SomniumMod.LOGGER.info("[Somnium] Игрок {} создаёт новую группу {} (сон: {})",
                player.getName().getString(), groupId, dream.id());
        enterDreamWithType(player, dream, groupId, true);
    }

    /**
     * Общая логика входа в сон с конкретным типом сна.
     *
     * @param player игрок, входящий в сон
     * @param dream тип сна
     * @param groupId UUID группы общего сна
     * @param isLeader true, если игрок создаёт новый инстанс сна (лидер), false если присоединяется к существующему
     */
    private static void enterDreamWithType(ServerPlayerEntity player, DreamType dream, UUID groupId, boolean isLeader) {
        RegistryKey<World> dreamDimension = ModDimensions.forDreamId(dream.id());

        MinecraftServer server = ((ServerWorld) player.getEntityWorld()).getServer();
        if (server == null) return;
        ServerWorld dreamWorld = server.getWorld(dreamDimension);
        if (dreamWorld == null) {
            SomniumMod.LOGGER.warn("[Somnium] Измерение сна {} не найдено — проверьте datapack измерений!", dreamDimension.getValue());
            return;
        }

        // ДОБАВЛЕНО: триггерим ачивку "Первое сноведение" при первом входе в любой сон
        com.somnium.mod.advancement.ModCriteria.FIRST_DREAM.trigger(player);

        // ИСПРАВЛЕНИЕ ("попадаю в сон с вещами из реального мира"): снимаем копию инвентаря
        // ДО телепортации и очищаем его — во сне у игрока должно быть пусто. Оригинальные вещи
        // хранятся в ActiveDream и возвращаются в wake() целиком, что бы ни случилось во сне.
        List<ItemStack> savedInventory = snapshotAndClearInventory(player);

        // ДОБАВЛЕНО (сохранение состояния здоровья и сытости): фиксируем физическое состояние
        // игрока ДО телепортации в сон — при пробуждении оно будет восстановлено полностью.
        float savedHealth = player.getHealth();
        int savedFoodLevel = player.getHungerManager().getFoodLevel();
        float savedSaturation = player.getHungerManager().getSaturationLevel();

        // ВАЖНО: точку и измерение возврата нужно зафиксировать ДО телепортации — иначе
        // player.getEntityWorld()/getBlockPos() ниже вернут уже координаты сна, а не яви.
        RegistryKey<World> returnDimension = player.getEntityWorld().getRegistryKey();
        BlockPos returnPos = player.getBlockPos();

        // ДОБАВЛЕНО (мультиплеер, защита кровати): запоминаем позиции обеих половин кровати
        protectPlayerBed(player, returnPos);

        // ИЗМЕНЕНО (мультиплеер): лидер генерирует мир и выбирает точку спавна, присоединяющийся
        // копирует уже готовые координаты из GroupInfo и спавнится рядом с лидером
        BlockPos spawnPos;
        SharedDreamSession.GroupInfo groupInfo = SharedDreamSession.getGroup(groupId);

        if (isLeader) {
            // Лидер: генерируем мир сна и выбираем точку спавна
            spawnPos = findDreamSpawn(dreamWorld);

            // ДОБАВЛЕНО (сон "Зеркальная комната"): создаём комнату ПЕРЕД телепортацией и корректируем spawnPos
            // ИЗМЕНЕНО: используем groupId вместо player.getUuid() для ключа общего стейта
            if (dream.id().equals(SomniumMod.id("mirror_room"))) {
                spawnPos = setupMirrorRoomAndGetPlayerSpawn(dreamWorld, spawnPos, groupId);
            }

            // ДОБАВЛЕНО (сон "Обрушающаяся шахта"): создаём процедурный лабиринт ПЕРЕД телепортацией
            // ИЗМЕНЕНО: используем groupId вместо player.getUuid() для ключа общего стейта
            if (dream.id().equals(SomniumMod.id("collapsing_mine"))) {
                spawnPos = setupCollapsingMineAndGetPlayerSpawn(dreamWorld, spawnPos, groupId);
            }

            // Сохраняем точку спавна в GroupInfo для присоединяющихся игроков
            if (groupInfo != null) {
                groupInfo.sharedSpawn = spawnPos;
            }
        } else {
            // Присоединяющийся: пропускаем генерацию, спавнимся рядом с лидером
            if (groupInfo == null || groupInfo.sharedSpawn == null) {
                SomniumMod.LOGGER.error("[Somnium] Игрок {} пытается присоединиться к группе {}, но точка спавна не найдена!",
                        player.getName().getString(), groupId);
                // Fallback: генерируем свою точку
                spawnPos = findDreamSpawn(dreamWorld);
            } else {
                // Спавн рядом с лидером (в радиусе 3-5 блоков)
                double angle = RANDOM.nextDouble() * Math.PI * 2;
                double dist = 3.0 + RANDOM.nextDouble() * 2.0;
                int offsetX = (int) Math.round(Math.cos(angle) * dist);
                int offsetZ = (int) Math.round(Math.sin(angle) * dist);
                spawnPos = groupInfo.sharedSpawn.add(offsetX, 0, offsetZ);
                SomniumMod.LOGGER.info("[Somnium] Игрок {} присоединяется к группе {} в точке {}",
                        player.getName().getString(), groupId, spawnPos);
            }
        }

        // ДОБАВЛЕНО (сон "Сон-в-сне"): копируем чанк вокруг игрока из реального мира
        // ИЗМЕНЕНО: только лидер выполняет генерацию, присоединяющийся пропускает
        if (isLeader && dream.id().equals(SomniumMod.id("dream_within_dream"))) {
            BlockPos realWorldPos = player.getBlockPos();
            ServerWorld realWorld = (ServerWorld) player.getEntityWorld();

            // Сохраняем реальную позицию игрока
            DREAM_WITHIN_DREAM_ORIGINAL_POS.put(player.getUuid(), realWorldPos);

            // Копируем время суток и погоду из реального мира
            long realTimeOfDay = realWorld.getTimeOfDay();
            boolean isRaining = realWorld.isRaining();
            boolean isThundering = realWorld.isThundering();

            // Сохраняем погоду реального мира для последующего восстановления
            DREAM_WITHIN_DREAM_ORIGINAL_WEATHER.put(player.getUuid(),
                new WeatherSnapshot(realTimeOfDay, isRaining, isThundering));

            // Копируем чанк (32 чанка = 128x128) вокруг игрока
            ChunkSnapshot snapshot = captureChunk(realWorld, realWorldPos);
            DREAM_WITHIN_DREAM_CHUNKS.put(player.getUuid(), snapshot);

            // Воссоздаём скопированный чанк в измерении сна
            spawnPos = recreateChunkInDream(dreamWorld, snapshot, realWorldPos);

            // Применяем ту же погоду и время суток, что и в реальном мире
            dreamWorld.setTimeOfDay(realTimeOfDay);
            if (isRaining || isThundering) {
                dreamWorld.setWeather(0, 6000, isRaining, isThundering);
            } else {
                dreamWorld.setWeather(6000, 0, false, false);
            }

            SomniumMod.LOGGER.info("[Dream Within Dream] Скопирован чанк из реального мира для игрока {} (время: {}, дождь: {}, гроза: {})",
                player.getName().getString(), realTimeOfDay, isRaining, isThundering);
        }

        // Останавливаем ванильное "лежание в кровати" перед сменой измерения — иначе
        // клиент может остаться в анимации сна на новом месте.
        if (player.isSleeping()) {
            player.wakeUp();
        }

        player.teleport(dreamWorld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                player.getYaw(), player.getPitch());

        // ДОБАВЛЕНО (мультиплеер): создаём спящее тело в реальном мире
        // ИСПРАВЛЕНО: ActiveDream ("active") на этом этапе ещё не создан — используем
        // returnDimension/returnPos, зафиксированные выше ДО телепортации.
        ServerWorld returnWorld = server.getWorld(returnDimension);
        if (returnWorld != null) {
            spawnSleepingBody(player, returnWorld, returnPos);
        }

        // Сбрасываем накопленную дистанцию падения и даём короткое окно неуязвимости —
        // см. комментарий у LANDING_IMMUNITY_UNTIL.
        player.fallDistance = 0;
        player.setVelocity(0, 0, 0);
        grantLandingImmunity(player);

        // ДОБАВЛЕНО (негативные эффекты во время сна): накладываем дебаффы, чтобы сон ощущался
        // как кошмар. ИЗМЕНЕНО: каждый сон теперь имеет свой уникальный набор эффектов,
        // подходящий под его тематику (водные эффекты для Тонущего города, слепота для Шахты и т.д.)
        // ИСКЛЮЧЕНИЕ: "Сон-в-сне" не получает эффектов — должен выглядеть как реальность
        if (!dream.id().equals(SomniumMod.id("dream_within_dream"))) {
            applyDreamDebuffs(player, dream.id(), dream.durationTicks());
        }

        // ИЗМЕНЕНО ("убери сверху название сна и цель сна"): разовый тайтл на весь экран
        // и тексты в верхнем HUD убраны — название и цель сна отправляются в чат, где их
        // всегда можно перечитать; на экране остаётся только тонкая полоса времени сна
        // (см. DreamObjectiveHudRenderer).
        // ИСКЛЮЧЕНИЕ: "Сон-в-сне" не показывает ничего — скрывает, что это сон
        if (!dream.id().equals(SomniumMod.id("dream_within_dream"))) {
            player.sendMessage(net.minecraft.text.Text.translatable(dream.displayNameKey())
                    .formatted(net.minecraft.util.Formatting.RED, net.minecraft.util.Formatting.BOLD), false);
            player.sendMessage(net.minecraft.text.Text.translatable("somnium.dream.objective", dream.objective())
                    .formatted(net.minecraft.util.Formatting.GRAY), false);
            DreamHudPayload.sendActive(player, dream.displayNameKey(), dream.objective(),
                    (int) dream.durationTicks(), (int) dream.durationTicks());
            // ДОБАВЛЕНО: звук входа в сон (но не для dream_within_dream - там должна быть тишина)
            player.playSound(
                    com.somnium.mod.registry.ModSounds.DREAM_ENTER,
                    1.0f,
                    0.7f);
        }

        // ИЗМЕНЕНО (мультиплеер): лидер создаёт монстров и объекты сна, присоединяющийся копирует
        // ссылки на общие объекты (doorPos, bossUuid) из GroupInfo
        UUID bossUuid;
        BlockPos doorPos;

        if (isLeader) {
            // Лидер: спавним монстров и создаём объекты сна
            // ИЗМЕНЕНО: используем groupId вместо player.getUuid() для ключа общего стейта
            bossUuid = spawnDreamMonsters(dreamWorld, spawnPos, dream, groupId);

            // ДОБАВЛЕНО (сон "Лестница в никуда", редизайн "Падающих досок"): лестничная шахта
            // над пустотой — спиральный марш достраивается вверх по мере подъёма игрока.
            // ИЗМЕНЕНО: используем groupId вместо player.getUuid() для ключа общего стейта
            if (dream.id().equals(SomniumMod.id("falling_planks"))) {
                setupStairwell(dreamWorld, spawnPos, groupId);
            }

            // ДОБАВЛЕНО (сон "Кровавый пир", новая механика): пиршественный стол перед игроком
            // ИЗМЕНЕНО: используем groupId вместо player.getUuid() для ключа общего стейта
            if (dream.id().equals(SomniumMod.id("crimson_feast"))) {
                setupCrimsonFeastTable(dreamWorld, spawnPos, groupId);
            }

            // ДОБАВЛЕНО ("задание сна нельзя выполнить"): раньше цель сна была только текстом в HUD —
            // ничего в коде её не отслеживало, поэтому единственным способом закончить сон было
            // дождаться таймаута или умереть. Теперь при входе в сон реально готовится то, что нужно
            // проверить/собрать/победить, а UUID/координата цели сохраняются в ActiveDream ниже.
            // ИЗМЕНЕНО: некоторые сны (например, falling_planks) не имеют цели (objectiveType == null)
            // и завершаются только по таймауту или смерти - для них не спавним маркеры.
            doorPos = null;
            if (dream.objectiveType() == DreamObjectiveType.REACH_DOOR) {
                // ДОБАВЛЕНО: для лабиринта шахты используем позицию из генератора
                // ИЗМЕНЕНО: используем groupId вместо player.getUuid() для ключа общего стейта
                if (dream.id().equals(SomniumMod.id("collapsing_mine"))) {
                    MazeGenerator maze = COLLAPSING_MINE_MAZES.get(groupId);
                    BlockPos origin = COLLAPSING_MINE_ORIGINS.get(groupId);
                    if (maze != null && origin != null) {
                        // ИСПРАВЛЕНО: baseY прибавляется к origin.y — передаём 0, иначе дверь
                        // оказывалась над лабиринтом (удвоенная высота), а не на его полу
                        doorPos = maze.findExitPosition(spawnPos, origin, 0);
                    }
                } else {
                    doorPos = findObjectivePoint(dreamWorld, spawnPos);
                }
                spawnWakeDoorMarker(dreamWorld, doorPos);
                // ДОБАВЛЕНО: инициализируем трекер двери для этого игрока
                WakeDoorTracker.onDreamStart(player.getUuid());
            } else if (dream.objectiveType() == DreamObjectiveType.COLLECT_ITEMS) {
                spawnCollectibleItems(dreamWorld, spawnPos, dream.objectiveTargetId(), dream.objectiveCount());
            }

            // Сохраняем общие объекты в GroupInfo для присоединяющихся игроков
            if (groupInfo != null) {
                groupInfo.doorPos = doorPos;
                groupInfo.bossTargetUuid = bossUuid;
            }
        } else {
            // Присоединяющийся: пропускаем спавн монстров и создание объектов, копируем ссылки из GroupInfo
            if (groupInfo != null) {
                bossUuid = groupInfo.bossTargetUuid;
                doorPos = groupInfo.doorPos;
                // Инициализируем трекер двери для присоединившегося игрока, если цель — REACH_DOOR
                if (dream.objectiveType() == DreamObjectiveType.REACH_DOOR) {
                    WakeDoorTracker.onDreamStart(player.getUuid());
                }
            } else {
                SomniumMod.LOGGER.warn("[Somnium] Игрок {} присоединяется к группе {}, но GroupInfo не найдена!",
                        player.getName().getString(), groupId);
                bossUuid = null;
                doorPos = null;
            }
        }

        ACTIVE.put(player.getUuid(), new ActiveDream(
                dream.id(),
                returnDimension,
                returnPos,
                server.getTicks(),
                dream.durationTicks(),
                dream.objective(),
                savedInventory,
                savedHealth,
                savedFoodLevel,
                savedSaturation,
                dream.objectiveType(),
                doorPos,
                bossUuid,
                dream.objectiveType() == DreamObjectiveType.COLLECT_ITEMS ? dream.objectiveTargetId() : null,
                dream.objectiveCount()
        ));

        // УСТАРЕЛО: регистрация в сеансе сна теперь выполняется через SharedDreamSession.createGroup/joinGroup
        // которые уже были вызваны в enterDream() — этот вызов больше не нужен
        // SharedDreamSession.joinSession(player.getUuid(), dream.id());

        // ИСПРАВЛЕНО (сон "Зеркальная комната"): устанавливаем позицию стекла с задержкой
        // Делаем это ПОСЛЕ добавления в ACTIVE, через 10 тиков когда моб точно загружен
        // ИЗМЕНЕНО (мультиплеер): только лидер инициализирует зеркало, присоединяющиеся его пропускают
        if (isLeader && dream.id().equals(SomniumMod.id("mirror_room")) && bossUuid != null) {
            UUID finalBossUuid = bossUuid;
            UUID finalPlayerId = player.getUuid();
            // Сохраняем запрос на инициализацию в очередь, обработка в tickActiveDreams()
            PENDING_MIRROR_INIT.put(finalPlayerId, new MirrorInitData(finalBossUuid, 10));
        }

        // ДОБАВЛЕНО (сон "Тонущий город"): инициализируем начальный уровень воды и включаем дождь
        // ИЗМЕНЕНО (мультиплеер): используем groupId вместо player.getUuid() для ключа общего стейта,
        // но только лидер инициализирует начальный уровень воды — присоединяющиеся копируют текущий уровень
        if (dream.id().equals(SomniumMod.id("drowning_city"))) {
            if (isLeader) {
                DROWNING_CITY_WATER_LEVEL.put(groupId, DROWNING_CITY_START_Y);
                // Принудительно включаем дождь для атмосферы затопления
                dreamWorld.setWeather(0, 999999, true, false); // clearDuration=0, rainDuration=999999, raining=true, thundering=false
            }
            // Присоединяющиеся видят текущий уровень воды из общего стейта (уже в карте по groupId)
        }

        // TODO (Приоритет 2): заспавнить/сгенерировать саму структуру локации сна (dream.structureTemplate()),
        // если это не "PROCEDURAL" — например, вставить заранее собранный NBT-шаблон вокруг spawnPos.
        // Сейчас локация целиком берётся из плейсхолдер-генератора измерения (data/somnium/dimension/*.json).
    }

    /**
     * Спавнит начальный набор монстров сна вокруг точки входа игрока.
     * Количество: 1 экземпляр каждого типа монстра, перечисленного в dream.monsterEntityIds(),
     * плюс дополнительный "довесок" для снов с одним типом монстра, чтобы не было слишком пусто.
     *
     * ИЗМЕНЕНО (мультиплеер): теперь принимает sessionKey (groupId) вместо playerId для доступа
     * к общему стейту сна (лабиринты, комнаты и т.д.)
     *
     * @param sessionKey UUID группы общего сна (groupId) — ключ для доступа к per-dream картам
     * @return UUID гарантированно заспавненного "боя цели" для DreamObjectiveType.BOSS_KILL,
     *         или null, если у этого сна нет цели типа "победить конкретную сущность".
     */
    private static UUID spawnDreamMonsters(ServerWorld world, BlockPos center, DreamType dream, UUID sessionKey) {
        var monsterIds = dream.monsterEntityIds();
        if (monsterIds.isEmpty()) return null;

        boolean isBossDream = dream.objectiveType() == DreamObjectiveType.BOSS_KILL
                && dream.objectiveTargetId() != null;

        // ДОБАВЛЕНО (сон "Зеркальная комната"): специальный спавн для зеркального двойника
        boolean isMirrorRoom = dream.id().equals(SomniumMod.id("mirror_room"));

        // ДОБАВЛЕНО (сон "Обрушающаяся шахта"): специальный спавн монстров в лабиринте
        boolean isCollapsingMine = dream.id().equals(SomniumMod.id("collapsing_mine"));

        UUID bossUuid = null;
        if (isBossDream) {
            // ИСПРАВЛЕНИЕ (боевой баг): раньше сны с одним типом монстра ("монокошмарные") всегда
            // получали +2 "довеска" (extrasForSingleMonsterDreams ниже) — из-за этого, например,
            // "Сон-в-сне" реально спавнил ТРИ Кошмара-Амальгамы одновременно, хотя это уникальный
            // босс, а цель "Победить Кошмар" в единственном числе. Теперь для целей BOSS_KILL
            // спавнится ровно один гарантированный экземпляр-цель — ближе к игроку, чтобы бой
            // не превращался в беготню по всей платформе, — а его UUID запоминается для проверки
            // в tickActiveDreams().
            BlockPos bossSpawnPos;
            if (isMirrorRoom) {
                // ИСПРАВЛЕНИЕ: Зеркальный двойник спавнится строго на противоположной стороне комнаты
                // (X=7 от угла комнаты), за стеклянной стеной, а не в случайном месте
                // ИЗМЕНЕНО: используем sessionKey вместо playerId для доступа к общему стейту
                bossSpawnPos = getMirrorMonsterSpawnPos(center, sessionKey);
            } else {
                bossSpawnPos = randomRingPosition(world, center, BOSS_SPAWN_MIN_RADIUS, BOSS_SPAWN_MAX_RADIUS);
            }
            var boss = spawnOneAt(world, dream.objectiveTargetId(), bossSpawnPos);
            if (boss != null) bossUuid = boss.getUuid();
        }

        // ДОБАВЛЕНО: для лабиринта используем специальный спавн в случайных ячейках
        if (isCollapsingMine) {
            MazeGenerator maze = COLLAPSING_MINE_MAZES.get(sessionKey);
            BlockPos origin = COLLAPSING_MINE_ORIGINS.get(sessionKey);
            if (maze != null && origin != null) {
                // Спавним больше монстров в лабиринте - по 3-4 каждого типа
                int monstersPerType = 4;
                for (Identifier entityId : monsterIds) {
                    // ИСПРАВЛЕНО: baseY прибавляется к origin.y — передаём 0, иначе мобы
                    // появлялись на удвоенной высоте над лабиринтом и не попадали в него
                    List<BlockPos> spawnPositions = maze.findMonsterSpawnPositions(origin, 0, monstersPerType);
                    for (BlockPos pos : spawnPositions) {
                        spawnOneAt(world, entityId, pos);
                    }
                }
            }
        } else {
            int extrasForSingleMonsterDreams = (monsterIds.size() == 1 && !isBossDream) ? 2 : 0;

            for (Identifier entityId : monsterIds) {
                if (isBossDream && entityId.equals(dream.objectiveTargetId())) continue; // уже заспавнен как босс выше
                spawnOne(world, center, entityId, MONSTER_SPAWN_MIN_RADIUS, MONSTER_SPAWN_MAX_RADIUS);
                for (int i = 0; i < extrasForSingleMonsterDreams; i++) {
                    spawnOne(world, center, entityId, MONSTER_SPAWN_MIN_RADIUS, MONSTER_SPAWN_MAX_RADIUS);
                }
            }
        }
        return bossUuid;
    }

    private static MobEntity spawnOne(ServerWorld world, BlockPos center, Identifier entityId, double minRadius, double maxRadius) {
        // ВАЖНО: Registries.ENTITY_TYPE — DefaultedRegistry с дефолтом "minecraft:pig".
        // Обычный .get(id) НИКОГДА не возвращает null для незарегистрированного id — он молча
        // отдаёт свинью, и предупреждение ниже просто не срабатывало (баг: монстры сна тихо
        // подменялись свиньями либо не подменялись вовсе, если совпадение было случайным).
        // getOptionalValue(id) — единственный надёжный способ проверить, что сущность реально
        // зарегистрирована в ModEntities, вместо .get(), который на DefaultedRegistry тихо
        // возвращает дефолт (см. комментарий выше).
        var maybeType = Registries.ENTITY_TYPE.getOrEmpty(entityId);
        if (maybeType.isEmpty()) {
            SomniumMod.LOGGER.warn("[Somnium] Неизвестная сущность монстра сна: {} (не зарегистрирована в ModEntities)", entityId);
            return null;
        }
        EntityType<?> type = maybeType.get();

        BlockPos pos = randomRingPosition(world, center, minRadius, maxRadius);
        var entity = type.create(world);
        if (entity instanceof MobEntity mob) {
            mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RANDOM.nextFloat() * 360f, 0);

            // ИСПРАВЛЕНИЕ (сон mirror_room): специальная инициализация для MirrorReflectionEntity
            if (mob instanceof com.somnium.mod.entity.nightmare.MirrorReflectionEntity mirrorEntity) {
                // Находим ближайшего игрока для установки цели зеркального двойника
                var nearestPlayer = world.getClosestPlayer(
                    mob.getX(), mob.getY(), mob.getZ(),
                    100.0, // радиус поиска
                    false
                );
                if (nearestPlayer instanceof ServerPlayerEntity serverPlayer) {
                    mirrorEntity.setTargetPlayer(serverPlayer);
                    // НЕ устанавливаем позицию зеркала здесь - она будет установлена после
                    // в enterDreamWithType() с правильными абсолютными координатами стекла
                }
            }

            world.spawnEntity(mob);
            return mob;
        }
        return null;
    }

    /** Спавнит монстра на конкретной позиции (для зеркальной комнаты) */
    private static MobEntity spawnOneAt(ServerWorld world, Identifier entityId, BlockPos pos) {
        var maybeType = Registries.ENTITY_TYPE.getOrEmpty(entityId);
        if (maybeType.isEmpty()) {
            SomniumMod.LOGGER.warn("[Somnium] Неизвестная сущность монстра сна: {} (не зарегистрирована в ModEntities)", entityId);
            return null;
        }
        EntityType<?> type = maybeType.get();

        var entity = type.create(world);
        if (entity instanceof MobEntity mob) {
            mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RANDOM.nextFloat() * 360f, 0);

            // УБРАНО: не устанавливаем mirrorPlane здесь - будет установлен позже с правильными координатами

            world.spawnEntity(mob);
            return mob;
        }
        return null;
    }

    /** Случайная позиция в кольце вокруг центра, на известной высоте плейсхолдер-платформы мира сна. */
    private static BlockPos randomRingPosition(ServerWorld world, BlockPos center, double minRadius, double maxRadius) {
        double angle = RANDOM.nextDouble() * Math.PI * 2;
        double dist = minRadius + RANDOM.nextDouble() * (maxRadius - minRadius);
        int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
        int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
        // ИСПРАВЛЕНИЕ (та же причина, что и в findDreamSpawn/ModDimensions#platformSurfaceY):
        // world.getTopY(...) не гарантированно готов для ещё не подгруженных дальних чанков —
        // используем ту же арифметически известную высоту плейсхолдер-платформы, а не хайтмап.
        int y = ModDimensions.platformSurfaceY(world.getRegistryKey());
        return new BlockPos(x, y, z);
    }

    /**
     * ДОБАВЛЕНО (сон "Лес теней", новый выход): Дерево-Маяк — мёртвое дерево из тёмного дуба
     * со светящимся "сердцем" и фонарями душ. Заменяет Дверь пробуждения только для этого сна:
     * выход — не пройти сквозь объект, а ПРОСТОЯТЬ в свете дерева, пока идёт канал
     * (см. tickShadowForest, BEACON_TREE_CHANNEL_*).
     */
    private static void buildBeaconTree(ServerWorld world, BlockPos pos) {
        // Ствол из тёмного дуба, 5 блоков
        for (int dy = 0; dy <= 4; dy++) {
            world.setBlockState(pos.up(dy), net.minecraft.block.Blocks.DARK_OAK_LOG.getDefaultState());
        }
        // Светящееся "сердце" на вершине — маяк, видимый сквозь темноту леса издалека
        world.setBlockState(pos.up(5), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        // Ветви на высоте 4 и подвесные фонари душ под их концами
        BlockPos[] branches = { pos.add(1, 4, 0), pos.add(-1, 4, 0), pos.add(0, 4, 1), pos.add(0, 4, -1) };
        for (BlockPos branch : branches) {
            world.setBlockState(branch, net.minecraft.block.Blocks.DARK_OAK_LOG.getDefaultState());
            world.setBlockState(branch.down(), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState()
                    .with(net.minecraft.block.LanternBlock.HANGING, true));
        }
        // Кольцо фонарей душ у подножия — видимая граница "света дерева"
        world.setBlockState(pos.add(2, 0, 2), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState());
        world.setBlockState(pos.add(-2, 0, 2), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState());
        world.setBlockState(pos.add(2, 0, -2), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState());
        world.setBlockState(pos.add(-2, 0, -2), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState());

        // Имя над деревом (невидимый стенд, как у двери) — видно издалека
        ArmorStandEntity marker = new ArmorStandEntity(world, pos.getX() + 0.5, pos.getY() + 6.5, pos.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomName(net.minecraft.text.Text.literal("§b§lДерево-Маяк"));
        marker.setCustomNameVisible(true);
        world.spawnEntity(marker);
    }

    /**
     * Точка цели REACH_DOOR — дальше от игрока, чем обычные монстры, чтобы до неё нужно было
     * дойти/добежать через локацию сна, а не оказаться рядом сразу же.
     */
    private static BlockPos findObjectivePoint(ServerWorld world, BlockPos center) {
        return randomRingPosition(world, center, DOOR_MIN_RADIUS, DOOR_MAX_RADIUS);
    }

    /**
     * ИЗМЕНЕНО: вместо невидимого маркера теперь ставится настоящая белая берёзовая дверь,
     * которую нужно открыть и пройти сквозь неё. Дверь окружена светящимися блоками для видимости.
     */
    private static void spawnWakeDoorMarker(ServerWorld world, BlockPos pos) {
        // Ставим фундамент из гладкого кварца (красивая белая платформа)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos basePos = pos.add(x, -1, z);
                world.setBlockState(basePos, net.minecraft.block.Blocks.SMOOTH_QUARTZ.getDefaultState());
            }
        }

        // Ставим нижнюю половину берёзовой двери (белая)
        net.minecraft.block.DoorBlock doorBlock = (net.minecraft.block.DoorBlock) net.minecraft.block.Blocks.BIRCH_DOOR;
        world.setBlockState(pos, doorBlock.getDefaultState()
                .with(net.minecraft.block.DoorBlock.FACING, net.minecraft.util.math.Direction.NORTH)
                .with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER));

        // Ставим верхнюю половину двери
        world.setBlockState(pos.up(), doorBlock.getDefaultState()
                .with(net.minecraft.block.DoorBlock.FACING, net.minecraft.util.math.Direction.NORTH)
                .with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER));

        // Рамка вокруг двери из светящихся блоков (sea lantern - яркие белые блоки)
        world.setBlockState(pos.add(-1, 0, 0), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        world.setBlockState(pos.add(1, 0, 0), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        world.setBlockState(pos.add(-1, 1, 0), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        world.setBlockState(pos.add(1, 1, 0), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        world.setBlockState(pos.add(-1, 2, 0), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        world.setBlockState(pos.add(0, 2, 0), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        world.setBlockState(pos.add(1, 2, 0), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());

        // Невидимый маркер над дверью для имени (чтобы игрок видел название издалека)
        ArmorStandEntity marker = new ArmorStandEntity(world, pos.getX() + 0.5, pos.getY() + 2.5, pos.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomName(net.minecraft.text.Text.translatable("somnium.dream.wake_door")
                .copy().formatted(net.minecraft.util.Formatting.WHITE, net.minecraft.util.Formatting.BOLD));
        marker.setCustomNameVisible(true);
        world.spawnEntity(marker);
    }

    /**
     * Раскидывает objectiveCount экземпляров предмета objectiveItemId вокруг точки спавна сна —
     * помечены setNeverDespawn(), чтобы не исчезли раньше, чем игрок успеет их найти (таймауты
     * снов могут быть длиннее стандартного времени жизни предмета на земле).
     */
    private static void spawnCollectibleItems(ServerWorld world, BlockPos center, Identifier itemId, int count) {
        if (itemId == null || count <= 0) return;
        var maybeItem = Registries.ITEM.getOrEmpty(itemId);
        if (maybeItem.isEmpty()) {
            SomniumMod.LOGGER.warn("[Somnium] Неизвестный предмет для сбора в сне: {}", itemId);
            return;
        }
        var item = maybeItem.get();
        for (int i = 0; i < count; i++) {
            BlockPos pos = randomRingPosition(world, center, COLLECT_ITEM_MIN_RADIUS, COLLECT_ITEM_MAX_RADIUS);
            ItemEntity itemEntity = new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5,
                    new ItemStack(item));
            itemEntity.setNeverDespawn();
            world.spawnEntity(itemEntity);
        }
    }

    /**
     * Вызывается тик-обработчиком, если у игрока есть активный сон — проверяет таймаут.
     *
     * ПОПУТНОЕ ИСПРАВЛЕНИЕ: раньше здесь был ACTIVE.forEach(...), а wake() внутри лямбды сам
     * удаляет запись из этой же ACTIVE — то есть при любом естественном завершении сна по
     * таймауту сервер падал с ConcurrentModificationException (HashMap не переживает удаление
     * элемента во время forEach по нему же). Раз этот путь теперь ещё и отвечает за восстановление
     * инвентаря и скип ночи, крашиться он точно не должен — итерируемся по СНИМКУ ключей.
     */
    public static void tickActiveDreams(MinecraftServer server) {
        // ИСПРАВЛЕНИЕ: обрабатываем отложенную инициализацию зеркальных отражений
        if (!PENDING_MIRROR_INIT.isEmpty()) {
            for (UUID playerId : new ArrayList<>(PENDING_MIRROR_INIT.keySet())) {
                MirrorInitData initData = PENDING_MIRROR_INIT.get(playerId);
                if (initData == null) continue;

                if (initData.ticksRemaining <= 0) {
                    // Время инициализировать зеркало
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                    if (player != null && player.getEntityWorld() instanceof ServerWorld dreamWorld) {
                        BlockPos roomCorner = MIRROR_ROOM_CORNER.get(playerId);
                        if (roomCorner != null) {
                            int middleX = 30 / 2; // 15 - центр комнаты (где стекло)
                            int centerZ = 30 / 2; // 15 - центр по Z
                            BlockPos glassPlanePos = roomCorner.add(middleX, 0, centerZ);

                            // Ищем моба по UUID
                            var mobEntity = dreamWorld.getEntity(initData.bossUuid);
                            if (mobEntity instanceof com.somnium.mod.entity.nightmare.MirrorReflectionEntity mirrorMob) {
                                mirrorMob.setMirrorPlane(glassPlanePos);
                                mirrorMob.setTargetPlayer(player);
                                SomniumMod.LOGGER.info("[Somnium] MirrorReflection initialized: mirror at {}, player at {}, mob at {}",
                                    glassPlanePos, player.getBlockPos(), mirrorMob.getBlockPos());
                            } else {
                                SomniumMod.LOGGER.warn("[Somnium] Failed to find MirrorReflection entity with UUID {}", initData.bossUuid);
                            }
                        }
                    }
                    PENDING_MIRROR_INIT.remove(playerId);
                } else {
                    // Уменьшаем счетчик тиков
                    PENDING_MIRROR_INIT.put(playerId, new MirrorInitData(initData.bossUuid, initData.ticksRemaining - 1));
                }
            }
        }

        if (ACTIVE.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(uuid);
            if (active == null) continue; // уже проснулся в этом же тике по другой причине

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            // ДОБАВЛЕНО ("задание сна нельзя выполнить"): проверяем выполнение реальной цели
            // ДО проверки таймаута — если игрок успел выполнить условие в тот же тик, когда
            // истекает время, засчитываем именно успех (лучшая награда), а не простое "пережил".
            if (isObjectiveComplete(player, active)) {
                SomniumMod.LOGGER.info("[Dream Tick] Цель сна {} выполнена игроком {}", active.dreamId(), player.getName().getString());
                wake(player, SanityManager.DreamOutcome.SURVIVED_OBJECTIVE);
                continue;
            }

            long elapsed = server.getTicks() - active.enterTick();
            if (elapsed >= active.durationTicks()) {
                SomniumMod.LOGGER.info("[Dream Tick] Таймаут сна {} для игрока {} ({}/{})",
                    active.dreamId(), player.getName().getString(), elapsed, active.durationTicks());
                wake(player, SanityManager.DreamOutcome.SURVIVED_TIMEOUT);
            }
        }
    }

    /**
     * ДОБАВЛЕНО ("добавь тревожную музыку", "никакого шёпота нету"): раньше ни один сон не
     * издавал вообще никаких периодических звуков — только разовые тайтлы при входе/выходе.
     * Пока сон активен:
     *  - каждые ~8-14 секунд играет общая тревожная фоновая "музыка" (пещерный эмбиент,
     *    ModSounds.DREAM_DREAD_AMBIENCE) — одинаково для всех 7 снов, для атмосферы;
     *  - отдельно, только в "Лесу теней" (shadow_forest), каждые ~9-16 секунд играет "шёпот"
     *    (ModSounds.WHISPER_AMBIENT) — источник звука позиционируется РЕАЛЬНО ЗА СПИНОЙ игрока
     *    (противоположно направлению взгляда на момент проигрывания), ровно как описано в цели
     *    сна ("дойти до Дерева-Маяка, ни разу не обернувшись на шёпот за спиной").
     * Вызывается тик-обработчиком (см. SomniumMod) наравне с tickActiveDreams.
     *
     * Механика "а что если всё-таки обернуться" реализована в tickShadowForest():
     * здесь только запоминаются момент шёпота и yaw игрока (LAST_WHISPER_TICK/WHISPER_YAW).
     */
    public static void tickDreamAmbience(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long now = server.getTicks();

        for (UUID uuid : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(uuid);
            if (active == null) continue;

            // ИСКЛЮЧЕНИЕ: "Сон-в-сне" не получает фоновую музыку через эту функцию -
            // музыка запускается программно через 20 секунд в tickDreamWithinDream()
            if (active.dreamId().equals(SomniumMod.id("dream_within_dream"))) {
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            long nextAmbience = NEXT_AMBIENCE_TICK.getOrDefault(uuid, 0L);
            if (now >= nextAmbience) {
                player.playSound(
                        com.somnium.mod.registry.ModSounds.DREAM_DREAD_AMBIENCE,
                        0.5f,
                        0.7f + RANDOM.nextFloat() * 0.3f);
                NEXT_AMBIENCE_TICK.put(uuid, now + 160 + RANDOM.nextInt(120)); // ~8-14 сек
            }

            if (active.dreamId().equals(SomniumMod.id("shadow_forest"))) {
                long nextWhisper = NEXT_WHISPER_TICK.getOrDefault(uuid, 0L);
                if (now >= nextWhisper) {
                    playWhisperBehindPlayer(player);
                    NEXT_WHISPER_TICK.put(uuid, now + 180 + RANDOM.nextInt(140)); // ~9-16 сек
                    // ДОБАВЛЕНО ("не оборачивайся на шёпот"): запоминаем момент шёпота и
                    // направление взгляда — tickShadowForest проверит, обернулся ли игрок
                    LAST_WHISPER_TICK.put(uuid, now);
                    WHISPER_YAW.put(uuid, player.getYaw());
                }
            }
        }
    }

    /** Проигрывает "шёпот" из точки за спиной игрока — противоположно текущему направлению взгляда. */
    private static void playWhisperBehindPlayer(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return;
        double yawRad = Math.toRadians(player.getYaw());
        double behindX = player.getX() + Math.sin(yawRad) * 3.5;
        double behindZ = player.getZ() - Math.cos(yawRad) * 3.5;
        // ПРОВЕРИТЬ ПРИ ПЕРВОЙ СБОРКЕ: World#playSound(PlayerEntity except, double x, double y,
        // double z, SoundEvent, SoundCategory, float volume, float pitch) — стабильная сигнатура
        // для 1.14+, но в теории могла получить доп. параметр (seed) в новых версиях; если
        // компилятор ругается, добавьте недостающий аргумент по автодополнению IDE.
        // ВАЖНО: except=null означает, что звук услышат ВСЕ игроки рядом с этой точкой в мире
        // сна, а не только тот, за чьей спиной он должен быть — на одиночном сервере (как в
        // вашем crash-report, Player Count: 1/8) это не имеет значения, но при нескольких
        // игроках в одном сне это стоит заменить на адресную отправку пакета только этому игроку.
        world.playSound(null, behindX, player.getY(), behindZ,
                com.somnium.mod.registry.ModSounds.WHISPER_AMBIENT,
                net.minecraft.sound.SoundCategory.HOSTILE,
                0.8f, 0.5f + RANDOM.nextFloat() * 0.3f);
    }

    /**
     * ДОБАВЛЕНО (баланс "Леса теней" по запросу "урон ощущается несправедливо"):
     * грейс-период с момента входа в сон — мобы не должны атаковать игрока,
     * пока он осматривается. 240 тиков = 12 секунд.
     */
    public static final long DREAM_ENTRY_GRACE_TICKS = 240L;

    /**
     * true, если игрок в активном сне и с момента входа прошло меньше
     * DREAM_ENTRY_GRACE_TICKS тиков. Используется сущностями снов для запрета атак.
     */
    public static boolean isInDreamEntryGrace(UUID playerId, long nowTicks) {
        ActiveDream active = ACTIVE.get(playerId);
        return active != null && nowTicks - active.enterTick() < DREAM_ENTRY_GRACE_TICKS;
    }

    /**
     * ДОБАВЛЕНО (сон "Лес теней", финальная цель по запросу "нету конечной цели сна — доделай"):
     * реализует описанное в цели сна правило "дойди до Дерева-Маяка, ни разу не обернувшись".
     * 1) Ведьмины огни: каждые GUIDE_LIGHT_INTERVAL тиков перед игроком загорается цепочка
     *    из трёх огоньков (soul fire) в направлении Двери пробуждения у Дерева-Маяка —
     *    частицы видны даже под Blindness, поэтому цель наконец можно НАЙТИ в темноте.
     * 2) "Не оборачивайся": в течение WHISPER_TURN_WINDOW тиков после шёпота за спиной
     *    проверяется yaw игрока; если он повернулся больше чем на WHISPER_TURN_ANGLE —
     *    прямо перед ним появляется Lurking Shade. Смерть от теней = поражение
     *    (DIED_IN_DREAM, как и везде); выдержать соблазн и дойти до двери = победа.
     */
    public static void tickShadowForest(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long now = server.getTicks();

        for (UUID uuid : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(uuid);
            if (active == null || !active.dreamId().equals(SomniumMod.id("shadow_forest"))) continue;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null || !(player.getEntityWorld() instanceof ServerWorld world)) continue;

            // 1) Ведьмины огни к Дереву-Маяку
            if (active.doorPos() != null && now >= NEXT_GUIDE_LIGHT_TICK.getOrDefault(uuid, 0L)) {
                double dx = active.doorPos().getX() + 0.5 - player.getX();
                double dz = active.doorPos().getZ() + 0.5 - player.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 1.0) {
                    dx /= len;
                    dz /= len;
                    for (int i = 1; i <= 3; i++) {
                        world.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
                                player.getX() + dx * (i * 1.8), player.getY() + 1.2,
                                player.getZ() + dz * (i * 1.8),
                                1, 0.02, 0.02, 0.02, 0.0);
                    }
                }
                NEXT_GUIDE_LIGHT_TICK.put(uuid, now + GUIDE_LIGHT_INTERVAL);
            }

            // 1.5) ДОБАВЛЕНО (новый выход "Дерево-Маяк" вместо двери): канал пробуждения.
            // Стоишь в свете дерева — свет забирает тебя; вышел из света — канал затухает.
            if (active.doorPos() != null) {
                double distSq = player.squaredDistanceTo(
                        active.doorPos().getX() + 0.5, player.getY(), active.doorPos().getZ() + 0.5);
                if (distSq <= BEACON_TREE_CHANNEL_RADIUS * BEACON_TREE_CHANNEL_RADIUS) {
                    int progress = BEACON_TREE_CHANNEL_TICKS.getOrDefault(uuid, 0) + 1;
                    BEACON_TREE_CHANNEL_TICKS.put(uuid, progress);

                    if (progress == 1) {
                        player.sendMessage(net.minecraft.text.Text.literal(
                                "§bОстанься в свете Дерева-Маяка — он забирает тебя из сна..."), true);
                    } else if (progress % 25 == 0 && progress < BEACON_TREE_CHANNEL_REQUIRED) {
                        player.sendMessage(net.minecraft.text.Text.literal(
                                "§bСвет наполняет тебя... " + (progress * 100 / BEACON_TREE_CHANNEL_REQUIRED) + "%"), true);
                        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                                net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                                net.minecraft.sound.SoundCategory.PLAYERS,
                                1.0f, 0.8f + 0.4f * progress / BEACON_TREE_CHANNEL_REQUIRED);
                    }
                    // Души поднимаются вокруг игрока — видимый индикатор канала
                    world.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL,
                            player.getX(), player.getY() + 0.5, player.getZ(),
                            2, 0.3, 0.5, 0.3, 0.01);

                    if (progress >= BEACON_TREE_CHANNEL_REQUIRED) {
                        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                                net.minecraft.sound.SoundEvents.BLOCK_BEACON_POWER_SELECT,
                                net.minecraft.sound.SoundCategory.PLAYERS, 1.5f, 1.2f);
                        BEACON_TREE_CHANNEL_TICKS.remove(uuid);
                        wake(player, SanityManager.DreamOutcome.SURVIVED_OBJECTIVE);
                        continue;
                    }
                } else {
                    // Вышел из света — канал затухает вдвое быстрее, чем накапливается
                    Integer progress = BEACON_TREE_CHANNEL_TICKS.get(uuid);
                    if (progress != null && progress > 0) {
                        progress = Math.max(0, progress - 2);
                        if (progress == 0) {
                            BEACON_TREE_CHANNEL_TICKS.remove(uuid);
                        } else {
                            BEACON_TREE_CHANNEL_TICKS.put(uuid, progress);
                        }
                    }
                }
            }

            // 2) "Не оборачивайся на шёпот"
            Long whisperTick = LAST_WHISPER_TICK.get(uuid);
            Float whisperYaw = WHISPER_YAW.get(uuid);
            if (whisperTick == null || whisperYaw == null) continue;
            if (now - whisperTick > WHISPER_TURN_WINDOW) {
                // Окно соблазна истекло — игрок выдержал и не обернулся
                LAST_WHISPER_TICK.remove(uuid);
                continue;
            }
            float turned = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(
                    player.getYaw() - whisperYaw));
            if (turned > WHISPER_TURN_ANGLE) {
                LAST_WHISPER_TICK.remove(uuid); // один шёпот = одно наказание

                // Тень материализуется прямо перед взглядом игрока — плата за любопытство
                double yawRad = Math.toRadians(player.getYaw());
                BlockPos shadePos = BlockPos.ofFloored(
                        player.getX() - Math.sin(yawRad) * 2.5, player.getY(),
                        player.getZ() + Math.cos(yawRad) * 2.5);
                spawnOneAt(world, SomniumMod.id("lurking_shade"), shadePos);
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        com.somnium.mod.registry.ModSounds.WHISPER_AMBIENT,
                        net.minecraft.sound.SoundCategory.HOSTILE, 1.2f, 0.3f);
            }
        }
    }

    /** Проверка условия цели конкретного сна — см. DreamObjectiveType для описания каждого типа. */
    private static boolean isObjectiveComplete(ServerPlayerEntity player, ActiveDream active) {
        if (active.objectiveType() == null) return false;
        return switch (active.objectiveType()) {
            case REACH_DOOR -> active.doorPos() != null
                    && WakeDoorTracker.isObjectiveComplete(player.getUuid(), active.doorPos(), player.getBlockPos());
            case BOSS_KILL -> active.bossTargetUuid() != null && isBossDefeated(player, active.bossTargetUuid());
            case COLLECT_ITEMS -> active.collectItemId() != null
                    && countHeldItem(player, active.collectItemId()) >= active.collectItemCount();
        };
    }

    /** Босс считается побеждённым, если его сущность больше не загружена/жива в мире сна. */
    private static boolean isBossDefeated(ServerPlayerEntity player, UUID bossUuid) {
        if (!(player.getEntityWorld() instanceof ServerWorld dreamWorld)) return false;

        // ИСПРАВЛЕНИЕ: не считаем босса побежденным в первые 20 тиков после входа в сон
        // (grace period для загрузки сущности, особенно важно для mirror_room)
        ActiveDream active = ACTIVE.get(player.getUuid());
        if (active != null) {
            long elapsed = dreamWorld.getServer().getTicks() - active.enterTick();
            if (elapsed < 20) {
                return false; // Босс еще загружается, не проверяем
            }
        }

        var entity = dreamWorld.getEntity(bossUuid);
        return entity == null || !entity.isAlive();
    }

    private static int countHeldItem(ServerPlayerEntity player, Identifier itemId) {
        var maybeItem = Registries.ITEM.getOrEmpty(itemId);
        if (maybeItem.isEmpty()) return 0;
        var item = maybeItem.get();
        int total = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    /** Игрок нашёл "Дверь пробуждения" раньше времени, либо выполнил цель сна. */
    public static void wake(ServerPlayerEntity player, SanityManager.DreamOutcome outcome) {
        ActiveDream active = ACTIVE.remove(player.getUuid());
        if (active == null) {
            SomniumMod.LOGGER.warn("[Dream Wake] Попытка разбудить игрока {}, но активный сон не найден", player.getName().getString());
            return;
        }

        MinecraftServer server = ((ServerWorld) player.getEntityWorld()).getServer();
        if (server == null) return;

        long elapsed = server.getTicks() - active.enterTick();
        SomniumMod.LOGGER.info("[Dream Wake] Игрок {} просыпается. Причина: {}, Сон: {}, Прошло тиков: {}/{}",
            player.getName().getString(), outcome, active.dreamId(), elapsed, active.durationTicks());

        // ДОБАВЛЕНО: очищаем чат от тревожных сообщений для сна dream_within_dream
        if (active.dreamId().equals(SomniumMod.id("dream_within_dream"))) {
            // Отправляем 100 пустых строк чтобы "прокрутить" чат и скрыть красные надписи
            for (int i = 0; i < 100; i++) {
                player.sendMessage(net.minecraft.text.Text.literal(""), false);
            }
        }

        // ИСПРАВЛЕНИЕ (утечка per-dream стейта в общем сне): все карты состояния снов
        // ключуются sessionKey (groupId) — фиксируем ключ ДО выхода из сессии, иначе после
        // leaveSession() он уже не восстановить (для одиночного игрока sessionKey == playerId).
        UUID dreamStateKey = sessionKey(player.getUuid());

        // ДОБАВЛЕНО: удаляем игрока из активного сеанса сна
        SharedDreamSession.leaveSession(player.getUuid(), active.dreamId());

        // ИЗМЕНЕНО: очищаем артефакты сна только если это был последний игрок в сеансе
        // Если есть другие игроки в этом сне, не удаляем двери/предметы/монстров
        ServerWorld dreamWorld = (ServerWorld) player.getEntityWorld();
        boolean hasOtherPlayers = SharedDreamSession.hasOtherPlayers(player.getUuid(), active.dreamId());
        if (!hasOtherPlayers) {
            cleanupDreamEntities(dreamWorld, active);
        }

        ServerWorld returnWorld = server.getWorld(active.returnDimension());
        if (returnWorld == null) return;

        player.teleport(returnWorld,
                active.returnPos().getX() + 0.5, active.returnPos().getY(), active.returnPos().getZ() + 0.5,
                player.getYaw(), player.getPitch());

        player.fallDistance = 0;
        player.setVelocity(0, 0, 0);
        grantLandingImmunity(player);

        // ИСПРАВЛЕНИЕ ("попадаю в сон с вещами из реального мира" / возврат): восстанавливаем
        // ЦЕЛИКОМ тот инвентарь, с которым игрок засыпал — то, что он подобрал или потерял во сне,
        // не переносится (см. snapshotAndClearInventory в enterDream).
        restoreInventory(player, active.savedInventory());

        // ДОБАВЛЕНО (сохранение состояния здоровья и сытости): восстанавливаем физическое
        // состояние игрока на момент засыпания — урон и голод из сна не переносятся в реальность.
        player.setHealth(active.savedHealth());
        player.getHungerManager().setFoodLevel(active.savedFoodLevel());
        player.getHungerManager().setSaturationLevel(active.savedSaturation());

        // ИСПРАВЛЕНИЕ (эффекты не пропадают после пробуждения): очищаем все эффекты зелий,
        // полученные во время сна — они не должны переноситься в реальный мир.
        player.clearStatusEffects();

        // ИСПРАВЛЕНИЕ ("после пробуждения не скипается ночь"): обычный ванильный ночной скип
        // завязан на то, что игрок непрерывно остаётся isSleeping()=true в одном и том же мире,
        // пока % спящих игроков не станет достаточным несколько тиков подряд. Мы же сами вызываем
        // player.wakeUp() и телепортируем игрока в измерение сна ещё во время начала этого процесса
        // (см. enterDream) — тем самым обрываем ванильный скип на середине: время в реальном мире
        // не доматывается до утра и продолжает идти как обычно, пока игрок отсутствует. В итоге
        // игрок мог "проспать" весь сон, а по возвращении всё ещё застать ночь. Раз игрок формально
        // "лёг спать" — эмулируем ванильный результат сна вручную: если сейчас в мире возврата ночь,
        // домотать время до утра и погасить дождь/грозу, как это делает обычная кровать.
        forceMorningIfNight(returnWorld);

        SanityManager.onWake(player, outcome, active.dreamId().toString());
        sendDreamTitle(player, net.minecraft.text.Text.translatable("somnium.dream.wake")
                .copy().formatted(net.minecraft.util.Formatting.GRAY), null);
        DreamHudPayload.sendInactive(player);

        // Сбрасываем расписание эмбиента/шёпота — следующий сон начинает отсчёт заново.
        NEXT_AMBIENCE_TICK.remove(player.getUuid());
        NEXT_WHISPER_TICK.remove(player.getUuid());

        // ДОБАВЛЕНО (мультиплеер, защита кровати): снимаем защиту с кровати при пробуждении
        PROTECTED_BEDS.remove(player.getUuid());

        // ДОБАВЛЕНО (мультиплеер, видимость спящего партнёра): убираем "тело" из кровати
        removeSleepingBody(server, player.getUuid());

        // ДОБАВЛЕНО (сон "Лес теней"): очищаем трекинг "не обернувшись", ведьминых огней
        // и канала Дерева-Маяка
        LAST_WHISPER_TICK.remove(player.getUuid());
        WHISPER_YAW.remove(player.getUuid());
        NEXT_GUIDE_LIGHT_TICK.remove(player.getUuid());
        BEACON_TREE_CHANNEL_TICKS.remove(player.getUuid());

        // ДОБАВЛЕНО: очищаем трекер двери пробуждения
        WakeDoorTracker.onDreamEnd(player.getUuid());

        // ДОБАВЛЕНО (сон "Падающие доски"): очищаем данные о платформе при пробуждении
        // ИСПРАВЛЕНО: карты платформ ключуются sessionKey — см. dreamStateKey выше
        clearFallingPlanksData(dreamStateKey);
        // "Лестница в никуда": per-player стейт (последняя безопасная точка, стояние на гнилом)
        STAIR_LAST_SAFE.remove(player.getUuid());
        STAIR_ROTTEN_STAND.remove(player.getUuid());

        // ИСПРАВЛЕНИЕ (утечка per-dream стейта в общем сне): карты состояния снов (стол пира,
        // лабиринт и т.п.) ключуются sessionKey (groupId), а leaveSession() выше уже отвязал
        // игрока от группы — поэтому ключ вычисляем заново через sessionKey() НЕЛЬЗЯ, он
        // вернёт playerId. Для корректной очистки в общем сне используем dreamStateKey,
        // зафиксированный ДО leaveSession (для одиночного игрока совпадает с playerId).
        // ДОБАВЛЕНО (сон "Зеркальная комната"): очищаем данные о стекле при пробуждении
        // (PENDING_MIRROR_INIT намеренно остаётся по playerId — кладётся под UUID игрока)
        MIRROR_ROOM_GLASS.remove(dreamStateKey);
        MIRROR_GLASS_BREAK_TIME.remove(dreamStateKey);
        MIRROR_ROOM_CORNER.remove(dreamStateKey);
        PENDING_MIRROR_INIT.remove(player.getUuid());

        // ДОБАВЛЕНО (сон "Тонущий город"): очищаем данные об уровне воды при пробуждении
        DROWNING_CITY_WATER_LEVEL.remove(dreamStateKey);
        DROWNING_CITY_FILL_QUEUE.remove(dreamStateKey);

        // ДОБАВЛЕНО (сон "Обрушающаяся шахта"): очищаем данные о лабиринте при пробуждении
        COLLAPSING_MINE_MAZES.remove(dreamStateKey);
        COLLAPSING_MINE_ORIGINS.remove(dreamStateKey);
        MINE_OPENED_PASSAGES.remove(dreamStateKey);
        NEXT_MINE_SHIFT_TICK.remove(dreamStateKey);

        // ДОБАВЛЕНО (сон "Кровавый пир", редизайн): стол, блюдо, курсы, кубок при пробуждении
        FEAST_TABLES.remove(dreamStateKey);
        FEAST_DISH_ITEM.remove(dreamStateKey);
        FEAST_DISH_TAINTED.remove(dreamStateKey);
        FEAST_DISH_DEADLINE.remove(dreamStateKey);
        NEXT_DISH_TICK.remove(dreamStateKey);
        FEAST_COURSES_DONE.remove(dreamStateKey);
        FEAST_TOAST_ITEM.remove(dreamStateKey);
        FEAST_GUESTS.remove(dreamStateKey);
        FEAST_NEXT_GUEST_SOUND.remove(dreamStateKey);
        FEAST_JUDGE_FOCUS_POS.remove(dreamStateKey);
        FEAST_JUDGE_FOCUS_UNTIL.remove(dreamStateKey);
        // Догорающие блюда убираем из мира, чтобы не висели вечными предметами
        if (player.getEntityWorld() instanceof ServerWorld feastWorld) {
            for (UUID burningUuid : FEAST_BURNING_DISHES.keySet()) {
                var burning = feastWorld.getEntity(burningUuid);
                if (burning != null) burning.discard();
            }
        }
        FEAST_BURNING_DISHES.clear();
        // Зажигалка и кубок — сонные предметы, в реальный мир их забирать нельзя
        removeFeastItems(player);
        FEAST_LIGHTER_GIVEN.remove(player.getUuid());

        // ДОБАВЛЕНО (сон "Сон-в-сне"): очищаем данные о погоде при пробуждении
        DREAM_WITHIN_DREAM_ORIGINAL_POS.remove(player.getUuid());
        DREAM_WITHIN_DREAM_CHUNKS.remove(player.getUuid());
        DREAM_WITHIN_DREAM_SIGN_START.remove(player.getUuid());
        DREAM_WITHIN_DREAM_ORIGINAL_WEATHER.remove(player.getUuid());
    }

    /**
     * Домотать время мира возврата до утра и убрать непогоду, если сейчас там ночь —
     * см. комментарий про обрыв ванильного ночного скипа в wake() выше.
     * Диапазон "ночь" взят по аналогии с ванильным условием, при котором кровать вообще
     * позволяет лечь (примерно с 12000 по 23999 тиков суток) — сверьте с decompile 1.21.11,
     * если гейм-дизайн потребует более точного порога.
     */
    private static void forceMorningIfNight(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay();
        long dayTime = timeOfDay % 24000L;
        boolean isNight = dayTime >= 12000L;
        if (!isNight) return;

        long morningTime = timeOfDay - dayTime + 24000L;
        world.setTimeOfDay(morningTime);
        world.setWeather(0, 0, false, false);
    }

    /**
     * Снимает копию текущего инвентаря игрока (основной + броня + офф-хенд, всё, что покрывает
     * PlayerInventory#size()) и сразу очищает реальный инвентарь — во сне у игрока должно быть
     * пусто. Копии ItemStack не сериализуются, а хранятся как есть в памяти (см. ActiveDream).
     */
    private static List<ItemStack> snapshotAndClearInventory(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> snapshot = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            snapshot.add(stack.copy());
            // ДОБАВЛЕНО: Колокол Пробуждения (WakingBellItem) — единственное исключение из общей
            // очистки инвентаря. Это предмет-"аварийный выход", специально предназначенный для
            // использования ВНУТРИ сна, поэтому логично, что игрок берёт его с собой физически
            // (как будто сжимает в руке, засыпая), а не находит уже во сне.
            if (!(stack.getItem() instanceof com.somnium.mod.item.WakingBellItem)) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        player.currentScreenHandler.sendContentUpdates();
        return snapshot;
    }

    /**
     * Заменяет инвентарь на сохранённый снимок из яви. Слоты с Колоколом Пробуждения не трогаем —
     * они и так не выгружались при входе в сон (см. snapshotAndClearInventory) и могли быть
     * частично израсходованы прямо во сне (см. WakingBellItem#use) — восстановление из снимка
     * затёрло бы этот расход обратно к исходному количеству.
     */
    private static void restoreInventory(ServerPlayerEntity player, List<ItemStack> savedInventory) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < savedInventory.size() && slot < inventory.size(); slot++) {
            ItemStack current = inventory.getStack(slot);
            if (current.getItem() instanceof com.somnium.mod.item.WakingBellItem) continue;
            inventory.setStack(slot, savedInventory.get(slot));
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    private static void grantLandingImmunity(ServerPlayerEntity player) {
        MinecraftServer server = ((ServerWorld) player.getEntityWorld()).getServer();
        if (server == null) return;
        LANDING_IMMUNITY_UNTIL.put(player.getUuid(), server.getTicks() + LANDING_IMMUNITY_TICKS);
    }

    /** Проверяется в ServerPlayerEntityMixin перед обработкой урона/смерти игрока. */
    public static boolean isLandingImmune(UUID playerId, long currentTick) {
        Long until = LANDING_IMMUNITY_UNTIL.get(playerId);
        return until != null && currentTick < until;
    }

    /** Чистит устаревшие записи об иммунитете — вызывается тик-обработчиком. */
    public static void tickLandingImmunity(MinecraftServer server) {
        if (LANDING_IMMUNITY_UNTIL.isEmpty()) return;
        LANDING_IMMUNITY_UNTIL.values().removeIf(until -> server.getTicks() >= until);
    }

    /**
     * Показывает крупный текст по центру экрана (как ванильные тайтлы достижений/смены дня),
     * а не сообщение в чат — заголовок красным (тема кошмара), подзаголовок серым.
     * subtitle может быть null, если нужен только заголовок.
     */
    private static void sendDreamTitle(ServerPlayerEntity player, net.minecraft.text.Text title, net.minecraft.text.Text subtitle) {
        net.minecraft.text.Text redTitle = title.copy().formatted(net.minecraft.util.Formatting.RED, net.minecraft.util.Formatting.BOLD);

        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(10, 60, 20));
        if (subtitle != null) {
            player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(subtitle));
        }
        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(redTitle));
    }

    /**
     * Вызывается при смерти игрока внутри сна (см. ServerPlayerEntityMixin — реальная смерть
     * там ОТМЕНЯЕТСЯ через ci.cancel(), поэтому здесь нужно самим "откатить" последствия
     * смертельного удара: здоровье, огонь, эффекты — иначе игрок вернётся в явь с 0 HP.
     * По сути "смерть во сне" не убивает по-настоящему, а резко будит игрока.
     */
    public static void onDeathInDream(ServerPlayerEntity player) {
        if (ACTIVE.containsKey(player.getUuid())) {
            SomniumMod.LOGGER.warn("[Dream Death] Игрок {} умер во сне! Здоровье: {}, Y: {}",
                player.getName().getString(), player.getHealth(), player.getY());

            player.setHealth(player.getMaxHealth());
            player.clearStatusEffects();
            player.extinguish();
            player.setFireTicks(0);
            wake(player, SanityManager.DreamOutcome.DIED_IN_DREAM);
        }
    }

    /**
     * Обновляет постоянный HUD цели сна (полосу оставшегося времени) для игрока, если у него
     * сейчас активный сон — вызывается раз в секунду из SomniumMod, рядом с тиком рассудка.
     * Не путать с sendDreamTitle/DreamHudPayload.sendActive в enterDream — тот отправляется
     * один раз при входе, этот — регулярно, чтобы полоса времени реально "утекала" на экране.
     */
    public static void sendHudUpdateIfDreaming(ServerPlayerEntity player, MinecraftServer server) {
        ActiveDream active = ACTIVE.get(player.getUuid());
        if (active == null) return;

        DreamType dream = DreamRegistry.get(active.dreamId());

        // ИСКЛЮЧЕНИЕ: "Сон-в-сне" не показывает HUD — полностью скрываем интерфейс
        if (dream != null && dream.id().equals(SomniumMod.id("dream_within_dream"))) {
            return;
        }

        String nameKey = dream != null ? dream.displayNameKey() : active.dreamId().toString();

        long elapsed = server.getTicks() - active.enterTick();
        long remaining = Math.max(0L, active.durationTicks() - elapsed);

        // ДОБАВЛЕНО: для COLLECT_ITEMS дописываем в HUD живой прогресс (X/Y) — без этого игрок
        // не понимает, сколько ещё собирать до выполнения цели.
        String objectiveText = active.objective();
        if (active.objectiveType() == DreamObjectiveType.COLLECT_ITEMS && active.collectItemId() != null) {
            int have = Math.min(active.collectItemCount(), countHeldItem(player, active.collectItemId()));
            objectiveText = objectiveText + " (" + have + "/" + active.collectItemCount() + ")";
        }

        DreamHudPayload.sendActive(player, nameKey, objectiveText,
                (int) remaining, (int) active.durationTicks());
    }

    private static Identifier safeParse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return Identifier.tryParse(raw);
    }

    /**
     * ДОБАВЛЕНО (негативные эффекты во время сна): накладывает дебаффы на игрока при входе в сон.
     * ИЗМЕНЕНО: каждый сон имеет свой уникальный набор эффектов, подходящий под его тематику.
     *
     * Эффекты по снам:
     * - drowning_city: Water Breathing НЕТ + Slowness (тяжело двигаться в воде)
     * - shadow_forest: Blindness + Slowness (темнота леса, шёпот дезориентирует)
     * - mirror_wastes: Nausea + Weakness (зеркала искажают восприятие)
     * - collapsing_mine: Blindness III + Mining Fatigue (темнота шахты, обрушение)
     * - crimson_feast: Hunger + Weakness (голод и слабость)
     * - void_of_eyes: Levitation (кратковременно) + Slowness Falling (парение в пустоте)
     * - dream_within_dream: все эффекты сразу (самый тяжёлый кошмар)
     */
    private static void applyDreamDebuffs(ServerPlayerEntity player, Identifier dreamId, long dreamDurationTicks) {
        int durationInTicks = (int) Math.min(Integer.MAX_VALUE, dreamDurationTicks + 100);

        // Базовый эффект для всех снов - невозможность ломать блоки (сны не должны позволять изменять мир)
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE,
                durationInTicks,
                4, // уровень V
                false, false, true
        ));

        // Уникальные эффекты в зависимости от сна
        if (dreamId.equals(SomniumMod.id("drowning_city"))) {
            // Тонущий город: тяжесть движения в воде, НЕТ водного дыхания (нужно искать воздух)
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.SLOWNESS,
                    durationInTicks, 2, false, false, true
            ));

        } else if (dreamId.equals(SomniumMod.id("shadow_forest"))) {
            // Лес теней: густая темнота + замедление от страха
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.BLINDNESS,
                    durationInTicks, 0, false, false, true
            ));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.SLOWNESS,
                    durationInTicks, 1, false, false, true
            ));

        } else if (dreamId.equals(SomniumMod.id("mirror_wastes"))) {
            // Пустошь зеркал: искажение восприятия + физическая слабость
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.NAUSEA,
                    durationInTicks, 0, false, false, true
            ));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.WEAKNESS,
                    durationInTicks, 1, false, false, true
            ));

        } else if (dreamId.equals(SomniumMod.id("collapsing_mine"))) {
            // Обрушающаяся шахта: почти полная темнота
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.BLINDNESS,
                    durationInTicks, 2, // усиленная слепота
                    false, false, true
            ));

        } else if (dreamId.equals(SomniumMod.id("crimson_feast"))) {
            // Кровавый пир: голод и слабость (тема извращённого пира)
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.HUNGER,
                    durationInTicks, 2, false, false, true
            ));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.WEAKNESS,
                    durationInTicks, 0, false, false, true
            ));

        } else if (dreamId.equals(SomniumMod.id("void_of_eyes"))) {
            // Пустота с глазами: ощущение парения, медленное падение
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.SLOW_FALLING,
                    durationInTicks, 0, false, false, true
            ));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.SLOWNESS,
                    durationInTicks, 1, false, false, true
            ));

        } else if (dreamId.equals(SomniumMod.id("dream_within_dream"))) {
            // Сон-в-сне: комбинация всех кошмаров (самый тяжёлый сон)
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.SLOWNESS,
                    durationInTicks, 2, false, false, true
            ));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.NAUSEA,
                    durationInTicks, 0, false, false, true
            ));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.WEAKNESS,
                    durationInTicks, 1, false, false, true
            ));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.BLINDNESS,
                    durationInTicks, 0, false, false, true
            ));

        } else if (dreamId.equals(SomniumMod.id("falling_planks"))) {
            // Падающие доски: только слабость (страх), без левитации
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.WEAKNESS,
                    durationInTicks, 0, false, false, true
            ));
        }
    }

    /**
     * ИСПРАВЛЕНИЕ (баг дублирования дверей): удаляет все артефакты предыдущего визита в сон —
     * маркеры "Двери пробуждения" (ArmorStand), собираемые предметы (ItemEntity с neverDespawn),
     * и оставшихся монстров сна. Вызывается из wake() перед телепортацией игрока обратно.
     *
     * Альтернативный подход (удалять всё при ВХОДЕ в сон) не подходит, т.к. теоретически в одном
     * измерении сна могут находиться несколько игроков одновременно (мультиплеер) — удаление при
     * входе второго игрока сломало бы сон первого. Удаление при ВЫХОДЕ безопаснее: последний
     * покинувший сон игрок "гасит свет".
     */
    private static void cleanupDreamEntities(ServerWorld dreamWorld, ActiveDream active) {
        // Удаляем дверь пробуждения и её структуру (если была)
        if (active.doorPos() != null) {
            BlockPos doorPos = active.doorPos();

            // Удаляем маркер ArmorStand над дверью
            var nearbyEntities = dreamWorld.getEntitiesByClass(
                    ArmorStandEntity.class,
                    new net.minecraft.util.math.Box(doorPos).expand(5.0),
                    entity -> entity.isInvulnerable() && entity.isInvisible()
            );
            for (var marker : nearbyEntities) {
                marker.discard();
            }

            // Удаляем саму дверь (нижняя и верхняя половины)
            dreamWorld.setBlockState(doorPos, net.minecraft.block.Blocks.AIR.getDefaultState());
            dreamWorld.setBlockState(doorPos.up(), net.minecraft.block.Blocks.AIR.getDefaultState());

            // Удаляем фундамент из кварца
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    dreamWorld.setBlockState(doorPos.add(x, -1, z), net.minecraft.block.Blocks.AIR.getDefaultState());
                }
            }

            // Удаляем рамку из светящихся блоков
            dreamWorld.setBlockState(doorPos.add(-1, 0, 0), net.minecraft.block.Blocks.AIR.getDefaultState());
            dreamWorld.setBlockState(doorPos.add(1, 0, 0), net.minecraft.block.Blocks.AIR.getDefaultState());
            dreamWorld.setBlockState(doorPos.add(-1, 1, 0), net.minecraft.block.Blocks.AIR.getDefaultState());
            dreamWorld.setBlockState(doorPos.add(1, 1, 0), net.minecraft.block.Blocks.AIR.getDefaultState());
            dreamWorld.setBlockState(doorPos.add(-1, 2, 0), net.minecraft.block.Blocks.AIR.getDefaultState());
            dreamWorld.setBlockState(doorPos.add(0, 2, 0), net.minecraft.block.Blocks.AIR.getDefaultState());
            dreamWorld.setBlockState(doorPos.add(1, 2, 0), net.minecraft.block.Blocks.AIR.getDefaultState());
        }

        // Удаляем все собираемые предметы (ItemEntity с neverDespawn)
        // ВАЖНО: удаляем ТОЛЬКО предметы с флагом neverDespawn (те, что мы сами раскидали при
        // входе в сон) — если игрок что-то выбросил сам, это обычный ItemEntity без флага.
        // Проверяем через itemAge: предметы с setNeverDespawn() имеют itemAge = -32768
        var allItems = dreamWorld.getEntitiesByClass(
                ItemEntity.class,
                new net.minecraft.util.math.Box(BlockPos.ORIGIN).expand(100.0),
                item -> item.getItemAge() == -32768 // предметы с neverDespawn имеют специальное значение возраста
        );
        for (var item : allItems) {
            item.discard();
        }

        // Удаляем всех оставшихся монстров сна (AbstractNightmareEntity)
        // Это опционально (монстры сами по себе не мешают следующему визиту, т.к. при входе
        // спавнятся новые), но предотвращает накопление "зависших" мобов при частых входах/выходах.
        var nightmares = dreamWorld.getEntitiesByClass(
                com.somnium.mod.entity.nightmare.AbstractNightmareEntity.class,
                new net.minecraft.util.math.Box(BlockPos.ORIGIN).expand(100.0),
                entity -> true
        );
        for (var nightmare : nightmares) {
            nightmare.discard();
        }
    }

    /**
     * ДОБАВЛЕНО (мультиплеер, видимость спящего партнёра): создаёт лежащую в кровати
     * "модель" игрока (невидимый ArmorStand с именем игрока над головой) в реальном мире —
     * чтобы проснувшиеся или другие игроки видели, что партнёр всё ещё спит, а не исчез.
     * Удаляется в removeSleepingBody() при пробуждении настоящего игрока (см. wake()).
     */
    private static void spawnSleepingBody(ServerPlayerEntity player, ServerWorld world, BlockPos bedPos) {
        ArmorStandEntity body = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        body.refreshPositionAndAngles(bedPos.getX() + 0.5, bedPos.getY() + 0.2, bedPos.getZ() + 0.5,
                player.getYaw(), 0);
        body.setInvisible(true);
        body.setInvulnerable(true);
        body.setNoGravity(true);
        body.setCustomName(net.minecraft.text.Text.translatable("somnium.dream.sleeping_body", player.getName()));
        body.setCustomNameVisible(true);
        // Поза "лёжа": наклон правой руки/тела условно имитирует лежание — ArmorStand не умеет
        // ложиться физически, поэтому кладём его на бок разворотом основной позы.
        body.setBodyYaw(player.getYaw());
        body.setPose(net.minecraft.entity.EntityPose.SLEEPING);

        world.spawnEntity(body);
        SLEEPING_BODIES.put(player.getUuid(), body.getUuid());
    }

    /**
     * ДОБАВЛЕНО (мультиплеер, видимость спящего партнёра): убирает "тело" при пробуждении
     * настоящего игрока (естественном или по колоколу) — см. вызов в wake().
     */
    private static void removeSleepingBody(MinecraftServer server, UUID playerId) {
        UUID bodyId = SLEEPING_BODIES.remove(playerId);
        if (bodyId == null) return;

        for (ServerWorld world : server.getWorlds()) {
            var entity = world.getEntity(bodyId);
            if (entity != null) {
                entity.discard();
                return;
            }
        }
    }

    /**
     * ДОБАВЛЕНО (мультиплеер, защита кровати): запоминает обе половины кровати игрока.
     */
    private static void protectPlayerBed(ServerPlayerEntity player, BlockPos bedPos) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return;

        net.minecraft.block.BlockState state = world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof net.minecraft.block.BedBlock)) return;

        List<BlockPos> bedBlocks = new ArrayList<>();
        bedBlocks.add(bedPos);

        // Находим вторую половину кровати
        net.minecraft.block.enums.BedPart part = state.get(net.minecraft.block.BedBlock.PART);
        net.minecraft.util.math.Direction facing = state.get(net.minecraft.block.BedBlock.FACING);
        BlockPos otherHalf = part == net.minecraft.block.enums.BedPart.HEAD
                ? bedPos.offset(facing.getOpposite())
                : bedPos.offset(facing);
        bedBlocks.add(otherHalf);

        PROTECTED_BEDS.put(player.getUuid(), bedBlocks);
    }

    /**
     * ДОБАВЛЕНО (мультиплеер, защита кровати): проверяет, защищена ли кровать.
     */
    public static boolean isBedProtected(BlockPos pos) {
        for (List<BlockPos> bedBlocks : PROTECTED_BEDS.values()) {
            if (bedBlocks.contains(pos)) return true;
        }
        return false;
    }

    /**
     * ДОБАВЛЕНО (мультиплеер, колокол): проверяет, находится ли игрок во сне.
     */
    public static boolean isDreaming(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    // ==================== ДОБАВЛЕНО (сон "Лестница в никуда", редизайн "Падающих досок") ====================
    // Игрок оказывается на дне тёмной лестничной клетки, как в многоэтажке. Спиральный
    // марш по стенам достраивается вверх по мере подъёма, а ступени за спиной обрушаются.
    // Выше начинаются гнилые ступени (бирюзовые, дымятся) — ломаются под ногами. На третьем
    // витке лестница резко обрывается: площадка, глухой потолок и люк — выход из сна.

    /** Центр шахты (позиция спавна игрока) для каждой сессии */
    private static final Map<UUID, BlockPos> STAIR_ORIGIN = new HashMap<>();
    /** Индекс следующей непостроенной ступени */
    private static final Map<UUID, Integer> STAIR_BUILT_STEPS = new HashMap<>();
    /** Индекс ступени -> её позиция (нужно для обрушения за спиной) */
    private static final Map<UUID, List<BlockPos>> STAIR_STEP_POSITIONS = new HashMap<>();
    /** Индекс следующей ступени, которую предстоит обрушить */
    private static final Map<UUID, Integer> STAIR_COLLAPSE_NEXT = new HashMap<>();
    /** Очередь позиций на обрушение — сыплем по одной ступени за пару тиков */
    private static final Map<UUID, List<BlockPos>> STAIR_COLLAPSE_QUEUE = new HashMap<>();
    /** Гнилые ступени (не держат вес) */
    private static final Map<UUID, java.util.Set<BlockPos>> STAIR_ROTTEN = new HashMap<>();
    /** Позиция люка в потолке финальной площадки — выход из сна */
    private static final Map<UUID, BlockPos> STAIR_HATCH = new HashMap<>();
    /** Текущая высота временного потолка шахты (поднимается по мере постройки) */
    private static final Map<UUID, Integer> STAIR_CEILING_Y = new HashMap<>();
    /** Верх построенной части стен шахты */
    private static final Map<UUID, Integer> STAIR_WALL_TOP_Y = new HashMap<>();
    /** Тик пробуждения после открытия люка */
    private static final Map<UUID, Long> STAIR_WAKE_TICK = new HashMap<>();
    /** Сообщение "лестница обрывается" показано */
    private static final Map<UUID, Boolean> STAIR_FINAL_ANNOUNCED = new HashMap<>();
    /** Последняя безопасная точка игрока на лестнице (per-player) */
    private static final Map<UUID, net.minecraft.util.math.Vec3d> STAIR_LAST_SAFE = new HashMap<>();
    /** Сколько тиков игрок стоит на гнилой ступени (per-player) */
    private static final Map<UUID, Integer> STAIR_ROTTEN_STAND = new HashMap<>();

    /** Кольцо периметра шахты 7×7 (x,z ∈ -3..3) по часовой стрелке от северо-западного угла */
    private static final int[][] STAIR_RING = {
            {-3, -3}, {-2, -3}, {-1, -3}, {0, -3}, {1, -3}, {2, -3}, {3, -3},
            {3, -2}, {3, -1}, {3, 0}, {3, 1}, {3, 2}, {3, 3},
            {2, 3}, {1, 3}, {0, 3}, {-1, 3}, {-2, 3}, {-3, 3},
            {-3, 2}, {-3, 1}, {-3, 0}, {-3, -1}, {-3, -2}
    };
    /** Финал — 3 витка (72 ступени, +36 высоты): лестница резко обрывается */
    private static final int STAIR_FINAL_STEP = 72;
    /** Сколько ступеней строим вперёд от игрока */
    private static final int STAIR_BUILD_AHEAD = 30;
    /** Ступени ниже этого отставания от игрока обрушаются */
    private static final int STAIR_COLLAPSE_BEHIND = 12;
    /** Гниль начинается с 30-й ступени (вторая половина подъёма) */
    private static final int STAIR_ROTTEN_START_STEP = 30;
    private static final double STAIR_ROTTEN_CHANCE = 0.15;
    /** Через сколько тиков стояния гнилая ступень ломается */
    private static final int STAIR_ROTTEN_BREAK_TICKS = 15;
    /** Падение ниже последней безопасной точки на столько блоков = ловим и возвращаем */
    private static final double STAIR_FALL_CATCH_DEPTH = 5.0;
    private static final float STAIR_FALL_DAMAGE = 4.0f;
    private static final float STAIR_FALL_SANITY = -4.0f;
    private static final float STAIR_ROTTEN_SANITY = -2.0f;

    /**
     * ДОБАВЛЕНО (сон "Лестница в никуда"): строит низ лестничной клетки — тёмную шахту
     * 7×7 с полированным глубинносланцевым полом и стенами, уходящими вниз в пустоту.
     * Ступени достраиваются по мере подъёма (см. tickFallingPlanks).
     */
    private static void setupStairwell(ServerWorld world, BlockPos center, UUID sessionKey) {
        // Пол шахты
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlockState(center.add(x, -1, z),
                        net.minecraft.block.Blocks.POLISHED_DEEPSLATE.getDefaultState());
            }
        }
        // Стены: от восьми блоков ниже пола (шахта "растёт из темноты") до +5
        buildShaftWalls(world, center, center.getY() - 8, center.getY() + 5);
        // Временный потолок — поднимается по мере постройки лестницы
        buildShaftCeiling(world, center, center.getY() + 5);
        // Факел душ у входа
        world.setBlockState(center.add(3, 1, -3), net.minecraft.block.Blocks.SOUL_WALL_TORCH.getDefaultState()
                .with(net.minecraft.block.WallTorchBlock.FACING, net.minecraft.util.math.Direction.WEST));

        STAIR_ORIGIN.put(sessionKey, center);
        STAIR_BUILT_STEPS.put(sessionKey, 0);
        STAIR_STEP_POSITIONS.put(sessionKey, new ArrayList<>());
        STAIR_COLLAPSE_NEXT.put(sessionKey, 0);
        STAIR_COLLAPSE_QUEUE.put(sessionKey, new ArrayList<>());
        STAIR_ROTTEN.put(sessionKey, new java.util.HashSet<>());
        STAIR_HATCH.remove(sessionKey);
        STAIR_CEILING_Y.put(sessionKey, center.getY() + 5);
        STAIR_WALL_TOP_Y.put(sessionKey, center.getY() + 5);
        STAIR_WAKE_TICK.remove(sessionKey);
        STAIR_FINAL_ANNOUNCED.remove(sessionKey);
    }

    /** Стены шахты: кольцо 9×9 (x,z = ±4) из глубинносланца с кирпичными поясами, слои y0..y1. */
    private static void buildShaftWalls(ServerWorld world, BlockPos center, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            boolean band = Math.floorMod(y - center.getY(), 4) == 0;
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                    world.setBlockState(new BlockPos(center.getX() + x, y, center.getZ() + z),
                            band ? net.minecraft.block.Blocks.DEEPSLATE_BRICKS.getDefaultState()
                                 : net.minecraft.block.Blocks.POLISHED_DEEPSLATE.getDefaultState());
                }
            }
        }
    }

    /** Потолок шахты 7×7 на заданной высоте. */
    private static void buildShaftCeiling(ServerWorld world, BlockPos center, int y) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlockState(new BlockPos(center.getX() + x, y, center.getZ() + z),
                        net.minecraft.block.Blocks.POLISHED_DEEPSLATE.getDefaultState());
            }
        }
    }

    /** Снимает временный потолок (при подъёме или перед постройкой финального). */
    private static void clearShaftCeiling(ServerWorld world, BlockPos center, int y) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlockState(new BlockPos(center.getX() + x, y, center.getZ() + z),
                        net.minecraft.block.Blocks.AIR.getDefaultState());
            }
        }
    }

    /** Строит ступень idx спирального марша: кольцо периметра, +0.5 высоты за ступень. */
    private static void buildStairStep(ServerWorld world, BlockPos center, UUID sessionKey, int idx) {
        int[] cell = STAIR_RING[idx % STAIR_RING.length];
        int y = center.getY() + idx / 2;
        BlockPos pos = new BlockPos(center.getX() + cell[0], y, center.getZ() + cell[1]);

        // Ступень смотрит "лицом" к предыдущей (вниз по маршу)
        int[] prev = STAIR_RING[Math.floorMod(idx - 1, STAIR_RING.length)];
        int dx = prev[0] - cell[0];
        int dz = prev[1] - cell[1];
        net.minecraft.util.math.Direction facing =
                dx == 1 ? net.minecraft.util.math.Direction.EAST :
                dx == -1 ? net.minecraft.util.math.Direction.WEST :
                dz == 1 ? net.minecraft.util.math.Direction.SOUTH :
                net.minecraft.util.math.Direction.NORTH;

        boolean rotten = idx >= STAIR_ROTTEN_START_STEP && RANDOM.nextDouble() < STAIR_ROTTEN_CHANCE;
        var block = rotten ? net.minecraft.block.Blocks.WARPED_STAIRS
                           : net.minecraft.block.Blocks.DARK_OAK_STAIRS;
        world.setBlockState(pos, block.getDefaultState()
                .with(net.minecraft.block.StairsBlock.FACING, facing)
                .with(net.minecraft.block.StairsBlock.HALF,
                        idx % 2 == 0 ? net.minecraft.block.enums.BlockHalf.BOTTOM
                                     : net.minecraft.block.enums.BlockHalf.TOP));
        STAIR_STEP_POSITIONS.get(sessionKey).add(pos);
        if (rotten) {
            STAIR_ROTTEN.get(sessionKey).add(pos);
        }

        // Факел душ на стене над каждой шестой ступенью — мрачная подсветка марша
        if (idx % 6 == 0) {
            net.minecraft.util.math.Direction intoShaft =
                    cell[0] == -3 ? net.minecraft.util.math.Direction.EAST :
                    cell[0] == 3 ? net.minecraft.util.math.Direction.WEST :
                    cell[1] == -3 ? net.minecraft.util.math.Direction.SOUTH :
                    net.minecraft.util.math.Direction.NORTH;
            BlockPos torchPos = new BlockPos(pos.getX(), y + 2, pos.getZ());
            if (world.getBlockState(torchPos).isAir()) {
                world.setBlockState(torchPos, net.minecraft.block.Blocks.SOUL_WALL_TORCH.getDefaultState()
                        .with(net.minecraft.block.WallTorchBlock.FACING, intoShaft));
            }
        }
    }

    /** Финал: лестница обрывается — площадка в углу, глухой потолок и люк со светом за ним. */
    private static void buildStairFinal(ServerWorld world, BlockPos center, UUID sessionKey) {
        int platformY = center.getY() + STAIR_FINAL_STEP / 2;
        // Площадка в северо-западном углу (последняя ступень витка сама ведёт на неё)
        for (int[] cell : new int[][] {{-3, -3}, {-2, -3}, {-2, -2}}) {
            world.setBlockState(new BlockPos(center.getX() + cell[0], platformY, center.getZ() + cell[1]),
                    net.minecraft.block.Blocks.DARK_OAK_PLANKS.getDefaultState());
        }
        // Стены до потолка и глухой финальный потолок вместо временного
        int ceilingY = platformY + 5;
        clearShaftCeiling(world, center, STAIR_CEILING_Y.get(sessionKey));
        buildShaftWalls(world, center, STAIR_WALL_TOP_Y.get(sessionKey) + 1, ceilingY);
        buildShaftCeiling(world, center, ceilingY);
        STAIR_WALL_TOP_Y.put(sessionKey, ceilingY);
        STAIR_CEILING_Y.put(sessionKey, ceilingY);
        // Люк над площадкой — за ним свет пробуждения
        BlockPos hatch = new BlockPos(center.getX() - 3, ceilingY, center.getZ() - 3);
        world.setBlockState(hatch, net.minecraft.block.Blocks.SPRUCE_TRAPDOOR.getDefaultState()
                .with(net.minecraft.block.TrapdoorBlock.HALF, net.minecraft.block.enums.BlockHalf.BOTTOM)
                .with(net.minecraft.block.TrapdoorBlock.OPEN, false));
        world.setBlockState(hatch.up(), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
        STAIR_HATCH.put(sessionKey, hatch);
        // Факел душ на площадке
        world.setBlockState(new BlockPos(center.getX() - 2, platformY + 1, center.getZ() - 3),
                net.minecraft.block.Blocks.SOUL_WALL_TORCH.getDefaultState()
                        .with(net.minecraft.block.WallTorchBlock.FACING, net.minecraft.util.math.Direction.SOUTH));
    }

    /**
     * ИЗМЕНЕНО (сон "Лестница в никуда", бывш. "Падающие доски"): спиральный марш
     * достраивается вверх по мере подъёма, ступени обрушаются за спиной, гнилые ступени
     * ломаются под ногами, падение мягко возвращает на последнюю безопасную точку.
     * Выход — люк в потолке финальной площадки (открывается обычным ПКМ).
     * Поражение — смерть во сне или падение в пустоту вне шахты (checkFallingPlanksVoidFall).
     */
    public static void tickFallingPlanks(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long now = server.getTicks();

        for (UUID playerId : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(playerId);
            if (active == null || !active.dreamId().equals(SomniumMod.id("falling_planks"))) {
                continue; // не наш сон
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || !(player.getEntityWorld() instanceof ServerWorld world)) continue;

            UUID sessionKey = sessionKey(playerId);
            BlockPos center = STAIR_ORIGIN.get(sessionKey);
            if (center == null) continue;

            // 0) Защита сцены: в шахте не должно быть монстров вообще
            if (now % 10 == 0) {
                for (net.minecraft.entity.mob.MobEntity mob : world.getEntitiesByClass(
                        net.minecraft.entity.mob.MobEntity.class,
                        new net.minecraft.util.math.Box(center).expand(20, 50, 20),
                        e -> e instanceof net.minecraft.entity.mob.Monster)) {
                    mob.discard();
                }
            }

            // 1) Текущая "ступень" игрока по высоте и достройка марша вперёд
            int playerStep = (int) Math.max(0, Math.round((player.getY() - center.getY()) * 2));
            int built = STAIR_BUILT_STEPS.getOrDefault(sessionKey, 0);
            int targetBuilt = Math.min(playerStep + STAIR_BUILD_AHEAD, STAIR_FINAL_STEP + 1);
            List<BlockPos> stepPositions = STAIR_STEP_POSITIONS.get(sessionKey);
            while (built < targetBuilt) {
                if (built == STAIR_FINAL_STEP) {
                    buildStairFinal(world, center, sessionKey);
                } else {
                    buildStairStep(world, center, sessionKey, built);
                }
                built++;
            }
            STAIR_BUILT_STEPS.put(sessionKey, built);
            // Поднимаем временный потолок и стены вслед за постройкой (до финала)
            if (STAIR_HATCH.get(sessionKey) == null) {
                int builtTopY = center.getY() + built / 2;
                int neededCeiling = builtTopY + 4;
                int ceilingY = STAIR_CEILING_Y.getOrDefault(sessionKey, center.getY() + 5);
                if (neededCeiling > ceilingY) {
                    clearShaftCeiling(world, center, ceilingY);
                    buildShaftWalls(world, center, STAIR_WALL_TOP_Y.get(sessionKey) + 1, neededCeiling);
                    buildShaftCeiling(world, center, neededCeiling);
                    STAIR_WALL_TOP_Y.put(sessionKey, neededCeiling);
                    STAIR_CEILING_Y.put(sessionKey, neededCeiling);
                }
            }

            // 2) Обрушение за спиной: ступени ниже playerStep - STAIR_COLLAPSE_BEHIND сыплются
            int collapseUntil = Math.min(playerStep - STAIR_COLLAPSE_BEHIND, STAIR_FINAL_STEP);
            int collapseNext = STAIR_COLLAPSE_NEXT.getOrDefault(sessionKey, 0);
            List<BlockPos> collapseQueue = STAIR_COLLAPSE_QUEUE.get(sessionKey);
            while (collapseNext < collapseUntil && collapseNext < stepPositions.size()) {
                collapseQueue.add(stepPositions.get(collapseNext));
                collapseNext++;
            }
            STAIR_COLLAPSE_NEXT.put(sessionKey, collapseNext);
            if (!collapseQueue.isEmpty() && now % 2 == 0) {
                BlockPos falling = collapseQueue.remove(0);
                world.setBlockState(falling, net.minecraft.block.Blocks.AIR.getDefaultState());
                STAIR_ROTTEN.get(sessionKey).remove(falling);
                if (falling.getSquaredDistance(player.getBlockPos()) < 500) {
                    world.playSound(null, falling, net.minecraft.sound.SoundEvents.BLOCK_WOOD_BREAK,
                            net.minecraft.sound.SoundCategory.BLOCKS, 0.9f, 0.7f);
                    world.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(
                                    net.minecraft.particle.ParticleTypes.BLOCK,
                                    net.minecraft.block.Blocks.DARK_OAK_PLANKS.getDefaultState()),
                            falling.getX() + 0.5, falling.getY() + 0.5, falling.getZ() + 0.5,
                            10, 0.25, 0.25, 0.25, 0.05);
                }
            }

            // 3) Гнилые ступени: скрип-предупреждение и пролом под ногами
            java.util.Set<BlockPos> rotten = STAIR_ROTTEN.get(sessionKey);
            BlockPos supportPos = net.minecraft.util.math.BlockPos.ofFloored(
                    player.getX(), player.getY() - 0.5, player.getZ());
            if (rotten.contains(supportPos) && player.isOnGround()) {
                int stand = STAIR_ROTTEN_STAND.getOrDefault(playerId, 0) + 1;
                STAIR_ROTTEN_STAND.put(playerId, stand);
                if (stand == 1 || stand % 5 == 0) {
                    world.playSound(null, supportPos, net.minecraft.sound.SoundEvents.BLOCK_WOOD_HIT,
                            net.minecraft.sound.SoundCategory.BLOCKS, 0.9f, 0.5f);
                }
                if (stand >= STAIR_ROTTEN_BREAK_TICKS) {
                    rotten.remove(supportPos);
                    STAIR_ROTTEN_STAND.remove(playerId);
                    world.setBlockState(supportPos, net.minecraft.block.Blocks.AIR.getDefaultState());
                    world.playSound(null, supportPos, net.minecraft.sound.SoundEvents.BLOCK_WOOD_BREAK,
                            net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 0.6f);
                    world.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(
                                    net.minecraft.particle.ParticleTypes.BLOCK,
                                    net.minecraft.block.Blocks.WARPED_PLANKS.getDefaultState()),
                            supportPos.getX() + 0.5, supportPos.getY() + 0.5, supportPos.getZ() + 0.5,
                            20, 0.3, 0.3, 0.3, 0.08);
                    SanityManager.get(player).addSanity(STAIR_ROTTEN_SANITY);
                    player.sendMessage(net.minecraft.text.Text.literal(
                            "§7Гнилая ступень не выдержала!"), true);
                }
            } else {
                STAIR_ROTTEN_STAND.remove(playerId);
            }
            if (now % 15 == 0) {
                int shown = 0;
                for (BlockPos rotPos : rotten) {
                    if (shown >= 5) break;
                    if (rotPos.getSquaredDistance(player.getBlockPos()) < 200) {
                        world.spawnParticles(net.minecraft.particle.ParticleTypes.WITCH,
                                rotPos.getX() + 0.5, rotPos.getY() + 1.0, rotPos.getZ() + 0.5,
                                1, 0.15, 0.05, 0.15, 0.0);
                        shown++;
                    }
                }
            }

            // 4) Последняя безопасная точка и мягкая ловля падения
            if (player.isOnGround() && Math.abs(supportPos.getX() - center.getX()) <= 4
                    && Math.abs(supportPos.getZ() - center.getZ()) <= 4) {
                var supportBlock = world.getBlockState(supportPos).getBlock();
                if (supportBlock == net.minecraft.block.Blocks.DARK_OAK_STAIRS
                        || supportBlock == net.minecraft.block.Blocks.WARPED_STAIRS
                        || supportBlock == net.minecraft.block.Blocks.DARK_OAK_PLANKS
                        || supportBlock == net.minecraft.block.Blocks.POLISHED_DEEPSLATE) {
                    STAIR_LAST_SAFE.put(playerId, player.getPos());
                }
            }
            net.minecraft.util.math.Vec3d lastSafe = STAIR_LAST_SAFE.get(playerId);
            if (lastSafe != null && player.getY() < lastSafe.y - STAIR_FALL_CATCH_DEPTH) {
                player.fallDistance = 0.0f;
                player.teleport(world, lastSafe.x, lastSafe.y, lastSafe.z,
                        player.getYaw(), player.getPitch());
                player.damage(world.getDamageSources().fall(), STAIR_FALL_DAMAGE);
                SanityManager.get(player).addSanity(STAIR_FALL_SANITY);
                player.sendMessage(net.minecraft.text.Text.literal(
                        "§7Ты срываешься в темноту пролёта... и приходишь в себя на ступенях."), true);
                world.playSound(null, lastSafe.x, lastSafe.y, lastSafe.z,
                        net.minecraft.sound.SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                        net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 0.6f);
            }

            // 5) Подсказки и сообщение об обрыве
            if (playerStep < 6 && STAIR_HATCH.get(sessionKey) == null && now % 120 == 0) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "§7Лестница зовёт наверх. Она обрушается за спиной — не стой на месте."), true);
            }
            if (playerStep >= STAIR_FINAL_STEP && !STAIR_FINAL_ANNOUNCED.getOrDefault(sessionKey, false)) {
                STAIR_FINAL_ANNOUNCED.put(sessionKey, true);
                player.sendMessage(net.minecraft.text.Text.literal(
                        "§7Лестница обрывается. Выше — только темнота... и люк."), true);
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        net.minecraft.sound.SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        net.minecraft.sound.SoundCategory.AMBIENT, 1.0f, 0.8f);
            }

            // 6) Люк: ванильное открытие ПКМ (или если сломали) — свет и пробуждение
            BlockPos hatch = STAIR_HATCH.get(sessionKey);
            if (hatch != null) {
                Long wakeTick = STAIR_WAKE_TICK.get(sessionKey);
                if (wakeTick == null) {
                    var hatchState = world.getBlockState(hatch);
                    boolean opened = hatchState.isAir()
                            || (hatchState.getBlock() == net.minecraft.block.Blocks.SPRUCE_TRAPDOOR
                                && hatchState.get(net.minecraft.block.TrapdoorBlock.OPEN));
                    if (opened) {
                        STAIR_WAKE_TICK.put(sessionKey, now + 25);
                        player.sendMessage(net.minecraft.text.Text.literal(
                                "§6Свет бьёт в глаза... ты просыпаешься."), true);
                        world.playSound(null, hatch, net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                                net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.8f);
                        world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                                hatch.getX() + 0.5, hatch.getY() - 1.5, hatch.getZ() + 0.5,
                                30, 0.3, 1.2, 0.3, 0.02);
                    } else if (hatch.getSquaredDistance(player.getBlockPos()) < 36 && now % 60 == 0) {
                        player.sendMessage(net.minecraft.text.Text.literal(
                                "§7ПКМ по люку в потолке — выход из сна."), true);
                    }
                } else if (now >= wakeTick) {
                    wake(player, SanityManager.DreamOutcome.SURVIVED_OBJECTIVE);
                    continue;
                }
            }
        }
    }

    /** ДОБАВЛЕНО: полная очистка per-session данных сна "Лестница в никуда" */
    private static void clearFallingPlanksData(UUID sessionKey) {
        STAIR_ORIGIN.remove(sessionKey);
        STAIR_BUILT_STEPS.remove(sessionKey);
        STAIR_STEP_POSITIONS.remove(sessionKey);
        STAIR_COLLAPSE_NEXT.remove(sessionKey);
        STAIR_COLLAPSE_QUEUE.remove(sessionKey);
        STAIR_ROTTEN.remove(sessionKey);
        STAIR_HATCH.remove(sessionKey);
        STAIR_CEILING_Y.remove(sessionKey);
        STAIR_WALL_TOP_Y.remove(sessionKey);
        STAIR_WAKE_TICK.remove(sessionKey);
        STAIR_FINAL_ANNOUNCED.remove(sessionKey);
    }

    /**
     * ДОБАВЛЕНО (сон "Падающие доски"): детектор падения в void. Если игрок падает ниже Y=0
     * в сне "Падающие доски", это поражение — пробуждение с исходом DIED_IN_DREAM.
     * Вызывается из тик-обработчика для активных снов.
     */
    public static void checkFallingPlanksVoidFall(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;

        for (UUID playerId : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(playerId);
            if (active == null) continue;
            if (!active.dreamId().equals(SomniumMod.id("falling_planks"))) continue;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) continue;

            // ИСПРАВЛЕНО: падение в пустоту — это поражение, а не "выживание по таймауту"
            // (раньше падение давало +15 рассудка как победа). Теперь -10, как смерть во сне.
            if (player.getY() < 0) {
                wake(player, SanityManager.DreamOutcome.DIED_IN_DREAM);
            }
        }
    }

    private static BlockPos findDreamSpawn(ServerWorld world) {
        // TODO (Приоритет 2): заменить на поиск специальной структуры "точки входа" сна,
        // расставленной в NBT-шаблоне локации. Пока — центр мира на высоте первого твёрдого блока.
        BlockPos center = BlockPos.ORIGIN;

        // ИСПРАВЛЕНО: раньше здесь был world.getTopY(Heightmap.Type.MOTION_BLOCKING, ...),
        // который в момент самого первого визита игрока в это измерение мог отработать до того,
        // как хайтмап чанка (0,0) реально досчитался — из-за чего игрок иногда спавнился высоко
        // в воздухе и разбивался насмерть о плейсхолдер-платформу (см. onDeathInDream/ServerPlayerEntityMixin).
        // Поверхность плейсхолдер-генератора "minecraft:flat" известна заранее (сумма layers из
        // data/somnium/dimension/*.json), поэтому используем детерминированное значение —
        // см. ModDimensions#platformSurfaceY.
        int y = ModDimensions.platformSurfaceY(world.getRegistryKey());

        return new BlockPos(center.getX(), y, center.getZ());
    }

    /** Карта игроков в зеркальной комнате -> позиция стеклянной стены */
    private static final Map<UUID, List<BlockPos>> MIRROR_ROOM_GLASS = new HashMap<>();
    /** Карта игроков -> тик разбивания стекла (через 15 секунд после входа) */
    private static final Map<UUID, Long> MIRROR_GLASS_BREAK_TIME = new HashMap<>();
    /** Карта игроков -> угол комнаты (для расчета позиции монстра) */
    private static final Map<UUID, BlockPos> MIRROR_ROOM_CORNER = new HashMap<>();
    /** ИСПРАВЛЕНИЕ: очередь отложенной инициализации MirrorReflection (UUID босса + оставшиеся тики до инициализации) */
    private static final Map<UUID, MirrorInitData> PENDING_MIRROR_INIT = new HashMap<>();

    /** Данные для отложенной инициализации зеркального отражения */
    private record MirrorInitData(UUID bossUuid, int ticksRemaining) {}

    /** ДОБАВЛЕНО: Тонущий город - текущий уровень воды для каждого игрока */
    private static final Map<UUID, Integer> DROWNING_CITY_WATER_LEVEL = new HashMap<>();
    /**
     * ИСПРАВЛЕНО ("пролагивает, когда вода появляется"): очередь позиций очередного
     * слоя воды, заливаемого БАТЧАМИ по DROWNING_CITY_FILL_BATCH блоков за тик.
     * Раньше весь слой 321×321 (~103 тыс. позиций) ставился одним махом за один тик —
     * десятки тысяч setBlockState с флагом 3 (соседские апдейты + пересылка клиенту),
     * каждый блок воды запускал fluid physics и пересчёт света -> гарантированный
     * лаг-спайк каждые 6 секунд.
     */
    private static final Map<UUID, java.util.ArrayDeque<BlockPos>> DROWNING_CITY_FILL_QUEUE = new HashMap<>();
    /** Начальный уровень воды в тонущем городе (bedrock 1 + stone 4 + sand 2 = Y=7) */
    private static final int DROWNING_CITY_START_Y = 7;
    /** Максимальный уровень воды (высота двери/воздушного кармана) */
    private static final int DROWNING_CITY_MAX_Y = 80;
    /**
     * Как часто поднимается вода (в тиках) - каждые 4 секунды (80 тиков).
     * ИЗМЕНЕНО: было 120 (6 сек) — при длительности сна 6000 тиков вода успевала
     * подняться лишь до Y=57, поэтому MAX_Y=80 и "критическое" предупреждение
     * никогда не срабатывали. С 80 тиками к концу сна вода доходит до ~Y=80.
     */
    private static final int DROWNING_CITY_WATER_RISE_INTERVAL = 80;
    /**
     * Сколько блоков воды ставится за один тик при заливке слоя (баланс: слой
     * ~103 тыс. позиций заливается за ~50 тиков = 2.5 секунды — вода поднимается
     * заметно быстрее, чем раз в 4 секунды "телепортируется" уровень, но без
     * лаг-спайков: 2048 setBlockState за тик не просаживают сервер).
     */
    private static final int DROWNING_CITY_FILL_BATCH = 2048;

    /** ДОБАВЛЕНО: Лабиринт шахты - генераторы лабиринтов для каждого игрока */
    private static final Map<UUID, MazeGenerator> COLLAPSING_MINE_MAZES = new HashMap<>();
    /** Угол лабиринта (для корректного позиционирования) */
    private static final Map<UUID, BlockPos> COLLAPSING_MINE_ORIGINS = new HashMap<>();

    // ДОБАВЛЕНО (механика "проходы появляются за спиной" по запросу на collapsing_mine):
    /** Базовые позиции проходов, открытых этой механикой (для последующего "зарастания") */
    private static final Map<UUID, List<BlockPos>> MINE_OPENED_PASSAGES = new HashMap<>();
    /** Тик следующего сдвига стен лабиринта */
    private static final Map<UUID, Long> NEXT_MINE_SHIFT_TICK = new HashMap<>();
    /** Каждые 5 секунд лабиринт "дышит": проход за спиной открывается, другой зарастает */
    private static final int MINE_SHIFT_INTERVAL = 100;
    /** Радиус сканирования стен вокруг игрока (в ячейках сетки) */
    private static final int MINE_SHIFT_RADIUS = 8;
    /** Сколько открытых механикой проходов держим одновременно — лишние зарастают */
    private static final int MINE_MAX_OPEN_PASSAGES = 6;

    // ДОБАВЛЕНО (сон "Кровавый пир", редизайн "Последний ужин"):
    /** Позиции "тарелок" пиршественного стола для каждой сессии */
    private static final Map<UUID, List<BlockPos>> FEAST_TABLES = new HashMap<>();
    /** UUID текущего блюда (ItemEntity) на столе */
    private static final Map<UUID, UUID> FEAST_DISH_ITEM = new HashMap<>();
    /** Тронуто ли текущее блюдо (true = есть опасно, надо отодвинуть) */
    private static final Map<UUID, Boolean> FEAST_DISH_TAINTED = new HashMap<>();
    /** Тик, когда нерешённое блюдо убирают гости (обида за невнимание) */
    private static final Map<UUID, Long> FEAST_DISH_DEADLINE = new HashMap<>();
    /** Тик подачи следующего блюда */
    private static final Map<UUID, Long> NEXT_DISH_TICK = new HashMap<>();
    /** Сколько блюд уже разрешено (отведано или отодвинуто) из FEAST_COURSES */
    private static final Map<UUID, Integer> FEAST_COURSES_DONE = new HashMap<>();
    /** UUID Кубка Тоста — финальный выход из сна */
    private static final Map<UUID, UUID> FEAST_TOAST_ITEM = new HashMap<>();
    /** Всего блюд за пир — после них подают кубок */
    private static final int FEAST_COURSES = 5;
    /** Пауза между блюдами (~8 сек); первая подача через 5 сек */
    private static final int FEAST_DISH_INTERVAL = 160;
    private static final int FEAST_FIRST_DISH_DELAY = 100;
    /** Блюдо ждёт решения игрока 20 секунд, потом судьи обиженно убирают его */
    private static final int FEAST_DISH_LIFETIME = 400;
    /** Рассудок за исходы блюд: съел свежее / съел тронутое / сжёг тронутое /
     *  сжёг свежее / проигнорировал (судьи убрали сами) */
    private static final float FEAST_EAT_FRESH_SANITY = 3.0f;
    private static final float FEAST_EAT_TAINTED_SANITY = -6.0f;
    private static final float FEAST_BURN_TAINTED_SANITY = 2.0f;
    private static final float FEAST_BURN_FRESH_SANITY = -2.0f;
    private static final float FEAST_IGNORED_SANITY = -2.0f;
    /** Шанс, что поданное блюдо тронуто */
    private static final double FEAST_TAINTED_CHANCE = 0.45;
    /** UUID судей пира (неподвижные одержимые за столом) — для взгляда вслед игроку */
    private static final Map<UUID, List<UUID>> FEAST_GUESTS = new HashMap<>();
    /** Тик следующего жуткого звука судей */
    private static final Map<UUID, Long> FEAST_NEXT_GUEST_SOUND = new HashMap<>();
    /** Блюдо, подожжённое зажигалкой: UUID сущности -> тик, когда оно догорит и исчезнет */
    private static final Map<UUID, Long> FEAST_BURNING_DISHES = new HashMap<>();
    /** Как долго блюдо зрелищно горит на столе (1.5 сек) */
    private static final int FEAST_BURN_TICKS = 30;
    /** Куда смотрят судьи, когда реагируют на событие (иначе — на игрока) */
    private static final Map<UUID, BlockPos> FEAST_JUDGE_FOCUS_POS = new HashMap<>();
    /** Тик, до которого действует фокус судей */
    private static final Map<UUID, Long> FEAST_JUDGE_FOCUS_UNTIL = new HashMap<>();
    /** Длительность фокуса судей на событии (3 сек) */
    private static final int FEAST_JUDGE_FOCUS_TICKS = 60;
    /** Игроки, которым уже выдали Зажигалку судей (сообщение показываем один раз) */
    private static final java.util.Set<UUID> FEAST_LIGHTER_GIVEN = new java.util.HashSet<>();
    /** NBT-метки сонных предметов пира — по ним же чистим инвентарь при пробуждении */
    private static final String FEAST_LIGHTER_TAG = "somnium_feast_lighter";
    private static final String FEAST_TOAST_TAG = "somnium_feast_toast";

    /** ДОБАВЛЕНО: Сон-в-сне - сохранённые блоки чанка для каждого игрока */
    private static final Map<UUID, ChunkSnapshot> DREAM_WITHIN_DREAM_CHUNKS = new HashMap<>();
    /** Позиция игрока в реальном мире для точного воссоздания */
    private static final Map<UUID, BlockPos> DREAM_WITHIN_DREAM_ORIGINAL_POS = new HashMap<>();
    /** Погода реального мира для копирования */
    private static final Map<UUID, WeatherSnapshot> DREAM_WITHIN_DREAM_ORIGINAL_WEATHER = new HashMap<>();
    /** Когда начали появляться таблички за спиной */
    private static final Map<UUID, Long> DREAM_WITHIN_DREAM_SIGN_START = new HashMap<>();

    /** Структура для хранения снимка чанка */
    private record ChunkSnapshot(
        Map<BlockPos, net.minecraft.block.BlockState> blocks,
        BlockPos origin
    ) {}

    /** Структура для хранения погоды */
    private record WeatherSnapshot(
        long timeOfDay,
        boolean isRaining,
        boolean isThundering
    ) {}

    /**
     * УПРОЩЕНО (сон "Зеркальная комната"): создаёт большую комнату 30×30×8
     * со стеклянной стеной СТРОГО ПОСЕРЕДИНЕ. Игрок слева, моб справа, расстояния равны.
     */
    /**
     * ИЗМЕНЕНО (мультиплеер): теперь принимает sessionKey (groupId) вместо playerId
     */
    private static BlockPos setupMirrorRoomAndGetPlayerSpawn(ServerWorld world, BlockPos center, UUID sessionKey) {
        // УВЕЛИЧЕНО: размеры комнаты 30×30 блоков, высота 8 блоков
        int roomSize = 30;
        int roomHeight = 8;

        // Угол комнаты - центрируем относительно точки спавна (0,0,0)
        BlockPos roomCorner = center.add(-roomSize/2, 0, -roomSize/2);

        // Строим пол из гладкого кварца
        for (int x = 0; x < roomSize; x++) {
            for (int z = 0; z < roomSize; z++) {
                world.setBlockState(roomCorner.add(x, -1, z),
                    net.minecraft.block.Blocks.SMOOTH_QUARTZ.getDefaultState());
            }
        }

        // Строим стены из кварцевых блоков
        for (int y = 0; y < roomHeight; y++) {
            for (int x = 0; x < roomSize; x++) {
                // Северная и южная стены
                world.setBlockState(roomCorner.add(x, y, 0),
                    net.minecraft.block.Blocks.QUARTZ_BLOCK.getDefaultState());
                world.setBlockState(roomCorner.add(x, y, roomSize-1),
                    net.minecraft.block.Blocks.QUARTZ_BLOCK.getDefaultState());
            }
            for (int z = 0; z < roomSize; z++) {
                // Западная и восточная стены
                world.setBlockState(roomCorner.add(0, y, z),
                    net.minecraft.block.Blocks.QUARTZ_BLOCK.getDefaultState());
                world.setBlockState(roomCorner.add(roomSize-1, y, z),
                    net.minecraft.block.Blocks.QUARTZ_BLOCK.getDefaultState());
            }
        }

        // Строим потолок из гладкого кварца
        for (int x = 0; x < roomSize; x++) {
            for (int z = 0; z < roomSize; z++) {
                world.setBlockState(roomCorner.add(x, roomHeight, z),
                    net.minecraft.block.Blocks.SMOOTH_QUARTZ.getDefaultState());
            }
        }

        // ИСПРАВЛЕНО: стеклянная стена СТРОГО ПОСЕРЕДИНЕ комнаты
        // Комната 30×30, центр на X=15, игрок на X=7, моб на X=23
        List<BlockPos> glassBlocks = new ArrayList<>();
        int middleX = roomSize / 2; // 15 - СТРОГО ЦЕНТР комнаты
        for (int y = 0; y < roomHeight; y++) {
            for (int z = 1; z < roomSize - 1; z++) {
                BlockPos glassPos = roomCorner.add(middleX, y, z);
                world.setBlockState(glassPos, net.minecraft.block.Blocks.GLASS.getDefaultState());
                glassBlocks.add(glassPos);
            }
        }

        // Добавляем освещение по всему потолку
        for (int x = 3; x < roomSize - 3; x += 5) {
            for (int z = 3; z < roomSize - 3; z += 5) {
                world.setBlockState(roomCorner.add(x, roomHeight-1, z),
                    net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState());
            }
        }

        // Сохраняем позиции стекла для последующего разбивания
        MIRROR_ROOM_GLASS.put(sessionKey, glassBlocks);
        MIRROR_GLASS_BREAK_TIME.put(sessionKey, world.getServer().getTicks() + 400L); // 20 секунд
        MIRROR_ROOM_CORNER.put(sessionKey, roomCorner);

        // ИСПРАВЛЕНО: игрок спавнится СЛЕВА от стекла с отступом 8 блоков
        // Стекло на X=15, игрок на X=7 (отступ 8 блоков слева)
        int playerX = middleX - 8; // 7
        int centerZ = roomSize / 2; // 15 (центр по Z)
        return roomCorner.add(playerX, 0, centerZ);
    }

    /**
     * ИСПРАВЛЕНО (сон "Зеркальная комната"): моб спавнится ЗЕРКАЛЬНО от игрока относительно стекла.
     * Комната 30×30, стекло на X=15, игрок на X=7, моб на X=23 (оба на расстоянии 8 блоков от стекла).
     */
    public static BlockPos getMirrorMonsterSpawnPos(BlockPos playerSpawn, UUID playerId) {
        BlockPos roomCorner = MIRROR_ROOM_CORNER.get(playerId);
        if (roomCorner == null) {
            // Fallback: моб на +16 блоков от игрока (2 × 8)
            return playerSpawn.add(16, 0, 0);
        }
        // Комната 30×30, стекло на X=15, игрок на X=7, моб на X=23
        int roomSize = 30;
        int middleX = roomSize / 2; // 15 (стекло)
        int mobX = middleX + 8; // 23 (отступ 8 блоков СПРАВА от стекла, как и игрок слева)
        int mobZ = roomSize / 2; // 15 (центр по Z)
        return roomCorner.add(mobX, 0, mobZ);
    }

    /**
     * ДОБАВЛЕНО (сон "Зеркальная комната"): проверяет таймер разбивания стекла.
     * Через 15 секунд после входа стекло разбивается с эффектами.
     *
     * ИЗМЕНЕНО (мультиплеер): теперь обходит активных игроков и использует sessionKey
     */
    public static void tickMirrorRoomGlass(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;

        long now = server.getTicks();
        // Используем Set для отслеживания уже обработанных sessionKey (избегаем дублирования для группы)
        var processedSessions = new java.util.HashSet<UUID>();

        for (UUID playerId : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(playerId);
            if (active == null || !active.dreamId().equals(SomniumMod.id("mirror_room"))) continue;

            UUID sessionKey = sessionKey(playerId);
            if (processedSessions.contains(sessionKey)) continue; // уже обработали для этой группы
            processedSessions.add(sessionKey);

            Long breakTime = MIRROR_GLASS_BREAK_TIME.get(sessionKey);
            if (breakTime == null || now < breakTime) continue;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || !(player.getEntityWorld() instanceof ServerWorld dreamWorld)) continue;

            List<BlockPos> glassBlocks = MIRROR_ROOM_GLASS.get(sessionKey);
            if (glassBlocks == null || glassBlocks.isEmpty()) continue;

            // Разбиваем все стеклянные блоки
            for (BlockPos glassPos : glassBlocks) {
                dreamWorld.setBlockState(glassPos, net.minecraft.block.Blocks.AIR.getDefaultState());

                // Эффект разбивания стекла
                dreamWorld.spawnParticles(
                    new net.minecraft.particle.BlockStateParticleEffect(
                        net.minecraft.particle.ParticleTypes.BLOCK,
                        net.minecraft.block.Blocks.GLASS.getDefaultState()
                    ),
                    glassPos.getX() + 0.5, glassPos.getY() + 0.5, glassPos.getZ() + 0.5,
                    15, 0.3, 0.3, 0.3, 0.1
                );
            }

            // Звук разбивающегося стекла
            dreamWorld.playSound(null, glassBlocks.get(glassBlocks.size()/2),
                net.minecraft.sound.SoundEvents.BLOCK_GLASS_BREAK,
                net.minecraft.sound.SoundCategory.BLOCKS,
                2.0f, 0.8f);

            // Очищаем данные (один раз для всей группы)
            MIRROR_ROOM_GLASS.remove(sessionKey);
            MIRROR_GLASS_BREAK_TIME.remove(sessionKey);
        }
    }

    /**
     * ДОБАВЛЕНО (сон "Тонущий город"): постепенный подъём уровня воды.
     * Вода поднимается каждые 6 секунд на 1 блок, создавая давление на игрока
     * добраться до воздушного кармана (двери пробуждения) до того, как вода поднимется до потолка.
     *
     * ИЗМЕНЕНО (мультиплеер): теперь использует sessionKey для общего уровня воды группы
     */
    public static void tickDrowningCityWater(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;

        long now = server.getTicks();
        var processedSessions = new java.util.HashSet<UUID>();

        for (UUID playerId : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(playerId);
            if (active == null || !active.dreamId().equals(SomniumMod.id("drowning_city"))) continue;

            UUID sessionKey = sessionKey(playerId);
            if (processedSessions.contains(sessionKey)) continue;
            processedSessions.add(sessionKey);

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || !(player.getEntityWorld() instanceof ServerWorld dreamWorld)) continue;

            long elapsed = now - active.enterTick();

            // Притяжение на дно для ВСЕХ игроков в группе
            for (UUID memberId : new ArrayList<>(ACTIVE.keySet())) {
                ActiveDream memberActive = ACTIVE.get(memberId);
                if (memberActive == null || !memberActive.dreamId().equals(SomniumMod.id("drowning_city"))) continue;
                if (!sessionKey.equals(sessionKey(memberId))) continue;

                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
                if (member == null) continue;

                if (elapsed >= 400 && (member.isSubmergedInWater() || member.isTouchingWater())) {
                    double secondsSincePull = (elapsed - 400) / 20.0;
                    double pullStrength = Math.min(0.15 + secondsSincePull * 0.005, 0.35);
                    net.minecraft.util.math.Vec3d vel = member.getVelocity();
                    member.setVelocity(vel.x, vel.y - pullStrength, vel.z);
                    member.velocityDirty = true;

                    if (elapsed % 60 == 0) {
                        member.sendMessage(
                            net.minecraft.text.Text.literal("§4§lГлубина тянет вас на дно!"),
                            true
                        );
                    }
                }
            }

            java.util.ArrayDeque<BlockPos> queue =
                    DROWNING_CITY_FILL_QUEUE.computeIfAbsent(sessionKey, k -> new java.util.ArrayDeque<>());

            Integer currentLevel = DROWNING_CITY_WATER_LEVEL.get(sessionKey);
            if (currentLevel == null) currentLevel = DROWNING_CITY_START_Y;

            // Новый уровень планируем только когда предыдущий слой полностью долит —
            // при отставании (лаг/AFK) уровни догоняются последовательно, без фризов.
            if (queue.isEmpty()) {
                int expectedWaterLevel = DROWNING_CITY_START_Y + (int)(elapsed / DROWNING_CITY_WATER_RISE_INTERVAL);
                expectedWaterLevel = Math.min(expectedWaterLevel, DROWNING_CITY_MAX_Y);

                if (expectedWaterLevel > currentLevel) {
                    int newLevel = currentLevel + 1;
                    DROWNING_CITY_WATER_LEVEL.put(sessionKey, newLevel);

                    // Сканируем слой и ставим позиции в очередь
                    enqueueWaterLayer(dreamWorld, player.getBlockPos(), newLevel, queue);

                    // Звук воды каждые 5 уровней
                    if (newLevel % 5 == 0) {
                        dreamWorld.playSound(null, player.getBlockPos(),
                            net.minecraft.sound.SoundEvents.AMBIENT_UNDERWATER_ENTER,
                            net.minecraft.sound.SoundCategory.AMBIENT,
                            1.5f, 0.8f);
                    }

                    // Предупреждение ВСЕМ игрокам в группе когда вода достигает критического уровня
                    if (newLevel >= DROWNING_CITY_MAX_Y - 10) {
                        for (UUID memberId : new ArrayList<>(ACTIVE.keySet())) {
                            if (!sessionKey.equals(sessionKey(memberId))) continue;
                            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
                            if (member != null) {
                                member.sendMessage(
                                    net.minecraft.text.Text.literal("§c§lВода поднимается! Найдите воздушный карман!"),
                                    false
                                );
                            }
                        }
                    }
                }
            }

            // Дренируем очередь батчем
            int placed = 0;
            while (placed < DROWNING_CITY_FILL_BATCH && !queue.isEmpty()) {
                BlockPos waterPos = queue.poll();
                if (dreamWorld.getBlockState(waterPos).isAir()) {
                    dreamWorld.setBlockState(waterPos, net.minecraft.block.Blocks.WATER.getDefaultState(),
                            net.minecraft.block.Block.NOTIFY_LISTENERS);
                    placed++;
                }
            }
        }
    }

    /**
     * Собирает в очередь все воздушные позиции слоя Y в радиусе 160 блоков (10 чанков)
     * вокруг игрока — сама заливка выполняется батчами в tickDrowningCityWater().
     */
    private static void enqueueWaterLayer(ServerWorld world, BlockPos center, int y, java.util.ArrayDeque<BlockPos> queue) {
        int radius = 160; // 10 чанков = 10 * 16 = 160 блоков
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                if (world.getBlockState(pos).isAir()) {
                    queue.add(pos);
                }
            }
        }
    }

    /**
     * ДОБАВЛЕНО (сон "Обрушающаяся шахта"): создаёт процедурный лабиринт из каменных блоков.
     * Лабиринт генерируется вокруг центра мира, игрок спавнится в случайной ячейке,
     * дверь пробуждения в самой дальней точке от игрока.
     */
    /**
     * ИЗМЕНЕНО (мультиплеер): теперь принимает sessionKey (groupId) вместо playerId
     */
    private static BlockPos setupCollapsingMineAndGetPlayerSpawn(ServerWorld world, BlockPos center, UUID sessionKey) {
        // Параметры лабиринта
        int mazeWidth = 20;  // 20x20 ячеек
        int mazeHeight = 20;
        int cellSize = 3;    // Каждая ячейка 3x3 блока
        int wallHeight = 4;  // Высота стен

        MazeGenerator maze = new MazeGenerator(mazeWidth, mazeHeight, cellSize, RANDOM);
        maze.generate();

        // Сохраняем для последующего использования
        COLLAPSING_MINE_MAZES.put(sessionKey, maze);

        // Вычисляем угол лабиринта (центрируем относительно точки спавна)
        // ИСПРАВЛЕНО: полный размер сетки — (width*2+1) ячеек (ячейки + стены между ними),
        // раньше лабиринт был смещён относительно центра почти на всю свою ширину
        int totalSize = (mazeWidth * 2 + 1) * cellSize;
        BlockPos origin = center.add(-totalSize / 2, 0, -totalSize / 2);
        COLLAPSING_MINE_ORIGINS.put(sessionKey, origin);

        // Строим лабиринт в мире
        buildMazeInWorld(world, maze, origin, wallHeight);

        // Находим позицию спавна игрока
        // ИСПРАВЛЕНО: baseY в MazeGenerator ПРИБАВЛЯЕТСЯ к origin.y (origin.add(x, baseY, z)),
        // а сюда передавался абсолютный center.getY() — высота удваивалась, и игрок
        // появлялся высоко НАД лабиринтом. Передаём 0: уровень пола уже в origin.
        return maze.findSpawnPosition(origin, 0);
    }

    /**
     * Строит физический лабиринт в мире из блоков
     */
    private static void buildMazeInWorld(ServerWorld world, MazeGenerator maze, BlockPos origin, int wallHeight) {
        int gridWidth = maze.getWidth() * 2 + 1;
        int gridHeight = maze.getHeight() * 2 + 1;
        int cellSize = maze.getCellSize();

        // Строим пол из каменных кирпичей
        for (int x = 0; x < gridWidth * cellSize; x++) {
            for (int z = 0; z < gridHeight * cellSize; z++) {
                world.setBlockState(origin.add(x, -1, z),
                    net.minecraft.block.Blocks.STONE_BRICKS.getDefaultState());
            }
        }

        // Строим стены
        for (int gridX = 0; gridX < gridWidth; gridX++) {
            for (int gridZ = 0; gridZ < gridHeight; gridZ++) {
                if (maze.isWall(gridX, gridZ)) {
                    // Это стена - строим блоки
                    for (int x = 0; x < cellSize; x++) {
                        for (int z = 0; z < cellSize; z++) {
                            int worldX = gridX * cellSize + x;
                            int worldZ = gridZ * cellSize + z;

                            for (int y = 0; y < wallHeight; y++) {
                                // Чередуем блоки для атмосферы шахты
                                net.minecraft.block.Block block;
                                if (y == 0 || RANDOM.nextFloat() < 0.8f) {
                                    block = net.minecraft.block.Blocks.DEEPSLATE;
                                } else {
                                    block = net.minecraft.block.Blocks.COAL_ORE;
                                }
                                world.setBlockState(origin.add(worldX, y, worldZ), block.getDefaultState());
                            }
                        }
                    }
                } else {
                    // Это проход - добавляем освещение (факелы на стенах)
                    if (RANDOM.nextFloat() < 0.15f) {
                        int centerX = gridX * cellSize + cellSize / 2;
                        int centerZ = gridZ * cellSize + cellSize / 2;

                        // ИСПРАВЛЕНО ("факелы спавнятся в воздухе по центру"): раньше факел ставился
                        // в ЦЕНТР прохода дефолтным состоянием WALL_TORCH без свойства FACING — то есть
                        // без опоры он просто висел в воздухе (и рано или поздно "высыпался"). Теперь
                        // факел ставится в блок прохода, вплотную примыкающий к стене, с корректным
                        // FACING (направлен от стены в проход) и только к ортогональному соседу —
                        // диагональная "стена" опорой для настенного факела быть не может.
                        boolean torchPlaced = false;
                        for (int dx = -1; dx <= 1 && !torchPlaced; dx++) {
                            for (int dz = -1; dz <= 1 && !torchPlaced; dz++) {
                                if ((dx == 0) == (dz == 0)) continue; // только ортогональные соседи
                                if (maze.isWall(gridX + dx, gridZ + dz)) {
                                    net.minecraft.util.math.Direction facing =
                                            dx == 1 ? net.minecraft.util.math.Direction.WEST :
                                            dx == -1 ? net.minecraft.util.math.Direction.EAST :
                                            dz == 1 ? net.minecraft.util.math.Direction.NORTH :
                                            net.minecraft.util.math.Direction.SOUTH;
                                    world.setBlockState(origin.add(centerX + dx, 1, centerZ + dz),
                                            net.minecraft.block.Blocks.WALL_TORCH.getDefaultState()
                                                    .with(net.minecraft.block.WallTorchBlock.FACING, facing));
                                    torchPlaced = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Строим потолок из каменных блоков
        for (int x = 0; x < gridWidth * cellSize; x++) {
            for (int z = 0; z < gridHeight * cellSize; z++) {
                world.setBlockState(origin.add(x, wallHeight, z),
                    net.minecraft.block.Blocks.DEEPSLATE.getDefaultState());
            }
        }
    }

    /**
     * ДОБАВЛЕНО (механика "проходы появляются за спиной" по запросу на collapsing_mine):
     * каждые MINE_SHIFT_INTERVAL тиков лабиринт меняется, пока игрок не смотрит:
     *  - случайная стена ПОЗАДИ игрока (вне угла обзора ~100°) обрушается, открывая
     *    новый проход (звук + частицы);
     *  - один из ранее открытых проходов зарастает обратно — тоже только вне поля
     *    зрения и не под ногами игрока.
     * Открытые механикой проходы не меняют карту MazeGenerator — дверь остаётся
     * достижимой по исходному лабиринту, а сдвиги добавляют короткие пути.
     */
    /**
     * ИЗМЕНЕНО (мультиплеер): теперь обходит активных игроков и использует sessionKey
     */
    public static void tickCollapsingMine(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long now = server.getTicks();
        var processedSessions = new java.util.HashSet<UUID>();

        for (UUID playerId : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(playerId);
            if (active == null || !active.dreamId().equals(SomniumMod.id("collapsing_mine"))) continue;

            UUID sessionKey = sessionKey(playerId);
            if (processedSessions.contains(sessionKey)) continue;
            processedSessions.add(sessionKey);

            Long nextShift = NEXT_MINE_SHIFT_TICK.get(sessionKey);
            if (nextShift != null && now < nextShift) continue;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            MazeGenerator maze = COLLAPSING_MINE_MAZES.get(sessionKey);
            BlockPos origin = COLLAPSING_MINE_ORIGINS.get(sessionKey);
            if (player == null || maze == null || origin == null
                    || !(player.getEntityWorld() instanceof ServerWorld world)) continue;

            NEXT_MINE_SHIFT_TICK.put(sessionKey, now + MINE_SHIFT_INTERVAL);

            int cellSize = maze.getCellSize();
            double yawRad = Math.toRadians(player.getYaw());
            double lookX = -Math.sin(yawRad);
            double lookZ = Math.cos(yawRad);
            int gridW = maze.getWidth() * 2 + 1;
            int gridH = maze.getHeight() * 2 + 1;
            int pgx = (player.getBlockX() - origin.getX()) / cellSize;
            int pgz = (player.getBlockZ() - origin.getZ()) / cellSize;

            // --- Открываем случайную стену за спиной ---
            List<int[]> candidates = new ArrayList<>();
            for (int gx = Math.max(1, pgx - MINE_SHIFT_RADIUS); gx <= Math.min(gridW - 2, pgx + MINE_SHIFT_RADIUS); gx++) {
                for (int gz = Math.max(1, pgz - MINE_SHIFT_RADIUS); gz <= Math.min(gridH - 2, pgz + MINE_SHIFT_RADIUS); gz++) {
                    if (!maze.isWall(gx, gz)) continue;
                    // Стена должна разделять два прохода — иначе обрушение ведёт в никуда
                    boolean splitsX = !maze.isWall(gx - 1, gz) && !maze.isWall(gx + 1, gz);
                    boolean splitsZ = !maze.isWall(gx, gz - 1) && !maze.isWall(gx, gz + 1);
                    if (!splitsX && !splitsZ) continue;
                    if (isInPlayerView(player, lookX, lookZ, origin, gx, gz, cellSize)) continue; // только за спиной
                    candidates.add(new int[]{gx, gz});
                }
            }
            if (!candidates.isEmpty()) {
                int[] pick = candidates.get(RANDOM.nextInt(candidates.size()));
                BlockPos base = origin.add(pick[0] * cellSize, 0, pick[1] * cellSize);
                for (int x = 0; x < cellSize; x++) {
                    for (int z = 0; z < cellSize; z++) {
                        for (int y = 0; y < 3; y++) {
                            world.setBlockState(base.add(x, y, z), net.minecraft.block.Blocks.AIR.getDefaultState());
                        }
                    }
                }
                MINE_OPENED_PASSAGES.computeIfAbsent(sessionKey, k -> new ArrayList<>()).add(base);
                world.playSound(null, base, net.minecraft.sound.SoundEvents.BLOCK_DEEPSLATE_BREAK,
                        net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 0.5f);
                world.spawnParticles(net.minecraft.particle.ParticleTypes.EXPLOSION,
                        base.getX() + 1.5, base.getY() + 1.5, base.getZ() + 1.5,
                        2, 1.0, 1.0, 1.0, 0.0);
            }

            // --- Зарастает один из открытых проходов (тоже вне поля зрения) ---
            List<BlockPos> opened = MINE_OPENED_PASSAGES.get(sessionKey);
            if (opened != null && !opened.isEmpty()) {
                for (int i = 0; i < opened.size(); i++) {
                    BlockPos base = opened.get(i);
                    int gx = (base.getX() - origin.getX()) / cellSize;
                    int gz = (base.getZ() - origin.getZ()) / cellSize;
                    // Не заращиваем проход, в котором стоит игрок
                    if (Math.abs(gx - pgx) <= 1 && Math.abs(gz - pgz) <= 1) continue;
                    // Под лимитом — только за спиной; сверх лимита закрываем принудительно
                    if (opened.size() <= MINE_MAX_OPEN_PASSAGES
                            && isInPlayerView(player, lookX, lookZ, origin, gx, gz, cellSize)) continue;
                    // Восстанавливаем стену как в buildMazeInWorld
                    for (int x = 0; x < cellSize; x++) {
                        for (int z = 0; z < cellSize; z++) {
                            for (int y = 0; y < 4; y++) {
                                net.minecraft.block.Block block = (y == 0 || RANDOM.nextFloat() < 0.8f)
                                        ? net.minecraft.block.Blocks.DEEPSLATE
                                        : net.minecraft.block.Blocks.COAL_ORE;
                                world.setBlockState(base.add(x, y, z), block.getDefaultState());
                            }
                        }
                    }
                    opened.remove(i);
                    world.playSound(null, base, net.minecraft.sound.SoundEvents.BLOCK_DEEPSLATE_PLACE,
                            net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 0.5f);
                    break; // за один сдвиг заращиваем максимум один проход
                }
            }
        }
    }

    /**
     * ДОБАВЛЕНО: true, если позиция сетки (gx,gz) в поле зрения игрока (в пределах ~100°
     * от направления взгляда) или прямо под ним — такие места лабиринт не трогает.
     */
    private static boolean isInPlayerView(ServerPlayerEntity player, double lookX, double lookZ,
                                          BlockPos origin, int gx, int gz, int cellSize) {
        double dx = origin.getX() + gx * cellSize + cellSize / 2.0 - player.getX();
        double dz = origin.getZ() + gz * cellSize + cellSize / 2.0 - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 2.0) return true;
        double dot = (dx / len) * lookX + (dz / len) * lookZ;
        return dot > -0.17; // cos(100°) ≈ -0.17
    }

    /**
     * ДОБАВЛЕНО (сон "Кровавый пир", редизайн "Последний ужин"): строит банкетный зал —
     * тёмный чернитно-багровый чертог с базальтовыми колоннами, люстрой из фонарей душ,
     * кострами душ по углам, багровыми знамёнами и длинным столом. За столом — пять
     * Судей пира: одержимые в черепах иссер-скелетов и тёмных рясах, неподвижные,
     * вечно смотрящие на игрока (см. tickCrimsonFeast). Игрок спавнится на багровой
     * дорожке напротив них; блюда подаются на центральную "тарелку".
     */
    private static void setupCrimsonFeastTable(ServerWorld world, BlockPos center, UUID sessionKey) {
        int floorY = center.getY() - 1;

        // === ЗАЛ: пол, дорожка, стены с колоннами, потолок ===
        for (int x = -8; x <= 8; x++) {
            for (int z = -7; z <= 7; z++) {
                // Пол: полированный чернит; багровая дорожка от входа к столу
                boolean runner = Math.abs(x) <= 1 && z >= -7;
                world.setBlockState(new BlockPos(center.getX() + x, floorY, center.getZ() + z),
                        runner ? net.minecraft.block.Blocks.NETHER_WART_BLOCK.getDefaultState()
                               : net.minecraft.block.Blocks.POLISHED_BLACKSTONE.getDefaultState());
                // Потолок
                world.setBlockState(new BlockPos(center.getX() + x, floorY + 7, center.getZ() + z),
                        net.minecraft.block.Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
            }
        }
        for (int y = 0; y <= 5; y++) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -7; z <= 7; z++) {
                    boolean wall = Math.abs(x) == 8 || Math.abs(z) == 7;
                    if (!wall) continue;
                    // Дверной проём на юге — оттуда "пришёл" игрок
                    if (z == 7 && Math.abs(x) <= 1 && y <= 2) continue;
                    boolean pillar = (Math.abs(x) == 8 && (Math.abs(z) == 7 || z == -3 || z == 1))
                                  || (Math.abs(z) == 7 && (Math.abs(x) == 8 || x == -4 || x == 0 || x == 4));
                    BlockPos pos = new BlockPos(center.getX() + x, floorY + 1 + y, center.getZ() + z);
                    if (pillar) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.POLISHED_BASALT.getDefaultState()
                                .with(net.minecraft.block.PillarBlock.AXIS, net.minecraft.util.math.Direction.Axis.Y));
                    } else if (y == 5) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState());
                    } else if (y == 3) {
                        // Багровый пояс по стенам
                        world.setBlockState(pos, net.minecraft.block.Blocks.RED_NETHER_BRICKS.getDefaultState());
                    } else {
                        world.setBlockState(pos, net.minecraft.block.Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
                    }
                }
            }
        }

        // === ДЕКОР: люстра над столом, костры душ по углам, знамёна, свечи ===
        // Люстра: цепь с потолка + висячий фонарь душ над центром стола
        world.setBlockState(center.add(0, 5, -3), net.minecraft.block.Blocks.CHAIN.getDefaultState());
        world.setBlockState(center.add(0, 4, -3), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState()
                .with(net.minecraft.block.LanternBlock.HANGING, true));
        // Костры душ по углам зала — синие ведьмины огни и дым
        for (int[] corner : new int[][] {{-6, -5}, {6, -5}, {-6, 5}, {6, 5}}) {
            world.setBlockState(new BlockPos(center.getX() + corner[0], floorY + 1, center.getZ() + corner[1]),
                    net.minecraft.block.Blocks.SOUL_CAMPFIRE.getDefaultState()
                            .with(net.minecraft.block.CampfireBlock.LIT, true));
        }
        // Багровые знамёна на северной стене за судьями
        for (int bx : new int[] {-3, 0, 3}) {
            world.setBlockState(center.add(bx, 3, -7),
                    net.minecraft.block.Blocks.RED_WALL_BANNER.getDefaultState()
                            .with(net.minecraft.block.WallBannerBlock.FACING, net.minecraft.util.math.Direction.SOUTH));
        }

        // === СТОЛ: 9 блоков вдоль оси X, свечи, фонари душ по краям ===
        List<BlockPos> plates = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            BlockPos tablePos = center.add(x, 0, -3);
            world.setBlockState(tablePos, net.minecraft.block.Blocks.POLISHED_BLACKSTONE.getDefaultState());
            plates.add(tablePos);
        }
        // Свечи на столе (не занимая центральную тарелку игрока)
        for (int cx : new int[] {-3, 3}) {
            world.setBlockState(center.add(cx, 1, -3), net.minecraft.block.Blocks.RED_CANDLE.getDefaultState()
                    .with(net.minecraft.block.CandleBlock.CANDLES, 2)
                    .with(net.minecraft.block.CandleBlock.LIT, true));
        }
        // Фонари душ по краям стола
        world.setBlockState(center.add(-5, 0, -3), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState());
        world.setBlockState(center.add(5, 0, -3), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState());
        // Троны судей за столом: сиденья-ступени и высокие спинки
        for (int tx = -4; tx <= 4; tx += 2) {
            world.setBlockState(center.add(tx, 0, -5), net.minecraft.block.Blocks.BLACKSTONE_STAIRS.getDefaultState()
                    .with(net.minecraft.block.StairsBlock.FACING, net.minecraft.util.math.Direction.SOUTH));
            world.setBlockState(center.add(tx, 0, -6), net.minecraft.block.Blocks.POLISHED_BLACKSTONE.getDefaultState());
            world.setBlockState(center.add(tx, 1, -6), net.minecraft.block.Blocks.POLISHED_BLACKSTONE.getDefaultState());
            world.setBlockState(center.add(tx, 2, -6), net.minecraft.block.Blocks.POLISHED_BLACKSTONE.getDefaultState());
        }

        // === СУДЬИ ПИРА: одержимые в черепах иссер-скелетов и тёмных рясах.
        // ИЗМЕНЕНО по фидбеку "манекены не страшные": череп-маска + ряса + хореография
        // взглядов и реакций (см. tickCrimsonFeast, judgesReact) ===
        List<UUID> guests = new ArrayList<>();
        for (int x = -4; x <= 4; x += 2) {
            var guestType = com.somnium.mod.registry.ModEntities.FERAL_VILLAGER;
            var created = guestType.create(world);
            if (created == null) continue;
            created.refreshPositionAndAngles(
                    center.getX() + x + 0.5, center.getY(), center.getZ() - 4 + 0.5, 0f, 0f);
            created.setAiDisabled(true);      // неподвижен: восседает за столом
            created.setInvulnerable(true);    // его нельзя убить — он часть сцены
            created.setSilent(true);          // звуки только через сценарий
            created.setPersistent();          // не деспавнится
            created.setCustomName(net.minecraft.text.Text.literal("§4§lСудья пира"));
            created.setCustomNameVisible(true);
            // Череп иссер-скелета как маска и тёмная ряса из окрашенной кожи
            created.equipStack(net.minecraft.entity.EquipmentSlot.HEAD,
                    new ItemStack(net.minecraft.item.Items.WITHER_SKELETON_SKULL));
            created.equipStack(net.minecraft.entity.EquipmentSlot.CHEST,
                    dyedRobe(new ItemStack(net.minecraft.item.Items.LEATHER_CHESTPLATE)));
            created.equipStack(net.minecraft.entity.EquipmentSlot.LEGS,
                    dyedRobe(new ItemStack(net.minecraft.item.Items.LEATHER_LEGGINGS)));
            for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
                created.setEquipmentDropChance(slot, 0.0f);
            }
            world.spawnEntity(created);
            guests.add(created.getUuid());
        }

        FEAST_TABLES.put(sessionKey, plates);
        FEAST_GUESTS.put(sessionKey, guests);
        FEAST_NEXT_GUEST_SOUND.put(sessionKey,
                (long) world.getServer().getTicks() + 300 + RANDOM.nextInt(200));
        FEAST_DISH_ITEM.remove(sessionKey);
        FEAST_DISH_TAINTED.remove(sessionKey);
        FEAST_COURSES_DONE.put(sessionKey, 0);
        FEAST_TOAST_ITEM.remove(sessionKey);
        FEAST_JUDGE_FOCUS_POS.remove(sessionKey);
        FEAST_JUDGE_FOCUS_UNTIL.remove(sessionKey);
        NEXT_DISH_TICK.put(sessionKey, (long) world.getServer().getTicks() + FEAST_FIRST_DISH_DELAY);
    }

    /** Тёмно-багровая ряса судьи — кожаный доспех, окрашенный почти в чёрный. */
    private static ItemStack dyedRobe(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.item.DyeableItem dyeable) {
            dyeable.setColor(stack, 0x1B0507);
        }
        return stack;
    }

    /**
     * ДОБАВЛЕНО (сон "Кровавый пир", редизайн "Последний ужин"): судьи по очереди
     * подают FEAST_COURSES блюд. Каждое блюдо либо свежее, либо тронутое (дымится зелёным):
     *  - ОТВЕДАТЬ (подойти или ПКМ рукой): свежее +FEAST_EAT_FRESH_SANITY рассудка;
     *    тронутое — FEAST_EAT_TAINTED_SANITY, яд и смех судей;
     *  - СЖЕЧЬ (ПКМ Зажигалкой судей по блюду): тронутое сжечь правильно
     *    (+FEAST_BURN_TAINTED_SANITY, судьи одобряют), свежее — оскорбление кухни
     *    (FEAST_BURN_FRESH_SANITY, судьи шипят);
     *  - ПРОИГНОРИРОВАТЬ: через FEAST_DISH_LIFETIME судьи обиженно убирают блюдо сами.
     * После всех блюд подают Кубок Тоста — возьми его и ВЫПЕЙ до дна (зажми ПКМ) = победа.
     * Поражение — смерть во сне (DIED_IN_DREAM); таймаут — "пережил без тоста".
     */
    public static void tickCrimsonFeast(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long now = server.getTicks();

        // Подожжённые блюда догорают независимо от того, какой игрок сейчас обрабатывается
        tickBurningDishes(server, now);

        for (UUID playerId : new ArrayList<>(ACTIVE.keySet())) {
            ActiveDream active = ACTIVE.get(playerId);
            if (active == null || !active.dreamId().equals(SomniumMod.id("crimson_feast"))) {
                continue; // не наш сон
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || !(player.getEntityWorld() instanceof ServerWorld world)) continue;

            UUID sessionKey = sessionKey(playerId);

            // 0.1) ЗАЩИТА СЦЕНЫ: никаких агрессивных мобов в зале пира — убираем любого,
            // кто заспавнился естественно (плоский мир + тёмный зал) или забрёл извне.
            // Судьи исключены по UUID — они свои.
            if (now % 10 == 0) {
                List<BlockPos> platesForSweep = FEAST_TABLES.get(sessionKey);
                List<UUID> judgeIds = FEAST_GUESTS.get(sessionKey);
                if (platesForSweep != null && !platesForSweep.isEmpty()) {
                    BlockPos mid = platesForSweep.get(platesForSweep.size() / 2);
                    for (net.minecraft.entity.mob.MobEntity mob : world.getEntitiesByClass(
                            net.minecraft.entity.mob.MobEntity.class,
                            new net.minecraft.util.math.Box(mid).expand(28, 12, 28),
                            e -> e instanceof net.minecraft.entity.mob.Monster
                                    && (judgeIds == null || !judgeIds.contains(e.getUuid())))) {
                        mob.discard();
                    }
                }
            }

            // 0.2) Зажигалка судей: выдаём участникам пира (и перевыдаём при потере)
            if (now % 40 == 0 && !hasFeastLighter(player)) {
                giveFeastLighter(player);
            }

            // 0.3) Судьи: смотрят на фокус события (горящее блюдо, кубок) или на игрока
            List<UUID> guests = FEAST_GUESTS.get(sessionKey);
            if (guests != null && !guests.isEmpty() && now % 5 == 0) {
                BlockPos focus = null;
                Long focusUntil = FEAST_JUDGE_FOCUS_UNTIL.get(sessionKey);
                if (focusUntil != null && now < focusUntil) {
                    focus = FEAST_JUDGE_FOCUS_POS.get(sessionKey);
                }
                for (UUID guestUuid : guests) {
                    if (world.getEntity(guestUuid) instanceof net.minecraft.entity.mob.MobEntity guest) {
                        double targetX = focus != null ? focus.getX() + 0.5 : player.getX();
                        double targetZ = focus != null ? focus.getZ() + 0.5 : player.getZ();
                        double dx = targetX - guest.getX();
                        double dz = targetZ - guest.getZ();
                        float lookYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
                        guest.setYaw(lookYaw);
                        guest.setBodyYaw(lookYaw);
                        guest.setHeadYaw(lookYaw);
                    }
                }
            }
            if (now >= FEAST_NEXT_GUEST_SOUND.getOrDefault(sessionKey, Long.MAX_VALUE)) {
                // Все судьи разом издают низкий искажённый стон — синхронно и жутко
                if (guests != null && !guests.isEmpty()) {
                    for (UUID guestUuid : guests) {
                        var guest = world.getEntity(guestUuid);
                        if (guest != null) {
                            world.playSound(null, guest.getX(), guest.getY(), guest.getZ(),
                                    net.minecraft.sound.SoundEvents.ENTITY_ZOMBIE_VILLAGER_AMBIENT,
                                    net.minecraft.sound.SoundCategory.HOSTILE, 0.7f, 0.45f);
                        }
                    }
                }
                FEAST_NEXT_GUEST_SOUND.put(sessionKey, now + 300 + RANDOM.nextInt(300));
            }

            // 0.4) Финал: игрок пьёт Кубок Тоста — победа, когда он допьёт до дна
            if (player.isUsingItem()) {
                ItemStack activeStack = player.getActiveItem();
                if (isToastGoblet(activeStack)
                        && player.getItemUseTime() >= activeStack.getMaxUseTime() - 2) {
                    judgesReact(world, sessionKey, FeastReaction.CELEBRATE, null);
                    world.playSound(null, player.getX(), player.getY(), player.getZ(),
                            net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                            net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.8f);
                    wake(player, SanityManager.DreamOutcome.SURVIVED_OBJECTIVE);
                    continue;
                }
            }

            // 1) На столе активное блюдо — ждём решения игрока
            UUID dishUuid = FEAST_DISH_ITEM.get(sessionKey);
            if (dishUuid != null) {
                var dish = world.getEntity(dishUuid);
                boolean tainted = FEAST_DISH_TAINTED.getOrDefault(sessionKey, false);
                if (dish == null || !dish.isAlive()) {
                    // Блюдо исчезло — игрок его подобрал ("отведал")
                    finishCourse(sessionKey, now);
                    eatDish(player, world, sessionKey, tainted);
                } else {
                    // Тронутое блюдо дымится зелёным — внимательный игрок заметит
                    if (tainted && now % 10 == 0) {
                        world.spawnParticles(net.minecraft.particle.ParticleTypes.WITCH,
                                dish.getX(), dish.getY() + 0.4, dish.getZ(),
                                3, 0.15, 0.1, 0.15, 0.01);
                    }
                    if (now >= FEAST_DISH_DEADLINE.getOrDefault(sessionKey, now)) {
                        // Игрок проигнорировал блюдо — судьи обиженно убирают его сами
                        dish.discard();
                        finishCourse(sessionKey, now);
                        SanityManager.get(player).addSanity(FEAST_IGNORED_SANITY);
                        player.sendMessage(net.minecraft.text.Text.literal(
                                "§7Судьи молча убирают нетронутое блюдо... Они обижены."), true);
                        judgesReact(world, sessionKey, FeastReaction.ANGRY, null);
                    }
                }
                continue;
            }

            // 2) Все блюда разрешены — подаём Кубок Тоста (финал сна)
            int done = FEAST_COURSES_DONE.getOrDefault(sessionKey, 0);
            if (done >= FEAST_COURSES) {
                if (!FEAST_TOAST_ITEM.containsKey(sessionKey)) {
                    spawnToastGoblet(world, sessionKey);
                } else {
                    UUID toastUuid = FEAST_TOAST_ITEM.get(sessionKey);
                    var toast = world.getEntity(toastUuid);
                    if (toast != null && now % 20 == 0) {
                        // Кубок манит золотыми частицами
                        world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                                toast.getX(), toast.getY() + 0.4, toast.getZ(),
                                2, 0.15, 0.1, 0.15, 0.01);
                    }
                    if (now % 200 == 0 && !hasToastGoblet(player)) {
                        player.sendMessage(net.minecraft.text.Text.literal(
                                "§7Кубок Тоста ждёт на столе — возьми его и выпей до дна."), true);
                    }
                }
                continue;
            }

            // 3) Подача следующего блюда
            if (now >= NEXT_DISH_TICK.getOrDefault(sessionKey, 0L)) {
                serveNextDish(world, sessionKey, now, done == 0);
            }
        }
    }

    /** Блюдо разрешено любым способом — счётчик курсов +1, следующая подача по расписанию. */
    private static void finishCourse(UUID sessionKey, long now) {
        FEAST_DISH_ITEM.remove(sessionKey);
        FEAST_DISH_TAINTED.remove(sessionKey);
        FEAST_COURSES_DONE.put(sessionKey, FEAST_COURSES_DONE.getOrDefault(sessionKey, 0) + 1);
        NEXT_DISH_TICK.put(sessionKey, now + FEAST_DISH_INTERVAL);
    }

    /** Ставит на центральную тарелку перед игроком очередное блюдо — свежее или тронутое. */
    private static void serveNextDish(ServerWorld world, UUID sessionKey, long now, boolean firstDish) {
        List<BlockPos> plates = FEAST_TABLES.get(sessionKey);
        if (plates == null || plates.isEmpty()) return;
        BlockPos plate = plates.get(plates.size() / 2); // место игрока — центр стола

        boolean tainted = RANDOM.nextDouble() < FEAST_TAINTED_CHANCE;
        net.minecraft.item.Item[] freshMenu = {
                net.minecraft.item.Items.GOLDEN_CARROT, net.minecraft.item.Items.COOKED_BEEF,
                net.minecraft.item.Items.BAKED_POTATO, net.minecraft.item.Items.BREAD,
                net.minecraft.item.Items.APPLE };
        net.minecraft.item.Item[] taintedMenu = {
                net.minecraft.item.Items.ROTTEN_FLESH, net.minecraft.item.Items.SPIDER_EYE,
                net.minecraft.item.Items.POISONOUS_POTATO };
        net.minecraft.item.Item dishItem = tainted
                ? taintedMenu[RANDOM.nextInt(taintedMenu.length)]
                : freshMenu[RANDOM.nextInt(freshMenu.length)];

        ItemStack stack = new ItemStack(dishItem);
        stack.setCustomName(net.minecraft.text.Text.literal("§6Блюдо пира"));
        ItemEntity dish = new ItemEntity(world,
                plate.getX() + 0.5, plate.getY() + 1.0, plate.getZ() + 0.5, stack);
        dish.setNeverDespawn();
        // ИСПРАВЛЕНО (фидбек "поджигаю блюдо, а судьи думают, что я его взял"): блюдо
        // нельзя подобрать с пола — пикап срабатывал раньше ПКМ и считался поеданием.
        // Теперь всё осознанно: ПКМ рукой = съесть, ПКМ зажигалкой = сжечь.
        dish.setPickupDelay(32767);
        dish.setGlowing(true);
        world.spawnEntity(dish);

        FEAST_DISH_ITEM.put(sessionKey, dish.getUuid());
        FEAST_DISH_TAINTED.put(sessionKey, tainted);
        FEAST_DISH_DEADLINE.put(sessionKey, now + FEAST_DISH_LIFETIME);

        world.playSound(null, plate, net.minecraft.sound.SoundEvents.ENTITY_ITEM_FRAME_PLACE,
                net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 0.5f);

        if (firstDish) {
            ServerPlayerEntity player = world.getServer().getPlayerManager()
                    .getPlayer(sessionKey);
            if (player != null) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "§6Первое блюдо. Свежее — отведай (ПКМ по блюду), тронутое (зелёный дым) — сожги Зажигалкой."),
                        true);
            }
        }
    }

    /** Кубок Тоста как предмет с NBT-меткой — по ней ловим реальное питьё и чистим инвентарь. */
    private static ItemStack createToastGobletStack() {
        ItemStack goblet = new ItemStack(net.minecraft.item.Items.HONEY_BOTTLE);
        goblet.getOrCreateNbt().putBoolean(FEAST_TOAST_TAG, true);
        goblet.setCustomName(net.minecraft.text.Text.literal("§6§lКубок Тоста"));
        return goblet;
    }

    /** Ставит на стол Кубок Тоста — финальный выход из сна (взять и выпить до дна). */
    private static void spawnToastGoblet(ServerWorld world, UUID sessionKey) {
        List<BlockPos> plates = FEAST_TABLES.get(sessionKey);
        if (plates == null || plates.isEmpty()) return;
        BlockPos plate = plates.get(plates.size() / 2);

        ItemEntity toast = new ItemEntity(world,
                plate.getX() + 0.5, plate.getY() + 1.0, plate.getZ() + 0.5, createToastGobletStack());
        toast.setNeverDespawn();
        toast.setGlowing(true);
        world.spawnEntity(toast);
        FEAST_TOAST_ITEM.put(sessionKey, toast.getUuid());

        world.playSound(null, plate, net.minecraft.sound.SoundEvents.BLOCK_BELL_USE,
                net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 0.6f);
        // Судьи торжественно поворачиваются к кубку
        judgesReact(world, sessionKey, FeastReaction.APPROVE, plate);

        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(sessionKey);
        if (player != null) {
            player.sendMessage(net.minecraft.text.Text.literal(
                    "§6Пир окончен. Возьми Кубок Тоста со стола — и выпей его до дна (зажми ПКМ), чтобы проснуться."),
                    true);
        }
    }

    /** Виды реакций судей пира на поступки игрока. */
    private enum FeastReaction { APPROVE, ANGRY, LAUGH, CELEBRATE }

    /**
     * ДОБАВЛЕНО (сон "Кровавый пир", редизайн): ПКМ по сущностям пира.
     * ПКМ по блюду с Зажигалкой судей в руке = сжечь блюдо (правильный ответ на тронутое);
     * ПКМ по блюду без зажигалки = съесть его (то же, что подобрать с пола);
     * ПКМ по Кубку Тоста = взять его в руки (дальше — просто выпить, зажав ПКМ).
     * Регистрация события — UseEntityCallback в SomniumMod#onInitialize.
     */
    public static net.minecraft.util.ActionResult onFeastEntityUse(ServerPlayerEntity player,
                                                                   net.minecraft.util.Hand hand,
                                                                   net.minecraft.entity.Entity entity) {
        ActiveDream active = ACTIVE.get(player.getUuid());
        if (active == null || !active.dreamId().equals(SomniumMod.id("crimson_feast"))) {
            return net.minecraft.util.ActionResult.PASS;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return net.minecraft.util.ActionResult.PASS;
        }
        UUID sessionKey = sessionKey(player.getUuid());
        long now = world.getServer().getTicks();

        // ПКМ по блюду: зажигалка в любой руке — сжечь; иначе — съесть
        UUID dishUuid = FEAST_DISH_ITEM.get(sessionKey);
        if (dishUuid != null && dishUuid.equals(entity.getUuid())) {
            boolean tainted = FEAST_DISH_TAINTED.getOrDefault(sessionKey, false);
            if (isFeastLighter(player.getStackInHand(hand)) || isFeastLighter(player.getMainHandStack())) {
                burnDish(player, world, sessionKey, entity, tainted, now);
            } else {
                entity.discard();
                finishCourse(sessionKey, now);
                eatDish(player, world, sessionKey, tainted);
            }
            return net.minecraft.util.ActionResult.SUCCESS;
        }

        // ПКМ по Кубку Тоста — взять в руки (питьё ловится в tickCrimsonFeast)
        UUID toastUuid = FEAST_TOAST_ITEM.get(sessionKey);
        if (toastUuid != null && toastUuid.equals(entity.getUuid())) {
            entity.discard();
            FEAST_TOAST_ITEM.remove(sessionKey);
            player.giveItemStack(createToastGobletStack());
            player.sendMessage(net.minecraft.text.Text.literal(
                    "§6Ты поднимаешь Кубок Тоста. Судьи замирают — выпей его (зажми ПКМ)."), true);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sound.SoundEvents.ITEM_BOTTLE_FILL,
                    net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.7f);
            return net.minecraft.util.ActionResult.SUCCESS;
        }

        return net.minecraft.util.ActionResult.PASS;
    }

    /** Игрок съел блюдо (подобрал или ПКМ рукой): свежее — награда, тронутое — яд и смех. */
    private static void eatDish(ServerPlayerEntity player, ServerWorld world, UUID sessionKey,
                                boolean tainted) {
        if (tainted) {
            SanityManager.get(player).addSanity(FEAST_EAT_TAINTED_SANITY);
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.POISON, 200, 0));
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.NAUSEA, 160, 0));
            player.sendMessage(net.minecraft.text.Text.literal(
                    "§2Блюдо было тронуто... Судьи хохочут."), true);
            judgesReact(world, sessionKey, FeastReaction.LAUGH, null);
        } else {
            SanityManager.get(player).addSanity(FEAST_EAT_FRESH_SANITY);
            player.sendMessage(net.minecraft.text.Text.literal(
                    "§6Свежее блюдо. Судьи одобрительно кивают."), true);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EAT,
                    net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.8f);
            judgesReact(world, sessionKey, FeastReaction.APPROVE, null);
        }
    }

    /** Игрок поджёг блюдо Зажигалкой судей: зрелищное горение на столе и реакция судей. */
    private static void burnDish(ServerPlayerEntity player, ServerWorld world, UUID sessionKey,
                                 net.minecraft.entity.Entity dish, boolean tainted, long now) {
        dish.setFireTicks(FEAST_BURN_TICKS + 20);
        FEAST_BURNING_DISHES.put(dish.getUuid(), now + FEAST_BURN_TICKS);
        finishCourse(sessionKey, now);
        BlockPos dishPos = dish.getBlockPos();
        world.playSound(null, dishPos, net.minecraft.sound.SoundEvents.ITEM_FLINTANDSTEEL_USE,
                net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
        world.playSound(null, dishPos, net.minecraft.sound.SoundEvents.BLOCK_FIRE_AMBIENT,
                net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 0.8f);
        if (tainted) {
            // Правильное решение: тронутое блюдо уничтожено огнём
            SanityManager.get(player).addSanity(FEAST_BURN_TAINTED_SANITY);
            player.sendMessage(net.minecraft.text.Text.literal(
                    "§6Тронутое блюдо сгорает в пламени. Судьи одобрительно склоняют головы."), true);
            judgesReact(world, sessionKey, FeastReaction.APPROVE, dishPos);
        } else {
            // Свежее блюдо сожжено — оскорбление кухни хозяина
            SanityManager.get(player).addSanity(FEAST_BURN_FRESH_SANITY);
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.HUNGER, 300, 0));
            player.sendMessage(net.minecraft.text.Text.literal(
                    "§7Ты сжёг свежее блюдо. Судьи недовольно шипят."), true);
            judgesReact(world, sessionKey, FeastReaction.ANGRY, dishPos);
        }
    }

    /** Реакция судей: звуки, частицы над каждым, взгляды на точку события (если задана). */
    private static void judgesReact(ServerWorld world, UUID sessionKey, FeastReaction reaction,
                                    BlockPos focusPos) {
        List<UUID> guests = FEAST_GUESTS.get(sessionKey);
        if (guests == null || guests.isEmpty()) return;
        long now = world.getServer().getTicks();
        for (UUID guestUuid : guests) {
            var guest = world.getEntity(guestUuid);
            if (guest == null) continue;
            switch (reaction) {
                case APPROVE -> {
                    world.playSound(null, guest.getX(), guest.getY(), guest.getZ(),
                            net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_YES,
                            net.minecraft.sound.SoundCategory.HOSTILE, 0.9f, 0.55f);
                    world.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
                            guest.getX(), guest.getY() + 2.2, guest.getZ(), 6, 0.2, 0.2, 0.2, 0.02);
                }
                case ANGRY -> {
                    world.playSound(null, guest.getX(), guest.getY(), guest.getZ(),
                            net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO,
                            net.minecraft.sound.SoundCategory.HOSTILE, 0.9f, 0.5f);
                    world.spawnParticles(net.minecraft.particle.ParticleTypes.ANGRY_VILLAGER,
                            guest.getX(), guest.getY() + 2.2, guest.getZ(), 4, 0.25, 0.2, 0.25, 0.02);
                }
                case LAUGH -> {
                    world.playSound(null, guest.getX(), guest.getY(), guest.getZ(),
                            net.minecraft.sound.SoundEvents.ENTITY_WITCH_CELEBRATE,
                            net.minecraft.sound.SoundCategory.HOSTILE, 0.9f, 0.5f);
                    world.spawnParticles(net.minecraft.particle.ParticleTypes.WITCH,
                            guest.getX(), guest.getY() + 2.0, guest.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
                }
                case CELEBRATE -> {
                    world.playSound(null, guest.getX(), guest.getY(), guest.getZ(),
                            net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_CELEBRATE,
                            net.minecraft.sound.SoundCategory.HOSTILE, 1.0f, 0.6f);
                    world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                            guest.getX(), guest.getY() + 2.2, guest.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
                }
            }
        }
        if (focusPos != null) {
            FEAST_JUDGE_FOCUS_POS.put(sessionKey, focusPos);
            FEAST_JUDGE_FOCUS_UNTIL.put(sessionKey, now + FEAST_JUDGE_FOCUS_TICKS);
        }
    }

    /** Горящие блюда: пламя и дым, потом блюдо исчезает с шипением и душой-искрой. */
    private static void tickBurningDishes(MinecraftServer server, long now) {
        if (FEAST_BURNING_DISHES.isEmpty()) return;
        for (var entry : new ArrayList<>(FEAST_BURNING_DISHES.entrySet())) {
            net.minecraft.entity.Entity dish = null;
            ServerWorld dishWorld = null;
            for (ServerWorld world : server.getWorlds()) {
                dish = world.getEntity(entry.getKey());
                if (dish != null) { dishWorld = world; break; }
            }
            if (dish == null) {
                FEAST_BURNING_DISHES.remove(entry.getKey());
                continue;
            }
            if (now % 3 == 0) {
                dishWorld.spawnParticles(net.minecraft.particle.ParticleTypes.FLAME,
                        dish.getX(), dish.getY() + 0.3, dish.getZ(), 4, 0.15, 0.15, 0.15, 0.02);
                dishWorld.spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE,
                        dish.getX(), dish.getY() + 0.6, dish.getZ(), 2, 0.1, 0.15, 0.1, 0.01);
            }
            if (now >= entry.getValue()) {
                dishWorld.playSound(null, dish.getX(), dish.getY(), dish.getZ(),
                        net.minecraft.sound.SoundEvents.BLOCK_FIRE_EXTINGUISH,
                        net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 1.2f);
                dishWorld.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL,
                        dish.getX(), dish.getY() + 0.3, dish.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
                dish.discard();
                FEAST_BURNING_DISHES.remove(entry.getKey());
            }
        }
    }

    /** Создаёт Зажигалку судей — сонный инструмент пира (неразрушима, NBT-метка). */
    private static ItemStack createFeastLighter() {
        ItemStack lighter = new ItemStack(net.minecraft.item.Items.FLINT_AND_STEEL);
        lighter.getOrCreateNbt().putBoolean(FEAST_LIGHTER_TAG, true);
        lighter.getOrCreateNbt().putBoolean("Unbreakable", true);
        lighter.setCustomName(net.minecraft.text.Text.literal("§6§lЗажигалка судей"));
        return lighter;
    }

    private static boolean isFeastLighter(ItemStack stack) {
        return stack.getItem() == net.minecraft.item.Items.FLINT_AND_STEEL
                && stack.getNbt() != null && stack.getNbt().getBoolean(FEAST_LIGHTER_TAG);
    }

    private static boolean hasFeastLighter(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (isFeastLighter(player.getInventory().getStack(i))) return true;
        }
        return false;
    }

    /** Выдаёт зажигалку и один раз объясняет, зачем она (onboarding механики). */
    private static void giveFeastLighter(ServerPlayerEntity player) {
        player.giveItemStack(createFeastLighter());
        if (FEAST_LIGHTER_GIVEN.add(player.getUuid())) {
            player.sendMessage(net.minecraft.text.Text.literal(
                    "§6Судьи вручают тебе Зажигалку. Тронутые блюда (дымятся зелёным) — сжигай ею: ПКМ по блюду."),
                    false);
        }
    }

    private static boolean isToastGoblet(ItemStack stack) {
        return stack.getItem() == net.minecraft.item.Items.HONEY_BOTTLE
                && stack.getNbt() != null && stack.getNbt().getBoolean(FEAST_TOAST_TAG);
    }

    private static boolean hasToastGoblet(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (isToastGoblet(player.getInventory().getStack(i))) return true;
        }
        return false;
    }

    /** Сонные предметы пира нельзя выносить в реальный мир — снимаем при пробуждении. */
    private static void removeFeastItems(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isFeastLighter(stack) || isToastGoblet(stack)) {
                inventory.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * ДОБАВЛЕНО (сон "Сон-в-сне"): копирует блоки 32 чанков (128x128) вокруг игрока
     */
    private static ChunkSnapshot captureChunk(ServerWorld world, BlockPos center) {
        Map<BlockPos, net.minecraft.block.BlockState> blocks = new HashMap<>();

        // Копируем область 128x128 блоков (32 чанка = 8x8 чанков) по горизонтали
        // От Y=center.Y-30 до Y=center.Y+30 по вертикали для большей реалистичности
        int halfSize = 64; // 128 / 2 = 64 блока в каждую сторону от центра
        int startX = center.getX() - halfSize;
        int startZ = center.getZ() - halfSize;
        int minY = Math.max(world.getBottomY(), center.getY() - 30);
        int maxY = center.getY() + 30; // Фиксированный диапазон относительно игрока

        SomniumMod.LOGGER.info("[Dream Within Dream] Копирование области 128x128, Y от {} до {}", minY, maxY);

        for (int x = startX; x < startX + 128; x++) {
            for (int z = startZ; z < startZ + 128; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    net.minecraft.block.BlockState state = world.getBlockState(pos);
                    if (!state.isAir()) {
                        blocks.put(new BlockPos(x - startX, y - minY, z - startZ), state);
                    }
                }
            }
        }

        SomniumMod.LOGGER.info("[Dream Within Dream] Скопировано {} блоков", blocks.size());

        return new ChunkSnapshot(blocks, new BlockPos(startX, minY, startZ));
    }

    /**
     * ДОБАВЛЕНО (сон "Сон-в-сне"): воссоздаёт скопированный чанк в измерении сна
     * ИЗМЕНЕНО: убраны барьеры - обрыв в пустоту естественный (просто нет блоков дальше)
     */
    private static BlockPos recreateChunkInDream(ServerWorld dreamWorld, ChunkSnapshot snapshot, BlockPos originalPos) {
        // Центрируем воссозданный чанк вокруг (0, 5, 0) в измерении сна
        BlockPos dreamOrigin = new BlockPos(0, 5, 0);

        SomniumMod.LOGGER.info("[Dream Within Dream] Воссоздание блоков в мире сна. Origin: {}, блоков: {}",
            dreamOrigin, snapshot.blocks.size());

        for (Map.Entry<BlockPos, net.minecraft.block.BlockState> entry : snapshot.blocks.entrySet()) {
            BlockPos relativePos = entry.getKey();
            BlockPos dreamPos = dreamOrigin.add(relativePos);
            dreamWorld.setBlockState(dreamPos, entry.getValue(), 3);
        }

        // Возвращаем позицию спавна игрока - точно на том же месте, где он был в реальном мире
        int relX = originalPos.getX() - snapshot.origin.getX();
        int relY = originalPos.getY() - snapshot.origin.getY();
        int relZ = originalPos.getZ() - snapshot.origin.getZ();

        BlockPos spawnPos = dreamOrigin.add(relX, relY, relZ);

        SomniumMod.LOGGER.info("[Dream Within Dream] Позиция спавна игрока: {} (оригинал: {}, относительно: {},{},{})",
            spawnPos, originalPos, relX, relY, relZ);

        // ИСПРАВЛЕНИЕ: проверяем есть ли блок под игроком, если нет - создаём страховочную платформу 3x3
        BlockPos blockBelow = spawnPos.down();
        if (dreamWorld.getBlockState(blockBelow).isAir()) {
            SomniumMod.LOGGER.warn("[Dream Within Dream] Под игроком пустота! Создаём страховочную платформу");

            // Создаём платформу 3x3 из того же материала, что и ближайший блок вокруг
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos platformPos = blockBelow.add(dx, 0, dz);
                    dreamWorld.setBlockState(platformPos, net.minecraft.block.Blocks.STONE.getDefaultState(), 3);
                }
            }
        }

        return spawnPos;
    }

    /**
     * ДОБАВЛЕНО (сон "Сон-в-сне"): тикает механику тревожных эффектов через 20 секунд
     * Через 20 секунд мир меняется: наступает ночь, начинается снег, красное небо, тревожная музыка
     */
    public static void tickDreamWithinDream(MinecraftServer server) {
        if (DREAM_WITHIN_DREAM_ORIGINAL_POS.isEmpty()) return;

        long now = server.getTicks();

        for (UUID playerId : new ArrayList<>(DREAM_WITHIN_DREAM_ORIGINAL_POS.keySet())) {
            ActiveDream active = ACTIVE.get(playerId);
            if (active == null || !active.dreamId().equals(SomniumMod.id("dream_within_dream"))) {
                DREAM_WITHIN_DREAM_ORIGINAL_POS.remove(playerId);
                DREAM_WITHIN_DREAM_CHUNKS.remove(playerId);
                DREAM_WITHIN_DREAM_SIGN_START.remove(playerId);
                DREAM_WITHIN_DREAM_ORIGINAL_WEATHER.remove(playerId);
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || !(player.getEntityWorld() instanceof ServerWorld dreamWorld)) {
                continue;
            }

            long elapsed = now - active.enterTick();

            // После 20 секунд (400 тиков) начинаем показывать эффекты кошмара
            if (elapsed >= 400) {
                if (!DREAM_WITHIN_DREAM_SIGN_START.containsKey(playerId)) {
                    DREAM_WITHIN_DREAM_SIGN_START.put(playerId, now);

                    // Меняем время на ночь (18000 = полночь)
                    long currentTime = dreamWorld.getTimeOfDay();
                    long dayPart = currentTime % 24000L;
                    long nightTime = currentTime - dayPart + 18000L;
                    dreamWorld.setTimeOfDay(nightTime);

                    // Включаем снегопад и грозу для красного неба
                    dreamWorld.setWeather(0, 999999, true, true);

                    // ИСПРАВЛЕНО: Используем playSound с координатами для клиента
                    dreamWorld.playSound(
                        null, // null = слышат все игроки рядом
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        com.somnium.mod.registry.ModSounds.DREAM_DREAD_AMBIENCE,
                        net.minecraft.sound.SoundCategory.AMBIENT,
                        2.0f, // громче
                        0.5f
                    );
                    dreamWorld.playSound(
                        null,
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        com.somnium.mod.registry.ModSounds.RUPTURE_STINGER,
                        net.minecraft.sound.SoundCategory.HOSTILE,
                        1.5f, // громче
                        0.5f
                    );

                    SomniumMod.LOGGER.info("[Dream Within Dream] Кошмар начался для игрока {} (время изменено на ночь, погода: снег+гроза)",
                        player.getName().getString());
                }

                // Показываем новое тревожное сообщение каждые 5 секунд (100 тиков)
                long timeSinceSigns = now - DREAM_WITHIN_DREAM_SIGN_START.get(playerId);
                if (timeSinceSigns % 100 == 0) {
                    showDisturbingMessage(player);
                }
            }
        }
    }

    /**
     * ДОБАВЛЕНО (сон "Сон-в-сне"): показывает тревожное красное сообщение на экране
     */
    private static void showDisturbingMessage(ServerPlayerEntity player) {
        String[] messages = {
            "§4§l§nПРЫГНИ",
            "§4§l§nЗАКОНЧИ СТРАДАНИЯ",
            "§4§l§nЭТО КОНЕЦ",
            "§4§l§nНЕТ ВЫХОДА",
            "§4§l§nОТПУСТИ",
            "§4§l§nУМРИ",
            "§4§l§nСПРЫГНИ ВНИЗ",
            "§4§l§nОСВОБОДИСЬ",
            "§4§l§nПАДАЙ",
            "§4§l§nПРОВАЛИСЬ В БЕЗДНУ"
        };
        String message = messages[RANDOM.nextInt(messages.length)];

        // Показываем в чате
        player.sendMessage(
            net.minecraft.text.Text.literal(message),
            false
        );

        // Показываем в action bar (над хотбаром)
        player.sendMessage(
            net.minecraft.text.Text.literal(message),
            true
        );

        // Звук тревоги (правильная сигнатура для ServerPlayerEntity)
        player.playSound(
            net.minecraft.sound.SoundEvents.ENTITY_WITHER_AMBIENT,
            1.0f,
            0.5f
        );
    }
}
