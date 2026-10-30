package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Recognition of a submitted official query screenshot, not authentication of its contents. */
final class PersonnelQueryScreenshot {
    private static final Set<String> OFFICIAL_HOSTS=Set.of("cx.mem.gov.cn","cert.mohrss.gov.cn","zscx.osta.org.cn","jzsc.mohurd.gov.cn","zjt.hainan.gov.cn");
    private static final Pattern URL=Pattern.compile("(?i)https?://[^\\s<>|，。；、）)]+");
    record Proof(List<Source> sources,List<Evidence> evidence,List<Map<String,Object>> observations){}
    static Proof find(Rule parent,Fact certificate,RuleEvaluator evaluator){
        String name=certificate.subject(),number=str(certificate.attributes(),"number"),category=str(certificate.attributes(),"category");List<Source> sources=new ArrayList<>();List<Evidence> evidence=new ArrayList<>();List<Map<String,Object>> receipts=new ArrayList<>();Map<String,CertificateEvidenceRecords.ScopeResolver> scopes=new HashMap<>();
        for(Source source:evaluator.corpus.all){if(!CertificateEvidenceRecords.usable(source,evaluator.corpus,scopes))continue;String text=source.evidence().quote();
            if(!fields(text,name,number,category)||!officialUrl(text))continue;
            if(revoked(text))return new Proof(List.of(),List.of(),List.of());
            String obligation=parent.id()+"-QUERY-"+identity(name+"|"+number).substring(0,12);boolean accepted=false;
            for(Map<String,Object> observation:RuleEvaluator.maps(evaluator.context.get("visualObservations"))){
                if(str(observation,"id").isBlank()||!"FIELD".equals(str(observation,"type"))||!str(observation,"label").startsWith("查询截图")||!VisualObservationPolicy.assess(observation).valueUsable()||!VisualObservationPolicy.validSourceBounds(observation))continue;
                if(!source.evidence().documentId().equals(str(observation,"documentId"))||RunSession.number(observation.get("location"),RunSession.number(observation.get("page"),-1))!=source.evidence().page())continue;
                if(!(observation.get("source") instanceof Map<?,?> origin)||!source.documentHash().equals(origin.get("documentHash"))||!Objects.equals(source.documentVersion(),origin.get("documentVersion"))||!source.evidence().documentId().equals(origin.get("documentId"))||RunSession.number(origin.get("page"),-1)!=source.evidence().page())continue;
                String image=str(observation,"imageHash");if(image.isBlank()||!image.equals(origin.get("renderedSha256")))continue;
                String observed=str(observation,"value");if(observed.isBlank())observed=str(observation,"observedValue");if(!fields(observed,name,number,category)||!officialUrl(observed))continue;if(revoked(observed))return new Proof(List.of(),List.of(),List.of());
                accepted=true;receipts.add(linked("observationId",str(observation,"id"),"sourceId",source.id(),"imageHash",image,"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"page",source.evidence().page(),"proofScope","SOURCE_BOUND_OFFICIAL_QUERY_SCREENSHOT_SUBMISSION","authenticityAssessed",false));
                Evidence original=source.evidence();evidence.add(new Evidence(original.documentId(),original.documentName(),original.page(),original.locationType(),observed,"image:"+image,0));
            }
            if(accepted)sources.add(source);else if(evaluator.visualRequests.stream().noneMatch(r->obligation.equals(str(r,"obligationId"))&&Objects.equals(r.get("location"),source.evidence().page())))evaluator.visualRequests.add(linked("obligationId",obligation,"documentId",source.evidence().documentId(),"locationType",source.evidence().locationType(),"location",source.evidence().page(),"task","FIELD","fields",List.of("查询截图原文（须包含页面为查询截图的可见特征、姓名、证书类别、证书编号及官方查询网址；自述文字不算截图）"),"expectedSubjects",List.of(name),"sourceIds",List.of(source.id()),"question","读取同一证书官方查询截图，核实它是截图页面并原样读取字段，不判断真伪或打分"));
        }return new Proof(List.copyOf(sources),List.copyOf(evidence),List.copyOf(receipts));
    }
    private static boolean fields(String text,String name,String number,String category){if(!text.matches("(?s).*(?:查询截图|查询结果|查询详情|证书查询).*" )||!text.contains(category))return false;
        Matcher owner=Pattern.compile("(?:姓名|持证人)[:：\\s|]*([^\\s|，,。；;:：]{2,12})").matcher(text),serial=Pattern.compile("证书(?:编号|号码|号)[:：\\s|]*([A-Za-z0-9][A-Za-z0-9()（）/-]{3,79})").matcher(text);Set<String> names=new HashSet<>(),numbers=new HashSet<>();while(owner.find())names.add(owner.group(1));while(serial.find())numbers.add(serial.group(1));return names.equals(Set.of(name))&&numbers.equals(Set.of(number));}
    private static boolean revoked(String text){return text.matches("(?s).*(?:状态|有效性)[:：\\s]*(?:已撤销|已注销|失效|过期|无效).*");}
    static boolean officialUrl(String text){Matcher url=URL.matcher(text);boolean found=false;while(url.find())try{URI uri=URI.create(url.group());if(uri.getUserInfo()!=null||!Set.of("https","http").contains(uri.getScheme().toLowerCase(Locale.ROOT))||!OFFICIAL_HOSTS.contains(Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT)))return false;found=true;}catch(IllegalArgumentException invalid){return false;}return found;}
}
