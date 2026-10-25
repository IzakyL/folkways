package io.github.izakyl.folkways.epicfight.client;

import io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.ResidentGeoBackend;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import software.bernie.geckolib.event.GeoRenderEvent;
import yesman.epicfight.client.events.engine.RenderEngine;

/** Loaded only when GeckoLib is present. Its replaced-entity event is separate from GeoEntity's event. */
@OnlyIn(Dist.CLIENT)
final class ResidentGeoEventBridge {
    private ResidentGeoEventBridge() {
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener(ResidentGeoEventBridge::beforeReplacedRender);
    }

    private static void beforeReplacedRender(GeoRenderEvent.ReplacedEntity.Pre event) {
        if (!(event.getReplacedEntity() instanceof ResidentEntity resident) || resident.level() == null
                || event.isCanceled()) {
            return;
        }
        ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(resident);
        RenderEngine engine = RenderEngine.getInstance();
        if (patch == null || !patch.overrideRender() || !engine.hasRendererFor(resident)) {
            return;
        }
        // The replacement renderer delegates to an EF mesh renderer, which never calls the Geo renderer.
        // Cancelling after it succeeds prevents the original Geo geometry from being drawn a second time.
        // GeckoLib fires Pre after preRender; ResidentGeoBackend has already applied model.scale.
        // Converted vertices contain that scale, while a fallback skin must keep BIPED's size.
        float inverse = event.getRenderer() instanceof ResidentGeoBackend
                && ResidentLooks.of(resident).appearance() instanceof ResidentLook.Model model
            ? 1.0F / model.scale() : 1.0F;
        if (!Float.isFinite(inverse) || inverse <= 0) {
            return;
        }
        event.getPoseStack().pushPose();
        try {
            event.getPoseStack().scale(inverse, inverse, inverse);
            engine.renderEntityArmatureModel(resident, patch, event.getRenderer(), event.getBufferSource(),
                event.getPoseStack(), event.getPackedLight(), event.getPartialTick());
        } finally {
            event.getPoseStack().popPose();
        }
        event.setCanceled(true);
    }
}
