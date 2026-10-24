package io.github.izakyl.folkways.front.api.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Optional tools for the book's overhead view; selecting a tool grants no server authority. */
public final class OverheadTools {
    public enum Camera { STANDARD, TACTICAL }

    /**
     * Optional client interaction for a selected tool. Coordinates are GUI pixels, as in Screen. A consumed
     * press owns its drag and release; returning true prevents the book's ordinary click or use action.
     * Install implementations during client setup only. Tools without a handler retain their old behavior.
     */
    public interface Handler {
        /** Key hints for this tool while it is selected in the overhead view. */
        default List<Cue> cues() { return List.of(); }
        /** Space reserved at the bottom of the overhead view for this tool's controls. */
        default int bottomInset() { return 0; }
        default boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }
        default boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            return false;
        }
        default boolean mouseReleased(double mouseX, double mouseY, int button) { return false; }
        /** A consumed key is latched until release; held-key repeats do not invoke the handler again. */
        default boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
        default void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
        /** Only the pointer gesture was replaced, for example by a camera drag. */
        default void cancelPointer() { cancel(); }
        /** The tool or its overhead screen was left; abandon unfinished pointer gestures. */
        default void cancel() { }
    }

    public record Tool(ResourceLocation id, String nameKey, ResourceLocation icon, Camera camera,
                       String actionKey, Runnable open) {
        public Tool {
            Objects.requireNonNull(id); Objects.requireNonNull(nameKey); Objects.requireNonNull(icon);
            Objects.requireNonNull(camera); Objects.requireNonNull(actionKey); Objects.requireNonNull(open);
        }
    }

    private static volatile List<Tool> tools = List.of();
    private static final Map<ResourceLocation, Handler> handlers = new HashMap<>();
    private static ResourceLocation selected;
    private static long selectionRevision;

    private OverheadTools() { }

    static synchronized void register(Tool tool) {
        Objects.requireNonNull(tool);
        if (tools.stream().anyMatch(existing -> existing.id().equals(tool.id()))) {
            throw new IllegalArgumentException("overhead tool already registered: " + tool.id());
        }
        List<Tool> next = new ArrayList<>(tools);
        next.add(tool);
        tools = List.copyOf(next);
    }

    public static List<Tool> tools() { return tools; }
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
    public static Optional<Handler> selectedHandler() {
        return selected().map(tool -> handlers.get(tool.id()));
    }
    /** Changes even if a tool is left and selected again between two screen events. */
    public static long selectionRevision() { return selectionRevision; }
    public static boolean select(ResourceLocation id) {
        if (tools.stream().noneMatch(tool -> tool.id().equals(id))) { return false; }
        if (!Objects.equals(selected, id)) {
            selectedHandler().ifPresent(Handler::cancel);
            selectionRevision++;
        }
        selected = id;
        return true;
    }
    public static void deselect() {
        selectedHandler().ifPresent(Handler::cancel);
        if (selected != null) { selectionRevision++; }
        selected = null;
    }
}
