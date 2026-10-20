package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessObligationGroupsTest {
    private Finding finding(String id,String expected,String source,String type,String category) {
        var evidence=new Evidence("tender","招标",1,"PARAGRAPH",source,"",1);
        var cp=new Checkpoint(id,"经营状况",category,"HIGH",type,"material","CONTAINS",expected,"",true,true,true,evidence);
        return new Finding(id,"run",cp,new Evaluation("UNDETERMINED","待核","",List.of(),0,"RULE"),Map.of());
    }
    @Test void mergesRepeatedReferencesWithoutDeletingTheirResults() {
        var a=finding("1","经营状况：须提供承诺书。","经营状况：须提供承诺书。","SEMANTIC_RESPONSE","资格");
        var b=finding("2","经营状况\t须提供承诺书","经营状况\t须提供承诺书","SEMANTIC_RESPONSE","资格");
        var groups=BusinessObligationGroups.summarize(List.of(a,b));
        assertEquals(1,groups.size());assertEquals(List.of("1","2"),groups.get(0).get("resultIds"));assertEquals(2,groups.get(0).get("sourceCount"));
    }
    @Test void doesNotMergeDifferentLotExceptionOrScoringContext() {
        var a=finding("1","提供承诺书","第一标段：提供承诺书","SEMANTIC_RESPONSE","资格");
        var b=finding("2","提供承诺书","第二标段：提供承诺书","SEMANTIC_RESPONSE","资格");
        var c=finding("3","提供承诺书","第一标段：提供承诺书","SEMANTIC_RESPONSE","评分");
        assertEquals(3,BusinessObligationGroups.summarize(List.of(a,b,c)).size());
    }
    @Test void numericPunctuationAndAndOrAreNotDiscarded() {
        assertNotEquals(BusinessObligationGroups.canonical("金额1.2万元"),BusinessObligationGroups.canonical("金额12万元"));
        assertNotEquals(BusinessObligationGroups.canonical("甲或乙"),BusinessObligationGroups.canonical("甲和乙"));
        assertNotEquals(BusinessObligationGroups.canonical("金额1 00万元"),BusinessObligationGroups.canonical("金额100万元"));
    }
    @Test void identicalShortWordingOnDifferentPagesIsNotAssumedSameScope(){
        var a=finding("1","提交营业执照","提交营业执照","SEMANTIC_RESPONSE","资格");
        var cp=a.checkpoint();var b=new Finding("2","run",new Checkpoint("2",cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),true,true,true,new Evidence("tender","招标",2,"PARAGRAPH",cp.source().quote(),"",1)),a.evaluation(),Map.of());
        assertEquals(2,BusinessObligationGroups.summarize(List.of(a,b)).size());
    }
}
