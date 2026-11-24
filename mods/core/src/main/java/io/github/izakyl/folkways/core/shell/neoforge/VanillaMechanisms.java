package io.github.izakyl.folkways.core.shell.neoforge;

import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Link;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Role;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.mechanism.Mechanisms;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Core's part of a mechanism graph: every Folkways signal found in the scan, vanilla redstone sources and the vanilla
 * blocks they open, light or push, and where each source's signal reaches. Reach is read from the blocks as placed:
 * the blocks beside a source, the block it hangs on and its neighbours, and on along redstone dust and diodes.
 */
public final class VanillaMechanisms implements Mechanisms.Provider {

    // Dust carries a signal fifteen blocks; diodes renew it, so a line may run a little further.
    private static final int WIRE_REACH = 48;

    public static void install() {
        Mechanisms.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "vanilla"), new VanillaMechanisms());
    }

    @Override
    public void describe(MechanismScan scan) {
        ServerLevel level = scan.level();
        for (MechanismScan.Cell cell : scan.cells()) {
            BlockState state = level.getBlockState(cell.storage());
            if (state.isAir()) {
                continue;
            }
            if (state.getBlock() instanceof LeverBlock || state.getBlock() instanceof ButtonBlock) {
                scan.node(cell.storage()).role(Role.CONTROL).role(Role.REDSTONE)
                    .put("powered", state.getValue(BlockStateProperties.POWERED));
            } else if (state.isSignalSource() && !carrier(state)) {
                scan.node(cell.storage()).role(Role.REDSTONE);
            } else if (state.getBlock() instanceof DoorBlock) {
                if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    scan.node(cell.storage()).role(Role.ACTUATOR).put("open", state.getValue(DoorBlock.OPEN));
                }
            } else if (state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock) {
                scan.node(cell.storage()).role(Role.ACTUATOR).put("open", state.getValue(BlockStateProperties.OPEN));
            } else if (state.getBlock() instanceof PistonBaseBlock) {
                scan.node(cell.storage()).role(Role.ACTUATOR)
                    .put("extended", state.getValue(PistonBaseBlock.EXTENDED))
                    .put("facing", state.getValue(PistonBaseBlock.FACING).getSerializedName());
            } else if (state.getBlock() instanceof RedstoneLampBlock || state.getBlock() instanceof DispenserBlock) {
                scan.node(cell.storage()).role(Role.ACTUATOR);
            }
            if (state.hasBlockEntity() || state.getBlock() instanceof LeverBlock
                    || state.getBlock() instanceof ButtonBlock) {
                List<Signal> signals = Controls.signals(level, cell.at());
                if (!signals.isEmpty()) {
                    List<Map<String, Object>> controls = new ArrayList<>();
                    for (Signal signal : signals) {
                        Map<String, Object> control = new LinkedHashMap<>();
                        control.put("part", signal.part());
                        control.put("type", Mechanisms.describe(signal.type()));
                        signal.feedback().flatMap(reading -> reading.read(level))
                            .ifPresent(value -> control.put("reading", Mechanisms.describe(value)));
                        controls.add(control);
                    }
                    scan.node(cell.storage()).role(Role.CONTROL).put("controls", controls);
                }
            }
        }
    }

    @Override
    public void link(MechanismScan scan) {
        ServerLevel level = scan.level();
        for (MechanismScan.NodeBuilder node : List.copyOf(scan.nodes())) {
            if (node.entity().isPresent() || !node.at().in(level)) {
                continue;
            }
            BlockPos pos = node.at().block(level);
            List<Port<?>> ports = Ports.all(level, node.at());
            if (!ports.isEmpty()) {
                node.put("ports", ports.stream().map(Port::part).distinct().toList());
            }
            if (!node.has(Role.REDSTONE)) {
                continue;
            }
            Map<BlockPos, String> reached = reach(level, pos);
            for (Map.Entry<BlockPos, String> powered : reached.entrySet()) {
                if (powered.getKey().equals(pos)) {
                    continue;
                }
                scan.existing(powered.getKey()).filter(target -> !target.has(Role.REDSTONE)
                        || target.has(Role.ACTUATOR) || target.has(Role.TRANSMISSION))
                    .ifPresent(target -> scan.edge(node, target, Link.POWERS, Map.of("via", powered.getValue())));
            }
        }
    }

    @Override
    public void observe(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity block,
                        Map<String, Object> into) {
        for (var property : List.of(BlockStateProperties.POWERED, BlockStateProperties.OPEN,
                BlockStateProperties.EXTENDED, BlockStateProperties.LIT)) {
            if (state.hasProperty(property)) {
                into.put(property.getName(), state.getValue(property));
            }
        }
    }

    @Override
    public boolean mechanical(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
            || state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof PistonBaseBlock
            || state.getBlock() instanceof RedstoneLampBlock || carrier(state);
    }

    private static boolean carrier(BlockState state) {
        return state.getBlock() instanceof RedStoneWireBlock || state.getBlock() instanceof DiodeBlock;
    }

    /** The cells a source's signal can reach, each with how: beside it, through its support, or along a line. */
    private static Map<BlockPos, String> reach(ServerLevel level, BlockPos source) {
        Map<BlockPos, String> reached = new LinkedHashMap<>();
        BlockState state = level.getBlockState(source);
        Set<BlockPos> strong = new LinkedHashSet<>();
        if (state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock) {
            strong.add(source.relative(attachedTo(state)));
        } else if (state.getBlock() instanceof RedstoneTorchBlock) {
            strong.add(source.above());
        }
        ArrayDeque<BlockPos> lines = new ArrayDeque<>();
        for (Direction direction : Direction.values()) {
            BlockPos next = source.relative(direction);
            reached.putIfAbsent(next, "adjacent");
            if (carrier(level.getBlockState(next))) {
                lines.add(next);
            }
        }
        for (BlockPos support : strong) {
            for (Direction direction : Direction.values()) {
                BlockPos next = support.relative(direction);
                reached.putIfAbsent(next, "support");
                if (carrier(level.getBlockState(next))) {
                    lines.add(next);
                }
            }
        }
        Set<BlockPos> wire = new LinkedHashSet<>(lines);
        while (!lines.isEmpty() && wire.size() < WIRE_REACH) {
            BlockPos at = lines.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                reached.putIfAbsent(next, "wire");
                // Dust runs up and down a block's side; a powered block under dust passes the signal to its sides.
                for (BlockPos step : List.of(next, next.above(), next.below())) {
                    if (!wire.contains(step) && level.isLoaded(step) && carrier(level.getBlockState(step))) {
                        wire.add(step);
                        lines.add(step);
                    }
                }
                if (direction == Direction.DOWN) {
                    for (Direction side : Direction.values()) {
                        reached.putIfAbsent(next.relative(side), "wire");
                    }
                }
            }
        }
        return reached;
    }

    private static Direction attachedTo(BlockState state) {
        return switch (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
            case CEILING -> Direction.UP;
            case FLOOR -> Direction.DOWN;
            case WALL -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING).getOpposite();
        };
    }
}
