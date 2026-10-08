package io.github.izakyl.folkways.core.api.perk;

public record Perk(String id, int maxRank) {

    public Perk {
        if (maxRank < 1) {
            throw new IllegalArgumentException("perk " + id + " has no rank, so a draw of it grants nothing");
        }
    }
}
