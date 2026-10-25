package io.github.alphaly.aeronauticsinnerworld.content.drill;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class BoreStationBlockEntity extends BlockEntity {
    private static final TicketType<BlockPos> TICKET = TicketType.create("innerworld_bore_station",
            Comparator.comparingLong(BlockPos::asLong), 40);
    private GlobalPos owner;
    private GlobalPos partner;
    private int ticks;

    public BoreStationBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.BORE_STATION_ENTITY.get(), pos, state);
    }

    public GlobalPos getOwner() {
        return owner;
    }

    public GlobalPos getPartner() {
        return partner;
    }

    void pairWith(GlobalPos destination) {
        if (destination.equals(partner)) {
            return;
        }
        partner = destination;
        setChanged();
    }

    public String bind(GlobalPos controller) {
        var destination = controller.dimension().equals(Level.OVERWORLD) ? Mantle.LEVEL : Core.LEVEL;
        if ((!controller.dimension().equals(Level.OVERWORLD) && !controller.dimension().equals(Mantle.LEVEL))
                || (!level.dimension().equals(controller.dimension()) && !level.dimension().equals(destination))) {
            return "station_wrong_layer";
        }
        if (level.dimension().equals(controller.dimension()) && worldPosition.distSqr(controller.pos()) > 32 * 32) {
            return "station_too_far";
        }
        if (!controller.equals(owner)) {
            owner = controller;
            partner = null;
            setChanged();
        }
        var source = findController(true);
        if (source != null) {
            source.registerStation(this);
        }
        return partner == null ? "station_bound" : "station_connected";
    }

    public void tick() {
        if (owner == null || ticks++ % 20 != 0) {
            return;
        }
        boolean nearby = level.getNearestPlayer(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5, 32, false) != null;
        if (!nearby && partner != null) {
            return;
        }
        var source = findController(nearby);
        if (source != null) {
            source.registerStation(this);
        }
    }

    private DrillControllerBlockEntity findController(boolean load) {
        if (owner == null || !(level instanceof ServerLevel here)) {
            return null;
        }
        var source = here.getServer().getLevel(owner.dimension());
        if (source == null || (!load && !source.isLoaded(owner.pos()))) {
            return null;
        }
        if (load) {
            source.resetEmptyTime();
            source.getChunkSource().addRegionTicket(TICKET, new ChunkPos(owner.pos()), 2, worldPosition);
            source.getChunkAt(owner.pos());
        }
        return source.getBlockEntity(owner.pos()) instanceof DrillControllerBlockEntity controller ? controller : null;
    }

    public boolean travel(ServerPlayer player) {
        var controller = findController(true);
        if (controller == null) {
            message(player, "station_controller_missing");
            return false;
        }
        if (partner == null) {
            controller.registerStation(this);
        }
        if (partner == null) {
            message(player, "station_waiting");
            return false;
        }
        if (controller.getSpeed() == 0) {
            message(player, "station_no_power");
            return false;
        }
        if (player.isPassenger() || player.isVehicle() || player.isOnPortalCooldown()) {
            message(player, "station_player_busy");
            return false;
        }
        var target = player.server.getLevel(partner.dimension());
        if (target == null) {
            message(player, "station_unavailable");
            return false;
        }
        target.resetEmptyTime();
        target.getChunkSource().addRegionTicket(TICKET, new ChunkPos(partner.pos()), 2, worldPosition);
        target.getChunkAt(partner.pos());
        var here = GlobalPos.of(level.dimension(), worldPosition);
        if (!(target.getBlockEntity(partner.pos()) instanceof BoreStationBlockEntity other)
                || !here.equals(other.partner) || !owner.equals(other.owner)) {
            message(player, "station_unavailable");
            return false;
        }
        var feet = partner.pos().relative(other.getBlockState().getValue(BoreStationBlock.FACING));
        var destination = Vec3.atBottomCenterOf(feet);
        var bounds = player.getBoundingBox().move(destination.subtract(player.position()));
        if (target.isOutsideBuildHeight(feet) || target.isOutsideBuildHeight(feet.above())
                || !target.getWorldBorder().isWithinBounds(bounds)
                || !target.getBlockState(feet.below()).isFaceSturdy(target, feet.below(), Direction.UP)
                || !target.noCollision(player, bounds) || target.containsAnyLiquid(bounds)
                || !target.getEntities(player, bounds,
                        entity -> entity instanceof LivingEntity && entity.isAlive() && !entity.isSpectator()).isEmpty()) {
            message(player, "station_exit_blocked");
            return false;
        }
        float yaw = other.getBlockState().getValue(BoreStationBlock.FACING).toYRot();
        player.teleportTo(target, destination.x, destination.y, destination.z, yaw, 0);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        player.setPortalCooldown(40);
        return true;
    }

    public void showStatus(ServerPlayer player) {
        if (partner == null) {
            message(player, owner == null ? "station_unbound" : "station_waiting");
        } else {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.station_destination",
                    Component.translatable("dimension.aeronautics_innerworld."
                            + (partner.dimension().equals(Level.OVERWORLD) ? "overworld"
                                    : partner.dimension().equals(Mantle.LEVEL) ? "mantle" : "core")),
                    partner.pos().getX(), partner.pos().getY(), partner.pos().getZ()), true);
        }
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld." + key), true);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) {
            tag.put("Controller", GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, owner).getOrThrow());
        }
        if (partner != null) {
            tag.put("Partner", GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, partner).getOrThrow());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("Controller")).result().orElse(null);
        partner = GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("Partner")).result().orElse(null);
    }
}
