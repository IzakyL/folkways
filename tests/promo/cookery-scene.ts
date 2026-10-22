import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { defineTask, javaTask, player, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { commandPos, frames, tryCommand } from "../shared/harness/bw-helpers";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast } from "./fast-setup";
import { applyCookeryLooks, prepareCookeryModels, WINE_FOX_MODELS } from "./cookery-cast";
import { cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, type PromoPair } from "./promo-kit";

// Shared staging for the small Cookery takes: each films one feature on a bare plot with a handful of residents.

export const K = "kaleidoscope_cookery:";
export const T = "kaleidoscope_tavern:";
export const COOKERY_CONFIG = "blockwright.toml#promo-cookery";
export const SUPPLY_BOX = "folkways:creative_supply_box";

export type Scene = {
  pair: PromoPair; server: MinecraftServer; client: MinecraftClient; name: string; o: Vec;
  at: (x: number, z: number, y?: number) => Vec;
  set: (p: Vec, block: string) => Promise<unknown>;
  fill: (a: Vec, b: Vec, block: string) => Promise<unknown>;
};

/** Launches the Cookery profile with the Wine Fox cast and clears a level plot around the player. */
export async function openScene(scope: string): Promise<Scene> {
  prepareCookeryModels();
  const pair = await launchPromoPair(scope, { config: COOKERY_CONFIG, models: WINE_FOX_MODELS });
  const { server, client } = pair;
  const grounded = await waitForGroundedPlayer(server);
  const name = grounded.name;
  await world.command(server, `op ${name}`);
  await cinematicWorld(server);
  await world.command(server, "gamerule doMobSpawning false");
  await world.command(server, "time set 3500");
  const o = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
  const at = (x: number, z: number, y = 0): Vec => ({ x: o.x + x, y: o.y + y, z: o.z + z });
  const set = (p: Vec, block: string) => world.command(server, `setblock ${commandPos(p)} ${block}`);
  const fill = (a: Vec, b: Vec, block: string) => tryCommand(server, `fill ${commandPos(a)} ${commandPos(b)} ${block}`);
  await fill(at(-16, -16, 0), at(16, 16, 14), "minecraft:air");
  await fill(at(-16, -16, -1), at(16, 16, -1), "minecraft:grass_block");
  return { pair, server, client, name, o, at, set, fill };
}

/** Founds the colony over the given members and admits residents wearing the Wine Fox looks. A settler needs its own
 * bed and food in store: unless the scene has both, a row of beds and a chest of rations are laid out from dorm,
 * out of the camera's view. */
export async function castColony(scene: Scene, members: Vec[], count: number, trades: (i: number) => string[],
  dorm?: Vec) {
  const { server, client, name, o } = scene;
  if (dorm) for (let i = 0; i < count; i++) {
    const foot = { ...dorm, x: dorm.x + i * 2 };
    await scene.set(foot, "minecraft:white_bed[part=foot,facing=north]");
    await scene.set({ ...foot, z: foot.z - 1 }, "minecraft:white_bed[part=head,facing=north]");
    members = [...members, foot];
  }
  if (dorm) {
    const rations = { ...dorm, x: dorm.x - 2 };
    await scene.set(rations, `minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:cooked_beef",count:64},{Slot:1b,id:"minecraft:cooked_beef",count:64}]}`);
    members = [...members, rations];
  }
  await world.command(server, `tp ${name} ${o.x + 0.5} ${o.y} ${o.z + 0.5}`);
  await world.command(server, `item replace entity ${name} weapon.mainhand with folkways:colony_book`);
  await tick.sprint(server, 10);
  const founded = await foundPromoColonyFast(server, name, members);
  const admitted = await admitPromoSettlersFast(server, name, founded.colony_id, count);
  const residents = (await world.entities(server, { types: ["folkways:resident"], limit: 64 })).entities.map(one => one.uuid);
  if (residents.length !== count) throw new Error(`Expected ${count} residents, found ${residents.length}`);
  const looks = await applyCookeryLooks(server, client, residents);
  const licences = await licenseCrewFast(server, residents.map((uuid, i) => ({ uuid, trades: trades(i) })));
  for (const uuid of residents) await world.command(server, `data merge entity ${uuid} {Invulnerable:1b}`);
  await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
  return { colony_id: founded.colony_id, residents, founded, admitted, looks, licences };
}

