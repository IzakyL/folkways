package io.github.izakyl.folkways.plugins.supply;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SupplyGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SupplyGameTests.class); }

    @GameTest(template = "empty")
    public static void allItemsSupplyOnlyFiniteRequestedGoodsAndNeverActsAsOutputStorage(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            Node node = rig.offer(Items.STONE.getDefaultInstance(), 130);
            helper.assertValueEqual(node.spec().gives().getFirst().count(), 64L, "a withdrawal is bounded by the real stack size");
            helper.assertTrue(Containers.at(helper.getLevel(), rig.cell).isEmpty(), "a source is not a fake inventory or an output store");
            helper.assertTrue(rig.colony.storesIn(helper.getLevel().dimension()).isEmpty(), "registering the source never adds it as storage");
            var done = (Outcome.Done) node.commit(helper.getLevel(), rig);
            helper.assertValueEqual(done.made().getFirst().getCount(), 64, "exactly one finite physical stack is generated");
            helper.assertTrue(node.refusal(helper.getLevel()).isEmpty(),
                "a completed physical withdrawal must settle successfully without growing a duplicate replacement");
            helper.assertValueEqual(rig.box.generated().get("minecraft:stone"), 64L, "only goods actually generated are counted");
            helper.assertTrue(node.commit(helper.getLevel(), rig) instanceof Outcome.Failed, "a completed withdrawal cannot replay and duplicate its stack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void whitelistsAdmitItemsTagsAndQueriesWithoutSupplyingAnythingElse(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.box.configure(false, List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("wheat")),
                ItemFilter.tag(ResourceLocation.withDefaultNamespace("planks")), ItemFilter.query("minecraft:carrot").orElseThrow()));
            helper.assertTrue(rig.box.allows(Items.WHEAT) && rig.box.allows(Items.OAK_PLANKS) && rig.box.allows(Items.CARROT),
                "the actual whitelist supports items, tags and queries");
            helper.assertFalse(rig.box.allows(Items.BREAD), "intermediate and finished items outside a whitelist stay unavailable");
            helper.assertTrue(rig.options(Items.BREAD.getDefaultInstance(), 1).isEmpty(), "the planner receives no forbidden source offer");
            rig.box.configure(false, List.of());
            helper.assertTrue(rig.options(Items.WHEAT.getDefaultInstance(), 1).isEmpty(), "an empty whitelist supplies nothing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingTheWhitelistInvalidatesAnAlreadyPlannedWithdrawal(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            Node old = rig.offer(Items.WHEAT.getDefaultInstance(), 5);
            rig.box.configure(false, List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("carrot"))));
            helper.assertTrue(old.commit(helper.getLevel(), rig) instanceof Outcome.Failed, "old all-items jobs cannot escape the revised whitelist");
            helper.assertTrue(rig.box.generated().isEmpty(), "refusing an obsolete job generates no goods");
            helper.assertTrue(rig.offer(Items.CARROT.getDefaultInstance(), 5).commit(helper.getLevel(), rig) instanceof Outcome.Done,
                "a fresh allowed offer still works");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unmarkingTheSourceCancelsItsOutstandingMaterialOffer(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            Node old = rig.offer(Items.WHEAT.getDefaultInstance(), 5);
            rig.colony.release(rig.colony.holdingAt(rig.at).orElseThrow().id(), Release.LET_GO);
            helper.assertTrue(old.commit(helper.getLevel(), rig) instanceof Outcome.Failed, "an unmarked box cannot feed a stale job");
            helper.assertTrue(rig.box.generated().isEmpty(), "cancellation produces nothing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFullPackAndAnOutOfReachBodyCannotGenerateUncarriedGoods(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            Node job = rig.offer(Items.WHEAT.getDefaultInstance(), 5);
            for (int i = 0; i < rig.pack().getContainerSize(); i++) rig.pack().setItem(i, new ItemStack(Items.DIRT, 64));
            helper.assertTrue(job.commit(helper.getLevel(), rig) instanceof Outcome.Failed, "real pack capacity is checked before generation");
            rig.pack().clearContent();
            rig.resident.moveTo(rig.cell.offset(30, 0, 0).getCenter());
            helper.assertTrue(job.commit(helper.getLevel(), rig) instanceof Outcome.Failed, "a remote body must actually come to the source");
            helper.assertTrue(rig.box.generated().isEmpty(), "failed reach or capacity never creates supplies");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeBlockEntityReloadKeepsItsRulesAndCountersAndRejectsOldJobs(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.box.configure(false, List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("wheat"))));
            rig.offer(Items.WHEAT.getDefaultInstance(), 7).commit(helper.getLevel(), rig);
            Node old = rig.offer(Items.WHEAT.getDefaultInstance(), 1);
            var tag = rig.box.saveWithFullMetadata(helper.getLevel().registryAccess());
            var reloaded = new SupplyBox(rig.cell, SupplyContent.BLOCK.get().defaultBlockState());
            reloaded.loadWithComponents(tag, helper.getLevel().registryAccess());
            helper.getLevel().setBlockEntity(reloaded);
            helper.assertFalse(reloaded.allItems(), "native reload retains whitelist mode");
            helper.assertTrue(reloaded.allows(Items.WHEAT) && !reloaded.allows(Items.BREAD), "native reload retains actual whitelist rules");
            helper.assertValueEqual(reloaded.generated().get("minecraft:wheat"), 7L, "generation accounting survives reload");
            helper.assertTrue(old.commit(helper.getLevel(), rig) instanceof Outcome.Failed, "jobs tied to the unloaded block entity cannot mint goods");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyACreativePlayerInReachMayChangeTheBoxConfiguration(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var survival = helper.makeMockPlayer(GameType.SURVIVAL);
            survival.moveTo(rig.cell.getCenter());
            var packet = new SupplyNetwork.Configure(rig.cell, rig.box.revision(), false, List.of());
            helper.assertFalse(SupplyNetwork.configure(survival, packet), "survival players cannot change an infinite source");
            var creative = helper.makeMockPlayer(GameType.CREATIVE);
            creative.moveTo(rig.cell.getCenter());
            helper.assertTrue(SupplyNetwork.configure(creative, packet), "a nearby creative player can set an empty whitelist");
            helper.assertFalse(rig.box.allows(Items.WHEAT), "the accepted configuration has real server-side effect");
            helper.assertFalse(SupplyNetwork.configure(creative, packet), "a stale panel cannot overwrite a newer revision");
            creative.moveTo(rig.cell.offset(20, 0, 0).getCenter());
            helper.assertFalse(SupplyNetwork.configure(creative, new SupplyNetwork.Configure(rig.cell, rig.box.revision(), true, List.of())),
                "remote configuration is refused");
        }
        helper.succeed();
    }

    private static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final Colony colony;
        final BlockPos cell;
        final WorldPos at;
        final SupplyBox box;
        final ResidentEntity resident;
        final Stances stances;
        Rig(GameTestHelper helper) {
            this.helper = helper;
            cell = helper.absolutePos(new BlockPos(3, 1, 3));
            at = WorldPos.of(helper.getLevel(), cell);
            for (BlockPos floor : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(3, -1, 3))) {
                helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            }
            helper.getLevel().setBlockAndUpdate(cell, SupplyContent.BLOCK.get().defaultBlockState());
            box = (SupplyBox) helper.getLevel().getBlockEntity(cell);
            colony = Colonies.mint(helper.getLevel().getServer());
            helper.assertTrue(colony.hold(SupplyContent.SOURCE, new Held.Block(at)).isPresent(), "the colony claims the real source");
            resident = PersonBody.RESIDENT.get().create(helper.getLevel());
            resident.moveTo(cell.getX() + 1.5, cell.getY(), cell.getZ() + 0.5);
            stances = Stances.of(helper.getLevel(), java.util.Set.of(cell.east())).orElseThrow();
        }
        List<io.github.izakyl.folkways.core.api.work.Refinement.Change> options(ItemStack item, long count) {
            var shop = new SupplyWorkshop(UUID.randomUUID(), colony, at, stances, box, box.revision());
            Produce wanted = Produce.of(SupplyContent.ID, shop.site(), ItemSpec.of(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem())), count, Produce.UNBOUNDED);
            return shop.options(wanted, Grown.of(wanted));
        }
        Node offer(ItemStack item, long count) { return options(item, count).getFirst().replacement().nodes().getFirst(); }
        public Resident resident() { return resident.resident(); }
        public Mob body() { return resident; }
        public Container pack() { return resident.pack(); }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new AssertionError("withdrawals must fit before creating goods"); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { resident.discard(); colony.raze(); }
    }
}
