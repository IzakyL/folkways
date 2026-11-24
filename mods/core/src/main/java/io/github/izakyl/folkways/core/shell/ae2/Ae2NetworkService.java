package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.shell.crafting.NetworkExtensions;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

final class Ae2NetworkService {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "ae2_network_service");

    private Ae2NetworkService() {
    }

    static void install(IEventBus bus) {
        Ae2Integration.ACCESS.register(Providers::new);
        NetworkExtensions.externalProvider(ColonyProvider.class);
        NetworkExtensions.externalPattern(IPatternDetails.class, ColonyPattern::isColonyPattern);
        bus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, event ->
            event.enqueueWork(() -> PatternDetailsHelper.registerDecoder(ColonyPattern.DECODER)));
        bus.addListener(Declaring.class, event -> PackKeeps.add((colony, body) -> Ae2Integration.attachment(colony, Providers.class)
            .map(providers -> providers.keeps(body.id())).orElse(List.of())));
        bus.addListener(Registering.class, event -> {
            event.enrollment(OWNER, new Enrollment(List.of(), Schema.none()));
            event.panel(OWNER, colony ->
                Ae2Integration.attachment(colony, Providers.class).map(providers -> providers));
        });
    }
}