export async function place(server: MinecraftServer, uuid: string, p: Vec) {
  await world.command(server, `tp ${uuid} ${p.x + 0.5} ${p.y} ${p.z + 0.5}`);
}

/** Puts the spectating player at a fixed camera and clears the HUD. */
export async function fixCamera(scene: Scene, feet: Vec, aim: Vec, fov: number) {
  const { server, client, name } = scene;
  await world.command(server, `gamemode spectator ${name}`);
  await setFov(client, fov);
  const look = cameraLookingAt({ x: feet.x + 0.5, y: feet.y + 1.62, z: feet.z + 0.5 },
    { x: aim.x + 0.5, y: aim.y + 0.5, z: aim.z + 0.5 });
  await world.command(server, `tp ${name} ${feet.x + 0.5} ${feet.y} ${feet.z + 0.5} ${look.yaw} ${look.pitch}`);
  await tick.sprint(server, 5);
  await player.place(client, look);
  await screen.dismiss(client);
  await hideHud(client);
  await frames(client, 30);
  return look;
}

/** One table group: tables across [x1..x2]×[z1..z2] with a chair facing in on every free side cell. */
export async function tableGroup(scene: Scene, x1: number, z1: number, x2: number, z2: number, y = 0, wood = "spruce") {
  const tables: Vec[] = [], chairs: Vec[] = [];
  for (let x = x1; x <= x2; x++) for (let z = z1; z <= z2; z++) {
    const p = scene.at(x, z, y);
    await scene.set(p, `${K}table_${wood}`);
    tables.push(p);
  }
  const sides: Array<[number, number, string]> = [];
  for (let x = x1; x <= x2; x++) sides.push([x, z1 - 1, "south"], [x, z2 + 1, "north"]);
  for (let z = z1; z <= z2; z++) sides.push([x1 - 1, z, "east"], [x2 + 1, z, "west"]);
  for (const [x, z, facing] of sides) {
    const p = scene.at(x, z, y);
    await scene.set(p, `${K}chair_${wood}[facing=${facing}]`);
    chairs.push(p);
  }
  return { tables, chairs };
}

/** Floor and furniture cells of a dining area spanning [x1..x2]×[z1..z2]. */
export function areaCells(scene: Scene, x1: number, z1: number, x2: number, z2: number, y = 0) {
  const cells: Vec[] = [];
  for (let x = x1; x <= x2; x++) for (let z = z1; z <= z2; z++) cells.push(scene.at(x, z, y - 1), scene.at(x, z, y));
  return cells;
}

export type DiningArea = { id: string; cells: Vec[]; tables: Vec[]; cabinets?: Vec[]; foods: string[]; drinks?: string[]; servings?: number };

/** Delegates the dining areas with their menus; meals last mealTicks so a seated diner stays in frame. */
export async function stageDining(server: MinecraftServer, colony: string, areas: DiningArea[], mealTicks = 1200) {
  return server.run(STAGE_DINING, { colony, areas: areas.map(area => ({ ...area, drinks: area.drinks ?? [], servings: area.servings ?? 1 })), mealTicks });
}

/** Food 0..20 and tiredness per resident, written into the living presence's own books. */
export async function setVitals(server: MinecraftServer, colony_id: string, vitals: Array<{ uuid: string; food: number; tired: number }>) {
  return runColonyTask(server, SET_VITALS, { colony_id, vitals });
}

