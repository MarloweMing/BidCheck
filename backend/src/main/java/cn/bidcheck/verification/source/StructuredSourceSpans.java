package cn.bidcheck.verification.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Request-owned exact source spans. The model may select an ID, never supply source offsets. */
public final class StructuredSourceSpans {
    public static final String VERSION="SOURCE_FACTS_SPAN_V1";
    public static final String POLICY="source-spans-exact-rows-lines-1;sourceChars=16000;quoteChars=4000;jsonUtf8Bytes=64000;fieldTarget=40;fieldHardLimit=120;perFieldRejection=1";
    public static final int SPAN_FIELD_TARGET=40,SPAN_FIELD_HARD_LIMIT=120;
    private static final int SOURCE_LIMIT=16000,QUOTE_LIMIT=4000;
    private static final int SERIALIZED_SPAN_LIMIT=64000;
    private static final Pattern ROW=Pattern.compile("(?is)<tr\\b[^>]*>.*?</tr\\s*>");
    private static final JsonNodeFactory JSON=JsonNodeFactory.instance;
    private static final Set<String> BINDING_KEYS=Set.of("version","id","page","start","end","sourceTextHash","quoteHash");
    private StructuredSourceSpans(){}

    public record SourceSpan(String id,int page,int start,int end,String text){}
    public record PageContext(int page,int sourceLength,int submittedEnd,boolean truncated,
                              List<SourceSpan> spans,List<String> coverageLimitations,String sourceTextHash) {
        public PageContext {spans=List.copyOf(spans);coverageLimitations=List.copyOf(coverageLimitations);}
        /** Only IDs and exact source text enter the prompt; trusted offsets stay on the server. */
        public List<Map<String,Object>> instruction(){return spans.stream().map(s->Map.<String,Object>of("id",s.id(),"text",s.text())).toList();}
    }
    public record DecodedPage(JsonNode canonical,List<Map<String,Object>> fieldBindings,List<String> coverageLimitations,
                              List<Map<String,Object>> rejectedFields,int submittedFieldCount) {
        public DecodedPage {fieldBindings=List.copyOf(fieldBindings);coverageLimitations=List.copyOf(coverageLimitations);rejectedFields=List.copyOf(rejectedFields);}
    }

    /** Retain complete raw HTML rows or complete raw lines. Never join cells or trim source text. */
    public static PageContext build(int page,String original){
        if(page<1)throw new IllegalArgumentException("Source page must be positive");
        String source=Objects.toString(original,"");int limit=Math.min(SOURCE_LIMIT,source.length()),cursor=0;
        List<SourceSpan> spans=new ArrayList<>();boolean[] omitted={source.length()>limit};Matcher rows=ROW.matcher(source);
        while(rows.find()&&rows.start()<limit){
            lines(source,cursor,rows.start(),limit,page,spans,omitted);
            add(source,rows.start(),rows.end(),limit,page,spans,omitted);
            cursor=rows.end();if(cursor>=limit)break;
        }
        if(cursor<limit)lines(source,cursor,source.length(),limit,page,spans,omitted);
        // Bound the actual JSON array, including escaped text, UTF-8, commas and brackets.
        // Keep a source-order prefix of complete spans; do not skip a large span and resume later.
        List<SourceSpan> bounded=new ArrayList<>();int serializedBytes=2;
        for(SourceSpan span:spans){
            int cost=JSON.objectNode().put("id",span.id()).put("text",span.text()).toString().getBytes(StandardCharsets.UTF_8).length+(bounded.isEmpty()?0:1);
            if(serializedBytes+cost>SERIALIZED_SPAN_LIMIT){omitted[0]=true;break;}
            bounded.add(span);serializedBytes+=cost;
        }
        return new PageContext(page,source.length(),limit,omitted[0],bounded,
            omitted[0]?List.of("STRUCTURED_SOURCE_TRUNCATED"):List.of(),digest(source));
    }
    private static void lines(String source,int start,int end,int limit,int page,List<SourceSpan> spans,boolean[] omitted){
        int at=start;
        while(at<end&&at<limit){
            int next=at;while(next<end&&source.charAt(next)!='\n'&&source.charAt(next)!='\r')next++;
            if(next<end){if(source.charAt(next++)=='\r'&&next<end&&source.charAt(next)=='\n')next++;}
            add(source,at,next,limit,page,spans,omitted);at=next;
        }
    }
    private static void add(String source,int start,int end,int limit,int page,List<SourceSpan> spans,boolean[] omitted){
        if(start>=end)return;
        if(end>limit||end-start>QUOTE_LIMIT){omitted[0]=true;return;}
        String text=source.substring(start,end);if(text.isBlank())return;
        spans.add(new SourceSpan(id(page,start,end,text),page,start,end,text));
    }

    /** Page mapping/isolation remains the adapter's responsibility, as in the legacy envelope. */
    public static JsonNode envelope(JsonNode input,List<Integer> requested)throws IOException {
        keys(input,Set.of("wire","pages","limitations"));
        if(!VERSION.equals(string(input.get("wire"),64)))throw invalid();
        JsonNode pages=input.get("pages");if(pages==null||!pages.isArray()||pages.size()>requested.size()+3)throw mapping();
        ObjectNode out=((ObjectNode)input).deepCopy();out.remove("wire");return out;
    }

