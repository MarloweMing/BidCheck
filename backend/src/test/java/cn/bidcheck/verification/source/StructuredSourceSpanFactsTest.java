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
    private Document document(String source){return new Document("doc","project","BID","file.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,source,"PAGE","PDF_OCR","")),cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> field(Document doc,StructuredSourceSpans.PageContext context,int index,String value,String subject)throws Exception{
        ObjectNode input=json.createObjectNode().put("page",1).put("hasMore",false);input.putArray("f").addArray().add("amount").add(value).add("CLEAR").add(context.spans().get(index).id()).add(subject).add("PERSON").add("");
        cn.bidcheck.verification.source.StructuredSourceSpans.DecodedPage decoded=StructuredSourceSpans.page(input,context);Map<String,Object> item=json.convertValue(decoded.canonical().path("observations").get(0),Map.class);
        item.put("sourceSpan",decoded.fieldBindings().get(0));item.put("sourcePage",1);item.put("modelLimitations",cn.bidcheck.compat.Java8Api.listOf());
        item.put("source",cn.bidcheck.compat.Java8Api.mapOf("projectId", doc.projectId(), "documentId", doc.id(), "documentHash", doc.sha256(), "documentVersion", 1, "page", 1));return item;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private Map<String,Object> batch(Document doc,List<Map<String,Object>> fields){return StructuredContextFacts.batch(doc,cn.bidcheck.compat.Java8Api.listOf(1),cn.bidcheck.compat.Java8Api.mapOf("observations", fields),"model");}
    @Test void duplicateIdenticalLinesKeepDifferentActualOffsetsAndSourceIds()throws Exception{
        String source="甲 100元\n甲 100元\n";cn.bidcheck.domain.Domain.Document doc=document(source);cn.bidcheck.verification.source.StructuredSourceSpans.PageContext context=StructuredSourceSpans.build(1,source);
        java.util.Map<java.lang.String, java.lang.Object> result=batch(doc,cn.bidcheck.compat.Java8Api.listOf(field(doc,context,0,"100元","甲"), field(doc,context,1,"100元","甲")));
        assertEquals(2,list(result.get("facts")).size());List<Map<String,Object>> refs=list(result.get("sources"));assertEquals(2,refs.size());assertNotEquals(refs.get(0).get("id"),refs.get(1).get("id"));
        assertEquals("chars:0-7",map(refs.get(0).get("evidence")).get("anchor"));assertEquals("chars:7-14",map(refs.get(1).get("evidence")).get("anchor"));
    }
    @Test void legacyObservationWithoutSpanMetadataRetainsItsOriginalBehavior()throws Exception{
        String source="甲 100元\n甲 100元\n";cn.bidcheck.domain.Domain.Document doc=document(source);java.util.Map<java.lang.String, java.lang.Object> item=field(doc,StructuredSourceSpans.build(1,source),1,"100元","甲");item.remove("sourceSpan");
        java.util.Map<java.lang.String, java.lang.Object> result=batch(doc,cn.bidcheck.compat.Java8Api.listOf(item));assertEquals(1,list(result.get("facts")).size());assertEquals("chars:0-7",map(list(result.get("sources")).get(0).get("evidence")).get("anchor"));
    }
    @Test void aPersistedBindingCannotSurviveChangedPageTextOrTamperedOffsets()throws Exception{
        String source="甲 100元\n";cn.bidcheck.domain.Domain.Document doc=document(source);java.util.Map<java.lang.String, java.lang.Object> item=field(doc,StructuredSourceSpans.build(1,source),0,"100元","甲");
        java.util.Map<java.lang.String, java.lang.Object> altered=batch(document(source+"另一行"),cn.bidcheck.compat.Java8Api.listOf(item));assertTrue(list(altered.get("facts")).isEmpty());assertEquals("CONTEXT_SOURCE_SPAN_INVALID",list(altered.get("rejected")).get(0).get("code"));
        Map<String,Object> changed=new HashMap<>(map(item.get("sourceSpan")));changed.put("start",1);item.put("sourceSpan",changed);
        assertEquals("CONTEXT_SOURCE_SPAN_INVALID",list(batch(doc,cn.bidcheck.compat.Java8Api.listOf(item)).get("rejected")).get(0).get("code"));
    }
    @Test void spanSelectionDoesNotRelaxQuoteValueOrSameRowOwnerValidation()throws Exception{
        String source="<table><tr><td>甲</td><td>100元</td></tr><tr><td>乙</td><td>200元</td></tr></table>";cn.bidcheck.domain.Domain.Document doc=document(source);cn.bidcheck.verification.source.StructuredSourceSpans.PageContext context=StructuredSourceSpans.build(1,source);
        int second=java.util.stream.IntStream.range(0,context.spans().size()).filter(i->context.spans().get(i).text().contains("200元")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        java.util.Map<java.lang.String, java.lang.Object> wrongOwner=field(doc,context,second,"200元","甲");java.util.Map<java.lang.String, java.lang.Object> result=batch(doc,cn.bidcheck.compat.Java8Api.listOf(wrongOwner));java.util.Map<java.lang.String, java.lang.Object> fact=list(result.get("facts")).get(0);
        assertEquals("",fact.get("subject"));assertEquals("UNRESOLVED",map(fact.get("attributes")).get("subjectBinding"));
        java.util.Map<java.lang.String, java.lang.Object> wrongValue=field(doc,context,second,"100元","乙");java.util.Map<java.lang.String, java.lang.Object> rejected=batch(doc,cn.bidcheck.compat.Java8Api.listOf(wrongValue));assertTrue(list(rejected.get("facts")).isEmpty());assertEquals("CONTEXT_QUOTE_NOT_ON_SOURCE_PAGE",list(rejected.get("rejected")).get(0).get("code"));
    }
    @Test void longHtmlRowStillCannotPromoteOwnershipEvenWhenSpanFitsQuoteLimit()throws Exception{
        String source="<tr><td>甲</td><td>"+cn.bidcheck.compat.Java8Api.repeat("x", 2050)+"100元</td></tr>";cn.bidcheck.domain.Domain.Document doc=document(source);cn.bidcheck.verification.source.StructuredSourceSpans.PageContext context=StructuredSourceSpans.build(1,source);
        java.util.Map<java.lang.String, java.lang.Object> result=batch(doc,cn.bidcheck.compat.Java8Api.listOf(field(doc,context,0,"100元","甲")));java.util.Map<java.lang.String, java.lang.Object> fact=list(result.get("facts")).get(0);assertEquals("",fact.get("subject"));
    }
    @Test void coverageLimitationDoesNotRejectOtherwiseValidSourceFact()throws Exception{
        String source="甲 100元\n";cn.bidcheck.domain.Domain.Document doc=document(source);java.util.Map<java.lang.String, java.lang.Object> item=field(doc,StructuredSourceSpans.build(1,source),0,"100元","甲");item.put("coverageLimitations",cn.bidcheck.compat.Java8Api.listOf("STRUCTURED_FIELDS_REMAINING"));
        java.util.Map<java.lang.String, java.lang.Object> result=batch(doc,cn.bidcheck.compat.Java8Api.listOf(item));assertEquals(1,list(result.get("facts")).size());assertTrue(list(result.get("rejected")).isEmpty());
    }
}
