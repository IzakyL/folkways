package io.github.izakyl.folkways.core.shell.create.kinetic;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlock;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.chainDrive.ChainDriveBlock;
import com.simibubi.create.content.kinetics.chainDrive.ChainGearshiftBlock;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import com.simibubi.create.content.kinetics.drill.DrillBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlock;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogwheelBlock;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedShaftBlock;
import com.simibubi.create.content.kinetics.transmission.ClutchBlock;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlock;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Link;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Role;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan.NodeBuilder;
import io.github.izakyl.folkways.core.facility.api.mechanism.Mechanisms;
import io.github.izakyl.folkways.core.shell.create.actuator.mixin.RotationPropagatorAccess;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Create's part of a mechanism graph: kinetic parts, joined and weighed by Create's own propagation rule, the way each
 * source's rotation reaches each actuator, and the contraptions actuators carry. A network with no source in the scan
 * still shows its links, marked undriven.
 */
public final class CreateMechanisms implements Mechanisms.Provider {

    // Bounds the walk from one source; a scan holds far fewer kinetic blocks than this in practice.
    private static final int MAX_WALK = 4096;

    public static void install() {
        Mechanisms.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "create"), new CreateMechanisms());
    }

    @Override
    public void describe(MechanismScan scan) {
        ServerLevel level = scan.level();
        Map<BlockPos, KineticBlockEntity> kinetic = new LinkedHashMap<>();
        for (MechanismScan.Cell cell : scan.cells()) {
            BlockState state = level.getBlockState(cell.storage());
            if (state.hasBlockEntity() && state.getBlock() instanceof IRotate
                    && level.getBlockEntity(cell.storage()) instanceof KineticBlockEntity block
                    && !(block instanceof BeltBlockEntity belt && !belt.isController())) {
                kinetic.put(cell.storage(), block);
            }
        }
        Map<KineticBlockEntity, NodeBuilder> nodes = new HashMap<>();
        for (KineticBlockEntity block : kinetic.values()) {
            nodes.put(block, describe(scan, block));
        }
        Map<KineticBlockEntity, Map<KineticBlockEntity, Float>> links = new HashMap<>();
        Set<String> outside = new HashSet<>();
        for (KineticBlockEntity block : kinetic.values()) {
            for (BlockPos neighbour : RotationPropagatorAccess.folkways$neighbours(block)) {
                if (!(level.getBlockEntity(neighbour) instanceof KineticBlockEntity other) || other == block) {
                    continue;
                }
                if (other instanceof BeltBlockEntity belt && !belt.isController()) {
                    continue;
                }
                float ratio = RotationPropagatorAccess.folkways$modifier(block, other);
                if (ratio == 0 && !RotationPropagator.isConnected(block, other)) {
                    continue;
                }
                if (!kinetic.containsKey(neighbour)) {
                    outside.add(nodes.get(block).id());
                    nodes.get(block).put("leaves_scan", true);
                    continue;
                }
                links.computeIfAbsent(block, key -> new LinkedHashMap<>()).put(other, ratio);
            }
        }
        if (!outside.isEmpty()) {
            scan.unsupported("kinetic links leave the scanned box at " + String.join(", ", outside.stream().limit(4).toList()));
        }
        orient(scan, kinetic, nodes, links);
        for (KineticBlockEntity block : kinetic.values()) {
            AbstractContraptionEntity moved = moved(block);
            if (moved != null && moved.isAlive()) {
                NodeBuilder contraption = contraption(scan, moved);
                scan.edge(nodes.get(block), contraption, Link.MOVES, Map.of());
            }
        }
        for (Entity entity : scan.entities()) {
            if (entity instanceof AbstractContraptionEntity contraption && scan.existing(entity).isEmpty()) {
                contraption(scan, contraption);
            }
        }
    }

    private static NodeBuilder contraption(MechanismScan scan, AbstractContraptionEntity entity) {
        NodeBuilder node = scan.node(entity).kind("contraption").role(Role.MOVING);
        var contraption = entity.getContraption();
        if (contraption != null) {
            node.put("blocks", contraption.getBlocks().size());
            if (contraption.bounds != null) {
                node.put("size", List.of(round(contraption.bounds.getXsize()), round(contraption.bounds.getYsize()),
                    round(contraption.bounds.getZsize())));
            }
            node.put("anchor", List.of(contraption.anchor.getX(), contraption.anchor.getY(), contraption.anchor.getZ()));
        }
        node.put("position", List.of(round(entity.getX()), round(entity.getY()), round(entity.getZ())));
        node.put("stalled", entity.isStalled());
        return node;
    }

    @Nullable
    private static AbstractContraptionEntity moved(KineticBlockEntity block) {
        if (block instanceof LinearActuatorBlockEntity actuator) {
            return actuator.movedContraption;
        }
        if (block instanceof MechanicalBearingBlockEntity bearing) {
            return bearing.getMovedContraption();
        }
        return null;
    }

    private static NodeBuilder describe(MechanismScan scan, KineticBlockEntity block) {
        BlockState state = block.getBlockState();
        NodeBuilder node = scan.node(block.getBlockPos());
        node.put("speed", round(block.getSpeed()));
        if (state.getBlock() instanceof IRotate rotate) {
            node.put("axis", rotate.getRotationAxis(state).getSerializedName());
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            node.put("facing", state.getValue(BlockStateProperties.FACING).getSerializedName());
        }
        if (block.isOverStressed()) {
            node.put("overstressed", true);
        }
        if (block instanceof HandCrankBlockEntity crank) {
            node.role(Role.SOURCE).role(Role.CONTROL);
            node.put("forward_rpm", round(CreateCranks.forwardSpeed(crank)));
            node.put("turning", crank.inUse > 0);
            if (crank instanceof ValveHandleBlockEntity valve) {
                node.put("degrees_per_turn", valve.angleInput.getValue());
                node.put("note", "turn +1 = one use: rotates degrees_per_turn; turn -1 = one sneaking use, the other way");
            } else {
                node.put("note", "turn +1 = one use: spins about 10 ticks at forward_rpm; -1 spins the other way");
            }
        } else if (block instanceof MechanicalPistonBlockEntity piston) {
            node.role(Role.ACTUATOR).put("offset", round(piston.offset)).put("running", piston.running)
                .put("sticky", MechanicalPistonBlock.isStickyPiston(state));
        } else if (block instanceof PulleyBlockEntity pulley) {
            node.role(Role.ACTUATOR).put("offset", round(pulley.offset)).put("running", pulley.running);
            if (pulley instanceof ElevatorPulleyBlockEntity) {
                node.put("elevator", true);
            }
        } else if (block instanceof MechanicalBearingBlockEntity bearing) {
            if (bearing instanceof WindmillBearingBlockEntity) {
                node.role(Role.SOURCE);
            }
            node.role(Role.ACTUATOR).put("angle", round(bearing.getInterpolatedAngle(0)))
                .put("running", bearing.isRunning());
        } else if (block instanceof GantryCarriageBlockEntity) {
            node.role(Role.ACTUATOR);
        } else if (block instanceof DrillBlockEntity) {
            node.kind("drill").role(Role.ACTUATOR).put("spinning", block.getSpeed() != 0)
                .put("note", "breaks the block at its tip while spinning, including world blocks along its path "
                    + "when carried by a moving structure");
        } else if (block instanceof SplitShaftBlockEntity split) {
            node.role(Role.TRANSMISSION);
            boolean powered = state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
            node.put("powered", powered);
            if (state.getBlock() instanceof ClutchBlock) {
                node.put("when_powered", "disengages: rotation stops passing through");
            } else if (split instanceof SequencedGearshiftBlockEntity sequenced) {
                node.put("when_powered", "runs its programmed sequence on a rising signal");
                node.put("idle", sequenced.isIdle());
            } else if (state.getBlock() instanceof GearshiftBlock || state.getBlock() instanceof ChainGearshiftBlock) {
                node.put("when_powered", state.getBlock() instanceof GearshiftBlock
                    ? "reverses the output side" : "changes the speed ratio");
            }
        } else if (relay(state)) {
            node.role(Role.TRANSMISSION).foldable(true);
        } else if (block.isSource()) {
            node.role(Role.SOURCE);
        } else {
            node.role(Role.MACHINE);
        }
        if (block instanceof EncasedFanBlockEntity fan) {
            node.put("air", CreateFans.describe(scan.level(), fan));
        }
        return node;
    }

    private static boolean relay(BlockState state) {
        var block = state.getBlock();
        return block instanceof ShaftBlock || block instanceof CogWheelBlock || block instanceof GearboxBlock
            || block instanceof EncasedShaftBlock || block instanceof EncasedCogwheelBlock || block instanceof BeltBlock
            || block instanceof ChainDriveBlock && !(block instanceof ChainGearshiftBlock);
    }

    /**
     * Orients each link downstream from the sources and records, for each source, the factor by which its speed
     * reaches each actuator and machine. Links no source reaches are kept as undriven.
     */
    private static void orient(MechanismScan scan, Map<BlockPos, KineticBlockEntity> kinetic,
                               Map<KineticBlockEntity, NodeBuilder> nodes,
                               Map<KineticBlockEntity, Map<KineticBlockEntity, Float>> links) {
        Set<Long> placed = new HashSet<>();
        Map<KineticBlockEntity, Map<KineticBlockEntity, Float>> adjacent = symmetric(links);
        List<KineticBlockEntity> sources = kinetic.values().stream()
            .filter(block -> nodes.get(block).has(Role.SOURCE)).toList();
        for (KineticBlockEntity source : sources) {
            Map<KineticBlockEntity, Double> factor = new HashMap<>();
            Map<KineticBlockEntity, KineticBlockEntity> parent = new HashMap<>();
            ArrayDeque<KineticBlockEntity> open = new ArrayDeque<>();
            factor.put(source, 1.0);
            open.add(source);
            while (!open.isEmpty() && factor.size() < MAX_WALK) {
                KineticBlockEntity at = open.poll();
                for (Map.Entry<KineticBlockEntity, Float> next : adjacent.getOrDefault(at, Map.of()).entrySet()) {
                    KineticBlockEntity to = next.getKey();
                    if (factor.containsKey(to)) {
                        continue;
                    }
                    // Another source drives the rest of this network: the walk stops at it.
                    if (nodes.get(to).has(Role.SOURCE) && !(nodes.get(to).has(Role.ACTUATOR))) {
                        continue;
                    }
                    factor.put(to, factor.get(at) * next.getValue());
                    parent.put(to, at);
                    open.add(to);
                    if (placed.add(pair(at, to))) {
                        scan.edge(nodes.get(at), nodes.get(to), Link.DRIVES, Map.of("ratio", (double) next.getValue()));
                    }
                }
            }
            NodeBuilder from = nodes.get(source);
            float rpm = source instanceof HandCrankBlockEntity crank ? CreateCranks.forwardSpeed(crank)
                : source.getGeneratedSpeed();
            for (Map.Entry<KineticBlockEntity, Double> reached : factor.entrySet()) {
                NodeBuilder to = nodes.get(reached.getKey());
                if (reached.getKey() == source || !(to.has(Role.ACTUATOR) || to.has(Role.MACHINE))) {
                    continue;
                }
                List<NodeBuilder> through = new ArrayList<>();
                int hops = 0;
                for (KineticBlockEntity step = parent.get(reached.getKey()); step != null && step != source;
                        step = parent.get(step)) {
                    hops++;
                    NodeBuilder relay = nodes.get(step);
                    if (relay.has(Role.TRANSMISSION) && !relay.folds()) {
                        through.add(0, relay);
                    }
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put(from.has(Role.CONTROL) ? "rpm_per_forward_turn" : "rpm", round(reached.getValue() * rpm));
                scan.path(from, to, reached.getValue(), hops + 1, through, details);
            }
        }
        for (Map.Entry<KineticBlockEntity, Map<KineticBlockEntity, Float>> entry : links.entrySet()) {
            for (Map.Entry<KineticBlockEntity, Float> link : entry.getValue().entrySet()) {
                if (placed.add(pair(entry.getKey(), link.getKey()))) {
                    scan.edge(nodes.get(entry.getKey()), nodes.get(link.getKey()), Link.DRIVES,
                        Map.of("ratio", (double) link.getValue(), "driven", false));
                }
            }
        }
        // A clutch let go, or anything else that splits a shaft, still sits between its two sides.
        for (KineticBlockEntity block : kinetic.values()) {
            if (!(block instanceof SplitShaftBlockEntity) || !(block.getBlockState().getBlock() instanceof IRotate rotate)) {
                continue;
            }
            Direction.Axis axis = rotate.getRotationAxis(block.getBlockState());
            for (Direction side : Direction.values()) {
                KineticBlockEntity other = kinetic.get(block.getBlockPos().relative(side));
                if (side.getAxis() != axis || other == null || !(other.getBlockState().getBlock() instanceof IRotate next)
                        || !next.hasShaftTowards(scan.level(), other.getBlockPos(), other.getBlockState(),
                            side.getOpposite())) {
                    continue;
                }
                if (placed.add(pair(block, other))) {
                    scan.edge(nodes.get(block), nodes.get(other), Link.DRIVES, Map.of("ratio", 0.0, "engaged", false));
                }
            }
        }
    }

    // Both directions of every link, each with the speed at its far end per unit speed at its near end.
    private static Map<KineticBlockEntity, Map<KineticBlockEntity, Float>> symmetric(
            Map<KineticBlockEntity, Map<KineticBlockEntity, Float>> links) {
        Map<KineticBlockEntity, Map<KineticBlockEntity, Float>> both = new HashMap<>();
        links.forEach((from, targets) -> targets.forEach((to, ratio) -> {
            both.computeIfAbsent(from, key -> new LinkedHashMap<>()).put(to, ratio);
            both.computeIfAbsent(to, key -> new LinkedHashMap<>()).putIfAbsent(from, ratio == 0 ? 0 : 1 / ratio);
        }));
        return both;
    }

    // One key per unordered pair of blocks.
    private static long pair(KineticBlockEntity a, KineticBlockEntity b) {
        long first = a.getBlockPos().asLong();
        long second = b.getBlockPos().asLong();
        return Math.min(first, second) * 31 + Math.max(first, second);
    }

    @Override
    public void observe(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity block,
                        Map<String, Object> into) {
        if (!(block instanceof KineticBlockEntity kinetic)) {
            return;
        }
        into.put("speed", round(kinetic.getSpeed()));
        switch (kinetic) {
            case LinearActuatorBlockEntity actuator -> {
                into.put("offset", round(actuator.offset));
                into.put("running", actuator.running);
            }
            case MechanicalBearingBlockEntity bearing -> {
                into.put("angle", round(bearing.getInterpolatedAngle(0)));
                into.put("running", bearing.isRunning());
            }
            case HandCrankBlockEntity crank -> into.put("turning", crank.inUse > 0);
            case EncasedFanBlockEntity fan -> into.put("air_processing", CreateFans.processing(fan));
            default -> {
            }
        }
    }

    @Override
    public void observe(Entity entity, Map<String, Object> into) {
        if (entity instanceof AbstractContraptionEntity contraption) {
            into.put("x", round(entity.getX()));
            into.put("y", round(entity.getY()));
            into.put("z", round(entity.getZ()));
            if (contraption.getContraption() != null) {
                into.put("blocks", contraption.getContraption().getBlocks().size());
            }
        }
    }

    @Override
    public boolean mechanical(Entity entity) {
        // Glue joins blocks into the contraption an actuator assembles.
        return entity instanceof AbstractContraptionEntity || entity instanceof SuperGlueEntity;
    }

    @Override
    public boolean mechanical(BlockState state) {
        return state.getBlock() instanceof IRotate || MechanicalPistonBlock.isExtensionPole(state)
            || MechanicalPistonBlock.isPistonHead(state);
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    static Direction facing(BlockState state) {
        return state.getValue(BlockStateProperties.FACING);
    }
}
