package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Whole-source signature obligations. A mark on one form never completes another form. */
final class SourceSignatureRequirements {
    static final String VERSION="source-signature-requirements-2-evidence-selected-authority";
    private static final ObjectMapper JSON=new ObjectMapper();
    private SourceSignatureRequirements(){}
    static List<Rule> compile(Checkpoint cp,String source,RequirementSourceContext.Context sourceContext,Map<String,Object> context){
        String kind=kind(source);if(cn.bidcheck.compat.Java8Api.isBlank(kind)||sourceContext.identity().isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.listOf(new Rule("SOURCE-SIGN-"+identity(source).substring(0,16),kind.equals("AUTHORITY")?"文件签署权及原文免交条件":"规定格式逐份签署位置","SECTION","","sourceSignature","","","",
                linked("sourceSignatureVersion",VERSION,"signatureKind",kind,"deterministicFamily","SOURCE_SIGNATURE_POSITIONS","sourceCheckpoint",JSON.convertValue(cp,Map.class),"requirementSourceContext",sourceContext.map()),cp.source().quote(),"",cn.bidcheck.compat.Java8Api.listOf()));
    }
    static boolean applies(Rule r){return VERSION.equals(r.parameters().get("sourceSignatureVersion"))&&r.predicate().equals("SECTION");}
    /** Coverage approval is limited to this executor's exact original obligation and bounded references. */
    static boolean compilationComplete(Rule rule,String source){
        if(!applies(rule)||!rule.children().isEmpty()||!cn.bidcheck.compat.Java8Api.isBlank(rule.subject())||!rule.field().equals("sourceSignature")||!cn.bidcheck.compat.Java8Api.isBlank(rule.logic()))return false;
        Checkpoint cp;try{cp=JSON.convertValue(rule.parameters().get("sourceCheckpoint"),Checkpoint.class);}catch(RuntimeException ex){return false;}
        if(cp==null||cp.source()==null||!rule.sourceQuote().equals(cp.source().quote())||cn.bidcheck.compat.Java8Api.isBlank(kind(source))||!kind(source).equals(str(rule.parameters(),"signatureKind")))return false;
        if(!(rule.parameters().get("requirementSourceContext") instanceof Map<?,?>)||!(((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity") instanceof Map<?,?>))return false;
        String doc=Objects.toString(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentId"),""),hash=Objects.toString(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentHash"),"");
        if(!doc.equals(cp.source().documentId())||cn.bidcheck.compat.Java8Api.isBlank(hash)||!Objects.equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("location"),cp.source().page())||!cp.source().locationType().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("locationType")))return false;
        boolean original=identity(rule.sourceQuote()).equals(Objects.toString(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("sourceHash"),""))
                &&RunSession.number(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("end"),-1)-RunSession.number(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("start"),-1)==rule.sourceQuote().length()
                &&RunSession.number(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("start"),-1)>=0&&RunSession.number(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentVersion"),0)>0;
        List<Map<String,Object>> spans=RuleEvaluator.maps(((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("sources"));Set<String> parts=new LinkedHashSet<>();parts.add(rule.sourceQuote());
        for(Map<String,Object> span:spans){String quote=str(span,"sourceQuote"),type=str(span,"kind");
            if(!doc.equals(str(span,"documentId"))||!hash.equals(str(span,"documentHash"))||!Objects.equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentVersion"),span.get("documentVersion"))||cn.bidcheck.compat.Java8Api.isBlank(quote)||RunSession.number(span.get("end"),-1)-RunSession.number(span.get("start"),-1)!=quote.length())return false;
            if(type.equals("OBLIGATION")&&(!quote.equals(rule.sourceQuote())||!Objects.equals(span.get("location"),cp.source().page())))return false;
            if(cn.bidcheck.compat.Java8Api.setOf("REFERENCE_CLAUSE", "SCOPED_PROOF", "SCOPED_SCORING_CONTINUATION", "SCOPED_BOOK_CONDITION").contains(type)&&!rule.sourceQuote().contains(quote))parts.add(quote);
        }
        return original&&clean(String.join("\n",parts)).equals(clean(source));
    }
    private static String kind(String source){String text=clean(source).replace('（','(').replace('）',')');
        if(text.matches("(?:文件签署权)?按照招标文件规定填报并提交。见第[一二三四五六七八九十0-9]+章中《法定代表人(?:\\(负责人\\))?身份证明》及《法定代表人(?:\\(负责人\\))?授权委托书》\\(法定代表人直接签署投标文件时(?:无需|无须)提交\\)。?"))return "AUTHORITY";
        if(text.contains("投标文件格式")&&text.contains("凡出现投标人单位落款的地方")&&text.contains("必须加盖单位公章")&&text.contains("或者其委托代理人签字"))return "ALL_POSITIONS";
        return "";
    }
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        Checkpoint cp;try{cp=JSON.convertValue(rule.parameters().get("sourceCheckpoint"),Checkpoint.class);}catch(RuntimeException ex){return evaluator.unknown(rule,"SIGNATURE_SOURCE_REQUIRED","签署要求尚未绑定冻结来源",cn.bidcheck.compat.Java8Api.listOf());}
        Document tender=tender(cp,evaluator.context);String kind=kind(rule.sourceQuote());
        if(tender==null||cn.bidcheck.compat.Java8Api.isBlank(kind)||!kind.equals(kind(cp.source().quote()))||!kind.equals(str(rule.parameters(),"signatureKind"))||!clean(rule.sourceQuote()).contains(clean(cp.source().quote())))return evaluator.unknown(rule,"SIGNATURE_SOURCE_REQUIRED","签署要求或免交条件尚未对应本次冻结招标原文",cn.bidcheck.compat.Java8Api.listOf());
        Decision result=kind.equals("AUTHORITY")?authority(rule,cp,evaluator):positions(rule,cp,tender,evaluator,false);
        org.slf4j.LoggerFactory.getLogger(SourceSignatureRequirements.class).info("bidcheck.signature_inventory checkpointId={} ruleId={} kind={} status={} gaps={}",cp.id(),rule.id(),kind,result.status(),cn.bidcheck.compat.Java8Api.toList(result.gaps().stream().map(Gap::code)));
        return result;
    }
    private static Decision authority(Rule rule,Checkpoint cp,RuleEvaluator evaluator){
        Rule identification=TenderFormAudit.namedRule(cp,rule.sourceQuote(),"法定代表人身份证明",evaluator.context,false,rule.id()+":identity");
        Rule authorization=TenderFormAudit.namedRule(cp,rule.sourceQuote(),"法定代表人授权委托书",evaluator.context,false,rule.id()+":authorization");
        Decision identity=evaluator.evaluate(identification),authority=evaluator.evaluate(authorization);
        // A complete submitted authorization discharges this alternative. Do not open a
        // separate direct-signature investigation after the selected branch is proved.
        Decision option=authority.status().equals("PASS")?authority:RuleEvaluator.combine(rule.id()+":authority-option","代理人授权或法定代表人直接签署免交","ANY",cn.bidcheck.compat.Java8Api.listOf(authority, directSignatureExemption(rule,cp,evaluator)));
        Decision combined=RuleEvaluator.combine(rule.id(),rule.description(),"ALL",cn.bidcheck.compat.Java8Api.listOf(identity, option));
        Map<String,Object> proof=new LinkedHashMap<>(combined.proof());proof.putAll(linked("executor",VERSION,"proofScope","IDENTITY_AND_AUTHORITY_WITH_SOURCE_EXEMPTION","authorizationUnconditionallyRequired",false,"sourceException","法定代表人直接签署投标文件时无需提交"));
        return copy(combined,proof);
    }
    private static Decision directSignatureExemption(Rule rule,Checkpoint cp,RuleEvaluator evaluator){
        List<Source> declarations=cn.bidcheck.compat.Java8Api.toList(evaluator.corpus.all.stream().filter(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked())
                .filter(s->clean(s.evidence().quote()).matches("(?s).*(?:本次|本项目)投标文件由(?:本单位)?法定代表人[\\p{IsHan}·]{0,12}直接签署[^。；;]{0,15}(?:。|；|;|$).*"))
                .filter(EnterpriseSubjectBindings::formalDocument));
        if(declarations.isEmpty())return evaluator.unknown(rule,"DIRECT_SIGNER_EXEMPTION_PROOF_REQUIRED","授权书免交只适用于法定代表人实际直接签署；材料缺失或打印姓名不能证明适用",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("absenceProvesExemption", false));
        Document tender=tender(cp,evaluator.context);Decision direct=positions(rule,cp,tender,evaluator,true);
        Map<String,Object> proof=new LinkedHashMap<>(direct.proof());proof.put("directSigningDeclarations",cn.bidcheck.compat.Java8Api.toList(declarations.stream().map(Source::evidence)));proof.put("exemptionRequiresActualSignaturePositions",true);
        return new Decision(rule.id()+":direct-signature","法定代表人直接签署免交分支",direct.status(),direct.reason(),direct.evidences(),direct.gaps(),proof);
    }
    private static Decision positions(Rule rule,Checkpoint cp,Document tender,RuleEvaluator evaluator,boolean directOnly){
        SourceRequirementInventory.Inventory inventory=SourceRequirementInventory.format(tender);List<Decision> decisions=new ArrayList<>();List<SourceRequirementInventory.Member> members=new ArrayList<>();List<String> issues=new ArrayList<>(inventory.issues());
        cn.bidcheck.verification.engine.RequirementSourceContext.Context sourceContext=RequirementSourceContext.resolve(cp,cp.source().quote(),evaluator.context);
        cn.bidcheck.verification.engine.RequirementReferences.Expansion references=RequirementReferences.expand(cp,cp.source().quote(),sourceContext,evaluator.context);issues.addAll(references.issues());
        for(cn.bidcheck.verification.engine.SourceRequirementInventory.Member member:inventory.members()){
            if(member.name().equals("承诺书")){
                cn.bidcheck.verification.engine.SourceRequirementInventory.Inventory commitments=SourceRequirementInventory.commitments(tender);members.addAll(commitments.members());issues.addAll(commitments.issues());
            }else members.add(member);
        }
        if(members.isEmpty())issues.add("未展开任何签署格式");
        List<Map<String,Object>> scopes=new ArrayList<>();
        for(cn.bidcheck.verification.engine.SourceRequirementInventory.Member member:members){String name=member.name();
            if(directOnly&&TenderFormAudit.canonicalForm(name).equals("法定代表人授权委托书"))continue;
            Rule base=TenderFormAudit.namedRule(cp,rule.sourceQuote(),name,evaluator.context,false,rule.id()+":form:"+identity(name).substring(0,12));
            Map<String,Object> p=new LinkedHashMap<>(base.parameters());p.put("signaturePositionsOnly",true);p.put("legalRepresentativeDirectOnly",directOnly);p.put("formInventorySource",member.sources());
            Rule form=new Rule(base.id(),name+"签署位置",base.predicate(),base.subject(),base.field(),base.operator(),base.expected(),base.unit(),p,base.sourceQuote(),base.logic(),base.children());
            // An unresolved template is an exact missing source task, not a completed material check.
            Decision decision=base.predicate().equals("UNSUPPORTED")?evaluator.unknown(form,"FORM_TEMPLATE_REFERENCE_REQUIRED","尚未定位该格式签署位置原文："+name,cn.bidcheck.compat.Java8Api.listOf()):evaluator.evaluate(form);
            scopes.add(linked("name",name,"source",member.sources(),"ruleId",form.id(),"status",decision.status()));decisions.add(decision);
        }
        if(!issues.isEmpty())decisions.add(evaluator.unknown(rule,"SIGNATURE_INVENTORY_COVERAGE_REQUIRED","签署格式清单尚未完整闭合："+String.join("；",issues),cn.bidcheck.compat.Java8Api.listOf()));
        // Referenced correction and substitute-seal conditions cannot vanish from the umbrella rule.
        String source=RequirementReferences.requirementText(cp.source().quote(),references.context());
        String unresolvedPolicy=uncoveredPolicy(source);
        if(!cn.bidcheck.compat.Java8Api.isBlank(unresolvedPolicy))decisions.add(evaluator.unknown(rule,"SIGNATURE_SOURCE_POLICY_REQUIRED","签署原文另有条件尚未完成映射，已核签章不能代表这些条件完成",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("uncoveredSourcePolicy", unresolvedPolicy)));
        if(source.contains("涂改")||source.contains("行间插字")||source.contains("删除"))decisions.add(evaluator.unknown(rule,"ALTERATION_SIGNATURE_COVERAGE_REQUIRED","引用条款还要求涂改、插字或删除处的签署确认，需完成相应原图位置检查",cn.bidcheck.compat.Java8Api.listOf()));
        if(source.contains("业务章")||source.contains("专用章"))decisions.add(evaluator.unknown(rule,"SUBSTITUTE_SEAL_APPLICABILITY_REQUIRED","需从指定签章位置确认是否使用业务章或专用章；若使用，则核对公章授权，不能因一般盖章已完成而漏掉该条件",cn.bidcheck.compat.Java8Api.listOf()));
        Decision combined=RuleEvaluator.combine(rule.id(),rule.description(),"ALL",decisions);Map<String,Object> proof=new LinkedHashMap<>(combined.proof());
        proof.putAll(linked("executor",VERSION,"proofScope","ALL_REFERENCED_FORM_SIGNATURE_POSITIONS","inventorySources",inventory.sources(),"formCount",members.size(),"forms",scopes,"inventoryIssues",issues,"legalRepresentativeDirectOnly",directOnly,"doesNotProveAllFormContents",true,"signatureAuthenticityCheckRequired",false));return copy(combined,proof);
    }
    /** Only the closed positional sentence is discharged by form marks; an extra duty is retained. */
    static String uncoveredPolicy(String source){
        if("AUTHORITY".equals(kind(source)))return "";
        String text=clean(source).replace('（','(').replace('）',')');
        String representative="法定代表人(?:\\(负责人\\)|/负责人)?";
        String sentence="第[一二三四五六七八九十0-9]+章[“\\\"]投标文件格式[”\\\"]中凡出现投标人单位落款的地方，必须加盖单位公章；凡出现投标人"+representative+"或者其委托代理人签字的地方，必须由投标人"+representative+"或者其委托代理人签字。";
        // No wildcard consumes the remainder: source-specific ancillary conditions stay visible.
        text=text.replaceAll(sentence,"");
        return text.replaceAll("^[\\s/。；;]+|[\\s/。；;]+$","");
    }
    private static Document tender(Checkpoint cp,Map<String,Object> context){
        if(cp==null||cp.source()==null||!(context.get("tenderDocuments") instanceof Collection<?>))return null;
        List<Document> matches=cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (context.get("tenderDocuments"))).stream().filter(Document.class::isInstance).map(Document.class::cast).filter(d->d.role().equals("TENDER")&&d.id().equals(cp.source().documentId()))
                .filter(d->d.pages().stream().anyMatch(p->p.number()==cp.source().page()&&p.locationType().equals(cp.source().locationType())&&p.text().contains(cp.source().quote()))));return matches.size()==1?matches.get(0):null;
    }
    private static Decision copy(Decision d,Map<String,Object> proof){return new Decision(d.id(),d.description(),d.status(),d.reason(),d.evidences(),d.gaps(),proof);}
}
