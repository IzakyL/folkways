package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Cadence;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.Keeps;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RosterSaveGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RosterSaveGameTests.class);
    }

    @GameTest(template = "empty")
    public static void legacyRailRosterMigratesAndRealRailWritesPreserveUnknownFields(GameTestHelper helper) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        CompoundTag legacy = Writer.of().uuids("trains", List.of(first, second))
            .blob("unknown_extension", Writer.of().string("kept", "yes").tag()).tag();
        ColonyData data = restored(legacy);
        AtomicInteger dirty = new AtomicInteger();
        data.keeps().setDirtyListener(dirty::incrementAndGet);
        RailPresence presence = presence(data);
        helper.assertTrue(presence.taken().equals(List.of(first, second)) && dirty.get() == 1,
            "the real rail presence must read the legacy train roster through its successful version zero migration");
        helper.assertTrue(presence.take(third) && presence.release(first), "real roster mutations must still work");
        CompoundTag envelope = data.works().kept(RailContent.ID);
        helper.assertTrue(envelope.getInt("version") == Roster.SAVE.version()
                && Reader.of(envelope.getCompound("payload")).uuids("trains").equals(List.of(second, third))
                && envelope.getCompound("payload").getCompound("unknown_extension").getString("kept").equals("yes")
                && envelope.getCompound("original").equals(legacy),
            "rail writes must use the envelope and retain unknown fields and the original UUID roster backup");
        ColonyData reloaded = restored(envelope);
        AtomicInteger readDirty = new AtomicInteger();
        reloaded.keeps().setDirtyListener(readDirty::incrementAndGet);
        RailPresence again = presence(reloaded);
        helper.assertTrue(again.taken().equals(List.of(second, third)) && readDirty.get() == 0
                && reloaded.works().kept(RailContent.ID).equals(envelope),
            "the migrated roster must reload idempotently without another migration or dirty notification");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unreadableRailDataCannotStartAnEmptyRailPresenceOrBeOverwritten(GameTestHelper helper) {
        CompoundTag future = envelope(99, Writer.of().uuids("trains", List.of(UUID.randomUUID())).tag());
        CompoundTag badLegacy = Writer.of().string("trains", "not a UUID list").tag();
        ListTag invalidIds = new ListTag();
        invalidIds.add(new IntArrayTag(new int[] {1, 2, 3}));
        CompoundTag badUuid = new CompoundTag();
        badUuid.put("trains", invalidIds);
        CompoundTag badCurrent = envelope(Roster.SAVE.version(), badLegacy.copy());
        for (CompoundTag original : List.of(future, badLegacy, badUuid, badCurrent)) {
            ColonyData data = restored(original);
            AtomicInteger dirty = new AtomicInteger();
            data.keeps().setDirtyListener(dirty::incrementAndGet);
            helper.assertTrue(data.works().kept(Roster.SAVE).isEmpty(), "unreadable rail data must fail closed");
            helper.assertTrue(refused(() -> presence(data))
                    && refused(() -> data.works().keep(RailContent.ID, new CompoundTag()))
                    && refused(() -> data.works().keep(Roster.SAVE, new CompoundTag())),
                "rail must neither start with an empty substitute nor bypass protection through either write API");
            helper.assertTrue(data.works().kept(RailContent.ID).equals(original) && dirty.get() == 0,
                "future, malformed legacy and malformed current rosters must preserve every original byte-equivalent field");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void railCoordinateRelocationPreservesItsRosterAndHistoricalBackup(GameTestHelper helper) {
        UUID train = UUID.randomUUID();
        WorldPos old = WorldPos.of(Level.OVERWORLD, new BlockPos(12, 80, 12));
        WorldPos moved = old.at(old.cell().east());
        CompoundTag legacy = Writer.of().uuids("trains", List.of(train)).blob("extension_platform", old.save()).tag();
        ColonyData data = restored(legacy);
        data.works().kept(Roster.SAVE).orElseThrow();
        relocate(data.keeps(), Map.of(old, moved));
        RailPresence presence = presence(data);
        helper.assertTrue(presence.holds(train) && WorldPos.load(Reader.of(data.works().kept(Roster.SAVE)
                .orElseThrow().getCompound("extension_platform"))).orElseThrow().equals(moved),
            "the live rail payload must retain its train identities and move nested extension coordinates");
        helper.assertTrue(data.works().kept(RailContent.ID).getCompound("original").equals(legacy),
            "relocation cannot rewrite the first legacy roster backup");
        helper.succeed();
    }

    private static CompoundTag envelope(int version, CompoundTag payload) {
        return Writer.of().string("folkways:ownerSave", "folkways:owner-save/1").id("owner", RailContent.ID)
            .integer("version", version).blob("payload", payload).tag();
    }

    private static RailPresence presence(ColonyData data) {
        try {
            return new RailPresence(data.works());
        } finally {
            Cadence.forget(data.works());
        }
    }

    private static ColonyData restored(CompoundTag rail) {
        CompoundTag saved = new ColonyData().save(new CompoundTag());
        saved.getCompound("keeps").put(RailContent.ID.toString(), rail.copy());
        try {
            var load = ColonyData.class.getDeclaredMethod("load", Reader.class);
            load.setAccessible(true);
            return ((Optional<?>) load.invoke(null, Reader.of(saved))).map(ColonyData.class::cast).orElseThrow();
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static void relocate(Keeps keeps, Map<WorldPos, WorldPos> moved) {
        try {
            var relocate = Keeps.class.getDeclaredMethod("relocate", Map.class);
            relocate.setAccessible(true);
            relocate.invoke(keeps, moved);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static boolean refused(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }
}
