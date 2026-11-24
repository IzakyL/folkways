package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class DeskLaunchers {

    public interface Context {

        Pane window(String id, Component title, float width, float height, Function<Pane, UIElement> body);
    }

    public record Launcher(String id, ResourceLocation icon, String tooltip, Consumer<Context> action) {
    }

    private static final List<Launcher> LAUNCHERS = new ArrayList<>();

    private static Predicate<String> current = id -> false;

    private static Consumer<String> lone = id -> { };

    private DeskLaunchers() {
    }

    public static void register(Launcher launcher) {
        if (LAUNCHERS.stream().anyMatch(existing -> existing.id().equals(launcher.id()))) {
            throw new IllegalArgumentException("Duplicate desk launcher: " + launcher.id());
        }
        LAUNCHERS.add(launcher);
    }

    public static List<Launcher> all() {
        return List.copyOf(LAUNCHERS);
    }

    public static void install(Predicate<String> openCurrent, Consumer<String> openLone) {
        current = openCurrent;
        lone = openLone;
    }

    public static void open(String id) {
        if (!current.test(id)) {
            lone.accept(id);
        }
    }
}
