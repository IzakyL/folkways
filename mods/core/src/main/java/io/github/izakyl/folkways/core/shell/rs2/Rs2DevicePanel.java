package io.github.izakyl.folkways.core.shell.rs2;

import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// The colony's Refined Storage devices as the network panel shows them; networks come from the presence's last sweep.
final class Rs2DevicePanel implements NetworkPanel.Side {

    private final Colony colony;
    private final NetworkPresence presence;

    Rs2DevicePanel(Colony colony, NetworkPresence presence) {
        this.colony = colony;
        this.presence = presence;
    }

    @Override
    public Optional<NetworkPanel.Probe> probe(ServerLevel level, BlockPos at) {
        WorldPos where = WorldPos.of(level, at);
        if (!level.isLoaded(at) || !colony.storesIn(level.dimension()).contains(where)) {
            return Optional.empty();
        }
        return Rs2Devices.at(level, at).filter(port -> !port.buffer()).map(port -> new NetworkPanel.Probe(where,
            icon(level, at), new NetworkPanel.Ceiling(port.operations().contains(ItemPort.Operation.PUT),
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

    private static ItemStack icon(ServerLevel level, BlockPos at) {
        return new ItemStack(level.getBlockState(at).getBlock());
    }
}
