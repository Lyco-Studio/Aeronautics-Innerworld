package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;

final class DockPortcullis {
    private final ServerLevel level;
    private final FakePlayer player;
    private final Rotation rotation;
    private final BlockPos origin, piston, engine, clutchLever, reverseLever;
    private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
    private final AABB glueBounds;
    private final Set<UUID> previousGlue = new HashSet<>();
    private boolean closing = true;
    private int startingFuel;

    DockPortcullis(ServerLevel level, BlockPos origin, FakePlayer player) {
        this(level, origin, player, Rotation.NONE);
    }

    DockPortcullis(ServerLevel level, BlockPos origin, FakePlayer player, Rotation rotation) {
        this.level = level;this.origin = origin;this.player = player;this.rotation = rotation;
        glueBounds = SuperGlueEntity.span(at(-1, -1, 8), at(8, 14, 8));
        level.getEntitiesOfClass(SuperGlueEntity.class, glueBounds).forEach(entity -> previousGlue.add(entity.getUUID()));
        var anchor = new BlockPos(8, 6, 8);
        piston = at(anchor);engine = at(anchor.north(3));
        clutchLever = at(anchor.north(2).west());reverseLever = at(anchor.north().west());
        for (var pos : BlockPos.betweenClosed(new BlockPos(6, 5, 4), new BlockPos(9, 7, 8))) {
            var local = pos;
            boolean wall = local.getX() == 6 || local.getX() == 9 || local.getY() != 6
                    || local.getZ() == 4 || local.getZ() == 8;
            set(at(pos), wall ? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        for (var pos : BlockPos.betweenClosed(new BlockPos(-1, 8, 8), new BlockPos(8, 14, 8)))
            set(at(pos), Blocks.AIR.defaultBlockState());
        for (var pos : BlockPos.betweenClosed(new BlockPos(-1, -1, 8), new BlockPos(5, 7, 8)))
            set(at(pos), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        for (int x = 6; x <= 8; x++) set(at(x, 7, 8), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        set(piston, AllBlocks.STICKY_MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false));
        for (int length = 1; length <= 7; length++)
            set(at(anchor.below(length)), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        set(at(anchor.north()), AllBlocks.GEARSHIFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        set(at(anchor.north(2)), AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        set(reverseLever, lever(true));set(clutchLever, lever(true));
        set(engine, SimBlocks.PORTABLE_ENGINES.get(DyeColor.BROWN).getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        fuel(4);
        glue(at(-1, -1, 8), at(5, 7, 8));
        glue(at(-1, 7, 8), at(8, 7, 8));
    }

    private static BlockState lever(boolean powered) {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, Direction.WEST).setValue(LeverBlock.POWERED, powered);
    }

    private BlockPos at(BlockPos relative) {return origin.offset(relative.rotate(rotation));}
    private BlockPos at(int x, int y, int z) {return at(new BlockPos(x, y, z));}

    private void set(BlockPos pos, BlockState state) {
        original.putIfAbsent(pos.immutable(), level.getBlockState(pos));level.setBlockAndUpdate(pos, state.rotate(rotation));
    }

    private void glue(BlockPos from, BlockPos to) {
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(from, to)));
    }

    void command(boolean closed) {
        closing = closed;setLever(reverseLever, !closed);setLever(clutchLever, false);
    }

    void tick() {if (atRequestedPosition()) setLever(clutchLever, true);}

    boolean atRequestedPosition() {
        return atPosition(closing);
    }

    BlockPos directionControl() {return reverseLever;}
    BlockPos clutchControl() {return clutchLever;}

    boolean atPosition(boolean closed) {
        int lift = closed ? 0 : 7;
        for (var pos : BlockPos.betweenClosed(at(-1, -1 + lift, 8), at(5, 7 + lift, 8)))
            if (!level.getBlockState(pos).is(Blocks.DEEPSLATE_BRICKS)) return false;
        for (int x = 6; x <= 8; x++)
            if (!level.getBlockState(at(x, 7 + lift, 8)).is(Blocks.DEEPSLATE_BRICKS)) return false;
        return closed || !level.getBlockState(at(2, -1, 8)).is(Blocks.DEEPSLATE_BRICKS);
    }

    void fuel(int count) {
        startingFuel = count;
        ((PortableEngineBlockEntity) level.getBlockEntity(engine)).inventory.setItem(0, new ItemStack(Items.COAL, count));
    }

    boolean consumedFuel() {
        return level.getBlockEntity(engine) instanceof PortableEngineBlockEntity source && source.inventory.getItem(0).getCount() < startingFuel;
    }

    String status() {
        var actuator = (MechanicalPistonBlockEntity) level.getBlockEntity(piston);
        return level.getBlockState(piston) + ", rpm=" + actuator.getSpeed() + ", closed=" + closing;
    }

    private void setLever(BlockPos pos, boolean powered) {
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER)) throw new IllegalStateException("Dock gate control was damaged at " + pos);
        if (state.getValue(LeverBlock.POWERED) == powered) return;
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), rotation.rotate(Direction.WEST), pos, false));
    }

    void cleanUp() {
        if (level.getBlockEntity(piston) instanceof MechanicalPistonBlockEntity actuator) actuator.disassemble();
        if (level.getBlockEntity(engine) instanceof PortableEngineBlockEntity source) source.inventory.clearContent();
        level.getEntitiesOfClass(SuperGlueEntity.class, glueBounds).stream()
                .filter(entity -> !previousGlue.contains(entity.getUUID())).forEach(SuperGlueEntity::discard);
        original.forEach(level::setBlockAndUpdate);
    }
}
