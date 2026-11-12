package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Accepted inputs belong to the colony. Network task cancellation never changes this ledger. */
public final class NetworkBuffers implements Facing {

    private static final Map<UUID, Set<NetworkBuffers>> ACTIVE = new LinkedHashMap<>();

    public record Access(StorePort port, Object network, StorePort delivery, Consumer<UUID> cancel) {
        public Access(StorePort port, Object network, StorePort delivery) {
            this(port, network, delivery, task -> { });
        }
    }

    private static final class Batch {
        private final UUID id;
        private final WorldPos device;
        private String recipe;
        private final List<ItemStack> goods = new ArrayList<>();
        private final List<ItemStack> received = new ArrayList<>();
        private final Set<WorldPos> devices = new LinkedHashSet<>();
        private final Set<UUID> commissions = new LinkedHashSet<>();
        private Object network;
        private UUID worker;
        private int stage;
        private boolean returning;
        private CompoundTag continuation = new CompoundTag();
        private final Set<Integer> committed = new HashSet<>();
        private final Set<Integer> finished = new HashSet<>();
        private Grown work;
        private List<Hold> holds = List.of();
        private long runs = 1;
        private final List<Node> active = new ArrayList<>();
        private final Set<WorldPos> workingAt = new HashSet<>();

        Batch(UUID id, WorldPos device, String recipe) {
            this.id = id;
            this.device = device;
            this.recipe = recipe;
            devices.add(device);
        }
    }

    private final Colony colony;
    private final ResourceLocation owner;
    private final MinecraftServer server;
    private final ColonyRecipes recipes;
    private final List<Batch> batches = new ArrayList<>();
    private List<Access> access = List.of();

    public NetworkBuffers(Colony colony, ResourceLocation owner, MinecraftServer server, ColonyRecipes recipes) {
        this.colony = colony;
        this.owner = owner;
        this.server = server;
        this.recipes = recipes;
        ACTIVE.computeIfAbsent(colony.id(), ignored -> new HashSet<>()).add(this);
        for (var raw : colony.kept(owner).getList("batches", Tag.TAG_COMPOUND)) {
            CompoundTag data = (CompoundTag) raw;
            var device = WorldPos.load(Reader.of(data.getCompound("device")));
            if (device.isEmpty() || !data.hasUUID("id")) {
                continue;
            }
            Batch batch = new Batch(data.getUUID("id"), device.get(), data.getString("recipe"));
            for (var task : data.getList("commissions", Tag.TAG_INT_ARRAY)) {
                batch.commissions.add(NbtUtils.loadUUID(task));
            }
            batch.stage = data.getInt("stage");
            batch.returning = data.getBoolean("returning");
            batch.continuation = data.getCompound("continuation").copy();
            for (int stage : data.getIntArray("committed")) {
                batch.committed.add(stage);
            }
            batch.runs = Math.max(1, data.getLong("runs"));
            for (int stage : data.getIntArray("finished")) {
                batch.finished.add(stage);
            }
            batch.worker = data.hasUUID("worker") ? data.getUUID("worker") : null;
            for (var goods : data.getList("goods", Tag.TAG_COMPOUND)) {
                ItemStack.parse(server.registryAccess(), goods).ifPresent(batch.goods::add);
            }
            for (var goods : data.getList("received", Tag.TAG_COMPOUND)) {
                ItemStack.parse(server.registryAccess(), goods).ifPresent(batch.received::add);
            }
            if (batch.received.isEmpty()) {
                batch.goods.forEach(stack -> batch.received.add(stack.copy()));
            }
            for (var at : data.getList("devices", Tag.TAG_COMPOUND)) {
                WorldPos.load(Reader.of((CompoundTag) at)).ifPresent(batch.devices::add);
            }
            batches.add(batch);
        }
    }

    public boolean busy() {
        return free().isEmpty() && batches.stream().noneMatch(this::collecting);
    }

