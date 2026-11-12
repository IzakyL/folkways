import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, scrollElementIntoView } from "../ldlib2";
import { LDLIB2 } from "../ldlib2/module";
import { PANEL_ROUTES } from "../ldlib2/routes";
import {
  foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { commandPos } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { seededServer } from "../shared/harness/mod-settings";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { GROUND, type Vec } from "../shared/harness/stage";

const REASON = "Unsafe test container";
const UNUSABLE = defineTask<Vec, boolean>({
  name: "folkways.store-usability.fixture", side: "server",
  source: javaTask({ imports: ["java.lang.reflect.Proxy", "net.minecraft.core.BlockPos",
    "net.minecraft.resources.ResourceLocation", "net.minecraft.network.chat.Component",
    "io.github.izakyl.folkways.core.inventory.api.port.StorePort",
    "io.github.izakyl.folkways.core.inventory.api.port.StorePorts",
    "io.github.izakyl.folkways.core.inventory.api.port.Usability"], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var pos = new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z"));
var original = StorePorts.at(level, pos).orElseThrow();
var unusable = (StorePort) Proxy.newProxyInstance(StorePort.class.getClassLoader(), new Class<?>[] {StorePort.class},
    (proxy, method, values) -> {
        if (method.getName().equals("usability")) {
            return Usability.because(Component.literal("Unsafe test container"));
        }
        return method.invoke(original, values);
    });
StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "e2e_unusable"),
    StorePorts.SPECIFIC + 1, (world, at) -> world == level && at.equals(pos)
        ? Optional.of(unusable) : Optional.empty());
return true;
` }),
});

// Read the displayed text components, including their actual formatting, without consulting colony state.
const LABELS = defineTask<{ routes: typeof PANEL_ROUTES }, Array<{ text: string; colors: number[] }>>({
  name: "folkways.store-usability.visible-labels", side: "client", modules: [LDLIB2],
  source: javaTask({ imports: ["blockwright.ldlib2.Panel", "blockwright.minecraft.core.Reflect",
    "net.minecraft.client.Minecraft", "net.minecraft.network.chat.Component",
    "net.minecraft.network.chat.Style"], body: `
var routes = Panel.Routes.of(Args.of(ctx));
var located = Panel.locate((Minecraft) ctx.client(), routes);
var tree = Panel.read(located.modularUI(), routes, 64, 32000, Panel.Node.ALL);
var result = new ArrayList<Object>();
for (var node : tree.nodes()) {
    if (!node.displayed || !node.visible) {
        continue;
    }
    if (node.text == null || !List.of("Chest", "Barrel").contains(node.text)
            && !node.text.contains("Unsafe test container")) {
        continue;
    }
    Object value = Reflect.navigate(node.target, "getText()");
    if (value instanceof Component component) {
        var colors = new ArrayList<Integer>();
        component.visit((style, text) -> {
            if (style.getColor() != null) {
                colors.add(style.getColor().getValue());
            }
            return Optional.empty();
        }, Style.EMPTY);
        result.add(Map.of("text", component.getString(), "colors", colors));
    }
}
return result;
` }),
});

test("the book admits usable and unusable stores; the colony panel greys the latter and explains why",
  async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  const run = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({ server: { instance: seededServer(`store-usability-server-${run}`) },
      client: { instance: `store-usability-client-${run}` } });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = {
      x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4,
    };
    await GROUND.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 0 });
    const chest = { x: origin.x + 3, y: origin.y, z: origin.z + 3 };
    const barrel = { x: origin.x + 6, y: origin.y, z: origin.z + 3 };
    await world.command(server, `setblock ${commandPos(chest)} minecraft:chest`);
    await world.command(server, `setblock ${commandPos(barrel)} minecraft:barrel`);
    await server.run(UNUSABLE, barrel);
    for (const at of [chest, barrel]) {
      expect((await optInViaBook(server, client, player.name, at, "chest")).member).toBe(true);
    }
    await screen.dismiss(client);
    await tick.sprint(server, 40);
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await clickElement(client, { id: "folkways.page.wares" }, { scrollIntoView: true });
    await expect.poll(async () => (await panelElements(client)).some(element => element.text?.includes(REASON)),
      { timeout: 15_000 }).toBe(true);
    await scrollElementIntoView(client, { text: REASON });
    const labels = await client.run(LABELS, { routes: PANEL_ROUTES });
    expect(labels.find(label => label.text === "Chest"), "the usable chest remains listed").toBeTruthy();
    expect(labels.find(label => label.text === "Barrel")?.colors, "the unusable store name is grey")
      .toContain(0xAAAAAA);
    const reason = labels.find(label => label.text.includes(REASON));
    expect(reason?.text).toMatch(/unusable/i);
    expect(reason?.colors, "the explanation is grey too").toContain(0xAAAAAA);
    await testInfo.attach("store-labels.json", {
      body: JSON.stringify(labels, null, 2), contentType: "application/json",
    });
    await media.screenshot(client, { path: testInfo.outputPath("unusable-store.png") });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
