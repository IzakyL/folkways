package io.github.izakyl.folkways.plugins.build.mixin;

import com.simibubi.create.content.schematics.client.tools.ISchematicTool;
import com.simibubi.create.content.schematics.client.tools.ToolType;
import com.simibubi.create.foundation.gui.AllIcons;
import io.github.izakyl.folkways.plugins.build.CreateHandoffTool;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ToolType.class)
public abstract class ToolTypeMixin {

    @Unique
    private static ToolType folkways$handOff;

    @Invoker("<init>")
    private static ToolType folkways$new(String name, int ordinal, ISchematicTool tool, AllIcons icon) {
        throw new AssertionError();
    }

    @Unique
    private static ToolType folkways$handOff() {
        if (folkways$handOff == null) {
            folkways$handOff = folkways$new("FOLKWAYS_HAND_OFF", ToolType.values().length,
                CreateHandoffTool.TOOL, CreateHandoffTool.icon());
        }
        return folkways$handOff;
    }

    @Inject(
        method = "getTools(Z)Ljava/util/List;",
        at = @At("RETURN"),
        require = 1)
    private static void folkways$offerHandOff(boolean creative,
            CallbackInfoReturnable<List<ToolType>> tools) {
        tools.getReturnValue().add(folkways$handOff());
    }

    @Inject(
        method = "getDisplayName()Lnet/minecraft/network/chat/MutableComponent;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1)
    private void folkways$displayName(CallbackInfoReturnable<MutableComponent> name) {
        if ((Object) this == folkways$handOff) {
            name.setReturnValue(CreateHandoffTool.displayName());
        }
    }

    @Inject(
        method = "getDescription()Ljava/util/List;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1)
    private void folkways$description(CallbackInfoReturnable<List<Component>> description) {
        if ((Object) this == folkways$handOff) {
            description.setReturnValue(CreateHandoffTool.description());
        }
    }
}
