package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.api.resident.body.BodyActions;
import io.github.izakyl.folkways.epicfight.client.EpicFightClient;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.registry.entries.EpicFightAttributes;

/** Optional combat renderer and weapon implementation for Folkways' human residents. */
@Mod(FolkwaysEpicFight.ID)
public final class FolkwaysEpicFight {
    public static final String ID = "folkways_epicfight";

    public FolkwaysEpicFight(IEventBus modBus) {
        EpicFightEventHooks.Registry.ENTITY_PATCH.registerEvent(event -> {
            Armatures.registerEntityTypeArmature(PersonBody.RESIDENT.get(), Armatures.BIPED);
            event.registerEntityPatch(PersonBody.RESIDENT.get(), ResidentEpicFightPatch::new);
        }, ID + ":resident");
        modBus.addListener(FolkwaysEpicFight::attributes);
        modBus.addListener(FolkwaysEpicFight::setup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            EpicFightClient.install(modBus);
        }
    }

    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Armatures.registerEntityTypeArmature(PersonBody.RESIDENT.get(), Armatures.BIPED);
            Weapons.register(ResourceLocation.fromNamespaceAndPath(ID, "melee"), EpicFightWeaponAdapter.INSTANCE);
            LoadoutRegistry.register(ResourceLocation.fromNamespaceAndPath(ID, "melee"), EpicFightLoadoutProvider.INSTANCE);
            BodyActions.register(ResourceLocation.fromNamespaceAndPath(ID, "epicfight_labor"), body -> {
                if (body instanceof ResidentEntity resident) {
                    ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(resident);
                    return patch == null || !patch.blocksLabor();
                }
                return true;
            });
        });
    }

    private static void attributes(EntityAttributeModificationEvent event) {
        // Resident is MISC, so do not depend on EF discovering it in the vanilla humanoid list.
        add(event, Attributes.ATTACK_SPEED);
        add(event, EpicFightAttributes.WEIGHT);
        add(event, EpicFightAttributes.IMPACT);
        add(event, EpicFightAttributes.ARMOR_NEGATION);
        add(event, EpicFightAttributes.MAX_STRIKES);
        add(event, EpicFightAttributes.STUN_ARMOR);
        add(event, EpicFightAttributes.ASSASSINATION_RESISTANCE);
        add(event, EpicFightAttributes.OFFHAND_ATTACK_SPEED);
        add(event, EpicFightAttributes.OFFHAND_MAX_STRIKES);
        add(event, EpicFightAttributes.OFFHAND_ARMOR_NEGATION);
        add(event, EpicFightAttributes.OFFHAND_IMPACT);
    }

    private static void add(EntityAttributeModificationEvent event, Holder<Attribute> attribute) {
        if (!event.has(PersonBody.RESIDENT.get(), attribute)) {
            event.add(PersonBody.RESIDENT.get(), attribute);
        }
    }
}
