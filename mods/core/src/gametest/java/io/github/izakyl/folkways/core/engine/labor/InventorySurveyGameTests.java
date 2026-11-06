package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.inventory.InventorySnapshot.State;
import io.github.izakyl.folkways.core.api.inventory.InventorySnapshot;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.inventory.InventoryObservations;
import io.github.izakyl.folkways.core.engine.plan.Manifest;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
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
public final class InventorySurveyGameTests {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_test", "survey_index");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(InventorySurveyGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aReservedResidentRemainsObservedButCannotJoinTheCrew(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        body.pack().setItem(4, new ItemStack(Items.IRON_INGOT, 6));
        body.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        ColonyData data = new ColonyData();
        Survey survey = new Survey();
        survey.reserved(Set.of(body.id()));
        try {
            survey.answer(level, data, Map.of(body.id(), body), seen -> { });
            InventoryObservations.work(data.inventory(), level.getServer(), 100);
            survey.answer(level, data, Map.of(body.id(), body), seen -> { });
            var observed = data.works().inventory().snapshot().residents().get(body.id());
            helper.assertTrue(observed.state() == State.OBSERVED && observed.pack().get(4).stackCopy().getCount() == 6
                && observed.equipment().get(EquipmentSlot.OFFHAND).stackCopy().is(Items.SHIELD),
                "labor reservation cannot hide physical pack or native equipment");
            helper.assertTrue(survey.hands().isEmpty(), "reservation still excludes the resident from labor");
            survey.reserved(Set.of());
            survey.answer(level, data, Map.of(body.id(), body), seen -> { });
            helper.assertTrue(survey.hands().size() == 1 && survey.hands().getFirst().cargo().stream()
                .anyMatch(stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 6),
                "the released crew hand derives cargo from the raw observation");
        } finally {
            data.inventory().clear();
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void commitPublishesInPlacePackShrinkAndComponentMutation(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        body.pack().setItem(0, new ItemStack(Items.BREAD, 6));
        ColonyData data = new ColonyData();
        Survey survey = new Survey();
        survey.answer(level, data, Map.of(body.id(), body), seen -> { });
        InventoryObservations.work(data.inventory(), level.getServer(), 100);
        survey.answer(level, data, Map.of(body.id(), body), seen -> { });
        var before = data.inventory().snapshot().residents().get(body.id());
        Worker worker = new Hands(body, Stands.NONE, Placement.NOWHERE, drop -> { }, List::of);
        Node node = new Node() {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER,
                new WorkSite.AtEntity(body.id(), WorldPos.of(level, body.blockPosition())),
                Stances.WHEREVER, Workload.Once.of(1)).done();

            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Outcome commit(ServerLevel where, Worker who) {
                ItemStack stack = who.pack().getItem(0);
                stack.shrink(2);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("core mutation"));
                return Outcome.done();
            }
        };
        Commit.run(level, node, worker);
        try {
            helper.assertTrue(data.inventory().snapshot().residents().get(body.id()).state() == State.DIRTY,
                "Commit reports an in-place mutation even without a container mutator");
            helper.succeedWhen(() -> {
                survey.answer(level, data, Map.of(body.id(), body), seen -> { });
                var after = data.inventory().snapshot().residents().get(body.id());
                helper.assertTrue(after.state() == State.OBSERVED
                    && after.version().contents() > before.version().contents()
                    && after.pack().get(0).stackCopy().getCount() == 4
                    && after.pack().get(0).stackCopy().getHoverName().getString().equals("core mutation"),
                    "the targeted reread captures count and components");
                data.inventory().clear();
                body.discard();
            });
        } catch (RuntimeException broken) {
            data.inventory().clear();
            body.discard();
            throw broken;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeWritesStowingAndTransferRefreshOneSharedObservation(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        WorldPos at = WorldPos.of(level, pos);
        Stash stash = Stash.at(at);
        colony.stores(OWNER, level.dimension(), List.of(at));
        Placement placement = new Placement(Set.of(), List.of(), List.of(stash), List.of(stash));
        Worker worker = new Hands(body, Stands.NONE, placement, drop -> { }, List::of);
        AtomicInteger phase = new AtomicInteger();
        helper.succeedWhen(() -> {
            InventorySnapshot.Store observed = colony.inventory().snapshot().stores().get(stash);
            helper.assertTrue(observed != null && observed.state() == State.OBSERVED, "store observation is ready");
            int count = observed.slots().stream().mapToInt(slot -> slot.stackCopy().getCount()).sum();
            if (phase.get() == 0) {
                var container = Containers.at(level, pos).orElseThrow();
                container.setItem(0, new ItemStack(Items.BREAD, 8));
                container.setChanged();
                phase.incrementAndGet();
                helper.assertTrue(false, "wait for a native empty-to-stocked mutation");
            }
            if (phase.get() == 1) {
                helper.assertTrue(count == 8, "native setChanged queues the cached-empty store");
                Stowing.put(worker, placement, List.of(new ItemStack(Items.BREAD, 2)));
                phase.incrementAndGet();
                helper.assertTrue(false, "wait for stowing");
            }
            if (phase.get() == 2) {
                helper.assertTrue(count == 10, "stowing updates the same observation");
                TransferNode take = TransferNode.at(TransferNode.Endpoint.store(stash), TransferNode.Endpoint.pack(),
                    new Manifest(List.of(new ItemStack(Items.BREAD, 3)), List.of()),
                    Set.of(new Stand(at))).orElseThrow();
                helper.assertTrue(take.draw(level, worker) instanceof Staged.Taken, "the live transfer succeeds");
                phase.incrementAndGet();
                helper.assertTrue(false, "wait for the transfer");
            }
            helper.assertTrue(count == 7, "transfer extraction updates the shared store slots");
            colony.raze();
            body.discard();
        });
    }
}
