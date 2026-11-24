import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import {
  commandPos,
  foundColonyFast,
  foundingBox,
  openColonyPanelViaBook,
  optInViaBook,
  pickViaSelector,
  waitForGroundedPlayer,
  type Vec,
} from "../shared/colony/colony-founding";
import { BOOK_TABS, textUnder, waitForPresent } from "../shared/ui/book-cards";
import { clickTab } from "../shared/ui/colony-pages";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { mustCommand } from "../shared/ui/world-ui";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { sampleWhileSprinting, settledAll, unwrapSettled } from "../shared/harness/tick-tools";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage } from "../shared/harness/stage";

const RESIDENT_COUNT = 3;

const ORDER_COUNT = 8;
const SMELT_INPUT = "minecraft:raw_iron";
const SMELT_OUTPUT = "minecraft:iron_ingot";
const POLICY_FUEL = "minecraft:oak_planks";
const FUEL_CONTROL = "folkways.card.items.fuel";
const WARES_SCOPE = createHash("md5").update("folkways_core:colony_settings/folkways_vanilla:wares").digest();
WARES_SCOPE[6] = (WARES_SCOPE[6] & 0x0f) | 0x30;
WARES_SCOPE[8] = (WARES_SCOPE[8] & 0x3f) | 0x80;
const WARES_SCOPE_ID = WARES_SCOPE.toString("hex").replace(
  /^(........)(....)(....)(....)(............)$/, "$1-$2-$3-$4-$5",
);
const BLOCKED_PHASE_TICKS = 800;
const SMELT_PHASE_TICKS = 12_000;

for (const stage of twins()) test(stage.title("smelting end-to-end: the fuel policy set through Settings is what unblocks the furnace"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  ensureOut("e2e");
  const runId = newRunId();

  const serverTrace = tracePath("e2e", stage.name("smelting-server"));
  const clientTrace = tracePath("e2e", stage.name("smelting-client"));

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let chest: any;
  let outputChest: any;

  try {
    const clientInstance = stage.name(`smelting-client-${runId}`);
    pair = await launchPair({
      config: stage.config,
      server: { trace: serverTrace, instance: seededServer(stage.name(`smelting-server-${runId}`)) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: [chest, outputChest].map((at) => at && stage.at(at)), entityTypes: ["folkways_vanilla:resident", "minecraft:item"] }),
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
    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT, stage });
    expect(founding.residents, `residents=${founding.residents} (expected ${RESIDENT_COUNT})`).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    const furnace: Vec = { x: origin.x + 6, y: origin.y, z: origin.z + 6 };
    chest = { x: origin.x + 4, y: origin.y, z: origin.z + 6 };
    outputChest = { x: origin.x + 8, y: origin.y, z: origin.z + 6 };
    await mustCommand(server, `setblock ${commandPos(stage.at(furnace))} minecraft:furnace`);
    await mustCommand(server, `setblock ${commandPos(stage.at(chest))} minecraft:chest{Items:[]}`);
    await mustCommand(server, `setblock ${commandPos(stage.at(outputChest))} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, chest, "chest", stage);
    await optInViaBook(server, client, playerName, outputChest, "chest", stage);
    await optInViaBook(server, client, playerName, furnace, "worksite", stage);
    await mustCommand(server, `item replace block ${commandPos(stage.at(chest))} container.0 with ${POLICY_FUEL} 32`);
    await mustCommand(server, `item replace block ${commandPos(stage.at(furnace))} container.0 with ${SMELT_INPUT} ${ORDER_COUNT}`);
    await tick.sprint(server, 5);

    const watched = [furnace, chest, outputChest].map((at) => stage.at(at));

    const blocked = await pollForOutput(server, watched, BLOCKED_PHASE_TICKS);
    expect(
      blocked.found,
      `${SMELT_OUTPUT} appeared after ${blocked.ticks} ticks while the colony's fuel list still held only`
        + ` coal and charcoal and neither was in storage — the furnace path is ignoring WaresContent.FUEL`,
    ).toBe(false);

    await allowFuelViaSettings(server, client, playerName, founding.standBlock, stage,
      String(founding.evidence.colonyId));
    const smelted = await pollForOutput(server, watched, SMELT_PHASE_TICKS);

    expect(
      smelted.found,
      `${SMELT_OUTPUT} never appeared after oak_planks was ticked in the fuel picker: nobody carried a plank`
        + " to the loaded, cold furnace, so the colony still burns by the untouched default",
    ).toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }

});

async function allowFuelViaSettings(
  server: any, client: any, playerName: string, standBlock: Vec, stage: Stage, colonyId: string,
) {
  await openColonyPanelViaBook(server, client, playerName, standBlock, stage);
  await clickTab(client, BOOK_TABS.settings);
  const scope = `folkways.book.scope.${WARES_SCOPE_ID}`;
  await waitForPresent(server, client, scope);
  await clickElement(client, { id: scope });
  await waitForPresent(server, client, FUEL_CONTROL);
  expect(textUnder(await panelElements(client), FUEL_CONTROL)).toContain("Coal");
  await pickViaSelector(client, FUEL_CONTROL, POLICY_FUEL, "*minecraft:oak_planks");

  await expect.poll(async () => {
    await tick.sprint(server, 5);
    return (await runColonyTask(server, FUEL_RULES, { colony_id: colonyId })).rules;
  }).toEqual(expect.arrayContaining(["minecraft:coal", "minecraft:charcoal", POLICY_FUEL]));
  await screen.dismiss(client);
  await tick.sprint(server, 10);
}

async function pollForOutput(server: any, watched: Vec[], maxTicks: number) {
  const step = 20;
  const outcome = await sampleWhileSprinting(server, {
    maxTicks,
    stepTicks: step,
    read: async (ticks) => {
      const reads = await settledAll(
        watched.map((at) => () =>
          world.probe(server, `if items block ${commandPos(at)} container.* ${SMELT_OUTPUT}`)),
      );
      for (let index = 0; index < reads.length; index++) {
        if (unwrapSettled(reads[index])) {
          return { ticks, at: watched[index] as Vec | undefined };
        }
      }
      return { ticks, at: undefined as Vec | undefined };
    },
    done: (sample) => sample.at !== undefined,
  });
  if (outcome.matched) {
    return { found: true, ticks: outcome.sample.ticks, at: outcome.sample.at };
  }
  return { found: false, ticks: maxTicks, reason: outcome.reason };
}

// The colony's fuel rules as the server holds them, each as it describes itself.
const FUEL_RULES = defineTask<{ colony_id: string }, Record<string, any>>({
  name: "folkways.smelting.fuel-rules",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.item.api.ItemFilter",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.vanilla.wares.WaresContent",
    ],
    body: `
Args args = Args.of(ctx);
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id")))
    .orElseThrow(() -> Fail.notFound("no colony"));
List<String> rules = new ArrayList<String>();
for (ItemFilter rule : ColonyFront.of(colony).settings(WaresContent.ID).entries("fuel")) {
    rules.add(rule.describe());
}
Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("rules", rules);
return Sync.stamp(out);
`,
  }),
});
