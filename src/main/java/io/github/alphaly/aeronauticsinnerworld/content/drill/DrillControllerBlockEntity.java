package io.github.alphaly.aeronauticsinnerworld.content.drill;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption.MovementMode;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.utility.ServerSpeedProvider;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class DrillControllerBlockEntity extends LinearActuatorBlockEntity {
    private static final int STROKE_LENGTH = 128;
    private Vec3 segmentOrigin = Vec3.ZERO;
    private Direction movementDirection = Direction.DOWN;
    private boolean engaged;
    private boolean stopRequested;
    private String status = "idle";
    private GlobalPos remoteHead;
    private UUID remoteHeadId;
    private GlobalPos ticketedHead;
    private BlockPos sourceStation;
    private BlockPos mantleStation;
    private boolean mantleBored;

    public DrillControllerBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.DRILL_CONTROLLER_ENTITY.get(), pos, state);
        movementDirection = state.getValue(DrillControllerBlock.FACING);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Placement is explicit; the actuator's automatic placement selector is unused.
    }

    @Override
    protected ValueBoxTransform getMovementModeSlot() {
        return new CenteredSideValueBoxTransform();
    }

    @Override
    protected MovementMode getMovementMode() {
        return MovementMode.MOVE_NEVER_PLACE;
    }

    public boolean canWork() {
        return running && engaged && getSpeed() != 0 && !isRemoved();
    }

    public Direction getMovementDirection() {
        return movementDirection;
    }

    public String getStatus() {
        if (running && movedContraption == null && remoteHead != null) {
            return "loading_head";
        }
        if (running && engaged && getSpeed() == 0) {
            return "no_power";
        }
        return status;
    }

    public Component getStatusMessage() {
        var direction = Component.translatable("direction.aeronautics_innerworld." + movementDirection.getName());
        var state = Component.translatable("status.aeronautics_innerworld." + getStatus());
        if (!(movedContraption instanceof DrillHeadEntity head) || !head.isAlive()) {
            return Component.translatable("message.aeronautics_innerworld.controller_status", direction, state);
        }
        var dimension = head.level().dimension();
        Component layer = dimension.equals(net.minecraft.world.level.Level.OVERWORLD)
                ? Component.translatable("dimension.aeronautics_innerworld.overworld")
                : dimension.equals(Mantle.LEVEL) ? Component.translatable("dimension.aeronautics_innerworld.mantle")
                : dimension.equals(Core.LEVEL) ? Component.translatable("dimension.aeronautics_innerworld.core")
                : Component.literal(dimension.location().toString());
        var bounds = head.getContraption().bounds;
        return Component.translatable("message.aeronautics_innerworld.drill_head_status", layer,
                head.getBlockX(), head.getBlockZ(), Mth.floor(head.getY() + bounds.minY),
                Mth.ceil(head.getY() + bounds.maxY) - 1, direction, state);
    }

    public void toggleWork() {
        if (running && movedContraption == null) {
            return;
        }
        if (!running) {
            try {
                assemble();
                lastException = null;
            } catch (AssemblyException exception) {
                lastException = exception;
                status = "assembly_failed";
            }
        } else if (engaged) {
            requestPause();
        } else {
            engaged = true;
            stopRequested = false;
            waitingForSpeedChange = false;
            status = "working";
        }
        notifyUpdate();
    }

    public void requestPause() {
        if (isGridAligned()) {
            pauseImmediately("paused");
        } else {
            stopRequested = true;
            status = "stopping";
            notifyUpdate();
        }
    }

    public boolean setMovementDirection(Direction direction) {
        if (engaged || stopRequested || !isGridAligned() || (running && movedContraption == null)) {
            return false;
        }
        if (movedContraption != null) {
            segmentOrigin = toPosition(offset);
            DrillLayerTravel.resetActors((DrillHeadEntity) movedContraption);
        }
        movementDirection = direction;
        offset = getInitialOffset();
        clientOffsetDiff = 0;
        waitingForSpeedChange = false;
        forceMove = true;
        status = running ? "paused" : "idle";
        notifyUpdate();
        return true;
    }

    public boolean cycleDirection() {
        Direction[] directions = {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
        for (int i = 0; i < directions.length; i++) {
            if (directions[i] == movementDirection) {
                return setMovementDirection(directions[(i + 1) % directions.length]);
            }
        }
        return false;
    }

    private boolean isGridAligned() {
        return Math.abs(offset - Math.round(offset)) < 1.0e-4;
    }

    @Override
    public void tick() {
        if (!level.isClientSide) {
            maintainRemoteHead();
            if (movedContraption instanceof DrillHeadEntity head && head.isAlive() && head.isRemote()) {
                // The powered source advances remote actors even before the destination has players.
                head.tickActors();
            }
        }
        super.tick();
        if (level.isClientSide) {
            return;
        }
        if (stopRequested && isGridAligned()) {
            pauseImmediately("paused");
        }
        if (movedContraption instanceof DrillHeadEntity head) {
            head.setWorking(canWork());
            if (canWork() && isGridAligned() && DrillLayerTravel.tryCross(this, head)) {
                return;
            }
        }
        if (movedContraption != null && !canWork()) {
            movedContraption.setContraptionMotion(Vec3.ZERO);
        }
    }

    private void maintainRemoteHead() {
        if (movedContraption instanceof DrillHeadEntity head && head.isAlive()) {
            remoteHead = head.level() == level ? null : GlobalPos.of(head.level().dimension(), head.blockPosition());
            remoteHeadId = remoteHead == null ? null : head.getUUID();
        }
        if (!running) {
            remoteHead = null;
            remoteHeadId = null;
        }
        var source = (ServerLevel) level;
        if (ticketedHead != null && (remoteHead == null || !ticketedHead.dimension().equals(remoteHead.dimension())
                || !new ChunkPos(ticketedHead.pos()).equals(new ChunkPos(remoteHead.pos())))) {
            var oldLevel = source.getServer().getLevel(ticketedHead.dimension());
            if (oldLevel != null) {
                oldLevel.getChunkSource().removeRegionTicket(DrillLayerTravel.CHUNK_TICKET,
                        new ChunkPos(ticketedHead.pos()), 2, worldPosition);
            }
            ticketedHead = null;
        }
        if (remoteHead == null) {
            return;
        }
        var target = source.getServer().getLevel(remoteHead.dimension());
        if (target == null) {
            return;
        }
        target.getChunkSource().addRegionTicket(DrillLayerTravel.CHUNK_TICKET, new ChunkPos(remoteHead.pos()), 2, worldPosition);
        target.resetEmptyTime();
        if (movedContraption != null && target.getNearestPlayer(movedContraption, 128) != null) {
            // Chunk tickets alone do not prevent an empty dimension from suspending block-entity ticks.
            source.resetEmptyTime();
            source.getChunkSource().addRegionTicket(DrillLayerTravel.CHUNK_TICKET, new ChunkPos(worldPosition), 2, worldPosition);
        }
        ticketedHead = remoteHead;
        if (movedContraption == null || !movedContraption.isAlive()) {
            target.getChunkAt(remoteHead.pos());
            if (remoteHeadId != null && target.getEntity(remoteHeadId) instanceof DrillHeadEntity head) {
                movedContraption = head;
            }
        }
    }

    void pauseForLayer(String reason) {
        pauseImmediately(reason);
    }

    void acceptTransferredHead(DrillHeadEntity head) {
        if (head.level().dimension().equals(destinationLayer())) {
            mantleBored = true;
        }
        movedContraption = head;
        segmentOrigin = head.position();
        offset = getInitialOffset();
        clientOffsetDiff = 0;
        pauseImmediately("layer_arrived");
        if (level instanceof ServerLevel source) {
            source.getChunkSource().addRegionTicket(DrillLayerTravel.CHUNK_TICKET, new ChunkPos(worldPosition), 2, worldPosition);
        }
        maintainRemoteHead();
    }

    @Override
    public void attach(ControlledContraptionEntity entity) {
        if (!running) {
            segmentOrigin = entity.position();
            offset = getInitialOffset();
            engaged = false;
            status = "paused";
            forceMove = true;
        }
        super.attach(entity);
    }

    @Override
    protected void visitNewPosition() {
        setChanged();
    }

    @Override
    protected boolean isPassive() {
        return !canWork();
    }

    @Override
    protected boolean moveAndCollideContraption() {
        if (movedContraption instanceof DrillHeadEntity head
                && (head.level().dimension().equals(Mantle.LEVEL) || head.level().dimension().equals(Core.LEVEL))
                && head.getY() + head.getContraption().bounds.maxY + Math.max(0, getMotionVector().y)
                        >= head.level().getMaxBuildHeight() - 1.0e-4) {
            if (head.isStalled()) {
                head.setContraptionMotion(Vec3.ZERO);
                return false;
            }
            var motion = getMotionVector();
            head.setContraptionMotion(motion);
            head.move(motion.x, motion.y, motion.z);
            return DrillLayerTravel.collidesAtCeiling(head, movementDirection);
        }
        return super.moveAndCollideContraption();
    }

    @Override
    public float getMovementSpeed() {
        float speed = Mth.clamp(Math.abs(getSpeed()) / 512, 0, 0.49f);
        float untilGrid = (float) (Math.floor(offset + 1.0e-4) + 1 - offset);
        speed = Math.min(speed, untilGrid);
        return level.isClientSide ? (speed + clientOffsetDiff / 2) * ServerSpeedProvider.get() : speed;
    }

    @Override
    public void onSpeedChanged(float previousSpeed) {
        float previousOffset = offset;
        super.onSpeedChanged(previousSpeed);
        // Shaft reversal changes speed, while travel direction is selected separately.
        if (offset != previousOffset) {
            offset = previousOffset;
            resetContraptionToOffset();
        }
        assembleNextTick = false;
        forceMove = true;
        notifyUpdate();
    }

    @Override
    protected void assemble() throws AssemblyException {
        if (running || level == null || level.isClientSide) {
            return;
        }
        var origin = worldPosition.relative(getBlockState().getValue(DrillControllerBlock.FACING));
        var contraption = new DrillContraption(worldPosition);
        if (!contraption.assemble(level, origin)) {
            status = "empty";
            return;
        }
        segmentOrigin = Vec3.atLowerCornerOf(origin);
        offset = getInitialOffset();
        var entity = DrillHeadEntity.create(level, this, contraption);
        entity.setPos(toPosition(offset));
        if (!level.addFreshEntity(entity)) {
            status = "assembly_failed";
            return;
        }
        contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
        movedContraption = entity;
        running = true;
        engaged = true;
        stopRequested = false;
        waitingForSpeedChange = false;
        status = "working";
        forceMove = true;
        notifyUpdate();
    }

    public boolean tryPlaceHead() {
        if (engaged || !isGridAligned() || movedContraption == null) {
            return false;
        }
        var anchor = BlockPos.containing(toPosition(offset));
        var headLevel = movedContraption.level();
        for (var pos : movedContraption.getContraption().getBlocks().keySet()) {
            var target = anchor.offset(pos);
            if (!headLevel.isLoaded(target) || headLevel.isOutsideBuildHeight(target)
                    || !headLevel.getBlockState(target).canBeReplaced()) {
                status = "placement_blocked";
                notifyUpdate();
                return false;
            }
        }
        var stations = movedContraption.getContraption().getBlocks().entrySet().stream()
                .filter(entry -> entry.getValue().state().is(AeronauticsInnerworld.BORE_STATION.get()))
                .map(entry -> anchor.offset(entry.getKey())).toList();
        movedContraption.disassemble();
        // The destination may unload as soon as the moving head releases its ticket.
        for (var pos : stations) {
            if (headLevel.getBlockEntity(pos) instanceof BoreStationBlockEntity station) {
                registerStation(station);
            }
        }
        movedContraption = null;
        disassemble();
        return true;
    }

    public boolean reconnectHead(DrillHeadEntity head) {
        if (!head.getControllerLocation().equals(GlobalPos.of(level.dimension(), worldPosition))
                || (movedContraption != null && movedContraption != head)
                || (running && remoteHeadId != null && !remoteHeadId.equals(head.getUUID()))) {
            return false;
        }
        attach(head);
        if (head.isRemote() && head.level().dimension().equals(destinationLayer())) {
            mantleBored = true;
        }
        maintainRemoteHead();
        notifyUpdate();
        return true;
    }

    void registerStation(BoreStationBlockEntity station) {
        if (!GlobalPos.of(level.dimension(), worldPosition).equals(station.getOwner())) {
            return;
        }
        if (station.getLevel() == level) {
            if (worldPosition.distSqr(station.getBlockPos()) <= 32 * 32
                    && (sourceStation == null || sourceStation.equals(station.getBlockPos())
                    || !(level.getBlockEntity(sourceStation) instanceof BoreStationBlockEntity previous)
                    || !station.getOwner().equals(previous.getOwner()))) {
                if (!station.getBlockPos().equals(sourceStation)) {
                    sourceStation = station.getBlockPos();
                    setChanged();
                }
                if (station.getPartner() != null && station.getPartner().dimension().equals(destinationLayer())) {
                    if (!station.getPartner().pos().equals(mantleStation)) {
                        mantleStation = station.getPartner().pos();
                        mantleBored = true;
                        setChanged();
                    }
                } else if (mantleStation != null) {
                    var mantle = ((ServerLevel) level).getServer().getLevel(destinationLayer());
                    if (mantle != null && mantle.getBlockEntity(mantleStation) instanceof BoreStationBlockEntity target
                            && station.getOwner().equals(target.getOwner())) {
                        pairStations(station, target);
                    }
                }
            }
            return;
        }
        if (!mantleBored || sourceStation == null || !station.getLevel().dimension().equals(destinationLayer())
                || !(level.getBlockEntity(sourceStation) instanceof BoreStationBlockEntity source)
                || !station.getOwner().equals(source.getOwner())) {
            return;
        }
        var destination = GlobalPos.of(station.getLevel().dimension(), station.getBlockPos());
        if (source.getPartner() == null || source.getPartner().equals(destination)
                || !(station.getLevel().getBlockEntity(source.getPartner().pos()) instanceof BoreStationBlockEntity previous)
                || !station.getOwner().equals(previous.getOwner())) {
            pairStations(source, station);
        }
    }

    private void pairStations(BoreStationBlockEntity source, BoreStationBlockEntity target) {
        source.pairWith(GlobalPos.of(target.getLevel().dimension(), target.getBlockPos()));
        target.pairWith(GlobalPos.of(level.dimension(), source.getBlockPos()));
        if (!target.getBlockPos().equals(mantleStation)) {
            mantleStation = target.getBlockPos();
            setChanged();
        }
    }

    private ResourceKey<net.minecraft.world.level.Level> destinationLayer() {
        return level.dimension().equals(Mantle.LEVEL) ? Core.LEVEL : Mantle.LEVEL;
    }

    @Override
    public void disassemble() {
        if (movedContraption != null) {
            movedContraption.setContraptionMotion(Vec3.ZERO);
            var head = movedContraption;
            // Controller removal must not place a head into terrain or outside build height.
            if (head instanceof DrillHeadEntity drillHead) {
                drillHead.setWorking(false);
                drillHead.getContraption().stop(head.level());
            } else {
                head.disassemble();
            }
            movedContraption = null;
        }
        running = false;
        engaged = false;
        stopRequested = false;
        status = "idle";
        maintainRemoteHead();
        notifyUpdate();
    }

    @Override
    protected void collided() {
        pauseImmediately("blocked");
    }

    @Override
    protected void tryDisassemble() {
        pauseImmediately("stroke_end");
    }

    private void pauseImmediately(String reason) {
        engaged = false;
        stopRequested = false;
        status = reason;
        if (movedContraption != null) {
            movedContraption.setContraptionMotion(Vec3.ZERO);
            if (movedContraption instanceof DrillHeadEntity head) {
                head.setWorking(false);
            }
        }
        forceMove = true;
        notifyUpdate();
    }

    @Override
    protected int getExtensionRange() {
        return STROKE_LENGTH + getInitialOffset();
    }

    @Override
    protected int getInitialOffset() {
        return 1;
    }

    @Override
    protected Vec3 toMotionVector(float speed) {
        return Vec3.atLowerCornerOf(movementDirection.getNormal()).scale(speed);
    }

    @Override
    protected Vec3 toPosition(float offset) {
        return segmentOrigin.add(toMotionVector(offset - getInitialOffset()));
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 16;
        return lastStressApplied;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.putString("TravelDirection", movementDirection.getName());
        tag.putDouble("SegmentX", segmentOrigin.x);
        tag.putDouble("SegmentY", segmentOrigin.y);
        tag.putDouble("SegmentZ", segmentOrigin.z);
        tag.putBoolean("Engaged", engaged);
        tag.putBoolean("StopRequested", stopRequested);
        tag.putString("WorkStatus", status);
        tag.putBoolean("MantleBored", mantleBored);
        if (sourceStation != null) {
            tag.putLong("SourceStation", sourceStation.asLong());
        }
        if (mantleStation != null) {
            tag.putLong("MantleStation", mantleStation.asLong());
        }
        if (remoteHead != null) {
            var position = movedContraption == null ? remoteHead.pos() : movedContraption.blockPosition();
            tag.putString("HeadDimension", remoteHead.dimension().location().toString());
            tag.putLong("HeadPosition", position.asLong());
            if (remoteHeadId != null) {
                tag.putUUID("HeadIdentity", remoteHeadId);
            }
        }
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        var direction = Direction.byName(tag.getString("TravelDirection"));
        movementDirection = direction == null ? Direction.DOWN : direction;
        segmentOrigin = new Vec3(tag.getDouble("SegmentX"), tag.getDouble("SegmentY"), tag.getDouble("SegmentZ"));
        engaged = tag.getBoolean("Engaged");
        stopRequested = tag.getBoolean("StopRequested");
        status = tag.getString("WorkStatus");
        mantleBored = tag.getBoolean("MantleBored");
        sourceStation = tag.contains("SourceStation") ? BlockPos.of(tag.getLong("SourceStation")) : null;
        mantleStation = tag.contains("MantleStation") ? BlockPos.of(tag.getLong("MantleStation")) : null;
        var headDimension = ResourceLocation.tryParse(tag.getString("HeadDimension"));
        remoteHead = headDimension == null ? null : GlobalPos.of(ResourceKey.create(Registries.DIMENSION, headDimension),
                BlockPos.of(tag.getLong("HeadPosition")));
        remoteHeadId = tag.hasUUID("HeadIdentity") ? tag.getUUID("HeadIdentity") : null;
        super.read(tag, registries, clientPacket);
    }
}
