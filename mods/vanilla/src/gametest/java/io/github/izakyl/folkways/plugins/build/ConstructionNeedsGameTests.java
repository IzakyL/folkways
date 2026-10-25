package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionNeedsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ConstructionNeedsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void automaticCampfireUsesBuildingLaborAndConsumesMaterial(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.refresh();
        fixture.refresh();
        helper.assertTrue(fixture.asked.size() == 1, "refresh does not duplicate construction requests");
        Node node = fixture.asked.getFirst();
        helper.assertTrue(node.spec().needs().stream().anyMatch(need ->
            need.spec().equals(io.github.izakyl.folkways.core.api.terms.ItemSpec.of(ResourceLocation.withDefaultNamespace("campfire"))) && need.count() == 1), "one campfire is requested");
        helper.assertTrue(node.spec().vocation().filter(BuildContent.trade()::equals).isPresent(), "construction uses the building vocation");
        fixture.pack.setItem(0, new ItemStack(Items.CAMPFIRE));
        Outcome outcome = Commit.run(helper.getLevel(), fixture.operation(), fixture.worker);
        helper.assertTrue(outcome instanceof Outcome.Done, "normal construction commit succeeds: " + outcome);
        helper.assertTrue(helper.getLevel().getBlockState(fixture.cell).is(Blocks.CAMPFIRE), "campfire is built");
        helper.assertTrue(fixture.pack.isEmpty(), "construction spends its campfire material");
        fixture.needs.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removedRegionWithdrawsAutomaticConstruction(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.refresh();
        fixture.needs.refresh(helper.getLevel(), Map.of());
        helper.assertTrue(!fixture.asked.isEmpty() && fixture.asked.stream().allMatch(node ->
            fixture.withdrawn.contains(node.id())), "all pending work is withdrawn when its region disappears");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void automaticConstructionPreservesNewObstacles(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.refresh();
        fixture.pack.setItem(0, new ItemStack(Items.CAMPFIRE));
        Node operation = fixture.operation();
        helper.getLevel().setBlock(fixture.cell, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        Outcome outcome = Commit.run(helper.getLevel(), operation, fixture.worker);
        helper.assertTrue(outcome instanceof Outcome.Failed, "construction refuses a newly placed obstacle");
        helper.assertTrue(helper.getLevel().getBlockState(fixture.cell).is(Blocks.DIAMOND_BLOCK), "new block survives");
        helper.assertTrue(fixture.pack.countItem(Items.CAMPFIRE) == 1, "failed construction spends no materials");
        fixture.needs.close();
        helper.succeed();
    }

    private static final class Fixture {
        final List<Node> asked = new ArrayList<>();
        final List<UUID> withdrawn = new ArrayList<>();
        final SimpleContainer pack = new SimpleContainer(8);
        final ConstructionNeeds needs;
        final Worker worker;
        final GameTestHelper helper;
        final BlockPos cell;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            cell = helper.absolutePos(new BlockPos(2, 2, 2));
            for (int x = 0; x <= 4; x++) {
                for (int z = 0; z <= 4; z++) {
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 1, z)), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 2);
            Colony colony = (Colony) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Colony.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "submit" -> { asked.addAll(((Grown) args[2]).nodes()); yield null; }
                    case "withdraw" -> { withdrawn.add((UUID) args[1]); yield null; }
                    default -> null;
                });
            needs = new ConstructionNeeds(colony, ResourceLocation.fromNamespaceAndPath("folkways", "apiary"));
            Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
            body.setPos(Vec3.atBottomCenterOf(cell.offset(-1, 0, 0)));
            worker = (Worker) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "body" -> body;
                    case "pack" -> pack;
                    case "resident" -> new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
                    case "effects" -> Effects.NONE;
                    case "placement" -> Placement.NOWHERE;
                    case "held" -> ItemStack.EMPTY;
                    case "within", "route", "supplied" -> List.of();
                    default -> null;
                });
        }

        Node operation() {
            Workload.Within shift = (Workload.Within) asked.getFirst().spec().workload();
            Workload.Within.Turn turn = shift.next(helper.getLevel(), worker);
            helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "builder receives a construction operation: " + turn);
            return ((Workload.Within.Turn.Piece) turn).node();
        }

        void refresh() {
            needs.refresh(helper.getLevel(), Map.of(WorldPos.of(helper.getLevel(), cell), Blocks.CAMPFIRE.defaultBlockState()));
        }
    }
}
