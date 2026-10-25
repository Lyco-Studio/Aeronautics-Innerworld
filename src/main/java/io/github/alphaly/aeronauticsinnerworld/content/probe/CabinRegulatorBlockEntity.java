package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import dev.ryanhcode.sable.util.BoundedBitVolume3i;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class CabinRegulatorBlockEntity extends KineticBlockEntity {
    private WaterOcclusionRegion region;
    private int ticks;
    private int volume;

    public CabinRegulatorBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.CABIN_REGULATOR_ENTITY.get(), pos, state);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 4;
        return lastStressApplied;
    }

    @Override
    public void tick() {
        super.tick();
        if (Math.abs(getSpeed()) < 16 || (region != null && region.isDirty())) {
            clearRegion();
        }
        if (region == null && Math.abs(getSpeed()) >= 16 && ticks++ % 10 == 0) {
            buildRegion();
        }
    }

    private void buildRegion() {
        var container = WaterOcclusionContainer.getContainer(level);
        if (container == null || Sable.HELPER.getContaining(this) == null) {
            return;
        }
        var seed = worldPosition.relative(getBlockState().getValue(ProbeDeviceBlock.FACING));
        var pending = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        Set<BlockPos> interior = new HashSet<>();
        pending.add(seed);
        while (!pending.isEmpty()) {
            var pos = pending.removeFirst();
            if (!visited.add(pos)) {
                continue;
            }
            if (visited.size() > 8192 || Math.abs(pos.getX() - seed.getX()) > 16
                    || Math.abs(pos.getY() - seed.getY()) > 16 || Math.abs(pos.getZ() - seed.getZ()) > 16
                    || !level.hasChunkAt(pos)) {
                return;
            }
            var state = level.getBlockState(pos);
            if (Block.isShapeFullBlock(state.getCollisionShape(level, pos))) {
                continue;
            }
            // Stored liquid must be drained before the compartment can be sealed.
            if (!state.getFluidState().isEmpty() || interior.size() >= 4096) {
                return;
            }
            interior.add(pos);
            for (var direction : Direction.values()) {
                pending.addLast(pos.relative(direction));
            }
        }
        if (!interior.isEmpty()) {
            region = container.addRegion(BoundedBitVolume3i.fromBlocks(interior));
            volume = interior.size();
        }
    }

    public boolean isSealed() {
        return region != null && !region.isDirty();
    }

    public void showStatus(Player player) {
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.cabin_status",
                Component.translatable("message.aeronautics_innerworld."
                        + (isSealed() ? "cabin_sealed" : Math.abs(getSpeed()) < 16 ? "cabin_unpowered" : "cabin_open")), volume), true);
    }

    private void clearRegion() {
        if (region != null && level != null) {
            var container = WaterOcclusionContainer.getContainer(level);
            if (container != null) {
                container.removeRegion(region);
            }
        }
        region = null;
        volume = 0;
    }

    @Override
    public void invalidate() {
        clearRegion();
        super.invalidate();
    }
}
