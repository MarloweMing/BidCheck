package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.TableRelationIndex;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Conservative, source-bound extensions. Unknown context is a visible gap, never an assumed match. */
public final class ExpandedConsistencyEngine {
    public static final String VERSION = "expanded-consistency-3-context-table";
    private static final int LIMIT=20000;
    private static final Set<String> LEGACY=Set.of("companyName","totalPrice","openingTime","personName","personRole");
    private static final Map<String,String> ALIASES=new LinkedHashMap<>();
    private static final Pattern LABEL;
    private static final Pattern LOT=Pattern.compile("(?:第?([一二三四五六七八九十百0-9]+)\\s*(?:标段|标包|包件)|(?:标段|标包|包件|包号)\\s*[:：]?\\s*([一二三四五六七八九十百0-9]+))");
    private static final Pattern NUMBER=Pattern.compile("^(不超过|不大于|至多|最多|至少|不低于|不少于|大于|小于|>=|<=|≥|≤|>|<|约|大约)?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([^0-9\\s]*)$");
    private static final Pattern DATE=Pattern.compile("^(20[0-9]{2}|19[0-9]{2})[年/.-]([0-9]{1,2})[月/.-]([0-9]{1,2})日?(?:[ T ]*(上午|下午)?([0-9]{1,2})[:：时]([0-9]{1,2})?分?(?:[:：]([0-9]{1,2}))?)?$");
    private static final Pattern HISTORICAL=Pattern.compile("历史项目|历史业绩|历史合同|过往项目|过往合同|类似项目业绩|类似业绩|业绩证明|业绩合同|合同业绩|已完成项目|已完成合同");
    private static final Pattern TRIGGER=Pattern.compile("(?:自)?(?:合同签订|合同生效|合同签署|收到通知|接到通知|通知下达|订单下达|验收合格|验收通过|设备到货|收到预付款)(?:之日|当日)?(?:起|后)");
    private static final Map<String,String> CONTEXT_LABELS=new LinkedHashMap<>();
    static {
        for(Map<String,Object> definition:ConsistencyCatalog.definitions()) {
            String field=text(definition,"id");
            if(LEGACY.contains(field)||field.equals("custom"))continue;
            for(Object alias:(List<?>)definition.get("aliases")) ALIASES.put(alias.toString(),field);
        }
        LABEL=Pattern.compile("(?<![\\p{L}0-9])(?:本项目|本次投标|本次采购|本标段)?\\s*("+ALIASES.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).reduce((a,b)->a+"|"+b).orElse("(?!)")+")\\s*(?:[（(]([^）)\\r\\n]{1,30})[）)])?\\s*[:：=]\\s*([^;；。\\r\\n|｜\\t]+)");
        CONTEXT_LABELS.put("品目","object"); CONTEXT_LABELS.put("品目名称","object"); CONTEXT_LABELS.put("设备名称","object");
        CONTEXT_LABELS.put("产品名称","object"); CONTEXT_LABELS.put("服务对象","object"); CONTEXT_LABELS.put("系统名称","object");
        CONTEXT_LABELS.put("比较对象","object"); CONTEXT_LABELS.put("标的名称","object"); CONTEXT_LABELS.put("工作项","object");
        CONTEXT_LABELS.put("付款阶段","stage"); CONTEXT_LABELS.put("阶段","stage"); CONTEXT_LABELS.put("实施阶段","stage");
        CONTEXT_LABELS.put("起算条件","trigger"); CONTEXT_LABELS.put("起算事件","trigger"); CONTEXT_LABELS.put("起算点","trigger");
        CONTEXT_LABELS.put("适用条件","condition"); CONTEXT_LABELS.put("故障等级","condition"); CONTEXT_LABELS.put("测试条件","condition");
        CONTEXT_LABELS.put("测试方法","condition"); CONTEXT_LABELS.put("产品版本","version"); CONTEXT_LABELS.put("软件版本","version");
        CONTEXT_LABELS.put("适用范围","range"); CONTEXT_LABELS.put("费用范围","range"); CONTEXT_LABELS.put("部件范围","range");
        CONTEXT_LABELS.put("服务期间","period"); CONTEXT_LABELS.put("费用期间","period"); CONTEXT_LABELS.put("统计年度","period");
        CONTEXT_LABELS.put("币种","currency"); CONTEXT_LABELS.put("税基","taxBasis"); CONTEXT_LABELS.put("计量单位","unitBasis");
        CONTEXT_LABELS.put("数量单位","unitBasis"); CONTEXT_LABELS.put("日历口径","dayType");
    }

    public Map<String,Object> scan(Document document,List<Map<String,Object>> supplementalFacts) {
        return scan(document,supplementalFacts,DocumentContextIndex.build(document),TableRelationIndex.build(document));
    }
    Map<String,Object> scan(Document document,List<Map<String,Object>> supplementalFacts,DocumentContextIndex contextIndex,TableRelationIndex tableIndex) {
        Objects.requireNonNull(document,"document");
        List<Map<String,Object>> facts=new ArrayList<>(); Context context=new Context();
        int scanned=0,rejected=0,accepted=0;Set<String> unreadLocations=new LinkedHashSet<>();
        for(Page page:document.pages()==null?List.<Page>of():document.pages()) {
            interrupted();
            String location=page.locationType()+"|"+page.number();
            if(page.text()==null||page.text().isBlank()) { unreadLocations.add(location); continue; }
            if(facts.size()>=LIMIT) { unreadLocations.add(location);continue; }
            scanned++;
            if(incomplete(page))unreadLocations.add(location);
            List<Line> lines=lines(page.text()); String[] headers=null; Evidence headerEvidence=null; int tableStart=-1;
            boolean insideHtmlTable=false;
            for(Line line:lines) {
                interrupted(); if(facts.size()>=LIMIT)break;
                boolean htmlLine=insideHtmlTable||TableFactSupport.html(line.text);
                if(line.text.matches("(?is).*<table\\b.*"))insideHtmlTable=true;
                if(line.text.matches("(?is).*</table\\s*>.*"))insideHtmlTable=false;
                if(htmlLine){headers=null;continue;}
                if(line.text.length()>10000){unreadLocations.add(location);continue;}
                Evidence evidence=source(document,page,line.text,line.number,line.offset);
                DocumentContextIndex.Assignment assignment=contextIndex.at(page,line.number);
                if(assignment.boundary())context.clearObject();
                context.advance(line.text,evidence);
                context.bind(assignment);
                String[] cells=cells(line.text);
                if(cells.length>1&&isHeader(cells)) { headers=cells;headerEvidence=evidence;tableStart=line.number;continue; }
                if(headers!=null&&cells.length==headers.length&&cells.length>1) {
                    table(document,page,line,headers,cells,headerEvidence,tableStart,context,facts);continue;
                }
                if(cells.length<2&&!line.text.isBlank())headers=null;
                extract(document,page,line,context,facts);
            }
        }
        for(TableRelationIndex.Row row:tableIndex.rows()) {
            if(!row.status().equals("RESOLVED")||facts.size()>=LIMIT)continue;
            Page page=document.pages().stream().filter(p->p.number()==row.page()&&Objects.equals(p.locationType(),row.locationType())).findFirst().orElse(null);
            if(page==null||row.end()-row.start()>10000)continue;
            Context tableContext=new Context();tableContext.bind(contextIndex.at(page,row.line()));
            String[] headers=row.associations().stream().map(a->TableFactSupport.semanticHeader(row,a)).toArray(String[]::new);
            String[] values=row.associations().stream().map(a->a.rawValue().strip()).toArray(String[]::new);
            Line line=new Line(row.line(),row.start(),page.text().substring(row.start(),row.end()));
            int first=facts.size();table(document,page,line,headers,values,TableFactSupport.header(document,page,row),row.line(),tableContext,facts);
            TableFactSupport.bind(document,page,row,facts,first);
        }
        for(Map<String,Object> input:supplementalFacts==null?List.<Map<String,Object>>of():supplementalFacts) {
            interrupted();if(facts.size()>=LIMIT)break;
            if(input==null||LEGACY.contains(text(input,"field")))continue;
            if(PersonCredentialConsistency.ownedField(text(input,"field"))){rejected++;continue;}
            Map<String,Object> fact=supplemental(document,input);
            if(fact==null)rejected++;else{facts.add(fact);accepted++;}
        }
        boolean truncated=facts.size()>=LIMIT;facts=deduplicate(facts);
        List<Map<String,Object>> groups=new ArrayList<>();
        valueGroups(facts,groups);arithmeticGroups(facts,groups);temporalGroups(facts,groups);semanticGroups(facts,groups);evidenceGroups(facts,groups);
        Map<String,Object> coverage=map("pagesScanned",scanned,"pagesUnparsed",unreadLocations.size(),"occurrencesCount",facts.size(),
                "modelFactsAccepted",accepted,"modelFactsRejected",rejected,"occurrencesTruncated",truncated,
                "fields",facts.stream().map(f->f.get("field")).distinct().toList(),"categories",categoryCoverage(facts,groups),
                "relationsEvaluated",List.of("VALUE","ARITHMETIC","TEMPORAL","SEMANTIC","EVIDENCE"));
        coverage.put("tableRelations",tableIndex.summary());
        return map("version",VERSION,"catalogVersion",ConsistencyCatalog.VERSION,"overall","REVIEW_REQUIRED", "occurrences",facts,"groups",groups,"coverage",coverage,
                "limitations",List.of("扩展目录支持明确标签及简单行列表格；复杂自然语言取决于模型及原文质量，未发现事实不代表通过。",
                        "跨页汇总、任意逻辑推理、外部资质真实性和未识别附件不在本地确定性规则的保证范围内。"));
    }

    private void extract(Document doc,Page page,Line line,Context context,List<Map<String,Object>> out) {
        if(line.text.length()>10000)return;
        Matcher matcher=LABEL.matcher(line.text);
        while(matcher.find()&&out.size()<LIMIT) {
            String alias=matcher.group(1),field=ALIASES.get(alias),raw=matcher.group(3).trim();
            int next=findNextLabel(raw);if(next>=0)raw=raw.substring(0,next).stripTrailing().replaceFirst("[，,]+$","");
            if(raw.isBlank()||raw.length()>1000)continue;
            Context local=context.copy(); local.apply(localClause(line.text,matcher.start(),matcher.end()),source(doc,page,line.text,line.number,line.offset),false);
            Map<String,Object> item=create(doc,page,line.text,line.number,line.offset,field,alias,raw,matcher.group(2)==null?"":matcher.group(2),local,false);
            if(item!=null)out.add(item);
        }
    }
    private static int findNextLabel(String raw) {
        int best=-1;
        for(String alias:ALIASES.keySet()) {
            Matcher m=Pattern.compile("[，,]\\s*"+Pattern.quote(alias)+"\\s*[:：=]").matcher(raw);
            if(m.find()&&(best<0||m.start()<best))best=m.start();
        }
        return best;
    }

    private void table(Document doc,Page page,Line line,String[] headers,String[] cells,Evidence header,int tableStart,Context context,List<Map<String,Object>> out) {
        Context local=context.copy();if(local.references.size()>=12)local.clearObject();local.references.add(header);
        for(int i=0;i<headers.length;i++) {
            String label=plainHeader(headers[i]),value=cells[i];
            if(value.isBlank())continue;
            if(CONTEXT_LABELS.containsKey(label))local.dimensions.put(CONTEXT_LABELS.get(label),value);
            if(Set.of("名称","品名","项目名称","清单项","项目特征").contains(label))local.dimensions.put("object",value);
            if(Set.of("参数名称","指标名称").contains(label))local.parameter=value;
            if(Set.of("姓名","人员姓名").contains(label))local.person=value;
            if(label.equals("合同编号")||label.equals("合同号"))local.contract=value;
            if(label.equals("证书名称"))local.certificate=value;
            if(label.equals("证书编号"))local.certificateNumber=value;
            if(label.equals("持证人")||label.equals("证书持有人"))local.holder=value;
            if(label.matches("标段|标包|包件|包号"))local.lot=lot(value.matches("[0-9一二三四五六七八九十百]+")?label+value:value);
        }
        String rowId="row-"+hash(doc.id()+"|"+doc.version()+"|"+page.locationType()+"|"+page.number()+"|"+tableStart+"|"+line.number);
        for(int i=0;i<headers.length&&out.size()<LIMIT;i++) {
            String label=plainHeader(headers[i]),field=ALIASES.get(label),raw=cells[i];
            if(field==null||raw.isBlank())continue;
            Matcher hm=Pattern.compile("[（(]([^）)]+)[）)]").matcher(headers[i]);
            String suppliedUnit=hm.find()?hm.group(1):"";
            Map<String,Object> fact=create(doc,page,line.text,line.number,line.offset,field,label,raw,suppliedUnit,local,false);
            if(fact!=null) { fact.put("rowId",rowId);fact.put("sourceRowLine",line.number);fact.put("sourceColumn",i+1);out.add(fact); }
        }
    }

    private Map<String,Object> create(Document doc,Page page,String quote,int line,int offset,String field,String label,String raw,String suppliedUnit,Context context,boolean model) {
        if(raw.isBlank()||raw.length()>2000||quote.isBlank()||quote.length()>10000||!quote.contains(raw)||raw.matches("(?:[_＿—-]+|[XＸ]{2,}|待填.*|待补.*|不适用|详见.*)"))return null;
        Map<String,Object> definition=ConsistencyCatalog.definition(field);
        if(definition.isEmpty())return null;
        String type=text(definition,"valueType"),scope=context.scope,category=text(definition,"categoryId");
        if(HISTORICAL.matcher(quote).find())scope="HISTORICAL_PROJECT";
        if(quote.matches("(?s).*(?:示例|填写说明|参考模板|示范文本).*"))scope="TEMPLATE";
        Map<String,String> dims=new LinkedHashMap<>(context.dimensions);
        if(field.equals("productVersion"))dims.remove("version");
        if(field.equals("statisticalYear"))dims.remove("period");
        String subject=subject(definition,context);
        Normalized normalized=normalize(type,raw,suppliedUnit,dims);
        if(field.equals("feeCoverage")&&!Set.of("INCLUDED","CHARGED").contains(normalized.value)||field.equals("subcontractCommitment")&&!Set.of("NO_SUBCONTRACT","SUBCONTRACT").contains(normalized.value))normalized=new Normalized("","","EQ",true,"","");
        if(!normalized.dayType.isBlank())dims.put("dayType",normalized.dayType);
        if(type.equals("MONEY")) {
            if(dims.getOrDefault("currency","").isBlank()&&!normalized.unit.isBlank())dims.put("currency",normalized.unit);
            String tax=quote.contains("不含税")||quote.contains("税前")?"EXCLUSIVE":quote.contains("含税")||quote.contains("税后")?"INCLUSIVE":"";
            if(!tax.isBlank())dims.put("taxBasis",tax);
            Matcher priceBasis=Pattern.compile("/(台|套|个|次|人|份|件|项|米|公斤|吨)").matcher(raw+" "+suppliedUnit);
            if(priceBasis.find())dims.put("unitBasis",priceBasis.group(1));
        }
        if(type.equals("DURATION")&&!dims.containsKey("trigger")) {
            Matcher trigger=TRIGGER.matcher(raw);if(trigger.find())dims.put("trigger",trigger.group());
        }
        List<String> gaps=new ArrayList<>();
        if(subject.isBlank()||subject.contains("UNRESOLVED"))gaps.add("缺少可对齐的对象或身份");
        if(dims.values().stream().anyMatch(v->v.equals("UNRESOLVED")))gaps.add("同一引文包含多个上下文，无法确认事实归属");
        if(type.equals("PARAMETER")&&context.parameter.isBlank())gaps.add("缺少参数或指标名称");
        if(Set.of("DURATION","MONEY","NUMBER","PERCENT").contains(type)&&normalized.unit.isBlank())gaps.add("缺少可确认的单位或数值口径");
        if(normalized.value.isBlank())gaps.add("值或条件无法可靠归一化");
        if(type.equals("MONEY")&&quote.contains("不含税")&&quote.replace("不含税","").contains("含税"))gaps.add("同一引文包含多个税基，无法确认金额口径");
        if(Set.of("constructionPeriod","deliveryPeriod","plannedCompletionPeriod","servicePeriod","warrantyPeriod","bidValidity","backupRetention").contains(field)&&!dims.containsKey("trigger"))gaps.add("缺少明确起算事件");
        if(category.equals("payment")&&!dims.containsKey("stage"))gaps.add("缺少付款阶段");
        if(Set.of("feeCoverage","subcontractCommitment").contains(field)) {
            if(!dims.containsKey("range"))gaps.add("缺少承诺适用范围");
            if(!dims.containsKey("period"))gaps.add("缺少承诺适用期间");
        }
        if(category.equals("performance")&&(!scope.equals("HISTORICAL_PROJECT")||context.contract.isBlank()))gaps.add("缺少明确历史合同身份");
        if(scope.equals("UNRESOLVED"))gaps.add("无法确认项目范围");
        if(context.lot.equals("UNRESOLVED"))gaps.add("无法确认标段归属");
        boolean ocr=Objects.toString(page.parser(),"").toUpperCase(Locale.ROOT).contains("OCR");
        String revision=revision(quote)||context.revision?"UNRESOLVED":"CURRENT";
        boolean weak=model||ocr||incomplete(page)||normalized.weak||normalized.dayType.equals("UNSPECIFIED")||Set.of("TEXT","CLAIM","PARAMETER","DYNAMIC").contains(type)||!revision.equals("CURRENT")||category.equals("personnel")||category.equals("qualification");
        Evidence evidence=source(doc,page,quote,line,offset);
        List<Evidence> allReferences=new ArrayList<>(context.references);
        if(context.assignment!=null)allReferences.addAll(context.assignment.evidences());
        List<Evidence> references=allReferences.stream().filter(e->!e.equals(evidence)).distinct().limit(12).toList();
        String key=doc.id()+"|"+doc.version()+"|"+page.locationType()+"|"+page.number()+"|"+offset+"|"+field+"|"+raw+"|"+subject+"|"+scope+"|"+context.lot+"|"+dims;
        Map<String,Object> result=map("id","occ-"+hash(key),"field",field,"fieldLabel",label,"title",text(definition,"title"),"categoryId",category,
                "subject",subject.isBlank()?"UNRESOLVED":subject,"scope",scope,"lot",context.lot,"basis",dims.getOrDefault("taxBasis","")+"|"+dims.getOrDefault("period",""),
                "time",dims.getOrDefault("period",""),"rawValue",raw,"value",normalized.value,"unit",normalized.unit,"operator",normalized.operator,
                "sourceUnit",suppliedUnit,
                "dimensions",dims,"comparability",gaps.isEmpty()?(weak?"WEAK":"STRONG"):"INCOMPLETE","scopeReason",String.join("；",gaps),"missingContext",gaps,
                "revision",revision,"extractionMethod",model?"MODEL_SOURCE_VERIFIED":ocr?"OCR_TRANSCRIPT_RULE":"TEXT_RULE",
                "confidence",model?0.75:ocr?0.75:0.98,"sourceOffset",offset,"evidence",evidence,"contextEvidences",references,"valueType",type);
        if(!context.parameter.isBlank())result.put("parameterName",context.parameter);
        if(context.assignment!=null){result.put("contextAssignment",context.assignment.asMap());result.put("sectionId",context.assignment.sectionId());}
        if(!normalized.precision.isBlank())result.put("precision",normalized.precision);
        return result;
    }

    private static String subject(Map<String,Object> definition,Context context) {
        String mode=text(definition,"subjectMode"),object=context.dimensions.getOrDefault("object","");
        if(mode.equals("PROJECT"))return object.isBlank()?"CURRENT_PROJECT":object;
        if(mode.equals("PERSON"))return context.person;
        if(mode.equals("CONTRACT"))return context.contract.isBlank()?"":"CONTRACT:"+context.contract;
        if(mode.equals("CERTIFICATE")) {
            if(text(definition,"id").equals("certificateNumber"))return context.certificate.isBlank()||context.holder.isBlank()?"":context.holder+"|"+context.certificate;
            return context.certificateNumber.isBlank()?"":"CERTIFICATE:"+context.certificateNumber;
        }
        return object;
    }

    private Map<String,Object> supplemental(Document doc,Map<String,Object> input) {
        String field=text(input,"field");if(LEGACY.contains(field)||ConsistencyCatalog.definition(field).isEmpty())return null;
        Evidence supplied=evidence(input.get("evidence"));
        if(supplied==null||!doc.id().equals(supplied.documentId())||supplied.quote()==null||supplied.quote().isBlank()||supplied.quote().length()>10000)return null;
        Page page=(doc.pages()==null?List.<Page>of():doc.pages()).stream().filter(p->p.number()==supplied.page()&&Objects.equals(p.locationType(),supplied.locationType())).findFirst().orElse(null);
        if(page==null||page.text()==null)return null;
        String quote=supplied.quote(),raw=text(input,"rawValue"),contextQuote=text(input,"contextQuote");
        if(raw.isBlank()||!quote.contains(raw)||!page.text().contains(quote))return null;
        int offset=page.text().indexOf(quote);
        if(input.get("sourceOffset") instanceof Number n) {offset=n.intValue();if(offset<0||!page.text().startsWith(quote,offset))return null;}
        if(contextQuote.length()>6000)return null;
        if(!contextQuote.isBlank()&&!contextBound(page.text(),contextQuote,quote,offset))contextQuote="";
        String supported=contextQuote.isBlank()?quote:contextQuote;
        Context context=new Context();context.scope="UNRESOLVED";context.apply(supported,source(doc,page,supported,lineAt(page.text(),offset),offset),false);
        String requestedScope=text(input,"scope");
        if(HISTORICAL.matcher(supported).find()&&requestedScope.equals("HISTORICAL_PROJECT"))context.scope="HISTORICAL_PROJECT";
        else if(requestedScope.equals("CURRENT_PROJECT")&&!HISTORICAL.matcher(supported).find()&&supported.matches("(?s).*(?:本项目|本次投标|本次采购|本标段).*"))context.scope="CURRENT_PROJECT";
        Map<String,Object> requestedDims=asMap(input.get("dimensions"));
        for(String key:ConsistencyCatalog.DIMENSION_KEYS) {String value=text(requestedDims,key);if(!value.isBlank()&&value.length()<=200&&supported.contains(value)&&!"UNRESOLVED".equals(context.dimensions.get(key)))context.dimensions.put(key,value);}
        String requestedSubject=text(input,"subject");
        if(!requestedSubject.isBlank()&&requestedSubject.length()<=200&&supported.contains(requestedSubject)) {
            if(!context.dimensions.containsKey("object"))context.dimensions.put("object",requestedSubject);
            if(context.person.isBlank())context.person=requestedSubject;
        }
        String label=text(input,"fieldLabel");
        if(field.equals("custom")) {
            if(label.isBlank()||label.length()>80||!supported.contains(label)||!ConsistencyCatalog.categories().contains(text(input,"categoryId")))return null;
        }else if(!label.isBlank()&&!supported.contains(label))label="";
        if(label.isBlank())label=text(ConsistencyCatalog.definition(field),"title");
        if(Set.of("technicalParameter","acceptanceMetric").contains(field)&&!text(input,"fieldLabel").isBlank()&&supported.contains(text(input,"fieldLabel"))&&!((List<?>)ConsistencyCatalog.definition(field).get("aliases")).contains(text(input,"fieldLabel")))context.parameter=text(input,"fieldLabel");
        Map<String,Object> result=create(doc,page,quote,lineAt(page.text(),offset),offset,field,label,raw,"",context,true);
        if(result==null)return null;
        result.put("confidence",Math.min(0.8,finite(supplied.confidence())));
        if(!contextQuote.isBlank())result.put("contextQuote",contextQuote);
        if(field.equals("custom")) {
            result.put("field","dynamic."+hash(normal(label)));result.put("title",label);result.put("categoryId",text(input,"categoryId"));
            if(text(input,"categoryId").equals("performance")) {
                if(!text(result,"scope").equals("HISTORICAL_PROJECT")||context.contract.isBlank()||context.contract.equals("UNRESOLVED")) {
                    List<String> missing=new ArrayList<>();if(result.get("missingContext") instanceof List<?> previous)for(Object value:previous)missing.add(value.toString());
                    missing.add("缺少明确历史合同身份");result.put("missingContext",missing);result.put("scopeReason",String.join("；",missing));result.put("comparability","INCOMPLETE");
                } else result.put("subject","CONTRACT:"+context.contract+"|"+result.get("subject"));
            }
            result.put("id","occ-"+hash(text(result,"id")+"|"+result.get("field")+"|"+result.get("categoryId")));
        }
        return result;
    }

    private void valueGroups(List<Map<String,Object>> facts,List<Map<String,Object>> groups) {
        Map<String,List<Map<String,Object>>> buckets=new LinkedHashMap<>();
        for(Map<String,Object> fact:facts) {
            if(!eligible(fact)||text(fact,"valueType").equals("CLAIM"))continue;
            buckets.computeIfAbsent(comparisonKey(fact),ignored->new ArrayList<>()).add(fact);
        }
        for(var entry:buckets.entrySet()) {
            interrupted();List<Map<String,Object>> items=entry.getValue();if(items.size()<2||compatible(items))continue;
            boolean strong=items.stream().allMatch(f->text(f,"comparability").equals("STRONG"));
            String type=text(items.get(0),"valueType");
            String reason=Set.of("TEXT","PARAMETER","DYNAMIC").contains(type)?"同一对象及已识别条件下出现不同表述；文字差异可能存在等价解释，需结合原文复核。":"同一对象、字段及已识别条件下的值不兼容；已按原文单位和数值边界核对。";
            if(items.stream().anyMatch(f->!text(f,"revision").equals("CURRENT")))reason+=" 存在新旧版本或更正线索，请先确认有效版本。";
            groups.add(group("VALUE","FIELD_VALUE",entry.getKey(),items,strong?"CONFLICT":"SUSPECTED",reason));
        }
    }
    private static String comparisonKey(Map<String,Object> fact) {
        String labelBasis=Set.of("personEmployer","warrantyPeriod").contains(text(fact,"field"))?text(fact,"fieldLabel"):"";
        return String.join("|",text(fact,"categoryId"),text(fact,"field"),labelBasis,normal(text(fact,"parameterName")),normal(text(fact,"subject")),text(fact,"scope"),text(fact,"lot"),text(fact,"unit"),dimensionKey(asMap(fact.get("dimensions"))));
    }
    private static String dimensionKey(Map<String,Object> dims) {
        return ConsistencyCatalog.DIMENSION_KEYS.stream().sorted().map(k->k+"="+normal(text(dims,k))).reduce((a,b)->a+"|"+b).orElse("");
    }
    private static boolean eligible(Map<String,Object> fact) {
        String scope=text(fact,"scope");
        if(!scope.equals("CURRENT_PROJECT")&&!(scope.equals("HISTORICAL_PROJECT")&&text(fact,"categoryId").equals("performance")))return false;
        return !text(fact,"subject").equals("UNRESOLVED")&&!text(fact,"value").isBlank()&&!Set.of("INCOMPLETE","INVALID").contains(text(fact,"comparability"));
    }
    private static boolean compatible(List<Map<String,Object>> items) {
        String type=text(items.get(0),"valueType");
        if(Set.of("NUMBER","MONEY","PERCENT","DURATION").contains(type)) {
            BigDecimal lower=null,upper=null;boolean lowerOpen=false,upperOpen=false;
            for(Map<String,Object> fact:items) {
                BigDecimal value=decimal(fact);if(value==null||text(fact,"operator").equals("APPROX"))return true;
                String op=text(fact,"operator");
                if(Set.of("EQ","GE","GT").contains(op)) {if(lower==null||value.compareTo(lower)>0){lower=value;lowerOpen=op.equals("GT");}else if(value.compareTo(lower)==0)lowerOpen|=op.equals("GT");}
                if(Set.of("EQ","LE","LT").contains(op)) {if(upper==null||value.compareTo(upper)<0){upper=value;upperOpen=op.equals("LT");}else if(value.compareTo(upper)==0)upperOpen|=op.equals("LT");}
            }
            return lower==null||upper==null||lower.compareTo(upper)<0||lower.compareTo(upper)==0&&!lowerOpen&&!upperOpen;
        }
        if(type.equals("DATE")) {
            Map<String,Object> reference=items.stream().max(Comparator.comparingInt(f->text(f,"value").length())).orElseThrow();
            return items.stream().allMatch(f->{String a=text(reference,"value"),b=text(f,"value");return a.startsWith(b)||b.startsWith(a);});
        }
        return items.stream().map(f->text(f,"value")).distinct().count()<2;
    }

    private void arithmeticGroups(List<Map<String,Object>> facts,List<Map<String,Object>> groups) {
        Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();
        for(Map<String,Object> f:facts)if(!text(f,"rowId").isBlank()&&Set.of("unitPrice","quantity","lineAmount").contains(text(f,"field")))rows.computeIfAbsent(text(f,"rowId"),k->new ArrayList<>()).add(f);
        for(var entry:rows.entrySet()) {
            interrupted();List<Map<String,Object>> row=entry.getValue();
            Map<String,Object> price=one(row,"unitPrice"),quantity=one(row,"quantity"),amount=one(row,"lineAmount");
            if(price==null||quantity==null||amount==null)continue;
            if(!text(price,"scope").equals("CURRENT_PROJECT")||!text(quantity,"scope").equals("CURRENT_PROJECT")||!text(amount,"scope").equals("CURRENT_PROJECT"))continue;
            BigDecimal p=decimal(price),q=decimal(quantity),a=decimal(amount);
            if(p==null||q==null||a==null||List.of(price,quantity,amount).stream().anyMatch(f->!text(f,"operator").equals("EQ")))continue;
            if(List.of(price,quantity,amount).stream().anyMatch(f->text(f,"comparability").equals("INCOMPLETE")))continue;
            if(text(price,"unit").isBlank()||!text(price,"unit").equals(text(amount,"unit"))||text(quantity,"unit").isBlank())continue;
            Map<String,Object> pd=asMap(price.get("dimensions")),ad=asMap(amount.get("dimensions"));
            if(!text(pd,"taxBasis").equals(text(ad,"taxBasis"))||!text(pd,"period").equals(text(ad,"period")))continue;
            if(!text(pd,"unitBasis").isBlank()&&!text(pd,"unitBasis").equals(text(quantity,"unit")))continue;
            if(List.of(price,amount).stream().anyMatch(f->text(f,"scopeReason").contains("多个税基")))continue;
            BigDecimal expected=p.multiply(q),tolerance=roundingTolerance(text(amount,"rawValue"),text(amount,"sourceUnit"));
            if(expected.subtract(a).abs().compareTo(tolerance)<=0)continue;
            List<Map<String,Object>> operands=List.of(price,quantity,amount);
            boolean weak=operands.stream().anyMatch(f->!text(f,"comparability").equals("STRONG"));
            Map<String,Object> group=group("ARITHMETIC","LINE_PRICE_TIMES_QUANTITY",entry.getKey(),operands,weak?"SUSPECTED":"CONFLICT",
                    "同一清单行单价 × 数量 = "+plain(expected)+"，原文行金额为 "+plain(a)+"；差额超过原金额小数位允许的舍入容差 "+plain(tolerance)+"。");
            group.put("categoryId","price_structure");group.put("title","清单行金额与单价、数量不一致");
            Map<String,Object> calculation=map("formula","unitPrice * quantity = lineAmount","expected",plain(expected),"actual",plain(a),"tolerance",plain(tolerance),"unit",text(amount,"unit"));
            group.put("calculation",calculation);asMap(group.get("comparisonContext")).put("calculation",calculation);
            groups.add(group);
        }
    }

    private void temporalGroups(List<Map<String,Object>> facts,List<Map<String,Object>> groups) {
        Map<String,List<Map<String,Object>>> buckets=new LinkedHashMap<>();
        for(Map<String,Object> f:facts) {
            if(!Set.of("deliveryPeriod","plannedCompletionPeriod").contains(text(f,"field"))||!eligible(f))continue;
            Map<String,Object> dims=asMap(f.get("dimensions"));
            if(text(dims,"trigger").isBlank()||text(dims,"dayType").equals("UNSPECIFIED")||text(dims,"dayType").isBlank()&&text(f,"unit").equals("DAY"))continue;
            String key=normal(text(f,"subject"))+"|"+text(f,"scope")+"|"+text(f,"lot")+"|"+text(f,"unit")+"|"+dimensionKey(dims);
            buckets.computeIfAbsent(key,k->new ArrayList<>()).add(f);
        }
        for(var entry:buckets.entrySet()) {
            interrupted();List<Map<String,Object>> items=entry.getValue();
            Map<String,Object> deadline=items.stream().filter(f->text(f,"field").equals("deliveryPeriod")&&Set.of("EQ","LE","LT").contains(text(f,"operator"))&&decimal(f)!=null).min(Comparator.comparing(ExpandedConsistencyEngine::decimal)).orElse(null);
            if(deadline==null)continue;
            List<Map<String,Object>> late=items.stream().filter(f->text(f,"field").equals("plannedCompletionPeriod")&&Set.of("EQ","GE","GT").contains(text(f,"operator"))&&decimal(f)!=null&&(decimal(f).compareTo(decimal(deadline))>0||decimal(f).compareTo(decimal(deadline))==0&&(text(f,"operator").equals("GT")||text(deadline,"operator").equals("LT")))).toList();
            if(late.isEmpty())continue;
            List<Map<String,Object>> operands=new ArrayList<>();operands.add(deadline);operands.addAll(late);
            boolean strong=operands.stream().allMatch(f->text(f,"comparability").equals("STRONG"));
            Map<String,Object> group=group("TEMPORAL","PLAN_EXCEEDS_DELIVERY",entry.getKey(),operands,strong?"CONFLICT":"SUSPECTED","同一起算事件、对象及日历口径下，计划完成周期超出交付承诺；未对并行任务时长求和。");
            group.put("title","计划完成周期超出交付承诺");groups.add(group);
        }
    }

    private void semanticGroups(List<Map<String,Object>> facts,List<Map<String,Object>> groups) {
        Map<String,List<Map<String,Object>>> buckets=new LinkedHashMap<>();
        for(Map<String,Object> f:facts)if(eligible(f)&&text(f,"valueType").equals("CLAIM")&&!text(f,"value").equals("UNPARSED"))buckets.computeIfAbsent(comparisonKey(f),k->new ArrayList<>()).add(f);
        for(var entry:buckets.entrySet()) {
            List<Map<String,Object>> items=entry.getValue();if(items.stream().map(f->f.get("value")).distinct().count()<2)continue;
            groups.add(group("SEMANTIC","EXPLICIT_OPPOSITE_COMMITMENT",entry.getKey(),items,"SUSPECTED","同一对象、适用范围和期间出现相反承诺；请核对例外条款、分工及原文语义后确认。"));
        }
    }

    private void evidenceGroups(List<Map<String,Object>> facts,List<Map<String,Object>> groups) {
        Map<String,List<Map<String,Object>>> buckets=new LinkedHashMap<>();
        for(Map<String,Object> f:facts) {
            if(!text(f,"comparability").equals("INCOMPLETE")||Set.of("TEMPLATE","THIRD_PARTY").contains(text(f,"scope")))continue;
            if(text(f,"scope").equals("HISTORICAL_PROJECT")&&!text(f,"categoryId").equals("performance"))continue;
            String key=text(f,"categoryId")+"|"+text(f,"field")+"|"+text(f,"scopeReason");
            buckets.computeIfAbsent(key,k->new ArrayList<>()).add(f);
        }
        for(var entry:buckets.entrySet()) {
            List<Map<String,Object>> items=entry.getValue();
            Map<String,Object> group=group("EVIDENCE","MISSING_COMPARISON_CONTEXT",entry.getKey(),items,"EVIDENCE_INSUFFICIENT",
                    text(items.get(0),"scopeReason")+"；已有事实不能可靠对齐。这表示比较依据不足，不表示附件或资质不存在。");
            Map<String,Object> commonDimensions=new LinkedHashMap<>();
            for(String key:ConsistencyCatalog.DIMENSION_KEYS) {
                String value=text(asMap(items.get(0).get("dimensions")),key);
                if(!value.isBlank()&&items.stream().allMatch(f->text(asMap(f.get("dimensions")),key).equals(value)))commonDimensions.put(key,value);
            }
            Map<String,Object> common=map("subject",commonValue(items,"subject"),"scope",commonValue(items,"scope"),"lot",commonValue(items,"lot"),
                    "dimensions",commonDimensions,"missing",text(items.get(0),"scopeReason"),"aggregation","按字段和缺口原因汇总，仅显示共同上下文");
            group.put("comparisonContext",common);
            for(String key:List.of("subject","scope","lot","basis"))group.put(key,commonValue(items,key));
            group.put("title",text(items.get(0),"title")+"缺少比较依据");groups.add(group);
        }
    }

    private static Map<String,Object> group(String relation,String rule,String key,List<Map<String,Object>> items,String status,String reason) {
        Map<String,Object> first=items.get(0);List<Evidence> evidence=new ArrayList<>();
        for(Map<String,Object> f:items) {evidence.add((Evidence)f.get("evidence"));if(f.get("contextEvidences") instanceof List<?> refs)for(Object e:refs)if(e instanceof Evidence ev)evidence.add(ev);}
        String suffix=status.equals("CONFLICT")?"存在不一致":status.equals("SUSPECTED")?"存在疑似差异":"缺少比较依据";
        return map("id","conflict-"+hash(VERSION+"|"+relation+"|"+rule+"|"+key),"field",first.get("field"),"fieldLabel",first.get("fieldLabel"),"title",text(first,"title")+suffix,
                "categoryId",first.get("categoryId"),"relationType",relation,"ruleId",rule,"subject",first.get("subject"),"scope",first.get("scope"),"lot",first.get("lot"),"basis",first.get("basis"),
                "comparisonContext",map("subject",first.get("subject"),"scope",first.get("scope"),"lot",first.get("lot"),"dimensions",first.get("dimensions"),"parameterName",text(first,"parameterName")),
                "status",status,"reason",reason,"occurrenceIds",items.stream().map(f->f.get("id")).toList(),"evidences",evidence.stream().distinct().toList(),"values",items.stream().map(f->f.get("value")).distinct().toList());
    }

    private static List<Map<String,Object>> categoryCoverage(List<Map<String,Object>> facts,List<Map<String,Object>> groups) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(String id:ConsistencyCatalog.categories()) {
            long count=facts.stream().filter(f->text(f,"categoryId").equals(id)).count();
            long needs=facts.stream().filter(f->text(f,"categoryId").equals(id)&&text(f,"comparability").equals("INCOMPLETE")).count();
            result.add(map("id",id,"title",ConsistencyCatalog.categoryTitle(id),"capability","RULES_AND_MODEL","status",count==0?"NO_FACTS":needs>0?"NEEDS_CONTEXT":"OBSERVED",
                    "occurrences",count,"groups",groups.stream().filter(g->text(g,"categoryId").equals(id)).count(),
                    "limitations",List.of("仅识别明确标签、简单表格与来源已核对的模型候选；未知对象或必要条件不会被假定相同。")));
        }
        return result;
    }

    private static Normalized normalize(String type,String raw,String suppliedUnit,Map<String,String> dims) {
        String trimmed=raw.trim();
        if(type.equals("DURATION")) {
            trimmed=TRIGGER.matcher(trimmed).replaceAll("").replaceFirst("^自","").trim();
            trimmed=trimmed.replaceFirst("(?:以内|内)$","").trim();
        }
        if(type.equals("MONEY"))trimmed=trimmed.replaceFirst("^(?:人民币|￥|¥)\\s*","");
        if(Set.of("NUMBER","MONEY","PERCENT","DURATION").contains(type)) {
            Matcher matcher=NUMBER.matcher(trimmed);
            if(!matcher.matches())return new Normalized("","","EQ",true,"","");
            String modifier=Objects.toString(matcher.group(1),""),tail=matcher.group(3),unit=tail.isBlank()?suppliedUnit:tail;
            String operator=switch(modifier){case "不超过","不大于","至多","最多","<=","≤"->"LE";case "至少","不低于","不少于",">=","≥"->"GE";case ">","大于"->"GT";case "<","小于"->"LT";case "约","大约"->"APPROX";default->"EQ";};
            if(raw.endsWith("内")&&operator.equals("EQ"))operator="LE";
            BigDecimal value=new BigDecimal(matcher.group(2).replace(",",""));String dayType="";
            if(type.equals("MONEY")) {
                if(unit.matches("亿元(?:/(?:台|套|个|次|人|份|件|项|米|公斤|吨))?")){value=value.multiply(new BigDecimal("100000000"));unit="CNY";}
                else if(unit.matches("万元(?:/(?:台|套|个|次|人|份|件|项|米|公斤|吨))?")){value=value.multiply(new BigDecimal("10000"));unit="CNY";}
                else if(unit.matches("(?:元|人民币|CNY)(?:/(?:台|套|个|次|人|份|件|项|米|公斤|吨))?"))unit="CNY";
                else if(unit.equals("美元")||unit.equals("USD"))unit="USD";
                else if(unit.equals("欧元")||unit.equals("EUR"))unit="EUR";
                else unit="";
            } else if(type.equals("DURATION")) {
                switch(unit) {
                    case "年"->{value=value.multiply(new BigDecimal("12"));unit="MONTH";}
                    case "月","个月"->unit="MONTH";
                    case "小时","时"->{value=value.multiply(new BigDecimal("60"));unit="MINUTE";}
                    case "分钟","分"->unit="MINUTE";
                    case "日历天","日历日"->{unit="DAY";dayType="CALENDAR";}
                    case "工作日","工作天"->{unit="DAY";dayType="WORKING";}
                    case "天","日"->{unit="DAY";String basis=dims.getOrDefault("dayType","");dayType=basis.matches("日历天|日历日|CALENDAR")?"CALENDAR":basis.matches("工作日|工作天|WORKING")?"WORKING":"UNSPECIFIED";}
                    default->unit="";
                }
            } else if(type.equals("PERCENT"))unit=unit.equals("%")||unit.equals("％")?"PERCENT":"";
            else {
                if(unit.isBlank())unit=dims.getOrDefault("unitBasis","");
                if(!unit.matches("台|套|个|次|人|名|份|件|项|座|处|站|米|平方米|m|m2|m²|公斤|千克|吨|kg|t|用户|账号|许可"))unit="";
            }
            return new Normalized(plain(value),unit,operator,operator.equals("APPROX"),dayType,"");
        }
        if(type.equals("DATE")) {
            Matcher matcher=DATE.matcher(trimmed);
            if(!matcher.matches())return new Normalized("","DATE","EQ",true,"","");
            try {
                LocalDate date=LocalDate.of(Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2)),Integer.parseInt(matcher.group(3)));
                if(matcher.group(5)==null)return new Normalized(date.toString(),"DATE","EQ",false,"","DAY");
                int hour=Integer.parseInt(matcher.group(5));if("下午".equals(matcher.group(4))&&hour<12)hour+=12;if("上午".equals(matcher.group(4))&&hour==12)hour=0;
                LocalTime time=LocalTime.of(hour,matcher.group(6)==null?0:Integer.parseInt(matcher.group(6)),matcher.group(7)==null?0:Integer.parseInt(matcher.group(7)));
                String value=date+String.format(Locale.ROOT,"T%02d:%02d",time.getHour(),time.getMinute());if(matcher.group(7)!=null)value+=String.format(Locale.ROOT,":%02d",time.getSecond());
                return new Normalized(value,"DATE","EQ",false,"",matcher.group(7)==null?"MINUTE":"SECOND");
            }catch(DateTimeException|NumberFormatException ignored){return new Normalized("","DATE","EQ",true,"","");}
        }
        if(type.equals("CLAIM")) {
            String clean=normal(raw);
            if(Set.of("全部包含","已包含","包含全部费用","全部免费","免费","不另收费","不另行收费").contains(clean))return new Normalized("INCLUDED","","EQ",true,"","");
            if(Set.of("另行收费","另收费用","不包含","另外收费","收费").contains(clean))return new Normalized("CHARGED","","EQ",true,"","");
            if(Set.of("不分包","不得分包","全部自行实施","无分包","否").contains(clean))return new Normalized("NO_SUBCONTRACT","","EQ",true,"","");
            if(Set.of("分包实施","安排分包","有分包","是").contains(clean))return new Normalized("SUBCONTRACT","","EQ",true,"","");
            return new Normalized("","","EQ",true,"","");
        }
        return new Normalized(normal(raw),"","EQ",type.equals("DYNAMIC")||type.equals("TEXT")||type.equals("PARAMETER"),"","");
    }

    private static BigDecimal roundingTolerance(String raw,String sourceUnit) {
        Matcher matcher=Pattern.compile("[0-9]+(?:,[0-9]{3})*(?:\\.([0-9]+))?").matcher(raw);
        int scale=matcher.find()&&matcher.group(1)!=null?matcher.group(1).length():0;
        String units=raw+" "+sourceUnit;
        BigDecimal multiplier=units.contains("亿元")?new BigDecimal("100000000"):units.contains("万元")?new BigDecimal("10000"):BigDecimal.ONE;
        return BigDecimal.ONE.movePointLeft(scale).divide(new BigDecimal("2")).multiply(multiplier);
    }
    private static Map<String,Object> one(List<Map<String,Object>> values,String field) {List<Map<String,Object>> found=values.stream().filter(f->text(f,"field").equals(field)).toList();return found.size()==1?found.get(0):null;}
    private static String commonValue(List<Map<String,Object>> values,String key){String first=text(values.get(0),key);return values.stream().allMatch(value->text(value,key).equals(first))?first:"";}
    private static BigDecimal decimal(Map<String,Object> fact) {try{return new BigDecimal(text(fact,"value"));}catch(NumberFormatException ignored){return null;}}
    private static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static String plainHeader(String value){return value.replaceAll("[（(][^）)]*[）)]","").trim();}
    private static String[] cells(String text){return Arrays.stream(text.replaceAll("^[|｜]|[|｜]$","").split("[|｜\\t]",-1)).map(String::trim).toArray(String[]::new);}
    private static boolean isHeader(String[] cells){return Arrays.stream(cells).map(ExpandedConsistencyEngine::plainHeader).filter(c->ALIASES.containsKey(c)||CONTEXT_LABELS.containsKey(c)||Set.of("名称","品名","参数名称","指标名称","姓名","人员姓名","证书名称","合同编号","标段","包号").contains(c)).count()>=2;}
    private static boolean contextBound(String text,String context,String quote,int offset) {int at=context.indexOf(quote);while(at>=0){if(offset-at>=0&&text.startsWith(context,offset-at))return true;at=context.indexOf(quote,at+1);}return false;}
    private static List<Map<String,Object>> deduplicate(List<Map<String,Object>> facts){Map<String,Map<String,Object>> unique=new LinkedHashMap<>();for(Map<String,Object> fact:facts)unique.putIfAbsent(text(fact,"id"),fact);return new ArrayList<>(unique.values());}
    private static boolean incomplete(Page page){return Objects.toString(page.warning(),"").matches("(?s).*(?:OCR_DEFERRED|OCR未完成|未识别|未解析|解析失败|图片未|图像未|无文本).*")||page.layout()!=null&&"PARTIAL".equals(page.layout().status());}
    private static boolean revision(String text){return text.matches("(?s).*(?:原定|原版本|旧版本|调整为|更正为|变更为|修订版|补遗|更正公告).*");}
    private static Evidence source(Document doc,Page page,String quote,int line,int offset){return new Evidence(doc.id(),doc.name(),page.number(),page.locationType(),quote,"p"+page.number()+"-l"+line+"-o"+offset,Objects.toString(page.parser(),"").toUpperCase(Locale.ROOT).contains("OCR")?0.75:0.98);}
    private static int lineAt(String text,int offset){return 1+(int)text.substring(0,Math.max(0,Math.min(offset,text.length()))).chars().filter(c->c=='\n').count();}
    private static List<Line> lines(String text){List<Line> out=new ArrayList<>();Matcher matcher=Pattern.compile("\\R").matcher(text);int start=0,line=1;while(matcher.find()){out.add(new Line(line++,start,text.substring(start,matcher.start())));start=matcher.end();}out.add(new Line(line,start,text.substring(start)));return out;}
    private static String localClause(String value,int start,int end){int from=start,to=end;while(from>0&&";；。\n\r".indexOf(value.charAt(from-1))<0)from--;while(to<value.length()&&";；。\n\r".indexOf(value.charAt(to))<0)to++;return value.substring(from,to);}
    private static String lot(String value){Set<String> values=new LinkedHashSet<>();Matcher m=LOT.matcher(value);while(m.find())values.add("LOT-"+chineseNumber(m.group(1)==null?m.group(2):m.group(1)));return values.isEmpty()?"":values.size()==1?values.iterator().next():"UNRESOLVED";}
    private static String chineseNumber(String value){if(value.matches("[0-9]+"))try{return Integer.toString(Integer.parseInt(value));}catch(NumberFormatException ignored){return value;}int sum=0,digit=0;String digits="零一二三四五六七八九";for(char c:value.toCharArray()){int at=digits.indexOf(c);if(at>=0)digit=at;else if(c=='十'){sum+=(digit==0?1:digit)*10;digit=0;}else if(c=='百'){sum+=(digit==0?1:digit)*100;digit=0;}}return Integer.toString(sum+digit);}
    private static String normal(String value){return value.replace('（','(').replace('）',')').replaceAll("[\\s\\u3000]","").toUpperCase(Locale.ROOT);}
    private static String text(Map<String,?> map,String key){return Objects.toString(map.get(key),"");}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    @SuppressWarnings("unchecked") private static Map<String,Object> asMap(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,24);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new IllegalStateException("CONSISTENCY_CANCELLED");}
    private static double finite(double value){return Double.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
    private static Evidence evidence(Object value){if(value instanceof Evidence e)return e;if(!(value instanceof Map<?,?> input))return null;try{return new Evidence(Objects.toString(input.get("documentId"),""),Objects.toString(input.get("documentName"),""),Integer.parseInt(Objects.toString(input.get("page"),"0")),Objects.toString(input.get("locationType"),""),Objects.toString(input.get("quote"),""),Objects.toString(input.get("anchor"),""),Double.parseDouble(Objects.toString(input.get("confidence"),"0.7")));}catch(RuntimeException ignored){return null;}}
    private record Line(int number,int offset,String text) { }
    private record Normalized(String value,String unit,String operator,boolean weak,String dayType,String precision) { }

    private static final class Context {
        String scope="CURRENT_PROJECT",lot="",person="",certificate="",certificateNumber="",holder="",contract="",parameter="";
        boolean revision;
        final Map<String,String> dimensions=new LinkedHashMap<>();
        final List<Evidence> references=new ArrayList<>();
        DocumentContextIndex.Assignment assignment;
        Context copy(){Context c=new Context();c.scope=scope;c.lot=lot;c.person=person;c.certificate=certificate;c.certificateNumber=certificateNumber;c.holder=holder;c.contract=contract;c.parameter=parameter;c.revision=revision;c.dimensions.putAll(dimensions);c.references.addAll(references);c.assignment=assignment;return c;}
        void bind(DocumentContextIndex.Assignment value){assignment=value;scope=value.scope();if(!value.lot().isBlank()||value.boundary())lot=value.lot();}
        void advance(String text,Evidence evidence){
            String clean=text.trim().replaceFirst("[:：]$","").stripTrailing();boolean heading=clean.length()<100&&!clean.matches(".*[:：=|｜\\t].*")&&!clean.isBlank();
            if(heading){
                boolean historical=HISTORICAL.matcher(clean).find()||clean.matches(".*(?:合同复印件|合同协议书).*");
                if(clean.matches(".*(?:示例|填写说明|参考模板|示范文本|范例).*")){scope="TEMPLATE";clearObject();}
                else if(historical){scope="HISTORICAL_PROJECT";clearObject();}
                else if(clean.matches(".*(?:投标函|本项目|本次投标|报价表|开标一览表|技术方案|售后方案|商务响应|技术响应|人员配置|产品配置|承诺书|联合体协议).*")){scope="CURRENT_PROJECT";clearObject();if(ExpandedConsistencyEngine.lot(clean).isBlank()&&!lot.isBlank())lot="UNRESOLVED";}
                else if(clean.contains("合同")){scope="UNRESOLVED";clearObject();}
                else {clearObject();}
                if(!ExpandedConsistencyEngine.lot(clean).isBlank()){lot=ExpandedConsistencyEngine.lot(clean);clearObject();}
                if(ExpandedConsistencyEngine.revision(clean))revision=true;
            }
            apply(text,evidence,true);
        }
        void clearObject(){dimensions.clear();references.clear();person="";certificate="";certificateNumber="";holder="";contract="";parameter="";revision=false;}
        void apply(String text,Evidence evidence,boolean persistent){
            boolean changed=false;
            Map<String,Set<String>> contextValues=new LinkedHashMap<>();
            for(var entry:CONTEXT_LABELS.entrySet())contextValues.computeIfAbsent(entry.getValue(),k->new LinkedHashSet<>()).addAll(labelValues(text,entry.getKey()));
            Set<String> objects=new LinkedHashSet<>();
            for(var entry:CONTEXT_LABELS.entrySet())if(entry.getValue().equals("object"))objects.addAll(labelValues(text,entry.getKey()));
            for(var entry:CONTEXT_LABELS.entrySet()) {
                String value=labelValue(text,entry.getKey());if(value.isBlank())continue;
                if(entry.getValue().equals("object")&&objects.size()>1)value="UNRESOLVED";
                if(entry.getValue().equals("object")&&!value.equals(dimensions.get("object"))){dimensions.clear();references.clear();parameter="";}
                dimensions.put(entry.getValue(),value);changed=true;
            }
            String value=labelValue(text,"姓名");if(!value.isBlank()){person=value;changed=true;}
            value=labelValue(text,"人员姓名");if(!value.isBlank()){person=value;changed=true;}
            value=labelValue(text,"证书名称");if(!value.isBlank()){certificate=value;changed=true;}
            value=labelValue(text,"证书编号");if(!value.isBlank()){certificateNumber=value;changed=true;}
            value=labelValue(text,"证书持有人");if(value.isBlank())value=labelValue(text,"持证人");if(!value.isBlank()){holder=value;changed=true;}
            value=labelValue(text,"合同编号");if(value.isBlank())value=labelValue(text,"合同号");if(!value.isBlank()){if(!contract.equals(value)){dimensions.clear();references.clear();}contract=value;changed=true;}
            value=labelValue(text,"参数名称");if(value.isBlank())value=labelValue(text,"指标名称");if(!value.isBlank()){parameter=value;changed=true;}
            String foundLot=ExpandedConsistencyEngine.lot(text);if(!foundLot.isBlank()){lot=foundLot;changed=true;}
            Matcher trigger=TRIGGER.matcher(text);if(trigger.find()&&!dimensions.containsKey("trigger")){dimensions.put("trigger",trigger.group());changed=true;}
            for(var entry:contextValues.entrySet())if(entry.getValue().size()>1)dimensions.put(entry.getKey(),"UNRESOLVED");
            if(labelValues(text,"合同编号").size()>1||labelValues(text,"合同号").size()>1)contract="UNRESOLVED";
            if(labelValues(text,"姓名").size()>1||labelValues(text,"人员姓名").size()>1)person="UNRESOLVED";
            if(labelValues(text,"证书编号").size()>1)certificateNumber="UNRESOLVED";
            if(changed&&evidence!=null&&!references.contains(evidence)){if(references.size()>=12)clearObject();references.add(evidence);}
        }
        private static String labelValue(String text,String label){Matcher m=Pattern.compile("(?<![\\p{L}0-9])(?:本项目|本次投标|本次采购|本标段)?\\s*"+Pattern.quote(label)+"\\s*[:：=]\\s*([^;；。\\r\\n|｜\\t,，]{1,200})").matcher(text);return m.find()?m.group(1).trim():"";}
        private static Set<String> labelValues(String text,String label){Set<String> values=new LinkedHashSet<>();Matcher m=Pattern.compile("(?<![\\p{L}0-9])(?:本项目|本次投标|本次采购|本标段)?\\s*"+Pattern.quote(label)+"\\s*[:：=]\\s*([^;；。\\r\\n|｜\\t,，]{1,200})").matcher(text);while(m.find())values.add(m.group(1).trim());return values;}
    }
}
