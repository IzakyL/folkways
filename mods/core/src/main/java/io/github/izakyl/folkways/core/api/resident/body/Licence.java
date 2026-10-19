package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.vocation.Vocation;
import java.util.Objects;

public final class Licence {

    private final Vocation vocation;
    private final Keenness keenness;

    Licence(Vocation vocation, Keenness keenness) {
        this.vocation = vocation;
        this.keenness = keenness;
    }

    public Vocation vocation() {
        return vocation;
    }

    public Keenness keenness() {
        return keenness;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Licence licence
            && vocation.equals(licence.vocation)
            && keenness == licence.keenness;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vocation, keenness);
    }

    @Override
    public String toString() {
        return vocation.id() + "@" + keenness.number();
    }
}
