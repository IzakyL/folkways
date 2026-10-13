import { expect, test } from "@playwright/test";
import { input, media, screen, tick } from "@izakyl/blockwright-minecraft";
import { commandPos, createColonyViaBook, tryCommand, safeAction, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import {
  createMarkedZone,
  heldScroll,
  KEY_LEFT_CONTROL,
  markBox,
  releaseHeldZone,
  setBookGesture,
} from "../shared/ui/zone-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, screenName, safeScreen } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";
const BOOK_ITEM = "folkways:colony_book";
const KEY_V = 86;
const MARKED_SIZE = /^(\d+)x(\d+)x(\d+) marked/;

test("view from above: the book's box gesture worked with the mouse from a camera off the player", async ({}, testInfo) => {
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
      server: { trace: tracePath("e2e", "overhead-server"), instance: seededServer(`overhead-server-${runId}`) },
      client: { trace: tracePath("e2e", "overhead-client"), instance: `overhead-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    await tryCommand(server, `fill ${origin.x - 12} ${origin.y} ${origin.z - 20} ${origin.x + 12} ${origin.y + 16} ${origin.z + 12} minecraft:air`);
    await tryCommand(server, `fill ${origin.x - 12} ${origin.y - 1} ${origin.z - 20} ${origin.x + 12} ${origin.y - 1} ${origin.z + 12} minecraft:smooth_stone`);
    await tryCommand(server, `setblock ${commandPos(origin)} minecraft:smooth_stone`);
    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.found = await createColonyViaBook(server, client, playerName, origin);
    evidence.mode = await setBookGesture(server, client, playerName, "box");
    const ground = { x: origin.x + 3, y: origin.y - 1, z: origin.z + 3 };
    const standAt = { x: ground.x + 0.5, y: origin.y, z: ground.z + 0.5 };
    evidence.farm_mark = await markBox(server, client, playerName, standAt, ground, ground);
    evidence.farm = await createMarkedZone(server, client, playerName, origin, "farm");
    await releaseHeldZone(client);
    await tryCommand(server, `tp ${playerName} ${origin.x + 3.5} ${origin.y} ${origin.z + 3.5} 0 0`);
    await tick.sprint(server, 25);

    await input.key(client, { keyCode: KEY_V, action: "press" });
    await input.key(client, { keyCode: KEY_V, action: "release" });
    await frames(client, 4);
    const opened = await safeScreen(client);
    evidence.screen = opened;
    expect(screenName(opened), "the key should open the view from above").toContain("OverheadScreen");

    const info: any = (opened as any).screen;
    const centre = { x: Math.round(info.width / 2), y: Math.round(info.height / 2) };

    // The farm under the player's feet: its card follows the mouse over it, and the layer button puts it away.
    await input.move(client, { x: centre.x, y: centre.y + 32 });
    await frames(client, 6);
    await media.screenshot(client, { path: outAbs("e2e", "shots", "overhead-hover.png") });
    await screen.clickWidget(client, { label: "Zones" });
    await input.move(client, { x: centre.x, y: centre.y + 32 });
    await frames(client, 6);
    await media.screenshot(client, { path: outAbs("e2e", "shots", "overhead-hidden.png") });
    await screen.clickWidget(client, { label: "Zones" });
    await drainActionBar(client);
    await input.click(client, centre);
    await tick.sprint(server, 3);
    evidence.corner = await readActionBar(client);
    expect(evidence.corner.text, "a left click under the mouse sets the first corner").toMatch(/^First corner set down/);

    await input.click(client, { x: centre.x + 60, y: centre.y - 30 });
    await tick.sprint(server, 3);
    evidence.marked = await readActionBar(client);
    const marked = MARKED_SIZE.exec(evidence.marked.text ?? "");
    expect(marked, `a click elsewhere marks the box: ${evidence.marked.text}`).not.toBeNull();
    expect(Number(marked![1]) * Number(marked![3]), "the two clicks were on different cells of the floor").toBeGreaterThan(1);
    await media.screenshot(client, { path: outAbs("e2e", "shots", "overhead-marked.png") });

    await input.move(client, { x: centre.x + 30, y: centre.y - 15 });
    evidence.push = await heldScroll(server, client, [KEY_LEFT_CONTROL], 2);
    const pushed = MARKED_SIZE.exec(evidence.push.bar?.text ?? "");
    expect(pushed, `Ctrl+scroll over the box pushes the face under the mouse: ${evidence.push.bar?.text}`).not.toBeNull();
    expect(Number(pushed![2]), "the face under the mouse is the top, pulled two blocks up").toBe(Number(marked![2]) + 2);
    await media.screenshot(client, { path: outAbs("e2e", "shots", "overhead-pushed.png") });

    await input.click(client, centre, { button: "right" });
    await tick.sprint(server, 10);
    await frames(client, 4);
    const panel = await safeScreen(client);
    evidence.panel = screenName(panel);
    expect(evidence.panel, "a right click opens the book's panel over the view").not.toContain("OverheadScreen");
    expect(evidence.panel).not.toBe("");
    await media.screenshot(client, { path: outAbs("e2e", "shots", "overhead-panel.png") });

    await safeAction(() => screen.dismiss(client));
    await tick.sprint(server, 5);
    await frames(client, 4);
    evidence.back = screenName(await safeScreen(client));
    expect(evidence.back, "closing the panel gives the view from above back").toContain("OverheadScreen");

    // The buttons along the top do what the keys do: change the gesture, and deal with what is being drawn.
    await screen.clickWidget(client, { label: "Draw a line" });
    await tick.sprint(server, 10);
    await frames(client, 4);
    await input.click(client, centre);
    await tick.sprint(server, 3);
    await input.click(client, { x: centre.x + 80, y: centre.y + 20 });
    await tick.sprint(server, 3);
    await frames(client, 4);
    evidence.second_point = await readActionBar(client);
    expect(evidence.second_point.text, "with the line gesture picked from the bar, two clicks set two points").toMatch(/^Point 2 set down/);
    evidence.drawing = (await screen.widgets(client)).map((widget: any) => widget.label);
    expect(evidence.drawing, "a line of two points can be set up, taken back a point, or discarded")
      .toEqual(expect.arrayContaining(["Set it up", "Take back a point", "Discard", "Zones", "Paths", "Works", "Residents"]));
    await input.move(client, { x: centre.x + 40, y: centre.y + 60 });
    await frames(client, 4);
    await media.screenshot(client, { path: outAbs("e2e", "shots", "overhead-line.png") });

    await screen.clickWidget(client, { label: "Take back a point" });
    await tick.sprint(server, 3);
    await frames(client, 4);
    evidence.undone = (await screen.widgets(client)).map((widget: any) => widget.label);
    expect(evidence.undone, "one point is not yet a line to set up").not.toContain("Set it up");
    expect(evidence.undone).toContain("Take back a point");
    await screen.clickWidget(client, { label: "Discard" });
    await tick.sprint(server, 3);
    await frames(client, 4);
    evidence.discarded = (await screen.widgets(client)).map((widget: any) => widget.label);
    expect(evidence.discarded, "with nothing drawn there is nothing to discard").not.toContain("Discard");

    await input.key(client, { keyCode: KEY_V, action: "press" });
    await input.key(client, { keyCode: KEY_V, action: "release" });
    await frames(client, 4);
    evidence.left = await safeScreen(client);
    expect((evidence.left as any).screen ?? null, "the key again goes back to the player's own eyes").toBeNull();
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach("overhead-evidence.json", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});
