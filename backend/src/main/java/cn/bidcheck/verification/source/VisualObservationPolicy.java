package cn.bidcheck.verification.source;

import java.util.*;
import java.util.regex.*;

/** Server-side admission of an observation, distinct from the business relations it can prove.
 * The model's classified metadata is never trusted: this assessment is recomputed from the
 * literal observation and its original limitations whenever the observation is consumed. */
public final class VisualObservationPolicy {
    public static final String VERSION="visual-field-admission-1";
    public enum Kind { PAGE_SCOPE, AUTHENTICITY_SCOPE, RELATION_UNPROVEN, LABEL_UNPROVEN,
        VALUE_UNREADABLE, NOT_OBSERVED, OTHER_OBSERVATION, INFORMATION, UNCLASSIFIED_RESTRICTION }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Constraint {
        private final Kind kind;
        private final boolean blocksValue;
        private final boolean blocksRelation;
        private final String message;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Constraint(@com.fasterxml.jackson.annotation.JsonProperty("kind") Kind kind, @com.fasterxml.jackson.annotation.JsonProperty("blocksValue") boolean blocksValue, @com.fasterxml.jackson.annotation.JsonProperty("blocksRelation") boolean blocksRelation, @com.fasterxml.jackson.annotation.JsonProperty("message") String message) { this.kind = kind; this.blocksValue = blocksValue; this.blocksRelation = blocksRelation; this.message = message; }
        public Kind kind() { return kind; }
        public boolean blocksValue() { return blocksValue; }
        public boolean blocksRelation() { return blocksRelation; }
        public String message() { return message; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Constraint that = (Constraint) other; return java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.blocksValue, that.blocksValue) && java.util.Objects.equals(this.blocksRelation, that.blocksRelation) && java.util.Objects.equals(this.message, that.message); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.blocksValue); hash = 31 * hash + java.util.Objects.hashCode(this.blocksRelation); hash = 31 * hash + java.util.Objects.hashCode(this.message); return hash; }
        @Override public String toString() { return "Constraint[" + "kind=" + kind + ", blocksValue=" + blocksValue + ", blocksRelation=" + blocksRelation + ", message=" + message + "]"; }

        Map<String,Object> audit(){return cn.bidcheck.compat.Java8Api.mapOf("kind", kind.name(), "blocksValue", blocksValue, "blocksRelation", blocksRelation, "message", message);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Assessment {
        private final boolean valueUsable;
        private final boolean relationUsable;
        private final List<Constraint> constraints;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Assessment(@com.fasterxml.jackson.annotation.JsonProperty("valueUsable") boolean valueUsable, @com.fasterxml.jackson.annotation.JsonProperty("relationUsable") boolean relationUsable, @com.fasterxml.jackson.annotation.JsonProperty("constraints") List<Constraint> constraints) { this.valueUsable = valueUsable; this.relationUsable = relationUsable; this.constraints = constraints; }
        public boolean valueUsable() { return valueUsable; }
        public boolean relationUsable() { return relationUsable; }
        public List<Constraint> constraints() { return constraints; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Assessment that = (Assessment) other; return java.util.Objects.equals(this.valueUsable, that.valueUsable) && java.util.Objects.equals(this.relationUsable, that.relationUsable) && java.util.Objects.equals(this.constraints, that.constraints); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.valueUsable); hash = 31 * hash + java.util.Objects.hashCode(this.relationUsable); hash = 31 * hash + java.util.Objects.hashCode(this.constraints); return hash; }
        @Override public String toString() { return "Assessment[" + "valueUsable=" + valueUsable + ", relationUsable=" + relationUsable + ", constraints=" + constraints + "]"; }

        public Map<String,Object> audit(){return cn.bidcheck.compat.Java8Api.mapOf("version", VERSION, "valueUsable", valueUsable, "relationUsable", relationUsable, "constraints", cn.bidcheck.compat.Java8Api.toList(constraints.stream().map(Constraint::audit)));}
    }
    private VisualObservationPolicy(){}
    private static final Pattern UNCERTAINTY=Pattern.compile("无法|不能|未能|不清|模糊|遮挡|裁切|缺失|未见|未观察|不详|推断|不确定|尚未|未确认|未核|未验证|未解码|非结构化|占位符|空白");
    private static final Pattern BLUR=Pattern.compile("模糊|不清晰|不清楚|不清|遮挡|裁切|无法辨|不能辨|未完全清晰|分辨率|不详|占位符");
    private static final Pattern MARK=Pattern.compile("印章|公章|签章|盖章|签名|签字|印文");
    private static final Pattern EXTERNAL=Pattern.compile("真伪|真实性|法律效力|合法注册|法定一致性|官方一致性|实际担任|实际缴纳|正式成立|本人操作|原件一致性|是否为原件|签署时刻|授权有效");
    private static final Pattern RELATION=Pattern.compile("同一人|持证人|归属|对应签署|签署身份|签署人|所属|关联|属于|任职|实际参与|本人|一致性|完整构成");
    private static final Pattern PAGE_SCOPE=Pattern.compile("仅(?:依据|基于|限|观察|识别)|只(?:观察|识别|转录)|单页|本页|其他页|其他页面|邻页|附件|上下文|整体有效|完整证书|完整证件|仅按任务|未被要求|未要求|任务未|任务仅|任务指定|故未|未列入|未提取|二维码");
    private static final Pattern FIELD_NAME=Pattern.compile("姓名|企业名称|单位名称|有效期|注册日期|发证日期|证书编号|证号|职称|资质|类别|印章|印文|签名|签字|二维码|时间轴|金额|价格|最高投标限价|网址");

    public static Assessment assess(Map<String,?> observation){
        List<Constraint> constraints=new ArrayList<>();String type=text(observation,"type"),label=text(observation,"label"),value=text(observation,"value");
        if(cn.bidcheck.compat.Java8Api.isBlank(value))value=text(observation,"observedValue");
        String readability=text(observation,"readability");
        if(!"CLEAR".equals(readability)||cn.bidcheck.compat.Java8Api.isBlank(value))constraints.add(new Constraint("NOT_OBSERVED".equals(readability)?Kind.NOT_OBSERVED:Kind.VALUE_UNREADABLE,true,true,"观察不是非空CLEAR字段"));
        Object raw=observation.get("modelLimitations");
        if(raw instanceof List<?>)for(Object entry:((List<?>) (raw))){
            if(!(entry instanceof String)){constraints.add(new Constraint(Kind.UNCLASSIFIED_RESTRICTION,true,true,"限制说明格式无效"));continue;}
            for(String part:((String) (entry)).split("[。；;\\n]+"))if(!cn.bidcheck.compat.Java8Api.isBlank(part))constraints.add(classify(cn.bidcheck.compat.Java8Api.strip(part),type,label,value));
        }else if(raw!=null)constraints.add(new Constraint(Kind.UNCLASSIFIED_RESTRICTION,true,true,"限制说明格式无效"));
        boolean valueUsable=constraints.stream().noneMatch(Constraint::blocksValue);
        return new Assessment(valueUsable,valueUsable&&constraints.stream().noneMatch(Constraint::blocksRelation),cn.bidcheck.compat.Java8Api.listCopyOf(constraints));
    }

    private static final Pattern CONTRACT_EFFECT_LIMIT=Pattern.compile(
        "^(?:(?:未见|缺少|未提供)(?:完整)?(?:合同封面|合同全文|签署页|合同)(?:或签署页)?(?:全貌|全文|全本)?[，,])?"
        +"(?:无法|不能|未能)(?:据此)?(?:确认|验证|核实)(?:该日期|此日期|所见日期)(?:是否为|是否属于)"
        +"(?:最终生效签署日期|最终生效日期|最终签署日期|合同最终生效日期)(?:或是否存在后续修改)?$");
    private static final Pattern UNCERTAIN_SIGNING_FIELD=Pattern.compile(
        "(?:无法|不能|未能|难以|不确定|不清楚)(?:确认|判断|确定|区分|核实)?[^。；;]{0,16}(?:日期的性质|日期性质|该日期是否为签订|该日期是否为合同签订|该日期是否为签署|属于哪一合同|所属合同|来源页|来源不明)"
        +"|(?:该日期|签订日期|签署日期|所见日期)[^。；;]{0,18}(?:可能是|也可能|还是|或是|属于哪)[^。；;]{0,8}(?:开工|竣工|验收|签发|填表|合同)"
        +"|(?:无法|不能|未能|不确定)[^。；;]{0,16}(?:日期归属哪份合同|日期所属合同|日期来自哪份合同|对应哪份合同)"
        +"|(?:年月日|年份|月份|日份|日期数字|日期数值)[^。；;]{0,8}(?:不确定|猜测|推断|无法辨|看不清|疑似)");

    /** Called only after the host proves this is the requested signing line of the current
     * frozen contract. A scope limitation is not permission to infer an effective date or a seal. */
    public static Assessment assessBoundContractSigningLine(Map<String,?> observation){
        Assessment base=assess(observation);
        if(!"FIELD".equals(text(observation,"type"))||!"CLEAR".equals(text(observation,"readability"))
                || !text(observation,"label").matches("(?:合同)?(?:签订|签署|签约)(?:日期|时间)")
                || !validSourceBounds(observation)) return base;
        List<Constraint> limits=new ArrayList<>();
        for(Constraint original:base.constraints()){
            String note=original.message();
            if(UNCERTAIN_SIGNING_FIELD.matcher(note).find())
                limits.add(c(Kind.LABEL_UNPROVEN,true,true,note));
            else if(original.kind()==Kind.UNCLASSIFIED_RESTRICTION && CONTRACT_EFFECT_LIMIT.matcher(note).matches())
                limits.add(c(Kind.AUTHENTICITY_SCOPE,false,true,note));
            else limits.add(original);
        }
        boolean usable=limits.stream().noneMatch(Constraint::blocksValue);
        return new Assessment(usable,usable&&limits.stream().noneMatch(Constraint::blocksRelation),
            cn.bidcheck.compat.Java8Api.listCopyOf(limits));
    }
    private static Constraint classify(String note,String type,String label,String value){
        boolean mark=cn.bidcheck.compat.Java8Api.setOf("SEAL", "SIGNATURE").contains(type), relevant=mentions(note,label,value),uncertain=UNCERTAINTY.matcher(note).find();
        if(!uncertain)return c(Kind.INFORMATION,false,false,note);
        // Physical readability remains a blocker for the affected field, never explained away as scope.
        if(BLUR.matcher(note).find()){
            boolean global=note.matches("(?s).*(?:整页|整张|全页|全部(?:文字|字段|图像)|所有(?:文字|字段)).*");
            if(global||relevant||mark&&MARK.matcher(note).find())return c(Kind.VALUE_UNREADABLE,true,true,note);
            if(FIELD_NAME.matcher(note).find())return c(Kind.OTHER_OBSERVATION,false,false,note);
            return c(Kind.UNCLASSIFIED_RESTRICTION,true,true,note);
        }
        // Mislabelled values must not acquire a semantic field just because their digits are legible.
        if(relevant&&note.matches("(?s).*(?:系.{0,20}推断|根据上下文推断|依据上下文推断|此处为推断|无法确认.{0,40}(?:字段|类别|编号)|非结构化|导航标签|非.{0,15}(?:证书|字段)|未明确标注|未见独立.{0,12}字段).*"))
            return c(Kind.LABEL_UNPROVEN,true,true,note);
        if(mark&&note.matches("(?s).*(?:未(?:观察到|见|显示).{0,12}(?:实际|可见|手写)?(?:印章|公章|签章|盖章|签名|签字)|(?:签字|签名|盖章)栏.{0,8}空白|仅(?:见文字|为印刷)|模板文字).*"))
            return c(Kind.NOT_OBSERVED,true,true,note);
        // An authenticity disclaimer cannot invalidate an otherwise localized seal or a visible word.
        if(EXTERNAL.matcher(note).find())return c(Kind.AUTHENTICITY_SCOPE,false,true,note);
        if(mark&&MARK.matcher(note).find()&&note.matches("(?s).*(?:未观察到|未见|不存在|空白|仅见文字|模板文字|仅为印刷|未显示|无法确认|无法确定).*"))
            return c(Kind.NOT_OBSERVED,true,true,note);
        if(RELATION.matcher(note).find())return c(Kind.RELATION_UNPROVEN,false,true,note);
        if(PAGE_SCOPE.matcher(note).find())return c(Kind.PAGE_SCOPE,false,true,note);
        if(relevant)return c(Kind.UNCLASSIFIED_RESTRICTION,true,true,note);
        if(FIELD_NAME.matcher(note).find()||MARK.matcher(note).find())return c(Kind.OTHER_OBSERVATION,false,false,note);
        return c(Kind.UNCLASSIFIED_RESTRICTION,true,true,note);
    }
    private static boolean mentions(String note,String label,String value){
        if(label.length()>1&&note.contains(label)||value.length()>1&&note.contains(value))return true;
        for(String alias:aliases(label))if(note.contains(alias))return true;
        return false;
    }
    private static List<String> aliases(String label){switch (label) {case "name":return cn.bidcheck.compat.Java8Api.listOf("姓名");case "certificateNumber":case "number":return cn.bidcheck.compat.Java8Api.listOf("证书编号", "证号");case "validUntil":case "end":case "validity":return cn.bidcheck.compat.Java8Api.listOf("有效期");case "companyName":case "employer":return cn.bidcheck.compat.Java8Api.listOf("企业名称", "单位名称");case "category":return cn.bidcheck.compat.Java8Api.listOf("类别", "分类", "资质", "category");default:return cn.bidcheck.compat.Java8Api.listOf();}}
    private static Constraint c(Kind kind,boolean value,boolean relation,String note){return new Constraint(kind,value,relation,note);}
    private static String text(Map<String,?> map,String key){return Objects.toString(map.get(key),"");}

    /** Recheck persisted bounds: a four-item array alone is not a valid source location. */
    public static boolean validSourceBounds(Map<String,?> observation){
        if(!(observation.get("sourceBbox") instanceof List<?>)||((List<?>) (observation.get("sourceBbox"))).size()!=4)return false;
        double[] bounds=new double[4];for(int i=0;i<4;i++){if(!(((List<?>) (observation.get("sourceBbox"))).get(i) instanceof Number)||!Double.isFinite(((Number) (((List<?>) (observation.get("sourceBbox"))).get(i))).doubleValue()))return false;bounds[i]=((Number) (((List<?>) (observation.get("sourceBbox"))).get(i))).doubleValue();}
        if(bounds[0]<0||bounds[1]<0||bounds[2]<=bounds[0]||bounds[3]<=bounds[1])return false;
        if(observation.get("source") instanceof Map<?,?>){
            if(((Map<?,?>) (observation.get("source"))).get("renderedWidth") instanceof Number&&bounds[2]>((Number) (((Map<?,?>) (observation.get("source"))).get("renderedWidth"))).doubleValue())return false;
            if(((Map<?,?>) (observation.get("source"))).get("renderedHeight") instanceof Number&&bounds[3]>((Number) (((Map<?,?>) (observation.get("source"))).get("renderedHeight"))).doubleValue())return false;
        }return true;
    }
}
