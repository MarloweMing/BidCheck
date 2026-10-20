package cn.bidcheck.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FocusedOwnObligationTest {
    @Test void certificateBusinessObligationRemainsVisibleBesideItsReferenceTime(){
        var date=Map.<String,Object>of("id","R-date","description","截止日期","predicate","REFERENCE_TIME","children",List.of());
        var certificate=Map.<String,Object>of("id","R","description","证书在截止日有效","predicate","VALIDITY","logic","ALL","children",List.of(date));
        var decisions=Map.of("R::own",Map.<String,Object>of("id","R::own","status","UNDETERMINED","reason","缺少证书有效期","gaps",List.of()),"R-date",Map.<String,Object>of("id","R-date","status","PASS","reason","已确认日期"));
        List<Map<String,Object>> conditions=new ArrayList<>();FocusedCheckEvaluator.leaves(certificate,decisions,conditions,null,false);
        assertEquals(2,conditions.size());assertEquals("R::own",conditions.get(0).get("id"));assertEquals("VALIDITY",conditions.get(0).get("predicate"));assertEquals("UNDETERMINED",conditions.get(0).get("status"));assertEquals("REFERENCE_TIME",conditions.get(1).get("predicate"));
        assertEquals("REQUIREMENT",conditions.get(0).get("conditionRole"));assertEquals("AUXILIARY",conditions.get(1).get("conditionRole"));
    }
    @Test void pureLogicalContainerDoesNotInventAnExtraBusinessObligation(){
        var child=Map.<String,Object>of("id","R1","description","承诺书","predicate","MATERIAL","children",List.of());
        var group=Map.<String,Object>of("id","R","description","任一证明","predicate","GROUP","logic","ANY","children",List.of(child));
        List<Map<String,Object>> conditions=new ArrayList<>();FocusedCheckEvaluator.leaves(group,Map.of(),conditions,null,false);assertEquals(1,conditions.size());assertEquals("R1",conditions.get(0).get("id"));
    }
}
