package io.github.alphaly.aeronauticsinnerworld.client.ponder;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.alphaly.aeronauticsinnerworld.content.probe.HeatEngineBlock;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld.*;

public final class InnerworldPonderScenes {
    private InnerworldPonderScenes() { }

    public static void drillAssembly(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = begin(builder, "drill_assembly", "Assembling and steering a drill head");
        var fixed = util.select().fromTo(2, 1, 3, 2, 2, 4);
        var head = util.select().fromTo(2, 2, 5, 3, 2, 5);
        var rock = util.select().fromTo(2, 1, 6, 3, 3, 6);
        scene.world().showSection(fixed, Direction.DOWN);
        scene.world().setKineticSpeed(fixed, 32);
        narrate(scene, "Power the back shaft of the fixed controller. Its front points toward the drill head.", 2, 2, 4);
        var moving = scene.world().showIndependentSection(head, Direction.DOWN);
        scene.world().showSection(rock, Direction.DOWN);
        scene.overlay().showControls(at(3, 3, 5), Pointing.DOWN, 50).rightClick().withItem(AllItems.SUPER_GLUE.asStack());
        scene.overlay().showOutline(PonderPalette.GREEN, "head", head, 100);
        narrate(scene, "Glue the multi-axis cutter and a chest together. Keep the fixed controller, engine and rock outside the selection.", 3, 2, 5);
        click(scene, at(2, 2, 4), ItemStack.EMPTY, false);
        scene.world().destroyBlock(util.grid().at(2, 2, 6));
        scene.world().destroyBlock(util.grid().at(3, 2, 6));
        scene.world().moveSection(moving, new Vec3(0, 0, 1), 40);
        narrate(scene, "Right-click the controller with an empty hand to start. Cut blocks enter the attached storage.", 3, 2, 6);
        click(scene, at(2, 2, 4), ItemStack.EMPTY, false);
        narrate(scene, "Right-click again to pause at a whole-block position before changing direction or unloading.", 2, 2, 4);
        click(scene, at(2, 2, 4), new ItemStack(DRILL_REMOTE.get()), false);
        click(scene, at(4, 2, 6), new ItemStack(DRILL_REMOTE.get()), true);
        scene.overlay().showLine(PonderPalette.BLUE, at(2, 2, 6), at(5, 2, 6), 100);
        scene.world().moveSection(moving, new Vec3(2, 0, 0), 50);
        narrate(scene, "Bind the remote to the controller. While paused, sneak-use the remote to choose a direction, then resume horizontal excavation.", 4, 2, 6);
        click(scene, at(2, 2, 4), AllItems.WRENCH.asStack(), true);
        narrate(scene, "Pause, then sneak-use a wrench on the original controller to place the head back into the world and open its storage.", 3, 2, 5);
        scene.markAsFinished();
    }

    public static void layerConnection(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = begin(builder, "layer_connection", "Connecting the underground layers");
        var head = util.select().fromTo(2, 5, 2, 3, 5, 3);
        scene.world().showSection(util.select().fromTo(1, 5, 1, 3, 7, 3).substract(head), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(4, 1, 4, 7, 1, 7), Direction.UP);
        var moving = scene.world().showIndependentSection(head, Direction.DOWN);
        narrate(scene, "The drill connects the Overworld, mantle and core. Only its Create drill-head assembly crosses between layers.", 2, 5, 2);
        click(scene, at(2, 6, 2), new ItemStack(DRILL_REMOTE.get()), false);
        click(scene, at(1, 6, 2), new ItemStack(DRILL_REMOTE.get()), false);
        click(scene, at(3, 5, 2), new ItemStack(DRILL_REMOTE.get()), false);
        narrate(scene, "Bind one remote to the controller and both bore stations. Glue one station and supplies to the head; leave the other at the powered origin.", 3, 5, 2);
        scene.world().moveSection(moving, new Vec3(3, -3, 3), 60);
        narrate(scene, "Drill downward. At the layer boundary the head transfers and pauses automatically; fixed equipment stays at the origin.", 5, 2, 5);
        scene.world().moveSection(moving, new Vec3(1, 0, 0), 40);
        narrate(scene, "Resume downward, then turn horizontally in the upper mantle to excavate a dry preparation base. Probes are built and operated here.", 6, 2, 5);
        click(scene, at(2, 6, 2), AllItems.WRENCH.asStack(), true);
        scene.overlay().showLine(PonderPalette.GREEN, at(1, 6, 2), at(7, 2, 5), 100);
        narrate(scene, "Pause and wrench the original controller to land the head. Keep both station exits dry with two blocks of headroom and solid footing.", 7, 2, 5);
        click(scene, at(1, 6, 2), ItemStack.EMPTY, false);
        narrate(scene, "Empty-hand right-click a connected station to travel with your inventory. Keep the original drill powered to maintain the connection.", 1, 6, 2);
        scene.world().setBlock(util.grid().at(6, 2, 7), DEEP_BORE_COUPLER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(6, 2, 7), Direction.DOWN);
        narrate(scene, "For the core expedition, attach a deep-bore coupler to the drill head, establish a mantle origin, and drill through the lower boundary.", 6, 2, 7);
        scene.markAsFinished();
    }

