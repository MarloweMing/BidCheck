package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A damaged signing-date sentence may request its original image. Only a field already
 * admitted by EvidenceCorpus (same frozen page, image hash and bounds) can yield a day. */
final class ContractSigningOriginal {
    private static final Pattern DAMAGED=Pattern.compile(
        "(?:签订|签署|签约)(?:时间|日期)[ \\t：:|*#\\r\\n]{0,40}(?:本合同于)?[ \\t_]*(.{0,12}年.{0,7}月.{0,7}日)(?:签订|签署|签约)?");
    private static final Pattern IMAGE_DATE=Pattern.compile(
        "^(?:合同)?(?:签订|签署|签约)(?:时间|日期)[ \\t]*[:：][ \\t]*(20[0-9]{2}[年./-][0-9]{1,2}[月./-][0-9]{1,2}日?)$");

    static String damagedDateQuote(Source source) {
        if(source.id().startsWith("V") || source.index())return "";
        Matcher m=DAMAGED.matcher(source.evidence().quote());
        while(m.find()) {
            if(!m.group(1).contains("年") || !m.group(1).matches("(?s).*[0-9].*")
                    || !m.group(1).matches("(?s).*(?:[_%?？�×]|[0-9]{1,3}年).*")
                    || m.group(1).matches("\\s*20[0-9]{2}年[0-9]{1,2}月[0-9]{1,2}日\\s*")) continue;
            return m.group().trim();
        }
        return "";
    }

    static final class Review {
        final List<Source> visual=new ArrayList<>();
        final List<Map<String,Object>> checks=new ArrayList<>();
        final Map<String,List<Source>> days=new LinkedHashMap<>();
        boolean differentPrintedMonth;
    }

    static Review inspect(Rule rule,Map<String,Object> row,List<Fact> verified,RuleEvaluator e) {
        Review out=new Review();
        if(row==null)return out;
        // All field facts belong to the same scored row. At least one server-issued identity
        // must independently bind each candidate image page (including the whole packet conflict scan).
        Map<String,Fact> identities=new LinkedHashMap<>();
        for(Fact f:verified) {
            String record=str(f.attributes(),"contractRecordId");
            if(!record.isEmpty())identities.putIfAbsent(record,f);
        }
        if(identities.isEmpty()) return out;
        for(Source source:e.corpus.all) {
            if(!DeclaredPerformanceRows.contains(row,source))continue;
            String line=damagedDateQuote(source);
            if(line.isEmpty() && str(row,"separatorTitle").contains("框架合同") && FrameworkOriginalFields.signatures(source)
                    && SigningDateBlock.read(Collections.singletonList(source)).status==SigningDateBlock.Status.NOT_FOUND) {
                // One date after both signatures can belong to the second signer or to the
                // joint agreement. The field's printed position must be checked on this image.
                String quote=source.evidence().quote();
                Matcher tail=Pattern.compile("(?m)^([ \\t]*20[0-9]{2}年[0-9]{1,2}月[0-9]{1,2}日)[ \\t]*(?:\\s*第[ \\t]*[0-9]+[ \\t]*页[ \\t]*共[ \\t]*[0-9]+[ \\t]*页)?\\s*\\z").matcher(quote);
                if(tail.find() && quote.contains("授权代表"))line="双方签署区页尾："+tail.group(1).trim();
            }
            if(line.isEmpty() || identities.values().stream().noneMatch(f->binds(row,f,source,e.corpus)))continue;
            List<Source> readable=new ArrayList<>();
            List<Map<String,Object>> readings=new ArrayList<>();
            Set<String> inspectedImages=new HashSet<>();
            for(Map<String,Object> observation:RuleEvaluator.maps(e.context.get("visualObservations"))) {
                if(!requestedScope(observation,rule.id(),source))continue;
                inspectedImages.add(str(observation,"imageHash"));
                Source image=e.corpus.visualFieldSource(observation);
                if(image==null) continue;
                // The old cached assessment was made for any possible use. Re-evaluate the
                // raw limitations here, after the row, original signing line and request match.
                VisualObservationPolicy.Assessment assessment=VisualObservationPolicy.assessBoundContractSigningLine(observation);
                Matcher value=IMAGE_DATE.matcher(image.evidence().quote());
                if(assessment.valueUsable() && value.matches() && ProofVerifier.date(value.group(1))!=null) {
                    readable.add(image);
                    out.days.computeIfAbsent(ProofVerifier.date(value.group(1)).toString(),k->new ArrayList<>()).add(image);
                    readings.add(linked("observationId",observation.get("id"),"sourceId",image.id(),"imageHash",observation.get("imageHash"),
                        "sourceBbox",observation.get("sourceBbox"),"readability",observation.get("readability"),"value",value.group(1),
                        "admission",assessment.audit(),"provesContractEffectOrSignature",false));
                }
            }
            out.visual.addAll(readable);
            boolean inspected=!inspectedImages.isEmpty();
            out.checks.add(linked("sourceId",source.id(),"page",source.evidence().page(),"field","合同签订日期",
                "originalText",line,"status",!readable.isEmpty()?"ORIGINAL_FIELD_OBSERVED":inspected?"ORIGINAL_IMAGE_REQUIRES_MANUAL_REVIEW":"ORIGINAL_IMAGE_REQUIRED",
                "visualSourceIds",cn.bidcheck.compat.Java8Api.toList(readable.stream().map(Source::id)),"visualReadings",readings,"businessProof",false));
            if(readable.isEmpty()&&!inspected) {
                Map<String,Object> request=linked("documentId",source.evidence().documentId(),"locationType",source.evidence().locationType(),
                    "location",source.evidence().page(),"task","FIELD","fields",Collections.singletonList("合同签订日期"),
                    "reason","CONTRACT_SIGNING_DATE_ORIGINAL_REQUIRED","obligationId",rule.id(),
                    "sourceId",source.id(),"sourceIds",Collections.singletonList(source.id()),"fieldContext",line);
                if(!e.visualRequests.contains(request))e.visualRequests.add(request);
            }
        }
        if(out.days.size()==1) {
            java.time.LocalDate day=ProofVerifier.date(out.days.keySet().iterator().next());
            Set<String> anchors=new HashSet<>();
            for(Fact fact:identities.values())anchors.addAll(ProofVerifier.strings(fact.attributes().get("recordAnchorSourceIds")));
            for(String id:anchors) {
                Source cover=e.corpus.byId.get(id);
                if(cover==null||!DeclaredPerformanceRows.contains(row,cover))continue;
                Matcher month=Pattern.compile("(?m)^[ \\t#|>*]*(?:合同)?(?:签订|签署|签约)(?:日期|时间)[ \\t]*[:：][ \\t]*(20[0-9]{2})年(0?[1-9]|1[0-2])月[ \\t|。]*$").matcher(cover.evidence().quote());
                if(month.find()&&(Integer.parseInt(month.group(1))!=day.getYear()||Integer.parseInt(month.group(2))!=day.getMonthValue())) {
                    out.differentPrintedMonth=true;
                    out.checks.add(linked("status","SIGNING_MONTH_DIFFERS_FROM_ORIGINAL_DAY","sourceId",id,"page",cover.evidence().page(),
                        "originalText",month.group().trim(),"observedSigningDay",day.toString(),"businessProof",false));
                }
            }
        }
        return out;
    }

