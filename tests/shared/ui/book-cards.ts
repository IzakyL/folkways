import { createHash } from "node:crypto";
import {
  defineTask, javaTask, screen, tick, type MinecraftClient, type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, scrollElementIntoView, type Element } from "../../ldlib2";
import { frames, safeAction, softly, tryCommand, waitForElement } from "../harness/bw-helpers";
import { GROUND, type Stage } from "../harness/stage";
import { clickTab } from "./colony-pages";

type Vec = { x: number; y: number; z: number };

/** A facility's card in a window of its own over the game, and the book's window a card opens in beside its pages. */
export const CARD_WINDOW = "folkways.card.window";
export const BOOK_CARD_WINDOW = "folkways.window.book_card";

/** The book's own pages, by their navigation ids. Modules show rows on them; none adds a page of its own. */
export const BOOK_TABS = {
  overview: "folkways.tab.overview",
  residents: "folkways.tab.residents",
  stock: "folkways.tab.metrics",
  facilities: "folkways.tab.facilities",
  settings: "folkways.tab.settings",
  members: "folkways.tab.members",
  lessons: "folkways.tab.lessons",
} as const;

export type PanelPage = "OVERVIEW" | "RESIDENTS" | "STOCK";

/**
 * The id a module's section of a book page goes by, as `PanelFacilities.id` makes it: a name-based UUID of
 * `folkways_core:panel/<owner>/<PAGE>`.
 */
export function panelSectionId(owner: string, page: PanelPage): string {
  const hash = createHash("md5").update(`folkways_core:panel/${owner}/${page}`, "utf8").digest();
  hash[6] = (hash[6] & 0x0f) | 0x30;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** The element token of a module's section on a book page. */
export function panelSection(owner: string, page: PanelPage): string {
  return `folkways.book.section.${panelSectionId(owner, page)}`;
}

export type CardRow = { source: string; row: number; id: string; acts: string[] };

const ROW = /^folkways\.card\.rows\.(.+)\.(\d+)$/;
const ACT = /^folkways\.card\.rows\.(.+)\.(\d+)\.act\.(\d+)$/;

/**
 * The rows a card shows, with their acts, in order: the card's whole tree, or only what lies under `within` (a book
 * section, say, since several sections on one page share a source's row ids).
 */
export function cardRowsIn(elements: Element[], source?: string, within?: string): CardRow[] {
  const root = within === undefined ? undefined : elements.find(element => element.id === within);
  if (within !== undefined && !root) return [];
  const inside = (element: Element) => root === undefined
    || ancestorsOf(elements, element).some(ancestor => ancestor === root || ancestor.id === root.id);
  const rows = new Map<string, CardRow>();
  for (const element of elements) {
    const id = element.id ?? "";
    const row = ROW.exec(id);
    if (row && !ACT.test(id) && (source === undefined || row[1] === source) && inside(element)) {
      rows.set(id, { source: row[1], row: Number(row[2]), id, acts: [] });
    }
  }
  for (const element of elements) {
    const act = ACT.exec(element.id ?? "");
    if (!act || !inside(element)) continue;
    const owner = rows.get(`folkways.card.rows.${act[1]}.${act[2]}`);
    if (owner && !owner.acts.includes(act[0])) owner.acts.push(act[0]);
  }
  return [...rows.values()].sort((a, b) => a.row - b.row);
}

export async function cardRows(client: MinecraftClient, source?: string, within?: string): Promise<CardRow[]> {
  return cardRowsIn(await panelElements(client), source, within);
}

/** The text drawn under one element (a row, a line), joined. */
export function textUnder(elements: Element[], id: string): string {
  const root = elements.find(element => element.id === id);
  if (!root) return "";
  return [root, ...elements.filter(element => ancestorsOf(elements, element).some(ancestor => ancestor.id === id))]
    .map(element => element.text ?? "").filter(Boolean).join(" ");
}

const FACILITY_AT = defineTask<{ player: string; x: number; y: number; z: number }, { id: string | null }>({
  name: "folkways.shared.facility-at",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "io.github.izakyl.folkways.core.front.api.ui.Pages",
      "io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities"],
    body: `
var a = Args.of(ctx);
var player = ctx.server().getPlayerList().getPlayerByName(a.string("player"));
var colony = Pages.colonyOf(player).orElseThrow();
var at = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var held = HeldFacilities.at(player.serverLevel(), colony, at);
var out = new HashMap<String, Object>();
out.put("id", held.map(one -> one.id().toString()).orElse(null));
return out;
`,
  }),
});

/** The facility the player's colony holds at a block (a container, a bed, a station), if it holds one there. */
export async function facilityAt(server: MinecraftServer, playerName: string, at: Vec): Promise<string | null> {
  return (await server.run(FACILITY_AT, { player: playerName, ...at })).id;
}

const OPEN_CARD = defineTask<{ player: string; facility: string }, { ok: boolean }>({
  name: "folkways.shared.open-card",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.api.ui.Pages", "io.github.izakyl.folkways.core.card.net.CardOpen"],
    body: `
var a = Args.of(ctx);
var player = ctx.server().getPlayerList().getPlayerByName(a.string("player"));
var colony = Pages.colonyOf(player).orElseThrow();
CardOpen.send(player, colony.id(), UUID.fromString(a.string("facility")));
return Map.of("ok", true);
`,
  }),
});

