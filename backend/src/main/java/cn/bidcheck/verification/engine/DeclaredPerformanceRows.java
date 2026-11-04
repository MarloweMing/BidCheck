package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.facts.TableStructure;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Read submitted inventory rows first. Separator titles route acquisition, never confer a score. */
final class DeclaredPerformanceRows {
    private static final Pattern ROW = Pattern.compile("(?m)^[ \\t|]*([1-9][0-9]{0,2})(?:[ \\t]+|[ \\t]*\\|)");
    private static final Pattern MARK = Pattern.compile("(?m)^[ #\\t]*(?:([①-⑳])|([1-9][0-9]?)[、.．](?![0-9]{1,2}\\s*[、.．]))[ \\t]*([^\\r\\n]{4,180})$");
    private static final Pattern DATE = Pattern.compile("(20[0-9]{2})\\s*年\\s*([0-9]{1,2})\\s*月\\s*([0-9]{1,2})\\s*日");
    private static final Pattern PO = Pattern.compile("(?i)\\bPO[-：: ]*[A-Z0-9-]*[0-9][A-Z0-9-]*");
    private DeclaredPerformanceRows() {}

    /**
     * Revalidate exact inventory-page navigation when rebuilding the whole decision tree.
     * Recovery fact sources may contain only an appointment/social page, so they cannot be
     * the authority for which previously declared table layouts still belong to the tree.
     * This reads the current source grid only: no contract search, scan/model call or fact.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> restoreOwnerLayouts(
            List<Rule> roots, EvidenceCorpus corpus, Map<String, Object> context) {
        List<Map<String, Object>> audit = new ArrayList<>();
        if (!(context.get("layoutLookup") instanceof Function<?, ?>)) {
            return audit;
        }

        Map<String, Map<String, Object>> knownCells = new LinkedHashMap<>();
        for (Map<String, Object> cell : RuleEvaluator.maps(context.get("_validatedLayoutCells"))) {
            knownCells.put(layoutKey(cell), cell);
        }
        Set<String> visitedPages = new HashSet<>();
        Deque<Rule> pendingRules = new ArrayDeque<>(roots);
        while (!pendingRules.isEmpty()) {
            Rule rule = pendingRules.removeFirst();
            pendingRules.addAll(rule.children());
            if (!SourcePerformanceScore.MODE.equals(str(rule.parameters(), "sourceScoringMode"))
                    || str(rule.parameters(), "personRole").isEmpty()
                    || SourceScoringRules.issue(rule) != null) {
                continue;
            }

            for (Map<String, Object> row : read(rule, "", corpus, Collections.emptyList())) {
                String pageKey = str(row, "documentId") + ":" + row.get("inventoryPage")
                        + ":" + str(row, "documentHash") + ":" + row.get("documentVersion");
                if (!visitedPages.add(pageKey)) {
                    continue;
                }

                List<Source> inventorySources = new ArrayList<>();
                for (Source source : corpus.all) {
                    if (inventoryContains(row, source) && !source.id().startsWith("V")) {
                        inventorySources.add(source);
                    }
                }
                if (inventorySources.isEmpty()) {
                    continue;
                }
                Source source = inventorySources.get(0);
                Map<String, Object> auditEvent = linked(
                        "parentId", rule.id(),
                        "documentId", row.get("documentId"),
                        "documentHash", row.get("documentHash"),
                        "documentVersion", row.get("documentVersion"),
                        "page", row.get("inventoryPage"),
                        "sourceIds", row.get("sourceIds"),
                        "allowScan", false,
                        "businessProof", false,
                        "modelCalls", 0);
                try {
                    Function<Map<String, Object>, ?> layoutLookup =
                            (Function<Map<String, Object>, ?>) context.get("layoutLookup");
                    Object layoutResult = layoutLookup.apply(linked(
                            "documentId", source.evidence().documentId(),
                            "documentHash", source.documentHash(),
                            "documentVersion", source.documentVersion(),
                            "locationType", source.evidence().locationType(),
                            "location", source.evidence().page(),
                            "sourceIds", row.get("sourceIds"),
                            "sourceQuote", source.evidence().quote(),
                            "allowScan", false));
                    Fact inventorySelector = new Fact(
                            "inventory-owner", "FIELD", "", "", "", "", "", "", "UNRESOLVED",
                            ProofVerifier.strings(row.get("sourceIds")), Collections.emptyMap());
                    List<Map<String, Object>> validatedCells = TableStructure.validatedCells(
                            RuleEvaluator.maps(layoutResult),
                            LayoutBinding.sources(inventorySelector, corpus.byId));
                    for (Map<String, Object> cell : validatedCells) {
                        knownCells.put(layoutKey(cell), cell);
                    }
                    auditEvent.put("validatedCellCount", validatedCells.size());
                    auditEvent.put("status", validatedCells.isEmpty()
                            ? "OWNER_LAYOUT_UNAVAILABLE" : "SOURCE_GRID_REVALIDATED");
                } catch (CancellationException interrupted) {
                    throw interrupted;
                } catch (RuntimeException unavailable) {
                    auditEvent.put("status", "OWNER_LAYOUT_UNAVAILABLE");
                    auditEvent.put("errorType", unavailable.getClass().getSimpleName());
                }
                audit.add(auditEvent);
            }
        }
        context.put("_validatedLayoutCells", new ArrayList<>(knownCells.values()));
        return audit;
    }

    private static String layoutKey(Map<String, Object> cell) {
        String cellId = str(cell, "spanId");
        if (cellId.isEmpty()) {
            cellId = str(cell, "cellId");
        }
        return str(cell, "documentId") + ":" + str(cell, "documentHash")
                + ":" + cell.get("documentVersion") + ":" + cellId;
    }

    static List<Map<String,Object>> read(Rule rule, String target, EvidenceCorpus corpus,List<Map<String,Object>> layout) {
        boolean personal=!str(rule.parameters(),"personRole").isEmpty();
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Document document:corpus.documents){
            if(!"BID".equals(document.role()))continue;
            List<Page> pages=new ArrayList<>(document.pages());
            pages.sort(Comparator.comparingInt(Page::number));
            for(int i=0;i<pages.size();i++){
                Page list=pages.get(i);
                String text=Objects.toString(list.text(),"");
                String compact=clean(text);
                if(!compact.contains("序号合同名称签订时间")||!compact.contains("金额")||
                    compact.contains("填写示例")||compact.contains("空白模板"))continue;
                if(personal){
                    boolean design=str(rule.parameters(),"personRole").contains("设计");
                    if(!text.trim().startsWith(design?"作为设计负责人的业绩":"作为负责人的业绩"))continue;
                }else if(!compact.contains("业绩清单")||!compact.contains("类似项目情况表"))continue;
                List<Map<String,Object>> pageRows=rows(text);
                if(pageRows.isEmpty())continue;
                attachRanges(pageRows,document,pages,i,corpus);
                bindLayout(pageRows,layout,corpus);
                if(personal)bindOwners(pageRows,text,str(rule.parameters(),"personRole"));
                rows.addAll(pageRows);
            }
        }
        if(personal){
            Map<String,Set<String>> tables=new HashMap<>();
            for(Map<String,Object> row:rows)for(String name:ProofVerifier.strings(row.get("ownerCandidates")))
                tables.computeIfAbsent(name,k->new HashSet<>()).add(str(row,"documentId")+":"+row.get("inventoryPage"));
            for(Map<String,Object> row:rows)if(ProofVerifier.strings(row.get("ownerCandidates")).stream().anyMatch(n->tables.get(n).size()>1))row.put("ownerBindingStatus","AMBIGUOUS_MULTIPLE_TABLES");
            if(!target.isEmpty())rows.removeIf(row->!"SOURCE_BOUND".equals(row.get("ownerBindingStatus"))||!target.equals(str(row,"declaredOwner")));
        }
        return rows;
    }

    /** Only explicitly labelled table ownership or validated header/cell linkage binds a name.
     * Incidental mentions anywhere in a table are not an ownership relationship. */
    private static void bindOwners(List<Map<String,Object>> rows,String text,String role){
        int header=text.indexOf("序号");String opening=header<0?"":text.substring(0,header);
        Set<String> labelled=new LinkedHashSet<>();List<String> quotes=new ArrayList<>();
        Matcher m=Pattern.compile("(?m)^[ \\t]*(?:姓名|申报人|业绩申报人)[ \\t]*[:：][ \\t]*([\\p{IsHan}·]{2,12})[ \\t]*$").matcher(opening);
        while(m.find()){labelled.add(m.group(1));quotes.add(m.group().trim());}
        for(Map<String,Object> row:rows){
            Set<String> names=new LinkedHashSet<>(labelled);List<String> evidence=new ArrayList<>(quotes);
            Object raw=row.get("sourceBoundTableFields");
            if(raw instanceof Map<?,?>)for(String label:Arrays.asList("姓名","申报人","业绩申报人",role.contains("设计")?"设计负责人":"项目负责人")){
                Object value=((Map<?,?>)raw).get(label);String name=Objects.toString(value,"");
                if(name.matches("[\\p{IsHan}·]{2,12}")){names.add(name);evidence.add(label+"："+name);}
            }
            row.put("personalDeclaration",true);row.put("ownerCandidates",new ArrayList<>(names));row.put("ownerSourceQuotes",evidence);
            row.put("ownerBindingStatus",names.size()==1?"SOURCE_BOUND":names.isEmpty()?"OWNER_UNRESOLVED":"AMBIGUOUS_OWNER_FIELDS");
            if(names.size()==1)row.put("declaredOwner",names.iterator().next());
        }
    }

