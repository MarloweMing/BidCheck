package cn.bidcheck.verification.source;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredSourceSpansTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode page(StructuredSourceSpans.PageContext context,String value){
        ObjectNode out=json.createObjectNode().put("page",context.page()).put("hasMore",false);out.putArray("limitations");
        out.putArray("f").addArray().add("amount").add(value).add("CLEAR").add(context.spans().get(0).id()).add("").add("UNKNOWN").add("");return out;
    }
    @Test void sourceRowsAndLinesRetainEveryOriginalCharacterAndStableOffsets(){
        String source="标题\r\n<table><tr class='x'><td>甲&amp;乙</td><td> 100 </td></tr>\r\n<tr><td>丙</td><td>200</td></tr></table>\n尾😀文";
        var context=StructuredSourceSpans.build(7,source);assertFalse(context.truncated());assertEquals(source.length(),context.sourceLength());
        assertTrue(context.spans().stream().anyMatch(s->s.text().equals("<tr class='x'><td>甲&amp;乙</td><td> 100 </td></tr>")));
        assertTrue(context.spans().stream().anyMatch(s->s.text().equals("标题\r\n")));
        for(var span:context.spans()){assertEquals(source.substring(span.start(),span.end()),span.text());assertTrue(span.text().length()<=4000);assertEquals(7,span.page());}
        assertEquals(context,StructuredSourceSpans.build(7,source));assertNotEquals(context.spans().get(0).id(),StructuredSourceSpans.build(8,source).spans().get(0).id());
        assertTrue(context.instruction().stream().allMatch(m->m.keySet().equals(Set.of("id","text"))));
    }
    @Test void adjacentHtmlCellsAreNeverConcatenatedOrHtmlDecoded(){
        String source="<table><tr><td>甲&amp;乙</td><td>100元</td></tr></table>";
        var context=StructuredSourceSpans.build(1,source);assertTrue(context.spans().stream().noneMatch(s->s.text().equals("甲&乙100元")));
        assertTrue(context.spans().stream().allMatch(s->source.contains(s.text())));
    }
    @Test void longRowsAndInputTailAreExplicitlyUncoveredAndNeverCutIntoFalseShortRows(){
        String row="<tr><td>"+"甲".repeat(4000)+"</td></tr>";
        var context=StructuredSourceSpans.build(1,"title\n"+row+"\ntail");
        assertTrue(context.truncated());assertEquals(List.of("STRUCTURED_SOURCE_TRUNCATED"),context.coverageLimitations());
        assertTrue(context.spans().stream().noneMatch(s->s.text().contains("甲")));assertTrue(context.spans().stream().anyMatch(s->s.text().equals("tail")));
        var prefix=StructuredSourceSpans.build(1,"a\n".repeat(7999)+"😀z");
        assertEquals(16000,prefix.submittedEnd());assertTrue(prefix.truncated());
        assertTrue(prefix.spans().stream().allMatch(s->s.end()<=16000&&!s.text().contains("😀")));
    }
    @Test void completeRawLineExactlyAtQuoteLimitIsPreservedButLongerLineIsNot(){
        assertFalse(StructuredSourceSpans.build(1,"x".repeat(4000)).truncated());
        var longLine=StructuredSourceSpans.build(1,"x".repeat(4001));assertTrue(longLine.truncated());assertTrue(longLine.spans().isEmpty());
    }
    @Test void decodeReturnsCanonicalFieldsAndSeparateTrustedBindingsWithoutCoverageInModelLimitations()throws Exception{
        var context=StructuredSourceSpans.build(1,"甲 100元\n");var input=page(context,"100元");input.put("hasMore",true);
        var result=StructuredSourceSpans.page(input,context);var field=result.canonical().path("observations").get(0);
        assertEquals("甲 100元\n",field.path("sourceQuote").asText());assertEquals("FIELD",field.path("type").asText());assertTrue(field.path("bbox").isNull());
        assertFalse(field.has("sourceSpan"));assertEquals(9,field.size());assertTrue(result.canonical().path("limitations").isEmpty());
        assertEquals(List.of("STRUCTURED_FIELDS_REMAINING"),result.coverageLimitations());assertEquals(1,result.fieldBindings().size());
        assertEquals(context.spans().get(0),StructuredSourceSpans.verifiedBinding(1,"甲 100元\n",field.path("sourceQuote").asText(),result.fieldBindings().get(0)));
    }
    @Test void fortyIsVisibleTargetAndOverTargetIsRetainedUntilLedgerHardLimit()throws Exception{
        var context=StructuredSourceSpans.build(1,"100元");var input=page(context,"100元");var fields=(ArrayNode)input.path("f");JsonNode field=fields.get(0).deepCopy();
        for(int i=1;i<40;i++)fields.add(field.deepCopy());var decoded=StructuredSourceSpans.page(input,context);
        assertEquals(40,decoded.fieldBindings().size());assertEquals(List.of("STRUCTURED_FIELD_LIMIT_REACHED"),decoded.coverageLimitations());
        fields.add(field.deepCopy());decoded=StructuredSourceSpans.page(input,context);
        assertEquals(41,decoded.fieldBindings().size());assertEquals(41,decoded.submittedFieldCount());assertEquals(List.of("STRUCTURED_OUTPUT_TARGET_EXCEEDED"),decoded.coverageLimitations());
        while(fields.size()<120)fields.add(field.deepCopy());decoded=StructuredSourceSpans.page(input,context);assertEquals(120,decoded.fieldBindings().size());
        fields.add(field.deepCopy());assertThrows(IOException.class,()->StructuredSourceSpans.page(input,context));
    }
    @Test void emptyOutputOnTruncatedInputStillReportsCoverageGap()throws Exception{
        var context=StructuredSourceSpans.build(1,"x".repeat(4001));ObjectNode input=json.createObjectNode().put("page",1).put("hasMore",false);input.putArray("f");
        assertEquals(List.of("STRUCTURED_SOURCE_TRUNCATED"),StructuredSourceSpans.page(input,context).coverageLimitations());
    }
    @Test void maliciousUnknownAndCrossPageIdsCannotResolve()throws Exception{
        var context=StructuredSourceSpans.build(1,"100元");
        for(String id:List.of("0","../page/1",StructuredSourceSpans.build(2,"100元").spans().get(0).id())){
            ObjectNode input=page(context,"100元");((ArrayNode)input.path("f").get(0)).set(3,json.getNodeFactory().textNode(id));
            var decoded=StructuredSourceSpans.page(input,context);assertEquals(List.of(),decoded.fieldBindings());assertTrue(decoded.canonical().path("observations").isEmpty());
            assertEquals("VISUAL_SOURCE_SPAN_INVALID",decoded.rejectedFields().get(0).get("code"));assertEquals(0,decoded.rejectedFields().get(0).get("inputIndex"));
            assertEquals(List.of("STRUCTURED_FIELDS_REJECTED"),decoded.coverageLimitations());
        }
    }
    @Test void decoderDoesNotCoerceNumbersMissingHasMoreEnumsOrExtraMetadata()throws Exception{
        var context=StructuredSourceSpans.build(1,"100元");
        for(String mode:List.of("number","missing","stringBool","enum","offset","shortTuple","page")){
            ObjectNode input=page(context,"100元");ArrayNode field=(ArrayNode)input.path("f").get(0);
            switch(mode){case "number"->field.set(3,json.getNodeFactory().numberNode(0));case "missing"->input.remove("hasMore");case "stringBool"->input.put("hasMore","false");
                case "enum"->field.set(2,json.getNodeFactory().textNode("YES"));case "offset"->input.put("start",0);case "shortTuple"->field.remove(6);case "page"->input.put("page",2);}
            if(Set.of("number","enum","shortTuple").contains(mode)){
                var decoded=StructuredSourceSpans.page(input,context);assertEquals(1,decoded.rejectedFields().size(),mode);assertTrue(decoded.fieldBindings().isEmpty(),mode);
            }else assertThrows(IOException.class,()->StructuredSourceSpans.page(input,context),mode);
        }
    }
    @Test void envelopeLeavesMissingDuplicateAndExtraPageIsolationToExistingAdapter()throws Exception{
        ObjectNode envelope=json.createObjectNode().put("wire",StructuredSourceSpans.VERSION);ArrayNode pages=envelope.putArray("pages");pages.addObject().put("page",1);pages.addObject().put("page",1);pages.addObject().put("page",999);
        JsonNode canonical=StructuredSourceSpans.envelope(envelope,List.of(1,3));assertFalse(canonical.has("wire"));assertEquals(3,canonical.path("pages").size());
        envelope.put("offset",0);assertThrows(IOException.class,()->StructuredSourceSpans.envelope(envelope,List.of(1,3)));
    }
    @Test void persistedBindingMustMatchExactOriginalHashOffsetAndQuote()throws Exception{
        var context=StructuredSourceSpans.build(1,"100元\n100元\n");var decoded=StructuredSourceSpans.page(page(context,"100元"),context);
        Map<String,Object> binding=decoded.fieldBindings().get(0);assertNull(StructuredSourceSpans.verifiedBinding(1,"100元\n100元\nextra","100元\n",binding));
        for(String key:List.of("start","end","page","sourceTextHash","quoteHash","id","version")){
            Map<String,Object> changed=new HashMap<>(binding);changed.put(key,key.equals("start")?6:key.equals("end")?11:key.equals("page")?2:"wrong");
            assertNull(StructuredSourceSpans.verifiedBinding(1,"100元\n100元\n","100元\n",changed),key);
        }
    }
    @Test void thousandsOfShortLinesAreBoundedByExactJsonBytesAndKeepOnlyWholePrefixSpans()throws Exception{
        String source="甲\n".repeat(8000);var context=StructuredSourceSpans.build(1,source);
        assertTrue(context.truncated());assertEquals(List.of("STRUCTURED_SOURCE_TRUNCATED"),context.coverageLimitations());
        ArrayNode array=json.valueToTree(context.instruction());assertTrue(array.toString().getBytes(StandardCharsets.UTF_8).length<=64000);
        assertTrue(context.spans().size()<8000);int cursor=0;
        for(var span:context.spans()){assertEquals(cursor,span.start());assertEquals("甲\n",span.text());cursor=span.end();}
        String text=source.substring(cursor,cursor+2);String id="P1S"+cursor+"E"+(cursor+2)+"H"+SourceSafety.digest(text.getBytes(StandardCharsets.UTF_8)).substring(0,16);
        array.addObject().put("id",id).put("text",text);assertTrue(array.toString().getBytes(StandardCharsets.UTF_8).length>64000,"the first omitted complete span must exceed the exact budget");
        ObjectNode input=page(context,"甲");assertEquals(List.of("STRUCTURED_SOURCE_TRUNCATED"),StructuredSourceSpans.page(input,context).coverageLimitations());
    }
    @Test void jsonEscapingAndUtf8BothCountTowardTheSameBudget(){
        String line="\"\\\u0001\t汉😀\n";String source=line.repeat(1500);var context=StructuredSourceSpans.build(3,source);
        assertTrue(source.length()<16000);assertTrue(context.truncated());ArrayNode array=json.valueToTree(context.instruction());
        int actual=array.toString().getBytes(StandardCharsets.UTF_8).length;assertTrue(actual<=64000);
        for(var span:context.spans()){assertEquals(line,span.text());assertEquals(source.substring(span.start(),span.end()),span.text());}
        int next=context.spans().get(context.spans().size()-1).end(),end=next+line.length();
        array.addObject().put("id","P3S"+next+"E"+end+"H"+SourceSafety.digest(line.getBytes(StandardCharsets.UTF_8)).substring(0,16)).put("text",line);
        assertTrue(array.toString().getBytes(StandardCharsets.UTF_8).length>64000);
    }
    @Test void unicodeSurrogatePairsAreNeverCutBySerializedBudget(){
        String line="😀😃😄\r\n";String source=line.repeat(1800);var context=StructuredSourceSpans.build(4,source);
        assertTrue(context.truncated());assertTrue(json.valueToTree(context.instruction()).toString().getBytes(StandardCharsets.UTF_8).length<=64000);
        for(var span:context.spans()){assertEquals(line,span.text());assertEquals(0,span.start()%line.length());assertEquals(0,span.end()%line.length());}
    }
    @Test void malformedFieldsDoNotConsumeOrMisalignBindingsForValidNeighbours()throws Exception{
        String source="甲 100元\n乙 200元\n丙 300元\n";var context=StructuredSourceSpans.build(1,source);ObjectNode input=page(context,"100元");ArrayNode fields=(ArrayNode)input.path("f");
        fields.addArray().add("bad");
        fields.addArray().add("amount").add("200元").add("CLEAR").add(context.spans().get(1).id()).add("乙").add("PERSON").add("");
        fields.addArray().add("amount").add("200元").add("CLEAR").add(context.spans().get(1).id()).add("乙").add("INVALID_ENUM").add("");
        fields.addArray().add("amount").add("300元").add("CLEAR").add(context.spans().get(2).id()).add("丙").add("PERSON").add("");
        var decoded=StructuredSourceSpans.page(input,context);assertEquals(5,decoded.submittedFieldCount());assertEquals(3,decoded.fieldBindings().size());
        assertEquals(List.of(1,3),decoded.rejectedFields().stream().map(r->r.get("inputIndex")).toList());
        for(int n=0;n<3;n++){
            var span=context.spans().get(n);var observation=decoded.canonical().path("observations").get(n);
            assertEquals(span.text(),observation.path("sourceQuote").asText());assertEquals(span,StructuredSourceSpans.verifiedBinding(1,source,span.text(),decoded.fieldBindings().get(n)));
        }
        assertEquals(List.of("STRUCTURED_FIELDS_REJECTED"),decoded.coverageLimitations());
        assertTrue(decoded.rejectedFields().stream().allMatch(r->r.keySet().equals(Set.of("page","inputIndex","code"))));
    }
    @Test void everyOriginalParseConstraintIsIsolatedAndAllBadCannotMasqueradeAsNoFields()throws Exception{
        var context=StructuredSourceSpans.build(1,"100元");ObjectNode input=page(context,"100元");ArrayNode fields=(ArrayNode)input.path("f");fields.removeAll();
        fields.addArray().add("amount").add("100元").add("UNREADABLE").add(context.spans().get(0).id()).add("").add("UNKNOWN").add("");
        fields.addArray().add("amount").add("100元").add("NOT_OBSERVED").add(context.spans().get(0).id()).add("").add("UNKNOWN").add("");
        fields.addArray().add("x".repeat(101)).add("100元").add("CLEAR").add(context.spans().get(0).id()).add("").add("UNKNOWN").add("");
        fields.addNull();fields.addObject().put("sourceSpan",0);
        var decoded=StructuredSourceSpans.page(input,context);assertEquals(5,decoded.submittedFieldCount());assertEquals(5,decoded.rejectedFields().size());
        assertTrue(decoded.fieldBindings().isEmpty());assertTrue(decoded.canonical().path("observations").isEmpty());assertEquals(List.of("STRUCTURED_FIELDS_REJECTED"),decoded.coverageLimitations());
        assertEquals(List.of(0,1,2,3,4),decoded.rejectedFields().stream().map(r->r.get("inputIndex")).toList());
    }
    @Test void evidenceMismatchIsStillPassedToOriginalSourceValidatorWithoutInventingQuote()throws Exception{
        var context=StructuredSourceSpans.build(1,"100元");ObjectNode input=page(context,"999元");var decoded=StructuredSourceSpans.page(input,context);
        assertTrue(decoded.rejectedFields().isEmpty());assertEquals("100元",decoded.canonical().path("observations").get(0).path("sourceQuote").asText());
        assertEquals("999元",decoded.canonical().path("observations").get(0).path("value").asText());
    }
    @Test void simulatedFiftyThreeFieldResponseRetainsFiftyOneStrictItemsAndBothReviewCodes()throws Exception{
        var context=StructuredSourceSpans.build(1,"100元");ObjectNode input=page(context,"100元");ArrayNode fields=(ArrayNode)input.path("f");JsonNode valid=fields.get(0).deepCopy();
        while(fields.size()<53)fields.add(valid.deepCopy());((ArrayNode)fields.get(7)).set(5,TextNode.valueOf("INVALID_ENUM"));((ArrayNode)fields.get(52)).set(2,TextNode.valueOf("INVALID_ENUM"));
        var decoded=StructuredSourceSpans.page(input,context);assertEquals(53,decoded.submittedFieldCount());assertEquals(51,decoded.fieldBindings().size());
        assertEquals(List.of(7,52),decoded.rejectedFields().stream().map(r->r.get("inputIndex")).toList());
        assertEquals(Set.of("STRUCTURED_OUTPUT_TARGET_EXCEEDED","STRUCTURED_FIELDS_REJECTED"),new HashSet<>(decoded.coverageLimitations()));
    }
}
