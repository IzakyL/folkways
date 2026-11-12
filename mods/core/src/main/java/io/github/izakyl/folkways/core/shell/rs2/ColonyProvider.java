package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.ParentContainer;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.TaskContainer;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.shell.crafting.NetworkBuffers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** An off-world, zero-energy provider, attached through the native container lifecycle. */
final class ColonyProvider extends AbstractNetworkNode implements PatternProvider, TaskListener {

    WorldPos device;
    AbstractNetworkNode anchor;
    final Set<WorldPos> devices = new HashSet<>();
    private final NetworkBuffers buffers;
    private final Set<ParentContainer> parents = new HashSet<>();
    private final TaskContainer tasks = new TaskContainer(this);
    private Map<Pattern, String> patterns = Map.of();
    private int pushes;
    private final NetworkNodeContainer container = new NetworkNodeContainer() {
        @Override
        public NetworkNode getNode() {
            return ColonyProvider.this;
        }
    };

    ColonyProvider(WorldPos device, NetworkBuffers buffers) {
        this.buffers = buffers;
        this.device = device;
        devices.add(device);
        TerminalRecipes.added(this);
    }

    void connect(NetworkPort port) {
        device = port.identity().home();
        devices.add(device);
        anchor = port.node();
        if (network != anchor.getNetwork()) {
            detach();
            setNetwork(anchor.getNetwork());
            setActive(true);
            network.addContainer(container);
        }
    }

    void detach() {
        if (network != null) {
            network.removeContainer(container);
            setNetwork(null);
        }
    }

    List<Task> tasks() {
        return tasks.getAll();
    }

    CompoundTag save() {
        var data = new CompoundTag();
        data.put("device", device.save());
        var known = new ListTag();
        devices.forEach(at -> known.add(at.save()));
        data.put("devices", known);
        var saved = new ListTag();
        for (Task task : tasks.getAll()) {
            if (task instanceof TaskImpl nativeTask) {
                saved.add(TaskSnapshots.encode(
                    nativeTask.createSnapshot()));
            }
        }
        data.put("tasks", saved);
        return data;
    }

    static ColonyProvider load(CompoundTag data, NetworkBuffers buffers) {
        var device = WorldPos.load(Reader.of(
            data.getCompound("device"))).orElseThrow();
        var provider = new ColonyProvider(device, buffers);
        for (var raw : data.getList("devices", Tag.TAG_COMPOUND)) {
            WorldPos.load(Reader.of((CompoundTag) raw))
                .ifPresent(provider.devices::add);
        }
        for (var raw : data.getList("tasks", Tag.TAG_COMPOUND)) {
            provider.addTask(new TaskImpl(
                TaskSnapshots.decode(
                    (CompoundTag) raw)));
        }
        return provider;
    }

    void patterns(Map<Pattern, String> offered) {
        if (patterns.equals(offered)) {
            return;
        }
        patterns.keySet().stream().filter(pattern -> !offered.containsKey(pattern))
            .forEach(pattern -> parents.forEach(parent -> parent.remove(this, pattern)));
        offered.keySet().stream().filter(pattern -> !patterns.containsKey(pattern))
            .forEach(pattern -> parents.forEach(parent -> parent.add(this, pattern, 0)));
        patterns = Map.copyOf(offered);
    }

    @Override
    public long getEnergyUsage() {
        return 0;
    }

    @Override
    public void onAddedIntoContainer(ParentContainer parent) {
        parents.add(parent);
        tasks.onAddedIntoContainer(parent);
        patterns.keySet().forEach(pattern -> parent.add(this, pattern, 0));
    }

    @Override
    public void onRemovedFromContainer(ParentContainer parent) {
        tasks.onRemovedFromContainer(parent);
        parents.remove(parent);
        patterns.keySet().forEach(pattern -> parent.remove(this, pattern));
    }

    @Override
    public void setNetwork(Network next) {
        if (network != null) {
            tasks.detachAll(network.getComponent(StorageNetworkComponent.class));
        }
        super.setNetwork(next);
        if (next != null) {
            tasks.attachAll(next.getComponent(StorageNetworkComponent.class));
        }
    }

    @Override
    public void addTask(Task task) {
        tasks.add(task, network == null ? List.of() : List.of(network.getComponent(StorageNetworkComponent.class)));
        parents.forEach(parent -> parent.taskAdded(this, task));
    }

    @Override
    public void cancelTask(TaskId task) {
        tasks.cancel(task);
    }

    @Override
    public List<TaskStatus> getTaskStatuses() {
        return tasks.getStatuses();
    }

    @Override
    public long getAmount(ResourceKey resource) {
        return tasks.getAmount(resource);
    }

    @Override
    public void receivedExternalIteration() {
    }

    @Override
    public void receivedExternalIteration(Pattern pattern) {
        if (network != null) {
            var provider = network.getComponent(AutocraftingNetworkComponent.class).getProviderByPattern(pattern);
            if (provider != null) {
                provider.receivedExternalIteration();
            }
        }
    }

    @Override
    public Result accept(Pattern pattern, Collection<ResourceAmount> inputs, Action action) {
        List<ItemStack> goods = new ArrayList<>();
        for (var input : inputs) {
            if (!(input.resource() instanceof ItemResource item)) {
                return Result.REJECTED;
            }
            goods.addAll(NetworkBuffers.stacks(item.toItemStack(), input.amount()));
        }
        String recipe = patterns.get(pattern);
        boolean admitted = recipe != null && network != null
            && buffers.canAccept(device, network, recipe, goods);
        if (action == Action.SIMULATE) {
            return admitted ? Result.ACCEPTED : Result.REJECTED;
        }
        if (!admitted || !buffers.accept(device, network, recipe, goods)) {
            // RS2 2.0.9 only logs a rejected EXECUTE after withdrawing its internal inputs.
            // Ownership must therefore remain durable here until the stacks can be returned.
            buffers.recover(device, network, goods);
            return Result.REJECTED;
        }
        pushes++;
        return Result.ACCEPTED;
    }

    boolean isBusy() {
        return buffers.busy();
    }

    int pushes() {
        return pushes;
    }

    long buffered(ItemResource item) {
        return buffers.count(device, item.toItemStack());
    }

    @Override
    public void doWork() {
        if (network != null) {
            tasks.step(network, this, this);
        }
    }

    void destroy() {
        detach();
        TerminalRecipes.removed(this);
    }
}
