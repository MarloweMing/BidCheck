package cn.bidcheck.document;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable OCR work scope. A lexical match selects work; it never establishes business relevance or completeness. */
public final class ExtractionPlan {
    public static final String VERSION="extraction-plan-2-consistency";
    public static final int DEFAULT_SELECTED_IMAGES=80;
    public enum Mode { FULL,NATIVE_ONLY,CHECKPOINT_DRIVEN,SELECTED }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cue{
        private final String checkpointId;
        private final String title;
        private final List<String> terms;
        private final String ruleSignature;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cue(@com.fasterxml.jackson.annotation.JsonProperty("checkpointId") String checkpointId, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("terms") List<String> terms, @com.fasterxml.jackson.annotation.JsonProperty("ruleSignature") String ruleSignature) { this.checkpointId = checkpointId; this.title = title; this.terms = terms; this.ruleSignature = ruleSignature; }
        public String checkpointId() { return checkpointId; }
        public String title() { return title; }
        public List<String> terms() { return terms; }
        public String ruleSignature() { return ruleSignature; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cue that = (Cue) other; return java.util.Objects.equals(this.checkpointId, that.checkpointId) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.terms, that.terms) && java.util.Objects.equals(this.ruleSignature, that.ruleSignature); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.checkpointId); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.terms); hash = 31 * hash + java.util.Objects.hashCode(this.ruleSignature); return hash; }
        @Override public String toString() { return "Cue[" + "checkpointId=" + checkpointId + ", title=" + title + ", terms=" + terms + ", ruleSignature=" + ruleSignature + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class NativeLocation{
        private final int position;
        private final String locationType;
        private final String text;
        private final int imageCount;
        private final int ocrUnits;
        @com.fasterxml.jackson.annotation.JsonCreator
        NativeLocation(@com.fasterxml.jackson.annotation.JsonProperty("position") int position, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("imageCount") int imageCount, @com.fasterxml.jackson.annotation.JsonProperty("ocrUnits") int ocrUnits) { this.position = position; this.locationType = locationType; this.text = text; this.imageCount = imageCount; this.ocrUnits = ocrUnits; }
        public int position() { return position; }
        public String locationType() { return locationType; }
        public String text() { return text; }
        public int imageCount() { return imageCount; }
        public int ocrUnits() { return ocrUnits; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; NativeLocation that = (NativeLocation) other; return java.util.Objects.equals(this.position, that.position) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.imageCount, that.imageCount) && java.util.Objects.equals(this.ocrUnits, that.ocrUnits); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.position); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.imageCount); hash = 31 * hash + java.util.Objects.hashCode(this.ocrUnits); return hash; }
        @Override public String toString() { return "NativeLocation[" + "position=" + position + ", locationType=" + locationType + ", text=" + text + ", imageCount=" + imageCount + ", ocrUnits=" + ocrUnits + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Decision{
        private final boolean selected;
        private final String code;
        private final List<String> reasons;
        private final List<String> checkpointIds;
        @com.fasterxml.jackson.annotation.JsonCreator
        Decision(@com.fasterxml.jackson.annotation.JsonProperty("selected") boolean selected, @com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("reasons") List<String> reasons, @com.fasterxml.jackson.annotation.JsonProperty("checkpointIds") List<String> checkpointIds) { this.selected = selected; this.code = code; this.reasons = reasons; this.checkpointIds = checkpointIds; }
        public boolean selected() { return selected; }
        public String code() { return code; }
        public List<String> reasons() { return reasons; }
        public List<String> checkpointIds() { return checkpointIds; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Decision that = (Decision) other; return java.util.Objects.equals(this.selected, that.selected) && java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.reasons, that.reasons) && java.util.Objects.equals(this.checkpointIds, that.checkpointIds); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.selected); hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.reasons); hash = 31 * hash + java.util.Objects.hashCode(this.checkpointIds); return hash; }
        @Override public String toString() { return "Decision[" + "selected=" + selected + ", code=" + code + ", reasons=" + reasons + ", checkpointIds=" + checkpointIds + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Resolved{
        private final Map<String,Decision> decisions;
        private final List<Map<String,Object>> unresolvedSelections;
        @com.fasterxml.jackson.annotation.JsonCreator
        Resolved(@com.fasterxml.jackson.annotation.JsonProperty("decisions") Map<String,Decision> decisions, @com.fasterxml.jackson.annotation.JsonProperty("unresolvedSelections") List<Map<String,Object>> unresolvedSelections) { this.decisions = decisions; this.unresolvedSelections = unresolvedSelections; }
        public Map<String,Decision> decisions() { return decisions; }
        public List<Map<String,Object>> unresolvedSelections() { return unresolvedSelections; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Resolved that = (Resolved) other; return java.util.Objects.equals(this.decisions, that.decisions) && java.util.Objects.equals(this.unresolvedSelections, that.unresolvedSelections); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.decisions); hash = 31 * hash + java.util.Objects.hashCode(this.unresolvedSelections); return hash; }
        @Override public String toString() { return "Resolved[" + "decisions=" + decisions + ", unresolvedSelections=" + unresolvedSelections + "]"; }
}
    private final Mode mode;
    private final String sourceSha256,reason,hash;
    private final List<OcrSelection> selections;
    private final List<Cue> cues;
    private final int maxSelectedImages;

    private ExtractionPlan(Mode mode,String source,List<OcrSelection> selections,int maximum,String reason,List<Cue> cues){
        this.mode=Objects.requireNonNull(mode);this.sourceSha256=cn.bidcheck.compat.Java8Api.strip(cn.bidcheck.compat.Java8Api.requireNonNullElse(source, "")).toLowerCase(Locale.ROOT);
        if(!sourceSha256.isEmpty()&&!sourceSha256.matches("[0-9a-f]{64}")||mode==Mode.SELECTED&&sourceSha256.isEmpty())throw new IllegalArgumentException("指定 OCR 位置必须绑定原文件指纹。");
        if(maximum<0||maximum>1000||mode!=Mode.NATIVE_ONLY&&maximum==0)throw new IllegalArgumentException("OCR 计划预算必须在 1 至 1000 之间。");
        this.maxSelectedImages=maximum;this.reason=cn.bidcheck.compat.Java8Api.strip(cn.bidcheck.compat.Java8Api.requireNonNullElse(reason, ""));
        if(this.reason.length()>500)throw new IllegalArgumentException("OCR 计划说明超限。");
        this.selections=cn.bidcheck.compat.Java8Api.listCopyOf(selections);this.cues=cn.bidcheck.compat.Java8Api.listCopyOf(cues);
        if(this.selections.size()>20_000||this.cues.size()>1000)throw new IllegalArgumentException("OCR 计划超限。");
        this.hash=digest(canonical());
    }
    public static ExtractionPlan full(){return new ExtractionPlan(Mode.FULL,"",cn.bidcheck.compat.Java8Api.listOf(),1000,"识别所有原生文字不足或包含图像的位置",cn.bidcheck.compat.Java8Api.listOf());}
    public static ExtractionPlan nativeOnly(){return new ExtractionPlan(Mode.NATIVE_ONLY,"",cn.bidcheck.compat.Java8Api.listOf(),0,"先建立原生文档地图，图像内容尚未识别",cn.bidcheck.compat.Java8Api.listOf());}
    public static ExtractionPlan selected(String sha256,List<OcrSelection> positions,int maximum,String reason){return new ExtractionPlan(Mode.SELECTED,sha256,positions,maximum,reason,cn.bidcheck.compat.Java8Api.listOf());}
    public static ExtractionPlan fromCheckpoints(List<Checkpoint> checkpoints){
        if(checkpoints==null)return nativeOnly();
        java.util.List<cn.bidcheck.domain.Domain.Checkpoint> selected=cn.bidcheck.compat.Java8Api.toList(checkpoints.stream().filter(Objects::nonNull).filter(c->c.confirmed()&&c.enabled()).sorted(Comparator.comparing(c->Objects.toString(c.id(),""))));
        if(selected.isEmpty())return nativeOnly();
        if(selected.size()>1000)throw new IllegalArgumentException("OCR 检查点计划超限。");
        if(selected.stream().anyMatch(c->"FULL_TEXT_REVIEW".equals(c.type())&&"fullTextConsistency".equals(c.field())))
            return full().withImageBudget(DEFAULT_SELECTED_IMAGES);
        List<Cue> cues=new ArrayList<>();for(cn.bidcheck.domain.Domain.Checkpoint c:selected)cues.add(new Cue(safe(c.id()),safe(c.title()),terms(c),ruleSignature(c)));
        return new ExtractionPlan(Mode.CHECKPOINT_DRIVEN,"",cn.bidcheck.compat.Java8Api.listOf(),DEFAULT_SELECTED_IMAGES,"根据已确认并启用的检查点选择图像位置；未匹配的位置保留延期",cues);
    }
    public ExtractionPlan withImageBudget(int maximum){return new ExtractionPlan(mode,sourceSha256,selections,mode==Mode.NATIVE_ONLY?0:maximum,reason,cues);}
    public ExtractionPlan bindSource(String sha256){return new ExtractionPlan(mode,sha256,selections,maxSelectedImages,reason,cues);}
    public Mode mode(){return mode;}
    public String sourceSha256(){return sourceSha256;}
    public List<OcrSelection> selections(){return selections;}
    public int maxSelectedImages(){return maxSelectedImages;}
    public String reason(){return reason;}
    public String hash(){return hash;}
    public String sourceBoundHash(String actualSha256){return digest(hash+"\n"+actualSha256);}

    /** Final outcome summary, deliberately not a claim that image content or business requirements are fully verified. */
    public Map<String,Object> describe(List<Page> pages){
        List<Page> values=pages==null?cn.bidcheck.compat.Java8Api.listOf():pages;java.util.LinkedHashMap<java.lang.String, java.lang.Object> out=new LinkedHashMap<String,Object>();
        out.put("schemaVersion",VERSION);out.put("mode",mode.name());out.put("planHash",hash);out.put("sourceSha256",sourceSha256);out.put("maxSelectedImages",maxSelectedImages);out.put("reason",reason);
        out.put("checkpointIds",cn.bidcheck.compat.Java8Api.toList(cues.stream().map(Cue::checkpointId)));out.put("selections",selections);
        out.put("locationCount",values.size());out.put("recognizedLocationCount",values.stream().filter(p->safe(p.parser()).contains("OCR")).count());
        out.put("deferredLocationCount",values.stream().filter(p->safe(p.warning()).contains("[OCR_DEFERRED]")).count());
        out.put("incompleteLocationCount",values.stream().filter(p->safe(p.warning()).contains("[OCR未完成]")).count());
        out.put("ocrCoverageComplete",values.stream().noneMatch(p->safe(p.warning()).contains("[OCR未完成]")));
        out.put("boundary","计划匹配只用于选择识别工作；延期位置不能用于证明材料缺失，识别覆盖不等于业务核验通过。");
        return Collections.unmodifiableMap(out);
    }

    Resolved resolve(List<NativeLocation> map,int parserMaximum) throws java.io.InterruptedIOException {
        Map<String,Decision> decisions=new LinkedHashMap<>();List<Map<String,Object>> unmatched=new ArrayList<>();
        Map<String,OcrSelection> explicit=new HashMap<>();
        for(OcrSelection selection:selections){String key=locationKey(selection.locationType(),selection.position());if(explicit.putIfAbsent(key,selection)!=null)throw new IllegalArgumentException("OCR 计划包含重复位置。");}
        Map<Integer,List<Cue>> matches=new HashMap<>();
        if(mode==Mode.CHECKPOINT_DRIVEN)for(int i=0;i<map.size();i++){
            interrupted();
            String text=compact(map.get(i).text());List<Cue> hit=new ArrayList<>();
            if(!cn.bidcheck.compat.Java8Api.isBlank(text))for(Cue cue:cues)if(cue.terms.stream().anyMatch(text::contains))hit.add(cue);
            if(!hit.isEmpty())matches.put(i,hit);
        }
        int remaining=Math.min(maxSelectedImages,parserMaximum);
        Set<String> seen=new HashSet<>();
        for(int i=0;i<map.size();i++){
            interrupted();
            NativeLocation location=map.get(i);String locationKey=locationKey(location.locationType,location.position);seen.add(locationKey);
            OcrSelection selection=explicit.get(locationKey);List<Cue> matched=matches.getOrDefault(i,cn.bidcheck.compat.Java8Api.listOf());NativeLocation anchor=null;
            if(mode==Mode.CHECKPOINT_DRIVEN&&matched.isEmpty()&&location.ocrUnits>0&&compact(location.text).length()<80){
                // Bounded work candidates, not evidence that separate pages belong to the same person/table.
                if(!newMaterialHeading(location.text))for(int previous=i-1;previous>=Math.max(0,i-12);previous--){
                    NativeLocation previousLocation=map.get(previous);String text=compact(previousLocation.text);
                    if(text.length()<=160&&!text.contains("目录")&&matches.containsKey(previous)){
                        matched=matches.get(previous);anchor=previousLocation;break;
                    }
                    if(previousLocation.ocrUnits==0||text.length()>=80||newMaterialHeading(previousLocation.text))break;
                }
            }
            for(int image=1;image<=location.ocrUnits;image++){
                boolean selected=mode==Mode.FULL||mode==Mode.SELECTED&&selection!=null&&(selection.imageIndexes().isEmpty()||selection.imageIndexes().contains(image))||mode==Mode.CHECKPOINT_DRIVEN&&!matched.isEmpty();
                String code=mode==Mode.NATIVE_ONLY?"NATIVE_ONLY":mode==Mode.CHECKPOINT_DRIVEN?"NO_NATIVE_MATCH":"NOT_SELECTED";
                List<String> reasons=cn.bidcheck.compat.Java8Api.listOf("未纳入本次识别计划，内容仍未知");List<String> ids=cn.bidcheck.compat.Java8Api.listOf();
                if(selected){
                    if(mode==Mode.FULL){code="FULL_EXTRACTION";reasons=cn.bidcheck.compat.Java8Api.listOf("原生文字不足或含图像，按完整识别策略处理");}
                    else if(mode==Mode.SELECTED){code="EXPLICIT_SELECTION";reasons=selection.reasons().isEmpty()?cn.bidcheck.compat.Java8Api.listOf("明确选择此来源位置"):selection.reasons();}
                    else{code=anchor!=null?"CONTINUATION_CANDIDATE":"MATCHED_NATIVE_TEXT";String prefix=anchor==null?"原生文字匹配：":"位置 "+anchor.locationType+" "+anchor.position+" 标题后的有界图像续页候选（不证明业务关联）：";reasons=cn.bidcheck.compat.Java8Api.toList(matched.stream().map(c->prefix+c.title).limit(6));ids=cn.bidcheck.compat.Java8Api.toList(matched.stream().map(Cue::checkpointId).distinct().limit(100));}
                    if(remaining<=0){selected=false;code="PLAN_IMAGE_BUDGET";reasons=cn.bidcheck.compat.Java8Api.listOf("已达到本次计划的图像预算，其余位置延期");}else remaining--;
                }
                decisions.put(unitKey(location.locationType,location.position,image),new Decision(selected,code,reasons,ids));
            }
            if(selection!=null)for(Integer index:selection.imageIndexes())if(index>location.ocrUnits)unmatched.add(cn.bidcheck.compat.Java8Api.mapOf("position", location.position, "locationType", location.locationType, "imageIndex", index, "code", "IMAGE_INDEX_NOT_FOUND"));
            if(selection!=null&&location.ocrUnits==0)unmatched.add(cn.bidcheck.compat.Java8Api.mapOf("position", location.position, "locationType", location.locationType, "code", "NO_OCR_UNIT_AT_POSITION"));
        }
        for(OcrSelection selection:selections)if(!seen.contains(locationKey(selection.locationType(),selection.position())))unmatched.add(cn.bidcheck.compat.Java8Api.mapOf("position", selection.position(), "locationType", selection.locationType(), "code", "POSITION_NOT_FOUND"));
        return new Resolved(Collections.unmodifiableMap(decisions),cn.bidcheck.compat.Java8Api.listCopyOf(unmatched));
    }
    private static boolean newMaterialHeading(String value){String text=compact(value);
        if(text.contains("目录")||TERMS.stream().anyMatch(term->text.contains(compact(term))))return true;
        return cn.bidcheck.compat.Java8Extras.lines(safe(value)).map(cn.bidcheck.compat.Java8Api::strip).filter(line->line.length()<100).anyMatch(line->line.matches("^(?:第[一二三四五六七八九十百0-9]+[章节]|[一二三四五六七八九十]+[、.．]|\\d+(?:[.．]\\d+)*[、.．\\s]+).*?[A-Za-z\\p{IsHan}].*"));
    }
    static String locationKey(String type,int position){return type+":"+position;}
    static String unitKey(String type,int position,int image){return locationKey(type,position)+":"+image;}
    private String canonical(){
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> out=new LinkedHashMap<String,Object>();out.put("version",VERSION);out.put("mode",mode.name());out.put("source",sourceSha256);out.put("maximum",maxSelectedImages);out.put("reason",reason);
        out.put("selections",cn.bidcheck.compat.Java8Api.toList(selections.stream().sorted(Comparator.comparing(OcrSelection::locationType).thenComparingInt(OcrSelection::position))));
        out.put("cues",cn.bidcheck.compat.Java8Api.toList(cues.stream().map(c->cn.bidcheck.compat.Java8Api.mapOf("id", c.checkpointId, "title", c.title, "terms", c.terms, "ruleSignature", c.ruleSignature))));
        try{return new ObjectMapper().writeValueAsString(out);}catch(Exception e){throw new IllegalArgumentException("OCR 计划无法序列化。");}
    }
    private static final List<String> TERMS=cn.bidcheck.compat.Java8Api.listOf("投标函", "投标报价", "报价表", "开标一览表", "工程量清单", "工期", "进度计划", "投标有效期", "联合体", "联合体协议", "营业执照", "统一社会信用代码", "安全生产许可证", "资质证书", "资格证书", "注册证书", "注册建造师", "注册建筑师", "项目经理", "项目负责人", "施工负责人", "设计负责人", "技术负责人", "安全员", "主要负责人", "拟派人员", "人员配备", "人员情况表", "社会保险", "社保证明", "养老保险", "缴费证明", "任职", "劳动合同", "身份证", "授权委托书", "法定代表人", "业绩", "合同协议书", "合同金额", "竣工验收", "中标通知书", "质量管理体系", "环境管理体系", "职业健康安全", "财务报表", "审计报告", "投标保证金", "保函", "承诺书", "信誉声明", "信用中国", "设计方案", "施工组织设计", "技术方案", "施工方案", "质量保证", "保修", "质保", "车辆", "车牌", "发动机", "VIN");
    private static List<String> terms(Checkpoint checkpoint){
        String original=safe(checkpoint.title())+"\n"+safe(checkpoint.expected())+"\n"+safe(checkpoint.field());String text=compact(original);
        LinkedHashSet<String> terms=new LinkedHashSet<>();for(String term:TERMS)if(text.contains(compact(term)))terms.add(compact(term));
        java.util.regex.Matcher quoted=Pattern.compile("[《「“]([^》」”]{2,50})[》」”]").matcher(original);while(quoted.find()&&terms.size()<64)terms.add(compact(quoted.group(1)));
        for(String part:safe(checkpoint.title()).split("[，,；;：:（）()、/\\s]+")){String value=compact(part);if(value.length()>=3&&value.length()<=24&&terms.size()<64)terms.add(value);}
        String field=safe(checkpoint.field()).toLowerCase(Locale.ROOT);
        if(text.contains("社保")||text.contains("社会保险")||text.contains("养老保险")||field.contains("month")||field.contains("social"))terms.addAll(cn.bidcheck.compat.Java8Api.listOf("社保", "缴费明细", "社会保险", "养老保险", "参保证明", "缴费证明"));
        if(text.contains("人员")||field.contains("person")||field.contains("certificate"))terms.addAll(cn.bidcheck.compat.Java8Api.listOf("人员情况表", "拟在本合同任职", "资格证书", "注册证书", "执业资格", "职称证书"));
        if(text.contains("有效期")&&text.contains("证")||field.contains("certificateexpiry"))terms.addAll(cn.bidcheck.compat.Java8Api.listOf("资格证书", "注册证书", "有效期至"));
        if(text.contains("业绩")||field.contains("contract"))terms.addAll(cn.bidcheck.compat.Java8Api.listOf("业绩", "合同协议书", "合同签订", "中标通知书", "竣工验收"));
        if(field.contains("company")||field.contains("creditcode"))terms.addAll(cn.bidcheck.compat.Java8Api.listOf("营业执照", "企业名称", "统一社会信用代码"));
        if(field.contains("projectname")||field.contains("projectnumber"))terms.addAll(cn.bidcheck.compat.Java8Api.listOf("投标函", "项目名称", "项目编号"));
        if(field.contains("price")||field.contains("amount"))terms.addAll(cn.bidcheck.compat.Java8Api.listOf("投标报价", "报价表"));
        return cn.bidcheck.compat.Java8Api.listCopyOf(terms);
    }
    static String compact(String value){return safe(value).replaceAll("[\\s\\p{Z}]+","").toLowerCase(Locale.ROOT);}
    private static String safe(String value){return value==null?"":value;}
    private static String ruleSignature(Checkpoint checkpoint){try{return digest(new ObjectMapper().writeValueAsString(checkpoint));}catch(Exception e){throw new IllegalArgumentException("检查点快照无法序列化。");}}
    private static void interrupted() throws java.io.InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("文档解析已中止。");}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
