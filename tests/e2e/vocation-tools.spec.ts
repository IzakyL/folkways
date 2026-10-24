import { expect, test } from "@playwright/test";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { clickTab } from "../shared/ui/colony-pages";

type State = { building: boolean; farming: boolean; crafting: boolean; buildingRules: number; farmingRules: number };
const STATE = defineTask<{ colony: string; reload?: boolean }, State>({
  name: "folkways.tools.state", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.engine.colony.ColonyRegistry",
    "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
    "net.minecraft.resources.ResourceLocation",
    "net.minecraft.server.MinecraftServer",
  ], body: `
var id=UUID.fromString(String.valueOf(ctx.arg("colony")));
if(Boolean.TRUE.equals(ctx.arg("reload")))ColonyFront.forget(id);
var front=ColonyFront.of(ColonyRegistry.get((MinecraftServer)ctx.server()).find(id).orElseThrow().works());
var result=new LinkedHashMap<String,Object>();
for(var name:List.of("building","farming","crafting")) {
 var held=front.settings(ResourceLocation.fromNamespaceAndPath("folkways",name));
 result.put(name,held.valueOf("tool").isPresent());
 if(!name.equals("crafting"))result.put(name+"Rules",held.entries("tool").size());
}
return result;
` }),
});

test("vocation tool editors isolate lists, persist empty, and reset defaults", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const run = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { instance: seededServer(`tools-server-${run}`), trace: tracePath("e2e", `tools-server-${run}`) },
      client: { instance: `tools-client-${run}`, trace: tracePath("e2e", `tools-client-${run}`) },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await GROUND.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, grounded.name, { origin, residentCount: 1 });
    const colony = String(founded.evidence.colonyId);
    await openColonyPanelViaBook(server, client, grounded.name, founded.standBlock);
    await clickTab(client, "folkways.tab.settings");
    const state = (reload = false) => server.run(STATE, { colony, reload });
    expect(await state()).toMatchObject({ building: false, farming: false, crafting: false });
    await clickElement(client, { id: "folkways.settings.open.tool.folkways.building" }, { scrollIntoView: true });
    await waitForElement(client, { id: "folkways.selector.rule_drop.0" });
    for (let rule = 0; rule < 3; rule++) {
      await clickElement(client, { id: "folkways.selector.rule_drop.0" }, { scrollIntoView: true });
    }
    await clickElement(client, { id: "folkways.selector.confirm" });
    await expect.poll(state).toMatchObject({ building: true, buildingRules: 0, farming: false });
    expect(await state(true)).toMatchObject({ building: true, buildingRules: 0, farming: false });
    await clickElement(client, { id: "folkways.settings.open.tool.folkways.farming" }, { scrollIntoView: true });
    await waitForElement(client, { id: "folkways.selector.rule_drop.0" });
    await clickElement(client, { id: "folkways.selector.confirm" });
    await expect.poll(state).toMatchObject({ buildingRules: 0, farming: true, farmingRules: 1 });
    await clickElement(client, { id: "folkways.settings.reset.tool.folkways.building" }, { scrollIntoView: true });
    await expect.poll(state).toMatchObject({ building: false, farming: true, farmingRules: 1 });
    await clickElement(client, { id: "folkways.settings.open.tool.folkways.crafting" }, { scrollIntoView: true });
    await waitForElement(client, { id: "folkways.selector.confirm" });
    expect((await panelElements(client)).some(e => e.id === "folkways.selector.rule_drop.0" && e.displayed)).toBe(false);
    await clickElement(client, { id: "folkways.selector.confirm" });
    await expect.poll(state).toMatchObject({ crafting: true });
    await clickTab(client, "folkways.page.build");
    await waitForElement(client, { id: "folkways.settings.open.tool.folkways.building.folkways.build" });
    await clickElement(client, { id: "folkways.settings.open.tool.folkways.building.folkways.build" }, { scrollIntoView: true });
    await waitForElement(client, { id: "folkways.selector.rule_drop.2" });
    expect(await state(true)).toMatchObject({ building: false, farming: true, crafting: true });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
