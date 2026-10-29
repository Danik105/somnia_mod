package com.somnium.mod.client;

import com.somnium.mod.entity.SkinOwner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

import java.util.Optional;
import java.util.UUID;

/** Скин игрока-владельца для спящих тел и отражений (дефолтный Стив/Алекс, если игрок неизвестен). */
final class SkinTextures {

    private SkinTextures() {}

    static Identifier of(SkinOwner entity) {
        Optional<UUID> owner = entity.getSkinOwner();
        if (owner.isEmpty()) return DefaultSkinHelper.getTexture();
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        PlayerListEntry entry = handler != null ? handler.getPlayerListEntry(owner.get()) : null;
        return entry != null ? entry.getSkinTexture() : DefaultSkinHelper.getTexture(owner.get());
    }
}
