package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Selectors for actual framework originals. Orders and price schedules are not identity anchors. */
final class FrameworkOriginalFields {
    private static final Pattern QUALIFIERS=Pattern.compile("^(?:[（(](?:标段|标包)[^（）()]{1,32}[）)])(?:[（(]20[0-9]{2}年[-－—][\\p{IsHan}A-Za-z0-9·]{2,45}[）)])?$");
    private static final Pattern PARTY=Pattern.compile("(?m)^[ \\t#|>*]*(甲[ \\t]*方|乙[ \\t]*方)[ \\t]*(?:[（(](?:全称)[）)])?[ \\t]*[:：][ \\t]*([\\p{IsHan}A-Za-z0-9（）()·]{3,100}?(?:有限责任公司|股份有限公司|有限公司|分公司))(?=[ \\t（(，,。；;|\\r\\n]|$)");
    private FrameworkOriginalFields(){}

    static boolean qualifiedTitle(String formal,String base){
        String title=clean(formal).replace('（','(').replace('）',')'),root=clean(base).replace('（','(').replace('）',')');
        return root.endsWith("框架合同") && title.startsWith(root) && QUALIFIERS.matcher(title.substring(root.length())).matches();
    }
    static String coverTitle(Source cover,String base){
        if(!base.contains("框架合同")||cover.index()||ContractRecordBindings.isOrder(cover.evidence().quote()))return "";
        Set<String> exact=new LinkedHashSet<>(),qualified=new LinkedHashSet<>();
        for(String line:cover.evidence().quote().split("\\R")){
            String title=line.trim().replaceFirst("^#{1,6}\\s*","").replaceFirst("^[①②③④⑤⑥⑦⑧⑨⑩]\\s*","")
                .replaceFirst("^[0-9]+[、.．]\\s*","").replaceFirst("^(?:合同名称|合同名)\\s*[:：]\\s*","");
            if(ContractCandidateFields.sameTitle(title,base))exact.add(title);
            if(qualifiedTitle(title,base))qualified.add(title);
        }
        if(qualified.size()>1)return "";
        return !qualified.isEmpty()?qualified.iterator().next():exact.isEmpty()?"":exact.iterator().next();
    }
    static boolean samePacketTitles(String formal,String base){
        return ContractCandidateFields.sameTitle(formal,base)||qualifiedTitle(formal,base);
    }
    static boolean orderStart(Source source){
        String quote=source.evidence().quote();
        return Pattern.compile("(?m)^[ \\t#]*(?:对应订单|订单汇总(?:表|清单)?|(?:中国联合网络通信有限公司)?采购订单)[ \\t]*$").matcher(quote).find()
            || ContractRecordBindings.isOrder(quote);
    }
    static List<Source> originals(Map<String,Object> row,EvidenceCorpus corpus){
        return originals(row,corpus.all);
    }
    static List<Source> originals(Map<String,Object> row,Collection<Source> sources){
        List<Source> pages=cn.bidcheck.compat.Java8Api.toList(sources.stream().filter(s->!s.id().startsWith("V")
            && DeclaredPerformanceRows.contains(row,s)).sorted(Comparator.comparingInt(s->s.evidence().page())));
        int firstOrder=pages.stream().filter(FrameworkOriginalFields::orderStart)
            .mapToInt(s->s.evidence().page()).min().orElse(Integer.MAX_VALUE);
        return cn.bidcheck.compat.Java8Api.toList(pages.stream().filter(s->s.evidence().page()<firstOrder));
    }
    static Set<String> parties(Source page){
        Set<String> names=new LinkedHashSet<>();Matcher m=PARTY.matcher(page.evidence().quote());
        while(m.find()) names.add(m.group(2));return names;
    }
    static boolean signatures(Source source){
        String text=source.evidence().quote();
        return parties(source).size()>=2 && text.matches("(?s).*(?:签署页|签字页|授权代表|法定代表人).*")
            && text.matches("(?s).*(?:签字|盖章|签章).*");
    }
    static String signedRaw(Source source){
        if(!signatures(source))return "";
        SigningDateBlock signing=SigningDateBlock.read(Collections.singletonList(source));
        if(signing.status!=SigningDateBlock.Status.CONFIRMED)return "";
        Matcher m=Pattern.compile("20[0-9]{2}\\s*(?:年|[-./])\\s*[0-9]{1,2}\\s*(?:月|[-./])\\s*[0-9]{1,2}\\s*日?").matcher(source.evidence().quote());
        while(m.find())if(signing.confirms(m.group()))return m.group().trim();
        return "";
    }
}