    public static DecodedPage page(JsonNode input,PageContext context)throws IOException {
        keys(input,Set.of("page","f","hasMore","limitations"));
        if(!input.path("page").isIntegralNumber()||!input.path("page").canConvertToInt()||input.path("page").intValue()!=context.page())throw mapping();
        JsonNode fields=input.get("f"),hasMore=input.get("hasMore");
        // Match the existing ledger's 120-fact bound; never truncate an over-limit response.
        if(fields==null||!fields.isArray()||fields.size()>SPAN_FIELD_HARD_LIMIT||hasMore==null||!hasMore.isBoolean())throw invalid();
        Map<String,SourceSpan> byId=new HashMap<>();for(SourceSpan span:context.spans())byId.put(span.id(),span);
        ObjectNode out=JSON.objectNode().put("page",context.page());
        if(input.has("limitations"))out.set("limitations",input.get("limitations").deepCopy());
        ArrayNode observations=out.putArray("observations");List<Map<String,Object>> bindings=new ArrayList<>(),rejected=new ArrayList<>();int inputIndex=0;
        for(JsonNode field:fields){
            try {
                if(!field.isArray()||field.size()!=7)throw new IOException("VISUAL_SOURCE_SPAN_FIELD_TUPLE_INVALID");
                String label=string(field.get(0),100),value=string(field.get(1),2048),readability=string(field.get(2),30),spanId=string(field.get(3),160);
                String subject=string(field.get(4),160),entityType=string(field.get(5),30),recordKey=string(field.get(6),160);
                if(!Set.of("CLEAR","PARTIAL","UNREADABLE","NOT_OBSERVED").contains(readability))throw new IOException("VISUAL_SOURCE_SPAN_READABILITY_INVALID");
                if(!Set.of("VEHICLE","PERSON","COMPANY","CONTRACT","CERTIFICATE","UNKNOWN").contains(entityType))throw new IOException("VISUAL_SOURCE_SPAN_ENTITY_TYPE_INVALID");
                if(Set.of("UNREADABLE","NOT_OBSERVED").contains(readability)&&!value.isBlank())throw new IOException("VISUAL_SOURCE_SPAN_UNREADABLE_VALUE_INVALID");
                SourceSpan span=byId.get(spanId);if(span==null)throw new IOException("VISUAL_SOURCE_SPAN_INVALID");
                observations.addObject().put("type","FIELD").put("label",label).put("value",value).put("readability",readability)
                    .putNull("bbox").put("sourceQuote",span.text()).put("subject",subject).put("entityType",entityType).put("recordKey",recordKey);
                bindings.add(binding(context,span));
            }catch(IOException invalid){
                // Only safe positions and fixed codes leave the decoder; never retain field contents.
                rejected.add(Map.of("page",context.page(),"inputIndex",inputIndex,"code",invalid.getMessage()));
            }
            inputIndex++;
        }
        Set<String> limitations=new LinkedHashSet<>(context.coverageLimitations());
        if(fields.size()==SPAN_FIELD_TARGET)limitations.add("STRUCTURED_FIELD_LIMIT_REACHED");
        if(fields.size()>SPAN_FIELD_TARGET)limitations.add("STRUCTURED_OUTPUT_TARGET_EXCEEDED");
        if(!rejected.isEmpty())limitations.add("STRUCTURED_FIELDS_REJECTED");
        if(hasMore.booleanValue())limitations.add("STRUCTURED_FIELDS_REMAINING");
        return new DecodedPage(out,bindings,List.copyOf(limitations),rejected,fields.size());
    }

    /** Called only after JSON parse. This metadata is not part of the model-accepted schema. */
    private static Map<String,Object> binding(PageContext context,SourceSpan span){
        return Map.of("version",VERSION,"id",span.id(),"page",span.page(),"start",span.start(),"end",span.end(),
            "sourceTextHash",context.sourceTextHash(),"quoteHash",digest(span.text()));
    }

    /** Rebuild the exact page mapping before trusting even a persisted server binding. */
    static SourceSpan verifiedBinding(int page,String original,String quote,Object metadata){
        if(!(metadata instanceof Map<?,?> values)||!values.keySet().equals(BINDING_KEYS)||!VERSION.equals(values.get("version")))return null;
        int start=integer(values.get("start")),end=integer(values.get("end"));
        if(integer(values.get("page"))!=page||start<0||end<=start)return null;
        PageContext context=build(page,original);
        if(!context.sourceTextHash().equals(values.get("sourceTextHash"))||!digest(quote).equals(values.get("quoteHash")))return null;
        for(SourceSpan span:context.spans())if(span.start()==start&&span.end()==end&&span.id().equals(values.get("id"))&&span.text().equals(quote))return span;
        return null;
    }
    private static int integer(Object value){return value instanceof Number n&&n.doubleValue()==n.intValue()?n.intValue():-1;}
    private static String id(int page,int start,int end,String text){return "P"+page+"S"+start+"E"+end+"H"+digest(text).substring(0,16);}
    private static String digest(String text){return SourceSafety.digest(text.getBytes(StandardCharsets.UTF_8));}
    private static void keys(JsonNode object,Set<String> allowed)throws IOException {
        if(object==null||!object.isObject())throw invalid();for(Iterator<String> it=object.fieldNames();it.hasNext();)if(!allowed.contains(it.next()))throw invalid();
    }
    private static String string(JsonNode value,int maximum)throws IOException {if(value==null||!value.isTextual()||value.textValue().length()>maximum)throw invalid();return value.textValue();}
    private static IOException invalid(){return new IOException("VISUAL_SOURCE_SPAN_SCHEMA_INVALID");}
    private static IOException mapping(){return new IOException("VISUAL_PAGE_MAPPING_INVALID");}
}
