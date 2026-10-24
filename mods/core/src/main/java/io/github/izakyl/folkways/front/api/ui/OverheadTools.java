package io.github.izakyl.folkways.front.api.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Optional tools for the book's overhead view; selecting a tool grants no server authority. */
public final class OverheadTools {
    public enum Camera { STANDARD, TACTICAL }

    public record Tool(ResourceLocation id, String nameKey, ResourceLocation icon, Camera camera,
                       String actionKey, Runnable open) {
        public Tool {
            Objects.requireNonNull(id); Objects.requireNonNull(nameKey); Objects.requireNonNull(icon);
            Objects.requireNonNull(camera); Objects.requireNonNull(actionKey); Objects.requireNonNull(open);
        }
    }

    private static volatile List<Tool> tools = List.of();
    private static ResourceLocation selected;

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
    public static boolean select(ResourceLocation id) {
        if (tools.stream().noneMatch(tool -> tool.id().equals(id))) { return false; }
        selected = id;
        return true;
    }
    public static void deselect() { selected = null; }
}
