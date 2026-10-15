package io.github.izakyl.folkways.plugins.cookery.tools;

import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

// Reservations and missing-tool decisions only. The core must move the physical tool through ordinary hauling.
public final class KitchenTools {

    public enum Whereabouts { AVAILABLE, UNREACHABLE, BORROWED, IN_TRANSIT }
    public enum Status { AVAILABLE, BORROWED, BUSY, UNREACHABLE, MISSING }
    public enum Phase { IN_TRANSIT, BORROWED, RETURNING }

    // The same physical id follows a tool from its source through hauling and its loan. Repeated observations
    // of that id are one tool, with an occupied observation taking precedence over an available one.
    public record Tool(UUID id, ItemStack stack, Whereabouts whereabouts) {
        public Tool {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(whereabouts, "whereabouts");
            if (stack.isEmpty() || stack.getCount() != 1) {
                throw new IllegalArgumentException("one physical tool is required");
            }
            stack = stack.copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    // Commitments are the real positive quantities already promised for the requested family, including
    // explicit player stock orders. Keeping their ids lets this pool exclude its own automatic request.
    public record Inventory(List<Tool> tools, Map<UUID, Long> commitments, boolean complete) {
        public Inventory(List<Tool> tools, Map<UUID, Long> commitments) {
            this(tools, commitments, true);
        }

        public Inventory {
            Map<UUID, Tool> physical = new LinkedHashMap<>();
            for (Tool tool : tools) {
                physical.merge(tool.id(), tool, (first, next) ->
                    next.whereabouts().ordinal() > first.whereabouts().ordinal() ? next : first);
            }
            tools = List.copyOf(physical.values());
            commitments = Map.copyOf(commitments);
            if (commitments.values().stream().anyMatch(count -> count < 0)) {
                throw new IllegalArgumentException("tool commitments cannot be negative");
            }
        }
    }

    public record Replenishment(UUID id, ItemSpec tools, long count) {
    }

    public record Decision(Status status, Optional<Tool> tool, Optional<Replenishment> replenishment) {
        public boolean permitsProduction() {
            return status == Status.MISSING && replenishment.isPresent();
        }
    }

    public record Request(UUID colony, UUID node, ItemSpec tools) {
        public Request {
            Objects.requireNonNull(colony, "colony");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tools, "tools");
        }
    }

    public record Lease(UUID id, Request request, UUID tool, UUID resident, Phase phase) {
        private Lease with(Phase next) {
            return new Lease(id, request, tool, resident, next);
        }
    }

    private record Family(UUID colony, ItemSpec tools) {
        static Family of(Request request) {
            return new Family(request.colony(), request.tools());
        }
    }

    private final Map<Family, LinkedHashSet<UUID>> queues = new LinkedHashMap<>();
    private final Map<UUID, Lease> leases = new LinkedHashMap<>();
    private final Map<UUID, UUID> byTool = new LinkedHashMap<>();

    public synchronized Decision request(UUID colony, UUID node, ItemSpec tools, Inventory inventory) {
        Request request = new Request(colony, node, tools);
        queues.computeIfAbsent(Family.of(request), ignored -> new LinkedHashSet<>()).add(node);
        return decision(request, inventory);
    }

    // Replenishment is demand from adopted work, not an execution turn. A downstream task whose food
    // prerequisites are unfinished must never enter the physical borrowing queue ahead of its producer.
    public synchronized Decision supply(UUID colony, ItemSpec tools, Inventory inventory) {
        return decision(new Request(colony, replenishment(colony, tools), tools), inventory, false);
    }
    public synchronized Optional<Lease> reserve(UUID colony, UUID node, ItemSpec tools, UUID resident,
                                                Inventory inventory) {
        Request request = new Request(colony, node, tools);
        Optional<Lease> previous = loan(request);
        if (previous.isPresent()) {
            return previous.filter(lease -> lease.resident().equals(resident) && lease.phase() != Phase.RETURNING);
        }
        if (!requested(request)) {
            return Optional.empty();
        }
        Decision decision = decision(request, inventory);
        if (decision.status() != Status.AVAILABLE) {
            return Optional.empty();
        }
        UUID tool = decision.tool().orElseThrow().id();
        Lease lease = new Lease(UUID.randomUUID(), request, tool, Objects.requireNonNull(resident), Phase.IN_TRANSIT);
        leases.put(lease.id(), lease);
        byTool.put(tool, lease.id());
        return Optional.of(lease);
    }

    public synchronized boolean carried(UUID leaseId) {
        Lease lease = leases.get(leaseId);
        if (lease == null || lease.phase() == Phase.RETURNING || !requested(lease.request())) {
            return false;
        }
        leases.put(leaseId, lease.with(Phase.BORROWED));
        return true;
    }

    public synchronized boolean returning(UUID leaseId) {
        Lease lease = leases.get(leaseId);
        if (lease == null) {
            return false;
        }
        leases.put(leaseId, lease.with(Phase.RETURNING));
        dequeue(lease.request());
        return true;
    }

    // Call only after a physical return completed. Cancellation alone never makes a tool available again.
    public synchronized boolean returned(UUID leaseId) {
        Lease lease = leases.get(leaseId);
        if (lease == null || lease.phase() != Phase.RETURNING) {
            return false;
        }
        remove(lease);
        return true;
    }

    // A confirmed broken, destroyed or irretrievably lost item, not an unloaded body or an unreachable store.
    public synchronized boolean lost(UUID leaseId) {
        Lease lease = leases.get(leaseId);
        if (lease == null) {
            return false;
        }
        remove(lease);
        return true;
    }

