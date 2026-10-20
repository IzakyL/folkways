package io.github.izakyl.folkways.cookery.racks;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.KitchenwareRacksBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Claims;
import io.github.izakyl.folkways.core.engine.plan.Cooldowns;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Shown;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.Weaver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RackContainerGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RackContainerGameTests.class);
    }

    @GameTest(template = "empty")
    public static void realRackSlotsKeepOneToolEachAndPreserveItsComponents(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        knife.set(DataComponents.CUSTOM_NAME, Component.literal("Shared kitchen knife"));
        knife.setDamageValue(17);
        ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        helper.assertTrue(Containers.insert(view, knife).isEmpty(), "the named knife goes into the real left slot");
        helper.assertTrue(Containers.insert(view, shovel).isEmpty(), "the shovel goes into the real right slot");
        helper.assertTrue(ItemStack.matches(rack.getItemLeft(), knife), "the BE itself owns the original knife components");
        helper.assertTrue(ItemStack.matches(rack.getItemRight(), shovel), "the BE itself owns the original shovel");
        ItemStack spare = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        helper.assertTrue(ItemStack.matches(Containers.insert(view, spare), spare), "a full rack refuses a third tool");
        helper.assertTrue(ItemStack.matches(Containers.extract(view, stack -> stack.is(knife.getItem()), 64), knife),
            "a real extraction returns one named, worn tool, even with a larger requested count");
        helper.assertTrue(rack.getItemLeft().isEmpty(), "the actual BE left slot empties");
        helper.assertTrue(ItemStack.matches(rack.getItemRight(), shovel), "the other physical slot is unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rackRejectsFoodAndArmorWithoutChangingEitherRealSlot(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        Containers.insert(view, knife);
        CompoundTag before = rack.saveWithFullMetadata(helper.getLevel().registryAccess());
        for (ItemStack refused : new ItemStack[] {
            new ItemStack(Items.BREAD, 3), new ItemStack(ModItems.STRAW_HAT.get())
        }) {
            helper.assertTrue(ItemStack.matches(Containers.insert(view, refused), refused),
                "the adapter obeys the native rack's tools-only restriction");
        }
        helper.assertTrue(before.equals(rack.saveWithFullMetadata(helper.getLevel().registryAccess())),
            "refused supplies did not alter either native rack slot");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullRackFallsBackToOrdinaryStorageWithoutLosingTools(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        SimpleContainer ordinary = new SimpleContainer(2);
        Containers.insert(view, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        Containers.insert(view, new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
        ItemStack spare = new ItemStack(ModItems.SICKLE.get());
        ItemStack left = Containers.insert(view, spare);
        helper.assertTrue(Containers.insert(ordinary, left).isEmpty(), "a full rack leaves the whole tool for ordinary storage");
        helper.assertTrue(ItemStack.matches(ordinary.getItem(0), spare), "the fallback owns exactly the original spare");
        helper.assertValueEqual(rack.getItemLeft().getCount() + rack.getItemRight().getCount()
            + ordinary.getItem(0).getCount(), 3, "both native rack slots and the fallback conserve all three tools");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadedRackKeepsItsToolButAnOldViewCannotTakeOrInsertAnything(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer stale = new RackContainer(rack);
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        knife.set(DataComponents.CUSTOM_NAME, Component.literal("Reloaded knife"));
        knife.setDamageValue(23);
        Containers.insert(stale, knife);
        CompoundTag saved = rack.saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.getLevel().removeBlockEntity(rack.getBlockPos());
        KitchenwareRacksBlockEntity loaded = new KitchenwareRacksBlockEntity(rack.getBlockPos(), rack.getBlockState());
        loaded.loadWithComponents(saved, helper.getLevel().registryAccess());
        helper.getLevel().setBlockEntity(loaded);
        helper.assertTrue(Containers.extract(stale, stack -> true, 1).isEmpty(), "an obsolete view cannot steal the reloaded tool");
        ItemStack extra = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        helper.assertTrue(ItemStack.matches(Containers.insert(stale, extra), extra), "an obsolete view rejects every incoming tool");
        RackContainer current = new RackContainer(loaded);
        helper.assertTrue(ItemStack.matches(Containers.extract(current, stack -> true, 1), knife),
            "the real reloaded BE preserves and releases the original named, worn tool exactly once");
        helper.assertTrue(current.isEmpty(), "the newly loaded rack is physically empty after extraction");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeClicksAndTheContainerOperateOnTheSamePhysicalTool(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setNoAi(true);
        body.moveTo(rack.getBlockPos().getX() + 0.5, rack.getBlockPos().getY(), rack.getBlockPos().getZ() + 1.5);
        helper.getLevel().addFreshEntity(body);
        try {
            ItemStack named = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
            named.set(DataComponents.CUSTOM_NAME, Component.literal("One physical shared tool"));
            named.setDamageValue(11);
            ItemStack offered = named.copy();
            helper.assertTrue(rack.onClick(body, offered, true) && offered.isEmpty(),
                "the original mod interaction consumes one tool into its left slot");
            ItemStack taken = Containers.extract(view, stack -> true, 1);
            helper.assertTrue(ItemStack.matches(taken, named) && rack.getItemLeft().isEmpty(),
                "the adapter removes exactly the physical tool accepted by the native interaction");
            helper.assertTrue(Containers.insert(view, taken).isEmpty(), "the same physical tool goes back through the adapter");
            body.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(rack.onClick(body, ItemStack.EMPTY, true), "the native empty-hand interaction retrieves it again");
            helper.assertTrue(ItemStack.matches(body.getMainHandItem(), named) && view.isEmpty(),
                "the native interaction sees the adapter's real slot and preserves all tool components");
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void registeredRackIsDiscoveredByTheOrdinaryContainerEntryPoint(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        KitchenRacks.register();
        KitchenRacks.register();
        var found = Containers.at(helper.getLevel(), rack.getBlockPos()).orElseThrow();
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        helper.assertTrue(Containers.insert(found, knife).isEmpty(), "ordinary storage discovery reaches the native rack slots");
        helper.assertTrue(ItemStack.matches(rack.getItemLeft(), knife), "discovery does not expose a detached shadow inventory");
        helper.assertValueEqual(found.getMaxStackSize(), 1, "ordinary hauling sees the native one-tool slot capacity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeRackClicksAcceptAnOptionallyTaggedPotLidAndPreserveItsComponents(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setNoAi(true);
        body.moveTo(rack.getBlockPos().getX() + 0.5, rack.getBlockPos().getY(), rack.getBlockPos().getZ() + 1.5);
        try {
            ItemStack lid = new ItemStack(ModItems.STOCKPOT_LID.get());
            lid.set(DataComponents.CUSTOM_NAME, Component.literal("The physical stockpot lid"));
            lid.setDamageValue(17);
            helper.assertTrue(lid.getItem() instanceof ShieldItem
                    && lid.is(Tags.Items.TOOLS),
                "the optional shield child joins the native tools parent tag");
            ItemStack offered = lid.copy();
            helper.assertTrue(rack.onClick(body, offered, true) && offered.isEmpty(),
                "the unchanged native rack interaction accepts and consumes exactly one real lid");
            ItemStack taken = Containers.extract(view, stack -> true, 1);
            helper.assertTrue(ItemStack.matches(taken, lid) && view.isEmpty(), "adapter extraction preserves the same named and worn lid");
            helper.assertTrue(Containers.insert(view, taken).isEmpty(), "the same lid returns through ordinary container storage");
            body.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(rack.onClick(body, ItemStack.EMPTY, true)
                    && ItemStack.matches(body.getMainHandItem(), lid) && view.isEmpty(),
                "native retrieval sees the same adapter slot without a duplicate lid");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryCargoPlanningPrefersTheRealRackForANamedPotLid(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper);
        var level = helper.getLevel();
        for (BlockPos cell : BlockPos.betweenClosed(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(8, 0, 4)))) {
            level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(fixture.source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(fixture.target.cell(), ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState());
        KitchenRacks.register();
        var ordinary = Containers.at(level, fixture.source.cell()).orElseThrow();
        var preferred = Containers.at(level, fixture.target.cell()).orElseThrow();
        ItemStack lid = new ItemStack(ModItems.STOCKPOT_LID.get());
        lid.set(DataComponents.CUSTOM_NAME, Component.literal("Put this lid on the rack")); lid.setDamageValue(23);
        fixture.worker.pack().setItem(0, lid.copy());
        var original = fixture.crew.hands().getFirst();
        var carrying = new Crew(List.of(
            new Crew.Hand(original.who(), original.at(), 6, 6, 1,
                original.licences(), original.tools(), Optional.empty(), List.of(lid.copy()))));
        var source = new Stash(fixture.source);
        var target = new Stash(fixture.target);
        var stances = Stances.of(level,
            Reach.workableCells(level, fixture.target.cell())).orElseThrow();
        var stands = Stands.building()
            .container(source, fixture.stands.around(source)).container(target, Stands.cellsOf(stances)).done();
        var stock = new Stock(List.of(), Map.of(source, 27, target, 2),
            Map.of(target, KitchenRacks.rules(List.of(fixture.target))),
            Map.of(target, Containers.refused(preferred)));
        var planner = new Weaver(
            new Claims(), new Cooldowns());
        planner.stow(original.id());
        planner.know(new Situation(fixture.ways, stock, List.of(), stands), carrying);
        var planned = Shown.view(planner.grow(false));
        helper.assertValueEqual(planned.vertices().size(), 1, "one carried physical lid needs one actual deposit");
        var deposit = planned.vertices().values().iterator().next().node();
        helper.assertTrue(deposit.spec().site().where().equals(fixture.target), "the real tools preference chooses the native rack over an empty ordinary store");
        var feet = deposit.spec().stances().cells().iterator().next().cell();
        fixture.worker.body().moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        helper.assertTrue(Commit.run(level, deposit, fixture.worker)
                instanceof Outcome.Done, "the planned transfer writes into the actual native rack slot");
        helper.assertTrue(ItemStack.matches(preferred.getItem(0), lid) && fixture.worker.pack().isEmpty() && ordinary.isEmpty(),
            "only the real preferred rack owns the named and worn lid after deposit");
        helper.succeed();
    }

    private static KitchenwareRacksBlockEntity rack(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(at, ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState());
        return (KitchenwareRacksBlockEntity) helper.getLevel().getBlockEntity(at);
    }
}
