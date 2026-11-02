package cn.bidcheck.verification.source;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Reads a labelled date cell/phrase, stopping before another field or certificate.
 * Dates merely occurring elsewhere on the same page are never substitute field values. */
public final class DateFieldValues {
    public static final String VERSION="date-field-boundary-4-exact-period-label";
    private static final Pattern DATE=Pattern.compile("(?<![0-9])(\\d{4})\\s*(?:年|[-./])\\s*(\\d{1,2})\\s*(?:月|[-./])\\s*(\\d{1,2})\\s*日?(?![0-9])");
    private static final Pattern NEXT_FIELD=Pattern.compile("注册有效期|使用有效期|使用期限|证书有效日期|证书有效期|资格有效期|有效截止日期|有效期限|有效期(?:至)?|资质类别|资质名称|资质等级|发证机关|签发机关|批准机关|发证日期|签发日期|批准日期|颁发日期|初次认证日期|注册日期|打印日期|出生日期|更新日期|证书编号|电子证书编号|注册编号|证件号码|企业名称|单位名称|持证人|姓名|备注|[。；;]");
    private DateFieldValues(){}

    /** A printed signing/footer date is a document field, never proof of an actual signature.
     * Only this small alias family may use a generic date beside the document's signing block.
     * Certificate validity, issue dates and reference dates retain exact-label matching. */
    public static List<LocalDate> readAssertion(String quote,String field,int maximum){
        if(quote==null||field==null||field.isBlank()||maximum<1)return List.of();
        List<LocalDate> exact=read(quote,field,maximum);if(!exact.isEmpty())return exact;
        if(quote==null||maximum!=1||!Set.of("签署日期","落款日期","签字日期","落款时间","签署时间").contains(field))return List.of();
        String text=quote.replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ");
        if(!text.matches("(?s).*(?:承诺书|声明书|声明函|投标函|授权委托书|联合体协议书|证明书).*"))return List.of();
        Matcher generic=Pattern.compile("(?m)(?:^|[\\n|])\\s*日期\\s*[:：]").matcher(text);List<List<LocalDate>> dates=new ArrayList<>();
        while(generic.find()){
            String before=text.substring(Math.max(0,generic.start()-240),generic.start());
            if(!before.matches("(?s).*(?:投标人|供应商|承诺人|声明人|法定代表人|负责人|委托代理人|甲方|乙方)[^\\n]{0,90}(?:盖章|签字|签名|签章)[^\\n]*(?:\\n[^\\n]*){0,3}"))continue;
            List<LocalDate> value=read(text.substring(generic.start()),"日期",1);if(value.isEmpty())return List.of();dates.add(value);
        }
        if(dates.isEmpty())return List.of();List<LocalDate> first=dates.get(0);return dates.stream().allMatch(first::equals)?first:List.of();
    }

    public static List<LocalDate> read(String quote,String field,int maximum){
        if(quote==null||field==null||field.isBlank()||maximum<1)return List.of();
        // HTML cell tags and Markdown separators are formatting, not permission to cross the next label.
        String text=quote.replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ");
        String label=Pattern.quote(field);Matcher found=Pattern.compile(label).matcher(text);List<List<LocalDate>> occurrences=new ArrayList<>();
        while(found.find()){
            // A short label must not silently name a different kind of period.
            if(field.equals("有效期")&&found.start()>=2&&text.substring(Math.max(0,found.start()-4),found.start()).matches(".*(?:注册|使用|证书|资格)$"))continue;
            // 有效期 and 有效期至 are distinct printed labels. A later category-specific
            // 有效期至 note must not overwrite the original certificate's 有效期 field.
            // “有效期：至...” remains an expiry-only value of the shorter label.
            if(field.equals("有效期")&&text.startsWith("至",found.end()))continue;
            String after=text.substring(found.end(),Math.min(text.length(),found.end()+180));Matcher next=NEXT_FIELD.matcher(after);if(next.find())after=after.substring(0,next.start());
            // A date value must begin at the field. Never search through unrelated prose for dates.
            after=after.replaceFirst("^[\\s:：|]*(?:为\\s*[:：]?|至|截止至|截至)?[\\s:：|]*","");
            Matcher dates=DATE.matcher(after);List<LocalDate> values=new ArrayList<>();int previousEnd=0;boolean invalid=false;
            while(dates.find()){
                String between=after.substring(previousEnd,dates.start());
                if(values.isEmpty()&&!between.isBlank()||!values.isEmpty()&&!between.matches("[\\s至到起止从—–~～－\\-:：|]*")){invalid=true;break;}
                try{values.add(LocalDate.of(Integer.parseInt(dates.group(1)),Integer.parseInt(dates.group(2)),Integer.parseInt(dates.group(3))));}catch(RuntimeException e){invalid=true;break;}
                previousEnd=dates.end();
            }
            if(!invalid&&!values.isEmpty()&&values.size()<=maximum)occurrences.add(List.copyOf(values));
            else if(invalid||values.size()>maximum)return List.of();
        }
        if(occurrences.isEmpty())return List.of();List<LocalDate> first=occurrences.get(0);
        return occurrences.stream().allMatch(first::equals)?first:List.of();
    }
}
