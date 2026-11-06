package io.github.izakyl.folkways.cookery.testing;

import io.github.izakyl.folkways.core.api.colony.Cadence;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.effect.Effects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * A colony holding one kitchen station and nothing else. It records what Asking publishes, submits and withdraws;
 * every submitted root must accept its own planned hook.
 */
public final class StationColony {
    public final UUID id = UUID.randomUUID();
    public final Holding holding;
    public final ColonyView view;
    public final Colony colony;
    public final List<Grown> submitted = new ArrayList<>();
    public final List<UUID> withdrawn = new ArrayList<>();
    public BiConsumer<UUID, Ending> ended;
    public int published;
    private final ColonyData inventoryData = new ColonyData();
    private final Map<ResourceLocation, CompoundTag> kept = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public StationColony(GameTestHelper helper, BlockPos cell, String planned) {
        ServerLevel level = helper.getLevel();
        holding = new Holding(UUID.randomUUID(), CookeryContent.STATION, new Held.Block(WorldPos.of(level, cell)));
        view = Proxies.of(ColonyView.class, (proxy, method, args) -> switch (method.getName()) {
            case "level" -> level;
            case "dimension" -> level.dimension();
            case "holdings" -> List.of(holding);
            case "blocks" -> CookeryContent.STATION.equals(args[0]) ? Set.of(cell) : Set.of();
            case "residents" -> List.of();
            case "effectsOf" -> Effects.NONE;
            default -> throw new AssertionError(method.getName());
        });
        colony = Proxies.of(Colony.class, (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "inventory", "inventoryFeedback" -> inventoryData.inventory();
            case "views" -> List.of(view);
            case "view" -> view;
            case "holdings" -> args == null || args.length == 0 || CookeryContent.STATION.equals(args[0]) ? List.of(holding) : List.of();
            case "storesIn", "residents", "reservedInputs" -> List.of();
            case "service", "store" -> Optional.empty();
            case "publish" -> { published++; yield null; }
            case "rule" -> null;
            case "kept" -> kept.getOrDefault(args[0], new CompoundTag()).copy();
            case "keep" -> { kept.put((ResourceLocation) args[0], ((CompoundTag) args[1]).copy()); yield null; }
            case "submit" -> {
                Grown grown = (Grown) args[2]; submitted.add(grown); ended = (BiConsumer<UUID, Ending>) args[3];
                helper.assertTrue(grown.nodes().getFirst().planned(level).isEmpty(), planned); yield null;
            }
            case "withdraw" -> { withdrawn.add((UUID) args[1]); yield null; }
            default -> throw new AssertionError(method.getName());
        });
    }

    public void close() {
        Cadence.forget(colony);
        inventoryData.inventory().clear();
    }
}
