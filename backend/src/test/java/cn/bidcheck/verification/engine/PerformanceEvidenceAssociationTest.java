package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceEvidenceAssociationTest {
    static final String LIST_NAME="北方中心装饰装修工程施工合同",CONTRACT_NAME="北方中心装饰装修工程施工总合同";
    EvidenceCorpus frozenNavigation(String rows)throws Exception{
        List<Page> pages;try(java.io.InputStream input=getClass().getResourceAsStream("/regressions/company-performance-list-pages.json")){pages=new ArrayList<>(Arrays.asList(new ObjectMapper().readValue(input,Page[].class)));}
        pages.removeIf(p->p.number()==279||p.number()==282);pages.add(new Page(279,"5.9 业绩证明文件\n（1）业绩清单\n附表6-4 近年完成的类似项目情况表\n序号 合同名称 签订时间 金额\n"+rows+"\n279","PAGE","PDFBOX_NATIVE",""));
        pages.add(new Page(282,"合同名称："+CONTRACT_NAME+"\n合同编号：TEST-2023\n乙方：北方建筑有限公司\n合同金额：200万元\n合同签订日期：2023年1月1日\n282","PAGE","PDF_OCR",""));
        return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("frozen-doc","p","BID","冻结投标文件",1,"frozen-version","",pages,cn.bidcheck.compat.Java8Api.listOf())));
    }
    String row(int n,String date){return n+" 合同名称："+LIST_NAME+" 签订时间："+date+" 金额：200万元";}
    Fact contract(EvidenceCorpus c,String date){return new Fact("contract-test","CONTRACT","TEST-2023","contract",CONTRACT_NAME,CONTRACT_NAME,"","","HISTORICAL_PROJECT",cn.bidcheck.compat.Java8Api.toList(sources(c).stream().map(Source::id)),linked("contractName",CONTRACT_NAME,"contractNumber","TEST-2023","party","北方建筑有限公司","amount","200","unit","万元","amountBasis","合同金额","signedDate",date));}
    List<Source> sources(EvidenceCorpus c){return cn.bidcheck.compat.Java8Api.toList(c.all.stream().filter(s->s.evidence().page()==282));}
    PerformanceInventoryProof.Match match(EvidenceCorpus c,List<Fact> facts){return new PerformanceInventoryProof("附表6-4",new RuleEvaluator(facts,c,cn.bidcheck.compat.Java8Api.mapOf())).matchWithEvidence(CONTRACT_NAME,cn.bidcheck.compat.Java8Api.setOf("TEST-2023"),true,cn.bidcheck.compat.Java8Api.setOf("frozen-doc"),sources(c),facts);}
    @Test void frozenFormalNavigationPlusIndependentDateAndAmountResolveUniqueSimilarTitle()throws Exception{
        cn.bidcheck.verification.engine.EvidenceCorpus c=frozenNavigation(row(1,"2023年1月1日"));cn.bidcheck.verification.engine.PerformanceInventoryProof.Match match=match(c,cn.bidcheck.compat.Java8Api.listOf(contract(c,"2023-01-01")));assertTrue(match.present(),match.toString());java.util.Map<java.lang.String, java.lang.Object> record=match.records().get(0);assertEquals(true,record.get("signedDateMatched"));assertEquals(true,record.get("contractTotalMatched"));assertEquals(true,record.get("contractSourcesWithinRange"));assertEquals("LIST_ROW_TO_CONTRACT_ONLY",record.get("linkScope"));assertEquals(false,record.get("countsAsPerformance"));assertEquals(279,match.evidence().get(0).page());
    }
    @Test void similarTitleWithoutIndependentMatchAndDuplicateRowsRemainUnresolved()throws Exception{
        for(String rows:cn.bidcheck.compat.Java8Api.listOf(row(1,"2024年1月1日"), row(1,"2023年1月1日")+"\n"+row(2,"2023年1月1日"))){cn.bidcheck.verification.engine.EvidenceCorpus c=frozenNavigation(rows);cn.bidcheck.verification.engine.PerformanceInventoryProof.Match match=match(c,cn.bidcheck.compat.Java8Api.listOf(contract(c,"2023-01-01")));assertFalse(match.present());assertFalse(match.candidates().isEmpty());assertTrue(match.candidates().stream().allMatch(v->!ProofVerifier.strings(v.get("sourceIds")).isEmpty()));}
    }
    @Test void confirmedContractRowIsConsumedOnlyForItsCitedExactRowAndOriginalAmountIsNotRewritten()throws Exception{
        cn.bidcheck.verification.engine.EvidenceCorpus c=frozenNavigation(row(1,"2024年1月1日"));cn.bidcheck.verification.engine.VerificationTypes.Fact contract=contract(c,"2023-01-01");cn.bidcheck.verification.engine.PerformanceInventoryProof.Match pending=match(c,cn.bidcheck.compat.Java8Api.listOf(contract));java.util.Map<java.lang.String, java.lang.Object> row=pending.candidates().get(0);Set<String> ids=new LinkedHashSet<>(ProofVerifier.strings(row.get("sourceIds")));ids.addAll(contract.sourceIds());java.util.Map<java.lang.String, java.lang.Object> attributes=linked("object",LIST_NAME,"documentId","frozen-doc","inventoryPage",279,"inventoryRow","1","humanConfirmation",new InferenceClaims.HumanConfirmation("claim","reviewer","2026-11-02",InferenceClaims.POLICY,"rule","fingerprint"));
        Fact confirmed=new Fact("confirmed","RELATION",CONTRACT_NAME,"CONTRACT_RECORD_MATCH",LIST_NAME,LIST_NAME,"","申报表与合同原件对应","HISTORICAL_PROJECT",cn.bidcheck.compat.Java8Api.listCopyOf(ids),attributes);cn.bidcheck.verification.engine.PerformanceInventoryProof.Match accepted=match(c,cn.bidcheck.compat.Java8Api.listOf(contract, confirmed));assertTrue(accepted.present());assertEquals(true,accepted.records().get(0).get("humanConfirmed"));assertEquals("200",contract.attributes().get("amount"));
        attributes.put("inventoryRow","2");Fact wrong=new Fact(confirmed.id(),confirmed.kind(),confirmed.subject(),confirmed.field(),confirmed.rawValue(),confirmed.value(),confirmed.unit(),confirmed.basis(),confirmed.scope(),confirmed.sourceIds(),attributes);assertFalse(match(c,cn.bidcheck.compat.Java8Api.listOf(contract, wrong)).present());
    }
}
