import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineTask, javaTask, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { sleepMs } from "./promo-kit";

// The airport promo's world: Airline's patterns drafted into a full field, the Superb Warfare fleet parked on it,
// and the reads the take films by. Coordinates are absolute: the field is laid out around ORIGIN, the ground cell of
// the flat world's spawn.

export type Vec = { x: number; y: number; z: number };

const HERE = path.dirname(fileURLToPath(import.meta.url));
const java = (name: string) => readFileSync(path.join(HERE, "airport", name), "utf8");

/** The ground cell the field is drafted on: the flat world's grass layer. Vehicles and people stand one above. */
export const ORIGIN: Vec = { x: 0, y: -60, z: 0 };
export const FLOOR = ORIGIN.y + 1;
export const TOWER_HEIGHT = 44;
/** The tower block on top of its shaft, south of the field and facing south and scanning north over it. */
export const TOWER: Vec = { x: 0, y: ORIGIN.y + TOWER_HEIGHT - 1, z: 170 };
export const AIRPORT_NAME = "机场";

/** Landing-site ids as the airport publishes them: the tower's address, then the facility. */
const SITE = `minecraft:overworld@${TOWER.x}, ${TOWER.y}, ${TOWER.z}`;
export const SITES = {
  runway09L: `${SITE}/runway/09L`,
  runway27R: `${SITE}/runway/27R`,
  runway09R: `${SITE}/runway/09R`,
  runway27L: `${SITE}/runway/27L`,
  runway04: `${SITE}/runway/04`,
  runway22: `${SITE}/runway/22`,
  pad: (x: number, z: number) => `${SITE}/pad/minecraft:overworld@${x}, ${ORIGIN.y}, ${z}`,
};
/** The ground field's landing sites: six runway ends and four helipads. */
export const GROUND_SITES = 10;
/** The element id a site's row takes in the command card's picker. */
export const pickerRow = (site: string) =>
  "folkways.choice.option." + site.replace(/#/g, "").replace(/:/g, ".").replace(/\//g, ".");

/** Helipad centres, as BuildAirport.java drafts them: two rows of two east of the east connector. */
export const PADS = { north: { x: 118, z: -88 }, northFar: { x: 143, z: -88 }, south: { x: 118, z: -46 },
  southFar: { x: 143, z: -46 } };

/** The catapult on runway 27R: its rails start 14 cells down the runway from the east threshold, pointing west. */
export const CATAPULT = { x: 126, y: ORIGIN.y, z: -140, facing: "west" };

/** The targets' range, out on the grass south of the field and well short of the tower. */
export const RANGE: Vec = { x: 0, y: FLOOR, z: 105 };

export type Parked = { key: string; type: string; x: number; z: number; yaw: number; crew?: boolean };

/** The terminal apron's six stands, west to east: centre x, and the bay's centre z (noses toward the terminal). */
export const STAND_STOP_Z = -75;
export const STANDS = [-68, -43, -18, 7, 32, 57].map(x => ({ x, z: STAND_STOP_Z + 11 }));

// Yaw: 0 faces south, 90 west, 180 north, -90 east.
export const FLEET: Parked[] = [
  // The performers.
  { key: "a10", type: "superbwarfare:a_10a", x: -143.5, z: 0.5, yaw: -90, crew: true },          // runway 09R
  { key: "ju87", type: "superbwarfare:ju_87", x: 135.5, z: -139.5, yaw: 90, crew: true },        // runway 27R
  { key: "ah6", type: "superbwarfare:ah_6", x: 118.5, z: -87.5, yaw: 180, crew: true },          // north pad
  { key: "mi28", type: "superbwarfare:mi_28", x: 143.5, z: -87.5, yaw: 180, crew: true },        // far north pad
  { key: "m1", type: "superbwarfare:m_1a_2", x: -45.5, z: 52.5, yaw: 0 },                         // boarded on film
  // Empty, as the rest of the motor pool: a crew in either would open fire on the boarded tank's targets.
  { key: "ztz", type: "superbwarfare:ztz_99a", x: -30.5, z: 52.5, yaw: 0 },
  { key: "bmp", type: "superbwarfare:bmp_2", x: -15.5, z: 52.5, yaw: 0 },
  { key: "lav", type: "superbwarfare:lav_150", x: 0.5, z: 52.5, yaw: 0 },
  // Parked, for the field to look busy: five of the six stands, nose in at the terminal, a gunship on the grass, a
  // helicopter on the far south pad, the rest of the motor pool.
  { key: "tom6", type: "superbwarfare:tom_6", x: -67.5, z: -63.5, yaw: 180 },
  { key: "kv16", type: "superbwarfare:kv_16", x: -42.5, z: -63.5, yaw: 180 },
  { key: "ju87b", type: "superbwarfare:ju_87", x: -17.5, z: -63.5, yaw: 180 },
  { key: "a10b", type: "superbwarfare:a_10a", x: 7.5, z: -63.5, yaw: 180 },
  { key: "a10c", type: "superbwarfare:a_10a", x: 57.5, z: -63.5, yaw: 180 },
  { key: "ac130", type: "superbwarfare:ac_130h", x: -110.5, z: 45.5, yaw: -90 },
  { key: "ah6b", type: "superbwarfare:ah_6", x: 143.5, z: -45.5, yaw: 180 },
  { key: "bradley", type: "superbwarfare:bradley", x: 15.5, z: 52.5, yaw: 0 },
  { key: "lavad", type: "superbwarfare:lav_ad", x: 30.5, z: 52.5, yaw: 0 },
  { key: "t90", type: "superbwarfare:t_90a", x: 45.5, z: 52.5, yaw: 0 },
  { key: "pickup", type: "superbwarfare:sodayo_pick_up_hmg", x: 60.5, z: 52.5, yaw: 0 },
];

/** Drafts the field from Airline's patterns. Long: it reloads and rewrites the whole field. */
export const BUILD = defineTask<Vec & { tower: number }, any>({
  name: "folkways.promo.airport.build", side: "server", timeoutMs: 600_000, source: java("BuildAirport.java"),
});

/** Hands the tower to the colony, names the airport and waits for its survey. Runs on a worker thread. */
export const SURVEY = defineTask<Vec & { colony: string; name: string; timeoutMs?: number }, any>({
  name: "folkways.promo.airport.survey", side: "server", thread: "worker", timeoutMs: 620_000,
  source: java("SurveyAirport.java"),
});

/** The fleet: spawn, crew, catapult and read. */
export const FLEET_TASK = defineTask<Record<string, any>, any>({
  name: "folkways.promo.airport.fleet", side: "server", timeoutMs: 60_000, source: java("Fleet.java"),
});

/** Whether the airport has a finished, flawless survey: only then does it offer its runways and pads. */
export const AIRPORT = defineTask<Vec, { scanning: boolean; flaws: string[]; sites: string[] }>({
  name: "folkways.promo.airport.state", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.airline.Airports", "io.github.izakyl.folkways.core.combat.api.LandingSites",
      "net.minecraft.core.BlockPos", "net.minecraft.server.MinecraftServer"],
    body: `
var a = Args.of(ctx);
var level = ((MinecraftServer) ctx.server()).overworld();
var pos = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var airport = Airports.at(level, pos);
return Map.of("scanning", Airports.scanning(level, pos),
    "flaws", airport.map(found -> found.flaws().stream().map(flaw -> flaw.kind().name()).toList()).orElse(List.of("NONE")),
    "sites", LandingSites.in(level).stream().map(site -> site.id()).toList());`,
  }),
});

