package io.github.izakyl.folkways.core.shell.ae2;

import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.item.api.BlockNames;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

// The colony's ME devices as the network panel shows them; networks come from the presence's last sweep.
final class Ae2DevicePanel implements NetworkPanel.Side {

    private final Colony colony;
    private final NetworkPresence presence;

    Ae2DevicePanel(Colony colony, NetworkPresence presence) {
        this.colony = colony;
        this.presence = presence;
    }

    @Override
    public Optional<NetworkPanel.Probe> probe(ServerLevel level, BlockPos at) {
        WorldPos where = WorldPos.of(level, at);
        if (!level.isLoaded(at) || !colony.storesIn(level.dimension()).contains(where)) {
            return Optional.empty();
        }
        return Ae2Devices.at(level, at).filter(port -> !port.buffer()).map(port -> new NetworkPanel.Probe(where,
            BlockNames.pictureAt(level, at), new NetworkPanel.Ceiling(port.operations().contains(ItemPort.Operation.PUT),
                port.operations().contains(ItemPort.Operation.TAKE), port.crafts()),
            StoreAccess.usability(level, port) instanceof Usability.Unusable unusable ? Optional.of(unusable.reason())
                : Optional.empty(), Optional.ofNullable(presence.networks.get(where))));
    }

    @Override
    public List<NetworkPanel.Probe> enrolled(ColonyView view) {
        List<NetworkPanel.Probe> found = new ArrayList<>();
        for (WorldPos at : colony.storesIn(view.dimension())) {
            if (at.in(view.level())) {
                probe(view.level(), at.block(view.level())).ifPresent(found::add);
            }
        }
        return found;
    }

    @Override
    public NetworkDeviceSettings settings() {
        return presence.settings;
    }

    @Override
    public void changed(ServerLevel level) {
        presence.changed();
    }
}
