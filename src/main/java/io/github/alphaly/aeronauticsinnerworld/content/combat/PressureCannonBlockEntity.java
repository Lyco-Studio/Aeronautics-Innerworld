package io.github.alphaly.aeronauticsinnerworld.content.combat;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.CabinRegulatorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.List;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemStackHandler;

public final class PressureCannonBlockEntity extends KineticBlockEntity implements Clearable {
    public final ItemStackHandler ammunition = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(AeronauticsInnerworld.PRESSURE_BOLT.get());
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) sendData();
        }
    };
    private int cooldown;

    public PressureCannonBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.PRESSURE_CANNON_ENTITY.get(), pos, state);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 16;
        return lastStressApplied;
    }

    @Override
    public void clearContent() {
        ammunition.setStackInSlot(0, ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (Math.abs(getSpeed()) < 16 || ammunition.getStackInSlot(0).isEmpty()
                || !(Sable.HELPER.getContaining(this) instanceof ServerSubLevel ship)) {
            return;
        }
        var direction = ship.logicalPose().transformNormal(Vec3.atLowerCornerOf(getBlockState().getValue(ProbeDeviceBlock.FACING).getNormal()));
        var muzzle = ship.logicalPose().transformPosition(Vec3.atCenterOf(worldPosition)).add(direction.scale(.8));
        var projectile = AeronauticsInnerworld.PRESSURE_BOLT_ENTITY.get().create(level);
        if (projectile == null) {
            return;
        }
        projectile.setPos(muzzle);
        projectile.shoot(direction.x, direction.y, direction.z, 1.5f, 0);
        var box = ship.getPlot().getBoundingBox();
        var crewSpace = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        var owner = level.getNearestPlayer(muzzle.x, muzzle.y, muzzle.z, 12,
                player -> !player.isSpectator() && crewSpace.contains(
                        ship.logicalPose().transformPositionInverse(player.getBoundingBox().getCenter())));
        if (owner != null) {
            projectile.setOwner(owner);
        }
        if (level.addFreshEntity(projectile)) {
            ammunition.extractItem(0, 1, false);
            cooldown = Math.max(10, 640 / Math.round(Math.abs(getSpeed())));
            for (var actor : ship.getPlot().getBlockEntityActors()) {
                if (actor instanceof CabinRegulatorBlockEntity cabin) {
                    cabin.addWorkHeat(1.5f);
                }
            }
            level.playSound(null, BlockPos.containing(muzzle), SoundEvents.CROSSBOW_SHOOT, SoundSource.BLOCKS, .8f, .7f);
        }
    }

    public void reload(Player player, ItemStack stack) {
        var remaining = ammunition.insertItem(0, stack.copy(), false);
        int used = stack.getCount() - remaining.getCount();
        if (!player.getAbilities().instabuild) {
            stack.shrink(used);
        }
        showStatus(player);
    }

    public void showStatus(Player player) {
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.cannon_status",
                ammunition.getStackInSlot(0).getCount(), Math.round(Math.abs(getSpeed()))), true);
    }

    public void interact(Player player) {
        if (player.isShiftKeyDown()) {
            var stack = ammunition.getStackInSlot(0);
            var remaining = stack.copy();
            player.getInventory().add(remaining);
            ammunition.extractItem(0, stack.getCount() - remaining.getCount(), false);
            player.containerMenu.broadcastChanges();
            showStatus(player);
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        super.addToGoggleTooltip(tooltip, sneaking);
        int count = ammunition.getStackInSlot(0).getCount();
        String state = Sable.HELPER.getContaining(this) == null ? "cannon_unassembled"
                : Math.abs(getSpeed()) < 16 ? "cannon_unpowered" : count == 0 ? "cannon_empty" : "cannon_ready";
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("    ").append(Component.translatable("goggle.aeronautics_innerworld.cannon"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("    ").append(Component.translatable("goggle.aeronautics_innerworld.ammunition", count))
                .withStyle(count == 0 ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        tooltip.add(Component.literal("    ").append(Component.translatable("goggle.aeronautics_innerworld." + state)));
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Ammunition", ammunition.serializeNBT(registries));
        tag.putInt("ShotCooldown", Math.max(0, cooldown));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        ammunition.deserializeNBT(registries, tag.getCompound("Ammunition"));
        cooldown = net.minecraft.util.Mth.clamp(tag.getInt("ShotCooldown"), 0, 40);
    }
}
