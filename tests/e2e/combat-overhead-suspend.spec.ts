import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

type Vec = { x: number; y: number; z: number };
type Control = {
  request: number;
  operation: string;
  accepted: boolean;
  selection: string[];
  groups: string[][];
  points: number;
  commandId: string;
};
type Route = { intent: string; accepted: boolean; points: number; commandId: string };
type LocalRoute = { points: number; commandId: string };
type GroupState = { id: string; phase: string; commandId: string; orders: string[] };

const KEY_V = 86;

test("closing V and switching hands suspends only temporary RTS selection, preserving accepted groups", async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  const evidence: Record<string, unknown> = {};
  let colony = "";
  let playerName = "";
  let residents: string[] = [];

  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `combat-suspend-server-${run}` },
      client: { instance: `combat-suspend-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    playerName = player.name;
    const origin: Vec = {
      x: Math.round(player.pos.x) + 4,
      y: Math.round(player.pos.y),
      z: Math.round(player.pos.z) + 4,
    };
    await mustCommand(server, "difficulty peaceful");
    await mustCommand(server, "gamerule doMobSpawning false");
    await world.command(server,
      `fill ${origin.x - 8} ${origin.y - 1} ${origin.z - 6} ${origin.x + 12} ${origin.y - 1} ${origin.z + 12} minecraft:smooth_stone`);
    await world.command(server,
      `fill ${origin.x - 8} ${origin.y} ${origin.z - 6} ${origin.x + 12} ${origin.y + 5} ${origin.z + 12} minecraft:air`,
      { allowFailure: true });
    const founding = await foundColonyFast(server, client, player.name, { origin, residentCount: 3 });
    colony = String(founding.evidence.colonyId);
    residents = await server.run(RESIDENT_IDS, { colony });
    expect(residents).toHaveLength(3);

    await openColonyPanelViaBook(server, client, player.name, founding.standBlock);
    await screen.dismiss(client);
    await tapV(client);
    const overhead = await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    const combat = (await screen.widgets(client)).find(widget => /^(Combat|战斗|戰鬥)$/.test(widget.label?.trim() ?? ""));
    expect(combat, "the V toolbar must expose the registered RTS tool").toBeTruthy();
    await screen.clickWidget(client, { label: combat!.label });
    await frames(client, 20);

    const members = residents.slice(0, 2);
    await client.run(SELECT, { residents: members });
    const selected = await waitForSelection(client, server, members);
    expect(selected.accepted).toBe(true);
    expect(selected.selection).toEqual(expect.arrayContaining(members));
    expect(selected.groups.every(group => group.length === 0), "the tested squad must not be bound to a Ctrl+number slot")
      .toBe(true);
    // The helper lays the safe floor at origin.y - 1 and clears the feet cell starting at origin.y.
    const sent = await client.run(ISSUE, { residents: members, x: origin.x + 7, y: origin.y, z: origin.z + 7 });
    expect(sent.sent).toBe(true);
    const accepted = await waitForRoute(client, server, "MOVE");
    evidence.acceptedUnboundSquad = { overhead, selected, sent, accepted };
    expect(accepted.intent).toBe("MOVE");
    expect(accepted.accepted).toBe(true);
    expect(accepted.points).toBe(2);
    const beforeClose = await readGroup(server, colony, playerName, members);
    evidence.beforeClose = beforeClose;
    expect(beforeClose.commandId).not.toBe("");
    expect(accepted.commandId).toBe(beforeClose.commandId);
    expect(beforeClose.phase).not.toBe("IDLE");

    // Closing V invokes the combat handler's suspend path; it must not send SELECT(empty) or CLEAR.
    const controlBeforeClose = await readControl(client);
    await tapV(client);
    // Queue a real heartbeat immediately after SUSPEND. The controller consumes whichever control ACK is latest.
    const heartbeatSent = await client.run(SEND_HEARTBEAT);
    expect(heartbeatSent.sent).toBe(true);
    const suspended = await waitForControl(client, server, "HEARTBEAT", controlBeforeClose.request);
    await frames(client, 2);
    const restoredRoute = await client.run(READ_LOCAL_ROUTE);
    const afterClose = await readGroup(server, colony, playerName, members);
    evidence.closedV = { heartbeatSent, latestControlAfterSuspend: suspended, restoredRoute, afterClose };
    expect(suspended.operation).toBe("HEARTBEAT");
    expect(suspended.accepted).toBe(true);
    expect(suspended.selection).toEqual(expect.arrayContaining(members));
    expect(suspended.points).toBe(2);
    expect(suspended.commandId).toBe(beforeClose.commandId);
    expect(restoredRoute.points).toBe(2);
    expect(restoredRoute.commandId).toBe(beforeClose.commandId);
    expect(suspended.groups.every(group => group.length === 0), "suspending an unbound actual squad must not invent a shortcut group")
      .toBe(true);
    expect(afterClose.id).toBe(beforeClose.id);
    expect(afterClose.commandId).toBe(beforeClose.commandId);
    expect(afterClose.phase).not.toBe("IDLE");
    expect(afterClose.orders.every(order => order !== "NONE")).toBe(true);

    // Keep the squad's chunks loaded while moving the player outside the client entity tracking range.
    await mustCommand(server,
      `forceload add ${origin.x - 8} ${origin.z - 8} ${origin.x + 12} ${origin.z + 12}`);
    const remote = { x: origin.x + 400, y: origin.y, z: origin.z + 400 };
    await mustCommand(server, `forceload add ${remote.x - 3} ${remote.z - 3} ${remote.x + 3} ${remote.z + 3}`);
    await tick.sprint(server, 2);
    await world.command(server,
      `fill ${remote.x - 3} ${remote.y - 1} ${remote.z - 3} ${remote.x + 3} ${remote.y - 1} ${remote.z + 3} minecraft:smooth_stone`);
    await world.command(server,
      `fill ${remote.x - 3} ${remote.y} ${remote.z - 3} ${remote.x + 3} ${remote.y + 4} ${remote.z + 3} minecraft:air`,
      { allowFailure: true });
    await mustCommand(server, `tp ${playerName} ${remote.x + 0.5} ${remote.y} ${remote.z + 0.5}`);
    await frames(client, 20);
    const trackingGap = await client.run(READ_TRACKING, { residents: members });
    evidence.clientTrackingGap = { remote, trackingGap };
    expect(trackingGap.tracked).toEqual([]);

    // Putting the book away must not end the accepted task.
    await mustCommand(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    // Keep the screen closed for over the 100-tick input lease and observe the group generation/order directly.
    // Separate server coverage verifies task behavior without heartbeat renewal.
    for (let interval = 0; interval < 6; interval++) {
      await frames(client, 20);
      await tick.sprint(server, 20);
    }
    const afterHandSwitch = await readGroup(server, colony, playerName, members);
    evidence.afterHandSwitch = afterHandSwitch;
    expect(afterHandSwitch.id).toBe(beforeClose.id);
    expect(afterHandSwitch.commandId).toBe(beforeClose.commandId);
    expect(afterHandSwitch.phase).not.toBe("IDLE");
    expect(afterHandSwitch.orders.every(order => order !== "NONE")).toBe(true);

    await mustCommand(server, `item replace entity ${playerName} weapon.mainhand with folkways:colony_book`);
    await tapV(client);
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    await frames(client, 20);
    const resumed = await readControl(client);
    const localRouteAfterReopen = await client.run(READ_LOCAL_ROUTE);
    const stillUntracked = await client.run(READ_TRACKING, { residents: members });
    for (let interval = 0; interval < 3; interval++) {
      await frames(client, 20);
      await tick.sprint(server, 20);
    }
    const afterTrackingGap = await readControl(client);
    const afterReopen = await readGroup(server, colony, playerName, members);
    evidence.reopenedV = { resumed, localRouteAfterReopen, stillUntracked, afterTrackingGap, afterReopen };
    expect(stillUntracked.tracked).toEqual([]);
    expect(afterTrackingGap.selection).toEqual(expect.arrayContaining(members));
    expect(afterTrackingGap.points).toBe(2);
    expect(afterTrackingGap.commandId).toBe(beforeClose.commandId);
    expect(resumed.groups.every(group => group.length === 0)).toBe(true);
    expect(afterReopen.id).toBe(beforeClose.id);
    expect(resumed.selection).toEqual(expect.arrayContaining(members));
    expect(resumed.points).toBe(2);
    expect(resumed.commandId).toBe(beforeClose.commandId);
    expect(localRouteAfterReopen.points).toBe(2);
    expect(localRouteAfterReopen.commandId).toBe(beforeClose.commandId);
    expect(afterReopen.commandId).toBe(beforeClose.commandId);
    expect(afterReopen.phase).not.toBe("IDLE");

    await client.run(BIND, { slot: 1 });
    const bound = await waitForGroupBinding(client, server, members);
    evidence.boundAfterResume = bound;
    expect(bound.accepted).toBe(true);
    expect(bound.groups[0]).toEqual(expect.arrayContaining(members));

    // A third resident is selected without belonging to any shortcut or running task.
    await client.run(SELECT, { residents: [residents[2]] });
    const temporary = await waitForSelection(client, server, [residents[2]]);
    expect(temporary.selection).toEqual([residents[2]]);
    const controlBeforeTemporarySuspend = await readControl(client);
    await tapV(client);
    await client.run(SEND_HEARTBEAT);
    const temporaryReleased = await waitForControl(client, server, "HEARTBEAT", controlBeforeTemporarySuspend.request);
    const afterTemporaryRelease = await readGroup(server, colony, playerName, members);
    evidence.temporarySelection = { temporaryReleased, afterTemporaryRelease };
    expect(temporaryReleased.operation).toBe("HEARTBEAT");
    expect(temporaryReleased.accepted).toBe(true);
    expect(temporaryReleased.selection).toEqual([]);
    expect(temporaryReleased.groups[0]).toEqual(expect.arrayContaining(members));
    expect(afterTemporaryRelease.id).toBe(beforeClose.id);
    expect(afterTemporaryRelease.commandId).toBe(beforeClose.commandId);
    expect(afterTemporaryRelease.phase).not.toBe("IDLE");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("combat-overhead-suspend-evidence.json", {
      body: JSON.stringify(evidence, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

async function tapV(client: MinecraftClient) {
  await input.key(client, { keyCode: KEY_V, action: "press" });
  await input.key(client, { keyCode: KEY_V, action: "release" });
}

async function readControl(client: MinecraftClient): Promise<Control> {
  return client.run(READ_CONTROL);
}

async function readRoute(client: MinecraftClient): Promise<Route> {
  return client.run(READ_ROUTE);
}

const READ_LOCAL_ROUTE = defineTask<void, LocalRoute>({
  name: "folkways.combat-suspend.read-controller-route-cache",
  side: "client",
  source: javaTask({
    imports: ["java.lang.reflect.Field", "java.util.List", "java.util.Map", "io.github.izakyl.folkways.front.api.ui.OverheadTools"],
    body: `
var handler=OverheadTools.selectedHandler().orElse(null);if(handler==null)return Map.of("points",0,"commandId","");
Field routeField=handler.getClass().getDeclaredField("route");routeField.setAccessible(true);var route=(List<?>)routeField.get(handler);
Field commandField=handler.getClass().getDeclaredField("routeCommandId");commandField.setAccessible(true);
return Map.of("points",route.size(),"commandId",String.valueOf(commandField.get(handler)));`,
  }),
});

async function waitForRoute(client: MinecraftClient, server: MinecraftServer, intent: string): Promise<Route> {
  let latest = await readRoute(client);
  for (let attempt = 0; attempt < 20 && latest.intent !== intent; attempt++) {
    await tick.sprint(server, 1);
    await frames(client, 2);
    latest = await readRoute(client);
  }
  expect(latest.intent, `the client must receive the ${intent} route acknowledgement`).toBe(intent);
  return latest;
}

async function waitForControl(client: MinecraftClient, server: MinecraftServer, operation: string, afterRequest = -1): Promise<Control> {
  let latest = await readControl(client);
  for (let attempt = 0; attempt < 20 && (latest.operation !== operation || latest.request <= afterRequest); attempt++) {
    await tick.sprint(server, 1);
    await frames(client, 2);
    latest = await readControl(client);
  }
  expect(latest.operation, `the client must receive the ${operation} acknowledgement`).toBe(operation);
  expect(latest.request, "the observed acknowledgement must follow the operation being suspended").toBeGreaterThan(afterRequest);
  return latest;
}

async function waitForSelection(client: MinecraftClient, server: MinecraftServer, ids: string[]): Promise<Control> {
  let latest = await readControl(client);
  for (let attempt = 0; attempt < 20 && !ids.every(id => latest.selection.includes(id)); attempt++) {
    await tick.sprint(server, 1);
    await frames(client, 2);
    latest = await readControl(client);
  }
  expect(latest.selection).toEqual(expect.arrayContaining(ids));
  return latest;
}

async function waitForGroupBinding(client: MinecraftClient, server: MinecraftServer, ids: string[]): Promise<Control> {
  let latest = await readControl(client);
  for (let attempt = 0; attempt < 20 && !ids.every(id => latest.groups[0]?.includes(id)); attempt++) {
    await tick.sprint(server, 1);
    await frames(client, 2);
    latest = await readControl(client);
  }
  expect(latest.groups[0]).toEqual(expect.arrayContaining(ids));
  return latest;
}

async function readGroup(server: MinecraftServer, colony: string, player: string, residents: string[]): Promise<GroupState> {
  return server.run(READ_GROUP, { colony, player, residents });
}

const RESIDENT_IDS = defineTask<{ colony: string }, string[]>({
  name: "folkways.combat-suspend.resident-ids",
  side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.api.colony.Colonies"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
return colony.residents().stream().map(r->r.id().toString()).limit(3).toList();`,
  }),
});

