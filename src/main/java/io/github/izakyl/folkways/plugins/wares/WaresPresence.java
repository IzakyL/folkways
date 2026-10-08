package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class WaresPresence implements Facing {

    private static final int STOCK_SHOWN = 4;

    private final Colony colony;

    private final Sweep sweep;

    private final CraftRecipes tenure;

    private final Map<WorldPos, StationHost> stations = new LinkedHashMap<>();

    private final Bookings spokenFor = new Bookings();

    private volatile Map<Block, Set<WorldPos>> cookers = Map.of();

    private final Function<Block, Set<WorldPos>> kin = block -> cookers.getOrDefault(block, Set.of());

    private final Upkeep upkeep;

    WaresPresence(Colony colony, CraftRecipes tenure) {
        this.colony = colony;
        this.sweep = new Sweep(colony, WaresContent.ID, 40);
        this.upkeep = new Upkeep(spokenFor, sweep::nudge);
        this.tenure = tenure;
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        RecipeIndex recipes = tenure.recipes();
        Optional<ItemSpec> fuel = fuel();
        List<WorldPos> standing = new ArrayList<>();
        List<Upkeep.Cold> cold = new ArrayList<>();
        List<Upkeep.Stranded> stranded = new ArrayList<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            List<Workshop> here = new ArrayList<>();
            for (BlockPos pos : view.blocks(WaresContent.STATION)) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (!Stations.isStation(state)) {
                    continue;
                }
                WorldPos at = WorldPos.of(level, pos);
                StationHost host = stations.get(at);
                if (host == null) {
                    Optional<Stances> footings = Stances.of(level, Reach.workableCells(level, pos));
                    if (footings.isEmpty()) {
                        continue;
                    }
                    host = hostFor(at, state.getBlock(), footings.get());
                    stations.put(at, host);
                }
                here.add(host.frozen(recipes, level, fuel));
                standing.add(at);
                upkeep(level, host, at, state, fuel, cold, stranded);
            }
            colony.publish(WaresContent.ID, level.dimension(), here);
        }
        stations.keySet().retainAll(standing);
        Map<Block, Set<WorldPos>> byBlock = new LinkedHashMap<>();
        stations.forEach((at, host) -> {
            if (host instanceof CookHost cook) {
                byBlock.computeIfAbsent(cook.block(), key -> new LinkedHashSet<>()).add(at);
            }
        });
        byBlock.replaceAll((block, ats) -> Set.copyOf(ats));
        cookers = Map.copyOf(byBlock);
        upkeep.found(cold, stranded);
    }

    public List<Grown> goals(ColonyView view) {
        return upkeep.goals(view.level());
    }

    private static void upkeep(ServerLevel level, StationHost host, WorldPos at, BlockState state,
                               Optional<ItemSpec> fuel, List<Upkeep.Cold> cold,
                               List<Upkeep.Stranded> stranded) {
        Block block = state.getBlock();
        if (!Stations.COOKERS.containsKey(block)) {
            return;
        }
        if (fuel.isPresent() && Machines.coldWithCharge(level, at.block(level), state)) {
            cold.add(new Upkeep.Cold(at, block, host.stances(), fuel.get()));
        }
        Machines.finished(level, at.block(level)).ifPresent(machine -> stranded.add(new Upkeep.Stranded(at, block,
            host.stances(), machine.getItem(Machines.RESULT_SLOT).copy())));
    }

    public void closed(Closing why) {
        if (why == Closing.SHUTDOWN) {
            return;
        }
        stations.clear();
        spokenFor.clear();
        upkeep.clear();
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(WaresContent.ID)) {
            return Optional.empty();
        }
        ServerLevel level = view.level();
        List<Board.Row> rows = new ArrayList<>();
        rows.add(new Board.Row(new ItemStack(BuiltInRegistries.ITEM.get(WaresContent.FUEL.icon())),
            Component.translatable(WaresContent.FUEL.nameKey()),
            Component.empty(),
            List.of(new Board.Act.Edit(WaresContent.FUEL.key()))));
        Optional<ItemSpec> fuel = fuel();
        int here = 0;
        for (WorldPos cell : stations.keySet()) {
            if (!cell.in(level) || !level.isLoaded(cell.block(level))) {
                continue;
            }
            here++;
            BlockState state = level.getBlockState(cell.block(level));
            rows.add(new Board.Row(new ItemStack(state.getBlock()),
                state.getBlock().getName(),
                fuelLine(level, cell.block(level)),
                List.of(new Board.Act.Ping(cell.block(level)))).told(told(level, cell.block(level), fuel)));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.wares.stations",
                Component.literal(Integer.toString(here)))),
            rows,
            Optional.of(Component.translatable("folkways.page.wares.empty"))));
    }

    private static Component fuelLine(ServerLevel level, BlockPos at) {
        Optional<Container> machine = Machines.at(level, at);
        if (machine.isEmpty() || machine.get().getContainerSize() <= Machines.FUEL_SLOT) {
            return Component.empty();
        }
        ItemStack held = machine.get().getItem(Machines.FUEL_SLOT);
        return held.isEmpty()
            ? Component.translatable("folkways.page.wares.cold")
            : Component.translatable("folkways.page.wares.fuelled", held.getCount(),
                held.getHoverName());
    }

    // A cooker looked at tells its fuel, its load turning and how far through it is; a store, what it holds most of.
    @Override
    public List<Line> blockLines(BlockPos at, ColonyView view) {
        ServerLevel level = view.level();
        if (stations.containsKey(WorldPos.of(level, at))) {
            List<Line> lines = new ArrayList<>();
            Sentence fired = Hearths.fired(level, at, fuel());
            Sentence cooking = Hearths.cooking(level, at);
            if (!fired.isEmpty()) {
                lines.add(Line.told(fired));
            }
            if (!cooking.isEmpty()) {
                lines.add(Line.told(cooking));
                Hearths.progress(level, at).ifPresent(lines::add);
            }
            return List.copyOf(lines);
        }
        BlockPos anchor = Containers.anchor(level, at);
        if (!view.blocks(WaresContent.STORE).contains(anchor)) {
            return List.of();
        }
        List<Sentence.Token.Ware> most = stock(level, anchor);
        return most.isEmpty() ? List.of() : List.of(Line.told(Sentence.wares(Sentence.glyph("stock"), most)));
    }

    private static List<Sentence.Token.Ware> stock(ServerLevel level, BlockPos at) {
        Map<Item, Long> held = new LinkedHashMap<>();
        Containers.at(level, at).ifPresent(store -> {
            for (int slot = 0; slot < store.getContainerSize(); slot++) {
                ItemStack stack = store.getItem(slot);
                if (!stack.isEmpty()) {
                    held.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                }
            }
        });
        return held.entrySet().stream()
            .sorted(Map.Entry.<Item, Long>comparingByValue().reversed())
            .limit(STOCK_SHOWN)
            .map(entry -> new Sentence.Token.Ware(BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue()))
            .toList();
    }

    // The fuel a cooker burns, then the load it is cooking turning into what it makes.
    private static Sentence told(ServerLevel level, BlockPos at, Optional<ItemSpec> fuel) {
        return Hearths.fired(level, at, fuel).then(Hearths.cooking(level, at));
    }

    private Optional<ItemSpec> fuel() {
        return Fronts.of(colony).settings(WaresContent.ID).items(WaresContent.FUEL.key());
    }

    private StationHost hostFor(WorldPos at, Block block, Stances footings) {
        RecipeType<? extends AbstractCookingRecipe> cooks = Stations.COOKERS.get(block);
        return cooks == null
            ? new PackCraftHost(at, block, footings)
            : new CookHost(at, block, cooks, footings, spokenFor, kin, sweep::nudge);
    }
}
