package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public record NodeSpec(
    UUID id,
    ResourceLocation owner,
    WorkSite site,
    Stances stances,
    List<Need> needs,
    List<Amount> gives,
    List<ToolUse> tools,
    Workload workload,
    WorkEffort effort,
    double pace,
    Optional<Vocation> vocation,
    WorkGesture gesture,
    Optional<BlockPos> focus,
    int estimate,
    ResourceLocation doing,
    Optional<String> about,
    Optional<ResourceLocation> subject
) {

    public NodeSpec {
        needs = List.copyOf(needs);
        gives = List.copyOf(gives);
        tools = List.copyOf(tools);
        if (effort == null || workload instanceof Workload.Once && effort instanceof WorkEffort.PerTick
                || workload instanceof Workload.Continuous && effort instanceof WorkEffort.Total) {
            throw new IllegalArgumentException("finite work needs total effort; continuous work needs a rate");
        }
    }

    /**
     * The items the work goes through: what it needs and what it gives, or, for work that moves no goods, the
     * item it is done to, uncounted.
     */
    public Doing.Wares wares() {
        List<Doing.Ware> used = new ArrayList<>();
        for (Need need : needs) {
            Doings.item(need.spec()).ifPresent(item -> used.add(new Doing.Ware(item, need.count())));
        }
        List<Doing.Ware> made = new ArrayList<>();
        for (Amount amount : gives) {
            Doings.item(amount.spec()).ifPresent(item -> made.add(new Doing.Ware(item, amount.count())));
        }
        if (used.isEmpty() && made.isEmpty()) {
            return subject.map(item -> new Doing.Wares(List.of(new Doing.Ware(item, 0L)), List.of()))
                .orElse(Doing.Wares.NONE);
        }
        return new Doing.Wares(used, made);
    }

    public static Builder of(UUID id, ResourceLocation owner, WorkSite site, Stances stances,
            Workload workload) {
        return new Builder(id, owner, site, stances, workload);
    }

    public static final class Builder {

        private final UUID id;
        private final ResourceLocation owner;
        private final WorkSite site;
        private final Stances stances;
        private final Workload workload;
        private WorkEffort effort;
        private List<Need> needs = List.of();
        private List<Amount> gives = List.of();
        private List<ToolUse> tools = List.of();
        private double pace = 1.0D;
        private Optional<Vocation> vocation = Optional.empty();
        private WorkGesture gesture = WorkGesture.NONE;
        private Optional<BlockPos> focus = Optional.empty();
        private int estimate;
        private ResourceLocation doing = Doings.WORKING;
        private Optional<String> about = Optional.empty();
        private Optional<ResourceLocation> subject = Optional.empty();

        private Builder(UUID id, ResourceLocation owner, WorkSite site, Stances stances,
                Workload workload) {
            this.id = id;
            this.owner = owner;
            this.site = site;
            this.stances = stances;
            this.workload = workload;
            this.effort = WorkEffort.standard(workload);
            this.estimate = workload instanceof Workload.Once once
                ? once.baseTicks()
                : 0;
        }

        public Builder needs(List<Need> needs) {
            this.needs = needs;
            return this;
        }

        public Builder needs(Need... needs) {
            return needs(List.of(needs));
        }

        public Builder gives(List<Amount> gives) {
            this.gives = gives;
            return this;
        }

        public Builder gives(Amount... gives) {
            return gives(List.of(gives));
        }

        public Builder tools(List<ToolUse> tools) {
            this.tools = tools;
            return this;
        }

        public Builder tools(ToolUse... tools) {
            return tools(List.of(tools));
        }

        /** Overrides the standard effort derived from base work time (20 ticks per unit).
         * Use NONE for non-labor activities such as eating or sleeping.
         */
        public Builder effort(WorkEffort effort) {
            this.effort = effort;
            return this;
        }

        public Builder pace(double pace) {
            this.pace = pace;
            return this;
        }

        public Builder vocation(Vocation vocation) {
            this.vocation = Optional.ofNullable(vocation);
            return this;
        }

        public Builder vocation(Optional<Vocation> vocation) {
            this.vocation = vocation;
            return this;
        }

        public Builder gesture(WorkGesture gesture) {
            this.gesture = gesture;
            return this;
        }

        public Builder focus(BlockPos focus) {
            this.focus = Optional.ofNullable(focus).map(BlockPos::immutable);
            return this;
        }

        public Builder estimate(int estimate) {
            this.estimate = Math.max(0, estimate);
            return this;
        }

        public Builder doing(ResourceLocation doing) {
            return doing(doing, Optional.empty());
        }

        public Builder doing(ResourceLocation doing, Optional<String> about) {
            this.doing = doing;
            this.about = about;
            return this;
        }

        public Builder doing(ResourceLocation doing, ItemSpec about) {
            this.subject = Doings.item(about);
            return doing(doing, Doings.about(about));
        }

        public Builder doing(ResourceLocation doing, Block about) {
            this.subject = Doings.item(about);
            return doing(doing, Doings.about(about));
        }

        public NodeSpec done() {
            return new NodeSpec(id, owner, site, stances, needs, gives, tools, workload, effort, pace,
                vocation, gesture, focus, estimate, doing, about, subject);
        }
    }
}
