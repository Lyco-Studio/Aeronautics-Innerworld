package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.HeatEngineBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.HeatEngineBlock;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_thermal")
@PrefixGameTestTemplate(false)
public final class HeatEngineChecks {
    @GameTest(template = "empty", timeoutTicks = 450)
    public static void drivesStopsAndRestartsWithoutFuel(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        checkRecipes(helper);
        var rig = new ProbeTestRig(level,new BlockPos(512,170,512));
        rig.heatPower();
        helper.onEachTick(level::resetEmptyTime);
        double[] start = new double[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(),"Waiting for heat probe assembly"))
                .thenExecute(() -> {
                    refill(helper,rig);
                    rig.assemble();
                    helper.assertTrue(rig.cabin().getCoolant()==120,"Assembly lost coolant loaded in the dry dock");
                    while (rig.cabin().consumeCoolantTicks(20)) { }
                }).thenExecuteAfter(12,() -> {
                    assertRunning(helper,rig,false);
                    for (var actor : rig.subLevel.getPlot().getBlockEntityActors())
                        helper.assertTrue(!(actor instanceof CreativeMotorBlockEntity),"Heat probe retained creative power");
                    refill(helper,rig);
                }).thenExecuteAfter(30,() -> {
                    assertRunning(helper,rig,true);
                    helper.assertTrue(Math.abs(rig.cabin().getSpeed())==32 && rig.cabin().isSealed(),"Heat engine failed to power and seal the cabin");
                    start[0]=rig.subLevel.logicalPose().position().z();
                    for (var control : new BlockPos[]{ProbeTestRig.LEFT_THROTTLE,ProbeTestRig.RIGHT_THROTTLE}) {
                        var pos=rig.local(control);
                        level.setBlockAndUpdate(pos,level.getBlockState(pos).setValue(BlockStateProperties.POWERED,false));
                    }
                }).thenExecuteAfter(70,() -> {
                    helper.assertTrue(rig.subLevel.logicalPose().position().z()>start[0]+.1,"Heat-powered probe did not propel its native hull");
                    helper.assertTrue(rig.cabin().getCoolant()<118 && rig.cabin().getCoolant()>100,"Shared coolant debit is missing or excessive: "+rig.cabin().getCoolant());
                    while (rig.cabin().consumeCoolantTicks(20)) { }
                    while (rig.cabin().consumeCoolantTicks(1)) { }
                }).thenExecuteAfter(8,() -> {
                    assertRunning(helper,rig,false);
                    helper.assertTrue(rig.cabin().getSpeed()==0,"Depleted coolant left the cabin shaft powered");
                    rig.unload();
                }).thenExecuteAfter(5,rig::restore).thenExecuteAfter(20,() -> {
                    assertRunning(helper,rig,false);
                    helper.assertTrue(rig.cabin().getCoolant()==0,"Reload created coolant");
                    refill(helper,rig);
                }).thenExecuteAfter(30,() -> {
                    assertRunning(helper,rig,true);
                    helper.assertTrue(rig.cabin().isSealed(),"Refilled probe failed to reseal after power loss");
                    LogUtils.getLogger().info("Heat probe passed: seven native generators, propulsion, shared coolant depletion, reload without power and coolant-only restart");
                    rig.cleanUp();
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void isolatesVesselCoolingAndPersistsFractions(GameTestHelper helper) {
        var level=helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var dry=new ProbeTestRig(level,new BlockPos(640,170,512));
        var supplied=new ProbeTestRig(level,new BlockPos(688,170,512));
        dry.heatPower();supplied.heatPower();
        helper.onEachTick(level::resetEmptyTime);
        CompoundTag[] saved = new CompoundTag[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(dry.ready() && supplied.ready(),"Waiting for isolated heat probes"))
                .thenExecute(() -> {dry.assemble();supplied.assemble();refill(helper,supplied);})
                .thenWaitUntil(() -> assertRunning(helper,supplied,true))
                .thenExecuteAfter(23,() -> {
                    assertRunning(helper,dry,false);assertRunning(helper,supplied,true);
                    saved[0]=supplied.cabin().saveWithoutMetadata(level.registryAccess());
                    helper.assertTrue(saved[0].getInt("CoolantFraction")>0,"Engine cooling did not retain its fractional debit");
                    supplied.unload();
                }).thenExecuteAfter(5,() -> {
                    supplied.restore();
                    var restored=supplied.cabin().saveWithoutMetadata(level.registryAccess());
                    helper.assertTrue(restored.getInt("Coolant")==saved[0].getInt("Coolant")
                            && restored.getInt("CoolantFraction")==saved[0].getInt("CoolantFraction"),"Reload changed shared coolant fractions");
                }).thenExecuteAfter(25,() -> {
                    assertRunning(helper,dry,false);assertRunning(helper,supplied,true);
                    var p=supplied.subLevel.logicalPose().position();
                    dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(supplied.subLevel)
                            .teleport(new org.joml.Vector3d(p.x(),285,p.z()),new org.joml.Quaterniond());
                }).thenExecuteAfter(15,() -> {
                    assertRunning(helper,supplied,false);
                    LogUtils.getLogger().info("Heat engine isolation passed: independent vessel reservoirs, exact saved fractions and loss-of-heat shutdown");
                    dry.cleanUp();supplied.cleanUp();
                }).thenSucceed();
    }

    private static void refill(GameTestHelper helper,ProbeTestRig rig) {
        var player=new FakePlayer(rig.level,new GameProfile(UUID.fromString("184f079c-e329-4dc6-818d-8b32ca7fea01"),"HeatEngineer"));
        var stack=new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get(),2);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var pos=rig.cabin().getBlockPos();
        AeronauticsInnerworld.COOLANT_CELL.get().useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false)));
        helper.assertTrue(stack.getCount()==1 && rig.cabin().getCoolant()>=120,"Unpowered coolant loading failed or consumed the wrong amount");
    }

    private static void checkRecipes(GameTestHelper helper) {
        var level=helper.getLevel();
        var recipe=level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID,"heat_engine")).orElseThrow().value();
        var tag=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM)
                .getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID,"portable_engines"))).orElseThrow();
        int count=0;
        for (var holder:tag) {
            var input=new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(9,ItemStack.EMPTY));
            for (int slot:new int[]{1,3,5}) input.set(slot,com.simibubi.create.AllItems.COPPER_SHEET.asStack());
            input.set(4,new ItemStack(holder.value()));
            input.set(7,com.simibubi.create.AllBlocks.FLUID_TANK.asStack());
            var crafting=CraftingInput.of(3,3,input);
            helper.assertTrue(((net.minecraft.world.item.crafting.CraftingRecipe)recipe).matches(crafting,level),"Heat engine recipe rejected "+BuiltInRegistries.ITEM.getKey(holder.value()));
            var result=((net.minecraft.world.item.crafting.CraftingRecipe)recipe).assemble(crafting,level.registryAccess());
            helper.assertTrue(result.is(AeronauticsInnerworld.HEAT_ENGINE_ITEM.get()) && result.getCount()==1,"Heat engine upgrade produced an invalid result");
            count++;
        }
        helper.assertTrue(count==16,"Heat engine upgrade missed portable engine colors");
    }

    private static void assertRunning(GameTestHelper helper,ProbeTestRig rig,boolean active) {
        helper.assertTrue(rig.heatEngines().size()==7,"Expected seven heat engines on the assembled probe");
        for (HeatEngineBlockEntity engine:rig.heatEngines())
            helper.assertTrue(engine.isRunning()==active && (Math.abs(engine.getGeneratedSpeed())==32)==active
                            && engine.getBlockState().getValue(HeatEngineBlock.ACTIVE)==active,
                    "Heat engine state mismatch: expected="+active+", status="+engine.getStatus()+", speed="+engine.getSpeed()
                    +", world="+rig.subLevel.logicalPose().transformPosition(Vec3.atCenterOf(engine.getBlockPos())));
    }
}
