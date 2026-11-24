import {
  ancestorsOf,
  childrenOf,
  clickElement,
  type Element,
  LDLIB2,
  type PanelRead,
  panelElements,
  scrollElementIntoView,
  selectElement,
  HOVER_REFRESH_FRAMES,
} from "../../ldlib2";
import { anchorOf, clipBox } from "../../ldlib2/rules";
import { PANEL_ROUTES } from "../../ldlib2/routes";
import {
  defineTask,
  input,
  javaTask,
  media,
  player,
  tick,
  type MinecraftClient,
  type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import {
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
} from "../harness/bw-helpers";
import { GROUND, type Stage } from "../harness/stage";

type Vec = { x: number; y: number; z: number };


const OPEN_ATTEMPTS = 3;


export async function openContainerScreen(client: MinecraftClient, chest: Vec, attempts = OPEN_ATTEMPTS,
  stage: Stage = GROUND) {
  let opened: any = null;
  let seen: any = null;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    await safeAction(() => frames(client, 5));
    opened = await safeAction(() => player.useBlock(client, stage.at(chest), { face: "up" }));
    await waitForAnyScreen(client, 8_000);
    seen = await safeScreen(client);
    if (screenOpen(seen)) {
      return opened;
    }
  }
  throw new Error(`site: no screen opened on the chest at ${chest.x},${chest.y},${chest.z}`
    + ` after ${attempts} attempts (use=${JSON.stringify(opened)}, screen=${JSON.stringify(screenName(seen))})`);
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
const KEY_END = 269;

async function expectControl(
  client: MinecraftClient, token: string, ...kinds: string[]
): Promise<Element> {
  const found = selectElement(await panelElements(client), { id: token });
  if (!found) {
    throw new Error(`panel: no ${token} on this page`);
  }
  if (!kinds.some((kind) => found.className.endsWith(`.${kind}`))) {
    throw new Error(`panel: ${token} is a ${found.className}, not a ${kinds.join(" or ")} - `
      + "this gesture does not fit the control, and forcing it would silently do nothing");
  }
  return found;
}

const TYPE_ATTEMPTS = 2;

const READ_FIELD_TEXT = defineTask<Record<string, unknown>, PanelRead>({
  name: "folkways.panel.read-field-text",
  side: "client",
  modules: [LDLIB2],
  source: javaTask({ body: "return blockwright.ldlib2.Ldlib2.panel(ctx, Args.of(ctx));" }),
});

async function fieldText(client: MinecraftClient, token: string): Promise<string | undefined> {
  const read = await client.run(READ_FIELD_TEXT, {
    routes: { ...PANEL_ROUTES, select: { ...PANEL_ROUTES.select, text: "getRawText()", ownText: "getRawText()" } },
  });
  return selectElement(read.elements, { id: token })?.text;
}

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

    tried.focus = await clickElement(client, { id: token }, { scrollIntoView: true });
    // Clear the text with unmodified editing keys and check the buffer before typing.
    const previous = await fieldText(client, token);
    if (previous === undefined) {
      throw new Error(`panel: could not read ${token} before typing`);
    }
    await tap("end", () => input.key(client, { keyCode: KEY_END }));
    for (const _ of previous) {
      await tap("backspace", () => input.key(client, { keyCode: KEY_BACKSPACE }));
    }
    tried.cleared = await fieldText(client, token);
    if (refused.length === 0 && tried.cleared !== "") {
      throw new Error(`panel: ${token} still held ${JSON.stringify(tried.cleared)} after clearing`);
    }
    tried.typed = await tap("type", () => input.key(client, { text: value }));
    tried.commit = await tap("commit", () => input.key(client, { keyCode: KEY_ENTER }));
    await settlePanel(server, client);

    tried.actual = await fieldText(client, token);
    tried.refused = refused;
    evidence.attempts.push(tried);
    if (refused.length === 0) {
      if (tried.actual !== value) {
        throw new Error(`panel: ${token} text after commit was ${JSON.stringify(tried.actual)}, `
          + `expected ${JSON.stringify(value)} (attempts=${JSON.stringify(evidence.attempts)})`);
      }
      evidence.verified = "client-text";
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
  // A flag is a switch on the colony's own pages and a box to tick on a zone's.
  evidence.control = (await expectControl(client, token, "Switch", "Toggle")).className;
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
  const ancestors = ancestorsOf(elements, row).reverse();
  const scroller = ancestors.find(element => element.className.endsWith(".ScrollerView"));
  const viewport = ancestors.find(element => element.classes?.includes("__scroller_view_view-port__"));
  if (!scroller || !viewport) {
    throw new Error(`panel: ${row.id} is not in a scroll viewport`);
  }
  return { scroller, viewport };
}

function horizontalTrack(elements: Element[], scroller: Element) {
  const bar = childrenOf(elements, scroller).find(element =>
    element.className.includes("Scroller$Horizontal") && element.displayed && element.width > 0);
  if (!bar) {
    throw new Error(`panel: no visible horizontal scrollbar in ${scroller.id ?? scroller.className}`);
  }
  // The Horizontal element includes the two arrow buttons. Only its middle child is the track;
  // clicking the arrows moves one step, so those positions cannot calibrate the scroll range.
  const track = childrenOf(elements, bar).find(element =>
    element.className.endsWith(".UIElement") && element.width > 0 && element.height > 0);
  if (!track) {
    throw new Error(`panel: no track inside horizontal scrollbar ${bar.index}`);
  }
  return track;
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

// A row wider than its scroller can still be clipped on x after it has been wheeled into the page's view.
async function revealVertically(client: MinecraftClient, anchor: string) {
  try {
    await scrollElementIntoView(client, { id: anchor });
  } catch (error) {
    if (!String(error).includes("a mouse wheel only scrolls vertically")) {
      throw error;
    }
  }
}

export async function scrollCellIntoView(
  server: MinecraftServer, client: MinecraftClient, anchor: string, token: string,
  visibility: "full" | "center" = "full",
) {
  await revealVertically(client, anchor);
  const elements = await panelElements(client);
  const cell = selectElement(elements, { id: token });
  const row = selectElement(elements, { id: anchor });
  if (!cell || !row) {
    throw new Error(`panel: no ${cell ? anchor : token} on this page`);
  }
  const { scroller, viewport } = scrollViewAround(elements, row);
  const view = { x: viewport.contentX, width: viewport.contentWidth };
  const evidence: any = { anchor, token, viewport: view, cell: { x: cell.x, width: cell.width } };
  // Center clicks round to a whole pixel, so keep that point inside the viewport.
  const center = cell.width / 2;
  const left = visibility === "center" ? view.x - center + 1 : view.x;
  const right = view.x + view.width - (visibility === "center" ? center + 1 : cell.width);
  const inside = (x: number) => x >= left && x <= right;
  if (inside(cell.x)) {
    evidence.settled = "already";
    return evidence;
  }

  let trackElement = horizontalTrack(elements, scroller);
  const clip = clipBox(elements, trackElement);
  const trackY = Math.round(trackElement.contentY + trackElement.contentHeight / 2);
  const trackClipped = clip !== undefined && (trackY < clip.y || trackY >= clip.y + clip.height);
  if (trackClipped) {
    // The Workforce track follows the last roster row and can be below the page viewport.
    trackElement = await scrollElementIntoView(client, anchorOf(elements, trackElement));
  }
  const track = {
    x: trackElement.contentX,
    y: Math.round(trackElement.contentY + trackElement.contentHeight / 2),
    width: trackElement.contentWidth,
  };
  evidence.track = track;
  const atRight = await clickTrackAt(server, client, track, 1, token);
  const atLeft = await clickTrackAt(server, client, track, 0, token);
  evidence.calibration = { at_left: atLeft, at_right: atRight };
  const span = atRight - atLeft;
  if (span === 0) {
    throw new Error(`panel: clicking both ends of the track leaves ${token} at ${atLeft} (viewport ${JSON.stringify(view)}): `
      + "the area will not scroll horizontally");
  }
  const ends = [(left - atLeft) / span, (right - atLeft) / span]
    .map((fraction) => Math.min(Math.max(fraction, 0), 1))
    .sort((a, b) => a - b);
  let wanted = (ends[0] + ends[1]) / 2;
  evidence.fraction = wanted;
  let landed = await clickTrackAt(server, client, track, wanted, token);
  evidence.landed = landed;
  for (let nudge = 0; nudge < TRACK_NUDGES && !inside(landed); nudge++) {
    const off = landed < left ? left - landed : right - landed;
    const next = Math.min(Math.max(wanted + off / span, 0), 1);
    wanted = next;
    landed = await clickTrackAt(server, client, track, next, token);
    evidence[`nudge_${nudge}`] = { off, fraction: next, landed };
  }
  if (!inside(landed)) {
    throw new Error(`panel: ${token} scrolled to ${landed} outside the accepted range [${left}, ${right}] (${JSON.stringify(evidence)})`);
  }
  if (trackClipped) {
    await revealVertically(client, anchor);
  }
  evidence.settled = true;
  return evidence;
}
