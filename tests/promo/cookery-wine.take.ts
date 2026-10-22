import { expect, test } from "@playwright/test";
import { shaderEvidence } from "../shared/capture/shaders";
import { take, type PromoPair } from "./promo-kit";
import { areaCells, castColony, fixCamera, K, openScene, place, readScene, rollUntil, setVitals, shoot, stageDining, T, tableGroup } from "./cookery-scene";
import { readTavern, stageTavern } from "./tavern-stage";

// A small winery beside a dining table. Two vintners press grapes in the tub, pour the juice into the barrel and,
// once it has fermented, bottle wine from the tap; the bottles go to the bar cabinet in the dining area. Then three
// hungry residents each take a bottle from the cabinet, sit down to a dish and drink with the meal.

const DISHES: Array<[string, number]> = [[K + "braised_beef", 16], [K + "sweet_and_sour_pork", 16]];
const WINE = T + "wine";

test("wine: vintners press and bottle wine that diners take with their meal", async ({}, testInfo) => {
  const run = take("cookery-wine");
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("cookery-wine");
    pair = scene.pair;
    const { server, at, set, fill } = scene;

    await fill(at(-7, -4, -1), at(4, 3, -1), "minecraft:smooth_sandstone");
    await fill(at(5, -4, -1), at(11, 3, -1), "minecraft:spruce_planks");
    const table = await tableGroup(scene, 7, -1, 8, 0);
    // A bar cabinet holds two bottles; two of them keep a bottle for each diner.
    const cabinets = [at(6, -3), at(10, -3)];
    for (const cabinet of cabinets) await set(cabinet, T + "bar_cabinet[facing=north]");
    const larder = at(12, 1);
    await set(larder, `minecraft:chest[facing=west]{Items:[${DISHES.map(([id, count], slot) => `{Slot:${slot}b,id:"${id}",count:${count}}`).join(",")}]}`);
    const area = { id: "wine-table", cells: areaCells(scene, 5, -4, 11, 3), tables: table.tables, cabinets,
      foods: DISHES.map(([id]) => id), drinks: [WINE] };

    const cast = await castColony(scene, [larder], 6, i => i < 3 ? ["hauling"] : [], at(-6, -14));
    run.note("cast", cast);
    const vintners = cast.residents.slice(0, 2), waiter = cast.residents[2], diners = cast.residents.slice(3);
    run.note("tavern", await stageTavern(server, scene.name, cast.colony_id, scene.o, vintners));
    await place(server, waiter, at(12, -1));
    for (const [i, uuid] of diners.entries()) await place(server, uuid, at(13, -3 + i * 2));
    run.note("vitals", await setVitals(server, cast.colony_id, cast.residents.map(uuid => ({ uuid, food: 20, tired: 0 }))));
    run.note("dining", await stageDining(server, cast.colony_id, [area], 900));
    // The winery faces north, so the camera stands on that side.
    await fixCamera(scene, at(3, -15, 7), at(3, 0, -1), 50);

    const everyone = cast.residents;
    const sample = async () => ({ ...await readScene(server, cast.colony_id, everyone, [area]),
      tavern: await readTavern(server, cast.colony_id, scene.o, vintners) });
    const brewing = await shoot(run, scene, "01-pressing",
      "Fixed view of a small winery: two vintners carry grapes from the store to the pressing tub, press them and pour "
      + "the juice into the barrel", 45, 20, sample, { area });
    run.note("brewing", brewing);
    const pressed = brewing.some((one: any) => one.tavern.tub.fluidAmount > 0 || one.tavern.barrel.fluidAmount > 0);
    expect(pressed, "grapes must be pressed into juice on camera").toBe(true);

    // Fermentation takes a while; the cut skips it. The waiter sets the table meanwhile.
    const stocked = await rollUntil(server, 40, 600_000, sample, (state: any) => state.drinks >= 3 && state.portions >= 3);
    run.note("stocked", stocked.log);
    expect(stocked.ready, "bottled wine must reach the bar cabinet and dishes the table").toBe(true);
    run.note("hungry", await setVitals(server, cast.colony_id, diners.map(uuid => ({ uuid, food: 6, tired: 0 }))));

    const dining = await shoot(run, scene, "02-wine-with-meal",
      "The same view later: wine is bottled at the tap, and three hungry residents each take a bottle from the bar "
      + "cabinet, sit at the table beside a dish and drink with their meal", 70, 20, sample, { area });
    run.note("dining", dining);
    const drank = new Set(dining.flatMap((one: any) => one.people.filter((p: any) => diners.includes(p.uuid)
      && (p.drinking || p.tableDrinks > 0)).map((p: any) => p.uuid)));
    run.note("summary", { drank: [...drank] });
    expect(drank.size, "diners must drink wine at the table on camera").toBeGreaterThanOrEqual(2);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
