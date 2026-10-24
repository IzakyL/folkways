import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, typeIntoElement, waitForElement } from "../ldlib2";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { ADMIT_SETTLERS, runColonyTask } from "../shared/colony/colony-tasks";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { clickTab } from "../shared/ui/colony-pages";
import { wheelCell } from "../shared/ui/site-panel";

const CELL = "folkways.resident.defaults.folkways.resident.folkways.hauling";
const READ = defineTask<{ player: string }, { allowed: boolean; priority: number; residents: Array<{ id: string; allowed: boolean; priority: number }> }>({
  name: "folkways.defaults.read", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.charter.CurrentColony",
    "io.github.izakyl.folkways.front.engine.colony.Residents",
    "io.github.izakyl.folkways.core.engine.colony.WorkDefaults",
    "io.github.izakyl.folkways.core.api.vocation.Vocations",
    "io.github.izakyl.folkways.plugins.person.PersonBody",
  ], body: `
var server=ctx.server();var player=server.getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var colony=CurrentColony.of(player).orElseThrow();var trade=Vocations.required(Vocations.HAULING);
var rule=WorkDefaults.of(colony,PersonBody.ID).of(trade.id());
var rows=new ArrayList<Map<String,Object>>();
for(var body:Residents.of(colony,server)) {
 var known=body.licences().trade(trade).orElseThrow();
 rows.add(Map.of("id",body.id().toString(),"allowed",body.licences().allows(known),
  "priority",body.licences().keennessOf(known).number()));
}
return Map.of("allowed",rule.allowed(),"priority",rule.priority().number(),"residents",rows);
` }),
});
const NARROW = defineTask<void, number>({
  name: "folkways.defaults.narrow", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc=Minecraft.getInstance();mc.options.guiScale().set(3);mc.resizeDisplay();
return mc.getWindow().getGuiScaledWidth();
` }),
});

test("future human work defaults remain editable with an empty or filtered roster", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const run = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { instance: seededServer(`work-defaults-server-${run}`), trace: tracePath("e2e", `work-defaults-server-${run}`) },
      client: { instance: `work-defaults-client-${run}`, trace: tracePath("e2e", `work-defaults-client-${run}`) },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await world.command(server, `gamemode survival ${player}`);
    await GROUND.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, player, { origin, residentCount: 0 });
    // Supply beds while keeping the roster empty until after the template is edited.
    for (const x of [0, 2]) {
      const foot = { x: origin.x + x, y: origin.y, z: origin.z };
      await world.command(server, `setblock ${commandPos(foot)} minecraft:red_bed[part=foot,facing=south]`);
      await world.command(server, `setblock ${commandPos({ ...foot, z: foot.z + 1 })} minecraft:red_bed[part=head,facing=south]`);
      expect((await optInViaBook(server, client, player, foot, "bed")).member).toBe(true);
    }
    await openColonyPanelViaBook(server, client, player, founded.standBlock);
    await clickTab(client, "folkways.tab.residents");
    await waitForElement(client, { id: CELL });
    expect((await server.run(READ, { player })).residents).toHaveLength(0);
    await expect.poll(() => text(client, CELL)).toBe("3");
    await clickElement(client, { id: CELL });
    await expect.poll(() => server.run(READ, { player })).toMatchObject({ allowed: false, priority: 3 });
    await wheelCell(server, client, CELL, -1);
    await expect.poll(() => server.run(READ, { player })).toMatchObject({ allowed: false, priority: 2 });
    await runColonyTask(server, ADMIT_SETTLERS, { player, colony_id: String(founded.evidence.colonyId), count: 1 });
    await expect.poll(async () => (await server.run(READ, { player })).residents).toEqual([
      { id: expect.any(String), allowed: false, priority: 2 },
    ]);
    await typeIntoElement(client, { id: "folkways.resident.search" }, "name=__nobody__");
    await clickElement(client, { id: "folkways.resident.defaults.reset" });
    await expect.poll(() => server.run(READ, { player })).toMatchObject({ allowed: true, priority: 3,
      residents: [{ id: expect.any(String), allowed: false, priority: 2 }] });
    await expect.poll(() => text(client, CELL)).toBe("3");
    await clickElement(client, { id: "folkways.resident.defaults.fold" });
    await frames(client, 3);
    const folded = await panelElements(client);
    const cell = folded.find(one => one.id === CELL)!;
    expect(ancestorsOf(folded, cell).some(one => one.displayed === false)).toBe(true);
    await clickElement(client, { id: "folkways.resident.defaults.fold" });
    await client.run(NARROW, undefined);
    await frames(client, 5);
    await clickTab(client, "folkways.tab.residents");
    await waitForElement(client, { id: CELL });
    await media.screenshot(client, { path: testInfo.outputPath("defaults-narrow.png") });
    await screen.dismiss(client);
    await runColonyTask(server, ADMIT_SETTLERS, { player, colony_id: String(founded.evidence.colonyId), count: 1 });
    await tick.sprint(server, 5);
    const rows = (await server.run(READ, { player })).residents;
    expect(rows).toHaveLength(2);
    expect(rows.map(({ allowed, priority }) => ({ allowed, priority }))).toEqual(expect.arrayContaining([
      { allowed: false, priority: 2 }, { allowed: true, priority: 3 },
    ]));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function text(client: MinecraftClient, id: string): Promise<string | undefined> {
  return (await panelElements(client)).find(one => one.id === id)?.text;
}
