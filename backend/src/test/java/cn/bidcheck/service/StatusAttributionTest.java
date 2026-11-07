package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.NonContractModeScenario;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;

class StatusAttributionTest {
    private Finding finding(String id,String status,Map<String,Object> atomic) {
        Checkpoint cp=new Checkpoint(id,id,"","HIGH","VALUE_COMPARE","totalPrice","","","",true,true,true,null);
        return new Finding(id,"run",cp,new Evaluation(status,"原始判断","",Collections.emptyList(),0,"RULES"),fields("atomicCheck",atomic));
    }
    @Test void noExecutableTargetBecomesUserAction(){
        List<Map<String,Object>> gaps=new ArrayList<>();
        for(String code:Arrays.asList("FORM_CONDITION_COVERAGE_REQUIRED","VISUAL_MARK_UNREADABLE","DIRECT_SIGNER_EXEMPTION_PROOF_REQUIRED"))
            gaps.add(fields("code",code));
        Finding f=finding("form","UNDETERMINED",fields("gaps",gaps));
        Map<String,Object> job=fields("semanticGate",fields("reasonCode","NO_EXECUTABLE_EVIDENCE_TARGET"));
        Map<String,Object> result=BusinessResultPresentation.describe(f.evaluation(),f.assessment(),job);
        assertEquals("USER_ACTION",result.get("state"));
        String next=result.get("nextStep").toString();
        for(String word:Arrays.asList("成员","原图","直接签署"))assertTrue(next.contains(word),next);
        job.put("id","failed-job");job.put("status","FAILED");
        assertEquals("SYSTEM_INCOMPLETE",BusinessResultPresentation.describe(f.evaluation(),f.assessment(),job).get("state"));
    }
    @Test void missingQuoteVolumePropagatesToQuoteChecks(){
        Map<String,Object> missing=fields("code","BID_VOLUME_NOT_UPLOADED");
        Map<String,Object> condition=fields("gaps",Collections.singletonList(missing),"proof",fields("requestedVolume","报价文件"));
        Finding volume=finding("volume","UNDETERMINED",fields("conditions",Collections.singletonList(condition)));
        Finding price=finding("price","UNDETERMINED",fields("gaps",Collections.singletonList(fields("code","PRICE_EVIDENCE_REQUIRED"))));
        Finding adjusted=RunVolumeDependencies.apply(price,CheckResolution.map(RunVolumeDependencies.inventory(Arrays.asList(price,volume))));
        assertEquals("MATERIAL_REQUIRED",BusinessResultPresentation.describe(adjusted.evaluation(),adjusted.assessment()).get("state"));
        assertEquals(Arrays.asList("volume"),CheckResolution.maps(CheckResolution.atomic(adjusted).get("gaps")).get(1).get("volumeCheckpointIds"));
        assertSame(price,RunVolumeDependencies.apply(price,Collections.emptyMap()));
    }
    @Test void evidenceBasedFailStaysRisk(){
        Finding f=finding("price","FAIL",fields("gaps",Collections.singletonList(fields("code","PRICE_EVIDENCE_REQUIRED"))));
        Finding retained=RunVolumeDependencies.apply(f,fields("报价文件",Arrays.asList("volume")));
        assertSame(f,retained);
        assertEquals("RISK",BusinessResultPresentation.describe(retained.evaluation(),retained.assessment()).get("state"));
    }
    @Test void nonContractFactWithContractModeIsProcessedNormally(){
        NonContractModeScenario.verify();
    }
    @Test void missingVolumeReasonNamesTheVolume(){
        for(String title:Arrays.asList("第三分册 报价文件","第四分册 技术附件")){
            Map<String,Object> gap=fields("code","BID_VOLUME_NOT_UPLOADED");
            Map<String,Object> condition=fields("gaps",Collections.singletonList(gap),"proof",fields("requestedVolume",title));
            Finding f=finding("item","UNDETERMINED",fields("gaps",Collections.singletonList(gap),"conditions",Collections.singletonList(condition)));
            Map<String,Object> display=BusinessResultPresentation.describe(f.evaluation(),f.assessment());
            assertEquals("MATERIAL_REQUIRED",display.get("state"));assertTrue(display.get("reason").toString().contains("未上传《"+title+"》"));
            assertFalse(display.get("reason").toString().contains("不代表"));assertEquals("请补充该分册后重新检测。",display.get("nextStep"));
        }
    }
    @Test void userActionNextStepNamesWhatToConfirm(){
        Map<String,Object> gap=fields("code","CONTRACT_SIGNING_DATE_CONFLICT","description","合同双方签署日不同");
        Map<String,Object> card=fields("status","NEEDS_CONFIRMATION","claim","合同签订日期以 2022-04-25 还是 2022-04-24 为准？",
            "evidences",Collections.singletonList(fields("page",287,"locationType","PAGE")));
        Map<String,Object> atomic=fields("gaps",Collections.singletonList(gap),"proof",fields("pendingConfirmationCount",1),"inferredFacts",Collections.singletonList(card));
        Finding f=finding("conflict","UNDETERMINED",atomic);
        Map<String,Object> result=BusinessResultPresentation.describe(f.evaluation(),f.assessment());
        assertEquals("USER_ACTION",result.get("state"));
        for(String token:Arrays.asList("第287页","2022-04-25","2022-04-24"))assertTrue(result.get("nextStep").toString().contains(token));
        card.put("status","CONFIRMED");atomic.put("conditions",Collections.singletonList(fields("title","承诺函指定位置的签章","status","UNDETERMINED",
            "evidences",Collections.singletonList(fields("page",31,"locationType","PAGE")))));
        f=finding("other","NEEDS_CONFIRMATION",atomic);result=BusinessResultPresentation.describe(f.evaluation(),f.assessment());
        assertTrue(result.get("nextStep").toString().contains("承诺函指定位置的签章（第31页）"));assertFalse(result.get("nextStep").toString().contains("2022-04-25"));
    }
    @Test void extraPersonnelWithoutACardAsksForTheReferencedStaffingBaseline(){
        Map<String,Object> gap=fields("code","SCORING_BASE_CONFIGURATION_REQUIRED","description","须先闭合本次招标人员配置");
        Map<String,Object> proof=fields("executor","source-extra-personnel-score-1","baseRequirementSources",Arrays.asList(fields("location",70,"locationType","PARAGRAPH"),fields("location",438,"locationType","BLOCK")),
            "baseConfiguration",fields("gaps",Collections.singletonList(fields("code","SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED"))));
        Finding f=finding("scoring","UNDETERMINED",fields("gaps",Collections.singletonList(gap),"conditions",Collections.singletonList(fields("proof",proof))));
        Map<String,Object> result=BusinessResultPresentation.describe(f.evaluation(),f.assessment(),fields("semanticGate",fields("reasonCode","NO_EXECUTABLE_EVIDENCE_TARGET")));
        assertEquals("USER_ACTION",result.get("state"));for(String word:Arrays.asList("第70段","第438段","基础岗位","人数","资格条件"))assertTrue(result.get("nextStep").toString().contains(word));
    }
    @Test void publishedFindingReplacesGenericVisibleCopyAndRetainsOriginalEvaluation(){
        Map<String,Object> missing=fields("code","BID_VOLUME_NOT_UPLOADED","requestedVolume","第三分册 报价文件");
        Evaluation raw=new Evaluation("UNDETERMINED","本次检查尚未定位到足以支持判断的相关证据，不代表原文件缺少材料。","等待",Collections.emptyList(),0.65,"RULES");
        Finding material=finding("volume","UNDETERMINED",fields("gaps",Collections.singletonList(missing)));
        Finding source=new Finding(material.id(),material.runId(),material.checkpoint(),raw,material.assessment());
        Finding view=CheckResolution.attach(source,Collections.emptyMap());Map<String,Object> bp=CheckResolution.map(view.assessment().get("businessPresentation"));
        String visible=view.evaluation().reason()+" "+bp.get("reason")+" "+bp.get("nextStep");
        assertFalse(visible.contains("不代表原文件缺少材料"));assertTrue(visible.contains("报价"));assertEquals(bp.get("reason"),view.evaluation().reason());assertEquals(bp.get("nextStep"),view.evaluation().suggestion());
        assertEquals(raw,view.assessment().get("rawEvaluation"));assertEquals(raw.status(),view.evaluation().status());assertEquals(raw.confidence(),view.evaluation().confidence());
        Map<String,Object> card=fields("status","NEEDS_CONFIRMATION","claim","签订日期以哪一日为准？","evidences",Collections.singletonList(fields("page",40,"locationType","PAGE")));
        Finding conflict=finding("contract","UNDETERMINED",fields("proof",fields("pendingConfirmationCount",1),"inferredFacts",Collections.singletonList(card)));
        raw=new Evaluation("UNDETERMINED","仍有需要人工确认的范围，请结合条件说明和原文核对。","",Collections.emptyList(),0.3,"RULES");
        view=CheckResolution.attach(new Finding(conflict.id(),conflict.runId(),conflict.checkpoint(),raw,conflict.assessment()),Collections.emptyMap());
        bp=CheckResolution.map(view.assessment().get("businessPresentation"));visible=view.evaluation().reason()+" "+bp.get("reason")+" "+bp.get("nextStep");
        assertFalse(visible.contains("仍有需要人工确认的范围"));assertEquals(bp.get("reason"),view.evaluation().reason());assertTrue(visible.contains("第40页"));
        assertEquals(raw,view.assessment().get("rawEvaluation"));assertEquals(raw.evidences(),view.evaluation().evidences());
    }
}
