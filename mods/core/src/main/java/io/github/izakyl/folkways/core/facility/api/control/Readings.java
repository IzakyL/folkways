package io.github.izakyl.folkways.core.facility.api.control;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * The read layer: named probes that read one value at a place. Core registers the vanilla ones; integrations add
 * third-party state such as a bearing's angle. Facility data names a probe and where to read it.
 */
public final class Readings {

    /** Reads `on`/`off` from a block's `powered` property, or its redstone signal when it has none. */
    public static final ResourceLocation POWERED = ResourceLocation.fromNamespaceAndPath("folkways_core", "powered");

    @FunctionalInterface
    public interface Probe {

        Optional<Value> read(ServerLevel level, WorldPos at);
    }

    public interface ConfiguredProbe {

        Optional<Value> read(ServerLevel level, WorldPos at, Map<String, Value> config);

        boolean valid(Map<String, Value> config);

        default OptionalDouble range(Map<String, Value> config) {
            return OptionalDouble.empty();
        }
    }

    /**
     * A suggested reading, such as a probe of the block a part points at. Discovery lists these beside the
     * facility controls so the facility author knows which probe to read where.
     */
    public record Hint(ResourceLocation probe, WorldPos at, Map<String, Value> config, String about) {

        public Hint {
            Objects.requireNonNull(probe, "probe");
            Objects.requireNonNull(at, "at");
            config = Map.copyOf(config);
            Objects.requireNonNull(about, "about");
        }
    }

    @FunctionalInterface
    public interface Hints {

        /** The readings the block at `pos` suggests; empty for blocks this integration does not know. */
        List<Hint> at(ServerLevel level, WorldPos pos);
    }

    private static final Map<ResourceLocation, ConfiguredProbe> PROBES = new ConcurrentHashMap<>();
    private static volatile List<Hints> hints = List.of();

    private Readings() {
    }

    public static void register(ResourceLocation id, Probe probe) {
        Objects.requireNonNull(probe, "probe");
        register(id, new ConfiguredProbe() {
            @Override
            public Optional<Value> read(ServerLevel level, WorldPos at, Map<String, Value> config) {
                return probe.read(level, at);
            }

            @Override
            public boolean valid(Map<String, Value> config) {
                return config.isEmpty();
            }
        });
    }

    public static void register(ResourceLocation id, ConfiguredProbe probe) {
        if (PROBES.putIfAbsent(Objects.requireNonNull(id, "id"), Objects.requireNonNull(probe, "probe")) != null) {
            throw new IllegalStateException(id + " already reads");
        }
    }

    public static synchronized void registerHints(Hints source) {
        List<Hints> next = new ArrayList<>(hints);
        next.add(Objects.requireNonNull(source, "source"));
        hints = List.copyOf(next);
    }

    /** What the blocks at `pos` suggest reading, from every registered source. */
    public static List<Hint> hints(ServerLevel level, WorldPos pos) {
        if (!pos.in(level) || !level.isLoaded(pos.block(level))) {
            return List.of();
        }
        List<Hint> found = new ArrayList<>();
        hints.forEach(source -> found.addAll(source.at(level, pos)));
        return found;
    }

    public static Optional<ConfiguredProbe> probe(ResourceLocation id) {
        return Optional.ofNullable(PROBES.get(id));
    }

    public static Optional<Reading> of(ResourceLocation probe, WorldPos at) {
        return of(probe, at, Map.of());
    }

    public static Optional<Reading> of(ResourceLocation probe, WorldPos at, Map<String, Value> config) {
        return probe(probe).filter(found -> found.valid(config)).map(found -> level -> at.in(level)
            && level.isLoaded(at.block(level)) ? found.read(level, at, config) : Optional.empty());
    }

    /** Every reading taken now, at one instant; readings with nothing to read are left out. */
    public static Map<String, Value> snapshot(ServerLevel level, Map<String, Reading> readings) {
        Map<String, Value> taken = new LinkedHashMap<>();
        readings.forEach((name, reading) -> reading.read(level).ifPresent(value -> taken.put(name, value)));
        return taken;
    }
}
