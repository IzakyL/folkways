package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.world.api.Stand;
import java.util.Map;
import java.util.UUID;

// Where and when the schedule last placed work on its route, read from the tours that settled it.
record Booked(Stand at, int start, int finish) {

    static Booked in(Tours tours, UUID id) {
        try {
            var field = Tours.class.getDeclaredField("settled");
            field.setAccessible(true);
            Object placed = ((Map<?, ?>) field.get(tours)).get(id);
            if (placed == null) {
                throw new AssertionError("work was not placed: " + id);
            }
            var at = placed.getClass().getDeclaredMethod("at");
            var start = placed.getClass().getDeclaredMethod("start");
            var finish = placed.getClass().getDeclaredMethod("finish");
            at.setAccessible(true);
            start.setAccessible(true);
            finish.setAccessible(true);
            return new Booked((Stand) at.invoke(placed), (int) start.invoke(placed), (int) finish.invoke(placed));
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    static Booked in(Solving planner, UUID id) {
        try {
            var scheduler = Solving.class.getDeclaredField("scheduler");
            scheduler.setAccessible(true);
            var tours = Scheduler.class.getDeclaredField("tours");
            tours.setAccessible(true);
            return in((Tours) tours.get(scheduler.get(planner)), id);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
