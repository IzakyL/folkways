import { expect } from "@playwright/test";
import { defineTask, javaTask, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { frames } from "../shared/harness/bw-helpers";
import { sleepMs } from "./promo-kit";

// Read-only projections, packet acknowledgements and authoritative state from the real-input acceptance test.
export type Vec = { x: number; y: number; z: number };
export type Pixel = {
  x: number;
  y: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  width: number;
  height: number;
};
export type Button = {
  kind: string;
  slot: number;
  texture: string;
  x: number;
  y: number;
  width: number;
  height: number;
};
export type ClientState = {
  combat: boolean;
  roster: number;
  selected: string[];
  cachedPoints: number;
  cachedCommand: string;
  attackMode: boolean;
  modeLabel: string;
  modeColors: number[];
  modeBold: boolean[];
  replacementPending: boolean;
  routePending: boolean;
  controlPending: boolean;
  control: {
    request: number;
    accepted: boolean;
    operation: string;
    selection: string[];
    groups: string[][];
  };
  route: {
    request: number;
    accepted: boolean;
    intent: string;
    points: Vec[];
    cycleStart: number;
    command: string;
  };
  state: {
    command: string;
    complete: boolean;
    stop: number;
    attackMove: boolean;
  };
  buttons: Button[];
  failure: string;
  overlay: string;
};
export type ServerState = {
  selection: string[];
  groups: string[][];
  owned: boolean[];
  reserved: boolean[];
  group: string;
  command: string;
  kind: string;
  phase: string;
  result: string;
  members: string[];
  memberGroups: string[];
  points: Vec[];
  cycleStart: number;
  complete: boolean;
  attackMove: boolean;
  stop: number;
  waiting: number;
  orders: string[];
};
export type Observation = { client: ClientState; server: ServerState };
export type Context = { colony: string; player: string; residents: string[] };

export function same(actual: string[], expected: string[]) {
  const a = [...actual].sort(),
    b = [...expected].sort();
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

export function samePoints(actual: Vec[], expected: Vec[]) {
  return (
    actual.length === expected.length &&
    actual.every(
      (point, index) => point.x === expected[index].x && point.y === expected[index].y && point.z === expected[index].z,
    )
  );
}

export function visible(at: Pixel) {
  expect(at.x, "input must lie inside the rendered viewport").toBeGreaterThan(5);
  expect(at.x).toBeLessThan(at.width - 5);
  expect(at.y, "input must avoid the toolbar").toBeGreaterThan(40);
  expect(at.y, "world input must avoid the 60px cards").toBeLessThan(at.height - 65);
}

export async function project(client: MinecraftClient, point: Vec, entity = ""): Promise<Pixel> {
  return client.run(PROJECT, { ...point, entity });
}

export async function waitFor(
  label: string,
  client: MinecraftClient,
  server: MinecraftServer,
  context: Context,
  predicate: (value: Observation) => boolean,
  attempts = 300,
  ticks = 1,
  observe?: (value: Observation) => void,
): Promise<Observation> {
  let latest: Observation | undefined;
  for (let attempt = 0; attempt < attempts; attempt++) {
    await sleepMs(150);
    await frames(client, 3);
    latest = {
      client: await client.run(READ_CLIENT),
      server: await server.run(READ_SERVER, context),
    };
    expect(latest.server.phase, `${label}: genuine group failure, ${JSON.stringify(latest.server)}`).not.toBe(
      "FAILED_WAITING",
    );
    expect(latest.client.failure, `${label}: no visible failure may enter the take`).toBe("");
    expect(latest.client.overlay, `${label}: successful commands remain silent`).toBe("");
    observe?.(latest);
    if (predicate(latest)) {
      return latest;
    }
  }
  throw new Error(`${label} did not converge: ${JSON.stringify(latest)}`);
}

export async function selection(
  client: MinecraftClient,
  server: MinecraftServer,
  context: Context,
  ids: string[],
  after: number,
) {
  return waitFor(
    "selection ACK and exclusive ownership",
    client,
    server,
    context,
    (value) =>
      value.client.control.request > after &&
      value.client.control.accepted &&
      !value.client.controlPending &&
      same(value.client.selected, ids) &&
      same(value.client.control.selection, ids) &&
      same(value.server.selection, ids),
  );
}

export async function routeAck(
  client: MinecraftClient,
  server: MinecraftServer,
  context: Context,
  after: number,
  intent: string,
  points: Vec[],
  cycleStart: number,
  attackMove: boolean,
) {
  return waitFor(
    `${intent} accepted with exact authored points`,
    client,
    server,
    context,
    (value) =>
      value.client.route.request > after &&
      value.client.route.accepted &&
      value.client.route.intent === intent &&
      !value.client.routePending &&
      !value.client.replacementPending &&
      samePoints(value.client.route.points, points) &&
      samePoints(value.server.points, points) &&
      value.server.cycleStart === cycleStart &&
      value.client.route.cycleStart === cycleStart &&
      value.server.attackMove === attackMove &&
      value.server.command === value.client.route.command &&
      squadIntact(value, context),
  );
}

export const READ_SERVER = defineTask<Context, ServerState>({
  name: "folkways.combat-rts-input.observe-server",
  side: "server",
  source: javaTask({
    imports: [
      "java.util.UUID",
      "java.util.List",
      "java.util.Map",
      "java.util.LinkedHashMap",
      "net.minecraft.server.MinecraftServer",
      "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.resident.api.Bodies",
      "io.github.izakyl.folkways.core.rts.combat.group.UnitGroups",
      "io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands",
      "io.github.izakyl.folkways.core.rts.combat.control.CombatSelectionOwnership",
    ],
    body: `
var a = Args.of(ctx);
var server = (MinecraftServer) ctx.server();
var player = server.getPlayerList().getPlayerByName(a.string("player"));
var colony = Colonies.of(server, UUID.fromString(a.string("colony"))).orElseThrow();
var ids = a.strings("residents").stream().map(UUID::fromString).toList();
var ownership = CombatSelectionOwnership.of(colony);
var snapshot = ownership.snapshot(player);
var groups = UnitGroups.of(colony);
var commands = ResidentCommands.of(colony);
var group = ids.stream().map(groups::ofResident).flatMap(java.util.Optional::stream).findFirst().orElse(null);
var out = new LinkedHashMap<String, Object>();
out.put("selection", snapshot.selection().stream().map(Object::toString).toList());
out.put("groups", snapshot.groups().stream().map(g -> g.stream().map(Object::toString).toList()).toList());
out.put("owned",
    ids.stream()
        .map(id -> {
            var entity = player.serverLevel().getEntity(id);
            return entity != null && Bodies.of(entity).filter(body -> ownership.owns(player, body)).isPresent();
        })
        .toList());
out.put("reserved", ids.stream().map(ownership::reserved).toList());
out.put("memberGroups",
    ids.stream().map(id -> groups.ofResident(id).map(g -> g.id().toString()).orElse("")).toList());
out.put("orders",
    ids.stream()
        .map(id -> commands.current(id).map(o -> o.mode().name() + ":" + o.status().name()).orElse("NONE"))
        .toList());
out.put("group", group == null ? "" : group.id().toString());
out.put("command", group == null ? "" : group.commandId().map(Object::toString).orElse(""));
out.put("kind", group == null ? "NONE" : group.commandKind().name());
out.put("phase", group == null ? "NONE" : group.phase().name());
out.put("result", group == null ? "NONE" : group.endReason().name());
out.put("members", group == null ? List.of() : group.members().stream().map(Object::toString).toList());
out.put("points",
    group == null ? List.of()
                  : group.pointRoute()
                        .stream()
                        .map(p -> Map.of("x", p.cell().getX(), "y", p.cell().getY(), "z", p.cell().getZ()))
                        .toList());
out.put("cycleStart", group == null ? -1 : group.cycleStart());
out.put("complete", group != null && group.routeComplete());
out.put("attackMove", group != null && group.attackMove());
out.put("stop", group == null ? -1 : group.patrolStop());
out.put("waiting", group == null ? 0 : group.waitingMembers());
return out;`,
  }),
});

export const READ_CLIENT = defineTask<void, ClientState>({
  name: "folkways.combat-rts-input.observe-client-and-rendered-hits",
  side: "client",
  source: javaTask({
    imports: [
      "java.util.List",
      "java.util.Map",
      "java.util.Set",
      "java.util.LinkedHashMap",
      "java.util.ArrayList",
      "io.github.izakyl.folkways.core.front.api.ui.OverheadTools",
      "io.github.izakyl.folkways.core.rts.combat.net.CombatPackets",
    ],
    body: `
var out = new LinkedHashMap<String, Object>();
var control = CombatPackets.controls().orElse(null);
var route = CombatPackets.route().orElse(null);
var state = CombatPackets.state();
var gui = net.minecraft.client.Minecraft.getInstance().gui;
var overlay = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");
overlay.setAccessible(true);
var overlayTime = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageTime");
overlayTime.setAccessible(true);
var message = (net.minecraft.network.chat.Component) overlay.get(gui);
out.put("overlay", overlayTime.getInt(gui) > 0 && message != null ? message.getString() : "");
out.put("failure", "");
out.put("roster", CombatPackets.roster().size());
out.put("controlPending", CombatPackets.controlPending());
out.put("routePending", CombatPackets.routePending());
out.put("control",
    control == null
        ? Map.of("request", 0L, "accepted", false, "operation", "", "selection", List.of(), "groups", List.of())
        : Map.of("request", control.request(), "accepted", control.accepted(), "operation",
              control.operation().name(), "selection",
              control.selection().stream().map(Object::toString).toList(), "groups",
              control.groups().stream().map(g -> g.stream().map(Object::toString).toList()).toList()));
out.put("route",
    route == null ? Map.of("request", 0L, "accepted", false, "intent", "", "points", List.of(), "cycleStart",
                        -1, "command", "")
                  : Map.of("request", route.request(), "accepted", route.accepted(), "intent",
                        route.intent().name(), "cycleStart", route.cycleStart(), "points",
                        route.points()
                            .stream()
                            .map(p -> Map.of("x", p.cell().getX(), "y", p.cell().getY(), "z", p.cell().getZ()))
                            .toList(),
                        "command", String.valueOf(route.state().commandId())));
out.put("state",
    Map.of("command", String.valueOf(state.commandId()), "complete", state.routeComplete(), "stop",
        state.stop(), "attackMove", state.attackMove()));
var handler = OverheadTools.selectedHandler().orElse(null);
boolean combat = handler != null && handler.getClass().getName().endsWith(".CombatController");
out.put("combat", combat);
out.put("selected", List.of());
out.put("cachedPoints", 0);
out.put("cachedCommand", "");
out.put("attackMode", false);
out.put("modeLabel", "");
out.put("modeColors", List.of());
out.put("modeBold", List.of());
out.put("replacementPending", false);
out.put("buttons", List.of());
if (combat) {
    var type = handler.getClass();
    var visibleFailure = type.getDeclaredMethod("visibleFailure");
    visibleFailure.setAccessible(true);
    var failure = (net.minecraft.network.chat.Component) visibleFailure.invoke(handler);
    out.put("failure", failure == null ? "" : failure.getString());
    var selected = type.getDeclaredField("selected");
    selected.setAccessible(true);
    out.put("selected", ((Set<?>) selected.get(handler)).stream().map(Object::toString).toList());
    var points = type.getDeclaredField("route");
    points.setAccessible(true);
    out.put("cachedPoints", ((List<?>) points.get(handler)).size());
    var command = type.getDeclaredField("routeCommandId");
    command.setAccessible(true);
    out.put("cachedCommand", String.valueOf(command.get(handler)));
    var mode = type.getDeclaredField("attackMoveMode");
    mode.setAccessible(true);
    out.put("attackMode", mode.getBoolean(handler));
    var label = type.getDeclaredMethod("movementMode");
    label.setAccessible(true);
    out.put("modeLabel", ((net.minecraft.network.chat.Component) label.invoke(handler)).getString());
    var options = ((net.minecraft.network.chat.contents.TranslatableContents)
        handler.cues().get(1).does().getContents()).getArgs();
    out.put("modeColors", java.util.Arrays.stream(options)
        .map(option -> ((net.minecraft.network.chat.Component) option).getStyle().getColor().getValue()).toList());
    out.put("modeBold", java.util.Arrays.stream(options)
        .map(option -> ((net.minecraft.network.chat.Component) option).getStyle().isBold()).toList());
    var pending = type.getDeclaredField("pendingReplacement");
    pending.setAccessible(true);
    out.put("replacementPending", pending.get(handler) != null);
    var cardsField = type.getDeclaredField("cards");
    cardsField.setAccessible(true);
    var cards = cardsField.get(handler);
    var hitsField = cards.getClass().getDeclaredField("hits");
    hitsField.setAccessible(true);
    var buttons = new ArrayList<Map<String, Object>>();
    var pressed = cards.getClass().getDeclaredField("PRESSED");
    pressed.setAccessible(true);
    for (var hit : (List<?>) hitsField.get(cards)) {
        var actionMethod = hit.getClass().getDeclaredMethod("action");
        actionMethod.setAccessible(true);
        var action = actionMethod.invoke(hit);
        var kindMethod = action.getClass().getDeclaredMethod("kind");
        kindMethod.setAccessible(true);
        var row = new LinkedHashMap<String, Object>();
        row.put("kind", kindMethod.invoke(action).toString());
        var slot = action.getClass().getDeclaredMethod("slot");
        slot.setAccessible(true);
        row.put("slot", slot.invoke(action));
        var texture = hit.getClass().getDeclaredMethod("texture");
        texture.setAccessible(true);
        row.put("texture", texture.invoke(hit) == pressed.get(null) ? "pressed" : "other");
        for (var field : List.of("x", "y", "width", "height")) {
            var accessor = hit.getClass().getDeclaredMethod(field);
            accessor.setAccessible(true);
            row.put(field, accessor.invoke(hit));
        }
        buttons.add(row);
    }
    out.put("buttons", buttons);
}
return out;`,
  }),
});

const PROJECT = defineTask<Vec & { entity: string }, Pixel>({
  name: "folkways.combat-rts-input.observe-world-projection",
  side: "client",
  source: javaTask({
    imports: [
      "java.util.Map",
      "java.util.Optional",
      "java.util.UUID",
      "net.minecraft.client.Minecraft",
      "net.minecraft.world.phys.Vec3",
      "net.minecraft.world.phys.AABB",
    ],
    body: `
var a = Args.of(ctx);
var mc = Minecraft.getInstance();
var type = Class.forName("io.github.izakyl.folkways.core.rts.client.CombatProjection");
var constructor = type.getDeclaredConstructor(Minecraft.class);
constructor.setAccessible(true);
var projection = constructor.newInstance(mc);
double left, top, right, bottom;
if (!a.string("entity").isEmpty()) {
    var id = UUID.fromString(a.string("entity"));
    net.minecraft.world.entity.Entity found = null;
    for (var entity : mc.level.entitiesForRendering()) {
        if (entity.getUUID().equals(id)) {
            found = entity;
            break;
        }
    }
    if (found == null) {
        throw new IllegalStateException("fixture resident is not client-tracked: " + id);
    }
    var bounds = type.getDeclaredMethod("bounds", AABB.class);
    bounds.setAccessible(true);
    var rect = ((Optional<?>) bounds.invoke(projection, found.getBoundingBox())).orElseThrow();
    var l = rect.getClass().getDeclaredMethod("left");
    l.setAccessible(true);
    left = ((Number) l.invoke(rect)).doubleValue();
    var t = rect.getClass().getDeclaredMethod("top");
    t.setAccessible(true);
    top = ((Number) t.invoke(rect)).doubleValue();
    var r = rect.getClass().getDeclaredMethod("right");
    r.setAccessible(true);
    right = ((Number) r.invoke(rect)).doubleValue();
    var b = rect.getClass().getDeclaredMethod("bottom");
    b.setAccessible(true);
    bottom = ((Number) b.invoke(rect)).doubleValue();
} else {
    var point = type.getDeclaredMethod("point", Vec3.class);
    point.setAccessible(true);
    var pixel = ((Optional<?>) point.invoke(
                     projection, new Vec3(a.integer("x") + 0.5, a.integer("y") + 0.12, a.integer("z") + 0.5)))
                    .orElseThrow();
    var x = pixel.getClass().getDeclaredMethod("x");
    x.setAccessible(true);
    left = right = ((Number) x.invoke(pixel)).doubleValue();
    var y = pixel.getClass().getDeclaredMethod("y");
    y.setAccessible(true);
    top = bottom = ((Number) y.invoke(pixel)).doubleValue();
}
return Map.of("x", (left + right) / 2, "y", (top + bottom) / 2, "left", left, "top", top, "right", right,
    "bottom", bottom, "width", mc.getWindow().getGuiScaledWidth(), "height",
    mc.getWindow().getGuiScaledHeight());`,
  }),
});

export function squadIntact(value: Observation, context: Context) {
  return (
    same(value.server.members, context.residents) &&
    same(value.server.selection, context.residents) &&
    same(value.client.selected, context.residents) &&
    value.server.memberGroups.every((group) => group === value.server.group) &&
    value.server.owned.every(Boolean) &&
    value.server.reserved.every(Boolean)
  );
}
