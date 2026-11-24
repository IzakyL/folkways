package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;

/**
 * A Ram Rod used on the muzzle of a mounted big cannon: each pulse is one stroke, which drives the munitions nearest
 * the muzzle one cell deeper by CBC's rules. A stroke takes the tool's cooldown; one that moves nothing is refused.
 */
record CbcRam(WorldPos anchor, CannonMountBlockEntity mount) implements Signal {

    @Override
    public String part() {
        return "ram";
    }

    @Override
    public Object leaseKey() {
        return anchor;
    }

    @Override
    public SignalType type() {
        return SignalType.PULSE;
    }

    @Override
    public Cost cost() {
        int cooldown = CBCConfigs.server().cannons.loadingToolCooldown.get();
        return cooldown > 0 ? new Cost.Ticks(cooldown) : Cost.INSTANT;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return CbcIntegration.attached(level, anchor, mount) && CbcCannon.mounted(mount).filter(CbcMuzzle::open)
            .isPresent();
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!(value instanceof Value.Pulse) || !CbcIntegration.attached(level, anchor, mount)) {
            return Write.REFUSED;
        }
        var mounted = CbcCannon.mounted(mount).filter(CbcMuzzle::open).orElse(null);
        if (mounted == null) {
            return Write.REFUSED;
        }
        var refused = CbcMuzzle.ram(level, mount, mounted);
        if (refused.isEmpty()) {
            CbcBreech.refuse(mount, "");
            return Write.WRITTEN;
        }
        if (refused.get().isEmpty()) {
            return Write.BUSY;
        }
        CbcBreech.refuse(mount, refused.get());
        return Write.REFUSED;
    }
}
