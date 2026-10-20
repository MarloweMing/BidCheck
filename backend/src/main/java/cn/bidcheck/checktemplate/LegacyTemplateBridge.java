package cn.bidcheck.checktemplate;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.engine.CheckEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Narrow compatibility bridge for existing typed checkpoints. It deliberately refuses
 * SEMANTIC_RESPONSE and never asks a model to invent a template or parameter.
 */
@Component
public final class LegacyTemplateBridge {
    public static final String VERSION = "legacy-template-bridge-2";
    private static final Set<String> PRICE_FIELDS = Set.of("totalPrice", "bidPrice", "quotationTotal");
    private static final Set<String> OPERATORS = Set.of("=", "!=", "<=", "<", ">=", ">");
    private final CheckTemplateRegistry registry;
    private final TemplateCheckExecutor executor;
    private final CheckEngine legacyEngine = new CheckEngine();
    private final ObjectMapper json = new ObjectMapper();

    public LegacyTemplateBridge() { this(CheckTemplateRegistry.defaultRegistry()); }

    @Autowired
    public LegacyTemplateBridge(CheckTemplateRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = new TemplateCheckExecutor(registry);
    }

    public String identity() {
        return TemplateIdentity.digest(VERSION,Map.of(
                "executor",executor.identity(),
                "legacyEngine",CheckEngine.VERSION,
                "priceFields",PRICE_FIELDS.stream().sorted().toList(),
                "operators",OPERATORS.stream().sorted().toList(),
                "bridgeBinary",TemplateIdentity.classFingerprint(LegacyTemplateBridge.class)));
    }

    public boolean supports(Checkpoint checkpoint) {
        if (checkpoint == null || !checkpoint.enabled() || !checkpoint.confirmed()) return false;
        return switch (checkpoint.type()) {
            case "MATERIAL_EXISTENCE" -> text(checkpoint.expected());
            case "DATE_VALIDITY" -> certificateField(checkpoint.field()) && date(checkpoint.expected())
                    && Set.of(">=", ">").contains(checkpoint.operator());
            case "VALUE_COMPARE" -> PRICE_FIELDS.contains(checkpoint.field()) && decimal(checkpoint.expected())
                    && OPERATORS.contains(checkpoint.operator());
            default -> false;
        };
    }

    public BridgeResult evaluate(Checkpoint checkpoint, Document bid, Document tender, List<Fact> facts) {
        if (!supports(checkpoint)) throw new IllegalArgumentException("Checkpoint is not supported by the deterministic template bridge");
        Objects.requireNonNull(bid, "bid");
        facts = facts == null ? List.of() : List.copyOf(facts);
        CheckSpec spec = toSpec(checkpoint, tender);
        Evaluation legacy = legacyEngine.evaluate(checkpoint, bid, facts);
        EvidenceView evidence = legacyEvidence(spec, checkpoint, bid, facts, legacy);
        TemplateExecution execution = executor.execute(spec, evidence);
        return bridge(execution, legacy.evidences());
    }

    /** Converts an explicitly confirmed specification without duplicating projection mapping in BidService. */
    public BridgeResult evaluate(CheckSpec spec, EvidenceView evidence) {
        TemplateExecution execution = executor.execute(spec, evidence);
        return bridge(execution, List.of());
    }

    /** Uses source-bound domain evidence supplied by the local assembler in the published result. */
    public BridgeResult evaluate(CheckSpec spec, EvidenceView evidence, List<Evidence> domainEvidence) {
        TemplateExecution execution = executor.execute(spec, evidence);
        return bridge(execution, domainEvidence);
    }

    /**
     * Adapts the existing local fact readers to a frozen explicit specification. Unsupported
     * observation kinds stay unresolved; selecting a template never falls back to the compiler.
     */
    public BridgeResult evaluate(CheckSpec spec, Checkpoint checkpoint, Document bid, Document tender, List<Fact> facts) {
        Objects.requireNonNull(spec,"spec");Objects.requireNonNull(checkpoint,"checkpoint");Objects.requireNonNull(bid,"bid");
        Checkpoint adapted=checkpointForSpec(spec,checkpoint);
        if(adapted==null)return evaluate(spec,EvidenceView.empty());
        Evaluation legacy=legacyEngine.evaluate(adapted,bid,facts==null?List.of():facts);
        EvidenceView evidence=legacyEvidence(spec,adapted,bid,facts==null?List.of():facts,legacy);
        TemplateExecution execution=executor.execute(spec,evidence);
        return bridge(execution,legacy.evidences());
    }

