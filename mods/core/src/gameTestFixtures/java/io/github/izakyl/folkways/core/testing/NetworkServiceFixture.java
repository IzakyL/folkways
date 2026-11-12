package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.resident.api.WorkPriorities;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** A real resident serving network orders through a test bench or an enrolled vanilla furnace. */
public final class NetworkServiceFixture {

    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_core", "service_test");
    public final Colony colony;
    public final ResidentEntity person;
    public final LaborFixture labor;
    public boolean running;
    private final List<ResidentEntity> helpers = new ArrayList<>();
    private boolean closed;
    private final GameTestHelper helper;
    private final BlockPos station;
    private final BlockPos fuelStore;
    private final List<BlockPos> stations = new ArrayList<>();

    public NetworkServiceFixture(GameTestHelper helper, List<BlockPos> terminals) {
        this(helper, terminals, 0);
    }

    public static NetworkServiceFixture furnace(GameTestHelper helper, List<BlockPos> terminals) {
        return furnaces(helper, terminals, 1);
    }

    public static NetworkServiceFixture furnaces(GameTestHelper helper, List<BlockPos> terminals, int count) {
        return new NetworkServiceFixture(helper, terminals, count);
    }

    private NetworkServiceFixture(GameTestHelper helper, List<BlockPos> terminals, int furnaces) {
        this.helper = helper;
        var level = helper.getLevel();
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 10; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        colony = Colonies.mint(level.getServer());
        for (BlockPos terminal : terminals) {
            colony.hold(WaresContent.STORE, new Claimed.Block(
                WorldPos.of(level, helper.absolutePos(terminal)))).orElseThrow();
        }
        var stores = new ArrayList<>(terminals.stream()
            .map(at -> WorldPos.of(level, helper.absolutePos(at))).toList());
        station = helper.absolutePos(new BlockPos(8, 2, 5));
        fuelStore = helper.absolutePos(new BlockPos(2, 2, 8));
        if (furnaces > 0) {
            for (int index = 0; index < furnaces; index++) {
                BlockPos at = station.offset(index, 0, 0);
                level.setBlockAndUpdate(at, Blocks.FURNACE.defaultBlockState());
                helper.assertTrue(((Container) level.getBlockEntity(at)).isEmpty(), "each furnace starts empty");
                colony.hold(WaresContent.STATION, new Claimed.Block(WorldPos.of(level, at))).orElseThrow();
                stations.add(at);
            }
            level.setBlockAndUpdate(fuelStore, Blocks.CHEST.defaultBlockState());
            ((Container) level.getBlockEntity(fuelStore)).setItem(0, new ItemStack(Items.COAL));
            WorldPos fuelAt = WorldPos.of(level, fuelStore);
            colony.hold(WaresContent.STORE, new Claimed.Block(fuelAt)).orElseThrow();
            stores.add(fuelAt);
        } else {
            level.setBlockAndUpdate(station, Blocks.CRAFTING_TABLE.defaultBlockState());
            colony.publish(OWNER, level.dimension(), List.of(new Bench(UUID.randomUUID(), WorkSite.at(level, station),
                Stances.at(level, station.south()))));
        }
        colony.stores(OWNER, level.dimension(), stores);
        person = PersonBody.RESIDENT.get().create(level);
        person.moveTo(helper.absolutePos(new BlockPos(8, 2, 3)).getBottomCenter());
        level.addFreshEntity(person);
        colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
        person.joinColony(colony.id());
        colony.entered(person);
        person.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
        helper.assertTrue(!WorkPriorities.defaults(Vocations.NETWORK_SERVICE).allowed()
                && !person.licences().allows(person.licences().trade(
                    Vocations.required(Vocations.NETWORK_SERVICE)).orElseThrow()),
            "network service defaults off for both colony work settings and existing resident licences");
        labor = new LaborFixture(colony, level);
        Cleanup.on(helper, () -> {
            closed = true;
            labor.close();
            colony.raze();
            person.discard();
            helpers.forEach(ResidentEntity::discard);
        });
        helper.onEachTick(() -> {
            if (!closed && running) {
                labor.tick();
            }
        });
    }

    public void enable(boolean enabled) {
        person.licences().allow(person.licences().trade(Vocations.required(Vocations.NETWORK_SERVICE))
            .orElseThrow(), enabled);
    }

    public void addServiceResident() {
        var level = helper.getLevel();
        ResidentEntity resident = PersonBody.RESIDENT.get().create(level);
        resident.moveTo(person.position());
        level.addFreshEntity(resident);
        colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        colony.entered(resident);
        resident.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
        resident.licences().allow(resident.licences().trade(Vocations.required(Vocations.NETWORK_SERVICE))
            .orElseThrow(), true);
        helpers.add(resident);
    }

