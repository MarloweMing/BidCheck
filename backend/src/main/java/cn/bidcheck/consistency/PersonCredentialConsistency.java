package cn.bidcheck.consistency;

import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.document.TableRelationIndex;
import cn.bidcheck.document.PersonCredentialFacts.Occurrence;
import cn.bidcheck.document.PersonCredentialFacts.Result;
import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.Pattern;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

/** Whole-document, source-preserving comparisons. A source record is never an inferred person. */
public final class PersonCredentialConsistency {
    /** Frozen legacy contract for OCR preparation/cached extraction; use VERSION for new consistency runs. */
    public static final String PREPARATION_VERSION = "person-credential-consistency-2-information-policy";
    public static final String VERSION = "person-credential-consistency-3-table-relations";
    private static final int MAX_PAIRS = 200000;
    private static final Set<String> NUMBERS = Set.of("idNumber","drivingLicenseNumber","drivingFileNumber","passportNumber","certificateNumber");
    private static final Set<String> STABLE = Set.of("personName","gender","birthDate","ethnicGroup");
    private static final Set<String> CHANGEABLE = Set.of("validFrom","validUntil","validPeriod","vehicleClass","address","issuingAuthority","nationality","firstIssueDate","certificateLevel");
    private static final Set<String> DATES=Set.of("birthDate","validFrom","validUntil","firstIssueDate");
    private static final Pattern MASK = Pattern.compile("[＊*●•×_＿?？]|(?i)未知|不详|未识别|待填|缺失");
    private static final Map<String,String> LABELS = labels();
    public static boolean ownedField(String name) { return LABELS.keySet().stream().map(PersonCredentialConsistency::field).anyMatch(name::equals); }

    /** Extraction ambiguity is retained for transparency; only operational coverage failures require action. */
    public static boolean issueRequiresReview(Map<String,Object> issue) {
        if(issue==null)return false;
        if(issue.get("requiresReview") instanceof Boolean value)return value;
        return switch(Objects.toString(issue.get("code"),"")) {
            case "SOURCE_PAGE_MISSING","SOURCE_NOT_BOUND","SOURCE_NOT_VERIFIED","SOURCE_INCOMPLETE",
                    "PAGE_NOT_SCANNED","PAGE_SOURCE_INCOMPLETE","PAGE_FACTS_NOT_PERSISTED","PAGE_FACT_SOURCE_REJECTED",
                    "PERSON_PAGE_LIMIT","PERSON_RECORD_LIMIT","COMPARISON_BUDGET","DOCUMENT_INCOMPLETE" -> true;
            default -> false;
        };
    }

    /** Do not infer actionability from historical PARTIAL/pagesUnparsed: those also included harmless extraction gaps. */
    public static boolean requiresReview(Map<String,Object> coverage) {
        if(coverage==null)return false;
        if(coverage.get("requiresReview") instanceof Boolean value)return value;
        return maps(coverage.get("issues")).stream().anyMatch(PersonCredentialConsistency::issueRequiresReview)
                ||Boolean.TRUE.equals(coverage.get("comparisonsTruncated"))
                ||coverage.get("sourceRejected") instanceof Number count&&count.longValue()>0;
    }

    public Map<String,Object> scan(Document document) {
        List<Result> results = new ArrayList<>();
        for (Page page : pages(document)) { interrupted(); results.add(PersonCredentialFacts.analyzeForConsistency(document,page)); }
        return scan(document,results);
    }

    /** Compare separate volumes without ever changing an evidence document ID or page number. */
    public Map<String,Object> scan(List<Document> documents) {
        List<DocumentResults> inputs=new ArrayList<>();
        for(Document document:documents==null?List.<Document>of():documents) {
            List<Result> results=new ArrayList<>();
            for(Page page:pages(document)) { interrupted();results.add(PersonCredentialFacts.analyzeForConsistency(document,page)); }
            inputs.add(new DocumentResults(document,results));
        }
        return scanDocuments(inputs);
    }

    /** Cached extraction is accepted only while every occurrence still points to the supplied source. */
    public Map<String,Object> scan(Document document,List<Result> results) {
        Objects.requireNonNull(document,"document");
        return scanDocuments(List.of(new DocumentResults(document,results)));
    }

