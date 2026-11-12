package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
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

public final class PortHooks implements StorePorts.Hooks {

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
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "containers"), StorePorts.CONTAINERS,
            (level, pos) -> {
                hooks.observe(level.getServer());
                return Containers.at(level, pos).map(container -> new SlotPort(
                    new PortIdentity(new PortIdentity.Block(WorldPos.of(level, Containers.chestAnchor(level, pos))),
                        WorldPos.of(level, pos)), container));
            });
        StorePorts.install(hooks);
        Containers.installPorts(PortHooks::anchor, (level, pos) -> level instanceof ServerLevel server
            ? StorePorts.at(server, pos).isPresent() : Containers.at(level, pos).isPresent());
    }

    private void observe(net.minecraft.server.MinecraftServer server) {
        if (observedServer != server) {
            unclaimedVerdicts.clear();
            observedServer = server;
        }
    }

    private static BlockPos anchor(net.minecraft.world.level.Level level, BlockPos clicked) {
        if (level instanceof ServerLevel server) {
            var port = StorePorts.at(server, clicked);
            if (port.isPresent()) {
                Object key = port.get().identity().key();
                Optional<BlockPos> registered = Colonies.all(server.getServer()).stream()
                    .flatMap(colony -> colony.storesIn(server.dimension()).stream())
                    .filter(at -> at.in(server)).map(at -> at.block(server)).distinct()
                    .filter(at -> StorePorts.at(server, at)
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
    public void condemn(ServerLevel level, WorldPos at, Component reason) {
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
    public Optional<Component> condemned(WorldPos at) {
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
                        .result();
                }
            }
        }
        return Optional.ofNullable(unclaimedVerdicts.get(at));
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
