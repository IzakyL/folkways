import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { tryCommand } from "../shared/harness/bw-helpers";

type Scene = { colony: string; group: string; enemy: string; x: string; y: string; z: string };
type ResidentReading = {
  id: string; x: number; y: number; z: number; health: number;
  slot: string; order: string; mode: string; doing: string;
};
type Reading = {
  phase: string; stop: number; members: number; active: number; inSlots: boolean;
  enemyAlive: boolean; enemyHealth: number; enemyMaxHealth: number;
  enemyHasTarget: boolean; residentsProvoked: boolean; ownedWeapons: number[];
  residents: ResidentReading[];
};

test("four armed residents patrol, initiate combat, reform, and resume their route", async ({}, testInfo) => {
  test.setTimeout(450_000);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  const evidence: Array<{ step: string; reading: Reading }> = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `formation-patrol-server-${run}` },
      client: { instance: `formation-patrol-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await mustCommand(server, `gamemode spectator ${player.name}`);
    await mustCommand(server, "difficulty normal");
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, `fill ${x - 5} ${y - 1} ${z - 5} ${x + 36} ${y - 1} ${z + 25} minecraft:stone`);
    await tryCommand(server, `fill ${x - 5} ${y} ${z - 5} ${x + 36} ${y + 5} ${z + 25} minecraft:air`);
    scene = await server.run(SETUP, { x: String(x), y: String(y), z: String(z) });

    async function sample(step: string): Promise<Reading> {
      const reading = await server.run(READ, scene!);
      evidence.push({ step, reading });
      return reading;
    }
    async function waitFor(step: string, predicate: (reading: Reading) => boolean, budget: number, stride = 8) {
      for (let elapsed = 0; elapsed < budget; elapsed += stride) {
        await tick.sprint(server, stride);
        const reading = await sample(step);
        expect(reading.members, `${step}: actual membership must stay intact`).toBe(4);
        expect(reading.residents.every(resident => resident.health > 0), `${step}: all four residents must survive`).toBe(true);
        if (reading.phase === "FAILED") throw new Error(`${step}: patrol failed: ${JSON.stringify(reading)}`);
        if (predicate(reading)) return reading;
      }
      throw new Error(`${step} timed out: ${JSON.stringify(evidence.at(-1))}`);
    }
    const slots = (reading: Reading) => reading.residents.map(resident => ({ id: resident.id, slot: resident.slot }));
    function expectFormed(reading: Reading, offset: number) {
      expect(reading.inSlots, "every entity must physically stand in its own assigned slot").toBe(true);
      expect(reading.active).toBe(4);
      expect(reading.residents.every(resident => resident.mode === "GUARD")).toBe(true);
      expect(new Set(reading.residents.map(resident => resident.slot)).size).toBe(4);
      for (const resident of reading.residents) {
        expect(Math.abs(resident.x - (x + offset + .5))).toBeLessThanOrEqual(.9);
      }
      expect(reading.ownedWeapons).toEqual([1, 1, 1, 1]);
    }

    const first = await waitFor("first stop assembled", reading => reading.stop === 0
      && reading.phase === "HOLDING" && reading.inSlots, 600);
    expectFormed(first, 8);
    const second = await waitFor("second stop assembled", reading => reading.stop === 1
      && reading.phase === "HOLDING" && reading.inSlots, 650);
    expectFormed(second, 18);
    expect(slots(second)).not.toEqual(slots(first));

    // The server task only creates a passive enemy. It gives no resident target, damage, or movement result.
    const spawned = await server.run(CONTROL, { ...scene, op: "enemy" });
    scene = { ...scene, enemy: spawned.enemy };
    const before = await sample("unprovoked enemy spawned");
    expect(before.enemyAlive).toBe(true);
    expect(before.enemyHealth).toBe(before.enemyMaxHealth);
    expect(before.enemyHasTarget).toBe(false);
    expect(before.residentsProvoked).toBe(false);

    const fought = await waitFor("patrol initiates real combat", reading => reading.enemyAlive
      && reading.enemyHealth < before.enemyHealth && reading.phase === "ENGAGING", 400, 4);
    expect(fought.stop, "combat must pause the same route stop").toBe(1);
    expect(fought.enemyHasTarget, "the NoAI enemy never acquires a resident").toBe(false);
    expect(fought.residentsProvoked, "none of the residents needed to be attacked first").toBe(false);
    expect(fought.residents.every(resident => resident.health === 20)).toBe(true);
    expect(slots(fought), "combat must retain the original return slots").toEqual(slots(second));
    expect(fought.residents.map(resident => resident.order), "combat must retain the original guard handles")
      .toEqual(second.residents.map(resident => resident.order));
    expect(fought.residents.some(resident => Math.abs(resident.x - (x + 18.5)) > .9),
      "melee guards must actually leave the formation to reach the distant enemy").toBe(true);

    // Let native weapon sessions finish the enemy; no kill command or target removal ends the fight.
    const reformed = await waitFor("enemy defeated and original formation restored", reading => !reading.enemyAlive
      && reading.stop === 1 && reading.phase === "HOLDING" && reading.inSlots, 700, 4);
    expectFormed(reformed, 18);
    expect(slots(reformed)).toEqual(slots(second));
    expect(reformed.residentsProvoked).toBe(false);
    const resumed = await waitFor("third stop reached after combat", reading => reading.stop === 2
      && reading.phase === "HOLDING" && reading.inSlots, 700);
    expectFormed(resumed, 4);

    await server.run(CONTROL, { ...scene, op: "release" });
    const released = await sample("group released");
    expect(released.phase).toBe("IDLE");
    expect(released.active).toBe(0);
    for (let elapsed = 0; elapsed < 32; elapsed += 8) await tick.sprint(server, 8);
    const settled = await sample("release remains effective");
    expect(settled.phase).toBe("IDLE");
    expect(settled.active).toBe(0);
    expect(settled.ownedWeapons).toEqual([1, 1, 1, 1]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && scene) {
      try { await pair.server.run(CLEANUP, scene); } catch { /* Keep the original failure and its evidence. */ }
    }
    await testInfo.attach("formation-patrol-readings.json", {
      body: JSON.stringify(evidence, null, 2), contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.phys.Vec3",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.resident.body.Body", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.core.api.terms.Reach", "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.plugins.combat.group.UnitGroups", "io.github.izakyl.folkways.plugins.combat.group.Deployment",
];

const SETUP = defineTask<{ x: string; y: string; z: string }, Scene>({
  name: "folkways.formation-patrol.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
var colony = Colonies.mint(level.getServer());
var bodies = new ArrayList<Body>();
for (int n = 0; n < 4; n++) {
  var resident = PersonBody.RESIDENT.get().create(level);
  resident.moveTo(x + 2.5, y, z + 7.5 + n * 2);
  resident.pack().insert(new ItemStack(n % 2 == 0 ? Items.IRON_SWORD : Items.IRON_AXE));
  level.addFreshEntity(resident);
  colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow();
  resident.joinColony(colony.id()); bodies.add(resident);
}
var group = UnitGroups.of(colony).create(bodies);
var route = new ArrayList<Deployment>();
for (int offset : new int[] {8, 18, 4}) {
  route.add(new Deployment(WorldPos.of(level, new BlockPos(x + offset, y, z + 7)),
    WorldPos.of(level, new BlockPos(x + offset, y, z + 13)), new Vec3(1, 0, 0), new Deployment.Formation(2)));
}
group.patrol(route);
return Map.of("colony", colony.id().toString(), "group", group.id().toString(), "enemy", "",
  "x", args.string("x"), "y", args.string("y"), "z", args.string("z"));
` }),
});

