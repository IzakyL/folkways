package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.item.api.BlockNames;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class Ae2Integration {

    static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_core", "ae2_network");

    private Ae2Integration() {
    }

    public static void install(IEventBus bus) {
        BlockNames.picture(Devices::terminal);
        NetworkPanel.side(colony -> colony.service(OWNER, NetworkPresence.class).map(NetworkPresence::panel));
        bus.addListener(Registering.class, event ->
            event.facing(OWNER, colony -> colony.service(OWNER, NetworkPresence.class).map(value -> value)));
        bus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, event ->
            event.enqueueWork(() -> PatternDetailsHelper.registerDecoder(ColonyPattern.DECODER)));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> Tally.stopped(event.getServer()));
        bus.addListener(Declaring.class, event -> {
            PackKeeps.add((colony, body) ->
                colony.service(OWNER, NetworkPresence.class).map(presence -> presence.keeps(body.id()))
                    .orElse(List.of()));
            StorePorts.register(OWNER, StorePorts.SPECIFIC, (level, pos) -> Devices.at(level, pos).map(port -> port));
            event.colony(OWNER, scope -> {
                NetworkPresence presence = new NetworkPresence(scope.colony());
                scope.share(presence);
                scope.tick(presence::tick);
                scope.closed(why -> presence.close());
            });
        });
    }
}
