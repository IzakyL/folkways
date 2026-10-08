package io.github.izakyl.folkways.fuzz;

import io.github.izakyl.folkways.core.engine.plan.LiveEconomy;
import io.github.izakyl.folkways.core.engine.travel.graph.LivePaths;
import io.github.izakyl.folkways.plugins.build.LiveBuild;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * The in-game half of a live fuzz campaign, called by Blockwright tasks from tests/fuzz/fuzz-kit.ts between tick
 * segments: {@code open} lays a case out on a plot, {@code step} lands what is due on every open case and says
 * which have settled, {@code close} judges one and clears its plot. The ticks themselves are the server's own,
 * advanced by the campaign, so residents walk, fetch, wait and work as they do in play.
 */
public final class Live {

    private static final Map<String, Supplier<LiveTarget>> TARGETS = Map.of(
        "build", LiveBuild::new,
        "economy", LiveEconomy::new,
        "paths", LivePaths::new);

    private static final Map<String, Open> OPEN = new ConcurrentHashMap<>();

    private record Open(LiveTarget target, Plot plot, Map<String, Object> kase, LiveTarget.Run run,
                        List<Violation> seen) {
    }

    private Live() {
    }

    private static LiveTarget target(Map<String, Object> args) {
        String name = Cases.text(args, "target", "");
        Supplier<LiveTarget> made = TARGETS.get(name);
        if (made == null) {
            throw new IllegalArgumentException("no live fuzz target " + name + "; there are " + TARGETS.keySet());
        }
        return made.get();
    }

    /** Draws a case ({@code seed}, {@code index}) or takes one ({@code case}), and lays it on plot {@code plot}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> open(MinecraftServer server, Map<String, Object> args) {
        LiveTarget target = target(args);
        Map<String, Object> kase = args.get("case") instanceof Map<?, ?> given
            ? (Map<String, Object>) given
            : target.draw(new Rng(Rng.mix(Long.parseLong(Cases.text(args, "seed", "0")), Cases.num(args, "index", 0))));
        Plot plot = Plot.of(server.overworld(), Cases.num(args, "plot", 0), target.half(kase));
        String id = Cases.text(args, "id", target.name() + "@" + plot.index());
        Open stale = OPEN.remove(id);
        if (stale != null) {
            stale.run().close();
        }
        plot.claim();
        List<Violation> seen = new ArrayList<>();
        LiveTarget.Run run;
        try {
            run = target.open(plot, kase);
        } catch (Throwable error) {
            plot.clear();
            return Cases.map("id", id, "case", kase, "opened", false,
                "violations", reported(List.of(Violation.threw(error))));
        }
        OPEN.put(id, new Open(target, plot, kase, run, seen));
        return Cases.map("id", id, "case", kase, "opened", true,
            "budget", run.budget() > 0 ? run.budget() : target.budget());
    }

    /** Lands what is due on each case in {@code ids}, {@code elapsed} ticks in, and says which have settled. */
    public static Map<String, Object> step(MinecraftServer server, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> elapsed = args.get("elapsed") instanceof Map<?, ?> each ? cast(each) : Map.of();
        for (Object name : Cases.list(args, "ids")) {
            String id = String.valueOf(name);
            Open open = OPEN.get(id);
            if (open == null) {
                out.put(id, Cases.map("state", "gone"));
                continue;
            }
            long now = Cases.num(elapsed, id, 0);
            int before = open.seen().size();
            try {
                open.run().step(now, open.seen());
            } catch (Throwable error) {
                open.seen().add(Violation.threw(error));
            }
            boolean broke = open.seen().size() > before;
            boolean settled;
            try {
                settled = open.run().settled(now);
            } catch (Throwable error) {
                open.seen().add(Violation.threw(error));
                settled = true;
            }
            out.put(id, Cases.map("state", broke ? "broke" : settled ? "settled" : "running"));
        }
        return out;
    }

    /** Judges a case, clears its plot and answers what went wrong along the way and at the end. */
    public static Map<String, Object> close(MinecraftServer server, Map<String, Object> args) {
        String id = Cases.text(args, "id", "");
        Open open = OPEN.remove(id);
        if (open == null) {
            return Cases.map("id", id, "violations", List.of(), "gone", true);
        }
        List<Violation> all = new ArrayList<>(open.seen());
        Map<String, Object> summary = Map.of();
        try {
            all.addAll(open.run().judge(Cases.flag(args, "settled"), Cases.num(args, "elapsed", 0)));
            summary = open.run().summary();
        } catch (Throwable error) {
            all.add(Violation.threw(error));
        } finally {
            try {
                open.run().close();
            } catch (Throwable error) {
                all.add(new Violation("live.close-threw", Violation.threw(error).detail()));
            }
            open.plot().clear();
        }
        // One violation of each kind per case: the first says what happened, the rest only repeat it.
        Map<String, Violation> kinds = new LinkedHashMap<>();
        for (Violation violation : all) {
            kinds.putIfAbsent(violation.kind(), violation);
        }
        return Cases.map("id", id, "violations", reported(List.copyOf(kinds.values())), "summary", summary);
    }

    /** The one-move shrinks of a case, the biggest cuts first, at most {@code limit} of them. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> candidates(Map<String, Object> args) {
        List<Object> all = Shrink.candidates(args.get("case"));
        int limit = Cases.num(args, "limit", 64);
        return Cases.map("candidates", all.subList(0, Math.min(limit, all.size())));
    }

    private static List<Object> reported(List<Violation> violations) {
        List<Object> out = new ArrayList<>();
        for (Violation violation : violations) {
            out.add(Cases.map("kind", violation.kind(), "detail", violation.detail()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
