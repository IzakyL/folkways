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
            scope.closed(why -> director.close());
        }));
    }
}
