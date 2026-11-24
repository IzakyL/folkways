package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/**
 * A mounted autocannon's fire input: the redstone level its mount would see. The breech keeps firing from its
 * ammunition while the level is above zero, at CBC's cadence for that level; zero stops it.
 */
record CbcFireRate(WorldPos anchor, CannonMountBlockEntity mount) implements Signal {

    @Override
    public String part() {
        return "fire";
    }

    @Override
    public Object leaseKey() {
        return anchor;
    }

    @Override
    public SignalType type() {
        return new SignalType.Real(0, 15, 1);
    }

    @Override
    public boolean valid(ServerLevel level) {
        return CbcIntegration.attached(level, anchor, mount) && CbcCannon.autocannon(mount)
            .filter(gun -> gun.cannon().canBeFiredOnController(mount)).isPresent();
    }

    @Override
    public Optional<Reading> feedback() {
        return Readings.of(CbcIntegration.id("fire_rate"), anchor);
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!(value instanceof Value.Real power) || type().check(value).isPresent() || !valid(level)) {
            return Write.REFUSED;
        }
        var gun = CbcCannon.autocannon(mount).orElseThrow();
        gun.cannon().onRedstoneUpdate(level, gun.entity(), true, (int) Math.round(power.value()), mount);
        return Write.WRITTEN;
    }
}
