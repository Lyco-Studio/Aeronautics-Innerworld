package io.github.alphaly.aeronauticsinnerworld.content.combat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.CabinRegulatorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class LavaStalker extends Monster {
    private static final EntityDataAccessor<Boolean> WINDING_UP = SynchedEntityData.defineId(LavaStalker.class, EntityDataSerializers.BOOLEAN);
    private int windup;
    private int attackCooldown;

    public LavaStalker(EntityType<? extends LavaStalker> type, Level level) {
        super(type, level);
        xpReward = 8;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 30).add(Attributes.MOVEMENT_SPEED, .22)
                .add(Attributes.ATTACK_DAMAGE, 4).add(Attributes.FOLLOW_RANGE, 32).add(Attributes.KNOCKBACK_RESISTANCE, .3);
    }

    public static boolean canSpawn(EntityType<LavaStalker> type, ServerLevelAccessor level, MobSpawnType reason,
            BlockPos pos, RandomSource random) {
        return level.getLevel().dimension().equals(Mantle.LEVEL) && level.getDifficulty() != Difficulty.PEACEFUL
                && pos.getY() > 8 && pos.getY() < 198 && level.getFluidState(pos).is(FluidTags.LAVA)
                && level.getFluidState(pos.above()).is(FluidTags.LAVA)
                && (reason == MobSpawnType.SPAWN_EGG || reason == MobSpawnType.SPAWNER || random.nextInt(8) == 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        targetSelector.addGoal(0, new NearestAttackableTargetGoal<>(this, Player.class, 10, false, false, null));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(WINDING_UP, false);
    }

    public boolean isWindingUp() {
        return entityData.get(WINDING_UP);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        setNoGravity(isInLava());
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        var target = getTarget();
        if (target == null || !target.isAlive()) {
            windup = 0;
            entityData.set(WINDING_UP, false);
            return;
        }
        var offset = target.getBoundingBox().getCenter().subtract(getBoundingBox().getCenter());
        getLookControl().setLookAt(target, 30, 30);
        if (isInLava() && offset.lengthSqr() > 1) {
            setDeltaMovement(getDeltaMovement().scale(.6).add(offset.normalize().scale(.08)));
        }
        var ray = level().clip(new ClipContext(getBoundingBox().getCenter(), target.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        var ship = ray.getType() == HitResult.Type.BLOCK ? Sable.HELPER.getContaining(level(), ray.getBlockPos()) : null;
        boolean nearHull = ship != null && ship.logicalPose().transformPosition(Vec3.atCenterOf(ray.getBlockPos()))
                .distanceToSqr(getBoundingBox().getCenter()) <= 9;
        boolean nearPlayer = ray.getType() == HitResult.Type.MISS && offset.lengthSqr() <= 4;
        if (!nearHull && !nearPlayer) {
            windup = 0;
            entityData.set(WINDING_UP, false);
            return;
        }
        if (attackCooldown > 0) {
            return;
        }
        if (windup++ == 0) {
            entityData.set(WINDING_UP, true);
            playSound(SoundEvents.BLAZE_SHOOT, .8f, .6f);
        }
        if (windup < 20) {
            return;
        }
        if (nearHull && ship instanceof ServerSubLevel serverShip) {
            var pos = ray.getBlockPos();
            var state = level().getBlockState(pos);
            boolean hit = false;
            if (level().getBlockEntity(pos) instanceof ProbeDriveBlockEntity drive) {
                drive.fail();
                hit = true;
            } else if (state.is(AeronauticsInnerworld.REFRACTORY_HULL.get())) {
                hit = level().setBlockAndUpdate(pos, AeronauticsInnerworld.DAMAGED_HULL.get().defaultBlockState());
            } else if (state.getDestroySpeed(level(), pos) >= 0 && state.getBlock().getExplosionResistance() <= 12) {
                hit = level().destroyBlock(pos, true);
            }
            if (hit) {
                for (var actor : serverShip.getPlot().getBlockEntityActors()) {
                    if (actor instanceof CabinRegulatorBlockEntity cabin) {
                        cabin.recordHullDamage(pos);
                    }
                }
                if (target instanceof Player player) {
                    player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.hull_under_attack"), true);
                }
            }
        } else if (nearPlayer) {
            doHurtTarget(target);
        }
        windup = 0;
        entityData.set(WINDING_UP, false);
        attackCooldown = 80;
    }
}