/**
 * Opens a facility's card window the way the server does once a facility is drawn or clicked: for fixtures whose
 * subject is the card, not how it was reached.
 */
export async function openCardOf(server: MinecraftServer, client: MinecraftClient, playerName: string, facility: string) {
  await server.run(OPEN_CARD, { player: playerName, facility });
  await tick.sprint(server, 3);
  const window = await waitForElement(client, { id: CARD_WINDOW }, { timeoutMs: 8_000 });
  await settleCard(server, client);
  return window;
}

/** Waits for a card to be drawn from its first status (it says it is waiting until then). */
export async function settleCard(server: MinecraftServer, client: MinecraftClient, timeoutMs = 8_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    await tick.sprint(server, 2);
    await safeAction(() => frames(client, 3));
    const elements = await panelElements(client);
    if (elements.some(element => element.id === "folkways.card.body" || element.id === "folkways.card.card")) return;
    if (Date.now() >= deadline) throw new Error("card: no status reached the card window");
  }
}

const OPEN_ATTEMPTS = 3;

/**
 * Opens the card of a block the colony holds by using the book on it, as a player does: the player stands beside
 * it, aims at it and right-clicks. The card opens in a window of its own.
 */
export async function openBlockCard(
  server: MinecraftServer, client: MinecraftClient, playerName: string, block: Vec, stage: Stage = GROUND,
) {
  const evidence: any = { block, attempts: [] };
  let missed: unknown;
  for (let attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
    const seen = stage.stand(block);
    if (attempt > 0) {
      // A resident wandering onto the spot catches the aim before the block does; step it aside.
      await tryCommand(server, `tp @e[type=folkways_vanilla:resident,x=${block.x + 0.5},y=${block.y + 1},`
        + `z=${block.z + 0.5},distance=..2.5] ${block.x + 4.5} ${block.y + 1} ${block.z + 4.5}`);
    }
    await tryCommand(server, `tp ${playerName} ${seen.x + 0.5} ${seen.y} ${seen.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await safeAction(() => screen.dismiss(client));
    evidence.attempts.push(await softly(() => stage.click({ server, client }, block, { player: playerName, button: "right" })));
    try {
      evidence.window = await waitForElement(client, { id: CARD_WINDOW }, { timeoutMs: 8_000 });
      await settleCard(server, client);
      return evidence;
    } catch (error) {
      missed = error;
    }
  }
  throw new Error(`card: using the book on ${JSON.stringify(block)} opened no card: ${missed} `
    + `(evidence: ${JSON.stringify(evidence)})`);
}

/** Closes a card window (and whatever screen is under it); the view from above, if open, comes back by itself. */
export async function closeCard(server: MinecraftServer, client: MinecraftClient) {
  const closed = await safeAction(() => screen.dismiss(client));
  await tick.sprint(server, 3);
  return closed;
}

/**
 * Opens a facility's card from the book's Facilities page: the page is shown, the facility's row pressed, and its
 * card drawn in the book's card window. The book must be open.
 */
export async function openFacilityInBook(server: MinecraftServer, client: MinecraftClient, facility: string) {
  await clickTab(client, BOOK_TABS.facilities);
  const row = `folkways.book.facility.${facility}`;
  await expectPoll(server, client, async () => (await panelElements(client)).some(element => element.id === row),
    `the Facilities page never listed ${facility}`);
  await clickElement(client, { id: row }, { scrollIntoView: true });
  await tick.sprint(server, 3);
  await expectPoll(server, client, async () => (await panelElements(client)).some(element =>
    element.id === BOOK_CARD_WINDOW && element.displayed !== false), "the book's card window never opened");
  await expectPoll(server, client, async () => (await panelElements(client)).some(element =>
    element.id === "folkways.card.body"), "the book's card window never drew the card");
}

/** Shows a book page and waits until a module's section on it has drawn rows. */
export async function openSection(
  server: MinecraftServer, client: MinecraftClient, tab: string, owner: string, page: PanelPage,
): Promise<CardRow[]> {
  await clickTab(client, tab);
  const within = panelSection(owner, page);
  let rows: CardRow[] = [];
  await expectPoll(server, client, async () => (rows = await cardRows(client, undefined, within)).length > 0,
    `the ${owner} section of ${tab} drew no rows`);
  return rows;
}

async function expectPoll(server: MinecraftServer, client: MinecraftClient, check: () => Promise<boolean>,
  message: string, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await check()) return;
    if (Date.now() >= deadline) throw new Error(message);
    await tick.sprint(server, 3);
    await safeAction(() => frames(client, 3));
  }
}

/**
 * Waits until an element is on the panel at all, scrolled out of sight or not (a card's controls below its fold are),
 * then scrolls it into view.
 */
export async function waitForPresent(server: MinecraftServer, client: MinecraftClient, id: string, timeoutMs = 8_000) {
  await expectPoll(server, client, async () => (await panelElements(client)).some(element => element.id === id),
    `no ${id} on the panel`, timeoutMs);
  await scrollElementIntoView(client, { id });
}
