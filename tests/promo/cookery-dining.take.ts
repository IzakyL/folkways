import { expect, test } from "@playwright/test";
import { shaderEvidence } from "../shared/capture/shaders";
import { take, type PromoPair } from "./promo-kit";
import { areaCells, castColony, fixCamera, K, openScene, place, readScene, rollUntil, setVitals, shoot, stageDining, tableGroup } from "./cookery-scene";

// One long connected table in a dining area. A server carries ready dishes from the larder to the table, and five
// hungry residents walk over, take a free chair beside a dish and eat it there. Any chair of the long table serves
// any dish on it: the tables are one piece of furniture to the dining area.

const DISHES: Array<[string, number]> = [
  [K + "stir_fried_pork_with_peppers", 16], [K + "sweet_and_sour_pork", 16], [K + "braised_beef", 16], [K + "pork_bone_soup", 16],
];
const SECONDS = 70;

test("dining: hungry residents sit down to the dishes set on a shared table", async ({}, testInfo) => {
  const run = take("cookery-dining");
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("cookery-dining");
    pair = scene.pair;
    const { server, at, set, fill } = scene;

    await fill(at(-6, -6, -1), at(6, 1, -1), "minecraft:spruce_planks");
    await fill(at(-5, -5, -1), at(5, 0, -1), "minecraft:birch_planks");
    const larder = at(-8, -3);
    await set(larder, `minecraft:chest[facing=east]{Items:[${DISHES.map(([id, count], slot) => `{Slot:${slot}b,id:"${id}",count:${count}}`).join(",")}]}`);
    for (const x of [-6, 6]) for (const z of [-6, 1]) await set(at(x, z), "minecraft:lantern");
    // Two rows of five tables joined into one: fourteen chairs around one shared top.
    const table = await tableGroup(scene, -2, -3, 2, -2);
    const area = { id: "dining-room", cells: areaCells(scene, -6, -6, 6, 1), tables: table.tables, foods: DISHES.map(([id]) => id) };

    const cast = await castColony(scene, [larder], 6, i => i === 0 ? ["hauling"] : [], at(-6, 15));
    run.note("cast", cast);
    const [waiter, ...diners] = cast.residents;
    await place(server, waiter, at(-7, -1));
    for (const [i, uuid] of diners.entries()) await place(server, uuid, at(9, -5 + i * 1.5));
    run.note("vitals", await setVitals(server, cast.colony_id, [
      { uuid: waiter, food: 20, tired: 0 }, ...diners.map(uuid => ({ uuid, food: 20, tired: 0 }))]));
    run.note("dining", await stageDining(server, cast.colony_id, [area], 900));
    await fixCamera(scene, at(0, 8, 5), at(0, -2.5, 0), 45);

    const everyone = cast.residents;
    const sample = () => readScene(server, cast.colony_id, everyone, [area]);
    // The waiter sets the first dishes while the diners are still full.
    const preRoll = await rollUntil(server, 20, 120_000, sample, (state: any) => state.portions >= 4);
    run.note("preRoll", preRoll.log);
    expect(preRoll.ready, "the waiter must set dishes before the diners get hungry").toBe(true);
    run.note("hungry", await setVitals(server, cast.colony_id, diners.map(uuid => ({ uuid, food: 6, tired: 0 }))));
    // Diners take the nearest free chair, so they start spread along both sides of the table.
    const spots = [at(-4, 1), at(0, 1), at(4, 1), at(-2, -6), at(2, -6)];
    for (const [i, uuid] of diners.entries()) await place(server, uuid, spots[i]);

    const evidence = await shoot(run, scene, "01-dining",
      "Fixed view of a dining room: a waiter carries dishes from the larder to one long shared table, and five hungry "
      + "residents walk in, sit at free chairs beside the dishes and eat", SECONDS, 20, sample, { area, dishes: DISHES });
    run.note("activity", evidence);
    const seated = Math.max(...evidence.map((one: any) => one.people.filter((p: any) => p.seated).length));
    const sat = new Set(evidence.flatMap((one: any) => one.people.filter((p: any) => p.seated).map((p: any) => p.uuid)));
    run.note("summary", { seated, sat: [...sat] });
    expect(seated, "several diners must sit together on camera").toBeGreaterThanOrEqual(3);
    expect(sat.size, "most diners must eat at the table on camera").toBeGreaterThanOrEqual(4);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