    static List<Map<String,Object>> rows(String text) {
        List<Map<String,Object>> records=new ArrayList<>();
        Matcher row=ROW.matcher(text);
        while(row.find()){
            int ordinal=Integer.parseInt(row.group(1));
            if(ordinal!=records.size()+1)continue;
            int end=text.indexOf('\n',row.end()); if(end<0)end=text.length();
            String line=text.substring(row.start(),end);
            records.add(linked("row",ordinal,"rowAnchor",line,"sourceOffset",row.start(),"countsAsPerformance",false));
        }
        for(int i=0;i<records.size();i++){
            Map<String,Object> record=records.get(i);
            int start=(Integer)record.get("sourceOffset");
            int end=i+1<records.size()?(Integer)records.get(i+1).get("sourceOffset"):text.length();
            String quote=text.substring(start,end).trim();record.put("rowQuote",quote);
            Set<String> dates=new LinkedHashSet<>();Matcher date=DATE.matcher(quote);
            while(date.find())try{dates.add(java.time.LocalDate.of(Integer.parseInt(date.group(1)),Integer.parseInt(date.group(2)),Integer.parseInt(date.group(3))).toString());}catch(java.time.DateTimeException ignored){}
            record.put("explicitDates",dates);
            List<String> amounts=new ArrayList<>();
            Matcher amount=Pattern.compile("(?:建安费|设计费|装修部分金额|合同总价|合同金额|订单金额)[:： ]*[0-9][0-9,.]*\\s*(?:万元|元)").matcher(quote);
            while(amount.find())amounts.add(amount.group());record.put("typedAmounts",amounts);
            // Column position is not authority. Only a single explicit date and a separate,
            // recognizable contract title can repair a reversed pair of submitted columns.
            String body=quote.replaceFirst("^[ \\t|]*[0-9]+[ \\t|]*","");
            String name="";boolean swapped=false;
            if(body.contains("|")){
                List<String> columns=new ArrayList<>();for(String cell:body.split("\\|"))if(!cell.trim().isEmpty())columns.add(cell.trim());
                if(columns.size()>=2){String a=columns.get(0),b=columns.get(1);
                    if(DATE.matcher(a).matches()&&title(b)){name=b;swapped=true;}
                    else if(title(a)&&DATE.matcher(b).matches())name=a;
                }
            }else{
                Matcher labelled=Pattern.compile("^(?:合同名称[:：]\\s*)?(.+?)\\s+(?:签订时间|签订日期)[:：]").matcher(body);
                if(labelled.find()&&title(labelled.group(1)))name=labelled.group(1).trim();
            }
            if(!name.isEmpty()&&dates.size()==1){record.put("contractName",name);record.put("signedDate",dates.iterator().next());
                record.put("fieldBindingMethod","UNAMBIGUOUS_SOURCE_ROW_VALUE_TYPES");
                if(swapped)record.put("columnCorrection","DATE_AND_TITLE_SWAPPED_BY_VALUE_TYPE");
            }
        }
        return records;
    }
    private static boolean title(String text){return text.matches(".*(?:合同|工程|项目|设计|装修).*")&&!DATE.matcher(text).find()&&!text.matches(".*(?:金额|总价|建安费|设计费)[:：].*");}

