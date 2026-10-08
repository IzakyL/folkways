import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import {
  foundColonyFast,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { mustCommand, tryCommand } from "./world-ui";
import {
  buildPen,
  createPastureZoneViaPanel,
  pastureZoneFailure,
  penLayout,
} from "./pasture-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath, outAbs } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const RESIDENT_COUNT = 3;

const TARGET = 6;
const EGG_ITEM = "minecraft:egg";

const MAX_LAY_TICKS = 16_000;
const LAY_BURST = 200;
const COLLECT_TICKS = 2_400;
const COLLECT_BURST = 20;

test("pasture eggs end-to-end: vanilla hens lay on their own eggTime, residents collect naturally laid eggs into colony inventory", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();

  const serverTrace = tracePath("e2e", "pasture-eggs-server");
  const clientTrace = tracePath("e2e", "pasture-eggs-client");

  const evidence: any = { samples: [] };
  let foodChest: Vec | undefined;
  let pair: E2EPair | undefined;
  let server: any;
  let client: any;

  try {
    const clientInstance = `pasture-eggs-client-${runId}`;
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`pasture-eggs-server-${runId}`) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: [foodChest], entityTypes: ["folkways:resident", "minecraft:chicken", "minecraft:item"] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await mustCommand(server, `gamemode survival ${playerName}`);

    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    expect(
      founding.residents,
      `residents=${founding.residents} (expected ${RESIDENT_COUNT})`,
    ).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    foodChest = founding.foodChest;
    const pen = penLayout(origin, 8, 3);
    evidence.pen = pen;
    await buildPen(server, pen);

    const zone = await createPastureZoneViaPanel(server, client, playerName, founding.standBlock, pen, {
      animal: "chicken",
      target: TARGET,
    });
    expect(zone.created, pastureZoneFailure(zone, "chicken", TARGET)).toBe(true);

    const baseline = await observeEggs(server, pen, foodChest);
    evidence.baseline = baseline;
    expect(baseline.stored + baseline.drops.reduce((n: number, d: any) => n + d.count, 0),
      "before the chickens lay, neither the site nor colony stock should already hold eggs").toBe(0);

    const spots = [
      { x: pen.min.x + 0.5, z: pen.min.z + 0.5 },
      { x: pen.min.x + 1.5, z: pen.min.z + 0.5 },
      { x: pen.max.x + 0.5, z: pen.min.z + 0.5 },
      { x: pen.min.x + 0.5, z: pen.max.z + 0.5 },
      { x: pen.min.x + 1.5, z: pen.max.z + 0.5 },
      { x: pen.max.x + 0.5, z: pen.max.z + 0.5 },
    ];
    for (const spot of spots) {
      await mustCommand(server, `summon minecraft:chicken ${spot.x} ${origin.y} ${spot.z} {Age:0,PersistenceRequired:1b}`);
    }
    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${founding.standBlock.y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 20);
    let firstEggTick: number | undefined;
    let collected = false;
    for (let ticks = 0; ticks <= MAX_LAY_TICKS + COLLECT_TICKS;) {
      const sample = await observeEggs(server, pen, foodChest);
      evidence.samples.push({ ticks, ...sample });
      if (firstEggTick === undefined && (sample.inPen > 0 || sample.stored > baseline.stored)) {
        firstEggTick = ticks;
      }
      if (sample.stored > baseline.stored) {
        collected = true;
        break;
      }
      if (firstEggTick === undefined ? ticks >= MAX_LAY_TICKS : ticks - firstEggTick >= COLLECT_TICKS) {
        break;
      }
      const step = firstEggTick === undefined ? LAY_BURST : COLLECT_BURST;
      await tick.sprint(server, step);
      ticks += step;
    }
    evidence.firstEggTick = firstEggTick;
    evidence.collected = collected;
    expect(collected, firstEggTick === undefined
      ? "no eggs laid in the pasture and no new eggs in stock; see chicken and drop positions in pasture-eggs-evidence.json"
      : "eggs seen in the pasture, but no new eggs in packs or chests within the collection budget; see pasture-eggs-evidence.json").toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(outAbs("e2e", "reports", "pasture-eggs-evidence.json"), JSON.stringify(evidence, null, 2));
    await pair?.teardown(testInfo);
  }
});

type Pen_ = ReturnType<typeof penLayout>;

async function observeEggs(server: MinecraftServer, pen: Pen_, foodChest: Vec) {
  // world.entities throws when the sampling fails, so reaching here means it succeeded.
  const result = await world.entities(server, { nbt: true, limit: 1000 });
  expect(result.limited, "drop sampling should not be truncated").toBe(false);
  const entities: any[] = result.entities;
  const drops = entities.filter((e: any) => e.type === "minecraft:item" && e.nbt?.Item?.id === EGG_ITEM)
    .map((e: any) => ({ uuid: e.uuid, position: e.position, count: e.nbt.Item.count,
      inPen: Math.floor(e.position.x) >= pen.min.x && Math.floor(e.position.x) <= pen.max.x
        && Math.floor(e.position.z) >= pen.min.z && Math.floor(e.position.z) <= pen.max.z
        && Math.floor(e.position.y) >= pen.min.y && Math.floor(e.position.y) <= pen.max.y + 4 }));
  const packs = entities.filter((e: any) => e.type === "folkways:resident").map((e: any) => ({
    uuid: e.uuid, position: e.position,
    eggs: (e.nbt?.["neoforge:attachments"]?.["folkways:body_state"]?.Pack?.Items ?? [])
      .reduce((n: number, item: any) => n + (item.Stack?.id === EGG_ITEM ? item.Stack.count : 0), 0),
  }));
  const container: any = await world.container(server, foodChest, { nbt: true });
  expect(container.found, `sampling the colony chest must succeed: ${container.reason ?? ""}`).toBe(true);
  expect(container.nbt?.Items, "chest inventory NBT must be readable").toBeDefined();
  const chest = container.nbt.Items.reduce((n: number, item: any) => n + (item.id === EGG_ITEM ? item.count : 0), 0);
  return { drops, packs, chest, stored: chest + packs.reduce((n: number, p: any) => n + p.eggs, 0),
    inPen: drops.filter((d: any) => d.inPen).reduce((n: number, d: any) => n + d.count, 0),
    chickens: entities.filter((e: any) => e.type === "minecraft:chicken")
      .map((e: any) => ({ uuid: e.uuid, position: e.position, eggTime: e.nbt?.EggLayTime })),
  };
}
