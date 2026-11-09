import { expect, test } from "@playwright/test";
import { media, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, scrollElementIntoView, type Element } from "../ldlib2";
import { boardActs, openSite, settlePanel } from "../shared/ui/site-panel";
import { setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { outAbs } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { commandPos, frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { mkdirSync } from "node:fs";
import path from "node:path";

const PAGE = "folkways_vanilla.orders";
const REFUSAL = `folkways.board.refusal.${PAGE}`;

// Existing orders never prevent switching a container's incoming goods filter.
test("a container's filter mode switches on each click, including with an existing order", async ({}, testInfo) => {
  test.setTimeout(300_000);
  const shots = outAbs("e2e", "shots", "container-filter-mode");
  mkdirSync(shots, { recursive: true });
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({ server: { instance: seededServer(`filter-mode-server-${run}`) },
      client: { instance: `filter-mode-client-${run}` } });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await GROUND.lay(server, foundingBox(origin));
    await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    const chest = { x: origin.x + 3, y: origin.y, z: origin.z - 2 };
    await tryCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, player.name, chest, "chest");

    const open = async () => {
      await screen.dismiss(client);
      await tryCommand(server, `tp ${player.name} ${chest.x + 0.5} ${chest.y} ${chest.z + 2.5} 180 30`);
      await openSite(server, client, chest);
      const edits = await boardActs(client, "edit", PAGE);
      const rows = new Map<number, number>();
      for (const edit of edits) rows.set(edit.row, (rows.get(edit.row) ?? 0) + 1);
      const filter = [...rows].find(([, count]) => count === 1)![0];
      const draft = [...rows].find(([, count]) => count === 3)![0];
      const acts = await boardActs(client, "do", PAGE);
      const mode = acts.find(act => act.row > filter && act.row < draft)!;
      // The order is filed from the first row after the draft that offers both maintain and deliver.
      const filing = new Map<number, number>();
      for (const act of acts.filter(act => act.row > draft)) filing.set(act.row, (filing.get(act.row) ?? 0) + 1);
      const fileRow = [...filing].find(([, count]) => count >= 2)?.[0];
      const file = acts.find(act => act.row === fileRow && act.act === 0);
      return { filter, mode: mode.id, file: file?.id };
    };
    const summary = async (filter: number) => {
      const tree = await panelElements(client);
      return textUnder(tree, `folkways.board.row.${PAGE}.${filter}`);
    };
    const refusal = async () => {
      const shown = (await panelElements(client)).find(element => element.id === REFUSAL);
      return shown && shown.displayed !== false ? shown.text ?? "" : "";
    };
    const press = async (id: string) => {
      await scrollElementIntoView(client, { id });
      await clickElement(client, { id }, { scrollIntoView: true });
      await settlePanel(server, client);
      await frames(client, 3);
    };
    const shoot = (name: string) => media.screenshot(client, { path: path.join(shots, `${name}.png`) });

    // Without orders, each click switches the mode.
    let rules = await open();
    expect(await summary(rules.filter)).toMatch(/blacklist/i);
    await scrollElementIntoView(client, { id: rules.mode });
    await shoot("01-blacklist");
    await press(rules.mode);
    expect(await summary(rules.filter), "one click switches to the whitelist").toMatch(/whitelist/i);
    expect(await refusal()).toBe("");
    await shoot("02-whitelist");
    await press(rules.mode);
    expect(await summary(rules.filter), "the next click switches back").toMatch(/blacklist/i);

    // An order filed with no goods chosen is refused, and the page says so under its board until an act goes.
    expect(rules.file, "the order draft offers an act that files it").toBeTruthy();
    await press(rules.file!);
    expect(await refusal(), "a refused act is said on the page").not.toBe("");
    await shoot("02b-refused-on-page");
    await press(rules.mode);
    expect(await refusal(), "the next act that goes through clears the refusal").toBe("");
    await press(rules.mode);
    expect(await summary(rules.filter)).toMatch(/blacklist/i);

    // An empty whitelist still permits ordered cobblestone.
    await setMaintainDemandViaUI(server, client, player.name, chest, "minecraft:cobblestone", 1);
    rules = await open();
    await press(rules.mode);
    expect(await summary(rules.filter), "orders allow switching to an empty whitelist").toMatch(/whitelist/i);
    expect(await refusal()).toBe("");
    await shoot("03-whitelist-with-order");
    await press(rules.mode);
    expect(await summary(rules.filter)).toMatch(/blacklist/i);
    expect(await refusal()).toBe("");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

function textUnder(elements: Element[], token: string): string {
  const parent = elements.find(element => element.id === token)!;
  const words: string[] = [];
  for (let at = parent.index + 1; at < elements.length && elements[at].depth > parent.depth; at++) {
    if (elements[at].displayed && elements[at].text?.trim()) words.push(elements[at].text!);
  }
  return words.join(" ");
}
