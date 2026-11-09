package io.github.izakyl.folkways.core.front.api.ui;

import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.client.ZoneSelection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

    public interface FocusedTool {
        ResourceLocation id();

        default ResourceLocation mode() {
            return BUILD;
        }

        String nameKey();

        ResourceLocation icon();

        void tick(UUID colony);

        /** Read-only overlay refresh, including while another mode owns input. */
        default void backgroundTick(Optional<UUID> colony) {
        }

        default void reset() {
            leave();
        }

        List<Action> actions();

        List<Mark> marks();

        Component status();

        default Object selectionKey() {
            return id();
        }

        void use(Optional<Box> box, Optional<Mark> aimed);

        default void leave() {
        }
    }

    public record Box(BlockPos min, BlockPos max) {
    }

    public static Optional<Box> box() {
        return ZoneSelection.marked().map(marked -> new Box(marked.min(), marked.max()));
    }

    public static boolean drafting() {
        return ZoneSelection.drafting();
    }

    public static void clearBox() {
        ZoneSelection.clear();
    }

    public static void discardBox() {
        ZoneSelection.discard();
    }

    public record Action(Component name, boolean on, Runnable run) {
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
    private static final Map<ResourceLocation, FocusedTool> focusedTools = new LinkedHashMap<>();
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

    public static boolean selectMode(ResourceLocation id) {
        if (!modes.containsKey(id)) {
            return false;
        }
        if (mode.equals(id)) {
            return true;
        }
        deselect();
        mode = id;
        selectionRevision++;
        tools.stream().filter(tool -> tool.mode().equals(id)).findFirst().ifPresent(tool -> select(tool.id()));
        return true;
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

    /** Focused plugin tools share the toolbar and exclusive selection with tactical tools. */
    public static synchronized void register(FocusedTool tool) {
        Objects.requireNonNull(tool);
        register(new Tool(tool.id(), tool.mode(), tool.nameKey(), tool.icon(), Camera.STANDARD, tool.nameKey(),
            () -> tool.use(box(), Optional.empty())));
        focusedTools.put(tool.id(), tool);
    }

    public static List<FocusedTool> all() {
        return List.copyOf(focusedTools.values());
    }

    public static Optional<FocusedTool> active() {
        return Optional.ofNullable(focusedTools.get(selected));
    }

    public static void select(FocusedTool tool) {
        select(tool.id());
    }

    /** Leaving the overhead view clears focused state; tactical selection keeps its existing lifecycle. */
    public static void leave() {
        if (active().isPresent()) {
            deselect();
        }
    }

    public static List<Tool> tools() {
        return tools;
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
        focusedTools.values().forEach(FocusedTool::reset);
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
            if (focusedTools.containsKey(id)) {
                ZoneSelection.clear();
            }
            selectionRevision++;
        }
        selected = id;
        mode = selected().orElseThrow().mode();
        return true;
    }

    public static void deselect() {
        selectedHandler().ifPresent(Handler::cancel);
        FocusedTool old = active().orElse(null);
        if (selected != null) {
            selectionRevision++;
        }
        selected = null;
        mode = BUILD;
        if (old != null) {
            ZoneSelection.clear();
            old.leave();
        }
    }
}
