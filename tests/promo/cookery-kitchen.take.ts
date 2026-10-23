import { expect, test } from "@playwright/test";
import { shaderEvidence } from "../shared/capture/shaders";
import { take, type PromoPair } from "./promo-kit";
import { areaCells, castColony, fixCamera, K, openScene, place, preferCrafting, readScene, rollUntil, setVitals, shoot, stageDining, SUPPLY_BOX, tableGroup } from "./cookery-scene";

// An open kitchen beside one dining table. Four cooks take raw ingredients from the pantry and work every Cookery
// station on the line: wheat ground on the millstone for bun dough, buns in the steamer, bone soup in the stockpot and
// pork on the pots and chopping board. Each dish goes to the table the dining area wants filled. No dish exists
// before the shot.

const MENU = [K + "dongpo_pork", K + "pork_bone_soup", K + "baozi"];
const PANTRY = ["minecraft:porkchop", "minecraft:chicken", K + "green_chili", "minecraft:bowl",
  "minecraft:wheat", "minecraft:water_bucket", "minecraft:bone",
  "minecraft:oak_planks", "minecraft:bamboo", "minecraft:iron_ingot", "minecraft:brick", K + "oil", K + "stockpot_lid"];
const KITCHEN_WORK = new Set(["preparing_food", "chopping_food", "cooking_food", "serving_food", "crafting"]);
const SECONDS = 120;
const MILL_PARTS = [[-1, -1, "left_up"], [0, -1, "up"], [1, -1, "right_up"], [-1, 0, "left_center"], [0, 0, "center"],
  [1, 0, "right_center"], [-1, 1, "left_down"], [0, 1, "down"], [1, 1, "right_down"]] as const;

