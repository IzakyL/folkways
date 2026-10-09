import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, reflect, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement, panelElements } from "../ldlib2";
import { waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { BUDGET } from "../shared/budgets";
import { frames, safeAction, softly, tryCommand } from "../shared/bw-helpers";
import { runColonyTask } from "../e2e/colony-tasks";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast } from "./fast-setup";
import {
  cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, writeLook,
  type Look, type PromoPair,
} from "./promo-kit";
import { shaderEvidence } from "./shaders";

// One shot of a small homestead. The player only looks over the workforce page, where each resident is allowed one
// trade or another, and closes it. Nobody has a tool for their trade: the crafter makes a hoe for each farmer and a
// rod for the fisher from the planks, sticks and string in store, and they go to work with them, while one resident
// stops to eat and another, worn out, goes to bed.

const EYE = 1.62;
// Each resident's trades, in the order they are cast. Everyone hauls too: a resident's own tools and meals come into
// its pack by its own hands, under hauling, so one not allowed to haul is never brought either. The hungry one farms
// as well, so it goes on to the field once it has eaten.
const CREW: Array<{ role: string; trades: string[]; post: [number, number] }> = [
  { role: "crafter", trades: ["crafting", "hauling"], post: [-7, -4] },
  { role: "farmer", trades: ["farming", "hauling"], post: [-3, -5] },
  { role: "fisher", trades: ["fishing", "hauling"], post: [5, -7] },
  { role: "hungry", trades: ["farming", "hauling"], post: [-1, -5] },
  { role: "tired", trades: ["hauling"], post: [2, -3] },
];
// Food and tiredness per role: the hungry one below the mark where work stops for a meal (14), the tired one past the
// mark where it stops for bed (800); everyone else well clear of both, so only those two break off on camera.
const VITALS: Record<string, { food: number; tired: number }> = {
  crafter: { food: 19, tired: 100 },
  farmer: { food: 19, tired: 150 },
  fisher: { food: 18, tired: 120 },
  hungry: { food: 8, tired: 200 },
  tired: { food: 19, tired: 1000 },
};
const STORE: Array<[string, number]> = [
  ["minecraft:oak_planks", 16], ["minecraft:stick", 16], ["minecraft:string", 8],
];
const FOOD = "minecraft:bread";
// The panel is browsed with the world held still, so nobody starts on a tool before the page has been seen.
const BROWSE_BEAT_MS = 1_000;
const CURSOR_MS = 700;
// The world runs faster while the work is filmed; the cut speeds it up again.
const WORK_RATE = 40;
const POLL_MS = 1_000;
const WORK_TIMEOUT_MS = 150_000;
// Once everything has happened, the homestead goes on at its own pace for a while, for the shot to end on.
const HOLD_AFTER_MS = 8_000;
const FOV = 62;
// The panel drawn larger than the default, so the page reads in a 1080p frame.
const GUI_SCALE = 3;
const PRIORITY_CELL = (vocation: string, uuid: string) => `folkways.resident.vocation.folkways.${vocation}.${uuid}`;

type Box = { min: Vec; max: Vec };
type Spot = { feet: Vec; aim: Vec };
type Mark = (name: string) => void;

