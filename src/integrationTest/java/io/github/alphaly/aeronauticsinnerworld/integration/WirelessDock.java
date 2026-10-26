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
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
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
    private int settled, elapsed, phaseTicks;
    private long started;
    private volatile int leftTrim, rightTrim;
    private volatile boolean canClose;

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
        if (elapsed++ == 0) started = level.getGameTime();
        if (!rig.cabin().isSealed() || player.isInLava() || player.getHealth() < player.getMaxHealth())
            throw new IllegalStateException("Wireless dock pilot lost cabin protection: tick=" + elapsed + ", sealed="
                    + rig.cabin().isSealed() + ", health=" + player.getHealth() + ", lava=" + player.isInLava()
                    + ", fire=" + player.isOnFire() + ", damage=" + (player.getLastDamageSource() == null ? "none" : player.getLastDamageSource().getMsgId())
                    + ", position=" + player.position() + ", hull=" + rig.subLevel.boundingBox());
        for (int z : new int[] {-3, -5}) if (level.getFluidState(at(2, 1, z)).is(FluidTags.LAVA))
            throw new IllegalStateException("Wireless dock flooded the fixed living area");
        var left = (ProbeDriveBlockEntity) level.getBlockEntity(rig.local(new BlockPos(1, 0, 2)));
        var right = (ProbeDriveBlockEntity) level.getBlockEntity(rig.local(new BlockPos(3, 0, 2)));
        leftTrim = left.getTrimMode();rightTrim = right.getTrimMode();
        boolean opening = level.getBlockState(latch).getValue(ToggleLatchBlock.POWERING);
        var box = rig.subLevel.boundingBox();
        var velocity = RigidBodyHandle.of(rig.subLevel).getLinearVelocity();
        if (++phaseTicks > 600 || elapsed > 2400) throw new IllegalStateException("Wireless dock voyage timed out: phase=" + phase
                + ", hull=" + box.minX() + "," + box.minY() + ".." + box.maxX() + "," + box.maxY()
                + ", velocity=" + velocity + ", trim=" + leftTrim + "/" + rightTrim
                + ", shutter=" + shutter.status() + ", gate=" + gate.status());
        switch (phase) {
            case 0 -> {
                if (opening && shutter.atPosition(false) && gate.atPosition(false)) next(1, rig, "gates open");
            }
            case 1 -> {
                if (!opening || !shutter.atPosition(false) || !gate.atPosition(false))
                    throw new IllegalStateException("Wireless dock did not retain its open end positions");
                var drive = (ProbeDriveBlockEntity) level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE));
                if (++settled >= 80 && drive.isImmersed()) next(2, rig, "flooded; lifting off the skids");
            }
            case 2 -> {if (box.minY() > 202.2) next(3, rig, "clear of the dock floor; holding depth");}
            case 3 -> {if (leftTrim == 0 && rightTrim == 0 && ++settled >= 15) next(4, rig, "leaving the dock");}
            case 4 -> {if (box.minX() > -487.5) next(5, rig, "entire hull in the launch well; descending");}
            case 5 -> {if (box.maxY() < 199.5) next(6, rig, "below the natural ceiling; sailing into open sea");}
            case 6 -> {if (box.minX() > -477) next(7, rig, "reached the natural lava sea; stopping");}
            case 7 -> {if (++settled >= 15) next(8, rig, "reversing toward the launch well");}
            case 8 -> {if (box.maxX() < -478) next(9, rig, "back in the flooded well; ascending");}
            case 9 -> {if (box.minY() > 202.2) next(10, rig, "above the return sill; holding depth");}
            case 10 -> {if (leftTrim == 0 && rightTrim == 0 && ++settled >= 15) next(11, rig, "reversing into the dock");}
            case 11 -> {if (box.maxX() < -488.5) next(12, rig, "entire hull inside the dock; stopping");}
            case 12 -> {
                var drive = (ProbeDriveBlockEntity) level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE));
                var other = (ProbeDriveBlockEntity) level.getBlockEntity(rig.local(ProbeTestRig.RIGHT_DRIVE));
                canClose = leftTrim == 0 && rightTrim == 0 && drive.getSpeed() == 0 && other.getSpeed() == 0 && Math.abs(velocity.x()) < .1;
                if (!opening && shutter.atPosition(true) && gate.atPosition(true)) next(13, rig, "gates closed; draining");
            }
            case 13 -> {
                for (var pos : BlockPos.betweenClosed(at(-2, -1, -1), at(6, 7, 7)))
                    if (level.getFluidState(pos).is(FluidTags.LAVA)) return false;
                if (!shutter.consumedFuel() || !gate.consumedFuel()) throw new IllegalStateException("Wireless dock did not consume coal");
                LogUtils.getLogger().info("Native client dock voyage complete: ticks={}, coolant={}, position={}", level.getGameTime() - started,
                        rig.cabin().getCoolant(), rig.world(ProbeTestRig.CABIN));
                return true;
            }
            default -> throw new IllegalStateException("Unknown dock voyage phase " + phase);
        }
        return false;
    }

    private void next(int value, ProbeTestRig rig, String message) {
        phase = value;phaseTicks = 0;settled = 0;
        LogUtils.getLogger().info("Native client dock voyage phase {}: {}; position={}, velocity={}", value, message,
                rig.world(ProbeTestRig.CABIN), RigidBodyHandle.of(rig.subLevel).getLinearVelocity());
    }

    public boolean sailing() {return phase >= 2 && phase <= 12;}
    public boolean readyToClose() {return phase == 12 && canClose;}
    public int requestedThrust() {return phase == 4 || phase == 6 ? 1 : phase == 8 || phase == 11 ? -1 : 0;}
    public int requestedTrim() {return phase == 2 || phase == 9 ? 1 : phase == 5 ? -1 : 0;}
    public int trimMode(int x) {return x == 1 ? leftTrim : rightTrim;}

    public void cleanUp() {
        gate.cleanUp();shutter.cleanUp();
        original.forEach(level::setBlockAndUpdate);
    }
}
