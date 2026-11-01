package io.github.izakyl.folkways.efficientconstruction;

import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.plugins.build.EfficientConstruction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

/**
 * Folkways: Efficient Construction - the switch that lets a build site raise itself instead of having residents
 * place every block.
 *
 * <p>This jar holds no building code: it only tells Folkways Vanilla to use the efficient site, and hands it the
 * settings below. Without this jar installed the switch is never flipped and every site is the ordinary one.
 */
@Mod(EfficientConstructionMod.ID)
public final class EfficientConstructionMod {

    public static final String ID = "folkways_efficient_construction";

    private static final Logger LOGGER = LogUtils.getLogger();

    public EfficientConstructionMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, EfficientConstructionConfig.SPEC);
        // Flip the switch now, at the settings' defaults, so a site filed before the config is read still uses it.
        activate(EfficientConstruction.Settings.DEFAULTS);
        modBus.addListener(ModConfigEvent.Loading.class, event -> applyConfig());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> applyConfig());
        NeoForge.EVENT_BUS.addListener(EfficientConstructionCommand::register);
    }

    private static void applyConfig() {
        activate(new EfficientConstruction.Settings(
            EfficientConstructionConfig.TICKS_PER_BLOCK.get(),
            EfficientConstructionConfig.WORKERS_PER_SITE.get(),
            EfficientConstructionConfig.STACKS_PER_TRIP.get(),
            EfficientConstructionConfig.STOW_ON_SITE.get()));
    }

    // A Folkways Vanilla built without the efficient-construction seam cannot be extended this way: say so plainly
    // and leave the ordinary building work in place rather than failing the game's start.
    private static void activate(EfficientConstruction.Settings settings) {
        try {
            EfficientConstruction.activate(settings);
        } catch (LinkageError missing) {
            LOGGER.error("Folkways: Efficient Construction needs a Folkways build that carries the "
                + "efficient-construction seam, and the installed folkways-vanilla does not. "
                + "Building will use the ordinary site.", missing);
        }
    }
}
