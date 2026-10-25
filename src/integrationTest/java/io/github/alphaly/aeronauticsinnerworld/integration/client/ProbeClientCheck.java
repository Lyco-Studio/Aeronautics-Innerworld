package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class ProbeClientCheck {
    private static int ticks;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Boolean> sample;
    private static CompletableFuture<Void> cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static boolean finished;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.probeClientCheck") || finished) {
            return;
        }
        var client = Minecraft.getInstance();
        if (++ticks > 1800 && failure == null && cleanup == null) {
            failure = new IllegalStateException("Probe client check timed out");
        }
        if (failure != null && cleanup == null) {
            cleanup = client.getSingleplayerServer().submit(() -> {
                if (scene != null) {
                    scene.cleanUp();
                }
            });
        }
        if (cleanup != null) {
            if (cleanup.isDone()) {
                cleanup.join();
                if (failure != null) {
                    throw failure;
                }
                try {
                    Files.writeString(client.gameDirectory.toPath().resolve("probe-check.result"), "verified");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Cannot write probe check result", e);
                }
                LogUtils.getLogger().info("Probe client check passed: native assembly, survival cabin, moving passenger, lava camera and models");
                finished = true;
                client.stop();
            }
            return;
        }
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null || ticks < 100) {
            return;
        }
        try {
            if (setup == null) {
                for (var block : java.util.List.of(AeronauticsInnerworld.REFRACTORY_HULL.get(),
                        AeronauticsInnerworld.MANTLE_VIEWPORT.get(), AeronauticsInnerworld.PRESSURE_HATCH.get(),
                        AeronauticsInnerworld.MOLTEN_THRUSTER.get(), AeronauticsInnerworld.MANTLE_BALLAST.get(),
                        AeronauticsInnerworld.CABIN_REGULATOR.get())) {
                    for (var state : block.getStateDefinition().getPossibleStates()) {
                        if (client.getBlockRenderer().getBlockModel(state) == client.getModelManager().getMissingModel()) {
                            throw new IllegalStateException("Missing probe model: " + state);
                        }
                    }
                }
                var uuid = client.player.getUUID();
                setup = client.getSingleplayerServer().submit(() -> new Scene(
                        client.getSingleplayerServer().getPlayerList().getPlayer(uuid)));
                return;
            }
            if (!setup.isDone()) {
                return;
            }
            scene = setup.join();
            if (sample == null) {
                sample = client.getSingleplayerServer().submit(scene::tick);
            } else if (sample.isDone()) {
                boolean finished = sample.join();
                sample = null;
                if (finished) {
                    if (!WaterOcclusionContainer.getContainer(client.level).isOccluded(client.player.getEyePosition())) {
                        throw new IllegalStateException("Client cabin did not occlude lava at the player's eyes");
                    }
                    if (client.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
                        throw new IllegalStateException("Sealed cabin retained lava camera fog");
                    }
                    Screenshot.grab(client.gameDirectory, "mantle-probe-cabin-check.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("Probe screenshot: {}", message.getString()));
                    cleanup = client.getSingleplayerServer().submit(scene::cleanUp);
                }
            }
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private static final class Scene {
        private final ServerPlayer player;
        private final ServerLevel oldLevel;
        private final Vec3 oldPosition;
        private final float oldYaw;
        private final float oldPitch;
        private final GameType oldGameMode;
        private final boolean oldFlying;
        private final float oldHealth;
        private final int oldCooldown;
        private final ProbeTestRig rig;
        private int ticks;
        private double startZ;

        private Scene(ServerPlayer player) {
            this.player = player;
            oldLevel = player.serverLevel();
            oldPosition = player.position();
            oldYaw = player.getYRot();
            oldPitch = player.getXRot();
            oldGameMode = player.gameMode.getGameModeForPlayer();
            oldFlying = player.getAbilities().flying;
            oldHealth = player.getHealth();
            oldCooldown = player.getPortalCooldown();
            var mantle = player.server.getLevel(Mantle.LEVEL);
            rig = new ProbeTestRig(mantle, new BlockPos(128, 170, 128));
        }

        private boolean tick() {
            rig.level.resetEmptyTime();
            if (rig.subLevel == null && !rig.ready()) {
                return false;
            }
            ticks++;
            if (ticks == 1) {
                rig.assemble();
                rig.power();
            }
            if (ticks == 40) {
                if (!rig.cabin().isSealed()) {
                    throw new IllegalStateException("Cabin was not sealed before boarding");
                }
                var feet = rig.subLevel.logicalPose().transformPosition(
                        Vec3.atLowerCornerOf(rig.local(new BlockPos(1, 1, 2))).add(.5, .05, .5));
                player.teleportTo(rig.level, feet.x, feet.y, feet.z, 180, 0);
                player.setGameMode(GameType.SURVIVAL);
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                startZ = rig.subLevel.logicalPose().position().z();
            }
            if (ticks > 45) {
                if (player.isInLava() || player.isOnFire() || player.getHealth() < oldHealth) {
                    throw new IllegalStateException("Survival player was exposed to lava inside sealed cabin");
                }
                var local = rig.subLevel.logicalPose().transformPositionInverse(player.position());
                var relative = local.subtract(Vec3.atLowerCornerOf(rig.local(BlockPos.ZERO)));
                if (relative.x < .5 || relative.x > 4.5 || relative.y < .5 || relative.y > 3.5
                        || relative.z < .5 || relative.z > 6.5) {
                    throw new IllegalStateException("Moving hull did not retain its passenger: " + relative);
                }
            }
            if (ticks == 65) {
                rig.motor(ProbeTestRig.LEFT_DRIVE, 64);
                rig.motor(ProbeTestRig.RIGHT_DRIVE, 64);
            }
            if (ticks == 110) {
                if (rig.subLevel.logicalPose().position().z() < startZ + .3) {
                    throw new IllegalStateException("Occupied probe did not move forward");
                }
                rig.motor(ProbeTestRig.LEFT_DRIVE, -64);
                rig.motor(ProbeTestRig.RIGHT_DRIVE, -64);
            }
            if (ticks == 170) {
                if (RigidBodyHandle.of(rig.subLevel).getLinearVelocity().z() >= 0) {
                    throw new IllegalStateException("Occupied probe did not reverse");
                }
                rig.motor(ProbeTestRig.LEFT_DRIVE, 0);
                rig.motor(ProbeTestRig.RIGHT_DRIVE, 0);
            }
            return ticks >= 195;
        }

        private void cleanUp() {
            player.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch);
            player.setGameMode(oldGameMode);
            player.getAbilities().flying = oldFlying;
            player.onUpdateAbilities();
            player.setHealth(oldHealth);
            player.clearFire();
            player.setPortalCooldown(oldCooldown);
            rig.cleanUp();
        }
    }
}
