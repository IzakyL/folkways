import { defineTask, javaTask, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import type { Vec } from "../shared/colony/colony-founding";

// The debug overlay answers to where the player looks, not to where a free camera looks: its card is for whatever is
// under the player's crosshair, and its waypoint graph is read around the player's own feet. So this rig flies the
// camera and, when asked to, carries the player with it on every rendered frame, aimed exactly where the camera looks.

export type RigMove = {
  /** Eye positions flown through over `seconds`, eased in and out; one key holds still. */
  eye?: Vec[];
  /** Follows this entity at `offset` from its feet instead of flying the eye path. */
  follow?: string;
  offset?: Vec;
  /** Looks at this entity's middle, or along these points (eased like the eye). */
  lookAt?: string;
  look?: Vec[];
  seconds?: number;
  /** Carry the player with the camera (the card and the graph follow it); otherwise the player stays parked. */
  carry?: boolean;
  /** Springiness of the eye; higher keeps closer to the path. */
  omega?: number;
  /** Scrolls the debug card from `from` to `to` gui px over `seconds`, starting `delay` seconds into the move. */
  scroll?: { from: number; to: number; delay: number; seconds: number };
};

export async function rigMove(client: MinecraftClient, move: RigMove) {
  return client.run(RIG, { action: "move", ...move });
}

export async function rigStop(client: MinecraftClient) {
  return client.run(RIG, { action: "stop" });
}

export async function rigState(client: MinecraftClient) {
  return client.run(RIG, { action: "state" });
}

const RIG = defineTask<Record<string, any>, any>({
  name: "folkways.promo.debug-rig",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "blockwright.minecraft.core.client.View", "dev.blockwright.api.CameraPose", "dev.blockwright.api.CameraOverride",
      "net.minecraft.client.Minecraft", "net.minecraft.world.entity.Entity", "net.minecraft.world.phys.Vec3",
    ],
    body: `
var a = Args.of(ctx);
var mc = Minecraft.getInstance();
String action = a.string("action");
if (action.equals("stop")) {
    View.releaseCamera();
    rig = null;
    return Map.of("stopped", true);
}
if (action.equals("state")) {
    if (rig == null) return Map.of("rig", false);
    return Map.of("rig", true, "eye", List.of(rig.eye[0], rig.eye[1], rig.eye[2]), "yaw", rig.yaw, "pitch", rig.pitch,
        "frames", rig.frames, "lost", rig.lost);
}
Rig next = new Rig();
if (rig != null) {
    System.arraycopy(rig.eye, 0, next.eye, 0, 3);
    System.arraycopy(rig.vel, 0, next.vel, 0, 3);
    next.started = true;
    // A new aim turns over from where the last one looked instead of snapping.
    if (rig.lastLook != null) next.fromLook = rig.lastLook.clone();
}
next.keys = points(a.value("eye"));
next.looks = points(a.value("look"));
next.seconds = a.has("seconds") ? a.number("seconds") : 0;
next.carry = a.has("carry") && Boolean.TRUE.equals(a.value("carry"));
next.omega = a.has("omega") ? a.number("omega") : 4.0;
next.follow = entityId(mc, a.has("follow") ? String.valueOf(a.value("follow")) : null);
next.lookAt = entityId(mc, a.has("lookAt") ? String.valueOf(a.value("lookAt")) : null);
var offset = points(a.value("offset"));
if (!offset.isEmpty()) next.offset = offset.get(0);
if (a.has("scroll")) {
    var s = (Map<?, ?>) a.value("scroll");
    next.scrollFrom = ((Number) s.get("from")).doubleValue();
    next.scrollTo = ((Number) s.get("to")).doubleValue();
    next.scrollDelay = ((Number) s.get("delay")).doubleValue();
    next.scrollSeconds = ((Number) s.get("seconds")).doubleValue();
    next.scrolling = true;
}
if (next.keys.isEmpty() && next.follow == null) throw new IllegalArgumentException("a move needs an eye path or an entity to follow");
if (next.looks.isEmpty() && next.lookAt == null) throw new IllegalArgumentException("a move needs somewhere to look");
if (a.has("follow") && next.follow == null) throw new IllegalArgumentException("no client entity " + a.value("follow"));
if (a.has("lookAt") && next.lookAt == null) throw new IllegalArgumentException("no client entity " + a.value("lookAt"));
next.t0 = System.nanoTime();
next.previous = next.t0;
rig = next;
Rig held = next;
View.holdCamera(CameraOverride.of(Boolean.FALSE, partial -> held.frame(partial)));
return Map.of("moving", true, "keys", next.keys.size(), "carry", next.carry);`,
    members: `
private static Rig rig;
private static java.lang.reflect.Field scrollField;

static List<double[]> points(Object raw) {
    var out = new ArrayList<double[]>();
    if (raw instanceof List<?> list) for (var one : list) {
        var p = (Map<?, ?>) one;
        out.add(new double[] {((Number) p.get("x")).doubleValue(), ((Number) p.get("y")).doubleValue(), ((Number) p.get("z")).doubleValue()});
    }
    return out;
}

static Integer entityId(Minecraft mc, String uuid) {
    if (uuid == null || mc.level == null) return null;
    for (var entity : mc.level.entitiesForRendering()) if (entity.getUUID().toString().equals(uuid)) return entity.getId();
    return null;
}

static double ease(double t) {
    t = Math.max(0, Math.min(1, t));
    return t * t * t * (t * (6 * t - 15) + 10);
}

static double[] along(List<double[]> keys, double s) {
    if (keys.size() == 1) return keys.get(0).clone();
    double scaled = s * (keys.size() - 1);
    int i = Math.min(keys.size() - 2, (int) Math.floor(scaled));
    double local = scaled - i;
    var p = keys.get(i);
    var q = keys.get(i + 1);
    // Catmull-Rom through the keys, so a path of several keys does not kink at them.
    var o = keys.get(Math.max(0, i - 1));
    var r = keys.get(Math.min(keys.size() - 1, i + 2));
    double[] out = new double[3];
    double t = local, t2 = t * t, t3 = t2 * t;
    for (int k = 0; k < 3; k++) {
        out[k] = 0.5 * ((2 * p[k]) + (-o[k] + q[k]) * t + (2 * o[k] - 5 * p[k] + 4 * q[k] - r[k]) * t2
            + (-o[k] + 3 * p[k] - 3 * q[k] + r[k]) * t3);
    }
    return out;
}

private static final class Rig {
    final double[] eye = new double[3], vel = new double[3];
    List<double[]> keys = List.of(), looks = List.of();
    double[] offset = {0, 2, -4};
    double[] fromLook, lastLook;
    double blend = 1.2;
    double seconds, omega;
    boolean carry, started, scrolling;
    Integer follow, lookAt;
    double scrollFrom, scrollTo, scrollDelay, scrollSeconds;
    long t0, previous;
    float yaw, pitch;
    int frames, lost;

    CameraPose frame(float partial) {
        var mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double elapsed = (now - t0) / 1.0e9;
        double s = seconds <= 0 ? 1 : ease(elapsed / seconds);
        double[] want;
        if (follow != null) {
            Entity entity = mc.level == null ? null : mc.level.getEntity(follow);
            if (entity == null) { lost++; want = eye.clone(); }
            else { var at = entity.getPosition(partial); want = new double[] {at.x + offset[0], at.y + offset[1], at.z + offset[2]}; }
        } else {
            want = along(keys, s);
        }
        double dt = Math.min(0.1, (now - previous) / 1.0e9);
        previous = now;
        if (!started) {
            System.arraycopy(want, 0, eye, 0, 3);
            started = true;
        } else if (dt > 0) {
            int steps = Math.max(1, (int) Math.ceil(dt * 240));
            double h = dt / steps;
            for (int step = 0; step < steps; step++) for (int k = 0; k < 3; k++) {
                double acc = omega * omega * (want[k] - eye[k]) - 2 * omega * vel[k];
                vel[k] += acc * h;
                eye[k] += vel[k] * h;
            }
        }
        double[] target;
        if (lookAt != null) {
            Entity entity = mc.level == null ? null : mc.level.getEntity(lookAt);
            if (entity == null) { lost++; target = new double[] {eye[0], eye[1], eye[2] + 1}; }
            else { var at = entity.getPosition(partial); target = new double[] {at.x, at.y + entity.getBbHeight() * 0.55, at.z}; }
        } else {
            target = along(looks, s);
        }
        if (fromLook != null && elapsed < blend) {
            double b = ease(elapsed / blend);
            for (int k = 0; k < 3; k++) target[k] = fromLook[k] + (target[k] - fromLook[k]) * b;
        }
        lastLook = target.clone();
        double dx = target[0] - eye[0], dy = target[1] - eye[1], dz = target[2] - eye[2];
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        var player = mc.player;
        if (carry && player != null) {
            double fy = eye[1] - player.getEyeHeight();
            player.setPos(eye[0], fy, eye[2]);
            player.xo = eye[0]; player.yo = fy; player.zo = eye[2];
            player.xOld = eye[0]; player.yOld = fy; player.zOld = eye[2];
            player.setDeltaMovement(Vec3.ZERO);
            player.setYRot(yaw); player.setXRot(pitch); player.yRotO = yaw; player.xRotO = pitch;
            player.setYHeadRot(yaw); player.yHeadRotO = yaw; player.yBodyRot = yaw; player.yBodyRotO = yaw;
        }
        if (scrolling) {
            double into = (elapsed - scrollDelay) / Math.max(0.01, scrollSeconds);
            int value = (int) Math.round(scrollFrom + (scrollTo - scrollFrom) * ease(into));
            try {
                if (scrollField == null) {
                    scrollField = Class.forName("io.github.izakyl.folkways.core.front.client.ColonyDebugState").getDeclaredField("scroll");
                    scrollField.setAccessible(true);
                }
                scrollField.setInt(null, value);
            } catch (ReflectiveOperationException e) {
                scrolling = false;
            }
        }
        frames++;
        return new CameraPose(true, eye[0], eye[1], eye[2], true, yaw, pitch);
    }
}`,
  }),
});

