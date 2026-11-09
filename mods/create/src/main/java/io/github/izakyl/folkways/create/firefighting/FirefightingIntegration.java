package io.github.izakyl.folkways.create.firefighting;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.vanilla.damagecontrol.SuppressionMethods;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class FirefightingIntegration {
    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_create", "firefighting");

    private FirefightingIntegration() {
    }

    public static void install(IEventBus bus) {
        bus.addListener(RegisterPayloadHandlersEvent.class, ResidentSprayPacket::register);
        var events = NeoForge.EVENT_BUS;
        events.addListener(HoseSessions::tick);
        events.addListener(HoseSessions::died);
        events.addListener(HoseSessions::left);
        events.addListener(HoseSessions::chunkUnloaded);
        events.addListener(HoseSessions::levelUnloaded);
        if (FMLEnvironment.dist.isClient()) {
            ResidentSprayVisuals.install();
        }
        bus.addListener(Declaring.class, event -> {
            ExtinguisherAdapter method = new ExtinguisherAdapter();
            SuppressionMethods.register(method);
            HoseAdapter hose = new HoseAdapter();
            SuppressionMethods.register(hose);
            Vocations.addKit(DamageControlContent.ID,
                ItemSpec.of(ResourceLocation.fromNamespaceAndPath("createfirefightingadd", "fire_extinguisher")));
            event.colony(OWNER, scope -> {
                FirefightingPresence presence = new FirefightingPresence(scope.colony(), method);
                scope.tick(presence::tick);
                scope.closed(why -> {
                    presence.closed(why);
                    hose.forget(scope.colony().id());
                });
            });
        });
    }
}
