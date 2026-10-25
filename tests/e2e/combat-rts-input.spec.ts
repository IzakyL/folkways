import { expect, test, type TestInfo } from "@playwright/test";
import {
  defineTask, input, javaTask, media, screen, tick, world,
  type MinecraftClient, type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { ensureOut, outAbs } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

type Vec = { x: number; y: number; z: number };
type Pixel = { x: number; y: number; left: number; top: number; right: number; bottom: number; width: number; height: number };
type Button = { kind: string; x: number; y: number; width: number; height: number };
type ClientState = {
  combat: boolean; roster: number; selected: string[]; cachedPoints: number; cachedCommand: string;
  armed: boolean; replacementPending: boolean; routePending: boolean; controlPending: boolean;
  control: { request: number; accepted: boolean; operation: string; selection: string[]; groups: string[][] };
  route: { request: number; accepted: boolean; intent: string; points: Vec[]; cycleStart: number; command: string };
  state: { command: string; complete: boolean; stop: number; attackMove: boolean };
  buttons: Button[];
};
type ServerState = {
  selection: string[]; groups: string[][]; owned: boolean[]; reserved: boolean[];
  group: string; command: string; kind: string; phase: string; result: string;
  members: string[]; memberGroups: string[]; points: Vec[]; cycleStart: number;
  complete: boolean; attackMove: boolean; stop: number; waiting: number; orders: string[];
};
type Observation = { client: ClientState; server: ServerState };
type Context = { colony: string; player: string; residents: string[] };

// Actions under test go through input.*. Java tasks only arrange the initial fixture
// or observe production state/projection/hit rectangles; none issue RTS actions.
test("RTS real pointer and keyboard inputs select, bind, queue, stop, suspend and release", async ({}, testInfo) => {
  test.setTimeout(600_000);
  let pair: E2EPair | undefined;
  const evidence: Record<string, unknown> = {
    scope: "Three residents on flat ground, real screen input, authoritative ACK and group assertions",
    unverified: ["Hostile engagement and resuming a route after combat", "Large formations and difficult terrain"],
    visualReview: "Route screenshots require human inspection; state assertions alone do not prove rendered pixels",
  };
  try {
    ensureOut("e2e");
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#combat",
      server: { instance: `combat-input-server-${run}` },
      client: { instance: `combat-input-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await mustCommand(server, "difficulty peaceful");
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, "gamerule doDaylightCycle false");
    await mustCommand(server, "time set noon");
    await world.command(server,
      `fill ${origin.x - 19} ${origin.y - 1} ${origin.z - 19} ${origin.x + 19} ${origin.y - 1} ${origin.z + 19} minecraft:smooth_stone`);
    await world.command(server,
      `fill ${origin.x - 19} ${origin.y} ${origin.z - 19} ${origin.x + 19} ${origin.y + 6} ${origin.z + 19} minecraft:air`,
      { allowFailure: true });
    const founding = await foundColonyFast(server, client, player.name, { origin, residentCount: 3 });
    const colony = String(founding.evidence.colonyId);
    await openColonyPanelViaBook(server, client, player.name, founding.standBlock);
    await screen.dismiss(client);
    const residents = await server.run(PREPARE_RESIDENTS, { colony, ...origin });
    expect(residents).toHaveLength(3);
    const context: Context = { colony, player: player.name, residents };
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(server, 2);
    await frames(client, 10);
    await input.key(client, { keyCode: 86 });
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    const combat = (await screen.widgets(client)).find(widget => /^(Combat|战斗|戰鬥)$/.test(widget.label?.trim() ?? ""));
    expect(combat, "War must be present in the combat profile").toBeTruthy();
    await screen.clickWidget(client, { label: combat!.label });
    await waitFor("combat roster", client, server, context, value => value.client.combat && value.client.roster >= 3);

    const boxes = await Promise.all(residents.map(entity => project(client, origin, entity)));
    const from = { x: Math.min(...boxes.map(box => box.left)) - 3, y: Math.min(...boxes.map(box => box.top)) - 3 };
    const to = { x: Math.max(...boxes.map(box => box.right)) + 3, y: Math.max(...boxes.map(box => box.bottom)) + 3 };
    visible({ ...boxes[0], x: from.x, y: from.y });
    visible({ ...boxes[0], x: to.x, y: to.y });
    evidence.boxGesture = { from, to, boxes };
    const beforeBox = (await client.run(READ_CLIENT)).control.request;
    await input.drag(client, { from, to, steps: 8, button: "left" });
    evidence.boxSelection = await selection(client, server, context, residents, beforeBox);

    const third = residents[2];
    const shiftClickThird = async () => {
      const at = await project(client, origin, third);
      visible(at);
      await input.click(client, at, { button: "left", modifiers: ["shift"] });
    };
    let before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    evidence.shiftRemove = await selection(client, server, context, residents.slice(0, 2), before);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    evidence.shiftAdd = await selection(client, server, context, residents, before);

    const bindOne = async (members: string[]) => {
      const request = (await client.run(READ_CLIENT)).control.request;
      await input.key(client, { keyCode: 49, modifiers: ["ctrl"] });
      return waitFor("Ctrl+1 authoritative exact binding", client, server, context, value =>
        value.client.control.request > request && value.client.control.accepted && !value.client.controlPending
        && same(value.client.control.groups[0] ?? [], members) && same(value.server.groups[0] ?? [], members)
        && same(value.client.selected, value.server.selection));
    };
    evidence.bindABC = await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents.slice(0, 2), before);
    const ab = await bindOne(residents.slice(0, 2));
    expect(ab.server.owned[2], "C loses its final reference after exact ABC -> AB replacement").toBe(false);
    expect(ab.server.reserved[2]).toBe(false);
    evidence.exactAB = ab;
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents, before);
    evidence.rebindABC = await bindOne(residents);
    const unbound = await bindOne([]);
    expect(same(unbound.client.selected, residents), "same-set Ctrl+1 keeps visible selection").toBe(true);
    expect(same(unbound.server.selection, residents)).toBe(true);
    expect(unbound.server.owned).toEqual([true, true, true]);
    evidence.sameSetUnbind = unbound;

    const a = { x: origin.x - 5, y: origin.y, z: origin.z + 11 };
    const b = { x: origin.x, y: origin.y, z: origin.z + 14 };
    const c = { x: origin.x + 6, y: origin.y, z: origin.z + 11 };
    const groundClick = async (point: Vec, control = false) => {
      const at = await project(client, point);
      visible(at);
      await input.click(client, at, { button: "right", modifiers: control ? ["ctrl"] : [] });
    };
    let routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(a);
    const moving = await routeAck(client, server, context, routeRequest, "MOVE", [a], -1, false);
    expect(moving.server.kind).toBe("DIRECT");
    expect(moving.server.complete).toBe(false);
    const squad = moving.server.group;
    const generation = moving.server.command;
    expect(squad).not.toBe("");
    expect(moving.server.memberGroups).toEqual([squad, squad, squad]);
    expect(same(moving.server.members, residents)).toBe(true);
    evidence.ordinaryMove = moving;
    await shot(client, testInfo, run, "ordinary-route-moving");

    const completed = await waitFor("ordinary point completes its physical assembly and 20-tick dwell", client, server, context,
      value => value.server.command === generation && value.server.complete
        && value.client.state.command === generation && value.client.state.complete,
      260, 10);
    expect(completed.server.phase).toBe("HOLDING");
    expect(completed.server.waiting).toBe(0);
    expect(completed.server.owned).toEqual([true, true, true]);
    expect(completed.client.cachedPoints, "completed authored history stays available for Ctrl append/review").toBe(1);
    evidence.ordinaryComplete = completed;
    await shot(client, testInfo, run, "ordinary-route-completed-hidden");
    await input.key(client, { keyCode: 341, action: "press" });
    try {
      await frames(client, 2);
      await shot(client, testInfo, run, "completed-ctrl-history");
    } finally {
      await input.key(client, { keyCode: 341, action: "release" });
    }

    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(b, true);
    evidence.appendB = await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b], -1, false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(c, true);
    evidence.appendC = await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b, c], -1, false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(b, true);
    const loop = await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b, c], 1, false);
    expect(loop.server.group).toBe(squad);
    expect(loop.server.command, "append/closure keeps the accepted generation").toBe(generation);
    evidence.closedSuffix = loop;
    await shot(client, testInfo, run, "committed-loop");

    const clickButton = async (kind: string) => {
      await frames(client, 2);
      const state = await client.run(READ_CLIENT);
      const hit = state.buttons.find(button => button.kind === kind);
      expect(hit, `${kind} must have a rendered, real hit rectangle`).toBeTruthy();
      await input.click(client, { x: hit!.x + hit!.width / 2, y: hit!.y + hit!.height / 2 }, { button: "left" });
    };
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await clickButton("STOP");
    const stopped = await waitFor("Stop button accepted, retaining the actual squad", client, server, context, value =>
      value.client.route.request > routeRequest && value.client.route.accepted && value.client.route.intent === "STOP"
      && !value.client.routePending && value.server.kind === "STOP" && value.server.points.length === 0);
    expect(stopped.server.group).toBe(squad);
    expect(stopped.server.command).not.toBe(generation);
    expect(stopped.server.owned).toEqual([true, true, true]);
    expect(same(stopped.client.selected, residents)).toBe(true);
    evidence.stopped = stopped;

    await clickButton("ATTACK_MOVE");
    expect((await client.run(READ_CLIENT)).armed, "Attack Move is a one-shot next-ground-click mode").toBe(true);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    const attackPoint = { x: origin.x + 6, y: origin.y, z: origin.z + 7 };
    await groundClick(attackPoint);
    const attackMove = await routeAck(client, server, context, routeRequest, "MOVE", [attackPoint], -1, true);
    expect(attackMove.server.kind).toBe("ATTACK_MOVE");
    const attackSquad = attackMove.server.group;
    expect(attackSquad).not.toBe("");
    expect(same(attackMove.server.members, residents)).toBe(true);
    expect(attackMove.server.memberGroups).toEqual([attackSquad, attackSquad, attackSquad]);
    expect(attackMove.server.command).not.toBe(stopped.server.command);
    expect(attackMove.server.owned).toEqual([true, true, true]);
    expect(attackMove.server.reserved).toEqual([true, true, true]);
    if (attackSquad !== squad) {
      expect(await server.run(GROUP_EXISTS, { colony, group: squad }), "a new ground route removes the superseded empty squad").toBe(false);
    }
    expect(attackMove.client.armed).toBe(false);
    evidence.attackMoveNoHostileFixture = attackMove;
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await clickButton("STOP");
    const finalStop = await waitFor("second Stop replaces attack move", client, server, context, value =>
      value.client.route.request > routeRequest && value.client.route.accepted && value.client.route.intent === "STOP"
      && value.server.kind === "STOP" && value.server.points.length === 0 && !value.client.routePending);
    expect(finalStop.server.group).toBe(attackSquad);
    expect(finalStop.server.command).not.toBe(attackMove.server.command);
    const stopGeneration = finalStop.server.command;

    await input.key(client, { keyCode: 258 });
    await frames(client, 4);
    expect((await client.run(READ_CLIENT)).combat, "Tab must leave the combat tool").toBe(false);
    await tick.sprint(server, 2);
    let returned = false;
    for (let step = 0; step < 12; step++) {
      await input.key(client, { keyCode: 258 });
      await tick.sprint(server, 1);
      await frames(client, 4);
      if ((await client.run(READ_CLIENT)).combat) { returned = true; break; }
    }
    expect(returned, "Tab cycles the book gestures and returns to Combat").toBe(true);
    const resumed = await waitFor("Tab return restores authoritative selection without replacing orders", client, server, context,
      value => value.client.combat && !value.client.controlPending && same(value.client.selected, residents)
        && value.server.group === attackSquad && value.server.command === stopGeneration);
    expect(resumed.server.owned).toEqual([true, true, true]);
    evidence.tabRoundTrip = resumed;

    await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    await clickButton("RELEASE");
    const released = await waitFor("Release removes the selected residents from every control reference", client, server, context,
      value => value.client.control.request > before && value.client.control.accepted && !value.client.controlPending
        && value.client.selected.length === 0 && value.server.selection.length === 0
        && value.server.groups.every(group => group.length === 0)
        && value.server.owned.every(owned => !owned) && value.server.reserved.every(reserved => !reserved)
        && value.server.memberGroups.every(group => group === "") && value.server.orders.every(order => order === "NONE"));
    evidence.released = released;
    await shot(client, testInfo, run, "released");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("combat-rts-input-evidence.json", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

function same(actual: string[], expected: string[]) {
  const a = [...actual].sort(), b = [...expected].sort();
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

function samePoints(actual: Vec[], expected: Vec[]) {
  return actual.length === expected.length && actual.every((point, index) =>
    point.x === expected[index].x && point.y === expected[index].y && point.z === expected[index].z);
}

function visible(at: Pixel) {
  expect(at.x, "input must lie inside the rendered viewport").toBeGreaterThan(5);
  expect(at.x).toBeLessThan(at.width - 5);
  expect(at.y, "input must avoid the toolbar").toBeGreaterThan(40);
  expect(at.y, "world input must avoid the 60px cards").toBeLessThan(at.height - 65);
}

async function project(client: MinecraftClient, point: Vec, entity = ""): Promise<Pixel> {
  return client.run(PROJECT, { ...point, entity });
}

async function shot(client: MinecraftClient, testInfo: TestInfo, run: string, label: string) {
  const path = outAbs("e2e", "shots", `combat-rts-input-${run}-${label}.png`);
  await media.screenshot(client, { path, hideGui: false });
  await testInfo.attach(label, { path, contentType: "image/png" });
}

async function waitFor(label: string, client: MinecraftClient, server: MinecraftServer, context: Context,
  predicate: (value: Observation) => boolean, attempts = 45, ticks = 1): Promise<Observation> {
  let latest: Observation | undefined;
  for (let attempt = 0; attempt < attempts; attempt++) {
    await tick.sprint(server, ticks);
    await frames(client, 3);
    latest = { client: await client.run(READ_CLIENT), server: await server.run(READ_SERVER, context) };
    expect(latest.server.phase, `${label}: genuine group failure, ${JSON.stringify(latest.server)}`).not.toBe("FAILED_WAITING");
    if (predicate(latest)) { return latest; }
  }
  throw new Error(`${label} did not converge: ${JSON.stringify(latest)}`);
}

async function selection(client: MinecraftClient, server: MinecraftServer, context: Context, ids: string[], after: number) {
  return waitFor("selection ACK and exclusive ownership", client, server, context, value =>
    value.client.control.request > after && value.client.control.accepted && !value.client.controlPending
      && same(value.client.selected, ids) && same(value.client.control.selection, ids) && same(value.server.selection, ids));
}

async function routeAck(client: MinecraftClient, server: MinecraftServer, context: Context, after: number,
  intent: string, points: Vec[], cycleStart: number, attackMove: boolean) {
  return waitFor(`${intent} accepted with exact authored points`, client, server, context, value =>
    value.client.route.request > after && value.client.route.accepted && value.client.route.intent === intent
      && !value.client.routePending && !value.client.replacementPending
      && samePoints(value.client.route.points, points) && samePoints(value.server.points, points)
      && value.server.cycleStart === cycleStart && value.client.route.cycleStart === cycleStart
      && value.server.attackMove === attackMove && value.server.command === value.client.route.command);
}

const PREPARE_RESIDENTS = defineTask<{ colony: string } & Vec, string[]>({
  name: "folkways.combat-rts-input.prepare-flat-fixture", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "net.minecraft.world.entity.Mob",
      "io.github.izakyl.folkways.core.api.colony.Colonies"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var ids=colony.residents().stream().map(r->r.id()).limit(3).toList();
for(int i=0;i<ids.size();i++){
  Mob mob=null;for(var level:server.getAllLevels()){var entity=level.getEntity(ids.get(i));if(entity instanceof Mob found){mob=found;break;}}
  if(mob==null)throw new IllegalStateException("missing fixture resident "+ids.get(i));
  mob.getNavigation().stop();mob.moveTo(a.integer("x")-4+i*4+0.5,a.integer("y"),a.integer("z")+6.5,0,0);
  mob.setHealth(mob.getMaxHealth());
}
return ids.stream().map(Object::toString).toList();`,
  }),
});

const GROUP_EXISTS = defineTask<{ colony: string; group: string }, boolean>({
  name: "folkways.combat-rts-input.observe-superseded-squad", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.plugins.combat.group.UnitGroups"],
    body: `var a=Args.of(ctx);var colony=Colonies.of((MinecraftServer)ctx.server(),UUID.fromString(a.string("colony"))).orElseThrow();
return UnitGroups.of(colony).group(UUID.fromString(a.string("group"))).isPresent();`,
  }),
});

