package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.ocr.OcrLayout;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.facts.LedgerValues.*;
import static cn.bidcheck.verification.facts.StructuralFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuralLedgerTest {
    @Test void layoutOnlyChangeInvalidatesOldOccurrencesAndParserFingerprint(){var helper=new FactLedgerServiceTest();var d=helper.doc("d","合成甲：一级");helper.publish("run","cp",List.of(d),helper.batch(List.of(helper.fact("f","一级","S")),List.of(helper.source(d,"S"))));var page=d.pages().get(0);var next=new Page(page.number(),page.text(),page.locationType(),page.parser(),page.warning(),OcrLayout.unavailable("结构版本已重新解析"));var newDoc=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),"",List.of(next),List.of());assertNotEquals(FactLedgerService.parserFingerprint(page),FactLedgerService.parserFingerprint(next));assertTrue(helper.lookup(List.of(newDoc)).isEmpty());}
    @Test void nativeRowIdentityAndChainProofAreRecomputedAndFrozen(){var helper=new FactLedgerServiceTest();var fixture=row(1,false,false,ID,"2025-01、2025-02、2025-03");var d=helper.doc("d",fixture.text());var fact=helper.fact("f","缴费月份","S");fact.put("kind","MONTHS");fact.put("subject",NAME);fact.put("attributes",of("person",NAME,"months",List.of("2025-01","2025-02","2025-03"),"cellIds",ids(fixture),"structureProof",Map.of("status","MODEL_FORGED")));
        var batch=helper.batch(List.of(fact),List.of(helper.source(d,"S")));batch.put("layoutCells",fixture.cells());batch.put("layoutIdentity","test-layout-1");assertEquals(1,helper.publish("run","cp",List.of(d),batch).get("accepted"));var result=helper.lookup(List.of(d)).get(0);assertEquals("STRONG",result.get("identityStrength"));assertTrue(canonical(result).contains("SOURCE_BOUND"));assertFalse(canonical(result).contains("MODEL_FORGED"));var snapshot=helper.ledger.snapshot("project","run","engine-identity",List.of(d));fixture.cells().clear();assertEquals(snapshot,helper.view("run",List.of(d)));}
    @Test void inventedModelStructureProofCannotUpgradeIdentityWithoutLayout(){var helper=new FactLedgerServiceTest();var d=helper.doc("d","合成人甲 缴费月份2025-01");var fact=helper.fact("f","缴费月份","S");fact.put("kind","MONTHS");fact.put("subject",NAME);fact.put("attributes",of("person",NAME,"structureProof",Map.of("status","SOURCE_BOUND","identityStrength","STRONG")));helper.publish("run","cp",List.of(d),helper.batch(List.of(fact),List.of(helper.source(d,"S"))));var out=helper.lookup(List.of(d)).get(0);assertEquals("SOURCE_LOCAL",out.get("identityStrength"));assertFalse(map(map(out.get("fact")).get("attributes")).containsKey("structureProof"));}
}
