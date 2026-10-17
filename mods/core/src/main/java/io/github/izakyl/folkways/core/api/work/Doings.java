package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.Tags;

public final class Doings {

    public static final ResourceLocation WALKING = named("walking");

    public static final ResourceLocation WORKING = named("working");

    public static final ResourceLocation WAITING = named("waiting");

    // No body is ever set to this: it is what a card shows for a body that is up to nothing.
    public static final ResourceLocation IDLE = named("idle");

    public static final ResourceLocation PICKING_UP = named("picking_up");
    public static final ResourceLocation FETCHING = named("fetching");
    public static final ResourceLocation STOWING = named("stowing");

    private Doings() {
    }

    public static Optional<String> about(ItemSpec goods) {
        ItemSpec one = goods.anyOf().isEmpty() ? goods : goods.anyOf().iterator().next();
        return one.item()
            .map(id -> BuiltInRegistries.ITEM.get(id).getDescriptionId())
            .or(() -> one.tag().map(tag -> Tags.getTagTranslationKey(tag)));
    }

    public static Optional<String> about(Block block) {
        return block == Blocks.AIR ? Optional.empty() : Optional.of(block.getDescriptionId());
    }

    /** The one item that stands for the goods: the item itself, or the first member of its tag. */
    public static Optional<ResourceLocation> item(ItemSpec goods) {
        ItemSpec one = goods.anyOf().isEmpty() ? goods : goods.anyOf().iterator().next();
        return one.item().or(() -> one.tag()
            .flatMap(tag -> BuiltInRegistries.ITEM.getTag(tag))
            .flatMap(members -> members.stream().findFirst())
            .flatMap(member -> member.unwrapKey())
            .map(key -> key.location()));
    }

    public static Optional<ResourceLocation> item(Block block) {
        Item item = block.asItem();
        return item == Items.AIR ? Optional.empty() : Optional.of(BuiltInRegistries.ITEM.getKey(item));
    }

    private static ResourceLocation named(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}