const READ_SERVER = defineTask<Context, ServerState>({
  name: "folkways.combat-rts-input.observe-server", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.LinkedHashMap", "net.minecraft.server.MinecraftServer",
      "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.plugins.combat.group.UnitGroups", "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
      "io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var player=server.getPlayerList().getPlayerByName(a.string("player"));
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var ids=a.strings("residents").stream().map(UUID::fromString).toList();var ownership=CombatSelectionOwnership.of(colony);
var snapshot=ownership.snapshot(player);var groups=UnitGroups.of(colony);var commands=ResidentCommands.of(colony);
var group=ids.stream().map(groups::ofResident).flatMap(java.util.Optional::stream).findFirst().orElse(null);
var out=new LinkedHashMap<String,Object>();
out.put("selection",snapshot.selection().stream().map(Object::toString).toList());
out.put("groups",snapshot.groups().stream().map(g->g.stream().map(Object::toString).toList()).toList());
out.put("owned",ids.stream().map(id->{var entity=player.serverLevel().getEntity(id);return entity!=null&&Bodies.of(entity).filter(body->ownership.owns(player,body)).isPresent();}).toList());
out.put("reserved",ids.stream().map(ownership::reserved).toList());
out.put("memberGroups",ids.stream().map(id->groups.ofResident(id).map(g->g.id().toString()).orElse("")).toList());
out.put("orders",ids.stream().map(id->commands.current(id).map(o->o.mode().name()+":"+o.status().name()).orElse("NONE")).toList());
out.put("group",group==null?"":group.id().toString());out.put("command",group==null?"":group.commandId().map(Object::toString).orElse(""));
out.put("kind",group==null?"NONE":group.commandKind().name());out.put("phase",group==null?"NONE":group.phase().name());
out.put("result",group==null?"NONE":group.endReason().name());out.put("members",group==null?List.of():group.members().stream().map(Object::toString).toList());
out.put("points",group==null?List.of():group.pointRoute().stream().map(p->Map.of("x",p.cell().getX(),"y",p.cell().getY(),"z",p.cell().getZ())).toList());
out.put("cycleStart",group==null?-1:group.cycleStart());out.put("complete",group!=null&&group.routeComplete());out.put("attackMove",group!=null&&group.attackMove());
out.put("stop",group==null?-1:group.patrolStop());out.put("waiting",group==null?0:group.waitingMembers());return out;`,
  }),
});

const READ_CLIENT = defineTask<void, ClientState>({
  name: "folkways.combat-rts-input.observe-client-and-rendered-hits", side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.Map", "java.util.Set", "java.util.LinkedHashMap", "java.util.ArrayList",
      "io.github.izakyl.folkways.front.api.ui.OverheadTools", "io.github.izakyl.folkways.plugins.combat.net.CombatPackets"],
    body: `
var out=new LinkedHashMap<String,Object>();var control=CombatPackets.controls().orElse(null);var route=CombatPackets.route().orElse(null);var state=CombatPackets.state();
out.put("roster",CombatPackets.roster().size());out.put("controlPending",CombatPackets.controlPending());out.put("routePending",CombatPackets.routePending());
out.put("control",control==null?Map.of("request",0L,"accepted",false,"operation","","selection",List.of(),"groups",List.of()):
  Map.of("request",control.request(),"accepted",control.accepted(),"operation",control.operation().name(),
    "selection",control.selection().stream().map(Object::toString).toList(),"groups",control.groups().stream().map(g->g.stream().map(Object::toString).toList()).toList()));
out.put("route",route==null?Map.of("request",0L,"accepted",false,"intent","","points",List.of(),"cycleStart",-1,"command",""):
  Map.of("request",route.request(),"accepted",route.accepted(),"intent",route.intent().name(),"cycleStart",route.cycleStart(),
    "points",route.points().stream().map(p->Map.of("x",p.cell().getX(),"y",p.cell().getY(),"z",p.cell().getZ())).toList(),"command",String.valueOf(route.state().commandId())));
out.put("state",Map.of("command",String.valueOf(state.commandId()),"complete",state.routeComplete(),"stop",state.stop(),"attackMove",state.attackMove()));
var handler=OverheadTools.selectedHandler().orElse(null);boolean combat=handler!=null&&handler.getClass().getName().endsWith(".CombatController");out.put("combat",combat);
out.put("selected",List.of());out.put("cachedPoints",0);out.put("cachedCommand","");out.put("armed",false);out.put("replacementPending",false);out.put("buttons",List.of());
if(combat){
  var type=handler.getClass();var selected=type.getDeclaredField("selected");selected.setAccessible(true);out.put("selected",((Set<?>)selected.get(handler)).stream().map(Object::toString).toList());
  var points=type.getDeclaredField("route");points.setAccessible(true);out.put("cachedPoints",((List<?>)points.get(handler)).size());
  var command=type.getDeclaredField("routeCommandId");command.setAccessible(true);out.put("cachedCommand",String.valueOf(command.get(handler)));
  var armed=type.getDeclaredField("attackMoveArmed");armed.setAccessible(true);out.put("armed",armed.getBoolean(handler));
  var pending=type.getDeclaredField("pendingReplacement");pending.setAccessible(true);out.put("replacementPending",pending.get(handler)!=null);
  var cardsField=type.getDeclaredField("cards");cardsField.setAccessible(true);var cards=cardsField.get(handler);
  var hitsField=cards.getClass().getDeclaredField("hits");hitsField.setAccessible(true);var buttons=new ArrayList<Map<String,Object>>();
  for(var hit:(List<?>)hitsField.get(cards)){
    var actionMethod=hit.getClass().getDeclaredMethod("action");actionMethod.setAccessible(true);var action=actionMethod.invoke(hit);
    var kindMethod=action.getClass().getDeclaredMethod("kind");kindMethod.setAccessible(true);var row=new LinkedHashMap<String,Object>();row.put("kind",kindMethod.invoke(action).toString());
    for(var field:List.of("x","y","width","height")){var accessor=hit.getClass().getDeclaredMethod(field);accessor.setAccessible(true);row.put(field,accessor.invoke(hit));}buttons.add(row);
  }out.put("buttons",buttons);
}return out;`,
  }),
});

const PROJECT = defineTask<Vec & { entity: string }, Pixel>({
  name: "folkways.combat-rts-input.observe-world-projection", side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.Optional", "java.util.UUID", "net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.AABB"],
    body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var type=Class.forName("io.github.izakyl.folkways.war.client.CombatProjection");
var constructor=type.getDeclaredConstructor(Minecraft.class);constructor.setAccessible(true);var projection=constructor.newInstance(mc);
double left,top,right,bottom;
if(!a.string("entity").isEmpty()){
  var id=UUID.fromString(a.string("entity"));net.minecraft.world.entity.Entity found=null;
  for(var entity:mc.level.entitiesForRendering()){if(entity.getUUID().equals(id)){found=entity;break;}}
  if(found==null)throw new IllegalStateException("fixture resident is not client-tracked: "+id);
  var bounds=type.getDeclaredMethod("bounds",AABB.class);bounds.setAccessible(true);var rect=((Optional<?>)bounds.invoke(projection,found.getBoundingBox())).orElseThrow();
  var l=rect.getClass().getDeclaredMethod("left");l.setAccessible(true);left=((Number)l.invoke(rect)).doubleValue();
  var t=rect.getClass().getDeclaredMethod("top");t.setAccessible(true);top=((Number)t.invoke(rect)).doubleValue();
  var r=rect.getClass().getDeclaredMethod("right");r.setAccessible(true);right=((Number)r.invoke(rect)).doubleValue();
  var b=rect.getClass().getDeclaredMethod("bottom");b.setAccessible(true);bottom=((Number)b.invoke(rect)).doubleValue();
}else{
  var point=type.getDeclaredMethod("point",Vec3.class);point.setAccessible(true);
  var pixel=((Optional<?>)point.invoke(projection,new Vec3(a.integer("x")+0.5,a.integer("y")+0.12,a.integer("z")+0.5))).orElseThrow();
  var x=pixel.getClass().getDeclaredMethod("x");x.setAccessible(true);left=right=((Number)x.invoke(pixel)).doubleValue();
  var y=pixel.getClass().getDeclaredMethod("y");y.setAccessible(true);top=bottom=((Number)y.invoke(pixel)).doubleValue();
}
return Map.of("x",(left+right)/2,"y",(top+bottom)/2,"left",left,"top",top,"right",right,"bottom",bottom,
  "width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight());`,
  }),
});
