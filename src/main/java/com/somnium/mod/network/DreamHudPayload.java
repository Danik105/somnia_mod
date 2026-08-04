package com.somnium.mod.network;

import com.somnium.mod.SomniumMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * ИСПРАВЛЕНИЕ ("задание сна непонятно и быстро пропадает"): раньше цель сна показывалась
 * только один раз через ванильный TitleS2CPacket/SubtitleS2CPacket в DreamManager#sendDreamTitle —
 * у него ЖЁСТКО заданное время жизни (fade-in 10 + stay 60 + fade-out 20 = 90 тиков, ~4.5 секунды),
 * после чего текст пропадает НАВСЕГДА, даже если игрок не успел прочитать. Этот пакет решает задачу
 * иначе: клиент запоминает последнее полученное состояние и рисует ПОСТОЯННЫЙ HUD (см.
 * DreamObjectiveHudRenderer), который висит на экране всё время, пока сон активен — заголовок,
 * цель и полоса оставшегося времени сна, а не мгновенно исчезающий тайтл.
 */
public class DreamHudPayload {
    public static final Identifier ID = new Identifier(SomniumMod.MOD_ID, "dream_hud");

    public static void sendActive(ServerPlayerEntity player, String dreamNameKey, String objectiveText, int remainingTicks, int totalTicks) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(true);
        buf.writeString(dreamNameKey);
        buf.writeString(objectiveText);
        buf.writeVarInt(remainingTicks);
        buf.writeVarInt(totalTicks);
        ServerPlayNetworking.send(player, ID, buf);
    }

    public static void sendInactive(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(false);
        buf.writeString("");
        buf.writeString("");
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        ServerPlayNetworking.send(player, ID, buf);
    }
}
