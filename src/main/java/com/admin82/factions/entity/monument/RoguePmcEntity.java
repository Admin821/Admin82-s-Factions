package com.admin82.factions.entity.monument;

import com.admin82.factions.Config;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.Comparator;
import java.util.List;

public class RoguePmcEntity extends Monster {
    private static final int ENGAGEMENT_MEMORY_TICKS = 20 * 90;

    private enum EngagementOrder { NONE, HOLD, ADVANCE, COVER }
    private record FormationSlot(int index, int count) {}

    private final RoguePmcRole role;
    private UUID monumentId;
    private UUID squadId;
    private UUID sharedTargetId;
    private UUID aimedTargetId;
    private BlockPos homePosition;
    private BlockPos lastInsidePosition;
    private BlockPos patrolPosition;
    private BlockPos sharedEngagementPosition;
    private BlockPos combatCoverPosition;
    private double aimExposure;
    private int shotCooldown;
    private int retaliationTicks;
    private int underFireTicks;
    private int sharedEngagementTicks;
    private int patrolWaitTicks;
    private boolean holdingPatrolPosition;
    private EngagementOrder engagementOrder = EngagementOrder.NONE;

    public RoguePmcEntity(EntityType<? extends RoguePmcEntity> type, Level level, RoguePmcRole role) {
        super(type, level);
        this.role = role;
        setPersistenceRequired();
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 48.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
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
        TaczPmcBridge.drawGun(this);
    }

