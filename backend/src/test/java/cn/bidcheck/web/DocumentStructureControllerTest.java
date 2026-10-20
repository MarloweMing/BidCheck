package cn.bidcheck.web;

import cn.bidcheck.document.word.WordRenderingService;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.service.BidService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentStructureControllerTest {
    @TempDir Path temp;BidService bids;WordRenderingService words;MockMvc http;Document doc;
    @BeforeEach void setup()throws Exception{
        bids=mock(BidService.class);words=mock(WordRenderingService.class);doc=new Document("word","project","BID","投标.docx",2,"a".repeat(64),"",List.of(new Page(7,"原内容块","PARAGRAPH","WORD_NATIVE","")),List.of());
        when(bids.document("word")).thenReturn(doc);when(bids.path("word")).thenReturn(temp.resolve("input.docx"));
        http=MockMvcBuilders.standaloneSetup(new DocumentStructureController(bids,words)).build();
    }
    @Test void oldVersionLayoutExplicitlyUnavailable()throws Exception{http.perform(get("/api/documents/word/pages/7/layout")).andExpect(status().isOk()).andExpect(jsonPath("$.location").value(7)).andExpect(jsonPath("$.locationType").value("PARAGRAPH")).andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.layout.status").value("UNAVAILABLE"));verifyNoInteractions(words);}
    @Test void missingOriginalPositionIsNotTreatedAsRenderedPage()throws Exception{http.perform(get("/api/documents/word/pages/1/layout")).andExpect(status().isNotFound());verifyNoInteractions(words);}
    @Test void missingLicenseReturnsHonestStatusAndPreventsPageRendering()throws Exception{
        when(words.layout(eq(doc),any())).thenReturn(Map.of("status","LICENSE_REQUIRED","usableForEvidence",false));
        http.perform(get("/api/documents/word/word-layout")).andExpect(status().isOk()).andExpect(jsonPath("$.documentVersion").value(2)).andExpect(jsonPath("$.status").value("LICENSE_REQUIRED"));
        http.perform(get("/api/documents/word/rendered-pages/1/image")).andExpect(status().isConflict());verify(words,never()).renderPage(any(),any(),anyInt());
    }
    @Test void licensedDerivativeUsesSeparateEndpointAndDoesNotCacheStaleRender()throws Exception{
        when(words.layout(eq(doc),any())).thenReturn(Map.of("status","COMPLETE","usableForEvidence",true));when(words.renderPage(eq(doc),any(),eq(1))).thenReturn(new byte[]{1,2,3});
        http.perform(get("/api/documents/word/rendered-pages/1/image")).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control","no-store"));
        Assertions.assertEquals(7,doc.pages().get(0).number());Assertions.assertEquals("PARAGRAPH",doc.pages().get(0).locationType());
    }
}
