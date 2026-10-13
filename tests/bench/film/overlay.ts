import { chromium } from "playwright";
import { execFileSync, spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

// Lays the run's own numbers over its film: the machine and the versions, the server's tick rate and tick time (red
// over the 50 ms a tick may take), what the colony is up to, the tasks submitted, planned and done, and what has been
// made, dug and placed, each with the latest of them shown as it comes. Every number is read from the run's reports
// at the moment the frame shows, so the film has to be one cut from that run:
//
//   npm run bench:overlay -- [--reports dir] [--film in.mp4] [--cuts cuts.json] [--out out.mp4] [--music file|none]
//
// Without --cuts the film is taken to be the one the run cut itself (the report's video.cut.cuts); a film cut again
// by hand names the cuts it kept. The overlay is drawn by overlay/hud.html, a frame at a time, in a headless browser.
// The film is given music, one of the game's own tracks unless --music names another file or none. With --still n
// only the film's frame n is drawn, to a picture beside the output, to see a change by.

const REPO = process.cwd();
const arg = (name: string, fallback: string) => {
  const at = process.argv.indexOf(`--${name}`);
  return at >= 0 && process.argv[at + 1] ? process.argv[at + 1] : fallback;
};
const REPORTS = arg("reports", "tests/out/bench/reports");
const FILM = arg("film", "tests/out/bench/film/large-colony-cut.mp4");
const OUT = arg("out", FILM.replace(/\.mp4$/, "-overlay.mp4"));
const CUTS = arg("cuts", "");
const MUSIC = arg("music", "minecraft/sounds/music/game/haggstrom.ogg");
const STILL = arg("still", "");

const FPS = 30;
// The numbers are read anew every so many frames, all of a kind together, so they can be followed: the counts six
// times a second, the tick rate and tick time three times, each the mean of the ticks since it was last read.
const COUNT_EVERY = 5, PERF_EVERY = 10;
// The graph holds a minute of ticks, a point for every half second of them: the same ticks make a point in every
// frame, so the line slides and does not change shape.
const SPARK_TICKS = 1200, SPARK_EVERY = 10;
// A row shows what is being made, dug or placed as a belt of pictures that moves as fast as its count goes up. One
// picture stands for as many of them as keeps the belt, at its busiest, under this many places a frame.
const ROW_SHOWN = 5, ROW_FASTEST = 0.34;
// The browsers that draw frames side by side.
const PAGES = 8;

/** Own business, not the colony's work: left out of the tasks done. */
const URGES = new Set(["SleepAction", "EatAction", "Stay", "MendAction"]);
const DIGS = new Set(["StripNode", "ClearNode", "FellNode"]);
const PLACES = new Set(["LayNode", "ScaffoldNode"]);
/** Hauls move goods and digging turns up rubble: neither makes anything. */
const MAKES_NOTHING = new Set(["TransferNode", "StripNode", "ClearNode"]);

function rowsOf(file: string): Record<string, string>[] {
  const [head, ...lines] = readFileSync(path.join(REPORTS, file), "utf8").split("\n").filter(Boolean);
  const keys = head.split(",");
  return lines.map((line) => Object.fromEntries(line.split(",").map((v, i) => [keys[i], v])));
}

/** The index of the last of `sorted` not above `x`, or -1. */
function last(sorted: number[], x: number) {
  let lo = 0, hi = sorted.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (sorted[mid] <= x) lo = mid + 1; else hi = mid; }
  return lo - 1;
}

type Event = { tick: number; count: number; item?: string };

/** Events in order of time, with the running sum of their counts. */
function tally(events: Event[]) {
  events.sort((a, b) => a.tick - b.tick);
  const ticks = events.map((e) => e.tick), sums: number[] = [];
  events.reduce((sum, e, i) => sums[i] = sum + e.count, 0);
  return { events, ticks, upTo: (tick: number) => { const i = last(ticks, tick); return i < 0 ? 0 : sums[i]; } };
}

