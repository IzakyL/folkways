import { expect, test } from "@playwright/test";
import { camera, input, media, screen, tick } from "@izakyl/blockwright-minecraft";
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
import { twins } from "../shared/harness/stage";

const BOOK_ITEM = "folkways:colony_book";
const KEY_V = 86;
const KEY_SPACE = 32;
const KEY_LEFT_SHIFT = 340;
const KEY_TAB = 258;
const KEY_BACKSPACE = 259;
const MARKED_SIZE = /^(\d+)x(\d+)x(\d+) marked/;

for (const stage of twins()) test(stage.title("view from above: the book's box gesture worked with the mouse from a camera off the player"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("overhead-server")), instance: seededServer(stage.name(`overhead-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("overhead-client")), instance: stage.name(`overhead-client-${runId}`) },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const plot = {
      min: { x: origin.x - 12, y: origin.y, z: origin.z - 20 },
      max: { x: origin.x + 12, y: origin.y + 16, z: origin.z + 12 },
    };
    await stage.lay(server, plot);
    const fill = (from: any, to: any, block: string) =>
      tryCommand(server, `fill ${commandPos(stage.at(from))} ${commandPos(stage.at(to))} ${block}`);
    await fill(plot.min, plot.max, "minecraft:air");
    await fill({ ...plot.min, y: origin.y - 1 }, { ...plot.max, y: origin.y - 1 }, "minecraft:smooth_stone");
    await tryCommand(server, `setblock ${commandPos(stage.at(origin))} minecraft:smooth_stone`);
    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.found = await createColonyViaBook(server, client, playerName, origin, stage);
    evidence.mode = await setBookGesture(server, client, playerName, "box");
    const ground = { x: origin.x + 3, y: origin.y - 1, z: origin.z + 3 };
    const standAt = { x: ground.x + 0.5, y: origin.y, z: ground.z + 0.5 };
    evidence.farm_mark = await markBox(server, client, playerName, standAt, ground, ground, stage);
    evidence.farm = await createMarkedZone(server, client, playerName, origin, "farm", stage);
    await releaseHeldZone(client);
    await tryCommand(server, `tp ${playerName} ${origin.x + 3.5} ${stage.stand(origin).y} ${origin.z + 3.5} 0 0`);
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
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("overhead-hover")}.png`) });
    await screen.clickWidget(client, { label: "Zones" });
    await input.move(client, { x: centre.x, y: centre.y + 32 });
    await frames(client, 6);
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("overhead-hidden")}.png`) });
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
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("overhead-marked")}.png`) });

    await input.move(client, { x: centre.x + 30, y: centre.y - 15 });
    evidence.push = await heldScroll(server, client, [KEY_LEFT_CONTROL], -2);
    const pushed = MARKED_SIZE.exec(evidence.push.bar?.text ?? "");
    expect(pushed, `Ctrl+scroll over the box pushes the face under the mouse: ${evidence.push.bar?.text}`).not.toBeNull();
    expect(Number(pushed![2]), "the face under the mouse is the top, drawn two blocks up towards the camera by scrolling down").toBe(Number(marked![2]) + 2);
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("overhead-pushed")}.png`) });

    await input.click(client, centre, { button: "right" });
    await tick.sprint(server, 10);
    await frames(client, 4);
    const panel = await safeScreen(client);
    evidence.panel = screenName(panel);
    expect(evidence.panel, "a right click opens the book's panel over the view").not.toContain("OverheadScreen");
    expect(evidence.panel).not.toBe("");
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("overhead-panel")}.png`) });

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
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("overhead-line")}.png`) });

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

    // Jump and sneak take the camera straight up and down, and from above sneak does nothing else.
    const height = async () => (await camera.state(client)).camera.position.y;
    const holding = async (keyCode: number, during: () => Promise<void>) => {
      await input.key(client, { keyCode, action: "press" });
      try {
        await during();
      } finally {
        await input.key(client, { keyCode, action: "release" });
      }
    };
    const tap = async (keyCode: number) => {
      await input.key(client, { keyCode, action: "press" });
      await input.key(client, { keyCode, action: "release" });
    };
    evidence.climb = { from: await height() };
    await holding(KEY_SPACE, () => frames(client, 20));
    await frames(client, 2);
    evidence.climb.risen = await height();
    expect(evidence.climb.risen, "jump held raises the camera").toBeGreaterThan(evidence.climb.from + 0.5);
    await holding(KEY_LEFT_SHIFT, () => frames(client, 20));
    await frames(client, 2);
    evidence.climb.sunk = await height();
    expect(evidence.climb.sunk, "sneak held lowers the camera").toBeLessThan(evidence.climb.risen - 0.5);

    // What sneak does with a click and the wheel in the player's own eyes has keys of its own from above.
    await input.click(client, centre);
    await tick.sprint(server, 3);
    await frames(client, 4);
    expect((await screen.widgets(client)).map((widget: any) => widget.label), "a click starts a new line")
      .toContain("Discard");
    await drainActionBar(client);
    await tap(KEY_BACKSPACE);
    await tick.sprint(server, 3);
    await frames(client, 4);
    evidence.backspaced = { bar: await readActionBar(client), widgets: (await screen.widgets(client)).map((widget: any) => widget.label) };
    expect(evidence.backspaced.bar.text, "Backspace lets go of the line").toMatch(/^Let go of it/);
    expect(evidence.backspaced.widgets).not.toContain("Discard");
    await drainActionBar(client);
    await tap(KEY_TAB);
    await tick.sprint(server, 5);
    evidence.tabbed = await readActionBar(client);
    expect(evidence.tabbed.text, "Tab turns the book to the next gesture").toMatch(/^Book: /);
    expect(evidence.tabbed.text).not.toMatch(/drawing a line/);

    await input.key(client, { keyCode: KEY_V, action: "press" });
    await input.key(client, { keyCode: KEY_V, action: "release" });
    await frames(client, 4);
    evidence.left = await safeScreen(client);
    expect((evidence.left as any).screen ?? null, "the key again goes back to the player's own eyes").toBeNull();
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach(`${stage.name("overhead-evidence")}.json`, { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});