    private static void attachRanges(List<Map<String,Object>> rows,Document document,List<Page> pages,int listIndex,EvidenceCorpus corpus) {
        Page list=pages.get(listIndex);
        int end=pages.get(pages.size()-1).number();
        int bound=PerformanceInventoryProof.declaredSectionEnd(document,list);
        if(bound>=list.number())end=Math.min(end,bound);
        Boolean circled=null;
        Map<Integer,Integer> starts=new LinkedHashMap<>();Map<Integer,String> names=new LinkedHashMap<>();
        for(int i=listIndex+1;i<pages.size();i++){
            Page page=pages.get(i);if(page.number()>end)break;String text=Objects.toString(page.text(),"");String opening=text.substring(0,Math.min(400,text.length()));
            if(opening.trim().startsWith("作为负责人的业绩")||opening.trim().startsWith("作为设计负责人的业绩")||opening.matches("(?s)^\\s*[0-9]+[.．、]\\s*[^\\n]{1,30}\\n\\s*[（(]1[）)]情况表.*")||
                opening.matches("(?s)^\\s*[0-9]+(?:\\.[0-9]+)+[ \\t]+[^\\r\\n]{1,45}\\r?\\n.*")){
                end=page.number()-1;break;
            }
            Matcher m=MARK.matcher(opening);
            while(m.find()){
                if(circled!=null&&circled.booleanValue()!=(m.group(1)!=null))continue;
                int n=m.group(1)==null?Integer.parseInt(m.group(2)):m.group(1).charAt(0)-'①'+1;
                String title=m.group(3).trim();
                if(n!=starts.size()+1||n>rows.size()||!title.matches(".*(?:工程|项目|合同|装饰|装修).*")||title.matches(".*(?:\\.{3}|…{2}).*"))continue;
                circled=m.group(1)!=null;starts.put(n,page.number());names.put(n,title);break;
            }
        }
        boolean complete=starts.size()==rows.size();
        for(Map<String,Object> row:rows){
            int n=(Integer)row.get("row");
            row.put("documentId",document.id());row.put("documentHash",document.sha256());row.put("documentVersion",document.version());row.put("inventoryPage",list.number());row.put("sourceIds",pageSources(corpus,document.id(),list.number()));
            row.put("inventoryIdentity",document.id()+":"+list.number()+":"+n);
            if(!starts.containsKey(n))continue;
            int first=starts.get(n);int last=starts.getOrDefault(n+1,complete?end+1:first+1)-1;
            row.put("proofPageStart",first);row.put("proofPageEnd",last);row.put("separatorTitle",names.get(n));
            row.put("rangeUse","NAVIGATION_ONLY");row.put("separatorSourceIds",pageSources(corpus,document.id(),first));
            List<Map<String,Object>> orders=new ArrayList<>();
            for(Page p:pages){
                if(p.number()<first||p.number()>last)continue;
                Matcher po=PO.matcher(Objects.toString(p.text(),""));Set<String> numbers=new LinkedHashSet<>();
                while(po.find())numbers.add(po.group());
                if(!numbers.isEmpty())orders.add(linked("page",p.number(),"orderNumbers",numbers));
            }
            row.put("orderPages",orders);
        }
    }