    private Map<String,Object> scanDocuments(List<DocumentResults> inputs) {
        Map<String,Page> sourcePages = new LinkedHashMap<>();
        Map<String,Document> sourceDocuments=new LinkedHashMap<>();
        for(DocumentResults input:inputs) for(Page page:pages(input.document)) {
            String location=location(input.document,page.number(),page.locationType());sourcePages.put(location,page);sourceDocuments.put(location,input.document);
        }
        Set<String> visited = new LinkedHashSet<>(), candidates = new LinkedHashSet<>(), incomplete = new LinkedHashSet<>(), extracted = new LinkedHashSet<>();
        List<Map<String,Object>> issues = new ArrayList<>(), occurrences = new ArrayList<>(), extractionNoise = new ArrayList<>();
        Map<String,Record> records = new LinkedHashMap<>();
        Set<String> occurrenceIds = new HashSet<>();
        int sourceRejected=0;
        for(DocumentResults input:inputs) for(Result result:input.results==null?List.<Result>of():input.results) {
            Document document=input.document;
            interrupted();
            String place=location(document,result.page(),result.locationType());
            Page page=sourcePages.get(place);
            if(page==null) { sourceRejected++; issue(issues,document,result.page(),result.locationType(),"SOURCE_PAGE_MISSING","缓存记录的来源页已不在当前文档中"); continue; }
            visited.add(place);
            if(result.candidate()) candidates.add(place);
            if(!result.complete()&&result.candidate()) incomplete.add(place);
            for(Map<String,Object> problem:result.issues()) {
                String code=Objects.toString(problem.get("code"),"EXTRACTION_INCOMPLETE");
                if(code.equals("PERSON_EXTRACTION_NOISE_REJECTED")) {
                    if(validNoiseReceipt(document,page,problem)) extractionNoise.add(new LinkedHashMap<>(problem));
                    else { sourceRejected++; issue(issues,document,page.number(),page.locationType(),"SOURCE_NOT_VERIFIED","排除字段的来源记录未通过验证，需检查提取过程"); }
                    continue;
                }
                issue(issues,document,page.number(),page.locationType(),safeCode(code),issueMessage(code));
            }
            for(Occurrence occurrence:result.occurrences()) {
                if(!validSource(document,page,occurrence)) {
                    sourceRejected++; incomplete.add(place);
                    issue(issues,document,page.number(),page.locationType(),"SOURCE_NOT_VERIFIED","字段未通过当前页原文验证，未用于比较");
                    continue;
                }
                if(!occurrenceIds.add(occurrence.id())) continue;
                extracted.add(place);
                Record record=records.computeIfAbsent(occurrence.recordKey(),Record::new);
                record.items.add(occurrence);
                occurrences.add(occurrence(document,page,occurrence));
            }
        }
        for(DocumentResults input:inputs) for(Page page:pages(input.document)) {
            String place=location(input.document,page.number(),page.locationType());
            if(!visited.contains(place)){incomplete.add(place);issue(issues,input.document,page.number(),page.locationType(),"PAGE_NOT_SCANNED","该位置尚未执行人员证件字段扫描，不能视为已覆盖");}
            if(incomplete(page)){incomplete.add(place);issue(issues,input.document,page.number(),page.locationType(),"PAGE_SOURCE_INCOMPLETE","该位置文字或 OCR 识别尚不完整，需补充识别或回看原页");}
        }
        Map<String,IssueGroup> grouped=new LinkedHashMap<>();
        for(Record record:records.values()) {
            for(Occurrence occurrence:record.items) {
                if((NUMBERS.contains(occurrence.field())||DATES.contains(occurrence.field()))&&!usable(occurrence)) {
                    addGroup(grouped,occurrence.field(),"EVIDENCE_INSUFFICIENT","INCOMPLETE_RECORD",record.key,
                            label(occurrence.field())+"存在遮挡、缺位或格式不完整，不能据此确认一致或认定冲突。",record);
                }
            }
            if(record.name().isBlank()&&record.strongKeys().isEmpty())
                addGroup(grouped,"identityCoverage","EVIDENCE_INSUFFICIENT","UNRESOLVED",record.key,
                        "本处证件缺少可核对的持有人或完整身份标识，尚不能可靠关联到其他页面。",record);
        }
        Map<String,List<Record>> buckets=new LinkedHashMap<>();
        for(Record record:records.values()) for(String key:record.strongKeys()) buckets.computeIfAbsent("IDENTIFIER:"+key,k->new ArrayList<>()).add(record);
        for(Record record:records.values()) if(!record.name().isBlank()) buckets.computeIfAbsent("NAME:"+record.name(),k->new ArrayList<>()).add(record);
        Set<String> pairs=new HashSet<>(); boolean comparisonsTruncated=false;
        outer: for(Map.Entry<String,List<Record>> bucket:buckets.entrySet()) {
            List<Record> members=bucket.getValue();
            for(int i=0;i<members.size();i++) for(int j=i+1;j<members.size();j++) {
                interrupted(); Record left=members.get(i),right=members.get(j);
                String pair=left.key.compareTo(right.key)<0?left.key+"\n"+right.key:right.key+"\n"+left.key;
                if(!pairs.add(pair)) continue;
                if(pairs.size()>MAX_PAIRS) { comparisonsTruncated=true; break outer; }
                compare(left,right,bucket.getKey(),grouped);
            }
        }
        if(comparisonsTruncated) issue(issues,0,"COMPARISON_BUDGET","同名或同证号关联数量达到本轮比较上限，尚未完成的关联需继续核对");
        boolean documentIncomplete=inputs.stream().map(DocumentResults::document).anyMatch(document->document.warnings()!=null&&document.warnings().stream().anyMatch(PersonCredentialConsistency::unread));
        if(documentIncomplete) issue(issues,0,"DOCUMENT_INCOMPLETE","文档仍有未完成的识别范围，人员与证件覆盖尚不完整");
        List<Map<String,Object>> allGroups=grouped.values().stream().map(IssueGroup::report).toList();
        List<Map<String,Object>> groups=allGroups.stream().filter(g->Set.of("CONFLICT","SUSPECTED").contains(g.get("status"))).toList();
        List<Map<String,Object>> informationalGroups=allGroups.stream().filter(g->"EVIDENCE_INSUFFICIENT".equals(g.get("status"))).toList();
        long unresolvedGroups=informationalGroups.size();
        String status=!incomplete.isEmpty()||documentIncomplete||comparisonsTruncated||sourceRejected>0||unresolvedGroups>0?"PARTIAL":occurrences.isEmpty()?"NO_FACTS":"TEXT_SCANNED";
        List<Map<String,Object>> actionableIssues=issues.stream().filter(PersonCredentialConsistency::issueRequiresReview).toList();
        List<Map<String,Object>> informationalIssues=issues.stream().filter(issue->!issueRequiresReview(issue)).toList();
        boolean requiresReview=!actionableIssues.isEmpty();
        long actionablePages=issuePages(actionableIssues),informationalPages=issuePages(informationalIssues);
        List<Integer> missing=candidates.stream().filter(p->!extracted.contains(p)).map(p->sourcePages.get(p).number()).distinct().sorted().toList();
        List<Map<String,Object>> missingLocations=candidates.stream().filter(p->!extracted.contains(p)).map(p->sourceLocation(sourceDocuments.get(p),sourcePages.get(p))).toList();
        Map<String,Object> coverage=values("personCredentialVersion",VERSION,"status",status,
                "pagesTotal",sourcePages.size(),"pagesScanned",visited.size(),"pagesUnparsed",incomplete.size(),
                "candidatePages",candidates.size(),"extractedPages",extracted.size(),"incompletePages",incomplete.size(),
                "missingCandidatePages",missing,"missingCandidateLocations",missingLocations,"issues",issues,"sourceRejected",sourceRejected,
                "requiresReview",requiresReview,"attentionStatus",requiresReview?"ACTION_REQUIRED":!informationalIssues.isEmpty()||!informationalGroups.isEmpty()?"INFORMATION_ONLY":"CLEAR",
                "actionableIssues",actionableIssues,"informationalIssues",informationalIssues,
                "actionableIssueCount",actionableIssues.size(),"informationalIssueCount",informationalIssues.size(),
                "actionablePages",actionablePages,"informationalPages",informationalPages,"informationalGroupsCount",informationalGroups.size(),
                "occurrencesCount",occurrences.size(),"comparableOccurrencesCount",occurrences.stream().filter(o->!"INVALID".equals(o.get("comparability"))).count(),
                "comparisonGroupsCount",groups.size(),"comparedRecordPairs",Math.min(pairs.size(),MAX_PAIRS),"comparisonsTruncated",comparisonsTruncated,
                "occurrencesTruncated",false,"identityResolvedGroups",groups.stream().filter(g->"CORROBORATED".equals(g.get("identityResolution"))).count(),
                "fields",LABELS.keySet().stream().filter(k->!k.equals("identityCoverage")).map(PersonCredentialConsistency::field).toList(),
                "observedFields",occurrences.stream().map(o->o.get("field")).distinct().toList());
        coverage.put("extractionNoiseRejectedCount",extractionNoise.size());
        coverage.put("extractionNoiseRejections",extractionNoise);
        coverage.put("consistencyFilterVersion",PersonCredentialFacts.CONSISTENCY_FILTER_VERSION);
        coverage.put("categories",List.of(values("id","personnel","title","人员与证件","capability","LOCAL_RULES","status",requiresReview?"NEEDS_CONTEXT":occurrences.isEmpty()?"NO_FACTS":"OBSERVED","occurrences",occurrences.size(),"groups",groups.size(),"limitations",List.of("仅比较已识别且能回到原文的字段；同名并不直接认定同一人。"))));
        Map<String,Object> report=values("version",VERSION,"catalogVersion",ConsistencyCatalog.VERSION,
                "overall",requiresReview||!groups.isEmpty()?"REVIEW_REQUIRED":"NO_ACTIONABLE_FINDINGS","occurrences",occurrences,"groups",groups,"informationalGroups",informationalGroups,"coverage",coverage,
                "limitations",List.of("对全文所有已识别的人员及证件记录比较，不限制两处材料之间的页距；每处原文独立保存。",
                        "同名仅建立疑似关联，完整证号或独立的明确岗位线索用于加强身份核对；不同种类证件的号码分别比较。",
                        "有效期、准驾车型、地址及签发机关的变化可能来自换证或更新，均保留待复核，不自动判定不合格。",
                        "字段缺失、号码遮挡、归属不明及证件类型不明确作为提取说明保留，不单独生成检查问题；明确可比的其他字段继续检查。",
                        "未识别页面、来源核验失败和处理上限仍单独提示；未发现差异不代表全部原文事实均已提取。"));
        List<Document> documents=inputs.stream().map(DocumentResults::document).toList();
        report.put("documentIds",documents.stream().map(Document::id).toList());
        report.put("sourceManifest",documents.stream().map(doc->values("documentId",doc.id(),"documentName",doc.name(),"documentHash",doc.sha256(),"documentVersion",doc.version())).toList());
        if(documents.size()==1) { Document document=documents.get(0);report.put("documentId",document.id());report.put("documentHash",document.sha256());report.put("documentVersion",document.version()); }
        return report;
    }

