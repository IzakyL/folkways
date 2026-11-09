import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";

export type MountedTick = {
  tick: number; uuid: string; x: number; y: number; z: number; yaw: number;
  horse: boolean; horseX: number; horseY: number; horseZ: number; horseYaw: number; bodyYaw: number;
  stage: string; going: string; runner: string; node: string; distance: number;
  navDone: boolean; path: number; next: number; target: string; goals: string;
  wantedX: number; wantedZ: number; speed: number;
};

export function mountedMotion(server: MinecraftServer, action: "start" | "stop", colony: string, uuids: string[]) {
  return server.run(MOTION, { action, colony, uuids });
}

export function settledMotion(rows: MountedTick[], uuids: string[], graceTicks = 20) {
  return uuids.map((uuid) => {
    const riderRows = rows.filter((r) => r.uuid === uuid);
    const arrival = riderRows.find((r) => r.horse && r.runner === "holding" && r.distance >= 0 && r.distance <= .9);
    const tail = arrival ? riderRows.filter((r) => r.tick > arrival.tick + graceTicks) : [];
    let travel = 0;
    let turn = 0;
    let paths = 0;
    for (let i = 1; i < tail.length; i++) {
      const a = tail[i - 1], b = tail[i];
      travel += Math.hypot(b.horseX - a.horseX, b.horseZ - a.horseZ);
      turn += Math.abs(((b.horseYaw - a.horseYaw) % 360 + 540) % 360 - 180);
      if (b.path !== a.path) {
        paths++;
      }
    }
    return { uuid, arrivalTick: arrival?.tick, graceTicks, ticks: tail.length, travel, turn, paths,
      mounted: tail.every((r) => r.horse),
      arrived: tail.every((r) => r.runner === "holding"),
      maxDistance: Math.max(...tail.map((r) => r.distance)), stages: [...new Set(tail.map((r) => r.stage))],
      goals: [...new Set(tail.map((r) => r.goals))] };
  });
}

const MOTION = defineTask<Record<string, any>, MountedTick[]>({
  name: "folkways.promo.mounted-tick-motion",
  side: "server",
  limits: { maxNodes: 2_000_000 },
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.core.api.work.Node",
      "io.github.izakyl.folkways.core.api.work.Stances",
      "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
      "io.github.izakyl.folkways.core.engine.labor.Labor",
      "io.github.izakyl.folkways.vanilla.person.ResidentEntity",
      "io.github.izakyl.folkways.vanilla.combat.weapon.ResidentMounts",
      "net.neoforged.neoforge.event.tick.ServerTickEvent",
      "net.neoforged.neoforge.common.NeoForge",
    ],
    body: `
var a = Args.of(ctx);
if (a.string("action").equals("stop")) {
    NeoForge.EVENT_BUS.unregister(observer);
    var result = new ArrayList<>(rows);
    rows.clear();
    return result;
}
rows.clear();
var level = ctx.server().overworld();
var colony = UUID.fromString(a.string("colony"));
var ids = a.list("uuids").stream().map(x -> UUID.fromString(x.toString())).toList();
var site = Labor.class.getDeclaredMethod("site", UUID.class, net.minecraft.resources.ResourceKey.class);
site.setAccessible(true);
long[] lastTick = {Long.MIN_VALUE};
observer = event -> {
    if (event.getServer() != ctx.server() || lastTick[0] == level.getGameTime()) {
        return;
    }
    lastTick[0] = level.getGameTime();
    try {
        var labor = ((Optional<?>) site.invoke(null, colony, level.dimension())).orElse(null);
        var runners = labor == null ? Map.of() : (Map<?, ?>) field(field(labor, "runners"), "byResident");
        for (var id : ids) {
            if (!(level.getEntity(id) instanceof ResidentEntity rider)) {
                continue;
            }
            var horse = ResidentMounts.horse(rider).orElse(null);
            if (horse == null) {
                continue;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("tick", level.getGameTime()); row.put("uuid", id.toString());
            row.put("x", rider.getX()); row.put("y", rider.getY()); row.put("z", rider.getZ());
            row.put("yaw", rider.getYRot()); row.put("horse", rider.getVehicle() == horse);
            row.put("horseX", horse.getX()); row.put("horseY", horse.getY()); row.put("horseZ", horse.getZ());
            row.put("horseYaw", horse.getYRot()); row.put("bodyYaw", horse.yBodyRot);
            var navigation = horse.getNavigation(); var path = navigation.getPath();
            row.put("navDone", navigation.isDone()); row.put("path", path == null ? 0 : System.identityHashCode(path));
            row.put("next", path == null ? -1 : path.getNextNodeIndex());
            row.put("target", path == null ? "-" : path.getTarget().toShortString());
            row.put("wantedX", horse.getMoveControl().getWantedX());
            row.put("wantedZ", horse.getMoveControl().getWantedZ()); row.put("speed", horse.getSpeed());
            row.put("goals", horse.goalSelector.getAvailableGoals().stream().filter(g -> g.isRunning())
                .map(g -> g.getGoal().getClass().getSimpleName()).sorted().toList().toString());
            var runner = runners.get(id);
            var trip = field(runner, "carrying");
            var node = (Node) field(runner, "current");
            row.put("runner", trip != null ? "travelling" : node != null ? "holding" : "idle");
            row.put("node", node == null ? "-" : node.getClass().getSimpleName() + ":" + node.id());
            Object onHorse = null;
            if (trip != null) {
                var stages = (List<?>) field(trip, "stages");
                int index = (int) field(trip, "at");
                if (index < stages.size()) {
                    onHorse = field(stages.get(index), "carrying");
                }
            }
            row.put("stage", onHorse == null ? "-" : String.valueOf(field(onHorse, "stage")));
            row.put("going", onHorse == null ? node == null ? "idle" : "Arrived (runner footing)"
                : String.valueOf(field(onHorse, "going")));
            double distance = -1;
            var footing = (Stances) field(runner, "footing");
            if (footing instanceof Stances.Cells cells) {
                distance = cells.cells().stream().map(p -> WorldSpaces.storage(level, p))
                    .flatMap(Optional::stream).mapToDouble(p -> p.getBottomCenter().distanceTo(horse.position()))
                    .min().orElse(-1);
            }
            row.put("distance", distance);
            rows.add(row);
        }
    } catch (ReflectiveOperationException error) {
        throw new IllegalStateException(error);
    }
};
NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, observer);
return List.of();`,
    members: `
private static final List<Object> rows = new ArrayList<>();
private static java.util.function.Consumer<ServerTickEvent.Post> observer;

private static Object field(Object value, String name) throws ReflectiveOperationException {
    if (value == null) {
        return null;
    }
    for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(value);
        } catch (NoSuchFieldException ignored) {
        }
    }
    return null;
}`,
  }),
});
