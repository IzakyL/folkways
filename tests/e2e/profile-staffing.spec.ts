import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements } from "../ldlib2";
import {
  foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { waitForElement } from "../shared/harness/bw-helpers";
import { settlePanel, typeCell } from "../shared/ui/site-panel";

const KEY = "folkways.staffing.";
type Scene = { player: string; route: string; zone: string; melee: string; ranged: string };

test("zone and route panels select interchangeable loadouts and total headcounts without resident pins",
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
    await waitForElement(client, { id: KEY + "allowed" });
    expect((await panelElements(client)).some(element => element.id === KEY + "title")).toBe(false);
    await clickElement(client, { id: KEY + "allowed" });
    await clickElement(client, { id: `folkways.choice.option.${scene.melee}` });
    await clickElement(client, { id: `folkways.choice.option.${scene.ranged}` });
    await clickElement(client, { id: "folkways.choice.confirm" });
    await settlePanel(server, client);
    await expect.poll(async () => (await server.run(READ, scene)).allowed).toBe(2);
    await typeCell(server, client, KEY + "headcount_label", "3");
    await expect.poll(async () => { await tick.sprint(server, 3); return server.run(READ, scene); })
      .toMatchObject({ count: 3, allowed: 2, filled: 2 });
    const routeUi = await panelElements(client);
    expect(routeUi.filter(element => element.displayed && element.visible !== false
      && ancestorsOf(routeUi, element).every(parent => parent.displayed && parent.visible !== false))
      .map(element => element.text).join(" ")).not.toContain("This type has no configurable parameters.");
    await typeCell(server, client, KEY + "headcount_label", "0");
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(READ, scene)).filled; })
      .toBe(0);
    await server.run(SELECT, { player: scene.player, target: scene.zone });
    await waitForElement(client, { id: KEY + "allowed" });
    await settlePanel(server, client);
    await clickElement(client, { id: KEY + "allowed" });
    await clickElement(client, { id: `folkways.choice.option.${scene.melee}` });
    await clickElement(client, { id: "folkways.choice.confirm" });
    await settlePanel(server, client);
    await typeCell(server, client, KEY + "headcount_label", "1");
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      return server.run(READ, { ...scene, route: scene.zone });
    })
      .toMatchObject({ count: 1, allowed: 1, filled: 0 });
    await expect.poll(async () => (await panelElements(client)).filter(element => element.displayed)
      .map(element => element.text).join(" ")).toContain("Ranged profile required");
    await screen.dismiss(client);
  } catch (error) { pair?.failing(error); throw error; }
  finally { await pair?.teardown(testInfo); }
});

const IMPORTS = ["io.github.izakyl.folkways.front.api.ui.Pages",
  "io.github.izakyl.folkways.front.ui.screen.ColonyShell",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.plugins.patrol.PatrolStaffing",
  "io.github.izakyl.folkways.plugins.patrol.PatrolContent",
  "io.github.izakyl.folkways.plugins.patrol.SentryPosts",
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
ColonyShell.editSelection(player,route.id());
return Map.of("player",player.getGameProfile().getName(),"route",route.id().toString(),"zone",zone.id().toString(),
 "melee",melee.id().toString(),"ranged",ranged.id().toString());` }) });
const SELECT = defineTask<{ player: string; target: string }, boolean>({
  name: "folkways.staffing.ui-select", side: "server",
  source: javaTask({ imports: IMPORTS, body: `var a=Args.of(ctx);
var player=ctx.server().getPlayerList().getPlayerByName(a.string("player"));
ColonyShell.editSelection(player,UUID.fromString(a.string("target")));return true;` }) });
const READ = defineTask<Scene, { count: number; allowed: number; filled: number }>({
  name: "folkways.staffing.ui-read", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var player=ctx.server().getPlayerList().getPlayerByName(a.string("player"));
var staffing=PatrolStaffing.of(Pages.colonyOf(player).orElseThrow());var target=UUID.fromString(a.string("route"));
return Map.of("count",staffing.count(target),"allowed",staffing.allowed(target).size(),
 "filled",staffing.members(target).size());` }) });
