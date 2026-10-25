package io.github.izakyl.folkways.war.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.front.client.Overhead;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.plugins.combat.net.CombatControlPacket;
import io.github.izakyl.folkways.plugins.combat.net.CombatPackets;
import io.github.izakyl.folkways.plugins.combat.net.CombatWaypointReply;
import io.github.izakyl.folkways.plugins.combat.ui.CombatContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Pointer feedback is local; selection, shortcut membership and every waypoint are server-authoritative. */
final class CombatController implements OverheadTools.Handler {
    private static final int SELECTED = 0xFF8FEAC3;
    private static final int DRAFT = 0xFFFFDC7A;
    private static final int CLOSE_RADIUS = 12;
    private static final int MAX_ROUTE_SAMPLES = 512;
    private final CombatCards cards = new CombatCards();
    private final LinkedHashSet<UUID> selected = new LinkedHashSet<>();
    private final Map<Integer, List<UUID>> shortcuts = new HashMap<>();
    private final Map<UUID, ResidentEntity> residents = new LinkedHashMap<>();
    private final List<WorldPos> route = new ArrayList<>();
    private Context context;
    private int requestIn;
    private int heartbeatIn;
    private long controlRevision = -1, routeRevision = -1;
    private int cycleStart = -1;
    private boolean routeOpen;
    private boolean routeAttackMove;
    private UUID routeCommandId;
    private boolean attackMoveArmed;
    private int attackMoveArmGeneration;
    private PendingRoute pendingReplacement;
    private boolean dragging;
    private boolean additiveSelection;
    private double startX, startY, endX, endY;
    private Component notice = Component.empty();
    private long noticeUntil;
    private WorldPos moveMarker;
    private long markerUntil;

    private record Context(UUID player, UUID colony, Realm realm) { }

    /** Input-only route context while a replacement is waiting for its authoritative reply. */
    private static final class PendingRoute {
        final boolean attackMove;
        final int armGeneration;
        final CombatWaypointReply.Intent initialIntent;
        final List<WorldPos> points = new ArrayList<>();
        final ArrayDeque<WorldPos> additions = new ArrayDeque<>();
        int cycleStart = -1;
        long initialRequest;
        long additionRequest;
        boolean awaitingInitialAck = true;
        boolean awaitingAdditionAck;

        PendingRoute(WorldPos first, boolean attackMove, int armGeneration,
                CombatWaypointReply.Intent initialIntent, long initialRequest) {
            this.points.add(first);
            this.attackMove = attackMove;
            this.armGeneration = armGeneration;
            this.initialIntent = initialIntent;
            this.initialRequest = initialRequest;
        }
    }

    private boolean active() {
        return Overhead.activeScreen() && OverheadTools.selected().filter(tool -> tool.id().equals(CombatContent.ID)).isPresent();
    }

