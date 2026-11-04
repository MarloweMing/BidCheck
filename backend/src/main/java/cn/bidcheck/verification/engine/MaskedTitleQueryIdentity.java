package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Re-identifies a cited name cell in a single title-query record across overlapping chunk windows. */
public final class MaskedTitleQueryIdentity {
    private static final Pattern NAME=Pattern.compile("(?m)^[ \\t]*\\|[ \\t]*姓[ \\t]*名[ \\t]*\\|[ \\t]*([^|\\r\\n]*?)[ \\t]*\\|[ \\t]*\\r?$");
    private static final Pattern FIELD=Pattern.compile("^[ \\t]*\\|[ \\t]*([^|]+?)[ \\t]*\\|[ \\t]*([^|]+?)[ \\t]*\\|[ \\t]*$");
    private MaskedTitleQueryIdentity(){}
    public static String key(String checkpoint,Map<String,Object> fact,List<Map<String,Object>> evidence,Document doc){
        String person=str(fact,"subject"),masked=str(fact,"value");
        if(checkpoint.isEmpty()||doc==null||evidence.size()!=2||!"RELATION".equals(str(fact,"kind"))||!"同一人".equals(str(fact,"field"))
                ||!person.matches("[\\p{IsHan}·]{2,12}")||!masked.matches("[\\p{IsHan}*＊×●_·]{2,12}")||!masked.matches(".*[*＊×●_].*")
                ||!masked.matches(".*[\\p{IsHan}].*")||masked.length()!=person.length())return "";
        for(int i=0;i<masked.length();i++)if("*＊×●_".indexOf(masked.charAt(i))<0&&masked.charAt(i)!=person.charAt(i))return "";
        String appointment="",occurrence="";
        Set<Integer> pages=new HashSet<>();
        for(Map<String,Object> ref:evidence){
            String quote=str(ref,"quote");int n=RunSession.number(ref.get("page"),-1);
            if(!doc.id().equals(str(ref,"documentId"))||!doc.sha256().equals(str(ref,"documentHash"))||doc.version()!=RunSession.number(ref.get("documentVersion"),-1)
                    ||!"PAGE".equals(str(ref,"locationType"))||!pages.add(n)||str(ref,"sourceId").isEmpty()||quote.isEmpty())return "";
            List<Page> found=cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().filter(p->p.number()==n&&p.locationType().equals("PAGE")));
            if(found.size()!=1)return "";String text=found.get(0).text();int start=text.indexOf(quote);
            if(start<0||text.indexOf(quote,start+1)>=0)return "";
            if(quote.contains(masked)){
                if(!occurrence.isEmpty())return "";
                occurrence=record(text,masked,start,start+quote.length(),fact);
                if(occurrence.isEmpty())return "";occurrence=n+"|"+occurrence;
            }else{
                if(!appointment.isEmpty()||!quote.contains(person)||!text.contains("拟在本合同任职"))return "";
                appointment=str(ref,"sourceId");
            }
        }
        if(appointment.isEmpty()||occurrence.isEmpty())return "";
        return identity("MASKED_TITLE_QUERY_NAME_CELL_1|"+checkpoint+"|"+doc.id()+"|"+doc.sha256()+"|"+doc.version()+"|"+appointment+"|"+person+"|"+masked+"|"+occurrence);
    }
    private static String record(String page,String mask,int start,int end,Map<String,Object> fact){
        int at=page.indexOf(mask);
        if(at<start||at+mask.length()>end||page.indexOf(mask,at+1)>=0)return "";
        Matcher label=NAME.matcher(page);
        if(!label.find()||label.start(1)!=at||!mask.equals(label.group(1)))return "";
        int header=label.start(),after=label.end(1);if(label.find())return "";
        String context=page.substring(Math.max(0,header-500),header);
        if(!context.matches("(?s).*职称(?:评审)?(?:信息)?查询.*"))return "";
        int tableEnd=page.indexOf('\n',after);if(tableEnd<0)return "";
        String number="";int category=0;Set<String> labels=new HashSet<>();
        for(String raw:page.substring(tableEnd+1).split("\\r?\\n")){
            if(!raw.trim().startsWith("|"))break;
            if(raw.trim().matches("[ |:\\-]+"))continue;
            Matcher field=FIELD.matcher(raw);if(!field.matches())return "";
            String key=field.group(1).trim();if(!labels.add(key)||"姓名".equals(key))return "";
            if(Arrays.asList("证书编号","证书号码","证书号").contains(key)){
                if(!number.isEmpty())return "";number=field.group(2).trim();
            }
            if(key.startsWith("职称"))category++;
        }
        String claimed="";
        if(fact.get("attributes") instanceof Map<?,?>)claimed=Objects.toString(((Map<?,?>)fact.get("attributes")).get("number"),"");
        if(category==0||!number.matches("[A-Za-z0-9][A-Za-z0-9*＊×●_./()-]{2,78}[A-Za-z0-9]")||!claimed.isEmpty()&&!claimed.equals(number))return "";
        return at+"|"+(at+mask.length())+"|"+number;
    }
}