    public CheckSpec toSpec(Checkpoint checkpoint, Document tender) {
        if (!supports(checkpoint)) throw new IllegalArgumentException("Checkpoint is not supported by the deterministic template bridge");
        String templateId;TemplateTypes.ProofStandard standard;Map<String,Object> parameters=new LinkedHashMap<>();
        switch(checkpoint.type()) {
            case "MATERIAL_EXISTENCE" -> {
                templateId="MATERIAL_PRESENT";standard=TemplateTypes.ProofStandard.MATERIAL_SUBMISSION;
                parameters.put("materialType",checkpoint.expected().trim());parameters.put("requireBody",true);
            }
            case "DATE_VALIDITY" -> {
                templateId="CERTIFICATE_VALIDITY";standard=TemplateTypes.ProofStandard.FACTUAL_CONDITION;
                parameters.put("certificateType","ANY_CERTIFICATE");parameters.put("comparisonDate",checkpoint.expected().trim());parameters.put("inclusive",">=".equals(checkpoint.operator()));
            }
            case "VALUE_COMPARE" -> {
                templateId="PRICE_COMPARE";standard=TemplateTypes.ProofStandard.FACTUAL_CONDITION;
                parameters.put("field",checkpoint.field());parameters.put("operator",checkpoint.operator());parameters.put("expected",normalizedExpected(checkpoint));
                parameters.put("currency","CNY");parameters.put("unit","元");parameters.put("tolerance","0");
            }
            default -> throw new IllegalArgumentException("Unsupported checkpoint type");
        }
        List<SourceRef> refs=checkpoint.source()==null?List.of():List.of(source(checkpoint.source(),tender));
        List<ParameterBinding> bindings=parameters.entrySet().stream().map(entry->new ParameterBinding(entry.getKey(),entry.getValue(),"LEGACY_CHECKPOINT","迁移自已确认的类型化检查项",refs)).toList();
        String signature=signature(templateId,checkpoint,parameters);
        return new CheckSpec("template-"+checkpoint.id(),1,"business-"+checkpoint.id(),checkpoint.id(),category(checkpoint.category()),
                TemplateTypes.Purpose.MANDATORY,TemplateTypes.VerificationMode.DOCUMENT,templateId,"1.0",SubjectSelector.bidder(),parameters,
                Applicability.applies(),standard,refs,bindings,Map.of(),Map.of("legacyCheckpointType",checkpoint.type()),Map.of("state","FULL","mapped",true),
                true,Confirmation.legacyConfirmed(),signature);
    }

    public BridgeResult bridge(TemplateExecution execution, List<Evidence> domainEvidence) {
        ResultProjection projection=execution.projection();
        Evaluation evaluation=toEvaluation(projection,domainEvidence);
        Map<String,Object> assessment=new LinkedHashMap<>();
        assessment.put("schemaVersion","template-result-v1");
        assessment.put("templateResult",json.convertValue(projection,new TypeReference<Map<String,Object>>(){}));
        assessment.put("executionRoute",execution.executionRoute());
        assessment.put("templateSpec",Map.of("id",execution.spec().id(),"revision",execution.spec().revision(),
                "templateId",execution.spec().templateId(),"templateVersion",execution.spec().templateVersion(),
                "category",execution.spec().category().name(),"purpose",execution.spec().purpose().name(),
                "verificationMode",execution.spec().verificationMode().name(),"proofStandard",execution.spec().proofStandard().name(),
                "semanticSignature",execution.spec().semanticSignature()));
        assessment.put("templateTrace",Map.of("identity",identity(),"executor",executor.identity(),"compileRequests",0,
                "evidenceNeedKeys",execution.evidenceNeeds().stream().map(EvidenceNeed::key).toList()));
        return new BridgeResult(evaluation,Map.copyOf(assessment),execution.spec(),execution);
    }

