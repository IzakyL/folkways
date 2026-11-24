package io.github.izakyl.folkways.core.shell.letitwalk;

import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Facility controls and readings for native walker inputs and the hull they drive. */
public final class LetItWalkIntegration {

    private LetItWalkIntegration() {
    }

    public static void install() {
        Controls.register(id("pilot"), Controls.SPECIFIC, (level, pos) -> Walkers.station(level, pos)
            .map(station -> List.<Signal>of(new WalkerDrive(pos))).orElseGet(List::of));
        Controls.register(id("posture"), Controls.SPECIFIC, WalkerPosture::at);
        Controls.register(id("core"), Controls.SPECIFIC, (level, pos) -> Walkers.coreAt(level, pos)
            .map(core -> List.<Signal>of(new WalkerPark(pos))).orElseGet(List::of));
        Controls.register(id("keys"), Controls.SPECIFIC, WalkerKey::at);
        WalkerMechanisms.install();
        WalkerCopies.install();
        WalkerPosture.install();
        Readings.register(id("position"), (level, pos) -> Walkers.core(level, pos).flatMap(core ->
            Walkers.position(core).map(at -> positionReading(at, Walkers.destination(core).orElse(null)))));
        Readings.register(id("powered"), (level, pos) -> Walkers.core(level, pos)
            .or(() -> Walkers.coreAt(level, pos)).map(core -> Value.of(core.isPowered())));
        Readings.register(id("parked"), (level, pos) -> Walkers.coreAt(level, pos)
            .or(() -> Walkers.core(level, pos)).map(core -> Value.of(core.parked)));
    }

    static Value positionReading(Vec3 at, Vec3 goal) {
        if (goal != null && arrived(at, goal)) {
            return Value.of(goal.x, goal.y, goal.z);
        }
        Vec3 block = new Vec3(Math.floor(at.x), Math.floor(at.y), Math.floor(at.z));
        // The block grid can report the goal before the walker enters the arrival radius.
        Vec3 reported = block.equals(goal) ? at : block;
        return Value.of(reported.x, reported.y, reported.z);
    }

    static boolean arrived(Vec3 at, Vec3 goal) {
        double dx = goal.x - at.x;
        double dz = goal.z - at.z;
        return dx * dx + dz * dz <= WalkerDrive.ARRIVED * WalkerDrive.ARRIVED;
    }

    static ResourceLocation id(String part) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", "let_it_walk/" + part);
    }
}
