package io.github.izakyl.folkways.cookery.millstone.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.cookery.millstone.MillstoneDrive;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MillstoneBlockEntity.class, remap = false)
public abstract class MillstoneBlockEntityMixin implements MillstoneDrive.Driver {
    @Shadow private UUID entityId;
    @Unique private MillstoneDrive.NativeBatch folkways$before;

    @Override
    public UUID folkways$driverId() { return entityId; }

    @Inject(method = "canBindEntity", at = @At("HEAD"), cancellable = true)
    private void folkways$controlledResidents(LivingEntity entity, CallbackInfoReturnable<Boolean> answer) {
        if (Bodies.of(entity).isPresent() && !MillstoneDrive.permitted((MillstoneBlockEntity) (Object) this, entity)) {
            answer.setReturnValue(false);
        }
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void folkways$releaseStaleDrivers(CallbackInfo callback) {
        folkways$before = MillstoneDrive.capture((MillstoneBlockEntity) (Object) this);
        MillstoneDrive.before((MillstoneBlockEntity) (Object) this, entityId);
    }
    @Inject(method = "tick", at = @At("TAIL"))
    private void folkways$keepOwnedOutput(CallbackInfo callback) {
        MillstoneDrive.after((MillstoneBlockEntity) (Object) this, folkways$before);
        folkways$before = null;
    }
    // The first check is only native automatic output ejection. Later checks still see the
    // real occupied output slots, so a bound animal cannot accept a second batch over them.
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/github/ysbbbbbb/kaleidoscopecookery/blockentity/kitchen/MillstoneBlockEntity;isOutputEmpty()Z", ordinal = 0))
    private boolean folkways$holdOwnedOutputs(MillstoneBlockEntity mill) {
        return MillstoneDrive.keepOutputs(mill) || mill.isOutputEmpty();
    }
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"))
    private double folkways$worldDistance(LivingEntity entity, Vec3 center) {
        return entity.distanceToSqr(MillstoneDrive.project((MillstoneBlockEntity) (Object) this, entity, center));
    }
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;moveTo(DDDFF)V"))
    private void folkways$worldOrbit(LivingEntity entity, double x, double y, double z, float yaw, float pitch) {
        Vec3 world = MillstoneDrive.project((MillstoneBlockEntity) (Object) this, entity, new Vec3(x, y, z));
        entity.moveTo(world.x, world.y, world.z, yaw, pitch);
    }
}
