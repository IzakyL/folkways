package io.github.izakyl.folkways.plugins.person.name;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.look.LookPool;
import io.github.izakyl.folkways.plugins.person.look.LookSet;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import io.github.izakyl.folkways.plugins.person.name.ColonyNames.Part;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentPoolGameTests {

    private static final String TEMPLATE = "empty";
    private static final int PINNED = 45;

    private static final ResourceLocation DEFAULT_SET = id("default");
    private static final ResourceLocation SLIM = id("slim");
    private static final ResourceLocation ALEX_SLIM = id("alex_slim");
    private static final ResourceLocation ALEX_WIDE = id("alex_wide");

    private ResidentPoolGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentPoolGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void theNamePoolStartsAsTheDataPacksAndIsEditedEntryByEntry(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        try {
            helper.assertValueEqual(ColonyNames.of(colony, registries), ResidentNames.merged(registries),
                "an untouched colony draws from every name set the data packs offer");
            helper.assertTrue(ColonyNames.add(colony, registries, Part.GIVEN, "  Zed "), "a new name goes in");
            helper.assertFalse(ColonyNames.add(colony, registries, Part.GIVEN, "Zed"), "the same name twice does not");
            helper.assertFalse(ColonyNames.add(colony, registries, Part.SURNAME, "x".repeat(NamePool.MAX_LENGTH + 1)),
                "a name longer than a name may be does not");
            helper.assertTrue(ColonyNames.of(colony, registries).names().contains("Zed"), "the added name is kept, trimmed");
            String surname = ColonyNames.of(colony, registries).surnames().get(0);
            helper.assertTrue(ColonyNames.remove(colony, registries, Part.SURNAME, surname), "a surname comes out");
            helper.assertFalse(ColonyNames.of(colony, registries).surnames().contains(surname), "and stays out");

            NamePool set = ResidentNames.set(registries, DEFAULT_SET).orElseThrow();
            ColonyNames.replace(colony, set);
            helper.assertValueEqual(ColonyNames.of(colony, registries), set, "replacing takes the set whole");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aNewcomerKeepsTheNameItDrewWhateverThePoolBecomes(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        ColonyNames.replace(colony, new NamePool(List.of("Ada"), List.of("Lovelace")));
        ResidentEntity person = spawn(helper, colony);
        helper.runAfterDelay(PINNED, () -> {
            helper.assertValueEqual(person.getDisplayName().getString(), "Ada Lovelace",
                "a newcomer is named from the colony's pool");
            ColonyNames.replace(colony, new NamePool(List.of("Bob"), List.of()));
            helper.runAfterDelay(PINNED, () -> {
                try {
                    helper.assertValueEqual(person.getDisplayName().getString(), "Ada Lovelace",
                        "a resident's name is his own once drawn");
                    person.setNames("Grace", "");
                    helper.assertValueEqual(person.getDisplayName().getString(), "Grace",
                        "with no surname the name is the given name alone");
                    CompoundTag saved = new CompoundTag();
                    person.addAdditionalSaveData(saved);
                    ResidentEntity reloaded = PersonBody.RESIDENT.get().create(helper.getLevel());
                    reloaded.readAdditionalSaveData(saved);
                    helper.assertValueEqual(reloaded.givenName(), "Grace", "the name is saved with the resident");
                } finally {
                    person.discard();
                    colony.raze();
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aLookSetReplacesThePoolAndEditsFollowIt(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        try {
            helper.assertTrue(LookPool.of(colony).allows(ALEX_WIDE), "an untouched colony draws every look");
            LookSet slim = LookSet.of(registries, SLIM).orElseThrow(() ->
                new AssertionError("the built-in slim look set is missing"));
            for (ResourceLocation look : slim.looks()) {
                helper.assertTrue(ResidentLooks.entry(registries, ResourceKey.create(ResidentLooks.REGISTRY, look))
                    .isPresent(), "a built-in set names a look that does not exist: " + look);
            }
            LookPool.replace(colony, slim.looks());
            helper.assertTrue(LookPool.of(colony).allows(ALEX_SLIM), "the set's looks are in");
            helper.assertFalse(LookPool.of(colony).allows(ALEX_WIDE), "and only those");
            LookPool.set(colony, ALEX_WIDE, true);
            LookPool.set(colony, ALEX_SLIM, false);
            helper.assertTrue(LookPool.of(colony).allows(ALEX_WIDE), "a look added after replacing is in");
            helper.assertFalse(LookPool.of(colony).allows(ALEX_SLIM), "a look struck after replacing is out");

            ListTag excluded = new ListTag();
            excluded.add(StringTag.valueOf(ALEX_WIDE.toString()));
            CompoundTag older = new CompoundTag();
            older.put("excluded", excluded);
            helper.assertFalse(LookPool.from(older).allows(ALEX_WIDE), "a pool saved before sets still strikes its looks");
            helper.assertTrue(LookPool.from(older).allows(ALEX_SLIM), "and keeps the rest");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void aResidentKeepsHisLookWhenThePoolDropsIt(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        LookPool.replace(colony, List.of(ALEX_SLIM));
        ResidentEntity person = spawn(helper, colony);
        person.setLookId(ResourceKey.create(ResidentLooks.REGISTRY, ALEX_WIDE));
        helper.runAfterDelay(PINNED, () -> {
            try {
                Optional<ResourceKey<ResidentLook>> look = person.lookId();
                helper.assertValueEqual(look.map(ResourceKey::location), Optional.of(ALEX_WIDE),
                    "a look chosen for one resident stays, even outside the pool");            } finally {
                person.discard();
                colony.raze();
            }
            helper.succeed();
        });
    }

    private static ResidentEntity spawn(GameTestHelper helper, Colony colony) {
        ResidentEntity person = PersonBody.RESIDENT.get().create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        person.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.getLevel().addFreshEntity(person);
        colony.hold(PersonContent.ID, Held.Entity.of(person));
        return person;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path);
    }
}
