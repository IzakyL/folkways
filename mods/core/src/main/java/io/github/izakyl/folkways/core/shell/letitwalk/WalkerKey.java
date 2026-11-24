package io.github.izakyl.folkways.core.shell.letitwalk;

import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerServerHandler;
import dev.letitwalk.content.PilotControlsBlockEntity;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.createmod.catnip.data.Couple;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * One of a pilot station's four action keys (use, attack, drop, inventory), pressed once. A key does what its linked
 * frequencies are wired to on the walker, such as firing a cannon or swinging an arm; pressed, it powers them for
 * Create's linked-controller hold time, as a pilot's quick click would. Only keys with a frequency set are offered.
 */
record WalkerKey(WorldPos anchor, int index) implements Signal {

    static final List<String> NAMES = List.of("use", "attack", "drop", "inventory");
    // Stands in for the pilot's hands: the native handler keys a press by who made it.
    private static final UUID HANDS = UUID.nameUUIDFromBytes("folkways_core:let_it_walk/key".getBytes());

    static List<Signal> at(ServerLevel level, WorldPos pos) {
        return Walkers.station(level, pos).map(station -> {
            List<Signal> keys = new ArrayList<>();
            for (int index = 0; index < PilotControlsBlockEntity.REDSTONE_KEYS.length; index++) {
                if (frequency(station, index) != null) {
                    keys.add(new WalkerKey(pos, index));
                }
            }
            return keys;
        }).orElseGet(List::of);
    }

    private static Couple<Frequency> frequency(PilotControlsBlockEntity station, int index) {
        var first = Frequency.of(station.frequencies.getStackInSlot(2 * index));
        var second = Frequency.of(station.frequencies.getStackInSlot(2 * index + 1));
        return first == Frequency.EMPTY && second == Frequency.EMPTY ? null : Couple.create(first, second);
    }

    @Override
    public String part() {
        return "key/" + NAMES.get(index);
    }

    @Override
    public SignalType type() {
        return SignalType.PULSE;
    }

    @Override
    public Object leaseKey() {
        return anchor;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return Walkers.station(level, anchor).filter(station -> frequency(station, index) != null).isPresent();
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!(value instanceof Value.Pulse)) {
            return Write.REFUSED;
        }
        var station = Walkers.station(level, anchor).orElse(null);
        var keyed = station == null ? null : frequency(station, index);
        if (keyed == null) {
            return Write.REFUSED;
        }
        LinkedControllerServerHandler.receivePressed(level, station.getBlockPos(), HANDS, List.of(keyed), true);
        return Write.WRITTEN;
    }
}