    static List<Map<String,Object>> layoutActions(List<Map<String,Object>> rows,String target,EvidenceCorpus corpus){
        Map<String,Map<String,Object>> actions=new LinkedHashMap<>();
        for(Map<String,Object> row:rows){
            if(row.containsKey("contractName")&&(!Boolean.TRUE.equals(row.get("personalDeclaration"))||"SOURCE_BOUND".equals(row.get("ownerBindingStatus"))))continue;
            List<String> ids=ProofVerifier.strings(row.get("sourceIds"));
            if(ids.isEmpty())continue;
            Source source=corpus.byId.get(ids.get(0));if(source==null)continue;
            actions.putIfAbsent(source.id(),linked("actionId","inventory-layout-"+identity(target+source.id()),
                "tool","read_table_record","targetId",target,"sourceId",source.id(),"documentId",source.evidence().documentId(),
                "documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"locationType",source.evidence().locationType(),
                "page",source.evidence().page(),"reasonCode","PERFORMANCE_ROW_FIELDS_REQUIRE_SOURCE_BOUND_COLUMNS"));
        }
        return new ArrayList<>(actions.values());
    }

    private static void bindLayout(List<Map<String,Object>> rows,List<Map<String,Object>> layout,EvidenceCorpus corpus){
        if(layout.isEmpty()||rows.isEmpty())return;
        List<String> ids=ProofVerifier.strings(rows.get(0).get("sourceIds"));
        Fact selector=new Fact("inventory","FIELD","","","","","","","UNRESOLVED",ids,Collections.emptyMap());
        List<Map<String,Object>> cells=cn.bidcheck.verification.facts.TableStructure.validatedCells(layout,LayoutBinding.sources(selector,corpus.byId));
        Map<String,Map<String,Object>> headers=new HashMap<>();
        for(Map<String,Object> cell:cells)if(Boolean.TRUE.equals(cell.get("header")))headers.put(str(cell,"cellId"),cell);
        // The native grid may omit a semantic header flag for a role label. Recover only
        // that explicit label on the proven ordinal-header row, within the same grid.
        List<Map<String,Object>> ownerHeaders=new ArrayList<>();
        for(Map<String,Object> cell:cells)if("PDFBOX_NATIVE_TABLE".equals(str(cell,"source"))&&clean(str(cell,"text")).matches("项目负责人|设计负责人")
            &&RunSession.number(cell.get("rowSpan"),1)==1&&RunSession.number(cell.get("columnSpan"),1)==1
            &&headers.values().stream().anyMatch(h->"序号".equals(clean(str(h,"text")))&&sameGrid(cell,h)&&Objects.equals(cell.get("row"),h.get("row"))))ownerHeaders.add(cell);
        Map<String,Map<String,String>> values=new LinkedHashMap<>();
        for(Map<String,Object> cell:cells){
            List<String> refs=ProofVerifier.strings(cell.get("headerCellIds"));
            Map<String,Object> header=refs.size()==1?headers.get(refs.get(0)):null;
            if(header==null&&refs.isEmpty()&&"PDFBOX_NATIVE_TABLE".equals(str(cell,"source"))&&RunSession.number(cell.get("rowSpan"),1)==1&&RunSession.number(cell.get("columnSpan"),1)==1){
                List<Map<String,Object>> aligned=new ArrayList<>();for(Map<String,Object> h:ownerHeaders)if(sameGrid(cell,h)&&Objects.equals(cell.get("column"),h.get("column"))&&RunSession.number(cell.get("row"),0)>RunSession.number(h.get("row"),0))aligned.add(h);
                if(aligned.size()==1)header=aligned.get(0);
            }
            if(header==null||!sameGrid(cell,header))continue;
            String label=clean(str(header,"text"));
            String key=str(cell,"tableId")+":"+str(cell,"frameId")+":"+cell.get("row");
            Map<String,String> fields=values.computeIfAbsent(key,k->new LinkedHashMap<>());
            if(fields.putIfAbsent(label,clean(str(cell,"text")))!=null)fields.put(label,"");
        }
        for(Map<String,Object> row:rows){
            List<Map<String,String>> matches=new ArrayList<>();
            for(Map<String,String> fields:values.values())if(String.valueOf(row.get("row")).equals(fields.get("序号")))matches.add(fields);
            if(matches.size()!=1)continue;
            Map<String,String> fields=matches.get(0);
            String name=fields.getOrDefault("合同名称",""),date=fields.getOrDefault("签订时间",fields.getOrDefault("签订日期",""));
            if(DATE.matcher(name).matches()&&date.matches(".*(?:合同|工程|项目|设计|装修).*")){
                String tmp=name;name=date;date=tmp;row.put("columnCorrection","DATE_AND_TITLE_SWAPPED_BY_VALUE_TYPE");
            }
            if(!name.isEmpty()&&!DATE.matcher(name).matches())row.put("contractName",name);
            Matcher d=DATE.matcher(date);
            if(d.matches())try{row.put("signedDate",java.time.LocalDate.of(Integer.parseInt(d.group(1)),Integer.parseInt(d.group(2)),Integer.parseInt(d.group(3))).toString());}catch(java.time.DateTimeException ignored){}
            Map<String,String> amounts=new LinkedHashMap<>();fields.forEach((k,v)->{if(k.contains("金额")||k.contains("建安费")||k.contains("设计费"))amounts.put(k,v);});
            row.put("declaredAmountFields",amounts);row.put("sourceBoundTableFields",fields);
        }
    }

