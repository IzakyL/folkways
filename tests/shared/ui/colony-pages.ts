import { expect } from "@playwright/test";
import { defineTask, input, javaTask, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements } from "../../ldlib2";
import { runColonyTask } from "../colony/colony-tasks";
import { frames } from "../harness/bw-helpers";

export type Page = { name: string; token: string };

/**
 * Every registered colony page: its name (id with dots) and its title key. Given a player, only the pages their
 * colony's navigation shows; a page such as supply stays out of it until the colony has something to show there.
 */
export const PAGES = defineTask<{ player?: string }, { ok: true; pages: Page[] }>({
  name: "folkways.visual.pages",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.front.api.ui.Pages",
      "io.github.izakyl.folkways.front.ui.screen.PageKinds"],
    body: `
var name = Args.of(ctx).string("player");
var player = name.isEmpty() ? null : ((MinecraftServer)ctx.server()).getPlayerList().getPlayerByName(name);
Optional<Colony> colony = player == null ? Optional.empty() : Pages.colonyOf(player);
var pages = new ArrayList<Map<String, Object>>();
for (var page : PageKinds.all()) {
    if (colony.isPresent() && !PageKinds.shows(colony.get(), page.id())) continue;
    pages.add(Map.of("name", page.id().toString().replace(':', '.').replace('/', '.'), "token", page.nameKey()));
}
return Map.of("ok", true, "pages", pages);
`,
  }),
});

export async function discoverPages(server: MinecraftServer, player?: string): Promise<Page[]> {
  const result = await runColonyTask(server, PAGES, { player: player ?? "" });
  expect(result.pages.length, "The runtime page registry is empty").toBeGreaterThan(0);
  return result.pages;
}

export async function clickTab(client: MinecraftClient, id: string) {
  for (let attempt = 0; attempt < 64; attempt++) {
    const elements = await panelElements(client);
    const target = elements.find(element => element.id === id);
    const nav = elements.find(element => element.id === "folkways.navigation");
    if (!target || !nav) throw new Error(`Missing navigation or registered page ${id}`);
    if (nav.displayed !== false && target.displayed === false) {
      throw new Error(`The navigation hides page ${id} for this colony; discover pages with the player to skip it`);
    }
    // On a narrow panel the list of pages is folded behind the menu button.
    const menu = elements.find(element => element.id === "folkways.navigation.menu");
    if (nav.displayed === false && menu && menu.displayed !== false) {
      await clickElement(client, { id: menu.id });
      await frames(client, 3);
      continue;
    }
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
