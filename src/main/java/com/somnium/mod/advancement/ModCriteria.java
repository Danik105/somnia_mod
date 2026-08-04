package com.somnium.mod.advancement;

import com.somnium.mod.SomniumMod;
import net.minecraft.advancement.criterion.Criterion;

/**
 * Регистрация кастомных критериев достижений для мода Somnium.
 */
public class ModCriteria {

    public static final FirstDreamCriterion FIRST_DREAM = new FirstDreamCriterion();

    public static void register() {
        // Регистрируем кастомный критерий
        net.minecraft.advancement.criterion.Criteria.register(FIRST_DREAM);
        SomniumMod.LOGGER.info("[Somnium] Зарегистрированы критерии достижений");
    }
}
