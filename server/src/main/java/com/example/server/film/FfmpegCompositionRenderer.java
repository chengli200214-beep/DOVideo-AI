package com.example.server.film;

import com.example.server.generation.GenerationArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Only downloaded, integrity-checked local MP4s enter FFmpeg. No shell or user filter expression is used. */
@Component
public class FfmpegCompositionRenderer implements CompositionRenderer {
    private final GenerationArtifactStore artifacts;
    private final ObjectMapper json;
    private final String ffmpeg,ffprobe;
    private final Path font;
    public FfmpegCompositionRenderer(GenerationArtifactStore artifacts,ObjectMapper json,
            @Value("${composition.ffmpeg-dir:${tool.ffmpeg.dir:}}") String directory,
            @Value("${composition.font-path:}") String fontPath) {
        this.artifacts=artifacts; this.json=json;
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String suffix=windows?".exe":"";
        ffmpeg=directory.isBlank()?"ffmpeg":Path.of(directory,"ffmpeg"+suffix).toAbsolutePath().toString();
        ffprobe=directory.isBlank()?"ffprobe":Path.of(directory,"ffprobe"+suffix).toAbsolutePath().toString();
        font=Path.of(fontPath.isBlank()?(windows?"C:/Windows/Fonts/msyh.ttc":"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"):fontPath);
    }
    public Map<String,Object> health() {
        boolean available=false; String version="";
        Path work=null;
        try {
            work=Files.createTempDirectory("aigc-tools-");
            run(List.of(ffprobe,"-version"),work,"probe.txt",System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
            run(List.of(ffmpeg,"-version"),work,"version.txt",System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
            version=Files.readAllLines(work.resolve("version.txt")).getFirst(); available=true;
        } catch(Exception ignored) { }
        finally { if(work!=null) cleanup(work); }
        return Map.of("available",available,"version",version,"captionsAvailable",available && Files.isRegularFile(font));
    }
    @Override public Result render(CompositionRepository.Task task,FilmSpec spec) throws Exception {
        if(spec.clips()==null || spec.clips().size()<2 || spec.clips().size()>8 || spec.width()<2 || spec.height()<2
                || spec.width()>1280 || spec.height()>1280) throw new Failure("INVALID_VIDEO");
        Path work=Files.createTempDirectory("aigc-film-"); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(900);
        try {
            if(spec.burnCaptions() && spec.clips().stream().anyMatch(clip -> clip.caption()!=null && !clip.caption().isBlank())) {
                if(!Files.isRegularFile(font)) throw new Failure("CAPTION_FONT_UNAVAILABLE");
                Files.copy(font,work.resolve("font.ttc"));
            }
            List<Map<String,Object>> sourceMetadata=new ArrayList<>();
            StringBuilder list=new StringBuilder();
            for(int i=0;i<spec.clips().size();i++) {
                if(System.nanoTime()>deadline) throw new Failure("RENDER_TIMEOUT");
                var clip=spec.clips().get(i); String source="source-"+i+".mp4",segment="segment-"+i+".mp4";
                Path input=work.resolve(source); artifacts.copyArtifact(clip.artifactKey(),input);
                if(Files.size(input)!=clip.artifactSize() || !hash(input).equals(clip.artifactSha256())) throw new Failure("INPUT_INTEGRITY_FAILED");
                var info=probe(input,work,"source-probe-"+i+".json",deadline);
                double duration=((Number)info.get("durationSeconds")).doubleValue();
                if(duration<0.1 || duration>60 || !Double.isFinite(duration)) throw new Failure("INVALID_VIDEO");
                sourceMetadata.add(Map.of("versionId",clip.versionId(),"durationSeconds",duration,"source",info));
                List<String> command=new ArrayList<>(List.of(ffmpeg,"-hide_banner","-v","error","-nostdin","-y","-threads","2",
                    "-protocol_whitelist","file,pipe","-f","mov","-i",source));
                boolean audio=(Boolean)info.get("hasAudio");
                if(!audio) command.addAll(List.of("-f","lavfi","-i","anullsrc=r=48000:cl=stereo"));
                String filter="scale="+spec.width()+":"+spec.height()+":force_original_aspect_ratio=decrease:force_divisible_by=2,pad="+spec.width()+":"+spec.height()+":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=24";
                if(spec.burnCaptions() && clip.caption()!=null && !clip.caption().isBlank()) {
                    Files.writeString(work.resolve("caption-"+i+".txt"),wrapCaption(clip.caption(),spec.width()<900?20:34),StandardCharsets.UTF_8);
                    filter+=",drawtext=fontfile=font.ttc:textfile=caption-"+i+".txt:expansion=none:fontsize=30:fontcolor=white:box=1:boxcolor=black@0.65:boxborderw=12:x=(w-text_w)/2:y=h-text_h-50";
                }
                command.addAll(List.of("-map","0:v:0","-map",audio?"0:a:0":"1:a:0","-vf",filter,"-filter_threads","2",
                    "-af","apad","-t",String.format(Locale.ROOT,"%.6f",duration),"-c:v","libx264","-preset","veryfast","-crf","23","-pix_fmt","yuv420p",
                    "-c:a","aac","-ar","48000","-ac","2","-movflags","+faststart","-fs","268435456",segment));
                run(command,work,"render-"+i+".log",deadline);
                if(Files.size(work.resolve(segment))>=256L*1024*1024) throw new Failure("OUTPUT_TOO_LARGE");
                list.append("file '").append(segment).append("'\n");
            }
            Files.writeString(work.resolve("segments.txt"),list,StandardCharsets.UTF_8);
            run(List.of(ffmpeg,"-hide_banner","-v","error","-nostdin","-y","-protocol_whitelist","file,pipe",
                "-f","concat","-safe","1","-i","segments.txt","-c","copy","-movflags","+faststart","-fs","268435456","film.mp4"),work,"concat.log",deadline);
            Path output=work.resolve("film.mp4"); var metadata=probe(output,work,"film-probe.json",deadline);
            if(((Number)metadata.get("width")).intValue()!=spec.width() || ((Number)metadata.get("height")).intValue()!=spec.height()) throw new Failure("INVALID_VIDEO");
            String key="composed/"+task.userId()+"/"+task.id()+"/"+task.leaseToken()+".mp4";
            var artifact=artifacts.saveFile(key,output);
            return new Result(artifact,json.writeValueAsString(Map.of("output",metadata,"inputs",sourceMetadata,"burnCaptions",spec.burnCaptions(),
                "audioPolicy","preserve available source audio; pad missing audio with silence","renderer","ffmpeg-h264-aac-v1")));
        } finally { cleanup(work); }
    }
    Map<String,Object> probe(Path file,Path work,String log,long deadline) throws Exception {
        run(List.of(ffprobe,"-v","error","-protocol_whitelist","file,pipe","-f","mov","-show_entries",
            "format=duration:stream=codec_type,codec_name,width,height","-of","json",file.toAbsolutePath().toString()),work,log,deadline);
        var node=json.readTree(Files.readString(work.resolve(log))); var streams=node.path("streams");
        com.fasterxml.jackson.databind.JsonNode video=null; boolean audio=false;
        for(var stream:streams) { if(stream.path("codec_type").asText().equals("video") && video==null) video=stream; if(stream.path("codec_type").asText().equals("audio")) audio=true; }
        if(video==null || video.path("width").asInt()<1 || video.path("width").asInt()>8192 || video.path("height").asInt()<1 || video.path("height").asInt()>8192) throw new Failure("INVALID_VIDEO");
        double duration=node.path("format").path("duration").asDouble(Double.NaN);
        if(!Double.isFinite(duration) || duration<=0) throw new Failure("INVALID_VIDEO");
        return Map.of("width",video.path("width").asInt(),"height",video.path("height").asInt(),"durationSeconds",duration,"videoCodec",video.path("codec_name").asText(),"hasAudio",audio);
    }
    private void run(List<String> args,Path work,String log,long deadline) throws Exception {
        Process process;
        try { process=new ProcessBuilder(args).directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve(log).toFile()).start(); }
        catch(java.io.IOException missing) { throw new Failure("FFMPEG_UNAVAILABLE"); }
        try {
            long millis=Math.min(90_000,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
            if(!process.waitFor(millis,TimeUnit.MILLISECONDS)) throw new Failure("RENDER_TIMEOUT");
            if(process.exitValue()!=0) throw new Failure("FFMPEG_COMMAND_FAILED");
        } finally { if(process.isAlive()) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } }
    }
    static String wrapCaption(String text,int columns) {
        String clean=text.replaceAll("[\\p{Cntrl}]"," ").trim(); StringBuilder out=new StringBuilder(); int index=0;
        for(int point:clean.codePoints().limit(120).toArray()) { if(index>0 && index%columns==0) out.append('\n'); out.appendCodePoint(point); index++; } return out.toString();
    }
    static String hash(Path file) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256"); try(var input=Files.newInputStream(file)) { byte[] buffer=new byte[8192]; int count; while((count=input.read(buffer))!=-1) hash.update(buffer,0,count); }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void cleanup(Path work) {
        try(var paths=Files.walk(work)) { for(var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch(java.io.IOException ignored) { }
    }
}
