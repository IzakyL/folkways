package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A mortar's tube as an item port: a shell put in is fired by the mortar's own working, as a player's would be, once
 * its native countdown runs out. It takes one shell at a time, only while the mortar is idle.
 */
public record WarfareBreech(MortarEntity mortar) implements Port<ItemStack> {

    public static final String BREECH = "breech";

    static List<Port<ItemStack>> at(ServerLevel level, WorldPos cell) {
        return WarfareMount.standing(level, cell).stream().filter(MortarEntity.class::isInstance)
            .<Port<ItemStack>>map(vehicle -> new WarfareBreech((MortarEntity) vehicle)).toList();
    }

    /** Whether the mortar is idle: no countdown running and nothing in the tube. */
    public boolean idle() {
        return mortar.getEntityData().get(MortarEntity.FIRE_TIME) == 0 && mortar.getItems().getFirst().isEmpty();
    }

    @Override
    public ResourceKind<ItemStack> resource() {
        return ResourceKind.ITEM;
    }

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(mortar.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(mortar);
    }

    @Override
    public String part() {
        return BREECH + "/" + mortar.getUUID();
    }

    @Override
    public Object leaseKey() {
        return mortar.getUUID();
    }

    @Override
    public boolean valid(ServerLevel level) {
        return WarfareMount.valid(level, mortar, this);
    }

    @Override
    public Optional<Stances> stance(ServerLevel level) {
        return WarfareMount.stance(level, mortar);
    }

    /** Puts one shell in and starts the native launch, by the writer, who becomes the shell's shooter. */
    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (offered.isEmpty() || !(offered.getItem() instanceof MortarShellItem) || !valid(level) || !idle()
            || !mortar.canPlaceItem(0, offered)) {
            return ItemStack.EMPTY;
        }
        ItemStack shell = offered.copyWithCount(1);
        if (!simulate) {
            // As the native hand-loading does: the shell goes in without the container's change hook, which would
            // fire it with no shooter, then the launch is started by the one who loaded it.
            mortar.getItems().set(0, shell.copy());
            mortar.vehicleShoot(writer, "Main", (Vec3) null);
        }
        return shell;
    }

    /** Takes back a shell waiting in the tube of a mortar that is not counting down. */
    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        ItemStack held = mortar.getItems().getFirst();
        if (wanted.isEmpty() || !valid(level) || mortar.getEntityData().get(MortarEntity.FIRE_TIME) != 0
            || !ItemStack.isSameItemSameComponents(held, wanted)) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            mortar.getItems().set(0, ItemStack.EMPTY);
        }
        return held.copyWithCount(1);
    }
}
