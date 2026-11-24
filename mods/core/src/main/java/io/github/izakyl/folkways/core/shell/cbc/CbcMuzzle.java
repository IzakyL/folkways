package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.cannon_end.BigCannonEnd;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.equipment.manual_loading.RamRodItem;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;

/**
 * The muzzle of a mounted big cannon as a player's hands use it ({@code BigCannonBlock.onInteractWhileAssembled}): a
 * munition pushed into the open muzzle cell, facing along the bore, when that cell is empty. The {@link CbcRam Ram Rod}
 * then drives it down. One munition per deposit, propellant first and the projectile last, refused when it could not
 * be rammed down to the load or the seated load would be unsafe.
 */
record CbcMuzzle(WorldPos anchor, CannonMountBlockEntity mount) implements Port<ItemStack> {

    private static final Map<CannonMountBlockEntity, Long> RAMMED = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public String part() {
        return "muzzle";
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
        return CbcIntegration.attached(level, anchor, mount) && CbcCannon.mounted(mount).filter(CbcMuzzle::open)
            .isPresent();
    }

    /** The muzzle cell: the last tube cell, open and unconnected along the bore, as the native click requires. */
    static boolean open(CbcCannon mounted) {
        var tube = mounted.tube();
        if (tube.isEmpty()) {
            return false;
        }
        var pos = tube.getLast();
        var dir = mounted.cannon().initialOrientation();
        var info = mounted.cannon().getBlocks().get(pos);
        return info != null && info.state().getBlock() instanceof BigCannonBlock cannon
            && cannon.getFacing(info.state()).getAxis() == dir.getAxis()
            && cannon.getOpeningType(mounted.cannon(), info.state(), pos) == BigCannonEnd.OPEN
            && mounted.cannon().presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity cell
            && !cell.cannonBehavior().isConnectedTo(dir);
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (offered.isEmpty() || !CbcIntegration.attached(level, anchor, mount)
                || !(Block.byItem(offered.getItem()) instanceof BigCannonMunitionBlock munition)) {
            return ItemStack.EMPTY;
        }
        var mounted = CbcCannon.mounted(mount).filter(CbcMuzzle::open).orElse(null);
        if (mounted == null) {
            CbcBreech.refuse(mount, "the cannon has no open muzzle");
            return ItemStack.EMPTY;
        }
        var tube = mounted.tube();
        var muzzle = tube.getLast();
        var dir = mounted.cannon().initialOrientation();
        var cell = mounted.chamber(muzzle);
        if (!cell.block().state().isAir()) {
            CbcBreech.refuse(mount, "a munition sits in the muzzle; ram it down first");
            return ItemStack.EMPTY;
        }
        var entry = munition.getHandloadingInfo(offered, muzzle, dir, level.registryAccess());
        if (!(mounted.cannon().presentBlockEntities.get(muzzle) instanceof IBigCannonBlockEntity barrel)
                || !barrel.canLoadBlock(entry)) {
            CbcBreech.refuse(mount, "the muzzle cannot take " + BuiltInRegistries.ITEM.getKey(offered.getItem()));
            return ItemStack.EMPTY;
        }
        var problem = seated(mounted, entry);
        if (problem.isPresent()) {
            CbcBreech.refuse(mount, problem.get());
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            cell.loadBlock(entry);
            BigCannonBlock.writeAndSyncMultipleBlockData(Set.of(muzzle), mounted.entity(), mounted.cannon());
            CbcBreech.refuse(mount, "");
        }
        return offered.copyWithCount(1);
    }

    // The load once this munition is rammed down onto it, judged by CBC's limits.
    private static Optional<String> seated(CbcCannon mounted, StructureBlockInfo entry) {
        var tube = mounted.tube();
        List<StructureBlockInfo> after = new ArrayList<>();
        boolean gap = false;
        for (var pos : tube) {
            var info = mounted.chamber(pos).block();
            if (info.state().isAir()) {
                gap = true;
            } else if (gap) {
                return Optional.of("a munition rests partway down the bore; ram it down first");
            } else {
                after.add(info);
            }
        }
        if (!after.isEmpty() && CbcBallistics.spent(after.getFirst())) {
            return Optional.of("a spent cartridge fills the chamber; open the breech to eject it");
        }
        if (after.stream().anyMatch(info -> info.state().getBlock() instanceof ProjectileBlock<?>)) {
            return Optional.of("the bore already holds its projectile");
        }
        boolean projectile = entry.state().getBlock() instanceof ProjectileBlock<?>;
        if (projectile && after.isEmpty()) {
            return Optional.of("push propellant in first; through the muzzle the projectile goes in last");
        }
        if (projectile && ((ProjectileBlock<?>) entry.state().getBlock()).getExpectedSize() != 1) {
            return Optional.of("load a single-block projectile");
        }
        if (!projectile && !CbcCannon.propellant(entry)) {
            return Optional.of("only propellant and a projectile go into the bore");
        }
        after.add(entry);
        int seat = after.size() - 1;
        if (seat >= tube.size()) {
            return Optional.of("the tube has " + tube.size() + " cells; this load needs " + after.size());
        }
        int reach = CBCConfigs.server().cannons.ramRodReach.get();
        if (tube.size() - 1 - seat > reach) {
            return Optional.of("a Ram Rod reaches " + reach + " cells into the bore (ramRodReach), so this munition"
                + " would stop " + (tube.size() - 1 - seat - reach) + " cells short of the load");
        }
        for (int i = 0; i < after.size(); i++) {
            if (!(mounted.cannon().presentBlockEntities.get(tube.get(i)) instanceof IBigCannonBlockEntity barrel)
                    || !barrel.canPushBlock(after.get(i))) {
                return Optional.of("cell " + (i + 1) + " of the tube cannot hold "
                    + BuiltInRegistries.BLOCK.getKey(after.get(i).state().getBlock()));
            }
        }
        return CbcBreech.safe(mounted, after);
    }

