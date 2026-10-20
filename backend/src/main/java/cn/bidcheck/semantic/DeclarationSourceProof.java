package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import java.util.*;
import java.util.regex.*;

/** A declaration is a document with a heading and a printed issuer, not a person/employment edge.
 * This proof establishes only written authorship/response, never signatures or the declared real-world facts. */
final class DeclarationSourceProof {
    private static final Pattern ISSUER=Pattern.compile("(?:投标人|供应商|承诺单位|声明单位)(?:名称)?\\s*(?:[（(][^）)\\n]{0,25}[）)])?\\s*[:：]\\s*([^\\n；;|]{2,100})");
    static Map<String,Object> prove(Document bid,Evidence body,String subject,String title,Collection<Evidence> available){
        if(bid==null||body==null||!title.matches(".{1,50}(?:承诺书|声明书|声明函)")||subject.isBlank()||!body.documentId().equals(bid.id()))return Map.of();
        List<Page> exact=bid.pages().stream().filter(p->p.number()==body.page()&&p.locationType().equals(body.locationType())).toList();if(exact.size()!=1)return Map.of();
        Page page=exact.get(0);String original=page.text(),quote=body.quote();if(original==null||quote.isBlank()||original.indexOf(quote)<0||original.indexOf(quote,original.indexOf(quote)+1)>=0)return Map.of();
        Set<String> issuers=new HashSet<>();Matcher names=ISSUER.matcher(quote);while(names.find())issuers.add(compact(names.group(1)));
        if(!issuers.equals(Set.of(compact(subject)))||!quote.matches("(?s).*(?:我方承诺|我公司承诺|本公司承诺|本单位承诺|我方声明|特此承诺|特此声明).*"))return Map.of();
        var resolver=DocumentScopeProof.forDocument(bid);if(!Boolean.TRUE.equals(resolver.currentEvidence(body).get("verifiedCurrent")))return Map.of();
        if(hasTitle(quote,title))return proof(body,body,subject,title,"SAME_DOCUMENT_BODY_AND_ISSUER");
        if(!"PAGE".equals(body.locationType())||!printedPage(original,body.page())||hasNewDocumentTitle(original))return Map.of();
        List<Evidence> headings=available.stream().filter(e->e!=null&&bid.id().equals(e.documentId())&&e.locationType().equals("PAGE")&&e.page()==body.page()-1&&hasTitle(e.quote(),title))
            .filter(e->Boolean.TRUE.equals(resolver.currentEvidence(e).get("verifiedCurrent")))
            .filter(e->bid.pages().stream().anyMatch(p->p.number()==e.page()&&p.locationType().equals("PAGE")&&printedPage(p.text(),p.number())))
            .toList();
        if(headings.isEmpty())return Map.of();
        // The preceding page must have one declaration title. Adjacency alone does not connect documents.
        Page previous=bid.pages().stream().filter(p->p.number()==body.page()-1&&p.locationType().equals("PAGE")).findFirst().orElse(null);
        if(previous==null||titles(previous.text()).size()!=1||!titles(previous.text()).contains(compact(title)))return Map.of();
        return proof(headings.get(0),body,subject,title,"HEADING_CONTINUATION_PRINTED_PAGES_AND_ISSUER");
    }
    private static Map<String,Object> proof(Evidence heading,Evidence body,String subject,String title,String method){return Map.of("method",method,"proofLevel","WRITTEN_DECLARATION_ONLY","subject",subject,"documentTitle",title,"headingSource",heading,"issuerSource",body,"signatureVerified",false,"declaredFactsVerified",false);}
    private static boolean hasTitle(String text,String title){return titles(text).contains(compact(title));}
    private static Set<String> titles(String text){Set<String> result=new HashSet<>();for(String line:Objects.toString(text,"").split("\\R")){
        String s=compact(line).replaceFirst("^[#*\\d一二三四五六七八九十.、（）()★]+","");
        if(s.matches("[^：:；;。]{2,55}(?:承诺书|声明书|声明函)"))result.add(s);
    }return result;}
    private static boolean hasNewDocumentTitle(String text){return !titles(text.substring(0,Math.min(300,text.length()))).isEmpty();}
    private static boolean printedPage(String text,int page){List<String> lines=Objects.toString(text,"").lines().map(String::strip).filter(s->!s.isBlank()).toList();return !lines.isEmpty()&&lines.get(lines.size()-1).equals(Integer.toString(page));}
    private static String compact(String text){return text.replaceAll("\\s+","");}
}
