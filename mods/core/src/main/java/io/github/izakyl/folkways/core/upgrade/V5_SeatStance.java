package io.github.izakyl.folkways.core.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import net.minecraft.nbt.CompoundTag;

/**
 * Vanilla's weapon-station seat marker (`lease`, `vehicle`) becomes the engine's seat-stance marker (`token`,
 * `entity`), so a body saved in a station seat is still let out of it on load.
 */
public final class V5_SeatStance implements Step {
    private static final String STATION_SEAT = "folkways_vanilla:station_seat";
    private static final String SEAT_STANCE = "folkways_core:seat_stance";

    @Override
    public int target() {
        return 5;
    }

    @Override
    public Surface surface() {
        return Surface.ENTITY;
    }

    @Override
    public void apply(CompoundTag raw) {
        CompoundTag persistent = raw.getCompound("NeoForgeData");
        if (!persistent.contains(STATION_SEAT)) {
            return;
        }
        CompoundTag loan = persistent.getCompound(STATION_SEAT);
        persistent.remove(STATION_SEAT);
        if (persistent.contains(SEAT_STANCE)) {
            return;
        }
        CompoundTag marker = new CompoundTag();
        if (loan.hasUUID("lease")) {
            marker.putUUID("token", loan.getUUID("lease"));
        }
        if (loan.hasUUID("vehicle")) {
            marker.putUUID("entity", loan.getUUID("vehicle"));
        }
        persistent.put(SEAT_STANCE, marker);
    }
}
