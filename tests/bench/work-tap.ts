import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

const KEY = "folkways.bench.workTap";

// Installs (once per game JVM, kept in a system property) listeners on the colony's own work reports and keeps every
// one of them, from the moment they are installed until the game stops, as the row it was reported:
//   work      WorkProgress: a node taken up, finished, released or failed, by whom, where, doing what, with what goods
//   exertion  WorkExertion: a body's active ticks, labor seconds and blocks walked, reported when work or a walk ends
// Nothing is summed, labelled or dropped, and nothing is read from the world. Nothing a client sees changes.
const INSTALL = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.work-tap-install",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.neoforged.bus.api.EventPriority",
      "net.neoforged.neoforge.common.NeoForge",
      "net.neoforged.neoforge.server.ServerLifecycleHooks",
      "io.github.izakyl.folkways.core.api.work.Doing",
      "io.github.izakyl.folkways.core.api.work.WorkExertion",
      "io.github.izakyl.folkways.core.api.work.WorkProgress",
      "io.github.izakyl.folkways.core.api.work.WorkYield",
    ],
    body: `
var props = System.getProperties();
if (props.get("${KEY}") instanceof Map<?,?>) return Map.of("ok", true, "installed", false);
var work = Collections.synchronizedList(new ArrayList<String>());
var exertion = Collections.synchronizedList(new ArrayList<String>());
var tap = new ConcurrentHashMap<String, Object>();
var yields = Collections.synchronizedList(new ArrayList<String>());
tap.put("work", work); tap.put("exertion", exertion); tap.put("yield", yields);
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, WorkProgress.class, event -> {
  long now = ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
  var node = event.node();
  var spec = node.spec();
  BlockPos site = spec.site().cell();
  String focus = spec.focus().map(p -> p.getX() + "," + p.getY() + "," + p.getZ()).orElse(",,");
  work.add(now + "," + event.step() + "," + node.id() + "," + spec.owner() + "/" + node.getClass().getSimpleName() + ","
    + event.worker().map(UUID::toString).orElse("") + "," + site.getX() + "," + site.getY() + "," + site.getZ() + "," + focus + ","
    + spec.doing() + "," + wares(node.wares().used()) + "," + wares(node.wares().made()));
});
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, WorkExertion.class, event -> {
  long now = ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
  exertion.add(now + "," + event.body().getUUID() + "," + event.workDone() + "," + event.activeTicks() + "," + event.distance());
});
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, WorkYield.class, event -> {
  long now = ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
  var node = event.node();
  var made = new StringBuilder();
  for (var stack : event.made()) {
    if (stack.isEmpty()) continue;
    if (made.length() > 0) made.append(';');
    made.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())).append('=').append(stack.getCount());
  }
  yields.add(now + "," + node.id() + "," + node.spec().owner() + "/" + node.getClass().getSimpleName() + ","
    + event.body().getUUID() + "," + made);
});
props.put("${KEY}", tap);
return Map.of("ok", true, "installed", true);
`,
    members: `
static String wares(List<Doing.Ware> list) {
  var out = new StringBuilder();
  for (var ware : list) {
    if (out.length() > 0) out.append(';');
    out.append(ware.item()).append('=').append(ware.count());
  }
  return out.toString();
}
`,
  }),
});

// Writes every report kept so far to two CSV files, as they were kept.
const DUMP = defineTask<{ work: string; exertion: string; yield: string }, Record<string, any>>({
  name: "folkways.bench.work-tap-dump",
  side: "server",
  timeoutMs: 120_000,
  source: javaTask({
    imports: ["java.nio.file.Files", "java.nio.file.Path"],
    body: `
if (!(System.getProperties().get("${KEY}") instanceof Map<?,?> tap)) throw Fail.unavailable("work tap not installed");
var args = Args.of(ctx);
int work = write(Path.of(args.string("work")),
  "game_time,step,node,kind,worker,site_x,site_y,site_z,focus_x,focus_y,focus_z,doing,used,made", (List<?>) tap.get("work"));
int exertion = write(Path.of(args.string("exertion")),
  "game_time,body,work_done,active_ticks,distance", (List<?>) tap.get("exertion"));
int yields = write(Path.of(args.string("yield")), "game_time,node,kind,body,made", (List<?>) tap.get("yield"));
return Map.of("ok", true, "work", work, "exertion", exertion, "yield", yields);
`,
    members: `
static int write(java.nio.file.Path path, String header, List<?> rows) throws java.io.IOException {
  java.nio.file.Files.createDirectories(path.getParent());
  int n = 0;
  try (var out = java.nio.file.Files.newBufferedWriter(path)) {
    out.write(header); out.write('\\n');
    synchronized (rows) {
      for (Object row : rows) { out.write((String) row); out.write('\\n'); n++; }
    }
  }
  return n;
}
`,
  }),
});

export async function installWorkTap(server: MinecraftServer) {
  return runColonyTask(server, INSTALL, {});
}

export async function dumpWorkTap(server: MinecraftServer, paths: { work: string; exertion: string; yield: string }) {
  const { ok, $task, ...out } = await runColonyTask(server, DUMP, paths);
  return out as { work: number; exertion: number; yield: number };
}

export const WORK_TAP_NOTE = "Every work report the colony made from launch to the end of the run, one row each, nothing summed or left out. "
  + "work rows are WorkProgress events: step is STARTED / FINISHED / RELEASED / FAILED, node the node's id (the same id across "
  + "its steps), kind owner/class, worker the reported worker (empty when the event names none), site and focus the cells the "
  + "node works at (focus empty when it has none), used / made the goods the node names as item=count;... "
  + "exertion rows are WorkExertion events: a body's labor seconds, active ticks and blocks walked, reported when its work or walk ends. "
  + "yield rows are WorkYield events: what a finished node actually gave (crops harvested, logs felled, blocks dug, items "
  + "crafted), as item=count;... - empty when it gave nothing, as laying a block or tilling does.";
