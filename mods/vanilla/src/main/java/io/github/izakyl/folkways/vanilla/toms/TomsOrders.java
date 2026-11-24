package io.github.izakyl.folkways.vanilla.toms;

import io.github.izakyl.folkways.core.card.api.notice.Attempt;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
import io.github.izakyl.folkways.vanilla.orders.Orders;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A storage network's terminal as a place to order from the colony, the way an ME terminal orders autocrafting. A
 * request is an ordinary once order into one of the network's member containers; this class only picks the member,
 * lists what the colony can make or fetch, and reads the orders back as requests.
 */
public final class TomsOrders {

    /** A registered access point, the colony that holds it and the member containers it stands for. */
    public record Network(Colony colony, ServerLevel level, WorldPos access, List<WorldPos> members) {
        public Network {
            members = List.copyOf(members);
        }
    }

    /** Goods the colony can bring to the network: how many are on it, in its other stores, and whether it makes them. */
    public record Offer(ResourceLocation item, long here, long elsewhere, boolean made) {
    }

    public enum Status {
        WAITING, WORKING, STALLED, DELIVERED
    }

    /** One request into the network, as its terminal tells it. */
    public record Request(ResourceLocation item, long asked, long high, long held, long coming, Status status,
                          Optional<Component> reason) {
    }

    private static final long RECIPE_TICKS = 40L;
    private static final long DELIVERED_TICKS = 6_000L;
    private static final int MOST_DELIVERED = 8;

    private record Recipes(ColonyRecipes recipes, long[] read) {
    }

    // What a request filed, so its row reads the count asked for and stays told for a while once delivered.
    private record Placed(WorldPos into, ResourceLocation item, long asked, long high, long since) {
    }

    private record Key(UUID colony, WorldPos access) {
    }

    private static final Map<UUID, Recipes> RECIPES = new HashMap<>();
    private static final Map<Key, Map<ResourceLocation, Placed>> PLACED = new HashMap<>();
    private static final Map<Key, List<Placed>> DELIVERED = new HashMap<>();

    private TomsOrders() {
    }

    public static Optional<Network> network(ServerLevel level, WorldPos access) {
        if (!access.in(level) || !level.isLoaded(access.block(level))) {
            return Optional.empty();
        }
        Optional<List<WorldPos>> members = StoreAccess.at(level, access.block(level))
            .flatMap(port -> port.proxyMembers()).filter(found -> !found.isEmpty());
        if (members.isEmpty()) {
            return Optional.empty();
        }
        for (Colony colony : Colonies.all(level.getServer())) {
            if (colony.claimAt(access).isPresent()) {
                return Optional.of(new Network(colony, level, access, members.get()));
            }
        }
        return Optional.empty();
    }

    /** Everything the colony could bring here: goods its workshops make, and goods held in its other stores. */
    public static List<Offer> offers(Network network) {
        InventorySnapshot inventory = network.colony().inventory().snapshot();
        Set<WorldPos> members = Set.copyOf(network.members());
        Map<ResourceLocation, long[]> counted = new LinkedHashMap<>();
        for (InventorySnapshot.Store store : inventory.stores().values()) {
            if (!store.dimension().equals(network.level().dimension()) || !store.usability().usable()
                    || store.state() != InventorySnapshot.State.OBSERVED) {
                continue;
            }
            boolean here = members.contains(store.stash().pos());
            for (var entry : store.entries()) {
                ResourceLocation item = BuiltInRegistries.ITEM.getKey(entry.prototype().getItem());
                counted.computeIfAbsent(item, ignored -> new long[2])[here ? 0 : 1] += entry.count();
            }
        }
        Set<ResourceLocation> made = new LinkedHashSet<>();
        for (ColonyRecipes.Entry entry : recipes(network.colony(), network.level().getServer()).entries()) {
            if (!entry.workshop().site().where().in(network.level())) {
                continue;
            }
            for (Amount output : entry.recipe().outputs()) {
                output.spec().item().filter(BuiltInRegistries.ITEM::containsKey).ifPresent(made::add);
            }
        }
        List<Offer> offers = new ArrayList<>();
        Set<ResourceLocation> items = new LinkedHashSet<>(made);
        counted.forEach((item, counts) -> {
            if (counts[1] > 0) {
                items.add(item);
            }
        });
        for (ResourceLocation item : items) {
            if (BuiltInRegistries.ITEM.get(item) == Items.AIR) {
                continue;
            }
            long[] counts = counted.getOrDefault(item, new long[2]);
            offers.add(new Offer(item, counts[0], counts[1], made.contains(item)));
        }
        // Goods already in the colony's stores come first, as an ME terminal lists stock before what it can craft.
        offers.sort(Comparator.comparing((Offer offer) -> offer.elsewhere() == 0)
            .thenComparing(offer -> offer.item().toString()));
        return List.copyOf(offers);
    }

