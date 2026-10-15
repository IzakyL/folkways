import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, media, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement, scrollElementIntoView } from "../ldlib2";
import { chooseInSelector, foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook,
  waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { boardActs, settlePanel } from "../shared/ui/site-panel";
import { colonyRegistry, configureZone, createMarkedZone, openBookPage, setBookGesture,
  zoneCellsAt, zoneCount, zoneFlagSettingAt, zoneKindAt } from "../shared/ui/zone-tools";
import { aimAndWorldLeftClick } from "../shared/ui/world-ui";
import { MODEL_ASSETS, placeServerModel, readResidentLooks, reloadServerModels, waitForModelsOnClient } from "../shared/ui/model-folder";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seedModSettings } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage, type Vec } from "../shared/harness/stage";

const KCC = "kaleidoscope_cookery:";
const RICE_CROP = KCC + "rice_crop";
const WILD_RICE = KCC + "wild_rice";
const PANICLE = KCC + "rice_panicle";
const HAT = KCC + "straw_hat";
const WHEAT_SEED = "minecraft:wheat_seeds";
const BONE_MEAL = "minecraft:bone_meal";
const RESIDENT = "folkways:resident";
const ITEM = "minecraft:item";
const GRASS_SEEDS = ["tomato_seed", "lettuce_seed", "chili_seed", "tea_seed"].map((id) => KCC + id);
const PROTECTED_WHEAT = 128;
const PRODUCTION_TICKS = 8_000;

type Layout = ReturnType<typeof farming>;
type Stack = { id?: string; count?: number; Slot?: number };
type Inventory = { totals: Record<string, number>; chests: unknown[]; residents: unknown[]; drops: unknown[] };
type FarmState = { compostPoint: boolean; compostHasBlockEntity: boolean; root: string; middle: string; top: string; grass: number; compost: number;
  wearHat: boolean; wearArmor: boolean; hatResidents: Array<{ uuid: string; x: number; y: number; z: number }>;
  complete: boolean; wheatPhysical: number; wheatCompostable: number; rareCompostable: number };

