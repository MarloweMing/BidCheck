package cn.bidcheck.verification.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.util.*;

/** Wire compression only. Every tuple becomes one legacy observation before source validation. */
final class StructuredCompactResponse {
    static final String VERSION="SOURCE_FACTS_COMPACT_V1";
    private static final JsonNodeFactory JSON=JsonNodeFactory.instance;
    private StructuredCompactResponse(){}

    static JsonNode envelope(JsonNode input,List<Integer> requested)throws IOException {
        keys(input,cn.bidcheck.compat.Java8Api.setOf("wire", "pages", "limitations"));
        if(!VERSION.equals(string(input.get("wire"),64)))throw invalid();
        JsonNode pages=input.get("pages");
        if(pages==null||!pages.isArray()||pages.size()>requested.size()+3)throw mapping();
        // Preserve the existing bounded per-page isolation: unknown pages are rejected, duplicate or
        // missing requested pages fail independently, and a malformed page never discards its peers.
        ObjectNode out=((ObjectNode)input).deepCopy();out.remove("wire");return out;
    }

    static JsonNode page(JsonNode input)throws IOException {
        keys(input,cn.bidcheck.compat.Java8Api.setOf("page", "q", "r", "f", "limitations"));
        if(!input.path("page").isIntegralNumber()||!input.path("page").canConvertToInt())throw mapping();
        JsonNode quotes=array(input.get("q"),40),records=array(input.get("r"),40),fields=array(input.get("f"),40);
        for(JsonNode quote:quotes)string(quote,4000);
        for(JsonNode record:records){
            tuple(record,3);string(record.get(0),160);String type=string(record.get(1),30);string(record.get(2),160);
            if(!cn.bidcheck.compat.Java8Api.setOf("VEHICLE", "PERSON", "COMPANY", "CONTRACT", "CERTIFICATE", "UNKNOWN").contains(type))throw invalid();
        }
        ObjectNode out=JSON.objectNode().put("page",input.path("page").intValue());
        if(input.has("limitations"))out.set("limitations",input.get("limitations").deepCopy());
        ArrayNode observations=out.putArray("observations");
        for(JsonNode field:fields){
            tuple(field,5);String label=string(field.get(0),100),value=string(field.get(1),2048),readability=string(field.get(2),30);
            if(!cn.bidcheck.compat.Java8Api.setOf("CLEAR", "PARTIAL", "UNREADABLE", "NOT_OBSERVED").contains(readability))throw invalid();
            int q=index(field.get(3),quotes.size()),r=index(field.get(4),records.size());JsonNode record=records.get(r);
            observations.addObject().put("type","FIELD").put("label",label).put("value",value).put("readability",readability)
                .putNull("bbox").put("sourceQuote",quotes.get(q).textValue()).put("subject",record.get(0).textValue())
                .put("entityType",record.get(1).textValue()).put("recordKey",record.get(2).textValue());
        }
        return out;
    }
    private static void keys(JsonNode object,Set<String> allowed)throws IOException {
        if(object==null||!object.isObject())throw invalid();
        for(Iterator<String> it=object.fieldNames();it.hasNext();)if(!allowed.contains(it.next()))throw invalid();
    }
    private static JsonNode array(JsonNode value,int maximum)throws IOException {if(value==null||!value.isArray()||value.size()>maximum)throw invalid();return value;}
    private static void tuple(JsonNode value,int length)throws IOException {if(value==null||!value.isArray()||value.size()!=length)throw invalid();}
    private static String string(JsonNode value,int maximum)throws IOException {if(value==null||!value.isTextual()||value.textValue().length()>maximum)throw invalid();return value.textValue();}
    private static int index(JsonNode value,int size)throws IOException {if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<0||value.intValue()>=size)throw invalid();return value.intValue();}
    private static IOException invalid(){return new IOException("VISUAL_COMPACT_SCHEMA_INVALID");}
    private static IOException mapping(){return new IOException("VISUAL_PAGE_MAPPING_INVALID");}
}