    private static void compare(Record left,Record right,String bucket,Map<String,IssueGroup> groups) {
        Set<String> common=new LinkedHashSet<>(left.fields()); common.retainAll(right.fields());
        for(String field:common) {
            if(!NUMBERS.contains(field)&&!STABLE.contains(field)&&!CHANGEABLE.contains(field)) continue;
            if(!compatibleTypes(left,right,field)) continue;
            String key=left.name().equals(right.name())&&!left.name().isBlank()?"NAME:"+left.name():bucket;
            List<Occurrence> allA=left.field(field),allB=right.field(field);
            boolean bad=allA.stream().anyMatch(o->!usable(o))||allB.stream().anyMatch(o->!usable(o));
            if(bad) {
                String reason=field.equals("personName")?"姓名字段含多人、遮挡或无法明确归属的内容，已保留原文，不据此认定姓名冲突。":label(field)+"存在遮挡、不完整或无法归一的取值，已保留原文，该取值不参与差异判定。";
                addGroup(groups,field,"EVIDENCE_INSUFFICIENT",field.equals("personName")?"INCOMPLETE_IDENTITY":"INCOMPLETE_RECORD",key,reason,left,right);
            }
            // One unusable value must not suppress valid comparisons on this or other fields.
            List<Occurrence> a=allA.stream().filter(PersonCredentialConsistency::usable).toList(),b=allB.stream().filter(PersonCredentialConsistency::usable).toList();
            boolean difference=a.stream().flatMap(x->b.stream().map(y->!comparisonValue(x).equals(comparisonValue(y)))).anyMatch(Boolean::booleanValue);
            if(!difference)continue;
            String resolution=corroborated(left,right,field)?"CORROBORATED":"NAME_ONLY";
            String status,reason;
            if(CHANGEABLE.contains(field)) {
                status="SUSPECTED"; reason=label(field)+"在关联材料中不同，可能涉及续换发、增驾或信息更新，需核对签发时点和有效版本。";
            } else {
                boolean ocr=left.items.stream().anyMatch(o->o.evidence().confidence()<0.9)||right.items.stream().anyMatch(o->o.evidence().confidence()<0.9);
                status="CORROBORATED".equals(resolution)&&!ocr?"CONFLICT":"SUSPECTED";
                reason=label(field)+"在不同来源记录中出现不同值。"+("CORROBORATED".equals(resolution)?"已找到独立的共同身份线索，请逐处核对原件。":"当前关联主要依赖同名线索，需先确认是否同一人，不能仅据姓名认定冲突。");
                if(ocr) reason+=" 部分字段来自 OCR 转录，需回看对应原页。";
            }
            addGroup(groups,field,status,resolution,key,reason,left,right);
        }
    }

