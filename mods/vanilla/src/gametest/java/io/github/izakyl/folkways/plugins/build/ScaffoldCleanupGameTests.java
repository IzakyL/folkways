package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScaffoldCleanupGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ScaffoldCleanupGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 1200)
    public static void completedConstructionRecoversItsRuntimeScaffold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (BlockPos floor : BlockPos.betweenClosed(new BlockPos(1, 0, 1), new BlockPos(21, 0, 21))) {
            level.setBlock(helper.absolutePos(floor), Blocks.STONE.defaultBlockState(), 3);
        }
        for (BlockPos floor : BlockPos.betweenClosed(new BlockPos(8, 3, 8), new BlockPos(12, 3, 12))) {
            level.setBlock(helper.absolutePos(floor), Blocks.STONE.defaultBlockState(), 3);
        }
        Map<BlockPos, BlockState> targets = new LinkedHashMap<>();
        for (int y = 4; y <= 5; y++) {
            for (int z = 9; z <= 10; z++) {
                targets.put(helper.absolutePos(new BlockPos(8, y, z)), Blocks.STONE.defaultBlockState());
            }
        }
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        SiteRig rig = SiteRig.start(level, new BuildTarget(WorldPos.of(level, targets.keySet().iterator().next()).realm(),
            targets), temporary, Set.of());
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 1, 9))));
        body.setOnGround(true);
        level.addFreshEntity(body);
        Driver driver = new Driver(helper, rig, temporary, body);
        helper.onEachTick(driver::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.site.complete(),
                "the completed site also recovers its scaffold; targets left=" + rig.site.left()
                    + ", scaffold cells=" + temporary.cells().size() + ", pieces=" + rig.site.pieces().size()
                    + ", driver=" + driver.state()))
            .thenExecute(() -> {
                helper.assertTrue(driver.raised > 0, "the resident actually raised temporary access during construction");
                helper.assertValueEqual(driver.returned, driver.raised, "every placed scaffold returns to the worker");
                helper.assertTrue(targets.keySet().stream().allMatch(cell -> level.getBlockState(cell).is(Blocks.STONE)),
                    "the complete building stays in place");
                body.discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void cleanupWaitsWhenTheScaffoldStackIsActuallyFull(GameTestHelper helper) {
        waitsForPackSpace(helper, new ItemStack(Items.SCAFFOLDING, 64));
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void cleanupWaitsWhenOnlySomeScaffoldsWouldFit(GameTestHelper helper) {
        waitsForPackSpace(helper, new ItemStack(Items.SCAFFOLDING, 62));
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void cleanupDoesNotMergeIntoANamedScaffoldStack(GameTestHelper helper) {
        ItemStack named = new ItemStack(Items.SCAFFOLDING, 61);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("kept scaffolding"));
        waitsForPackSpace(helper, named);
    }

    private static void waitsForPackSpace(GameTestHelper helper, ItemStack carried) {
        ServerLevel level = helper.getLevel();
        for (BlockPos floor : BlockPos.betweenClosed(new BlockPos(1, 0, 1), new BlockPos(21, 0, 21))) {
            level.setBlock(helper.absolutePos(floor), Blocks.STONE.defaultBlockState(), 3);
        }
        BlockPos base = helper.absolutePos(new BlockPos(10, 1, 10));
        List<BlockPos> column = List.of(base, base.above(), base.above(2));
        column.forEach(cell -> level.setBlock(cell, ScaffoldNode.state(), 3));
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        temporary.added(column.stream().map(cell -> WorldPos.of(level, cell)).toList());
        SiteRig rig = SiteRig.start(level, new BuildTarget(WorldPos.of(level, base).realm(), Map.of()), temporary, Set.of());
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(base.west(2)));
        body.setOnGround(true);
        level.addFreshEntity(body);
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.STONE, 64), carried.copy());
        Driver driver = new Driver(helper, rig, temporary, body, pack);
        helper.onEachTick(driver::tick);
        helper.startSequence().thenExecuteAfter(80, () -> {
            helper.assertTrue(!temporary.isEmpty()
                && column.stream().allMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)),
                "cleanup keeps the entire column until the pack can receive every returned item");
            helper.assertTrue(ItemStack.matches(pack.getItem(1), carried), "capacity checks never change carried items");
            pack.setItem(1, ItemStack.EMPTY);
        }).thenWaitUntil(() -> helper.assertTrue(rig.site.complete(), "cleanup resumes after room is made"))
            .thenExecute(() -> {
                helper.assertValueEqual(driver.returned, column.size(), "every scaffold is recovered exactly once");
                helper.assertTrue(pack.getItem(1).is(Items.SCAFFOLDING)
                    && pack.getItem(1).getCount() == column.size(), "the recovered scaffold is stored in the freed slot");
                body.discard();
            }).thenSucceed();
    }

    private static final class Driver {
        private final GameTestHelper helper;
        private final SiteRig rig;
        private final TemporaryScaffolds temporary;
        private final Worker worker;
        private final Set<UUID> ended = new HashSet<>();
        private Node shift;
        private Node piece;
        private int left;
        private int raised;
        private int returned;
        private String result = "not committed";

        Driver(GameTestHelper helper, SiteRig rig, TemporaryScaffolds temporary, Mob body) {
            // Every slot stays occupied after building; cleanup must reuse the partly spent scaffold stack.
            this(helper, rig, temporary, body,
                new SimpleContainer(new ItemStack(Items.STONE, 64), new ItemStack(Items.SCAFFOLDING, 64)));
        }

        Driver(GameTestHelper helper, SiteRig rig, TemporaryScaffolds temporary, Mob body, SimpleContainer pack) {
            this.helper = helper;
            this.rig = rig;
            this.temporary = temporary;
            this.worker = (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "body" -> body;
                    case "pack" -> pack;
                    case "resident" -> new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("villager"));
                    case "effects" -> Effects.NONE;
                    case "placement" -> Placement.NOWHERE;
                    case "held" -> ItemStack.EMPTY;
                    case "within", "route", "supplied" -> List.of();
                    default -> null;
                });
        }

        void tick() {
            ServerLevel level = helper.getLevel();
            rig.site.tick(level);
            if (shift == null) {
                shift = rig.asked.stream().flatMap(goal -> goal.nodes().stream())
                    .filter(node -> !ended.contains(node.id()) && !rig.withdrawn.contains(node.id()))
                    .findFirst().orElse(null);
                if (shift == null) {
                    return;
                }
            }
            Workload.Within workload = (Workload.Within) shift.spec().workload();
            workload.tick(level, worker);
            if (piece == null) {
                Workload.Within.Turn next = workload.next(level, worker);
                if (next instanceof Workload.Within.Turn.Piece operation) {
                    piece = operation.node();
                    left = ((Workload.Once) piece.spec().workload()).baseTicks();
                } else if (next == Workload.Within.LEAVE) {
                    ended.add(shift.id());
                    rig.end(shift, Ending.DONE);
                    shift = null;
                }
                return;
            }
            if (--left > 0) {
                return;
            }
            int before = temporary.cells().size();
            Outcome outcome = Commit.run(level, piece, worker);
            result = outcome.toString();
            if (outcome instanceof Outcome.Done done) {
                raised += Math.max(0, temporary.cells().size() - before);
                returned += done.made().stream().filter(stack -> stack.is(Items.SCAFFOLDING))
                    .mapToInt(ItemStack::getCount).sum();
                done.made().forEach(stack -> helper.assertTrue(Containers.insert(worker.pack(), stack).isEmpty(),
                    "the returned material fits without dropping anything"));
                workload.ended(level, piece, Ending.DONE);
                piece = null;
            } else {
                left = 20;
            }
        }

        String state() {
            Mob body = worker.body();
            return "shift=" + (shift != null) + ", piece=" + (piece instanceof BuildNode build ? build.job().kind() : piece)
                + ", left=" + left + ", body=" + body.position() + ", aim=" + body.getNavigation().getTargetPos()
                + ", navDone=" + body.getNavigation().isDone()
                + ", turns=" + rig.site.turns() + ", asked=" + rig.asked.size() + ", ended=" + ended.size()
                + ", withdrawn=" + rig.withdrawn.size() + ", work=" + rig.site.pieces().stream().map(p -> {
                    Step step = p.work().step().orElseThrow();
                    return step.kind() + ":stances=" + p.node().spec().stances().cells().size()
                        + ":current=" + SiteApproach.stances(helper.getLevel(), step.focus(), step.after().keySet(),
                            p.work().stances()).size();
                }).toList() + ", owned=" + temporary.cells() + ", last=" + result;
        }
    }
}
