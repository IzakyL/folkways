import { camera, defineTask, javaTask, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { foundColonyFast, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { FILM_LOOK } from "../shared/capture/shaders";
import { commandPos, frames, tryCommand } from "../shared/harness/bw-helpers";
import type { VisualRun } from "../shared/capture/visual-kit";
import { CLEAR_STAGING_MESSAGES } from "./combat-presentation";
import { cinematicWorld, launchPromoPair, type PromoPair } from "./promo-kit";

// The mounted promo stands on the promo's flat plains with the cart lines and the RTS layer installed. One paddock of
// tame horses (a horse pasture) sits west of a dirt road that runs east to the work and the fighting.
export const MOUNTED_CONFIG = "blockwright.toml#promo-mounted";

export type MountedScene = Awaited<ReturnType<typeof openMountedScene>>;

export async function openMountedScene(scope: string, run: VisualRun, residents: number, founding = { x: 0, z: 120 }) {
  const pair: PromoPair = await launchPromoPair(scope, { config: MOUNTED_CONFIG, look: { options: FILM_LOOK } });
  const { server, client } = pair;
  const grounded = await waitForGroundedPlayer(server);
  const name = grounded.name;
  await world.command(server, `op ${name}`);
  await cinematicWorld(server);
  for (const command of ["difficulty normal", "gamerule doMobSpawning false", "gamerule doFireTick false",
    "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear", "time set 4500"]) {
    await world.command(server, command);
  }
  const o = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
  const at = (x: number, z: number, dy = 0): Vec => ({ x: o.x + x, y: o.y + dy, z: o.z + z });
  await world.command(server, `forceload add ${o.x - 64} ${o.z - 64} ${o.x + 160} ${o.z + 48}`);
  // Beds and the colony stand sit far behind the set, out of every shot.
  const founded = await foundColonyFast(server, client, name, { origin: at(founding.x, founding.z), residentCount: residents });
  // Finish the founders' existing food collection before placing the cast on the set.
  await tick.sprint(server, 200);
  // The colony book stays in the inventory, not the hand: held, it draws every resident's card over its head.
  await world.command(server, `item replace entity ${name} hotbar.8 from entity ${name} weapon.mainhand`);
  await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
  const colony = founded.evidence.colonyId as string;
  await cinematicWorld(server);
  await world.command(server, "time set 4500");
  run.note("site", { origin: o, colony, founding: founded.evidence.finalResidents });
  return { pair, server, client, name, o, at, colony };
}

/** The paddock: an oak fence round a horse pasture, open three blocks wide on its east side towards the road. */
export async function buildPaddock(scene: MountedScene, x0: number, z0: number, x1: number, z1: number) {
  const { server, at } = scene;
  const fill = (a: Vec, b: Vec, block: string) => world.command(server, `fill ${commandPos(a)} ${commandPos(b)} ${block}`);
  await fill(at(x0, z0), at(x1, z0), "minecraft:oak_fence");
  await fill(at(x0, z1), at(x1, z1), "minecraft:oak_fence");
  await fill(at(x0, z0), at(x0, z1), "minecraft:oak_fence");
  await fill(at(x1, z0), at(x1, z1), "minecraft:oak_fence");
  // The side towards the road stands open, so the horses walk straight out.
  await fill(at(x1, z0 + 1), at(x1, z1 - 1), "minecraft:air");
  // Hay and a trough inside, so it reads as a horse paddock.
  await world.command(server, `setblock ${commandPos(at(x0 + 1, z0 + 1))} minecraft:hay_block`);
  await world.command(server, `setblock ${commandPos(at(x0 + 1, z0 + 2))} minecraft:hay_block`);
  await world.command(server, `setblock ${commandPos(at(x0 + 2, z0 + 1))} minecraft:hay_block`);
  await world.command(server, `setblock ${commandPos(at(x0 + 1, z1 - 1))} minecraft:water_cauldron[level=3]`);
  return { min: at(x0 + 1, z0 + 1, -1), max: at(x1 - 1, z1 - 1, -1) };
}

export async function road(scene: MountedScene, x0: number, x1: number, z: number, half = 1) {
  const { server, at } = scene;
  await world.command(server, `fill ${commandPos(at(x0, z - half, -1))} ${commandPos(at(x1, z + half, -1))} minecraft:dirt_path`);
}

/** Tame grown horses, summoned with fixed speed and coats, standing in the paddock. */
export async function summonHorses(scene: MountedScene, spots: Vec[], still = true) {
  const variants = [0, 257, 514, 771, 1028, 1, 258, 3, 516];
  const ids: string[] = [];
  for (const [i, spot] of spots.entries()) {
    const tag = `promo_horse_${i}`;
    await world.command(scene.server, `summon minecraft:horse ${spot.x + .5} ${spot.y} ${spot.z + .5} ` +
      `{Tame:1b,Age:0,NoAI:${still ? 1 : 0}b,PersistenceRequired:1b,Variant:${variants[i % variants.length]},Tags:["${tag}"],Rotation:[-90f,0f],` +
      `attributes:[{id:"minecraft:generic.movement_speed",base:0.27d}]}`);
    ids.push(tag);
  }
  return ids;
}

export async function markMembers(scene: MountedScene, cells: Vec[]) {
  return runColonyTask(scene.server, MARK_MEMBERS, {
    player: scene.name, colony_id: scene.colony, cells: cells.flatMap((p) => [p.x, p.y, p.z]),
  });
}

/** A fixed shot: the player a spectator above the subject (so its chunks and entities stay sent), the free camera set. */
export async function standShot(scene: MountedScene, eye: Vec, target: Vec, fov = 70, commander?: Vec) {
  const look = cameraLookingAt(eye, target);
  if (commander) {
    // A creative commander stands on an invisible barrier high above and behind the camera: inside the ground its
    // eyes would black out the frame.
    await world.command(scene.server, `gamemode creative ${scene.name}`);
    await tryCommand(scene.server, `setblock ${commandPos({ ...commander, y: commander.y - 1 })} minecraft:barrier`);
    await world.command(scene.server, `tp ${scene.name} ${commander.x + .5} ${commander.y} ${commander.z + .5}`);
  } else {
    await world.command(scene.server, `gamemode spectator ${scene.name}`);
    await world.command(scene.server, `tp ${scene.name} ${target.x} ${target.y + 24} ${target.z} ${look.yaw} ${look.pitch}`);
  }
  await camera.reset(scene.client);
  await camera.set(scene.client, { position: eye, ...look, fov });
  await camera.hud(scene.client, false);
  await scene.client.run(CLEAR_STAGING_MESSAGES);
  await frames(scene.client, 30);
  return { eye, ...look, fov };
}

export type Pose = { position: Vec; yaw: number; pitch: number };

/**
 * Dresses the bare plains: grass and flowers scattered over the ground and trees in a loose band, all kept off the
 * boxes the set stands on (and a margin round them).
 */
export async function dressLand(scene: MountedScene, area: { x0: number; z0: number; x1: number; z1: number },
  keepClear: Array<{ x0: number; z0: number; x1: number; z1: number }>, trees: Array<{ x: number; z: number; kind: string }>) {
  const { server, at } = scene;
  const result = await server.run(DRESS, { y: scene.o.y, x0: scene.o.x + area.x0, z0: scene.o.z + area.z0,
    x1: scene.o.x + area.x1, z1: scene.o.z + area.z1,
    clear: keepClear.map((b) => ({ x0: scene.o.x + b.x0 - 1, z0: scene.o.z + b.z0 - 1, x1: scene.o.x + b.x1 + 1, z1: scene.o.z + b.z1 + 1 })) });
  for (const tree of trees) {
    await tryCommand(server, `place feature minecraft:${tree.kind} ${commandPos(at(tree.x, tree.z))}`);
  }
  return result;
}

const DRESS = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-dress-land",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.level.block.Blocks"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var random = new java.util.Random(7);
int y = (int) a.integer("y"); int placed = 0;
var clear = a.list("clear");
var flowers = List.of(Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET);
for (int x = (int) a.integer("x0"); x <= a.integer("x1"); x++) {
  for (int z = (int) a.integer("z0"); z <= a.integer("z1"); z++) {
    boolean kept = false;
    for (var raw : clear) {
      var b = (Map<?, ?>) raw;
      if (x >= ((Number) b.get("x0")).intValue() && x <= ((Number) b.get("x1")).intValue()
          && z >= ((Number) b.get("z0")).intValue() && z <= ((Number) b.get("z1")).intValue()) { kept = true; break; }
    }
    if (kept) continue;
    var at = new BlockPos(x, y, z);
    if (!level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK) || !level.getBlockState(at).isAir()) continue;
    double r = random.nextDouble();
    if (r < .22) { level.setBlock(at, Blocks.SHORT_GRASS.defaultBlockState(), 2); placed++; }
    else if (r < .245) { level.setBlock(at, flowers.get(random.nextInt(flowers.size())).defaultBlockState(), 2); placed++; }
  }
}
return Map.of("placed", placed);`,
  }),
});

export async function flyTo(scene: MountedScene, from: Pose, eye: Vec, target: Vec, fov: number, ms: number) {
  const look = cameraLookingAt(eye, target);
  const next = { position: eye, yaw: look.yaw, pitch: look.pitch };
  await camera.fly(scene.client, { poses: [from, next], durationMs: ms, fov });
  return next;
}

const IMPORTS = [
  "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.terms.ItemSpec",
  "io.github.izakyl.folkways.core.api.resident.body.Bodies",
  "io.github.izakyl.folkways.core.api.vocation.Vocations",
  "io.github.izakyl.folkways.core.front.engine.colony.ColonyFront",
  "io.github.izakyl.folkways.core.front.engine.colony.ColonySettings",
  "io.github.izakyl.folkways.vanilla.person.ResidentEntity",
  "io.github.izakyl.folkways.vanilla.pasture.PastureContent",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles",
  "io.github.izakyl.folkways.vanilla.combat.weapon.ResidentMounts",
  "net.minecraft.core.BlockPos",
  "net.minecraft.core.registries.BuiltInRegistries",
  "net.minecraft.resources.ResourceLocation",
  "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.world.entity.ai.attributes.Attributes",
  "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.item.Items",
];

/** Draws a horse pasture zone over the paddock floor, as the book would. */
export const DRAW_PASTURE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-pasture",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var min = (Map<?, ?>) a.value("min"); var max = (Map<?, ?>) a.value("max");
var cells = new LinkedHashSet<BlockPos>();
for (var p : BlockPos.betweenClosed(pos(min), pos(max))) cells.add(p.immutable());
var zone = ColonyFront.of(colony).addZone(level, PastureContent.PASTURE, cells, ColonySettings.empty()
    .with(PastureContent.ANIMAL.key(), new ColonySettings.Value.Choice(ResourceLocation.withDefaultNamespace("horse"))))
    .orElseThrow(() -> new IllegalStateException("pasture refused"));
return Map.of("zone", zone.id().toString(), "cells", cells.size());`,
    members: `
static BlockPos pos(Map<?, ?> p) {
  return new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
}`,
  }),
});

