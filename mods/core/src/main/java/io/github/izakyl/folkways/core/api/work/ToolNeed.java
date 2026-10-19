package io.github.izakyl.folkways.core.api.work;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbility;

public sealed interface ToolNeed {

    ToolNeed NONE = new None();

    record None() implements ToolNeed {
    }

    record Ability(ItemAbility ability) implements ToolNeed {
    }

    record SuitableFor(BlockState state) implements ToolNeed {
    }

    record Tagged(TagKey<Item> tag) implements ToolNeed {
    }
}