for (const stage of twins()) test(stage.title("cookery farming: native rice, owned hats, protected seeds and a compost-to-grass loop"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();
  const serverName = stage.name(`cookery-farming-server-${runId}`);
  seedModSettings(serverName, { mode: "SINGLE_THREAD" });
  const evidence: Record<string, unknown> = { stage: stage.kind, protectedWheat: PROTECTED_WHEAT };
  let pair: E2EPair | undefined;
  let containers: Vec[] = [];
  let layout: Layout | undefined;
  try {
    pair = await launchPair({
      config: stage.kind === "ground" ? "blockwright.toml#cookery" : "blockwright.toml#cookery-sable",
      server: { trace: tracePath("e2e", stage.name("cookery-farming-server-" + runId)), instance: serverName },
      client: { trace: tracePath("e2e", stage.name("cookery-farming-client-" + runId)), instance: stage.name(`cookery-farming-client-${runId}`) },
      snapshot: () => ({ containers: containers.map((at) => stage.at(at)), entityTypes: [RESIDENT, ITEM] }),
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    // Exercise the existing native skin renderer on land and the existing user Geo model aboard.
    if (stage.kind === "aboard") {
      evidence.model = await placeServerModel(server, path.join(MODEL_ASSETS, "default_boy"), "default_boy", { weight: 1_000_000 });
      await reloadServerModels(server);
      evidence.modelArrival = await waitForModelsOnClient(client, "assets/folkways/geo/resident_models/default_boy.geo.json");
    }
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const box = foundingBox(origin);
    await stage.lay(server, box);
    const block = (at: Vec) => commandPos(stage.at(at));
    await world.command(server, `fill ${block({ ...box.min, y: origin.y - 1 })} ${block({ ...box.max, y: origin.y - 1 })} minecraft:smooth_stone`);
    const founded = await foundColonyFast(server, client, player, { origin, residentCount: 1, stage });
    evidence.founding = founded.evidence;
    layout = farming(origin);
    containers = [founded.foodChest, layout.supply, layout.seedTarget];
    for (const command of ["gamerule doDaylightCycle false", "gamerule doMobSpawning false", "gamerule randomTickSpeed 0", "difficulty peaceful", "time set day"]) {
      await world.command(server, command);
    }
    await world.command(server, `gamemode survival ${player}`);
    await tryCommand(server, `execute positioned ${commandPos(stage.world(origin))} run kill @e[type=minecraft:fox,distance=..48]`);
    await tryCommand(server, `execute positioned ${commandPos(stage.world(origin))} run kill @e[type=${ITEM},distance=..48]`);
    for (const chest of containers.slice(1)) {
      await world.command(server, `setblock ${block(chest)} minecraft:chest{Items:[]}`);
      await optInViaBook(server, client, player, chest, "chest", stage);
    }
    await world.command(server, `setblock ${block(layout.composter)} minecraft:composter`);
    evidence.composterMark = await optInViaBook(server, client, player, layout.composter, "worksite", stage);
    const compostRegistration = await state(server, stage, layout);
    expect(compostRegistration.compostPoint, "the book registers a real ledger-owned composter point").toBe(true);
    expect(compostRegistration.compostHasBlockEntity, "registration preserves the native composter without a fabricated block entity").toBe(false);

    evidence.hatOff = await editClothing(server, client, player, founded.standBlock, stage, 0);
    expect((await state(server, stage, layout)).wearHat, "the real book action disables automatic hats").toBe(false);
    expect((await state(server, stage, layout)).wearArmor, "a full farmer outfit is opt-in").toBe(false);
    evidence.clothingPicker = await editClothing(server, client, player, founded.standBlock, stage, 2, HAT);
    expect((await inventory(server, stage, containers)).totals[HAT] ?? 0, "the clothing selector is a sample, not a physical free hat").toBe(0);

    // Mark the actual ground before adding water, so the book can address its visible surface.
    await world.command(server, `setblock ${block(layout.riceGround)} minecraft:dirt`);
    await setBookGesture(server, client, player, "box");
    await aimAndWorldLeftClick(server, client, player, layout.riceGround, stage);
    await aimAndWorldLeftClick(server, client, player, layout.riceGround, stage);
    evidence.riceMark = await createMarkedZone(server, client, player, founded.standBlock, "farm", stage);
    evidence.riceSetting = await configureZone(server, client, player, founded.standBlock,
      { min: layout.riceGround, max: layout.riceGround }, [{ key: "crop", option: RICE_CROP }], stage);
    // Native rice needs source water and two clear blocks above. Its one-block rim contains the water.
    for (const [dx, dz] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) {
      await world.command(server, `setblock ${block({ ...layout.riceRoot, x: layout.riceRoot.x + dx!, z: layout.riceRoot.z + dz! })} minecraft:smooth_stone`);
    }
    await putStock(server, stage, layout.supply, "minecraft:wooden_hoe", 1);
    await putStock(server, stage, layout.supply, WILD_RICE, 2);
    const beforeRice = await inventory(server, stage, containers);
    evidence.beforeRice = beforeRice;
    expect(beforeRice.totals[PANICLE] ?? 0, "no panicles exist before the real crop harvest").toBe(0);
    expect(beforeRice.totals[WILD_RICE]).toBe(2);
    await world.command(server, `setblock ${block(layout.riceRoot)} minecraft:water[level=0]`);
    evidence.planted = await waitFor(server, stage, layout, "the resident must place all three real rice parts", (sample) => threeParts(sample));
    const plantedStock = await inventory(server, stage, containers);
    expect(plantedStock.totals[WILD_RICE] ?? 0, "one real seed is spent on one three-part plant").toBe(1);
    expect((evidence.planted as FarmState).root).toContain("age=0");
    expect((evidence.planted as FarmState).root).toContain("waterlogged=true");
    expect((evidence.planted as FarmState).hatResidents).toHaveLength(0);

    await world.command(server, "gamerule randomTickSpeed 1024");
    let matureSeen = false;
    let harvested: Inventory | undefined;
    const riceStates: unknown[] = [];
    for (let elapsed = 0; elapsed <= PRODUCTION_TICKS;) {
      const sample = await state(server, stage, layout);
      matureSeen ||= sample.root.includes("age=7");
      if (riceStates.length === 0 || sample.root !== (riceStates.at(-1) as { root: string }).root) riceStates.push({ elapsed, ...sample });
      const goods = await inventory(server, stage, containers);
      if ((goods.totals[PANICLE] ?? 0) > 0) { harvested = goods; break; }
      const step = /age=[56]/.test(sample.root) ? 1 : 10;
      await tick.sprint(server, step);
      elapsed += step;
    }
    await world.command(server, "gamerule randomTickSpeed 0");
    evidence.riceStates = riceStates;
    evidence.harvested = harvested;
    expect(matureSeen, "maturity must come from native random ticking, never an age write").toBe(true);
    expect(harvested, "the real farmer must harvest native panicles").toBeDefined();
    const panicles = harvested!.totals[PANICLE] ?? 0;
    expect(panicles, "one rice root yields the native two to four panicles, with no loot from attached parts").toBeGreaterThanOrEqual(2);
    expect(panicles).toBeLessThanOrEqual(4);
    evidence.replanted = await waitFor(server, stage, layout, "the same wet plot must receive its next real seed", (sample) => threeParts(sample) && sample.root.includes("age=0"));
    expect((await inventory(server, stage, containers)).totals[PANICLE]).toBe(panicles);

    await world.command(server, `fill ${block({ ...layout.grass.min, y: layout.grass.min.y - 1 })} ${block({ ...layout.grass.max, y: layout.grass.max.y - 1 })} minecraft:dirt`);
    await world.command(server, `fill ${block(layout.grass.min)} ${block(layout.grass.max)} minecraft:grass_block`);
    evidence.grassMark = await markSeedGrass(server, client, player, founded.standBlock, layout, stage);
    expect((await state(server, stage, layout)).grass, "no short grass is pre-generated to bypass the bone-meal loop").toBe(0);
    evidence.seedOrder = await setMaintainDemandViaUI(server, client, player, layout.seedTarget, WHEAT_SEED, PROTECTED_WHEAT, stage);
    for (let stack = 0; stack < 4; stack++) await putStock(server, stage, layout.supply, WHEAT_SEED, 64);
    await putStock(server, stage, layout.supply, HAT, 1);
    const initial = await inventory(server, stage, containers);
    evidence.beforeSeedLoop = initial;
    expect(initial.totals[BONE_MEAL] ?? 0, "all bone meal must come from the real composter").toBe(0);
    for (const seed of GRASS_SEEDS) expect(initial.totals[seed] ?? 0, "there are no unstated rare seeds").toBe(0);
    await tick.sprint(server, 200);
    expect((await state(server, stage, layout)).hatResidents).toHaveLength(0);
    expect((await inventory(server, stage, containers)).totals[WHEAT_SEED], "without a worn hat the composter must not consume even surplus seeds").toBe(256);
    expect((await state(server, stage, layout)).compost).toBe(0);

    evidence.hatOn = await editClothing(server, client, player, founded.standBlock, stage, 0);
    const wearing = await waitFor(server, stage, layout, "one actual supplied hat must move from logistics into HEAD", (sample) => sample.hatResidents.length === 1);
    expect(wearing.wearHat).toBe(true);
    expect((await inventory(server, stage, containers)).totals[HAT], "the worn hat remains the same single physical item").toBe(1);
    const looks = await readResidentLooks(server);
    evidence.looks = looks;
    if (stage.kind === "aboard") expect(looks[0], "the aboard hat screenshot exercises the existing user Geo model").toMatch(/^model\/default_boy\//);
    const resident = wearing.hatResidents[0]!;
    const eye = { x: resident.x + 3.5, y: resident.y + 2.2, z: resident.z + 3.5 };
    const shot = outAbs("e2e", "shots", stage.name("cookery-real-head-hat") + ".png");
    await frames(client, 8);
    await media.screenshot(client, { path: shot, camera: { position: eye,
      ...cameraLookingAt(eye, { x: resident.x, y: resident.y + 1.5, z: resident.z }) } });
    await testInfo.attach(stage.kind === "ground" ? "native-skin-head-hat" : "user-geo-head-hat", { path: shot, contentType: "image/png" });

    let level7Seen = false, level8Seen = false, collectedSeen = false, grewGrass = false, harvestedGrass = false, newSeedSeen = false;
    let previousGrass = 0;
    const observations: unknown[] = [];
    for (let elapsed = 0; elapsed <= PRODUCTION_TICKS;) {
      const sample = await state(server, stage, layout);
      const stock = await inventory(server, stage, containers);
      expect(sample.complete, "all fixture inventory must stay loaded").toBe(true);
      expect(sample.wheatPhysical, "standing orders protect their full high-water count before any seed enters compost").toBeGreaterThanOrEqual(PROTECTED_WHEAT);
      expect(stock.totals[WHEAT_SEED] ?? 0, "physical chest, pack and drop accounting also retains the protected wheat").toBeGreaterThanOrEqual(PROTECTED_WHEAT);
      expect(sample.rareCompostable, "first-batch rare grass seeds remain protected for expansion").toBe(0);
      expect(stock.totals[HAT], "equipment is never copied while grass and compost tasks run").toBe(1);
      level7Seen ||= sample.compost === 7;
      level8Seen ||= sample.compost === 8;
      collectedSeen ||= level8Seen && sample.compost < 7;
      grewGrass ||= sample.grass > 0;
      harvestedGrass ||= grewGrass && sample.grass < previousGrass;
      newSeedSeen ||= GRASS_SEEDS.some((seed) => (stock.totals[seed] ?? 0) > 0);
      observations.push({ elapsed, ...sample, totals: stock.totals });
      previousGrass = sample.grass;
      if (level7Seen && level8Seen && collectedSeen && grewGrass && harvestedGrass && newSeedSeen) break;
      const step = sample.compost >= 6 ? 5 : 10;
      await tick.sprint(server, step);
      elapsed += step;
    }
    evidence.seedLoop = { level7Seen, level8Seen, collectedSeen, grewGrass, harvestedGrass, newSeedSeen, observations };
    expect(level7Seen, "native compost reaches the scheduled maturing stage").toBe(true);
    expect(level8Seen, "the native scheduled tick reaches collectable compost").toBe(true);
    expect(collectedSeen, "a resident extracts the real bone meal and empties the composter").toBe(true);
    expect(grewGrass, "that physical bone meal restores native short grass inside the marked area").toBe(true);
    expect(harvestedGrass, "the hatted farmer harvests the newly grown real grass").toBe(true);
    expect(newSeedSeen, "native grass loot supplies actual previously missing Cookery seeds").toBe(true);
    evidence.final = await inventory(server, stage, containers);
  } catch (error) {
    pair?.failing(error);
    evidence.failure = String(error);
    if (pair && layout) {
      try { evidence.atFailure = { state: await state(pair.server, stage, layout), inventory: await inventory(pair.server, stage, containers) }; }
      catch (snapshotError) { evidence.snapshotFailure = String(snapshotError); }
    }
    throw error;
  } finally {
    try {
      const file = testInfo.outputPath("cookery-farming-evidence.json");
      writeFileSync(file, JSON.stringify(evidence, null, 2));
      await testInfo.attach("cookery-farming-evidence", { path: file, contentType: "application/json" });
    } finally { await pair?.teardown(testInfo); }
  }
});

function farming(origin: Vec) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return { supply: at(-8, 4), seedTarget: at(-8, 7), composter: at(-4, 12), riceGround: at(-10, 11, -1), riceRoot: at(-10, 11),
    grass: { min: at(3, 3, -1), max: at(17, 17, -1) } };
}

async function editClothing(server: MinecraftServer, client: MinecraftClient, player: string, stand: Vec, stage: Stage,
  setting: 0 | 2, item?: string) {
  await openColonyPanelViaBook(server, client, player, stand, stage);
  await scrollElementIntoView(client, { id: "folkways.page.cookery" });
  await clickElement(client, { id: "folkways.page.cookery" });
  await settlePanel(server, client);
  const edits = await boardActs(client, "edit", "folkways.cookery");
  expect(edits.length, "the actual kitchen page exposes hat, outfit and clothing settings").toBe(3);
  const edit = edits[setting]!;
  await scrollElementIntoView(client, { id: edit.id });
  const opened = await clickElement(client, { id: edit.id });
  let picked: unknown;
  if (item) {
    picked = await chooseInSelector(client, item, "*" + item, opened);
    await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent", timeoutMs: 8_000 });
  }
  await settlePanel(server, client);
  await screen.dismiss(client);
  await tick.sprint(server, 10);
  return { setting, edit, opened, picked };
}

async function markSeedGrass(server: MinecraftServer, client: MinecraftClient, player: string, stand: Vec, layout: Layout, stage: Stage) {
  const registry = await colonyRegistry(server);
  const before = await zoneCount(server, registry);
  await setBookGesture(server, client, player, "box");
  await aimAndWorldLeftClick(server, client, player, layout.grass.min, stage);
  await aimAndWorldLeftClick(server, client, player, layout.grass.max, stage);
  await openBookPage(server, client, player, stand, stage);
  await clickElement(client, { id: "folkways.zoneconfig.kind.folkways.seed_grass" });
  await settlePanel(server, client);
  await clickElement(client, { id: "folkways.zoneconfig.confirm" });
  await expect.poll(async () => { await tick.sprint(server, 3); return zoneCount(server, registry); }, { timeout: 8_000 }).toBe(before + 1);
  await screen.dismiss(client);
  expect(await zoneKindAt(server, registry, before)).toBe("folkways:seed_grass");
  expect(await zoneCellsAt(server, registry, before)).toBe(225);
  expect(await zoneFlagSettingAt(server, registry, "restore_grass", before)).toBe(true);
  return { index: before, cells: 225, restoreGrass: true };
}

function threeParts(sample: FarmState) {
  return sample.root.includes(RICE_CROP) && sample.root.includes("location=0")
    && sample.middle.includes(RICE_CROP) && sample.middle.includes("location=1")
    && sample.top.includes(RICE_CROP) && sample.top.includes("location=2");
}

async function waitFor(server: MinecraftServer, stage: Stage, layout: Layout, reason: string, ready: (sample: FarmState) => boolean) {
  let last: FarmState | undefined;
  for (let elapsed = 0; elapsed <= PRODUCTION_TICKS; elapsed += 10) {
    last = await state(server, stage, layout);
    if (ready(last)) return last;
    await tick.sprint(server, 10);
  }
  throw new Error(`${reason}; final state: ${JSON.stringify(last)}`);
}

async function state(server: MinecraftServer, stage: Stage, layout: Layout): Promise<FarmState> {
  return server.run(READ_FARM, { rice: stage.at(layout.riceRoot), composter: stage.at(layout.composter),
    min: stage.at(layout.grass.min), max: stage.at(layout.grass.max) });
}

// Observation only: all production, membership, orders and clothes mutations above use the game and book UI.
const READ_FARM = defineTask<{ rice: Vec; composter: Vec; min: Vec; max: Vec }, FarmState>({
  name: "folkways.cookery.farming-observation", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.level.block.Blocks", "net.minecraft.world.level.block.ComposterBlock",
      "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.item.Items", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.front.api.Fronts",
      "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos",
      "io.github.izakyl.folkways.plugins.cookery.CookeryContent", "io.github.izakyl.folkways.plugins.cookery.seeds.SeedPresence",
      "com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems", "com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod"],
    members: `private static BlockPos pos(Object raw) {
      var at = (Map<?, ?>) raw;
      return new BlockPos(((Number)at.get("x")).intValue(), ((Number)at.get("y")).intValue(), ((Number)at.get("z")).intValue());
    }`,
    body: `
var args=Args.of(ctx); var level=ctx.server().overworld(); var root=pos(args.value("rice"));
var compost=level.getBlockState(pos(args.value("composter")));
int grass=0; for(var at:BlockPos.betweenClosed(pos(args.value("min")),pos(args.value("max"))))
  if(level.isLoaded(at.above())&&level.getBlockState(at.above()).is(Blocks.SHORT_GRASS))grass++;
var colony=Colonies.all(ctx.server()).getFirst(); var settings=Fronts.of(colony).settings(CookeryContent.ID);
var seeds=colony.service(SeedPresence.ID,SeedPresence.class).orElseThrow(); var budget=seeds.budget(level);
var hats=new ArrayList<Object>(); for(var resident:colony.residents()) {
  var entity=level.getEntity(resident.id()); if(entity==null)continue;
  var body=Bodies.of(entity).orElseThrow(); if(body.mob().getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT))
    hats.add(Map.of("uuid",resident.id().toString(),"x",entity.getX(),"y",entity.getY(),"z",entity.getZ()));
}
long rare=0; for(var item:List.of(ModItems.TOMATO_SEED.get(),ModItems.LETTUCE_SEED.get(),ModItems.CHILI_SEED.get(),ModItems.TEA_SEED.get()))rare+=budget.tally(item).compostable();
var wheat=budget.tally(Items.WHEAT_SEEDS); var out=new LinkedHashMap<String,Object>();
out.put("root",level.getBlockState(root).toString());out.put("middle",level.getBlockState(root.above()).toString());out.put("top",level.getBlockState(root.above(2)).toString());
out.put("grass",grass);out.put("compost",compost.is(Blocks.COMPOSTER)?compost.getValue(ComposterBlock.LEVEL):-1);
out.put("compostPoint",colony.holdingAt(WorldPos.of(level,pos(args.value("composter"))))
  .filter(holding->holding.owner().equals(CookeryContent.COMPOST)&&holding.what() instanceof Held.Point).isPresent());
out.put("compostHasBlockEntity",level.getBlockEntity(pos(args.value("composter")))!=null);
out.put("wearHat",settings.flag("wear_hat"));out.put("wearArmor",settings.flag("wear_farmer_armor"));out.put("hatResidents",hats);
out.put("complete",budget.complete());out.put("wheatPhysical",wheat.physical());out.put("wheatCompostable",wheat.compostable());out.put("rareCompostable",rare);
return out;`,
  }),
});

