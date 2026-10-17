package io.github.izakyl.folkways.create.dispatch.mixin;

import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.trains.station.GlobalPackagePort;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import io.github.izakyl.folkways.create.dispatch.PortAliases;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A train unloads a parcel at a postbox by the address its station keeps for it, which Create copies from the filter
// as typed: it is widened by the postbox's alias as the filter is, each time the postbox is attached again.
@Mixin(StationBlockEntity.class)
public abstract class StationBlockEntityMixin {

    @Inject(method = "attachPackagePort", at = @At("TAIL"), require = 1)
    private void folkways$answerToAlias(PackagePortBlockEntity port, CallbackInfo ci) {
        GlobalStation station = ((StationBlockEntity) (Object) this).getStation();
        GlobalPackagePort kept = station == null ? null : station.connectedPorts.get(port.getBlockPos());
        if (kept != null) {
            kept.address = PortAliases.widen(port.addressFilter, port.getBlockPos());
        }
    }
}
