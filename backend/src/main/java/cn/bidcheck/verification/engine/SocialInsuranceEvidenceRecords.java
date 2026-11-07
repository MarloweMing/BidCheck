package cn.bidcheck.verification.engine;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/**
 * Reads payment records from the frozen original HTML table transcription. It never treats a
 * query period as payment or imports an employer from proximity alone. A selected-page report
 * may use its adjacent cover only inside the same explicit personnel attachment, with matching
 * report title and printed pagination; every structural witness is retained for re-admission.
 * Values and row/column membership are re-derived at admission; serialized proof is audit only.
 */
final class SocialInsuranceEvidenceRecords {
    static final String VERSION="social-payment-table-records-3-paginated-attachment";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SocialInsuranceEvidenceRecords.class);
    private static final Pattern RANGE=Pattern.compile("chars:(\\d+)-(\\d+)");
    private static final Pattern TABLE=Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table\\s*>");
    private static final Pattern ROW=Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern CELL=Pattern.compile("(?is)<(td|th)\\b([^>]*)>(.*?)</\\1\\s*>");
    private static final Pattern MONTH=Pattern.compile("^(20\\d{2})\\s*(?:年|[-./])\\s*(0?[1-9]|1[0-2])\\s*月?$");
    private static final Pattern EMPLOYER=Pattern.compile("(?:缴费单位|参保单位|单位名称)\\s*[:：]\\s*([^\\r\\n<>|]{2,100})");
    private SocialInsuranceEvidenceRecords(){}

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Field{
        private final String text;
        private final int start;
        private final int end;
        private final String pageKey;
        @com.fasterxml.jackson.annotation.JsonCreator
        Field(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("pageKey") String pageKey) { this.text = text; this.start = start; this.end = end; this.pageKey = pageKey; }
        public String text() { return text; }
        public int start() { return start; }
        public int end() { return end; }
        public String pageKey() { return pageKey; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Field that = (Field) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.pageKey, that.pageKey); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.pageKey); return hash; }
        @Override public String toString() { return "Field[" + "text=" + text + ", start=" + start + ", end=" + end + ", pageKey=" + pageKey + "]"; }
