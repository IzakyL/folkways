package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCartsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CartGameTests {
    private static final ItemSpec SADDLES = ItemSpec.of(Items.SADDLE);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CartGameTests.class);
    }

    @GameTest(template = "empty")
    public static void hitchingDoesNotChangeThePackOrItsPlanningCapacity(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "hand_cart")) {
            int size = rig.pack().getContainerSize();
            Crew.Hand bare = rig.hand();
            rig.hitch();
            Crew.Hand pulling = rig.hand();
            helper.assertTrue(size == rig.pack().getContainerSize() && bare.packCells() == pulling.packCells()
                    && bare.packCapacity() == pulling.packCapacity(),
                "a manual connection never changes pack capacity");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savedPullerRelinksAfterReload(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "hand_cart")) {
            rig.hitch();
            rig.cart.setItem(0, new ItemStack(Items.SADDLE));
            CompoundTag tag = new CompoundTag();
            rig.cart.saveWithoutId(tag);
            rig.cart.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            AbstractCargoCart restored = rig.newCart("hand_cart");
            restored.load(tag);
            helper.getLevel().addFreshEntity(restored);
            restored.tick();
            helper.assertTrue(restored.getPulling() == rig.body && CartLinks.pulling(rig.body).orElseThrow() == restored,
                "native pullingUUID restores the resident-to-cart link");
            helper.assertTrue(Goods.countIn(restored, SADDLES) == 1, "reload keeps cart inventory");
            restored.setPulling(null);
            restored.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void leavingTheColonyDetachesTheCartWithoutTakingItsCargo(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "hand_cart")) {
            rig.hitch();
            rig.cart.setItem(0, new ItemStack(Items.DIAMOND, 7));
            rig.body.leaveColony();
            rig.cart.tick();
            helper.assertTrue(rig.cart.getPulling() == null && rig.cart.getItem(0).getCount() == 7,
                "leaving detaches a service cart with its inventory intact");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnloadedPullerKeepsItsSavedLinkAndRelinksOnReturn(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "hand_cart")) {
            rig.hitch();
            rig.cart.setItem(0, new ItemStack(Items.SADDLE));
            CompoundTag saved = new CompoundTag();
            rig.body.saveWithoutId(saved);
            UUID id = rig.body.id();
            rig.body.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            rig.cart.tick();
            helper.assertTrue(id.equals(((SavedPuller) rig.cart).folkways$puller()),
                "unloading a resident must retain native pullingUUID instead of detaching permanently");
            var replacement = (ResidentEntity) BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("folkways", "resident")).create(helper.getLevel());
            replacement.load(saved);
            helper.getLevel().addFreshEntity(replacement);
            replacement.joinColony(rig.colony.id());
            try {
                rig.cart.tick();
                helper.assertTrue(rig.cart.getPulling() == replacement
                        && CartLinks.pulling(replacement).orElseThrow() == rig.cart && Goods.countIn(rig.cart, SADDLES) == 1,
                    "the returning resident re-links without an entity scan or any change to the cargo");
            } finally {
                replacement.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void admissionSurvivesUnloadingAndColonySaveLoadButDestructionReleasesIt(GameTestHelper helper)
            throws ReflectiveOperationException {
        try (Rig rig = new Rig(helper, "hand_cart")) {
            var held = rig.colony.holdingOf(rig.cart.getUUID()).orElseThrow();
            rig.cart.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.assertTrue(rig.colony.holdingOf(rig.cart.getUUID()).filter(held::equals).isPresent(),
                "unloading a cart retains admission");
            var original = io.github.izakyl.folkways.core.engine.colony.ColonyData
                .find(helper.getLevel().getServer(), rig.colony.id()).orElseThrow();
            var load = original.getClass().getDeclaredMethod("load", io.github.izakyl.folkways.base.persist.Reader.class);
            load.setAccessible(true);
            var restored = (io.github.izakyl.folkways.core.engine.colony.ColonyData) ((Optional<?>) load.invoke(null,
                io.github.izakyl.folkways.base.persist.Reader.of(original.save(new CompoundTag())))).orElseThrow();
            helper.assertTrue(restored.works().holdingOf(rig.cart.getUUID()).filter(held::equals).isPresent(),
                "entity admission survives actual colony serialization without loading the cart");
            var replacement = rig.newCart("hand_cart");
            replacement.setUUID(rig.cart.getUUID());
            replacement.moveTo(rig.body.position());
            helper.getLevel().addFreshEntity(replacement);
            replacement.discard();
            helper.assertTrue(rig.colony.holdingOf(replacement.getUUID()).isEmpty(),
                "a broken admitted cart releases its holding");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDeadPullerLeavesItsLoadedCartBehind(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "hand_cart")) {
            rig.hitch();
            rig.cart.setItem(0, new ItemStack(Items.DIAMOND, 3));
            rig.body.kill();
            rig.cart.tick();
            helper.assertTrue(rig.cart.getPulling() == null && rig.cart.getItem(0).getCount() == 3,
                "death detaches the cart and retains its goods");
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        private final GameTestHelper helper;
        private final PlanningFixture fixture;
        private final ResidentEntity body;
        private final Colony colony;
        private final AbstractCargoCart cart;

        private Rig(GameTestHelper helper, String kind) {
            this.helper = helper;
            fixture = new PlanningFixture(helper, 6);
            body = (ResidentEntity) fixture.worker.body();
            colony = Colonies.mint(helper.getLevel().getServer());
            body.joinColony(colony.id());
            body.moveTo(fixture.source.cell().getBottomCenter());
            helper.getLevel().addFreshEntity(body);
            body.joinColony(colony.id());
            for (int x = -2; x <= 10; x++) {
                for (int z = -2; z <= 2; z++) {
                    helper.getLevel().setBlockAndUpdate(fixture.source.cell().offset(x, -1, z),
                        Blocks.STONE.defaultBlockState());
                }
            }
            cart = newCart(kind);
            cart.moveTo(fixture.source.cell().getBottomCenter().add(0, 0, 1));
            helper.getLevel().addFreshEntity(cart);
            colony.hold(FolkwaysCartsMod.OWNER, Held.Entity.of(cart)).orElseThrow();
        }

        private AbstractCargoCart newCart(String kind) {
            return (AbstractCargoCart) BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("astikorcartsredux", kind)).create(helper.getLevel());
        }

        private void hitch() {
            cart.setPulling(body);
            helper.assertTrue(cart.getPulling() == body && CartLinks.pulling(body).orElseThrow() == cart,
                "a manual connection restores the carting link");
        }

        private Container pack() {
            return body.pack();
        }

        private Crew.Hand hand() {
            var hand = fixture.crew.hands().getFirst();
            return new Crew.Hand(body.resident(), hand.at(), hand.packCells(), hand.packCapacity(), 1,
                hand.licences(), List.of(), Optional.empty(), List.of(), KitRules.defaults());
        }

        @Override
        public void close() {
            cart.setPulling(null);
            cart.discard();
            body.discard();
            colony.raze();
        }
    }
}
