package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Link;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Role;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.mechanism.Mechanisms;
import io.github.izakyl.folkways.core.shell.cbc.mixin.CannonMountAccess;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Create Big Cannons' part of a mechanism graph: a cannon mount is the actuator that turns its barrel, with its yaw and
 * pitch, whether a cannon is assembled and loaded, and the barrel it carries. Create's provider already joins the
 * mount's kinetic inputs; the mount's own controls (aim, fire, breech) come with the graph's Folkways controls.
 */
final class CbcMechanisms implements Mechanisms.Provider {

    static void install() {
        Mechanisms.register(CbcIntegration.id("mechanism"), new CbcMechanisms());
    }

    @Override
    public void describe(MechanismScan scan) {
        ServerLevel level = scan.level();
        for (MechanismScan.Cell cell : scan.cells()) {
            if (!(level.getBlockEntity(cell.storage()) instanceof CannonMountBlockEntity mount)) {
                continue;
            }
            CannonMountAccess angles = (CannonMountAccess) mount;
            var node = scan.node(cell.storage()).kind("cannon_mount").role(Role.ACTUATOR)
                .put("yaw", round(angles.folkwaysYaw())).put("pitch", round(angles.folkwaysPitch()))
                .put("assembled", mount.isRunning() && mount.getContraption() != null)
                .put("note", "aim takes [yaw, pitch] through hand cranks attached to the needed shafts; fire is a pulse on a big cannon"
                    + " and a 0-15 level on an autocannon; kinetic rotation into the mount also turns the cannon");
            state(mount, node::put);
            CbcCannon.assembled(mount).ifPresent(entity -> {
                var cannon = (rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption)
                    entity.getContraption();
                var barrel = scan.node(entity).kind(switch (CbcCannon.kind(mount)) {
                        case AUTOCANNON -> "autocannon";
                        case DROP_MORTAR -> "drop_mortar";
                        default -> "cannon";
                    }).role(Role.MOVING)
                    .put("blocks", cannon.getBlocks().size())
                    .put("facing", cannon.initialOrientation().getSerializedName())
                    .put("loaded", CbcIntegration.loaded(mount));
                scan.edge(node, barrel, Link.MOVES, Map.of());
            });
        }
    }

    // What a loader needs to see: the bore load and its limit, the autocannon's rounds and cadence, and the last refusal.
    private static void state(CannonMountBlockEntity mount, java.util.function.BiConsumer<String, Object> into) {
        if (CbcCannon.assembled(mount).isEmpty()) {
            return;
        }
        into.accept("loaded", CbcIntegration.loaded(mount));
        CbcCannon.mounted(mount).ifPresent(cannon -> {
            into.accept("charges", cannon.charges());
            into.accept("projectile", cannon.projectile());
            into.accept("stress", round(cannon.stress()));
            into.accept("safe_stress", cannon.cannon().getMaxSafeCharges());
        });
        CbcCannon.autocannon(mount).ifPresent(gun -> {
            into.accept("ammo", CbcMagazine.rounds(gun));
            into.accept("fire_rate", gun.breech().getFireRate());
        });
        CbcCannon.dropMortar(mount).ifPresent(mortar -> into.accept("queued", !CbcMortarRound.queued(mortar).isEmpty()));
        String refusal = CbcBreech.refusal(mount);
        if (!refusal.isEmpty()) {
            into.accept("refusal", refusal);
        }
    }

    @Override
    public void observe(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity block,
                        Map<String, Object> into) {
        if (block instanceof CannonMountBlockEntity mount) {
            CannonMountAccess angles = (CannonMountAccess) mount;
            into.put("yaw", round(angles.folkwaysYaw()));
            into.put("pitch", round(angles.folkwaysPitch()));
            into.put("assembled", mount.isRunning() && mount.getContraption() != null);
            state(mount, into::put);
        }
    }

    @Override
    public void observe(Entity entity, Map<String, Object> into) {
        if (entity instanceof PitchOrientedContraptionEntity cannon) {
            into.put("yaw", round(cannon.yaw));
            into.put("pitch", round(cannon.pitch));
        }
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
