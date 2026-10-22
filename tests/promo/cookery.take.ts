import { expect, test } from "@playwright/test";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { defineTask, javaTask, player, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { ADMIT_SETTLERS, runColonyTask } from "../shared/colony/colony-tasks";
import { commandPos, frames } from "../shared/harness/bw-helpers";
import { shaderEvidence } from "../shared/capture/shaders";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast } from "./fast-setup";
import { stageCookerySideWork, readCookerySideWork } from "./cookery-work";
import { configureMarketMenu, stageExtraKitchen, readExtraKitchen } from "./cookery-extra-kitchen";
import { stageExtraYard, readExtraYard } from "./cookery-extra-yard";
import { stageCookeryVisitors, stepCookeryVisitors } from "./cookery-visitors";
import { KITCHEN_WORK, validateCookeryTake } from "./cookery-validation";
import { dressCookeryGarden } from "./cookery-set";
import { prepareCookeryModels, WINE_FOX_MODELS, applyCookeryLooks } from "./cookery-cast";
import { buildCookeryElevator, readCookeryElevator } from "./cookery-elevator";
import { stageCookeryTavern, readCookeryTavern } from "./cookery-tavern";
import { cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, type PromoPair } from "./promo-kit";

const K = "kaleidoscope_cookery:";
const TABLE_MENU = [K + "baozi", K + "stir_fried_pork_with_peppers", K + "pork_bone_soup",
  K + "sashimi", "minecraft:cooked_rabbit", K + "sweet_and_sour_pork", K + "braised_beef", K + "fish_flavored_shredded_pork"];
const PANTRY_SUPPLIES = ["minecraft:wheat", "minecraft:porkchop", K + "green_chili", "minecraft:water_bucket",
  "minecraft:sugar", "minecraft:beef", "minecraft:brown_mushroom", "minecraft:oak_planks", "minecraft:bamboo", "minecraft:iron_ingot", "minecraft:brick", "minecraft:bone", "minecraft:bowl", K + "oil", K + "stockpot_lid"];
const SECONDS = 240;
const VISITORS = 53;

