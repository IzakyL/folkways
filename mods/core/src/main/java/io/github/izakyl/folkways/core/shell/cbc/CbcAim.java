package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

record CbcAim(WorldPos anchor, CannonMountBlockEntity mount) implements Signal {

    @Override
    public String part() {
        return "aim";
    }

    @Override
    public Object leaseKey() {
        return anchor;
    }

    @Override
    public SignalType type() {
        var entity = mount.getContraption();
        return new SignalType.Vector(List.of(new SignalType.Real(-360, 360, 0),
            entity == null ? new SignalType.Real(0, 0, 0)
                : new SignalType.Real(-entity.maximumDepression(), entity.maximumElevation(), 0)));
    }

    @Override
    public boolean valid(ServerLevel level) {
        return CbcIntegration.attached(level, anchor, mount) && CbcCannon.assembled(mount)
            .filter(entity -> entity.canBeTurnedByController(mount) && !entity.isStalled())
            .isPresent();
    }

    /** The cannon's geometry, bore and native flight figures, for laying it from a script. */
    @Override
    public java.util.Map<String, Reading> readings() {
        return java.util.Map.of("ballistics", level -> CbcIntegration.attached(level, anchor, mount)
            ? CbcBallistics.read(this) : Optional.empty());
    }

    @Override
    public Optional<Reading> feedback() {
        return Optional.of(level -> CbcIntegration.attached(level, anchor, mount)
            ? Optional.of(CbcCrankAim.observed(mount)) : Optional.empty());
    }

    @Override
    public Optional<Reading> commandFeedback() {
        return Optional.of(level -> CbcCrankAim.reached(mount));
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!valid(level) || !(value instanceof Value.Vector angles) || type().check(value).isPresent()) {
            return Write.REFUSED;
        }
        return CbcCrankAim.drive(level, mount, angles);
    }
}
