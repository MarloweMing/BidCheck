package cn.bidcheck.web;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.word.WordRenderingService;
import cn.bidcheck.service.BidService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static cn.bidcheck.service.BidService.fields;

/** Read-only, on-demand structure and derivative previews. Existing evidence block numbers never change. */
@RestController
@RequestMapping("/api/documents")
public final class DocumentStructureController {
    private final BidService bids;
    private final WordRenderingService words;
    public DocumentStructureController(BidService bids,WordRenderingService words){this.bids=bids;this.words=words;}

    @GetMapping("/{id}/pages/{page}/layout")
    public Object pageLayout(@PathVariable String id,@PathVariable int page){
        Document doc=bids.document(id);
        Page source=doc.pages().stream().filter(p->p.number()==page).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"原文位置不存在"));
        return fields("documentId",id,"documentHash",doc.sha256(),"documentVersion",doc.version(),"location",page,"locationType",source.locationType(),
            "available",source.layout()!=null,"layout",source.layout()==null?cn.bidcheck.compat.Java8Api.mapOf("status", "UNAVAILABLE", "warnings", cn.bidcheck.compat.Java8Api.listOf("当前文件版本未保存结构化OCR结果；文字转录和原文仍可查看")):source.layout().toMap());
    }

    @GetMapping("/{id}/word-layout")
    public Object wordLayout(@PathVariable String id)throws IOException{
        Document doc=word(id);Map<String,Object> result=new LinkedHashMap<>(words.layout(doc,bids.path(id)));
        result.put("documentId",doc.id());result.put("documentHash",doc.sha256());result.put("documentVersion",doc.version());return result;
    }

    @GetMapping(value="/{id}/rendered-pages/{page}/image",produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> renderedPage(@PathVariable String id,@PathVariable int page)throws IOException{
        if(page<1)throw BidService.bad("渲染页码无效");Document doc=word(id);requireRendering(doc);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(words.renderPage(doc,bids.path(id),page));
    }

    @GetMapping("/{id}/rendered-pdf")
    public ResponseEntity<FileSystemResource> renderedPdf(@PathVariable String id)throws IOException{
        Document doc=word(id);requireRendering(doc);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(doc.name()+"-渲染副本.pdf",StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(words.renderedPdf(doc,bids.path(id))));
    }
    private Document word(String id){Document doc=bids.document(id);if(!doc.name().toLowerCase(Locale.ROOT).endsWith(".docx"))throw BidService.bad("该文件不是Word文档");return doc;}
    private void requireRendering(Document doc)throws IOException{if(!Boolean.TRUE.equals(words.layout(doc,bids.path(doc.id())).get("usableForEvidence")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Word整页渲染尚不可用，请查看原生内容或下载原文件；许可证与字体状态见渲染详情");}
}
