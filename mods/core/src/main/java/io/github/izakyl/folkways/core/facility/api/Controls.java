package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Finds the controls at a block. A block may have several, each named by its {@link Control#part part}: for example,
 * separate input and output ports. Every adapter is asked and their answers are merged; only
 * answers for the same part compete, and the adapter of higher priority wins it. The block's ports of every kind come
 * from {@link Ports} by the same rule. What is found is only a candidate: nothing belongs to a facility until a facility
 * names it.
 *
 * <p>Priorities by convention: integrations for specific blocks use {@link #SPECIFIC}; Core's vanilla adapters use
 * {@link #VANILLA}.
 */
public final class Controls {

    public static final int SPECIFIC = 1000;
    public static final int VANILLA = 0;

    @FunctionalInterface
    public interface Adapter {

        /**
         * The signals this adapter knows at `pos`, each with its own part; empty when it knows none. Parts it does not
         * name are left to other adapters. The cell is told in its realm, which may be a moving structure: a control
         * keeps it as its anchor.
         */
        List<Signal> at(ServerLevel level, WorldPos pos);
    }

    @FunctionalInterface
    public interface MotionAdapter {
        Optional<Motion> at(ServerLevel level, WorldPos pos);
    }

    private static volatile List<MotionAdapter> motionAdapters = List.of();

    public static synchronized void registerMotion(MotionAdapter adapter) {
        var next = new ArrayList<>(motionAdapters);
        next.add(adapter);
        motionAdapters = List.copyOf(next);
    }

    public static List<Motion> motion(ServerLevel level, WorldPos pos) {
        if (!loaded(level, pos)) {
            return List.of();
        }
        return motionAdapters.stream().flatMap(adapter -> adapter.at(level, pos).stream()).toList();
    }

    private record Entry(ResourceLocation owner, int priority, Adapter adapter) {
    }

    private static volatile List<Entry> adapters = List.of();

    private Controls() {
    }

    public static synchronized void register(ResourceLocation owner, int priority, Adapter adapter) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adapter, "adapter");
        if (adapters.stream().anyMatch(entry -> entry.owner().equals(owner))) {
            throw new IllegalStateException(owner + " already adapts controls");
        }
        List<Entry> next = new ArrayList<>(adapters);
        next.add(new Entry(owner, priority, adapter));
        next.sort(Comparator.comparingInt(Entry::priority).reversed());
        adapters = List.copyOf(next);
    }

    static boolean loaded(ServerLevel level, WorldPos pos) {
        return pos.in(level) && level.isLoaded(pos.block(level));
    }

    public static List<Signal> signals(ServerLevel level, WorldPos pos) {
        if (!loaded(level, pos)) {
            return List.of();
        }
        Map<String, Signal> found = new LinkedHashMap<>();
        for (Entry entry : adapters) {
            for (Signal signal : entry.adapter().at(level, pos)) {
                found.putIfAbsent(signal.part(), signal);
            }
        }
        return List.copyOf(found.values());
    }

    /** The signal of this part at the block, from the adapter of highest priority that names it. */
    public static Optional<Signal> signal(ServerLevel level, WorldPos pos, String part) {
        if (!loaded(level, pos)) {
            return Optional.empty();
        }
        for (Entry entry : adapters) {
            for (Signal signal : entry.adapter().at(level, pos)) {
                if (signal.part().equals(part)) {
                    return Optional.of(signal);
                }
            }
        }
        return Optional.empty();
    }

    /** Every control at the block: its signals, then its ports. */
    public static List<Control> at(ServerLevel level, WorldPos pos) {
        List<Control> found = new ArrayList<>(signals(level, pos));
        found.addAll(Ports.all(level, pos));
        found.addAll(motion(level, pos));
        return found;
    }

    /** The candidates within an area, for a player marking out a facility. */
    public static List<Control> within(ServerLevel level, Collection<WorldPos> cells) {
        List<Control> found = new ArrayList<>();
        cells.forEach(cell -> found.addAll(at(level, cell)));
        return found;
    }

    public static Optional<Control> resolve(ServerLevel level, ControlRef ref) {
        WorldPos pos = ref.at();
        Optional<Signal> signal = signal(level, pos, ref.part());
        if (signal.isPresent()) {
            return signal.map(Control.class::cast);
        }
        return Ports.part(level, pos, ref.part()).map(Control.class::cast).or(() ->
            motion(level, pos).stream().filter(sensor -> sensor.part().equals(ref.part())).map(Control.class::cast)
                .findFirst());
    }
}