/** Puts the named trade first, so the resident cooks or brews before general hauling. */
export async function preferCrafting(server: MinecraftServer, uuids: string[], pack: Array<[string, number]> = []) {
  return server.run(PREFER_CRAFTING, { uuids, pack: pack.map(([id, count]) => ({ id, count })) });
}

export async function readScene(server: MinecraftServer, colony: string, uuids: string[], areas: DiningArea[]) {
  return server.run(READ_SCENE, { colony, uuids, areas: areas.map(area => ({ id: area.id, tables: area.tables, cabinets: area.cabinets ?? [] })) });
}

/** Runs the world until ready() holds, sampling once a second; the world is frozen again on return. */
export async function rollUntil<S>(server: MinecraftServer, rate: number, timeoutMs: number,
  sample: () => Promise<S>, ready: (state: S) => boolean, log: S[] = []) {
  await setTickRate(server, rate);
  await tick.freeze(server, false);
  try {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      const state = await sample();
      log.push(state);
      if (ready(state)) return { ready: true, state, log };
      await sleepMs(1000);
    }
    return { ready: false, state: log.at(-1), log };
  } finally {
    await tick.freeze(server, true);
    await setTickRate(server, 20);
  }
}

/** Records one fixed-camera clip at the given tick rate, sampling the scene each second. */
export async function shoot<S>(run: any, scene: Scene, name: string, subject: string, seconds: number, rate: number,
  sample: () => Promise<S>, worldState: Record<string, unknown> = {}) {
  const evidence: Array<S & { seconds: number }> = [];
  await setTickRate(scene.server, rate);
  try {
    await promoClip(run, scene.client, scene.server, name, { subject, worldState, seconds,
      during: async () => {
        const started = Date.now();
        while ((Date.now() - started) / 1000 < seconds) {
          evidence.push({ seconds: (Date.now() - started) / 1000, ...await sample() });
          await sleepMs(1000);
        }
      } });
  } finally {
    await setTickRate(scene.server, 20);
  }
  return evidence;
}

const STAGE_DINING = defineTask<Record<string, any>, any>({
  name: "folkways.promo.scene-dining", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.terms.ItemFilter",
    "io.github.izakyl.folkways.front.engine.colony.ColonyFront", "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
    "io.github.izakyl.folkways.plugins.person.living.LivingContent", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation",
    "io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig", "net.neoforged.neoforge.common.ModConfigSpec",
  ], body: `
var args = Args.of(ctx);
var colony = Colonies.of(ctx.server(), UUID.fromString(args.string("colony"))).orElseThrow();
var front = ColonyFront.of(colony);
// Lengthen the real meal on this filming server only; no food or mood is forged.
((ModConfigSpec.IntValue)FolkwaysVanillaConfig.SPEC.getValues().get("living.diningMealTicks")).set((int) args.integer("mealTicks"));
var zones = new ArrayList<Object>();
for (var rawArea : args.list("areas")) {
    var area = Args.of((Map) rawArea, "area");
    var cells = new java.util.LinkedHashSet<BlockPos>();
    for (var raw : area.list("cells")) {
        var cell = (Map<?, ?>) raw;
        cells.add(new BlockPos(((Number) cell.get("x")).intValue(), ((Number) cell.get("y")).intValue(), ((Number) cell.get("z")).intValue()));
    }
    var settings = ColonySettings.empty()
        .with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count((int) area.integer("servings")))
        .with(LivingContent.DINING_FOOD.key(), new ColonySettings.Value.Items(
            area.strings("foods").stream().map(id -> ItemFilter.item(ResourceLocation.parse(id))).toList()));
    if (!area.strings("drinks").isEmpty()) settings = settings.with(LivingContent.DINING_DRINK.key(), new ColonySettings.Value.Items(
        area.strings("drinks").stream().map(id -> ItemFilter.item(ResourceLocation.parse(id))).toList()));
    var zone = front.addZone(ctx.server().overworld(), LivingContent.DINING, cells, settings).orElseThrow();
    zones.add(Map.of("id", area.string("id"), "zone", zone.id().toString()));
}
return Map.of("areas", zones);
` }),
});

