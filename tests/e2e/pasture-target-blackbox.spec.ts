import { expect, test } from "@playwright/test";
import { tick } from "@izakyl/blockwright-minecraft";
import { writeFileSync } from "node:fs";
import {
  createColonyViaBook,
  errorSummary,
  tryCommand,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { buildPen, createPastureZoneViaPanel, pastureZoneFailure, penLayout, type Pen } from "./pasture-tools";
import {
  applyZoneSetting,
  colonyRegistry,
  holdZoneAndOpen,
  releaseHeldZone,
  zoneChoiceSettingAt,
  zoneCountSettingAt,
  zoneSettingToken,
} from "./zone-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, outAbs, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, safeAction, waitForElement } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const BOOK_ITEM = "folkways:colony_book";

const CREATED_TARGET = 4;
const EXPECTED_TARGET = 7;

test("the zone settings page sets a pasture's target head count on the colony's own zone", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "pasture-target-server"), instance: seededServer(`pasture-target-server-${runId}`) },
      client: { trace: tracePath("e2e", "pasture-target-client"), instance: `pasture-target-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const standBlock: Vec = { x: origin.x - 4, y: origin.y, z: origin.z };

    await tryCommand(server, `fill ${origin.x - 20} ${origin.y} ${origin.z - 20} ${origin.x + 20} ${origin.y + 6} ${origin.z + 20} minecraft:air`);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.found = await createColonyViaBook(server, client, playerName, standBlock);

    const pen = penLayout(origin, 8, 2);
    await buildPen(server, pen);
    evidence.zone = await createPastureZoneViaPanel(server, client, playerName, standBlock, pen, {
      animal: "cow",
      target: CREATED_TARGET,
    });
    expect(
      evidence.zone.created,
      pastureZoneFailure(evidence.zone, "cow", CREATED_TARGET),
    ).toBe(true);

    const registry = await colonyRegistry(server);
    evidence.zone_animal = await zoneChoiceSettingAt(server, registry, "animal");
    expect(evidence.zone_animal, "the zone read is the pasture just created").toBe("minecraft:cow");
    evidence.target_before = await zoneCountSettingAt(server, registry, "target");
    expect(evidence.target_before, "the reflection probe first proves it reads something: the head count at creation").toBe(CREATED_TARGET);

    await settleOverview(server, client, 4);

    evidence.apply = await applyPastureTarget(server, client, playerName, standBlock, pen, EXPECTED_TARGET);

    const series: number[] = [];
    for (let round = 0; round < 12; round++) {
      await tick.sprint(server, 20);
      const now = await zoneCountSettingAt(server, registry, "target");
      series.push(now);
      if (now === EXPECTED_TARGET) {
        break;
      }
      await safeAction(() => frames(client, 30));
    }
    evidence.target_series = series;
    evidence.target_after = series[series.length - 1];
    expect(
      evidence.target_after,
      `writing ${EXPECTED_TARGET} into the head count field on the settings page should change the target from ${CREATED_TARGET} to ${EXPECTED_TARGET} (read from the zone in ColonyData)`,
    ).toBe(EXPECTED_TARGET);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(outAbs("e2e", "reports", "pasture-target-evidence.json"), JSON.stringify(evidence, null, 2) + "\n");
    testInfo.attach("pasture-target-evidence.json", {
      body: JSON.stringify(evidence, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

async function applyPastureTarget(
  server: any, client: any, playerName: string, standBlock: Vec, pen: Pen, target: number,
) {
  const step: any = { target };
  try {
    step.hold = await holdZoneAndOpen(server, client, playerName, { min: pen.min, max: pen.max });
    await waitForElement(client, { id: zoneSettingToken("target") }, { timeoutMs: 8_000 });
    step.press = await applyZoneSetting(server, client, { key: "target", count: target });
    await tick.sprint(server, 10);
  } catch (error) {
    step.error = errorSummary(error);
  } finally {
    step.release = await releaseHeldZone(client);
  }
  return step;
}

async function settleOverview(server: any, client: any, rounds: number) {
  for (let i = 0; i < rounds; i++) {
    await safeAction(() => frames(client, 150));
    await tick.sprint(server, 20);
  }
}
