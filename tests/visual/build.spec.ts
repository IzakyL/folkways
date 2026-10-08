import { launchPair, type E2EPair } from "../shared/pair";
import { test } from "@playwright/test";
import { tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { TRADE_TOOLS, foundColonyFast, optInViaBook, toolChestItems, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { handOffSchematic, schematicCells, schematicSpec } from "../e2e/schematic-tools";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const RESIDENT_COUNT = 3;
const ASSET = "tests/e2e/assets/oak-wall.nbt";

test("construction on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("build");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "build-server"), instance: seededOffThreadServer(`visual-build-server-${runId}`) },
      client: { trace: tracePath("visual", "build-client"), instance: `visual-build-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    await tryCommand(server, `op ${playerName}`);
    await world.command(server, `gamemode creative ${playerName}`);

    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    run.note("colony", { origin, residents: founding.residents, stand: founding.standBlock });

    const spec = schematicSpec(ASSET);
    const chest: Vec = { x: origin.x + 6, y: origin.y, z: origin.z + 4 };
    let slot = TRADE_TOOLS.building.length * RESIDENT_COUNT;
    await world.command(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[${toolChestItems(TRADE_TOOLS.building, RESIDENT_COUNT, 0)}]}`);
    for (const [id, total] of Object.entries(spec.materials)) {
      await world.command(server, `item replace block ${commandPos(chest)} container.${slot++} with ${id} ${Math.min(64, total + 32)}`);
    }
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, chest, "chest");

    const anchor: Vec = { x: origin.x + 8, y: origin.y, z: origin.z - 8 };
    const cells = schematicCells(spec, anchor);
    await tryCommand(
      server,
      `fill ${commandPos({ x: anchor.x - 1, y: anchor.y, z: anchor.z - 1 })} ` +
        `${commandPos({ x: anchor.x + spec.size.x, y: anchor.y + spec.size.y + 2, z: anchor.z + spec.size.z })} minecraft:air`,
    );
    await tick.sprint(server, 5);

    run.note("handoff", (await handOffSchematic(server, client, playerName, { asset: ASSET, anchor })).receipt);

    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${founding.standBlock.y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 20);

    const wanted = spec.cells.map((cell) => cell.block);
    const laid = async () => (await readIds(server, cells)).filter((id, index) => id === wanted[index]).length;
    const look = { x: anchor.x + 1, y: anchor.y + 1, z: anchor.z + 1 };
    const eye = { x: look.x - 6, y: anchor.y + 5, z: look.z - 6 };
    const camera = { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, look), fov: 70 };
    run.note("site", { asset: ASSET, anchor, cells, chest, camera });

    await run.shot(client, "01-site-before", {
      subject: "Build site: blueprint ordered, nobody has started yet",
      worldState: { cells, laid: await laid(), materials: spec.materials },
      camera,
      hard: true,
    });

    await run.clip(client, server, "02-site-working", {
      subject: "Construction: a resident fetches planks, walks to the site, and builds the blueprint block by block",
      worldState: { cells, materials: spec.materials, chest, residents: founding.residents },
      camera,
      seconds: 20,
    });

    for (let round = 0; round < 20 && (await laid()) < cells.length; round++) {
      await tick.sprint(server, 60);
    }

    const finalLaid = await laid();
    run.note("site_final", { laid: finalLaid, of: cells.length, blocks: await readIds(server, cells) });
    await run.shot(client, "03-site-after", {
      subject: "Build site: progress at this moment",
      worldState: { cells, laid: finalLaid, of: cells.length, materials: spec.materials },
      camera,
      hard: true,
    });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function readIds(server: MinecraftServer, positions: Vec[]): Promise<string[]> {
  const ids: string[] = [];
  for (const position of positions) {
    const result = await world.block(server, position, { dimension: "minecraft:overworld" });
    const id = result?.id;
    if (typeof id !== "string" || id === "") {
      throw new Error(`Block query at ${commandPos(position)} returned no id (${JSON.stringify(result)}). That does not mean the block is air.`);
    }
    ids.push(id.includes(":") ? id : `minecraft:${id}`);
  }
  return ids;
}
