import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";

type Scene = {
  colony: string;
  resident: string;
  enemies: string[];
  order: string;
  homeX: number;
  homeY: number;
  homeZ: number;
  workX: number;
  workY: number;
  workZ: number;
  x: string;
  y: string;
  z: string;
};

type Reading = {
  tick: number;
  residentPresent: boolean;
  residentHealth: number;
  x: number;
  y: number;
  z: number;
  target: string;
  order: string;
  orderMode: string;
  orderStatus: string;
  doing: string;
  enemy: string;
  enemyPresent: boolean;
  enemyHealth: number;
  enemyMaxHealth: number;
  enemyX: number;
  enemyY: number;
  enemyZ: number;
  enemyDistance: number;
  hasLineOfSight: boolean;
  damageSource: string;
  damageBy: string;
  swordCount: number;
  swordDamage: number;
  weaponSession: boolean;
  weaponArmed: boolean;
  weaponRange: number;
  navigationDone: boolean;
  navigationPathNodes: number;
  balk: string;
  orderDestinationX: number;
  orderDestinationY: number;
  orderDestinationZ: number;
  orderDestinationWorldX: number;
  orderDestinationWorldY: number;
  orderDestinationWorldZ: number;
  workBlock: string;
  dirtCount: number;
};

test("GUARD survives two combat rounds, returns to its slot, then resumes real work after release", async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  const evidence: Array<{ step: string; elapsedTicks: number; reading: Reading | Record<string, unknown> }> = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `combat-endurance-server-${run}` },
      client: { instance: `combat-endurance-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await world.command(server, `gamemode spectator ${player.name}`);
    await world.command(server, "difficulty normal");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "gamerule doDaylightCycle false");
    await world.command(server, "time set midnight");
    await world.command(server, `fill ${x - 12} ${y - 1} ${z - 8} ${x + 22} ${y - 1} ${z + 14} minecraft:stone`);
    await world.command(server, `fill ${x - 12} ${y} ${z - 8} ${x + 22} ${y + 6} ${z + 14} minecraft:air`, { allowFailure: true });
    // This is the only work target. After setup it changes only if the real groundwork labor clears it.
    const workX = x + 3, workY = y, workZ = z + 5;
    await world.command(server, `setblock ${workX} ${workY} ${workZ} minecraft:dirt`);
    scene = await server.run(SETUP, {
      x: String(x), y: String(y), z: String(z),
      workX: String(workX), workY: String(workY), workZ: String(workZ),
    });

    async function sample(step: string, elapsedTicks = 0): Promise<Reading> {
      const reading = await server.run(READ, scene!);
      evidence.push({ step, elapsedTicks, reading });
      expect(reading.residentPresent, `${step}: the same resident must remain loaded`).toBe(true);
      expect(reading.residentHealth, `${step}: the guard must survive`).toBeGreaterThan(0);
      expect(reading.swordCount, `${step}: the physical iron sword must remain unique`).toBe(1);
      return reading;
    }

    async function waitFor(
      step: string,
      predicate: (reading: Reading) => boolean,
      budget: number,
      stride = 4,
    ): Promise<Reading> {
      for (let elapsed = 0; elapsed < budget; elapsed += stride) {
        await tick.sprint(server, stride);
        const reading = await sample(step, elapsed + stride);
        if (predicate(reading)) return reading;
      }
      throw new Error(`${step} timed out after ${budget} ticks: ${JSON.stringify(evidence.at(-1))}`);
    }

    // Observe a real groundwork node before GUARD takes precedence; don't synthesize doing/navigation state.
    const laborStarted = await waitFor("groundwork labor begins before combat", reading =>
      reading.doing.startsWith("folkways:clearing") && reading.workBlock === "minecraft:dirt", 900, 5);
    evidence.push({ step: "labor-before-combat", elapsedTicks: 0, reading: laborStarted });

    let guardOrder = "";
    for (let round = 1; round <= 2; round++) {
      const issued = await server.run(WAVE, { ...scene, round: String(round) });
      if (round === 1) {
        guardOrder = issued.order;
        scene = { ...scene!, order: issued.order, homeX: issued.homeX, homeY: issued.homeY, homeZ: issued.homeZ,
          enemies: [...scene!.enemies, issued.enemy] };
      } else {
        expect(issued.order, "the second fight must reuse the same GUARD handle").toBe(guardOrder);
        scene = { ...scene!, enemies: [...scene!.enemies, issued.enemy] };
      }

      const fresh = await sample(`round ${round}: enemy spawned`);
      expect(fresh.enemyPresent).toBe(true);
      expect(fresh.enemyHealth).toBe(fresh.enemyMaxHealth);
      expect(fresh.order).toBe(guardOrder);
      expect(fresh.orderMode).toBe("GUARD");

      const hit = await waitFor(`round ${round}: GUARD makes a real hit`, reading =>
        reading.enemyPresent && reading.enemyHealth < reading.enemyMaxHealth
          && reading.order === guardOrder, 480);
      expect(hit.damageSource).not.toBe("NONE");
      expect(hit.damageBy).toBe(scene!.resident);
      expect(hit.workBlock, "combat must interrupt the unfinished real labor target").toBe("minecraft:dirt");

      await waitFor(`round ${round}: enemy defeated by the resident`, reading =>
        !reading.enemyPresent && reading.order === guardOrder, 600);
      const returned = await waitFor(`round ${round}: resident returns to its original GUARD slot`, reading =>
        reading.order === guardOrder && reading.orderMode === "GUARD" && reading.orderStatus === "HOLDING"
          && Math.hypot(reading.x - scene!.homeX, reading.z - scene!.homeZ) <= 1.1, 500);
      evidence.push({ step: `round ${round}: returned`, elapsedTicks: 0, reading: returned });
      expect(returned.workBlock, "the work target must remain untouched until GUARD is released").toBe("minecraft:dirt");
    }

    const released = await server.run(RELEASE, scene);
    evidence.push({ step: "explicit GUARD release", elapsedTicks: 0, reading: released });
    expect(released.releasedOrder).toBe(guardOrder);
    expect(released.releaseStatus).toBe("RELEASED");
    expect(released.activeOrder).toBe("NONE");
    const postRelease: Reading[] = [];
    let laborObservedAgain = false;
    let completed: Reading | undefined;
    for (let elapsed = 0; elapsed < 1200; elapsed += 4) {
      await tick.sprint(server, 4);
      const reading = await sample("after release: groundwork resumes", elapsed + 4);
      postRelease.push(reading);
      laborObservedAgain ||= reading.doing.startsWith("folkways:clearing");
      if (reading.workBlock === "minecraft:air" && reading.dirtCount > 0) {
        completed = reading;
        break;
      }
    }
    evidence.push({ step: "post-release labor observations", elapsedTicks: postRelease.length * 4,
      reading: { laborObservedAgain, samples: postRelease } });
    expect(laborObservedAgain, "real clearing work must be selected again after order release").toBe(true);
    expect(completed, "the resident must clear the dirt through its real groundwork task after GUARD release").toBeDefined();
    expect(completed!.dirtCount).toBeGreaterThan(0);
    expect(completed!.order).toBe("NONE");

  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && scene) {
      try { await pair.server.run(CLEANUP, scene); } catch { /* Preserve the test failure and pair evidence. */ }
    }
    await testInfo.attach("combat-endurance-readings.json", {
      body: JSON.stringify({
        populations: { residents: 1, enemiesPerRound: 1, combatRounds: 2 },
        weapon: "one real iron sword, count includes pack and active weapon session",
        enemies: "native zombie combat AI is disabled to isolate incoming damage; residents retain native AI and must damage/defeat both spawned enemies",
        labor: "one real Groundwork clear zone over dirt; recorded doing state before combat and again after GUARD release, then block and dirt output",
        observations: evidence,
      }, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "java.util.UUID", "java.util.Set", "java.util.LinkedHashMap", "java.util.Map", "java.util.List", "java.lang.reflect.Proxy",
  "net.minecraft.server.MinecraftServer", "net.minecraft.server.level.ServerLevel", "net.minecraft.core.BlockPos",
  "net.minecraft.resources.ResourceLocation", "net.minecraft.core.registries.BuiltInRegistries",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.Mob",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "net.minecraft.world.phys.Vec3",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.resident.body.Body", "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.core.api.work.Worker", "io.github.izakyl.folkways.plugins.combat.weapon.Weapons",
  "io.github.izakyl.folkways.front.engine.colony.ColonyFront", "io.github.izakyl.folkways.front.engine.colony.ColonySchemas",
  "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
];

const SETUP = defineTask<{ x: string; y: string; z: string; workX: string; workY: string; workZ: string }, Scene>({
  name: "folkways.combat.endurance.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
int workX = Integer.parseInt(args.string("workX")), workY = Integer.parseInt(args.string("workY")), workZ = Integer.parseInt(args.string("workZ"));
var colony = Colonies.mint(level.getServer());
var body = PersonBody.RESIDENT.get().create(level);
body.moveTo(x + 2.5, y, z + 2.5);
body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024.0);
body.setHealth(1024.0F);
body.pack().insert(new ItemStack(Items.IRON_SWORD));
body.setPersistenceRequired();
colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
body.joinColony(colony.id());
level.addFreshEntity(body);
ResourceLocation ground = ResourceLocation.fromNamespaceAndPath("folkways", "ground");
var settings = ColonySettings.byDefault(ColonySchemas.ofDelegation(ground))
    .with("clear", new ColonySettings.Value.Flag(true));
var zone = ColonyFront.of(colony).addZone(level, ground, Set.of(new BlockPos(workX, workY, workZ)), settings)
    .orElseThrow(() -> new IllegalStateException("the one-cell real groundwork zone was not created"));
return Map.ofEntries(
  Map.entry("colony", colony.id().toString()), Map.entry("resident", body.getUUID().toString()),
  Map.entry("enemies", List.of()), Map.entry("order", ""),
  Map.entry("homeX", body.getX()), Map.entry("homeY", body.getY()), Map.entry("homeZ", body.getZ()),
  Map.entry("workX", workX), Map.entry("workY", workY), Map.entry("workZ", workZ),
  Map.entry("x", args.string("x")), Map.entry("y", args.string("y")), Map.entry("z", args.string("z")));
` }),
});

