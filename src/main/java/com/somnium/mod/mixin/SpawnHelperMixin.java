package com.somnium.mod.mixin;

import com.somnium.mod.dimension.ModDimensions;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ИСПРАВЛЕНИЕ: ванильные мобы (пауки, скелеты) всё равно спавнятся в измерениях снов,
 * несмотря на spawn_overrides в JSON. Этот миксин полностью блокирует естественный спавн
 * мобов в измерениях снов — там должны быть только наши кошмары (AbstractNightmareEntity),
 * которых мы спавним вручную через DreamManager.
 */
@Mixin(SpawnHelper.class)
public class SpawnHelperMixin {

    @Inject(
            method = "spawnEntitiesInChunk",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void somnium$cancelSpawnInDreamDimensions(
            SpawnGroup spawnGroup,
            ServerWorld world,
            WorldChunk chunk,
            SpawnHelper.Checker checker,
            SpawnHelper.Runner runner,
            CallbackInfo ci
    ) {
        // Если это одно из измерений снов — отменяем спавн полностью
        if (ModDimensions.isDreamDimension(world.getRegistryKey())) {
            ci.cancel();
        }
    }
}
