package io.github.izakyl.folkways.core.shell.letitwalk;

import dev.letitwalk.content.LegJointBlockEntity;
import dev.letitwalk.content.PilotControlsBlockEntity;
import dev.letitwalk.content.WalkerCoreBlockEntity;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Link;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Role;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.mechanism.Mechanisms;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Let it Walk!'s part of a mechanism graph: a walker core is the actuator that walks its hull, and pilot controls
 * command it (their destination, posture inputs and action keys). A walker moves as a
 * Sable sub-level with its legs; a copy of it walks too, and its core reports where it stands and whether it is parked
 * or powered.
 */
final class WalkerMechanisms implements Mechanisms.Provider {

    static void install() {
        Mechanisms.register(LetItWalkIntegration.id("mechanism"), new WalkerMechanisms());
    }

    @Override
    public void describe(MechanismScan scan) {
        ServerLevel level = scan.level();
        int joints = 0;
        for (MechanismScan.Cell cell : scan.cells()) {
            var block = level.getBlockEntity(cell.storage());
            if (block instanceof LegJointBlockEntity) {
                joints++;
            } else if (block instanceof WalkerCoreBlockEntity core) {
                var node = scan.node(cell.storage()).kind("walker_core").role(Role.ACTUATOR).role(Role.CONTROL)
                    .put("assembled", core.isAssembled()).put("powered", core.isPowered()).put("parked", core.parked)
                    .put("note", "walks its hull (a Sable sub-level, with its legs); park toggles native parking");
                Walkers.position(core).ifPresent(at -> node.put("position",
                    List.of(Math.floor(at.x), Math.floor(at.y), Math.floor(at.z))));
                Walkers.heading(core).ifPresent(facing -> node.put("heading",
                    List.of(Math.round(facing.x * 100) / 100.0, Math.round(facing.z * 100) / 100.0)));
            } else if (block instanceof PilotControlsBlockEntity) {
                scan.node(cell.storage()).kind("pilot_controls").role(Role.CONTROL)
                    .put("note", "destination walks the walker to a world position; key/* parts press "
                        + "the station's linked action keys. height sets the height offset in blocks; "
                        + "lean takes [roll, pitch] in radians. limb/<n> takes [fwd, up] in the native unit-disc "
                        + "plane, with limbs numbered from 1 in pilot order. Its reading gives the world position "
                        + "of the end effector and whether the limb is an arm");
            }
        }
        if (joints > 0) {
            scan.unsupported("walker legs (" + joints + " joints) are described by their core, not one by one");
        }
    }

    @Override
    public void observe(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity block,
                        Map<String, Object> into) {
        if (block instanceof WalkerCoreBlockEntity core && core.isAssembled()) {
            into.put("parked", core.parked);
            into.put("powered", core.isPowered());
            Walkers.position(core).ifPresent(at -> into.put("position",
                List.of(Math.floor(at.x), Math.floor(at.y), Math.floor(at.z))));
        }
    }

    @Override
    public void link(MechanismScan scan) {
        ServerLevel level = scan.level();
        for (MechanismScan.Cell cell : scan.cells()) {
            if (level.getBlockEntity(cell.storage()) instanceof PilotControlsBlockEntity station) {
                Walkers.core(level, station).filter(core -> scan.covers(core.getBlockPos())).ifPresent(core ->
                    scan.edge(scan.node(cell.storage()), scan.node(core.getBlockPos()), Link.CONTROLS,
                        Map.of("via", "destination")));
            }
        }
    }
}
