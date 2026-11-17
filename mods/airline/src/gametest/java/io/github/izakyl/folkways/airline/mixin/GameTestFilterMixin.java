package io.github.izakyl.folkways.airline.mixin;

import io.github.izakyl.folkways.airline.GameTestMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Selects tests after grouping so reduced reproductions retain the full suite's batch order and boundaries. */
@Mixin(GameTestBatchFactory.class)
public abstract class GameTestFilterMixin {
    @Inject(method = "fromTestFunction", at = @At("RETURN"), cancellable = true)
    private static void airline$filterTests(CallbackInfoReturnable<Collection<GameTestBatch>> callback) {
        for (GameTestBatch batch : callback.getReturnValue()) {
            batch.gameTestInfos().forEach(test -> test.addListener(new GameTestMetrics()));
        }
        String filter = System.getProperty("folkways.airline.testFilter");
        Set<String> methods = Arrays.stream(System.getProperty("folkways.testMethods", "").split(","))
            .map(String::trim).filter(name -> !name.isEmpty()).map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        if (filter != null || !methods.isEmpty()) {
            Pattern pattern = Pattern.compile(filter == null ? ".*" : filter, Pattern.CASE_INSENSITIVE);
            Collection<GameTestBatch> selected = new ArrayList<>();
            for (GameTestBatch batch : callback.getReturnValue()) {
                var tests = batch.gameTestInfos().stream()
                    .filter(test -> pattern.matcher(test.getTestName()).matches())
                    .filter(test -> methods.isEmpty() || methods.contains(test.getTestName()
                        .substring(test.getTestName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))).toList();
                if (!tests.isEmpty()) {
                    selected.add(new GameTestBatch(batch.name(), tests,
                        batch.beforeBatchFunction(), batch.afterBatchFunction()));
                }
            }
            callback.setReturnValue(selected);
        }
        String shard = System.getProperty("folkways.airline.testShard", "");
        if (!shard.isEmpty()) {
            String[] parts = shard.split("/", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("gameTestShard must be zero-based i/n");
            }
            int index = Integer.parseInt(parts[0]);
            int count = Integer.parseInt(parts[1]);
            if (count < 1 || index < 0 || index >= count) {
                throw new IllegalArgumentException("gameTestShard requires 0 <= i < n");
            }
            var batches = new ArrayList<>(callback.getReturnValue());
            batches.sort(Comparator.comparingLong(GameTestFilterMixin::airline$weight).reversed()
                .thenComparing(GameTestBatch::name));
            long[] loads = new long[count];
            Set<GameTestBatch> assigned = new HashSet<>();
            for (GameTestBatch batch : batches) {
                int target = 0;
                for (int candidate = 1; candidate < count; candidate++) {
                    if (loads[candidate] < loads[target]) {
                        target = candidate;
                    }
                }
                loads[target] += airline$weight(batch);
                if (target == index) {
                    assigned.add(batch);
                }
            }
            var selected = callback.getReturnValue().stream().filter(assigned::contains).toList();
            callback.setReturnValue(selected);
            LoggerFactory.getLogger(GameTestFilterMixin.class).info(
                "AIRLINE_TEST_SHARD shard={} batches={} tests={} weight={}", shard, selected.size(),
                selected.stream().mapToInt(batch -> batch.gameTestInfos().size()).sum(), loads[index]);
            selected.forEach(batch -> batch.gameTestInfos().forEach(test ->
                LoggerFactory.getLogger(GameTestFilterMixin.class).info("AIRLINE_TEST_SELECTED name={}",
                    test.getTestName())));
        }
    }

    private static long airline$weight(GameTestBatch batch) {
        return batch.gameTestInfos().stream().mapToLong(test -> test.getTimeoutTicks()).sum();
    }
}
