package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Prefab facilities are facilities recognised from blocks. Overlapping native bodies are resolved by the facility ownership index. A recognised facility supplies its own planning projections.
 *
 * <p>Implement world and planning projections in {@link Facility#projections}. Register Java routines with
 * {@link Routines#register} under IDs in the prefab's namespace, and name their uses through
 * {@link Facility#routines} and {@link Routine.Use}. A prefab without callable routines can return an empty map.
 */
public final class Prefabs {

    @FunctionalInterface
    public interface Recogniser {

        /** The prefab whose anchor is the block at `pos`, if this recogniser knows the block. */
        Optional<Facility> at(ServerLevel level, BlockPos pos);
    }

    @FunctionalInterface
    public interface Coverage {
        List<? extends Facility> at(ServerLevel level, BlockPos pos);
    }

    private record Entry(ResourceLocation id, Recogniser recogniser, Coverage coverage) {
    }

    private static volatile List<Entry> recognisers = List.of();

    private Prefabs() {
    }

    public static synchronized void register(ResourceLocation id, Recogniser recogniser) {
        register(id, recogniser, (level, pos) -> recogniser.at(level, pos).stream().toList());
    }

    public static synchronized void register(ResourceLocation id, Recogniser recogniser, Coverage coverage) {
        Objects.requireNonNull(recogniser, "recogniser");
        if (recognisers.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalStateException(id + " already recognises prefabs");
        }
        List<Entry> next = new ArrayList<>(recognisers);
        next.add(new Entry(Objects.requireNonNull(id, "id"), recogniser, Objects.requireNonNull(coverage)));
        next.sort(java.util.Comparator.comparing(Entry::id));
        recognisers = List.copyOf(next);
    }

    static List<Facility> covering(ServerLevel level, BlockPos pos) {
        List<Facility> candidates = nativeCovering(level, pos);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return Facilities.ownerOf(level, WorldPos.of(level, pos)).stream()
            .filter(owner -> candidates.stream().anyMatch(prefab -> prefab.id().equals(owner.id()))).toList();
    }

    static boolean covered(ServerLevel level, Facility facility) {
        return DefinedFacilities.forOwnership(level)
            .anyMatch(definition -> definition.extent().overlaps(facility.extent(level)));
    }

    static List<Facility> nativeCovering(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return List.of();
        }
        return recognisers.stream().flatMap(entry -> entry.coverage().at(level, pos).stream())
            .map(Facility.class::cast).toList();
    }

    public static Optional<Facility> at(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || DefinedFacilities.covers(level, WorldPos.of(level, pos))) {
            return Optional.empty();
        }
        for (Entry entry : recognisers) {
            Optional<Facility> found = entry.recogniser().at(level, pos);
            if (found.isPresent()) {
                var accepted = found.filter(facility -> Facilities.ownerOf(level, facility.site())
                    .filter(owner -> owner.id().equals(facility.id())).isPresent());
                if (accepted.isPresent()) {
                    return accepted;
                }
            }
        }
        return Optional.empty();
    }
}
