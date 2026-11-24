package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.shell.cbc.mixin.CannonMountAccess;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

public final class CbcIntegration {

    private CbcIntegration() {
    }

    public static void install() {
        CbcMechanisms.install();
        Controls.register(id("mount"), Controls.SPECIFIC, (level, pos) -> mount(level, pos)
            .map(mount -> switch (CbcCannon.kind(mount)) {
                case AUTOCANNON -> List.<Signal>of(new CbcAim(pos, mount), new CbcFireRate(pos, mount));
                case DROP_MORTAR -> List.<Signal>of(new CbcAim(pos, mount));
                default -> List.<Signal>of(new CbcAim(pos, mount), new CbcTrigger(pos, mount), new CbcRam(pos, mount));
            })
            .orElseGet(List::of));
        Ports.register(ResourceKind.ITEM, id("mount"), Ports.SPECIFIC, (level, pos) -> mount(level, pos)
            .map(mount -> switch (CbcCannon.kind(mount)) {
                case AUTOCANNON -> List.<Port<ItemStack>>of(new CbcMagazine(pos, mount));
                case DROP_MORTAR -> List.<Port<ItemStack>>of(new CbcMortarRound(pos, mount));
                default -> List.<Port<ItemStack>>of(new CbcBreech(pos, mount), new CbcMuzzle(pos, mount));
            }).orElseGet(List::of));
        Readings.register(id("aim"), (level, pos) -> mount(level, pos).map(mount -> {
            CannonMountAccess angles = (CannonMountAccess) mount;
            return Value.of(angles.folkwaysYaw(), angles.folkwaysPitch());
        }));
        Readings.register(id("assembled"), (level, pos) -> mount(level, pos)
            .map(mount -> Value.of(mount.isRunning() && mount.getContraption() != null
                && mount.getContraption().isAlive())));
        Readings.register(id("loaded"), (level, pos) -> mount(level, pos).map(mount -> Value.of(loaded(mount))));
        Readings.register(id("charges"), (level, pos) -> mount(level, pos)
            .map(mount -> Value.of(CbcCannon.mounted(mount).map(CbcCannon::charges).orElse(0))));
        Readings.register(id("projectile"), (level, pos) -> mount(level, pos)
            .map(mount -> new Value.Text(CbcCannon.mounted(mount).map(CbcCannon::projectile).orElse(""))));
        Readings.register(id("stress"), (level, pos) -> mount(level, pos)
            .map(mount -> CbcCannon.mounted(mount).map(cannon -> Value.of(cannon.stress(),
                cannon.cannon().getMaxSafeCharges())).orElse(Value.of(0, 0))));
        Readings.register(id("refusal"), (level, pos) -> mount(level, pos)
            .map(mount -> new Value.Text(CbcBreech.refusal(mount))));
        Readings.register(id("ammo"), (level, pos) -> mount(level, pos)
            .map(mount -> Value.of(CbcCannon.autocannon(mount).map(CbcMagazine::rounds).orElse(0))));
        Readings.register(id("fire_rate"), (level, pos) -> mount(level, pos)
            .map(mount -> Value.of(CbcCannon.autocannon(mount).map(gun -> gun.breech().getFireRate()).orElse(0))));
    }

    /** A big cannon with an ignitable charge behind a projectile, an autocannon with rounds, or a falling round. */
    static boolean loaded(CannonMountBlockEntity mount) {
        return switch (CbcCannon.kind(mount)) {
            case BIG_CANNON -> CbcCannon.mounted(mount).filter(CbcCannon::loaded).isPresent();
            case AUTOCANNON -> CbcCannon.autocannon(mount).map(CbcMagazine::rounds).orElse(0) > 0;
            case DROP_MORTAR -> CbcCannon.dropMortar(mount).map(CbcMortarRound::queued)
                .filter(stack -> !stack.isEmpty()).isPresent();
            case NONE -> false;
        };
    }

    static ResourceLocation id(String part) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", "cbc/" + part);
    }

    static boolean attached(ServerLevel level, WorldPos pos, CannonMountBlockEntity mount) {
        return !mount.isRemoved() && mount(level, pos).orElse(null) == mount;
    }

    private static Optional<CannonMountBlockEntity> mount(ServerLevel level, WorldPos pos) {
        if (pos.in(level) && level.isLoaded(pos.block(level))
            && level.getBlockEntity(pos.block(level)) instanceof CannonMountBlockEntity mount) {
            return Optional.of(mount);
        }
        return Optional.empty();
    }
}
