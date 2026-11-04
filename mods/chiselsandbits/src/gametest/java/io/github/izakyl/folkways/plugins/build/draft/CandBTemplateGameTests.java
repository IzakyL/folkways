package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.chiselsandbits.CandBConfig;
import io.github.izakyl.folkways.chiselsandbits.CandBMaterials;
import io.github.izakyl.folkways.chiselsandbits.CandBSchematicBridge;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.chiselsandbits.FolkwaysChiselsAndBitsMod;
import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.core.api.structure.Voxelizer;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.plugins.build.CandBGameTests;
import io.github.izakyl.folkways.plugins.build.CandBTestData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.api.config.IServerConfiguration;
import mod.chiselsandbits.api.multistate.StateEntrySize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysChiselsAndBitsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CandBTemplateGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CandBTemplateGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void pavilionDrawsContinuousRoofInBothFrames(GameTestHelper helper) throws Exception {
        var id = ResourceLocation.parse("folkways_chiselsandbits:pavilion");
        Pattern pattern = Patterns.find(id).orElseThrow();
        helper.assertValueEqual(pattern.detail(), "bits", "integration pattern uses bits");
        for (String language : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            var words = pattern.face().words().get(language);
            helper.assertTrue(words != null && words.containsKey("title"), "pavilion has a title in " + language);
            for (var setting : pattern.knobs().settings()) {
                helper.assertTrue(words.containsKey(setting.key()), "translated knob " + setting.key());
            }
        }
        CompoundTag defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                defaults.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        for (Direction facing : List.of(Direction.NORTH, Direction.EAST)) {
            var commission = new Commission(new Hint.Zone(BlockPos.ZERO, new BlockPos(8, 0, 8)),
                Chosen.load(pattern.knobs(), defaults), World.NONE, facing, 73);
            long began = System.nanoTime();
            Drawn drawn = Drafting.draw(pattern, commission).join();
            long millis = (System.nanoTime() - began) / 1_000_000;
            helper.assertTrue(drawn instanceof Drawn.Ready, "pavilion draws facing " + facing + ": " + drawn);
            var cells = ((Drawn.Ready) drawn).draft().cells();
            helper.assertTrue(cells.stream().anyMatch(cell -> cell.payload().present()), "curves have partial cells");
            helper.assertTrue(cells.stream().anyMatch(cell -> !cell.payload().present()), "platform has whole cells");
            if (facing == Direction.NORTH) {
                var ready = (Drawn.Ready) drawn;
                BlockPos formerlyFloating = new BlockPos(2, 3, 7).subtract(ready.corner());
                var fragment = cells.stream().filter(cell -> cell.offset().equals(formerlyFloating)).findFirst();
                try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
                    helper.assertTrue(fragment.isEmpty() || fragment.get().payload().present()
                        && CandBStructureCodec.decode(fragment.get().payload().tag())
                            .getBlockInformation(15, 0, 8).isAir(), "bracket cut leaves no floating lower tip");
                }
                var partial = cells.stream().filter(cell -> cell.payload().present()).toList();
                var unique = new HashSet<CompoundTag>();
                int resolution = Voxelizer.installed().orElseThrow().resolution();
                int perBlock = resolution * resolution * resolution;
                long voxels = (long) (cells.size() - partial.size()) * perBlock;
                try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
                    for (var cell : partial) {
                        unique.add(cell.payload().tag());
                        voxels += CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                            .mapToLong(Integer::longValue).sum();
                    }
                }
                var bytes = new ByteArrayOutputStream();
                var draft = ((Drawn.Ready) drawn).draft();
                NbtIo.writeCompressed(DraftTemplate.of(draft, 3955), bytes);
                LoggerFactory.getLogger("folkways-patterns").info(
                    "pavilion metrics extent={} partial={} whole={} voxels={} equivalent={} millis={}"
                        + " payloadBytes={} gzipBytes={} unique={} ratio={}",
                    draft.extent(), partial.size(), cells.size() - partial.size(), voxels, (double) voxels / perBlock,
                    millis, partial.stream().mapToLong(cell -> cell.payload().bytes()).sum(), bytes.size(),
                    unique.size(), (double) unique.size() / partial.size());
                CandBGameTests.reportPatternBudgets(helper, DraftTemplate.of(draft, 3955));
            }
        }
        var frame = Frame.over(new BoundingBox(0, 0, 0, 10, 4, 10), Direction.NORTH, false);
        var roof = new Solid.CurvedHip(frame, new Vec3i(11, 5, 11), 2.6, 1.15, 0.25);
        var point = frame.at(0.1, 1.2, 0.1);
        helper.assertTrue(roof.holds(point.x, point.y, point.z), "corner is raised above the middle eave");
        point = frame.at(0.1, 1.2, 5.5);
        helper.assertTrue(!roof.holds(point.x, point.y, point.z), "middle eave stays low");
        point = frame.at(0.1, 0.28, 5.5);
        helper.assertTrue(!roof.holds(point.x, point.y, point.z), "low part of a subcell curve");
        point = frame.at(0.9, 0.28, 5.5);
        helper.assertTrue(roof.holds(point.x, point.y, point.z), "curve changes inside the same block");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void templateImportStampFitAndExportKeepVoxelCoordinates(GameTestHelper helper) {
        Piece piece = piece();
        Scope scope = Scope.over(new BoundingBox(0, 0, 0, 0, 0, 0));
        Draft draft = fitted(helper, piece, scope);
        helper.assertValueEqual(draft.cells().size(), 1, "one template cell");
        CandBTestData.sameVoxels(helper, CandBTestData.sculpture(),
            CandBStructureCodec.decode(draft.cells().getFirst().payload().tag()));
        CompoundTag exported = DraftTemplate.of(draft, 3955);
        var written = exported.getList("blocks", CompoundTag.TAG_COMPOUND).getCompound(0).getCompound("nbt");
        CandBTestData.sameVoxels(helper, CandBTestData.sculpture(), CandBStructureCodec.decode(written));
        CompoundTag absent = CandBTestData.structure(CandBStructureCodec.encode(CandBTestData.sculpture()));
        absent.getList("palette", CompoundTag.TAG_COMPOUND).getCompound(0).putString("Name", "absent:carving");
        boolean refused = false;
        try { DraftTemplate.pieceOf(absent); } catch (StructureCompat.Failure expected) {
            refused = true;
        }
        helper.assertTrue(refused, "an external configured template waits for its missing block mod");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void tiledAndStretchedTemplatesCopyIndependentPayloads(GameTestHelper helper) {
        Piece piece = piece();
        Piece stretched = new Piece(piece.cells(), piece.size(), List.of(Set.of(0), Set.of(), Set.of()), piece.payloads())
            .stretchedTo(new int[] {3, 1, 1});
        Piece tiled = piece.tiledTo(new int[] {2, 1, 2});
        for (Piece copied : List.of(stretched, tiled)) {
            Draft draft = fitted(helper, copied, Scope.over(new BoundingBox(0, 0, 0,
                copied.size().getX() - 1, copied.size().getY() - 1, copied.size().getZ() - 1)));
            helper.assertValueEqual(draft.cells().size(), copied.cells().size(), "every copied cell reaches the drawing");
            for (Draft.Cell cell : draft.cells()) {
                CandBTestData.sameVoxels(helper, CandBTestData.sculpture(), CandBStructureCodec.decode(cell.payload().tag()));
            }
            CompoundTag mutable = draft.cells().getFirst().payload().tag();
            mutable.remove("data");
            helper.assertTrue(draft.cells().stream().allMatch(cell -> cell.payload().tag().contains("data")),
                "one returned tag cannot mutate any copy of a piece");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void templatePayloadsFollowAllHorizontalFramesAndMirrors(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (boolean mirrored : List.of(false, true)) {
                Frame frame = Frame.over(new BoundingBox(0, 0, 0, 0, 0, 0), facing, mirrored);
                Scope scope = new Scope(frame, new Vec3i(1, 1, 1), "", "", mirrored);
                Draft draft = fitted(helper, piece(), scope);
                var expected = CandBTestData.sculpture();
                Rotation rotation = switch (facing) {
                    case EAST -> Rotation.CLOCKWISE_90;
                    case SOUTH -> Rotation.CLOCKWISE_180;
                    case WEST -> Rotation.COUNTERCLOCKWISE_90;
                    default -> Rotation.NONE;
                };
                CandBStructureCodec.turn(expected, mirrored ? Mirror.FRONT_BACK : Mirror.NONE, rotation);
                CandBTestData.sameVoxels(helper, expected, CandBStructureCodec.decode(draft.cells().getFirst().payload().tag()));
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void actualTemplateVocabularyPreservesPayloadsThroughFit(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("local", "test_chiseled_template");
        Templates.adoptOwn(Map.of(id, CandBTestData.structure(CandBStructureCodec.encode(CandBTestData.sculpture()))));
        try {
            Pattern pattern = Pattern.parse(ResourceLocation.fromNamespaceAndPath("folkways", "test_chiseled_stamp"), """
                detail = "bits"
                def draw(site):
                    return [stamp("carving", template("local:test_chiseled_template", stretch = {"x": [0]}), site.zone, fit = "stretch")]
                """).orElseThrow();
            Drawn drawn = pattern.drawOn(new Commission(new Hint.Zone(BlockPos.ZERO, new BlockPos(2, 0, 0)),
                Chosen.load(pattern.knobs(), new CompoundTag()), World.NONE, Direction.NORTH, 0));
            helper.assertTrue(drawn instanceof Drawn.Ready, "the Starlark template/stamp vocabulary accepts the payload: " + drawn);
            Draft draft = ((Drawn.Ready) drawn).draft();
            helper.assertValueEqual(draft.cells().size(), 3, "the template stretches to all three cells");
            for (Draft.Cell cell : draft.cells()) {
                CandBTestData.sameVoxels(helper, CandBTestData.sculpture(), CandBStructureCodec.decode(cell.payload().tag()));
            }
        } finally {
            Templates.adoptOwn(Map.of());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void bitsPatternSamplesCurvesAndKeepsWholeInteriors(GameTestHelper helper) throws Exception {
        String source;
        try (var input = CandBTemplateGameTests.class.getResourceAsStream(
                "/data/folkways/folkways/pattern/test_bits.star")) {
            source = new String(Objects.requireNonNull(input).readAllBytes(),
                StandardCharsets.UTF_8);
        }
        Pattern pattern = Pattern.parse(ResourceLocation.parse("folkways:test_bits"), source).orElseThrow();
        helper.assertValueEqual(pattern.grain(), Pattern.DEFAULT_GRAIN, "omitted grain uses the shared default");
        for (String declaration : List.of("grain = 3", "grain = 0", "grain = 8.0", "grain = True",
                "grain = 32", "grain = None", "grain = \"8\"")) {
            var errors = new ArrayList<String>();
            helper.assertTrue(Pattern.parse(ResourceLocation.parse("folkways:bad_grain"),
                declaration + "\n" + source, id -> Optional.empty(), errors::add).isEmpty(),
                "invalid grain fails to parse: " + declaration);
            helper.assertTrue(errors.stream().anyMatch(error -> error.contains("grain")),
                "grain refusal explains the declaration");
        }
        for (String detail : List.of("", "detail = \"blocks\"\n")) {
            helper.assertTrue(Pattern.parse(ResourceLocation.parse("folkways:blocks_grain"),
                "grain = 8\n" + detail + "def draw(site): return []").isEmpty(),
                "block patterns cannot declare grain");
        }
        for (int grain : List.of(16, 8, 4)) {
            helper.assertValueEqual(Pattern.parse(ResourceLocation.parse("folkways:valid_grain"),
                "grain = " + grain + "\n" + source).orElseThrow().grain(), grain, "explicit grain accepted");
        }
        Commission commission = new Commission(new Hint.Zone(BlockPos.ZERO, new BlockPos(3, 2, 4)),
            Chosen.load(pattern.knobs(), new CompoundTag()), World.NONE, Direction.NORTH, 73);
        Drawn drawn = Drafting.draw(pattern, commission).join();
        helper.assertTrue(drawn instanceof Drawn.Ready, "bits draws on the drafting thread: " + drawn);
        var ready = (Drawn.Ready) drawn;
        var box = new BoundingBox(0, 0, 0, 3, 2, 4);
        Solid shape = new Solid.Ellipsoid(Frame.over(box, Direction.NORTH, false), new Vec3i(4, 3, 5), true);
        verifySamples(helper, ready, List.of(shape), false);
        helper.assertTrue(ready.draft().cells().stream().anyMatch(cell -> cell.payload().present()),
            "curved cells have payloads");
        helper.assertTrue(ready.draft().cells().stream().anyMatch(cell -> !cell.payload().present()),
            "interiors stay whole");
        Pattern blocks = Pattern.parse(ResourceLocation.parse("folkways:test_blocks"),
            source.replace("detail = \"bits\"", "")).orElseThrow();
        var old = (Drawn.Ready) blocks.drawOn(commission);
        helper.assertTrue(old.draft().cells().stream().noneMatch(cell -> cell.payload().present()),
            "default stays block fitted");
        Pattern explicit = Pattern.parse(ResourceLocation.parse("folkways:test_explicit_blocks"),
            source.replace("\"bits\"", "\"blocks\"")).orElseThrow();
        helper.assertValueEqual(((Drawn.Ready) explicit.drawOn(commission)).draft(), old.draft(),
            "explicit blocks is identical");
        Pattern uncarvable = Pattern.parse(ResourceLocation.parse("folkways:test_uncarvable"),
            source.replace("minecraft:stone", "minecraft:chest")).orElseThrow();
        Pattern fallback = Pattern.parse(ResourceLocation.parse("folkways:test_fallback"),
            source.replace("minecraft:stone", "minecraft:chest").replace("detail = \"bits\"", "")).orElseThrow();
        helper.assertValueEqual(((Drawn.Ready) uncarvable.drawOn(commission)).draft(),
            ((Drawn.Ready) fallback.drawOn(commission)).draft(), "uncarvable cells keep the original fitting");
        var config = IServerConfiguration.getInstance();
        var bitSize = config.getClass().getDeclaredField("bitSize");
        bitSize.setAccessible(true);
        Object previous = bitSize.get(config);
        try {
            bitSize.set(config, (Supplier<StateEntrySize>)
                () -> StateEntrySize.ONE_EIGHT);
            var coarse = (Drawn.Ready) Drafting.draw(pattern, commission).join();
            helper.assertValueEqual(Voxelizer.installed()
                .orElseThrow().resolution(), 8, "server bit-size controls sampling");
            verifySamples(helper, coarse, List.of(shape), false);
        } finally {
            bitSize.set(config, previous);
        }
        int reads = CandBConfig.BLUEPRINT_READS.get();
        int bytes = CandBConfig.BLUEPRINT_BYTES.get();
        int millis = CandBConfig.BLUEPRINT_MILLIS.get();
        try {
            CandBConfig.BLUEPRINT_READS.set(1);
            CandBConfig.BLUEPRINT_BYTES.set(16384);
            CandBConfig.BLUEPRINT_MILLIS.set(10);
            helper.assertTrue(Drafting.draw(pattern, commission).join() instanceof Drawn.Ready,
                "drafting keeps its own deadline instead of the synchronous blueprint budget");
            boolean refused = false;
            try (var processing = StructureCompat.processing()) {
                var payload = ready.draft().cells().stream().filter(cell -> cell.payload().present())
                    .findFirst().orElseThrow().payload();
                CandBStructureCodec.validated(payload.tag());
                CandBStructureCodec.validated(payload.tag());
            } catch (StructureCompat.Failure expected) {
                refused = true;
            }
            helper.assertTrue(!refused, "server-drawn content reuses verified counts without spending the decode budget");
        } finally {
            CandBConfig.BLUEPRINT_READS.set(reads);
            CandBConfig.BLUEPRINT_BYTES.set(bytes);
            CandBConfig.BLUEPRINT_MILLIS.set(millis);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void bitsComposeMaterialsAcrossFramesAndMirrors(GameTestHelper helper) {
        verifyIntervalSampling(helper);
        var encoder = Voxelizer.installed().orElseThrow();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (boolean mirrored : List.of(false, true)) {
                var box = new BoundingBox(0, 0, 0, 1, 1, 1);
                Frame frame = Frame.over(box, facing, mirrored);
                Solid base = new Solid.Oriented(frame, new Vec3i(2, 2, 2));
                Solid dome = new Solid.Intersection(
                    new Solid.Ellipsoid(frame.moved(0.3, 0.1, 0.2), new Vec3i(1, 2, 1), true), base);
                var parts = List.of(new Massing.Part("base", new Solid.Difference(base, dome), new Skin.Fitted(List.of(
                    ItemFilter.item(ResourceLocation.parse("minecraft:stone"))), Fit.SOLID)),
                    new Massing.Part("trim", dome, new Skin.Fitted(List.of(
                    ItemFilter.item(ResourceLocation.parse("minecraft:oak_planks"))), Fit.SOLID)));
                Drawn drawn;
                try (var processing = StructureCompat.processing()) {
                    drawn = Fitter.fit(Lattice.of(new Massing(parts), encoder).orElseThrow(), BlockPos.ZERO, 1);
                }
                helper.assertTrue(drawn instanceof Drawn.Ready, "composed drawing");
                var ready = (Drawn.Ready) drawn;
                helper.assertTrue(ready.draft().cells().stream().anyMatch(cell -> cell.payload().present()),
                    "two materials share cells");
                verifySamples(helper, ready, List.of(base, dome), true);
                var exported = DraftTemplate.of(ready.draft(), 3955);
                var written = exported.getList("blocks", CompoundTag.TAG_COMPOUND);
                for (int cell = 0; cell < ready.draft().cells().size(); cell++) {
                    var original = ready.draft().cells().get(cell);
                    if (original.payload().present()) {
                        CandBTestData.sameVoxels(helper, CandBStructureCodec.decode(original.payload().tag()),
                            CandBStructureCodec.decode(written.getCompound(cell).getCompound("nbt")));
                    }
                }
            }
        }
        // A narrow curved sliver missed by every original octant still reaches the voxel drawing.
        Frame thinFrame = Frame.over(new BoundingBox(0, 0, 0, 0, 0, 0), Direction.NORTH, false).moved(0.95, 0, 0);
        Solid thin = new Solid.Intersection(new Solid.Ellipsoid(thinFrame, new Vec3i(1, 1, 1), false),
            new Solid.Cuboid(new BoundingBox(0, 0, 0, 0, 0, 0)));
        helper.assertValueEqual(Octants.of(thin, 0, 0, 0), Octants.NONE, "octants miss this sliver");
        var thinPart = new Massing.Part("sliver", thin, new Skin.Fitted(List.of(
            ItemFilter.item(ResourceLocation.parse("minecraft:stone"))), Fit.SOLID));
        var sliver = (Drawn.Ready) Fitter.fit(Lattice.of(new Massing(List.of(thinPart)), encoder, 16).orElseThrow(),
            BlockPos.ZERO, 0);
        helper.assertValueEqual(sliver.draft().cells().size(), 1, "voxel-only cell is included");
        verifySamples(helper, sliver, List.of(thin), false, 16);
        var storage = new StateEntryStorage(8);
        storage.setBlockInformation(1, 2, 3, new BlockInformation(
            Blocks.STONE.defaultBlockState(), Optional.empty()));
        var decoded = CandBStructureCodec.decode(CandBStructureCodec.encode(storage));
        helper.assertValueEqual(decoded.getSize(), 8, "codec retains non-default resolution");
        helper.assertValueEqual(decoded.getBlockInformation(1, 2, 3), storage.getBlockInformation(1, 2, 3),
            "non-default coordinates");
        var bridge = new CandBSchematicBridge();
        var entity = bridge.detached(helper.getLevel().registryAccess(), BlockPos.ZERO, CandBTestData.state(),
            new StructurePayload(CandBStructureCodec.encode(storage)));
        var saved = entity.saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(CandBStructureCodec.decode(saved).getSize(), 8, "saved entity retains resolution");
        CandBStructureCodec.turn(decoded, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
        helper.assertValueEqual(decoded.getBlockInformation(4, 2, 6), storage.getBlockInformation(1, 2, 3),
            "non-default transform");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void representativeBitsDomeReportsItsBudget(GameTestHelper helper) throws Exception {
        Pattern pattern = Pattern.parse(ResourceLocation.parse("folkways:bits_benchmark"), """
            detail = "bits"
            def draw(site):
                return [part("dome", dome(site.zone), ["minecraft:stone"])]
            """).orElseThrow();
        CandBGameTests.checkRecipeDrawing(helper, pattern,
            new Hint.Zone(BlockPos.ZERO, new BlockPos(11, 5, 11)), new CompoundTag());
        var encoder = Voxelizer.installed().orElseThrow();
        var sample = new VoxelFit(encoder);
        var box = new BoundingBox(0, 0, 0, 11, 5, 11);
        var solid = new Solid.Ellipsoid(Frame.over(box, Direction.NORTH, false), new Vec3i(12, 6, 12), true);
        sample.paint(List.of(new Massing.Part("dome", solid,
            new Skin.Fitted(List.of(ItemFilter.item(ResourceLocation.parse("minecraft:stone"))), Fit.SOLID))));
        int perBlock = (int) Math.pow(encoder.resolution(), 3);
        long expectedPartial = sample.cells().values().stream()
            .filter(layers -> layers.getFirst().mask().cardinality() < perBlock).count();
        long expectedBits = sample.cells().values().stream().mapToLong(layers -> layers.getFirst().mask().cardinality())
            .filter(count -> count < perBlock).sum();
        long started = System.nanoTime();
        Drawn drawn = Drafting.draw(pattern, new Commission(new Hint.Zone(BlockPos.ZERO, new BlockPos(11, 5, 11)),
            Chosen.load(pattern.knobs(), new CompoundTag()), World.NONE, Direction.NORTH, 0)).join();
        long millis = (System.nanoTime() - started) / 1_000_000;
        helper.assertTrue(drawn instanceof Drawn.Ready, "the representative bits dome must draw: " + drawn);
        var ready = (Drawn.Ready) drawn;
        long partial = ready.draft().cells().stream().filter(cell -> cell.payload().present()).count();
        long bits = ready.draft().cells().stream().filter(cell -> cell.payload().present())
            .mapToLong(cell -> CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                .mapToLong(Integer::longValue).sum()).sum();
        helper.assertValueEqual(partial, expectedPartial, "all curved cells reach the drawing");
        helper.assertValueEqual(bits, expectedBits, "all sampled bits reach the drawing");
        long payloadBytes = ready.draft().cells().stream().mapToLong(cell -> cell.payload().bytes()).sum();
        var ghosts = new ArrayList<>(ready.draft().cells().stream()
            .map(cell -> new Ghost(ready.corner().offset(cell.offset()), cell.state(), cell.payload())).toList());
        Collections.reverse(ghosts);
        var preview = Ghost.limited(ghosts, 2048, ready.corner());
        helper.assertTrue(payloadBytes > StructurePayload.MAX_PREVIEW_BYTES, "the drawing exceeds the display budget");
        helper.assertTrue(preview.size() < ghosts.size(), "excess preview cells are omitted");
        helper.assertTrue(preview.stream().mapToLong(ghost -> ghost.payload().bytes()).sum()
            <= StructurePayload.MAX_PREVIEW_BYTES, "preview payloads stay bounded");
        helper.assertValueEqual(preview.getFirst().pos(), ghosts.stream()
            .min(Comparator.comparingDouble(ghost -> ghost.pos().distSqr(ready.corner())))
            .orElseThrow().pos(), "the nearest cell is retained first");
        CompoundTag file = DraftTemplate.of(ready.draft(), 3955);
        int uploadBytes;
        try (var output = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(file, output);
            uploadBytes = output.size();
        } catch (IOException failed) {
            throw new AssertionError(failed);
        }
        LoggerFactory.getLogger("folkways-patterns").info(
            "BITS_BENCH width=12 height=6 depth=12 partial={} bits={} whole={} drawMillis={}"
                + " payloadBytes={} uploadBytes={} previewCells={} previewBytes={}",
            partial, bits, ready.draft().cells().size() - partial, millis, payloadBytes, uploadBytes,
            preview.size(), preview.stream().mapToLong(ghost -> ghost.payload().bytes()).sum());
        CandBGameTests.reportPatternBudgets(helper, file);
        started = System.nanoTime();
        Drawn larger = Drafting.draw(pattern, new Commission(new Hint.Zone(BlockPos.ZERO, new BlockPos(29, 14, 29)),
            Chosen.load(pattern.knobs(), new CompoundTag()), World.NONE, Direction.NORTH, 0)).join();
        helper.assertTrue(larger instanceof Drawn.Ready, "the larger curved shape draws");
        var large = (Drawn.Ready) larger;
        long largePartial = large.draft().cells().stream().filter(cell -> cell.payload().present()).count();
        LoggerFactory.getLogger("folkways-patterns").info("BITS_LARGE partial={} cells={} drawMillis={}",
            largePartial, large.draft().cells().size(), (System.nanoTime() - started) / 1_000_000);
        helper.assertTrue(largePartial >= 1500 && largePartial <= 3000, "a roughly two-thousand-partial-cell shape");
        CandBGameTests.reportPatternBudgets(helper, DraftTemplate.of(large.draft(), 3955));
        galleryBitsDrawingTimings(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void toriiDrawsBitsAndTurns(GameTestHelper helper) throws Exception {
        architecturalBits(helper, "torii");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rotundaDrawsBitsAndTurns(GameTestHelper helper) throws Exception {
        architecturalBits(helper, "rotunda");
    }

    private static void architecturalBits(GameTestHelper helper, String name) throws Exception {
        var id = ResourceLocation.fromNamespaceAndPath("folkways_chiselsandbits", name);
        Pattern pattern = Patterns.find(id).orElseThrow();
        helper.assertTrue(pattern.detail().equals("bits"), "integration resource loaded with bit detail");
        for (String language : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            var words = pattern.face().words().get(language);
            helper.assertTrue(words != null && words.containsKey("title"), "localized display name: " + language);
            for (var knob : pattern.knobs().settings()) {
                helper.assertTrue(words.containsKey(knob.key()), "localized knob: " + language + "/" + knob.key());
            }
        }
        var defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                defaults.put(items.key(), ItemSpec.of(items.byDefault().getFirst().id()).save());
            }
        }
        for (Direction facing : List.of(Direction.NORTH, Direction.EAST)) {
            long start = System.nanoTime();
            Drawn drawn = Drafting.draw(pattern, new Commission(
                new Hint.Zone(BlockPos.ZERO, new BlockPos(15, 15, 15)),
                Chosen.load(pattern.knobs(), defaults), World.NONE, facing, 7)).join();
            helper.assertTrue(drawn instanceof Drawn.Ready, name + " draws facing " + facing + ": " + drawn);
            var ready = (Drawn.Ready) drawn;
            long drawMillis = (System.nanoTime() - start) / 1_000_000;
            long partial = ready.draft().cells().stream().filter(cell -> cell.payload().present()).count();
            helper.assertTrue(partial > 0, "curves contain partial cells");
            if (facing == Direction.NORTH) {
                long whole = ready.draft().cells().size() - partial;
                long bits = whole * 4096 + ready.draft().cells().stream().filter(cell -> cell.payload().present())
                    .mapToLong(cell -> CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                        .mapToLong(Integer::longValue).sum()).sum();
                long bytes = ready.draft().cells().stream().mapToLong(cell -> cell.payload().bytes()).sum();
                long unique = ready.draft().cells().stream().filter(cell -> cell.payload().present())
                    .map(Draft.Cell::payload).distinct().count();
                var file = DraftTemplate.of(ready.draft(), 3955);
                try (var output = new ByteArrayOutputStream()) {
                    NbtIo.writeCompressed(file, output);
                    LoggerFactory.getLogger("folkways-patterns").info(
                        "ARCH_BITS {} extent={} partial={} whole={} voxels={} equivalents={} millis={}"
                            + " payloadBytes={} gzipBytes={} unique={} uniqueRatio={}",
                        name, ready.draft().extent().size(), partial, whole, bits, bits / 4096.0,
                        drawMillis, bytes, output.size(), unique,
                        (double) unique / partial);
                }
                CandBGameTests.reportPatternBudgets(helper, file);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void gothicCloisterDrawsBitsAcrossFrames(GameTestHelper helper) throws Exception {
        var arcFrame = Frame.over(new BoundingBox(0, 0, 0, 0, 3, 3), Direction.SOUTH, false);
        var grown = new Solid.PointedVault(arcFrame, new Vec3i(1, 4, 4), 4, 2);
        var tip = arcFrame.at(0.5, 6.3, 2);
        helper.assertTrue(grown.holds(tip.x, tip.y, tip.z)
            && grown.bounds().maxY() + 1 > tip.y, "grown pointed tip remains inside its bounds");
        var id = ResourceLocation.parse("folkways_chiselsandbits:gothic_cloister");
        Pattern pattern = Patterns.find(id).orElseThrow();
        helper.assertValueEqual(pattern.detail(), "bits", "integration pattern has bit detail");
        for (String language : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            var words = pattern.face().words().get(language);
            helper.assertTrue(words != null && words.containsKey("title"), "localized title " + language);
            for (var setting : pattern.knobs().settings()) {
                helper.assertTrue(words.containsKey(setting.key()), "localized knob " + setting.key());
            }
        }
        var defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof io.github.izakyl.folkways.front.api.Schema.Setting.Items items) {
                defaults.put(items.key(), io.github.izakyl.folkways.core.api.terms.ItemSpec.of(
                    items.byDefault().getFirst().id()).save());
            }
        }
        Drawn.Ready north = null;
        for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            long started = System.nanoTime();
            Drawn drawn = Drafting.draw(pattern, new Commission(
                new Hint.Path(List.of(BlockPos.ZERO, BlockPos.ZERO.relative(facing, 16))),
                Chosen.load(pattern.knobs(), defaults), World.NONE, facing, 0)).join();
            long millis = (System.nanoTime() - started) / 1_000_000;
            helper.assertTrue(drawn instanceof Drawn.Ready, "gothic cloister draws: " + drawn);
            var ready = (Drawn.Ready) drawn;
            if (north == null) {
                north = ready;
            } else {
                assertRotatedVoxels(helper, north, ready, facing);
            }
            long partial = ready.draft().cells().stream().filter(cell -> cell.payload().present()).count();
            long whole = ready.draft().cells().size() - partial;
            helper.assertTrue(partial > 0 && whole > 0, "curved bits and whole plinths");
            long bits = whole * 4096;
            try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
                for (var cell : ready.draft().cells()) {
                    if (cell.payload().present()) {
                        bits += CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                            .mapToLong(Integer::longValue).sum();
                    }
                }
            }
            long unique = ready.draft().cells().stream().filter(cell -> cell.payload().present())
                .map(Draft.Cell::payload).distinct().count();
            long payloadBytes = ready.draft().cells().stream().mapToLong(cell -> cell.payload().bytes()).sum();
            CompoundTag file = DraftTemplate.of(ready.draft(), 3955);
            int gzip;
            try (var output = new ByteArrayOutputStream()) {
                NbtIo.writeCompressed(file, output);
                gzip = output.size();
            }
            LoggerFactory.getLogger("folkways-patterns").info(
                "GOTHIC facing={} size={} partial={} whole={} bits={} equivalent={} drawMillis={}"
                    + " payloadBytes={} gzipBytes={} unique={} uniqueRatio={}",
                facing, ready.draft().extent().size(), partial, whole, bits, bits / 4096.0,
                millis, payloadBytes, gzip, unique, (double) unique / partial);
            if (facing == Direction.NORTH) {
                CandBGameTests.reportPatternBudgets(helper, file);
            }
        }
        // Two adjacent coarse cells across each satellite remain stone at grain eight.
        for (int x : List.of(5, 7)) {
            for (int z : List.of(7, 9)) {
                helper.assertTrue(!gothicVoxel(north, new BlockPos(-2, 1, 0), x, 1, z).isAir(),
                    "attached shaft is at least two grain-eight cells across");
            }
        }
        helper.assertTrue(gothicVoxel(north, new BlockPos(0, 2, 0), 8, 8, 8).isAir(), "open end portal");
        for (var points : List.of(
                List.of(BlockPos.ZERO, new BlockPos(0, 0, -12), new BlockPos(12, 0, -12)),
                List.of(BlockPos.ZERO, new BlockPos(12, 0, 12), new BlockPos(24, 0, 0)),
                List.of(BlockPos.ZERO, new BlockPos(0, 3, 12), new BlockPos(0, 1, 24)))) {
            var drawn = Drafting.draw(pattern, new Commission(new Hint.Path(points),
                Chosen.load(pattern.knobs(), defaults), World.NONE, Direction.NORTH, 0)).join();
            helper.assertTrue(drawn instanceof Drawn.Ready, "joined or stepped cloister draws: " + drawn);
            var ready = (Drawn.Ready) drawn;
            assertSingleRoofSurface(helper, ready);
            for (var point : points) {
                helper.assertTrue(gothicVoxel(ready, point.above(2), 8, 8, 8).isAir(),
                    "path centre stays open at " + point);
            }
        }
        helper.succeed();
    }

    private static void assertSingleRoofSurface(GameTestHelper helper, Drawn.Ready ready) {
        Map<Long, BitSet> columns = new HashMap<>();
        try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
            for (var cell : ready.draft().cells()) {
                BlockPos pos = ready.corner().offset(cell.offset());
                var bits = cell.payload().present() ? CandBStructureCodec.decode(cell.payload().tag()) : null;
                for (int x : List.of(4, 12)) {
                    for (int z : List.of(4, 12)) {
                        for (int y = 0; y < 16; y++) {
                            BlockState state = bits == null ? cell.state()
                                : bits.getBlockInformation(x, y, z).blockState();
                            if (state.is(Blocks.DEEPSLATE_TILES)) {
                                long column = BlockPos.asLong(pos.getX() * 16 + x, 0, pos.getZ() * 16 + z);
                                columns.computeIfAbsent(column, ignored -> new BitSet()).set(pos.getY() * 16 + y);
                            }
                        }
                    }
                }
            }
        }
        helper.assertTrue(!columns.isEmpty(), "joined roof is present");
        for (var column : columns.entrySet()) {
            BitSet heights = column.getValue();
            int bottom = heights.nextSetBit(0);
            helper.assertTrue(heights.nextSetBit(heights.nextClearBit(bottom)) < 0,
                "one roof surface per column, without a lower penetrating skin: " + BlockPos.of(column.getKey()));
        }
    }

    private static BlockState gothicVoxel(Drawn.Ready ready, BlockPos pos, int x, int y, int z) {
        var cell = ready.draft().cells().stream()
            .filter(one -> ready.corner().offset(one.offset()).equals(pos)).findFirst();
        if (cell.isEmpty()) {
            return Blocks.AIR.defaultBlockState();
        }
        if (!cell.get().payload().present()) {
            return cell.get().state();
        }
        try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
            return CandBStructureCodec.decode(cell.get().payload().tag()).getBlockInformation(x, y, z).blockState();
        }
    }

    private static void assertRotatedVoxels(GameTestHelper helper, Drawn.Ready north,
            Drawn.Ready turned, Direction facing) {
        int turns = switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        Map<BlockPos, Draft.Cell> actual = new HashMap<>();
        for (var cell : turned.draft().cells()) {
            actual.put(turned.corner().offset(cell.offset()), cell);
        }
        helper.assertValueEqual(actual.size(), north.draft().cells().size(), "rotated cell count " + facing);
        try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
            for (var cell : north.draft().cells()) {
                BlockPos pos = north.corner().offset(cell.offset());
                for (int turn = 0; turn < turns; turn++) {
                    pos = new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
                }
                var other = actual.get(pos);
                helper.assertTrue(other != null, "rotated cell exists " + facing + " " + pos);
                var expectedBits = cell.payload().present() ? CandBStructureCodec.decode(cell.payload().tag()) : null;
                var actualBits = other.payload().present() ? CandBStructureCodec.decode(other.payload().tag()) : null;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int rx = x;
                            int rz = z;
                            for (int turn = 0; turn < turns; turn++) {
                                int before = rx;
                                rx = 15 - rz;
                                rz = before;
                            }
                            var expected = expectedBits == null ? cell.state()
                                : expectedBits.getBlockInformation(x, y, z).blockState();
                            var found = actualBits == null ? other.state()
                                : actualBits.getBlockInformation(rx, y, rz).blockState();
                            if (!expected.equals(found)) {
                                helper.fail("rotated voxel " + facing + " " + pos
                                    + " [" + x + "," + y + "," + z + "]");
                            }
                        }
                    }
                }
            }
        }
    }

    private static void galleryBitsDrawingTimings(GameTestHelper helper) throws Exception {
        for (String name : List.of("torii", "rotunda", "gothic_cloister", "pavilion",
                "stone_arch_bridge", "rampart", "curved_gallery")) {
            Pattern pattern = Patterns.find(ResourceLocation.parse("folkways_chiselsandbits:" + name))
                .orElseThrow();
            Hint hint = switch (name) {
                case "stone_arch_bridge" -> new Hint.Zone(BlockPos.ZERO, new BlockPos(2, 8, 21));
                case "rampart" -> new Hint.Zone(BlockPos.ZERO, new BlockPos(4, 10, 24));
                case "curved_gallery" -> new Hint.Zone(BlockPos.ZERO, new BlockPos(2, 8, 12));
                case "gothic_cloister" -> new Hint.Zone(BlockPos.ZERO, new BlockPos(5, 1, 15));
                default -> new Hint.Zone(BlockPos.ZERO, new BlockPos(8, 16, 8));
            };
            var commission = new Commission(hint, defaultSettings(pattern), World.NONE, Direction.NORTH, 73);
            long started = System.nanoTime();
            Drawn drawn = Drafting.draw(pattern, commission).join();
            long millis = (System.nanoTime() - started) / 1_000_000;
            long partial = drawn instanceof Drawn.Ready ready
                ? ready.draft().cells().stream().filter(cell -> cell.payload().present()).count() : -1;
            long whole = drawn instanceof Drawn.Ready ready ? ready.draft().cells().stream()
                .filter(cell -> !cell.payload().present() && !cell.state().isAir()).count() : -1;
            long units = (long) Math.pow(Voxelizer.installed().orElseThrow().resolution(), 3);
            Map<ItemSpec, Long> materials = new HashMap<>();
            if (drawn instanceof Drawn.Ready ready) {
                try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
                    for (var cell : ready.draft().cells()) {
                        if (cell.payload().present()) {
                            CandBStructureCodec.validated(cell.payload().tag()).volume().counts()
                                .forEach((material, count) -> materials.merge(material, (long) count, Long::sum));
                        } else if (!cell.state().isAir()) {
                            materials.merge(ItemSpec.exact(new ItemStack(cell.state().getBlock().asItem())),
                                units, Long::sum);
                        }
                    }
                }
            }
            long blocks = materials.values().stream().mapToLong(count -> (count + units - 1) / units).sum();
            double equivalent = materials.values().stream().mapToLong(Long::longValue).sum() / (double) units;
            String digest = "refused";
            byte[] written = new byte[0];
            if (drawn instanceof Drawn.Ready ready) {
                var output = new ByteArrayOutputStream();
                NbtIo.writeCompressed(DraftTemplate.of(ready.draft(), 3955), output);
                written = output.toByteArray();
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(written));
            }
            LoggerFactory.getLogger("folkways-patterns").info(
                "GALLERY_BITS {} millis={} partial={} whole={} blocks={} equivalent={} ready={} sha256={}",
                name, millis, partial, whole, blocks, equivalent, drawn instanceof Drawn.Ready, digest);
            helper.assertTrue(drawn instanceof Drawn.Ready, name + " finishes within the default deadline: " + drawn);
            Drawn repeated = Drafting.draw(pattern, commission).join();
            helper.assertTrue(repeated instanceof Drawn.Ready, name + " repeated drawing finishes: " + repeated);
            var output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(DraftTemplate.of(((Drawn.Ready) repeated).draft(), 3955), output);
            helper.assertTrue(Arrays.equals(written, output.toByteArray()), name + " exports identical bytes");
        }
    }

    private static void verifyIntervalSampling(GameTestHelper helper) {
        var encoder = Voxelizer.installed().orElseThrow();
        var random = new Random(731);
        int size = encoder.resolution();
        for (int grain : List.of(16, 8, 4)) {
            Map<Integer, Long> symmetricCounts = new HashMap<>();
            for (int turn = 0; turn < 6; turn++) {
                Frame frame = Frame.over(new BoundingBox(-2, -1, -2, 2, 4, 2), Direction.SOUTH, turn % 2 == 1)
                    .quartered(turn);
                if (turn >= 4) {
                    frame = frame.turned(new Vec3(0, 1, 0), 27, Vec3.ZERO);
                }
                var dimensions = new Vec3i(4, 4, 4);
                Solid base = new Solid.Oriented(frame, dimensions);
                Solid vault = new Solid.Vault(frame, dimensions, 2, 1);
                Solid arch = new Solid.Arched(base, frame, 3, 1.7, -0.6);
                Solid dome = new Solid.Ellipsoid(frame.moved(0.2, 0.1, 0.3), dimensions, true);
                Solid group = new Solid.Group(List.of(arch, vault, dome));
                var shapes = List.of(base, vault, arch, dome, group, new Solid.Difference(group, dome),
                    new Solid.Intersection(vault, dome), new Solid.PointedVault(frame, dimensions, 3.5, 0.2),
                    new Solid.Cylinder(frame, dimensions, 1, true),
                    new Solid.Lifted(arch, -2, -2, 2, new double[] {0, 0.7, -0.2, 1.3}, true),
                    new Solid.Lifted(base, -2, -2, 2, new double[] {0, 0.7, -0.2, 1.3}, false),
                    new Solid.Under(arch, -2), new Solid.CurvedHip(frame, dimensions, 2.6, 1.15, 0.18),
                    new Solid.PitchedRoof(frame, dimensions, 2.4, 0.18),
                    new Solid.CurvedBeam(frame, dimensions, 0.35, 2.7),
                    new Solid.Frustum(frame, dimensions, 1.6, 0.3, 0.25, 3.4, 0.2),
                    new Solid.Frustum(frame, dimensions, 0.3, 1.6, -0.25, 3.4, 0.2),
                    new Solid.FractionalBox(frame, new Vec3(-0.2, 0.13, 0.3), new Vec3(3.7, 3.4, 4.1)),
                    new Solid.TaperedBox(frame, dimensions, 1.3, 0.4),
                    new Solid.Tube(frame, List.of(Vec3.ZERO, new Vec3(2, 1, 2), new Vec3(3, 3, 1)), 0.17),
                    new Solid.ProfiledSweep(frame, List.of(Vec3.ZERO, new Vec3(2, 1, 2), new Vec3(3, 1, 1)),
                        2.4, 0.3, 1.15, 0.3, 0.22),
                    new Solid.ProfiledSweep(frame, List.of(Vec3.ZERO, new Vec3(2, 1, 2), new Vec3(3, 1, 1)),
                        2.4, 0.3, 1.15, 0.3, 0.22, true));
                for (Solid shape : shapes) {
                    var bounds = shape.bounds();
                    var fit = new VoxelFit(encoder, grain);
                    var repeated = new VoxelFit(encoder, grain);
                    var parts = List.of(new Massing.Part("interval", shape, new Skin.Fitted(List.of(
                        ItemFilter.item(ResourceLocation.parse("minecraft:stone"))), Fit.SOLID)));
                    helper.assertTrue(fit.paint(parts), "small sampled shapes fit the cell limit");
                    long began = System.nanoTime();
                    helper.assertTrue(repeated.paint(parts), "repeated shapes fit the cell limit");
                    long optimizedNanos = System.nanoTime() - began;
                    long sampled = fit.cells().values().stream()
                        .mapToLong(layers -> layers.getFirst().mask().cardinality()).sum();
                    int shapeIndex = shapes.indexOf(shape);
                    if (turn == 0) {
                        symmetricCounts.put(shapeIndex, sampled);
                    } else if (turn < 4 && !(shape instanceof Solid.Lifted)) {
                        helper.assertValueEqual(sampled, symmetricCounts.get(shapeIndex),
                            "coarse centres preserve quarter-turn and mirror volume at grain " + grain);
                    }
                    if (turn == 0 || turn < 4 && (shape instanceof Solid.Tube
                            || shape instanceof Solid.PointedVault || shape instanceof Solid.PitchedRoof)) {
                        began = System.nanoTime();
                        long expectedCount = 0;
                        for (int cx = bounds.minX(); cx <= bounds.maxX(); cx++) {
                            for (int cy = bounds.minY(); cy <= bounds.maxY(); cy++) {
                                for (int cz = bounds.minZ(); cz <= bounds.maxZ(); cz++) {
                                    BitSet expected = bruteMask(shape, cx, cy, cz, size, grain);
                                    expectedCount += expected.cardinality();
                                    var layers = fit.cells().get(BlockPos.asLong(cx, cy, cz));
                                    helper.assertTrue(expected.equals(layers == null ? new BitSet()
                                        : layers.getFirst().mask()), "full interval mask: " + shape.getClass().getSimpleName());
                                }
                            }
                        }
                        long bruteNanos = System.nanoTime() - began;
                        long actualCount = fit.cells().values().stream()
                            .mapToLong(layers -> layers.getFirst().mask().cardinality()).sum();
                        helper.assertTrue(expectedCount == actualCount, "full sampled voxel count");
                        LoggerFactory.getLogger("folkways-patterns").info(
                            "COVERAGE {} grain={} optimizedMicros={} bruteMicros={}",
                            shape.getClass().getSimpleName(), grain,
                            optimizedNanos / 1000, bruteNanos / 1000);
                    }
                    for (int cell = 0; cell < 32; cell++) {
                        int cx = bounds.minX() - 1 + random.nextInt(bounds.getXSpan() + 2);
                        int cy = bounds.minY() - 1 + random.nextInt(bounds.getYSpan() + 2);
                        int cz = bounds.minZ() - 1 + random.nextInt(bounds.getZSpan() + 2);
                        BitSet expected = bruteMask(shape, cx, cy, cz, size, grain);
                        long key = BlockPos.asLong(cx, cy, cz);
                        var layers = fit.cells().get(key);
                        var actual = layers == null ? new BitSet() : layers.getFirst().mask();
                        helper.assertTrue(actual.equals(expected), "interval mask matches brute force: "
                            + shape.getClass().getSimpleName() + " turn=" + turn + " cell=" + new BlockPos(cx, cy, cz));
                        var again = repeated.cells().get(key);
                        byte[] bytes = again == null ? new byte[0] : again.getFirst().mask().toByteArray();
                        helper.assertTrue(Arrays.equals(actual.toByteArray(), bytes),
                            "sampling is byte deterministic");
                    }
                }
            }
        }
        // Synthetic storage sizes exercise non-divisible and odd coarse grids without changing C&B encoding.
        for (int resolution : List.of(6, 10, 3)) {
            Voxelizer storage = new Voxelizer() {
                @Override
                public int resolution() {
                    return resolution;
                }

                @Override
                public boolean supports(BlockState material) {
                    return encoder.supports(material);
                }

                @Override
                public BlockState carrier() {
                    return encoder.carrier();
                }

                @Override
                public StructurePayload encode(BlockState[] materials) {
                    throw new AssertionError("mask regression does not encode synthetic storage");
                }
            };
            Frame frame = Frame.over(new BoundingBox(0, 0, 0, 1, 1, 1), Direction.SOUTH, false);
            Solid shape = new Solid.CurvedBeam(frame, new Vec3i(2, 2, 2), 0.35, 1.3);
            for (int grain : List.of(16, 8, 4)) {
                var fit = new VoxelFit(storage, grain);
                helper.assertTrue(fit.paint(List.of(new Massing.Part("fallback", shape,
                    new Skin.Fitted(List.of(ItemFilter.item(ResourceLocation.parse("minecraft:stone"))), Fit.SOLID)))),
                    "fallback sample fits");
                for (int x = 0; x < 2; x++) {
                    for (int y = 0; y < 2; y++) {
                        for (int z = 0; z < 2; z++) {
                            var layers = fit.cells().get(BlockPos.asLong(x, y, z));
                            helper.assertTrue(bruteMask(shape, x, y, z, resolution, grain).equals(
                                layers == null ? new BitSet() : layers.getFirst().mask()),
                                "largest divisor fallback at storage " + resolution + " grain " + grain);
                        }
                    }
                }
            }
        }
    }

    private static BitSet bruteMask(Solid shape, int cx, int cy, int cz, int size, int grain) {
        int grid = Math.min(size, grain);
        while (size % grid != 0) {
            grid--;
        }
        int stride = size / grid;
        var mask = new BitSet(size * size * size);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    if (shape.holds(cx + (x / stride + 0.5D) / grid, cy + (y / stride + 0.5D) / grid,
                            cz + (z / stride + 0.5D) / grid)) {
                        mask.set((x * size + y) * size + z);
                    }
                }
            }
        }
        return mask;
    }

    private static Chosen defaultSettings(Pattern pattern) {
        var saved = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                saved.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        return Chosen.load(pattern.knobs(), saved);
    }

    private static void verifySamples(GameTestHelper helper, Drawn.Ready ready, List<Solid> shapes, boolean mixed) {
        verifySamples(helper, ready, shapes, mixed, Pattern.DEFAULT_GRAIN);
    }

    private static void verifySamples(GameTestHelper helper, Drawn.Ready ready, List<Solid> shapes, boolean mixed,
            int grain) {
        int size = Voxelizer.installed().orElseThrow().resolution();
        int grid = Math.min(size, grain);
        while (size % grid != 0) {
            grid--;
        }
        int stride = size / grid;
        Map<BlockPos, Draft.Cell> cells = new HashMap<>();
        for (Draft.Cell cell : ready.draft().cells()) {
            cells.put(ready.corner().offset(cell.offset()), cell);
        }
        var bounds = shapes.getFirst().bounds();
        for (int cy = bounds.minY(); cy <= bounds.maxY(); cy++) {
            for (int cz = bounds.minZ(); cz <= bounds.maxZ(); cz++) {
                for (int cx = bounds.minX(); cx <= bounds.maxX(); cx++) {
                    Draft.Cell cell = cells.get(new BlockPos(cx, cy, cz));
                    var storage = cell != null && cell.payload().present()
                        ? CandBStructureCodec.decode(cell.payload().tag()) : null;
                    long expectedBits = 0;
                    for (int x = 0; x < size; x++) {
                        for (int y = 0; y < size; y++) {
                            for (int z = 0; z < size; z++) {
                                double wx = cx + (x / stride + 0.5D) / grid;
                                double wy = cy + (y / stride + 0.5D) / grid;
                                double wz = cz + (z / stride + 0.5D) / grid;
                                var expected = Blocks.AIR.defaultBlockState();
                                for (int part = 0; part < shapes.size(); part++) {
                                    if (shapes.get(part).holds(wx, wy, wz)) {
                                        expected = (part == 1 && mixed ? Blocks.OAK_PLANKS
                                            : Blocks.STONE).defaultBlockState();
                                    }
                                }
                                var actual = storage != null ? storage.getBlockInformation(x, y, z).blockState()
                                    : cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
                                helper.assertValueEqual(actual, expected, "world voxel " + new BlockPos(cx, cy, cz)
                                    + "/" + new BlockPos(x, y, z));
                                if (!expected.isAir()) {
                                    expectedBits++;
                                }
                            }
                        }
                    }
                    if (storage != null) {
                        helper.assertValueEqual(CandBStructureCodec.counts(storage)
                            .values().stream().mapToLong(Integer::longValue).sum(), expectedBits, "exact voxel count");
                    }
                }
            }
        }
    }

    private static Piece piece() {
        return DraftTemplate.pieceOf(CandBTestData.structure(CandBStructureCodec.encode(CandBTestData.sculpture())));
    }

    private static Draft fitted(GameTestHelper helper, Piece piece, Scope scope) {
        Map<BlockPos, StructurePayload> payloads = piece.payloadsInto(scope, new int[3]);
        var cells = piece.laidInto(scope, new int[3]);
        var part = new Massing.Part("carving", new Solid.Cells(cells.keySet()), new Skin.Stamped(cells, List.of(), payloads));
        var lattice = Lattice.of(new Massing(List.of(part))).orElseThrow();
        Drawn drawn = Fitter.fit(lattice, BlockPos.ZERO, 0);
        helper.assertTrue(drawn instanceof Drawn.Ready, "the payload is fitted");
        return ((Drawn.Ready) drawn).draft();
    }
}
