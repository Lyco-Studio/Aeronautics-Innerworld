package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

final class AgentBaritone {
    private static Object primary()throws ReflectiveOperationException {
        var provider=Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
    }
    static Object execute(String command)throws ReflectiveOperationException {
        var manager=Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(primary());
        return Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute",String.class).invoke(manager,command);
    }
    static boolean busy() {
        try {
            var p=primary();var type=Class.forName("baritone.api.IBaritone");
            for(String name:List.of("getMineProcess","getBuilderProcess","getCustomGoalProcess","getGetToBlockProcess","getExploreProcess")) {
                var process=type.getMethod(name).invoke(p);
                if(name.equals("getBuilderProcess") && (Boolean)Class.forName("baritone.api.process.IBuilderProcess").getMethod("isPaused").invoke(process))continue;
                if((Boolean)Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive").invoke(process))return true;
            }
        }catch(ReflectiveOperationException ignored){}
        return false;
    }
    static String schematic(JsonObject input)throws Exception {
        String name=input.get("name").getAsString();if(!name.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid schematic name");
        int width=input.get("width").getAsInt(),height=input.get("height").getAsInt(),length=input.get("length").getAsInt();
        if(width<1||height<1||length<1||width*height*length>100000)throw new IllegalArgumentException("Schematic dimensions are too large");
        var palette=new LinkedHashMap<String,Integer>();palette.put("minecraft:air",0);var blocks=new int[width*height*length];
        for(var entry:input.getAsJsonArray("blocks")) {
            var o=entry.getAsJsonObject();int x=o.get("x").getAsInt(),y=o.get("y").getAsInt(),z=o.get("z").getAsInt();
            if(x<0||x>=width||y<0||y>=height||z<0||z>=length)throw new IllegalArgumentException("Block outside schematic");
            String state=o.get("state").getAsString();int id=palette.computeIfAbsent(state,k->palette.size());blocks[(y*length+z)*width+x]=id;
        }
        var data=new ByteArrayOutputStream();for(int id:blocks){while((id&~127)!=0){data.write((id&127)|128);id>>>=7;}data.write(id);}
        var tag=new CompoundTag();tag.putInt("Version",2);tag.putInt("DataVersion",SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.putShort("Width",(short)width);tag.putShort("Height",(short)height);tag.putShort("Length",(short)length);tag.putIntArray("Offset",new int[]{0,0,0});
        var colors=new CompoundTag();palette.forEach(colors::putInt);tag.put("Palette",colors);tag.putInt("PaletteMax",palette.size());tag.putByteArray("BlockData",data.toByteArray());
        var folder=MinecraftAgent.directory().getParent().resolve("schematics");Files.createDirectories(folder);var path=folder.resolve(name+".schem");NbtIo.writeCompressed(tag,path);return path.toString();
    }
}