    private static boolean compatibleTypes(Record a,Record b,String field) {
        if(STABLE.contains(field)||field.equals("idNumber")) return true;
        // Canonical explicit labels keep their meaning on a roster as well as on the credential itself.
        // An untyped generic "证号" never reaches these fields from the extractor.
        if(Set.of("drivingLicenseNumber","drivingFileNumber","vehicleClass","passportNumber").contains(field)) return true;
        if(field.equals("certificateNumber")||a.hasType("PERSON_CERTIFICATE")||b.hasType("PERSON_CERTIFICATE"))
            return !a.certificateName().isBlank()&&a.certificateName().equals(b.certificateName());
        return !Collections.disjoint(a.types(),b.types())&&!a.hasType("UNKNOWN")&&!b.hasType("UNKNOWN");
    }

    private static boolean corroborated(Record a,Record b,String comparedField) {
        for(String number:NUMBERS) {
            if(number.equals(comparedField)||Set.of("idNumber","drivingLicenseNumber").contains(number)&&Set.of("idNumber","drivingLicenseNumber").contains(comparedField)) continue;
            if(!compatibleTypes(a,b,number)) continue;
            for(Occurrence x:a.field(number)) for(Occurrence y:b.field(number))
                if(usable(x)&&usable(y)&&normalize(x.value()).equals(normalize(y.value()))) return true;
        }
        if(a.name().isBlank()||!a.name().equals(b.name())) return false;
        Set<String> roles=new HashSet<>(a.values("role")); roles.retainAll(b.values("role"));
        return roles.stream().anyMatch(r->r.matches("项目负责人|项目经理|技术负责人|法定代表人|授权代表|被授权人"));
    }

