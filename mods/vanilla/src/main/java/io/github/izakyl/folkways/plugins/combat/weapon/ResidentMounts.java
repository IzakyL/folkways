package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.base.persist.Required;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.plugins.pasture.PastureContent;
import io.github.izakyl.folkways.plugins.pasture.PastureHorses;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.ride.Riding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Horse holdings outlive unloaded riders and deferred releases outlive unloaded horses. */
public final class ResidentMounts {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "resident_mounts");
    private final Colony colony;
    private int countdown;
    private final Map<UUID, Link> links = new LinkedHashMap<>();

    private static final class Link {
        private final UUID horse;
        private final boolean noAi;
        private boolean saddle;
        private boolean armor;

        private Link(UUID horse, boolean noAi) {
            this.horse = horse;
            this.noAi = noAi;
        }
    }

    ResidentMounts(Colony colony) {
        this.colony = colony;
        CompoundTag saved = colony.kept(ID);
        if (saved.isEmpty()) {
            return;
        }
        for (Tag value : Required.list(saved, "riders", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            Link link = new Link(Required.uuid(row, "horse"), Required.flag(row, "no_ai"));
            link.saddle = Required.flag(row, "saddle");
            link.armor = Required.flag(row, "armor");
            links.put(Required.uuid(row, "rider"), link);
        }
    }

    public static void died(LivingDeathEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            releaseFor(level, event.getEntity());
        }
    }

    public static void removed(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity().getRemovalReason() != null
                && event.getEntity().getRemovalReason().shouldDestroy()) {
            releaseFor(level, event.getEntity());
        }
    }

    private static void releaseFor(ServerLevel level, Entity entity) {
        if (!(entity instanceof ResidentEntity) && !(entity instanceof AbstractHorse)) {
            return;
        }
        for (Colony colony : Colonies.all(level.getServer())) {
            PatrolContent.suppliesOf(colony).ifPresent(supplies -> {
                ResidentMounts mounts = supplies.mounts();
                for (UUID rider : List.copyOf(mounts.links.keySet())) {
                    Link link = mounts.links.get(rider);
                    if (rider.equals(entity.getUUID()) || link.horse.equals(entity.getUUID())) {
                        Entity found = entity instanceof AbstractHorse ? entity : level.getEntity(link.horse);
                        if (found instanceof AbstractHorse horse) {
                            mounts.release(level, rider, horse);
                        }
                    }
                }
            });
        }
    }

    void close(Closing why) {
        if (why == Closing.SHUTDOWN) {
            return;
        }
        for (UUID rider : List.copyOf(links.keySet())) {
            for (ServerLevel level : ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
                if (level.getEntity(links.get(rider).horse) instanceof AbstractHorse horse) {
                    release(level, rider, horse);
                    break;
                }
            }
        }
    }

    public static Optional<AbstractHorse> horse(Mob body) {
        if (!(body instanceof ResidentEntity resident) || !(body.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return resident.colonyId()
            .flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(colony -> PatrolContent.suppliesOf(colony))
            .flatMap(supplies -> supplies.mounts().horse(level, body.getUUID()));
    }

    public Optional<AbstractHorse> horse(ServerLevel level, UUID rider) {
        Link link = links.get(rider);
        return link != null && level.getEntity(link.horse) instanceof AbstractHorse horse
            && horse.isAlive() && held(link) ? Optional.of(horse) : Optional.empty();
    }

    public boolean assigned(UUID rider) {
        return links.containsKey(rider);
    }

    private boolean held(Link link) {
        return colony.claimOf(link.horse).filter(holding -> holding.owner().equals(ID)).isPresent();
    }

    void tick(MinecraftServer server, LoadoutProfiles profiles) {
        if (--countdown > 0) {
            return;
        }
        countdown = 20;
        var roster = colony.residents().stream().map(resident -> resident.id()).collect(Collectors.toSet());
        for (UUID rider : List.copyOf(links.keySet())) {
            Link link = links.get(rider);
            boolean wanted = roster.contains(rider)
                && profiles.assigned(rider).stream().flatMap(profile -> profile.entries().stream())
                    .anyMatch(entry -> entry.kind() == LoadoutProfile.Kind.MOUNT);
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(link.horse) instanceof AbstractHorse horse) {
                    if (!wanted || !horse.isAlive() || !held(link)) {
                        release(level, rider, horse);
                    } else {
                        horse.addTag(PastureContent.CLAIMED);
                        if (horse.getPassengers().isEmpty()) {
                            park(horse);
                        }
                    }
                    break;
                }
            }
        }
        for (var resident : colony.residents()) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(resident.id()) instanceof ResidentEntity body) {
                    reconcile(level, body, profiles.assigned(body.id()));
                    break;
                }
            }
        }
    }

    public void reconcile(ServerLevel level, ResidentEntity body, Optional<LoadoutProfile> profile) {
        boolean wanted = body.isAlive() && colony.claimOf(body.id()).isPresent()
            && profile.stream().flatMap(value -> value.entries().stream())
                .anyMatch(entry -> entry.kind() == LoadoutProfile.Kind.MOUNT);
        if (!wanted) {
            horse(level, body.id()).ifPresent(horse -> release(level, body.id(), horse));
            if (body.locomotion().id().equals(Riding.INSTANCE.id())) {
                Locomotions.override(body, null);
            }
            return;
        }
        if (!links.containsKey(body.id())) {
            PastureHorses.claim(colony, level, ID).ifPresent(horse -> {
                links.put(body.id(), new Link(horse.getUUID(), horse.isNoAi()));
                horse.setPersistenceRequired();
                park(horse);
                save();
            });
        }
        horse(level, body.id()).ifPresent(horse -> {
            Link link = links.get(body.id());
            for (LoadoutProfile.Entry entry : profile.orElseThrow().entries()) {
                if (entry.kind() == LoadoutProfile.Kind.MOUNT) {
                    if (horse.getSlot(400).get().isEmpty()) {
                        ItemStack saddle = Containers.extract(body.pack(), entry::matches, 1);
                        if (!saddle.isEmpty()) {
                            horse.getSlot(400).set(saddle);
                            link.saddle = true;
                            save();
                        }
                    }
                } else if (entry.kind() == LoadoutProfile.Kind.HORSE_ARMOR) {
                    ItemStack worn = horse.getItemBySlot(EquipmentSlot.BODY);
                    if (link.armor && !entry.matches(worn)) {
                        horse.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
                        giveBack(level, body, horse, worn);
                        link.armor = false;
                        save();
                    }
                    if (horse.getItemBySlot(EquipmentSlot.BODY).isEmpty()) {
                        ItemStack armor = Containers.extract(body.pack(), entry::matches, 1);
                        if (!armor.isEmpty()) {
                            horse.setItemSlot(EquipmentSlot.BODY, armor);
                            link.armor = true;
                            save();
                        }
                    }
                }
            }
            if (link.armor && profile.get().entries().stream()
                    .noneMatch(entry -> entry.kind() == LoadoutProfile.Kind.HORSE_ARMOR)) {
                ItemStack armor = horse.getItemBySlot(EquipmentSlot.BODY);
                horse.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
                link.armor = false;
                giveBack(level, body, horse, armor);
                save();
            }
        });
        // Other links (for example a cart lead) retain their own movement while in use.
        if (body.locomotion().id().equals(Locomotions.WALKING)) {
            Locomotions.override(body, Riding.INSTANCE);
        }
    }

    public static boolean riding(Mob body) {
        return horse(body).filter(horse -> body.getVehicle() == horse).isPresent();
    }

    public static void park(AbstractHorse horse) {
        horse.getNavigation().stop();
        horse.setSpeed(0);
        horse.setZza(0);
        horse.setDeltaMovement(Vec3.ZERO);
        horse.setNoAi(true);
    }

    // Mounts without asking how near the horse is: for a rider who has already walked to a cell beside it.
    public static boolean board(Mob rider) {
        AbstractHorse horse = horse(rider).orElse(null);
        if (horse == null || !horse.isSaddled() || horse.isLeashed()
                || rider.getVehicle() != horse && horse.isVehicle()) {
            return false;
        }
        if (rider.getVehicle() != horse && !rider.startRiding(horse)) {
            return false;
        }
        horse.setNoAi(false);
        horse.positionRider(rider);
        horse.goalSelector.disableControlFlag(Goal.Flag.MOVE);
        horse.goalSelector.disableControlFlag(Goal.Flag.JUMP);
        return true;
    }

    public static void dismount(Mob rider) {
        horse(rider).filter(horse -> rider.getVehicle() == horse).ifPresent(horse -> {
            rider.stopRiding();
            park(horse);
        });
    }

    private void release(ServerLevel level, UUID rider, AbstractHorse horse) {
        Link link = links.remove(rider);
        if (link == null) {
            return;
        }
        ResidentEntity body = level.getEntity(rider) instanceof ResidentEntity resident ? resident : null;
        horse.ejectPassengers();
        if (link.saddle) {
            ItemStack saddle = horse.getSlot(400).get();
            horse.getSlot(400).set(ItemStack.EMPTY);
            giveBack(level, body, horse, saddle);
        }
        if (link.armor) {
            ItemStack armor = horse.getItemBySlot(EquipmentSlot.BODY);
            horse.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
            giveBack(level, body, horse, armor);
        }
        horse.setNoAi(link.noAi);
        horse.goalSelector.enableControlFlag(Goal.Flag.MOVE);
        horse.goalSelector.enableControlFlag(Goal.Flag.JUMP);
        PastureHorses.release(colony, ID, horse);
        if (body != null && body.locomotion().id().equals(Riding.INSTANCE.id())) {
            Locomotions.override(body, null);
        }
        save();
    }

    private void giveBack(ServerLevel level, ResidentEntity body, AbstractHorse horse, ItemStack item) {
        ItemStack left = item;
        for (var at : colony.storesIn(level.dimension())) {
            if (StoreRule.stores(colony.storeRules(at)) && StoreRule.admits(colony.storeRules(at), left)) {
                var store = colony.store(level, at);
                if (store.isPresent()) {
                    left = Containers.insert(store.get(), left);
                    colony.inventoryFeedback().changed(Stash.at(at));
                }
            }
            if (left.isEmpty()) {
                return;
            }
        }
        if (body != null && body.isAlive()) {
            left = Containers.insert(body.pack(), left);
        }
        if (!left.isEmpty()) {
            horse.spawnAtLocation(left);
        }
    }

    private void save() {
        ListTag rows = new ListTag();
        links.forEach((rider, link) -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("rider", rider);
            row.putUUID("horse", link.horse);
            row.putBoolean("no_ai", link.noAi);
            row.putBoolean("saddle", link.saddle);
            row.putBoolean("armor", link.armor);
            rows.add(row);
        });
        CompoundTag tag = new CompoundTag();
        tag.put("riders", rows);
        colony.keep(ID, tag);
    }
}
