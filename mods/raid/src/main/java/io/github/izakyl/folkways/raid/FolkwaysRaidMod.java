package io.github.izakyl.folkways.raid;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyLifecycle;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(FolkwaysRaidMod.ID)
public final class FolkwaysRaidMod {

    public static final String ID = "folkways_raid";
    public static final ResourceLocation RAIDS = ResourceLocation.fromNamespaceAndPath("folkways", "raids");

    public FolkwaysRaidMod(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, RaidConfig.SPEC);
        bus.addListener(
            Declaring.class,
            event ->
            event.colony(
            RAIDS,
            scope -> {
                RaidDirector director = new RaidDirector(scope.colony(), scope.server());
                scope.share(director);
                scope.closed(
                    why -> {
                        if (why != Closing.SHUTDOWN) {
                            director.stop();
                        }
                        director.close();
                    });
            }));
        var page = PageKind.boarded(RAIDS, "folkways.page.raids", ResourceLocation.withDefaultNamespace("shield"));
        bus.addListener(
            Registering.class,
            event -> event.enrollment(RAIDS, new Enrollment(List.of(), Schema.none(), List.of(page))));
        bus.addListener(RegisteringUi.class, event -> event.page(RAIDS, RaidPage::new));
        ColonyLifecycle.listen(
            new ColonyLifecycle.Listener() {

                @Override
                public Runnable relocating(MinecraftServer server, Colony colony, Map<WorldPos, WorldPos> moved) {
                    return () -> RaidDirector.of(colony).ifPresent(RaidDirector::stop);
                }
            });
    }
}
