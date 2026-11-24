package io.github.izakyl.folkways.core.front.api.ui;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Optional tools for the book's overhead view; selecting a tool grants no server authority. */
public final class OverheadTools {
    public enum Camera {
        STANDARD,
        TACTICAL
    }

    /**
    * Optional client interaction for a selected tool. Coordinates are GUI pixels, as in Screen. A consumed
    * press owns its drag and release; returning true prevents the book's ordinary click or use action.
    * Install implementations during client setup only. Tools without a handler retain their old behavior.
    */
    public interface Handler {
        /** Key hints for this tool while it is selected in the overhead view. */
        default List<Cue> cues() {
            return List.of();
        }

        /** Keep only the tool's own short cues in the left rail; full help belongs in hover tooltips. */
        default boolean compactHints() {
            return false;
        }

        /** Whether Core may draw the resident's usual look card while this overhead tool is active. */
        default boolean showResidentCard(UUID resident) {
            return true;
        }

        /** Space reserved at the bottom of the overhead view for this tool's controls. */
        default int bottomInset() {
            return 0;
        }

        /**
         * The areas of the overhead view this tool's own controls cover now, such as its cards and docks, in GUI
         * pixels. The view's other controls, its key hints and hover cards keep clear of them.
         */
        default List<ScreenRectangle> reserved() {
            return List.of();
        }

        /** A pointer gesture can reserve the descent modifier without changing the camera's global bindings. */
        default boolean blocksCameraDescent() {
            return false;
        }

        default boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }

        default boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            return false;
        }

        default boolean mouseReleased(double mouseX, double mouseY, int button) {
            return false;
        }

        /** A consumed key is latched until release; held-key repeats do not invoke the handler again. */
        default boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return false;
        }

        /** Passive world information only; no controls or hit regions. */
        default void renderOverlay(GuiGraphics graphics, float partialTick) {
        }

        default void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }

        /** Only the pointer gesture was replaced, for example by a camera drag. */
        default void cancelPointer() {
            cancel();
        }

        /** The tool or its overhead screen was left; abandon unfinished pointer gestures. */
        default void cancel() {
        }
    }

    public record Tool(
        ResourceLocation id,
        ResourceLocation mode,
        String nameKey,
        ResourceLocation icon,
        Camera camera,
        String actionKey,
        Runnable open) {
        public Tool(ResourceLocation id, String nameKey, ResourceLocation icon, Camera camera,
                String actionKey, Runnable open) {
            this(id, BUILD, nameKey, icon, camera, actionKey, open);
        }

        /** A tool whose handler supplies all controls, without a separate action button. */
        public Tool(ResourceLocation id, ResourceLocation mode, String nameKey, ResourceLocation icon, Camera camera) {
            this(id, mode, nameKey, icon, camera, "", () -> {});
        }

        public Tool {
            Objects.requireNonNull(id);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(nameKey);
            Objects.requireNonNull(icon);
            Objects.requireNonNull(camera);
            Objects.requireNonNull(actionKey);
            Objects.requireNonNull(open);
        }
    }

    public record Mode(ResourceLocation id, String nameKey, ResourceLocation icon) {
        public Mode {
            Objects.requireNonNull(id);
            Objects.requireNonNull(nameKey);
            Objects.requireNonNull(icon);
        }
    }

    /**
     * Passive world marks a module keeps over what the colony holds, drawn while the book is bound and told when
     * hovered from above. They own no input: what they mark is opened and drawn like anything else the book holds.
     */
    public interface Marks {
        /** Read-only refresh, every client tick, for the colony the book is bound to if any. */
        default void tick(Optional<UUID> colony) {
        }

        default void reset() {
        }

        List<Mark> marks();
    }

    /** Coordinates use the level's storage frame, just like the ordinary box editor. */
    public record Mark(UUID area, BlockPos min, BlockPos max, int color, List<Line> lines) {
        public Mark {
            lines = List.copyOf(lines);
        }
    }

    public static final ResourceLocation BUILD = ResourceLocation.fromNamespaceAndPath("folkways_core", "build");
    private static final Map<ResourceLocation, Mode> modes = new LinkedHashMap<>();
    private static ResourceLocation mode = BUILD;

    static {
        registerMode(new Mode(BUILD, "folkways.overhead.mode.build",
            ResourceLocation.withDefaultNamespace("bricks")));
    }

    private static volatile List<Tool> tools = List.of();
    private static final Map<ResourceLocation, Handler> handlers = new HashMap<>();
    private static final List<Marks> marks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static ResourceLocation selected;
    private static long selectionRevision;

    private OverheadTools() {
    }

    public static synchronized void registerMode(Mode entry) {
        Objects.requireNonNull(entry);
        if (modes.putIfAbsent(entry.id(), entry) != null) {
            throw new IllegalArgumentException("overhead mode already registered: " + entry.id());
        }
    }

    public static List<Mode> modes() {
        return List.copyOf(modes.values());
    }

    public static ResourceLocation mode() {
        return mode;
    }

    static synchronized void register(Tool tool) {
        Objects.requireNonNull(tool);
        if (tools.stream().anyMatch(existing -> existing.id().equals(tool.id()))) {
            throw new IllegalArgumentException("overhead tool already registered: " + tool.id());
        }
        if (!modes.containsKey(tool.mode())) {
            throw new IllegalArgumentException("unknown overhead mode: " + tool.mode());
        }
        List<Tool> next = new ArrayList<>(tools);
        next.add(tool);
        tools = List.copyOf(next);
    }

    /** Passive marks are installed during client setup only. */
    public static void registerMarks(Marks source) {
        marks.add(Objects.requireNonNull(source));
    }

    public static List<Marks> marks() {
        return List.copyOf(marks);
    }

    /** Leaving the overhead view keeps a tactical selection, as it always has. */
    public static void leave() {
    }

    public static List<Tool> tools() {
        return tools;
    }

    /** The tools of one mode, in registration order. */
    public static List<Tool> tools(ResourceLocation mode) {
        return tools.stream().filter(tool -> tool.mode().equals(mode)).toList();
    }

    public static Optional<Tool> selected() {
        return tools.stream().filter(tool -> tool.id().equals(selected)).findFirst();
    }

    public static void registerHandler(ResourceLocation id, Handler handler) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(handler);
        if (handlers.putIfAbsent(id, handler) != null) {
            throw new IllegalArgumentException("overhead tool handler already registered: " + id);
        }
    }

    public static void renderOverlays(GuiGraphics graphics, float partialTick) {
        handlers.values().forEach(handler -> handler.renderOverlay(graphics, partialTick));
    }

    public static void reset() {
        deselect();
        marks.forEach(Marks::reset);
    }

    public static Optional<Handler> selectedHandler() {
        return selected().map(tool -> handlers.get(tool.id()));
    }

    /** Changes even if a tool is left and selected again between two screen events. */
    public static long selectionRevision() {
        return selectionRevision;
    }

    public static boolean select(ResourceLocation id) {
        if (tools.stream().noneMatch(tool -> tool.id().equals(id))) {
            return false;
        }
        if (!Objects.equals(selected, id)) {
            deselect();
            selectionRevision++;
        }
        selected = id;
        mode = selected().orElseThrow().mode();
        return true;
    }

    public static void deselect() {
        selectedHandler().ifPresent(Handler::cancel);
        if (selected != null) {
            selectionRevision++;
        }
        selected = null;
        mode = BUILD;
    }
}
