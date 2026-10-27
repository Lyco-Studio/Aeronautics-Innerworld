package io.github.alphaly.aeronauticsinnerworld.content.probe;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public final class ProbeServiceItem extends Item {
    private final int cooling;

    public ProbeServiceItem(Properties properties, int cooling) {
        super(properties);
        this.cooling = cooling;
    }

    @Override
    public InteractionResult onItemUseFirst(net.minecraft.world.item.ItemStack stack, UseOnContext context) {
        return useOn(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var entity = level.getBlockEntity(pos);
        if (cooling > 0 && !(entity instanceof CabinRegulatorBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (cooling == 0 && !(entity instanceof ProbeDriveBlockEntity)
                && !level.getBlockState(pos).is(AeronauticsInnerworld.DAMAGED_HULL.get())) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        String status;
        boolean used = false;
        if (cooling > 0) {
            used = ((CabinRegulatorBlockEntity) entity).addCoolant(cooling);
            status = used ? "coolant_loaded" : "coolant_full";
        } else {
            boolean hot = false;
            var ship = Sable.HELPER.getContaining(level, pos);
            if (ship instanceof ServerSubLevel serverShip) {
                for (var actor : serverShip.getPlot().getBlockEntityActors()) {
                    if (actor instanceof CabinRegulatorBlockEntity cabin && cabin.getHeat() > 70) {
                        hot = true;
                        break;
                    }
                }
            }
            if (hot) {
                status = "repair_hot";
            } else if (entity instanceof ProbeDriveBlockEntity drive) {
                used = drive.isFaulted();
                if (used) {
                    drive.repair();
                }
                status = used ? "repair_done" : "repair_unneeded";
            } else {
                used = level.setBlockAndUpdate(pos, AeronauticsInnerworld.REFRACTORY_HULL.get().defaultBlockState());
                status = used ? "repair_done" : "repair_unneeded";
            }
        }
        if (used && (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild)) {
            context.getItemInHand().shrink(1);
        }
        if (used && cooling > 0) {
            var ship = Sable.HELPER.getContaining(level, pos);
            var soundPos = ship == null ? pos : BlockPos.containing(ship.logicalPose().transformPosition(Vec3.atCenterOf(pos)));
            level.playSound(null, soundPos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, .6f, .85f);
        }
        if (context.getPlayer() != null) {
            var message = cooling > 0
                    ? Component.translatable("message.aeronautics_innerworld." + status,
                            ((CabinRegulatorBlockEntity) entity).getCoolant(), cooling)
                    : Component.translatable("message.aeronautics_innerworld." + status);
            context.getPlayer().displayClientMessage(message.withStyle(used ? ChatFormatting.AQUA : ChatFormatting.GOLD), true);
        }
        return InteractionResult.SUCCESS;
    }
}
