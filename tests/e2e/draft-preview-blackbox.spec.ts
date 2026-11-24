import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { commandPos, createColonyViaBook, tryCommand, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { chooseBookTool, markBox, useBook } from "../shared/ui/zone-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { settlePanel } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { clickUnverified, safeScreen, screenOpen, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { around, twins } from "../shared/harness/stage";

const BOOK_ITEM = "folkways_core:colony_book";
const OFFER = "offer:folkways_vanilla:draft";
const CHOOSE = "folkways.draft.choose";
const PATTERN = "folkways.draft.pattern.folkways_vanilla.terraced_field";
const PREVIEW = "folkways.draft.preview";
const BUILD = "folkways.draft.confirm";

// The blocks the preview stands in the world, read off the client.
const PREVIEWED = defineTask<undefined, Record<string, any>>({
  name: "folkways.draft-preview.shown",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    body: `
var preview = Class.forName("io.github.izakyl.folkways.core.front.client.ShapePreview");
var ghosts = preview.getDeclaredField("ghosts"); ghosts.setAccessible(true);
var offer = preview.getDeclaredField("offer"); offer.setAccessible(true);
return Map.of("ghosts", ((List<?>) ghosts.get(null)).size(), "offer", String.valueOf(offer.get(null)));
`,
  }),
});

// How many build orders the colony bound to the player's book has filed.
const SITES = defineTask<{ player: string }, Record<string, any>>({
  name: "folkways.draft-preview.sites",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.front.api.Books",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.vanilla.build.BuildContent",
    ],
    body: `
var player = ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var id = Books.heldColony(player).orElseThrow();
var colony = ColonyGround.of(ctx.server(), id).orElseThrow();
var presence = colony.service(BuildContent.ID, Object.class).orElseThrow();
var book = presence.getClass().getDeclaredField("book"); book.setAccessible(true);
var held = book.get(presence);
var orders = held.getClass().getDeclaredMethod("allBuildOrders"); orders.setAccessible(true);
return Map.of("sites", ((List<?>) orders.invoke(held)).size());
`,
  }),
});

// The client's memory of a pattern's drawing settings, worked straight through its own save and load: each pattern
// keeps its own, saved knobs conform to the schema as it stands now, and turning the memory off neither reads nor
// writes. Leaves the settings file as it found it.
const MEMORY = defineTask<void, Record<string, boolean>>({
  name: "folkways.drawing-memory.persistence",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.FolkwaysClientConfig",
      "io.github.izakyl.folkways.core.front.api.Schema",
      "io.github.izakyl.folkways.vanilla.build.draft.client.KnobValues",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.nbt.CompoundTag",
      "net.neoforged.fml.loading.FMLPaths",
      "java.nio.file.Files",
    ],
    body: `
var path = FMLPaths.CONFIGDIR.get().resolve("folkways-drawing-settings.properties");
byte[] before = Files.exists(path) ? Files.readAllBytes(path) : null;
boolean enabled = FolkwaysClientConfig.rememberDrawingSettings();
var memory = Class.forName("io.github.izakyl.folkways.vanilla.build.draft.client.RememberedPatternSettings");
var load = memory.getDeclaredMethod("load", String.class);
load.setAccessible(true);
var save = memory.getDeclaredMethod("save", String.class, CompoundTag.class);
save.setAccessible(true);
try {
    FolkwaysClientConfig.rememberDrawingSettings(true);
    var a = new CompoundTag(); a.putInt("width", 99);
    var b = new CompoundTag(); b.putInt("width", -9);
    save.invoke(null, "pattern/folkways_vanilla:memory_a", a);
    save.invoke(null, "pattern/folkways_vanilla:memory_b", b);
    var disk = new java.util.Properties();
    try (var input = Files.newInputStream(path)) { disk.load(input); }
    boolean persisted = disk.getProperty("pattern/folkways_vanilla:memory_a").equals(a.toString())
        && disk.getProperty("pattern/folkways_vanilla:memory_b").equals(b.toString());
    boolean isolated = readMemory(load, "pattern/folkways_vanilla:memory_a").orElseThrow().getInt("width") == 99
        && readMemory(load, "pattern/folkways_vanilla:memory_b").orElseThrow().getInt("width") == -9
        && readMemory(load, "folkways_vanilla:memory_a").isEmpty();
    var stone = ResourceLocation.parse("minecraft:stone");
    var dirt = ResourceLocation.parse("minecraft:dirt");
    var schema = new Schema(List.of(
        new Schema.Setting.Count("width", "", stone, 2, 8, 4),
        new Schema.Setting.Flag("flag", "", stone, true),
        new Schema.Setting.Choice("choice", "", stone, List.of(stone, dirt), stone),
        Schema.Setting.Items.oneBlock("material", "", stone, stone)));
    var restore = KnobValues.class.getDeclaredMethod("restore", Schema.class, CompoundTag.class);
    restore.setAccessible(true);
    var saved = a.copy(); saved.putBoolean("flag", false);
    saved.putString("choice", "minecraft:dirt"); saved.put("material", new CompoundTag());
    var restored = (KnobValues) restore.invoke(null, schema, saved);
    boolean restoredValues = restored.count("width") == 8 && !restored.flag("flag")
        && restored.choice("choice").equals(dirt) && restored.items("material").isEmpty();
    b.putString("choice", "minecraft:missing_option");
    var fallback = (KnobValues) restore.invoke(null, schema, b);
    boolean conformed = fallback.count("width") == 2 && fallback.flag("flag")
        && fallback.choice("choice").equals(stone) && fallback.items("material").isPresent();
    byte[] written = Files.readAllBytes(path);
    FolkwaysClientConfig.rememberDrawingSettings(false);
    boolean disabledRead = readMemory(load, "pattern/folkways_vanilla:memory_a").isEmpty();
    save.invoke(null, "pattern/folkways_vanilla:memory_a", b);
    boolean disabledWrite = java.util.Arrays.equals(written, Files.readAllBytes(path));
    FolkwaysClientConfig.rememberDrawingSettings(true);
    boolean preserved = readMemory(load, "pattern/folkways_vanilla:memory_a").orElseThrow().equals(a);
    return Map.of("persisted", persisted, "isolated", isolated, "restored", restoredValues,
        "conformed", conformed, "disabledRead", disabledRead, "disabledWrite", disabledWrite,
        "preserved", preserved);
} finally {
    FolkwaysClientConfig.rememberDrawingSettings(enabled);
    if (before == null) Files.deleteIfExists(path); else Files.write(path, before);
}
`,
    members: `
@SuppressWarnings("unchecked")
private static Optional<CompoundTag> readMemory(java.lang.reflect.Method load, String key)
        throws ReflectiveOperationException {
    return (Optional<CompoundTag>) load.invoke(null, key);
}
`,
  }),
});

