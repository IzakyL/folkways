import { clickElement, type Element, panelElements, selectElement, HOVER_REFRESH_FRAMES } from "../ldlib2";
import { input, media, player, reflect, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import {
  clickUnverified,
  frames,
  isSafeFailure,
  pressAtHovered,
  safeAction,
  safeScreen,
  screenName,
  screenOpen,
  softly,
  waitForAnyScreen,
  waitForElement,
} from "../shared/bw-helpers";

type Vec = { x: number; y: number; z: number };


const OPEN_ATTEMPTS = 3;
const ENTER_ATTEMPTS = 20;

export type SlotView = { index: number; item: { id: string; count?: number | null } | null };

export type BoardAct = { page: string; row: number; act: number; id: string };

export async function openContainerScreen(client: MinecraftClient, chest: Vec, attempts = OPEN_ATTEMPTS) {
  let opened: any = null;
  let seen: any = null;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    await safeAction(() => frames(client, 5));
    opened = await safeAction(() => player.useBlock(client, { x: chest.x, y: chest.y, z: chest.z }, { face: "up" }));
    await waitForAnyScreen(client, 8_000);
    seen = await safeScreen(client);
    if (screenOpen(seen)) {
      return opened;
    }
  }
  throw new Error(`site: chest screen did not open at ${chest.x},${chest.y},${chest.z}`
    + ` after ${attempts} attempts (use=${JSON.stringify(opened)}, screen=${JSON.stringify(screenName(seen))})`);
}

export async function enterSitePanel(server: MinecraftServer, client: MinecraftClient) {
  await tick.sprint(server, 5);
  await waitForElement(client, { id: "folkways.site.panel" }, { timeoutMs: 8_000 });
  for (let attempt = 0; attempt < ENTER_ATTEMPTS; attempt++) {
    if ((await laidOutBoardRows(client)).length > 0) {
      return;
    }
    await safeAction(() => frames(client, 3));
    await tick.sprint(server, 3);
  }
  const rows = await boardRows(client);
  throw new Error(`site: after the chest opened, the side panel drew no rows`
    + ` (screen=${JSON.stringify(screenName(await safeScreen(client)))}`
    + `; the tree has tokens for ${rows.length} rows but all are zero-size: ${JSON.stringify(rows.map((row) => row.id))})`);
}

async function laidOutBoardRows(client: MinecraftClient) {
  return (await panelElements(client))
    .filter((element) => element.width > 0 && /^folkways\.board\.row\./.test(element.id ?? ""));
}

export async function openSite(server: MinecraftServer, client: MinecraftClient, chest: Vec) {
  const opened = await openContainerScreen(client, chest);
  const entered = await enterSitePanel(server, client);
  return { opened, entered };
}

async function panelIds(client: MinecraftClient): Promise<string[]> {
  const elements = await panelElements(client);
  return elements.map((element) => element.id).filter((id): id is string => typeof id === "string");
}

export async function boardRows(client: MinecraftClient): Promise<{ page: string; row: number; id: string }[]> {
  return (await panelIds(client))
    .map((id) => /^folkways\.board\.row\.(.+)\.(\d+)$/.exec(id))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => ({ page: match[1], row: Number(match[2]), id: match[0] }))
    .sort((a, b) => a.row - b.row);
}

export async function boardActs(client: MinecraftClient, verb: string, page?: string): Promise<BoardAct[]> {
  const pattern = new RegExp(`^folkways\\.board\\.${verb}\\.(.+)\\.(\\d+)\\.(\\d+)$`);
  return (await panelIds(client))
    .map((id) => pattern.exec(id))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => ({ page: match[1], row: Number(match[2]), act: Number(match[3]), id: match[0] }))
    .filter((found) => page === undefined || found.page === page)
    .sort((a, b) => (a.row - b.row) || (a.act - b.act));
}

export async function boardAct(
  client: MinecraftClient, verb: string, page: string, row: number, act = 0,
): Promise<BoardAct> {
  const acts = await boardActs(client, verb, page);
  const found = acts.find((one) => one.row === row && one.act === act);
  if (!found) {
    throw new Error(`site: row ${row} on page ${page} has no ${verb} #${act}`
      + ` (present: ${JSON.stringify(acts.map((one) => one.id))})`);
  }
  return found;
}

async function gridSlots(client: MinecraftClient): Promise<SlotView[]> {
  const result = await reflect.query(client, {
    root: { kind: "static", className: "net.minecraft.client.Minecraft", path: "getInstance().screen.getMenu()" },
    select: { active: "isActive()", itemId: "getItem().getItem().builtInRegistryHolder().key().location().toString()",
      count: "getItem().getCount()" },
    expand: { path: "slots" }, limits: { maxDepth: 1, maxItems: 256 },
  });
  return (result.tree?.children ?? [])
    .filter(node => node.class?.endsWith(".FilterSlot") && node.values?.active === true)
    .map((node, index) => {
      const id = node.values?.itemId;
      return { index, item: typeof id === "string" && id !== "minecraft:air"
        ? { id, count: Number(node.values?.count ?? 1) } : null };
    });
}

