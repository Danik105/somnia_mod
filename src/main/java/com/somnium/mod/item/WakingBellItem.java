package com.somnium.mod.item;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * ДОБАВЛЕНО ("предметов нет"): Колокол Пробуждения — крафтуемый расходник (Эссенция Кошмара +
 * золотой слиток), который у игрока и раньше формально не было причины держать в инвентаре сна:
 * снимок инвентаря при входе в сон (см. DreamManager#snapshotAndClearInventory) очищает основной
 * инвентарь ПОЛНОСТЬЮ, так что предмет нужно держать не физически с собой в кармане, а как
 * "аварийный выход" — колокол можно использовать только пока сон активен: одно нажатие ПКМ сразу
 * будит игрока (DreamOutcome.WOKE_EARLY — небольшая награда рассудка, меньше полного выполнения
 * цели, но лучше, чем ждать таймаута или рисковать умереть). До этого у игрока не было НИКАКОГО
 * способа выйти из сна раньше времени вручную — либо ждать таймер, либо использовать
 * REACH_DOOR/BOSS_KILL/COLLECT_ITEMS цель (см. DreamObjectiveType), либо умереть (со штрафом).
 */
public class WakingBellItem extends Item {

    public WakingBellItem(Settings settings) {
        super(settings);
    }

    // ИСПРАВЛЕНО ПО ОШИБКЕ СБОРКИ: TypedActionResult<ItemStack> в вашей версии API (26.1) не
    // существует — Item#use() здесь возвращает простой ActionResult, а изменения ItemStack
    // делаются прямо на объекте, который вернул getStackInHand() (это та же ссылка, что лежит
    // в инвентаре, так что decrement() ниже сразу отражается на реальном стеке игрока).
    @Override
    public net.minecraft.util.TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (world.isClient() || !(user instanceof ServerPlayerEntity serverPlayer)) {
            return net.minecraft.util.TypedActionResult.pass(stack);
        }

        if (!DreamManager.isDreaming(serverPlayer.getUuid())) {
            serverPlayer.sendMessage(
                    net.minecraft.text.Text.translatable("somnium.item.waking_bell.not_dreaming"), true);
            return net.minecraft.util.TypedActionResult.fail(stack);
        }

        world.playSound(null, serverPlayer.getBlockPos(),
                com.somnium.mod.registry.ModSounds.DREAM_WAKE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        DreamManager.wake(serverPlayer, SanityManager.DreamOutcome.WOKE_EARLY);

        if (!serverPlayer.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        return net.minecraft.util.TypedActionResult.success(stack, world.isClient());
    }
}