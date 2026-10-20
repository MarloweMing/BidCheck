package cn.bidcheck.consistency;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyDerivedConclusionTest {
    private Page page(int n,String text){return new Page(n,text,"PAGE","OCR","");}
    private Document doc(Page...p){return new Document("d","p","BID","bid.pdf",1,"sha","",List.of(p),List.of());}
    private Map<String,Object> occurrence(Document doc,int n,String quote,String field,String value,String id){
        var p=doc.pages().stream().filter(x->x.number()==n).findFirst().orElseThrow();
        return values("id",id,"field",field,"value",value,"scope","CURRENT_PROJECT","subject","UNRESOLVED","sourceOffset",p.text().indexOf(quote),
            "evidence",new Evidence(doc.id(),doc.name(),n,"PAGE",quote,"p"+n,0));
    }
    private Map<String,Object> report(List<Map<String,Object>> os,String field){return values("documentId","d","documentHash","sha","occurrences",os,
        "groups",List.of(values("id","g","field",field,"fieldLabel",field,"status","EVIDENCE_INSUFFICIENT","title","缺少比较依据",
            "occurrenceIds",os.stream().map(o->o.get("id")).toList(),"evidences",os.stream().map(o->o.get("evidence")).toList(),
            "comparisonContext",values("aggregation","汇总","scope","UNRESOLVED"))));}
    private Evaluation evaluate(Document d,Map<String,Object> report){return ConsistencyDerivedConclusion.recompute(d,report).get("g");}
    private Map<String,Object> derived(Map<String,Object> report){return map(maps(report.get("groups")).get(0).get("derivedConclusion"));}
    private String invoice(String id,String item,String price,String tax){
        return "电子发票（普通发票）\n发票号码："+id+"\n<table><tr><td>项目名称</td><td>规格型号</td><td>单位</td><td>数量</td><td>单价</td><td>金额</td><td>税率/征收率</td><td>税额</td></tr>"
            +"<tr><td>"+item+"</td><td>M1</td><td>台</td><td>1</td><td>"+price+"</td><td>"+price+"</td><td>13%</td><td>"+tax+"</td></tr>"
            +"<tr><td colspan=8>价税合计（小写）¥113.00</td></tr></table>";
    }
    private Map<String,Object> tax(Document d,int page,String value,String id){return occurrence(d,page,value+"</td></tr>","taxAmount",value,id);}
    @Test void currentProjectLocalProofActuallyChangesEvaluationWithoutOverwritingOriginal(){
        String name="2026年合成中心装修工程";Document d=doc(page(1,"（"+name+"）\n投标文件（商务部分）"),
            page(9,"合同条款偏离表\n项目名称："+name),page(488,"技术偏离表\n项目名称："+name));
        var os=List.of(occurrence(d,9,"项目名称："+name,"projectName",name,"a"),occurrence(d,488,"项目名称："+name,"projectName",name,"b"));
        var report=report(os,"projectName");assertEquals("PASS",evaluate(d,report).status());
        assertEquals("EVIDENCE_INSUFFICIENT",maps(report.get("groups")).get(0).get("status"));
        assertEquals(List.of("a","b"),derived(report).get("resolvedOccurrenceIds"));
        assertEquals("OBSERVED_OCCURRENCES",derived(report).get("proofScope"));assertEquals(false,derived(report).get("documentCoverageComplete"));
    }
    @Test void unknownOriginalCannotDisappearBehindOneGoodObject(){
        Document d=doc(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"),page(2,"证书编号：DEF67890"));
        var os=List.of(occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a"),occurrence(d,2,"证书编号：DEF67890","certificateNumber","DEF67890","b"));
        var r=report(os,"certificateNumber");assertEquals("UNDETERMINED",evaluate(d,r).status());
        assertEquals(List.of("b"),derived(r).get("unresolvedOccurrenceIds"));assertEquals(false,derived(r).get("comparisonCoverageComplete"));
    }
    @Test void differentCertificateInstancesAreSeparatedWithoutProvingQualificationValidity(){
        Document d=doc(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"),page(2,"建筑业企业资质证书\n企业名称：乙公司\n证书编号：DEF67890"));
        var os=List.of(occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a"),occurrence(d,2,"证书编号：DEF67890","certificateNumber","DEF67890","b"));
        var r=report(os,"certificateNumber");assertEquals("PASS",evaluate(d,r).status());assertEquals(2,derived(r).get("separatedObjectCount"));
        assertTrue(maps(derived(r).get("objects")).stream().allMatch(o->Boolean.FALSE.equals(o.get("provesQualificationValidity"))));
    }
    @Test void sameCertificateDifferentHoldersRemainRisk(){
        Document d=doc(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"),page(2,"建筑业企业资质证书\n企业名称：乙公司\n证书编号：ABC12345"));
        var os=List.of(occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a"),occurrence(d,2,"证书编号：ABC12345","certificateNumber","ABC12345","b"));
        var r=report(os,"certificateNumber");assertEquals("WARNING",evaluate(d,r).status());assertFalse((Boolean)derived(r).get("comparisonCoverageComplete"));
    }
    @Test void twoInvoicesAreVerifiedSeparatelyAndNeverBecomeCurrentBidPrices(){
        Document d=doc(page(263,invoice("12345678901234567890","仪器甲","100.00","13.00")),page(274,invoice("22345678901234567890","仪器乙","200.00","26.00")));
        var r=report(List.of(tax(d,263,"13.00","a"),tax(d,274,"26.00","b")),"taxAmount");
        assertEquals("PASS",evaluate(d,r).status());assertEquals(2,derived(r).get("separatedObjectCount"));
        for(var o:maps(derived(r).get("objects"))){assertEquals("INVOICE_TRANSACTION",o.get("objectKind"));
            var relations=maps(map(o.get("relationValidation")).get("relations"));assertFalse(relations.isEmpty());
            assertTrue(relations.stream().allMatch(x->Boolean.FALSE.equals(x.get("currentBidPrice"))));}
    }
    @Test void sameInvoiceSameItemDifferentTaxValuesAreNotPartitionedByTheirValues(){
        Document d=doc(page(1,invoice("12345678901234567890","仪器甲","100.00","13.00")),page(2,invoice("12345678901234567890","仪器甲","100.00","15.00")));
        var r=report(List.of(tax(d,1,"13.00","a"),tax(d,2,"15.00","b")),"taxAmount");
        assertEquals("WARNING",evaluate(d,r).status());assertEquals(1,derived(r).get("separatedObjectCount"));
    }
    @Test void forgedNumericValueAndWrongColumnCannotPass(){
        Document d=doc(page(1,invoice("12345678901234567890","仪器甲","100.00","13.00")));
        var a=tax(d,1,"13.00","a");a.put("value","999.00");assertEquals("UNDETERMINED",evaluate(d,report(List.of(a),"taxAmount")).status());
        var b=occurrence(d,1,"100.00</td>","taxAmount","100.00","b");assertEquals("UNDETERMINED",evaluate(d,report(List.of(b),"taxAmount")).status());
    }
    @Test void repeatedInvoiceItemsRequireIndependentRowIdentity(){
        String text=invoice("12345678901234567890","仪器甲","100.00","13.00");String row="<tr><td>仪器甲</td><td>M1</td><td>台</td><td>1</td><td>100.00</td><td>100.00</td><td>13%</td><td>13.00</td></tr>";
        Document d=doc(page(1,text.replace(row,row+row)));assertEquals("UNDETERMINED",evaluate(d,report(List.of(tax(d,1,"13.00","a")),"taxAmount")).status());
    }
    @Test void validUnitPriceBasisNeedsPrintedColumnArithmetic(){
        Document d=doc(page(1,invoice("12345678901234567890","仪器甲","100.00","13.00")));
        var o=occurrence(d,1,"100.00</td>","unitPrice","100.00","a");var r=report(List.of(o),"unitPrice");assertEquals("PASS",evaluate(d,r).status());
        var proof=map(maps(derived(r).get("objects")).get(0).get("identityProof"));assertEquals("CNY_EXCLUSIVE",proof.get("basis"));
        Document bad=doc(page(1,invoice("12345678901234567890","仪器甲","100.00","18.00")));
        assertEquals("UNDETERMINED",evaluate(bad,report(List.of(occurrence(bad,1,"100.00</td>","unitPrice","100.00","a")),"unitPrice")).status());
    }
    @Test void originalSourceOffsetAndManifestAreRevalidated(){
        Document d=doc(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"));
        var o=occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a");o.put("sourceOffset",0);
        assertEquals("UNDETERMINED",evaluate(d,report(List.of(o),"certificateNumber")).status());
        var r=report(List.of(o),"certificateNumber");r.put("documentHash","other");assertThrows(IllegalArgumentException.class,()->evaluate(d,r));
    }
    @Test void aProvenFieldCannotSatisfyAnotherFieldsParentGroup(){
        Document d=doc(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"));
        var o=occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a");
        assertEquals("UNDETERMINED",evaluate(d,report(List.of(o),"qualificationGrade")).status());
    }
    @Test void localSourceProofIsConsumedBeforeAnyRetrievalOrModelRequest(){
        Document d=doc(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"));
        var r=report(List.of(occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a")),"certificateNumber");
        new ConsistencyDoubtReviewer((a,b,c)->{fail("No retrieval is needed for complete local relations");return null;},(a,b,c,e)->{fail("No distinct values require a model");return null;},()->true).review(d,r,()->true,g->{});
        assertEquals("PASS",derived(r).get("status"));assertEquals("LOCAL_RELATIONS_VERIFIED",map(maps(r.get("groups")).get(0).get("targetedReview")).get("status"));
    }
    @Test void contentsAndHistoricalProjectMentionsDoNotAcquireCurrentIdentity(){
        String name="2026年合成中心装修工程";
        Document d=doc(page(1,"（"+name+"）\n投标文件（商务部分）"),page(2,"目录\n项目名称："+name+"\n投标函....9\n报价表....12"),page(3,"历史项目\n项目名称：旧工程"));
        var os=List.of(occurrence(d,2,"项目名称："+name,"projectName",name,"a"),occurrence(d,3,"项目名称：旧工程","projectName","旧工程","b"));
        var r=report(os,"projectName");assertEquals("UNDETERMINED",evaluate(d,r).status());assertEquals(2,strings(derived(r).get("excludedOccurrenceIds")).size());
        assertTrue(strings(derived(r).get("resolvedOccurrenceIds")).isEmpty());
    }
    @Test void oneCurrentValueAndProvenHistoryCanCloseButUnknownCannot(){
        String name="2026年合成中心装修工程";
        Document d=doc(page(1,"（"+name+"）\n投标文件（商务部分）"),page(9,"合同条款偏离表\n项目名称："+name),page(10,"历史项目\n项目名称：旧工程"));
        var os=List.of(occurrence(d,9,"项目名称："+name,"projectName",name,"a"),occurrence(d,10,"项目名称：旧工程","projectName","旧工程","b"));
        var r=report(os,"projectName");assertEquals("PASS",evaluate(d,r).status());assertEquals(List.of("b"),derived(r).get("excludedOccurrenceIds"));
    }
    private List<?> strings(Object value){return (List<?>)value;}
}