test("residents work by their licences, fetch their own tools, eat and sleep", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("household");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("household");
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    // The wheat grows fast enough to see it come up behind the farmers.
    await tryCommand(server, "gamerule randomTickSpeed 300");
    const o: Vec = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
    const at = (x: number, z: number, dy = 0): Vec => ({ x: o.x + x, y: o.y + dy, z: o.z + z });
    const box = (x1: number, z1: number, x2: number, z2: number, dy = -1): Box => ({ min: at(x1, z1, dy), max: at(x2, z2, dy) });

    // Workshop on the left, the field in the middle, the pond on the right, and the beds and the food at the front
    // right: all of it in one fixed frame.
    const set = {
      table: at(-9, -5), store: at(-9, -3), spare: at(-9, -7),
      field: box(-6, -12, 0, -8), ditches: [box(-6, -13, 0, -13), box(-6, -7, 0, -7)], barn: at(1, -6),
      pond: box(3, -14, 8, -11, -1), bank: box(4, -10, 7, -10), catch: at(9, -9),
      beds: [at(4, -3), at(4, -1), at(7, -3), at(7, -1), at(-4, -1)], larder: at(9, -2),
    };
    const spot: Spot = { feet: at(0, 4, 7), aim: at(0, -6, 0) };

    run.note("ground", await stageGround(server, o, set, spot));
    await world.command(server, `tp ${player} ${o.x + 0.5} ${o.y} ${o.z + 0.5} 180 20`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 10);
    const founded = await foundPromoColonyFast(server, player, [
      set.table, set.store, set.spare, set.barn, set.catch, set.larder, ...set.beds,
    ]);
    const colony_id = founded.colony_id;
    run.note("founded", founded);
    run.note("admitted", await admitPromoSettlersFast(server, player, colony_id, CREW.length));

    await world.command(server, `gamemode survival ${player}`);
    await setFov(client, FOV);
    run.note("gui_scale", await setGuiScale(client, GUI_SCALE));
    await safeAction(() => screen.dismiss(client));
    let look = await standAt(server, client, player, spot);

    // From here the world holds still until the panel closes: the crew are cast, the field and the fishery are laid
    // out, and nobody moves while the page is on screen.
    await tick.freeze(server, true);
    const crew = await castCrew(server, at);
    run.note("crew", crew);
    run.note("zones", await runColonyTask(server, MAKE_ZONES, { colony_id, zones: [
      { kind: "folkways:farm", ...set.field, crop: "minecraft:wheat" },
      { kind: "folkways:fish", ...set.bank },
    ] }));
    run.note("vitals", await runColonyTask(server, SET_VITALS, { colony_id, vitals: crew.map((one) => ({
      uuid: one.uuid, ...VITALS[one.role],
    })) }));
    // The chat lines and the join toasts fade before the shot rolls.
    await sleepMs(12_000);

    const byRole = (role: string) => crew.find((one) => one.role === role)!.uuid;
    const shot = await film(run, client, server, "01-household", {
      subject: "The player opens the colony book on the workforce page and points over each resident's allowed trade, "
        + "then closes it on a fixed view of a homestead: the crafter makes hoes and a fishing rod from planks, sticks "
        + "and string and hands them out, the farmers till, sow and harvest wheat, the fisher fishes from the bank, "
        + "one resident stops to eat and another goes to bed",
      during: async (mark) => {
        // The clip lets the world run as it starts recording: it is held again until the panel closes.
        await tick.freeze(server, true);
        await sleepMs(1_000);
        mark("open");
        const browse = await browseWorkforce(client, crew, mark);
        await sleepMs(BROWSE_BEAT_MS);
        await safeAction(() => screen.dismiss(client));
        await safeAction(() => input.move(client, { x: 0, y: 0 }));
        mark("closed");
        await hideHud(client);
        await writeLook(client, look);
        await tick.freeze(server, false);
        await setTickRate(server, WORK_RATE);
        const watched = await watch(server, colony_id, mark, {
          farmers: [byRole("farmer"), byRole("hungry")], fisher: byRole("fisher"),
          hungry: byRole("hungry"), tired: byRole("tired"), field: lift(set.field),
        });
        await setTickRate(server, 20);
        await sleepMs(HOLD_AFTER_MS);
        await hideHud(client);
        return { browse, watched };
      },
    });
    run.note("shot", shot);
    const seen = shot.watched.seen;
    expect(seen.hoes, "every farmer must be handed a hoe on camera").toBe(true);
    expect(seen.rod, "the fisher must be handed a rod on camera").toBe(true);
    expect(seen.ate, "the hungry resident must eat on camera").toBe(true);
    expect(seen.slept, "the tired resident must go to bed on camera").toBe(true);
    expect(seen.sown, "the field must be sown on camera").toBe(true);
    expect(seen.fishing, "the fisher must fish on camera").toBe(true);
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// One clip, with the moments it passes through stamped in seconds from its start, for the cut to time its pieces by.
async function film(run: any, client: any, server: any, name: string,
  options: { subject: string; during: (mark: Mark) => Promise<any> }) {
  const marks: Record<string, number> = {};
  let started = 0;
  let result: any;
  const gates = await promoClip(run, client, server, name, {
    subject: options.subject,
    worldState: {},
    seconds: 1,
    during: async () => {
      started = Date.now();
      result = await options.during((mark) => { marks[mark] = Math.round((Date.now() - started) / 100) / 10; });
      marks.end = Math.round((Date.now() - started) / 100) / 10;
    },
  });
  return { ...result, marks, gates };
}

// Opens the panel with the book, turns to the workforce page, and rests the cursor on each resident's allowed trade.
async function browseWorkforce(client: MinecraftClient, crew: Array<{ uuid: string; role: string; trades: string[] }>,
  mark: Mark) {
  const evidence: any = { cells: [] };
  evidence.open = await rightClick(client);
  await softly(() => screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 10_000 }));
  await safeAction(() => frames(client, 10));
  mark("panel");
  await sleepMs(BROWSE_BEAT_MS);
  evidence.tab = await softly(() => clickElement(client, { id: "folkways.tab.residents" }, { scrollIntoView: true }));
  await safeAction(() => frames(client, 10));
  mark("workforce");
  let cursor = { x: 0, y: 0 };
  await sleepMs(BROWSE_BEAT_MS);
  for (const one of crew) {
    // The page names each cell by the trade and the head of the resident's id.
    const id = PRIORITY_CELL(one.trades[0], "");
    const elements = await panelElements(client).catch(() => []);
    const cell = elements.find((element: any) => typeof element.id === "string" && element.id.startsWith(id)
      && one.uuid.startsWith(element.id.slice(id.length)));
    if (!cell) {
      evidence.cells.push({ id, found: false });
      continue;
    }
    const to = { x: Math.round(cell.x + cell.width / 2), y: Math.round(cell.y + cell.height / 2) };
    cursor = await glideCursor(client, cursor, to, CURSOR_MS);
    evidence.cells.push({ id, found: true, at: to });
    await sleepMs(BROWSE_BEAT_MS);
  }
  return evidence;
}

