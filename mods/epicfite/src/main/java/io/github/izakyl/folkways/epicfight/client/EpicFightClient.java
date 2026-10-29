package io.github.izakyl.folkways.epicfight.client;

import io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;

@OnlyIn(Dist.CLIENT)
public final class EpicFightClient {
    private EpicFightClient() {
    }

    public static void install(IEventBus modBus) {
        ResidentEpicFightPatch.renderingWhen(EpicFightClient::shouldOverride);
        modBus.addListener(EpicFightClient::registerReloadListener);
        if (ModList.get().isLoaded("geckolib")) {
            ResidentGeoEventBridge.install();
        }
        EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(event ->
            event.addPatchedEntityRenderer(PersonBody.RESIDENT.get(),
                type -> new ResidentEpicFightRenderer(event.getContext(), type)), "folkways_epicfight:resident");
    }

    /** Combat always draws the armature that owns its hit frames, including a model's explicit fallback skin. */
    public static boolean shouldOverride(ResidentEntity resident) {
        ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(resident);
        if (patch != null && patch.actionOwnsPose()) {
            return true;
        }
        // Existing work, leisure, fishing and riding presentations have their own authored poses.
        if (resident.swinging || !resident.leisureMotion().isEmpty() || resident.castLine().isPresent()
                || resident.isPassenger()) {
            return false;
        }
        return ResidentLooks.of(resident).appearance() instanceof ResidentLook.Skin || ResidentGeoMeshes.supports(resident);
    }

    private static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> {
            ResidentGeoMeshes.clear();
            ResidentRenderEvidence.clear();
        });
    }
}
