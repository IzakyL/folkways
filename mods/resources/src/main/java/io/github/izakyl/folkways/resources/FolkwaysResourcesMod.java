package io.github.izakyl.folkways.resources;

import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(FolkwaysResourcesMod.ID)
public final class FolkwaysResourcesMod {
    public static final String ID = "folkways_resources";
    public static final ResourceLocation CONTENT = ResourceLocation.fromNamespaceAndPath("folkways", "resources");
    public static final PageKind PAGE = PageKind.boarded(CONTENT, "folkways.page.resources",
        ResourceLocation.fromNamespaceAndPath("folkways", "wood_resource"));

    public FolkwaysResourcesMod(IEventBus modBus) {
        ResourceItems.register(modBus);
        modBus.addListener(Registering.class, event -> event.enrollment(CONTENT,
            new Enrollment(List.of(), Schema.none(), List.of(PAGE))));
        modBus.addListener(RegisteringUi.class, event -> event.page(PAGE.id(), ResourcesPage::new));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) ResourceItems.ALL.forEach(item -> event.accept(item.get()));
        });
        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, event ->
            event.addListener((ResourceManagerReloadListener) ResourceCatalog::reload));
    }
}
