package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Carry a verified missing-volume result from a material checkpoint to its dependent price checks. */
final class RunVolumeDependencies {
    private static final String MISSING="BID_VOLUME_NOT_UPLOADED";

    static Map<String,List<String>> inventory(List<Finding> findings) {
        Map<String,List<String>> missing=new TreeMap<>();
        for(Finding finding:findings) for(Map<String,Object> condition:maps(atomic(finding).get("conditions"))) {
            String volume=Objects.toString(map(condition.get("proof")).get("requestedVolume"),"");
            if(!volume.isEmpty() && maps(condition.get("gaps")).stream().anyMatch(g->MISSING.equals(g.get("code")))) {
                List<String> origins=missing.computeIfAbsent(volume,v->new ArrayList<>());
                if(!origins.contains(finding.checkpoint().id()))origins.add(finding.checkpoint().id());
            }
        }
        return missing;
    }

    static Finding apply(Finding finding,Map<String,Object> inventory) {
        if(!"UNDETERMINED".equals(finding.evaluation().status()))return finding;
        Set<String> origins=new LinkedHashSet<>(), titles=new LinkedHashSet<>();
        inventory.forEach((name,ids)->{
            if(name.matches("(?:第[一二三四五六七八九十百0-9]+分册\\s*)?报价文件")&&ids instanceof Collection<?> && !((Collection<?>)ids).isEmpty()){
                ((Collection<?>)ids).forEach(id->origins.add(String.valueOf(id)));titles.add(name);
            }
        });
        if(origins.isEmpty())return finding;
        if(titles.size()>1)titles.remove("报价文件");
        String volume=String.join("》、《",titles);
        Map<String,Object> atomic=atomic(finding);
        List<Map<String,Object>> gaps=new ArrayList<>(maps(atomic.get("gaps")));
        if(gaps.stream().noneMatch(g->"PRICE_EVIDENCE_REQUIRED".equals(g.get("code"))))return finding;
        Map<String,Object> gap=gaps.stream().filter(g->MISSING.equals(g.get("code"))).findFirst().orElse(null);
        if(gap!=null&&volume.equals(gap.get("requestedVolume"))&&new ArrayList<>(origins).equals(gap.get("volumeCheckpointIds")))return finding;
        if(gap==null){gap=new LinkedHashMap<>();gaps.add(gap);}
        gap.put("code",MISSING);gap.put("description","本项需要报价金额或清单，当前已上传文件未覆盖报价分册。请补充报价文件。");
        gap.put("requiredInput",volume);gap.put("requestedVolume",volume);gap.put("resumeTrigger","INPUT_OR_EVIDENCE_CHANGED");
        gap.put("volumeCheckpointIds",new ArrayList<>(origins));
        atomic.put("gaps",gaps);
        Map<String,Object> assessment=new LinkedHashMap<>(finding.assessment());
        assessment.put("atomicCheck",atomic);
        return FocusedCheckEvaluator.publishedFinding(finding,assessment);
    }
    private RunVolumeDependencies(){}
}
