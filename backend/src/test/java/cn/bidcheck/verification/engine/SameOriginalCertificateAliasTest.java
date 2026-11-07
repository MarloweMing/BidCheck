package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SameOriginalCertificateAliasTest {
    private static final String COMPANY="合成施工有限公司";
    private static final String CATEGORY="职业健康安全管理体系认证";
    private static final String NUMBER="SYN2026-A01";
    private static final Rule RULE=new Rule("health-validity","施工单位体系认证","VALIDITY",COMPANY,
        "有效期","","","",linked("category",CATEGORY),"施工单位具备职业健康安全管理体系认证，得1分。","",
        Collections.emptyList());
    private String certificate(String number) {
        return CATEGORY+"证书\n证书编号："+number+"\n兹证明\n"+COMPANY
            +"\n证书有效日期：2025年09月04日至2028年09月03日";
    }
    private EvidenceCorpus corpus(String secondNumber) {
        List<Page> pages=Arrays.asList(new Page(1,"投标文件","PAGE","TEXT",""),
            new Page(2,certificate(NUMBER),"PAGE","TEXT",""),
            new Page(3,certificate(secondNumber),"PAGE","TEXT",""));
        return new EvidenceCorpus(Collections.singletonList(
            new Document("bid","project","BID","合成投标文件",2,"original-hash","",pages,Collections.emptyList())));
    }
    private Source source(EvidenceCorpus corpus,int page) {
        return corpus.all.stream().filter(s -> s.evidence().page()==page).findFirst().get();
    }
    private Fact fact(String category,String number,Source source) {
        return new Fact("f"+source.id()+category,"CERTIFICATE",COMPANY,"证书",number,number,"","","CURRENT_PROJECT",
            Collections.singletonList(source.id()),linked("category",category,"number",number,"numberField","证书编号",
            "validityField","证书有效日期","start","2025-09-04","end","2028-09-03"));
    }
    private Decision date(EvidenceCorpus corpus,List<Fact> facts,boolean referenceConfirmed) {
        Map<String,Object> ctx=linked("referenceDate","2026-08-05","referenceDateSourceVerified",referenceConfirmed,
            "referenceDateEvidence",linked("documentId","t","quote","投标截止日期：2026年8月5日",
            "confirmation","SOURCE_UNAMBIGUOUS","confirmed",referenceConfirmed));
        return new RuleEvaluator(facts,corpus,ctx).evaluate(RULE);
    }

    @Test void sameNumberAndOriginalPageWithTwoCategoryAliasesRetainThePrintedValidityField() {
        EvidenceCorpus corpus=corpus(NUMBER);
        Source original=source(corpus,2);
        List<Fact> aliases=Arrays.asList(fact(CATEGORY,NUMBER,original),fact(CATEGORY+"证书",NUMBER,original));
        Decision dated=date(corpus,aliases,true);
        assertEquals("PASS",dated.status(),dated.toString());
        assertEquals("证书有效日期",dated.proof().get("validityField"));
        Decision unconfirmed=date(corpus,aliases,false);
        assertEquals("UNDETERMINED",unconfirmed.status());
        assertEquals("REFERENCE_TIME_UNCONFIRMED",unconfirmed.gaps().get(0).code());
    }

    @Test void aliasFromAnotherCertificateOrAnotherOriginalPageCannotSelectOneCertificate() {
        for(String secondNumber:Arrays.asList(NUMBER,"SYN2026-B02")) {
            EvidenceCorpus corpus=corpus(secondNumber);
            List<Fact> records=Arrays.asList(fact(CATEGORY,NUMBER,source(corpus,2)),
                fact(CATEGORY+"证书",secondNumber,source(corpus,3)));
            Decision result=date(corpus,records,true);
            assertEquals("UNDETERMINED",result.status(),secondNumber);
        }
    }

    @Test void samePageNumberInAnotherVersionOrAnUnlocatedOriginIsStillDistinct() {
        EvidenceCorpus corpus=corpus(NUMBER);Source original=source(corpus,2);
        Source revised=new Source("revised",original.evidence(),"",false,false,"revised-hash",3);
        List<Fact> aliases=Arrays.asList(fact(CATEGORY,NUMBER,original),fact(CATEGORY+"证书",NUMBER,revised));
        Map<String,Source> both=new HashMap<>(corpus.byId);both.put(revised.id(),revised);
        for(Map<String,Source> locations:Arrays.asList(both,corpus.byId)) {
            Set<List<String>> common=DomainBindings.sameOriginalCertificates(aliases,locations);
            assertNotEquals(DomainBindings.certificateKey(aliases.get(0),common),
                DomainBindings.certificateKey(aliases.get(1),common));
        }
    }
}
