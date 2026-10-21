import { expect, test } from "@playwright/test";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { defineTask, javaTask, player, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { ADMIT_SETTLERS, runColonyTask } from "../shared/colony/colony-tasks";
import { commandPos, frames } from "../shared/harness/bw-helpers";
import { shaderEvidence } from "../shared/capture/shaders";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import { stageCookerySideWork, readCookerySideWork } from "./cookery-work";
import { configureMarketMenu, stageExtraKitchen, startExtraTea, readExtraKitchen } from "./cookery-extra-kitchen";
import { stageExtraYard, readExtraYard } from "./cookery-extra-yard";
import { stageCookeryVisitors, stepCookeryVisitors, summarizeCookeryVisitors } from "./cookery-visitors";
import { dressCookeryGarden } from "./cookery-set";
import { prepareCookeryModels, WINE_FOX_MODELS, applyCookeryLooks } from "./cookery-cast";
import { buildCookeryElevator, readCookeryElevator } from "./cookery-elevator";
import { cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, type PromoPair } from "./promo-kit";

const K = "kaleidoscope_cookery:";
const DISH = K + "baozi_plate";
const DINNER = K + "dongpo_pork";
const PANTRY_SUPPLIES = ["minecraft:wheat", "minecraft:porkchop", K + "green_chili", "minecraft:water_bucket",
  "minecraft:oak_planks", "minecraft:bamboo", "minecraft:iron_ingot", "minecraft:brick", "minecraft:bone", "minecraft:bowl", K + "oil", K + "stockpot_lid"];
const KITCHEN_WORK = new Set(["preparing_food", "chopping_food", "cooking_food", "serving_food", "crafting"]);
const SECONDS = 240;
const VISITORS = 16;

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
      food: at(-17, -4), source: at(-17, -2), store: at(-17, 0), dinner: at(-17, 2), target: at(-4, 1),
      table: at(-14, -2), board: at(-10, 0, 1), stockpot: at(-12, -4, 1), pot: at(-9, -4, 1),
      steamer: at(-6, -4, 1), mill: at(-14, 1),
      stockpotSecond: at(-11, -4, 1), potSecond: at(-8, -4, 1), steamerSecond: at(-5, -4, 1),
    };
    const set = async (p: Vec, block: string) => world.command(server, `setblock ${commandPos(p)} ${block}`);
    const scenery = await dressCookeryGarden(server, o);
    run.note("scenery", scenery);
    for (const key of ["food", "store", "target", "dinner"] as const) await set(cells[key], "minecraft:chest[facing=north]{Items:[]}");
    // Native cellar storage drains the dispatch shelf, so bounded maintain orders
    // keep cooking throughout the long take instead of building a huge initial plan.
    await set({ ...cells.target, y: cells.target.y - 1 }, "minecraft:hopper[facing=down]");
    await set({ ...cells.target, y: cells.target.y - 2 }, "minecraft:barrel[facing=up]");
    for (let slot = 0; slot < 3; slot++) await world.command(server,
      `item replace block ${commandPos(cells.food)} container.${slot} with minecraft:cooked_beef 64`);
    // A small opening service stock lets the inn open before the first cooking batch settles.
    // Dining thereafter draws from the same real products the kitchen keeps making.
    for (const [slot, food] of Array<string>(8).fill(DINNER).entries()) await world.command(server,
      `item replace block ${commandPos(cells.dinner)} container.${slot} with ${food} 16`);
    await set(cells.source, `folkways:creative_supply_box{all:0b,whitelist:${JSON.stringify(PANTRY_SUPPLIES.map(id => "i" + id))}}`);
    await set(cells.table, "minecraft:crafting_table");
    await set(cells.board, K + "chopping_board");
    for (const key of ["stockpot", "pot", "steamer", "stockpotSecond", "potSecond", "steamerSecond"] as const) {
      await set({ ...cells[key], y: cells[key].y - 1 }, K + "stove[facing=north,lit=true]");
      const appliance = key.replace("Second", "");
      // The skillet's north handle opens directly onto the one-cell front operator aisle.
      await set(cells[key], K + appliance + (appliance === "pot" ? "[facing=north]" : ""));
    }
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
    const residents = (await world.entities(server, { types: ["folkways:resident"], limit: 64 })).entities;
    expect(residents).toHaveLength(12);
    run.note("looks", await applyCookeryLooks(server, client, residents.map(one => one.uuid)));
    const cooks = residents.slice(0, 5).map(one => one.uuid);
    const diners = residents.slice(5, 8).map(one => one.uuid);
    const porters = residents.slice(8, 12).map(one => one.uuid);
    const farmers: string[] = [];
    run.note("cast", { cooks, diners, porters, licences: await licenseCrewFast(server,
      residents.map((one, i) => ({ uuid: one.uuid, trades: i < 5 ? ["crafting", "hauling"] : ["hauling"] }))) });
    const diningAreas = scenery.diningAreas.map(area => ({ ...area, food: DINNER }));
    run.note("dining", await server.run(STAGE_DINING, { colony: founded.colony_id, areas: diningAreas }));
    run.note("kitchenRoles", await server.run(KITCHEN_ROLES, { cooks }));
    for (const [i, resident] of residents.entries()) {
      await world.command(server, `data merge entity ${resident.uuid} {Invulnerable:1b}`);
      const post = i < 5 ? at(-14 + i * 2, -2) : i < 8 ? at(-5, -5 + (i - 5), 6) : at(-6 + (i - 8) * 1.5, -8);
      await world.command(server, `tp ${resident.uuid} ${post.x + 0.5} ${post.y} ${post.z + 0.5}`);
    }
    run.note("orders", await setMaintainDemandsFast(server, name, [
      { at: cells.target, item: DISH, count: 48, low: 32 },
      { at: cells.target, item: K + "stir_fried_pork_with_peppers", count: 24, low: 12 },
      { at: cells.target, item: K + "pork_bone_soup", count: 8, low: 4 },
    ]));
    await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
    await world.command(server, `gamemode spectator ${name}`);
    await setFov(client, 56);
    const feet = at(-2, -37, 13);
    const aim = at(-8, -3, 4);
    const look = cameraLookingAt({ x: feet.x + 0.5, y: feet.y + 1.62, z: feet.z + 0.5 },
      { x: aim.x + 0.5, y: aim.y + 0.5, z: aim.z + 0.5 });
    await world.command(server, `tp ${name} ${feet.x + 0.5} ${feet.y} ${feet.z + 0.5} ${look.yaw} ${look.pitch}`);
    await tick.sprint(server, 5);
    await player.place(client, look);
    await screen.dismiss(client);
    await hideHud(client);
    await frames(client, 30);
    // Roll only once the real planner has filled the foreground tables and started kitchen work.
    let ready = false;
    const warmup: any[] = [];
    await setTickRate(server, 40);
    await tick.freeze(server, false);
    try {
      const started = Date.now();
      while (Date.now() - started < 300_000) {
        const state = await sample(server, founded.colony_id, residents.map(one => one.uuid), diningAreas, o);
        warmup.push(state);
        if (warmup.length % 10 === 0) run.note("warmup", warmup);
        if (state.diningAreas.every((area: any) => area.portions >= 4) && state.people.some((one: any) => cooks.includes(one.uuid) && KITCHEN_WORK.has(one.doing))) {
          ready = true; break;
        }
        await sleepMs(1500);
      }
    } finally {
      await tick.freeze(server, true);
      await setTickRate(server, 20);
      run.note("warmup", warmup);
    }
    expect(ready, "real table replenishment and active native kitchen work must be ready before filming").toBe(true);
    await licenseCrewFast(server, diners.map(uuid => ({ uuid, trades: [] })));
    // Rehearsal journeys remain physical: never move an actor off a cabin or landing between tasks.
    await server.run(APPETITE, { uuids: diners });
    await tick.freeze(server, false);
    let seated = false;
    const boarding: any[] = [];
    try {
      const started = Date.now();
      while (Date.now() - started < 60_000) {
        const state = await sample(server, founded.colony_id, residents.map(one => one.uuid), diningAreas, o);
        boarding.push(state);
        if (state.people.some((one: any) => diners.includes(one.uuid) && one.dining && one.seatedAtTable)) { seated = true; break; }
        await sleepMs(500);
      }
    } finally { await tick.freeze(server, true); run.note("boarding", boarding); }
    expect(seated, "the upstairs restaurant must be serving a seated diner before the take").toBe(true);
    // Let the slow roast start naturally before the one-off yard chores enter the scene.
    const admissions = [];
    for (let i = 0; i < 3; i++) admissions.push(await runColonyTask(server, ADMIT_SETTLERS,
      { player: name, colony_id: founded.colony_id, count: 1 }));
    const known = new Set(residents.map(one => one.uuid));
    const marketNewcomers = (await world.entities(server, { types: ["folkways:resident"], limit: 64 })).entities
      .filter(one => !known.has(one.uuid));
    expect(marketNewcomers).toHaveLength(3);
    residents.push(...marketNewcomers);
    const marketCrew = marketNewcomers.map(one => one.uuid);
    run.note("extraKitchen", await stageExtraKitchen(server, name, founded.colony_id, o, marketCrew));
    for (const resident of marketNewcomers) await world.command(server, `data merge entity ${resident.uuid} {Invulnerable:1b}`);
    let roasting = false;
    const marketWarmup: any[] = [];
    await tick.freeze(server, false);
    try {
      const started = Date.now();
      while (Date.now() - started < 180_000) {
        const state = await readExtraKitchen(server, founded.colony_id, o, marketCrew);
        marketWarmup.push(state);
        if (marketWarmup.length % 5 === 0) run.note("marketWarmup", marketWarmup);
        if (state.spit.some((half: any) => half.powered && half.raw.count > 0 && half.ticks > 0)) { roasting = true; break; }
        await sleepMs(500);
      }
    } finally { await tick.freeze(server, true); run.note("marketWarmup", marketWarmup); }
    expect(roasting, "ordinary market orders must load the real rotating spit before filming").toBe(true);
    // The inn now serves its arriving patrons at the banquet. Existing staff keep
    // their packed rations; new arrivals use the normal dining urge rather than restocking road snacks.
    run.note("tavernMenu", await server.run(TAVERN_MENU, { colony: founded.colony_id }));
    for (let i = 0; i < 4; i++) admissions.push(await runColonyTask(server, ADMIT_SETTLERS,
      { player: name, colony_id: founded.colony_id, count: 1 }));
    run.note("crewAdmissions", admissions);
    const staged = new Set(residents.map(one => one.uuid));
    const newcomers = (await world.entities(server, { types: ["folkways:resident"], limit: 64 })).entities
      .filter(one => !staged.has(one.uuid));
    expect(newcomers).toHaveLength(4);
    residents.push(...newcomers);
    farmers.push(newcomers[0].uuid);
    const yardCrew = newcomers.slice(1).map(one => one.uuid);
    // Sixteen independent diners arrive from both sides of the street in staggered waves.
    const existing = new Set(residents.map(one => one.uuid));
    for (let i = 0; i < VISITORS; i++) admissions.push(await runColonyTask(server, ADMIT_SETTLERS,
      { player: name, colony_id: founded.colony_id, count: 1 }));
    const arrivals = (await world.entities(server, { types: ["folkways:resident"], limit: 64 })).entities
      .filter(one => !existing.has(one.uuid));
    expect(arrivals).toHaveLength(VISITORS);
    residents.push(...arrivals);
    const visitorCrew = arrivals.map(one => one.uuid);
    await tick.freeze(server, true);
    run.note("sideWork", await stageCookerySideWork(server, name, founded.colony_id, o, farmers));
    run.note("extraYard", await stageExtraYard(server, name, founded.colony_id, o, yardCrew));
    const visitors = await stageCookeryVisitors(server, name, founded.colony_id, o, visitorCrew);
    run.note("visitors", visitors);
    run.note("crewAdmissions", admissions);
    run.note("teaOrder", await startExtraTea(server, name, founded.colony_id, o));
    for (const resident of newcomers) await world.command(server, `data merge entity ${resident.uuid} {Invulnerable:1b}`);
    run.note("looks", await applyCookeryLooks(server, client, residents.map(one => one.uuid)));
    expect((await world.entities(server, { types: ["minecraft:slime"], limit: 1 })).entities,
      "the courtyard must be free of hostile slimes that obscure and trample its crops").toHaveLength(0);
    await run.shot(client, "composition", { subject: "Ground-floor kitchen, upstairs restaurant and the real elevator share one stationary frame",
      worldState: { cells, diningAreas, cooks, diners }, hard: true });
    const evidence: any[] = [];
    await promoClip(run, client, server, "01-kitchen-dining", {
      subject: "One continuous view of the working kitchen, upstairs diners and residents standing in the moving elevator",
      worldState: { cells, diningAreas, cooks, diners, openingServiceStock: { [DINNER]: 128 },
        kitchenOrder: DISH, kitchenSupplies: PANTRY_SUPPLIES }, seconds: SECONDS,
      during: async () => {
        const started = Date.now();
        let appetiteAt = 3;
        while ((Date.now() - started) / 1000 < SECONDS) {
          const seconds = (Date.now() - started) / 1000;
          if (seconds >= appetiteAt) {
            await server.run(APPETITE, { uuids: [diners[Math.floor((appetiteAt - 3) / 18) % diners.length]] });
            appetiteAt += 18;
          }
          evidence.push({ seconds, ...await sample(server, founded.colony_id,
            residents.map(one => one.uuid), diningAreas, o),
            sideWork: await readCookerySideWork(server, founded.colony_id, o, farmers),
            extraKitchen: await readExtraKitchen(server, founded.colony_id, o, marketCrew),
            extraYard: await readExtraYard(server, founded.colony_id, o, yardCrew),
            visitors: await stepCookeryVisitors(server, founded.colony_id, o, visitorCrew, seconds) });
          if (evidence.length % 30 === 0) run.note("activity", evidence);
          await sleepMs(1000);
        }
      },
    });
    run.note("activity", evidence);
    const visits = summarizeCookeryVisitors(evidence.map(state => ({ seconds: state.seconds, ...state.visitors })), visitorCrew);
    run.note("visits", visits);
    expect(visits.filter(visit => visit.complete).length, "multiple visitors must walk in, finish a real table meal and walk back off camera").toBeGreaterThanOrEqual(2);
    const service = diningAreas.map(area => {
      const samples = evidence.map(state => state.diningAreas.find((one: any) => one.id === area.id));
      let replenished = 0;
      for (let i = 1; i < samples.length; i++) for (let t = 0; t < samples[i].tables.length; t++) {
        const before = samples[i - 1].tables[t].slots;
        replenished += samples[i].tables[t].slots.filter((filled: boolean, slot: number) => filled && !before[slot]).length;
      }
      return { id: area.id, replenished, minimumPortions: Math.min(...samples.map(one => one.portions)) };
    });
    run.note("service", service);
    for (const area of service) expect(area.replenished, `${area.id} must receive fresh servings during the recording`).toBeGreaterThan(0);
    const kitchenSamples = evidence.filter(state => state.people.some((one: any) =>
      [...cooks, ...marketCrew].includes(one.uuid) && KITCHEN_WORK.has(one.doing)));
    run.note("kitchenContinuity", { activeSamples: kitchenSamples.length, samples: evidence.length,
      fraction: kitchenSamples.length / evidence.length });
    expect(kitchenSamples.length / evidence.length, "the native kitchen must remain active throughout most of the shot").toBeGreaterThanOrEqual(0.7);
    run.note("farmProgress", evidence.at(-1)?.sideWork);
    expect(evidence.at(-1)?.sideWork.harvested, "the filmed farmer actually picks or harvests initially mature crops").toBeGreaterThan(0);
    run.note("yardProgress", evidence.at(-1)?.extraYard);
    expect(Math.max(...evidence.map(state => state.extraYard.caught)), "the fisher must land a real catch, independently of any transported raw ingredients").toBeGreaterThan(0);
    expect(Math.max(...evidence.map(state => state.extraYard.sheared)), "the herder must shear a real sheep during the take").toBeGreaterThan(0);
    expect(Math.max(...evidence.map(state => state.extraYard.milked)), "the milk delivery must come from actually milking the staged cow").toBeGreaterThan(0);
    run.note("marketProgress", evidence.at(-1)?.extraKitchen);
    for (const dish of ["sashimi", "barley_tea", "cooked_rabbit"]) {
      expect(Math.max(...evidence.map(state => state.extraKitchen.products[dish])), `the market must actually produce ${dish} during the take`).toBeGreaterThan(0);
    }
    expect(evidence.some(state => state.extraKitchen.spit.some((half: any) => half.powered && half.raw.count > 0 && half.ticks > 0)),
      "the rotating spit must really cook its meat").toBe(true);
    expect(evidence.some(state => (state.extraKitchen.targets[1].items[K + "barley_tea"] ?? 0) > 0),
      "real brewed tea must reach its courtyard service barrel").toBe(true);
    const simultaneous = evidence.filter(state => state.people.some((one: any) => diners.includes(one.uuid) && one.dining && one.seatedAtTable)
      && state.people.some((one: any) => cooks.includes(one.uuid) && KITCHEN_WORK.has(one.doing)));
    run.note("simultaneous", { samples: simultaneous.length, total: evidence.length });
    const occupied = evidence.filter(state => state.lift.passengers.length > 0);
    run.note("standingCapacity", evidence.map(state => ({ seconds: state.seconds,
      capacity: state.lift.capacity, free: state.lift.free })));
    run.note("journeys", occupied.map(state => ({ seconds: state.seconds, contactY: state.lift.contactY, passengers: state.lift.passengers })));
    expect(occupied.length, "the same shot must contain verified standing cabin passengers, excluding nearby residents and diners on chairs").toBeGreaterThanOrEqual(3);
    const heights = occupied.map(state => Number(state.lift.contactY));
    expect(Math.max(...heights) - Math.min(...heights), "occupied elevator must actually travel between floors on camera").toBeGreaterThan(3);
    expect(simultaneous.length, "the shot must actually show seated diners alongside kitchen work").toBeGreaterThanOrEqual(5);
    const after = evidence.at(-1);
    expect(after.people.filter((one: any) => diners.includes(one.uuid) && one.dined >= 0).length,
      "foreground residents really finish table meals and receive the dining experience").toBeGreaterThanOrEqual(2);
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function sample(server: MinecraftServer, colony: string, uuids: string[], areas: Array<{ id: string; tables: Vec[] }>, origin: Vec): Promise<any> {
  const activity = await server.run(ACTIVITY, { colony, uuids, areas });
  const lift = await readCookeryElevator(server, colony, origin);
  const standing = new Set(lift.passengers.map((one: any) => one.uuid));
  return { ...activity, people: activity.people.map((one: any) => ({ ...one,
    inLift: standing.has(one.uuid), seatedAtTable: one.dining && one.mounted && !standing.has(one.uuid),
  })), lift };
}

