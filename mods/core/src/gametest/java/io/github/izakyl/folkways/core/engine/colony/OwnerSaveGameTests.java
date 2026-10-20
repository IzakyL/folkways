package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.OwnerSave;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OwnerSaveGameTests {

    private static final ResourceLocation OWNER = id("test/versioned_save");
    private static final ResourceLocation FAILED = id("test/failed_save");
    private static final ResourceLocation VALIDATED = id("test/validated_save");
    private static final ResourceLocation OTHER = id("test/raw_save");
    private static final ResourceLocation ABSENT = id("missing_plugin/opaque_save");
    private static final List<Integer> STEPS = new ArrayList<>();
    private static final OwnerSave SAVE = new OwnerSave(OWNER, List.of(
        payload -> step(payload, 0), payload -> step(payload, 1), payload -> step(payload, 2)));
    private static final OwnerSave FAIL = new OwnerSave(FAILED, List.of(payload -> {
        payload.putBoolean("partial_mutation", true);
        return payload;
    }, payload -> { throw new AssertionError("injected migration failure"); }));
    private static final OwnerSave CHECKED = new OwnerSave(VALIDATED, List.of(payload -> payload), payload -> {
        if (payload.contains("invalid")) {
            throw new IllegalArgumentException("injected validation failure");
        }
    });

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(OwnerSaveGameTests.class);
    }

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.save(SAVE);
        event.save(FAIL);
        event.save(CHECKED);
        event.colony(OWNER, scope -> scope.share(new Probe()));
        event.colony(OTHER, scope -> scope.share(new Probe()));
    }

    @GameTest(template = "empty")
    public static void legacyPayloadMigratesInOrderOnceAndRetainsItsFirstOriginal(GameTestHelper helper) {
        STEPS.clear();
        CompoundTag legacy = Writer.of().string("owner", "ordinary legacy field").integer("version", 91)
            .blob("payload", Writer.of().string("unknown", "retained").tag()).tag();
        Keeps keeps = loaded(OWNER, legacy);
        AtomicInteger dirty = dirty(keeps);
        CompoundTag migrated = keeps.kept(SAVE).orElseThrow();
        helper.assertTrue(STEPS.equals(List.of(0, 1, 2)) && migrated.getInt("steps") == 3,
            "legacy owner/version/payload fields are raw version zero, and each migration runs in order");
        helper.assertTrue(dirty.get() == 1 && keeps.kept(OWNER).getCompound("original").equals(legacy),
            "one completed migration commits once and keeps the first original inside its envelope");
        helper.assertTrue(migrated.getCompound("payload").getString("unknown").equals("retained"),
            "migration preserves unknown legacy fields");
        migrated.putBoolean("mutated_read", true);
        keeps.kept(SAVE).orElseThrow();
        helper.assertTrue(STEPS.equals(List.of(0, 1, 2)) && dirty.get() == 1
                && !keeps.kept(SAVE).orElseThrow().contains("mutated_read"),
            "repeated reads do not rerun migrations, expose stored references or mark dirty");
        CompoundTag envelope = keeps.kept(OWNER);
        envelope.putString("unknown_envelope_metadata", "retained");
        Keeps restored = loaded(OWNER, envelope);
        AtomicInteger restoredDirty = dirty(restored);
        CompoundTag changed = restored.kept(SAVE).orElseThrow();
        helper.assertTrue(STEPS.size() == 3 && restoredDirty.get() == 0,
            "loading a current version is idempotent and read-only");
        changed.putInt("new_value", 42);
        restored.keep(SAVE, changed);
        helper.assertTrue(restored.kept(OWNER).getCompound("original").equals(legacy)
                && restored.kept(OWNER).getString("unknown_envelope_metadata").equals("retained"),
            "later writes retain the first backup and unknown envelope metadata");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void absentOwnersStayAbsentOnReadAndLegacyOwnersKeepTheirRawApi(GameTestHelper helper) {
        STEPS.clear();
        Keeps keeps = new Keeps();
        AtomicInteger dirty = dirty(keeps);
        helper.assertTrue(keeps.kept(SAVE).orElseThrow().isEmpty() && keeps.save().isEmpty()
                && STEPS.isEmpty() && dirty.get() == 0,
            "an absent owner's read creates no envelope, backup, migration or dirty state");
        keeps.keep(SAVE, Writer.of().integer("fresh", 1).tag());
        helper.assertTrue(!keeps.kept(OWNER).contains("original") && dirty.get() == 1,
            "the first fresh write creates a versioned payload without inventing an original backup");
        CompoundTag raw = Writer.of().string("unversioned", "compatible").tag();
        keeps.keep(OTHER, raw);
        raw.putBoolean("changed_outside", true);
        helper.assertTrue(!keeps.kept(OTHER).contains("changed_outside"), "raw writes still take defensive copies");
        keeps.keep(OTHER, keeps.kept(OTHER));
        helper.assertTrue(dirty.get() == 2 && !keeps.kept(OTHER).contains("folkways:ownerSave"),
            "legacy owner payloads remain raw, with equal writes leaving them clean");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void intermediateVersionsRunOnlyTheirRemainingStepsAndRetainTheFirstBackup(GameTestHelper helper) {
        STEPS.clear();
        CompoundTag firstOriginal = Writer.of().string("historical", "before any migration").tag();
        CompoundTag versionOne = envelope(OWNER, 1, Writer.of().integer("steps", 1).string("unknown", "kept").tag());
        versionOne.put("original", firstOriginal.copy());
        versionOne.putString("unknown_metadata", "kept");
        Keeps keeps = loaded(OWNER, versionOne);
        AtomicInteger dirty = dirty(keeps);
        CompoundTag current = keeps.kept(SAVE).orElseThrow();
        helper.assertTrue(STEPS.equals(List.of(1, 2)) && current.getInt("steps") == 3
                && current.getString("unknown").equals("kept") && dirty.get() == 1,
            "a version one envelope must skip its already completed version zero migration");
        helper.assertTrue(keeps.kept(OWNER).getCompound("original").equals(firstOriginal)
                && keeps.kept(OWNER).getString("unknown_metadata").equals("kept")
                && versionOne.getInt("version") == 1,
            "an additional upgrade must retain the first backup and unknown metadata without changing its input");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void futureAndMalformedEnvelopesAreProtectedWithoutDirtying(GameTestHelper helper) {
        List<CompoundTag> invalid = new ArrayList<>();
        invalid.add(envelope(OWNER, 99, new CompoundTag()));
        invalid.add(envelope(OWNER, -1, new CompoundTag()));
        CompoundTag textVersion = envelope(OWNER, SAVE.version(), new CompoundTag());
        textVersion.putString("version", "3");
        invalid.add(textVersion);
        CompoundTag fractionalVersion = envelope(OWNER, SAVE.version(), new CompoundTag());
        fractionalVersion.putDouble("version", 3.5);
        invalid.add(fractionalVersion);
        CompoundTag wrongOwner = envelope(OTHER, SAVE.version(), new CompoundTag());
        invalid.add(wrongOwner);
        CompoundTag wrongFormat = envelope(OWNER, SAVE.version(), new CompoundTag());
        wrongFormat.putString("folkways:ownerSave", "future-format");
        invalid.add(wrongFormat);
        CompoundTag wrongMarker = envelope(OWNER, SAVE.version(), new CompoundTag());
        wrongMarker.putInt("folkways:ownerSave", 1);
        invalid.add(wrongMarker);
        CompoundTag missingPayload = envelope(OWNER, SAVE.version(), new CompoundTag());
        missingPayload.remove("payload");
        invalid.add(missingPayload);
        CompoundTag wrongBackup = envelope(OWNER, SAVE.version(), new CompoundTag());
        wrongBackup.putString("original", "not a compound");
        invalid.add(wrongBackup);
        for (CompoundTag blob : invalid) {
            Keeps keeps = loaded(OWNER, blob);
            AtomicInteger dirty = dirty(keeps);
            helper.assertTrue(keeps.kept(SAVE).isEmpty() && !keeps.allowed(OWNER),
                "invalid or future envelopes must disable their owner");
            helper.assertTrue(refused(() -> keeps.keep(OWNER, new CompoundTag()))
                    && refused(() -> keeps.keep(SAVE, new CompoundTag())), "both writing APIs must fail closed");
            helper.assertTrue(keeps.kept(OWNER).equals(blob) && dirty.get() == 0,
                "failed reads and refused writes must retain the exact blob without dirtying");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFailedMigrationRollsBackAllStepsAndDoesNotBlockOtherOwners(GameTestHelper helper) {
        CompoundTag original = Writer.of().string("untouched", "source").tag();
        Keeps keeps = new Keeps();
        keeps.load(Reader.of(Writer.of().blob(FAILED.toString(), original).blob(OWNER.toString(), original.copy()).tag()));
        AtomicInteger dirty = dirty(keeps);
        helper.assertTrue(keeps.kept(FAIL).isEmpty() && keeps.kept(FAIL).isEmpty()
                && keeps.kept(FAILED).equals(original) && dirty.get() == 0,
            "a failure after mutating a private intermediate must not publish a partial result or backup");
        helper.assertTrue(keeps.kept(SAVE).isPresent() && dirty.get() == 1,
            "another owner's successful migration must still run and commit");
        helper.assertTrue(keeps.kept(FAILED).equals(original) && !original.contains("partial_mutation"),
            "successful owners cannot change the failed owner's saved source");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void currentPayloadValidationAlsoProtectsInvalidDataAndWrites(GameTestHelper helper) {
        CompoundTag invalid = envelope(VALIDATED, CHECKED.version(), Writer.of().flag("invalid", true).tag());
        Keeps keeps = loaded(VALIDATED, invalid);
        AtomicInteger dirty = dirty(keeps);
        helper.assertTrue(keeps.kept(CHECKED).isEmpty() && dirty.get() == 0
                && keeps.kept(VALIDATED).equals(invalid), "invalid current payloads are protected before owner setup");
        Keeps fresh = new Keeps();
        AtomicInteger freshDirty = dirty(fresh);
        helper.assertTrue(refused(() -> fresh.keep(CHECKED, Writer.of().flag("invalid", true).tag()))
                && fresh.save().isEmpty() && freshDirty.get() == 0,
            "validation must reject an invalid write before publishing any envelope");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingPluginsRoundTripTheirRawAndVersionedDataLosslessly(GameTestHelper helper) {
        CompoundTag opaque = envelope(ABSENT, 47, Writer.of().string("unknown", "future payload").tag());
        opaque.put("original", Writer.of().string("unknown_old", "backup").tag());
        CompoundTag raw = Writer.of().string("missing_raw_plugin", "preserved").tag();
        Keeps keeps = new Keeps();
        CompoundTag saved = Writer.of().blob(ABSENT.toString(), opaque).blob(OTHER.toString(), raw).tag();
        keeps.load(Reader.of(saved));
        AtomicInteger dirty = dirty(keeps);
        helper.assertTrue(!keeps.allowed(ABSENT) && refused(() -> keeps.keep(ABSENT, new CompoundTag())),
            "a missing schema cannot enable an older raw owner or replace an opaque envelope");
        CompoundTag roundTrip = keeps.save();
        Keeps restored = new Keeps();
        restored.load(Reader.of(roundTrip));
        roundTrip.getCompound(ABSENT.toString()).putBoolean("external_mutation", true);
        helper.assertTrue(restored.save().equals(saved) && keeps.save().equals(saved) && dirty.get() == 0,
            "missing plugins retain every field and backup across load/save without exposing mutable references");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unreadableOwnersSkipRuntimeSetupWithoutDisablingHealthyOwners(GameTestHelper helper) {
        ColonyData data = new ColonyData();
        CompoundTag future = envelope(OWNER, 99, new CompoundTag());
        data.keeps().load(Reader.of(Writer.of().blob(OWNER.toString(), future).tag()));
        try {
            ColonyRuntime.settle(helper.getLevel().getServer(), data.works());
            helper.assertTrue(data.works().settled() && data.works().service(OWNER, Probe.class).isEmpty()
                    && data.works().service(OTHER, Probe.class).isPresent(),
                "only the unreadable owner must skip service, tick and callback registration");
            helper.assertTrue(refused(() -> data.works().keep(OWNER, new CompoundTag()))
                    && data.works().kept(OWNER).equals(future), "the public raw write cannot bypass protection");
        } finally {
            data.works().closed(Closing.SHUTDOWN);
            ColonyWays.forget(data.colonyId());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void relocationMovesOnlyTheLivePayloadAndNeverItsFirstBackup(GameTestHelper helper) {
        WorldPos old = WorldPos.of(Level.OVERWORLD, new BlockPos(12, 80, 12));
        WorldPos moved = old.at(old.cell().east());
        ListTag list = new ListTag();
        list.add(old.save());
        CompoundTag legacy = Writer.of().blob("point", old.save()).tag();
        legacy.put("list", list);
        Keeps keeps = loaded(OWNER, legacy);
        keeps.kept(SAVE).orElseThrow();
        AtomicInteger dirty = dirty(keeps);
        keeps.relocate(Map.of(old, moved));
        CompoundTag payload = keeps.kept(SAVE).orElseThrow();
        helper.assertTrue(WorldPos.load(Reader.of(payload.getCompound("point"))).orElseThrow().equals(moved)
                && WorldPos.load(Reader.of(payload.getList("list", 10).getCompound(0))).orElseThrow().equals(moved),
            "recursive coordinate migration updates compounds and list payload entries");
        helper.assertTrue(keeps.kept(OWNER).getCompound("original").equals(legacy)
                && !keeps.references(Set.of(old)) && dirty.get() == 1,
            "the original remains unchanged and cannot make a stale coordinate appear live");
        keeps.relocate(Map.of(old, moved));
        helper.assertTrue(dirty.get() == 1, "repeating the same coordinate migration is idempotent");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedDataRejectsRelocationBeforeAnyHoldingMoves(GameTestHelper helper) {
        WorldPos old = WorldPos.of(Level.OVERWORLD, new BlockPos(12, 80, 12));
        WorldPos moved = old.at(old.cell().east());
        for (ResourceLocation owner : List.of(OWNER, FAILED, ABSENT)) {
            ColonyData data = new ColonyData();
            CompoundTag opaque = owner.equals(FAILED) ? Writer.of().blob("point", old.save()).tag()
                : envelope(owner, 99, Writer.of().blob("point", old.save()).tag());
            data.holdings().hold(null, OTHER, new Held.Point(old)).orElseThrow();
            data.keeps().load(Reader.of(Writer.of().blob(owner.toString(), opaque).tag()));
            AtomicInteger dirty = new AtomicInteger();
            data.setDirtyCallback(dirty::incrementAndGet);
            helper.assertTrue(refused(() -> data.relocate(null, Map.of(old, moved))),
                "future, failed and unavailable owner saves must reject whole-colony relocation");
            helper.assertTrue(data.holdings().at(old).isPresent() && data.holdings().at(moved).isEmpty()
                    && data.works().kept(owner).equals(opaque) && dirty.get() == 0,
                "rejected relocation must move no holdings, rewrite no opaque blob and leave the colony clean");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unreadableRootSchemasStayCleanAndReturnTheExactOriginalEvenOnForcedSave(GameTestHelper helper) {
        List<CompoundTag> invalid = new ArrayList<>();
        for (int version : List.of(SaveVersion.CURRENT + 1, -1, 0)) {
            invalid.add(Writer.of().integer("schemaVersion", version).string("future_field", "preserved").tag());
        }
        invalid.add(Writer.of().string("schemaVersion", "not an integer").string("future_field", "preserved").tag());
        for (CompoundTag original : invalid) {
            ListTag colonies = new ListTag();
            colonies.add(new ColonyData().save(new CompoundTag()));
            original.put("colonies", colonies);
            ColonyRegistry registry = ColonyRegistry.load(original, null);
            registry.setDirty();
            helper.assertTrue(!registry.isDirty() && registry.colonies().isEmpty()
                    && refused(() -> registry.mint(null))
                    && refused(() -> registry.raze(null, UUID.randomUUID())),
                "unreadable root data must refuse writes, minting and deletion without becoming dirty");
            CompoundTag saved = registry.save(new CompoundTag(), null);
            saved.putBoolean("outside_mutation", true);
            helper.assertTrue(registry.save(new CompoundTag(), null).equals(original),
                "even a forced root save must return a private copy of the complete original blob");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unversionedAndCurrentRootSavesRemainReadableWithoutDirtying(GameTestHelper helper) {
        for (boolean versioned : List.of(false, true)) {
            ColonyData data = new ColonyData();
            CompoundTag opaque = envelope(ABSENT, 47, Writer.of().string("opaque", "retained").tag());
            data.keeps().load(Reader.of(Writer.of().blob(ABSENT.toString(), opaque).tag()));
            CompoundTag root = Writer.of().children("colonies", List.of(data), colony -> colony.save(new CompoundTag()))
                .uuids("razed", List.of()).tag();
            if (versioned) {
                SaveVersion.write(root);
            }
            ColonyRegistry registry = ColonyRegistry.load(root, null);
            helper.assertTrue(!registry.isDirty() && registry.find(data.colonyId()).isPresent()
                    && registry.find(data.colonyId()).orElseThrow().works().kept(ABSENT).equals(opaque),
                "legacy and current root schemas must retain missing owners without loading or dirtying them");
        }
        helper.succeed();
    }

    private static CompoundTag step(CompoundTag payload, int from) {
        if (payload.getInt("steps") != from) {
            throw new IllegalStateException("out of order migration");
        }
        STEPS.add(from);
        payload.putInt("steps", from + 1);
        return payload;
    }

    private static CompoundTag envelope(ResourceLocation owner, int version, CompoundTag payload) {
        return Writer.of().string("folkways:ownerSave", "folkways:owner-save/1").id("owner", owner)
            .integer("version", version).blob("payload", payload).tag();
    }

    private static Keeps loaded(ResourceLocation owner, CompoundTag payload) {
        Keeps keeps = new Keeps();
        keeps.load(Reader.of(Writer.of().blob(owner.toString(), payload).tag()));
        return keeps;
    }

    private static AtomicInteger dirty(Keeps keeps) {
        AtomicInteger dirty = new AtomicInteger();
        keeps.setDirtyListener(dirty::incrementAndGet);
        return dirty;
    }

    private static boolean refused(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException | IllegalArgumentException expected) {
            return true;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }

    private static final class Probe { }
}
