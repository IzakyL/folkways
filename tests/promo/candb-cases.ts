import { type Cell, type Drawing } from "./overhead-view";

type Knobs = Record<string, number | boolean | string[]>;
export type Vec = { x: number; y: number; z: number };
export type Site = { pattern: string; min?: Vec; max?: Vec; points?: Vec[]; knobs: Knobs };
const p = (x: number, z: number, dy = 0): Vec => ({ x, y: 64 + dy, z });
const zone = (name: string, x: number, size: number, knobs: Knobs): Site => ({
  pattern: `folkways_chiselsandbits:${name}`, min: p(x, 10), max: p(x + size - 1, 10 + size - 1), knobs,
});
export const HERO = zone("pavilion", 4, 9, { width: 9, height: 4 });

export type Shot = {
  pattern: string;
  camera: { centre: Vec; yaw: number; off: number; pitch: number };
  drawings: Drawing[];
  fell?: Cell[];
};

const number = (key: string, value: number) => ({ key, value });

const palette = (key: string, before: string, after: string) => ({
  key, toggle: [`minecraft:${before}`, `minecraft:${after}`],
});

const boxes = (x: number, z: number, knobs: Drawing["knobs"][]): Drawing[] => [
  { gesture: "box", cells: [[x, z], [x + 7, z + 7]], knobs: knobs[0] },
  { gesture: "box", cells: [[x + 20, z], [x + 31, z + 6]], knobs: knobs[1] },
  { gesture: "box", cells: [[x, z + 22], [x + 8, z + 34]], knobs: knobs[2] },
  { gesture: "box", cells: [[x + 22, z + 22], [x + 33, z + 33]], knobs: knobs[3] },
];

const lines = (
  x: number, z: number, knobs: Drawing["knobs"][], lift?: Drawing["lift"],
): Drawing[] => [
  { gesture: "line", cells: [[x, z], [x + 28, z]], knobs: knobs[0] },
  { gesture: "line", cells: [[x, z + 18], [x + 16, z + 18], [x + 26, z + 26]], knobs: knobs[1] },
  { gesture: "line", cells: [[x, z + 38], [x + 10, z + 34], [x + 20, z + 40], [x + 30, z + 36]], knobs: knobs[2] },
  { gesture: "line", cells: [[x + 48, z], [x + 44, z + 16], [x + 48, z + 36]], knobs: knobs[3], lift },
];

const lens = (x: number, y: number, z: number, off = 72): Shot["camera"] => ({
  centre: { x, y, z }, yaw: 180, off, pitch: 52,
});

// One fixed picture per family. Draw the far row first so roofs cannot intercept later mouse clicks.
export const SHOTS: Shot[] = [
  { pattern: "pavilion", camera: lens(181, 76, -150, 54), drawings: boxes(162, -170, [
    [number("width", 7), number("height", 3)],
    [number("width", 11), number("height", 5), { key: "railing", tick: true }],
    [palette("beam", "spruce_planks", "dark_oak_planks")],
    [palette("tiles", "deepslate_tiles", "bricks"), number("width", 11)],
  ]) },
  { pattern: "gothic_cloister", camera: lens(181, 76, -150, 82), drawings: lines(156, -172, [
    [number("width", 5)], [number("height", 6), number("bay", 8)],
    [palette("stone", "smooth_sandstone", "polished_diorite")], [number("width", 7), number("bay", 5)],
  ], [76, 78, 76]) },
  { pattern: "stone_arch_bridge", camera: lens(322, 63, 44, 82), drawings: [
    { gesture: "line", cells: [[290, 28], [318, 28]], lift: [64, 64], knobs: [number("width", 3)] },
    { gesture: "line", cells: [[330, 26], [356, 44]], lift: [65, 65],
      knobs: [number("width", 5), number("span", 3)] },
    { gesture: "line", cells: [[290, 52], [318, 58]], lift: [64, 65],
      knobs: [number("camber", 2), palette("stone", "stone_bricks", "sandstone")] },
    { gesture: "line", cells: [[306, 76], [340, 76]], lift: [65, 65] },
  ] },
  { pattern: "rotunda", camera: lens(181, 76, -150, 54), drawings: boxes(162, -170, [
    [number("radius", 3)], [number("radius", 4), number("height", 4)],
    [palette("stone", "smooth_quartz", "sandstone")],
    [palette("floor", "polished_diorite", "stone_bricks"), number("radius", 4)],
  ]) },
  { pattern: "torii", camera: lens(181, 76, -150, 54), drawings: boxes(162, -170, [
    [number("width", 7), number("height", 4)], [number("width", 11), number("height", 6)],
    [palette("frame", "red_concrete", "dark_oak_planks")],
    [palette("cap", "polished_blackstone", "bricks"), number("width", 11)],
  ]) },
  { pattern: "rampart", camera: lens(82, 74, -145, 88), drawings: lines(50, -174, [
    [number("height", 5)], [number("height", 7), number("spacing", 12)],
    [{ key: "gate", tick: true }, palette("stone", "light_gray_concrete", "stone_bricks")],
    [number("spacing", 28), { key: "gate", tick: true }],
  ]) },
  { pattern: "curved_gallery", camera: { ...lens(294, 71, -116, 84), pitch: 60 }, drawings: lines(264, -138, [
    [number("width", 3)], [number("width", 5), number("height", 5)],
    [{ key: "railing", tick: true }, palette("beam", "spruce_planks", "dark_oak_planks")],
    [number("bay", 6), palette("tiles", "gray_concrete", "deepslate_tiles")],
  ]) },
];

// Clear vegetation across each footprint and the foreground, retaining the terrain and water below it.
for (const shot of SHOTS) {
  const cells = shot.drawings.flatMap(drawing => drawing.cells);
  const xs = cells.map(cell => cell[0]);
  const zs = cells.map(cell => cell[1]);
  shot.fell = [];
  for (let x = Math.min(...xs) - 6; x <= Math.max(...xs) + 6; x += 5) {
    for (let z = Math.min(...zs) - 6; z <= Math.max(...zs) + 12; z += 5) {
      shot.fell.push([x, z]);
    }
  }
}
