import fs from "node:fs";
import { pathToFileURL } from "node:url";

// Native 1.6.0 commit 4d39e36c: PlateRegistry serves food without FOOD on its BlockItems;
// ClayPotMilkTeaItem is a drink and cold_cut_ham_slices is FoodBiteThreeByThreeBlock.
// These are registry IDs checked against the fixed source and actual runtime audit, not filename guesses.
const servedWithoutFood = new Set([
  "shengjian_mantou_plate", "baozi_plate", "qingtuan_plate", "sticky_candy_plate", "sticky_rice_cake_plate", "zongzi_plate",
  "berry_platter", "apple_platter", "tomato_platter", "watermelon_platter", "chorus_fruit_platter",
  "clay_pot_milk_tea", "cold_cut_ham_slices",
].map(id => "kaleidoscope_cookery:" + id));

const proceduralFood = [
  { item: "kaleidoscope_cookery:dark_cuisine", source: "PotBlockEntity.onTakeOut when BURNT", resident_use: "FoodProperties: edible when stocked", orderable: true },
  { item: "kaleidoscope_cookery:suspicious_stir_fry", source: "PotBlockEntity.applySuspiciousRecipe for an unmatched ingredient combination", resident_use: "FoodProperties: edible when stocked", orderable: true },
  { item: "kaleidoscope_cookery:mystery_tea", source: "TeapotBlockEntity.tick: unmatched tea input produces four cups", resident_use: "No FoodProperties; not a resident ration", orderable: true },
];