    public static void sealedProbe(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = begin(builder, "sealed_probe", "Sealing, assembling and repairing a probe");
        var hull = util.select().fromTo(2, 1, 2, 6, 5, 6);
        var hatch = util.select().fromTo(2, 2, 4, 2, 3, 4);
        scene.world().showSection(hull, Direction.DOWN);
        narrate(scene, "Build a completely enclosed hull in a dry mantle dock. Use refractory hull, viewports and a two-block pressure hatch for access.", 2, 2, 4);
        click(scene, at(2, 2, 4), ItemStack.EMPTY, false);
        scene.world().modifyBlock(util.grid().at(2, 2, 4), state -> state.setValue(PressureHatchBlock.OPEN, false), false);
        scene.world().modifyBlock(util.grid().at(2, 3, 4), state -> state.setValue(PressureHatchBlock.OPEN, false), false);
        scene.overlay().showOutline(PonderPalette.GREEN, "hatch", hatch, 100);
        narrate(scene, "Close the hatch before flooding the dock. Matching stacked hatches open and close together.", 2, 2, 4);
        scene.world().hideSection(util.select().fromTo(2, 4, 2, 6, 5, 6), Direction.UP);
        scene.world().hideSection(util.select().fromTo(3, 2, 6, 6, 3, 6), Direction.SOUTH);
        narrate(scene, "Cutaway view: face the regulator vent into the dry cabin and supply at least 16 RPM plus coolant. The real hull must also have a sealed roof.", 4, 2, 2);
        scene.world().showSection(util.select().fromTo(2, 4, 2, 6, 5, 6), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(3, 2, 6, 6, 3, 6), Direction.NORTH);
        click(scene, at(4, 5, 4), ItemStack.EMPTY, false);
        scene.overlay().showOutline(PonderPalette.GREEN, "hull", hull, 100);
        narrate(scene, "Glue all onboard parts together, excluding the dock. On the physics assembler, hold right-click and drag its handle fully upward, then release.", 4, 5, 4);
        var ship = scene.world().makeSectionIndependent(hull);
        scene.world().moveSection(ship, new Vec3(0, .4, 0), 30);
        scene.idle(35);
        scene.world().moveSection(ship, new Vec3(0, -.4, 0), 30);
        scene.idle(35);
        var damage = util.grid().at(2, 2, 3);
        scene.world().setBlock(damage, DAMAGED_HULL.get().defaultBlockState(), true);
        scene.overlay().showOutline(PonderPalette.RED, "breach", util.select().position(damage), 100);
        narrate(scene, "Hull damage can breach the cabin; device faults can stop equipment. Check the regulator and sounder with engineer's goggles to locate trouble.", 2, 2, 3);
        scene.world().destroyBlock(damage);
        scene.idle(15);
        click(scene, at(2, 2, 3), new ItemStack(EMERGENCY_PATCH.get()), false);
        scene.world().setBlock(damage, EMERGENCY_PATCH.get().defaultBlockState(), true);
        narrate(scene, "Seal an opening with an emergency patch to regain protection. Keep spare patches and repair kits inside the cabin.", 2, 2, 3);
        click(scene, at(2, 2, 3), new ItemStack(REPAIR_KIT.get()), false);
        scene.world().setBlock(damage, REFRACTORY_HULL.get().defaultBlockState(), true);
        narrate(scene, "Use a repair kit on damaged hull, a patch or a faulty device. Restore cooling and power, then return to a dry dock for inspection.", 2, 2, 3);
        scene.markAsFinished();
    }

