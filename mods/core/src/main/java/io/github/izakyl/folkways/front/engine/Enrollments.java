package io.github.izakyl.folkways.front.engine;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.Admission;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Facings;
import io.github.izakyl.folkways.front.api.Registering;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    private static final Map<ResourceLocation, Function<Colony, Optional<Facing>>> FACINGS =
        new LinkedHashMap<>();

    private Enrollments() {
    }

    public static void gather() {
        Facings.install(new Facings.Source() {
            public Optional<Facing> of(Colony colony, ResourceLocation owner) {
                Function<Colony, Optional<Facing>> provider = FACINGS.get(owner);
                return provider == null
                    ? Optional.empty()
                    : provider.apply(colony);
            }

            public List<Facing> all(Colony colony) {
                var owners = new LinkedHashSet<ResourceLocation>();
                owners.addAll(BY_OWNER.keySet());
                owners.addAll(FACINGS.keySet());
                List<Facing> found = new ArrayList<>();
                owners.forEach(owner -> of(colony, owner).ifPresent(found::add));
                return List.copyOf(found);
            }
        });
        Registering registering = new Registering(new Registering.Registry() {
            public void enrollment(ResourceLocation owner, Enrollment enrollment) {
                register(owner, enrollment);
            }
            public void admission(ResourceLocation owner, ResourceLocation residentKind, Admission how) {
                admit(owner, residentKind, how);
            }
            public void facing(ResourceLocation owner, Function<Colony, Optional<Facing>> provider) {
                if (FACINGS.putIfAbsent(owner, provider) != null) {
                    throw new IllegalStateException(owner + " already supplies a front view");
                }
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

    public static Enrollment of(ResourceLocation owner) {
        return BY_OWNER.getOrDefault(owner, Enrollment.none());
    }

    public static Map<ResourceLocation, Enrollment> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(BY_OWNER));
    }

    public static List<Delegation> delegations() {
        return List.copyOf(DELEGATIONS.values());
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
