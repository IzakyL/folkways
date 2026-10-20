import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, media, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement, panelElements } from "../ldlib2";
import { commandPos, createColonyViaBook, tryCommand, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { markBox, openBookPage, setBookGesture } from "../shared/ui/zone-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { settlePanel } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { clickUnverified, frames, safeScreen, screenOpen, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { around, twins, type Stage } from "../shared/harness/stage";

const BOOK_ITEM = "folkways:colony_book";
const OFFER = "folkways.zoneconfig.offer.folkways.draft";
const CHOOSE = "folkways.draft.choose";
const PATTERN = "folkways.draft.pattern.folkways.terraced_field";
const PREVIEW = "folkways.draft.preview";
const BUILD = "folkways.draft.confirm";

// Setup only: put an unparsed source file on this client's shelf. The book UI must load it itself.
const SETUP_LOCAL_PATTERN = defineTask<{ stem: string }, { path: string; id: string; ui: string }>({
  name: "folkways.draft-preview.setup-local-file",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.plugins.build.draft.Patterns",
      "java.nio.file.Files",
      "java.nio.file.StandardOpenOption",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
String stem = Args.of(ctx).string("stem");
if (!stem.matches("draft_preview_[a-z0-9_-]+")) throw new IllegalArgumentException("invalid fixture name");
String source = Patterns.sources().get(ResourceLocation.parse("folkways:terraced_field"));
if (source == null) throw new IllegalStateException("the server has not offered terraced_field");
var folder = Patterns.SHELF.path();
Files.createDirectories(folder);
var file = folder.resolve(stem + ".star");
Files.writeString(file, source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
return Map.of("path", file.toAbsolutePath().toString(), "id", "local:" + stem,
    "ui", "folkways.draft.pattern.local." + stem);
`,
  }),
});

const CLEANUP_LOCAL_PATTERN = defineTask<{ stem: string }, boolean>({
  name: "folkways.draft-preview.cleanup-local-file",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.plugins.build.draft.Patterns", "java.nio.file.Files"],
    body: `
String stem = Args.of(ctx).string("stem");
if (!stem.matches("draft_preview_[a-z0-9_-]+")) throw new IllegalArgumentException("invalid fixture name");
return Files.deleteIfExists(Patterns.SHELF.path().resolve(stem + ".star"));
`,
  }),
});

// The blocks the preview stands in the world, read off the client.
const PREVIEWED = defineTask<undefined, Record<string, any>>({
  name: "folkways.draft-preview.shown",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    body: `
var preview = Class.forName("io.github.izakyl.folkways.front.client.ShapePreview");
var ghosts = preview.getDeclaredField("ghosts"); ghosts.setAccessible(true);
var offer = preview.getDeclaredField("offer"); offer.setAccessible(true);
return Map.of("ghosts", ((List<?>) ghosts.get(null)).size(), "offer", String.valueOf(offer.get(null)));
`,
  }),
});

// How many build orders the colony bound to the player's book has filed, and patterns it keeps growing.
const SITES = defineTask<{ player: string }, Record<string, any>>({
  name: "folkways.draft-preview.sites",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Books",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.build.BuildContent",
    ],
    body: `
var player = ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var id = Books.heldColony(player).orElseThrow();
var colony = ColonyGround.of(ctx.server(), id).orElseThrow();
var presence = colony.service(BuildContent.ID, Object.class).orElseThrow();
var book = presence.getClass().getDeclaredField("book"); book.setAccessible(true);
var held = book.get(presence);
var orders = held.getClass().getDeclaredMethod("allBuildOrders"); orders.setAccessible(true);
var growing = presence.getClass().getDeclaredField("growing"); growing.setAccessible(true);
return Map.of("sites", ((List<?>) orders.invoke(held)).size(), "growing", ((Map<?, ?>) growing.get(presence)).size());
`,
  }),
});

for (const stage of twins()) test(stage.title("a local file pattern is previewed before it is built, and the window opens on it again"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  const localStem = `draft_preview_${runId.toLowerCase()}`;
  let pair: E2EPair | undefined;
  let localFileCreated = false;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("draft-preview-server")), instance: seededServer(stage.name(`draft-preview-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("draft-preview-client")), instance: stage.name(`draft-preview-client-${runId}`) },
    });
    const { server, client } = pair;

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const plot = around([origin], 14, 10);
    await stage.lay(server, plot);
    const fill = (from: any, to: any, block: string) =>
      tryCommand(server, `fill ${commandPos(stage.at(from))} ${commandPos(stage.at(to))} ${block}`);
    await fill(plot.min, plot.max, "minecraft:air");
    await fill({ ...plot.min, y: origin.y - 1 }, { ...plot.max, y: origin.y - 1 }, "minecraft:smooth_stone");
    // The field looks for ground under the floor it is marked on. Under a ship's one-block deck there is only air,
    // so the ship gets a hull below it; on the ground that layer lies below the world, which is ground enough.
    await fill({ ...plot.min, y: origin.y - 2 }, { ...plot.max, y: origin.y - 2 }, "minecraft:smooth_stone");
    await tryCommand(server, `setblock ${commandPos(stage.at(origin))} minecraft:smooth_stone`);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.found = await createColonyViaBook(server, client, playerName, origin, stage);
    evidence.mode = await setBookGesture(server, client, playerName, "box");

    const min = { x: origin.x + 2, y: origin.y - 1, z: origin.z + 2 };
    const max = { x: origin.x + 10, y: origin.y + 3, z: origin.z + 10 };
    // Beside the block the book is opened on, not inside it.
    const standAt = { x: origin.x + 0.5, y: origin.y, z: origin.z - 0.5 };
    // A glass post stands up to the far corner for it to be clicked on, and is gone again once it has been. It stands
    // on the floor rather than floating: aboard, a block touching nothing else of the ship becomes a ship of its own.
    const post = { ...max, y: origin.y };
    await tryCommand(server, `fill ${commandPos(stage.at(post))} ${commandPos(stage.at(max))} minecraft:glass`);
    evidence.mark = await markBox(server, client, playerName, standAt, min, max, stage);
    await tryCommand(server, `fill ${commandPos(stage.at(post))} ${commandPos(stage.at(max))} minecraft:air`);
    await tick.sprint(server, 3);
    expect(evidence.mark.marked.text, "the two corners mark a box over the floor").toMatch(/marked$/);
    evidence.sites_before = await sites(server, playerName);

    evidence.local_file = await client.run(SETUP_LOCAL_PATTERN, { stem: localStem });
    localFileCreated = true;

    // Both shelves appear through the normal book UI; preview the actual client file.
    await openOffer(server, client, playerName, origin, stage);
    await waitForElement(client, { id: evidence.local_file.ui }, { timeoutMs: 8_000 });
    evidence.offered = await tokensOf(client);
    expect(evidence.offered, "the picker keeps the built-in pattern beside the local file")
      .toEqual(expect.arrayContaining([PATTERN, evidence.local_file.ui]));
    await clickElement(client, { id: evidence.local_file.ui });
    await settlePanel(server, client);
    evidence.first = await tokensOf(client);
    expect(evidence.first, "the pattern's page offers a preview").toContain(PREVIEW);
    expect(evidence.first, "nothing is built before it has been previewed").not.toContain(BUILD);

    await drainActionBar(client);
    await clickUnverified(client, { id: PREVIEW });
    // It is drawn off the client's thread, and the window closes on the preview once it has come back.
    await expect.poll(async () => {
      await settlePanel(server, client);
      evidence.after_preview = await safeScreen(client);
      return screenOpen(evidence.after_preview);
    }, { timeout: 8_000, message: "the preview closes the window" }).toBe(false).catch(async (error) => {
      // A drawing that came back refused leaves the window open with the reason on the page.
      const said = (await panelElements(client)).map((one) => one.text).filter(Boolean);
      throw new Error(`the preview did not close the window; the page says ${JSON.stringify(said)}\n${error}`);
    });
    evidence.told = await readActionBar(client);
    expect(evidence.told.text, "the player is told to come back to the window").toMatch(/back to the screen to confirm/);
    evidence.shown = await client.run(PREVIEWED, undefined);
    expect(evidence.shown.ghosts, "the pattern's blocks stand in the world").toBeGreaterThan(0);
    await frames(client, 4);
    const eye = { x: max.x + 6, y: max.y + 8, z: max.z + 6 };
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("draft-preview")}.png`),
      camera: { position: eye, ...cameraLookingAt(eye, { x: (min.x + max.x) / 2, y: min.y, z: (min.z + max.z) / 2 }) } });
    evidence.sites_previewed = await sites(server, playerName);
    expect(evidence.sites_previewed.sites + evidence.sites_previewed.growing, "a preview files no build site")
      .toBe(evidence.sites_before.sites + evidence.sites_before.growing);

    // Back in the window it opens on the same pattern, and can now be built.
    await openBookPage(server, client, playerName, origin, stage);
    await waitForElement(client, { id: BUILD }, { timeoutMs: 8_000 });
    evidence.again = await tokensOf(client);
    expect(evidence.again, "the window opens on the previewed pattern again").toEqual(expect.arrayContaining([PREVIEW, BUILD]));

    await clickUnverified(client, { id: BUILD });
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      const now = await sites(server, playerName);
      return now.sites + now.growing;
    }, { timeout: 8_000, message: "building files a site" }).toBeGreaterThan(evidence.sites_before.sites + evidence.sites_before.growing);
    evidence.built = await client.run(PREVIEWED, undefined);
    expect(evidence.built.ghosts, "once built the preview is gone").toBe(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    try {
      if (pair && localFileCreated) {
        evidence.local_file_deleted = await pair.client.run(CLEANUP_LOCAL_PATTERN, { stem: localStem });
      }
    } finally {
      const file = testInfo.outputPath("local-pattern-evidence.json");
      writeFileSync(file, JSON.stringify(evidence, null, 2));
      await testInfo.attach(stage.name("evidence"), { path: file, contentType: "application/json" });
      await pair?.teardown(testInfo);
    }
  }
});

async function openOffer(server: MinecraftServer, client: MinecraftClient, playerName: string, standBlock: any, stage: Stage) {
  await openBookPage(server, client, playerName, standBlock, stage);
  await waitForElement(client, { id: OFFER }, { timeoutMs: 8_000 });
  await clickElement(client, { id: OFFER });
  await settlePanel(server, client);
  await waitForElement(client, { id: CHOOSE }, { timeoutMs: 8_000 });
  await clickElement(client, { id: CHOOSE });
  await settlePanel(server, client);
  await waitForElement(client, { id: PATTERN }, { timeoutMs: 8_000 });
}

async function sites(server: MinecraftServer, playerName: string) {
  return (await server.run(SITES, { player: playerName })) as { sites: number; growing: number };
}

async function tokensOf(client: MinecraftClient): Promise<string[]> {
  return (await panelElements(client)).filter(one => one.width > 0 && one.height > 0 && one.displayed !== false)
    .map((one) => String(one.id ?? "")).filter(Boolean);
}
