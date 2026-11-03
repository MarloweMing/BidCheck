package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the production mixture of shared Chinese dates and native ISO dates. */
class CertificateDateFormatRegressionTest {
    private static final String COMPANY="北方建设有限公司",CATEGORY="建筑装饰工程设计专项";
    private EvidenceCorpus corpus(String... texts){List<Page> pages=new ArrayList<>();for(int i=0;i<texts.length;i++)pages.add(new Page(i+1,texts[i],"PAGE","TEXT",""));return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("bid","project","BID","投标文件",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf())));}
    private String certificate(String period){return "资质证书\n企业名称："+COMPANY+"\n资质等级：建筑装饰工程设计专项甲级\n证书编号：A12345678\n有效期："+period+"\n发证日期：2021年06月16日";}
    private Map<String,Object> context(boolean coverage){Map<String,Object> c=linked("referenceDate","2025-08-05","referenceDateSourceVerified",true,"referenceDateEvidence",cn.bidcheck.compat.Java8Api.mapOf("documentId", "t", "quote", "投标截止日期：2025年8月5日", "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));if(coverage)c.put("certificateSetComplete",true);return c;}
    private Rule rule(boolean coverage){Map<String,Object> p=linked("category",CATEGORY,"validityField","有效期");if(coverage)p.put("allApplicableCertificates",true);return new Rule("valid","证书有效期","VALIDITY",COMPANY,"有效期","","","",p,"证书在投标截止日期有效","",cn.bidcheck.compat.Java8Api.listOf());}
    private Fact fact(EvidenceCorpus c,String id,int page,String start,String end){Map<String,Object> attrs=linked("category",CATEGORY,"grade","甲级","number","A12345678","numberField","证书编号","validityField","有效期","end",end);if(!cn.bidcheck.compat.Java8Api.isBlank(start))attrs.put("start",start);Source source=c.all.stream().filter(s->s.evidence().page()==page).findFirst().orElseThrow(java.util.NoSuchElementException::new);Fact f=new Fact(id,"CERTIFICATE",COMPANY,"证书","A12345678","A12345678","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),attrs);assertNull(new ProofVerifier().issue(f,c.byId),"fixture must remain source grounded");return f;}
    private Decision check(EvidenceCorpus c,List<Fact> facts,boolean coverage){return new RuleEvaluator(facts,c,context(coverage)).validity(rule(coverage),facts);}

    @SuppressWarnings("unchecked")
    @Test void sharedChineseExpiryAndNativeIsoExpiryAreOneRange(){EvidenceCorpus c=corpus(certificate("至2026年06月04日"));List<Fact> facts=cn.bidcheck.compat.Java8Api.listOf(fact(c,"shared",1,"","2026年06月04日"), fact(c,"native",1,"","2026-06-04"));for(boolean coverage:cn.bidcheck.compat.Java8Api.listOf(false, true)){Decision d=check(c,facts,coverage);assertEquals("PASS",d.status(),d.toString());List<Map<String,Object>> audits=(List<Map<String,Object>>)d.proof().get("dateSemantics");assertEquals(2,audits.size());for(Map<String,Object> entry:audits){Map<String,Object> dates=(Map<String,Object>)entry.get("dateSemantics");assertEquals("EXPIRY_DATE",dates.get("semantics"));assertEquals("",dates.get("start"));assertEquals("2021-06-16",dates.get("issuedOn"));assertEquals(true,dates.get("issueDateIsNotValidityStart"));}}}

    @Test void sameClosedIntervalInChineseIsoAndSlashSpellingIsOneRange(){EvidenceCorpus c=corpus(certificate("2021年06月16日至2026年06月04日"));List<Fact> facts=cn.bidcheck.compat.Java8Api.listOf(fact(c,"shared",1,"2021年06月16日","2026年06月04日"), fact(c,"native",1,"2021-06-16","2026-06-04"), fact(c,"slash",1,"2021/6/16","2026/6/4"));for(boolean coverage:cn.bidcheck.compat.Java8Api.listOf(false, true))assertEquals("PASS",check(c,facts,coverage).status());}

    @Test void mixedFormatsCannotHideCertificateNotYetValidAtTenderDeadline(){EvidenceCorpus c=corpus(certificate("2025年09月04日至2028年09月03日"));List<Fact> facts=cn.bidcheck.compat.Java8Api.listOf(fact(c,"shared",1,"2025年09月04日","2028年09月03日"), fact(c,"native",1,"2025-09-04","2028-09-03"));for(boolean coverage:cn.bidcheck.compat.Java8Api.listOf(false, true)){Decision d=check(c,facts,coverage);assertEquals("FAIL",d.status(),d.toString());assertTrue(d.proof().toString().contains("start=2025-09-04"));assertTrue(d.proof().toString().contains("referenceDate=2025-08-05"));}}

    @Test void genuinelyDifferentExpiryDatesStillRequireVersionEvidence(){EvidenceCorpus c=corpus(certificate("至2026年06月04日"),certificate("至2027年06月04日"));List<Fact> facts=cn.bidcheck.compat.Java8Api.listOf(fact(c,"old",1,"","2026年06月04日"), fact(c,"renewed",2,"","2027-06-04"));for(boolean coverage:cn.bidcheck.compat.Java8Api.listOf(false, true)){Decision d=check(c,facts,coverage);assertEquals("UNDETERMINED",d.status());assertTrue(d.gaps().stream().anyMatch(g->g.code().equals("CERTIFICATE_VERSION_REQUIRED")));assertTrue(d.proof().containsKey("normalizedDateRanges"));}}

    @Test void sameExpiryDoesNotEraseDifferentValidFromDates(){EvidenceCorpus c=corpus(certificate("2024年01月01日至2027年06月04日"),certificate("2025年09月01日至2027年06月04日"));List<Fact> facts=cn.bidcheck.compat.Java8Api.listOf(fact(c,"current",1,"2024年01月01日","2027年06月04日"), fact(c,"future",2,"2025-09-01","2027-06-04"));for(boolean coverage:cn.bidcheck.compat.Java8Api.listOf(false, true)){Decision d=check(c,facts,coverage);assertEquals("UNDETERMINED",d.status());assertTrue(d.gaps().stream().anyMatch(g->g.code().equals("CERTIFICATE_VERSION_REQUIRED")));}}

    @Test void expiryOnlyComparisonDoesNotInventIssuance(){EvidenceCorpus c=corpus(certificate("至2026年06月04日").replace("发证日期：2021年06月16日",""));List<Fact> facts=cn.bidcheck.compat.Java8Api.listOf(fact(c,"shared",1,"","2026年06月04日"), fact(c,"native",1,"","2026-06-04"));Decision result=check(c,facts,false);assertEquals("PASS",result.status());assertTrue(result.proof().toString().contains("EXPIRY_AT_REFERENCE_DATE_ONLY"));assertFalse(result.proof().toString().contains("issuedOn"));}
}
