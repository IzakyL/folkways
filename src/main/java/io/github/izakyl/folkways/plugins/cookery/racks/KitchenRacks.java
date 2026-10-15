package io.github.izakyl.folkways.plugins.cookery.racks;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.KitchenwareRacksBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

public final class KitchenRacks {

    private static final int PRIORITY = 100;
    private static boolean registered;

    private KitchenRacks() {
    }

    public static synchronized void register() {
        if (!registered) {
            Containers.register(KitchenwareRacksBlockEntity.class,
                (level, rack) -> Optional.of(new RackContainer(rack)));
            registered = true;
        }
    }

    public static boolean matches(BlockState state) {
        return state.is(ModBlocks.KITCHENWARE_RACKS.get());
    }

    public static List<StoreRule> rules(Collection<WorldPos> racks) {
        ItemSpec tools = ItemSpec.of(Tags.Items.TOOLS);
        return racks.stream().distinct().map(at -> (StoreRule) new StoreRule.Prefer(at, tools, PRIORITY)).toList();
    }
}
