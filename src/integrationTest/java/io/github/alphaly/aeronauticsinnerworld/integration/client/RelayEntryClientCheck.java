package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.structure.MantleRelayPiece;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class RelayEntryClientCheck {
    private static int ticks, phase = -1, frames;
    private static boolean capturedOptions, oldPause, clicked, finished, returning;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> step, cleanup;
    private static Scene scene;
    private static RuntimeException failure;

    private static boolean voyage() {return Boolean.getBoolean("innerworld.relayVoyage") || Boolean.getBoolean("innerworld.relayTransfer");}
    private static boolean transfer() {return voyage() && Boolean.getBoolean("innerworld.relayTransfer");}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.relayEntryClientCheck") || finished) return;
        var client = Minecraft.getInstance();
        if (!capturedOptions) {oldPause = client.options.pauseOnLostFocus;capturedOptions = true;client.options.pauseOnLostFocus = false;}
        if (client.screen instanceof PauseScreen) client.setScreen(null);
        try {
            if (++ticks > (voyage() ? 24000 : 1800) && cleanup == null) throw new IllegalStateException("Relay intake timed out at phase " + phase);
            if (cleanup != null) {
                if (!cleanup.isDone()) return;
                cleanup.join();finished = true;client.options.pauseOnLostFocus = oldPause;
                if (failure != null) throw failure;
                Files.writeString(client.gameDirectory.toPath().resolve("relay-entry-check.result"), voyage() ? transfer() ? "transfer-verified" : "voyage-verified" : "verified");
                LogUtils.getLogger().info(transfer() ? "Relay transfer passed: native boat exit, first alloy recovery and reboarding"
                        : voyage() ? "Natural relay voyage passed: fueled native controls, gallery recovery and dry outpost return"
                        : "Relay intake check passed: native swim and first alloy recovery with ordinary fire resistance");
                client.stop();return;
            }
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null || ticks < 100) return;
            var server = client.getSingleplayerServer();
            if (setup == null) {var id = client.player.getUUID();setup = server.submit(() -> new Scene(server.getPlayerList().getPlayer(id)));return;}
            if (!setup.isDone()) return;
            scene = setup.join();
            if (scene.phase != phase) {release(client);phase = scene.phase;frames = 0;clicked = false;}
            frames++;
            if (phase == 0 || phase == 5) scene.expedition.tickClient(client);
            if (phase == 1) {
                var offset = Vec3.atBottomCenterOf(scene.landing).add(0, .5, 0).subtract(client.player.position());
                client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));client.player.setXRot(-25);
                client.options.keyUp.setDown(offset.horizontalDistanceSqr() > .02);client.options.keyJump.setDown(offset.y > .05);
            }
            if ((phase == 2 || phase == 3) && scene.walk != null) scene.walk.tickClient(client);
            if (phase == 3 && client.player.containerMenu instanceof ChestMenu chest && frames % 5 == 0) {
                for (int slot = 0; slot < chest.getRowCount() * 9; slot++) {
                    if (chest.getSlot(slot).getItem().is(AeronauticsInnerworld.RELIC_ALLOY.get())) {
                        client.gameMode.handleInventoryMouseClick(chest.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);
                        LogUtils.getLogger().info("Native first alloy transfer requested: menu={}, slot={}", chest.containerId, slot);
                        break;
                    }
                }
            }
            if (phase == 4) {
                if (!clicked) {client.player.closeContainer();clicked = true;}
                if (frames == 8) Screenshot.grab(client.gameDirectory, "relay-first-recovery.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("First relay recovery screenshot: {}", message.getString()));
                if (frames > 15) {
                    if (voyage()) {
                        if (!returning) {returning = true;step = server.submit(scene::beginReturn);}
                    } else cleanup = server.submit(scene::cleanUp);
                    return;
                }
            }
            if (phase == 6) {
                if (frames == 8) Screenshot.grab(client.gameDirectory, transfer() ? "relay-transfer-return.png" : "relay-natural-voyage.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Natural relay cargo return screenshot: {}", message.getString()));
                if (frames > 15) {cleanup = server.submit(scene::cleanUp);return;}
            }
            if (step != null) {if (!step.isDone()) return;step.join();step = null;}
            step = server.submit(scene::advance);
        } catch (Exception error) {
            release(client);client.setScreen(null);
            if (finished || cleanup != null && cleanup.isDone()) {
                finished = true;client.options.pauseOnLostFocus = oldPause;throw new IllegalStateException("Relay intake check failed", error);
            }
            failure = new IllegalStateException("Relay intake check failed", error);
            cleanup = client.getSingleplayerServer().submit(() -> {if (scene != null) scene.cleanUp();});
        }
    }

    private static void release(Minecraft client) {
        client.options.keyUp.setDown(false);client.options.keyJump.setDown(false);client.options.keyShift.setDown(false);
        client.options.keyUse.setDown(false);
        if (client.player != null && client.gameMode != null && client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
    }

    private static final class Scene {
        private final ServerPlayer player;
        private final TeamPlayerState oldPlayer;
        private final ServerLevel level;
        private final boolean oldSpawning;
        private final int oldSlot;
        private final BoundingBox bounds;
        private final BlockPos landing, hatch, chest;
        private final Map<BlockPos, BlockState> hatches = new HashMap<>();
        private final Map<BlockPos, CompoundTag> containers = new HashMap<>();
        private final Set<String> earned = new HashSet<>();
        private final int alloy;
        private volatile int phase = 1;
        private volatile CoreMazeWalk walk;
        private final long started;
        private RelayVoyage expedition;

        private Scene(ServerPlayer player) {
            this.player = player;oldPlayer = TeamPlayerState.capture(player);oldSlot = player.getInventory().selected;
            level = player.server.getLevel(Mantle.LEVEL);
            oldSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            var progression = player.server.getAdvancements().get(id("expedition/relay"));
            player.getAdvancements().getOrStartProgress(progression).getCompletedCriteria().forEach(earned::add);
            var located = level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id("mantle_relays")),
                    new BlockPos(-2048, 175, -2048), 4, false);
            if (located == null) throw new IllegalStateException("No natural relay near the intake check region");
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id("mantle_relay"));
            var start = level.getChunkAt(located).getStartForStructure(structure);
            if (start == null || !start.isValid() || !(start.getPieces().getFirst() instanceof MantleRelayPiece relay))
                throw new IllegalStateException("Natural relay has no intake piece");
            bounds = start.getBoundingBox();
            for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) level.getChunk(x, z);
            var intake = relay.getEntrance();
            BlockPos dry = null, door = null, supplies = null;
            double distance = Double.MAX_VALUE;
            int recovered = 0;
            for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                var state = level.getBlockState(pos);
                if (pos.getY() == intake.getY() + 3 && state.isAir() && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                        && pos.distSqr(intake) < distance) {dry = pos.immutable();distance = pos.distSqr(intake);}
                if (state.is(AeronauticsInnerworld.PRESSURE_HATCH.get())) {
                    hatches.put(pos.immutable(), state);
                    if (door == null || pos.getY() < door.getY()) door = pos.immutable();
                }
                if (level.getBlockEntity(pos) instanceof ChestBlockEntity box) {
                    containers.put(pos.immutable(), box.saveWithFullMetadata(level.registryAccess()));
                    int count = count(box);
                    if (supplies == null && count > 0) {supplies = pos.immutable();recovered = count;}
                }
            }
            if (dry == null || door == null || supplies == null || !level.getFluidState(intake).is(FluidTags.LAVA)) {
                containers.forEach((pos, tag) -> level.getBlockEntity(pos).loadWithComponents(tag, level.registryAccess()));
                throw new IllegalStateException("Natural relay is missing its wet approach, dry landing or first alloy supplies");
            }
            landing = dry;hatch = door;chest = supplies;alloy = recovered;
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, player.server);
            player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying = false;player.onUpdateAbilities();
            player.getInventory().clearContent();player.getInventory().selected = 2;
            player.removeAllEffects();
            if (!voyage()) player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 3600));
            player.setHealth(player.getMaxHealth());
            var food = new CompoundTag();new net.minecraft.world.food.FoodData().addAdditionalSaveData(food);player.getFoodData().readAdditionalSaveData(food);
            player.getPersistentData().putInt("InnerworldHeatExposure", 0);
            var point = Vec3.atBottomCenterOf(intake);
            if (voyage()) {
                try {
                    expedition = new RelayVoyage(player, level, intake, landing, bounds);expedition.build();
                    player.getInventory().setItem(0, new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get(), 8));
                    player.getInventory().setItem(1, new ItemStack(Items.COOKED_BEEF, 8));
                    player.getInventory().setItem(5, new ItemStack(Items.COMPASS));
                    player.getInventory().setItem(6, new ItemStack(AeronauticsInnerworld.MOLTEN_SCALE.get(), 3));
                    player.getInventory().setItem(7, new ItemStack(com.simibubi.create.AllItems.COPPER_SHEET.get(), 2));
                    player.getInventory().setItem(8, new ItemStack(Items.GLASS));
                    for (int slot : new int[] {3, 4}) {
                        var potion = new ItemStack(Items.POTION);potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS, new PotionContents(Potions.LONG_FIRE_RESISTANCE));
                        player.getInventory().setItem(slot, potion);
                    }
                    point = expedition.initialPosition();phase = 0;
                } catch (RuntimeException error) {cleanUp();throw error;}
            }
            player.teleportTo(level, point.x, point.y, point.z, 0, -25);player.containerMenu.broadcastChanges();
            started = level.getGameTime();
            LogUtils.getLogger().info("Natural relay intake starts at {}, dry landing={}, first alloy={}", intake, landing, alloy);
        }

        private void advance() {
            level.resetEmptyTime();
            if (level.getGameTime() - started > (voyage() ? 18000 : 1200) || !voyage() && player.getHealth() < 6 || !player.isAlive())
                throw new IllegalStateException("First relay approach failed at phase " + phase + ", position=" + player.position()
                        + ", heat=" + player.getPersistentData().getInt("InnerworldHeatExposure") + ", health=" + player.getHealth()
                        + ", alloy=" + player.getInventory().countItem(AeronauticsInnerworld.RELIC_ALLOY.get()) + "/" + alloy
                        + ", chest=" + count((ChestBlockEntity) level.getBlockEntity(chest)) + ", menu=" + player.containerMenu.getClass().getSimpleName());
            if (!player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) throw new IllegalStateException("First alloy recovery used late expedition armor");
            if (phase == 0 || phase == 5) {
                expedition.advance();
                if (phase == 0 && expedition.atRelay()) phase = 1;
                else if (phase == 5 && expedition.returned()) {
                    if (player.getInventory().countItem(AeronauticsInnerworld.RELIC_ALLOY.get()) != alloy)
                        throw new IllegalStateException("Natural voyage lost its recovered alloy before reaching the outpost");
                    phase = 6;
                }
                return;
            }
            if (phase == 1 && player.getY() >= landing.getY() && player.position().subtract(Vec3.atBottomCenterOf(landing)).horizontalDistanceSqr() < .3
                    && level.getFluidState(BlockPos.containing(player.getEyePosition())).isEmpty()) {
                LogUtils.getLogger().info("Native first relay landing reached: heat={}, health={}",
                        player.getPersistentData().getInt("InnerworldHeatExposure"), player.getHealth());
                walk = new CoreMazeWalk(level, bounds, player.blockPosition(), hatch);phase = 2;
            } else if (phase == 2 || phase == 3) {
                walk.advance(player.position());
                if (phase == 2 && level.getBlockState(hatch).getValue(PressureHatchBlock.OPEN)) {
                    walk = new CoreMazeWalk(level, bounds, player.blockPosition(), chest);phase = 3;
                } else if (phase == 3 && player.getInventory().countItem(AeronauticsInnerworld.RELIC_ALLOY.get()) == alloy) {
                    if (count((ChestBlockEntity) level.getBlockEntity(chest)) != 0)
                        throw new IllegalStateException("First alloy recovery duplicated the chest contents");
                    LogUtils.getLogger().info("Native first relay recovery completed: alloy={}, early mask={}, health={}", alloy,
                            player.getItemBySlot(EquipmentSlot.HEAD).is(AeronauticsInnerworld.HEATWEAVE_HELMET.get()), player.getHealth());phase = 4;
                }
            }
        }

        private static int count(ChestBlockEntity chest) {
            int count = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(AeronauticsInnerworld.RELIC_ALLOY.get())) count += chest.getItem(slot).getCount();
            return count;
        }

        private void beginReturn() {expedition.beginReturn();phase = 5;}

        private void cleanUp() {
            if (expedition != null) expedition.cleanUp();
            player.closeContainer();hatches.forEach(level::setBlockAndUpdate);
            containers.forEach((pos, tag) -> {
                var entity = level.getBlockEntity(pos);entity.loadWithComponents(tag, level.registryAccess());entity.setChanged();
            });
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(oldSpawning, player.server);
            var progression = player.server.getAdvancements().get(id("expedition/relay"));
            var completed = new HashSet<String>();
            player.getAdvancements().getOrStartProgress(progression).getCompletedCriteria().forEach(completed::add);
            for (var criterion : completed)
                if (!earned.contains(criterion)) player.getAdvancements().revoke(progression, criterion);
            oldPlayer.restore();player.getInventory().selected = oldSlot;
            player.connection.send(new ClientboundSetCarriedItemPacket(oldSlot));
        }

        private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, path);}
    }
}
