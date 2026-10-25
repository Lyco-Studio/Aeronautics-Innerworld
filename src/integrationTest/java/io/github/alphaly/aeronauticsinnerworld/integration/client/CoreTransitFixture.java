package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class CoreTransitFixture {
    final BlockPos coreStation;
    final BlockPos mantleStation;
    private final ServerLevel core;
    private final ServerLevel mantle;
    private final LinkedHashMap<GlobalPos, BlockState> original = new LinkedHashMap<>();

    CoreTransitFixture(ServerPlayer player, BlockPos entrance, Direction outward) {
        core = player.server.getLevel(Core.LEVEL);mantle = player.server.getLevel(Mantle.LEVEL);
        coreStation = entrance.relative(outward, 3);mantleStation = new BlockPos(coreStation.getX(), 245, coreStation.getZ());
        var controllerPos = mantleStation.west(3);
        place(mantle, controllerPos, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                .setValue(DrillControllerBlock.FACING, Direction.DOWN));
        place(mantle, controllerPos.above(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
        ((CreativeMotorBlockEntity) mantle.getBlockEntity(controllerPos.above())).generatedSpeed.setValue(64);
        place(mantle, mantleStation, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState());
        place(core, coreStation, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState().setValue(BoreStationBlock.FACING, outward));
        for (var destination : java.util.List.of(GlobalPos.of(Core.LEVEL, coreStation.relative(outward)),
                GlobalPos.of(Mantle.LEVEL, mantleStation.north()))) {
            var level = player.server.getLevel(destination.dimension());var feet = destination.pos();
            place(level, feet, Blocks.AIR.defaultBlockState());place(level, feet.above(), Blocks.AIR.defaultBlockState());
            place(level, feet.below(), Blocks.IRON_BLOCK.defaultBlockState());
        }
        var owner = GlobalPos.of(Mantle.LEVEL, controllerPos);
        pair(mantle, mantleStation, owner, GlobalPos.of(Core.LEVEL, coreStation));
        pair(core, coreStation, owner, GlobalPos.of(Mantle.LEVEL, mantleStation));
        var feet = coreStation.relative(outward);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.getInventory().setItem(17, new ItemStack(Items.COPPER_INGOT, 17));player.containerMenu.broadcastChanges();
        player.setPortalCooldown(0);
        player.teleportTo(core, feet.getX() + .5, feet.getY(), feet.getZ() + .5, outward.getOpposite().toYRot(), 15);
    }

    private void pair(ServerLevel level, BlockPos pos, GlobalPos owner, GlobalPos partner) {
        var station = (BoreStationBlockEntity) level.getBlockEntity(pos);
        station.bind(owner);
        var tag = station.saveWithFullMetadata(level.registryAccess());
        tag.put("Partner", GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, partner).getOrThrow());
        station.loadWithComponents(tag, level.registryAccess());station.setChanged();
    }

    private void place(ServerLevel level, BlockPos pos, BlockState state) {
        original.putIfAbsent(GlobalPos.of(level.dimension(), pos), level.getBlockState(pos));level.setBlockAndUpdate(pos, state);
    }

    void verifyCargo(ServerPlayer player) {
        var cargo = player.getInventory().getItem(17);
        if (!cargo.is(Items.COPPER_INGOT) || cargo.getCount() != 17) throw new IllegalStateException("Core station transit altered carried supplies");
    }

    void cleanUp() {
        original.forEach((pos, state) -> (pos.dimension().equals(Core.LEVEL) ? core : mantle).setBlockAndUpdate(pos.pos(), state));
    }
}
