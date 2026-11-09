import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { tryCommand } from "../shared/harness/bw-helpers";
import {
  SQUAD_CLEANUP, SQUAD_READ, SQUAD_RELEASE, SQUAD_SETUP, waitForSquad, type Squad, type SquadReading,
} from "../shared/combat/formation";

test("four residents patrol both ways through single-file bends and two steps", async ({}, testInfo) => {
  test.setTimeout(450_000);
  let pair: E2EPair | undefined;
  let squad: Squad | undefined;
  const firstBend = new Set<string>();
  const secondBend = new Set<string>();
  try {
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { instance: `formation-terrain-server-${run}` },
      client: { instance: `formation-terrain-client-${run}` },
      snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await mustCommand(server, `gamemode spectator ${player.name}`);
    await mustCommand(server, "gamerule doMobSpawning false");
    await tryCommand(server, `fill ${x - 2} ${y} ${z - 2} ${x + 24} ${y + 8} ${z + 24} minecraft:air`);
    await server.run(BUILD_TERRAIN, { x, y, z });
    // Up through both bends to a line two blocks higher, then back down to the starting room.
    squad = {
      ...await server.run(SQUAD_SETUP, { x, y, z, melee: false, route: [
        { from: [x + 20, y + 2, z + 12], to: [x + 20, y + 2, z + 18], facing: 1 },
        { from: [x + 2, y, z + 7], to: [x + 2, y, z + 13], facing: -1 },
      ] }),
      watch: [x + 10, y, z + 10, x + 10, y, z + 15],
    };

    // Who has stood in each single-cell bend so far, over every reading taken.
    const note = (reading: SquadReading) => {
      for (const person of reading.residents) {
        if (person.standing[0]) firstBend.add(person.id);
        if (person.standing[1]) secondBend.add(person.id);
      }
      return reading;
    };
    const holding = (stop: number) => (reading: SquadReading) =>
      note(reading).stop === stop && reading.phase === "HOLDING" && reading.inSlots;
    const initial = note(await server.run(SQUAD_READ, squad));
    expect(initial.phase, "local height matching must preserve sequential column entry").toBe("COLUMN");
    const raised = await waitForSquad(server, squad, "raised line reached after both bends", holding(0), 1100, 4);
    expect(raised.active).toBe(4);
    expect(raised.residents.every(person => person.mode === "GUARD")).toBe(true);
    expect(raised.residents.every(person => Math.abs(person.y - (y + 2)) < .1)).toBe(true);
    expect(firstBend.size, "every entity must physically round the first single-cell bend").toBe(4);
    expect(secondBend.size, "every entity must physically round the second single-cell bend").toBe(4);
    const returned = await waitForSquad(server, squad, "patrol descends and reforms in its starting room", holding(1), 1100, 4);
    expect(returned.active).toBe(4);
    expect(returned.residents.every(person => Math.abs(person.x - (x + 2.5)) <= .9 && Math.abs(person.y - y) < .1)).toBe(true);
    await server.run(SQUAD_RELEASE, squad);
    const released = await server.run(SQUAD_READ, squad);
    expect(released.phase).toBe("IDLE");
    expect(released.active).toBe(0);
    for (let elapsed = 0; elapsed < 24; elapsed += 4) await tick.sprint(server, 4);
    expect((await server.run(SQUAD_READ, squad)).active).toBe(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && squad) {
      try { await pair.server.run(SQUAD_CLEANUP, squad); } catch { /* Preserve the original failure. */ }
    }
    await pair?.teardown(testInfo);
  }
});

// A starting room, a stone block with a single-file corridor bending twice through it, two steps up at its far
// end and a raised floor beyond.
const BUILD_TERRAIN = defineTask<{ x: number; y: number; z: number }, boolean>({
  name: "folkways.formation-terrain.build", side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.level.block.Blocks"],
    body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var origin = new BlockPos((int)args.integer("x"), (int)args.integer("y") - 1, (int)args.integer("z"));
for (int dx = 0; dx < 22; dx++) for (int dz = 0; dz < 22; dz++)
  level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
for (int dx = 6; dx <= 17; dx++) for (int dz = 0; dz < 22; dz++) for (int dy = 1; dy <= 5; dy++)
  level.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
for (int dx = 6; dx <= 10; dx++) for (int dy = 1; dy <= 4; dy++)
  level.setBlock(origin.offset(dx, dy, 10), Blocks.AIR.defaultBlockState(), 3);
for (int dz = 10; dz <= 15; dz++) for (int dy = 1; dy <= 4; dy++)
  level.setBlock(origin.offset(10, dy, dz), Blocks.AIR.defaultBlockState(), 3);
for (int dx = 10; dx <= 17; dx++) {
  int floor = dx < 15 ? 0 : dx == 15 ? 1 : 2;
  for (int dy = 1; dy <= 5; dy++) level.setBlock(origin.offset(dx, dy, 15),
    (dy <= floor ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
}
for (int dx = 18; dx < 22; dx++) for (int dz = 12; dz <= 18; dz++) for (int dy = 1; dy <= 2; dy++)
  level.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
return true;
`,
  }),
});