async function setGuiScale(client: MinecraftClient, scale: number) {
  const integer = await reflect.invoke(client, {
    target: { kind: "static", className: "java.lang.Integer" },
    method: "valueOf", args: [scale], argTypes: ["int"], returnHandle: true,
  });
  await reflect.invoke(client, {
    target: { kind: "static", className: "net.minecraft.client.Minecraft" },
    path: "getInstance().options.guiScale()", method: "set",
    args: [{ $handle: integer.handle }], argTypes: ["java.lang.Object"],
  });
  await reflect.invoke(client, {
    target: { kind: "static", className: "net.minecraft.client.Minecraft" },
    path: "getInstance()", method: "resizeDisplay",
  });
  await safeAction(() => frames(client, 8));
  return scale;
}

async function glideCursor(client: MinecraftClient, from: { x: number; y: number }, to: { x: number; y: number }, ms: number) {
  const steps = Math.max(1, Math.round(ms / 30));
  for (let step = 1; step <= steps; step++) {
    const t = step / steps;
    const eased = t * t * (3 - 2 * t);
    await safeAction(() => input.move(client, {
      x: Math.round(from.x + (to.x - from.x) * eased), y: Math.round(from.y + (to.y - from.y) * eased),
    }));
    await sleepMs(30);
  }
  return to;
}

