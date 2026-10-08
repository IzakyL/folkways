import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

const KEY = "folkways.bench.tickMeter";
const RING = 1 << 16;

// Installs (once per game JVM, kept in a system property) tick listeners that time every normally run server
// tick into two rings. Nothing a client sees changes, so it does not stamp.
const INSTALL = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.tick-meter-install",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "java.util.concurrent.atomic.AtomicLong",
      "net.neoforged.bus.api.EventPriority",
      "net.neoforged.neoforge.common.NeoForge",
      "net.neoforged.neoforge.event.tick.ServerTickEvent",
    ],
    body: `
var props = System.getProperties();
if (props.get("${KEY}") instanceof Map<?,?> meter) return Map.of("ok", true, "installed", false, "count", ((AtomicLong) meter.get("count")).get());
var full = new long[${RING}];
var post = new long[${RING}];
var count = new AtomicLong();
var began = new long[2];
var meter = new ConcurrentHashMap<String,Object>();
meter.put("full", full); meter.put("post", post); meter.put("count", count);
NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, event -> began[0] = System.nanoTime());
NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Post.class, event -> began[1] = System.nanoTime());
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, event -> {
  if (began[0] == 0L || !event.getServer().tickRateManager().runsNormally()) return;
  long now = System.nanoTime();
  int at = (int) (count.get() % ${RING});
  full[at] = now - began[0];
  post[at] = now - began[1];
  count.incrementAndGet();
});
props.put("${KEY}", meter);
return Map.of("ok", true, "installed", true, "count", 0L);
`,
  }),
});

// Read-only: tick-time stats of the ticks counted since \`from\`.
const READ = defineTask<{ from: number }, Record<string, any>>({
  name: "folkways.bench.tick-meter-read",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["java.util.concurrent.atomic.AtomicLong"],
    body: `
if (!(System.getProperties().get("${KEY}") instanceof Map<?,?> meter)) throw Fail.unavailable("tick meter not installed");
long to = ((AtomicLong) meter.get("count")).get();
long asked = Args.of(ctx).integer("from");
long from = Math.min(to, Math.max(asked, to - ${RING}));
var out = new LinkedHashMap<String,Object>();
out.put("ok", true); out.put("from", from); out.put("to", to); out.put("ticks", to - from); out.put("lost", Math.max(0L, from - asked));
out.put("full", stats((long[]) meter.get("full"), from, to));
out.put("post", stats((long[]) meter.get("post"), from, to));
return out;
`,
    members: `
static Map<String,Object> stats(long[] ring, long from, long to) {
  int n = (int) (to - from);
  double[] ms = new double[n];
  double sum = 0;
  for (int i = 0; i < n; i++) { ms[i] = ring[(int) ((from + i) % ${RING})] / 1e6; sum += ms[i]; }
  Arrays.sort(ms);
  var out = new LinkedHashMap<String,Object>();
  out.put("mean", n == 0 ? null : sum / n);
  out.put("p50", n == 0 ? null : ms[(n - 1) / 2]);
  out.put("p95", n == 0 ? null : ms[Math.min(n - 1, (int) Math.floor(n * 0.95))]);
  out.put("p99", n == 0 ? null : ms[Math.min(n - 1, (int) Math.floor(n * 0.99))]);
  out.put("max", n == 0 ? null : ms[n - 1]);
  int over = 0;
  for (double v : ms) if (v > 50.0) over++;
  out.put("over_50", over);
  return out;
}
`,
  }),
});

export type TickWindow = {
  ticks: number;
  full: TickStats;
  post: TickStats;
  from: number;
  to: number;
  lost: number;
};
export type TickStats = { mean: number | null; p50: number | null; p95: number | null; p99: number | null; max: number | null; over_50: number };

export async function installTickMeter(server: MinecraftServer): Promise<number> {
  const result = await runColonyTask(server, INSTALL, {});
  return Number(result.count ?? 0);
}

export async function tickCursor(server: MinecraftServer): Promise<number> {
  return (await readTicks(server, Number.MAX_SAFE_INTEGER)).to;
}

export async function readTicks(server: MinecraftServer, from: number): Promise<TickWindow> {
  const { ok, $task, ...window } = await runColonyTask(server, READ, { from: Math.min(from, 2 ** 52) });
  return window as TickWindow;
}

export const TICK_METER_NOTE = "full = ServerTickEvent.Pre (HIGHEST) to the end of ServerTickEvent.Post (LOWEST): the whole server tick including "
  + "the NeoForge Post phase where Folkways labor runs; post = Post phase alone (Folkways plus any other mod's Post work). "
  + "Only ticks where the tick rate manager runs normally are counted. Budget is 50 ms; over_50 counts ticks that overran it.";