    /**
     * One stroke of the Ram Rod into the muzzle, as {@code RamRodItem.onUseOnCannon}: find the first munition within
     * ramRodReach of the muzzle, then push the column behind it one cell deeper, at most ramRodStrength munitions and
     * only into open tube. Why nothing moved, when it did not.
     */
    static Optional<String> ram(ServerLevel level, CannonMountBlockEntity mount, CbcCannon mounted) {
        long now = level.getGameTime();
        Long last = RAMMED.get(mount);
        if (last != null && now - last < CBCConfigs.server().cannons.loadingToolCooldown.get()) {
            return Optional.of("");
        }
        var contraption = mounted.cannon();
        var push = contraption.initialOrientation().getOpposite();
        var start = mounted.tube().getLast();
        int reach = CBCConfigs.server().cannons.ramRodReach.get();
        int k = -1;
        for (int i = 0; i < reach; i++) {
            BlockPos pos = start.relative(push, i);
            var info = contraption.getBlocks().get(pos);
            if (info == null || !RamRodItem.isValidLoadBlock(info.state(), contraption, pos, push)) {
                return Optional.of("no munition within the Ram Rod's reach of " + reach + " cells");
            }
            if (contraption.presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity cell
                    && cell.cannonBehavior().block().state().isAir()) {
                continue;
            }
            k = i;
            break;
        }
        if (k == -1) {
            return Optional.of("no munition within the Ram Rod's reach of " + reach + " cells");
        }
        int strength = CBCConfigs.server().cannons.ramRodStrength.get();
        List<StructureBlockInfo> column = new ArrayList<>();
        for (int i = 0; i < strength + 1; i++) {
            BlockPos pos = start.relative(push, i + k);
            var info = contraption.getBlocks().get(pos);
            if (info == null || !RamRodItem.isValidLoadBlock(info.state(), contraption, pos, push)) {
                return Optional.of("the load is rammed home");
            }
            if (contraption.presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity cell
                    && !cell.cannonBehavior().block().state().isAir()) {
                column.add(cell.cannonBehavior().block());
                if (column.size() > strength) {
                    return Optional.of("a Ram Rod pushes at most " + strength + " munitions (ramRodStrength)");
                }
                continue;
            }
            break;
        }
        if (column.isEmpty()) {
            return Optional.of("no munition within the Ram Rod's reach of " + reach + " cells");
        }
        Set<BlockPos> changes = new HashSet<>();
        for (int i = column.size() - 1; i >= 0; i--) {
            BlockPos from = start.relative(push, i + k);
            BlockPos to = from.relative(push);
            if (!(contraption.presentBlockEntities.get(from) instanceof IBigCannonBlockEntity source)
                    || !(contraption.presentBlockEntities.get(to) instanceof IBigCannonBlockEntity target)) {
                break;
            }
            source.cannonBehavior().removeBlock();
            target.cannonBehavior().tryLoadingBlock(column.get(i));
            changes.add(to);
            if (i == 0) {
                changes.add(from);
            }
        }
        BigCannonBlock.writeAndSyncMultipleBlockData(changes, mounted.entity(), contraption);
        RAMMED.put(mount, now);
        return Optional.empty();
    }

    /** The bore from the first chamber toward the muzzle: the order munitions go in through the muzzle. */
    @Override
    public List<ItemStack> contents(ServerLevel level) {
        var mounted = CbcCannon.mounted(mount).orElse(null);
        if (mounted == null) {
            return List.of();
        }
        var contents = new ArrayList<ItemStack>();
        for (var pos : mounted.tube()) {
            var entry = mounted.chamber(pos).block();
            if (entry.state().getBlock() instanceof BigCannonMunitionBlock munition) {
                contents.add(munition.getExtractedItem(entry, level.registryAccess()));
            }
        }
        return contents;
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        return ItemStack.EMPTY;
    }
}
