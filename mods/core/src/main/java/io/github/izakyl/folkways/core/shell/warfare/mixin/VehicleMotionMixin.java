package io.github.izakyl.folkways.core.shell.warfare.mixin;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.shell.warfare.DirectVehicleMotion;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VehicleEntity.class)
public abstract class VehicleMotionMixin implements DirectVehicleMotion.Controlled {
    @Unique
    private static final EntityDataAccessor<Boolean> folkways$controlled = SynchedEntityData.defineId(
        VehicleEntity.class, EntityDataSerializers.BOOLEAN);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void folkways$defineMotion(SynchedEntityData.Builder builder, CallbackInfo callback) {
        builder.define(folkways$controlled, false);
    }

    @Override
    public boolean folkways$controlled() {
        return ((VehicleEntity) (Object) this).getEntityData().get(folkways$controlled);
    }

    @Override
    public void folkways$controlled(boolean controlled) {
        ((VehicleEntity) (Object) this).getEntityData().set(folkways$controlled, controlled);
    }

    @Redirect(method = "travel", at = @At(value = "INVOKE",
        target = "Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo;work("
            + "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;)V"))
    private void folkways$motion(EngineInfo engine, VehicleEntity vehicle) {
        DirectVehicleMotion.work(engine, vehicle);
    }
}
