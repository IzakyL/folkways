package io.github.izakyl.folkways.vanilla.build;

import com.google.common.hash.Hashing;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.operation.Commissioning;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.operation.SettingChanges;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.work.api.Joints;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.vanilla.build.draft.Chosen;
import io.github.izakyl.folkways.vanilla.build.draft.Commission;
import io.github.izakyl.folkways.vanilla.build.draft.DraftRefusal;
import io.github.izakyl.folkways.vanilla.build.draft.Drafting;
import io.github.izakyl.folkways.vanilla.build.draft.Drawn;
import io.github.izakyl.folkways.vanilla.build.draft.Face;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Pattern;
import io.github.izakyl.folkways.vanilla.build.draft.PatternText;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import io.github.izakyl.folkways.vanilla.build.draft.Vocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public final class SurvivalCommissions implements Commissioning.Source {

    private static final ResourceLocation INLINE = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "inline");

    private final Pattern authored;
    private final String written;

    public SurvivalCommissions() {
        this(null, null);
    }

    private SurvivalCommissions(Pattern authored, String written) {
        this.authored = authored;
        this.written = written;
    }

    @Override
    public List<Commissioning.Order> orders(Colony colony) {
        return colony.service(BuildContent.ID, BuildPresence.class).map(BuildPresence::orders).orElse(List.of());
    }

    @Override
    public boolean cancel(Colony colony, java.util.UUID id) {
        return colony.service(BuildContent.ID, BuildPresence.class).map(site -> site.cancelOrder(id)).orElse(false);
    }

    @Override
    public String patternHelp() {
        return Vocabulary.help();
    }

    @Override
    public CompletableFuture<Commissioning.Inline> inline(ServerLevel level, String source) {
        return Rounds.onServer(level, Drafting.prepare(() -> {
            Pattern pattern = Pattern.inline(INLINE, source);
            return new Commissioning.Inline(INLINE, pattern.knobs(), new SurvivalCommissions(pattern, source));
        })).handle((prepared, failure) -> {
            if (failure != null) {
                ScriptFault fault = ScriptRuntime.fault(failure);
                String code = fault.kind() == ScriptFault.Kind.MEMORY ? "memory_limit" : "pattern_failed";
                throw new Commissioning.PatternFailure(code, fault.detail());
            }
            return prepared;
        });
    }

    private Optional<Pattern> pattern(Commissioning.Request request) {
        return authored == null ? Patterns.find(request.pattern()) : Optional.of(authored);
    }


    @Override
    public List<ResourceLocation> patterns() {
        return Patterns.sources().keySet().stream().filter(id -> Patterns.find(id).isPresent()).sorted().toList();
    }

    @Override
    public List<Commissioning.Pattern> descriptions() {
        return patterns().stream().map(id -> {
            Pattern pattern = Patterns.find(id).orElseThrow();
            Map<String, List<String>> options = new TreeMap<>();
            pattern.face().offered().forEach((key, offered) ->
                options.put(key, offered.stream().map(option -> option.key()).toList()));
            Map<String, Component> errors = new TreeMap<>();
            String prefix = PatternText.register(pattern.face());
            pattern.face().words().values().stream().flatMap(words -> words.keySet().stream())
                .filter(key -> key.startsWith("error.")).distinct().sorted().forEach(key ->
                    errors.put(key, Component.translatableWithFallback(prefix + key,
                        pattern.face().word("en_us", key).orElse(key))));
            Face face = pattern.face();
            Optional<Component> marking = face.word(Face.FALLBACK, Face.MARKING)
                .or(() -> face.words().keySet().stream().sorted()
                    .flatMap(language -> face.word(language, Face.MARKING).stream()).findFirst())
                .map(fallback -> Component.translatableWithFallback(prefix + Face.MARKING, fallback));
            return new Commissioning.Pattern(id, pattern.accepts().stream().sorted().toList(), pattern.knobs(),
                options, errors, marking);
        }).toList();
    }

    private static Hint hint(Commissioning.Request request) {
        return request.points().<Hint>map(Hint.Path::new)
            .orElseGet(() -> new Hint.Zone(request.from(), request.to()));
    }

    @Override
    public Optional<String> refusal(ServerLevel level, Colony colony, Commissioning.Request request) {
        if (!request.mirror().equals("none")) {
            return Optional.of("pattern_commission_has_no_mirror");
        }
        if (request.points().filter(points -> points.size() < 2 || points.size() > Hint.MOST_POINTS).isPresent()) {
            return Optional.of("invalid_path_size");
        }
        var pattern = pattern(request);
        if (pattern.isEmpty() || !pattern.get().accepts(hint(request))) {
            return Optional.of("unknown_pattern_or_mark");
        }
        if (BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class)).isEmpty()) {
            return Optional.of("building_unavailable");
        }
        try {
            settings(pattern.get(), request.settings());
        } catch (IllegalArgumentException invalid) {
            return Optional.of("invalid_setting_value");
        }
        return Optional.empty();
    }

    private static Chosen settings(Pattern pattern, CompoundTag saved) {
        CompoundTag effective = Chosen.of(pattern.knobs(), ColonySettings.byDefault(pattern.knobs())).save();
        for (String key : saved.getAllKeys()) {
            Schema.Setting declared = pattern.knobs().find(key).orElseThrow(IllegalArgumentException::new);
            ColonySettings.Value value = switch (declared) {
                case Schema.Setting.Flag ignored -> {
                    require(saved.contains(key, Tag.TAG_BYTE));
                    yield new ColonySettings.Value.Flag(saved.getBoolean(key));
                }
                case Schema.Setting.Count ignored -> {
                    require(saved.contains(key, Tag.TAG_INT));
                    yield new ColonySettings.Value.Count(saved.getInt(key));
                }
                case Schema.Setting.Choice ignored -> {
                    require(saved.contains(key, Tag.TAG_STRING));
                    ResourceLocation chosen = ResourceLocation.tryParse(saved.getString(key));
                    require(chosen != null);
                    yield new ColonySettings.Value.Choice(chosen);
                }
                case Schema.Setting.Items ignored -> {
                    require(saved.contains(key, Tag.TAG_COMPOUND));
                    if (saved.getCompound(key).isEmpty()) {
                        yield new ColonySettings.Value.Items(List.of());
                    }
                    ItemSpec spec = Reader.of(saved).child(key).flatMap(ItemSpec::load)
                        .orElseThrow(IllegalArgumentException::new);
                    yield new ColonySettings.Value.Items(filters(spec));
                }
            };
            var valid = SettingChanges.validated(declared, value).orElseThrow(IllegalArgumentException::new);
            switch (valid) {
                case ColonySettings.Value.Flag flag -> effective.putBoolean(key, flag.value());
                case ColonySettings.Value.Count count -> effective.putInt(key, count.value());
                case ColonySettings.Value.Choice choice -> effective.putString(key, choice.value().toString());
                case ColonySettings.Value.Items items -> {
                    var chosen = ColonySettings.empty().with(key, items).items(key);
                    if (chosen.isPresent()) {
                        effective.put(key, chosen.get().save());
                    } else {
                        effective.remove(key);
                    }
                }
            }
        }
        return Chosen.load(pattern.knobs(), effective);
    }

    private static List<ItemFilter> filters(ItemSpec spec) {
        return (spec.anyOf().isEmpty() ? List.of(spec) : spec.anyOf().stream().toList()).stream().map(one ->
            one.item().map(ItemFilter::item)
                .orElseGet(() -> ItemFilter.tag(
                    one.tag().orElseThrow(IllegalArgumentException::new).location()))).toList();
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("invalid setting value");
        }
    }

    @Override
    public String fingerprint(Commissioning.Request request) {
        return Patterns.sources().toString();
    }

    @Override
    public CompletableFuture<Commissioning.Drawing> draw(ServerLevel level, Colony colony,
            Commissioning.Request request) {
        Optional<String> refusal = refusal(level, colony, request);
        if (refusal.isPresent()) {
            String code = refusal.get();
            return CompletableFuture.completedFuture(new Commissioning.Drawing.Refused(code,
                Commissioning.refusal(code).orElseThrow()));
        }
        Pattern pattern = pattern(request).orElseThrow();
        Hint hint = hint(request);
        var world = pattern.capture(level, hint);
        Commission commission = new Commission(hint, settings(pattern, request.settings()), world, request.facing(),
            Patterns.seedOf(pattern, hint));
        return Rounds.onServer(level, Drafting.draw(pattern, commission)).thenApply(drawn -> {
            if (drawn instanceof Drawn.Refused refused) {
                return new Commissioning.Drawing.Refused(refused.why().name().toLowerCase(Locale.ROOT),
                    refused.component(), authored != null && (refused.why() == DraftRefusal.PATTERN_FAILED
                        || refused.why() == DraftRefusal.MEMORY_LIMIT) ? refused.detail() : "");
            }
            return summarize(level, (Drawn.Ready) drawn, hint, request.pattern());
        });
    }

    private static Commissioning.Drawing summarize(ServerLevel level, Drawn.Ready ready, Hint hint,
            ResourceLocation pattern) {
        var draft = ready.draft();
        if (draft.cells().size() > BlueprintBook.maxCaptureBlocks()) {
            return new Commissioning.Drawing.Refused("site_too_large", Component.translatable(
                "folkways.blueprint.too_large", BlueprintBook.maxCaptureBlocks()));
        }
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        List<Ghost> ghosts = new ArrayList<>();
        var hash = Hashing.sha256().newHasher();
        for (var cell : draft.cells()) {
            BlockPos at = ready.corner().offset(cell.offset());
            var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            if (chunk == null || !level.isInWorldBounds(at) || !level.getWorldBorder().isWithinBounds(at)) {
                return new Commissioning.Drawing.Refused("unloaded", Component.translatable(
                    "folkways.refusal.draft.unloaded", at.toShortString()));
            }
            BlockState standing = chunk.getBlockState(at);
            cells.put(at, cell.state());
            if (!standing.equals(cell.state())) {
                ghosts.add(new Ghost(at, cell.state(), cell.payload()));
            }
            hash.putLong(at.asLong()).putUnencodedChars(cell.state().toString())
                .putUnencodedChars(standing.toString());
        }
        Map<ResourceLocation, Long> materials = new TreeMap<>();
        BuildTarget target = new BuildTarget(new Realm.Dimension(level.dimension()), cells);
        target.shots().values().stream().distinct().forEach(shot ->
            target.costs(level, shot, Set.of()).ifPresent(needs -> needs.forEach(need ->
                need.spec().item().ifPresent(item -> materials.merge(item, need.count(), Long::sum)))));
        var joints = draft.joints().stream().map(joint -> joint.moved(ready.corner())).toList();
        var jointCost = Joints.joiner().preview(level, cells, joints);
        if (jointCost instanceof Joints.Preview.Unavailable) {
            return new Commissioning.Drawing.Refused("joiner_unavailable", Component.translatable(
                DraftRefusal.JOINER_UNAVAILABLE.translationKey(), "create"));
        }
        if (jointCost instanceof Joints.Preview.Refused refused) {
            var joint = refused.joint();
            return new Commissioning.Drawing.Refused("joint_unconnectable", Component.translatable(
                "folkways.refusal.draft.joint_unconnectable",
                joint.from().toShortString(), joint.to().toShortString()));
        }
        ((Joints.Preview.Ready) jointCost).materials().forEach(stack ->
            materials.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), (long) stack.getCount(), Long::sum));
        hash.putUnencodedChars(draft.joints().toString()).putUnencodedChars(materials.toString());
        BlockPos from = ready.corner();
        var size = draft.extent().size();
        BlockPos to = from.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        List<BlockPos> anchors = switch (hint) {
            case Hint.Path path -> ready.resolvedPath().isEmpty() ? path.points() : ready.resolvedPath();
            case Hint.Zone zone -> List.of(zone.min(), zone.max());
        };
        Optional<Commissioning.Drawing.RailConnections> connections = Optional.empty();
        if (pattern.equals(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "railway"))) {
            var reports = ready.reports();
            connections = Optional.of(new Commissioning.Drawing.RailConnections(
                reports.getOrDefault("rail_start_connected", 0.0) > 0,
                reports.getOrDefault("rail_end_connected", 0.0) > 0,
                reports.getOrDefault("rail_nearby", 0.0) > 0));
        }
        hash.putUnencodedChars(anchors.toString()).putUnencodedChars(connections.toString());
        return new Commissioning.Drawing.Ready(from, to, draft.cells().size(), materials, draft.joints().size(),
            hash.hash().toString(), Ghost.limited(ghosts, Ghost.MAX_PREVIEW_GHOSTS, from), anchors, connections);
    }

    @Override
    public CompletableFuture<Commissioning.Result> commission(ServerPlayer player, ServerLevel level, Colony colony,
            Commissioning.Request request) {
        Optional<String> refusal = refusal(level, colony, request);
        if (refusal.isPresent()) {
            String code = refusal.get();
            return CompletableFuture.completedFuture(new Commissioning.Result(false,
                Commissioning.refusal(code).orElseThrow(), refusal));
        }
        Pattern pattern = pattern(request).orElseThrow();
        var site = BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class)).orElseThrow();
        return Commissions.file(level, site, pattern, hint(request), settings(pattern, request.settings()),
                request.facing(), "", Optional.of(player.getUUID()), player.getGameProfile().getName(),
                Optional.ofNullable(written))
            .thenApply(said -> new Commissioning.Result(said.filed(), said.message()));
    }

    @Override
    public CompletableFuture<Commissioning.Result> raise(ServerPlayer player, ServerLevel level, Colony colony,
            Commissioning.Request request) {
        if (!player.isCreative()) {
            return commission(player, level, colony, request);
        }
        Optional<String> refusal = refusal(level, colony, request);
        if (refusal.isPresent()) {
            String code = refusal.get();
            return CompletableFuture.completedFuture(new Commissioning.Result(false,
                Commissioning.refusal(code).orElseThrow(), refusal));
        }
        Pattern pattern = pattern(request).orElseThrow();
        return Rounds.drawOnce(level, pattern, hint(request), settings(pattern, request.settings()), request.facing())
            .thenCompose(once -> once instanceof Rounds.Once.Drafted drafted
                ? BuildPackets.raised(level, drafted.blueprint(), drafted.corner())
                : CompletableFuture.completedFuture(new Commissioning.Result(false,
                    ((Rounds.Once.Refused) once).why(), Optional.of("raise_failed"))));
    }
}
