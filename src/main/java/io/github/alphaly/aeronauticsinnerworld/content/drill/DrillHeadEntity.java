package io.github.alphaly.aeronauticsinnerworld.content.drill;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class DrillHeadEntity extends ControlledContraptionEntity implements IControlContraption {
    private static final EntityDataAccessor<Boolean> WORKING = SynchedEntityData.defineId(DrillHeadEntity.class, EntityDataSerializers.BOOLEAN);
    private ResourceKey<Level> controllerDimension;
    private BlockPos returnPosition;
    private Vec3 interpolationTarget;
    private int interpolationTicks;

    public DrillHeadEntity(EntityType<?> type, Level level) {
        super(type, level);
        controllerDimension = level.dimension();
    }

    public static DrillHeadEntity create(Level level, DrillControllerBlockEntity controller, DrillContraption contraption) {
        var entity = new DrillHeadEntity(AeronauticsInnerworld.DRILL_HEAD_ENTITY.get(), level);
        entity.controllerPos = controller.getBlockPos();
        entity.setContraption(contraption);
        return entity;
    }

    public void bindController(GlobalPos owner) {
        controllerPos = owner.pos();
        controllerDimension = owner.dimension();
    }

    public BlockPos getReturnPosition() {
        return returnPosition;
    }

    public void setReturnPosition(BlockPos pos) {
        returnPosition = pos;
    }

    public void setWorking(boolean working) {
        entityData.set(WORKING, working);
    }

    public boolean isRemote() {
        return !level().dimension().equals(controllerDimension);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(WORKING, false);
    }

    @Override
    protected IControlContraption getController() {
        if (!isRemote()) {
            return super.getController();
        }
        if (level() instanceof ServerLevel serverLevel) {
            var source = serverLevel.getServer().getLevel(controllerDimension);
            if (source != null && source.isLoaded(controllerPos)
                    && source.getBlockEntity(controllerPos) instanceof DrillControllerBlockEntity controller) {
                return controller;
            }
        }
        // A remote head remains parked while its source chunk is unloaded.
        return this;
    }

    @Override
    protected boolean isActorActive(MovementContext context, MovementBehaviour behaviour) {
        boolean working = getController() instanceof DrillControllerBlockEntity controller
                ? controller.canWork() : level().isClientSide && entityData.get(WORKING);
        return working && super.isActorActive(context, behaviour);
    }

    @Override
    protected void tickContraption() {
        if (isRemote()) {
            if (level().isClientSide && interpolationTicks > 0) {
                setPos(position().lerp(interpolationTarget, 1.0 / interpolationTicks--));
            } else if (!level().isClientSide && getController() == this) {
                setWorking(false);
                setContraptionMotion(Vec3.ZERO);
            }
            if (!level().isClientSide) {
                var controller = getController();
                if (controller != this && !controller.isAttachedTo(this)) {
                    controller.attach(this);
                }
                // Server actors are advanced by the source controller, once per source tick.
                return;
            }
        }
        super.tickContraption();
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        if (isRemote()) {
            interpolationTarget = new Vec3(x, y, z);
            interpolationTicks = Math.max(1, steps);
        } else {
            super.lerpTo(x, y, z, yaw, pitch, steps);
        }
    }

    @Override
    protected void writeAdditional(CompoundTag tag, HolderLookup.Provider registries, boolean spawnPacket) {
        super.writeAdditional(tag, registries, spawnPacket);
        tag.putString("ControllerDimension", controllerDimension.location().toString());
        if (returnPosition != null) {
            tag.putLong("LayerReturnPosition", returnPosition.asLong());
        }
    }

    @Override
    protected void readAdditional(CompoundTag tag, boolean spawnPacket) {
        super.readAdditional(tag, spawnPacket);
        var id = ResourceLocation.tryParse(tag.getString("ControllerDimension"));
        controllerDimension = id == null ? level().dimension() : ResourceKey.create(Registries.DIMENSION, id);
        returnPosition = tag.contains("LayerReturnPosition") ? BlockPos.of(tag.getLong("LayerReturnPosition")) : null;
    }

    @Override
    public boolean isAttachedTo(AbstractContraptionEntity entity) {
        return entity == this;
    }

    @Override
    public void attach(ControlledContraptionEntity entity) {
    }

    @Override
    public void onStall() {
    }

    @Override
    public boolean isValid() {
        return isAlive();
    }

    @Override
    public BlockPos getBlockPosition() {
        return blockPosition();
    }
}
