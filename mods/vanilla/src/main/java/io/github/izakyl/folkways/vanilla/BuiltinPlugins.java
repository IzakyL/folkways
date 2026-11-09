package io.github.izakyl.folkways.vanilla;

import io.github.izakyl.folkways.vanilla.build.BuildPlugin;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlPlugin;
import io.github.izakyl.folkways.vanilla.farming.FarmingPlugin;
import io.github.izakyl.folkways.vanilla.fishing.FishingPlugin;
import io.github.izakyl.folkways.vanilla.groundwork.GroundworkPlugin;
import io.github.izakyl.folkways.vanilla.orders.OrdersPlugin;
import io.github.izakyl.folkways.vanilla.pasture.PasturePlugin;
import io.github.izakyl.folkways.vanilla.patrol.PatrolPlugin;
import io.github.izakyl.folkways.vanilla.person.PersonPlugin;
import io.github.izakyl.folkways.vanilla.wares.WaresPlugin;
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
        io.github.izakyl.folkways.vanilla.compost.CompostContent.install(modBus);
        PasturePlugin.install(modBus);
        FishingPlugin.install(modBus);
        GroundworkPlugin.install(modBus);
        PatrolPlugin.install(modBus);
        DamageControlPlugin.install(modBus);
        OrdersPlugin.install(modBus);
        io.github.izakyl.folkways.vanilla.supply.SupplyContent.install(modBus);
    }
}
