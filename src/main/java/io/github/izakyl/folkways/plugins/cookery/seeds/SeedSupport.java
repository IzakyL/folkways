package io.github.izakyl.folkways.plugins.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.plugins.farming.CropSeeds;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.orders.Orders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Server-thread bridge from real colony inventory, current plan claims and persisted player orders. */
public final class SeedSupport {
    private final Colony colony;
    private final MinecraftServer server;
    private final SeedBudget budget;

    public SeedSupport(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        budget = new SeedBudget(this::snapshot, this::catalogue);
    }

    public SeedBudget budget(ServerLevel level) { return budget; }
    public Set<Item> catalogue() { return CropSeeds.catalogue(server.overworld(), BlockPos.ZERO); }

    public boolean enabled() {
        return loadedBodies().values().stream().anyMatch(body -> body.licences().of(FarmingContent.trade()).isPresent()
            && body.mob().getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT));
    }

    private Map<UUID, Body> loadedBodies() {
        Map<UUID, Body> found = new LinkedHashMap<>();
        for (var resident : colony.residents()) {
            for (ServerLevel level : server.getAllLevels()) {
                var entity = level.getEntity(resident.id());
                if (entity != null) Bodies.of(entity).ifPresent(body -> found.putIfAbsent(resident.id(), body));
            }
        }
        return found;
    }

    public SeedBudget.Snapshot snapshot() {
        boolean complete = true;
        Map<WorldPos, SeedBudget.Stock> stores = new LinkedHashMap<>();
        List<SeedBudget.Stock> stocks = new ArrayList<>();
        List<ReservedInput> reserved = new ArrayList<>();
        List<SeedBudget.FutureNeed> future = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (WorldPos offered : colony.storesIn(level.dimension())) {
                if (!loadedStore(level, offered)) { complete = false; continue; }
                WorldPos canonical = canonical(level, offered);
                if (stores.containsKey(canonical)) continue;
                WorldPos readable = colony.storesIn(level.dimension()).contains(canonical) ? canonical : offered;
                Optional<Container> store = colony.store(level, readable);
                if (store.isPresent()) stores.put(canonical, stock("store/" + canonical, SeedBudget.Location.store(canonical), store.get()));
            }
            reserved.addAll(colony.reservedInputs(level));
        }
        stocks.addAll(stores.values());
        Map<UUID, Body> bodies = loadedBodies();
        if (bodies.size() != colony.residents().size()) complete = false;
        bodies.forEach((id, body) -> stocks.add(stock("pack/" + id, SeedBudget.Location.pack(id), body.pack())));
        List<SeedBudget.Claim> claims = new ArrayList<>();
        for (ReservedInput input : reserved) {
            SeedBudget.Location at = SeedBudget.Location.UNKNOWN;
            if (input.store().isPresent()) {
                WorldPos where = input.store().get();
                Optional<ServerLevel> level = where.level(server);
                if (level.isEmpty() || !loadedStore(level.get(), where)) complete = false;
                else at = SeedBudget.Location.store(canonical(level.get(), where));
            } else if (input.resident().isPresent()) {
                at = SeedBudget.Location.pack(input.resident().get());
                if (!bodies.containsKey(input.resident().get())) complete = false;
            }
            claims.add(new SeedBudget.Claim(input.node(), at, input.goods(), input.count()));
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (CropSeeds.Sowing sowing : CropSeeds.sowing(colony, level)) {
                // One sowing's repeated stage observations are not additional demands. Clamp at this
                // job's actual requested count, including an unknown-location plan claim only once.
                long covered = reserved.stream().filter(input -> input.node().equals(sowing.node()) && Goods.overlap(input.goods(), sowing.seed()))
                    .mapToLong(ReservedInput::count).sum();
                long left = Math.max(0, sowing.count() - covered);
                if (left > 0) future.add(new SeedBudget.FutureNeed("sowing/" + sowing.node(), sowing.seed(), left));
            }
        }
        List<SeedBudget.StockTarget> targets = new ArrayList<>();
        for (Orders.Target target : Orders.targets(colony)) {
            Optional<ServerLevel> level = target.into().level(server);
            if (level.isEmpty() || !loadedStore(level.get(), target.into())) complete = false;
            WorldPos where = level.isPresent() && level.get().isLoaded(target.into().block(level.get()))
                ? canonical(level.get(), target.into()) : target.into();
            targets.add(new SeedBudget.StockTarget("order/" + where + "/" + target.goods().describe(), target.goods(), target.high()));
        }
        return new SeedBudget.Snapshot(stocks, claims, future, Map.of(), targets, complete);
    }

    private static SeedBudget.Stock stock(String identity, SeedBudget.Location at, Container contents) {
        return new SeedBudget.Stock(identity, at, IntStream.range(0, contents.getContainerSize()).mapToObj(contents::getItem).toList());
    }

    private static boolean loadedStore(ServerLevel level, WorldPos where) {
        if (!where.in(level) || !level.isLoaded(where.block(level))) return false;
        BlockPos at = where.block(level);
        var state = level.getBlockState(at);
        return !(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE
            || level.isLoaded(at.relative(ChestBlock.getConnectedDirection(state)));
    }

    private static WorldPos canonical(ServerLevel level, WorldPos where) {
        return WorldPos.of(level, Containers.anchor(level, where.block(level)));
    }
}
