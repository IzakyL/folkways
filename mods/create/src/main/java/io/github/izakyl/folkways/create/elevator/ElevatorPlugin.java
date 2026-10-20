package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class ElevatorPlugin {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "elevator");
    public static final ResourceLocation BOARDING = ResourceLocation.fromNamespaceAndPath("folkways", "elevator_boarding");
    public static final ResourceLocation RIDING = ResourceLocation.fromNamespaceAndPath("folkways", "elevator_riding");

    private ElevatorPlugin() {
    }

    public static void install(IEventBus modBus) {
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
            new Delegation(ID, new Shape.Block(state -> state.getBlock() instanceof ElevatorPulleyBlock,
                ElevatorPlugin::mainPulley), ResourceLocation.fromNamespaceAndPath("create", "elevator_pulley"))))));
        modBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(() -> Keepouts.install(ElevatorShafts.INSTANCE)));
        NeoForge.EVENT_BUS.addListener(ElevatorShafts::tick);
        NeoForge.EVENT_BUS.addListener(ElevatorShafts::unload);
        NeoForge.EVENT_BUS.addListener(ElevatorDispatch::tick);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class,
            event -> ElevatorDispatch.forget(event.getServer()));
    }

    static BlockPos mainPulley(Level level, BlockPos clicked) {
        BlockPos main = clicked;
        for (int mirrors = 0; mirrors < 32 && level.hasChunkAt(main)
                && level.getBlockEntity(main) instanceof ElevatorPulleyBlockEntity pulley
                && pulley.getMirrorParent() != null; mirrors++) {
            main = pulley.getMirrorParent();
        }
        return main.immutable();
    }
}
