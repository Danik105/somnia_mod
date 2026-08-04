package com.somnium.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.somnium.mod.SomniumMod;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamRegistry;
import com.somnium.mod.dream.DreamType;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Команда /testdream для быстрого запуска полноценного сна (с заданием, таймером, мобами).
 * Использование: /testdream <dream_name>
 *
 * Доступные сны:
 * - drowning_city
 * - shadow_forest
 * - mirror_wastes
 * - collapsing_mine
 * - crimson_feast
 * - void_of_eyes
 * - dream_within_dream
 * - falling_planks
 */
public class TestDreamCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("testdream")
                .then(CommandManager.literal("drowning_city")
                        .executes(ctx -> startDream(ctx, "drowning_city")))
                .then(CommandManager.literal("shadow_forest")
                        .executes(ctx -> startDream(ctx, "shadow_forest")))
                .then(CommandManager.literal("mirror_wastes")
                        .executes(ctx -> startDream(ctx, "mirror_wastes")))
                .then(CommandManager.literal("collapsing_mine")
                        .executes(ctx -> startDream(ctx, "collapsing_mine")))
                .then(CommandManager.literal("crimson_feast")
                        .executes(ctx -> startDream(ctx, "crimson_feast")))
                .then(CommandManager.literal("void_of_eyes")
                        .executes(ctx -> startDream(ctx, "void_of_eyes")))
                .then(CommandManager.literal("dream_within_dream")
                        .executes(ctx -> startDream(ctx, "dream_within_dream")))
                .then(CommandManager.literal("falling_planks")
                        .executes(ctx -> startDream(ctx, "falling_planks")))
                .then(CommandManager.literal("mirror_room")
                        .executes(ctx -> startDream(ctx, "mirror_room")))
        );
    }

    private static int startDream(CommandContext<ServerCommandSource> ctx, String dreamName) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Эту команду может использовать только игрок"));
            return 0;
        }

        Identifier dreamId = SomniumMod.id(dreamName);
        DreamType dreamType = DreamRegistry.get(dreamId);

        if (dreamType == null) {
            source.sendError(Text.literal("Неизвестный сон: " + dreamName));
            return 0;
        }

        // Запускаем полноценный сон через DreamManager (как при обычном засыпании)
        // Это включает: телепортацию, спавн мобов, установку цели, таймер, эффекты и т.д.
        DreamManager.enterDreamDirectly(player, dreamType);

        source.sendFeedback(() -> Text.literal("§aЗапуск сна: §f" + dreamName), false);
        return 1;
    }
}
