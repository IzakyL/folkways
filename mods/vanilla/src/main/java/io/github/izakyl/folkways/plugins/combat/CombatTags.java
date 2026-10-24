package io.github.izakyl.folkways.plugins.combat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/** Data-pack policy layered over native attack AI and neutral-state checks. */
public final class CombatTags {
    public static final TagKey<EntityType<?>> RESIDENT_ENEMIES = tag("resident_enemies");
    public static final TagKey<EntityType<?>> PATROL_TARGETS = tag("patrol_targets");
    public static final TagKey<EntityType<?>> IGNORED = tag("ignored_resident_enemies");

    private CombatTags() { }

    private static TagKey<EntityType<?>> tag(String name) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("folkways", name));
    }
}