/** What the client's debug overlay holds right now: the card it shows, its lines and links, and the waypoint graph. */
export async function readOverlay(client: MinecraftClient) {
  return client.run(READ_OVERLAY, {});
}

const READ_OVERLAY = defineTask<Record<string, any>, any>({
  name: "folkways.promo.debug-overlay-read",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.client.Minecraft", "net.minecraft.Util",
      "io.github.izakyl.folkways.core.front.api.notice.Line",
      "io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket",
      "io.github.izakyl.folkways.core.front.engine.net.DebugTarget",
      "io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug",
    ],
    body: `
var type = Class.forName("io.github.izakyl.folkways.core.front.client.ColonyDebugState");
var mc = Minecraft.getInstance();
var colony = get(type, "colony");
var snapshot = (DebugFocusPacket) get(type, "snapshot");
var graph = (WayGraphDebug.Snapshot) get(type, "waypoints");
long now = Util.getMillis();
var out = new LinkedHashMap<String, Object>();
out.put("colony", String.valueOf(colony));
out.put("fresh", now - ((Number) get(type, "lastReply")).longValue() <= 2000L);
out.put("freshWaypoints", now - ((Number) get(type, "lastWaypointsReply")).longValue() <= 3000L);
out.put("hideGui", mc.options.hideGui);
out.put("guiScale", mc.getWindow().getGuiScale());
out.put("scroll", get(type, "scroll"));
out.put("contentHeight", get(type, "contentHeight"));
String target = switch (snapshot.target()) {
    case DebugTarget.Resident resident -> {
        var entity = mc.level == null ? null : mc.level.getEntity(resident.entityId());
        yield "resident:" + (entity == null ? "?" : entity.getUUID().toString());
    }
    case DebugTarget.Block block -> "block:" + block.pos().getX() + "," + block.pos().getY() + "," + block.pos().getZ();
    case DebugTarget.None none -> "none";
};
out.put("target", target);
var lines = new ArrayList<String>();
for (Line line : snapshot.card()) lines.add(text(line));
out.put("card", lines);
var links = new ArrayList<String>();
for (var link : snapshot.links()) links.add(link.kind() + "#" + link.index() + ":" + link.label().getString());
out.put("links", links);
var stores = new LinkedHashMap<String, Integer>();
int differs = 0;
for (var mark : snapshot.stores()) {
    stores.merge(mark.state().name(), 1, Integer::sum);
    if (mark.differs()) differs++;
}
out.put("stores", stores);
out.put("storesDiffer", differs);
var points = new LinkedHashMap<String, Integer>();
for (var point : graph.points()) points.merge(point.kind().name(), 1, Integer::sum);
var edges = new LinkedHashMap<String, Integer>();
for (var edge : graph.edges()) edges.merge(edge.kind().name() + ":" + edge.sort(), 1, Integer::sum);
out.put("points", points);
out.put("edges", edges);
out.put("truncated", graph.truncated());
return out;`,
    members: `
static Object get(Class<?> type, String name) throws ReflectiveOperationException {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
}

static String text(Line line) {
    return switch (line) {
        case Line.Said said -> said.notice().component().getString();
        case Line.Literal literal -> literal.text();
        case Line.Text text -> text.component().getString();
        default -> line.toString();
    };
}`,
  }),
});