    private static boolean sameGrid(Map<String,Object> a,Map<String,Object> b){
        for(String key:Arrays.asList("documentId","documentHash","documentVersion","locationType","location","tableId","frameId"))if(!Objects.equals(a.get(key),b.get(key)))return false;
        return !str(a,"tableId").isEmpty()&&!str(a,"frameId").isEmpty();
    }

    static boolean contains(Map<String,Object> row,Source source){
        if(source==null||!source.evidence().documentId().equals(row.get("documentId"))||
            !(row.get("proofPageStart") instanceof Number)||!(row.get("proofPageEnd") instanceof Number))return false;
        if(row.containsKey("documentHash")&&!source.documentHash().equals(str(row,"documentHash"))||row.containsKey("documentVersion")&&source.documentVersion()!=RunSession.number(row.get("documentVersion"),-1))return false;
        int page=source.evidence().page();
        return page>=((Number)row.get("proofPageStart")).intValue()&&page<=((Number)row.get("proofPageEnd")).intValue();
    }
    static boolean inventoryContains(Map<String,Object> row,Source source){
        return source!=null&&source.evidence().documentId().equals(str(row,"documentId"))
            &&source.evidence().page()==RunSession.number(row.get("inventoryPage"),-1)
            &&(!row.containsKey("documentHash")||source.documentHash().equals(str(row,"documentHash")))
            &&(!row.containsKey("documentVersion")||source.documentVersion()==RunSession.number(row.get("documentVersion"),-1));
    }

