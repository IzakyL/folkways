package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.facility.api.control.PortObservation;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class PortHooks implements Ports.Hooks {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store_verdicts");

    private static PortHooks installed;

    private net.minecraft.server.MinecraftServer observedServer;
    private final Map<WorldPos, Component> unclaimedVerdicts = new HashMap<>();

    private PortHooks() {
    }

    public static void install() {
        PortHooks hooks = new PortHooks();
        installed = hooks;
        Ports.registerItem(ResourceLocation.fromNamespaceAndPath("folkways_core", "containers"), Ports.CONTAINERS,
            (level, pos) -> {
                hooks.observe(level.getServer());
                return Containers.at(level, pos).map(container -> new SlotPort(
                    new PortIdentity(new PortIdentity.Block(WorldPos.of(level, Containers.chestAnchor(level, pos))),
                        WorldPos.of(level, pos)), container));
            });
        StoreAccess.install((level, pos) -> Ports.itemAt(level, WorldPos.of(level, pos)), hooks::observation,
            Facilities::attachedStore, Facilities::owningStorePart);
        Ports.installItems((level, pos, priority, port) ->
            StoreAccess.checked(level, pos, priority, Ports.CONTAINERS, port), hooks);
        Containers.installPorts(PortHooks::anchor, (level, pos) -> level instanceof ServerLevel server
            ? StoreAccess.at(server, pos).isPresent() : Containers.at(level, pos).isPresent());
    }

    private void observe(net.minecraft.server.MinecraftServer server) {
        if (observedServer != server) {
            unclaimedVerdicts.clear();
            observedServer = server;
        }
    }

    private static BlockPos anchor(net.minecraft.world.level.Level level, BlockPos clicked) {
        if (level instanceof ServerLevel server) {
            var port = StoreAccess.at(server, clicked);
            if (port.isPresent()) {
                Object key = port.get().identity().key();
                Optional<BlockPos> registered = Colonies.all(server.getServer()).stream()
                    .flatMap(colony -> colony.storesIn(server.dimension()).stream())
                    .filter(at -> at.in(server)).map(at -> at.block(server)).distinct()
                    .filter(at -> StoreAccess.at(server, at)
                        .filter(other -> other.identity().key().equals(key)).isPresent())
                    .min(BlockPos::compareTo);
                if (registered.isPresent()) {
                    return Containers.chestAnchor(level, registered.get());
                }
            }
        }
        return Containers.chestAnchor(level, clicked);
    }

    @Override
    public void changed(ServerLevel level, BlockPos pos) {
        observe(level.getServer());
        InventoryFeedback.store(level, WorldPos.of(level, pos));
    }

    @Override
    public void observe(ServerLevel level, PortObservation observation) {
        WorldPos at = observation.at();
        Component reason = observation.reason();
        observe(level.getServer());
        boolean kept = false;
        for (Colony colony : Colonies.all(level.getServer())) {
            if (colony.claimAt(at).isEmpty() && !colony.storesIn(level.dimension()).contains(at)
                && colony.inventory().snapshot().store(at).isEmpty()) {
                continue;
            }
            kept = true;
            CompoundTag bag = colony.kept(OWNER);
            ListTag verdicts = bag.getList("verdicts", Tag.TAG_COMPOUND);
            verdicts.removeIf(tag -> WorldPos.load(Reader.of((CompoundTag) tag)).filter(at::equals).isPresent());
            CompoundTag verdict = at.save();
            verdict.put("reason", ComponentSerialization.CODEC.encodeStart(
                level.registryAccess().createSerializationContext(NbtOps.INSTANCE), reason).getOrThrow());
            verdicts.add(verdict);
            bag.put("verdicts", verdicts);
            colony.keep(OWNER, bag);
            colony.inventoryFeedback().changed(Stash.at(at));
        }
        // Direct port users may encounter a violation before any colony owns the block.
        if (!kept) {
            unclaimedVerdicts.put(at, reason.copy());
        }
    }

    @Override
    public Optional<PortObservation> observation(WorldPos at) {
        var running = ServerLifecycleHooks.getCurrentServer();
        var server = running == null ? observedServer : running;
        if (server == null) {
            return Optional.empty();
        }
        observe(server);
        for (Colony colony : Colonies.all(server)) {
            for (Tag tag : colony.kept(OWNER).getList("verdicts", Tag.TAG_COMPOUND)) {
                CompoundTag verdict = (CompoundTag) tag;
                if (WorldPos.load(Reader.of(verdict)).filter(at::equals).isPresent()) {
                    return ComponentSerialization.CODEC.parse(
                        server.registryAccess().createSerializationContext(NbtOps.INSTANCE), verdict.get("reason"))
                        .result().map(reason -> new PortObservation(at, reason));
                }
            }
        }
        return Optional.ofNullable(unclaimedVerdicts.get(at)).map(reason -> new PortObservation(at, reason));
    }

    public static void registered(Colony colony, WorldPos at) {
        if (installed != null) {
            installed.unclaimedVerdicts.remove(at);
        }
        CompoundTag bag = colony.kept(OWNER);
        ListTag verdicts = bag.getList("verdicts", Tag.TAG_COMPOUND);
        if (verdicts.removeIf(tag -> WorldPos.load(Reader.of((CompoundTag) tag)).filter(at::equals).isPresent())) {
            bag.put("verdicts", verdicts);
            colony.keep(OWNER, bag);
            colony.inventoryFeedback().changed(Stash.at(at));
        }
    }
}
