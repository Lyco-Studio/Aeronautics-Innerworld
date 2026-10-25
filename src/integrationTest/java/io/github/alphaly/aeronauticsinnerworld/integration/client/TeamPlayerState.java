package io.github.alphaly.aeronauticsinnerworld.integration.client;

import java.util.List;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

record TeamPlayerState(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode,
        boolean flying, float health, ListTag inventory, List<MobEffectInstance> effects, boolean hadHeat, int heat,
        int portalCooldown, int food, float saturation) {
    static TeamPlayerState capture(ServerPlayer player) {
        return new TeamPlayerState(player, player.serverLevel(), player.position(), player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer(), player.getAbilities().flying, player.getHealth(),
                player.getInventory().save(new ListTag()), player.getActiveEffects().stream().map(MobEffectInstance::new).toList(),
                player.getPersistentData().contains("InnerworldHeatExposure"), player.getPersistentData().getInt("InnerworldHeatExposure"),
                player.getPortalCooldown(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel());
    }

    void restore() {
        player.teleportTo(level, position.x, position.y, position.z, yaw, pitch);player.setGameMode(mode);
        player.getAbilities().flying = flying;player.onUpdateAbilities();player.getInventory().load(inventory);
        player.setHealth(health);player.removeAllEffects();effects.forEach(effect -> player.addEffect(new MobEffectInstance(effect)));
        if (hadHeat) player.getPersistentData().putInt("InnerworldHeatExposure", heat);else player.getPersistentData().remove("InnerworldHeatExposure");
        player.setPortalCooldown(portalCooldown);player.getFoodData().setFoodLevel(food);player.getFoodData().setSaturation(saturation);
        player.clearFire();player.containerMenu.broadcastChanges();
    }
}
