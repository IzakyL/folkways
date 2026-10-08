package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ColonyStocks {

    private ColonyStocks() {
    }

    public static Map<Item, Integer> tally(Colony colony, MinecraftServer server) {
        Map<Item, Integer> held = new LinkedHashMap<>();
        for (ColonyView view : colony.views(server)) {
            for (Container container : containers(colony, view)) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        held.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        for (Body resident : Residents.of(colony, server)) {
            Container pack = resident.pack();
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                ItemStack stack = pack.getItem(slot);
                if (!stack.isEmpty()) {
                    held.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        return held;
    }

    public static long available(Colony colony, MinecraftServer server, ItemSpec spec) {
        long total = 0;
        for (ColonyView view : colony.views(server)) {
            for (Container container : containers(colony, view)) {
                total += Goods.countIn(container, spec);
            }
        }
        for (Body resident : Residents.of(colony, server)) {
            total += Goods.countIn(resident.pack(), spec);
        }
        return total;
    }

    // The stores the colony's work draws on in this level.
    private static List<Container> containers(Colony colony, ColonyView view) {
        ServerLevel level = view.level();
        List<Container> found = new ArrayList<>();
        for (WorldPos store : colony.storesIn(view.dimension())) {
            WorldSpaces.storage(level, store).filter(level::isLoaded)
                .flatMap(cell -> Containers.at(level, cell)).ifPresent(found::add);
        }
        return found;
    }
}
