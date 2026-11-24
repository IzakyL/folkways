package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A named, parameterised piece of work a {@link Facility} can do. A routine is not an executor and assigns no one: it
 * answers one run with a subgraph of the plan, a {@link Refinement.Change}, and the plan decides who does which node,
 * in parallel where the nodes allow, with the same travel, holds, preemption and stall reasons as any other work.
 *
 * <p>The nodes may be any work: writes to controls and waits on readings for a facility, tilling and sowing for a farm.
 * Order goes in `links`, and `workerGroups` binds steps that one resident must do together. The graph must end in one
 * node carrying the run's id ({@link Call#id}): the run is done when that node is.
 *
 * <p>{@link #expand} runs on the planner's thread and must not touch the world: it plans from the readings and rig
 * taken when the run was asked for. Within a run, a node's own failure is answered by the node
 * ({@link io.github.izakyl.folkways.core.work.api.Node#failing}: wait and try again, or fail). A node that fails gives
 * the whole run up. The trigger decides whether to resubmit with fresh readings.
 */
public interface Routine {

    ResourceLocation id();

    default String description() {
        return id().toString();
    }

    default JsonObject argumentSchema() {
        return argumentsSchema(Map.of());
    }

    static JsonObject argumentsSchema(Map<String, String> fields, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        fields.forEach((name, type) -> {
            JsonObject field = new JsonObject();
            field.addProperty("type", type);
            properties.add(name, field);
        });
        schema.add("properties", properties);
        var mandatory = new com.google.gson.JsonArray();
        for (String field : required) {
            mandatory.add(field);
        }
        schema.add("required", mandatory);
        return schema;
    }

    /** Empty when the routine cannot run as called; the run then stands unrefined, as a stall. */
    Optional<Refinement.Change> expand(Call call);

    /** Why the call cannot run, judged on the server thread when it is asked for; empty when it can. */
    default Optional<String> check(Call call) {
        return Optional.empty();
    }

    /** Whether this routine can dispatch its expanded work through staffed posts. */
    default boolean runsAtPosts() {
        return false;
    }

    /** Executes a checked call on the server thread when all its writes can be done remotely. */
    default Optional<Outcome> direct(ServerLevel level, FacilityDefinition facility, Call call) {
        return Optional.empty();
    }

    /** A failure reported by a routine or one of its nodes. */
    record Failure(String translationKey, boolean retryable) implements RefusalKind {
    }

    /** Validates library-specific facility settings during preview. */
    default void validate(ServerLevel level, FacilityDefinition facility, Use use) {
    }

    /** Captures routine-specific inputs on the server thread before plan expansion. */
    default Call snapshot(ServerLevel level, Call call) {
        return call;
    }

    /** Refreshes world-dependent readings when the arguments have already been pinned. */
    default Call resume(ServerLevel level, Call call) {
        return call;
    }

    /** Values published by a routine use under its facility name. */
    default Map<String, Value> readings(ServerLevel level, FacilityDefinition facility, Use use) {
        return Map.of();
    }

    /** Control names or argument keys describing the potential writes without a bound facility. */
    default List<String> writeTargets() {
        return List.of();
    }

    /** The named controls a run may write. */
    default Set<String> writes(Map<String, Value> arguments) {
        return Set.of();
    }

    /** Whether a saved call can be planned from the currently available controls. */
    default boolean automatic(ServerLevel level, Colony colony,
                              FacilityDefinition facility, Map<String, Value> arguments) {
        return true;
    }

    default boolean ready(ServerLevel level, FacilityDefinition facility, Map<String, Value> arguments) {
        return true;
    }

    /** A polyline to draw while the facility is selected, evaluated on the server thread. */
    default Optional<Path> path(ServerLevel level, FacilityDefinition facility, Use use) {
        return Optional.empty();
    }

    /** An optional center for the facility's range rings, evaluated on the server thread. */
    default Optional<Vec3> overlayCenter(ServerLevel level, FacilityDefinition facility, Use use) {
        return Optional.empty();
    }

    /** A drawn path; {@code strike} stops it, marked, where it first meets a block when it is drawn. */
    record Path(List<Vec3> points, boolean endsAtMark, boolean strike) {
        public Path {
            points = List.copyOf(points);
            if (points.isEmpty()) {
                throw new IllegalArgumentException("a path needs at least one point");
            }
        }

        public Path(List<Vec3> points, boolean endsAtMark) {
            this(points, endsAtMark, false);
        }

        /** The path cut where it first meets a block, marked there; unchanged unless it strikes. */
        public Path struck(net.minecraft.server.level.ServerLevel level) {
            if (!strike) {
                return this;
            }
            List<Vec3> cut = new java.util.ArrayList<>();
            cut.add(points.getFirst());
            for (int i = 1; i < points.size(); i++) {
                Vec3 from = points.get(i - 1);
                Vec3 to = points.get(i);
                if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(to))) {
                    return new Path(cut, false);
                }
                var hit = level.clip(new net.minecraft.world.level.ClipContext(from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                    cut.add(hit.getLocation());
                    return new Path(cut, true);
                }
                cut.add(to);
            }
            return new Path(cut, endsAtMark);
        }
    }

    /**
     * One run as the routine plans it: the boundary id, who asked, the facility's identity and site, its parameters,
     * the run's arguments over the routine's defaults, the readings taken when it was asked for, and, for a facility
     * with a body, its rig.
     */
    record Call(UUID id, ResourceLocation owner, UUID facility, WorldPos site, Map<String, Value> parameters,
                Map<String, Value> arguments, Map<String, Value> readings, Optional<Rig> rig) {

        public Call {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(facility, "facility");
            Objects.requireNonNull(site, "site");
            parameters = Map.copyOf(parameters);
            arguments = Map.copyOf(arguments);
            readings = Map.copyOf(readings);
            Objects.requireNonNull(rig, "rig");
        }
    }

    sealed interface Argument {
        Value resolve(Map<String, Value> parameters);

        record Literal(Value value) implements Argument {
            @Override
            public Value resolve(Map<String, Value> parameters) {
                return value;
            }
        }

        record Parameter(String name) implements Argument {
            @Override
            public Value resolve(Map<String, Value> parameters) {
                Value value = parameters.get(name);
                if (value == null) {
                    throw new IllegalArgumentException(name);
                }
                return value;
            }
        }

        record ObjectValue(Map<String, Argument> entries) implements Argument {
            public ObjectValue {
                entries = Map.copyOf(entries);
            }

            @Override
            public Value resolve(Map<String, Value> parameters) {
                Map<String, Value> values = new LinkedHashMap<>();
                entries.forEach((key, argument) -> values.put(key, argument.resolve(parameters)));
                return DefinitionCodec.object(values);
            }
        }

        record Array(List<Argument> entries) implements Argument {
            public Array {
                entries = List.copyOf(entries);
            }

            @Override
            public Value resolve(Map<String, Value> parameters) {
                List<Value> resolved = entries.stream().map(entry -> entry.resolve(parameters)).toList();
                if (resolved.stream().allMatch(Value.Real.class::isInstance)) {
                    return new Value.Vector(resolved.stream().map(Value.Real.class::cast).map(Value.Real::value).toList());
                }
                return new Value.Sequence(resolved);
            }
        }
    }

    /** A facility's use of a library routine under its own name, with default arguments: `{"use": ..., "with": ...}`. */
    record Use(ResourceLocation routine, Map<String, Value> with, Map<String, Argument> references) {

        public Use(ResourceLocation routine, Map<String, Value> with) {
            this(routine, with, Map.of());
        }

        public Use {
            Objects.requireNonNull(routine, "routine");
            with = Map.copyOf(with);
            references = Map.copyOf(references);
        }

        public Use resolve(Map<String, Value> parameters) {
            if (references.isEmpty()) {
                return this;
            }
            Map<String, Value> resolved = new LinkedHashMap<>(with);
            references.forEach((name, argument) -> resolved.put(name, argument.resolve(parameters)));
            return new Use(routine, resolved);
        }

        public Map<String, Value> arguments(Map<String, Value> given) {
            Map<String, Value> all = new LinkedHashMap<>(with);
            all.putAll(given);
            return all;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("use", routine.toString());
            tag.put("with", Value.saveAll(with));
            if (!references.isEmpty()) {
                tag.put("references", DefinitionCodec.saveReferences(references));
            }
            return tag;
        }

        public static Use load(CompoundTag tag) {
            return new Use(ResourceLocation.parse(tag.getString("use")), Value.loadAll(tag.getCompound("with")),
                DefinitionCodec.loadReferences(tag.getCompound("references")));
        }
    }
}
