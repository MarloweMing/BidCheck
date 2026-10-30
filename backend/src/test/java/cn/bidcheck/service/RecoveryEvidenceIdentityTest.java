package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEvidenceIdentityTest {
    private static Map<String,Object> source(int page,List<Integer> bbox){
        return Map.of("documentId","bid-1","documentHash","frozen-hash","documentVersion",1,"page",page,"bbox",bbox);
    }
    private static Map<String,Object> change(List<Map<String,Object>> sources,Map<String,Object> attributes){
        return Map.of("recordType","FACT","sources",sources,"fact",Map.of("kind","PERSON_ROLE","subject","项目负责人","field","姓名","value","张三","attributes",attributes));
    }

    @Test void boundingBoxPositionChangesEvidenceIdentity(){
        String first=RecoveryEvidenceIdentity.key(change(List.of(source(22,List.of(10,20,30,40))),Map.of()));
        String reordered=RecoveryEvidenceIdentity.key(change(List.of(source(22,List.of(20,10,40,30))),Map.of()));
        assertNotEquals(first,reordered,"different positional coordinates must trigger a new evidence input");
    }

    @Test void sourceCollectionOrderAndDuplicateReceiptsDoNotChangeEvidenceIdentity(){
        Map<String,Object> first=source(22,List.of(10,20,30,40)),second=source(104,List.of(1,2,3,4));
        assertEquals(RecoveryEvidenceIdentity.key(change(List.of(first,second),Map.of())),
            RecoveryEvidenceIdentity.key(change(List.of(second,first,second),Map.of())),
            "source membership is explicitly unordered; repeat receipts are not new evidence");
    }

    @Test void orderedAttributePathsAndRepeatedCellsRemainSignificant(){
        List<Map<String,Object>> sources=List.of(source(22,List.of(10,20,30,40)));
        String original=RecoveryEvidenceIdentity.key(change(sources,Map.of("path",List.of("人员","证书"),"cells",List.of("张三","张三"))));
        assertNotEquals(original,RecoveryEvidenceIdentity.key(change(sources,Map.of("path",List.of("证书","人员"),"cells",List.of("张三","张三")))));
        assertNotEquals(original,RecoveryEvidenceIdentity.key(change(sources,Map.of("path",List.of("人员","证书"),"cells",List.of("张三")))));
    }

    @Test void actualSetAttributesRemainOrderIndependent(){
        assertEquals(RecoveryEvidenceIdentity.canonical(new LinkedHashSet<>(List.of("a","b"))),
            RecoveryEvidenceIdentity.canonical(new LinkedHashSet<>(List.of("b","a"))));
    }

    @Test void declarationExplanationCannotPublishAnotherSemanticEvidenceEvent(){
        Map<String,Object> fact=new LinkedHashMap<>(Map.of("kind","DECLARATION","field","commitment","value","拟委派人员未超出最大承接数量","basis","人员承诺书"));
        Map<String,Object> event=Map.of("recordType","FACT","fact",fact,"sources",List.of(source(475,List.of(1,2,3,4))));
        String first=RecoveryEvidenceIdentity.key(event);fact.put("basis","人员承诺书明确承诺未超出数量");
        assertEquals(first,RecoveryEvidenceIdentity.key(event));
        fact.put("value","拟委派人员已超出最大承接数量");assertNotEquals(first,RecoveryEvidenceIdentity.key(event));
    }

    @Test void numericAndFieldPricingBasisRemainBusinessInput(){
        for(String kind:List.of("NUMBER","FIELD","CONTRACT")){
            Map<String,Object> fact=new LinkedHashMap<>(Map.of("kind",kind,"field","amount","value","100","basis","含税总报价"));
            Map<String,Object> event=Map.of("recordType","FACT","fact",fact,"sources",List.of(source(12,List.of(1,2,3,4))));
            String first=RecoveryEvidenceIdentity.key(event);fact.put("basis","不含税单价");assertNotEquals(first,RecoveryEvidenceIdentity.key(event));
        }
    }
}
