package cn.bidcheck.verification;

import cn.bidcheck.service.BidService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class VerificationController {
    private final VerificationService service;
    private final VerificationReportService reports;
    public VerificationController(VerificationService service,VerificationReportService reports) {this.service=service;this.reports=reports;}
    @GetMapping("/verification-config") public Object configuration() {return service.configuration();}
    @GetMapping("/projects/{project}/verification-runs") public Object runs(@PathVariable String project) {return service.list(project);}
    @PostMapping("/projects/{project}/verification-runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object start(@PathVariable String project,@RequestBody Map<String,Object> body) {return service.start(project,body);}
    @GetMapping("/verification-runs/{id}") public Object detail(@PathVariable String id) {return service.detail(id);}
    @GetMapping("/verification-runs/{id}/facts") public Object facts(@PathVariable String id) {return service.facts(id);}
    @GetMapping("/verification-runs/{id}/obligations") public Object obligations(@PathVariable String id) {
        return VerificationService.maps(service.detail(id).get("results")).stream().flatMap(r->VerificationService.maps(r.get("obligations")).stream()
            .map(o->{Map<String,Object> copy=new LinkedHashMap<>(o);copy.put("checkpointId",r.get("checkpointId"));copy.put("checkpointTitle",r.get("title"));return copy;})).toList();
    }
    @GetMapping("/verification-runs/{id}/gaps") public Object gaps(@PathVariable String id) {
        return VerificationService.maps(service.detail(id).get("results")).stream().flatMap(r->VerificationService.maps(r.get("gaps")).stream()
            .map(g->{Map<String,Object> copy=new LinkedHashMap<>(g);copy.put("checkpointId",r.get("checkpointId"));copy.put("checkpointTitle",r.get("title"));return copy;})).toList();
    }
    @GetMapping("/verification-runs/{id}/obligations/{checkpoint}/proof")
    public Object proof(@PathVariable String id,@PathVariable String checkpoint) {return service.proof(id,checkpoint);}
    @PostMapping("/verification-runs/{id}/revisions") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object revise(@PathVariable String id,@RequestBody Map<String,Object> body) {return service.revise(id,body);}
    @PostMapping(value="/projects/{project}/verification-inputs",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@PathVariable String project,@RequestParam String runId,@RequestParam String role,
            @RequestParam String purpose,@RequestParam MultipartFile file) throws IOException {
        return service.uploadInput(project,runId,role,purpose,file);
    }
    @PostMapping("/projects/{project}/verification-external-records") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object external(@PathVariable String project,@RequestBody Map<String,Object> body) {return service.external(project,body);}
    @GetMapping("/documents/{id}/layout") public Object layout(@PathVariable String id) throws IOException {return service.layout(id);}
    @GetMapping("/verification-runs/{id}/report") public ResponseEntity<byte[]> report(@PathVariable String id,@RequestParam(defaultValue="html") String format) {
        var report=reports.generate(id,format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(report.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("BidCheck-Automation-"+id+"."+format,StandardCharsets.UTF_8).build().toString()).body(report.bytes());
    }
}
