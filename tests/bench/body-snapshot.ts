import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

// Read-only: where each asked-for resident of the colony is and what it is doing.
export const BODY_SNAPSHOT = defineTask<{ colony_id: string; uuids: string[] }, Record<string, any>>({
  name: "folkways.bench.body-snapshot",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
String colonyId = args.string("colony_id");
var colony = ColonyGround.of(server, UUID.fromString(colonyId))
    .orElseThrow(() -> Fail.notFound("no colony " + colonyId + " in the registry"));
var wanted = new HashSet<String>(args.strings("uuids"));
var workers = new LinkedHashMap<String, Object>();
for (var resident : colony.residents()) {
    if (!wanted.contains(resident.id().toString())) continue;
    var entity = server.overworld().getEntity(resident.id());
    if (entity == null || !entity.isAlive()) continue;
    var body = Bodies.of(entity).orElseThrow();
    if (!body.colonyId().filter(colony.id()::equals).isPresent()) continue;
    var entry = new LinkedHashMap<String, Object>();
    entry.put("uuid", resident.id().toString());
    entry.put("kind", body.kind().id().toString());
    entry.put("x", entity.getX()); entry.put("y", entity.getY()); entry.put("z", entity.getZ());
    var doing = body.doing();
    if (doing.isPresent()) entry.put("current", doing.get().what().toString());
    else entry.put("idle", true);
    workers.put(resident.id().toString(), entry);
}
return Map.of("ok", true, "workers", workers, "loaded_alive", workers.size(), "roster", colony.residents().size());
`,
  }),
});

export async function snapshotBodies(server: MinecraftServer, colony_id: string, uuids: string[]) {
  const workers: Record<string, any> = {};
  let roster = 0;
  for (let i = 0; i < uuids.length; i += 100) {
    const batch = await runColonyTask(server, BODY_SNAPSHOT, { colony_id, uuids: uuids.slice(i, i + 100) });
    if (Object.keys(batch.workers).length !== batch.loaded_alive) throw new Error("Body snapshot was truncated");
    Object.assign(workers, batch.workers);
    roster = batch.roster;
  }
  return { workers, loaded_alive: Object.keys(workers).length, roster };
}
