package io.github.izakyl.folkways.core.front.client.ponder.mixin;

import com.google.common.collect.Multimap;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import java.util.Collection;
import java.util.Map;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Ponder stores boards before world tags exist. Filter its index, tag links and playback at the point of use.
@Pseudo
@Mixin(value = PonderSceneRegistry.class, remap = false)
public abstract class PonderSceneRegistryMixin {

    @Shadow
    @Final
    private Multimap<ResourceLocation, StoryBoardEntry> scenes;

    @Inject(method = "getRegisteredEntries", at = @At("RETURN"), cancellable = true, require = 1)
    private void folkways$visibleEntries(
            CallbackInfoReturnable<Collection<Map.Entry<ResourceLocation, StoryBoardEntry>>> cir) {
        cir.setReturnValue(cir.getReturnValue().stream().filter(entry -> Scenes.available(entry.getValue())).toList());
    }

    @Inject(method = "doScenesExistForId", at = @At("HEAD"), cancellable = true, require = 1)
    private void folkways$visibleComponent(ResourceLocation id, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(scenes.get(id).stream().anyMatch(Scenes::available));
    }

    @ModifyVariable(method = "compile(Ljava/util/Collection;)Ljava/util/List;", at = @At("HEAD"),
        argsOnly = true, require = 1)
    private Collection<StoryBoardEntry> folkways$visiblePlayback(Collection<StoryBoardEntry> entries) {
        return entries.stream().filter(Scenes::available).toList();
    }
}
