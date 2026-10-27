package io.github.izakyl.folkways.raid;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.MobTravel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

@EventBusSubscriber(modid = FolkwaysRaidMod.ID)
public final class RaidPlunder {

    private static final String CARGO = "folkways:plunder";
    private static final TagKey<Item> PRECIOUS =
        TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("folkways", "raid_precious"));
    private static final TagKey<Item> VALUABLE =
        TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("folkways", "raid_valuable"));

    private RaidPlunder() {
    }

    static boolean holding(Colony colony, ServerLevel level, RaidInstance raid, List<Mob> mobs) {
        for (var store : colony.storesIn(level.dimension()).stream().limit(128).toList()) {
            for (Mob mob : mobs) {
                if (mob.distanceToSqr(Vec3.atCenterOf(store.block(level))) > 9
                    || !MobTravel.sees(mob, store)
                    || !RaidEdits.loot(level, colony, store, mob)) {
                    continue;
                }
                boolean defended =
                    colony.residents().stream()
                    .map(resident -> level.getEntity(resident.id()))
                    .anyMatch(entity -> entity != null && entity.isAlive() && entity.distanceToSqr(mob) < 36);
                if (defended) {
                    continue;
                }
                raid.holdTicks = store.equals(raid.heldStore) ? raid.holdTicks + 20 : 20;
                raid.heldStore = store;
                return raid.holdTicks >= RaidConfig.STORE_HOLD.get();
            }
        }
        raid.holdTicks = 0;
        raid.heldStore = null;
        return false;
    }

    static void prepare(Colony colony, RaidInstance raid, MinecraftServer server) {
        List<CompoundTag> candidates = new ArrayList<>();
        double share = Math.min(RaidConfig.PLUNDER_SHARE.get(), raid.definition.limits().plunderShare());
        if (share <= 0) {
            return;
        }
        Mob actor =
            raid.members.stream()
            .flatMap(
            member -> {
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.getEntity(member.id()) instanceof Mob mob) {
                        return Stream.of(mob);
                    }
                }
                return Stream.<Mob>empty();
            })
            .findFirst()
            .orElse(null);
        if (actor == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (WorldPos store : colony.storesIn(level.dimension()).stream().limit(128).toList()) {
                if (!(store.realm() instanceof Realm.Dimension) || !RaidEdits.loot(level, colony, store, actor)) {
                    continue;
                }
                var container = colony.store(level, store).orElse(null);
                if (container == null) {
                    continue;
                }
                for (int slot = 0; slot < Math.min(256, container.getContainerSize()); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    CompoundTag row = new CompoundTag();
                    row.put("store", store.save());
                    row.putInt("slot", slot);
                    row.putInt("initialCount", stack.getCount());
                    row.put(
                        "stack",
                        stack.copyWithCount(Math.max(1, (int) Math.floor(stack.getCount() * share)))
                        .save(level.registryAccess()));
                    row.putInt("weight", stack.is(PRECIOUS) ? 10 : stack.is(VALUABLE) ? 4 : 1);
                    candidates.add(row);
                }
            }
        }
        candidates.sort(Comparator.comparingInt((CompoundTag row) -> row.getInt("weight")).reversed());
        int items = Math.min(RaidConfig.PLUNDER_ITEMS.get(), raid.definition.limits().plunderItems());
        int stacks = Math.min(RaidConfig.PLUNDER_STACKS.get(), raid.definition.limits().plunderStacks());
        for (CompoundTag row : candidates) {
            if (raid.plunder.size() >= stacks || items <= 0) {
                break;
            }
            ItemStack stack = ItemStack.parseOptional(server.registryAccess(), row.getCompound("stack"));
            stack.setCount(Math.min(items, stack.getCount()));
            row.put("stack", stack.save(server.registryAccess()));
            items -= stack.getCount();
            raid.plunder.add(row);
        }
    }

    static WorldPos target(RaidDirector director, Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        boolean changed = false;
        for (Tag value : director.raid().plunder) {
            CompoundTag row = (CompoundTag) value;
            if (row.getBoolean("done")) {
                continue;
            }
            WorldPos at = WorldPos.load(Reader.of(row.getCompound("store"))).orElse(null);
            if (at == null) {
                row.putBoolean("done", true);
                changed = true;
                continue;
            }
            if (!at.in(level)) {
                continue;
            }
            if (!director.colony().storesIn(level.dimension()).contains(at)) {
                row.putBoolean("done", true);
                changed = true;
                continue;
            }
            // Unloaded stores can return later; they must not block other loaded targets.
            if (!level.isLoaded(at.block(level))) {
                continue;
            }
            var container = director.colony().store(level, at).orElse(null);
            int slot = row.getInt("slot");
            ItemStack requested = ItemStack.parseOptional(level.registryAccess(), row.getCompound("stack"));
            if (!RaidEdits.loot(level, director.colony(), at, mob)
                || container == null
                || slot < 0
                || slot >= container.getContainerSize()
                || requested.isEmpty()
                || !ItemStack.isSameItemSameComponents(requested, container.getItem(slot))
                || container.getItem(slot).isEmpty()) {
                row.putBoolean("done", true);
                changed = true;
                continue;
            }
            if (changed) {
                director.save();
            }
            return at;
        }
        if (changed) {
            director.save();
        }
        return null;
    }

    private static void abandon(RaidDirector director, WorldPos at) {
        for (Tag value : director.raid().plunder) {
            CompoundTag row = (CompoundTag) value;
            if (!row.getBoolean("done")
                && WorldPos.load(Reader.of(row.getCompound("store"))).filter(at::equals).isPresent()) {
                row.putBoolean("done", true);
            }
        }
        director.save();
    }

    static boolean take(RaidDirector director, Mob mob, WorldPos at) {
        if (!(mob.level() instanceof ServerLevel level)
            || director.raid().phase != RaidInstance.Phase.PLUNDER
            || !director.owns(mob)
            || director.raid().paused
            || !at.in(level)) {
            return false;
        }
        if (!director.colony().storesIn(level.dimension()).contains(at)) {
            abandon(director, at);
            return false;
        }
        if (!level.isLoaded(at.block(level))) {
            return false;
        }
        var container = director.colony().store(level, at).orElse(null);
        if (container == null || !RaidEdits.loot(level, director.colony(), at, mob)) {
            abandon(director, at);
            return false;
        }
        if (mob.distanceToSqr(Vec3.atCenterOf(at.block(level))) > 9 || !MobTravel.sees(mob, at)) {
            return false;
        }
        ListTag cargo = mob.getPersistentData().getList(CARGO, Tag.TAG_COMPOUND);
        if (cargo.size()
            >= Math.min(RaidConfig.PLUNDER_PER_RAIDER.get(), director.raid().definition.limits().plunderPerRaider())) {
            return false;
        }
        int spentItems = 0;
        int spentStacks = 0;
        for (Tag value : director.raid().plunder) {
            CompoundTag row = (CompoundTag) value;
            if (row.contains("taken")) {
                spentStacks++;
                spentItems += ItemStack.parseOptional(level.registryAccess(), row.getCompound("taken")).getCount();
            }
        }
        int remaining =
            Math.min(RaidConfig.PLUNDER_ITEMS.get(), director.raid().definition.limits().plunderItems()) - spentItems;
        if (remaining <= 0
            || spentStacks
            >= Math.min(RaidConfig.PLUNDER_STACKS.get(), director.raid().definition.limits().plunderStacks())) {
            return false;
        }
        for (Tag value : director.raid().plunder) {
            CompoundTag row = (CompoundTag) value;
            if (row.getBoolean("done")
                || WorldPos.load(Reader.of(row.getCompound("store"))).filter(at::equals).isEmpty()) {
                continue;
            }
            int slot = row.getInt("slot");
            ItemStack requested = ItemStack.parseOptional(level.registryAccess(), row.getCompound("stack"));
            if (slot < 0
                || slot >= container.getContainerSize()
                || !ItemStack.isSameItemSameComponents(requested, container.getItem(slot))) {
                row.putBoolean("done", true);
                continue;
            }
            int share =
                Math.max(
                1,
                (int)
                Math.floor(
                row.getInt("initialCount")
                * Math.min(
                RaidConfig.PLUNDER_SHARE.get(),
                director.raid().definition.limits().plunderShare())));
            if (RaidConfig.PLUNDER_SHARE.get() <= 0) {
                return false;
            }
            ItemStack taken =
                container.removeItem(
                slot,
                Math.min(
                Math.min(remaining, share),
                Math.min(requested.getCount(), container.getItem(slot).getCount())));
            row.putBoolean("done", true);
            if (taken.isEmpty()) {
                continue;
            }
            container.setChanged();
            row.putUUID("looter", mob.getUUID());
            row.put("taken", taken.save(level.registryAccess()));
            cargo.add(taken.save(level.registryAccess()));
            mob.getPersistentData().put(CARGO, cargo);
            director.save();
            mob.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        director.save();
        return false;
    }

    static boolean carrying(Mob mob) {
        return !mob.getPersistentData().getList(CARGO, Tag.TAG_COMPOUND).isEmpty();
    }

    static boolean full(RaidDirector director, Mob mob) {
        return mob.getPersistentData().getList(CARGO, Tag.TAG_COMPOUND).size()
            >= Math.min(RaidConfig.PLUNDER_PER_RAIDER.get(), director.raid().definition.limits().plunderPerRaider());
    }

    static void forfeitCargo(Mob mob) {
        mob.getPersistentData().remove(CARGO);
    }

    static void returnCargo(Mob mob) {
        boolean carrying = carrying(mob);
        for (Tag value : mob.getPersistentData().getList(CARGO, Tag.TAG_COMPOUND)) {
            ItemStack stack = ItemStack.parseOptional(mob.registryAccess(), (CompoundTag) value);
            if (!stack.isEmpty()) {
                mob.spawnAtLocation(stack);
            }
        }
        mob.getPersistentData().remove(CARGO);
        if (carrying) {
            dropped(mob);
        }
    }

    private static void dropped(Mob mob) {
        RaidMembers.director(mob)
            .ifPresent(
            director -> {
                if (director.raid() == null || RaidMembers.raid(mob).filter(director.raid().id::equals).isEmpty()) {
                    return;
                }
                for (Tag value : director.raid().plunder) {
                    CompoundTag row = (CompoundTag) value;
                    if (row.hasUUID("looter") && row.getUUID("looter").equals(mob.getUUID())) {
                        row.putBoolean("dropped", true);
                    }
                }
                director.save();
            });
    }

    @SubscribeEvent
    public static void drops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !RaidMembers.marked(mob)) {
            return;
        }
        for (Tag value : mob.getPersistentData().getList(CARGO, Tag.TAG_COMPOUND)) {
            ItemStack stack = ItemStack.parseOptional(mob.registryAccess(), (CompoundTag) value);
            if (!stack.isEmpty()) {
                event.getDrops().add(new ItemEntity(mob.level(), mob.getX(), mob.getY(), mob.getZ(), stack));
            }
        }
        mob.getPersistentData().remove(CARGO);
        dropped(mob);
        RaidMembers.director(mob).ifPresent(director -> director.returned(mob));
    }
}