    private boolean collecting(Batch batch) {
        return batch.worker != null && batch.stage == 0 && batch.finished.isEmpty()
            && batch.active.stream().noneMatch(node -> colony.isHeld(node.id()))
            && body(batch.worker).filter(NetworkBuffers::enabled).isPresent();
    }

    private Optional<Body> free() {
        return free(List.of());
    }

    private Optional<Body> free(List<Node> work) {
        Set<UUID> reserved = new HashSet<>();
        for (NetworkBuffers buffer : ACTIVE.getOrDefault(colony.id(), Set.of())) {
            buffer.batches.forEach(batch -> {
                if (batch.worker != null) {
                    reserved.add(batch.worker);
                }
            });
        }
        return colony.residents().stream().filter(resident -> !reserved.contains(resident.id()))
            .flatMap(resident -> body(resident.id()).stream())
            .filter(body -> body.mob().isAlive() && body.doing().isEmpty() && enabled(body))
            .filter(body -> work.stream().allMatch(node ->
                node.worker().filter(id -> !id.equals(body.id())).isEmpty()
                    && node.spec().vocation().filter(trade -> body.licences().of(trade).isEmpty()).isEmpty()))
            .findFirst();
    }

    private Optional<Body> body(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity != null) {
                return Bodies.of(entity);
            }
        }
        return Optional.empty();
    }

    static boolean enabled(Body body) {
        return body.licences().of(Vocations.required(Vocations.NETWORK_SERVICE)).isPresent();
    }

    public boolean canAccept(WorldPos device, Object network, String recipe, List<ItemStack> inputs) {
        if (inputs.isEmpty() || network == null) {
            return false;
        }
        var entry = recipes.find(recipe);
        if (entry.isEmpty()) {
            return false;
        }
        var pending = batches.stream().filter(batch -> !batch.returning && batch.device.equals(device)
            && batch.recipe.equals(recipe) && network.equals(batch.network) && collecting(batch)).findFirst();
        if (pending.isPresent() && entry.get().prepare(pending.get().runs + 1)
                .filter(change -> change.holds().stream().allMatch(Hold::mayTake)).isPresent()) {
            return true;
        }
        return entry.get().prepare(1).filter(change -> change.holds().stream().allMatch(Hold::mayTake)
            && free(change.replacement().nodes()).isPresent()).isPresent();
    }

    public boolean accept(WorldPos device, Object network, String recipe, List<ItemStack> inputs) {
        if (!canAccept(device, network, recipe, inputs)) {
            return false;
        }
        // Combine uncollected iterations through the workshop's own sizing, so a furnace prices fuel for the load.
        var pending = batches.stream().filter(batch -> batch.device.equals(device) && batch.recipe.equals(recipe)
            && network.equals(batch.network) && collecting(batch)).findFirst();
        if (pending.isPresent() && !inputs.isEmpty()) {
            Batch batch = pending.get();
            // Reprice without this batch's old booking consuming room or claiming its own fuel stock.
            batch.holds.forEach(hold -> hold.release(Ending.REVOKED));
            var combined = recipes.find(recipe).flatMap(entry -> entry.prepare(batch.runs + 1))
                .filter(change -> reserve(change.holds()));
            if (combined.isPresent()) {
                List<Node> withdrawn = List.copyOf(batch.active);
                batch.active.clear();
                withdrawn.forEach(node -> colony.withdraw(owner, node.id()));
                batch.work = combined.get().replacement();
                batch.holds = combined.get().holds();
                checkpoint(batch);
                batch.runs++;
                NetworkTask.current().ifPresent(batch.commissions::add);
                inputs.forEach(stack -> {
                    batch.goods.add(stack.copy());
                    batch.received.add(stack.copy());
                });
                save();
                return true;
            }
            if (!reserve(batch.holds)) {
                throw new IllegalStateException("could not restore an uncollected network batch's reservations");
            }
        }
        var work = recipes.find(recipe).flatMap(entry -> entry.prepare(1));
        Optional<Body> worker = work.flatMap(change -> free(change.replacement().nodes()));
        if (worker.isEmpty() || inputs.isEmpty() || !reserve(work.orElseThrow().holds())) {
            return false;
        }
        Batch batch = new Batch(UUID.randomUUID(), device, recipe);
        batch.network = network;
        NetworkTask.current().ifPresent(batch.commissions::add);
        batch.worker = worker.get().id();
        batch.work = work.orElseThrow().replacement();
        batch.holds = work.orElseThrow().holds();
        checkpoint(batch);
        inputs.forEach(stack -> {
            batch.goods.add(stack.copy());
            batch.received.add(stack.copy());
        });
        access.stream().filter(at -> at.network().equals(network))
            .forEach(at -> batch.devices.add(at.port().identity().home()));
        batches.add(batch);
        save();
        return true;
    }

    /** RS2 has already withdrawn these stacks when an execute call is rejected. Keep them until return succeeds. */
    public void recover(WorldPos device, Object network, List<ItemStack> inputs) {
        Batch batch = new Batch(UUID.randomUUID(), device, "");
        batch.network = network;
        batch.returning = true;
        NetworkTask.current().ifPresent(batch.commissions::add);
        inputs.forEach(stack -> batch.goods.add(stack.copy()));
        batches.add(batch);
        save();
    }

    private void checkpoint(Batch batch) {
        recipes.find(batch.recipe).flatMap(entry -> entry.workshop()
            .saveWork(batch.work, batch.holds, server.registryAccess())).ifPresent(data -> batch.continuation = data);
    }

    public long count(WorldPos device, ItemStack item) {
        return batches.stream().filter(batch -> batch.device.equals(device)).flatMap(batch -> batch.goods.stream())
            .filter(stack -> ItemStack.isSameItemSameComponents(stack, item)).mapToLong(ItemStack::getCount).sum();
    }

    public void tick(List<Access> available) {
        access = List.copyOf(available);
        if (!recipes.ready()) {
            return;
        }
        boolean changed = false;
        for (Batch batch : List.copyOf(batches)) {
            var connected = access.stream().filter(at -> at.network().equals(batch.network)
                || batch.devices.contains(at.port().identity().home())).findFirst();
            if (connected.isPresent()) {
                batch.network = connected.get().network();
                for (var at : access) {
                    if (at.network().equals(batch.network)) {
                        changed |= batch.devices.add(at.port().identity().home());
                    }
                }
            }
            boolean workerGone = batch.worker != null && body(batch.worker)
                .filter(worker -> worker.mob().isAlive() && enabled(worker)).isEmpty();
            boolean recipeGone = !batch.recipe.isEmpty() && recipes.find(batch.recipe).isEmpty();
            if (workerGone || recipeGone || batch.returning) {
                List<Node> abandoned = List.copyOf(batch.active);
                batch.active.clear();
                abandoned.forEach(node -> colony.withdraw(owner, node.id()));
                if (workerGone && !recipeGone && !batch.returning && batch.stage == 0 && !batch.goods.isEmpty()) {
                    batch.worker = null;
                    batch.worker = free(batch.work == null ? List.of() : batch.work.nodes()).map(Body::id).orElse(null);
                    changed = true;
                    if (batch.worker != null) {
                        continue;
                    }
                }
                batch.returning = true;
                batch.holds.forEach(hold -> hold.release(Ending.REVOKED));
                batch.holds = List.of();
                if (connected.isPresent()) {
                    batch.commissions.forEach(connected.get().cancel());
                    batch.commissions.clear();
                }
                if (batch.commissions.isEmpty() && (batch.goods.isEmpty() || returnInputs(batch))) {
                    batches.remove(batch);
                }
                changed = true;
                continue;
            }
            if (!batch.active.isEmpty()) {
                boolean invalid = batch.workingAt.stream().anyMatch(at -> access.stream()
                    .noneMatch(value -> value.port().identity().home().equals(at)));
                if (invalid && batch.active.stream().noneMatch(node -> colony.isHeld(node.id()))) {
                    List<Node> abandoned = List.copyOf(batch.active);
                    batch.active.clear();
                    abandoned.forEach(node -> colony.withdraw(owner, node.id()));
                }
                continue;
            }
            if (batch.recipe.isEmpty()) {
                var recovered = recipes.forInputs(batch.goods);
                if (recovered.isPresent()) {
                    batch.recipe = recovered.get().reference();
                    changed = true;
                }
            }
            var entry = recipes.find(batch.recipe);
            if (entry.isEmpty()) {
                continue;
            }
            var work = work(batch);
            if (work.isEmpty()) {
                continue;
            }
            if (batch.worker == null) {
                batch.worker = free(work.get().nodes()).map(Body::id).orElse(null);
                changed |= batch.worker != null;
            }
            Optional<Body> worker = batch.worker == null ? Optional.empty() : body(batch.worker);
            if (worker.isEmpty() || !enabled(worker.get())) {
                batch.returning = true;
                changed = true;
                continue;
            }
            List<Node> steps = ordered(work.get());
            int last = steps.size() + entry.get().recipe().outputs().size();
            if (batch.stage > last) {
                batch.holds.forEach(hold -> hold.release(Ending.DONE));
                batches.remove(batch);
                changed = true;
                continue;
            }
            List<Node> remaining = new ArrayList<>();
            Map<UUID, Integer> stages = new LinkedHashMap<>();
            batch.workingAt.clear();
            for (int stage = batch.stage; stage <= last; stage++) {
                if (batch.finished.contains(stage)) {
                    continue;
                }
                if (stage > 0 && stage <= steps.size()) {
                    int index = stage;
                    Node node = ServiceNode.bind(steps.get(stage - 1), batch.worker,
                        batch.committed.contains(stage), () -> {
                            batch.committed.add(index);
                            save();
                        });
                    remaining.add(node);
                    stages.put(node.id(), stage);
                    continue;
                }
                boolean take = stage == 0;
                var target = access.stream().filter(at -> at.network().equals(batch.network))
                    .map(Access::port).filter(at -> at.operations().contains(take
                        ? StorePort.Operation.TAKE : StorePort.Operation.PUT))
                    .filter(at -> take ? StoreRule.takes(colony.storeRules(at.identity().home()))
                        : StoreRule.stores(colony.storeRules(at.identity().home())))
                    .filter(at -> at.identity().home().in(worker.get().mob().level()))
                    .filter(at -> at.identity().home().level(server)
                        .equals(entry.get().workshop().site().where().level(server))).findFirst();
                if (target.isEmpty()) {
                    break;
                }
                WorldPos at = target.get().identity().home();
                ServerLevel level = at.level(server).orElseThrow();
                var stances = Stances.of(level, Reach.workableCells(level, at.block(level)));
                if (stances.isEmpty()) {
                    break;
                }
                List<ItemStack> goods;
                if (take) {
                    goods = batch.goods.stream().map(ItemStack::copy).toList();
                } else {
                    Amount amount = entry.get().materials(batch.runs).outputs().get(stage - steps.size() - 1);
                    goods = stacks(Goods.display(amount.spec()), amount.count());
                }
                List<Amount> leaves = take ? goods.stream().map(stack -> new Amount(Goods.specOf(stack),
                    stack.getCount())).toList() : goods.stream().map(stack -> new Amount(
                        Goods.specOf(stack), -stack.getCount())).toList();
                var spec = NodeSpec.of(UUID.randomUUID(), owner, new WorkSite.AtBlock(at), stances.get(),
                        Workload.Once.of(10)).leaves(leaves)
                    .vocation(Vocations.required(Vocations.NETWORK_SERVICE)).gesture(WorkGesture.SWING)
                    .doing(take ? Doings.FETCHING : Doings.STOWING);
                remaining.add(TransferNode.obligation(spec.done(), new Stash(at), goods, take, batch.worker,
                    (world, operation) -> port(batch, at, operation).map(nativePort -> take
                        ? new BufferPort(batch, nativePort) : nativePort)));
                stages.put(remaining.getLast().id(), stage);
                batch.workingAt.add(at);
            }
            if (remaining.size() != last - batch.stage + 1 - batch.finished.size()) {
                continue;
            }
            // Loading can settle only after cooking, while fueling must still be allowed to run.
            // Keep the workshop's dependencies rather than serializing its independent steps.
            List<Before> links = new ArrayList<>(work.get().links().stream()
                .filter(link -> stages.containsKey(link.from()) && stages.containsKey(link.to())).toList());
            for (Node before : remaining) {
                int from = stages.get(before.id());
                for (Node after : remaining) {
                    int to = stages.get(after.id());
                    if (from == 0 && to > 0 || from > 0 && from <= steps.size() && to > steps.size()
                            || from > steps.size() && to == from + 1) {
                        links.add(new Before(before.id(), after.id()));
                    }
                }
            }
            batch.active.addAll(remaining);
            colony.submit(owner, remaining.getFirst().spec().site().where().level(server).orElseThrow().dimension(),
                new Grown(remaining, links).following(batch.holds.stream().flatMap(hold -> hold.after().stream())
                    .distinct().flatMap(before -> remaining.stream().map(node -> new Before(before, node.id())))
                    .toList()).byOneWorker(), (id, ending) -> {
                    if (batch.active.removeIf(node -> node.id().equals(id)) && ending instanceof Ending.Done) {
                        batch.finished.add(stages.get(id));
                        while (batch.finished.remove(batch.stage)) {
                            batch.stage++;
                        }
                    }
                    save();
                });
        }
        if (changed) {
            save();
        }
    }

    private boolean returnInputs(Batch batch) {
        for (Access at : access) {
            StorePort port = at.port();
            if (!at.network().equals(batch.network) || !port.operations().contains(StorePort.Operation.PUT)) {
                continue;
            }
            var level = port.identity().home().level(server);
            if (level.isPresent() && port.put(level.get(), batch.goods, List.of(), true, true).done()
                && port.put(level.get(), batch.goods, List.of(), true, false).done()) {
                batch.goods.clear();
                return true;
            }
        }
        return false;
    }

    private Optional<StorePort> port(Batch batch, WorldPos at, StorePort.Operation operation) {
        return access.stream().filter(value -> value.network().equals(batch.network))
            .map(value -> operation == StorePort.Operation.PUT ? value.delivery() : value.port())
            .filter(value -> value.identity().home().equals(at))
            .filter(value -> at.level(server).map(level -> colony.storesIn(level.dimension()).contains(at)).orElse(false)
                && value.usability().usable() && value.operations().contains(operation)).findFirst();
    }

    private Optional<Grown> work(Batch batch) {
        if (batch.work == null) {
            recipes.find(batch.recipe).flatMap(entry -> batch.continuation.isEmpty() ? entry.prepare(batch.runs)
                : entry.workshop().resumeWork(batch.continuation, server.registryAccess()))
                .filter(change -> reserve(change.holds())).ifPresent(change -> {
                    batch.work = change.replacement();
                    batch.holds = change.holds();
                    checkpoint(batch);
                });
        }
        return Optional.ofNullable(batch.work);
    }

    private static boolean reserve(List<Hold> holds) {
        List<Hold> taken = new ArrayList<>();
        for (Hold hold : holds) {
            if (hold.take().isPresent()) {
                taken.forEach(previous -> previous.release(Ending.REVOKED));
                return false;
            }
            taken.add(hold);
        }
        return true;
    }

    // Keep the workshop's declaration order among ready steps. Regenerated node UUIDs after load must not
    // change the stage numbers persisted in stage/finished.
    private static List<Node> ordered(Grown work) {
        List<Node> result = new ArrayList<>();
        Set<UUID> done = new HashSet<>();
        while (result.size() < work.nodes().size()) {
            for (Node node : work.nodes()) {
                if (!done.contains(node.id()) && work.links().stream().filter(link -> link.to().equals(node.id()))
                        .allMatch(link -> done.contains(link.from()))) {
                    result.add(node);
                    done.add(node.id());
                }
            }
        }
        return result;
    }

    public static List<ItemStack> stacks(ItemStack item, long count) {
        List<ItemStack> result = new ArrayList<>();
        while (count > 0) {
            int size = (int) Math.min(item.getMaxStackSize(), count);
            result.add(item.copyWithCount(size));
            count -= size;
        }
        return result;
    }

    private void save() {
        ListTag saved = new ListTag();
        for (Batch batch : batches) {
            CompoundTag data = new CompoundTag();
            data.putUUID("id", batch.id);
            data.put("device", batch.device.save());
            data.putString("recipe", batch.recipe);
            ListTag commissions = new ListTag();
            batch.commissions.forEach(task -> commissions.add(NbtUtils.createUUID(task)));
            data.put("commissions", commissions);
            data.putInt("stage", batch.stage);
            data.putBoolean("returning", batch.returning);
            data.put("continuation", batch.continuation.copy());
            data.putIntArray("committed", batch.committed.stream().mapToInt(Integer::intValue).sorted().toArray());
            data.putLong("runs", batch.runs);
            data.putIntArray("finished", batch.finished.stream().mapToInt(Integer::intValue).sorted().toArray());
            if (batch.worker != null) {
                data.putUUID("worker", batch.worker);
            }
            ListTag goods = new ListTag();
            batch.goods.forEach(stack -> goods.add(stack.save(server.registryAccess())));
            data.put("goods", goods);
            ListTag received = new ListTag();
            batch.received.forEach(stack -> received.add(stack.save(server.registryAccess())));
            data.put("received", received);
            ListTag devices = new ListTag();
            batch.devices.forEach(at -> devices.add(at.save()));
            data.put("devices", devices);
            saved.add(data);
        }
        CompoundTag data = new CompoundTag();
        data.put("batches", saved);
        colony.keep(owner, data);
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.toString().equals("folkways_vanilla:wares") || batches.isEmpty()) {
            return Optional.empty();
        }
        List<Board.Row> rows = new ArrayList<>();
        rows.add(Board.Row.heading(Component.translatable("folkways.network.pending")));
        for (Batch batch : batches) {
            List<ItemStack> goods = batch.goods.isEmpty() ? recipes.find(batch.recipe).stream()
                .flatMap(entry -> entry.recipe().outputs().stream()).map(amount -> Goods.display(amount.spec()))
                .toList() : batch.goods;
            for (ItemStack stack : goods) {
                rows.add(Board.Row.of(stack.copy(), stack.getHoverName(), Component.translatable(
                    "folkways.network.buffer", stack.getCount(), batch.device.toString())));
            }
        }
        return Optional.of(Board.of(rows));
    }

    // The normal pack-keeping mechanism protects obligations from idle unloading and unrelated colony work.
    public List<Amount> keeps(UUID resident) {
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        for (Batch batch : batches) {
            if (!resident.equals(batch.worker)) {
                continue;
            }
            batch.received.forEach(stack -> held.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum));
            work(batch).ifPresent(work -> {
                List<Node> steps = ordered(work);
                for (int index = 0; index < steps.size(); index++) {
                    if (index + 1 >= batch.stage && !batch.finished.contains(index + 1)
                            && !batch.committed.contains(index + 1)) {
                        continue;
                    }
                    NodeSpec spec = steps.get(index).spec();
                    spec.needs().forEach(need -> subtract(held, need.spec(), need.count()));
                    spec.gives().forEach(amount -> held.merge(amount.spec(), amount.count(), Long::sum));
                    spec.leaves().forEach(amount -> held.merge(amount.spec(), amount.count(), Long::sum));
                }
                recipes.find(batch.recipe).ifPresent(entry -> {
                    int delivered = Math.max(0, batch.stage - steps.size() - 1);
                    for (int index = 0; index < Math.min(delivered, entry.recipe().outputs().size()); index++) {
                        Amount output = entry.materials(batch.runs).outputs().get(index);
                        subtract(held, output.spec(), output.count());
                    }
                });
            });
        }
        return held.entrySet().stream().filter(entry -> entry.getValue() > 0)
            .map(entry -> new Amount(entry.getKey(), entry.getValue())).toList();
    }

    private static void subtract(Map<ItemSpec, Long> held,
            ItemSpec goods, long count) {
        for (var entry : held.entrySet()) {
            if (Goods.includes(goods, entry.getKey())) {
                long taken = Math.min(Math.max(0, entry.getValue()), count);
                entry.setValue(entry.getValue() - taken);
                count -= taken;
            }
        }
    }

    public void close() {
        var active = ACTIVE.get(colony.id());
        if (active != null) {
            active.remove(this);
            if (active.isEmpty()) {
                ACTIVE.remove(colony.id());
            }
        }
        for (Batch batch : batches) {
            List<Node> withdrawn = List.copyOf(batch.active);
            batch.active.clear();
            withdrawn.forEach(node -> colony.withdraw(owner, node.id()));
            batch.holds.forEach(hold -> hold.release(Ending.REVOKED));
        }
        save();
    }

    private final class BufferPort implements StorePort {
        private final Batch batch;
        private final StorePort nativePort;

        BufferPort(Batch batch, StorePort nativePort) {
            this.batch = batch;
            this.nativePort = nativePort;
        }

        @Override
        public PortIdentity identity() {
            return nativePort.identity();
        }

        @Override
        public Usability usability() {
            return nativePort.usability();
        }

        @Override
        public Set<Operation> operations() {
            return Set.of(Operation.TAKE, Operation.PUT);
        }

        @Override
        public PortReader reader(ServerLevel level, List<StoreRule> rules) {
            throw new UnsupportedOperationException("obligation buffers are never colony stock");
        }

        @Override
        public TransferResult take(ServerLevel level, List<Pull> requested, List<StoreRule> rules, boolean simulate) {
            if (!StoreRule.takes(rules) || !usability().usable()) {
                return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
            }
            SimpleContainer contents = new SimpleContainer(batch.goods.size());
            for (int slot = 0; slot < batch.goods.size(); slot++) {
                contents.setItem(slot, batch.goods.get(slot).copy());
            }
            List<ItemStack> moved = new ArrayList<>();
            for (Pull pull : requested) {
                if (!(pull instanceof Pull.Exact exact)) {
                    return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
                }
                ItemStack wanted = exact.stack();
                ItemStack taken = Containers.extract(contents,
                    stack -> ItemStack.isSameItemSameComponents(stack, wanted), wanted.getCount());
                if (taken.getCount() != wanted.getCount()) {
                    return new TransferResult.Refused(TransferResult.Why.NOTHING_TO_TAKE);
                }
                moved.add(taken);
            }
            if (!simulate) {
                batch.goods.clear();
                for (int slot = 0; slot < contents.getContainerSize(); slot++) {
                    if (!contents.getItem(slot).isEmpty()) {
                        batch.goods.add(contents.getItem(slot).copy());
                    }
                }
                save();
            }
            return new TransferResult.Done(moved);
        }

        @Override
        public TransferResult put(ServerLevel level, List<ItemStack> goods, List<StoreRule> rules,
                boolean directed, boolean simulate) {
            if (!simulate) {
                goods.forEach(stack -> batch.goods.add(stack.copy()));
                save();
            }
            return new TransferResult.Done(goods);
        }
    }
}
