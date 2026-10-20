package cn.bidcheck.checktemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Factory and deterministic implementations for the first template set. */
final class CoreTemplateEvaluators {
    private static final Set<String> CONSISTENCY_FIELDS=Set.of("companyName","creditCode","projectName","projectNumber",
            "totalPrice","duration","designDuration","constructionDuration","installationDuration",
            "commissioningDuration","bidValidity","warranty","documentDate","date");
    private CoreTemplateEvaluators() { }

    static Optional<CheckTemplate> create(TemplateMetadata metadata) {
        return Optional.ofNullable(switch (metadata.evaluatorId()) {
            case "material-present" -> new MaterialPresent(metadata);
            case "commitment-with-seal" -> new CommitmentWithSeal(metadata);
            case "material-alternative" -> new MaterialAlternative(metadata);
            case "certificate-validity" -> new CertificateValidity(metadata);
            case "price-compare" -> new PriceCompare(metadata);
            case "personnel-roster" -> new PersonnelRoster(metadata);
            case "section-checklist" -> new SectionChecklist(metadata);
            case "entity-field-match" -> new EntityFieldMatch(metadata);
            case "personnel-credential" -> new PersonnelCredential(metadata);
            case "consortium-responsibility" -> new ConsortiumResponsibility(metadata);
            case "price-reconcile" -> new PriceReconcile(metadata);
            case "duration-compare" -> new DurationCompare(metadata);
            case "cross-document-consistency" -> new CrossDocumentConsistency(metadata);
            case "form-field-completeness" -> new FormFieldCompleteness(metadata);
            case "date-relation" -> new DateRelation(metadata);
            case "text-anomaly" -> new TextAnomaly(metadata);
            case "objective-scoring" -> new ObjectiveScoring(metadata);
            case "quality-advisory" -> new QualityAdvisory(metadata);
            case "external-check" -> new ExternalCheck(metadata);
            default -> null;
        });
    }

    private abstract static class Base implements CheckTemplate {
        final TemplateMetadata metadata;
        final ParameterSchema schema;
        Base(TemplateMetadata metadata) { this.metadata = metadata; this.schema = new ParameterSchema(metadata.parameters()); }
        @Override public String id() { return metadata.id(); }
        @Override public String version() { return metadata.version(); }
        @Override public TemplateMetadata metadata() { return metadata; }
        @Override public ParameterSchema parameterSchema() { return schema; }
        Map<String, Object> parameters(CheckSpec spec) { return schema.withDefaults(spec.parameters()); }

        EvidenceNeed need(CheckSpec spec, String suffix, TemplateTypes.NeedKind kind, Set<String> fields,
                          Map<String, String> constraints, boolean closure) {
            return new EvidenceNeed(spec.id() + ":" + suffix, kind, spec.subjectSelector(), fields, constraints, true, closure);
        }

        TemplateResult result(TemplateTypes.ConclusionState conclusion, TemplateTypes.EvidenceState evidence,
                              String reasonCode, String summary, List<ConditionResult> conditions,
                              Collection<String> evidenceRefs, Collection<String> missing,
                              Collection<String> dependencies) {
            boolean satisfied = conclusion == TemplateTypes.ConclusionState.SATISFIED
                    || conclusion == TemplateTypes.ConclusionState.NOT_APPLICABLE;
            boolean determinedRisk = conclusion == TemplateTypes.ConclusionState.NOT_SATISFIED
                    || conclusion == TemplateTypes.ConclusionState.SUSPECTED_RISK;
            return new TemplateResult(conclusion, evidence, reasonCode, summary, conditions,
                    distinct(evidenceRefs), distinct(missing), distinct(dependencies),
                    satisfied ? TemplateTypes.ActionOwner.NONE : determinedRisk ? TemplateTypes.ActionOwner.USER : TemplateTypes.ActionOwner.SYSTEM,
                    satisfied ? "NONE" : determinedRisk ? "REVIEW_RISK" : "LOCATE_EVIDENCE",
                    satisfied ? "" : determinedRisk ? "请核对所列原文证据及要求。" : "系统将按缺失字段定向补查；未取得证据前不判定材料有问题。");
        }

        ConditionResult condition(String id, String label, TemplateTypes.ConditionState state, String code,
                                  String summary, Collection<String> evidence, Collection<String> missing) {
            return new ConditionResult(id, label, state, code, summary, distinct(evidence), distinct(missing));
        }
    }

