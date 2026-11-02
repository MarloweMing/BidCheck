package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.source.DateFieldValues;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class QualificationCertificateEvidenceTest {
    private static final String COMPANY="北方施工有限公司",CATEGORY="建筑装修装饰工程专业承包";
    private static final String ORIGINAL="# 建筑业企业资质证书\n企业名称："+COMPANY+"\n证书编号：D10009999 有效期：2028年12月24日\n资质类别及等级：\n"+CATEGORY+"贰级 2009/07/07;\n建筑幕墙工程专业承包贰级 2017/04/21;\n使用期限：2025-08-14 至 2025-11-13\n备注：上述标注红色贰级资质为叁级换贰级，有效期至 2025/11/05;";
    private EvidenceCorpus corpus(String text){return new EvidenceCorpus(List.of(new Document("bid","p","BID","原件.pdf",1,"hash","",List.of(new Page(1,text,"PAGE","OCR","")),List.of())));}
    private Fact selected(EvidenceCorpus corpus){return new Fact("selected","CERTIFICATE",COMPANY,"证书","D10009999","D10009999","","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of("category",CATEGORY,"number","D10009999","numberField","证书编号","grade","贰级"));}
    private QualificationCertificateEvidence.Resolution resolved(EvidenceCorpus corpus){return QualificationCertificateEvidence.resolve(selected(corpus),corpus.all.get(0),corpus).orElseThrow();}
    @Test void originalHeaderDateDoesNotReadCategoryAwardDatesOrRestrictedNoteAsGeneralExpiry(){
        var corpus=corpus(ORIGINAL);var result=resolved(corpus);
        assertEquals("有效期",result.field());assertEquals("2028-12-24",result.fact().attributes().get("end"));
        assertEquals(List.of(LocalDate.of(2028,12,24)),DateFieldValues.read(ORIGINAL,"有效期",2));
        assertEquals(List.of(LocalDate.of(2025,11,5)),DateFieldValues.read(ORIGINAL,"有效期至",2));
        assertNull(new ProofVerifier().issue(result.fact(),corpus.byId));assertNull(DomainBindings.issue(result.fact(),corpus.byId,List.of()));
        Evidence source=(Evidence)result.proof().get("source");assertTrue(source.quote().startsWith("证书编号：D10009999"));assertFalse(source.quote().contains("2009/07/07"));assertEquals(true,result.proof().get("retainsUsePeriodAndCategoryRestrictions"));
    }
    @Test void knownUsePeriodBeforeTenderIsFailureRatherThanCategoryPending(){
        var c=corpus(ORIGINAL);var result=CertificateValidityDates.fact(resolved(c).fact(),c,"有效期",LocalDate.of(2025,8,5));
        assertTrue(result.complete(),result.toString());assertEquals(false,result.valid());assertTrue(result.proof().toString().contains("USE_PERIOD"));assertTrue(result.proof().toString().contains("start=2025-08-14"));
    }
    @Test void referenceWithinEveryPossibleExpiryCeilingNeedsNoGuessedColourMapping(){
        var c=corpus(ORIGINAL);var result=CertificateValidityDates.fact(resolved(c).fact(),c,"有效期",LocalDate.of(2025,10,5));
        assertEquals(true,result.valid(),result.toString());assertTrue(result.proof().toString().contains("allPossibleBranchesCoverReference=true"));assertTrue(result.proof().toString().contains("categoryApplicability=UNRESOLVED"));
    }
    @Test void afterPotentialCategoryLimitStillRequiresItsActualScope(){
        var c=corpus(ORIGINAL);var result=CertificateValidityDates.fact(resolved(c).fact(),c,"有效期",LocalDate.of(2025,11,6));
        assertFalse(result.complete());assertEquals("CATEGORY_SPECIFIC_PERIOD_REQUIRED",result.code());
    }
    @Test void unresolvedRestrictionCannotConcealAnAlreadyExpiredUseDocument(){
        var c=corpus(ORIGINAL);var result=CertificateValidityDates.fact(resolved(c).fact(),c,"有效期",LocalDate.of(2025,11,14));
        assertEquals(false,result.valid());assertTrue(result.proof().containsKey("unresolvedCategoryLimits"));
    }
    @Test void mismatchingNumberQueryAndCompetingMainDatesAreNotHeaderProof(){
        for(String text:List.of(ORIGINAL.replace("D10009999","D10000000"),"查询截图\n"+ORIGINAL,ORIGINAL+"\n有效期：2029-01-01",ORIGINAL+"\n证书编号：D20009999 有效期：2029-01-01")){
            var c=corpus(text);assertTrue(QualificationCertificateEvidence.resolve(selected(c),c.all.get(0),c).isEmpty(),text);
        }
    }
    @Test void bareCategoryDateRangeCannotBeInterpretedAsAnExpiryCeiling(){
        var c=corpus(ORIGINAL.replace("有效期至 2025/11/05","有效期从 2025/09/01 至 2025/11/05"));
        assertTrue(QualificationCertificateEvidence.resolve(selected(c),c.all.get(0),c).isEmpty(),"a second conflicting use of the same label cannot be ignored when selecting the header");
    }
}
