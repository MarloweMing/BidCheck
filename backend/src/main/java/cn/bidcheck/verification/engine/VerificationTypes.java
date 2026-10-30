package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;

final class VerificationTypes {
    static final String VERSION="verification-engine-2.28-source-occurrence-ledger";
    static final Set<String> PREDICATES=Set.of("MATERIAL","DECLARATION","SECTION","PERSON_ROLE","PERSON_COUNT","ENTITY_COUNT","NOT_APPLICABLE","CERTIFICATE","VALIDITY","MONTHS","RELATION","NUMBER","SAME_FIELD","PRICE_RECONCILE","DATE_RELATION","CONTRACT","SCORE_COUNT","TEXT_ANOMALY","QUALITY_ADVISORY","QUALITY","EXTERNAL","VISUAL","REFERENCE_TIME","INPUT","UNSUPPORTED");
    record Rule(String id,String description,String predicate,String subject,String field,String operator,String expected,String unit,
                Map<String,Object> parameters,String sourceQuote,String logic,List<Rule> children) {
        Rule { parameters=parameters==null?Map.of():Map.copyOf(parameters);children=children==null?List.of():List.copyOf(children); }
    }
    record Plan(String logic,List<Rule> rules,String method,List<String> issues,List<Map<String,Object>> policyNotes,Map<String,Object> requirementScope) {
        Plan(String logic,List<Rule> rules,String method,List<String> issues){this(logic,rules,method,issues,List.of(),Map.of());}
        Plan(String logic,List<Rule> rules,String method,List<String> issues,List<Map<String,Object>> policyNotes){this(logic,rules,method,issues,policyNotes,Map.of());}
    }
    record Source(String id,Evidence evidence,String opening,boolean index,boolean historical,String documentHash,int documentVersion,DocumentSubjectScope.Context scopeContext) {
        Source { scopeContext=scopeContext==null?DocumentSubjectScope.Context.unspecified():scopeContext; }
        Source(String id,Evidence evidence,String opening,boolean index,boolean historical,String documentHash,int documentVersion){this(id,evidence,opening,index,historical,documentHash,documentVersion,DocumentSubjectScope.Context.unspecified());}
        Source(String id,Evidence evidence,String opening,boolean index,boolean historical){this(id,evidence,opening,index,historical,"",0);}
    }
    record Fact(String id,String kind,String subject,String field,String rawValue,String value,String unit,String basis,String scope,
                List<String> sourceIds,Map<String,Object> attributes) { }
    record Gap(String code,String description,String requiredInput,String resumeTrigger) {
        Map<String,Object> map(){return linked("code",code,"description",description,"requiredInput",requiredInput,"resumeTrigger",resumeTrigger);}
    }
    record Decision(String id,String description,String status,String reason,List<Evidence> evidences,List<Gap> gaps,Map<String,Object> proof) {
        Map<String,Object> map(){return linked("id",id,"description",description,"status",status,"reason",reason,"evidences",evidences,"gaps",gaps.stream().map(Gap::map).toList(),"proof",proof);}
    }
    static Map<String,Object> linked(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(values[i].toString(),values[i+1]);return out;}
    static String str(Map<String,?> map,String key){Object v=map.get(key);return v==null?"":v.toString();}
    static String clean(String value){return value==null?"":value.replaceAll("\\s+","").trim();}
    static String identity(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String redact(String value){return value==null?"":value.replaceAll("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])","[证件号码已隐藏]");}
    static Gap gap(String code,String detail){return new Gap(code,detail,detail,"INPUT_OR_EVIDENCE_CHANGED");}
}
