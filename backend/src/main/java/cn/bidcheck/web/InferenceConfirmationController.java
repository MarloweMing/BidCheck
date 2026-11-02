package cn.bidcheck.web;

import cn.bidcheck.service.InferenceConfirmationService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Decisions concern an evidence relationship, never a direct override of a verdict. */
@RestController
@RequestMapping("/api/runs/{runId}/inference-confirmations")
public class InferenceConfirmationController {
    private final InferenceConfirmationService service;
    public InferenceConfirmationController(InferenceConfirmationService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable String runId){return service.list(runId);}
    @PostMapping("/{id}/decision") public Map<String,Object> decide(@PathVariable String runId,@PathVariable String id,@RequestBody Map<String,Object> body){return service.decide(runId,id,body);}
    @PostMapping(value="/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> attachment(@PathVariable String runId,@PathVariable String id,@RequestParam MultipartFile file,@RequestParam String note,@RequestParam int expectedRevision) throws IOException {
        return service.appendAttachment(runId,id,file,note,expectedRevision);
    }
    @GetMapping("/export") public ResponseEntity<Map<String,Object>> export(@PathVariable String runId){
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("推断确认记录.json",StandardCharsets.UTF_8).build().toString())
            .body(service.exportRecords(runId));
    }
}