    private static boolean validNoiseReceipt(Document doc,Page page,Map<String,Object> receipt) {
        Evidence e=receipt.get("evidence") instanceof Evidence value?value:null;
        if(!(receipt.get("start") instanceof Number start)||!(receipt.get("end") instanceof Number end))return false;
        int from=start.intValue(),to=end.intValue();String text=Objects.toString(page.text(),"");
        return doc.id().equals(receipt.get("documentId"))&&Objects.equals(doc.sha256(),receipt.get("documentHash"))
                &&receipt.get("documentVersion") instanceof Number version&&version.intValue()==doc.version()
                &&e!=null&&doc.id().equals(e.documentId())&&doc.name().equals(e.documentName())&&e.page()==page.number()
                &&Objects.equals(e.locationType(),page.locationType())&&e.quote()!=null&&!e.quote().isBlank()&&text.contains(e.quote())
                &&from>=0&&to>from&&to<=text.length()&&text.substring(from,to).equals(receipt.get("rawValue"))
                &&PersonCredentialFacts.CONSISTENCY_FILTER_VERSION.equals(receipt.get("policyVersion"))
                &&!Objects.toString(receipt.get("oldOccurrenceId"),"").isBlank()&&!Objects.toString(receipt.get("rejectionId"),"").isBlank();
    }

    private static boolean validSource(Document doc,Page page,Occurrence o) {
        if(o==null||o.evidence()==null||o.context()==null||o.id()==null||o.id().isBlank()||o.recordKey()==null||o.recordKey().isBlank()) return false;
        Evidence e=o.evidence(); String text=Objects.toString(page.text(),"");
        return doc.id().equals(e.documentId())&&doc.name().equals(e.documentName())&&page.number()==e.page()&&Objects.equals(page.locationType(),e.locationType())
                &&Objects.equals(doc.sha256(),o.context().get("sourceHash"))&&Integer.toString(doc.version()).equals(o.context().get("sourceVersion"))
                &&PersonCredentialFacts.VERSION.equals(o.context().get("parserVersion"))
                &&e.quote()!=null&&!e.quote().isBlank()&&e.quote().length()<=10000&&text.contains(e.quote())
                &&o.rawValue()!=null&&!o.rawValue().isBlank()&&e.quote().contains(o.rawValue())&&o.field()!=null&&o.field().matches("[a-zA-Z][a-zA-Z0-9]{0,79}");
    }

    private static Map<String,Object> occurrence(Document doc,Page page,Occurrence o) {
        boolean usable=usable(o);
        return values("id",o.id(),"field",field(o.field()),"title",label(o.field()),"fieldLabel",label(o.field()),"categoryId","personnel",
                "subject",o.personName().isBlank()?"持有人待确认":o.personName().substring(0,1)+"**（来源记录）","scope","UNRESOLVED","lot","","basis",credentialLabel(o.credentialType()),"time","","unit","",
                "rawValue",o.rawValue(),"value",o.value(),"comparability",usable?"WEAK":"INVALID","scopeReason","独立来源记录；跨页身份关联由人员证件规则另行核对",
                "revision","UNRESOLVED","extractionMethod",Objects.toString(page.parser(),"").contains("OCR")?"PERSON_CREDENTIAL_OCR_TEXT":"PERSON_CREDENTIAL_SOURCE_TEXT",
                "confidence",o.evidence().confidence(),"evidence",o.evidence(),"contextEvidences",List.of(),"dimensions",Map.of("object","原文位置 "+page.number()),
                "recordKey",o.recordKey(),"credentialType",o.credentialType(),"personCredentialField",o.field(),"personCredentialContext",o.context(),"tableAssociation",tableAssociation(doc,page,o),
                "ruleId",VERSION,"documentHash",doc.sha256(),"documentVersion",doc.version());
    }

    private static Map<String,Object> tableAssociation(Document document,Page page,Occurrence occurrence) {
        Map<String,String> context=occurrence.context();if(!TableRelationIndex.VERSION.equals(context.get("tableRelationVersion")))return Map.of();
        try {
            int start=Integer.parseInt(context.get("tableSourceStart")),end=Integer.parseInt(context.get("tableSourceEnd"));
            if(start<0||end>page.text().length()||start>=end||!page.text().substring(start,end).equals(occurrence.rawValue()))return Map.of();
            TableRelationIndex.Source source=new TableRelationIndex.Source(document.id(),document.version(),document.sha256(),page.number(),page.locationType(),start,end);
            List<Map<String,Object>> headers=new ArrayList<>();
            for(String range:context.getOrDefault("tableHeaderSources","").split(";")) {
                String[] offsets=range.split(":");if(offsets.length!=2)return Map.of();int first=Integer.parseInt(offsets[0]),last=Integer.parseInt(offsets[1]);
                if(first<0||last>page.text().length()||first>=last||last>start)return Map.of();
                headers.add(new TableRelationIndex.Source(document.id(),document.version(),document.sha256(),page.number(),page.locationType(),first,last).asMap());
            }
            return values("version",TableRelationIndex.VERSION,"tableId",context.get("tableId"),"row",Integer.parseInt(context.get("tableRow")),
                    "column",Integer.parseInt(context.get("tableColumn")),"cellId",context.get("tableCellId"),"status",context.get("tableAssociationStatus"),
                    "headerPath",List.of(context.getOrDefault("tableHeaderPath","").split("\n")),"inherited",Boolean.parseBoolean(context.get("tableInherited")),
                    "source",source.asMap(),"headerSources",headers,"geometryAvailable",false);
        }catch(RuntimeException invalid){return Map.of();}
    }