test("cookery: cooks make dishes and set them on the table", async ({}, testInfo) => {
  const run = take("cookery-kitchen");
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("cookery-kitchen");
    pair = scene.pair;
    const { server, at, set, fill } = scene;

    // Millstone on the far left, the cooking line along the wall, the table on the right; every operator stands on the
    // south side, facing the camera.
    await fill(at(-13, -6, -1), at(4, 0, -1), "minecraft:polished_andesite");
    await fill(at(5, -6, -1), at(10, 0, -1), "minecraft:spruce_planks");
    await fill(at(-13, -6, 0), at(4, -6, 3), "minecraft:bricks");
    await fill(at(-7, -5, 0), at(3, -5, 0), "minecraft:bricks");
    for (const x of [-7, -5, -3, -1, 1, 3]) await set(at(x, -5, 1), "minecraft:smooth_stone_slab[type=bottom]");
    const stations = { steamer: at(-6, -5, 1), stockpot: at(-4, -5, 1), pot: at(-2, -5, 1), potSecond: at(0, -5, 1),
      board: at(2, -5, 1), mill: at(-10, -2) };
    for (const key of ["steamer", "stockpot", "pot", "potSecond"] as const)
      await set({ ...stations[key], y: stations[key].y - 1 }, K + "stove[facing=south,lit=true]");
    // Stockpot and steamer are worked from the side clockwise of their facing: east puts it on the south aisle.
    await set(stations.steamer, K + "steamer[facing=east]");
    await set(stations.stockpot, K + "stockpot[facing=east]");
    for (const key of ["pot", "potSecond"] as const) await set(stations[key], K + "pot[facing=south]");
    await set({ ...stations.board, y: stations.board.y - 1 }, "minecraft:stripped_spruce_log[axis=x]");
    await set(stations.board, K + "chopping_board[facing=south]");
    for (const [dx, dz, part] of MILL_PARTS)
      await set({ ...stations.mill, x: stations.mill.x + dx, z: stations.mill.z + dz }, K + `millstone[part=${part},facing=north]`);
    const pantry = at(-7, -4), utensils = at(-7, -3), bench = at(-7, -2);
    await set(pantry, `${SUPPLY_BOX}{all:0b,whitelist:${JSON.stringify(PANTRY.map(id => "i" + id))}}`);
    await set(utensils, `minecraft:chest[facing=east]{Items:[${[
      ...[0, 1, 2, 3].map(slot => `{Slot:${slot}b,id:"${K}kitchen_shovel",count:1}`),
      ...[4, 5].map(slot => `{Slot:${slot}b,id:"${K}iron_kitchen_knife",count:1}`)].join(",")}]}`);
    await set(bench, "minecraft:crafting_table");
    const table = await tableGroup(scene, 7, -4, 8, -2);
    const area = { id: "kitchen-table", cells: areaCells(scene, 5, -6, 10, 0), tables: table.tables, foods: MENU, servings: 2 };

    const cast = await castColony(scene, [...Object.values(stations), pantry, utensils, bench], 7, i => i < 4 ? ["crafting", "hauling"] : [], at(-8, 15));
    run.note("cast", cast);
    const cooks = cast.residents.slice(0, 4), diners = cast.residents.slice(4);
    await setVitals(server, cast.colony_id, cast.residents.map(uuid => ({ uuid, food: 20, tired: 0 })));
    for (const [i, uuid] of diners.entries()) await place(server, uuid, at(9, 2 + i));
    run.note("roles", await preferCrafting(server, cooks, [["minecraft:cooked_beef", 24]]));
    for (const [i, uuid] of cooks.entries()) await place(server, uuid, at(-6 + i * 3, -2));
    run.note("diningSetup", await stageDining(server, cast.colony_id, [area], 200));
    await fixCamera(scene, at(-2, 8, 11), at(-2, -3, 0), 55);
    await run.shot(scene.client, "layout", { subject: "Empty table beside the cold kitchen before anyone cooks", worldState: { stations, area }, hard: true });

    const sample = () => readScene(server, cast.colony_id, cast.residents, [area]);
    const cooking = (state: any) => state.people.filter((one: any) => KITCHEN_WORK.has(one.action)).length;
    const preRoll = await rollUntil(server, 40, 180_000, sample, state => cooking(state) >= 2);
    run.note("preRoll", preRoll.log);
    expect(preRoll.ready, "two cooks must be working before the camera rolls").toBe(true);
    const before = (preRoll.state as any).portions;

    // The diners get hungry one at a time, seconds apart, and each comes in from somewhere else.
    const ARRIVALS = [{ from: at(6, 1), after: 0 }, { from: at(9.5, 4), after: 9 }, { from: at(5, 6.5), after: 20 }];
    let firstServed = 0, invitedCount = 0;
    const cookingAndDining = async () => {
      const state = await sample();
      if (!firstServed && state.portions >= 4) firstServed = Date.now();
      while (firstServed && invitedCount < diners.length && Date.now() - firstServed >= ARRIVALS[invitedCount].after * 1000) {
        const uuid = diners[invitedCount];
        // Large dishes may waste a bounded amount; diners need not starve before accepting them.
        await setVitals(server, cast.colony_id, [{ uuid, food: 6, tired: 0 }]);
        await place(server, uuid, ARRIVALS[invitedCount].from);
        invitedCount++;
      }
      return state;
    };
    const evidence = await shoot(run, scene, "01-kitchen",
      "Four cooks grind wheat on the millstone, cut dough on the chopping board and cook in the steamer, stockpot and pots, "
      + "then carry the dishes to the nearby table; three hungry residents drift in one by one and eat while cooking continues", SECONDS, 20, cookingAndDining,
      { stations, menu: MENU, pantry: PANTRY });
    run.note("activity", evidence);
    const served = Math.max(...evidence.map((one: any) => one.portions)) - before;
    // Walking to a stove or carrying a dish to the table is kitchen work too.
    const working = (state: any) => state.people.some((one: any) => KITCHEN_WORK.has(one.toward) || one.toward === "setting_table");
    const busy = evidence.filter(working).length / evidence.length;
    run.note("summary", { before, served, busy });
    expect(served, "dishes must reach the table on camera").toBeGreaterThanOrEqual(4);
    expect(busy, "the kitchen must be at work for most of the shot").toBeGreaterThan(0.5);
    expect(invitedCount, "freshly cooked dishes must invite the diners during the kitchen shot").toBe(diners.length);
    const kitchenSeated = new Set(evidence.flatMap((one: any) => one.people.filter((p: any) => diners.includes(p.uuid)
      && p.seated && p.action === "dining_eating").map((p: any) => p.uuid)));
    expect(kitchenSeated.size, "at least two residents must eat in the kitchen shot itself").toBeGreaterThanOrEqual(2);
    const dining = await shoot(run, scene, "02-dining",
      "The same kitchen and table: residents sit down to the dishes the cooks have just made, while cooking and serving continue",
      70, 20, sample, { area });
    run.note("dining", dining);
    const sat = new Set([...evidence, ...dining].flatMap((one: any) => one.people.filter((p: any) => diners.includes(p.uuid) && p.seated).map((p: any) => p.uuid)));
    run.note("diningSummary", { sat: [...sat] });
    expect(sat.size, "the residents must eat the freshly cooked dishes on camera").toBeGreaterThanOrEqual(3);
    const finished = dining.at(-1).people.filter((p: any) => diners.includes(p.uuid) && p.lastDined > preRoll.state.time);
    expect(finished.length, "residents must finish real table meals, not merely sit").toBeGreaterThanOrEqual(2);
    run.note("summary", { before, served, busy, sat: [...sat], finished: finished.map((p: any) => p.uuid) });
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
