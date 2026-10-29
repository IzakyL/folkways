package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.combat.weapon.*;
import io.github.izakyl.folkways.plugins.person.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** Each asynchronous fixture owns its entities, colony, listeners and loan through test completion. */
final class EpicFightTestScene implements AutoCloseable, GameTestListener {
    final ServerLevel level;
    final Colony colony;
    final ResidentEntity resident;
    final Zombie zombie;
    final Cow cow;
    final Worker worker;
    final ItemStack sword;
    final List<Object> listeners = new ArrayList<>();
    boolean closed;

    EpicFightTestScene(GameTestHelper helper) {
        level = helper.getLevel();
        for (int x = 0; x < 7; x++) for (int z = 0; z < 4; z++)
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        colony = Colonies.mint(level.getServer());
        resident = PersonBody.RESIDENT.get().create(level);
        resident.setNoAi(true);
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        resident.moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        level.addFreshEntity(resident);
        colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(9);
        resident.pack().setItem(0, sword);
        profile(Items.IRON_SWORD);
        zombie = EntityType.ZOMBIE.create(level);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 12000));
        zombie.moveTo(resident.getX() + 1.2, resident.getY(), resident.getZ());
        cow = EntityType.COW.create(level);
        cow.setNoAi(true);
        cow.moveTo(resident.getX(), resident.getY(), resident.getZ() + 2);
        level.addFreshEntity(zombie);
        level.addFreshEntity(cow);
        worker = worker(resident.pack(), resident.resident());
        helper.testInfo.addListener(this);
    }

    void profile(Item item) {
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        LoadoutProfile profile = profiles.create("native EF fixture");
        profiles.add(profile.id(), LoadoutProfile.Entry.weapon(ItemSpec.of(item)));
        profiles.assign(List.of(resident.id()), Optional.of(profile.id()));
    }

    Worker worker(Container pack, Resident identity) {
        return new Worker() {
            public Resident resident() { return identity; }
            public Mob body() { return resident; }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { resident.spawnAtLocation(stack); }
            public Effects effects() { return resident.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }

    WeaponSession open() { return EpicFightWeaponAdapter.INSTANCE.open(worker, resident.pack().getItem(0)).orElseThrow(); }
    void damageListener(Consumer<LivingDamageEvent.Post> listener) {
        listeners.add(listener);
        NeoForge.EVENT_BUS.addListener(listener);
    }
    public void testStructureLoaded(GameTestInfo info) { }
    public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
    public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
    public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    public void close() {
        if (closed) return;
        closed = true;
        for (Object listener : listeners) NeoForge.EVENT_BUS.unregister(listener);
        resident.stopWeaponSession();
        resident.discard(); zombie.discard(); cow.discard(); colony.raze();
    }

    static final class Control implements DefenseControl {
        DefenseControl.Order order;
        public Optional<? extends DefenseControl.Order> current(UUID resident) { return Optional.ofNullable(order); }
    }
    static final class Order implements DefenseControl.Order {
        public boolean guarding() { return false; }
        public boolean defensiveOnly() { return false; }
        public io.github.izakyl.folkways.core.api.terms.WorldPos destination() { return null; }
        public void engaging() { }
    }
}
