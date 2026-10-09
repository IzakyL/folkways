import { expect } from "@playwright/test";
import { clickElement, panelElements, ancestorsOf, visibleBox } from "../ldlib2";
import { defineTask, input, javaTask, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";
import type { VisualRun } from "./visual-kit";
import { clickUnverified, frames } from "../shared/bw-helpers";

export type Page = { name: string; token: string };

/** Every registered colony page: its name (id with dots) and its title key. */
export const PAGES = defineTask<Record<string, never>, { ok: true; pages: Page[] }>({
  name: "folkways.visual.pages",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.ui.screen.PageKinds"],
    body: `
var pages = new ArrayList<Map<String, Object>>();
for (var page : PageKinds.all()) {
    pages.add(Map.of("name", page.id().toString().replace(':', '.').replace('/', '.'), "token", page.nameKey()));
}
return Map.of("ok", true, "pages", pages);
`,
  }),
});

/** Adds one zone of every volume delegation beside the player, in the colony their main-hand book names. */
export const ZONE_FIXTURES = defineTask<{ player: string }, { ok: true; zones: Array<{ id: string; type: string }> }>({
  name: "folkways.visual.zone-fixtures",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.server.MinecraftServer",
      "io.github.izakyl.folkways.front.engine.Enrollments",
      "io.github.izakyl.folkways.front.api.Shape",
      "io.github.izakyl.folkways.front.engine.colony.ZoneDrafts",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.front.engine.item.ColonyBookItem",
    ],
    body: `
MinecraftServer server = ctx.server();
String name = Args.of(ctx).string("player");
var player = server.getPlayerList().getPlayerByName(name);
if (player == null) {
    throw Fail.notFound("no online player named " + name);
}
var colony = ColonyGround.of(server, ColonyBookItem.boundColonyId(player.getMainHandItem()).orElseThrow()).orElseThrow();
var zones = new ArrayList<Map<String, Object>>();
for (var type : Enrollments.delegations()) {
    if (!(type.shape() instanceof Shape.Volume)) continue;
    var cell = player.blockPosition().below().offset(zones.size(), 0, 0);
    var id = ZoneDrafts.zone(player.serverLevel(), colony, type.id(), cell, cell, ColonySettings.empty()).orElseThrow().id();
    zones.add(Map.of("id", id.toString(), "type", type.id().toString()));
}
return Sync.stamp(Map.of("ok", true, "zones", zones));
`,
  }),
});

/** Opens the colony shell for the player and the selection editor of one zone. */
export const OPEN_ZONE = defineTask<{ player: string; zone: string }, { ok: true }>({
  name: "folkways.visual.open-zone",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.front.ui.screen.ColonyShell"],
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
String name = args.string("player");
var player = server.getPlayerList().getPlayerByName(name);
if (player == null) {
    throw Fail.notFound("no online player named " + name);
}
ColonyShell.open(player);
ColonyShell.editSelection(player, UUID.fromString(args.string("zone")));
return Sync.stamp(Map.of("ok", true));
`,
  }),
});

export async function discoverPages(server: MinecraftServer): Promise<Page[]> {
  const result = await runColonyTask(server, PAGES, {});
  expect(result.pages.length, "The runtime page registry is empty").toBeGreaterThan(0);
  return result.pages;
}

export async function clickTab(client: MinecraftClient, id: string) {
  for (let attempt = 0; attempt < 64; attempt++) {
    const elements = await panelElements(client);
    const target = elements.find(element => element.id === id);
    const nav = elements.find(element => element.id === "folkways.navigation");
    if (!target || !nav) throw new Error(`Missing navigation or registered page ${id}`);
    if (!ancestorsOf(elements, target).some(ancestor => ancestor.id === nav.id)
        || (target.y >= nav.y && target.y + target.height <= nav.y + nav.height)) {
      await clickElement(client, { id });
      await frames(client, 4);
      await input.move(client, { x: 0, y: 0 });
      return;
    }
    await input.move(client, { x: nav.x + nav.width / 2, y: nav.y + nav.height / 2 });
    await input.scroll(client, { yOffset: target.y < nav.y ? 1 : -1 });
    await frames(client, 3);
  }
  throw new Error(`Could not scroll to registered page ${id}`);
}

export async function capturePageWindows(server: MinecraftServer, client: MinecraftClient, run: VisualRun, page: Page) {
  const elements = await panelElements(client);
  const openers = elements.filter(element => [element, ...ancestorsOf(elements, element)].every(one => one.displayed !== false && one.visible !== false) && element.width > 0
    && element.className.endsWith(".Button")
    && /^(folkways\.settings\.open\.|folkways\.board\.(open|edit)\.)/.test(element.id ?? ""));
  for (const opener of openers) {
    await clickElement(client, { id: opener.id! }, { scrollIntoView: true });
    await tick.sprint(server, 5);
    await frames(client, 6);
    const opened = await panelElements(client);
    const windows = opened.filter(element =>
      [element, ...ancestorsOf(opened, element)].every(one => one.displayed !== false && one.visible !== false) && visibleBox(opened, element)
      // A setting that names one item opens the item search in place of a window; it closes the same way.
      && (element.id?.startsWith("folkways.window.close.") || element.id === "folkways.selector.close")
      && element.id !== "folkways.window.close.colony");
    expect(windows.length, `Opening ${opener.id} did not expose a window; add a scenario for inline editors`).toBeGreaterThan(0);
    const name = `page-${page.name}-open-${opener.id}`;
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, name, {
      subject: `${page.token} → ${opener.id}`,
      worldState: { page, opener: opener.id, windows: windows.map(window => window.id) }, hard: true,
    });
    for (const window of windows.reverse()) {
      await clickUnverified(client, { id: window.id! });
      await tick.sprint(server, 5);
      await frames(client, 4);
    }
  }
}

export async function captureZoneEditors(server: MinecraftServer, client: MinecraftClient, run: VisualRun, player: string) {
  const fixtures = await runColonyTask(server, ZONE_FIXTURES, { player });
  run.note("discovered_zone_editors", fixtures.zones);
  for (const zone of fixtures.zones) {
    await runColonyTask(server, OPEN_ZONE, { player, zone: zone.id });
    await tick.sprint(server, 5);
    await expect.poll(async () => (await panelElements(client)).some(element =>
      element.id === "folkways.selection.back" && element.width > 0), { timeout: 8000 }).toBe(true);
    await frames(client, 5);
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, `zone-editor-${zone.type.replace(/[:/]/g, ".")}`, {
      subject: `Existing zone settings: ${zone.type}`, worldState: zone, hard: true,
    });
    await screen.dismiss(client);
    // Let the server take the close before the next zone opens; a late close would shut the new shell.
    await tick.sprint(server, 3);
  }
}
