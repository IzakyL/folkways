import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";

type Scene = { colony: string; residents: string[]; zombies: string[] };
type Sample = {
  tick: number;
  requestedResidents: number;
  requestedZombies: number;
  loadedResidents: number;
  loadedZombies: number;
  targetedZombies: number;
  guardOrders: number;
  activeWeaponSessions: number;
  attackedResidents: number;
  attackedZombies: number;
  attackedEntities: number;
  pathingResidents: number;
  pathingZombies: number;
  engineMeanMspt: number;
  enginePeakMspt: number;
  fullTickMeanMspt: number;
  fullTickPeakMspt: number;
  engineTickSamples: number;
  engineTickSamplesMspt: number[];
  fullTickSamples: number;
  fullTickSamplesMspt: number[];
};

const ENABLED = process.env.FOLKWAYS_COMBAT_BENCH === "1";

test.skip(!ENABLED, "set FOLKWAYS_COMBAT_BENCH=1 to run the opt-in live combat benchmark");

test("combat entity populations: live tick cost and AI activity", async ({}, testInfo) => {
  test.setTimeout(480_000);
  let pair: E2EPair | undefined;
  const samples: Sample[] = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `combat-bench-server-${run}` },
      client: { instance: `combat-bench-client-${run}` },
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
    // A flat, clear platform makes each resident/zombie pair able to move and tick.
    await world.command(server, `fill ${x - 45} ${y - 1} ${z - 30} ${x + 45} ${y - 1} ${z + 30} minecraft:stone`);
    await world.command(server, `fill ${x - 36} ${y} ${z - 20} ${x + 40} ${y + 5} ${z + 20} minecraft:air`, { allowFailure: true });

    for (const total of [16, 32, 64]) {
      const each = total / 2;
      const scene = await server.run(SPAWN, { each: String(each), x: String(x), y: String(y), z: String(z) });
      try {
        // Warm up navigation, target acquisition, and the server's 100-tick rolling sample ring.
        await tick.sprint(server, 200);
        await tick.sprint(server, 100);
        const sample = await server.run(MEASURE, scene);
        const row: Sample = {
          ...sample,
          requestedResidents: each,
          requestedZombies: each,
        };
        samples.push(row);
        expect(row.loadedResidents, `fixture should load all ${each} guarded residents`).toBe(each);
        expect(row.loadedZombies, `fixture should load all ${each} combat zombies`).toBe(each);
        expect(row.engineTickSamples, "the server ring should contain a complete measurement window").toBe(100);
        expect(row.fullTickSamples, "the Pre/Post listener ring should contain a complete measurement window").toBe(100);
        expect(row.targetedZombies, "native zombies should actively target guarded residents").toBeGreaterThan(0);
        expect(row.guardOrders, "every resident should retain its real GUARD order").toBe(each);
        expect(row.attackedEntities, "the live combat fixture should record real attacks").toBeGreaterThan(0);
        await server.run(CLEANUP, scene);
      } catch (error) {
        try {
          await server.run(CLEANUP, scene);
        } catch {
          // Keep the original fixture or measurement error.
        }
        throw error;
      }
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("combat-performance-samples.json", {
      body: JSON.stringify({
        enabledBy: "FOLKWAYS_COMBAT_BENCH=1",
        populations: "16/32/64 total entities; each population is residents/zombies 8/8, 16/16, 32/32",
        warmupTicks: 200,
        measurementTicks: 100,
        fixtureHealth: "Residents and zombies have 1024 max health and start at full health to keep both sides alive during the 300 tick window; they are not invulnerable.",
        combatFixture: "Each resident carries an iron sword and receives a real GUARD order aimed toward its paired zombie; both sides retain native AI.",
        engineMsptDefinition: "MinecraftServer.tickTimesNanos rolling 100 tickServer samples; nanos converted to milliseconds",
        fullTickMsptDefinition: "nanoTime from ServerTickEvent.Pre HIGHEST to ServerTickEvent.Post LOWEST, rolling 100 ticks",
        notes: [
          "Each population warms up for 200 ticks before the final 100 ticks are recorded.",
          "Engine MSPT omits NeoForge ServerTickEvent.Post work; fullTickMspt includes Pre-to-Post listeners.",
          "Health is deliberately inflated for population stability; observations include whether guard orders, weapon sessions, and actual damage occurred.",
          "This artifact reports observations only and defines no performance pass threshold.",
        ],
        samples,
      }, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "java.util.UUID",
  "net.minecraft.server.MinecraftServer",
  "net.minecraft.world.entity.EntityType",
  "net.minecraft.world.entity.Mob",
  "net.minecraft.world.entity.ai.attributes.Attributes",
  "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.item.Items",
  "net.minecraft.world.phys.Vec3",
  "java.util.function.Consumer",
  "net.neoforged.bus.api.EventPriority",
  "net.neoforged.neoforge.common.NeoForge",
  "net.neoforged.neoforge.event.tick.ServerTickEvent",
  "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.resident.body.Body",
  "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
];

const SPAWN = defineTask<{ each: string; x: string; y: string; z: string }, Scene>({
  name: "folkways.combat.performance.spawn",
  side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int each = Integer.parseInt(args.string("each"));
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
var colony = Colonies.mint(level.getServer());
var residents = new ArrayList<String>();
var zombies = new ArrayList<String>();
for (int index = 0; index < each; index++) {
  int column = index % 8, row = index / 8;
  double px = x - 31 + column * 9, pz = z - 14 + row * 9;
  var resident = PersonBody.RESIDENT.get().create(level);
  resident.moveTo(px + .5, y, pz + .5);
  resident.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024.0);
  resident.setHealth(1024.0f);
  resident.pack().insert(new ItemStack(Items.IRON_SWORD));
  resident.setPersistenceRequired();
  level.addFreshEntity(resident);
  colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow();
  resident.joinColony(colony.id());
  ResidentCommands.of(colony).guard(resident, WorldSpaces.at(resident), new Vec3(1, 0, 0));
  residents.add(resident.getUUID().toString());

  Mob zombie = EntityType.ZOMBIE.create(level);
  zombie.moveTo(px + 3.5, y, pz + .5);
  zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024.0);
  zombie.setHealth(1024.0f);
  zombie.setPersistenceRequired();
  level.addFreshEntity(zombie);
  zombies.add(zombie.getUUID().toString());
}
var tickStartNanos = new long[1];
var engineTickNanos = new long[100];
var fullTickNanos = new long[100];
Consumer<ServerTickEvent.Pre> pre = event -> {
  tickStartNanos[0] = System.nanoTime();
};
Consumer<ServerTickEvent.Post> post = event -> {
  int slot = event.getServer().getTickCount() % fullTickNanos.length;
  long started = tickStartNanos[0];
  if (started != 0L) {
    engineTickNanos[slot] = event.getServer().getTickTimesNanos()[slot];
    fullTickNanos[slot] = System.nanoTime() - started;
  }
};
NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ServerTickEvent.Pre.class, pre);
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerTickEvent.Post.class, post);
System.getProperties().put("folkways.combat.performance:" + colony.id(), new Object[] { pre, post, engineTickNanos, fullTickNanos });
return Map.of("colony", colony.id().toString(), "residents", residents, "zombies", zombies);
` }),
});

const MEASURE = defineTask<Scene, Omit<Sample, "requestedResidents" | "requestedZombies">>({
  name: "folkways.combat.performance.measure",
  side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var residentIds = new HashSet<>(Args.of(ctx).strings("residents"));
var residents = new ArrayList<Mob>();
var zombies = new ArrayList<Mob>();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
for (String id : Args.of(ctx).strings("residents")) {
  var entity = level.getEntity(UUID.fromString(id));
  if (entity instanceof Mob mob) residents.add(mob);
}
for (String id : Args.of(ctx).strings("zombies")) {
  var entity = level.getEntity(UUID.fromString(id));
  if (entity instanceof Mob mob) zombies.add(mob);
}
int targeted = 0, guards = 0, weaponSessions = 0, attackedResidents = 0, attackedZombies = 0;
int pathingResidents = 0, pathingZombies = 0;
for (Mob resident : residents) {
  if (!resident.getNavigation().isDone()) pathingResidents++;
  if (resident instanceof ResidentEntity body) {
    if (ResidentCommands.of(colony).current(body.id())
        .map(order -> order.mode() == ResidentCommands.Mode.GUARD).orElse(false)) guards++;
    if (body.hasWeaponSession()) weaponSessions++;
  }
  if (resident.getLastHurtByMob() != null) attackedResidents++;
}
for (Mob zombie : zombies) {
  if (zombie.getTarget() != null && residentIds.contains(zombie.getTarget().getUUID().toString())) targeted++;
  if (!zombie.getNavigation().isDone()) pathingZombies++;
  if (zombie.getLastHurtByMob() != null) attackedZombies++;
}
Object[] listenerState = (Object[])System.getProperties().get("folkways.combat.performance:" + args.string("colony"));
if (listenerState == null) throw new IllegalStateException("full tick timing listeners are not registered");
long[] nanos = (long[])listenerState[2];
var timesMs = new ArrayList<Double>(nanos.length);
double totalMs = 0.0, peakMs = 0.0;
int count = 0;
for (long sample : nanos) {
  if (sample <= 0L) continue;
  double millis = sample / 1_000_000.0;
  timesMs.add(millis); totalMs += millis; peakMs = Math.max(peakMs, millis); count++;
}
long[] fullTickNanos = (long[])listenerState[3];
var fullTimesMs = new ArrayList<Double>(fullTickNanos.length);
double fullTotalMs = 0.0, fullPeakMs = 0.0;
int fullCount = 0;
for (long sample : fullTickNanos) {
  if (sample <= 0L) continue;
  double millis = sample / 1_000_000.0;
  fullTimesMs.add(millis); fullTotalMs += millis; fullPeakMs = Math.max(fullPeakMs, millis); fullCount++;
}
return Map.ofEntries(
  Map.entry("tick", ((MinecraftServer)ctx.server()).getTickCount()),
  Map.entry("loadedResidents", residents.size()), Map.entry("loadedZombies", zombies.size()),
  Map.entry("targetedZombies", targeted), Map.entry("guardOrders", guards), Map.entry("activeWeaponSessions", weaponSessions),
  Map.entry("attackedResidents", attackedResidents), Map.entry("attackedZombies", attackedZombies),
  Map.entry("attackedEntities", attackedResidents + attackedZombies),
  Map.entry("pathingResidents", pathingResidents), Map.entry("pathingZombies", pathingZombies),
  Map.entry("engineMeanMspt", count == 0 ? 0.0 : totalMs / count), Map.entry("enginePeakMspt", peakMs), Map.entry("engineTickSamples", count),
  Map.entry("engineTickSamplesMspt", timesMs),
  Map.entry("fullTickMeanMspt", fullCount == 0 ? 0.0 : fullTotalMs / fullCount),
  Map.entry("fullTickPeakMspt", fullPeakMs), Map.entry("fullTickSamples", fullCount), Map.entry("fullTickSamplesMspt", fullTimesMs));
` }),
});

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.performance.cleanup",
  side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
String timingKey = "folkways.combat.performance:" + args.string("colony");
Object[] listenerState = (Object[])System.getProperties().remove(timingKey);
if (listenerState != null) {
  NeoForge.EVENT_BUS.unregister(listenerState[0]);
  NeoForge.EVENT_BUS.unregister(listenerState[1]);
}
for (String key : List.of("residents", "zombies")) {
  for (String id : Args.of(ctx).strings(key)) {
    var entity = level.getEntity(UUID.fromString(id));
    if (entity != null) entity.discard();
  }
}
Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).ifPresent(colony -> colony.raze());
return Map.of("done", true);
` }),
});
