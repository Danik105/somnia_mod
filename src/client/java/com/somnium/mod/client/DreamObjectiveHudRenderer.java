package com.somnium.mod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Постоянный HUD-виджет сверху экрана: ТОЛЬКО полоса оставшегося времени сна.
 *
 * ИЗМЕНЕНО ("убери сверху название сна и цель сна"): строки с названием сна и целью
 * убраны из HUD — название и цель теперь отправляются в чат при входе в сон
 * (см. DreamManager#enterDreamWithType), где их всегда можно перечитать, а экран
 * не захламляется текстом. Оставлена только рамочная полоса времени — она
 * информативна и не мешает обзору.
 *
 * Полоса показывает долю времени сна, оставшегося до принудительного пробуждения
 * по таймауту (DreamType#durationTicks()) — по мере убывания цвет плавно смещается
 * от насыщенного фиолетового (тема сна) к тревожному красному.
 */
public final class DreamObjectiveHudRenderer {

    private static final int BAR_WIDTH = 220;
    private static final int BAR_HEIGHT = 7;
    private static final int TOP_MARGIN = 12;

    // Цвет полосы в начале сна (много времени) и в конце (времени почти нет).
    private static final int COLOR_FULL_R = 0x8E, COLOR_FULL_G = 0x3A, COLOR_FULL_B = 0xD6;   // фиолетовый
    private static final int COLOR_EMPTY_R = 0xC4, COLOR_EMPTY_G = 0x1E, COLOR_EMPTY_B = 0x2A; // тревожный красный

    private DreamObjectiveHudRenderer() {}

    public static void render(DrawContext context, DreamHudState state) {
        if (state == null || !state.active()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;

        int screenWidth = context.getScaledWindowWidth();
        int centerX = screenWidth / 2;

        // Только полоса времени с компактной плашкой-подложкой (название/цель — в чате,
        // см. заголовок класса).
        int barY = TOP_MARGIN;
        int barX = centerX - BAR_WIDTH / 2;

        context.fill(barX - 8, barY - 4, barX + BAR_WIDTH + 8, barY + BAR_HEIGHT + 4, 0x70000000);

        float fraction = state.totalTicks() <= 0 ? 0f
                : Math.max(0f, Math.min(1f, state.remainingTicks() / (float) state.totalTicks()));

        drawBar(context, barX, barY, fraction);
    }

    private static void drawBar(DrawContext context, int x, int y, float fraction) {
        // Бронзовая рамка в два слоя (тёмный + светлый) — примерно как окантовка полос статов
        // у Ведьмака, вместо плоской однотонной ванильной полоски.
        context.fill(x - 2, y - 2, x + BAR_WIDTH + 2, y + BAR_HEIGHT + 2, 0xFF3A2A14);
        context.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xFFC9A24B);
        context.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF15100A);

        int filledWidth = (int) (BAR_WIDTH * fraction);
        if (filledWidth <= 0) return;

        int baseColor = lerpColor(fraction);
        int lightColor = shade(baseColor, 1.35f);
        int darkColor = shade(baseColor, 0.75f);

        // Лёгкий вертикальный градиент внутри полосы (светлее сверху) — придаёт объём,
        // а не плоскую заливку сплошным цветом.
        context.fillGradient(x, y, x + filledWidth, y + BAR_HEIGHT, lightColor, darkColor);
    }

    /** Линейная интерполяция между цветом "много времени" и "мало времени". */
    private static int lerpColor(float fraction) {
        int r = (int) (COLOR_EMPTY_R + (COLOR_FULL_R - COLOR_EMPTY_R) * fraction);
        int g = (int) (COLOR_EMPTY_G + (COLOR_FULL_G - COLOR_EMPTY_G) * fraction);
        int b = (int) (COLOR_EMPTY_B + (COLOR_FULL_B - COLOR_EMPTY_B) * fraction);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int shade(int argb, float factor) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((argb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Простое неизменяемое состояние HUD, полученное последним DreamHudPayload. */
    public record DreamHudState(boolean active, String dreamNameKey, String objectiveText, int remainingTicks, int totalTicks) {
        public static final DreamHudState INACTIVE = new DreamHudState(false, "", "", 0, 0);
    }
}
