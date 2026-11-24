import { createHash } from "node:crypto";
import { expect } from "@playwright/test";
import { clickElement, panelElements, ancestorsOf, scrollElementIntoView, visibleBox } from "../ldlib2";
import { anchorOf, clipBox } from "../ldlib2/rules";
import { defineTask, input, javaTask, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { type Page } from "../shared/ui/colony-pages";
import type { VisualRun } from "../shared/capture/visual-kit";
import { clickUnverified, frames } from "../shared/harness/bw-helpers";
import { waitForPresent, CARD_WINDOW } from "../shared/ui/book-cards";

export { BOOK_PAGES, clickTab, type Page } from "../shared/ui/colony-pages";

/** Expands a colony settings scope and waits for its card control. */
export async function openSettingsScope(server: MinecraftServer, client: MinecraftClient, scope: string, control: string) {
  const hash = createHash("md5").update(`folkways_core:colony_settings/${scope}`).digest();
  hash[6] = (hash[6] & 0x0f) | 0x30;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.toString("hex");
  const id = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  const target = await expandSettingsScope(server, client, id, control);
  await scrollElementIntoView(client, target);
  return target;
}

async function expandSettingsScope(server: MinecraftServer, client: MinecraftClient, id: string, control: string) {
  const heading = `folkways.book.scope.${id}`;
  await waitForPresent(server, client, heading);
  const card = `folkways.book.scope.card.${id}`;
  if (!(await panelElements(client)).some(element => element.id === card && element.displayed !== false)) {
    await clickElement(client, { id: heading }, { scrollIntoView: true });
  }
  let target: ReturnType<typeof anchorOf> | undefined;
  await expect.poll(async () => {
    await tick.sprint(server, 3);
    await frames(client, 3);
    const elements = await panelElements(client);
    const element = elements.find(element => element.id === control
      && ancestorsOf(elements, element).some(parent => parent.id === card));
    target = element ? anchorOf(elements, element) : undefined;
    return target !== undefined;
  }, { timeout: 8_000, message: `Settings scope ${id} must contain ${control}` }).toBe(true);
  return target!;
}

/** Adds one zone of every enabled volume delegation beside the player, in the colony they act for. */
export const ZONE_FIXTURES = defineTask<{ player: string }, { ok: true; zones: Array<{ id: string; type: string }> }>({
  name: "folkways.visual.zone-fixtures",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.level.block.Blocks",
      "io.github.izakyl.folkways.vanilla.groundwork.GroundworkContent",
      "io.github.izakyl.folkways.core.front.engine.Enrollments",
      "io.github.izakyl.folkways.core.front.api.Shape",
      "io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.core.colony.charter.CurrentColony",
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
    if (!(type.shape() instanceof Shape.Volume) || type.disabledReason().isPresent()) {
        continue;
    }
    var cell = player.blockPosition().below().offset(zones.size(), 0, 0);
    var settings = ColonySettings.empty();
    if (type.id().equals(GroundworkContent.ZONE)) {
        // Clearing converts the zone into an order; pending weeding keeps its editor available for capture.
        player.serverLevel().setBlockAndUpdate(cell.below(), Blocks.DIRT.defaultBlockState());
        player.serverLevel().setBlockAndUpdate(cell, Blocks.SHORT_GRASS.defaultBlockState());
        settings = settings.with(GroundworkContent.CLEAR, new ColonySettings.Value.Flag(false))
            .with(GroundworkContent.WEED, new ColonySettings.Value.Flag(true));
    }
    var id = ZoneDrafts.zone(player.serverLevel(), colony, type.id(), cell, cell, settings).orElseThrow().id();
    zones.add(Map.of("id", id.toString(), "type", type.id().toString()));
}
return Sync.stamp(Map.of("ok", true, "zones", zones));
`,
  }),
});

/** Opens one zone's card for the player, as drawing or right-clicking it does; its settings are on the card. */
export const OPEN_ZONE = defineTask<{ player: string; zone: string }, { ok: true }>({
  name: "folkways.visual.open-zone",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.card.net.CardOpen",
      "io.github.izakyl.folkways.core.colony.charter.CurrentColony"],
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
String name = args.string("player");
var player = server.getPlayerList().getPlayerByName(name);
if (player == null) {
    throw Fail.notFound("no online player named " + name);
}
CardOpen.send(player, CurrentColony.of(player).orElseThrow().id(), UUID.fromString(args.string("zone")));
return Sync.stamp(Map.of("ok", true));
`,
  }),
});

