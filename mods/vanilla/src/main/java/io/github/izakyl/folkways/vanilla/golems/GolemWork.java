package io.github.izakyl.folkways.vanilla.golems;

import io.github.izakyl.folkways.core.extension.api.Relating;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.shell.api.GolemKindIds;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

public final class GolemWork {

    private GolemWork() {
    }

    public static void install(IEventBus bus) {
        bus.addListener(Relating.class, GolemWork::relate);
    }

    private static void relate(Relating relating) {
        ResourceLocation patrolling = id("patrolling");
        for (ResourceLocation kind : List.of(GolemKindIds.METAL, GolemKindIds.HUMANOID, GolemKindIds.DOG)) {
            relating.participation(kind, Stake.urge(id("fear")), Participation.ALWAYS);
            relating.participation(kind, Stake.urge(id("patrol")), Participation.ALWAYS);
            relating.participation(kind, Stake.vocation(patrolling), Participation.OPTIONAL);
        }
        allow(relating, GolemKindIds.METAL, "building");
        allow(relating, GolemKindIds.HUMANOID, "building", "crafting", "conducting", "dispatching");
        relating.barehanded(GolemKindIds.METAL, patrolling);
        relating.barehanded(GolemKindIds.DOG, patrolling);
    }

    private static void allow(Relating relating, ResourceLocation kind, String... trades) {
        for (String trade : trades) {
            relating.participation(kind, Stake.vocation(id(trade)), Participation.OPTIONAL);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysVanillaMod.ID, path);
    }
}
