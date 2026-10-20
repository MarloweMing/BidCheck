package cn.bidcheck.checktemplate;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.engine.FactExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTemplateBridgeTest {
    private final LegacyTemplateBridge bridge=new LegacyTemplateBridge();
    private final Evidence source=new Evidence("tender","招标文件",3,"PAGE","最高限价100万元","p3-l1",1);

    @Test void bridgeAcceptsOnlyExplicitLegacyTypes(){
        assertFalse(bridge.supports(checkpoint("SEMANTIC_RESPONSE","material","","提供承诺书","")));
        assertFalse(bridge.supports(checkpoint("VALUE_COMPARE","duration","<=","60","DAY")));
        assertTrue(bridge.supports(checkpoint("VALUE_COMPARE","totalPrice","<=","100","万元")));
        assertTrue(bridge.supports(checkpoint("MATERIAL_EXISTENCE","material","EXISTS","营业执照","")));
    }

    @Test void legacyPriceUsesTemplateProjectionAndNeverCompilesARule(){
        Checkpoint checkpoint=checkpoint("VALUE_COMPARE","totalPrice","<=","100","万元");
        Document bid=new Document("bid","project","BID","投标文件",1,"sha","",List.of(new Page(1,"投标函\n投标总报价：99万元","PAGE","PDF",null)),List.of());
        LegacyTemplateBridge.BridgeResult result=bridge.evaluate(checkpoint,bid,null,new FactExtractor().extract(bid));
        assertEquals("PASS",result.evaluation().status());
        assertEquals("PRICE_COMPARE",result.spec().templateId());
        assertEquals("1000000",result.spec().parameters().get("expected"));
        assertEquals(0,((Map<?,?>)result.assessment().get("templateTrace")).get("compileRequests"));
        Map<?,?> projection=(Map<?,?>)result.assessment().get("templateResult");
        assertEquals("template-result-v1",projection.get("schemaVersion"));
        assertEquals(false,projection.get("finalReconciled"));
    }

    @Test void unsupportedOrUnconfirmedCheckpointCannotSilentlyEnterTemplateRoute(){
        Checkpoint base=checkpoint("VALUE_COMPARE","totalPrice","<=","100","万元");
        Checkpoint unconfirmed=new Checkpoint(base.id(),base.title(),base.category(),base.severity(),base.type(),base.field(),base.operator(),base.expected(),base.unit(),base.required(),false,true,base.source());
        assertFalse(bridge.supports(unconfirmed));
        assertThrows(IllegalArgumentException.class,()->bridge.evaluate(unconfirmed,null,null,List.of()));
    }

    private Checkpoint checkpoint(String type,String field,String operator,String expected,String unit){return new Checkpoint("cp","测试","响应检查","HIGH",type,field,operator,expected,unit,true,true,true,source);}
}