// Polls the crew until everything the shot is for has happened on camera: each farmer holds a hoe and the fisher a
// rod, the hungry one has eaten, the tired one has slept, wheat is up in the field and the fisher has cast.
async function watch(server: any, colony_id: string, mark: Mark,
  who: { farmers: string[]; fisher: string; hungry: string; tired: string; field: Box }) {
  const seen = { hoes: false, rod: false, ate: false, slept: false, sown: false, fishing: false };
  const readings: any[] = [];
  const started = Date.now();
  while (Date.now() - started < WORK_TIMEOUT_MS) {
    await sleepMs(POLL_MS);
    const read: any = await runColonyTask(server, READ_CREW, { colony_id, field: who.field }).catch((error) => ({ error: String(error) }));
    if (read.error) {
      readings.push(read);
      continue;
    }
    const crew = new Map<string, any>((read.crew as any[]).map((one) => [one.id, one]));
    const was = { ...seen };
    seen.hoes ||= who.farmers.every((id) => crew.get(id)?.tools.some((tool: string) => tool.endsWith("_hoe")));
    seen.rod ||= crew.get(who.fisher)?.tools.includes("minecraft:fishing_rod") ?? false;
    seen.ate ||= crew.get(who.hungry)?.what === "folkways:eating" || (crew.get(who.hungry)?.food ?? 0) > 14;
    seen.slept ||= crew.get(who.tired)?.what === "folkways:sleeping";
    seen.sown ||= read.wheat > 0;
    seen.fishing ||= String(crew.get(who.fisher)?.what ?? "").includes("fish");
    for (const key of Object.keys(seen) as Array<keyof typeof seen>) {
      if (seen[key] && !was[key]) mark(key);
    }
    if (readings.length % 20 === 5) {
      readings.push({ placards: await runColonyTask(server, PLACARDS, { colony_id }).catch((error) => String(error)) });
      readings.push({ heartbeat: await runColonyTask(server, HEARTBEAT, { colony_id }).catch((error) => String(error)) });
    }
    readings.push({ t: Math.round((Date.now() - started) / 100) / 10, wheat: read.wheat,
      crew: (read.crew as any[]).map((one) => `${one.id.slice(0, 4)}:${one.what}:${one.tools.join("+")}:${one.food}`) });
    if (Object.values(seen).every(Boolean)) {
      mark("done");
      return { reached: true, seen, readings };
    }
  }
  return { reached: false, seen, readings };
}

async function standAt(server: any, client: MinecraftClient, player: string, spot: Spot): Promise<Look> {
  const look = lookAt(spot, spot.aim);
  await world.command(server, `tp ${player} ${spot.feet.x + 0.5} ${spot.feet.y} ${spot.feet.z + 0.5} ${look.yaw} ${look.pitch}`);
  await tick.sprint(server, 10);
  await writeLook(client, look);
  await safeAction(() => frames(client, 30));
  await sleepMs(2_000);
  return look;
}

function lookAt(spot: Spot, target: Vec): Look {
  const eye = { x: spot.feet.x + 0.5, y: spot.feet.y + EYE, z: spot.feet.z + 0.5 };
  return cameraLookingAt(eye, { x: target.x + 0.5, y: target.y + 0.5, z: target.z + 0.5 });
}

function lift(plot: Box): Box {
  return { min: { ...plot.min, y: plot.min.y + 1 }, max: { ...plot.max, y: plot.max.y + 1 } };
}

async function rightClick(client: MinecraftClient) {
  await safeAction(() => input.button(client, { button: "right", action: "press" }));
  await sleepMs(60);
  return safeAction(() => input.button(client, { button: "right", action: "release" }));
}

