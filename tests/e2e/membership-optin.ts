import { screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../ldlib2";
import { chooseInSelector, optInViaBook } from "./colony-founding";
import {
  boardAct,
  boardActs,
  boardRows,
  openSite,
  typeCell,
} from "./site-panel";
import { frames, safeAction, tryCommand, softly, waitForElement } from "../shared/bw-helpers";

type Vec = { x: number; y: number; z: number };

const ORDERS_PAGE = "folkways.orders";
const ACT_MAINTAIN = 0;
const ACT_DELIVER = 1;
const FILED_POLLS = 12;

export async function optInContainerViaUI(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
) {
  return optInViaBook(server, client, playerName, chest, "chest");
}

export async function setMaintainDemandViaUI(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count = 1,
) {
  return fileOrderViaSitePanel(server, client, playerName, chest, itemId, count, ACT_MAINTAIN);
}

// The same order, filed from wherever the player stands and looks: a filmed shot keeps its camera still, and
// holds each step for beatMs so a viewer can follow it.
export async function setMaintainDemandInView(
  server: MinecraftServer, client: MinecraftClient, chest: Vec, itemId: string, count = 1, beatMs = 0,
) {
  return fileOrderViaSitePanel(server, client, null, chest, itemId, count, ACT_MAINTAIN, beatMs);
}

export async function deliverOrderViaSitePanel(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count = 1,
) {
  return fileOrderViaSitePanel(server, client, playerName, chest, itemId, count, ACT_DELIVER);
}

// The site's panel open on its order draft, with the draft's item button: what an item dragged in lands on.
export async function openOrderDraft(
  server: MinecraftServer, client: MinecraftClient, chest: Vec,
) {
  const evidence: any = { chest };
  evidence.open = await openSite(server, client, chest);
  evidence.row = await firstOrdersRow(client);
  evidence.edit_item = (await boardAct(client, "edit", ORDERS_PAGE, evidence.row, 0)).id;
  evidence.row_id = `folkways.board.row.${ORDERS_PAGE}.${evidence.row}`;
  return evidence;
}

async function fileOrderViaSitePanel(
  server: MinecraftServer, client: MinecraftClient, playerName: string | null, chest: Vec,
  itemId: string, count: number, act: number, beatMs = 0,
) {
  const evidence: any = { chest, itemId, count, act };
  const beat = () => (beatMs > 0 ? new Promise((resolve) => setTimeout(resolve, beatMs)) : Promise.resolve());
  if (playerName !== null) {
    await tryCommand(server, `tp ${playerName} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
  }
  await tick.sprint(server, 5);
  await screen.dismiss(client);

  evidence.open = await openSite(server, client, chest);
  await beat();
  const before = await ordersRowCount(client);
  evidence.rows_before = before;

  const draftRow = await firstOrdersRow(client);
  const wanted = await boardAct(client, "edit", ORDERS_PAGE, draftRow, 0);
  evidence.edit_item = wanted.id;
  await softly(() => clickElement(client, { id: wanted.id }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));
  await beat();

  // The draft names one item, picked straight from the selector the item act opens.
  evidence.pick = await chooseInSelector(client, itemId, `*${itemId}`, evidence.edit_item);
  await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent", timeoutMs: 8_000 });
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));
  await beat();

  const countRow = await firstOrdersRow(client);
  const howMany = await boardAct(client, "edit", ORDERS_PAGE, countRow, 2);
  evidence.edit_count = howMany.id;
  if (act === ACT_MAINTAIN) {
    const low = await boardAct(client, "edit", ORDERS_PAGE, countRow, 1);
    evidence.set_low = await typeCell(server, client, low.id, "1");
  }
  await beat();
  evidence.set_count = await typeCell(server, client, howMany.id, String(count));
  await beat();

  const fileRow = await fileRowIndex(client);
  const pressed = await boardAct(client, "do", ORDERS_PAGE, fileRow, act);
  evidence.act_id = pressed.id;
  evidence.press = await softly(() => clickElement(client, { id: pressed.id }));

  for (let poll = 0; poll < FILED_POLLS; poll++) {
    await tick.sprint(server, 10);
    await safeAction(() => frames(client, 3));
    evidence.rows_after = await ordersRowCount(client);
    if (evidence.rows_after > before) {
      break;
    }
  }
  await beat();
  await screen.dismiss(client);
  await tick.sprint(server, 20);

  if (!(evidence.rows_after > before)) {
    throw new Error(`order: after pressing ${pressed.id}, the Orders page for the site at ${chest.x},${chest.y},${chest.z}`
      + ` still has only ${evidence.rows_after} rows (${before} before ordering): the order never reached the colony`
      + ` (evidence: ${JSON.stringify(evidence)})`);
  }
  return evidence;
}

async function ordersRowCount(client: MinecraftClient): Promise<number> {
  return (await boardRows(client)).filter((row) => row.page === ORDERS_PAGE).length;
}

async function firstOrdersRow(client: MinecraftClient): Promise<number> {
  const rows = (await boardRows(client)).filter((row) => row.page === ORDERS_PAGE);
  if (rows.length === 0) {
    throw new Error("order: this site's panel has no Orders rows");
  }
  return rows[0].row;
}

async function fileRowIndex(client: MinecraftClient): Promise<number> {
  const acts = await boardActs(client, "do", ORDERS_PAGE);
  const byRow = new Map<number, number>();
  for (const act of acts) {
    byRow.set(act.row, (byRow.get(act.row) ?? 0) + 1);
  }
  for (const [row, count] of byRow) {
    if (count >= 2) {
      return row;
    }
  }
  throw new Error(`order: no order row with two actions on this page (found ${JSON.stringify(acts.map((a) => a.id))})`);
}
