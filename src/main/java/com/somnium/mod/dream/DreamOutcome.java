package com.somnium.mod.dream;

/** Чем закончился сон — определяет изменение рассудка при пробуждении. */
public enum DreamOutcome {
    /** Выполнил цель сна. */
    SURVIVED_OBJECTIVE(35.0f, true),
    /** Дожил до конца таймера. */
    SURVIVED_TIMEOUT(15.0f, true),
    /** Проснулся сам (Колокол Пробуждения, звон колокола у кровати, команда). */
    WOKE_EARLY(5.0f, true),
    /** Умер во сне — штраф. */
    DIED_IN_DREAM(-10.0f, true),
    /** Сон оборвался (выход с сервера, перезапуск): без награды и без сброса таймера сна. */
    INTERRUPTED(0.0f, false);

    private final float sanityDelta;
    private final boolean countsAsSleep;

    DreamOutcome(float sanityDelta, boolean countsAsSleep) {
        this.sanityDelta = sanityDelta;
        this.countsAsSleep = countsAsSleep;
    }

    public float sanityDelta() {
        return sanityDelta;
    }

    /** Сбрасывает ли такой исход счётчик бессонницы. */
    public boolean countsAsSleep() {
        return countsAsSleep;
    }
}