const SELECT = defineTask<{ residents: string[] }, { sent: boolean }>({
  name: "folkways.combat-suspend.client-select",
  side: "client",
  source: javaTask({
    imports: ["java.util.UUID", "io.github.izakyl.folkways.plugins.combat.net.CombatPackets"],
    body: `var ids=Args.of(ctx).strings("residents").stream().map(UUID::fromString).toList();return java.util.Map.of("sent",CombatPackets.select(ids));`,
  }),
});

const BIND = defineTask<{ slot: number }, { sent: boolean }>({
  name: "folkways.combat-suspend.client-bind",
  side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.plugins.combat.net.CombatPackets"],
    body: `return java.util.Map.of("sent",CombatPackets.bind(Args.of(ctx).integer("slot")));`,
  }),
});

const SEND_HEARTBEAT = defineTask<void, { sent: boolean }>({
  name: "folkways.combat-suspend.client-heartbeat",
  side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.plugins.combat.net.CombatPackets"],
    body: `return java.util.Map.of("sent",CombatPackets.heartbeat());`,
  }),
});

const READ_TRACKING = defineTask<{ residents: string[] }, { tracked: string[] }>({
  name: "folkways.combat-suspend.client-tracking-state",
  side: "client",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.client.Minecraft"],
    body: `
var mc=Minecraft.getInstance();var requested=new java.util.HashSet<>(Args.of(ctx).strings("residents").stream().map(UUID::fromString).toList());
var tracked=new java.util.ArrayList<String>();
if(mc.level!=null){for(var entity:mc.level.entitiesForRendering()){if(requested.contains(entity.getUUID()))tracked.add(entity.getUUID().toString());}}
return java.util.Map.of("tracked",tracked);`,
  }),
});

