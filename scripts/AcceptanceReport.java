import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Offline Java 8 export: run-report baseline-run-report task backend.log output-directory. */
public final class AcceptanceReport {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Map<String,String> STATES=new LinkedHashMap<>();
    static {
        STATES.put("SATISFIED","满足要求"); STATES.put("RISK","存在风险");
        STATES.put("SYSTEM_INCOMPLETE","系统未完成"); STATES.put("EVIDENCE_PENDING","系统取证中");
        STATES.put("WAIT_EXTERNAL","待外部条件"); STATES.put("USER_ACTION","需你确认");
        STATES.put("NOT_APPLICABLE","不适用"); STATES.put("NOTICE","全文提示");
        STATES.put("MATERIAL_REQUIRED","请补充材料");
    }
    static final class Row {
        String id,name,state,reason;
        long duration;
        Row(JsonNode record){
            JsonNode cp=record.path("checkpoint"), a=record.path("assessment"), business=a.path("businessPresentation");
            id=required(cp,"id");name=required(cp,"title");state=required(business,"state");
            if(!STATES.containsKey(state))throw new IllegalArgumentException("Unknown persisted state "+state+" at "+id);
            Set<String> reasons=new LinkedHashSet<>();
            for(JsonNode gap:a.path("atomicCheck").path("gaps"))reasons.add(required(gap,"code")+": "+gap.path("description").asText());
            if(!business.path("reason").asText().isEmpty())reasons.add(business.path("reason").asText());
            reason=String.join(" / ",reasons);duration=a.path("elapsedMillis").asLong(-1);
        }
    }
    static final class Report {
        String id,task,status,project,bid,tender;
        Map<String,Row> rows=new LinkedHashMap<>();
        Report(String file)throws IOException{
            JsonNode body=JSON.readTree(new File(file)),run=body.path("run");
            id=required(run,"id");task=required(run,"taskId");status=required(run,"status");project=required(run,"projectId");
            bid=required(run,"documentId");tender=required(run,"tenderDocumentId");
            if(!Arrays.asList("COMPLETED","FAILED","CANCELLED").contains(status))throw new IllegalArgumentException("Nonterminal run "+status);
            if(!body.path("results").isArray())throw new IllegalArgumentException("Missing results");
            for(JsonNode result:body.path("results")){
                if(!required(result,"runId").equals(id))throw new IllegalArgumentException("Mixed result runs");
                Row r=new Row(result);if(rows.put(r.id,r)!=null)throw new IllegalArgumentException("Duplicate "+r.id);
            }
        }
        Map<String,Integer> counts(){
            Map<String,Integer> counts=new LinkedHashMap<>();for(String s:STATES.keySet())counts.put(s,0);
            for(Row r:rows.values())counts.put(r.state,counts.get(r.state)+1);return counts;
        }
    }
    static final class Recovery {
        long count,idle,millis,idleMillis;
        boolean initialPhase,completePhase;
        Recovery(String file,Report r)throws IOException{
            try(BufferedReader in=Files.newBufferedReader(Paths.get(file),StandardCharsets.UTF_8)){
                for(String line;(line=in.readLine())!=null;){
                    if(!line.contains("runId="+r.id+" ")||!line.contains("taskId="+r.task+" "))continue;
                    if(line.contains("bidcheck.check_phase ") && line.contains("phase=CHECKPOINTS ") && line.contains("state=COMPLETED"))initialPhase=true;
                    if(line.contains("bidcheck.check_phase ") && line.contains("phase=RESOLUTION ") && line.contains("state=COMPLETED"))completePhase=true;
                    if(!line.contains("bidcheck.resolution "))continue;
                    String progress=field(line,"progressMade");
                    if(!progress.equals("true")&&!progress.equals("false"))throw new IOException("Incomplete resolution progress");
                    long elapsed=Long.parseLong(field(line,"durationMs"));if(elapsed<0)throw new IOException("Invalid duration");
                    count++;millis=Math.addExact(millis,elapsed);
                    if(progress.equals("false")){idle++;idleMillis=Math.addExact(idleMillis,elapsed);}
                }
            }
            if(!initialPhase || ("COMPLETED".equals(r.status)&&!completePhase) || (count==0&&!completePhase))
                throw new IOException("Incomplete run log: checkpoint/recovery boundaries not verified for "+r.id);
        }
    }
    static String field(String line,String key)throws IOException{
        Matcher m=Pattern.compile("(?:^|\\s)"+key+"=([^\\s]+)").matcher(line);
        if(!m.find())throw new IOException("Missing log field "+key);return m.group(1);
    }
    static String required(JsonNode n,String key){
        String v=n.path(key).asText("").trim();if(v.isEmpty())throw new IllegalArgumentException("Missing field "+key);return v;
    }
    static String md(String value){return value.replace("|","&#124;").replace("\r"," ").replace("\n"," ").replace("\u0060","&#96;");}
    static void write(Path out,String name,String text)throws IOException{
        Files.write(out.resolve(name),text.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("run-report.json baseline-run-report.json task.json backend.log output-directory");
        Report old=new Report(args[1]),run=new Report(args[0]);
        JsonNode task=JSON.readTree(new File(args[2]));if(task.has("task"))task=task.path("task");
        if(!required(task,"id").equals(run.task)||!required(task,"projectId").equals(run.project))throw new IllegalArgumentException("Task/run mismatch");
        if(!old.project.equals(run.project)||!old.bid.equals(run.bid)||!old.tender.equals(run.tender))throw new IllegalArgumentException("Baseline documents differ");
        String ts=required(task,"status");boolean natural=run.status.equals("COMPLETED")&&ts.equals("SUCCEEDED");
        if(!natural&&!ts.equals(run.status))throw new IllegalArgumentException("Inconsistent terminal state "+ts);
        long elapsed=Duration.between(OffsetDateTime.parse(required(task,"createdAt")).toInstant(),OffsetDateTime.parse(required(task,"updatedAt")).toInstant()).toMillis();
        if(elapsed<0)throw new IllegalArgumentException("Invalid wall time");
        Recovery recovery=new Recovery(args[3],run);
        Set<String> missing=new LinkedHashSet<>(old.rows.keySet()),extra=new LinkedHashSet<>(run.rows.keySet());
        missing.removeAll(run.rows.keySet());extra.removeAll(old.rows.keySet());
        boolean exact=run.rows.size()==109&&old.rows.size()==109&&missing.isEmpty()&&extra.isEmpty();
        Path out=Paths.get(args[4]);Files.createDirectories(out);
        for(String name:Arrays.asList("comparison.md","findings.md","counts.json"))if(Files.exists(out.resolve(name)))throw new IOException("Existing output "+out.resolve(name));
        String minutes=BigDecimal.valueOf(elapsed).divide(BigDecimal.valueOf(60000),9,RoundingMode.CEILING).stripTrailingZeros().toPlainString();
        StringBuilder comparison=new StringBuilder("# 109 项检查对比\n\n");
        comparison.append("本轮运行 ").append(run.id).append("，状态 **").append(run.status).append("**。基线运行 ").append(old.id).append("。\n\n");
        comparison.append("本轮：").append(minutes).append(" 分钟（任务创建至终态更新，共 ").append(elapsed).append(" 毫秒）。\n\n");
        comparison.append("原始事项 ").append(run.rows.size()).append(" 个，缺失 ").append(missing.size()).append(" 个，额外 ").append(extra.size()).append(" 个。\n\n");
        comparison.append("| 事项及 ID | 上一轮 | 本轮 | 最后评估用时 | 原因及缺口 |\n|---|---|---|---|---|\n");
        for(Row row:run.rows.values()){
            Row prior=old.rows.get(row.id);
            comparison.append("| ").append(md(row.name)).append("<br>\u0060").append(row.id).append("\u0060 | ")
                    .append(prior==null?"新增":STATES.get(prior.state)).append(" | ").append(STATES.get(row.state)).append(" | ")
                    .append(row.duration<0?"未记录":row.duration+" ms").append(" | ").append(md(row.reason)).append(" |\n");
        }
        write(out,"comparison.md",comparison.toString());
        String findings="# 补查处理核算\n\n运行 "+run.id+"，任务 "+run.task+"，状态 **"+run.status+"**。\n\n"
            +recovery.count+" 条已结束补查处理记录，其中 "+recovery.idle+" 条 progressMade=false，累计明确耗时 "
            +((recovery.idleMillis+59999)/60000)+" 分钟（按整数分钟验收时上取整，精确值 "+recovery.idleMillis+" 毫秒）。\n\n"
            +"全部已结束补查累计 "+recovery.millis+" 毫秒。统计只读取该任务该运行的补查结束日志。任务墙钟用时 "+elapsed+" 毫秒。\n";
        write(out,"findings.md",findings);
        Map<String,Object> data=new LinkedHashMap<>();data.put("runId",run.id);data.put("baselineRunId",old.id);data.put("taskId",run.task);
        data.put("status",run.status);data.put("naturalCompletion",natural);data.put("wallMillis",elapsed);
        data.put("exact109",exact);data.put("missingIds",missing);data.put("extraIds",extra);data.put("before",old.counts());data.put("after",run.counts());
        data.put("completedRecoveryRecords",recovery.count);data.put("noProgressRecoveryRecords",recovery.idle);
        data.put("noProgressRatio",recovery.count==0?0:(double)recovery.idle/recovery.count);
        data.put("noProgressRecoveryMillis",recovery.idleMillis);data.put("allRecoveryMillis",recovery.millis);
        write(out,"counts.json",JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data)+"\n");
        if(!exact)throw new IllegalStateException("Coverage mismatch. See counts.json.");
    }
}
