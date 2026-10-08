package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
import net.minecraft.world.food.FoodProperties;

final class Hunger {
    private static final String TAG_FOOD_LEVEL = "FoodLevel";
    private static final String TAG_SATURATION = "Saturation";
    private static final String TAG_EXHAUSTION = "Exhaustion";

    public static final int MAX_FOOD_LEVEL = 20;
    public static final int REGEN_FOOD_LEVEL = 18;
    private static final float EXHAUSTION_THRESHOLD = 4.0F;
    public static final float WALK_EXHAUSTION_PER_BLOCK = 0.1F;
    // One unit is one standard labor second: 80 seconds consume one saturation/food point.
    static final float WORK_EXHAUSTION_PER_UNIT = 0.05F;
    private static final float REGEN_EXHAUSTION_PER_HP = 6.0F;
    private static final float STARTING_SATURATION = 5.0F;
    private static final int SLOW_REGEN_INTERVAL = 80;
    private static final int FAST_REGEN_INTERVAL = 10;
    private static final int STARVE_INTERVAL = 80;

    private int foodLevel = MAX_FOOD_LEVEL;
    private float saturationLevel = STARTING_SATURATION;
    private float exhaustionLevel = 0.0F;
    private int vitalsTimer;
    private float exhaustionRateMul = 1.0F;

    public void setExhaustionRateMul(float mul) {
        this.exhaustionRateMul = mul <= 0.0F ? 1.0F : mul;
    }

    public void addExhaustion(float amount, Difficulty difficulty) {
        if (amount <= 0.0F) {
            return;
        }
        exhaustionLevel += amount * exhaustionRateMul;
        while (exhaustionLevel >= EXHAUSTION_THRESHOLD) {
            exhaustionLevel -= EXHAUSTION_THRESHOLD;
            if (saturationLevel > 0.0F) {
                saturationLevel = Math.max(0.0F, saturationLevel - 1.0F);
            } else if (difficulty != Difficulty.PEACEFUL) {
                foodLevel = Math.max(0, foodLevel - 1);
            }
        }
    }

    public void eat(FoodProperties food) {
        foodLevel = Math.min(MAX_FOOD_LEVEL, foodLevel + food.nutrition());
        saturationLevel = Math.min(saturationLevel + food.saturation(), foodLevel);
    }

    public boolean canEatWithoutWaste(FoodProperties food) {
        return foodLevel <= MAX_FOOD_LEVEL - food.nutrition();
    }

    public float stepVitals(int elapsedTicks, float health, float maxHealth, Difficulty difficulty,
            boolean naturalRegenAllowed) {
        boolean belowMaxHealth = health < maxHealth;
        if (naturalRegenAllowed && saturationLevel > 0.0F && belowMaxHealth && foodLevel >= MAX_FOOD_LEVEL) {
            vitalsTimer += elapsedTicks;
            if (vitalsTimer >= FAST_REGEN_INTERVAL) {
                float heal = Math.min(saturationLevel, REGEN_EXHAUSTION_PER_HP);
                addExhaustion(heal, difficulty);
                vitalsTimer = 0;
                return heal / REGEN_EXHAUSTION_PER_HP;
            }
            return 0.0F;
        }
        if (naturalRegenAllowed && foodLevel >= REGEN_FOOD_LEVEL && belowMaxHealth) {
            vitalsTimer += elapsedTicks;
            if (vitalsTimer >= SLOW_REGEN_INTERVAL) {
                addExhaustion(REGEN_EXHAUSTION_PER_HP, difficulty);
                vitalsTimer = 0;
                return 1.0F;
            }
            return 0.0F;
        }
        if (foodLevel <= 0) {
            vitalsTimer += elapsedTicks;
            if (vitalsTimer >= STARVE_INTERVAL) {
                vitalsTimer = 0;
                return starvationBites(health, difficulty) ? -1.0F : 0.0F;
            }
            return 0.0F;
        }
        vitalsTimer = 0;
        return 0.0F;
    }

    private static boolean starvationBites(float health, Difficulty difficulty) {
        return health > 10.0F
            || difficulty == Difficulty.HARD
            || health > 1.0F && difficulty == Difficulty.NORMAL;
    }

    public int foodLevel() {
        return foodLevel;
    }

    CompoundTag save() {
        return Writer.of()
            .integer(TAG_FOOD_LEVEL, foodLevel)
            .decimal(TAG_SATURATION, saturationLevel)
            .decimal(TAG_EXHAUSTION, exhaustionLevel)
            .tag();
    }

    void load(Reader reader) {
        reader.integer(TAG_FOOD_LEVEL).ifPresent(level -> {
            foodLevel = clampFood(level);
            saturationLevel = Math.max(0.0F, reader.decimal(TAG_SATURATION).orElse(0.0F));
            exhaustionLevel = Math.max(0.0F, reader.decimal(TAG_EXHAUSTION).orElse(0.0F));
        });
        vitalsTimer = 0;
    }

    private static int clampFood(int value) {
        return Math.clamp(value, 0, MAX_FOOD_LEVEL);
    }
}
