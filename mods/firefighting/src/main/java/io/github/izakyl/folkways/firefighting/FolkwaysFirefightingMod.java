package io.github.izakyl.folkways.firefighting;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionMethods;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(FolkwaysFirefightingMod.ID)
public final class FolkwaysFirefightingMod {
    public static final String ID = "folkways_firefighting";
    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "firefighting");

    public FolkwaysFirefightingMod(IEventBus bus) {
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