    private static boolean usable(Occurrence o) {
        String raw=Objects.toString(o.rawValue(),""),value=Objects.toString(o.value(),"");
        if(raw.isBlank()||value.isBlank()||MASK.matcher(raw).find()) return false;
        if(o.field().equals("personName"))return "EXPLICIT".equals(o.context().get("personNameStatus"))&&o.personName()!=null&&!o.personName().isBlank();
        if(DATES.contains(o.field()))return o.field().equals("validUntil")&&Set.of("长期","永久","LONGTERM").contains(normalize(value))||date(normalize(value))!=null;
        if(o.field().equals("gender"))return Set.of("男","女").contains(value.strip());
        if(NUMBERS.contains(o.field())) {
            if("true".equalsIgnoreCase(o.context().get("numberMasked"))) return false;
            String numberStatus=o.context().get("numberStatus");
            if(numberStatus!=null&&!numberStatus.equals("VALID")) return false;
            String compact=normalize(value);
            if(o.field().equals("idNumber")) return compact.matches("[0-9]{15}|[0-9]{17}[0-9X]");
            return compact.matches("[A-Z0-9][A-Z0-9()（）./-]{1,79}");
        }
        return true;
    }

    private static void addGroup(Map<String,IssueGroup> out,String field,String status,String resolution,String key,String reason,Record... records) {
        String identity=field+"|"+status+"|"+resolution+"|"+key;
        IssueGroup group=out.computeIfAbsent(identity,k->new IssueGroup(identity,field,status,resolution,reason));
        for(Record record:records) group.records.put(record.key,record);
    }

    private static final class IssueGroup {
        final String identity,field,status,resolution,reason; final Map<String,Record> records=new LinkedHashMap<>();
        IssueGroup(String identity,String field,String status,String resolution,String reason){this.identity=identity;this.field=field;this.status=status;this.resolution=resolution;this.reason=reason;}
        Map<String,Object> report() {
            List<Occurrence> items=records.values().stream().flatMap(r->r.items.stream()).distinct().toList();
            List<Integer> pages=items.stream().map(o->o.evidence().page()).distinct().sorted().toList();
            List<Map<String,Object>> locations=items.stream().map(Occurrence::evidence).map(e->values("documentId",e.documentId(),"documentName",e.documentName(),"page",e.page(),"locationType",e.locationType())).distinct().toList();
            List<String> safeValues=items.stream().filter(o->o.field().equals(field)).map(PersonCredentialConsistency::masked).distinct().toList();
            return values("id","person-issue-"+id(identity),"categoryId","personnel","field",field(field),"fieldLabel",label(field),
                    "title",label(field)+(status.equals("EVIDENCE_INSUFFICIENT")?"提取说明":status.equals("CONFLICT")?"存在不一致":"需核对差异"),
                    "status",status,"severity",status.equals("EVIDENCE_INSUFFICIENT")?"INFO":"WARNING","requiresReview",!status.equals("EVIDENCE_INSUFFICIENT"),
                    "reason",reason,"identityResolution",resolution,"subject",status.equals("EVIDENCE_INSUFFICIENT")?"原文来源记录":"来源记录待核对","scope","UNRESOLVED","lot","","basis","",
                    "relationType",status.equals("EVIDENCE_INSUFFICIENT")?"EVIDENCE":"VALUE","ruleId",VERSION+":"+field,
                    "comparisonContext",values("subject","关联人员证件","identityResolution",resolution.equals("CORROBORATED")?"有独立共同线索":resolution.equals("NAME_ONLY")?"同名疑似关联":"身份或取值依据不足"),
                    "occurrenceIds",items.stream().map(Occurrence::id).distinct().toList(),"evidences",items.stream().map(Occurrence::evidence).distinct().toList(),
                    "pages",pages,"sourcePages",pages,"sourceLocations",locations,"values",safeValues,"businessDecision","UNDETERMINED");
        }
    }