const SET_VITALS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.scene-vitals", side: "server", timeoutMs: 20_000,
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

const PREFER_CRAFTING = defineTask<Record<string, any>, any>({
  name: "folkways.promo.scene-prefer-crafting", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.resident.body.Keenness",
    "io.github.izakyl.folkways.core.api.vocation.Vocations", "io.github.izakyl.folkways.plugins.wares.WaresContent",
    "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation", "net.minecraft.world.item.ItemStack",
  ], body: `
var args = Args.of(ctx);
for (var uuid : args.strings("uuids")) {
    var body = Bodies.of(ctx.server().overworld().getEntity(UUID.fromString(uuid))).orElseThrow();
    body.pack().clearContent();
    for (var raw : args.list("pack")) {
        var one = (Map<?, ?>) raw;
        body.pack().insert(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(one.get("id")))),
            ((Number) one.get("count")).intValue()));
    }
    body.licences().trade(WaresContent.trade()).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FIRST));
    body.licences().trade(Vocations.required(Vocations.HAULING)).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FOURTH));
}
return Sync.stamp(Map.of("priority", "crafting-before-hauling"));
` }),
});

const READ_SCENE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.scene-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.plugins.person.living.DiningTables",
    "io.github.izakyl.folkways.core.api.terms.Containers",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var people = new ArrayList<Object>();
for (var id : args.strings("uuids")) {
    var entity = level.getEntity(UUID.fromString(id));
    var body = Bodies.of(entity).orElseThrow();
    var one = new LinkedHashMap<String, Object>();
    one.put("uuid", id);
    one.put("action", body.doing().map(doing -> doing.what().getPath()).orElse("idle"));
    one.put("toward", body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).orElse("idle"));
    boolean dining = entity instanceof io.github.izakyl.folkways.plugins.person.ResidentEntity person && person.leisureMotion().equals("dining");
    one.put("seated", dining && entity.getVehicle() != null);
    one.put("drinking", entity.getPersistentData().getBoolean("folkways:dining_drinking"));
    one.put("tableDrinks", entity.getPersistentData().getInt("folkways:table_drinks"));
    one.put("position", List.of(Math.round(entity.getX() * 10) / 10.0, Math.round(entity.getY() * 10) / 10.0, Math.round(entity.getZ() * 10) / 10.0));
    one.put("pack", body.pack().contents().stream().filter(stack -> !stack.isEmpty())
        .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + "x" + stack.getCount()).toList());
    people.add(one);
}
var areas = new ArrayList<Object>();
int portions = 0, drinks = 0;
for (var rawArea : args.list("areas")) {
    var area = Args.of((Map) rawArea, "area");
    var foods = new ArrayList<String>();
    for (var raw : area.list("tables")) {
        var p = (Map<?, ?>) raw;
        var pos = new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
        var inventory = DiningTables.inventory(level, pos).orElseThrow();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            var food = stack.getFoodProperties(null);
            if (!stack.isEmpty() && food != null && food.nutrition() > 0) foods.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        }
    }
    var bottles = new ArrayList<String>();
    for (var raw : area.list("cabinets")) {
        var p = (Map<?, ?>) raw;
        var pos = new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
        var cabinet = Containers.at(level, pos).orElseThrow();
        for (int slot = 0; slot < cabinet.getContainerSize(); slot++) {
            var stack = cabinet.getItem(slot);
            if (!stack.isEmpty()) bottles.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        }
    }
    portions += foods.size(); drinks += bottles.size();
    areas.add(Map.of("id", area.string("id"), "foods", foods, "bottles", bottles));
}
return Map.of("time", level.getGameTime(), "people", people, "areas", areas, "portions", portions, "drinks", drinks);
` }),
});
