import { expect, test } from "@playwright/test";
import { shaderEvidence } from "../shared/capture/shaders";
import { take, type PromoPair } from "./promo-kit";
import { areaCells, castColony, fixCamera, K, openScene, place, preferCrafting, readScene, rollUntil, shoot, stageDining, SUPPLY_BOX, tableGroup } from "./cookery-scene";

// An open kitchen beside one dining table. Three cooks take raw ingredients from the pantry, cook on the stoves and
// the chopping board, and carry each dish to the table the dining area wants filled. No dish exists before the shot.

const MENU = [K + "stir_fried_pork_with_peppers", K + "sweet_and_sour_pork", K + "pork_bone_soup", K + "braised_beef", K + "sashimi"];
const PANTRY = ["minecraft:porkchop", "minecraft:beef", "minecraft:cod", K + "green_chili", "minecraft:sugar",
  "minecraft:water_bucket", "minecraft:brown_mushroom", "minecraft:bone", "minecraft:bowl", "minecraft:wheat",
  "minecraft:oak_planks", "minecraft:bamboo", "minecraft:iron_ingot", "minecraft:brick", K + "oil", K + "stockpot_lid"];
const KITCHEN_WORK = new Set(["preparing_food", "chopping_food", "cooking_food", "serving_food", "crafting"]);
const SECONDS = 60;

test("cookery: cooks make dishes and set them on the table", async ({}, testInfo) => {
  const run = take("cookery-kitchen");
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("cookery-kitchen");
    pair = scene.pair;
    const { server, at, set, fill } = scene;

    // Kitchen on the left, the table on the right; every operator stands on the south side, facing the camera.
    await fill(at(-8, -6, -1), at(4, -1, -1), "minecraft:polished_andesite");
    await fill(at(5, -6, -1), at(10, 0, -1), "minecraft:spruce_planks");
    await fill(at(-8, -6, 0), at(4, -6, 3), "minecraft:bricks");
    await fill(at(-5, -5, 0), at(3, -5, 0), "minecraft:bricks");
    for (const x of [-5, -3, -1, 1, 3]) await set(at(x, -5, 1), "minecraft:smooth_stone_slab[type=bottom]");
    const stations = { stockpot: at(-4, -5, 1), pot: at(-2, -5, 1), potSecond: at(0, -5, 1), board: at(2, -5, 1) };
    for (const key of ["stockpot", "pot", "potSecond"] as const) {
      const p = stations[key];
      await set({ ...p, y: p.y - 1 }, K + "stove[facing=south,lit=true]");
      await set(p, key === "stockpot" ? K + "stockpot[facing=east]" : K + "pot[facing=south]");
    }
    await set({ ...stations.board, y: stations.board.y - 1 }, "minecraft:stripped_spruce_log[axis=x]");
    await set(stations.board, K + "chopping_board[facing=south]");
    const pantry = at(-7, -4), utensils = at(-7, -3), bench = at(-7, -2);
    await set(pantry, `${SUPPLY_BOX}{all:0b,whitelist:${JSON.stringify(PANTRY.map(id => "i" + id))}}`);
    await set(utensils, `minecraft:chest[facing=east]{Items:[${[
      ...[1, 2, 3].map(slot => `{Slot:${slot}b,id:"${K}kitchen_shovel",count:1}`),
      ...[4, 5].map(slot => `{Slot:${slot}b,id:"${K}iron_kitchen_knife",count:1}`)].join(",")}]}`);
    await set(bench, "minecraft:crafting_table");
    const table = await tableGroup(scene, 7, -4, 8, -2);
    const area = { id: "kitchen-table", cells: areaCells(scene, 5, -6, 10, 0), tables: table.tables, foods: MENU, servings: 2 };

    const cast = await castColony(scene, [...Object.values(stations), pantry, utensils, bench], 3, () => ["crafting", "hauling"], at(-8, 15));
    run.note("cast", cast);
    const cooks = cast.residents;
    run.note("roles", await preferCrafting(server, cooks, [["minecraft:cooked_beef", 24]]));
    for (const [i, uuid] of cooks.entries()) await place(server, uuid, at(-4 + i * 3, -2));
    run.note("dining", await stageDining(server, cast.colony_id, [area]));
    await fixCamera(scene, at(1, 7, 9), at(1, -3, 0), 50);
    await run.shot(scene.client, "layout", { subject: "Empty table beside the cold kitchen before anyone cooks", worldState: { stations, area }, hard: true });

    const sample = () => readScene(server, cast.colony_id, cooks, [area]);
    const cooking = (state: any) => state.people.filter((one: any) => KITCHEN_WORK.has(one.action)).length;
    const preRoll = await rollUntil(server, 40, 180_000, sample, state => cooking(state) >= 2);
    run.note("preRoll", preRoll.log);
    expect(preRoll.ready, "two cooks must be working before the camera rolls").toBe(true);
    const before = (preRoll.state as any).portions;

    const evidence = await shoot(run, scene, "01-kitchen",
      "Fixed view of an open kitchen: three cooks fetch ingredients from the pantry, cook in the stockpot and pots, "
      + "chop on the board and carry each finished dish to the dining table beside them", SECONDS, 30, sample,
      { stations, menu: MENU, pantry: PANTRY });
    run.note("activity", evidence);
    const served = Math.max(...evidence.map((one: any) => one.portions)) - before;
    // Walking to a stove or carrying a dish to the table is kitchen work too.
    const working = (state: any) => state.people.some((one: any) => KITCHEN_WORK.has(one.toward) || one.toward === "setting_table");
    const busy = evidence.filter(working).length / evidence.length;
    run.note("summary", { before, served, busy });
    expect(served, "dishes must reach the table on camera").toBeGreaterThanOrEqual(4);
    expect(busy, "the kitchen must be at work for most of the shot").toBeGreaterThan(0.5);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
