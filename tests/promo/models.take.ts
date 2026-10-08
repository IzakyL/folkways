import type { PromoPair } from "./promo-kit";
import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { foundColonyFast, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import {
  readResidentLooks,
  waitForModelsOnClient,
} from "../e2e/model-folder";
import { MARK_MEMBERS, runColonyTask } from "../e2e/colony-tasks";
import { markZone, residentPositions, settleClient } from "./scene";
import { setMaintainDemandsFast } from "./fast-setup";
import path from "node:path";
import {
  cinematicWorld,
  hideHud,
  launchPromoPair,
  promoClip,
  setFov,
  sleepMs,
  take,
  writeLook,
} from "./promo-kit";
import { shaderEvidence } from "./shaders";
import { BUDGET } from "../shared/budgets";
import { commandPos, tryCommand, frames, safeAction } from "../shared/bw-helpers";

const MODEL_WEIGHT = 600;
const MODEL_ASSETS = path.join(process.cwd(), "tests/promo/models");
const MODEL_NAMES = [
  "wine_fox_wedding", "wine_fox_magical", "wine_fox_tactics",
  "wine_fox_survivor", "wine_fox_hanfu", "wine_fox_momo",
  "wine_fox_nine_tailed", "wine_fox_saint", "wine_fox_matured",
] as const;
const CLIP_SECONDS = 45;
const SWAP_MS = 2500;
const GEOMETRY_PACK_PATH = "assets/folkways/geo/resident_models/wine_fox_wedding.geo.json";

// foundColonyFast rings its founding set with smooth stone walls this far out and this high.
const FOUNDING_PAD = 20;
const WALL_HEIGHT = 3;
const LOGS = "minecraft:oak_log";
const PLANKS = "minecraft:oak_planks";
const STICKS = "minecraft:stick";
const INGOTS = "minecraft:iron_ingot";
const WHEAT = "minecraft:wheat";
const LOG_STORE: Array<[string, number]> = [[LOGS, 8]];
const ORE_STORE: Array<[string, number]> = [["minecraft:raw_iron", 2], ["minecraft:coal", 2]];
// Small orders, emptied again every swap of their stage, keep each trip short: fetch a little, work it, deliver, go again.
const PLANK_ORDER = 8;
const STICK_ORDER = 4;
const INGOT_ORDER = 2;
const RESIDENT_NAME = "Wine Fox";
// The shot walks through one trade at a time: the resident is licensed for that stage's trades only,
// from the swap it starts on (phase 0 is the opening, before the clip rolls), or from the first swap after the stage
// before it has shown its work and left the resident standing about.
type Stage = { name: string; from: number; trades: string[]; doing: string[] };
const STAGES: Stage[] = [
  { name: "fishing", from: 0, trades: ["fishing"], doing: ["folkways:fishing"] },
  { name: "crafting", from: 4, trades: ["crafting", "hauling"], doing: ["folkways:crafting"] },
  { name: "smelting", from: 8, trades: ["crafting", "hauling"],
    doing: ["folkways:loading", "folkways:fueling", "folkways:unloading", "folkways:clearing_station"] },
  { name: "farming", from: 13, trades: ["farming", "hauling"],
    doing: ["folkways:harvesting", "folkways:planting", "folkways:tilling"] },
];
const stageAt = (phase: number) => STAGES.filter((stage) => stage.from <= phase).at(-1)!;
const YARD = { x0: -6, x1: 9, z0: -2, z1: 10 };
const FIELD = { x0: -5, x1: -2, z0: 1, z1: 3 };
// A little pond in front of the bench, fished from its north bank so the resident faces the camera.
const POND = { x0: 3, x1: 5, z0: 8, z1: 9 };
const FISH_SPOT = { dx: 4, dz: 7 };
const FLOWERS: Array<[number, number, string]> = [
  [-2, 8, "minecraft:dandelion"], [3, -1, "minecraft:cornflower"],
  [8, 4, "minecraft:oxeye_daisy"], [-5, 6, "minecraft:allium"], [0, 9, "minecraft:azure_bluet"],
];
const CAMERA_BACK = 10.5;
const CAMERA_UP = 10.5;
const CAMERA_FOV = 40;
const PREROLL_TICKS = 200;
const WORK_WAIT_MS = 30_000;
const LEISURE = new Set(["idle", "folkways:chatting", "folkways:eating", "folkways:sleeping"]);

test("one resident walks about a small colony at work, card up, while cycling through server YSM models", async ({}, testInfo) => {
  test.setTimeout(BUDGET.bench);
  const run = take("models");
  let pair: PromoPair | undefined;
  let server: any;
  let client: any;
  let clientInstance = "";
  try {
    pair = await launchPromoPair("models", {
      models: MODEL_NAMES.map((name) => ({
        source: path.join(MODEL_ASSETS, name), name, manifest: { weight: MODEL_WEIGHT },
      })),
    });
    ({ server, client, clientInstance } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `op ${playerName}`);
    await cinematicWorld(server);
    await tryCommand(server, "gamerule fallDamage false");
    await world.command(server, `gamemode survival ${playerName}`);

    const arrival = await waitForModelsOnClient(client, GEOMETRY_PACK_PATH);
    run.note("models", { names: MODEL_NAMES, weight: MODEL_WEIGHT, ...arrival });

    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    // The founding set is one bed and a food chest; its walls come down so only the tiny colony is left.
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 1 });
    const colony_id = String(founding.evidence.colonyId);
    run.note("colony", { origin, colony_id, residents: founding.residents });
    await clearWalls(server, origin);

    // A fenced yard around the bed. The log store, the bench and the two order chests sit further apart than
    // one spot can reach, so the resident walks the logs over, crafts, and carries planks and sticks off.
    const at = (dx: number, dz: number, dy = 0) => ({ x: origin.x + dx, y: origin.y + dy, z: origin.z + dz });
    const above = (cell: Vec) => ({ ...cell, y: cell.y + 1 });
    const ground = (dx: number, dz: number) => at(dx, dz, -1);
    const logChest = at(-4, 7);
    const bench = at(2, 4);
    const barrel = at(3, 4);
    const plankChest = at(7, 7);
    const stickChest = at(7, 1);
    const furnace = at(3, 1);
    const oreChest = at(2, 1);
    const ingotChest = at(-1, 8);
    await dress(server, [
      // Fence ring with a shut gate facing the camera, which keeps the resident in the yard.
      [at(YARD.x0, YARD.z0), at(YARD.x1, YARD.z0), "minecraft:oak_fence"],
      [at(YARD.x0, YARD.z0), at(YARD.x0, YARD.z1), "minecraft:oak_fence"],
      [at(YARD.x1, YARD.z0), at(YARD.x1, YARD.z1), "minecraft:oak_fence"],
      [at(YARD.x0, YARD.z1), at(YARD.x1, YARD.z1), "minecraft:oak_fence"],
      [at(1, YARD.z1), at(2, YARD.z1), "minecraft:oak_fence_gate[facing=south]"],
      // Paths from the gate to each stop.
      [ground(1, 5), ground(2, YARD.z1 - 1), "minecraft:dirt_path"],
      [ground(-3, 5), ground(6, 5), "minecraft:dirt_path"],
      [ground(6, 1), ground(6, 7), "minecraft:dirt_path"],
      [ground(-3, 5), ground(-3, 7), "minecraft:dirt_path"],
      // Ripe wheat, watered from a ditch along its front edge.
      [ground(FIELD.x0, FIELD.z0), ground(FIELD.x1, FIELD.z1), "minecraft:farmland[moisture=7]"],
      [ground(FIELD.x0, FIELD.z1 + 1), ground(FIELD.x1, FIELD.z1 + 1), "minecraft:water"],
      // The pond, two deep, with a sand bed below its water.
      [ground(POND.x0, POND.z0), ground(POND.x1, POND.z1), "minecraft:water"],
      [at(POND.x0, POND.z0, -2), at(POND.x1, POND.z1, -2), "minecraft:water"],
      [at(POND.x0, POND.z0, -3), at(POND.x1, POND.z1, -3), "minecraft:sand"],
      // Log pile beside the log chest.
      [at(-5, 8), at(-5, 9), "minecraft:oak_log[axis=x]"],
      [at(-4, 9), at(-4, 9), "minecraft:oak_log[axis=x]"],
      [at(-5, 9, 1), at(-5, 9, 1), "minecraft:oak_log[axis=x]"],
      // Hay, a composter and a barrel.
      [at(4, -1), at(6, -1), "minecraft:hay_block"],
      [at(5, -1, 1), at(5, -1, 1), "minecraft:hay_block"],
      [at(-4, -1), at(-4, -1), "minecraft:composter"],
      [barrel, barrel, "minecraft:barrel[facing=up]"],
    ]);
    await world.command(server, `setblock ${commandPos(logChest)} minecraft:chest[facing=east]`);
    await world.command(server, `setblock ${commandPos(bench)} minecraft:crafting_table`);
    await world.command(server, `setblock ${commandPos(plankChest)} minecraft:chest[facing=west]`);
    await world.command(server, `setblock ${commandPos(stickChest)} minecraft:chest[facing=west]`);
    await world.command(server, `setblock ${commandPos(furnace)} minecraft:furnace[facing=south]`);
    await world.command(server, `setblock ${commandPos(ingotChest)} minecraft:chest[facing=south]`);
    await world.command(server, `setblock ${commandPos(oreChest)} minecraft:chest[facing=south]`);
    // Anything standing on the bench and its neighbour takes their tops out of the footings,
    // or the resident climbs up and works from on top of the bench.
    await world.command(server, `setblock ${commandPos(above(bench))} minecraft:potted_red_tulip`);
    for (const top of [barrel, logChest, plankChest, stickChest, furnace, ingotChest, oreChest]) {
      await world.command(server, `setblock ${commandPos(above(top))} minecraft:lantern`);
    }
    for (const post of [at(-1, 5), at(5, 5), at(YARD.x0, YARD.z1), at(YARD.x1, YARD.z1)]) {
      await tryCommand(server, `setblock ${commandPos(post)} minecraft:oak_fence`);
      await tryCommand(server, `setblock ${commandPos(above(post))} minecraft:lantern`);
    }
    for (const [dx, dz, flower] of FLOWERS) {
      await tryCommand(server, `setblock ${commandPos(at(dx, dz))} ${flower}`);
    }
    // The log chest feeds the bench, and the ore and coal wait beside the furnace so smelting is not all fetching.
    for (const [chest, store] of [[logChest, LOG_STORE], [oreChest, ORE_STORE]] as const) {
      let slot = 0;
      for (const [item, stacks] of store) {
        for (let stack = 0; stack < stacks; stack++) {
          await world.command(server, `item replace block ${commandPos(chest)} container.${slot++} with ${item} 64`);
        }
      }
    }
    await tick.sprint(server, 3);
    run.note("members", await runColonyTask(server, MARK_MEMBERS, {
      player: playerName, colony_id,
      cells: [logChest, bench, plankChest, stickChest, furnace, ingotChest, oreChest].flatMap((c) => [c.x, c.y, c.z]),
    }));
    // The ingot order is only filed once the smelting stage starts, or crafting would feed the furnace
    // alongside the bench.
    run.note("demand", await setMaintainDemandsFast(server, playerName, [
      { at: plankChest, item: PLANKS, count: PLANK_ORDER },
      { at: stickChest, item: STICKS, count: STICK_ORDER },
    ]));

    // The wheat becomes a farm zone the way a player makes one, with the book, from beside the field.
    const standBlock = at(-1, 5);
    await world.command(server, `tp ${playerName} ${standBlock.x + 0.5} ${standBlock.y} ${standBlock.z + 0.5} 180 40`);
    await settleClient(server, client);
    const field = { min: ground(FIELD.x0, FIELD.z0), max: ground(FIELD.x1, FIELD.z1) };
    run.note("farm", await markZone(server, client, playerName, { standBlock } as any, field, "farm",
      [{ key: "crop", option: WHEAT }]));
    // Sown only once the zone is marked: standing wheat catches the crosshair meant for the farmland corners.
    await world.command(server, `fill ${commandPos(at(FIELD.x0, FIELD.z0))} ${commandPos(at(FIELD.x1, FIELD.z1))} `
      + `minecraft:wheat[age=7]`);

    const [resident] = await residentPositions(server);
    expect(resident, "the colony must have its one resident").toBeDefined();
    await world.command(server, `data merge entity ${resident.uuid} {CustomName:'"${RESIDENT_NAME}"'}`);
    // The fishing spot, and a rod, hoe and seed up front, so no stage waits on the bench making its tools.
    const fishSpot = at(FISH_SPOT.dx, FISH_SPOT.dz);
    const rig = await runColonyTask(server, RIG_RESIDENT, {
      colony_id, uuid: resident.uuid, fish: fishSpot, trades: stageAt(0).trades,
    });
    run.note("rig", rig);
    // Admission leaves them outside the yard; start them by the pond so the shot does not open on an empty yard.
    await world.command(server, `tp ${resident.uuid} ${fishSpot.x + 0.5} ${fishSpot.y} ${fishSpot.z + 0.5}`);

    // The colony book stays in hand: holding it is what floats the resident's card over their head.
    await world.command(server, `execute if items entity ${playerName} weapon.mainhand folkways:colony_book`);
    await world.command(server, `gamemode spectator ${playerName}`);
    await safeAction(() => frames(client, 10));
    await safeAction(() => screen.dismiss(client));
    run.note("hud", await hideHud(client));

    await tick.sprint(server, PREROLL_TICKS);
    await safeAction(() => tick.freeze(server, false));

    const phases: any[] = [];
    const swap = async (phase: number, stage = stageAt(phase)) => {
      const result = await runColonyTask(server, CYCLE_MODELS, {
        uuid: resident.uuid, name: MODEL_NAMES[phase % MODEL_NAMES.length], phase, trades: stage.trades,
      });
      phases.push({ ...result, stage: stage.name });
      return result;
    };
    // Swaps come every few seconds, too far apart to catch a short job like loading the furnace,
    // so what the resident is doing is also read twice a second through the clip.
    const watched: Array<{ stage: string; doing: string }> = [];
    let stageNow = stageAt(0).name;
    let watching = false;
    const watch = async () => {
      while (watching) {
        const seen = await runColonyTask(server, WATCH_DOING, { uuid: resident.uuid }).catch(() => undefined);
        if (seen) watched.push({ stage: stageNow, doing: String(seen.doing) });
        await sleepMs(WATCH_MS);
      }
    };
    const fill = async (chest: Vec, item: string, count: number) =>
      tryCommand(server, `item replace block ${commandPos(chest)} container.0 with ${item} ${count}`);
    // Only the order's own goods are taken out: whatever else the colony put away there stays.
    const empty = async (chest: Vec, item: string) =>
      tryCommand(server, `data remove block ${commandPos(chest)} Items[{id:"${item}"}]`);

    // Open on the resident already at work, not standing about.
    const until = Date.now() + WORK_WAIT_MS;
    let working = await swap(0);
    while (!stageAt(0).doing.includes(String(working.doing)) && Date.now() < until) {
      await sleepMs(1_000);
      phases.pop();
      working = await swap(0);
    }
    run.note("working", working);
    expect(stageAt(0).doing, `the resident never took up fishing: ${JSON.stringify(working)}`)
      .toContain(String(working.doing));

    // Looking down on the whole yard from above the gate, the sun behind the camera,
    // narrowed so the card stays readable at this distance.
    const centre: Vec = at(1.5, 4.5);
    const eye: Vec = { x: centre.x, y: centre.y + CAMERA_UP, z: centre.z + CAMERA_BACK };
    const look = cameraLookingAt(eye, centre);
    await world.command(server, `tp ${playerName} ${eye.x} ${eye.y} ${eye.z} ${look.yaw} ${look.pitch}`);
    await writeLook(client, look);
    await setFov(client, CAMERA_FOV);
    await safeAction(() => frames(client, 20));

    const gates = await promoClip(run, client, server, "01-models", {
      subject: "Looking down on a small fenced colony: its one resident, Wine Fox, goes through a day's trades in turn: "
        + "fishing at the pond, crafting planks and sticks at the bench for the order chests, loading the furnace with ore "
        + "and coal, then harvesting and replanting the wheat field, the colony book floating their card over their head, "
        + "while they change into the next Wine Fox YSM model every few seconds",
      worldState: { colony_id, resident: resident.uuid, bench, logChest, plankChest, stickChest, furnace, ingotChest, oreChest, field,
        fishSpot, stages: STAGES,
        camera: { eye, centre, fov: CAMERA_FOV, ...look }, models: MODEL_NAMES },
      seconds: CLIP_SECONDS,
      during: async () => {
        watching = true;
        const watcher = watch();
        let current = 0;
        for (let phase = 1; phase < CLIP_SECONDS * 1000 / SWAP_MS; phase++) {
          await sleepMs(SWAP_MS);
          // Loading the furnace is done long before the ore is cooked; waiting out the cook would leave the
          // resident idle on camera, so a finished stage hands over to the next one early.
          const shown = watched.filter((one) => one.stage === STAGES[current].name);
          const finished = shown.some((one) => STAGES[current].doing.includes(one.doing))
            && LEISURE.has(shown.at(-1)?.doing ?? "");
          const entering = current + 1 < STAGES.length && (STAGES[current + 1].from <= phase || finished);
          if (entering) current++;
          const stage = STAGES[current];
          stageNow = stage.name;
          // A withdrawn licence lets the cast in hand run to its bite, which can take half a minute;
          // taking the spot away pulls the rod in at once.
          if (entering && stage.name === "crafting") {
            run.note("fish-spot-closed", await runColonyTask(server, CLOSE_ZONE, { colony_id, zone: rig.zone }));
          }
          if (entering && stage.name === "smelting") {
            // The bench's orders stand met from here on, and the ingot order becomes the only work.
            await fill(plankChest, PLANKS, PLANK_ORDER);
            await fill(stickChest, STICKS, STICK_ORDER);
            run.note("smelt-demand", await setMaintainDemandsFast(server, playerName, [
              { at: ingotChest, item: INGOTS, count: INGOT_ORDER },
            ]));
          }
          if (entering && stage.name === "farming") {
            await fill(ingotChest, INGOTS, INGOT_ORDER);
          }
          await swap(phase, stage);
          // Emptied, the stage's orders fall short again, so another round of fetch, work and deliver starts.
          if (stage.name === "crafting") {
            await empty(plankChest, PLANKS);
            await empty(stickChest, STICKS);
          }
          // Seed they have just sown ripens at once, so there is always wheat to bring in.
          if (stage.name === "farming") {
            await tryCommand(server, `fill ${commandPos(at(FIELD.x0, FIELD.z0))} ${commandPos(at(FIELD.x1, FIELD.z1))} `
              + `minecraft:wheat[age=7] replace minecraft:wheat`);
          }
        }
        watching = false;
        await watcher;
      },
    });

    run.note("shot", { ...gates, phases, watched, looks: await readResidentLooks(server),
      kinds: [...new Set(phases.map((one) => one.doing))] });
    run.note("shaders", shaderEvidence(clientInstance));
    for (const stage of STAGES) {
      const seen = [...phases, ...watched].filter((one) => one.stage === stage.name).map((one) => String(one.doing));
      expect(seen.some((doing) => stage.doing.includes(doing)),
        `the ${stage.name} stage must show its work (${stage.doing.join(", ")}), saw ${seen.join(", ")}`).toBe(true);
    }
    const worked = phases.filter((one) => !LEISURE.has(String(one.doing)));
    expect(worked.length, `the resident must be at work for most of the shot: ${JSON.stringify(phases)}`)
      .toBeGreaterThan(phases.length / 2);
    expect(new Set(phases.map((one) => `${one.x},${one.z}`)).size, "the resident must walk about the yard")
      .toBeGreaterThanOrEqual(3);
    expect(new Set(phases.map((one) => one.look)).size, "every swap must land on the next model")
      .toBe(Math.min(phases.length, MODEL_NAMES.length));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function clearWalls(server: any, origin: Vec) {
  const [x0, x1] = [origin.x - FOUNDING_PAD, origin.x + FOUNDING_PAD];
  const [z0, z1] = [origin.z - FOUNDING_PAD, origin.z + FOUNDING_PAD];
  const top = origin.y + WALL_HEIGHT;
  for (const [ax, az, bx, bz] of [[x0, z0, x1, z0], [x0, z1, x1, z1], [x0, z0, x0, z1], [x1, z0, x1, z1]]) {
    await world.command(server, `fill ${ax} ${origin.y} ${az} ${bx} ${top} ${bz} minecraft:air`);
  }
}

async function dress(server: any, fills: Array<[Vec, Vec, string]>) {
  for (const [from, to, block] of fills) {
    await tryCommand(server, `fill ${commandPos(from)} ${commandPos(to)} ${block}`);
  }
}

const CYCLE_MODELS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cycle-models",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
      "net.minecraft.resources.*",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var level=ctx.server().overworld();
var args=Args.of(ctx);
int phase=(int) args.integer("phase");
var resident=(ResidentEntity)level.getEntity(UUID.fromString(args.string("uuid")));
String prefix="model/"+args.string("name")+"/";
var id=ResidentLooks.pool(level.registryAccess()).keySet().stream()
    .filter(key -> key.getNamespace().equals("folkways") && key.getPath().startsWith(prefix))
    .sorted(Comparator.comparing(Object::toString)).findFirst()
    .orElseThrow(() -> new IllegalStateException("Missing YSM model "+prefix));
resident.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,id));
var trades=args.list("trades").stream().map(String::valueOf).toList();
var licences=Bodies.of(resident).orElseThrow().licences();
for(var vocation:Vocations.all()) licences.trade(vocation).ifPresent(trade->
    licences.allow(trade,trades.contains(vocation.id().getPath())));