for (const stage of twins()) test(stage.title("a pattern is previewed out in the world before it is built, and the window opens on it again"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
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
    evidence.tool = await chooseBookTool(server, client, OFFER);

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

    // The first time, only a preview can be asked for.
    await useBook(client);
    await settlePanel(server, client);
    await waitForElement(client, { id: CHOOSE }, { timeoutMs: 8_000 });
    await clickElement(client, { id: CHOOSE });
    await settlePanel(server, client);
    await waitForElement(client, { id: PATTERN }, { timeoutMs: 8_000 });
    await clickElement(client, { id: PATTERN });
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
    evidence.sites_previewed = await sites(server, playerName);
    expect(evidence.sites_previewed.sites, "a preview files no build site")
      .toBe(evidence.sites_before.sites);

    // Used again, the book opens the offer's window on the same pattern, which can now be built.
    await useBook(client);
    await waitForElement(client, { id: BUILD }, { timeoutMs: 8_000 });
    evidence.again = await tokensOf(client);
    expect(evidence.again, "the window opens on the previewed pattern again").toEqual(expect.arrayContaining([PREVIEW, BUILD]));

    await clickUnverified(client, { id: BUILD });
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      const now = await sites(server, playerName);
      return now.sites;
    }, { timeout: 8_000, message: "building files a site" }).toBeGreaterThan(evidence.sites_before.sites);
    evidence.built = await client.run(PREVIEWED, undefined);
    expect(evidence.built.ghosts, "once built the preview is gone").toBe(0);

    // The drawing settings' memory lives on the client alone, so it is checked once, on the ground.
    if (stage.kind === "ground") {
      expect(await client.run(MEMORY, undefined, { timeoutMs: 20_000 }), "drawing memory keeps each pattern's own settings")
        .toEqual({ persisted: true, isolated: true, restored: true, conformed: true,
          disabledRead: true, disabledWrite: true, preserved: true });
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach(stage.name("evidence"), { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function sites(server: MinecraftServer, playerName: string) {
  return (await server.run(SITES, { player: playerName })) as { sites: number };
}

async function tokensOf(client: MinecraftClient): Promise<string[]> {
  return (await panelElements(client)).filter(one => one.width > 0 && one.height > 0 && one.displayed !== false)
    .map((one) => String(one.id ?? "")).filter(Boolean);
}