    public void clearStoredFuel() {
        Container stock = (Container) helper.getLevel().getBlockEntity(fuelStore);
        stock.clearContent();
        helper.assertTrue(stock.isEmpty(), "the colony's chest has no fuel");
    }

    public void furnaceInputsConsumed() {
        for (BlockPos at : stations) {
            Container furnace = (Container) helper.getLevel().getBlockEntity(at);
            helper.assertTrue(furnace.isEmpty(), "the real furnace spent its raw iron and coal and returned its output");
        }
        Container stock = (Container) helper.getLevel().getBlockEntity(fuelStore);
        helper.assertTrue(Goods.countIn(stock, ItemSpec.of(Items.COAL)) == 0,
            "no unused coal remains in the colony chest: " + stock);
        for (var item : List.of(Items.RAW_IRON, Items.COAL, Items.IRON_INGOT)) {
            helper.assertValueEqual(Goods.countIn(person.pack(), ItemSpec.of(item)), 0L,
                "no network input or output remains in the resident's pack: " + item);
        }
    }

    public void furnaceRecipeInputs() {
        var recipes = new ColonyRecipes(colony);
        recipes.refresh(helper.getLevel().getServer());
        var smelting = recipes.entries().stream().filter(entry -> entry.recipe().outputs().stream()
            .anyMatch(output -> Goods.includes(ItemSpec.of(Items.IRON_INGOT), output.spec())))
            .filter(entry -> entry.recipe().inputs().stream()
                .anyMatch(input -> Goods.includes(input.spec(), ItemSpec.of(Items.RAW_IRON)))).toList();
        helper.assertTrue(!smelting.isEmpty() && smelting.stream().allMatch(entry ->
                entry.recipe().inputs().equals(List.of(new Need(ItemSpec.of(Items.RAW_IRON), 1)))),
            "furnace patterns require only raw iron; fuel remains a colony supply: " + smelting);
    }

    public String diagnosis() {
        return "route=" + labor.route(person).stream().map(node -> node.getClass().getSimpleName()).toList()
            + "; stalls=" + colony.stallsIn(helper.getLevel().dimension()).stream()
                .map(stall -> stall.why() + ":" + stall.goods().map(Object::toString).orElse("")).limit(2).toList()
            + "; pack=" + person.pack().contents() + "; at=" + person.blockPosition();
    }

    public record Bench(UUID key, WorkSite site, Stances stances) implements Workshop {
        @Override
        public ResourceLocation id() {
            return OWNER;
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce) || produce.wanted().item()
                    .filter(ResourceLocation.withDefaultNamespace("diamond")::equals).isEmpty()) {
                return List.of();
            }
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, site, stances, Workload.Once.of(20))
                .needs(new Need(ItemSpec.of(Items.IRON_INGOT), 2), new Need(ItemSpec.of(Items.COPPER_INGOT), 1))
                .gives(new Amount(ItemSpec.of(Items.EMERALD), 1)).done();
            Node mixing = new Node() {
                @Override
                public NodeSpec spec() {
                    return spec;
                }

                @Override
                public Outcome commit(ServerLevel level, Worker worker) {
                    long iron = worker.supplied().stream().filter(stack -> stack.is(Items.IRON_INGOT))
                        .mapToLong(ItemStack::getCount).sum();
                    long copper = worker.supplied().stream().filter(stack -> stack.is(Items.COPPER_INGOT))
                        .mapToLong(ItemStack::getCount).sum();
                    if (iron != 2 || copper != 1) {
                        throw new AssertionError("workshop must consume the exact network inputs: "
                            + worker.supplied());
                    }
                    return new Outcome.Done(List.of(), List.of(new ItemStack(Items.EMERALD)), Optional.empty());
                }
            };
            NodeSpec finishing = NodeSpec.of(UUID.randomUUID(), OWNER, site, stances, Workload.Once.of(20))
                .needs(new Need(ItemSpec.of(Items.EMERALD), 1))
                .gives(new Amount(ItemSpec.of(Items.DIAMOND), 1)).done();
            Node polishing = new Node() {
                @Override
                public NodeSpec spec() {
                    return finishing;
                }

                @Override
                public Outcome commit(ServerLevel level, Worker worker) {
                    if (worker.supplied().size() != 1 || !worker.supplied().getFirst().is(Items.EMERALD)
                            || worker.supplied().getFirst().getCount() != 1) {
                        throw new AssertionError("the next workshop step must consume its carried intermediate");
                    }
                    return new Outcome.Done(List.of(), List.of(new ItemStack(Items.DIAMOND)), Optional.empty());
                }
            };
            return List.of(Refinement.Change.of(Grown.then(mixing, polishing)));
        }
    }
}