/**
 * Hands each named resident a loadout profile with a mount (and whatever else its row asks for), puts the real saddle,
 * horse armour, weapon and arrows in its pack, takes every licence away and stands it at its spot.
 */
export const OUTFIT_RIDERS = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-outfit",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var profiles = LoadoutProfiles.of(colony);
var out = new ArrayList<Object>();
for (var raw : a.list("riders")) {
  var row = (Map<?, ?>) raw;
  var body = (ResidentEntity) level.getEntity(UUID.fromString(String.valueOf(row.get("uuid"))));
  var b = Bodies.of(body).orElseThrow();
  for (var vocation : Vocations.all()) b.licences().trade(vocation).ifPresent(t -> b.licences().allow(t, false));
  var entries = new ArrayList<LoadoutProfile.Entry>();
  entries.add(LoadoutProfile.Entry.mount());
  body.pack().insert(new ItemStack(Items.SADDLE));
  if (row.get("armor") != null) {
    var armor = BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(row.get("armor"))));
    entries.add(LoadoutProfile.Entry.horseArmor(ItemSpec.of(armor)));
    body.pack().insert(new ItemStack(armor));
  }
  if (row.get("weapon") != null) {
    var weapon = BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(row.get("weapon"))));
    entries.add(LoadoutProfile.Entry.weapon(ItemSpec.of(weapon)));
    body.pack().insert(new ItemStack(weapon));
    if (weapon == Items.BOW || weapon == Items.CROSSBOW) {
      entries.add(LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 64, 1));
      body.pack().insert(new ItemStack(Items.ARROW, 64));
    }
    body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
    body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
    body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
    body.setHealth(200);
    body.addTag("draft_crew");
  }
  for (var raw2 : (List<?>) row.get("tools")) {
    var t = String.valueOf(raw2).split(" ");
    body.pack().insert(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(t[0])), t.length > 1 ? Integer.parseInt(t[1]) : 1));
  }
  var created = profiles.create(String.valueOf(row.get("profile")));
  profiles.replace(new LoadoutProfile(created.id(), created.name(), entries));
  profiles.assign(List.of(body.id()), Optional.of(created.id()));
  var home = (Map<?, ?>) row.get("home");
  body.teleportTo(((Number) home.get("x")).doubleValue() + .5, ((Number) home.get("y")).doubleValue(),
      ((Number) home.get("z")).doubleValue() + .5);
  body.setYRot(((Number) row.get("yaw")).floatValue()); body.setYHeadRot(((Number) row.get("yaw")).floatValue());
  body.getNavigation().stop();
  out.add(Map.of("uuid", body.id().toString(), "profile", created.id().toString(), "entries", entries.size()));
}
return out;`,
  }),
});

/** Every named resident and its horse as the colony holds it: where they are, who rides what, what each is doing. */
export const RIDERS = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-riders",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var rows = new ArrayList<Object>();
for (var raw : a.list("uuids")) {
  var entity = level.getEntity(UUID.fromString(String.valueOf(raw)));
  if (!(entity instanceof ResidentEntity body)) { rows.add(Map.of("uuid", String.valueOf(raw), "missing", true)); continue; }
  var row = new LinkedHashMap<String, Object>();
  row.put("uuid", body.id().toString());
  row.put("x", body.getX()); row.put("y", body.getY()); row.put("z", body.getZ());
  var horse = ResidentMounts.horse(body);
  row.put("riding", horse.isPresent() && body.getVehicle() == horse.get());
  row.put("passenger", body.isPassenger());
  row.put("horse", horse.map(h -> Map.of("uuid", h.getUUID().toString(), "x", h.getX(), "y", h.getY(), "z", h.getZ(),
      "saddled", h.isSaddled(), "armor", h.getItemBySlot(EquipmentSlot.BODY).toString(), "parked", h.isNoAi()))
      .orElse(null));
  row.put("locomotion", body.locomotion().id().toString());
  row.put("doing", body.doing().map(d -> d.toward().orElse(d.what()).toString()).orElse("idle"));
  row.put("hand", body.getMainHandItem().toString());
  var data = body.getPersistentData();
  row.put("hits", data.getInt("hits")); row.put("mountedHits", data.getInt("mountedHits"));
  row.put("mountedMelee", data.getInt("mountedMelee")); row.put("mountedShots", data.getInt("mountedShots"));
  row.put("kills", data.getInt("kills"));
  rows.add(row);
}
return Map.of("time", level.getGameTime(), "riders", rows);`,
  }),
});

