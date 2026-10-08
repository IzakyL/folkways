package io.github.izakyl.folkways.front.engine;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.item.BookLookup;
import io.github.izakyl.folkways.front.engine.item.FilterStackCodec;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysRecipes;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import io.github.izakyl.folkways.front.ui.screen.ItemListEditor;
import io.github.izakyl.folkways.front.ui.screen.PageKinds;
import io.github.izakyl.folkways.front.ui.screen.SettingsPage;
import java.util.Optional;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

public final class Front {

    private Front() {
    }

    // The colony's tool list, from its settings page; a tool off the list is not taken up.
    private static boolean toolAllowed(Colony colony, Item tool) {
        return ColonyFront.of(colony).settings(CoreSettings.CORE).items(CoreSettings.TOOL_KEY)
            .map(allowed -> Goods.matches(new ItemStack(tool), allowed))
            .orElse(false);
    }

    public static void install(IEventBus modBus) {
        Fronts.install(ColonyFront::of);
        KitRules.install(Front::toolAllowed);
        Pages.install(player -> ColonyAuthority.of(player, Optional.empty()).map(ColonyAuthority::colony));
        FilterStacks.install(new FilterStackCodec());
        FrontLifecycle.install();
        BookLookup.install();
        FolkwaysDataComponents.register(modBus);
        FolkwaysItems.register(modBus);
        FolkwaysPlayerAttachments.register(modBus);
        FolkwaysRecipes.register(modBus);
        ColonyShell.register();
        SettingsPage.install(ItemListEditor::open);
        modBus.addListener(RegisteringUi.class, PageKinds::registerUi);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(FolkwaysItems.COLONY_BOOK.get());
            }
        });
    }
}
