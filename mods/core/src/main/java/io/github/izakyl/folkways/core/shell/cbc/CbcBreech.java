package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlock;
import rbasamoyai.createbigcannons.munitions.config.BigCannonPropellantCompatibilityHandler;

/**
 * The quick-firing breech as a player's hands use it: open it, push a munition into the first chamber, and ram the
 * column one cell deeper as a Ram Rod does when the next cell is taken. One munition per deposit, projectile first,
 * then charges, refused beyond what CBC fires safely.
 */
record CbcBreech(WorldPos anchor, CannonMountBlockEntity mount) implements Port<ItemStack> {

    private static final Map<CannonMountBlockEntity, Long> RAMMED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<CannonMountBlockEntity, String> REFUSALS =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public String part() {
        return "breech";
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
        return CbcIntegration.attached(level, anchor, mount)
            && CbcCannon.mounted(mount).map(CbcCannon::quickBreech).isPresent();
    }

    /** Why the last deposit through this mount's loading port was turned down, until one succeeds. */
    static String refusal(CannonMountBlockEntity mount) {
        return REFUSALS.getOrDefault(mount, "");
    }

    static void refuse(CannonMountBlockEntity mount, String reason) {
        if (reason.isEmpty()) {
            REFUSALS.remove(mount);
        } else {
            REFUSALS.put(mount, reason);
        }
    }

    @Override
    public void prepareDeposit(ServerLevel level, Mob writer, ItemStack offered) {
        if (offered.isEmpty() || !(Block.byItem(offered.getItem()) instanceof BigCannonMunitionBlock)
                || !CbcIntegration.attached(level, anchor, mount)) {
            return;
        }
        CbcCannon.mounted(mount).ifPresent(mounted -> open(level, mounted, mount));
    }

    // Opening ejects whatever sits in the first chamber, as the native lever does. Only a spent cartridge may go.
    private static void open(ServerLevel level, CbcCannon mounted, CannonMountBlockEntity mount) {
        var breech = mounted.quickBreech();
        if (breech == null || breech.onInteractionCooldown() || breech.isOpen() || breech.getOpenDirection() < 0) {
            return;
        }
        var start = mounted.cannon().getStartPos();
        var chamber = mounted.chamber(start);
        var held = chamber == null ? BigCannonBehavior.EMPTY : chamber.block();
        boolean eject = !held.state().isAir();
        if (eject && !spent(held)) {
            refuse(mount, "the quick-firing breech is closed over a live load; opening it would eject the chamber");
            return;
        }
        breech.toggleOpening();
        Set<BlockPos> changed = new HashSet<>();
        changed.add(start.relative(mounted.cannon().initialOrientation().getOpposite()));
        if (eject && held.state().getBlock() instanceof BigCannonMunitionBlock munition) {
            var item = munition.getExtractedItem(held, level.registryAccess());
            chamber.removeBlock();
            changed.add(start);
            var normal = new Vec3(mounted.cannon().initialOrientation().getOpposite().step());
            var local = Vec3.atCenterOf(start.relative(mounted.cannon().initialOrientation().getOpposite()))
                .add(normal.scale(1.1));
            var at = mounted.entity().toGlobalVector(local, 0);
            var velocity = mounted.entity().applyRotation(normal, 0).scale(0.075);
            var drop = new ItemEntity(level, at.x, at.y, at.z, item, velocity.x, velocity.y, velocity.z);
            drop.setPickUpDelay(CBCConfigs.server().munitions.quickFiringBreechItemPickupDelay.get());
            level.addFreshEntity(drop);
        }
        BigCannonBlock.writeAndSyncMultipleBlockData(changed, mounted.entity(), mounted.cannon());
    }

