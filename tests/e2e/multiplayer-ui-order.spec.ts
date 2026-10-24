import { expect, test } from "@playwright/test";
import {
  defineTask,
  javaTask,
  media,
  tick,
  player,
  screen,
} from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, type Element } from "../ldlib2";
import {
  commandPos,
  createColonyViaBook,
  isColonyMember,
  tryCommand,
  waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { markBox, openBookPage, setBookGesture } from "../shared/ui/zone-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { settlePanel, openSite } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import {
  frames,
  safeScreen,
  screenOpen,
  waitForElement,
} from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND, around } from "../shared/harness/stage";

const SCHEMA_BODY = `
var field=Enrollments.class.getDeclaredField("BY_OWNER");field.setAccessible(true);
var owners=(Map<ResourceLocation,Object>)field.get(null);
if(Args.of(ctx).string("reverse").equals("yes")){var entries=new ArrayList<>(owners.entrySet());Collections.reverse(entries);owners.clear();for(var e:entries)owners.put(e.getKey(),e.getValue());}
if(Args.of(ctx).string("reverse").equals("yes")){
  var entryField=io.github.izakyl.folkways.front.ui.panel.Inlays.class.getDeclaredField("BY_BAY");entryField.setAccessible(true);
  var bays=(Map<?,Map<ResourceLocation,Object>>)entryField.get(null);
  for(var entriesByOwner:bays.values()){var entries=new ArrayList<>(entriesByOwner.entrySet());Collections.reverse(entries);entriesByOwner.clear();for(var e:entries)entriesByOwner.put(e.getKey(),e.getValue());}
}
var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),actor.registryAccess());
buf.writeResourceLocation(ColonyShell.ID);
var menu=PlayerUIMenuType.create(99,actor.getInventory(),buf);
var managed=menu.modularUI.syncManager;
var lookup=UISyncManager.class.getDeclaredField("syncValues");lookup.setAccessible(true);
var table=(IdentityMap<SyncValue<?>>)lookup.get(managed);
var ownValues=UIElement.class.getDeclaredField("syncValues");ownValues.setAccessible(true);
var labels=new IdentityHashMap<SyncValue<?>,String>();
var queue=new ArrayDeque<UIElement>();queue.add(menu.modularUI.ui.rootElement);
while(!queue.isEmpty()){var node=queue.removeFirst();String label=node.getId();var parent=node.getParent();while((label==null||label.isEmpty())&&parent!=null){label=parent.getId();parent=parent.getParent();}for(var value:(List<SyncValue<?>>)ownValues.get(node))labels.put(value,node.getClass().getSimpleName()+"#"+label);queue.addAll(node.getChildren());}
var values=new ArrayList<>(table.values());values.sort(Comparator.comparingInt(table::getID));
var rows=new ArrayList<Object>();boolean remote=actor.level().isClientSide();
for(var value:values)rows.add(Map.of("id",table.getID(value),"type",value.syncValueHolder.type.getTypeName(),"s2c",remote?value.isAcceptSync():value.isToSync(),"c2s",remote?value.isToSync():value.isAcceptSync(),"element",labels.getOrDefault(value,"")));
buf.release();
return Map.of("owners",Enrollments.all().keySet().stream().map(Object::toString).toList(),"values",rows,"pages",io.github.izakyl.folkways.front.ui.screen.PageKinds.every().stream().map(kind->kind.id().toString()).toList());
`;
const SCHEMA_IMPORTS = [
  "io.github.izakyl.folkways.front.engine.Enrollments",
  "io.github.izakyl.folkways.front.ui.screen.ColonyShell",
  "com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType",
  "com.lowdragmc.lowdraglib2.gui.sync.UISyncManager",
  "com.lowdragmc.lowdraglib2.gui.sync.SyncValue",
  "com.lowdragmc.lowdraglib2.utils.IdentityMap",
  "com.lowdragmc.lowdraglib2.gui.ui.UIElement",
  "net.minecraft.network.RegistryFriendlyByteBuf",
  "net.minecraft.resources.ResourceLocation",
  "io.netty.buffer.Unpooled",
  "java.util.*",
];
const SERVER_SCHEMA = defineTask<{ reverse: string }, any>({
  name: "folkways.ui-schema.server",
  side: "server",
  source: javaTask({
    imports: SCHEMA_IMPORTS,
    body:
      "var actor=ctx.server().getPlayerList().getPlayers().getFirst();" +
      SCHEMA_BODY,
  }),
});
const CLIENT_SCHEMA = defineTask<{ reverse: string }, any>({
  name: "folkways.ui-schema.client",
  side: "client",
  source: javaTask({
    imports: [...SCHEMA_IMPORTS, "net.minecraft.client.Minecraft"],
    body: "var actor=Minecraft.getInstance().player;" + SCHEMA_BODY,
  }),
});
const BOOK_ITEM = "folkways:colony_book";
const OFFER = "folkways.zoneconfig.offer.folkways.sweep";
const STORES = "folkways.sweep.kind.folkways.store";
const STATIONS = "folkways.sweep.kind.folkways.station";
const TAKE = "folkways.sweep.take";