const ISSUE = defineTask<{ residents: string[]; x: number; y: number; z: number }, { sent: boolean }>({
  name: "folkways.combat-suspend.client-issue-route",
  side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.UUID", "net.minecraft.core.BlockPos", "net.minecraft.client.Minecraft",
      "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.plugins.combat.net.CombatPackets"],
    body: `
var a=Args.of(ctx);var ids=a.strings("residents").stream().map(UUID::fromString).toList();
var point=WorldPos.of(Minecraft.getInstance().level,new BlockPos((int)a.integer("x"),(int)a.integer("y"),(int)a.integer("z")));
var next=WorldPos.of(Minecraft.getInstance().level,new BlockPos((int)a.integer("x")+3,(int)a.integer("y"),(int)a.integer("z")+3));
return java.util.Map.of("sent",CombatPackets.submit(ids,List.of(point,next),0,false));`,
  }),
});

const READ_ROUTE = defineTask<void, Route>({
  name: "folkways.combat-suspend.client-read-route-ack",
  side: "client",
  source: javaTask({
    imports: ["java.util.LinkedHashMap", "io.github.izakyl.folkways.plugins.combat.net.CombatPackets"],
    body: `
var reply=CombatPackets.route().orElse(null);var result=new LinkedHashMap<String,Object>();
if(reply==null){result.put("intent","");result.put("accepted",false);result.put("points",0);result.put("commandId","");}
else{result.put("intent",reply.intent().name());result.put("accepted",reply.accepted());result.put("points",reply.points().size());result.put("commandId",String.valueOf(reply.state().commandId()));}
return result;`,
  }),
});

