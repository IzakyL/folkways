package io.github.izakyl.folkways.airline;

import com.lowdragmc.lowdraglib2.gui.LDLibFonts;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * The airport drawn as a chart, north up, scaled to fit whatever room the page gives it. Everything the survey
 * knows about a facility is in the tooltip of the shape that draws it; defects no shape owns gather on one marker.
 */
final class AirportMap extends UIElement {
    private static final String KEY = "folkways.airline.airport.";
    private static final String MAP = KEY + "map.";
    private static final int GRID = 0x163B2A1A;
    private static final int RUNWAY = 0xFF4E4842;
    private static final int RUNWAY_MARK = 0xFFEADCB6;
    private static final int PAD = 0xFF5E7A4E;
    private static final int STAND = 0xFFD9C79C;
    private static final int STAND_EDGE = 0xFF8F7A55;
    private static final int TAXIWAY = 0xFFC8952C;
    private static final int JUNCTION = 0xFF8A5E14;
    private static final int HOLDING = 0xFFB4482A;
    private static final int LIGHT = 0xFFF4EBD2;
    private static final int STAFFED = 0xFF2E7D78;
    private static final int UNSTAFFED = 0xFFB8742A;
    private static final int FLAW = 0xFFB0302A;
    private static final int DARK = 0xFF1E140C;
    private static final int PADDING = 18;
    private static final int MIN_EXTENT = 24;
    // Zoom bounds: a little below the fitted view, and at most this many pixels to a block.
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_SCALE = 24;

    // Also the order a pointer looks through them: small marks over the larger shapes they sit on.
    private enum Kind { WARNING, TOWER, NODE, END, PAD, STAND, TAXIWAY, RUNWAY, AREA }

    // Tooltip inks: LDLib2 lays tooltips on parchment, so they are the book's own dark inks.
    private static final int TIP_TITLE = 0x2B1C12;
    private static final int TIP_TEXT = 0x5C4A33;
    private static final int TIP_GOOD = 0x3F6B33;
    private static final int TIP_WARN = 0x9A5A12;
    private static final int TIP_BAD = 0x9A3324;
    private static final int TIP_STAFFED = 0x23615C;

    /** A drawn facility in world cells: a rectangle, a point, or a path through the cells where it turns. */
    private static final class Feature {
        private final Kind kind;
        private final Footprint area;
        private final List<BlockPos> path;
        private final BlockPos point;
        private final Object source;
        private final List<Component> lines = new ArrayList<>();
        private final List<Component> problems = new ArrayList<>();

        private Feature(Kind kind, Footprint area, List<BlockPos> path, BlockPos point, Object source) {
            this.kind = kind;
            this.area = area;
            this.path = path;
            this.point = point;
            this.source = source;
        }

        private boolean owns(BlockPos pos) {
            return switch (kind) {
                case TOWER, NODE, END -> point.getX() == pos.getX() && point.getZ() == pos.getZ();
                case TAXIWAY -> ((GroundGraph.Edge) source).cells().stream()
                    .anyMatch(cell -> cell.getX() == pos.getX() && cell.getZ() == pos.getZ());
                case PAD, STAND, RUNWAY, AREA -> area.contains(new BlockPos(pos.getX(), area.y(), pos.getZ()));
                case WARNING -> false;
            };
        }
    }

    private Airport airport;
    private List<Airport.Rectangle> pavement = List.of();
    private final List<Feature> features = new ArrayList<>();
    private final Feature warning = new Feature(Kind.WARNING, null, List.of(), BlockPos.ZERO, null);
    private int targetY;
    private Feature hovered;
    // The chart's frame at the last draw: screen = origin + (world - min) * scale.
    private float scale = 1;
    private float fitScale = 1;
    private float originX;
    private float originY;
    private float minX;
    private float minZ;
    private float width = 1;
    private float height = 1;
    // The reader's view, kept on this client only. Fitted until the wheel or a drag moves it; zoom is relative
    // to the fitted scale so the view survives the page being resized.
    private boolean fitted = true;
    private float zoom = 1;
    private float centerX;
    private float centerZ;
    private boolean pressed;
    private boolean dragging;
    private float pressX;
    private float pressY;
    private float lastX;
    private float lastY;
    private boolean overFit;

