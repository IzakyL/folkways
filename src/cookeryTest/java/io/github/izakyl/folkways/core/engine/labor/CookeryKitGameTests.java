package io.github.izakyl.folkways.core.engine.labor;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.plan.Claims;
import io.github.izakyl.folkways.core.engine.plan.Cooldowns;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.Planner;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.Why;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryKitGameTests {
    private static final Ways WAYS = new Ways() {
        public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
            return Faring.yes(Journey.afoot(1, goals));
        }
        public Faring anyoneReaches(Set<WorldPos> goals) {
            return Faring.yes(Journey.afoot(1, goals));
        }
    };

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryKitGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aStoredKnifeIsRequestedFetchedRecognizedAndUsedByTheRealBoard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos boardCell = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos chestCell = boardCell.offset(-3, 0, 0);
        for (BlockPos floor : BlockPos.betweenClosed(boardCell.offset(-5, -1, -4), boardCell.offset(4, -1, 4))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(floor.above(3), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(chestCell, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(boardCell, ModBlocks.CHOPPING_BOARD.get().defaultBlockState());
        Container source = Containers.at(level, chestCell).orElseThrow();
        source.setItem(0, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        source.setItem(1, new ItemStack(Items.CHICKEN));
        var body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(boardCell.getX() + 0.5, boardCell.getY(), boardCell.getZ() + 1.5);
        ColonyData colony = new ColonyData();
        colony.works().stores(CookeryContent.ID, level.dimension(), List.of(WorldPos.of(level, chestCell)));
        Survey survey = new Survey();
        KitchenBookings bookings = new KitchenBookings();
        Workshop board = ChoppingBoardWorkshops.freeze(UUID.randomUUID(), colony.colonyId(), level,
            WorldPos.of(level, boardCell), Stances.at(level, boardCell.south()), bookings, () -> { }).orElseThrow();
        ItemSpec meat = Goods.specOf(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
        Grown order = Grown.of(Delivery.to(UUID.randomUUID(), CookeryContent.ID,
            WorkSite.at(level, chestCell), Stances.WHEREVER, meat, 3, count -> { }));
        Planner before = new Planner(new Claims(), new Cooldowns());
        Planner supply = new Planner(new Claims(), new Cooldowns());
        Planner production = new Planner(new Claims(), new Cooldowns());
        try {
            helper.assertTrue(body.pack().isEmpty(), "the real knife starts only in the store, never injected into a pack");
            Known known = read(survey, level, colony, body, board);
            var blocked = before.handle(known, List.of(new Message.Submitted(CookeryContent.ID, order)), 0, Map.of());
            helper.assertTrue(blocked.shortfalls().stream().anyMatch(shortfall -> shortfall.why() instanceof Why.NoHand),
                "a resident without a carried knife cannot cut food yet");
            List<Message> requests = new ArrayList<>();
            Kits kits = new Kits();
            kits.read(colony.works(), body, requests::add);
            helper.assertValueEqual(requests.stream().filter(message -> message instanceof Message.Submitted submitted
                && submitted.work().nodes().stream().anyMatch(node -> node instanceof Delivery delivery
                    && Goods.matches(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()), delivery.goods().spec()))).count(), 1L,
                "the actual equipment reader requests a kitchen knife from colony stock");
            var equipped = supply.handle(known, requests, 1, Map.of());
            helper.assertTrue(!equipped.delta().put().isEmpty(), "the actual planner grows the stored knife's delivery");
            execute(helper, body, survey, equipped);
            helper.assertValueEqual(Goods.countIn(source, ItemSpec.of(TagMod.KITCHEN_KNIFE)), 0L,
                "the original physical knife leaves the store");
            helper.assertValueEqual(Goods.countIn(body.pack(), ItemSpec.of(TagMod.KITCHEN_KNIFE)), 1L,
                "real hauling and receipt put exactly that knife in the resident's pack");
            known = read(survey, level, colony, body, board);
            helper.assertTrue(known.hands().getFirst().tools().stream().anyMatch(stack -> stack.is(TagMod.KITCHEN_KNIFE)),
                "Survey reports the newly fetched knife as equipment instead of cargo");
            var made = production.handle(known, List.of(new Message.Submitted(CookeryContent.ID, order)), 2, Map.of());
            Node cutting = made.delta().put().values().stream().map(Vertex::node)
                .filter(node -> node.spec().owner().equals(CookeryContent.ID)).findFirst().orElseThrow();
            helper.assertTrue(Tools.equipped(body.kind().id(), cutting.spec(), known.hands().getFirst().tools()),
                "the actual board offer now fits the surveyed worker");
            execute(helper, body, survey, made);
            helper.assertValueEqual(Goods.countIn(source, meat) + Goods.countIn(body.pack(), meat), 3L,
                "the real planned board produces exactly three meat pieces");
            helper.assertValueEqual(Goods.countIn(source, Goods.specOf(new ItemStack(Items.BONE)))
                + Goods.countIn(body.pack(), Goods.specOf(new ItemStack(Items.BONE))), 1L,
                "the real board preserves its secondary product exactly once");
            helper.assertValueEqual(Goods.countIn(source, Goods.specOf(new ItemStack(Items.CHICKEN)))
                + Goods.countIn(body.pack(), Goods.specOf(new ItemStack(Items.CHICKEN))), 0L,
                "the original chicken is spent exactly once");
            helper.assertValueEqual(Goods.countIn(body.pack(), ItemSpec.of(TagMod.KITCHEN_KNIFE)), 1L,
                "the reusable knife is still carried after real cuts");
            helper.assertTrue(((ChoppingBoardBlockEntity) level.getBlockEntity(boardCell)).getCurrentCutStack().isEmpty(),
                "the real board finishes empty");
            requests.clear();
            kits.read(colony.works(), body, requests::add);
            helper.assertTrue(requests.stream().noneMatch(message -> message instanceof Message.Submitted submitted
                && submitted.work().nodes().stream().anyMatch(node -> node instanceof Delivery delivery
                    && Goods.matches(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()), delivery.goods().spec()))),
                "carrying the knife does not request a second copy");
        } finally {
            before.closed();
            supply.closed();
            production.closed();
            body.discard();
            io.github.izakyl.folkways.front.engine.colony.ColonyFront.forget(colony.colonyId());
        }
        helper.succeed();
    }

    private static Known read(Survey survey, ServerLevel level, ColonyData colony, Body body, Workshop board) {
        survey.asked(new Asks(true, Set.of(), true, Set.of(), Long.MAX_VALUE));
        survey.answer(level, colony, Map.of(body.id(), body), seen -> { });
        return survey.known(WAYS, List.of(board));
    }

    // Wind-up and walking are exercised by the black-box twins. Here the real plan's ordered nodes commit with
    // their actual placements, so no helper inserts tools or ingredients into a resident's pack.
    private static void execute(GameTestHelper helper, Body body, Survey survey, Planner.Woven woven) {
        ServerLevel level = helper.getLevel();
        Set<UUID> done = new LinkedHashSet<>();
        while (done.size() < woven.delta().put().size()) {
            Vertex vertex = woven.delta().put().values().stream().filter(candidate -> !done.contains(candidate.id()))
                .filter(candidate -> woven.delta().linked().stream().filter(link -> link.to().equals(candidate.id()))
                    .allMatch(link -> done.contains(link.from()))).findFirst().orElseThrow();
            Node node = vertex.node();
            node.spec().stances().cells().stream().findFirst().ifPresent(stand ->
                body.mob().moveTo(stand.cell().getX() + 0.5, stand.cell().getY(), stand.cell().getZ() + 0.5));
            Hands who = new Hands(body, survey.stands(), vertex.plan(), drop -> {
                throw new AssertionError("unexpected dropped goods: " + drop.getItem());
            }, List::of);
            helper.assertTrue(node.planned(level).isEmpty(), "the actual planned node accepts its placement");
            if (node.spec().workload() instanceof Workload.Within within) {
                boolean left = false;
                for (int step = 0; step < 32 && !left; step++) {
                    var turn = within.next(level, who);
                    if (turn instanceof Workload.Within.Turn.Piece piece) {
                        settle(helper, level, piece.node(), who);
                        within.ended(level, piece.node(), Ending.DONE);
                    } else {
                        helper.assertTrue(turn instanceof Workload.Within.Turn.Leave, "the real board never stalls");
                        left = true;
                    }
                }
                helper.assertTrue(left, "the real board finishes within its declared cuts");
                helper.assertTrue(node.commit(level, who) instanceof Outcome.Done, "the parent finishes after its pieces");
            } else {
                settle(helper, level, node, who);
            }
            node.ended(level, Ending.DONE);
            done.add(vertex.id());
        }
    }

    private static void settle(GameTestHelper helper, ServerLevel level, Node node, Hands who) {
        Outcome outcome = Commit.run(level, node, who);
        helper.assertTrue(outcome instanceof Outcome.Done, "the real commit succeeds: " + outcome);
        Stowing.put(who, who.placement(), ((Outcome.Done) outcome).made());
    }
}
