package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.api.event.ShootEvent;
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponAdapter;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
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
    private static final ThreadLocal<Receipt> FIRING = new ThreadLocal<>();
    private static final Map<ResidentEntity, Map<ResourceLocation, Long>> NEXT_SHOT = new WeakHashMap<>();
    private static final class Receipt {
        final ShootParameters parameters;
        boolean fired;
        Receipt(ShootParameters parameters) { this.parameters = parameters; }
    }

    private final ResourceLocation gun;
    private final ResourceLocation ammo;
    private final double range;

    WarfareWeapon(String gun, String ammo, double range) {
        this.gun = WarfareWeapons.nativeId(gun);
        this.ammo = WarfareWeapons.nativeId(ammo);
        this.range = range;
    }

    public ResourceLocation id() { return ResourceLocation.fromNamespaceAndPath("folkways", "warfare/" + gun.getPath()); }
    ResourceLocation nativeId() { return gun; }
    public ItemSpec weapon() { return ItemSpec.of(gun); }
    public ItemSpec ammunition() { return ItemSpec.of(ammo); }
    @Override public double range() { return range; }
    @Override public boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof GunItem && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(gun);
    }
    @Override public boolean usable(Worker worker, ItemStack stack) {
        return accepts(stack) && Goods.countIn(worker.pack(), ammunition()) > 0;
    }
    @Override public Optional<WeaponSession> open(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity resident) || resident.hasWeaponSession() || worker.pack() != resident.pack()
                || !resident.isAlive() || resident.isRemoved() || !(resident.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) { return Optional.empty(); }
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            ItemStack stack = worker.pack().getItem(slot);
            if (usable(worker, stack)) { return open(worker, stack); }
        }
        return Optional.empty();
    }

    @Override public Optional<WeaponSession> open(Worker worker, ItemStack selected) {
        if (!(worker.body() instanceof ResidentEntity resident) || resident.hasWeaponSession() || worker.pack() != resident.pack()
                || !resident.isAlive() || resident.isRemoved() || !(resident.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread() || !usable(worker, selected)) { return Optional.empty(); }
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            if (worker.pack().getItem(slot) != selected) { continue; }
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

    // Lend one supplied round to the native firing transaction. Player-loaded rounds remain in the magazine.
    boolean shoot(ServerLevel level, Worker who, LivingEntity target, ItemStack weapon) {
        GunData data = GunData.from(weapon);
        var consumer = data.selectedAmmoConsumer();
        if (consumer.getType() != AmmoConsumer.AmmoConsumeType.PLAYER_AMMO || consumer.getPlayerAmmoType() == null
                || !BuiltInRegistries.ITEM.getKey(consumer.getPlayerAmmoType().getItem()).equals(ammo)
                || data.get(GunProp.AMMO_COST_PER_SHOOT) != 1 || data.useBackpackAmmo() || !ready(level, (ResidentEntity) who.body())) { return false; }
        int slot = -1;
        for (int n = 0; n < who.pack().getContainerSize(); n++) {
            if (Goods.matches(who.pack().getItem(n), ammunition())) { slot = n; break; }
        }
        int before = data.ammo.get();
        if (slot < 0 || before < 0 || before == Integer.MAX_VALUE) { return false; }
        data.ammo.set(before + 1);
        if (!data.canShoot(who.body())) { data.ammo.set(before); data.save(); return false; }
        Vec3 muzzle = who.body().getEyePosition();
        var parameters = new ShootParameters(who.body(), who.body(), level, muzzle,
            target.getBoundingBox().getCenter().subtract(muzzle).normalize(), data, 0.8, true, target.getUUID(), null);
        Receipt receipt = new Receipt(parameters);
        Receipt previous = FIRING.get();
        boolean boltBefore = data.bolt.needed.get();
        FIRING.set(receipt);
        try {
            data.shoot(parameters);
            if (receipt.fired) {
                who.pack().removeItem(slot, 1);
                int interval = Math.max(data.get(GunProp.BOLT_ACTION_TIME) + 1,
                    Math.max(3, (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM)))));
                // Pack settlement copies stacks; keep the operator's cadence across hand-loan returns.
                NEXT_SHOT.computeIfAbsent((ResidentEntity) who.body(), ignored -> new java.util.HashMap<>())
                    .put(gun, level.getGameTime() + interval);
            }
            return receipt.fired;
        } finally {
            if (!receipt.fired) { data.ammo.set(before); data.bolt.needed.set(boltBefore); data.save(); }
            if (previous == null) { FIRING.remove(); } else { FIRING.set(previous); }
        }
    }

    static void shot(ShootEvent.Post event) {
        Receipt receipt = FIRING.get();
        if (receipt != null && event.getParameters() == receipt.parameters) { receipt.fired = true; }
    }
}
