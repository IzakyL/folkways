package io.github.izakyl.folkways.core.rts.combat.ui;

import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

/** Registers the RTS mode and its tactical controls. */
public final class CombatContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "combat");
    public static final ResourceLocation MODE = ResourceLocation.fromNamespaceAndPath("folkways_core", "rts");

    private CombatContent() {
    }

    public static void install(IEventBus bus) {
        bus.addListener(
            RegisteringUi.class,
            event -> {
                ResourceLocation icon = ResourceLocation.withDefaultNamespace("iron_sword");
                event.mode(new OverheadTools.Mode(MODE, "folkways.overhead.mode.rts", icon));
                event.tool(new OverheadTools.Tool(ID, MODE, "folkways.overhead.mode.rts", icon,
                    OverheadTools.Camera.TACTICAL));
            });
    }
}
