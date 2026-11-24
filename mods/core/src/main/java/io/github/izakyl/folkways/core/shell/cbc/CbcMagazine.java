package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;

/**
 * A mounted autocannon breech's ammo container slot, as a player's hand uses it: put a container in when the slot
 * is empty, take the one in it out at any time (a full one too, as a sneaking player can).
 */
record CbcMagazine(WorldPos anchor, CannonMountBlockEntity mount) implements Port<ItemStack> {

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
    public boolean valid(ServerLevel level) {
        return CbcIntegration.attached(level, anchor, mount) && CbcCannon.autocannon(mount).isPresent();
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (offered.isEmpty() || !(offered.getItem() instanceof AutocannonAmmoContainerItem)
                || !CbcIntegration.attached(level, anchor, mount)) {
            return ItemStack.EMPTY;
        }
        var gun = CbcCannon.autocannon(mount).orElse(null);
        if (gun == null) {
            return ItemStack.EMPTY;
        }
        if (!gun.breech().getMagazine().isEmpty()) {
            CbcBreech.refuse(mount, "the breech already holds an ammo container; take it out first");
            return ItemStack.EMPTY;
        }
        var container = offered.copyWithCount(1);
        if (!simulate) {
            gun.breech().setMagazine(container.copy());
            sync(gun);
            CbcBreech.refuse(mount, "");
        }
        return container;
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        if (!CbcIntegration.attached(level, anchor, mount)) {
            return ItemStack.EMPTY;
        }
        var gun = CbcCannon.autocannon(mount).orElse(null);
        if (gun == null || gun.breech().getMagazine().isEmpty()
                || !wanted.isEmpty() && !ItemStack.isSameItem(gun.breech().getMagazine(), wanted)) {
            return ItemStack.EMPTY;
        }
        var container = gun.breech().getMagazine().copy();
        if (!simulate) {
            gun.breech().setMagazine(ItemStack.EMPTY);
            sync(gun);
        }
        return container;
    }

    @Override
    public List<ItemStack> contents(ServerLevel level) {
        return CbcCannon.autocannon(mount).map(gun -> gun.breech().getMagazine())
            .filter(stack -> !stack.isEmpty()).map(stack -> List.of(stack.copy())).orElseGet(List::of);
    }

    static int rounds(CbcCannon.Autocannon gun) {
        int queued = gun.breech().getInputBuffer().stream().mapToInt(ItemStack::getCount).sum();
        return queued + AutocannonAmmoContainerItem.getTotalAmmoCount(gun.breech().getMagazine());
    }

    private static void sync(CbcCannon.Autocannon gun) {
        var start = gun.cannon().getStartPos();
        BigCannonBlock.writeAndSyncSingleBlockData(gun.breech(), gun.cannon().getBlocks().get(start), gun.entity(),
            gun.cannon());
    }
}
