package com.somnium.mod.client;

import com.somnium.mod.sanity.SanityManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * HUD-виджет рассудка — «Глаз рассудка», всё рисуется процедурно, без текстур.
 *
 * Расположение (ИЗМЕНЕНО по запросу "перенеси шкалу по центру к ХП/броне/голоду"):
 * виджет центрируется по горизонтали и вписывается строкой НАД ванильными полосами
 * статуса (сердца/броня/поглощение), автоматически поднимаясь выше, если над сердцами
 * отрисованы ряды брони или поглощения, чтобы ничего не перекрывать. В режимах без
 * полос статуса (творческий/наблюдатель) прижимается к области над хотбаром.
 *
 * Состояние сна (ДОБАВЛЕНО по запросу "во сне глазик закрытый"): пока клиент считает
 * сон активным (см. DreamHudPayload в SomniumClient), глаз рисуется закрытым
 * (щёлка с ресничками), а под виджетом мягко пульсирует фиолетовое свечение —
 * игрок с первого взгляда понимает, что персонаж спит.
 *
 * Остальное как раньше:
 *  - открытость глаза пропорциональна рассудку; ниже THRESHOLD_ECHOES белок наливается
 *    кровью и глаз перестаёт моргать; при высоком рассудке — изредка моргает;
 *  - шкала в двухслойной фиолетово-серебряной рамке с градиентом и засечками на
 *    порогах 50 / 25 / 10;
 *  - цвет шкалы: фиолетовый (100) -> янтарный (50) -> оранжевый (25) -> алый (0);
 *  - заполнение плавно догоняет реальное значение; справа — числовое значение;
 *  - ниже THRESHOLD_RUPTURE — пульсирующая красная подложка и дрожь виджета.
 */
public final class SanityHudRenderer {

    private static final int BAR_WIDTH = 76;
    private static final int BAR_HEIGHT = 7;

    private static final int EYE_HALF_HEIGHT = 4;
    private static final int EYE_HALF_WIDTH = 8;
    private static final int EYE_WIDTH = EYE_HALF_WIDTH * 2 + 1;

    private static final int EYE_BAR_GAP = 5;
    private static final int BAR_VALUE_GAP = 6;

    // Цветовые ступени шкалы: значение рассудка -> RGB.
    private static final int[] STOP_VALUES = {100, 50, 25, 0};
    private static final int[] STOP_COLORS = {
            0x8B7CFF, // мягкий фиолетовый — спокойствие
            0xE0B040, // янтарный — шёпот
            0xE06030, // оранжевый — эхо
            0xA01025  // алый — разрыв
    };

    /** Отображаемое значение — плавно догоняет реальное (синхронизация раз в секунду). */
    private static float displayedSanity = -1f;

    private SanityHudRenderer() {}

    public static void render(DrawContext context, float sanity, boolean dreaming) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;

        // Плавная анимация заполнения: экспоненциальное сглаживание к целевому значению.
        if (displayedSanity < 0f) {
            displayedSanity = sanity;
        } else {
            displayedSanity += (sanity - displayedSanity) * 0.12f;
            if (Math.abs(displayedSanity - sanity) < 0.05f) displayedSanity = sanity;
        }

        int screenHeight = context.getScaledWindowHeight();
        int centerX = context.getScaledWindowWidth() / 2;

        // Вертикальная позиция: строкой выше верхнего ряда ванильных полос статуса.
        // Ряды сердец/голода занимают height-39..height-30; броня и поглощение
        // добавляют по ряду выше (10 px каждый) — учитываем, чтобы не перекрывать их.
        int y;
        if (client.interactionManager != null && client.interactionManager.hasStatusBars() && client.player != null) {
            int topOffset = 39;
            if (client.player.getArmor() > 0) topOffset += 10;
            if (client.player.getAbsorptionAmount() > 0) topOffset += 10;
            // ИСПРАВЛЕНО ("шкала перекрывает пузырьки воздуха под водой"): ряд пузырьков
            // рисуется над голодом на той же высоте, что и броня, — поднимаем виджет ещё
            // на ряд, пока игрок тратит запас воздуха.
            if (client.player.getAir() < client.player.getMaxAir()) topOffset += 10;
            y = screenHeight - topOffset - (BAR_HEIGHT + 5);
        } else {
            // Творческий режим / наблюдатель: полос статуса нет — над хотбаром.
            y = screenHeight - 36;
        }

