package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class VisualFieldConsumptionTest {
    private Document doc(String body){return new Document("bid","project","BID","fixture.pdf",1,"sha","",List.of(new Page(1,body,"PAGE","PDF","")),List.of());}
    private Map<String,Object> observation(){return linked("id","v","documentId","bid","location",1,"locationType","PAGE","imageHash","img","type","FIELD","label","姓名","value","张甲","readability","CLEAR","sourceBbox",List.of(1,1,20,20),"modelLimitations",List.of("仅基于单页图像识别，未验证证书真伪或有效性"),"source",Map.of("documentHash","sha","renderedSha256","img","documentVersion",1,"page",1));}
    private Rule role(){return new Rule("r","项目负责人","PERSON_ROLE","项目负责人","姓名","","","",Map.of(),"项目负责人","",List.of());}
    @Test void clearObservationBecomesLiteralFactWithoutInventingSubject(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("本项目人员配置")));c.addVisualObservations(List.of(observation()));List<Source> visual=c.all.stream().filter(s->s.id().startsWith("V")).toList();assertEquals(1,visual.size());Fact f=c.nativeFacts(List.of(role()),visual).get(0);assertEquals("FIELD",f.kind());assertEquals("UNRESOLVED",f.scope());assertEquals("",f.subject());assertNull(new ProofVerifier().issue(f,c.byId));assertEquals("UNDETERMINED",new RuleEvaluator(List.of(f),c,Map.of()).evaluate(role()).status());}
    @Test void modelCannotTurnVisualLabelIntoPersonRole(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("本项目人员配置")));var o=observation();o.put("label","项目负责人");c.addVisualObservations(List.of(o));Source v=c.all.stream().filter(s->s.id().startsWith("V")).findFirst().orElseThrow();Fact f=new Fact("x","PERSON_ROLE","项目负责人","姓名","张甲","张甲","","","CURRENT_PROJECT",List.of(v.id()),Map.of());assertEquals("VISUAL_FIELD_RELATION_REQUIRES_ORIGINAL_SOURCE",new ProofVerifier().issue(f,c.byId));}
    @Test void wrongVersionAndWrongCoordinatesRemainRejected(){for(Object replacement:List.of(Map.of("documentHash","sha","renderedSha256","img","documentVersion",2),Map.of("documentHash","wrong","renderedSha256","img"))){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("正文")));var o=observation();o.put("source",replacement);c.addVisualObservations(List.of(o));assertTrue(c.all.stream().noneMatch(s->s.id().startsWith("V")));}}
    @Test void repeatedObservationDoesNotDuplicateSource(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("正文")));c.addVisualObservations(List.of(observation(),observation()));assertEquals(1,c.all.stream().filter(s->s.id().startsWith("V")).count());}
    @Test void ordinarySealPassesWithAuthenticityDisclaimerButNotWrongDocument(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("本项目授权书\n甲公司授权书盖章位置")));Rule r=new Rule("r","甲公司授权书盖章","VISUAL","甲公司","seal","","","",Map.of("documentType","授权书"),"甲公司授权书盖章","",List.of());var o=observation();o.put("obligationId","r");o.put("type","SEAL");o.put("label","印章");o.put("value","甲公司");o.put("observedValue","甲公司");o.put("modelLimitations",List.of("无法确认印章真伪及法律效力"));assertEquals("PASS",new RuleEvaluator(List.of(),c,Map.of("visualObservations",List.of(o))).evaluate(r).status());o.put("obligationId","different");assertEquals("UNDETERMINED",new RuleEvaluator(List.of(),c,Map.of("visualObservations",List.of(o))).evaluate(r).status());}
}
