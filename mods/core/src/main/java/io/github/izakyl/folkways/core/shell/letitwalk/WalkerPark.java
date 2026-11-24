package io.github.izakyl.folkways.core.shell.letitwalk;

import dev.letitwalk.content.WalkerCoreBlockEntity;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** The walker core's parking switch: parked, the hull settles on its legs and ignores its pilot. */
record WalkerPark(WorldPos anchor) implements Signal {

    static final String PART = "park";

    @Override
    public String part() {
        return PART;
    }

    @Override
    public SignalType type() {
        return SignalType.BOOL;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return core(level).isPresent();
    }

    @Override
    public Optional<Reading> feedback() {
        return Readings.of(LetItWalkIntegration.id("parked"), anchor);
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!(value instanceof Value.Bool(boolean parked))) {
            return Write.REFUSED;
        }
        WalkerCoreBlockEntity core = core(level).orElse(null);
        if (core == null) {
            return Write.REFUSED;
        }
        if (core.parked != parked) {
            core.parked = parked;
            core.syncToClients();
        }
        return Write.WRITTEN;
    }

    private Optional<WalkerCoreBlockEntity> core(ServerLevel level) {
        return Walkers.coreAt(level, anchor);
    }
}
