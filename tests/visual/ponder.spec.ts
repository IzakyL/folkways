import { launchPair, type E2EPair } from "../shared/pair";
import { expect, test } from "@playwright/test";
import { input, screen, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../e2e/colony-founding";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, safeScreen, screenName, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

// Page order in the colony book: the basics folder, then one folder per plugin in the order it enrolled
// (Lessons.folders()). The fixture pack loads Create and Modular Golems, so their scenes are here too.
const SCENES = [
  "colony_book",
  "book_modes",
  "colony_panel",
  "residents",
  "household",
  "workshop",
  "building",
  "create_handoff",
  "farming",
  "pasture",
  "fishing",
  "orders",
  "rail",
  "dispatch",
  "golems",
];

const CLIP_SECONDS = 75;

const CLIP_FPS = 20;

test.describe.configure({ mode: "parallel" });

for (const [index, name] of SCENES.entries()) {
  test(`ponder scene on camera: ${name}`, async ({}, testInfo) => {
    test.setTimeout(BUDGET.heavy);
    const scope = `ponder-${index}-${name.replace(/_/g, "-")}`;
    const run = new VisualRun(scope);
    const runId = newRunId();
    let pair: E2EPair | undefined;
    let server: MinecraftServer;
    let client: MinecraftClient;
    try {
      pair = await launchPair({
        config: CONFIG_PATH,
        server: { trace: tracePath("visual", `${scope}-server`), instance: seededOffThreadServer(`visual-${scope}-server-${runId}`) },
        client: { trace: tracePath("visual", `${scope}-client`), instance: `visual-${scope}-client-${runId}` },
      });
      ({ server, client } = pair);

      const grounded = await waitForGroundedPlayer(server);
      await tryCommand(server, "difficulty peaceful");
      const openBook = `ponder folkways:colony_book ${grounded.name}`;
      await tryCommand(server, openBook);
      await realWait(3_000);
      await frames(client, 2);

      const opened = screenName(await safeScreen(client));
      run.note("screen", opened);
      expect(opened, "the colony book did not open a ponder screen").toMatch(/PonderUI/);

      const buttons = (await screen.widgets(client))
        .filter((widget) => /PonderButton/.test(widget.className ?? ""))
        .sort((a, b) => a.x - b.x);
      if (buttons.length < 4) {
        throw new Error(`ponder: expected the paging buttons, saw ${buttons.length} PonderButtons`);
      }
      const right = { x: buttons[3].x, y: buttons[3].y, width: buttons[3].width, height: buttons[3].height };
      run.note("paging_button", right);

      const pageRight = async (to: number) => {
        await input.move(client, { x: right.x + right.width / 2, y: right.y + right.height / 2 });
        const pressed = await input.button(client, { button: "left", action: "press" });
        await input.button(client, { button: "left", action: "release" });
        const handled = pressed?.dispatch?.screenHandled ?? null;
        expect(
          handled,
          `Scene ${to} (${SCENES[to]}): the next-page click missed. PonderUI did not consume it, ` +
            `so the clip records an earlier scene. dispatch=${JSON.stringify(pressed?.dispatch ?? null)} screen=${pressed?.screen ?? null}`,
        ).toBe(true);
        return handled;
      };

      // Page to the scene before this one off camera; the last click happens on camera so the scene plays from its start.
      for (let to = 1; to < index; to++) {
        await pageRight(to);
        await realWait(1_000);
        await frames(client, 2);
      }

      const worldState: Record<string, unknown> = { scene: name, index };
      await run.clip(client, server, `${index}-${name}`, {
        subject: `Ponder scene \`${name}\`, in full: check that each beat shows what its text says`,
        worldState,
        seconds: CLIP_SECONDS,
        fps: CLIP_FPS,
        during: async () => {
          if (index === 0) {
            const reopened = await tryCommand(server, openBook);
            worldState.enter = reopened.status === "ok" && reopened.success ? "reopened" : "reopen-failed";
          } else {
            worldState.enter = { paged: await pageRight(index) };
          }
          await realWait(1_000);
          await frames(client, 2);
        },
      });

      const closing = screenName(await safeScreen(client));
      expect(
        closing,
        `Scene ${index} (${name}): the screen was no longer ponder when recording ended: ${closing}. The close button was hit, so the clip does not show the scene.`,
      ).toMatch(/PonderUI/);
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
    }
  });
}

function realWait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
}
