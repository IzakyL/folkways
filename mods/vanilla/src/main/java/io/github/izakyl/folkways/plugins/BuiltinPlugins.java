package io.github.izakyl.folkways.plugins;

import io.github.izakyl.folkways.plugins.build.BuildPlugin;
import io.github.izakyl.folkways.plugins.wares.WaresPlugin;
import io.github.izakyl.folkways.plugins.farming.FarmingPlugin;
import io.github.izakyl.folkways.plugins.fishing.FishingPlugin;
import io.github.izakyl.folkways.plugins.groundwork.GroundworkPlugin;
import io.github.izakyl.folkways.plugins.orders.OrdersPlugin;
import io.github.izakyl.folkways.plugins.pasture.PasturePlugin;
import io.github.izakyl.folkways.plugins.patrol.PatrolPlugin;
import io.github.izakyl.folkways.plugins.person.PersonPlugin;
import net.neoforged.bus.api.IEventBus;

// Every built-in plugin comes in through its one install, as a plugin from another mod would.
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    public static void install(IEventBus modBus) {
        PersonPlugin.install(modBus);
        WaresPlugin.install(modBus);
        BuildPlugin.install(modBus);
        FarmingPlugin.install(modBus);
        io.github.izakyl.folkways.plugins.compost.CompostContent.install(modBus);
        PasturePlugin.install(modBus);
        FishingPlugin.install(modBus);
        GroundworkPlugin.install(modBus);
        PatrolPlugin.install(modBus);
        modBus.addListener(io.github.izakyl.folkways.core.api.Declaring.class,
            io.github.izakyl.folkways.plugins.combat.command.CommandContent::declare);
        modBus.addListener(io.github.izakyl.folkways.core.api.Declaring.class,
            io.github.izakyl.folkways.plugins.combat.group.GroupContent::declare);
        modBus.addListener(io.github.izakyl.folkways.core.api.Declaring.class,
            io.github.izakyl.folkways.plugins.combat.control.SelectionControlContent::declare);
        io.github.izakyl.folkways.plugins.combat.ui.CombatContent.install(modBus);
        OrdersPlugin.install(modBus);
        io.github.izakyl.folkways.plugins.supply.SupplyContent.install(modBus);
    }
}
