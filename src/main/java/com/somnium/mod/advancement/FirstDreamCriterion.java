package com.somnium.mod.advancement;

import com.google.gson.JsonObject;
import com.somnium.mod.SomniumMod;
import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.advancement.criterion.AbstractCriterionConditions;
import net.minecraft.predicate.entity.AdvancementEntityPredicateDeserializer;
import net.minecraft.predicate.entity.LootContextPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Критерий достижения "Первое сноведение" - срабатывает при первом входе в любой сон.
 */
public class FirstDreamCriterion extends AbstractCriterion<FirstDreamCriterion.Conditions> {

    @Override
    protected Conditions conditionsFromJson(JsonObject obj, LootContextPredicate playerPredicate, AdvancementEntityPredicateDeserializer predicateDeserializer) {
        return new Conditions(getId(), playerPredicate);
    }

    public void trigger(ServerPlayerEntity player) {
        this.trigger(player, conditions -> true);
    }

    public static class Conditions extends AbstractCriterionConditions {
        public Conditions(Identifier id, LootContextPredicate player) {
            super(id, player);
        }
    }

    @Override
    public Identifier getId() {
        return SomniumMod.id("first_dream");
    }
}
