package io.github.izakyl.folkways.plugins.cookery.tools;

import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

// One colony's runtime borrowing and merged missing-tool orders. Food graphs keep their original material budget.
public final class TaskTools {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "cookery_tools");

    private record Supply(KitchenTools.Replenishment request) {
    }

    final Colony colony;
    final MinecraftServer server;
    final KitchenTools pool = new KitchenTools();
    final ToolStock stock;
    private final Map<UUID, ToolWork> active = new LinkedHashMap<>();
    private final Map<UUID, ItemStack> borrowed = new LinkedHashMap<>();
    private int delay;
    private final Map<UUID, Supply> supplying = new LinkedHashMap<>();

    public TaskTools(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        stock = new ToolStock(colony, server, pool, this::tool);
        var saved = colony.kept(ID);
        pool.load(colony.id(), saved);
        var items = saved.getList("items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            var item = items.getCompound(index);
            if (item.hasUUID("loan")) {
                var stack = ItemStack.parseOptional(server.overworld().registryAccess(), item.getCompound("stack"));
                if (!stack.isEmpty()) {
                    borrowed.put(item.getUUID("loan"), stack);
                }
            }
        }
        pool.leases(colony.id()).forEach(lease ->
            protect(lease.id(), lease.resident(), List.of(lease.request().tools())));
    }

    public static void register() {
        Tools.shared(new ToolNeed.Tagged(TagMod.KITCHEN_KNIFE), ItemSpec.of(TagMod.KITCHEN_KNIFE));
        Tools.shared(new ToolNeed.Tagged(TagMod.KITCHEN_SHOVEL), ItemSpec.of(TagMod.KITCHEN_SHOVEL));
    }

    public Workshop decorate(Workshop original) {
        return new Workshop() {
            public UUID key() { return original.key(); }
            public ResourceLocation id() { return original.id(); }
            public WorkSite site() { return original.site(); }
            public List<Refinement.Change> options(Intent intent, Grown graph) {
                return original.options(intent, graph).stream().map(TaskTools.this::decorate).toList();
            }
        };
    }

    public Refinement.Change decorate(Refinement.Change change) {
        return new Refinement.Change(decorate(change.replacement()), change.links(), change.workerGroups(), change.holds());
    }

    public Grown decorate(Grown original) {
        List<Node> wrapped = original.nodes().stream().map(node -> {
            boolean shared = node.spec().tools().stream().anyMatch(use -> Tools.shared(use.need()).isPresent());
            return shared && !(node instanceof ToolWork) ? (Node) new ToolWork(this, node) : node;
        }).toList();
        return new Grown(wrapped, original.links(), original.workerGroups(), original.follows());
    }

    synchronized void started(ToolWork work) {
        active.put(work.id(), work);
    }

    synchronized void ended(ToolWork work) {
        active.remove(work.id(), work);
        pool.cancel(colony.id(), work.id());
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(work.id());
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            if (lease.request().node().equals(work.id())) {
                if (tool(lease).isEmpty()) {
                    returned(lease);
                    continue;
                }
                protect(lease.id(), lease.resident(), List.of(lease.request().tools()));
            }
        }
        persist();
    }

    void protect(UUID task, UUID resident, List<ItemSpec> tools) {
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.keep(task, resident,
            stack -> tools.stream().anyMatch(family -> Goods.matches(stack, family)));
    }

    void returned(KitchenTools.Lease lease) {
        pool.returning(lease.id());
        pool.returned(lease.id());
        borrowed.remove(lease.id());
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(lease.id());
        persist();
    }

    void record(KitchenTools.Lease lease, ItemStack tool) {
        borrowed.put(lease.id(), tool.copyWithCount(1));
        protect(lease.id(), lease.resident(), List.of(lease.request().tools()));
        persist();
    }

    Optional<ItemStack> tool(KitchenTools.Lease lease) {
        return Optional.ofNullable(borrowed.get(lease.id())).map(ItemStack::copy);
    }

    int carriedSlot(Container pack, KitchenTools.Lease lease, ItemStack expected) {
        if (expected.isEmpty()) {
            return -1;
        }
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            var stack = pack.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, expected)) {
                return slot;
            }
        }
        return -1;
    }

    void lost(KitchenTools.Lease lease) {
        pool.lost(lease.id());
        borrowed.remove(lease.id());
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(lease.id());
        persist();
    }

    void persist() {
        var saved = pool.save(colony.id());
        var items = new ListTag();
        for (var lease : pool.leases(colony.id())) {
            var stack = borrowed.get(lease.id());
            if (stack != null && !stack.isEmpty()) {
                var item = new CompoundTag();
                item.putUUID("loan", lease.id());
                item.put("stack", stack.save(server.overworld().registryAccess()));
                items.add(item);
            }
        }
        saved.put("items", items);
        colony.keep(ID, saved);
    }

    public synchronized void tick(MinecraftServer ignored) {
        if (delay-- > 0) {
            return;
        }
        delay = 19;
        Set<UUID> needed = new LinkedHashSet<>();
        for (ToolWork work : List.copyOf(active.values())) {
            ServerLevel level = work.level();
            if (level == null || !work.needsTools()) {
                continue;
            }
            for (ItemSpec family : work.families()) {
                var inventory = stock.read(level, family).inventory();
                for (Supply supply : supplying.values()) {
                    if (supply.request().tools().equals(family) && inventory.commitments().entrySet().stream()
                            .noneMatch(entry -> !entry.getKey().equals(supply.request().id()) && entry.getValue() > 0)) {
                        needed.add(supply.request().id());
                    }
                }
                var decision = pool.request(colony.id(), work.id(), family, inventory);
                decision.replenishment().ifPresent(request -> {
                    needed.add(request.id());
                    if (supplying.containsKey(request.id())) {
                        return;
                    }
                    Goods.members(family).stream().map(item -> item.getDefaultInstance()).findFirst()
                        .flatMap(stack -> stock.destinations(level, stack).stream().findFirst()).ifPresent(target -> {
                            Supply supply = new Supply(request);
                            supplying.put(request.id(), supply);
                            Delivery delivery = Delivery.to(request.id(), ID, new WorkSite.AtBlock(target.at()),
                                Stances.WHEREVER, request.tools(), 1, count -> { });
                            colony.submit(ID, level.dimension(), Grown.of(delivery), (node, how) -> {
                                synchronized (TaskTools.this) {
                                    supplying.remove(node);
                                }
                            });
                        });
                });
            }
        }
        for (UUID obsolete : List.copyOf(supplying.keySet())) {
            if (!needed.contains(obsolete)) {
                supplying.remove(obsolete);
                colony.withdraw(ID, obsolete);
            }
        }
    }

    public synchronized List<Grown> goals(ColonyView view) {
        List<Grown> recovery = new ArrayList<>();
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            if (lease.phase() != KitchenTools.Phase.RETURNING) {
                continue;
            }
            stock.body(lease.resident()).filter(body -> body.mob().level() == view.level()).ifPresent(body -> {
                protect(lease.id(), lease.resident(), List.of(lease.request().tools()));
                recovery.add(Grown.of(new ToolReturn(this, lease, body)));
            });
        }
        return List.copyOf(recovery);
    }

    public synchronized void closed(Closing why) {
        if (why == Closing.SHUTDOWN) {
            persist();
        } else {
            colony.keep(ID, new CompoundTag());
        }
        for (UUID supply : supplying.keySet()) {
            colony.withdraw(ID, supply);
        }
        active.keySet().forEach(io.github.izakyl.folkways.core.api.resident.body.TaskTools::release);
        pool.leases(colony.id()).forEach(lease ->
            io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(lease.id()));
        active.clear();
        supplying.clear();
    }
}
