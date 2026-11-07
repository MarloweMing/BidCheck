package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A reread is scoped to the target and to original bodies returned by the host. */
final class ValidationReadWork {
    static final String VERSION = "validated-read-work-1";
    private final Map<String,Rule> rules = new HashMap<>();
    private final Map<String,List<Source>> independent = new HashMap<>();
    private final Map<String,Set<String>> rejectedSources = new HashMap<>();
    private final Map<String,String> dispositions = new HashMap<>();
    private final Map<String,String> boundPersons = new HashMap<>();
    private final Set<String> possibleDirectAppointments = new HashSet<>();
    private final EvidenceCorpus frozen;

    ValidationReadWork(List<Rule> targets, EvidenceCorpus corpus) {
        frozen=corpus;
        for (Rule target : targets) {
            if (isAppointment(target)) {
                List<Source> candidates = new ArrayList<>();
                for (Source source : corpus.all) {
                    if (source.index() || source.historical() || source.scopeContext().blocked()
                            || source.id().startsWith("V")) continue;
                    String body = source.evidence().quote().replaceAll("\\s+", "");
                    boolean agreement = body.contains("联合体协议") && body.contains("牵头")
                        && (body.contains("共同投标") || body.contains("联合体成员") || body.contains("自愿组成"));
                    String person = target.subject();
                    boolean namedAppointment = !person.isEmpty() && body.contains(person)
                        && (body.contains("委派") || body.contains("任命") || body.contains("派任"));
                    if (agreement || namedAppointment) candidates.add(source);
                }
                independent.put(target.id(), candidates);
                rules.put(target.id(), target);
            } else if ("VALIDITY".equals(target.predicate()) && "certificateExpiry".equals(target.field())
                    && target.subject().isEmpty() && Boolean.TRUE.equals(target.parameters().get("allApplicableCertificates"))) {
                List<Source> eligible = new ArrayList<>();
                for (Source source : corpus.all) if (!source.index() && !source.historical()
                        && !source.scopeContext().blocked() && !source.id().startsWith("V")
                        && source.evidence().quote().contains("证")) eligible.add(source);
                Map<String,Source> records = new LinkedHashMap<>();
                // Unit qualifications can be usable originals even when a personnel certificate parser has no record.
                for(Source source:eligible) {
                    String body=source.evidence().quote().replaceAll("\\s+","");
                    if ((body.contains("资质证书") || body.contains("安全生产许可证")) && body.contains("有效期")
                            && (body.contains("证书编号") || body.contains("证书号码") || body.contains("许可证编号")))
                        records.put(source.id(),source);
                }
                for (CertificateSourceComparison.Record record : CertificateRecordBindings.records(corpus, eligible)) {
                    if (!record.fields().keySet().stream().anyMatch(field -> field.startsWith("VALIDITY_"))) continue;
                    for (Source source : record.sources()) records.putIfAbsent(source.id(), source);
                }
                independent.put(target.id(), new ArrayList<>(records.values()));
                rules.put(target.id(), target);
            }
        }
    }

    private static boolean isAppointment(Rule rule) {
        return "RELATION".equals(rule.predicate()) && "委派".equals(rule.field());
    }

    Map<String,List<Source>> records() { return independent; }