async function stageGround(server: any, o: Vec, set: any, spot: Spot) {
  const fill = (a: Vec, b: Vec, block: string) =>
    world.command(server, `fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} ${block}`);
  // Clearing ground that is already clear fills nothing, which the command reports as a failure.
  const clear = (a: Vec, b: Vec, block: string) =>
    tryCommand(server, `fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} ${block}`);
  const setblock = (p: Vec, block: string) => world.command(server, `setblock ${p.x} ${p.y} ${p.z} ${block}`);
  await world.command(server, `forceload add ${o.x - 40} ${o.z - 40} ${o.x + 40} ${o.z + 32}`);
  for (let y = o.y; y <= o.y + 24; y += 4) {
    await clear({ x: o.x - 40, y, z: o.z - 40 }, { x: o.x + 40, y: y + 3, z: o.z + 32 }, "minecraft:air");
  }
  await clear({ x: o.x - 40, y: o.y - 1, z: o.z - 40 }, { x: o.x + 40, y: o.y - 1, z: o.z + 32 }, "minecraft:grass_block");
  await tryCommand(server, `kill @e[type=!minecraft:player,x=${o.x},y=${o.y},z=${o.z},distance=..80]`);

  // The workshop: a table, the store of planks, sticks and string beside it, and a spare chest for what is made.
  await fill({ x: set.table.x - 1, y: o.y - 1, z: set.spare.z - 1 }, { x: set.table.x + 1, y: o.y - 1, z: set.store.z + 1 },
    "minecraft:coarse_dirt");
  await setblock(set.table, "minecraft:crafting_table");
  const stock = STORE.map(([id, count], slot) => `{Slot:${slot}b,id:\"${id}\",count:${count}}`).join(",");
  await setblock(set.store, `minecraft:chest[facing=east]{Items:[${stock}]}`);
  await setblock(set.spare, "minecraft:chest[facing=east]{Items:[]}");

  // The field, watered from a ditch along each long side, and its seed store at the corner.
  for (const ditch of set.ditches) {
    await fill(ditch.min, ditch.max, "minecraft:water");
  }
  await setblock(set.barn, "minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:wheat_seeds\",count:64}]}");

  // The pond, two deep, the bank the fisher stands on in front of it, and a chest for the catch.
  await fill({ ...set.pond.min, y: set.pond.min.y - 1 }, set.pond.max, "minecraft:water");
  await fill(set.bank.min, set.bank.max, "minecraft:sand");
  await setblock(set.catch, "minecraft:chest[facing=west]{Items:[]}");

  // The beds, and the bread they eat from beside them.
  for (const foot of set.beds) {
    await setblock(foot, "minecraft:red_bed[part=foot,facing=east]");
    await setblock({ ...foot, x: foot.x + 1 }, "minecraft:red_bed[part=head,facing=east]");
  }
  await setblock(set.larder, `minecraft:chest[facing=west]{Items:[{Slot:0b,id:\"${FOOD}\",count:64}]}`);

  // The camera stands on a barrier, unseen, above the homestead.
  await setblock({ ...spot.feet, y: spot.feet.y - 1 }, "minecraft:barrier");
  await tick.sprint(server, 5);
  return { origin: o };
}

// Each resident to its post, allowed its own trades only and holding none of the tools for them.
async function castCrew(server: any, at: (x: number, z: number, dy?: number) => Vec) {
  const standing = await world.entities(server, { dimension: "minecraft:overworld", limit: 100, types: ["folkways:resident"] });
  const uuids: string[] = standing.entities.map((one: any) => String(one.uuid));
  if (uuids.length < CREW.length) throw new Error(`only ${uuids.length} residents to cast`);
  const crew = CREW.map((one, i) => ({ uuid: uuids[i], role: one.role, trades: one.trades }));
  for (const [i, one] of CREW.entries()) {
    const post = at(one.post[0], one.post[1]);
    await world.command(server, `tp ${uuids[i]} ${post.x + 0.5} ${post.y} ${post.z + 0.5} 180 0`);
  }
  const licensed = await licenseCrewFast(server, crew.map(({ uuid, trades }) => ({ uuid, trades })));
  const emptied = await runColonyTask(server, EMPTY_PACKS, { uuids: crew.map((one) => one.uuid) });
  return crew.map((one) => ({ ...one, licensed: licensed.licensed.find((l: any) => l.uuid === one.uuid)?.trades, emptied }));
}

