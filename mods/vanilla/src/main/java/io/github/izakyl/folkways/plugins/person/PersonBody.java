package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Relating;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.passage.Passages;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PersonBody {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident");

    private static final ResourceLocation LIVING = folkways("living");
    private static final ResourceLocation FEAR = folkways("fear");
    private static final ResourceLocation PATROL = folkways("patrol");

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, FolkwaysMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<ResidentEntity>> RESIDENT =
        ENTITY_TYPES.register(
            "resident",
            id -> EntityType.Builder.of(ResidentEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.95F)
                .eyeHeight(1.74F)
                .vehicleAttachment(Player.DEFAULT_VEHICLE_ATTACHMENT)
                .clientTrackingRange(8)
                .build(id.toString())
        );

    private PersonBody() {
    }

    public static void declare(Declaring declaring) {
        declaring.residentKind(ResidentKind.growing(ID, Locomotions.named(Locomotions.WALKING)).taking(rows()));
    }

    public static ResidentKind person() {
        return ResidentKinds.of(ID).orElseThrow(() ->
            new IllegalStateException(ID + " is asked for before it has been declared"));
    }

    private static Map<Stake, Participation> rows() {
        Map<Stake, Participation> rows = new LinkedHashMap<>();
        rows.put(Stake.urge(LIVING), Participation.ALWAYS);
        rows.put(Stake.urge(FEAR), Participation.ALWAYS);
        rows.put(Stake.urge(PATROL), Participation.ALWAYS);
        return rows;
    }

    public static void relate(Relating relating) {
        for (ResourceLocation trade : Vocations.ids()) {
            relating.defaultParticipation(ID, Stake.vocation(trade), Participation.OPTIONAL);
        }
        for (ResourceLocation way : Passages.ids()) {
            relating.defaultParticipation(ID, Stake.passage(way), Participation.OPTIONAL);
        }
    }

    private static ResourceLocation folkways(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path);
    }
    public static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(RESIDENT.get(), ResidentEntity.createAttributes().build());
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
