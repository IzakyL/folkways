package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.data.CustomData;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.atsuishio.superbwarfare.init.ModItems;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.patrol.SentryPosts;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

public final class WarfareStations {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_firearms", "warfare/stations");
    static final String PENDING = "folkways_firearms:warfare_pending";
    private static final Map<VehicleEntity, WarfareStation> STATIONS = new IdentityHashMap<>();

    private WarfareStations() {
    }

    static void declare(Declaring declaring) {
        WeaponStations.register(
            ID,
            (level, zone) -> {
                if (zone.cells().isEmpty()) {
                    return List.of();
                }
                AABB bounds = new AABB(zone.cells().iterator().next());
                for (var cell : zone.cells()) {
                    bounds = bounds.minmax(new AABB(cell));
                }
                return level
                    .getEntitiesOfClass(VehicleEntity.class, bounds.inflate(2), WarfareStations::emplacement)
                    .stream()
                    .filter(
                    vehicle ->
                        zone.cells().stream()
                            .anyMatch(cell -> zone.at(cell).equals(WorldSpaces.at(vehicle))))
                    .map(vehicle -> STATIONS.computeIfAbsent(vehicle, WarfareStation::new))
                    .filter(station -> station.valid(level))
                    .map(WeaponStations.Station.class::cast)
                    .toList();
            });
    }

    static void enroll(Registering registering) {
        registering.enrollment(ID, new Enrollment(List.of(new Delegation(SentryPosts.STATION,
            new Shape.Body(type -> List.of(ModEntities.MK_42.get(), ModEntities.BL_132.get(),
                ModEntities.MLE_1934.get(), ModEntities.FH_77BW.get(), ModEntities.MORTAR.get(),
                ModEntities.HPJ_11.get()).contains(type)),
            ResourceLocation.withDefaultNamespace("crossbow"))), Schema.none(), List.of()));
    }

    public static boolean emplacement(VehicleEntity vehicle) {
        return vehicle instanceof Fh77bwEntity
            || vehicle.computed().getEngineType() == EngineType.FIXED
            && (vehicle.getVehicleType() == VehicleType.ARTILLERY
            || vehicle.getVehicleType() == VehicleType.DEFENSE);
    }

    static String weaponName(VehicleEntity vehicle) {
        if (vehicle instanceof MortarEntity) {
            return "Main";
        }
        return vehicle.getGunName(vehicle.getTurretControllerIndex());
    }

    static boolean automatic(VehicleEntity station) {
        var data = station.getGunData(weaponName(station));
        if (data == null || station instanceof TowEntity || !data.propertyOverrideString.get().isBlank()) {
            return false;
        }
        for (var type : GunData.PERK_TYPES) {
            if (!data.perk.getInstances(type).isEmpty()) {
                return false;
            }
        }
        if (!Double.isFinite(data.get(GunProp.VELOCITY))
            || data.get(GunProp.VELOCITY) <= 0
            || !Double.isFinite(data.get(GunProp.GRAVITY))
            || data.get(GunProp.GRAVITY) < 0
            || !Double.isFinite(data.get(GunProp.EXPLOSION_RADIUS))
            || data.get(GunProp.EXPLOSION_RADIUS) < 0
            || !Double.isFinite(data.get(GunProp.SPREAD))
            || data.get(GunProp.SPREAD) < 0
            || data.get(GunProp.PROJECTILE_AMOUNT) != 1) {
            return false;
        }
        if (station instanceof MortarEntity mortar) {
            ItemStack shell = mortar.getItem(0);
            return shell.isEmpty() || shell.is(ModItems.MORTAR_SHELL.get());
        }
        var projectile = data.get(GunProp.PROJECTILE);
        return projectile.getItemId().equals("superbwarfare:cannon_shell")
            && projectile.data == null
            && !CustomData.LAUNCHABLE_ENTITY.containsKey(projectile.getItemId())
            && data.get(GunProp.SHELL_TYPE).equals("HE");
    }

    static ItemSpec ammunition(VehicleEntity vehicle) {
        if (vehicle instanceof MortarEntity) {
            return ItemSpec.of(ModItems.MORTAR_SHELL.get());
        }
        var data = vehicle.getGunData(weaponName(vehicle));
        if (data == null) {
            return null;
        }
        ItemStack stack = data.selectedAmmoConsumer().stack();
        return stack.isEmpty() ? null : ItemSpec.exact(stack);
    }

    /** Capture the exact queued engagement; an old callback cannot borrow a replacement lease. */
    public static Runnable queued(VehicleEntity vehicle, Runnable nativeShot) {
        WarfareStation station = STATIONS.get(vehicle);
        return station == null ? nativeShot : station.queued(nativeShot);
    }

    public static boolean permits(ShootParameters parameters) {
        if (!(parameters.ammoSupplier instanceof VehicleEntity vehicle)
            || !(parameters.shooter instanceof ResidentEntity)) {
            return true;
        }
        WarfareStation station = STATIONS.get(vehicle);
        return station != null && station.emitting();
    }

    public static void tickMortar(MortarEntity mortar, Runnable nativeTick) {
        WarfareStation station = STATIONS.get(mortar);
        if (station != null) {
            station.tickMortar(nativeTick);
        } else {
            if (mortar.getPersistentData().getBoolean(PENDING)) {
                mortar.getEntityData().set(MortarEntity.FIRE_TIME, 0);
                mortar.getPersistentData().remove(PENDING);
            }
            nativeTick.run();
        }
    }

    static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        WarfareStation station = STATIONS.remove(event.getEntity());
        if (station != null) {
            station.cancel();
        }
    }
}
