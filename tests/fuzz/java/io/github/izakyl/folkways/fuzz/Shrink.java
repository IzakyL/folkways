package io.github.izakyl.folkways.fuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The moves a live campaign shrinks a failing case by, as delta debugging over case data. Two moves, both of
 * which a {@link LiveTarget} must accept: dropping a run of entries from a list of records (a list whose entries
 * are maps; number lists are tuples and stay whole), and lowering a number kept under a {@code "count"} key. The
 * campaign runs the candidates side by side on free plots and starts over from the first that fails the same way.
 */
public final class Shrink {

    private Shrink() {
    }

    /** Every one-move shrink of {@code tree}, the biggest cuts first. */
    static List<Object> candidates(Object tree) {
        List<List<Object>> paths = new ArrayList<>();
        List<List<Object>> counts = new ArrayList<>();
        walk(tree, new ArrayList<>(), paths, counts);
        List<Object> out = new ArrayList<>();
        for (int chunkDiv = 1; chunkDiv <= 64; chunkDiv *= 2) {
            for (List<Object> path : paths) {
                int size = ((List<?>) at(tree, path)).size();
                int chunk = Math.max(1, size / chunkDiv);
                if (chunkDiv > 1 && chunk == Math.max(1, size / (chunkDiv / 2))) {
                    continue;
                }
                for (int from = 0; from < size; from += chunk) {
                    Object copy = Cases.copy(tree);
                    List<?> list = (List<?>) at(copy, path);
                    list.subList(from, Math.min(size, from + chunk)).clear();
                    out.add(copy);
                }
            }
        }
        for (List<Object> path : counts) {
            int value = Cases.num(at(tree, path), 0);
            for (int lower : new int[] {1, value / 2, value - 1}) {
                if (lower >= 1 && lower < value) {
                    Object copy = Cases.copy(tree);
                    put(copy, path, lower);
                    out.add(copy);
                }
            }
        }
        return out;
    }

    private static void walk(Object tree, List<Object> path, List<List<Object>> lists, List<List<Object>> counts) {
        if (tree instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                path.add(entry.getKey());
                if ("count".equals(entry.getKey()) && entry.getValue() instanceof Number number && number.longValue() > 1) {
                    counts.add(List.copyOf(path));
                }
                walk(entry.getValue(), path, lists, counts);
                path.removeLast();
            }
        } else if (tree instanceof List<?> list) {
            if (!list.isEmpty() && list.stream().allMatch(item -> item instanceof Map<?, ?>)) {
                lists.add(List.copyOf(path));
            }
            for (int i = 0; i < list.size(); i++) {
                path.add(i);
                walk(list.get(i), path, lists, counts);
                path.removeLast();
            }
        }
    }

    private static Object at(Object tree, List<Object> path) {
        Object here = tree;
        for (Object step : path) {
            here = step instanceof Integer index ? ((List<?>) here).get(index) : ((Map<?, ?>) here).get(step);
        }
        return here;
    }

    @SuppressWarnings("unchecked")
    private static void put(Object tree, List<Object> path, Object value) {
        Object parent = at(tree, path.subList(0, path.size() - 1));
        Object last = path.getLast();
        if (last instanceof Integer index) {
            ((List<Object>) parent).set(index, value);
        } else {
            ((Map<String, Object>) parent).put((String) last, value);
        }
    }
}
