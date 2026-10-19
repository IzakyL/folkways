import { launchPair, type E2EPair } from "../shared/harness/pair";
import { expect, test } from "@playwright/test";
import { screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { copyFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { clickElement } from "../ldlib2";
import { foundColonyFast, setBookGesture, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { aimAndLeftClick, markBox, openBookPage } from "../shared/ui/zone-tools";
import { HANDOFF_CONFIRM } from "../shared/ui/schematic-tools";
import { settlePanel } from "../shared/ui/site-panel";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { LayoutProbe } from "../shared/capture/layout-lint";
import { clickTab } from "./ui-catalog";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { clickUnverified, commandPos, frames, safeAction, safeScreen, screenOpen, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const BOOK_ITEM = "folkways:colony_book";
const RESIDENT_COUNT = 2;
const OFFER = "folkways.zoneconfig.offer.folkways.draft";
const CHOOSE = "folkways.draft.choose";
const PATTERN = "folkways.draft.pattern.folkways.terraced_field";
const PREVIEW = "folkways.draft.preview";
const BUILD = "folkways.draft.confirm";
const BUILD_TAB = "folkways.page.build";
const SHELF = "folkways.board.open.folkways.build.0.0";
const SHELF_WINDOW = "folkways.window.folkways.blueprints";
const HUT = "folkways.blueprints.entry.local.blueprint.hut";
const HANDOFF_WINDOW = "folkways.window.blueprint_handoff";
const HUT_ASSET = "tests/shared/assets/ghost-hut.nbt";

// The two ways a build is set down: a pattern drawn over a marked box, previewed out in the world before it is
// built; and a blueprint off the shelf, carried on the book, standing where the player aims and set down by a click.
test("placing patterns and blueprints on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("placement");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "placement-server"), instance: seededOffThreadServer(`visual-placement-server-${runId}`) },
      client: { trace: tracePath("visual", "placement-client"), instance: `visual-placement-client-${runId}` },
    });
    const { server, client } = pair;
    const probe = new LayoutProbe(client);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `op ${playerName}`);
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    run.note("colony", { origin, residents: founding.residents, stand: founding.standBlock });

    // ── a pattern over a marked box ──────────────────────────────────────────────────────────────────────────
    const min: Vec = { x: origin.x + 4, y: origin.y - 1, z: origin.z - 16 };
    const max: Vec = { x: origin.x + 12, y: origin.y + 3, z: origin.z - 8 };
    await clearPlot(server, { ...min, x: min.x - 2, z: min.z - 2 }, { ...max, x: max.x + 2, z: max.z + 2 });
    await holdBook(server, playerName);
    await setBookGesture(server, client, playerName, "box");
    // The far corner hangs in the air: a glass post stands up to it for it to be clicked on, and is gone after.
    const post = { ...max, y: origin.y };
    await tryCommand(server, `fill ${commandPos(post)} ${commandPos(max)} minecraft:glass`);
    const standAt = { x: min.x - 1.5, y: origin.y, z: max.z + 2.5 };
    const marked = await markBox(server, client, playerName, standAt, min, max);
    await tryCommand(server, `fill ${commandPos(post)} ${commandPos(max)} minecraft:air`);
    await tick.sprint(server, 3);
    run.note("marked", marked.marked);

    await openBookPage(server, client, playerName, founding.standBlock);
    await waitForElement(client, { id: OFFER }, { timeoutMs: 8_000 });
    await clickElement(client, { id: OFFER });
    await settlePanel(server, client);
    await waitForElement(client, { id: CHOOSE }, { timeoutMs: 8_000 });
    await clickElement(client, { id: CHOOSE });
    await settlePanel(server, client);
    await waitForElement(client, { id: PATTERN }, { timeoutMs: 8_000 });
    await run.shot(client, "01-pattern-list", {
      subject: "A box is marked: the book offers the patterns that can be drawn in it",
      worldState: { min, max }, hard: true,
    });
    await run.lint(probe, "window:pattern-list");

    await clickElement(client, { id: PATTERN });
    await settlePanel(server, client);
    await waitForElement(client, { id: PREVIEW }, { timeoutMs: 8_000 });
    await run.shot(client, "02-pattern-knobs", {
      subject: "A pattern picked: its knobs, and only a preview to ask for before anything is built",
      worldState: { pattern: PATTERN }, hard: true,
    });
    await run.lint(probe, "window:pattern-knobs");

    await clickUnverified(client, { id: PREVIEW });
    await expect.poll(async () => {
      await settlePanel(server, client);
      return screenOpen(await safeScreen(client));
    }, { timeout: 8_000, message: "the preview closes the window" }).toBe(false);
    const eye = { x: max.x + 6, y: max.y + 8, z: max.z + 6 };
    const middle = { x: (min.x + max.x) / 2, y: min.y, z: (min.z + max.z) / 2 };
    await run.shot(client, "03-pattern-preview", {
      subject: "The pattern previewed out in the world, nothing filed yet",
      worldState: { min, max },
      camera: { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, middle), fov: 70 },
      hard: true,
    });

    await openBookPage(server, client, playerName, founding.standBlock);
    await waitForElement(client, { id: BUILD }, { timeoutMs: 8_000 });
    await run.shot(client, "04-pattern-confirm", {
      subject: "Back in the window on the previewed pattern, which can now be built",
      worldState: {}, hard: true,
    });
    await run.lint(probe, "window:pattern-confirm");
    await clickUnverified(client, { id: BUILD });
    await settlePanel(server, client);
    await safeAction(() => screen.dismiss(client));
    await tick.sprint(server, 100);
    await run.shot(client, "05-pattern-site", {
      subject: "The pattern built: a site filed where the preview stood",
      worldState: { min, max },
      camera: { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, middle), fov: 70 },
      hard: true,
    });

    // ── a blueprint off the shelf ────────────────────────────────────────────────────────────────────────────
    const shelf = path.join((server as any).instance.instanceDir, "folkways", "blueprint");
    mkdirSync(shelf, { recursive: true });
    copyFileSync(HUT_ASSET, path.join(shelf, "hut.nbt"));
    await world.command(server, "reload");
    await tick.sprint(server, 20);
    const corner: Vec = { x: origin.x - 14, y: origin.y - 1, z: origin.z - 14 };
    await clearPlot(server, { ...corner, x: corner.x - 2, z: corner.z - 2 }, { x: corner.x + 10, y: origin.y + 8, z: corner.z + 10 });
    await holdBook(server, playerName);
    await setBookGesture(server, client, playerName, "point");

    await openBookPage(server, client, playerName, founding.standBlock);
    await clickTab(client, BUILD_TAB);
    await settlePanel(server, client);
    await clickElement(client, { id: SHELF }, { scrollIntoView: true });
    await waitForElement(client, { id: SHELF_WINDOW }, { timeoutMs: 8_000 });
    await settlePanel(server, client);
    await run.shot(client, "06-blueprint-shelf", {
      subject: "The blueprint shelf: files from the server's and the player's own folkways/blueprint folders",
      worldState: { file: "hut.nbt" }, hard: true,
    });
    await run.lint(probe, "window:blueprint-shelf");

    await clickElement(client, { id: HUT }, { scrollIntoView: true });
    await settlePanel(server, client);
    await tryCommand(server, `gamemode creative ${playerName}`);
    const feet = { x: corner.x + 3.5, y: origin.y, z: corner.z + 12.5 };
    const aimEye = { x: feet.x, y: feet.y + 1.62, z: feet.z };
    const aim = cameraLookingAt(aimEye, { x: corner.x + 0.5, y: corner.y + 1, z: corner.z + 0.5 });
    await tryCommand(server, `tp ${playerName} ${feet.x} ${feet.y} ${feet.z} ${aim.yaw} ${aim.pitch}`);
    await tick.sprint(server, 3);
    await safeAction(() => frames(client, 20));
    await run.shot(client, "07-blueprint-ghost", {
      subject: "The hut carried on the book: its ghost stands where the player aims",
      worldState: { corner }, hard: true,
    });

    await aimAndLeftClick(server, client, playerName, feet, corner);
    await waitForElement(client, { id: HANDOFF_WINDOW }, { timeoutMs: 8_000 });
    await settlePanel(server, client);
    await run.shot(client, "08-blueprint-handoff", {
      subject: "Set down: the hand-off window, to place the build order or raise it at once",
      worldState: { corner }, hard: true,
    });
    await run.lint(probe, "window:blueprint-handoff");

    await clickElement(client, { id: HANDOFF_CONFIRM });
    await settlePanel(server, client);
    await safeAction(() => screen.dismiss(client));
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tick.sprint(server, 10);
    const siteEye = { x: corner.x + 12, y: origin.y + 8, z: corner.z + 12 };
    await run.shot(client, "09-blueprint-site", {
      subject: "The hut ordered: its site stands where it was set down",
      worldState: { corner },
      camera: { dimension: "minecraft:overworld", position: siteEye, ...cameraLookingAt(siteEye, { x: corner.x + 3, y: corner.y, z: corner.z + 3 }), fov: 70 },
      hard: true,
    });

    run.expectTaken(["01-pattern-list", "02-pattern-knobs", "03-pattern-preview", "04-pattern-confirm", "05-pattern-site",
      "06-blueprint-shelf", "07-blueprint-ghost", "08-blueprint-handoff", "09-blueprint-site"]);
    run.expectLinted(["window:pattern-list", "window:pattern-knobs", "window:pattern-confirm",
      "window:blueprint-shelf", "window:blueprint-handoff"]);
    run.expectLintClean();
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// Air above a smooth stone floor two blocks deep: the pattern looks for ground under the floor it is marked on.
async function clearPlot(server: MinecraftServer, min: Vec, max: Vec) {
  await tryCommand(server, `fill ${commandPos(min)} ${commandPos(max)} minecraft:air`);
  for (const dy of [0, 1]) {
    await tryCommand(server, `fill ${commandPos({ ...min, y: min.y - dy })} ${commandPos({ ...max, y: min.y - dy })} minecraft:smooth_stone`);
  }
  await tick.sprint(server, 3);
}

async function holdBook(server: MinecraftServer, playerName: string) {
  await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
  await tick.sprint(server, 3);
}