    private static final class Record {
        final String key; final List<Occurrence> items=new ArrayList<>();
        Record(String key){this.key=key;}
        List<Occurrence> field(String field){return items.stream().filter(o->field.equals(o.field())).toList();}
        Set<String> fields(){Set<String> out=new LinkedHashSet<>();items.forEach(o->out.add(o.field()));return out;}
        Set<String> values(String field){Set<String> out=new LinkedHashSet<>();field(field).stream().filter(PersonCredentialConsistency::usable).forEach(o->out.add(normalize(o.value())));return out;}
        String name(){if(items.stream().anyMatch(o->!"EXPLICIT".equals(o.context().get("personNameStatus"))))return "";Set<String> names=values("personName");return names.size()==1?names.iterator().next():"";}
        String certificateName(){Set<String> names=values("certificateName");if(names.isEmpty())items.stream().map(o->o.context().getOrDefault("credentialName","")).filter(s->!s.isBlank()).forEach(s->names.add(normalize(s)));return names.size()==1?names.iterator().next():"";}
        Set<String> types(){Set<String> out=new LinkedHashSet<>();items.forEach(o->out.add(o.credentialType()));return out;}
        boolean hasType(String type){return types().contains(type);}
        Set<String> strongKeys(){Set<String> out=new LinkedHashSet<>();for(String number:NUMBERS) for(String value:values(number)) {
            if(values(number).size()!=1)continue;
            if(number.equals("certificateNumber")&&certificateName().isBlank()) continue;
            out.add(number+":"+(number.equals("certificateNumber")?certificateName()+":":"")+value);
        }return out;}
    }

