package cn.bidcheck.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FocusedOwnObligationTest {
    @Test void certificateBusinessObligationRemainsVisibleBesideItsReferenceTime(){
        java.util.Map<java.lang.String, java.lang.Object> date=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("id", "R-date", "description", "截止日期", "predicate", "REFERENCE_TIME", "children", cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> certificate=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("id", "R", "description", "证书在截止日有效", "predicate", "VALIDITY", "logic", "ALL", "children", cn.bidcheck.compat.Java8Api.listOf(date));
        java.util.Map<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> decisions=cn.bidcheck.compat.Java8Api.mapOf("R::own", cn.bidcheck.compat.Java8Api.<String, Object>mapOf("id", "R::own", "status", "UNDETERMINED", "reason", "缺少证书有效期", "gaps", cn.bidcheck.compat.Java8Api.listOf()), "R-date", cn.bidcheck.compat.Java8Api.<String, Object>mapOf("id", "R-date", "status", "PASS", "reason", "已确认日期"));
        List<Map<String,Object>> conditions=new ArrayList<>();FocusedCheckEvaluator.leaves(certificate,decisions,conditions,null,false);
        assertEquals(2,conditions.size());assertEquals("R::own",conditions.get(0).get("id"));assertEquals("VALIDITY",conditions.get(0).get("predicate"));assertEquals("UNDETERMINED",conditions.get(0).get("status"));assertEquals("REFERENCE_TIME",conditions.get(1).get("predicate"));
        assertEquals("REQUIREMENT",conditions.get(0).get("conditionRole"));assertEquals("AUXILIARY",conditions.get(1).get("conditionRole"));
    }
    @Test void pureLogicalContainerDoesNotInventAnExtraBusinessObligation(){
        java.util.Map<java.lang.String, java.lang.Object> child=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("id", "R1", "description", "承诺书", "predicate", "MATERIAL", "children", cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> group=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("id", "R", "description", "任一证明", "predicate", "GROUP", "logic", "ANY", "children", cn.bidcheck.compat.Java8Api.listOf(child));
        List<Map<String,Object>> conditions=new ArrayList<>();FocusedCheckEvaluator.leaves(group,cn.bidcheck.compat.Java8Api.mapOf(),conditions,null,false);assertEquals(1,conditions.size());assertEquals("R1",conditions.get(0).get("id"));
    }
}
