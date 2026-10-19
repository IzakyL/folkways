package io.github.izakyl.folkways.core.engine.labor;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryKitGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryKitGameTests.class); }

    @GameTest(template = "empty")
    public static void theCareerKitNeverRequestsAKitchenKnifeForEveryResident(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var kits = new Kits();
        List<Message> requests = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                var body = PersonBody.RESIDENT.get().create(helper.getLevel());
                try { kits.read(colony, body, requests::add); }
                finally { body.discard(); }
            }
            helper.assertFalse(requests.stream().filter(message -> message instanceof Message.Submitted)
                .map(message -> (Message.Submitted) message).flatMap(message -> message.work().nodes().stream())
                .filter(node -> node instanceof Delivery).map(node -> (Delivery) node)
                .anyMatch(delivery -> io.github.izakyl.folkways.core.api.work.Tools.sharedGoods().stream()
                    .anyMatch(family -> Goods.overlap(delivery.goods().spec(), family))),
                "three residents do not request standing knives, shovels or sickles through any career kit");
        } finally { colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aKitchenShovelCannotBeTakenAsAnOrdinaryBuildingShovel(GameTestHelper helper) {
        ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        helper.assertTrue(io.github.izakyl.folkways.core.api.work.Tools.sharedItem(shovel), "the actual native spatula is task-owned");
        helper.assertTrue(io.github.izakyl.folkways.core.api.work.Tools.answers(shovel,
            new io.github.izakyl.folkways.core.api.work.ToolNeed.Tagged(TagMod.KITCHEN_SHOVEL)), "the shared cooking family accepts its actual tool");
        helper.assertFalse(io.github.izakyl.folkways.core.api.work.Tools.answers(shovel,
            new io.github.izakyl.folkways.core.api.work.ToolNeed.Tagged(net.minecraft.tags.ItemTags.SHOVELS)), "a building task cannot steal the cooking spatula");
        helper.assertTrue(io.github.izakyl.folkways.core.api.work.Tools.answers(net.minecraft.world.item.Items.IRON_SHOVEL.getDefaultInstance(),
            new io.github.izakyl.folkways.core.api.work.ToolNeed.Tagged(net.minecraft.tags.ItemTags.SHOVELS)), "ordinary building shovels retain native eligibility");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBorrowedToolIsProtectedOnlyUntilItsTaskReleasesIt(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        UUID task = UUID.randomUUID();
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        knife.set(DataComponents.CUSTOM_NAME, Component.literal("the shared knife"));
        knife.setDamageValue(17);
        body.pack().setItem(0, knife);
        try {
            helper.assertValueEqual(Kit.of(colony, body).cargoIn(body).size(), 1, "idle kitchen tools can be returned as cargo");
            TaskTools.keep(task, body.id(), stack -> ItemStack.isSameItemSameComponents(stack, knife));
            Kit active = Kit.of(colony, body);
            helper.assertTrue(active.cargoIn(body).isEmpty(), "an active loan cannot be stowed halfway through work");
            helper.assertTrue(ItemStack.matches(active.inPack(body).getFirst(), knife), "Survey's tools retain the actual loan components");
            TaskTools.release(task);
            helper.assertTrue(ItemStack.matches(Kit.of(colony, body).cargoIn(body).getFirst(), knife),
                "the same named and worn physical tool is exposed for return after release");
        } finally { TaskTools.release(task); body.discard(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unusedClothingIsCargoAndOnlyAnActiveTaskProtectsItsPhysicalLoan(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var kits = new Kits();
        UUID loan = UUID.randomUUID();
        ItemStack hat = new ItemStack(ModItems.STRAW_HAT.get());
        hat.set(DataComponents.CUSTOM_NAME, Component.literal("temporary physical loan"));
        body.pack().setItem(0, hat);
        try {
            helper.assertTrue(ItemStack.matches(Kit.of(colony, body).cargoIn(body).getFirst(), hat),
                "unused clothing stays real cargo for shared storage, not a standing career kit");
            helper.assertTrue(Kit.of(colony, body).inPack(body).isEmpty(), "an ordinary idle hat is not a hand tool");
            TaskTools.keep(loan, body.id(), stack -> ItemStack.isSameItemSameComponents(stack, hat));
            helper.assertTrue(Kit.of(colony, body).cargoIn(body).isEmpty(), "only an active physical task loan is protected from ordinary unloading");
            helper.assertTrue(ItemStack.matches(Kit.of(colony, body).inPack(body).getFirst(), hat), "the protected loan keeps its exact components");
            Equipment.reconcile(colony, body);
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the career kit does not turn a cargo hat into permanent native equipment");
            TaskTools.release(loan);
            helper.assertTrue(ItemStack.matches(Kit.of(colony, body).cargoIn(body).getFirst(), hat), "after task release the one hat is cargo again");
            List<Message> requests = new ArrayList<>(); kits.read(colony, body, requests::add);
            helper.assertFalse(requests.stream().filter(message -> message instanceof Message.Submitted)
                .map(message -> (Message.Submitted) message).flatMap(message -> message.work().nodes().stream())
                .filter(node -> node instanceof Delivery).map(node -> (Delivery) node)
                .anyMatch(delivery -> Goods.overlap(delivery.goods().spec(), ItemSpec.of(TagMod.STRAW_HAT))),
                "the career survey never requests a permanent spare hat");
        } finally { TaskTools.release(loan); body.discard(); colony.raze(); }
        helper.succeed();
    }
}