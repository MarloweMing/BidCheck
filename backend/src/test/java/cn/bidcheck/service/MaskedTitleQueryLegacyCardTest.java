package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaskedTitleQueryLegacyCardTest {
    @Test void oldDecisionHidesOnlyTheSameNameCellAndStaleCardCannotOverrideIt(){
        InferenceConfirmationServiceTest f=new InferenceConfirmationServiceTest();
        String role="拟在本合同任职 项目负责人\n姓名 李明扬",row="| 姓名 | 李*扬 |",query="职称评审信息查询\n"+row+"\n| 证书编号 | 20***16 |\n| 职称名称级别 | 工程师 |";
        Document bid=new Document("bid","project","BID","a.pdf",1,"hash","",Arrays.asList(new Page(1,role,"PAGE","TEXT",""),
            new Page(2,query,"PAGE","OCR",""),new Page(3,query,"PAGE","OCR","")),Collections.emptyList());when(f.bids.document("bid")).thenReturn(bid);
        for(int i=0;i<3;i++){
            int page=i==2?3:2;String block=i==0?"职称评审信息查询\n"+row:"李*扬 |\n| 证书编号";
            f.service.capture("run","cp",bid,f.tender,fields("id","legacy"+i,"claim","identity-"+i,"inferenceType","MASKED_PERSON_IDENTITY",
                "proposedFact",fields("kind","RELATION","field","同一人","subject","李明扬","value","李*扬","attributes",fields("number",i==0?"":"20***16")),
                "evidences",Arrays.asList(ref("r",1,role),ref("chunk"+i,page,block))));
        }
        Map<String,Object> old=f.store.maps("inference_claim","project","run").stream().filter(r->"legacy0".equals(r.get("engineClaimId"))).findFirst().get();
        old.put("status","CONFIRMED");old.put("revision",1);f.store.save("inference_claim",text(old,"id"),"project","run",old);
        List<Map<String,Object>> cards=f.service.list("run");assertEquals(2,cards.size());
        assertTrue(cards.stream().anyMatch(c->"CONFIRMED".equals(c.get("status"))&&"legacy0".equals(c.get("engineClaimId"))));
        assertTrue(cards.stream().anyMatch(c->"NEEDS_CONFIRMATION".equals(c.get("status"))&&"legacy2".equals(c.get("engineClaimId"))));
        String stale=text(f.store.maps("inference_claim","project","run").stream().filter(c->"legacy1".equals(c.get("engineClaimId"))).findFirst().get(),"id");
        assertThrows(RuntimeException.class,()->f.service.decide("run",stale,f.decision("REJECTED")));
        assertEquals(0,f.store.maps("inference_decision","project",null).size());
    }
    private static Map<String,Object> ref(String id,int page,String quote){return fields("sourceId",id,"documentId","bid","documentHash","hash","documentVersion",1,"locationType","PAGE","page",page,"quote",quote);}
}
