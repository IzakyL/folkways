import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { BIND_COLONY, MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { BUDGET } from "../shared/harness/budgets";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { layFloor } from "../shared/combat/overhead";

type Scene = { colony: string; resident: string; stockX: number; stockY: number; stockZ: number; homeX: number; homeZ: number };
type Reading = {
  x: number; z: number; health: number; stockBows: number; stockArrows: number;
  carriedBows: number; arrows: number; bowDamage: number; session: boolean;
  order: string; mode: string; status: string; supply: string; reason: string;
  sourceDistance: number; targetHealth: number; hitByResident: boolean; bowAllowed: boolean;
  stockSwords: number; carriedSwords: number; swordDamage: number; reservedAtSource: number;
};

test("guards walk to real registered storage for their kit: a melee guard collects the unique sword and fights, a bow guard a bounded bow loadout and fires", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { instance: `combat-supplies-server-${run}` },
      client: { instance: `combat-supplies-client-${run}` },
      snapshot: () => ({ containers: scene ? [{ x: scene.stockX, y: scene.stockY, z: scene.stockZ }] : [], entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:arrow"] }),
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 5, y = Math.round(player.pos.y), z = Math.round(player.pos.z) + 5;
    const stock = { x: x - 5, y, z };
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "gamerule doDaylightCycle false");
    await world.command(server, "time set midnight");
    await world.command(server, "difficulty normal");
    await layFloor(server, { x: x - 10, z: z - 8 }, { x: x + 25, z: z + 8 }, y);
    await world.command(server, `item replace entity ${player.name} weapon.mainhand with folkways:colony_book`);
    const colony = String((await runColonyTask(server, BIND_COLONY, { player: player.name })).colony_id);
    await world.command(server, `setblock ${stock.x} ${y} ${z} minecraft:chest`);
    await world.command(server, `item replace block ${stock.x} ${y} ${z} container.0 with minecraft:iron_sword`);
    await world.command(server, `tp ${player.name} ${stock.x + .5} ${y} ${z - 2.5}`);
    await runColonyTask(server, MARK_MEMBERS, { player: player.name, colony_id: colony, cells: [stock.x, y, z] });
    await world.command(server, `gamemode spectator ${player.name}`);
    const site = { colony, stockX: stock.x, stockY: y, stockZ: z };

    // Melee: an unassigned resident claims nothing; once guarding under the melee profile it collects the only sword.
    scene = await server.run(SETUP, { ...site, homeX: x + 16.5, homeZ: z + .5, bow: false });
    const melee: Reading[] = [];
    const meleeWait = watching(server, scene, melee, value => {
      expect(value.health).toBe(20);
      expect(value.stockSwords + value.carriedSwords, "the unique sword must have one real owner through profile collection").toBe(1);
    });
    const unassigned = await server.run(READ, scene);
    expect(unassigned.mode).toBe("NONE");
    expect(unassigned.carriedSwords).toBe(0);
    expect(unassigned.stockSwords).toBe(1);
    expect(unassigned.reservedAtSource, "unassigned residents do not infer a combat kit claim").toBe(0);
    await server.run(GUARD_MELEE, scene);
    const meleeOrder = (await server.run(READ, scene)).order;
    expect(meleeOrder).not.toBe("NONE");
    const armed = await meleeWait("GUARD collects its assigned melee profile", value => value.carriedSwords === 1
      && value.stockSwords === 0 && value.mode === "GUARD" && value.status === "HOLDING"
      && Math.hypot(value.x - scene!.homeX, value.z - scene!.homeZ) < 1.1);
    expect(armed.order).toBe(meleeOrder);
    expect(armed.reservedAtSource).toBe(0);
    expect(melee.some(value => value.supply === "FETCHING")).toBe(true);
    expect(Math.min(...melee.map(value => value.sourceDistance))).toBeLessThan(5.0);

    await server.run(TARGET, scene);
    const fought = await meleeWait("the normally controlled melee guard actually strikes", value => value.hitByResident && value.targetHealth < 100 && value.swordDamage > 0);
    expect(fought.mode).toBe("GUARD");
    expect(fought.order).toBe(meleeOrder);
    await server.run(RETREAT, scene);
    const stopped = await meleeWait("retreat closes the actual melee hand", value => value.mode === "RETREAT" && !value.session, 80);
    expect(stopped.carriedSwords).toBe(1);
    for (let elapsed = 0; elapsed < 40; elapsed += 4) {
      await tick.sprint(server, 4);
      const value = await server.run(READ, scene);
      expect(value.mode).toBe("RETREAT");
      expect(value.carriedSwords, "retreat keeps the unique equipment").toBe(1);
      expect(value.stockSwords).toBe(0);
      expect(value.swordDamage).toBe(stopped.swordDamage);
    }
    await server.run(CLEANUP, { ...scene, raze: false });

    // Bow: the guard fetches one bow and only the 8 of 16 arrows its profile keeps, then hits with them.
    await world.command(server, `item replace block ${stock.x} ${y} ${z} container.1 with minecraft:bow`);
    await world.command(server, `item replace block ${stock.x} ${y} ${z} container.2 with minecraft:arrow 16`);
    scene = await server.run(SETUP, { ...site, homeX: x + 8.5, homeZ: z + .5, bow: true });
    const bow: Reading[] = [];
    const bowWait = watching(server, scene, bow, value => {
      expect(value.health).toBe(20);
      expect(value.stockBows + value.carriedBows, "the native bow must have exactly one owner").toBe(1);
    });
    const initial = await server.run(READ, scene);
    expect(initial.carriedBows).toBe(0);
    expect(initial.arrows).toBe(0);
    expect(initial.stockBows).toBe(1);
    expect(initial.stockArrows).toBe(16);
    expect(initial.bowAllowed, `combat equipment is independent of trade tools: ${JSON.stringify(initial)}`).toBe(false);
    const bowOrder = initial.order;
    expect(bowOrder).not.toBe("NONE");
    const equipped = await bowWait("real supply trip and return", value => value.carriedBows === 1 && value.arrows === 8
      && value.stockArrows === 8 && value.mode === "GUARD" && value.status === "HOLDING"
      && Math.hypot(value.x - scene!.homeX, value.z - scene!.homeZ) < 1.1);
    expect(equipped.order).toBe(bowOrder);
    expect(bow.some(value => value.supply === "FETCHING")).toBe(true);
    expect(Math.min(...bow.map(value => value.sourceDistance)), "the guard must physically reach the actual warehouse").toBeLessThan(5.0);
    expect(Math.min(...bow.map(value => value.x)), "the stock cannot be remotely inserted into the guard").toBeLessThan(initial.x - 4);

    await server.run(TARGET, scene);
    const hit = await bowWait("real bow hit after resupply", value => value.hitByResident && value.targetHealth < 100 && value.bowDamage > 0);
    expect(hit.mode).toBe("GUARD");
    expect(hit.order).toBe(bowOrder);
    expect(hit.stockArrows + hit.arrows).toBeLessThan(16);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && scene) { try { await pair.server.run(CLEANUP, { ...scene, raze: true }); } catch {} }
    await pair?.teardown(testInfo);
  }
});

