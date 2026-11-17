package io.github.izakyl.folkways.core.rts.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.front.api.ui.Cue;
import io.github.izakyl.folkways.core.front.api.ui.ModeOverlays;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.client.Overhead;
import io.github.izakyl.folkways.core.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.rts.combat.net.ClientUnits;
import io.github.izakyl.folkways.core.rts.combat.net.CombatControlPacket;
import io.github.izakyl.folkways.core.rts.combat.net.CombatPackets;
import io.github.izakyl.folkways.core.rts.combat.net.CombatWaypointReply;
import io.github.izakyl.folkways.core.rts.combat.net.UnitSnapshot;
import io.github.izakyl.folkways.core.rts.combat.ui.CombatContent;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Pointer feedback is local; selection, shortcut membership and every waypoint are server-authoritative. */
final class CombatController implements OverheadTools.Handler, UnitWindow.Host {
    private static final int SELECTED = 0xFF8FEAC3;
    private static final int DRAFT = 0xFFFFDC7A;
    private static final int CLOSE_RADIUS = 12;
    private static final int MAX_ROUTE_SAMPLES = 512;
    private static final long FAILURE_DURATION_MS = 3000;
    private final CombatCards cards = new CombatCards();
    private final LinkedHashSet<UUID> selected = new LinkedHashSet<>();
    // The server selection contains both resident and vehicle UUIDs.
    private final LinkedHashSet<UUID> vehicles = new LinkedHashSet<>();
    private final UnitWindow window = new UnitWindow(this);
    private static final Map<UUID, Entity> SEEN = new HashMap<>();
    private int unitsIn;
    private final Map<Integer, List<UUID>> shortcuts = new HashMap<>();
    private final Map<UUID, LivingEntity> residents = new LinkedHashMap<>();
    private final List<WorldPos> route = new ArrayList<>();
    private Context context;
    private int requestIn;
    private int heartbeatIn;
    private long controlRevision = -1, routeRevision = -1;
    private int cycleStart = -1;
    private boolean routeOpen;
    private boolean routeAttackMove;
    private UUID routeCommandId;
    // What a plain right-click on ground does, set by right-clicking the card's Move or Attack button.
    private boolean attackMoveMode;
    private Command armed = Command.NONE;
    private PendingRoute pendingReplacement;
    private boolean dragging;
    private boolean additiveSelection;
    private double startX, startY, endX, endY;
    private WorldPos moveMarker;
    private long markerUntil;
    private Component failureMessage;
    private long failureUntil;

    private record Context(UUID player, UUID colony, Realm realm) {
    }

    /** A command button waiting for its target: the next left click in the world completes it. */
    enum Command {
        NONE,
        MOVE,
        ATTACK,
        BOARD,
        LOITER
    }

    /** Input-only route context while a replacement is waiting for its authoritative reply. */
    private static final class PendingRoute {
        final boolean attackMove;
        final CombatWaypointReply.Intent initialIntent;
        final List<WorldPos> points = new ArrayList<>();
        final ArrayDeque<WorldPos> additions = new ArrayDeque<>();
        int cycleStart = -1;
        long initialRequest;
        long additionRequest;
        boolean awaitingInitialAck = true;
        boolean awaitingAdditionAck;

        PendingRoute(
            WorldPos first,
            boolean attackMove,
            CombatWaypointReply.Intent initialIntent,
            long initialRequest) {
            this.points.add(first);
            this.attackMove = attackMove;
            this.initialIntent = initialIntent;
            this.initialRequest = initialRequest;
        }
    }

    private boolean active() {
        return Overhead.activeScreen()
            && OverheadTools.selected().filter(tool -> tool.id().equals(CombatContent.ID)).isPresent();
    }

