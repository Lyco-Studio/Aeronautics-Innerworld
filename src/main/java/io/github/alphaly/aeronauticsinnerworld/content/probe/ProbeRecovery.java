package io.github.alphaly.aeronauticsinnerworld.content.probe;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

@EventBusSubscriber(modid = AeronauticsInnerworld.MOD_ID)
public final class ProbeRecovery {
    private static final String DEATH_TARGET = "InnerworldProbeDeath";

    public static boolean contains(SubLevel ship, Player player) {
        var box = ship.getPlot().getBoundingBox();
        return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)
                .contains(ship.logicalPose().transformPositionInverse(player.getBoundingBox().getCenter()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void recordDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.remove(DEATH_TARGET);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        if (!player.level().dimension().equals(Mantle.LEVEL)) return;
        for (var ship : SubLevelContainer.getContainer(player.level()).getAllSubLevels()) {
            if (!(ship instanceof ServerSubLevel serverShip) || !contains(ship, player)) continue;
            boolean probe = false;
            for (var actor : serverShip.getPlot().getBlockEntityActors()) {
                if (actor instanceof CabinRegulatorBlockEntity) {probe = true;break;}
            }
            if (!probe) continue;
            var local = ship.logicalPose().transformPositionInverse(player.position());
            var target = new CompoundTag();
            target.putUUID("Ship", ship.getUniqueId());
            target.putLong("Death", player.blockPosition().asLong());
            target.putDouble("X", local.x);
            target.putDouble("Y", local.y);
            target.putDouble("Z", local.z);
            persisted.put(DEATH_TARGET, target);
            return;
        }
    }

    public static CompoundTag deathTarget(Player player) {
        var target = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getCompound(DEATH_TARGET);
        var death = player.getLastDeathLocation();
        return target.hasUUID("Ship") && death.isPresent() && death.get().dimension().equals(Mantle.LEVEL)
                && target.getLong("Death") == death.get().pos().asLong() ? target.copy() : null;
    }
}
