package io.github.izakyl.folkways.core.runtime.mixin;

import io.github.izakyl.folkways.core.testing.SelectedTests;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameTestBatchFactory.class)
public abstract class GameTestMethodsMixin {
    @Inject(method = "fromTestFunction", at = @At("RETURN"), cancellable = true)
    private static void folkways$selectTests(CallbackInfoReturnable<Collection<GameTestBatch>> callback) {
        String selection = System.getProperty("folkways.tests", "");
        Collection<GameTestBatch> batches = callback.getReturnValue();
        if (!selection.isBlank()) {
            var matches = SelectedTests.selector(selection);
            var selected = new ArrayList<GameTestBatch>();
            for (GameTestBatch batch : batches) {
                var tests = batch.gameTestInfos().stream().filter(test -> matches.test(test.getTestFunction())).toList();
                if (!tests.isEmpty()) {
                    selected.add(new GameTestBatch(batch.name(), tests, batch.beforeBatchFunction(),
                        batch.afterBatchFunction()));
                }
            }
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("No GameTests matched " + selection);
            }
            batches = selected;
        }
        String shard = System.getProperty("folkways.testShard", "");
        if (!shard.isEmpty()) {
            batches = folkways$shard(batches, shard);
        }
        if (batches != callback.getReturnValue()) {
            callback.setReturnValue(batches);
        }
    }

    @Unique
    private static Collection<GameTestBatch> folkways$shard(Collection<GameTestBatch> input, String shard) {
        String[] parts = shard.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("gameTestShard must be zero-based i/n");
        }
        int index = Integer.parseInt(parts[0]);
        int count = Integer.parseInt(parts[1]);
        if (count < 1 || index < 0 || index >= count) {
            throw new IllegalArgumentException("gameTestShard requires 0 <= i < n");
        }
        var batches = new ArrayList<>(input);
        batches.sort(Comparator.comparingLong(GameTestMethodsMixin::folkways$weight).reversed()
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
            loads[target] += folkways$weight(batch);
            if (target == index) {
                assigned.add(batch);
            }
        }
        var selected = input.stream().filter(assigned::contains).toList();
        LoggerFactory.getLogger(GameTestMethodsMixin.class).info(
            "GAMETEST_SHARD shard={} batches={} tests={} weight={}", shard, selected.size(),
            selected.stream().mapToInt(batch -> batch.gameTestInfos().size()).sum(), loads[index]);
        selected.forEach(batch -> batch.gameTestInfos().forEach(test ->
            LoggerFactory.getLogger(GameTestMethodsMixin.class).info("GAMETEST_SELECTED name={}",
                test.getTestName())));
        return selected;
    }

    @Unique
    private static long folkways$weight(GameTestBatch batch) {
        return batch.gameTestInfos().stream().mapToLong(test -> test.getTimeoutTicks()).sum();
    }
}
