export type Vec = { x: number; y: number; z: number };
export type Box = { min: Vec; max: Vec };
export type LoopBounds = { minX: number; maxX: number; minZ: number; maxZ: number };
export type Stop = { name: string; position: Vec; reverse: boolean };

export type SiteInput = {
  origin: Vec;
  lineLength: number;
  passengerCount: number;
  stations: Stop[];
  carTop: number;
  loop?: LoopBounds;
  decoration?: Box;
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
  const first = stations[0] === stop;
  const second = stations[1] === stop;
  const from = station.z + (stop.reverse ? -carEnd - 2 : (passengerCount === 1 && second ? -2 : -1));
  const to = station.z + (stop.reverse ? 2 : (passengerCount === 1 && first ? 7 : carEnd + 2));
  return box({ x: station.x + 2, y: gy, z: from }, { x: station.x + 3, y: gy + 1, z: to });
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
  const { origin, lineLength, passengerCount, stations, carTop, loop, decoration } = input;
  const { x, y: gy, z: z0 } = origin;
  const carEnd = carEndOf(passengerCount);
  const car = box({ x: x - HALF_WIDTH, y: gy, z: z0 + 3 }, { x: x + HALF_WIDTH, y: carTop, z: z0 + carEnd });
  const track = tracks(origin, lineLength, carEnd, stations, loop);
  const along = (t: Box): Box => t.min.x === t.max.x
    ? box({ x: t.min.x - HALF_WIDTH, y: gy, z: t.min.z }, { x: t.max.x + HALF_WIDTH, y: carTop, z: t.max.z })
    : box({ x: t.min.x, y: gy, z: t.min.z - HALF_WIDTH }, { x: t.max.x, y: carTop, z: t.max.z + HALF_WIDTH });
  const envelope = track.map(along);
  const stationCells = stations.map(({ position: p }) => box(p, p));
  const bed = [...track, ...stationCells].map((b) => box({ ...b.min, y: gy - 1 }, { ...b.max, y: gy - 1 }));
  const platforms = stations.map((stop) => platformOf(stop, stations, passengerCount, gy));
  const bends = loop ? curves(loop, gy, carTop) : [];
  const structures = [car, ...platforms, ...stationCells, ...(decoration ? [decoration] : [])];
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
