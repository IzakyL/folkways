package io.github.izakyl.folkways.front.client.ponder.mixin;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.AbstractPonderScreen;
import net.createmod.ponder.foundation.ui.PonderUI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Ponder lifts its own buttons above the scene but leaves the one that steps back out of the lesson on the floor: a
// scene as wide as ours, on a large interface, drew its ground over that button and the way out was gone. A lesson
// of ours stands it with the others.
@Pseudo
@Mixin(value = PonderUI.class, remap = false)
public abstract class PonderUIMixin extends AbstractPonderScreen {

    // Where Ponder stands its own buttons.
    private static final float BUTTONS = 420.0F;

    @Shadow
    @Final
    private List<PonderScene> scenes;

    @Inject(method = "init", at = @At("TAIL"))
    private void folkways$backAboveScene(CallbackInfo ci) {
        if (backTrack != null && !scenes.isEmpty()
                && FolkwaysMod.MOD_ID.equals(scenes.get(0).getNamespace())) {
            backTrack.atZLevel(BUTTONS);
        }
    }
}
