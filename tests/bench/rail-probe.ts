import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

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
if (entity == null) return Map.of("ok", true, "loaded", false);
var train = entity.getCarriage().train;
var station = train.getCurrentStation();
var result = new LinkedHashMap<String, Object>();
result.put("ok", true); result.put("loaded", true);
result.put("x", entity.getX()); result.put("z", entity.getZ());
result.put("speed", train.speed); result.put("derailed", train.derailed);
result.put("station", station == null ? "" : station.name);
result.put("schedule_entry", train.runtime.currentEntry);
result.put("paused", train.runtime.paused);
result.put("seated_residents", entity.getPassengers().stream().filter(e -> Bodies.of(e).isPresent()).count());
return result;
`,
  }),
});