const STAGE_DINING = defineTask<Record<string, any>, any>({
  name: "folkways.promo.cookery-dining-area", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.terms.ItemFilter",
    "io.github.izakyl.folkways.front.engine.colony.ColonyFront", "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
    "io.github.izakyl.folkways.plugins.person.living.LivingContent", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation",
  ], body: `
var args = Args.of(ctx);
var colony = Colonies.of(ctx.server(), UUID.fromString(args.string("colony"))).orElseThrow();
var front = ColonyFront.of(colony);
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
    var settings = ColonySettings.empty().with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count(1))
        .with(LivingContent.DINING_FOOD.key(), new ColonySettings.Value.Items(List.of(ItemFilter.item(ResourceLocation.parse(area.string("food"))))));
    var zone = front.addZone(ctx.server().overworld(), LivingContent.DINING, cells, settings).orElseThrow();
    zones.add(Map.of("id", area.string("id"), "zone", zone.id().toString(), "servings", 1, "food", area.string("food")));
}
return Map.of("areas", zones);
` }),
});

const APPETITE = defineTask<{ uuids: string[] }, any>({
  name: "folkways.promo.cookery-dining-appetite", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.work.WorkExertion", "io.github.izakyl.folkways.effect.Stats"], body: `
var ids = Args.of(ctx).strings("uuids");
for (var id : ids) {
    var entity = ctx.server().overworld().getEntity(UUID.fromString(id));
    var body = Bodies.of(entity).orElseThrow();
    double appetite = body.effects().value(Stats.APPETITE);
    WorkExertion.performed(body.mob(), 4000 / Math.max(0.1, appetite), 0, 0);
}
return Map.of("stagedAppetite", ids);
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
    people.add(Map.of("uuid", id, "doing", body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).orElse("idle"),
        "dining", entity instanceof io.github.izakyl.folkways.plugins.person.ResidentEntity person && person.leisureMotion().equals("dining"),
        "mounted", entity.isPassenger(), "dined", dined,
        "position", List.of(entity.getX(), entity.getY(), entity.getZ())));
}
int portions = 0;
var areas = new ArrayList<Object>();
for (var rawArea : args.list("areas")) {
    var area = Args.of((Map) rawArea, "area");
    int count = 0;var tables = new ArrayList<Object>();
    for (var raw : area.list("tables")) {
        var at = (Map<?, ?>) raw;
        var pos = new BlockPos(((Number) at.get("x")).intValue(), ((Number) at.get("y")).intValue(), ((Number) at.get("z")).intValue());
        var inventory = DiningTables.inventory(level, pos).orElseThrow();
        var slots = new ArrayList<Boolean>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            boolean filled = !inventory.getItem(i).isEmpty();slots.add(filled);if (filled) count++;
        }
        tables.add(Map.of("position", List.of(pos.getX(), pos.getY(), pos.getZ()), "slots", slots));
    }
    portions += count;areas.add(Map.of("id", area.string("id"), "portions", count, "tables", tables));
}
return Map.of("people", people, "portions", portions, "diningAreas", areas, "time", level.getGameTime());
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
