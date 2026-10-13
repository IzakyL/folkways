import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../colony/colony-tasks";

export async function sampleTrain(server: MinecraftServer, carriage: string) {
  return runColonyTask(server, TRAIN, { carriage });
}

// Read-only: where the Create train of a carriage is, how fast, and who sits in it.
const TRAIN = defineTask<{ carriage: string }, Record<string, any>>({
  name: "folkways.bench.train",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.trains.entity.CarriageContraptionEntity",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    ],
    body: `
var level = ctx.server().overworld();
var entity = (CarriageContraptionEntity) level.getEntity(UUID.fromString(Args.of(ctx).string("carriage")));
if (entity == null) {
  // Out in country no one has loaded, the carriage has no entity; Create still runs the train on its graph.
  var trains = new ArrayList<Object>();
  for (var t : com.simibubi.create.Create.RAILWAYS.trains.values()) {
    var at = t.carriages.get(0).getLeadingPoint().getPosition(t.graph);
    var here = t.getCurrentStation();
    trains.add(Map.of("name", t.name.getString(), "x", Math.round(at.x), "z", Math.round(at.z), "speed", t.speed, "derailed", t.derailed,
      "station", here == null ? "" : here.name, "schedule_entry", t.runtime.currentEntry));
  }
  return Map.of("ok", true, "loaded", false, "trains", trains);
}
var train = entity.getCarriage().train;
var station = train.getCurrentStation();
var result = new LinkedHashMap<String, Object>();
result.put("ok", true); result.put("loaded", true);
result.put("x", entity.getX()); result.put("z", entity.getZ());
result.put("speed", train.speed); result.put("derailed", train.derailed);
result.put("station", station == null ? "" : station.name);
result.put("schedule_entry", train.runtime.currentEntry);
result.put("paused", train.runtime.paused);
// Why a train with somewhere to go stands still: the signal it waits at, for how long, and who holds each block.
var nav = train.navigation;
result.put("destination", nav.destination == null ? "" : nav.destination.name);
result.put("distance", nav.distanceToDestination);
result.put("waiting_for_signal", String.valueOf(nav.waitingForSignal));
result.put("ticks_waiting_for_signal", nav.ticksWaitingForSignal);
result.put("train", train.id.toString());
var blocks = new ArrayList<Object>();
for (var group : com.simibubi.create.Create.RAILWAYS.signalEdgeGroups.values())
  blocks.add(Map.of("block", group.id.toString(), "fallback", group.fallbackGroup, "reserved", group.reserved != null,
    "trains", group.trains.stream().map(t -> t.id.toString()).toList()));
result.put("blocks", blocks);
result.put("seated_residents", entity.getPassengers().stream().filter(e -> Bodies.of(e).isPresent()).count());
return result;
`,
  }),
});