export async function capturePageWindows(server: MinecraftServer, client: MinecraftClient, run: VisualRun, page: Page) {
  const capture = async (scope?: string) => {
    const elements = await panelElements(client);
    const openers = elements.filter(element => {
      const parents = ancestorsOf(elements, element);
      return [element, ...parents].every(one => one.displayed !== false && one.visible !== false)
        && (!scope || parents.some(parent => parent.id === scope))
        && element.width > 0 && element.active !== false && element.className.endsWith(".Button")
        && (/^folkways\.(settings|book)\.open\./.test(element.id ?? "")
          || /^folkways\.card\.(items\.|choice\..*\.open$)/.test(element.id ?? ""));
    });
    for (const opener of openers) {
      await clickElement(client, anchorOf(elements, opener), { scrollIntoView: true });
      await tick.sprint(server, 5);
      await frames(client, 6);
      const opened = await panelElements(client);
      const windows = opened.filter(element =>
        [element, ...ancestorsOf(opened, element)].every(one => one.displayed !== false && one.visible !== false) && visibleBox(opened, element)
        && (element.id?.startsWith("folkways.window.close.") || element.id === "folkways.selector.close"
          || element.id === "folkways.choice.cancel")
        && element.id !== "folkways.window.close.colony");
      expect(windows.length, `Opening ${opener.id} did not expose a window; add a scenario for inline editors`).toBeGreaterThan(0);
      const name = `page-${page.name}-open-${scope ? `${scope.slice("folkways.book.scope.card.".length)}-` : ""}${opener.id}`;
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
      if (windows.some(window => window.id === "folkways.choice.cancel")) {
        await expect.poll(async () => {
          const remaining = await panelElements(client);
          return remaining.some(element => element.id === "folkways.choice.cancel" && visibleBox(remaining, element));
        }, { timeout: 8000 }).toBe(false);
      }
    }
  };
  if (page.token !== "folkways.tab.settings") {
    await capture();
    return;
  }

  const elements = await panelElements(client);
  const scopes = elements.filter(element => /^folkways\.book\.scope\.[a-f0-9-]+$/.test(element.id ?? ""));
  expect(scopes.length).toBeGreaterThan(0);
  for (const heading of scopes) {
    const id = heading.id!;
    const key = id.slice("folkways.book.scope.".length);
    const scope = `folkways.book.scope.card.${key}`;
    const title = elements.find(element => element.className.endsWith(".Label")
      && element.text && element.text !== ">" && element.text !== "v"
      && ancestorsOf(elements, element).some(parent => parent.id === id))?.text ?? id;
    const open = (await panelElements(client)).some(element => element.id === scope && element.displayed !== false);
    await expandSettingsScope(server, client, key, "folkways.card.card");
    const expanded = await panelElements(client);
    const card = expanded.find(element => element.id === scope)!;
    const frame = clipBox(expanded, card);
    const line = expanded.find(element => element.className.endsWith(".Label") && element.text
      && element.width > 0 && element.height > 0 && ancestorsOf(expanded, element).some(parent => parent.id === scope));
    await scrollElementIntoView(client, anchorOf(expanded, frame && card.height > frame.height && line ? line : card));
    await input.move(client, { x: 0, y: 0 });
    await frames(client, 3);
    await run.shot(client, `settings-scope-${key}`, { subject: title, worldState: { scope: key }, hard: true });
    await capture(scope);
    if (!open) {
      await clickElement(client, { id }, { scrollIntoView: true });
      await frames(client, 3);
    }
  }
}

export async function captureZoneCards(server: MinecraftServer, client: MinecraftClient, run: VisualRun, player: string) {
  const fixtures = await runColonyTask(server, ZONE_FIXTURES, { player });
  run.note("zone_cards", fixtures.zones);
  // A full groundwork scan must not consume the fixture before its card is photographed.
  await tick.sprint(server, 120);
  for (const zone of fixtures.zones) {
    await runColonyTask(server, OPEN_ZONE, { player, zone: zone.id });
    await tick.sprint(server, 5);
    await expect.poll(async () => (await panelElements(client)).some(element =>
      element.id === CARD_WINDOW && element.width > 0)
      && (await panelElements(client)).some(element => element.id === "folkways.card.body"), { timeout: 8000 }).toBe(true);
    await frames(client, 5);
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, `zone-card-${zone.type.replace(/[:/]/g, ".")}`, {
      subject: `A zone's card and its settings: ${zone.type}`, worldState: zone, hard: true,
    });
    await screen.dismiss(client);
    // Let the server take the close before the next zone opens; a late close would shut the next card.
    await tick.sprint(server, 3);
  }
}
