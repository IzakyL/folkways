package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartHelper;
import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.parts.misc.InterfacePart;
import appeng.parts.reporting.AbstractTerminalPart;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

final class Devices {

    static final TagKey<Block> TAKE = tag("take");
    static final TagKey<Block> PUT = tag("put");
    static final TagKey<Block> CRAFT = tag("craft");

    private Devices() {
    }

    // A terminal is a part on a cable bus, whose own block would be named only "Cable and/or Bus".
    static Optional<ItemStack> terminal(Level level, BlockPos at) {
        IPartHost parts = PartHelper.getPartHost(level, at);
        if (parts != null) {
            for (Direction side : Direction.values()) {
                if (parts.getPart(side) instanceof AbstractTerminalPart terminal) {
                    return Optional.of(new ItemStack(terminal.getPartItem()));
                }
            }
        }
        return Optional.empty();
    }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "network_access/" + name));
    }

    // Terminals are parts of cable_bus, not blocks. A tagged cable without a terminal grants no access.
    static Optional<NetworkPort> at(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        IPartHost existing = PartHelper.getPartHost(level, pos);
        boolean buffer = level.getBlockEntity(pos) instanceof InterfaceBlockEntity;
        if (existing != null) {
            boolean terminal = false;
            for (Direction side : Direction.values()) {
                buffer |= existing.getPart(side) instanceof InterfacePart;
                terminal |= existing.getPart(side) instanceof AbstractTerminalPart;
            }
            buffer &= !terminal;
        }
        if (buffer) {
            return Optional.of(new NetworkPort(level, pos, null, Set.of(), false, false, true));
        }
        if (!state.is(TAKE) && !state.is(PUT) && !state.is(CRAFT)) {
            return Optional.empty();
        }
        List<IGridNode> nodes = new ArrayList<>();
        IPartHost parts = PartHelper.getPartHost(level, pos);
        boolean terminal = false;
        if (parts != null) {
            for (Direction side : Direction.values()) {
                if (parts.getPart(side) instanceof AbstractTerminalPart part) {
                    terminal = true;
                    if (part.getGridNode() != null) {
                        nodes.add(part.getGridNode());
                    }
                }
            }
            if (!terminal) {
                return Optional.empty();
            }
        } else if (level.getBlockEntity(pos) instanceof IInWorldGridNodeHost host) {
            for (Direction side : Direction.values()) {
                IGridNode node = host.getGridNode(side);
                if (node != null && !nodes.contains(node)) {
                    nodes.add(node);
                }
            }
        } else {
            return Optional.empty();
        }
        EnumSet<StorePort.Operation> operations = EnumSet.noneOf(StorePort.Operation.class);
        if (state.is(TAKE)) {
            operations.add(StorePort.Operation.TAKE);
        }
        if (state.is(PUT)) {
            operations.add(StorePort.Operation.PUT);
        }
        IGridNode node = nodes.isEmpty() ? null : nodes.getFirst();
        boolean ambiguous = node != null && nodes.stream().anyMatch(other -> other.getGrid() != node.getGrid());
        return Optional.of(new NetworkPort(level, pos, node, operations, state.is(CRAFT), ambiguous, false));
    }

    // Grid objects survive on one side of a split. Membership makes *both* resulting identities change.
    record NetworkKey(Set<UUID> members) {
        NetworkKey {
            members = Set.copyOf(members);
        }
    }

    private static final Map<IGridNode, UUID> NODES = new WeakHashMap<>();

    static NetworkKey key(IGridNode node) {
        Set<UUID> members = new HashSet<>();
        node.getGrid().getNodes().forEach(member -> {
            if (!(member.getOwner() instanceof ColonyProvider)) {
                members.add(NODES.computeIfAbsent(member, ignored -> UUID.randomUUID()));
            }
        });
        return new NetworkKey(members);
    }

    static IActionSource source(IGridNode node) {
        if (node.getOwner() instanceof IActionHost device) {
            return IActionSource.ofMachine(device);
        }
        return IActionSource.ofMachine(() -> node);
    }

    static Component reason(String name) {
        return Component.translatable("folkways.ae2." + name);
    }

}
