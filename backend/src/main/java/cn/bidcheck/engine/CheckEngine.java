package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Deterministic evidence-backed evaluation. Unavailable model/OCR work stays unresolved. */
public final class CheckEngine {
    public static final String VERSION="deterministic-source-scope-4-explicit-history-recovery";
    public Evaluation evaluate(Checkpoint rule, Document document, List<Fact> inputFacts) {
        return evaluate(rule,document,inputFacts,null);
    }
    public Evaluation evaluate(Checkpoint rule, Document document, List<Fact> inputFacts,Document tender) {
        Objects.requireNonNull(rule,"checkpoint"); Objects.requireNonNull(document,"document");
        List<Fact> facts=inputFacts==null?List.of():inputFacts.stream().filter(f -> f.evidence()!=null && Objects.equals(document.id(),f.evidence().documentId())).toList();
        if(!rule.enabled()) return result("NOT_APPLICABLE","该检查项已停用","如需检查，请启用后重新运行。",List.of(),1);
        if(!rule.confirmed()) return result("UNDETERMINED","检查点尚未人工确认","先确认招标要求、字段、运算符与单位。",source(rule),1);
        if(rule.type()==null || rule.field()==null || rule.operator()==null) return unknown(rule,"检查类型、字段或运算符缺失");
        try {
            return switch(rule.type()==null?"":rule.type()) {
                case "VALUE_COMPARE" -> numeric(rule,document,facts);
                case "ENTITY_FIELD_CONSISTENCY" -> "plateNumber".equals(rule.field())?vehicles(rule,document,facts):"personName".equals(rule.field())?people(rule,document,facts):identity(rule,document,facts);
                case "MATERIAL_EXISTENCE" -> material(rule,document,tender);
                case "DATE_VALIDITY" -> dates(rule,document,facts);
                case "FULL_TEXT_REVIEW" -> new FullTextReview().evaluate(rule,document,facts);
                case "SEMANTIC_RESPONSE" -> result("UNDETERMINED","此项需要语义判断，当前确定性引擎不能确认响应质量","请人工核对原文；配置语义服务后可再次检查。",source(rule),0);
                case "SIGNATURE_SEAL" -> result("UNDETERMINED","签字或盖章需要图像核验，文字提及不能证明已经签章","请人工核对指定位置的签字、印章和日期。",source(rule),0);
                default -> result("UNDETERMINED","未支持的检查类型："+rule.type(),"请调整检查类型或人工复核。",source(rule),0);
            };
        } catch(IllegalArgumentException | java.time.DateTimeException ex) {
            return result("UNDETERMINED","规则或事实格式无法安全比较","检查字段、日期、单位和运算符后重试。",source(rule),0);
        }
    }
    private Evaluation numeric(Checkpoint cp,Document doc,List<Fact> facts) {
        if(cp.expected()==null || cp.expected().isBlank()) return unknown(cp,"数值规则的期望值为空");
        if(cp.unit()==null || cp.unit().isBlank()) return unknown(cp,"数值规则缺少单位，不能安全比较");
        if(!Set.of("bidValidity","duration","warranty","totalPrice").contains(cp.field())) return unknown(cp,"不支持的数值字段");
        if(!Set.of(">=","<=","=",">","<").contains(cp.operator())) return unknown(cp,"不支持的比较运算符");
        var letter=BidLetterResponseProof.evaluate(cp,doc,null);
        if(letter.isPresent())return letter.get().evaluation();
        FactScopePolicy.Selection scoped=FactScopePolicy.of(doc).select(cp,facts);
        List<Fact> relevant=scoped.included();
        if(relevant.isEmpty()) return result("UNDETERMINED","未找到可明确归属当前投标及相同标段、金额口径的数值；历史合同及业绩数值不能用于此阈值比较","确认当前报价、标段和含税口径，定位原文后复核。",evidence(field(facts,cp.field())),0);
        BigDecimal expected=expected(cp);
        List<Fact> failed=new ArrayList<>(),compared=new ArrayList<>(),unresolved=new ArrayList<>(scoped.uncertain());
        Set<String> values=new HashSet<>();
        for(Fact f:relevant) {
            String value=f.normalizedValue();
            String base="totalPrice".equals(cp.field())?"CNY":"warranty".equals(cp.field())?"MONTH":"DAY";
            String factUnit=base;
            if(value.contains(":")) { String[] pieces=value.split(":",2); factUnit=pieces[0]; value=pieces[1]; }
            String requiredUnit=canonicalUnit(cp.unit());
            if("warranty".equals(cp.field()) && "YEAR".equals(requiredUnit)) requiredUnit="MONTH";
            if(!requiredUnit.isBlank() && !requiredUnit.equals(factUnit)) { unresolved.add(f); continue; }
            BigDecimal actual=new BigDecimal(value); int diff=actual.compareTo(expected);
            boolean pass=switch(cp.operator()) { case ">=" -> diff>=0; case "<=" -> diff<=0; case "=" -> diff==0; case ">" -> diff>0; case "<" -> diff<0; default -> false; };
            compared.add(f); values.add(EngineText.decimal(actual)); if(!pass) failed.add(f);
        }
        if(!failed.isEmpty()) return result("FAIL","投标数值 "+failed.stream().map(Fact::rawValue).distinct().collect(Collectors.joining("；"))+" 不符合 "+cp.operator()+" "+cp.expected()+" "+cp.unit(),"核对并修改相关响应；同步更正全文所有出现位置。",evidence(failed),1);
        if(!unresolved.isEmpty() || compared.isEmpty()) return result("UNDETERMINED","部分数值的来源范围、标段或时间/金额单位无法安全比较","区分历史合同、当前投标、标段和含税口径，明确单位后复核。",evidence(unresolved),0.5);
        if(values.size()>1) return result("WARNING","已提取数值均符合阈值，但同一字段在文件中出现不同数值","核对是否存在章节间不一致，并确认不同数值是否属于不同范围。",evidence(compared),0.85);
        // This threshold decision is scoped to the cited values. Incomplete cited images
        // are guarded by OcrReviewPolicy; other pages do not invalidate these comparisons.
        return result("PASS","已提取的数值满足 "+cp.operator()+" "+cp.expected()+" "+cp.unit(),"",evidence(compared),1);
    }
    private BigDecimal expected(Checkpoint cp) {
        String unit=cp.unit()==null?"":cp.unit();
        if("totalPrice".equals(cp.field())) return new BigDecimal(EngineText.amount(cp.expected(),unit));
        BigDecimal n=EngineText.number(cp.expected());
        if("warranty".equals(cp.field()) && "YEAR".equals(canonicalUnit(unit))) n=n.multiply(BigDecimal.valueOf(12));
        return n;
    }
    private String canonicalUnit(String value) {
        if(value==null) return "";
        return switch(value.toUpperCase(Locale.ROOT)) { case "元","万元","万","亿元","人民币","CNY" -> "CNY"; case "天","日","日历天","DAY" -> "DAY"; case "月","个月","MONTH" -> "MONTH"; case "年","YEAR" -> "YEAR"; case "工作日","WORKDAY" -> "WORKDAY"; default -> value; };
    }
    private Evaluation identity(Checkpoint cp,Document doc,List<Fact> facts) {
        if(!Set.of("projectName","projectNumber","companyName","creditCode").contains(cp.field())) return unknown(cp,"不支持的一致性字段");
        FactScopePolicy.Selection scoped=FactScopePolicy.of(doc).select(cp,facts);
        List<Fact> relevant=scoped.included();
        if(relevant.isEmpty()) return result("UNDETERMINED","未找到可明确归属当前投标及相同标段的身份字段；历史业绩和合同项目不参与本项目冲突判断","核对当前项目的封面、投标函、报价表及字段来源。",evidence(field(facts,cp.field())),0);
        List<Fact> uncertain=new ArrayList<>(scoped.uncertain());
        if(Set.of("companyName","creditCode").contains(cp.field())) {
            uncertain.addAll(relevant.stream().filter(f->!"BIDDER".equals(f.entityKey())).toList());
            relevant=relevant.stream().filter(f->"BIDDER".equals(f.entityKey())).toList();
            if(relevant.isEmpty()) return result("UNDETERMINED","已找到身份字段，但无法明确归属于当前投标人","区分投标人证照、合同甲乙方及其他主体后复核。",evidence(uncertain),0.4);
        }
        Set<String> values=relevant.stream().map(Fact::normalizedValue).collect(Collectors.toSet());
        if(values.size()>1) return result("FAIL","同一身份字段出现多个不同值："+String.join("；",values),"核对招标项目或投标人身份，统一所有出现位置。",evidence(relevant),1);
        if(cp.expected()!=null && !cp.expected().isBlank() && !values.contains(EngineText.normalized(cp.expected()))) return result("FAIL","投标内容与招标要求不一致；要求为“"+cp.expected()+"”","根据招标文件核对并修正对应字段。",evidence(relevant),1);
        if(!uncertain.isEmpty()) {
            List<Fact> support=new ArrayList<>(relevant); support.addAll(uncertain);
            return result("UNDETERMINED","明确当前范围的字段未发现矛盾，但部分记录的项目、标段或主体归属不明","区分当前投标、历史合同及第三方主体后再确认一致性。",evidence(support),0.5);
        }
        if(EngineText.incomplete(doc)) return result("UNDETERMINED","已识别字段一致，但有未解析内容","补充未识别页面后复核全文。",evidence(relevant),0.7);
        if(values.size()==1 && relevant.size()==1 && (cp.expected()==null || cp.expected().isBlank())) return result("UNDETERMINED","仅找到一次字段记录，没有第二处证据可供一致性比对","核对正文、附件和证照中的同一字段。",evidence(relevant),0.5);
        return result("PASS","已提取字段保持一致"+(cp.expected()!=null && !cp.expected().isBlank()?"，且与招标要求相符":""),"",evidence(relevant),1);
    }
    private Evaluation people(Checkpoint cp,Document doc,List<Fact> facts) {
        List<Fact> relevant=field(facts,"personName");
        if(relevant.isEmpty()) return unknown(cp,"未提取到明确角色的人员姓名");
        Map<String,List<Fact>> roles=relevant.stream().collect(Collectors.groupingBy(Fact::entityKey));
        List<Fact> compared=new ArrayList<>();
        for(var entry:roles.entrySet()) {
            var values=entry.getValue().stream().map(Fact::normalizedValue).distinct().toList();
            if(values.size()>1) return result("WARNING","同一人员角色“"+entry.getKey()+"”出现不同姓名","确认是否为同一角色或多人配置；结合证件、岗位和授权范围人工核对。",evidence(entry.getValue()),0.8);
            if(entry.getValue().size()>1) compared.addAll(entry.getValue());
        }
        if(compared.isEmpty() || EngineText.incomplete(doc)) return result("UNDETERMINED","人员跨处证据不足，或存在未解析内容","补充人员表、资格证及授权材料后核对。",evidence(relevant),0.4);
        return result("PASS","已识别的相同人员角色姓名一致","",evidence(compared),0.95);
    }
    private Evaluation vehicles(Checkpoint cp,Document doc,List<Fact> facts) {
        List<Fact> plates=field(facts,"plateNumber");
        if(plates.isEmpty()) return unknown(cp,"未识别到车牌文本；图片中的行驶证需 OCR 或人工核验");
        Map<String,String> parent=new HashMap<>();
        Map<String,String> identifierOwner=new HashMap<>();
        for(Fact f:facts) if("VEHICLE".equals(f.entityType())) {
            parent.putIfAbsent(f.entityKey(),f.entityKey());
            if(Set.of("vin","engineNumber").contains(f.field()) && f.normalizedValue()!=null && !f.normalizedValue().isBlank()) {
                String identifier=f.field()+":"+f.normalizedValue(); String previous=identifierOwner.putIfAbsent(identifier,f.entityKey());
                if(previous!=null) parent.put(root(parent,f.entityKey()),root(parent,previous));
            }
        }
        Map<String,List<Fact>> grouped=new LinkedHashMap<>();
        for(Fact plate:plates) grouped.computeIfAbsent(root(parent,plate.entityKey()),ignored->new ArrayList<>()).add(plate);
        List<Fact> compared=new ArrayList<>(),unmatched=new ArrayList<>();
        for(var entry:grouped.entrySet()) {
            List<Fact> group=entry.getValue();
            boolean strong=group.stream().anyMatch(f->f.entityKey().startsWith("VIN:") || f.entityKey().startsWith("ENGINE:"));
            long positions=group.stream().map(f->f.evidence().anchor()).distinct().count();
            if(!strong || positions<2) { unmatched.addAll(group); continue; }
            Set<String> values=group.stream().map(Fact::normalizedValue).collect(Collectors.toSet());
            List<Fact> support=new ArrayList<>(group);
            facts.stream().filter(f->"VEHICLE".equals(f.entityType()) && Set.of("vin","engineNumber").contains(f.field()) && root(parent,f.entityKey()).equals(entry.getKey())).forEach(support::add);
            if(values.size()>1) return result("FAIL","同一 VIN 或发动机号对应的车辆，车牌号在不同位置不一致："+String.join(" / ",values),"核对车辆信息表和行驶证，修正错误车牌并保留正确证明材料。",evidence(support),1);
            compared.addAll(support);
        }
        if(!unmatched.isEmpty() || compared.isEmpty()) return result("UNDETERMINED","存在缺少共同 VIN/发动机号或缺少第二处证据的车辆，不能确认全部车辆一致","补充车架号、发动机号和行驶证；仅凭所有人或车型不能判为同一车辆。",evidence(plates),0.5);
        if(EngineText.incomplete(doc)) return result("UNDETERMINED","已对齐车辆车牌一致，但部分文档内容尚未解析","完成扫描件识别后复核。",evidence(compared),0.7);
        return result("PASS","通过 VIN/发动机号对齐的车辆，其跨处车牌记录一致","",evidence(compared),1);
    }
    private String root(Map<String,String> parent,String key) {
        parent.putIfAbsent(key,key); String p=parent.get(key);
        if(p.equals(key)) return key;
        String result=root(parent,p); parent.put(key,result); return result;
    }
    private Evaluation material(Checkpoint cp,Document doc,Document tender) {
        if(cp.expected()==null || cp.expected().isBlank()) return unknown(cp,"材料名称为空");
        var aliased=MaterialAliasProof.evaluate(cp,doc,tender);
        if(aliased.isPresent())return aliased.get();
        List<Evidence> found=new ArrayList<>(); Set<String> years=new HashSet<>();
        Set<String> requestedYears=cp.unit()!=null && cp.unit().startsWith("YEAR:")?new HashSet<>(Arrays.asList(cp.unit().substring(5).split(","))):Set.of();
        List<Page> pages=doc.pages()==null?List.of():doc.pages();
        for(int pageIndex=0;pageIndex<pages.size();pageIndex++) {
            Page page=pages.get(pageIndex);
            List<String> lines=EngineText.lines(page);
            for(int i=0;i<lines.size();i++) {
                String line=lines.get(i);
                if(!line.contains(cp.expected())) continue;
                String context=String.join("\n",lines.subList(i,Math.min(lines.size(),i+5)));
                CompositionSection section=compositionCategory(cp) && "PAGE".equals(page.locationType())
                        && sectionTitle(line).equals(cp.expected()) ? compositionSection(page,i,cp.expected()) : null;
                if(section!=null) context=section.quote();
                List<Page> adjacent=new ArrayList<>();
                if(!"PAGE".equals(page.locationType())) for(int j=pageIndex+1;j<Math.min(pages.size(),pageIndex+4);j++) {
                    Page next=pages.get(j);
                    if("PAGE".equals(next.locationType())) break;
                    String following=next.text()==null?"":next.text();
                    if(following.matches("^(?:[一二三四五六七八九十]+、|第.+章|附件.+[:：]).*")) break;
                    adjacent.add(next); context+="\n"+following;
                }
                if(line.matches(".*(?:未提供|不提供|缺少|缺失|待补|将提供|承诺提供|须提供|需提供|应提供|要求提供|目录).*")) continue;
                if(line.matches(".*[.．…·]{2,}\\s*\\d+\\s*$")) continue;
                boolean attachment=line.matches(".*(?:附件|附录|证明材料)\\s*[0-9一二三四五六七八九十]*\\s*[:：、.．]\\s*.*") || line.matches(".*(?:附件|附录)\\s*[0-9一二三四五六七八九十]+\\s+.*");
                boolean content=materialContent(cp.expected(),context);
                String heading=line.replaceFirst("^(?:附件\\s*)?[（(]?[一二三四五六七八九十0-9]+(?:\\.[0-9]+)?[）)、.．\\s]+","").trim();
                if(section!=null) { content=section.hasContent(); attachment=false; }
                else if(compositionCategory(cp) && !"PAGE".equals(page.locationType()) && heading.equals(cp.expected()) && context.length()-line.length()>40) content=true;
                if(!attachment && !content) continue;
                Evidence e=EngineText.evidence(doc,page,section==null?i+1:section.line(),section==null?String.join("\n",lines.subList(i,Math.min(lines.size(),i+5))):section.quote()); found.add(e);
                for(Page linked:adjacent) if(linked.text()!=null && !linked.text().isBlank()) found.add(EngineText.evidence(doc,linked,1,linked.text()));
                var matcher=java.util.regex.Pattern.compile("20\\d{2}").matcher(context); while(matcher.find()) years.add(matcher.group());
            }
        }
        if(!found.isEmpty() && years.containsAll(requestedYears)) return result("PASS","找到明确附件或材料内容证据"+(requestedYears.isEmpty()?"":"，覆盖要求年度 "+String.join("、",new TreeSet<>(requestedYears))),"",found,0.95);
        List<Evidence> similar=compositionCategory(cp)?similarComposition(cp,doc):List.of();
        if(!similar.isEmpty()) return result("WARNING","发现与“"+cp.expected()+"”标题不同的疑似对应章节及正文，不能直接认定缺少该组成内容","对照招标要求核实章节用途、正文和适用主体；标题相近不代表实质要求已满足。",similar,0.6);
        if(EngineText.incomplete(doc)) return result("UNDETERMINED","未找到充分材料证据，且存在未解析/扫描内容，不能确认缺失","补充 OCR、材料正文或人工核对附件。",found.isEmpty()?source(cp):found,0);
        if(!cp.required()) return result("NOT_APPLICABLE","此材料为非必需项，未识别到充分证据","",found,1);
        List<Evidence> support=new ArrayList<>(source(cp)); support.addAll(found);
        if(support.isEmpty()) return unknown(cp,"未找到充分材料证据，且检查项没有招标依据");
        String reason=found.isEmpty()?"在已解析文本中未找到明确的“"+cp.expected()+"”附件或内容证据":"已找到部分材料，但未覆盖要求年度："+requestedYears.stream().filter(y->!years.contains(y)).sorted().collect(Collectors.joining("、"));
        // A complete text traversal is not proof that OCR or literal matching found every material.
        return result("UNDETERMINED",reason+"；未检索到不代表未提供，不能确认材料缺失", "人工定位对应材料及年度报告，核对别名、表格和原始页面后确认。",support,0);
    }
    private boolean compositionCategory(Checkpoint cp) { return "组成检查".equals(cp.category()) || "形式检查".equals(cp.category()); }
    /** Alias names only retrieve candidates; they never establish equivalent legal content. */
    private List<Evidence> similarComposition(Checkpoint cp,Document doc) {
        List<Set<String>> names=List.of(
                Set.of("授权委托书","法定代表人授权委托书","法人授权委托书","法定代表人授权书","法人授权书"),
                Set.of("法定代表人身份证明","法定代表人身份证明书","法人代表身份证明"),
                Set.of("实施计划","项目实施计划","实施方案","项目实施方案"),
                Set.of("投标报价表","投标报价一览表","开标一览表"));
        String expected=sectionTitle(cp.expected());
        Set<String> aliases=names.stream().filter(group->group.contains(expected)).findFirst().orElse(Set.of());
        if(aliases.isEmpty()) return List.of();
        List<Evidence> candidates=new ArrayList<>();
        List<Page> pages=doc.pages()==null?List.of():doc.pages();
        for(int at=0;at<pages.size();at++) {
            Page page=pages.get(at); List<String> lines=EngineText.lines(page);
            for(int i=0;i<lines.size();i++) {
                String title=sectionTitle(lines.get(i));
                if(title.equals(expected)||!aliases.contains(title)) continue;
                if("PAGE".equals(page.locationType())) {
                    CompositionSection section=compositionSection(page,i,title);
                    if(section.hasContent()) candidates.add(EngineText.evidence(doc,page,section.line(),section.quote()));
                } else {
                    // DOCX paragraphs are separate locations. Preserve every actual paragraph,
                    // and stop at another section instead of borrowing unrelated material.
                    List<Evidence> section=new ArrayList<>();
                    section.add(EngineText.evidence(doc,page,i+1,lines.get(i)));
                    int length=0; boolean substantive=false;
                    for(int next=at+1;next<Math.min(pages.size(),at+4);next++) {
                        Page following=pages.get(next); String body=Objects.toString(following.text(),"").strip();
                        if("PAGE".equals(following.locationType())||sectionBoundary(sectionNumber(lines.get(i)),sectionNumber(body),body,title)) break;
                        if(body.isBlank()||body.matches(".*(?:未提供|待补|将提供|承诺提供|须提供|需提供|应提供|要求提供).*")) continue;
                        length+=body.length();substantive|=body.length()>=18 && body.matches("(?s).*[。；，、：:].*");
                        section.add(EngineText.evidence(doc,following,1,following.text()));
                    }
                    if(length>40&&substantive)candidates.addAll(section);
                }
            }
        }
        return candidates;
    }
    /** Same-page composition evidence includes the actual body, with original whitespace and line anchor. */
    private CompositionSection compositionSection(Page page,int nonemptyIndex,String expected) {
        String text=page.text();
        var matcher=java.util.regex.Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").matcher(text);
        int nonempty=0,physicalLine=0,start=-1,end=-1,firstLine=1,examined=0,bodyLength=0;
        boolean substantive=false;
        String rootNumber="";
        while(matcher.find() && matcher.start()<text.length()) {
            physicalLine++;
            String line=matcher.group().strip();
            if(start<0) {
                if(line.isEmpty()) continue;
                if(nonempty++!=nonemptyIndex) continue;
                start=matcher.start();end=matcher.end();firstLine=physicalLine;rootNumber=sectionNumber(line);
                // A heading in a table of contents cannot prove that its section body is present.
                if(text.substring(0,start).matches("(?s).*(?:^|\\R)\\s*目\\s*录\\s*(?:\\R|$).*")) return new CompositionSection(line,firstLine,false);
                continue;
            }
            if(matcher.end()-start>1500 || (!line.isEmpty() && ++examined>30)) break;
            String number=sectionNumber(line);
            boolean repeatedTitle=sectionTitle(line).equals(expected);
            if(!repeatedTitle && sectionBoundary(rootNumber,number,line,expected)) break;
            end=matcher.end();
            if(line.isBlank() || repeatedTitle || line.matches("【[^】]*】|\\d{1,4}|.*[.．…·]{2,}\\s*\\d+\\s*$")
                    || !number.isEmpty() || line.matches("第[一二三四五六七八九十百0-9]+[章节篇].*")
                    || line.matches(".{1,35}(?:方案|计划|目标|分析|理念|概况|目录|设计|识别|措施|建议书)")
                    || line.matches(".*(?:未提供|待补|将提供|承诺提供|须提供|需提供|应提供|要求提供).*")) continue;
            bodyLength+=line.length();
            if(line.length()>=18 || line.matches(".*[。；，、：:].*")) substantive=true;
        }
        if(start<0) return new CompositionSection("",1,false);
        return new CompositionSection(text.substring(start,end).stripTrailing(),firstLine,bodyLength>40 && substantive);
    }
    private String sectionTitle(String value) {
        String title=value.strip().replaceFirst("^(?:附件|附录)\\s*","");
        title=title.replaceFirst("^\\d+(?:[.．]\\d+)*[.．、\\s]*","")
                .replaceFirst("^[（(]?[一二三四五六七八九十百]+[）)、:：.．\\s]+","")
                .replaceFirst("^第[一二三四五六七八九十百0-9]+[章节篇][、.．\\s]*","").replaceFirst("^[:：、]\\s*","").strip();
        return title.startsWith("【") && title.endsWith("】")?title.substring(1,title.length()-1).strip():title;
    }
    private String sectionNumber(String value) {
        var arabic=java.util.regex.Pattern.compile("^(\\d+(?:[.．]\\d+)*)(?:[.．、\\s]*)(?=[^\\d\\s.．、]).+").matcher(value);
        if(arabic.matches()) return "N:"+arabic.group(1).replace('．','.');
        if(value.matches("^[一二三四五六七八九十百]+、.*")) return "CN";
        if(value.matches("^[（(][一二三四五六七八九十百]+[）)].*")) return "PAREN";
        var chapter=java.util.regex.Pattern.compile("^第[一二三四五六七八九十百0-9]+([章节篇]).*").matcher(value);
        return chapter.matches()?"CH:"+chapter.group(1):"";
    }
    private boolean sectionBoundary(String root,String next,String line,String expected) {
        if(root.startsWith("N:") && next.startsWith("N:")) return !next.startsWith(root+".");
        if(!root.isEmpty() && root.equals(next)) return true;
        if(line.matches("^(?:附件|附录)\\s*[0-9一二三四五六七八九十]*[:：、.．\\s].*")) return true;
        if(root.isEmpty() && next.isEmpty() && line.matches(".{1,30}(?:书|报告|证明|清单|表|函|方案|计划)")) return true;
        return next.isEmpty() && EngineText.MATERIALS.stream().anyMatch(name->!name.equals(expected) && sectionTitle(line).equals(name));
    }
    private record CompositionSection(String quote,int line,boolean hasContent) { }
    private boolean materialContent(String name,String text) {
        if(name.contains("营业执照")) return text.matches("(?s).*(统一社会信用代码|注册资本|经营范围|成立日期).*" );
        if(name.contains("授权委托")) return text.matches("(?s).*(委托人|被授权人|受托人|授权代表).*" );
        if(name.contains("财务") || name.contains("审计")) return text.matches("(?s).*(审计意见|资产负债|利润表|会计师事务所|所有者权益|现金流量).*" );
        if(name.contains("社保")) return text.matches("(?s).*(缴费|参保|社会保险费|个人编号).*" );
        if(name.contains("纳税")) return text.matches("(?s).*(税款|完税|税额|征收机关).*" );
        if(name.contains("合同")) return text.matches("(?s).*(甲方|乙方|合同金额|合同编号).*" );
        if(name.contains("行驶证")) return text.matches("(?s).*(号牌号码|车辆识别代号|发动机号码).*" );
        if(name.contains("证书")) return text.matches("(?s).*(证书编号|有效期|发证机关|资质等级).*" );
        if(name.contains("保证金")) return text.matches("(?s).*(银行回单|交易流水|付款账户|电子保函|保函编号).*" );
        if(name.contains("投标函")) return text.matches("(?s).*(投标报价|投标有效期|投标总价).*" );
        return false;
    }
    private Evaluation dates(Checkpoint cp,Document doc,List<Fact> facts) {
        if(!"certificateExpiry".equals(cp.field()) || !Set.of(">=",">").contains(cp.operator())) return unknown(cp,"证书日期规则字段或运算符不受支持");
        if(cp.expected()==null || cp.expected().isBlank()) return unknown(cp,"缺少明确的投标截止日期");
        LocalDate deadline=LocalDate.parse(cp.expected()); List<Fact> relevant=field(facts,"certificateExpiry");
        if(relevant.isEmpty()) return unknown(cp,"未提取到证书有效截止日期");
        List<Fact> expired=new ArrayList<>(),unresolved=new ArrayList<>(),validated=new ArrayList<>();
        FactScopePolicy scope=FactScopePolicy.of(doc);
        for(Fact fact:relevant) {
            switch(scope.date(fact,deadline,">".equals(cp.operator())).status()) {
                case "EXPIRED" -> expired.add(fact);
                case "VALID" -> validated.add(fact);
                case "UNRESOLVED" -> unresolved.add(fact);
                default -> { /* Explicit historical records are retained, outside this decision. */ }
            }
        }
        if(!expired.isEmpty()) return result("FAIL","存在证书在要求日期 "+deadline+" 已过期或尚未生效","核实证书所属主体和适用范围，替换有效证书。",evidence(expired),1);
        if(!unresolved.isEmpty() || validated.isEmpty()) return result("UNDETERMINED","存在无法确定的证件范围或有效期起止日期，申请记录及识别说明不能替代有效截止日期","核对证件主体、日期标签、起止范围及原图。",evidence(unresolved.isEmpty()?relevant:unresolved),0.4);
        if(EngineText.incomplete(doc)) return result("UNDETERMINED","已提取证书日期有效，但仍有未解析内容","补充未识别证照后复核。",evidence(relevant),0.7);
        return result("PASS","已定位且完成日期角色核对的证件截止日期均满足要求日期 "+deadline,"",evidence(validated),1);
    }
    private List<Fact> field(List<Fact> facts,String field) { return facts.stream().filter(f->field.equals(f.field())).toList(); }
    private List<Evidence> evidence(List<Fact> facts) { return facts.stream().map(Fact::evidence).filter(Objects::nonNull).distinct().toList(); }
    private List<Evidence> source(Checkpoint cp) { return cp.source()==null?List.of():List.of(cp.source()); }
    private Evaluation unknown(Checkpoint cp,String reason) { return result("UNDETERMINED",reason,"请补充材料、定位证据或人工复核。",source(cp),0); }
    private Evaluation result(String status,String reason,String suggestion,List<Evidence> evidence,double confidence) { return new Evaluation(status,reason,suggestion,List.copyOf(evidence),confidence,"DETERMINISTIC"); }
}
