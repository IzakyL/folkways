package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.work.api.Manifest;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.work.internal.LaborIncident;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TransferPresentationGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TransferPresentationGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void plannedTransfersNameTheChosenAlternative(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        ItemSpec food = food();
        ItemSpec chosen = food.anyOf().stream().skip(1).findFirst().orElseThrow();
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM
            .get(chosen.item().orElseThrow()), 11);
        var barrels = f.barrels();
        barrels.source().setItem(0, stack.copy());
        var task = new PlanningFixture.Owed(new WorkSite.AtBlock(f.target), Stances.at(f.target), food, 11);
        var stock = new Stock(List.of(new Stock.Holding(new Stash(f.source), stack)),
            Map.of(new Stash(f.source), 26, new Stash(f.target), 27));
        var solver = new PlanningFixture.Rounds();
        try {
            var planned = solver.solve(f.situation(stock, List.of()), f.crew, task.goals(), List.of(), 0);
            var tour = planned.schedule().tours().get(f.worker.resident().id());
            helper.assertTrue(tour != null && tour.nodes().size() == 2, "fetch and stow must both be planned");
            for (UUID id : tour.nodes()) {
                var transfer = (TransferNode) planned.weave().vertices().get(id).node();
                String name = stack.getItem().getDescriptionId();
                helper.assertValueEqual(transfer.spec().about(), Optional.of(name),
                    "the planner's selected alternative names both transfers");
                helper.assertValueEqual(subject(ColonyLabor.job(transfer.spec())), name,
                    "debug cards and chat reports name the selected goods");
                helper.assertValueEqual(subject(LaborIncident.job(transfer)), name,
                    "incident chat reports use the same name");
                helper.assertValueEqual(transfer.wares().used().getFirst().item(), chosen.item().orElseThrow(),
                    "the doing icon agrees with the selected name");
            }
        } finally {
            solver.closed();
            f.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 20)
    public static void unresolvedGoodsStayGenericAndFollowLaterBindings(GameTestHelper helper) {
        ItemSpec food = food();
        String foodName = Tags.getTagTranslationKey(Tags.Items.FOODS);
        helper.assertValueEqual(Doings.about(food), Optional.of(foodName), "food alternatives share a category");
        helper.assertValueEqual(Doings.about(ItemSpec.anyOf(List.of(ItemSpec.of(Items.BREAD),
            ItemSpec.of(Items.IRON_INGOT)))), Optional.of("folkways.goods.any"), "mixed goods have a generic name");
        helper.assertValueEqual(Doings.about(ItemSpec.anyOf(List.of(ItemSpec.of(Items.BREAD)))),
            Optional.of(Items.BREAD.getDescriptionId()), "one alternative keeps its item name");
        helper.assertTrue(Doings.item(food).isEmpty(), "unresolved alternatives cannot claim the first item's icon");
        var at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        for (ItemSpec need : List.of(food, ItemSpec.of(Tags.Items.FOODS))) {
            var transfer = TransferNode.delivering(UUID.randomUUID(), new Stash(at), new Need(need, 11),
                Stances.WHEREVER, count -> { });
            helper.assertValueEqual(transfer.spec().about(), Optional.of(foodName), "unresolved stowing is generic");
            helper.assertValueEqual(subject(ColonyLabor.job(transfer.spec())), foodName,
                "unresolved debug names stay generic too");
            var pending = new Manifest(List.of(), List.of(new Need(need, 11)));
            var resolved = transfer.resolved(new Manifest(List.of(), List.of(), List.of(pending)));
            helper.assertValueEqual(resolved.spec().about(), Optional.of(foodName), "pending cargo stays generic");
            ItemSpec chosen = food.anyOf().stream().skip(1).findFirst().orElseThrow();
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM
                .get(chosen.item().orElseThrow()), 11);
            pending.bind(List.of(stack));
            helper.assertValueEqual(resolved.spec().about(), Optional.of(stack.getItem().getDescriptionId()),
                "a shared manifest bound after resolution updates the name");
            helper.assertValueEqual(resolved.wares().used().getFirst().item(), chosen.item().orElseThrow(),
                "late binding updates the icon too");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void executingAndSyncedDoingUsesResolvedGoods(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        ItemSpec food = food();
        ItemSpec chosen = food.anyOf().stream().skip(1).findFirst().orElseThrow();
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM
            .get(chosen.item().orElseThrow()), 11);
        var unresolved = new TransferNode(UUID.randomUUID(), TransferNode.Endpoint.pack(),
            TransferNode.Endpoint.store(new Stash(at)), new Manifest(List.of(), List.of(new Need(food, 11))),
            Stances.WHEREVER, new WorkSite.AtBlock(at));
        helper.assertValueEqual(unresolved.spec().about(), Doings.about(food), "unplanned transfer stays generic");
        var transfer = unresolved.resolved(new Manifest(List.of(stack), List.of()));
        var body = PersonBody.RESIDENT.get().create(level);
        var labor = new ColonyLabor(new ColonyData(), level.dimension());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            body.setPos(at.cell().getX() + 0.5D, at.cell().getY(), at.cell().getZ() + 0.5D);
            body.pack().setItem(0, stack.copy());
            PlanningFixture.apply(labor, level, List.of(), Shown.delta(transfer));
            var runner = new BodyRunner(body.getUUID(), labor.execution());
            runner.follow(List.of(transfer.id()));
            labor.tick(runner, level, body);
            labor.tick(runner, level, body);
            Doing doing = body.doing().orElseThrow();
            helper.assertValueEqual(doing.what(), Doings.STOWING, "the body starts stowing");
            helper.assertValueEqual(doing.about(), Optional.of(stack.getItem().getDescriptionId()),
                "the actual executing doing names the selected alternative");
            doing.encode(buffer);
            Doing synced = Doing.decode(buffer);
            helper.assertValueEqual(synced, doing, "the doing network format preserves the resolved name and icon");
            var name = (TranslatableContents) io.github.izakyl.folkways.core.front.api.notice.Doings
                .name(synced).getContents();
            helper.assertValueEqual(subject((Component) name.getArgs()[0]), stack.getItem().getDescriptionId(),
                "client doing text names the selected goods");
        } finally {
            buffer.release();
            labor.close();
            body.discard();
        }
        helper.succeed();
    }

    private static ItemSpec food() {
        return ItemSpec.anyOf(List.of(ItemSpec.of(Items.COOKED_PORKCHOP), ItemSpec.of(Items.BREAD)));
    }

    private static String subject(Component job) {
        var contents = (TranslatableContents) job.getContents();
        return ((TranslatableContents) ((Component) contents.getArgs()[0]).getContents()).getKey();
    }
}
