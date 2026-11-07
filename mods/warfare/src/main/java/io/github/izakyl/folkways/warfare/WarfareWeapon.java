package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.data.CustomData;
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponAdapter;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class WarfareWeapon implements WeaponAdapter {

    private static final ThreadLocal<boolean[]> EMISSION = new ThreadLocal<>();
    private static final Map<ResidentEntity, Map<ResourceLocation, Long>> NEXT_SHOT = new WeakHashMap<>();

    private final ResourceLocation gun;
    private final ResourceLocation ammo;
    private final double range;

    WarfareWeapon(String gun, String ammo, double range) {
        this.gun = WarfareWeapons.nativeId(gun);
        this.ammo = WarfareWeapons.nativeId(ammo);
        this.range = range;
    }

    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("folkways", "warfare/" + gun.getPath());
    }

    ResourceLocation nativeId() {
        return gun;
    }

    public ItemSpec weapon() {
        return ItemSpec.of(gun);
    }

    public ItemSpec ammunition() {
        return ItemSpec.of(ammo);
    }

    @Override
    public double range() {
        return range;
    }

    @Override
    public boolean accepts(ItemStack stack) {
        if (stack.isEmpty()
            || !(stack.getItem() instanceof GunItem)
            || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(gun)) {
            return false;
        }
        GunData data = GunData.from(stack);
        if (!data.propertyOverrideString.get().isBlank()) {
            return false;
        }
        // Perks can alter projectiles or add effects outside the computed explosion properties.
        for (var type : GunData.PERK_TYPES) {
            if (!data.perk.getInstances(type).isEmpty()) {
                return false;
            }
        }
        var consumer = data.selectedAmmoConsumer();
        var projectile = data.get(GunProp.PROJECTILE);
        return projectile.getItemId().equals("superbwarfare:projectile")
            && projectile.data == null
            && !CustomData.LAUNCHABLE_ENTITY.containsKey(projectile.getItemId())
            && data.get(GunProp.BYPASSES_ARMOR) < 1
            && data.get(GunProp.EXPLOSION_DAMAGE) == 0
            && data.get(GunProp.EXPLOSION_RADIUS) == 0
            && data.get(GunProp.PROJECTILE_AMOUNT) > 0
            && data.get(GunProp.AMMO_COST_PER_SHOOT) == 1
            && Double.isFinite(data.get(GunProp.VELOCITY))
            && data.get(GunProp.VELOCITY) > 0
            && Double.isFinite(data.get(GunProp.GRAVITY))
            && data.get(GunProp.GRAVITY) >= 0
            && !Boolean.TRUE.equals(data.get(GunProp.AUTO_RELOAD))
            && !data.useBackpackAmmo()
            && data.ammo.get() >= 0
            && data.ammo.get() < Integer.MAX_VALUE
            && consumer.getType() == AmmoConsumer.AmmoConsumeType.PLAYER_AMMO
            && consumer.getLoadAmount() == 1
            && consumer.getPlayerAmmoType() != null
            && BuiltInRegistries.ITEM.getKey(consumer.getPlayerAmmoType().getItem()).equals(ammo);
    }

    @Override
    public boolean usable(Worker worker, ItemStack stack) {
        return accepts(stack) && (GunData.from(stack).ammo.get() > 0 || Goods.countIn(worker.pack(), ammunition()) > 0);
    }


    @Override
    public Optional<WeaponSession> open(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity resident)
            || resident.hasWeaponSession()
            || worker.pack() != resident.pack()
            || !resident.isAlive()
            || resident.isRemoved()
            || !(resident.level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()) {
            return Optional.empty();
        }
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            ItemStack stack = worker.pack().getItem(slot);
            if (usable(worker, stack)) {
                return open(worker, stack);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<WeaponSession> open(Worker worker, ItemStack selected) {
        if (!(worker.body() instanceof ResidentEntity resident)
            || resident.hasWeaponSession()
            || worker.pack() != resident.pack()
            || !resident.isAlive()
            || resident.isRemoved()
            || !(resident.level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()
            || !usable(worker, selected)) {
            return Optional.empty();
        }
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            if (worker.pack().getItem(slot) != selected) {
                continue;
            }
            ItemStack actual = worker.pack().removeItemNoUpdate(slot);
            WarfareSession session = new WarfareSession(worker, this);
            resident.claimWeaponHand(session, actual);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    boolean ready(ServerLevel level, ResidentEntity body) {
        return level.getGameTime() >= NEXT_SHOT.getOrDefault(body, Map.of()).getOrDefault(gun, Long.MIN_VALUE);
    }

    // The native magazine consumes loaded rounds first; loose supplies lend one round when empty.
    boolean shoot(ServerLevel level, Worker who, LivingEntity target, ItemStack weapon) {
        if (!accepts(weapon) || !ready(level, (ResidentEntity) who.body())) {
            return false;
        }
        GunData data = GunData.from(weapon);
        int slot = -1;
        for (int n = 0; n < who.pack().getContainerSize(); n++) {
            if (Goods.matches(who.pack().getItem(n), ammunition())) {
                slot = n;
                break;
            }
        }
        int before = data.ammo.get();
        if (before == 0 && slot < 0 || before < 0 || before == Integer.MAX_VALUE) {
            return false;
        }
        boolean supplied = before == 0;
        data.ammo.set(supplied ? 1 : before);
        if (!data.canShoot(who.body())) {
            data.ammo.set(before);
            data.save();
            return false;
        }
        Vec3 muzzle = who.body().getEyePosition();
        var parameters =
            new ShootParameters(
            who.body(),
            who.body(),
            level,
            muzzle,
            target.getBoundingBox().getCenter().subtract(muzzle).normalize(),
            data,
            0.8,
            true,
            target.getUUID(),
            null);
        ItemStack round =
            supplied
            ? Containers.extract(who.pack(), stack -> Goods.matches(stack, ammunition()), 1)
            : ItemStack.EMPTY;
        if (supplied && round.isEmpty()) {
            data.ammo.set(before);
            data.save();
            return false;
        }
        boolean fired = false;
        boolean boltBefore = data.bolt.needed.get();
        try {
            fired = receipt(() -> data.shoot(parameters));
            if (fired) {
                int interval =
                    Math.max(
                    data.get(GunProp.BOLT_ACTION_TIME) + 1,
                    Math.max(3, (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM)))));
                // Pack settlement copies stacks; keep the operator's cadence across hand-loan
                // returns.
                NEXT_SHOT
                    .computeIfAbsent((ResidentEntity) who.body(), ignored -> new HashMap<>())
                    .put(gun, level.getGameTime() + interval);
            }
            return fired;
        } finally {
            if (!fired) {
                data.ammo.set(before);
                data.bolt.needed.set(boltBefore);
                data.save();
                if (!round.isEmpty()) {
                    ItemStack left = Containers.insert(who.pack(), round);
                    if (!left.isEmpty()) {
                        who.spill(left);
                    }
                }
            }
        }
    }

    public static boolean emitted(boolean accepted) {
        boolean[] receipt = EMISSION.get();
        if (accepted && receipt != null) {
            receipt[0] = true;
        }
        return accepted;
    }

    static boolean receipt(Runnable action) {
        boolean[] previous = EMISSION.get();
        boolean[] receipt = {false};
        EMISSION.set(receipt);
        try {
            action.run();
            return receipt[0];
        } finally {
            if (previous == null) {
                EMISSION.remove();
            } else {
                EMISSION.set(previous);
            }
        }
    }
}
