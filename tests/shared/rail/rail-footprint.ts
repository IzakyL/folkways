export type Vec = { x: number; y: number; z: number };
export type Box = { min: Vec; max: Vec };
export type LoopBounds = { minX: number; maxX: number; minZ: number; maxZ: number };
/**
 * A station: `position` is the station block. Along a z-running straight (the default) `reverse` means the train
 * runs south through it, else north; along an x-running one (`axis: "x"`) it means the train runs west, else east.
 * `side` is which side of the track the station and its platform stand: +1 is east (or south), -1 west (or north).
 */
export type Stop = { name: string; position: Vec; reverse: boolean; axis?: "x" | "z"; side?: 1 | -1 };

/** The way a train runs through the stop along its axis: -1 toward smaller coordinates, +1 toward larger. */
export function headingOf(stop: Stop): 1 | -1 {
  return (stop.axis ?? "z") === "z" ? (stop.reverse ? 1 : -1) : (stop.reverse ? -1 : 1);
}

/**
 * A car standing behind a stop, read in its own terms: `u` across the track (toward +x on a z-running straight, +z on
 * an x-running one), `v` back from the station along the way the train comes in, `h` up from the track. A car's cells
 * run from v = 1 (its nose, beside the station) to v = carEndOf(seats) - 2.
 */
export type CarFrame = {
  at(u: number, v: number, h: number): Vec;
  axis: "x" | "z";
  /** The side the station and its platform stand on, in u. */
  side: 1 | -1;
  /** Block-state facings: back down the car, ahead toward the station, and outward on either side (u < 0, u > 0). */
  back: string;
  ahead: string;
  outward: (u: number) => string;
};

export function carFrame(stop: Stop): CarFrame {
  const axis = stop.axis ?? "z", side = stop.side ?? 1, back = -headingOf(stop) as 1 | -1;
  const track = trackOf(stop), along = axis === "z" ? stop.position.z : stop.position.x;
  const at = (u: number, v: number, h: number): Vec => axis === "z"
    ? { x: track.x + u, y: track.y + h, z: along + v * back }
    : { x: along + v * back, y: track.y + h, z: track.z + u };
  const named = (sign: number) => axis === "z" ? (sign > 0 ? "south" : "north") : (sign > 0 ? "east" : "west");
  const across = (sign: number) => axis === "z" ? (sign > 0 ? "east" : "west") : (sign > 0 ? "south" : "north");
  return { at, axis, side, back: named(back), ahead: named(-back), outward: (u) => across(u) };
}

/** The track cell the station stands beside. */
export function trackOf(stop: Stop): Vec {
  const side = stop.side ?? 1, p = stop.position;
  return (stop.axis ?? "z") === "z" ? { ...p, x: p.x - side } : { ...p, z: p.z - side };
}

export type SiteInput = {
  origin: Vec;
  lineLength: number;
  passengerCount: number;
  stations: Stop[];
  carTop: number;
  loop?: LoopBounds;
  /** The track is already down: the site is the car's straight, the stations and their platforms. */
  laid?: boolean;
  decoration?: Box;
  /** The stations stand already (another train of the line put them up): they are no part of what is cleared. */
  standing?: boolean;
};

export type Site = {
  car: Box;
  track: Box[];
  envelope: Box[];
  bed: Box[];
  platforms: Box[];
  curves: Box[];
  decoration?: Box;
  structures: Box[];
  footprint: Box[];
};

export const LOOP_RADIUS = 16;
const HALF_WIDTH = 2;

export const box = (a: Vec, b: Vec): Box => ({
  min: { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), z: Math.min(a.z, b.z) },
  max: { x: Math.max(a.x, b.x), y: Math.max(a.y, b.y), z: Math.max(a.z, b.z) },
});

export function inside(b: Box, p: Vec): boolean {
  return p.x >= b.min.x && p.x <= b.max.x && p.y >= b.min.y && p.y <= b.max.y && p.z >= b.min.z && p.z <= b.max.z;
}

export function carEndOf(passengerCount: number): number {
  return passengerCount > 1 ? 8 + Math.ceil(passengerCount / 2) : 8;
}

