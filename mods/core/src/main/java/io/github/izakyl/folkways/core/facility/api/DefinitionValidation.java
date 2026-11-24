package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Problem;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

final class DefinitionValidation {

    private static final int MAX_CELLS = 262144;

    private DefinitionValidation() {
    }

    public static Optional<Problem> checkBounds(ServerLevel level, FacilityExtent extent) {
        long count = 0;
        for (FacilityExtent.Element element : extent.elements()) {
            if (level == null) {
                return Optional.of(new Problem("extent", "unloaded", ""));
            }
            if (element instanceof FacilityExtent.Entity entity) {
                if (level.getEntity(entity.id()) == null) {
                    return Optional.of(new Problem("extent", "unloaded", entity.id().toString()));
                }
                if (++count > MAX_CELLS) {
                    return Optional.of(new Problem("extent", "size", Integer.toString(MAX_CELLS)));
                }
                continue;
            }
            FacilityExtent.Box box = switch (element) {
                case FacilityExtent.Box bounds -> bounds;
                case FacilityExtent.Block block ->
                    new FacilityExtent.Box(block.pos().realm(), block.pos().cell(), block.pos().cell());
                case FacilityExtent.Entity ignored -> throw new IllegalStateException();
            };
            long x = (long) box.max().getX() - box.min().getX() + 1;
            long y = (long) box.max().getY() - box.min().getY() + 1;
            long z = (long) box.max().getZ() - box.min().getZ() + 1;
            if (x > MAX_CELLS || y > MAX_CELLS || z > MAX_CELLS || (count += x * y * z) > MAX_CELLS) {
                return Optional.of(new Problem("extent", "size", Integer.toString(MAX_CELLS)));
            }
            WorldPos start = new WorldPos(box.realm(), box.min());
            WorldPos end = start.at(box.max());
            if (!start.in(level) || !end.in(level)) {
                return Optional.of(new Problem("extent.space", "unloaded", ""));
            }
            if (box.realm() instanceof Realm.Frame frame && !FacilityExtent.structure(level, frame.structure())
                    .filter(body -> body.contains(start) && body.contains(end)).isPresent()) {
                return Optional.of(new Problem("extent", "structure", frame.structure().toString()));
            }
            if (!ServerLevel.isInSpawnableBounds(start.block(level))
                    || !ServerLevel.isInSpawnableBounds(end.block(level))
                    || level.isOutsideBuildHeight(start.block(level)) || level.isOutsideBuildHeight(end.block(level))) {
                return Optional.of(new Problem("extent", "bounds", ""));
            }
        }
        return Optional.empty();
    }