    private static boolean spent(StructureBlockInfo info) {
        return info.state().getBlock() instanceof BigCartridgeBlock && BigCartridgeBlock.getPowerFromData(info) <= 0;
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (offered.isEmpty() || !CbcIntegration.attached(level, anchor, mount)
                || !(Block.byItem(offered.getItem()) instanceof BigCannonMunitionBlock munition)) {
            return ItemStack.EMPTY;
        }
        var mounted = CbcCannon.mounted(mount).orElse(null);
        if (mounted == null) {
            return ItemStack.EMPTY;
        }
        var breech = mounted.quickBreech();
        if (breech == null || breech.onInteractionCooldown()) {
            return ItemStack.EMPTY;
        }
        if (!breech.isOpen()) {
            if (!simulate) {
                open(level, mounted, mount);
            }
            return ItemStack.EMPTY;
        }
        var dir = mounted.cannon().initialOrientation();
        var tube = mounted.tube();
        if (tube.isEmpty()) {
            refuse(mount, "the cannon has no chamber behind the breech");
            return ItemStack.EMPTY;
        }
        // A spent cartridge left behind an open breech goes as a hand would clear it: shut the breech, then open it.
        if (!mounted.bore().isEmpty() && spent(mounted.bore().getFirst())) {
            if (!simulate && breech.getOpenDirection() >= 0) {
                breech.toggleOpening();
                syncBreech(mounted);
            }
            return ItemStack.EMPTY;
        }
        var entry = munition.getHandloadingInfo(offered, tube.getFirst(), dir, level.registryAccess());
        var column = mounted.bore();
        for (int i = column.size(); i < tube.size(); i++) {
            if (!mounted.chamber(tube.get(i)).block().state().isAir()) {
                refuse(mount, "the bore holds a load or obstruction past the first gap; clear it first");
                return ItemStack.EMPTY;
            }
        }
        List<StructureBlockInfo> after = new ArrayList<>();
        after.add(entry);
        after.addAll(column);
        var problem = order(column, entry).or(() -> fits(mounted, after)).or(() -> safe(mounted, after));
        if (problem.isPresent()) {
            refuse(mount, problem.get());
            return ItemStack.EMPTY;
        }
        // The breech itself advances one munition. A longer column needs the Ram Rod and its cooldown.
        boolean ram = column.size() >= 2;
        long now = level.getGameTime();
        if (ram) {
            Long last = RAMMED.get(mount);
            if (last != null && now - last < CBCConfigs.server().cannons.loadingToolCooldown.get()) {
                return ItemStack.EMPTY;
            }
        }
        if (!simulate) {
            Set<BlockPos> changed = new HashSet<>();
            for (int i = column.size() - 1; i >= 0; i--) {
                mounted.chamber(tube.get(i + 1)).loadBlock(column.get(i));
                changed.add(tube.get(i + 1));
            }
            mounted.chamber(tube.getFirst()).loadBlock(entry);
            changed.add(tube.getFirst());
            BigCannonBlock.writeAndSyncMultipleBlockData(changed, mounted.entity(), mounted.cannon());
            if (ram) {
                RAMMED.put(mount, now);
            }
            refuse(mount, "");
        }
        return offered.copyWithCount(1);
    }

    private static Optional<String> order(List<StructureBlockInfo> column, StructureBlockInfo entry) {
        boolean projectile = entry.state().getBlock() instanceof ProjectileBlock<?>;
        if (column.isEmpty()) {
            if (!(entry.state().getBlock() instanceof ProjectileBlock<?> shell) || shell.getExpectedSize() != 1) {
                return Optional.of("load a single-block projectile first; CBC fires the propellant behind it");
            }
            return Optional.empty();
        }
        if (projectile || !CbcCannon.propellant(entry)) {
            return Optional.of("the bore already holds a projectile; only propellant goes behind it");
        }
        if (!(column.getLast().state().getBlock() instanceof ProjectileBlock<?>)
                || column.subList(0, column.size() - 1).stream().anyMatch(info -> !CbcCannon.propellant(info))) {
            return Optional.of("the bore holds a load this port did not place; clear it first");
        }
        return Optional.empty();
    }

    // Each munition must fit its cell, and the ram must move the column behind the new one.
    private static Optional<String> fits(CbcCannon mounted, List<StructureBlockInfo> after) {
        var tube = mounted.tube();
        if (after.size() > tube.size()) {
            return Optional.of("the tube has " + tube.size() + " cells; this load needs " + after.size());
        }
        int strength = CBCConfigs.server().cannons.ramRodStrength.get();
        if (after.size() - 1 >= 2 && after.size() - 1 > strength) {
            return Optional.of("a ram rod pushes at most " + strength + " munitions (ramRodStrength), so a hand load"
                + " stops at " + (strength + 1) + " blocks");
        }
        for (int i = 0; i < after.size(); i++) {
            if (!(mounted.cannon().presentBlockEntities.get(tube.get(i)) instanceof IBigCannonBlockEntity cell)
                    || !cell.canPushBlock(after.get(i))) {
                return Optional.of("cell " + (i + 1) + " of the tube cannot hold "
                    + BuiltInRegistries.BLOCK.getKey(after.get(i).state().getBlock()));
            }
        }
        return Optional.empty();
    }