    void bindSubjects(List<Map<String,Object>> contracts, EvidenceCorpus corpus) {
        for(Map<String,Object> contract:contracts) {
            String id=str(contract,"obligationId");
            Rule target=rules.get(id);
            if(target==null || !isAppointment(target) || !"RESOLVED".equals(str(contract,"bindingStatus")))continue;
            List<Map<String,Object>> subjects=RuleEvaluator.maps(contract.get("resolvedSubjects"));
            if(subjects.size()!=1)continue;
            String person=str(subjects.get(0),"subject");
            if(person.isEmpty() || DomainBindings.role(person))continue;
            boundPersons.put(id,person);
            Set<Source> distinct=new LinkedHashSet<>(independent.get(id));
            Map<String,List<Source>> samePage=new LinkedHashMap<>();
            for(Source source:corpus.all) {
                if(source.index() || source.historical() || source.scopeContext().blocked() || source.id().startsWith("V"))continue;
                String page=EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType());
                samePage.computeIfAbsent(page,ignored->new ArrayList<>()).add(source);
            }
            for(List<Source> chunks:samePage.values()){
                StringBuilder page=new StringBuilder();
                for(Source chunk:chunks)page.append(chunk.evidence().quote());
                String body=page.toString().replaceAll("\\s+","");
                if(body.contains(person) && body.matches("(?s).*(?:委派|任命|派任|委任|选派|派遣|派出).*")){
                    possibleDirectAppointments.add(id);distinct.addAll(chunks);
                }
            }
            independent.put(id,new ArrayList<>(distinct));
        }
    }

    List<Source> prioritize(String target, List<Source> ranked, Set<String> read) {
        List<Source> candidates = independent.get(target);
        if (candidates == null || candidates.isEmpty()) return ranked;
        Map<String,Source> ordered = new LinkedHashMap<>();
        for (Source source : candidates) if (!read.contains(source.id())) ordered.put(source.id(), source);
        for (Source source : ranked) ordered.putIfAbsent(source.id(), source);
        return new ArrayList<>(ordered.values());
    }

    void observe(String target, Map<String,Object> args, Map<String,Object> result) {
        Rule rule = rules.get(target);
        if (rule == null || !"REJECTED".equals(str(result,"status"))) return;
        Map<String,Object> fact = object(args.get("fact"));
        Set<String> ids = new TreeSet<>(ProofVerifier.strings(fact.get("sourceIds")));
        String reason = "";
        if (isAppointment(rule) && "INFERENCE_PROTOCOL_INVALID".equals(str(result,"error"))) {
            Map<String,Object> attrs = object(fact.get("attributes"));
            Map<String,Object> inference = object(attrs.get("inference"));
            List<Map<String,Object>> citations = RuleEvaluator.maps(inference.get("evidence"));
            // A structurally correctable proposal whose citations name the proposed person
            // still has real work. Retire a request to reread only when that endpoint is absent.
            String person=boundPersons.get(target);
            if (!possibleDirectAppointments.contains(target) && attrs.get("proposedFact") == null && !citations.isEmpty() && person!=null
                    && person.equals(str(fact,"value"))
                    && citations.stream().allMatch(c -> ids.contains(str(c,"sourceId"))
                        && !str(c,"quote").isEmpty() && !str(c,"quote").contains(person)))
                reason = "APPOINTMENT_ENDPOINT_UNCITED";
        } else if (isAppointment(rule) && "RELATION".equals(str(fact,"kind")) && rule.field().equals(str(fact,"field"))
                && Objects.equals(boundPersons.get(target),str(fact,"value")) && !possibleDirectAppointments.contains(target)
                && exclusivelyUngroundedValue(result) && mixedOriginals(str(fact,"rawValue"),ids)) {
            reason="APPOINTMENT_ENDPOINT_UNCITED";
        } else if ("VALIDITY".equals(rule.predicate()) && "CERTIFICATE".equals(str(fact,"kind"))
                && rule.field().equals(str(fact,"field"))) {
            List<Map<String,Object>> issues = RuleEvaluator.maps(result.get("rejections"));
            if (!issues.isEmpty() && issues.stream().allMatch(i -> "RAW_VALUE_UNGROUNDED".equals(str(i,"code"))
                    && "/fact/rawValue".equals(str(i,"fieldPath"))))
                reason = "OBSERVED_CERTIFICATE_RECORDS_READ";
        }
        // A later different repair or candidate must replace the previous disposition.
        if (ids.isEmpty() || reason.isEmpty()) {
            dispositions.remove(target);
            rejectedSources.remove(target);
        } else {
            dispositions.put(target, reason);
            rejectedSources.put(target, ids);
        }
    }

    private static boolean exclusivelyUngroundedValue(Map<String,Object> result) {
        List<Map<String,Object>> issues=RuleEvaluator.maps(result.get("rejections"));
        return !issues.isEmpty() && issues.stream().allMatch(i->"RAW_VALUE_UNGROUNDED".equals(str(i,"code"))
            && "/fact/rawValue".equals(str(i,"fieldPath")));
    }

    /** Positive evidence of a splice, not a presumption based on a validator label. */
    private boolean mixedOriginals(String rawValue,Set<String> ids) {
        if(ids.size()<2 || rawValue.isEmpty())return false;
        Set<String> owners=new HashSet<>(),fragments=new HashSet<>();
        String normalized=rawValue.replaceAll("\\s+","");
        for(String id:ids) {
            Source source=frozen.byId.get(id);
            if(source==null)return false;
            String page=EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType());
            cn.bidcheck.domain.Domain.Page original=frozen.pages.get(page);
            if(original==null)return false;
            String text=Objects.toString(original.text(),"").replaceAll("\\s+","");
            if(text.contains(normalized))return false; // Includes legitimate quotations spanning source chunks.
            cn.bidcheck.domain.Domain.Page following=frozen.pages.get(EvidenceCorpus.pageKey(
                source.evidence().documentId(),source.evidence().page()+1,source.evidence().locationType()));
            if(following!=null && (text+Objects.toString(following.text(),"").replaceAll("\\s+","")).contains(normalized))return false;
            String body=source.evidence().quote().replaceAll("\\s+","");
            for(String line:rawValue.split("\\R")){
                String fragment=line.replaceAll("\\s+","");
                if(fragment.length()>=4 && body.contains(fragment)){owners.add(page);fragments.add(fragment);}
            }
        }
        return owners.size()>=2 && fragments.size()>=2;
    }

    Map<String,Object> receipt(String target, String revision, Set<String> bodies) {
        if (!dispositions.containsKey(target)) return Collections.emptyMap();
        Set<String> required = new TreeSet<>(rejectedSources.get(target));
        for (Source source : independent.getOrDefault(target, Collections.emptyList())) required.add(source.id());
        Set<String> pending = new TreeSet<>(required);
        pending.removeAll(bodies);
        return linked("version", VERSION, "authority", "CURRENT_SERVER_PROOF", "targetObligationId", target,
            "evidenceRevision", revision, "reason", dispositions.get(target), "businessProof", false,
            "requiredSourceIds", new ArrayList<>(required), "readSourceIds", new ArrayList<>(new TreeSet<>(bodies)),
            "pendingSourceIds", new ArrayList<>(pending));
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        return value instanceof Map ? (Map<String,Object>)value : Collections.emptyMap();
    }
}
