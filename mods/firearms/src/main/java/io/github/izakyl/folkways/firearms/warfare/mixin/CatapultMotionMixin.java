package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity;
import io.github.izakyl.folkways.firearms.warfare.FrameCatapultMotion;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CatapultShuttleEntity.class)
public abstract class CatapultMotionMixin {
    @Unique
    private static final EntityDataAccessor<Boolean> FOLKWAYS_FRAME_MOTION =
        SynchedEntityData.defineId(CatapultShuttleEntity.class, EntityDataSerializers.BOOLEAN);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void folkways$defineFrameMotion(SynchedEntityData.Builder builder, CallbackInfo callback) {
        builder.define(FOLKWAYS_FRAME_MOTION, false);
    }

    @Invoker("towingTick")
    protected abstract void folkways$tow();

    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER), cancellable = true)
    private void folkways$frame(CallbackInfo callback) {
        var shuttle = (CatapultShuttleEntity) (Object) this;
        if (shuttle.level().isClientSide()) {
            if (shuttle.getEntityData().get(FOLKWAYS_FRAME_MOTION)) {
                // Native client collision predicts against world axes; frame motion arrives in entity packets.
                callback.cancel();
            }
            return;
        }
        boolean frame = FrameCatapultMotion.tick(shuttle, this::folkways$tow);
        shuttle.getEntityData().set(FOLKWAYS_FRAME_MOTION, frame);
        if (frame) {
            callback.cancel();
        }
    }
}
