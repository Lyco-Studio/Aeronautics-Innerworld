package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

public final class MantleSamplerBlockEntity extends KineticBlockEntity implements Clearable {
    private static final int REACH = 6;
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
    private java.util.UUID specimen;
    private String status = "sampler_idle";
    private int cargoCount;
    private int cargoSlots;

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
        workTick(server);
        if (level.getGameTime() % 10 == 0) sendData();
    }

    private void workTick(ServerLevel server) {
        var ship = Sable.HELPER.getContaining(this);
        if (ship == null || getSpeed() == 0) {
            status = "sampler_idle";
            progress = 0;
            target = null;
            specimen = null;
            return;
        }
        var origin = ship.logicalPose().transformPosition(Vec3.atCenterOf(worldPosition));
        var direction = ship.logicalPose().transformNormal(
                Vec3.atLowerCornerOf(getBlockState().getValue(ProbeDeviceBlock.FACING).getNormal()));
        BlockPos found = null;
        int clearDistance = REACH;
        for (int i = 1; i <= REACH; i++) {
            var pos = BlockPos.containing(origin.add(direction.scale(i)));
            if (!level.hasChunkAt(pos)) {
                clearDistance = i - 1;
                break;
            }
            var state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                clearDistance = i - 1;
                if (state.is(MINEABLE)) {
                    found = pos;
                }
                break;
            }
        }
        if (found == null) {
            if (!collectItem(server, origin, direction, clearDistance) && !sampleCreature(server, origin, direction, clearDistance)) {
                status = "sampler_no_target";
                target = null;
                progress = 0;
                specimen = null;
            }
            return;
        }
        if (specimen != null || !found.equals(target)) {
            target = found;
            progress = 0;
        }
        specimen = null;
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
            addWorkHeat();
            progress = 0;
        }
    }

    private boolean collectItem(ServerLevel server, Vec3 origin, Vec3 direction, int reach) {
        if (reach < 1) return false;
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (var item : server.getEntitiesOfClass(ItemEntity.class,
                new AABB(origin, origin.add(direction.scale(reach))).inflate(1.25))) {
            var center = item.getBoundingBox().getCenter();
            double along = center.subtract(origin).dot(direction);
            if (!item.isAlive() || item.getItem().isEmpty() || item.hasPickUpDelay() || along < .75 || along > reach || along >= nearestDistance
                    || center.distanceToSqr(origin.add(direction.scale(along))) > 1) continue;
            var hit = server.clip(new ClipContext(origin.add(direction.scale(.51)), center,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, item));
            if (hit.getType() != HitResult.Type.MISS) continue;
            nearest = item;nearestDistance = along;
        }
        if (nearest == null) return false;
        target = nearest.blockPosition();specimen = null;progress = 0;
        var stack = nearest.getItem();
        var remaining = ItemHandlerHelper.insertItemStacked(inventory, stack.copy(), false);
        if (remaining.getCount() == stack.getCount()) {
            status = "sampler_full";
            return true;
        }
        if (remaining.isEmpty()) nearest.discard();
        else nearest.setItem(remaining);
        status = "sampler_salvaging";
        addWorkHeat();
        return true;
    }

    private boolean sampleCreature(ServerLevel server, Vec3 origin, Vec3 direction, int reach) {
        if (reach < 1) {
            return false;
        }
        LavaStalker nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (var creature : server.getEntitiesOfClass(LavaStalker.class,
                new AABB(origin, origin.add(direction.scale(reach))).inflate(1.25))) {
            var center = creature.getBoundingBox().getCenter();
            double along = center.subtract(origin).dot(direction);
            if (creature.isNeutral() && creature.isAlive() && creature.getTarget() == null && along >= .5 && along <= reach
                    && center.distanceToSqr(origin.add(direction.scale(along))) <= 1 && along < nearestDistance) {
                nearest = creature;
                nearestDistance = along;
            }
        }
        if (nearest == null) {
            return false;
        }
        if (nearest.getSampleCooldown() > 0) {
            status = "sampler_grazer_wait";
            target = null;
            progress = 0;
            specimen = null;
            return true;
        }
        if (!nearest.getUUID().equals(specimen)) {
            specimen = nearest.getUUID();
            progress = 0;
        }
        target = nearest.blockPosition();
        status = "sampler_grazer_working";
        progress += Math.min(256, Math.abs(getSpeed())) / 32;
        if (progress < 20) {
            return true;
        }
        var pending = new ItemStackHandler(9);
        pending.deserializeNBT(level.registryAccess(), inventory.serializeNBT(level.registryAccess()));
        if (!ItemHandlerHelper.insertItemStacked(pending, new ItemStack(AeronauticsInnerworld.RAW_HARMONIC.get()), false).isEmpty()) {
            status = "sampler_full";
            return true;
        }
        if (!nearest.takeSample().isEmpty()) {
            for (int slot = 0; slot < 9; slot++) {
                inventory.setStackInSlot(slot, pending.getStackInSlot(slot));
            }
            progress = 0;
            addWorkHeat();
        }
        return true;
    }

    private void addWorkHeat() {
        if (Sable.HELPER.getContaining(this) instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel ship) {
            for (var actor : ship.getPlot().getBlockEntityActors()) {
                if (actor instanceof CabinRegulatorBlockEntity cabin) {
                    cabin.addWorkHeat(1);
                }
            }
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
                    sendData();
                    return;
                }
            }
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        super.addToGoggleTooltip(tooltip, sneaking);
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("    ").append(Component.translatable("goggle.aeronautics_innerworld.sampler"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("    ").append(Component.translatable("message.aeronautics_innerworld." + status))
                .withStyle(status.equals("sampler_full") ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        if (status.equals("sampler_working") || status.equals("sampler_grazer_working")) {
            tooltip.add(Component.literal("    ").append(Component.translatable("goggle.aeronautics_innerworld.sample_progress",
                    Math.min(100, Math.round(progress * 5)))).withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.literal("    ").append(Component.translatable("goggle.aeronautics_innerworld.cargo", cargoCount, cargoSlots)));
        if (target != null) tooltip.add(Component.literal("    ").append(Component.translatable("goggle.aeronautics_innerworld.coordinates",
                target.getX(), target.getY(), target.getZ())));
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!clientPacket) tag.put("Inventory", inventory.serializeNBT(registries));
        else {
            int count = 0, slots = 0;
            for (int i = 0; i < inventory.getSlots(); i++) {
                var stack = inventory.getStackInSlot(i);
                if (!stack.isEmpty()) {count += stack.getCount();slots++;}
            }
            tag.putInt("CargoCount", count);
            tag.putInt("CargoSlots", slots);
            tag.putString("SamplerStatus", status);
            tag.putFloat("SampleProgress", progress);
            if (target != null) tag.putLong("SampleTarget", target.asLong());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (!clientPacket) inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        else {
            cargoCount = tag.getInt("CargoCount");
            cargoSlots = tag.getInt("CargoSlots");
            status = tag.getString("SamplerStatus");
            progress = tag.getFloat("SampleProgress");
            target = tag.contains("SampleTarget") ? BlockPos.of(tag.getLong("SampleTarget")) : null;
        }
    }
}
