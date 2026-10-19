package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.effect.perk.Perks;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class Bodies {

    private static final Map<ResidentKind, Perks> BUILT = new ConcurrentHashMap<>();

    private static final ResourceLocation SPEED_MODIFIER =
        ResourceLocation.fromNamespaceAndPath("folkways", "effect_speed");
    private static final ResourceLocation REACH_MODIFIER =
        ResourceLocation.fromNamespaceAndPath("folkways", "effect_reach");

    private Bodies() {
    }

    public static Optional<Body> of(Entity entity) {
        return ResidentKinds.of(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
            .flatMap(kind -> kind.read(entity));
    }

    public static void credit(Body body, Vocation vocation, int amount, RandomSource random) {
        if (!(body.kind().growth() instanceof ResidentKind.Growth.Climbs)) {
            return;
        }
        body.mob().getData(FolkwaysAttachments.BODY_STATE)
            .credit(vocation.id(), amount, random);
        body.refreshEffects();
    }

    // Sets the body's attributes and pack to what its effects make them.
    public static void carry(Body body) {
        Effects effects = body.effects();
        body.pack().applyCapacityMultiplier(effects.value(Stats.PACK_ROOM));
        modify(body, Attributes.BLOCK_INTERACTION_RANGE, REACH_MODIFIER, effects.value(Stats.REACH),
            AttributeModifier.Operation.ADD_VALUE);
        modify(body, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER, effects.value(Stats.MOVE_SPEED) - 1.0,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    // Takes off what effects put on the body's attributes. The pack keeps its cells, and what is in them.
    public static void shed(Body body) {
        modify(body, Attributes.BLOCK_INTERACTION_RANGE, REACH_MODIFIER, 0.0, AttributeModifier.Operation.ADD_VALUE);
        modify(body, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER, 0.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    private static void modify(Body body, Holder<Attribute> attribute, ResourceLocation id, double amount,
                               AttributeModifier.Operation operation) {
        AttributeInstance instance = body.mob().getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        if (amount != 0.0) {
            instance.addTransientModifier(new AttributeModifier(id, amount, operation));
        }
    }

    static Perks perksOf(ResidentKind kind, BodyState state) {
        if (kind.growth() instanceof ResidentKind.Growth.Built(Map<String, Integer> ranks)) {
            return BUILT.computeIfAbsent(kind, sort -> Perks.fixed(ranks));
        }
        return state.perks();
    }
}