    public void refreshConfiguredLoadout(List<ItemStack> loadout) {
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) setItemSlot(slot, ItemStack.EMPTY);
        equipRoleKit();
        applyConfiguredLoadout(loadout);
    }

    public UUID getMonumentId() {
        return monumentId;
    }

    public RoguePmcRole getRole() {
        return role;
    }

    public void assignSquad(UUID id) {
        squadId = id;
        patrolPosition = null;
        holdingPatrolPosition = false;
        patrolWaitTicks = 0;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;
        MonumentEntry monument = enforceMonumentBoundary();
        if (monument == null) return;
        if (shotCooldown > 0) shotCooldown--;
        if (retaliationTicks > 0) retaliationTicks--;
        if (underFireTicks > 0) underFireTicks--;
        if (sharedEngagementTicks > 0) {
            sharedEngagementTicks--;
        } else {
            engagementOrder = EngagementOrder.NONE;
        }
        if (patrolWaitTicks > 0) patrolWaitTicks--;

        LivingEntity target = getTarget();
        if (!isValidTarget(target)) {
            setTarget(null);
            combatCoverPosition = null;
            underFireTicks = 0;
            aimExposure = Math.max(0.0, aimExposure - 2.0);
            updatePatrol(monument);
            return;
        }

        boolean atCombatCover = combatCoverPosition != null
            && distanceToSqr(Vec3.atCenterOf(combatCoverPosition)) <= 4.0;
        setPose(atCombatCover
            ? role == RoguePmcRole.HEAVY_GUNNER ? Pose.SWIMMING : Pose.CROUCHING
            : role == RoguePmcRole.SNIPER && holdingPatrolPosition ? Pose.SWIMMING : Pose.STANDING);

        double distance = distanceTo(target);
        boolean visible = getSensing().hasLineOfSight(target);
        if (!target.getUUID().equals(aimedTargetId)) {
            aimedTargetId = target.getUUID();
            aimExposure = 0.0;
        }
        if (visible && tickCount % 20 == 0) broadcastEngagement(target);
        if (visible) sharedEngagementPosition = target.blockPosition().immutable();
        double difficulty = difficultyScale(monument);
        AiCombatProfile profile = combatProfile(monument);
        double requiredAimTicks = requiredAimTicks(distance, profile);
        aimExposure = visible
            ? Math.min(requiredAimTicks, aimExposure + targetExposure(target) * (1.0 + 0.35 * difficulty))
                : Math.max(0.0, aimExposure - 1.5);
        getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (tickCount % 10 == 0) updateCombatMovement(target, distance, visible);
        double readiness = Math.clamp(aimExposure / requiredAimTicks, 0.0, 1.0);
        if (visible && distance <= profile.combatRange() && readiness >= profile.fireThreshold() && shotCooldown <= 0) {
            fireAt(target, readiness, difficulty, profile);
            shotCooldown = profile.shotCooldownTicks();
        } else if (role == RoguePmcRole.HEAVY_GUNNER && atCombatCover && !visible && sharedEngagementTicks > 0
                && sharedEngagementPosition != null && distanceToSqr(Vec3.atCenterOf(sharedEngagementPosition))
                <= profile.combatRange() * profile.combatRange() && shotCooldown <= 0) {
            fireAtPoint(Vec3.atCenterOf(sharedEngagementPosition), 0.35, difficulty, profile);
            shotCooldown = Math.max(3, profile.shotCooldownTicks());
        }
    }

    private AiCombatProfile combatProfile(MonumentEntry monument) {
        return AiCombatProfileData.get(((ServerLevel) level()).getServer())
                .getProfile(role, monument.getAiDifficultyLevel(role));
    }

    private double requiredAimTicks(double distance, AiCombatProfile profile) {
        double distanceFactor = Math.clamp(distance / profile.combatRange(), 0.0, 1.0);
        return profile.lockOnTicks() * (0.55 + distanceFactor * 1.45);
    }

    private boolean isValidTarget(LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        if (target instanceof RoguePmcEntity) return false;
        if (target instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
        return target.level().dimension().equals(level().dimension())
            && (distanceTo(target) <= role.range() || retaliationTicks > 0 && target == getTarget()
                || sharedEngagementTicks > 0 && target.getUUID().equals(sharedTargetId));
    }

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
        setTarget(null);
        BlockPos returnPosition = lastInsidePosition != null ? lastInsidePosition : homePosition;
        if (returnPosition != null) {
            teleportTo(returnPosition.getX() + 0.5, returnPosition.getY(), returnPosition.getZ() + 0.5);
        }
        return monument;
    }

    private double targetExposure(LivingEntity target) {
        if (target.isSwimming()) return Config.MONUMENT_PMC_PRONE_EXPOSURE.get();
        if (target.isCrouching()) return Config.MONUMENT_PMC_CROUCH_EXPOSURE.get();
        return 1.0;
    }

    private void updateCombatMovement(LivingEntity target, double distance, boolean visible) {
        MonumentEntry monument = monumentId == null || !(level() instanceof ServerLevel serverLevel)
                ? null : MonumentData.get(serverLevel.getServer()).get(monumentId);
        if (monument == null) return;
        AiCombatProfile profile = AiCombatProfileData.get(((ServerLevel) level()).getServer())
            .getProfile(role, monument.getAiDifficultyLevel(role));
        boolean targetInside = monument.contains(target.blockPosition(), monument.dimension);

        if (!targetInside) {
            seekCover(target, monument);
            return;
        }

        if (engagementOrder == EngagementOrder.COVER) {
            seekCover(target, monument);
            return;
        }

        if (distance < profile.rushRange() && distance > 0.01) {
            if (role == RoguePmcRole.SNIPER) {
                seekCover(target, monument);
            } else {
                combatCoverPosition = null;
                advanceToward(target, monument);
            }
            return;
        }

        if (engagementOrder == EngagementOrder.ADVANCE || !visible || distance > profile.preferredRange()) {
            combatCoverPosition = null;
            advanceToward(target, monument);
            return;
        }

        if (underFireTicks > 0 && distance <= profile.coverRange()) {
            seekCover(target, monument);
            return;
        }

        combatCoverPosition = null;
        getNavigation().stop();
    }

    private void seekCover(LivingEntity target, MonumentEntry monument) {
        if (combatCoverPosition == null || !monument.contains(combatCoverPosition, monument.dimension)) {
            combatCoverPosition = chooseCoverPosition(target, monument);
        }
        if (combatCoverPosition != null && distanceToSqr(Vec3.atCenterOf(combatCoverPosition)) > 4.0) {
            getNavigation().moveTo(combatCoverPosition.getX() + 0.5, combatCoverPosition.getY(),
                    combatCoverPosition.getZ() + 0.5, 1.2);
            return;
        }
        if (combatCoverPosition == null) {
            Vec3 away = position().subtract(target.position()).normalize();
            if (moveInsideMonument(getX() + away.x * 8.0, getZ() + away.z * 8.0, 1.2, monument)) return;
        }
        getNavigation().stop();
    }

    private void advanceToward(LivingEntity target, MonumentEntry monument) {
        if (monument.contains(target.blockPosition(), monument.dimension)) {
            Vec3 squadCenter = squadCenter();
            Vec3 approach = target.position().subtract(squadCenter).normalize();
            double standoff = role == RoguePmcRole.SNIPER
                    ? role.minimumRange() : Math.max(3.0, role.minimumRange() * 0.5);
            Vec3 rallyPoint = target.position().subtract(approach.scale(standoff));
            FormationSlot slot = formationSlot();
            double angle = formationAngle(slot);
            double slotRadius = role == RoguePmcRole.SNIPER ? 3.0 : 4.0;
            double destinationX = rallyPoint.x + Math.cos(angle) * slotRadius;
            double destinationZ = rallyPoint.z + Math.sin(angle) * slotRadius;
            if (!moveInsideMonument(destinationX, destinationZ,
                    retaliationTicks > 0 ? 1.2 : 1.05, monument)) {
                moveInsideMonument(rallyPoint.x, rallyPoint.z,
                        retaliationTicks > 0 ? 1.2 : 1.05, monument);
            }
            return;
        }
        Vec3 direction = target.position().subtract(position()).normalize();
        for (int distance = 10; distance >= 2; distance -= 2) {
            if (moveInsideMonument(getX() + direction.x * distance, getZ() + direction.z * distance,
                    retaliationTicks > 0 ? 1.2 : 1.05, monument)) return;
        }
        getNavigation().stop();
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

    private BlockPos chooseCoverPosition(LivingEntity target, MonumentEntry monument) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double desiredSector = formationAngle(formationSlot());
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
            HitResult obstruction = level().clip(new ClipContext(candidateEyes, target.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (obstruction.getType() == HitResult.Type.MISS) continue;
                double candidateSector = Math.atan2(candidate.getZ() - target.getZ(), candidate.getX() - target.getX());
                double sectorDifference = Math.abs(Math.atan2(Math.sin(candidateSector - desiredSector),
                    Math.cos(candidateSector - desiredSector)));
                double score = distanceToSqr(candidateEyes) - candidate.distSqr(target.blockPosition()) * 0.08
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
        if (hurt && !level().isClientSide && source.getEntity() instanceof LivingEntity attacker
            && !(attacker instanceof RoguePmcEntity) && attacker.isAlive()
            && attacker.level().dimension().equals(level().dimension())) {
            setTarget(attacker);
            retaliationTicks = ENGAGEMENT_MEMORY_TICKS;
            underFireTicks = 120;
            combatCoverPosition = null;
            aimedTargetId = attacker.getUUID();
            MonumentEntry monument = monumentId == null ? null
                    : MonumentData.get(((ServerLevel) level()).getServer()).get(monumentId);
            if (monument != null) {
                aimExposure = Math.max(aimExposure, requiredAimTicks(distanceTo(attacker), combatProfile(monument)) * 0.35);
            }
            getLookControl().setLookAt(attacker, 60.0F, 60.0F);
            broadcastEngagement(attacker, chooseEngagementOrder(attacker, true));
        }
        return hurt;
    }

    private void updatePatrol(MonumentEntry monument) {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        if (sharedEngagementTicks > 0 && sharedEngagementPosition != null
                && monument.contains(sharedEngagementPosition, monument.dimension)) {
            setPose(Pose.STANDING);
            if (distanceToSqr(Vec3.atCenterOf(sharedEngagementPosition)) > 9.0) {
                getNavigation().moveTo(sharedEngagementPosition.getX() + 0.5, sharedEngagementPosition.getY(),
                        sharedEngagementPosition.getZ() + 0.5, 1.15);
            }
            return;
        }
        if (tickCount % 20 != 0) return;

        if (role == RoguePmcRole.SNIPER) {
            updateSniperPatrol(serverLevel, monument);
            return;
        }

        List<RoguePmcEntity> squad = squadmates(serverLevel);
        boolean leader = squad.stream().min(Comparator.comparing(RoguePmcEntity::getUUID)).orElse(this) == this;
        if (leader && (patrolPosition == null || holdingPatrolPosition && patrolWaitTicks <= 0)) {
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
                patrolWaitTicks = role == RoguePmcRole.SNIPER
                        ? 300 + random.nextInt(301) : 80 + random.nextInt(81);
            }
            setPose(role == RoguePmcRole.SNIPER ? Pose.SWIMMING : Pose.STANDING);
            return;
        }
        setPose(Pose.STANDING);
        getNavigation().moveTo(patrolPosition.getX() + 0.5, patrolPosition.getY(),
                patrolPosition.getZ() + 0.5, role == RoguePmcRole.LIGHT_GUNNER ? 1.15 : 0.95);
    }

    private BlockPos choosePatrolPosition(ServerLevel level, MonumentEntry monument, boolean highGround,
                                          BlockPos near) {
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
        double radius = 8.0 + indexBand(slot.index()) * 3.0;
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

    private static int indexBand(int index) {
        return Math.floorMod(index, 3);
    }

    private void broadcastEngagement(LivingEntity target) {
        if (!(level() instanceof ServerLevel serverLevel) || !isSquadLeader(serverLevel)) return;
        broadcastEngagement(target, chooseEngagementOrder(target, underFireTicks > 0));
    }

    private void broadcastEngagement(LivingEntity target, EngagementOrder order) {
        if (!(level() instanceof ServerLevel serverLevel) || monumentId == null) return;
        for (RoguePmcEntity member : squadmates(serverLevel)) {
            member.receiveEngagement(target, order);
        }
    }

    private EngagementOrder chooseEngagementOrder(LivingEntity target, boolean respondingToFire) {
        if (!(level() instanceof ServerLevel serverLevel) || monumentId == null) return EngagementOrder.HOLD;
        MonumentEntry monument = MonumentData.get(serverLevel.getServer()).get(monumentId);
        if (monument == null) return EngagementOrder.HOLD;
        double distance = distanceTo(target);
        if (!monument.contains(target.blockPosition(), monument.dimension)) return EngagementOrder.COVER;
        if (role != RoguePmcRole.SNIPER && distance < role.minimumRange()) return EngagementOrder.ADVANCE;
        if (distance > role.preferredRange()) return EngagementOrder.ADVANCE;
        if (!squadIsGathered(serverLevel)) return EngagementOrder.ADVANCE;
        return respondingToFire ? EngagementOrder.COVER : EngagementOrder.HOLD;
    }

    private void receiveEngagement(LivingEntity target, EngagementOrder order) {
        sharedTargetId = target.getUUID();
        sharedEngagementPosition = target.blockPosition().immutable();
        sharedEngagementTicks = ENGAGEMENT_MEMORY_TICKS;
        engagementOrder = order;
        if (order == EngagementOrder.COVER) {
            underFireTicks = Math.max(underFireTicks, 120);
            combatCoverPosition = null;
        }
        setTarget(target);
    }

    private boolean isSquadLeader(ServerLevel level) {
        return squadmates(level).stream().min(Comparator.comparing(RoguePmcEntity::getUUID)).orElse(this) == this;
    }

    private FormationSlot formationSlot() {
        if (!(level() instanceof ServerLevel serverLevel)) return new FormationSlot(0, 1);
        List<RoguePmcEntity> squad = squadmates(serverLevel).stream()
                .sorted(Comparator.comparing(RoguePmcEntity::getUUID)).toList();
        int index = Math.max(0, squad.indexOf(this));
        return new FormationSlot(index, Math.max(1, squad.size()));
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

    private boolean squadIsGathered(ServerLevel level) {
        List<RoguePmcEntity> squad = squadmates(level);
        Vec3 center = squadCenter();
        return squad.stream().allMatch(member -> member.distanceToSqr(center) <= 64.0);
    }

    private List<RoguePmcEntity> squadmates(ServerLevel level) {
        if (squadId == null || monumentId == null) return List.of(this);
        return level.getEntitiesOfClass(RoguePmcEntity.class, getBoundingBox().inflate(384.0), member ->
                member.isAlive() && monumentId.equals(member.monumentId) && squadId.equals(member.squadId));
    }

    private double difficultyScale(MonumentEntry monument) {
        return (monument.getAiDifficultyLevel(role) - 3) / 2.0;
    }

    private void fireAt(LivingEntity target, double readiness, double difficulty, AiCombatProfile profile) {
        broadcastEngagement(target);
        Vec3 targetPoint = target.getEyePosition().subtract(0.0, target.getBbHeight() * 0.15, 0.0);
        fireAtPoint(targetPoint, readiness, difficulty, profile);
    }

    private void fireAtPoint(Vec3 targetPoint, double readiness, double difficulty, AiCombatProfile profile) {
        Vec3 origin = getEyePosition();
        Vec3 direction = targetPoint.subtract(origin);
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) (Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(direction.y, horizontal) * Mth.RAD_TO_DEG);
        double distanceFactor = Math.clamp(direction.length() / profile.combatRange(), 0.0, 1.0);
        float cone = (float) (profile.maximumConeDegrees() * (0.75 + distanceFactor * 1.25)
                * (1.0 - readiness) * (1.0 - 0.4 * difficulty));
        yaw += (random.nextFloat() - 0.5F) * cone;
        pitch += (random.nextFloat() - 0.5F) * cone;

        if (!TaczPmcBridge.shoot(this, pitch, yaw)) fireFallback(direction, cone, difficulty);
        level().playSound(null, blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0F, 0.85F);
    }

    private void fireFallback(Vec3 direction, float cone, double difficulty) {
        ItemStack weapon = getMainHandItem().isEmpty() ? null : getMainHandItem();
        AbstractArrow projectile = new Arrow(level(), this, new ItemStack(Items.ARROW), weapon);
        projectile.setBaseDamage(role.damage() * (1.0 + 0.3 * difficulty));
        projectile.pickup = AbstractArrow.Pickup.DISALLOWED;
        projectile.shoot(direction.x, direction.y, direction.z, 3.2F, Math.max(0.5F, cone));
        level().addFreshEntity(projectile);
    }

    private void equipRoleKit() {
        if (getMainHandItem().isEmpty()) {
            for (var gunId : role.gunIds()) {
                ItemStack gun = TaczPmcBridge.createGun(this, gunId);
                if (!gun.isEmpty()) {
                    setItemSlot(EquipmentSlot.MAINHAND, gun);
                    setDropChance(EquipmentSlot.MAINHAND, 0.0F);
                    TaczPmcBridge.drawGun(this);
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

    private void equipArmor(EquipmentSlot slot, net.minecraft.world.item.Item item) {
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