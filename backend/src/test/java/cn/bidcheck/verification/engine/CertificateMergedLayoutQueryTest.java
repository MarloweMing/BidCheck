package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class CertificateMergedLayoutQueryTest {
    private static final String COMPANY="北方建设有限公司",CATEGORY="建筑装修装饰工程专业承包";
    private static final String TEXT="本项目资质查询截图\n企业名称："+COMPANY+"\nhttps://qual.example.cn/query\n| 证书编号 | 资质名称 |\n| D1001 | 建筑工程施工总承包二级 |\n| | "+CATEGORY+"贰级 |";
    private EvidenceCorpus corpus(){return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("bid","p","BID","投标.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,TEXT,"PAGE","OCR","")),cn.bidcheck.compat.Java8Api.listOf())));}
    @Test void twoNumbersInOneTableRemainUnboundUntilTheUserIdentifiesTheRow(){
        String text=TEXT.replace("| | "+CATEGORY,"| D1002 | 市政工程施工总承包二级 |\n| | "+CATEGORY);
        EvidenceCorpus c=new PersonnelCountRoleTest().corpus(text);
        assertTrue(CertificateEvidenceRecords.qualificationQueries(c,COMPANY,CATEGORY).isEmpty());
        List<QualificationProofAssociation.Record> options=CertificateEvidenceRecords.qualificationQueries(c,COMPANY,CATEGORY,Collections.emptyList(),"D1001");
        assertEquals(1,options.size());
        assertEquals("ROW_NUMBER_ASSOCIATION_REQUIRES_HUMAN_CONFIRMATION",options.get(0).fields().get("binding"));
        assertEquals(false,options.get(0).fields().get("inheritedCertificateNumber"));
        assertTrue(CertificateEvidenceRecords.qualificationQueries(c,COMPANY,CATEGORY,Collections.emptyList(),"D9999").isEmpty());
    }
    private Map<String,Object> cell(String id,int row,int column,int rowSpan,String text,boolean header,String headerId){
        int start=TEXT.indexOf(text);assertTrue(start>=0);
        return linked("kind","TABLE_CELL","id",id,"cellId",id,"documentId","bid","documentHash","hash","documentVersion",1,"locationType","PAGE","location",1,"tableId","t","frameId","f","source","OCR_STRUCTURED","imageHash",cn.bidcheck.compat.Java8Api.repeat("a", 64),"imageWidth",500,"imageHeight",300,"bbox",cn.bidcheck.compat.Java8Api.listOf((column-1)*200d, (row-1)*30d, column*200d, (row-1+rowSpan)*30d),"row",row,"column",column,"rowSpan",rowSpan,"columnSpan",1,"text",text,"header",header,"headerCellIds",cn.bidcheck.compat.Java8Api.isBlank(headerId)?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(headerId),"sourceFragments",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("start", start, "end", start+text.length(), "text", text)));
    }
    private List<Map<String,Object>> cells(int span){return cn.bidcheck.compat.Java8Api.listOf(cell("h-number",1,1,1,"证书编号",true,""), cell("h-category",1,2,1,"资质名称",true,""), cell("number",2,1,span,"D1001",false,"h-number"), cell("first",2,2,1,"建筑工程施工总承包二级",false,"h-category"), cell("second",3,2,1,CATEGORY+"贰级",false,"h-category"));}
    @Test void onlyExplicitMergedCertificateCellCanLinkSecondQualificationRow(){
        java.util.List<cn.bidcheck.verification.engine.QualificationProofAssociation.Record> rows=CertificateEvidenceRecords.qualificationQueries(corpus(),COMPANY,CATEGORY,cells(2));assertEquals(1,rows.size());cn.bidcheck.verification.engine.QualificationProofAssociation.Record row=rows.get(0);
        assertEquals("D1001",row.number());assertEquals("二级",row.grade());assertEquals(CATEGORY,row.category());assertEquals(true,row.fields().get("inheritedCertificateNumber"));assertEquals(3,row.fields().get("row"));
        assertEquals("D1001",CertificateEvidenceRecords.qualificationQueries(corpus(),COMPANY,CATEGORY,cells(1)).get(0).number());
    }
    @Test void differentCompanyOrChangedSourceOffsetCannotBorrowMergedNumber(){
        assertTrue(CertificateEvidenceRecords.qualificationQueries(corpus(),"南方建设有限公司",CATEGORY,cells(2)).isEmpty());
        List<Map<String,Object>> forged=new ArrayList<>(cells(2));Map<String,Object> number=new LinkedHashMap<>(forged.get(2));number.put("sourceFragments",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("start", 0, "end", 5, "text", "D1001")));forged.set(2,number);
        assertTrue(CertificateEvidenceRecords.qualificationQueries(corpus(),COMPANY,CATEGORY,forged).isEmpty());
    }
    @Test void primaryIdentityUsesNormalizedHolderAndNumberButNotQualificationRow(){
        Source source=corpus().all.get(0);
        cn.bidcheck.verification.engine.QualificationProofAssociation.Record first=new QualificationProofAssociation.Record(source,COMPANY,"建筑工程施工总承包","D1001","二级",false,cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.QualificationProofAssociation.Record otherCategory=new QualificationProofAssociation.Record(source,COMPANY,CATEGORY,"d1001","二级",true,cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.QualificationProofAssociation.Record anotherNumber=new QualificationProofAssociation.Record(source,COMPANY,CATEGORY,"D1002","二级",true,cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.QualificationProofAssociation.Record anotherCompany=new QualificationProofAssociation.Record(source,"南方建设有限公司",CATEGORY,"D1001","二级",true,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(first.key(),otherCategory.key());assertNotEquals(first.key(),anotherNumber.key());assertNotEquals(first.key(),anotherCompany.key());
    }
    @Test void blankNumberProducesConcreteReadActionWithoutPrematureQueryFact(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=corpus();assertEquals("D1001",CertificateEvidenceRecords.qualificationQueries(c,COMPANY,CATEGORY).get(0).number());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> actions=CertificateEvidenceRecords.qualificationLayoutActions(c,COMPANY,CATEGORY,"D1001","query-condition");
        assertEquals(1,actions.size());java.util.Map<java.lang.String, java.lang.Object> action=actions.get(0);assertEquals("read_table_record",action.get("tool"));assertEquals("query-condition",action.get("targetId"));assertEquals(c.all.get(0).id(),action.get("sourceId"));
        assertEquals("hash",action.get("documentHash"));assertEquals(1,action.get("documentVersion"));assertEquals(1,action.get("page"));
        assertEquals(false,((Map<?,?>)action.get("evidenceDiscovery")).get("establishesMergedRelation"));assertEquals(cn.bidcheck.compat.Java8Api.mapOf("targetId", "query-condition", "sourceId", c.all.get(0).id()),action.get("arguments"));
        assertTrue(CertificateEvidenceRecords.qualificationLayoutActions(c,"南方建设有限公司",CATEGORY,"D1001","query-condition").isEmpty());
        assertTrue(CertificateEvidenceRecords.qualificationLayoutActions(c,COMPANY,CATEGORY,"D2002","query-condition").isEmpty());
        assertTrue(CertificateEvidenceRecords.qualificationLayoutActions(c,COMPANY,"工程设计综合资质","D1001","query-condition").isEmpty());
    }
}
