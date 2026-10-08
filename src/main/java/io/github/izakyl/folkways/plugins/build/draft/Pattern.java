package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.plugins.build.Joints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record Pattern(ResourceLocation id, Schema knobs, Set<String> accepts, List<String> phases, Object draw,
        Object grow) {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private static final long LOAD_STEPS = 200_000L;
    private static final long DRAW_STEPS = 4_000_000L;

    private static final StarlarkSemantics SEMANTICS =
        StarlarkSemantics.builder().setBool("-allow_recursion", true).build();

    private static final String KNOBS = "knobs";
    private static final String DRAW = "draw";
    private static final String GROW = "grow";
    private static final String ACCEPTS = "accepts";
    private static final String PHASES = "phases";
    private static final String SUFFIX = ".star";
    private static final Function<ResourceLocation, Optional<String>> NO_LIBRARIES = id -> Optional.empty();
    private static final String JOINER = "create";

    public Pattern {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(knobs, "knobs");
        accepts = Set.copyOf(accepts);
        phases = List.copyOf(phases);
        if ((draw == null) == (grow == null)) {
            throw new IllegalArgumentException("a pattern defines draw(site) or grow(site), and not both");
        }
    }

    public static Optional<Pattern> parse(ResourceLocation id, String source) {
        return parse(id, source, NO_LIBRARIES);
    }

    public static Optional<Pattern> parse(ResourceLocation id, String source,
            Function<ResourceLocation, Optional<String>> libraries) {
        try (Mutability mutability = Mutability.create("pattern", id)) {
            Module module = execute(id, source, libraries, mutability, new HashMap<>(), new LinkedHashSet<>());
            Object drawing = module.getGlobal(DRAW);
            Object growing = module.getGlobal(GROW);
            if (drawing == null && growing == null) {
                return Optional.empty();
            }
            if (drawing != null && growing != null) {
                throw Starlark.errorf("a pattern defines draw(site) or grow(site), and not both");
            }
            Pattern pattern = new Pattern(id, schema(module.getGlobal(KNOBS)), accepts(module.getGlobal(ACCEPTS)),
                phases(module.getGlobal(PHASES)), drawing, growing);
            Set<ResourceLocation> unloaded = pattern.unloaded();
            if (!unloaded.isEmpty()) {
                LOGGER.warn("{}: needs {}, which this game has not loaded", id, unloaded);
            }
            return Optional.of(pattern);
        } catch (SyntaxError.Exception | EvalException | RuntimeException broken) {
            LOGGER.error("{}: {}", id, broken.getMessage());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Module execute(ResourceLocation id, String source,
            Function<ResourceLocation, Optional<String>> libraries, Mutability mutability,
            Map<ResourceLocation, Module> loaded, Set<ResourceLocation> loading)
            throws SyntaxError.Exception, EvalException, InterruptedException {
        loading.add(id);
        StarlarkThread thread = new StarlarkThread(mutability, SEMANTICS);
        thread.setMaxExecutionSteps(LOAD_STEPS);
        thread.setPrintHandler((printing, message) -> LOGGER.info("{}: {}", id, message));
        thread.setLoader(written -> {
            ResourceLocation wanted = libraryId(written);
            if (wanted == null) {
                return null;
            }
            Module done = loaded.get(wanted);
            if (done != null) {
                return done;
            }
            if (loading.contains(wanted)) {
                throw new IllegalStateException(id + " loads " + wanted + ", which is still loading: a load cycle");
            }
            Optional<String> text = libraries.apply(wanted);
            if (text.isEmpty()) {
                return null;
            }
            try {
                Module library = execute(wanted, text.get(), libraries, mutability, loaded, loading);
                loaded.put(wanted, library);
                return library;
            } catch (SyntaxError.Exception | EvalException broken) {
                throw new IllegalStateException(wanted + ": " + broken.getMessage(), broken);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
        Module module = Module.withPredeclared(SEMANTICS, Vocabulary.predeclared());
        Starlark.execFile(ParserInput.fromString(source, id.toString()), FileOptions.DEFAULT, module, thread);
        loading.remove(id);
        return module;
    }

    static ResourceLocation libraryId(String written) {
        String trimmed = written.endsWith(SUFFIX) ? written.substring(0, written.length() - SUFFIX.length()) : written;
        return ResourceLocation.tryParse(trimmed);
    }

    public boolean accepts(Hint hint) {
        return accepts.contains(hint.kind());
    }

    public boolean grows() {
        return grow != null;
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
            if (!filter.tag() && !BuiltInRegistries.ITEM.containsKey(filter.id())) {
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

    public Drawn drawOn(Commission commission) {
        if (draw == null) {
            return switch (growOn(commission)) {
                case Round.Grew grew -> grew.ready();
                case Round.Ended ended -> Drawn.refused(DraftRefusal.NOTHING_DRAWN);
                case Round.Stuck stuck -> stuck.refused();
            };
        }
        Derivation drawing = new Derivation(commission, phases);
        Drawn drawn = run(commission, drawing, draw);
        if (drawn instanceof Drawn.Ready && drawing.grown() != null) {
            return Drawn.refused(DraftRefusal.PATTERN_FAILED, "grown(...) is what grow(site) answers, not draw(site)");
        }
        return drawn;
    }

    public Round growOn(Commission commission) {
        if (grow == null) {
            return new Round.Stuck(new Drawn.Refused(DraftRefusal.PATTERN_FAILED, id + " does not grow"));
        }
        Derivation drawing = new Derivation(commission, phases);
        Drawn drawn = run(commission, drawing, grow);
        Vocabulary.Grown grown = drawing.grown();
        return switch (drawn) {
            case Drawn.Ready ready -> new Round.Grew(ready,
                grown == null ? commission.growth().kept() : grown.keep(), grown != null && grown.last());
            case Drawn.Refused refused when refused.why() == DraftRefusal.NOTHING_DRAWN -> new Round.Ended();
            case Drawn.Refused refused -> new Round.Stuck(refused);
        };
    }

    private Drawn run(Commission commission, Derivation drawing, Object fn) {
        if (!accepts(commission.hint())) {
            return Drawn.refused(DraftRefusal.WRONG_MARK, commission.hint().kind());
        }
        Set<ResourceLocation> missing = wanting(commission.settings());
        if (!missing.isEmpty()) {
            return Drawn.refused(DraftRefusal.UNKNOWN_BLOCK, named(missing));
        }
        try (Mutability mutability = Mutability.create("draw", id)) {
            StarlarkThread thread = new StarlarkThread(mutability, SEMANTICS);
            thread.setMaxExecutionSteps(DRAW_STEPS);
            thread.setPrintHandler((printing, message) -> LOGGER.info("{}: {}", id, message));
            List<Massing.Part> parts = drawing.derive(thread, fn, new DraftSite(drawing));
            if (parts.isEmpty()) {
                return Drawn.refused(DraftRefusal.NOTHING_DRAWN);
            }
            Drawn drawn = Lattice.of(new Massing(parts))
                .map(lattice -> Fitter.fit(lattice, commission.hint().anchor(), commission.seed()))
                .orElseGet(() -> Drawn.refused(DraftRefusal.SITE_TOO_LARGE));
            if (!(drawn instanceof Drawn.Ready laid)) {
                return drawn;
            }
            Drawn.Ready ready = laid;
            if (!drawing.joints().isEmpty()) {
                if (!Joints.installed()) {
                    return Drawn.refused(DraftRefusal.UNKNOWN_BLOCK, JOINER);
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
            return ready.counting(drawing.reports());
        } catch (EvalException stopped) {
            DraftRefusal why = drawing.stoppedFor();
            return Drawn.refused(why == null ? DraftRefusal.PATTERN_FAILED : why, stopped.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Drawn.refused(DraftRefusal.PATTERN_FAILED, "interrupted");
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
