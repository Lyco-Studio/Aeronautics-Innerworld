package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import net.minecraft.world.item.DyeColor;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.CabinRegulatorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDeviceBlock;
import io.github.alphaly.aeronauticsinnerworld.content.probe.EchoSounderBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.MantleSamplerBlockEntity;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;

public final class ProbeTestRig {
    public final ServerLevel level;
    public ServerSubLevel subLevel;
    private final Map<BlockPos, BlockState> original = new HashMap<>();
    private final BlockPos origin;
    private BlockPos plotOrigin;
    private SubLevelData saved;
    private final java.util.ArrayList<SuperGlueEntity> glue = new java.util.ArrayList<>();
    private static final TicketType<BlockPos> ENVIRONMENT = TicketType.create("innerworld_probe_check",
            java.util.Comparator.comparingLong(BlockPos::asLong), 600);
    public static final BlockPos CABIN = new BlockPos(2, 1, 0);
    public static final BlockPos LEFT_DRIVE = new BlockPos(1, 1, 6);
    public static final BlockPos RIGHT_DRIVE = new BlockPos(3, 1, 6);
    public static final BlockPos HATCH = new BlockPos(2, 2, 0);
    public static final BlockPos SOUNDER = new BlockPos(2, 1, 3);
    public static final BlockPos SAMPLER = new BlockPos(3, 1, 0);

    public ProbeTestRig(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
        level.resetEmptyTime();
        level.getChunkSource().addRegionTicket(ENVIRONMENT, new ChunkPos(origin), 3, origin);
        removeOldRig();
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

    public void workEquipment() {
        device(SOUNDER, AeronauticsInnerworld.ECHO_SOUNDER.get(), Direction.NORTH);
        device(SAMPLER, AeronauticsInnerworld.MANTLE_SAMPLER.get(), Direction.NORTH);
        place(SOUNDER.south(), SimBlocks.PORTABLE_ENGINES.get(DyeColor.BROWN).getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        ((PortableEngineBlockEntity) level.getBlockEntity(origin.offset(SOUNDER.south()))).inventory.setItem(0,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 4));
        ((EchoSounderBlockEntity) level.getBlockEntity(origin.offset(SOUNDER))).setHome(origin);
        ((MantleSamplerBlockEntity) level.getBlockEntity(origin.offset(SAMPLER))).inventory.setStackInSlot(0,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COPPER_INGOT, 17));
    }

    public BlockPos mineralTarget() {
        var point = subLevel == null ? Vec3.atCenterOf(origin.offset(3, 1, -2))
                : world(SAMPLER).add(subLevel.logicalPose().transformNormal(new Vec3(0, 0, -2)));
        var pos = BlockPos.containing(point);
        original.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlockAndUpdate(pos, AeronauticsInnerworld.HARMONIC_ORE.get().defaultBlockState());
        return pos;
    }

    public EchoSounderBlockEntity sounder() {
        return (EchoSounderBlockEntity) level.getBlockEntity(local(SOUNDER));
    }

    public MantleSamplerBlockEntity sampler() {
        return (MantleSamplerBlockEntity) level.getBlockEntity(local(SAMPLER));
    }

    public boolean ready() {
        removeOldRig();
        return level.getEntitiesOfClass(SuperGlueEntity.class,
                SuperGlueEntity.span(origin.offset(-2, -2, -2), origin.offset(7, 6, 9))).size() == glue.size();
    }

    public int sourceCopperDrops() {
        return level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new AABB(Vec3.atLowerCornerOf(origin.offset(-2, -2, -2)),
                        Vec3.atLowerCornerOf(origin.offset(7, 6, 9))))
                .stream().filter(entity -> entity.getItem().is(net.minecraft.world.item.Items.COPPER_INGOT))
                .mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private String rigName() {
        return "innerworld_probe_check_" + origin.getX() + "_" + origin.getZ();
    }

    private void removeOldRig() {
        if (subLevel != null) {
            return;
        }
        var container = SubLevelContainer.getContainer(level);
        for (var old : List.copyOf(container.getAllSubLevels())) {
            if (rigName().equals(old.getName())) {
                container.removeSubLevel(old, SubLevelRemovalReason.REMOVED);
            }
        }
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
            subLevel.setName(rigName());
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

    public void unload() {
        saved = SubLevelSerializer.toData(subLevel, List.of());
        var container = SubLevelContainer.getContainer(level);
        container.removeForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        container.removeSubLevel(subLevel, SubLevelRemovalReason.UNLOADED);
        subLevel = null;
    }

    public void restore() {
        subLevel = SubLevelSerializer.fullyLoad(level, saved);
        saved = null;
        locateCabin();
        SubLevelContainer.getContainer(level).addForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
    }

    public void cleanUp() {
        var container = SubLevelContainer.getContainer(level);
        if (subLevel != null) {
            if (level.getBlockEntity(local(SOUNDER.south())) instanceof PortableEngineBlockEntity engine) {
                engine.inventory.clearContent();
            }
            if (level.getBlockEntity(local(SAMPLER)) instanceof MantleSamplerBlockEntity sampler) {
                for (int slot = 0; slot < 9; slot++) {
                    sampler.inventory.setStackInSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
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
