package cn.bidcheck.document;

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
    private record Cue(String checkpointId,String title,List<String> terms,String ruleSignature){}
    record NativeLocation(int position,String locationType,String text,int imageCount,int ocrUnits){}
    record Decision(boolean selected,String code,List<String> reasons,List<String> checkpointIds){}
    record Resolved(Map<String,Decision> decisions,List<Map<String,Object>> unresolvedSelections){}
    private final Mode mode;
    private final String sourceSha256,reason,hash;
    private final List<OcrSelection> selections;
    private final List<Cue> cues;
    private final int maxSelectedImages;

    private ExtractionPlan(Mode mode,String source,List<OcrSelection> selections,int maximum,String reason,List<Cue> cues){
        this.mode=Objects.requireNonNull(mode);this.sourceSha256=Objects.requireNonNullElse(source,"").strip().toLowerCase(Locale.ROOT);
        if(!sourceSha256.isEmpty()&&!sourceSha256.matches("[0-9a-f]{64}")||mode==Mode.SELECTED&&sourceSha256.isEmpty())throw new IllegalArgumentException("指定 OCR 位置必须绑定原文件指纹。");
        if(maximum<0||maximum>1000||mode!=Mode.NATIVE_ONLY&&maximum==0)throw new IllegalArgumentException("OCR 计划预算必须在 1 至 1000 之间。");
        this.maxSelectedImages=maximum;this.reason=Objects.requireNonNullElse(reason,"").strip();
        if(this.reason.length()>500)throw new IllegalArgumentException("OCR 计划说明超限。");
        this.selections=List.copyOf(selections);this.cues=List.copyOf(cues);
        if(this.selections.size()>20_000||this.cues.size()>1000)throw new IllegalArgumentException("OCR 计划超限。");
        this.hash=digest(canonical());
    }
    public static ExtractionPlan full(){return new ExtractionPlan(Mode.FULL,"",List.of(),1000,"识别所有原生文字不足或包含图像的位置",List.of());}
    public static ExtractionPlan nativeOnly(){return new ExtractionPlan(Mode.NATIVE_ONLY,"",List.of(),0,"先建立原生文档地图，图像内容尚未识别",List.of());}
    public static ExtractionPlan selected(String sha256,List<OcrSelection> positions,int maximum,String reason){return new ExtractionPlan(Mode.SELECTED,sha256,positions,maximum,reason,List.of());}
    public static ExtractionPlan fromCheckpoints(List<Checkpoint> checkpoints){
        if(checkpoints==null)return nativeOnly();
        var selected=checkpoints.stream().filter(Objects::nonNull).filter(c->c.confirmed()&&c.enabled()).sorted(Comparator.comparing(c->Objects.toString(c.id(),""))).toList();
        if(selected.isEmpty())return nativeOnly();
        if(selected.size()>1000)throw new IllegalArgumentException("OCR 检查点计划超限。");
        if(selected.stream().anyMatch(c->"FULL_TEXT_REVIEW".equals(c.type())&&"fullTextConsistency".equals(c.field())))
            return full().withImageBudget(DEFAULT_SELECTED_IMAGES);
        List<Cue> cues=new ArrayList<>();for(var c:selected)cues.add(new Cue(safe(c.id()),safe(c.title()),terms(c),ruleSignature(c)));
        return new ExtractionPlan(Mode.CHECKPOINT_DRIVEN,"",List.of(),DEFAULT_SELECTED_IMAGES,"根据已确认并启用的检查点选择图像位置；未匹配的位置保留延期",cues);
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
        List<Page> values=pages==null?List.of():pages;var out=new LinkedHashMap<String,Object>();
        out.put("schemaVersion",VERSION);out.put("mode",mode.name());out.put("planHash",hash);out.put("sourceSha256",sourceSha256);out.put("maxSelectedImages",maxSelectedImages);out.put("reason",reason);
        out.put("checkpointIds",cues.stream().map(Cue::checkpointId).toList());out.put("selections",selections);
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
            if(!text.isBlank())for(Cue cue:cues)if(cue.terms.stream().anyMatch(text::contains))hit.add(cue);
            if(!hit.isEmpty())matches.put(i,hit);
        }
        int remaining=Math.min(maxSelectedImages,parserMaximum);
        Set<String> seen=new HashSet<>();
        for(int i=0;i<map.size();i++){
            interrupted();
            NativeLocation location=map.get(i);String locationKey=locationKey(location.locationType,location.position);seen.add(locationKey);
            OcrSelection selection=explicit.get(locationKey);List<Cue> matched=matches.getOrDefault(i,List.of());NativeLocation anchor=null;
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
                List<String> reasons=List.of("未纳入本次识别计划，内容仍未知");List<String> ids=List.of();
                if(selected){
                    if(mode==Mode.FULL){code="FULL_EXTRACTION";reasons=List.of("原生文字不足或含图像，按完整识别策略处理");}
                    else if(mode==Mode.SELECTED){code="EXPLICIT_SELECTION";reasons=selection.reasons().isEmpty()?List.of("明确选择此来源位置"):selection.reasons();}
                    else{code=anchor!=null?"CONTINUATION_CANDIDATE":"MATCHED_NATIVE_TEXT";String prefix=anchor==null?"原生文字匹配：":"位置 "+anchor.locationType+" "+anchor.position+" 标题后的有界图像续页候选（不证明业务关联）：";reasons=matched.stream().map(c->prefix+c.title).limit(6).toList();ids=matched.stream().map(Cue::checkpointId).distinct().limit(100).toList();}
                    if(remaining<=0){selected=false;code="PLAN_IMAGE_BUDGET";reasons=List.of("已达到本次计划的图像预算，其余位置延期");}else remaining--;
                }
                decisions.put(unitKey(location.locationType,location.position,image),new Decision(selected,code,reasons,ids));
            }
            if(selection!=null)for(Integer index:selection.imageIndexes())if(index>location.ocrUnits)unmatched.add(Map.of("position",location.position,"locationType",location.locationType,"imageIndex",index,"code","IMAGE_INDEX_NOT_FOUND"));
            if(selection!=null&&location.ocrUnits==0)unmatched.add(Map.of("position",location.position,"locationType",location.locationType,"code","NO_OCR_UNIT_AT_POSITION"));
        }
        for(OcrSelection selection:selections)if(!seen.contains(locationKey(selection.locationType(),selection.position())))unmatched.add(Map.of("position",selection.position(),"locationType",selection.locationType(),"code","POSITION_NOT_FOUND"));
        return new Resolved(Collections.unmodifiableMap(decisions),List.copyOf(unmatched));
    }
    private static boolean newMaterialHeading(String value){String text=compact(value);
        if(text.contains("目录")||TERMS.stream().anyMatch(term->text.contains(compact(term))))return true;
        return safe(value).lines().map(String::strip).filter(line->line.length()<100).anyMatch(line->line.matches("^(?:第[一二三四五六七八九十百0-9]+[章节]|[一二三四五六七八九十]+[、.．]|\\d+(?:[.．]\\d+)*[、.．\\s]+).*?[A-Za-z\\p{IsHan}].*"));
    }
    static String locationKey(String type,int position){return type+":"+position;}
    static String unitKey(String type,int position,int image){return locationKey(type,position)+":"+image;}
    private String canonical(){
        var out=new LinkedHashMap<String,Object>();out.put("version",VERSION);out.put("mode",mode.name());out.put("source",sourceSha256);out.put("maximum",maxSelectedImages);out.put("reason",reason);
        out.put("selections",selections.stream().sorted(Comparator.comparing(OcrSelection::locationType).thenComparingInt(OcrSelection::position)).toList());
        out.put("cues",cues.stream().map(c->Map.of("id",c.checkpointId,"title",c.title,"terms",c.terms,"ruleSignature",c.ruleSignature)).toList());
        try{return new ObjectMapper().writeValueAsString(out);}catch(Exception e){throw new IllegalArgumentException("OCR 计划无法序列化。");}
    }
    private static final List<String> TERMS=List.of("投标函","投标报价","报价表","开标一览表","工程量清单","工期","进度计划","投标有效期","联合体","联合体协议","营业执照","统一社会信用代码","安全生产许可证","资质证书","资格证书","注册证书","注册建造师","注册建筑师","项目经理","项目负责人","施工负责人","设计负责人","技术负责人","安全员","主要负责人","拟派人员","人员配备","人员情况表","社会保险","社保证明","养老保险","缴费证明","任职","劳动合同","身份证","授权委托书","法定代表人","业绩","合同协议书","合同金额","竣工验收","中标通知书","质量管理体系","环境管理体系","职业健康安全","财务报表","审计报告","投标保证金","保函","承诺书","信誉声明","信用中国","设计方案","施工组织设计","技术方案","施工方案","质量保证","保修","质保","车辆","车牌","发动机","VIN");
    private static List<String> terms(Checkpoint checkpoint){
        String original=safe(checkpoint.title())+"\n"+safe(checkpoint.expected())+"\n"+safe(checkpoint.field());String text=compact(original);
        LinkedHashSet<String> terms=new LinkedHashSet<>();for(String term:TERMS)if(text.contains(compact(term)))terms.add(compact(term));
        var quoted=Pattern.compile("[《「“]([^》」”]{2,50})[》」”]").matcher(original);while(quoted.find()&&terms.size()<64)terms.add(compact(quoted.group(1)));
        for(String part:safe(checkpoint.title()).split("[，,；;：:（）()、/\\s]+")){String value=compact(part);if(value.length()>=3&&value.length()<=24&&terms.size()<64)terms.add(value);}
        String field=safe(checkpoint.field()).toLowerCase(Locale.ROOT);
        if(text.contains("社保")||text.contains("社会保险")||text.contains("养老保险")||field.contains("month")||field.contains("social"))terms.addAll(List.of("社保","缴费明细","社会保险","养老保险","参保证明","缴费证明"));
        if(text.contains("人员")||field.contains("person")||field.contains("certificate"))terms.addAll(List.of("人员情况表","拟在本合同任职","资格证书","注册证书","执业资格","职称证书"));
        if(text.contains("有效期")&&text.contains("证")||field.contains("certificateexpiry"))terms.addAll(List.of("资格证书","注册证书","有效期至"));
        if(text.contains("业绩")||field.contains("contract"))terms.addAll(List.of("业绩","合同协议书","合同签订","中标通知书","竣工验收"));
        if(field.contains("company")||field.contains("creditcode"))terms.addAll(List.of("营业执照","企业名称","统一社会信用代码"));
        if(field.contains("projectname")||field.contains("projectnumber"))terms.addAll(List.of("投标函","项目名称","项目编号"));
        if(field.contains("price")||field.contains("amount"))terms.addAll(List.of("投标报价","报价表"));
        return List.copyOf(terms);
    }
    static String compact(String value){return safe(value).replaceAll("[\\s\\p{Z}]+","").toLowerCase(Locale.ROOT);}
    private static String safe(String value){return value==null?"":value;}
    private static String ruleSignature(Checkpoint checkpoint){try{return digest(new ObjectMapper().writeValueAsString(checkpoint));}catch(Exception e){throw new IllegalArgumentException("检查点快照无法序列化。");}}
    private static void interrupted() throws java.io.InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("文档解析已中止。");}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
