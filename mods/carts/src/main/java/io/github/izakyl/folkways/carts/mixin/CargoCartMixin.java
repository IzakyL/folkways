package io.github.izakyl.folkways.carts.mixin;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.carts.CartContents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractCargoCart.class, remap = false)
public abstract class CargoCartMixin implements CartContents.View {
    @Unique
    private static final EntityDataAccessor<CompoundTag> FOLKWAYS_CONTENTS =
        SynchedEntityData.defineId(AbstractCargoCart.class, EntityDataSerializers.COMPOUND_TAG);

    @Inject(method = "defineSynchedData", at = @At("RETURN"))
    private void folkways$defineContents(SynchedEntityData.Builder builder, CallbackInfo callback) {
        builder.define(FOLKWAYS_CONTENTS, new CompoundTag());
    }

    @Inject(method = "onContentsChanged", at = @At("RETURN"))
    private void folkways$stockChanged(int slot, CallbackInfo callback) {
        CartContents.synchronize((AbstractCargoCart) (Object) this);
    }

    @Override
    public CompoundTag folkways$contents() {
        return ((AbstractCargoCart) (Object) this).getEntityData().get(FOLKWAYS_CONTENTS);
    }

    @Override
    public void folkways$contents(CompoundTag contents) {
        ((AbstractCargoCart) (Object) this).getEntityData().set(FOLKWAYS_CONTENTS, contents);
    }
}
