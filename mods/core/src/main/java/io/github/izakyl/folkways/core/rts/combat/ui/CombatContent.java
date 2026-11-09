package io.github.izakyl.folkways.core.rts.combat.ui;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

public final class CombatContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "combat");
    public static final ResourceLocation MODE = ResourceLocation.fromNamespaceAndPath("folkways_core", "rts");
    public static final PageKind PAGE =
        PageKind.boarded(ID, "folkways.page.combat", ResourceLocation.withDefaultNamespace("iron_sword"));

    private CombatContent() {
    }

    public static void install(IEventBus bus) {
        bus.addListener(
            Declaring.class,
            event ->
                event.colony(
                    ID,
                    scope -> {
                        CombatDesk desk = new CombatDesk(scope.colony());
                        scope.share(desk);
                        scope.closed(why -> desk.close());
                    }));
        bus.addListener(
            Registering.class,
            event -> event.enrollment(ID, new Enrollment(List.of(), Schema.none(), List.of(PAGE))));
        bus.addListener(
            RegisteringUi.class,
            event -> {
                event.page(PAGE.id(), CombatPage::new);
                event.mode(new OverheadTools.Mode(MODE, "folkways.overhead.mode.rts", PAGE.icon()));
                event.tool(
                    new OverheadTools.Tool(
                    ID,
                    MODE,
                    PAGE.nameKey(),
                    PAGE.icon(),
                    OverheadTools.Camera.TACTICAL,
                    "folkways.combat.open_commands",
                    () -> Pages.open(PAGE.id())));
            });
    }
}
