package io.github.izakyl.folkways.core.shell.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import io.github.izakyl.folkways.core.shell.crafting.NetworkTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl", remap = false)
public abstract class Rs2CommissionMixin {

    @Shadow
    public abstract TaskId getId();

    @WrapMethod(method = "step")
    private boolean folkways$commission(RootStorage storage, ExternalPatternSinkProvider sinks, StepBehavior behavior,
                                        TaskListener listener, Operation<Boolean> original) {
        try (var scope = new NetworkTask(getId().id())) {
            return original.call(storage, sinks, behavior, listener);
        }
    }
}
