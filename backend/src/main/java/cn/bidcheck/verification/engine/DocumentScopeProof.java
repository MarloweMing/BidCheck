package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;

/** Public boundary for consumers outside the typed verification engine; no model-supplied scope is trusted. */
public final class DocumentScopeProof {
    private DocumentScopeProof() {}
    public static Map<String,Object> currentEvidence(Document bid,Evidence evidence){return forDocument(bid).currentEvidence(evidence);}
    /** Reuse one resolver while checking a collection, so the document is scanned only once. */
    public static Resolver forDocument(Document bid){return new Resolver(bid);}
    public static final class Resolver {
        private final Document document;
        private final DocumentSubjectScope scope;
        private final DocumentCollectionScope collections;
        private final StandaloneCommitmentScope commitments;
        private final MappedSectionScope mappedSections;
        private Resolver(Document document){this.document=document;this.scope=document==null?null:new DocumentSubjectScope(document);this.collections=new DocumentCollectionScope(document);this.commitments=new StandaloneCommitmentScope(document);this.mappedSections=new MappedSectionScope(document);}
        public Map<String,Object> currentEvidence(Evidence evidence){
            if(document==null||evidence==null||!document.id().equals(evidence.documentId()))return result("UNRESOLVED","来源文件不能对应当前投标文件",cn.bidcheck.compat.Java8Api.mapOf());
            List<Page> pages=cn.bidcheck.compat.Java8Api.toList(document.pages().stream().filter(p->p.number()==evidence.page()&&p.locationType().equals(evidence.locationType())));
            if(pages.size()!=1)return result("UNRESOLVED","原文位置不唯一或不存在",cn.bidcheck.compat.Java8Api.mapOf());
            Page page=pages.get(0);String text=Objects.toString(page.text(),""),quote=Objects.toString(evidence.quote(),"");
            if(cn.bidcheck.compat.Java8Api.isBlank(quote))return result("UNRESOLVED","未提供可定位原文",cn.bidcheck.compat.Java8Api.mapOf());
            int at=text.indexOf(quote);if(at<0||text.indexOf(quote,at+1)>=0)return result("UNRESOLVED","原文引用未在原页唯一定位",cn.bidcheck.compat.Java8Api.mapOf());
            String opening=text.substring(0,Math.min(180,text.length()));
            if(VerificationTypes.clean(opening).startsWith("目录")||cn.bidcheck.compat.Java8Extras.lines(text).filter(line->line.matches(".*(?:\\.{3,}|…{2,}|·{3,})\\s*\\d+\\s*")).count()>=2)
                return result("NAVIGATION_ONLY","目录仅用于定位，不能证明正文材料齐全",cn.bidcheck.compat.Java8Api.mapOf());
            DocumentSubjectScope.Context context=scope.context(page,at,at+quote.length());
            if(context.appointmentBlocks().stream().noneMatch(block->block.equals(quote)))
                return result("HISTORICAL_REGION","引用落入历史履历区域或跨越当前与历史区域",context.audit());
            if(context.blocked()||!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION", "CURRENT_DOCUMENT").contains(context.status()))
            {
                Map<String,Object> collection=collections.currentEvidence(page,quote);
                if(!collection.isEmpty())return result("CURRENT_BID_SECTION","封面、当前投标声明、文书目录、印刷页码及项目编号共同确定当前承诺文书范围",collection);
                Map<String,Object> commitment=commitments.currentEvidence(page,quote);
                if(!commitment.isEmpty())return result("CURRENT_BID_SECTION","封面与当前投标函、目录标题、印刷页码及下一同级章节共同确定此页承诺文书范围",commitment);
                Map<String,Object> mapped=mappedSections.currentEvidence(page,quote);
                if(!mapped.isEmpty())return result("CURRENT_BID_SECTION","封面、唯一目录映射、正文标题、印刷页码及下一同级正文边界共同确定当前投标章节",mapped);
                return result(context.status(),"文书项目范围尚未证明为当前项目",context.audit());
            }
            return result(context.status(),"原文唯一定位且处于当前投标文书范围",context.audit());
        }
    }
    private static Map<String,Object> result(String status,String reason,Map<String,Object> sourceScope){
        return cn.bidcheck.compat.Java8Api.mapOf("status", status, "verifiedCurrent", cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION", "CURRENT_DOCUMENT").contains(status), "reason", reason, "sourceScope", sourceScope);
    }
}
