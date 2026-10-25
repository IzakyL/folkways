package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.Pack;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.Manifest;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.core.engine.travel.graph.WayGraph;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PersonalItemsGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "personal_items");
    private static final String CLOTHED = "folkways_test:personal_clothing";

    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(PersonalItemsGameTests.class); }

    @SubscribeEvent public static void declare(Declaring event) {
        Equipment.register(OWNER, (colony, body) -> body.mob().getPersistentData().getBoolean(CLOTHED)
            ? List.of(new Equipment.Demand(EquipmentSlot.HEAD, Goods.specOf(Items.LEATHER_HELMET.getDefaultInstance())))
            : List.of());
    }

    @GameTest(template = "empty", batch = "personal_equipment")
    public static void ownershipSurvivesPackSavingAndClearingPreservesEveryOtherComponent(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var other = PersonBody.RESIDENT.get().create(helper.getLevel());
        try {
            ItemStack stack = new ItemStack(Items.IRON_SWORD);
            stack.setDamageValue(37);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("physical heirloom"));
            CompoundTag extra = new CompoundTag(); extra.putString("another_mod:detail", "retained");
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra));
            ItemStack original = stack.copy();
            body.pack().setItem(0, stack);
            PersonalItems.mark(body, body.pack().getItem(0), "mainhand");
            helper.assertTrue(PersonalItems.belongsTo(body, stack) && !PersonalItems.belongsTo(other, stack),
                "ownership identifies the resident, never merely an item type");
            Pack restored = new Pack(); restored.load(body.pack().save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            ItemStack saved = restored.getItem(0);
            helper.assertValueEqual(PersonalItems.purpose(body, saved).orElseThrow(), "mainhand", "the actual item tag survives saving");
            helper.assertTrue(ItemStack.matches(saved, stack), "count, durability, name and ownership survive pack round-trip");
            PersonalItems.clear(saved);
            helper.assertTrue(!PersonalItems.marked(saved) && ItemStack.matches(saved, original),
                "clear removes only the ownership tag and preserves unrelated custom data and components");
            ItemStack plain = new ItemStack(Items.ARROW, 17);
            PersonalItems.mark(body, plain, "ammo"); PersonalItems.clear(plain);
            helper.assertTrue(!plain.has(DataComponents.CUSTOM_DATA) && plain.getCount() == 17,
                "clearing the last tag restores the plain stack without changing its physical count");
        } finally { body.discard(); other.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "personal_equipment")
    public static void personalStacksShareTheRealEighteenCellsWithoutBecomingToolsOrCargo(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var other = PersonBody.RESIDENT.get().create(helper.getLevel());
        try {
            body.pack().setItem(0, new ItemStack(Items.ARROW, 64));
            body.pack().setItem(1, new ItemStack(Items.IRON_SWORD));
            body.pack().setItem(2, new ItemStack(Items.STONE, 12));
            PersonalItems.mark(body, body.pack().getItem(0), "ammo");
            PersonalItems.mark(other, body.pack().getItem(1), "spare");
            Kit kit = Kit.of(colony, body);
            helper.assertValueEqual(body.pack().getContainerSize(), 18, "personal, loaned and cargo goods have one physical pack");
            helper.assertTrue(kit.inPack(body).stream().noneMatch(PersonalItems::marked), "personal weapons are outside labor's tool snapshot");
            helper.assertTrue(kit.cargoIn(body).size() == 1 && kit.cargoIn(body).getFirst().is(Items.STONE),
                "neither this resident's ammunition nor another resident's marked weapon becomes cargo");
            int capacity = body.pack().plannableCells(item -> kit.holds(item.getDefaultInstance())) - kit.keptCells(body);
            helper.assertValueEqual(capacity, 16, "64 arrows occupy one cell and the real sword one more, without shadow inventory");
            helper.assertValueEqual(body.pack().plannableRoom(item -> kit.holds(item.getDefaultInstance())), 15,
                "only the fifteen physically empty cells are currently free");
            helper.assertValueEqual(Kit.carried(body, Goods.specOf(Items.ARROW.getDefaultInstance())), 0L,
                "personal ammunition does not satisfy a generic labor kit request");
        } finally { body.discard(); other.discard(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "personal_equipment")
    public static void ordinaryAndPlannedMaterialDrawsCannotConsumePersonalAmmunition(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        try {
            var arrows = Goods.specOf(Items.ARROW.getDefaultInstance());
            body.pack().setItem(0, new ItemStack(Items.ARROW, 16));
            PersonalItems.mark(body, body.pack().getItem(0), "ammo");
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(level, helper.absolutePos(BlockPos.ZERO)),
                Stances.WHEREVER, Workload.Once.of(0)).needs(new Need(arrows, 3, true)).done();
            Node consumer = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel at, Worker who) {
                    helper.assertTrue(who.supplied().stream().noneMatch(PersonalItems::marked), "the node only receives ordinary material");
                    return Outcome.done();
                }
            };
            Worker unplanned = new Hands(body, null, Placement.NOWHERE, drop -> { }, List::of);
            helper.assertTrue(Commit.run(level, consumer, unplanned) instanceof Outcome.Failed, "an undeclared draw cannot spend personal ammunition");
            Placement placed = new Placement(java.util.Set.of(), List.of(), List.of(), List.of(),
                List.of(new Placement.Draw(java.util.Optional.empty(), arrows, 3)));
            Worker planned = new Hands(body, null, placed, drop -> { }, List::of);
            helper.assertTrue(Commit.run(level, consumer, planned) instanceof Outcome.Failed, "a stale planner claim cannot spend newly protected items");
            body.pack().setItem(1, new ItemStack(Items.ARROW, 3));
            helper.assertTrue(Commit.run(level, consumer, planned) instanceof Outcome.Done, "ordinary matching material remains usable");
            helper.assertTrue(body.pack().getItem(1).isEmpty() && body.pack().getItem(0).getCount() == 16,
                "only the three ordinary arrows were consumed; the physical personal stack is unchanged");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "personal_equipment")
    public static void aStaleHaulManifestCannotStowPersonalStacks(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        try {
            BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
            level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
            Stash store = Stash.at(WorldPos.of(level, at));
            body.pack().setItem(0, new ItemStack(Items.ARROW, 16));
            PersonalItems.mark(body, body.pack().getItem(0), "ammo");
            Worker who = new Hands(body, null, new Placement(java.util.Set.of(), List.of(), List.of(), List.of(store)), drop -> { }, List::of);
            var exact = new TransferNode(UUID.randomUUID(), new TransferNode.Endpoint.Pack(java.util.Optional.empty()),
                new TransferNode.Endpoint.Store(store), new Manifest(List.of(body.pack().getItem(0)), List.of()),
                Stances.WHEREVER, WorkSite.at(level, at));
            helper.assertTrue(exact.draw(level, who) instanceof Staged.Short, "even an exact component manifest cannot extract personal cargo");
            var pending = new TransferNode(UUID.randomUUID(), new TransferNode.Endpoint.Pack(java.util.Optional.empty()),
                new TransferNode.Endpoint.Store(store), new Manifest(List.of(), List.of(new Need(Goods.specOf(Items.ARROW.getDefaultInstance()), 16))),
                Stances.WHEREVER, WorkSite.at(level, at));
            helper.assertTrue(pending.draw(level, who) instanceof Staged.Short, "a generic pending manifest also respects personal ownership");
            helper.assertTrue(body.pack().getItem(0).getCount() == 16 && PersonalItems.belongsTo(body, body.pack().getItem(0)),
                "rejected hauling neither moves nor consumes the protected physical stack");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "personal_equipment")
    public static void storageSurveyNeverOffersMarkedGoodsOrTheirOccupiedCellToOtherWorkers(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        try {
            BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
            level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
            var storage = Containers.at(level, at).orElseThrow();
            for (int slot = 0; slot < storage.getContainerSize(); slot++) storage.setItem(slot, new ItemStack(Items.STONE, 64));
            storage.setItem(0, new ItemStack(Items.ARROW, 16));
            PersonalItems.mark(body, storage.getItem(0), "ammo");
            storage.setItem(1, new ItemStack(Items.ARROW, 3));
            WorldPos source = WorldPos.of(level, at);
            colony.stores(OWNER, level.dimension(), List.of(source));
            var data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            var survey = new Survey();
            survey.asked(new Asks(false, java.util.Set.of(Stash.at(source)), false, java.util.Set.of(), Long.MAX_VALUE));
            survey.answer(level, data, java.util.Map.of(), ignored -> { });
            var stock = survey.known(WayGraph.UNBUILT, List.of()).world().stock();
            var arrows = Goods.specOf(Items.ARROW.getDefaultInstance());
            helper.assertValueEqual(stock.available(arrows), 3L, "the planner can reserve only the three ordinary arrows, never someone else's personal ammunition");
            helper.assertValueEqual(stock.roomAt(Stash.at(source), arrows), 61L,
                "the protected stack remains physically occupied and contributes neither an empty cell nor stack-merging room");
            helper.assertTrue(storage.getItem(0).getCount() == 16 && PersonalItems.belongsTo(body, storage.getItem(0)),
                "survey filtering never mutates real inventory");
        } finally { body.discard(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "personal_equipment")
    public static void clothingLocksPersistAndManualOwnershipSurvivesAutomaticReconciliation(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        try {
            body.mob().getPersistentData().putBoolean(CLOTHED, true);
            body.pack().setItem(0, new ItemStack(Items.LEATHER_HELMET));
            Equipment.lock(body, EquipmentSlot.HEAD, true);
            helper.assertTrue(Equipment.wanted(colony, body).isEmpty() && !Equipment.reconcile(colony, body),
                "an intentionally empty locked slot requests and equips nothing");
            CompoundTag saved = new CompoundTag(); body.saveWithoutId(saved);
            var restored = PersonBody.RESIDENT.get().create(helper.getLevel());
            try { restored.load(saved); helper.assertTrue(Equipment.locked(restored, EquipmentSlot.HEAD), "slot locks survive native entity saving"); }
            finally { restored.discard(); }
            Equipment.lock(body, EquipmentSlot.HEAD, false);
            helper.assertTrue(Equipment.reconcile(colony, body) && Equipment.managed(body, EquipmentSlot.HEAD), "unlocking restores the automatic clothing demand");
            ItemStack physical = body.getItemBySlot(EquipmentSlot.HEAD);
            Equipment.lock(body, EquipmentSlot.HEAD, true);
            body.getPersistentData().putBoolean(CLOTHED, false);
            helper.assertTrue(!Equipment.reconcile(colony, body) && !Equipment.release(body), "reconcile and colony release both leave locked clothes in place");
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.HEAD) == physical, "locking retains the one native armor item");
            Equipment.lock(body, EquipmentSlot.HEAD, false);
            helper.assertTrue(Equipment.release(body) && body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                "unlocking permits automatic clothing to return to the real pack");
            body.getPersistentData().putBoolean(CLOTHED, true);
            helper.assertTrue(Equipment.reconcile(colony, body), "the same returned physical clothing can be equipped again");
            physical = body.getItemBySlot(EquipmentSlot.HEAD);
            Equipment.manual(body, EquipmentSlot.HEAD);
            helper.assertTrue(!Equipment.managed(body, EquipmentSlot.HEAD) && !Equipment.release(body)
                && body.getItemBySlot(EquipmentSlot.HEAD) == physical, "manual clears only bookkeeping and never removes the real item");
        } finally { body.discard(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "personal_equipment")
    public static void packDeathDropsReleaseOwnershipWithoutDuplicatingOrLosingComponents(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        Component name = Component.literal(UUID.randomUUID().toString());
        try {
            ItemStack arrows = new ItemStack(Items.ARROW, 16);
            arrows.set(DataComponents.CUSTOM_NAME, name);
            CompoundTag extra = new CompoundTag(); extra.putInt("another_mod:detail", 42);
            arrows.set(DataComponents.CUSTOM_DATA, CustomData.of(extra));
            ItemStack expected = arrows.copy();
            PersonalItems.mark(body, arrows, "ammo");
            body.pack().setItem(0, arrows);
            body.pack().dropContents(body);
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2),
                drop -> name.equals(drop.getItem().get(DataComponents.CUSTOM_NAME)));
            helper.assertTrue(body.pack().isEmpty() && drops.size() == 1, "death empties the pack and creates exactly one physical stack");
            helper.assertTrue(ItemStack.matches(drops.getFirst().getItem(), expected),
                "the dropped arrows lose only personal ownership, retaining count, name and unrelated custom data");
            drops.forEach(ItemEntity::discard);
        } finally {
            level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2),
                drop -> name.equals(drop.getItem().get(DataComponents.CUSTOM_NAME))).forEach(ItemEntity::discard);
            body.discard();
        }
        helper.succeed();
    }
}
