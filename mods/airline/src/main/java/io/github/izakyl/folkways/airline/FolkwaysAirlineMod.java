package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.combat.api.AirportTraffic;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(FolkwaysAirlineMod.ID)
public final class FolkwaysAirlineMod {
    public static final String ID = "folkways_airline";

    public FolkwaysAirlineMod(IEventBus bus, ModContainer container) {
        AirlineContent.register(bus);
        LandingSites.register(AirportLandingSites::sites);
        LandingSites.registerAirports((level, id) -> AirportRegistry.of(level).registered(id));
        AirportTraffic.register(new AirportBookings());
        NeoForge.EVENT_BUS.addListener(AirTraffic::unload);
        NeoForge.EVENT_BUS.addListener(AirTraffic::stop);
        bus.addListener(Registering.class, AirportColony::enroll);
        bus.addListener(RegisteringUi.class, event -> event.page(AirportColony.ID, AirportPage::new));
        NeoForge.EVENT_BUS.addListener(Airports::tick);
        NeoForge.EVENT_BUS.addListener(Airports::stopped);
    }
}
