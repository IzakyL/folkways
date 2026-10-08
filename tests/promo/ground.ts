import type { MinecraftServer } from "@izakyl/blockwright-minecraft";
import type { Vec } from "../e2e/colony-founding";
import { commandPos, tryCommand } from "../shared/bw-helpers";

export type Cell = { x: number; z: number };

export type Blob = { x: number; z: number; rx: number; rz: number };

export type BlobOptions = {
  seed?: number;
  ragged?: number;
  wobble?: number;
};

export function blobCells(blobs: Blob[], options: BlobOptions = {}): Cell[] {
  const { seed = 0, ragged = 0.5, wobble = 5 } = options;
  const minX = Math.floor(Math.min(...blobs.map((b) => b.x - b.rx)) - 1);
  const maxX = Math.ceil(Math.max(...blobs.map((b) => b.x + b.rx)) + 1);
  const minZ = Math.floor(Math.min(...blobs.map((b) => b.z - b.rz)) - 1);
  const maxZ = Math.ceil(Math.max(...blobs.map((b) => b.z + b.rz)) + 1);
  const cells: Cell[] = [];
  for (let z = minZ; z <= maxZ; z++) {
    for (let x = minX; x <= maxX; x++) {
      let inside = -Infinity;
      for (const blob of blobs) {
        const dx = (x + 0.5 - blob.x) / blob.rx;
        const dz = (z + 0.5 - blob.z) / blob.rz;
        inside = Math.max(inside, 1 - dx * dx - dz * dz);
      }
      if (inside <= (smoothNoise(x, z, seed, wobble) - 0.5) * ragged) continue;
      cells.push({ x, z });
    }
  }
  return cells;
}

export function pruneCells(cells: Cell[], keep = 2): Cell[] {
  const inside = new Set(cells.map(key));
  return cells.filter((cell) => {
    let neighbours = 0;
    for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as Array<[number, number]>) {
      if (inside.has(key({ x: cell.x + dx, z: cell.z + dz }))) neighbours++;
    }
    return neighbours >= keep;
  });
}

export function edgeCells(cells: Cell[], { diagonal = true }: { diagonal?: boolean } = {}): Cell[] {
  const inside = new Set(cells.map(key));
  const ring = new Map<string, Cell>();
  const around: Array<[number, number]> = diagonal
    ? [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]]
    : [[1, 0], [-1, 0], [0, 1], [0, -1]];
  for (const cell of cells) {
    for (const [dx, dz] of around) {
      const beside = { x: cell.x + dx, z: cell.z + dz };
      if (inside.has(key(beside))) continue;
      ring.set(key(beside), beside);
    }
  }
  return [...ring.values()];
}

export function pickCells(cells: Cell[], chance: number, seed: number, wobble = 0): Cell[] {
  return cells.filter((cell) =>
    (wobble > 0 ? smoothNoise(cell.x, cell.z, seed, wobble) : rawNoise(cell.x, cell.z, seed)) < chance,
  );
}

export function nearestCells(cells: Cell[], to: Cell, count: number): Cell[] {
  return [...cells]
    .sort((a, b) => Math.hypot(a.x - to.x, a.z - to.z) - Math.hypot(b.x - to.x, b.z - to.z))
    .slice(0, count);
}

export function cellBounds(cells: Cell[]): { minX: number; maxX: number; minZ: number; maxZ: number } {
  return {
    minX: Math.min(...cells.map((c) => c.x)),
    maxX: Math.max(...cells.map((c) => c.x)),
    minZ: Math.min(...cells.map((c) => c.z)),
    maxZ: Math.max(...cells.map((c) => c.z)),
  };
}

export async function fillCells(server: MinecraftServer, cells: Vec[], block: string) {
  let commands = 0;
  for (const run of runs(cells)) {
    const result = await tryCommand(server, `fill ${commandPos(run.from)} ${commandPos(run.to)} ${block}`);
    if (!(result.status === "ok" && result.success) && !/No blocks were filled/.test((result?.output ?? []).join(" "))) {
      throw new Error(`Placing ${block} failed: ${JSON.stringify(result)}`);
    }
    commands++;
  }
  return { cells: cells.length, commands };
}

function runs(cells: Vec[]): Array<{ from: Vec; to: Vec }> {
  const lines = new Map<string, Vec[]>();
  for (const cell of cells) {
    const line = `${cell.y}:${cell.z}`;
    let bucket = lines.get(line);
    if (!bucket) lines.set(line, (bucket = []));
    bucket.push(cell);
  }
  const spans: Array<{ from: Vec; to: Vec }> = [];
  for (const line of lines.values()) {
    line.sort((a, b) => a.x - b.x);
    let from = line[0];
    let last = line[0];
    for (const cell of line.slice(1)) {
      if (cell.x === last.x + 1) {
        last = cell;
        continue;
      }
      spans.push({ from, to: last });
      from = cell;
      last = cell;
    }
    spans.push({ from, to: last });
  }
  return spans;
}

function key(cell: Cell) {
  return `${cell.x},${cell.z}`;
}

function rawNoise(x: number, z: number, seed: number) {
  let h = Math.imul(x | 0, 374761393) ^ Math.imul(z | 0, 668265263) ^ Math.imul(seed | 0, 1274126177);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}

function smoothNoise(x: number, z: number, seed: number, wobble: number) {
  const gx = x / wobble;
  const gz = z / wobble;
  const x0 = Math.floor(gx);
  const z0 = Math.floor(gz);
  const tx = ease(gx - x0);
  const tz = ease(gz - z0);
  const top = lerp(rawNoise(x0, z0, seed), rawNoise(x0 + 1, z0, seed), tx);
  const bottom = lerp(rawNoise(x0, z0 + 1, seed), rawNoise(x0 + 1, z0 + 1, seed), tx);
  return lerp(top, bottom, tz);
}

function ease(t: number) {
  return t * t * (3 - 2 * t);
}

function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t;
}
