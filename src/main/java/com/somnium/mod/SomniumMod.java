package com.somnium.mod;

import com.somnium.mod.command.TestDreamCommand;
import com.somnium.mod.command.SanityCommand;
import com.somnium.mod.dream.DreamRegistry;
import com.somnium.mod.event.BleedThroughManager;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModItems;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.sanity.SanityManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа мода Somnium.
 *
 * Порядок инициализации важен:
 *  1. Регистрация сущностей / предметов / звуков (регистры должны быть готовы раньше всего)
 *  2. Регистрация реестра типов снов (DreamRegistry) — грузит data-driven JSON из data/somnium/dream
 *  3. Регистрация сетевых пакетов (синхронизация рассудка на клиент)
 *  4. Подписка на игровые события (сон, тик сервера, смерть/пробуждение)
 */
public final class SomniumMod implements ModInitializer {

    public static final String MOD_ID = "somnium";
    public static final Logger LOGGER = LoggerFactory.getLogger("Somnium");

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("[Somnium] Границы между сном и явью начинают истончаться...");

        // 1. Базовые регистры
        ModEntities.register();
        ModEntities.registerAttributes();
        ModItems.register();
        com.somnium.mod.registry.ModBlocks.register(); // руда/блок сноведений, портал

        // ДОБАВЛЕНО ("руда как у меди"): жилы руды сноведений в обычном мире
        net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(
                net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInOverworld(),
                net.minecraft.world.gen.GenerationStep.Feature.UNDERGROUND_ORES,
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.PLACED_FEATURE,
                        id("dream_ore")));
        // ИСПРАВЛЕНО: раньше предметы регистрировались, но никогда не попадали ни в одну
        // вкладку творческого режима — вызов ниже добавлен, чтобы они стали видны игроку.
        com.somnium.mod.registry.ModItemGroups.register();
        ModSounds.register();

        // ДОБАВЛЕНО: регистрация критериев достижений
        com.somnium.mod.advancement.ModCriteria.register();

        // 2. Реестр снов (data-driven, см. data/somnium/dream/*.json)
        DreamRegistry.bootstrap();

        // 3. Сетевые пакеты (рассудок -> клиент, для HUD)
        // В 1.20.1 не требуют регистрации через PayloadTypeRegistry

        // 4. Обработчики событий (перехват сна теперь реализован миксином PlayerEntityMixin —
        // см. src/main/java/com/somnium/mod/mixin/PlayerEntityMixin.java, регистрации в коде не требует)
        BleedThroughManager.register();

        // 5. Тик рассудка — раз в секунду (каждые 20 тиков) на каждого игрока
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0) return;
            for (var player : server.getPlayerManager().getPlayerList()) {
                SanityManager.tick(player);
                // Обновление постоянного HUD цели сна (полоса оставшегося времени) — раз в секунду,
                // этого достаточно для плавного отображения, не нагружая сеть каждым тиком.
                com.somnium.mod.dream.DreamManager.sendHudUpdateIfDreaming(player, server);
            }
        });

        // 6. Проверка таймаута активных снов (пробуждение по истечении времени сна) — каждый тик,
        // операция дешёвая (проход по карте активных снов, обычно пустой или очень маленькой).
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickActiveDreams);

        // 7. Проверка игроков, ожидающих перехода в сон (легли в кровать SLEEP_TRANSITION_TICKS
        // тиков назад) — переносит их в измерение сна уже на затемнённом экране.
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickPendingSleepTransitions);

        // 8. Очистка устаревших записей об окне неуязвимости при приземлении после
        // телепортации в сон/из сна (см. DreamManager#grantLandingImmunity).
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickLandingImmunity);

        // 9. ДОБАВЛЕНО ("добавь тревожную музыку", "никакого шёпота нету"): периодическая
        // фоновая атмосфера снов + "шёпот за спиной" в Лесу теней — раньше сны были полностью
        // немыми, кроме разовых тайтлов.
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickDreamAmbience);

        // 10. ДОБАВЛЕНО (сон "Падающие доски"): проверка таймеров удаления досок платформы
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickFallingPlanks);

        // 11. ДОБАВЛЕНО (сон "Падающие доски"): детектор падения игрока в пустоту = пробуждение
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::checkFallingPlanksVoidFall);

        // 12. ДОБАВЛЕНО (сон "Зеркальная комната"): проверка таймера разбивания стекла
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickMirrorRoomGlass);

        // 13. ДОБАВЛЕНО (сон "Тонущий город"): постепенный подъём уровня воды
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickDrowningCityWater);

        // 14. ДОБАВЛЕНО (сон "Сон-в-сне"): таблички за спиной игрока
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickDreamWithinDream);

        // 15. ДОБАВЛЕНО (сон "Лес теней"): ведьмины огни к Дереву-Маяку + проверка "не обернулся"
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickShadowForest);

        // 16. ДОБАВЛЕНО (сон "Обрушающаяся шахта"): проходы открываются/зарастают за спиной
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickCollapsingMine);

        // 17. ДОБАВЛЕНО (сон "Кровавый пир"): подача блюд, порча блюд, голодание/насыщение
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickCrimsonFeast);

        // 17.1 ДОБАВЛЕНО (сон "Пустошь зеркал", редизайн "Поймай своё отражение"):
        // убегающее зеркало (3 касания до выхода) и Двойник, идущий по следу игрока
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickMirrorWastes);

        // 17.2 ДОБАВЛЕНО (сон "Пустота с глазами"): глаза открываются во тьме,
        // сердцебиение, Наблюдатели сжимают кольцо — "движ" с первых секунд
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickVoidOfEyes);

        // 17.3 ДОБАВЛЕНО ("портал в мир снов"): стояние в портале 4 сек = телепорт
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickDreamPortal);
        // 17.4 Долгие подсказки в экшенбаре (держатся 8-9 секунд)
        ServerTickEvents.END_SERVER_TICK.register(com.somnium.mod.dream.DreamManager::tickHints);

        // 17.4 ДОБАВЛЕНО ("портал в мир снов"): поджог рамки из блоков сноведений
        // зажигалкой — как рамка из обсидиана для ада. Ставим наш обработчик так, чтобы
        // он срабатывал до ванильной постановки огня.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                return net.minecraft.util.ActionResult.PASS;
            }
            if (player.getStackInHand(hand).getItem() != net.minecraft.item.Items.FLINT_AND_STEEL) {
                return net.minecraft.util.ActionResult.PASS;
            }
            net.minecraft.util.math.BlockPos firePos = hitResult.getBlockPos().offset(hitResult.getSide());
            if (com.somnium.mod.dream.DreamPortalHelper.tryIgnite(serverWorld, firePos)) {
                return net.minecraft.util.ActionResult.SUCCESS;
            }
            return net.minecraft.util.ActionResult.PASS;
        });

        // 18. ДОБАВЛЕНО (команда для тестирования): /testdream для быстрой телепортации в измерения снов
        CommandRegistrationCallback.EVENT.register(TestDreamCommand::register);
        // 19. ДОБАВЛЕНО ("добавь команды, чтобы влиять на шкалу рассудка"): /sanity get|set|add
        CommandRegistrationCallback.EVENT.register(SanityCommand::register);

        // 20. ДОБАВЛЕНО (мультиплеер, защита кровати): запрещаем разрушать кровати спящих игроков
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (state.getBlock() instanceof net.minecraft.block.BedBlock) {
                if (com.somnium.mod.dream.DreamManager.isBedProtected(pos)) {
                    player.sendMessage(net.minecraft.text.Text.literal("§cЭту кровать нельзя сломать — в ней кто-то спит!"), true);
                    return false; // отменяем разрушение
                }
            }
            return true; // разрешаем разрушение
        });

        // 21. ДОБАВЛЕНО (сон "Кровавый пир", поджог без пиксель-хантинга): зажигалка в одной
        // руке + блюдо в другой (или брошенное блюдо рядом) + ПКМ куда угодно = сжечь блюдо.
        // Регистрируем и UseBlock (ПКМ по блоку), и UseItem (ПКМ в воздух) — ловим оба случая.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                return com.somnium.mod.dream.DreamManager.onFeastLighterUse(serverPlayer);
            }
            return net.minecraft.util.ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                var result = com.somnium.mod.dream.DreamManager.onFeastLighterUse(serverPlayer);
                if (result == net.minecraft.util.ActionResult.SUCCESS) {
                    return net.minecraft.util.TypedActionResult.success(player.getStackInHand(hand));
                }
            }
            return net.minecraft.util.TypedActionResult.pass(player.getStackInHand(hand));
        });

        // 22. ДОБАВЛЕНО (мультиплеер, колокол): удар в колокол будит спящих игроков в радиусе 5 блоков
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                return net.minecraft.util.ActionResult.PASS;
            }

            net.minecraft.util.math.BlockPos pos = hitResult.getBlockPos();
            net.minecraft.block.BlockState state = world.getBlockState(pos);

            if (state.getBlock() instanceof net.minecraft.block.BellBlock) {
                // ИСПРАВЛЕНО (конфликт с выходом из "Тонущего города"): колокол собора
                // в измерении сна — это ЦЕЛЬ сна (выход с исходом SURVIVED_OBJECTIVE через
                // близость, см. tickDrowningCityWater), а не "будильник" для спящих снаружи.
                // Без этого исключения клик по колоколу будил бы самого кликнувшего с
                // худшим исходом WOKE_EARLY (он находится в радиусе 5 блоков и сам спит).
                if (com.somnium.mod.dimension.ModDimensions.isDreamDimension(serverWorld.getRegistryKey())) {
                    return net.minecraft.util.ActionResult.PASS;
                }
                // Звонящий сам во сне — разрешаем (в измерениях снов колоколов нет, но комментируем)
                // Если бы колокола были в снах, можно было бы добавить проверку:
                // if (com.somnium.mod.dream.DreamManager.isDreaming(player.getUuid())) return ActionResult.PASS;

                // Ищем спящих игроков в радиусе 5 блоков
                for (net.minecraft.server.network.ServerPlayerEntity nearbyPlayer : serverWorld.getPlayers()) {
                    if (nearbyPlayer.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 25.0) { // 5^2 = 25
                        if (com.somnium.mod.dream.DreamManager.isDreaming(nearbyPlayer.getUuid())) {
                            com.somnium.mod.dream.DreamManager.wake(nearbyPlayer,
                                com.somnium.mod.sanity.SanityManager.DreamOutcome.WOKE_EARLY);
                            nearbyPlayer.sendMessage(net.minecraft.text.Text.literal("§eЗвон колокола разбудил вас!"), false);
                        }
                    }
                }
            }

            return net.minecraft.util.ActionResult.PASS;
        });

        // 22. ДОБАВЛЕНО (сон "Кровавый пир", редизайн "Последний ужин"): ПКМ по блюду =
        // отодвинуть, ПКМ по Кубку Тоста = поднять и выпить (выход из сна)
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                return com.somnium.mod.dream.DreamManager.onFeastEntityUse(serverPlayer, hand, entity);
            }
            return net.minecraft.util.ActionResult.PASS;
        });

        // 24. ДОБАВЛЕНО (выход из сна "Сон-в-сне"): чтобы проснуться из сна-в-сне, нужно
        // УСНУТЬ В НЁМ — ПКМ по своей кровати в скопированном мире. Во ВСЕХ измерениях снов
        // bed_works=false (см. data/somnium/dimension_type), поэтому без этого перехвата
        // кровать просто ВЗОРВАЛАСЬ бы, как в Незере. Перехватываем клик раньше ванили.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return net.minecraft.util.ActionResult.PASS;
            }
            if (!(world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof net.minecraft.block.BedBlock)) {
                return net.minecraft.util.ActionResult.PASS;
            }
            if (!com.somnium.mod.dimension.ModDimensions.isDreamDimension(world.getRegistryKey())) {
                return net.minecraft.util.ActionResult.PASS;
            }
            // Измерение сна: ванильную обработку клика глушим всегда (иначе взрыв)
            if (world.getRegistryKey().equals(com.somnium.mod.dimension.ModDimensions.DREAM_WITHIN_DREAM)
                    && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                    && com.somnium.mod.dream.DreamManager.isDreaming(serverPlayer.getUuid())) {
                serverPlayer.playSound(com.somnium.mod.registry.ModSounds.DREAM_WAKE, 1.0f, 1.0f);
                com.somnium.mod.dream.DreamManager.wake(serverPlayer,
                    com.somnium.mod.sanity.SanityManager.DreamOutcome.SURVIVED_OBJECTIVE);
                return net.minecraft.util.ActionResult.SUCCESS;
            }
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer2
                    && com.somnium.mod.dream.DreamManager.isDreaming(serverPlayer2.getUuid())) {
                com.somnium.mod.dream.DreamManager.showHint(serverPlayer2, "§7Кровати во снах не работают...", 5);
            }
            return net.minecraft.util.ActionResult.SUCCESS;
        });

        LOGGER.info("[Somnium] Инициализация завершена. Сладких снов.");
    }
}
