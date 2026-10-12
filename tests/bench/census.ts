import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

const KEY = "folkways.bench.census";

// Installs (once per game JVM, kept in a system property) a tick listener that counts the colony's labor once a
// second of game time (every 20 normally run ticks) and appends the count to a file as it is taken, one JSON line
// each, so it can be read while the run goes on. The count is LaborCensus, the labor's own read-only census: nodes
// submitted ever, the nodes on the plan by phase, those laid on routes, those left unassigned by reason, the goods
// found short, and every body by what it is up to. Nothing is summed over time or dropped. Nothing a client sees
// changes.
const INSTALL = defineTask<{ colony_id: string; path: string }, Record<string, any>>({
  name: "folkways.bench.census-install",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "java.nio.file.Files",
      "java.nio.file.Path",
      "java.nio.file.StandardOpenOption",
      "net.neoforged.bus.api.EventPriority",
      "net.neoforged.neoforge.common.NeoForge",
      "net.neoforged.neoforge.event.tick.ServerTickEvent",
      "io.github.izakyl.folkways.core.engine.labor.LaborCensus",
    ],
    body: `
var props = System.getProperties();
if (props.get("${KEY}") != null) return Map.of("ok", true, "installed", false);
var args = Args.of(ctx);
var colony = UUID.fromString(args.string("colony_id"));
var path = Path.of(args.string("path"));
Files.createDirectories(path.getParent());
var out = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
var taken = new long[1];
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, event -> {
  var server = event.getServer();
  if (!server.tickRateManager().runsNormally()) return;
  var level = server.overworld();
  long now = level.getGameTime();
  if (now % 20 != 0) return;
  var census = LaborCensus.of(level, colony);
  if (census.isEmpty()) return;
  var c = census.get();
  var line = new StringBuilder("{\\"game_time\\":").append(now)
    .append(",\\"epoch_ms\\":").append(System.currentTimeMillis())
    .append(",\\"day_time\\":").append(level.getDayTime())
    .append(",\\"submitted\\":").append(c.submitted())
    .append(",\\"planned\\":").append(c.planned())
    .append(",\\"phases\\":").append(json(c.phases()))
    .append(",\\"unassigned\\":").append(json(c.unassigned()))
    .append(",\\"missing\\":").append(json(c.missing()))
    .append(",\\"standing\\":").append(json(c.standing()))
    .append("}\\n");
  try {
    out.write(line.toString());
    out.flush();
    taken[0]++;
  } catch (java.io.IOException failed) {
    throw new java.io.UncheckedIOException(failed);
  }
});
props.put("${KEY}", taken);
return Map.of("ok", true, "installed", true, "path", path.toString());
`,
    members: `
static String json(Map<?, ?> map) {
  var out = new StringBuilder("{");
  for (var entry : map.entrySet()) {
    if (out.length() > 1) out.append(',');
    out.append('"').append(String.valueOf(entry.getKey()).replace("\\"", "'")).append("\\":").append(entry.getValue());
  }
  return out.append('}').toString();
}
`,
  }),
});

const COUNT = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.census-count",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    body: `
if (!(System.getProperties().get("${KEY}") instanceof long[] taken)) return Map.of("ok", true, "lines", -1);
return Map.of("ok", true, "lines", taken[0]);
`,
  }),
});

export async function installCensus(server: MinecraftServer, colonyId: string, path: string) {
  const { ok, $task, ...out } = await runColonyTask(server, INSTALL, { colony_id: colonyId, path });
  return out;
}

export async function censusLines(server: MinecraftServer) {
  const { ok, $task, ...out } = await runColonyTask(server, COUNT, {});
  return out as { lines: number };
}

export const CENSUS_NOTE = "One JSON line per second of game time (every 20 normally run ticks) from launch to the end of the "
  + "run, appended as it was taken, so the file can be read live. game_time / day_time = overworld clocks; epoch_ms = "
  + "System.currentTimeMillis(), the clock the film's wall_start_ms is read on. submitted = nodes that ever came onto "
  + "the colony's plan; phases = nodes on the plan now by phase (PENDING, READY, WORKING, SETTLING, DONE, FAILED, "
  + "CANCELLED); planned = nodes laid on some body's route now; unassigned = nodes the last schedule left without a "
  + "body, by reason; missing = goods the last plan found short, by item; standing = every body of the colony by what "
  + "it is up to: idle, stalled, walking, working, holding, away, riding, urge/<node kind> (EatAction, SleepAction, "
  + "MendAction...). Completed work is in the work CSV (FINISHED rows), what it gave in the yield CSV.";
