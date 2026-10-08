import { launchPair, type E2EPair } from "../shared/pair";
import { expect, test } from "@playwright/test";
import { screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { optInContainerViaUI } from "../e2e/membership-optin";
import { aimAndWorldLeftClick } from "../e2e/world-ui";
import { createMarkedZone, setBookGesture } from "../e2e/zone-tools";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { residentStates, lookPortraits } from "./look-shots";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const RESIDENT_COUNT = 3;
const CROP_ID = "minecraft:wheat_seeds";

test("resident looks on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("looks");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "looks-server"), instance: seededOffThreadServer(`visual-looks-server-${runId}`) },
      client: { trace: tracePath("visual", "looks-client"), instance: `visual-looks-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await world.command(server, `gamemode survival ${playerName}`);
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    expect(founding.residents).toBeGreaterThanOrEqual(RESIDENT_COUNT);
    run.note("colony", { origin, residents: founding.residents, stand: founding.standBlock });

    const chest: Vec = { x: origin.x, y: origin.y, z: origin.z + 8 };
    const ground: Vec = { x: origin.x + 4, y: origin.y - 1, z: origin.z + 10 };
    await world.command(server, `setblock ${commandPos(ground)} minecraft:dirt`);
    await tryCommand(server, `setblock ${commandPos({ x: ground.x, y: ground.y + 1, z: ground.z })} minecraft:air`);
    await world.command(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    for (const [slot, item, count] of [
      ["container.0", "minecraft:wooden_hoe", 1],
      ["container.1", "minecraft:wooden_hoe", 1],
      ["container.2", CROP_ID, 8],
    ] as const) {
      await world.command(server, `item replace block ${commandPos(chest)} ${slot} with ${item} ${count}`);
    }
    await tick.sprint(server, 3);
    await optInContainerViaUI(server, client, playerName, chest);
    await armFarmZone(server, client, playerName, founding.standBlock, ground);

    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${founding.standBlock.y} ${founding.standBlock.z + 1.5} 180 30`);
    await world.command(server, `gamemode creative ${playerName}`);
    await tick.sprint(server, 40);

    const portraits = lookPortraits(run, server, client, { prefix: "01-look", want: 2 });

    for (let round = 0; round < 12; round++) {
      await tick.sprint(server, 20);
      await portraits.sample();
    }

    await portraits.crowdShot();
    run.note("looks", portraits.summary());

    const crowd = await residentStates(server);
    const centre = crowd[0]?.position ?? { x: origin.x, y: origin.y, z: origin.z };
    const eye = { x: centre.x - 6, y: centre.y + 3.5, z: centre.z - 6 };
    await run.clip(client, server, "02-residents-moving", {
      subject: "Resident looks: a crowd walking around: clothes, skeleton, arm swing",
      worldState: { residents: crowd.map((c) => ({ uuid: c.uuid.slice(0, 8), look: c.look, doing: c.doing })) },
      camera: { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, centre), fov: 70 },
      seconds: 10,
    });

    portraits.assertPortraits();
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function armFarmZone(server: MinecraftServer, client: MinecraftClient, playerName: string, standBlock: Vec, ground: Vec) {
  await setBookGesture(server, client, playerName, "box");
  await aimAndWorldLeftClick(server, client, playerName, ground);
  await tick.sprint(server, 3);
  await aimAndWorldLeftClick(server, client, playerName, ground);
  await tick.sprint(server, 5);
  await createMarkedZone(server, client, playerName, standBlock, "farm");
  await tick.sprint(server, 5);
  await screen.dismiss(client);
}
