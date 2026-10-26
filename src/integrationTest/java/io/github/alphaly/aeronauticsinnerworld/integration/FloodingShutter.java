package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;

final class FloodingShutter {
    private final ServerLevel level;
    private final FakePlayer player;
    private final BlockPos origin, piston, engine, clutchLever, reverseLever;
    private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
    private final AABB glueBounds;
    private final Set<UUID> previousGlue = new HashSet<>();
    private boolean closing = true;

    FloodingShutter(ServerLevel level, BlockPos origin, FakePlayer player) {
        this.level = level;this.origin = origin;this.player = player;
        glueBounds = SuperGlueEntity.span(origin.offset(-7, 7, -5), origin.offset(10, 10, 8));
        level.getEntitiesOfClass(SuperGlueEntity.class, glueBounds).forEach(entity -> previousGlue.add(entity.getUUID()));
        piston = origin.offset(-1, 8, -1);engine = piston.north(3);
        clutchLever = piston.north(2).above();reverseLever = piston.north().above();
        for (var pos : BlockPos.betweenClosed(origin.offset(-7, 8, -5), origin.offset(10, 9, 8))) {
            var relative = pos.subtract(origin);
            boolean intake = relative.getY() == 9 && relative.getX() >= 0 && relative.getX() <= 4
                    && relative.getZ() >= -1 && relative.getZ() <= 7;
            if (intake) {
                if (!level.getFluidState(pos).is(FluidTags.LAVA))
                    throw new IllegalStateException("Shutter intake has no natural lava at " + pos);
            } else set(pos, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        }
        for (var pos : BlockPos.betweenClosed(piston.offset(-1, 1, -4), piston.offset(1, 2, 0)))
            set(pos, pos.getY() == piston.getY() + 2 || pos.getX() != piston.getX()
                    || pos.getZ() == piston.getZ() - 4 || pos.getZ() == piston.getZ()
                    ? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
        for (var pos : BlockPos.betweenClosed(origin.offset(0, 8, -1), origin.offset(9, 8, 7)))
            set(pos, pos.getX() <= origin.getX() + 4 ? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
        for (var pos : BlockPos.betweenClosed(origin.offset(5, 7, -1), origin.offset(9, 7, 7)))
            set(pos, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        var pistonState = AllBlocks.STICKY_MECHANICAL_PISTON.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.EAST)
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false);
        if (AllBlocks.STICKY_MECHANICAL_PISTON.get().getRotationAxis(pistonState) != Direction.Axis.Z)
            throw new IllegalStateException("Shutter piston does not expose a north-south shaft");
        set(piston, pistonState);
        for (int length = 1; length <= 5; length++)
            set(piston.west(length), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        set(piston.north(), AllBlocks.GEARSHIFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        set(piston.north(2), AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        set(reverseLever, lever(true));set(clutchLever, lever(true));
        set(engine, SimBlocks.PORTABLE_ENGINES.get(DyeColor.BROWN).getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        ((PortableEngineBlockEntity) level.getBlockEntity(engine)).inventory.setItem(0, new ItemStack(Items.COAL, 4));
        var glue = new SuperGlueEntity(level, SuperGlueEntity.span(origin.offset(0, 8, -1), origin.offset(4, 8, 7)));
        level.addFreshEntity(glue);
    }

    private static BlockState lever(boolean powered) {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.POWERED, powered);
    }

    private void set(BlockPos pos, BlockState state) {
        original.putIfAbsent(pos.immutable(), level.getBlockState(pos));level.setBlockAndUpdate(pos, state);
    }

    void command(boolean closed) {
        closing = closed;setLever(reverseLever, !closed);setLever(clutchLever, false);
    }

    void tick() {
        if (atRequestedPosition()) setLever(clutchLever, true);
    }

    boolean atRequestedPosition() {
        for (int x = 0; x <= 4; x++) for (int z = -1; z <= 7; z++) {
            if (!level.getBlockState(origin.offset(x + (closing ? 0 : 5), 8, z)).is(Blocks.DEEPSLATE_BRICKS)) return false;
            if (!closing && level.getBlockState(origin.offset(x, 8, z)).is(Blocks.DEEPSLATE_BRICKS)) return false;
        }
        return true;
    }

    boolean consumedFuel() {
        return level.getBlockEntity(engine) instanceof PortableEngineBlockEntity source
                && source.inventory.getItem(0).getCount() < 4;
    }

    String status() {
        var actuator = (MechanicalPistonBlockEntity) level.getBlockEntity(piston);
        return level.getBlockState(piston) + ", rpm=" + actuator.getSpeed() + ", closed=" + closing;
    }

    private void setLever(BlockPos pos, boolean powered) {
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER)) throw new IllegalStateException("Shutter control was damaged at " + pos);
        if (state.getValue(LeverBlock.POWERED) == powered) return;
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    void cleanUp() {
        if (level.getBlockEntity(piston) instanceof MechanicalPistonBlockEntity actuator) actuator.disassemble();
        if (level.getBlockEntity(engine) instanceof PortableEngineBlockEntity source) source.inventory.clearContent();
        level.getEntitiesOfClass(SuperGlueEntity.class, glueBounds).stream()
                .filter(entity -> !previousGlue.contains(entity.getUUID())).forEach(SuperGlueEntity::discard);
        original.forEach(level::setBlockAndUpdate);
    }
}
