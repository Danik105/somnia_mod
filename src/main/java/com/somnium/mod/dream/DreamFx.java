package com.somnium.mod.dream;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/** Адресные эффекты для одного игрока: звуки, тайтлы, "личное" небо. */
public final class DreamFx {

    private DreamFx() {}

    /** Звук в точке, который слышит только этот игрок (шёпот за спиной и т.п.). */
    public static void soundTo(ServerPlayerEntity player, SoundEvent sound, SoundCategory category,
                               double x, double y, double z, float volume, float pitch) {
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(sound),
                category, x, y, z, volume, pitch, player.getRandom().nextLong()));
    }

    public static void title(ServerPlayerEntity player, Text title, @Nullable Text subtitle,
                             int fadeIn, int stay, int fadeOut) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
        if (subtitle != null) player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    /**
     * Время суток только для этого игрока. Измерения снов делят время и погоду с основным
     * миром (их свойства только для чтения), поэтому setTimeOfDay/setWeather там не работают —
     * вместо этого отправляем клиенту собственное время с выключенным циклом дня.
     */
    public static void sendTime(ServerPlayerEntity player, long timeOfDay) {
        player.networkHandler.sendPacket(new WorldTimeUpdateS2CPacket(
                player.getWorld().getTime(), timeOfDay, false));
    }

    /** Дождь/гроза только для этого игрока (сбрасывается при смене измерения). */
    public static void sendWeather(ServerPlayerEntity player, float rain, float thunder) {
        player.networkHandler.sendPacket(new GameStateChangeS2CPacket(
                rain > 0 ? GameStateChangeS2CPacket.RAIN_STARTED : GameStateChangeS2CPacket.RAIN_STOPPED, 0.0f));
        player.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, rain));
        player.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, thunder));
    }

    /** Эффект без частиц, но с иконкой. */
    public static void effect(ServerPlayerEntity player, StatusEffect effect, int duration, int amplifier) {
        player.addStatusEffect(new StatusEffectInstance(effect, duration, amplifier, false, false, true));
    }
}