/** The client set up for reading the card: GUI scale, no tutorial toast, chat kept small. */
export async function prepareClient(client: MinecraftClient, guiScale: number, chatScale = 0.5, chatWidth = 0.6) {
  return client.run(PREPARE_CLIENT, { guiScale, chatScale, chatWidth });
}

const PREPARE_CLIENT = defineTask<Record<string, any>, any>({
  name: "folkways.promo.debug-client",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.client.tutorial.TutorialSteps"],
    body: `
var mc = Minecraft.getInstance();
mc.options.guiScale().set((int) Args.of(ctx).integer("guiScale"));
mc.options.hideGui = false;
mc.options.tutorialStep = TutorialSteps.NONE;
mc.getTutorial().setStep(TutorialSteps.NONE);
mc.getToasts().clear();
// The debug reports still scroll by in chat, small enough to leave the picture and the card clear.
mc.options.chatScale().set(Args.of(ctx).number("chatScale"));
mc.options.chatWidth().set(Args.of(ctx).number("chatWidth"));
mc.resizeDisplay();
mc.options.save();
return Map.of("guiScale", mc.getWindow().getGuiScale(), "width", mc.getWindow().getGuiScaledWidth(),
    "height", mc.getWindow().getGuiScaledHeight(), "lang", mc.options.languageCode);`,
  }),
});

