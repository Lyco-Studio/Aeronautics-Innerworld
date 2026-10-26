package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.redstone.diodes.ToggleLatchBlock;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.util.FakePlayer;

public final class WirelessDock {
    public static final BlockPos ORIGIN = new BlockPos(-496, 203, 2);
    public static final Rotation ROTATION = Rotation.COUNTERCLOCKWISE_90;
    private final ServerLevel level;
    private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
    private final FloodingShutter shutter;
    private final DockPortcullis gate;
    private final BlockPos latch;
    private volatile int phase;
    private int settled, elapsed;

    public WirelessDock(ServerLevel level) {
        this.level = level;
        for (var pos : BlockPos.betweenClosed(at(-4, -2, -2), at(7, 8, 8))) {
            var local = pos.subtract(ORIGIN).rotate(Rotation.CLOCKWISE_90);
            if (local.getX() == -4 || local.getX() == 7 || local.getZ() == -2 || local.getZ() == 8 || local.getY() == 8)
                set(pos, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            else if (local.getX() == -3 && local.getY() > -2 && level.getBlockState(pos).is(Blocks.DEEPSLATE_BRICKS))
                set(pos, Blocks.AIR.defaultBlockState());
        }
        for (int y : new int[] {1, 2}) set(at(2, y, -2), AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, ROTATION.rotate(Direction.NORTH)));
        for (var pos : BlockPos.betweenClosed(at(0, 9, 9), at(4, 9, 17))) set(pos, Blocks.LAVA.defaultBlockState());
        var builder = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "wireless-dock"));
        shutter = new FloodingShutter(level, ORIGIN, builder, ROTATION, true);
        gate = new DockPortcullis(level, ORIGIN, builder, ROTATION);
        shutter.fuel(16);gate.fuel(16);
        latch = at(-1, 1, -4);
        for (int z = -5; z <= -3; z++) set(at(-1, 0, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        set(latch, AllBlocks.POWERED_TOGGLE_LATCH.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, ROTATION.rotate(Direction.SOUTH)));
        link(at(-1, 1, -3), Direction.UP, true, Items.COPPER_INGOT.getDefaultInstance());
        link(at(-1, 1, -5), Direction.UP, false, Items.IRON_INGOT.getDefaultInstance());
        link(shutter.directionControl(), Direction.UP, true, Items.IRON_INGOT.getDefaultInstance());
        link(gate.directionControl(), ROTATION.rotate(Direction.WEST), true, Items.IRON_INGOT.getDefaultInstance());
        for (var control : new BlockPos[] {shutter.clutchControl(), gate.clutchControl()})
            level.setBlockAndUpdate(control, level.getBlockState(control).setValue(LeverBlock.POWERED, false));
    }

    private static BlockPos at(int x, int y, int z) {return ORIGIN.offset(new BlockPos(x, y, z).rotate(ROTATION));}

    private void set(BlockPos pos, BlockState state) {
        original.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlockAndUpdate(pos, state);
    }

    private void link(BlockPos pos, Direction face, boolean receiver, ItemStack frequency) {
        var state = AllBlocks.REDSTONE_LINK.getDefaultState().setValue(BlockStateProperties.FACING, face)
                .setValue(RedstoneLinkBlock.RECEIVER, receiver);
        if (level.getBlockState(pos).is(Blocks.LEVER)) level.setBlockAndUpdate(pos, state);
        else set(pos, state);
        // Deferred link behaviours are created by the initial block-entity data read.
        var data = new net.minecraft.nbt.CompoundTag();data.putBoolean("Transmitter", !receiver);
        level.getBlockEntity(pos).loadWithComponents(data, level.registryAccess());
        var behaviour = BlockEntityBehaviour.get(level, pos, LinkBehaviour.TYPE);
        behaviour.setFrequency(true, frequency);behaviour.setFrequency(false, Items.CLOCK.getDefaultInstance());
    }

    public ItemStack controller() {
        var controller = AllItems.LINKED_CONTROLLER.asStack();
        var items = new ArrayList<ItemStack>();
        for (int slot = 0; slot < 12; slot++) items.add(ItemStack.EMPTY);
        items.set(0, Items.COPPER_INGOT.getDefaultInstance());items.set(1, Items.CLOCK.getDefaultInstance());
        controller.set(AllDataComponents.LINKED_CONTROLLER_ITEMS, ItemContainerContents.fromItems(items));
        return controller;
    }

    public boolean advance(ServerPlayer player, ProbeTestRig rig) {
        ++elapsed;
        if (!rig.cabin().isSealed() || player.isInLava() || player.getHealth() < player.getMaxHealth())
            throw new IllegalStateException("Wireless dock pilot lost cabin protection: tick=" + elapsed + ", sealed="
                    + rig.cabin().isSealed() + ", health=" + player.getHealth() + ", lava=" + player.isInLava()
                    + ", fire=" + player.isOnFire() + ", damage=" + (player.getLastDamageSource() == null ? "none" : player.getLastDamageSource().getMsgId())
                    + ", position=" + player.position() + ", hull=" + rig.subLevel.boundingBox());
        boolean opening = level.getBlockState(latch).getValue(ToggleLatchBlock.POWERING);
        if (elapsed > 900) throw new IllegalStateException("Wireless dock timed out: phase=" + phase + ", latch="
                + level.getBlockState(latch) + ", shutter=" + shutter.status() + ", gate=" + gate.status());
        if (phase == 0 && opening && shutter.atPosition(false) && gate.atPosition(false)) {
            phase = 1;
            LogUtils.getLogger().info("Wireless dock opened through client controller; checking retained open state");
        }
        if (phase == 1 && ++settled >= 80) {
            if (!opening || !shutter.atPosition(false) || !gate.atPosition(false))
                throw new IllegalStateException("Wireless dock did not retain its open end positions");
            phase = 2;
            LogUtils.getLogger().info("Wireless dock retained open state; ready for the second client button input");
        }
        if (phase == 2 && !opening && shutter.atPosition(true) && gate.atPosition(true)) {
            phase = 3;
            LogUtils.getLogger().info("Wireless dock closed through client controller; waiting for drainage");
        }
        if (phase != 3) return false;
        for (var pos : BlockPos.betweenClosed(at(-2, -1, -1), at(6, 7, 7)))
            if (level.getFluidState(pos).is(FluidTags.LAVA)) return false;
        if (!shutter.consumedFuel() || !gate.consumedFuel()) throw new IllegalStateException("Wireless dock did not consume coal");
        return true;
    }

    public boolean readyToClose() {return phase == 2;}

    public void cleanUp() {
        gate.cleanUp();shutter.cleanUp();
        original.forEach(level::setBlockAndUpdate);
    }
}
