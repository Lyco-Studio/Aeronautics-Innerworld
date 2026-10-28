package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.google.gson.*;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.*;

final class AgentTasks {
    private static JsonObject request;
    private static JsonArray plan;
    private static int index, ticks, age, craftCount, attempts, assemblyShipCount;
    private static String report="idle";
    private static CompletableFuture<CoreMazeWalk> planning;
    private static CoreMazeWalk walk;
    private static net.minecraft.world.item.crafting.RecipeHolder<?> recipe;

    static String status(){return report;}
    static boolean busy(){return request!=null;}
    static void cancel(){request=null;plan=null;walk=null;planning=null;recipe=null;report="idle";}
    static void start(Minecraft c,JsonObject o) {
        cancel();request=o;index=ticks=age=attempts=0;report=o.get("action").getAsString();
        if(report.equals("assemble"))assemblyShipCount=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(c.level).getLoadedCount();
        if(o.has("blocks"))plan=o.getAsJsonArray("blocks");
        else {plan=new JsonArray();plan.add(o);}
    }
    private static void done(Minecraft c){MinecraftAgent.release(c);request=null;walk=null;planning=null;report="done";}
    static void tick(Minecraft c) {
        if(request==null)return;
        try {
            ticks++;age++;
            if(age>24000)throw new IllegalStateException("Task exceeded 20 minutes");
            String action=request.get("action").getAsString();
            if(action.equals("fight")){fight(c);return;}
            if(action.equals("craft")){craft(c);return;}
            if(action.equals("assemble")){assemble(c);return;}
            if(planning!=null){if(!planning.isDone())return;walk=planning.join();planning=null;}
            if(walk!=null){walk.advance(c.player.position());walk.tickClient(c);if(!walk.arrived())return;walk=null;MinecraftAgent.release(c);ticks=0;}
            if(index>=plan.size()){done(c);return;}
            var step=plan.get(index).getAsJsonObject();var target=MinecraftAgent.block(step);var state=c.level.getBlockState(target);
            boolean mine=action.equals("mine");
            if(mine?state.isAir():BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(step.get("item").getAsString())) {
                index++;ticks=attempts=0;report=action+" "+index+"/"+plan.size();c.gameMode.stopDestroyBlock();return;
            }
            if(ticks>600)throw new IllegalStateException("Cannot reach or complete block "+target+"; now="+state);
            BlockHitResult hit=mine?hit(c,target):placement(c,target);
            if(hit==null) {
                if(ticks%15==0 && planning==null) approach(c,target,mine);
                return;
            }
            MinecraftAgent.look(c,hit.getLocation());
            if(mine) {
                if(step.has("tool"))MinecraftAgent.equip(c,step.get("tool").getAsString());
                c.options.keyShift.setDown(false);
                c.gameMode.continueDestroyBlock(target,hit.getDirection());c.player.swing(InteractionHand.MAIN_HAND);
            } else if(ticks%8==0) {
                MinecraftAgent.equip(c,step.get("item").getAsString());
                c.options.keyShift.setDown(true);
                if(step.has("yaw"))c.player.setYRot(step.get("yaw").getAsFloat());
                if(step.has("pitch"))c.player.setXRot(step.get("pitch").getAsFloat());
                c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,hit);c.player.swing(InteractionHand.MAIN_HAND);
            }
        }catch(Exception e){MinecraftAgent.release(c);request=null;walk=null;planning=null;report="failed: "+e.getMessage();}
    }
    private static BlockHitResult hit(Minecraft c,BlockPos pos){
        for(double y:new double[]{.5,.2,.8}) {
            var point=Vec3.atLowerCornerOf(pos).add(.5,y,.5);
            var hit=c.level.clip(new ClipContext(c.player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,c.player));
            if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(pos)&&hit.getLocation().distanceTo(c.player.getEyePosition())<4.5)return hit;
        }return null;
    }
    private static BlockHitResult placement(Minecraft c,BlockPos target){
        if(!c.level.getBlockState(target).canBeReplaced())throw new IllegalStateException("Placement target occupied: "+target);
        for(var direction:Direction.values()) {
            var against=target.relative(direction);var face=direction.getOpposite();
            if(c.level.getBlockState(against).getCollisionShape(c.level,against).isEmpty())continue;
            var point=Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499));
            var hit=c.level.clip(new ClipContext(c.player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,c.player));
            if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(against)&&hit.getDirection()==face&&hit.getLocation().distanceTo(c.player.getEyePosition())<4.5)return hit;
        }return null;
    }
    private static void approach(Minecraft c,BlockPos target,boolean mine){
        MinecraftAgent.release(c);var server=c.getSingleplayerServer();var id=c.player.getUUID();int skip=attempts++;
        planning=server.submit(()->{
            var p=server.getPlayerList().getPlayer(id);var level=p.serverLevel();var candidates=new ArrayList<BlockPos>();
            for(var pos:BlockPos.betweenClosed(target.offset(-3,-3,-3),target.offset(3,1,3))) {
                if(!level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()||!level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty()
                    ||!level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP))continue;
                if(pos.equals(target)||pos.distSqr(target)<1)continue;candidates.add(pos.immutable());
            }
            candidates.sort(Comparator.comparingDouble(pos->pos.distToCenterSqr(p.position())));
            if(candidates.isEmpty())throw new IllegalStateException("No standable approach to "+target);
            var bounds=BoundingBox.fromCorners(p.blockPosition().offset(-24,-16,-24),p.blockPosition().offset(24,16,24));
            for(int i=0;i<candidates.size();i++)try{return new CoreMazeWalk(level,bounds,p.blockPosition(),candidates.get((i+skip)%candidates.size()),false,true);}catch(IllegalStateException ignored){}
            throw new IllegalStateException("No walkable approach to "+target);
        });
    }
    private static void craft(Minecraft c) {
        if(!(c.player.containerMenu instanceof CraftingMenu menu))throw new IllegalStateException("Open a crafting table first");
        if(recipe==null) {
            recipe=c.getSingleplayerServer().getRecipeManager().byKey(ResourceLocation.parse(request.get("recipe").getAsString())).orElseThrow();
            var id=c.player.getUUID();var holder=recipe;c.getSingleplayerServer().execute(()->c.getSingleplayerServer().getPlayerList().getPlayer(id).awardRecipes(List.of(holder)));
            craftCount=0;ticks=0;
        }
        if(ticks%10==1)c.gameMode.handlePlaceRecipe(menu.containerId,recipe,false);
        if(ticks%10==6&&!menu.getSlot(0).getItem().isEmpty()) {
            craftCount+=menu.getSlot(0).getItem().getCount();c.gameMode.handleInventoryMouseClick(menu.containerId,0,0,ClickType.QUICK_MOVE,c.player);
            if(craftCount>=(request.has("count")?request.get("count").getAsInt():1)){done(c);return;}
        }
        if(ticks>160)throw new IllegalStateException("Crafting ingredients unavailable for "+recipe.id());
    }
    private static void assemble(Minecraft c) {
        var pos=MinecraftAgent.block(request);
        if(dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(c.level).getLoadedCount()>assemblyShipCount){done(c);return;}
        if(!c.player.getMainHandItem().isEmpty())throw new IllegalStateException("Physics assembly requires an empty hand");
        var point=Vec3.atCenterOf(pos).add(0,-.25,0);MinecraftAgent.look(c,point);
        var handler=dev.simulated_team.simulated.index.SimClickInteractions.PHYSICS_ASSEMBLER_MANAGER;
        if(ticks==10) {
            var hit=c.level.clip(new ClipContext(c.player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,c.player));
            if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos)||point.distanceTo(c.player.getEyePosition())>4.5)
                throw new IllegalStateException("Physics assembler is out of reach or occluded");
            c.options.keyUse.setDown(true);c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,hit);
        }
        if(ticks>10&&handler.isActive()&&index==0) {
            if(handler.getFraction()<.99)handler.onMouseMove(0,-40);
            else if(ticks>15){handler.onUse(1,0,c.options.keyUse);c.options.keyUse.setDown(false);index=1;}
        }
        if(ticks>200)throw new IllegalStateException("Physics assembler did not finish");
    }
    private static void fight(Minecraft c) {
        Monster target=null;double nearest=48*48;
        for(var e:c.level.entitiesForRendering())if(e instanceof Monster mob&&mob.isAlive()&&c.player.hasLineOfSight(mob)&&mob.distanceToSqr(c.player)<nearest){target=mob;nearest=mob.distanceToSqr(c.player);}
        if(target==null){done(c);return;}
        if(target instanceof CoreWarden boss&&boss.getHeatZone()!=null) {
            var zone=Vec3.atBottomCenterOf(boss.getHeatZone());
            if(c.player.position().subtract(zone).horizontalDistanceSqr()<12.25) {
                var away=c.player.position().subtract(zone).multiply(1,0,1);if(away.lengthSqr()<.1)away=new Vec3(1,0,0);
                MinecraftAgent.look(c,c.player.getEyePosition().add(away.normalize()));c.options.keyUp.setDown(true);c.options.keyDown.setDown(false);return;
            }
        }
        boolean eat=c.player.getFoodData().getFoodLevel()<=16;MinecraftAgent.look(c,target.getEyePosition());
        c.options.keyUp.setDown(!eat&&nearest>5);c.options.keyDown.setDown(eat&&nearest<64);
        String item=eat?"minecraft:cooked_beef":"minecraft:diamond_sword";
        if(!BuiltInRegistries.ITEM.getKey(c.player.getMainHandItem().getItem()).toString().equals(item)&&c.player.isUsingItem())c.gameMode.releaseUsingItem(c.player);
        MinecraftAgent.equip(c,item);
        if(eat){c.options.keyUse.setDown(true);if(!c.player.isUsingItem())c.gameMode.useItem(c.player,InteractionHand.MAIN_HAND);}
        else if(target instanceof CoreWarden boss&&boss.getAttackType()!=0){c.options.keyUse.setDown(true);if(!c.player.isUsingItem())c.gameMode.useItem(c.player,InteractionHand.OFF_HAND);}
        else {
            c.options.keyUse.setDown(false);
            if(nearest<10&&c.player.getAttackStrengthScale(0)>.95f) {
                if(c.player.isUsingItem())c.gameMode.releaseUsingItem(c.player);
                c.gameMode.attack(c.player,target);c.player.swing(InteractionHand.MAIN_HAND);
            } else if(nearest<16&&c.player.getOffhandItem().is(Items.SHIELD)) {
                if(!c.player.isUsingItem())c.gameMode.useItem(c.player,InteractionHand.OFF_HAND);
            } else if(c.player.isUsingItem())c.gameMode.releaseUsingItem(c.player);
        }
        if(ticks>(request.has("ticks")?request.get("ticks").getAsInt():400))done(c);
    }
}