        // Дрожание всего виджета при низком рассудке — маркер тревоги.
        int jitterX = 0, jitterY = 0;
        if (sanity <= SanityManager.THRESHOLD_RUPTURE) {
            jitterX = (int) (Math.sin(System.currentTimeMillis() / 40.0) * 2);
            jitterY = (int) (Math.cos(System.currentTimeMillis() / 55.0) * 2);
        } else if (sanity <= SanityManager.THRESHOLD_ECHOES) {
            jitterY = (int) (Math.sin(System.currentTimeMillis() / 90.0) * 1);
        }
        y += jitterY;

        int stateColor = evalColor(displayedSanity);

        Text valueText = Text.literal(String.valueOf(Math.round(displayedSanity)));
        int valueWidth = client.textRenderer.getWidth(valueText);

        // Центрируем всю группу [глаз][шкала][число] по ширине экрана.
        int totalWidth = EYE_WIDTH + EYE_BAR_GAP + BAR_WIDTH + BAR_VALUE_GAP + valueWidth;
        int groupX = centerX - totalWidth / 2 + jitterX;
        int eyeCx = groupX + EYE_HALF_WIDTH;
        int barX = groupX + EYE_WIDTH + EYE_BAR_GAP;
        int valueX = barX + BAR_WIDTH + BAR_VALUE_GAP;

        // Мягкое фиолетовое свечение во сне — виджет "дышит", игрок видит, что спит.
        if (dreaming) {
            int alpha = 0x28 + (int) (0x18 * (Math.sin(System.currentTimeMillis() / 400.0) * 0.5 + 0.5));
            context.fill(groupX - 4, y - 5, valueX + valueWidth + 4, y + BAR_HEIGHT + 5, (alpha << 24) | 0x4A2E80);
        }

        // Пульсирующая красная подложка при рассудке ниже порога разрыва.
        if (sanity <= SanityManager.THRESHOLD_RUPTURE) {
            int alpha = 0x30 + (int) (0x20 * (Math.sin(System.currentTimeMillis() / 150.0) * 0.5 + 0.5));
            context.fill(groupX - 4, y - 5, valueX + valueWidth + 4, y + BAR_HEIGHT + 5, (alpha << 24) | 0x801018);
        }

        // Общая полупрозрачная плашка под весь виджет — читаемость на любом фоне.
        context.fill(groupX - 3, y - 4, valueX + valueWidth + 3, y + BAR_HEIGHT + 4, 0x70000000);

        if (displayedSanity <= 0.0f) {
            // Рассудок на нуле — "мёртвый" глаз: красный перечёркнутый крестик вместо век.
            drawDeadEye(context, eyeCx, y + BAR_HEIGHT / 2);
        } else if (dreaming) {
            drawClosedEye(context, eyeCx, y + BAR_HEIGHT / 2);
        } else {
            drawEye(context, eyeCx, y + BAR_HEIGHT / 2, displayedSanity, stateColor);
        }
        drawBar(context, barX, y, displayedSanity / 100.0f, stateColor);

