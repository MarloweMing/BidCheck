package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SocialInsuranceEvidenceRecordsTest {
    private static final String COMPANY="北方建筑有限公司";
    private EvidenceCorpus corpus(String body){return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("b","p","BID","投标文件",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,body,"PAGE","PDF_OCR","")),cn.bidcheck.compat.Java8Api.listOf())));}
    private Rule rule(){return new Rule("r","同一人员连续社保","MONTHS","甲某","socialInsurance","","","月",cn.bidcheck.compat.Java8Api.mapOf("count", 3),"","",cn.bidcheck.compat.Java8Api.listOf());}
    private String header(){return "<table><tr><td rowspan=2>姓名</td><td rowspan=2>社会保障号码</td><td rowspan=2>险种</td><td colspan=2>缴费情况</td><td rowspan=2>本单位实际缴费月数</td></tr><tr><td>起始年月</td><td>截止年月</td></tr>";}
    private String row(String person,String id,String start,String end,String count){return "<tr><td>"+person+"</td><td>"+id+"</td><td>养老保险</td><td>"+start+"</td><td>"+end+"</td><td>"+count+"</td></tr>";}
    private String table(){return header()+row("甲某","110101199001010015","2025年01月","2025年08月","8")+"</table>";}

    @Test void originalRowAndCountDeriveMonthsWithoutInventedQuote(){
        EvidenceCorpus c=corpus("北京市社会保险个人权益记录\n单位名称:"+COMPANY+"\n查询日期：2025年01月至2025年12月\n"+table());
        List<Fact> facts=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c);assertEquals(1,facts.size());Fact f=facts.get(0);
        assertEquals("甲某",f.rawValue());assertEquals(COMPANY,f.attributes().get("employer"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("2025-01", "2025-02", "2025-03", "2025-04", "2025-05", "2025-06", "2025-07", "2025-08"),f.attributes().get("months"));
        assertNull(SocialInsuranceEvidenceRecords.issue(f,c.byId));assertFalse(f.attributes().toString().contains("2025年01月至2025年08月"));
        assertEquals("START_END_AND_ACTUAL_PAID_MONTH_COUNT",f.attributes().get("monthDerivation"));
    }
    @Test void queryPeriodAndEndpointsWithoutMatchingActualCountCannotProvePayment(){
        for(String value:cn.bidcheck.compat.Java8Api.listOf("5", "0", "欠缴", "", "8个月")){
            EvidenceCorpus c=corpus("姓名：甲某\n查询日期：2025年01月至2025年08月\n"+table().replace(">8<",">"+value+"<"));
            assertTrue(SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c).isEmpty(),value);
        }
        assertTrue(SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),corpus("姓名：甲某；单位名称:"+COMPANY+"；查询日期：2025年01月至2025年08月")).isEmpty());
    }
    @Test void mergedNamesDoNotMixDifferentPeopleOrExtendShorterCoverage(){
        String rows="<tr><td rowspan=2>甲某</td><td rowspan=2>110101199001010015</td><td>养老保险</td><td>2025年01月</td><td>2025年08月</td><td>8</td></tr>"
                +"<tr><td>医疗保险</td><td>2025年01月</td><td>2025年08月</td><td>8</td></tr>"
                +row("乙某","110101199001010023","2025年07月","2025年08月","2");
        List<Fact> facts=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),corpus(header()+rows+"</table>"));assertEquals(2,facts.size());
        assertEquals(8,ProofVerifier.strings(facts.get(0).attributes().get("months")).size());assertEquals(2,ProofVerifier.strings(facts.get(1).attributes().get("months")).size());
    }
    @Test void tamperedMonthsEmployerAndPersonAreRejectedByOriginalRecord(){
        EvidenceCorpus c=corpus("单位名称:"+COMPANY+"\n"+table());Fact f=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c).get(0);
        for(java.util.Map.Entry<java.lang.String, java.lang.Object> change:cn.bidcheck.compat.Java8Api.mapOf("months", cn.bidcheck.compat.Java8Api.listOf("2025-09"), "employer", "南方建筑有限公司", "person", "乙某").entrySet()){
            Map<String,Object> attrs=new LinkedHashMap<>(f.attributes());attrs.put(change.getKey(),change.getValue());
            Fact forged=new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),attrs);
            assertNotNull(SocialInsuranceEvidenceRecords.issue(forged,c.byId),change.getKey());
        }
    }
    @Test void multipleEmployerHeadersNeverChooseOneAndAdjacentPagesDoNotSupplyEmployer(){
        Fact ambiguous=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),corpus("单位名称:"+COMPANY+"\n单位名称:南方建筑有限公司\n"+table())).get(0);
        assertEquals("",ambiguous.attributes().get("employer"));
        Document doc=new Document("b","p","BID","投标文件",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"单位名称:"+COMPANY,"PAGE","PDF_OCR",""), new Page(2,table(),"PAGE","PDF_OCR","")),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("",SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc))).get(0).attributes().get("employer"));
    }
    @Test void exactSourceSpansSurviveChunkBoundariesAndChangingSourceInvalidatesFact(){
        // The old 220-line fixture was only 1,395 characters, below the 1,400-character
        // chunk boundary. Make the original table itself span chunks, not just its prefix.
        String largeTable=table().replace("</table>",cn.bidcheck.compat.Java8Api.repeat(row("乙某","110101199001010023","2025年07月","2025年08月","2"), 16)+"</table>");
        EvidenceCorpus c=corpus(cn.bidcheck.compat.Java8Api.repeat("表前说明\n", 240)+"单位名称:"+COMPANY+"\n"+largeTable);
        assertTrue(c.all.size()>2,"Fixture must cross more than one original chunk boundary");
        Fact f=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c).get(0);
        assertTrue(f.sourceIds().size()>1);assertNull(SocialInsuranceEvidenceRecords.issue(f,c.byId));
        Map<String,Source> missing=new HashMap<>(c.byId);missing.remove(f.sourceIds().get(0));assertNotNull(SocialInsuranceEvidenceRecords.issue(f,missing));
        Map<?,?> fields=(Map<?,?>)f.attributes().get("sourceFieldSpans");assertTrue(fields.containsKey("start"));assertTrue(fields.containsKey("header:count"));
    }
    @Test void historicalRecordRetainsHistoryAndMustNotClaimCurrent(){
        EvidenceCorpus c=corpus("历史项目\n单位名称:"+COMPANY+"\n"+table());Fact f=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c).get(0);
        assertEquals("HISTORICAL_PROJECT",f.scope());
        Fact forged=new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),"CURRENT_PROJECT",f.sourceIds(),f.attributes());
        assertNotNull(SocialInsuranceEvidenceRecords.issue(forged,c.byId));
    }
    @Test void acceptedDerivedFactIsReadmittedAndRuleComputesCoverage()throws Exception{
        EvidenceCorpus c=corpus("单位名称:"+COMPANY+"\n"+table());List<Fact> facts=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c);
        cn.bidcheck.verification.engine.ProofVerifier.Verified verified=new ProofVerifier().verify(new ObjectMapper().valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.toList(facts.stream().map(SharedFactReuse::value)))),c.all);
        assertTrue(verified.rejected().isEmpty(),verified.rejected().toString());assertEquals(1,verified.facts().size());
        assertEquals("PASS",new RuleEvaluator(verified.facts(),c,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule()).status());
    }
    private EvidenceCorpus reportPages(String firstId,String secondId,String additional){
        String title="北京市社会保险个人权益记录\n";
        return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("b","p","BID","投标文件",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,title+"查询流水号："+firstId+"\n单位名称："+COMPANY+"\n第1页（共9页）","PAGE","PDF_OCR",""), new Page(2,title+"查询流水号："+secondId+"\n"+additional+table()+"\n第5页（共9页）","PAGE","PDF_OCR","")),cn.bidcheck.compat.Java8Api.listOf())));
    }
    @Test void explicitReportReferenceBindsHeaderEvenWithMissingIntermediatePages(){EvidenceCorpus c=reportPages("REPORT-123456","REPORT-123456","");
        List<Source> selected=cn.bidcheck.compat.Java8Api.toList(c.all.stream().filter(s->s.evidence().page()==2));Fact f=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c,selected).get(0);
        assertEquals(COMPANY,f.attributes().get("employer"));assertEquals("SAME_EXPLICIT_REPORT_REFERENCE",f.attributes().get("employerBinding"));assertNull(SocialInsuranceEvidenceRecords.issue(f,c.byId));
        assertTrue(f.sourceIds().stream().map(c.byId::get).anyMatch(s->s.evidence().page()==1));
        Map<?,?> spans=(Map<?,?>)f.attributes().get("sourceFieldSpans");assertTrue(spans.containsKey("row:reportId"));
    }
    @Test void mismatchedReportReferenceCannotBorrowEmployer(){EvidenceCorpus c=reportPages("REPORT-123456","REPORT-999999","");Fact f=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c).get(0);assertEquals("",f.attributes().get("employer"));}
    @Test void derivedStrongIdentifierCannotBeForged(){EvidenceCorpus c=corpus("单位名称:"+COMPANY+"\n"+table());Fact f=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c).get(0);Map<String,Object> attrs=new LinkedHashMap<>(f.attributes());attrs.put("identifierFingerprint",identity("110101199001010023"));Fact forged=new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),attrs);assertEquals("SOCIAL_IDENTIFIER_LINK_UNPROVEN",SocialInsuranceEvidenceRecords.issue(forged,c.byId));}
    @Test void sameSocialReportDoesNotMergeSameNameWithDifferentIdentifiers(){EvidenceCorpus c=corpus("本项目专职安全员：甲某 身份证号码：110101199001010023\n单位名称:"+COMPANY+"\n"+table());Fact social=SocialInsuranceEvidenceRecords.extract(cn.bidcheck.compat.Java8Api.listOf(rule()),c).get(0);Fact role=new Fact("role","PERSON_ROLE","专职安全员","role","甲某","甲某","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());
        cn.bidcheck.verification.engine.SubjectIdentity.Problem problem=new SubjectIdentity(cn.bidcheck.compat.Java8Api.listOf(role, social),c,cn.bidcheck.compat.Java8Api.listOf()).issue(new Rule("r","人员社保","MONTHS","专职安全员","socialInsurance","","","月",cn.bidcheck.compat.Java8Api.mapOf("count", 3),"","",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(social));assertNotNull(problem);assertEquals("SUBJECT_IDENTITY_CONFLICT",problem.code());}
    @Test void conflictingAppointmentAndPaymentInOneChunkExposeBothRecordOrigins(){
        EvidenceCorpus c=corpus("本项目专职安全员：甲某 身份证号码：110101199001010023\n单位名称:"+COMPANY+"\n"+table());
        Fact social=SocialInsuranceEvidenceRecords.extract(Collections.singletonList(rule()),c).get(0);
        Fact role=new Fact("role","PERSON_ROLE","专职安全员","role","甲某","甲某","","","CURRENT_PROJECT",
            Collections.singletonList(c.all.get(0).id()),Collections.emptyMap());
        Rule check=new Rule("r","人员社保","MONTHS","专职安全员","socialInsurance","","","月",Collections.singletonMap("count",3),"","",Collections.emptyList());
        Decision pending=new Decision("r","人员社保","UNDETERMINED","身份待核",Collections.emptyList(),
            Collections.singletonList(gap("ENTITY_IDENTITY_AMBIGUOUS","身份待核")),Collections.emptyMap());
        assertEquals(IdentityNumberEvidence.Match.CONFLICT,IdentityNumberEvidence.compare(role,social,c));
        Decision result=IdentityNumberEvidence.attach(check,pending,Arrays.asList(role,social),c);
        assertTrue(result.gaps().stream().anyMatch(g->"ID_NUMBER_PAIR_CONFLICT".equals(g.code())));
        assertTrue(ProgrammaticInferenceCandidates.collect(check,result,Arrays.asList(role,social),c).isEmpty());
    }
}