// Feed the runtime GameTest audit, never names inferred from JSON filenames.
export function analyze(audit) {
  const support = new Set(["minecraft:bowl", "minecraft:bucket", "minecraft:glass_bottle", "minecraft:glass", "minecraft:string", "minecraft:paper", "minecraft:stick", "minecraft:bamboo", "minecraft:coal", "minecraft:charcoal"]);
  const raw = new Set(["wheat", "melon_slice", "melon", "pumpkin", "sugar_cane", "honey_bottle", "milk_bucket", "water_bucket", "egg", "turtle_egg", "brown_mushroom", "red_mushroom", "beetroot", "carrot", "potato", "apple", "sweet_berries", "glow_berries", "cocoa_beans", "beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon", "tropical_fish", "pufferfish", "slime_ball", "kelp", "amethyst_shard", "snowball", "ice", "leather", "rabbit_hide", "chainmail_boots", "nautilus_shell", "dragon_breath", "sculk", "pink_petals"].map(id => "minecraft:" + id));
  for (const [id, item] of Object.entries(audit.items)) {
    if (item.tags.some(tag => tag === "c:seeds")) raw.add(id);
    if (id.startsWith("kaleidoscope_cookery:") && ["tomato", "red_chili", "green_chili", "lettuce", "rice_panicle", "fresh_tea_leaves", "caterpillar", "raw_cow_offal"].includes(id.split(":")[1])) raw.add(id);
  }
  // Rice is processed grain, despite its native seed tag; prefer the harvested panicle/wild seed route.
  raw.delete("kaleidoscope_cookery:rice");
  function members(value) {
    if (Array.isArray(value)) return [...new Set(value.flatMap(members))].sort();
    if (!value) return [];
    if (value.item) return [value.item];
    if (value.tag) return Object.entries(audit.items).filter(([, item]) => item.tags.includes(value.tag)).map(([id]) => id).sort();
    return [];
  }
  const recipes = audit.recipes.filter(r => r.result && (r.cookery_owned || r.id.startsWith("minecraft:"))).map(r => {
    const ingredients = r.resolved_ingredients.filter(group => group.length).map(group => group.filter(id => id.startsWith("minecraft:") || id.startsWith("kaleidoscope_cookery:")));
    if (r.type === "kaleidoscope_cookery:teapot") ingredients.push(members(r.definition.ingredient), [r.definition.tea_fluid === "minecraft:milk" ? "minecraft:milk_bucket" : "minecraft:water_bucket"]);
    if (r.type.endsWith(":pot") || r.type.endsWith(":flex_pot")) ingredients.push(members({ tag: "kaleidoscope_cookery:oil" }));
    if (r.type.endsWith(":stockpot") || r.type.endsWith(":flex_stockpot")) {
      const soup = r.definition.soup_base ?? "kaleidoscope_cookery:water";
      if (soup.endsWith(":water")) ingredients.push(["minecraft:water_bucket"]);
      else if (soup.endsWith(":milk")) ingredients.push(["minecraft:milk_bucket"]);
      else if (soup === "minecraft:lava") ingredients.push(["minecraft:lava_bucket"]);
      else if (/^minecraft:(pufferfish|cod|salmon|tropical_fish)_bucket$/.test(soup)) ingredients.push([soup]);
      else throw new Error("Unclassified soup base " + soup + " for " + r.id);
    }
    // Identical tags/slots use one alternative. Repetition increases quantity, never distinct kinds.
    const groups = [...new Map(ingredients.map(group => [JSON.stringify(group), group])).values()];
    return { ...r, groups };
  }).filter(r => r.groups.length);
  const byOutput = Map.groupBy(recipes, r => r.result.id);
  const outputs = new Set(byOutput.keys());
  const depth = new Map();
  const allInputs = new Set(recipes.flatMap(r => r.groups.flat()));
  for (const id of allInputs) if (raw.has(id) || support.has(id) || !outputs.has(id)) depth.set(id, 0);
  for (let pass = 0; pass <= outputs.size; pass++) {
    let changed = false;
    for (const r of recipes) {
      if (raw.has(r.result.id) || support.has(r.result.id)) continue;
      const ds = r.groups.map(group => Math.min(...group.map(id => depth.get(id) ?? Infinity)));
      const d = 1 + Math.max(...ds);
      if (d < (depth.get(r.result.id) ?? Infinity)) { depth.set(r.result.id, d); changed = true; }
    }
    if (!changed) break;
  }
  const cache = new Map();
  const subset = (a, b) => a.every(id => b.includes(id));
  function pareto(routes) {
    const unique = [...new Map(routes.map(route => [route.bases.join("|"), route])).values()].sort((a, b) => a.bases.length - b.bases.length || a.steps.length - b.steps.length || a.bases.join().localeCompare(b.bases.join()));
    const kept = [];
    for (const route of unique) if (!kept.some(other => subset(other.bases, route.bases))) kept.push(route);
    return kept;
  }
  function routes(id) {
    if (cache.has(id)) return cache.get(id);
    const d = depth.get(id);
    if (d === undefined) return [];
    if (d === 0) return [{ bases: support.has(id) ? [] : [id], support: support.has(id) ? [id] : [], steps: [], root: null }];
    const possible = [];
    for (const r of byOutput.get(id) ?? []) {
      const ds = r.groups.map(group => Math.min(...group.map(item => depth.get(item) ?? Infinity)));
      if (1 + Math.max(...ds) !== d) continue;
      let combinations = [{ bases: [], support: [], steps: [], root: r.id }];
      for (const [index, group] of r.groups.entries()) {
        const options = group.filter(item => depth.get(item) === ds[index]).flatMap(routes);
        combinations = pareto(combinations.flatMap(part => options.map(option => ({
          bases: [...new Set([...part.bases, ...option.bases])].sort(),
          support: [...new Set([...part.support, ...option.support])].sort(),
          steps: [...new Set([...part.steps, ...option.steps])], root: r.id,
        }))));
      }
      possible.push(...combinations.map(route => ({ ...route, steps: [...route.steps, r.id] })));
    }
    const result = pareto(possible); cache.set(id, result); return result;
  }
  const targets = [...new Set(audit.recipes.filter(r => r.cookery_owned && r.result && (audit.items[r.result.id]?.food || r.type === "kaleidoscope_cookery:teapot" || servedWithoutFood.has(r.result.id))).map(r => r.result.id))].sort();
  const dishes = targets.map(item => {
    const choices = routes(item).sort((a, b) => a.bases.length - b.bases.length || a.steps.length - b.steps.length || a.root?.localeCompare(b.root ?? ""));
    const root = recipes.find(recipe => recipe.id === choices[0]?.root);
    return { item, depth: depth.get(item), base_kinds: choices[0]?.bases.length, route: choices[0],
      process_steps: choices[0]?.steps.length, direct_ingredient_slots: root?.resolved_ingredients.filter(group => group.length).length,
      direct_food_groups_including_native_fluid_and_oil: root?.groups.filter(group => !group.every(id => support.has(id))),
      minimal_base_sets: choices.length };
  });
  const reachable = dishes.filter(d => d.route);
  const maxDepth = Math.max(...reachable.map(d => d.depth));
  const maxKinds = Math.max(...reachable.map(d => d.base_kinds));
  const nonRecipe = proceduralFood.filter(food => audit.items[food.item] && !byOutput.has(food.item));
  const harvestOnly = ["tomato", "red_chili", "green_chili", "lettuce", "caterpillar"].map(id => "kaleidoscope_cookery:" + id)
    .filter(id => audit.items[id] && !byOutput.has(id));
  return { scope: { native_count: audit.native_recipe_count, food_or_drink_targets: dishes.length,
    procedural_targets_without_recipe: nonRecipe.length, all_final_targets: dishes.length + nonRecipe.length, method: "Each ingredient chooses its own minimum depth; shared identical slots choose one alternative; among minimum-depth routes minimize distinct required base ingredients. Containers, tools and fuels excluded. Raw harvest/animal inputs fixed above; no packing/reverse cycles." }, non_food_recipe_targets: dishes.filter(d => !audit.items[d.item]?.food).map(d => d.item),
    procedural_without_recipe: nonRecipe, harvest_only_edible_inputs: harvestOnly,
    raw_boundary: [...raw].sort(), support_boundary: [...support].sort(), max_depth: maxDepth, deepest: reachable.filter(d => d.depth === maxDepth), max_base_kinds: maxKinds, most_base_kinds: reachable.filter(d => d.base_kinds === maxKinds), unreachable: dishes.filter(d => !d.route), dishes };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const report = analyze(JSON.parse(fs.readFileSync(process.argv[2], "utf8")));
  fs.writeFileSync(process.argv[3], JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify({ recipe_targets: report.scope.food_or_drink_targets, all_final_targets: report.scope.all_final_targets, procedural_without_recipe: report.procedural_without_recipe, max_depth: report.max_depth, deepest: report.deepest.map(d => [d.item, d.route.bases]), max_base_kinds: report.max_base_kinds, most_base_kinds: report.most_base_kinds.map(d => [d.item, d.route.bases]), unreachable: report.unreachable.map(d => d.item) }, null, 2));
}
