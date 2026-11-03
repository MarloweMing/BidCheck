package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Literal identifier extraction with an independently resolved current bidder and a bounded owner. */
final class NativeCompanyCodeFacts {
    static final String VERSION="company-credit-code-source-spans-1";
    private static final Pattern CODE=Pattern.compile("(?:统一社会信用代码|社会信用代码|纳税人识别号)[\\t *`|:：]*(?:\\r?\\n[\\t *`|]*)?([0-9A-Z]{18})(?![0-9A-Z])");
    private static final Pattern OWNER=Pattern.compile("(?m)^[\\t #*`|]*(?:名\\s*称|企业名称|单位名称|纳税人名称|投标人名称|企业全称)[\\t *`|:：]+([\\p{IsHan}A-Za-z0-9（）()·]{2,100}?(?:有限责任公司|股份有限公司|有限公司|公司))(?=[\\t *`|。；;]|$)");
    private NativeCompanyCodeFacts(){}
    static List<Fact> extract(Rule rule,EvidenceCorpus corpus,List<Source> selected){
        if(!rule.predicate().equals("SAME_FIELD")||!cn.bidcheck.compat.Java8Api.setOf("creditCode", "统一社会信用代码", "纳税人识别号").contains(rule.field()))return cn.bidcheck.compat.Java8Api.listOf();
        String selector=cn.bidcheck.compat.Java8Api.isBlank(rule.subject())?"投标人":rule.subject();Rule partyRule=new Rule(rule.id()+":parties",rule.description(),rule.predicate(),selector,rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),rule.logic(),rule.children());
        List<Fact> parties=EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(partyRule),corpus.all);Set<String> owners=new LinkedHashSet<>(DomainBindings.subjects(selector,parties,corpus));if(owners.isEmpty()&&!DomainBindings.role(selector)&&!cn.bidcheck.compat.Java8Api.isBlank(selector))owners.add(selector);if(owners.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        Set<String> selectedPages=new HashSet<>();selected.forEach(s->selectedPages.add(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType())));Map<String,Fact> result=new LinkedHashMap<>();
        for(String owner:owners)for(cn.bidcheck.verification.engine.LegalRegistrationProof.Registration registration:LegalRegistrationProof.records(corpus,owner)){
            cn.bidcheck.domain.Domain.Evidence evidence=registration.evidence();if(!selectedPages.contains(EvidenceCorpus.pageKey(evidence.documentId(),evidence.page(),evidence.locationType())))continue;
            List<String> sourceIds=ProofVerifier.strings(registration.binding().get("sourceIds"));add(result,rule,corpus,owner,registration.number(),sourceIds,"SINGLE_CURRENT_REGISTRATION_BLOCK");
        }
        for(String pageKey:selectedPages){cn.bidcheck.domain.Domain.Page page=corpus.pages.get(pageKey);if(page==null)continue;List<Source> sources=cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()).equals(pageKey)));if(sources.isEmpty()||sources.stream().anyMatch(s->s.index()||s.historical()||s.scopeContext().blocked()))continue;
            String text=page.text();if(text.matches("(?s).*(?:填写示例|示范文本|仅供参考|空白模板).*"))continue;Set<String> labelled=new LinkedHashSet<>();Matcher owner=OWNER.matcher(text);while(owner.find())labelled.add(owner.group(1));if(labelled.size()!=1)continue;String company=labelled.iterator().next();if(!owners.contains(company))continue;
            Matcher codes=CODE.matcher(text);while(codes.find())add(result,rule,corpus,company,codes.group(1),cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)),"UNIQUE_LABELLED_OWNER_ON_ORIGINAL_PAGE");
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result.values());
    }
    private static void add(Map<String,Fact> out,Rule rule,EvidenceCorpus corpus,String company,String code,List<String> ids,String binding){
        Fact fact=new Fact("N"+identity(VERSION+rule.field()+company+code+ids).substring(0,20),"FIELD",company,rule.field(),code,code,"","","CURRENT_PROJECT",ids,linked("bindingMethod",binding,"bindingVersion",VERSION,"sourceLabel","统一社会信用代码或纳税人识别号","identifierOwner",company,"scopeBoundary","SAME_CURRENT_BID_COMPANY_ONLY"));
        if(new ProofVerifier().issue(fact,corpus.byId)==null)out.put(fact.id(),fact);
    }
}
