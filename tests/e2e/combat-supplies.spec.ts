import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { BIND_COLONY, MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";

type Scene = { colony: string; resident: string; stockX: number; stockY: number; stockZ: number; homeX: number; homeZ: number };
type Reading = {
  x: number; z: number; health: number; stockBows: number; stockArrows: number;
  carriedBows: number; arrows: number; bowDamage: number; session: boolean;
  order: string; mode: string; status: string; supply: string; reason: string;
  sourceDistance: number; targetHealth: number; hitByResident: boolean;
  bowAllowed: boolean; crossbowAllowed: boolean; tools: string; defaultTools: string;
  patrolKit: string; readableBows: number; readableArrows: number; claims: string; weaponCode: string;
  stockSwords: number; carriedSwords: number; swordDamage: number; reservedAtSource: number;
};

test("a guard walks to real registered storage for a bounded bow loadout, fires and cancels replenishment on retreat", async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  const readings: Array<{ stage: string; tick: number; value: Reading }> = [];
  try {
    const run = newRunId();
    pair = await launchPair({
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
    await world.command(server, `fill ${x - 10} ${y - 1} ${z - 8} ${x + 22} ${y - 1} ${z + 8} minecraft:stone`);
    await world.command(server, `fill ${x - 10} ${y} ${z - 8} ${x + 22} ${y + 5} ${z + 8} minecraft:air`, { allowFailure: true });
    await world.command(server, `item replace entity ${player.name} weapon.mainhand with folkways:colony_book`);
    const colony = String((await runColonyTask(server, BIND_COLONY, { player: player.name })).colony_id);
    await world.command(server, `setblock ${stock.x} ${y} ${z} minecraft:chest`);
    await world.command(server, `item replace block ${stock.x} ${y} ${z} container.0 with minecraft:bow`);
    await world.command(server, `item replace block ${stock.x} ${y} ${z} container.1 with minecraft:arrow 16`);
    await world.command(server, `tp ${player.name} ${stock.x + .5} ${y} ${z - 2.5}`);
    await runColonyTask(server, MARK_MEMBERS, { player: player.name, colony_id: colony, cells: [stock.x, y, z] });
    await world.command(server, `gamemode spectator ${player.name}`);
    scene = await server.run(SETUP, { colony, stockX: stock.x, stockY: y, stockZ: z, homeX: x + 8.5, homeZ: z + .5 });
    const initial = await server.run(READ, scene);
    readings.push({ stage: "guard starts empty", tick: 0, value: initial });
    expect(initial.carriedBows).toBe(0);
    expect(initial.arrows).toBe(0);
    expect(initial.stockBows).toBe(1);
    expect(initial.stockArrows).toBe(16);
    expect(initial.bowAllowed, `fresh colony tool permission: ${JSON.stringify(initial)}`).toBe(true);
    const order = initial.order;
    expect(order).not.toBe("NONE");

    async function waitFor(stage: string, predicate: (value: Reading) => boolean, budget = 640): Promise<Reading> {
      for (let elapsed = 0; elapsed < budget; elapsed += 4) {
        await tick.sprint(server, 4);
        const value = await server.run(READ, scene!);
        readings.push({ stage, tick: elapsed + 4, value });
        expect(value.health).toBe(20);
        expect(value.stockBows + value.carriedBows, "the native bow must have exactly one owner").toBe(1);
        if (predicate(value)) return value;
      }
      throw new Error(`${stage} timed out: ${JSON.stringify(readings.at(-1))}`);
    }

    const equipped = await waitFor("real supply trip and return", value => value.carriedBows === 1 && value.arrows === 8
      && value.stockArrows === 8 && value.mode === "GUARD" && value.status === "HOLDING"
      && Math.hypot(value.x - scene!.homeX, value.z - scene!.homeZ) < 1.1);
    expect(equipped.order).toBe(order);
    expect(readings.some(row => row.value.supply === "FETCHING")).toBe(true);
    expect(Math.min(...readings.map(row => row.value.sourceDistance)), "the guard must physically reach the actual warehouse").toBeLessThan(4.6);
    expect(Math.min(...readings.map(row => row.value.x)), "the stock cannot be remotely inserted into the guard").toBeLessThan(initial.x - 4);

    await server.run(TARGET, scene);
    const hit = await waitFor("real bow hit after resupply", value => value.hitByResident && value.targetHealth < 100 && value.bowDamage > 0);
    expect(hit.mode).toBe("GUARD");
    expect(hit.order).toBe(order);
    expect(hit.stockArrows + hit.arrows).toBeLessThan(16);
    await server.run(RETREAT, scene);
    const stopped = await waitFor("retreat revokes firing and supply authority", value => value.mode === "RETREAT" && !value.session, 80);
    for (let elapsed = 0; elapsed < 80; elapsed += 4) {
      await tick.sprint(server, 4);
      const value = await server.run(READ, scene);
      readings.push({ stage: "retreat remains in control", tick: elapsed + 4, value });
      expect(value.mode).toBe("RETREAT");
      expect(value.session).toBe(false);
      expect(value.stockArrows).toBe(stopped.stockArrows);
      expect(value.arrows).toBe(stopped.arrows);
      expect(value.carriedBows).toBe(1);
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("combat-supplies-readings.json", { body: JSON.stringify(readings, null, 2), contentType: "application/json" });
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch {} }
    await pair?.teardown(testInfo);
  }
});

test("a single empty melee guard withdraws its previous kit claim, collects the unique sword and actually fights", async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  const readings: Array<{ stage: string; tick: number; value: Reading }> = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `melee-supplies-server-${run}` },
      client: { instance: `melee-supplies-client-${run}` },
      snapshot: () => ({ containers: scene ? [{ x: scene.stockX, y: scene.stockY, z: scene.stockZ }] : [], entityTypes: ["folkways:resident", "minecraft:zombie"] }),
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 5, y = Math.round(player.pos.y), z = Math.round(player.pos.z) + 5;
    const stock = { x: x - 5, y, z };
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "gamerule doDaylightCycle false");
    await world.command(server, "time set midnight");
    await world.command(server, "difficulty normal");
    await world.command(server, `fill ${x - 10} ${y - 1} ${z - 8} ${x + 25} ${y - 1} ${z + 8} minecraft:stone`);
    await world.command(server, `fill ${x - 10} ${y} ${z - 8} ${x + 25} ${y + 5} ${z + 8} minecraft:air`, { allowFailure: true });
    await world.command(server, `item replace entity ${player.name} weapon.mainhand with folkways:colony_book`);
    const colony = String((await runColonyTask(server, BIND_COLONY, { player: player.name })).colony_id);
    await world.command(server, `setblock ${stock.x} ${y} ${z} minecraft:chest`);
    await world.command(server, `item replace block ${stock.x} ${y} ${z} container.0 with minecraft:iron_sword`);
    await world.command(server, `tp ${player.name} ${stock.x + .5} ${y} ${z - 2.5}`);
    await runColonyTask(server, MARK_MEMBERS, { player: player.name, colony_id: colony, cells: [stock.x, y, z] });
    await world.command(server, `gamemode spectator ${player.name}`);
    scene = await server.run(SETUP_MELEE, { colony, stockX: stock.x, stockY: y, stockZ: z, homeX: x + 16.5, homeZ: z + .5 });

    async function waitFor(stage: string, predicate: (value: Reading) => boolean, budget = 640): Promise<Reading> {
      for (let elapsed = 0; elapsed < budget; elapsed += 4) {
        const value = await server.run(READ, scene!);
        readings.push({ stage, tick: elapsed, value });
        expect(value.health).toBe(20);
        expect(value.stockSwords + value.carriedSwords, "the unique sword must have one real owner through claim withdrawal").toBe(1);
        if (predicate(value)) return value;
        await tick.sprint(server, 4);
      }
      throw new Error(`${stage} timed out: ${JSON.stringify(readings.at(-1))}`);
    }

    const claimed = await waitFor("ordinary global planner reserves the real sword", value => value.reservedAtSource === 1 && value.stockSwords === 1, 160);
    expect(claimed.mode).toBe("NONE");
    expect(claimed.carriedSwords).toBe(0);
    await server.run(GUARD_MELEE, scene);
    const commanded = await server.run(READ, scene);
    const order = commanded.order;
    expect(order).not.toBe("NONE");
    const equipped = await waitFor("GUARD withdraws the old claim and collects its actual melee loadout", value => value.carriedSwords === 1
      && value.stockSwords === 0 && value.mode === "GUARD" && value.status === "HOLDING"
      && Math.hypot(value.x - scene!.homeX, value.z - scene!.homeZ) < 1.1);
    expect(equipped.order).toBe(order);
    expect(equipped.reservedAtSource).toBe(0);
    expect(readings.some(row => row.value.supply === "FETCHING")).toBe(true);
    expect(Math.min(...readings.map(row => row.value.sourceDistance))).toBeLessThan(4.6);

    await server.run(TARGET, scene);
    const fought = await waitFor("the normally controlled melee guard actually strikes", value => value.hitByResident && value.targetHealth < 100 && value.swordDamage > 0);
    expect(fought.mode).toBe("GUARD");
    expect(fought.order).toBe(order);
    await server.run(RETREAT, scene);
    const stopped = await waitFor("retreat closes the actual melee hand", value => value.mode === "RETREAT" && !value.session, 80);
    expect(stopped.carriedSwords).toBe(1);
    for (let elapsed = 0; elapsed < 40; elapsed += 4) {
      await tick.sprint(server, 4);
      const value = await server.run(READ, scene);
      readings.push({ stage: "retreat keeps unique equipment", tick: elapsed + 4, value });
      expect(value.mode).toBe("RETREAT");
      expect(value.carriedSwords).toBe(1);
      expect(value.stockSwords).toBe(0);
      expect(value.swordDamage).toBe(stopped.swordDamage);
    }
  } catch (error) { pair?.failing(error); throw error; }
  finally {
    await testInfo.attach("melee-supplies-readings.json", { body: JSON.stringify(readings, null, 2), contentType: "application/json" });
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch {} }
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.Container",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.Items",
  "net.minecraft.world.phys.Vec3", "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.terms.WorldSpaces", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands", "io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies",
];

