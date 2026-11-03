package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.facts.FactLedgerService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.bidcheck.verification.source.SourceSafety.map;

/** Converts visual transcription into source-text facts without promoting proposed row ownership to proof. */
public final class StructuredContextFacts {
    public static final String VERSION="structured-context-facts-2-field-admission";
    private StructuredContextFacts(){}

    public static Map<String,Object> batch(Document document,List<Integer> pages,Map<String,Object> response,String modelIdentity){
        List<Map<String,Object>> sources=new ArrayList<>(),facts=new ArrayList<>(),rejected=new ArrayList<>();
        boolean independent=Boolean.TRUE.equals(response.get("independentPages"));
        List<Map<String,Object>> relations=independent?cn.bidcheck.compat.Java8Api.listOf():VisualPageWindows.relations(document,pages);
        Set<String> seen=new HashSet<>();Map<String,Map<String,Object>> refs=new LinkedHashMap<>();
        if(document!=null&&pages!=null&&(independent?VisualPageWindows.validIndependent(document,pages):VisualPageWindows.valid(document,pages)))for(Map<String,Object> item:maps(response.get("observations"))){
            int number=integer(item.get("sourcePage"),integer(item.get("page"),-1));String value=text(item,"value"),quote=text(item,"sourceQuote"),field=text(item,"label");
            String code="";Page page=document.pages().stream().filter(p->p.number()==number).findFirst().orElse(null);
            StructuredSourceSpans.SourceSpan boundSpan=null;
            Map<String,Object> origin=castMap(item.get("source"));
            VisualObservationPolicy.Assessment admission=VisualObservationPolicy.assess(item);
            if(page==null||!pages.contains(number)||!document.id().equals(origin.get("documentId"))||!document.projectId().equals(origin.get("projectId"))
                ||!document.sha256().equals(origin.get("documentHash"))||document.version()!=integer(origin.get("documentVersion"),-1)||number!=integer(origin.get("page"),-1))code="CONTEXT_SOURCE_SCOPE_INVALID";
            else if(!"FIELD".equals(item.get("type"))||!admission.valueUsable())code="CONTEXT_FIELD_NEEDS_REVIEW";
            else if(cn.bidcheck.compat.Java8Api.isBlank(value)||value.length()>2048||cn.bidcheck.compat.Java8Api.isBlank(field)||field.length()>100||cn.bidcheck.compat.Java8Api.isBlank(quote)||quote.length()>4000
                ||!Objects.toString(page.text(),"").contains(quote)||!quote.contains(value))code="CONTEXT_QUOTE_NOT_ON_SOURCE_PAGE";
            if(code.isEmpty()&&item.containsKey("sourceSpan")){
                boundSpan=StructuredSourceSpans.verifiedBinding(number,Objects.toString(page.text(),""),quote,item.get("sourceSpan"));
                if(boundSpan==null)code="CONTEXT_SOURCE_SPAN_INVALID";
            }
            if(!code.isEmpty()){rejected.add(map("page",number,"field",field,"code",code));continue;}
            boolean ownershipUncertain=admission.constraints().stream().anyMatch(c->c.kind()==VisualObservationPolicy.Kind.RELATION_UNPROVEN);
            String proposedSubject=text(item,"subject"),subject=!ownershipUncertain&&boundInOneRow(quote,proposedSubject,value)?proposedSubject:"";
            String entityType=cn.bidcheck.compat.Java8Api.isBlank(subject)?"UNKNOWN":text(item,"entityType");
            if(!cn.bidcheck.compat.Java8Api.setOf("VEHICLE", "PERSON", "COMPANY", "CONTRACT", "CERTIFICATE", "UNKNOWN").contains(entityType))entityType="UNKNOWN";
            // A row number is page-local. Only an explicitly repeated, strong identifier may group across pages.
            String record=text(item,"recordKey");boolean strongRecord=record.matches("[A-HJ-NPR-Z0-9]{17}|[0-9]{17}[0-9Xx]")&&boundInOneRow(quote,record,value);
            String recordKey=strongRecord?record:document.id()+":"+number+":"+digest(quote).substring(0,16);
            String original=Objects.toString(page.text(),"");int at=boundSpan==null?original.indexOf(quote):boundSpan.start();
            String sourceId="C"+digest(document.id()+"\n"+document.sha256()+"\n"+document.version()+"\n"+FactLedgerService.parserFingerprint(page)+"\n"+at+"\n"+quote).substring(0,24);
            refs.putIfAbsent(sourceId,map("id",sourceId,"projectId",document.projectId(),"documentHash",document.sha256(),"documentVersion",document.version(),
                "parserFingerprint",FactLedgerService.parserFingerprint(page),"evidence",map("documentId",document.id(),"documentName",document.name(),"page",number,
                    "locationType",page.locationType(),"quote",quote,"anchor","chars:"+at+"-"+(at+quote.length()),"confidence",0)));
            Map<String,Object> attributes=map("entityType",entityType,"sourcePage",number,"recordKey",recordKey,"recordIdentityEstablished",strongRecord,
                "subjectBinding",cn.bidcheck.compat.Java8Api.isBlank(subject)?"UNRESOLVED":"SAME_SOURCE_ROW","contextWindowPages",independent?cn.bidcheck.compat.Java8Api.listOf(number):cn.bidcheck.compat.Java8Api.listCopyOf(pages),"independentBatch",independent,"contextRelationIds",cn.bidcheck.compat.Java8Api.toList(relations.stream().map(r->r.get("relationId"))),
                "schemaVersion",VisualVerificationService.FACT_SCHEMA,"localization",item.getOrDefault("localization","UNAVAILABLE"),"sourceBbox",item.get("sourceBbox"),
                "boundsProof","MODEL_PROPOSED_ONLY","visualModelIdentity",modelIdentity,"constraintAssessment",admission.audit());
            String id="CF"+digest(sourceId+"\n"+entityType+"\n"+subject+"\n"+field+"\n"+value).substring(0,24);
            if(seen.add(id))facts.add(map("id",id,"kind","FIELD","subject",subject,"field",field,"rawValue",value,"value",value,"unit","","basis","SOURCE_TEXT_AND_VISUAL",
                "scope","UNRESOLVED","sourceIds",cn.bidcheck.compat.Java8Api.listOf(sourceId),"attributes",attributes));
        }
        sources.addAll(refs.values());
        return map("sources",sources,"facts",facts,"layoutCells",cn.bidcheck.compat.Java8Api.listOf(),"layoutIdentity","","contextRelations",relations,"rejected",rejected,
            "operationIdentity",VERSION+":"+Objects.toString(modelIdentity,"")+":"+Objects.toString(response.get("requestFingerprint"),""),"engineVersion",VERSION,
            "schemaVersion",VisualVerificationService.FACT_SCHEMA,"inputPages",pages==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(pages));
    }
    private static boolean boundInOneRow(String quote,String subject,String value){
        if(cn.bidcheck.compat.Java8Api.isBlank(subject)||subject.length()>160)return false;
        if(quote.toLowerCase(Locale.ROOT).contains("<tr")){
            java.util.regex.Matcher rows=java.util.regex.Pattern.compile("(?is)<tr\\b[^>]*>.*?</tr\\s*>").matcher(quote);
            while(rows.find()){String row=rows.group();if(row.length()<=2000&&row.contains(subject)&&row.contains(value))return true;}return false;
        }
        return Arrays.stream(quote.split("[\\r\\n；;。]")).filter(line->line.length()<=1000).anyMatch(line->line.contains(subject)&&line.contains(value));
    }
    private static boolean mapsOrStringsEmpty(Object value){return value==null||value instanceof List<?>&&((List<?>) (value)).isEmpty();}
    private static String text(Map<String,Object> value,String key){return Objects.toString(value.get(key),"");}
    private static int integer(Object value,int fallback){return value instanceof Number&&((Number) (value)).doubleValue()==((Number) (value)).intValue()?((Number) (value)).intValue():fallback;}
    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object value){return value instanceof Map<?,?>?(Map<String,Object>)((Map<?,?>) (value)):cn.bidcheck.compat.Java8Api.mapOf();}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?>))return cn.bidcheck.compat.Java8Api.listOf();return cn.bidcheck.compat.Java8Api.toList(((List<?>) (value)).stream().filter(v->v instanceof Map<?,?>).map(StructuredContextFacts::castMap));}
    private static String digest(String value){return SourceSafety.digest(value.getBytes(StandardCharsets.UTF_8));}
}
