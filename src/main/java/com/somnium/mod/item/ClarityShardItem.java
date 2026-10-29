package com.somnium.mod.item;

import com.somnium.mod.sanity.SanityData;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Осколок Ясности: ПКМ — +20 рассудка (перезарядка 60 с). Раньше предмет крафтился,
 * но ничего не делал (хотя описание обещало восстановление рассудка).
 */
public class ClarityShardItem extends Item {

    private static final float SANITY_RESTORE = 20.0f;
    private static final int COOLDOWN_TICKS = 1200;

    public ClarityShardItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!(user instanceof ServerPlayerEntity player)) return TypedActionResult.success(stack, true);
        if (SanityManager.get(player).getSanity() >= SanityData.MAX_SANITY) {
            player.sendMessage(Text.translatable("somnium.item.clarity_shard.full").formatted(Formatting.GRAY), true);
            return TypedActionResult.fail(stack);
        }
        SanityManager.add(player, SANITY_RESTORE);
        player.getItemCooldownManager().set(this, COOLDOWN_TICKS);
        player.getServerWorld().spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(),
                20, 0.4, 0.6, 0.4, 0.03);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        player.sendMessage(Text.translatable("somnium.item.clarity_shard.used").formatted(Formatting.AQUA), true);
        if (!player.getAbilities().creativeMode) stack.decrement(1);
        return TypedActionResult.consume(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.somnium.clarity_shard.tooltip").formatted(Formatting.AQUA));
    }
}
