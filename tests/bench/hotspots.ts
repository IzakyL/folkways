import { closeSync, openSync, readSync } from "node:fs";

// Where the colony is busy right now, for the roaming cameras: read from the film feed (director.ts), the rows a
// listener in the game appends to a file as work starts and ends, as the trains run, and of who stands with nothing
// to do.

type Vec = { x: number; y: number; z: number };
type Box = { minX: number; maxX: number; minZ: number; maxZ: number };

/** A place a roaming camera can film: where it stands, what it looks at, and the ground whose work counts for it. */
export type Spot = { name: string; eye: Vec; target: Vec; box: Box; underground?: boolean; station?: string };

type Work = { at: number; kind: string; x: number; y: number; z: number };
type Train = { here: string; to: string; distance: number; speed: number; riders: number; from: string; left: number };

/** What the feed has told so far: the game time, the work in hand, the work lately begun, the trains, and the idle. */
export type Scene = { now: number; active: Map<string, Work>; recent: Work[]; trains: Map<string, Train>; idle: Vec[] };

// Work begun in the last ten seconds still counts for half: a row of crops is many short nodes, one after another.
const RECENT_TICKS = 200;
// A node never reported ended is forgotten after two minutes.
const STALE_TICKS = 2400;
// Work round a spot counts within this many blocks of its ground, and this far above or below what it looks at.
const MARGIN = 6;
const RISE = 20;
// How much less a spot counts just after it, or one beside it, was shown, and how long (wall seconds) that takes to
// wear off. Spots whose views meet within NEAR blocks show much the same ground.
const SHOWN = 0.9;
const SHOWN_S = 180;
const NEAR = 40;

// Taking a seat is the boarding the film wants; driving is not seen at all.
const weigh = (kind: string) => kind === "ConductNode" ? 0 : kind === "TakeSeatNode" ? 2 : 1;
// A haul stays in hand all the way from one chest to the other, its hauler seldom at the place it names: it counts
// only as it begins.
const lingers = (kind: string) => kind !== "TransferNode";

const beside = (a: Spot, b: Spot) => Math.hypot(a.target.x - b.target.x, a.target.z - b.target.z) <= NEAR;

const covers = (spot: Spot, w: Vec) => w.x >= spot.box.minX - MARGIN && w.x <= spot.box.maxX + MARGIN
  && w.z >= spot.box.minZ - MARGIN && w.z <= spot.box.maxZ + MARGIN && Math.abs(w.y - spot.target.y) <= RISE;

const same = (a: string, b: string) => a !== "" && a.toLowerCase() === b.toLowerCase();

export function emptyScene(): Scene {
  return { now: 0, active: new Map(), recent: [], trains: new Map(), idle: [] };
}

/**
 * Takes the feed's rows into the scene. A row is `time,H` (the clock, and the start of that second's count of the
 * idle), `time,W,step,node,kind,x,y,z` (work), `time,T,train,station,destination,distance,speed,riders` (a train:
 * where it stands, where it heads, how far and how fast, and how many of the colony sit in it) or `time,I,x,y,z` (one
 * of the colony with nothing in hand, and where).
 */
export function take(scene: Scene, rows: string[]) {
  for (const row of rows) {
    const f = row.split(",");
    const at = Number(f[0]);
    if (!Number.isFinite(at)) continue;
    scene.now = Math.max(scene.now, at);
    if (f[1] === "H") scene.idle = [];
    else if (f[1] === "I") scene.idle.push({ x: Number(f[2]), y: Number(f[3]), z: Number(f[4]) });
    else if (f[1] === "W") {
      if (f[2] !== "STARTED") { scene.active.delete(f[3]); continue; }
      const work = { at, kind: f[4], x: Number(f[5]), y: Number(f[6]), z: Number(f[7]) };
      scene.active.set(f[3], work);
      scene.recent.push(work);
    } else if (f[1] === "T") {
      const was = scene.trains.get(f[2]) ?? { here: "", to: "", distance: 0, speed: 0, riders: 0, from: "", left: -Infinity };
      const next = { ...was, here: f[3], to: f[4], distance: Number(f[5]), speed: Number(f[6]), riders: Number(f[7] ?? 0) };
      if (was.here !== "" && next.here === "") { next.from = was.here; next.left = at; }
      scene.trains.set(f[2], next);
    }
  }
  scene.recent = scene.recent.filter((w) => scene.now - w.at <= RECENT_TICKS);
  for (const [id, w] of scene.active) if (scene.now - w.at > STALE_TICKS) scene.active.delete(id);
}

