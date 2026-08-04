package com.somnium.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.somnium.mod.network.SanitySyncPayload;
import com.somnium.mod.sanity.SanityData;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * ДОБАВЛЕНО ("добавь команды, чтобы влиять на шкалу рассудка"): команда /sanity
 * для отладки и тестирования системы рассудка.
 *
 * Использование:
 *  /sanity get [игрок]        — показать текущее значение
 *  /sanity set <0-100> [игрок] — установить значение
 *  /sanity add <дельта> [игрок] — прибавить/отнять (отрицательное число)
 *
 * Если игрок не указан — применяется к тому, кто ввёл команду.
 * После изменения значение сразу синхронизируется на клиент (HUD обновляется мгновенно,
 * не дожидаясь ближайшего секундного тика SanityManager).
 */
public class SanityCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("sanity")
                .then(CommandManager.literal("get")
                        .executes(ctx -> getSanity(ctx, null))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> getSanity(ctx, EntityArgumentType.getPlayer(ctx, "player")))))
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("value", FloatArgumentType.floatArg(0.0f, 100.0f))
                                .executes(ctx -> setSanity(ctx, FloatArgumentType.getFloat(ctx, "value"), null))
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(ctx -> setSanity(ctx, FloatArgumentType.getFloat(ctx, "value"), EntityArgumentType.getPlayer(ctx, "player"))))))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("delta", FloatArgumentType.floatArg(-100.0f, 100.0f))
                                .executes(ctx -> addSanity(ctx, FloatArgumentType.getFloat(ctx, "delta"), null))
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(ctx -> addSanity(ctx, FloatArgumentType.getFloat(ctx, "delta"), EntityArgumentType.getPlayer(ctx, "player"))))))
        );
    }

    private static ServerPlayerEntity resolveTarget(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity explicit) {
        return explicit != null ? explicit : ctx.getSource().getPlayer();
    }

    private static int getSanity(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity explicit) {
        ServerPlayerEntity target = resolveTarget(ctx, explicit);
        if (target == null) {
            ctx.getSource().sendError(Text.literal("Эту команду может использовать только игрок"));
            return 0;
        }
        float value = SanityManager.get(target).getSanity();
        final String name = target.getName().getString();
        ctx.getSource().sendFeedback(() -> Text.literal("§bРассудок §f" + name + "§b: §f" + String.format("%.1f", value)), false);
        return 1;
    }

    private static int setSanity(CommandContext<ServerCommandSource> ctx, float value, ServerPlayerEntity explicit) {
        ServerPlayerEntity target = resolveTarget(ctx, explicit);
        if (target == null) {
            ctx.getSource().sendError(Text.literal("Эту команду может использовать только игрок"));
            return 0;
        }
        SanityData data = SanityManager.get(target);
        data.setSanity(value);
        SanitySyncPayload.sendTo(target, data.getSanity());
        final String name = target.getName().getString();
        ctx.getSource().sendFeedback(() -> Text.literal("§aРассудок §f" + name + "§a установлен: §f" + String.format("%.1f", data.getSanity())), false);
        return 1;
    }

    private static int addSanity(CommandContext<ServerCommandSource> ctx, float delta, ServerPlayerEntity explicit) {
        ServerPlayerEntity target = resolveTarget(ctx, explicit);
        if (target == null) {
            ctx.getSource().sendError(Text.literal("Эту команду может использовать только игрок"));
            return 0;
        }
        SanityData data = SanityManager.get(target);
        data.addSanity(delta);
        SanitySyncPayload.sendTo(target, data.getSanity());
        final String name = target.getName().getString();
        ctx.getSource().sendFeedback(() -> Text.literal("§aРассудок §f" + name + "§a изменён: §f" + String.format("%.1f", data.getSanity())), false);
        return 1;
    }
}
