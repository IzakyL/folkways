package io.github.izakyl.folkways.core.testing;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.gametest.framework.TestFunction;

public final class SelectedTests {
    private static final Map<TestFunction, Method> METHODS = new IdentityHashMap<>();

    private SelectedTests() {
    }

    public static void record(TestFunction test, Method method) {
        METHODS.put(test, method);
    }

    public static Predicate<TestFunction> selector(String selection) {
        List<Predicate<TestFunction>> selectors = new ArrayList<>();
        int start = 0;
        while (start < selection.length()) {
            while (start < selection.length() && Character.isWhitespace(selection.charAt(start))) {
                start++;
            }
            if (start == selection.length()) {
                break;
            }
            boolean regex = selection.charAt(start) == '/';
            int end = start;
            if (regex) {
                end++;
                while (end < selection.length() && selection.charAt(end) != '/') {
                    end += selection.charAt(end) == '\\' ? 2 : 1;
                }
                if (end >= selection.length()) {
                    throw new IllegalArgumentException("Unclosed GameTest regex in " + selection);
                }
                Pattern pattern = Pattern.compile(selection.substring(start + 1, end), Pattern.CASE_INSENSITIVE);
                selectors.add(test -> pattern.matcher(qualifiedName(test)).matches());
                end++;
                while (end < selection.length() && Character.isWhitespace(selection.charAt(end))) {
                    end++;
                }
                if (end < selection.length() && selection.charAt(end) != ',') {
                    throw new IllegalArgumentException("Expected comma after GameTest regex in " + selection);
                }
            } else {
                end = selection.indexOf(',', start);
                if (end < 0) {
                    end = selection.length();
                }
                String name = selection.substring(start, end).trim();
                if (!name.isEmpty()) {
                    selectors.add(test -> matchesName(test, name));
                }
            }
            start = end + 1;
        }
        return test -> selectors.stream().anyMatch(selector -> selector.test(test));
    }

    private static String qualifiedName(TestFunction test) {
        Method method = METHODS.get(test);
        return method == null ? test.testName() : method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static boolean matchesName(TestFunction test, String name) {
        Method method = METHODS.get(test);
        if (method == null) {
            return name.equals(test.testName());
        }
        Class<?> type = method.getDeclaringClass();
        return name.equals(type.getName()) || name.equals(type.getSimpleName()) || name.equals(qualifiedName(test))
            || name.equals(type.getSimpleName() + "#" + method.getName());
    }
}
