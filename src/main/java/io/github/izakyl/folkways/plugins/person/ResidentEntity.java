package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.perk.GlobalPerks;
import io.github.izakyl.folkways.core.api.perk.Perks;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.plugins.person.look.LookPool;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import io.github.izakyl.folkways.plugins.person.name.ColonyNames;
import io.github.izakyl.folkways.plugins.person.name.ResidentNames;
import io.github.izakyl.folkways.plugins.person.walk.OpenDoorwayGoal;
import io.github.izakyl.folkways.plugins.person.walk.ResidentMoveControl;
import io.github.izakyl.folkways.plugins.person.walk.ResidentPathNavigation;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ResidentEntity extends PathfinderMob implements Body {

    private static final String TAG_LOOK = "Look";
    private static final String TAG_GIVEN = "GivenName";
    private static final String TAG_SURNAME = "Surname";
    private static final ResourceLocation PERK_SPEED_MODIFIER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "perk_speed");
    private static final ResourceLocation PERK_REACH_MODIFIER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "perk_reach");

    private static final EntityDataAccessor<String> DATA_LOOK =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_GIVEN =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SURNAME =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_CAST_LINE =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    private boolean handIsPresentation;

    public ResidentEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        moveControl = new ResidentMoveControl(this);
        setPersistenceRequired();
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        setDropChance(EquipmentSlot.OFFHAND, 0.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LOOK, "");
        builder.define(DATA_GIVEN, "");
        builder.define(DATA_SURNAME, "");
        builder.define(DATA_CAST_LINE, Optional.empty());
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.28)
            .add(Attributes.FOLLOW_RANGE, 64.0)
            .add(Attributes.ATTACK_DAMAGE, 1.0)
            .add(Attributes.BLOCK_INTERACTION_RANGE, Reach.PLAYER_BLOCK_REACH);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorwayGoal(this));
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation navigation = new ResidentPathNavigation(this, level);
        navigation.setMaxVisitedNodesMultiplier(FolkwaysConfig.pathSearchBudget());
        return navigation;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && level() instanceof ServerLevel serverLevel) {
            sweepStrayHandStack();
            if (tickCount % 20 == 0) {
                pinLook(serverLevel);
                pinName(serverLevel);
                leaveWithARazedColony(serverLevel);
            }
        }
    }

    // Vanilla only runs a swing's clock for monsters and players; without it the first swing never ends, so the
    // server swallows every later one and renderers see a swing stuck at its first tick.
    @Override
    public void aiStep() {
        updateSwingTime();
        super.aiStep();
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel serverLevel) {
            colonyId().flatMap(id -> Colonies.of(serverLevel.getServer(), id))
                .ifPresent(colony -> colony.entered(this));
        }
    }

    @Override
    public void presentHeldItem(ItemStack copy) {
        sweepStrayHandStack();
        handIsPresentation = !copy.isEmpty();
        setItemInHand(InteractionHand.MAIN_HAND, copy);
    }

    @Override
    public void clearHandPresentation() {
        sweepStrayHandStack();
        handIsPresentation = false;
        setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        presentCastLine(Optional.empty());
    }

    @Override
    public void presentCastLine(Optional<BlockPos> cell) {
        entityData.set(DATA_CAST_LINE, cell);
    }

    public Optional<BlockPos> castLine() {
        return entityData.get(DATA_CAST_LINE);
    }

    private void sweepStrayHandStack() {
        if (handIsPresentation || !(level() instanceof ServerLevel)) {
            return;
        }
        ItemStack stray = getMainHandItem();
        if (stray.isEmpty()) {
            return;
        }
        setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        ItemStack remainder = pack().insert(stray);
        if (!remainder.isEmpty()) {
            spawnAtLocation(remainder);
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (level() instanceof ServerLevel serverLevel) {
            colonyId().flatMap(id -> Colonies.of(serverLevel.getServer(), id))
                .ifPresent(colony -> Release.fromWorld(getRemovalReason())
                    .ifPresentOrElse(why -> colony.holdingOf(getUUID())
                        .ifPresent(held -> colony.release(held.id(), why)), () -> colony.exited(getUUID())));
        }
    }

    @Override
    public Mob mob() {
        return this;
    }

    @Override
    public ResidentKind kind() {
        return PersonBody.person();
    }

    public Optional<ResourceKey<ResidentLook>> lookId() {
        String written = entityData.get(DATA_LOOK);
        if (written.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ResourceLocation.tryParse(written))
            .map(id -> ResourceKey.create(ResidentLooks.REGISTRY, id));
    }

    public void setLookId(ResourceKey<ResidentLook> look) {
        entityData.set(DATA_LOOK, look.location().toString());
    }

    // The colony's look pool chooses once, when the resident has none; after that the look is the resident's own.
    private void pinLook(ServerLevel level) {
        if (lookId().isPresent()) {
            return;
        }
        Predicate<ResourceLocation> allowed = colonyId()
            .flatMap(id -> Colonies.of(level.getServer(), id))
            .map(LookPool::of)
            .<Predicate<ResourceLocation>>map(pool -> pool::allows)
            .orElse(look -> true);
        ResidentLooks.draw(level.registryAccess(), getUUID(), allowed)
            .ifPresent(key -> entityData.set(DATA_LOOK, key.location().toString()));
    }

    // Likewise the name: drawn from the colony's name pool once, then kept, whatever the pool becomes.
    private void pinName(ServerLevel level) {
        if (!entityData.get(DATA_GIVEN).isEmpty()) {
            return;
        }
        Optional<Colony> colony = colonyId().flatMap(id -> Colonies.of(level.getServer(), id));
        if (colony.isEmpty()) {
            return;
        }
        ResidentNames.draw(ColonyNames.of(colony.get(), level.registryAccess()), getUUID())
            .ifPresent(drawn -> setNames(drawn.given(), drawn.surname()));
    }

    public String givenName() {
        return entityData.get(DATA_GIVEN);
    }

    public String surname() {
        return entityData.get(DATA_SURNAME);
    }

    public void setNames(String given, String surname) {
        entityData.set(DATA_GIVEN, given);
        entityData.set(DATA_SURNAME, surname);
    }

    private void leaveWithARazedColony(ServerLevel level) {
        if (colonyId().filter(id -> Colonies.razed(level.getServer(), id)).isEmpty()) {
            return;
        }
        pack().dropContents(this);
        leaveColony();
        discard();
    }

    @Override
    protected Component getTypeName() {
        String given = givenName();
        if (!given.isEmpty()) {
            return new ResidentNames.Drawn(given, surname()).asName();
        }
        return ResidentNames.of(level().registryAccess(), getUUID());
    }

    @Override
    public void refreshPerkEffects() {
        Perks perks = perks();
        pack().applyCapacityMultiplier(packRoom(perks.rank(GlobalPerks.PACKMULE)));
        AttributeInstance arms = getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (arms != null) {
            arms.removeModifier(PERK_REACH_MODIFIER);
            double past = reachBonus(perks.rank(GlobalPerks.LONGARM));
            if (past != 0.0) {
                arms.addTransientModifier(new AttributeModifier(
                    PERK_REACH_MODIFIER, past, AttributeModifier.Operation.ADD_VALUE));
            }
        }
        AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(PERK_SPEED_MODIFIER);
            double mul = walkingSpeed(perks.rank(GlobalPerks.BRISK));
            if (mul != 1.0) {
                speed.addTransientModifier(new AttributeModifier(
                    PERK_SPEED_MODIFIER, mul - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
    }

    private static double packRoom(int rank) {
        return switch (rank) {
            case 1 -> 1.5;
            case 2 -> 2.0;
            case 3 -> 2.5;
            case 4 -> 3.0;
            default -> 1.0;
        };
    }

    private static double walkingSpeed(int rank) {
        return switch (rank) {
            case 1 -> 1.15;
            case 2 -> 1.30;
            case 3 -> 1.45;
            case 4 -> 1.60;
            default -> 1.0;
        };
    }

    public static double reachBonus(int longarmRank) {
        return switch (longarmRank) {
            case 1 -> 0.5;
            case 2 -> 1.0;
            case 3 -> 1.5;
            case 4 -> 2.0;
            default -> 0.0;
        };
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        ItemStack shown = ItemStack.EMPTY;
        if (handIsPresentation) {
            shown = getMainHandItem();
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        super.addAdditionalSaveData(tag);
        if (handIsPresentation) {
            setItemInHand(InteractionHand.MAIN_HAND, shown);
        }
        String look = entityData.get(DATA_LOOK);
        if (!look.isEmpty()) {
            tag.putString(TAG_LOOK, look);
        }
        if (!givenName().isEmpty()) {
            tag.putString(TAG_GIVEN, givenName());
            tag.putString(TAG_SURNAME, surname());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(DATA_LOOK, tag.getString(TAG_LOOK));
        setNames(tag.getString(TAG_GIVEN), tag.getString(TAG_SURNAME));
        refreshPerkEffects();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (level() instanceof ServerLevel serverLevel) {
            ResidentObituary.announce(serverLevel, this);
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        pack().dropContents(this);
        ItemStack stray = getMainHandItem();
        if (!handIsPresentation && !stray.isEmpty()) {
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            spawnAtLocation(stray);
        }
    }
}
