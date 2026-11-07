package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.FactRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.facts.LedgerValues.*;
import static org.junit.jupiter.api.Assertions.*;

class PageCertificateWitnessTest {
    private static final String CATEGORY="职业健康安全管理体系认证",HOLDER="合成施工有限公司",NUMBER="SYN-S001";
    private static final String ORIGINAL="# "+CATEGORY+"证书\n证书编号："+NUMBER+"\n兹证明\n"+HOLDER+"\n证书有效日期：2025年09月04日至2028年09月03日";

    @Test void singleOriginalPageCreatesACompactVersionBoundWitnessWithoutChangingSourceIdentity(){
        Document document=document(ORIGINAL,1,'a');Map<String,Object> input=input(document,ORIGINAL);
        Map<String,Object> source=source(document,input),witness=map(source.get("pageCertificateWitness"));
        assertEquals(PageCertificateWitness.VERSION,witness.get("version"));assertEquals("CURRENT_SERVER_PROOF",witness.get("authority"));
        assertEquals(false,witness.get("businessProof"));assertEquals(CATEGORY,witness.get("managementCategory"));
        assertEquals(NUMBER,witness.get("number"));assertEquals(HOLDER,witness.get("holder"));
        assertEquals(document.id(),witness.get("documentId"));assertEquals(document.sha256(),witness.get("documentHash"));
        assertEquals(1,witness.get("documentVersion"));assertEquals(453,witness.get("page"));assertEquals("PAGE",witness.get("locationType"));
        assertEquals(input.get("parserFingerprint"),witness.get("parserFingerprint"));assertEquals(hash(ORIGINAL),witness.get("pageTextHash"));
        assertFalse(witness.containsKey("quote"));assertFalse(witness.containsKey("pageText"));
        String expected=hash(canonical(cn.bidcheck.compat.Java8Api.listOf(document.id(),document.sha256(),document.version(),input.get("parserFingerprint"),source.get("evidence"))));
        assertEquals(expected,source.get("sourceFingerprint"));
    }

    @Test void aSnippetCannotHideAnotherCertificateOnTheSameFrozenPage(){
        for(String extra:Arrays.asList("\n# 质量管理体系认证证书", "\n证书编号：SYN-S002", "\n获证组织名称：另一合成公司")){
            Document document=document(ORIGINAL+extra,1,'a');
            assertFalse(source(document,input(document,ORIGINAL)).containsKey("pageCertificateWitness"),extra);
        }
    }

    @Test void aPreviouslyStoredOccurrenceWithoutTheWitnessKeepsItsIdentityAndIsReused(){
        Document document=document(ORIGINAL,1,'a');Map<String,Object> input=input(document,ORIGINAL);
        Map<String,Object> legacySource=source(document,input);legacySource.remove("pageCertificateWitness");
        Map<String,Object> fact=of("id","fact","kind","CERTIFICATE","subject",HOLDER,"field","证书",
            "rawValue",NUMBER,"value",NUMBER,"unit","","basis","","scope","CURRENT_PROJECT",
            "sourceIds",Collections.singletonList("source"),"attributes",of("category",CATEGORY,"number",NUMBER,"numberField","证书编号"));
        Map<String,Object> semantic=new LinkedHashMap<>(fact);semantic.remove("id");
        semantic.put("sourceIds",Collections.singletonList(legacySource.get("sourceFingerprint")));
        String originalId=dbKey("occurrence",canonical(cn.bidcheck.compat.Java8Api.listOf("project","engine",SemanticFactIdentity.value(semantic))));
        FactRepository repository=FactRepository.memoryForTests();
        repository.insert(of("id",originalId,"occurrenceId",originalId,"projectId","project","engineIdentity","engine",
            "fact",fact,"sources",Collections.singletonList(legacySource),"entity",EntityIdentity.resolve(fact,Collections.singletonList(legacySource)),
            "layoutIdentity","","createdAt","2026-01-01T00:00:00Z","ledgerVersion","fact-ledger-4-field-observations"));
        FactLedgerService ledger=new FactLedgerService(new FactLedgerServiceTest.MemoryStore(),repository);
        Map<String,Object> result=ledger.publish("project","run","cp","engine",Collections.singletonList(document),
            of("operationIdentity","witness-compatibility","engineVersion","test","facts",Collections.singletonList(fact),"sources",Collections.singletonList(input)));
        assertEquals(Collections.singletonList(originalId),result.get("occurrenceIds"));assertEquals(1,result.get("reused"));
        assertFalse(maps(repository.get(originalId).get("sources")).get(0).containsKey("pageCertificateWitness"));
    }

