package com.admin82.factions.entity.monument;

import com.admin82.factions.compat.tacz.Tacz;
import com.admin82.factions.Config;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Monument guard with Tarkov-style awareness.
 *
 * <p>Guards are never omniscient. They notice players through a vision cone (distance, stance, light,
 * movement and line of sight all matter) and by hearing noises (see {@link PmcHearing}). Awareness
 * escalates CALM → SUSPICIOUS → SEARCHING → COMBAT and decays back when nothing is found. A guard only
 * shoots at a target it has actually spotted and is physically facing.
 *
 * <p>Squads share callouts: a spotted enemy's last known position (not its live position) is passed to
 * squadmates, who take a role-based order: assault and light gunners flank, heavies suppress, snipers
 * hold overwatch.
 */
public class RoguePmcEntity extends Monster {
    public enum Alertness { CALM, SUSPICIOUS, SEARCHING, COMBAT }

    private enum SquadOrder { NONE, HOLD, FLANK, SUPPRESS, OVERWATCH, COVER }

    private record FormationSlot(int index, int count) {}

    private static final float DETECTION_SUSPICIOUS = 0.3F;
    private static final float DETECTION_SPOTTED = 1.0F;
    private static final int TARGET_LOST_TICKS = 60;
    private static final int SUPPRESS_AFTER_LOST_TICKS = 40;
    private static final int CALLOUT_INTERVAL_TICKS = 40;
    private static final int ALLY_GUNFIRE_INTERVAL_TICKS = 60;
    private static final double SQUAD_HOLD_RADIUS = 32.0;

    private final RoguePmcRole role;
    private UUID monumentId;
    private UUID squadId;
    private BlockPos homePosition;
    private BlockPos lastInsidePosition;
    private BlockPos patrolPosition;
    private BlockPos combatCoverPosition;
    private boolean holdingPatrolPosition;
    private int patrolWaitTicks;
    private int shotCooldown;

    // Awareness
    private Alertness alertness = Alertness.CALM;
    private final Map<UUID, Float> detection = new HashMap<>();
    @Nullable private UUID threatId;
    @Nullable private Vec3 lastKnownPosition;
    @Nullable private Vec3 investigatePosition;
    private boolean investigating;
    private int alertTicks;
    private int unseenTicks;
    private int underFireTicks;
    private int pauseTicks;
    private int searchLegs;
    private int suspicion;
    private int lastAllyGunfireTick = -ALLY_GUNFIRE_INTERVAL_TICKS;
    private float scanYaw;
    private int scanTicks;
    private double aimExposure;
    @Nullable private UUID aimedTargetId;
    private SquadOrder squadOrder = SquadOrder.NONE;

    public RoguePmcEntity(EntityType<? extends RoguePmcEntity> type, Level level, RoguePmcRole role) {
        super(type, level);
        this.role = role;
        setPersistenceRequired();
    }

    @Override
    protected void registerGoals() {
        // Only swimming is goal-driven. Looking, targeting and moving are handled by the awareness model
        // below so guards never "know" where a player is without seeing or hearing them.
        goalSelector.addGoal(1, new FloatGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, SpawnGroupData spawnData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnData);
        equipRoleKit();
        return result;
    }

    public void bindToMonument(UUID id, BlockPos home) {
        monumentId = id;
        homePosition = home.immutable();
        lastInsidePosition = blockPosition().immutable();
        restrictTo(homePosition, 128);
    }

