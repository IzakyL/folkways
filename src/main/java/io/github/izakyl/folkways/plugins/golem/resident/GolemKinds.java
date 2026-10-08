package io.github.izakyl.folkways.plugins.golem.resident;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.perk.GlobalPerks;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.ResidentKind.Growth;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.plugins.golem.domain.GolemContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public final class GolemKinds {

    public static final ResourceLocation METAL =
        ResourceLocation.fromNamespaceAndPath("modulargolems", "metal_golem");
    public static final ResourceLocation HUMANOID =
        ResourceLocation.fromNamespaceAndPath("modulargolems", "humanoid_golem");
    public static final ResourceLocation DOG =
        ResourceLocation.fromNamespaceAndPath("modulargolems", "dog_golem");

    private static final ResourceLocation HAULING = folkways("hauling");
    private static final ResourceLocation BUILDING = folkways("building");
    private static final ResourceLocation CRAFTING = folkways("crafting");
    private static final ResourceLocation CONDUCTING = folkways("conducting");
    private static final ResourceLocation DISPATCHING = folkways("dispatching");
    private static final ResourceLocation PATROLLING = folkways("patrolling");
    private static final ResourceLocation PATROL = folkways("patrol");
    private static final ResourceLocation GOLEM = folkways("golem");
    private static final ResourceLocation FEAR = folkways("fear");
    private static final ResourceLocation TRAIN = folkways("train");

    private static final Map<ResourceLocation, List<ResourceLocation>> TRADES = Map.of(
        METAL, List.of(HAULING, BUILDING, PATROLLING),
        HUMANOID, List.of(HAULING, BUILDING, CRAFTING, CONDUCTING, DISPATCHING, PATROLLING),
        DOG, List.of(HAULING, PATROLLING));

    // Metal and dog golems fight with their own body; a humanoid golem swings whatever it is handed.
    private static final Map<ResourceLocation, Set<ResourceLocation>> BAREHANDED = Map.of(
        METAL, Set.of(PATROLLING),
        HUMANOID, Set.of(),
        DOG, Set.of(PATROLLING));

    public static final ResidentKind METAL_GOLEM = golem(METAL,
        new Growth.Built(Map.of(GlobalPerks.PACKMULE, 2, GlobalPerks.LONGARM, 2)));

    public static final ResidentKind HUMANOID_GOLEM = golem(HUMANOID,
        new Growth.Built(Map.of(GlobalPerks.PACKMULE, 1)));

    public static final ResidentKind DOG_GOLEM = golem(DOG,
        new Growth.Built(Map.of(GlobalPerks.BRISK, 3)));

    public static final List<ResidentKind> ALL = List.of(METAL_GOLEM, HUMANOID_GOLEM, DOG_GOLEM);

    private GolemKinds() {
    }

    public static void declare(Declaring declaring) {
        ALL.forEach(declaring::residentKind);
    }

    public static void enroll(Registering registering) {
        GolemAdmission handover = new GolemAdmission();
        for (ResidentKind kind : ALL) {
            registering.admission(GolemContent.BODY, kind.id(), handover);
        }
    }

    private static ResidentKind golem(ResourceLocation body, Growth growth) {
        return new ResidentKind(body, growth, Locomotions.named(Locomotions.WALKING))
            .embodiedBy((kind, entity) -> entity instanceof AbstractGolemEntity<?, ?> golem
                ? GolemBody.of(golem, kind)
                : Optional.empty())
            .taking(rows(body))
            .barehanded(BAREHANDED.get(body));
    }

    private static Map<Stake, Participation> rows(ResourceLocation body) {
        Map<Stake, Participation> rows = new LinkedHashMap<>();
        rows.put(Stake.urge(GOLEM), Participation.ALWAYS);
        rows.put(Stake.urge(FEAR), Participation.ALWAYS);
        rows.put(Stake.urge(PATROL), Participation.ALWAYS);
        for (ResourceLocation trade : TRADES.get(body)) {
            rows.put(Stake.vocation(trade), Participation.OPTIONAL);
        }
        rows.put(Stake.passage(TRAIN), Participation.OPTIONAL);
        return rows;
    }

    private static ResourceLocation folkways(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}