// Samples one guard every 4 ticks into `seen` until `done`, holding `invariant` on every sample.
function watching(server: MinecraftServer, scene: Scene, seen: Reading[], invariant: (value: Reading) => void) {
  return async (stage: string, done: (value: Reading) => boolean, budget = 640): Promise<Reading> => {
    for (let elapsed = 0; elapsed < budget; elapsed += 4) {
      await tick.sprint(server, 4);
      const value = await server.run(READ, scene);
      seen.push(value);
      invariant(value);
      if (done(value)) return value;
    }
    throw new Error(`${stage} timed out: ${JSON.stringify(seen.at(-1))}`);
  };
}

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.Container",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.Items",
  "net.minecraft.world.phys.Vec3", "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.terms.WorldSpaces", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles", "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.core.api.terms.ItemSpec", "net.neoforged.neoforge.common.Tags",
  "io.github.izakyl.folkways.rts.combat.command.ResidentCommands", "io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies",
];

const SETUP = defineTask<Omit<Scene, "resident"> & { bow: boolean }, Scene>({
  name: "folkways.combat.supplies.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var resident = PersonBody.RESIDENT.get().create(level);
resident.moveTo(args.number("homeX"), args.number("stockY"), args.number("homeZ"));
resident.setPersistenceRequired();
level.addFreshEntity(resident);
colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow();
resident.joinColony(colony.id());
if (args.flag("bow", false)) {
  var profiles = LoadoutProfiles.of(colony);
  var bow = profiles.add(profiles.create("Bow").id(), LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)));
  profiles.assign(List.of(resident.id()), Optional.of(bow.id()));
  ResidentCommands.of(colony).guard(resident, WorldSpaces.at(resident), new Vec3(1, 0, 0));
}
return Map.of("colony", args.string("colony"), "resident", resident.id().toString(),
 "stockX", args.number("stockX"), "stockY", args.number("stockY"), "stockZ", args.number("stockZ"),
 "homeX", args.number("homeX"), "homeZ", args.number("homeZ"));
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.combat.supplies.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var body = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
var stockAt = new BlockPos((int)args.integer("stockX"), (int)args.integer("stockY"), (int)args.integer("stockZ"));
var store = (Container)level.getBlockEntity(stockAt);
int stockBows = 0, stockArrows = 0, stockSwords = 0;
for (int slot = 0; slot < store.getContainerSize(); slot++) {
 var item = store.getItem(slot); if (item.is(Items.BOW)) stockBows += item.getCount(); if (item.is(Items.ARROW)) stockArrows += item.getCount();
 if (item.is(Items.IRON_SWORD)) stockSwords += item.getCount();
}
boolean session = body.hasWeaponSession(); var hand = body.getMainHandItem();
int bows = body.pack().count(Items.BOW) + (session && hand.is(Items.BOW) ? hand.getCount() : 0);
int damage = session && hand.is(Items.BOW) ? hand.getDamageValue()
 : body.pack().contents().stream().filter(item -> item.is(Items.BOW)).mapToInt(item -> item.getDamageValue()).findFirst().orElse(0);