    private static boolean requestedScope(Map<String,Object> o,String obligation,Source original) {
        if(!"FIELD".equals(str(o,"type")) || !"FIELD".equals(str(o,"requestedTask"))
                    || !obligation.equals(str(o,"obligationId")) || !Collections.singletonList(original.id()).equals(ProofVerifier.strings(o.get("requestedSourceIds")))
                    || !ProofVerifier.strings(o.get("requestedFields")).contains("合同签订日期")
                    || !original.evidence().documentId().equals(str(o,"documentId"))
                    || !original.evidence().locationType().equals(str(o,"locationType"))
                    || original.evidence().page()!=RunSession.number(o.get("location"),-1)
                    || !(o.get("source") instanceof Map<?,?>)) return false;
        Map<?,?> meta=(Map<?,?>)o.get("source");
        return original.documentHash().equals(meta.get("documentHash")) && original.evidence().documentId().equals(meta.get("documentId"))
            && original.documentVersion()==RunSession.number(meta.get("documentVersion"),-1)
            && original.evidence().page()==RunSession.number(meta.get("page"),-1)
            && !str(o,"imageHash").isEmpty() && str(o,"imageHash").equals(meta.get("renderedSha256"));
    }

    private static boolean binds(Map<String,Object> row,Fact fact,Source page,EvidenceCorpus corpus) {
        Map<String,Object> attrs=fact.attributes();
        List<String> anchors=ProofVerifier.strings(attrs.get("recordAnchorSourceIds"));
        if(anchors.isEmpty()||anchors.contains(page.id()))return false;
        List<Source> refs=new ArrayList<>(); Set<String> ids=new LinkedHashSet<>(anchors);
        ids.add(page.id());
        for(String id:ids) {
            Source s=corpus.byId.get(id); if(s==null || !DeclaredPerformanceRows.contains(row,s))return false;
            refs.add(s);
        }
        return new ContractRecordBindings(row,corpus.byId,ids).matches(fact,refs);
    }
}