const MAKE_ZONES = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.household-zones",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.*",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var zones=new ArrayList<Object>();
for(var raw:args.list("zones")){
  var z=(Map<?,?>)raw;var min=(Map<?,?>)z.get("min");var max=(Map<?,?>)z.get("max");
  var chosen=ColonySettings.empty();
  if(z.containsKey("crop"))chosen=chosen.with("crop",new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(z.get("crop")))));
  var zone=ZoneDrafts.zone(level,colony,ResourceLocation.parse(String.valueOf(z.get("kind"))),
    new BlockPos(((Number)min.get("x")).intValue(),((Number)min.get("y")).intValue(),((Number)min.get("z")).intValue()),
    new BlockPos(((Number)max.get("x")).intValue(),((Number)max.get("y")).intValue(),((Number)max.get("z")).intValue()),chosen)
    .orElseThrow(()->new IllegalStateException("Zone refused: "+z));
  zones.add(zone.id().toString());
}
return Sync.stamp(Map.of("ok",true,"zones",zones));
`,
  }),
});

// A fresh resident may arrive with something in hand: the shot is about the colony making each tool, so packs start empty.
const EMPTY_PACKS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.household-empty",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);var out=new ArrayList<Object>();
for(var raw:args.list("uuids")){
  var body=Bodies.of(level.getEntity(UUID.fromString(String.valueOf(raw)))).orElseThrow();
  var had=new ArrayList<String>();
  for(var stack:body.pack().contents())if(!stack.isEmpty())had.add(BuiltInRegistries.ITEM.getKey(stack.getItem())+"x"+stack.getCount());
  body.pack().clearContent();
  out.add(Map.of("uuid",String.valueOf(raw),"had",had));
}
return Sync.stamp(Map.of("ok",true,"emptied",out));
`,
  }),
});

// Sets each resident's food and tiredness, the same way the vitals of the tour are spread out.
const SET_VITALS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.household-vitals",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent",
      "java.lang.reflect.AccessibleObject",
      "java.lang.reflect.Constructor",
    ],
    body: `
var server=ctx.server();var args=Args.of(ctx);
Colony colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
Object presence=colony.service(LivingContent.ID,Object.class).orElseThrow();
@SuppressWarnings("unchecked")
Map<UUID,Object> bellies=(Map<UUID,Object>)open(presence.getClass().getDeclaredField("bellies")).get(presence);
@SuppressWarnings("unchecked")
Map<UUID,Object> wear=(Map<UUID,Object>)open(presence.getClass().getDeclaredField("wear")).get(presence);
Class<?> hungerType=Class.forName("io.github.izakyl.folkways.plugins.person.living.Hunger");
Class<?> restType=Class.forName("io.github.izakyl.folkways.plugins.person.living.Rest");
Constructor<?> newHunger=open(hungerType.getDeclaredConstructor());
Constructor<?> newRest=open(restType.getDeclaredConstructor());
var set=new ArrayList<Object>();
for(var raw:args.list("vitals")){
  var one=(Map<?,?>)raw;var id=UUID.fromString(String.valueOf(one.get("uuid")));
  Object belly=bellies.computeIfAbsent(id,key->make(newHunger));
  open(hungerType.getDeclaredField("foodLevel")).setInt(belly,((Number)one.get("food")).intValue());
  open(hungerType.getDeclaredField("saturationLevel")).setFloat(belly,0.0F);
  Object rest=wear.computeIfAbsent(id,key->make(newRest));
  open(restType.getDeclaredField("tired")).setDouble(rest,((Number)one.get("tired")).doubleValue());
  set.add(Map.of("uuid",id.toString(),"food",one.get("food"),"tired",one.get("tired")));
}
return Sync.stamp(Map.of("ok",true,"set",set));
`,
    members: `
private static Object make(Constructor<?> constructor) {
    try {
        return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
    }
}

private static <T extends AccessibleObject> T open(T member) {
    member.setAccessible(true);
    return member;
}
`,
  }),
});

// What each resident is doing, the tools in its pack and how fed it is, and how much wheat stands in the field.
const READ_CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.household-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent",
      "java.lang.reflect.AccessibleObject",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.world.item.TieredItem",
      "net.minecraft.world.item.FishingRodItem",
      "net.minecraft.world.level.block.Blocks",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
