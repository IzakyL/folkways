package io.github.izakyl.folkways.core.front.engine;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.Admission;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.GhostSource;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.MemberPointing;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.ResidentLines;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModLoader;

public final class Enrollments {

    private static final Map<ResourceLocation, Enrollment> BY_OWNER = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Delegation> DELEGATIONS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Admitting> ADMISSIONS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Function<Colony, Optional<ColonyPanel>>> PANELS =
        new LinkedHashMap<>();
    private static final Map<ResourceLocation, List<Function<Colony, Optional<HoldingKind>>>> KINDS =
        new LinkedHashMap<>();

    private static final Map<ResourceLocation, Function<Colony, Optional<ResidentLines>>> RESIDENT_LINES =
        new LinkedHashMap<>();

    private static final Map<ResourceLocation, Function<Colony, Optional<GhostSource>>> GHOST_SOURCES =
        new LinkedHashMap<>();

    private static final Map<ResourceLocation, Function<Colony, Optional<MemberPointing>>> MEMBER_POINTING =
        new LinkedHashMap<>();

    private Enrollments() {
    }

    public static void gather() {
        Registering registering = new Registering(new Registering.Registry() {
            public void enrollment(ResourceLocation owner, Enrollment enrollment) {
                register(owner, enrollment);
            }
            public void admission(ResourceLocation owner, ResourceLocation residentKind, Admission how) {
                admit(owner, residentKind, how);
            }
            public void residentLines(ResourceLocation owner, Function<Colony, Optional<ResidentLines>> provider) {
                if (RESIDENT_LINES.putIfAbsent(owner, provider) != null) {
                    throw new IllegalStateException(owner + " already registers residentLines");
                }
            }

            public void ghostSource(ResourceLocation owner, Function<Colony, Optional<GhostSource>> provider) {
                if (GHOST_SOURCES.putIfAbsent(owner, provider) != null) {
                    throw new IllegalStateException(owner + " already registers ghostSource");
                }
            }

            public void memberPointing(ResourceLocation owner, Function<Colony, Optional<MemberPointing>> provider) {
                if (MEMBER_POINTING.putIfAbsent(owner, provider) != null) {
                    throw new IllegalStateException(owner + " already registers memberPointing");
                }
            }

            public void panel(ResourceLocation owner, Function<Colony, Optional<ColonyPanel>> panel) {
                if (PANELS.putIfAbsent(owner, panel) != null) {
                    throw new IllegalStateException(owner + " already shows a panel");
                }
            }
            public void holding(ResourceLocation delegation, Function<Colony, Optional<HoldingKind>> kind) {
                KINDS.computeIfAbsent(delegation, ignored -> new java.util.ArrayList<>()).add(kind);
            }
        });
        try {
            ModLoader.postEventWrapContainerInModOrder(registering);
        } finally {
            registering.close();
        }
    }

    static void register(ResourceLocation owner, Enrollment enrollment) {
        if (BY_OWNER.putIfAbsent(owner, enrollment) != null) {
            throw new IllegalStateException(owner + " has already enrolled with the front");
        }
        for (Delegation delegation : enrollment.delegations()) {
            int limit = switch (delegation.shape()) {
                case Shape.Volume volume -> volume.maxCells();
                case Shape.Path path -> path.maxPoints();
                default -> 0;
            };
            if (limit > 0) {
                Mechanics.kind(new Mechanics.Kind(delegation.id(), Mechanics.named("delegation", delegation.id()),
                        Mechanics.named("mechanics.zone", delegation.id()),
                        delegation.shape() instanceof Shape.Path ? "path" : "zone", limit));
            }
            if (delegation.shape() instanceof io.github.izakyl.folkways.core.front.api.Shape.Point point) {
                io.github.izakyl.folkways.core.colony.internal.PointSites.register(delegation.id(), point.accepts()::test);
            }
            if (delegation.shape() instanceof io.github.izakyl.folkways.core.front.api.Shape.Block block
                && block.fallback()) {
                io.github.izakyl.folkways.core.colony.internal.PointSites.register(delegation.id(),
                    (level, pos) -> block.accepts().test(level, pos)
                        && io.github.izakyl.folkways.core.inventory.api.port.StoreAccess.ledger(level, pos));
            }
            if (DELEGATIONS.putIfAbsent(delegation.id(), delegation) != null) {
                throw new IllegalStateException(delegation.id() + " is already declared");
            }
        }
    }

    static void admit(ResourceLocation owner, ResourceLocation residentKind, Admission how) {
        if (ADMISSIONS.putIfAbsent(residentKind, new Admitting(owner, how)) != null) {
            throw new IllegalStateException(residentKind + " already says what taking one in means");
        }
    }

    public static Map<ResourceLocation, ResidentLines> residentLines(Colony colony) {
        Map<ResourceLocation, ResidentLines> found = new LinkedHashMap<>();
        RESIDENT_LINES.forEach((owner, provider) -> provider.apply(colony).ifPresent(value -> found.put(owner, value)));
        return found;
    }

    public static Map<ResourceLocation, GhostSource> ghostSource(Colony colony) {
        Map<ResourceLocation, GhostSource> found = new LinkedHashMap<>();
        GHOST_SOURCES.forEach((owner, provider) -> provider.apply(colony).ifPresent(value -> found.put(owner, value)));
        return found;
    }

    public static Map<ResourceLocation, MemberPointing> memberPointing(Colony colony) {
        Map<ResourceLocation, MemberPointing> found = new LinkedHashMap<>();
        MEMBER_POINTING.forEach((owner, provider) ->
            provider.apply(colony).ifPresent(value -> found.put(owner, value)));
        return found;
    }

    public static Enrollment of(ResourceLocation owner) {
        return BY_OWNER.getOrDefault(owner, Enrollment.none());
    }

    public static Map<ResourceLocation, Enrollment> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(BY_OWNER));
    }

    public static List<Delegation> delegations() {
        return List.copyOf(DELEGATIONS.values());
    }

    /** The modules that show something on the book's own pages, by owner, in registration order. */
    public static Map<ResourceLocation, ColonyPanel> panels(Colony colony) {
        Map<ResourceLocation, ColonyPanel> found = new LinkedHashMap<>();
        PANELS.forEach((owner, panel) -> panel.apply(colony).ifPresent(held -> found.put(owner, held)));
        return found;
    }

    /** What each module registered for this delegation makes of the colony's holdings, in registration order. */
    public static List<HoldingKind> kinds(Colony colony, ResourceLocation delegation) {
        return KINDS.getOrDefault(delegation, List.of()).stream()
            .flatMap(kind -> kind.apply(colony).stream()).toList();
    }

    public static Optional<Delegation> delegation(ResourceLocation id) {
        return Optional.ofNullable(DELEGATIONS.get(id));
    }

    // Who takes in a body of this kind, and for whom the colony then holds it.
    public record Admitting(ResourceLocation owner, Admission how) {
    }

    public static Optional<Admitting> admission(ResourceLocation residentKind) {
        return Optional.ofNullable(ADMISSIONS.get(residentKind));
    }
}
