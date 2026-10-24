package io.github.izakyl.folkways.chiselsandbits.mixin;

import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.chiselsandbits.CandBSchematicBridge;
import io.github.izakyl.folkways.chiselsandbits.SchematicBits;
import io.github.izakyl.folkways.chiselsandbits.SchematicStatistics;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChiseledBlockEntity.class, remap = false)
abstract class ChiseledBlockEntityMixin extends BlockEntity implements SchematicBits {
    private ChiseledBlockEntityMixin(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
            net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
    private void folkways$schematicLevel(net.minecraft.world.level.Level level, CallbackInfo ci) {
        if (CandBSchematicBridge.schematic(level)) {
            // The printer has no chunk tracker, and its entities must never join the real world tracker.
            super.setLevel(level);
            ci.cancel();
        }
    }
    @Shadow private StateEntryStorage storage;
    @Shadow public abstract void receivePayload(ChiseledBlockEntity.Payload payload);
    @Shadow private Tag lastTag;
    @Unique private boolean folkways$valid;

    @Inject(method = "setChanged", at = @At("HEAD"), cancellable = true)
    private void folkways$noSchematicUpdates(CallbackInfo ci) {
        if (getLevel() != null && CandBSchematicBridge.schematic(getLevel())) {
            ci.cancel();
        }
    }

    @Inject(method = "rotate", at = @At("HEAD"), cancellable = true)
    private void folkways$registryRotation(net.minecraft.core.Direction.Axis axis, int turns, CallbackInfo ci) {
        if (getLevel() != null && CandBSchematicBridge.schematic(getLevel())) ci.cancel();
    }

    @Inject(method = "mirror", at = @At("HEAD"), cancellable = true)
    private void folkways$registryMirror(net.minecraft.core.Direction.Axis axis, CallbackInfo ci) {
        if (getLevel() != null && CandBSchematicBridge.schematic(getLevel())) ci.cancel();
    }

    @Override
    public boolean folkways$valid() {
        return folkways$valid;
    }

    @Inject(method = "loadAdditional", at = @At("HEAD"), cancellable = true)
    private void folkways$readSchematic(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() != null && !CandBSchematicBridge.schematic(self.getLevel())
            && !tag.getBoolean("folkways:schematic")) {
            return;
        }
        folkways$valid = false;
        try {
            storage = CandBStructureCodec.decode(tag);
            var empty = new ChiseledBlockEntity(self.getBlockPos(), self.getBlockState()).payload();
            if (self.getLevel() != null && !CandBSchematicBridge.schematic(self.getLevel())) {
                ((SchematicStatistics) (Object) empty.mutableStatistics())
                    .folkways$recalculate(self.getLevel(), storage, self.getBlockPos());
            }
            receivePayload(new ChiseledBlockEntity.Payload(storage, empty.mutableStatistics()));
            lastTag = CandBStructureCodec.encode(storage).get("data");
            folkways$valid = true;
        } catch (RuntimeException invalid) {
            // Keep the invalid payload so neither printer silently turns it into an empty, free block.
            lastTag = tag.get("data");
        }
        ci.cancel();
    }
}
