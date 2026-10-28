package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.google.gson.*;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.ryanhcode.sable.Sable;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class MinecraftAgent {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static int ticks, inputTicks;
    private static int idleTicks;
    private static Path directory;
    private static CompletableFuture<?> pending;
    private static String pendingId;
    private static CoreMazeWalk walk;
    private static CompletableFuture<CoreMazeWalk> planning;
    private static String action = "idle", chapter = "准备出发", detail = "材料辅助 · 连续存档";

    static boolean enabled() {return Boolean.getBoolean("innerworld.agentPlay");}
    static Path directory() {return directory;}
    static void look(Minecraft client, Vec3 target) {
        var d = target.subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-d.x, d.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(d.y, d.horizontalDistance())));
    }
    static void release(Minecraft c) {
        c.options.keyUp.setDown(false);c.options.keyDown.setDown(false);c.options.keyLeft.setDown(false);
        c.options.keyRight.setDown(false);c.options.keyJump.setDown(false);c.options.keyShift.setDown(false);
        c.options.keyAttack.setDown(false);c.options.keyUse.setDown(false);c.options.keySprint.setDown(false);
        if(c.player != null && c.player.isUsingItem()) c.gameMode.releaseUsingItem(c.player);
    }
    static JsonArray position(Vec3 p) {var a=new JsonArray();a.add(p.x);a.add(p.y);a.add(p.z);return a;}
    static BlockPos block(JsonObject o) {return BlockPos.containing(o.get("x").getAsDouble(),o.get("y").getAsDouble(),o.get("z").getAsDouble());}
    static Vec3 point(JsonObject o) {return new Vec3(o.get("x").getAsDouble(),o.get("y").getAsDouble(),o.get("z").getAsDouble());}
    private static String string(JsonObject o,String k,String fallback) {return o.has(k)?o.get(k).getAsString():fallback;}
    private static int integer(JsonObject o,String k,int fallback) {return o.has(k)?o.get(k).getAsInt():fallback;}
    static void write(Path path, Object object) throws java.io.IOException {
        var tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,JSON.toJson(object));
        Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void reply(String id,Object value,boolean ok) throws java.io.IOException {
        var o=new JsonObject();o.addProperty("id",id);o.addProperty("ok",ok);o.add("result",JSON.toJsonTree(value));
        write(directory.resolve("response.json"),o);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!enabled()) return;
        var c=Minecraft.getInstance();
        try {
            if(directory==null) {directory=c.gameDirectory.toPath().resolve("agent");Files.createDirectories(directory);}
            c.options.pauseOnLostFocus=false;

            if(c.player==null || c.level==null || c.getSingleplayerServer()==null) return;
            ticks++;
            if(!c.player.isAlive()) {
                release(c);walk=null;planning=null;inputTicks=0;AgentTasks.cancel();
                if(AgentBaritone.busy())AgentBaritone.execute("cancel");
            }
            if(planning!=null && planning.isDone()) {walk=planning.join();planning=null;}
            if(walk!=null) {
                walk.advance(c.player.position());walk.tickClient(c);
                if(walk.arrived()) {walk=null;release(c);action="idle";}
            }
            AgentTasks.tick(c);
            if(inputTicks>0 && --inputTicks==0) {release(c);action="idle";}
            if(pending!=null && pending.isDone()) {
                try {reply(pendingId,pending.join(),true);} catch(Exception e) {reply(pendingId,e.toString(),false);}
                pending=null;
            }
            var request=directory.resolve("request.json");
            if(pending==null && Files.isRegularFile(request)) {
                var command=JsonParser.parseString(Files.readString(request)).getAsJsonObject();Files.delete(request);
                var id=command.get("id").getAsString();
                try {
                    var result=execute(c,command);
                    if(result instanceof CompletableFuture<?> future) {pending=future;pendingId=id;}
                    else reply(id,result,true);
                } catch(Exception e) {reply(id,e.toString(),false);}
            }
            if(ticks%10==0) write(directory.resolve("state.json"),state(c));
            boolean busy = walk!=null || planning!=null || pending!=null || inputTicks>0 || AgentTasks.busy() || AgentBaritone.busy();
            if(busy) idleTicks=0;
            else if(++idleTicks>8 && (c.screen==null || c.screen instanceof net.minecraft.client.gui.screens.DeathScreen)) c.pauseGame(false);
        } catch(Exception e) {action="error: "+e.getMessage();release(c);walk=null;planning=null;AgentTasks.cancel();}
    }

    private static Object execute(Minecraft c,JsonObject o) throws Exception {
        String op=o.get("action").getAsString();
        if(!java.util.Set.of("state","note","blocks","inspect","server_inspect","screenshot","record","record_stop").contains(op)) {
            if(c.screen instanceof PauseScreen)c.setScreen(null);
            idleTicks=0;
        }
        switch(op) {
            case "state": return state(c);
            case "baritone": return AgentBaritone.execute(o.get("command").getAsString());
            case "schematic": return AgentBaritone.schematic(o);
            case "respawn": c.player.respawn();c.setScreen(null);return true;
            case "pause": c.pauseGame(false);return true;
            case "key_click": net.minecraft.client.KeyMapping.click(c.options.keyUse.getKey());return true;
            case "attack_click":
                if(com.simibubi.create.AllItems.SUPER_GLUE.isIn(c.player.getMainHandItem()))
                    return com.simibubi.create.CreateClient.GLUE_HANDLER.onMouseInput(true);
                net.minecraft.client.KeyMapping.click(c.options.keyAttack.getKey());return true;
            case "offhand": c.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,BlockPos.ZERO,Direction.DOWN));return true;
            case "note": {
                chapter=string(o,"title",chapter);detail=string(o,"detail",detail);
                var line=new JsonObject();line.addProperty("time",java.time.Instant.now().toString());line.addProperty("title",chapter);line.addProperty("detail",detail);
                Files.writeString(directory.resolve("events.jsonl"),JSON.toJson(line).replace("\n","")+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                return chapter;
            }
            case "command": {
                var server=c.getSingleplayerServer();var player=c.player.getUUID();String text=o.get("command").getAsString();
                return server.submit(()-> {var p=server.getPlayerList().getPlayer(player);server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4),text);return "executed";});
            }
            case "look": look(c,point(o));return true;
            case "select": c.player.getInventory().selected=integer(o,"slot",0);return true;
            case "equip": return equip(c,o.get("item").getAsString());
            case "close": c.player.closeContainer();c.setScreen(null);return true;
            case "use": {
                if(o.has("x")) {
                    var pos=block(o);var ship=Sable.HELPER.getContaining(c.level,pos);var target=Vec3.atCenterOf(pos);
                    if(o.has("face"))target=target.add(Vec3.atLowerCornerOf(Direction.byName(o.get("face").getAsString()).getNormal()).scale(.499));
                    if(ship!=null) target=ship.logicalPose().transformPosition(target);
                    look(c,target);
                    if(ship==null) {
                        var hit=c.level.clip(new ClipContext(c.player.getEyePosition(),target,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,c.player));
                        if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getLocation().distanceTo(c.player.getEyePosition())>4.6)
                            throw new IllegalStateException("Target is out of reach or occluded: "+hit);
                        c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,hit);
                    } else {
                        if(target.distanceTo(c.player.getEyePosition())>4.6) throw new IllegalStateException("Ship device is out of reach");
                        var face=Direction.byName(string(o,"face","up"));
                        c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.49)),face,pos,false));
                    }
                } else c.gameMode.useItem(c.player,InteractionHand.MAIN_HAND);
                c.player.swing(InteractionHand.MAIN_HAND);return true;
            }
            case "click": c.gameMode.handleInventoryMouseClick(c.player.containerMenu.containerId,integer(o,"slot",0),integer(o,"button",0),ClickType.valueOf(string(o,"type","QUICK_MOVE")),c.player);return true;
            case "walk": {
                release(c);AgentTasks.cancel();var target=block(o);var server=c.getSingleplayerServer();var id=c.player.getUUID();
                planning=server.submit(()-> {var p=server.getPlayerList().getPlayer(id);return new CoreMazeWalk(p.serverLevel(),BoundingBox.fromCorners(p.blockPosition().offset(-40,-24,-40),p.blockPosition().offset(40,24,40)),p.blockPosition(),target,false,true);});
                action="walking";return true;
            }
            case "keys": {
                release(c);walk=null;planning=null;AgentTasks.cancel();inputTicks=Math.min(1200,integer(o,"ticks",20));
                for(String key:new String[]{"forward","back","left","right","jump","sneak","use","sprint"}) {
                    boolean down=o.has(key)&&o.get(key).getAsBoolean();
                    switch(key) {case "forward"->c.options.keyUp.setDown(down);case "back"->c.options.keyDown.setDown(down);case "left"->c.options.keyLeft.setDown(down);case "right"->c.options.keyRight.setDown(down);case "jump"->c.options.keyJump.setDown(down);case "sneak"->c.options.keyShift.setDown(down);case "use"->c.options.keyUse.setDown(down);case "sprint"->c.options.keySprint.setDown(down);}
                }
                if(o.has("yaw"))c.player.setYRot(o.get("yaw").getAsFloat());if(o.has("pitch"))c.player.setXRot(o.get("pitch").getAsFloat());action="input";return true;
            }
            case "stop": release(c);walk=null;planning=null;inputTicks=0;AgentTasks.cancel();action="idle";return true;
            case "mine", "place", "build", "craft", "fight", "assemble": release(c);walk=null;planning=null;AgentTasks.start(c,o);action=op;return true;
            case "blocks": {
                var out=new JsonArray();int radius=Math.min(20,integer(o,"radius",8));var center=o.has("x")?block(o):c.player.blockPosition();
                for(var p:BlockPos.betweenClosed(center.offset(-radius,-radius,-radius),center.offset(radius,radius,radius))) {
                    var s=c.level.getBlockState(p);if(s.isAir())continue;
                    if(o.has("filter")&&!BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString().contains(o.get("filter").getAsString()))continue;
                    var b=new JsonObject();b.add("pos",position(Vec3.atLowerCornerOf(p)));b.addProperty("block",s.toString());out.add(b);
                }return out;
            }
            case "inspect": {
                var pos=block(o);var b=c.level.getBlockEntity(pos);var out=new JsonObject();out.addProperty("block",c.level.getBlockState(pos).toString());
                if(b instanceof IHaveGoggleInformation g) {var lines=new java.util.ArrayList<Component>();g.addToGoggleTooltip(lines,true);out.add("goggles",JSON.toJsonTree(lines.stream().map(Component::getString).toList()));}
                if(b!=null) out.addProperty("data",b.saveWithFullMetadata(c.level.registryAccess()).toString());return out;
            }
            case "server_inspect": {
                var pos=block(o);var dimension=c.level.dimension();var server=c.getSingleplayerServer();
                return server.submit(()->{
                    var level=server.getLevel(dimension);var b=level.getBlockEntity(pos);var out=new JsonObject();
                    out.addProperty("block",level.getBlockState(pos).toString());
                    if(b!=null)out.addProperty("data",b.saveWithFullMetadata(level.registryAccess()).toString());
                    if(b instanceof io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity drill) {
                        out.addProperty("status",drill.getStatus());out.addProperty("direction",drill.getMovementDirection().getName());
                        if(drill.movedContraption!=null){out.add("head_pos",position(drill.movedContraption.position()));out.addProperty("head_dimension",drill.movedContraption.level().dimension().location().toString());}
                    }
                    return out;
                });
            }
            case "screenshot": {
                String name=string(o,"name","agent-view")+".png";Screenshot.grab(c.gameDirectory,name,c.getMainRenderTarget(),m->{});return c.gameDirectory.toPath().resolve("screenshots").resolve(name).toString();
            }
            case "record": return AgentRecorder.start(c,string(o,"name","playthrough"));
            case "record_stop": AgentRecorder.stop();return "stopping";
            case "quit": release(c);AgentRecorder.stop();c.stop();return true;
            default: throw new IllegalArgumentException("Unknown action: "+op);
        }
    }

    static boolean equip(Minecraft c,String id) {
        for(int i=0;i<36;i++) if(BuiltInRegistries.ITEM.getKey(c.player.getInventory().getItem(i).getItem()).toString().equals(id)) {
            if(i<9)c.player.getInventory().selected=i;
            else {int slot=i<9?36+i:i;c.gameMode.handleInventoryMouseClick(c.player.inventoryMenu.containerId,slot,8,ClickType.SWAP,c.player);c.player.getInventory().selected=8;}
            return true;
        }
        throw new IllegalStateException("Missing item "+id);
    }

    private static JsonObject state(Minecraft c) {
        var out=new JsonObject();out.addProperty("dimension",c.level.dimension().location().toString());out.add("position",position(c.player.position()));
        out.addProperty("yaw",c.player.getYRot());out.addProperty("pitch",c.player.getXRot());out.addProperty("health",c.player.getHealth());out.addProperty("food",c.player.getFoodData().getFoodLevel());
        out.addProperty("baritone_busy",AgentBaritone.busy());out.addProperty("paused",c.isPaused());out.addProperty("game_mode",c.gameMode.getPlayerMode().getName());out.addProperty("on_ground",c.player.onGround());out.addProperty("in_lava",c.player.isInLava());out.addProperty("selected",c.player.getInventory().selected);out.addProperty("action",action);
        out.addProperty("task",AgentTasks.status());out.addProperty("chapter",chapter);out.addProperty("screen",c.screen==null?"game":c.screen.getClass().getSimpleName());
        out.addProperty("recording",AgentRecorder.status());out.addProperty("hand_data",c.player.getMainHandItem().getComponents().toString());var items=new JsonArray();
        if(c.hitResult instanceof BlockHitResult hit) {
            var target=new JsonObject();target.add("pos",position(Vec3.atLowerCornerOf(hit.getBlockPos())));
            target.addProperty("face",hit.getDirection().getName());target.addProperty("type",hit.getType().name());out.add("target",target);
        }
        for(int i=0;i<c.player.getInventory().getContainerSize();i++) {var s=c.player.getInventory().getItem(i);if(!s.isEmpty()){var v=new JsonObject();v.addProperty("slot",i);v.addProperty("item",BuiltInRegistries.ITEM.getKey(s.getItem()).toString());v.addProperty("count",s.getCount());v.addProperty("damage",s.getDamageValue());items.add(v);}}
        out.add("inventory",items);var menu=new JsonArray();
        for(var slot:c.player.containerMenu.slots) if(!slot.getItem().isEmpty()) {var v=new JsonObject();v.addProperty("slot",slot.index);v.addProperty("item",BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());v.addProperty("count",slot.getItem().getCount());menu.add(v);}
        out.add("menu",menu);out.addProperty("menu_id",c.player.containerMenu.containerId);var entities=new JsonArray();
        for(var e:c.level.entitiesForRendering())if(e!=c.player&&e.distanceTo(c.player)<48) {var v=new JsonObject();v.addProperty("id",e.getId());v.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());v.add("position",position(e.position()));if(e instanceof net.minecraft.world.entity.LivingEntity living)v.addProperty("health",living.getHealth());entities.add(v);}
        out.add("entities",entities);
        var ships=new JsonArray();
        for(var ship:dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(c.level).getAllSubLevels()) {
            var v=new JsonObject();var box=ship.getPlot().getBoundingBox();
            v.addProperty("id",ship.getUniqueId().toString());
            v.add("min",position(new Vec3(box.minX(),box.minY(),box.minZ())));
            v.add("max",position(new Vec3(box.maxX(),box.maxY(),box.maxZ())));
            v.add("player_local",position(ship.logicalPose().transformPositionInverse(c.player.position())));
            var devices=new JsonArray();
            for(var actor:ship.getPlot().getBlockEntityActors())if(actor instanceof net.minecraft.world.level.block.entity.BlockEntity b) {
                var d=new JsonObject();d.addProperty("block",BuiltInRegistries.BLOCK.getKey(b.getBlockState().getBlock()).toString());
                d.add("pos",position(Vec3.atLowerCornerOf(b.getBlockPos())));
                d.add("world",position(ship.logicalPose().transformPosition(Vec3.atCenterOf(b.getBlockPos()))));devices.add(d);
            }
            v.add("devices",devices);ships.add(v);
        }
        out.add("ships",ships);return out;
    }

    @SubscribeEvent public static void overlay(RenderGuiEvent.Post event) {
        if(!enabled()||Minecraft.getInstance().player==null)return;
        var g=event.getGuiGraphics();var f=Minecraft.getInstance().font;int width=Math.min(g.guiWidth()-16,Math.max(f.width(chapter),f.width(detail))+18);
        g.fill(7,7,7+width,39,0xb010141a);g.drawString(f,chapter,15,13,0xe6d4ad);g.drawString(f,detail,15,26,0xb8c5c9);
    }
}
