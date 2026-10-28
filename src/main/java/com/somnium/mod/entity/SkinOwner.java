package com.somnium.mod.entity;

import java.util.Optional;
import java.util.UUID;

/** Сущность, которая рисуется скином конкретного игрока (спящее тело, отражения). */
public interface SkinOwner {
    Optional<UUID> getSkinOwner();
}