    /** CBC's own limits for the propellant ahead of a projectile: ignition, combination, tube thickness and stress. */
    static Optional<String> safe(CbcCannon mounted, List<StructureBlockInfo> after) {
        var dir = mounted.cannon().initialOrientation();
        var tube = mounted.tube();
        List<StructureBlockInfo> propellant = new ArrayList<>();
        for (var info : after) {
            if (!CbcCannon.propellant(info)) {
                break;
            }
            propellant.add(info);
        }
        if (propellant.isEmpty()) {
            return Optional.empty();
        }
        var first = propellant.getFirst();
        if (!((BigCannonPropellantBlock) first.state().getBlock()).canBeIgnited(first, dir)) {
            return Optional.of("the charge nearest the breech cannot be ignited (damp, empty or facing the wrong way)");
        }
        if (!safeLoad(propellant, dir)) {
            return Optional.of("CBC does not fire this propellant combination; a big cartridge must sit nearest the"
                + " breech and alone among cartridges");
        }
        float stress = 0;
        for (int i = 0; i < propellant.size(); i++) {
            var info = propellant.get(i);
            stress += ((BigCannonPropellantBlock) info.state().getBlock()).getStressOnCannon(info);
            var cell = mounted.cannon().getBlocks().get(tube.get(i));
            if (!(mounted.cannon().presentBlockEntities.get(tube.get(i)) instanceof IBigCannonBlockEntity barrel)
                    || cell == null || !barrel.blockCanHandle(cell)) {
                return Optional.of("propellant would rest in cell " + (i + 1) + " ("
                    + (cell == null ? "none" : BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()))
                    + "), which is not a chamber; CBC can burst the barrel there");
            }
        }
        int limit = mounted.cannon().getMaxSafeCharges();
        if (stress > limit) {
            return Optional.of("propellant stress " + format(stress) + " exceeds the cannon's safe limit of " + limit
                + " (getMaxSafeCharges: material and breech strength); CBC can burst the cannon");
        }
        return Optional.empty();
    }

    // MountedBigCannonContraption.PropellantContext.safeLoad, which is not visible outside CBC.
    private static boolean safeLoad(List<StructureBlockInfo> propellant, Direction orientation) {
        Map<Block, Integer> allowedCounts = new HashMap<>();
        Set<Block> foundBlocks = new HashSet<>();
        for (int index = 0; index < propellant.size(); index++) {
            var info = propellant.get(index);
            Block block = info.state().getBlock();
            if (!(block instanceof BigCannonPropellantBlock charge) || !charge.isValidAddition(info, index, orientation)) {
                return false;
            }
            var compatibilities = BigCannonPropellantCompatibilityHandler.getCompatibilities(block);
            boolean firstAndPreviouslyUnrestricted = false;
            if (foundBlocks.add(block)) {
                for (var entry : compatibilities.validPropellantCounts().entrySet()) {
                    Block other = entry.getKey();
                    int oldCount = allowedCounts.getOrDefault(other, -1);
                    int newCount = entry.getValue();
                    if (newCount >= 0 && (oldCount < 0 || newCount < oldCount)) {
                        firstAndPreviouslyUnrestricted = block == other && !allowedCounts.containsKey(block);
                        allowedCounts.put(other, newCount);
                    }
                }
            }
            if (allowedCounts.containsKey(block) && !firstAndPreviouslyUnrestricted) {
                int allowed = allowedCounts.get(block);
                if (allowed <= 0) {
                    return false;
                }
                allowedCounts.put(block, allowed - 1);
            }
        }
        return true;
    }

    private static String format(float value) {
        return value == (int) value ? Integer.toString((int) value) : Float.toString(value);
    }

    static void syncBreech(CbcCannon mounted) {
        var pos = mounted.cannon().getStartPos().relative(mounted.cannon().initialOrientation().getOpposite());
        BigCannonBlock.writeAndSyncMultipleBlockData(Set.of(pos), mounted.entity(), mounted.cannon());
    }

    /** The bore in loading order: the projectile first, then each charge behind it. */
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
                contents.addFirst(munition.getExtractedItem(entry, level.registryAccess()));
            }
        }
        return contents;
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        return ItemStack.EMPTY;
    }
}