    public static void heatExchange(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = begin(builder, "heat_exchange", "Lava heat and shared cabin coolant");
        scene.world().showSection(util.select().fromTo(2, 1, 1, 6, 2, 6), Direction.DOWN);
        narrate(scene, "Cutaway of an assembled mantle probe: each heat engine draws heat from lava and supplies 32 RPM / 1024 SU from its rear shaft.", 4, 2, 1);
        scene.world().setBlocks(util.select().fromTo(1, 1, 0, 7, 1, 0), Blocks.LAVA.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(1, 2, 0, 7, 2, 0), Blocks.LAVA.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(1, 1, 0, 7, 2, 0), Direction.UP);
        narrate(scene, "Assemble and close the probe in a dry dock first. Flood the dock until the engines are immersed in mantle lava; dry or unassembled engines stay off.", 4, 2, 1);
        click(scene, at(4, 2, 2), new ItemStack(COOLANT_CELL.get()), false);
        scene.world().setKineticSpeed(util.select().fromTo(2, 2, 1, 6, 2, 5), 32);
        engines(scene, true);
        scene.overlay().showLine(PonderPalette.BLUE, at(4, 2, 2), at(2, 2, 4), 100);
        scene.overlay().showLine(PonderPalette.BLUE, at(4, 2, 2), at(6, 2, 4), 100);
        narrate(scene, "Right-click the cabin regulator with coolant cells. All engines on the same probe share this reserve, so refill from inside the cabin.", 4, 2, 2);
        narrate(scene, "A basic cell adds 120 units, a stabilized cell 240; each regulator holds 600. Allow enough room for a whole cell before refilling.", 4, 2, 2);
        engines(scene, false);
        scene.world().setKineticSpeed(util.select().fromTo(2, 2, 1, 6, 2, 5), 0);
        scene.overlay().showOutline(PonderPalette.RED, "cooling", util.select().position(4, 2, 2), 100);
        narrate(scene, "When coolant runs out, heat exchange stops and the engines lose output. Cabin cooling also needs power: refill before the reserve runs low.", 4, 2, 2);
        click(scene, at(4, 2, 2), new ItemStack(STABILIZED_COOLANT_CELL.get()), false);
        engines(scene, true);
        scene.world().setKineticSpeed(util.select().fromTo(2, 2, 1, 6, 2, 5), 32);
        narrate(scene, "Coolant can be added while stopped. With lava heat restored and coolant available, the engines restart; goggles show the current condition and stop reason.", 4, 2, 1);
        scene.markAsFinished();
    }

    public static void propulsionTrim(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = begin(builder, "propulsion_trim", "Independent propulsion and depth trim");
        var deck = util.select().fromTo(2, 1, 1, 6, 3, 6);
        scene.world().showSection(deck, Direction.DOWN);
        var left = util.select().fromTo(3, 2, 3, 3, 2, 5);
        var right = util.select().fromTo(5, 2, 3, 5, 2, 5);
        scene.world().setKineticSpeed(util.select().fromTo(3, 2, 2, 5, 2, 2), 32);
        narrate(scene, "Drive-train cutaway: build two independent lanes, each with an engine, clutch, gearshift and thruster. Thrusters and ballast must be immersed in mantle lava.", 3, 2, 3);
        click(scene, at(3, 3, 3), ItemStack.EMPTY, false);
        scene.world().toggleRedstonePower(util.select().fromTo(3, 2, 3, 5, 3, 3));
        scene.world().setKineticSpeed(left, 32);
        scene.world().setKineticSpeed(right, 32);
        scene.overlay().showLine(PonderPalette.GREEN, at(3, 2, 5), at(3, 2, 7), 100);
        scene.overlay().showLine(PonderPalette.GREEN, at(5, 2, 5), at(5, 2, 7), 100);
        narrate(scene, "A powered clutch disconnects its lane. Turn both clutch levers off to engage thrust; turn them on to stop propulsion.", 3, 3, 3);
        scene.world().toggleRedstonePower(util.select().fromTo(3, 2, 4, 5, 3, 4));
        scene.world().setKineticSpeed(util.select().fromTo(3, 2, 4, 5, 2, 5), -32);
        narrate(scene, "Switch both gearshifts together to reverse. Their levers change rotation direction; keep both lanes matched for straight travel.", 5, 3, 4);
        scene.world().toggleRedstonePower(util.select().fromTo(3, 2, 4, 3, 3, 4));
        scene.world().setKineticSpeed(left, 32);
        scene.overlay().showLine(PonderPalette.BLUE, at(3, 2, 5), at(3, 2, 7), 100);
        scene.overlay().showLine(PonderPalette.BLUE, at(5, 2, 7), at(5, 2, 5), 100);
        narrate(scene, "Stop one lane to turn, or run the lanes in opposite directions for a tight turn. Briefly reverse the difference to slow the turn before stopping.", 4, 2, 5);
        scene.world().setKineticSpeed(left, 0);
        scene.world().setKineticSpeed(right, 0);
        scene.overlay().showOutline(PonderPalette.BLUE, "ballast", util.select().fromTo(3, 2, 1, 5, 2, 1), 100);
        narrate(scene, "In speed mode, ballast holds depth at 32 RPM. Each ballast supports up to 128 mass units; add more for a larger or heavily loaded probe.", 3, 2, 1);
        click(scene, at(3, 2, 1), ItemStack.EMPTY, true);
        click(scene, at(5, 2, 1), ItemStack.EMPTY, true);
        narrate(scene, "Empty both hands, then sneak-right-click each ballast to cycle speed mode, ascend and descend. Match both modes, then return to speed mode to hold depth.", 5, 2, 1);
        scene.markAsFinished();
    }