/** Turns the debug overlay on for the player, as the settings page's Debug logs switch does. */
export async function enableDebug(server: MinecraftServer, player: string) {
  return server.run(ENABLE_DEBUG, { player });
}

const ENABLE_DEBUG = defineTask<Record<string, any>, any>({
  name: "folkways.promo.debug-enable",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.engine.colony.LaborDebug"],
    body: `
var player = ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
if (player == null) throw new IllegalStateException("no player");
LaborDebug.set(player, true);
return Map.of("enabled", LaborDebug.enabled(player));`,
  }),
});

/** The colony's published waypoint graph read around a point, the same way the overlay reads it around the player. */
export async function readWayGraph(server: MinecraftServer, colony_id: string, centre: Vec) {
  return server.run(READ_WAYGRAPH, { colony_id, centre });
}

const READ_WAYGRAPH = defineTask<Record<string, any>, any>({
  name: "folkways.promo.debug-waygraph",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.travel.internal.ColonyWays",
      "io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug",
      "net.minecraft.world.phys.Vec3",
    ],
    body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var c = (Map<?, ?>) args.value("centre");
var centre = new Vec3(((Number) c.get("x")).doubleValue(), ((Number) c.get("y")).doubleValue(), ((Number) c.get("z")).doubleValue());
var ways = ColonyWays.published(UUID.fromString(args.string("colony_id")), level);
if (ways.isEmpty()) return Map.of("published", false);
var graph = WayGraphDebug.read(level, ways.get(), centre);
var points = new LinkedHashMap<String, Integer>();
for (var point : graph.points()) points.merge(point.kind().name(), 1, Integer::sum);
var edges = new LinkedHashMap<String, Integer>();
var crossings = new ArrayList<Object>();
for (var edge : graph.edges()) {
    edges.merge(edge.kind().name() + ":" + edge.sort(), 1, Integer::sum);
    if (edge.kind() == WayGraphDebug.EdgeKind.CROSSING) {
        var from = graph.points().get(edge.from()).pos();
        var to = graph.points().get(edge.to()).pos();
        crossings.add(Map.of("sort", edge.sort().toString(), "from", List.of(from.getX(), from.getY(), from.getZ()),
            "to", List.of(to.getX(), to.getY(), to.getZ())));
    }
}
return Map.of("published", true, "points", points, "edges", edges, "crossings", crossings, "truncated", graph.truncated());`,
  }),
});
