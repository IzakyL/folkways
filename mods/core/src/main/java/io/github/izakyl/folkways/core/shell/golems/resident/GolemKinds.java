package io.github.izakyl.folkways.core.shell.golems.resident;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.ResidentKind.Growth;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.effect.perk.GlobalPerks;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.shell.api.GolemKindIds;
import io.github.izakyl.folkways.core.shell.golems.domain.GolemContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class GolemKinds {

    private static final ResourceLocation HAULING = ResourceLocation.fromNamespaceAndPath("folkways_core", "hauling");
    private static final ResourceLocation GOLEM = ResourceLocation.fromNamespaceAndPath("folkways_core", "golem");
    private static final ResourceLocation TRAIN = ResourceLocation.fromNamespaceAndPath("folkways_core", "train");

    public static final ResidentKind METAL_GOLEM = golem(GolemKindIds.METAL,
        new Growth.Built(Map.of(GlobalPerks.GOLEM_PACK_ROOM, 2, GlobalPerks.LONGARM, 2)));

    public static final ResidentKind HUMANOID_GOLEM = golem(GolemKindIds.HUMANOID,
        new Growth.Built(Map.of(GlobalPerks.GOLEM_PACK_ROOM, 1)));

    public static final ResidentKind DOG_GOLEM = golem(GolemKindIds.DOG,
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
            .taking(rows());
    }

    private static Map<Stake, Participation> rows() {
        Map<Stake, Participation> rows = new LinkedHashMap<>();
        rows.put(Stake.urge(GOLEM), Participation.ALWAYS);
        rows.putAll(Commands.rows());
        rows.put(Stake.vocation(HAULING), Participation.OPTIONAL);
        rows.put(Stake.passage(TRAIN), Participation.OPTIONAL);
        return rows;
    }

}
