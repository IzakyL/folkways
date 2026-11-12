package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.NetworkNodeActor;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.grid.AbstractGridBlockEntity;
import com.refinedmods.refinedstorage.common.iface.InterfaceBlockEntity;
import com.refinedmods.refinedstorage.common.storage.diskinterface.AbstractDiskInterfaceBlockEntity;
import com.refinedmods.refinedstorage.common.storagemonitor.StorageMonitorBlockEntity;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

final class Devices {

    static final TagKey<Block> TAKE = tag("take");
    static final TagKey<Block> PUT = tag("put");
    static final TagKey<Block> CRAFT = tag("craft");

    private Devices() {
    }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "network_access/" + name));
    }

    static Optional<NetworkPort> at(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        // These buffers replenish, drain or transform their own contents. Never expose their item handlers.
        if (entity instanceof InterfaceBlockEntity || entity instanceof AbstractDiskInterfaceBlockEntity) {
            return Optional.of(new NetworkPort(level, pos, null, Set.of(), false, true));
        }
        var state = level.getBlockState(pos);
        if ((!state.is(TAKE) && !state.is(PUT) && !state.is(CRAFT))
                || !(entity instanceof AbstractGridBlockEntity || entity instanceof StorageMonitorBlockEntity)) {
            return Optional.empty();
        }
        var host = (AbstractNetworkNodeContainerBlockEntity<?>) entity;
        var nodes = host.getContainerProvider().getContainers().stream().map(NetworkNodeContainer::getNode).toList();
        AbstractNetworkNode node = nodes.size() == 1 && nodes.getFirst() instanceof AbstractNetworkNode nativeNode
            ? nativeNode : null;
        EnumSet<StorePort.Operation> operations = EnumSet.noneOf(StorePort.Operation.class);
        if (state.is(TAKE)) {
            operations.add(StorePort.Operation.TAKE);
        }
        if (state.is(PUT)) {
            operations.add(StorePort.Operation.PUT);
        }
        return Optional.of(new NetworkPort(level, pos, node, operations, state.is(CRAFT), false));
    }

    // Both sides of a split must change identity, even when RS retains the old Network on one side.
    record NetworkKey(UUID network, Set<UUID> members) {
        NetworkKey {
            members = Set.copyOf(members);
        }
    }

    private static final Map<Object, UUID> IDENTITIES = new WeakHashMap<>();

    static NetworkKey key(Network network) {
        Set<UUID> members = new HashSet<>();
        for (var container : network.getComponent(GraphNetworkComponent.class).getContainers()) {
            if (!(container.getNode() instanceof ColonyProvider)) {
                members.add(IDENTITIES.computeIfAbsent(container, ignored -> UUID.randomUUID()));
            }
        }
        return new NetworkKey(IDENTITIES.computeIfAbsent(network, ignored -> UUID.randomUUID()), members);
    }

    static NetworkNodeActor source(AbstractNetworkNode node) {
        return new NetworkNodeActor(node);
    }

    static Component reason(String name) {
        return Component.translatable("folkways.rs2." + name);
    }
}