    public Evaluation toEvaluation(ResultProjection projection,List<Evidence> evidences) {
        String status=switch(projection.conclusion().state()){
            case SATISFIED->"PASS";case NOT_SATISFIED->"FAIL";case SUSPECTED_RISK->"WARNING";
            case NOT_APPLICABLE->"NOT_APPLICABLE";case UNDETERMINED->"UNDETERMINED";};
        double confidence=switch(projection.evidence().state()){case SUFFICIENT->0.95;case PARTIAL->0.55;case CONFLICTING->0.35;default->0.25;};
        return new Evaluation(status,projection.conclusion().summary(),projection.nextStep(),evidences==null?List.of():List.copyOf(evidences),confidence,"TEMPLATE_FIXED");
    }

    private EvidenceView legacyEvidence(CheckSpec spec,Checkpoint checkpoint,Document bid,List<Fact> facts,Evaluation legacy) {
        List<Observation> observations=new ArrayList<>();boolean determined=Set.of("PASS","FAIL").contains(legacy.status());
        if("MATERIAL_PRESENT".equals(spec.templateId())){
            List<SourceRef> sources=legacy.evidences().stream().map(e->source(e,bid)).toList();
            if("PASS".equals(legacy.status()))observations.add(new Observation.MaterialObservation("legacy-material-"+checkpoint.id(),checkpoint.expected(),Set.of(),"BIDDER",true,true,false,Set.of(),TemplateTypes.ObservationState.SUPPORTED,sources,legacy.confidence()));
            else if("FAIL".equals(legacy.status()))observations.add(new Observation.MaterialObservation("legacy-material-"+checkpoint.id(),checkpoint.expected(),Set.of(),"BIDDER",true,false,false,Set.of(),TemplateTypes.ObservationState.REFUTED,sources,legacy.confidence()));
            else if(!sources.isEmpty())observations.add(new Observation.MaterialObservation("legacy-material-"+checkpoint.id(),checkpoint.expected(),Set.of(),"BIDDER",true,false,true,Set.of(),TemplateTypes.ObservationState.UNRESOLVED,sources,legacy.confidence()));
        } else if("CERTIFICATE_VALIDITY".equals(spec.templateId())&&determined){
            for(Fact fact:usedFacts(facts,legacy.evidences(),"certificateExpiry")){
                LocalDate until=parseDate(fact.normalizedValue());if(until==null)continue;
                observations.add(new Observation.CertificateObservation("legacy-cert-"+observations.size(),"ANY_CERTIFICATE",fact.entityKey(),"","",null,until,LocalDate.of(9999,12,31).equals(until),true,TemplateTypes.ObservationState.SUPPORTED,List.of(source(fact.evidence(),bid)),1));
            }
        } else if("PRICE_COMPARE".equals(spec.templateId())&&determined){
            for(Fact fact:usedFacts(facts,legacy.evidences(),checkpoint.field())){
                ParsedPrice parsed=parsePrice(fact.normalizedValue());if(parsed==null)continue;
                observations.add(new Observation.PriceObservation("legacy-price-"+observations.size(),checkpoint.field(),"BIDDER","","TOTAL",parsed.taxBasis(),"CNY","元",parsed.amount(),true,TemplateTypes.ObservationState.SUPPORTED,List.of(source(fact.evidence(),bid)),1));
            }
        }
        Set<String> closed=new LinkedHashSet<>();if(determined)registry.find(spec.templateId(),spec.templateVersion()).orElseThrow().evidenceNeeds(spec).forEach(need->closed.add(need.key()));
        return new EvidenceView("legacy:"+bid.id()+":"+bid.version(),0,observations,closed,Set.of(),Map.of());
    }