export async function slotItem(client: MinecraftClient, index: number): Promise<SlotView["item"]> {
  return (await gridSlots(client))[index]?.item ?? null;
}

export async function firstEmptyGridSlot(client: MinecraftClient): Promise<number> {
  const empty = (await gridSlots(client)).find(slot => !slot.item);
  if (empty === undefined) {
    throw new Error("site: this grid has no empty slot");
  }
  return empty.index;
}

export async function elementBounds(client: MinecraftClient, id: string) {
  const found = selectElement(await panelElements(client), { id });
  return found ? { x: found.x, y: found.y, width: found.width, height: found.height } : null;
}

export async function settlePanel(server: MinecraftServer, client: MinecraftClient) {
  await tick.sprint(server, 2);
  await safeAction(() => frames(client, 3));
}

const KEY_ENTER = 257;
const KEY_BACKSPACE = 259;
const KEY_DELETE = 261;
const KEY_END = 269;

const CLEAR_STROKES = 12;

async function expectControl(
  client: MinecraftClient, token: string, kind: string,
): Promise<Element> {
  const found = selectElement(await panelElements(client), { id: token });
  if (!found) {
    throw new Error(`panel: no ${token} on this page`);
  }
  if (!found.className.endsWith(`.${kind}`)) {
    throw new Error(`panel: ${token} is a ${found.className}, not a ${kind} - `
      + "this gesture does not fit the control, and forcing it would silently do nothing");
  }
  return found;
}

const TYPE_ATTEMPTS = 2;

export async function typeCell(
  server: MinecraftServer, client: MinecraftClient, token: string, value: string,
) {
  const evidence: any = { token, value, attempts: [] };
  evidence.control = (await expectControl(client, token, "TextField")).className;
  for (let attempt = 0; attempt < TYPE_ATTEMPTS; attempt++) {
    const tried: any = { attempt };
    const refused: string[] = [];
    const tap = async (step: string, act: () => Promise<unknown>) => {
      const result = await safeAction(act);
      if (!sent(result)) {
        refused.push(`${step}: ${(result as any)?.reason ?? JSON.stringify(result)}`);
      }
      return result;
    };

    tried.focus = await tap("focus", () => clickUnverified(client, { id: token }));
    await tap("end", () => input.key(client, { keyCode: KEY_END }));
    for (let stroke = 0; stroke < CLEAR_STROKES; stroke++) {
      await tap("backspace", () => input.key(client, { keyCode: KEY_BACKSPACE }));
    }
    for (let stroke = 0; stroke < CLEAR_STROKES; stroke++) {
      await tap("delete", () => input.key(client, { keyCode: KEY_DELETE }));
    }
    tried.typed = await tap("type", () => input.key(client, { text: value }));
    tried.commit = await tap("commit", () => input.key(client, { keyCode: KEY_ENTER }));
    await settlePanel(server, client);

    tried.refused = refused;
    evidence.attempts.push(tried);
    if (refused.length === 0) {
      evidence.verified = "keys-delivered";
      return evidence;
    }
  }
  throw new Error(`panel: typing ${JSON.stringify(value)} into ${token}, `
    + `some keys failed to reach the client on all ${TYPE_ATTEMPTS} attempts, so the field most likely still holds its old value: `
    + `${JSON.stringify(evidence.attempts.map((one: any) => one.refused))}`);
}

function sent(result: unknown): boolean {
  return result != null && !isSafeFailure(result);
}

export async function toggleCell(
  server: MinecraftServer, client: MinecraftClient, token: string,
) {
  const evidence: any = { token };
  evidence.control = (await expectControl(client, token, "Switch")).className;
  evidence.press = await softly(() => clickElement(client, { id: token }));
  await settlePanel(server, client);
  return evidence;
}

export function cellTextIn(elements: Element[], token: string): string | null {
  const press = elements.find((element) => element.id === token);
  if (!press) {
    return null;
  }
  for (let at = press.index + 1; at < elements.length && elements[at].depth > press.depth; at++) {
    if (/(^|\.)Label$/.test(elements[at].className)) {
      return elements[at].text ?? "";
    }
  }
  return null;
}

export async function cellText(client: MinecraftClient, token: string): Promise<string | null> {
  return cellTextIn(await panelElements(client), token);
}

