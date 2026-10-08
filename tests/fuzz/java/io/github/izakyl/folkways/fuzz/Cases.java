package io.github.izakyl.folkways.fuzz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reading and copying case data as it comes back from JSON: numbers may be Long, Integer or Double. */
public final class Cases {

    private Cases() {
    }

    public static int num(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public static int num(Map<String, Object> from, String key, int fallback) {
        return num(from.get(key), fallback);
    }

    public static double real(Map<String, Object> from, String key, double fallback) {
        return from.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    public static boolean flag(Map<String, Object> from, String key) {
        return Boolean.TRUE.equals(from.get(key));
    }

    public static String text(Map<String, Object> from, String key, String fallback) {
        return from.get(key) instanceof String text ? text : fallback;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> from, String key) {
        return from.get(key) instanceof List<?> list ? (List<Object>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Map<String, Object> from, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list(from, key)) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    public static int[] ints(Map<String, Object> from, String key) {
        List<Object> list = list(from, key);
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = num(list.get(i), 0);
        }
        return out;
    }

    /** An int list as case data. */
    public static List<Object> of(int... values) {
        List<Object> out = new ArrayList<>(values.length);
        for (int value : values) {
            out.add(value);
        }
        return out;
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Object copy(Object tree) {
        if (tree instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put((String) key, copy(value)));
            return out;
        }
        if (tree instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(copy(item));
            }
            return out;
        }
        return tree;
    }

    /** Leaves and lists in a tree, for measuring how much a shrink took off. */
    public static int size(Object tree) {
        if (tree instanceof Map<?, ?> map) {
            int total = 0;
            for (Object value : map.values()) {
                total += size(value);
            }
            return total;
        }
        if (tree instanceof List<?> list) {
            int total = 1;
            for (Object item : list) {
                total += size(item);
            }
            return total;
        }
        return 1;
    }
}
