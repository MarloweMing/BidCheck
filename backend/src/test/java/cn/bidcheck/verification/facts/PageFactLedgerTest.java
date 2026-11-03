package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.FactRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

class PageFactLedgerTest {
    final FactLedgerServiceTest.MemoryStore store=new FactLedgerServiceTest.MemoryStore();
    final FactRepository core=FactRepository.memoryForTests();
    final FactLedgerService ledger=new FactLedgerService(store,core);
    static final String VIN="LSVA1234567890123";
    Page page(int number,String text){return new Page(number,text,"PAGE","QWEN_PDF_OCR","");}
    Document doc(Page... pages){return new Document("doc","project","BID","投标.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(pages),cn.bidcheck.compat.Java8Api.listOf());}
    Page vehicle(int number,String plate){return page(number,"车牌号码："+plate+"\n车辆识别代号："+VIN+"\n发动机号码：ABCDE12345");}
    Map<String,Object> view(Document doc){return ledger.documentView(doc,"parser-v1");}
    Map<String,Object> persist(Document doc,Page page){return ledger.persistPageFacts(doc,page,"parser-v1");}
    long count(Map<String,Object> summary,String key){return ((Number)summary.get(key)).longValue();}
    Map<String,Object> source(Document doc,Page p,String id,String quote){return of("id",id,"projectId",doc.projectId(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",FactLedgerService.parserFingerprint(p),"evidence",new Evidence(doc.id(),doc.name(),p.number(),p.locationType(),quote,"",1));}
    Map<String,Object> fact(String id,String source,String value){return of("id",id,"kind","FIELD","subject","合成甲","field","qualification","rawValue",value,"value",value,"scope","UNRESOLVED","unit","","basis","","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf("entityType", "PERSON"));}

    @Test void newPagesDoNotChangeOldOccurrenceIdentityAndReplayIsIdempotent(){
        Page first=vehicle(18,"京A12345"),second=vehicle(137,"京A54321");Document partial=doc(first),full=doc(first,second);
        java.util.Map<java.lang.String, java.lang.Object> initial=persist(partial,first);assertEquals(3L,initial.get("accepted"));
        persist(full,second);java.util.Map<java.lang.String, java.lang.Object> replay=persist(full,first);assertEquals(initial.get("occurrenceIds"),replay.get("occurrenceIds"));assertEquals(3,replay.get("reused"));
        java.util.Map<java.lang.String, java.lang.Object> current=view(full);assertEquals(6,count(map(current.get("summary")),"occurrences"));assertEquals(2,count(map(current.get("summary")),"pages"));
        assertEquals(1,maps(current.get("entities")).size());assertEquals("VEHICLE",maps(current.get("entities")).get(0).get("type"));
        assertEquals(1,maps(current.get("conflicts")).size());java.util.Map<java.lang.String, java.lang.Object> conflict=maps(current.get("conflicts")).get(0);assertEquals("plateNumber",conflict.get("field"));assertEquals("REVIEW_REQUIRED",conflict.get("status"));assertEquals("HIGH",conflict.get("riskLevel"));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(18, 137),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(maps(conflict.get("sourceRefs")).stream().map(e->(Integer)e.get("page")))));
    }

    @Test void correctedPageReplacesOnlyItsCurrentPointerWhileOldEvidenceRemains(){
        Page first=vehicle(18,"京A12345"),old=vehicle(137,"京A54321");Document before=doc(first,old);
        java.util.Map<java.lang.String, java.lang.Object> one=persist(before,first);java.util.List<java.lang.String> oldIds=strings(persist(before,old).get("occurrenceIds"));assertEquals(1,maps(view(before).get("conflicts")).size());
        Page corrected=vehicle(137,"京A12345");Document after=doc(first,corrected);
        assertEquals(3,count(map(view(after).get("summary")),"occurrences"));
        persist(after,corrected);java.util.Map<java.lang.String, java.lang.Object> current=view(after);assertEquals(6,count(map(current.get("summary")),"occurrences"));assertTrue(maps(current.get("conflicts")).isEmpty());
        for(String id:oldIds)assertFalse(core.get(id).isEmpty(),"history is immutable");
        assertTrue(cn.bidcheck.compat.Java8Api.toList(maps(current.get("occurrences")).stream().map(o->o.get("occurrenceId"))).containsAll(strings(one.get("occurrenceIds"))));
    }

    @Test void blankReplacementRemovesCurrentFactsWithoutDeletingHistory(){
        Page original=vehicle(1,"京A12345");java.util.List<java.lang.String> ids=strings(persist(doc(original),original).get("occurrenceIds"));
        Page unavailable=page(1,"");persist(doc(unavailable),unavailable);assertTrue(maps(view(doc(unavailable)).get("occurrences")).isEmpty());assertFalse(core.get(ids.get(0)).isEmpty());
    }

    @Test void everyPageEvidenceRetainsOriginalSourceAndNeverGetsSyntheticBox(){
        Page page=vehicle(1,"京A12345");Document doc=doc(page);persist(doc,page);
        for(Map<String,Object> occurrence:maps(view(doc).get("occurrences"))){assertEquals(false,occurrence.get("geometryAvailable"));for(java.util.Map<java.lang.String, java.lang.Object> source:maps(occurrence.get("sources"))){java.util.Map<java.lang.String, java.lang.Object> evidence=map(source.get("evidence"));assertEquals(doc.sha256(),source.get("documentHash"));assertEquals(1,source.get("documentVersion"));assertEquals(1,evidence.get("page"));assertTrue(page.text().contains(text(evidence,"quote")));assertFalse(evidence.containsKey("bbox"));}}
    }

    @Test void projectDocumentVersionHashAndParserConfigurationStayIsolated(){
        Page page=vehicle(1,"京A12345");Document doc=doc(page);persist(doc,page);
        for(Document foreign:cn.bidcheck.compat.Java8Api.listOf(new Document(doc.id(),"foreign",doc.role(),doc.name(),doc.version(),doc.sha256(),"",doc.pages(),cn.bidcheck.compat.Java8Api.listOf()), new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),2,doc.sha256(),"",doc.pages(),cn.bidcheck.compat.Java8Api.listOf()), new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),1,cn.bidcheck.compat.Java8Api.repeat("b", 64),"",doc.pages(),cn.bidcheck.compat.Java8Api.listOf()), new Document("other",doc.projectId(),doc.role(),doc.name(),1,doc.sha256(),"",doc.pages(),cn.bidcheck.compat.Java8Api.listOf())))assertTrue(maps(view(foreign).get("occurrences")).isEmpty());
        assertTrue(maps(ledger.documentView(doc,"another-parser").get("occurrences")).isEmpty());
    }

    @Test void samePersonNameWithoutIdentifierNeverMergesAcrossPages(){
        Page a=page(1,"姓名：合成甲 类别一级"),b=page(2,"姓名：合成甲 类别二级");Document doc=doc(a,b);
        for(Page p:doc.pages()){String value=p.number()==1?"一级":"二级";Fact f=new Fact("PERSON","合成甲","category",value,value,new Evidence(doc.id(),doc.name(),p.number(),"PAGE",p.text(),"",1));ledger.persistSuppliedPageFacts(doc,p,cn.bidcheck.compat.Java8Api.listOf(f),"parser-v1");}
        assertEquals(2,maps(view(doc).get("entities")).size());assertTrue(maps(view(doc).get("conflicts")).isEmpty());
    }

    @Test void incompleteVinOrSeveralVehiclesCannotBecomeStrongIdentity(){
        for(String text:cn.bidcheck.compat.Java8Api.listOf("车牌号码：京A12345\n车辆识别代号：LSVA123456", "车牌号码：京A12345 车牌号码：京A54321\n车辆识别代号："+VIN)){
            java.util.Map<java.lang.String, java.lang.Object> entity=EntityIdentity.resolve(of("kind","FIELD","subject","vehicle","rawValue","京A12345","attributes",cn.bidcheck.compat.Java8Api.mapOf("entityType", "VEHICLE")),cn.bidcheck.compat.Java8Api.listOf(of("id","S","sourceFingerprint",text,"evidence",cn.bidcheck.compat.Java8Api.mapOf("quote", text))));assertEquals("SOURCE_LOCAL",entity.get("identityStrength"));
        }
    }

    @Test void sourceOutsidePageAndInventedQuotesAreRejectedWithoutLeakingValues(){
        Page page=page(1,"原文 一级");Document doc=doc(page);Fact wrong=new Fact("PERSON","合成甲","category","敏感虚构二级","二级",new Evidence(doc.id(),doc.name(),1,"PAGE","敏感虚构二级","",1));
        java.util.Map<java.lang.String, java.lang.Object> result=ledger.persistSuppliedPageFacts(doc,page,cn.bidcheck.compat.Java8Api.listOf(wrong),"parser-v1");assertEquals(0L,result.get("accepted"));assertFalse(canonical(result).contains("敏感"));assertTrue(maps(view(doc).get("occurrences")).isEmpty());
        assertThrows(IllegalArgumentException.class,()->persist(doc,page(2,"原文 一级")));
        assertThrows(IllegalArgumentException.class,()->persist(doc,page(1,"过期文本")));
    }

    @Test void overlappingWindowsDeduplicateSameSourceFactAndRetainOtherPages(){
        Page a=page(1,"表格第一页"),b=page(2,"表格第二页"),overlap=page(3,"合成甲 资格一级"),d=page(4,"表格第四页"),e=page(5,"表格第五页");Document doc=doc(a,b,overlap,d,e);
        java.util.Map<java.lang.String, java.lang.Object> first=ledger.persistContextFacts(doc,cn.bidcheck.compat.Java8Api.listOf(a, b, overlap),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(source(doc,overlap,"A",overlap.text())),"facts",cn.bidcheck.compat.Java8Api.listOf(fact("model-1","A","一级")),"operationIdentity","window-1"));
        java.util.Map<java.lang.String, java.lang.Object> second=ledger.persistContextFacts(doc,cn.bidcheck.compat.Java8Api.listOf(overlap, d, e),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(source(doc,overlap,"B",overlap.text())),"facts",cn.bidcheck.compat.Java8Api.listOf(fact("model-2","B","一级")),"operationIdentity","window-2"));
        assertEquals(first.get("occurrenceIds"),second.get("occurrenceIds"));assertEquals(1,second.get("reused"));assertEquals(1,maps(view(doc).get("occurrences")).size());
    }

    @Test void correctedContextPageInvalidatesWindowEvenIfFactIsOnUnchangedOverlap(){
        Page a=page(1,"表头"),b=page(2,"续页"),c=page(3,"合成甲 资格一级");Document doc=doc(a,b,c);
        ledger.persistContextFacts(doc,doc.pages(),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(source(doc,c,"A",c.text())),"facts",cn.bidcheck.compat.Java8Api.listOf(fact("model","A","一级"))));
        assertEquals(1,maps(view(doc).get("occurrences")).size());assertTrue(maps(view(doc(a,page(2,"纠正续页"),c)).get("occurrences")).isEmpty());
    }

    @Test void contextRejectsNonconsecutivePagesAndEvidenceOutsideWindow(){
        Page a=page(1,"合成甲 一级"),b=page(2,"续页"),c=page(8,"远处");Document doc=doc(a,b,c);
        assertThrows(IllegalArgumentException.class,()->ledger.persistContextFacts(doc,cn.bidcheck.compat.Java8Api.listOf(a, c),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(),"facts",cn.bidcheck.compat.Java8Api.listOf())));
        java.util.Map<java.lang.String, java.lang.Object> result=ledger.persistContextFacts(doc,cn.bidcheck.compat.Java8Api.listOf(a, b),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(source(doc,c,"A",c.text())),"facts",cn.bidcheck.compat.Java8Api.listOf(fact("model","A","远处"))));assertEquals(0L,result.get("accepted"));assertFalse(maps(result.get("rejected")).isEmpty());
    }

    @Test void sourceTableHeaderAndSameRowVinBindVisualFieldsWithoutTrustingModelRecordKey(){
        String row="| 1 | 京A54321 | "+VIN+" |";
        Page table=page(18,"车辆信息表\n| 序号 | 车牌号码 | 车辆识别代号 |\n|---|---|---|\n"+row),license=vehicle(137,"京A12345");Document doc=doc(table,license);
        java.util.Map<java.lang.String, java.lang.Object> f=fact("visual","A","京A54321");f.put("subject",VIN);f.put("field","车牌号码");f.put("basis","SOURCE_TEXT_AND_VISUAL");f.put("attributes",cn.bidcheck.compat.Java8Api.mapOf("entityType", "VEHICLE", "recordKey", "FORGED", "recordIdentityEstablished", true));
        java.util.Map<java.lang.String, java.lang.Object> result=ledger.persistContextFacts(doc,cn.bidcheck.compat.Java8Api.listOf(table),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(source(doc,table,"A",row)),"facts",cn.bidcheck.compat.Java8Api.listOf(f)));
        assertEquals(1L,result.get("accepted"));assertEquals(1,((List<?>)result.get("acceptedDomainFacts")).size());
        persist(doc,license);java.util.Map<java.lang.String, java.lang.Object> current=view(doc);assertEquals(1,maps(current.get("entities")).size());assertEquals(1,maps(current.get("conflicts")).size());
        java.util.Map<java.lang.String, java.lang.Object> context=maps(current.get("occurrences")).stream().filter(o->"CONTEXT_FACT".equals(o.get("sourceDomain"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);assertEquals(2,maps(context.get("sources")).size());
        assertEquals("VIN:"+VIN,ledger.documentFacts(doc,"parser-v1").stream().filter(v->v.evidence().page()==18).findFirst().orElseThrow(java.util.NoSuchElementException::new).entityKey());
    }

    @Test void tableColumnMismatchOrMissingVinHeaderNeverPromotesAModelKey(){
        for(String header:cn.bidcheck.compat.Java8Api.listOf("| 序号 | 车牌号码 | 证书编号 |", "| 序号 | 车辆识别代号 | 车牌号码 |")){
            String row="| 1 | 京A54321 | "+VIN+" |";Page p=page(1,header+"\n|---|---|---|\n"+row);Document doc=doc(p);
            java.util.Map<java.lang.String, java.lang.Object> f=fact("visual","A","京A54321");f.put("subject",VIN);f.put("field","车牌号码");f.put("attributes",cn.bidcheck.compat.Java8Api.mapOf("entityType", "VEHICLE", "recordKey", VIN, "recordIdentityEstablished", true));
            ledger.persistContextFacts(doc,cn.bidcheck.compat.Java8Api.listOf(p),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(source(doc,p,"A",row)),"facts",cn.bidcheck.compat.Java8Api.listOf(f)));
            assertTrue(maps(view(doc).get("entities")).stream().allMatch(e->"SOURCE_LOCAL".equals(e.get("identityStrength"))));assertTrue(ledger.documentFacts(doc,"parser-v1").isEmpty());
        }
    }

    @Test void nativeAndVisualCopiesAtOnePositionNeverBecomeTwoIndependentCheckEvidences(){
        Page page=vehicle(1,"京A12345");Document doc=doc(page);persist(doc,page);
        java.util.Map<java.lang.String, java.lang.Object> f=fact("visual","A","京A12345");f.put("subject","VIN:"+VIN);f.put("field","车牌号码");f.put("basis","SOURCE_TEXT_AND_VISUAL");f.put("attributes",cn.bidcheck.compat.Java8Api.mapOf("entityType", "VEHICLE"));
        ledger.persistContextFacts(doc,cn.bidcheck.compat.Java8Api.listOf(page),"parser-v1",of("sources",cn.bidcheck.compat.Java8Api.listOf(source(doc,page,"A",page.text())),"facts",cn.bidcheck.compat.Java8Api.listOf(f)));
        List<Fact> converted=ledger.documentFacts(doc,"parser-v1");assertEquals(1,converted.stream().filter(v->"plateNumber".equals(v.field())).count());
    }

    @Test void personnelCredentialsAreDurableSourceBoundAndComparedAcrossDistantPages(){
        Page first=credentialPage(1,"110101199001010011"),last=credentialPage(50,"110101199001010038");Document document=doc(first,last);
        java.util.Map<java.lang.String, java.lang.Object> saved=persist(document,first);persist(document,last);assertEquals(saved.get("occurrenceIds"),persist(document,first).get("occurrenceIds"));
        java.util.Map<java.lang.String, java.lang.Object> result=view(document);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> groups=maps(result.get("personCredentialGroups"));
        assertTrue(groups.stream().anyMatch(g->new HashSet<>(g.get("sourcePages") instanceof List<?>?((List<?>) (g.get("sourcePages"))):cn.bidcheck.compat.Java8Api.listOf()).equals(cn.bidcheck.compat.Java8Api.setOf(1, 50))),"Nonadjacent occurrences must share a source-backed comparison group");
        List<Fact> facts=cn.bidcheck.compat.Java8Api.toList(ledger.documentFacts(document,"parser-v1").stream().filter(f->"PERSON_CREDENTIAL".equals(f.entityType())));
        assertTrue(facts.stream().anyMatch(f->f.evidence().page()==1&&"110101199001010011".equals(f.rawValue())));
        assertTrue(facts.stream().anyMatch(f->f.evidence().page()==50&&"110101199001010038".equals(f.rawValue())));
        assertTrue(facts.stream().allMatch(f->document.pages().stream().anyMatch(p->p.number()==f.evidence().page()&&p.text().contains(f.evidence().quote()))));
        assertFalse(maps(result.get("personCredentialOccurrences")).isEmpty());
    }

    @Test void correctedCredentialPageRetractsCurrentConflictAndAnotherScopeCannotReuseComparison(){
        Page first=credentialPage(1,"110101199001010011"),last=credentialPage(50,"110101199001010038");Document before=doc(first,last);
        persist(before,first);java.util.List<java.lang.String> oldIds=strings(persist(before,last).get("occurrenceIds"));
        Page corrected=credentialPage(50,"110101199001010011");Document after=doc(first,corrected);persist(after,corrected);
        assertTrue(maps(view(after).get("personCredentialGroups")).stream().noneMatch(g->"CONFLICT".equals(g.get("status"))||"SUSPECTED".equals(g.get("status"))));
        assertFalse(canonical(view(after).get("personCredentialOccurrences")).contains("110101199001010038"));
        for(String id:oldIds)assertFalse(core.get(id).isEmpty(),"Current replacement retains immutable history");
        java.util.Map<java.lang.String, java.lang.Object> other=ledger.documentView(after,"different-parser");assertTrue(maps(other.get("personCredentialOccurrences")).isEmpty());assertEquals("PARTIAL",map(other.get("personCredentialCoverage")).get("status"));
        assertEquals(true,map(other.get("personCredentialCoverage")).get("requiresReview"));
    }

    @Test void unrecognizedCredentialCandidateIsVisibleAsACoverageGap(){
        Page unreadable=page(7,"中华人民共和国机动车驾驶证\n姓名：\n证号：");Document document=doc(unreadable);persist(document,unreadable);
        java.util.Map<java.lang.String, java.lang.Object> coverage=map(view(document).get("personCredentialCoverage"));assertEquals("PARTIAL",coverage.get("status"));
        assertFalse(maps(coverage.get("issues")).isEmpty());assertEquals(0,maps(view(document).get("personCredentialOccurrences")).size());
        assertEquals(false,coverage.get("requiresReview"));assertEquals("INFORMATION_ONLY",coverage.get("attentionStatus"));
    }

    @Test void maskedPersonnelNumbersRemainDurableInformationWithoutInflatingConflictCounts(){
        Page first=credentialPage(1,"110101********0011"),last=credentialPage(50,"110101********0011");Document document=doc(first,last);
        persist(document,first);persist(document,last);java.util.Map<java.lang.String, java.lang.Object> result=view(document);
        assertTrue(maps(result.get("personCredentialGroups")).isEmpty());assertFalse(maps(result.get("personCredentialInformationalGroups")).isEmpty());
        assertEquals(0,map(result.get("summary")).get("personCredentialGroups"));
        assertEquals(false,map(result.get("personCredentialCoverage")).get("requiresReview"));
        assertTrue(ledger.documentFacts(document,"parser-v1").stream().anyMatch(f->"PERSON_CREDENTIAL".equals(f.entityType())&&"110101********0011".equals(f.rawValue())));
        assertTrue(maps(result.get("conflicts")).isEmpty());
    }

    private Page credentialPage(int number,String license){return page(number,"中华人民共和国机动车驾驶证\n姓名：张三\n身份证号码：110101199001010011\n驾驶证号："+license+"\n性别：男\n出生日期：1990-01-01");}
}
