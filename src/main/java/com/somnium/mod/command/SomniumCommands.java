package com.somnium.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.somnium.mod.SomniumMod;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.haunt.SleepParalysis;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.util.math.BlockPos;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamRegistry;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.dream.DreamType;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Команды (уровень прав 2 — операторы и одиночная игра с читами):
 *  /somnium dream &lt;сон&gt; [игроки]   — немедленно отправить в сон
 *  /somnium wake [игроки]            — разбудить
 *  /somnium sanity get|set|add ...   — рассудок
 *  /somnium sessions                 — активные сны
 * /testdream и /sanity оставлены как короткие псевдонимы.
 *
 * Раньше команды были доступны любому игроку на сервере, а /testdream во сне
 * перезаписывал снимок инвентаря — настоящие вещи пропадали навсегда.
 */
public final class SomniumCommands {

    private static final DynamicCommandExceptionType UNKNOWN_DREAM = new DynamicCommandExceptionType(
            id -> Text.translatable("somnium.command.unknown_dream", String.valueOf(id)));

    private static final SuggestionProvider<ServerCommandSource> DREAMS = (ctx, builder) ->
            CommandSource.suggestIdentifiers(DreamRegistry.all().stream().map(DreamType::id), builder);

    private SomniumCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess access,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("somnium").requires(src -> src.hasPermissionLevel(2))
                .then(dreamNode("dream"))
                .then(literal("wake")
                        .executes(ctx -> wake(ctx, List.of(ctx.getSource().getPlayerOrThrow())))
                        .then(argument("targets", EntityArgumentType.players())
                                .executes(ctx -> wake(ctx, EntityArgumentType.getPlayers(ctx, "targets")))))
                .then(sanityNode())
                .then(literal("sessions").executes(SomniumCommands::sessions))
                .then(literal("paralysis")
                        .executes(ctx -> paralysis(ctx, List.of(ctx.getSource().getPlayerOrThrow())))
                        .then(argument("targets", EntityArgumentType.players())
                                .executes(ctx -> paralysis(ctx, EntityArgumentType.getPlayers(ctx, "targets"))))));
        dispatcher.register(literal("paralysis").requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> paralysis(ctx, List.of(ctx.getSource().getPlayerOrThrow())))
                .then(argument("targets", EntityArgumentType.players())
                        .executes(ctx -> paralysis(ctx, EntityArgumentType.getPlayers(ctx, "targets")))));

        dispatcher.register(dreamNode("testdream").requires(src -> src.hasPermissionLevel(2)));
        dispatcher.register(sanityNode().requires(src -> src.hasPermissionLevel(2)));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> dreamNode(String name) {
        return literal(name).then(argument("dream", IdentifierArgumentType.identifier()).suggests(DREAMS)
                .executes(ctx -> dream(ctx, List.of(ctx.getSource().getPlayerOrThrow())))
                .then(argument("targets", EntityArgumentType.players())
                        .executes(ctx -> dream(ctx, EntityArgumentType.getPlayers(ctx, "targets")))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> sanityNode() {
        return literal("sanity")
                .then(literal("get")
                        .executes(ctx -> sanityGet(ctx, ctx.getSource().getPlayerOrThrow()))
                        .then(argument("target", EntityArgumentType.player())
                                .executes(ctx -> sanityGet(ctx, EntityArgumentType.getPlayer(ctx, "target")))))
                .then(literal("set").then(argument("value", FloatArgumentType.floatArg(0, 100))
                        .executes(ctx -> sanityChange(ctx, List.of(ctx.getSource().getPlayerOrThrow()), false))
                        .then(argument("targets", EntityArgumentType.players())
                                .executes(ctx -> sanityChange(ctx, EntityArgumentType.getPlayers(ctx, "targets"), false)))))
                .then(literal("add").then(argument("value", FloatArgumentType.floatArg(-100, 100))
                        .executes(ctx -> sanityChange(ctx, List.of(ctx.getSource().getPlayerOrThrow()), true))
                        .then(argument("targets", EntityArgumentType.players())
                                .executes(ctx -> sanityChange(ctx, EntityArgumentType.getPlayers(ctx, "targets"), true)))));
    }

    private static int dream(CommandContext<ServerCommandSource> ctx, Collection<ServerPlayerEntity> targets)
            throws CommandSyntaxException {
        Identifier id = IdentifierArgumentType.getIdentifier(ctx, "dream");
        DreamType type = DreamRegistry.get(id);
        // "/testdream elevator" читается как minecraft:elevator, а сны лежат в somnium:
        if (type == null && id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            type = DreamRegistry.get(SomniumMod.id(id.getPath()));
        }
        if (type == null) throw UNKNOWN_DREAM.create(id);
        int started = 0;
        for (ServerPlayerEntity player : targets) {
            if (DreamManager.isDreaming(player.getUuid())) {
                ctx.getSource().sendError(Text.translatable("somnium.command.already_dreaming", player.getName()));
                continue;
            }
            if (DreamManager.enterDream(player, type, null)) started++;
        }
        int count = started;
        String dreamName = type.nameKey();
        ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.dream_started",
                Text.translatable(dreamName), count).formatted(Formatting.GREEN), true);
        return count;
    }

    private static int wake(CommandContext<ServerCommandSource> ctx, Collection<ServerPlayerEntity> targets) {
        int woken = 0;
        for (ServerPlayerEntity player : targets) {
            if (DreamManager.requestWake(player, DreamOutcome.WOKE_EARLY)) woken++;
        }
        int count = woken;
        ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.woken", count), true);
        return count;
    }

    private static int sanityGet(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player) {
        float value = SanityManager.get(player).getSanity();
        ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.sanity_get", player.getName(),
                String.format("%.1f", value)).formatted(Formatting.AQUA), false);
        return Math.round(value);
    }

    private static int sanityChange(CommandContext<ServerCommandSource> ctx, Collection<ServerPlayerEntity> targets,
                                    boolean add) {
        float value = FloatArgumentType.getFloat(ctx, "value");
        for (ServerPlayerEntity player : targets) {
            if (add) SanityManager.add(player, value);
            else SanityManager.set(player, value);
            float now = SanityManager.get(player).getSanity();
            ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.sanity_set", player.getName(),
                    String.format("%.1f", now)).formatted(Formatting.GREEN), true);
        }
        return targets.size();
    }

    /**
     * Сонный паралич со 100% шансом. Лежит в кровати — начинается сразу. Кровать рядом
     * (в 6 блоках) — игрока укладывает в неё. Иначе паралич случится, когда он ляжет сам.
     */
    private static int paralysis(CommandContext<ServerCommandSource> ctx, Collection<ServerPlayerEntity> targets) {
        int started = 0;
        for (ServerPlayerEntity player : targets) {
            if (DreamManager.isDreaming(player.getUuid())) {
                ctx.getSource().sendError(Text.translatable("somnium.command.already_dreaming", player.getName()));
                continue;
            }
            if (!player.isSleeping()) {
                BlockPos bed = findBed(player);
                if (bed != null) player.sleep(bed);
            }
            if (player.isSleeping() && SleepParalysis.start(player)) {
                started++;
            } else {
                SleepParalysis.forceNext(player);
                ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.paralysis_next", player.getName())
                        .formatted(Formatting.GRAY), false);
            }
        }
        int count = started;
        if (count > 0) {
            ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.paralysis", count).formatted(Formatting.GREEN), true);
        }
        return count;
    }

    @org.jetbrains.annotations.Nullable
    private static BlockPos findBed(ServerPlayerEntity player) {
        for (BlockPos pos : BlockPos.iterateOutwards(player.getBlockPos(), 6, 3, 6)) {
            BlockState state = player.getWorld().getBlockState(pos);
            if (state.getBlock() instanceof BedBlock && state.get(BedBlock.PART) == BedPart.HEAD && !state.get(BedBlock.OCCUPIED)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static int sessions(CommandContext<ServerCommandSource> ctx) {
        Collection<DreamSession> sessions = DreamManager.sessions();
        ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.sessions", sessions.size()), false);
        for (DreamSession session : sessions) {
            ctx.getSource().sendFeedback(() -> Text.translatable("somnium.command.session_line",
                    session.type.id().toString(), session.slot, session.members().size(), session.remaining() / 20)
                    .formatted(Formatting.GRAY), false);
        }
        return sessions.size();
    }
}
