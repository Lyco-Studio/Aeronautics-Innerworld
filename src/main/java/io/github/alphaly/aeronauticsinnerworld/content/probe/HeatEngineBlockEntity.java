package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class HeatEngineBlockEntity extends GeneratingKineticBlockEntity implements BlockEntitySubLevelActor {
    private boolean running;
    private int coolingPhase;
    private String status = "unassembled";

    public HeatEngineBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.HEAT_ENGINE_ENTITY.get(), pos, state);
    }

    @Override
    public float getGeneratedSpeed() {
        return running ? convertToDirection(32, getBlockState().getValue(HeatEngineBlock.FACING).getOpposite()) : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        lastCapacityProvided = 32;
        return lastCapacityProvided;
    }

    @Override
    public void initialize() {
        super.initialize();
        if (!level.isClientSide) updateGeneratedRotation();
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) return;
        var next = exchangeHeat();
        boolean active = next.equals("running");
        if (active != running || !next.equals(status)) {
            running = active;
            status = next;
            updateGeneratedRotation();
            setChanged();
            sendData();
        }
    }

    private String exchangeHeat() {
        if (!level.dimension().equals(Mantle.LEVEL)) return "wrong_dimension";
        if (!(Sable.HELPER.getContaining(this) instanceof ServerSubLevel ship)) return "unassembled";
        var pos = BlockPos.containing(ship.logicalPose().transformPosition(Vec3.atCenterOf(worldPosition)));
        if (!level.hasChunkAt(pos) || !level.getFluidState(pos).is(FluidTags.LAVA)) return "no_heat";
        CabinRegulatorBlockEntity source = null;
        boolean linked = false;
        for (var actor : ship.getPlot().getBlockEntityActors()) {
            if (actor instanceof CabinRegulatorBlockEntity cabin) {
                linked = true;
                if (cabin.getCoolant() > 0 && (source == null || cabin.getBlockPos().asLong() < source.getBlockPos().asLong())) source = cabin;
            }
        }
        if (!linked) return "no_link";
        if (source == null) return "no_coolant";
        // All engines debit the actual cabin store, including while that cabin has no shaft power.
        if (coolingPhase == 0 && !source.consumeCoolantTicks(1)) return "no_coolant";
        coolingPhase = (coolingPhase + 1) % 10;
        setChanged();
        return "running";
    }

    public boolean isRunning() {return running;}

    public String getStatus() {return status;}

    public void showStatus(Player player) {
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.heat_engine_status",
                Component.translatable("message.aeronautics_innerworld.heat_engine_" + status), Math.round(getSpeed())), true);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        super.addToGoggleTooltip(tooltip, sneaking);
        tooltip.add(Component.translatable("message.aeronautics_innerworld.heat_engine_" + status));
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("CoolingPhase", coolingPhase);
        if (clientPacket) {
            tag.putBoolean("HeatEngineRunning", running);
            tag.putString("HeatEngineStatus", status);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        coolingPhase = net.minecraft.util.Mth.clamp(tag.getInt("CoolingPhase"), 0, 9);
        running = clientPacket && tag.getBoolean("HeatEngineRunning");
        status = clientPacket ? tag.getString("HeatEngineStatus") : "unassembled";
    }
}
