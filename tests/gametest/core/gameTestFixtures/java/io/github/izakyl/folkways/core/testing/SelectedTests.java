package io.github.izakyl.folkways.core.testing;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Explicit registration lets a failed native fixture rerun without repeating successful methods. */
public final class SelectedTests {

    private SelectedTests() {
    }

    public static void register(RegisterGameTestsEvent event, Class<?> type) {
        Set<String> classes = selection("folkways.testClasses");
        if (!classes.isEmpty() && !classes.contains(type.getSimpleName())) {
            return;
        }
        Set<String> selected = selection("folkways.testMethods");
        Arrays.stream(type.getDeclaredMethods()).filter(method -> selected.isEmpty()
            || selected.contains(method.getName())).forEach(event::register);
    }

    private static Set<String> selection(String property) {
        return Arrays.stream(System.getProperty(property, "").split(","))
            .map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toSet());
    }
}
