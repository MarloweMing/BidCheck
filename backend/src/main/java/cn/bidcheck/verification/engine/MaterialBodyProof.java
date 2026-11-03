package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A qualification-material heading identifies a container, not the presence of its certificates. */
final class MaterialBodyProof {
    private static final Pattern GENERIC=Pattern.compile("(?:有效|资格|资质|人员).{0,8}证明(?:文件|材料)");
    private static final Pattern QUALIFICATION_CONTEXT=Pattern.compile("(?:人员|有效|任职).{0,8}资格证明(?:文件|材料)|(?:资格|资质)证明(?:文件|材料)");
    private static final Pattern CERTIFICATE_TITLE=Pattern.compile("[\\p{IsHan}A-Za-z（）()· ]{0,35}(?:注册证书|资格证书|执业证书|职称证书|资质证书|安全生产考核合格证(?:书)?|毕业证书|学位证书)(?:[（(][^）)]{0,20}[）)])?");
    private static final Pattern PROFILE_TITLE=Pattern.compile("(?:附表[0-9—-]+\\s*)?.{0,20}(?:情况表|简历表|履历表|人员名单|人员配置表)");
    private static final Pattern NUMBER=Pattern.compile("(?:证书(?:编号|号码|号)|注册(?:编号|号)|管理(?:编号|号)|资格(?:证书)?编号|编号)\\s*[:：|]?\\s*[A-Za-z0-9][A-Za-z0-9（）()/-]{3,}");
    private static final Pattern NAME=Pattern.compile("(?:姓\\s*名|持证人|企业名称|单位名称)\\s*[:：|]?\\s*[\\p{IsHan}A-Za-z·]{2,40}");
    private static final Pattern DATE=Pattern.compile("(?:有效期|注册日期|发证日期|签发日期|批准日期)[^\\n]{0,20}\\d{4}[年./-]\\d{1,2}");
    private static final Pattern ISSUER=Pattern.compile("(?:发证机关|颁发机关|注册机关)\\s*[:：|]?\\s*[\\p{IsHan}]{4,40}|本证书由[^\\n]{4,80}(?:颁发|签发)");
    private MaterialBodyProof(){}

    static String issue(Fact fact,List<Source> sources){
        if(!fact.kind().equals("MATERIAL"))return null;
        String labels=clean(fact.field()+" "+fact.value()+" "+fact.basis());
        if(!GENERIC.matcher(labels).find())return null;
        boolean qualification=QUALIFICATION_CONTEXT.matcher(labels).find()||sources.stream().anyMatch(s->QUALIFICATION_CONTEXT.matcher(clean(s.evidence().quote())).find());
        if(!qualification)return null; // Other forms of proof retain their own material semantics.
        return sources.stream().filter(s->!s.index()).anyMatch(s->certificateBody(s.evidence().quote(),fact.subject()))?null:"QUALIFICATION_MATERIAL_BODY_UNPROVEN";
    }

    private static boolean certificateBody(String original,String subject){
        String text=original.replaceAll("(?i)</(?:td|th|tr|p|div)>","\n").replaceAll("<[^>]+>"," ").replace("**","").replace("`","");
        List<String> lines=Arrays.asList(text.split("\\n",-1));
        for(int i=0;i<lines.size();i++){
            String title=title(lines.get(i));if(!CERTIFICATE_TITLE.matcher(title).matches())continue;
            StringBuilder body=new StringBuilder();
            for(int j=i+1;j<lines.size();j++){
                String next=title(lines.get(j));
                if(PROFILE_TITLE.matcher(next).matches()||CERTIFICATE_TITLE.matcher(next).matches())break;
                body.append(lines.get(j)).append('\n');
            }
            String value=body.toString();
            if(value.matches("(?s).*(?:仅供参考|填写示例|证书样本|证书模板|示例姓名|示例编号|待填写).*"))continue;
            if(!cn.bidcheck.compat.Java8Api.isBlank(subject)&&!clean(value).contains(clean(subject)))continue;
            // Filled certificate fields are required; unrelated long prose and CV identity columns do not qualify.
            boolean named=NAME.matcher(value).find(),numbered=NUMBER.matcher(value).find();
            int supporting=(named?1:0)+(DATE.matcher(value).find()?1:0)+(ISSUER.matcher(value).find()?1:0);
            if(numbered&&(named||!cn.bidcheck.compat.Java8Api.isBlank(subject))||supporting>=2&&named)return true;
        }
        return false;
    }
    private static String title(String value){return cn.bidcheck.compat.Java8Api.strip(SectionCoverage.strip(cn.bidcheck.compat.Java8Api.strip(value).replaceFirst("^[#>*★]+\\s*","")));}
}
