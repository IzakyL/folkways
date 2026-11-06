import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import {
  SQUAD_CLEANUP, SQUAD_READ, SQUAD_RELEASE, SQUAD_SETUP, waitForSquad, type Squad, type SquadReading,
} from "../shared/combat/formation";
import { layFloor } from "../shared/combat/overhead";

test("four armed residents patrol, initiate combat, reform, and resume their route", async ({}, testInfo) => {
  test.setTimeout(450_000);
  let pair: E2EPair | undefined;
  let squad: Squad | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#rts",
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
    await layFloor(server, { x: x - 5, z: z - 5 }, { x: x + 36, z: z + 25 }, y);
    const route = [8, 18, 4].map(offset => ({
      from: [x + offset, y, z + 7] as [number, number, number], to: [x + offset, y, z + 13] as [number, number, number],
      facing: 1 as const,
    }));
    squad = { ...await server.run(SQUAD_SETUP, { x, y, z, melee: true, route }), watch: [] };

    const slots = (reading: SquadReading) => reading.residents.map(resident => ({ id: resident.id, slot: resident.slot }));
    function expectFormed(reading: SquadReading, offset: number) {
      expect(reading.inSlots, "every entity must physically stand in its own assigned slot").toBe(true);
      expect(reading.active).toBe(4);
      expect(reading.residents.every(resident => resident.mode === "GUARD")).toBe(true);
      expect(new Set(reading.residents.map(resident => resident.slot)).size).toBe(4);
      for (const resident of reading.residents) {
        expect(Math.abs(resident.x - (x + offset + .5))).toBeLessThanOrEqual(.9);
      }
      expect(reading.ownedWeapons).toEqual([1, 1, 1, 1]);
    }

    const first = await waitForSquad(server, squad, "first stop assembled", reading => reading.stop === 0
      && reading.phase === "HOLDING" && reading.inSlots, 600);
    expectFormed(first, 8);
    const second = await waitForSquad(server, squad, "second stop assembled", reading => reading.stop === 1
      && reading.phase === "HOLDING" && reading.inSlots, 650);
    expectFormed(second, 18);
    expect(slots(second)).not.toEqual(slots(first));

    // The server task only creates a passive enemy. It gives no resident target, damage, or movement result.
    squad = { ...squad, enemy: await server.run(SPAWN_ENEMY, { ...squad, x, y, z }) };
    const before = await server.run(SQUAD_READ, squad);
    expect(before.enemyAlive).toBe(true);
    expect(before.enemyHealth).toBe(before.enemyMaxHealth);
    expect(before.enemyHasTarget).toBe(false);
    expect(before.residentsProvoked).toBe(false);

    const fought = await waitForSquad(server, squad, "patrol initiates real combat", reading => reading.enemyAlive
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
    const reformed = await waitForSquad(server, squad, "enemy defeated and original formation restored", reading => !reading.enemyAlive
      && reading.stop === 1 && reading.phase === "HOLDING" && reading.inSlots, 700, 4);
    expectFormed(reformed, 18);
    expect(slots(reformed)).toEqual(slots(second));
    expect(reformed.residentsProvoked).toBe(false);
    const resumed = await waitForSquad(server, squad, "third stop reached after combat", reading => reading.stop === 2
      && reading.phase === "HOLDING" && reading.inSlots, 700);
    expectFormed(resumed, 4);

    await server.run(SQUAD_RELEASE, squad);
    const released = await server.run(SQUAD_READ, squad);
    expect(released.phase).toBe("IDLE");
    expect(released.active).toBe(0);
    for (let elapsed = 0; elapsed < 32; elapsed += 8) await tick.sprint(server, 8);
    const settled = await server.run(SQUAD_READ, squad);
    expect(settled.phase).toBe("IDLE");
    expect(settled.active).toBe(0);
    expect(settled.ownedWeapons).toEqual([1, 1, 1, 1]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && squad) {
      try { await pair.server.run(SQUAD_CLEANUP, squad); } catch { /* Keep the original failure and its evidence. */ }
    }
    await pair?.teardown(testInfo);
  }
});

// A passive, sturdy zombie beyond the second stop: the squad must notice it on its own.
const SPAWN_ENEMY = defineTask<Squad & { x: number; y: number; z: number }, string>({
  name: "folkways.formation-patrol.enemy", side: "server",
  source: javaTask({
    imports: [
      "net.minecraft.server.MinecraftServer", "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EquipmentSlot",
      "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
      "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.rts.combat.group.UnitGroups",
    ],
    body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var group = UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow();
if (group.patrolStop() != 1) throw new IllegalStateException("the patrol must be at its second stop before contact");
var enemy = EntityType.ZOMBIE.create(level);
enemy.setNoAi(true); enemy.setPersistenceRequired();
enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
// Extra fixture health leaves time to observe the first hit before four real melee weapons defeat it.
enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); enemy.setHealth(100);
enemy.moveTo(args.integer("x") + 25.5, args.integer("y"), args.integer("z") + 10.5);
level.addFreshEntity(enemy);
return enemy.getUUID().toString();
`,
  }),
});
