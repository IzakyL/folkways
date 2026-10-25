package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.ResidentShields;
import io.github.izakyl.folkways.plugins.combat.motion.CombatMotions;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
import net.neoforged.neoforge.common.ItemAbilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResidentEntity extends PathfinderMob implements Body {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final String TAG_LOOK = "Look";
    private static final String TAG_GIVEN = "GivenName";
    private static final String TAG_SURNAME = "Surname";
    private static final String TAG_SHIELD_COOLDOWN = "ShieldCooldown";

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
    private static final EntityDataAccessor<Integer> DATA_SHIELD_COOLDOWN =
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
    private WeaponSession weaponSession;

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
        builder.define(DATA_SHIELD_COOLDOWN, 0);
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
            CombatMotions.tick(this);
            if (shieldCooldownTicks() > 0) entityData.set(DATA_SHIELD_COOLDOWN, shieldCooldownTicks() - 1);
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
        if (weaponSession != null) {
            return;
        }
        sweepStrayHandStack();
        handIsPresentation = !copy.isEmpty();
        setItemInHand(InteractionHand.MAIN_HAND, copy);
    }

    @Override
    public void clearHandPresentation() {
        if (weaponSession != null) {
            presentCastLine(Optional.empty());
            return;
        }
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
        if (weaponSession != null || handIsPresentation || !(level() instanceof ServerLevel)) {
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
        closeWeaponSession();
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

    /** Vanilla weapons temporarily own the actual main hand; Core tool presentation never owns it. */
    public boolean hasWeaponSession() {
        return weaponSession != null;
    }

    public boolean ownsWeaponHand(WeaponSession session) {
        return weaponSession == session;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.OFFHAND && stack != getOffhandItem()) ResidentShields.lower(this);
        if (slot == EquipmentSlot.MAINHAND && weaponSession != null && stack != getMainHandItem()) {
            // Commands and other mods may replace the hand. Return the previous actual item before
            // installing the replacement; the stopped session must never adopt or fire the new item.
            closeWeaponSession();
        }
        super.setItemSlot(slot, stack);
    }

    public void claimWeaponHand(WeaponSession session, ItemStack actual) {
        if (weaponSession != null || !(level() instanceof ServerLevel)) {
            throw new IllegalStateException("resident main hand is already owned or is not on the server");
        }
        clearHandPresentation();
        weaponSession = session;
        handIsPresentation = false;
        super.setItemSlot(EquipmentSlot.MAINHAND, actual);
    }

    public void releaseWeaponHand(WeaponSession session) {
        if (weaponSession != session) {
            return;
        }
        stopUsingItem();
        ItemStack actual = getMainHandItem();
        super.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        weaponSession = null;
        ItemStack remainder = pack().insert(actual);
        if (!remainder.isEmpty()) {
            spawnAtLocation(remainder);
        }
    }

    private void closeWeaponSession() {
        CombatMotions.stop(this);
        ResidentShields.lower(this);
        if (weaponSession != null) {
            weaponSession.close();
        }
    }

    /** Server-side control changes stop the one actual hand owner through its normal return lifecycle. */
    public void stopWeaponSession() {
        if (!(level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("resident weapon control belongs to the server thread");
        }
        closeWeaponSession();
    }

    @Override
    public ItemStack getProjectile(ItemStack weapon) {
        if (weaponSession != null && weapon.getItem() instanceof ProjectileWeaponItem projectile) {
            for (int slot = 0; slot < pack().getContainerSize(); slot++) {
                ItemStack candidate = pack().getItem(slot);
                if (!candidate.isEmpty() && (!PersonalItems.marked(candidate) || PersonalItems.belongsTo(this, candidate))
                        && !io.github.izakyl.folkways.core.api.resident.body.TaskTools.holds(id(), candidate)
                        && projectile.getAllSupportedProjectiles(weapon).test(candidate)) {
                    return candidate;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void leaveColony() {
        closeWeaponSession();
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
        closeWeaponSession();
        io.github.izakyl.folkways.core.api.resident.body.Equipment.release(this);
        for (EquipmentSlot slot : ResidentEquipment.SLOTS) {
            if (slot == EquipmentSlot.MAINHAND) continue;
            ItemStack actual = getItemBySlot(slot);
            if (actual.isEmpty()) continue;
            setItemSlot(slot, ItemStack.EMPTY);
            ResidentEquipment.released(actual);
            spawnAtLocation(actual);
        }
        for (int cell = 0; cell < pack().getContainerSize(); cell++) ResidentEquipment.released(pack().getItem(cell));
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
    public CompoundTag saveWithoutId(CompoundTag tag) {
        // Entity serializes attachments (including Pack) BEFORE addAdditionalSaveData.
        // Return the loan before that point so neither the hand nor the snapshot duplicates or loses it.
        closeWeaponSession();
        return super.saveWithoutId(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        closeWeaponSession();
        handIsPresentation = false;
        super.load(tag);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        ItemStack shown = ItemStack.EMPTY;
        if (handIsPresentation) {
            shown = getMainHandItem();
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_SHIELD_COOLDOWN, shieldCooldownTicks());
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
        CombatMotions.stop(this);
        super.readAdditionalSaveData(tag);
        entityData.set(DATA_SHIELD_COOLDOWN, Mth.clamp(tag.getInt(TAG_SHIELD_COOLDOWN), 0, 100));
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
        closeWeaponSession();
        super.die(cause);
        if (level() instanceof ServerLevel serverLevel) {
            // The obituary reaches only who carries the colony's book; the log keeps how and where for the rest.
            LOGGER.info("resident died id={} cause={} at={}, {}, {} in={}", getUUID(), cause.getMsgId(),
                blockPosition().getX(), blockPosition().getY(), blockPosition().getZ(),
                serverLevel.getBlockState(blockPosition()).getBlock().builtInRegistryHolder().key().location());
            ResidentObituary.announce(serverLevel, this);
        }
    }

    /** Remaining vanilla-length axe disable window, synchronized for the resident status UI. */
    public int shieldCooldownTicks() {
        return entityData.get(DATA_SHIELD_COOLDOWN);
    }

    @Override
    public boolean isBlocking() {
        return shieldCooldownTicks() == 0 && super.isBlocking();
    }

    // LivingEntity's implementation is empty for mobs. Match Player's real shield wear threshold and
    // durability calculation; directional blocking, projectile piercing and bypass tags remain vanilla.
    @Override
    protected void hurtCurrentlyUsedShield(float amount) {
        ItemStack actual = getUseItem();
        if (amount < 3.0F || !actual.canPerformAction(ItemAbilities.SHIELD_BLOCK)) return;
        InteractionHand hand = getUsedItemHand();
        EquipmentSlot slot = getSlotForHand(hand);
        actual.hurtAndBreak(1 + Mth.floor(amount), this, slot);
        if (actual.isEmpty()) {
            stopUsingItem();
            setItemSlot(slot, ItemStack.EMPTY);
            playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + random.nextFloat() * 0.4F);
        }
    }

    @Override
    protected void blockUsingShield(LivingEntity attacker) {
        super.blockUsingShield(attacker);
        // This is the same native attacker/item ability used by Player.blockUsingShield.
        if (attacker.canDisableShield()) {
            entityData.set(DATA_SHIELD_COOLDOWN, 100);
            ResidentShields.lower(this);
            level().broadcastEntityEvent(this, (byte) 30);
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        closeWeaponSession();
        for (EquipmentSlot slot : ResidentEquipment.SLOTS) {
            if (slot == EquipmentSlot.MAINHAND) continue;
            ItemStack actual = getItemBySlot(slot);
            if (PersonalItems.belongsTo(this, actual)) {
                ResidentEquipment.released(actual);
                setDropChance(slot, 2.0F);
            }
        }
        for (int cell = 0; cell < pack().getContainerSize(); cell++) ResidentEquipment.released(pack().getItem(cell));
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        pack().dropContents(this);
        ItemStack stray = getMainHandItem();
        if (!handIsPresentation && !stray.isEmpty()) {
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            ResidentEquipment.released(stray);
            spawnAtLocation(stray);
        }
    }
}
