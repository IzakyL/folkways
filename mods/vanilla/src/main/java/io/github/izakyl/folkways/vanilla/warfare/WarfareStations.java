package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import io.github.izakyl.folkways.core.combat.api.Trajectories;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.warfare.WarfareBallistics;
import io.github.izakyl.folkways.core.shell.warfare.WarfareMount;
import io.github.izakyl.folkways.solve.Ballistics;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.patrol.SentryPosts;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

public final class WarfareStations {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "warfare/stations");
    // Earlier releases marked a mortar whose shot they held back; the mortar now holds its own shots, and an old
    // save's mark is dropped when the mortar is armed.
    private static final String RETIRED_PENDING = "folkways_vanilla:warfare_pending";
    private static final Map<VehicleEntity, WarfareStation> STATIONS = new IdentityHashMap<>();

    private WarfareStations() {
    }

    static void declare(Declaring declaring) {
        WeaponStations.register(ID, (level, mount) -> mount instanceof WarfareMount found
            ? Optional.of(STATIONS.computeIfAbsent(found.vehicle(), WarfareStations::station))
                .filter(station -> station.valid(level)).map(WeaponStations.Station.class::cast)
            : Optional.empty());
    }

    private static WarfareStation station(VehicleEntity vehicle) {
        vehicle.getPersistentData().remove(RETIRED_PENDING);
        return new WarfareStation(vehicle);
    }

    static void enroll(Registering registering) {
        registering.enrollment(ID, new Enrollment(List.of(new Delegation(SentryPosts.STATION,
            new Shape.Body(type -> List.of(ModEntities.MK_42.get(), ModEntities.BL_132.get(),
                ModEntities.MLE_1934.get(), ModEntities.FH_77BW.get(), ModEntities.MORTAR.get(),
                ModEntities.HPJ_11.get()).contains(type)),
            ResourceLocation.withDefaultNamespace("crossbow"))), Schema.none()));
    }

    // Superb Warfare shells fly without drag; the aim's pitch is Minecraft's, positive downward.
    static Optional<Trajectories.Launch> launch(VehicleEntity vehicle, String weapon, Value angles) {
        double speed = vehicle.getProjectileVelocity(weapon);
        double gravity = vehicle.getProjectileGravity(weapon);
        if (!(angles instanceof Value.Vector(List<Double> axes)) || axes.size() != 2 || !Double.isFinite(speed)
                || speed <= 0 || !Double.isFinite(gravity) || gravity < 0) {
            return Optional.empty();
        }
        return Optional.of(new Trajectories.Launch((vehicle instanceof MortarEntity ? vehicle.getEyePosition() : vehicle.getShootPos(weapon, 1)),
            new Ballistics.Aim(axes.get(0), Mth.clamp(-axes.get(1), -90, 90)),
            new Ballistics.Model(speed, gravity, 1)));
    }

    static boolean automatic(VehicleEntity station) {
        return WarfareBallistics.unmodified(station);
    }

    static ItemSpec ammunition(VehicleEntity vehicle) {
        return WarfareBallistics.ammunition(vehicle);
    }

    static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        STATIONS.remove(event.getEntity());
    }
}
