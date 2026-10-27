package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class ProbeClientCheck {
    private static int ticks;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Boolean> sample;
    private static CompletableFuture<Void> cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static boolean finished;
    private static boolean capturedPause, oldPause;
    private static boolean sawCabinFog, sawExteriorFog;

    @SubscribeEvent
    public static void fog(ViewportEvent.RenderFog event) {
        if (!Boolean.getBoolean("innerworld.probeClientCheck") || scene == null || cleanup != null
                || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;
        if (scene.ticks > 100 && scene.ticks < 165) {
            if (event.getType() != FogType.NONE || event.getFarPlaneDistance() <= 5) {
                failure = new IllegalStateException("Dry cabin retained liquid fog on rendered parts");
            } else sawCabinFog = true;
        } else if (scene.ticks > 232 && scene.ticks < 240) {
            if (event.getType() != FogType.LAVA || event.getFarPlaneDistance() > 5) {
                failure = new IllegalStateException("Exterior camera lost its lava fog");
            } else sawExteriorFog = true;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.probeClientCheck") || finished) {
            return;
        }
        var client = Minecraft.getInstance();
        if (Boolean.getBoolean("innerworld.machineArtReview")) {
            MachineArtReview.tick(client);
            return;
        }
        if (!capturedPause) {oldPause = client.options.pauseOnLostFocus;capturedPause = true;client.options.pauseOnLostFocus = false;}
        if (client.screen instanceof PauseScreen) client.setScreen(null);
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
                try {cleanup.join();} finally {client.options.pauseOnLostFocus = oldPause;client.options.save();}
                if (failure != null) {
                    throw failure;
                }
                try {
                    Files.writeString(client.gameDirectory.toPath().resolve("probe-check.result"), "verified");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Cannot write probe check result", e);
                }
                LogUtils.getLogger().info("Probe client check passed: native assembly, survival cabin, moving passenger, cabin/exterior fog, lava camera and models");
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
                        AeronauticsInnerworld.CABIN_REGULATOR.get(), AeronauticsInnerworld.ECHO_SOUNDER.get(),
                        AeronauticsInnerworld.MANTLE_SAMPLER.get(), AeronauticsInnerworld.HARMONIC_ORE.get(),
                        AeronauticsInnerworld.DAMAGED_HULL.get(), AeronauticsInnerworld.EMERGENCY_PATCH.get())) {
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
                if (scene.ticks == 182) {
                    Screenshot.grab(client.gameDirectory, "mantle-probe-service-check.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("Probe service screenshot: {}", message.getString()));
                }
                if (finished) {
                    if (!sawCabinFog || !sawExteriorFog) {
                        throw new IllegalStateException("Probe check did not render both cabin and exterior fog");
                    }
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
        private final java.util.List<ItemStack> oldInventory;
        private final java.util.List<MobEffectInstance> oldEffects;
        private final int oldExposure;
        private final boolean hadExposure;
        private final int oldRawCount;
        private final ProbeTestRig rig;
        private int ticks;
        private double startZ;
        private BlockPos ore;
        private float testHealth;

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
            oldInventory = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                    .mapToObj(slot -> player.getInventory().getItem(slot).copy()).toList();
            oldEffects = player.getActiveEffects().stream().map(MobEffectInstance::new).toList();
            hadExposure = player.getPersistentData().contains("InnerworldHeatExposure");
            oldExposure = player.getPersistentData().getInt("InnerworldHeatExposure");
            oldRawCount = player.getInventory().countItem(AeronauticsInnerworld.RAW_HARMONIC.get());
            var mantle = player.server.getLevel(Mantle.LEVEL);
            rig = new ProbeTestRig(mantle, new BlockPos(128, 170, 128));
            rig.workEquipment();
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
                rig.motor(ProbeTestRig.SAMPLER, 0);
            }
            if (ticks == 40) {
                if (!rig.cabin().isSealed()) {
                    throw new IllegalStateException("Cabin was not sealed before boarding");
                }
                var feet = rig.subLevel.logicalPose().transformPosition(
                        Vec3.atLowerCornerOf(rig.local(new BlockPos(1, 1, 2))).add(.5, .05, .5));
                player.teleportTo(rig.level, feet.x, feet.y, feet.z, 180, 0);
                player.setGameMode(GameType.SURVIVAL);
                player.removeAllEffects();
                testHealth = player.getMaxHealth();
                player.setHealth(testHealth);
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                startZ = rig.subLevel.logicalPose().position().z();
                use(new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get(), 2), rig.local(ProbeTestRig.CABIN), Direction.UP);
                if (rig.cabin().getCoolant() != 120 || player.getMainHandItem().getCount() != 1) {
                    throw new IllegalStateException("Real player coolant loading failed");
                }
                ore = rig.mineralTarget();
                rig.sounder().interact(player);
                rig.motor(ProbeTestRig.SAMPLER, 64);
            }
            if (ticks > 45) {
                if ((ticks < 190 || (ticks > 215 && ticks < 230))
                        && (player.isInLava() || player.isOnFire() || player.getHealth() < testHealth)) {
                    throw new IllegalStateException("Survival player was exposed to lava inside sealed cabin");
                }
                var local = rig.subLevel.logicalPose().transformPositionInverse(player.position());
                var relative = local.subtract(Vec3.atLowerCornerOf(rig.local(BlockPos.ZERO)));
                if ((ticks < 230 || ticks > 242) && (relative.x < .5 || relative.x > 4.5 || relative.y < .5 || relative.y > 3.5
                        || relative.z < .5 || relative.z > 6.5)) {
                    throw new IllegalStateException("Moving hull did not retain its passenger: " + relative);
                }
            }
            if (ticks == 65) {
                if (rig.level.getBlockState(ore).is(AeronauticsInnerworld.HARMONIC_ORE.get()) || sampleCount() != 1) {
                    throw new IllegalStateException("Occupied probe failed to collect its mineral sample: " + rig.sampler().getStatus());
                }
                rig.motor(ProbeTestRig.SAMPLER, 0);
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
            if (ticks == 180) {
                ((ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE))).fail();
                rig.level.setBlockAndUpdate(rig.local(new BlockPos(0, 1, 2)), AeronauticsInnerworld.DAMAGED_HULL.get().defaultBlockState());
                rig.sounder().interact(player);
            }
            if (ticks == 185) {
                use(new ItemStack(AeronauticsInnerworld.REPAIR_KIT.get(), 3), rig.local(ProbeTestRig.LEFT_DRIVE), Direction.UP);
                use(player.getMainHandItem(), rig.local(new BlockPos(0, 1, 2)), Direction.UP);
                if (player.getMainHandItem().getCount() != 1
                        || ((ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE))).isFaulted()
                        || !rig.level.getBlockState(rig.local(new BlockPos(0, 1, 2))).is(AeronauticsInnerworld.REFRACTORY_HULL.get())) {
                    throw new IllegalStateException("Real player mechanical and hull repair failed");
                }
            }
            if (ticks == 190) {
                rig.level.setBlockAndUpdate(rig.local(new BlockPos(0, 1, 2)), Blocks.AIR.defaultBlockState());
            }
            if (ticks == 193) {
                if (rig.cabin().isSealed() || !player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
                    throw new IllegalStateException("Hull breach did not trigger emergency spray");
                }
                use(new ItemStack(AeronauticsInnerworld.EMERGENCY_PATCH.get(), 2), rig.local(new BlockPos(0, 0, 2)), Direction.UP);
                if (player.getMainHandItem().getCount() != 1) {
                    throw new IllegalStateException("Real player emergency patch did not consume one plate");
                }
            }
            if (ticks == 215 && (!rig.cabin().isSealed() || player.isInLava() || player.isOnFire() || player.getHealth() < testHealth)) {
                throw new IllegalStateException("Patched survival cabin did not recover safely");
            }
            if (ticks == 230) {
                player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 0, false, false, true));
                player.getPersistentData().putInt("InnerworldHeatExposure", 4);
                var outside = rig.world(new BlockPos(7, 1, 3));
                player.teleportTo(rig.level, outside.x, outside.y, outside.z, 180, 0);
            }
            if (ticks == 242) {
                if (!player.hasEffect(MobEffects.FIRE_RESISTANCE) || player.getHealth() >= testHealth) {
                    throw new IllegalStateException("Fire resistance bypassed mantle heat exposure");
                }
                var inside = rig.subLevel.logicalPose().transformPosition(
                        Vec3.atLowerCornerOf(rig.local(new BlockPos(1, 1, 2))).add(.5, .05, .5));
                player.teleportTo(rig.level, inside.x, inside.y, inside.z, 180, 0);
            }
            if (ticks == 250) {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                rig.sampler().interact(player);
                rig.sampler().interact(player);
                if (sampleCount() != 0 || player.getInventory().countItem(AeronauticsInnerworld.RAW_HARMONIC.get()) != oldRawCount + 1) {
                    throw new IllegalStateException("Real player sample unloading lost or duplicated cargo");
                }
            }
            return ticks >= 265;
        }

        private int sampleCount() {
            int count = 0;
            for (int slot = 0; slot < 9; slot++) {
                var stack = rig.sampler().inventory.getStackInSlot(slot);
                if (stack.is(AeronauticsInnerworld.RAW_HARMONIC.get())) {
                    count += stack.getCount();
                }
            }
            return count;
        }

        private void use(ItemStack item, BlockPos pos, Direction face) {
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            player.gameMode.useItemOn(player, rig.level, item, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false));
        }

        private void cleanUp() {
            player.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch);
            player.setGameMode(oldGameMode);
            player.getAbilities().flying = oldFlying;
            player.onUpdateAbilities();
            player.setHealth(oldHealth);
            player.clearFire();
            player.setPortalCooldown(oldCooldown);
            for (int slot = 0; slot < oldInventory.size(); slot++) {
                player.getInventory().setItem(slot, oldInventory.get(slot));
            }
            player.removeAllEffects();
            oldEffects.forEach(player::addEffect);
            if (hadExposure) {
                player.getPersistentData().putInt("InnerworldHeatExposure", oldExposure);
            } else {
                player.getPersistentData().remove("InnerworldHeatExposure");
            }
            player.containerMenu.broadcastChanges();
            rig.cleanUp();
        }
    }
}
