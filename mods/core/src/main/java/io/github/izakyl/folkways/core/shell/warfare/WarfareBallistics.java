package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.data.CustomData;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A Superb Warfare emplacement's {@code ballistics} reading: its muzzle, the limits of its aim, its shell and how it
 * flies, range, cycle and what it holds. The flight is offered only for a gun firing one plain shell the way the
 * reading models it.
 */
public final class WarfareBallistics {

    private WarfareBallistics() {
    }

    static Optional<Value> read(VehicleEntity vehicle) {
        String weapon = WarfareMount.weapon(vehicle);
        GunData data = vehicle.getGunData(weapon);
        if (data == null) {
            return Optional.empty();
        }
        Map<String, Value> found = new LinkedHashMap<>();
        found.put("kind", new Value.Text("warfare"));
        Vec3 muzzle = muzzle(vehicle, weapon);
        found.put("pivot", Value.of(muzzle.x, muzzle.y, muzzle.z));
        Vec3 facing = (vehicle instanceof MortarEntity ? vehicle.getLookAngle() : vehicle.getShootVec(weapon, 1))
            .normalize();
        found.put("facing", Value.of(facing.x, facing.y, facing.z));
        found.put("muzzle", Value.of(0));
        // Minecraft's pitch: positive aims down.
        found.put("pitch_up", Value.of(false));
        found.put("limits", new Value.Sequence(List.of(Value.of(-360, 360),
            Value.of(-vehicle.getTurretMaxPitch(), -vehicle.getTurretMinPitch()))));
        found.put("cycle", Value.of(cycleTicks(data)));
        found.put("loaded", Value.of(data.ammo.get() >= data.get(GunProp.AMMO_COST_PER_SHOOT)));
        found.put("indirect", Value.of(vehicle instanceof MortarEntity || vehicle instanceof Fh77bwEntity));
        found.put("range", Value.of(data.get(GunProp.RANGE)));
        found.put("damage", Value.of(data.get(GunProp.DAMAGE)));
        found.put("blast", Value.of(2 * data.get(GunProp.EXPLOSION_RADIUS)));
        var shell = ammunition(vehicle);
        if (shell != null && shell.item().isPresent()) {
            found.put("ammunition", new Value.Text(shell.item().get().toString()));
            found.put("magazine", Value.of(IntStream.range(0, Math.max(0, vehicle.getContainerSize()))
                .mapToObj(vehicle::getItem).filter(stack -> !stack.isEmpty() && Goods.matches(stack, shell))
                .mapToInt(ItemStack::getCount).sum()));
        }
        double speed = vehicle.getProjectileVelocity(weapon);
        double gravity = vehicle.getProjectileGravity(weapon);
        if (unmodified(vehicle) && Double.isFinite(speed) && speed > 0 && Double.isFinite(gravity) && gravity >= 0) {
            // Superb Warfare shells fly without drag.
            found.put("flight", DefinitionCodec.object(Map.of("speed", Value.of(speed), "gravity", Value.of(gravity),
                "retention", Value.of(1))));
        }
        return Optional.of(DefinitionCodec.object(found));
    }

    private static Vec3 muzzle(VehicleEntity vehicle, String weapon) {
        return vehicle instanceof MortarEntity ? vehicle.getEyePosition() : vehicle.getShootPos(weapon, 1);
    }

    static int cycleTicks(GunData data) {
        return Math.max(data.get(GunProp.BOLT_ACTION_TIME) + 1,
            Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM)))));
    }

    /** The shell the gun is set to fire: a mortar's shell, otherwise its selected ammunition. */
    public static ItemSpec ammunition(VehicleEntity vehicle) {
        if (vehicle instanceof MortarEntity) {
            return ItemSpec.of(ModItems.MORTAR_SHELL.get());
        }
        var data = vehicle.getGunData(WarfareMount.weapon(vehicle));
        if (data == null) {
            return null;
        }
        ItemStack stack = data.selectedAmmoConsumer().stack();
        return stack.isEmpty() ? null : ItemSpec.exact(stack);
    }

    /**
     * Whether the gun fires as its data says: one plain high-explosive shell (or a mortar shell) with no perks or
     * overrides, a finite velocity and gravity, and no tow launcher.
     */
    public static boolean unmodified(VehicleEntity station) {
        var data = station.getGunData(WarfareMount.weapon(station));
        if (data == null || station instanceof TowEntity || !data.propertyOverrideString.get().isBlank()) {
            return false;
        }
        for (var type : GunData.PERK_TYPES) {
            if (!data.perk.getInstances(type).isEmpty()) {
                return false;
            }
        }
        if (!Double.isFinite(data.get(GunProp.VELOCITY))
            || data.get(GunProp.VELOCITY) <= 0
            || !Double.isFinite(data.get(GunProp.GRAVITY))
            || data.get(GunProp.GRAVITY) < 0
            || !Double.isFinite(data.get(GunProp.EXPLOSION_RADIUS))
            || data.get(GunProp.EXPLOSION_RADIUS) < 0
            || !Double.isFinite(data.get(GunProp.SPREAD))
            || data.get(GunProp.SPREAD) < 0
            || data.get(GunProp.PROJECTILE_AMOUNT) != 1) {
            return false;
        }
        if (station instanceof MortarEntity mortar) {
            ItemStack shell = mortar.getItem(0);
            return shell.isEmpty() || shell.is(ModItems.MORTAR_SHELL.get());
        }
        var projectile = data.get(GunProp.PROJECTILE);
        return projectile.getItemId().equals("superbwarfare:cannon_shell")
            && projectile.data == null
            && !CustomData.LAUNCHABLE_ENTITY.containsKey(projectile.getItemId())
            && data.get(GunProp.SHELL_TYPE).equals("HE");
    }
}
