package io.github.izakyl.folkways.create.dispatch.mixin;

import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import io.github.izakyl.folkways.create.dispatch.CreatePorts;
import io.github.izakyl.folkways.create.dispatch.PortAliases;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Chain routing tables, chain drop-offs and a frogport's own catch all read the port's filter through here.
@Mixin(PackagePortBlockEntity.class)
public abstract class PackagePortBlockEntityMixin {

    @Inject(method = "getFilterString", at = @At("RETURN"), cancellable = true, require = 1)
    private void folkways$answerToAlias(CallbackInfoReturnable<String> cir) {
        PackagePortBlockEntity port = (PackagePortBlockEntity) (Object) this;
        cir.setReturnValue(PortAliases.widen(cir.getReturnValue(), port.getBlockPos()));
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void folkways$rememberPort(CallbackInfo ci) {
        CreatePorts.track((PackagePortBlockEntity) (Object) this);
    }
}
