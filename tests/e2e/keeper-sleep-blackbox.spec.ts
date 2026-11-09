import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { WIRE_NETWORK, NETWORK_STATE } from "../shared/colony/integration-tasks";
import { wearResidents } from "../shared/colony/vitals";
import { readDoing } from "../shared/colony/resident-probe";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const RESIDENT_TYPE = "folkways_vanilla:resident";
const SPENT = 1200;

test("a keeper worn out at the desk gets up and goes to bed", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = {};
  try {
    pair = await launchPair({
      server: { trace: tracePath("e2e", "keeper-sleep-server"), instance: seededServer(`keeper-sleep-server-${runId}`) },
      client: { trace: tracePath("e2e", "keeper-sleep-client"), instance: `keeper-sleep-client-${runId}` },
      snapshot: () => ({ entityTypes: [RESIDENT_TYPE] }),
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const at = (x: number, z: number, dy = 0) => ({ x: origin.x + x, y: origin.y + dy, z: origin.z + z });
    const founded = await foundColonyFast(server, client, player, { origin, residentCount: 1 });
    const colony_id = founded.evidence.colonyId;
    evidence.beds = founded.bedFeet;

    const source = at(-11, 8);
    const packager = at(-10, 8);
    const link = at(-10, 7);
    const send = at(-10, 8, 1);
    // Create hands a stock ticker to a keeper seated beside it, level with it or one block down. The seat is sunk into
    // the floor in a booth: whoever gets up from it is set on top of it, a jump short of the floor round it.
    const ticker = at(-9, 8);
    const seat = at(-8, 8, -1);
    const receive = at(6, 8);
    const target = at(4, 10);
    const store = at(8, 10);
    const chains = [at(-7, 6, 4), at(6, 6, 4)];
    for (const chain of chains)
      await world.command(server, `fill ${chain.x} ${origin.y} ${chain.z} ${chain.x} ${chain.y - 1} ${chain.z} create:shaft[axis=y]`);
    for (const [pos, block] of [
      [source, "minecraft:chest[facing=south]"],
      [packager, "create:packager[facing=east]"],
      [link, "create:stock_link[face=wall,facing=north]"],
      [send, "create:package_frogport"],
      [receive, "create:package_frogport"],
      [ticker, "create:stock_ticker[facing=east]"],
      [seat, "create:orange_seat"],
      [target, "minecraft:chest[facing=south]"],
      [store, "minecraft:chest[facing=south]"],
      ...chains.map(pos => [pos, "create:chain_conveyor"] as [typeof seat, string]),
    ] as [typeof seat, string][]) await world.command(server, `setblock ${commandPos(pos)} ${block}`);
    for (const dz of [-1, 1])
      await world.command(server, `fill ${commandPos(at(-8, 8 + dz, -1))} ${commandPos(at(-8, 8 + dz, 1))} minecraft:stone_bricks`);
    await tick.sprint(server, 20);
    const args = { player, colony_id, source, packager, link, send, receive, ticker, target, store, chains, edges: [[0, 1]] };
    evidence.network = await runColonyTask(server, WIRE_NETWORK, args);

    let seated = false;
    for (let waited = 0; waited < 6_000 && !seated; waited += 200) {
      await tick.sprint(server, 200);
      seated = (await runColonyTask(server, NETWORK_STATE, args)).seated > 0;
    }
    expect(seated, "the keeper takes the seat by the stock ticker").toBe(true);

    evidence.worn = await wearResidents(server, colony_id, { food: 20, exhaustion: 0, tired: SPENT });
    const samples: any[] = [];
    let slept = false;
    for (let ticks = 0; ticks < 4_000 && !slept; ticks += 100) {
      await tick.sprint(server, 100);
      const one = (await world.entities(server, { dimension: "minecraft:overworld", types: [RESIDENT_TYPE], nbt: true, limit: 4 }))
        .entities[0] as any;
      slept = one?.nbt?.SleepingY !== undefined;
      samples.push({
        ticks: ticks + 100, at: one?.position, riding: one?.nbt?.RootVehicle !== undefined, slept,
        doing: await readDoing(server, one.uuid).catch(error => String(error).slice(0, 120)),
      });
    }
    evidence.samples = samples;
    expect(slept, `the worn-out keeper never lay down: ${JSON.stringify(samples.slice(-3))}`).toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(outAbs("e2e", "reports", "keeper-sleep-evidence.json"), JSON.stringify(evidence, null, 2) + "\n");
    await pair?.teardown(testInfo);
  }
});
