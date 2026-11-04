import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Offline Java 8 implementation of the ticket package's evidence checks. */
public final class TicketAcceptance {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int JAVA8_BASELINE_TESTS=4177;
    private static final int JAVA8_BASELINE_SKIPPED=18;
    private static final Pattern GAP=Pattern.compile("\\b([A-Z][A-Z0-9_]{6,}):");
    private final List<Map<String,Object>> checks=new ArrayList<>();
    private final Map<String,Integer> totals=new LinkedHashMap<>();
    private void check(boolean hard,boolean ok,String name,String detail){add(ok?"通过":hard?"失败":"警告",name,detail);}
    private void add(String state,String name,String detail){
        Map<String,Object> result=new LinkedHashMap<>();result.put("state",state);result.put("name",name);result.put("detail",detail);
        checks.add(result);totals.put(state,totals.getOrDefault(state,0)+1);
    }
    private static String read(Path p)throws IOException{return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    private static String str(JsonNode n,String key){return n.path(key).asText("");}
    private static Set<String> strings(JsonNode list){Set<String>s=new TreeSet<>();for(JsonNode v:list)s.add(v.asText());return s;}
    private static Set<String> matches(Pattern regex,String value){
        Set<String>s=new TreeSet<>();Matcher m=regex.matcher(value);while(m.find())s.add(m.group(1));return s;
    }

    private static final class Row {
        final String state,reason;
        Row(String state,String reason){this.state=state;this.reason=reason;}
    }
    private static Map<String,Row> rows(Path file)throws IOException{
        Map<String,Row> result=new LinkedHashMap<>();
        Pattern row=Pattern.compile("^\\|\\s*.*?<br>\\x60([0-9a-fA-F-]{36})\\x60\\s+\\|\\s+[^|]+\\|\\s+([^|]+?)\\s+\\|\\s+[^|]+\\|\\s+(.*?)\\s*\\|$");
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
            if(!line.startsWith("|")||!line.contains("<br>\u0060"))continue;
            Matcher m=row.matcher(line);if(!m.matches())throw new IOException("Malformed checkpoint row in "+file);
            if(result.put(m.group(1),new Row(m.group(2).trim(),m.group(3)))!=null)throw new IOException("Repeated checkpoint "+m.group(1));
        }
        if(result.isEmpty())throw new IOException("No checkpoint rows in "+file);
        return result;
    }
    private static Map<String,Row> baseline(Path path,String expectedRun)throws IOException{
        if(path.getFileName().toString().endsWith(".json")){
            AcceptanceReport.Report report=new AcceptanceReport.Report(path.toString());
            if(!report.id.equals(expectedRun))throw new IOException("Baseline report does not match counts.json");
            Map<String,Row> result=new LinkedHashMap<>();
            for(AcceptanceReport.Row r:report.rows.values())result.put(r.id,new Row(AcceptanceReport.STATES.get(r.state),r.reason));
            return result;
        }
        if(!read(path).contains(expectedRun))throw new IOException("Missing baseline run ID in comparison");
        return rows(path);
    }
    private void ticketCases(JsonNode spec,Map<String,Row> current,List<JsonNode> claims,boolean dateConfirmed){
        if(!spec.path("cases").isArray()||spec.path("cases").size()!=59)throw new IllegalArgumentException("Expected all 59 supplied ticket cases");
        for(JsonNode c:spec.path("cases")){
            String prefix=str(c,"id"),name=str(c,"ticket")+" "+prefix+" "+str(c,"name");
            if(c.path("needs_reference_date").asBoolean()&&!dateConfirmed){add("跳过",name,"待确认实际截止日并使用该时点重跑");continue;}
            boolean hard="hard".equals(str(c,"level"));List<String> ids=new ArrayList<>(),problems=new ArrayList<>();
            for(String id:current.keySet())if(id.startsWith(prefix))ids.add(id);
            if(ids.size()!=1){check(hard,false,name,"匹配到 "+ids.size()+" 个检查项");continue;}
            Row row=current.get(ids.get(0));Set<String> allowed=strings(c.path("allowed_status"));
            if(!allowed.isEmpty()&&!allowed.contains(row.state)||strings(c.path("forbidden_status")).contains(row.state))problems.add("本轮状态 "+row.state);
            Set<String> forbidden=strings(c.path("forbidden_gaps"));forbidden.retainAll(matches(GAP,row.reason));
            if(!forbidden.isEmpty())problems.add("仍有缺口 "+forbidden);
            for(String t:strings(c.path("forbidden_reason")))if(row.reason.contains(t))problems.add("原因仍含 "+t);
            String type=str(c,"required_claim_type");
            if(!type.isEmpty()&&!row.state.equals("满足要求")&&claims.stream().noneMatch(q->str(q,"checkpointId").equals(ids.get(0))&&str(q,"inferenceType").equals(type)))problems.add("缺确认记录 "+type);
            check(hard,problems.isEmpty(),name,String.join("，",problems));
        }
    }
    private List<JsonNode> claims(JsonNode export,String run){
        if(!run.equals(str(export,"runId"))||!export.path("claims").isArray())throw new IllegalArgumentException("Wrong or missing confirmation export");
        List<JsonNode> own=new ArrayList<>();Map<List<Object>,Integer> pairs=new HashMap<>();int warnings=0;
        for(JsonNode c:export.path("claims")){
            String origin=c.hasNonNull("originRunId")?str(c,"originRunId"):str(c,"runId");
            if(origin.isEmpty())throw new IllegalArgumentException("Confirmation record origin missing");
            if(!run.equals(origin))continue;
            own.add(c);Set<String> sources=strings(c.path("proposedFact").path("sourceIds"));
            List<Object> key=Arrays.asList(str(c,"checkpointId"),sources);pairs.put(key,pairs.getOrDefault(key,0)+1);
            if("PERSON_IDENTITY".equals(str(c,"inferenceType"))&&!c.path("risks").toString().contains("身份证"))
                for(JsonNode e:c.path("evidences"))if(Pattern.compile("\\d{17}[0-9Xx]").matcher(str(e,"quote")).find()){warnings++;break;}
        }
        long repeats=pairs.values().stream().filter(i->i>1).count();
        check(true,repeats==0,"同一事项和同一来源配对只出一张卡",repeats+" 组重复，按 originRunId 计入本轮 "+own.size()+" 张");
        check(false,warnings==0,"身份证号可比对时的询问",warnings+" 张身份证提示需复核");
        return own;
    }
    private void global(JsonNode spec,JsonNode counts,Map<String,Row> current,Map<String,Row> base){
        check(true,current.size()==109&&base.keySet().equals(current.keySet())&&counts.path("exact109").asBoolean(false),"109个原始ID与基线一致",current.size()+" 个");
        check(true,str(counts,"baselineRunId").equals(str(spec,"baseline_run")),"基线运行一致",str(counts,"baselineRunId"));
        List<String> lost=new ArrayList<>();Set<String> exceptions=strings(spec.path("allow_pass_removed"));
        for(Map.Entry<String,Row> e:base.entrySet())if("满足要求".equals(e.getValue().state)&&exceptions.stream().noneMatch(e.getKey()::startsWith)){
            Row now=current.get(e.getKey());if(now==null||!"满足要求".equals(now.state))lost.add(e.getKey());
        }
        check(true,lost.isEmpty(),"旧通过不回退",String.join(", ",lost));
        check(true,counts.path("naturalCompletion").asBoolean(false)&&"COMPLETED".equals(str(counts,"status")),"任务自然结束",str(counts,"status"));
        JsonNode limits=spec.path("limits");long elapsed=number(counts,"wallMillis"),idle=number(counts,"noProgressRecoveryRecords"),
            total=number(counts,"completedRecoveryRecords"),ms=number(counts,"noProgressRecoveryMillis");
        check(true,elapsed<=number(limits,"max_total_minutes")*60000,"总耗时",elapsed+" ms");
        if(idle>total||ms>number(counts,"allRecoveryMillis"))throw new IllegalArgumentException("Inconsistent recovery counts");
        check(true,BigDecimal.valueOf(idle).compareTo(limits.path("max_recovery_noprogress_ratio").decimalValue().multiply(BigDecimal.valueOf(total)))<=0,"无进展补查占比",idle+"/"+total);
        check(true,ms<=number(limits,"max_recovery_noprogress_minutes")*60000,"无进展补查耗时",ms+" ms");
    }
    private static long number(JsonNode n,String key){
        JsonNode v=n.get(key);if(v==null||!v.isIntegralNumber()||v.asLong()<0)throw new IllegalArgumentException("Missing/invalid counter "+key);return v.asLong();
    }
    private void code(Path repo,JsonNode spec)throws IOException{
        for(String group:Arrays.asList("guards_must_remain","must_contain","must_not_contain"))for(JsonNode row:spec.path("code_checks").path(group)){
            String file=str(row,"file"),keyword=str(row,"text");
            if("agent-worker/worker.py".equals(file)&&"must_not_contain".equals(group))continue;
            Path path=repo.resolve(file).normalize();if(!path.startsWith(repo))throw new IOException("Invalid spec path");
            boolean ok=Files.isRegularFile(path)&&read(path).contains(keyword)!=group.equals("must_not_contain");
            check(true,ok,group+" "+path.getFileName(),keyword);
        }
        for(String[] r:new String[][]{{"FullTextReview.java","SIGNING_LABEL","日期：2025年10月8日"},{"EngineText.java","DATE","2025 年10 月8 日"}}){
            String src=read(repo.resolve("backend/src/main/java/cn/bidcheck/engine").resolve(r[0]));
            Matcher m=Pattern.compile("\\b"+r[1]+"\\s*=\\s*(?:Pattern\\.compile\\()?\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(src);
            boolean ok=m.find()&&Pattern.compile(m.group(1).replace("\\\\","\\")).matcher(r[2]).find();
            check(true,ok,"工单1 Java日期正则 "+r[1],r[2]);
        }
    }
    private void tests(Path dir,Path log)throws Exception{
        String source=read(log);
        Set<String> suites=matches(Pattern.compile("(?m)^\\[INFO\\] Running ([\\w.$]+)\\s*$"),source);
        Matcher summaries=Pattern.compile("(?m)^\\[(?:INFO|ERROR|WARNING)\\] Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)\\s*$").matcher(source);
        long[] maven={-1,-1,-1,-1};while(summaries.find())for(int i=0;i<4;i++)maven[i]=Long.parseLong(summaries.group(i+1));
        long[] xmlCounts=new long[4];Set<String> passed=new HashSet<>();boolean allXml=!suites.isEmpty();
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setExpandEntityReferences(false);factory.setXIncludeAware(false);DocumentBuilder builder=factory.newDocumentBuilder();
        String[] attrs={"tests","failures","errors","skipped"};
        for(String suite:suites){
            Path path=dir.resolve("TEST-"+suite+".xml");
            if(!Files.isRegularFile(path)){allXml=false;continue;}
            Element root=builder.parse(path.toFile()).getDocumentElement();
            if(!suite.equals(root.getAttribute("name")))throw new IOException("Suite name mismatch "+path);
            for(int i=0;i<4;i++)xmlCounts[i]+=Long.parseLong(root.getAttribute(attrs[i]));
            NodeList cases=root.getElementsByTagName("testcase");
            for(int i=0;i<cases.getLength();i++){
                Element el=(Element)cases.item(i);
                if(el.getElementsByTagName("error").getLength()+el.getElementsByTagName("failure").getLength()+el.getElementsByTagName("skipped").getLength()==0)
                    passed.add(el.getAttribute("classname")+"#"+el.getAttribute("name"));
            }
        }
        boolean linked=allXml&&Arrays.equals(maven,xmlCounts);
        check(true,linked&&source.contains("[INFO] BUILD SUCCESS")&&maven[1]==0&&maven[2]==0,"完整 Maven 回归与 XML 一致",Arrays.toString(maven)+"，XML "+Arrays.toString(xmlCounts));
        long executed=xmlCounts[0]-xmlCounts[3];
        check(true,linked&&xmlCounts[0]>=JAVA8_BASELINE_TESTS&&executed>=JAVA8_BASELINE_TESTS-JAVA8_BASELINE_SKIPPED,
            "Java8 测试数量及实际执行数","本轮总数 "+xmlCounts[0]+"、非跳过 "+executed+"，基线总数 "+JAVA8_BASELINE_TESTS+"、非跳过 "+(JAVA8_BASELINE_TESTS-JAVA8_BASELINE_SKIPPED));
        String prefix="cn.bidcheck.verification.agent.";
        for(String test:Arrays.asList("JavaAgentHttpTest#realHttpWireUsesExplicitKeyAndUtf8AndParsesToolCalls",
            "JavaAgentHttpTest#retriesServerFailuresTwiceButNeverRetriesUnauthorized",
            "JavaAgentHttpTest#brokenConnectionCanRecoverAndExhaustionKeepsTransportCategory",
            "JavaAgentHttpTest#absoluteRequestDeadlineStopsSlowProvider","JavaAgentRunnerTest#hostDeadlineKeepsStartedModelAndToolCounts")){
            check(true,passed.contains(prefix+test),"Java传输行为回归",test);
        }
    }
    private int run(String[] args)throws Exception{
        if(args.length!=8&&args.length!=9)throw new IllegalArgumentException("reportDir baselineComparison.md claims.json acceptance_cases.json repo surefireReports full-test.log outputDir [--reference-date-confirmed]");
        boolean dateConfirmed=args.length==9&&"--reference-date-confirmed".equals(args[8]);
        if(args.length==9&&!dateConfirmed)throw new IllegalArgumentException("Unrecognized reference date flag");
        Path dir=Paths.get(args[0]),out=Paths.get(args[7]);
        JsonNode counts=JSON.readTree(dir.resolve("counts.json").toFile()),spec=JSON.readTree(new File(args[3])),export=JSON.readTree(new File(args[2]));
        String run=str(counts,"runId");
        if(run.isEmpty()||!read(dir.resolve("comparison.md")).contains(run))throw new IOException("Comparison not linked to run");
        Map<String,Row> current=rows(dir.resolve("comparison.md")),base=baseline(Paths.get(args[1]),str(counts,"baselineRunId"));
        global(spec,counts,current,base);ticketCases(spec,current,claims(export,run),dateConfirmed);
        code(Paths.get(args[4]).toAbsolutePath().normalize(),spec);tests(Paths.get(args[5]),Paths.get(args[6]));
        Map<String,Object> report=new LinkedHashMap<>();report.put("runId",run);report.put("cases",spec.path("cases").size());
        report.put("referenceDateConfirmed",dateConfirmed);report.put("totals",totals);report.put("checks",checks);
        String boundary="适配纯Java8。旧worker.py文本断言替换为真实Java HTTP及调查超时回归报告。旧Java/Python测试源码行计数替换为完整Maven运行和Surefire XML实际执行数。时间与比例按精确毫秒和次数判定。";
        report.put("migrationBoundary",boundary);Files.createDirectories(out);
        if(Files.exists(out.resolve("acceptance.json"))||Files.exists(out.resolve("acceptance.md")))throw new IOException("Existing acceptance output");
        Files.write(out.resolve("acceptance.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
        StringBuilder text=new StringBuilder("# 工单验收\n\n运行 ").append(run).append("。").append(boundary).append("\n\n");
        for(String state:Arrays.asList("失败","警告","跳过","通过"))for(Map<String,Object> c:checks)
            if(state.equals(c.get("state")))text.append("- [").append(state).append("] ").append(c.get("name")).append("　").append(c.get("detail")).append('\n');
        text.append("\n合计 ").append(totals).append('\n');
        Files.write(out.resolve("acceptance.md"),text.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println(totals);return totals.getOrDefault("失败",0)>0?1:0;
    }
    public static void main(String[] args){
        try{System.exit(new TicketAcceptance().run(args));}catch(Exception e){System.err.println("Invalid evidence: "+e);System.exit(2);}
    }
}