int swords = body.pack().count(Items.IRON_SWORD) + (session && hand.is(Items.IRON_SWORD) ? hand.getCount() : 0);
int swordDamage = session && hand.is(Items.IRON_SWORD) ? hand.getDamageValue()
 : body.pack().contents().stream().filter(item -> item.is(Items.IRON_SWORD)).mapToInt(item -> item.getDamageValue()).findFirst().orElse(0);
var order = ResidentCommands.of(colony).current(body.id()); var state = PatrolSupplies.of(colony).status(body.id());
var target = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, body.getBoundingBox().inflate(32))
 .stream().findFirst().orElse(null);
return Map.ofEntries(Map.entry("x", body.getX()), Map.entry("z", body.getZ()), Map.entry("health", body.getHealth()),
 Map.entry("stockBows", stockBows), Map.entry("stockArrows", stockArrows), Map.entry("carriedBows", bows),
 Map.entry("arrows", body.pack().count(Items.ARROW)), Map.entry("bowDamage", damage), Map.entry("session", session),
 Map.entry("order", order.map(value -> value.id().toString()).orElse("NONE")), Map.entry("mode", order.map(value -> value.mode().name()).orElse("NONE")),
 Map.entry("status", order.map(value -> value.status().name()).orElse("NONE")), Map.entry("supply", state.state().name()), Map.entry("reason", state.reason()),
 Map.entry("sourceDistance", body.position().distanceTo(Vec3.atCenterOf(stockAt))), Map.entry("targetHealth", target == null ? 100 : target.getHealth()),
 Map.entry("hitByResident", target != null && target.getLastHurtByMob() == body),
 Map.entry("bowAllowed", io.github.izakyl.folkways.core.api.vocation.KitRules.allows(colony, io.github.izakyl.folkways.core.api.vocation.Vocations.required(io.github.izakyl.folkways.plugins.patrol.PatrolContent.VOCATION), Items.BOW)),
 Map.entry("stockSwords", stockSwords), Map.entry("carriedSwords", swords), Map.entry("swordDamage", swordDamage),
 Map.entry("reservedAtSource", colony.reservedInputs(level).stream().filter(claim -> claim.store()
  .filter(WorldSpaces.at(level, stockAt)::equals).isPresent()).mapToLong(claim -> claim.count()).sum()));
` }),
});

const GUARD_MELEE = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.supplies.melee.guard", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var body = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
var profiles = LoadoutProfiles.of(colony);
var melee = profiles.add(profiles.create("Melee").id(), LoadoutProfile.Entry.weapon(ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS)));
profiles.assign(List.of(body.id()), Optional.of(melee.id()));
ResidentCommands.of(colony).guard(body, WorldSpaces.at(level,
 new BlockPos((int)Math.floor(args.number("homeX")), (int)args.integer("stockY"), (int)Math.floor(args.number("homeZ")))), new Vec3(1, 0, 0));
return Map.of("done", true);
` }),
});

const TARGET = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.supplies.target", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var level = ((MinecraftServer)ctx.server()).overworld();
var target = EntityType.ZOMBIE.create(level); target.setNoAi(true); target.setPersistenceRequired();
target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
target.moveTo(args.number("homeX") + 6, args.number("stockY"), args.number("homeZ")); level.addFreshEntity(target);
return Map.of("done", true);
` }),
});

const RETREAT = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.supplies.retreat", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var level = ((MinecraftServer)ctx.server()).overworld();
var body = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
ResidentCommands.of(colony).retreat(body, WorldSpaces.at(level, new BlockPos((int)args.number("homeX"), (int)args.number("stockY"), (int)args.number("homeZ") + 5)));
return Map.of("done", true);
` }),
});

// Discards the guard and the zombies around its post; the colony too when `raze`.
const CLEANUP = defineTask<Scene & { raze: boolean }, { done: boolean }>({
  name: "folkways.combat.supplies.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var body = level.getEntity(UUID.fromString(args.string("resident"))); if (body != null) body.discard();
for (var enemy : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class,
 new net.minecraft.world.phys.AABB(args.number("homeX") - 32, args.number("stockY") - 4, args.number("homeZ") - 32,
 args.number("homeX") + 32, args.number("stockY") + 8, args.number("homeZ") + 32))) enemy.discard();
if (args.flag("raze", false)) Colonies.of(server, UUID.fromString(args.string("colony"))).ifPresent(colony -> colony.raze());
return Map.of("done", true);
` }),
});