// The rows show things as an inventory does: an item by its picture, a block as the block seen from above one corner.
// Both are read out of the game's own resources, by the item's model and the models it is made from. What the game
// draws some other way (a bed, a chest) and other mods' goods are not shown.
const RESOURCES = path.join(REPO, "build/moddev/artifacts", `neoforge-${property("neoforge_version")}-client-extra-aka-minecraft-resources.jar`);
type Face = { texture: string; tint?: string };
type Icon = { kind: "flat"; face: Face } | { kind: "cube" | "slab" | "stairs"; top: Face; left: Face; right: Face };

let listed: Set<string> | undefined;
const inJar = (file: string) => (listed ??= new Set(existsSync(RESOURCES)
  ? execFileSync("unzip", ["-Z1", RESOURCES], { encoding: "utf8", maxBuffer: 1 << 26 }).split("\n") : [])).has(file);
const plain = (id: string) => id.replace(/^minecraft:/, "");

const icons = new Map<string, Icon | undefined>();
function icon(item: string, dir: string): Icon | undefined {
  if (!icons.has(item)) icons.set(item, item.startsWith("minecraft:") ? drawn(plain(item), dir) : undefined);
  return icons.get(item);
}

function drawn(name: string, dir: string): Icon | undefined {
  const textures: Record<string, string> = {}, parents: string[] = [];
  for (let model = `item/${name}`, depth = 0; model && depth < 8; depth++) {
    const file = `assets/minecraft/models/${model}.json`;
    if (!inJar(file)) break;
    const json = JSON.parse(execFileSync("unzip", ["-p", RESOURCES, file], { encoding: "utf8" }));
    for (const [key, value] of Object.entries<string>(json.textures ?? {})) textures[key] ??= value;
    model = json.parent ? plain(json.parent) : "";
    parents.push(model);
  }
  // A texture is named outright or by another of the model's own.
  const named = (...keys: string[]): string | undefined => {
    for (const key of keys) {
      let value = textures[key];
      for (let hops = 0; value?.startsWith("#") && hops < 4; hops++) value = textures[value.slice(1)];
      if (value && !value.startsWith("#")) return plain(value);
    }
    return undefined;
  };
  const face = (texture: string | undefined, tint?: string): Face | undefined => {
    if (!texture) return undefined;
    const file = path.join(dir, `${texture.replace(/\//g, "_")}.png`), from = `assets/minecraft/textures/${texture}.png`;
    if (!existsSync(file)) {
      if (!inJar(from)) return undefined;
      writeFileSync(file, execFileSync("unzip", ["-p", RESOURCES, from], { maxBuffer: 1 << 24 }));
    }
    return { texture: "file://" + file, tint };
  };
  if (parents.includes("builtin/entity")) return undefined;
  if (parents.some((p) => /^item\/(generated|handheld|handheld_rod)$/.test(p))) {
    const flat = face(named("layer0"));
    return flat && { kind: "flat", face: flat };
  }
  if (!parents.some((p) => p.startsWith("block/"))) return undefined;
  // Leaves and the top of grass are grey in the game's files and coloured by the land they stand on: here by a meadow's.
  const leaves = /_leaves$/.test(name) ? (name.startsWith("spruce") ? "#619961" : name.startsWith("birch") ? "#80a755" : "#59ae30") : undefined;
  const top = face(named("top", "end", "up", "all", "texture", "wall", "side", "particle"), leaves ?? (name === "grass_block" ? "#7cbd6b" : undefined));
  const left = face(named("front", "side", "all", "north", "texture", "wall", "particle"), leaves);
  const right = face(named("side", "all", "east", "texture", "wall", "particle"), leaves);
  if (!top || !left || !right) return undefined;
  return { kind: parents.includes("block/slab") ? "slab" : parents.includes("block/stairs") ? "stairs" : "cube", top, left, right };
}

function property(name: string) {
  return readFileSync(path.join(REPO, "gradle.properties"), "utf8").match(new RegExp(`^${name}=(.*)$`, "m"))?.[1].trim() ?? "?";
}

