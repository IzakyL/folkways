package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.shell.cbc.mixin.ProjectilePropertiesAccess;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.quickfiring_breech.QuickfiringBreechBlockEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.index.CBCDataComponents;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ap_shell.APShellProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.he_shell.HEShellProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlockItem;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;

/**
 * A mounted big cannon's {@code ballistics} reading: where it pivots and how its mount turns, the bore and its limits by
 * CBC's own rules, the flight of what it holds, and the native figures of every big cannon munition, so a script can
 * work out the flight of a load it has not loaded yet.
 */
final class CbcBallistics {

    private static final long MUNITION_REFRESH = 200;
    private static Map<String, Value> munitions = Map.of();
    private static long munitionsAt = Long.MIN_VALUE;
    private static Level munitionsLevel;

    private CbcBallistics() {
    }

    static Optional<Value> read(CbcAim aim) {
        var frame = aim.anchor().realm() instanceof Realm.Frame realm
            ? WorldSpaces.frame(aim.mount().getLevel(), realm.structure()) : Optional.<WorldSpaces.Frame>empty();
        return CbcCannon.mounted(aim.mount()).map(cannon -> {
            var entity = cannon.entity();
            var level = entity.level();
            var direction = cannon.cannon().initialOrientation();
            var storedPivot = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 1);
            var storedForward = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO.relative(direction)), 1);
            var pivot = frame.map(space -> world(space, storedPivot)).orElse(storedPivot);
            var forward = frame.map(space -> world(space, storedForward)).orElse(storedForward);
            var tube = cannon.tube();
            var start = cannon.cannon().getStartPos();
            var end = start.relative(direction, tube.size());
            double muzzle = Vec3.atLowerCornerOf(end).dot(Vec3.atLowerCornerOf(direction.getNormal())) - 1;
            Map<String, Value> data = new LinkedHashMap<>();
            data.put("kind", new Value.Text("big_cannon"));
            data.put("pivot", vector(pivot));
            data.put("facing", vector(forward.subtract(pivot).normalize()));
            data.put("muzzle", Value.of(muzzle));
            frame.ifPresent(space -> {
                var origin = space.toWorld(Vec3.ZERO);
                data.put("frame", DefinitionCodec.object(Map.of(
                    "x", vector(space.toWorld(new Vec3(1, 0, 0)).subtract(origin)),
                    "y", vector(space.toWorld(new Vec3(0, 1, 0)).subtract(origin)),
                    "z", vector(space.toWorld(new Vec3(0, 0, 1)).subtract(origin)))));
            });
            data.put("yaw_offset", Value.of(Mth.wrapDegrees(direction.toYRot() - entity.getInitialYaw())));
            data.put("pitch_up", Value.of(true));
            data.put("limits", new Value.Sequence(List.of(Value.of(-360, 360),
                Value.of(-entity.maximumDepression(), entity.maximumElevation()))));
            data.put("cycle", Value.of(2 * Math.max(1, QuickfiringBreechBlockEntity.getOpeningTime())));
            data.put("loaded", Value.of(cannon.loaded()));
            current(cannon).ifPresent(flight -> data.put("flight", flight));
            data.put("tube", Value.of(tube.size()));
            data.put("chambers", Value.of(chambers(cannon)));
            data.put("max_stress", Value.of(cannon.cannon().getMaxSafeCharges()));
            data.put("ram_strength", Value.of(CBCConfigs.server().cannons.ramRodStrength.get()));
            data.put("ram_reach", Value.of(CBCConfigs.server().cannons.ramRodReach.get()));
            var failures = CBCConfigs.server().failure;
            data.put("squib", Value.of(!failures.disableAllFailure.get() && failures.squibChance.getF() > 0));
            data.put("stuck_velocity", Value.of(cannon.cannon().getBlocks().get(start).state().getBlock()
                instanceof BigCannonBlock chamber ? chamber.getCannonMaterial().properties().minimumVelocityPerBarrel()
                : -1));
            data.put("bore", bore(cannon));
            data.put("munitions", DefinitionCodec.object(munitions(level)));
            return DefinitionCodec.object(data);
        });
    }

    // The flight of what the bore holds now: its projectile and every charge behind it, as CBC adds them up.
    private static Optional<Value> current(CbcCannon cannon) {
        if (!cannon.loaded()) {
            return Optional.empty();
        }
        var level = cannon.entity().level();
        float speed = 0;
        for (var info : cannon.bore()) {
            if (CbcCannon.propellant(info)) {
                speed += Math.max(0, ((BigCannonPropellantBlock) info.state().getBlock()).getChargePower(info));
                continue;
            }
            if (!(info.state().getBlock() instanceof ProjectileBlock<?> projectile)) {
                return Optional.empty();
            }
            var round = projectile.getProjectile(level, ((BigCannonMunitionBlock) projectile)
                .getExtractedItem(info, level.registryAccess()));
            return round == null ? Optional.empty()
                : flight(level, round, speed + round.addedChargePower());
        }
        return Optional.empty();
    }

    private static Optional<Value> flight(Level level, AbstractBigCannonProjectile round, float speed) {
        var properties = ((ProjectilePropertiesAccess) round).folkwaysBallistics();
        var dimension = DimensionMunitionPropertiesHandler.getProperties(level);
        if (properties == null || properties.isQuadraticDrag()) {
            return Optional.empty();
        }
        double gravity = -properties.gravity() * dimension.gravityMultiplier();
        double retention = 1 - Math.min(1, properties.drag() * dimension.dragMultiplier());
        return speed > 0 && gravity >= 0 && retention >= 0 && retention <= 1
            ? Optional.of(DefinitionCodec.object(Map.of("speed", Value.of(speed), "gravity", Value.of(gravity),
                "retention", Value.of(retention)))) : Optional.empty();
    }

    /** The leading cells that may hold propellant without bursting: CBC's thick tubing. */
    private static int chambers(CbcCannon cannon) {
        int count = 0;
        for (var pos : cannon.tube()) {
            var cell = cannon.cannon().getBlocks().get(pos);
            if (!(cannon.cannon().presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity barrel)
                    || cell == null || !barrel.blockCanHandle(cell)) {
                break;
            }
            count++;
        }
        return count;
    }

    /** Every munition in the tube, from the first chamber toward the muzzle, with its cell. */
    private static Value bore(CbcCannon cannon) {
        List<Value> cells = new ArrayList<>();
        var tube = cannon.tube();
        for (int i = 0; i < tube.size(); i++) {
            var info = cannon.chamber(tube.get(i)).block();
            if (!(info.state().getBlock() instanceof BigCannonMunitionBlock)) {
                continue;
            }
            Map<String, Value> cell = new LinkedHashMap<>();
            cell.put("item", new Value.Text(BuiltInRegistries.ITEM.getKey(info.state().getBlock().asItem()).toString()));
            cell.put("cell", Value.of(i));
            if (info.state().getBlock() instanceof BigCartridgeBlock) {
                float power = BigCartridgeBlock.getPowerFromData(info);
                cell.put("power", Value.of(power));
                cell.put("spent", Value.of(power <= 0));
            }
            cells.add(DefinitionCodec.object(cell));
        }
        return new Value.Sequence(cells);
    }

    /**
     * Each big cannon munition by item: a projectile's own charge power, flight, size, blast and whether it can lodge;
     * a charge's power and stress; a cartridge's power and stress per level of its {@code createbigcannons:power}.
     */
    private static Map<String, Value> munitions(Level level) {
        long now = level.getGameTime();
        if (munitionsLevel == level && now >= munitionsAt && now - munitionsAt < MUNITION_REFRESH) {
            return munitions;
        }
        Map<String, Value> found = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Item item = block.asItem();
            if (!(block instanceof BigCannonMunitionBlock) || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            munition(level, block, new ItemStack(item)).ifPresent(data ->
                found.put(BuiltInRegistries.ITEM.getKey(item).toString(), data));
        }
        munitions = Map.copyOf(found);
        munitionsAt = now;
        munitionsLevel = level;
        return munitions;
    }

    private static Optional<Value> munition(Level level, Block block, ItemStack stack) {
        Map<String, Value> data = new LinkedHashMap<>();
        if (block instanceof ProjectileBlock<?> projectile) {
            AbstractBigCannonProjectile round;
            try {
                round = projectile.getProjectile(level, stack);
            } catch (RuntimeException unavailable) {
                return Optional.empty();
            }
            if (round == null) {
                return Optional.empty();
            }
            data.put("kind", new Value.Text("projectile"));
            data.put("power", Value.of(round.addedChargePower()));
            data.put("size", Value.of(projectile.getExpectedSize()));
            data.put("squib", Value.of(round.canSquib()));
            data.put("blast", Value.of(blastRadius(round)));
            flight(level, round, 1).ifPresent(model -> {
                var fields = DefinitionCodec.fields(model);
                data.put("gravity", fields.get("gravity"));
                data.put("retention", fields.get("retention"));
            });
            return Optional.of(DefinitionCodec.object(data));
        }
        if (block instanceof BigCartridgeBlock cartridge) {
            var unit = BigCartridgeBlockItem.getWithPower(1);
            data.put("kind", new Value.Text("cartridge"));
            data.put("power", Value.of(cartridge.getChargePower(unit)));
            data.put("stress", Value.of(cartridge.getStressOnCannon(unit)));
            data.put("max", Value.of(cartridge.getMaximumPowerLevels()));
            data.put("component", new Value.Text(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(CBCDataComponents.POWER)
                .toString()));
            return Optional.of(DefinitionCodec.object(data));
        }
        if (block instanceof BigCannonPropellantBlock charge) {
            data.put("kind", new Value.Text("charge"));
            data.put("power", Value.of(charge.getChargePower(stack)));
            data.put("stress", Value.of(charge.getStressOnCannon(stack)));
            return Optional.of(DefinitionCodec.object(data));
        }
        return Optional.empty();
    }

    /** Twice the larger blast power of a plain shell; negative for a fuzed payload Folkways does not model. */
    static double blastRadius(AbstractBigCannonProjectile round) {
        if (round instanceof HEShellProjectile || round instanceof APShellProjectile) {
            var explosion = CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                .getPropertiesOf(round.getType()).explosion();
            return 2 * Math.max(explosion.blockDamagePower(), explosion.entityDamagePower());
        }
        return round instanceof FuzedBigCannonProjectile ? -1 : 0;
    }

    private static Vec3 world(WorldSpaces.Frame space, Vec3 storage) {
        return space.toWorld(storage.subtract(Vec3.atLowerCornerOf(space.storageOrigin())));
    }

    private static Value vector(Vec3 v) {
        return Value.of(v.x, v.y, v.z);
    }

    static boolean spent(StructureBlockInfo info) {
        return info.state().getBlock() instanceof BigCartridgeBlock && BigCartridgeBlock.getPowerFromData(info) <= 0;
    }
}