    @Test void frozenPageCacheCannotBePoisonedThroughReturnedWitnessMetadata(){
        Document document=document(ORIGINAL,1,'a');
        FactLedgerService.Scope scope=new FactLedgerService.Scope("project","engine",Collections.singletonList(document));
        Map<String,Object> first=FactLedgerService.source(scope,input(document,ORIGINAL));
        ((Map<String,Object>)first.get("pageCertificateWitness")).put("holder","虚构公司");
        Map<String,Object> again=FactLedgerService.source(scope,input(document,ORIGINAL));
        assertEquals(HOLDER,map(again.get("pageCertificateWitness")).get("holder"));
        assertEquals(1,scope.pageCertificateWitnesses.size());
        Document ambiguous=document(ORIGINAL+"\n# 质量管理体系认证证书",1,'a');
        scope=new FactLedgerService.Scope("project","engine",Collections.singletonList(ambiguous));
        assertFalse(FactLedgerService.source(scope,input(ambiguous,ORIGINAL)).containsKey("pageCertificateWitness"));
        assertTrue(scope.pageCertificateWitnesses.containsKey(FactLedgerService.Scope.pageKey("doc","PAGE",453)));
        assertTrue(scope.pageCertificateWitnesses.get(FactLedgerService.Scope.pageKey("doc","PAGE",453)).isEmpty());
    }

    @Test void witnessesAreRebuiltFromCurrentNumberHolderVersionAndHash(){
        Map<String,Object> baseline=source(document(ORIGINAL,1,'a'),input(document(ORIGINAL,1,'a'),ORIGINAL));
        Map<String,Object> oldWitness=map(baseline.get("pageCertificateWitness"));
        for(Document changed:Arrays.asList(document(ORIGINAL.replace(NUMBER,"SYN-S002"),1,'a'),
                document(ORIGINAL.replace(HOLDER,"另一合成公司"),1,'a'),document(ORIGINAL,2,'a'),document(ORIGINAL,1,'b'))){
            Map<String,Object> input=input(changed,changed.pages().get(0).text());input.put("pageCertificateWitness",oldWitness);
            Map<String,Object> witness=map(source(changed,input).get("pageCertificateWitness"));
            assertNotEquals(oldWitness,witness);
            assertEquals(changed.sha256(),witness.get("documentHash"));assertEquals(changed.version(),witness.get("documentVersion"));
            assertEquals(hash(changed.pages().get(0).text()),witness.get("pageTextHash"));
        }
        for(String field:Arrays.asList("documentHash","documentVersion")){
            Document current=document(ORIGINAL,2,'b');Map<String,Object> stale=input(current,ORIGINAL);
            stale.put(field,field.equals("documentHash")?document(ORIGINAL,1,'a').sha256():1);
            stale.put("pageCertificateWitness",oldWitness);
            assertThrows(IllegalArgumentException.class,()->source(current,stale));
        }
    }

    @Test void suppliedWitnessCannotCreateAClassificationOrReplaceTheFrozenPageResult(){
        Map<String,Object> forged=of("version",PageCertificateWitness.VERSION,"authority","CURRENT_SERVER_PROOF",
            "businessProof",false,"managementCategory","质量管理体系认证","number","FORGED","holder","虚构公司");
        Document ambiguous=document(ORIGINAL+"\n# 质量管理体系认证证书",1,'a');Map<String,Object> input=input(ambiguous,ORIGINAL);
        input.put("pageCertificateWitness",forged);assertFalse(source(ambiguous,input).containsKey("pageCertificateWitness"));
        Document current=document(ORIGINAL,1,'a');input=input(current,ORIGINAL);input.put("pageCertificateWitness",forged);
        assertEquals(CATEGORY,map(source(current,input).get("pageCertificateWitness")).get("managementCategory"));
    }

