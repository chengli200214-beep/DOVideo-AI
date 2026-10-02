package com.example.server.film;
import com.example.server.generation.GenerationArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FfmpegSafetyTest {
    @Test void mismatchedSourceIntegrityFailsBeforeProcessInvocationOrObjectUpload() throws Exception {
        var artifacts=mock(GenerationArtifactStore.class);
        doAnswer(call -> { Files.write(call.getArgument(1),new byte[]{1,2,3}); return null; }).when(artifacts).copyArtifact(anyString(),any());
        var engine=new FfmpegCompositionRenderer(artifacts,new ObjectMapper(),"missing-ffmpeg-directory","");
        var clip=new FilmSpec.Clip("version","shot","task","title","","","private-key",4,"not-matching");
        var task=new CompositionRepository.Task("job","project",1,"hash","{}","RENDERING",1,null,null,null,null,null,"lease",Long.MAX_VALUE,1,1);
        var error=assertThrows(CompositionRenderer.Failure.class,()->engine.render(task,new FilmSpec(1,1,720,1280,false,List.of(clip,clip))));
        assertEquals("INPUT_INTEGRITY_FAILED",error.code()); verify(artifacts,never()).saveFile(anyString(),any());
    }
    @Test void captionsRemainLiteralUnicodeAndAreWrappedWithoutFilterEscapingOrExpansion() {
        String text="100% %{eif:1:d} '中文' \\ 路径\n第二行";
        var caption=FfmpegCompositionRenderer.wrapCaption(text,10);
        assertTrue(caption.contains("中文")); assertTrue(caption.contains("%")); assertTrue(caption.contains("\\"));
        assertTrue(caption.contains("\n")); assertFalse(caption.contains("\r"));
        assertEquals(120,FfmpegCompositionRenderer.wrapCaption("字".repeat(200),200).codePointCount(0,120));
    }
}
