package io.github.izakyl.folkways.core.shell.rs2;

import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.shell.crafting.NetworkExtensions;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

final class Rs2NetworkService {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "rs2_network_service");

    private Rs2NetworkService() {
    }

    static void install(IEventBus bus) {
        Rs2Integration.ACCESS.register(Providers::new);
        NetworkExtensions.externalProvider(ColonyProvider.class);
        bus.addListener(TerminalRecipes::register);
        bus.addListener(Declaring.class, event -> PackKeeps.add((colony, body) -> Rs2Integration.attachment(colony, Providers.class)
            .map(providers -> providers.keeps(body.id())).orElse(List.of())));
        bus.addListener(Registering.class, event -> {
            event.enrollment(OWNER, new Enrollment(List.of(), Schema.none()));
            event.panel(OWNER, colony ->
                Rs2Integration.attachment(colony, Providers.class).map(providers -> providers));
        });
    }
}
