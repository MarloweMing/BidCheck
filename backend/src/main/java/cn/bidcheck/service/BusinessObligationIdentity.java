package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** A conservative, deterministic equivalence proof, not a semantic similarity score. */
final class BusinessObligationIdentity {
    static final String VERSION="business-obligation-v2-frozen-scope";
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Signature {
        private final String key;
        private final boolean eligible;
        private final String reason;
        private final Map<String,Object> dimensions;
        private final Map<String,Object> occurrence;
        @com.fasterxml.jackson.annotation.JsonCreator
        Signature(@com.fasterxml.jackson.annotation.JsonProperty("key") String key, @com.fasterxml.jackson.annotation.JsonProperty("eligible") boolean eligible, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("dimensions") Map<String,Object> dimensions, @com.fasterxml.jackson.annotation.JsonProperty("occurrence") Map<String,Object> occurrence) { this.key = key; this.eligible = eligible; this.reason = reason; this.dimensions = dimensions; this.occurrence = occurrence; }
        public String key() { return key; }
        public boolean eligible() { return eligible; }
        public String reason() { return reason; }
        public Map<String,Object> dimensions() { return dimensions; }
        public Map<String,Object> occurrence() { return occurrence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Signature that = (Signature) other; return java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.eligible, that.eligible) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.dimensions, that.dimensions) && java.util.Objects.equals(this.occurrence, that.occurrence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.eligible); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.dimensions); hash = 31 * hash + java.util.Objects.hashCode(this.occurrence); return hash; }
        @Override public String toString() { return "Signature[" + "key=" + key + ", eligible=" + eligible + ", reason=" + reason + ", dimensions=" + dimensions + ", occurrence=" + occurrence + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Scope {
        private final String project;
        private final String purpose;
        private final String subject;
        private final String lot;
        private final String restrictions;
        private final List<Map<String,Object>> anchors;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Scope(@com.fasterxml.jackson.annotation.JsonProperty("project") String project, @com.fasterxml.jackson.annotation.JsonProperty("purpose") String purpose, @com.fasterxml.jackson.annotation.JsonProperty("subject") String subject, @com.fasterxml.jackson.annotation.JsonProperty("lot") String lot, @com.fasterxml.jackson.annotation.JsonProperty("restrictions") String restrictions, @com.fasterxml.jackson.annotation.JsonProperty("anchors") List<Map<String,Object>> anchors) { this.project = project; this.purpose = purpose; this.subject = subject; this.lot = lot; this.restrictions = restrictions; this.anchors = anchors; }
        public String project() { return project; }
        public String purpose() { return purpose; }
        public String subject() { return subject; }
        public String lot() { return lot; }
        public String restrictions() { return restrictions; }
        public List<Map<String,Object>> anchors() { return anchors; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Scope that = (Scope) other; return java.util.Objects.equals(this.project, that.project) && java.util.Objects.equals(this.purpose, that.purpose) && java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.lot, that.lot) && java.util.Objects.equals(this.restrictions, that.restrictions) && java.util.Objects.equals(this.anchors, that.anchors); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.project); hash = 31 * hash + java.util.Objects.hashCode(this.purpose); hash = 31 * hash + java.util.Objects.hashCode(this.subject); hash = 31 * hash + java.util.Objects.hashCode(this.lot); hash = 31 * hash + java.util.Objects.hashCode(this.restrictions); hash = 31 * hash + java.util.Objects.hashCode(this.anchors); return hash; }
        @Override public String toString() { return "Scope[" + "project=" + project + ", purpose=" + purpose + ", subject=" + subject + ", lot=" + lot + ", restrictions=" + restrictions + ", anchors=" + anchors + "]"; }
}
    private final Document tender;
    private final Map<String,Page> pages=new HashMap<>();
    private final Map<String,Scope> scopes=new HashMap<>();
    private final Set<String> duplicateLocations=new HashSet<>();
    private static final Pattern LOT=Pattern.compile("第[一二三四五六七八九十百0-9]+标段|标段[一二三四五六七八九十百0-9]+|第[一二三四五六七八九十百0-9]+包|包[一二三四五六七八九十百0-9]+");

    BusinessObligationIdentity(Document tender){
        this.tender=tender;if(tender==null||tender.pages()==null)return;
        Page undividedSource=null;boolean example=false;
        for(Page p:cn.bidcheck.compat.Java8Api.toList(tender.pages().stream().limit(120))){
            String t=cn.bidcheck.compat.Java8Api.strip(Objects.toString(p.text(),""));if(t.length()<100&&t.matches(".*(?:示例|仅供参考|范例|填写样例).*"))example=true;
            if(!example&&t.matches("(?:本项目|本次招标)(?:不划分标段|不分标段)[。；;]?")){undividedSource=p;break;}
        }
        boolean undivided=undividedSource!=null;Map<String,Object> globalLot=undivided?anchor(undividedSource,"PROJECT_LOT","EXPLICIT_UNDIVIDED_PROJECT"):cn.bidcheck.compat.Java8Api.mapOf();
        String project="PROJECT:"+tender.projectId(),purpose="",subject="",lot=undivided?"EXPLICIT_UNDIVIDED_PROJECT":"",restrictions="";
        List<Map<String,Object>> anchors=new ArrayList<>();
        if(undivided)anchors.add(globalLot);
        for(Page page:tender.pages()){
            String key=location(page.number(),page.locationType()),raw=Objects.toString(page.text(),""),text=compact(raw);
            if(pages.putIfAbsent(key,page)!=null)duplicateLocations.add(key);
            if(text.matches("第[一二三四五六七八九十0-9]+章.*")){purpose="";subject="";restrictions="";lot=undivided?"EXPLICIT_UNDIVIDED_PROJECT":"";anchors.clear();if(undivided)anchors.add(globalLot);}
            String nextPurpose=purpose(raw);boolean heading=cn.bidcheck.compat.Java8Api.strip(raw).length()<=100&&!raw.contains("。")&&!raw.matches(".*[；;].*");
            if(cn.bidcheck.compat.Java8Api.strip(raw).length()<=220&&cn.bidcheck.compat.Java8Extras.lines(raw).count()==1){Matcher projectLabel=Pattern.compile("^(?:项目名称|工程名称)[:：](.+)$").matcher(text);if(projectLabel.find()){project=normalize(projectLabel.group(1));anchors.add(anchor(page,"PROJECT",project));}}
            if(!cn.bidcheck.compat.Java8Api.isBlank(nextPurpose)&&(heading||raw.matches("(?s)^(?:[0-9.]+\\s*)?(?:资格评审|形式评审|响应评审|详细评审)\\t.*"))){
                purpose=nextPurpose;restrictions="";if(purpose.equals("QUALIFICATION"))subject="投标人";
                anchors.add(anchor(page,"PURPOSE",purpose));
            }
            if(heading){
                if(text.matches("[0-9]+[.、][^0-9].*")){restrictions="";subject=purpose.equals("QUALIFICATION")?"投标人":"";lot=undivided?"EXPLICIT_UNDIVIDED_PROJECT":"";}
                String localLot=lot(raw);if(!cn.bidcheck.compat.Java8Api.isBlank(localLot)){lot=localLot;anchors.add(anchor(page,"LOT",lot));}
                String localSubject=subjectHeading(text);if(!cn.bidcheck.compat.Java8Api.isBlank(localSubject)){if(!subject.equals(localSubject))restrictions="";subject=localSubject;anchors.add(anchor(page,"SUBJECT",subject));}
                // Headings that restrict members, exceptions, applicability or currency cannot disappear.
                if(text.matches(".*(?:联合体|牵头|成员|仅限|适用于|除外|含税|不含税|人民币|美元|欧元|若|如果|除.*外).*")){
                    restrictions=normalize(raw);anchors.add(anchor(page,"RESTRICTION",restrictions));
                }
            }
            List<Map<String,Object>> scopeAnchors=new ArrayList<>(anchors.subList(Math.max(0,anchors.size()-12),anchors.size()));
            if(undivided&&!scopeAnchors.contains(globalLot))scopeAnchors.add(0,globalLot);
            scopes.put(key,new Scope(project,purpose,subject,lot,restrictions,cn.bidcheck.compat.Java8Api.listCopyOf(scopeAnchors)));
        }
    }
    Signature signature(Finding finding){
        Checkpoint cp=finding.checkpoint();Evidence source=cp.source();Map<String,Object> occurrence=fields("resultId",finding.id(),"checkpointId",cp.id(),"source",source,"sourceValidated",false,"decisionStatus",finding.evaluation()==null?"UNDETERMINED":finding.evaluation().status(),"normalizations",cn.bidcheck.compat.Java8Api.listOf());
        if(tender==null||!"TENDER".equals(tender.role())||tender.sha256()==null||cn.bidcheck.compat.Java8Api.isBlank(tender.sha256()))return rejected(finding,occurrence,"冻结招标文档不可用，保留原位置归并边界");
        if(source==null||"FULL_TEXT_REVIEW".equals(cp.type())||finding.assessment()!=null&&finding.assessment().containsKey("consistencyGroupId"))return rejected(finding,occurrence,"无招标原句或属于全文一致性结果，不跨章节归并");
        if(!tender.id().equals(source.documentId()))return rejected(finding,occurrence,"来源不属于本轮冻结招标文件");
        String key=location(source.page(),source.locationType());Page page=pages.get(key);
        if(page==null||duplicateLocations.contains(key))return rejected(finding,occurrence,"来源位置不存在或不唯一");
        String pageText=strict(page.text()),sourceText=strict(source.quote());int at=pageText.indexOf(sourceText);
        if(cn.bidcheck.compat.Java8Api.isBlank(sourceText)||at<0||pageText.indexOf(sourceText,at+1)>=0)return rejected(finding,occurrence,"来源原文没有在冻结内容位置唯一对应");
        occurrence.put("sourceValidated",true);
        String expected=normalize(cp.expected());if(cn.bidcheck.compat.Java8Api.isBlank(expected))return rejected(finding,occurrence,"业务义务为空");
        if(containers(source.quote()).stream().filter(c->containsExactlyOnce(normalize(c),expected)).count()!=1)return rejected(finding,occurrence,"业务义务未在所引原文中唯一对应");
        // The quote may be truncated by extraction. Bound all qualifiers from the immutable source block,
        // not from the shorter citation, otherwise an omitted exception could disappear from the key.
        List<String> containers=containers(page.text());List<String> matching=new ArrayList<>();
        for(String container:containers){String candidate=normalize(container);if(containsExactlyOnce(candidate,expected))matching.add(container);}
        if(matching.size()!=1)return rejected(finding,occurrence,"当前义务未在唯一独立原文条款中对应，不能丢弃上下文后归并");
        String original=matching.get(0),container=normalize(original);Scope scope=scopes.get(key);
        String purpose=scope.purpose();String localPurpose=purpose(original);if(!cn.bidcheck.compat.Java8Api.isBlank(localPurpose))purpose=localPurpose;
        if(cn.bidcheck.compat.Java8Api.isBlank(purpose))return rejected(finding,occurrence,"原文未确定资格、响应或评分用途");
        String localSubject=subjectHeading(compact(original));String subject=cn.bidcheck.compat.Java8Api.isBlank(localSubject)?scope.subject():localSubject;
        if(cn.bidcheck.compat.Java8Api.isBlank(subject))return rejected(finding,occurrence,"原文未确定适用主体");
        String explicitLot=lot(original),lot=cn.bidcheck.compat.Java8Api.isBlank(explicitLot)?scope.lot():explicitLot;
        if(cn.bidcheck.compat.Java8Api.isBlank(lot))return rejected(finding,occurrence,"原文未明确标段范围或不划分标段，不能将未知范围视为相同");
        if(compact(original).matches(".*(?:填写示例|仅供参考|历史项目|举例说明).*"))return rejected(finding,occurrence,"原文属于示例或历史说明，不能作为同一当前义务归并");
        Map<String,Object> predicate=predicate(cp,expected);
        Map<String,Object> dimensions=fields("projectId",tender.projectId(),"projectScope",scope.project(),"tenderHash",tender.sha256(),"tenderVersion",tender.version(),
            "subject",subject,"lot",lot,"purpose",purpose,"inheritedRestrictions",scope.restrictions(),"predicate",predicate,
            "category",normalize(cp.category()),"required",cp.required(),"enabled",cp.enabled(),"confirmed",cp.confirmed(),
            "obligation",expected,"containingClause",container);
        String identity=dimensions.toString();int start=container.indexOf(expected);
        occurrence.put("obligationSpan",fields("text",cp.expected(),"containingSourceText",original,"normalizedStart",start,"normalizedEnd",start+expected.length(),"offsetBasis","CANONICAL_REQUIREMENT_TEXT","normalizedObligation",expected));
        occurrence.put("contextProof",fields("projectScope",scope.project(),"subject",subject,"lot",lot,"purpose",purpose,"restrictions",scope.restrictions(),"anchors",scope.anchors(),"frozenDocumentHash",tender.sha256()));
        occurrence.put("normalizations",cn.bidcheck.compat.Java8Api.listOf("全半角、汉字相邻排版空白与圈号列表换行；保留英文及数字之间的分隔", "受控表格标签/章节编号", "须提供/应提交等义务动作同义词；保留否定、或/且、例外、时点及全部未知文本"));
        return new Signature(identity,true,"冻结原文、限定义务与主体/标段/用途/条件均相同",dimensions,occurrence);
    }
    private static Signature rejected(Finding f,Map<String,Object> occurrence,String reason){occurrence.put("mergeBlocker",reason);return new Signature("unique:"+f.id(),false,reason,cn.bidcheck.compat.Java8Api.mapOf(),occurrence);}
    private static Map<String,Object> anchor(Page p,String kind,String value){return fields("location",p.number(),"locationType",p.locationType(),"kind",kind,"value",value,"sourceQuote",p.text());}
    private static String location(int page,String type){return type+":"+page;}
    private static boolean containsExactlyOnce(String text,String part){int at=text.indexOf(part);return at>=0&&text.indexOf(part,at+1)<0;}
    private static String purpose(String raw){String t=compact(raw);
        if(t.matches(".*(?:评分标准|打分表|评分项|得[0-9]+分|每[有项个].*得[0-9]+分).*"))return "SCORING";
        if(t.startsWith("资格评审")||t.matches("[★*]*(?:投标人)?资格(?:要求|审查|条件).*"))return "QUALIFICATION";
        if(t.startsWith("形式评审"))return "FORMAL";
        if(t.startsWith("响应评审"))return "RESPONSE";
        return "";
    }
    private static String subjectHeading(String text){
        String t=text.replaceFirst("^(?:资格评审|形式评审|响应评审|资质要求|其他要求)","").replaceFirst("^(?:[0-9]+(?:\\.[0-9]+){2,3}|\\([0-9]+\\)|[0-9]+[.、](?=[\\p{IsHan}]))","");
        for(String role:cn.bidcheck.compat.Java8Api.listOf("联合体各成员", "联合体牵头人", "联合体成员", "施工企业主要负责人", "项目负责人", "施工部分负责人", "设计部分负责人", "专职安全员", "设计单位", "施工单位", "投标人", "投标单位"))
            if(t.startsWith(role)||t.startsWith("★"+role))return role.equals("投标单位")?"投标人":role;
        return "";
    }
    private static String lot(String text){Matcher m=LOT.matcher(compact(text));Set<String> lots=new LinkedHashSet<>();while(m.find())lots.add(m.group());return lots.size()==1?lots.iterator().next():lots.isEmpty()?"":"MULTIPLE:"+String.join("|",lots);}
    private static List<String> containers(String quote){
        List<String> out=new ArrayList<>();for(String item:Objects.toString(quote,"").split("\\s+/\\s+")){
            String t=cn.bidcheck.compat.Java8Api.strip(item);if(!out.isEmpty()&&t.matches("(?s)^(?:须|需|应)?提交有效证明文件.*|^证明材料[:：].*|^并提供查询.*"))out.set(out.size()-1,out.get(out.size()-1)+" "+t);
            else out.add(t);
        }return out;
    }
    private static Map<String,Object> predicate(Checkpoint cp,String expected){
        String type=Objects.toString(cp.type(),"");String action=expected.matches(".*(?:须提供|提供|提交).*(?:证书|证照|执照|承诺书|证明文件).*" )?"DOCUMENT_OBLIGATION":"TEXT_OBLIGATION";
        if(type.equals("VALUE_COMPARE"))action="VALUE_COMPARE";
        String operator;
switch (Objects.toString(cp.operator(),"")) {case "GTE":case "GE":case ">=":operator = ">="; break;case "LTE":case "LE":case "<=":operator = "<="; break;case "EQ":case "==":case "=":operator = "="; break;default:operator = Objects.toString(cp.operator(),""); break;}
        return fields("kind",action,"type",type,"field",Objects.toString(cp.field(),""),"operator",operator,"unit",normalize(cp.unit()),"expected",expected);
    }
    static String normalize(String value){
        // Remove a line wrap before an explicit circled list marker before NFKC loses that marker.
        // Do not generalize this to arbitrary digits: AB C, 1 00 and numeric punctuation are meaningful.
        String t=cn.bidcheck.compat.Java8Api.strip(Normalizer.normalize(Objects.toString(value,"").replaceAll("\\s+(?=[①②③④⑤⑥⑦⑧⑨⑩])",""),Normalizer.Form.NFKC));
        t=t.replaceFirst("^(?:资格评审|形式评审|响应评审)[\\t ]+","");
        t=t.replaceFirst("^(?:资质要求|其他要求)[\\t ]+","");
        t=t.replaceFirst("^(?:[0-9]+(?:\\.[0-9]+){2,3}|\\([0-9]+\\))[\\t ]*(?=设计单位资质要求|施工单位资质要求|注册要求|人员要求|经营状况|信誉要求|项目负责人|施工部分负责人|设计部分负责人|专职安全员|施工企业主要负责人)","");
        t=t.replaceFirst("^(注册要求|经营状况|信誉要求|人员要求)[\\t:： ]+","$1:");
        t=layoutWhitespace(t);
        t=t.replace("投标单位","投标人").replace("有效的营业执照","有效营业执照");
        t=t.replaceAll("(?:应当|必须|需要|需|应|须)(?:提交|提供|出具)","须提供");
        t=t.replaceAll("(?:不低于|不少于|至少)(?=[0-9]+(?:\\.[0-9]+)?(?:人|名|元|万元|亿元|天|日历天|个月|月|年))","不少于").replaceAll("(?<=[0-9])名","人");
        return t.replaceAll("[。；;]+$","");
    }
    private static String strict(String value){return layoutWhitespace(Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFKC));}
    private static String compact(String value){return strict(value);}
    private static String layoutWhitespace(String value){
        StringBuilder out=new StringBuilder();for(int i=0;i<value.length();){char ch=value.charAt(i);if(!Character.isWhitespace(ch)){out.append(ch);i++;continue;}
            int end=i+1;while(end<value.length()&&Character.isWhitespace(value.charAt(end)))end++;
            if(i>0&&end<value.length()){char left=value.charAt(i-1),right=value.charAt(end);boolean hanLeft=Character.UnicodeScript.of(left)==Character.UnicodeScript.HAN,hanRight=Character.UnicodeScript.of(right)==Character.UnicodeScript.HAN;
                if(!hanLeft&&!hanRight)out.append(' ');
            }i=end;
        }return cn.bidcheck.compat.Java8Api.strip(out.toString());
    }
    private static Map<String,Object> fields(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(values[i].toString(),values[i+1]);return result;}
}