const SETUP = defineTask<Omit<Scene, "resident">, Scene>({
  name: "folkways.combat.supplies.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var resident = PersonBody.RESIDENT.get().create(level);
resident.moveTo(args.number("homeX"), args.number("stockY"), args.number("homeZ"));
resident.setPersistenceRequired();
level.addFreshEntity(resident); colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow(); resident.joinColony(colony.id());
PatrolSupplies.of(colony).loadout(resident, PatrolSupplies.Loadout.BOW);
ResidentCommands.of(colony).guard(resident, WorldSpaces.at(resident), new Vec3(1, 0, 0));
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
var settings = io.github.izakyl.folkways.front.engine.colony.ColonyFront.of(colony)
 .settings(io.github.izakyl.folkways.front.api.CoreSettings.CORE);
var readable = colony.store(level, WorldSpaces.at(level, stockAt));
var bowSpec = io.github.izakyl.folkways.core.api.terms.ItemSpec.of(net.minecraft.resources.ResourceLocation.withDefaultNamespace("bow"));
var arrowSpec = io.github.izakyl.folkways.core.api.terms.ItemSpec.of(net.minecraft.tags.ItemTags.ARROWS);
var target = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, body.getBoundingBox().inflate(32))
 .stream().findFirst().orElse(null);
return Map.ofEntries(Map.entry("x", body.getX()), Map.entry("z", body.getZ()), Map.entry("health", body.getHealth()),
 Map.entry("stockBows", stockBows), Map.entry("stockArrows", stockArrows), Map.entry("carriedBows", bows),
 Map.entry("arrows", body.pack().count(Items.ARROW)), Map.entry("bowDamage", damage), Map.entry("session", session),
 Map.entry("order", order.map(value -> value.id().toString()).orElse("NONE")), Map.entry("mode", order.map(value -> value.mode().name()).orElse("NONE")),
 Map.entry("status", order.map(value -> value.status().name()).orElse("NONE")), Map.entry("supply", state.state().name()), Map.entry("reason", state.reason()),
 Map.entry("sourceDistance", body.position().distanceTo(Vec3.atCenterOf(stockAt))), Map.entry("targetHealth", target == null ? 100 : target.getHealth()),
 Map.entry("hitByResident", target != null && target.getLastHurtByMob() == body),
 Map.entry("bowAllowed", io.github.izakyl.folkways.core.api.vocation.KitRules.allows(colony, Items.BOW)),
 Map.entry("crossbowAllowed", io.github.izakyl.folkways.core.api.vocation.KitRules.allows(colony, Items.CROSSBOW)),
 Map.entry("tools", settings.entries(io.github.izakyl.folkways.front.api.CoreSettings.TOOL_KEY).toString()),
 Map.entry("defaultTools", io.github.izakyl.folkways.front.api.CoreSettings.tool().byDefault().toString()),
 Map.entry("patrolKit", io.github.izakyl.folkways.core.api.vocation.Vocations.required(
  io.github.izakyl.folkways.plugins.patrol.PatrolContent.VOCATION).kit().toString()),
 Map.entry("readableBows", readable.map(value -> io.github.izakyl.folkways.core.api.terms.Goods.countIn(value, bowSpec)).orElse(0L)),
 Map.entry("readableArrows", readable.map(value -> io.github.izakyl.folkways.core.api.terms.Goods.countIn(value, arrowSpec)).orElse(0L)),
 Map.entry("claims", colony.reservedInputs(level).toString()),
 Map.entry("stockSwords", stockSwords), Map.entry("carriedSwords", swords), Map.entry("swordDamage", swordDamage),
 Map.entry("reservedAtSource", colony.reservedInputs(level).stream().filter(claim -> claim.store()
  .filter(WorldSpaces.at(level, stockAt)::equals).isPresent()).mapToLong(claim -> claim.count()).sum()),
 Map.entry("weaponCode", io.github.izakyl.folkways.plugins.patrol.PatrolContent.class.getProtectionDomain().getCodeSource().getLocation().toString()));
` }),
});

const SETUP_MELEE = defineTask<Omit<Scene, "resident">, Scene>({
  name: "folkways.combat.supplies.melee.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var resident = PersonBody.RESIDENT.get().create(level);
resident.moveTo(args.number("homeX"), args.number("stockY"), args.number("homeZ")); resident.setPersistenceRequired();
level.addFreshEntity(resident); colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow(); resident.joinColony(colony.id());
return Map.of("colony", args.string("colony"), "resident", resident.id().toString(),
 "stockX", args.number("stockX"), "stockY", args.number("stockY"), "stockZ", args.number("stockZ"),
 "homeX", args.number("homeX"), "homeZ", args.number("homeZ"));
` }),
});

const GUARD_MELEE = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.supplies.melee.guard", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var body = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
PatrolSupplies.of(colony).loadout(body, PatrolSupplies.Loadout.MELEE);
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

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.supplies.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx); var server = (MinecraftServer)ctx.server(); var level = server.overworld();
var body = level.getEntity(UUID.fromString(args.string("resident"))); if (body != null) body.discard();
for (var enemy : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class,
 new net.minecraft.world.phys.AABB(args.number("homeX") - 32, args.number("stockY") - 4, args.number("homeZ") - 32,
 args.number("homeX") + 32, args.number("stockY") + 8, args.number("homeZ") + 32))) enemy.discard();
Colonies.of(server, UUID.fromString(args.string("colony"))).ifPresent(colony -> colony.raze());
return Map.of("done", true);
` }),
});