    AirportMap() {
        addClass("folkways_card");
        addEventListener(UIEvents.MOUSE_WHEEL, event -> {
            if (airport != null && event.deltaY != 0) {
                float[] at = pointer();
                zoomAt(at[0], at[1], (float) Math.pow(1.25, Math.signum(event.deltaY)));
            }
            // The wheel belongs to the chart; the page around it must not scroll as well.
            event.stopPropagation();
        });
        addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button != 0 || airport == null) {
                return;
            }
            float[] at = pointer();
            if (!fitted && overFit(at[0], at[1])) {
                fit();
                return;
            }
            pressed = true;
            dragging = false;
            pressX = lastX = at[0];
            pressY = lastY = at[1];
        });
        addEventListener(UIEvents.MOUSE_MOVE, event -> {
            if (pressed) {
                float[] at = pointer();
                dragTo(at[0], at[1]);
            }
        });
        addEventListener(UIEvents.MOUSE_UP, event -> {
            if (pressed) {
                float[] at = pointer();
                dragTo(at[0], at[1]);
            }
            pressed = false;
            dragging = false;
        });
        addEventListener(UIEvents.DOUBLE_CLICK, event -> fit());
        addEventListener(UIEvents.HOVER_TOOLTIPS, event -> {
            if (overFit) {
                event.hoverTooltips = HoverTooltips.empty().append(Component.translatable(MAP + "fit")
                    .withColor(TIP_TITLE));
            } else if (hovered != null) {
                List<Component> all = new ArrayList<>(hovered.lines);
                all.addAll(hovered.problems);
                event.hoverTooltips = HoverTooltips.empty().append(all.toArray(Component[]::new));
            }
        });
    }

    /** Back to the view that fits the whole airport. */
    void fit() {
        fitted = true;
        zoom = 1;
        pressed = false;
        dragging = false;
    }

    // Scales the view by a factor, keeping the world point under the pointer where it is.
    private void zoomAt(float pointerX, float pointerY, float factor) {
        float fit = fitScale;
        float next = Math.max(MIN_ZOOM, Math.min(Math.max(1, MAX_SCALE / fit), zoom * factor));
        if (next == zoom) {
            return;
        }
        float worldX = minX + (pointerX - originX) / scale;
        float worldZ = minZ + (pointerY - originY) / scale;
        float nextScale = fit * next;
        centerX = worldX - (pointerX - originX) / nextScale + width / 2 / nextScale;
        centerZ = worldZ - (pointerY - originY) / nextScale + height / 2 / nextScale;
        zoom = next;
        fitted = false;
        // Several notches can arrive before the next frame; each must start from the view the last one left.
        scale = nextScale;
        minX = centerX - width / 2f / scale;
        minZ = centerZ - height / 2f / scale;
    }

    private boolean overFit(float pointerX, float pointerY) {
        float[] at = fitAt();
        return Math.abs(pointerX - at[0]) <= 7 && Math.abs(pointerY - at[1]) <= 7;
    }

    private float[] fitAt() {
        return new float[] {originX + width - 11, originY + height - 11};
    }

    /** Shows a surveyed airport; null shows the empty state. */
    void show(Airport next, String state, ListTag catapults) {
        // Another airport starts fitted; a fresh survey of the same one keeps the reader's view.
        if (next == null || airport == null || !next.tower().equals(airport.tower())) {
            fit();
        }
        airport = next;
        pavement = next == null ? List.of() : pavementRows(next.ground().pavement());
        features.clear();
        warning.lines.clear();
        warning.problems.clear();
        hovered = null;
        if (next == null) {
            return;
        }
        targetY = next.coverage().stream().mapToInt(facility -> facility.area().y()).min()
            .orElse(next.tower().getY());
        Feature main = null;
        for (TowerCoverage.Tower tower : next.towers()) {
            Feature feature = add(Kind.TOWER, null, tower.pos(), tower);
            if (tower.pos().equals(next.tower())) {
                main = feature;
            }
            feature.lines.add(title(MAP + "tower"));
            feature.lines.add(detail(MAP + "tower.scan_direction", tower.pos().toShortString(),
                Component.translatable(KEY + "facing." + tower.scanDirection().getName())));
            feature.lines.add(detail(MAP + "tower.reach", tower.pos().getY() - targetY, tower.radius(targetY)));
            feature.lines.add(Component.translatable(KEY + (tower.staffed() ? "staffed" : "unstaffed"))
                .withColor(tower.staffed() ? TIP_STAFFED : TIP_WARN));
        }
        if (main == null) {
            main = add(Kind.TOWER, null, next.tower(), null);
            main.lines.add(title(MAP + "tower"));
        }
        // The airport as a whole speaks through the tower it was surveyed from.
        main.lines.addAll(0, List.of(AirportColony.title(next).copy().withColor(TIP_TITLE)
            .withStyle(ChatFormatting.BOLD), detail(Component.translatable(KEY + state)),
            detail(Component.translatable(KEY + "counts", next.runways().size(), next.helipads().size(),
                next.stands().size(), next.ground().nodes().size(), next.ground().edges().size()))));
        main.lines.add(detail(KEY + "plane", targetY));
        for (GroundGraph.Node node : next.ground().nodes()) {
            String kind = node.kinds().contains(GroundGraph.Kind.HOLDING_POINT) ? "holding"
                : node.kinds().contains(GroundGraph.Kind.RUNWAY_ENTRY) ? "entry"
                : node.kinds().contains(GroundGraph.Kind.JUNCTION) ? "junction" : null;
            if (kind == null) {
                continue;
            }
            Feature feature = add(Kind.NODE, null, node.pos(), node);
            feature.lines.add(title(MAP + kind));
            if (kind.equals("holding")) {
                feature.lines.add(detail(MAP + "holding.distance", AirportNumbers.number(node.holdingDistance())));
            } else if (kind.equals("entry")) {
                feature.lines.add(detail(MAP + "entry.remaining", node.remainingFirst(), node.remainingSecond()));
            }
            feature.lines.add(detail(MAP + "at", node.pos().toShortString()));
        }
        for (Runway runway : next.runways()) {
            for (BlockPos threshold : List.of(runway.firstThreshold(), runway.secondThreshold())) {
                Feature feature = add(Kind.END, null, threshold, runway);
                feature.lines.add(title(MAP + "end", next.designator(runway, threshold)));
                feature.lines.add(detail(MAP + "end.landing",
                    Component.literal(AirportNumbers.number(runway.headingAt(threshold)) + "°")));
                feature.lines.add(detail(MAP + "at", threshold.toShortString()));
                for (Tag value : catapults) {
                    CompoundTag catapult = (CompoundTag) value;
                    if (catapult.getLong("threshold") == threshold.asLong()) {
                        feature.lines.add(detail(MAP + "catapult", catapult.getString("start"),
                            catapult.getInt("length"), catapult.getInt("power")));
                        feature.lines.add(detail(MAP + "catapult.speed",
                            AirportNumbers.number(catapult.getDouble("speed")), catapult.getString("controller")));
                    }
                }
            }
        }
        for (Helipad pad : next.helipads()) {
            Feature feature = add(Kind.PAD, Airport.footprint(pad), pad.center(), pad);
            feature.lines.add(title(MAP + "helipad"));
            feature.lines.add(detail(MAP + "helipad.size", pad.size(), pad.size()));
            feature.lines.add(detail(MAP + "at", pad.center().toShortString()));
        }
        for (Airport.Stand stand : next.stands()) {
            Feature feature = add(Kind.STAND, stand.footprint(), stand.pos(), stand);
            feature.lines.add(title(MAP + "stand"));
            feature.lines.add(detail(MAP + "stand.size", stand.length(), stand.width()));
            feature.lines.add(detail(MAP + "stand.fits", AirportNumbers.number(stand.maxLength()),
                AirportNumbers.number(stand.maxWingspan())));
            feature.lines.add(detail(MAP + "stand.turn", AirportNumbers.number(stand.turningRadius())));
            feature.lines.add(detail(MAP + "at", stand.pos().toShortString()));
        }
        for (GroundGraph.Edge edge : next.ground().edges()) {
            Feature feature = new Feature(Kind.TAXIWAY, bounds(edge.cells()), simplify(edge.cells()), edge.from(),
                edge);
            features.add(feature);
            feature.lines.add(title(MAP + "taxiway"));
            feature.lines.add(detail(MAP + "taxiway.wingspan", AirportNumbers.number(edge.maxWingspan())));
            feature.lines.add(detail(MAP + "taxiway.span", edge.from().toShortString(), edge.to().toShortString(),
                edge.length()));
        }
        for (Runway runway : next.runways()) {
            Feature feature = add(Kind.RUNWAY, Airport.footprint(runway), runway.firstThreshold(), runway);
            feature.lines.add(title(MAP + "runway", next.designator(runway, runway.firstThreshold())
                + "/" + next.designator(runway, runway.secondThreshold())));
            feature.lines.add(detail(MAP + "runway.size", runway.length(), runway.width()));
            feature.lines.add(detail(MAP + "runway.surface", runway.surfaceY()));
        }
        for (GroundGraph.Turn turn : next.ground().turns()) {
            owner(turn.pos()).lines.add(detail(MAP + "corner",
                Component.translatable(KEY + "facing." + turn.first().getName()),
                Component.translatable(KEY + "facing." + turn.second().getName()),
                AirportNumbers.number(turn.maxWingspan()), AirportNumbers.number(turn.minRadius()),
                AirportNumbers.number(turn.maxRadius())));
        }
        for (TowerCoverage.Facility facility : next.coverage()) {
            if (!facility.covered()) {
                var area = facility.area();
                Feature feature = features.stream().filter(candidate -> candidate.kind != Kind.TAXIWAY
                        && area.equals(candidate.area)).findFirst()
                    .orElseGet(() -> owner(new BlockPos((area.minX() + area.maxX()) / 2, area.y(),
                        (area.minZ() + area.maxZ()) / 2)));
                if (feature == warning) {
                    // Nothing drawn here owns it: the uncovered patch is its own outlined shape.
                    feature = add(Kind.AREA, area, new BlockPos(area.minX(), area.y(), area.minZ()), facility);
                    feature.lines.add(title(MAP + "area"));
                    feature.lines.add(detail(MAP + "at", feature.point.toShortString()));
                }
                feature.problems.add(Component.translatable(MAP + "uncovered",
                    Component.translatable(KEY + "coverage." + facility.reason())).withColor(TIP_WARN));
            }
        }
        for (Airport.Problem problem : next.flaws()) {
            owner(problem.pos()).problems.add(problem.text().copy().withColor(TIP_BAD));
        }
        if (!warning.problems.isEmpty()) {
            warning.lines.add(title(KEY + "flaws"));
        }
        if (next.flaws().isEmpty()) {
            main.lines.add(Component.translatable(KEY + "clear").withColor(TIP_GOOD));
        }
    }

    private Feature add(Kind kind, Footprint area, BlockPos point, Object source) {
        Feature feature = new Feature(kind, area, List.of(), point, source);
        features.add(feature);
        return feature;
    }

    private Feature owner(BlockPos pos) {
        for (Kind kind : Kind.values()) {
            for (Feature feature : features) {
                if (feature.kind == kind && feature.owns(pos)) {
                    return feature;
                }
            }
        }
        return warning;
    }

    private static Component title(String key, Object... args) {
        return Component.translatable(key, args).withColor(TIP_TITLE).withStyle(ChatFormatting.BOLD);
    }

    private static Component detail(String key, Object... args) {
        return detail(Component.translatable(key, args));
    }

    private static Component detail(MutableComponent text) {
        return text.withColor(TIP_TEXT);
    }

    private static Airport.Rectangle bounds(List<BlockPos> cells) {
        return new Airport.Rectangle(cells.stream().mapToInt(BlockPos::getX).min().orElse(0),
            cells.stream().mapToInt(BlockPos::getZ).min().orElse(0),
            cells.stream().mapToInt(BlockPos::getX).max().orElse(0),
            cells.stream().mapToInt(BlockPos::getZ).max().orElse(0), cells.isEmpty() ? 0 : cells.getFirst().getY());
    }

    // Keeps only the cells where the path turns, so a straight taxiway is one stroke.
    private static List<BlockPos> simplify(List<BlockPos> cells) {
        List<BlockPos> kept = new ArrayList<>();
        for (int index = 0; index < cells.size(); index++) {
            if (index == 0 || index == cells.size() - 1 || !cells.get(index).subtract(cells.get(index - 1))
                    .equals(cells.get(index + 1).subtract(cells.get(index)))) {
                kept.add(cells.get(index));
            }
        }
        return List.copyOf(kept);
    }

    private float sx(double x) {
        return originX + (float) (x - minX) * scale;
    }

    private float sz(double z) {
        return originY + (float) (z - minZ) * scale;
    }

    private float[] center(BlockPos pos) {
        return new float[] {sx(pos.getX() + 0.5), sz(pos.getZ() + 0.5)};
    }

    // Fits every facility and tower in the room, centred; coverage sectors run off the edge.
    private void frame(float x, float y, float width, float height) {
        this.width = width;
        this.height = height;
        int x0 = Integer.MAX_VALUE;
        int z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (Feature feature : features) {
            Footprint box = feature.area != null ? feature.area : new Airport.Rectangle(feature.point.getX(),
                feature.point.getZ(), feature.point.getX(), feature.point.getZ(), 0);
            x0 = Math.min(x0, box.minX());
            z0 = Math.min(z0, box.minZ());
            x1 = Math.max(x1, box.maxX() + 1);
            z1 = Math.max(z1, box.maxZ() + 1);
        }
        for (Airport.Rectangle area : pavement) {
            x0 = Math.min(x0, area.minX());
            z0 = Math.min(z0, area.minZ());
            x1 = Math.max(x1, area.maxX() + 1);
            z1 = Math.max(z1, area.maxZ() + 1);
        }
        float spanX = Math.max(MIN_EXTENT, x1 - x0);
        float spanZ = Math.max(MIN_EXTENT, z1 - z0);
        float fit = Math.max(0.05f, Math.min((width - PADDING * 2) / spanX, (height - PADDING * 2) / spanZ));
        if (fitted) {
            centerX = (x0 + x1) / 2f;
            centerZ = (z0 + z1) / 2f;
        }
        fitScale = fit;
        zoom = Math.max(MIN_ZOOM, Math.min(Math.max(1, MAX_SCALE / fit), zoom));
        // The airport may not be panned out of sight: the view's centre stays over its bounds.
        centerX = Math.max(x0, Math.min(x1, centerX));
        centerZ = Math.max(z0, Math.min(z1, centerZ));
        scale = fit * zoom;
        minX = centerX - width / 2f / scale;
        minZ = centerZ - height / 2f / scale;
        originX = x;
        originY = y;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        float x = getPositionX() + 3;
        float y = getPositionY() + 3;
        float width = getSizeWidth() - 6;
        float height = getSizeHeight() - 6;
        if (width <= 8 || height <= 8) {
            return;
        }
        GuiGraphics graphics = context.graphics;
        Font font = LDLibFonts.font();
        if (airport == null) {
            hovered = null;
            List<FormattedCharSequence> lines = font.split(Component.translatable(KEY + "empty"),
                (int) Math.max(40, width - 24));
            float top = y + height / 2 - lines.size() * 5;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, Math.round(x + (width - font.width(line)) / 2), Math.round(top),
                    Rows.QUIET, false);
                top += 10;
            }
            return;
        }
        frame(x, y, width, height);
        float[] mouse = pointer();
        pan(mouse[0], mouse[1]);
        graphics.flush();
        context.enableScissor(x, y, width, height);
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        grid(buffer, pose, x, y, width, height);
        for (TowerCoverage.Tower tower : airport.towers()) {
            wedge(buffer, pose, tower, tower.staffed() ? STAFFED : UNSTAFFED);
        }
        for (Airport.Rectangle area : pavement) {
            fill(buffer, pose, sx(area.minX()), sz(area.minZ()), sx(area.maxX() + 1), sz(area.maxZ() + 1),
                0xFF827B6C);
        }
        float stroke = Math.max(1.5f, Math.min(3f, scale * 1.2f));
        for (Feature feature : features) {
            if (feature.kind == Kind.TAXIWAY) {
                path(buffer, pose, feature.path, stroke, TAXIWAY);
            }
        }
        for (Feature feature : features) {
            switch (feature.kind) {
                case RUNWAY -> runway(buffer, pose, feature);
                case STAND -> stand(buffer, pose, feature);
                case PAD -> {
                    float[] at = center(feature.point);
                    float radius = padRadius(feature);
                    circle(buffer, pose, at[0], at[1], radius, PAD);
                    if (radius >= 5) {
                        ring(buffer, pose, at[0], at[1], radius - 1.75f, 0.75f, 0xAAF4EBD2);
                    }
                }
                default -> {
                }
            }
        }
        for (TowerCoverage.Facility facility : airport.coverage()) {
            if (!facility.covered()) {
                var area = facility.area();
                outline(buffer, pose, sx(area.minX()) - 1, sz(area.minZ()) - 1, sx(area.maxX() + 1) + 1,
                    sz(area.maxZ() + 1) + 1, 1, FLAW);
            }
        }
        for (Feature feature : features) {
            if (feature.kind == Kind.NODE) {
                node(buffer, pose, feature, stroke);
            }
        }
        for (Feature feature : features) {
            if (feature.kind == Kind.TOWER) {
                tower(buffer, pose, feature);
            }
        }
        boolean inside = isHover() && context.mouseX >= x && context.mouseX < x + width && context.mouseY >= y
            && context.mouseY < y + height;
        overFit = inside && !fitted && !dragging && overFit(context.mouseX, context.mouseY);
        hovered = inside && !dragging && !overFit ? hit(context.mouseX, context.mouseY, stroke, x, y, width) : null;
        if (hovered != null && hovered.kind != Kind.WARNING) {
            highlight(buffer, pose, hovered, stroke);
        }
        for (Feature feature : features) {
            if (!feature.problems.isEmpty()) {
                float[] at = badge(feature);
                circle(buffer, pose, at[0], at[1], 4, LIGHT);
                circle(buffer, pose, at[0], at[1], 3.25f, FLAW);
            }
        }
        if (!warning.problems.isEmpty()) {
            float[] at = warningAt(x, y, width);
            circle(buffer, pose, at[0], at[1], 6.5f, hovered == warning ? DARK : LIGHT);
            circle(buffer, pose, at[0], at[1], 5.5f, FLAW);
        }
        if (!fitted) {
            fitButton(buffer, pose);
        }
        int bar = furniture(buffer, pose, x, y, height);
        graphics.flush();
        labels(graphics, font, x, y, width, height, bar);
        graphics.flush();
        context.disableScissor();
    }

    // Where the cursor is now, in GUI units. Events can carry the position LDLib2 last saw, which lags a cursor
    // that jumped and then scrolled or dragged before a frame was drawn.
    private static float[] pointer() {
        Minecraft minecraft = Minecraft.getInstance();
        var window = minecraft.getWindow();
        return new float[] {(float) (minecraft.mouseHandler.xpos() * window.getGuiScaledWidth()
            / window.getScreenWidth()), (float) (minecraft.mouseHandler.ypos() * window.getGuiScaledHeight()
            / window.getScreenHeight())};
    }

    // A press held past the frame follows the pointer; one released outside the chart ends here.
    private void pan(float mouseX, float mouseY) {
        if (pressed && !Minecraft.getInstance().mouseHandler.isLeftPressed()) {
            pressed = false;
        }
        if (pressed) {
            dragTo(mouseX, mouseY);
        } else {
            dragging = false;
        }
    }

    // A press becomes a pan once the pointer has moved a few pixels; a plain click leaves the view alone.
    private void dragTo(float mouseX, float mouseY) {
        if (!dragging && Math.hypot(mouseX - pressX, mouseY - pressY) >= 3) {
            dragging = true;
            fitted = false;
        }
        if (dragging) {
            centerX -= (mouseX - lastX) / scale;
            centerZ -= (mouseY - lastY) / scale;
            minX = centerX - width / 2f / scale;
            minZ = centerZ - height / 2f / scale;
        }
        lastX = mouseX;
        lastY = mouseY;
    }

    // Four corner ticks in a small plate at the foot of the chart: back to the whole airport.
    private void fitButton(VertexConsumer buffer, Matrix4f pose) {
        float[] at = fitAt();
        fill(buffer, pose, at[0] - 7, at[1] - 7, at[0] + 7, at[1] + 7, overFit ? DARK : STAND_EDGE);
        fill(buffer, pose, at[0] - 6, at[1] - 6, at[0] + 6, at[1] + 6, overFit ? STAND_EDGE : LIGHT);
        int ink = overFit ? LIGHT : DARK;
        for (int across : new int[] {-1, 1}) {
            for (int down : new int[] {-1, 1}) {
                float cx = at[0] + across * 3.5f;
                float cz = at[1] + down * 3.5f;
                float ix = cx - across * 2.5f;
                float iz = cz - down * 2.5f;
                fill(buffer, pose, Math.min(cx, ix), cz - 0.5f, Math.max(cx, ix), cz + 0.5f, ink);
                fill(buffer, pose, cx - 0.5f, Math.min(cz, iz), cx + 0.5f, Math.max(cz, iz), ink);
            }
        }
    }

    private float padRadius(Feature feature) {
        return Math.max(3, (feature.area.maxX() - feature.area.minX() + 1) * scale / 2);
    }

    private Feature hit(float mouseX, float mouseY, float stroke, float x, float y, float width) {
        if (!warning.problems.isEmpty()) {
            float[] at = warningAt(x, y, width);
            if (Math.hypot(mouseX - at[0], mouseY - at[1]) <= 8) {
                return warning;
            }
        }
        for (Kind kind : Kind.values()) {
            Feature best = null;
            double nearest = Double.MAX_VALUE;
            for (Feature feature : features) {
                if (feature.kind != kind) {
                    continue;
                }
                double distance = distance(feature, mouseX, mouseY);
                double reach = switch (kind) {
                    case TOWER -> 6;
                    case NODE -> stroke / 2 + 2.5;
                    case END -> 8;
                    case TAXIWAY -> stroke / 2 + 1.5;
                    default -> 0.5;
                };
                if (distance <= reach && distance < nearest) {
                    nearest = distance;
                    best = feature;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private double distance(Feature feature, float mouseX, float mouseY) {
        switch (feature.kind) {
            case TOWER, NODE -> {
                float[] at = center(feature.point);
                return Math.hypot(mouseX - at[0], mouseY - at[1]);
            }
            case END -> {
                float[] at = endLabel((Runway) feature.source, feature.point);
                return Math.hypot(mouseX - at[0], mouseY - at[1]);
            }
            case PAD -> {
                float[] at = center(feature.point);
                return Math.max(0, Math.hypot(mouseX - at[0], mouseY - at[1]) - padRadius(feature));
            }
            case RUNWAY -> {
                Runway runway = (Runway) feature.source;
                float[] a = center(runway.firstThreshold());
                float[] b = center(runway.secondThreshold());
                return Math.max(0, segment(mouseX, mouseY, a[0], a[1], b[0], b[1])
                    - Math.max(4, runway.width() * scale) / 2);
            }
            case STAND, AREA -> {
                float[] box = box(feature);
                return Math.hypot(Math.max(Math.max(box[0] - mouseX, 0), mouseX - box[2]),
                    Math.max(Math.max(box[1] - mouseY, 0), mouseY - box[3]));
            }
            case TAXIWAY -> {
                double nearest = Double.MAX_VALUE;
                for (int index = 1; index < feature.path.size(); index++) {
                    float[] a = center(feature.path.get(index - 1));
                    float[] b = center(feature.path.get(index));
                    nearest = Math.min(nearest, segment(mouseX, mouseY, a[0], a[1], b[0], b[1]));
                }
                return nearest;
            }
            default -> {
                return Double.MAX_VALUE;
            }
        }
    }

    private static double segment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float length = dx * dx + dy * dy;
        float t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    // A rectangle on screen; runways are kept at least a few pixels wide however far the chart is zoomed out.
    private float[] box(Feature feature) {
        var area = feature.area;
        float[] box = {sx(area.minX()), sz(area.minZ()), sx(area.maxX() + 1), sz(area.maxZ() + 1)};
        int across = feature.kind == Kind.RUNWAY && Math.abs(((Runway) feature.source).dx()) > 0.9 ? 1 : 0;
        float least = feature.kind == Kind.RUNWAY ? 4 : 3;
        if (box[across + 2] - box[across] < least) {
            float middle = (box[across] + box[across + 2]) / 2;
            box[across] = middle - least / 2;
            box[across + 2] = middle + least / 2;
        }
        if (feature.kind == Kind.STAND && box[2] - box[0] < least) {
            box[2] = box[0] + least;
        }
        return box;
    }

    private float[] endLabel(Runway runway, BlockPos threshold) {
        double sign = threshold.equals(runway.firstThreshold()) ? -1 : 1;
        return new float[] {sx(threshold.getX() + 0.5) + (float) (sign * runway.dx() * 10),
            sz(threshold.getZ() + 0.5) + (float) (sign * runway.dz() * 10)};
    }

    private float[] badge(Feature feature) {
        return switch (feature.kind) {
            case STAND, RUNWAY, AREA -> {
                float[] box = box(feature);
                yield new float[] {box[2], box[1]};
            }
            case PAD -> {
                float[] at = center(feature.point);
                float offset = padRadius(feature) * 0.7f;
                yield new float[] {at[0] + offset, at[1] - offset};
            }
            case TAXIWAY -> {
                float[] a = center(feature.path.get(0));
                float[] b = center(feature.path.get(Math.min(1, feature.path.size() - 1)));
                yield new float[] {(a[0] + b[0]) / 2 + 3, (a[1] + b[1]) / 2 - 3};
            }
            case END -> {
                float[] at = endLabel((Runway) feature.source, feature.point);
                yield new float[] {at[0] + 7, at[1] - 5};
            }
            default -> {
                float[] at = center(feature.point);
                yield new float[] {at[0] + 5, at[1] - 5};
            }
        };
    }

    private static float[] warningAt(float x, float y, float width) {
        return new float[] {x + width - 11, y + 11};
    }

    private int gridStep() {
        int step = 4;
        while (step * scale < 28) {
            step *= 2;
        }
        return step;
    }

    private void grid(VertexConsumer buffer, Matrix4f pose, float x, float y, float width, float height) {
        int step = gridStep();
        for (int line = (int) Math.ceil(minX / step) * step; sx(line) < x + width; line += step) {
            fill(buffer, pose, sx(line), y, sx(line) + 0.5f, y + height, GRID);
        }
        for (int line = (int) Math.ceil(minZ / step) * step; sz(line) < y + height; line += step) {
            fill(buffer, pose, x, sz(line), x + width, sz(line) + 0.5f, GRID);
        }
    }

    private void wedge(VertexConsumer buffer, Matrix4f pose, TowerCoverage.Tower tower, int color) {
        int radius = tower.radius(targetY);
        if (radius <= 0) {
            return;
        }
        float[] at = center(tower.pos());
        double facing = Math.atan2(tower.scanDirection().getStepZ(), tower.scanDirection().getStepX());
        double half = Math.toRadians(TowerCoverage.SECTOR_DEGREES / 2);
        int steps = 48;
        float reach = radius * scale;
        int shade = (color & 0xFFFFFF) | 0x16000000;
        int edge = (color & 0xFFFFFF) | 0xA0000000;
        float px = 0;
        float py = 0;
        for (int step = 0; step <= steps; step++) {
            double angle = facing - half + 2 * half * step / steps;
            float qx = at[0] + (float) Math.cos(angle) * reach;
            float qy = at[1] + (float) Math.sin(angle) * reach;
            if (step > 0) {
                quad(buffer, pose, at[0], at[1], px, py, qx, qy, qx, qy, shade);
                line(buffer, pose, px, py, qx, qy, 1, edge);
            }
            px = qx;
            py = qy;
        }
        for (double angle : new double[] {facing - half, facing + half}) {
            dashed(buffer, pose, at[0], at[1], at[0] + (float) Math.cos(angle) * reach,
                at[1] + (float) Math.sin(angle) * reach, edge);
        }
    }

    private void runway(VertexConsumer buffer, Matrix4f pose, Feature feature) {
        Runway runway = (Runway) feature.source;
        float[] a = center(runway.firstThreshold());
        float[] b = center(runway.secondThreshold());
        float stroke = Math.max(4, runway.width() * scale);
        line(buffer, pose, a[0] - (float) runway.dx() * scale / 2,
            a[1] - (float) runway.dz() * scale / 2, b[0] + (float) runway.dx() * scale / 2,
            b[1] + (float) runway.dz() * scale / 2, stroke, RUNWAY);
        dashed(buffer, pose, a[0], a[1], b[0], b[1], RUNWAY_MARK);
        float nx = (float) (-runway.dz() * (stroke / 2 - 1));
        float nz = (float) (runway.dx() * (stroke / 2 - 1));
        line(buffer, pose, a[0] - nx, a[1] - nz, a[0] + nx, a[1] + nz, 1, RUNWAY_MARK);
        line(buffer, pose, b[0] - nx, b[1] - nz, b[0] + nx, b[1] + nz, 1, RUNWAY_MARK);
    }

    private void stand(VertexConsumer buffer, Matrix4f pose, Feature feature) {
        float[] box = box(feature);
        fill(buffer, pose, box[0], box[1], box[2], box[3], STAND);
        outline(buffer, pose, box[0], box[1], box[2], box[3], 0.75f, STAND_EDGE);
        // The stop line, where the nose rests.
        float line = 1.25f;
        switch (((Airport.Stand) feature.source).facing()) {
            case NORTH -> fill(buffer, pose, box[0], box[1], box[2], box[1] + line, DARK);
            case SOUTH -> fill(buffer, pose, box[0], box[3] - line, box[2], box[3], DARK);
            case WEST -> fill(buffer, pose, box[0], box[1], box[0] + line, box[3], DARK);
            default -> fill(buffer, pose, box[2] - line, box[1], box[2], box[3], DARK);
        }
    }

    private void node(VertexConsumer buffer, Matrix4f pose, Feature feature, float stroke) {
        float[] at = center(feature.point);
        GroundGraph.Node node = (GroundGraph.Node) feature.source;
        if (node.kinds().contains(GroundGraph.Kind.HOLDING_POINT)) {
            float size = stroke / 2 + 1.5f;
            fill(buffer, pose, at[0] - size, at[1] - size, at[0] + size, at[1] + size, HOLDING);
        } else if (node.kinds().contains(GroundGraph.Kind.RUNWAY_ENTRY)) {
            circle(buffer, pose, at[0], at[1], stroke / 2 + 1.75f, DARK);
            circle(buffer, pose, at[0], at[1], stroke / 2 + 0.75f, LIGHT);
        } else {
            circle(buffer, pose, at[0], at[1], stroke / 2 + 0.75f, JUNCTION);
        }
    }

    private void tower(VertexConsumer buffer, Matrix4f pose, Feature feature) {
        float[] at = center(feature.point);
        TowerCoverage.Tower tower = (TowerCoverage.Tower) feature.source;
        int color = tower == null || tower.staffed() ? STAFFED : UNSTAFFED;
        if (tower != null) {
            line(buffer, pose, at[0], at[1], at[0] + tower.scanDirection().getStepX() * 9,
                at[1] + tower.scanDirection().getStepZ() * 9, 1.5f, DARK);
        }
        float size = 4.5f;
        quad(buffer, pose, at[0], at[1] - size - 1, at[0] - size - 1, at[1], at[0], at[1] + size + 1,
            at[0] + size + 1, at[1], DARK);
        quad(buffer, pose, at[0], at[1] - size, at[0] - size, at[1], at[0], at[1] + size, at[0] + size, at[1], color);
        circle(buffer, pose, at[0], at[1], 1.25f, LIGHT);
    }

    private void highlight(VertexConsumer buffer, Matrix4f pose, Feature feature, float stroke) {
        switch (feature.kind) {
            case TOWER, NODE -> {
                float[] at = center(feature.point);
                ring(buffer, pose, at[0], at[1], feature.kind == Kind.TOWER ? 8 : stroke / 2 + 3.5f, 1, DARK);
            }
            case END -> {
                float[] at = endLabel((Runway) feature.source, feature.point);
                ring(buffer, pose, at[0], at[1], 7.5f, 1, DARK);
            }
            case PAD -> {
                float[] at = center(feature.point);
                ring(buffer, pose, at[0], at[1], padRadius(feature) + 1.75f, 1, DARK);
            }
            case STAND, RUNWAY, AREA -> {
                float[] box = box(feature);
                outline(buffer, pose, box[0] - 1.5f, box[1] - 1.5f, box[2] + 1.5f, box[3] + 1.5f, 1, DARK);
            }
            case TAXIWAY -> path(buffer, pose, feature.path, stroke + 0.5f, DARK);
            default -> {
            }
        }
    }

    // The scale bar along the foot and the north arrow at the head; returns the bar's length in blocks.
    private int furniture(VertexConsumer buffer, Matrix4f pose, float x, float y, float height) {
        int blocks = gridStep();
        while (blocks * scale > 80 && blocks > 1) {
            blocks /= 2;
        }
        float left = x + 8;
        float base = y + height - 7;
        float right = left + blocks * scale;
        fill(buffer, pose, left, base - 1, right, base, Rows.QUIET);
        fill(buffer, pose, left, base - 4, left + 1, base, Rows.QUIET);
        fill(buffer, pose, right - 1, base - 4, right, base, Rows.QUIET);
        float nx = x + 11;
        float ny = y + 6;
        quad(buffer, pose, nx, ny, nx - 4, ny + 10, nx, ny + 7.5f, nx, ny + 7.5f, Rows.QUIET);
        quad(buffer, pose, nx, ny, nx, ny + 7.5f, nx + 4, ny + 10, nx + 4, ny + 10, 0xFFB8A27A);
        return blocks;
    }

    private void labels(GuiGraphics graphics, Font font, float x, float y, float width, float height, int bar) {
        for (Feature feature : features) {
            if (feature.kind == Kind.END) {
                float[] at = endLabel((Runway) feature.source, feature.point);
                text(graphics, font, Component.literal(airport.designator((Runway) feature.source, feature.point)),
                    at[0], at[1], Rows.INK, true);
            } else if (feature.kind == Kind.PAD && padRadius(feature) >= 6) {
                float[] at = center(feature.point);
                text(graphics, font, Component.literal("H"), at[0] + 0.5f, at[1], LIGHT, true);
            }
        }
        for (Feature feature : features) {
            if (!feature.problems.isEmpty()) {
                float[] at = badge(feature);
                small(graphics, font, "!", at[0] + 0.25f, at[1]);
            }
        }
        if (!warning.problems.isEmpty()) {
            float[] at = warningAt(x, y, width);
            text(graphics, font, Component.literal("!"), at[0] + 0.5f, at[1] + 0.5f, 0xFFFFFFFF, true);
        }
        text(graphics, font, Component.translatable(MAP + "north"), x + 18, y + 12, Rows.QUIET, false);
        text(graphics, font, Component.translatable(MAP + "scale", bar), x + 8, y + height - 17, Rows.QUIET, false);
    }

    // Text centred on a point when centred, else from its left edge with the point on its middle line.
    private static void text(GuiGraphics graphics, Font font, Component text, float x, float y, int color,
                             boolean centred) {
        graphics.pose().pushPose();
        graphics.pose().translate(centred ? x - font.width(text) / 2f : x, y - 4, 0);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private static void small(GuiGraphics graphics, Font font, String text, float x, float y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(0.75f, 0.75f, 1);
        graphics.drawString(font, text, -font.width(text) / 2f, -3.5f, 0xFFFFFFFF, false);
        graphics.pose().popPose();
    }

    private static List<Airport.Rectangle> pavementRows(List<BlockPos> cells) {
        List<BlockPos> sorted = cells.stream().sorted(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX)).toList();
        List<Airport.Rectangle> rows = new ArrayList<>();
        BlockPos start = null;
        BlockPos end = null;
        for (BlockPos cell : sorted) {
            if (end != null && (cell.getY() != end.getY() || cell.getZ() != end.getZ()
                    || cell.getX() != end.getX() + 1)) {
                rows.add(Airport.Rectangle.between(start, end));
                start = null;
            }
            if (start == null) {
                start = cell;
            }
            end = cell;
        }
        if (start != null) {
            rows.add(Airport.Rectangle.between(start, end));
        }
        return List.copyOf(rows);
    }

    private void path(VertexConsumer buffer, Matrix4f pose, List<BlockPos> path, float stroke, int color) {
        for (int index = 0; index < path.size(); index++) {
            float[] b = center(path.get(index));
            fill(buffer, pose, b[0] - stroke / 2, b[1] - stroke / 2, b[0] + stroke / 2, b[1] + stroke / 2, color);
            if (index > 0) {
                float[] a = center(path.get(index - 1));
                line(buffer, pose, a[0], a[1], b[0], b[1], stroke, color);
            }
        }
    }

    private static void dashed(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1,
                               int color) {
        float length = (float) Math.hypot(x1 - x0, y1 - y0);
        for (float at = 0; at < length; at += 6) {
            float end = Math.min(length, at + 3);
            line(buffer, pose, x0 + (x1 - x0) * at / length, y0 + (y1 - y0) * at / length,
                x0 + (x1 - x0) * end / length, y0 + (y1 - y0) * end / length, 1, color);
        }
    }

    private static void line(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1,
                             float width, int color) {
        float length = (float) Math.hypot(x1 - x0, y1 - y0);
        if (length == 0) {
            return;
        }
        float nx = -(y1 - y0) / length * width / 2;
        float ny = (x1 - x0) / length * width / 2;
        quad(buffer, pose, x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, color);
    }

    private static void outline(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1,
                                float width, int color) {
        fill(buffer, pose, x0, y0, x1, y0 + width, color);
        fill(buffer, pose, x0, y1 - width, x1, y1, color);
        fill(buffer, pose, x0, y0 + width, x0 + width, y1 - width, color);
        fill(buffer, pose, x1 - width, y0 + width, x1, y1 - width, color);
    }

    private static void circle(VertexConsumer buffer, Matrix4f pose, float x, float y, float radius, int color) {
        int steps = 20;
        for (int step = 0; step < steps; step++) {
            double a = Math.PI * 2 * step / steps;
            double b = Math.PI * 2 * (step + 1) / steps;
            float bx = x + (float) Math.cos(b) * radius;
            float by = y + (float) Math.sin(b) * radius;
            quad(buffer, pose, x, y, x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius, bx, by,
                bx, by, color);
        }
    }

    private static void ring(VertexConsumer buffer, Matrix4f pose, float x, float y, float radius, float width,
                             int color) {
        int steps = 24;
        float outer = radius + width / 2;
        float inner = radius - width / 2;
        for (int step = 0; step < steps; step++) {
            double a = Math.PI * 2 * step / steps;
            double b = Math.PI * 2 * (step + 1) / steps;
            quad(buffer, pose, x + (float) Math.cos(a) * inner, y + (float) Math.sin(a) * inner,
                x + (float) Math.cos(a) * outer, y + (float) Math.sin(a) * outer,
                x + (float) Math.cos(b) * outer, y + (float) Math.sin(b) * outer,
                x + (float) Math.cos(b) * inner, y + (float) Math.sin(b) * inner, color);
        }
    }

    private static void fill(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1,
                             int color) {
        quad(buffer, pose, x0, y0, x0, y1, x1, y1, x1, y0, color);
    }

    // GuiGraphics.fill winds its corners one way; every quad here is turned to match in case faces are culled.
    private static void quad(VertexConsumer buffer, Matrix4f pose, float ax, float ay, float bx, float by,
                             float cx, float cy, float dx, float dy, int color) {
        float area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy);
        if (area > 0) {
            quad(buffer, pose, dx, dy, cx, cy, bx, by, ax, ay, color);
            return;
        }
        buffer.addVertex(pose, ax, ay, 0).setColor(color);
        buffer.addVertex(pose, bx, by, 0).setColor(color);
        buffer.addVertex(pose, cx, cy, 0).setColor(color);
        buffer.addVertex(pose, dx, dy, 0).setColor(color);
    }
}
