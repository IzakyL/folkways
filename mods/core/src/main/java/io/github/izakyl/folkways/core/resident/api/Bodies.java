package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Stats;
import java.util.Objects;
import java.util.Optional;
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

    public interface StateSource {

        BodyStateAccess state(Body body);

        Licences licences(Body body);
    }

    private static StateSource states;

    private static final ResourceLocation SPEED_MODIFIER =
        ResourceLocation.fromNamespaceAndPath("folkways", "effect_speed");
    private static final ResourceLocation REACH_MODIFIER =
        ResourceLocation.fromNamespaceAndPath("folkways", "effect_reach");

    private Bodies() {
    }

    public static void install(StateSource source) {
        states = Objects.requireNonNull(source);
    }

    static BodyStateAccess state(Body body) {
        return states.state(body);
    }

    static Licences licences(Body body) {
        return states.licences(body);
    }

    public static Optional<Body> of(Entity entity) {
        return ResidentKinds.of(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
            .flatMap(kind -> kind.read(entity));
    }

    public static void credit(Body body, Vocation vocation, int amount, RandomSource random) {
        if (!(body.kind().growth() instanceof ResidentKind.Growth.Climbs)) {
            return;
        }
        state(body).credit(vocation.id(), amount, random);
        body.refreshEffects();
    }

    public static void worked(Body body, Vocation vocation, int ticks, RandomSource random) {
        if (!(body.kind().growth() instanceof ResidentKind.Growth.Climbs)) {
            return;
        }
        state(body).worked(vocation.id(), ticks, random);
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
}