/** Counts each crew member's hits on the wave, and which of them landed from the saddle, melee or by projectile. */
export const OBSERVE_MOUNTED_HITS = defineTask<Record<string, never>, any>({
  name: "folkways.promo.mounted-hit-observer",
  side: "server",
  source: javaTask({
    body: `
java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> hit = event -> {
    var shooter = event.getSource().getEntity();
    if (shooter != null && shooter.getTags().contains("draft_crew")
        && event.getEntity().getTags().contains("draft_attacker") && event.getNewDamage() > 0) {
        var data = shooter.getPersistentData();
        data.putInt("hits", data.getInt("hits") + 1);
        if (shooter.getVehicle() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse) {
            data.putInt("mountedHits", data.getInt("mountedHits") + 1);
            if (event.getSource().getDirectEntity() == shooter) data.putInt("mountedMelee", data.getInt("mountedMelee") + 1);
            else data.putInt("mountedShots", data.getInt("mountedShots") + 1);
        }
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(hit);
java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> death = event -> {
    var shooter = event.getSource().getEntity();
    if (shooter != null && shooter.getTags().contains("draft_crew") && event.getEntity().getTags().contains("draft_attacker")) {
        var data = shooter.getPersistentData();
        data.putInt("kills", data.getInt("kills") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(death);
return true;`,
  }),
});

