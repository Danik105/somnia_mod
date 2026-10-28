package com.somnium.mod.dream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Подсказки во сне. Короткие висят в экшенбаре заданное время (ваниль гасит его через ~3 с,
 * поэтому текст переотправляется), длинные один раз уходят в чат — там их можно перечитать.
 */
public final class DreamHints {

    private static final int ACTION_BAR_MAX_LENGTH = 60;
    private static final int RESEND_INTERVAL = 25;

    private record ActiveHint(Text text, long untilTick) {}

    private static final Map<UUID, ActiveHint> ACTIVE = new HashMap<>();

    private DreamHints() {}

    public static void show(ServerPlayerEntity player, Text text, int seconds) {
        if (text.getString().length() > ACTION_BAR_MAX_LENGTH) {
            player.sendMessage(text, false);
            return;
        }
        long until = player.getServer().getTicks() + seconds * 20L;
        ACTIVE.put(player.getUuid(), new ActiveHint(text, until));
        player.sendMessage(text, true);
    }

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long now = server.getTicks();
        Iterator<Map.Entry<UUID, ActiveHint>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now >= entry.getValue().untilTick()) {
                it.remove();
                continue;
            }
            if (now % RESEND_INTERVAL != 0) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
            } else {
                player.sendMessage(entry.getValue().text(), true);
            }
        }
    }
}