/** Waits until the airport offers its sites again: any block change near it sends it back to its survey. */
export async function airportReady(server: MinecraftServer, timeoutMs = 360_000) {
  const t0 = Date.now();
  let last: any;
  while (Date.now() - t0 < timeoutMs) {
    last = await server.run(AIRPORT, TOWER);
    if (!last.scanning && last.flaws.length === 0 && last.sites.length >= GROUND_SITES) return last;
    await sleepMs(2_000);
  }
  throw new Error(`The airport never came back from its survey: ${JSON.stringify(last)}`);
}

/** Dummy targets on the range: zombies that do not move, in pumpkins so the sun leaves them be. */
export const TARGETS = defineTask<{ points: Vec[]; health: number }, string[]>({
  name: "folkways.promo.airport.targets", side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.world.entity.EntityType",
      "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.ai.attributes.Attributes",
      "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items"],
    body: `
var a = Args.of(ctx);
var level = ((MinecraftServer) ctx.server()).overworld();
var ids = new ArrayList<String>();
for (Object raw : a.list("points")) {
    var p = Args.of(raw, "point");
    var zombie = EntityType.ZOMBIE.create(level);
    zombie.setNoAi(true);
    zombie.setPersistenceRequired();
    zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(a.number("health"));
    zombie.setHealth((float) a.number("health"));
    zombie.getAttribute(Attributes.SCALE).setBaseValue(2.0);
    zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
    zombie.moveTo(p.number("x") + 0.5, p.number("y"), p.number("z") + 0.5, 180, 0);
    zombie.addTag("folkways_promo_target");
    level.addFreshEntity(zombie);
    ids.add(zombie.getUUID().toString());
}
return ids;`,
  }),
});

