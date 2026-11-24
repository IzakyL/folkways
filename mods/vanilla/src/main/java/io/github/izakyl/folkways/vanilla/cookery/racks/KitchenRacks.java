package io.github.izakyl.folkways.vanilla.cookery.racks;


import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collection;
import java.util.List;
import net.neoforged.neoforge.common.Tags;

public final class KitchenRacks {

    private static final int PRIORITY = 100;

    private KitchenRacks() {
    }

    public static List<StoreRule> rules(Collection<WorldPos> racks) {
        ItemSpec tools = ItemSpec.of(Tags.Items.TOOLS);
        return racks.stream().distinct().map(at -> (StoreRule) new StoreRule.Prefer(at, tools, PRIORITY)).toList();
    }
}
