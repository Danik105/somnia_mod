package com.somnium.mod.client;

import com.somnium.mod.haunt.Horror;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

/**
 * Скример (лицо на весь экран с тряской и красной вспышкой) и чёрные полосы катсцены.
 * Команды приходят с сервера (Horror.screamer / Horror.letterbox).
 */
public final class HorrorOverlay {

    private static final Identifier[] FACES = new Identifier[Horror.FACES];
    private static final Random RANDOM = Random.create();

    static {
        for (int i = 0; i < FACES.length; i++) {
            FACES[i] = new Identifier("somnium", "textures/gui/screamer_" + i + ".png");
        }
    }

    private static int screamerTicks;
    private static int screamerTotal;
    private static int face;
    private static boolean letterbox;
    private static float bars;

    private HorrorOverlay() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(Horror.SCREAMER_ID, (client, handler, buf, sender) -> {
            int f = buf.readVarInt();
            int ticks = buf.readVarInt();
            client.execute(() -> {
                face = Math.floorMod(f, FACES.length);
                screamerTicks = ticks;
                screamerTotal = ticks;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Horror.LETTERBOX_ID, (client, handler, buf, sender) -> {
            boolean on = buf.readBoolean();
            client.execute(() -> letterbox = on);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (screamerTicks > 0) screamerTicks--;
            float target = letterbox ? 1.0f : 0.0f;
            bars += (target - bars) * 0.2f;
            if (Math.abs(target - bars) < 0.01f) bars = target;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            screamerTicks = 0;
            letterbox = false;
            bars = 0;
        });
    }

    public static void render(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();

        if (bars > 0) {
            int bar = Math.round(h * 0.12f * bars);
            ctx.fill(0, 0, w, bar, 0xFF000000);
            ctx.fill(0, h - bar, w, h, 0xFF000000);
        }

        if (screamerTicks <= 0) return;
        ctx.fill(0, 0, w, h, 0xFF000000);
        // Лицо резко наезжает на камеру и трясётся
        float progress = 1.0f - (float) screamerTicks / Math.max(1, screamerTotal);
        float scale = 1.05f + progress * 0.35f;
        int size = Math.round(Math.max(w, h) * scale);
        int shake = Math.max(2, size / 40);
        int x = (w - size) / 2 + RANDOM.nextInt(shake * 2 + 1) - shake;
        int y = (h - size) / 2 + RANDOM.nextInt(shake * 2 + 1) - shake;
        ctx.drawTexture(FACES[face], x, y, size, size, 0, 0, 256, 256, 256, 256);
        if (screamerTicks % 4 < 2) ctx.fill(0, 0, w, h, 0x55AA0000);
    }
}
