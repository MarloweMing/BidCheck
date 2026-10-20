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
                List.of(new Page(1,"资格证书\n本证书表明持证人通过国家统一组织的考试，取得一级建造师的执业资格。\n", "PAGE","OCR_TEXT",null)),List.of());
    }
    @Test void noiseReceiptsRemainSourceBoundWithoutCreatingAnActionableCoverageFailure() {
        var doc=doc();var result=PersonCredentialFacts.analyzeForConsistency(doc,doc.pages().get(0));
        var coverage=map(new PersonCredentialConsistency().scan(doc,List.of(result)).get("coverage"));
        assertEquals(1,coverage.get("extractionNoiseRejectedCount"));
        assertEquals(0,coverage.get("sourceRejected"));
        assertFalse(PersonCredentialConsistency.requiresReview(coverage));
        var receipt=maps(coverage.get("extractionNoiseRejections")).get(0);
        assertEquals(doc.pages().get(0).text().substring(((Number)receipt.get("start")).intValue(),((Number)receipt.get("end")).intValue()),receipt.get("rawValue"));
        assertEquals("PERSON_NAME_DESCRIPTION",receipt.get("reasonCode"));
        assertNotEquals("",receipt.get("oldOccurrenceId"));
    }
    @Test void malformedNoiseReceiptMustNotSilentlySuppressASourceFailure() {
        var doc=doc();var result=PersonCredentialFacts.analyzeForConsistency(doc,doc.pages().get(0));
        var receipt=new LinkedHashMap<>(result.issues().stream().filter(i->"PERSON_EXTRACTION_NOISE_REJECTED".equals(i.get("code"))).findFirst().orElseThrow());
        receipt.put("documentHash","another-document");
        var invalid=new PersonCredentialFacts.Result(1,"PAGE",true,true,List.of(),List.of(receipt),List.of());
        var coverage=map(new PersonCredentialConsistency().scan(doc,List.of(invalid)).get("coverage"));
        assertEquals(0,coverage.get("extractionNoiseRejectedCount"));
        assertEquals(1,coverage.get("sourceRejected"));
        assertTrue(PersonCredentialConsistency.requiresReview(coverage));
    }
}
