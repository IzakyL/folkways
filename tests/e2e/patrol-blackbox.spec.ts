import { expect, test } from "@playwright/test";
import { screen } from "@izakyl/blockwright-minecraft";
import { panelElements } from "../ldlib2";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { clickTab, discoverPages } from "../shared/ui/colony-pages";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { twins } from "../shared/harness/stage";
import { mustCommand } from "../shared/ui/world-ui";

// V route movement remains covered by combat-rts-input and formation-patrol.
for (const stage of twins()) test(stage.title("the retired patrol page is absent and colony combat defaults remain accessible"), async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: stage.config,
      server: { instance: stage.name(`retired-patrol-server-${run}`) },
      client: { instance: stage.name(`retired-patrol-client-${run}`) },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    await mustCommand(server, `gamemode survival ${player.name}`);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, player.name, { origin, residentCount: 1, stage });
    await openColonyPanelViaBook(server, client, player.name, stage.at(founding.standBlock));
    const pages = await discoverPages(server, player.name);
    expect(pages.some(page => page.name === "folkways.patrol" || page.token === "folkways.page.patrol")).toBe(false);
    expect(pages.some(page => page.name === "folkways.combat")).toBe(true);
    await clickTab(client, "folkways.page.combat");
    await expect.poll(async () => (await panelElements(client)).some(element =>
      element.id === "folkways.combat.defaults_enabled" && element.active && element.displayed)).toBe(true);
    expect((await panelElements(client)).some(element => element.id === "folkways.page.patrol")).toBe(false);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair) {
      await screen.dismiss(pair.client).catch(() => undefined);
      await pair.teardown(testInfo);
    }
  }
});
