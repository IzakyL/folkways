package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.colony.ColonyMetrics;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ItemIdentityGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ItemIdentityGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void componentSubsetsKeepUnspecifiedValuesAndOldRequirementsFree(GameTestHelper helper) {
        ItemStack first = ammo("tacz:45acp", 7);
        CompoundTag extra = first.get(DataComponents.CUSTOM_DATA).copyTag();
        extra.putString("maker", "test");
        first.set(DataComponents.CUSTOM_DATA, CustomData.of(extra));
        first.set(DataComponents.CUSTOM_NAME, Component.literal("Ammunition"));
        ItemSpec wanted = subset("tacz:45acp");
        helper.assertTrue(Goods.matches(first, wanted) && !Goods.matches(ammo("tacz:9mm", 7), wanted),
            "custom_data permits extra fields but distinguishes ammo ids");
        helper.assertTrue(Goods.matches(first, ItemSpec.of(Items.PAPER))
            && Goods.matches(first, ItemSpec.anyOf(List.of(ItemSpec.of(Items.STONE), wanted))),
            "ordinary requirements and alternatives retain their meaning");
        ItemSpec sized = ItemSpec.of(Items.PAPER).withComponents(DataComponentMap.builder()
            .set(DataComponents.MAX_STACK_SIZE, 16).build());
        first.set(DataComponents.MAX_STACK_SIZE, 16);
        helper.assertTrue(Goods.matches(first, sized) && !Goods.matches(ammo("tacz:45acp", 7), sized),
            "ordinary component constraints compare whole values");
        helper.assertTrue(Goods.stackSize(sized) == 16 && Goods.stackSize(ItemSpec.of(Items.PAPER)) == 64,
            "component-aware sizes use separate cache entries");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void customDataSubsetsRecurseThroughCompoundsAndLists(GameTestHelper helper) throws Exception {
        CompoundTag expected = TagParser.parseTag("{ammo:{id:'tacz:45acp'},lots:[{maker:'test'}]}");
        CompoundTag actual = TagParser.parseTag("{ammo:{id:'tacz:45acp',quality:2},lots:[{maker:'other'},{maker:'test',batch:1}]}");
        ItemSpec wanted = ItemSpec.of(Items.PAPER).withComponents(DataComponentMap.builder()
            .set(DataComponents.CUSTOM_DATA, CustomData.of(expected)).build());
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(actual));
        helper.assertTrue(Goods.matches(stack, wanted), "nested compounds and unordered NBT subset lists permit extra values");
        actual.getCompound("ammo").putString("id", "tacz:9mm");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(actual));
        helper.assertTrue(!Goods.matches(stack, wanted) && !Goods.overlap(wanted, Goods.specOf(stack)),
            "a conflicting nested identity fails both matching and overlap");
        ItemStack potion = new ItemStack(Items.POTION);
        helper.assertTrue(Goods.matches(potion, Goods.specOf(potion)),
            "an item's customized getDefaultInstance cannot replace an actual stack's default map");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void exactIdentityIncludesDefaultsRemovalsAndIgnoresCount(GameTestHelper helper) {
        ItemStack first = ammo("tacz:45acp", 2);
        first.remove(DataComponents.RARITY);
        ItemSpec identity = Goods.specOf(first);
        int hash = identity.hashCode();
        ItemStack same = first.copyWithCount(20);
        helper.assertTrue(identity.equals(Goods.specOf(same)) && Goods.matches(same, identity),
            "stack count is not part of identity");
        first.set(DataComponents.CUSTOM_DATA, ammo("tacz:9mm", 1).get(DataComponents.CUSTOM_DATA));
        helper.assertTrue(identity.hashCode() == hash && Goods.matches(same, identity) && !Goods.matches(first, identity),
            "changing the source stack cannot change an identity used as a map key");
        same.set(DataComponents.RARITY, Rarity.COMMON);
        helper.assertTrue(!Goods.matches(same, identity), "removed defaults are part of the exact map");
        ItemStack shown = Goods.display(identity);
        helper.assertTrue(!shown.has(DataComponents.RARITY) && Goods.matches(shown, identity),
            "display stacks retain removed and custom components");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void constraintsRoundTripThroughCodecAndNbt(GameTestHelper helper) {
        ItemStack stack = ammo("tacz:45acp", 7);
        stack.remove(DataComponents.RARITY);
        ItemSpec both = ItemSpec.anyOf(List.of(Goods.specOf(stack), subset("tacz:9mm")));
        ItemSpec loaded = ItemSpec.load(Reader.of(both.save())).orElseThrow();
        helper.assertTrue(loaded.equals(both) && loaded.hashCode() == both.hashCode(), "NBT round trip preserves map keys");
        var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ItemSpec decoded = ItemSpec.CODEC.parse(ops, ItemSpec.CODEC.encodeStart(ops, both).getOrThrow()).getOrThrow();
        helper.assertTrue(decoded.equals(both) && Goods.matches(stack, decoded), "registry codec round trip preserves alternatives");
        stack.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.UNBREAKING), 1);
        ItemSpec enchanted = Goods.specOf(stack);
        helper.assertTrue(ItemSpec.load(Reader.of(enchanted.save(helper.getLevel().registryAccess())),
            helper.getLevel().registryAccess()).orElseThrow().equals(enchanted), "registry-backed components round trip");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void oldSavesMigrateToUnconstrainedRequirementsAndInvalidConstraintsDoNotBroaden(GameTestHelper helper) {
        CompoundTag item = new CompoundTag();
        item.putString("item", "minecraft:paper");
        CompoundTag tag = new CompoundTag();
        tag.putString("tag", "minecraft:logs");
        CompoundTag union = new CompoundTag();
        ListTag children = new ListTag();
        children.add(item);
        children.add(tag);
        union.put("anyOf", children);
        helper.assertTrue(ItemSpec.load(Reader.of(item)).orElseThrow().equals(ItemSpec.of(Items.PAPER))
            && ItemSpec.load(Reader.of(tag)).orElseThrow().equals(ItemSpec.of(ItemTags.LOGS))
            && ItemSpec.load(Reader.of(union)).orElseThrow().equals(ItemSpec.anyOf(List.of(
                ItemSpec.of(Items.PAPER), ItemSpec.of(ItemTags.LOGS)))), "all three old forms explicitly default to no constraint");
        item.putString("components", "broken");
        helper.assertTrue(ItemSpec.load(Reader.of(item)).isEmpty(), "a corrupt constraint never becomes an item-only requirement");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void overlapsDistinguishIdentitiesAndCombineCompatibleSubsets(GameTestHelper helper) {
        ItemSpec first = subset("tacz:45acp");
        ItemSpec second = subset("tacz:9mm");
        ItemStack exact = ammo("tacz:45acp", 1);
        helper.assertTrue(!Goods.overlap(first, second) && Goods.overlap(first, Goods.specOf(exact))
            && !Goods.overlap(second, Goods.specOf(exact)), "overlap checks component constraints");
        CompoundTag independent = new CompoundTag();
        independent.putString("maker", "test");
        ItemSpec other = ItemSpec.of(Items.PAPER).withComponents(DataComponentMap.builder()
            .set(DataComponents.CUSTOM_DATA, CustomData.of(independent)).build());
        helper.assertTrue(Goods.overlap(first, other) && Goods.overlap(other, first)
            && !Goods.overlap(other, Goods.specOf(exact)), "subsets can share a larger map, but an exact map must already match");
        helper.assertTrue(!Goods.overlap(ItemSpec.anyOf(List.of(first, ItemSpec.of(Items.STONE))), second),
            "alternatives cannot hide incompatible constraints");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void stockClaimsManifestsAndPackCellsKeepVariantsSeparate(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        Stash source = new Stash(f.source);
        ItemStack first = ammo("tacz:45acp", 5);
        ItemStack second = ammo("tacz:9mm", 9);
        Stock stock = new Stock(List.of(new Stock.Holding(source, first), new Stock.Holding(source, second)), Map.of(source, 0));
        ItemSpec broad = ItemSpec.of(Items.PAPER);
        helper.assertTrue(stock.lotsOf(broad).size() == 2 && stock.available(broad) == 14
            && stock.available(subset("tacz:45acp")) == 5 && stock.available(subset("tacz:9mm")) == 9,
            "lots and count caches distinguish component identities");
        helper.assertTrue(stock.manifest(source, Goods.specOf(first), 2, 3).exact().getFirst().getCount() == 3
            && stock.roomAt(source, Goods.specOf(first)) == 59, "manifests and partial stack room use the matching lot alone");
        Claims claims = new Claims();
        claims.take(new Claim(new Resource.Lot(source, Goods.specOf(first)), UUID.randomUUID(), 5));
        helper.assertTrue(claims.taken(new Resource.Lot(source, Goods.specOf(second))) == 0,
            "reserving one identity leaves the other untouched");
        helper.assertTrue(Leaves.cells(Leaves.ofStacks(List.of(first, second))) == 2
            && !PackLoad.fits(0, 1, List.of(List.of(new Amount(Goods.specOf(first), 5), new Amount(Goods.specOf(second), 9)))),
            "different variants cannot share a pack cell");
        first.setCount(50);
        helper.assertTrue(stock.available(broad) == 14 && stock.heldAt(source, broad) == 14,
            "the stock snapshot and its caches stay consistent after inventory changes");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void plannerHaulsAndCommitConsumesEachAmmoIdentitySeparately(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, f.source.cell()).orElseThrow();
        ItemStack first = ammo("tacz:45acp", 2);
        ItemStack second = ammo("tacz:9mm", 3);
        source.setItem(0, first.copy());
        source.setItem(1, second.copy());
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), PlanningFixture.Rounds.OWNER,
            new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1))
            .needs(List.of(new Need(subset("tacz:45acp"), 2, true), new Need(subset("tacz:9mm"), 3, true))).done();
        Node consume = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) {
                helper.assertTrue(who.supplied().size() == 2
                    && who.supplied().stream().filter(stack -> Goods.matches(stack, subset("tacz:45acp"))).mapToInt(ItemStack::getCount).sum() == 2
                    && who.supplied().stream().filter(stack -> Goods.matches(stack, subset("tacz:9mm"))).mapToInt(ItemStack::getCount).sum() == 3,
                    "commit receives both ammo types with their original counts and components");
                return Outcome.done();
            }
        };
        var solver = new PlanningFixture.Rounds();
        try {
            var solved = solver.solve(f.situation(new Stock(List.of(new Stock.Holding(new Stash(f.source), first),
                new Stock.Holding(new Stash(f.source), second)), Map.of(new Stash(f.source), 25)), List.of()),
                f.crew, List.of(Grown.of(consume)), List.of(), 0);
            var tour = solved.schedule().tours().get(f.worker.resident().id());
            helper.assertTrue(tour != null && tour.nodes().size() == 3, "each variant has its own fetch before consumption");
            for (UUID id : tour.nodes()) {
                var vertex = solved.weave().vertices().get(id);
                if (vertex.node() instanceof TransferNode fetch) {
                    helper.assertTrue(fetch.goods().size() == 1, "a fetch manifest carries one concrete identity");
                } else {
                    helper.assertTrue(vertex.plan().draws().size() == 2
                        && !vertex.plan().draws().getFirst().spec().equals(vertex.plan().draws().getLast().spec()),
                        "the consumer holds two distinct component identities");
                }
                helper.assertTrue(Commit.run(level, vertex.node(), new Placed(f.worker, vertex.plan())) instanceof Outcome.Done, "every planned step commits");
            }
            helper.assertTrue(source.isEmpty() && f.worker.pack().isEmpty(), "all five rounds are consumed exactly once");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void failedCommitReturnsComponentsWithoutMergingVariants(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        f.worker.pack().setItem(0, ammo("tacz:9mm", 4));
        f.worker.pack().setItem(1, ammo("tacz:45acp", 2));
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), PlanningFixture.Rounds.OWNER,
            new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1))
            .needs(new Need(subset("tacz:45acp"), 2, true)).done();
        Node failing = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) {
                return Outcome.failed(LaborRefusal.GOODS_GONE);
            }
        };
        helper.assertTrue(Commit.run(helper.getLevel(), failing, f.worker) instanceof Outcome.Failed
            && Goods.countIn(f.worker.pack(), subset("tacz:45acp")) == 2
            && Goods.countIn(f.worker.pack(), subset("tacz:9mm")) == 4, "rollback preserves both component identities");
        ItemStack extracted = Containers.extract(f.worker.pack(), stack -> Goods.matches(stack, ItemSpec.of(Items.PAPER)), 6);
        helper.assertTrue(extracted.getCount() == 4 && Goods.matches(extracted, subset("tacz:9mm")),
            "a broad extraction cannot grow one variant with another's count");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void stockListsKeepSeparateComponentStacks(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        var container = Containers.at(level, f.source.cell()).orElseThrow();
        container.setItem(0, ammo("tacz:45acp", 2));
        container.setItem(1, ammo("tacz:9mm", 3));
        var colony = Colonies.mint(level.getServer());
        try {
            colony.hold(PlanningFixture.Rounds.OWNER, new Held.Block(f.source)).orElseThrow();
            colony.stores(PlanningFixture.Rounds.OWNER, level.dimension(), List.of(f.source));
            var reading = ColonyMetrics.of(level.getServer(), colony);
            helper.assertTrue(reading.stock().size() == 2 && reading.goods() == 5,
                "stock lists count two component identities rather than one item id");
            helper.assertTrue(reading.stock().stream().allMatch(row -> row.count() == Goods.countIn(container, row.spec())
                && Goods.matches(row.stack(), row.spec())), "each stock icon preserves its components and its own count");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    private record Placed(Worker who, Placement placement) implements Worker {
        public Resident resident() { return who.resident(); }
        public Mob body() { return who.body(); }
        public Container pack() { return who.pack(); }
        public List<Store> within() { return who.within(); }
        public ItemStack held(ToolNeed need) { return who.held(need); }
        public void spill(ItemStack stack) { who.spill(stack); }
        public Effects effects() { return who.effects(); }
        public List<Node> route() { return who.route(); }
    }

    private static ItemSpec subset(String id) {
        return ItemSpec.of(Items.PAPER).withComponents(DataComponentMap.builder()
            .set(DataComponents.CUSTOM_DATA, ammo(id, 1).get(DataComponents.CUSTOM_DATA)).build());
    }

    private static ItemStack ammo(String id, int count) {
        ItemStack stack = new ItemStack(Items.PAPER, count);
        CompoundTag data = new CompoundTag();
        data.putString("AmmoId", id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }
}
