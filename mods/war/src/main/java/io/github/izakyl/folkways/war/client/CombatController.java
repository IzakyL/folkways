package io.github.izakyl.folkways.war.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Screen selection and shortcut sets are local; submitting an intent is the only server mutation. */
final class CombatController implements OverheadTools.Handler {
    private static final int SELECTED = 0xFF8FEAC3;
    private static final int DRAFT = 0xFFFFDC7A;
    private static final int TEXT = 0xFFF0EEE6;
    private static final int BED = 0xB0000000;
    private static final int CLOSE_RADIUS = 12;
    private final LinkedHashSet<UUID> selected = new LinkedHashSet<>();
    private final Map<Integer, List<UUID>> shortcuts = new HashMap<>();
    private final Map<UUID, ResidentEntity> residents = new LinkedHashMap<>();
    private final List<WorldPos> route = new ArrayList<>();
    private Context context;
    private int requestIn;
    private boolean dragging;
    private double startX, startY, endX, endY;
    private Component notice = Component.empty();
    private long noticeUntil;

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
        if (context == null || !active()) { return; }
        if (requestIn-- <= 0) { CombatPackets.requestRoster(); requestIn = 20; }
        residents.clear();
        if (!rosterReady()) { return; }
        Set<UUID> roster = CombatPackets.roster();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof ResidentEntity resident && resident.isAlive() && !resident.isRemoved()
                && roster.contains(resident.getUUID())) { residents.put(resident.getUUID(), resident); }
        }
        boolean changed = selected.removeIf(id -> !residents.containsKey(id));
        if (changed) {
            cancel(); tell("folkways.war.selection_changed", selected.size());
        }
    }

    private boolean rosterReady() {
        return context != null && CombatPackets.rosterColony().filter(context.colony()::equals).isPresent()
            && CombatPackets.rosterAt().filter(at -> at.realm().equals(context.realm())).isPresent();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (y >= Minecraft.getInstance().getWindow().getGuiScaledHeight() - 58) { return true; }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = true; startX = endX = x; startY = endY = y; return true;
        }
        if (button != InputConstants.MOUSE_BUTTON_RIGHT) { return false; }
        if (dragging) { dragging = false; return true; }
        // Always consume RMB here: even an invalid command must not fall through to opening the book.
        if (!ready()) { return true; }
        if (selected.isEmpty()) { tell("folkways.war.select_first"); return true; }
        if (Screen.hasControlDown() && route.size() >= 2 && nearFirst(x, y)) {
            if (CombatPackets.submit(List.copyOf(selected), List.copyOf(route), true)) {
                route.clear(); tell("folkways.war.patrol_sent", selected.size());
            } else { tell("folkways.war.syncing"); }
            return true;
        }
        Optional<WorldPos> point = ground(x, y);
        if (point.isEmpty()) { tell("folkways.war.ground_required"); return true; }
        if (Screen.hasControlDown()) {
            if (!route.isEmpty() && !route.getFirst().sameRealm(point.get())) {
                tell("folkways.war.ground_required"); return true;
            }
            if (route.contains(point.get())) { tell("folkways.war.close_route"); return true; }
            if (route.size() >= CombatPackets.MAX_POINTS) { tell("folkways.war.route_full", CombatPackets.MAX_POINTS); return true; }
            route.add(point.get()); tell("folkways.war.waypoint_added", route.size());
        } else {
            if (CombatPackets.submit(List.copyOf(selected), List.of(point.get()), false)) {
                route.clear(); tell("folkways.war.move_sent", selected.size());
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
        replaceSelection(next);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            if (!ready()) { return true; }
            int slot = keyCode - GLFW.GLFW_KEY_0;
            if (Screen.hasControlDown()) {
                shortcuts.put(slot, List.copyOf(selected));
                tell("folkways.war.group_saved", slot, selected.size());
            } else {
                List<UUID> saved = shortcuts.getOrDefault(slot, List.of());
                replaceSelection(new LinkedHashSet<>(saved.stream().filter(residents::containsKey).toList()));
                tell("folkways.war.group_recalled", slot, selected.size(), saved.size());
            }
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_DELETE
            || keyCode == GLFW.GLFW_KEY_BACKSPACE) && !route.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { route.removeLast(); }
            else { route.clear(); }
            tell("folkways.war.route_draft", route.size()); return true;
        }
        return false;
    }

    private void replaceSelection(Set<UUID> next) {
        if (next.size() > CombatPackets.MAX_MEMBERS) { tell("folkways.war.selection_limit", CombatPackets.MAX_MEMBERS); return; }
        if (!selected.equals(next)) { route.clear(); }
        selected.clear(); selected.addAll(next);
        tell("folkways.war.selected", selected.size());
    }

    private Optional<WorldPos> ground(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        return Overhead.screenRay(x, y).flatMap(ray -> {
            BlockHitResult hit = minecraft.level.clip(new ClipContext(ray.origin(), ray.origin().add(ray.direction().scale(512)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) { return Optional.empty(); }
            BlockPos feet = hit.getBlockPos().above();
            if (!minecraft.level.hasChunkAt(feet) || !Footing.withFeetAt(minecraft.level, feet)
                || !minecraft.level.getFluidState(feet).isEmpty() || !minecraft.level.getFluidState(feet.above()).isEmpty()) {
                return Optional.empty();
            }
            WorldPos point = WorldPos.of(minecraft.level, feet);
            return context != null && point.realm().equals(context.realm()) ? Optional.of(point) : Optional.empty();
        });
    }

    private boolean nearFirst(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        return WorldSpaces.world(minecraft.level, route.getFirst()).flatMap(world ->
            new CombatProjection(minecraft).point(world.add(0, 0.12, 0)))
            .filter(pixel -> Math.hypot(pixel.x() - x, pixel.y() - y) <= CLOSE_RADIUS).isPresent();
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
        int width = minecraft.getWindow().getGuiScaledWidth();
        int top = minecraft.getWindow().getGuiScaledHeight() - 55;
        graphics.fill(5, top - 3, width - 5, top + 51, BED);
        graphics.enableScissor(7, top - 1, width - 7, top + 50);
        graphics.drawString(minecraft.font, Component.translatable("folkways.war.selected", selected.size()), 9, top, SELECTED, true);
        graphics.drawString(minecraft.font, Component.translatable("folkways.war.controls"), 9, top + 11, TEXT, true);
        graphics.drawString(minecraft.font, Component.translatable("folkways.war.path_controls"), 9, top + 22, TEXT, true);
        Component status = Util.getMillis() <= noticeUntil ? notice : !rosterReady()
            ? Component.translatable("folkways.war.syncing") : Component.translatable("folkways.war.shortcut_lifetime");
        graphics.drawString(minecraft.font, status, 9, top + 35, DRAFT, true);
        StringBuilder groups = new StringBuilder();
        for (int slot = 1; slot <= 9; slot++) {
            if (shortcuts.containsKey(slot)) { groups.append(slot).append(':').append(shortcuts.get(slot).size()).append("  "); }
        }
        if (!groups.isEmpty()) {
            Component label = Component.translatable("folkways.war.groups").append(groups.toString());
            graphics.drawString(minecraft.font, label, Math.max(9, width - minecraft.font.width(label) - 9), top, TEXT, true);
        }
        graphics.disableScissor();
    }

    private void drawRoute(GuiGraphics graphics, CombatProjection projection, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        CombatProjection.Pixel previous = null;
        for (int index = 0; index < route.size(); index++) {
            Optional<Vec3> world = WorldSpaces.world(minecraft.level, route.get(index));
            Optional<CombatProjection.Pixel> point = world.flatMap(at -> projection.point(at.add(0, 0.12, 0)));
            if (point.isEmpty()) { previous = null; continue; }
            CombatProjection.Pixel at = point.get();
            if (previous != null) { segment(graphics, previous.x(), previous.y(), at.x(), at.y(), DRAFT); }
            int radius = index == 0 ? CLOSE_RADIUS : 4;
            outline(graphics, new CombatProjection.Rect(at.x() - radius, at.y() - radius, at.x() + radius, at.y() + radius), DRAFT);
            graphics.drawString(minecraft.font, Integer.toString(index + 1), (int) at.x() + 6, (int) at.y() - 4, DRAFT, true);
            previous = at;
        }
        if (previous != null && Screen.hasControlDown()) {
            segment(graphics, previous.x(), previous.y(), mouseX, mouseY, 0xB0FFDC7A);
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
    public void cancel() { dragging = false; route.clear(); }

    @Override
    public void cancelPointer() { dragging = false; }

    void reset() {
        cancel(); selected.clear(); shortcuts.clear(); residents.clear(); context = null; requestIn = 0;
        CombatPackets.clearRoster();
        notice = Component.empty(); noticeUntil = 0;
    }
}
