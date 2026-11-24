package io.github.izakyl.folkways.core.shell.create;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import io.github.izakyl.folkways.core.shell.create.build.CreateSchematicPlacement;
import io.github.izakyl.folkways.core.shell.create.build.CreateTrack;
import io.github.izakyl.folkways.core.shell.create.dispatch.CreatePackageNetwork;
import io.github.izakyl.folkways.core.shell.create.dispatch.PackageNetworks;
import io.github.izakyl.folkways.core.shell.create.elevator.ElevatorShafts;
import io.github.izakyl.folkways.core.shell.create.actuator.CreateActuators;
import io.github.izakyl.folkways.core.shell.create.control.ControlHub;
import io.github.izakyl.folkways.core.shell.create.kinetic.CreateCranks;
import io.github.izakyl.folkways.core.shell.create.kinetic.CreateFans;
import io.github.izakyl.folkways.core.shell.create.kinetic.CreateMechanisms;
import io.github.izakyl.folkways.core.shell.create.firefighting.ResidentSprayVisuals;
import io.github.izakyl.folkways.core.shell.create.rail.RailCargo;
import io.github.izakyl.folkways.core.shell.create.rail.RailDrive;
import io.github.izakyl.folkways.core.shell.create.rail.domain.CreateTransitNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.Departures;
import io.github.izakyl.folkways.core.shell.create.rail.passage.Ridership;
import io.github.izakyl.folkways.core.shell.create.rail.domain.Trackways;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetworks;
import io.github.izakyl.folkways.core.shell.create.rail.passage.RailPassage;
import io.github.izakyl.folkways.core.work.api.BlockSeats;
import io.github.izakyl.folkways.core.work.api.SchematicPrinter;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class CreateIntegration {
    public static final String CONFIG = "folkways_core-create-server.toml";

    private CreateIntegration() {
    }

    public static void install(IEventBus bus, ModContainer container) {
        RailCargo.install();
        RailDrive.install();
        container.registerConfig(ModConfig.Type.SERVER, FolkwaysCreateConfig.SPEC, CONFIG);
        CreateTrack.install();
        ElevatorShafts.install();
        CreateActuators.install();
        CreateCranks.install();
        CreateMechanisms.install();
        CreateFans.install();
        ControlHub.install(bus);
        SchematicPrinter.install(new CreateSchematicPlacement());
        bus.addListener(Declaring.class, event -> event.passage(new RailPassage()));
        bus.addListener(FMLCommonSetupEvent.class, event ->
            event.enqueueWork(() -> Keepouts.install(Trackways.class, new Trackways())));
        var events = NeoForge.EVENT_BUS;
        CreateTransitNetwork transit = new CreateTransitNetwork();
        TransitNetworks.install(transit);
        events.addListener(transit::tick);
        events.addListener(Ridership::recoverPassengers);
        events.addListener(ServerStoppedEvent.class, event -> {
            transit.forgetServer();
            Departures.forgetServer();
        });
        CreatePackageNetwork packages = new CreatePackageNetwork();
        PackageNetworks.install(packages);
        BlockSeats.install(PackageNetworks::get);
        events.addListener(packages::tick);
        events.addListener(ServerStoppedEvent.class, event -> packages.forgetServer());
        if (OptionalIntegrations.FIREFIGHTING.enabled() && FMLEnvironment.dist.isClient()) {
            ResidentSprayVisuals.install();
        }
    }
}