for (const stage of [GROUND])
  test(
    stage.title(
      "identical peers keep the UI wire layout when registrations arrive in a different order",
    ),
    async ({}, testInfo) => {
      test.setTimeout(BUDGET.quick);
      ensureOut("e2e");

      const runId = newRunId();
      let pair: E2EPair | undefined;
      const evidence: any = {};

      try {
        pair = await launchPair({
          config: process.env.FOLKWAYS_UI_TEST_CONFIG ?? "blockwright.toml",
          readyTimeoutMs: { server: 90000, client: 90000 },
          server: {
            trace: tracePath("e2e", stage.name("ui-order-server")),
            instance: seededServer(stage.name(`ui-order-server-${runId}`)),
          },
          client: {
            trace: tracePath("e2e", stage.name("ui-order-client")),
            instance: stage.name(`ui-order-client-${runId}`),
          },
        });
        const { server, client } = pair;

        const baselineServer = await server.run(SERVER_SCHEMA, {
          reverse: "no",
        });
        const baselineClient = await client.run(CLIENT_SCHEMA, {
          reverse: "no",
        });
        evidence.schemaBaseline = {
          server: baselineServer,
          client: baselineClient,
        };
        expect(baselineClient.values).toEqual(baselineServer.values);
        expect(baselineClient.pages).toEqual(baselineServer.pages);
        const grounded = await waitForGroundedPlayer(server);
        const playerName = grounded.name;
        const origin = {
          x: Math.round(grounded.pos.x) + 4,
          y: Math.round(grounded.pos.y),
          z: Math.round(grounded.pos.z) + 4,
        };
        const plot = around([origin], 8, 4);
        await stage.lay(server, plot);
        await tryCommand(
          server,
          `fill ${commandPos(stage.at(plot.min))} ${commandPos(stage.at(plot.max))} minecraft:air`,
        );
        await tryCommand(
          server,
          `fill ${commandPos(stage.at({ ...plot.min, y: origin.y - 1 }))} ${commandPos(stage.at({ ...plot.max, y: origin.y - 1 }))} minecraft:smooth_stone`,
        );
        await tryCommand(
          server,
          `setblock ${commandPos(stage.at(origin))} minecraft:smooth_stone`,
        );
        await tryCommand(server, `gamemode survival ${playerName}`);
        await tryCommand(
          server,
          `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`,
        );
        await tick.sprint(server, 5);
        evidence.found = await createColonyViaBook(
          server,
          client,
          playerName,
          origin,
          stage,
        );

        // Two barrels stand at the box's corners, to be clicked on, and a furnace between them. Each is a whole block, so
        // the click aimed at its top lands on it.
        const first = { x: origin.x + 2, y: origin.y, z: origin.z + 2 };
        const furnace = { x: origin.x + 3, y: origin.y, z: origin.z + 2 };
        const last = { x: origin.x + 4, y: origin.y, z: origin.z + 2 };
        for (const [at, block] of [
          [first, "minecraft:barrel"],
          [furnace, "minecraft:furnace"],
          [last, "minecraft:barrel"],
        ] as const) {
          await tryCommand(
            server,
            `setblock ${commandPos(stage.at(at))} ${block}`,
          );
        }
        await tick.sprint(server, 5);

        evidence.mode = await setBookGesture(server, client, playerName, "box");
        const standAt = { x: origin.x + 3.5, y: origin.y, z: origin.z - 0.5 };
        evidence.mark = await markBox(
          server,
          client,
          playerName,
          standAt,
          first,
          last,
          stage,
        );
        expect(
          evidence.mark.marked.text,
          "the two barrels mark the box between them",
        ).toMatch(/marked$/);

        await openBookPage(server, client, playerName, origin, stage);
        await waitForElement(client, { id: OFFER }, { timeoutMs: 8_000 });
        await clickElement(client, { id: OFFER });
        await settlePanel(server, client);
        await waitForElement(client, { id: STORES }, { timeoutMs: 8_000 });
        await waitForElement(client, { id: STATIONS }, { timeoutMs: 8_000 });
        await frames(client, 4);
        await media.screenshot(client, {
          path: outAbs("e2e", "shots", `${stage.name("ui-order")}.png`),
        });

        // The furnace's kind is unticked; the barrels' stays as it opened.
        evidence.untick = await clickElement(client, { id: STATIONS });
        await settlePanel(server, client);
        await drainActionBar(client);
        evidence.take = await clickElement(client, { id: TAKE });
        await expect
          .poll(
            async () => {
              await tick.sprint(server, 3);
              return screenOpen(await safeScreen(client));
            },
            { timeout: 8_000, message: "taking them in closes the window" },
          )
          .toBe(false);
        evidence.told = await readActionBar(client);
        expect(
          evidence.told.text,
          "the player is told how many were taken in",
        ).toBe("Took in 2 blocks");

        expect(
          await isColonyMember(server, first, stage),
          "the first barrel is taken in",
        ).toBe(true);
        expect(
          await isColonyMember(server, last, stage),
          "the last barrel is taken in",
        ).toBe(true);
        expect(
          await isColonyMember(server, furnace, stage),
          "the furnace, its kind unticked, stays out",
        ).toBe(false);
        // Reopen the enrolled block after the server has accepted the sweep.
        await openSite(server, client, first, stage);
        evidence.reopened = await safeScreen(client);
        expect(screenOpen(evidence.reopened)).toBe(true);
        await screen.dismiss(client);
        const reordered = await client.run(CLIENT_SCHEMA, { reverse: "yes" });
        evidence.reorderedSchema = reordered;
        expect(reordered.values).toEqual(baselineServer.values);
        expect(reordered.pages).toEqual(baselineServer.pages);
        evidence.identicalAfterReorder = true;
        await player.useItem(client);
        await frames(client, 20);
        evidence.afterReorder = await screen.current(client);
        expect(evidence.afterReorder?.className).toContain(
          "ModularUIContainerScreen",
        );
        evidence.openCycles = [];
        for (let cycle = 0; cycle < 12; cycle++) {
          await screen.dismiss(client);
          await openSite(server, client, first, stage);
          expect((await screen.current(client))?.className).toContain(
            "ModularUIContainerScreen",
          );
          await screen.dismiss(client);
          await openBookPage(server, client, playerName, origin, stage);
          expect((await screen.current(client))?.className).toContain(
            "ModularUIContainerScreen",
          );
          evidence.openCycles.push({ cycle, site: true, book: true });
        }
      } catch (error) {
        pair?.failing(error);
        throw error;
      } finally {
        testInfo.attach(`${stage.name("ui-order-evidence")}.json`, {
          body: JSON.stringify(evidence, null, 2),
          contentType: "application/json",
        });
        await pair?.teardown(testInfo);
      }
    },
  );