const CONTROL = defineTask<Scene & { op: string }, { enemy: string }>({
  name: "folkways.formation-patrol.control", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var group = UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow();
String enemyId = args.string("enemy");
switch (args.string("op")) {
  case "enemy" -> {
    if (group.patrolStop() != 1) throw new IllegalStateException("the patrol must be at its second stop before contact");
    int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
    var enemy = EntityType.ZOMBIE.create(level);
    enemy.setNoAi(true); enemy.setPersistenceRequired();
    enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
    // Extra fixture health leaves time to observe the first hit before four real melee weapons defeat it.
    enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); enemy.setHealth(100);
    enemy.moveTo(x + 25.5, y, z + 10.5);
    level.addFreshEntity(enemy); enemyId = enemy.getUUID().toString();
  }
  case "release" -> group.release();
  default -> throw new IllegalArgumentException("unknown patrol fixture operation");
}
return Map.of("enemy", enemyId);
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.formation-patrol.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var group = UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow();
var commands = ResidentCommands.of(colony);
var bodies = colony.residents().stream().sorted(Comparator.comparing(resident -> resident.id().toString()))
  .map(resident -> (Body)level.getEntity(resident.id())).toList();
if (bodies.size() != 4 || bodies.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("four loaded residents required");
Mob enemy = args.string("enemy").isEmpty() ? null : (Mob)level.getEntity(UUID.fromString(args.string("enemy")));
var positions = new ArrayList<Map<String, Object>>();
var ownedWeapons = new ArrayList<Integer>();
for (var body : bodies) {
  var order = commands.current(body.id());
  var slot = group.destinations().get(body.id());
  var row = new LinkedHashMap<String, Object>();
  row.put("id", body.id().toString()); row.put("x", body.mob().getX()); row.put("y", body.mob().getY()); row.put("z", body.mob().getZ());
  row.put("health", body.mob().getHealth()); row.put("slot", slot == null ? "NONE" : slot.cell().toShortString());
  row.put("order", order.map(value -> value.id().toString()).orElse("NONE"));
  row.put("mode", order.map(value -> value.mode().name()).orElse("NONE")); row.put("doing", body.doing().toString());
  positions.add(row);
  int count = body.pack().count(Items.IRON_SWORD) + body.pack().count(Items.IRON_AXE);
  if (body.mob() instanceof ResidentEntity resident && resident.hasWeaponSession()) {
    var actual = resident.getMainHandItem();
    if (actual.is(Items.IRON_SWORD) || actual.is(Items.IRON_AXE)) count += actual.getCount();
  }
  ownedWeapons.add(count);
}
var result = new LinkedHashMap<String, Object>();
result.put("phase", group.phase().name()); result.put("stop", group.patrolStop()); result.put("members", group.members().size());
result.put("active", bodies.stream().filter(body -> commands.current(body.id()).isPresent()).count());
result.put("inSlots", group.destinations().size() == 4 && bodies.stream().allMatch(body ->
  Reach.standingAt(body.mob().position(), group.destinations().get(body.id()).block(level))));
result.put("enemyAlive", enemy != null && enemy.isAlive()); result.put("enemyHealth", enemy == null ? 0 : enemy.getHealth());
result.put("enemyMaxHealth", enemy == null ? 0 : enemy.getMaxHealth()); result.put("enemyHasTarget", enemy != null && enemy.getTarget() != null);
result.put("residentsProvoked", bodies.stream().anyMatch(body -> body.mob().getLastHurtByMob() != null));
result.put("ownedWeapons", ownedWeapons); result.put("residents", positions);
return result;
` }),
});

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.formation-patrol.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).ifPresent(colony -> {
  UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).ifPresent(group -> group.release());
  for (var resident : List.copyOf(colony.residents())) {
    var entity = level.getEntity(resident.id()); if (entity != null) entity.discard();
  }
  colony.raze();
});
if (!args.string("enemy").isEmpty()) {
  var enemy = level.getEntity(UUID.fromString(args.string("enemy"))); if (enemy != null) enemy.discard();
}
return Map.of("done", true);
` }),
});