        context.drawText(client.textRenderer, valueText, valueX, y + BAR_HEIGHT / 2 - 4, stateColor, true);
    }

    /** Шкала в двухслойной рамке с градиентом и засечками на порогах. */
    private static void drawBar(DrawContext context, int x, int y, float fraction, int baseColor) {
        fraction = Math.max(0f, Math.min(1f, fraction));

        // Рамка в два слоя (тёмный + светлый) — тот же приём, что в DreamObjectiveHudRenderer,
        // но в фиолетово-серебряной гамме, чтобы виджеты различались с первого взгляда.
        context.fill(x - 2, y - 2, x + BAR_WIDTH + 2, y + BAR_HEIGHT + 2, 0xFF1B1430);
        context.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xFF8F7FD6);
        context.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF120E1E);

        int filledWidth = (int) (BAR_WIDTH * fraction);
        if (filledWidth > 0) {
            // Вертикальный градиент (светлее сверху) — объём вместо плоской заливки.
            context.fillGradient(x, y, x + filledWidth, y + BAR_HEIGHT,
                    shade(baseColor, 1.35f), shade(baseColor, 0.75f));
        }

        // Засечки на порогах 50 / 25 / 10 — игрок видит, где проходят "линии тревоги".
        for (float threshold : new float[]{SanityManager.THRESHOLD_WHISPERS, SanityManager.THRESHOLD_ECHOES, SanityManager.THRESHOLD_RUPTURE}) {
            int tickX = x + (int) (BAR_WIDTH * (threshold / 100.0f));
            context.fill(tickX, y, tickX + 1, y + BAR_HEIGHT, 0x66000000);
        }
    }

    /**
     * Процедурный глаз: миндалевидный белок, радужка цвета текущего состояния, зрачок.
     * Открытость пропорциональна рассудку; ниже THRESHOLD_ECHOES белок краснеет,
     * а моргание отключается — неподвижный взгляд страшнее.
     */
    private static void drawEye(DrawContext context, int cx, int cy, float sanity, int irisColor) {
        float openness = Math.max(0.15f, sanity / 100.0f);

        // Редкое моргание (раз в ~4 секунды, 150 мс) — только пока рассудок не слишком низок.
        if (sanity > SanityManager.THRESHOLD_ECHOES) {
            long phase = System.currentTimeMillis() % 4000;
            if (phase > 3850) {
                openness *= Math.abs((phase - 3850) / 75.0f - 1.0f);
            }
        }

        boolean bloodshot = sanity <= SanityManager.THRESHOLD_ECHOES;
        int scleraColor = bloodshot ? 0xFFD9A8A8 : 0xFFE9E5DA;

        // Белок: миндалевидная форма по строкам, сжатая по вертикали согласно открытости.
        int maxVisibleRow = (int) (EYE_HALF_HEIGHT * openness);
        for (int row = -EYE_HALF_HEIGHT; row <= EYE_HALF_HEIGHT; row++) {
            if (Math.abs(row) > maxVisibleRow) continue;
            double n = row / (EYE_HALF_HEIGHT + 0.6);
            int halfW = (int) Math.round(EYE_HALF_WIDTH * Math.sqrt(Math.max(0.0, 1.0 - n * n)));
            context.fill(cx - halfW, cy + row, cx + halfW + 1, cy + row + 1, scleraColor);
        }

        // Радужка и зрачок — только если глаз достаточно открыт, чтобы их было видно.
        if (maxVisibleRow >= 1) {
            int irisHalf = Math.min(2, maxVisibleRow);
            for (int row = -irisHalf; row <= irisHalf; row++) {
                double n = row / 2.4;
                int halfW = (int) Math.round(2 * Math.sqrt(Math.max(0.0, 1.0 - n * n)));
                context.fill(cx - halfW, cy + row, cx + halfW + 1, cy + row + 1, irisColor);
            }
            context.fill(cx, cy, cx + 1, cy + 1, 0xFF101010);
        }
    }

    /** Закрытый глаз во сне: тёмная щёлка с тремя ресничками вниз. */
    private static void drawClosedEye(DrawContext context, int cx, int cy) {
        int lashColor = 0xFF4A4066;
        // Линия сомкнутых век — та же ширина, что у открытого глаза.
        context.fill(cx - EYE_HALF_WIDTH, cy, cx + EYE_HALF_WIDTH + 1, cy + 1, lashColor);
        // Реснички.
        for (int i = -1; i <= 1; i++) {
            int lashX = cx + i * 5;
            context.fill(lashX, cy + 1, lashX + 1, cy + 3, lashColor);
        }
    }

    /**
     * "Мёртвый" глаз при нулевом рассудке (ДОБАВЛЕНО по запросу "глаз красным
     * перечёркнутым крестиком"): тёмная щёлка век + алый крест поверх — как знак
     * "вычеркнут из реальности". Диагонали рисуются попиксельно по строкам.
     */
    private static void drawDeadEye(DrawContext context, int cx, int cy) {
        int lashColor = 0xFF4A4066;
        int crossColor = 0xFFC01A2E;
        context.fill(cx - EYE_HALF_WIDTH, cy, cx + EYE_HALF_WIDTH + 1, cy + 1, lashColor);
        for (int d = -4; d <= 4; d++) {
            // Диагональ сверху-слева вниз-вправо.
            context.fill(cx + d, cy - EYE_HALF_HEIGHT + (d + 4), cx + d + 1, cy - EYE_HALF_HEIGHT + (d + 5), crossColor);
            // Диагональ снизу-слева вверх-вправо.
            context.fill(cx + d, cy + EYE_HALF_HEIGHT - (d + 4), cx + d + 1, cy + EYE_HALF_HEIGHT - (d + 3), crossColor);
        }
    }

    /** Цвет шкалы: плавная интерполяция между ступенями STOP_VALUES/STOP_COLORS. */
    private static int evalColor(float sanity) {
        for (int i = 0; i < STOP_VALUES.length - 1; i++) {
            int hi = STOP_VALUES[i];
            int lo = STOP_VALUES[i + 1];
            if (sanity <= hi && sanity >= lo) {
                float t = (sanity - lo) / (float) (hi - lo);
                return lerpColor(STOP_COLORS[i + 1], STOP_COLORS[i], t);
            }
        }
        return 0xFF000000 | STOP_COLORS[STOP_COLORS.length - 1];
    }

    private static int lerpColor(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int shade(int argb, float factor) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((argb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