const WAVE = defineTask<Scene & { round: string }, { enemy: string; order: string; homeX: number; homeY: number; homeZ: number }>(
  { name: "folkways.combat.endurance.wave", side: "server",
    source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var resident = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
if (resident == null || !resident.isAlive()) throw new IllegalStateException("the same live guard is required");
var commands = ResidentCommands.of(colony);
var order = commands.current(resident.id()).orElseGet(() ->
  commands.guard(resident, WorldSpaces.at(resident), new Vec3(1, 0, 0)));
if (order.mode() != ResidentCommands.Mode.GUARD) throw new IllegalStateException("existing resident order is not GUARD");
var home = WorldSpaces.world(level, order.destination()).orElseThrow();
Mob enemy = EntityType.ZOMBIE.create(level);
enemy.setNoAi(true);
enemy.setPersistenceRequired();
enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0);
enemy.setHealth(60.0F);
enemy.moveTo(resident.getX() + 8.5, resident.getY(), resident.getZ() + .5);
level.addFreshEntity(enemy);
return Map.of("enemy", enemy.getUUID().toString(), "order", order.id().toString(),
  "homeX", home.x, "homeY", home.y, "homeZ", home.z);
` }) });

const READ = defineTask<Scene, Reading>({
  name: "folkways.combat.endurance.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var resident = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
if (resident == null) return Map.ofEntries(Map.entry("tick", level.getGameTime()), Map.entry("residentPresent", false));
var current = ResidentCommands.of(colony).current(resident.id());
String enemyId = args.strings("enemies").isEmpty() ? "" : args.strings("enemies").getLast();
Mob enemy = enemyId.isEmpty() ? null : (Mob)level.getEntity(UUID.fromString(enemyId));
boolean session = resident.hasWeaponSession();
Worker weaponWorker = (Worker)Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] { Worker.class },
  (proxy, method, values) -> switch (method.getName()) {
    case "body" -> resident;
    case "resident" -> resident.resident();
    case "pack" -> resident.pack();
    default -> throw new UnsupportedOperationException("diagnostic Worker method: " + method.getName());
  });
var hand = resident.getMainHandItem();
int swordCount = resident.pack().count(Items.IRON_SWORD) + (session && hand.is(Items.IRON_SWORD) ? hand.getCount() : 0);
var damage = enemy == null ? null : enemy.getLastDamageSource();
var path = resident.getNavigation().getPath();
var order = current.map(value -> value.destination().cell()).orElse(null);
var orderWorld = current.flatMap(value -> WorldSpaces.world(level, value.destination())).orElse(null);
var result = new LinkedHashMap<String, Object>();
result.put("tick", level.getGameTime()); result.put("residentPresent", true); result.put("residentHealth", resident.getHealth());
result.put("x", resident.getX()); result.put("y", resident.getY()); result.put("z", resident.getZ());
result.put("target", resident.getTarget() == null ? "NONE" : resident.getTarget().getUUID().toString());
result.put("order", current.map(value -> value.id().toString()).orElse("NONE"));
result.put("orderMode", current.map(value -> value.mode().name()).orElse("NONE"));
result.put("orderStatus", current.map(value -> value.status().name()).orElse("NONE"));
result.put("doing", resident.doing().map(value -> value.what().toString()).orElse("NONE"));
result.put("enemy", enemyId); result.put("enemyPresent", enemy != null && enemy.isAlive());
result.put("enemyHealth", enemy == null ? 0.0F : enemy.getHealth()); result.put("enemyMaxHealth", enemy == null ? 0.0F : enemy.getMaxHealth());
result.put("enemyX", enemy == null ? -1.0 : enemy.getX()); result.put("enemyY", enemy == null ? -1.0 : enemy.getY());
result.put("enemyZ", enemy == null ? -1.0 : enemy.getZ());
result.put("enemyDistance", enemy == null ? -1.0 : resident.distanceTo(enemy));
result.put("hasLineOfSight", enemy != null && enemy.isAlive() && resident.hasLineOfSight(enemy));
result.put("damageSource", damage == null ? "NONE" : damage.getMsgId());
result.put("damageBy", damage == null || damage.getEntity() == null ? "NONE" : damage.getEntity().getUUID().toString());
result.put("swordCount", swordCount); result.put("swordDamage", session && hand.is(Items.IRON_SWORD)
  ? hand.getDamageValue() : resident.pack().contents().stream().filter(stack -> stack.is(Items.IRON_SWORD))
    .mapToInt(ItemStack::getDamageValue).findFirst().orElse(-1));
result.put("weaponSession", session);
result.put("weaponArmed", Weapons.armed(weaponWorker)); result.put("weaponRange", Weapons.range(weaponWorker));
result.put("navigationDone", resident.getNavigation().isDone());
result.put("navigationPathNodes", path == null ? 0 : path.getNodeCount());
result.put("balk", resident.balk().map(Object::toString).orElse("NONE"));
result.put("orderDestinationX", order == null ? -1 : order.getX());
result.put("orderDestinationY", order == null ? -1 : order.getY());
result.put("orderDestinationZ", order == null ? -1 : order.getZ());
result.put("orderDestinationWorldX", orderWorld == null ? -1.0 : orderWorld.x);
result.put("orderDestinationWorldY", orderWorld == null ? -1.0 : orderWorld.y);
result.put("orderDestinationWorldZ", orderWorld == null ? -1.0 : orderWorld.z);
result.put("workBlock", BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(
  (int)args.integer("workX"), (int)args.integer("workY"), (int)args.integer("workZ")))
  .getBlock()).toString());
result.put("dirtCount", resident.pack().count(Items.DIRT));
return result;
` }),
});

const RELEASE = defineTask<Scene, { releasedOrder: string; releaseStatus: string; activeOrder: string }>(
  { name: "folkways.combat.endurance.release", side: "server",
    source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var resident = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
if (resident == null) throw new IllegalStateException("the same live resident is required for release");
var commands = ResidentCommands.of(colony);
var order = commands.current(resident.id()).orElseThrow(() -> new IllegalStateException("GUARD handle is not active"));
String id = order.id().toString();
commands.release(resident.id());
return Map.of("releasedOrder", id, "releaseStatus", order.status().name(),
  "activeOrder", commands.current(resident.id()).map(value -> value.id().toString()).orElse("NONE"));
` }) });

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.endurance.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
if (!args.string("resident").isEmpty()) {
  var resident = level.getEntity(UUID.fromString(args.string("resident")));
  if (resident != null) resident.discard();
}
for (String id : args.strings("enemies")) {
  var enemy = level.getEntity(UUID.fromString(id)); if (enemy != null) enemy.discard();
}
Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).ifPresent(colony -> colony.raze());
return Map.of("done", true);
` }),
});