    void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            reset();
            return;
        }
        Context now =
            CurrentColony.id(minecraft.player)
            .map(
            colony ->
                new Context(
                    minecraft.player.getUUID(),
                    colony,
                    WorldSpaces.at(minecraft.player).realm()))
            .orElse(null);
        if (!Objects.equals(context, now)) {
            reset();
            context = now;
        }
        if (context == null) {
            return;
        }
        synchronizeControl();
        boolean retained = !selected.isEmpty() || !vehicles.isEmpty() || shortcuts.values().stream().anyMatch(group -> !group.isEmpty());
        if (!active()) {
            if (retained && heartbeatIn-- <= 0) {
                CombatPackets.heartbeat();
                heartbeatIn = 40;
            }
            refreshResidents(minecraft);
            window.tick();
            return;
        }
        if (requestIn-- <= 0) {
            CombatPackets.requestRoster();
            requestIn = 20;
        }
        if (unitsIn-- <= 0) {
            ClientUnits.refresh(context.colony());
            unitsIn = 20;
        }
        see(minecraft);
        window.tick();
        if (retained && heartbeatIn-- <= 0) {
            CombatPackets.heartbeat();
            heartbeatIn = 40;
        }
        refreshResidents(minecraft);
        synchronizeRoute();
    }

    private void refreshResidents(Minecraft minecraft) {
        residents.clear();
        if (!rosterReady()) {
            return;
        }
        Set<UUID> roster = CombatPackets.roster();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity resident
                && resident.isAlive()
                && !resident.isRemoved()
                && roster.contains(resident.getUUID())) {
                residents.put(resident.getUUID(), resident);
                SEEN.putIfAbsent(resident.getUUID(), resident);
            }
        }
    }

    /** Client bodies of every listed unit, crew member and squad member, for faces and world picking. */
    private void see(Minecraft minecraft) {
        SEEN.clear();
        Set<UUID> wanted = new HashSet<>();
        for (UnitSnapshot.Unit unit : ClientUnits.units()) {
            wanted.add(unit.id());
            wanted.addAll(unit.members());
            unit.seats().forEach(seat -> seat.occupant().ifPresent(wanted::add));
        }
        if (!wanted.isEmpty()) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (wanted.contains(entity.getUUID()) && entity.isAlive() && !entity.isRemoved()) {
                    SEEN.put(entity.getUUID(), entity);
                }
            }
        }
        if (ClientUnits.snapshot().isPresent()) {
            vehicles.removeIf(id -> unit(id).filter(unit -> unit.kind() == UnitSnapshot.Kind.VEHICLE).isEmpty());
        }
    }

    static Entity seen(UUID id) {
        return SEEN.get(id);
    }

    /** Whether the client already shows this body riding that vehicle, ahead of the next unit snapshot. */
    static boolean riding(UUID resident, UUID vehicle) {
        Entity body = SEEN.get(resident);
        return body != null && body.getVehicle() != null && body.getVehicle().getUUID().equals(vehicle);
    }

    static Map<UUID, UUID> aboard(Set<UUID> residents) {
        Map<UUID, UUID> result = new HashMap<>();
        for (UUID id : residents) {
            Entity body = SEEN.get(id);
            if (body != null && body.getVehicle() != null) {
                result.put(id, body.getVehicle().getUUID());
            }
        }
        return result;
    }

    private static Optional<UnitSnapshot.Unit> unit(UUID id) {
        return ClientUnits.unit(id);
    }

    @Override
    public Optional<UnitSnapshot.Unit> focus() {
        if (!active() || context == null) {
            return Optional.empty();
        }
        if (vehicles.size() == 1 && selected.isEmpty()) {
            return unit(vehicles.iterator().next());
        }
        if (vehicles.isEmpty() && !selected.isEmpty()) {
            return ClientUnits.units().stream()
                .filter(unit -> unit.kind() == UnitSnapshot.Kind.SQUAD && unit.members().containsAll(selected))
                .findFirst();
        }
        return Optional.empty();
    }

    @Override
    public void failed(Component reason) {
        if (active()) {
            failureMessage = reason;
            failureUntil = Util.getMillis() + FAILURE_DURATION_MS;
        }
    }

    @Override
    public void selectResidents(Set<UUID> residents) {
        if (ready()) {
            vehicles.clear();
            replaceSelection(residents);
        }
    }

    @Override
    public Set<UUID> selectedResidents() {
        // The card belongs to the RTS tool: another book tool keeps the selection but not its card.
        return active() ? Set.copyOf(selected) : Set.of();
    }

    @Override
    public Set<UUID> selectedVehicles() {
        return active() ? Set.copyOf(vehicles) : Set.of();
    }

    @Override
    public void selectVehicle(UUID vehicle) {
        if (ready()) {
            replaceSelection(Set.of());
            selectVehicles(Set.of(vehicle), false);
        }
    }

    @Override
    public Command armed() {
        return armed;
    }

    @Override
    public void arm(Command command) {
        armed = command;
    }

    @Override
    public boolean attackMoveDefault() {
        return attackMoveMode;
    }

    @Override
    public void attackMoveDefault(boolean attack) {
        attackMoveMode = attack;
    }

    @Override
    public void stop() {
        command(CombatKeys.STOP);
    }

    UnitWindow window() {
        return window;
    }

    private boolean listedVehicle(Entity entity) {
        return unit(entity.getUUID()).filter(unit -> unit.kind() == UnitSnapshot.Kind.VEHICLE).isPresent();
    }

    /** Vehicles the colony crews or has reserved seats in: the ones box selection and the unit strip offer. */
    private boolean ownVehicle(UnitSnapshot.Unit unit) {
        return unit.kind() == UnitSnapshot.Kind.VEHICLE && (context != null
            && unit.owner().filter(context.colony()::equals).isPresent()
            || unit.seats().stream().anyMatch(seat -> seat.occupant().isPresent()));
    }

    /** The listed vehicle under the pointer, nearest first; blocks in front hide it. */
    private Optional<UUID> vehicleAt(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        return Overhead.screenRay(x, y).flatMap(ray -> {
            Vec3 end = ray.origin().add(ray.direction().scale(512));
            BlockHitResult obstruction = minecraft.level.clip(new ClipContext(ray.origin(), end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
            double nearest = obstruction.getLocation().distanceToSqr(ray.origin()) + 4;
            UUID found = null;
            for (UnitSnapshot.Unit unit : ClientUnits.units()) {
                Entity entity = SEEN.get(unit.id());
                if (unit.kind() != UnitSnapshot.Kind.VEHICLE || entity == null) {
                    continue;
                }
                Optional<Vec3> hit = entity.getBoundingBox().inflate(.15).clip(ray.origin(), end);
                if (hit.isPresent() && hit.get().distanceToSqr(ray.origin()) < nearest) {
                    nearest = hit.get().distanceToSqr(ray.origin());
                    found = unit.id();
                }
            }
            return Optional.ofNullable(found);
        });
    }

    private void selectVehicles(Set<UUID> next, boolean additive) {
        if (!additive) {
            vehicles.clear();
        }
        for (UUID id : next) {
            if (additive && next.size() == 1 && vehicles.contains(id)) {
                vehicles.remove(id);
            } else {
                vehicles.add(id);
            }
        }
        replaceSelection(Set.copyOf(selected));
    }

    private void board(UUID vehicle) {
        if (ClientUnits.snapshot().isEmpty()) {
            fail("folkways.rts.syncing");
            return;
        }
        window.track(ClientUnits.board(vehicle, List.copyOf(selected)), vehicle);
        // Seated residents leave the RTS roster as crew; follow them into the vehicle's window.
        replaceSelection(Set.of());
        selectVehicles(Set.of(vehicle), false);
    }

    private void commandVehicles(double x, double y, boolean targets) {
        if (ClientUnits.snapshot().isEmpty()) {
            fail("folkways.rts.syncing");
            return;
        }
        if (targets && !Screen.hasControlDown()) {
            Optional<Entity> target = target(x, y);
            if (target.isPresent()) {
                for (UUID vehicle : vehicles) {
                    window.track(ClientUnits.order(vehicle, new Vehicles.Order(Vehicles.Action.ATTACK, List.of(), -1,
                        Optional.of(target.get().getUUID()), Optional.empty())), vehicle);
                }
                return;
            }
        }
        Optional<WorldPos> point = ground(x, y);
        if (point.isEmpty()) {
            fail("folkways.rts.ground_required");
            return;
        }
        Vehicles.Action action = attackMoveMode ? Vehicles.Action.ATTACK_MOVE : Vehicles.Action.MOVE;
        for (UUID vehicle : vehicles) {
            if (Screen.hasControlDown()) {
                var status = unit(vehicle).map(UnitSnapshot.Unit::status);
                int closing = status.map(value -> nearWaypoint(x, y, value.route().points(),
                    value.route().cycleStart() < 0)).orElse(-1);
                if (closing >= 0 && status.orElseThrow().route().points().size() > closing + 1) {
                    window.track(ClientUnits.cycle(vehicle, closing), vehicle);
                } else {
                    window.track(ClientUnits.appendPoint(vehicle, point.get(), attackMoveMode), vehicle);
                }
            } else {
                // Fixed wing cannot stop over a point: sent there, it circles it.
                boolean fixedWing = unit(vehicle).filter(found -> found.capabilities().airframe()
                    == Vehicles.Airframe.FIXED_WING && found.capabilities().actions().contains(Vehicles.Action.LOITER))
                    .isPresent();
                window.track(ClientUnits.order(vehicle, new Vehicles.Order(fixedWing ? Vehicles.Action.LOITER : action,
                    List.of(point.get()), -1, Optional.empty(), Optional.empty())), vehicle);
            }
        }
        moveMarker = point.get();
        markerUntil = Util.getMillis() + 3000;
    }

    private void synchronizeControl() {
        if (CombatPackets.controlPending() || controlRevision == CombatPackets.controlRevision()) {
            return;
        }
        CombatPackets.controls()
            .filter(
            state ->
                state.colonyId().equals(context.colony()) && state.at().realm().equals(context.realm()))
            .ifPresent(
            state -> {
                boolean userOperation = switch (state.operation()) {
                    case SELECT, BIND, RECALL, RELEASE -> true;
                    default -> false;
                };
                if (!state.accepted() && userOperation) {
                    fail("folkways.combat.error.rejected");
                }
                Set<UUID> nextVehicles = state.selection().stream()
                    .filter(id -> unit(id).filter(value -> value.kind() == UnitSnapshot.Kind.VEHICLE).isPresent())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                vehicles.clear();
                vehicles.addAll(nextVehicles);
                Set<UUID> next = new LinkedHashSet<>(state.selection());
                next.removeAll(nextVehicles);
                boolean selectionChanged = !selected.equals(next);
                if (selectionChanged) {
                    clearRoute();
                    cards.reset();
                }
                selected.clear();
                selected.addAll(next);
                shortcuts.clear();
                for (int slot = 1; slot <= 9; slot++) {
                    shortcuts.put(slot, state.groups().get(slot - 1));
                }
                boolean stableHeartbeat =
                    state.accepted()
                    && state.operation() == CombatControlPacket.Operation.HEARTBEAT
                    && pendingReplacement == null
                    && !CombatPackets.routePending();
                boolean routeSnapshot =
                    selectionChanged
                    || state.accepted()
                    && (state.operation() == CombatControlPacket.Operation.SELECT
                    || state.operation() == CombatControlPacket.Operation.RECALL
                    || state.operation()
                    == CombatControlPacket.Operation.RELEASE
                    || state.operation()
                    == CombatControlPacket.Operation.SUSPEND)
                    || stableHeartbeat;
                if (routeSnapshot) {
                    route.clear();
                    route.addAll(state.points());
                    cycleStart = state.cycleStart();
                    routeOpen = state.open();
                    routeAttackMove = state.attackMove();
                    routeCommandId = state.state().commandId();
                }
            });
        controlRevision = CombatPackets.controlRevision();
    }

    private void synchronizeRoute() {
        if (routeRevision == CombatPackets.routeRevision()) {
            return;
        }
        CombatPackets.route()
            .filter(
            state ->
                state.colonyId().equals(context.colony())
                    && state.at().realm().equals(context.realm())
                    && Set.copyOf(state.residents()).equals(selected))
            .ifPresent(
            state -> {
                route.clear();
                route.addAll(state.points());
                cycleStart = state.cycleStart();
                routeOpen = state.open();
                routeAttackMove = state.state().attackMove();
                routeCommandId = state.state().commandId();
                resolvePendingRoute(state);
                if (!state.accepted()) {
                    moveMarker = null;
                    fail("folkways.combat.error.rejected");
                }
            });
        routeRevision = CombatPackets.routeRevision();
    }

    private void resolvePendingRoute(CombatWaypointReply reply) {
        PendingRoute pending = pendingReplacement;
        if (pending == null) {
            return;
        }
        if (pending.awaitingInitialAck
            && reply.request() == pending.initialRequest
            && reply.intent() == pending.initialIntent) {
            pending.awaitingInitialAck = false;
            if (!reply.accepted()) {
                pendingReplacement = null;
                return;
            }
            sendNextPendingAddition(pending);
            return;
        }
        if (pending.awaitingAdditionAck
            && reply.request() == pending.additionRequest
            && reply.intent() == CombatWaypointReply.Intent.WAYPOINT) {
            pending.awaitingAdditionAck = false;
            if (!reply.accepted()) {
                pendingReplacement = null;
                return;
            }
            if (reply.cycleStart() >= 0) {
                pending.cycleStart = reply.cycleStart();
            }
            pending.additions.removeFirst();
            sendNextPendingAddition(pending);
        }
    }

    private void sendNextPendingAddition(PendingRoute pending) {
        if (pendingReplacement != pending || pending.awaitingInitialAck || pending.awaitingAdditionAck) {
            return;
        }
        if (pending.additions.isEmpty()) {
            pendingReplacement = null;
            return;
        }
        WorldPos point = pending.additions.peekFirst();
        long request = sendRouteRequest(() -> CombatPackets.append(List.copyOf(selected), point, pending.attackMove));
        if (request < 0) {
            pendingReplacement = null;
            return;
        }
        pending.additionRequest = request;
        pending.awaitingAdditionAck = true;
    }

    private long sendRouteRequest(BooleanSupplier submit) {
        if (!submit.getAsBoolean()) {
            fail("folkways.rts.syncing");
            return -1;
        }
        return CombatPackets.lastRouteRequest();
    }

    private boolean rosterReady() {
        return context != null
            && CombatPackets.rosterColony().filter(context.colony()::equals).isPresent()
            && CombatPackets.rosterAt().filter(at -> at.realm().equals(context.realm())).isPresent();
    }

    @Override
    public List<Cue> cues() {
        if (armed != Command.NONE) {
            return List.of(
                Cue.of("folkways.rts.armed." + armed.name().toLowerCase(java.util.Locale.ROOT), Cue.Key.CLICK),
                Cue.of("folkways.rts.armed.cancel", Cue.Key.ALT_CLICK));
        }
        return List.of(
            Cue.of("folkways.keys.combat.select", Cue.Key.CLICK),
            Cue.of("folkways.rts.move_options", Cue.Key.ALT_CLICK),
            Cue.of("folkways.keys.combat.route", Cue.Key.MODIFIER, Cue.Key.ALT_CLICK),
            Cue.bound("folkways.rts.release_control.tooltip", CombatKeys.RELEASE.getName()));
    }

    @Override
    public boolean compactHints() {
        return true;
    }

    @Override
    public boolean showResidentCard(UUID resident) {
        return !selected.contains(resident);
    }

    @Override
    public int bottomInset() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight() - cards.visibleTop();
    }

    @Override
    public boolean blocksCameraDescent() {
        return dragging;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (unmodified()) {
            for (KeyMapping mapping : CombatKeys.ALL) {
                if (mapping.matchesMouse(button) && mapping.isConflictContextAndModifierActive()) {
                    command(mapping);
                    return true;
                }
            }
        }
        if (cards.covers(x, y)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                int slot = cards.clicked(x, y);
                if (slot > 0 && ready()) {
                    shortcut(slot, Screen.hasControlDown());
                }
            }
            return true;
        }
        if (armed != Command.NONE) {
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                armed = Command.NONE;
                return true;
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                Command command = armed;
                // Shift keeps the command armed for another target, as it queues in other RTS games.
                if (!Screen.hasShiftDown()) {
                    armed = Command.NONE;
                }
                if (ready()) {
                    execute(command, x, y);
                }
                return true;
            }
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            additiveSelection = Screen.hasShiftDown();
            dragging = true;
            startX = endX = x;
            startY = endY = y;
            return true;
        }
        if (button != InputConstants.MOUSE_BUTTON_RIGHT) {
            return false;
        }
        if (dragging) {
            dragging = false;
            return true;
        }
        // Always consume RMB here: even an invalid command must not fall through to opening the
        // book.
        if (!ready()) {
            return true;
        }
        order(x, y, null, true);
        return true;
    }

    private void execute(Command command, double x, double y) {
        switch (command) {
            case MOVE -> order(x, y, false, false);
            case ATTACK -> order(x, y, true, false);
            case BOARD -> {
                if (selected.isEmpty()) {
                    fail("folkways.rts.select_first");
                    return;
                }
                vehicleAt(x, y).ifPresentOrElse(this::board, () -> fail("folkways.rts.board_pick"));
            }
            case LOITER -> loiter(x, y);
            case NONE -> {
            }
        }
    }

    private void loiter(double x, double y) {
        Optional<WorldPos> point = ground(x, y);
        if (point.isEmpty()) {
            fail("folkways.rts.ground_required");
            return;
        }
        for (UUID vehicle : vehicles) {
            if (unit(vehicle).filter(found -> found.capabilities().actions().contains(Vehicles.Action.LOITER))
                .isPresent()) {
                window.track(ClientUnits.order(vehicle, new Vehicles.Order(Vehicles.Action.LOITER,
                    List.of(point.get()), -1, Optional.empty(), Optional.empty())), vehicle);
            }
        }
        moveMarker = point.get();
        markerUntil = Util.getMillis() + 3000;
    }

    /**
     * Issues an order at a screen point. A plain right-click is smart: a vehicle boards, a hostile is attacked,
     * ground is moved to. The Move button only moves; the Attack button attacks a target or attack-moves to ground.
     */
    private void order(double x, double y, Boolean forced, boolean smart) {
        boolean persistent = attackMoveMode;
        if (forced != null) {
            attackMoveMode = forced;
        }
        try {
            order(x, y, smart);
        } finally {
            attackMoveMode = persistent;
        }
    }

    private void order(double x, double y, boolean smart) {
        boolean attack = attackMoveMode;
        if (selected.isEmpty() && vehicles.isEmpty()) {
            fail("folkways.rts.select_first");
            return;
        }
        if (smart && !selected.isEmpty() && !Screen.hasControlDown()) {
            Optional<UUID> ride = vehicleAt(x, y);
            if (ride.isPresent()) {
                board(ride.get());
                return;
            }
        }
        if (!vehicles.isEmpty()) {
            commandVehicles(x, y, smart || attack);
            if (selected.isEmpty()) {
                return;
            }
        }
        PendingRoute pending = pendingReplacement;
        List<WorldPos> inputRoute = pending == null ? route : pending.points;
        int inputCycleStart = pending == null ? cycleStart : pending.cycleStart;
        boolean inputAttackMove = pending == null ? routeAttackMove : pending.attackMove;
        if (Screen.hasControlDown() && !inputRoute.isEmpty() && attackMoveMode != inputAttackMove) {
            fail("folkways.rts.route_mode_mismatch");
            return;
        }
        if (Screen.hasControlDown() && inputCycleStart >= 0) {
            fail("folkways.rts.route_closed");
            return;
        }
        int closing = Screen.hasControlDown() && pending == null ? nearWaypoint(x, y, route, routeOpen) : -1;
        if (closing >= 0) {
            if (closing >= inputRoute.size() - 1) {
                fail("folkways.rts.cycle_too_short");
                return;
            }
            sendRouteRequest(
                () -> CombatPackets.append(List.copyOf(selected), inputRoute.get(closing), routeAttackMove));
            return;
        }
        if ((smart || attack) && !Screen.hasControlDown()) {
            Optional<Entity> target = target(x, y);
            if (target.isPresent()) {
                if (sendRouteRequest(() -> CombatPackets.attack(List.copyOf(selected), target.get().getUUID())) >= 0) {
                    cancelPendingRoute();
                }
                return;
            }
        }
        Optional<WorldPos> point = ground(x, y);
        if (point.isEmpty()) {
            fail("folkways.rts.ground_required");
            return;
        }
        if (Screen.hasControlDown()) {
            if (pending != null) {
                if (pending.cycleStart >= 0) {
                    fail("folkways.rts.route_closed");
                    return;
                }
                int existing = pending.points.indexOf(point.get());
                if (existing >= 0) {
                    if (existing >= pending.points.size() - 1) {
                        fail("folkways.rts.cycle_too_short");
                        return;
                    }
                    pending.cycleStart = existing;
                    pending.additions.addLast(point.get());
                    sendNextPendingAddition(pending);
                    return;
                }
                if (pending.points.size() >= CombatPackets.MAX_POINTS) {
                    fail("folkways.rts.route_full", CombatPackets.MAX_POINTS);
                    return;
                }
                pending.points.add(point.get());
                pending.additions.addLast(point.get());
                sendNextPendingAddition(pending);
                return;
            }
            if (route.size() >= CombatPackets.MAX_POINTS && !route.contains(point.get())) {
                fail("folkways.rts.route_full", CombatPackets.MAX_POINTS);
                return;
            }
            boolean mode = attackMoveMode;
            long request = sendRouteRequest(() -> CombatPackets.append(List.copyOf(selected), point.get(), mode));
            if (request >= 0 && route.isEmpty()) {
                pendingReplacement =
                    new PendingRoute(
                    point.get(),
                    mode,
                    CombatWaypointReply.Intent.WAYPOINT,
                    request);
            }
        } else {
            boolean mode = attackMoveMode;
            long request =
                sendRouteRequest(() -> CombatPackets.submit(List.copyOf(selected), List.of(point.get()), -1, mode));
            if (request >= 0) {
                pendingReplacement =
                    new PendingRoute(
                    point.get(), mode, CombatWaypointReply.Intent.MOVE, request);
                moveMarker = point.get();
                markerUntil = Util.getMillis() + 3000;
            }
        }
    }

    private boolean ready() {
        tick();
        if (!rosterReady()) {
            fail("folkways.rts.syncing");
            return false;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !dragging) {
            return false;
        }
        endX = x;
        endY = y;
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !dragging) {
            return button == InputConstants.MOUSE_BUTTON_RIGHT;
        }
        dragging = false;
        endX = x;
        endY = y;
        if (!ready()) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection projection = new CombatProjection(minecraft);
        Set<UUID> next = new LinkedHashSet<>();
        Set<UUID> nextVehicles = new LinkedHashSet<>();
        CombatProjection.Rect area = CombatProjection.Rect.between(startX, startY, endX, endY);
        boolean click = Math.hypot(endX - startX, endY - startY) < 4;
        if (click) {
            double nearest = Double.POSITIVE_INFINITY;
            UUID pointed = null;
            for (LivingEntity resident : residents.values()) {
                Optional<CombatProjection.Rect> bounds = projection.bounds(resident.getBoundingBox());
                if (bounds.isPresent() && bounds.get().contains(endX, endY)) {
                    double distance = resident.distanceToSqr(minecraft.gameRenderer.getMainCamera().getPosition());
                    if (distance < nearest) {
                        nearest = distance;
                        pointed = resident.getUUID();
                    }
                }
            }
            if (pointed != null) {
                next.add(pointed);
            } else {
                vehicleAt(endX, endY).ifPresent(nextVehicles::add);
            }
        } else {
            for (LivingEntity resident : residents.values()) {
                projection
                    .bounds(resident.getBoundingBox())
                    .filter(area::intersects)
                    .ifPresent(bounds -> next.add(resident.getUUID()));
            }
            for (UnitSnapshot.Unit unit : ClientUnits.units()) {
                Entity vehicle = SEEN.get(unit.id());
                if (vehicle != null && ownVehicle(unit)) {
                    projection.bounds(vehicle.getBoundingBox()).filter(area::intersects)
                        .ifPresent(bounds -> nextVehicles.add(unit.id()));
                }
            }
        }
        if (additiveSelection) {
            Set<UUID> combined = new LinkedHashSet<>(selected);
            if (Math.hypot(endX - startX, endY - startY) < 4
                && next.size() == 1
                && combined.contains(next.iterator().next())) {
                combined.remove(next.iterator().next());
            } else {
                combined.addAll(next);
            }
            replaceSelection(combined);
            if (click) {
                selectVehicles(nextVehicles, true);
            } else {
                vehicles.addAll(nextVehicles);
                replaceSelection(Set.copyOf(selected));
            }
        } else {
            replaceSelection(next);
            selectVehicles(nextVehicles, false);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (unmodified() && (modifiers & (GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL
            | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) == 0) {
            for (KeyMapping mapping : CombatKeys.ALL) {
                if (mapping.matches(keyCode, scanCode) && mapping.isConflictContextAndModifierActive()) {
                    command(mapping);
                    return true;
                }
            }
        }
        int slot =
            keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9
            ? keyCode - GLFW.GLFW_KEY_0
            : keyCode >= GLFW.GLFW_KEY_KP_1 && keyCode <= GLFW.GLFW_KEY_KP_9
            ? keyCode - GLFW.GLFW_KEY_KP_0
            : 0;
        if (slot > 0) {
            if (!ready()) {
                return true;
            }
            boolean control =
                (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                || Minecraft.ON_OSX && (modifiers & GLFW.GLFW_MOD_SUPER) != 0
                || Screen.hasControlDown();
            shortcut(slot, control);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && window.closePopup()) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && armed != Command.NONE) {
            armed = Command.NONE;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (!selected.isEmpty() || !vehicles.isEmpty())) {
            vehicles.clear();
            replaceSelection(Set.of());
            return true;
        }
        return false;
    }

    private static boolean unmodified() {
        return !Screen.hasShiftDown() && !Screen.hasControlDown() && !Screen.hasAltDown();
    }

    private void command(KeyMapping mapping) {
        dragging = false;
        if (!ready()) {
            return;
        }
        if (selected.isEmpty() && vehicles.isEmpty()) {
            fail("folkways.rts.select_first");
            return;
        }
        if (mapping != CombatKeys.STOP && mapping != CombatKeys.RELEASE) {
            // A command-card key presses its button, so the key and the click do exactly the same thing; a key
            // whose button the card does not show (a ground vehicle's Land) does nothing.
            CombatKeys.CARD.entrySet().stream().filter(entry -> entry.getValue() == mapping).findFirst()
                .ifPresent(entry -> window.press(entry.getKey()));
            return;
        }
        if (mapping == CombatKeys.STOP) {
            for (UUID vehicle : vehicles) {
                if (ClientUnits.snapshot().isPresent()) {
                    window.track(ClientUnits.order(vehicle, new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1,
                        Optional.empty(), Optional.empty())), vehicle);
                }
            }
            if (!selected.isEmpty() && sendRouteRequest(() -> CombatPackets.stop(List.copyOf(selected))) >= 0) {
                cancelPendingRoute();
            }
        } else {
            Set<UUID> all = new LinkedHashSet<>(selected);
            all.addAll(vehicles);
            if (!CombatPackets.release(List.copyOf(all))) {
                fail("folkways.rts.syncing");
            }
        }
    }

    private void replaceSelection(Set<UUID> next) {
        if (next.size() > CombatPackets.MAX_MEMBERS) {
            fail("folkways.rts.selection_limit", CombatPackets.MAX_MEMBERS);
            return;
        }
        Set<UUID> all = new LinkedHashSet<>(next);
        all.addAll(vehicles);
        if (all.size() > CombatPackets.MAX_MEMBERS || !CombatPackets.select(List.copyOf(all))) {
            fail("folkways.rts.syncing");
            return;
        }
        if (!selected.equals(next)) {
            clearRoute();
        }
        selected.clear();
        selected.addAll(next);
        cards.reset();
        heartbeatIn = 40;
    }

    private void shortcut(int slot, boolean bind) {
        boolean sent = bind ? CombatPackets.bind(slot) : CombatPackets.recall(slot);
        if (!sent) {
            fail("folkways.rts.syncing");
            return;
        }
        if (!bind) {
            clearRoute();
            vehicles.clear();
        }
        heartbeatIn = 40;
    }

    private Optional<Entity> target(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        return Overhead.screenRay(x, y)
            .flatMap(
            ray -> {
                Vec3 end = ray.origin().add(ray.direction().scale(512));
                BlockHitResult obstruction =
                    minecraft.level.clip(
                    new ClipContext(
                    ray.origin(),
                    end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    minecraft.player));
                double nearest = obstruction.getLocation().distanceToSqr(ray.origin());
                Entity aimed = null;
                for (Entity entity : minecraft.level.entitiesForRendering()) {
                    if (!(entity instanceof Mob) && Vehicles.of(entity).isEmpty()
                        || listedVehicle(entity)
                        || !entity.isAlive()
                        || entity.isRemoved()
                        || !entity.isPickable()
                        || Bodies.of(entity).isPresent()
                        || entity.isAlliedTo(minecraft.player)
                        || minecraft.player.isAlliedTo(entity)
                        || selected.stream()
                        .map(residents::get)
                        .filter(Objects::nonNull)
                        .anyMatch(friend -> entity.isAlliedTo(friend) || friend.isAlliedTo(entity))
                        || entity instanceof TamableAnimal pet && pet.isOwnedBy(minecraft.player)) {
                        continue;
                    }
                    Optional<Vec3> hit = entity.getBoundingBox().inflate(.15).clip(ray.origin(), end);
                    if (hit.isPresent() && hit.get().distanceToSqr(ray.origin()) < nearest) {
                        nearest = hit.get().distanceToSqr(ray.origin());
                        aimed = entity;
                    }
                }
                return Optional.ofNullable(aimed);
            });
    }

    private Optional<WorldPos> ground(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        return Overhead.screenRay(x, y)
            .flatMap(
            ray -> {
                BlockHitResult hit =
                    minecraft.level.clip(
                    new ClipContext(
                    ray.origin(),
                    ray.origin().add(ray.direction().scale(512)),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    minecraft.player));
                if (hit.getType() != HitResult.Type.BLOCK) {
                    return Optional.empty();
                }
                BlockPos feet = hit.getBlockPos().above();
                if (!minecraft.level.hasChunkAt(feet)
                    || !Footing.withFeetAt(minecraft.level, feet)
                    || !minecraft.level.getFluidState(feet).isEmpty()
                    || !minecraft.level.getFluidState(feet.above()).isEmpty()) {
                    return Optional.empty();
                }
                WorldPos point = WorldPos.of(minecraft.level, feet);
                return context != null && point.realm().equals(context.realm())
                    ? Optional.of(point)
                    : Optional.empty();
            });
    }

    private int nearWaypoint(double x, double y, List<WorldPos> points, boolean open) {
        if (y >= cards.visibleTop() || !open || points.size() < 2) {
            return -1;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection projection = new CombatProjection(minecraft);
        double nearest = CLOSE_RADIUS;
        int found = -1;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        for (int index = 0; index < points.size(); index++) {
            Optional<CombatProjection.Pixel> pixel =
                WorldSpaces.world(minecraft.level, points.get(index))
                .flatMap(world -> projection.point(world.add(0, .12, 0)));
            if (pixel.isPresent()) {
                if (pixel.get().x() < -7
                    || pixel.get().x() > width + 7
                    || pixel.get().y() < -7
                    || pixel.get().y() > height + 7) {
                    continue;
                }
                double distance = Math.hypot(pixel.get().x() - x, pixel.get().y() - y);
                if (distance <= nearest) {
                    nearest = distance;
                    found = index;
                }
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
        drawRoute(graphics, projection, mouseX, mouseY, partialTick);
        drawVehicleRoutes(graphics, projection, mouseX, mouseY);
        if (moveMarker != null && Util.getMillis() <= markerUntil) {
            WorldSpaces.world(minecraft.level, moveMarker)
                .flatMap(projection::point)
                .ifPresent(
                pixel -> {
                    if (pixel.x() >= -6
                        && pixel.x() <= minecraft.getWindow().getGuiScaledWidth() + 6
                        && pixel.y() >= -6
                        && pixel.y() <= minecraft.getWindow().getGuiScaledHeight() + 6) {
                        outline(
                            graphics,
                            new CombatProjection.Rect(
                            pixel.x() - 5, pixel.y() - 5, pixel.x() + 5, pixel.y() + 5),
                            SELECTED);
                    }
                });
        }
        cards.render(graphics, mouseX, mouseY, selected, residents, shortcuts, visibleFailure());
        if (armed != Command.NONE) {
            // The armed command rides the pointer until a click completes or cancels it.
            Component label = Component.translatable("folkways.rts.armed." + armed.name().toLowerCase(java.util.Locale.ROOT));
            int width = minecraft.font.width(label);
            graphics.fill(mouseX + 10, mouseY + 8, mouseX + 14 + width, mouseY + 20, 0xB0000000);
            graphics.drawString(minecraft.font, label, mouseX + 12, mouseY + 10,
                armed == Command.ATTACK ? 0xFFFF776E : 0xFFFFE29A, false);
        }
    }

    @Override
    public void renderOverlay(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || context == null
            || CurrentColony.id(minecraft.player).filter(context.colony()::equals).isEmpty()) {
            return;
        }
        CombatProjection projection = new CombatProjection(minecraft);
        drawSelectedHealth(graphics, projection, partialTick);
        if (!active()) {
            drawRoute(graphics, projection, -1000, -1000, partialTick);
            drawVehicleRoutes(graphics, projection, -1000, -1000);
        }
    }

    private void drawSelectedHealth(GuiGraphics graphics, CombatProjection projection, float partialTick) {
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        for (UUID id : vehicles) {
            Entity vehicle = SEEN.get(id);
            Optional<UnitSnapshot.Unit> unit = unit(id);
            if (vehicle == null || unit.isEmpty()) {
                continue;
            }
            projection.bounds(vehicle.getBoundingBox()).ifPresent(bounds -> corners(graphics, bounds));
            Vec3 top = vehicle.getPosition(partialTick).add(0, vehicle.getBbHeight() + 0.3, 0);
            projection.point(top).ifPresent(pixel -> {
                int centerX = Mth.floor(pixel.x()), y = Mth.floor(pixel.y());
                if (centerX < -20 || centerX > screenWidth + 20 || y < -6 || y > screenHeight + 6) {
                    return;
                }
                var status = unit.get().status();
                float ratio = status.maxHealth() <= 0 ? 0 : (float) Mth.clamp(status.health() / status.maxHealth(), 0, 1);
                int left = centerX - 15;
                int color = ratio > .5F ? SELECTED : 0xFFFF776E;
                graphics.fill(left - 1, y - 1, left + 31, y + 4, ModeOverlays.color(CombatContent.MODE, 0xB0000000));
                graphics.fill(left, y, left + 30, y + 3, ModeOverlays.color(CombatContent.MODE, 0xFF592F2D));
                graphics.fill(left, y, left + Math.round(30 * ratio), y + 3,
                    ModeOverlays.color(CombatContent.MODE, color));
                if (status.maxEnergy() > 0) {
                    float energy = (float) Mth.clamp((double) status.energy() / status.maxEnergy(), 0, 1);
                    graphics.fill(left - 1, y + 4, left + 31, y + 7, ModeOverlays.color(CombatContent.MODE, 0xB0000000));
                    graphics.fill(left, y + 4, left + 30, y + 6, ModeOverlays.color(CombatContent.MODE, 0xFF243848));
                    graphics.fill(left, y + 4, left + Math.round(30 * energy), y + 6,
                        ModeOverlays.color(CombatContent.MODE, 0xFF6FB7E8));
                }
            });
        }
        for (UUID id : selected) {
            LivingEntity resident = residents.get(id);
            if (resident == null) {
                continue;
            }
            Vec3 head = resident.getPosition(partialTick).add(0, resident.getBbHeight() + 0.18, 0);
            Optional<CombatProjection.Pixel> projected = projection.point(head);
            if (projected.isEmpty()) {
                continue;
            }
            int centerX = Mth.floor(projected.get().x()), y = Mth.floor(projected.get().y());
            if (centerX < -12 || centerX > screenWidth + 12 || y < -6 || y > screenHeight + 6) {
                continue;
            }
            int left = centerX - 9;
            int fill = Math.round(18 * Mth.clamp(resident.getHealth() / Math.max(1, resident.getMaxHealth()), 0, 1));
            int color = resident.getHealth() / Math.max(1, resident.getMaxHealth()) > .5F ? SELECTED : 0xFFFF776E;
            graphics.fill(left - 1, y - 1, left + 19, y + 4, ModeOverlays.color(CombatContent.MODE, 0xB0000000));
            graphics.fill(left, y, left + 18, y + 3, ModeOverlays.color(CombatContent.MODE, 0xFF592F2D));
            graphics.fill(left, y, left + fill, y + 3, ModeOverlays.color(CombatContent.MODE, color));
        }
    }

    private void drawVehicleRoutes(GuiGraphics graphics, CombatProjection projection, int mouseX, int mouseY) {
        var minecraft = Minecraft.getInstance();
        int[] budget = {MAX_ROUTE_SAMPLES};
        for (UUID id : vehicles) {
            var unit = unit(id);
            if (unit.isEmpty()) {
                continue;
            }
            var route = unit.get().status().route();
            boolean reviewing = active() && Screen.hasControlDown();
            if (route.current() < 0 && !reviewing) {
                continue;
            }
            CombatProjection.Pixel previous = WorldSpaces.world(minecraft.level, unit.get().position())
                .flatMap(projection::point).orElse(null);
            int closing = reviewing ? nearWaypoint(mouseX, mouseY, route.points(), route.cycleStart() < 0) : -1;
            for (int index = reviewing ? 0 : Math.max(0, route.current()); index < route.points().size(); index++) {
                var pixel = WorldSpaces.world(minecraft.level, route.points().get(index))
                    .flatMap(at -> projection.point(at.add(0, .12, 0)));
                if (pixel.isEmpty()) {
                    previous = null;
                    continue;
                }
                var at = pixel.get();
                if (previous != null) {
                    segment(graphics, previous.x(), previous.y(), at.x(), at.y(), SELECTED, budget);
                }
                outline(graphics, new CombatProjection.Rect(at.x() - 6, at.y() - 6, at.x() + 6, at.y() + 6),
                    closing == index ? DRAFT : SELECTED);
                graphics.drawString(minecraft.font, Integer.toString(index + 1), (int) at.x() + 6,
                    (int) at.y() - 4, SELECTED, true);
                previous = at;
            }
            if (previous != null && route.cycleStart() >= 0) {
                var first = WorldSpaces.world(minecraft.level, route.points().get(route.cycleStart()))
                    .flatMap(projection::point);
                if (first.isPresent()) {
                    segment(graphics, previous.x(), previous.y(), first.get().x(), first.get().y(), SELECTED, budget);
                }
            }
        }
    }

    private void drawRoute(
        GuiGraphics graphics, CombatProjection projection, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (route.isEmpty()) {
            return;
        }
        var state = CombatPackets.state();
        boolean matchingCommand = routeCommandId != null && routeCommandId.equals(state.commandId());
        boolean complete = matchingCommand && state.routeComplete();
        boolean reviewing = active() && Screen.hasControlDown() && pendingReplacement == null;
        if (complete && !reviewing) {
            return;
        }
        int currentStop = matchingCommand && state.stop() >= 0 ? Mth.clamp(state.stop(), 0, route.size() - 1) : 0;
        int firstStop = reviewing ? 0 : currentStop;
        int[] sampleBudget = {MAX_ROUTE_SAMPLES};
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        CombatProjection.Pixel squadCenter = selectedCenter(projection, partialTick).orElse(null);
        CombatProjection.Pixel previous = reviewing ? null : squadCenter;
        int closing = reviewing ? nearWaypoint(mouseX, mouseY, route, routeOpen) : -1;
        if (!reviewing && previous == null) {
            previous =
                WorldSpaces.world(minecraft.level, route.get(currentStop))
                .flatMap(world -> projection.point(world.add(0, 0.12, 0)))
                .orElse(null);
        }
        if (reviewing && !complete && squadCenter != null) {
            WorldSpaces.world(minecraft.level, route.get(currentStop))
                .flatMap(world -> projection.point(world.add(0, 0.12, 0)))
                .ifPresent(
                target ->
                    segment(
                        graphics,
                        squadCenter.x(),
                        squadCenter.y(),
                        target.x(),
                        target.y(),
                        SELECTED,
                        sampleBudget));
        }
        int ghost = 0x638FEAC3;
        for (int index = firstStop; index < route.size(); index++) {
            Optional<Vec3> world = WorldSpaces.world(minecraft.level, route.get(index));
            Optional<CombatProjection.Pixel> point = world.flatMap(at -> projection.point(at.add(0, 0.12, 0)));
            if (point.isEmpty()) {
                previous = null;
                continue;
            }
            CombatProjection.Pixel at = point.get();
            boolean oldSegment = reviewing && (complete || index < currentStop);
            int pathColor = oldSegment ? ghost : SELECTED;
            if (previous != null) {
                segment(graphics, previous.x(), previous.y(), at.x(), at.y(), pathColor, sampleBudget);
            }
            if (at.x() >= -7 && at.x() <= screenWidth + 7 && at.y() >= -7 && at.y() <= screenHeight + 7) {
                int radius = 6;
                int color =
                    closing == index && index < route.size() - 1
                    ? DRAFT
                    : reviewing && (complete || index < currentStop) ? ghost : SELECTED;
                outline(
                    graphics,
                    new CombatProjection.Rect(at.x() - radius, at.y() - radius, at.x() + radius, at.y() + radius),
                    color);
                graphics.drawString(
                    minecraft.font, Integer.toString(index + 1), (int) at.x() + 6, (int) at.y() - 4,
                    ModeOverlays.color(CombatContent.MODE, color), true);
            }
            previous = at;
        }
        if (previous != null && cycleStart >= 0 && cycleStart < route.size()) {
            CombatProjection.Pixel last = previous;
            WorldSpaces.world(minecraft.level, route.get(cycleStart))
                .flatMap(world -> projection.point(world.add(0, .12, 0)))
                .ifPresent(
                first ->
                    segment(
                        graphics,
                        last.x(),
                        last.y(),
                        first.x(),
                        first.y(),
                        reviewing && complete ? ghost : SELECTED,
                        sampleBudget));
            for (int index = cycleStart + 1; index <= currentStop && index < route.size(); index++) {
                int nextIndex = index;
                WorldSpaces.world(minecraft.level, route.get(nextIndex - 1))
                    .flatMap(world -> projection.point(world.add(0, .12, 0)))
                    .ifPresent(
                    from ->
                        WorldSpaces.world(minecraft.level, route.get(nextIndex))
                            .flatMap(world -> projection.point(world.add(0, .12, 0)))
                            .ifPresent(
                            to ->
                                segment(
                                    graphics,
                                    from.x(),
                                    from.y(),
                                    to.x(),
                                    to.y(),
                                    SELECTED,
                                    sampleBudget)));
            }
        }
    }

    private Optional<CombatProjection.Pixel> selectedCenter(CombatProjection projection, float partialTick) {
        Vec3 center = Vec3.ZERO;
        int count = 0;
        for (UUID id : selected) {
            LivingEntity resident = residents.get(id);
            if (resident == null) {
                continue;
            }
            center = center.add(resident.getPosition(partialTick));
            count++;
        }
        return count == 0 ? Optional.empty() : projection.point(center.scale(1.0 / count).add(0, 0.12, 0));
    }

    /** Corner brackets around a selected vehicle's screen bounds. */
    private static void corners(GuiGraphics graphics, CombatProjection.Rect rect) {
        int color = ModeOverlays.color(CombatContent.MODE, SELECTED);
        int left = (int) rect.left(), top = (int) rect.top();
        int right = (int) Math.ceil(rect.right()), bottom = (int) Math.ceil(rect.bottom());
        int arm = Math.max(3, Math.min(10, Math.min(right - left, bottom - top) / 4));
        graphics.fill(left, top, left + arm, top + 1, color);
        graphics.fill(left, top, left + 1, top + arm, color);
        graphics.fill(right - arm, top, right + 1, top + 1, color);
        graphics.fill(right, top, right + 1, top + arm, color);
        graphics.fill(left, bottom, left + arm, bottom + 1, color);
        graphics.fill(left, bottom - arm, left + 1, bottom + 1, color);
        graphics.fill(right - arm, bottom, right + 1, bottom + 1, color);
        graphics.fill(right, bottom - arm, right + 1, bottom + 1, color);
    }

    private static void outline(GuiGraphics graphics, CombatProjection.Rect rect, int color) {
        color = ModeOverlays.color(CombatContent.MODE, color);
        int left = (int) rect.left(),
            top = (int) rect.top(),
            right = (int) Math.ceil(rect.right()),
            bottom = (int) Math.ceil(rect.bottom());
        graphics.fill(left, top, right + 1, top + 1, color);
        graphics.fill(left, bottom, right + 1, bottom + 1, color);
        graphics.fill(left, top, left + 1, bottom + 1, color);
        graphics.fill(right, top, right + 1, bottom + 1, color);
    }

    private static void segment(
        GuiGraphics graphics, double x1, double y1, double x2, double y2, int color, int[] sampleBudget) {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        double[] clipped = clipSegment(x1, y1, x2, y2, width, height);
        if (clipped == null || sampleBudget[0] <= 0) {
            return;
        }
        x1 = clipped[0];
        y1 = clipped[1];
        x2 = clipped[2];
        y2 = clipped[3];
        int steps = Math.min(64, Math.max(1, (int) Math.ceil(Math.hypot(x2 - x1, y2 - y1) / 3)));
        steps = Math.min(steps, sampleBudget[0]);
        for (int step = 0; step <= steps && sampleBudget[0] > 0; step++, sampleBudget[0]--) {
            double t = step / (double) steps;
            int x = (int) Mth.lerp(t, x1, x2), y = (int) Mth.lerp(t, y1, y2);
            graphics.fill(x, y, x + 2, y + 2, ModeOverlays.color(CombatContent.MODE, color));
        }
    }

    /** Liang-Barsky clip: invisible off-screen legs consume no pixel budget. */
    private static double[] clipSegment(double x1, double y1, double x2, double y2, int width, int height) {
        double dx = x2 - x1, dy = y2 - y1;
        double[] t = {0.0, 1.0};
        if (!clip(-dx, x1, t)
            || !clip(dx, width - 1.0 - x1, t)
            || !clip(-dy, y1, t)
            || !clip(dy, height - 1.0 - y1, t)) {
            return null;
        }
        return new double[] {x1 + t[0] * dx, y1 + t[0] * dy, x1 + t[1] * dx, y1 + t[1] * dy};
    }

    private static boolean clip(double p, double q, double[] t) {
        if (p == 0.0) {
            return q >= 0.0;
        }
        double ratio = q / p;
        if (p < 0.0) {
            if (ratio > t[1]) {
                return false;
            }
            if (ratio > t[0]) {
                t[0] = ratio;
            }
        } else {
            if (ratio < t[0]) {
                return false;
            }
            if (ratio < t[1]) {
                t[1] = ratio;
            }
        }
        return true;
    }

    private void fail(String key, Object... args) {
        if (active()) {
            failureMessage = Component.translatable(key, args);
            failureUntil = Util.getMillis() + FAILURE_DURATION_MS;
        }
    }

    private Component visibleFailure() {
        return Util.getMillis() < failureUntil ? failureMessage : null;
    }

    private void clearFailure() {
        failureMessage = null;
        failureUntil = 0;
    }

    private void cancelPendingRoute() {
        pendingReplacement = null;
    }

    @Override
    public Component orders() {
        var state = CombatPackets.state();
        Component command = Component.translatable("folkways.rts.command." + state.command());
        Component phase = Component.translatable("folkways.rts.phase." + state.phase());
        Component summary;
        if ("none".equals(state.result())) {
            summary = Component.translatable("folkways.rts.status", command, phase);
        } else {
            Component result = Component.translatable("folkways.rts.result." + state.result());
            summary = Component.translatable("folkways.rts.status_result", command, phase, result);
        }
        if (state.waiting() > 0
            && ("waiting_for_member".equals(state.phase())
            || "reforming".equals(state.phase())
            || "failed_waiting".equals(state.phase()))) {
            return Component.translatable("folkways.rts.status_waiting", summary, state.waiting());
        }
        return summary;
    }

    @Override
    public void cancel() {
        dragging = false;
        clearFailure();
        attackMoveMode = false;
        armed = Command.NONE;
        cancelPendingRoute();
        CombatPackets.clearRoute();
        moveMarker = null;
        markerUntil = 0;
        requestIn = 0;
        if (Minecraft.getInstance().getConnection() != null) {
            CombatPackets.suspendControl();
        }
    }

    @Override
    public void cancelPointer() {
        dragging = false;
    }

    private void clearRoute() {
        route.clear();
        cycleStart = -1;
        routeOpen = false;
        routeAttackMove = false;
        routeCommandId = null;
        cancelPendingRoute();
        CombatPackets.clearRoute();
    }

    void reset() {
        SEEN.clear();
        clearFailure();
        if (Minecraft.getInstance().getConnection() != null) {
            CombatPackets.releaseControl();
        }
        dragging = false;
        clearRoute();
        selected.clear();
        vehicles.clear();
        shortcuts.clear();
        residents.clear();
        cards.reset();
        context = null;
        requestIn = 0;
        heartbeatIn = 0;
        controlRevision = -1;
        routeRevision = -1;
        CombatPackets.clearRoster();
        moveMarker = null;
        markerUntil = 0;
        attackMoveMode = false;
        armed = Command.NONE;
        routeAttackMove = false;
    }
}
