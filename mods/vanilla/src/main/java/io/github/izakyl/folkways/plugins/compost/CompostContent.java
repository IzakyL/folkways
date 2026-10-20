package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;

/** Native composters retain the existing delegation id even when Cookery is absent. */
public final class CompostContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "compost");
    public static final ResourceLocation COMPOSTING = ResourceLocation.fromNamespaceAndPath("folkways", "composting");

    private CompostContent() { }

    public static void install(IEventBus bus) {
        bus.addListener(Declaring.class, CompostContent::declare);
        bus.addListener(Registering.class, CompostContent::enroll);
    }

    private static void declare(Declaring event) {
        event.colony(ID, scope -> {
            CompostPresence presence = new CompostPresence(scope.colony(), scope.server());
            scope.share(presence);
            scope.watch(ID, presence);
            scope.tick(presence::tick);
            scope.closed(presence::closed);
        });
    }

    private static void enroll(Registering event) {
        event.facing(ID, colony -> colony.service(ID, CompostPresence.class).map(state -> state));
        event.enrollment(ID, new Enrollment(List.of(new Delegation(ID,
            new Shape.Point(state -> state.is(Blocks.COMPOSTER)), ResourceLocation.withDefaultNamespace("composter"))),
            Schema.none(), List.of(PageKind.boarded(ID, "folkways.page.compost", ResourceLocation.withDefaultNamespace("composter")))));
    }
}
