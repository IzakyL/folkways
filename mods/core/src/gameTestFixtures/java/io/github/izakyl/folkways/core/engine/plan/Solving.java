package io.github.izakyl.folkways.core.engine.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Both stages of planning run one after the other on the test's own thread: each round is woven, then laid out at
// once, and the plan is kept whole as any reader of its changes keeps it.
public final class Solving {

    private final Planner planner;
    private final Scheduler scheduler = new Scheduler();
    private final WeaveView plan = new WeaveView();
    private Map<UUID, Tour> tours = Map.of();
    private List<Message> feedback = List.of();

    public Solving() {
        this(new Claims(), new Cooldowns());
    }

    public Solving(Claims claims, Cooldowns cooldowns) {
        this.planner = new Planner(claims, cooldowns);
    }

    public Planned handle(Known known, List<Message> inbox, long tick) {
        List<Message> batch = new ArrayList<>(feedback);
        batch.addAll(inbox);
        Planner.Woven woven = planner.handle(known, batch, tick, tours);
        plan.apply(woven.delta());
        Scheduler.Scheduled laid = scheduler.handle(List.of(
            new Scheduler.Input(woven.delta(), woven.crew(), woven.ways(), woven.retry(), tick)));
        tours = laid.all();
        feedback = laid.stow().stream().<Message>map(Message.PackBusy::new).toList();
        return new Planned(woven, laid, Shown.of(plan, woven.delta()));
    }

    public WeaveView plan() {
        return plan;
    }

    public void closed() {
        planner.closed();
        scheduler.closed();
        plan.clear();
        tours = Map.of();
        feedback = List.of();
    }
}
