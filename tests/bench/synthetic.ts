import { Terrain, type Survey } from "./town.ts";
import { planTown } from "./town-build.ts";
import { countsOf, type Scale } from "./fixture.ts";
import type { Town } from "./raise.ts";

// Rolling ground with a river curling past the east side of the site, for planning without a game.
export function syntheticSurvey(cx: number, cz: number, radius: number): Survey {
  const minX = cx - radius, minZ = cz - radius, width = radius * 2 + 1, depth = radius * 2 + 1;
  const ground: number[] = [], water: number[] = [], cover: number[] = [];
  for (let i = 0; i < width; i++) for (let j = 0; j < depth; j++) {
    const x = minX + i, z = minZ + j;
    const hill = 70 + 4 * Math.sin(x / 23) * Math.cos(z / 31) + 3 * Math.sin((x + z) / 47);
    const river = Math.abs(x - cx - 150 - 20 * Math.sin(z / 40)) < 9;
    ground.push(Math.round(river ? 58 : hill));
    water.push(river ? 62 : -9999);
    cover.push((x * 7 + z * 13) % 11 === 0 ? 3 : 0);
  }
  return { minX, minZ, width, depth, ground, water, cover, sea: 62 };
}

export const BENCH_SCALE: Scale = { residents: 500, humans: 400, furnaces: 36, blastFurnaces: 12, smokers: 16,
  craftingTables: 56, smithingTables: 8, stonecutters: 8, pens: 24, fishSpots: 12 };

// A town as raiseTown answers it, with the terraced fields and docks left undrawn.
export function syntheticTown(scale: Scale = BENCH_SCALE): Town {
  const terrain = new Terrain(syntheticSurvey(0, 0, 260));
  const site = { x: 0, z: 0, level: 70, sea: 62 };
  const plan = planTown(terrain, site, countsOf(scale), 128);
  return { plan, terrain, site, plots: plan.plots, fishing: plan.ponds.map((p, i) => ({ name: `pond-${i}`, cells: p.cells })),
    wall: null, extent: plan.extent, level: 70 };
}