    @Test void inlineAndLabelledHoldersAreAcceptedButAnotherUnboundCompanyIsNotTheHolder(){
        for(String label:Arrays.asList("兹证明：","获证组织名称：","获证企业名称：")){
            String original=ORIGINAL.replace("兹证明\n"+HOLDER,label+HOLDER)+"\n办理机构：另一合成公司";
            Document document=document(original,1,'a');
            assertEquals(HOLDER,map(source(document,input(document,original)).get("pageCertificateWitness")).get("holder"));
        }
        String original=ORIGINAL.replace("兹证明\n", "办理机构：");Document document=document(original,1,'a');
        assertFalse(source(document,input(document,original)).containsKey("pageCertificateWitness"));
    }

    @Test void blankMarkdownLinesCanSeparateTheHolderButCannotBorrowTheNextFieldOrDate(){
        String original=ORIGINAL.replace("兹证明\n"+HOLDER,"兹证明\n\n\n\n# "+HOLDER);
        Document document=document(original,1,'a');
        assertEquals(HOLDER,map(source(document,input(document,original)).get("pageCertificateWitness")).get("holder"));
        for(String next:Arrays.asList("证书有效日期：2025年09月04日至2028年09月03日","2025年09月04日至2028年09月03日",
                "办理机构：另一合成公司","证书编号：SYN-S002")){
            original=ORIGINAL.substring(0,ORIGINAL.indexOf("兹证明"))+"兹证明\n\n\n\n# "+next;
            document=document(original,1,'a');
            assertFalse(source(document,input(document,original)).containsKey("pageCertificateWitness"),next);
        }
    }

    @Test void organizationCreditCodeFieldIsNotASecondHolderButAnotherExplicitHolderStillIs(){
        String original=ORIGINAL.replace("兹证明\n"+HOLDER,"兹证明\n\n\n\n# "+HOLDER)
            +"\n获证组织统一社会信用代码：SYNTHETIC-COMPANY-CODE";
        Document document=document(original,1,'a');
        assertEquals(HOLDER,map(source(document,input(document,ORIGINAL.substring(0,ORIGINAL.indexOf("兹证明"))))
            .get("pageCertificateWitness")).get("holder"));
        for(String label:Arrays.asList("获证组织名称：","获证组织：","获证企业名称：")){
            String single=original.replace("兹证明\n\n\n\n# "+HOLDER,label+HOLDER);
            document=document(single,1,'a');
            assertEquals(HOLDER,map(source(document,input(document,single)).get("pageCertificateWitness")).get("holder"),label);
            String ambiguous=original+"\n"+label+"另一合成公司";
            document=document(ambiguous,1,'a');
            assertFalse(source(document,input(document,ambiguous)).containsKey("pageCertificateWitness"),label);
        }
    }

    private Document document(String original,int version,char digest){
        return new Document("doc","project","BID","合成证书.pdf",version,cn.bidcheck.compat.Java8Api.repeat(String.valueOf(digest),64),"",
            Collections.singletonList(new Page(453,original,"PAGE","PDFBOX_NATIVE","")),Collections.emptyList());
    }
    private Map<String,Object> input(Document document,String quote){
        return of("id","source","projectId","project","documentHash",document.sha256(),"documentVersion",document.version(),
            "parserFingerprint",FactLedgerService.parserFingerprint(document.pages().get(0)),
            "evidence",new Evidence(document.id(),document.name(),453,"PAGE",quote,"synthetic",1));
    }
    private Map<String,Object> source(Document document,Map<String,Object> input){
        return FactLedgerService.source(new FactLedgerService.Scope("project","engine",Collections.singletonList(document)),input);
    }
}
