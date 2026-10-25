package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class TeamClientCheck {
    private static final boolean HOST = Boolean.getBoolean("innerworld.teamHostCheck");
    private static int ticks, sequence = -1, inputFrames;
    private static boolean applied, connected, finished, optionsCaptured, oldPause;
    private static CompletableFuture<Void> work, cleanup;
    private static CompletableFuture<Scene> setup;
    private static Scene scene;
    private static RuntimeException failure;

    private static Path control(String name) {return Path.of(System.getProperty("innerworld.teamControlDir"), name);}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if ((!HOST && !Boolean.getBoolean("innerworld.teamGuestCheck")) || finished) return;
        var client = Minecraft.getInstance();
        try {
            if (!optionsCaptured) {oldPause = client.options.pauseOnLostFocus;optionsCaptured = true;client.options.pauseOnLostFocus = false;}
            if (++ticks > 3000) throw new IllegalStateException("Team client timed out");
            if (!HOST && client.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
                throw new IllegalStateException("Team guest disconnected before completing the scene");
            }
            if (!HOST && Files.isRegularFile(control("finish"))) {
                require(Files.readString(control("finish")).equals("verified"), "Host reported a failed team scene");
                Files.writeString(control("guest.result"), "verified");finish(client);return;
            }
            if (cleanup != null) {
                if (!cleanup.isDone()) return;
                cleanup.join();
                if (failure != null) {Files.writeString(control("finish"), "failed");throw failure;}
                Files.writeString(control("finish"), "verified");
                if (Files.isRegularFile(control("guest.result"))) {
                    Files.writeString(control("host.result"), "verified");
                    LogUtils.getLogger().info("Two-client check passed: remote reload, shared cabin and motion, network repair, both boss participants, occupied exit refusal and supply return");
                    finish(client);
                }
                return;
            }
            if (client.level == null || client.player == null || ticks < 80) return;
            input(client);
            if (!HOST) return;
            var server = client.getSingleplayerServer();if (server == null) return;
            if (!connected) {
                connected = true;
                work = server.submit(() -> {
                    try {
                        server.setUsesAuthentication(false);
                        server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), 25568);
                        Files.writeString(control("ready"), "ready");
                    } catch (IOException e) {throw new java.io.UncheckedIOException(e);}
                });return;
            }
            if (work != null) {if (!work.isDone()) return;work.join();work = null;}
            if (setup == null) {
                var hostId = client.player.getUUID();
                work = server.submit(() -> {
                    if (server.getPlayerList().getPlayers().size() == 2) setup = server.submit(() ->
                            new Scene(server.getPlayerList().getPlayer(hostId), server.getPlayerList().getPlayerByName("InnerworldGuest")));
                });return;
            }
            if (!setup.isDone()) return;
            scene = setup.join();
            if (scene.complete) {cleanup = server.submit(scene::cleanUp);return;}
            work = server.submit(scene::advance);
        } catch (Exception e) {
            failure = new IllegalStateException("Team client check failed", e);
            if (!HOST || cleanup != null && cleanup.isDone()) {
                finished = true;client.options.pauseOnLostFocus = oldPause;throw failure;
            }
            var server = client.getSingleplayerServer();
            cleanup = server == null ? CompletableFuture.completedFuture(null) : server.submit(() -> {if (scene != null) scene.cleanUp();});
        }
    }

    private static void input(Minecraft client) throws IOException {
        var data = client.player.getOffhandItem().get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        var tag = data.copyTag();if (!tag.getBoolean("TeamCheck")) return;
        int next = tag.getInt("Sequence");
        if (next != sequence) {sequence = next;inputFrames = 0;applied = false;}
        var action = tag.getString("Action");
        inputFrames++;
        if (applied && action.equals("attack") && inputFrames >= 32) {applied = false;inputFrames = 12;}
        if (applied || inputFrames < 12 || !client.level.dimension().location().toString().equals(tag.getString("Dimension"))) return;
        if (action.equals("attack")) {
            var entity = client.level.getEntity(tag.getInt("Entity"));
            if (!(entity instanceof CoreWarden) || client.player.getAttackStrengthScale(0) < .95f) return;
            client.gameMode.attack(client.player, entity);client.player.swing(InteractionHand.MAIN_HAND);
        } else {
            var pos = BlockPos.of(tag.getLong("Target"));
            if (client.level.getBlockState(pos).isAir()) return;
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        }
        applied = true;Files.writeString(control(HOST ? "host.ack" : "guest.ack"), Integer.toString(sequence));
    }

    private static void finish(Minecraft client) {
        finished = true;client.options.pauseOnLostFocus = oldPause;client.stop();
    }

    private static void require(boolean condition, String message) {if (!condition) throw new IllegalStateException(message);}
    private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, path);}

    private static final class Scene {
        private final ServerPlayer host, guest;
        private final TeamPlayerState oldHost, oldGuest;
        private final boolean hostWon, guestWon;
        private final ProbeTestRig rig;
        private int stage, token;
        private long since;
        private Vec3 hostStart, guestStart;
        private CoreWarden warden;
        private java.util.UUID bossId;
        private CoreConsoleBlockEntity console;
        private CompoundTag originalConsole;
        private final HashMap<BlockPos, BlockState> coreBlocks = new HashMap<>();
        private BlockPos entrance;
        private Direction outward;
        private CoreTransitFixture transit;
        private boolean complete;

        private Scene(ServerPlayer host, ServerPlayer guest) {
            require(guest != null && !host.getUUID().equals(guest.getUUID()), "Two distinct players did not connect");
            this.host = host;this.guest = guest;oldHost = TeamPlayerState.capture(host);oldGuest = TeamPlayerState.capture(guest);
            var advancement = host.server.getAdvancements().get(id("exploration/core_victory"));
            hostWon = host.getAdvancements().getOrStartProgress(advancement).isDone();guestWon = guest.getAdvancements().getOrStartProgress(advancement).isDone();
            rig = new ProbeTestRig(host.server.getLevel(Mantle.LEVEL), new BlockPos(768, 180, 768));rig.combatEquipment();since = host.server.overworld().getGameTime();
        }

        private void advance() {
            long time = host.server.overworld().getGameTime();
            require(time - since < 240, "Team scene stalled at stage " + stage
                    + (warden == null ? "" : "; Warden=" + warden.getHealth() + "; host distance=" + host.distanceToSqr(warden)
                            + "; host=" + host.position() + "; guest=" + guest.position()));
            rig.level.resetEmptyTime();
            switch (stage) {
                case 0 -> {
                    if (!rig.ready()) return;
                    rig.assemble();rig.power();rig.cannon().ammunition.setStackInSlot(0, ItemStack.EMPTY);rig.cabin().addCoolant(240);next(1);
                }
                case 1 -> {
                    if (time - since < 35) return;
                    require(rig.cabin().isSealed(), "Shared cabin is not sealed");
                    board(host, new BlockPos(1, 1, 2));board(guest, new BlockPos(3, 1, 2));
                    signal(guest, "reload", rig.local(ProbeTestRig.CANNON), new ItemStack(AeronauticsInnerworld.PRESSURE_BOLT.get(), 32));next(2);
                }
                case 2 -> {
                    if (rig.cannon().ammunition.getStackInSlot(0).getCount() != 32) return;
                    require(guest.getMainHandItem().isEmpty(), "Remote reload duplicated ammunition");
                    var drive = (ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE));drive.fail();
                    board(host, new BlockPos(2, 1, 5));
                    signal(host, "repair", drive.getBlockPos(), new ItemStack(AeronauticsInnerworld.REPAIR_KIT.get()));next(3);
                }
                case 3 -> {
                    var drive = (ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE));
                    if (drive.isFaulted()) return;
                    require(host.getMainHandItem().isEmpty(), "Network repair did not consume exactly one kit");
                    hostStart = host.position();guestStart = guest.position();
                    rig.motor(ProbeTestRig.LEFT_DRIVE, 64);rig.motor(ProbeTestRig.RIGHT_DRIVE, 64);next(4);
                }
                case 4 -> {
                    if (time - since < 60) return;
                    require(host.getZ() > hostStart.z + .1 && guest.getZ() > guestStart.z + .1, "Two passengers were not carried by the same moving probe");
                    for (var player : java.util.List.of(host, guest)) require(player.getHealth() == 20
                            && player.getPersistentData().getInt("InnerworldHeatExposure") == 0, "A shared sealed cabin failed to protect a passenger");
                    prepareBoss();next(5);
                }
                case 5 -> {
                    if (warden == null) {
                        var entity = host.server.getLevel(Core.LEVEL).getEntity(bossId);
                        if (!(entity instanceof CoreWarden active)) return;
                        warden = active;warden.setNoAi(true);warden.setHealth(8);warden.exposeCore(160);
                        signal(guest, "attack", BlockPos.ZERO, new ItemStack(Items.IRON_SWORD));return;
                    }
                    if (warden.getHealth() >= 8) return;
                    require(warden.isAlive(), "First participant unexpectedly killed the shared target");
                    guest.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);guest.containerMenu.broadcastChanges();
                    signal(host, "attack", BlockPos.ZERO, new ItemStack(Items.DIAMOND_SWORD));next(6);
                }
                case 6 -> {
                    if (warden.isAlive() || console.getBossId() != null || !console.hasVictory()) return;
                    var advancement = host.server.getAdvancements().get(id("exploration/core_victory"));
                    require(host.getAdvancements().getOrStartProgress(advancement).isDone()
                            && guest.getAdvancements().getOrStartProgress(advancement).isDone(), "A real combat participant lost shared story completion");
                    transit = new CoreTransitFixture(host, entrance, outward);
                    guest.teleportTo(host.server.getLevel(Core.LEVEL), entrance.getX() + .5, entrance.getY(), entrance.getZ() + .5, 0, 0);
                    signal(host, "travel", transit.coreStation, ItemStack.EMPTY);next(7);
                }
                case 7 -> {
                    if (!host.level().dimension().equals(Mantle.LEVEL) || time - since < 50) return;
                    transit.verifyCargo(host);
                    var feet = transit.coreStation.relative(outward);
                    guest.teleportTo(host.server.getLevel(Core.LEVEL), feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
                    signal(host, "blocked_return", transit.mantleStation, ItemStack.EMPTY);next(8);
                }
                case 8 -> {
                    if (time - since < 30 || !acknowledged(token)) return;
                    require(host.level().dimension().equals(Mantle.LEVEL), "Supply station placed a player inside another passenger");
                    transit.verifyCargo(host);
                    guest.teleportTo(host.server.getLevel(Core.LEVEL), entrance.getX() + .5, entrance.getY(), entrance.getZ() + .5, 0, 0);
                    signal(host, "return", transit.mantleStation, ItemStack.EMPTY);next(9);
                }
                case 9 -> {
                    if (!host.level().dimension().equals(Core.LEVEL)) return;
                    transit.verifyCargo(host);complete = true;
                }
                default -> throw new IllegalStateException("Unknown team stage");
            }
        }

        private void board(ServerPlayer player, BlockPos local) {
            var pos = rig.world(local).add(0, -.45, 0);
            player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying = false;player.onUpdateAbilities();player.removeAllEffects();
            player.getPersistentData().putInt("InnerworldHeatExposure", 0);player.setHealth(20);player.getFoodData().setFoodLevel(20);
            player.getInventory().clearContent();player.teleportTo(rig.level, pos.x, pos.y, pos.z, 180, 0);player.containerMenu.broadcastChanges();
        }

        private void signal(ServerPlayer player, String action, BlockPos pos, ItemStack held) {
            var tag = new CompoundTag();tag.putBoolean("TeamCheck", true);tag.putInt("Sequence", ++token);tag.putString("Action", action);
            tag.putString("Dimension", player.level().dimension().location().toString());tag.putLong("Target", pos.asLong());
            if (warden != null) tag.putInt("Entity", warden.getId());
            var paper = new ItemStack(Items.PAPER);paper.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            player.setItemInHand(InteractionHand.OFF_HAND, paper);player.setItemInHand(InteractionHand.MAIN_HAND, held);player.containerMenu.broadcastChanges();
        }

        private boolean acknowledged(int token) {
            try {return Files.isRegularFile(control("host.ack")) && Files.readString(control("host.ack")).equals(Integer.toString(token));}
            catch (IOException e) {throw new java.io.UncheckedIOException(e);}
        }

        private void prepareBoss() {
            rig.motor(ProbeTestRig.LEFT_DRIVE, 0);rig.motor(ProbeTestRig.RIGHT_DRIVE, 0);
            var level = host.server.getLevel(Core.LEVEL);
            var found = level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id("core_mazes")), new BlockPos(12288, 33, 12288), 4, false);
            require(found != null, "Team maze could not be located");
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id("core_maze"));
            var start = level.getChunkAt(found).getStartForStructure(structure);var box = start.getBoundingBox();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) level.getChunk(x, z);
            for (var pos : BlockPos.betweenClosed(box.minX(), box.minY() + 1, box.minZ(), box.maxX(), box.minY() + 1, box.maxZ()))
                if (level.getBlockEntity(pos) instanceof CoreConsoleBlockEntity control) console = control;
            require(console != null, "Team maze console missing");originalConsole = console.saveWithFullMetadata(level.registryAccess());
            for (var key : java.util.List.of("Nodes", "Gates", "Valves")) for (long packed : originalConsole.getLongArray(key)) {
                var pos = BlockPos.of(packed);coreBlocks.put(pos, level.getBlockState(pos));
            }
            for (long packed : originalConsole.getLongArray("Nodes")) {
                var pos = BlockPos.of(packed);level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(LeverBlock.POWERED, true));
            }
            var arena = BlockPos.of(originalConsole.getLong("Arena"));
            host.teleportTo(level, arena.getX() + .5, arena.getY(), arena.getZ() + 2.5, 180, 0);
            guest.teleportTo(level, arena.getX() + 2.5, arena.getY(), arena.getZ() + .5, 90, 0);
            if (console.hasVictory()) {
                host.getInventory().setItem(17, new ItemStack(AeronauticsInnerworld.HARMONIC_CRYSTAL.get(), 4));host.setShiftKeyDown(true);
            }
            console.interact(host);host.setShiftKeyDown(false);bossId = console.getBossId();require(bossId != null, "Shared Warden missing");
            var maze = (CoreMazePiece) start.getPieces().getFirst();entrance = maze.getEntrance();outward = maze.getOrientation().getOpposite();
        }

        private void next(int stage) {this.stage = stage;since = host.server.overworld().getGameTime();LogUtils.getLogger().info("Team stage {}", stage);}

        private void cleanUp() {
            if (transit != null) transit.cleanUp();
            if (warden != null) warden.discard();
            if (console != null && originalConsole != null) {
                var level = host.server.getLevel(Core.LEVEL);coreBlocks.forEach(level::setBlockAndUpdate);
                level.getChunkAt(console.getBlockPos());
                if (level.getBlockEntity(console.getBlockPos()) instanceof CoreConsoleBlockEntity current) {
                    current.loadWithComponents(originalConsole, level.registryAccess());current.setChanged();
                }
                var area = new net.minecraft.world.phys.AABB(BlockPos.of(originalConsole.getLong("Arena"))).inflate(16);
                for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area))
                    if (item.getItem().is(AeronauticsInnerworld.CORE_HEART.get())) item.discard();
                for (var orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, area)) orb.discard();
            }
            var advancement = host.server.getAdvancements().get(id("exploration/core_victory"));
            if (!hostWon) host.getAdvancements().revoke(advancement, "defeat");if (!guestWon) guest.getAdvancements().revoke(advancement, "defeat");
            oldHost.restore();oldGuest.restore();rig.cleanUp();
        }
    }
}
