package example;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.task.Jobs;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.effect.perk.Perk;
import io.github.izakyl.folkways.effect.perk.Perks;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Compiled as an unrelated add-on. Never run: game behavior belongs to the complete runtime mods. */
public final class Extension {
    public Effects effects(Body body) {
        Perks perks = body.perks();
        int rank = perks.rank("example");
        return rank > 0 ? perks.effects().with(body.effects()) : Effects.NONE;
    }

    public void declare(Declaring event, ResourceLocation id, Perk perk) {
        List<Modifier> modifiers = perk.grant().at(1);
        List<Perk> perks = modifiers.isEmpty() ? List.of() : List.of(perk);
        event.vocation(new VocationSpec(id, perks));
    }

    public Optional<WorldPos> restore(WorldPos address, PastDay history) {
        CompoundTag saved = new CompoundTag();
        saved.put("address", address.save());
        saved.put("history", history.save());
        Reader reader = Reader.of(saved);
        reader.child("history").ifPresent(history::load);
        return reader.child("address").flatMap(WorldPos::load);
    }

    public Enrollment front(ResourceLocation id, Shape shape) {
        Delegation delegation = new Delegation(id, shape, Schema.none());
        return new Enrollment(List.of(delegation), Schema.none());
    }

    public CompletableFuture<Integer> background(Jobs jobs, Executor owner) {
        Jobs.Scope scope = jobs.scope();
        return jobs.submit(scope, 1, context -> 1, (context, value) -> value + 1, owner, value -> {});
    }
}
