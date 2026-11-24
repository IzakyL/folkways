import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, screen, tick } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements } from "../ldlib2";
import {
  foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { cardRowsIn, openCardOf, textUnder, waitForPresent } from "../shared/ui/book-cards";
import { settlePanel, typeCell } from "../shared/ui/site-panel";

const COUNT = "folkways.card.number.staffing_count.field";
const PROFILES = "staffing_profiles";
type Scene = { player: string; route: string; zone: string; melee: string; ranged: string };

/** Presses the one act of the profile row naming `profile`, allowing or barring it on the card. */
async function toggleProfile(server: any, client: any, profile: string) {
  await waitForPresent(server, client, `folkways.card.rows.${PROFILES}.0`);
  const elements = await panelElements(client);
  const row = cardRowsIn(elements, PROFILES).find(one => textUnder(elements, one.id).includes(profile));
  expect(row, `a row for ${profile}`).toBeTruthy();
  const before = textUnder(elements, row!.id);
  await clickElement(client, { id: row!.acts[0] }, { scrollIntoView: true });
  // The card is redrawn from the change: wait for it, so typing afterwards lands in the field drawn last.
  await expect.poll(async () => {
    await settlePanel(server, client);
    return textUnder(await panelElements(client), row!.id);
  }).not.toBe(before);
}

test("zone and route cards select interchangeable loadouts and total headcounts without resident pins",
  async ({}, testInfo) => {
  test.setTimeout(360_000);
  const run = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({ server: { instance: seededServer(`staffing-server-${run}`) },
      client: { instance: `staffing-client-${run}` } });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await GROUND.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 2 });
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    const scene = await server.run(SETUP, { player: player.name });
    await openCardOf(server, client, scene.player, scene.route);
    await waitForPresent(server, client, COUNT);
    await toggleProfile(server, client, "Melee guard");
    await toggleProfile(server, client, "Bow guard");
    await expect.poll(async () => (await server.run(READ, scene)).allowed).toBe(2);
    await typeCell(server, client, COUNT, "3");
    await expect.poll(async () => { await tick.sprint(server, 3); return server.run(READ, scene); })
      .toMatchObject({ count: 3, allowed: 2, filled: 2 });
    await media.screenshot(client, { path: testInfo.outputPath("route-staffing.png") });
    expect((await panelElements(client)).some(element => element.id === "folkways.card.button.staffing_retry"),
      "a route offers to set out again").toBe(true);
    await typeCell(server, client, COUNT, "0");
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(READ, scene)).filled; })
      .toBe(0);
    await screen.dismiss(client);
    await openCardOf(server, client, scene.player, scene.zone);
    await waitForPresent(server, client, COUNT);
    await toggleProfile(server, client, "Melee guard");
    await typeCell(server, client, COUNT, "1");
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      return server.run(READ, { ...scene, route: scene.zone });
    })
      .toMatchObject({ count: 1, allowed: 1, filled: 0 });
    // The card is not redrawn under a field being typed in: leave the field first.
    await clickElement(client, { id: "folkways.card.lines" }, { scrollIntoView: true });
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      const elements = await panelElements(client);
      return elements.filter(element => element.displayed && ancestorsOf(elements, element)
        .every(parent => parent.displayed !== false)).map(element => element.text).join(" ");
    }).toContain("Ranged profile required");
    await screen.dismiss(client);
  } catch (error) { pair?.failing(error); throw error; }
  finally { await pair?.teardown(testInfo); }
});

const IMPORTS = ["io.github.izakyl.folkways.core.front.api.ui.Pages",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.vanilla.patrol.PatrolStaffing",
  "io.github.izakyl.folkways.vanilla.patrol.PatrolContent",
  "io.github.izakyl.folkways.vanilla.patrol.SentryPosts",
  "io.github.izakyl.folkways.core.colony.api.Claimed",
  "io.github.izakyl.folkways.core.world.api.WorldPos",
  "io.github.izakyl.folkways.core.item.api.ItemSpec", "net.minecraft.world.item.Items"];
const SETUP = defineTask<{ player: string }, Scene>({ name: "folkways.staffing.ui-setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var player=ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var colony=Pages.colonyOf(player).orElseThrow();var level=player.serverLevel();
var profiles=LoadoutProfiles.of(colony);
var melee=profiles.create("Melee guard");var ranged=profiles.create("Bow guard");
profiles.add(melee.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)));
profiles.add(ranged.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)));
var residents=colony.residents().stream().map(resident->resident.id()).sorted().toList();
profiles.assign(List.of(residents.get(0)),Optional.of(melee.id()));
profiles.assign(List.of(residents.get(1)),Optional.of(ranged.id()));
var at=player.blockPosition().offset(3,0,3);
var route=colony.hold(PatrolContent.BEAT,new Claimed.Line(List.of(WorldPos.of(level,at),
 WorldPos.of(level,at.offset(8,0,0))))).orElseThrow();
var cells=new LinkedHashSet<WorldPos>();
for(int x=0;x<2;x++){for(int z=0;z<2;z++){cells.add(WorldPos.of(level,at.below().offset(x,0,z)));}}
var zone=colony.hold(SentryPosts.ZONE,new Claimed.Area(cells)).orElseThrow();
return Map.of("player",player.getGameProfile().getName(),"route",route.id().toString(),"zone",zone.id().toString(),
 "melee",melee.id().toString(),"ranged",ranged.id().toString());` }) });
const READ = defineTask<Scene, { count: number; allowed: number; filled: number }>({
  name: "folkways.staffing.ui-read", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var player=ctx.server().getPlayerList().getPlayerByName(a.string("player"));
var staffing=PatrolStaffing.of(Pages.colonyOf(player).orElseThrow());var target=UUID.fromString(a.string("route"));
return Map.of("count",staffing.count(target),"allowed",staffing.allowed(target).size(),
 "filled",staffing.members(target).size());` }) });