    /** A range can admit PO-only pages only after an original contract occurrence independently
     * matches one exact submitted title. Similar titles remain candidates, never automatic joins. */
    static Map<String,Object> packet(List<Fact> facts,List<Map<String,Object>> rows,EvidenceCorpus corpus){
        Set<String> names=new LinkedHashSet<>();Set<String> refs=new LinkedHashSet<>();
        for(Fact fact:facts){String name=clean(str(fact.attributes(),"contractName"));if(!name.isEmpty())names.add(name);refs.addAll(fact.sourceIds());}
        if(names.size()!=1||refs.isEmpty())return Collections.emptyMap();String name=names.iterator().next();
        List<Map<String,Object>> matches=new ArrayList<>();
        for(Map<String,Object> row:rows)if(name.equals(clean(str(row,"contractName")))&&name.equals(clean(str(row,"separatorTitle")))&&refs.stream().map(corpus.byId::get).allMatch(s->contains(row,s)))matches.add(row);
        return matches.size()==1?matches.get(0):Collections.emptyMap();
    }

    static List<Map<String,Object>> unverifiedChecks(List<Map<String,Object>> rows){
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows)result.add(linked("declaredRow",row,"contractIdentity",row.get("inventoryIdentity"),
            "sourceIds",row.get("sourceIds"),"contractNumbers",Collections.emptyList(),"counted",false,
            "missing",Collections.singletonList("申报记录已定位，需核对合同原件、签约主体、签订日期和适用金额口径")));
        return result;
    }

    private static List<String> pageSources(EvidenceCorpus corpus,String id,int page){
        List<String> ids=new ArrayList<>();
        for(Source source:corpus.all)if(source.evidence().documentId().equals(id)&&source.evidence().page()==page)ids.add(source.id());
        return ids;
    }
}