async function stacksAt(server: MinecraftServer, at: Vec): Promise<Stack[]> {
  const store = await world.container(server, at, { dimension: "minecraft:overworld", nbt: true });
  expect(store.found).toBe(true);
  const items = (store.nbt as { Items?: Stack[] } | undefined)?.Items;
  expect(Array.isArray(items), "physical container NBT must be complete").toBe(true);
  return items!;
}

async function putStock(server: MinecraftServer, stage: Stage, chest: Vec, item: string, count: number) {
  const at = stage.at(chest);
  const occupied = new Set((await stacksAt(server, at)).map((stack) => stack.Slot));
  const slot = Array.from({ length: 27 }, (_, i) => 26 - i).find((one) => !occupied.has(one));
  expect(slot, "supply insertion must not overwrite any existing physical goods").toBeDefined();
  await world.command(server, `execute unless data block ${commandPos(at)} Items[{Slot:${slot}b}] run item replace block ${commandPos(at)} container.${slot} with ${item} ${count}`);
}

async function inventory(server: MinecraftServer, stage: Stage, containers: Vec[]): Promise<Inventory> {
  const totals: Record<string, number> = {};
  const add = (stack: Stack) => { if (stack.id) totals[stack.id] = (totals[stack.id] ?? 0) + (stack.count ?? 1); };
  const chests = await Promise.all(containers.map(async (cell) => { const items = await stacksAt(server, stage.at(cell)); items.forEach(add); return { cell, items }; }));
  const sample = await world.entities(server, { dimension: "minecraft:overworld", types: [RESIDENT, ITEM], nbt: true, limit: 1_000 });
  expect(sample.limited).toBe(false);
  expect(sample.nbtTruncated).not.toBe(true);
  const residents: unknown[] = [], drops: unknown[] = [];
  for (const entity of sample.entities) {
    const nbt = entity.nbt as Record<string, any> | undefined;
    if (entity.type === RESIDENT) {
      const pack = nbt?.["neoforge:attachments"]?.["folkways:body_state"]?.Pack;
      expect(Array.isArray(pack?.Items), "actual resident pack must be readable").toBe(true);
      for (const entry of pack.Items) add(entry.Stack);
      expect(Array.isArray(nbt?.ArmorItems), "native equipment slots must be readable").toBe(true);
      nbt!.ArmorItems.forEach(add);
      residents.push({ uuid: entity.uuid, position: entity.position, items: pack.Items, armor: nbt!.ArmorItems });
    } else if (entity.type === ITEM) {
      expect(nbt?.Item).toBeDefined(); add(nbt!.Item); drops.push({ uuid: entity.uuid, stack: nbt!.Item });
    }
  }
  expect(residents).toHaveLength(1);
  return { totals, chests, residents, drops };
}
