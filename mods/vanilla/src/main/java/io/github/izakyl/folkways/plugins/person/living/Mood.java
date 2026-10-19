package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

// Long-lived experiences, independent of which action currently owns the resident.
final class Mood {
    static final int DAY = 24000;
    double value = 60;
    double leisure = 60;
    long lastTick = -1;
    long attacked = -DAY;
    long chatted = -DAY;
    long rested = -DAY;
    long housed = -2L * DAY;
    long fineMeal = -DAY;
    long nextBreak;
    long activityDay = -1;
    int housing;
    boolean comfortable;
    final Map<String, Long> meals = new HashMap<>();
    final Map<String, Integer> repetitions = new HashMap<>();
    Map<String, Integer> reasons = Map.of();

    static double speed(double mood) {
        if (mood <= 40) { return 0.75 + mood * 0.005; }
        if (mood <= 60) { return 0.95 + (mood - 40) * 0.0025; }
        if (mood <= 80) { return 1.0 + (mood - 60) * 0.005; }
        return 1.10 + (mood - 80) * 0.0025;
    }

    static int leisureMood(double leisure) {
        return (int) Math.round(leisure < 40 ? -6 + leisure * 0.15
            : leisure < 70 ? 0 : (leisure - 70) / 6);
    }

    void step(long now, int food, int alertness, int home, boolean safe, boolean uncomfortable) {
        step(now, food, alertness, home, safe, uncomfortable, 1.0, 0);
    }

    void step(long now, int food, int alertness, int home, boolean safe, boolean uncomfortable,
              double discomfort, int reassurance) {
        if (lastTick < 0 && chatted < 0) { chatted = now - DAY; }
        long elapsed = lastTick < 0 ? 0 : Math.clamp(now - lastTick, 0, 200);
        lastTick = now;
        leisure = Math.max(0, leisure - elapsed * 50.0 / DAY);
        meals.values().removeIf(at -> now - at > DAY * 2L);
        Map<String, Integer> parts = new LinkedHashMap<>();
        parts.put("food", food <= 4 ? -15 : now - fineMeal < DAY ? 8 : meals.size() >= 3 ? 4 : 0);
        parts.put("housing", now - housed < DAY * 2L ? housing : home);
        parts.put("rest", alertness <= 0 ? -10 : alertness < 400 ? -5 : now - rested < DAY ? 4 : 0);
        parts.put("leisure", leisureMood(leisure));
        parts.put("social", now - chatted < DAY ? 4 : now - chatted > DAY * 2L ? -4 : 0);
        double shadow = Math.max(0, 1.0 - (now - attacked) * (1.0 + reassurance / 10.0) / DAY);
        parts.put("safety", (safe ? 3 + reassurance : 0) - (int) Math.round(12 * shadow));
        parts.put("environment", uncomfortable ? -5 : comfortable ? 4 : 0);
        for (String part : java.util.List.of("food", "housing", "environment")) {
            parts.computeIfPresent(part, (key, value) -> value < 0 ? (int) Math.round(value * discomfort) : value);
        }
        reasons = Map.copyOf(parts);
        double target = Math.clamp(60 + parts.values().stream().mapToInt(Integer::intValue).sum(), 0, 100);
        double change = elapsed * 4.0 / 1000;
        value += Math.clamp(target - value, -change, change);
    }

    void enjoyed(String activity, double ticks, double duration, double recovery, long now) {
        if (activityDay != now / DAY) { activityDay = now / DAY; repetitions.clear(); }
        int repeated = repetitions.getOrDefault(activity, 0);
        double factor = repeated == 0 ? 1 : repeated == 1 ? 0.6 : 0.3;
        leisure = Math.min(100, leisure + recovery * factor * ticks / duration);
        if (activity.equals("chatting")) { chatted = now; }
    }

    CompoundTag save() {
        return Writer.of().decimal("mood", (float) value).decimal("leisure", (float) leisure)
            .blob("times", times()).integer("housing", housing)
            .children("meals", meals.entrySet(), entry -> {
                CompoundTag tag = new CompoundTag(); tag.putString("kind", entry.getKey()); tag.putLong("at", entry.getValue()); return tag;
            }).children("activities", repetitions.entrySet(), entry -> Writer.of().string("kind", entry.getKey()).integer("count", entry.getValue()).tag())
            .tag();
    }

    private CompoundTag times() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("attacked", attacked); tag.putLong("chatted", chatted); tag.putLong("rested", rested);
        tag.putLong("housed", housed); tag.putLong("fineMeal", fineMeal); tag.putLong("nextBreak", nextBreak);
        tag.putLong("activityDay", activityDay); return tag;
    }

    void load(Reader reader) {
        value = Math.clamp(reader.decimal("mood").orElse(60F), 0, 100);
        leisure = Math.clamp(reader.decimal("leisure").orElse(60F), 0, 100);
        housing = reader.integer("housing").orElse(0);
        reader.child("times").ifPresent(times -> {
            Reader tag = times;
            attacked = tag.longValue("attacked").orElse(-24000L); chatted = tag.longValue("chatted").orElse(-24000L); rested = tag.longValue("rested").orElse(-24000L);
            housed = tag.longValue("housed").orElse(-48000L); fineMeal = tag.longValue("fineMeal").orElse(-24000L); nextBreak = tag.longValue("nextBreak").orElse(-24000L);
            activityDay = tag.longValue("activityDay").orElse(-24000L);
        });
        for (Reader meal : reader.children("meals")) {
            meal.string("kind").ifPresent(kind -> meals.put(kind, meal.longValue("at").orElse(0L)));
        }
        for (Reader activity : reader.children("activities")) {
            activity.string("kind").ifPresent(kind -> repetitions.put(kind, Math.clamp(activity.integer("count").orElse(0), 0, 100)));
        }
    }
}