/** Living targets left, of those given. */
export const TARGETS_LEFT = defineTask<{ ids: string[] }, number>({
  name: "folkways.promo.airport.targets-left", side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer"],
    body: `
var level = ((MinecraftServer) ctx.server()).overworld();
int left = 0;
for (String id : Args.of(ctx).strings("ids")) {
    var entity = level.getEntity(UUID.fromString(id));
    if (entity != null && entity.isAlive()) left++;
}
return left;`,
  }),
});

/**
 * The world's rules for the film: Superb Warfare's explosions leave the blocks be (a cratered runway would send the
 * airport back to its survey), while mob griefing stays on, since residents only fire explosive rounds where it is.
 */
export const WORLD_RULES = defineTask<Record<string, never>, any>({
  name: "folkways.promo.airport.rules", side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.world.Difficulty",
      "net.minecraft.world.level.GameRules", "net.neoforged.neoforge.common.ModConfigSpec"],
    body: `
var server = (MinecraftServer) ctx.server();
server.setDifficulty(Difficulty.NORMAL, true);
var rules = server.overworld().getGameRules();
rules.getRule(GameRules.RULE_MOBGRIEFING).set(true, server);
rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
var destroy = (ModConfigSpec.BooleanValue) Class.forName("com.atsuishio.superbwarfare.config.server.ExplosionConfig")
    .getDeclaredField("EXPLOSION_DESTROY").get(null);
destroy.set(false);
return Map.of("explosionDestroy", destroy.get());`,
  }),
});

/** Opens the tower's airport page for the player, as right-clicking the tower does. */
export const OPEN_TOWER = defineTask<Vec, boolean>({
  name: "folkways.promo.airport.open-tower", side: "server",
  source: javaTask({
    imports: ["com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType", "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer"],
    body: `
var a = Args.of(ctx);
var pos = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
BlockUIMenuType.openUI(((MinecraftServer) ctx.server()).getPlayerList().getPlayers().getFirst(), pos);
return true;`,
  }),
});

export async function readFleet(server: MinecraftServer, ids: string[]) {
  return server.run(FLEET_TASK, { op: "read", vehicles: ids });
}

/** Client options for the film: the HUD shown, no toasts, tutorial or chat over the shot. */
export const CLIENT_SETUP = defineTask<{ guiScale: number }, any>({
  name: "folkways.promo.airport.client", side: "client", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.client.tutorial.TutorialSteps"],
    body: `
var mc = Minecraft.getInstance();
mc.options.guiScale().set((int) Args.of(ctx).integer("guiScale"));
mc.options.hideGui = false;
mc.options.tutorialStep = TutorialSteps.NONE;
mc.getTutorial().setStep(TutorialSteps.NONE);
mc.getToasts().clear();
mc.gui.getChat().clearMessages(true);
mc.resizeDisplay();
mc.options.save();
return Map.of("width", mc.getWindow().getGuiScaledWidth(), "height", mc.getWindow().getGuiScaledHeight());`,
  }),
});

export async function clientClear(client: MinecraftClient) {
  await client.run(CLEAR_CLIENT, {});
}

