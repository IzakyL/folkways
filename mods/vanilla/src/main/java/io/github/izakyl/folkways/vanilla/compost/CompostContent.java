package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;

public final class CompostContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "composting");
    // Keep the original declaration key so existing marked composters remain registered.
    public static final ResourceLocation POINT = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "compost");
    public static final PageKind PAGE = PageKind.boarded(CompostSettings.ID, "folkways.page.compost_settings",
        ResourceLocation.withDefaultNamespace("composter"));

    private CompostContent() { }

    public static void install(IEventBus modBus) {
        modBus.addListener(Declaring.class, CompostContent::declare);
        modBus.addListener(Registering.class, CompostContent::enroll);
        modBus.addListener(RegisteringUi.class, event -> event.page(PAGE.id(), CompostPage::new));
    }

    private static void declare(Declaring event) {
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
        event.facing(ID, colony -> colony.service(ID, CompostPresence.class).map(state -> state));
        event.enrollment(ID, new Enrollment(List.of(new Delegation(POINT,
            new Shape.Point(state -> state.is(Blocks.COMPOSTER)), ResourceLocation.withDefaultNamespace("composter"))),
            Schema.none(), List.of(PAGE)));
    }
}
