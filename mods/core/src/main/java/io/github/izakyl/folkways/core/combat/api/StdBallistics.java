package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.FacilityScriptContexts;
import io.github.izakyl.folkways.core.script.api.ScriptSteps;
import io.github.izakyl.folkways.core.script.api.StdBindings;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.solve.Ballistics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/**
 * The flight arithmetic behind {@code @std//ballistics}: solving a lay, sampling a flight and checking a shot against a
 * snapshot of the colony. Which load, which arc and when to fire stay in the script.
 */
@StarlarkBuiltin(name = "ballistics", doc = "Gun laying and flight prediction.")
public final class StdBallistics implements StarlarkValue {

    private static final StdBallistics INSTANCE = new StdBallistics();
    private static final int MAX_PATH = 1201;

    private StdBallistics() {
    }

    // Facility scripts that may check shots against the colony get a snapshot of it, taken on the server thread
    // around the facility with its own entities left out; a trial copy launches nothing, so it gets an empty one.
    private static final double SHOT_RADIUS = 320;

    /** Binds the `_ballistics` builtins and the colony snapshot their facility scripts check shots against. */
    public static void install() {
        StdBindings.bind("_ballistics", "_ballistics", INSTANCE);
        FacilityScriptContexts.register(new FacilityScriptContexts.Extension() {
            @Override
            public boolean wanted(String source) {
                return source.contains("avoids_colony") || source.contains("@std//ballistics");
            }

            @Override
            public Object take(ServerLevel level, Colony colony, FacilityDefinition facility) {
                List<Entity> own = facility.extent().elements().stream()
                    .filter(FacilityExtent.Entity.class::isInstance)
                    .map(element -> level.getEntity(((FacilityExtent.Entity) element).id()))
                    .filter(Objects::nonNull).toList();
                Vec3 center = WorldSpaces.world(level, facility.site()).orElse(null);
                return center == null ? null : ShotPlanning.snapshot(level, colony, own, center, SHOT_RADIUS);
            }

            @Override
            public Object trial() {
                return new ShotPlanning.Snapshot(List.of(), List.of(), null);
            }

            @Override
            public void install(StarlarkThread thread, Object context) {
                thread.setThreadLocal(ShotPlanning.Snapshot.class, (ShotPlanning.Snapshot) context);
            }
        });
    }

