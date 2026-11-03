package cn.bidcheck.consistency;

import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

class PersonNoiseReceiptTest {
    private Document doc() {
        return new Document("bid","project","BID","投标.pdf",1,"sha","",
                cn.bidcheck.compat.Java8Api.listOf(new Page(1,"资格证书\n本证书表明持证人通过国家统一组织的考试，取得一级建造师的执业资格。\n", "PAGE","OCR_TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
    }
    @Test void noiseReceiptsRemainSourceBoundWithoutCreatingAnActionableCoverageFailure() {
        cn.bidcheck.domain.Domain.Document doc=doc();cn.bidcheck.document.PersonCredentialFacts.Result result=PersonCredentialFacts.analyzeForConsistency(doc,doc.pages().get(0));
        java.util.Map<java.lang.String, java.lang.Object> coverage=map(new PersonCredentialConsistency().scan(doc,cn.bidcheck.compat.Java8Api.listOf(result)).get("coverage"));
        assertEquals(1,coverage.get("extractionNoiseRejectedCount"));
        assertEquals(0,coverage.get("sourceRejected"));
        assertFalse(PersonCredentialConsistency.requiresReview(coverage));
        java.util.Map<java.lang.String, java.lang.Object> receipt=maps(coverage.get("extractionNoiseRejections")).get(0);
        assertEquals(doc.pages().get(0).text().substring(((Number)receipt.get("start")).intValue(),((Number)receipt.get("end")).intValue()),receipt.get("rawValue"));
        assertEquals("PERSON_NAME_DESCRIPTION",receipt.get("reasonCode"));
        assertNotEquals("",receipt.get("oldOccurrenceId"));
    }
    @Test void malformedNoiseReceiptMustNotSilentlySuppressASourceFailure() {
        cn.bidcheck.domain.Domain.Document doc=doc();cn.bidcheck.document.PersonCredentialFacts.Result result=PersonCredentialFacts.analyzeForConsistency(doc,doc.pages().get(0));
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> receipt=new LinkedHashMap<>(result.issues().stream().filter(i->"PERSON_EXTRACTION_NOISE_REJECTED".equals(i.get("code"))).findFirst().orElseThrow(java.util.NoSuchElementException::new));
        receipt.put("documentHash","another-document");
        cn.bidcheck.document.PersonCredentialFacts.Result invalid=new PersonCredentialFacts.Result(1,"PAGE",true,true,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(receipt),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> coverage=map(new PersonCredentialConsistency().scan(doc,cn.bidcheck.compat.Java8Api.listOf(invalid)).get("coverage"));
        assertEquals(0,coverage.get("extractionNoiseRejectedCount"));
        assertEquals(1,coverage.get("sourceRejected"));
        assertTrue(PersonCredentialConsistency.requiresReview(coverage));
    }
}