    void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            reset(); return;
        }
        Context now = CurrentColony.id(minecraft.player)
            .map(colony -> new Context(minecraft.player.getUUID(), colony, WorldSpaces.at(minecraft.player).realm())).orElse(null);
        if (!java.util.Objects.equals(context, now)) {
            reset(); context = now;
        }
        if (context == null) { return; }
        synchronizeControl();
        boolean retained = !selected.isEmpty() || shortcuts.values().stream().anyMatch(group -> !group.isEmpty());
        if (!active()) {
            if (retained && heartbeatIn-- <= 0) { CombatPackets.heartbeat(); heartbeatIn = 40; }
            return;
        }
        if (requestIn-- <= 0) { CombatPackets.requestRoster(); requestIn = 20; }
        if (retained && heartbeatIn-- <= 0) { CombatPackets.heartbeat(); heartbeatIn = 40; }
        residents.clear();
        if (!rosterReady()) { return; }
        Set<UUID> roster = CombatPackets.roster();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof ResidentEntity resident && resident.isAlive() && !resident.isRemoved()
                && roster.contains(resident.getUUID())) { residents.put(resident.getUUID(), resident); }
        }
        synchronizeRoute();
    }

    private void synchronizeControl() {
        if (CombatPackets.controlPending() || controlRevision == CombatPackets.controlRevision()) { return; }
        CombatPackets.controls().filter(state -> state.colonyId().equals(context.colony())
            && state.at().realm().equals(context.realm())).ifPresent(state -> {
                Set<UUID> next = new LinkedHashSet<>(state.selection());
                int previousSelectionSize = selected.size();
                boolean selectionChanged = !selected.equals(next);
                if (selectionChanged) { clearRoute(); cards.reset(); }
                selected.clear(); selected.addAll(next);
                if (selected.isEmpty()) { disarmAttackMove(); }
                shortcuts.clear();
                for (int slot = 1; slot <= 9; slot++) { shortcuts.put(slot, state.groups().get(slot - 1)); }
                boolean stableHeartbeat = state.accepted()
                    && state.operation() == CombatControlPacket.Operation.HEARTBEAT
                    && pendingReplacement == null && !CombatPackets.routePending();
                boolean routeSnapshot = selectionChanged || state.accepted() && (
                    state.operation() == CombatControlPacket.Operation.SELECT
                    || state.operation() == CombatControlPacket.Operation.RECALL
                    || state.operation() == CombatControlPacket.Operation.RELEASE
                    || state.operation() == CombatControlPacket.Operation.SUSPEND) || stableHeartbeat;
                if (routeSnapshot) {
                    route.clear(); route.addAll(state.points());
                    cycleStart = state.cycleStart(); routeOpen = state.open();
                    routeAttackMove = state.attackMove();
                    routeCommandId = state.state().commandId();
                }
                if (state.operation() == CombatControlPacket.Operation.RELEASE) {
                    if (state.accepted()) { tell("folkways.war.release_accepted", Math.max(0, previousSelectionSize - next.size())); }
                    else { tell("folkways.combat.error.rejected"); }
                }
            });
        controlRevision = CombatPackets.controlRevision();
    }

    private void synchronizeRoute() {
        if (routeRevision == CombatPackets.routeRevision()) { return; }
        CombatPackets.route().filter(state -> state.colonyId().equals(context.colony())
            && state.at().realm().equals(context.realm()) && Set.copyOf(state.residents()).equals(selected)).ifPresent(state -> {
                route.clear(); route.addAll(state.points());
                cycleStart = state.cycleStart(); routeOpen = state.open();
                routeAttackMove = state.state().attackMove();
                routeCommandId = state.state().commandId();
                resolvePendingRoute(state);
                if (state.accepted()) {
                    switch (state.intent()) {
                        case MOVE -> tell("folkways.war.move_accepted", selected.size());
                        case ATTACK -> tell("folkways.war.attack_accepted", selected.size());
                        case WAYPOINT -> tell(cycleStart >= 0 ? "folkways.war.patrol_sent" : "folkways.war.waypoint_added",
                            cycleStart >= 0 ? selected.size() : route.size());
                        case STOP -> tell("folkways.war.stop_accepted", selected.size());
                    }
                } else { moveMarker = null; tell("folkways.combat.error.rejected"); }
            });
        routeRevision = CombatPackets.routeRevision();
    }

    private void resolvePendingRoute(CombatWaypointReply reply) {
        PendingRoute pending = pendingReplacement;
        if (pending == null) { return; }
        if (pending.awaitingInitialAck && reply.request() == pending.initialRequest
                && reply.intent() == pending.initialIntent) {
            pending.awaitingInitialAck = false;
            if (!reply.accepted()) { pendingReplacement = null; return; }
            if (pending.attackMove && reply.attackMove() && attackMoveArmed
                    && pending.armGeneration == attackMoveArmGeneration) {
                attackMoveArmed = false;
                attackMoveArmGeneration++;
            }
            sendNextPendingAddition(pending);
            return;
        }
        if (pending.awaitingAdditionAck && reply.request() == pending.additionRequest
                && reply.intent() == CombatWaypointReply.Intent.WAYPOINT) {
            pending.awaitingAdditionAck = false;
            if (!reply.accepted()) { pendingReplacement = null; return; }
            if (reply.cycleStart() >= 0) { pending.cycleStart = reply.cycleStart(); }
            pending.additions.removeFirst();
            sendNextPendingAddition(pending);
        }
    }

    private void sendNextPendingAddition(PendingRoute pending) {
        if (pendingReplacement != pending || pending.awaitingInitialAck || pending.awaitingAdditionAck) { return; }
        if (pending.additions.isEmpty()) { pendingReplacement = null; return; }
        WorldPos point = pending.additions.peekFirst();
        long request = sendRouteRequest(() -> CombatPackets.append(List.copyOf(selected), point, pending.attackMove));
        if (request < 0) { pendingReplacement = null; tell("folkways.war.syncing"); return; }
        pending.additionRequest = request;
        pending.awaitingAdditionAck = true;
    }

    private long sendRouteRequest(BooleanSupplier submit) {
        return submit.getAsBoolean() ? CombatPackets.lastRouteRequest() : -1;
    }

    private boolean rosterReady() {
        return context != null && CombatPackets.rosterColony().filter(context.colony()::equals).isPresent()
            && CombatPackets.rosterAt().filter(at -> at.realm().equals(context.realm())).isPresent();
    }

    @Override
    public List<Cue> cues() {
        return List.of(Cue.of("folkways.keys.combat.select", Cue.Key.CLICK),
            Cue.of("folkways.keys.combat.order", Cue.Key.ALT_CLICK),
            Cue.of("folkways.keys.combat.route", Cue.Key.MODIFIER, Cue.Key.ALT_CLICK));
    }

    @Override
    public boolean compactHints() { return true; }

    @Override
    public boolean showResidentCard(UUID resident) { return !selected.contains(resident); }

    @Override
    public int bottomInset() { return CombatCards.HEIGHT; }

    @Override
    public boolean blocksCameraDescent() { return dragging; }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (y >= cards.top()) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                CombatCards.Action action = cards.clicked(x, y);
                if (action != null && action.kind() == CombatCards.Kind.ATTACK_MOVE) {
                    attackMoveArmed = !attackMoveArmed;
                    attackMoveArmGeneration++;
                    return true;
                }
                if (action != null && (action.kind() == CombatCards.Kind.STOP || action.kind() == CombatCards.Kind.RELEASE)
                        && selected.isEmpty()) { return true; }
                if (!ready()) { return true; }
                if (action != null) {
                    switch (action.kind()) {
                        case GROUP -> shortcut(action.slot(), Screen.hasControlDown());
                        case UNIT -> {
                            if (residents.containsKey(action.resident())) { replaceSelection(Set.of(action.resident())); }
                        }
                        case PREVIOUS -> cards.page(false, selected.size());
                        case NEXT -> cards.page(true, selected.size());
                        case ATTACK_MOVE -> { }
                        case STOP -> {
                            if (!selected.isEmpty() && sendRouteRequest(() -> CombatPackets.stop(List.copyOf(selected))) < 0) {
                                tell("folkways.war.syncing");
                            } else if (!selected.isEmpty()) { cancelPendingRoute(); }
                        }
                        case RELEASE -> {
                            if (!selected.isEmpty() && !CombatPackets.release(List.copyOf(selected))) { tell("folkways.war.syncing"); }
                        }
                    }
                }
            }
            return true;
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            additiveSelection = Screen.hasShiftDown();
            dragging = true; startX = endX = x; startY = endY = y; return true;
        }
        if (button != InputConstants.MOUSE_BUTTON_RIGHT) { return false; }
        if (dragging) { dragging = false; return true; }
        // Always consume RMB here: even an invalid command must not fall through to opening the book.
        if (!ready()) { return true; }
        if (selected.isEmpty()) { tell("folkways.war.select_first"); return true; }
        PendingRoute pending = pendingReplacement;
        List<WorldPos> inputRoute = pending == null ? route : pending.points;
        int inputCycleStart = pending == null ? cycleStart : pending.cycleStart;
        if (Screen.hasControlDown() && inputCycleStart >= 0) {
            tell("folkways.war.route_closed"); return true;
        }
        int closing = Screen.hasControlDown() && pending == null ? nearWaypoint(x, y, route, routeOpen) : -1;
        if (closing >= 0) {
            if (closing >= inputRoute.size() - 1) { tell("folkways.war.cycle_too_short"); return true; }
            if (attackMoveArmed && !routeAttackMove) { tell("folkways.war.attack_move_new_route"); return true; }
            if (sendRouteRequest(() -> CombatPackets.append(List.copyOf(selected), inputRoute.get(closing), routeAttackMove)) < 0) {
                tell("folkways.war.syncing");
            }
            return true;
        }
        if (!Screen.hasControlDown()) {
            Optional<Mob> target = target(x, y);
            if (target.isPresent()) {
                if (sendRouteRequest(() -> CombatPackets.attack(List.copyOf(selected), target.get().getUUID())) >= 0) {
                    cancelPendingRoute();
                    tell("folkways.war.attack_sent", selected.size(), target.get().getDisplayName());
                } else { tell("folkways.war.syncing"); }
                return true;
            }
        }
        Optional<WorldPos> point = ground(x, y);
        if (point.isEmpty()) { tell("folkways.war.ground_required"); return true; }
        if (Screen.hasControlDown()) {
            if (pending != null) {
                if (pending.cycleStart >= 0) { tell("folkways.war.route_closed"); return true; }
                int existing = pending.points.indexOf(point.get());
                if (existing >= 0) {
                    if (existing >= pending.points.size() - 1) { tell("folkways.war.cycle_too_short"); return true; }
                    pending.cycleStart = existing;
                    pending.additions.addLast(point.get());
                    sendNextPendingAddition(pending);
                    return true;
                }
                if (pending.points.size() >= CombatPackets.MAX_POINTS) {
                    tell("folkways.war.route_full", CombatPackets.MAX_POINTS); return true;
                }
                pending.points.add(point.get());
                pending.additions.addLast(point.get());
                sendNextPendingAddition(pending);
                return true;
            }
            if (!route.isEmpty() && attackMoveArmed && !routeAttackMove) {
                tell("folkways.war.attack_move_new_route"); return true;
            }
            boolean mode = route.isEmpty() ? attackMoveArmed : routeAttackMove;
            long request = sendRouteRequest(() -> CombatPackets.append(List.copyOf(selected), point.get(), mode));
            if (request < 0) { tell("folkways.war.syncing"); }
            else if (route.isEmpty()) {
                pendingReplacement = new PendingRoute(point.get(), mode, attackMoveArmGeneration,
                    CombatWaypointReply.Intent.WAYPOINT, request);
            }
        } else {
            boolean mode = attackMoveArmed;
            long request = sendRouteRequest(() -> CombatPackets.submit(List.copyOf(selected), List.of(point.get()), -1, mode));
            if (request >= 0) {
                pendingReplacement = new PendingRoute(point.get(), mode, attackMoveArmGeneration,
                    CombatWaypointReply.Intent.MOVE, request);
                moveMarker = point.get(); markerUntil = Util.getMillis() + 3000;
                tell("folkways.war.move_sent", selected.size());
            } else { tell("folkways.war.syncing"); }
        }
        return true;
    }

    private boolean ready() {
        tick();
        if (!rosterReady()) { tell("folkways.war.syncing"); return false; }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !dragging) { return false; }
        endX = x; endY = y; return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !dragging) { return button == InputConstants.MOUSE_BUTTON_RIGHT; }
        dragging = false; endX = x; endY = y;
        if (!ready()) { return true; }
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection projection = new CombatProjection(minecraft);
        Set<UUID> next = new LinkedHashSet<>();
        CombatProjection.Rect area = CombatProjection.Rect.between(startX, startY, endX, endY);
        if (Math.hypot(endX - startX, endY - startY) < 4) {
            double nearest = Double.POSITIVE_INFINITY;
            UUID pointed = null;
            for (ResidentEntity resident : residents.values()) {
                Optional<CombatProjection.Rect> bounds = projection.bounds(resident.getBoundingBox());
                if (bounds.isPresent() && bounds.get().contains(x, y)) {
                    double distance = resident.distanceToSqr(minecraft.gameRenderer.getMainCamera().getPosition());
                    if (distance < nearest) { nearest = distance; pointed = resident.getUUID(); }
                }
            }
            if (pointed != null) { next.add(pointed); }
        } else {
            for (ResidentEntity resident : residents.values()) {
                projection.bounds(resident.getBoundingBox()).filter(area::intersects).ifPresent(bounds -> next.add(resident.getUUID()));
            }
        }
        if (additiveSelection) {
            Set<UUID> combined = new LinkedHashSet<>(selected);
            if (Math.hypot(endX - startX, endY - startY) < 4 && next.size() == 1 && combined.contains(next.iterator().next())) {
                combined.remove(next.iterator().next());
            } else { combined.addAll(next); }
            replaceSelection(combined);
        } else { replaceSelection(next); }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int slot = keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9 ? keyCode - GLFW.GLFW_KEY_0
            : keyCode >= GLFW.GLFW_KEY_KP_1 && keyCode <= GLFW.GLFW_KEY_KP_9 ? keyCode - GLFW.GLFW_KEY_KP_0 : 0;
        if (slot > 0) {
            if (!ready()) { return true; }
            boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                || Minecraft.ON_OSX && (modifiers & GLFW.GLFW_MOD_SUPER) != 0 || Screen.hasControlDown();
            shortcut(slot, control);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && !selected.isEmpty()) {
            replaceSelection(Set.of());
            return true;
        }
        return false;
    }

    private void replaceSelection(Set<UUID> next) {
        if (next.size() > CombatPackets.MAX_MEMBERS) { tell("folkways.war.selection_limit", CombatPackets.MAX_MEMBERS); return; }
        if (!CombatPackets.select(List.copyOf(next))) { tell("folkways.war.syncing"); return; }
        if (next.isEmpty()) { disarmAttackMove(); }
        if (!selected.equals(next)) { clearRoute(); }
        selected.clear(); selected.addAll(next);
        cards.reset();
        heartbeatIn = 40;
        tell("folkways.war.selected", selected.size());
    }

    private void shortcut(int slot, boolean bind) {
        boolean sent = bind ? CombatPackets.bind(slot) : CombatPackets.recall(slot);
        if (!sent) { tell("folkways.war.syncing"); return; }
        if (!bind) { clearRoute(); }
        heartbeatIn = 40;
        tell("folkways.war.group_updating", slot);
    }

    private Optional<Mob> target(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        return Overhead.screenRay(x, y).flatMap(ray -> {
            Vec3 end = ray.origin().add(ray.direction().scale(512));
            BlockHitResult obstruction = minecraft.level.clip(new ClipContext(ray.origin(), end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
            double nearest = obstruction.getLocation().distanceToSqr(ray.origin());
            Mob aimed = null;
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.isRemoved() || !mob.isPickable()
                    || Bodies.of(mob).isPresent() || mob.isAlliedTo(minecraft.player) || minecraft.player.isAlliedTo(mob)
                    || selected.stream().map(residents::get).filter(java.util.Objects::nonNull)
                        .anyMatch(friend -> mob.isAlliedTo(friend) || friend.isAlliedTo(mob))
                    || mob instanceof TamableAnimal pet && pet.isOwnedBy(minecraft.player)) { continue; }
                Optional<Vec3> hit = mob.getBoundingBox().inflate(.15).clip(ray.origin(), end);
                if (hit.isPresent() && hit.get().distanceToSqr(ray.origin()) < nearest) {
                    nearest = hit.get().distanceToSqr(ray.origin()); aimed = mob;
                }
            }
            return Optional.ofNullable(aimed);
        });
    }

    private Optional<WorldPos> ground(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        return Overhead.screenRay(x, y).flatMap(ray -> {
            BlockHitResult hit = minecraft.level.clip(new ClipContext(ray.origin(), ray.origin().add(ray.direction().scale(512)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK) { return Optional.empty(); }
            BlockPos feet = hit.getBlockPos().above();
            if (!minecraft.level.hasChunkAt(feet) || !Footing.withFeetAt(minecraft.level, feet)
                || !minecraft.level.getFluidState(feet).isEmpty() || !minecraft.level.getFluidState(feet.above()).isEmpty()) {
                return Optional.empty();
            }
            WorldPos point = WorldPos.of(minecraft.level, feet);
            return context != null && point.realm().equals(context.realm()) ? Optional.of(point) : Optional.empty();
        });
    }

    private int nearWaypoint(double x, double y, List<WorldPos> points, boolean open) {
        if (!open || points.size() < 2) { return -1; }
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection projection = new CombatProjection(minecraft);
        double nearest = CLOSE_RADIUS;
        int found = -1;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        for (int index = 0; index < points.size(); index++) {
            Optional<CombatProjection.Pixel> pixel = WorldSpaces.world(minecraft.level, points.get(index))
                .flatMap(world -> projection.point(world.add(0, .12, 0)));
            if (pixel.isPresent()) {
                if (pixel.get().x() < -7 || pixel.get().x() > width + 7
                        || pixel.get().y() < -7 || pixel.get().y() > height + 7) { continue; }
                double distance = Math.hypot(pixel.get().x() - x, pixel.get().y() - y);
                if (distance <= nearest) { nearest = distance; found = index; }
            }
        }
        return found;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection projection = new CombatProjection(minecraft);
        if (dragging) {
            CombatProjection.Rect area = CombatProjection.Rect.between(startX, startY, endX, endY);
            graphics.fill((int) area.left(), (int) area.top(), (int) area.right(), (int) area.bottom(), 0x2849D2A6);
            outline(graphics, area, SELECTED);
        }
        drawSelectedHealth(graphics, projection, partialTick);
        drawRoute(graphics, projection, mouseX, mouseY, partialTick);
        if (moveMarker != null && Util.getMillis() <= markerUntil) {
            WorldSpaces.world(minecraft.level, moveMarker).flatMap(projection::point).ifPresent(pixel ->
                {
                    if (pixel.x() >= -6 && pixel.x() <= minecraft.getWindow().getGuiScaledWidth() + 6
                            && pixel.y() >= -6 && pixel.y() <= minecraft.getWindow().getGuiScaledHeight() + 6) {
                        outline(graphics, new CombatProjection.Rect(pixel.x() - 5, pixel.y() - 5,
                            pixel.x() + 5, pixel.y() + 5), SELECTED);
                    }
                });
        }
        var state = CombatPackets.state();
        boolean routeFailure = state.phase().contains("failed") || switch (state.result()) {
            case "failed", "partial_failure", "no_survivors", "no_safe_anchor", "regroup_timed_out",
                "route_no_path", "route_member_blocked", "route_timed_out", "timed_out", "authority_lost", "out_of_range" -> true;
            default -> false;
        };
        Component status = !routeFailure && Util.getMillis() <= noticeUntil ? notice : !rosterReady()
            ? Component.translatable("folkways.war.syncing") : Component.empty();
        cards.render(graphics, mouseX, mouseY, selected, residents, shortcuts, route.size(),
            routeStatus(), status, attackMoveArmed, routeFailure);
    }

    private void drawSelectedHealth(GuiGraphics graphics, CombatProjection projection, float partialTick) {
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        for (UUID id : selected) {
            ResidentEntity resident = residents.get(id);
            if (resident == null) { continue; }
            Vec3 head = resident.getPosition(partialTick).add(0, resident.getBbHeight() + 0.18, 0);
            Optional<CombatProjection.Pixel> projected = projection.point(head);
            if (projected.isEmpty()) { continue; }
            int centerX = Mth.floor(projected.get().x()), y = Mth.floor(projected.get().y());
            if (centerX < -12 || centerX > screenWidth + 12 || y < -6 || y > screenHeight + 6) { continue; }
            int left = centerX - 9;
            int fill = Math.round(18 * Mth.clamp(resident.getHealth() / Math.max(1, resident.getMaxHealth()), 0, 1));
            int color = resident.getHealth() / Math.max(1, resident.getMaxHealth()) > .5F ? SELECTED : 0xFFFF776E;
            graphics.fill(left - 1, y - 1, left + 19, y + 4, 0xB0000000);
            graphics.fill(left, y, left + 18, y + 3, 0xFF592F2D);
            graphics.fill(left, y, left + fill, y + 3, color);
        }
    }

    private void drawRoute(GuiGraphics graphics, CombatProjection projection, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (route.isEmpty()) { return; }
        var state = CombatPackets.state();
        boolean matchingCommand = routeCommandId != null && routeCommandId.equals(state.commandId());
        boolean complete = matchingCommand && state.routeComplete();
        boolean reviewing = Screen.hasControlDown() && pendingReplacement == null;
        if (complete && !reviewing) { return; }
        int currentStop = matchingCommand && state.stop() >= 0
            ? Mth.clamp(state.stop(), 0, route.size() - 1) : 0;
        int firstStop = reviewing ? 0 : currentStop;
        int[] sampleBudget = {MAX_ROUTE_SAMPLES};
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        CombatProjection.Pixel squadCenter = selectedCenter(projection, partialTick).orElse(null);
        CombatProjection.Pixel previous = reviewing ? null : squadCenter;
        int closing = reviewing
            ? nearWaypoint(mouseX, mouseY, route, routeOpen) : -1;
        if (!reviewing && previous == null) {
            previous = WorldSpaces.world(minecraft.level, route.get(currentStop))
                .flatMap(world -> projection.point(world.add(0, 0.12, 0))).orElse(null);
        }
        if (reviewing && !complete && squadCenter != null) {
            WorldSpaces.world(minecraft.level, route.get(currentStop)).flatMap(world -> projection.point(world.add(0, 0.12, 0)))
                .ifPresent(target -> segment(graphics, squadCenter.x(), squadCenter.y(), target.x(), target.y(), SELECTED, sampleBudget));
        }
        int ghost = 0x638FEAC3;
        for (int index = firstStop; index < route.size(); index++) {
            Optional<Vec3> world = WorldSpaces.world(minecraft.level, route.get(index));
            Optional<CombatProjection.Pixel> point = world.flatMap(at -> projection.point(at.add(0, 0.12, 0)));
            if (point.isEmpty()) { previous = null; continue; }
            CombatProjection.Pixel at = point.get();
            boolean oldSegment = reviewing && (complete || index < currentStop);
            int pathColor = oldSegment ? ghost : SELECTED;
            if (previous != null) { segment(graphics, previous.x(), previous.y(), at.x(), at.y(), pathColor, sampleBudget); }
            if (at.x() >= -7 && at.x() <= screenWidth + 7 && at.y() >= -7 && at.y() <= screenHeight + 7) {
                int radius = 6;
                int color = closing == index && index < route.size() - 1 ? DRAFT
                    : reviewing && (complete || index < currentStop) ? ghost : SELECTED;
                outline(graphics, new CombatProjection.Rect(at.x() - radius, at.y() - radius,
                    at.x() + radius, at.y() + radius), color);
                graphics.drawString(minecraft.font, Integer.toString(index + 1), (int) at.x() + 6,
                    (int) at.y() - 4, color, true);
            }
            previous = at;
        }
        if (previous != null && cycleStart >= 0 && cycleStart < route.size()) {
            CombatProjection.Pixel last = previous;
            WorldSpaces.world(minecraft.level, route.get(cycleStart)).flatMap(world -> projection.point(world.add(0, .12, 0)))
                .ifPresent(first -> segment(graphics, last.x(), last.y(), first.x(), first.y(),
                    reviewing && complete ? ghost : SELECTED, sampleBudget));
            for (int index = cycleStart + 1; index <= currentStop && index < route.size(); index++) {
                int nextIndex = index;
                WorldSpaces.world(minecraft.level, route.get(nextIndex - 1)).flatMap(world -> projection.point(world.add(0, .12, 0)))
                    .ifPresent(from -> WorldSpaces.world(minecraft.level, route.get(nextIndex)).flatMap(world -> projection.point(world.add(0, .12, 0)))
                        .ifPresent(to -> segment(graphics, from.x(), from.y(), to.x(), to.y(), SELECTED, sampleBudget)));
            }
        }
    }

    private Optional<CombatProjection.Pixel> selectedCenter(CombatProjection projection, float partialTick) {
        Vec3 center = Vec3.ZERO;
        int count = 0;
        for (UUID id : selected) {
            ResidentEntity resident = residents.get(id);
            if (resident == null) { continue; }
            center = center.add(resident.getPosition(partialTick));
            count++;
        }
        return count == 0 ? Optional.empty()
            : projection.point(center.scale(1.0 / count).add(0, 0.12, 0));
    }

    private static void outline(GuiGraphics graphics, CombatProjection.Rect rect, int color) {
        int left = (int) rect.left(), top = (int) rect.top(), right = (int) Math.ceil(rect.right()), bottom = (int) Math.ceil(rect.bottom());
        graphics.fill(left, top, right + 1, top + 1, color); graphics.fill(left, bottom, right + 1, bottom + 1, color);
        graphics.fill(left, top, left + 1, bottom + 1, color); graphics.fill(right, top, right + 1, bottom + 1, color);
    }

    private static void segment(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color, int[] sampleBudget) {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        double[] clipped = clipSegment(x1, y1, x2, y2, width, height);
        if (clipped == null || sampleBudget[0] <= 0) { return; }
        x1 = clipped[0]; y1 = clipped[1]; x2 = clipped[2]; y2 = clipped[3];
        int steps = Math.min(64, Math.max(1, (int) Math.ceil(Math.hypot(x2 - x1, y2 - y1) / 3)));
        steps = Math.min(steps, sampleBudget[0]);
        for (int step = 0; step <= steps && sampleBudget[0] > 0; step++, sampleBudget[0]--) {
            double t = step / (double) steps;
            int x = (int) Mth.lerp(t, x1, x2), y = (int) Mth.lerp(t, y1, y2);
            graphics.fill(x, y, x + 2, y + 2, color);
        }
    }

    /** Liang-Barsky clip: invisible off-screen legs consume no pixel budget. */
    private static double[] clipSegment(double x1, double y1, double x2, double y2, int width, int height) {
        double dx = x2 - x1, dy = y2 - y1;
        double[] t = {0.0, 1.0};
        if (!clip(-dx, x1, t) || !clip(dx, width - 1.0 - x1, t)
                || !clip(-dy, y1, t) || !clip(dy, height - 1.0 - y1, t)) { return null; }
        return new double[] {x1 + t[0] * dx, y1 + t[0] * dy, x1 + t[1] * dx, y1 + t[1] * dy};
    }

    private static boolean clip(double p, double q, double[] t) {
        if (p == 0.0) { return q >= 0.0; }
        double ratio = q / p;
        if (p < 0.0) {
            if (ratio > t[1]) { return false; }
            if (ratio > t[0]) { t[0] = ratio; }
        } else {
            if (ratio < t[0]) { return false; }
            if (ratio < t[1]) { t[1] = ratio; }
        }
        return true;
    }

    private void tell(String key, Object... args) { notice = Component.translatable(key, args); noticeUntil = Util.getMillis() + 5000; }

    private void disarmAttackMove() {
        attackMoveArmed = false;
        attackMoveArmGeneration++;
    }

    private void cancelPendingRoute() { pendingReplacement = null; }

    private Component routeStatus() {
        var state = CombatPackets.state();
        Component command = Component.translatable("folkways.war.command." + state.command());
        Component phase = Component.translatable("folkways.war.phase." + state.phase());
        Component summary;
        if ("none".equals(state.result())) {
            summary = Component.translatable("folkways.war.status", command, phase);
        } else {
            Component result = Component.translatable("folkways.war.result." + state.result());
            summary = Component.translatable("folkways.war.status_result", command, phase, result);
        }
        if (state.waiting() > 0 && ("waiting_for_member".equals(state.phase())
                || "reforming".equals(state.phase()) || "failed_waiting".equals(state.phase()))) {
            return Component.translatable("folkways.war.status_waiting", summary, state.waiting());
        }
        return summary;
    }

    @Override
    public void cancel() {
        dragging = false;
        disarmAttackMove();
        clearRoute();
        moveMarker = null;
        markerUntil = 0;
        notice = Component.empty();
        noticeUntil = 0;
        requestIn = 0;
        if (Minecraft.getInstance().getConnection() != null) { CombatPackets.suspendControl(); }
    }

    @Override
    public void cancelPointer() { dragging = false; }

    private void clearRoute() {
        route.clear(); cycleStart = -1; routeOpen = false; routeAttackMove = false; routeCommandId = null;
        cancelPendingRoute();
        CombatPackets.clearRoute();
    }

    void reset() {
        if (Minecraft.getInstance().getConnection() != null) { CombatPackets.releaseControl(); }
        dragging = false;
        clearRoute(); selected.clear(); shortcuts.clear(); residents.clear(); cards.reset(); context = null;
        requestIn = 0; heartbeatIn = 0; controlRevision = -1; routeRevision = -1;
        CombatPackets.clearRoster();
        moveMarker = null; markerUntil = 0;
        disarmAttackMove(); routeAttackMove = false;
        notice = Component.empty(); noticeUntil = 0;
    }
}
