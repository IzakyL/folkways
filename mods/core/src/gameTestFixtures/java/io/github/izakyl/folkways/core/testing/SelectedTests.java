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
        Set<String> selected = Arrays.stream(System.getProperty("folkways.testMethods", "").split(","))
            .filter(name -> !name.isBlank()).collect(Collectors.toSet());
        Arrays.stream(type.getDeclaredMethods()).filter(method -> selected.isEmpty()
            || selected.contains(method.getName())).forEach(event::register);
    }
}
