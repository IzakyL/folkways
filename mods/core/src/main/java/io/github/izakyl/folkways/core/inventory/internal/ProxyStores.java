package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Claim transactions and remembered membership of enrolled physical access points. */
public final class ProxyStores {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store_proxies");

    private final Colony colony;
    private boolean releasing;

    public ProxyStores(Colony colony) {
        this.colony = colony;
    }

    private static Optional<ItemPort> port(MinecraftServer server, WorldPos at) {
        return WorldSpaces.resolve(server, at).flatMap(block -> StoreAccess.at(block.level(), block.cell()));
    }

    /** Preflight uses the same live declarations as the claim transaction, before any member is marked. */
    public static Optional<Component> refusal(MinecraftServer server, Colony colony, ResourceLocation owner,
                                               WorldPos at) {
        ItemPort proxy = port(server, at).orElse(null);
        if (proxy == null || proxy.proxyMembers().isEmpty()) {
            return Optional.empty();
        }
        if (!StoreAccess.usability(at.level(server).orElseThrow(), proxy).usable()) {
            return Optional.of(((Usability.Unusable) StoreAccess.usability(at.level(server).orElseThrow(), proxy)).reason());
        }
        for (WorldPos member : proxy.proxyMembers().orElseThrow()) {
            ItemPort store = port(server, member).orElse(null);
            boolean occupied = Colonies.all(server).stream().anyMatch(other -> other.claimAt(member)
                .filter(claim -> !other.id().equals(colony.id()) || !claim.owner().equals(owner)).isPresent());
            if (!member.sameRealm(at) || member.equals(at) || store == null || store.proxyMembers().isPresent()
                || occupied || WorldSpaces.resolve(server, member)
                    .filter(block -> block.level().getBlockEntity(block.cell()) != null).isEmpty()) {
                return Optional.of(Component.translatable("folkways.store.proxy.unavailable"));
            }
            if (!StoreAccess.usability(member.level(server).orElseThrow(), store).usable()) {
                return Optional.of(Component.translatable("folkways.store.proxy.member_refused",
                    ((Usability.Unusable) StoreAccess.usability(member.level(server).orElseThrow(), store)).reason()));
            }
        }
        return Optional.empty();
    }

    public Optional<Claim> hold(MinecraftServer server, ResourceLocation owner, Claimed what,
                                Supplier<Optional<Claim>> single) {
        WorldPos at = what instanceof Claimed.Block block ? block.cell() : null;
        ItemPort proxy = at == null ? null : port(server, at).orElse(null);
        if (proxy == null || proxy.proxyMembers().isEmpty()) {
            return single.get();
        }
        if (refusal(server, colony, owner, at).isPresent()) {
            return Optional.empty();
        }
        Optional<Claim> held = single.get();
        if (held.isEmpty()) {
            return held;
        }
        List<Claim> added = new ArrayList<>();
        added.add(held.get());
        for (WorldPos member : proxy.proxyMembers().orElseThrow()) {
            if (colony.claimAt(member).isPresent()) {
                continue;
            }
            Optional<Claim> joined = colony.hold(owner, new Claimed.Block(member));
            if (joined.isEmpty()) {
                releasing = true;
                try {
                    added.forEach(claim -> colony.release(claim.id(), ClaimRelease.LET_GO));
                } finally {
                    releasing = false;
                }
                return Optional.empty();
            }
            added.add(joined.get());
        }
        remember(at, proxy.proxyMembers().orElseThrow());
        return held;
    }

    private List<CompoundTag> records() {
        return colony.kept(OWNER).getList("proxies", Tag.TAG_COMPOUND).stream()
            .map(tag -> ((CompoundTag) tag).copy()).toList();
    }

    private static Optional<WorldPos> address(CompoundTag record) {
        return WorldPos.load(Reader.of(record.getCompound("at")));
    }

    private static List<WorldPos> members(CompoundTag record) {
        return record.getList("members", Tag.TAG_COMPOUND).stream()
            .map(tag -> WorldPos.load(Reader.of((CompoundTag) tag))).flatMap(Optional::stream).toList();
    }

    private void remember(WorldPos at, List<WorldPos> members) {
        if (records().stream().anyMatch(record -> address(record).filter(at::equals).isPresent()
            && new LinkedHashSet<>(members(record)).equals(new LinkedHashSet<>(members)))) {
            return;
        }
        ListTag records = new ListTag();
        records().stream().filter(record -> address(record).filter(at::equals).isEmpty()).forEach(records::add);
        CompoundTag record = new CompoundTag();
        record.put("at", at.save());
        ListTag positions = new ListTag();
        members.stream().distinct().forEach(member -> positions.add(member.save()));
        record.put("members", positions);
        records.add(record);
        CompoundTag bag = new CompoundTag();
        bag.put("proxies", records);
        colony.keep(OWNER, bag);
    }

    private void forget(Set<WorldPos> gone) {
        CompoundTag bag = colony.kept(OWNER);
        ListTag records = bag.getList("proxies", Tag.TAG_COMPOUND);
        records.removeIf(tag -> address((CompoundTag) tag).filter(gone::contains).isPresent());
        bag.put("proxies", records);
        colony.keep(OWNER, bag);
    }

    public void released(MinecraftServer server, Claim claim, ClaimRelease why) {
        if (releasing || !(claim.what() instanceof Claimed.Block block)) {
            return;
        }
        Set<WorldPos> gone = new LinkedHashSet<>();
        gone.add(block.cell());
        for (CompoundTag record : records()) {
            WorldPos at = address(record).orElseThrow();
            if (at.equals(block.cell())) {
                gone.addAll(members(record));
            } else if (members(record).contains(block.cell())) {
                gone.add(at);
            }
        }
        for (CompoundTag record : records()) {
            if (members(record).stream().anyMatch(gone::contains)) {
                gone.add(address(record).orElseThrow());
            }
        }
        releasing = true;
        try {
            for (WorldPos at : gone) {
                colony.claimAt(at).filter(other -> other.owner().equals(claim.owner()))
                    .ifPresent(other -> colony.release(other.id(), why));
            }
            forget(gone);
        } finally {
            releasing = false;
        }
    }

    /** Existing proxy claims also acquire membership on first load. Refused additions stay independent. */
    public void refresh(MinecraftServer server) {
        for (Claim claim : List.copyOf(colony.claims())) {
            if (!(claim.what() instanceof Claimed.Block block)) {
                continue;
            }
            ItemPort proxy = port(server, block.cell()).orElse(null);
            if (proxy == null || proxy.proxyMembers().isEmpty() || !StoreAccess.usability(block.cell().level(server).orElseThrow(), proxy).usable()) {
                continue;
            }
            Set<WorldPos> represented = new LinkedHashSet<>();
            records().stream().filter(record -> address(record).filter(block.cell()::equals).isPresent())
                .forEach(record -> represented.addAll(members(record)));
            for (WorldPos member : proxy.proxyMembers().orElseThrow()) {
                ItemPort store = port(server, member).orElse(null);
                if (!member.sameRealm(block.cell()) || store == null || !StoreAccess.usability(member.level(server).orElseThrow(), store).usable()
                    || store.proxyMembers().isPresent()) {
                    continue;
                }
                if (colony.claimAt(member).filter(other -> other.owner().equals(claim.owner())).isPresent()
                    || colony.hold(claim.owner(), new Claimed.Block(member)).isPresent()) {
                    represented.add(member);
                }
            }
            remember(block.cell(), List.copyOf(represented));
        }
    }
}
