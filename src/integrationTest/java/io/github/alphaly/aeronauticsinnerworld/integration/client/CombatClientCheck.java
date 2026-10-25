package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.client.LavaStalkerRenderer;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class CombatClientCheck {
    private static int ticks;
    private static int armorFrames;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Boolean> step;
    private static CompletableFuture<Void> cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static boolean reloaded;
    private static boolean checkedModels;
    private static boolean checkedArmor;
    private static int outlineFrames;
    private static boolean capturedEnemy;
    private static boolean checkedGrazer;
    private static boolean finished;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.combatClientCheck") || finished) {
            return;
        }
        var client = Minecraft.getInstance();
        try {
            if (++ticks > 1800 && cleanup == null) {
                throw new IllegalStateException("Combat client check timed out");
            }
            if (cleanup != null) {
                if (cleanup.isDone()) {
                    cleanup.join();
                    finished = true;
                    if (failure != null) {
                        throw failure;
                    }
                    if (!capturedEnemy) {
                        throw new IllegalStateException("Combat target was never visible through its sonar outline");
                    }
                    Files.writeString(client.gameDirectory.toPath().resolve("combat-check.result"), "verified");
                    LogUtils.getLogger().info("Combat client check passed: network reload, mounted cannon, creature renderer, heatweave equipment and bounded heat protection");
                    client.stop();
                }
                return;
            }
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null || ticks < 100) {
                return;
            }
            if (setup == null) {
                var uuid = client.player.getUUID();
                setup = client.getSingleplayerServer().submit(() -> new Scene(client.getSingleplayerServer().getPlayerList().getPlayer(uuid)));
                return;
            }
            if (!setup.isDone()) {
                return;
            }
            scene = setup.join();
            if (!checkedModels && scene.ticks >= 45) {
                BookLayoutChecks.check(client, scene.manual, 18);
                if (client.getBlockRenderer().getBlockModel(AeronauticsInnerworld.PRESSURE_CANNON.get().defaultBlockState())
                        == client.getModelManager().getMissingModel()) {
                    throw new IllegalStateException("Pressure cannon model is missing");
                }
                var predator = client.level.getEntity(scene.predator.getId());
                if (!(predator instanceof LavaStalker stalker)
                        || !(client.getEntityRenderDispatcher().getRenderer(stalker) instanceof LavaStalkerRenderer)) {
                    throw new IllegalStateException("Lava stalker has no synchronized creature renderer");
                }
                checkedModels = true;
            }
            if (scene.ticks >= 45 && !reloaded && client.player.getMainHandItem().is(AeronauticsInnerworld.PRESSURE_BOLT.get())) {
                var pos = scene.rig.local(ProbeTestRig.CANNON);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                reloaded = true;
            }
            if (scene.ticks >= 50 && !capturedEnemy && scene.predator != null
                    && client.level.getEntity(scene.predator.getId()) instanceof LavaStalker stalker
                    && stalker.isCurrentlyGlowing() && ++outlineFrames >= 3) {
                Screenshot.grab(client.gameDirectory, "mantle-probe-cannon.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Cannon encounter screenshot: {}", message.getString()));
                capturedEnemy = true;
            }
            if (!checkedGrazer && scene.grazer != null && scene.grazer.isAlive()
                    && client.level.getEntity(scene.grazer.getId()) instanceof LavaStalker grazer) {
                var renderer = client.getEntityRenderDispatcher().getRenderer(grazer);
                if (!grazer.isNeutral() || !(renderer instanceof LavaStalkerRenderer)
                        || !renderer.getTextureLocation(grazer).getPath().endsWith("mantle_grazer.png")) {
                    throw new IllegalStateException("Neutral grazer did not retain its distinct identity and skin");
                }
                checkedGrazer = true;
            }
            if (scene.ticks == 180 && !checkedArmor) {
                if (++armorFrames == 1) {
                    var material = AeronauticsInnerworld.HEATWEAVE.get();
                    if (client.getResourceManager().getResource(material.layers().getFirst().texture(false)).isEmpty()) {
                        throw new IllegalStateException("Heatweave armor texture is missing");
                    }
                    client.setScreen(new InventoryScreen(client.player));
                }
                if (armorFrames >= 6) {
                    Screenshot.grab(client.gameDirectory, "mantle-heatweave-armor.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("Heatweave equipment screenshot: {}", message.getString()));
                    client.setScreen(null);
                    checkedArmor = true;
                }
                return;
            }
            if (step == null) {
                step = client.getSingleplayerServer().submit(scene::tick);
            } else if (step.isDone()) {
                boolean done = step.join();
                step = null;
                if (done) {
                    cleanup = client.getSingleplayerServer().submit(scene::cleanUp);
                }
            }
        } catch (Exception e) {
            if (finished || cleanup != null && cleanup.isDone()) {
                finished = true;
                throw new IllegalStateException("Combat client check failed", e);
            }
            failure = new IllegalStateException("Combat client check failed", e);
            client.setScreen(null);
            cleanup = client.getSingleplayerServer().submit(() -> {
                if (scene != null) {
                    scene.cleanUp();
                }
            });
        }
    }

    private static final class Scene {
        private final ServerPlayer player;
        private final net.minecraft.server.level.ServerLevel oldLevel;
        private final Vec3 oldPosition;
        private final float oldYaw;
        private final float oldPitch;
        private final GameType oldMode;
        private final boolean oldFlying;
        private final float oldHealth;
        private final int oldPortalCooldown;
        private final List<ItemStack> oldInventory;
        private final List<MobEffectInstance> oldEffects;
        private final boolean hadExposure;
        private final int oldExposure;
        private final ProbeTestRig rig;
        private final ItemStack manual;
        private LavaStalker predator;
        private LavaStalker grazer;
        private int grazerSince;
        private boolean provoked;
        private boolean grazerRetaliated;
        private int ticks;
        private boolean killed;
        private float protectedHealth;

        private Scene(ServerPlayer player) {
            this.player = player;
            oldLevel = player.serverLevel();
            oldPosition = player.position();
            oldYaw = player.getYRot();
            oldPitch = player.getXRot();
            oldMode = player.gameMode.getGameModeForPlayer();
            oldFlying = player.getAbilities().flying;
            oldHealth = player.getHealth();
            oldPortalCooldown = player.getPortalCooldown();
            oldInventory = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                    .mapToObj(slot -> player.getInventory().getItem(slot).copy()).toList();
            oldEffects = player.getActiveEffects().stream().map(MobEffectInstance::new).toList();
            hadExposure = player.getPersistentData().contains("InnerworldHeatExposure");
            oldExposure = player.getPersistentData().getInt("InnerworldHeatExposure");
            var level = player.server.getLevel(Mantle.LEVEL);
            rig = new ProbeTestRig(level, new BlockPos(512, 180, 128));
            rig.combatEquipment();
            var input = CraftingInput.of(2, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.COPPER_INGOT)));
            manual = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow()
                    .value().assemble(input, level.registryAccess());
        }

        private boolean tick() {
            rig.level.resetEmptyTime();
            if (rig.subLevel == null && !rig.ready()) {
                return false;
            }
            if (++ticks == 1) {
                rig.assemble();
                rig.power();
                rig.motor(ProbeTestRig.CANNON, 0);
                rig.cabin().addCoolant(240);
            }
            if (ticks == 40) {
                if (!rig.cabin().isSealed()) {
                    throw new IllegalStateException("Combat pilot has no sealed cabin");
                }
                var position = rig.world(new BlockPos(1, 1, 2)).add(0, -.45, 0);
                player.teleportTo(rig.level, position.x, position.y, position.z, 180, 8);
                player.setGameMode(GameType.SURVIVAL);
                player.removeAllEffects();
                player.getPersistentData().putInt("InnerworldHeatExposure", 0);
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                player.setHealth(player.getMaxHealth());
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AeronauticsInnerworld.PRESSURE_BOLT.get(), 32));
                player.containerMenu.broadcastChanges();
                predator = AeronauticsInnerworld.LAVA_STALKER.get().create(rig.level);
                predator.setNoAi(true);
                predator.setNoGravity(true);
                predator.addTag("innerworld_combat_client_check");
                positionTarget();
                rig.level.addFreshEntity(predator);
            }
            if (predator != null && predator.isAlive()) {
                positionTarget();
            }
            if (ticks == 55) {
                if (!reloaded || rig.cannon().ammunition.getStackInSlot(0).getCount() != 41) {
                    throw new IllegalStateException("Actual client reload lost or duplicated pressure bolts");
                }
                if (rig.sounder().getThreat() != predator || !predator.hasEffect(MobEffects.GLOWING)) {
                    throw new IllegalStateException("Powered sounder did not mark its nearby combat target");
                }
                rig.motor(ProbeTestRig.CANNON, 32);
            }
            if (ticks > 55 && !killed && predator.isDeadOrDying()) {
                killed = true;
                rig.motor(ProbeTestRig.CANNON, 0);
                predator.discard();
            }
            if (ticks == 160 && !killed) {
                throw new IllegalStateException("Occupied probe did not defeat its target");
            }
            if (killed && ticks >= 120 && grazer == null) {
                grazer = AeronauticsInnerworld.MANTLE_GRAZER.get().create(rig.level);
                var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
                grazer.setPos(rig.world(ProbeTestRig.CANNON).add(direction.scale(3)).add(0, -.5, 0));
                grazer.setNoGravity(true);
                grazerSince = ticks;
                rig.level.addFreshEntity(grazer);
            }
            if (grazer != null && !grazerRetaliated && ticks - grazerSince >= 20 && !provoked) {
                if (grazer.getTarget() != null || !checkedGrazer) {
                    throw new IllegalStateException("Grazer attacked a nearby crew without warning or failed to synchronize");
                }
                rig.motor(ProbeTestRig.CANNON, 32);
                provoked = true;
            }
            if (provoked && !grazerRetaliated && grazer.getTarget() != null) {
                if (grazer.getTarget() != player) {
                    throw new IllegalStateException("Crew-fired pressure bolt attributed the retaliation to the wrong entity");
                }
                grazerRetaliated = true;
                rig.motor(ProbeTestRig.CANNON, 0);
                grazer.discard();
            }
            if (ticks == 175) {
                player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(AeronauticsInnerworld.HEATWEAVE_HELMET.get()));
                player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(AeronauticsInnerworld.HEATWEAVE_CHESTPLATE.get()));
                player.containerMenu.broadcastChanges();
            }
            if (ticks == 200) {
                if (!grazerRetaliated) {
                    throw new IllegalStateException("Provoked grazer did not retaliate against its actual shooter");
                }
                protectedHealth = player.getHealth();
                var outside = rig.world(new BlockPos(-6, 1, 2));
                player.teleportTo(rig.level, outside.x, outside.y, outside.z, 180, 0);
            }
            if (ticks == 265) {
                if (player.getHealth() < protectedHealth || player.getPersistentData().getInt("InnerworldHeatExposure") < 5) {
                    throw new IllegalStateException("Heatweave suit failed to extend the unprotected exposure window");
                }
                player.getPersistentData().putInt("InnerworldHeatExposure", 19);
            }
            if (ticks == 280) {
                if (player.getHealth() >= protectedHealth) {
                    throw new IllegalStateException("Heatweave suit granted unlimited mantle heat immunity");
                }
                var inside = rig.world(new BlockPos(1, 1, 2)).add(0, -.45, 0);
                player.teleportTo(rig.level, inside.x, inside.y, inside.z, 180, 0);
                player.getPersistentData().putInt("InnerworldHeatExposure", 0);
                player.clearFire();
            }
            return ticks >= 290;
        }

        private void positionTarget() {
            var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
            predator.setPos(rig.world(ProbeTestRig.CANNON).add(direction.scale(8)).add(0, -.35, 0));
            predator.setDeltaMovement(Vec3.ZERO);
        }

        private void cleanUp() {
            if (predator != null) {
                predator.discard();
            }
            if (grazer != null) {
                grazer.discard();
            }
            rig.cleanUp();
            player.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch);
            player.setGameMode(oldMode);
            player.getAbilities().flying = oldFlying;
            player.onUpdateAbilities();
            player.removeAllEffects();
            oldEffects.forEach(player::addEffect);
            for (int slot = 0; slot < oldInventory.size(); slot++) {
                player.getInventory().setItem(slot, oldInventory.get(slot));
            }
            player.setHealth(oldHealth);
            player.setPortalCooldown(oldPortalCooldown);
            player.clearFire();
            if (hadExposure) {
                player.getPersistentData().putInt("InnerworldHeatExposure", oldExposure);
            } else {
                player.getPersistentData().remove("InnerworldHeatExposure");
            }
            player.containerMenu.broadcastChanges();
        }
    }
}
