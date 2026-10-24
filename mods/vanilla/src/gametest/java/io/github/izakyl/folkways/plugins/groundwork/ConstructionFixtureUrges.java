package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

// These isolated movement regressions start at an exact supported edge. Optional idle living
// activities can run before the first queued fill is assigned, so exclude only this disposable
// colony's living urge provider. This does not model an ordinary colony's full-AI idle choices:
// native navigation, MoveControl, physics, the actual BodyRunner and all write gates stay enabled.
final class ConstructionFixtureUrges implements AutoCloseable {
    private record Sources(Object scope, ResourceLocation owner, List<Object> live, List<Object> original) { }

    private final GameTestHelper helper;
    private final ColonyData colony;
    private final List<Object> scopes;
    private final List<Sources> before;
    private final Sources living;
    private final Object removed;
    private boolean observed;
    private boolean closed;

    @SuppressWarnings("unchecked")
    static ConstructionFixtureUrges isolate(GameTestHelper helper, ColonyData colony) {
        try {
            Field scopesField = ColonyWorks.class.getDeclaredField("scopes");
            scopesField.setAccessible(true);
            List<Object> scopes = (List<Object>) scopesField.get(colony.works());
            List<Sources> before = new ArrayList<>();
            for (Object scope : scopes) {
                Field ownerField = scope.getClass().getDeclaredField("owner");
                Field sourcesField = scope.getClass().getDeclaredField("urges");
                ownerField.setAccessible(true);
                sourcesField.setAccessible(true);
                List<Object> live = (List<Object>) sourcesField.get(scope);
                before.add(new Sources(scope, (ResourceLocation) ownerField.get(scope), live, List.copyOf(live)));
            }
            List<Sources> selected = before.stream().filter(source -> source.owner().equals(LivingContent.ID)).toList();
            helper.assertTrue(selected.size() == 1, "exactly one living scope belongs to this private fixture colony");
            Sources living = selected.getFirst();
            helper.assertTrue(living.live().size() == 1,
                "the pinned living scope has exactly one identifiable urge provider to exclude");
            Object removed = living.live().getFirst();
            helper.assertTrue(removed == living.original().getFirst(), "the removed provider is the original exact identity");
            living.live().remove(0);
            ConstructionFixtureUrges isolated = new ConstructionFixtureUrges(helper, colony, scopes, before, living, removed);
            isolated.check(true);
            System.out.println("CONSTRUCTION_FIXTURE_URGES isolated colony=" + colony.colonyId()
                + " owner=" + living.owner() + " removed=1 otherProviders=" + isolated.otherProviders());
            return isolated;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("the construction fixture's local living scope could not be isolated", failure);
        }
    }

    private ConstructionFixtureUrges(GameTestHelper helper, ColonyData colony, List<Object> scopes,
            List<Sources> before, Sources living, Object removed) {
        this.helper = helper;
        this.colony = colony;
        this.scopes = scopes;
        this.before = List.copyOf(before);
        this.living = living;
        this.removed = removed;
    }

    void run(ResidentEntity body, Runnable actualRun) {
        check(true);
        if (observed) {
            actualRun.run();
            return;
        }
        observed = true;
        Vec3 position = body.position();
        String doingBefore = doing(body);
        actualRun.run();
        String doingAfter = doing(body);
        helper.assertTrue(position.equals(body.position()), "the real first dispatch never teleports or shifts its preset body");
        helper.assertTrue(!List.of("strolling", "relaxing", "chatting", "petting").contains(doingAfter),
            "this isolated first dispatch cannot replace the exact regression pose with optional idle living work");
        System.out.println("CONSTRUCTION_FIXTURE_URGES firstRun colony=" + colony.colonyId() + " actor=" + body.getUUID()
            + " before=" + position + " after=" + body.position() + " doingBefore=" + doingBefore
            + " doingAfter=" + doingAfter + " removed=1 otherProviders=" + otherProviders());
    }

    private static String doing(ResidentEntity body) {
        return body.doing().map(doing -> doing.what().getPath()).orElse("none");
    }

    private int otherProviders() {
        return before.stream().filter(source -> source != living).mapToInt(source -> source.original().size()).sum();
    }

    private void check(boolean isolated) {
        helper.assertTrue(scopes.size() == before.size(), "fixture isolation keeps every other participating scope");
        int providers = 0;
        int originalProviders = 0;
        for (int i = 0; i < before.size(); i++) {
            Sources source = before.get(i);
            helper.assertTrue(scopes.get(i) == source.scope(), "all scope identities and their order remain unchanged");
            List<Object> expected = isolated && source == living ? List.of() : source.original();
            helper.assertTrue(source.live().size() == expected.size(), "only the one local living provider is excluded");
            for (int at = 0; at < expected.size(); at++) {
                helper.assertTrue(source.live().get(at) == expected.get(at), "every retained provider keeps its exact original identity");
            }
            providers += source.live().size();
            originalProviders += source.original().size();
        }
        helper.assertTrue(originalProviders - providers == (isolated ? 1 : 0), "the exact provider exclusion count is verified");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            check(true);
        } finally {
            living.live().clear();
            living.live().add(removed);
        }
        check(false);
    }
}
