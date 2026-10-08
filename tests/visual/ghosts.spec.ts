import { launchPair, type E2EPair } from "../shared/pair";
import { test } from "@playwright/test";
import { tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { foundColonyFast, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { handOffSchematic, schematicSpec } from "../e2e/schematic-tools";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const ASSET = "tests/e2e/assets/ghost-hut.nbt";

test("ghosts on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("ghosts");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "ghosts-server"), instance: seededOffThreadServer(`visual-ghosts-server-${runId}`) },
      client: { trace: tracePath("visual", "ghosts-client"), instance: `visual-ghosts-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `op ${playerName}`);
    await world.command(server, `gamemode creative ${playerName}`);
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 1 });

    const spec = schematicSpec(ASSET);
    const anchor: Vec = { x: origin.x + 8, y: origin.y, z: origin.z - 10 };
    await tryCommand(
      server,
      `fill ${commandPos({ x: anchor.x - 1, y: anchor.y, z: anchor.z - 1 })} ` +
        `${commandPos({ x: anchor.x + spec.size.x, y: anchor.y + spec.size.y + 2, z: anchor.z + spec.size.z })} minecraft:air`,
    );
    await tick.sprint(server, 5);
    run.note("handoff", (await handOffSchematic(server, client, playerName, { asset: ASSET, anchor })).receipt);

    // A wrong block in the floor, and the right stair turned the wrong way in the roof.
    await world.command(server, `setblock ${commandPos({ x: anchor.x + 1, y: anchor.y, z: anchor.z + 1 })} minecraft:cobblestone`);
    await world.command(server, `setblock ${commandPos({ x: anchor.x + 2, y: anchor.y + 3, z: anchor.z })} minecraft:oak_stairs[facing=east]`);

    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${founding.standBlock.y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 60);

    const look = { x: anchor.x + 3, y: anchor.y + 1.5, z: anchor.z + 2.5 };
    const views: Array<[string, Vec]> = [
      ["01-front", { x: look.x - 3, y: anchor.y + 4, z: look.z + 9 }],
      ["02-corner", { x: look.x + 8, y: anchor.y + 6, z: look.z - 7 }],
      ["03-close", { x: look.x - 1.5, y: anchor.y + 2.5, z: look.z + 7 }],
      ["04-low-side", { x: look.x + 9, y: anchor.y + 1.6, z: look.z + 1 }],
      ["05-above", { x: look.x - 2, y: anchor.y + 9, z: look.z + 4 }],
    ];
    for (const [name, eye] of views) {
      const camera = { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, look), fov: 70 };
      await run.shot(client, name, { subject: "Owed blocks of a small hut", worldState: { anchor }, camera, hard: true });
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
