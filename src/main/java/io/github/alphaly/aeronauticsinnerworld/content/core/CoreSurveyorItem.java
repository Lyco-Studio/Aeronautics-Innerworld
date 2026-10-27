package io.github.alphaly.aeronauticsinnerworld.content.core;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class CoreSurveyorItem extends Item {
    public CoreSurveyorItem(Properties properties) {super(properties);}

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CoreConsoleBlockEntity console)) {
            return InteractionResult.PASS;
        }
        if (context.getPlayer() instanceof ServerPlayer player && !player.getCooldowns().isOnCooldown(this)) {
            console.showStatus(player);
            player.getCooldowns().addCooldown(this,20);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) return InteractionResultHolder.success(stack);
        if (!level.dimension().equals(Core.LEVEL)) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.core_survey_wrong_layer"), false);
            return InteractionResultHolder.fail(stack);
        }
        var tag = TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_mazes"));
        var found = server.findNearestMapStructure(tag, player.blockPosition(), 4, false);
        if (found == null) player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.core_survey_missing"), false);
        else {
            var structure = server.registryAccess().registryOrThrow(Registries.STRUCTURE).get(
                    ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_maze"));
            var start = server.getChunkAt(found).getStartForStructure(structure);
            var entrance = start != null && !start.getPieces().isEmpty() && start.getPieces().getFirst() instanceof CoreMazePiece maze
                    ? maze.getEntrance() : found;
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.core_survey_found",
                    entrance.getX(), entrance.getY(), entrance.getZ()), false);
        }
        player.getCooldowns().addCooldown(this, 100);
        return InteractionResultHolder.success(stack);
    }
}