test("cookery: one continuous kitchen and dining scene", async ({}, testInfo) => {
  const run = take("cookery");
  let pair: PromoPair | undefined;
  try {
    prepareCookeryModels();
    pair = await launchPromoPair("cookery", { config: "blockwright.toml#promo-cookery", models: WINE_FOX_MODELS });
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const name = grounded.name;
    await world.command(server, `op ${name}`);
    await cinematicWorld(server);
    run.note("menu", await configureMarketMenu(server));
    await world.command(server, "difficulty normal");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "time set 3500");
    const o = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
    const at = (x: number, z: number, y = 0): Vec => ({ x: o.x + x, y: o.y + y, z: o.z + z });
    const cells = {
      // Keep the lift's west approach level and clear of the chest's climbable collision box.
      food: at(-17, -4), source: at(-17, -2), store: at(-17, 0),
      table: at(-14, -2), board: at(-10, 0, 1), stockpot: at(-12, -4, 1), pot: at(-9, -4, 1),
      steamer: at(-6, -4, 1), mill: at(-23, 1),
      stockpotSecond: at(-11, -4, 1), potSecond: at(-8, -4, 1), steamerSecond: at(-5, -4, 1),
      // Most table dishes are stir-fried; a third skillet keeps one slow pot from idling the line.
      potThird: at(-10, -4, 1),
    };
    const set = async (p: Vec, block: string) => world.command(server, `setblock ${commandPos(p)} ${block}`);
    const scenery = await dressCookeryGarden(server, o);
    run.note("scenery", scenery);
    for (const key of ["food", "store"] as const) await set(cells[key], "minecraft:chest[facing=north]{Items:[]}");
    for (let slot = 0; slot < 3; slot++) await world.command(server,
      `item replace block ${commandPos(cells.food)} container.${slot} with minecraft:cooked_beef 64`);
    // Flour is ground from supplied wheat through the working courtyard mill.
    // All three skillets share a stocked utensil cupboard. Loans still move through the
    // normal shared-tool queue and must physically return after every task.
    for (let slot = 1; slot <= 3; slot++) await world.command(server,
      `item replace block ${commandPos(cells.store)} container.${slot} with ${K}kitchen_shovel`);
    for (let slot = 4; slot <= 5; slot++) await world.command(server,
      `item replace block ${commandPos(cells.store)} container.${slot} with ${K}iron_kitchen_knife`);
    // Tables request their own randomly chosen dishes. Only raw ingredients are supplied;
    // every opening serving and subsequent refill must pass through the native kitchen.
    await set(cells.source, `folkways:creative_supply_box{all:0b,whitelist:${JSON.stringify(PANTRY_SUPPLIES.map(id => "i" + id))}}`);
    await set(cells.table, "minecraft:crafting_table");
    await set(cells.board, K + "chopping_board");
    for (const key of ["stockpot", "pot", "steamer", "stockpotSecond", "potSecond", "steamerSecond", "potThird"] as const) {
      await set({ ...cells[key], y: cells[key].y - 1 }, K + "stove[facing=north,lit=true]");
      const appliance = key.replace(/Second|Third/, "");
      // The skillet's north handle opens directly onto the one-cell front operator aisle.
      await set(cells[key], K + appliance + (appliance === "pot" ? "[facing=north]" : ""));
    }
    // Native mill drivers circle two blocks from the centre; keep the entire orbit
    // and their headroom clear of the inn wall, counters and garden decorations.
    await world.command(server, `fill ${commandPos(at(-26, -2, -1))} ${commandPos(at(-20, 4, -1))} minecraft:smooth_sandstone`);
    await world.command(server, `fill ${commandPos(at(-26, -2))} ${commandPos(at(-20, 4, 3))} minecraft:air`);
    await set(cells.mill, K + "millstone[part=center]");
    for (const [dx, dz, part] of [[-1,-1,"left_up"],[0,-1,"up"],[1,-1,"right_up"],[-1,0,"left_center"],
      [1,0,"right_center"],[-1,1,"left_down"],[0,1,"down"],[1,1,"right_down"]] as const) {
      await set({ x: cells.mill.x + dx, y: cells.mill.y, z: cells.mill.z + dz }, K + `millstone[part=${part}]`);
    }
    await world.command(server, `tp ${name} ${o.x} ${o.y} ${o.z + 2}`);
    await world.command(server, `item replace entity ${name} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 10);
    const founded = await foundPromoColonyFast(server, name, [...Object.values(cells), ...scenery.beds]);
    run.note("founded", founded);
    run.note("elevator", await buildCookeryElevator(server, name, founded.colony_id, o));
    run.note("admitted", await admitPromoSettlersFast(server, name, founded.colony_id, 12));
    const residents = (await world.entities(server, { types: ["folkways:resident"], limit: 128 })).entities;
    expect(residents).toHaveLength(12);
    run.note("looks", await applyCookeryLooks(server, client, residents.map(one => one.uuid)));
    const cooks = residents.slice(0, 5).map(one => one.uuid);
    const diners = residents.slice(5, 8).map(one => one.uuid);
    const porters = residents.slice(8, 12).map(one => one.uuid);
    const farmers: string[] = [];
    run.note("cast", { cooks, diners, porters, licences: await licenseCrewFast(server,
      residents.map((one, i) => ({ uuid: one.uuid, trades: i < 5 ? ["crafting", "hauling"] : ["hauling"] }))) });
    const diningAreas = [...scenery.diningAreas].sort((a, b) => Number(a.id === "balcony") - Number(b.id === "balcony"))
      .map(area => ({ ...area, foods: TABLE_MENU }));
    run.note("dining", await server.run(STAGE_DINING, { colony: founded.colony_id, areas: diningAreas }));
    run.note("kitchenRoles", await server.run(KITCHEN_ROLES, { cooks }));
    for (const [i, resident] of residents.entries()) {
      await world.command(server, `data merge entity ${resident.uuid} {Invulnerable:1b}`);
      const post = i < 5 ? at(-14 + i * 2, -2) : i < 8 ? at(-5, -5 + (i - 5), 6) : at(-6 + (i - 8) * 1.5, -8);
      await world.command(server, `tp ${resident.uuid} ${post.x + 0.5} ${post.y} ${post.z + 0.5}`);
    }
    await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
    await world.command(server, `gamemode spectator ${name}`);
    await setFov(client, 55);
    const feet = at(-8, -31, 10);
    const aim = at(-10, -3, 5);
    const look = cameraLookingAt({ x: feet.x + 0.5, y: feet.y + 1.62, z: feet.z + 0.5 },
      { x: aim.x + 0.5, y: aim.y + 0.5, z: aim.z + 0.5 });
    await world.command(server, `tp ${name} ${feet.x + 0.5} ${feet.y} ${feet.z + 0.5} ${look.yaw} ${look.pitch}`);
    await tick.sprint(server, 5);
    await player.place(client, look);
    await screen.dismiss(client);
    await hideHud(client);
    await frames(client, 30);
    // Let the slow roast start naturally before the one-off yard chores enter the scene.
    const admissions = [];
    for (let i = 0; i < 3; i++) admissions.push(await runColonyTask(server, ADMIT_SETTLERS,
      { player: name, colony_id: founded.colony_id, count: 1 }));
    const known = new Set(residents.map(one => one.uuid));
    const marketNewcomers = (await world.entities(server, { types: ["folkways:resident"], limit: 128 })).entities
      .filter(one => !known.has(one.uuid));
    expect(marketNewcomers).toHaveLength(3);
    residents.push(...marketNewcomers);
    const marketCrew = marketNewcomers.map(one => one.uuid);
    run.note("extraKitchen", await stageExtraKitchen(server, name, founded.colony_id, o, marketCrew));
    for (const resident of marketNewcomers) await world.command(server, `data merge entity ${resident.uuid} {Invulnerable:1b}`);
    for (let i = 0; i < 2; i++) await runColonyTask(server, ADMIT_SETTLERS,
      { player: name, colony_id: founded.colony_id, count: 1 });
    const kitchenKnown = new Set(residents.map(one => one.uuid));
    const vintners = (await world.entities(server, { types: ["folkways:resident"], limit: 128 })).entities
      .filter(one => !kitchenKnown.has(one.uuid));
    expect(vintners).toHaveLength(2);
    residents.push(...vintners);
    const tavernCrew = vintners.map(one => one.uuid);
    run.note("tavern", await stageCookeryTavern(server, name, founded.colony_id, o, tavernCrew));
    const kitchenCrew = [...cooks, ...marketCrew, ...tavernCrew];
    run.note("kitchenCrew", kitchenCrew);
    // All stations exist before warming the dining requests; no separate kitchen orders.
    let ready = false;
    let lastPortions = 0, lastProgress = Date.now();
    const warmup: any[] = [];
    await setTickRate(server, 40);
    await tick.freeze(server, false);
    try {
      const started = Date.now();
      while (Date.now() - started < 600_000) {
        const state = await sample(server, founded.colony_id, residents.map(one => one.uuid), diningAreas, o);
        const kitchen = await readExtraKitchen(server, founded.colony_id, o, marketCrew);
        warmup.push({ ...state, extraKitchen: kitchen });
        if (warmup.length % 10 === 0) run.note("warmup", warmup);
        if (state.portions > lastPortions) { lastPortions = state.portions; lastProgress = Date.now(); }
        if (Date.now() - lastProgress > 90_000) throw new Error("Native kitchen stopped making table progress; inspect warmup.unassigned");
        if (state.diningAreas.every((area: any) => area.portions >= (area.id === "courtyard" ? 14 : 8) && area.drinks >= 2)) {
          ready = true; break;
        }
        await sleepMs(1500);
      }
    } finally {
      await tick.freeze(server, true);
      await setTickRate(server, 20);
      run.note("warmup", warmup);
    }
    expect(ready, "table requests must fill both dining areas before filming").toBe(true);
    // Waiting for wine makes warmup long enough to tire the kitchen. A cook who sleeps mid-dish keeps its
    // inputs and its booked skillet, so the staff start pre-roll rested, as they would a fresh shift.
    run.note("restedAfterWarmup", await runColonyTask(server, REST_CREW, { colony_id: founded.colony_id, uuids: kitchenCrew }));
    // The inn now serves its arriving patrons at the banquet. Existing staff keep
    // their packed rations; new arrivals use the normal dining urge rather than restocking road snacks.
    run.note("tavernMenu", await server.run(TAVERN_MENU, { colony: founded.colony_id }));
    for (let i = 0; i < 4; i++) admissions.push(await runColonyTask(server, ADMIT_SETTLERS,
      { player: name, colony_id: founded.colony_id, count: 1 }));
    run.note("crewAdmissions", admissions);
    const staged = new Set(residents.map(one => one.uuid));
    const newcomers = (await world.entities(server, { types: ["folkways:resident"], limit: 128 })).entities
      .filter(one => !staged.has(one.uuid));
    expect(newcomers).toHaveLength(4);
    residents.push(...newcomers);
    farmers.push(newcomers[0].uuid);
    const yardCrew = newcomers.slice(1).map(one => one.uuid);
    // Fifty-three arrivals join the original three diners in waves from both roads.
    const existing = new Set(residents.map(one => one.uuid));
    for (let i = 0; i < VISITORS; i++) admissions.push(await runColonyTask(server, ADMIT_SETTLERS,
      { player: name, colony_id: founded.colony_id, count: 1 }));
    const arrivals = (await world.entities(server, { types: ["folkways:resident"], limit: 128 })).entities
      .filter(one => !existing.has(one.uuid));
    expect(arrivals).toHaveLength(VISITORS);
    residents.push(...arrivals);
    const visitorCrew = [...diners, ...arrivals.map(one => one.uuid)];
    await tick.freeze(server, true);
    run.note("sideWork", await stageCookerySideWork(server, name, founded.colony_id, o, farmers));
    run.note("extraYard", await stageExtraYard(server, name, founded.colony_id, o, yardCrew));
    const visitors = await stageCookeryVisitors(server, name, founded.colony_id, o, visitorCrew);
    run.note("visitors", visitors);
    run.note("crewAdmissions", admissions);
    for (const resident of newcomers) await world.command(server, `data merge entity ${resident.uuid} {Invulnerable:1b}`);
    run.note("looks", await applyCookeryLooks(server, client, residents.map(one => one.uuid)));
    expect((await world.entities(server, { types: ["minecraft:slime"], limit: 1 })).entities,
      "the courtyard must be free of hostile slimes that obscure and trample its crops").toHaveLength(0);
    // Pre-roll is ordinary world time: guests walk in, claim food and sit themselves.
    // Start filming only after genuine meals and kitchen gestures share the frame.
    const preRoll: any[] = [];
    let visitorSeconds = 0, busy = false;
    await tick.freeze(server, false);
    try {
      const started = Date.now();
      while (Date.now() - started < 120_000) {
        visitorSeconds = (Date.now() - started) / 1000;
        const guests = await stepCookeryVisitors(server, founded.colony_id, o, visitorCrew, visitorSeconds);
        const state = await sample(server, founded.colony_id, residents.map(one => one.uuid), diningAreas, o);
        const eating = state.people.filter((one: any) => one.seatedAtTable).length;
        const upstairsEating = state.people.filter((one: any) => one.seatedAtTable && one.position[1] >= o.y + 5).length;
        const cooking = state.people.filter((one: any) => kitchenCrew.includes(one.uuid) && KITCHEN_WORK.has(one.action)).length;
        preRoll.push({ seconds: visitorSeconds, eating, upstairsEating, cooking, ...state, visitors: guests });
        if (preRoll.length % 10 === 0) run.note("preRoll", preRoll);
        busy = eating >= 6 && upstairsEating >= 2 && cooking >= 2;
        if (busy) break;
        await sleepMs(1000);
      }
    } finally {
      await tick.freeze(server, true);
      run.note("preRoll", preRoll);
    }
    expect(busy, "pre-roll must show six real diners, including two upstairs, and two active cooks before the camera starts").toBe(true);
    run.note("restedBeforeCamera", await runColonyTask(server, REST_CREW, { colony_id: founded.colony_id, uuids: kitchenCrew }));
    await run.shot(client, "composition", { subject: "Ground-floor kitchen, upstairs restaurant and the real elevator share one stationary frame",
      worldState: { cells, diningAreas, cooks, diners }, hard: true });
    const evidence: any[] = [];
    await promoClip(run, client, server, "01-kitchen-dining", {
      subject: "One continuous view of the working kitchen, upstairs diners and residents standing in the moving elevator",
      worldState: { cells, diningAreas, cooks, visitors: visitorCrew, openingServiceStock: {}, openingUtensils: { [K + "kitchen_shovel"]: 3, [K + "iron_kitchen_knife"]: 2 }, openingIngredients: {},
        tableMenu: TABLE_MENU, kitchenSupplies: PANTRY_SUPPLIES }, seconds: SECONDS,
      during: async () => {
        const started = Date.now();
        while ((Date.now() - started) / 1000 < SECONDS) {
          const seconds = (Date.now() - started) / 1000;
          evidence.push({ seconds, ...await sample(server, founded.colony_id,
            residents.map(one => one.uuid), diningAreas, o),
            sideWork: await readCookerySideWork(server, founded.colony_id, o, farmers),
            extraKitchen: await readExtraKitchen(server, founded.colony_id, o, marketCrew),
            extraYard: await readExtraYard(server, founded.colony_id, o, yardCrew),
            visitors: await stepCookeryVisitors(server, founded.colony_id, o, visitorCrew, visitorSeconds + seconds) });
          if (evidence.length % 30 === 0) run.note("activity", evidence);
          await sleepMs(1000);
        }
      },
    });
    run.note("activity", evidence);
    validateCookeryTake({ evidence, preRoll, visitors, visitorCrew, cooks: kitchenCrew, marketCrew, diningAreas,
      TABLE_MENU, SECONDS }, (key, value) => run.note(key, value));
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function sample(server: MinecraftServer, colony: string, uuids: string[], areas: Array<{ id: string; tables: Vec[] }>, origin: Vec): Promise<any> {
  const activity = await server.run(ACTIVITY, { colony, uuids, areas, origin });
  const lift = await readCookeryElevator(server, colony, origin);
  const standing = new Set(lift.passengers.map((one: any) => one.uuid));
  const tavern = await readCookeryTavern(server, colony, origin, []);
  return { ...activity, tavern, people: activity.people.map((one: any) => ({ ...one,
    inLift: standing.has(one.uuid), seatedAtTable: one.dining && one.mounted && !standing.has(one.uuid),
  })), lift };
}

const STAGE_DINING = defineTask<Record<string, any>, any>({
  name: "folkways.promo.cookery-dining-area", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.terms.ItemFilter",
    "io.github.izakyl.folkways.front.engine.colony.ColonyFront", "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
    "io.github.izakyl.folkways.plugins.person.living.LivingContent", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation",
    "io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig", "net.neoforged.neoforge.common.ModConfigSpec",
  ], body: `
var args = Args.of(ctx);
var colony = Colonies.of(ctx.server(), UUID.fromString(args.string("colony"))).orElseThrow();
var front = ColonyFront.of(colony);
// Extend the real meal workload in this isolated filming server; no food or mood is forged.
((ModConfigSpec.IntValue)FolkwaysVanillaConfig.SPEC.getValues().get("living.diningMealTicks")).set(1200);
front.setSetting(LivingContent.ID, LivingContent.FOOD.key(), new ColonySettings.Value.Items(
    List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("cooked_beef")))));
var zones = new ArrayList<Object>();
for (var rawArea : args.list("areas")) {
    var area = Args.of((Map) rawArea, "area");
    var cells = new java.util.LinkedHashSet<BlockPos>();
    for (var raw : area.list("cells")) {
        var cell = (Map<?, ?>) raw;
        cells.add(new BlockPos(((Number) cell.get("x")).intValue(), ((Number) cell.get("y")).intValue(), ((Number) cell.get("z")).intValue()));
    }
    var menu = area.strings("foods").stream().map(id -> ItemFilter.item(ResourceLocation.parse(id))).toList();
    int servings = area.string("id").equals("courtyard") ? 4 : 1;
    var settings = ColonySettings.empty().with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count(servings))
        .with(LivingContent.DINING_FOOD.key(), new ColonySettings.Value.Items(menu))
        .with(LivingContent.DINING_DRINK.key(), new ColonySettings.Value.Items(
            List.of(ItemFilter.item(ResourceLocation.parse("kaleidoscope_tavern:wine")))));
    var zone = front.addZone(ctx.server().overworld(), LivingContent.DINING, cells, settings).orElseThrow();
    zones.add(Map.of("id", area.string("id"), "zone", zone.id().toString(), "servings", servings, "foods", area.strings("foods"),
        "drinks", List.of("kaleidoscope_tavern:wine")));
}
return Map.of("areas", zones);
` }),
});

const TAVERN_MENU = defineTask<{ colony: string }, any>({
  name: "folkways.promo.cookery-tavern-menu", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies",
    "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
    "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
    "io.github.izakyl.folkways.plugins.person.living.LivingContent",
  ], body: `
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
ColonyFront.of(colony).setSetting(LivingContent.ID, LivingContent.FOOD.key(), new ColonySettings.Value.Items(List.of()));
return Sync.stamp(Map.of("arrivingPatrons", "table-meals", "scope", "filming-colony"));
` }),
});

const ACTIVITY = defineTask<Record<string, any>, any>({
  name: "folkways.promo.cookery-dining-activity", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.api.colony.Colonies",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.plugins.person.living.DiningTables",
    "io.github.izakyl.folkways.plugins.person.living.LivingContent", "net.minecraft.core.BlockPos"], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(args.string("colony"))).orElseThrow();
var life = colony.kept(LivingContent.ID);
var people = new ArrayList<Object>();
for (var id : args.strings("uuids")) {
    var entity = level.getEntity(UUID.fromString(id));
    var body = Bodies.of(entity).orElseThrow();
    long dined = -24000;
    for (var raw : life.getList("moods", 10)) {
        var mood = (net.minecraft.nbt.CompoundTag) raw;
        if (mood.hasUUID("resident") && mood.getUUID("resident").equals(entity.getUUID())) dined = mood.getCompound("times").getLong("dined");
    }
    var personState = new LinkedHashMap<String, Object>(Map.of("uuid", id, "doing", body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).orElse("idle"),
        "action", body.doing().map(doing -> doing.what().getPath()).orElse("idle"),
        "dining", entity instanceof io.github.izakyl.folkways.plugins.person.ResidentEntity person && person.leisureMotion().equals("dining"),
        "mounted", entity.getVehicle() != null && entity.getPersistentData().hasUUID("folkways:dining_seat")
            && entity.getPersistentData().getUUID("folkways:dining_seat").equals(entity.getVehicle().getUUID()), "dined", dined,
        "pack", java.util.stream.IntStream.range(0, body.pack().getContainerSize()).mapToObj(i -> body.pack().getItem(i).toString()).toList(),
        "position", List.of(entity.getX(), entity.getY(), entity.getZ())));
    personState.put("tableDrinks", entity.getPersistentData().getInt("folkways:table_drinks"));
    personState.put("lastTableDrink", entity.getPersistentData().getLong("folkways:last_table_drink"));
    personState.put("drinking", entity.getPersistentData().getBoolean("folkways:dining_drinking"));
    people.add(personState);
}
int portions = 0;
var areas = new ArrayList<Object>();
for (var rawArea : args.list("areas")) {
    var area = Args.of((Map) rawArea, "area");
    int count = 0, drinkCount = 0;var tables = new ArrayList<Object>();
    for (var raw : area.list("tables")) {
        var at = (Map<?, ?>) raw;
        var pos = new BlockPos(((Number) at.get("x")).intValue(), ((Number) at.get("y")).intValue(), ((Number) at.get("z")).intValue());
        var inventory = DiningTables.inventory(level, pos).orElseThrow();
        var slots = new ArrayList<Boolean>();
        var foods = new ArrayList<String>();
        var drinks = new ArrayList<String>();
        var drinkLevels = new ArrayList<Integer>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            var food = stack.getFoodProperties(null);
            boolean filled = !stack.isEmpty() && food != null && food.nutrition() > 0;
            slots.add(filled);
            if (filled) { count++;
                foods.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            } else if (!stack.isEmpty()) { drinkCount++;
                drinks.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                drinkLevels.add(com.github.ysbbbbbb.kaleidoscopetavern.item.BottleBlockItem.getBrewLevel(stack)); }
        }
        tables.add(Map.of("position", List.of(pos.getX(), pos.getY(), pos.getZ()), "slots", slots, "foods", foods, "drinks", drinks, "drinkLevels", drinkLevels));
    }
    portions += count;areas.add(Map.of("id", area.string("id"), "portions", count, "drinks", drinkCount, "tables", tables));
}
var origin = args.blockPos("origin");
var appliances = new ArrayList<Object>();
for (var at : List.of(origin.offset(-6, 1, -4), origin.offset(-5, 1, -4), origin.offset(-23, 0, 1))) {
    var be = level.getBlockEntity(at);
    if (be != null) appliances.add(Map.of("at", at.toShortString(), "nbt", be.saveWithFullMetadata(level.registryAccess()).toString()));
}
var toolDiagnostics = new LinkedHashMap<String,Object>();
var cookery = colony.service(io.github.izakyl.folkways.cookery.CookeryContent.ID,
    io.github.izakyl.folkways.cookery.CookeryPresence.class).orElseThrow();
var tools = cookery.tools();
for (var name : List.of("active", "pool")) {
    var field = tools.getClass().getDeclaredField(name);field.setAccessible(true);
    var value = field.get(tools);
    if (name.equals("pool")) {
        for (var part : List.of("queues", "leases")) {
            var inner = value.getClass().getDeclaredField(part);inner.setAccessible(true);
            toolDiagnostics.put(part, String.valueOf(inner.get(value)));
        }
    } else {
        var work = new ArrayList<Object>();
        for (var entry : ((Map<?,?>)value).entrySet()) {
            var node = entry.getValue();var row = new LinkedHashMap<String,Object>();row.put("id",entry.getKey().toString());
            for (var part : List.of("original", "families", "borrower", "started", "workDone")) {
                var inner = node.getClass().getDeclaredField(part);inner.setAccessible(true);var detail = inner.get(node);
                row.put(part, String.valueOf(detail));
                if (part.equals("original")) row.put("stationReady", ((io.github.izakyl.folkways.core.api.work.Node)detail).ready(level));
            }
            work.add(row);
        }
        toolDiagnostics.put("work", work);
    }
}
var siteMethod = io.github.izakyl.folkways.core.engine.labor.Labor.class.getDeclaredMethod("site", UUID.class, net.minecraft.resources.ResourceKey.class);
siteMethod.setAccessible(true);
var site = ((Optional<?>)siteMethod.invoke(null, colony.id(), level.dimension())).orElseThrow();
var diagnosisField = site.getClass().getDeclaredField("diagnosis");diagnosisField.setAccessible(true);
var diagnosis = (io.github.izakyl.folkways.core.engine.plan.Diagnosis)diagnosisField.get(site);
var graphField = site.getClass().getDeclaredField("graph");graphField.setAccessible(true);
var graph = (io.github.izakyl.folkways.core.engine.plan.WeaveView)graphField.get(site);
var unassigned = new ArrayList<Object>();
for (var waiting : diagnosis.unassigned()) {
    var vertex = graph.vertex(waiting.node());if (vertex == null) continue;
    var node = vertex.node();var row = new LinkedHashMap<String,Object>();
    row.put("id",waiting.node().toString());row.put("why",waiting.why().toString());row.put("blockedBy",String.valueOf(waiting.blockedBy()));
    row.put("kind",node.getClass().getSimpleName());row.put("spec",node.spec().toString());
    row.put("group",graph.group(waiting.node()).toString());row.put("pin",String.valueOf(graph.pin(waiting.node())));
    row.put("before",graph.before(waiting.node()).toString());unassigned.add(row);
}
return Map.of("people", people, "portions", portions, "diningAreas", areas, "appliances", appliances,
    "toolDiagnostics", toolDiagnostics, "unassigned", unassigned, "time", level.getGameTime());
` }),
});

