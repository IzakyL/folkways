package io.github.izakyl.folkways.core.runtime;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.resident.api.TaskTools;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.internal.Kit;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
            helper.assertFalse(requested(requests).anyMatch(goods -> Tools.sharedGoods().stream()
                    .anyMatch(family -> Goods.overlap(goods, family))),
                "three residents do not request standing knives, shovels or sickles through any career kit");
        } finally { colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aKitchenShovelCannotBeTakenAsAnOrdinaryBuildingShovel(GameTestHelper helper) {
        ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        helper.assertTrue(Tools.sharedItem(shovel), "the actual native spatula is task-owned");
        helper.assertTrue(Tools.answers(shovel, new ToolNeed.Tagged(TagMod.KITCHEN_SHOVEL)),
            "the shared cooking family accepts its actual tool");
        helper.assertFalse(Tools.answers(shovel, new ToolNeed.Tagged(ItemTags.SHOVELS)),
            "a building task cannot steal the cooking spatula");
        helper.assertTrue(Tools.answers(Items.IRON_SHOVEL.getDefaultInstance(), new ToolNeed.Tagged(ItemTags.SHOVELS)),
            "ordinary building shovels retain native eligibility");
        helper.succeed();
    }

    // A worn, named kitchen knife and a named straw hat: each idle one is cargo, and an active task's loan of it is
    // protected only until that task releases it.
    @GameTest(template = "empty")
    public static void idleKitchenToolsAndClothingAreCargoAndOnlyAnActiveTaskProtectsTheirLoan(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var kits = new Kits();
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        knife.set(DataComponents.CUSTOM_NAME, Component.literal("the shared knife"));
        knife.setDamageValue(17);
        ItemStack hat = new ItemStack(ModItems.STRAW_HAT.get());
        hat.set(DataComponents.CUSTOM_NAME, Component.literal("temporary physical loan"));
        try {
            for (ItemStack item : List.of(knife, hat)) {
                boolean clothing = item == hat;
                var body = PersonBody.RESIDENT.get().create(helper.getLevel());
                UUID loan = UUID.randomUUID();
                body.pack().setItem(0, item.copy());
                try {
                    List<ItemStack> idle = Kit.of(colony, body).cargoIn(body);
                    helper.assertTrue(idle.size() == 1 && ItemStack.matches(idle.getFirst(), item),
                        "an idle " + item + " stays real cargo for shared storage, not a standing career kit");
                    if (clothing) {
                        helper.assertTrue(Kit.of(colony, body).inPack(body).isEmpty(),
                            "an ordinary idle hat is not a hand tool");
                    }
                    TaskTools.keep(loan, body.id(), stack -> ItemStack.isSameItemSameComponents(stack, item));
                    Kit active = Kit.of(colony, body);
                    helper.assertTrue(active.cargoIn(body).isEmpty(),
                        "an active loan cannot be stowed halfway through work");
                    helper.assertTrue(ItemStack.matches(active.inPack(body).getFirst(), item),
                        "the protected loan keeps its exact components");
                    if (clothing) {
                        Equipment.reconcile(colony, body);
                        helper.assertTrue(body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                            "the career kit does not turn a cargo hat into permanent native equipment");
                    }
                    TaskTools.release(loan);
                    helper.assertTrue(ItemStack.matches(Kit.of(colony, body).cargoIn(body).getFirst(), item),
                        "the same physical " + item + " is cargo again after its task releases it");
                    if (clothing) {
                        List<Message> requests = new ArrayList<>();
                        kits.read(colony, body, requests::add);
                        helper.assertFalse(requested(requests)
                                .anyMatch(goods -> Goods.overlap(goods, ItemSpec.of(TagMod.STRAW_HAT))),
                            "the career survey never requests a permanent spare hat");
                    }
                } finally { TaskTools.release(loan); body.discard(); }
            }
        } finally { colony.raze(); }
        helper.succeed();
    }

    // The goods every delivery submitted among `requests` asks for.
    static Stream<ItemSpec> requested(List<Message> requests) {
        return requests.stream().filter(message -> message instanceof Message.Submitted)
            .flatMap(message -> ((Message.Submitted) message).work().nodes().stream())
            .filter(node -> node instanceof Delivery).map(node -> ((Delivery) node).goods().spec());
    }
}
