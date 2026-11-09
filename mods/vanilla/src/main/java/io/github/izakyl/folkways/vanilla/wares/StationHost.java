package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Stances;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

sealed interface StationHost permits CookHost, PackCraftHost {

    // The station as the plan sees it this round, with the colony's fuel and the station's state as they are now.
    Workshop frozen(RecipeIndex recipes, ServerLevel level, Optional<ItemSpec> fuel);

    Stances stances();
}
