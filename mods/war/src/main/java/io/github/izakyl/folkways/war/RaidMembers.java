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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob) || raid(mob).isEmpty()) { return; }
        if (mob instanceof Raider raider) { raider.setCanJoinRaid(false); }
        if (mob instanceof Zombie zombie) { zombie.setCanBreakDoors(false); }
        NativeHostility.restrictResidents(mob, target -> {
            CompoundTag tag = mob.getPersistentData().getCompound(MARK);
            return Bodies.of(target).flatMap(body -> body.colonyId()).filter(tag.getUUID("colony")::equals).isPresent()
                && director(mob).filter(director -> director.owns(mob) && !director.raid().paused).isPresent();
        });
        if (mob.goalSelector.getAvailableGoals().stream().noneMatch(goal -> goal.getGoal() instanceof Approach)) {
            mob.goalSelector.addGoal(5, new Approach(mob));
            mob.goalSelector.addGoal(1, new RaidBreachGoal(mob));
            mob.goalSelector.addGoal(2, new RaidIgniteGoal(mob));
        }
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