const CHOICE_CONFIRM = "folkways.choice.confirm";
const CHOICE_CANCEL = "folkways.choice.cancel";
const CHOICE_CHOSEN = "folkways_chosen";

export function choiceOptionToken(option: string): string {
  return `folkways.choice.option.${option.replace(/[:/]/g, ".")}`;
}

function buttonTextIn(elements: Element[], token: string): string | null {
  const press = elements.find((element) => element.id === token);
  if (!press) {
    return null;
  }
  for (let at = press.index + 1; at < elements.length && elements[at].depth > press.depth; at++) {
    if (elements[at].text) {
      return elements[at].text ?? "";
    }
  }
  return press.text ?? null;
}

export async function pickChoice(
  server: MinecraftServer, client: MinecraftClient, opener: string, option: string,
  readValue: () => Promise<string | null>, shot?: string | (() => Promise<unknown>),
) {
  const optionToken = choiceOptionToken(option);
  const before = await readValue();
  const evidence: any = { opener, option, option_token: optionToken, before };
  evidence.open = await softly(() => clickElement(client, { id: opener }));
  await waitForElement(client, { id: CHOICE_CANCEL }, { timeoutMs: 8_000 });
  const offered = await panelElements(client);
  const row = selectElement(offered, { id: optionToken });
  if (!row) {
    const present = offered.map((element) => element.id)
      .filter((id): id is string => typeof id === "string" && id.startsWith("folkways.choice.option."));
    await softly(() => clickElement(client, { id: CHOICE_CANCEL }));
    throw new Error(`panel: the picker behind ${opener} offers no ${option} (offered: ${JSON.stringify(present)})`);
  }
  const label = buttonTextIn(offered, optionToken);
  evidence.label = label;
  evidence.preselected = offered.filter((element) => element.classes?.includes(CHOICE_CHOSEN)
    && element.id?.startsWith("folkways.choice.option.")).map((element) => element.id);
  if (label !== null && before === label) {
    evidence.cancel = await softly(() => clickElement(client, { id: CHOICE_CANCEL }));
    await settlePanel(server, client);
    evidence.after = await readValue();
    evidence.changes = 0;
    return evidence;
  }
  evidence.pick = await clickElement(client, { id: optionToken }, { scrollIntoView: true });
  await settlePanel(server, client);
  const picked = await panelElements(client);
  evidence.highlighted = selectElement(picked, { id: optionToken })?.classes?.includes(CHOICE_CHOSEN) ?? false;
  if (!evidence.highlighted) {
    throw new Error(`panel: clicking ${optionToken} did not select it (${JSON.stringify(evidence.pick)})`);
  }
  if (typeof shot === "string") {
    // step: picked_before_confirm. Keep the evidence small: the capture answers the pixels too.
    evidence.shot = await safeAction(async () => {
      const { path, sha256, width, height, frame } = await media.screenshot(client, { path: shot });
      return { path, sha256, width, height, frame };
    });
  } else if (shot) {
    evidence.shot = await shot();
  }
  evidence.while_picking = await readValue();
  if (evidence.while_picking !== before) {
    throw new Error(`panel: picking ${option} behind ${opener} applied it before confirming `
      + `(${JSON.stringify(before)} -> ${JSON.stringify(evidence.while_picking)})`);
  }
  evidence.confirm = await softly(() => clickElement(client, { id: CHOICE_CONFIRM }));
  let after: string | null = before;
  for (let poll = 0; poll < 10 && after === before; poll++) {
    await settlePanel(server, client);
    after = await readValue();
  }
  evidence.after = after;
  if (after === before || (label !== null && after !== label)) {
    throw new Error(`panel: confirming ${option} behind ${opener} left it at ${JSON.stringify(after)} `
      + `(was ${JSON.stringify(before)}, wanted ${JSON.stringify(label)}; ${JSON.stringify(evidence)})`);
  }
  if (selectElement(await panelElements(client), { id: CHOICE_CONFIRM })) {
    throw new Error(`panel: the picker behind ${opener} is still open after confirming`);
  }
  evidence.changes = 1;
  return evidence;
}

export async function pickCell(
  server: MinecraftServer, client: MinecraftClient, token: string, option: string, shot?: string,
) {
  await expectControl(client, token, "Button");
  return pickChoice(server, client, token, option, () => cellText(client, token), shot);
}

export async function buttonText(client: MinecraftClient, token: string): Promise<string | null> {
  return buttonTextIn(await panelElements(client), token);
}

