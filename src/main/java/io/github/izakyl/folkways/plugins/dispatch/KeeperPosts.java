package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.Grown;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

final class KeeperPosts {

    private final Vocation trade = DispatchContent.trade();

    private final Runnable changed;

    private final Map<WorldPos, Post> keeping = new ConcurrentHashMap<>();

    private volatile Map<WorldPos, Post> open = Map.of();

    KeeperPosts(Runnable changed) {
        this.changed = changed;
    }

    void reconcile(Collection<Post> found) {
        Map<WorldPos, Post> next = new LinkedHashMap<>();
        for (Post post : found) {
            next.put(post.seat(), post);
        }
        open = Map.copyOf(next);
    }

    void goals(ServerLevel level, List<Grown> into) {
        Map<WorldPos, Post> wanted = new LinkedHashMap<>(keeping);
        open.forEach(wanted::putIfAbsent);
        for (Post post : wanted.values()) {
            if (post.seat().in(level)) {
                into.add(Grown.of(new KeepPostNode(idOf(post), trade, post, this)));
            }
        }
    }

    void keeping(Post post) {
        keeping.put(post.seat(), post);
        changed.run();
    }

    void left(WorldPos seat) {
        keeping.remove(seat);
        changed.run();
    }

    private static UUID idOf(Post post) {
        return UUID.nameUUIDFromBytes(("folkways:dispatch/" + post.seat() + "/keep")
            .getBytes(StandardCharsets.UTF_8));
    }
}
