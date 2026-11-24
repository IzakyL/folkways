package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.GunEventHandler;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * A turret's trigger as a pulse, pressed from the gunner's seat. A press is answered now: written when the gun's
 * magazine holds a round and its native shot is under way, busy while it reloads (a press with an empty magazine and
 * rounds in the gun's container starts the native reload, as a player's would), refused when the gun has nothing to
 * fire. The shot spends the gun's own ammunition.
 */
public record WarfareTrigger(VehicleEntity vehicle) implements Signal {

    public static final String FIRE = "fire";

    private static final class Shot {
        final Mob gunner;
        final WarfareTrigger trigger;
        final Consumer<Write> settled;
        boolean queued;

        Shot(Mob gunner, WarfareTrigger trigger, Consumer<Write> settled) {
            this.gunner = gunner;
            this.trigger = trigger;
            this.settled = settled;
        }
    }

    // The press whose native shot is being queued, and the shot leaving the gun now.
    private static final Map<VehicleEntity, Shot> SCHEDULING = new IdentityHashMap<>();
    private static final Map<VehicleEntity, Shot> EMITTING = new IdentityHashMap<>();

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(vehicle.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(vehicle);
    }

    @Override
    public String part() {
        return FIRE + "/" + vehicle.getUUID();
    }

    @Override
    public Object leaseKey() {
        return vehicle.getUUID();
    }

    @Override
    public SignalType type() {
        return SignalType.PULSE;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return WarfareMount.valid(level, vehicle, this);
    }

    @Override
    public Optional<Stances> stance(ServerLevel level) {
        return WarfareMount.stance(level, vehicle);
    }

    @Override
    public boolean enter(Mob writer) {
        return WarfareMount.manned(vehicle, writer);
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        return value instanceof Value.Pulse ? press(level, writer, ignored -> {}) : Write.REFUSED;
    }

    @Override
    public Write press(ServerLevel level, Mob writer, Consumer<Write> settled) {
        if (!valid(level) || !WarfareMount.manned(vehicle, writer)) {
            return Write.REFUSED;
        }
        String weapon = WarfareMount.weapon(vehicle);
        GunData data = vehicle.getGunData(weapon);
        if (data == null) {
            return Write.REFUSED;
        }
        // The gun counts its spare rounds once in a while; a press counts them afresh, as rounds may just have come in.
        data.cachedBackupAmmo = -1;
        if (!data.canShoot(vehicle)) {
            if (data.reloading()) {
                return Write.BUSY;
            }
            if (data.useBackpackAmmo() || !data.hasBackupAmmo(vehicle)) {
                return Write.REFUSED;
            }
            vehicle.modifyGunData(weapon, loaded -> {
                loaded.cachedBackupAmmo = -1;
                GunEventHandler.INSTANCE.tryStartReload(vehicle, loaded, true);
            });
            return Write.BUSY;
        }
        Shot shot = new Shot(writer, this, settled);
        SCHEDULING.put(vehicle, shot);
        try {
            vehicle.vehicleShoot(writer, weapon, (UUID) null, (Vec3) null);
        } finally {
            SCHEDULING.remove(vehicle);
        }
        return shot.queued ? Write.WRITTEN : Write.REFUSED;
    }

    /** The native shot a press queued, bound to its gunner; empty otherwise. */
    static Optional<Runnable> queued(VehicleEntity vehicle, Runnable nativeShot) {
        Shot shot = SCHEDULING.get(vehicle);
        if (shot == null) {
            return Optional.empty();
        }
        shot.queued = true;
        return Optional.of(() -> {
            if (!(vehicle.level() instanceof ServerLevel level) || !shot.trigger.valid(level)
                || !WarfareMount.manned(vehicle, shot.gunner)) {
                shot.settled.accept(Write.REFUSED);
                return;
            }
            EMITTING.put(vehicle, shot);
            try {
                nativeShot.run();
                shot.settled.accept(Write.WRITTEN);
            } finally {
                EMITTING.remove(vehicle);
            }
        });
    }

    /** Whether the native shot now leaving the gun is the one a press queued for this shooter. */
    static boolean emitting(VehicleEntity vehicle, ShootParameters parameters) {
        Shot shot = EMITTING.get(vehicle);
        return shot != null && shot.gunner == parameters.shooter;
    }
}