const READ_CONTROL = defineTask<void, Control>({
  name: "folkways.combat-suspend.client-read-control",
  side: "client",
  source: javaTask({
    imports: ["java.util.LinkedHashMap", "io.github.izakyl.folkways.plugins.combat.net.CombatPackets"],
    body: `
var reply=CombatPackets.controls().orElse(null);var result=new LinkedHashMap<String,Object>();
if(reply==null){result.put("request",0L);result.put("operation","");result.put("accepted",false);result.put("selection",java.util.List.of());result.put("groups",java.util.List.of());result.put("points",0);result.put("commandId","");}
else{result.put("request",reply.request());result.put("operation",reply.operation().name());result.put("accepted",reply.accepted());result.put("selection",reply.selection().stream().map(Object::toString).toList());
result.put("groups",reply.groups().stream().map(group->group.stream().map(Object::toString).toList()).toList());result.put("points",reply.points().size());result.put("commandId",String.valueOf(reply.state().commandId()));}
return result;`,
  }),
});

const READ_GROUP = defineTask<{ colony: string; player: string; residents: string[] }, GroupState>({
  name: "folkways.combat-suspend.server-read-group",
  side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "java.util.LinkedHashMap", "net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.plugins.combat.group.UnitGroups", "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var groups=UnitGroups.of(colony);var ids=a.strings("residents").stream().map(UUID::fromString).toList();
var group=ids.stream().map(groups::ofResident).flatMap(java.util.Optional::stream).findFirst().flatMap(member->groups.group(member.id())).orElse(null);
var commands=ResidentCommands.of(colony);var orders=ids.stream().map(id->commands.current(id).map(order->order.id().toString()).orElse("NONE")).toList();
return java.util.Map.of("id",group==null?"":group.id().toString(),"phase",group==null?"NONE":group.phase().name(),
"commandId",group==null?"":group.commandId().map(Object::toString).orElse(""),"orders",orders);`,
  }),
});
