package io.github.izakyl.folkways.front.engine;

import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.charter.FtbTeams;
import io.github.izakyl.folkways.front.engine.charter.Teams;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.item.BookLookup;
import io.github.izakyl.folkways.front.engine.item.FilterStackCodec;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import io.github.izakyl.folkways.front.ui.screen.PageKinds;
import java.util.Optional;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

public final class Front {

    private Front() {
    }

    public static void install(IEventBus modBus) {
        Fronts.install(ColonyFront::of);
        KitRules.install(colony -> ColonyFront.of(colony).toolRules());
        NeoForge.EVENT_BUS.addListener(
            (TagsUpdatedEvent event) -> ColonyFront.toolsReloaded());
        Pages.install(player -> ColonyAuthority.of(player, Optional.empty()).map(ColonyAuthority::colony));
        FilterStacks.install(new FilterStackCodec());
        FrontLifecycle.install();
        BookLookup.install();
        if (ModList.get().isLoaded(FtbTeams.MOD_ID)) {
            Teams.install(new FtbTeams());
        }
        FolkwaysDataComponents.register(modBus);
        FolkwaysItems.register(modBus);
        FolkwaysPlayerAttachments.register(modBus);
        ColonyShell.register();
        modBus.addListener(RegisteringUi.class, PageKinds::registerUi);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(FolkwaysItems.COLONY_BOOK.get());
            }
        });
    }
}
