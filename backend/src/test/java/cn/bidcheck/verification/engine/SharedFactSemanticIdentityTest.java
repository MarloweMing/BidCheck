package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class SharedFactSemanticIdentityTest {
    private Fact fact(String kind,String basis,String value){return new Fact("original",kind,"","commitment",value,value,"",basis,"CURRENT_PROJECT",List.of("Sa7e6cb0f0dfbb7808607"),Map.of());}
    @Test void loggedPage475DeclarationOnlyChangingBasisHasOneCanonicalIdentity(){
        String value="拟委派关键岗位人员未超出法律规定的同时承接项目的最大数量承接本项目";
        Fact first=SharedFactReuse.canonical(fact("DECLARATION","人员承诺书明确承诺拟委派关键岗位人员未超出法律规定的同时承接项目的最大数量",value));
        Fact later=SharedFactReuse.canonical(fact("DECLARATION","人员承诺书",value));
        assertEquals(first.id(),later.id());assertNotEquals(first.basis(),later.basis(),"audit explanation is retained, not erased");
        assertNotEquals(first.id(),SharedFactReuse.canonical(fact("DECLARATION","人员承诺书","拟派人员已超出最大数量")).id());
    }
    @Test void materialExplanationsAreAuditButMonetaryBasisIsBusinessIdentity(){
        assertEquals(SharedFactReuse.canonical(fact("MATERIAL","原文第 1 页","证书")).id(),SharedFactReuse.canonical(fact("MATERIAL","已找到原文","证书")).id());
        for(String kind:List.of("NUMBER","FIELD","CONTRACT"))assertNotEquals(SharedFactReuse.canonical(fact(kind,"含税总价","100")).id(),SharedFactReuse.canonical(fact(kind,"不含税单价","100")).id());
    }
    @Test void legacyOccurrencesWithDifferentExplanationsAreRevalidatedAsOneCurrentFact(){
        String value="拟委派关键岗位人员未超出法律规定的同时承接项目的最大数量承接本项目";
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,"人员承诺书\n我方承诺："+value+"。若有违反，我方愿意承担相应法律责任。")));
        Map<String,Object> first=new LinkedHashMap<>(SharedFactReuse.value(fact("DECLARATION","人员承诺书",value)));
        first.put("id","legacy-first");first.put("sourceIds",List.of(corpus.all.get(0).id()));
        Map<String,Object> second=new LinkedHashMap<>(first);second.put("id","legacy-second");second.put("basis","人员承诺书明确承诺未超出数量");
        SharedFactReuse reuse=new SharedFactReuseTest().reuse(corpus,List.of(SharedFactReuseTest.entry(corpus,first),SharedFactReuseTest.entry(corpus,second)));
        Rule rule=new Rule("r","人员承诺","DECLARATION","","commitment","EXISTS",value,"",Map.of(),value,"",List.of());
        var found=reuse.lookup(List.of(rule),corpus.all,List.of());assertEquals(1,found.size());
        assertEquals("legacy-first",first.get("id"));assertEquals("legacy-second",second.get("id"),"historical input receipts are not rewritten");
    }
    @Test void sourceReferenceOrderAndRepeatedReferencesDoNotCreateAnotherCanonicalFact(){
        Fact first=new Fact("old","DECLARATION","","commitment","已承诺","已承诺","","说明","CURRENT_PROJECT",List.of("S1","S2"),Map.of("cells",List.of("姓名","岗位")));
        Fact reordered=new Fact("new",first.kind(),first.subject(),first.field(),first.rawValue(),first.value(),first.unit(),first.basis(),first.scope(),List.of("S2","S1","S2"),first.attributes());
        assertEquals(SharedFactReuse.canonical(first).id(),SharedFactReuse.canonical(reordered).id());
        Fact changedCells=new Fact("new",first.kind(),first.subject(),first.field(),first.rawValue(),first.value(),first.unit(),first.basis(),first.scope(),first.sourceIds(),Map.of("cells",List.of("岗位","姓名")));
        assertNotEquals(SharedFactReuse.canonical(first).id(),SharedFactReuse.canonical(changedCells).id(),"table-cell ordering remains significant");
    }
}
