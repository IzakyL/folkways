package io.github.izakyl.folkways.core.shell.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.task.TaskSnapshot;
import io.github.izakyl.folkways.core.shell.rs2.TaskSnapshots;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "com.refinedmods.refinedstorage.common.autocrafting.autocrafter.TaskSnapshotPersistence",
    remap = false)
public abstract class Rs2TaskSnapshotMixin {

    @Unique
    private static final boolean folkways$codec = TaskSnapshots.install(
        Rs2TaskSnapshotMixin::encodeSnapshot, Rs2TaskSnapshotMixin::decodeSnapshot);

    @Shadow
    private static CompoundTag encodeSnapshot(TaskSnapshot snapshot) {
        throw new AssertionError();
    }

    @Shadow
    private static TaskSnapshot decodeSnapshot(CompoundTag data) {
        throw new AssertionError();
    }
}
