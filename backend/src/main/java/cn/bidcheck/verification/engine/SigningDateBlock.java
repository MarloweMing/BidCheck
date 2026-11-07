package cn.bidcheck.verification.engine;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.Source;
import static cn.bidcheck.verification.engine.VerificationTypes.linked;

/** Dates must belong to the signing field or to both identified party signature blocks. */
final class SigningDateBlock {
    enum Status { CONFIRMED, CONFLICT, NOT_FOUND }
    private static final String DATE = "(?<![0-9])20[0-9]{2}\\s*(?:年|[-./])\\s*[0-9]{1,2}\\s*(?:月|[-./])\\s*[0-9]{1,2}\\s*日?(?![0-9])";
    private static final Pattern DAYS = Pattern.compile(DATE);
    private static final Pattern LABEL = Pattern.compile("(?:合同)?(?:签订|签署|签约)(?:日期|时间)[ \\t]*[:：][ \\t]*(" + DATE + ")");
    private static final Pattern SIGNING_SENTENCE=Pattern.compile("(?:合同)?(?:签订|签署|签约)(?:日期|时间)[\\s|*#：:]{0,40}本合同于[ \\t_]*("+DATE+")[ \\t_]*(?:签订|签署|签约)");
    private static final Pattern FOOTER_LABEL = Pattern.compile("(?m)^[ \\t]*(?:时间|签字日期|盖章日期)[ \\t]*[:：][ \\t]*(" + DATE + ")");
    private static final Pattern A = Pattern.compile("(?m)^[ \\t*#|>]*(?:甲\\s*方|发包人|建设单位|委托人|采购人)(?=[ \\t（(:：|]|$)");
    private static final Pattern B = Pattern.compile("(?m)^[ \\t*#|>]*(?:乙\\s*方|承包人|施工单位|设计单位|受托人|供应商)(?=[ \\t（(:：|]|$)");
    private static final Pattern SIGN = Pattern.compile("签字|签章|盖章|法定代表人|委托代理人");
    private static final Pattern EXCLUDED = Pattern.compile("工期|开工|竣工|有效期|履约|付款|保修|质保");
    private static final Pattern COLUMN_SIGNERS = Pattern.compile("(?m)^[^\\r\\n]{0,40}(?:负责人|代表人)/签约授权代表[：:]_{1,}\\s+[^\\r\\n]{0,30}(?:负责人|代表人)/签约授权代表[：:]_{1,}");
    private static final SigningDateBlock NONE = new SigningDateBlock(Status.NOT_FOUND,"","",Collections.emptyList());
    final Status status;
    final String first, second;
    final List<Source> sources;

    private SigningDateBlock(Status status,String first,String second,List<Source> sources) {
        this.status=status; this.first=first; this.second=second; this.sources=sources;
    }
    boolean confirms(String date) {
        return status==Status.CONFIRMED && Objects.equals(ProofVerifier.date(date),ProofVerifier.date(first));
    }
    Map<String,Object> choice(String target,String contract) {
        List<Map<String,Object>> evidence = new ArrayList<>();
        List<String> ids = new ArrayList<>(); Set<Integer> pages = new TreeSet<>();
        for (Source source : sources) {
            ids.add(source.id()); pages.add(source.evidence().page());
            evidence.add(linked("sourceId",source.id(),"quote",source.evidence().quote()));
        }
        String choices = first + " / " + second;
        return linked("type","CONTRACT_SIGNING_DATE_CONFLICT","targetId",target,
            "claim","合同“"+contract+"”第"+pages+"页双方签署日期分别是"+first+"、"+second+"，签订日期以哪一天为准？",
            "reasoning","两方签署栏中印有不同日期，请依据合同条款选择有依据的日期。",
            "confidence",0.8,"risks",Collections.singletonList("选择签订日期只影响本合同时间核对。"),
            "evidence",evidence,"unresolvedGapCodes",Collections.singletonList("CONTRACT_SIGNING_DATE_CONFLICT"),
            "proposedFact",linked("kind","RELATION","field","CONTRACT_SIGNING_DATE_CHOICE","subject",contract,
                "rawValue",choices,"value",choices,"scope","HISTORICAL_PROJECT","sourceIds",ids,
                "attributes",linked("object",choices,"dateOptions",Arrays.asList(first,second))));
    }
    static SigningDateBlock read(Collection<Source> sources) {
        Map<String,List<Source>> labels=new LinkedHashMap<>();
        for(Source s:sources) {
            String q=s.evidence().quote();
            extractLabels(LABEL,q,s,labels);
            extractLabels(SIGNING_SENTENCE,q,s,labels);
            if(SIGN.matcher(q).find()) extractLabels(FOOTER_LABEL,q,s,labels);
        }
        if(!labels.isEmpty()) return result(labels);
        Map<String,List<Source>> dates=new LinkedHashMap<>();
        for(Source s:sources) {
            String q=s.evidence().quote();
            Matcher a=A.matcher(q), b=B.matcher(q);
            if(!a.find() || !b.find())continue;
            int left=a.start(), right=b.start();
            if(a.find()||b.find())continue;
            String first=q.substring(Math.min(left,right),Math.max(left,right)), second=q.substring(Math.max(left,right));
            if(!SIGNED(first)||!SIGNED(second))continue;
            List<String> inFirst=sectionDates(first),inSecond=sectionDates(second);
            if(inFirst.size()==1&&inSecond.size()==1) {
                add(dates,inFirst.get(0),s);add(dates,inSecond.get(0),s);
            } else if(inFirst.isEmpty()&&inSecond.size()==2&&inSecond.get(0).equals(inSecond.get(1))
                    && COLUMN_SIGNERS.matcher(second).find() && bareFooterDates(second)==2) {
                // Parallel OCR columns can serialize both date cells after the second heading.
                // Require two labelled signer columns, two standalone date lines and a single value.
                add(dates,inSecond.get(0),s);
            }
        }
        return result(dates);
    }
    private static boolean SIGNED(String segment) {
        return SIGN.matcher(segment.substring(0,Math.min(segment.length(),180))).find();
    }
    private static int bareFooterDates(String segment) {
        int count=0;
        for(String line:segment.split("\\R")) if(line.trim().matches(DATE))count++;
        return count;
    }
    private static List<String> sectionDates(String text) {
        List<String> result=new ArrayList<>();
        for(String line:text.split("\\R")) {
            if(EXCLUDED.matcher(line).find())continue;
            Matcher m=DAYS.matcher(line);
            while(m.find()) {
                LocalDate date=ProofVerifier.date(m.group());
                if(date!=null)result.add(date.toString());
            }
        }
        return result;
    }
    private static void extractLabels(Pattern label,String quote,Source source,Map<String,List<Source>> dates) {
        Matcher match=label.matcher(quote);
        while(match.find()) {
            LocalDate d=ProofVerifier.date(match.group(1));
            if(d!=null)add(dates,d.toString(),source);
        }
    }
    private static void add(Map<String,List<Source>> dates,String date,Source source) {
        dates.computeIfAbsent(date,k->new ArrayList<>()).add(source);
    }
    static SigningDateBlock result(Map<String,List<Source>> dates) {
        if(dates.isEmpty()||dates.size()>2)return NONE;
        List<String> values=new ArrayList<>(dates.keySet());Set<Source> sources=new LinkedHashSet<>();dates.values().forEach(sources::addAll);
        return new SigningDateBlock(values.size()==1?Status.CONFIRMED:Status.CONFLICT,values.get(0),
            values.size()==1?"":values.get(1),new ArrayList<>(sources));
    }
}
