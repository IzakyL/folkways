package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.vanilla.person.living.LivingContent;
import io.github.izakyl.folkways.vanilla.person.look.LookPool;
import io.github.izakyl.folkways.vanilla.person.look.ResidentLook;
import io.github.izakyl.folkways.vanilla.person.look.ResidentLooks;
import io.github.izakyl.folkways.vanilla.person.name.ColonyNames;
import io.github.izakyl.folkways.vanilla.person.name.ResidentNames;
import io.github.izakyl.folkways.vanilla.person.walk.OpenDoorwayGoal;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentMoveControl;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentPathNavigation;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResidentEntity extends PathfinderMob implements Body {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final String TAG_LOOK = "Look";
    private static final String TAG_GIVEN = "GivenName";
    private static final String TAG_SURNAME = "Surname";

    private static final EntityDataAccessor<String> DATA_LOOK =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_GIVEN =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SURNAME =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_CAST_LINE =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    private static final EntityDataAccessor<String> DATA_LEISURE =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> DATA_LEISURE_PHASE =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Float> DATA_FOOD_LEVEL =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.FLOAT);

    // A presentation copy of LivingPresence's authoritative Hunger, on the 0..20 scale TLM models read.
    // Entity data also reaches observers who never open the resident's book UI. A new/unbound resident is fed.
    public float foodLevel() { return entityData.get(DATA_FOOD_LEVEL); }
    public void foodLevel(float level) { entityData.set(DATA_FOOD_LEVEL, Math.clamp(level, 0.0F, 20.0F)); }

    public void leisureMotion(String motion) { leisureMotion(motion, 0); }
    public void leisureMotion(String motion, int phase) {
        entityData.set(DATA_LEISURE, motion);
        entityData.set(DATA_LEISURE_PHASE, phase);
    }
    public int leisurePhase() { return entityData.get(DATA_LEISURE_PHASE); }
    public String leisureMotion() { return entityData.get(DATA_LEISURE); }

    @Override
    public Effects effects() {
        return Body.super.effects().with(LivingContent.effects(this));
    }

    private boolean handIsPresentation;
    private WeaponAttack weaponAttack;

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
        builder.define(DATA_LEISURE, "");
        builder.define(DATA_LEISURE_PHASE, 0);
        builder.define(DATA_FOOD_LEVEL, 20.0F);
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
        navigation.setMaxVisitedNodesMultiplier(FolkwaysVanillaConfig.pathSearchBudget());
        return navigation;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && level() instanceof ServerLevel serverLevel) {
            if (!hasActiveAttack() && weaponDrawn()) {
                holsterWeapon();
            }
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

    // Only social pushing uses the smaller box; blocks and the shared route graph still see the full body.
    @Override
    public void push(Entity other) {
        if (other instanceof ResidentEntity
                && (Math.abs(getX() - other.getX()) >= 0.36D || Math.abs(getZ() - other.getZ()) >= 0.36D)) {
            return;
        }
        super.push(other);
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
        if (hasActiveAttack()) {
            return;
        }
        // An idle real hand item goes back to the pack before a presentation copy can cover it.
        holsterWeapon();
        handIsPresentation = !copy.isEmpty();
        super.setItemSlot(EquipmentSlot.MAINHAND, copy);
    }

    @Override
    public void clearHandPresentation() {
        if (hasActiveAttack()) {
            presentCastLine(Optional.empty());
            return;
        }
        holsterWeapon();
        handIsPresentation = false;
        super.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        presentCastLine(Optional.empty());
    }

    @Override
    public void presentCastLine(Optional<BlockPos> cell) {
        entityData.set(DATA_CAST_LINE, cell);
    }

    public Optional<BlockPos> castLine() {
        return entityData.get(DATA_CAST_LINE);
    }

    @Override
    public void onRemovedFromLevel() {
        endCombat();
        super.onRemovedFromLevel();
        if (level() instanceof ServerLevel serverLevel) {
            colonyId().flatMap(id -> Colonies.of(serverLevel.getServer(), id))
                .ifPresent(colony -> colony.removed(getUUID(), getRemovalReason()));
        }
    }

    @Override
    public Mob mob() {
        return this;
    }

    /** The native equipment slot is the only physical record of a drawn weapon. */
    public boolean weaponDrawn() {
        return !handIsPresentation && !getMainHandItem().isEmpty();
    }

    /** A transient behavior pointer, never an inventory ownership token. */
    public boolean isActiveAttack(WeaponAttack attack) {
        return weaponAttack == attack;
    }

    public boolean hasActiveAttack() {
        return weaponAttack != null;
    }

    public void beginAttack(WeaponAttack attack) {
        if (weaponAttack != null || !(level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("resident already attacking or outside the server thread");
        }
        weaponAttack = java.util.Objects.requireNonNull(attack);
    }

    public void endAttack(WeaponAttack attack) {
        if (weaponAttack == attack) {
            weaponAttack = null;
        }
    }

    /** Move the whole pack stack into MAINHAND, without copying or splitting it. */
    public boolean drawWeapon(int packSlot) {
        if (!(level() instanceof ServerLevel level) || !level.getServer().isSameThread()
            || weaponDrawn() || hasActiveAttack() || packSlot < 0 || packSlot >= pack().getContainerSize()
            || pack().getItem(packSlot).isEmpty()) {
            return false;
        }
        clearHandPresentation();
        handIsPresentation = false;
        super.setItemSlot(EquipmentSlot.MAINHAND, pack().removeItemNoUpdate(packSlot));
        return true;
    }

    /** Return the current physical hand to the pack, spilling only the remainder. */
    public void holsterWeapon() {
        if (!(level() instanceof ServerLevel)) {
            return;
        }
        stopAttack();
        if (!weaponDrawn()) {
            return;
        }
        stopUsingItem();
        ItemStack actual = getMainHandItem();
        super.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemStack remainder = pack().insert(actual);
        if (!remainder.isEmpty()) {
            spawnAtLocation(remainder);
        }
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND && hasActiveAttack() && stack != getMainHandItem()) {
            // Preserve the displaced physical item before commands or another mod install a new one.
            // No reference to that item is retained by an attack across ticks.
            stopAttack();
            holsterWeapon();
            handIsPresentation = false;
        }
        super.setItemSlot(slot, stack);
    }

    private void stopAttack() {
        WeaponAttack active = weaponAttack;
        if (active != null) {
            active.stop();
            endAttack(active);
        }
    }

    /** Lifecycle and control changes stop behavior and explicitly put the physical weapon away. */
    public void stopCombat() {
        if (!(level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("resident weapon control belongs to the server thread");
        }
        endCombat();
    }

    private void endCombat() {
        stopAttack();
        holsterWeapon();
    }

    @Override
    public ItemStack getProjectile(ItemStack weapon) {
        if (weaponDrawn() && weapon.getItem() instanceof ProjectileWeaponItem projectile) {
            for (int slot = 0; slot < pack().getContainerSize(); slot++) {
                ItemStack candidate = pack().getItem(slot);
                if (!candidate.isEmpty() && projectile.getAllSupportedProjectiles(weapon).test(candidate)) {
                    return candidate;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void leaveColony() {
        endCombat();
        Body.super.leaveColony();
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
            .orElse(LookPool.UNTOUCHED::allows);
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
        endCombat();
        io.github.izakyl.folkways.core.colony.api.Equipment.release(this);
        io.github.izakyl.folkways.core.equipment.api.GearSlots.dropAccessories(this);
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
    public void load(CompoundTag tag) {
        endCombat();
        handIsPresentation = false;
        super.load(tag);
        // A saved real hand has no live attack after loading. Holster it before work resumes.
        holsterWeapon();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        // Save a drawn weapon in the native hand, outside the pack, without interrupting combat on autosave.
        // Only presentation copies are omitted; loading returns the real hand item to the pack.
        ItemStack shown = ItemStack.EMPTY;
        if (handIsPresentation) {
            shown = getMainHandItem();
            super.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        super.addAdditionalSaveData(tag);
        if (handIsPresentation) {
            super.setItemSlot(EquipmentSlot.MAINHAND, shown);
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
        refreshEffects();
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
            // The obituary reaches only who carries the colony's book; the log keeps how and where for the rest.
            LOGGER.info("resident died id={} cause={} at={}, {}, {} in={}", getUUID(), cause.getMsgId(),
                blockPosition().getX(), blockPosition().getY(), blockPosition().getZ(),
                serverLevel.getBlockState(blockPosition()).getBlock().builtInRegistryHolder().key().location());
            ResidentObituary.announce(serverLevel, this);
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        endCombat();
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        io.github.izakyl.folkways.core.equipment.api.GearSlots.dropAccessories(this);
        pack().dropContents(this);
        ItemStack stray = getMainHandItem();
        if (!handIsPresentation && !stray.isEmpty()) {
            super.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            spawnAtLocation(stray);
        }
    }
}
