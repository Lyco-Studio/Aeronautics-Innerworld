package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import dev.ryanhcode.sable.util.BoundedBitVolume3i;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class CabinRegulatorBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor {
    private WaterOcclusionRegion region;
    private int ticks;
    private int volume;
    private Set<BlockPos> lastInterior = Set.of();
    private float heat;
    private float workHeat;
    private int coolant;
    private int coolantFraction;
    private int hotSeconds;
    private BlockPos damagedOffset;
    private int thermalTicks;

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
        boolean wasSealed = region != null;
        if (Math.abs(getSpeed()) < 16 || (region != null && region.isDirty())) {
            clearRegion();
        }
        if (region == null && Math.abs(getSpeed()) >= 16 && ticks++ % 10 == 0) {
            buildRegion();
        }
        if (!level.isClientSide) {
            if (wasSealed && !isSealed() && inLava() && consumeCoolantTicks(200)) {
                for (var player : level.players()) {
                    if (contains(player)) {
                        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 0, false, false, true));
                        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.cabin_emergency"), true);
                    }
                }
            }
            if (++thermalTicks % 20 == 0) {
                tickHeat();
            }
        }
    }

    private boolean inLava() {
        var ship = Sable.HELPER.getContaining(this);
        return ship != null && level.getFluidState(BlockPos.containing(ship.logicalPose()
                .transformPosition(Vec3.atCenterOf(worldPosition)))).is(FluidTags.LAVA);
    }

    private boolean contains(Player player) {
        var ship = Sable.HELPER.getContaining(this);
        return ship != null && lastInterior.contains(BlockPos.containing(ship.logicalPose()
                .transformPositionInverse(player.getBoundingBox().getCenter())));
    }

    private void tickHeat() {
        if (!(Sable.HELPER.getContaining(this) instanceof ServerSubLevel ship)) {
            return;
        }
        float previous = heat;
        float work = workHeat;
        workHeat = 0;
        if (!inLava()) {
            heat += work - 3;
        } else {
            float load = 0;
            for (var actor : ship.getPlot().getBlockEntityActors()) {
                if (actor instanceof ProbeDriveBlockEntity drive && !drive.isFaulted()) {
                    load += Math.min(1, Math.abs(drive.getSpeed()) / 64) * .2f;
                }
            }
            heat += work + (isSealed() ? .7f + load : 8);
            if (Math.abs(getSpeed()) >= 16 && consumeCoolantTicks(20)) {
                heat -= 2;
            }
        }
        heat = net.minecraft.util.Mth.clamp(heat, 0, 100);
        if (previous != heat || work != 0) {
            setChanged();
        }
        if (isSealed() && getCoolant() > 0) {
            for (var player : level.players()) {
                if (contains(player)) {
                    player.clearFire();
                }
            }
        }
        if (heat >= 100 && ++hotSeconds >= 10) {
            hotSeconds = 0;
            for (var actor : ship.getPlot().getBlockEntityActors()) {
                if (actor instanceof ProbeDriveBlockEntity drive && !drive.isFaulted() && drive.getSpeed() != 0) {
                    drive.fail();
                    break;
                }
            }
            damageHull();
            setChanged();
        } else if (heat < 80) {
            hotSeconds = 0;
        }
        if (heat >= 60) {
            for (var player : level.players()) {
                if (contains(player)) {
                    player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.cabin_hot",
                            Math.round(heat), getCoolant()), true);
                }
            }
        }
    }

    private void damageHull() {
        if (damagedOffset != null) {
            var pos = worldPosition.offset(damagedOffset);
            if (level.getBlockState(pos).is(AeronauticsInnerworld.DAMAGED_HULL.get())) {
                level.destroyBlock(pos, false);
                damagedOffset = null;
                return;
            }
            damagedOffset = null;
        }
        var plate = lastInterior.stream().flatMap(pos -> java.util.Arrays.stream(Direction.values()).map(pos::relative))
                .filter(pos -> level.getBlockState(pos).is(AeronauticsInnerworld.REFRACTORY_HULL.get()))
                .min(java.util.Comparator.comparingLong(BlockPos::asLong));
        plate.ifPresent(pos -> {
            level.setBlockAndUpdate(pos, AeronauticsInnerworld.DAMAGED_HULL.get().defaultBlockState());
            damagedOffset = pos.subtract(worldPosition);
        });
    }

    public float getHeat() {
        return net.minecraft.util.Mth.clamp(heat + workHeat, 0, 100);
    }

    public void addWorkHeat(float amount) {
        workHeat = Math.max(0, workHeat + amount);
        setChanged();
    }

    public void recordHullDamage(BlockPos pos) {
        damagedOffset = pos.subtract(worldPosition);
        setChanged();
    }

    public int getCoolant() {
        return coolant + (coolantFraction > 0 ? 1 : 0);
    }

    public boolean consumeCoolantTicks(int amount) {
        // Twentieths preserve fractional engine debits alongside the existing whole-unit saves.
        int available = coolant * 20 + coolantFraction;
        if (amount <= 0 || available < amount) return false;
        int remaining = available - amount;
        coolant = remaining / 20;
        coolantFraction = remaining % 20;
        setChanged();
        return true;
    }

    public BlockPos getDamagedPlate() {
        if (damagedOffset == null) {
            return null;
        }
        var pos = worldPosition.offset(damagedOffset);
        var state = level.getBlockState(pos);
        return state.is(AeronauticsInnerworld.DAMAGED_HULL.get()) || !Block.isShapeFullBlock(state.getCollisionShape(level, pos))
                ? pos : null;
    }

    public boolean addCoolant(int units) {
        if (units <= 0 || (coolant + units) * 20 + coolantFraction > 12000) {
            return false;
        }
        coolant += units;
        setChanged();
        return true;
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
            lastInterior = Set.copyOf(interior);
        }
    }

    public boolean isSealed() {
        return region != null && !region.isDirty();
    }

    public void showStatus(Player player) {
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.cabin_status",
                Component.translatable("message.aeronautics_innerworld."
                        + (isSealed() ? "cabin_sealed" : Math.abs(getSpeed()) < 16 ? "cabin_unpowered" : "cabin_open")),
                volume, Math.round(getHeat()), getCoolant()), true);
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

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Heat", heat);
        tag.putFloat("WorkHeat", workHeat);
        tag.putInt("Coolant", coolant);
        tag.putInt("CoolantFraction", coolantFraction);
        tag.putInt("HotSeconds", hotSeconds);
        if (damagedOffset != null) {
            tag.putLong("DamagedPlate", damagedOffset.asLong());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        heat = net.minecraft.util.Mth.clamp(tag.getFloat("Heat"), 0, 100);
        workHeat = Math.max(0, tag.getFloat("WorkHeat"));
        coolant = net.minecraft.util.Mth.clamp(tag.getInt("Coolant"), 0, 600);
        coolantFraction = coolant == 600 ? 0 : net.minecraft.util.Mth.clamp(tag.getInt("CoolantFraction"), 0, 19);
        hotSeconds = net.minecraft.util.Mth.clamp(tag.getInt("HotSeconds"), 0, 10);
        damagedOffset = tag.contains("DamagedPlate") ? BlockPos.of(tag.getLong("DamagedPlate")) : null;
    }
}
