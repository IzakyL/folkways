package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KitSupplyPolicyGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "kit_supply_policy");
    private static final String MONITORED = "folkways_test:policy_monitored";
    private static final String PAUSED = "folkways_test:policy_paused";
    private static final String READS = "folkways_test:policy_reads";

    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(KitSupplyPolicyGameTests.class); }

    @SubscribeEvent public static void declare(Declaring event) {
        KitRules.supplyPolicy(OWNER, (colony, body) -> {
            if (!body.mob().getPersistentData().getBoolean(MONITORED)) { return true; }
            if (!(body.mob().level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
                throw new IllegalStateException("equipment policy was queried outside the server snapshot");
            }
            var data = body.mob().getPersistentData();
            data.putInt(READS, data.getInt(READS) + 1);
            return !data.getBoolean(PAUSED);
        });
    }

    private static final Ways REACHABLE = new Ways() {
        public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) { return Faring.yes(Journey.afoot(0, goals)); }
        public Faring anyoneReaches(Set<WorldPos> goals) { return Faring.yes(Journey.afoot(0, goals)); }
    };

    @GameTest(template = "empty_large")
    public static void aPolicyChangeWithdrawsAndRestoresRealEquipmentClaimsWithoutAnInventoryChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        var body = PersonBody.RESIDENT.get().create(level);
        var solver = new Solving();
        try {
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 8; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            }
            BlockPos chest = helper.absolutePos(new BlockPos(5, 1, 3));
            level.setBlockAndUpdate(chest, Blocks.BARREL.defaultBlockState());
            var store = Containers.at(level, chest).orElseThrow();
            ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
            tool.setDamageValue(17); tool.set(DataComponents.CUSTOM_NAME, Component.literal("the one warehouse tool"));
            store.setItem(0, tool.copy());
            WorldPos source = WorldPos.of(level, chest);
            colony.stores(OWNER, level.dimension(), List.of(source));
            body.moveTo(chest.getX() - 2.5, chest.getY(), chest.getZ() + .5);
            colony.hold(PersonBody.ID, Held.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id()); level.addFreshEntity(body);
            body.getPersistentData().putBoolean(MONITORED, true);
            var selected = Vocations.all().stream().filter(trade -> body.licences().of(trade).isPresent())
                .filter(trade -> trade.kit().stream().anyMatch(goods -> Goods.matches(tool, goods)))
                .findFirst().orElseThrow();
            for (var trade : Vocations.all()) {
                if (trade != selected && !trade.id().equals(Vocations.HAULING)) {
                    body.licences().trade(trade).ifPresent(licence -> body.licences().allow(licence, false));
                }
            }
            long licenceRevision = body.licences().revision();
            var survey = new Survey(); var kits = new Kits();
            List<Message> messages = new ArrayList<>();
            Map<UUID, Body> present = Map.of(body.id(), body);
            int[] rounds = {0};
            Runnable read = () -> {
                int before = body.getPersistentData().getInt(READS);
                if (survey.answer(level, data, present, (seen, supply) -> kits.read(colony, seen, supply, messages::add))) {
                    messages.add(Message.ANSWERED);
                }
                rounds[0]++;
                helper.assertValueEqual(body.getPersistentData().getInt(READS), before + 1,
                    "each server survey queries the policy once and passes its snapshot to Kits");
            };
            survey.asked(new Asks(true, Set.of(), false, Set.of(body.id()), Long.MAX_VALUE));
            read.run();
            var planned = solver.handle(survey.known(REACHABLE, List.of()), List.copyOf(messages), 0);
            List<String> trace = new ArrayList<>();
            // The real survey may spend its first budget on the hand before it reads storage. Drive the
            // solver's actual feedback, rather than assuming one partial snapshot is the entire colony.
            for (int pass = 0; pass < 16 && !reserved(solver, source); pass++) {
                trace.add("pass=" + pass + ", messages=" + messages + ", stock="
                    + survey.known(REACHABLE, List.of()).world().stock().available(Goods.specOf(tool))
                    + ", diagnosis=" + planned.diagnosis() + ", asks=" + planned.asks());
                survey.asked(planned.asks()); messages.clear(); read.run();
                planned = solver.handle(survey.known(REACHABLE, List.of()), List.copyOf(messages), pass + 1);
            }
            helper.assertTrue(reserved(solver, source), "the ordinary equipment request really reserves the one physical source tool; " + trace);
            survey.asked(Asks.NONE); messages.clear();

            body.getPersistentData().putBoolean(PAUSED, true);
            read.run();
            helper.assertTrue(messages.stream().anyMatch(Message.Withdrawn.class::isInstance), "changing only the policy withdraws existing equipment work");
            solver.handle(survey.known(REACHABLE, List.of()), List.copyOf(messages), 32);
            helper.assertTrue(!reserved(solver, source), "the planner releases the real source claim after control changes");
            helper.assertTrue(ItemStack.matches(store.getItem(0), tool) && body.pack().contents().isEmpty(),
                "withdrawing a request leaves actual components, durability and ownership intact");
            messages.clear();

            body.getPersistentData().putBoolean(PAUSED, false);
            read.run();
            helper.assertTrue(messages.stream().anyMatch(Message.Submitted.class::isInstance), "releasing control recreates the ordinary equipment request");
            solver.handle(survey.known(REACHABLE, List.of()), List.copyOf(messages), 33);
            helper.assertTrue(reserved(solver, source), "the same physical stock can be reserved again after the policy permits it");
            helper.assertValueEqual(body.licences().revision(), licenceRevision, "the policy transition never changes profession permissions");
            helper.assertValueEqual(body.getPersistentData().getInt(READS), rounds[0], "policy queries follow only actual server survey rounds");
            helper.assertTrue(ItemStack.matches(store.getItem(0), tool) && body.pack().contents().isEmpty(), "policy changes neither draw nor duplicate actual stock");
        } finally { solver.closed(); body.discard(); colony.raze(); }
        helper.succeed();
    }

    private static boolean reserved(Solving solver, WorldPos source) {
        return InputReservations.read(solver.plan(), id -> NodeState.PENDING, id -> null).stream()
            .anyMatch(claim -> claim.store().filter(source::equals).isPresent() && claim.count() == 1);
    }
}
