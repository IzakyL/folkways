package io.github.izakyl.folkways.plugins;

import io.github.izakyl.folkways.plugins.build.BuildPlugin;
import io.github.izakyl.folkways.plugins.wares.WaresPlugin;
import io.github.izakyl.folkways.plugins.dispatch.DispatchPlugin;
import io.github.izakyl.folkways.plugins.farming.FarmingPlugin;
import io.github.izakyl.folkways.plugins.fishing.FishingPlugin;
import io.github.izakyl.folkways.plugins.golem.GolemPlugin;
import io.github.izakyl.folkways.plugins.orders.OrdersPlugin;
import io.github.izakyl.folkways.plugins.pasture.PasturePlugin;
import io.github.izakyl.folkways.plugins.patrol.PatrolPlugin;
import io.github.izakyl.folkways.plugins.person.PersonPlugin;
import io.github.izakyl.folkways.plugins.rail.RailPlugin;
import io.github.izakyl.folkways.plugins.sable.SablePlugin;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

// Every built-in plugin comes in through its one install, as a plugin from another mod would.
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    public static void install(IEventBus modBus) {
        PersonPlugin.install(modBus);
        WaresPlugin.install(modBus);
        BuildPlugin.install(modBus);
        FarmingPlugin.install(modBus);
        PasturePlugin.install(modBus);
        FishingPlugin.install(modBus);
        PatrolPlugin.install(modBus);
        OrdersPlugin.install(modBus);
        RailPlugin.install(modBus);
        DispatchPlugin.install(modBus);
        if (ModList.get().isLoaded("sable")) {
            SablePlugin.install(modBus);
        }
        if (ModList.get().isLoaded("modulargolems")) {
            GolemPlugin.install(modBus);
        }
    }
}
