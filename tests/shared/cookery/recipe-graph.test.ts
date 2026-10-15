import assert from "node:assert/strict";
import { test } from "node:test";

const { analyze } = await import(new URL("./recipe-graph.mjs", import.meta.url).href);
const K = "kaleidoscope_cookery:";
const item = (tags: string[] = [], food = false) => ({ tags, food });
const recipe = (id: string, result: string, groups: string[][], type = "minecraft:crafting_shapeless", definition = {}) => ({
  id, result: { id: result, count: 1 }, resolved_ingredients: groups, type, definition, cookery_owned: id.startsWith(K),
});
const audit = (items: Record<string, ReturnType<typeof item>>, recipes: ReturnType<typeof recipe>[]) => ({
  items, recipes, native_recipe_count: recipes.filter(entry => entry.cookery_owned).length,
});

test("dish graph takes the shortest required chain without reverse packing or Create shortcuts", () => {
  const report = analyze(audit({
    "minecraft:wheat": item(), [K + "flour"]: item(), [K + "dough"]: item(), [K + "food"]: item([], true),
  }, [
    recipe(K + "flour", K + "flour", [["minecraft:wheat"]]),
    recipe(K + "dough", K + "dough", [[K + "flour"]]),
    recipe(K + "food", K + "food", [[K + "dough"]]),
    recipe(K + "unpack", K + "dough", [[K + "food"]]),
    recipe("create:shortcut", K + "dough", [["minecraft:wheat"]]),
  ]));
  assert.equal(report.max_depth, 3);
  assert.deepEqual(report.deepest[0].route.steps, [K + "flour", K + "dough", K + "food"]);
  assert.deepEqual(report.deepest[0].route.bases, ["minecraft:wheat"]);
  assert.deepEqual(report.unreachable, []);
});

test("dish graph accounts for hidden pot oil and stockpot fluid but excludes serving bowls", () => {
  const report = analyze(audit({
    "minecraft:wheat_seeds": item(["c:seeds"]), "minecraft:carrot": item(), "minecraft:water_bucket": item(),
    "minecraft:bowl": item(), [K + "oil"]: item([K + "oil"]), [K + "stir_fry"]: item([], true), [K + "soup"]: item([], true),
  }, [
    recipe(K + "oil", K + "oil", [["minecraft:wheat_seeds"]]),
    recipe(K + "stir_fry", K + "stir_fry", [["minecraft:carrot"], ["minecraft:bowl"]], K + "pot"),
    recipe(K + "soup", K + "soup", [[K + "stir_fry"], ["minecraft:bowl"]], K + "stockpot", { soup_base: K + "water" }),
  ]));
  assert.equal(report.max_depth, 3);
  assert.deepEqual(report.deepest[0].route.bases, ["minecraft:carrot", "minecraft:water_bucket", "minecraft:wheat_seeds"]);
  assert.deepEqual(report.deepest[0].route.support, ["minecraft:bowl"]);
});

test("repeated alternative ingredient slots count required kinds once", () => {
  const report = analyze(audit({ "minecraft:carrot": item(), "minecraft:potato": item(), [K + "food"]: item([], true) }, [
    recipe(K + "food", K + "food", [["minecraft:carrot", "minecraft:potato"], ["minecraft:carrot", "minecraft:potato"], ["minecraft:carrot"]]),
  ]));
  assert.equal(report.max_base_kinds, 1);
  assert.deepEqual(report.deepest[0].route.bases, ["minecraft:carrot"]);
});

test("native served plates and drinks remain food goals without a FOOD component", () => {
  const report = analyze(audit({
    "minecraft:apple": item(), [K + "apple_platter"]: item(), [K + "clay_pot_milk_tea"]: item(),
    [K + "dark_cuisine"]: item([], true), [K + "suspicious_stir_fry"]: item([], true), [K + "mystery_tea"]: item(),
  }, [
    recipe(K + "apple_platter", K + "apple_platter", [["minecraft:apple"]]),
    recipe(K + "clay_pot_milk_tea", K + "clay_pot_milk_tea", [["minecraft:apple"]]),
  ]));
  assert.equal(report.scope.food_or_drink_targets, 2);
  assert.equal(report.scope.all_final_targets, 5);
  assert.deepEqual(report.non_food_recipe_targets, [K + "apple_platter", K + "clay_pot_milk_tea"]);
  assert.equal(report.procedural_without_recipe.length, 3);
});