var doing=Bodies.of(resident).flatMap(body->body.doing())
    .map(one->one.toward().orElse(one.what()).toString()).orElse("idle");
return Sync.stamp(Map.of("ok",true,"phase",phase,"look",resident.lookId().orElseThrow().location().toString(),
    "doing",doing,"x",resident.getBlockX(),"z",resident.getBlockZ()));
`,
  }),
});

const RIG_RESIDENT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.rig-resident",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.front.engine.colony.ZoneDrafts",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.*",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var fish=(Map<?,?>)args.value("fish");
var spot=new BlockPos(((Number)fish.get("x")).intValue(),((Number)fish.get("y")).intValue(),((Number)fish.get("z")).intValue());
var zone=ZoneDrafts.zone(level,colony,ResourceLocation.parse("folkways:fish"),spot,spot,ColonySettings.empty())
    .orElseThrow(()->new IllegalStateException("No fish zone at "+spot));
var body=Bodies.of(level.getEntity(UUID.fromString(args.string("uuid")))).orElseThrow();
var trades=args.list("trades").stream().map(String::valueOf).toList();
for(var vocation:Vocations.all()) body.licences().trade(vocation).ifPresent(trade->
    body.licences().allow(trade,trades.contains(vocation.id().getPath())));
body.pack().insert(new ItemStack(Items.FISHING_ROD));
body.pack().insert(new ItemStack(Items.WOODEN_HOE));
body.pack().insert(new ItemStack(Items.WHEAT_SEEDS,16));
return Sync.stamp(Map.of("ok",true,"zone",zone.id().toString(),"trades",trades));
`,
  }),
});

const CLOSE_ZONE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.close-zone",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    ],
    body: `
var args=Args.of(ctx);
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var removed=ColonyFront.of(colony).erase(UUID.fromString(args.string("zone")));
return Sync.stamp(Map.of("ok",true,"removed",removed));
`,
  }),
});

const WATCH_MS = 500;

const WATCH_DOING = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.watch-doing",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.resident.body.Bodies"],
    body: `
var entity=ctx.server().overworld().getEntity(UUID.fromString(Args.of(ctx).string("uuid")));
var doing=Bodies.of(entity).flatMap(body->body.doing())
    .map(one->one.toward().orElse(one.what()).toString()).orElse("idle");
return Sync.stamp(Map.of("ok",true,"doing",doing));
`,
  }),
});
