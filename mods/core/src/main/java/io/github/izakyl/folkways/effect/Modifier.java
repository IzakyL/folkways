package io.github.izakyl.folkways.effect;

// One change an effect makes. A stat is its base with every Add summed on, then times every Multiply; a flag holds
// while some Grant reaches it and no Forbid does.
public sealed interface Modifier {

    Scope scope();

    record Add(Stat stat, double amount, Scope scope) implements Modifier {
    }

    record Multiply(Stat stat, double factor, Scope scope) implements Modifier {
    }

    record Grant(Flag flag, Scope scope) implements Modifier {
    }

    record Forbid(Flag flag, Scope scope) implements Modifier {
    }

    static Modifier add(Stat stat, double amount) {
        return new Add(stat, amount, Scope.ANY);
    }

    static Modifier multiply(Stat stat, double factor) {
        return new Multiply(stat, factor, Scope.ANY);
    }

    static Modifier grant(Flag flag) {
        return new Grant(flag, Scope.ANY);
    }

    static Modifier forbid(Flag flag) {
        return new Forbid(flag, Scope.ANY);
    }
}