/** The machine this runs on, which is the one the run was filmed on, and the versions the mod is built against. */
function system(): Array<[string, string]> {
  const cpu = os.cpus()[0]?.model.replace(/\s+\d+-Core Processor$/, "").replace(/\((R|TM)\)/g, "").trim() ?? "?";
  const release = existsSync("/etc/os-release") ? readFileSync("/etc/os-release", "utf8").match(/^PRETTY_NAME="?([^"\n]*)/m)?.[1] : undefined;
  return [["CPU", cpu], ["Memory", `${Math.round(os.totalmem() / 2 ** 30 / 8) * 8} GB`],
    ["OS", (release ?? `${os.type()} ${os.release()}`).replace(/ LTS$/, "").replace(/^(\S+ \d+\.\d+)\.\d+$/, "$1")],
    ["Minecraft", property("minecraft_version")], ["NeoForge", property("neoforge_version")], ["Java", property("java_release")]];
}

async function main() {
  const report = JSON.parse(readFileSync(path.join(REPORTS, "large-colony.json"), "utf8"));
  const speed: number = report.video.cut.speed;
  const cuts: Array<{ from_ms: number; length_s: number }> = CUTS ? JSON.parse(readFileSync(CUTS, "utf8")) : report.video.cut.cuts;

  const ticks = rowsOf("large-colony.ticks.csv").map((r) => ({ tick: Number(r.game_time), ms: Number(r.epoch_ms), wall: Number(r.wall_ns), took: Number(r.full_ns) / 1e6 }));
  const tickMs = ticks.map((t) => t.ms);
  const census = readFileSync(path.join(REPORTS, "large-colony.census.ndjson"), "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  const censusTicks: number[] = census.map((c) => c.game_time);

  const done: Event[] = [], dug: Event[] = [], placed: Event[] = [], made: Event[] = [];
  for (const r of rowsOf("large-colony.work.csv")) {
    if (r.step !== "FINISHED") continue;
    const kind = r.kind.split("/").pop()!, tick = Number(r.game_time), item = r.used?.split(";")[0].split("=")[0] || undefined;
    if (URGES.has(kind)) continue;
    done.push({ tick, count: 1 });
    if (DIGS.has(kind)) dug.push({ tick, count: 1, item });
    if (PLACES.has(kind)) placed.push({ tick, count: 1, item });
  }
  for (const r of rowsOf("large-colony.yield.csv")) {
    const kind = r.kind.split("/").pop()!;
    if (!r.made || URGES.has(kind) || MAKES_NOTHING.has(kind)) continue;
    for (const part of r.made.split(";")) {
      const [item, count] = part.split("=");
      made.push({ tick: Number(r.game_time), count: Number(count), item });
    }
  }
  const tallies = { done: tally(done), dug: tally(dug), placed: tally(placed), items: tally(made) };

  // The film's frames, each with the tick the run was at when it was taken. A cut runs `speed` times as fast as it
  // was filmed; the film's own length says how long its cuts came out, a frame or so off what they were asked to be.
  const frames = Number(execFileSync("ffprobe", ["-v", "error", "-select_streams", "v:0", "-count_packets", "-show_entries", "stream=nb_read_packets",
    "-of", "csv=p=0", FILM], { encoding: "utf8" }).trim());
  const asked = cuts.reduce((sum, c) => sum + c.length_s / speed, 0), stretch = frames / FPS / asked;
  const tickOf: number[] = [];
  let began = 0;
  for (const cut of cuts) {
    const length = cut.length_s / speed * stretch;
    for (let f = Math.round(began * FPS); f < Math.round((began + length) * FPS) && f < frames; f++) {
      const wall = cut.from_ms + (f / FPS - began) / stretch * speed * 1000;
      tickOf[f] = Math.max(0, last(tickMs, wall));
    }
    began += length;
  }
  for (let f = 0; f < frames; f++) tickOf[f] ??= tickOf[f - 1] ?? 0;

  const tmp = mkdtempSync(path.join(os.tmpdir(), "folkways-overlay-"));
  const textures = path.join(tmp, "tex"), shots = path.join(tmp, "frames");
  mkdirSync(textures); mkdirSync(shots);
  const tickAt = (frame: number) => ticks[tickOf[Math.max(0, Math.min(frames - 1, frame))]].tick;

  // Each row's belt, laid out for the whole film first: a picture for every so many of the count, that of whatever
  // made up most of them, in the order they came. How far the belt has run at a frame is the count there.
  const rows = (["items", "dug", "placed"] as const).map((name) => {
    const { events, upTo } = tallies[name];
    const steps = Array.from({ length: frames - 1 }, (_, f) => upTo(tickAt(f + 1)) - upTo(tickAt(f))).sort((x, y) => x - y);
    let each = 1;
    while ((steps[Math.floor(steps.length * 0.98)] ?? 0) / each > ROW_FASTEST) each *= 2;
    const belt: Array<Icon | undefined> = [];
    let share = new Map<string, number>(), filled = 0;
    for (const e of events) {
      for (let left = e.count; left > 0;) {
        const take = Math.min(left, each - filled);
        if (e.item) share.set(e.item, (share.get(e.item) ?? 0) + take);
        left -= take; filled += take;
        if (filled < each) continue;
        const most = [...share].sort((x, y) => y[1] - x[1]).map(([item]) => icon(item, textures)).find(Boolean);
        belt.push(most);
        share = new Map(); filled = 0;
      }
    }
    return (frame: number) => {
      const run = upTo(tickAt(frame)) / each, whole = Math.min(Math.floor(run), belt.length);
      return { shown: belt.slice(Math.max(0, whole - ROW_SHOWN), whole).reverse().map((i) => i ?? null), moved: run - Math.floor(run) };
    };
  });

  // The graph's points: the mean of every SPARK_EVERY ticks, counted from the run's first, whatever frame asks.
  const points: number[] = [];
  for (let k = 0; k + SPARK_EVERY <= ticks.length; k += SPARK_EVERY)
    points.push(Math.round(ticks.slice(k, k + SPARK_EVERY).reduce((sum, t) => sum + t.took, 0) / SPARK_EVERY * 10) / 10);

  const between = (key: "submitted" | "planned", tick: number) => {
    const i = Math.max(0, last(censusTicks, tick)), a = census[i], b = census[i + 1];
    return b ? a[key] + (b[key] - a[key]) * (tick - a.game_time) / (b.game_time - a.game_time) : a[key];
  };

  const state = (frame: number) => {
    const i = tickOf[frame];
    // The counts as they stood when last read, and the ticks run between the last two readings of the tick time.
    const counted = tickAt(frame - frame % COUNT_EVERY), read = frame - frame % PERF_EVERY;
    const to = tickOf[read], from = Math.max(0, Math.min(to - 1, tickOf[Math.max(0, read - PERF_EVERY)]));
    const span = ticks.slice(from + 1, to + 1);
    const spark: Array<[number, number]> = [];
    for (let p = Math.max(0, Math.ceil((i - SPARK_TICKS) / SPARK_EVERY)); (p + 1) * SPARK_EVERY - 1 <= i; p++)
      spark.push([i - ((p + 1) * SPARK_EVERY - 1), points[p]]);
    const c = census[Math.max(0, last(censusTicks, ticks[i].tick))];
    return {
      day_time: c.day_time + (ticks[i].tick - c.game_time), standing: c.standing,
      tps: span.length ? Math.min(20, span.length / ((ticks[to].wall - ticks[from].wall) / 1e9)) : 20,
      mspt: span.length ? span.reduce((sum, t) => sum + t.took, 0) / span.length : ticks[to].took,
      spark, spark_ticks: SPARK_TICKS,
      submitted: between("submitted", counted), planned: between("planned", counted),
      done: tallies.done.upTo(counted), items: tallies.items.upTo(counted), dug: tallies.dug.upTo(counted), placed: tallies.placed.upTo(counted),
      made: rows[0](frame), dugs: rows[1](frame), placeds: rows[2](frame),
    };
  };

  const browser = await chromium.launch();
  const fixed = { speed, system: system() };
  if (STILL) {
    const frame = Math.max(0, Math.min(frames - 1, Number(STILL))), over = path.join(tmp, "still.png"), still = OUT.replace(/\.mp4$/, `-${frame}.png`);
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    await page.goto("file://" + path.join(REPO, "tests/bench/film/hud.html"));
    await page.evaluate((f) => (globalThis as any).setup(f), fixed);
    await page.evaluate((s) => (globalThis as any).render(s), state(frame));
    await page.screenshot({ path: over, omitBackground: true });
    await browser.close();
    execFileSync("ffmpeg", ["-v", "error", "-y", "-ss", (frame / FPS).toFixed(3), "-i", FILM, "-i", over, "-filter_complex", "overlay", "-frames:v", "1", still]);
    rmSync(tmp, { recursive: true, force: true });
    console.log(still);
    return;
  }
  let drawnFrames = 0;
  await Promise.all(Array.from({ length: PAGES }, async (_, k) => {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    await page.goto("file://" + path.join(REPO, "tests/bench/film/hud.html"));
    await page.evaluate((f) => (globalThis as any).setup(f), fixed);
    for (let f = k; f < frames; f += PAGES) {
      await page.evaluate((s) => (globalThis as any).render(s), state(f));
      // The browser now and then fails to take a picture, with eight pages at it at once: it is asked again.
      for (let tries = 0; ; tries++) {
        try { await page.screenshot({ path: path.join(shots, `${String(f).padStart(6, "0")}.png`), omitBackground: true }); break; }
        catch (error) { if (tries >= 4) throw error; await new Promise((again) => setTimeout(again, 200)); }
      }
      if (++drawnFrames % 600 === 0) console.log(`${drawnFrames}/${frames} frames drawn`);
    }
    await page.close();
  }));
  await browser.close();

  const seconds = frames / FPS, music = musicFile();
  await new Promise<void>((resolve, reject) => {
    const p = spawn("ffmpeg", ["-v", "error", "-y", "-i", FILM, "-framerate", String(FPS), "-i", path.join(shots, "%06d.png"),
      ...(music ? ["-stream_loop", "-1", "-i", music] : []),
      "-filter_complex", "[0:v][1:v]overlay=shortest=1[v]" + (music ? `;[2:a]afade=t=in:d=1.5,afade=t=out:st=${(seconds - 4).toFixed(2)}:d=4[a]` : ""),
      "-map", "[v]", ...(music ? ["-map", "[a]", "-c:a", "aac", "-b:a", "192k"] : ["-an"]), "-t", seconds.toFixed(3),
      "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", OUT], { stdio: "inherit" });
    p.on("error", reject);
    p.on("close", (code) => code === 0 ? resolve() : reject(new Error(`ffmpeg left with ${code}`)));
  });
  rmSync(tmp, { recursive: true, force: true });
  console.log(`${frames} frames, ${seconds.toFixed(1)} s${music ? ", with music" : ""}: ${OUT}`);
}

// The music: a file, or one of the game's own tracks by its name in the game's asset index, which the build has
// already fetched. None when asked for none, or when the track is not to be found.
function musicFile(): string | undefined {
  if (MUSIC === "none") return undefined;
  if (existsSync(MUSIC)) return MUSIC;
  const assets = path.join(os.homedir(), ".gradle/caches/neoformruntime/assets"), indexes = path.join(assets, "indexes");
  if (!existsSync(indexes)) return undefined;
  for (const index of readdirSync(indexes)) {
    const hash: string | undefined = JSON.parse(readFileSync(path.join(indexes, index), "utf8")).objects?.[MUSIC]?.hash;
    const file = hash && path.join(assets, "objects", hash.slice(0, 2), hash);
    if (file && existsSync(file)) return file;
  }
  console.warn(`no music: ${MUSIC} is neither a file nor a track among the game's assets`);
  return undefined;
}

main().catch((error) => { console.error(error); process.exit(1); });
