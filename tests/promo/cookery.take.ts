import { expect, test } from "@playwright/test";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { defineTask, javaTask, player, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { commandPos, frames } from "../shared/harness/bw-helpers";
import { shaderEvidence } from "../shared/capture/shaders";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import { dressCookeryGarden } from "./cookery-set";
import { prepareCookeryModels, WINE_FOX_MODELS, applyCookeryLooks } from "./cookery-cast";
import { buildCookeryElevator, readCookeryElevator } from "./cookery-elevator";
import { cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, type PromoPair } from "./promo-kit";

const K = "kaleidoscope_cookery:";
const DISH = K + "baozi_plate";
const DINNER = K + "dongpo_pork";
const RAW = ["minecraft:wheat", "minecraft:porkchop", K + "green_chili", "minecraft:water_bucket",
  "minecraft:oak_planks", "minecraft:iron_ingot", "minecraft:brick"];
const KITCHEN_WORK = new Set(["preparing_food", "chopping_food", "cooking_food", "serving_food", "crafting"]);
const SECONDS = 50;

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
    await world.command(server, "difficulty normal");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "time set 4500");
    const o = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
    const at = (x: number, z: number, y = 0): Vec => ({ x: o.x + x, y: o.y + y, z: o.z + z });
    const cells = {
      food: at(-16, -3), source: at(-16, -1), store: at(-16, 1), dinner: at(-16, 3), target: at(-5, -4),
      table: at(-12, 0), board: at(-9, 0, 1), stockpot: at(-6, 1, 1), pot: at(-6, 3, 1),
      steamer: at(-6, 5, 1), mill: at(-12, 4),
    };
    const set = async (p: Vec, block: string) => world.command(server, `setblock ${commandPos(p)} ${block}`);
    const scenery = await dressCookeryGarden(server, o);
    run.note("scenery", scenery);
    for (const key of ["food", "store", "target", "dinner"] as const) await set(cells[key], "minecraft:chest[facing=north]{Items:[]}");
    for (let slot = 0; slot < 3; slot++) await world.command(server,
      `item replace block ${commandPos(cells.food)} container.${slot} with minecraft:cooked_beef 64`);
    // The dining spread is set dressing. The kitchen's separate baozi order still uses raw inputs and native work.
    for (let slot = 0; slot < 8; slot++) await world.command(server,
      `item replace block ${commandPos(cells.dinner)} container.${slot} with ${DINNER} 8`);
    await set(cells.source, `folkways:creative_supply_box{all:0b,whitelist:${JSON.stringify(RAW.map(id => "i" + id))}}`);
    await set(cells.table, "minecraft:crafting_table");
    await set(cells.board, K + "chopping_board");
    for (const key of ["stockpot", "pot", "steamer"] as const) {
      await set({ ...cells[key], y: cells[key].y - 1 }, K + "stove[facing=west,lit=true]");
      await set(cells[key], K + key);
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
    run.note("admitted", await admitPromoSettlersFast(server, name, founded.colony_id, 8));
    const residents = (await world.entities(server, { types: ["folkways:resident"], limit: 20 })).entities;
    expect(residents).toHaveLength(8);
    run.note("looks", await applyCookeryLooks(server, client, residents.map(one => one.uuid)));
    const cooks = residents.slice(0, 3).map(one => one.uuid);
    const diners = residents.slice(3, 6).map(one => one.uuid);
    const porters = residents.slice(6).map(one => one.uuid);
    run.note("cast", { cooks, diners, porters, licences: await licenseCrewFast(server,
      residents.map((one, i) => ({ uuid: one.uuid, trades: i < 3 ? ["crafting", "hauling"] : ["hauling"] }))) });
    run.note("dining", await server.run(STAGE_DINING, {
      colony: founded.colony_id, cells: scenery.dining.cells, food: DINNER,
    }));
    for (const [i, resident] of residents.entries()) {
      await world.command(server, `data merge entity ${resident.uuid} {Invulnerable:1b}`);
      const post = i < 3 ? at(-13 + i * 3, -3) : at(-6 + (i - 3) * 1.5, -8);
      await world.command(server, `tp ${resident.uuid} ${post.x + 0.5} ${post.y} ${post.z + 0.5}`);
    }
    run.note("orders", await setMaintainDemandsFast(server, name, [
      { at: cells.target, item: DISH, count: 24, low: 12 },
    ]));
    await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
    await world.command(server, `gamemode spectator ${name}`);
    await setFov(client, 45);
    const feet = at(5, -35, 7);
    const aim = at(-7, 1, 5);
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
        const state = await sample(server, founded.colony_id, residents.map(one => one.uuid), scenery.dining.tables, o);
        warmup.push(state);
        if (state.portions >= 8 && state.people.some((one: any) => cooks.includes(one.uuid) && KITCHEN_WORK.has(one.doing))) {
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
    // Scene blocking only: put the diners near the ground-floor lift entrance and give each an appetite through real work exhaustion.
    for (const [i, uuid] of diners.entries()) {
      const post = at(-6 + i * 1.5, -8);
      await world.command(server, `tp ${uuid} ${post.x + 0.5} ${post.y} ${post.z + 0.5}`);
    }
    await server.run(APPETITE, { uuids: diners });
    await tick.freeze(server, false);
    let seated = false;
    const boarding: any[] = [];
    try {
      const started = Date.now();
      while (Date.now() - started < 60_000) {
        const state = await sample(server, founded.colony_id, residents.map(one => one.uuid), scenery.dining.tables, o);
        boarding.push(state);
        if (state.people.some((one: any) => diners.includes(one.uuid) && one.dining && one.riding)) { seated = true; break; }
        await sleepMs(500);
      }
    } finally { await tick.freeze(server, true); run.note("boarding", boarding); }
    expect(seated, "a diner must genuinely ride to the upstairs restaurant and start eating before the take").toBe(true);
    await run.shot(client, "composition", { subject: "Ground-floor kitchen, upstairs restaurant and the real elevator share one stationary frame",
      worldState: { cells, dining: scenery.dining, cooks, diners }, hard: true });
    const evidence: any[] = [];
    await promoClip(run, client, server, "01-kitchen-dining", {
      subject: "One continuous view of the working kitchen, upstairs diners and residents riding the elevator",
      worldState: { cells, dining: scenery.dining, cooks, diners, stagedTableFood: DINNER,
        kitchenOrder: DISH, kitchenRawSupply: RAW }, seconds: SECONDS,
      during: async () => {
        const started = Date.now();
        let appetiteAt = 3;
        while ((Date.now() - started) / 1000 < SECONDS) {
          const seconds = (Date.now() - started) / 1000;
          if (seconds >= appetiteAt) {
            await server.run(APPETITE, { uuids: [diners[Math.floor((appetiteAt - 3) / 3) % diners.length]] });
            appetiteAt += 3;
          }
          evidence.push({ seconds, ...await sample(server, founded.colony_id,
            residents.map(one => one.uuid), scenery.dining.tables, o) });
          await sleepMs(1000);
        }
      },
    });
    run.note("activity", evidence);
    const simultaneous = evidence.filter(state => state.people.some((one: any) => diners.includes(one.uuid) && one.dining && one.riding)
      && state.people.some((one: any) => cooks.includes(one.uuid) && KITCHEN_WORK.has(one.doing)));
    run.note("simultaneous", { samples: simultaneous.length, total: evidence.length });
    const occupied = evidence.filter(state => state.lift.passengers.length > 0);
    run.note("journeys", occupied.map(state => ({ seconds: state.seconds, contactY: state.lift.contactY, passengers: state.lift.passengers })));
    expect(occupied.length, "the same shot must contain real elevator passengers, excluding diners on chairs").toBeGreaterThanOrEqual(3);
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

async function sample(server: MinecraftServer, colony: string, uuids: string[], tables: Vec[], origin: Vec): Promise<any> {
  return { ...await server.run(ACTIVITY, { colony, uuids, tables }), lift: await readCookeryElevator(server, colony, origin) };
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
var cells = new java.util.LinkedHashSet<BlockPos>();
for (var raw : args.list("cells")) {
    var cell = (Map<?, ?>) raw;
    cells.add(new BlockPos(((Number) cell.get("x")).intValue(), ((Number) cell.get("y")).intValue(), ((Number) cell.get("z")).intValue()));
}
var settings = ColonySettings.empty().with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count(4))
    .with(LivingContent.DINING_FOOD.key(), new ColonySettings.Value.Items(List.of(ItemFilter.item(ResourceLocation.parse(args.string("food"))))));
var zone = front.addZone(ctx.server().overworld(), LivingContent.DINING, cells, settings).orElseThrow();
front.addZone(ctx.server().overworld(), LivingContent.RECREATION, cells, ColonySettings.empty()).orElseThrow();
return Map.of("zone", zone.id().toString(), "servings", 4, "food", args.string("food"));
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
        "riding", entity.isPassenger(), "dined", dined,
        "position", List.of(entity.getX(), entity.getY(), entity.getZ())));
}
int portions = 0;
for (var raw : args.list("tables")) {
    var at = (Map<?, ?>) raw;
    var pos = new BlockPos(((Number) at.get("x")).intValue(), ((Number) at.get("y")).intValue(), ((Number) at.get("z")).intValue());
    var inventory = DiningTables.inventory(level, pos).orElseThrow();
    for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).isEmpty()) portions++;
}
return Map.of("people", people, "portions", portions, "time", level.getGameTime());
` }),
});
