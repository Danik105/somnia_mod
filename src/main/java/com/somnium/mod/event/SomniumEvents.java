package com.somnium.mod.event;

import com.somnium.mod.command.SomniumCommands;
import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamPortalHelper;
import com.somnium.mod.dream.DreamRegistry;
import com.somnium.mod.dream.DreamSlots;
import com.somnium.mod.player.SomniumPlayerState;
import com.somnium.mod.portal.DreamPortalManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Все подписки мода на события Fabric — в одном месте. */
public final class SomniumEvents {

    private SomniumEvents() {}

    public static void register() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new DreamRegistry.Loader());
        CommandRegistrationCallback.EVENT.register(SomniumCommands::register);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DreamManager.tick(server);
            DreamPortalManager.tick(server);
            com.somnium.mod.haunt.Horror.tick(server);
            com.somnium.mod.haunt.SleepParalysis.tick(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(DreamSlots::resumeCleanup);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DreamManager.onServerStopping();
            com.somnium.mod.haunt.Horror.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> DreamSlots.onServerStopped());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> DreamManager.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DreamManager.onPlayerDisconnect(handler.player);
            DreamPortalManager.onDisconnect(handler.player.getUuid());
            BleedThroughManager.onDisconnect(handler.player.getUuid());
            com.somnium.mod.haunt.HauntManager.onDisconnect(handler.player.getUuid());
            com.somnium.mod.haunt.SleepParalysis.onDisconnect(handler.player.getUuid());
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) ->
                SomniumPlayerState.of(newPlayer).copyFrom(SomniumPlayerState.of(oldPlayer)));
        ServerEntityEvents.ENTITY_LOAD.register(DreamManager::onEntityLoad);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> com.somnium.mod.haunt.Horror.onEntityLoad(entity));

        registerInteractions();
        registerWorldProtection();
        com.somnium.mod.SomniumMod.LOGGER.info("[Somnium] Страшных событий в мире: {}", com.somnium.mod.haunt.WorldEvents.count());
    }

    private static void registerInteractions() {
        // Порядок важен: первый обработчик, вернувший не PASS, побеждает.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
                return ActionResult.PASS;
            }
            // Сон: сценарий (зажигалка пира, кровать Сна-в-сне) и кровати в снах
            ActionResult dream = DreamManager.onUseBlock(serverPlayer, hand, hit);
            if (dream != ActionResult.PASS) return dream;

            // Поджог рамки портала зажигалкой
            if (player.getStackInHand(hand).isOf(Items.FLINT_AND_STEEL)
                    && !ModDimensions.isSessionDimension(world.getRegistryKey())
                    && DreamPortalHelper.tryIgnite(serverWorld, hit.getBlockPos().offset(hit.getSide()))) {
                player.getStackInHand(hand).damage(1, player, p -> p.sendToolBreakStatus(hand));
                return ActionResult.SUCCESS;
            }

            // Обычный колокол будит спящих, чья кровать рядом
            if (world.getBlockState(hit.getBlockPos()).getBlock() instanceof BellBlock
                    && !ModDimensions.isSessionDimension(world.getRegistryKey())) {
                DreamManager.onRealBellRung(serverWorld, hit.getBlockPos());
            }
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity serverPlayer
                    && DreamManager.onUseItem(serverPlayer, hand) == ActionResult.SUCCESS) {
                return TypedActionResult.success(player.getStackInHand(hand));
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                return DreamManager.onUseEntity(serverPlayer, hand, entity);
            }
            return ActionResult.PASS;
        });
    }

    /** Во снах нельзя ломать блоки и разводить огонь (кроме творческого режима); кровати спящих защищены. */
    private static void registerWorldProtection() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                isProtectedDream(player, world) ? ActionResult.FAIL : ActionResult.PASS);

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (isProtectedDream(player, world)) return false;
            if (state.getBlock() instanceof BedBlock && world instanceof ServerWorld serverWorld
                    && DreamManager.isBedProtected(serverWorld, pos)) {
                player.sendMessage(Text.translatable("somnium.dream.bed_protected").formatted(Formatting.RED), true);
                return false;
            }
            return true;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || !isProtectedDream(player, world)) return ActionResult.PASS;
            var stack = player.getStackInHand(hand);
            if (stack.isOf(Items.FLINT_AND_STEEL) || stack.isOf(Items.FIRE_CHARGE)
                    || stack.isOf(Items.LAVA_BUCKET) || stack.isOf(Items.WATER_BUCKET)
                    || stack.getItem() instanceof net.minecraft.item.BlockItem) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    private static boolean isProtectedDream(PlayerEntity player, World world) {
        return !player.isCreative() && !player.isSpectator()
                && ModDimensions.isSessionDimension(world.getRegistryKey());
    }
}
