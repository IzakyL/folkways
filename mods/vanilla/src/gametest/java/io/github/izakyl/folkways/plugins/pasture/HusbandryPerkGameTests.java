package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HusbandryPerkGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(HusbandryPerkGameTests.class); }

    @GameTest(template = "empty")
    public static void fleeceAddsThreeWoolToTheActualShearingOutcome(GameTestHelper helper) {
        var level = helper.getLevel();
        var sheep = EntityType.SHEEP.create(level);
        var body = PersonBody.RESIDENT.get().create(level);
        var at = helper.absolutePos(new BlockPos(2, 2, 2));
        sheep.moveTo(at.getX(), at.getY(), at.getZ());
        var chore = new PenNode.Chore(UUID.randomUUID(),
            new Quarry(sheep.getUUID(), WorkSite.on(sheep), Stances.WHEREVER), () -> { }, new PastDay());
        var node = new ShearNode(chore);
        sheep.getRandom().setSeed(49);
        Outcome ordinary = node.work(level, worker(body, Effects.NONE), sheep);
        helper.assertTrue(ordinary instanceof Outcome.Done, "ordinary shearing succeeds");
        int before = ((Outcome.Done) ordinary).made().stream().mapToInt(ItemStack::getCount).sum();
        sheep.setSheared(false); sheep.getRandom().setSeed(49);
        Effects forcedRoll = Effects.of(List.of(new Effects.Part("test", Modifier.add(PastureContent.EXTRA_WOOL, 3))));
        Outcome bonus = node.work(level, worker(body, forcedRoll), sheep);
        helper.assertTrue(bonus instanceof Outcome.Done, "bonus shearing succeeds");
        int after = ((Outcome.Done) bonus).made().stream().mapToInt(ItemStack::getCount).sum();
        helper.assertValueEqual(after, before + 3, "three extra wool from the fleece specialist");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nurturingShortensRemainingGrowthOnlyOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var cow = EntityType.COW.create(level);
        cow.setAge(-24000);
        var body = PersonBody.RESIDENT.get().create(level);
        var effects = Effects.of(List.of(new Effects.Part("nurture", Modifier.add(PastureContent.BABY_GROWTH, .5))));
        var chore = new PenNode.Chore(UUID.randomUUID(),
            new Quarry(cow.getUUID(), WorkSite.on(cow), Stances.WHEREVER), () -> { }, new PastDay());
        var node = new NurtureNode(chore, Livestock.COW.feed(), body.mob().getUUID());
        Outcome outcome = node.work(level, worker(body, effects), cow);
        helper.assertTrue(outcome instanceof Outcome.Done, "specialist feeds the juvenile");
        helper.assertValueEqual(cow.getAge(), -10800, "normal feeding and specialist bonus shorten remaining growth");
        helper.assertTrue(!(node.work(level, worker(body, effects), cow) instanceof Outcome.Done), "same juvenile cannot be treated twice");
        helper.assertValueEqual(cow.getAge(), -10800, "repeat treatment changes no age");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twinsRespectThePastureTarget(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        var first = EntityType.COW.create(level);
        var second = EntityType.COW.create(level);
        first.moveTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(at));
        second.moveTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(at));
        level.addFreshEntity(first); level.addFreshEntity(second);
        var body = PersonBody.RESIDENT.get().create(level);
        var effects = Effects.of(List.of(new Effects.Part("twins", Modifier.add(PastureContent.TWINS, 1))));
        var pen = new Pen(UUID.randomUUID(), Livestock.COW, 3, true,
            io.github.izakyl.folkways.core.api.terms.WorldPos.of(level, at), java.util.Set.of(at), at.getY(), at.getY());
        BreedingPerks.fed(level, worker(body, effects), first, second, pen);
        BreedingPerks.born(new net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent(first, second, EntityType.COW.create(level)));
        helper.assertValueEqual(level.getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, new net.minecraft.world.phys.AABB(at).inflate(1)).size(), 2,
            "normal child reserves the last target place; no bonus child appears");
        pen = new Pen(pen.zone(), Livestock.COW, 4, true, pen.anchor(), pen.floor(), pen.minY(), pen.maxY());
        BreedingPerks.fed(level, worker(body, effects), first, second, pen);
        BreedingPerks.born(new net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent(first, second, EntityType.COW.create(level)));
        helper.assertValueEqual(level.getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, new net.minecraft.world.phys.AABB(at).inflate(1)).size(), 3,
            "bonus juvenile appears when both children fit the target");
        helper.succeed();
    }

    private static Worker worker(Body body, Effects effects) {
        Container pack = new SimpleContainer(new ItemStack(Items.SHEARS));
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body.mob(); }
            public Container pack() { return pack; }
            public List<ItemStack> supplied() { return List.of(new ItemStack(Items.WHEAT)); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return pack.getItem(0); }
            public void spill(ItemStack stack) { throw new AssertionError("unexpected spill"); }
            public Effects effects() { return effects; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }
}
