package io.github.izakyl.folkways.core.card.net;

import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Carries out a press on a facility's card. The press names the element by its place on the card and by what it binds,
 * and is refused as {@code hidden} unless the card the server holds has that element there now, so a client can do
 * nothing the card does not offer.
 */
public final class CardOrders {

    /** A press: which element, and what it chose. */
    public sealed interface Press {
        int index();

        String name();

        record Run(int index, String name) implements Press {
        }

        /** A target element picked a place or an entity in the world. */
        record Pick(int index, String name, Value picked) implements Press {
        }

        record Choose(int index, String name, int option) implements Press {
        }

        record Step(int index, String name, int direction) implements Press {
        }

        /** A flag, an item list or a typed number set to a value. */
        record Set(int index, String name, Value value) implements Press {
        }

        record Toggle(int index, String name, boolean on) implements Press {
        }

        /** One of a listed row's acts: the row and act by place, and the routine the act runs. */
        record Act(int index, String name, int row, int act) implements Press {
        }
    }

    private CardOrders() {
    }

    /** What a refusal key starts with when it is a translated reason of its own, such as an order's. */
    public static final String REASON = "folkways.refusal.";

    /**
     * Why the press was refused, as a notice ({@link #REASON} and a translation key when the facility said
     * why), or empty when it was carried out.
     */
    public static Optional<Notice> apply(ServerPlayer player, Operating operating, Press press) {
        var card = operating.card().elements();
        int index = press.index();
        if (index < 0 || index >= card.size()) {
            return refused("hidden");
        }
        Card.Element element = card.get(index);
        String name = press.name();
        List<DefinedFacilities.Problem> problems;
        switch (press) {
            case Press.Run ignored -> {
                if (!(element instanceof Card.Element.Button button) || !button.routine().equals(name)) {
                    return refused("hidden");
                }
                var disabled = operating.disabled(name, button.enabledWhen());
                if (disabled.isPresent()) {
                    return refused("disabled." + disabled.get());
                }
                problems = operating.run(player, name, Map.of());
            }
            case Press.Pick pick -> {
                if (!(element instanceof Card.Element.Target target) || !target.routine().equals(name)) {
                    return refused("hidden");
                }
                if (!fits(target.pick(), pick.picked())) {
                    return refused("pick");
                }
                var disabled = operating.disabled(name, target.enabledWhen());
                if (disabled.isPresent()) {
                    return refused("disabled." + disabled.get());
                }
                Map<String, Value> arguments = new LinkedHashMap<>(target.arguments());
                arguments.put("target", pick.picked());
                problems = operating.run(player, name, arguments);
            }
            case Press.Choose choose -> {
                if (!(element instanceof Card.Element.Choice choice) || !choice.param().equals(name)
                    || choose.option() < 0 || choose.option() >= choice.options().size()) {
                    return refused("hidden");
                }
                var disabled = operating.disabled(choice.run().orElse(""), choice.enabledWhen());
                if (disabled.isPresent()) {
                    return refused("disabled." + disabled.get());
                }
                Value selected = new Value.Text(choice.options().get(choose.option()));
                problems = operating.setParameter(name, selected);
                if (problems.isEmpty() && choice.run().isPresent()) {
                    problems = operating.run(player, choice.run().get(), Map.of(name, selected));
                }
            }
            case Press.Step step -> {
                if (!(element instanceof Card.Element.Number number) || !number.param().equals(name)
                    || step.direction() != 1 && step.direction() != -1) {
                    return refused("hidden");
                }
                Value current = operating.parameters().get(name);
                if (current == null || !number.accepts(current)) {
                    return refused("parameter");
                }
                problems = operating.setParameter(name, number.adjust(current, step.direction()));
            }
            case Press.Set set -> {
                boolean settable = element instanceof Card.Element.Flag || element instanceof Card.Element.Items
                    || element instanceof Card.Element.Number number && number.typed();
                if (!settable || !((Card.Element.Parameter) element).param().equals(name)) {
                    return refused("hidden");
                }
                if (!((Card.Element.Parameter) element).accepts(set.value())) {
                    return refused("parameter");
                }
                problems = operating.setParameter(name, set.value());
            }
            case Press.Toggle toggle -> {
                if (!(element instanceof Card.Element.Toggle switchable) || !switchable.trigger().equals(name)) {
                    return refused("hidden");
                }
                problems = operating.toggle(name, toggle.on());
            }
            case Press.Act act -> {
                if (!(element instanceof Card.Element.Rows list) || !list.source().equals(name)) {
                    return refused("hidden");
                }
                ServerLevel level = operating.loaded(player.getServer()).orElse(null);
                if (level == null) {
                    return refused("unloaded");
                }
                Map<String, Value> taken = new LinkedHashMap<>();
                operating.facility().readings(level)
                    .forEach((reading, source) -> source.read(level).ifPresent(value -> taken.put(reading, value)));
                var rows = operating.facility().rows(level, list.source(), taken);
                if (act.row() < 0 || act.row() >= rows.size() || act.act() < 0
                    || act.act() >= rows.get(act.row()).acts().size()
                    || !(rows.get(act.row()).acts().get(act.act()) instanceof Card.Act.Run run)) {
                    return refused("hidden");
                }
                var disabled = operating.disabled(run.routine(), Map.of());
                if (disabled.isPresent()) {
                    return refused("disabled." + disabled.get());
                }
                problems = operating.run(player, run.routine(), run.arguments());
            }
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(refusal(problems.getFirst()));
    }

    private static Optional<Notice> refused(String reason) {
        return Optional.of(new Notice(reason, List.of()));
    }

    // A refusal that names its own translated reason is told by that reason; any other by its kind.
    private static Notice refusal(DefinedFacilities.Problem problem) {
        if (problem.reason().equals("refused") && problem.detail().startsWith(REASON)
            && problem.detail().length() <= Notice.MAX_KEY) {
            return new Notice(problem.detail(), problem.arguments());
        }
        return new Notice(problem.reason(), List.of());
    }

    private static boolean fits(Card.Pick kind, Value picked) {
        return switch (picked) {
            case Value.Text ignored -> kind.acceptsEntity(true);
            case Value.Vector vector -> kind == Card.Pick.BOX ? vector.values().size() == 6
                : vector.values().size() == 3 && kind.acceptsPosition();
            default -> false;
        };
    }
}
