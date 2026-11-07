package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.verification.engine.PersonnelRoles;
import cn.bidcheck.verification.engine.RecoveryTargets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.service.BidService.text;
import static cn.bidcheck.service.CheckResolution.*;

/** Field matching identifies possible inputs. These checks discard changes that cannot
 * affect the executable target under its current, server-observed prerequisites. */
final class RecoveryEventRelevance {
    private static final Set<String> MANAGEMENT_TYPES=new HashSet<>(Arrays.asList(
        "职业健康安全管理体系认证","环境管理体系认证","质量管理体系认证"));
    private static final Pattern QUERY_CATEGORY=Pattern.compile("(?:认证项目|认证类别)[ \\t:：|]+([^\\n\\r|]+)");
    private static final Pattern CERTIFICATE_TITLE=Pattern.compile("(?m)^[ \\t]*(?:#{1,6}[ \\t]*)?(?:\\*\\*)?(职业健康安全管理体系认证|环境管理体系认证|质量管理体系认证)证书(?:\\*\\*)?[ \\t]*$");
    private RecoveryEventRelevance(){}

    static Set<String> targetsBlockedByTenderStandard(Finding finding){
        Map<String,Object> a=atomic(finding);
        Set<String> blockedParents=new HashSet<>();
        for(Map<String,Object> condition:maps(a.get("conditions"))){
            Map<String,Object> proof=map(condition.get("proof"));
            Map<String,Object> base=map(proof.get("baseConfiguration"));
            if("source-extra-personnel-score-1".equals(text(proof,"executor"))
                    &&"UNDETERMINED".equals(text(base,"status"))
                    &&maps(base.get("gaps")).stream().anyMatch(g->"SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED".equals(text(g,"code"))))
                blockedParents.add(text(condition,"id"));
        }
        if(blockedParents.isEmpty())return Collections.emptySet();
        Set<String> blocked=new HashSet<>();
        RecoveryTargets.readDependencies(map(a.get("plan")).get("rules")).forEach((id,read)->{
            if(blockedParents.contains(text(read,"parentId")))blocked.add(id);
        });
        return blocked;
    }

    static String excluded(Map<String,Object> binding,Map<String,Object> change){
        Map<String,Object> target=map(binding.get("target")),fact=map(change.get("fact"));
        if(Boolean.TRUE.equals(binding.get("tenderStandardRequired"))
                &&Arrays.asList("PERSON_ROLE","CERTIFICATE","MONTHS").contains(text(fact,"kind")))
            return "TENDER_STANDARD_STILL_REQUIRED";
        Map<String,Object> p=map(target.get("parameters"));
        if("RELATION".equals(text(target,"predicate"))&&"APPOINT".equals(text(p,"compoundReadKind"))
                &&"PERSON_ROLE".equals(text(fact,"kind"))&&"CURRENT_PROJECT".equals(text(fact,"scope"))
                &&"姓名".equals(text(fact,"field"))&&PersonnelRoles.sameObservationRole(text(target,"subject"),text(fact,"subject"))){
            List<String> known=RecoveryGapKeys.strings(binding.get("resolvedSubjects"));
            Map<String,Object> attributes=map(fact.get("attributes"));
            if(known.size()==1&&known.get(0).equals(text(fact,"value"))
                    &&Arrays.asList("employer","appointingUnit","object","委派单位","所属单位").stream().allMatch(k->text(attributes,k).isEmpty()))
                return "APPOINTMENT_PERSON_ALREADY_RESOLVED";
        }
        String category=text(p,"certificateProofCategory");
        if(category.isEmpty())category=managementCategory(text(p,"category"));
        if("CERTIFICATE".equals(text(fact,"kind")) && MANAGEMENT_TYPES.contains(category)
                && otherCertificateCategory(change,fact,category))return "DIFFERENT_PRINTED_CERTIFICATE_CATEGORY";
        if("MATERIAL".equals(text(target,"predicate"))&&"MATERIAL".equals(text(fact,"kind"))
                &&MANAGEMENT_TYPES.contains(category)&&otherQueryCategory(change,fact,category))
            return "DIFFERENT_PRINTED_QUERY_CATEGORY";
        return "";
    }