    public void applyConfiguredLoadout(List<ItemStack> loadout) {
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int index = 0; index < Math.min(slots.length, loadout.size()); index++) {
            if (loadout.get(index).isEmpty()) continue;
            setItemSlot(slots[index], loadout.get(index).copyWithCount(1));
            setDropChance(slots[index], 0.0F);
        }
        Tacz.drawGun(this);
    }

    public void refreshConfiguredLoadout(List<ItemStack> loadout) {
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) setItemSlot(slot, ItemStack.EMPTY);
        equipRoleKit();
        applyConfiguredLoadout(loadout);
    }

    public UUID getMonumentId() { return monumentId; }

    public RoguePmcRole getRole() { return role; }

    public Alertness getAlertness() { return alertness; }

    public void assignSquad(UUID id) {
        squadId = id;
        patrolPosition = null;
        holdingPatrolPosition = false;
        patrolWaitTicks = 0;
    }

    // Main loop

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;
        MonumentEntry monument = enforceMonumentBoundary();
        if (monument == null) return;
        if (shotCooldown > 0) shotCooldown--;
        if (underFireTicks > 0) underFireTicks--;
        if (patrolWaitTicks > 0) patrolWaitTicks--;
        if (pauseTicks > 0) pauseTicks--;

        AiTuning tuning = tuning(monument);
        updateVision(tuning, monument);
        switch (alertness) {
            case COMBAT -> tickCombat(monument, tuning);
            case SEARCHING -> tickSearching(monument, tuning);
            case SUSPICIOUS -> tickSuspicious(monument, tuning);
            default -> tickCalm(monument, tuning);
        }
    }

    private AiTuning tuning(MonumentEntry monument) {
        return AiBehaviorData.get(((ServerLevel) level()).getServer())
                .tuning(role, monument.getAiDifficultyLevel(role));
    }

    @Nullable
    private MonumentEntry monument() {
        if (monumentId == null || !(level() instanceof ServerLevel serverLevel)) return null;
        return MonumentData.get(serverLevel.getServer()).get(monumentId);
    }

    // Vision

    private void updateVision(AiTuning tuning, MonumentEntry monument) {
        if ((tickCount + getId()) % 2 != 0) return;
        Vec3 eyes = getEyePosition();
        Vec3 view = getViewVector(1.0F);
        Player best = null;
        float bestValue = 0.0F;
        for (ServerPlayer player : ((ServerLevel) level()).players()) {
            UUID id = player.getUUID();
            if (!canPerceive(player) || player.distanceToSqr(this) > tuning.visionRange() * tuning.visionRange()) {
                detection.remove(id);
                continue;
            }
            float gain = visibilityGain(player, eyes, view, tuning) * 2.0F;
            float current = detection.getOrDefault(id, 0.0F);
            float next = gain > 0.0F ? Math.min(1.5F, current + gain) : Math.max(0.0F, current - 0.03F);
            if (next <= 0.0F) detection.remove(id);
            else detection.put(id, next);
            if (gain > 0.0F && next > bestValue) {
                bestValue = next;
                best = player;
            }
        }
        if (best == null) return;
        boolean engagedWithVisibleTarget = alertness == Alertness.COMBAT && unseenTicks == 0
                && getTarget() != null && getTarget().isAlive();
        if (engagedWithVisibleTarget && !best.getUUID().equals(threatId)) return;
        if (bestValue >= DETECTION_SPOTTED) {
            spot(best, monument);
        } else if (bestValue >= DETECTION_SUSPICIOUS && alertness != Alertness.COMBAT) {
            // A glimpse: turn toward it and take a closer look.
            Vec3 glimpse = best.position();
            if (alertness == Alertness.CALM) becomeSuspicious(glimpse, 80, false);
            else investigatePosition = glimpse;
        }
    }

    private boolean canPerceive(Player player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    /** Per-tick increase of the detection meter for a player, 0 if they cannot currently be seen. */
    private float visibilityGain(LivingEntity target, Vec3 eyes, Vec3 view, AiTuning tuning) {
        Vec3 toTarget = target.getEyePosition().subtract(eyes);
        double distance = toTarget.length();
        if (distance < 0.01) return 1.0F;
        boolean touching = distance < 2.5;
        double cos = view.dot(toTarget.scale(1.0 / distance));
        double halfFov = Math.toRadians(tuning.fieldOfViewDegrees() / 2.0);
        if (cos < Math.cos(halfFov) && !touching) return 0.0F;
        if (!getSensing().hasLineOfSight(target)) return 0.0F;

        double posture = target.isSwimming() || target.isVisuallyCrawling()
                ? Config.MONUMENT_PMC_PRONE_EXPOSURE.get()
                : target.isCrouching() ? Config.MONUMENT_PMC_CROUCH_EXPOSURE.get() : 1.0;
        double light = 0.35 + 0.65 * level().getMaxLocalRawBrightness(target.blockPosition()) / 15.0;
        double visibility = target.hasEffect(MobEffects.GLOWING) ? 2.0 : target.isInvisible() ? 0.15 : 1.0;
        double effectiveRange = tuning.visionRange() * posture * light * visibility;
        if (distance > effectiveRange && !touching) return 0.0F;

        double movement = target.isSprinting() ? 1.6
                : target.getDeltaMovement().horizontalDistanceSqr() > 0.003 ? 1.0 : 0.6;
        double proximity = 1.0 - Math.min(1.0, distance / effectiveRange);
        double angle = Math.acos(Math.clamp(cos, -1.0, 1.0));
        double centre = 1.0 - 0.65 * Math.min(1.0, angle / halfFov);
        double alertBonus = switch (alertness) {
            case COMBAT -> 3.0;
            case SEARCHING -> 2.0;
            case SUSPICIOUS -> 1.5;
            case CALM -> 1.0;
        };
        double gain = (0.15 + 0.85 * proximity * proximity) * centre * movement * alertBonus / tuning.spotTicks();
        if (touching) gain = Math.max(gain, 0.25);
        return (float) gain;
    }

    private void spot(LivingEntity target, MonumentEntry monument) {
        boolean alreadyEngaged = alertness == Alertness.COMBAT && target.getUUID().equals(threatId);
        threatId = target.getUUID();
        lastKnownPosition = target.position();
        unseenTicks = 0;
        if (alreadyEngaged) return;
        alertness = Alertness.COMBAT;
        setTarget(target);
        combatCoverPosition = null;
        investigating = false;
        squadCallout(target, monument);
    }

    // Hearing

    /** Called by {@link PmcHearing} for every sound that might be in earshot. */
    public void hearSound(Vec3 origin, double radius, @Nullable LivingEntity source, PmcHearing.SoundKind kind) {
        MonumentEntry monument = monument();
        if (monument == null || !isAlive()) return;
        AiTuning tuning = tuning(monument);
        double distance = position().distanceTo(origin);
        if (distance > radius * tuning.hearingMultiplier()) return;

        // Sounds are localised imperfectly; the further away, the rougher the guess.
        double error = distance * 0.15;
        Vec3 guess = origin.add((random.nextDouble() - 0.5) * 2.0 * error, 0.0,
                (random.nextDouble() - 0.5) * 2.0 * error);
        if (!monument.contains(BlockPos.containing(guess), monument.dimension)) guess = origin;

        if (kind == PmcHearing.SoundKind.BULLET_IMPACT) underFireTicks = Math.max(underFireTicks, 80);
        if (alertness == Alertness.COMBAT) {
            if (source != null && source.getUUID().equals(threatId) && unseenTicks > 0) lastKnownPosition = guess;
            return;
        }
        if (source != null && kind.alarming()) {
            threatId = source.getUUID();
            if (source instanceof LivingEntity living) detection.merge(living.getUUID(), 0.4F, Float::sum);
        }

        if (kind.alarming()) {
            lastKnownPosition = guess;
            startSearch(guess, tuning.searchTicks(), true);
            squadAlarm(guess, monument);
            return;
        }
        suspicion++;
        if (alertness == Alertness.SEARCHING) {
            investigatePosition = guess;
            alertTicks = Math.max(alertTicks, tuning.searchTicks() / 2);
        } else if (suspicion >= 3) {
            startSearch(guess, tuning.searchTicks() / 2, true);
        } else {
            becomeSuspicious(guess, 100 + random.nextInt(60), true);
            squadHold(guess);
        }
    }

    // States

    private void becomeSuspicious(Vec3 position, int ticks, boolean investigate) {
        alertness = Alertness.SUSPICIOUS;
        investigatePosition = position;
        investigating = investigate && role != RoguePmcRole.SNIPER;
        alertTicks = Math.max(alertTicks, ticks);
        pauseTicks = 25 + random.nextInt(20);
        getNavigation().stop();
    }

    private void startSearch(Vec3 position, int ticks, boolean investigate) {
        alertness = Alertness.SEARCHING;
        investigatePosition = position;
        investigating = investigate && role != RoguePmcRole.SNIPER;
        alertTicks = Math.max(alertTicks, ticks);
        searchLegs = 0;
        pauseTicks = 10 + random.nextInt(15);
        setTarget(null);
        getNavigation().stop();
    }

    private void calmDown() {
        alertness = Alertness.CALM;
        threatId = null;
        lastKnownPosition = null;
        investigatePosition = null;
        investigating = false;
        suspicion = 0;
        searchLegs = 0;
        squadOrder = SquadOrder.NONE;
        combatCoverPosition = null;
        aimExposure = 0.0;
        setTarget(null);
        setPose(Pose.STANDING);
    }

    private void tickCalm(MonumentEntry monument, AiTuning tuning) {
        aimExposure = Math.max(0.0, aimExposure - 2.0);
        updatePatrol(monument);
        scanSurroundings(4.0F + (float) tuning.turnSpeed() * 0.25F);
    }

    private void tickSuspicious(MonumentEntry monument, AiTuning tuning) {
        if (--alertTicks <= 0 || investigatePosition == null) {
            calmDown();
            return;
        }
        double distanceSqr = distanceToSqr(investigatePosition);
        if (pauseTicks > 0 || !investigating || distanceSqr <= 9.0) {
            // Stop and listen / look toward the noise.
            getNavigation().stop();
            setPose(role == RoguePmcRole.SNIPER && holdingPatrolPosition ? Pose.SWIMMING : Pose.STANDING);
            if (distanceSqr <= 9.0) scanSurroundings((float) tuning.turnSpeed() * 0.5F);
            else lookAtPoint(investigatePosition, (float) tuning.turnSpeed() * 0.6F);
            return;
        }
        // Creep toward it.
        setPose(Pose.CROUCHING);
        lookAtPoint(investigatePosition, (float) tuning.turnSpeed() * 0.6F);
        if (tickCount % 10 == 0) moveTowards(investigatePosition, 0.7, monument);
    }

    private void tickSearching(MonumentEntry monument, AiTuning tuning) {
        if (--alertTicks <= 0) {
            calmDown();
            return;
        }
        float turn = (float) tuning.turnSpeed() * (underFireTicks > 0 ? 1.5F : 1.0F);
        if (investigatePosition == null) investigatePosition = lastKnownPosition;
        if (investigatePosition == null) {
            scanSurroundings(turn * 0.6F);
            return;
        }
        if (underFireTicks > 0 && random.nextDouble() > tuning.aggression() && tickCount % 10 == 0) {
            // Taking fire from an unseen shooter: get behind something first, keep watching that way.
            seekCover(investigatePosition, monument);
            lookAtPoint(investigatePosition, turn);
            return;
        }
        if (!investigating || squadOrder == SquadOrder.OVERWATCH || squadOrder == SquadOrder.SUPPRESS
                || squadOrder == SquadOrder.HOLD) {
            // Hold position and watch the area the threat was last in.
            getNavigation().stop();
            setPose(role == RoguePmcRole.SNIPER ? Pose.SWIMMING : Pose.CROUCHING);
            lookAtPoint(investigatePosition, turn);
            return;
        }
        double distanceSqr = distanceToSqr(investigatePosition);
        if (distanceSqr > 9.0 && pauseTicks <= 0) {
            setPose(tuning.aggression() >= 0.5 ? Pose.STANDING : Pose.CROUCHING);
            lookAtPoint(investigatePosition, turn);
            if (tickCount % 10 == 0 && !moveTowards(investigatePosition, 0.8 + 0.35 * tuning.aggression(), monument)) {
                pickNextSearchPoint(monument);
            }
            return;
        }
        // Arrived (or pausing): sweep the area, then check another nearby spot.
        getNavigation().stop();
        scanSurroundings(turn * 0.7F);
        if (pauseTicks <= 0) {
            pauseTicks = 30 + random.nextInt(30);
            pickNextSearchPoint(monument);
        }
    }

    private void pickNextSearchPoint(MonumentEntry monument) {
        if (searchLegs++ >= 3) {
            investigating = false;
            return;
        }
        Vec3 centre = lastKnownPosition != null ? lastKnownPosition : position();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 4.0 + random.nextDouble() * 8.0;
            Vec3 candidate = centre.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            if (monument.contains(BlockPos.containing(candidate), monument.dimension)) {
                investigatePosition = candidate;
                return;
            }
        }
    }

    // Combat

    private void tickCombat(MonumentEntry monument, AiTuning tuning) {
        LivingEntity target = getTarget();
        if (!isValidTarget(target, tuning)) {
            Vec3 lost = lastKnownPosition;
            setTarget(null);
            if (lost != null) startSearch(lost, tuning.searchTicks(), true);
            else calmDown();
            return;
        }
        double distance = distanceTo(target);
        boolean visible = getSensing().hasLineOfSight(target)
                && distance <= tuning.visionRange() * 1.5;
        if (visible) {
            lastKnownPosition = target.position();
            unseenTicks = 0;
        } else if (++unseenTicks > TARGET_LOST_TICKS) {
            startSearch(lastKnownPosition != null ? lastKnownPosition : target.position(), tuning.searchTicks(), true);
            return;
        }
        if (visible && tickCount % CALLOUT_INTERVAL_TICKS == 0) squadUpdate(target);

        boolean atCover = combatCoverPosition != null && distanceToSqr(Vec3.atCenterOf(combatCoverPosition)) <= 4.0;
        setPose(atCover
                ? role == RoguePmcRole.HEAVY_GUNNER ? Pose.SWIMMING : Pose.CROUCHING
                : role == RoguePmcRole.SNIPER && holdingPatrolPosition ? Pose.SWIMMING : Pose.STANDING);

        Vec3 aimPoint = visible
                ? target.getEyePosition().subtract(0.0, target.getBbHeight() * 0.15, 0.0)
                : lastKnownPosition.add(0.0, target.getEyeHeight() * 0.85, 0.0);
        lookAtPoint(aimPoint, (float) tuning.turnSpeed());
        double facingError = facingErrorDegrees(aimPoint);

        if (!target.getUUID().equals(aimedTargetId)) {
            aimedTargetId = target.getUUID();
            aimExposure = 0.0;
        }
        double requiredAimTicks = requiredAimTicks(distance, tuning);
        boolean steady = visible && facingError < 12.0;
        aimExposure = steady
                ? Math.min(requiredAimTicks, aimExposure + exposureOf(target))
                : Math.max(0.0, aimExposure - 1.5);

        if (tickCount % 10 == 0) updateCombatMovement(target, distance, visible, tuning, monument);

        double readiness = Math.clamp(aimExposure / requiredAimTicks, 0.0, 1.0);
        if (shotCooldown > 0) return;
        if (visible && facingError < 6.0 && distance <= tuning.engagementRange()
                && readiness >= tuning.fireThreshold()) {
            fireAtPoint(aimPoint, readiness, tuning);
            shotCooldown = tuning.shotCooldownTicks();
        } else if (!visible && unseenTicks < SUPPRESS_AFTER_LOST_TICKS && facingError < 8.0
                && (role == RoguePmcRole.HEAVY_GUNNER || squadOrder == SquadOrder.SUPPRESS)
                && distance <= tuning.engagementRange()) {
            // Suppress where the enemy just ducked out of sight.
            fireAtPoint(aimPoint, 0.3, tuning);
            shotCooldown = Math.max(3, tuning.shotCooldownTicks());
        }
    }

    private boolean isValidTarget(@Nullable LivingEntity target, AiTuning tuning) {
        if (target == null || !target.isAlive() || target instanceof RoguePmcEntity) return false;
        if (target instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
        return target.level() == level() && distanceTo(target) <= Math.max(tuning.engagementRange(), tuning.visionRange()) * 1.5;
    }

    private double exposureOf(LivingEntity target) {
        if (target.isSwimming() || target.isVisuallyCrawling()) return Config.MONUMENT_PMC_PRONE_EXPOSURE.get();
        if (target.isCrouching()) return Config.MONUMENT_PMC_CROUCH_EXPOSURE.get();
        return 1.0;
    }

    private double requiredAimTicks(double distance, AiTuning tuning) {
        double distanceFactor = Math.clamp(distance / tuning.engagementRange(), 0.0, 1.0);
        return tuning.lockOnTicks() * (0.55 + distanceFactor * 1.45);
    }

    private void updateCombatMovement(LivingEntity target, double distance, boolean visible, AiTuning tuning,
                                      MonumentEntry monument) {
        boolean targetInside = monument.contains(target.blockPosition(), monument.dimension);
        Vec3 threat = visible ? target.position() : lastKnownPosition;
        if (!targetInside || squadOrder == SquadOrder.COVER) {
            seekCover(threat, monument);
            return;
        }
        if (underFireTicks > 0 && distance > tuning.rushRange() && random.nextDouble() > tuning.aggression()) {
            seekCover(threat, monument);
            return;
        }
        if (distance < tuning.rushRange()) {
            if (role == RoguePmcRole.SNIPER || tuning.aggression() < 0.35) seekCover(threat, monument);
            else {
                combatCoverPosition = null;
                advanceToward(threat, monument, 1.2);
            }
            return;
        }
        if (!visible) {
            if (role == RoguePmcRole.SNIPER || squadOrder == SquadOrder.SUPPRESS
                    || squadOrder == SquadOrder.OVERWATCH || tuning.aggression() < 0.3) {
                getNavigation().stop();
            } else {
                combatCoverPosition = null;
                advanceToward(threat, monument, 1.05);
            }
            return;
        }
        if (distance > tuning.preferredRange() && role != RoguePmcRole.SNIPER) {
            combatCoverPosition = null;
            advanceToward(threat, monument, 1.05);
            return;
        }
        if (combatCoverPosition == null && tuning.aggression() > 0.3 && random.nextInt(4) == 0) {
            strafe(target, monument);
            return;
        }
        if (combatCoverPosition == null) getNavigation().stop();
    }

    private void strafe(LivingEntity target, MonumentEntry monument) {
        Vec3 toTarget = target.position().subtract(position()).normalize();
        Vec3 side = new Vec3(-toTarget.z, 0.0, toTarget.x).scale(random.nextBoolean() ? 3.0 : -3.0);
        moveInsideMonument(getX() + side.x, getZ() + side.z, 1.1, monument);
    }

    private void seekCover(Vec3 threat, MonumentEntry monument) {
        if (combatCoverPosition == null || !monument.contains(combatCoverPosition, monument.dimension)) {
            combatCoverPosition = chooseCoverPosition(threat, monument);
        }
        if (combatCoverPosition != null && distanceToSqr(Vec3.atCenterOf(combatCoverPosition)) > 4.0) {
            getNavigation().moveTo(combatCoverPosition.getX() + 0.5, combatCoverPosition.getY(),
                    combatCoverPosition.getZ() + 0.5, 1.2);
            return;
        }
        if (combatCoverPosition == null) {
            Vec3 away = position().subtract(threat).normalize();
            if (moveInsideMonument(getX() + away.x * 8.0, getZ() + away.z * 8.0, 1.2, monument)) return;
        }
        getNavigation().stop();
    }

    private void advanceToward(Vec3 threat, MonumentEntry monument, double speed) {
        if (monument.contains(BlockPos.containing(threat), monument.dimension)) {
            Vec3 approach = threat.subtract(squadCenter()).normalize();
            double standoff = role == RoguePmcRole.SNIPER ? role.minimumRange() : Math.max(3.0, role.minimumRange() * 0.5);
            Vec3 rallyPoint = threat.subtract(approach.scale(standoff));
            double angle = formationAngle(formationSlot());
            double slotRadius = squadOrder == SquadOrder.FLANK ? 8.0 : 4.0;
            if (!moveInsideMonument(rallyPoint.x + Math.cos(angle) * slotRadius,
                    rallyPoint.z + Math.sin(angle) * slotRadius, speed, monument)) {
                moveInsideMonument(rallyPoint.x, rallyPoint.z, speed, monument);
            }
            return;
        }
        Vec3 direction = threat.subtract(position()).normalize();
        for (int distance = 10; distance >= 2; distance -= 2) {
            if (moveInsideMonument(getX() + direction.x * distance, getZ() + direction.z * distance, speed, monument)) return;
        }
        getNavigation().stop();
    }

    private boolean moveTowards(Vec3 position, double speed, MonumentEntry monument) {
        return moveInsideMonument(position.x, position.z, speed, monument);
    }

    private boolean moveInsideMonument(double x, double z, double speed, MonumentEntry monument) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int blockY = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        BlockPos destination = new BlockPos(blockX, blockY, blockZ);
        if (!monument.contains(destination, monument.dimension)
                || getNavigation().createPath(destination, 0) == null) return false;
        getNavigation().moveTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, speed);
        return true;
    }

    @Nullable
    private BlockPos chooseCoverPosition(Vec3 threat, MonumentEntry monument) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double desiredSector = formationAngle(formationSlot());
        Vec3 threatEyes = threat.add(0.0, 1.5, 0.0);
        for (int sample = 0; sample < 24; sample++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 4.0 + random.nextDouble() * 8.0;
            int x = Mth.floor(getX() + Math.cos(angle) * radius);
            int z = Mth.floor(getZ() + Math.sin(angle) * radius);
            int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!monument.contains(candidate, monument.dimension)
                    || getNavigation().createPath(candidate, 0) == null) continue;
            Vec3 candidateEyes = Vec3.atBottomCenterOf(candidate).add(0.0, getEyeHeight(Pose.CROUCHING), 0.0);
            HitResult obstruction = level().clip(new ClipContext(candidateEyes, threatEyes,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (obstruction.getType() == HitResult.Type.MISS) continue;
            double candidateSector = Math.atan2(candidate.getZ() - threat.z, candidate.getX() - threat.x);
            double sectorDifference = Math.abs(Math.atan2(Math.sin(candidateSector - desiredSector),
                    Math.cos(candidateSector - desiredSector)));
            double score = distanceToSqr(candidateEyes) - Vec3.atCenterOf(candidate).distanceToSqr(threat) * 0.08
                    + sectorDifference * 20.0;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof RoguePmcEntity) return false;
        boolean hurt = super.hurt(source, amount);
        if (!hurt || level().isClientSide || !(source.getEntity() instanceof LivingEntity attacker)
                || !attacker.isAlive() || attacker.level() != level()) return hurt;
        if (attacker instanceof Player player && (player.isCreative() || player.isSpectator())) return hurt;
        MonumentEntry monument = monument();
        if (monument == null) return hurt;
        AiTuning tuning = tuning(monument);
        underFireTicks = 120;
        combatCoverPosition = null;
        detection.merge(attacker.getUUID(), 0.5F, Float::sum);
        if (!(attacker instanceof Player) && distanceTo(attacker) < 16.0) {
            spot(attacker, monument);
            return hurt;
        }
        if (alertness != Alertness.COMBAT) {
            // We know roughly where it came from, not exactly who or where.
            double error = distanceTo(attacker) * 0.1;
            Vec3 guess = attacker.position().add((random.nextDouble() - 0.5) * 2.0 * error, 0.0,
                    (random.nextDouble() - 0.5) * 2.0 * error);
            threatId = attacker.getUUID();
            lastKnownPosition = guess;
            startSearch(guess, tuning.searchTicks(), true);
            squadAlarm(guess, monument);
        }
        return hurt;
    }

    // Squad

    /** We spotted someone: tell the squad where they were and give each member a job. */
    private void squadCallout(LivingEntity target, MonumentEntry monument) {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        level().playSound(null, blockPosition(), SoundEvents.VINDICATOR_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.8F);
        Vec3 position = target.position();
        boolean targetInside = monument.contains(target.blockPosition(), monument.dimension);
        for (RoguePmcEntity member : squadmates(serverLevel)) {
            if (member == this) continue;
            member.receiveCallout(target, position, targetInside ? member.orderFor() : SquadOrder.COVER);
        }
    }

    /** Periodic update while we can still see the target. */
    private void squadUpdate(LivingEntity target) {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        for (RoguePmcEntity member : squadmates(serverLevel)) {
            if (member == this || !target.getUUID().equals(member.threatId)) continue;
            if (member.alertness == Alertness.SEARCHING) member.investigatePosition = target.position();
            if (member.alertness == Alertness.COMBAT && member.unseenTicks > 0) member.lastKnownPosition = target.position();
        }
    }

    private void receiveCallout(LivingEntity target, Vec3 position, SquadOrder order) {
        squadOrder = order;
        threatId = target.getUUID();
        lastKnownPosition = position;
        // Knowing where to look makes spotting much faster, but they still have to see it themselves.
        detection.merge(target.getUUID(), 0.6F, Math::max);
        if (alertness == Alertness.COMBAT) return;
        MonumentEntry monument = monument();
        int ticks = monument == null ? 600 : tuning(monument).searchTicks();
        startSearch(flankPoint(position, order), ticks, order == SquadOrder.FLANK);
        lastKnownPosition = position;
    }

    /** Loud noise heard: the whole squad goes on alert toward it. */
    private void squadAlarm(Vec3 position, MonumentEntry monument) {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        for (RoguePmcEntity member : squadmates(serverLevel)) {
            if (member == this || member.alertness == Alertness.COMBAT || member.alertness == Alertness.SEARCHING) continue;
            SquadOrder order = member.orderFor();
            member.squadOrder = order;
            member.threatId = threatId;
            member.lastKnownPosition = position;
            member.startSearch(member.flankPoint(position, order), member.tuning(monument).searchTicks(),
                    order == SquadOrder.FLANK);
        }
    }

    /** Quiet noise heard: nearby squadmates freeze and look, only the hearer goes to check. */
    private void squadHold(Vec3 position) {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        for (RoguePmcEntity member : squadmates(serverLevel)) {
            if (member == this || member.alertness != Alertness.CALM
                    || member.distanceToSqr(this) > SQUAD_HOLD_RADIUS * SQUAD_HOLD_RADIUS) continue;
            member.becomeSuspicious(position, 80 + member.random.nextInt(40), false);
        }
    }

    private SquadOrder orderFor() {
        return switch (role) {
            case SNIPER -> SquadOrder.OVERWATCH;
            case HEAVY_GUNNER -> SquadOrder.SUPPRESS;
            case ASSAULT, LIGHT_GUNNER -> SquadOrder.FLANK;
        };
    }

    /** Flankers approach from the side (left/right by formation slot) instead of straight on. */
    private Vec3 flankPoint(Vec3 threat, SquadOrder order) {
        if (order != SquadOrder.FLANK) return threat;
        Vec3 toThreat = threat.subtract(position());
        if (toThreat.lengthSqr() < 1.0) return threat;
        Vec3 direction = toThreat.normalize();
        double side = formationSlot().index() % 2 == 0 ? 1.0 : -1.0;
        Vec3 offset = new Vec3(-direction.z, 0.0, direction.x).scale(10.0 * side);
        Vec3 candidate = threat.add(offset);
        MonumentEntry monument = monument();
        return monument != null && monument.contains(BlockPos.containing(candidate), monument.dimension) ? candidate : threat;
    }

    /** Other squads hearing our gunfire come to help, heading for where we're shooting. */
    private void announceGunfire(Vec3 aimPoint) {
        if (tickCount - lastAllyGunfireTick < ALLY_GUNFIRE_INTERVAL_TICKS
                || !(level() instanceof ServerLevel serverLevel)) return;
        lastAllyGunfireTick = tickCount;
        double radius = AiTuning.GUNSHOT_RADIUS;
        for (RoguePmcEntity ally : serverLevel.getEntitiesOfClass(RoguePmcEntity.class,
                getBoundingBox().inflate(radius * 1.5), ally -> ally != this && ally.isAlive()
                        && monumentId != null && monumentId.equals(ally.monumentId)
                        && (squadId == null || !squadId.equals(ally.squadId)))) {
            ally.hearAllyGunfire(this, aimPoint);
        }
    }

    private void hearAllyGunfire(RoguePmcEntity ally, Vec3 aimPoint) {
        MonumentEntry monument = monument();
        if (monument == null || alertness == Alertness.COMBAT || alertness == Alertness.SEARCHING) return;
        AiTuning tuning = tuning(monument);
        if (distanceTo(ally) > AiTuning.GUNSHOT_RADIUS * tuning.hearingMultiplier()) return;
        threatId = ally.threatId;
        lastKnownPosition = aimPoint;
        squadOrder = orderFor();
        startSearch(flankPoint(aimPoint, squadOrder), tuning.searchTicks(), squadOrder == SquadOrder.FLANK);
    }

    private FormationSlot formationSlot() {
        if (!(level() instanceof ServerLevel serverLevel)) return new FormationSlot(0, 1);
        List<RoguePmcEntity> squad = squadmates(serverLevel).stream()
                .sorted(Comparator.comparing(RoguePmcEntity::getUUID)).toList();
        return new FormationSlot(Math.max(0, squad.indexOf(this)), Math.max(1, squad.size()));
    }

    private static double formationAngle(FormationSlot slot) {
        return Math.PI * 2.0 * slot.index() / slot.count();
    }

    private Vec3 squadCenter() {
        if (!(level() instanceof ServerLevel serverLevel)) return position();
        List<RoguePmcEntity> squad = squadmates(serverLevel);
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (RoguePmcEntity member : squad) {
            x += member.getX();
            y += member.getY();
            z += member.getZ();
        }
        return new Vec3(x / squad.size(), y / squad.size(), z / squad.size());
    }

    private List<RoguePmcEntity> squadmates(ServerLevel level) {
        if (squadId == null || monumentId == null) return List.of(this);
        return level.getEntitiesOfClass(RoguePmcEntity.class, getBoundingBox().inflate(384.0), member ->
                member.isAlive() && monumentId.equals(member.monumentId) && squadId.equals(member.squadId));
    }

    // Looking

    private void lookAtPoint(Vec3 point, float speed) {
        getLookControl().setLookAt(point.x, point.y, point.z, speed, speed);
    }

    /** Idle head sweeps so the vision cone actually covers the area over time. */
    private void scanSurroundings(float speed) {
        if (--scanTicks <= 0) {
            scanTicks = 30 + random.nextInt(50);
            float base = getNavigation().isDone() ? scanYaw : yBodyRot;
            scanYaw = base + (random.nextFloat() - 0.5F) * 200.0F;
        }
        double radians = Math.toRadians(scanYaw);
        lookAtPoint(new Vec3(getX() - Math.sin(radians) * 8.0, getEyeY() + (random.nextFloat() - 0.6F) * 0.2,
                getZ() + Math.cos(radians) * 8.0), speed);
    }

    private double facingErrorDegrees(Vec3 point) {
        Vec3 toPoint = point.subtract(getEyePosition());
        if (toPoint.lengthSqr() < 1.0E-4) return 0.0;
        double cos = getViewVector(1.0F).dot(toPoint.normalize());
        return Math.toDegrees(Math.acos(Math.clamp(cos, -1.0, 1.0)));
    }

    // Patrol

    private MonumentEntry enforceMonumentBoundary() {
        if (!(level() instanceof ServerLevel serverLevel) || monumentId == null) return null;
        MonumentEntry monument = MonumentData.get(serverLevel.getServer()).get(monumentId);
        if (monument == null) {
            discard();
            return null;
        }
        String dimension = serverLevel.dimension().location().toString();
        if (monument.contains(blockPosition(), dimension)) {
            lastInsidePosition = blockPosition().immutable();
            return monument;
        }
        getNavigation().stop();
        BlockPos returnPosition = lastInsidePosition != null ? lastInsidePosition : homePosition;
        if (returnPosition == null || !monument.contains(returnPosition, dimension)) {
            // The monument was moved (or its chunks changed) while this guard was away: it has nowhere to return to.
            discard();
            return null;
        }
        teleportTo(returnPosition.getX() + 0.5, returnPosition.getY(), returnPosition.getZ() + 0.5);
        return monument;
    }

    private void updatePatrol(MonumentEntry monument) {
        if (!(level() instanceof ServerLevel serverLevel) || tickCount % 20 != 0) return;
        if (role == RoguePmcRole.SNIPER) {
            updateSniperPatrol(serverLevel, monument);
            return;
        }
        List<RoguePmcEntity> squad = squadmates(serverLevel);
        boolean leader = squad.stream().min(Comparator.comparing(RoguePmcEntity::getUUID)).orElse(this) == this;
        if (leader && (patrolPosition == null || holdingPatrolPosition && patrolWaitTicks <= 0)) {
            // The leader only moves the squad on once everyone is calm.
            if (squad.stream().anyMatch(member -> member.alertness != Alertness.CALM)) return;
            patrolPosition = choosePatrolPosition(serverLevel, monument, false, monument.controllerPos);
            holdingPatrolPosition = false;
            for (RoguePmcEntity member : squad) member.receivePatrolPosition(patrolPosition, monument);
        }
        moveToPatrolPosition(monument);
    }

    private void updateSniperPatrol(ServerLevel level, MonumentEntry monument) {
        if (patrolPosition == null || holdingPatrolPosition && patrolWaitTicks <= 0) {
            BlockPos squadCenter = squadmates(level).stream()
                    .filter(member -> member != this && member.patrolPosition != null)
                    .map(member -> member.patrolPosition).findFirst().orElse(monument.controllerPos);
            patrolPosition = choosePatrolPosition(level, monument, true, squadCenter);
            holdingPatrolPosition = false;
        }
        moveToPatrolPosition(monument);
    }

    private void moveToPatrolPosition(MonumentEntry monument) {
        if (patrolPosition == null || !monument.contains(patrolPosition, monument.dimension)) return;
        if (distanceToSqr(Vec3.atCenterOf(patrolPosition)) <= 9.0) {
            getNavigation().stop();
            if (!holdingPatrolPosition) {
                holdingPatrolPosition = true;
                patrolWaitTicks = role == RoguePmcRole.SNIPER ? 300 + random.nextInt(301) : 80 + random.nextInt(81);
            }
            setPose(role == RoguePmcRole.SNIPER ? Pose.SWIMMING : Pose.STANDING);
            return;
        }
        setPose(Pose.STANDING);
        getNavigation().moveTo(patrolPosition.getX() + 0.5, patrolPosition.getY(),
                patrolPosition.getZ() + 0.5, role == RoguePmcRole.LIGHT_GUNNER ? 1.0 : 0.85);
    }

    private BlockPos choosePatrolPosition(ServerLevel level, MonumentEntry monument, boolean highGround, BlockPos near) {
        BlockPos best = monument.controllerPos;
        double bestScore = -Double.MAX_VALUE;
        for (long chunkKey : monument.getDesignatedChunks()) {
            ChunkPos chunk = new ChunkPos(chunkKey);
            for (int sample = 0; sample < (highGround ? 3 : 1); sample++) {
                int x = chunk.getMinBlockX() + 2 + random.nextInt(12);
                int z = chunk.getMinBlockZ() + 2 + random.nextInt(12);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                double distancePenalty = Math.sqrt(candidate.distSqr(near)) * (highGround ? 0.08 : 0.02);
                double score = (highGround ? y * 2.0 : random.nextDouble() * 40.0) - distancePenalty;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private void receivePatrolPosition(BlockPos position, MonumentEntry monument) {
        if (role == RoguePmcRole.SNIPER || position == null) return;
        FormationSlot slot = formationSlot();
        double angle = formationAngle(slot);
        double radius = 8.0 + Math.floorMod(slot.index(), 3) * 3.0;
        patrolPosition = position;
        for (int attempt = 0; attempt < 4; attempt++) {
            double candidateRadius = radius - attempt * 2.0;
            int x = Mth.floor(position.getX() + 0.5 + Math.cos(angle) * candidateRadius);
            int z = Mth.floor(position.getZ() + 0.5 + Math.sin(angle) * candidateRadius);
            int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (monument.contains(candidate, monument.dimension)) {
                patrolPosition = candidate;
                break;
            }
        }
        holdingPatrolPosition = false;
    }

    // Shooting

    private void fireAtPoint(Vec3 targetPoint, double readiness, AiTuning tuning) {
        Vec3 origin = getEyePosition();
        Vec3 direction = targetPoint.subtract(origin);
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) (Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(direction.y, horizontal) * Mth.RAD_TO_DEG);
        double distanceFactor = Math.clamp(direction.length() / tuning.engagementRange(), 0.0, 1.0);
        float cone = (float) (tuning.coneDegrees() * (0.75 + distanceFactor * 1.25) * (1.0 - readiness * 0.85));
        yaw += (random.nextFloat() - 0.5F) * cone;
        pitch += (random.nextFloat() - 0.5F) * cone;

        if (!Tacz.shoot(this, pitch, yaw)) fireFallback(direction, cone);
        level().playSound(null, blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0F, 0.85F);
        announceGunfire(targetPoint);
    }

    private void fireFallback(Vec3 direction, float cone) {
        ItemStack weapon = getMainHandItem().isEmpty() ? null : getMainHandItem();
        AbstractArrow projectile = new Arrow(level(), this, new ItemStack(Items.ARROW), weapon);
        projectile.setBaseDamage(role.damage());
        projectile.pickup = AbstractArrow.Pickup.DISALLOWED;
        projectile.shoot(direction.x, direction.y, direction.z, 3.2F, Math.max(0.5F, cone));
        level().addFreshEntity(projectile);
    }

    // Equipment & saving

    private void equipRoleKit() {
        if (getMainHandItem().isEmpty()) {
            for (var gunId : role.gunIds()) {
                ItemStack gun = Tacz.createGun(level().registryAccess(), gunId);
                if (!gun.isEmpty()) {
                    setItemSlot(EquipmentSlot.MAINHAND, gun);
                    setDropChance(EquipmentSlot.MAINHAND, 0.0F);
                    Tacz.drawGun(this);
                    break;
                }
            }
            if (getMainHandItem().isEmpty()) {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
                setDropChance(EquipmentSlot.MAINHAND, 0.0F);
            }
        }
        switch (role) {
            case SNIPER -> {
                equipArmor(EquipmentSlot.CHEST, Items.CHAINMAIL_CHESTPLATE);
                equipArmor(EquipmentSlot.FEET, Items.LEATHER_BOOTS);
            }
            case ASSAULT -> {
                equipArmor(EquipmentSlot.HEAD, Items.IRON_HELMET);
                equipArmor(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
            }
            case HEAVY_GUNNER -> {
                equipArmor(EquipmentSlot.HEAD, Items.IRON_HELMET);
                equipArmor(EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE);
                equipArmor(EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
                equipArmor(EquipmentSlot.FEET, Items.IRON_BOOTS);
            }
            case LIGHT_GUNNER -> {
                equipArmor(EquipmentSlot.CHEST, Items.LEATHER_CHESTPLATE);
                equipArmor(EquipmentSlot.FEET, Items.LEATHER_BOOTS);
            }
        }
    }

    private void equipArmor(EquipmentSlot slot, Item item) {
        if (!getItemBySlot(slot).isEmpty()) return;
        setItemSlot(slot, new ItemStack(item));
        setDropChance(slot, 0.0F);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (monumentId != null) tag.putUUID("MonumentId", monumentId);
        if (squadId != null) tag.putUUID("SquadId", squadId);
        if (homePosition != null) tag.putLong("MonumentHome", homePosition.asLong());
        if (lastInsidePosition != null) tag.putLong("LastInsidePosition", lastInsidePosition.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setCustomName(null);
        monumentId = tag.hasUUID("MonumentId") ? tag.getUUID("MonumentId") : null;
        squadId = tag.hasUUID("SquadId") ? tag.getUUID("SquadId") : null;
        if (tag.contains("MonumentHome")) {
            homePosition = BlockPos.of(tag.getLong("MonumentHome"));
            restrictTo(homePosition, 128);
        }
        lastInsidePosition = tag.contains("LastInsidePosition")
                ? BlockPos.of(tag.getLong("LastInsidePosition")) : homePosition;
        equipRoleKit();
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }
}