    public static void surveySampling(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = begin(builder, "survey_sampling", "Finding and collecting mantle samples");
        var onboard = util.select().fromTo(1, 1, 1, 3, 2, 5);
        scene.world().showSection(onboard, Direction.DOWN);
        scene.world().showSection(util.select().fromTo(7, 1, 3, 7, 3, 5), Direction.DOWN);
        scene.world().setKineticSpeed(util.select().fromTo(2, 2, 1, 2, 2, 2), 32);
        click(scene, at(2, 2, 2), AllItems.GOGGLES.asStack(), false);
        narrate(scene, "Power the echo sounder at 16 RPM or more. Engineer's goggles show navigation and cabin readings; hold Shift to inspect survey echoes and faults.", 2, 2, 2);
        scene.overlay().showOutline(PonderPalette.BLUE, "ore", util.select().position(7, 2, 4), 100);
        narrate(scene, "The sounder locates nearby harmonic ore and marks lava creatures. A display link on the sounder can feed navigation, cabin, survey or damage readings to a powered display board.", 7, 2, 4);
        scene.overlay().showLine(PonderPalette.GREEN, at(3, 2, 4), at(7, 2, 4), 100);
        narrate(scene, "On an assembled probe, align the sampler mouth with the ore at the same height. Approach within six blocks, stop thrust, and hold position.", 3, 2, 4);
        scene.world().setBlock(util.grid().at(5, 2, 4), Blocks.DEEPSLATE.defaultBlockState(), false);
        scene.world().showSection(util.select().position(5, 2, 4), Direction.DOWN);
        scene.overlay().showOutline(PonderPalette.RED, "obstacle", util.select().position(5, 2, 4), 100);
        narrate(scene, "Solid blocks block the sampling path. Clear them or reposition the probe, keeping the full hull and external engines clear of the rock face.", 5, 2, 4);
        scene.world().destroyBlock(util.grid().at(5, 2, 4));
        scene.world().setKineticSpeed(util.select().fromTo(2, 2, 4, 3, 2, 4), 32);
        scene.world().incrementBlockBreakingProgress(util.grid().at(7, 2, 4));
        scene.idle(20);
        scene.world().destroyBlock(util.grid().at(7, 2, 4));
        narrate(scene, "Supply rotational power to collect ore into the sampler's cargo hold. Stop and unload when full; goggles show progress and cargo space.", 3, 2, 4);
        click(scene, at(3, 2, 4), ItemStack.EMPTY, false);
        scene.world().createItemEntity(at(3, 3, 4), new Vec3(0, .1, .1), new ItemStack(RAW_HARMONIC.get(), 2));
        narrate(scene, "Empty-hand right-click the sampler to unload. Return to the recorded base, process your samples, and replenish coolant and repair supplies before the next trip.", 3, 2, 4);
        scene.markAsFinished();
    }

    private static CreateSceneBuilder begin(SceneBuilder builder, String id, String title) {
        var scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(.75f);
        scene.showBasePlate();
        scene.idle(10);
        return scene;
    }

    private static void narrate(CreateSceneBuilder scene, String text, int x, int y, int z) {
        scene.overlay().showText(110).text(text).pointAt(at(x, y, z)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
    }

    private static void click(CreateSceneBuilder scene, Vec3 target, ItemStack stack, boolean sneak) {
        var input = scene.overlay().showControls(target.add(0, .6, 0), Pointing.DOWN, 40).rightClick();
        if (!stack.isEmpty()) input.withItem(stack);
        if (sneak) input.whileSneaking();
    }

    private static void engines(CreateSceneBuilder scene, boolean active) {
        for (var pos : new BlockPos[] {new BlockPos(4, 2, 1), new BlockPos(2, 2, 4), new BlockPos(6, 2, 4)}) {
            scene.world().modifyBlock(pos, state -> state.setValue(HeatEngineBlock.ACTIVE, active), false);
        }
    }

    private static Vec3 at(int x, int y, int z) {return Vec3.atCenterOf(new BlockPos(x, y, z));}
}
