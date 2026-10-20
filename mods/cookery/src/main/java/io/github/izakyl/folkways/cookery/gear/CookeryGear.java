package io.github.izakyl.folkways.cookery.gear;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Native Cookery benefits requested by work, never a permanent kit for every farmer. */
public final class CookeryGear {
    public interface SeedGathering extends Node {
        boolean gathersGrass();
    }

    public record Request(Map<EquipmentSlot, ItemSpec> slots, boolean required) {
        public static final Request NONE = new Request(Map.of(), false);
        public Request { slots = Map.copyOf(slots); }
        public boolean empty() { return slots.isEmpty(); }
    }

    private static Predicate<Colony> hats = colony -> false;
    private static Predicate<Colony> armor = colony -> false;
    private static BiPredicate<Colony, Item> clothing = (colony, item) -> false;

    private CookeryGear() {
    }

    public static void register(Predicate<Colony> wearHat, Predicate<Colony> wearArmor,
                                BiPredicate<Colony, Item> allowed) {
        hats = wearHat;
        armor = wearArmor;
        clothing = allowed;
    }

    public static boolean permitsSeeds(Colony colony, Body body) {
        return body.licences().of(FarmingContent.trade()).isPresent() && hats.test(colony)
            && hat(colony).isPresent();
    }

    public static boolean permits(Colony colony, Body body, Request request) {
        if (body.licences().of(FarmingContent.trade()).isEmpty()) return false;
        if (request.required() ? !hats.test(colony) : !armor.test(colony)) return false;
        return request.slots().values().stream().allMatch(family -> Goods.members(family).stream()
            .allMatch(item -> clothing.test(colony, item)));
    }

    public static boolean allowed(Colony colony, ItemStack stack) {
        return !stack.isEmpty() && clothing.test(colony, stack.getItem());
    }

    public static Request request(Colony colony, Node node, ServerLevel level) {
        if (node instanceof SeedGathering seeds && seeds.gathersGrass() && hats.test(colony)) {
            return hat(colony).map(family -> new Request(Map.of(EquipmentSlot.HEAD, family), true))
                .orElse(Request.NONE);
        }
        // Only a Cookery paddy task whose actual offered work positions are all wet benefits from the set.
        // Working from a dry bank, ordinary wheat and unrelated water travel request no clothing.
        var spec = node.spec();
        if (!armor.test(colony) || !spec.owner().equals(FarmingContent.ID) || !spec.site().where().in(level)
                || spec.stances().cells().isEmpty()) return Request.NONE;
        var above = spec.site().where().block(level).above();
        if (!level.getBlockState(above).is(net.minecraft.world.level.block.Blocks.WATER)
                && !level.getBlockState(above).is(ModBlocks.RICE_CROP.get())) return Request.NONE;
        if (spec.stances().cells().stream().anyMatch(at -> !at.in(level)
                || !level.getFluidState(at.block(level)).is(net.minecraft.tags.FluidTags.WATER))) return Request.NONE;
        Map<EquipmentSlot, ItemSpec> slots = new LinkedHashMap<>();
        var hat = hat(colony);
        if (hat.isEmpty()) return Request.NONE;
        slots.put(EquipmentSlot.HEAD, hat.get());
        slots.put(EquipmentSlot.CHEST, exact(ModItems.FARMER_CHEST_PLATE.get()));
        slots.put(EquipmentSlot.LEGS, exact(ModItems.FARMER_LEGGINGS.get()));
        slots.put(EquipmentSlot.FEET, exact(ModItems.FARMER_BOOTS.get()));
        if (slots.values().stream().anyMatch(family -> Goods.members(family).stream()
                .noneMatch(item -> clothing.test(colony, item)))) return Request.NONE;
        return new Request(slots, false);
    }

    private static java.util.Optional<ItemSpec> hat(Colony colony) {
        List<ItemSpec> choices = Goods.members(ItemSpec.of(TagMod.STRAW_HAT)).stream()
            .filter(item -> clothing.test(colony, item)).map(CookeryGear::exact).toList();
        return choices.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ItemSpec.anyOf(choices));
    }

    private static ItemSpec exact(Item item) {
        return ItemSpec.of(BuiltInRegistries.ITEM.getKey(item));
    }
}