    static List<Problem> check(ServerLevel level, FacilityDefinition facility, Posts posts,
            Map<FacilityScripts.Source, FacilityScripts.Compiled> programs) {
        List<Problem> issues = new ArrayList<>();
        checkBounds(level, facility.extent()).ifPresent(issues::add);
        if (!issues.isEmpty()) {
            return issues;
        }
        boolean entityOnly = !facility.extent().elements().isEmpty() && facility.extent().elements().stream()
            .allMatch(FacilityExtent.Entity.class::isInstance);
        if (!facility.anchor().in(level) || (!entityOnly && !facility.extent().contains(facility.anchor()))) {
            issues.add(new Problem("anchor", "outside", ""));
        }
        Map<String, Control> resolved = new LinkedHashMap<>();
        var refs = new HashSet<>();
        var live = facility.resolve(level);
        facility.controls().forEach((name, placement) -> {
            var ref = facility.ref(name);
            String path = "controls." + name;
            Control control = live.get(name);
            if (placement.entity().isPresent() && (placement.carrier().isPresent()
                    || !facility.extent().contains(placement.entity().orElseThrow()))) {
                issues.add(new Problem(path + ".entity", "outside", ""));
            } else if (!validCarrier(facility, name)) {
                issues.add(new Problem(path + ".carried_by", "invalid", name));
            } else if (placement.entity().isEmpty()
                    && (!fits(facility.anchor(), placement.offset(), ref.at())
                        || (control == null || control.entityId().isEmpty()
                            ? !facility.extent().contains(ref.at())
                            : !facility.extent().contains(control.entityId().orElseThrow())))) {
                issues.add(new Problem(path + ".offset", "outside", ""));
            } else if (!refs.add(List.of(ref, placement.entity()))) {
                issues.add(new Problem(path + ".part", "duplicate", placement.part()));
            } else {
                Optional.ofNullable(control).filter(found -> found.valid(level))
                    .ifPresentOrElse(found -> resolved.put(name, found),
                        () -> issues.add(new Problem(path + ".part", "control", placement.part())));
            }
        });
        facility.posts().forEach((name, post) -> {
            String path = "posts." + name;
            for (String control : post.controls()) {
                if (!facility.controls().containsKey(control)) {
                    issues.add(new Problem(path + ".controls", "missing", control));
                }
            }
            boolean inside = switch (post.stance()) {
                case PostDefinition.SeatEntity seat -> facility.extent().contains(seat.entity());
                case PostDefinition.Footing footing -> postWithin(facility, footing.offset());
                case PostDefinition.SeatBlock seat -> postWithin(facility, seat.offset());
            };
            if (!inside) {
                issues.add(new Problem(path + ".stance", "outside", ""));
            } else if (post.resolve(level, facility, name, Set.of()).isEmpty()) {
                issues.add(new Problem(path + ".stance", "post_stance", ""));
            }
        });
        var readingNames = new HashSet<String>();
        resolved.forEach((name, control) -> {
            control.readings().keySet().forEach(suffix -> readingNames.add(name + "." + suffix));
            if (control instanceof Signal signal && signal.feedback().isPresent()
                    || control instanceof io.github.izakyl.folkways.core.facility.api.control.Motion) {
                readingNames.add(name);
            }
        });
        Set<String> publishers = new HashSet<>();
        facility.routines().forEach((name, use) -> {
            var source = FacilityScriptRuns.source(use);
            var program = source == null ? null : programs.get(source);
            if (program != null && program.program().definesFunction("readings")) {
                publishers.add(name);
            }
        });
        Predicate<String> hasReading = name -> readingNames.contains(name)
            || publishers.stream().anyMatch(routine -> name.startsWith(routine + "."));
        facility.probes().forEach((name, probe) -> {
            if (probe.source().isPresent()) {
                readingNames.add(name);
                return;
            }
            WorldPos at = facility.anchor().at(facility.anchor().cell().offset(probe.offset()));
            if (!probe.config().isEmpty() && (!facility.extent().contains(at) || !fits(facility.anchor(), probe.offset(), at))) {
                issues.add(new Problem("readings." + name + ".offset", "outside", ""));
            }
            var registered = Readings.probe(probe.probe());
            if (registered.isEmpty()) {
                issues.add(new Problem("readings." + name + ".probe", "missing", probe.probe().toString()));
            } else if (!registered.get().valid(probe.config())) {
                issues.add(new Problem("readings." + name, "invalid", ""));
            } else {
                readingNames.add(name);
            }
        });
        facility.routines().forEach((name, declared) -> {
            String path = "routines." + name;
            Routine.Use use;
            try {
                use = declared.resolve(facility.parameters());
            } catch (IllegalArgumentException missing) {
                issues.add(new Problem(path + ".with", "parameter", missing.getMessage()));
                return;
            }
            if (use.routine().equals(Routines.SCRIPT)) {
                return;
            }
            Optional<Routine> routine = Routines.of(use.routine());
            if (routine.isEmpty()) {
                issues.add(new Problem(path + ".use", "missing", use.routine().toString()));
            } else if (use.routine().equals(Routines.WRITE_ALL)) {
                use.with().forEach((control, value) ->
                    target(issues, path + ".with." + control, resolved, control, value, false));
            } else {
                try {
                    if (routine.get() instanceof WriteSequence sequence) {
                        sequence.validate(level, facility, use, posts.resolve(level, facility));
                    } else {
                        routine.get().validate(level, facility, use);
                    }
                } catch (IllegalArgumentException invalid) {
                    issues.add(new Problem(path + ".with", "refused", invalid.getMessage()));
                }
            }
        });
        facility.workshop().ifPresent(workshop -> {
            Routine.Use use = facility.routines().get(workshop.trigger().routine());
            if (use == null || !use.routine().equals(Routines.SCRIPT)) {
                issues.add(new Problem("workshop.routine", "invalid", workshop.trigger().routine()));
            }
        });
        facility.switches().forEach((name, entry) -> {
            String path = "triggers." + name;
            Routine.Use use = facility.routines().get(entry.trigger().routine());
            if (use == null) {
                issues.add(new Problem(path + ".routine", "missing", entry.trigger().routine()));
            } else if (entry.trigger() instanceof Trigger.Condition condition) {
                predicates(issues, path + ".when", condition.when(), hasReading, resolved);
                if (condition.source().isEmpty()) {
                    checkWithoutArguments(issues, path + ".routine", level, facility, condition.routine());
                } else if (TriggerSources.of(condition.source().get()).isEmpty()) {
                    issues.add(new Problem(path + ".source", "missing", condition.source().get().toString()));
                }
            } else if (entry.trigger() instanceof Trigger.Schedule) {
                checkWithoutArguments(issues, path + ".routine", level, facility, entry.trigger().routine());
            }
        });
        ui(issues, "ui.card", facility.card().elements(), facility, level, hasReading, resolved);
        overlays(issues, facility, hasReading);
        for (int i = 0; i < facility.gates().size(); i++) {
            FacilityDefinition.Gate gate = facility.gates().get(i);
            String path = "projections[" + i + "].gate";
            if (gate.cells().isEmpty() || gate.cells().stream()
                    .anyMatch(cell -> !facility.extent().contains(facility.anchor().at(cell)))) {
                issues.add(new Problem(path + ".cells", "gate_cells", ""));
            }
            if (gate.axes().filter(axes -> axes.stream().anyMatch(axis -> !axis.equals("x") && !axis.equals("z")))
                    .isPresent()) {
                issues.add(new Problem(path + ".axes", "invalid", ""));
            }
            if (gate.openWhen().isEmpty()) {
                issues.add(new Problem(path + ".open_when", "invalid", ""));
            }
            if (FacilitySamples.predicate(gate.openWhen()).isEmpty()) {
                gate.openWhen().forEach((control, value) ->
                    target(issues, path + ".open_when." + control, resolved, control, value, true));
            }
        }
        for (int i = 0; i < facility.rides().size(); i++) {
            var ride = facility.rides().get(i);
            String path = "projections.ride[" + i + "]";
            if (!facility.routines().containsKey(ride.routine())) {
                issues.add(new Problem(path + ".routine", "missing", ride.routine()));
            }
            if (ride.stops().size() < 2) {
                issues.add(new Problem(path + ".stops", "invalid", ""));
            }
            ride.stops().forEach((name, cells) -> {
                if (name.isBlank() || cells.isEmpty() || cells.stream()
                        .anyMatch(cell -> !facility.extent().contains(facility.anchor().at(cell)))) {
                    issues.add(new Problem(path + ".stops." + name + ".cells", "invalid", ""));
                }
            });
            if (ride.ticks() < 1) {
                issues.add(new Problem(path + ".ticks", "invalid", ""));
            }
            if (ride.capacity() < 1) {
                issues.add(new Problem(path + ".capacity", "invalid", ""));
            }
            if (FacilitySamples.predicate(ride.availableWhen()).isEmpty()) {
                ride.availableWhen().forEach((control, value) ->
                    target(issues, path + ".available_when." + control, resolved, control, value, true));
            }
        }
        for (int i = 0; i < facility.keepouts().size(); i++) {
            var keepout = facility.keepouts().get(i);
            String path = "projections.keepout[" + i + "]";
            for (String part : keepout.parts()) {
                if (!(resolved.get(part) instanceof io.github.izakyl.folkways.core.facility.api.control.Motion)) {
                    issues.add(new Problem(path + ".while_moving", "invalid", part));
                }
            }
        }
        for (int i = 0; i < facility.lanes().size(); i++) {
            FacilityDefinition.Lane lane = facility.lanes().get(i);
            String path = "projections.lane[" + i + "]";
            lanePort(issues, path + ".from", resolved, lane.from());
            lanePort(issues, path + ".to", resolved, lane.to());
            if (lane.automatic() && resolved.get(lane.from()) instanceof Port<?> port
                    && !port.part().equals(Ports.part(ResourceKind.ITEM))) {
                issues.add(new Problem(path + ".from", "port", lane.from()));
            }
            if (lane.from().equals(lane.to()) || lane.latency() < 1) {
                issues.add(new Problem(path, "invalid", ""));
            }
        }
        return List.copyOf(issues);
    }

