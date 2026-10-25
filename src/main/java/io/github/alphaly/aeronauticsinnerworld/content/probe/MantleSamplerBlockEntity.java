package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

public final class MantleSamplerBlockEntity extends KineticBlockEntity implements Clearable {
    private static final TagKey<Block> MINEABLE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "probe_mineable"));
    public final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private BlockPos target;
    private float progress;
    private String status = "sampler_idle";

    public MantleSamplerBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.MANTLE_SAMPLER_ENTITY.get(), pos, state);
    }

    public String getStatus() {
        return status;
    }

    public BlockPos getTarget() {
        return target;
    }

    @Override
    public void clearContent() {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 16;
        return lastStressApplied;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server) || !level.dimension().equals(Mantle.LEVEL)) {
            return;
        }
        var ship = Sable.HELPER.getContaining(this);
        if (ship == null || getSpeed() == 0) {
            status = "sampler_idle";
            progress = 0;
            return;
        }
        var origin = ship.logicalPose().transformPosition(Vec3.atCenterOf(worldPosition));
        var direction = ship.logicalPose().transformNormal(
                Vec3.atLowerCornerOf(getBlockState().getValue(ProbeDeviceBlock.FACING).getNormal()));
        BlockPos found = null;
        for (int i = 1; i <= 4; i++) {
            var pos = BlockPos.containing(origin.add(direction.scale(i)));
            if (!level.hasChunkAt(pos)) {
                break;
            }
            var state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                if (state.is(MINEABLE)) {
                    found = pos;
                }
                break;
            }
        }
        if (found == null) {
            status = "sampler_no_target";
            target = null;
            progress = 0;
            return;
        }
        if (!found.equals(target)) {
            target = found;
            progress = 0;
        }
        status = "sampler_working";
        progress += Math.min(256, Math.abs(getSpeed())) / 32;
        if (progress < 20) {
            return;
        }
        var state = level.getBlockState(target);
        var drops = Block.getDrops(state, server, target, null, null, new ItemStack(Items.IRON_PICKAXE));
        var pending = new ItemStackHandler(9);
        pending.deserializeNBT(level.registryAccess(), inventory.serializeNBT(level.registryAccess()));
        for (var stack : drops) {
            if (!ItemHandlerHelper.insertItemStacked(pending, stack, false).isEmpty()) {
                status = "sampler_full";
                return;
            }
        }
        if (level.destroyBlock(target, false)) {
            for (int slot = 0; slot < 9; slot++) {
                inventory.setStackInSlot(slot, pending.getStackInSlot(slot));
            }
            if (ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel serverShip) {
                for (var actor : serverShip.getPlot().getBlockEntityActors()) {
                    if (actor instanceof CabinRegulatorBlockEntity cabin) {
                        cabin.addWorkHeat(1);
                    }
                }
            }
            progress = 0;
        }
    }

    public void interact(Player player) {
        if (!player.isShiftKeyDown()) {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    var remaining = stack.copy();
                    player.getInventory().add(remaining);
                    inventory.extractItem(slot, stack.getCount() - remaining.getCount(), false);
                    player.containerMenu.broadcastChanges();
                    return;
                }
            }
        }
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld." + status), true);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Inventory", inventory.serializeNBT(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
    }
}
