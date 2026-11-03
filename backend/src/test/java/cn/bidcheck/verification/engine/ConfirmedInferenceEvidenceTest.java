package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedInferenceEvidenceTest {
    Document document(){return new Document("bid","project","BID","投标.txt",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(22,"本项目人员配置\n拟在本合同任职 项目负责人 张国超\n证书持有人 张*超","PAGE","NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());}
    Map<String,Object> record(Document doc){cn.bidcheck.verification.engine.VerificationTypes.Source source=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc)).all.get(0);return linked("id","claim","status","CONFIRMED","revision",1,"actor","审核人","decidedAt","2026-11-02","confirmationLedgerId","ledger","targetId","identity","targetFingerprint","rule-hash",
        "proposedFact",linked("kind","RELATION","subject","张国超","field","同一人","value","张*超","rawValue","张*超","scope","CURRENT_PROJECT","attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "张*超")),
        "evidences",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",source.id(),"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",1,"page",22,"locationType","PAGE","quote",source.evidence().quote())));}
    @Test void validatedRuntimeReceiptIsLostOnJsonRoundTripAndCannotAuthorizeTamperedRelation(){Document doc=document();Map<String,Object> relation=ConfirmedInferenceEvidence.validated(cn.bidcheck.compat.Java8Api.listOf(record(doc)),cn.bidcheck.compat.Java8Api.listOf(doc)).get(0);assertTrue(ConfirmedInferenceEvidence.isValidated(relation));
        Map<String,Object> serialized=new ObjectMapper().convertValue(relation,Map.class);assertFalse(ConfirmedInferenceEvidence.isValidated(serialized));
        Map<String,Object> tampered=new LinkedHashMap<>(relation);tampered.put("subject","其他人员");assertFalse(ConfirmedInferenceEvidence.isValidated(tampered));}
    @Test void wrongSourceVersionMissingLedgerAndUnquotedEvidenceNeverReachConsistency(){Document doc=document();Map<String,Object> wrong=record(doc);wrong.remove("confirmationLedgerId");assertTrue(ConfirmedInferenceEvidence.validated(cn.bidcheck.compat.Java8Api.listOf(wrong),cn.bidcheck.compat.Java8Api.listOf(doc)).isEmpty());wrong=record(doc);wrong.put("evidences",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc)).all.get(0).id(),"documentHash","wrong","documentVersion",1,"quote","不存在的原文")));assertTrue(ConfirmedInferenceEvidence.validated(cn.bidcheck.compat.Java8Api.listOf(wrong),cn.bidcheck.compat.Java8Api.listOf(doc)).isEmpty());}
}
