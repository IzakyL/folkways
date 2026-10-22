package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.notice.Meter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Parrot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

public final class LivingContent {

    public static final ResourceLocation EATING = ResourceLocation.fromNamespaceAndPath("folkways", "eating");

    public static final ResourceLocation SLEEPING = ResourceLocation.fromNamespaceAndPath("folkways", "sleeping");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "living");

    public static final ResourceLocation BED =
        ResourceLocation.fromNamespaceAndPath("folkways", "bed");

    public static final ResourceLocation RECREATION = ResourceLocation.fromNamespaceAndPath("folkways", "recreation");

    public static final ResourceLocation DINING = ResourceLocation.fromNamespaceAndPath("folkways", "dining");
    public static final ResourceLocation SETTING_TABLE = ResourceLocation.fromNamespaceAndPath("folkways", "setting_table");
    public static final ResourceLocation DINING_EATING = ResourceLocation.fromNamespaceAndPath("folkways", "dining_eating");

    public static final ResourceLocation WHITELIST = ResourceLocation.fromNamespaceAndPath("folkways", "food_whitelist");
    public static final ResourceLocation BLACKLIST = ResourceLocation.fromNamespaceAndPath("folkways", "food_blacklist");

    public static final Schema.Setting FOOD_MODE = new Schema.Setting.Choice(
        "food_mode", "folkways.settings.food_mode", ResourceLocation.withDefaultNamespace("cooked_beef"),
        List.of(WHITELIST, BLACKLIST), WHITELIST);

    public static final Schema.Setting FOOD_SAFETY = new Schema.Setting.Flag(
        "food_safety", "folkways.settings.food_safety", ResourceLocation.withDefaultNamespace("shield"), true);

    public static final Schema.Setting DINING_FOOD = new Schema.Setting.Items(
        "food", "folkways.settings.dining.food", ResourceLocation.withDefaultNamespace("cake"),
        vanilla("bread", "cooked_beef", "cake"));
    public static final Schema.Setting DINING_DRINK = new Schema.Setting.Items(
        "drink", "folkways.settings.dining.drink", ResourceLocation.withDefaultNamespace("glass_bottle"), List.of());
    public static final Schema.Setting DINING_SERVINGS = new Schema.Setting.Count(
        "servings", "folkways.settings.dining.servings", ResourceLocation.withDefaultNamespace("bowl"), 1, 4, 4);

    public static final Schema.Setting FOOD = new Schema.Setting.Items(
        "food", "folkways.settings.food", ResourceLocation.withDefaultNamespace("cooked_beef"),
        List.of(ItemFilter.tag(ResourceLocation.fromNamespaceAndPath("folkways", "resident_food"))));

    // A resident's belly and sleep on his card: the survival HUD's drumstick, and our own moon for rest.
    static final Meter FULLNESS =
        new Meter(ResourceLocation.withDefaultNamespace("hud/food_full"), 0xFFC8813A);

    static final Meter ALERTNESS =
        new Meter(ResourceLocation.fromNamespaceAndPath("folkways", "hud/rest"), 0xFFE8D98C);

    public static void declare(Declaring declaring) {
        DiningTables.installKeepout();
        declaring.colony(ID, scope -> {
            LivingPresence state = new LivingPresence(scope.colony());
            scope.share(state);
            scope.urges(state::urges);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static Effects effects(Body body) {
        if (!(body.mob().level() instanceof ServerLevel level)) {
            return Effects.NONE;
        }
        return body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(colony -> colony.service(ID, LivingPresence.class))
            .map(living -> living.effects(body.id())).orElse(Effects.NONE);
    }

    public static List<Component> details(Body body) {
        if (!(body.mob().level() instanceof ServerLevel level)) { return List.of(); }
        return body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(colony -> colony.service(ID, LivingPresence.class).map(living -> living.details(body.id(), colony.view(level))))
            .orElseGet(List::of);
    }

    public static void blockChanged(BlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) { return; }
        for (Colony colony : Colonies.all(level.getServer())) {
            colony.service(ID, LivingPresence.class).ifPresent(living -> living.changed(level, event.getPos()));
        }
    }

    public static void petRemoved(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) { return; }
        var entity = event.getEntity();
        var reason = entity.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) { return; }
        // A parrot is discarded when its saved entity moves onto a player's shoulder.
        if (entity instanceof Parrot && level.getServer().getPlayerList().getPlayers().stream()
                .anyMatch(player -> java.util.stream.Stream.of(player.getShoulderEntityLeft(), player.getShoulderEntityRight())
                    .anyMatch(tag -> tag.hasUUID("UUID") && tag.getUUID("UUID").equals(entity.getUUID())))) { return; }
        for (Colony colony : Colonies.all(level.getServer())) {
            if (ColonyPets.belongsTo(entity, colony)) {
                colony.service(ID, LivingPresence.class).ifPresent(living -> living.petRemoved(entity.getUUID()));
            }
        }
    }

    public static void exerted(WorkExertion event) {
        Bodies.of(event.body()).ifPresent(body -> body.colonyId()
            .flatMap(id -> Colonies.of(event.body().getServer(), id))
            .flatMap(colony -> colony.service(ID, LivingPresence.class))
            .ifPresent(living -> living.exerted(body, event)));
    }

    // Draws onto the human residents' page rather than one of its own.
    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, LivingPresence.class)
            .map(value -> (Facing) value));
        registering.enrollment(ID, new Enrollment(
            List.of(new Delegation(BED, new Shape.Block(Housing::isBed, Housing::sleepingCell)),
                new Delegation(RECREATION, new Shape.Volume(4096), ResourceLocation.withDefaultNamespace("poppy")),
                new Delegation(DINING, new Shape.Volume(4096), new Schema(List.of(DINING_FOOD, DINING_SERVINGS, DINING_DRINK)),
                    ResourceLocation.withDefaultNamespace("cake"))),
            new Schema(List.of(FOOD_MODE, FOOD_SAFETY, FOOD)), List.of()));
    }

    // What one more settler would ask of this colony; nothing, when it does not keep its residents living.
    public static List<SettlerNeed> settlerNeeds(Colony colony, MinecraftServer server) {
        return colony.service(ID, LivingPresence.class)
            .map(living -> living.settlerNeeds(server))
            .orElseGet(List::of);
    }

    // The meals a resident looked after keeps in their pack, so they are not put away again as soon as fetched.
    public static List<Amount> rations(Colony colony, Body body) {
        if (!looksAfter(body.resident())) {
            return List.of();
        }
        return food(colony)
            .map(food -> List.of(new Amount(food,
                LivingPresence.mealsFor(food, LivingPresence.RATION_DAYS * Hunger.FOOD_PER_DAY))))
            .orElse(List.of());
    }

    static Optional<ItemSpec> food(Colony colony) {
        var settings = Fronts.of(colony).settings(ID);
        return FoodSafety.allowed(settings.items(FOOD.key()), settings.choice(FOOD_MODE.key()).equals(BLACKLIST),
            settings.flag(FOOD_SAFETY.key()));
    }

    static boolean looksAfter(Resident resident) {
        return Participations.between(resident.kind(), Stake.urge(ID)) != Participation.NONE;
    }

    private static List<ItemFilter> vanilla(String... items) {
        return Arrays.stream(items).map(ResourceLocation::withDefaultNamespace).map(ItemFilter::item).toList();
    }
}
