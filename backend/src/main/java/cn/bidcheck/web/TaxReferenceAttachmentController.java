package cn.bidcheck.web;

import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.service.BidService;
import cn.bidcheck.service.CheckResolution;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Upload from a source-bound tax query card into the existing scoped supplement workflow. */
@RestController
@RequestMapping("/api/runs/{runId}/checkpoints/{checkpointId}/tax-reference-attachments")
public class TaxReferenceAttachmentController {
    private final BidService bids;
    private final Store store;
    public TaxReferenceAttachmentController(BidService bids,Store store){this.bids=bids;this.store=store;}

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@PathVariable String runId,@PathVariable String checkpointId,
            @RequestParam String sourceId,@RequestParam String note,@RequestParam MultipartFile file) throws IOException {
        Finding finding=store.get("finding",BidService.stableId(runId+":"+checkpointId),Finding.class);
        if(!runId.equals(finding.runId())||!checkpointId.equals(finding.checkpoint().id())||sourceId.trim().isEmpty()
                ||!hasSource(CheckResolution.maps(CheckResolution.atomic(finding).get("conditions")),sourceId)){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"当前检查项上没有这个税务截图确认请求");
        }
        return bids.uploadSupplement(runId,checkpointId,"",file,note);
    }
    static boolean hasSource(List<Map<String,Object>> conditions,String id){
        for(Map<String,Object> condition:conditions){
            Map<String,Object> proof=CheckResolution.map(condition.get("proof"));
            Map<String,Object> card=CheckResolution.map(proof.get("taxReferenceConfirmation"));
            if("TAX_REFERENCE_DATE".equals(card.get("type"))&&Boolean.FALSE.equals(card.get("businessProof"))
                    &&CheckResolution.maps(card.get("sources")).stream().anyMatch(s->id.equals(s.get("sourceId"))))return true;
            if(hasSource(CheckResolution.maps(proof.get("children")),id))return true;
        }
        return false;
    }
}
