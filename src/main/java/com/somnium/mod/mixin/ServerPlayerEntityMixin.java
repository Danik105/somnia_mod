package com.somnium.mod.mixin;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.player.SomniumPlayerAccess;
import com.somnium.mod.player.SomniumPlayerState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 *  - хранит данные мода на игроке и сохраняет их в его NBT;
 *  - смерть во сне — не настоящая смерть: отменяется и превращается в пробуждение;
 *  - короткая неуязвимость сразу после телепорта в сон и обратно.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin implements SomniumPlayerAccess {

    @Unique
    private final SomniumPlayerState somnium$state = new SomniumPlayerState();

    @Override
    public SomniumPlayerState somnium$getState() {
        return somnium$state;
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void somnium$write(NbtCompound nbt, CallbackInfo ci) {
        somnium$state.writeNbt(nbt);
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void somnium$read(NbtCompound nbt, CallbackInfo ci) {
        somnium$state.readNbt(nbt);
    }

    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void somnium$onDeath(DamageSource source, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (DreamManager.isDreaming(self.getUuid())) {
            ci.cancel();
            DreamManager.onDeathInDream(self);
        }
    }

    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void somnium$onDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (self.getServer() != null && DreamManager.isLandingImmune(self.getUuid(), self.getServer().getTicks())) {
            cir.setReturnValue(false);
        }
    }
}