    private static final class MaterialPresent extends Base {
        MaterialPresent(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);String type=string(p,"materialType");
            return List.of(need(spec,"material",TemplateTypes.NeedKind.MATERIAL,Set.of("bodyPresent"),Map.of("materialType",type),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec, EvidenceView view) {
            Map<String,Object> p=parameters(spec);String type=string(p,"materialType");boolean requireBody=bool(p,"requireBody",true);
            EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream()
                    .filter(o->materialTypeMatches(o,type)).toList();
            List<Observation.MaterialObservation> matches=candidates.stream()
                    .filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            boolean unresolved=candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN)
                    ||matches.stream().anyMatch(CoreTemplateEvaluators::invalidSupported);
            List<String> refs=sourceIds(matches);
            if(matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING))
                return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"MATERIAL_CONFLICTING",
                        "发现同一材料的冲突版本，暂不能确定。",List.of(condition("material","材料正文",TemplateTypes.ConditionState.UNKNOWN,"MATERIAL_CONFLICTING","材料版本冲突",refs,List.of())),refs,List.of("material.version"),List.of(need.key()));
            Optional<Observation.MaterialObservation> found=matches.stream().filter(CoreTemplateEvaluators::supported)
                    .filter(o->!requireBody||(o.bodyPresent()&&!o.titleOnly())).findFirst();
            if(found.isPresent())
                return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"MATERIAL_PRESENT",
                        "已定位到“"+type+"”正文及其当前项目来源。",List.of(condition("material","材料正文",TemplateTypes.ConditionState.SATISFIED,"MATERIAL_PRESENT","材料正文已提供",refs,List.of())),refs,List.of(),List.of(need.key()));
            if(view.isClosed(need)&&!unresolved)
                return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.NOT_LOCATED,"MATERIAL_NOT_PRESENT",
                        "已完成允许范围核对，未找到满足要求的“"+type+"”正文。",List.of(condition("material","材料正文",TemplateTypes.ConditionState.NOT_SATISFIED,"MATERIAL_NOT_PRESENT","未找到材料正文",refs,List.of())),refs,List.of(),List.of(need.key()));
            return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,
                    "MATERIAL_EVIDENCE_PENDING","尚未取得足以确认“"+type+"”正文的证据。",List.of(condition("material","材料正文",TemplateTypes.ConditionState.UNKNOWN,"MATERIAL_EVIDENCE_PENDING","材料正文待定位",refs,List.of("material.body"))),refs,List.of("material.body"),List.of(need.key()));
        }
    }

    private static final class CommitmentWithSeal extends Base {
        CommitmentWithSeal(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);String type=string(p,"materialType");String issuer=string(p,"issuerRole"),location=string(p,"sealLocation");
            return List.of(
                    need(spec,"commitment",TemplateTypes.NeedKind.MATERIAL,Set.of("bodyPresent","commitmentPoints","issuerRole"),Map.of("materialType",type,"issuerRole",issuer),true),
                    need(spec,"seal",TemplateTypes.NeedKind.SEAL,Set.of("observed","location","materialObservationId"),Map.of("materialType",type,"sealLocation",location),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec, EvidenceView view) {
            Map<String,Object> p=parameters(spec);String type=string(p,"materialType"),issuerRole=string(p,"issuerRole"),sealLocation=string(p,"sealLocation");List<String> points=strings(p,"requiredCommitmentPoints");
            List<EvidenceNeed> needs=evidenceNeeds(spec);EvidenceNeed materialNeed=needs.get(0),sealNeed=needs.get(1);
            List<Observation.MaterialObservation> materialCandidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,type)).toList();
            List<Observation.MaterialObservation> materials=materialCandidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            boolean materialUnresolved=materialCandidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN)
                    ||materials.stream().anyMatch(CoreTemplateEvaluators::invalidSupported);
            List<String> materialRefs=sourceIds(materials);
            Optional<Observation.MaterialObservation> body=materials.stream().filter(CoreTemplateEvaluators::supported).filter(o->o.bodyPresent()&&!o.titleOnly()).findFirst();
            TemplateTypes.ConditionState bodyState=body.isPresent()?TemplateTypes.ConditionState.SATISFIED:view.isClosed(materialNeed)&&!materialUnresolved?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
            Set<String> observedPoints=body.map(Observation.MaterialObservation::commitmentPoints).orElse(Set.of());
            List<String> missingPoints=points.stream().filter(point->observedPoints.stream().noneMatch(actual->same(actual,point))).toList();
            TemplateTypes.ConditionState pointState=body.isEmpty()?TemplateTypes.ConditionState.UNKNOWN:missingPoints.isEmpty()?TemplateTypes.ConditionState.SATISFIED:view.isClosed(materialNeed)?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
            SubjectMatch issuerMatch=body.map(value->issuerMatch(value,issuerRole)).orElse(SubjectMatch.UNKNOWN);
            TemplateTypes.ConditionState issuerState=issuerMatch==SubjectMatch.MATCH?TemplateTypes.ConditionState.SATISFIED
                    :issuerMatch==SubjectMatch.NO_MATCH?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
            String materialId=body.map(Observation.MaterialObservation::id).orElse("");
            List<Observation.SealObservation> sealCandidates=view.observations(Observation.SealObservation.class).stream()
                    .filter(o->subjectMatch(spec.subjectSelector(),o)!=SubjectMatch.NO_MATCH).toList();
            List<Observation.SealObservation> seals=sealCandidates.stream()
                    .filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH)
                    .filter(o->same(o.location(),sealLocation))
                    .filter(o->body.isPresent()&&sealBoundToMaterial(o,body.get())).toList();
            List<String> sealRefs=sourceIds(seals);
            boolean sealObserved=seals.stream().anyMatch(o->supported(o)&&o.observed());
            boolean sealUnresolved=sealCandidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN||invalidSupported(o))
                    ||sealCandidates.stream().anyMatch(o->supported(o)&&o.observed()&&(o.location().isBlank()||body.isEmpty()||!sealBoundToMaterial(o,body.get())));
            TemplateTypes.ConditionState sealState=sealObserved?TemplateTypes.ConditionState.SATISFIED:view.isClosed(sealNeed)&&!sealUnresolved?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
            List<ConditionResult> conditions=List.of(
                    condition("body","承诺书正文",bodyState,"COMMITMENT_BODY",bodyState==TemplateTypes.ConditionState.SATISFIED?"承诺书正文已定位":"承诺书正文尚未完整定位",materialRefs,bodyState==TemplateTypes.ConditionState.UNKNOWN?List.of("material.body"):List.of()),
                    condition("points","必要承诺点",pointState,"COMMITMENT_POINTS",missingPoints.isEmpty()?"必要承诺点已覆盖":"仍缺承诺点："+String.join("、",missingPoints),materialRefs,missingPoints),
                    condition("issuer","签发主体",issuerState,"COMMITMENT_ISSUER",issuerState==TemplateTypes.ConditionState.SATISFIED?"签发主体符合要求":"签发主体尚未得到可靠证明",materialRefs,issuerState==TemplateTypes.ConditionState.UNKNOWN?List.of("material.issuerRole"):List.of()),
                    condition("seal","盖章位置",sealState,"SEAL_OBSERVED",sealObserved?"已观察到与承诺书绑定且位于“"+sealLocation+"”的盖章":"尚未观察到与承诺书绑定且位于“"+sealLocation+"”的盖章",sealRefs,sealState==TemplateTypes.ConditionState.UNKNOWN?List.of("seal.observed","seal.location","seal.materialBinding"):List.of()));
            TemplateTypes.ConditionState combined=RestrictedLogic.allOf(conditions.stream().map(ConditionResult::state).toList());
            List<String> refs=new ArrayList<>(materialRefs);refs.addAll(sealRefs);
            List<String> missing=new ArrayList<>(missingPoints);if(bodyState==TemplateTypes.ConditionState.UNKNOWN)missing.add("material.body");if(issuerState==TemplateTypes.ConditionState.UNKNOWN)missing.add("material.issuerRole");if(sealState==TemplateTypes.ConditionState.UNKNOWN){missing.add("seal.observed");missing.add("seal.location");missing.add("seal.materialBinding");}
            if(combined==TemplateTypes.ConditionState.SATISFIED)
                return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"COMMITMENT_AND_SEAL_SATISFIED","已提供本项目承诺书并按要求盖章。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)
                return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.PARTIAL,"COMMITMENT_OR_SEAL_NOT_SATISFIED","承诺书正文、必要承诺点或盖章条件未全部满足。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
            return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"COMMITMENT_EVIDENCE_PENDING","承诺书或盖章证据尚未完整。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    private static final class MaterialAlternative extends Base {
        MaterialAlternative(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);List<String> alternatives=strings(p,"alternatives");
            return alternatives.stream().map(type->need(spec,"alternative-"+safe(type),TemplateTypes.NeedKind.MATERIAL,Set.of("bodyPresent"),Map.of("materialType",type),true)).toList();
        }
        @Override public TemplateResult evaluate(CheckSpec spec, EvidenceView view) {
            Map<String,Object> p=parameters(spec);List<String> alternatives=strings(p,"alternatives");int required=integer(p,"atLeast",1);boolean requireBody=bool(p,"requireBody",true);
            List<EvidenceNeed> needs=evidenceNeeds(spec);List<ConditionResult> conditions=new ArrayList<>();List<String> refs=new ArrayList<>();
            for(int i=0;i<alternatives.size();i++){
                String type=alternatives.get(i);EvidenceNeed need=needs.get(i);
                List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,type)).toList();
                List<Observation.MaterialObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
                boolean unresolved=candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN)
                        ||matches.stream().anyMatch(CoreTemplateEvaluators::invalidSupported);
                List<String> branchRefs=sourceIds(matches);refs.addAll(branchRefs);
                boolean present=matches.stream().anyMatch(o->supported(o)&&(!requireBody||(o.bodyPresent()&&!o.titleOnly())));
                TemplateTypes.ConditionState state=present?TemplateTypes.ConditionState.SATISFIED:view.isClosed(need)&&!unresolved?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
                conditions.add(condition("alternative-"+(i+1),type,state,present?"ALTERNATIVE_PRESENT":"ALTERNATIVE_PENDING",present?"该替代材料已提供":"该替代材料尚未确认",branchRefs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("material:"+type):List.of()));
            }
            TemplateTypes.ConditionState combined=RestrictedLogic.atLeastN(conditions.stream().map(ConditionResult::state).toList(),required);
            List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();
            if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"ALTERNATIVE_SATISFIED","允许的替代证明路径已满足。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.NOT_LOCATED,"ALTERNATIVES_NOT_SATISFIED","允许的替代证明路径均未达到要求。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"ALTERNATIVE_EVIDENCE_PENDING","替代证明路径尚未核对完整。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    private static final class CertificateValidity extends Base {
        CertificateValidity(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);return List.of(need(spec,"certificate-validity",TemplateTypes.NeedKind.CERTIFICATE,Set.of("validFrom","validUntil"),Map.of("certificateType",string(p,"certificateType")),false));
        }
        @Override public TemplateResult evaluate(CheckSpec spec, EvidenceView view) {
            Map<String,Object> p=parameters(spec);String type=string(p,"certificateType");LocalDate date=LocalDate.parse(string(p,"comparisonDate"));boolean inclusive=bool(p,"inclusive",true);EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.CertificateObservation> candidates=view.observations(Observation.CertificateObservation.class).stream()
                    .filter(o->same(o.certificateType(),type)).toList();
            List<Observation.CertificateObservation> matches=candidates.stream()
                    .filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            List<String> refs=sourceIds(matches);
            if(matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING))return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"CERTIFICATE_CONFLICTING","证书有效期记录存在冲突。",List.of(condition("validity","证书有效期",TemplateTypes.ConditionState.UNKNOWN,"CERTIFICATE_CONFLICTING","证书记录冲突",refs,List.of("certificate.validity"))),refs,List.of("certificate.validity"),List.of(need.key()));
            List<Observation.CertificateObservation> supported=matches.stream().filter(CoreTemplateEvaluators::supported).toList();
            boolean valid=supported.stream().anyMatch(o->covers(o,date,inclusive));
            boolean hasComplete=supported.stream().anyMatch(o->o.longTerm()||o.validUntil()!=null);
            if(valid)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"CERTIFICATE_VALID","证书在比较日期内有效。",List.of(condition("validity","证书有效期",TemplateTypes.ConditionState.SATISFIED,"CERTIFICATE_VALID","有效期覆盖比较日期",refs,List.of())),refs,List.of(),List.of(need.key()));
            if(hasComplete)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"CERTIFICATE_EXPIRED","证书有效期未覆盖比较日期。",List.of(condition("validity","证书有效期",TemplateTypes.ConditionState.NOT_SATISFIED,"CERTIFICATE_EXPIRED","有效期未覆盖比较日期",refs,List.of())),refs,List.of(),List.of(need.key()));
            return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"CERTIFICATE_DATE_PENDING","尚未取得可比较的证书有效期。",List.of(condition("validity","证书有效期",TemplateTypes.ConditionState.UNKNOWN,"CERTIFICATE_DATE_PENDING","证书有效期字段待提取",refs,List.of("certificate.validUntil"))),refs,List.of("certificate.validUntil"),List.of(need.key()));
        }
    }

    private static final class PriceCompare extends Base {
        PriceCompare(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);Map<String,String> constraints=new java.util.LinkedHashMap<>();
            for(String key:List.of("field","currency","unit","taxBasis","lot","priceLayer"))if(p.containsKey(key)&&!String.valueOf(p.get(key)).isBlank())constraints.put(key,String.valueOf(p.get(key)));
            return List.of(need(spec,"price",TemplateTypes.NeedKind.PRICE,Set.of("amount","currency","unit"),constraints,false));
        }
        @Override public TemplateResult evaluate(CheckSpec spec, EvidenceView view) {
            Map<String,Object> p=parameters(spec);String field=string(p,"field"),currency=string(p,"currency"),unit=string(p,"unit");BigDecimal expected=decimal(p,"expected"),tolerance=decimal(p,"tolerance",BigDecimal.ZERO);String operator=string(p,"operator");EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.PriceObservation> candidates=view.observations(Observation.PriceObservation.class).stream().filter(o->priceScope(o,p,field,currency,unit)).toList();
            List<Observation.PriceObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            List<String> refs=sourceIds(matches);List<Observation.PriceObservation> supported=matches.stream().filter(CoreTemplateEvaluators::supported).toList();
            Set<BigDecimal> amounts=supported.stream().map(o->o.amount().stripTrailingZeros()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if(matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING)||amounts.size()>1)return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"PRICE_CONFLICTING","同一报价口径发现多个不同金额。",List.of(condition("price","报价比较",TemplateTypes.ConditionState.UNKNOWN,"PRICE_CONFLICTING","报价值冲突",refs,List.of("price.amount"))),refs,List.of("price.amount"),List.of(need.key()));
            if(amounts.isEmpty())return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"PRICE_PENDING","尚未取得同标段、同税口径和同单位的报价。",List.of(condition("price","报价比较",TemplateTypes.ConditionState.UNKNOWN,"PRICE_PENDING","报价字段待定位",refs,List.of("price.amount"))),refs,List.of("price.amount"),List.of(need.key()));
            BigDecimal actual=amounts.iterator().next();boolean met=compare(actual,expected,operator,tolerance);
            String summary="实际值 "+plain(actual)+" "+operator+" 要求值 "+plain(expected)+(met?"，满足要求。":"，未满足要求。");
            return result(met?TemplateTypes.ConclusionState.SATISFIED:TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,met?"PRICE_SATISFIED":"PRICE_NOT_SATISFIED",summary,List.of(condition("price","报价比较",met?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED,met?"PRICE_SATISFIED":"PRICE_NOT_SATISFIED",summary,refs,List.of())),refs,List.of(),List.of(need.key()));
        }
    }

    private static final class PersonnelRoster extends Base {
        PersonnelRoster(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());
            if(!base.valid())return base;
            Map<String,Object> p=parameters(spec);int min=integer(p,"minCount",1);Integer max=p.containsKey("maxCount")?integer(p,"maxCount",0):null;
            List<ValidationResult.ValidationError> errors=new ArrayList<>();
            if(min<1)errors.add(new ValidationResult.ValidationError("minCount","OUT_OF_RANGE","minCount 必须大于等于 1"));
            if(max!=null&&max<min)errors.add(new ValidationResult.ValidationError("maxCount","INVALID_RANGE","maxCount 必须大于等于 minCount"));
            return errors.isEmpty()?ValidationResult.ok():ValidationResult.errors(errors);
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);String role=string(p,"role");return List.of(
                    need(spec,"personnel",TemplateTypes.NeedKind.PERSONNEL,Set.of("personKey","roles","currentAppointment"),Map.of("role",role),false),
                    need(spec,"roster-scope",TemplateTypes.NeedKind.ROSTER_SCOPE,Set.of("subjectSetClosed"),Map.of("role",role),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec, EvidenceView view) {
            Map<String,Object> p=parameters(spec);String role=string(p,"role");int min=integer(p,"minCount",1);Integer max=p.containsKey("maxCount")?integer(p,"maxCount",0):null;boolean allowConcurrent=bool(p,"allowConcurrent",true);Set<String> disallowed=new HashSet<>(strings(p,"disallowedRoles"));List<EvidenceNeed> needs=evidenceNeeds(spec);boolean rosterClosed=view.isClosed(needs.get(1));
            List<Observation.PersonnelObservation> candidates=view.observations(Observation.PersonnelObservation.class).stream()
                    .filter(Observation.PersonnelObservation::currentAppointment)
                    .filter(o->o.roles().stream().anyMatch(actual->same(actual,role))).filter(o->personnelDimensions(o,p)).toList();
            List<Observation.PersonnelObservation> matches=candidates.stream().filter(CoreTemplateEvaluators::supported)
                    .filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            boolean selectorUnresolved=candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN||invalidSupported(o));
            boolean conclusionClosed=rosterClosed&&!selectorUnresolved;
            Map<String,Observation.PersonnelObservation> unique=new java.util.LinkedHashMap<>();for(var o:matches)unique.putIfAbsent(o.personKey(),o);
            List<String> refs=sourceIds(unique.values());int count=unique.size();boolean concurrencyViolation=!allowConcurrent&&unique.values().stream().anyMatch(o->concurrent(o,role,disallowed));
            List<ConditionResult> conditions=new ArrayList<>();TemplateTypes.ConditionState minState=count>=min?TemplateTypes.ConditionState.SATISFIED:conclusionClosed?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
            conditions.add(condition("minimum","岗位人数下限",minState,"PERSONNEL_MINIMUM","已识别当前任职 "+count+" 人，要求至少 "+min+" 人。",refs,minState==TemplateTypes.ConditionState.UNKNOWN?List.of("personnel.rosterScope"):List.of()));
            if(max!=null){TemplateTypes.ConditionState maxState=!conclusionClosed?TemplateTypes.ConditionState.UNKNOWN:count<=max?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED;conditions.add(condition("maximum","岗位人数上限",maxState,"PERSONNEL_MAXIMUM","已识别当前任职 "+count+" 人，要求最多 "+max+" 人。",refs,maxState==TemplateTypes.ConditionState.UNKNOWN?List.of("personnel.rosterScope"):List.of()));}
            if(!allowConcurrent)conditions.add(condition("concurrency","岗位兼任",concurrencyViolation?TemplateTypes.ConditionState.NOT_SATISFIED:conclusionClosed?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.UNKNOWN,"PERSONNEL_CONCURRENCY",concurrencyViolation?"发现不允许的岗位兼任。":"未发现已核对人员存在禁止兼任。",refs,!conclusionClosed?List.of("personnel.rosterScope"):List.of()));
            TemplateTypes.ConditionState combined=RestrictedLogic.allOf(conditions.stream().map(ConditionResult::state).toList());List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();
            if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"PERSONNEL_ROSTER_SATISFIED","岗位人数及适用的兼任限制满足要求。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"PERSONNEL_ROSTER_NOT_SATISFIED","岗位人数或兼任限制未满足要求。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"PERSONNEL_ROSTER_PENDING","当前人员证据尚不足以闭合岗位名单。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    /** A checklist is satisfied only when every explicitly named item has its own body evidence. */
    private static final class SectionChecklist extends Base {
        SectionChecklist(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);List<String> items=strings(p,"requiredItems");
            return items.stream().map(item->need(spec,"item-"+safe(item),TemplateTypes.NeedKind.MATERIAL,
                    Set.of("bodyPresent"),Map.of("materialType",item),true)).toList();
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);List<String> items=strings(p,"requiredItems");boolean requireBody=bool(p,"requireBody",true);
            List<EvidenceNeed> needs=evidenceNeeds(spec);List<ConditionResult> conditions=new ArrayList<>();List<String> refs=new ArrayList<>();boolean conflicting=false;
            for(int index=0;index<items.size();index++){
                String item=items.get(index);EvidenceNeed need=needs.get(index);
                List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream()
                        .filter(o->materialTypeMatches(o,item)).toList();
                List<Observation.MaterialObservation> matches=candidates.stream()
                        .filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
                List<String> itemRefs=sourceIds(matches);refs.addAll(itemRefs);
                boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING);
                boolean unresolved=conflict||candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN)
                        ||matches.stream().anyMatch(CoreTemplateEvaluators::invalidSupported);
                boolean present=matches.stream().anyMatch(o->supported(o)&&(!requireBody||(o.bodyPresent()&&!o.titleOnly())));
                TemplateTypes.ConditionState state=present?TemplateTypes.ConditionState.SATISFIED
                        :view.isClosed(need)&&!unresolved?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
                conflicting|=conflict;
                conditions.add(condition("item-"+(index+1),item,state,
                        conflict?"CHECKLIST_ITEM_CONFLICTING":present?"CHECKLIST_ITEM_PRESENT":state==TemplateTypes.ConditionState.NOT_SATISFIED?"CHECKLIST_ITEM_MISSING":"CHECKLIST_ITEM_PENDING",
                        conflict?"该组成项存在冲突版本":present?"已定位该组成项正文":state==TemplateTypes.ConditionState.NOT_SATISFIED?"闭合范围内未找到该组成项正文":"该组成项正文仍待定位",
                        itemRefs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("material:"+item):List.of()));
            }
            TemplateTypes.ConditionState combined=RestrictedLogic.allOf(conditions.stream().map(ConditionResult::state).toList());
            List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();
            if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,
                    "SECTION_CHECKLIST_SATISFIED","明示组成项均已逐项定位到正文。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,
                    refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"SECTION_CHECKLIST_NOT_SATISFIED",
                    "闭合核对范围内仍有明示组成项缺少正文。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            return result(TemplateTypes.ConclusionState.UNDETERMINED,conflicting?TemplateTypes.EvidenceState.CONFLICTING:refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,
                    conflicting?"SECTION_CHECKLIST_CONFLICTING":"SECTION_CHECKLIST_PENDING","组成项正文证据尚未全部闭合。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    /** Compares two source-backed fields for every person in a closed role roster. */
    private static final class EntityFieldMatch extends Base {
        EntityFieldMatch(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;
            Map<String,Object> p=parameters(spec);String left=string(p,"leftField"),right=string(p,"rightField");List<ValidationResult.ValidationError> errors=new ArrayList<>();
            if(!supportedEntityField(left))errors.add(new ValidationResult.ValidationError("leftField","FIELD_NOT_SUPPORTED","不支持的左侧字段"));
            if(!supportedEntityField(right))errors.add(new ValidationResult.ValidationError("rightField","FIELD_NOT_SUPPORTED","不支持的右侧字段"));
            if(left.equals(right))errors.add(new ValidationResult.ValidationError("rightField","IDENTICAL_FIELDS","左右字段不能相同"));
            if((certificateField(left)||certificateField(right))&&string(p,"certificateType").isBlank())
                errors.add(new ValidationResult.ValidationError("certificateType","REQUIRED","比较证书字段时必须指定证书种类"));
            return errors.isEmpty()?ValidationResult.ok():ValidationResult.errors(errors);
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);String role=string(p,"role"),left=string(p,"leftField"),right=string(p,"rightField");List<EvidenceNeed> needs=new ArrayList<>();
            needs.add(need(spec,"entity-fields",TemplateTypes.NeedKind.PERSONNEL,Set.of(left,right),Map.of("role",role),false));
            if(certificateField(left)||certificateField(right))needs.add(need(spec,"certificate-fields",TemplateTypes.NeedKind.CERTIFICATE,
                    Set.of(left,right),Map.of("certificateType",string(p,"certificateType")),false));
            needs.add(need(spec,"entity-scope",TemplateTypes.NeedKind.ROSTER_SCOPE,Set.of("subjectSetClosed"),Map.of("role",role),true));
            return List.copyOf(needs);
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String role=string(p,"role"),left=string(p,"leftField"),right=string(p,"rightField"),certificateType=string(p,"certificateType");boolean caseSensitive=bool(p,"caseSensitive",false);
            List<EvidenceNeed> needs=evidenceNeeds(spec);EvidenceNeed personnelNeed=needs.get(0),rosterNeed=needs.get(needs.size()-1);EvidenceNeed certificateNeed=needs.size()==3?needs.get(1):null;
            List<Observation.PersonnelObservation> candidates=view.observations(Observation.PersonnelObservation.class).stream()
                    .filter(Observation.PersonnelObservation::currentAppointment).filter(o->role.isBlank()||o.roles().stream().anyMatch(value->same(value,role))).toList();
            List<Observation.PersonnelObservation> matches=candidates.stream().filter(CoreTemplateEvaluators::supported)
                    .filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            boolean selectorUnresolved=candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN||invalidSupported(o));
            Map<String,Observation.PersonnelObservation> people=new java.util.LinkedHashMap<>();for(var person:matches)people.putIfAbsent(person.personKey(),person);
            List<ConditionResult> conditions=new ArrayList<>();List<String> refs=new ArrayList<>();boolean conflict=false;
            for(Observation.PersonnelObservation person:people.values()){
                List<Observation.CertificateObservation> certificates=view.observations(Observation.CertificateObservation.class).stream()
                        .filter(Observation.CertificateObservation::currentScope).filter(o->o.holderKey().equals(person.personKey()))
                        .filter(o->certificateType.isBlank()||same(o.certificateType(),certificateType)).toList();
                FieldResolution leftValue=resolveEntityField(person,certificates,left);FieldResolution rightValue=resolveEntityField(person,certificates,right);
                List<Observation> evidence=new ArrayList<>();evidence.add(person);evidence.addAll(certificates);List<String> itemRefs=sourceIds(evidence);refs.addAll(itemRefs);
                conflict|=leftValue.conflicting()||rightValue.conflicting();boolean relevantClosed=fieldNeedClosed(left,personnelNeed,certificateNeed,view)&&fieldNeedClosed(right,personnelNeed,certificateNeed,view);
                TemplateTypes.ConditionState state;
                if(leftValue.conflicting()||rightValue.conflicting())state=TemplateTypes.ConditionState.UNKNOWN;
                else if(leftValue.values().size()==1&&rightValue.values().size()==1){String a=leftValue.values().iterator().next(),b=rightValue.values().iterator().next();state=(caseSensitive?a.equals(b):same(a,b))?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED;}
                else state=relevantClosed?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
                conditions.add(condition("entity-"+safe(person.personKey()),person.personName().isBlank()?person.personKey():person.personName(),state,
                        state==TemplateTypes.ConditionState.SATISFIED?"ENTITY_FIELDS_MATCH":state==TemplateTypes.ConditionState.NOT_SATISFIED?"ENTITY_FIELDS_DIFFER":"ENTITY_FIELDS_PENDING",
                        state==TemplateTypes.ConditionState.SATISFIED?"同一对象的两个字段一致":state==TemplateTypes.ConditionState.NOT_SATISFIED?"同一对象的两个字段不一致或字段缺失":"字段值尚未形成唯一可比记录",
                        itemRefs,state==TemplateTypes.ConditionState.UNKNOWN?List.of(left,right):List.of()));
            }
            boolean rosterClosed=view.isClosed(rosterNeed)&&!selectorUnresolved;TemplateTypes.ConditionState combined;
            if(conditions.stream().anyMatch(c->c.state()==TemplateTypes.ConditionState.NOT_SATISFIED))combined=TemplateTypes.ConditionState.NOT_SATISFIED;
            else if(conditions.isEmpty())combined=rosterClosed?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
            else combined=RestrictedLogic.forEachSubject(conditions.stream().map(ConditionResult::state).toList(),rosterClosed);
            if(conditions.isEmpty())conditions.add(condition("subject","适用对象",combined,"ENTITY_SUBJECT_EMPTY",rosterClosed?"闭合范围内未找到适用对象":"适用对象集合尚未闭合",List.of(),combined==TemplateTypes.ConditionState.UNKNOWN?List.of("entity.scope"):List.of()));
            List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();
            if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"ENTITY_FIELD_MATCH_SATISFIED","适用对象的字段值均一致。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"ENTITY_FIELD_MATCH_NOT_SATISFIED","发现同一对象字段不一致或闭合范围内缺少必需字段。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            return result(TemplateTypes.ConclusionState.UNDETERMINED,conflict?TemplateTypes.EvidenceState.CONFLICTING:refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"ENTITY_FIELD_MATCH_PENDING","字段比较所需的对象范围或字段值尚未闭合。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    /** Joins current appointment, certificate identity, level, employer and validity by personKey. */
    private static final class PersonnelCredential extends Base {
        PersonnelCredential(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);String role=string(p,"role"),type=string(p,"certificateType");Set<String> personnelFields=new LinkedHashSet<>(Set.of("personKey","roles","currentAppointment"));
            if(!string(p,"requiredLevel").isBlank())personnelFields.add("attributes."+string(p,"levelAttribute"));
            if(bool(p,"requireEmployerMatch",true))personnelFields.add("attributes."+string(p,"registeredEmployerAttribute"));
            return List.of(
                    need(spec,"credential-person",TemplateTypes.NeedKind.PERSONNEL,personnelFields,Map.of("role",role),false),
                    need(spec,"credential-certificate",TemplateTypes.NeedKind.CERTIFICATE,Set.of("holderKey","certificateNumber","validFrom","validUntil"),Map.of("certificateType",type),false),
                    need(spec,"credential-scope",TemplateTypes.NeedKind.ROSTER_SCOPE,Set.of("subjectSetClosed"),Map.of("role",role),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String role=string(p,"role"),type=string(p,"certificateType"),level=string(p,"requiredLevel"),levelAttribute=string(p,"levelAttribute"),employer=string(p,"employerKey"),registeredEmployerAttribute=string(p,"registeredEmployerAttribute");boolean requireEmployer=bool(p,"requireEmployerMatch",true),inclusive=bool(p,"inclusive",true);LocalDate date=string(p,"comparisonDate").isBlank()?null:LocalDate.parse(string(p,"comparisonDate"));
            List<EvidenceNeed> needs=evidenceNeeds(spec);EvidenceNeed personnelNeed=needs.get(0),certificateNeed=needs.get(1),rosterNeed=needs.get(2);
            List<Observation.PersonnelObservation> candidates=view.observations(Observation.PersonnelObservation.class).stream().filter(Observation.PersonnelObservation::currentAppointment)
                    .filter(o->o.roles().stream().anyMatch(value->same(value,role))).toList();
            List<Observation.PersonnelObservation> matches=candidates.stream().filter(CoreTemplateEvaluators::supported).filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            boolean selectorUnresolved=candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN||invalidSupported(o));Map<String,Observation.PersonnelObservation> people=new java.util.LinkedHashMap<>();for(var person:matches)people.putIfAbsent(person.personKey(),person);
            List<ConditionResult> conditions=new ArrayList<>();List<String> refs=new ArrayList<>();boolean conflict=false;
            for(Observation.PersonnelObservation person:people.values()){
                List<Observation.CertificateObservation> certCandidates=view.observations(Observation.CertificateObservation.class).stream().filter(Observation.CertificateObservation::currentScope)
                        .filter(o->o.holderKey().equals(person.personKey())).filter(o->same(o.certificateType(),type)).toList();
                List<Observation.CertificateObservation> certs=certCandidates.stream().filter(CoreTemplateEvaluators::supported).toList();boolean certConflict=certCandidates.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o));conflict|=certConflict;
                List<Observation> evidence=new ArrayList<>();evidence.add(person);evidence.addAll(certCandidates);List<String> itemRefs=sourceIds(evidence);refs.addAll(itemRefs);
                List<TemplateTypes.ConditionState> states=new ArrayList<>();
                TemplateTypes.ConditionState certificateState=certConflict?TemplateTypes.ConditionState.UNKNOWN:!certs.isEmpty()?TemplateTypes.ConditionState.SATISFIED:view.isClosed(certificateNeed)?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;states.add(certificateState);
                if(!level.isBlank()){String actual=person.attributes().getOrDefault(levelAttribute,"");states.add(actual.isBlank()?view.isClosed(personnelNeed)?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN:same(actual,level)?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED);}
                if(requireEmployer){String expected=employer.isBlank()?person.employerKey():employer,actual=person.attributes().getOrDefault(registeredEmployerAttribute,"");states.add(expected.isBlank()||actual.isBlank()?view.isClosed(personnelNeed)?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN:same(actual,expected)?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED);}
                if(date!=null){boolean complete=certs.stream().anyMatch(o->o.longTerm()||o.validUntil()!=null),valid=certs.stream().anyMatch(o->covers(o,date,inclusive));states.add(valid?TemplateTypes.ConditionState.SATISFIED:complete?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN);}
                TemplateTypes.ConditionState state=RestrictedLogic.allOf(states);
                conditions.add(condition("credential-"+safe(person.personKey()),person.personName().isBlank()?person.personKey():person.personName(),state,
                        state==TemplateTypes.ConditionState.SATISFIED?"PERSONNEL_CREDENTIAL_SATISFIED":state==TemplateTypes.ConditionState.NOT_SATISFIED?"PERSONNEL_CREDENTIAL_NOT_SATISFIED":"PERSONNEL_CREDENTIAL_PENDING",
                        state==TemplateTypes.ConditionState.SATISFIED?"任职、证书及适用的单位和有效期条件均满足":state==TemplateTypes.ConditionState.NOT_SATISFIED?"证书、等级、单位或有效期条件未满足":"人员证书关系仍有字段待核",
                        itemRefs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("personnel.credential"):List.of()));
            }
            boolean rosterClosed=view.isClosed(rosterNeed)&&!selectorUnresolved;TemplateTypes.ConditionState combined;
            if(conditions.stream().anyMatch(c->c.state()==TemplateTypes.ConditionState.NOT_SATISFIED))combined=TemplateTypes.ConditionState.NOT_SATISFIED;
            else if(conditions.isEmpty())combined=rosterClosed?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
            else combined=RestrictedLogic.forEachSubject(conditions.stream().map(ConditionResult::state).toList(),rosterClosed);
            if(conditions.isEmpty())conditions.add(condition("credential-subject","适用人员",combined,"PERSONNEL_CREDENTIAL_SUBJECT_EMPTY",rosterClosed?"闭合名单内未找到适用人员":"适用人员名单尚未闭合",List.of(),combined==TemplateTypes.ConditionState.UNKNOWN?List.of("personnel.rosterScope"):List.of()));
            List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();
            if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"PERSONNEL_CREDENTIAL_SATISFIED","适用人员的任职、证书、单位及有效期条件满足要求。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"PERSONNEL_CREDENTIAL_NOT_SATISFIED","至少一名适用人员的证书条件未满足，或闭合名单内未找到适用人员。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            return result(TemplateTypes.ConclusionState.UNDETERMINED,conflict?TemplateTypes.EvidenceState.CONFLICTING:refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"PERSONNEL_CREDENTIAL_PENDING","人员、证书、单位或名单范围尚未闭合。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    /** Checks explicit consortium agreement points; it never infers organization duties from personnel alone. */
    private static final class ConsortiumResponsibility extends Base {
        ConsortiumResponsibility(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;int min=integer(parameters(spec),"minMembers",2);
            return min<2?ValidationResult.errors(List.of(new ValidationResult.ValidationError("minMembers","OUT_OF_RANGE","联合体成员数下限必须大于等于 2"))):ValidationResult.ok();
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);return List.of(need(spec,"consortium-agreement",TemplateTypes.NeedKind.MATERIAL,
                    Set.of("bodyPresent","commitmentPoints"),Map.of("materialType",string(p,"materialType")),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String type=string(p,"materialType"),lead=string(p,"leadRole"),prefix=string(p,"memberPrefix");List<String> roles=strings(p,"requiredMemberRoles"),responsibilities=strings(p,"requiredResponsibilities");int min=integer(p,"minMembers",2);EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,type)).toList();
            List<Observation.MaterialObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();List<Observation.MaterialObservation> bodies=matches.stream().filter(CoreTemplateEvaluators::supported).filter(o->o.bodyPresent()&&!o.titleOnly()).toList();List<String> refs=sourceIds(matches);
            boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING)||bodies.stream().map(Observation.MaterialObservation::commitmentPoints).distinct().count()>1;
            boolean unresolved=conflict||candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN)||matches.stream().anyMatch(CoreTemplateEvaluators::invalidSupported);boolean closed=view.isClosed(need)&&!unresolved;
            Set<String> points=bodies.isEmpty()?Set.of():bodies.get(0).commitmentPoints();List<ConditionResult> conditions=new ArrayList<>();
            TemplateTypes.ConditionState bodyState=!bodies.isEmpty()?TemplateTypes.ConditionState.SATISFIED:closed?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;conditions.add(condition("agreement","联合体协议正文",bodyState,"CONSORTIUM_AGREEMENT",bodyState==TemplateTypes.ConditionState.SATISFIED?"已定位联合体协议正文":"联合体协议正文尚未可靠定位",refs,bodyState==TemplateTypes.ConditionState.UNKNOWN?List.of("consortium.agreement"):List.of()));
            List<String> required=new ArrayList<>();required.addAll(roles);required.addAll(responsibilities);if(!lead.isBlank())required.add(lead);
            for(int i=0;i<required.size();i++){String point=required.get(i);boolean found=points.stream().anyMatch(value->same(value,point));TemplateTypes.ConditionState state=found?TemplateTypes.ConditionState.SATISFIED:closed?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;conditions.add(condition("point-"+(i+1),point,state,"CONSORTIUM_POINT",found?"协议已明确该角色或责任":"协议中尚未确认该角色或责任",refs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("consortium.point:"+point):List.of()));}
            Set<String> members=points.stream().filter(value->value.startsWith(prefix)).map(value->value.substring(prefix.length()).trim()).filter(value->!value.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));TemplateTypes.ConditionState memberState=members.size()>=min?TemplateTypes.ConditionState.SATISFIED:closed?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;conditions.add(condition("members","联合体成员数量",memberState,"CONSORTIUM_MEMBER_COUNT","已识别明确成员 "+members.size()+" 个，要求至少 "+min+" 个。",refs,memberState==TemplateTypes.ConditionState.UNKNOWN?List.of("consortium.members"):List.of()));
            TemplateTypes.ConditionState combined=RestrictedLogic.allOf(conditions.stream().map(ConditionResult::state).toList());List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();
            if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"CONSORTIUM_RESPONSIBILITY_SATISFIED","联合体成员、角色及责任分工均有明确协议证据。",conditions,refs,List.of(),List.of(need.key()));
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"CONSORTIUM_RESPONSIBILITY_NOT_SATISFIED","闭合范围内的联合体协议未完整证明成员、角色或责任分工。",conditions,refs,List.of(),List.of(need.key()));
            return result(TemplateTypes.ConclusionState.UNDETERMINED,conflict?TemplateTypes.EvidenceState.CONFLICTING:refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"CONSORTIUM_RESPONSIBILITY_PENDING","联合体协议的成员或分工证据尚未闭合。",conditions,refs,missing,List.of(need.key()));
        }
    }

    /** Reconciles one explicit total with a non-empty, closed list of component price fields. */
    private static final class PriceReconcile extends Base {
        PriceReconcile(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;Map<String,Object> p=parameters(spec);List<String> fields=strings(p,"componentFields");List<ValidationResult.ValidationError> errors=new ArrayList<>();
            if(fields.stream().map(CoreTemplateEvaluators::normalize).distinct().count()!=fields.size())errors.add(new ValidationResult.ValidationError("componentFields","DUPLICATE_FIELD","分项字段不能重复"));
            if(fields.stream().anyMatch(field->same(field,string(p,"totalField"))))errors.add(new ValidationResult.ValidationError("componentFields","TOTAL_INCLUDED_AS_COMPONENT","总计字段不能同时作为分项"));
            BigDecimal tolerance=decimal(p,"tolerance",BigDecimal.ZERO);if(tolerance.signum()<0)errors.add(new ValidationResult.ValidationError("tolerance","OUT_OF_RANGE","容差不能为负数"));
            return errors.isEmpty()?ValidationResult.ok():ValidationResult.errors(errors);
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);List<String> fields=new ArrayList<>();fields.add(string(p,"totalField"));fields.addAll(strings(p,"componentFields"));List<EvidenceNeed> needs=new ArrayList<>();
            for(String field:fields){Map<String,String> constraints=new java.util.LinkedHashMap<>();for(String key:List.of("currency","unit","taxBasis","lot","priceLayer"))if(!string(p,key).isBlank())constraints.put(key,string(p,key));constraints.put("field",field);needs.add(need(spec,"price-"+safe(field),TemplateTypes.NeedKind.PRICE,Set.of("amount","currency","unit"),constraints,true));}
            return List.copyOf(needs);
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String totalField=string(p,"totalField"),currency=string(p,"currency"),unit=string(p,"unit");List<String> componentFields=strings(p,"componentFields");List<String> fields=new ArrayList<>();fields.add(totalField);fields.addAll(componentFields);BigDecimal tolerance=decimal(p,"tolerance",BigDecimal.ZERO);List<EvidenceNeed> needs=evidenceNeeds(spec);List<ConditionResult> conditions=new ArrayList<>();List<String> refs=new ArrayList<>();Map<String,BigDecimal> values=new java.util.LinkedHashMap<>();boolean conflict=false;
            for(int index=0;index<fields.size();index++){String field=fields.get(index);EvidenceNeed need=needs.get(index);List<Observation.PriceObservation> candidates=view.observations(Observation.PriceObservation.class).stream().filter(o->priceScope(o,p,field,currency,unit)).toList();List<Observation.PriceObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();List<String> itemRefs=sourceIds(matches);refs.addAll(itemRefs);Set<BigDecimal> amounts=matches.stream().filter(CoreTemplateEvaluators::supported).map(o->o.amount().stripTrailingZeros()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));boolean itemConflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o))||amounts.size()>1||candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN);conflict|=itemConflict;TemplateTypes.ConditionState state=itemConflict?TemplateTypes.ConditionState.UNKNOWN:amounts.size()==1?TemplateTypes.ConditionState.SATISFIED:view.isClosed(need)?TemplateTypes.ConditionState.NOT_SATISFIED:TemplateTypes.ConditionState.UNKNOWN;if(amounts.size()==1&&!itemConflict)values.put(field,amounts.iterator().next());conditions.add(condition("field-"+(index+1),field,state,itemConflict?"PRICE_FIELD_CONFLICTING":state==TemplateTypes.ConditionState.SATISFIED?"PRICE_FIELD_PRESENT":state==TemplateTypes.ConditionState.NOT_SATISFIED?"PRICE_FIELD_MISSING":"PRICE_FIELD_PENDING",state==TemplateTypes.ConditionState.SATISFIED?"已取得唯一同口径金额":state==TemplateTypes.ConditionState.NOT_SATISFIED?"闭合范围内未取得该金额":"金额尚未形成唯一可比值",itemRefs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("price:"+field):List.of()));}
            boolean allValues=values.size()==fields.size();TemplateTypes.ConditionState reconcileState;if(allValues){BigDecimal sum=componentFields.stream().map(values::get).reduce(BigDecimal.ZERO,BigDecimal::add);reconcileState=values.get(totalField).subtract(sum).abs().compareTo(tolerance)<=0?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED;}else reconcileState=TemplateTypes.ConditionState.UNKNOWN;conditions.add(condition("reconcile","报价合计",reconcileState,"PRICE_RECONCILIATION",reconcileState==TemplateTypes.ConditionState.SATISFIED?"总计与分项合计在容差内一致":reconcileState==TemplateTypes.ConditionState.NOT_SATISFIED?"总计与分项合计不一致":"分项或总计金额尚未齐全",refs,reconcileState==TemplateTypes.ConditionState.UNKNOWN?List.of("price.reconciliationInputs"):List.of()));
            TemplateTypes.ConditionState combined=RestrictedLogic.allOf(conditions.stream().map(ConditionResult::state).toList());List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();
            if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"PRICE_RECONCILE_SATISFIED","同一标段和口径下，总计与明示分项合计一致。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"PRICE_RECONCILE_NOT_SATISFIED","总计与分项合计不一致，或闭合范围内缺少必需金额。",conditions,refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
            return result(TemplateTypes.ConclusionState.UNDETERMINED,conflict?TemplateTypes.EvidenceState.CONFLICTING:refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"PRICE_RECONCILE_PENDING","报价勾稽所需金额尚未形成唯一完整口径。",conditions,refs,missing,needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    /** Compares a duration only after its unit, start event and calendar basis are aligned. */
    private static final class DurationCompare extends Base {
        DurationCompare(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;
            BigDecimal tolerance=decimal(parameters(spec),"tolerance",BigDecimal.ZERO);
            return tolerance.signum()<0?ValidationResult.errors(List.of(new ValidationResult.ValidationError(
                    "tolerance","OUT_OF_RANGE","工期比较容差不能为负数"))):ValidationResult.ok();
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);Map<String,String> constraints=stringConstraints(p,List.of("field","unit","startEvent","calendarBasis","lot"));
            return List.of(need(spec,"duration",TemplateTypes.NeedKind.PRICE,
                    Set.of("amount","unit","startEvent","calendarBasis"),constraints,false));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String field=string(p,"field"),unit=string(p,"unit"),operator=string(p,"operator");BigDecimal expected=decimal(p,"expected"),tolerance=decimal(p,"tolerance",BigDecimal.ZERO);EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.PriceObservation> candidates=view.observations(Observation.PriceObservation.class).stream()
                    .filter(o->o.currentScope()&&same(o.field(),field)&&o.currency().equalsIgnoreCase("DURATION")&&same(o.unit(),unit))
                    .filter(o->string(p,"startEvent").isBlank()||same(o.priceLayer(),string(p,"startEvent")))
                    .filter(o->string(p,"calendarBasis").isBlank()||same(o.taxBasis(),string(p,"calendarBasis")))
                    .filter(o->string(p,"lot").isBlank()||same(o.lot(),string(p,"lot"))).toList();
            List<Observation.PriceObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();List<String> refs=sourceIds(matches);
            Set<BigDecimal> amounts=matches.stream().filter(CoreTemplateEvaluators::supported).map(o->o.amount().stripTrailingZeros()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o))||amounts.size()>1||candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN);
            if(conflict)return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"DURATION_CONFLICTING","同一工期口径存在多个不同值。",List.of(condition("duration","期限比较",TemplateTypes.ConditionState.UNKNOWN,"DURATION_CONFLICTING","期限数值或口径存在冲突",refs,List.of("duration.value"))),refs,List.of("duration.value"),List.of(need.key()));
            if(!view.isClosed(need))return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"DURATION_SCOPE_PENDING","已取得期限候选值，但同口径证据范围尚未闭合，不能形成最终比较结论。",List.of(condition("duration","期限比较",TemplateTypes.ConditionState.UNKNOWN,"DURATION_SCOPE_PENDING","仍可能存在尚未处理的同口径期限候选值",refs,List.of("duration.scope"))),refs,List.of("duration.scope"),List.of(need.key()));
            if(amounts.isEmpty())return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"DURATION_PENDING","尚未取得同单位、同起算事件和同日历口径的期限值。",List.of(condition("duration","期限比较",TemplateTypes.ConditionState.UNKNOWN,"DURATION_PENDING","期限值或口径待定位",refs,List.of("duration.value","duration.basis"))),refs,List.of("duration.value","duration.basis"),List.of(need.key()));
            BigDecimal actual=amounts.iterator().next();boolean met=compare(actual,expected,operator,tolerance);String summary="实际期限 "+plain(actual)+unit+" "+operator+" 要求期限 "+plain(expected)+unit+(met?"，满足要求。":"，未满足要求。");
            return result(met?TemplateTypes.ConclusionState.SATISFIED:TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,met?"DURATION_SATISFIED":"DURATION_NOT_SATISFIED",summary,List.of(condition("duration","期限比较",met?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED,met?"DURATION_SATISFIED":"DURATION_NOT_SATISFIED",summary,refs,List.of())),refs,List.of(),List.of(need.key()));
        }
    }

    /** Compares source-backed values within the same logical party across independent occurrences. */
    private static final class CrossDocumentConsistency extends Base {
        CrossDocumentConsistency(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;Map<String,Object> p=parameters(spec);int minimum=integer(p,"minimumOccurrences",2);
            if(!CONSISTENCY_FIELDS.contains(string(p,"field")))return ValidationResult.errors(List.of(new ValidationResult.ValidationError("field","UNSUPPORTED_CANONICAL_FIELD","该字段尚无安全的跨文档规范化规则")));
            return minimum<2?ValidationResult.errors(List.of(new ValidationResult.ValidationError("minimumOccurrences","OUT_OF_RANGE","一致性比较至少需要两个独立、可追溯的来源记录"))):ValidationResult.ok();
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);return List.of(need(spec,"field-values",TemplateTypes.NeedKind.MATERIAL,
                    Set.of("value","documentCoverage"),Map.of("field",string(p,"field"),"minimumOccurrences",String.valueOf(integer(p,"minimumOccurrences",2))),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String field=string(p,"field"),type="FIELD:"+field;int minimum=integer(p,"minimumOccurrences",2);boolean caseSensitive=bool(p,"caseSensitive",false);EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,type)).toList();
            List<Observation.MaterialObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();List<String> refs=sourceIds(matches);
            Map<String,Map<String,Set<String>>> byPartyAndScope=new java.util.LinkedHashMap<>();boolean conflict=candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN);
            for(Observation.MaterialObservation observation:matches.stream().filter(CoreTemplateEvaluators::supported).toList()){
                List<String> values=pointValues(observation.commitmentPoints(),"VALUE:");
                if(values.size()!=1){conflict=true;continue;}
                String value=canonicalConsistencyValue(field,values.get(0),caseSensitive);
                if(value.isBlank()){conflict=true;continue;}
                List<String> scopes=pointValues(observation.commitmentPoints(),"SOURCE_SCOPE:");
                if(scopes.isEmpty())scopes=observation.sources().stream().filter(CoreTemplateEvaluators::validSource).map(SourceRef::documentId).distinct().toList();
                List<String> parties=pointValues(observation.commitmentPoints(),"PARTY_SCOPE:");
                if(parties.isEmpty())parties=List.of(observation.subjectKey().isBlank()?"SUBJECT":observation.subjectKey());
                for(String party:parties)for(String scope:scopes)byPartyAndScope
                        .computeIfAbsent(party,ignored->new java.util.LinkedHashMap<>())
                        .computeIfAbsent(scope,ignored->new LinkedHashSet<>()).add(value);
            }
            List<Map<String,Set<String>>> covered=byPartyAndScope.values().stream().filter(scopes->scopes.size()>=minimum).toList();
            int occurrenceCount=byPartyAndScope.values().stream().mapToInt(Map::size).sum();
            if(covered.isEmpty())return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"CROSS_DOCUMENT_COVERAGE_PENDING","可比来源记录不足，不能据此判断一致或不一致。",List.of(condition("coverage","可比来源覆盖",TemplateTypes.ConditionState.UNKNOWN,"CROSS_DOCUMENT_COVERAGE_PENDING","已取得 "+occurrenceCount+" 个可追溯来源，至少需要同一主体的 "+minimum+" 个独立来源",refs,List.of("field.documentCoverage"))),refs,List.of("field.documentCoverage"),List.of(need.key()));
            if(covered.stream().flatMap(scopes->scopes.values().stream()).anyMatch(values->values.size()>1))conflict=true;
            if(matches.stream().filter(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o)).anyMatch(o->{List<String> parties=pointValues(o.commitmentPoints(),"PARTY_SCOPE:");return parties.isEmpty()||parties.stream().anyMatch(party->byPartyAndScope.getOrDefault(party,Map.of()).size()>=minimum);}))conflict=true;
            if(conflict)return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"CROSS_DOCUMENT_VALUE_CONFLICTING","字段记录存在无法归并的冲突值。",List.of(condition("comparison","跨文档字段",TemplateTypes.ConditionState.UNKNOWN,"CROSS_DOCUMENT_VALUE_CONFLICTING","同一可比来源或主体的字段值冲突",refs,List.of("field.value"))),refs,List.of("field.value"),List.of(need.key()));
            boolean consistent=covered.stream().allMatch(scopes->scopes.values().stream().flatMap(Collection::stream).distinct().count()==1);
            if(consistent&&!view.isClosed(need))return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.PARTIAL,"CROSS_DOCUMENT_SCOPE_PENDING","当前可比记录值一致，但文档范围尚未闭合，不能宣称全文一致。",List.of(condition("coverage","可比文档覆盖",TemplateTypes.ConditionState.UNKNOWN,"CROSS_DOCUMENT_SCOPE_PENDING","仍有文档范围待扫描",refs,List.of("field.documentCoverage"))),refs,List.of("field.documentCoverage"),List.of(need.key()));
            String summary=consistent?"已纳入比较且完成范围闭合的 "+occurrenceCount+" 个可追溯来源中，同一主体的该字段值一致。":"同一主体的可比来源中发现该字段存在差异，请核对原文。";
            return result(consistent?TemplateTypes.ConclusionState.SATISFIED:TemplateTypes.ConclusionState.SUSPECTED_RISK,TemplateTypes.EvidenceState.SUFFICIENT,consistent?"CROSS_DOCUMENT_CONSISTENT":"CROSS_DOCUMENT_DIFFERENCE",summary,List.of(condition("comparison","跨文档字段",consistent?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED,consistent?"CROSS_DOCUMENT_CONSISTENT":"CROSS_DOCUMENT_DIFFERENCE",summary,refs,List.of())),refs,List.of(),List.of(need.key()));
        }
    }

    /** Requires explicit field-state observations; absence of an observation is never treated as an empty field. */
    private static final class FormFieldCompleteness extends Base {
        FormFieldCompleteness(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;List<String> fields=strings(parameters(spec),"requiredFields");
            return fields.stream().map(CoreTemplateEvaluators::normalize).distinct().count()!=fields.size()?ValidationResult.errors(List.of(new ValidationResult.ValidationError("requiredFields","DUPLICATE_FIELD","必填字段不能重复"))):ValidationResult.ok();
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);Map<String,String> constraints=stringConstraints(p,List.of("formType","expectedSubject"));constraints.put("requiredFields",String.join("|",strings(p,"requiredFields")));
            return List.of(need(spec,"form",TemplateTypes.NeedKind.MATERIAL,Set.of("bodyPresent","requiredFields"),constraints,false));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String formType=string(p,"formType");List<String> fields=strings(p,"requiredFields");EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,formType)).toList();
            List<Observation.MaterialObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();
            List<String> allRefs=sourceIds(matches);boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o))||candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN);
            Map<String,List<Observation.MaterialObservation>> instances=new java.util.LinkedHashMap<>();
            for(Observation.MaterialObservation observation:matches.stream().filter(CoreTemplateEvaluators::supported).filter(o->o.bodyPresent()&&!o.titleOnly()).toList())
                instances.computeIfAbsent(formInstanceKey(observation),ignored->new ArrayList<>()).add(observation);
            List<FormInstanceResult> completed=new ArrayList<>();
            for(List<Observation.MaterialObservation> instance:instances.values()){
                Set<String> points=instance.stream().flatMap(o->o.commitmentPoints().stream()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                List<String> refs=sourceIds(instance);List<ConditionResult> conditions=new ArrayList<>();boolean instanceConflict=false;
                for(int index=0;index<fields.size();index++){
                    String field=fields.get(index);boolean filled=hasPoint(points,"FIELD_FILLED:"+field),blank=hasPoint(points,"FIELD_BLANK:"+field)||hasPoint(points,"FIELD_PLACEHOLDER:"+field)||hasPoint(points,"FIELD_WRONG_SUBJECT:"+field);instanceConflict|=filled&&blank;
                    TemplateTypes.ConditionState state=filled&&blank?TemplateTypes.ConditionState.UNKNOWN:blank?TemplateTypes.ConditionState.NOT_SATISFIED:filled?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.UNKNOWN;
                    conditions.add(condition("field-"+(index+1),field,state,filled&&blank?"FORM_FIELD_CONFLICTING":blank?"FORM_FIELD_INCOMPLETE":filled?"FORM_FIELD_FILLED":"FORM_FIELD_PENDING",filled&&blank?"同一表单实例内字段状态冲突":blank?"字段被明确识别为空白、占位符或对象错误":filled?"字段已填写":"尚未取得该字段填写状态",refs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("form.field:"+field):List.of()));
                }
                TemplateTypes.ConditionState state=instanceConflict?TemplateTypes.ConditionState.UNKNOWN:RestrictedLogic.allOf(conditions.stream().map(ConditionResult::state).toList());
                if(state!=TemplateTypes.ConditionState.UNKNOWN)completed.add(new FormInstanceResult(state,conditions,refs));
                conflict|=instanceConflict;
            }
            Set<TemplateTypes.ConditionState> outcomes=completed.stream().map(FormInstanceResult::state).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if(outcomes.size()>1)conflict=true;
            if(!conflict&&outcomes.size()==1){
                TemplateTypes.ConditionState outcome=outcomes.iterator().next();FormInstanceResult chosen=completed.stream().filter(value->value.state()==outcome).findFirst().orElseThrow();
                if(outcome==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"FORM_FIELDS_COMPLETE","同一表单实例内的必填字段均有明确填写证据。",chosen.conditions(),chosen.refs(),List.of(),List.of(need.key()));
                return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"FORM_FIELD_INCOMPLETE","同一表单实例存在明确的空白、占位符或对象错填字段。",chosen.conditions(),chosen.refs(),List.of(),List.of(need.key()));
            }
            final boolean hasConflict=conflict;
            List<ConditionResult> pending=fields.stream().map(field->condition("field-"+(fields.indexOf(field)+1),field,TemplateTypes.ConditionState.UNKNOWN,hasConflict?"FORM_FIELD_CONFLICTING":"FORM_FIELD_PENDING",hasConflict?"不同表单实例或同一实例内的字段状态冲突":"尚未在同一表单实例中取得全部字段状态",allRefs,List.of("form.field:"+field))).toList();
            return result(TemplateTypes.ConclusionState.UNDETERMINED,conflict?TemplateTypes.EvidenceState.CONFLICTING:allRefs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,conflict?"FORM_FIELD_CONFLICTING":"FORM_FIELD_EVIDENCE_PENDING","必填字段必须在同一表单实例或同一页组内完整对应，不能拼接不同页面形成结论。",pending,allRefs,pending.stream().flatMap(c->c.missingInputs().stream()).toList(),List.of(need.key()));
        }
    }

    /** Compares two explicitly role-bound dates instead of comparing every date in the document. */
    private static final class DateRelation extends Base {
        DateRelation(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;Map<String,Object> p=parameters(spec);List<ValidationResult.ValidationError> errors=new ArrayList<>();
            if(same(string(p,"leftRole"),string(p,"rightRole")))errors.add(new ValidationResult.ValidationError("rightRole","IDENTICAL_DATE_ROLES","两个日期角色不能相同"));
            if(p.containsKey("maxDifferenceDays")&&integer(p,"maxDifferenceDays",0)<0)errors.add(new ValidationResult.ValidationError("maxDifferenceDays","OUT_OF_RANGE","允许日期差不能为负数"));
            return errors.isEmpty()?ValidationResult.ok():ValidationResult.errors(errors);
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);String relation=string(p,"relationKey");Map<String,String> left=new java.util.LinkedHashMap<>(),right=new java.util.LinkedHashMap<>();left.put("dateRole",string(p,"leftRole"));right.put("dateRole",string(p,"rightRole"));if(!relation.isBlank()){left.put("relationKey",relation);right.put("relationKey",relation);}
            return List.of(need(spec,"left-date",TemplateTypes.NeedKind.CERTIFICATE,Set.of("date"),left,false),need(spec,"right-date",TemplateTypes.NeedKind.CERTIFICATE,Set.of("date"),right,false));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String leftRole=string(p,"leftRole"),rightRole=string(p,"rightRole"),relation=string(p,"relationKey"),operator=string(p,"operator");List<EvidenceNeed> needs=evidenceNeeds(spec);
            List<Observation.CertificateObservation> left=dateObservations(view,spec,leftRole,relation),right=dateObservations(view,spec,rightRole,relation);List<Observation> all=new ArrayList<>();all.addAll(left);all.addAll(right);List<String> refs=sourceIds(all);Set<LocalDate> leftDates=dateValues(left),rightDates=dateValues(right);
            boolean conflict=left.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o))||right.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o))||leftDates.size()>1||rightDates.size()>1;
            if(conflict)return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"DATE_RELATION_CONFLICTING","同一日期角色存在多个冲突日期。",List.of(condition("relation","日期关系",TemplateTypes.ConditionState.UNKNOWN,"DATE_RELATION_CONFLICTING","日期值冲突",refs,List.of("date.uniqueValue"))),refs,List.of("date.uniqueValue"),needs.stream().map(EvidenceNeed::key).toList());
            if(needs.stream().anyMatch(need->!view.isClosed(need)))return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"DATE_RELATION_SCOPE_PENDING","日期候选已取得，但一个或两个角色的文件范围尚未闭合。",List.of(condition("relation","日期关系",TemplateTypes.ConditionState.UNKNOWN,"DATE_RELATION_SCOPE_PENDING","日期角色仍可能存在未处理的同范围候选值",refs,List.of("date.scope"))),refs,List.of("date.scope"),needs.stream().map(EvidenceNeed::key).toList());
            if(leftDates.size()!=1||rightDates.size()!=1)return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"DATE_RELATION_PENDING","尚未取得两个明确角色的可比日期。",List.of(condition("relation","日期关系",TemplateTypes.ConditionState.UNKNOWN,"DATE_RELATION_PENDING","左侧或右侧日期待定位",refs,List.of("date.left","date.right"))),refs,List.of("date.left","date.right"),needs.stream().map(EvidenceNeed::key).toList());
            LocalDate leftDate=leftDates.iterator().next(),rightDate=rightDates.iterator().next();int comparison=leftDate.compareTo(rightDate);boolean met=compareOrder(comparison,operator);long days=Math.abs(ChronoUnit.DAYS.between(leftDate,rightDate));if(p.containsKey("maxDifferenceDays"))met&=days<=integer(p,"maxDifferenceDays",0);String summary=leftRole+" "+leftDate+" 与 "+rightRole+" "+rightDate+(met?" 的日期关系满足要求。":" 的日期关系不满足要求。");
            return result(met?TemplateTypes.ConclusionState.SATISFIED:TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,met?"DATE_RELATION_SATISFIED":"DATE_RELATION_NOT_SATISFIED",summary,List.of(condition("relation","日期关系",met?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.NOT_SATISFIED,met?"DATE_RELATION_SATISFIED":"DATE_RELATION_NOT_SATISFIED",summary,refs,List.of())),refs,List.of(),needs.stream().map(EvidenceNeed::key).toList());
        }
    }

    /** Reports deterministic text candidates as advisory risks; it never turns missing text into a defect. */
    private static final class TextAnomaly extends Base {
        TextAnomaly(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;
            Map<String,Object> p=parameters(spec);List<String> types=strings(p,"anomalyTypes");
            List<ValidationResult.ValidationError> errors=new ArrayList<>();
            for(String type:types)if(!Set.of("TYPO","TRUNCATED","OCR_SUSPECT").contains(type.toUpperCase(Locale.ROOT)))
                errors.add(new ValidationResult.ValidationError("anomalyTypes","VALUE_NOT_ALLOWED","异常类型仅支持 TYPO、TRUNCATED、OCR_SUSPECT"));
            for(String rule:strings(p,"typoDictionary")){String[] parts=rule.split("(?:=>|->|→)",2);if(parts.length!=2||parts[0].isBlank()||parts[1].isBlank()||parts[0].strip().equals(parts[1].strip())||parts[0].strip().length()>40||parts[1].strip().length()>40)
                errors.add(new ValidationResult.ValidationError("typoDictionary","INVALID_TYPO_RULE","错词规则应为“错词=>正确词”，两侧各不超过40字"));}
            return errors.isEmpty()?ValidationResult.ok():ValidationResult.errors(errors);
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);return List.of(need(spec,"text-scan",TemplateTypes.NeedKind.MATERIAL,Set.of("anomalies"),Map.of("textScope",string(p,"textScope"),"anomalyTypes",String.join("|",strings(p,"anomalyTypes")),"typoDictionary",String.join("|",strings(p,"typoDictionary"))),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String type="TEXT:"+string(p,"textScope");Set<String> allowed=strings(p,"anomalyTypes").stream().map(value->value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,type)).toList();List<Observation.MaterialObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();List<String> refs=sourceIds(matches);boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o))||candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN);
            List<String> anomalies=matches.stream().filter(CoreTemplateEvaluators::supported).flatMap(o->pointValues(o.commitmentPoints(),"ANOMALY:").stream()).filter(value->{int split=value.indexOf(':');String code=(split<0?value:value.substring(0,split)).toUpperCase(Locale.ROOT);return allowed.contains(code);}).distinct().toList();
            if(conflict)return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"TEXT_SCAN_CONFLICTING","文字候选与来源记录存在冲突。",List.of(condition("anomalies","文字异常",TemplateTypes.ConditionState.UNKNOWN,"TEXT_SCAN_CONFLICTING","文字候选待复核",refs,List.of("text.original"))),refs,List.of("text.original"),List.of(need.key()));
            if(!anomalies.isEmpty())return result(TemplateTypes.ConclusionState.SUSPECTED_RISK,TemplateTypes.EvidenceState.SUFFICIENT,"TEXT_ANOMALY_FOUND","发现 "+anomalies.size()+" 处带定位的疑似错字、残句或 OCR 异常，需结合原文确认。",List.of(condition("anomalies","文字异常",TemplateTypes.ConditionState.NOT_SATISFIED,"TEXT_ANOMALY_FOUND",String.join("；",anomalies),refs,List.of())),refs,List.of(),List.of(need.key()));
            if(view.isClosed(need))return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"TEXT_SCAN_CLEAR","已完成指定文字范围扫描，未发现规则命中的异常候选。",List.of(condition("anomalies","文字异常",TemplateTypes.ConditionState.SATISFIED,"TEXT_SCAN_CLEAR","指定范围未发现异常候选",refs,List.of())),refs,List.of(),List.of(need.key()));
            return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"TEXT_SCAN_PENDING","指定文字范围尚未完成扫描，不能宣称无异常。",List.of(condition("anomalies","文字异常",TemplateTypes.ConditionState.UNKNOWN,"TEXT_SCAN_PENDING","文字扫描范围未闭合",refs,List.of("text.scanScope"))),refs,List.of("text.scanScope"),List.of(need.key()));
        }
    }

    /** Aggregates only source-backed score observations and waits for a closed evidence set. */
    private static final class ObjectiveScoring extends Base {
        ObjectiveScoring(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;BigDecimal maximum=decimal(parameters(spec),"maxScore");
            return maximum.signum()<0?ValidationResult.errors(List.of(new ValidationResult.ValidationError("maxScore","OUT_OF_RANGE","最高分不能为负数"))):ValidationResult.ok();
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);return List.of(need(spec,"scoring",TemplateTypes.NeedKind.PRICE,Set.of("amount","scoreItem"),Map.of("criterion",string(p,"criterion"),"aggregation",string(p,"aggregation"),"maxScore",plain(decimal(p,"maxScore"))),true));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String criterion=string(p,"criterion"),unit=string(p,"scoreUnit"),aggregation=string(p,"aggregation");boolean deduplicate=bool(p,"deduplicateBySubject",true);BigDecimal maximum=decimal(p,"maxScore");EvidenceNeed need=evidenceNeeds(spec).get(0);
            List<Observation.PriceObservation> candidates=view.observations(Observation.PriceObservation.class).stream().filter(o->o.currentScope()&&same(o.field(),criterion)&&o.currency().equalsIgnoreCase("SCORE")&&same(o.unit(),unit)).toList();List<Observation.PriceObservation> matches=candidates.stream().filter(o->syntheticSubjectMatch(spec.subjectSelector(),o)).toList();List<String> refs=sourceIds(matches);boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o)||o.amount().signum()<0);
            if(conflict)return result(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"OBJECTIVE_SCORE_CONFLICTING","客观评分证据存在冲突或非法负分。",List.of(condition("score","客观评分",TemplateTypes.ConditionState.UNKNOWN,"OBJECTIVE_SCORE_CONFLICTING","评分证据待核",refs,List.of("score.items"))),refs,List.of("score.items"),List.of(need.key()));
            if(!view.isClosed(need))return result(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"OBJECTIVE_SCORE_PENDING","客观评分证据范围尚未闭合，暂不生成精确得分。",List.of(condition("score","客观评分",TemplateTypes.ConditionState.UNKNOWN,"OBJECTIVE_SCORE_PENDING","评分证据仍可能补充",refs,List.of("score.scope"))),refs,List.of("score.scope"),List.of(need.key()));
            List<BigDecimal> values;if(deduplicate){Map<String,BigDecimal> bySubject=new java.util.LinkedHashMap<>();for(Observation.PriceObservation value:matches.stream().filter(CoreTemplateEvaluators::supported).toList()){String key=value.subjectKey().isBlank()?value.id():value.subjectKey();bySubject.merge(key,value.amount(),BigDecimal::max);}values=List.copyOf(bySubject.values());}else values=matches.stream().filter(CoreTemplateEvaluators::supported).map(Observation.PriceObservation::amount).toList();
            BigDecimal score="SUM".equals(aggregation)?values.stream().reduce(BigDecimal.ZERO,BigDecimal::add):values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);score=score.min(maximum);String summary="依据已闭合、可追溯的客观评分证据汇总为 "+plain(score)+unit+"，上限 "+plain(maximum)+unit+"。";
            return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"OBJECTIVE_SCORE_AGGREGATED",summary,List.of(condition("score","客观分值汇总",TemplateTypes.ConditionState.SATISFIED,"OBJECTIVE_SCORE_AGGREGATED",summary,refs,List.of())),refs,List.of(),List.of(need.key()));
        }
    }

    /** Produces evidence-based quality advice without presenting it as a final expert score. */
    private static final class QualityAdvisory extends Base {
        QualityAdvisory(TemplateMetadata metadata) { super(metadata); }
        @Override public ValidationResult validate(CheckSpec spec) {
            ValidationResult base=schema.validate(spec.parameters());if(!base.valid())return base;Map<String,Object> p=parameters(spec);List<String> dimensions=TemplateEvidenceAssembler.qualityDimensions(spec);
            if(dimensions.stream().map(CoreTemplateEvaluators::normalize).distinct().count()!=dimensions.size())return ValidationResult.errors(List.of(new ValidationResult.ValidationError("dimensions","DUPLICATE_DIMENSION","质量评估维度不能重复")));
            String highest=string(p,"highestTierDescription");if(!highest.isBlank()){List<String> unbound=dimensions.stream().filter(dimension->!normalize(highest).contains(normalize(dimension))).toList();if(!unbound.isEmpty())return ValidationResult.errors(List.of(new ValidationResult.ValidationError("highestTierDescription","DIMENSION_NOT_GROUNDED","最高档说明未明确包含评估维度："+String.join("、",unbound))));}
            return ValidationResult.ok();
        }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);return List.of(need(spec,"quality",TemplateTypes.NeedKind.MATERIAL,Set.of("bodyPresent","dimensions"),Map.of("materialType",string(p,"materialType"),"dimensions",String.join("|",TemplateEvidenceAssembler.qualityDimensions(spec))),false));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String materialType=string(p,"materialType");List<String> dimensions=TemplateEvidenceAssembler.qualityDimensions(spec);EvidenceNeed need=evidenceNeeds(spec).get(0);List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,materialType)).toList();List<Observation.MaterialObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();List<String> refs=sourceIds(matches);boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o))||candidates.stream().anyMatch(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.UNKNOWN);Set<String> points=matches.stream().filter(CoreTemplateEvaluators::supported).filter(o->o.bodyPresent()&&!o.titleOnly()).flatMap(o->o.commitmentPoints().stream()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));List<ConditionResult> conditions=new ArrayList<>();
            for(int index=0;index<dimensions.size();index++){String dimension=dimensions.get(index);List<String> gaps=pointValues(points,"GAP:"+dimension+":");boolean covered=hasPoint(points,"DIMENSION:"+dimension+":SUPPORTED");TemplateTypes.ConditionState state=!gaps.isEmpty()?TemplateTypes.ConditionState.NOT_SATISFIED:covered?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.UNKNOWN;List<String> dimensionRefs=sourceIds(matches.stream().filter(o->o.commitmentPoints().stream().anyMatch(point->same(point,"DIMENSION:"+dimension+":SUPPORTED")||point!=null&&point.regionMatches(true,0,"GAP:"+dimension+":",0,("GAP:"+dimension+":").length()))).toList());conditions.add(condition("dimension-"+(index+1),dimension,state,!gaps.isEmpty()?"QUALITY_GAP":covered?"QUALITY_DIMENSION_COVERED":"QUALITY_DIMENSION_PENDING",!gaps.isEmpty()?String.join("；",gaps):covered?"已有正文证据覆盖该维度":"尚未取得足以评价该维度的正文",dimensionRefs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("quality.dimension:"+dimension):List.of()));}
            if(conflict)return new TemplateResult(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"QUALITY_EVIDENCE_CONFLICTING","方案质量证据存在冲突，需先核对原文。",conditions,distinct(refs),List.of("quality.original"),List.of(need.key()),TemplateTypes.ActionOwner.USER,"HUMAN_JUDGMENT_REQUIRED","请评委结合原文确认冲突页面后再评价。");
            if(conditions.stream().anyMatch(c->c.state()==TemplateTypes.ConditionState.NOT_SATISFIED))return new TemplateResult(TemplateTypes.ConclusionState.SUSPECTED_RISK,TemplateTypes.EvidenceState.SUFFICIENT,"QUALITY_IMPROVEMENT_SUGGESTED","发现有证据支持的质量改进点；该建议不代表评委最终评分。",conditions,distinct(refs),List.of(),List.of(need.key()),TemplateTypes.ActionOwner.USER,"QUALITY_REVIEW","请评委结合评分办法和完整方案判断，并参考定位证据完善内容。");
            if(conditions.stream().allMatch(c->c.state()==TemplateTypes.ConditionState.SATISFIED))return new TemplateResult(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"QUALITY_DIMENSIONS_COVERED","指定质量维度均有正文覆盖；是否达到最高档仍由评委判断。",conditions,distinct(refs),List.of(),List.of(need.key()),TemplateTypes.ActionOwner.USER,"HUMAN_JUDGMENT_REQUIRED","请评委依据评分办法作最终质量判断。");
            List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();return new TemplateResult(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"QUALITY_EVIDENCE_PENDING","方案正文不足以覆盖全部评价维度，暂不生成质量结论。",conditions,distinct(refs),distinct(missing),List.of(need.key()),TemplateTypes.ActionOwner.SYSTEM,"LOCATE_EVIDENCE","请先定位对应方案正文；证据齐全后仍由评委作最终判断。");
        }
    }

    /** Waits for a source-adapter record and never substitutes uploaded document text for an external check. */
    private static final class ExternalCheck extends Base {
        ExternalCheck(TemplateMetadata metadata) { super(metadata); }
        @Override public List<EvidenceNeed> evidenceNeeds(CheckSpec spec) {
            Map<String,Object> p=parameters(spec);Map<String,String> constraints=stringConstraints(p,List.of("source","queryObject","asOfDate","adapterId"));return List.of(need(spec,"external-record",TemplateTypes.NeedKind.MATERIAL,Set.copyOf(strings(p,"requiredFields")),constraints,false));
        }
        @Override public TemplateResult evaluate(CheckSpec spec,EvidenceView view) {
            Map<String,Object> p=parameters(spec);String source=string(p,"source"),materialType="EXTERNAL:"+source;List<String> fields=strings(p,"requiredFields");EvidenceNeed need=evidenceNeeds(spec).get(0);List<Observation.MaterialObservation> candidates=view.observations(Observation.MaterialObservation.class).stream().filter(o->materialTypeMatches(o,materialType)).toList();List<Observation.MaterialObservation> matches=candidates.stream().filter(o->subjectMatch(spec.subjectSelector(),o)==SubjectMatch.MATCH).toList();List<String> refs=sourceIds(matches);boolean conflict=matches.stream().anyMatch(o->o.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(o));Set<String> points=matches.stream().filter(CoreTemplateEvaluators::supported).flatMap(o->o.commitmentPoints().stream()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));List<ConditionResult> conditions=new ArrayList<>();
            for(int index=0;index<fields.size();index++){String field=fields.get(index);boolean negative=hasPoint(points,"NEGATIVE:"+field),present=hasPointPrefix(points,"FIELD:"+field);TemplateTypes.ConditionState state=negative?TemplateTypes.ConditionState.NOT_SATISFIED:present?TemplateTypes.ConditionState.SATISFIED:TemplateTypes.ConditionState.UNKNOWN;conditions.add(condition("field-"+(index+1),field,state,negative?"EXTERNAL_FIELD_NEGATIVE":present?"EXTERNAL_FIELD_PRESENT":"EXTERNAL_FIELD_PENDING",negative?"外部记录明确显示该字段不满足":present?"外部记录已返回该字段":"外部记录尚未返回该字段",refs,state==TemplateTypes.ConditionState.UNKNOWN?List.of("external.field:"+field):List.of()));}
            if(conflict)return new TemplateResult(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.CONFLICTING,"EXTERNAL_RECORD_CONFLICTING","外部记录存在冲突，不能形成结论。",conditions,distinct(refs),List.of("external.record"),List.of(need.key()),TemplateTypes.ActionOwner.EXTERNAL,"REQUEST_EXTERNAL_RECORD","请从指定来源重新取得同对象、同时点的外部记录。");
            TemplateTypes.ConditionState combined=RestrictedLogic.allOf(conditions.stream().map(ConditionResult::state).toList());if(combined==TemplateTypes.ConditionState.SATISFIED)return result(TemplateTypes.ConclusionState.SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"EXTERNAL_CHECK_SATISFIED","指定外部来源已返回所需字段，核验结果满足要求。",conditions,refs,List.of(),List.of(need.key()));if(combined==TemplateTypes.ConditionState.NOT_SATISFIED)return result(TemplateTypes.ConclusionState.NOT_SATISFIED,TemplateTypes.EvidenceState.SUFFICIENT,"EXTERNAL_CHECK_NOT_SATISFIED","指定外部来源返回了明确的不满足记录。",conditions,refs,List.of(),List.of(need.key()));
            List<String> missing=conditions.stream().flatMap(c->c.missingInputs().stream()).toList();return new TemplateResult(TemplateTypes.ConclusionState.UNDETERMINED,refs.isEmpty()?TemplateTypes.EvidenceState.NOT_LOCATED:TemplateTypes.EvidenceState.PARTIAL,"EXTERNAL_RECORD_REQUIRED","尚未取得指定平台或时点的外部核验记录，不能据此判断材料有问题。",conditions,distinct(refs),distinct(missing),List.of(need.key()),TemplateTypes.ActionOwner.EXTERNAL,"REQUEST_EXTERNAL_RECORD","请通过 "+source+" 查询“"+string(p,"queryObject")+"”并保存可追溯记录。");
        }
    }

    private enum SubjectMatch { MATCH, NO_MATCH, UNKNOWN }

    private record FieldResolution(Set<String> values,boolean conflicting) { }

    private static boolean supportedEntityField(String field) {
        if(field==null)return false;
        return Set.of("personKey","personName","employerKey","consortiumRole","certificate.holderKey",
                "certificate.holderName","certificate.certificateNumber").contains(field)||field.startsWith("attributes.")&&field.length()>"attributes.".length();
    }
    private static boolean certificateField(String field){return field!=null&&field.startsWith("certificate.");}
    private static boolean fieldNeedClosed(String field,EvidenceNeed personnelNeed,EvidenceNeed certificateNeed,EvidenceView view){return certificateField(field)?certificateNeed!=null&&view.isClosed(certificateNeed):view.isClosed(personnelNeed);}
    private static FieldResolution resolveEntityField(Observation.PersonnelObservation person,List<Observation.CertificateObservation> certificates,String field){
        LinkedHashSet<String> values=new LinkedHashSet<>();boolean conflict=false;
        if(certificateField(field)){
            for(Observation.CertificateObservation certificate:certificates){
                if(certificate.state()==TemplateTypes.ObservationState.CONFLICTING||invalidSupported(certificate)){conflict=true;continue;}
                if(!supported(certificate))continue;
                String value=switch(field){
                    case "certificate.holderKey"->certificate.holderKey();
                    case "certificate.holderName"->certificate.holderName();
                    case "certificate.certificateNumber"->certificate.certificateNumber();
                    default->"";
                };
                if(!value.isBlank())values.add(value);
            }
        }else{
            String value=switch(field){
                case "personKey"->person.personKey();case "personName"->person.personName();case "employerKey"->person.employerKey();case "consortiumRole"->person.consortiumRole();
                default->field.startsWith("attributes.")?person.attributes().getOrDefault(field.substring("attributes.".length()),""):"";
            };
            if(!value.isBlank())values.add(value);
        }
        return new FieldResolution(Set.copyOf(values),conflict||values.size()>1);
    }

    private static boolean materialTypeMatches(Observation.MaterialObservation observation,String expected) {
        return same(observation.materialType(),expected)||observation.aliases().stream().anyMatch(alias->same(alias,expected));
    }

    /**
     * A selector is part of the proof, rather than a display filter.  Entity key equality alone is
     * insufficient: the observation must also prove entity type, requested role and requested
     * scope.  When the closed observation API cannot carry one of those dimensions, UNKNOWN keeps
     * the check out of both automatic pass and automatic failure.
     */
    private static SubjectMatch subjectMatch(SubjectSelector selector,Observation observation) {
        if(!currentScope(observation))return SubjectMatch.NO_MATCH;
        String expectedKey=selector.constraints().getOrDefault("entityKey","");
        if(!expectedKey.isBlank()&&!expectedKey.equalsIgnoreCase(observation.subjectKey()))return SubjectMatch.NO_MATCH;
        String actualType=observation instanceof Observation.PersonnelObservation||observation instanceof Observation.CertificateObservation
                ?"PERSON":"ORGANIZATION";
        if(!selector.entityType().equalsIgnoreCase(actualType))return SubjectMatch.NO_MATCH;

        String scope=selector.scope().toUpperCase(Locale.ROOT);
        if("CURRENT_LOT".equals(scope)){
            String expectedLot=selector.constraints().getOrDefault("lot","");
            if(!(observation instanceof Observation.PriceObservation price)||expectedLot.isBlank())return SubjectMatch.UNKNOWN;
            if(!same(expectedLot,price.lot()))return SubjectMatch.NO_MATCH;
        }else if("DOCUMENT".equals(scope)){
            String documentId=selector.constraints().getOrDefault("documentId","");
            if(documentId.isBlank())return SubjectMatch.UNKNOWN;
            if(observation.sources().stream().noneMatch(source->documentId.equals(source.documentId())))return SubjectMatch.NO_MATCH;
        }else if(!"CURRENT_PROJECT".equals(scope))return SubjectMatch.UNKNOWN;

        String expectedRole=selector.role();
        if(expectedRole.isBlank())return SubjectMatch.MATCH;
        if(observation instanceof Observation.PersonnelObservation personnel)
            return personnel.roles().stream().anyMatch(role->same(role,expectedRole))?SubjectMatch.MATCH:SubjectMatch.NO_MATCH;
        if(observation instanceof Observation.CertificateObservation)
            return same(observation.subjectKey(),expectedRole)?SubjectMatch.MATCH:SubjectMatch.UNKNOWN;
        return same(observation.subjectKey(),expectedRole)?SubjectMatch.MATCH:SubjectMatch.UNKNOWN;
    }

    private static boolean currentScope(Observation observation) {
        if(observation instanceof Observation.MaterialObservation value)return value.currentScope();
        if(observation instanceof Observation.SealObservation value)return value.currentScope();
        if(observation instanceof Observation.CertificateObservation value)return value.currentScope();
        if(observation instanceof Observation.PriceObservation value)return value.currentScope();
        if(observation instanceof Observation.PersonnelObservation value)return value.currentScope();
        return false;
    }

    private static SubjectMatch issuerMatch(Observation.MaterialObservation material,String issuerRole) {
        if(issuerRole.isBlank())return SubjectMatch.UNKNOWN;
        return same(material.subjectKey(),issuerRole)?SubjectMatch.MATCH:SubjectMatch.UNKNOWN;
    }

    private static boolean sealBoundToMaterial(Observation.SealObservation seal,Observation.MaterialObservation material) {
        if(!seal.materialObservationId().isBlank()&&seal.materialObservationId().equals(material.id()))return true;
        return seal.sources().stream().filter(CoreTemplateEvaluators::validSource).anyMatch(sealSource->
                material.sources().stream().filter(CoreTemplateEvaluators::validSource).anyMatch(materialSource->
                        sealSource.documentId().equals(materialSource.documentId())
                                &&sealSource.documentVersion()==materialSource.documentVersion()
                                &&sealSource.locationType().equalsIgnoreCase(materialSource.locationType())
                                &&sealSource.location()==materialSource.location()));
    }

    private static boolean supported(Observation observation) {
        return observation.state()==TemplateTypes.ObservationState.SUPPORTED&&observation.confidence()>0
                &&observation.sources().stream().anyMatch(CoreTemplateEvaluators::validSource);
    }
    private static boolean invalidSupported(Observation observation) {
        return observation.state()==TemplateTypes.ObservationState.SUPPORTED&&!supported(observation);
    }
    private static boolean validSource(SourceRef source) {
        return source!=null&&!source.documentId().isBlank()&&source.documentVersion()>=0
                &&!source.locationType().isBlank()&&source.location()>=0
                &&(!source.sourceHash().isBlank()||!source.documentHash().isBlank()
                ||!source.parserFingerprint().isBlank()||!source.quote().isBlank());
    }
    private static boolean covers(Observation.CertificateObservation value,LocalDate date,boolean inclusive){if(value.longTerm())return value.validFrom()==null||!date.isBefore(value.validFrom());if(value.validUntil()==null)return false;boolean afterStart=value.validFrom()==null||!date.isBefore(value.validFrom());boolean beforeEnd=inclusive?!date.isAfter(value.validUntil()):date.isBefore(value.validUntil());return afterStart&&beforeEnd;}
    private static List<Observation.CertificateObservation> dateObservations(EvidenceView view,CheckSpec spec,String role,String relationKey){
        return view.observations(Observation.CertificateObservation.class).stream().filter(Observation.CertificateObservation::currentScope)
                .filter(o->same(o.certificateType(),"DATE:"+role))
                .filter(o->relationKey.isBlank()?syntheticSubjectMatch(spec.subjectSelector(),o):same(o.holderKey(),relationKey)).toList();
    }
    private static boolean syntheticSubjectMatch(SubjectSelector selector,Observation observation){String expected=selector.constraints().getOrDefault("entityKey","");if(expected.isBlank()&&"ORGANIZATION".equalsIgnoreCase(selector.entityType()))expected=selector.role();return expected.isBlank()||same(expected,observation.subjectKey());}
    private static Set<LocalDate> dateValues(Collection<Observation.CertificateObservation> values){return values.stream().filter(CoreTemplateEvaluators::supported).map(Observation.CertificateObservation::validFrom).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));}
    private static boolean compareOrder(int comparison,String operator){return switch(operator){case"="->comparison==0;case"!="->comparison!=0;case"<="->comparison<=0;case"<"->comparison<0;case">="->comparison>=0;case">"->comparison>0;default->false;};}
    private static boolean priceScope(Observation.PriceObservation o,Map<String,Object> p,String field,String currency,String unit){if(!o.currentScope()||!same(o.field(),field)||!o.currency().equalsIgnoreCase(currency)||!o.unit().equals(unit))return false;for(String key:List.of("lot","priceLayer","taxBasis")){String expected=String.valueOf(p.getOrDefault(key,""));String actual=switch(key){case"lot"->o.lot();case"priceLayer"->o.priceLayer();default->o.taxBasis();};if(!expected.isBlank()&&!same(expected,actual))return false;}return true;}
    private static boolean personnelDimensions(Observation.PersonnelObservation o,Map<String,Object> p){String consortium=String.valueOf(p.getOrDefault("consortiumRole","")),employer=String.valueOf(p.getOrDefault("employerKey",""));return (consortium.isBlank()||same(consortium,o.consortiumRole()))&&(employer.isBlank()||employer.equals(o.employerKey()));}
    private static boolean concurrent(Observation.PersonnelObservation o,String role,Set<String> disallowed){return o.roles().stream().anyMatch(other->!same(other,role)&&(disallowed.isEmpty()||disallowed.stream().anyMatch(value->same(value,other))));}
    private static boolean compare(BigDecimal actual,BigDecimal expected,String operator,BigDecimal tolerance){BigDecimal delta=actual.subtract(expected).abs();return switch(operator){case"="->delta.compareTo(tolerance)<=0;case"!="->delta.compareTo(tolerance)>0;case"<="->actual.compareTo(expected.add(tolerance))<=0;case"<"->actual.compareTo(expected.subtract(tolerance))<0;case">="->actual.compareTo(expected.subtract(tolerance))>=0;case">"->actual.compareTo(expected.add(tolerance))>0;default->false;};}
    private static String string(Map<String,Object> values,String key){Object value=values.get(key);return value==null?"":String.valueOf(value).trim();}
    private static boolean bool(Map<String,Object> values,String key,boolean fallback){Object value=values.get(key);return value instanceof Boolean b?b:value==null?fallback:Boolean.parseBoolean(String.valueOf(value));}
    private static int integer(Map<String,Object> values,String key,int fallback){Object value=values.get(key);return value==null?fallback:value instanceof Number number?number.intValue():Integer.parseInt(String.valueOf(value));}
    private static BigDecimal decimal(Map<String,Object> values,String key){return decimal(values,key,null);}
    private static BigDecimal decimal(Map<String,Object> values,String key,BigDecimal fallback){Object value=values.get(key);return value==null?fallback:value instanceof BigDecimal decimal?decimal:new BigDecimal(String.valueOf(value));}
    private static List<String> strings(Map<String,Object> values,String key){Object value=values.get(key);if(!(value instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).map(String::trim).filter(s->!s.isBlank()).toList();}
    private static Map<String,String> stringConstraints(Map<String,Object> values,List<String> keys){Map<String,String> result=new java.util.LinkedHashMap<>();for(String key:keys){String value=string(values,key);if(!value.isBlank())result.put(key,value);}return result;}
    private static List<String> pointValues(Collection<String> points,String prefix){return points.stream().filter(value->value!=null&&value.regionMatches(true,0,prefix,0,prefix.length())).map(value->value.substring(prefix.length()).trim()).filter(value->!value.isBlank()).toList();}
    private static boolean hasPoint(Collection<String> points,String expected){return points.stream().anyMatch(value->same(value,expected));}
    private static boolean hasPointPrefix(Collection<String> points,String prefix){return points.stream().anyMatch(value->value!=null&&value.regionMatches(true,0,prefix,0,prefix.length()));}
    private static String canonicalConsistencyValue(String field,String raw,boolean caseSensitive){
        String value=raw==null?"":raw.trim();if(value.isBlank())return "";
        return switch(field){
            case "companyName"->caseSensitive
                    ?value.replaceAll("[（(]?\\s*(?:盖章|单位公章|公章)\\s*[）)]?","")
                    :TemplateEvidenceAssembler.canonicalCompanyValue(value);
            case "projectName"->caseSensitive
                    ?value.replaceAll("<[^>]*>","").replaceAll("[\\s\\p{Z}《》〈〉<>（）()【】\\[\\]：:、,，。;；_—–-]","")
                    :normalize(value);
            case "creditCode","projectNumber"->value.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
            case "documentDate","date"->canonicalDate(value);
            case "totalPrice"->canonicalMoney(value);
            case "duration","designDuration","constructionDuration","installationDuration",
                    "commissioningDuration","bidValidity","warranty"->canonicalDuration(value);
            default->"";
        };
    }
    private static String canonicalDate(String value){
        try{return LocalDate.parse(value).toString();}catch(java.time.format.DateTimeParseException ignored){}
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(?<!\\d)(\\d{4})[年./-](\\d{1,2})[月./-](\\d{1,2})日?(?!\\d)").matcher(value);
        if(!matcher.find())return "";
        try{return LocalDate.of(Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2)),Integer.parseInt(matcher.group(3))).toString();}catch(RuntimeException ignored){return "";}
    }
    private static String canonicalMoney(String value){
        String upper=value.toUpperCase(Locale.ROOT);String currency="";
        if(upper.matches("(?s).*(?:人民币|CNY|RMB|￥|¥|亿元|万元|元).*"))currency="CNY";
        else if(upper.matches("(?s).*(?:美元|USD|US\\$|\\$).*"))currency="USD";
        else if(upper.matches("(?s).*(?:欧元|EUR|€).*"))currency="EUR";
        else if(upper.matches("(?s).*(?:港币|港元|HKD).*"))currency="HKD";
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(?:[A-Z][A-Z_]*:)?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)").matcher(upper);
        if(currency.isBlank()||!matcher.find())return "";
        BigDecimal amount=new BigDecimal(matcher.group(1).replace(",",""));
        if(value.contains("亿元"))amount=amount.multiply(new BigDecimal("100000000"));
        else if(value.contains("万元")||value.matches("(?s).*\\d\\s*万(?:[^元]|$).*"))amount=amount.multiply(new BigDecimal("10000"));
        return currency+":"+plain(amount);
    }
    private static String canonicalDuration(String value){
        String upper=value.toUpperCase(Locale.ROOT);String prefix="";String numericSource=upper;
        int colon=upper.indexOf(':');if(colon>0){prefix=upper.substring(0,colon).trim();numericSource=upper.substring(colon+1);}
        java.util.regex.Matcher amountMatcher=java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(numericSource);
        if(!amountMatcher.find())return "";String basis="";
        if(prefix.contains("WORKDAY")||value.contains("工作日"))basis="WORKDAY";
        else if(prefix.contains("MONTH")||value.contains("个月")||value.matches("(?s).*\\d\\s*月.*"))basis="MONTH";
        else if(prefix.contains("YEAR")||value.matches("(?s).*\\d\\s*年.*"))basis="YEAR";
        else if(prefix.contains("DAY")||value.contains("日历日")||value.matches("(?s).*\\d\\s*(?:天|日).*"))basis="DAY";
        if(basis.isBlank())return "";
        return basis+":"+plain(new BigDecimal(amountMatcher.group(1)));
    }
    private static String formInstanceKey(Observation.MaterialObservation observation){
        String locations=observation.sources().stream().filter(CoreTemplateEvaluators::validSource)
                .map(source->source.documentId()+":"+source.documentVersion()+":"+source.locationType()+":"+source.location())
                .sorted().collect(java.util.stream.Collectors.joining("|"));
        return locations.isBlank()?observation.id():locations;
    }
    private static boolean same(String left,String right){return normalize(left).equals(normalize(right));}
    private static String normalize(String value){return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[\\s《》〈〉<>（）()【】\\[\\]：:、,，。;；_—-]","");}
    private static String safe(String value){String normalized=normalize(value);return normalized.isBlank()?"item":Integer.toUnsignedString(normalized.hashCode(),36);}
    private static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static List<String> sourceIds(Collection<? extends Observation> values){LinkedHashSet<String> ids=new LinkedHashSet<>();for(Observation value:values)for(SourceRef source:value.sources())if(validSource(source))ids.add(source.documentId()+":"+source.documentVersion()+":"+source.locationType()+":"+source.location());return List.copyOf(ids);}
    private static List<String> distinct(Collection<String> values){if(values==null)return List.of();return values.stream().filter(v->v!=null&&!v.isBlank()).distinct().toList();}
    private record FormInstanceResult(TemplateTypes.ConditionState state,List<ConditionResult> conditions,List<String> refs){}
}
