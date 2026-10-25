package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.simulated_team.simulated.index.SimBlocks;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.CabinRegulatorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDeviceBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;

public final class ProbeTestRig {
    public final ServerLevel level;
    public ServerSubLevel subLevel;
    private final Map<BlockPos, BlockState> original = new HashMap<>();
    private final BlockPos origin;
    private BlockPos plotOrigin;
    private final java.util.ArrayList<SuperGlueEntity> glue = new java.util.ArrayList<>();
    private static final TicketType<BlockPos> ENVIRONMENT = TicketType.create("innerworld_probe_check",
            java.util.Comparator.comparingLong(BlockPos::asLong), 600);
    public static final BlockPos CABIN = new BlockPos(2, 1, 0);
    public static final BlockPos LEFT_DRIVE = new BlockPos(1, 1, 6);
    public static final BlockPos RIGHT_DRIVE = new BlockPos(3, 1, 6);
    public static final BlockPos HATCH = new BlockPos(2, 2, 0);

    public ProbeTestRig(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
        level.resetEmptyTime();
        level.getChunkSource().addRegionTicket(ENVIRONMENT, new ChunkPos(origin), 3, origin);
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 7; z++) {
                    if (x == 0 || x == 4 || y == 0 || y == 3 || z == 0 || z == 6) {
                        place(new BlockPos(x, y, z), AeronauticsInnerworld.REFRACTORY_HULL.get().defaultBlockState());
                    } else {
                        place(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        place(HATCH, AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState());
        place(new BlockPos(1, 1, 0), AeronauticsInnerworld.MANTLE_VIEWPORT.get().defaultBlockState());
        place(new BlockPos(1, 2, 0), AeronauticsInnerworld.MANTLE_VIEWPORT.get().defaultBlockState());
        place(new BlockPos(2, 3, 3), Blocks.SEA_LANTERN.defaultBlockState());
        device(CABIN, AeronauticsInnerworld.CABIN_REGULATOR.get(), Direction.SOUTH);
        device(new BlockPos(1, 0, 2), AeronauticsInnerworld.MANTLE_BALLAST.get(), Direction.UP);
        device(new BlockPos(3, 0, 2), AeronauticsInnerworld.MANTLE_BALLAST.get(), Direction.UP);
        device(LEFT_DRIVE, AeronauticsInnerworld.MOLTEN_THRUSTER.get(), Direction.SOUTH);
        device(RIGHT_DRIVE, AeronauticsInnerworld.MOLTEN_THRUSTER.get(), Direction.SOUTH);
        var assemblerPos = new BlockPos(2, 4, 3);
        place(assemblerPos, SimBlocks.PHYSICS_ASSEMBLER.getDefaultState());
        glue(BlockPos.ZERO, new BlockPos(4, 3, 6));
        glue(new BlockPos(1, 0, 2), new BlockPos(1, -1, 2));
        glue(new BlockPos(3, 0, 2), new BlockPos(3, -1, 2));
        glue(CABIN, CABIN.north());
        glue(assemblerPos, assemblerPos.below());
    }

    private void glue(BlockPos start, BlockPos end) {
        var entity = new SuperGlueEntity(level, SuperGlueEntity.span(origin.offset(start), origin.offset(end)));
        level.addFreshEntity(entity);
        glue.add(entity);
    }

    public boolean ready() {
        return level.getEntitiesOfClass(SuperGlueEntity.class,
                SuperGlueEntity.span(origin.offset(-2, -2, -2), origin.offset(7, 6, 9))).size() == glue.size();
    }

    public void assemble() {
        var container = SubLevelContainer.getContainer(level);
        var assemblerPos = new BlockPos(2, 4, 3);
        try {
            var result = SimAssemblyHelper.assembleFromSingleBlock(level, origin.offset(assemblerPos),
                    origin.offset(assemblerPos.below()), true, true);
            if (result == null) {
                throw new IllegalStateException("Native probe assembly returned no structure");
            }
            subLevel = (ServerSubLevel) result.subLevel();
            plotOrigin = origin.offset(result.offset());
        } catch (com.simibubi.create.content.contraptions.AssemblyException e) {
            throw new IllegalStateException("Native probe assembly failed", e);
        }
        container.addForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
    }

    private void locateCabin() {
        var bounds = subLevel.getPlot().getBoundingBox();
        for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (level.getBlockEntity(pos) instanceof CabinRegulatorBlockEntity) {
                plotOrigin = pos.subtract(CABIN).immutable();
                return;
            }
        }
        throw new IllegalStateException("Native assembly lost cabin regulator");
    }

    private void place(BlockPos relative, BlockState state) {
        var pos = origin.offset(relative);
        original.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlockAndUpdate(pos, state);
    }

    private void device(BlockPos pos, ProbeDeviceBlock block, Direction facing) {
        place(pos, block.defaultBlockState().setValue(ProbeDeviceBlock.FACING, facing));
        place(pos.relative(facing.getOpposite()), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, facing));
    }

    public BlockPos local(BlockPos relative) {
        return plotOrigin.offset(relative);
    }

    public Vec3 world(BlockPos relative) {
        return subLevel.logicalPose().transformPosition(Vec3.atCenterOf(local(relative)));
    }

    public void motor(BlockPos device, int speed) {
        var state = level.getBlockState(local(device));
        if (!(state.getBlock() instanceof ProbeDeviceBlock)) {
            throw new IllegalStateException("Probe device missing at relative " + device + ", plot origin=" + plotOrigin
                    + ", bounds=" + subLevel.getPlot().getBoundingBox() + ", actors="
                    + java.util.stream.StreamSupport.stream(subLevel.getPlot().getBlockEntityActors().spliterator(), false)
                            .map(actor -> actor instanceof net.minecraft.world.level.block.entity.BlockEntity entity
                                    ? entity.getBlockPos() + "=" + entity.getBlockState() : actor.toString()).toList());
        }
        var pos = local(device).relative(state.getValue(ProbeDeviceBlock.FACING).getOpposite());
        ((CreativeMotorBlockEntity) level.getBlockEntity(pos)).generatedSpeed.setValue(speed);
    }

    public void power() {
        motor(CABIN, 32);
        ballast(32);
        motor(LEFT_DRIVE, 0);
        motor(RIGHT_DRIVE, 0);
    }

    public void ballast(int speed) {
        motor(new BlockPos(1, 0, 2), speed);
        motor(new BlockPos(3, 0, 2), speed);
    }

    public void obstacle() {
        for (int x = -5; x <= 9; x++) {
            for (int y = -12; y <= 18; y++) {
                place(new BlockPos(x, y, 11), Blocks.BASALT.defaultBlockState());
            }
        }
    }

    public CabinRegulatorBlockEntity cabin() {
        return (CabinRegulatorBlockEntity) level.getBlockEntity(local(CABIN));
    }

    public void reload() {
        var saved = SubLevelSerializer.toData(subLevel, List.of());
        var container = SubLevelContainer.getContainer(level);
        container.removeForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        container.removeSubLevel(subLevel, SubLevelRemovalReason.UNLOADED);
        subLevel = SubLevelSerializer.fullyLoad(level, saved);
        locateCabin();
        container.addForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
    }

    public void cleanUp() {
        var container = SubLevelContainer.getContainer(level);
        if (subLevel != null) {
            container.removeForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
        glue.forEach(entity -> {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        });
        original.forEach(level::setBlockAndUpdate);
        level.getChunkSource().removeRegionTicket(ENVIRONMENT, new ChunkPos(origin), 3, origin);
    }
}
