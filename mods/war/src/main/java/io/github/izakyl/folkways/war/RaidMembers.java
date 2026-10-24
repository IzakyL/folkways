package io.github.izakyl.folkways.war;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.engine.travel.MobTravel;
import io.github.izakyl.folkways.plugins.combat.NativeHostility;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

@EventBusSubscriber(modid = FolkwaysWarMod.ID)
public final class RaidMembers {
    private static final String MARK = "folkways:raider";
    private RaidMembers() { }

    static void mark(Mob mob, UUID colony, UUID raid, String role) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", colony); tag.putUUID("raid", raid); tag.putString("role", role);
        mob.getPersistentData().put(MARK, tag);
    }
    public static boolean marked(Mob mob) { return raid(mob).isPresent(); }
    static Optional<UUID> raid(Mob mob) {
        CompoundTag tag = mob.getPersistentData().getCompound(MARK);
        return tag.hasUUID("raid") ? Optional.of(tag.getUUID("raid")) : Optional.empty();
    }
    static String role(Mob mob) { return mob.getPersistentData().getCompound(MARK).getString("role"); }
    static Optional<RaidDirector> director(Mob mob) {
        CompoundTag tag = mob.getPersistentData().getCompound(MARK);
        if (!(mob.level() instanceof ServerLevel level) || !tag.hasUUID("colony")) { return Optional.empty(); }
        return Colonies.of(level.getServer(), tag.getUUID("colony")).flatMap(RaidDirector::of);
    }

    static net.minecraft.world.entity.SpawnGroupData prepareSpawn(Mob mob, ServerLevel level) {
        var group = mob instanceof Zombie ? new Zombie.ZombieGroupData(Zombie.getSpawnAsBabyOdds(mob.getRandom()), false) : null;
        var initialized = mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), net.minecraft.world.entity.MobSpawnType.EVENT, group);
        if (mob instanceof Zombie zombie) { suppressReinforcements(zombie); }
        return initialized;
    }

    private static void suppressReinforcements(Zombie zombie) {
        var chance = zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SPAWN_REINFORCEMENTS_CHANCE);
        chance.removeModifiers(); chance.setBaseValue(0);
    }

    @SubscribeEvent
    public static void converting(net.neoforged.neoforge.event.entity.living.LivingConversionEvent.Pre event) {
        if (event.getEntity() instanceof Mob mob && marked(mob)) {
            event.setConversionTimer(200); event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob) || raid(mob).isEmpty()) { return; }
        if (mob instanceof Raider raider) { raider.setCanJoinRaid(false); }
        if (mob instanceof Zombie zombie) {
            zombie.setCanBreakDoors(false); suppressReinforcements(zombie);
            // Older saves can still contain a jockey; its chicken has no recorded ownership.
            if (zombie.getVehicle() instanceof net.minecraft.world.entity.animal.Chicken) { zombie.stopRiding(); }
        }
        mob.goalSelector.getAvailableGoals().stream().map(goal -> goal.getGoal())
            .filter(goal -> goal instanceof net.minecraft.world.entity.ai.goal.BreakDoorGoal || goal instanceof net.minecraft.world.entity.ai.goal.RemoveBlockGoal)
            .toList().forEach(mob.goalSelector::removeGoal);
        NativeHostility.restrictResidents(mob, target -> {
            CompoundTag tag = mob.getPersistentData().getCompound(MARK);
            return Bodies.of(target).flatMap(body -> body.colonyId()).filter(tag.getUUID("colony")::equals).isPresent()
                && director(mob).filter(director -> director.owns(mob) && !director.raid().paused).isPresent();
        });
        if (mob.goalSelector.getAvailableGoals().stream().noneMatch(goal -> goal.getGoal() instanceof Approach)) {
            mob.goalSelector.addGoal(5, new Approach(mob));
            mob.goalSelector.addGoal(1, new RaidBreachGoal(mob));
            mob.goalSelector.addGoal(2, new RaidIgniteGoal(mob));
            mob.goalSelector.addGoal(1, new RaidLootGoal(mob));
        }
    }

    @SubscribeEvent
    public static void ticking(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel) || !marked(mob)) { return; }
        var director = director(mob);
        if (director.filter(d -> d.owns(mob)).isPresent()) {
            mob.setNoAi(director.get().raid().paused);
            if (director.get().raid().paused) { event.setCanceled(true); }
            return;
        }
        if (director.isEmpty() || director.get().raid() == null || director.get().raid().phase == RaidInstance.Phase.CANCELLED) { RaidPlunder.returnCargo(mob); }
        mob.discard();
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob && raid(mob).isPresent()) { director(mob).ifPresent(director -> director.died(mob.getUUID())); }
    }

    private static final class Approach extends Goal {
        private final Mob mob;
        Approach(Mob mob) { this.mob = mob; setFlags(EnumSet.of(Flag.MOVE)); }
        @Override
        public boolean canUse() {
            var director = director(mob);
            if (director.isEmpty() || !director.get().owns(mob)) { mob.discard(); return false; }
            return !director.get().raid().paused && mob.getTarget() == null;
        }
        @Override
        public void tick() {
            if (mob.tickCount % 20 == 0) { director(mob).ifPresent(director -> MobTravel.approach(mob, director.raid().target, 1)); }
        }
        @Override
        public void stop() { mob.getNavigation().stop(); }
    }
}
