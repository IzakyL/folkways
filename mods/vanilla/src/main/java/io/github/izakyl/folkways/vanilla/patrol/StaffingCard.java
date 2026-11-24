package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.HoldingKind.Held;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The staffing controls on a patrol route's or sentry zone's card: how many residents it takes in all, and which
 * equipment profiles may fill it, each profile a row allowed or not. A route also offers to set out again after its
 * team stalled.
 */
final class StaffingCard {
    static final String COUNT = "staffing_count";
    static final String PROFILES = "staffing_profiles";
    static final String ALLOW = "staffing_allow";
    static final String RETRY = "staffing_retry";
    private static final String KEY = "folkways.staffing.";
    private static final String PROFILE = "profile";
    private static final String ON = "on";
    private static final String REJECTED = "folkways.refusal.staffing.rejected";

    private StaffingCard() {
    }

    static List<Card.Element> elements(boolean route) {
        List<Card.Element> elements = new ArrayList<>();
        elements.add(new Card.Element.Heading(new Card.Label(KEY + "title", true)));
        elements.add(new Card.Element.Number(COUNT, 0, PatrolStaffing.MAX_COUNT, 1,
            Optional.of(ResourceLocation.withDefaultNamespace("iron_sword")),
            Optional.of(new Card.Label(KEY + "headcount_label", true)), Optional.of(0.0), true));
        elements.add(new Card.Element.Heading(new Card.Label(KEY + "allowed", true)));
        elements.add(new Card.Element.Rows(PROFILES, Optional.of(new Card.Label(KEY + "none_defined", true))));
        if (route) {
            elements.add(new Card.Element.Button(RETRY, Optional.of(ResourceLocation.withDefaultNamespace("compass")),
                Map.of(), Optional.of(new Card.Label(KEY + "retry", true))));
        } else {
            elements.add(new Card.Element.Note(new Card.Label(KEY + "sentry_hint", true)));
        }
        return List.copyOf(elements);
    }

    static Optional<Value> parameter(Colony colony, Held held, String key) {
        return key.equals(COUNT) ? Optional.of(Value.of(PatrolStaffing.of(colony).count(held.id()))) : Optional.empty();
    }

    static Optional<List<DefinedFacilities.Problem>> setParameter(Colony colony, Held held, String key, Value value) {
        if (!key.equals(COUNT)) {
            return Optional.empty();
        }
        if (!(value instanceof Value.Real real) || real.value() != Math.rint(real.value())) {
            return Optional.of(List.of(new DefinedFacilities.Problem(key, "parameter", key)));
        }
        PatrolStaffing staffing = PatrolStaffing.of(colony);
        return Optional.of(configure(held, key, (level, target) ->
            staffing.configure(level, target, staffing.allowed(target), (int) real.value())));
    }

    static List<Card.Row> rows(Colony colony, Held held, String source) {
        if (!source.equals(PROFILES)) {
            return List.of();
        }
        Set<UUID> allowed = PatrolStaffing.of(colony).allowed(held.id());
        List<Card.Row> rows = new ArrayList<>();
        for (LoadoutProfile profile : LoadoutProfiles.of(colony).all()) {
            boolean on = allowed.contains(profile.id());
            rows.add(Card.Row.of(new ItemStack(Items.IRON_CHESTPLATE), Line.text(Component.literal(profile.name())),
                    List.of(Line.text(Component.translatable(KEY + (on ? "profile_allowed" : "profile_barred")))))
                .with(new Card.Act.Run(ALLOW, Map.of(PROFILE, new Value.Text(profile.id().toString()),
                    ON, Value.of(!on)), new Card.Label(KEY + (on ? "bar" : "allow"), true), Optional.empty())));
        }
        return List.copyOf(rows);
    }

    static Optional<List<DefinedFacilities.Problem>> act(Colony colony, Held held, String routine,
            Map<String, Value> arguments) {
        PatrolStaffing staffing = PatrolStaffing.of(colony);
        return switch (routine) {
            case ALLOW -> {
                UUID profile;
                try {
                    profile = arguments.get(PROFILE) instanceof Value.Text text ? UUID.fromString(text.value()) : null;
                } catch (IllegalArgumentException malformed) {
                    profile = null;
                }
                if (profile == null || !(arguments.get(ON) instanceof Value.Bool on)) {
                    yield Optional.of(List.of(new DefinedFacilities.Problem(PROFILE, "invalid", "")));
                }
                UUID chosen = profile;
                yield Optional.of(configure(held, routine, (level, target) -> {
                    Set<UUID> next = new HashSet<>(staffing.allowed(target));
                    if (on.on()) {
                        next.add(chosen);
                    } else {
                        next.remove(chosen);
                    }
                    staffing.configure(level, target, next, staffing.count(target));
                }));
            }
            case RETRY -> {
                if (!held.delegation().equals(PatrolContent.BEAT)) {
                    yield Optional.empty();
                }
                yield Optional.of(colony.service(PatrolContent.ID, PatrolPresence.class).map(presence -> {
                    presence.teams().retry(held.id());
                    return List.<DefinedFacilities.Problem>of();
                }).orElseGet(() -> List.of(new DefinedFacilities.Problem(routine, "refused", routine))));
            }
            default -> Optional.empty();
        };
    }

    private interface Change {
        void apply(ServerLevel level, UUID target);
    }

    // Staffing is changed in the level the route or zone lies in; one that is not loaded, or a change it turns down
    // (a profile deleted meanwhile, a headcount out of bounds), is refused.
    private static List<DefinedFacilities.Problem> configure(Held held, String path, Change change) {
        Optional<ServerLevel> level = Optional.ofNullable(ServerLifecycleHooks.getCurrentServer())
            .flatMap(held.site()::level);
        if (level.isEmpty()) {
            return List.of(new DefinedFacilities.Problem(path, "unloaded", ""));
        }
        try {
            change.apply(level.get(), held.id());
        } catch (IllegalArgumentException refused) {
            return List.of(new DefinedFacilities.Problem(path, "refused", REJECTED));
        }
        return List.of();
    }
}