/** How busy a spot is, the middle of what is busy there, and how many stand idle on it. */
export function busy(spot: Spot, scene: Scene) {
  let score = 0, mass = 0, x = 0, y = 0, z = 0;
  const count = (w: Work, share: number) => {
    const weight = weigh(w.kind) * share;
    if (!weight || !covers(spot, w)) return;
    score += weight; mass += weight; x += w.x * weight; y += w.y * weight; z += w.z * weight;
  };
  for (const w of scene.active.values()) if (lingers(w.kind)) count(w, 1);
  for (const w of scene.recent) count(w, 0.5);
  const idle = scene.idle.filter((at) => covers(spot, at)).length;
  return { score, idle, centre: mass > 0 ? { x: x / mass, y: y / mass, z: z / mass } : undefined };
}

/**
 * The trains on their way to a station some spot looks at, each with that spot, how far off it still is and how many
 * of the colony it carries, its driver among them. The nearest first.
 */
export function calls(spots: Spot[], scene: Scene) {
  const due: Array<{ spot: Spot; train: string; distance: number; riders: number }> = [];
  for (const [train, t] of scene.trains) {
    if (t.here !== "") continue;
    const spot = spots.find((s) => s.station !== undefined && same(t.to, s.station));
    if (spot) due.push({ spot, train, distance: t.distance, riders: t.riders });
  }
  return due.sort((a, b) => a.distance - b.distance);
}

/** How a train's call at a station stands: still coming and how far off, standing there, or gone and how long since. */
export function call(scene: Scene, train: string, station: string):
  { state: "coming"; distance: number } | { state: "standing" } | { state: "gone"; ticks: number } {
  const t = scene.trains.get(train);
  if (t && same(t.here, station)) return { state: "standing" };
  if (t && t.here === "" && same(t.to, station)) return { state: "coming", distance: t.distance };
  return { state: "gone", ticks: t && same(t.from, station) ? scene.now - t.left : Infinity };
}

/**
 * The busiest spot not in `taken`, counting a spot shown lately for less so the film moves about, and one where many
 * stand idle for less too; nothing when no spot reaches `least`. `shown` is when each spot last went off the air, on
 * the wall clock `wall` is read on.
 */
export function pick(spots: Spot[], scene: Scene, options: { taken: Set<string>; shown: Map<string, number>; wall: number; least: number }) {
  let best: { spot: Spot; score: number; worth: number; centre?: Vec } | undefined;
  const onAir = spots.filter((s) => options.taken.has(s.name));
  for (const spot of spots) {
    if (onAir.some((s) => beside(s, spot))) continue;
    const { score, idle, centre } = busy(spot, scene);
    // A crowd standing about is no picture of a colony at work: a spot counts for the share of those on it that are
    // at work, and not at all with more idle on it than work.
    if (idle > score) continue;
    const last = Math.max(...spots.filter((s) => options.shown.has(s.name) && beside(s, spot)).map((s) => options.shown.get(s.name)!));
    const since = (options.wall - last) / 1000;
    // A crowd ten times the size is not ten times the picture: spots are ranked by the root of how busy they are.
    const worth = Math.sqrt(score) * (1 - SHOWN * Math.exp(-since / SHOWN_S)) * score / (score + idle);
    if (score >= options.least && worth > (best?.worth ?? 0)) best = { spot, score, worth, centre };
  }
  return best;
}

/** Reads the rows a file has gained since the last read; a file not there yet has none. */
export class Tail {
  private fd: number | undefined;
  private at = 0;
  private rest = "";
  private readonly chunk = Buffer.alloc(1 << 16);
  private readonly path: string;

  constructor(path: string) { this.path = path; }

  rows(): string[] {
    if (this.fd === undefined) {
      try { this.fd = openSync(this.path, "r"); } catch { return []; }
    }
    let text = this.rest;
    for (;;) {
      const n = readSync(this.fd, this.chunk, 0, this.chunk.length, this.at);
      if (n <= 0) break;
      this.at += n;
      text += this.chunk.toString("utf8", 0, n);
    }
    const lines = text.split("\n");
    this.rest = lines.pop() ?? "";
    return lines;
  }

  close() {
    if (this.fd !== undefined) closeSync(this.fd);
    this.fd = undefined;
  }
}
