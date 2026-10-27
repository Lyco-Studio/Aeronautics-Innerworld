package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_core_feedback")
@PrefixGameTestTemplate(false)
public final class CoreFeedbackChecks {
    @GameTest(template = "empty", timeoutTicks = 450)
    public static void inspectsWithoutStartingAndRestoresQueuedCooling(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Core.LEVEL);
        var origin = new BlockPos(2048,64,2048);
        var forced = new HashMap<ChunkPos,Boolean>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var chunk = new ChunkPos((origin.getX() >> 4) + x, (origin.getZ() >> 4) + z);
            forced.put(chunk,level.getForcedChunks().contains(chunk.toLong()));
            level.getChunk(chunk.x,chunk.z);level.setChunkForced(chunk.x,chunk.z,true);
        }
        for (var pos : BlockPos.betweenClosed(origin.offset(-4,-1,-4),origin.offset(8,-1,8))) {
            level.setBlockAndUpdate(pos,Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        level.setBlockAndUpdate(origin,AeronauticsInnerworld.CORE_CONSOLE.get().defaultBlockState());
        var console = (CoreConsoleBlockEntity) level.getBlockEntity(origin);
        var nodes = new BlockPos[] {origin.west(2),origin.north(2),origin.west(2).north(2)};
        var valves = new BlockPos[] {origin.offset(5,0,4),origin.offset(4,0,5)};
        var lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE,AttachFace.FLOOR);
        for (var pos : nodes) level.setBlockAndUpdate(pos,lever);
        for (var pos : valves) level.setBlockAndUpdate(pos,lever);
        var gates = new ArrayList<BlockPos>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            var pos = origin.offset(x,y,3);gates.add(pos);
            level.setBlockAndUpdate(pos,AeronauticsInnerworld.CORE_SEAL.get().defaultBlockState());
        }
        console.configure(java.util.Arrays.stream(nodes).mapToLong(BlockPos::asLong).toArray(),
                gates.stream().mapToLong(BlockPos::asLong).toArray(),java.util.Arrays.stream(valves).mapToLong(BlockPos::asLong).toArray(),origin.offset(4,0,4));
        var messages = new ArrayList<Component>();
        var player = new FakePlayer(level,new GameProfile(UUID.fromString("08099b5f-e85e-4bc2-bd49-8cd8a2751bb5"),"CoreInspector")) {
            @Override
            public void displayClientMessage(Component text, boolean actionBar) {messages.add(text);}
        };
        player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(origin.north(2)));
        var hit = new BlockHitResult(Vec3.atCenterOf(origin),Direction.UP,origin,false);
        var active = new CoreWarden[1];
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) {cleanUp();}
            public void testFailed(GameTestInfo test, GameTestRunner runner) {cleanUp();}
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
            private void cleanUp() {
                for (var entity : level.getEntities(null,new AABB(origin).inflate(16))) {
                    if (entity instanceof CoreWarden || entity instanceof net.minecraft.world.entity.item.ItemEntity
                            || entity instanceof net.minecraft.world.entity.ExperienceOrb) entity.discard();
                }
                for (var pos : BlockPos.betweenClosed(origin.offset(-4,-1,-4),origin.offset(8,3,8))) level.removeBlock(pos,false);
                player.discard();forced.forEach((chunk,wasForced) -> {if (!wasForced) level.setChunkForced(chunk.x,chunk.z,false);});
            }
        });
        helper.onEachTick(() -> {
            level.resetEmptyTime();
            // This unregistered fake player has no entity tick to advance its item cooldowns.
            player.getCooldowns().tick();
        });
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
        var missing = (TranslatableContents) messages.getLast().getContents();
        helper.assertTrue(missing.getKey().endsWith("core_nodes") && missing.getArgs()[1] instanceof Component name
                && key(name).endsWith("node_1"),"The console did not identify the first numbered inactive node");
        for (var pos : nodes) level.setBlockAndUpdate(pos,lever.setValue(LeverBlock.POWERED,true));
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.TORCH,2));
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(console.getBossId()==null,"A held torch accidentally started the Warden");
        level.removeBlock(origin.above(),false);
        messages.clear();player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(AeronauticsInnerworld.CORE_SURVEYOR.get()));
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(console.getBossId()==null && player.getMainHandItem().is(AeronauticsInnerworld.CORE_SURVEYOR.get())
                && messages.stream().filter(text -> key(text).endsWith("core_node_status")).count()==3,"Inspection started combat or omitted node diagnostics");
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(messages.stream().filter(text -> key(text).endsWith("core_node_status")).count()==3,"Repeated input flooded the diagnostic messages");
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(console.getBossId()!=null,"Empty-handed interaction did not start the Warden");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(level.getEntity(console.getBossId()) instanceof CoreWarden,"Waiting for the encounter entity"))
                .thenExecute(() -> {
                    active[0]=(CoreWarden)level.getEntity(console.getBossId());
                    helper.assertTrue(key(console.coolingStatus()).endsWith("core_cooling_ready"),"Cooling did not start ready");
                    level.setBlockAndUpdate(valves[0],lever.setValue(LeverBlock.POWERED,true));
                }).thenExecuteAfter(5,() -> {
                    helper.assertTrue(active[0].isCoreExposed() && !level.getBlockState(valves[0]).getValue(LeverBlock.POWERED),"The first valve did not vent");
                    level.setBlockAndUpdate(valves[1],lever.setValue(LeverBlock.POWERED,true));
                    helper.assertTrue(key(console.coolingStatus()).endsWith("core_cooling_queued"),"The shared cooling queue was not reported");
                    level.setBlockAndUpdate(valves[1],lever);
                    helper.assertTrue(key(console.coolingStatus()).endsWith("core_cooling_recharging"),"Cancelling the queued valve left a stale pending indicator");
                    level.setBlockAndUpdate(valves[1],lever.setValue(LeverBlock.POWERED,true));
                    var data=console.saveWithFullMetadata(level.registryAccess());console.loadWithComponents(data,level.registryAccess());
                    helper.assertTrue(console.saveWithoutMetadata(level.registryAccess()).getInt("VentCooldown")==data.getInt("VentCooldown")
                            && key(console.coolingStatus()).endsWith("core_cooling_queued"),"Reload lost the countdown or queued valve");
                }).thenExecuteAfter(175,() -> {
                    helper.assertTrue(!active[0].isCoreExposed() && level.getBlockState(valves[1]).getValue(LeverBlock.POWERED),"A queued valve fired before shared cooling recovered");
                    messages.clear();console.showStatus(player);
                    helper.assertTrue(messages.stream().anyMatch(text -> key(text).endsWith("core_cooling_queued")),"Active inspection omitted cooling status");
                }).thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(valves[1]).getValue(LeverBlock.POWERED)
                        && active[0].isCoreExposed(),"Waiting for the queued cooling request to fire"))
                .thenExecute(() -> {
                    helper.assertTrue(key(console.coolingStatus()).endsWith("core_cooling_recharging"),"Cooling did not return to recharging after consuming the queue");
                    active[0].hurt(level.damageSources().genericKill(),10000);
                    helper.assertTrue(console.hasVictory(),"The fixture did not finish its encounter");
                    messages.clear();
                    player.getInventory().setItem(1,new ItemStack(AeronauticsInnerworld.HARMONIC_CRYSTAL.get(),4));player.setShiftKeyDown(true);
                    player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(AeronauticsInnerworld.CORE_SURVEYOR.get()));
                    player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
                    helper.assertTrue(console.getBossId()==null && player.getInventory().countItem(AeronauticsInnerworld.HARMONIC_CRYSTAL.get())==4
                            && messages.stream().filter(text -> key(text).endsWith("core_node_status")).count()==3,"Sneaking inspection failed or consumed rematch crystals");
                    player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
                    helper.assertTrue(console.getBossId()!=null && player.getInventory().countItem(AeronauticsInnerworld.HARMONIC_CRYSTAL.get())==0,"Paid empty-handed rematch no longer works");
                }).thenSucceed();
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents translated ? translated.getKey() : "";
    }
}
