package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Scheduling metadata rebuilt from a frozen whole page, never from a cited excerpt. */
final class PageCertificateWitness {
    static final String VERSION="frozen-page-certificate-witness-1";
    private static final Pattern TITLE=Pattern.compile("(?m)^[ \\t]*(职业健康安全管理体系认证|环境管理体系认证|质量管理体系认证)证书[ \\t]*$");
    private static final Pattern NUMBER_LABEL=Pattern.compile("证书(?:编号|号码|号)");
    private static final Pattern NUMBER=Pattern.compile("证书(?:编号|号码|号)[ \\t:：|]*([^\\s|，,;；。<>]{1,100})");
    private static final Pattern NUMBER_VALUE=Pattern.compile("[A-Za-z0-9][A-Za-z0-9()（）/.-]{0,99}");
    private static final Pattern HOLDER_LABEL=Pattern.compile("(?:兹证明|获证(?:组织|企业)(?:名称)?)(?=[ \\t:：|\\n]|$)");
    private static final Pattern HOLDER=Pattern.compile("(?m)^[ \\t|]*(?:兹证明|获证(?:组织|企业)(?:名称)?)(?=[ \\t:：|\\n]|$)[ \\t:：|]*(?:\\n[ \\t|]*)*([^\\n|，,。;；<>]{2,100})[ \\t|]*$");
    private static final Pattern ORGANIZATION=Pattern.compile("[^：:|\\n]{2,100}(?:公司|集团|企业|事务所|研究院|设计院|中心|合作社|工厂|厂)");
    private PageCertificateWitness(){}

    static Map<String,Object> create(Document document,Page page,String parserFingerprint){
        if(document==null||page==null||!"PAGE".equals(page.locationType())||page.number()<1
                ||document.version()<1||document.id()==null||document.id().isEmpty()
                ||document.sha256()==null||document.sha256().isEmpty()
                ||parserFingerprint==null||parserFingerprint.isEmpty())return Collections.emptyMap();
        String original=Objects.toString(page.text(),"");
        String printed=original.replace("\r","").replace("**","").replace("`","")
            .replaceAll("(?m)^[ \\t]*#{1,6}[ \\t]*","");
        Set<String> categories=values(TITLE,printed),numbers=values(NUMBER,printed),holders=values(HOLDER,printed);
        if(categories.size()!=1||numbers.size()!=1||holders.size()!=1
                ||count(NUMBER_LABEL,printed)!=count(NUMBER,printed)
                ||count(HOLDER_LABEL,printed)!=count(HOLDER,printed)
                ||!NUMBER_VALUE.matcher(numbers.iterator().next()).matches()
                ||!ORGANIZATION.matcher(holders.iterator().next()).matches())return Collections.emptyMap();
        return of("version",VERSION,"authority","CURRENT_SERVER_PROOF","businessProof",false,
            "documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),
            "page",page.number(),"locationType",page.locationType(),"parserFingerprint",parserFingerprint,
            "pageTextHash",hash(original),"managementCategory",categories.iterator().next(),
            "number",numbers.iterator().next(),"holder",holders.iterator().next());
    }

    private static Set<String> values(Pattern pattern,String input){
        Set<String> values=new LinkedHashSet<>();Matcher matcher=pattern.matcher(input);
        while(matcher.find())values.add(matcher.group(1).trim());
        values.remove("");return values;
    }
    private static int count(Pattern pattern,String input){int count=0;Matcher matcher=pattern.matcher(input);while(matcher.find())count++;return count;}
}
