package com.somnium.mod.client;

import com.somnium.mod.network.DreamHudPayload;
import com.somnium.mod.network.SanitySyncPayload;
import com.somnium.mod.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * Клиентская часть мода:
 *  - принимает SanitySyncPayload и хранит последнее значение рассудка
 *  - рисует HUD-индикатор рассудка (простая полоса/иконка глаза, "плывущая" при низком рассудке)
 *  - регистрирует рендереры сущностей-кошмаров (пока временная заглушка, см. NightmarePlaceholderRenderer)
 */
public final class SomniumClient implements ClientModInitializer {

    private static volatile float clientSanity = 100.0f;

    /**
     * ИСПРАВЛЕНИЕ ("задание сна непонятно и быстро пропадает"): последнее полученное
     * состояние цели сна — рисуется постоянно, пока active=true, а не гаснет само по себе
     * как старый TitleS2CPacket/SubtitleS2CPacket (см. DreamHudPayload).
     */
    private static volatile DreamObjectiveHudRenderer.DreamHudState dreamHudState = DreamObjectiveHudRenderer.DreamHudState.INACTIVE;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SanitySyncPayload.ID, (client, handler, buf, responseSender) -> {
            float sanity = buf.readFloat();
            client.execute(() -> {
                clientSanity = sanity;
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(DreamHudPayload.ID, (client, handler, buf, responseSender) -> {
            boolean active = buf.readBoolean();
            String dreamNameKey = buf.readString();
            String objectiveText = buf.readString();
            int remainingTicks = buf.readVarInt();
            int totalTicks = buf.readVarInt();

            client.execute(() -> {
                dreamHudState = active
                        ? new DreamObjectiveHudRenderer.DreamHudState(true, dreamNameKey, objectiveText, remainingTicks, totalTicks)
                        : DreamObjectiveHudRenderer.DreamHudState.INACTIVE;
            });
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            SanityHudRenderer.render(drawContext, clientSanity, dreamHudState.active());
            DreamObjectiveHudRenderer.render(drawContext, dreamHudState);
        });

        // Регистрация рендереров сущностей (API 1.20.1)
        EntityRendererRegistry.register(ModEntities.DROWNED_WRETCH, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.LURKING_SHADE, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.MIRRORED_DOUBLE, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.SCREAMING_MINER, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.BLIND_BURROWER, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.FERAL_VILLAGER, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.FLESH_GOLEM, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.WATCHER, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.NIGHTMARE_AMALGAM, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.PHANTOM_EEL, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.STALKER, NightmarePlaceholderRenderer::new);
        EntityRendererRegistry.register(ModEntities.MIRROR_REFLECTION, MirrorReflectionRenderer::new);
    }

    public static float getClientSanity() {
        return clientSanity;
    }
}
