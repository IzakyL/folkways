package io.github.izakyl.folkways.carts.mixin;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import com.jusipat.astikorcartsredux.entity.AbstractDrawnEntity;
import io.github.izakyl.folkways.carts.CartLinks;
import io.github.izakyl.folkways.carts.SavedPuller;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractDrawnEntity.class, remap = false)
public abstract class DrawnCartMixin implements SavedPuller {
    @Shadow private UUID pullingUUID;

    @Override
    public UUID folkways$puller() {
        return pullingUUID;
    }

    @Inject(method = "canPull", at = @At("HEAD"), cancellable = true)
    private void folkways$canPull(Entity puller, CallbackInfoReturnable<Boolean> result) {
        if (CartLinks.residentDrawn((Entity) (Object) this) && CartLinks.residentPuller(puller)) {
            result.setReturnValue(true);
        }
    }

    @Inject(method = "shouldStopPulledTick", at = @At("HEAD"))
    private void folkways$release(CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof AbstractCargoCart cart && CartLinks.residentDrawn(cart)
                && !cart.level().isClientSide()) {
            CartLinks.sync(cart, pullingUUID);
        }
    }

    @Inject(method = {"tick", "setPulling"}, at = @At("RETURN"))
    private void folkways$link(CallbackInfo callback) {
        if ((Object) this instanceof AbstractCargoCart cart && CartLinks.residentDrawn(cart)
                && !cart.level().isClientSide()) {
            CartLinks.sync(cart, pullingUUID);
        }
    }
}
