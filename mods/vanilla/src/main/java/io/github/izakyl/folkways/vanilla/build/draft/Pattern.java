package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.script.api.Profile;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.work.api.Joint;
import io.github.izakyl.folkways.core.work.api.Joints;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.api.Voxelizer;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.build.BlueprintUploads;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.starlark.java.eval.AllocationBudget;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record Pattern(ResourceLocation id, Schema knobs, Face face, Set<String> accepts, List<String> phases,
        Capture capture, String detail, int grain, Object draw) {

    public static final int DEFAULT_GRAIN = 8;

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private static final String KNOBS = "knobs";
    private static final String LANG = "lang";
    private static final String DRAW = "draw";
    private static final String ACCEPTS = "accepts";
    private static final String PHASES = "phases";
    private static final String CAPTURE = "capture";
    private static final String SUFFIX = ".star";
    private static final Function<ResourceLocation, Optional<String>> INSTALLED =
        id -> Optional.ofNullable(Patterns.sources().get(id));
    private static final String JOINER = "create";

    public Pattern {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(knobs, "knobs");
        Objects.requireNonNull(face, "face");
        PatternText.register(face);
        accepts = Set.copyOf(accepts);
        phases = List.copyOf(phases);
        Objects.requireNonNull(capture, "capture");
        if (draw == null) {
            throw new IllegalArgumentException("a pattern defines draw(site)");
        }
    }

    private static Profile profile(int seconds) {
        return Profile.draftPattern(Duration.ofSeconds(seconds), Vocabulary.predeclared(), Pattern::libraryId,
            (id, message) -> LOGGER.info("{}: {}", id, message));
    }

    // Read on its own, a pattern still loads the libraries this game has installed.
    public static Optional<Pattern> parse(ResourceLocation id, String source) {
        return parse(id, source, INSTALLED);
    }

    public static Pattern inline(ResourceLocation id, String source) {
        var diagnostics = new ArrayList<String>();
        return parse(id, source, INSTALLED, diagnostics::add, true).orElseThrow(() ->
            new IllegalArgumentException(diagnostics.isEmpty() ? "Define draw(site)." : diagnostics.getFirst()));
    }

    public static Optional<Pattern> parse(ResourceLocation id, String source,
            Function<ResourceLocation, Optional<String>> libraries) {
        return parse(id, source, libraries, why -> {
        });
    }

    // refused hears why a pattern did not load, for the shelf to show.
    public static Optional<Pattern> parse(ResourceLocation id, String source,
            Function<ResourceLocation, Optional<String>> libraries, Consumer<String> refused) {
        return parse(id, source, libraries, refused, false);
    }

    private static Optional<Pattern> parse(ResourceLocation id, String source,
            Function<ResourceLocation, Optional<String>> libraries, Consumer<String> refused, boolean inline) {
        try {
            ScriptRuntime runtime = new ScriptRuntime(profile(FolkwaysVanillaConfig.drawSeconds()));
            ScriptRuntime.Loaded loaded =
                ScriptRuntime.result(runtime.submit(() -> runtime.load(id, source, libraries)));
            Module module = loaded.module();
            Object drawing = module.getGlobal(DRAW);
            if (drawing == null) {
                return Optional.empty();
            }
            String detail = detail(module.getGlobal("detail"));
            Pattern pattern = new Pattern(id, schema(module.getGlobal(KNOBS)),
                new Face(offered(module.getGlobal(KNOBS)), words(module, loaded.libraries())),
                accepts(module.getGlobal(ACCEPTS)), phases(module.getGlobal(PHASES)),
                Capture.parse(module.getGlobal(CAPTURE)), detail, grain(module.getGlobal("grain"), detail), drawing);
            Set<ResourceLocation> unloaded = pattern.unloaded();
            if (!unloaded.isEmpty()) {
                LOGGER.warn("{}: needs {}, which this game has not loaded", id, unloaded);
            }
            return Optional.of(pattern);
        } catch (EvalException | RuntimeException broken) {
            String detail = broken.getMessage();
            if (broken instanceof ScriptFault fault) {
                if (inline && fault.kind() == ScriptFault.Kind.MEMORY) {
                    throw fault;
                }
                if (fault.kind() == ScriptFault.Kind.TIMEOUT) {
                    detail = "Pattern load timed out";
                } else if (fault.location() != null) {
                    detail = fault.location() + ": " + fault.detail();
                }
            }
            LOGGER.error("{}: {}", id, detail);
            refused.accept(String.valueOf(detail));
            return Optional.empty();
        }
    }

    static ResourceLocation libraryId(String written) {
        String trimmed = written.endsWith(SUFFIX) ? written.substring(0, written.length() - SUFFIX.length()) : written;
        return ResourceLocation.tryParse(trimmed);
    }

    public boolean accepts(Hint hint) {
        return accepts.contains(hint.kind());
    }

    public Set<ResourceLocation> unloaded() {
        Set<ResourceLocation> missing = new LinkedHashSet<>();
        for (Schema.Setting setting : knobs.settings()) {
            if (setting instanceof Schema.Setting.Items slot) {
                unloaded(slot, missing);
            }
        }
        return missing;
    }

    private Set<ResourceLocation> wanting(Settings settings) {
        Set<ResourceLocation> missing = new LinkedHashSet<>();
        for (Schema.Setting setting : knobs.settings()) {
            if (!(setting instanceof Schema.Setting.Items slot)) {
                continue;
            }
            Optional<ItemSpec> chosen = settings.items(slot.key());
            if (chosen.isPresent()) {
                unregistered(chosen.get(), missing);
            } else {
                unloaded(slot, missing);
            }
        }
        return missing;
    }

    private static void unloaded(Schema.Setting.Items slot, Set<ResourceLocation> into) {
        for (ItemFilter filter : slot.byDefault()) {
            if (filter.kind() == ItemFilter.Kind.ITEM && !BuiltInRegistries.ITEM.containsKey(filter.id())) {
                into.add(filter.id());
            }
        }
    }

    private static void unregistered(ItemSpec spec, Set<ResourceLocation> into) {
        spec.item().filter(item -> !BuiltInRegistries.ITEM.containsKey(item)).ifPresent(into::add);
        for (ItemSpec alternative : spec.anyOf()) {
            unregistered(alternative, into);
        }
    }

    /** The chunks a drawing of this pattern over the mark may read. */
    public Area area(Hint hint) {
        return Area.around(hint, capture);
    }

    /**
     * What a drawing of this pattern over the mark will read, taken from the level now so it can be drawn on
     * another thread; this runs on the level's own thread.
     */
    public World capture(Level level, Hint hint) {
        return World.capture(level, area(hint));
    }

    public Drawn drawOn(Commission commission) {
        return Drafting.draw(this, commission).join();
    }

    CompletableFuture<Drawn> submitDrawing(Commission commission, int seconds) {
        Area area = area(commission.hint());
        Commission snapshot = new Commission(commission.hint(), commission.settings(),
            commission.world().snapshot(area), commission.facing(), commission.seed());
        // Drawings retain their time limit without a step budget of their own.
        Profile loading = profile(seconds);
        ScriptRuntime runtime = new ScriptRuntime(loading.withLimits(0, loading.time(), 0));
        return runtime.submit(() -> {
            Derivation drawing = new Derivation(snapshot, area, phases, face);
            return run(runtime, snapshot, drawing);
        });
    }

    private Drawn run(ScriptRuntime runtime, Commission commission, Derivation drawing) {
        if (!accepts(commission.hint())) {
            String kind = commission.hint().kind();
            return new Drawn.Refused(DraftRefusal.WRONG_MARK, kind,
                Optional.of(new Notice("folkways.draft.mark." + kind, List.of())));
        }
        if (detail.equals("bits") && Voxelizer.installed().isEmpty()) {
            return Drawn.refused(DraftRefusal.MISSING_VOXELIZER);
        }
        Set<ResourceLocation> missing = wanting(commission.settings());
        if (!missing.isEmpty()) {
            return Drawn.refused(DraftRefusal.UNKNOWN_BLOCK, named(missing));
        }
        try (var processing = detail.equals("bits") ? Voxelizer.installed().orElseThrow().drawing()
                : StructureCompat.processing()) {
            List<Massing.Part> parts = runtime.run(id, thread -> {
                long began = System.nanoTime();
                try {
                    return drawing.derive(thread, draw, new DraftSite(drawing));
                } finally {
                    LOGGER.info("{}: drew in {} steps, {} ms, {} counted bytes", id, thread.getExecutedSteps(),
                        (System.nanoTime() - began) / 1_000_000, AllocationBudget.currentCountedBytes());
                }
            });
            // Fitting runs after Starlark, under the drawing deadline and geometry limits.
            if (parts.isEmpty()) {
                return Drawn.refused(DraftRefusal.NOTHING_DRAWN);
            }
            Drawn drawn = Lattice.of(new Massing(parts),
                detail.equals("bits") ? Voxelizer.installed().orElseThrow() : null, grain)
                .map(lattice -> Fitter.fit(lattice, commission.hint().anchor(), commission.seed()))
                .orElseGet(() -> Drawn.refused(DraftRefusal.SITE_TOO_LARGE));
            if (!(drawn instanceof Drawn.Ready laid)) {
                return drawn;
            }
            Drawn.Ready ready = laid;
            if (!drawing.joints().isEmpty()) {
                if (!Joints.installed()) {
                    return Drawn.refused(DraftRefusal.JOINER_UNAVAILABLE, JOINER);
                }
                Drawn joined = joined(laid, commission.hint().anchor(), drawing.joints());
                if (!(joined instanceof Drawn.Ready fastened)) {
                    return joined;
                }
                ready = fastened;
            }
            if (!drawing.reports().isEmpty()) {
                LOGGER.info("{}: {}", id, drawing.reports());
            }
            if (detail.equals("bits")) {
                if (!uploadable(ready.draft())) {
                    return Drawn.refused(DraftRefusal.SITE_TOO_LARGE, "voxel drawing exceeds the upload limit");
                }
            }
            return ready.withDetails(drawing);
        } catch (StructureCompat.Failure failed) {
            return Drawn.refused(DraftRefusal.UNSUPPORTED_PAYLOAD);
        } catch (ScriptFault stopped) {
            if (stopped.getCause() instanceof StructureCompat.Failure) {
                return Drawn.refused(DraftRefusal.UNSUPPORTED_PAYLOAD);
            }
            if (stopped.kind() != ScriptFault.Kind.EVAL || !(stopped.getCause() instanceof EvalException)) {
                throw stopped;
            }
            DraftRefusal why = drawing.stoppedFor();
            return new Drawn.Refused(why == null ? DraftRefusal.PATTERN_FAILED : why, stopped.location() == null ? stopped.detail() : stopped.location() + ": " + stopped.detail(),
                drawing.explanation());
        }
    }

    private static boolean uploadable(Draft draft) {
        try (var written = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(DraftTemplate.of(draft,
                SharedConstants.getCurrentVersion().getDataVersion().getVersion()), written);
            Drafting.checkTime();
            return BlueprintUploads.fits(written.toByteArray());
        } catch (IOException failed) {
            return false;
        }
    }

    private static String named(Set<ResourceLocation> ids) {
        return String.join(", ", ids.stream().map(ResourceLocation::toString).toList());
    }

    private static Drawn joined(Drawn.Ready laid, BlockPos anchor, List<Joint> joints) {
        Map<BlockPos, BlockState> cells = new HashMap<>();
        for (Draft.Cell cell : laid.draft().cells()) {
            cells.put(cell.offset(), cell.state());
        }
        BlockPos shift = anchor.subtract(laid.corner());
        List<Joint> placed = new ArrayList<>();
        for (Joint joint : joints) {
            Joint local = joint.moved(shift);
            for (BlockPos end : List.of(local.from(), local.to())) {
                BlockState state = cells.get(end);
                if (state == null || state.isAir()) {
                    return Drawn.refused(DraftRefusal.PATTERN_FAILED, "a joint ends at "
                        + end.subtract(shift).toShortString() + ", where the drawing lays nothing");
                }
            }
            placed.add(local);
        }
        Draft draft = laid.draft();
        return laid.joining(new Draft(draft.extent(), draft.cells(), placed));
    }

    private static Schema schema(Object declared) throws EvalException {
        if (declared == null) {
            return Schema.none();
        }
        if (!(declared instanceof Sequence<?> sequence)) {
            throw Starlark.errorf("knobs is a list, got %s", Starlark.type(declared));
        }
        List<Schema.Setting> settings = new ArrayList<>();
        for (Object entry : sequence) {
            if (!(entry instanceof Vocabulary.Knob knob)) {
                throw Starlark.errorf("knobs holds what flag/count/choice/items answer, and one of them is %s",
                    Starlark.type(entry));
            }
            settings.add(knob.setting());
        }
        return new Schema(settings);
    }

    private static Map<String, List<Face.Option>> offered(Object declared) {
        Map<String, List<Face.Option>> offered = new LinkedHashMap<>();
        if (declared instanceof Sequence<?> sequence) {
            for (Object entry : sequence) {
                if (entry instanceof Vocabulary.Knob knob && !knob.offered().isEmpty()) {
                    offered.put(knob.setting().key(), knob.offered());
                }
            }
        }
        return offered;
    }

    // Shared libraries bring their words too; the pattern's own dictionary has the last say.
    private static Map<String, Map<String, String>> words(Module module, Map<ResourceLocation, Module> loaded)
            throws EvalException {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        List<Module> modules = new ArrayList<>(loaded.values());
        modules.add(module);
        for (Module part : modules) {
            words(part.getGlobal(LANG)).forEach((language, entries) ->
                merged.computeIfAbsent(language, ignored -> new LinkedHashMap<>()).putAll(entries));
        }
        return merged;
    }

    // lang = {"en_us": {"title": "Road", "width": "Width", "roof.gable": "Gable"}, "zh_cn": {...}}
    private static Map<String, Map<String, String>> words(Object declared) throws EvalException {
        Map<String, Map<String, String>> words = new LinkedHashMap<>();
        if (declared == null) {
            return words;
        }
        if (!(declared instanceof Dict<?, ?> languages)) {
            throw Starlark.errorf("lang is a dict of languages, got %s", Starlark.type(declared));
        }
        for (Map.Entry<?, ?> language : languages.entrySet()) {
            if (!(language.getKey() instanceof String code) || !(language.getValue() instanceof Dict<?, ?> said)) {
                throw Starlark.errorf("lang maps a language such as 'en_us' to a dict of its words");
            }
            Map<String, String> spoken = new LinkedHashMap<>();
            for (Map.Entry<?, ?> word : said.entrySet()) {
                if (!(word.getKey() instanceof String key) || !(word.getValue() instanceof String text)) {
                    throw Starlark.errorf("lang '%s' maps a key to its text, both strings", code);
                }
                spoken.put(key, text);
            }
            words.put(code.toLowerCase(Locale.ROOT), spoken);
        }
        return words;
    }

    private static Set<String> accepts(Object declared) throws EvalException {
        if (declared == null) {
            return Set.of(Hint.ZONE);
        }
        Set<String> kinds = new LinkedHashSet<>(Rule.strings(declared));
        for (String kind : kinds) {
            if (!kind.equals(Hint.ZONE) && !kind.equals(Hint.PATH)) {
                throw Starlark.errorf("a pattern accepts 'zone' or 'path', not '%s'", kind);
            }
        }
        if (kinds.isEmpty()) {
            throw Starlark.errorf("a pattern accepts at least one of 'zone' and 'path'");
        }
        return kinds;
    }

    private static int grain(Object declared, String detail) throws EvalException {
        if (declared == null) {
            return DEFAULT_GRAIN;
        }
        if (!detail.equals("bits")) {
            throw Starlark.errorf("grain is only valid with detail = 'bits'");
        }
        if (!(declared instanceof StarlarkInt value)
                || !(value.toBigInteger().equals(BigInteger.valueOf(16))
                    || value.toBigInteger().equals(BigInteger.valueOf(8))
                    || value.toBigInteger().equals(BigInteger.valueOf(4)))) {
            throw Starlark.errorf("grain must be 16, 8 or 4, got %s", Starlark.repr(declared));
        }
        return value.toInt("grain");
    }

    private static String detail(Object declared) throws EvalException {
        // Existing patterns may name a phase function detail. Only a value declares the drawing mode.
        if (declared == null || declared instanceof StarlarkCallable) {
            return "blocks";
        }
        if (!(declared instanceof String value) || !(value.equals("blocks") || value.equals("bits"))) {
            throw Starlark.errorf("detail must be 'blocks' or 'bits', got %s", Starlark.repr(declared));
        }
        return (String) declared;
    }

    private static List<String> phases(Object declared) throws EvalException {
        if (declared == null) {
            return List.of("main");
        }
        List<String> names = Rule.strings(declared);
        if (names.isEmpty() || new LinkedHashSet<>(names).size() != names.size()) {
            throw Starlark.errorf("phases lists at least one phase, each once, got %s", names);
        }
        return names;
    }
}