Field(String text,int start,int end){this(text,start,end,"");}}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class ReportPage{
        private final Original original;
        private final String reportKey;
        private final Field reportId;
        private final Field employer;
        private final Field title;
        private final Field pagination;
        private final int printedPage;
        private final int totalPages;
        @com.fasterxml.jackson.annotation.JsonCreator
        private ReportPage(@com.fasterxml.jackson.annotation.JsonProperty("original") Original original, @com.fasterxml.jackson.annotation.JsonProperty("reportKey") String reportKey, @com.fasterxml.jackson.annotation.JsonProperty("reportId") Field reportId, @com.fasterxml.jackson.annotation.JsonProperty("employer") Field employer, @com.fasterxml.jackson.annotation.JsonProperty("title") Field title, @com.fasterxml.jackson.annotation.JsonProperty("pagination") Field pagination, @com.fasterxml.jackson.annotation.JsonProperty("printedPage") int printedPage, @com.fasterxml.jackson.annotation.JsonProperty("totalPages") int totalPages) { this.original = original; this.reportKey = reportKey; this.reportId = reportId; this.employer = employer; this.title = title; this.pagination = pagination; this.printedPage = printedPage; this.totalPages = totalPages; }
        public Original original() { return original; }
        public String reportKey() { return reportKey; }
        public Field reportId() { return reportId; }
        public Field employer() { return employer; }
        public Field title() { return title; }
        public Field pagination() { return pagination; }
        public int printedPage() { return printedPage; }
        public int totalPages() { return totalPages; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ReportPage that = (ReportPage) other; return java.util.Objects.equals(this.original, that.original) && java.util.Objects.equals(this.reportKey, that.reportKey) && java.util.Objects.equals(this.reportId, that.reportId) && java.util.Objects.equals(this.employer, that.employer) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.pagination, that.pagination) && java.util.Objects.equals(this.printedPage, that.printedPage) && java.util.Objects.equals(this.totalPages, that.totalPages); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.original); hash = 31 * hash + java.util.Objects.hashCode(this.reportKey); hash = 31 * hash + java.util.Objects.hashCode(this.reportId); hash = 31 * hash + java.util.Objects.hashCode(this.employer); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.pagination); hash = 31 * hash + java.util.Objects.hashCode(this.printedPage); hash = 31 * hash + java.util.Objects.hashCode(this.totalPages); return hash; }
        @Override public String toString() { return "ReportPage[" + "original=" + original + ", reportKey=" + reportKey + ", reportId=" + reportId + ", employer=" + employer + ", title=" + title + ", pagination=" + pagination + ", printedPage=" + printedPage + ", totalPages=" + totalPages + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cell{
        private final Field field;
        private final int row;
        private final int column;
        private final int rowSpan;
        private final int columnSpan;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cell(@com.fasterxml.jackson.annotation.JsonProperty("field") Field field, @com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("column") int column, @com.fasterxml.jackson.annotation.JsonProperty("rowSpan") int rowSpan, @com.fasterxml.jackson.annotation.JsonProperty("columnSpan") int columnSpan) { this.field = field; this.row = row; this.column = column; this.rowSpan = rowSpan; this.columnSpan = columnSpan; }
        public Field field() { return field; }
        public int row() { return row; }
        public int column() { return column; }
        public int rowSpan() { return rowSpan; }
        public int columnSpan() { return columnSpan; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.column, that.column) && java.util.Objects.equals(this.rowSpan, that.rowSpan) && java.util.Objects.equals(this.columnSpan, that.columnSpan); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.column); hash = 31 * hash + java.util.Objects.hashCode(this.rowSpan); hash = 31 * hash + java.util.Objects.hashCode(this.columnSpan); return hash; }
        @Override public String toString() { return "Cell[" + "field=" + field + ", row=" + row + ", column=" + column + ", rowSpan=" + rowSpan + ", columnSpan=" + columnSpan + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Original{
        private final String text;
        private final int start;
        private final List<Source> sources;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Original(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Source> sources) { this.text = text; this.start = start; this.sources = sources; }
        public String text() { return text; }
        public int start() { return start; }
        public List<Source> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Original that = (Original) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "Original[" + "text=" + text + ", start=" + start + ", sources=" + sources + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Record{
        private final String id;
        private final String person;
        private final String employer;
        private final String identifierFingerprint;
        private final List<String> months;
        private final String insurance;
        private final Map<String,Field> fields;
        private final List<Source> sources;
        private final String derivation;
        private final String scope;
        @com.fasterxml.jackson.annotation.JsonCreator
        Record(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("person") String person, @com.fasterxml.jackson.annotation.JsonProperty("employer") String employer, @com.fasterxml.jackson.annotation.JsonProperty("identifierFingerprint") String identifierFingerprint, @com.fasterxml.jackson.annotation.JsonProperty("months") List<String> months, @com.fasterxml.jackson.annotation.JsonProperty("insurance") String insurance, @com.fasterxml.jackson.annotation.JsonProperty("fields") Map<String,Field> fields, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Source> sources, @com.fasterxml.jackson.annotation.JsonProperty("derivation") String derivation, @com.fasterxml.jackson.annotation.JsonProperty("scope") String scope) { this.id = id; this.person = person; this.employer = employer; this.identifierFingerprint = identifierFingerprint; this.months = months; this.insurance = insurance; this.fields = fields; this.sources = sources; this.derivation = derivation; this.scope = scope; }
        public String id() { return id; }
        public String person() { return person; }
        public String employer() { return employer; }
        public String identifierFingerprint() { return identifierFingerprint; }
        public List<String> months() { return months; }
        public String insurance() { return insurance; }
        public Map<String,Field> fields() { return fields; }
        public List<Source> sources() { return sources; }
        public String derivation() { return derivation; }
        public String scope() { return scope; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Record that = (Record) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.person, that.person) && java.util.Objects.equals(this.employer, that.employer) && java.util.Objects.equals(this.identifierFingerprint, that.identifierFingerprint) && java.util.Objects.equals(this.months, that.months) && java.util.Objects.equals(this.insurance, that.insurance) && java.util.Objects.equals(this.fields, that.fields) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.derivation, that.derivation) && java.util.Objects.equals(this.scope, that.scope); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.person); hash = 31 * hash + java.util.Objects.hashCode(this.employer); hash = 31 * hash + java.util.Objects.hashCode(this.identifierFingerprint); hash = 31 * hash + java.util.Objects.hashCode(this.months); hash = 31 * hash + java.util.Objects.hashCode(this.insurance); hash = 31 * hash + java.util.Objects.hashCode(this.fields); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.derivation); hash = 31 * hash + java.util.Objects.hashCode(this.scope); return hash; }
        @Override public String toString() { return "Record[" + "id=" + id + ", person=" + person + ", employer=" + employer + ", identifierFingerprint=" + identifierFingerprint + ", months=" + months + ", insurance=" + insurance + ", fields=" + fields + ", sources=" + sources + ", derivation=" + derivation + ", scope=" + scope + "]"; }

        Fact fact(){
            Map<String,Object> attrs=linked("person",person,"employer",employer,"months",months,"recordId",id,
                    "bindingMethod",VERSION,"insuranceType",insurance,"monthDerivation",derivation,
                    "sourceFieldSpans",spans(),"identifierFingerprint",identifierFingerprint,
                    "employerBinding",cn.bidcheck.compat.Java8Api.isBlank(employer)?"UNPROVEN":fields.containsKey("row:pagination")?"SAME_PAGINATED_REPORT_IN_EXPLICIT_ATTACHMENT":fields.containsKey("header:reportId")?"SAME_EXPLICIT_REPORT_REFERENCE":"SAME_ORIGINAL_RECORD",
                    "structureOrigin","FROZEN_HTML_TRANSCRIPTION","originalImageVerified",false);
            return new Fact("N"+identity(VERSION+id+person+employer+months).substring(0,20),"MONTHS",person,
                    "socialInsurance",person,Integer.toString(months.size()),"月","实际缴费记录",scope,
                    cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id).distinct()),attrs);
        }
        private Map<String,Object> spans(){
            Map<String,Object> out=new LinkedHashMap<>();fields.forEach((name,field)->{
                List<Map<String,Object>> refs=new ArrayList<>();
                for(Source source:sources){if(!cn.bidcheck.compat.Java8Api.isBlank(field.pageKey())&&!field.pageKey().equals(SocialInsuranceEvidenceRecords.pageKey(source)))continue;Matcher range=RANGE.matcher(source.evidence().anchor());if(!range.matches())continue;
                    int base=Integer.parseInt(range.group(1)),end=Integer.parseInt(range.group(2));
                    int first=Math.max(base,field.start()),last=Math.min(end,field.end());if(first>=last)continue;
                    refs.add(linked("sourceId",source.id(),"start",first-base,"end",last-base,
                            "pageStart",first,"pageEnd",last,"quote",source.evidence().quote().substring(first-base,last-base)));
                }
                out.put(name,linked("value",name.equals("identifier")?"[证件号码已隐藏]":field.text(),"spans",refs));
            });return out;
        }
    }

    static List<Fact> extract(List<Rule> rules,EvidenceCorpus corpus){
        if(rules.stream().noneMatch(r->r.predicate().equals("MONTHS")))return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(records(corpus.all).stream().map(Record::fact));
    }
    /** Expand only retrieved physical records. Repeated checks do not rescan every table in a bid. */
    static List<Fact> extract(List<Rule> rules,EvidenceCorpus corpus,List<Source> selected){
        if(rules.stream().noneMatch(r->r.predicate().equals("MONTHS")))return cn.bidcheck.compat.Java8Api.listOf();
        Set<String> pages=new HashSet<>();for(Source source:selected)pages.add(pageKey(source));
        Map<String,List<Source>> pageSources=new LinkedHashMap<>();for(Source source:corpus.all)if(!source.index()&&!source.id().startsWith("V"))pageSources.computeIfAbsent(pageKey(source),key->new ArrayList<>()).add(source);
        // The report cover is a structural dependency, not another free-text search. It is
        // admitted only after records() proves the same packet/title/pagination below.
        Set<String> selectedPages=new HashSet<>(pages);
        for(Source source:selected){String previous=EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page()-1,source.evidence().locationType());
            java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source> prev=pageSources.get(previous);if(prev!=null&&prev.stream().anyMatch(s->s.evidence().quote().contains("社会保险个人权益记录")))pages.add(previous);}
        Set<String> keys=new HashSet<>();for(String page:pages){Original original=reconstruct(pageSources.getOrDefault(page,cn.bidcheck.compat.Java8Api.listOf()));if(original!=null){ReportPage report=report(original);if(report!=null&&!cn.bidcheck.compat.Java8Api.isBlank(report.reportKey()))keys.add(report.reportKey());}}
        if(!keys.isEmpty())for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pageSources.entrySet()){
            if(pages.contains(entry.getKey())||entry.getValue().stream().noneMatch(s->s.evidence().quote().matches("(?s).*(?:查询流水号|社保权益记录编号|参保记录编号).*")))continue;
            Original original=reconstruct(entry.getValue());if(original==null)continue;ReportPage report=report(original);
            if(report!=null&&keys.contains(report.reportKey()))pages.add(entry.getKey());
        }
        return cn.bidcheck.compat.Java8Api.toList(records(cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->pages.contains(pageKey(s))))).stream()
            .filter(r->r.fields().get("person")!=null&&r.sources().stream().anyMatch(s->selectedPages.contains(pageKey(s))&&s.evidence().quote().contains(r.person())))
            .map(Record::fact));
    }
    /** Deterministic locator for the typed MONTHS obligation. Names only locate candidate
     * records; neither name search nor attachment proximity establishes personal identity. */
    static List<Source> targetedSources(List<Rule> rules,List<Fact> facts,EvidenceCorpus corpus){
        List<Rule> months=new ArrayList<>();for(Rule rule:rules)collectMonths(rule,months);if(months.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        Set<String> names=new LinkedHashSet<>();
        for(Rule rule:months){String selector=rule.subject();if(!DomainBindings.role(selector)&&selector.matches("[\\p{IsHan}·]{2,12}")&&!selector.matches(".*(?:上述|拟派|项目组|所有|全部|人员).*"))names.add(selector);
            for(Fact role:facts)if(role.kind().equals("PERSON_ROLE")&&role.scope().equals("CURRENT_PROJECT")&&DomainBindings.currentRoleSource(role,corpus.byId)
                    &&(selector.matches(".*(?:上述|拟派|项目组|所有|全部|人员).*" )||DomainBindings.roleMatches(selector,role.subject(),role,corpus)))names.add(role.value());}
        Set<String> pages=new LinkedHashSet<>();
        for(java.util.Map.Entry<java.lang.String, cn.bidcheck.domain.Domain.Page> entry:corpus.pages.entrySet()){String text=Objects.toString(entry.getValue().text(),"");
            if(text.contains("社会保险个人权益记录")&&text.contains("本单位实际缴费月数")&&text.toLowerCase(Locale.ROOT).contains("<table")&&names.stream().anyMatch(text::contains))pages.add(entry.getKey());}
        List<Source> selected=cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->pages.contains(pageKey(s))&&!s.index()&&!s.historical()&&!s.scopeContext().blocked()&&!s.id().startsWith("V")));
        LinkedHashMap<String,Source> exposed=new LinkedHashMap<>();selected.forEach(s->exposed.put(s.id(),s));
        for(Fact fact:extract(months,corpus,selected))for(String id:fact.sourceIds()){Source source=corpus.byId.get(id);if(source!=null)exposed.put(id,source);}
        LOG.info("bidcheck.social_record_locator ruleIds={} personSelectors={} dataSourceCount={} dependencySourceCount={} pages={} policy=TYPED_MONTHS_ORIGINAL_TABLE",cn.bidcheck.compat.Java8Api.toList(months.stream().map(Rule::id)),names,selected.size(),exposed.size()-selected.size(),pages);
        return cn.bidcheck.compat.Java8Api.listCopyOf(exposed.values());
    }
    private static void collectMonths(Rule rule,List<Rule> out){if(rule.predicate().equals("MONTHS"))out.add(rule);for(Rule child:rule.children())collectMonths(child,out);}
    private static String pageKey(Source source){return source.evidence().documentId()+":"+source.evidence().locationType()+":"+source.evidence().page();}
    static boolean claims(Fact fact){return fact.kind().equals("MONTHS")&&VERSION.equals(str(fact.attributes(),"bindingMethod"));}
    static String issue(Fact fact,Map<String,Source> sources){
        Record record=matching(fact,sources);if(record==null)return "SOCIAL_TABLE_RECORD_UNPROVEN";
        if(!fact.subject().equals(record.person())||!str(fact.attributes(),"person").equals(record.person()))return "STRUCTURE_SUBJECT_MISMATCH";
        if(!fact.rawValue().equals(record.person())||!fact.value().equals(Integer.toString(record.months().size())))return "SOCIAL_DERIVED_VALUE_INVALID";
        if(!str(fact.attributes(),"employer").equals(record.employer()))return "SOCIAL_EMPLOYER_LINK_UNPROVEN";
        if(!str(fact.attributes(),"identifierFingerprint").equals(record.identifierFingerprint()))return "SOCIAL_IDENTIFIER_LINK_UNPROVEN";
        if(!new TreeSet<>(ProofVerifier.strings(fact.attributes().get("months"))).equals(new TreeSet<>(record.months())))return "STRUCTURE_MONTH_COVERAGE_UNPROVEN";
        if(!fact.scope().equals(record.scope()))return "HISTORICAL_SCOPE_UNRESOLVED";
        return null;
    }
    static Fact verified(Fact fact,Map<String,Source> sources){
        Record record=matching(fact,sources);return record==null||issue(fact,sources)!=null?fact:record.fact();
    }
    static Record matching(Fact fact,Map<String,Source> sources){
        if(!claims(fact)||fact.sourceIds().isEmpty()||fact.sourceIds().stream().anyMatch(id->!sources.containsKey(id)))return null;
        String key=str(fact.attributes(),"recordId");
        return records(cn.bidcheck.compat.Java8Api.toList(fact.sourceIds().stream().map(sources::get))).stream().filter(r->r.id().equals(key)).findFirst().orElse(null);
    }

    static List<Record> records(Collection<Source> input){
        Map<String,List<Source>> groups=new LinkedHashMap<>();
        for(Source source:input){if(source.index()||source.id().startsWith("V"))continue;
            String key=source.evidence().documentId()+"|"+source.documentHash()+"|"+source.documentVersion()+"|"+source.evidence().locationType()+"|"+source.evidence().page();
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(source);
        }
        List<Record> out=new ArrayList<>();Map<String,ReportPage> reports=new LinkedHashMap<>();
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:groups.entrySet()){
            Original original=reconstruct(entry.getValue());if(original==null)continue;
            ReportPage report=report(original);if(report!=null)reports.put(pageKey(original.sources().get(0)),report);
            Matcher tables=TABLE.matcher(original.text());
            while(tables.find()){
                if(tables.group(1).toLowerCase(Locale.ROOT).contains("<table"))continue;
                String tableId=identity(VERSION+entry.getKey()+":"+(original.start()+tables.start())+":"+tables.group());
                out.addAll(read(tableId,original,tables.start(),tables.start(1),tables.group(1)));
            }
        }
        List<Record> linkedRecords=new ArrayList<>();
        for(Record record:out){
            ReportPage current=reports.get(pageKey(record.sources().get(0)));
            if(!cn.bidcheck.compat.Java8Api.isBlank(record.employer())||current==null||!record.scope().equals("CURRENT_PROJECT")){linkedRecords.add(record);continue;}
            if(current.reportId()==null){Record linked=paginated(record,current,reports);linkedRecords.add(linked==null?record:linked);continue;}
            List<ReportPage> headers=cn.bidcheck.compat.Java8Api.toList(reports.values().stream().filter(p->!cn.bidcheck.compat.Java8Api.isBlank(p.reportKey())&&p.reportKey().equals(current.reportKey())&&p.employer()!=null));
            Set<String> owners=new LinkedHashSet<>();headers.forEach(h->owners.add(h.employer().text()));
            if(owners.size()!=1){linkedRecords.add(record);continue;}
            ReportPage header=headers.get(0);Map<String,Field> fields=new LinkedHashMap<>();String rowPage=pageKey(record.sources().get(0));
            record.fields().forEach((key,value)->fields.put(key,new Field(value.text(),value.start(),value.end(),rowPage)));
            fields.put("employer",header.employer());fields.put("header:reportId",header.reportId());fields.put("row:reportId",current.reportId());
            List<Source> sources=new ArrayList<>(record.sources());sources.addAll(header.original().sources());
            linkedRecords.add(new Record(record.id(),record.person(),owners.iterator().next(),record.identifierFingerprint(),record.months(),record.insurance(),cn.bidcheck.compat.Java8Api.mapCopyOf(fields),cn.bidcheck.compat.Java8Api.toList(sources.stream().distinct()),record.derivation(),record.scope()));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(linkedRecords);
    }
    private static Record paginated(Record record,ReportPage row,Map<String,ReportPage> reports){
        if(row.pagination()==null||row.printedPage()<=1)return null;
        Source rowSource=row.original().sources().get(0);String previous=EvidenceCorpus.pageKey(rowSource.evidence().documentId(),rowSource.evidence().page()-1,rowSource.evidence().locationType());
        ReportPage header=reports.get(previous);if(header==null||header.employer()==null||header.reportId()==null||!header.title().text().equals(row.title().text())||!samePacket(header,row))return null;
        // An OCR omission on the duplicate cover may be repaired only from the identical
        // explicit report ID AND employer, never from a similar title or a nearby company.
        ReportPage pageWitness=header;
        if(header.pagination()==null){List<ReportPage> copies=cn.bidcheck.compat.Java8Api.toList(reports.values().stream().filter(h->h.reportKey().equals(header.reportKey())&&h.employer()!=null&&h.employer().text().equals(header.employer().text())&&h.title().text().equals(header.title().text())&&h.pagination()!=null));
            Set<String> versions=new HashSet<>();copies.forEach(h->versions.add(h.printedPage()+":"+h.totalPages()));if(versions.size()!=1||copies.isEmpty())return null;pageWitness=copies.get(0);}
        if(pageWitness.printedPage()!=1||pageWitness.totalPages()!=row.totalPages()||row.printedPage()>row.totalPages())return null;
        Set<String> owners=new HashSet<>();for(ReportPage h:reports.values())if(h.reportKey().equals(header.reportKey())&&h.employer()!=null)owners.add(h.employer().text());if(owners.size()!=1)return null;
        Map<String,Field> fields=new LinkedHashMap<>();String rowKey=pageKey(rowSource);
        record.fields().forEach((key,value)->fields.put(key,new Field(value.text(),value.start(),value.end(),rowKey)));
        fields.put("employer",header.employer());fields.put("header:reportId",header.reportId());fields.put("header:title",header.title());fields.put("row:title",row.title());fields.put("header:pagination",pageWitness.pagination());fields.put("row:pagination",row.pagination());
        if(pageWitness!=header)fields.put("duplicateHeader:reportId",pageWitness.reportId());
        List<Source> sources=new ArrayList<>(record.sources());sources.addAll(header.original().sources());sources.addAll(pageWitness.original().sources());
        LOG.debug("bidcheck.social_report_binding recordId={} person={} employer={} headerPage={} rowPage={} printedPage={} totalPages={} dependencySourceIds={} policy=SAME_PAGINATED_REPORT_IN_EXPLICIT_ATTACHMENT",record.id(),record.person(),header.employer().text(),header.original().sources().get(0).evidence().page(),rowSource.evidence().page(),row.printedPage(),row.totalPages(),cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id).distinct()));
        return new Record(record.id(),record.person(),header.employer().text(),record.identifierFingerprint(),record.months(),record.insurance(),cn.bidcheck.compat.Java8Api.mapCopyOf(fields),cn.bidcheck.compat.Java8Api.toList(sources.stream().distinct()),record.derivation(),record.scope());
    }
    private static boolean samePacket(ReportPage header,ReportPage row){
        Source a=header.original().sources().get(0),b=row.original().sources().get(0);
        return a.documentHash().equals(b.documentHash())&&a.documentVersion()==b.documentVersion()
            &&a.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")&&b.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")
            &&a.scopeContext().anchor().equals(b.scopeContext().anchor())&&!cn.bidcheck.compat.Java8Api.isBlank(a.scopeContext().anchor());
    }
    private static ReportPage report(Original original){
        if(original.sources().isEmpty()||original.sources().stream().anyMatch(s->s.historical()||s.scopeContext().blocked())||!original.text().contains("社会保险个人权益记录"))return null;
        Matcher ids=Pattern.compile("(?:查询流水号|社保权益记录编号|参保记录编号)\\s*[:：]\\s*([A-Za-z0-9-]{8,80})").matcher(original.text());
        Field id=null;String page=pageKey(original.sources().get(0));while(ids.find()){
            if(id!=null&&!id.text().equals(ids.group(1)))return null;
            id=new Field(ids.group(1),original.start()+ids.start(1),original.start()+ids.end(1),page);
        }
        Matcher titleMatch=Pattern.compile("[\\p{IsHan}]{0,12}社会保险个人权益记录[（(]单位职工缴费信息[）)]").matcher(original.text());
        Field title=null;if(titleMatch.find())title=new Field(titleMatch.group().replace('（','(').replace('）',')'),original.start()+titleMatch.start(),original.start()+titleMatch.end(),page);
        if(title==null){String label="社会保险个人权益记录";int at=original.text().indexOf(label);title=new Field(label,original.start()+at,original.start()+at+label.length(),page);}
        Matcher pagination=Pattern.compile("第\\s*([1-9][0-9]{0,3})\\s*页\\s*[（(]\\s*共\\s*([1-9][0-9]{0,3})\\s*页\\s*[）)]").matcher(original.text());Field printed=null;int printedPage=0,total=0;
        while(pagination.find()){int p=Integer.parseInt(pagination.group(1)),n=Integer.parseInt(pagination.group(2));if(printed!=null&&(p!=printedPage||n!=total))return null;printedPage=p;total=n;printed=new Field(pagination.group(),original.start()+pagination.start(),original.start()+pagination.end(),page);}
        if(id==null&&printed==null)return null;
        String prefix=original.text();int table=prefix.toLowerCase(Locale.ROOT).indexOf("<table");if(table>=0)prefix=prefix.substring(0,table);
        Matcher companies=EMPLOYER.matcher(prefix);Field company=null;boolean multiple=false;
        while(companies.find()){
            String name=cn.bidcheck.compat.Java8Api.strip(cn.bidcheck.compat.Java8Api.strip(companies.group(1)).replaceAll("[*`]+$",""));if(!name.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$"))continue;
            if(company!=null&&!company.text().equals(name))multiple=true;
            company=new Field(name,original.start()+companies.start(1),original.start()+companies.start(1)+name.length(),page);
        }
        if(multiple)company=null;Source first=original.sources().get(0);
        return new ReportPage(original,id==null?"":first.evidence().documentId()+"|"+first.documentHash()+"|"+first.documentVersion()+"|"+identity(id.text()),id,company,title,printed,printedPage,total);
    }
    private static Original reconstruct(List<Source> sources){
        List<Source> ordered=cn.bidcheck.compat.Java8Api.toList(sources.stream().distinct().sorted(Comparator.comparingInt(s->{Matcher m=RANGE.matcher(s.evidence().anchor());return m.matches()?Integer.parseInt(m.group(1)):Integer.MAX_VALUE;})));
        if(ordered.isEmpty()||ordered.size()>12)return null;
        int base=-1,end=-1;StringBuilder text=new StringBuilder();
        for(Source source:ordered){Matcher range=RANGE.matcher(source.evidence().anchor());if(!range.matches())return null;
            int from=Integer.parseInt(range.group(1)),to=Integer.parseInt(range.group(2));String quote=source.evidence().quote();
            if(to-from!=quote.length()||from<0||to>500000)return null;
            if(base<0){base=from;end=to;text.append(quote);continue;}
            if(from>end)return null;int overlap=Math.min(end,to)-from;
            if(overlap<0||!text.substring(from-base,from-base+overlap).equals(quote.substring(0,overlap)))return null;
            if(to>end){text.append(quote.substring(overlap));end=to;}
        }
        return new Original(text.toString(),base,ordered);
    }
    private static List<Record> read(String tableId,Original original,int tableStart,int bodyStart,String body){
        List<Cell> cells=parse(body,original.start()+bodyStart);if(cells.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,Integer> columns=new LinkedHashMap<>();Map<String,Cell> headings=new LinkedHashMap<>();
        for(Cell cell:cells){if(cell.row()>1||cell.columnSpan()!=1)continue;String label=label(cell.field().text());if(cn.bidcheck.compat.Java8Api.isBlank(label))continue;
            if(columns.putIfAbsent(label,cell.column())!=null)return cn.bidcheck.compat.Java8Api.listOf();headings.put(label,cell);
        }
        if(!columns.containsKey("person")||!columns.containsKey("start")||!columns.containsKey("end")||!columns.containsKey("count")||!columns.containsKey("insurance"))return cn.bidcheck.compat.Java8Api.listOf();
        String prefix=original.text().substring(0,tableStart);int previous=prefix.toLowerCase(Locale.ROOT).lastIndexOf("</table>");if(previous>=0)prefix=prefix.substring(previous+8);
        int prefixOffset=original.start()+tableStart-prefix.length();
        Matcher employerMatcher=EMPLOYER.matcher(prefix);Field company=null;boolean multiple=false;
        while(employerMatcher.find()){String name=cn.bidcheck.compat.Java8Api.strip(cn.bidcheck.compat.Java8Api.strip(employerMatcher.group(1)).replaceAll("[*`]+$",""));if(!name.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$"))continue;
            if(company!=null&&!company.text().equals(name))multiple=true;
            company=new Field(name,prefixOffset+employerMatcher.start(1),prefixOffset+employerMatcher.start(1)+name.length());
        }
        if(multiple)company=null;
        List<Record> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        int maxRow=cells.stream().mapToInt(Cell::row).max().orElse(0);
        int firstData=headings.values().stream().mapToInt(c->c.row()+c.rowSpan()).max().orElse(1);
        for(int row=firstData;row<=maxRow;row++){
            Map<String,Cell> values=new LinkedHashMap<>();boolean ambiguous=false;
            for(java.util.Map.Entry<java.lang.String, java.lang.Integer> column:columns.entrySet()){
                List<Cell> found=new ArrayList<>();for(Cell c:cells)if(c.row()<=row&&c.row()+c.rowSpan()>row&&c.column()==column.getValue()&&c.columnSpan()==1)found.add(c);
                if(found.size()>1){ambiguous=true;break;}if(found.size()==1)values.put(column.getKey(),found.get(0));
            }
            if(ambiguous||!values.keySet().containsAll(cn.bidcheck.compat.Java8Api.setOf("person", "start", "end", "count", "insurance")))continue;
            String person=values.get("person").field().text(),insurance=values.get("insurance").field().text();
            if(!person.matches("[\\p{IsHan}·]{2,12}")||!insurance.matches("(?:基本)?(?:养老|医疗|失业|工伤|生育)保险"))continue;
            YearMonth start=month(values.get("start").field().text()),end=month(values.get("end").field().text());
            String countText=values.get("count").field().text();if(start==null||end==null||!countText.matches("[1-9][0-9]{0,2}"))continue;
            int count=Integer.parseInt(countText);long span=ChronoUnit.MONTHS.between(start,end)+1;
            // Equal distinct-month cardinality and closed interval prove each month. Endpoints
            // alone, an arrears label, or a smaller count never prove the omitted middle months.
            if(span<1||span>120||span!=count)continue;
            Map<String,Field> fields=new LinkedHashMap<>();values.forEach((k,v)->fields.put(k,v.field()));headings.forEach((k,v)->fields.put("header:"+k,v.field()));
            String employer="";if(values.containsKey("employer")){employer=values.get("employer").field().text();}
            else if(company!=null){employer=company.text();fields.put("employer",company);}
            if(!cn.bidcheck.compat.Java8Api.isBlank(employer)&&!employer.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$"))continue;
            String identifier=values.containsKey("identifier")?clean(values.get("identifier").field().text()).toUpperCase(Locale.ROOT):"";
            String fingerprint=cn.bidcheck.verification.source.CitizenId.valid(identifier)?identity(identifier):"";
            List<String> months=new ArrayList<>();for(YearMonth current=start;!current.isAfter(end);current=current.plusMonths(1))months.add(current.toString());
            String duplicate=person+":"+fingerprint+":"+employer+":"+months;if(!seen.add(duplicate))continue;
            String scope=original.sources().stream().anyMatch(s->s.historical()||s.scopeContext().blocked())?"HISTORICAL_PROJECT":"CURRENT_PROJECT";
            out.add(new Record(identity(tableId+":"+row),person,employer,fingerprint,cn.bidcheck.compat.Java8Api.listCopyOf(months),insurance,
                    cn.bidcheck.compat.Java8Api.mapCopyOf(fields),original.sources(),"START_END_AND_ACTUAL_PAID_MONTH_COUNT",scope));
        }
        return out;
    }
    private static List<Cell> parse(String body,int offset){
        List<Cell> cells=new ArrayList<>();Set<String> occupied=new HashSet<>();Matcher rows=ROW.matcher(body);int row=0;
        while(rows.find()){
            if(row>1000)return cn.bidcheck.compat.Java8Api.listOf();Matcher match=CELL.matcher(rows.group(1));int column=0;
            while(match.find()){
                while(occupied.contains(row+":"+column))column++;int rowSpan=span(match.group(2),"rowspan"),colSpan=span(match.group(2),"colspan");
                if(rowSpan<1||colSpan<1||rowSpan>100||colSpan>30||column+colSpan>50)return cn.bidcheck.compat.Java8Api.listOf();
                String raw=match.group(3);if(raw.toLowerCase(Locale.ROOT).contains("<table"))return cn.bidcheck.compat.Java8Api.listOf();
                String text=cn.bidcheck.compat.Java8Api.strip(raw.replaceAll("(?is)<[^>]+>","").replace("&nbsp;"," ").replace("&amp;","&").replace("&#160;"," "));
                int start=offset+rows.start(1)+match.start(3),end=offset+rows.start(1)+match.end(3);
                for(int r=row;r<row+rowSpan;r++)for(int c=column;c<column+colSpan;c++)if(!occupied.add(r+":"+c))return cn.bidcheck.compat.Java8Api.listOf();
                cells.add(new Cell(new Field(text,start,end),row,column,rowSpan,colSpan));column+=colSpan;
            }
            row++;
        }
        return cells;
    }
    private static int span(String attrs,String name){Matcher matcher=Pattern.compile("(?i)\\b"+name+"\\s*=\\s*['\"]?(\\d+)").matcher(attrs);if(!matcher.find())return 1;return matcher.group(1).length()>3?0:Integer.parseInt(matcher.group(1));}
    private static YearMonth month(String text){try{Matcher m=MONTH.matcher(cn.bidcheck.compat.Java8Api.strip(text));return m.matches()?YearMonth.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))):null;}catch(RuntimeException invalid){return null;}}
    private static String label(String text){switch (clean(text)) {case "姓名":case "人员姓名":case "参保人姓名":case "参保人":return "person";case "身份证号":case "身份证号码":case "公民身份号码":case "社会保障号码":return "identifier";case "险种":case "保险类型":return "insurance";case "起始年月":case "缴费起始年月":case "缴费开始月份":return "start";case "截止年月":case "缴费截止年月":case "缴费结束月份":return "end";case "本单位实际缴费月数":case "实际缴费月数":case "实际缴费月份数":return "count";case "单位名称":case "缴费单位":case "参保单位":return "employer";default:return "";}}
}
