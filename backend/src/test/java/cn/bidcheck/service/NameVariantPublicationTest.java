package cn.bidcheck.service;

import cn.bidcheck.consistency.ConsistencyEngine;
import cn.bidcheck.consistency.ConsistencyRunSupport;
import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NameVariantPublicationTest {
    @Test void reviewingAGroupPreservesItsSourceBoundNameQuestion() {
        Document document=new Document("bid","p","BID","bid.pdf",1,"hash","",Arrays.asList(
            new Page(12,"本次投标\n项目负责人：王明","PAGE","PDFBOX_NATIVE",""),
            new Page(17,"本次投标\n项目负责人：王鸣","PAGE","PDFBOX_OCR","")),Collections.emptyList());
        Map<String,Object> report=new ConsistencyEngine().scan(document,Collections.emptyList());
        Map<String,Object> group=ConsistencyRunSupport.maps(report.get("groups")).stream()
            .filter(g->g.containsKey("nameVariantAttribution")).findFirst().orElseThrow(AssertionError::new);
        group.put("findingId","finding");
        Checkpoint checkpoint=new Checkpoint("checkpoint","姓名差异","全文信息检查","HIGH","FULL_TEXT_REVIEW", "fullTextConsistency","REVIEW","","",true,true,true,null);
        Evaluation original=new Evaluation("WARNING","原始差异","",Collections.emptyList(),0.6,"FULL_TEXT_CONSISTENCY");
        Finding finding=new Finding("finding","run",checkpoint,original,Collections.emptyMap());
        Finding reviewed=BidService.reviewedConsistencyFinding(finding,group);
        assertEquals("NEEDS_CONFIRMATION",reviewed.evaluation().status());
        assertEquals("USER_ACTION",CheckResolution.map(reviewed.assessment().get("businessPresentation")).get("state"));
        assertTrue(reviewed.evaluation().suggestion().contains("第 17 页"));
        assertTrue(reviewed.assessment().containsKey("nameVariantAttribution"));
    }
}
