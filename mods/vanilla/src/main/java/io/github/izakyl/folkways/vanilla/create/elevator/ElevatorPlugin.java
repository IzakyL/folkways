package io.github.izakyl.folkways.vanilla.create.elevator;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.shell.create.elevator.CabinMovement;
import io.github.izakyl.folkways.core.shell.create.elevator.CreateElevatorNetwork;
import io.github.izakyl.folkways.core.shell.create.elevator.ElevatorOutlines;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentNodeEvaluator;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class ElevatorPlugin {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "elevator");
    public static final ResourceLocation BOARDING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "elevator_boarding");
    public static final ResourceLocation RIDING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "elevator_riding");

    private ElevatorPlugin() {
    }

    public static void install(IEventBus modBus) {
        CabinMovement.install(CabinMovement.navigation(ResidentNodeEvaluator::new));
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            modBus.addListener(ElevatorScene::declare);
            NeoForge.EVENT_BUS.addListener(ElevatorOutlines::render);
        }
        modBus.addListener(Declaring.class, event -> {
            event.passage(new ElevatorPassage());
            event.colony(ID, scope -> {
                Elevators state = new Elevators(scope.colony());
                scope.share(state);
                scope.tick(state::tick);
                scope.closed(state::closed);
            });
        });
        // A pulley is delegated with the same book gesture as a bed, workstation or postbox.
        modBus.addListener(Registering.class, event -> event.enrollment(ID, new Enrollment(List.of(
            new Delegation(ID, new Shape.Block(CreateElevatorNetwork::isPulley, CreateElevatorNetwork::mainPulley),
                ResourceLocation.fromNamespaceAndPath("create", "elevator_pulley"))))));
        NeoForge.EVENT_BUS.addListener(ElevatorDispatch::tick);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class,
            event -> ElevatorDispatch.forget(event.getServer()));
    }
}
