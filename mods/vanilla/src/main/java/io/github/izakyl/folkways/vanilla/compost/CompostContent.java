package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.vanilla.farming.FarmingContent;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public final class CompostContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "composting");
    // Keep the original declaration key so existing marked composters remain registered.
    public static final ResourceLocation POINT = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "compost");
    private CompostContent() { }

    public static void install(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(DefinedFacilities.CoverageChanged.class, change ->
            Colonies.all(change.server()).forEach(colony -> colony.service(ID, CompostPresence.class)
                .ifPresent(presence -> presence.coverageChanged(change.server()))));
        modBus.addListener(Declaring.class, CompostContent::declare);
        modBus.addListener(Registering.class, CompostContent::enroll);
    }

    private static void declare(Declaring event) {
        CompostPrefab.register();
        Mechanics.station(Mechanics.Station.block(Blocks.COMPOSTER, POINT, FarmingContent.VOCATION,
                List.of(), List.of(Component.translatable("folkways.mechanics.compost"))));
        event.colony(ID, scope -> {
            SeedSupport seeds = new SeedSupport(scope.colony(), scope.server());
            CompostPresence state = new CompostPresence(scope.colony(), scope.server(), seeds);
            scope.share(state);
            scope.watch(POINT, state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    private static void enroll(Registering event) {
        event.holding(POINT, colony -> colony.service(ID, CompostPresence.class).map(state -> state));
        event.enrollment(ID, new Enrollment(List.of(new Delegation(POINT,
            new Shape.Point(state -> state.is(Blocks.COMPOSTER)), ResourceLocation.withDefaultNamespace("composter"))),
            CompostSettings.SCHEMA));
        event.panel(ID, colony -> Optional.of(new SeedReservePanel(colony)));
    }
}