    @StarlarkMethod(name = "solve", doc = "Both arcs from the origin to a relative XYZ for a flight model.",
        parameters = {@Param(name = "model", named = true), @Param(name = "offset", named = true)},
        useStarlarkThread = true)
    public StarlarkList<Object> solve(Object model, Object offset, StarlarkThread thread) throws EvalException {
        try {
            ScriptSteps.charge(thread, 400);
            Vec3 at = Gunnery.vector(offset, "offset");
            List<Object> shots = new ArrayList<>();
            for (var shot : Ballistics.solve(model(model), new Ballistics.Point(at.x, at.y, at.z))) {
                shots.add(Dict.copyOf(thread.mutability(), Map.of("yaw", StarlarkFloat.of(shot.aim().yaw()),
                    "pitch", StarlarkFloat.of(shot.aim().pitch()), "ticks", StarlarkFloat.of(shot.ticks()))));
            }
            return StarlarkList.copyOf(thread.mutability(), shots);
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    @StarlarkMethod(name = "aim", doc = "The aim part's angles that land a shot on a world XYZ, or None.",
        parameters = {@Param(name = "weapon", named = true), @Param(name = "model", named = true),
            @Param(name = "target", named = true), @Param(name = "indirect", named = true, defaultValue = "False")},
        useStarlarkThread = true)
    public Object aim(Object weapon, Object model, Object target, Boolean indirect, StarlarkThread thread)
            throws EvalException {
        try {
            ScriptSteps.charge(thread, 2400);
            var angles = Gunnery.aim(weapon(weapon), model(model), Gunnery.vector(target, "target"), indirect);
            return angles.isEmpty() ? Starlark.NONE : StarlarkList.copyOf(thread.mutability(),
                List.of(StarlarkFloat.of(angles.get()[0]), StarlarkFloat.of(angles.get()[1])));
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    @StarlarkMethod(name = "flight", doc = "The flight from the muzzle at the given angles, until it reaches the"
        + " target's nearest approach or falls far below the muzzle: points and whether it arrives.",
        parameters = {@Param(name = "weapon", named = true), @Param(name = "model", named = true),
            @Param(name = "angles", named = true), @Param(name = "target", named = true, defaultValue = "None"),
            @Param(name = "depth", named = true, defaultValue = "256")},
        useStarlarkThread = true)
    public Dict<String, Object> flight(Object weapon, Object model, Object angles, Object target, Object depth,
                                      StarlarkThread thread) throws EvalException {
        try {
            var gun = weapon(weapon);
            if (!(angles instanceof List<?> axes) || axes.size() != 2) {
                throw new IllegalArgumentException("angles must be [yaw, pitch]");
            }
            var launch = gun.launch(model(model), Gunnery.number(axes.get(0), "yaw"),
                Gunnery.number(axes.get(1), "pitch"));
            Vec3 aimed = target == Starlark.NONE ? null : Gunnery.vector(target, "target");
            var flight = Gunnery.flight(launch, aimed, launch.muzzle().y - Gunnery.number(depth, "depth"));
            ScriptSteps.charge(thread, 4 * flight.points().size());
            List<Object> points = new ArrayList<>();
            for (Vec3 point : flight.points().subList(0, Math.min(MAX_PATH, flight.points().size()))) {
                points.add(StarlarkList.copyOf(thread.mutability(),
                    List.of(StarlarkFloat.of(point.x), StarlarkFloat.of(point.y), StarlarkFloat.of(point.z))));
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("points", StarlarkList.copyOf(thread.mutability(), points));
            result.put("arrives", flight.arrives());
            return Dict.copyOf(thread.mutability(), result);
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    @StarlarkMethod(name = "avoids_colony", doc = "Whether a flight and its blast keep clear of colony members and"
        + " blocks, as automatic fire must.",
        parameters = {@Param(name = "points", named = true), @Param(name = "blast", named = true),
            @Param(name = "margin", named = true, defaultValue = "0.35")},
        useStarlarkThread = true)
    public boolean avoidsColony(Object points, Object blast, Object margin, StarlarkThread thread)
            throws EvalException {
        ShotPlanning.Snapshot snapshot = thread.getThreadLocal(ShotPlanning.Snapshot.class);
        if (snapshot == null) {
            throw Starlark.errorf("avoids_colony is available in facility routines of a colony");
        }
        try {
            List<Vec3> path = new ArrayList<>();
            if (!(points instanceof List<?> list)) {
                throw new IllegalArgumentException("points must be a list of [x, y, z]");
            }
            if (list.size() > MAX_PATH) {
                throw new IllegalArgumentException("at most " + MAX_PATH + " flight samples are allowed");
            }
            for (Object point : list) {
                path.add(Gunnery.vector(point, "point"));
            }
            ScriptSteps.charge(thread, path.size() * (8 + snapshot.blocks().size() / 16));
            return snapshot.avoids(path, Gunnery.number(margin, "margin"),
                Gunnery.number(blast, "blast"));
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    private static Gunnery.Weapon weapon(Object value) {
        if (!(value instanceof Map<?, ?> data)) {
            throw new IllegalArgumentException("weapon must be an aim part's ballistics reading");
        }
        return Gunnery.Weapon.of(data);
    }

    private static Ballistics.Model model(Object value) {
        if (!(value instanceof Map<?, ?> data)) {
            throw new IllegalArgumentException("model must be {speed, gravity, retention}");
        }
        return Gunnery.model(data);
    }
}
