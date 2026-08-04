package com.somnium.mod.network;

import com.somnium.mod.SomniumMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Синхронизирует текущее значение рассудка на клиент для отрисовки HUD.
 */
public class SanitySyncPayload {
    public static final Identifier ID = new Identifier(SomniumMod.MOD_ID, "sanity_sync");

    public static void sendTo(ServerPlayerEntity player, float sanity) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeFloat(sanity);
        ServerPlayNetworking.send(player, ID, buf);
    }
}
