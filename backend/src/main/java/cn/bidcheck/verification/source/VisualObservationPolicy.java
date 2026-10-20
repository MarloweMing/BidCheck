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
    public record Constraint(Kind kind,boolean blocksValue,boolean blocksRelation,String message) {
        Map<String,Object> audit(){return Map.of("kind",kind.name(),"blocksValue",blocksValue,"blocksRelation",blocksRelation,"message",message);}
    }
    public record Assessment(boolean valueUsable,boolean relationUsable,List<Constraint> constraints) {
        public Map<String,Object> audit(){return Map.of("version",VERSION,"valueUsable",valueUsable,"relationUsable",relationUsable,"constraints",constraints.stream().map(Constraint::audit).toList());}
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
        if(value.isBlank())value=text(observation,"observedValue");
        String readability=text(observation,"readability");
        if(!"CLEAR".equals(readability)||value.isBlank())constraints.add(new Constraint("NOT_OBSERVED".equals(readability)?Kind.NOT_OBSERVED:Kind.VALUE_UNREADABLE,true,true,"观察不是非空CLEAR字段"));
        Object raw=observation.get("modelLimitations");
        if(raw instanceof List<?> notes)for(Object entry:notes){
            if(!(entry instanceof String note)){constraints.add(new Constraint(Kind.UNCLASSIFIED_RESTRICTION,true,true,"限制说明格式无效"));continue;}
            for(String part:note.split("[。；;\\n]+"))if(!part.isBlank())constraints.add(classify(part.strip(),type,label,value));
        }else if(raw!=null)constraints.add(new Constraint(Kind.UNCLASSIFIED_RESTRICTION,true,true,"限制说明格式无效"));
        boolean valueUsable=constraints.stream().noneMatch(Constraint::blocksValue);
        return new Assessment(valueUsable,valueUsable&&constraints.stream().noneMatch(Constraint::blocksRelation),List.copyOf(constraints));
    }
    private static Constraint classify(String note,String type,String label,String value){
        boolean mark=Set.of("SEAL","SIGNATURE").contains(type), relevant=mentions(note,label,value),uncertain=UNCERTAINTY.matcher(note).find();
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
    private static List<String> aliases(String label){return switch(label){
        case "name"->List.of("姓名");case "certificateNumber","number"->List.of("证书编号","证号");
        case "validUntil","end","validity"->List.of("有效期");case "companyName","employer"->List.of("企业名称","单位名称");
        case "category"->List.of("类别","分类","资质","category");default->List.of();};}
    private static Constraint c(Kind kind,boolean value,boolean relation,String note){return new Constraint(kind,value,relation,note);}
    private static String text(Map<String,?> map,String key){return Objects.toString(map.get(key),"");}

    /** Recheck persisted bounds: a four-item array alone is not a valid source location. */
    public static boolean validSourceBounds(Map<String,?> observation){
        if(!(observation.get("sourceBbox") instanceof List<?> box)||box.size()!=4)return false;
        double[] bounds=new double[4];for(int i=0;i<4;i++){if(!(box.get(i) instanceof Number n)||!Double.isFinite(n.doubleValue()))return false;bounds[i]=n.doubleValue();}
        if(bounds[0]<0||bounds[1]<0||bounds[2]<=bounds[0]||bounds[3]<=bounds[1])return false;
        if(observation.get("source") instanceof Map<?,?> origin){
            if(origin.get("renderedWidth") instanceof Number n&&bounds[2]>n.doubleValue())return false;
            if(origin.get("renderedHeight") instanceof Number n&&bounds[3]>n.doubleValue())return false;
        }return true;
    }
}