const CLEAR_CLIENT = defineTask<Record<string, never>, boolean>({
  name: "folkways.promo.airport.clear", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
mc.getToasts().clear();
mc.gui.getChat().clearMessages(true);
mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
// No hotbar, hand or held book in any shot: the tactical view and the screens draw over the bare world.
mc.options.hideGui = true;
return true;`,
  }),
});

/** A real triangle buffer must preserve every vertex under Iris as well as vanilla rendering. */
export const SHADER_TRIANGLES = defineTask<{ verify: boolean }, any>({
  name: "folkways.promo.airport.shader-triangles", side: "client", source: java("ShaderTriangles.java"),
});

// ---------------------------------------------------------------------------------------------------------------
// The carrier

/**
 * The carrier is laid out (Carrier.java) with its stern at CARRIER_BASE, assembled into a Sable structure, then turned
 * and held with its deck's middle at CARRIER_AT, south-east of the field, its bow toward the airport.
 */
export const CARRIER_BASE: Vec = { x: 170, y: ORIGIN.y + 55, z: 120 };
export const CARRIER_AT: Vec = { x: 250, y: ORIGIN.y + 55, z: 120 };
export const CARRIER_TURN = 144;
export const CARRIER_NAME = "航母";
/** Deck cells (x from the stern, z across, starboard +): the runway's centreline, its catapult and the helipads. */
export const DECK = {
  runway: { from: 36, to: 185, z: -3 },
  catapult: { x: 120, z: -3 },
  pads: [{ x: 46, z: -16 }, { x: 68, z: -16 }],
  ju87: { x: 110, z: -3 },
};

export const CARRIER = defineTask<Record<string, any>, any>({
  name: "folkways.promo.airport.carrier", side: "server", timeoutMs: 120_000, source: java("Carrier.java"),
});

/** Waits until the carrier's airport, surveyed from its island, offers its runway ends and pads. */
export async function carrierReady(server: MinecraftServer, tower: Vec, timeoutMs = 240_000) {
  const t0 = Date.now();
  let last: any;
  while (Date.now() - t0 < timeoutMs) {
    last = await server.run(AIRPORT, tower);
    const sites = await server.run(CARRIER, { op: "sites" });
    if (!last.scanning && last.flaws.length === 0 && sites.sites.length >= 4) return { ...last, ...sites };
    await sleepMs(2_000);
  }
  throw new Error(`The carrier never came back from its survey: ${JSON.stringify(last)}`);
}

/**
 * A vehicle's controller as the firearms mod keeps it, read for the take's evidence; with `order`, first gives that
 * order straight through the vehicle provider and reports its result (the take's own orders go through the card).
 */
export const CONTROLLER = defineTask<Record<string, any>, any>({
  name: "folkways.promo.airport.controller", side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.combat.api.Vehicles",
      "io.github.izakyl.folkways.core.world.api.WorldPos", "net.minecraft.core.BlockPos"],
    body: `
var a = Args.of(ctx);
var level = ((MinecraftServer) ctx.server()).overworld();
var e = level.getEntity(UUID.fromString(a.string("id")));
var out = new LinkedHashMap<String, Object>();
if (a.has("altitude")) {
  Vehicles.altitude(e, Vehicles.Altitude.valueOf(a.string("altitude")));
}
if (a.has("order")) {
  var points = new ArrayList<WorldPos>();
  if (a.has("point")) { var p = a.object("point"); points.add(WorldPos.of(level, BlockPos.containing(p.number("x"), p.number("y"), p.number("z")))); }
  var order = new Vehicles.Order(Vehicles.Action.valueOf(a.string("order")), points, -1, Optional.empty(),
    a.has("airport") ? Optional.of(a.string("airport")) : Optional.empty());
  out.put("result", String.valueOf(Vehicles.of(e).orElseThrow().order(e, order)));
}
var type = Class.forName("io.github.izakyl.folkways.firearms.warfare.VehicleController");
var of = type.getDeclaredMethod("of", Class.forName("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity"));
of.setAccessible(true);
var controller = of.invoke(null, e);
for (String name : new String[] {"action", "landing", "departure", "launch", "dock", "departed", "touchdown", "liftedFrom"}) {
  var f = type.getDeclaredField(name); f.setAccessible(true);
  out.put(name, String.valueOf(f.get(controller)));
}
var launchField = type.getDeclaredField("launch"); launchField.setAccessible(true);
var launch = launchField.get(controller);
if (launch != null) {
  var shuttleField = launch.getClass().getDeclaredField("shuttle"); shuttleField.setAccessible(true);
  var shuttle = (net.minecraft.world.entity.Entity) shuttleField.get(launch);
  var slackMethod = Class.forName("io.github.izakyl.folkways.firearms.warfare.VehicleCatapults")
    .getDeclaredMethod("slack", Class.forName("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity"));
  slackMethod.setAccessible(true);
  out.put("slack", slackMethod.invoke(null, e));
  if (shuttle != null) {
    out.put("shuttlePosition", shuttle.position().toString());
    out.put("shuttleLook", shuttle.getLookAngle().toString());
    out.put("hookDistance", e.distanceTo(shuttle));
    out.put("hookRear", e.position().subtract(shuttle.position()).dot(shuttle.getLookAngle()));
  }
}
out.put("onGround", e.onGround());
out.put("tickCount", e.tickCount);
out.put("gameTime", level.getGameTime());
out.put("frozen", level.getServer().tickRateManager().isFrozen());
out.put("removed", e.isRemoved());
out.put("removalReason", String.valueOf(e.getRemovalReason()));
out.put("passenger", e.isPassenger());
out.put("vehicle", String.valueOf(e.getVehicle()));
out.put("canTick", level.shouldTickBlocksAt(e.blockPosition()));
out.put("entityTicking", level.isPositionEntityTicking(e.blockPosition()));
out.put("chunk", String.valueOf(level.getChunkAt(e.blockPosition()).getFullStatus()));
out.put("pos", e.position().toString());
out.put("motion", e.getDeltaMovement().toString());
return out;`,
  }),
});
