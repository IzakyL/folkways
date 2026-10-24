package io.github.izakyl.folkways.firefighting;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionMethods;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(FolkwaysFirefightingMod.ID)
public final class FolkwaysFirefightingMod {
    public static final String ID = "folkways_firefighting";
    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "firefighting");

    public FolkwaysFirefightingMod(IEventBus bus) {
        bus.addListener(Declaring.class, event -> {
            ExtinguisherAdapter method = new ExtinguisherAdapter();
            SuppressionMethods.register(method);
            Vocations.addKit(DamageControlContent.ID,
                ItemSpec.of(ResourceLocation.fromNamespaceAndPath("createfirefightingadd", "fire_extinguisher")));
            event.colony(OWNER, scope -> {
                FirefightingPresence presence = new FirefightingPresence(scope.colony(), method);
                scope.tick(presence::tick);
                scope.closed(presence::closed);
            });
        });
    }
}
