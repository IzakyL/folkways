package io.github.izakyl.folkways.firefighting.mixin;

import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import io.github.izakyl.folkways.firefighting.HoseSessions;
import io.github.izakyl.folkways.firefighting.Hydrants;
import io.github.izakyl.folkways.firefighting.ResidentHydrantBinding;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FireHydrantCabinetBlockEntity.class, remap = false)
public abstract class HydrantCabinetMixin implements ResidentHydrantBinding {
    @Shadow private UUID boundPlayer;
    @Unique private UUID folkways$resident;

    @Shadow public abstract boolean isDoorOpen();
    @Shadow private void updateDoorState(boolean wasOpen) { throw new AssertionError(); }

    @Inject(method = "tick", at = @At("HEAD"))
    private static void folkways$observed(Level level, BlockPos pos, BlockState state,
            FireHydrantCabinetBlockEntity cabinet, CallbackInfo ci) {
        Hydrants.observe(level, pos, cabinet);
    }

    @Override
    public boolean folkways$bindResident(UUID resident) {
        if (boundPlayer != null) {
            return false;
        }
        boolean open = isDoorOpen();
        boundPlayer = resident;
        folkways$resident = resident;
        updateDoorState(open);
        return true;
    }

    @Override
    public void folkways$unbindResident(UUID resident, boolean notify) {
        if (!resident.equals(folkways$resident)) {
            return;
        }
        boolean open = isDoorOpen();
        if (resident.equals(boundPlayer)) {
            boundPlayer = null;
        }
        folkways$resident = null;
        if (notify) {
            updateDoorState(open);
        }
    }

    @Inject(method = "forceClearBinding", at = @At("HEAD"))
    private void folkways$disconnected(CallbackInfo ci) {
        HoseSessions.disconnect((FireHydrantCabinetBlockEntity) (Object) this);
    }

    @Inject(method = "bindTo", at = @At("HEAD"))
    private void folkways$playerTookOver(Player player, CallbackInfo ci) {
        HoseSessions.disconnect((FireHydrantCabinetBlockEntity) (Object) this);
    }

    @Inject(method = "clearBinding", at = @At("HEAD"))
    private void folkways$cleared(UUID owner, CallbackInfo ci) {
        if (folkways$resident != null && (owner == null || owner.equals(folkways$resident))) {
            HoseSessions.disconnect((FireHydrantCabinetBlockEntity) (Object) this);
        }
    }

    // A live resident connection is a task, not a player binding to resurrect after a reload or a crash.
    @Inject(method = "saveAdditional", at = @At("RETURN"), remap = true)
    private void folkways$save(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (folkways$resident != null && folkways$resident.equals(boundPlayer)) {
            tag.remove("BoundPlayer");
        }
    }
}
