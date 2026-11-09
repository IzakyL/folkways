package io.github.izakyl.folkways.create.firefighting;

import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.api.ColonyInventory;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.resident.api.Pack;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_create")
@PrefixGameTestTemplate(false)
public final class FireStockGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "fire_stock");
    private static final Map<BlockPos, Probe> STORES = new HashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        Containers.register(LecternBlockEntity.class,
            (level, lectern) -> Optional.ofNullable(STORES.get(lectern.getBlockPos())));
        event.register(FireStockGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void observationsAreReusedAndMutationsArePublished(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, Blocks.LECTERN.defaultBlockState());
        WorldPos at = WorldPos.of(level, pos);
        Probe store = new Probe(9);
        store.setItem(0, new ItemStack(Items.WATER_BUCKET));
        STORES.put(pos, store);
        var body = PersonBody.RESIDENT.get().create(level);
        Probe pack = new Probe(Pack.DEFAULT_SIZE);
        pack.setItem(0, new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()));
        replacePack(body.pack(), pack);
        ColonyData data = new ColonyData();
        try {
            data.works().stores(OWNER, level.dimension(), List.of(at));
            data.inventory().advance(level, data.works(), Map.of(body.id(), body));
            InventoryObservations.work(data.inventory(), level.getServer(), 1000);
            int[] requests = new int[2];
            var delegate = data.works().inventory();
            var inventory = new ColonyInventory() {
                public InventorySnapshot snapshot() { return delegate.snapshot(); }

                public void ensureObserved(Stash target) {
                    requests[0]++;
                    delegate.ensureObserved(target);
                }

                public void ensureObservedResident(UUID target) {
                    requests[1]++;
                    delegate.ensureObservedResident(target);
                }
            };
            var colony = Proxies.of(Colony.class, (proxy, method, args) -> method.getName().equals("inventory") ? inventory
                : method.invoke(data.works(), args));
            FireStock stock = new FireStock(colony);
            InventorySnapshot snapshot = data.inventory().snapshot();
            InventorySnapshot.Store observed = snapshot.store(at).orElseThrow();
            helper.assertTrue(stock.resident(body.id()).orElseThrow()
                .is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()), "the pack tool is observed");
            helper.assertTrue(stock.water(snapshot, observed).orElseThrow(), "the store water is observed");
            int storeReads = store.reads;
            int packReads = pack.reads;
            for (int pass = 0; pass < 20; pass++) {
                stock.resident(body.id());
                stock.water(snapshot, observed);
                stock.extinguisher(snapshot, observed);
            }
            helper.assertTrue(store.reads == storeReads && pack.reads == packReads,
                "unchanged planning passes read neither the store nor the pack");
            store.setItem(0, new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()));
            pack.setItem(0, ItemStack.EMPTY);
            data.inventory().changed(Stash.at(at));
            data.inventory().changedResident(body.id());
            InventorySnapshot pending = data.inventory().snapshot();
            helper.assertTrue(stock.resident(body.id()).isEmpty(), "a dirty pack is unknown until refreshed");
            helper.assertTrue(stock.water(pending, pending.store(at).orElseThrow()).isEmpty(),
                "a dirty store is unknown until refreshed");
            helper.assertTrue(requests[0] == 1 && requests[1] == 1,
                "unknown store and resident stock request their respective observations");
            InventoryObservations.work(data.inventory(), level.getServer(), 1000);
            snapshot = data.inventory().snapshot();
            observed = snapshot.store(at).orElseThrow();
            helper.assertTrue(stock.resident(body.id()).orElseThrow().isEmpty(),
                "a pack mutation becomes visible");
            helper.assertTrue(stock.extinguisher(snapshot, observed).orElseThrow()
                && !stock.water(snapshot, observed).orElseThrow(), "a store mutation replaces cached facts");
            data.inventory().unloaded(body.id());
            helper.assertTrue(stock.resident(body.id()).isEmpty(), "an unavailable pack is unknown, not empty");
        } finally {
            STORES.remove(pos);
            data.inventory().clear();
            body.discard();
        }
        helper.succeed();
    }

    private static void replacePack(Pack pack, Probe probe) {
        try {
            Field field = pack.getClass().getDeclaredField("container");
            field.setAccessible(true);
            field.set(pack, probe);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static final class Probe extends SimpleContainer {
        int reads;

        Probe(int size) {
            super(size);
        }

        @Override
        public ItemStack getItem(int slot) {
            reads++;
            return super.getItem(slot);
        }
    }
}