    private Checkpoint checkpointForSpec(CheckSpec spec,Checkpoint original){
        Map<String,Object> p=spec.parameters();String type,field,operator,expected,unit;
        switch(spec.templateId()){
            case "MATERIAL_PRESENT"->{type="MATERIAL_EXISTENCE";field="material";operator="EXISTS";expected=value(p,"materialType");unit="";}
            case "CERTIFICATE_VALIDITY"->{type="DATE_VALIDITY";field="certificateExpiry";operator=Boolean.FALSE.equals(p.get("inclusive"))?">":">=";expected=value(p,"comparisonDate");unit="DATE";}
            case "PRICE_COMPARE"->{type="VALUE_COMPARE";field=value(p,"field");operator=value(p,"operator");expected=value(p,"expected");unit=value(p,"unit");}
            default->{return null;}
        }
        Checkpoint adapted=new Checkpoint(original.id(),original.title(),original.category(),original.severity(),type,field,operator,expected,unit,
            original.required(),true,true,original.source());
        return supports(adapted)?adapted:null;
    }

    private String value(Map<String,Object> values,String key){Object value=values.get(key);return value==null?"":String.valueOf(value).trim();}

    private List<Fact> usedFacts(List<Fact> facts,List<Evidence> used,String field){return facts.stream().filter(f->field.equals(f.field())).filter(f->used.stream().anyMatch(e->sameLocation(e,f.evidence()))).toList();}
    private boolean sameLocation(Evidence left,Evidence right){return left.documentId().equals(right.documentId())&&left.page()==right.page()&&(left.anchor().isBlank()||right.anchor().isBlank()||left.anchor().equals(right.anchor()));}
    private SourceRef source(Evidence evidence,Document document){int version=document!=null&&document.id().equals(evidence.documentId())?document.version():1;String hash=document!=null&&document.id().equals(evidence.documentId())?document.sha256():"";return SourceRef.legacy(evidence.documentId(),version,hash,evidence.locationType(),evidence.page(),evidence.quote(),evidence.anchor());}

    private TemplateTypes.Category category(String value){String category=value==null?"":value; if(category.contains("形式")||category.toUpperCase().contains("FORM"))return TemplateTypes.Category.FORMAL;if(category.contains("资格")||category.toUpperCase().contains("QUAL"))return TemplateTypes.Category.QUALIFICATION;if(category.contains("全文")||category.toUpperCase().contains("FULL"))return TemplateTypes.Category.FULL_TEXT;if(category.contains("效期")||category.toUpperCase().contains("VALID"))return TemplateTypes.Category.VALIDITY;return TemplateTypes.Category.RESPONSIVENESS;}
    private String normalizedExpected(Checkpoint checkpoint){BigDecimal value=new BigDecimal(checkpoint.expected().trim());if("万元".equals(checkpoint.unit())||"万".equals(checkpoint.unit()))value=value.multiply(new BigDecimal("10000"));else if("亿元".equals(checkpoint.unit()))value=value.multiply(new BigDecimal("100000000"));return value.stripTrailingZeros().toPlainString();}
    private String signature(String templateId,Checkpoint checkpoint,Map<String,Object> parameters){String value=templateId+"|"+checkpoint.field()+"|"+checkpoint.operator()+"|"+parameters;try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private ParsedPrice parsePrice(String value){if(value==null||value.isBlank())return null;String tax="";String numeric=value;if(value.contains(":")){String[] pieces=value.split(":",2);tax=pieces[0];numeric=pieces[1];}try{return new ParsedPrice(new BigDecimal(numeric),tax);}catch(NumberFormatException error){return null;}}
    private LocalDate parseDate(String value){try{return LocalDate.parse(value);}catch(Exception error){return null;}}
    private boolean certificateField(String field){return "certificateExpiry".equals(field)||"certificateCoverage".equals(field);}
    private boolean date(String value){try{LocalDate.parse(value);return true;}catch(DateTimeParseException|NullPointerException error){return false;}}
    private boolean decimal(String value){try{new BigDecimal(value);return true;}catch(NumberFormatException|NullPointerException error){return false;}}
    private boolean text(String value){return value!=null&&!value.isBlank();}
    private record ParsedPrice(BigDecimal amount,String taxBasis){}

    public record BridgeResult(Evaluation evaluation, Map<String,Object> assessment, CheckSpec spec, TemplateExecution execution) { }
}
