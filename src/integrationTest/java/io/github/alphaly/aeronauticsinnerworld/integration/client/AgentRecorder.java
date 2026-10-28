package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

@EventBusSubscriber(modid="aeronautics_innerworld_checks",value=Dist.CLIENT)
public final class AgentRecorder {
    private static final ArrayBlockingQueue<NativeImage> frames=new ArrayBlockingQueue<>(3);
    private static volatile boolean running;
    private static volatile String status="idle";
    private static long nextFrame;
    private static Thread writer;
    static String status(){return status;}
    static String start(Minecraft client,String name)throws Exception {
        if(writer!=null&&writer.isAlive())throw new IllegalStateException("Previous recording is still active");
        if(!name.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid recording name");
        var path=MinecraftAgent.directory().resolve("recordings").resolve(name+".mp4");Files.createDirectories(path.getParent());
        if(Files.exists(path))throw new IllegalStateException("Recording already exists");
        var ffmpeg=new ProcessBuilder(System.getProperty("innerworld.ffmpeg","ffmpeg"),"-hide_banner","-loglevel","warning","-f","image2pipe","-framerate","10","-vcodec","png","-i","pipe:0","-an","-vf","scale=1280:-2","-c:v","libx264","-preset","veryfast","-crf","22","-pix_fmt","yuv420p","-movflags","frag_keyframe+empty_moov+default_base_moof",path.toString())
                .redirectError(path.resolveSibling(name+".ffmpeg.log").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        running=true;nextFrame=0;status="recording "+path.getFileName();
        writer=new Thread(()->{
            long count=0;
            try(var output=ffmpeg.getOutputStream()) {
                while(running||!frames.isEmpty()) {
                    var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame==null)continue;
                    try(frame){output.write(frame.asByteArray());count++;}
                }
                output.close();if(!ffmpeg.waitFor(15,TimeUnit.SECONDS))ffmpeg.destroy();
                status="saved "+path.getFileName()+" ("+count+" frames)";
            }catch(Exception e){running=false;status="failed: "+e.getMessage();ffmpeg.destroy();}
            finally{NativeImage image;while((image=frames.poll())!=null)image.close();}
        },"innerworld-recording");writer.setDaemon(true);writer.start();return path.toString();
    }
    static void stop(){running=false;}
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if(!MinecraftAgent.enabled()||!running||Minecraft.getInstance().isPaused())return;
        long now=System.nanoTime();if(now<nextFrame||frames.remainingCapacity()==0)return;nextFrame=now+100_000_000L;
        var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        if(!frames.offer(image))image.close();
    }
}
