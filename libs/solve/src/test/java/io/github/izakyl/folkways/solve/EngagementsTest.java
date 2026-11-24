package io.github.izakyl.folkways.solve;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class EngagementsTest {

    @Test
    public void spreadsShotsAfterEnoughDamageIsCommitted() {
        var near = candidate("near", 1, 0, false, false);
        var far = candidate("far", 2, 0, false, false);
        assertEquals(Map.of("a", "near", "b", "far"),
            Engagements.allocate(List.of(unit("a", near, far), unit("b", near, far))));
    }

    @Test
    public void focusMarkOverridesOverkillAndDistance() {
        var near = candidate("near", 1, 0, false, false);
        var marked = candidate("marked", 2, 0, false, true);
        assertEquals(Map.of("a", "marked", "b", "marked"),
            Engagements.allocate(List.of(unit("a", near, marked), unit("b", near, marked))));
    }

    @Test
    public void indirectFirePrefersGroupsAtEqualThreat() {
        var near = candidate("near", 1, 0, true, false);
        var group = candidate("group", 2, 3, true, false);
        assertEquals(Map.of("a", "group"), Engagements.allocate(List.of(unit("a", near, group))));
    }

    @Test
    public void firesEnoughShotsToCoverToughTargets() {
        var tough = new Engagements.Target<>("tough", 30, 2, 0, false);
        var near = candidate("near", 1, 0, false, false);
        var far = new Engagements.Candidate<>(tough, 2, 0, 0, false);
        assertEquals(Map.of("a", "tough", "b", "tough", "c", "near"),
            Engagements.allocate(List.of(unit("a", near, far), unit("b", near, far), unit("c", near, far))));
    }

    private static Engagements.Candidate<String> candidate(String id, double distance, int neighbors,
                                                           boolean indirect, boolean marked) {
        return new Engagements.Candidate<>(new Engagements.Target<>(id, 20, 1, 0, marked),
            distance, 0, neighbors, indirect);
    }

    @SafeVarargs
    private static Engagements.Unit<String, String> unit(String id, Engagements.Candidate<String>... candidates) {
        return new Engagements.Unit<>(id, true, 20, 20, List.of(candidates));
    }
}