export async function wheelCell(
  server: MinecraftServer, client: MinecraftClient, token: string, direction: 1 | -1,
) {
  const bounds = await elementBounds(client, token);
  if (!bounds) {
    throw new Error(`panel: no ${token} on this page`);
  }
  const x = Math.round(bounds.x + bounds.width / 2);
  const y = Math.round(bounds.y + bounds.height / 2);
  await safeAction(() => input.move(client, { x, y }));
  await safeAction(() => frames(client, HOVER_REFRESH_FRAMES));
  const turn = await safeAction(() => input.scroll(client, { yOffset: direction }));
  await settlePanel(server, client);
  return { token, direction, at: { x, y }, turn };
}

const TRACK_NUDGES = 4;

function scrollViewAround(elements: Element[], row: Element) {
  const boxes = elements
    .filter((element) => element.className.endsWith(".ScrollerView"))
    .filter((element) => element.width > 0 && element.height > 0)
    .filter((element) => row.x >= element.x && row.x <= element.x + element.width)
    .filter((element) => row.y >= element.y && row.y + row.height <= element.y + element.height)
    .sort((a, b) => a.width * a.height - b.width * b.height);
  if (boxes.length === 0) {
    throw new Error(`panel: ${row.id} is not in any scroll area`);
  }
  const view = boxes[0];
  return { x: view.x, y: view.y, width: view.width, height: view.height };
}

function horizontalTrack(elements: Element[], view: { x: number; y: number; width: number; height: number }) {
  const bars = elements
    .filter((element) => element.className.includes("Scroller$Horizontal"))
    .filter((element) => element.width > 0 && element.height > 0)
    .filter((element) => Math.abs(element.x - view.x) <= 1 && Math.abs(element.width - view.width) <= 1)
    .filter((element) => Math.abs(element.y + element.height - (view.y + view.height)) <= 2);
  if (bars.length === 0) {
    throw new Error(`panel: no horizontal scrollbar below viewport ${JSON.stringify(view)} - the area does not overflow horizontally, `
      + "or its horizontalScrollDisplay is NEVER");
  }
  const bar = bars[0];
  return { x: bar.x, y: Math.round(bar.y + bar.height / 2), width: bar.width };
}

async function clickTrackAt(
  server: MinecraftServer, client: MinecraftClient,
  track: { x: number; y: number; width: number }, fraction: number, token: string,
) {
  const x = Math.round(track.x + Math.min(Math.max(fraction, 0), 1) * (track.width - 1));
  await softly(() => pressAtHovered(client, x, track.y));
  await settlePanel(server, client);
  const cell = selectElement(await panelElements(client), { id: token });
  if (!cell) {
    throw new Error(`panel: ${token} is gone from the page after scrolling`);
  }
  return cell.x;
}

export async function scrollCellIntoView(
  server: MinecraftServer, client: MinecraftClient, anchor: string, token: string,
) {
  const elements = await panelElements(client);
  const cell = selectElement(elements, { id: token });
  const row = selectElement(elements, { id: anchor });
  if (!cell || !row) {
    throw new Error(`panel: no ${cell ? anchor : token} on this page`);
  }
  const view = scrollViewAround(elements, row);
  const evidence: any = { anchor, token, viewport: view, cell: { x: cell.x, width: cell.width } };
  const inside = (x: number) => x >= view.x && x + cell.width <= view.x + view.width;
  if (inside(cell.x)) {
    evidence.settled = "already";
    return evidence;
  }

  const track = horizontalTrack(elements, view);
  evidence.track = track;
  const atRight = await clickTrackAt(server, client, track, 1, token);
  const atLeft = await clickTrackAt(server, client, track, 0, token);
  evidence.calibration = { at_left: atLeft, at_right: atRight };
  const span = atRight - atLeft;
  if (span === 0) {
    throw new Error(`panel: clicking both ends of the track leaves ${token} at ${atLeft} (viewport ${JSON.stringify(view)}): `
      + "the area will not scroll horizontally");
  }
  const ends = [(view.x - atLeft) / span, (view.x + view.width - cell.width - atLeft) / span]
    .map((fraction) => Math.min(Math.max(fraction, 0), 1))
    .sort((a, b) => a - b);
  const wanted = (ends[0] + ends[1]) / 2;
  evidence.fraction = wanted;
  let landed = await clickTrackAt(server, client, track, wanted, token);
  evidence.landed = landed;
  for (let nudge = 0; nudge < TRACK_NUDGES && !inside(landed); nudge++) {
    const off = landed < view.x ? view.x - landed : view.x + view.width - cell.width - landed;
    const next = Math.min(Math.max(wanted + off / span, 0), 1);
    landed = await clickTrackAt(server, client, track, next, token);
    evidence[`nudge_${nudge}`] = { off, fraction: next, landed };
  }
  if (!inside(landed)) {
    throw new Error(`panel: ${token} scrolled to ${landed} but is still not fully in the viewport (${JSON.stringify(evidence)})`);
  }
  evidence.settled = true;
  return evidence;
}
