import { expect } from "@playwright/test";
import { input, isMinecraftError, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements } from "../../ldlib2";
import { frames } from "../harness/bw-helpers";

export type Page = { name: string; token: string };

/**
 * The book's pages, in the order its navigation lists them: each one's name (id with dots) and its tab's id. They are
 * the book's own; modules show rows on them rather than adding pages.
 */
export const BOOK_PAGES: Page[] = ["overview", "residents", "metrics", "facilities", "settings", "members", "lessons"]
  .map(path => ({ name: `folkways_core.${path}`, token: `folkways.tab.${path}` }));

export async function clickTab(client: MinecraftClient, id: string) {
  for (let attempt = 0; attempt < 64; attempt++) {
    const elements = await panelElements(client);
    const target = elements.find(element => element.id === id);
    const nav = elements.find(element => element.id === "folkways.navigation");
    if (!target || !nav) throw new Error(`Missing navigation or registered page ${id}`);
    if (nav.displayed !== false && target.displayed === false) {
      throw new Error(`The navigation hides page ${id} for this colony; only the book's own pages are listed`);
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

/** Opens the resident's detail from a Workforce name after its server-backed sections have settled. */
export async function openResidentDetail(server: MinecraftServer, client: MinecraftClient, id: string) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await tick.sprint(server, 10);
    await frames(client, 5);
    try {
      await clickElement(client, { id }, { scrollIntoView: true, timeoutMs: 2_000 });
      break;
    } catch (error) {
      // A section can push the roster down while the click aims. Retry only when no press was sent.
      if (attempt === 2 || !(error instanceof Error) || !isMinecraftError(error.cause, "hover-mismatch")) {
        throw error;
      }
    }
  }
  await expect.poll(async () => {
    await tick.sprint(server, 3);
    await frames(client, 3);
    return (await panelElements(client)).some(element => element.id === "folkways.window.close.resident"
      && element.displayed !== false && element.width > 0);
  }, { timeout: 8_000, message: `Resident detail must open from ${id}` }).toBe(true);
}

/**
 * Opens one of the editors the Residents page offers (the name pool, the look pool, default work, equipment or
 * vehicle profiles) in its window of the book, by the editor's id with dots (`folkways_vanilla.names`). The book must
 * be open.
 */
export async function openEditor(client: MinecraftClient, editor: string) {
  await clickTab(client, "folkways.tab.residents");
  await clickElement(client, { id: `folkways.book.open.${editor}` }, { scrollIntoView: true });
  await frames(client, 4);
  for (let attempt = 0; attempt < 40; attempt++) {
    const close = (await panelElements(client)).find(element => element.id === `folkways.window.close.${editor}`);
    if (close && close.displayed !== false && close.width > 0) return;
    await frames(client, 3);
  }
  throw new Error(`The ${editor} editor's window never opened`);
}
