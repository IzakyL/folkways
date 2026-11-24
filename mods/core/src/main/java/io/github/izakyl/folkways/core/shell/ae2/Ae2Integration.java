package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.GridServices;
import appeng.api.networking.IGrid;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.facility.api.ExternalAttachments;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.item.api.BlockNames;
import io.github.izakyl.folkways.core.shell.crafting.NetworkExtensions;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class Ae2Integration {

    static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_core", "ae2_network");

    static final NetworkExtensions<NetworkPort, IGrid> ACCESS = new NetworkExtensions<>();

    public static <T extends NetworkExtensions.Attachment<NetworkPort, IGrid>> Optional<T> attachment(
            Colony colony, Class<T> type) {
        return colony.service(OWNER, NetworkPresence.class).flatMap(NetworkPresence::attachment)
            .filter(type::isInstance).map(type::cast);
    }

    private Ae2Integration() {
    }

    public static void install(IEventBus bus) {
        GridServices.register(AttachmentGridService.class, AttachmentGridService.class);
        BlockNames.picture(Ae2Devices::terminal);
        Ae2NetworkService.install(bus);
        NetworkPanel.side(colony -> colony.service(OWNER, NetworkPresence.class).map(NetworkPresence::panel));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> Tally.stopped(event.getServer()));
        bus.addListener(Declaring.class, event -> {
            Ports.registerItem(OWNER, Ports.SPECIFIC, (level, pos) -> Ae2Devices.at(level, pos).map(port -> port));
            ExternalAttachments.register(new ExternalStorage());
            StoreAccess.registerLocalRules((level, pos) -> Ae2Devices.at(level, pos).isPresent());
            event.colony(OWNER, scope -> {
                NetworkPresence presence = new NetworkPresence(scope.colony());
                scope.share(presence);
                scope.tick(presence::tick);
                scope.closed(why -> presence.close());
            });
        });
    }
}
