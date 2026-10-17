package io.github.izakyl.folkways.core.engine.travel.graph;

import java.util.Arrays;

final class StrongComponents {

    private StrongComponents() {
    }

    static int[] of(long[] cells, int[] head, int[] to) {
        int count = cells.length;
        boolean[] seen = new boolean[count];
        int[] stack = new int[count];
        int[] order = new int[count];
        int[] next = head.clone();
        int finished = 0;
        for (int seed = 0; seed < count; seed++) {
            if (seen[seed]) {
                continue;
            }
            int depth = 0;
            seen[seed] = true;
            stack[depth++] = seed;
            while (depth > 0) {
                int here = stack[depth - 1];
                if (next[here] < head[here + 1]) {
                    int beside = to[next[here]++];
                    if (!seen[beside]) {
                        seen[beside] = true;
                        stack[depth++] = beside;
                    }
                } else {
                    order[finished++] = here;
                    depth--;
                }
            }
        }
        int[] incoming = new int[count + 1];
        for (int target : to) {
            incoming[target + 1]++;
        }
        for (int id = 0; id < count; id++) {
            incoming[id + 1] += incoming[id];
        }
        int[] from = new int[to.length];
        int[] filled = incoming.clone();
        for (int id = 0; id < count; id++) {
            for (int edge = head[id]; edge < head[id + 1]; edge++) {
                from[filled[to[edge]]++] = id;
            }
        }
        int[] group = new int[count];
        int[] representative = new int[count];
        Arrays.fill(group, -1);
        for (int at = finished - 1; at >= 0; at--) {
            int seed = order[at];
            if (group[seed] >= 0) {
                continue;
            }
            int depth = 0;
            int least = seed;
            group[seed] = seed;
            stack[depth++] = seed;
            while (depth > 0) {
                int here = stack[--depth];
                if (cells[here] < cells[least]) {
                    least = here;
                }
                for (int edge = incoming[here]; edge < incoming[here + 1]; edge++) {
                    int beside = from[edge];
                    if (group[beside] < 0) {
                        group[beside] = seed;
                        stack[depth++] = beside;
                    }
                }
            }
            representative[seed] = least;
        }
        for (int id = 0; id < count; id++) {
            group[id] = representative[group[id]];
        }
        return group;
    }
}
