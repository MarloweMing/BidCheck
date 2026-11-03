package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Local prerequisite candidates for visual rules, never observations of a seal/signature. */
final class VisualSubjectBindings {
 private static final Set<String> ROLES=cn.bidcheck.compat.Java8Api.setOf("投标人", "投标单位", "法定代表人", "委托代理人", "授权代表");
 private static final Pattern BIDDER=Pattern.compile("(?m)^[\\t |]*(?:本项目|本次投标)?(投标人|投标单位)[\\t ]*([:：])[\\t ]*([\\p{IsHan}A-Za-z0-9（）()·]{2,100}?(?:有限责任公司|股份有限公司|有限公司|公司))(?=[\\t |（(。；;]|$)");
 private static final Pattern SIGNER=Pattern.compile("(?m)^[\\t |]*(?:本项目|本次投标)?(法定代表人|委托代理人|授权代表)[\\t ]*([:：])[\\t ]*([\\p{IsHan}·]{2,12})(?=[\\t |（(。；;]|$)");
 private VisualSubjectBindings(){}
 static List<Fact> extract(List<Rule> rules,List<Source> sources){
  if(rules.stream().noneMatch(r->r.predicate().equals("VISUAL")&&ROLES.contains(PersonnelRoles.canonical(r.subject()))))return cn.bidcheck.compat.Java8Api.listOf();
  ObjectMapper json=new ObjectMapper();Map<String,Fact> accepted=new LinkedHashMap<>();
  for(Source source:sources){
   String text=source.evidence().quote();
   if(source.index()||source.historical()||source.scopeContext().blocked()||text.matches("(?s).*(?:填写示例|示范文本|仅供参考|空白模板).*"))continue;
   for(Pattern pattern:cn.bidcheck.compat.Java8Api.listOf(BIDDER, SIGNER)){Matcher m=pattern.matcher(text);while(m.find()){
    String role=m.group(1),name=m.group(3);if(name.matches(".*(?:示例|样例|某某|待填写|待填|填写|签字|盖章|公司).*" )&&pattern==SIGNER||name.matches(".*(?:示例|样例|某某|待填写|待填|填写).*"))continue;
    Fact candidate=new Fact("local-visual-subject","RELATION",role,m.group(2),name,name,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),cn.bidcheck.compat.Java8Api.mapOf("object", name, "bindingMethod", pattern==BIDDER?"EXPLICIT_BIDDER_LABEL":"EXPLICIT_SIGNER_LABEL"));
    // The same current-document, explicit local-role and source guards used by model/shared facts apply.
    for(Fact checked:new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(candidate))),cn.bidcheck.compat.Java8Api.listOf(source)).facts()){
     Fact canonical=SharedFactReuse.canonical(checked);accepted.put(canonical.id(),canonical);
    }
   }}
  }
  return cn.bidcheck.compat.Java8Api.listCopyOf(accepted.values());
 }
}
