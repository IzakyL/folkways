import { expect, test } from "@playwright/test";
import { chat, defineTask, javaTask, player, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, waitForElement } from "../ldlib2";
import { clickTab } from "../shared/ui/colony-pages";
import { launchPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { newRunId } from "../shared/harness/run-id";

const SETUP = defineTask<Record<string, never>, boolean>({
  name: "folkways.debug.setup", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.charter.Charters",
    "io.github.izakyl.folkways.front.engine.charter.CurrentColony",
    "io.github.izakyl.folkways.front.engine.registry.FolkwaysItems",
    "net.minecraft.world.item.ItemStack", "net.minecraft.world.InteractionHand",
  ], body: `
var p=ctx.server().getPlayerList().getPlayers().getFirst();
var colony=Charters.found(p,"Debug fixture");CurrentColony.choose(p,colony);
p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(FolkwaysItems.COLONY_BOOK.get()));return true;
` }),
});

const ENABLED = defineTask<Record<string, never>, boolean>({
  name: "folkways.debug.enabled", side: "server",
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.engine.colony.LaborDebug"], body: `
return LaborDebug.enabled(ctx.server().getPlayerList().getPlayers().getFirst());
` }),
});

const INCIDENT = defineTask<{ count: number; key: string; other: boolean }, boolean>({
  name: "folkways.debug.incident", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.api.ui.Pages",
    "io.github.izakyl.folkways.core.engine.labor.LaborIncident",
    "net.neoforged.neoforge.common.NeoForge", "net.minecraft.network.chat.Component",
  ], body: `
var p=ctx.server().getPlayerList().getPlayers().getFirst();
var colony=Pages.colonyOf(p).orElseThrow().id();
if(((Boolean)ctx.arg("other")).booleanValue())colony=UUID.randomUUID();
for(int i=0;i<((Number)ctx.arg("count")).intValue();i++)NeoForge.EVENT_BUS.post(new LaborIncident(colony,
 p.serverLevel().dimension(),(String)ctx.arg("key"),Component.translatable("folkways.debug.incident.refused",
 Component.literal((String)ctx.arg("key")))));
return true;
` }),
});

// Queue and unsubscribe in one server call, so UI navigation cannot advance past the repeat deadline first.
const CLEAR_QUEUED = defineTask<Record<string, never>, boolean>({
  name: "folkways.debug.clearQueued", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.api.ui.Pages",
    "io.github.izakyl.folkways.front.engine.colony.LaborDebug",
    "io.github.izakyl.folkways.core.engine.labor.LaborIncident",
    "net.neoforged.neoforge.common.NeoForge", "net.minecraft.network.chat.Component",
  ], body: `
var p=ctx.server().getPlayerList().getPlayers().getFirst();
LaborDebug.set(p,true);
for(int i=0;i<2;i++)NeoForge.EVENT_BUS.post(new LaborIncident(Pages.colonyOf(p).orElseThrow().id(),
 p.serverLevel().dimension(),"discard-probe",Component.literal("discard-probe")));
LaborDebug.set(p,false);return !LaborDebug.enabled(p);
` }),
});

// Supply known counters to check the real chat packet and client translation, without pretending that an empty
// fixture colony has a busy workforce. The periodic, unsubscribed and stopped paths use the actual server tick.
const SAMPLE = defineTask<Record<string, never>, boolean>({
  name: "folkways.debug.sample", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.colony.LaborDebug",
    "io.github.izakyl.folkways.core.engine.labor.LaborCensus",
    "io.github.izakyl.folkways.core.engine.labor.LaborSnapshot",
    "io.github.izakyl.folkways.core.api.work.NodeState", "net.minecraft.server.level.ServerPlayer",
  ], body: `
var p=ctx.server().getPlayerList().getPlayers().getFirst();
var census=new LaborCensus(100,Map.of(NodeState.READY,5),5,Map.of("PACK_BUSY",3),Map.of("minecraft:wheat",8L),Map.of("idle",2,"working",1));
var snapshot=new LaborSnapshot(census,0,12,2,Map.of("NO_ROOM",1),List.of("PACK_BUSY/folkways:haul/TransferNode@test"));
var m=LaborDebug.class.getDeclaredMethod("report",ServerPlayer.class,LaborSnapshot.class);m.setAccessible(true);m.invoke(null,p,snapshot);return true;
` }),
});

test("book debug toggle sends bounded diagnostics only while enabled", async ({}, testInfo) => {
  ensureOut("e2e");
  const run = newRunId("labor-debug");
  const pair = await launchPair({
    server: { instance: `${run}-server`, trace: tracePath("e2e", `${run}-server`) },
    client: { instance: `${run}-client`, trace: tracePath("e2e", `${run}-client`) },
  });
  const { server, client } = pair;
  const messages = () => chat.messages(client, { limit: 200 });
  const waiting = (lines: string[]) => lines.filter(line => line.includes("No active planning site")).length;
  const openSettings = async () => {
    await player.useItem(client);
    await screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 15_000 });
    await clickTab(client, "folkways.tab.settings");
    await waitForElement(client, { id: "folkways.settings.debug_logs" }, { timeoutMs: 10_000 });
  };
  try {
    await server.run(SETUP, {});
    await tick.step(server, 3);
    await tick.sprint(server, 210);
    expect(await server.run(ENABLED, {})).toBe(false);
    expect((await messages()).some(line => line.includes("Folkways Debug"))).toBe(false);
    await server.run(INCIDENT, { count: 1, key: "disabled-path-probe", other: false });
    expect((await messages()).some(line => line.includes("disabled-path-probe"))).toBe(false);
    await openSettings();
    await clickElement(client, { id: "folkways.settings.debug_logs" });
    await expect.poll(() => server.run(ENABLED, {})).toBe(true);
    await chat.waitFor(client, "No active planning site", { includeExisting: true, timeoutMs: 15_000 });
    const before = waiting(await messages());
    await tick.sprint(server, 40);
    expect(waiting(await messages())).toBe(before);
    await screen.dismiss(client);
    await tick.sprint(server, 210);
    expect(await server.run(ENABLED, {})).toBe(true);
    expect(waiting(await messages())).toBeGreaterThan(before);
    await server.run(SAMPLE, {});
    await chat.waitFor(client, "fallbacks 2", { includeExisting: true, timeoutMs: 10_000 });
    expect((await messages()).some(line => line.includes("PACK_BUSY=3") && line.includes("NO_ROOM=1"))).toBe(true);
    await server.run(INCIDENT, { count: 19, key: "live-path-probe", other: false });
    await chat.waitFor(client, "Work could not continue: live-path-probe", { includeExisting: true, timeoutMs: 10_000 });
    expect((await messages()).filter(line => line.includes("live-path-probe"))).toHaveLength(1);
    await server.run(INCIDENT, { count: 1, key: "other-colony-probe", other: true });
    expect((await messages()).some(line => line.includes("other-colony-probe"))).toBe(false);
    await tick.sprint(server, 100);
    await chat.waitFor(client, "grouped occurrences: 18", { includeExisting: true, timeoutMs: 10_000 });
    await openSettings();
    await clickElement(client, { id: "folkways.settings.debug_logs" });
    await expect.poll(() => server.run(ENABLED, {})).toBe(false);
    const stopped = waiting(await messages());
    await tick.sprint(server, 210);
    expect(waiting(await messages())).toBe(stopped);
    await server.run(INCIDENT, { count: 1, key: "stopped-path-probe", other: false });
    expect((await messages()).filter(line => line.includes("live-path-probe"))).toHaveLength(2);
    expect((await messages()).some(line => line.includes("stopped-path-probe"))).toBe(false);
    expect(await server.run(CLEAR_QUEUED, {})).toBe(true);
    await tick.sprint(server, 110);
    await chat.waitFor(client, "discard-probe", { includeExisting: true, timeoutMs: 10_000 });
    expect((await messages()).filter(line => line.includes("discard-probe"))).toHaveLength(1);
  } catch (error) {
    pair.failing(error);
    throw error;
  } finally {
    await pair.teardown(testInfo);
  }
});
