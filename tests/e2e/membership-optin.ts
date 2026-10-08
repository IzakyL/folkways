import { screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../ldlib2";
import { optInViaBook, pickViaSelector } from "./colony-founding";
import {
  boardAct,
  boardActs,
  boardRows,
  firstEmptyGridSlot,
  openSite,
  slotItem,
  typeCell,
} from "./site-panel";
import { frames, safeAction, tryCommand, softly } from "../shared/bw-helpers";

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

export async function deliverOrderViaSitePanel(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count = 1,
) {
  return fileOrderViaSitePanel(server, client, playerName, chest, itemId, count, ACT_DELIVER);
}

export async function openOrderDraftGrid(
  server: MinecraftServer, client: MinecraftClient, chest: Vec,
) {
  const evidence: any = { chest };
  evidence.open = await openSite(server, client, chest);
  const draftRow = await firstOrdersRow(client);
  const wanted = await boardAct(client, "edit", ORDERS_PAGE, draftRow, 0);
  evidence.edit_item = wanted.id;
  await softly(() => clickElement(client, { id: wanted.id }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));
  evidence.slot = await firstEmptyGridSlot(client);
  return evidence;
}

async function fileOrderViaSitePanel(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count: number, act: number,
) {
  const evidence: any = { chest, itemId, count, act };
  await tryCommand(server, `tp ${playerName} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
  await tick.sprint(server, 5);
  await screen.dismiss(client);

  evidence.open = await openSite(server, client, chest);
  const before = await ordersRowCount(client);
  evidence.rows_before = before;

  const draftRow = await firstOrdersRow(client);
  const wanted = await boardAct(client, "edit", ORDERS_PAGE, draftRow, 0);
  evidence.edit_item = wanted.id;
  await softly(() => clickElement(client, { id: wanted.id }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));

  const slot = await firstEmptyGridSlot(client);
  evidence.slot = slot;
  evidence.pick = await pickViaSelector(client, slot, itemId, `*${itemId}`);
  await tick.sprint(server, 5);
  evidence.settled = await slotItem(client, slot);
  if (evidence.settled?.id !== itemId) {
    throw new Error(`order draft: slot ${slot} holds ${JSON.stringify(evidence.settled)}, expected ${itemId}`
      + ` (evidence: ${JSON.stringify(evidence)})`);
  }
  evidence.back = await softly(() => clickElement(client, { id: "folkways.settings.back" }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));

  const countRow = await firstOrdersRow(client);
  const howMany = await boardAct(client, "edit", ORDERS_PAGE, countRow, 2);
  evidence.edit_count = howMany.id;
  if (act === ACT_MAINTAIN) {
    const low = await boardAct(client, "edit", ORDERS_PAGE, countRow, 1);
    evidence.set_low = await typeCell(server, client, low.id, "1");
  }
  evidence.set_count = await typeCell(server, client, howMany.id, String(count));

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