    /** Orders count more of the item into the network: into the member already on order for it, else one holding it. */
    public static Attempt place(Network network, ResourceLocation item, long count) {
        if (!BuiltInRegistries.ITEM.containsKey(item) || BuiltInRegistries.ITEM.get(item) == Items.AIR) {
            return Attempt.refused(TomsOrderRefusal.UNKNOWN);
        }
        if (offers(network).stream().noneMatch(offer -> offer.item().equals(item))) {
            return Attempt.refused(TomsOrderRefusal.NOT_ORDERABLE,
                Notice.named(BuiltInRegistries.ITEM.get(item).getDescriptionId()));
        }
        ItemSpec goods = ItemSpec.of(item);
        Optional<WorldPos> into = destination(network, goods);
        if (into.isEmpty()) {
            return Attempt.refused(TomsOrderRefusal.NO_MEMBER);
        }
        Attempt attempt = Orders.request(network.colony(), network.level(), into.get(), goods, count);
        if (!attempt.refused()) {
            Key key = key(network);
            Placed before = PLACED.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).get(item);
            long asked = (before != null && before.into().equals(into.get()) ? before.asked() : 0) + count;
            long high = Orders.requests(network.colony(), network.level(), List.of(into.get())).stream()
                .filter(request -> request.goods().equals(goods)).mapToLong(Orders.Request::high).findFirst().orElse(count);
            PLACED.get(key).put(item, new Placed(into.get(), item, asked, high, now(network)));
            DELIVERED.getOrDefault(key, new ArrayList<>()).removeIf(done -> done.item().equals(item));
        }
        return attempt;
    }

    public static Attempt cancel(Network network, ResourceLocation item) {
        ItemSpec goods = ItemSpec.of(item);
        Optional<Orders.Request> order = Orders.requests(network.colony(), network.level(), network.members()).stream()
            .filter(request -> request.goods().equals(goods) && !request.standing()).findFirst();
        if (order.isEmpty()) {
            return Attempt.refused(TomsOrderRefusal.NO_REQUEST);
        }
        Attempt attempt = Orders.cancel(network.colony(), order.get().into(), goods);
        if (!attempt.refused()) {
            Optional.ofNullable(PLACED.get(key(network))).ifPresent(placed -> placed.remove(item));
        }
        return attempt;
    }

    /** The once orders into the network's members, then the requests delivered lately. */
    public static List<Request> requests(Network network) {
        Key key = key(network);
        Map<ResourceLocation, Placed> placed = PLACED.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        List<Placed> delivered = DELIVERED.computeIfAbsent(key, ignored -> new ArrayList<>());
        List<Request> rows = new ArrayList<>();
        Set<ResourceLocation> open = new LinkedHashSet<>();
        for (Orders.Request order : Orders.requests(network.colony(), network.level(), network.members())) {
            Optional<ResourceLocation> item = order.goods().item();
            if (order.standing() || item.isEmpty()) {
                continue;
            }
            open.add(item.get());
            Placed filed = placed.get(item.get());
            long asked = filed != null && filed.into().equals(order.into()) ? filed.asked()
                : Math.max(0, order.high() - order.held());
            Status status = order.stalled().isPresent() ? Status.STALLED
                : order.begun() ? Status.WORKING : Status.WAITING;
            rows.add(new Request(item.get(), asked, order.high(), order.held(), order.coming(), status,
                order.stalled().map(Notice::component)));
        }
        long now = now(network);
        InventorySnapshot inventory = network.colony().inventory().snapshot();
        for (Placed filed : List.copyOf(placed.values())) {
            if (open.contains(filed.item())) {
                continue;
            }
            placed.remove(filed.item());
            // An order withdrawn elsewhere, on the container's card, leaves its goods short and is not told.
            long held = inventory.store(filed.into()).map(store -> store.entries().stream()
                .filter(entry -> Goods.matches(entry.prototype(), ItemSpec.of(filed.item())))
                .mapToLong(entry -> entry.count()).sum()).orElse(0L);
            delivered.removeIf(done -> done.item().equals(filed.item()));
            if (held >= filed.high()) {
                delivered.addFirst(new Placed(filed.into(), filed.item(), filed.asked(), filed.high(), now));
            }
        }
        delivered.removeIf(done -> now - done.since() > DELIVERED_TICKS);
        while (delivered.size() > MOST_DELIVERED) {
            delivered.removeLast();
        }
        for (Placed done : delivered) {
            rows.add(new Request(done.item(), done.asked(), done.high(), done.high(), 0, Status.DELIVERED,
                Optional.empty()));
        }
        return List.copyOf(rows);
    }

    // Goods on order for this network keep going to the member they were ordered into; new goods go where some
    // already lie, else to the member with the most free slots.
    private static Optional<WorldPos> destination(Network network, ItemSpec goods) {
        for (Orders.Request request : Orders.requests(network.colony(), network.level(), network.members())) {
            if (request.goods().equals(goods) && !request.standing()) {
                return Optional.of(request.into());
            }
        }
        InventorySnapshot inventory = network.colony().inventory().snapshot();
        WorldPos best = null;
        long bestHeld = -1;
        long bestFree = -1;
        for (WorldPos member : network.members()) {
            var store = inventory.store(member).filter(found -> found.usability().usable()
                && found.state() == InventorySnapshot.State.OBSERVED);
            if (store.isEmpty() || !StoreAccess.at(network.level(), member.block(network.level())).isPresent()) {
                continue;
            }
            long held = store.get().entries().stream().filter(entry -> Goods.matches(entry.prototype(), goods))
                .mapToLong(entry -> entry.count()).sum();
            long free = store.get().slots().stream().filter(slot -> slot.stackCopy().isEmpty()).count();
            if (held > bestHeld || held == bestHeld && free > bestFree) {
                best = member;
                bestHeld = held;
                bestFree = free;
            }
        }
        return Optional.ofNullable(best);
    }

    private static ColonyRecipes recipes(Colony colony, MinecraftServer server) {
        Recipes cached = RECIPES.computeIfAbsent(colony.id(), ignored ->
            new Recipes(new ColonyRecipes(colony), new long[] {Long.MIN_VALUE}));
        long now = server.overworld().getGameTime();
        if (!cached.recipes().ready() || now - cached.read()[0] >= RECIPE_TICKS) {
            cached.recipes().refresh(server);
            cached.read()[0] = now;
        }
        return cached.recipes();
    }

    private static Key key(Network network) {
        return new Key(network.colony().id(), network.access());
    }

    private static long now(Network network) {
        return network.level().getServer().overworld().getGameTime();
    }

    static ItemStack icon(ResourceLocation item) {
        Item found = BuiltInRegistries.ITEM.get(item);
        return found == Items.AIR ? ItemStack.EMPTY : new ItemStack(found);
    }

    // A colony razed or a server stopped takes its requests with it.
    static void forget() {
        RECIPES.clear();
        PLACED.clear();
        DELIVERED.clear();
    }
}