    private static boolean validCarrier(FacilityDefinition facility, String part) {
        Set<String> visited = new HashSet<>();
        while (visited.add(part)) {
            var placement = facility.controls().get(part);
            if (placement == null) {
                return false;
            }
            if (placement.carrier().isEmpty()) {
                return true;
            }
            part = placement.carrier().get().part();
        }
        return false;
    }

    private static void lanePort(List<Problem> issues, String path, Map<String, Control> controls, String name) {
        if (!(controls.get(name) instanceof Port<?> port) || !port.resource().equals(ResourceKind.ITEM)) {
            issues.add(new Problem(path, "port", name));
        }
    }

    private static boolean postWithin(FacilityDefinition facility, net.minecraft.core.BlockPos offset) {
        WorldPos at = facility.anchor().at(facility.anchor().cell().offset(offset));
        return fits(facility.anchor(), offset, at) && facility.extent().contains(at);
    }

    private static boolean fits(WorldPos anchor, net.minecraft.core.BlockPos offset, WorldPos at) {
        return (long) anchor.cell().getX() + offset.getX() == at.cell().getX()
            && (long) anchor.cell().getY() + offset.getY() == at.cell().getY()
            && (long) anchor.cell().getZ() + offset.getZ() == at.cell().getZ();
    }

    private static void checkWithoutArguments(List<Problem> issues, String path, ServerLevel level, FacilityDefinition facility,
                                              String name) {
        Routine.Use use = facility.routines().get(name);
        if (use != null && use.routine().equals(Routines.SCRIPT)) {
            return;
        }
        try {
            Routines.run(level, facility, name, Map.of(), DefinedFacilities.ID);
        } catch (IllegalArgumentException refused) {
            issues.add(new Problem(path, "refused", refused.getMessage()));
        }
    }

