package cn.bidcheck.verification.engine;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/**
 * Reads payment records from the frozen original HTML table transcription. It never treats a
 * query period as payment, invents a continuous quotation, or imports an employer from proximity.
 * Values and row/column membership are re-derived at admission; serialized proof is audit only.
 */
final class SocialInsuranceEvidenceRecords {
    static final String VERSION="social-payment-table-records-2-explicit-report-reference";
    private static final Pattern RANGE=Pattern.compile("chars:(\\d+)-(\\d+)");
    private static final Pattern TABLE=Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table\\s*>");
    private static final Pattern ROW=Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern CELL=Pattern.compile("(?is)<(td|th)\\b([^>]*)>(.*?)</\\1\\s*>");
    private static final Pattern MONTH=Pattern.compile("^(20\\d{2})\\s*(?:年|[-./])\\s*(0?[1-9]|1[0-2])\\s*月?$");
    private static final Pattern EMPLOYER=Pattern.compile("(?:缴费单位|参保单位|单位名称)\\s*[:：]\\s*([^\\r\\n<>|]{2,100})");
    private SocialInsuranceEvidenceRecords(){}

    record Field(String text,int start,int end,String pageKey){Field(String text,int start,int end){this(text,start,end,"");}}
    private record ReportPage(Original original,String reportKey,Field reportId,Field employer){}
    private record Cell(Field field,int row,int column,int rowSpan,int columnSpan){}
    private record Original(String text,int start,List<Source> sources){}
    record Record(String id,String person,String employer,String identifierFingerprint,List<String> months,
                  String insurance,Map<String,Field> fields,List<Source> sources,String derivation,String scope){
        Fact fact(){
            Map<String,Object> attrs=linked("person",person,"employer",employer,"months",months,"recordId",id,
                    "bindingMethod",VERSION,"insuranceType",insurance,"monthDerivation",derivation,
                    "sourceFieldSpans",spans(),"identifierFingerprint",identifierFingerprint,
                    "employerBinding",employer.isBlank()?"UNPROVEN":fields.containsKey("header:reportId")?"SAME_EXPLICIT_REPORT_REFERENCE":"SAME_ORIGINAL_RECORD",
                    "structureOrigin","FROZEN_HTML_TRANSCRIPTION","originalImageVerified",false);
            return new Fact("N"+identity(VERSION+id+person+employer+months).substring(0,20),"MONTHS",person,
                    "socialInsurance",person,Integer.toString(months.size()),"月","实际缴费记录",scope,
                    sources.stream().map(Source::id).distinct().toList(),attrs);
        }
        private Map<String,Object> spans(){
            Map<String,Object> out=new LinkedHashMap<>();fields.forEach((name,field)->{
                List<Map<String,Object>> refs=new ArrayList<>();
                for(Source source:sources){if(!field.pageKey().isBlank()&&!field.pageKey().equals(SocialInsuranceEvidenceRecords.pageKey(source)))continue;Matcher range=RANGE.matcher(source.evidence().anchor());if(!range.matches())continue;
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
        if(rules.stream().noneMatch(r->r.predicate().equals("MONTHS")))return List.of();
        return records(corpus.all).stream().map(Record::fact).toList();
    }
    /** Expand only retrieved physical records. Repeated checks do not rescan every table in a bid. */
    static List<Fact> extract(List<Rule> rules,EvidenceCorpus corpus,List<Source> selected){
        if(rules.stream().noneMatch(r->r.predicate().equals("MONTHS")))return List.of();
        Set<String> pages=new HashSet<>();for(Source source:selected)pages.add(pageKey(source));
        Map<String,List<Source>> pageSources=new LinkedHashMap<>();for(Source source:corpus.all)if(!source.index()&&!source.id().startsWith("V"))pageSources.computeIfAbsent(pageKey(source),key->new ArrayList<>()).add(source);
        Set<String> keys=new HashSet<>();for(String page:pages){Original original=reconstruct(pageSources.getOrDefault(page,List.of()));if(original!=null){ReportPage report=report(original);if(report!=null)keys.add(report.reportKey());}}
        if(!keys.isEmpty())for(var entry:pageSources.entrySet()){
            if(pages.contains(entry.getKey())||entry.getValue().stream().noneMatch(s->s.evidence().quote().matches("(?s).*(?:查询流水号|社保权益记录编号|参保记录编号).*")))continue;
            Original original=reconstruct(entry.getValue());if(original==null)continue;ReportPage report=report(original);
            if(report!=null&&keys.contains(report.reportKey()))pages.add(entry.getKey());
        }
        return records(corpus.all.stream().filter(s->pages.contains(pageKey(s))).toList()).stream().map(Record::fact).toList();
    }
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
        return records(fact.sourceIds().stream().map(sources::get).toList()).stream().filter(r->r.id().equals(key)).findFirst().orElse(null);
    }

    static List<Record> records(Collection<Source> input){
        Map<String,List<Source>> groups=new LinkedHashMap<>();
        for(Source source:input){if(source.index()||source.id().startsWith("V"))continue;
            String key=source.evidence().documentId()+"|"+source.documentHash()+"|"+source.documentVersion()+"|"+source.evidence().locationType()+"|"+source.evidence().page();
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(source);
        }
        List<Record> out=new ArrayList<>();Map<String,ReportPage> reports=new LinkedHashMap<>();
        for(var entry:groups.entrySet()){
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
            if(!record.employer().isBlank()||current==null||!record.scope().equals("CURRENT_PROJECT")){linkedRecords.add(record);continue;}
            List<ReportPage> headers=reports.values().stream().filter(p->p.reportKey().equals(current.reportKey())&&p.employer()!=null).toList();
            Set<String> owners=new LinkedHashSet<>();headers.forEach(h->owners.add(h.employer().text()));
            if(owners.size()!=1){linkedRecords.add(record);continue;}
            ReportPage header=headers.get(0);Map<String,Field> fields=new LinkedHashMap<>();String rowPage=pageKey(record.sources().get(0));
            record.fields().forEach((key,value)->fields.put(key,new Field(value.text(),value.start(),value.end(),rowPage)));
            fields.put("employer",header.employer());fields.put("header:reportId",header.reportId());fields.put("row:reportId",current.reportId());
            List<Source> sources=new ArrayList<>(record.sources());sources.addAll(header.original().sources());
            linkedRecords.add(new Record(record.id(),record.person(),owners.iterator().next(),record.identifierFingerprint(),record.months(),record.insurance(),Map.copyOf(fields),sources.stream().distinct().toList(),record.derivation(),record.scope()));
        }
        return List.copyOf(linkedRecords);
    }
    private static ReportPage report(Original original){
        if(original.sources().isEmpty()||original.sources().stream().anyMatch(s->s.historical()||s.scopeContext().blocked())||!original.text().contains("社会保险个人权益记录"))return null;
        Matcher ids=Pattern.compile("(?:查询流水号|社保权益记录编号|参保记录编号)\\s*[:：]\\s*([A-Za-z0-9-]{8,80})").matcher(original.text());
        Field id=null;String page=pageKey(original.sources().get(0));while(ids.find()){
            if(id!=null&&!id.text().equals(ids.group(1)))return null;
            id=new Field(ids.group(1),original.start()+ids.start(1),original.start()+ids.end(1),page);
        }
        if(id==null)return null;
        String prefix=original.text();int table=prefix.toLowerCase(Locale.ROOT).indexOf("<table");if(table>=0)prefix=prefix.substring(0,table);
        Matcher companies=EMPLOYER.matcher(prefix);Field company=null;boolean multiple=false;
        while(companies.find()){
            String name=companies.group(1).strip().replaceAll("[*`]+$","").strip();if(!name.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$"))continue;
            if(company!=null&&!company.text().equals(name))multiple=true;
            company=new Field(name,original.start()+companies.start(1),original.start()+companies.start(1)+name.length(),page);
        }
        if(multiple)company=null;Source first=original.sources().get(0);
        return new ReportPage(original,first.evidence().documentId()+"|"+first.documentHash()+"|"+first.documentVersion()+"|"+identity(id.text()),id,company);
    }
    private static Original reconstruct(List<Source> sources){
        List<Source> ordered=sources.stream().distinct().sorted(Comparator.comparingInt(s->{Matcher m=RANGE.matcher(s.evidence().anchor());return m.matches()?Integer.parseInt(m.group(1)):Integer.MAX_VALUE;})).toList();
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
        List<Cell> cells=parse(body,original.start()+bodyStart);if(cells.isEmpty())return List.of();
        Map<String,Integer> columns=new LinkedHashMap<>();Map<String,Cell> headings=new LinkedHashMap<>();
        for(Cell cell:cells){if(cell.row()>1||cell.columnSpan()!=1)continue;String label=label(cell.field().text());if(label.isBlank())continue;
            if(columns.putIfAbsent(label,cell.column())!=null)return List.of();headings.put(label,cell);
        }
        if(!columns.containsKey("person")||!columns.containsKey("start")||!columns.containsKey("end")||!columns.containsKey("count")||!columns.containsKey("insurance"))return List.of();
        String prefix=original.text().substring(0,tableStart);int previous=prefix.toLowerCase(Locale.ROOT).lastIndexOf("</table>");if(previous>=0)prefix=prefix.substring(previous+8);
        int prefixOffset=original.start()+tableStart-prefix.length();
        Matcher employerMatcher=EMPLOYER.matcher(prefix);Field company=null;boolean multiple=false;
        while(employerMatcher.find()){String name=employerMatcher.group(1).strip().replaceAll("[*`]+$","").strip();if(!name.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$"))continue;
            if(company!=null&&!company.text().equals(name))multiple=true;
            company=new Field(name,prefixOffset+employerMatcher.start(1),prefixOffset+employerMatcher.start(1)+name.length());
        }
        if(multiple)company=null;
        List<Record> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        int maxRow=cells.stream().mapToInt(Cell::row).max().orElse(0);
        int firstData=headings.values().stream().mapToInt(c->c.row()+c.rowSpan()).max().orElse(1);
        for(int row=firstData;row<=maxRow;row++){
            Map<String,Cell> values=new LinkedHashMap<>();boolean ambiguous=false;
            for(var column:columns.entrySet()){
                List<Cell> found=new ArrayList<>();for(Cell c:cells)if(c.row()<=row&&c.row()+c.rowSpan()>row&&c.column()==column.getValue()&&c.columnSpan()==1)found.add(c);
                if(found.size()>1){ambiguous=true;break;}if(found.size()==1)values.put(column.getKey(),found.get(0));
            }
            if(ambiguous||!values.keySet().containsAll(Set.of("person","start","end","count","insurance")))continue;
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
            if(!employer.isBlank()&&!employer.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$"))continue;
            String identifier=values.containsKey("identifier")?clean(values.get("identifier").field().text()).toUpperCase(Locale.ROOT):"";
            String fingerprint=identifier.matches("[0-9]{17}[0-9X]")?identity(identifier):"";
            List<String> months=new ArrayList<>();for(YearMonth current=start;!current.isAfter(end);current=current.plusMonths(1))months.add(current.toString());
            String duplicate=person+":"+fingerprint+":"+employer+":"+months;if(!seen.add(duplicate))continue;
            String scope=original.sources().stream().anyMatch(s->s.historical()||s.scopeContext().blocked())?"HISTORICAL_PROJECT":"CURRENT_PROJECT";
            out.add(new Record(identity(tableId+":"+row),person,employer,fingerprint,List.copyOf(months),insurance,
                    Map.copyOf(fields),original.sources(),"START_END_AND_ACTUAL_PAID_MONTH_COUNT",scope));
        }
        return out;
    }
    private static List<Cell> parse(String body,int offset){
        List<Cell> cells=new ArrayList<>();Set<String> occupied=new HashSet<>();Matcher rows=ROW.matcher(body);int row=0;
        while(rows.find()){
            if(row>1000)return List.of();Matcher match=CELL.matcher(rows.group(1));int column=0;
            while(match.find()){
                while(occupied.contains(row+":"+column))column++;int rowSpan=span(match.group(2),"rowspan"),colSpan=span(match.group(2),"colspan");
                if(rowSpan<1||colSpan<1||rowSpan>100||colSpan>30||column+colSpan>50)return List.of();
                String raw=match.group(3);if(raw.toLowerCase(Locale.ROOT).contains("<table"))return List.of();
                String text=raw.replaceAll("(?is)<[^>]+>","").replace("&nbsp;"," ").replace("&amp;","&").replace("&#160;"," ").strip();
                int start=offset+rows.start(1)+match.start(3),end=offset+rows.start(1)+match.end(3);
                for(int r=row;r<row+rowSpan;r++)for(int c=column;c<column+colSpan;c++)if(!occupied.add(r+":"+c))return List.of();
                cells.add(new Cell(new Field(text,start,end),row,column,rowSpan,colSpan));column+=colSpan;
            }
            row++;
        }
        return cells;
    }
    private static int span(String attrs,String name){Matcher matcher=Pattern.compile("(?i)\\b"+name+"\\s*=\\s*['\"]?(\\d+)").matcher(attrs);if(!matcher.find())return 1;return matcher.group(1).length()>3?0:Integer.parseInt(matcher.group(1));}
    private static YearMonth month(String text){try{Matcher m=MONTH.matcher(text.strip());return m.matches()?YearMonth.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))):null;}catch(RuntimeException invalid){return null;}}
    private static String label(String text){return switch(clean(text)){
        case "姓名","人员姓名","参保人姓名","参保人"->"person";
        case "身份证号","身份证号码","公民身份号码","社会保障号码"->"identifier";
        case "险种","保险类型"->"insurance";
        case "起始年月","缴费起始年月","缴费开始月份"->"start";
        case "截止年月","缴费截止年月","缴费结束月份"->"end";
        case "本单位实际缴费月数","实际缴费月数","实际缴费月份数"->"count";
        case "单位名称","缴费单位","参保单位"->"employer";
        default->"";
    };}
}
