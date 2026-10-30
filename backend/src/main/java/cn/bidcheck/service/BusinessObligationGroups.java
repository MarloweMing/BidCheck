package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.text.Normalizer;
import java.util.*;

/** A read-only presentation index. It never deletes rules or shares a decision between them. */
public final class BusinessObligationGroups {
    private BusinessObligationGroups() { }

    public static List<Map<String,Object>> summarize(List<Finding> findings) {
        return summarize(findings,null);
    }

    public static List<Map<String,Object>> summarize(List<Finding> findings,Document tender) {
        BusinessObligationIdentity index=new BusinessObligationIdentity(tender);
        Map<String,BusinessObligationIdentity.Signature> signatures=new LinkedHashMap<>();
        Map<String,List<Finding>> groups=new LinkedHashMap<>();
        for(Finding finding:findings){
            var signature=index.signature(finding);signatures.put(finding.id(),signature);
            groups.computeIfAbsent(tender==null?identity(finding):signature.key(),key->new ArrayList<>()).add(finding);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(var entry:groups.entrySet()) {
            List<Finding> members=entry.getValue();
            Set<Evidence> sources=new LinkedHashSet<>();
            members.forEach(f->{if(f.checkpoint().source()!=null)sources.add(f.checkpoint().source());});
            var signature=signatures.get(members.get(0).id());boolean merged=members.size()>1;
            result.add(BidService.fields("id",BidService.stableId((tender==null?"business-obligation-v1:":BusinessObligationIdentity.VERSION+":")+entry.getKey()),
                "title",members.get(0).checkpoint().title(),"resultIds",members.stream().map(Finding::id).toList(),
                "sourceCount",sources.size(),"sources",List.copyOf(sources),"merged",merged,
                "identityVersion",tender==null?"business-obligation-v1":BusinessObligationIdentity.VERSION,
                "mergeMethod",merged?(tender==null?"SAME_SOURCE_EXACT":"FROZEN_TENDER_EQUIVALENT"):"UNMERGED",
                "identityDimensions",signature.dimensions(),"occurrences",members.stream().map(f->signatures.get(f.id()).occurrence()).toList(),
                "explanation",merged?(tender==null?"同一来源位置的严格等价展示；缺少冻结文档时不跨章节合并。":"已核对冻结招标原文，义务、适用主体、标段、资格/评分用途及全部限定条件相同；允许合并展示，逐项结论仍分别保留。"):signature.reason(),
                "executionReusable",false,"evidenceInvestigationReusable",merged&&tender!=null,"businessDecisionReusable",false,
                "boundary","冻结原文证明完全等价时可以只执行一次证据调查；每个原检查项仍用自己的规则树重新计算，结论及人工记录分别保留。"));
        }
        return List.copyOf(result);
    }

    /** Explain every proposed identity without changing, deleting or executing a checkpoint. */
    public static Map<String,Object> plan(List<Finding> findings,Document tender){
        BusinessObligationIdentity index=new BusinessObligationIdentity(tender);
        List<Map<String,Object>> items=new ArrayList<>();for(Finding finding:findings){var s=index.signature(finding);
            items.add(BidService.fields("resultId",finding.id(),"checkpointId",finding.checkpoint().id(),"eligible",s.eligible(),
                "candidateIdentity",s.eligible()?BidService.stableId(BusinessObligationIdentity.VERSION+":"+s.key()):"",
                "reason",s.reason(),"dimensions",s.dimensions(),"occurrence",s.occurrence()));}
        return BidService.fields("identityVersion",BusinessObligationIdentity.VERSION,"items",List.copyOf(items),"executionReusable",false,
            "evidenceInvestigationReusable",tender!=null,"businessDecisionReusable",false);
    }

    private static String identity(Finding finding) {
        Checkpoint c=finding.checkpoint();
        // Full-text comparison groups, empty expectations and unanchored clauses have no safe merge key.
        if(c.source()==null||canonical(c.expected()).isBlank()||"FULL_TEXT_REVIEW".equals(c.type())
                ||finding.assessment()!=null&&finding.assessment().containsKey("consistencyGroupId"))return "unique:"+finding.id();
        // Include the source context. Similar titles alone cannot prove matching lots, members or exceptions.
        return String.join("\u001f",Objects.toString(c.source().documentId(),""),Objects.toString(c.source().locationType(),""),
            Integer.toString(c.source().page()),Objects.toString(c.source().anchor(),""),canonical(c.category()),
            canonical(c.type()),canonical(c.field()),canonical(c.operator()),canonical(c.unit()),
            Boolean.toString(c.required()),canonical(c.expected()),canonical(c.source().quote()));
    }

    static String canonical(String value) {
        String text=Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFKC).strip();
        // Normalize a textual table label only; never strip numeric punctuation or a substantive clause.
        text=text.replaceFirst("^([\\p{IsHan}]{2,16})[\\t:：]+", "$1:");
        return text.replaceAll("\\s+", " ").replaceAll("(?<=:) +", "").strip().replaceAll("[。；;]+$", "");
    }
}
