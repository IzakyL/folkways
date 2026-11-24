import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, typeIntoElement } from "../ldlib2";
import { anchorOf } from "../ldlib2/rules";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { BOOK_TABS, waitForPresent } from "../shared/ui/book-cards";
import { clickTab } from "../shared/ui/colony-pages";

type State = { building: boolean; farming: boolean; crafting: boolean; buildingRules: number; farmingRules: number };
const STATE = defineTask<{ colony: string; reload?: boolean }, State>({
  name: "folkways.tools.state", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.colony.internal.ColonyRegistry",
    "io.github.izakyl.folkways.core.front.engine.colony.ColonyFront",
    "net.minecraft.resources.ResourceLocation",
    "net.minecraft.server.MinecraftServer",
  ], body: `
var id=UUID.fromString(String.valueOf(ctx.arg("colony")));
if(Boolean.TRUE.equals(ctx.arg("reload")))ColonyFront.forget(id);
var front=ColonyFront.of(ColonyRegistry.get((MinecraftServer)ctx.server()).find(id).orElseThrow().works());
var result=new LinkedHashMap<String,Object>();
for(var name:List.of("building","farming","crafting")) {
 var held=front.settings(ResourceLocation.fromNamespaceAndPath("folkways_vanilla",name));
 result.put(name,held.valueOf("tool").isPresent());
 if(!name.equals("crafting"))result.put(name+"Rules",held.entries("tool").size());
}
return result;
` }),
});

test("vocation tool editors isolate lists and persist empty", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
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
    await clickTab(client, BOOK_TABS.settings);
    const openTools = async (trade: string) => {
      const hash = createHash("md5").update(`folkways_core:colony_settings/folkways_vanilla:${trade}`).digest();
      hash[6] = (hash[6] & 0x0f) | 0x30;
      hash[8] = (hash[8] & 0x3f) | 0x80;
      const id = hash.toString("hex").replace(/^(........)(....)(....)(....)/, "$1-$2-$3-$4-");
      const head = `folkways.book.scope.${id}`;
      await waitForPresent(server, client, head);
      const scope = `folkways.book.scope.card.${id}`;
      if (!(await panelElements(client)).some(element => element.id === scope && element.displayed)) {
        await clickElement(client, { id: head });
      }
      const control = async () => {
        const elements = await panelElements(client);
        const tool = elements.find(element => element.id === "folkways.card.items.tool"
          && ancestorsOf(elements, element).some(ancestor => ancestor.id === scope));
        return tool && anchorOf(elements, tool);
      };
      await expect.poll(async () => {
        await tick.sprint(server, 3);
        return control();
      }).toBeDefined();
      await clickElement(client, (await control())!, { scrollIntoView: true });
    };
    const state = (reload = false) => server.run(STATE, { colony, reload });
    expect(await state()).toMatchObject({ building: false, farming: false, crafting: false });
    await openTools("building");
    await waitForElement(client, { id: "folkways.selector.rule_drop.0" });
    for (let rule = 0; rule < 3; rule++) {
      await clickElement(client, { id: "folkways.selector.rule_drop.0" }, { scrollIntoView: true });
    }
    await clickElement(client, { id: "folkways.selector.confirm" });
    await expect.poll(state).toMatchObject({ building: true, buildingRules: 0, farming: false });
    expect(await state(true)).toMatchObject({ building: true, buildingRules: 0, farming: false });
    await openTools("farming");
    await waitForElement(client, { id: "folkways.selector.rule_drop.0" });
    await typeIntoElement(client, { id: "folkways.selector.search" }, "minecraft:diamond_hoe");
    await clickElement(client, { id: "folkways.selector.result.minecraft.diamond_hoe" }, { scrollIntoView: true });
    await clickElement(client, { id: "folkways.selector.confirm" });
    await expect.poll(state).toMatchObject({ buildingRules: 0, farming: true, farmingRules: 2, crafting: false });
    await openTools("crafting");
    await waitForElement(client, { id: "folkways.selector.close" });
    expect((await panelElements(client)).some(e => e.id === "folkways.selector.rule_drop.0" && e.displayed)).toBe(false);
    await clickElement(client, { id: "folkways.selector.close" });
    await openTools("building");
    await waitForElement(client, { id: "folkways.selector.close" });
    expect((await panelElements(client)).some(e => e.id === "folkways.selector.rule_drop.0" && e.displayed)).toBe(false);
    await clickElement(client, { id: "folkways.selector.close" });
    expect(await state(true)).toMatchObject({ building: true, buildingRules: 0, farming: true, farmingRules: 2, crafting: false });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
