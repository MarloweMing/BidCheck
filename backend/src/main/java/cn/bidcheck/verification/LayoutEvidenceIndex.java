package cn.bidcheck.verification;

import java.util.*;
import java.util.function.Function;
import static cn.bidcheck.verification.VerificationService.*;

/** Bounded on-demand table context. Adjacent-page candidates are not themselves continuation proofs. */
public final class LayoutEvidenceIndex implements Function<Map<String,Object>,List<Map<String,Object>>> {
    private final Map<String,List<Map<String,Object>>> documents=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> identities=new LinkedHashMap<>();
    public void add(String documentId,Map<String,Object> layout) {
        if(!"COMPLETE".equals(layout.get("status"))) return;
        documents.put(documentId,maps(layout.get("spans")));
        identities.put(documentId,layout);
    }
    @Override public List<Map<String,Object>> apply(Map<String,Object> query) {
        String docId=Objects.toString(query.get("documentId"),"");
        int location=number(query.get("location"),number(query.get("page"),0));
        String subject=Objects.toString(query.get("subject"),"").strip();
        String requestedType=Objects.toString(query.get("locationType"),"");
        List<Map<String,Object>> spans=documents.getOrDefault(docId,List.of());
        String quote=Objects.toString(query.get("sourceQuote"),"");
        List<Map<String,Object>> located=spans.stream().filter(span->{int at=location(span);String text=Objects.toString(span.get("text"),"");return !"DELETED".equals(span.get("revision"))&&(location>0?at==location&&(requestedType.isBlank()||requestedType.equals(locationType(span))):!subject.isBlank()&&text.contains(subject));}).toList();
        // Select relevant table records before generic OCR text boxes. Long pages must not starve their cells.
        List<Map<String,Object>> ranked=located.stream().filter(s->"TABLE_CELL".equals(s.get("kind"))).sorted(Comparator.<Map<String,Object>>comparingInt(s->relevance(s,subject,quote)).reversed()).toList();
        List<Map<String,Object>> seeds=new ArrayList<>();Set<String> seededRows=new HashSet<>();
        for(Map<String,Object> span:ranked){if(Boolean.TRUE.equals(span.get("header")))continue;String key=tableGroup(span)+":"+number(span.get("row"),0);if(seededRows.add(key)){seeds.add(span);if(seeds.size()>=24)break;}}
        // Repeated explicit identity retrieves an adjacent row only as a candidate. TableStructure verifies continuation.
        Set<String> identityValues=new HashSet<>();for(Map<String,Object> seed:seeds)for(Map<String,Object> span:ranked)if(sameRow(seed,span)){String value=Objects.toString(span.get("text"),"").replaceAll("\\s+","");if(value.matches("[0-9]{17}[0-9Xx]"))identityValues.add(value.toUpperCase(Locale.ROOT));}
        if("PAGE".equals(requestedType))for(Map<String,Object> span:spans)if("TABLE_CELL".equals(span.get("kind"))&&"PAGE".equals(locationType(span))&&Math.abs(location(span)-location)==1&&identityValues.contains(Objects.toString(span.get("text"),"").replaceAll("\\s+","").toUpperCase(Locale.ROOT))&&seeds.size()<32&&seededRows.add(tableGroup(span)+":"+number(span.get("row"),0)))seeds.add(span);
        seeds.sort(Comparator.<Map<String,Object>>comparingInt(s->relevance(s,subject,quote)).reversed());
        List<Map<String,Object>> candidates=new ArrayList<>();Set<String> selectedIds=new HashSet<>();
        for(Map<String,Object> hit:seeds){
            // Every chosen data row is accompanied immediately by its actual headers, before the next row consumes budget.
            for(Map<String,Object> span:spans)if(!"DELETED".equals(span.get("revision"))&&sameRow(hit,span))add(candidates,selectedIds,span);
            for(Map<String,Object> span:spans)if(!"DELETED".equals(span.get("revision"))&&tableGroup(hit).equals(tableGroup(span))&&(Boolean.TRUE.equals(span.get("header"))||number(span.get("row"),99)<=2))add(candidates,selectedIds,span);
        }
        for(Map<String,Object> span:located)add(candidates,selectedIds,span);
        List<Map<String,Object>> output=new ArrayList<>();int characters=0;
        for(Map<String,Object> span:candidates) {
            String text=Objects.toString(span.get("text"),"");if(text.isBlank()||text.length()>4000||characters+text.length()>16000) continue;characters+=text.length();
            Map<String,Object> row=new LinkedHashMap<>();row.put("documentId",docId);row.put("spanId",span.get("id"));row.put("text",text);
            row.put("locationType",locationType(span));row.put("location",location(span));
            Map<String,Object> identity=identities.getOrDefault(docId,Map.of());row.put("documentHash",span.getOrDefault("documentHash",identity.get("documentHash")));row.put("documentVersion",span.getOrDefault("documentVersion",identity.get("documentVersion")));
            for(String key:List.of("kind","tableId","row","column","columnSpan","rowSpan","mergeContinuationOf","mergeUnresolved","bbox","coordinateSpace","source","ooxml","revision","frameId","imageHash","imageWidth","imageHeight","crop","headerCellIds","headerText","columnHeader","header","renderedMapping")) if(span.containsKey(key)) row.put(key,span.get(key));
            row.put("cellId","TABLE_CELL".equals(span.get("kind"))?span.get("id"):null);output.add(row);
        }
        return output;
    }
    private static void add(List<Map<String,Object>> out,Set<String> ids,Map<String,Object> span){if(out.size()<120&&ids.add(Objects.toString(span.get("id"),"")))out.add(span);}
    private static int relevance(Map<String,Object> span,String subject,String quote){String text=Objects.toString(span.get("text"),"");if(text.isBlank())return 0;int score=!subject.isBlank()&&text.contains(subject)?10000:0;if(!quote.isBlank()&&quote.contains(text))score+=text.matches("[0-9]{17}[0-9Xx]")?1000:100+Math.min(100,text.length());if(Boolean.TRUE.equals(span.get("header"))||number(span.get("row"),99)<=1)score-=200;return score;}
    private static String tableGroup(Map<String,Object> span){String table=Objects.toString(span.get("tableId"),"");if(table.isBlank())return "missing:"+span.get("id");String source=Objects.toString(span.get("source"),"");boolean ocr=source.equals("OCR_STRUCTURED")||source.equals("STRUCTURED_OCR");return table+":"+Objects.toString(span.get("frameId"),"")+(ocr?":"+locationType(span)+":"+location(span):"");}
    private static boolean sameRow(Map<String,Object> hit,Map<String,Object> cell){int row=number(hit.get("row"),-1),start=number(cell.get("row"),-2);return "TABLE_CELL".equals(cell.get("kind"))&&!Objects.toString(hit.get("tableId"),"").isBlank()&&tableGroup(hit).equals(tableGroup(cell))&&row>=start&&row<start+number(cell.get("rowSpan"),1);}
    private static int location(Map<String,Object> span){return number(span.get("location"),number(span.get("physicalPage"),number(span.get("documentBlock"),0)));}
    private static String locationType(Map<String,Object> span){String type=Objects.toString(span.get("locationType"),"");return type.isBlank()?span.get("physicalPage")!=null?"PAGE":"BLOCK":type;}
}