    private static String field(String raw){return switch(raw){case "personName"->"credentialPersonName";case "role"->"credentialPersonRole";case "gender"->"personGender";case "birthDate"->"personBirthDate";case "address"->"credentialAddress";case "phone"->"credentialPhone";case "employer"->"credentialEmployer";case "issuingAuthority"->"credentialIssuingAuthority";case "validFrom"->"credentialValidFrom";case "validUntil"->"credentialValidUntil";case "validPeriod"->"credentialValidPeriod";case "certificateName"->"personCertificateName";case "certificateNumber"->"personCertificateNumber";case "identityCoverage"->"personIdentityCoverage";default->raw;};}
    private static String label(String field){return LABELS.getOrDefault(field,"人员证件信息");}
    private static String credentialLabel(String type){return switch(type){case "ID_CARD"->"身份证";case "DRIVING_LICENSE"->"驾驶证";case "PASSPORT"->"护照";case "PERSON_CERTIFICATE"->"人员资格证书";case "PERSON_ROSTER"->"人员名单";default->"证件种类待确认";};}
    private static Map<String,String> labels(){Map<String,String> labels=new LinkedHashMap<>();String[][] pairs={{"personName","证件持有人姓名"},{"idNumber","身份证号码"},{"drivingLicenseNumber","驾驶证号码"},{"drivingFileNumber","驾驶证档案编号"},{"archiveNumber","证件档案编号"},{"passportNumber","护照号码"},{"certificateName","人员证书名称"},{"certificateNumber","人员证书编号"},{"gender","人员性别"},{"birthDate","出生日期"},{"vehicleClass","准驾车型"},{"validFrom","证件有效期起始日"},{"validUntil","证件有效期截止日"},{"validPeriod","证件有效期限"},{"address","证件住址"},{"issuingAuthority","证件签发机关"},{"role","人员岗位"},{"ethnicGroup","民族"},{"nationality","国籍"},{"firstIssueDate","初次领证日期"},{"certificateLevel","人员证书等级"},{"phone","联系电话"},{"employer","工作单位"},{"socialSecurityNumber","社会保障号码"},{"professionalTitle","人员职称"},{"age","年龄"},{"education","学历"},{"professionalMajor","所学专业"},{"graduationSchool","毕业院校"},{"classification","材料分类"},{"identityCoverage","人员身份关联"}};for(String[] pair:pairs)labels.put(pair[0],pair[1]);return Collections.unmodifiableMap(labels);}
    private static String masked(Occurrence o){String value=Objects.toString(o.value(),"");if(NUMBERS.contains(o.field()))return value.length()>4?"尾号 "+value.substring(value.length()-4):"号码未完整识别";if(o.field().equals("personName"))return value.isBlank()?"姓名未完整识别":value.substring(0,1)+"**";return "不同来源取值（详见原文）";}
    private static String normalize(String value){return Objects.toString(value,"").replaceAll("[\\s\\u3000]+","").toUpperCase(Locale.ROOT);}
    private static String comparisonValue(Occurrence value){String normalized=normalize(value.value());java.time.LocalDate date=DATES.contains(value.field())?date(normalized):null;return date==null?normalized:date.toString();}
    private static java.time.LocalDate date(String value){var matcher=Pattern.compile("^(\\d{4})[年./-](\\d{1,2})[月./-](\\d{1,2})日?$").matcher(value);if(matcher.matches())try{return java.time.LocalDate.of(Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2)),Integer.parseInt(matcher.group(3)));}catch(RuntimeException ignored){}return null;}
    private static List<Page> pages(Document doc){return doc.pages()==null?List.of():doc.pages();}
    private record DocumentResults(Document document,List<Result> results) {}
    private static String location(Document document,int page,String type){return document.id()+"|"+Objects.toString(type,"")+":"+page;}
    private static boolean incomplete(Page p){return p.text()==null||p.text().isBlank()||unread(p.warning())||unread(p.parser())||p.layout()!=null&&"PARTIAL".equals(p.layout().status());}
    private static boolean unread(String value){return value!=null&&value.matches("(?s).*(?:OCR_DEFERRED|OCR未完成|未识别|未解析|解析失败|识别失败|图片未|图像未|无文本|未处理|预算|DEFERRED|FAILED|PENDING).*");}
    private static void issue(List<Map<String,Object>> issues,int page,String code,String message){Map<String,Object> item=values("page",page,"code",code,"message",message);classifyIssue(item);if(!issues.contains(item))issues.add(item);}
    private static void issue(List<Map<String,Object>> issues,Document document,int page,String type,String code,String message){Map<String,Object> item=values("documentId",document.id(),"documentName",document.name(),"page",page,"locationType",type,"code",code,"message",message);classifyIssue(item);if(!issues.contains(item))issues.add(item);}
    private static void classifyIssue(Map<String,Object> issue){boolean required=issueRequiresReview(issue);issue.put("requiresReview",required);issue.put("severity",required?"WARNING":"INFO");}
    private static long issuePages(List<Map<String,Object>> issues){return issues.stream().filter(i->i.get("page") instanceof Number n&&n.intValue()>0).map(i->Objects.toString(i.get("documentId"),"")+"|"+Objects.toString(i.get("locationType"),"")+":"+i.get("page")).distinct().count();}
    private static Map<String,Object> sourceLocation(Document document,Page page){return values("documentId",document.id(),"documentName",document.name(),"page",page.number(),"locationType",page.locationType());}
    private static String issueMessage(String code){return switch(code){
        case "PERSON_PAGE_LIMIT","PERSON_RECORD_LIMIT"->"人员材料超过本轮结构解析范围，部分字段尚未完成提取";
        case "SOURCE_NOT_BOUND","SOURCE_NOT_VERIFIED"->"字段未通过当前文档版本的原文定位验证，未用于比较";
        case "PAGE_FACT_SOURCE_REJECTED"->"本页部分人员证件字段未通过事实库来源核验，已排除比较，需核对原页";
        case "SOURCE_INCOMPLETE","PAGE_FACTS_NOT_PERSISTED"->"该位置人员证件事实尚未完整识别或保存，不能视为已覆盖";
        case "PERSON_TABLE_INCOMPLETE","PERSON_TABLE_GRID","PERSON_TABLE_AMBIGUOUS"->"人员表格未闭合或行列对应关系不明确，未猜测字段归属";
        case "PERSON_FIELDS_UNRESOLVED"->"已发现人员或证件材料，但未提取到能定位的明确字段；保留提取说明";
        case "PERSON_RECORD_AMBIGUOUS"->"同一记录内出现多个不同同类证号，尚不能确定人员归属";
        case "PERSON_NAME_MISSING"->"该人员或证件记录未明确姓名，已提取的其他字段保留";
        case "PERSON_NAME_AMBIGUOUS"->"姓名包含多人、掩码或不明确内容，不能直接作为身份关联依据";
        case "CREDENTIAL_TYPE_UNKNOWN"->"证件种类尚未明确，未把不同种类的号码混同比较";
        case "CREDENTIAL_NUMBER_MISSING"->"证件记录未提取到明确号码，已提取的其他字段继续参与检查";
        case "CERTIFICATE_NAME_MISSING"->"人员证书未明确名称或类别，不能与其他种类证书混同比较";
        case "CREDENTIAL_NUMBER_MASKED"->"证件号码含掩码，不能据此确认完整号码一致";
        case "CREDENTIAL_NUMBER_FORMAT"->"证件号码格式或位数不完整，已保留原文，不单独判定为问题";
        case "PERSON_FIELD_EMPTY"->"原文存在空白字段，已保留其他明确字段";
        case "PERSON_FIELD_UNRESOLVED","PERSON_FIELD_LABEL_UNSUPPORTED"->"部分字段格式或标签尚未明确，已保留其他明确字段";
        case "PERSON_TABLE_SPAN_UNSUPPORTED","PERSON_TABLE_CELL_UNSUPPORTED","PERSON_TABLE_COLUMNS_MISMATCH","PERSON_TABLE_DUPLICATE_HEADER"->"表格含合并单元格或归属不明的内容，已保留原文及能明确归属的字段";
        case "PERSON_TABLE_HEADER_UNRESOLVED","PERSON_TABLE_FIELDS_UNSUPPORTED","PERSON_TABLE_NO_DATA"->"部分表头或列含义未明确，已保留原文及明确字段";
        default->"人员证件提取说明（"+safeCode(code)+"）";
    };}
    private static String safeCode(String code){return code.matches("[A-Z0-9_]{1,80}")?code:"EXTRACTION_INCOMPLETE";}
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("人员证件比较已取消");}
}
