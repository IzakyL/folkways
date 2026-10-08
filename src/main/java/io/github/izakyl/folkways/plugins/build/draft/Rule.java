package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;

import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;

@StarlarkBuiltin(name = "rule", doc = "A named rule, expanded in its phase.")
public record Rule(String name, StarlarkCallable fn, String phase, Set<String> labels, int priority,
                   Map<String, Object> attrs) implements StarlarkCallable {

    public Rule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fn, "fn");
        labels = Set.copyOf(labels);
        attrs = Map.copyOf(attrs);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
            throws EvalException, InterruptedException {
        Derivation drawing = Derivation.of(thread);
        if (args.isEmpty() || !(args.get(0) instanceof Scope scope)) {
            throw Starlark.errorf("rule '%s' is called on a scope first, got %s", name,
                args.isEmpty() ? "nothing" : Starlark.type(args.get(0)));
        }
        Set<String> named = new LinkedHashSet<>(labels);
        Map<String, Object> handed = new LinkedHashMap<>(attrs);
        String called = "";
        int order = priority;
        Map<String, Object> rest = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
            switch (entry.getKey()) {
                case "label" -> named.add(Starlark.str(entry.getValue()));
                case "labels" -> named.addAll(strings(entry.getValue()));
                case "name" -> called = Starlark.str(entry.getValue());
                case "attrs" -> handed.putAll(attributes(entry.getValue()));
                case "priority" -> order = Starlark.toInt(entry.getValue(), "priority");
                default -> rest.put(entry.getKey(), entry.getValue());
            }
        }
        int at = drawing.phaseOf(this);
        return new Node(this, scope, List.copyOf(args.subList(1, args.size())), rest, named, called, at, order,
            handed, null, "");
    }

    private static Map<String, Object> attributes(Object written) throws EvalException {
        if (!(written instanceof Dict<?, ?> dict)) {
            throw Starlark.errorf("attrs is a dict of names to values, got %s", Starlark.type(written));
        }
        return Vocabulary.attrsOf(dict);
    }

    static List<String> strings(Object written) throws EvalException {
        if (written instanceof String one) {
            return List.of(one);
        }
        if (!(written instanceof Sequence<?> many)) {
            throw Starlark.errorf("labels are a string or a list of strings, got %s", Starlark.type(written));
        }
        List<String> all = new ArrayList<>();
        for (Object each : many) {
            all.add(Starlark.str(each));
        }
        return all;
    }

    @Override
    public boolean isImmutable() {
        return true;
    }

    @Override
    public void repr(Printer printer) {
        printer.append("<rule " + name + ">");
    }

    @StarlarkBuiltin(name = "node", doc = "A rule called on a scope, waiting for its phase.")
    public record Node(Rule rule, Scope scope, List<Object> args, Map<String, Object> kwargs, Set<String> labels,
                       String name, int phase, int priority, Map<String, Object> attrs, Node parent, String path)
        implements StarlarkValue {

        public Node {
            args = List.copyOf(args);
            kwargs = Map.copyOf(kwargs);
            labels = Set.copyOf(labels);
            attrs = Map.copyOf(attrs);
        }

        String segment() {
            if (!name.isEmpty()) {
                return name;
            }
            return scope.name().isEmpty() ? rule.name() : rule.name() + ":" + scope.name();
        }

        Node placed(Node under, String at) {
            return new Node(rule, scope, args, kwargs, labels, name, phase, priority, attrs, under, at);
        }

        boolean within(Node ancestor) {
            for (Node step = this; step != null; step = step.parent) {
                if (step == ancestor) {
                    return true;
                }
            }
            return false;
        }

        Object attribute(String wanted) {
            for (Node step = this; step != null; step = step.parent) {
                Object found = step.attrs.get(wanted);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        @Override
        public boolean isImmutable() {
            return true;
        }

        @Override
        public void repr(Printer printer) {
            printer.append("<" + rule.name() + (path.isEmpty() ? "" : " at " + path) + ">");
        }
    }
}