// Tiredness is the living presence's own book; this only clears it, as a night's sleep would, for the named staff.
const REST_CREW = defineTask<{ colony_id: string; uuids: string[] }, any>({
  name: "folkways.promo.cookery-rest-crew", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies",
    "io.github.izakyl.folkways.plugins.person.living.LivingContent",
  ], body: `
var args = Args.of(ctx);
var colony = Colonies.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
Object presence = colony.service(LivingContent.ID, Object.class).orElseThrow();
var wearField = presence.getClass().getDeclaredField("wear");
wearField.setAccessible(true);
@SuppressWarnings("unchecked")
Map<UUID, Object> wear = (Map<UUID, Object>) wearField.get(presence);
var tired = Class.forName("io.github.izakyl.folkways.plugins.person.living.Rest").getDeclaredField("tired");
tired.setAccessible(true);
var before = new LinkedHashMap<String, Object>();
for (var id : args.strings("uuids")) {
    var rest = wear.get(UUID.fromString(id));
    if (rest == null) continue;
    before.put(id, Math.round(tired.getDouble(rest)));
    tired.setDouble(rest, 0.0D);
}
return Sync.stamp(Map.of("tiredBefore", before));
` }),
});

const KITCHEN_ROLES = defineTask<{ cooks: string[] }, any>({
  name: "folkways.promo.cookery-kitchen-roles", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.resident.body.Keenness",
    "io.github.izakyl.folkways.core.api.vocation.Vocations", "io.github.izakyl.folkways.plugins.wares.WaresContent",
    "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  ], body: `
for (var uuid : Args.of(ctx).strings("cooks")) {
    var body = Bodies.of(ctx.server().overworld().getEntity(UUID.fromString(uuid))).orElseThrow();
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 24));
    body.licences().trade(WaresContent.trade()).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FIRST));
    body.licences().trade(Vocations.required(Vocations.HAULING)).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FOURTH));
}
return Sync.stamp(Map.of("priority", "cooking-before-general-hauling"));
` }),
});
