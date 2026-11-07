package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class FlatUrlEvidenceReceiptTest {
    private static final String HOLDER="合成建设有限公司", HEALTH="职业健康安全管理体系认证", QUALITY="质量管理体系认证";
    private static final String URL="https://cert.synthetic.cn/record";
    private static final String REQUIREMENT="施工单位应有职业健康安全管理体系认证及质量管理体系认证，并提供对应网址。";
    private static final String HASH=String.join("",Collections.nCopies(64,"a"));

    private Rule url(String id,String category){
        return new Rule(id,"认证查询网址","MATERIAL",HOLDER,"material","","查询网址","",linked(
            "certificateProofCategory",category,"certificateRequirementQuote",REQUIREMENT,"certificateProofRequireValidity",true),
            REQUIREMENT,"",Collections.emptyList());
    }
    private EvidenceInvestigation.Host host(boolean withCertificate) {
        List<Page> pages=new ArrayList<>();
        pages.add(new Page(1,"投标文件\n项目名称：合成建设项目","PAGE","TEXT",""));
        pages.add(new Page(2,"查询网址及查询截图\n"+URL+"\n证书详情\n证书编号：SYN-10001\n认证项目：中国"+HEALTH
            +"\n组织名称："+HOLDER+"\n证书状态：有效","PAGE","TEXT",""));
        if(withCertificate)pages.add(new Page(3,HEALTH+"证书\n证书编号：SYN-10001\n兹证明\n"+HOLDER
            +"\n证书有效日期：2025年09月04日至2028年09月03日","PAGE","TEXT",""));
        Document doc=new Document("bid","project","BID","合成投标文件",1,HASH,"",pages,Collections.emptyList());
        EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(doc));
        Plan plan=new Plan("ALL",Arrays.asList(url("health",HEALTH),url("quality",QUALITY)),"LOCAL",Collections.emptyList());
        Map<String,Object> ctx=linked("originalRequirement",REQUIREMENT);
        Checkpoint cp=new Checkpoint("cp","认证查询","测试","HIGH","SEMANTIC_RESPONSE","","","","",true,true,true,null);
        return new EvidenceInvestigation.Host(cp,plan,plan.rules(),corpus,new LinkedHashMap<>(),ctx,
            new SharedFactReuse(corpus,ctx,"plan","version"),new RunSession(ModelGateway.disabled(),ctx,"version"),
            null,new ArrayList<>(),Collections.emptySet());
    }
    private Map<String,Object> target(EvidenceInvestigation.Host host,String id,Map<String,Object> start,List<String> accepted) {
        Map<String,Object> result=host.serverTargetInputConsumption(linked("status","EVIDENCE_UPDATED",
            "code","INVESTIGATION_CALL_LIMIT","failedToolCount",0,"requestId",start.get("requestId"),"acceptedFactIds",accepted));
        return RuleEvaluator.maps(result.get("targets")).stream().filter(row->id.equals(row.get("targetObligationId"))).findFirst().get();
    }
    private Map<String,Object> search(EvidenceInvestigation.Host host,String id) {
        return host.call("search_evidence",linked("query","证书查询网址","targetObligationId",id));
    }
    private Map<String,Object> witness(Map<String,Object> target) {
        return (Map<String,Object>)target.get("flatReadEvidence");
    }

    @Test void returnedQueryOriginalSuppliesOnlyItsOwnTargetAndSourceRevision() {
        EvidenceInvestigation.Host host=host(true);Map<String,Object> start=host.start();
        Map<String,Object> read=search(host,"health");
        assertEquals("COMPLETED",read.get("status"),read.toString());
        Map<String,Object> row=target(host,"health",start,Collections.emptyList()), proof=witness(row);
        assertNotNull(proof,row.toString());
        assertEquals(start.get("requestId"),proof.get("requestId"));
        assertEquals(row.get("evidenceRevision"),proof.get("evidenceRevision"));
        assertEquals(false,proof.get("businessProof"));
        assertEquals(1,ProofVerifier.strings(proof.get("readSourceIds")).size());
        Map<String,Object> source=RuleEvaluator.maps(proof.get("sources")).get(0);
        assertEquals(2,source.get("page"));assertEquals(HASH,source.get("documentHash"));
        assertEquals(1,source.get("documentVersion"));assertFalse(str(source,"parserFingerprint").isEmpty());
        assertNull(witness(target(host,"quality",start,Collections.emptyList())));
    }

    @Test void acceptedUrlFromAnOpenTargetHasVerifiedSourcesAndCannotBeBorrowed() {
        EvidenceInvestigation.Host host=host(false);Map<String,Object> start=host.start();
        search(host,"health");Source origin=host.corpus.all.stream().filter(s->s.evidence().page()==2).findFirst().get();
        Map<String,Object> submitted=host.call("submit_candidate_fact",linked("targetObligationId","health","fact",linked(
            "kind","MATERIAL","field","material","subject",HOLDER,"value",URL,"rawValue",URL,"scope","CURRENT_PROJECT",
            "sourceIds",Collections.singletonList(origin.id()))));
        List<String> accepted=ProofVerifier.strings(submitted.get("acceptedFactIds"));assertEquals(1,accepted.size(),submitted.toString());
        Map<String,Object> proof=witness(target(host,"health",start,accepted));assertNotNull(proof);
        assertEquals(accepted,proof.get("acceptedFactIds"));assertEquals(Collections.emptyList(),proof.get("readSourceIds"));
        assertEquals(Collections.singletonList(origin.id()),RuleEvaluator.maps(proof.get("acceptedFacts")).get(0).get("sourceIds"));
        assertNull(witness(target(host,"quality",start,accepted)));
        assertNull(witness(target(host,"health",start,Collections.emptyList())));
        search(host,"quality");
        assertNull(witness(target(host,"quality",start,accepted)),"reading the other category's URL cannot consume this target");
    }

    @Test void repeatedRecomputeAndAnEarlierRequestCannotCreateAReadWitness() {
        EvidenceInvestigation.Host host=host(true);Map<String,Object> start=host.start();
        for(int i=0;i<3;i++)host.call("recompute_check",Collections.emptyMap());
        assertNull(witness(target(host,"health",start,Collections.emptyList())));
        search(host,"health");Map<String,Object> next=host.start();
        assertNull(witness(target(host,"health",start,Collections.emptyList())));
        assertNull(witness(target(host,"health",next,Collections.emptyList())));
    }
}