/** Pumpkin-headed zombies (sunproof) walking in from the east, followed far enough to find the settlement. */
export const WAVE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-wave",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var ids = new ArrayList<String>();
for (var raw : a.list("at")) {
  var p = (Map<?, ?>) raw;
  var mob = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
  mob.moveTo(((Number) p.get("x")).doubleValue() + .5, ((Number) p.get("y")).doubleValue(), ((Number) p.get("z")).doubleValue() + .5, 90, 0);
  mob.setBaby(false);
  mob.setPersistenceRequired();
  mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(a.number("speed"));
  mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(96);
  mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(a.number("health"));
  mob.setHealth((float) a.number("health"));
  mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
  mob.addTag("draft_attacker");
  level.addFreshEntity(mob);
  ids.add(mob.getUUID().toString());
}
return Map.of("ids", ids);`,
  }),
});

export const ENEMIES = defineTask<Record<string, never>, any>({
  name: "folkways.promo.mounted-enemies",
  side: "server",
  source: javaTask({
    body: `
var level = ctx.server().overworld();
var rows = new ArrayList<Object>();
for (var e : level.getAllEntities()) {
  if (e.getTags().contains("draft_attacker") && e instanceof net.minecraft.world.entity.LivingEntity l && l.isAlive()) {
    rows.add(Map.of("uuid", e.getUUID().toString(), "x", e.getX(), "y", e.getY(), "z", e.getZ(), "health", l.getHealth()));
  }
}
return rows;`,
  }),
});

export const ROSTER = defineTask<Record<string, any>, string[]>({
  name: "folkways.promo.mounted-roster",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
return colony.residents().stream().map(r -> r.id().toString()).toList();`,
  }),
});

export async function riders(server: MinecraftServer, uuids: string[]) {
  return server.run(RIDERS, { uuids });
}

export async function settle(server: MinecraftServer, ticks: number) {
  await tick.sprint(server, ticks);
}

export async function quietClient(client: MinecraftClient) {
  await client.run(CLEAR_STAGING_MESSAGES);
}

export { tryCommand };

/** The field is delegated as a farm zone; visible crops alone do not schedule farming work. */
export const DRAW_FIELD = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-field",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.vanilla.farming.FarmingContent",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var min = a.blockPos("min");
var max = a.blockPos("max");
var cells = new LinkedHashSet<BlockPos>();
for (var cell : BlockPos.betweenClosed(min, max)) {
  if (level.getBlockState(cell).is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
    cells.add(cell.immutable());
  }
}
var zone = ColonyFront.of(colony).addZone(level, FarmingContent.PLOT, cells,
    ColonySettings.empty().with(FarmingContent.CROP,
        new ColonySettings.Value.Choice(ResourceLocation.withDefaultNamespace("wheat")))).orElseThrow();
return Map.of("zone", zone.id().toString(), "cells", cells.size());`,
  }),
});
