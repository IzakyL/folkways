package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.shell.cbc.mixin.DropMortarAccess;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.DropMortarMunition;

/**
 * A mounted drop mortar's muzzle, as a player's hand uses it: drop one round down an empty barrel with no round
 * already falling. The mortar launches it itself after {@code dropMortarDelay}; the dropper waits
 * {@code dropMortarItemCooldown} before dropping the same kind of round again.
 */
record CbcMortarRound(WorldPos anchor, CannonMountBlockEntity mount) implements Port<ItemStack> {

    private static final Map<Object, Map<Item, Long>> DROPPED = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public String part() {
        return "ammo";
    }

    @Override
    public Object leaseKey() {
        return anchor;
    }

    @Override
    public ResourceKind<ItemStack> resource() {
        return ResourceKind.ITEM;
    }

    @Override
    public Set<Operation> operations() {
        return Set.of(Operation.PUT);
    }

    @Override
    public boolean valid(ServerLevel level) {
        return CbcIntegration.attached(level, anchor, mount) && CbcCannon.dropMortar(mount).isPresent();
    }

    static ItemStack queued(CbcCannon mortar) {
        var round = ((DropMortarAccess) mortar.cannon()).folkwaysMortarRound();
        return round == null ? ItemStack.EMPTY : round;
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (offered.isEmpty() || !CbcIntegration.attached(level, anchor, mount)
                || !(Block.byItem(offered.getItem()) instanceof BigCannonMunitionBlock)
                || !(Block.byItem(offered.getItem()) instanceof DropMortarMunition<?>)) {
            return ItemStack.EMPTY;
        }
        var mortar = CbcCannon.dropMortar(mount).orElse(null);
        if (mortar == null) {
            return ItemStack.EMPTY;
        }
        if (!queued(mortar).isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!mortar.bore().isEmpty() || mortar.tube().stream()
                .anyMatch(pos -> !mortar.chamber(pos).block().state().isAir())) {
            CbcBreech.refuse(mount, "the mortar barrel must be empty before a round is dropped");
            return ItemStack.EMPTY;
        }
        Object dropper = writer == null ? mount : writer;
        long now = level.getGameTime();
        Long last = DROPPED.getOrDefault(dropper, Map.of()).get(offered.getItem());
        if (last != null && now - last < CBCConfigs.server().cannons.dropMortarItemCooldown.get()) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            if (!mortar.cannon().tryDroppingMortarRound(offered.copyWithCount(1))) {
                return ItemStack.EMPTY;
            }
            DROPPED.computeIfAbsent(dropper, ignored -> new HashMap<>()).put(offered.getItem(), now);
            CbcBreech.refuse(mount, "");
        }
        return offered.copyWithCount(1);
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public List<ItemStack> contents(ServerLevel level) {
        return CbcCannon.dropMortar(mount).map(CbcMortarRound::queued).filter(stack -> !stack.isEmpty())
            .map(stack -> List.of(stack.copy())).orElseGet(List::of);
    }
}