    private static void ui(List<Problem> issues, String path, List<Card.Element> elements, FacilityDefinition facility,
                           ServerLevel level, Predicate<String> readings, Map<String, Control> controls) {
        for (int i = 0; i < elements.size(); i++) {
            String field = path + "[" + i + "]";
            switch (elements.get(i)) {
                case Card.Element.Button button -> {
                    if (facility.triggers().stream().noneMatch(trigger -> trigger instanceof Trigger.Manual
                            && trigger.routine().equals(button.routine()))) {
                        issues.add(new Problem(field + ".button", "manual", button.routine()));
                    } else {
                        checkWithoutArguments(issues, field + ".button", level, facility, button.routine());
                    }
                    icon(issues, field, button.icon());
                    predicates(issues, field + ".enabled_when", button.enabledWhen(), readings, controls);
                }
                case Card.Element.Target target -> {
                    if (facility.triggers().stream().noneMatch(trigger -> trigger instanceof Trigger.Manual
                            && trigger.routine().equals(target.routine()))) {
                        issues.add(new Problem(field + ".target", "manual", target.routine()));
                    }
                    if (target.label().filter(label -> label.text().length() > 256).isPresent()) {
                        issues.add(new Problem(field + ".label", "invalid", ""));
                    }
                    icon(issues, field, target.icon());
                    predicates(issues, field + ".enabled_when", target.enabledWhen(), readings, controls);
                }
                case Card.Element.Toggle toggle -> {
                    icon(issues, field, toggle.icon());
                    FacilityDefinition.Switch entry = facility.switches().get(toggle.trigger());
                    if (entry == null || !entry.trigger().switchable()) {
                        issues.add(new Problem(field + ".toggle", "background", toggle.trigger()));
                    }
                }
                case Card.Element.Parameter parameter -> {
                    icon(issues, field, parameter.icon());
                    if (parameter instanceof Card.Element.Choice choice) {
                        choice(issues, field, choice, facility, readings, controls);
                    }
                    boolean valid = switch (parameter) {
                        case Card.Element.Choice choice -> !choice.options().isEmpty()
                            && choice.options().size() <= 256
                            && Set.copyOf(choice.options()).size() == choice.options().size()
                            && choice.options().stream().noneMatch(value -> value.isBlank() || value.length() > 256)
                            && (choice.labels().isEmpty() || choice.labels().size() == choice.options().size())
                            && choice.labels().stream().noneMatch(label -> label.length() > 256);
                        case Card.Element.Number number -> Double.isFinite(number.min())
                            && Double.isFinite(number.max()) && Double.isFinite(number.step())
                            && number.min() <= number.max() && number.step() > 0
                            && number.label().filter(label -> label.text().length() > 256).isEmpty()
                            && number.initial().map(initial -> number.accepts(Value.of(initial))).orElse(true);
                        case Card.Element.Flag flag -> flag.label().filter(label -> label.text().length() > 256).isEmpty();
                        case Card.Element.Items items -> items.label().filter(label -> label.text().length() > 256).isEmpty()
                            && items.accepts(new Value.Items(items.initial()));
                    };
                    if (!valid || parameter.param().length() > 256) {
                        issues.add(new Problem(field, "parameter", parameter.param()));
                    } else if (!parameter.accepts(facility.parameters().get(parameter.param()))) {
                        issues.add(new Problem("parameters." + parameter.param(), "parameter", parameter.param()));
                    }
                }
                case Card.Element.Readout readout -> {
                    if (!readings.test(readout.reading()) && facility.posts().keySet().stream()
                            .noneMatch(name -> ("posts." + name + ".staffed").equals(readout.reading()))) {
                        issues.add(new Problem(field + ".readout", "missing", readout.reading()));
                    }
                }
                // Lines and rows come from a facility's Java code; a definition has none to show.
                case Card.Element.Lines ignored -> issues.add(new Problem(field + ".lines", "unsupported", ""));
                case Card.Element.Rows rows -> issues.add(new Problem(field + ".rows", "unsupported", rows.source()));
                case Card.Element.Heading heading -> {
                    if (heading.text().text().isBlank() || heading.text().text().length() > 256) {
                        issues.add(new Problem(field + ".heading", "invalid", ""));
                    }
                }
                case Card.Element.Note note -> {
                    if (note.text().text().isBlank() || note.text().text().length() > 512) {
                        issues.add(new Problem(field + ".note", "invalid", ""));
                    }
                }
            }
        }
    }

