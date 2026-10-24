package io.github.izakyl.folkways.war.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.front.client.Overhead;
import io.github.izakyl.folkways.plugins.combat.net.CombatPackets;
import io.github.izakyl.folkways.plugins.combat.ui.CombatContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private boolean dragging;
    private boolean additiveSelection;
    private double startX, startY, endX, endY;
    private Component notice = Component.empty();
    private long noticeUntil;
    private WorldPos moveMarker;
    private long markerUntil;

    private record Context(UUID player, UUID colony, Realm realm) { }

    private boolean active() {
        return Overhead.activeScreen() && OverheadTools.selected().filter(tool -> tool.id().equals(CombatContent.ID)).isPresent();
    }

    void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            reset(); return;
        }
        Context now = Books.heldColony(minecraft.player)
            .map(colony -> new Context(minecraft.player.getUUID(), colony, WorldSpaces.at(minecraft.player).realm())).orElse(null);
        if (!java.util.Objects.equals(context, now)) {
            reset(); context = now;
        }
        if (context == null) { return; }
        synchronizeControl();
        boolean retained = !selected.isEmpty() || shortcuts.values().stream().anyMatch(group -> !group.isEmpty());
        if (!active() && !retained && !CombatPackets.controlPending()) { return; }
        if (requestIn-- <= 0) { CombatPackets.requestRoster(); requestIn = 20; }
        if (retained && heartbeatIn-- <= 0) { CombatPackets.heartbeat(); heartbeatIn = 40; }
        residents.clear();
        if (!rosterReady()) { return; }
        Set<UUID> roster = CombatPackets.roster();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof ResidentEntity resident && resident.isAlive() && !resident.isRemoved()
                && roster.contains(resident.getUUID())) { residents.put(resident.getUUID(), resident); }
        }
        boolean changed = selected.removeIf(id -> !residents.containsKey(id));
        if (changed) {
            clearRoute();
            CombatPackets.select(List.copyOf(selected));
            tell("folkways.war.selection_changed", selected.size());
        }
        synchronizeRoute();
    }

    private void synchronizeControl() {
        if (CombatPackets.controlPending() || controlRevision == CombatPackets.controlRevision()) { return; }
        CombatPackets.controls().filter(state -> state.colonyId().equals(context.colony())
            && state.at().realm().equals(context.realm())).ifPresent(state -> {
                Set<UUID> next = new LinkedHashSet<>(state.selection());
                if (!selected.equals(next)) { clearRoute(); cards.reset(); }
                selected.clear(); selected.addAll(next);
                shortcuts.clear();
                for (int slot = 1; slot <= 9; slot++) { shortcuts.put(slot, state.groups().get(slot - 1)); }
                route.clear(); route.addAll(state.points());
                cycleStart = state.cycleStart(); routeOpen = state.open();
            });
        controlRevision = CombatPackets.controlRevision();
    }

    private void synchronizeRoute() {
        if (routeRevision == CombatPackets.routeRevision()) { return; }
        CombatPackets.route().filter(state -> state.colonyId().equals(context.colony())
            && state.at().realm().equals(context.realm()) && Set.copyOf(state.residents()).equals(selected)).ifPresent(state -> {
                route.clear(); route.addAll(state.points());
                cycleStart = state.cycleStart(); routeOpen = state.open();
                if (state.accepted()) {
                    switch (state.intent()) {
                        case MOVE -> tell("folkways.war.move_accepted", selected.size());
                        case ATTACK -> tell("folkways.war.attack_accepted", selected.size());
                        case WAYPOINT -> tell(cycleStart >= 0 ? "folkways.war.patrol_sent" : "folkways.war.waypoint_added",
                            cycleStart >= 0 ? selected.size() : route.size());
                    }
                } else { moveMarker = null; tell("folkways.combat.error.rejected"); }
            });
        routeRevision = CombatPackets.routeRevision();
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
    public int bottomInset() { return CombatCards.HEIGHT; }

    @Override
    public boolean blocksCameraDescent() { return dragging; }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (y >= cards.top()) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT && ready()) {
                CombatCards.Action action = cards.clicked(x, y);
                if (action != null) {
                    switch (action.kind()) {
                        case GROUP -> shortcut(action.slot(), Screen.hasControlDown());
                        case UNIT -> {
                            if (residents.containsKey(action.resident())) { replaceSelection(Set.of(action.resident())); }
                        }
                        case PREVIOUS -> cards.page(false, selected.size());
                        case NEXT -> cards.page(true, selected.size());
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
        if (Screen.hasControlDown() && cycleStart >= 0 && !CombatPackets.routePending()) {
            tell("folkways.war.route_closed"); return true;
        }
        int closing = Screen.hasControlDown() ? nearWaypoint(x, y) : -1;
        if (closing >= 0) {
            if (closing >= route.size() - 1) { tell("folkways.war.cycle_too_short"); return true; }
            if (!CombatPackets.append(List.copyOf(selected), route.get(closing))) { tell("folkways.war.syncing"); }
            return true;
        }
        if (!Screen.hasControlDown()) {
            Optional<Mob> target = target(x, y);
            if (target.isPresent()) {
                if (CombatPackets.attack(List.copyOf(selected), target.get().getUUID())) {
                    tell("folkways.war.attack_sent", selected.size(), target.get().getDisplayName());
                } else { tell("folkways.war.syncing"); }
                return true;
            }
        }
        Optional<WorldPos> point = ground(x, y);
        if (point.isEmpty()) { tell("folkways.war.ground_required"); return true; }
        if (Screen.hasControlDown()) {
            if (!CombatPackets.append(List.copyOf(selected), point.get())) { tell("folkways.war.syncing"); }
        } else {
            if (CombatPackets.submit(List.copyOf(selected), List.of(point.get()), -1)) {
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

    private int nearWaypoint(double x, double y) {
        if (!routeOpen) { return -1; }
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection projection = new CombatProjection(minecraft);
        double nearest = CLOSE_RADIUS;
        int found = -1;
        for (int index = 0; index < route.size(); index++) {
            Optional<CombatProjection.Pixel> pixel = WorldSpaces.world(minecraft.level, route.get(index))
                .flatMap(world -> projection.point(world.add(0, .12, 0)));
            if (pixel.isPresent()) {
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
        CombatProjection.Rect area = CombatProjection.Rect.between(startX, startY, endX, endY);
        for (ResidentEntity resident : residents.values()) {
            boolean chosen = selected.contains(resident.getUUID());
            AABB box = resident.getBoundingBox().move(resident.getPosition(partialTick).subtract(resident.position()));
            projection.bounds(box).ifPresent(bounds -> {
                if (chosen || dragging && area.intersects(bounds)) { outline(graphics, bounds, chosen ? SELECTED : DRAFT); }
            });
        }
        if (dragging) {
            graphics.fill((int) area.left(), (int) area.top(), (int) area.right(), (int) area.bottom(), 0x2849D2A6);
            outline(graphics, area, SELECTED);
        }
        drawRoute(graphics, projection, mouseX, mouseY);
        if (moveMarker != null && Util.getMillis() <= markerUntil) {
            WorldSpaces.world(minecraft.level, moveMarker).flatMap(projection::point).ifPresent(pixel ->
                outline(graphics, new CombatProjection.Rect(pixel.x() - 5, pixel.y() - 5, pixel.x() + 5, pixel.y() + 5), SELECTED));
        }
        Component status = Util.getMillis() <= noticeUntil ? notice : !rosterReady()
            ? Component.translatable("folkways.war.syncing") : Component.empty();
        cards.render(graphics, mouseX, mouseY, selected, residents, shortcuts, route.size(), status);
    }

    private void drawRoute(GuiGraphics graphics, CombatProjection projection, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection.Pixel previous = null;
        int closing = Screen.hasControlDown() ? nearWaypoint(mouseX, mouseY) : -1;
        for (int index = 0; index < route.size(); index++) {
            Optional<Vec3> world = WorldSpaces.world(minecraft.level, route.get(index));
            Optional<CombatProjection.Pixel> point = world.flatMap(at -> projection.point(at.add(0, 0.12, 0)));
            if (point.isEmpty()) { previous = null; continue; }
            CombatProjection.Pixel at = point.get();
            if (previous != null) { segment(graphics, previous.x(), previous.y(), at.x(), at.y(), SELECTED); }
            int radius = 6;
            int color = closing == index && index < route.size() - 1 ? DRAFT : SELECTED;
            outline(graphics, new CombatProjection.Rect(at.x() - radius, at.y() - radius, at.x() + radius, at.y() + radius), color);
            graphics.drawString(minecraft.font, Integer.toString(index + 1), (int) at.x() + 6, (int) at.y() - 4, SELECTED, true);
            previous = at;
        }
        if (previous != null && cycleStart >= 0 && cycleStart < route.size()) {
            CombatProjection.Pixel last = previous;
            WorldSpaces.world(minecraft.level, route.get(cycleStart)).flatMap(world -> projection.point(world.add(0, .12, 0)))
                .ifPresent(first -> segment(graphics, last.x(), last.y(), first.x(), first.y(), SELECTED));
        }
    }

    private static void outline(GuiGraphics graphics, CombatProjection.Rect rect, int color) {
        int left = (int) rect.left(), top = (int) rect.top(), right = (int) Math.ceil(rect.right()), bottom = (int) Math.ceil(rect.bottom());
        graphics.fill(left, top, right + 1, top + 1, color); graphics.fill(left, bottom, right + 1, bottom + 1, color);
        graphics.fill(left, top, left + 1, bottom + 1, color); graphics.fill(right, top, right + 1, bottom + 1, color);
    }

    private static void segment(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color) {
        // Clamp before drawing so a distant off-screen waypoint cannot create an unbounded GUI loop.
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int steps = Math.min(256, Math.max(1, (int) Math.ceil(Math.hypot(x2 - x1, y2 - y1) / 3)));
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Mth.lerp(t, x1, x2), y = (int) Mth.lerp(t, y1, y2);
            if (x >= 0 && x < width && y >= 0 && y < height) { graphics.fill(x, y, x + 2, y + 2, color); }
        }
    }

    private void tell(String key, Object... args) { notice = Component.translatable(key, args); noticeUntil = Util.getMillis() + 5000; }

    @Override
    public void cancel() {
        dragging = false;
        if (!selected.isEmpty()) {
            if (Minecraft.getInstance().getConnection() != null) { replaceSelection(Set.of()); }
            else { selected.clear(); }
        }
        clearRoute();
    }

    @Override
    public void cancelPointer() { dragging = false; }

    private void clearRoute() {
        route.clear(); cycleStart = -1; routeOpen = false;
        CombatPackets.clearRoute();
    }

    void reset() {
        if (Minecraft.getInstance().getConnection() != null) { CombatPackets.releaseControl(); }
        dragging = false;
        clearRoute(); selected.clear(); shortcuts.clear(); residents.clear(); cards.reset(); context = null;
        requestIn = 0; heartbeatIn = 0; controlRevision = -1; routeRevision = -1;
        CombatPackets.clearRoster();
        moveMarker = null; markerUntil = 0;
        notice = Component.empty(); noticeUntil = 0;
    }
}