    /** One original certificate of a different management system cannot change this
     * rule. Do not infer irrelevance from a model label alone: the same holder, number
     * and unique printed certificate heading must occur in every cited original. */
    private static boolean otherCertificateCategory(Map<String,Object> change,Map<String,Object> fact,String required){
        Map<String,Object> attributes=map(fact.get("attributes"));
        String category=managementCategory(text(attributes,"category")),number=text(attributes,"number"),holder=text(fact,"subject");
        List<String> refs=RecoveryGapKeys.strings(fact.get("sourceIds"));
        if(!MANAGEMENT_TYPES.contains(category)||category.equals(required)||number.isEmpty()||holder.isEmpty()||refs.isEmpty())return false;
        Pattern printedNumbers=Pattern.compile("证书(?:编号|号)[ \\t:：|]*([A-Za-z0-9][A-Za-z0-9()（）/.-]*)");
        Pattern printedHolder=Pattern.compile("(?:兹证明|获证(?:组织|企业)(?:名称)?)[\\s#*：:|]*"+Pattern.quote(holder)+"(?=$|[\\s|，,。;；])");
        Set<String> verified=new HashSet<>();
        for(Map<String,Object> source:maps(change.get("sources"))){
            String id=text(source,"id");if(!refs.contains(id))continue;
            Map<String,Object> e=map(source.get("evidence"));
            if(text(source,"documentHash").isEmpty()||!(source.get("documentVersion") instanceof Number)
                    ||((Number)source.get("documentVersion")).intValue()<1||text(source,"parserFingerprint").isEmpty()
                    ||text(e,"documentId").isEmpty()||!"PAGE".equals(text(e,"locationType"))
                    ||!(e.get("page") instanceof Number)||((Number)e.get("page")).intValue()<1)return false;
            Map<String,Object> witness=map(source.get("pageCertificateWitness"));
            if(!"frozen-page-certificate-witness-1".equals(text(witness,"version"))
                    ||!"CURRENT_SERVER_PROOF".equals(text(witness,"authority"))||!Boolean.FALSE.equals(witness.get("businessProof"))
                    ||!text(e,"documentId").equals(text(witness,"documentId"))
                    ||!text(source,"documentHash").equals(text(witness,"documentHash"))
                    ||!Objects.equals(source.get("documentVersion"),witness.get("documentVersion"))
                    ||!Objects.equals(e.get("page"),witness.get("page"))
                    ||!text(e,"locationType").equals(text(witness,"locationType"))
                    ||!text(source,"parserFingerprint").equals(text(witness,"parserFingerprint"))
                    ||!text(witness,"pageTextHash").matches("[a-fA-F0-9]{64}")
                    ||!category.equals(text(witness,"managementCategory"))||!number.equals(text(witness,"number"))
                    ||!holder.equals(text(witness,"holder")))return false;
            String quote=text(e,"quote").replace("\r","");
            Matcher headings=CERTIFICATE_TITLE.matcher(quote);
            Set<String> names=new HashSet<>();while(headings.find())names.add(headings.group(1));
            Set<String> numbers=new HashSet<>();Matcher numbered=printedNumbers.matcher(quote);while(numbered.find())numbers.add(numbered.group(1));
            if(!Collections.singleton(category).equals(names)||!Collections.singleton(number).equals(numbers)||!printedHolder.matcher(quote).find())return false;
            verified.add(id);
        }
        return verified.containsAll(refs);
    }

    private static String managementCategory(String value){return value.trim().replaceFirst("证书$","");}

    private static boolean otherQueryCategory(Map<String,Object> change,Map<String,Object> fact,String required){
        List<String> refs=RecoveryGapKeys.strings(fact.get("sourceIds"));
        if(refs.isEmpty())return false;
        Map<String,String> classified=new HashMap<>();
        for(Map<String,Object> source:maps(change.get("sources"))){
            String id=text(source,"id");if(!refs.contains(id))continue;
            Map<String,Object> e=map(source.get("evidence"));
            if(text(source,"documentHash").isEmpty()||!(source.get("documentVersion") instanceof Number)
                    ||((Number)source.get("documentVersion")).intValue()<1||text(source,"parserFingerprint").isEmpty()
                    ||text(e,"documentId").isEmpty()||!"PAGE".equals(text(e,"locationType"))
                    ||!(e.get("page") instanceof Number)||((Number)e.get("page")).intValue()<1)return false;
            String name=printedCategory(text(e,"quote"));
            if(name.isEmpty()||name.equals(required)||classified.put(id,name)!=null)return false;
        }
        return classified.keySet().containsAll(refs)&&new HashSet<>(classified.values()).size()==1;
    }

    private static String printedCategory(String original){
        Set<String> names=new HashSet<>();
        Matcher m=QUERY_CATEGORY.matcher(original.replace("*","").replace("`",""));
        while(m.find()){
            String v=m.group(1).trim().replaceFirst("^(?:建设施工行业|中国)","").replaceFirst("证书$","");
            if(v.isEmpty()||v.matches("[-—–]+|[（(]?(?:下拉菜单|下拉选择|请选择)[）)]?"))continue;
            if(!MANAGEMENT_TYPES.contains(v))return "";
            names.add(v);
        }
        return names.size()==1?names.iterator().next():"";
    }
}