    public synchronized void cancel(UUID colony, UUID node) {
        List<Request> requests = queues.entrySet().stream()
            .filter(entry -> entry.getKey().colony().equals(colony) && entry.getValue().contains(node))
            .map(entry -> new Request(colony, node, entry.getKey().tools())).toList();
        requests.forEach(this::dequeue);
        for (Lease lease : List.copyOf(leases.values())) {
            if (lease.request().colony().equals(colony) && lease.request().node().equals(node)) {
                leases.put(lease.id(), lease.with(Phase.RETURNING));
            }
        }
    }

    public synchronized List<Lease> leases(UUID colony) {
        return leases.values().stream().filter(lease -> lease.request().colony().equals(colony)).toList();
    }

    public synchronized CompoundTag save(UUID colony) {
        CompoundTag saved = new CompoundTag();
        ListTag list = new ListTag();
        for (Lease lease : leases(colony)) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", lease.id());
            entry.putUUID("node", lease.request().node());
            entry.put("tools", lease.request().tools().save());
            entry.putUUID("tool", lease.tool());
            entry.putUUID("resident", lease.resident());
            list.add(entry);
        }
        saved.put("loans", list);
        return saved;
    }

    // Execution permits do not survive restart. Only physical returns survive; old work is not revived.
    public synchronized void load(UUID colony, CompoundTag saved) {
        queues.keySet().removeIf(family -> family.colony().equals(colony));
        leases(colony).forEach(this::remove);
        List<Lease> recovered = Entries.of(Reader.of(saved), "loans", entry -> {
            Optional<UUID> id = entry.uuid("id");
            Optional<UUID> node = entry.uuid("node");
            Optional<ItemSpec> tools = entry.child("tools").flatMap(ItemSpec::load);
            Optional<UUID> tool = entry.uuid("tool");
            Optional<UUID> resident = entry.uuid("resident");
            return id.isPresent() && node.isPresent() && tools.isPresent() && tool.isPresent() && resident.isPresent()
                ? Optional.of(new Lease(id.get(), new Request(colony, node.get(), tools.get()), tool.get(),
                    resident.get(), Phase.RETURNING)) : Optional.empty();
        });
        for (Lease lease : recovered) {
            if (!leases.containsKey(lease.id()) && !byTool.containsKey(lease.tool())) {
                leases.put(lease.id(), lease);
                byTool.put(lease.tool(), lease.id());
            }
        }
    }

    private Decision decision(Request request, Inventory inventory) {
        return decision(request, inventory, true);
    }

    private Decision decision(Request request, Inventory inventory, boolean execution) {
        Optional<Lease> own = loan(request);
        if (own.isPresent()) {
            return answer(own.get().phase() == Phase.BORROWED ? Status.BORROWED : Status.BUSY);
        }
        if (execution && !first(request)) {
            return answer(Status.BUSY);
        }
        List<Tool> matching = inventory.tools().stream()
            .filter(tool -> Goods.matches(tool.stack(), request.tools())).toList();
        Optional<Tool> available = matching.stream()
            .filter(tool -> tool.whereabouts() == Whereabouts.AVAILABLE && !byTool.containsKey(tool.id())).findFirst();
        if (available.isPresent()) {
            return new Decision(Status.AVAILABLE, available, Optional.empty());
        }
        boolean lent = leases.values().stream().anyMatch(lease -> lease.request().colony().equals(request.colony())
            && Goods.overlap(lease.request().tools(), request.tools()));
        if (lent || matching.stream().anyMatch(tool -> tool.whereabouts() != Whereabouts.UNREACHABLE)) {
            return answer(Status.BUSY);
        }
        if (!matching.isEmpty() || !inventory.complete()) {
            return answer(Status.UNREACHABLE);
        }
        UUID replenishment = replenishment(request.colony(), request.tools());
        boolean promised = inventory.commitments().entrySet().stream()
            .anyMatch(entry -> !entry.getKey().equals(replenishment) && entry.getValue() > 0);
        return promised ? answer(Status.BUSY) : new Decision(Status.MISSING, Optional.empty(),
            Optional.of(new Replenishment(replenishment, request.tools(), 1)));
    }

    private Optional<Lease> loan(Request request) {
        return leases.values().stream().filter(lease -> lease.request().equals(request)).findFirst();
    }

    private boolean first(Request request) {
        LinkedHashSet<UUID> queue = queues.get(Family.of(request));
        if (queue == null) {
            return false;
        }
        for (UUID node : queue) {
            Request turn = new Request(request.colony(), node, request.tools());
            if (loan(turn).isEmpty()) {
                return node.equals(request.node());
            }
        }
        return false;
    }

    private boolean requested(Request request) {
        LinkedHashSet<UUID> queue = queues.get(Family.of(request));
        return queue != null && queue.contains(request.node());
    }

    private void dequeue(Request request) {
        Family family = Family.of(request);
        LinkedHashSet<UUID> queue = queues.get(family);
        if (queue != null) {
            queue.remove(request.node());
            if (queue.isEmpty()) {
                queues.remove(family);
            }
        }
    }

    private void remove(Lease lease) {
        leases.remove(lease.id());
        byTool.remove(lease.tool(), lease.id());
    }

    private static Decision answer(Status status) {
        return new Decision(status, Optional.empty(), Optional.empty());
    }

    private static UUID replenishment(UUID colony, ItemSpec tools) {
        return UUID.nameUUIDFromBytes(("folkways:shared_tool/" + colony + "/" + tools.describe())
            .getBytes(StandardCharsets.UTF_8));
    }
}
