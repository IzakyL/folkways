import { expect, test } from "@playwright/test";
import { media, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { cardRows, openBlockCard, waitForPresent } from "../shared/ui/book-cards";
import { ORDER_MAINTAIN, ORDERS_HERE, filedOrdersAt, setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { outAbs } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { commandPos, frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { mkdirSync } from "node:fs";
import path from "node:path";

const MODE = "folkways.card.choice.orders_filter_mode";
const GOODS = "minecraft:cobblestone";

// Existing orders never prevent switching a container's incoming goods filter.
test("a container's filter mode switches between its two choices, including with an existing order", async ({}, testInfo) => {
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

    const press = async (id: string) => {
      await waitForPresent(server, client, id);
      await clickElement(client, { id });
      await tick.sprint(server, 5);
      await frames(client, 3);
    };
    const chip = (mode: "except" | "only") => `${MODE}.folkways_core:filter/${mode}`;
    const assertMode = (mode: "except" | "only") => expect.poll(async () => {
      await tick.sprint(server, 3);
      return (await panelElements(client)).find(element => element.id === chip(mode))?.classes;
    }).toContain("folkways_chosen");
    const shoot = (name: string) => media.screenshot(client, { path: path.join(shots, `${name}.png`) });

    await openBlockCard(server, client, player.name, chest);
    await assertMode("except");
    await waitForPresent(server, client, chip("except"));
    await shoot("01-blacklist");
    await press(chip("only"));
    await assertMode("only");
    await shoot("02-whitelist");
    await press(chip("except"));
    await assertMode("except");

    await press(ORDER_MAINTAIN);
    expect(await cardRows(client, ORDERS_HERE), "a draft with no item cannot file an order").toHaveLength(0);

    await setMaintainDemandViaUI(server, client, player.name, chest, GOODS, 1);
    await openBlockCard(server, client, player.name, chest);
    await press(chip("only"));
    await assertMode("only");
    expect(await cardRows(client, ORDERS_HERE)).toHaveLength(1);
    await shoot("03-whitelist-with-order");
    await press(chip("except"));
    await assertMode("except");
    expect(await filedOrdersAt(server, player.name, chest, GOODS)).toEqual([{ high: 1, standing: true }]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
