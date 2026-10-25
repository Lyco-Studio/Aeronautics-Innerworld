package io.github.alphaly.aeronauticsinnerworld.content.drill;

import java.util.List;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class DrillRemoteItem extends Item {
    private static final String BINDING = "InnerworldController";

    public DrillRemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        if (level.getBlockEntity(context.getClickedPos()) instanceof DrillControllerBlockEntity) {
            if (!level.isClientSide) {
                var data = context.getItemInHand().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                data.put(BINDING, GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE,
                        GlobalPos.of(level.dimension(), context.getClickedPos())).getOrThrow());
                context.getItemInHand().set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                message(context.getPlayer(), "remote_bound");
            }
            return InteractionResult.SUCCESS;
        }
        return use(level, context.getPlayer(), context.getHand()).getResult();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            var owner = binding(stack);
            var source = owner == null ? null : serverLevel.getServer().getLevel(owner.dimension());
            if (source == null) {
                message(player, "remote_unbound");
            } else {
                source.resetEmptyTime();
                source.getChunkSource().addRegionTicket(DrillLayerTravel.CHUNK_TICKET, new ChunkPos(owner.pos()), 2, owner.pos());
                source.getChunkAt(owner.pos());
                if (source.getBlockEntity(owner.pos()) instanceof DrillControllerBlockEntity controller) {
                    if (player.isShiftKeyDown()) {
                        if (!controller.cycleDirection()) {
                            message(player, "pause_before_turning");
                            return InteractionResultHolder.success(stack);
                        }
                    } else {
                        controller.toggleWork();
                    }
                    player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.controller_status",
                            Component.translatable("direction.aeronautics_innerworld." + controller.getMovementDirection().getName()),
                            Component.translatable("status.aeronautics_innerworld." + controller.getStatus())), true);
                } else {
                    message(player, "remote_missing");
                }
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var owner = binding(stack);
        lines.add(Component.translatable("item.aeronautics_innerworld.drill_remote.controls"));
        if (owner != null) {
            lines.add(Component.translatable("item.aeronautics_innerworld.drill_remote.binding",
                    owner.dimension().location().toString(), owner.pos().getX(), owner.pos().getY(), owner.pos().getZ()));
        }
    }

    private static GlobalPos binding(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return GlobalPos.CODEC.parse(NbtOps.INSTANCE, data.getCompound(BINDING)).result().orElse(null);
    }

    private static void message(Player player, String key) {
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld." + key), true);
        }
    }
}
