package io.github.izakyl.folkways.core.shell.cbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.quickfiring_breech.QuickfiringBreechBlockEntity;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;

record CbcCannon(PitchOrientedContraptionEntity entity, MountedBigCannonContraption cannon) {

    enum Kind {
        NONE,
        BIG_CANNON,
        AUTOCANNON,
        DROP_MORTAR
    }

    /** The cannon a running mount carries, in any orientation. */
    static Optional<PitchOrientedContraptionEntity> assembled(CannonMountBlockEntity mount) {
        var entity = mount.getContraption();
        return mount.isRunning() && entity != null && entity.isAlive()
            && entity.getContraption() instanceof AbstractMountedCannonContraption ? Optional.of(entity) : Optional.empty();
    }

    static Kind kind(CannonMountBlockEntity mount) {
        return assembled(mount).map(entity -> {
            if (entity.getContraption() instanceof MountedAutocannonContraption) {
                return Kind.AUTOCANNON;
            }
            if (entity.getContraption() instanceof MountedBigCannonContraption cannon) {
                return cannon.isDropMortar() ? Kind.DROP_MORTAR : Kind.BIG_CANNON;
            }
            return Kind.NONE;
        }).orElse(Kind.NONE);
    }

    static Optional<CbcCannon> mounted(CannonMountBlockEntity mount) {
        return assembled(mount).filter(entity -> entity.getContraption() instanceof MountedBigCannonContraption cannon
                && !cannon.isDropMortar() && cannon.initialOrientation().getAxis().isHorizontal())
            .map(entity -> new CbcCannon(entity, (MountedBigCannonContraption) entity.getContraption()));
    }

    static Optional<CbcCannon> dropMortar(CannonMountBlockEntity mount) {
        return assembled(mount).filter(entity -> entity.getContraption() instanceof MountedBigCannonContraption cannon
                && cannon.isDropMortar())
            .map(entity -> new CbcCannon(entity, (MountedBigCannonContraption) entity.getContraption()));
    }

    /** The breech of a mounted autocannon, with the entity that carries it. */
    record Autocannon(PitchOrientedContraptionEntity entity, MountedAutocannonContraption cannon,
                      AbstractAutocannonBreechBlockEntity breech) {
    }

    static Optional<Autocannon> autocannon(CannonMountBlockEntity mount) {
        return assembled(mount).flatMap(entity -> entity.getContraption() instanceof MountedAutocannonContraption cannon
                && cannon.getStartPos() != null
                && cannon.presentBlockEntities.get(cannon.getStartPos()) instanceof AbstractAutocannonBreechBlockEntity breech
            ? Optional.of(new Autocannon(entity, cannon, breech)) : Optional.empty());
    }

    BigCannonBehavior chamber(BlockPos pos) {
        return cannon.presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity barrel
            ? barrel.cannonBehavior() : null;
    }

    QuickfiringBreechBlockEntity quickBreech() {
        var pos = cannon.getStartPos().relative(cannon.initialOrientation().getOpposite());
        return cannon.presentBlockEntities.get(pos) instanceof QuickfiringBreechBlockEntity breech ? breech : null;
    }

    /** The tube cells from the first chamber toward the muzzle. */
    List<BlockPos> tube() {
        var cells = new ArrayList<BlockPos>();
        var dir = cannon.initialOrientation();
        for (var pos = cannon.getStartPos(); chamber(pos) != null; pos = pos.relative(dir)) {
            cells.add(pos);
        }
        return cells;
    }

    /** The contiguous munitions from the first chamber toward the muzzle. */
    List<StructureBlockInfo> bore() {
        var column = new ArrayList<StructureBlockInfo>();
        for (var pos : tube()) {
            var info = chamber(pos).block();
            if (info.state().isAir()) {
                break;
            }
            column.add(info);
        }
        return column;
    }

    static boolean propellant(StructureBlockInfo info) {
        return info.state().getBlock() instanceof BigCannonPropellantBlock
            && !(info.state().getBlock() instanceof ProjectileBlock<?>);
    }

    /** Propellant blocks ahead of the projectile in the bore. */
    int charges() {
        int count = 0;
        for (var info : bore()) {
            if (!propellant(info)) {
                break;
            }
            count++;
        }
        return count;
    }

    /** The projectile behind the charges, or an empty id. */
    String projectile() {
        for (var info : bore()) {
            if (info.state().getBlock() instanceof ProjectileBlock<?> projectile) {
                return BuiltInRegistries.BLOCK.getKey(projectile).toString();
            }
            if (!propellant(info)) {
                break;
            }
        }
        return "";
    }

    float stress() {
        float stress = 0;
        for (var info : bore()) {
            if (!propellant(info)) {
                break;
            }
            stress += ((BigCannonPropellantBlock) info.state().getBlock()).getStressOnCannon(info);
        }
        return stress;
    }

    boolean loaded() {
        var dir = cannon.initialOrientation();
        var bore = bore();
        if (bore.size() < 2 || !propellant(bore.getFirst())
                || !((BigCannonPropellantBlock) bore.getFirst().state().getBlock()).canBeIgnited(bore.getFirst(), dir)) {
            return false;
        }
        for (var info : bore) {
            if (!propellant(info)) {
                return info.state().getBlock() instanceof ProjectileBlock<?>;
            }
        }
        return false;
    }
}
