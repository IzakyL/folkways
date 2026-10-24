import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { BIND_COLONY, runColonyTask } from "../shared/colony/colony-tasks";
import { GOLEM_CAST } from "../shared/colony/integration-tasks";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";

type Scene = { colony: string; golem: string; foe: string; player: string };
type Reading = {
  className: string; kind: string; alive: boolean; health: number;
  targetHealth: number; targetMaxHealth: number; actualAttacker: boolean;
  fighting: boolean; doing: string; targetGoals: number; nativeGoals: number;
  hasNativeTarget: boolean; swords: number; swordDamage: number; bound: boolean;
};

test("real Modular Golems retain humanoid and barehanded combat, and recover native goals on dismissal", async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  let active: Scene | undefined;
  const readings: Array<{ kind: string; stage: string; tick: number; value: Reading }> = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `combat-golems-server-${run}` },
      client: { instance: `combat-golems-client-${run}` },
      snapshot: { entityTypes: ["modulargolems:metal_golem", "modulargolems:humanoid_golem", "modulargolems:dog_golem", "minecraft:zombie"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 8;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 8;
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "gamerule doDaylightCycle false");
    await world.command(server, "time set midnight");
    await world.command(server, "difficulty normal");
    await world.command(server, `fill ${x - 8} ${y - 1} ${z - 8} ${x + 12} ${y - 1} ${z + 8} minecraft:stone`);
    await world.command(server, `fill ${x - 8} ${y} ${z - 8} ${x + 12} ${y + 5} ${z + 8} minecraft:air`, { allowFailure: true });
    await world.command(server, `item replace entity ${player.name} weapon.mainhand with folkways:colony_book`);
    const colony = String((await runColonyTask(server, BIND_COLONY, { player: player.name })).colony_id);
    await world.command(server, `gamemode spectator ${player.name}`);

    for (const kind of ["humanoid", "metal", "dog"]) {
      const cast = await runColonyTask(server, GOLEM_CAST, {
        player: player.name, colony_id: colony, origin: { x, y, z },
        kinds: [kind], positions: [{ x, y, z }], freeze_humans: false,
        trades: ["patrolling"], pack: [{ item: "minecraft:iron_sword", count: 1 }],
      });
      const scene = await server.run(PREPARE, { colony, golem: String(cast.uuids[0]), player: player.name });
      active = scene;
      await tick.sprint(server, 20);
      const initial = await server.run(READ, scene);
      readings.push({ kind, stage: "enrolled before attack", tick: 0, value: initial });
      expect(initial.className).toContain("dev.xkmc.modulargolems.");
      expect(initial.kind).toBe(`modulargolems:${kind}_golem`);
      expect(initial.bound).toBe(true);
      expect(initial.swords).toBe(1);
      expect(initial.swordDamage).toBe(5);
      expect(initial.nativeGoals).toBe(0);
      expect(initial.targetGoals).toBe(0);
      expect((await server.run(PROVOKE, scene)).hit, `${kind}: native zombie blow must provoke actual self-defence`).toBe(true);

      let hit: Reading | undefined;
      for (let elapsed = 0; elapsed < 240; elapsed += 2) {
        await tick.sprint(server, 2);
        const reading = await server.run(READ, scene);
        readings.push({ kind, stage: "actual runner self-defence", tick: elapsed + 2, value: reading });
        expect(reading.alive).toBe(true);
        expect(reading.bound).toBe(true);
        expect(reading.hasNativeTarget, "native targeting stays under the colony's control").toBe(false);
        expect(reading.swords, "the actual tool stays unique in the real pack").toBe(1);
        if (reading.actualAttacker && reading.targetHealth < reading.targetMaxHealth) { hit = reading; break; }
      }
      expect(hit, `${kind}: real golem failed to hurt the real attacker; ${JSON.stringify(readings.at(-1))}`).toBeDefined();
      expect(readings.some(row => row.kind === kind && row.value.fighting), `${kind}: damage must run through the colony fighting activity`).toBe(true);
      if (kind === "humanoid") {
        // Tool wear is charged when the Once work commits, after native damage.
        await tick.sprint(server, 4);
        expect((await server.run(READ, scene)).swordDamage).toBeGreaterThan(5);
      } else {
        expect(hit!.swordDamage, `${kind}: carried equipment must not displace native barehanded combat`).toBe(5);
      }
      const dismissed = await server.run(DISMISS, scene);
      expect(dismissed.bound).toBe(false);
      expect(dismissed.nativeGoals, `${kind}: actual native AI must be restored on dismissal`).toBeGreaterThan(0);
      expect(dismissed.swords).toBe(1);
      await server.run(CLEANUP, scene);
      active = undefined;
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("combat-golems-readings.json", { body: JSON.stringify(readings, null, 2), contentType: "application/json" });
    if (pair && active) { try { await pair.server.run(CLEANUP, active); } catch {} }
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.EntityType",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.front.engine.colony.ColonyGround", "io.github.izakyl.folkways.front.engine.colony.Endorsements",
];

const PREPARE = defineTask<Omit<Scene, "foe">, Scene>({
  name: "folkways.combat.golems.prepare", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var golem = (Mob)level.getEntity(UUID.fromString(args.string("golem")));
var body = Bodies.of(golem).orElseThrow();
body.pack().getItem(0).setDamageValue(5);
var zombie = EntityType.ZOMBIE.create(level);
zombie.moveTo(golem.getX() + 3, golem.getY(), golem.getZ());
zombie.setNoAi(true); zombie.setPersistenceRequired();
zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
zombie.setHealth(1024);
level.addFreshEntity(zombie);
return Map.of("colony", args.string("colony"), "golem", args.string("golem"),
  "foe", zombie.getUUID().toString(), "player", args.string("player"));
` }),
});

const PROVOKE = defineTask<Scene, { hit: boolean }>({
  name: "folkways.combat.golems.provoke", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var level = ((MinecraftServer)ctx.server()).overworld();
var args = Args.of(ctx);
var golem = (Mob)level.getEntity(UUID.fromString(args.string("golem")));
var zombie = (Mob)level.getEntity(UUID.fromString(args.string("foe")));
return Map.of("hit", zombie.doHurtTarget(golem));
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.combat.golems.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var level = ((MinecraftServer)ctx.server()).overworld();
var args = Args.of(ctx);
var golem = (Mob)level.getEntity(UUID.fromString(args.string("golem")));
var target = (Mob)level.getEntity(UUID.fromString(args.string("foe")));
var body = Bodies.of(golem).orElseThrow();
int swords = 0, damage = 0;
for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
  var stack = body.pack().getItem(slot);
  if (stack.is(Items.IRON_SWORD)) { swords += stack.getCount(); damage += stack.getDamageValue(); }
}
return Map.ofEntries(Map.entry("className", golem.getClass().getName()), Map.entry("kind", body.kind().id().toString()),
  Map.entry("alive", golem.isAlive()), Map.entry("health", golem.getHealth()),
  Map.entry("targetHealth", target.getHealth()), Map.entry("targetMaxHealth", target.getMaxHealth()),
  Map.entry("actualAttacker", target.getLastHurtByMob() == golem),
  Map.entry("fighting", body.doing().map(doing -> doing.what().getPath().equals("fighting")).orElse(false)),
  Map.entry("doing", body.doing().toString()), Map.entry("targetGoals", golem.targetSelector.getAvailableGoals().size()),
  Map.entry("nativeGoals", golem.goalSelector.getAvailableGoals().stream().filter(wrapped ->
    !wrapped.getGoal().getClass().getName().equals("io.github.izakyl.folkways.plugins.person.walk.OpenDoorwayGoal")).count()),
  Map.entry("hasNativeTarget", golem.getTarget() != null),
  Map.entry("swords", swords), Map.entry("swordDamage", damage), Map.entry("bound", body.colonyId().isPresent()));
` }),
});

const DISMISS = defineTask<Scene, { bound: boolean; nativeGoals: number; swords: number }>({
  name: "folkways.combat.golems.dismiss", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var server = (MinecraftServer)ctx.server();
var args = Args.of(ctx);
var level = server.overworld();
var golem = (Mob)level.getEntity(UUID.fromString(args.string("golem")));
var body = Bodies.of(golem).orElseThrow();
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var player = server.getPlayerList().getPlayerByName(args.string("player"));
var result = Endorsements.pointed(level, colony, player, body).orElseThrow();
if (result.refusal().isPresent()) throw new IllegalStateException(result.refusal().get().key());
int swords = 0;
for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
  var stack = body.pack().getItem(slot); if (stack.is(Items.IRON_SWORD)) swords += stack.getCount();
}
return Map.of("bound", body.colonyId().isPresent(), "nativeGoals", golem.goalSelector.getAvailableGoals().size(), "swords", swords);
` }),
});

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.golems.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var level = ((MinecraftServer)ctx.server()).overworld();
var args = Args.of(ctx);
for (var key : List.of("golem", "foe")) {
  var entity = level.getEntity(UUID.fromString(args.string(key))); if (entity != null) entity.discard();
}
return Map.of("done", true);
` }),
});
