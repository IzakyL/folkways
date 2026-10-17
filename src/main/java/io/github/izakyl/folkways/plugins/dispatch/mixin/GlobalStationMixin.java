package io.github.izakyl.folkways.plugins.dispatch.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import io.github.izakyl.folkways.plugins.dispatch.MailGate;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// A train standing at a station takes every parcel out of the postboxes there that is not addressed to one of them.
// A parcel the colony waits on is let aboard only the train booked for it: to any other it reads as addressed here,
// and stays in its postbox.
@Mixin(GlobalStation.class)
public abstract class GlobalStationMixin {

    @WrapOperation(method = "runMailTransfer", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/logistics/box/PackageItem;matchAddress"
            + "(Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;)Z", ordinal = 0), require = 1)
    private boolean folkways$boardOnlyTheBooked(ItemStack parcel, String address, Operation<Boolean> original) {
        GlobalStation station = (GlobalStation) (Object) this;
        Train train = station.getPresentTrain();
        return original.call(parcel, address) || train != null && !MailGate.mayBoard(parcel, train.id, station.name);
    }
}
