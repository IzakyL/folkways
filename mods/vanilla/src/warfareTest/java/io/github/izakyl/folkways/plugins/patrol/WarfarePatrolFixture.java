package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.Tags;

/** Exercises Vanilla's actual patrol observation path without exposing its internal service. */
public final class WarfarePatrolFixture {

    private WarfarePatrolFixture() {
    }

    public static void observe(Colony colony, ServerLevel level, Function<List<Node>, Worker> worker) {
        PatrolPresence patrol = colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow();
        patrol.refresh(level.getServer());
        Worker scout = worker.apply(List.of());
        if (LoadoutProfiles.of(colony).assigned(scout.resident().id()).isEmpty()) {
            arm(colony, (ResidentEntity) scout.body(), "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
        }
        var route = Beats.charted(Fronts.of(colony), level).keySet().stream().sorted().findFirst().orElseThrow();
        PatrolStaffing.of(colony).configure(level, route,
            Set.of(LoadoutProfiles.of(colony).assigned(scout.resident().id()).orElseThrow().id()), 1);
        patrol.tick(level.getServer());
        patrol.urges(scout, colony.view(level));
    }

    private static void arm(Colony colony, Body body, String name, ItemSpec weapon) {
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        LoadoutProfile profile =
            profiles.all().stream()
            .filter(one -> one.name().equals(name))
            .findFirst()
            .orElseGet(() -> profiles.add(profiles.create(name).id(), LoadoutProfile.Entry.weapon(weapon)));
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }
}
