package cn.bidcheck.consistency;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.TableRelationIndex;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Source-bound whole-document occurrences. A completed scan never asserts that all facts were recognized. */
public final class ConsistencyEngine {
    public static final String VERSION = "consistency-10-source-bound-name-variants";
    private static final Set<String> FIELDS = cn.bidcheck.compat.Java8Api.setOf("companyName", "totalPrice", "openingTime", "personName", "personRole");
    private static final String ROLES = "项目负责人|项目经理|技术负责人|授权代表|被授权人|法定代表人|安全负责人|质量负责人|财务负责人";
    private static final String SEPARATOR = "[\\s:：=|｜]+";
    private static final Pattern COMPANY = Pattern.compile("((?:投标人|投标单位|响应人|响应单位|供应商)(?:名称)?|企业名称|公司名称)" + SEPARATOR + "([^|｜\\t;；。]+)");
    private static final Pattern ENTITY_SUFFIX = Pattern.compile("(?:有限责任公司|股份有限公司|有限公司|集团有限公司|集团公司|公司|合作社|事务所)");
    private static final Pattern PRICE = Pattern.compile("((?:投标总报价|投标报价|报价总额|报价合计|总报价|投标总价|报价总价|总价)(?:[（(](?:含税|不含税|税前|税后|元|万元|亿元)[）)])?)\\s*(?:[:：=|｜]|为|人民币|含税|不含税|税前|税后|\\s)+\\s*((?:人民币|[￥¥])?\\s*[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?\\s*(?:亿元|万元|元|万)?)");
    private static final Pattern OPENING = Pattern.compile("开标(?:日期|时间)" + SEPARATOR + "([^|｜\\t;；。]+)");
    private static final Pattern PERSON = Pattern.compile("(" + ROLES + ")(?:姓名)?" + SEPARATOR + "([\\p{IsHan}·]{2,12})");
    private static final Pattern NAME_ROLE = Pattern.compile("姓名" + SEPARATOR + "([\\p{IsHan}·]{2,12})[\\s,，;；|｜]+(?:岗位|职务|任职)" + SEPARATOR + "([\\p{IsHan}·]{2,16})");
    private static final Pattern DATE = Pattern.compile("(?<![0-9])(20[0-9]{2}|19[0-9]{2})\\s*[年/.-]\\s*([0-9]{1,2})\\s*[月/.-]\\s*([0-9]{1,2})(?:日)?(?:[ T\\s]*(?:上午|下午)?\\s*([0-9]{1,2})\\s*(?:[:：](?=\\s*[0-9])|时)\\s*([0-9]{1,2})?(?:分)?(?:\\s*[:：]\\s*([0-9]{1,2}))?)?");
    private static final Pattern NUMBER = Pattern.compile("(?:人民币|[￥¥])?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(亿元|万元|元|万)?");
    private static final Pattern LOT = Pattern.compile("(?:第?([一二三四五六七八九十百0-9]+)\\s*(?:标段|标包|包件)|(?:标段|标包|包件|包号)\\s*[:：]?\\s*([一二三四五六七八九十百0-9]+))");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)(?:[_＿—-]+|[XＸ]{2,}|待填.*|待补.*|详见.*|见附件.*|填写.*|姓名|单位名称|公司名称)");
    private static final int MAX_OCCURRENCES = 20000;

    public Map<String,Object> scan(Document document, List<Map<String,Object>> supplementalFacts) {
        Objects.requireNonNull(document, "document");
        List<Map<String,Object>> occurrences = new ArrayList<>();
        List<Page> pages = document.pages() == null ? cn.bidcheck.compat.Java8Api.listOf() : document.pages();
        DocumentContextIndex contextIndex=DocumentContextIndex.build(document);
        TableRelationIndex tableIndex=TableRelationIndex.build(document);
        Context context = new Context();
        int scanned = 0, unresolved = 0, skipped = 0, modelRejected = 0, modelAccepted = 0;
        for (Page page : pages) {
            interrupted();
            if (occurrences.size() >= MAX_OCCURRENCES) { skipped++; continue; }
            if (page.text() == null || cn.bidcheck.compat.Java8Api.isBlank(page.text())) { unresolved++; continue; }
            scanned++;
            if (incomplete(page)) unresolved++;
            String[] lines = page.text().split("\\R", -1);
            String[] tableHeader = null;
            int tableHeaderAt = -1;
            boolean insideHtmlTable=false;
            for (int at = 0; at < lines.length && occurrences.size() < MAX_OCCURRENCES; at++) {
                interrupted();
                String line = lines[at];
                boolean htmlLine=insideHtmlTable||TableFactSupport.html(line);
                if(line.matches("(?is).*<table\\b.*"))insideHtmlTable=true;
                if(line.matches("(?is).*</table\\s*>.*"))insideHtmlTable=false;
                if(htmlLine){tableHeader=null;continue;}
                context.advance(line);
                context.bind(contextIndex.at(page,at+1));
                Located source = new Located(document, page, at + 1, line, context.copy());
                extract(source, occurrences);
                String[] cells = cells(line);
                if (cells.length > 1 && header(cells)) { tableHeader = cells; tableHeaderAt = at; }
                else if (cells.length > 1 && tableHeader != null && cells.length == tableHeader.length) {
                    String tableQuote = page.text().substring(nthLineStart(page.text(), tableHeaderAt), nthLineEnd(page.text(), at));
                    Located tableSource = tableQuote.length() <= 10000 ? new Located(document, page, tableHeaderAt + 1, tableQuote, context.copy()) : source;
                    Evidence headerEvidence = new Evidence(document.id(), document.name(), page.number(), page.locationType(), lines[tableHeaderAt], "p" + page.number() + "-l" + (tableHeaderAt + 1), 0.98);
                    table(tableSource, tableHeader, cells, occurrences, headerEvidence, at + 1);
                } else if (cells.length < 2 && !cn.bidcheck.compat.Java8Api.isBlank(line)) tableHeader = null;
                // Vertical key/value cells retain one contiguous quote, including the actual newline.
                if (at + 1 < lines.length && labelOnly(line) && !cn.bidcheck.compat.Java8Api.isBlank(lines[at + 1])) {
                    int start = nthLineStart(page.text(), at), end = nthLineEnd(page.text(), at + 1);
                    String quote = page.text().substring(start, end);
                    extract(new Located(document, page, at + 1, quote, context.copy()), occurrences);
                }
            }
            if (occurrences.size() >= MAX_OCCURRENCES && !incomplete(page)) unresolved++;
        }
        for(TableRelationIndex.Row row:tableIndex.rows()) {
            if(!row.status().equals("RESOLVED")||occurrences.size()>=MAX_OCCURRENCES)continue;
            Page page=pages.stream().filter(p->p.number()==row.page()&&Objects.equals(p.locationType(),row.locationType())).findFirst().orElse(null);
            if(page==null||row.end()-row.start()>10000)continue;
            Context tableContext=new Context();tableContext.bind(contextIndex.at(page,row.line()));
            Located source=new Located(document,page,row.line(),page.text().substring(row.start(),row.end()),tableContext);
            String[] headers=row.associations().stream().map(a->TableFactSupport.semanticHeader(row,a)).toArray(String[]::new);
            String[] values=row.associations().stream().map(a->cn.bidcheck.compat.Java8Api.strip(a.rawValue())).toArray(String[]::new);
            int first=occurrences.size();
            table(source,headers,values,occurrences,TableFactSupport.header(document,page,row),row.line());
            TableFactSupport.bind(document,page,row,occurrences,first);
        }
        for (Map<String,Object> input : supplementalFacts == null ? cn.bidcheck.compat.Java8Api.<Map<String,Object>>listOf() : supplementalFacts) {
            interrupted();
            if (occurrences.size() >= MAX_OCCURRENCES) break;
            if (!FIELDS.contains(text(input, "field"))) continue;
            Map<String,Object> occurrence = supplemental(document, input);
            if (occurrence == null) modelRejected++;
            else { occurrences.add(occurrence); modelAccepted++; }
        }
        boolean truncated = occurrences.size() >= MAX_OCCURRENCES;
        occurrences = deduplicate(occurrences);
        applyDeclaredAliases(occurrences);
        ConsortiumContextPolicy.Declaration consortium=ConsortiumContextPolicy.bind(document,contextIndex,occurrences);
        for (Map<String,Object> occurrence : occurrences) {
            String dimensions = String.join("|", text(occurrence, "scope"), text(occurrence, "lot"), text(occurrence, "basis"), text(occurrence, "time"), text(occurrence, "unit"), text(occurrence, "value"));
            occurrence.put("id", "occ-" + hash(text(occurrence, "id") + "|" + dimensions));
        }
        List<Map<String,Object>> groups = compare(occurrences);
        groups.addAll(ConsortiumContextPolicy.nonMemberGroups(consortium,occurrences));
        int comparable = (int) occurrences.stream().filter(this::eligible).count();
        Map<String,Object> coverage = map("pagesTotal", pages.size(), "pagesScanned", scanned, "pagesUnparsed", unresolved + skipped, "pagesSkipped", skipped,
                "occurrencesCount", occurrences.size(), "comparableOccurrencesCount", comparable, "comparisonGroupsCount", groups.size(),
                "modelFactsAccepted", modelAccepted, "modelFactsRejected", modelRejected,
                "occurrencesTruncated", truncated,
                "status", pages.isEmpty() || unresolved > 0 || truncated || documentIncomplete(document) ? "PARTIAL" : "TEXT_SCANNED",
                "fields", cn.bidcheck.compat.Java8Api.listOf("companyName", "totalPrice", "openingTime", "personName", "personRole"));
        Map<String,Object> report = map("version", VERSION, "documentId", document.id(), "documentVersion", document.version(),
                "overall", "REVIEW_REQUIRED", "occurrences", occurrences, "groups", groups, "coverage", coverage,
                "limitations", cn.bidcheck.compat.Java8Api.listOf("已扫描可提取文字及已有 OCR 转录；扫描完成不等于全部事实均已识别。", "仅比较可对齐的主体、标段和口径；历史业绩、第三方、未知范围及失效版本不会自动判定为本次投标冲突。", "简称、更名、多人岗位、文件更正及图像识别差异需结合原文复核；没有差异组不代表全文通过。"));
        report=mergePersonCredentials(mergeExpanded(report, new ExpandedConsistencyEngine().scan(document, supplementalFacts,contextIndex,tableIndex)),
                new PersonCredentialConsistency().scan(document));
        scopeLegalRepresentativeGroups(document,report);
        NameVariantAttribution.apply(document,report);
        ConsistencyRunSupport.map(report.get("coverage")).put("tableRelations",tableIndex.summary());
        attachContextSummary(document,contextIndex,report);
        return report;
    }

    private static void attachContextSummary(Document document,DocumentContextIndex index,Map<String,Object> report) {
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();Map<String,Integer> counts=new LinkedHashMap<>();
        for(String status:cn.bidcheck.compat.Java8Api.listOf("EXPLICIT", "INHERITED", "DOCUMENT_DEFAULT", "UNRESOLVED"))counts.put(status,0);
        for(Map<String,Object> occurrence:ConsistencyRunSupport.maps(report.get("occurrences"))) {
            if(!(occurrence.get("contextAssignment") instanceof Map<?,?>)) {
                Evidence evidence=evidence(occurrence.get("evidence"));
                Page page=evidence==null?null:document.pages().stream().filter(p->p.number()==evidence.page()&&Objects.equals(p.locationType(),evidence.locationType())).findFirst().orElse(null);
                if(page!=null&&page.text()!=null) {
                    int offset=occurrence.get("sourceOffset") instanceof Number?((Number) (occurrence.get("sourceOffset"))).intValue():page.text().indexOf(evidence.quote());
                    // A repeated quote without an exact offset cannot borrow the first occurrence's section.
                    if(!(occurrence.get("sourceOffset") instanceof Number)&&offset>=0&&page.text().indexOf(evidence.quote(),offset+1)>=0)offset=-1;
                    DocumentContextIndex.Assignment assignment=index.at(page,offset>=0?physicalLine(page.text(),offset):0);
                    occurrence.put("contextAssignment",assignment.asMap());occurrence.put("sectionId",assignment.sectionId());
                }
            }
            Map<String,Object> assignment=ConsistencyRunSupport.map(occurrence.get("contextAssignment"));
            String status=Objects.toString(assignment.get("status"),"UNRESOLVED");counts.merge(status,1,Integer::sum);byId.put(text(occurrence,"id"),assignment);
        }
        for(Map<String,Object> group:ConsistencyRunSupport.maps(report.get("groups"))) {
            List<Map<String,Object>> assignments=new ArrayList<>();
            if(group.get("occurrenceIds") instanceof List<?>)for(Object id:((List<?>) (group.get("occurrenceIds")))) {
                Map<String,Object> entry=new LinkedHashMap<>(byId.getOrDefault(id.toString(),cn.bidcheck.compat.Java8Api.mapOf("status", "UNRESOLVED")));
                entry.put("occurrenceId",id);assignments.add(entry);
            }
            group.put("contextAssignments",assignments);
            group.put("contextStatus",assignments.isEmpty()||assignments.stream().anyMatch(a->"UNRESOLVED".equals(a.get("status")))?"UNRESOLVED":assignments.stream().anyMatch(a->"DOCUMENT_DEFAULT".equals(a.get("status")))?"DOCUMENT_DEFAULT":"SOURCE_BOUND");
        }
        Map<String,Object> summary=new LinkedHashMap<>(counts);summary.put("version",DocumentContextIndex.VERSION);
        ConsistencyRunSupport.map(report.get("coverage")).put("contextAssignments",summary);
    }

    private static Map<String,Object> mergePersonCredentials(Map<String,Object> report,Map<String,Object> persons) {
        List<Map<String,Object>> occurrences=new ArrayList<>(ConsistencyRunSupport.maps(report.get("occurrences")));
        List<Map<String,Object>> initialGroups=new ArrayList<>(ConsistencyRunSupport.maps(report.get("groups")));
        List<Map<String,Object>> personOccurrences=ConsistencyRunSupport.maps(persons.get("occurrences"));
        List<Map<String,Object>> information=new ArrayList<>(ConsistencyRunSupport.maps(persons.get("informationalGroups")));
        List<Map<String,Object>> groups=movePersonnelCertificateInformation(initialGroups,occurrences,personOccurrences,information);
        occurrences.addAll(personOccurrences);
        groups.addAll(ConsistencyRunSupport.maps(persons.get("groups")));
        Map<String,Object> coverage=ConsistencyRunSupport.map(report.get("coverage"));
        Map<String,Object> personCoverage=ConsistencyRunSupport.map(persons.get("coverage"));
        coverage.put("personCredentials",personCoverage);
        personCoverage.put("informationalGroupsCount",information.size());
        if(!information.isEmpty()&&!PersonCredentialConsistency.requiresReview(personCoverage))personCoverage.put("attentionStatus","INFORMATION_ONLY");
        report.put("personCredentialInformationalGroups",information);
        coverage.put("occurrencesCount",occurrences.size());coverage.put("comparisonGroupsCount",groups.size());
        coverage.put("observedFields",cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(o->text(o,"field")).distinct()));
        int old=coverage.get("pagesUnparsed") instanceof Number?((Number) (coverage.get("pagesUnparsed"))).intValue():0;
        int added=personCoverage.get("actionablePages") instanceof Number?((Number) (personCoverage.get("actionablePages"))).intValue():0;
        coverage.put("pagesUnparsed",Math.max(old,added));
        // Personnel extraction detail stays PARTIAL in its own coverage. Informational gaps are not unread pages.
        if(PersonCredentialConsistency.requiresReview(personCoverage))coverage.put("status","PARTIAL");
        for(Map<String,Object> category:ConsistencyRunSupport.maps(coverage.get("categories"))) if(cn.bidcheck.compat.Java8Api.setOf("personnel", "qualification").contains(category.get("id"))) {
            String categoryId=text(category,"id");
            long facts=occurrences.stream().filter(o->categoryId.equals(o.get("categoryId"))).count();
            long issues=groups.stream().filter(g->categoryId.equals(g.get("categoryId"))).count();
            boolean missing=groups.stream().anyMatch(g->categoryId.equals(g.get("categoryId"))&&"EVIDENCE_INSUFFICIENT".equals(g.get("status")));
            category.put("occurrences",facts);category.put("groups",issues);
            category.put("status",missing||categoryId.equals("personnel")&&PersonCredentialConsistency.requiresReview(personCoverage)?"NEEDS_CONTEXT":facts>0?"OBSERVED":"NO_FACTS");
        }
        List<String> limitations=new ArrayList<>();
        for(Object value:(List<?>)report.get("limitations")) limitations.add(value.toString());
        for(Object value:(List<?>)persons.get("limitations")) limitations.add(value.toString());
        report.put("occurrences",occurrences);report.put("groups",groups);report.put("limitations",cn.bidcheck.compat.Java8Api.toList(limitations.stream().distinct()));
        return report;
    }

    /** Generic certificate rules can rediscover a personnel field. Demote only the same proven source record's missing-context notice. */
    private static List<Map<String,Object>> movePersonnelCertificateInformation(List<Map<String,Object>> groups,List<Map<String,Object>> generic,List<Map<String,Object>> persons,List<Map<String,Object>> information) {
        Map<String,Map<String,Object>> byId=new HashMap<>();generic.forEach(o->byId.put(text(o,"id"),o));
        Set<String> personalRecords=new HashSet<>();
        for(Map<String,Object> person:persons)if("credentialPersonName".equals(person.get("field"))
                &&"EXPLICIT".equals(ConsistencyRunSupport.map(person.get("personCredentialContext")).get("personNameStatus"))
                &&!text(person,"rawValue").matches("(?s).*(?:公司|集团|企业|事务所|合作社|委员会|事业单位).*"))personalRecords.add(text(person,"recordKey"));
        List<Map<String,Object>> retained=new ArrayList<>();
        for(Map<String,Object> group:groups) {
            if(!"EVIDENCE_INSUFFICIENT".equals(group.get("status"))||!(group.get("occurrenceIds") instanceof List<?>)) {retained.add(group);continue;}
            List<Map<String,Object>> known=new ArrayList<>(),other=new ArrayList<>();boolean missing=false;
            for(Object id:((List<?>) (group.get("occurrenceIds")))) {
                Map<String,Object> occurrence=byId.get(Objects.toString(id,""));
                if(occurrence==null){missing=true;break;}
                (personnelCertificateSource(occurrence,persons,personalRecords)?known:other).add(occurrence);
            }
            if(missing||known.isEmpty()){retained.add(group);continue;}
            information.add(sourceSubset(group,known,true));
            if(!other.isEmpty())retained.add(sourceSubset(group,other,false));
        }
        return retained;
    }

    private static boolean personnelCertificateSource(Map<String,Object> generic,List<Map<String,Object>> persons,Set<String> personalRecords) {
        String personField;
switch (text(generic,"field")) {case "certificateNumber":personField = "personCertificateNumber"; break;case "certificateHolder":personField = "credentialPersonName"; break;case "qualificationGrade":personField = "certificateLevel"; break;case "certificateExpiry":personField = "credentialValidUntil"; break;default:personField = ""; break;}
        if(cn.bidcheck.compat.Java8Api.isBlank(personField)||!(generic.get("evidence") instanceof Evidence)||!(generic.get("sourceOffset") instanceof Number))return false;
        for(Map<String,Object> person:persons) {
            if(!personalRecords.contains(text(person,"recordKey"))||!personField.equals(person.get("field"))||!text(generic,"rawValue").equals(text(person,"rawValue"))||!(person.get("evidence") instanceof Evidence))continue;
            if(!((Evidence) (generic.get("evidence"))).documentId().equals(((Evidence) (person.get("evidence"))).documentId())||((Evidence) (generic.get("evidence"))).page()!=((Evidence) (person.get("evidence"))).page()||!((Evidence) (generic.get("evidence"))).locationType().equals(((Evidence) (person.get("evidence"))).locationType())||!((Evidence) (person.get("evidence"))).quote().contains(((Evidence) (generic.get("evidence"))).quote()))continue;
            Map<String,Object> context=ConsistencyRunSupport.map(person.get("personCredentialContext"));
            try {int start=Integer.parseInt(text(context,"recordStart")),end=Integer.parseInt(text(context,"recordEnd"));
                if(((Number) (generic.get("sourceOffset"))).intValue()>=start&&(long)((Number) (generic.get("sourceOffset"))).intValue()+((Evidence) (generic.get("evidence"))).quote().length()<=end)return true;
            }catch(NumberFormatException ignored){}
        }
        return false;
    }

    private static Map<String,Object> sourceSubset(Map<String,Object> original,List<Map<String,Object>> occurrences,boolean information) {
        Map<String,Object> result=new LinkedHashMap<>(original);List<Evidence> evidence=new ArrayList<>();
        for(Map<String,Object> occurrence:occurrences){if(occurrence.get("evidence") instanceof Evidence)evidence.add(((Evidence) (occurrence.get("evidence"))));if(occurrence.get("contextEvidences") instanceof List<?>)for(Object ref:((List<?>) (occurrence.get("contextEvidences"))))if(ref instanceof Evidence)evidence.add(((Evidence) (ref)));}
        result.put("occurrenceIds",cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(o->o.get("id"))));result.put("evidences",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()));
        result.put("values",cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(o->o.get("value")).distinct()));
        result.put("sourcePages",cn.bidcheck.compat.Java8Api.toList(evidence.stream().map(Evidence::page).distinct().sorted()));
        result.put("sourceLocations",cn.bidcheck.compat.Java8Api.toList(evidence.stream().map(e->map("documentId",e.documentId(),"documentName",e.documentName(),"page",e.page(),"locationType",e.locationType())).distinct()));
        if(information){result.put("id",text(original,"id")+"-person-information");result.put("severity","INFO");result.put("requiresReview",false);result.put("title",text(original,"fieldLabel")+"提取说明");result.put("reason","此字段已定位到人员证件原文；类别或关联条件不完整仅作提取说明，其他明确字段继续检查。");}
        return result;
    }

    /** Keep one report contract for API runs and direct engine users; never retain a relation with missing operands. */
    private static Map<String,Object> mergeExpanded(Map<String,Object> report, Map<String,Object> expanded) {
        List<Map<String,Object>> occurrences=new ArrayList<>(ConsistencyRunSupport.maps(report.get("occurrences")));
        List<Map<String,Object>> groups=new ArrayList<>(ConsistencyRunSupport.maps(report.get("groups")));
        for(Map<String,Object> occurrence:occurrences) {
            Map<String,Object> definition=ConsistencyCatalog.definition(text(occurrence,"field"));
            occurrence.put("categoryId",definition.getOrDefault("categoryId","project_identity"));
            occurrence.put("fieldLabel",occurrence.get("title"));
            occurrence.put("dimensions",cn.bidcheck.compat.Java8Api.mapOf());
        }
        for(Map<String,Object> group:groups) {
            Map<String,Object> definition=ConsistencyCatalog.definition(text(group,"field"));
            group.put("categoryId",definition.getOrDefault("categoryId","project_identity"));
            group.put("fieldLabel",definition.getOrDefault("title",text(group,"field")));
            group.put("relationType","VALUE");group.put("ruleId","legacy-scoped-"+text(group,"field"));
            group.put("comparisonContext",map("subject",group.get("subject"),"scope",group.get("scope"),"lot",group.get("lot"),"basis",group.get("basis")));
        }
        occurrences.addAll(ConsistencyRunSupport.maps(expanded.get("occurrences")));
        groups.addAll(ConsistencyRunSupport.maps(expanded.get("groups")));
        Map<String,Object> coverage=ConsistencyRunSupport.map(report.get("coverage"));
        Map<String,Object> extraCoverage=ConsistencyRunSupport.map(expanded.get("coverage"));
        boolean truncated=occurrences.size()>MAX_OCCURRENCES||Boolean.TRUE.equals(coverage.get("occurrencesTruncated"))
                ||Boolean.TRUE.equals(extraCoverage.get("occurrencesTruncated"));
        if(occurrences.size()>MAX_OCCURRENCES)occurrences=new ArrayList<>(occurrences.subList(0,MAX_OCCURRENCES));
        Set<String> retained=new HashSet<>();for(Map<String,Object> occurrence:occurrences)retained.add(text(occurrence,"id"));
        int before=groups.size();
        groups.removeIf(group->!(group.get("occurrenceIds") instanceof List<?>)||((List<?>) (group.get("occurrenceIds"))).isEmpty()||!((List<?>) (group.get("occurrenceIds"))).stream().allMatch(id->retained.contains(Objects.toString(id,""))));
        coverage.put("groupsOmittedByBudget",before-groups.size());
        coverage.put("occurrencesTruncated",truncated);
        int oldUnparsed=coverage.get("pagesUnparsed") instanceof Number?((Number) (coverage.get("pagesUnparsed"))).intValue():0;
        int expandedUnparsed=extraCoverage.get("pagesUnparsed") instanceof Number?((Number) (extraCoverage.get("pagesUnparsed"))).intValue():0;
        coverage.put("pagesUnparsed",Math.max(oldUnparsed,expandedUnparsed));
        if(truncated||expandedUnparsed>0||"PARTIAL".equals(extraCoverage.get("status")))coverage.put("status","PARTIAL");
        coverage.put("occurrencesCount",occurrences.size());
        coverage.put("comparableOccurrencesCount",(int)occurrences.stream().filter(o->cn.bidcheck.compat.Java8Api.setOf("STRONG", "WEAK").contains(text(o,"comparability"))&&!text(o,"subject").equals("UNRESOLVED")).count());
        coverage.put("comparisonGroupsCount",groups.size());
        coverage.put("fields",cn.bidcheck.compat.Java8Api.toList(ConsistencyCatalog.fields().stream().filter(f->!f.equals("custom"))));
        coverage.put("observedFields",cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(o->text(o,"field")).distinct()));
        coverage.put("dynamicFields",cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(o->text(o,"field")).filter(f->f.startsWith("dynamic.")).distinct()));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("modelFactsAccepted", "modelFactsRejected")) {
            int old=coverage.get(key) instanceof Number?((Number) (coverage.get(key))).intValue():0;
            int added=extraCoverage.get(key) instanceof Number?((Number) (extraCoverage.get(key))).intValue():0;
            coverage.put(key,old+added);
        }
        coverage.put("expanded",extraCoverage);
        List<Map<String,Object>> categories=new ArrayList<>();
        for(String categoryId:ConsistencyCatalog.categories()) {
            long facts=occurrences.stream().filter(o->categoryId.equals(text(o,"categoryId"))).count();
            long issues=groups.stream().filter(g->categoryId.equals(text(g,"categoryId"))).count();
            boolean gaps=groups.stream().anyMatch(g->categoryId.equals(text(g,"categoryId"))&&"EVIDENCE_INSUFFICIENT".equals(text(g,"status")));
            categories.add(map("id",categoryId,"title",ConsistencyCatalog.categoryTitle(categoryId),"capability","RULES_AND_MODEL",
                    "status",gaps?"NEEDS_CONTEXT":facts>0?"OBSERVED":"NO_FACTS","occurrences",facts,"groups",issues,
                    "limitations",cn.bidcheck.compat.Java8Api.listOf("本地识别明确标签和简单表格；复杂表述需模型辅助，仍须核对适用对象、条件和版本。")));
        }
        coverage.put("categories",categories);
        List<String> limitations=new ArrayList<>();
        if(report.get("limitations") instanceof List<?>)((List<?>) (report.get("limitations"))).forEach(v->limitations.add(Objects.toString(v,"")));
        limitations.add("目录支持18类事项；未提取到事实不代表该类已核验，模型补充和自然语言差异仅作待复核线索。");
        if(expanded.get("limitations") instanceof List<?>)((List<?>) (expanded.get("limitations"))).forEach(v->limitations.add(Objects.toString(v,"")));
        report.put("catalogVersion",ConsistencyCatalog.VERSION);report.put("occurrences",occurrences);report.put("groups",groups);
        report.put("limitations",cn.bidcheck.compat.Java8Api.toList(limitations.stream().distinct()));
        return report;
    }

    private void extract(Located source, List<Map<String,Object>> out) {
        String line = source.text();
        Matcher company = COMPANY.matcher(line);
        while (company.find()) {
            String raw = trimValue(company.group(2));
            boolean explicit = company.group(1).matches("(?:投标人|投标单位|响应人|响应单位|供应商)(?:名称)?");
            add(out, source, "companyName", "BIDDER", raw, explicit ? "STRONG" : "WEAK", explicit ? "" : "企业字段未明确标注投标人", "");
        }
        Matcher price = PRICE.matcher(line);
        while (price.find()) {
            String raw = price.group(2).trim();
            String unit = unit(raw);
            if (cn.bidcheck.compat.Java8Api.isBlank(unit)) {
                Matcher headerUnit = Pattern.compile("[（(]\\s*(亿元|万元|元)\\s*[）)]").matcher(price.group(1));
                if (headerUnit.find()) unit = headerUnit.group(1);
            }
            Map<String,Object> item = add(out, source, "totalPrice", "BID_TOTAL", raw, "STRONG", "", unit);
            if (item != null) item.put("basis", tax(localClause(line, price.start(), price.end())) + "|" + cycle(localClause(line, price.start(), price.end())));
        }
        Matcher opening = OPENING.matcher(line);
        while (opening.find()) {
            Matcher date = DATE.matcher(opening.group(1));
            if (date.find()) add(out, source, "openingTime", "PROJECT_OPENING", date.group().trim(), "STRONG", "", "");
        }
        Matcher person = PERSON.matcher(line);
        while (person.find()) {
            String name = trimPerson(person.group(2));
            if (!validPerson(name)) continue;
            add(out, source, "personName", normalizeRole(person.group(1)), name, "STRONG", "", "");
        }
        Matcher nameRole = NAME_ROLE.matcher(line);
        while (nameRole.find()) {
            String name = trimPerson(nameRole.group(1)), role = trimPerson(nameRole.group(2));
            if (!validPerson(name) || cn.bidcheck.compat.Java8Api.isBlank(role)) continue;
            add(out, source, "personRole", normalize(name), role, "STRONG", "同名人员仍需结合证件核对身份", "");
            if (role.matches(ROLES)) add(out, source, "personName", normalizeRole(role), name, "STRONG", "", "");
        }
    }

    private static boolean previousRoleOrCredential(Located source){
        String page=Objects.toString(source.page().text(),"");
        String[] lines=page.split("\\R");int end=Math.min(lines.length,Math.max(0,source.line()));
        boolean history=false;
        for(int i=0;i<end;i++){
            String line=lines[i].replaceFirst("^\\s*[#*]+\\s*","").trim();
            if(line.matches(".*(?:主要工作经历|工作履历|参加过的类似项目|曾任职务).*")
                    ||line.replace(" ","").matches("^(?:中华人民共和国|建筑施工企业|建筑施工企业主要负责人|建筑施工企业项目负责人|建筑业企业|一级建造师|二级建造师)?(?:安全生产考核合格证(?:书)?|资格证书|执业注册证书|注册证书|职称证书|施工许可证|证书查询)(?:[（(].*|\\s*)$"))history=true;
            if(line.matches(".*(?:拟在本合同任职|本项目人员配置|拟派项目组人员|项目管理机构组成表).*"))history=false;
        }
        return history;
    }

    private Map<String,Object> add(List<Map<String,Object>> out, Located located, String field, String subject,
                                   String raw, String strength, String reason, String suppliedUnit) {
        if (raw == null || cn.bidcheck.compat.Java8Api.isBlank(raw) || raw.length() > 500 || PLACEHOLDER.matcher(raw.trim()).matches() || !located.text().contains(raw) || out.size() >= MAX_OCCURRENCES) return null;
        // Flattened tables may place a company or another column heading after a role.
        // These are extraction candidates, not person/company facts; keep the source page intact.
        if (field.equals("personName") && !validPerson(raw.trim())) return null;
        if ((field.equals("personName") || field.equals("personRole")) && previousRoleOrCredential(located)) return null;
        if (field.equals("companyName") && raw.trim().matches("人员类型|证书类型|证书类别|证书编号|查询结果|序号")) return null;
        if (located.text().length() > 10000) {
            int at = located.text().indexOf(raw);
            located = new Located(located.doc(), located.page(), located.line(), located.text().substring(Math.max(0, at - 600), Math.min(located.text().length(), at + raw.length() + 600)), located.context());
        }
        Context context = located.context();
        String scope = context.scope, lot = lot(located.text());
        if (cn.bidcheck.compat.Java8Api.isBlank(lot)) lot = context.lot;
        if (historical(located.text())) scope = "HISTORICAL_PROJECT";
        if (located.text().matches("(?s).*(?:招标人|采购人|甲方|业主|发包人)(?:公司|企业)?名称.*") && field.equals("companyName")
                && !located.text().matches("(?s).*(?:投标人|投标单位|响应人|响应单位).*")) scope = "THIRD_PARTY";
        if (field.equals("companyName") && strength.equals("WEAK") && scope.equals("CURRENT_PROJECT")) scope = "UNRESOLVED";
        if (located.text().matches("(?s).*(?:示例|填写说明|示范|范例|参考模板).*")) scope = "TEMPLATE";
        String revision = revision(located.text()) || context.revision ? "UNRESOLVED" : "CURRENT";
        String basis = field.equals("totalPrice") ? tax(located.text()) + "|" + cycle(located.text()) : "";
        String normalized = normalized(field, raw, suppliedUnit);
        boolean ocr = Objects.toString(located.page().parser(), "").toUpperCase(Locale.ROOT).contains("OCR");
        double confidence = ocr ? 0.78 : 0.98;
        String comparison = cn.bidcheck.compat.Java8Api.isBlank(normalized) ? "INVALID" : strength;
        if (incomplete(located.page()) || ocr) comparison = "WEAK";
        Evidence evidence = new Evidence(located.doc().id(), located.doc().name(), located.page().number(), located.page().locationType(),
                located.text(), "p" + located.page().number() + "-l" + located.line(), confidence);
        Map<String,Object> result = map("id", "occ-" + hash(located.doc().id() + "|" + located.doc().version() + "|" + evidence.locationType() + "|" + evidence.anchor() + "|" + field + "|" + subject + "|" + raw),
                "field", field, "title", title(field), "subject", subject, "scope", scope, "lot", lot, "basis", basis,
                "time", "", "rawValue", raw, "value", normalized, "evidence", evidence, "extractionMethod", ocr ? "OCR_TRANSCRIPT_RULE" : "TEXT_RULE",
                "confidence", confidence, "comparability", comparison, "scopeReason", reason, "revision", revision,
                "unit", field.equals("totalPrice") ? cn.bidcheck.compat.Java8Api.isBlank(suppliedUnit) ? unit(raw) : suppliedUnit : "");
        if (field.equals("openingTime")) result.put("precision", precision(raw));
        if(context.assignment!=null) {
            result.put("contextAssignment",context.assignment.asMap());result.put("sectionId",context.assignment.sectionId());
            result.put("contextEvidences",context.assignment.evidences());
        }
        if (field.equals("companyName")) {
            Matcher alias = Pattern.compile("[（(](?:以下简称|简称)[“\"‘']?([^”\"’'）)]+)[”\"’']?[）)]").matcher(raw);
            if (alias.find()) result.put("declaredAlias", normalize(alias.group(1)));
        }
        out.add(result);
        return result;
    }

    private void table(Located source, String[] headings, String[] values, List<Map<String,Object>> out, Evidence headerEvidence, int rowLine) {
        String name = "", role = "", rowLot = "";int nameColumn=0,roleColumn=0;
        int first = out.size();
        for (int i = 0; i < headings.length; i++) if (headings[i].matches("标段|标包|包件|包号")) {
            rowLot = lot(values[i]);
            if (cn.bidcheck.compat.Java8Api.isBlank(rowLot)) rowLot = lot(headings[i] + " " + values[i]);
        }
        for (int i = 0; i < headings.length; i++) {
            int beforeColumn=out.size();
            String label = headings[i].trim(), value = values[i].trim();
            if (cn.bidcheck.compat.Java8Api.isBlank(value) || !source.text().contains(value)) continue;
            if (label.matches("(?:投标人|投标单位|响应人|响应单位|供应商)(?:名称)?")) add(out, source, "companyName", "BIDDER", value, "STRONG", "表头与本行单元格绑定", "");
            if (label.matches("(?:开标时间|开标日期)")) add(out, source, "openingTime", "PROJECT_OPENING", value, "STRONG", "表头与本行单元格绑定", "");
            if (label.matches("(?:投标总报价|投标报价|投标总价|总报价|总价).{0,12}")) {
                Matcher unit = Pattern.compile("亿元|万元|元").matcher(label);
                Map<String,Object> amount = add(out, source, "totalPrice", "BID_TOTAL", value, "STRONG", "表头与本行单元格绑定", unit.find() ? unit.group() : "");
                if (amount != null) amount.put("basis", tax(label + " " + value) + "|" + cycle(label + " " + value));
            }
            if (label.matches(ROLES)) add(out, source, "personName", normalizeRole(label), value, "STRONG", "表头与本行单元格绑定", "");
            if (label.matches("姓名|人员姓名")){name=value;nameColumn=i+1;}
            if (label.matches("岗位|职务|拟任职务|担任职务|本项目职务|在本项目中担任职务")){role=value;roleColumn=i+1;}
            for(int added=beforeColumn;added<out.size();added++)out.get(added).put("sourceColumn",i+1);
        }
        if (validPerson(name) && !cn.bidcheck.compat.Java8Api.isBlank(role)) {
            Map<String,Object> roleFact=add(out, source, "personRole", normalize(name), role, "STRONG", "表头与本行单元格绑定；同名人员需核对身份", "");if(roleFact!=null)roleFact.put("sourceColumn",roleColumn);
            if (role.matches(ROLES)){Map<String,Object> nameFact=add(out, source, "personName", normalizeRole(role), name, "STRONG", "表头与本行单元格绑定", "");if(nameFact!=null)nameFact.put("sourceColumn",nameColumn);}
        }
        for (int i = first; i < out.size(); i++) {
            Map<String,Object> occurrence = out.get(i);
            List<Evidence> references=new ArrayList<>();
            if(occurrence.get("contextEvidences") instanceof List<?>)for(Object reference:((List<?>) (occurrence.get("contextEvidences"))))if(reference instanceof Evidence)references.add(((Evidence) (reference)));
            references.add(headerEvidence);occurrence.put("contextEvidences",cn.bidcheck.compat.Java8Api.toList(references.stream().distinct().limit(12)));
            occurrence.put("id", "occ-" + hash(text(occurrence, "id") + "|row:" + rowLine));
            occurrence.put("sourceRowLine", rowLine);
            if (!cn.bidcheck.compat.Java8Api.isBlank(rowLot)) occurrence.put("lot", rowLot);
        }
    }

    private Map<String,Object> supplemental(Document doc, Map<String,Object> input) {
        if (input == null || !FIELDS.contains(text(input, "field"))) return null;
        Evidence evidence = evidence(input.get("evidence"));
        if (evidence == null || !Objects.equals(doc.id(), evidence.documentId()) || evidence.quote() == null || cn.bidcheck.compat.Java8Api.isBlank(evidence.quote()) || evidence.quote().length() > 12000) return null;
        Page page = (doc.pages() == null ? cn.bidcheck.compat.Java8Api.<Page>listOf() : doc.pages()).stream().filter(p -> p.number() == evidence.page() && Objects.equals(p.locationType(), evidence.locationType())).findFirst().orElse(null);
        String raw = text(input, "rawValue");
        if (page == null || page.text() == null || !page.text().contains(evidence.quote()) || cn.bidcheck.compat.Java8Api.isBlank(raw) || raw.length() > 500 || !evidence.quote().contains(raw)) return null;
        int sourceOffset = page.text().indexOf(evidence.quote());
        if (input.get("sourceOffset") instanceof Number) {
            int requestedOffset = ((Number) (input.get("sourceOffset"))).intValue();
            if (requestedOffset < 0 || requestedOffset + evidence.quote().length() > page.text().length() || !page.text().startsWith(evidence.quote(), requestedOffset)) return null;
            sourceOffset = requestedOffset;
        }
        String field = text(input, "field"), subject = text(input, "subject");
        String contextQuote = text(input, "contextQuote");
        if (contextQuote.length() > 6000 || !cn.bidcheck.compat.Java8Api.isBlank(contextQuote) && !page.text().contains(contextQuote)) return null;
        // The contextual subject/scope must enclose this value's quote, not another occurrence elsewhere on the page.
        if (!cn.bidcheck.compat.Java8Api.isBlank(contextQuote)) {
            int inContext = contextQuote.indexOf(evidence.quote());
            boolean bound = false;
            while (inContext >= 0) {
                int contextStart = sourceOffset - inContext;
                if (contextStart >= 0 && page.text().startsWith(contextQuote, contextStart)) { bound = true; break; }
                inContext = contextQuote.indexOf(evidence.quote(), inContext + 1);
            }
            if (!bound) contextQuote = "";
        }
        String semanticSource = evidence.quote() + "\n" + contextQuote;
        switch (field) {case "companyName":subject = "BIDDER"; break;case "totalPrice":subject = "BID_TOTAL"; break;case "openingTime":subject = "PROJECT_OPENING"; break;default:subject = subject; break;}
        if ((field.equals("personName") || field.equals("personRole")) && (cn.bidcheck.compat.Java8Api.isBlank(subject) || !semanticSource.contains(subject))) subject = "UNRESOLVED";
        if (field.equals("personName")) subject = normalizeRole(subject);
        if (field.equals("personRole")) subject = normalize(subject);
        Context context = new Context();
        context.scope = "UNRESOLVED";
        String scope = text(input, "scope");
        if (scope.equals("HISTORICAL_PROJECT") || historical(semanticSource)) context.scope = "HISTORICAL_PROJECT";
        else if (scope.equals("THIRD_PARTY")) context.scope = "THIRD_PARTY";
        else if (scope.equals("CURRENT_PROJECT") && semanticSource.matches("(?s).*(?:本项目|本次投标|投标人|投标报价|投标总价|开标时间|开标日期|拟任|项目经理|项目负责人).*")) context.scope = "CURRENT_PROJECT";
        context.lot = lot(semanticSource);
        String requestedLot = text(input, "lot");
        if (!cn.bidcheck.compat.Java8Api.isBlank(requestedLot) && cn.bidcheck.compat.Java8Api.isBlank(context.lot)) context.scope = "UNRESOLVED";
        List<Map<String,Object>> target = new ArrayList<>();
        Map<String,Object> result = add(target, new Located(doc, page, physicalLine(page.text(), sourceOffset), evidence.quote(), context), field,
                subject, raw, "WEAK", "模型补充事实；来源已核对，主体、范围和角色对应仍需复核", "");
        if (result == null || cn.bidcheck.compat.Java8Api.isBlank(text(result, "value"))) return null;
        result.put("extractionMethod", "MODEL_SOURCE_VERIFIED");
        result.put("confidence", Math.min(0.8, finiteConfidence(evidence.confidence())));
        result.put("comparability", "WEAK");
        if (field.equals("companyName") && context.scope.equals("CURRENT_PROJECT") && text(result, "scope").equals("UNRESOLVED")) result.put("scope", "CURRENT_PROJECT");
        if (field.equals("totalPrice")) result.put("basis", tax(semanticSource) + "|" + cycle(semanticSource));
        if (!cn.bidcheck.compat.Java8Api.isBlank(contextQuote)) {
            result.put("contextQuote", contextQuote);
            result.put("contextEvidences", cn.bidcheck.compat.Java8Api.listOf(new Evidence(doc.id(), doc.name(), page.number(), page.locationType(), contextQuote,
                    "p" + page.number() + "-l" + physicalLine(page.text(), page.text().indexOf(contextQuote)), 0)));
        }
        result.put("sourceOffset", sourceOffset);
        result.put("id", "occ-" + hash(text(result, "id") + "|offset:" + sourceOffset));
        result.put("evidence", new Evidence(doc.id(), doc.name(), page.number(), page.locationType(), evidence.quote(), evidence.anchor(), Math.min(0.8, finiteConfidence(evidence.confidence()))));
        return result;
    }

    private List<Map<String,Object>> compare(List<Map<String,Object>> occurrences) {
        Map<String,List<Map<String,Object>>> buckets = new LinkedHashMap<>();
        for (Map<String,Object> occurrence : occurrences) {
            if (!eligible(occurrence)) continue;
            String key = String.join("|", text(occurrence, "field"), text(occurrence, "subject"), text(occurrence, "scope"), text(occurrence, "lot"), text(occurrence, "basis"), text(occurrence, "time"));
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(occurrence);
        }
        List<Map<String,Object>> groups = new ArrayList<>();
        for (java.util.Map.Entry<java.lang.String, java.util.List<java.util.Map<java.lang.String, java.lang.Object>>> bucket : buckets.entrySet()) {
            interrupted();
            List<Map<String,Object>> items = bucket.getValue();
            if (items.size() < 2) continue;
            String field = text(items.get(0), "field");
            Map<String,Object> reference = items.stream().max(Comparator.comparingInt(o -> precisionRank(text(o, "precision")))).orElseThrow(java.util.NoSuchElementException::new);
            boolean difference = items.stream().anyMatch(item -> incompatible(field, reference, item));
            if (!difference) continue;
            boolean weak = items.stream().anyMatch(o -> !text(o, "comparability").equals("STRONG") || !text(o, "revision").equals("CURRENT"))
                    || field.equals("personName") || field.equals("personRole") || text(items.get(0), "lot").equals("UNRESOLVED");
            String reason = "在相同主体、字段和可识别范围内出现不同值，请对照各处原文确认。";
            if (field.equals("openingTime")) reason = "本项目开标时间在不同位置出现不兼容日期或时刻；仅日期与同日具体时刻不会被视为冲突。";
            if (field.equals("totalPrice")) reason = "同一标段及已识别税额、期间口径下的总报价不同，金额已换算为元。";
            if (field.equals("personName")) reason = "同一关键岗位在不同位置出现不同姓名，需确认是否同一标段、单人岗位或多人配置。";
            if (field.equals("personRole")) reason = "同名人员出现不同职务，需核对人员身份、任职时点及是否允许兼任。";
            if (items.stream().anyMatch(o -> !text(o, "revision").equals("CURRENT"))) reason += " 原文含更正或新旧版本线索，需先确认有效版本。";
            if (items.stream().anyMatch(o -> text(o, "extractionMethod").contains("OCR"))) reason += " 存在 OCR 转录，需核对图片原文。";
            List<Evidence> evidence = new ArrayList<>();
            for (Map<String,Object> item : items) {
                evidence.add((Evidence)item.get("evidence"));
                if (item.get("contextEvidences") instanceof List<?>) for (Object ref : ((List<?>) (item.get("contextEvidences")))) if (ref instanceof Evidence) evidence.add(((Evidence) (ref)));
            }
            evidence = cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct());
            groups.add(map("id", "conflict-" + hash(bucket.getKey()), "field", field, "title", title(field) + "存在" + (weak ? "疑似差异" : "不一致"),
                    "subject", items.get(0).get("subject"), "scope", items.get(0).get("scope"), "lot", items.get(0).get("lot"), "basis", items.get(0).get("basis"),
                    "status", weak ? "SUSPECTED" : "CONFLICT", "reason", reason,
                    "occurrenceIds", cn.bidcheck.compat.Java8Api.toList(items.stream().map(o -> o.get("id"))), "evidences", evidence,
                    "values", cn.bidcheck.compat.Java8Api.toList(items.stream().map(o -> o.get("value")).distinct())));
        }
        return groups;
    }

    /** Legal representatives belong to an enterprise, not to one global PERSON_NAME bucket.
     * Rebuild this single field after all native and source-reviewed occurrences have merged. */
    @SuppressWarnings("unchecked")
    private void scopeLegalRepresentativeGroups(Document document,Map<String,Object> report){
        List<Map<String,Object>> occurrences=ConsistencyRunSupport.maps(report.get("occurrences"));
        List<Map<String,Object>> groups=new ArrayList<>(ConsistencyRunSupport.maps(report.get("groups")));
        List<Map<String,Object>> stableParents=new ArrayList<>();for(Map<String,Object> group:groups)if("personName".equals(text(group,"field"))&&"法定代表人".equals(text(group,"subject")))stableParents.add(group);
        groups.removeIf(g->"personName".equals(text(g,"field"))&&"法定代表人".equals(text(g,"subject")));
        Map<String,List<Map<String,Object>>> buckets=new LinkedHashMap<>();
        for(Map<String,Object> occurrence:occurrences){
            if(!"personName".equals(text(occurrence,"field"))||!"法定代表人".equals(text(occurrence,"subject"))||!eligible(occurrence))continue;
            Evidence evidence=evidence(occurrence.get("evidence"));if(evidence==null)continue;
            Page page=document.pages().stream().filter(p->p.number()==evidence.page()&&Objects.equals(p.locationType(),evidence.locationType())).findFirst().orElse(null);
            if(page==null)continue;
            String entity=legalRepresentativeEntity(page);
            // An unresolved entity is isolated to its original page; it never compares against a
            // different enterprise merely because both rows say “法定代表人”.
            if(cn.bidcheck.compat.Java8Api.isBlank(entity))entity="UNRESOLVED:"+document.id()+":"+page.number()+":"+page.locationType()+":"+text(occurrence,"id");
            occurrence.put("organizationScope",entity);
            buckets.computeIfAbsent(entity+"|"+text(occurrence,"scope")+"|"+text(occurrence,"lot")+"|"+text(occurrence,"revision"),k->new ArrayList<>()).add(occurrence);
        }
        Map<String,List<Map<String,Object>>> comparisons=new LinkedHashMap<>();
        for(Map.Entry<String,List<Map<String,Object>>> entry:buckets.entrySet())for(Map<String,Object> result:compare(entry.getValue())){
            String key=legalRepresentativeBucketKey(result);Map<String,Object> scoped=new LinkedHashMap<>(result);scoped.put("organizationScope",text(entry.getValue().get(0),"organizationScope"));comparisons.computeIfAbsent(key,k->new ArrayList<>()).add(scoped);
        }
        for(Map<String,Object> parent:stableParents){
            String key=legalRepresentativeBucketKey(parent);List<Map<String,Object>> detail=comparisons.getOrDefault(key,Collections.emptyList());Map<String,Object> stable=new LinkedHashMap<>(parent);
            stable.put("organizationComparisons",detail);if(detail.isEmpty()){
                List<Map<String,Object>> unresolved=legalRepresentativeOccurrences(occurrences,parent,true);
                stable.put("status","EVIDENCE_INSUFFICIENT");stable.put("title",title("personName")+"缺少比较依据");stable.put("reason","法定代表人所属企业尚未形成可验证的同企业关系，已保留原文事实并按原检查项待核；不同企业或归属不明的数据不互比。");
                stable.put("values",Collections.emptyList());stable.put("evidences",cn.bidcheck.compat.Java8Api.toList(unresolved.stream().map(o->o.get("evidence")).distinct()));stable.put("occurrenceIds",cn.bidcheck.compat.Java8Api.toList(unresolved.stream().map(o->o.get("id")).distinct()));stable.put("unresolvedOccurrences",unresolved.stream().map(o->map("occurrenceId",o.get("id"),"value",o.get("value"),"organizationScope",o.get("organizationScope"),"evidence",o.get("evidence"))).collect(java.util.stream.Collectors.toList()));
            }else{
                stable.put("status",detail.stream().anyMatch(g->"CONFLICT".equals(g.get("status")))?"CONFLICT":"SUSPECTED");
                stable.put("reason",String.join("；",detail.stream().map(g->text(g,"reason")).distinct().collect(java.util.stream.Collectors.toList())));
                stable.put("values",detail.stream().flatMap(g->stringValues(g.get("values")).stream()).distinct().collect(java.util.stream.Collectors.toList()));
                stable.put("evidences",detail.stream().flatMap(g->objectValues(g.get("evidences")).stream()).distinct().collect(java.util.stream.Collectors.toList()));
                stable.put("occurrenceIds",detail.stream().flatMap(g->stringValues(g.get("occurrenceIds")).stream()).distinct().collect(java.util.stream.Collectors.toList()));
                List<Map<String,Object>> unresolved=legalRepresentativeOccurrences(occurrences,parent,true);stable.put("unresolvedOccurrences",unresolved.stream().map(o->map("occurrenceId",o.get("id"),"value",o.get("value"),"organizationScope",o.get("organizationScope"),"evidence",o.get("evidence"))).collect(java.util.stream.Collectors.toList()));
            }groups.add(stable);
        }
        report.put("groups",groups);
    }

    private static String legalRepresentativeBucketKey(Map<String,Object> group){return String.join("|","personName","法定代表人",text(group,"scope"),text(group,"lot"),text(group,"basis"),text(group,"time"));}
    private static List<Map<String,Object>> legalRepresentativeOccurrences(List<Map<String,Object>> occurrences,Map<String,Object> parent,boolean unresolvedOnly){return occurrences.stream().filter(o->"personName".equals(text(o,"field"))&&"法定代表人".equals(text(o,"subject"))&&text(o,"scope").equals(text(parent,"scope"))&&text(o,"lot").equals(text(parent,"lot"))&&text(o,"basis").equals(text(parent,"basis"))&&text(o,"time").equals(text(parent,"time"))).filter(o->!unresolvedOnly||text(o,"organizationScope").startsWith("UNRESOLVED:")).collect(java.util.stream.Collectors.toList());}
    private static List<String> stringValues(Object value){List<String> out=new ArrayList<>();if(value instanceof Iterable<?>)for(Object item:(Iterable<?>)value)out.add(Objects.toString(item,""));return out;}
    private static List<Object> objectValues(Object value){List<Object> out=new ArrayList<>();if(value instanceof Iterable<?>)for(Object item:(Iterable<?>)value)out.add(item);return out;}

    private static String legalRepresentativeEntity(Page page){
        String text=Objects.toString(page.text(),"");boolean license=text.matches("(?s).*(?:营业执照|统一社会信用代码).*" );
        boolean companyDetails=text.matches("(?s).*(?:企业详情|企业基本信息|企业信息).*" );
        if(!text.matches("(?s).*(?:法定代表人|企业法定代表人).*"))return "";
        Set<String> candidates=new LinkedHashSet<>();boolean query=text.matches("(?s).*(?:查询网址|查询截图|查询结果).*" );
        for(String line:text.split("\\R")){
            String value="";
            Matcher labelled=Pattern.compile("^[\\t ]*(?:\\|[\\t ]*)?(?:企业名称|公司名称)\\s*[:：|]?\\s*([^|｜\\t;；。]+)").matcher(line);
            Matcher licenseName=Pattern.compile("^[\\t ]*(?:\\|[\\t ]*)?名\\s*称\\s*[:：|]?\\s*([^|｜\\t;；。]+)").matcher(line);
            if(labelled.find())value=labelled.group(1);
            else if(license&&licenseName.find())value=licenseName.group(1);
            else if(query&&companyDetails&&line.matches("^[\\t ]*#{1,6}[\\t ]*[^#].*"))value=line.replaceFirst("^[\\t ]*#{1,6}[\\t ]*","");
            if(cn.bidcheck.compat.Java8Api.isBlank(value))continue;
            Matcher suffix=ENTITY_SUFFIX.matcher(value);if(!suffix.find())continue;
            int end=suffix.end();String candidate=value.substring(0,end).replaceAll("[\\t *`#]+$","").trim();
            candidate=candidate.replaceFirst("^(?:企业|公司|单位)?(?:名称|名\\s*称)\\s*","").trim();
            if(candidate.matches("[\\p{IsHan}A-Za-z0-9·（）()]{3,70}"))candidates.add(candidate);
        }
        return candidates.size()==1?candidates.iterator().next():"";
    }

    private boolean eligible(Map<String,Object> occurrence) {
        if (!text(occurrence, "scope").equals("CURRENT_PROJECT") || cn.bidcheck.compat.Java8Api.isBlank(text(occurrence, "value")) || cn.bidcheck.compat.Java8Api.setOf("INVALID", "INCOMPLETE").contains(text(occurrence, "comparability")) || text(occurrence, "subject").equals("UNRESOLVED")) return false;
        return !text(occurrence, "field").equals("totalPrice") || !cn.bidcheck.compat.Java8Api.isBlank(text(occurrence, "unit"));
    }

    private boolean incompatible(String field, Map<String,Object> left, Map<String,Object> right) {
        String a = text(left, "value"), b = text(right, "value");
        if (!field.equals("openingTime")) return !a.equals(b);
        if (!a.substring(0, 10).equals(b.substring(0, 10))) return true;
        if (text(left, "precision").equals("DAY") || text(right, "precision").equals("DAY")) return false;
        if (!a.substring(0, 16).equals(b.substring(0, 16))) return true;
        return text(left, "precision").equals("SECOND") && text(right, "precision").equals("SECOND") && !a.equals(b);
    }

    private static List<Map<String,Object>> deduplicate(List<Map<String,Object>> values) {
        Map<String,Map<String,Object>> unique = new LinkedHashMap<>();
        for (Map<String,Object> occurrence : values) {
            Evidence e = (Evidence) occurrence.get("evidence");
            String key = e.page() + "|" + e.locationType() + "|" + e.anchor() + "|" + text(occurrence, "sourceOffset") + "|" + e.quote() + "|" + text(occurrence, "sourceRowLine") + "|" + text(occurrence, "field") + "|" + text(occurrence, "subject") + "|" + text(occurrence, "value")
                    + "|" + String.join("|", text(occurrence, "scope"), text(occurrence, "lot"), text(occurrence, "basis"), text(occurrence, "time"), text(occurrence, "unit"));
            unique.putIfAbsent(key, occurrence);
        }
        return new ArrayList<>(unique.values());
    }

    private static void applyDeclaredAliases(List<Map<String,Object>> occurrences) {
        Map<String,Set<String>> aliases = new HashMap<>();
        for (Map<String,Object> occurrence : occurrences) if (text(occurrence, "field").equals("companyName") && !cn.bidcheck.compat.Java8Api.isBlank(text(occurrence, "declaredAlias"))) {
            String key = text(occurrence, "scope") + "|" + text(occurrence, "lot") + "|" + text(occurrence, "declaredAlias");
            aliases.computeIfAbsent(key, ignored -> new HashSet<>()).add(text(occurrence, "value"));
        }
        for (Map<String,Object> occurrence : occurrences) if (text(occurrence, "field").equals("companyName")) {
            Set<String> targets = aliases.get(text(occurrence, "scope") + "|" + text(occurrence, "lot") + "|" + text(occurrence, "value"));
            if (targets != null && targets.size() == 1) {
                occurrence.put("value", targets.iterator().next());
                occurrence.put("scopeReason", "按同范围原文明确声明的简称归一，仍保留原始写法");
            }
        }
    }

    private static String normalized(String field, String raw, String suppliedUnit) {
        if (field.equals("totalPrice")) {
            Matcher number = NUMBER.matcher(raw.trim());
            if (!number.matches()) return "";
            String unit = number.group(2) == null ? suppliedUnit : number.group(2);
            try {
                BigDecimal value = new BigDecimal(number.group(1).replace(",", ""));
                if (cn.bidcheck.compat.Java8Api.setOf("万", "万元").contains(unit)) value = value.multiply(new BigDecimal("10000"));
                if (unit.equals("亿元")) value = value.multiply(new BigDecimal("100000000"));
                return value.stripTrailingZeros().toPlainString();
            } catch (RuntimeException ignored) { return ""; }
        }
        if (field.equals("openingTime")) {
            Matcher date = DATE.matcher(raw);
            if (!date.find()) return "";
            try {
                LocalDate day = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
                if (date.group(4) == null) return day.toString();
                int hour = Integer.parseInt(date.group(4));
                if (raw.contains("下午") && hour < 12) hour += 12;
                if (raw.contains("上午") && hour == 12) hour = 0;
                LocalTime time = LocalTime.of(hour, date.group(5) == null ? 0 : Integer.parseInt(date.group(5)), date.group(6) == null ? 0 : Integer.parseInt(date.group(6)));
                return String.format(Locale.ROOT, "%sT%02d:%02d:%02d", day, time.getHour(), time.getMinute(), time.getSecond());
            } catch (DateTimeException | NumberFormatException ignored) { return ""; }
        }
        if (field.equals("companyName")) raw = raw.replaceAll("[（(](?:以下简称|简称).*[）)]", "").replaceAll("[（(](?:盖章|公章|签章|单位盖章)[）)]", "");
        return field.equals("personRole") ? normalizeRole(raw) : normalize(raw);
    }

    private static String precision(String raw) { Matcher matcher = DATE.matcher(raw); return matcher.find() ? matcher.group(4) == null ? "DAY" : matcher.group(6) == null ? "MINUTE" : "SECOND" : "UNKNOWN"; }
    private static int precisionRank(String value) { return value.equals("SECOND") ? 3 : value.equals("MINUTE") ? 2 : value.equals("DAY") ? 1 : 0; }
    private static String unit(String raw) { Matcher matcher = Pattern.compile("亿元|万元|元|万").matcher(raw); return matcher.find() ? matcher.group() : ""; }
    private static String tax(String value) { return value.matches("(?s).*(?:不含税|未税|税前).*") ? "EXCLUSIVE" : value.matches("(?s).*(?:含税|税后).*") ? "INCLUSIVE" : "UNSPECIFIED"; }
    private static String cycle(String value) { return value.matches("(?s).*(?:年度|每年|年报价|年费用).*") ? "YEAR" : value.matches("(?s).*(?:每月|月报价|月费用).*") ? "MONTH" : "TOTAL"; }
    private static String normalize(String value) { return value.replace('（', '(').replace('）', ')').replaceAll("[\\s\\u3000]", "").toUpperCase(Locale.ROOT); }
    private static String normalizeRole(String value) { return normalize(value).replaceAll("^(?:拟任|担任|本项目)", ""); }
    private static String trimValue(String value) { return value.split("(?:统一社会信用代码|联系人|法定代表人|地址|电话|签署日期|日期)\\s*[:：=]", 2)[0].trim().replaceFirst("[，,\\s]+$", ""); }
    private static String trimPerson(String value) { return value.split("(?:身份证|签字|签章|联系电话|电话|职务|岗位|姓名)\\s*[:：=]?", 2)[0].trim(); }
    private static boolean validPerson(String value) {
        return value != null && value.matches("[\\p{IsHan}·]{2,12}") && !PLACEHOLDER.matcher(value).matches()
                && !value.matches("(?:签字|签章|盖章|姓名|详见附件|不适用|项目经理|项目负责人|无|投标单位名称|投标人名称|人员类型|担任职务|发包人及)")
                && !value.matches(".*(?:公司|分公司|资格证书|考核合格证|京建安|发包人|承包人).*")
                && !value.matches("(?:是指|指甲方|指乙方|指投标人|应当|应具备|必须).*");
    }
    private static String title(String field) { switch (field) {case "companyName":return "投标企业名称";case "totalPrice":return "投标总报价";case "openingTime":return "开标时间";case "personName":return "关键岗位人员";case "personRole":return "人员职务";default:return field;} }
    private static boolean historical(String value) { return value.matches("(?s).*(?:历史项目|历史合同|过往项目|过往合同|类似项目业绩|类似项目合同|业绩项目|合同业绩|业绩合同|已完成项目|已完成合同|既往项目|既往合同|历史业绩).*"); }
    private static boolean revision(String value) { return value.matches("(?s).*(?:原定|原开标|原报价|调整为|更正为|变更为|修改为|现定|修订版|更正公告|补遗).*"); }
    private static String localClause(String line, int start, int end) {
        int from = start, to = end;
        while (from > 0 && "，,；;。|｜\t\n\r".indexOf(line.charAt(from - 1)) < 0) from--;
        while (to < line.length() && "，,；;。|｜\t\n\r".indexOf(line.charAt(to)) < 0) to++;
        return line.substring(from, to);
    }
    private static String lot(String value) {
        Set<String> lots = new LinkedHashSet<>(); Matcher matcher = LOT.matcher(value);
        while (matcher.find()) lots.add("LOT-" + chineseNumber(matcher.group(1) == null ? matcher.group(2) : matcher.group(1)));
        return lots.isEmpty() ? "" : lots.size() == 1 ? lots.iterator().next() : "UNRESOLVED";
    }
    private static String chineseNumber(String value) {
        if (value.matches("[0-9]+")) return String.valueOf(Integer.parseInt(value));
        int sum = 0, digit = 0; String digits = "零一二三四五六七八九";
        for (char c : value.toCharArray()) { int at = digits.indexOf(c); if (at >= 0) digit = at; else if (c == '十') { sum += (digit == 0 ? 1 : digit) * 10; digit = 0; } else if (c == '百') { sum += (digit == 0 ? 1 : digit) * 100; digit = 0; } }
        return String.valueOf(sum + digit);
    }
    private static boolean labelOnly(String text) { return text.trim().matches("(?:(?:投标人|投标单位|响应人|供应商)(?:名称)?|投标总报价|投标报价|投标总价|开标时间|开标日期|" + ROLES + ")\\s*[:：=|｜]?\\s*"); }
    private static String[] cells(String text) { return Arrays.stream(text.replaceAll("^[|｜]|[|｜]$", "").split("[|｜\\t]", -1)).map(String::trim).toArray(String[]::new); }
    private static boolean header(String[] values) { return Arrays.stream(values).filter(v -> v.matches("(?:序号|标段|标包|包件|包号|姓名|人员姓名|岗位|职务|拟任职务|担任职务|本项目职务|投标人名称|投标单位名称|开标时间|开标日期|投标总报价.*|投标报价.*|投标总价.*|总报价.*|总价.*|" + ROLES + ")")).count() >= 2; }
    private static boolean incomplete(Page page) { String warning = Objects.toString(page.warning(), ""); return warning.matches("(?s).*(?:OCR_DEFERRED|OCR未完成|未识别|未解析|解析失败|图片未|图像未|无文本).*" ) || (page.layout() != null && "PARTIAL".equals(page.layout().status())); }
    private static boolean documentIncomplete(Document document) { return document.warnings() != null && document.warnings().stream().anyMatch(w -> w != null && w.matches("(?s).*(?:OCR_DEFERRED|OCR未完成|未识别|未解析|解析失败|图片未|图像未).*")); }
    private static int physicalLine(String text, int offset) { return (int)text.substring(0, Math.max(0, offset)).chars().filter(c -> c == '\n').count() + 1; }
    private static int nthLineStart(String text, int index) { Matcher matcher = Pattern.compile("\\R").matcher(text); int at = 0; for (int i = 0; i < index && matcher.find(); i++) at = matcher.end(); return at; }
    private static int nthLineEnd(String text, int index) { Matcher matcher = Pattern.compile("\\R").matcher(text); for (int i = 0; matcher.find(); i++) if (i == index) return matcher.start(); return text.length(); }
    private static void interrupted() { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CONSISTENCY_CANCELLED"); }
    private static String text(Map<String,Object> map, String key) { return Objects.toString(map.get(key), ""); }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i + 1]); return result; }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static double finiteConfidence(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
    private static Evidence evidence(Object value) {
        if (value instanceof Evidence) return ((Evidence) (value));
        if (!(value instanceof Map<?,?>)) return null;
        try { return new Evidence(Objects.toString(((Map<?,?>) (value)).get("documentId"), ""), Objects.toString(((Map<?,?>) (value)).get("documentName"), ""), Integer.parseInt(Objects.toString(((Map<?,?>) (value)).get("page"), "0")), Objects.toString(((Map<?,?>) (value)).get("locationType"), ""), Objects.toString(((Map<?,?>) (value)).get("quote"), ""), Objects.toString(((Map<?,?>) (value)).get("anchor"), ""), Double.parseDouble(Objects.toString(((Map<?,?>) (value)).get("confidence"), "0.7"))); }
        catch (RuntimeException ignored) { return null; }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Located {
        private final Document doc;
        private final Page page;
        private final int line;
        private final String text;
        private final Context context;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Located(@com.fasterxml.jackson.annotation.JsonProperty("doc") Document doc, @com.fasterxml.jackson.annotation.JsonProperty("page") Page page, @com.fasterxml.jackson.annotation.JsonProperty("line") int line, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("context") Context context) { this.doc = doc; this.page = page; this.line = line; this.text = text; this.context = context; }
        public Document doc() { return doc; }
        public Page page() { return page; }
        public int line() { return line; }
        public String text() { return text; }
        public Context context() { return context; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Located that = (Located) other; return java.util.Objects.equals(this.doc, that.doc) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.line, that.line) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.context, that.context); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.doc); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.line); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.context); return hash; }
        @Override public String toString() { return "Located[" + "doc=" + doc + ", page=" + page + ", line=" + line + ", text=" + text + ", context=" + context + "]"; }
 }
    private static final class Context {
        String scope = "CURRENT_PROJECT", lot = ""; boolean revision;
        DocumentContextIndex.Assignment assignment;
        Context copy() { Context copy = new Context(); copy.scope = scope; copy.lot = lot; copy.revision = revision;copy.assignment=assignment;return copy; }
        void bind(DocumentContextIndex.Assignment value){assignment=value;scope=value.scope();lot=value.lot();}
        void advance(String line) {
            String clean = cn.bidcheck.compat.Java8Api.stripTrailing(line.trim().replaceFirst("[:：]$", ""));
            boolean heading = clean.length() < 90 && !clean.matches(".*[:：=|｜\\t].*");
            String foundLot = ConsistencyEngine.lot(clean);
            if (heading && (historical(clean) || clean.matches(".*(?:业绩情况|业绩证明|业绩材料|合同复印件|类似业绩|合同协议书).*"))) scope = "HISTORICAL_PROJECT";
            else if (heading && clean.matches(".*(?:投标函|投标人基本情况|本项目人员|拟投入.*人员|本项目报价|开标一览表|投标报价表|授权委托书|项目管理机构|人员配置表|法定代表人身份证明|本项目汇总|项目总计|全部标段).*")) {
                scope = "CURRENT_PROJECT"; revision = false;
                if (cn.bidcheck.compat.Java8Api.isBlank(foundLot) && clean.matches(".*(?:本项目汇总|项目总计|全部标段).*")) lot = "";
                else if (cn.bidcheck.compat.Java8Api.isBlank(foundLot) && !cn.bidcheck.compat.Java8Api.isBlank(lot)) lot = "UNRESOLVED";
            }
            else if (heading && clean.contains("合同")) scope = "UNRESOLVED";
            if (heading && ConsistencyEngine.revision(clean)) revision = true;
            if (heading && !cn.bidcheck.compat.Java8Api.isBlank(foundLot)) lot = foundLot;
        }
    }
}
