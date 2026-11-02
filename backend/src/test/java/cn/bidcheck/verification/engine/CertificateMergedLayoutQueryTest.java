package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class CertificateMergedLayoutQueryTest {
    private static final String COMPANY="北方建设有限公司",CATEGORY="建筑装修装饰工程专业承包";
    private static final String TEXT="本项目资质查询截图\n企业名称："+COMPANY+"\nhttps://qual.example.cn/query\n| 证书编号 | 资质名称 |\n| D1001 | 建筑工程施工总承包二级 |\n| | "+CATEGORY+"贰级 |";
    private EvidenceCorpus corpus(){return new EvidenceCorpus(List.of(new Document("bid","p","BID","投标.pdf",1,"hash","",List.of(new Page(1,TEXT,"PAGE","OCR","")),List.of())));}
    private Map<String,Object> cell(String id,int row,int column,int rowSpan,String text,boolean header,String headerId){
        int start=TEXT.indexOf(text);assertTrue(start>=0);
        return linked("kind","TABLE_CELL","id",id,"cellId",id,"documentId","bid","documentHash","hash","documentVersion",1,"locationType","PAGE","location",1,"tableId","t","frameId","f","source","OCR_STRUCTURED","imageHash","a".repeat(64),"imageWidth",500,"imageHeight",300,"bbox",List.of((column-1)*200d,(row-1)*30d,column*200d,(row-1+rowSpan)*30d),"row",row,"column",column,"rowSpan",rowSpan,"columnSpan",1,"text",text,"header",header,"headerCellIds",headerId.isBlank()?List.of():List.of(headerId),"sourceFragments",List.of(Map.of("start",start,"end",start+text.length(),"text",text)));
    }
    private List<Map<String,Object>> cells(int span){return List.of(cell("h-number",1,1,1,"证书编号",true,""),cell("h-category",1,2,1,"资质名称",true,""),cell("number",2,1,span,"D1001",false,"h-number"),cell("first",2,2,1,"建筑工程施工总承包二级",false,"h-category"),cell("second",3,2,1,CATEGORY+"贰级",false,"h-category"));}
    @Test void onlyExplicitMergedCertificateCellCanLinkSecondQualificationRow(){
        var rows=CertificateEvidenceRecords.qualificationQueries(corpus(),COMPANY,CATEGORY,cells(2));assertEquals(1,rows.size());var row=rows.get(0);
        assertEquals("D1001",row.number());assertEquals("二级",row.grade());assertEquals(CATEGORY,row.category());assertEquals(true,row.fields().get("inheritedCertificateNumber"));assertEquals(3,row.fields().get("row"));
        assertTrue(CertificateEvidenceRecords.qualificationQueries(corpus(),COMPANY,CATEGORY,cells(1)).isEmpty(),"an ordinary preceding row is not a merged cell");
    }
    @Test void differentCompanyOrChangedSourceOffsetCannotBorrowMergedNumber(){
        assertTrue(CertificateEvidenceRecords.qualificationQueries(corpus(),"南方建设有限公司",CATEGORY,cells(2)).isEmpty());
        List<Map<String,Object>> forged=new ArrayList<>(cells(2));Map<String,Object> number=new LinkedHashMap<>(forged.get(2));number.put("sourceFragments",List.of(Map.of("start",0,"end",5,"text","D1001")));forged.set(2,number);
        assertTrue(CertificateEvidenceRecords.qualificationQueries(corpus(),COMPANY,CATEGORY,forged).isEmpty());
    }
    @Test void primaryIdentityUsesNormalizedHolderAndNumberButNotQualificationRow(){
        Source source=corpus().all.get(0);
        var first=new QualificationProofAssociation.Record(source,COMPANY,"建筑工程施工总承包","D1001","二级",false,List.of());
        var otherCategory=new QualificationProofAssociation.Record(source,COMPANY,CATEGORY,"d1001","二级",true,List.of());
        var anotherNumber=new QualificationProofAssociation.Record(source,COMPANY,CATEGORY,"D1002","二级",true,List.of());
        var anotherCompany=new QualificationProofAssociation.Record(source,"南方建设有限公司",CATEGORY,"D1001","二级",true,List.of());
        assertEquals(first.key(),otherCategory.key());assertNotEquals(first.key(),anotherNumber.key());assertNotEquals(first.key(),anotherCompany.key());
    }
    @Test void blankNumberProducesConcreteReadActionWithoutPrematureQueryFact(){
        var c=corpus();assertTrue(CertificateEvidenceRecords.qualificationQueries(c,COMPANY,CATEGORY).isEmpty());
        var actions=CertificateEvidenceRecords.qualificationLayoutActions(c,COMPANY,CATEGORY,"D1001","query-condition");
        assertEquals(1,actions.size());var action=actions.get(0);assertEquals("read_table_record",action.get("tool"));assertEquals("query-condition",action.get("targetId"));assertEquals(c.all.get(0).id(),action.get("sourceId"));
        assertEquals("hash",action.get("documentHash"));assertEquals(1,action.get("documentVersion"));assertEquals(1,action.get("page"));
        assertEquals(false,((Map<?,?>)action.get("evidenceDiscovery")).get("establishesMergedRelation"));assertEquals(Map.of("targetId","query-condition","sourceId",c.all.get(0).id()),action.get("arguments"));
        assertTrue(CertificateEvidenceRecords.qualificationLayoutActions(c,"南方建设有限公司",CATEGORY,"D1001","query-condition").isEmpty());
        assertTrue(CertificateEvidenceRecords.qualificationLayoutActions(c,COMPANY,CATEGORY,"D2002","query-condition").isEmpty());
        assertTrue(CertificateEvidenceRecords.qualificationLayoutActions(c,COMPANY,"工程设计综合资质","D1001","query-condition").isEmpty());
    }
}
