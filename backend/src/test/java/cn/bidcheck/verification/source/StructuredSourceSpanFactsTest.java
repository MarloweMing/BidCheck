package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredSourceSpanFactsTest {
    private final ObjectMapper json=new ObjectMapper();
    private Document document(String source){return new Document("doc","project","BID","file.pdf",1,"hash","",List.of(new Page(1,source,"PAGE","PDF_OCR","")),List.of());}
    private Map<String,Object> field(Document doc,StructuredSourceSpans.PageContext context,int index,String value,String subject)throws Exception{
        ObjectNode input=json.createObjectNode().put("page",1).put("hasMore",false);input.putArray("f").addArray().add("amount").add(value).add("CLEAR").add(context.spans().get(index).id()).add(subject).add("PERSON").add("");
        var decoded=StructuredSourceSpans.page(input,context);Map<String,Object> item=json.convertValue(decoded.canonical().path("observations").get(0),Map.class);
        item.put("sourceSpan",decoded.fieldBindings().get(0));item.put("sourcePage",1);item.put("modelLimitations",List.of());
        item.put("source",Map.of("projectId",doc.projectId(),"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",1,"page",1));return item;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private Map<String,Object> batch(Document doc,List<Map<String,Object>> fields){return StructuredContextFacts.batch(doc,List.of(1),Map.of("observations",fields),"model");}
    @Test void duplicateIdenticalLinesKeepDifferentActualOffsetsAndSourceIds()throws Exception{
        String source="甲 100元\n甲 100元\n";var doc=document(source);var context=StructuredSourceSpans.build(1,source);
        var result=batch(doc,List.of(field(doc,context,0,"100元","甲"),field(doc,context,1,"100元","甲")));
        assertEquals(2,list(result.get("facts")).size());List<Map<String,Object>> refs=list(result.get("sources"));assertEquals(2,refs.size());assertNotEquals(refs.get(0).get("id"),refs.get(1).get("id"));
        assertEquals("chars:0-7",map(refs.get(0).get("evidence")).get("anchor"));assertEquals("chars:7-14",map(refs.get(1).get("evidence")).get("anchor"));
    }
    @Test void legacyObservationWithoutSpanMetadataRetainsItsOriginalBehavior()throws Exception{
        String source="甲 100元\n甲 100元\n";var doc=document(source);var item=field(doc,StructuredSourceSpans.build(1,source),1,"100元","甲");item.remove("sourceSpan");
        var result=batch(doc,List.of(item));assertEquals(1,list(result.get("facts")).size());assertEquals("chars:0-7",map(list(result.get("sources")).get(0).get("evidence")).get("anchor"));
    }
    @Test void aPersistedBindingCannotSurviveChangedPageTextOrTamperedOffsets()throws Exception{
        String source="甲 100元\n";var doc=document(source);var item=field(doc,StructuredSourceSpans.build(1,source),0,"100元","甲");
        var altered=batch(document(source+"另一行"),List.of(item));assertTrue(list(altered.get("facts")).isEmpty());assertEquals("CONTEXT_SOURCE_SPAN_INVALID",list(altered.get("rejected")).get(0).get("code"));
        Map<String,Object> changed=new HashMap<>(map(item.get("sourceSpan")));changed.put("start",1);item.put("sourceSpan",changed);
        assertEquals("CONTEXT_SOURCE_SPAN_INVALID",list(batch(doc,List.of(item)).get("rejected")).get(0).get("code"));
    }
    @Test void spanSelectionDoesNotRelaxQuoteValueOrSameRowOwnerValidation()throws Exception{
        String source="<table><tr><td>甲</td><td>100元</td></tr><tr><td>乙</td><td>200元</td></tr></table>";var doc=document(source);var context=StructuredSourceSpans.build(1,source);
        int second=java.util.stream.IntStream.range(0,context.spans().size()).filter(i->context.spans().get(i).text().contains("200元")).findFirst().orElseThrow();
        var wrongOwner=field(doc,context,second,"200元","甲");var result=batch(doc,List.of(wrongOwner));var fact=list(result.get("facts")).get(0);
        assertEquals("",fact.get("subject"));assertEquals("UNRESOLVED",map(fact.get("attributes")).get("subjectBinding"));
        var wrongValue=field(doc,context,second,"100元","乙");var rejected=batch(doc,List.of(wrongValue));assertTrue(list(rejected.get("facts")).isEmpty());assertEquals("CONTEXT_QUOTE_NOT_ON_SOURCE_PAGE",list(rejected.get("rejected")).get(0).get("code"));
    }
    @Test void longHtmlRowStillCannotPromoteOwnershipEvenWhenSpanFitsQuoteLimit()throws Exception{
        String source="<tr><td>甲</td><td>"+"x".repeat(2050)+"100元</td></tr>";var doc=document(source);var context=StructuredSourceSpans.build(1,source);
        var result=batch(doc,List.of(field(doc,context,0,"100元","甲")));var fact=list(result.get("facts")).get(0);assertEquals("",fact.get("subject"));
    }
    @Test void coverageLimitationDoesNotRejectOtherwiseValidSourceFact()throws Exception{
        String source="甲 100元\n";var doc=document(source);var item=field(doc,StructuredSourceSpans.build(1,source),0,"100元","甲");item.put("coverageLimitations",List.of("STRUCTURED_FIELDS_REMAINING"));
        var result=batch(doc,List.of(item));assertEquals(1,list(result.get("facts")).size());assertTrue(list(result.get("rejected")).isEmpty());
    }
}
