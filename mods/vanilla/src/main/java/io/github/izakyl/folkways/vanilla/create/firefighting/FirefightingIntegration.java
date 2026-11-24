package io.github.izakyl.folkways.vanilla.create.firefighting;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.create.firefighting.HoseSessions;
import io.github.izakyl.folkways.core.shell.create.firefighting.Hydrants;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.vanilla.damagecontrol.SuppressionMethods;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public final class FirefightingIntegration {
    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "firefighting");

    private FirefightingIntegration() {
    }

    public static void install(IEventBus bus) {
        Hydrants.onDisconnect(HoseSessions::disconnect);
        var events = NeoForge.EVENT_BUS;
        events.addListener(HoseSessions::tick);
        events.addListener(HoseSessions::died);
        events.addListener(HoseSessions::left);
        events.addListener(HoseSessions::chunkUnloaded);
        events.addListener(HoseSessions::levelUnloaded);
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
