import { expect } from "@playwright/test";
import { clickElement, panelElements, ancestorsOf, visibleBox } from "../ldlib2";
import { defineTask, input, javaTask, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { type Page } from "../shared/ui/colony-pages";
import type { VisualRun } from "../shared/capture/visual-kit";
import { clickUnverified, frames } from "../shared/harness/bw-helpers";

export { PAGES, clickTab, discoverPages, type Page } from "../shared/ui/colony-pages";

/** Adds one zone of every volume delegation beside the player, in the colony they act for. */
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
      "io.github.izakyl.folkways.front.engine.charter.CurrentColony",
    ],
    body: `
MinecraftServer server = ctx.server();
String name = Args.of(ctx).string("player");
var player = server.getPlayerList().getPlayerByName(name);
if (player == null) {
    throw Fail.notFound("no online player named " + name);
}
var colony = CurrentColony.of(player).orElseThrow();
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
