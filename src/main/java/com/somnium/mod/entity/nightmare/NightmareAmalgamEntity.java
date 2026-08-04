package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

/**
 * Nightmare Amalgam — уникальный босс сна «Сон-в-сне».
 * Циклически меняет "фазу", копируя механики других монстров:
 *  Фаза 1: как Watcher — замирает под взглядом
 *  Фаза 2: как Lurking Shade — становится невидимым
 *  Фаза 3: как Flesh Golem — атака по площади
 * Каждая фаза длится ~15 секунд, затем следующая — заставляет игрока
 * постоянно адаптировать тактику.
 */
public class NightmareAmalgamEntity extends AbstractNightmareEntity {

    private enum Phase { WATCHER, SHADE, GOLEM }

    private Phase currentPhase = Phase.WATCHER;
    private int phaseTimer = 0;
    private static final int PHASE_DURATION_TICKS = 300; // 15 секунд

    public NightmareAmalgamEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 150.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.22)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 8.0);
    }

    @Override
    public void tick() {
        super.tick();
        phaseTimer++;
        if (phaseTimer >= PHASE_DURATION_TICKS) {
            phaseTimer = 0;
            currentPhase = switch (currentPhase) {
                case WATCHER -> Phase.SHADE;
                case SHADE -> Phase.GOLEM;
                case GOLEM -> Phase.WATCHER;
            };
            // TODO: визуальный/звуковой эффект смены фазы + партиклы искажения
        }
        // TODO: делегировать поведение конкретной фазы соответствующей логике
        // (переиспользовать методы WatcherEntity/LurkingShadeEntity/FleshGolemEntity через статические хелперы)
    }
}
