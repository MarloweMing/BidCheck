package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Validates a user-confirmed pricing snapshot; it does not authenticate its issuing authority. */
public final class PricingInputValidator {
    private static final Pattern PRICE_FIELD=Pattern.compile("投标(?:总)?报价|投标(?:总)?价(?:格)?|评标价(?:格)?|报价");
    private static final Pattern NUM=Pattern.compile("(?<![\\d.])((?:\\d{1,3}(?:[,，]\\d{3})+|\\d+)(?:\\.\\d+)?)(?![\\d.])");
    private static final Pattern OTHER_PRICE=Pattern.compile("合同(?:报价|价|金额|总价|编号)|最高(?:投标)?限价|控制价|预算价|招标限价|中标价|估算价");
    private PricingInputValidator(){}
    public static Map<String,Object> validate(Object raw,List<Document> manifestDocuments){
        if(raw==null)return cn.bidcheck.compat.Java8Api.mapOf();if(!(raw instanceof Map<?,?>))throw bad("价格数据必须为对象");if(((Map<?,?>) (raw)).isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
        if(!Boolean.TRUE.equals(((Map<?,?>) (raw)).get("confirmed"))||!Boolean.TRUE.equals(((Map<?,?>) (raw)).get("rosterComplete"))||!Boolean.TRUE.equals(((Map<?,?>) (raw)).get("arithmeticCorrectionsConfirmed")))throw bad("须确认价格名单完整且算术修正已处理");
        if(!"EXCLUDING_TAX".equals(string(((Map<?,?>) (raw)),"taxBasis")))throw bad("本计算仅接受明确不含税的评标价格");
        Map<String,Document> documents=new HashMap<>();String project=null;for(Document d:cn.bidcheck.compat.Java8Api.requireNonNullElse(manifestDocuments, cn.bidcheck.compat.Java8Api.<Document>listOf())){if(d==null||d.id()==null||d.pages()==null||documents.put(d.id(),d)!=null)throw bad("资料集合包含无效或重复文档");if(project==null)project=d.projectId();if(!Objects.equals(project,d.projectId()))throw bad("价格依据不能跨项目");}
        Evidence roster=source(((Map<?,?>) (raw)).get("rosterSource"),documents);String rosterText=roster.quote();
        if(!rosterText.matches("(?s).*(?:有效投标人|有效投标单位|有效投标报价|有效投标价格).*")||!rosterText.matches("(?s).*(?:价格|报价|投标价|评标价|金额).*"))throw bad("名单原文必须明确为有效投标人价格名单");
        if(rosterText.matches("(?s).*(?:无效投标|非有效投标|投标无效|被否决|未通过评审|未通过审查).*")||!taxExplicit(rosterText))throw bad("名单有效性或不含税口径不明确，不能据此计算");
        String target=cn.bidcheck.compat.Java8Api.strip(string(((Map<?,?>) (raw)),"targetBidder"));if(cn.bidcheck.compat.Java8Api.isBlank(target)||target.length()>200)throw bad("须指定本次被核验投标人");
        if(!(((Map<?,?>) (raw)).get("entries") instanceof List<?>)||((List<?>) (((Map<?,?>) (raw)).get("entries"))).isEmpty()||((List<?>) (((Map<?,?>) (raw)).get("entries"))).size()>500)throw bad("须提供完整且有界的投标人价格列表");
        List<Map<String,Object>> normalized=new ArrayList<>();Set<String> bidders=new HashSet<>();List<String> declared=cn.bidcheck.compat.Java8Api.toList(((List<?>) (((Map<?,?>) (raw)).get("entries"))).stream().filter(v->v instanceof Map<?,?>).map(v->cn.bidcheck.compat.Java8Api.strip(string((Map<?,?>)v,"bidder"))).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)));
        for(Object value:((List<?>) (((Map<?,?>) (raw)).get("entries")))){if(!(value instanceof Map<?,?>))throw bad("价格行格式不正确");String bidder=cn.bidcheck.compat.Java8Api.strip(string(((Map<?,?>) (value)),"bidder")),identity=bidder.replaceAll("\\s+","");if(cn.bidcheck.compat.Java8Api.isBlank(bidder)||bidder.length()>200||!bidders.add(identity))throw bad("投标人不能为空或重复");
            String rawPrice=string(((Map<?,?>) (value)),"price"),unit=string(((Map<?,?>) (value)),"unit");if(!(((Map<?,?>) (value)).get("price") instanceof String)||!rawPrice.matches("(?:0|[1-9]\\d{0,18})(?:\\.\\d{1,8})?")||!cn.bidcheck.compat.Java8Api.setOf("元", "万元").contains(unit))throw bad("价格必须是普通十进制正数，原单位为元或万元");BigDecimal amount=new BigDecimal(rawPrice);if(amount.signum()<=0)throw bad("价格必须大于零");
            Evidence evidence=source(((Map<?,?>) (value)).get("source"),documents);Map<String,String> bound=boundLine(evidence.quote(),bidder,amount,unit,declared);if(bound==null)throw bad("投标人、投标/评标价格原值与单位未在同一局部行绑定");
            if(!rosterText.contains(bidder))throw bad("价格行的投标人未出现在所引有效名单中");
            BigDecimal yuan=unit.equals("万元")?amount.multiply(new BigDecimal("10000")):amount;if(yuan.stripTrailingZeros().scale()>6||yuan.precision()-yuan.scale()>18)throw bad("规范元价格不得超过18位整数和6位小数");normalized.add(VerificationTypes.linked("bidder",bidder,"price",yuan.stripTrailingZeros().toPlainString(),"unit","元","inputPrice",rawPrice,"inputUnit",unit,"sourcePrice",bound.get("price"),"sourceUnit",bound.get("unit"),"evidence",evidence));
        }
        if(!bidders.contains(target.replaceAll("\\s+","")))throw bad("被核验投标人不在价格名单内");
        return VerificationTypes.linked("confirmed",true,"rosterComplete",true,"arithmeticCorrectionsConfirmed",true,"taxBasis","EXCLUDING_TAX","targetBidder",target,"entries",normalized,"rosterEvidence",roster,"provenanceStatus","USER_CONFIRMED_DATA_SNAPSHOT");
    }
    private static boolean taxExplicit(String text){return text.matches("(?s).*(?:(?:不含税|未税|税前)[^。\\n]{0,30}(?:价|报价|金额)|(?:价|报价|金额)[^。\\n]{0,25}(?:不含税|未税|税前)).*");}
    private static Map<String,String> boundLine(String quote,String bidder,BigDecimal expected,String expectedUnit,List<String> declared){int valid=0;Map<String,String> bound=null;BigDecimal expectedYuan=expectedUnit.equals("万元")?expected.multiply(new BigDecimal("10000")):expected;for(String line:quote.split("\\R")){if(!line.contains(bidder)||OTHER_PRICE.matcher(line.replace(bidder,"")).find())continue;
            if(line.matches(".*(?:无效投标|投标无效|未通过|被否决).*"))continue;
            // An explicitly tax-inclusive row cannot be relabeled by an unrelated roster heading.
            String taxText=line.replace("不含税","").replace("未税","").replace("税前","");if(taxText.contains("含税")||taxText.contains("税后"))continue;
            int subjectStart=line.indexOf(bidder);if(subjectStart>0&&Character.isLetterOrDigit(line.charAt(subjectStart-1)))continue;int subjectEnd=subjectStart+bidder.length();String rest=line.substring(subjectEnd);if(!rest.matches("^(?:$|[\\s:：，,;；|].*|不含税.*|含税.*|投标.*|评标.*)"))continue;String local=line.substring(subjectStart).split("[;；|]",2)[0];
            for(String other:declared)if(!other.equals(bidder)){int otherAt=local.indexOf(other,bidder.length());if(otherAt>=0)local=local.substring(0,otherAt);}
            Matcher otherCompany=Pattern.compile("[，,]\\s*[\\p{IsHan}A-Za-z0-9（）()]{1,80}(?:公司|集团|事务所|研究院)").matcher(local);if(otherCompany.find())local=local.substring(0,otherCompany.start());
            Matcher fields=PRICE_FIELD.matcher(local);while(fields.find()){String tail=local.substring(fields.end());int stop=tail.length();Matcher next=PRICE_FIELD.matcher(tail);if(next.find())stop=next.start();tail=tail.substring(0,stop).split("[;；。|]",2)[0];Matcher number=NUM.matcher(tail);if(!number.find())continue;
                BigDecimal actual=new BigDecimal(number.group(1).replace(",","").replace("，",""));String after=tail.substring(number.end());Matcher suffix=Pattern.compile("^\\s*(万元|元)(?![\\p{IsHan}]*元)").matcher(after);String unit=suffix.find()?suffix.group(1):"";
                if(cn.bidcheck.compat.Java8Api.isBlank(unit)){String prefix=tail.substring(0,number.start());Matcher header=Pattern.compile("[（(]\\s*(万元|元)\\s*[）)]").matcher(prefix);if(header.find())unit=header.group(1);}
                if(!cn.bidcheck.compat.Java8Api.isBlank(unit)){BigDecimal actualYuan=unit.equals("万元")?actual.multiply(new BigDecimal("10000")):actual;if(actualYuan.compareTo(expectedYuan)==0){valid++;bound=cn.bidcheck.compat.Java8Api.mapOf("price", actual.stripTrailingZeros().toPlainString(), "unit", unit);}}
            }
        }return valid==1?bound:null;}
    private static Evidence source(Object raw,Map<String,Document> documents){if(!(raw instanceof Map<?,?>))throw bad("每项价格及名单均须提供原文来源");String id=string(((Map<?,?>) (raw)),"documentId"),quote=string(((Map<?,?>) (raw)),"quote");Document document=documents.get(id);if(document==null||cn.bidcheck.compat.Java8Api.isBlank(quote)||quote.length()>8000)throw bad("价格来源不属于本次资料集合或原文为空");int page;try{page=Integer.parseInt(string(((Map<?,?>) (raw)),"page"));}catch(Exception e){throw bad("价格来源位置无效");}Page found=null;for(Page p:document.pages())if(p.number()==page&&p.text()!=null&&p.text().contains(quote)){if(found!=null)throw bad("价格来源位置不唯一");found=p;}if(found==null)throw bad("价格来源引文与所选原文不一致");return new Evidence(id,document.name(),page,found.locationType(),quote,"pricing-source",found.parser()!=null&&found.parser().contains("OCR")?0:1);}
    private static String string(Map<?,?> map,String key){Object value=map.get(key);return value==null?"":value.toString();}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