export function platformOf(stop: Stop, stations: Stop[], passengerCount: number, gy: number): Box {
  const station = stop.position;
  const carEnd = carEndOf(passengerCount);
  if (stop.axis === "x" || stop.side === -1) {
    // The car stands behind the station, against the way it runs; the platform runs two cells out beyond it.
    const heading = headingOf(stop), side = stop.side ?? 1;
    const ahead = 2, behind = carEnd + 2;
    const along = (stop.axis ?? "z") === "z" ? station.z : station.x;
    const [a0, a1] = heading < 0 ? [along - ahead, along + behind] : [along - behind, along + ahead];
    const [o0, o1] = [side * 2, side * 3];
    return (stop.axis ?? "z") === "z"
      ? box({ x: station.x + o0, y: gy, z: a0 }, { x: station.x + o1, y: gy + 1, z: a1 })
      : box({ x: a0, y: gy, z: station.z + o0 }, { x: a1, y: gy + 1, z: station.z + o1 });
  }
  const first = stations[0] === stop;
  const second = stations[1] === stop;
  const from = station.z + (stop.reverse ? -carEnd - 2 : (passengerCount === 1 && second ? -2 : -1));
  const to = station.z + (stop.reverse ? 2 : (passengerCount === 1 && first ? 7 : carEnd + 2));
  return box({ x: station.x + 2, y: gy, z: from }, { x: station.x + 3, y: gy + 1, z: to });
}

/** A platform's two strips: the kerb beside the track, a step up, and the walk behind it. */
export function platformStrips(stop: Stop, platform: Box): { kerb: Box; walk: Box } {
  const side = stop.side ?? 1, z = (stop.axis ?? "z") === "z";
  const near = z ? (side > 0 ? platform.min.x : platform.max.x) : (side > 0 ? platform.min.z : platform.max.z);
  const far = z ? (side > 0 ? platform.max.x : platform.min.x) : (side > 0 ? platform.max.z : platform.min.z);
  const strip = (at: number, top: number): Box => z
    ? box({ x: at, y: platform.min.y, z: platform.min.z }, { x: at, y: top, z: platform.max.z })
    : box({ x: platform.min.x, y: platform.min.y, z: at }, { x: platform.max.x, y: top, z: at });
  return { kerb: strip(near, platform.min.y + 1), walk: strip(far, platform.min.y) };
}

// A line runs a little past a station that faces out beyond the car, so the train has track to stop on there.
export function trackEnd(origin: Vec, carEnd: number, stations: Stop[]): number {
  return Math.max(origin.z + carEnd, ...stations.filter((stop) => stop.reverse).map((stop) => stop.position.z + 2));
}

function tracks(origin: Vec, lineLength: number, carEnd: number, stations: Stop[], loop?: LoopBounds): Box[] {
  const { x, y, z } = origin;
  if (!loop) return [box({ x, y, z: z - lineLength - 8 }, { x, y, z: trackEnd(origin, carEnd, stations) })];
  const { minX: l, maxX: r, minZ: t, maxZ: b } = loop;
  return [
    box({ x: r, y, z: t + LOOP_RADIUS }, { x: r, y, z: b - LOOP_RADIUS }),
    box({ x: l, y, z: t + LOOP_RADIUS }, { x: l, y, z: b - LOOP_RADIUS }),
    box({ x: l + LOOP_RADIUS, y, z: t }, { x: r - LOOP_RADIUS, y, z: t }),
    box({ x: l + LOOP_RADIUS, y, z: b }, { x: r - LOOP_RADIUS, y, z: b }),
  ];
}

function curves(loop: LoopBounds, y: number, top: number): Box[] {
  const { minX: l, maxX: r, minZ: t, maxZ: b } = loop;
  const centres = [
    { x: r - LOOP_RADIUS, z: t + LOOP_RADIUS, from: -90 },
    { x: l + LOOP_RADIUS, z: t + LOOP_RADIUS, from: 180 },
    { x: l + LOOP_RADIUS, z: b - LOOP_RADIUS, from: 90 },
    { x: r - LOOP_RADIUS, z: b - LOOP_RADIUS, from: 0 },
  ];
  const out: Box[] = [];
  for (const c of centres) {
    for (let step = 0; step <= 18; step++) {
      const angle = ((c.from + step * 5) * Math.PI) / 180;
      const px = Math.round(c.x + LOOP_RADIUS * Math.cos(angle));
      const pz = Math.round(c.z + LOOP_RADIUS * Math.sin(angle));
      out.push(box({ x: px - HALF_WIDTH, y, z: pz - HALF_WIDTH }, { x: px + HALF_WIDTH, y: top, z: pz + HALF_WIDTH }));
    }
  }
  return out;
}

