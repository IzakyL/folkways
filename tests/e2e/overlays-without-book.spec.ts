import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, media, screen, tick } from "@izakyl/blockwright-minecraft";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { clickElement, panelElements } from "../ldlib2";
import {
  foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { outAbs } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { frames, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { clickTab } from "../shared/ui/colony-pages";

const BOOK_ITEM = "folkways_core:colony_book";
const SETTING = "folkways.settings.overlays_without_book";

type Drawn = { zones: number; outlines: number; residents: number; cards: number; held: boolean; option: boolean };

// What the client draws of the colony this frame: its zones and residents as received, and the zone outlines and
// cards the last world frame actually put on screen.
const DRAWN = defineTask<void, Drawn>({
  name: "folkways.overlays-without-book.drawn", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.FolkwaysClientConfig",
    "io.github.izakyl.folkways.core.front.client.ColonyHighlightState",
    "io.github.izakyl.folkways.core.front.client.ColonyLookState",
    "io.github.izakyl.folkways.core.front.engine.item.HeldBook",
    "net.minecraft.client.Minecraft",
  ], body: `
var inks = Class.forName("io.github.izakyl.folkways.core.front.client.WorldCards").getDeclaredField("INKS");
inks.setAccessible(true);
return Map.of("zones", ColonyHighlightState.zones().size(), "outlines", ColonyHighlightState.outlinesDrawn(),
  "residents", ColonyLookState.residents().size(),
  "cards", ((Map<?, ?>) inks.get(null)).size(), "held", HeldBook.bound(Minecraft.getInstance().player).isPresent(),
  "option", FolkwaysClientConfig.overlaysWithoutBook());
` }),
});

// A farm beside the founding set, so the colony has a zone to mark on the ground.
const ZONE = defineTask<{ player: string }, string>({
  name: "folkways.overlays-without-book.zone", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts",
    "io.github.izakyl.folkways.core.front.engine.colony.ColonySettings",
    "io.github.izakyl.folkways.core.front.engine.charter.CurrentColony",
    "net.minecraft.resources.ResourceLocation",
  ], body: `
var player = ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var colony = CurrentColony.of(player).orElseThrow();
var at = player.blockPosition().below();
return ZoneDrafts.zone(player.serverLevel(), colony, ResourceLocation.parse("folkways_vanilla:farm"), at.offset(2, 0, 2),
  at.offset(5, 0, 5), ColonySettings.empty()).orElseThrow().id().toString();
` }),
});

test("overlays without the book: hidden by default, shown with empty hands or anything else once ticked", async ({}, testInfo) => {
  test.setTimeout(300_000);
  const shots = outAbs("e2e", "shots", "overlays-without-book");
  mkdirSync(shots, { recursive: true });
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({ server: { instance: seededServer(`overlays-server-${run}`) },
      client: { instance: `overlays-client-${run}` } });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await GROUND.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 2 });
    await server.run(ZONE, { player: player.name });

    const hold = async (item: string) => {
      await tryCommand(server, `item replace entity ${player.name} weapon.mainhand with ${item}`);
      await tryCommand(server, `item replace entity ${player.name} weapon.offhand with minecraft:air`);
    };
    // Faces the nearest resident, so their card is in the frame.
    const face = () => tryCommand(server, `execute as ${player.name} at @s facing entity `
      + `@e[type=!minecraft:player,type=!minecraft:item,sort=nearest,limit=1] eyes run tp @s ~ ~ ~ ~ ~`);
    const drawn = async () => {
      await tick.sprint(server, 3);
      await frames(client, 4);
      return client.run(DRAWN);
    };
    const settled = async (want: (seen: Drawn) => boolean, message: string) => {
      await expect.poll(async () => want(await drawn()), { timeout: 15_000, message }).toBe(true);
      return drawn();
    };
    const shoot = async (name: string) => {
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 3);
      await media.screenshot(client, { path: path.join(shots, `${name}.png`) });
    };

    await hold(BOOK_ITEM);
    await face();
    const withBook = await settled(seen => seen.zones > 0 && seen.outlines > 0 && seen.residents > 0 && seen.cards > 0,
      "with the book in hand the zone's outline and the residents' cards are drawn");
    expect(withBook.option).toBe(false);
    await shoot("01-with-book");

    await hold("minecraft:air");
    await face();
    const hidden = await settled(seen => !seen.held && seen.zones === 0 && seen.outlines === 0 && seen.residents === 0 && seen.cards === 0,
      "by default nothing is drawn without the book, as before");
    expect(hidden.option).toBe(false);
    await shoot("02-empty-hand-default");

    // The player ticks it on the settings page.
    await hold(BOOK_ITEM);
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await clickTab(client, "folkways.tab.settings");
    await waitForElement(client, { id: SETTING }, { timeoutMs: 8_000 });
    await shoot("03-settings-unticked");
    await clickElement(client, { id: SETTING });
    await frames(client, 3);
    expect((await client.run(DRAWN)).option).toBe(true);
    await shoot("04-settings-ticked");
    await screen.dismiss(client);

    // Nothing in hand, then something other than the book: the same overlays either way.
    for (const [item, name] of [["minecraft:air", "05-empty-hand-shown"], ["minecraft:stick", "06-other-item-shown"]]) {
      await hold(item);
      await face();
      const shown = await settled(seen => !seen.held && seen.zones > 0 && seen.outlines > 0 && seen.residents > 0 && seen.cards > 0,
        `ticked, the zone's outline and residents' cards stay drawn holding ${item}`);
      expect(shown.option).toBe(true);
      await shoot(name);
    }

    // Unticked again, it is as before.
    await hold(BOOK_ITEM);
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await clickTab(client, "folkways.tab.settings");
    await waitForElement(client, { id: SETTING }, { timeoutMs: 8_000 });
    await clickElement(client, { id: SETTING });
    await frames(client, 3);
    expect((await client.run(DRAWN)).option).toBe(false);
    await screen.dismiss(client);
    await hold("minecraft:air");
    await settled(seen => !seen.held && seen.zones === 0 && seen.outlines === 0 && seen.residents === 0 && seen.cards === 0,
      "unticked, nothing is drawn without the book again");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
