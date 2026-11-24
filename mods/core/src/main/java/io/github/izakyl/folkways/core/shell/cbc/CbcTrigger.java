package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

public record CbcTrigger(WorldPos anchor, CannonMountBlockEntity mount) implements Signal {

    private static Mob operator;

    public static Mob operator() {
        return operator;
    }

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
        return SignalType.PULSE;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return CbcIntegration.attached(level, anchor, mount) && CbcCannon.mounted(mount).isPresent();
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!(value instanceof Value.Pulse) || !CbcIntegration.attached(level, anchor, mount)) {
            return Write.REFUSED;
        }
        var mounted = CbcCannon.mounted(mount).orElse(null);
        if (mounted == null || !mounted.loaded() || !mounted.cannon().canBeFiredOnController(mount)) {
            return Write.REFUSED;
        }
        var breech = mounted.quickBreech();
        if (breech != null && breech.getOpenProgress() > 0) {
            preparePulse(level, writer);
            return Write.BUSY;
        }
        Mob previous = operator;
        operator = writer;
        try {
            mounted.cannon().onRedstoneUpdate(level, mounted.entity(), true, 15, mount);
        } finally {
            operator = previous;
        }
        return Write.WRITTEN;
    }

    @Override
    public void preparePulse(ServerLevel level, Mob writer) {
        if (CbcIntegration.attached(level, anchor, mount)) {
            CbcCannon.mounted(mount).filter(CbcCannon::loaded).ifPresent(mounted -> {
                var breech = mounted.quickBreech();
                if (breech != null && breech.isOpen() && !breech.onInteractionCooldown()) {
                    breech.toggleOpening();
                    CbcBreech.syncBreech(mounted);
                }
            });
        }
    }
}
