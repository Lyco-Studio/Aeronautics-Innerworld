package io.github.alphaly.aeronauticsinnerworld.content.core;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CoreConsoleBlockEntity extends BlockEntity {
    private long[] nodes = new long[0];
    private long[] gates = new long[0];
    private long[] valves = new long[0];
    private BlockPos arena;
    private UUID boss;
    private boolean victory;
    private int ventCooldown;
    private int missingTicks;

    public CoreConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.CORE_CONSOLE_ENTITY.get(), pos, state);
    }

    public void configure(long[] nodes, long[] gates, long[] valves, BlockPos arena) {
        this.nodes = nodes.clone();
        this.gates = gates.clone();
        this.valves = valves.clone();
        this.arena = arena.immutable();
        setChanged();
    }

    public UUID getBossId() {
        return boss;
    }

    public boolean hasVictory() {
        return victory;
    }

    public void interact(ServerPlayer player) {
        if (!(level instanceof ServerLevel server) || arena == null || nodes.length != 3) return;
        if (boss != null) {
            message(player, "core_battle_active");
            return;
        }
        var nodeStates = readNodes(server);
        int restored = 0, missing = -1;
        for (int i = 0; i < nodeStates.length; i++) {
            if (nodeStates[i]) restored++;
            else if (missing == -1) missing = i;
        }
        if (restored != 3) {
            var pos = BlockPos.of(nodes[missing]);
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.core_nodes",
                    restored, nodeName(missing), pos.getX(), pos.getY(), pos.getZ()), false);
            return;
        }
        for (long packed : gates) {
            var pos = BlockPos.of(packed);
            if (server.getBlockState(pos).is(AeronauticsInnerworld.CORE_SEAL.get())) server.removeBlock(pos, false);
        }
        if (victory && !player.isShiftKeyDown()) {
            message(player, "core_rematch");
            return;
        }
        if (victory && !player.isCreative()) {
            int crystals = player.getInventory().countItem(AeronauticsInnerworld.HARMONIC_CRYSTAL.get());
            if (crystals < 4) {
                message(player, "core_rematch_cost");
                return;
            }
        }
        server.getChunkAt(arena);
        var warden = AeronauticsInnerworld.CORE_WARDEN.get().create(server);
        warden.setPos(arena.getX() + .5, arena.getY(), arena.getZ() + .5);
        warden.setHome(arena, worldPosition);
        if (!server.noCollision(warden) || !server.addFreshEntity(warden)) {
            message(player, "core_arena_blocked");
            return;
        }
        if (victory && !player.isCreative()) {
            int remaining = 4;
            for (var stack : player.getInventory().items) {
                if (stack.is(AeronauticsInnerworld.HARMONIC_CRYSTAL.get())) {
                    int used = Math.min(remaining, stack.getCount());stack.shrink(used);remaining -= used;
                    if (remaining == 0) break;
                }
            }
        }
        boss = warden.getUUID();
        missingTicks = 0;
        ventCooldown = 0;
        warden.recordParticipant(player);
        message(player, "core_battle_start");
        setChanged();
    }

    public void tick() {
        if (!(level instanceof ServerLevel server) || boss == null || arena == null) return;
        if (ventCooldown > 0) {
            ventCooldown--;
            setChanged();
        }
        if (server.getEntity(boss) instanceof CoreWarden warden && warden.isAlive()) {
            missingTicks = 0;
            if (ventCooldown != 0) return;
            for (long packed : valves) {
                var pos = BlockPos.of(packed);
                var state = server.getBlockState(pos);
                if (state.is(Blocks.LEVER) && state.getValue(LeverBlock.POWERED)) {
                    server.setBlockAndUpdate(pos, state.setValue(LeverBlock.POWERED, false));
                    warden.exposeCore(160);
                    var player = server.getNearestPlayer(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 6, false);
                    if (player instanceof ServerPlayer participant) warden.recordParticipant(participant);
                    server.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1, .5f);
                    ventCooldown = 300;
                    setChanged();
                    break;
                }
            }
        } else {
            // Entity sections can arrive after the console during chunk restoration.
            server.getChunkAt(arena);
            if (++missingTicks >= 200) {
                boss = null;
                missingTicks = 0;
                setChanged();
            }
        }
    }

    public void defeated(UUID identity) {
        if (identity.equals(boss)) {
            boss = null;
            victory = true;
            setChanged();
        }
    }

    private boolean[] readNodes(ServerLevel server) {
        var states = new boolean[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            var pos = BlockPos.of(nodes[i]);
            server.getChunkAt(pos);
            var state = server.getBlockState(pos);
            states[i] = state.is(Blocks.LEVER) && state.getValue(LeverBlock.POWERED);
        }
        return states;
    }

    private Component nodeName(int index) {
        return Component.translatable("maze.aeronautics_innerworld.node_" + (index + 1));
    }

    public void showStatus(ServerPlayer player) {
        if (!(level instanceof ServerLevel server) || arena == null || nodes.length != 3) {
            message(player, "core_console_unlinked");return;
        }
        var states = readNodes(server);
        int restored = 0;
        for (boolean active : states) if (active) restored++;
        String mode = boss != null ? "active" : victory ? "cleared" : restored == 3 ? "ready" : "locked";
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.core_console_status", restored,
                Component.translatable("message.aeronautics_innerworld.core_console_" + mode)).withStyle(ChatFormatting.GRAY), false);
        for (int i = 0; i < states.length; i++) {
            var pos = BlockPos.of(nodes[i]);
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.core_node_status", nodeName(i),
                    Component.translatable("message.aeronautics_innerworld.core_node_" + (states[i] ? "restored" : "inactive")),
                    pos.getX(), pos.getY(), pos.getZ()).withStyle(states[i] ? ChatFormatting.GREEN : ChatFormatting.GOLD), false);
        }
        if (boss != null) player.displayClientMessage(coolingStatus(), false);
        else message(player, victory ? "core_rematch" : "core_console_inspect_hint");
    }

    public Component coolingStatus() {
        if (ventCooldown == 0) return Component.translatable("message.aeronautics_innerworld.core_cooling_ready").withStyle(ChatFormatting.AQUA);
        boolean queued = false;
        for (long packed : valves) {
            var pos = BlockPos.of(packed);
            if (level != null && level.hasChunkAt(pos)) {
                var state = level.getBlockState(pos);
                queued |= state.is(Blocks.LEVER) && state.getValue(LeverBlock.POWERED);
            }
        }
        return Component.translatable("message.aeronautics_innerworld.core_cooling_" + (queued ? "queued" : "recharging"),
                (ventCooldown + 19) / 20).withStyle(ChatFormatting.GOLD);
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld." + key), false);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLongArray("Nodes", nodes);tag.putLongArray("Gates", gates);tag.putLongArray("Valves", valves);
        if (arena != null) tag.putLong("Arena", arena.asLong());
        if (boss != null) tag.putUUID("Boss", boss);
        tag.putBoolean("Victory", victory);tag.putInt("VentCooldown", ventCooldown);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        nodes = tag.getLongArray("Nodes");gates = tag.getLongArray("Gates");valves = tag.getLongArray("Valves");
        arena = tag.contains("Arena") ? BlockPos.of(tag.getLong("Arena")) : null;
        boss = tag.hasUUID("Boss") ? tag.getUUID("Boss") : null;
        victory = tag.getBoolean("Victory");ventCooldown = Math.clamp(tag.getInt("VentCooldown"), 0, 300);
    }
}
