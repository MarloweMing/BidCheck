package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEvidenceIdentityTest {
    private static Map<String,Object> source(int page,List<Integer> bbox){
        return cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid-1", "documentHash", "frozen-hash", "documentVersion", 1, "page", page, "bbox", bbox);
    }
    private static Map<String,Object> change(List<Map<String,Object>> sources,Map<String,Object> attributes){
        return cn.bidcheck.compat.Java8Api.mapOf("recordType", "FACT", "sources", sources, "fact", cn.bidcheck.compat.Java8Api.mapOf("kind", "PERSON_ROLE", "subject", "项目负责人", "field", "姓名", "value", "张三", "attributes", attributes));
    }

    @Test void boundingBoxPositionChangesEvidenceIdentity(){
        String first=RecoveryEvidenceIdentity.key(change(cn.bidcheck.compat.Java8Api.listOf(source(22,cn.bidcheck.compat.Java8Api.listOf(10, 20, 30, 40))),cn.bidcheck.compat.Java8Api.mapOf()));
        String reordered=RecoveryEvidenceIdentity.key(change(cn.bidcheck.compat.Java8Api.listOf(source(22,cn.bidcheck.compat.Java8Api.listOf(20, 10, 40, 30))),cn.bidcheck.compat.Java8Api.mapOf()));
        assertNotEquals(first,reordered,"different positional coordinates must trigger a new evidence input");
    }

    @Test void sourceCollectionOrderAndDuplicateReceiptsDoNotChangeEvidenceIdentity(){
        Map<String,Object> first=source(22,cn.bidcheck.compat.Java8Api.listOf(10, 20, 30, 40)),second=source(104,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3, 4));
        assertEquals(RecoveryEvidenceIdentity.key(change(cn.bidcheck.compat.Java8Api.listOf(first, second),cn.bidcheck.compat.Java8Api.mapOf())),
            RecoveryEvidenceIdentity.key(change(cn.bidcheck.compat.Java8Api.listOf(second, first, second),cn.bidcheck.compat.Java8Api.mapOf())),
            "source membership is explicitly unordered; repeat receipts are not new evidence");
    }

    @Test void orderedAttributePathsAndRepeatedCellsRemainSignificant(){
        List<Map<String,Object>> sources=cn.bidcheck.compat.Java8Api.listOf(source(22,cn.bidcheck.compat.Java8Api.listOf(10, 20, 30, 40)));
        String original=RecoveryEvidenceIdentity.key(change(sources,cn.bidcheck.compat.Java8Api.mapOf("path", cn.bidcheck.compat.Java8Api.listOf("人员", "证书"), "cells", cn.bidcheck.compat.Java8Api.listOf("张三", "张三"))));
        assertNotEquals(original,RecoveryEvidenceIdentity.key(change(sources,cn.bidcheck.compat.Java8Api.mapOf("path", cn.bidcheck.compat.Java8Api.listOf("证书", "人员"), "cells", cn.bidcheck.compat.Java8Api.listOf("张三", "张三")))));
        assertNotEquals(original,RecoveryEvidenceIdentity.key(change(sources,cn.bidcheck.compat.Java8Api.mapOf("path", cn.bidcheck.compat.Java8Api.listOf("人员", "证书"), "cells", cn.bidcheck.compat.Java8Api.listOf("张三")))));
    }

    @Test void actualSetAttributesRemainOrderIndependent(){
        assertEquals(RecoveryEvidenceIdentity.canonical(new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf("a", "b"))),
            RecoveryEvidenceIdentity.canonical(new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf("b", "a"))));
    }

    @Test void declarationExplanationCannotPublishAnotherSemanticEvidenceEvent(){
        Map<String,Object> fact=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("kind", "DECLARATION", "field", "commitment", "value", "拟委派人员未超出最大承接数量", "basis", "人员承诺书"));
        Map<String,Object> event=cn.bidcheck.compat.Java8Api.mapOf("recordType", "FACT", "fact", fact, "sources", cn.bidcheck.compat.Java8Api.listOf(source(475,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3, 4))));
        String first=RecoveryEvidenceIdentity.key(event);fact.put("basis","人员承诺书明确承诺未超出数量");
        assertEquals(first,RecoveryEvidenceIdentity.key(event));
        fact.put("value","拟委派人员已超出最大承接数量");assertNotEquals(first,RecoveryEvidenceIdentity.key(event));
    }

    @Test void numericAndFieldPricingBasisRemainBusinessInput(){
        for(String kind:cn.bidcheck.compat.Java8Api.listOf("NUMBER", "FIELD", "CONTRACT")){
            Map<String,Object> fact=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("kind", kind, "field", "amount", "value", "100", "basis", "含税总报价"));
            Map<String,Object> event=cn.bidcheck.compat.Java8Api.mapOf("recordType", "FACT", "fact", fact, "sources", cn.bidcheck.compat.Java8Api.listOf(source(12,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3, 4))));
            String first=RecoveryEvidenceIdentity.key(event);fact.put("basis","不含税单价");assertNotEquals(first,RecoveryEvidenceIdentity.key(event));
        }
    }
}