export function railSite(input: SiteInput): Site {
  const { origin, lineLength, passengerCount, stations, carTop, loop, laid, decoration, standing } = input;
  const { x, y: gy, z: z0 } = origin;
  const carEnd = carEndOf(passengerCount);
  // A laid line's car stands behind its first stop, whichever way that stop faces.
  const f = laid ? carFrame(stations[0]) : undefined;
  const car = f ? box(f.at(-HALF_WIDTH, 1, 0), f.at(HALF_WIDTH, carEnd - 2, carTop - f.at(0, 0, 0).y))
    : box({ x: x - HALF_WIDTH, y: gy, z: z0 + 3 }, { x: x + HALF_WIDTH, y: carTop, z: z0 + carEnd });
  // Where the stations already stand, the corridor starts at the car's nose, behind the station, not before it.
  const track = f ? [box(f.at(0, standing ? 1 : -10, 0), f.at(0, carEnd, 0))] : tracks(origin, lineLength, carEnd, stations, loop);
  const along = (t: Box): Box => t.min.x === t.max.x
    ? box({ x: t.min.x - HALF_WIDTH, y: gy, z: t.min.z }, { x: t.max.x + HALF_WIDTH, y: carTop, z: t.max.z })
    : box({ x: t.min.x, y: gy, z: t.min.z - HALF_WIDTH }, { x: t.max.x, y: carTop, z: t.max.z + HALF_WIDTH });
  const envelope = track.map(along);
  const stationCells = standing ? [] : stations.map(({ position: p }) => box(p, p));
  const bed = [...track, ...stationCells].map((b) => box({ ...b.min, y: b.min.y - 1 }, { ...b.max, y: b.min.y - 1 }));
  const platforms = stations.map((stop) => platformOf(stop, stations, passengerCount, stop.position.y));
  const bends = loop ? curves(loop, gy, carTop) : [];
  // Stations already standing keep their platforms: only the car's own room is cleared.
  const structures = [car, ...(standing ? [] : platforms), ...stationCells, ...(decoration ? [decoration] : [])];
  const footprint = [...structures, ...envelope, ...bed, ...bends];
  return { car, track, envelope, bed, platforms, curves: bends, decoration, structures, footprint };
}

export function bounds(boxes: Box[]): Box {
  return boxes.reduce((acc, b) => box(
    { x: Math.min(acc.min.x, b.min.x), y: Math.min(acc.min.y, b.min.y), z: Math.min(acc.min.z, b.min.z) },
    { x: Math.max(acc.max.x, b.max.x), y: Math.max(acc.max.y, b.max.y), z: Math.max(acc.max.z, b.max.z) },
  ));
}

export function offsetBox(origin: Vec, relative: Box): Box {
  const at = (v: Vec): Vec => ({ x: origin.x + v.x, y: origin.y + v.y, z: origin.z + v.z });
  return box(at(relative.min), at(relative.max));
}

export type Straight = { axis: "x" | "z"; at: number; from: number; to: number };

/** A loop's four straights: z-running ones at x = minX / maxX, x-running ones at z = minZ / maxZ. */
export function straightsOf(bounds: LoopBounds, radius = 16): Straight[] {
  const { minX: l, maxX: r, minZ: t, maxZ: b } = bounds;
  return [
    { axis: "z", at: r, from: t + radius, to: b - radius }, { axis: "z", at: l, from: t + radius, to: b - radius },
    { axis: "x", at: t, from: l + radius, to: r - radius }, { axis: "x", at: b, from: l + radius, to: r - radius },
  ];
}

/** Where a new loop's straights cross earlier loops' straights at a right angle. */
export function crossingsOf(loop: LoopBounds, earlier: LoopBounds[]): Array<{ x: number; z: number }> {
  const out: Array<{ x: number; z: number }> = [];
  for (const old of earlier) for (const a of straightsOf(loop)) for (const b of straightsOf(old)) {
    if (a.axis === b.axis) continue;
    const [zRun, xRun] = a.axis === "z" ? [a, b] : [b, a];
    if (zRun.at > xRun.from && zRun.at < xRun.to && xRun.at > zRun.from && xRun.at < zRun.to) out.push({ x: zRun.at, z: xRun.at });
  }
  return out;
}