Colony colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
Object presence=colony.service(LivingContent.ID,Object.class).orElseThrow();
@SuppressWarnings("unchecked")
Map<UUID,Object> bellies=(Map<UUID,Object>)open(presence.getClass().getDeclaredField("bellies")).get(presence);
var foodLevel=open(Class.forName("io.github.izakyl.folkways.plugins.person.living.Hunger").getDeclaredField("foodLevel"));
var crew=new ArrayList<Object>();
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());if(entity==null)continue;
  var body=Bodies.of(entity);
  var what=body.flatMap(b->b.doing()).map(doing->doing.toward().orElse(doing.what()).toString()).orElse("idle");
  var tools=new ArrayList<String>();
  if(body.isPresent())for(var stack:body.get().pack().contents())
    if(stack.getItem() instanceof TieredItem||stack.getItem() instanceof FishingRodItem)tools.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
  var belly=bellies.get(resident.id());
  int food=belly==null?-1:foodLevel.getInt(belly);
  crew.add(Map.of("id",resident.id().toString(),"what",what,"tools",tools,"food",food));
}
var f=(Map<?,?>)args.value("field");var lo=(Map<?,?>)f.get("min");var hi=(Map<?,?>)f.get("max");int wheat=0;
for(var p:BlockPos.betweenClosed(((Number)lo.get("x")).intValue(),((Number)lo.get("y")).intValue(),((Number)lo.get("z")).intValue(),
    ((Number)hi.get("x")).intValue(),((Number)hi.get("y")).intValue(),((Number)hi.get("z")).intValue()))
  if(level.getBlockState(p).is(Blocks.WHEAT))wheat++;
return Map.of("ok",true,"crew",crew,"wheat",wheat);
`,
    members: `
private static <T extends AccessibleObject> T open(T member) {
    member.setAccessible(true);
    return member;
}
`,
  }),
});

// DIAGNOSTIC: what every placard in the colony says.
const PLACARDS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.household-placards",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.api.Facing",
      "io.github.izakyl.folkways.front.api.notice.Line",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var view=colony.view(level);var out=new ArrayList<Object>();
for(var facing:Facing.all(colony))for(var placard:facing.placards(view)){
  var lines=new ArrayList<Object>();
  for(var line:placard.lines())lines.add(switch(line){
    case Line.Said said->said.notice().component().getString();
    case Line.Literal literal->literal.text();
    default->line.toString();
  });
  out.add(Map.of("id",placard.id().toString(),"lines",lines));
}
return Map.of("ok",true,"placards",out);
`,
  }),
});

// DIAGNOSTIC: the colony's labor readout, as its stall log prints it, and everything in each resident's pack.
const HEARTBEAT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.household-heartbeat",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceKey",
      "net.minecraft.world.level.Level",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var id=UUID.fromString(args.string("colony_id"));
var colony=ColonyGround.of(server,id).orElseThrow();
var labor=Class.forName("io.github.izakyl.folkways.core.engine.labor.Labor");
var site=labor.getDeclaredMethod("site",UUID.class,ResourceKey.class);site.setAccessible(true);
var found=(Optional<?>)site.invoke(null,id,Level.OVERWORLD);
String beat="no labor site";
if(found.isPresent()){var heart=found.get().getClass().getDeclaredMethod("heartbeat");heart.setAccessible(true);beat=String.valueOf(heart.invoke(found.get()));}
var packs=new ArrayList<Object>();
for(var resident:colony.residents()){
  var body=Bodies.of(level.getEntity(resident.id()));if(body.isEmpty())continue;
  var had=new ArrayList<String>();
  for(var stack:body.get().pack().contents())if(!stack.isEmpty())had.add(BuiltInRegistries.ITEM.getKey(stack.getItem())+"x"+stack.getCount());
  packs.add(resident.id().toString().substring(0,4)+":"+had);
}
return Map.of("ok",true,"heartbeat",beat,"packs",packs);
`,
  }),
});
