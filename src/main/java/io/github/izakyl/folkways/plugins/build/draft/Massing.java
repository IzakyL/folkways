package io.github.izakyl.folkways.plugins.build.draft;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Massing(List<Part> parts) {

    public Massing {
        parts = List.copyOf(parts);
    }

    public record Part(String role, Set<String> labels, Solid solid, Skin skin, Over over, String path) {

        public Part {
            role = Objects.requireNonNull(role, "role");
            labels = Set.copyOf(labels);
            solid = Objects.requireNonNull(solid, "solid");
            skin = Objects.requireNonNull(skin, "skin");
            over = Objects.requireNonNull(over, "over");
            path = path == null || path.isEmpty() ? role : path;
        }

        public Part(String role, Solid solid, Skin skin) {
            this(role, Set.of(), solid, skin, Over.ALL, role);
        }

        boolean known(String label) {
            return role.equals(label) || labels.contains(label);
        }

        boolean knownAny(Set<String> names) {
            if (names.contains(role)) {
                return true;
            }
            for (String label : labels) {
                if (names.contains(label)) {
                    return true;
                }
            }
            return false;
        }

        Part at(String madeAt) {
            return new Part(role, labels, solid, skin, over, madeAt);
        }
    }

    public record Over(Kind kind, Set<String> labels) {

        public static final Over ALL = new Over(Kind.ALL, Set.of());
        public static final Over EMPTY = new Over(Kind.EMPTY, Set.of());

        public enum Kind { ALL, EMPTY, LABELS }

        public Over {
            labels = Set.copyOf(labels);
        }

        boolean takes(Part existing) {
            return switch (kind) {
                case ALL -> true;
                case EMPTY -> existing.skin() instanceof Skin.Cleared;
                case LABELS -> existing.skin() instanceof Skin.Cleared || existing.knownAny(labels);
            };
        }
    }
}