    private static void choice(List<Problem> issues, String field, Card.Element.Choice choice, FacilityDefinition facility,
                               Predicate<String> readings, Map<String, Control> controls) {
        if (!choice.icons().isEmpty() && choice.icons().size() != choice.options().size()) {
            issues.add(new Problem(field + ".icons", "invalid", ""));
        }
        for (String item : choice.icons()) {
            if (!item.isEmpty()) {
                var id = Optional.ofNullable(ResourceLocation.tryParse(item));
                if (id.isEmpty()) {
                    issues.add(new Problem(field + ".icons", "invalid", ""));
                } else {
                    icon(issues, field + ".icons", id, "invalid");
                }
            }
        }
        if (choice.label().filter(label -> label.text().length() > 256).isPresent()) {
            issues.add(new Problem(field + ".label", "invalid", ""));
        }
        choice.initial().filter(value -> !choice.options().contains(value))
            .ifPresent(value -> issues.add(new Problem(field + ".default", "invalid", value)));
        choice.run().filter(run -> !facility.routines().containsKey(run) || facility.triggers().stream().noneMatch(
                trigger -> trigger instanceof Trigger.Manual && trigger.routine().equals(run)))
            .ifPresent(run -> issues.add(new Problem(field + ".run", "manual", run)));
        predicates(issues, field + ".enabled_when", choice.enabledWhen(), readings, controls);
    }

    private static void overlays(List<Problem> issues, FacilityDefinition facility, Predicate<String> readings) {
        if (facility.card().overlays().size() > 16) {
            issues.add(new Problem("ui.overlay", "invalid", ""));
        }
        for (int i = 0; i < facility.card().overlays().size(); i++) {
            String field = "ui.overlay[" + i + "]";
            switch (facility.card().overlays().get(i)) {
                case Card.Overlay.Range range -> {
                    Value parameter = facility.parameters().get(range.reading());
                    if (parameter == null ? !readings.test(range.reading())
                            : !(parameter instanceof Value.Real real) || !(real.value() > 0)) {
                        issues.add(new Problem(field + ".range", "missing", range.reading()));
                    }
                }
                case Card.Overlay.Path path -> {
                    if (!facility.routines().containsKey(path.routine()) || path.routine().length() > 256) {
                        issues.add(new Problem(field + ".path", "missing", path.routine()));
                    }
                }
            }
        }
    }

    private static void predicates(List<Problem> issues, String path, Map<String, Value> predicates,
                                   Predicate<String> readings, Map<String, Control> controls) {
        if (FacilitySamples.predicate(predicates).isPresent()) {
            return;
        }
        predicates.forEach((name, value) -> {
            String at = path + "." + name;
            if (!readings.test(name)) {
                issues.add(new Problem(at, "missing", name));
            } else if (controls.get(name) instanceof Signal signal && signal.type().check(value).isPresent()) {
                issues.add(new Problem(at, "type", name));
            }
        });
    }

    private static void icon(List<Problem> issues, String field, Optional<ResourceLocation> icon) {
        icon(issues, field + ".icon", icon, "icon");
    }

    private static void icon(List<Problem> issues, String path, Optional<ResourceLocation> icon, String reason) {
        icon.filter(id -> !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)
                || id.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getDefaultKey()))
            .ifPresent(id -> issues.add(new Problem(path, reason, id.toString())));
    }

    private static void target(List<Problem> issues, String path, Map<String, Control> controls, String name,
                               Value value, boolean feedback) {
        if (!(controls.get(name) instanceof Signal signal)) {
            issues.add(new Problem(path, "signal", name));
        } else if (signal.type().check(value).isPresent()) {
            issues.add(new Problem(path, "type", name));
        } else if (feedback && (signal.type() instanceof SignalType.Pulse || signal.feedback().isEmpty())) {
            issues.add(new Problem(path, "feedback", name));
        }
    }

}
