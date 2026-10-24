package io.github.izakyl.folkways.war;

import io.github.izakyl.folkways.core.api.Declaring;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(FolkwaysWarMod.ID)
public final class FolkwaysWarMod {
    public static final String ID = "folkways_war";
    public static final ResourceLocation RAIDS = ResourceLocation.fromNamespaceAndPath("folkways", "raids");

    public FolkwaysWarMod(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, WarConfig.SPEC);
        bus.addListener(Declaring.class, event -> event.colony(RAIDS, scope -> {
            RaidDirector director = new RaidDirector(scope.colony(), scope.server());
            scope.share(director);
            scope.closed(why -> {
                if (why != io.github.izakyl.folkways.core.api.colony.Closing.SHUTDOWN) { director.stop(); }
                director.close();
            });
        }));
        var page = io.github.izakyl.folkways.front.api.panel.PageKind.boarded(RAIDS, "folkways.page.raids", ResourceLocation.withDefaultNamespace("shield"));
        bus.addListener(io.github.izakyl.folkways.front.api.Registering.class, event -> event.enrollment(RAIDS,
            new io.github.izakyl.folkways.front.api.Enrollment(java.util.List.of(), io.github.izakyl.folkways.front.api.Schema.none(), java.util.List.of(page))));
        bus.addListener(io.github.izakyl.folkways.front.api.ui.RegisteringUi.class, event -> event.page(RAIDS, RaidPage::new));
        io.github.izakyl.folkways.core.api.colony.ColonyLifecycle.listen(new io.github.izakyl.folkways.core.api.colony.ColonyLifecycle.Listener() {
            @Override public Runnable relocating(net.minecraft.server.MinecraftServer server, io.github.izakyl.folkways.core.api.colony.Colony colony,
                    java.util.Map<io.github.izakyl.folkways.core.api.terms.WorldPos, io.github.izakyl.folkways.core.api.terms.WorldPos> moved) {
                return () -> RaidDirector.of(colony).ifPresent(RaidDirector::stop);
            }
        });
    }
}
