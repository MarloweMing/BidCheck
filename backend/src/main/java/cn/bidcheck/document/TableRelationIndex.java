package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** A source-preserving interpretation of supplied HTML grids, never a claim of visual OCR accuracy. */
public final class TableRelationIndex {
    public static final String VERSION = "table-relations-3-verified-item-continuations";
    private static final int MAX_PAGE = 200_000, MAX_TABLE = 80_000, MAX_ROWS = 500, MAX_COLUMNS = 100, MAX_CELLS = 10_000;
    private static final Pattern TABLE_TAG = Pattern.compile("(?is)<(/?)table\\b[^<>]*>");
    private static final Pattern TAG = Pattern.compile("(?is)<(/?)([a-z][a-z0-9]*)([^<>]*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\s+([A-Za-z_:][-A-Za-z0-9_:]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private static final Pattern EMBEDDED_FIELD = Pattern.compile("^\\s*([^:：<>\\r\\n]{1,30})\\s*[:：]\\s*(\\S(?:[^<>]*\\S)?)\\s*$");
    // Field vocabulary is structural evidence only; adding a header never invents its values or comparison scope.
    private static final Set<String> PROCUREMENT_LABELS = Set.of("商品","物料编码","物料名称","计量单位","采购数量","净价","含税价","净价小计","价税小计","价税合计","订单号","型号","品牌","区域","项目描述","性能质量要求","条款名称","编列内容","参数","不含税单价","不含税价合计","最高不含税单价限价","不含税价合计最高限价","不含税单价基价","统一折扣(cm)报价","税率/征收率","建筑服务发生地","建筑项目名称","说明","在本项目担任职务");
    private static final Set<String> ENGINEERING_LABELS = Set.of("工程名称","建筑面积","层数/建筑面积","工程地点","建设地点","结构类型","工程造价","开竣工日期","完工日期","施工单位","设计单位","监理单位","工程内容及检查情况","验收意见","竣工验收内容","验收小组意见","项目","验收记录","验收结论","综合验收结论","项目负责人","技术负责人","项目技术负责人","企业名称","详细地址","建立时间","注册资本金","统一社会信用代码(或营业执照注册号)","经济性质","法定代表人","单位负责人","职称或执业资格");
    private static final Set<String> ADDITIONAL_LABELS = Set.of("在本项目中担任职务","拟任职务","担任职务","本项目职务","标段号","注册建造师证","《安全生产考核合格证书》(B/C证)","社保证明","姓名/名称","初领日期","应复审日期","作业类别","操作项目","有效期开始日期","有效期结束日期","实际复审日期","签发机关","招标编号","分项名称","不含增值税总价","含增值税总价","不含增值税合价","含增值税合价","不含增值税单价","含增值税单价","内容","信息","字段","信息项");
    private static final Set<String> HEADER_LABELS = Set.of("序号","序列","姓名","人员姓名","岗位","职务","岗位名称","身份证号码","身份证号","证件号码","证书编号","证书名称","资格证书名称","证书等级","有效期","有效期至","有效期限","有效起始日期","有效截止日期","性别","出生日期","年龄","学历","专业","所学专业","职称","技术职称","工作单位","受聘单位","毕业院校","社会保障号码","险种","起始年月","截止年月","本单位实际缴费月数","缴费情况","缴费基数","缴费年月","人员信息","基本信息","证件信息","证书信息","项目名称","项目编号","项目经理","建设单位","合同名称","合同编号","合同金额","签订日期","开工日期","竣工日期","项目地点","标段","标段名称","规格型号","设备名称","产品名称","单位","数量","单价","合价","金额","含税金额","不含税金额","税率","税额","投标报价","投标总价","工期","质量标准","备注","联系电话","驾驶证号码","档案编号","准驾车型","护照号码","Name","Role","Position","ID Number","Certificate Number","Certificate Type","Valid Until","No.");

    /** Offsets use Java UTF-16 indices into the unchanged page text; no geometry is invented. */
    public record Source(String documentId, int documentVersion, String documentHash, int page, String locationType, int start, int end) {
        public Map<String,Object> asMap() { return map("documentId",documentId,"documentVersion",documentVersion,"documentHash",documentHash,"page",page,"locationType",locationType,"start",start,"end",end); }
    }
    public record Cell(String id, int row, int column, int rowSpan, int columnSpan, String rawValue, String value, boolean header, Source source) { }
    public record HeaderContinuation(String method, String anchorTableId, String previousTableId, Source anchorTitleSource,
                                     List<Source> sequenceSources, List<Source> boundarySources, int firstSequence, int lastSequence) {
        public HeaderContinuation { sequenceSources=List.copyOf(sequenceSources);boundarySources=List.copyOf(boundarySources); }
        public Map<String,Object> asMap(){return map("method",method,"anchorTableId",anchorTableId,"previousTableId",previousTableId,
                "anchorTitleSource",anchorTitleSource.asMap(),"sequenceSources",sequenceSources.stream().map(Source::asMap).toList(),
                "boundarySources",boundarySources.stream().map(Source::asMap).toList(),"firstSequence",firstSequence,"lastSequence",lastSequence);}
    }
    public record Association(String tableId, int row, int column, String cellId, String label, List<String> headerPath,
                              String value, String rawValue, Source source, List<Source> headerSources, boolean inherited, String status,
                              HeaderContinuation headerContinuation) {
        public Association { headerPath=List.copyOf(headerPath); headerSources=List.copyOf(headerSources); }
        public Association(String tableId,int row,int column,String cellId,String label,List<String> headerPath,String value,String rawValue,Source source,List<Source> headerSources,boolean inherited,String status){this(tableId,row,column,cellId,label,headerPath,value,rawValue,source,headerSources,inherited,status,null);}
        public Map<String,Object> asMap() { Map<String,Object> out=map("version",VERSION,"tableId",tableId,"row",row,"column",column,"cellId",cellId,"label",label,"headerPath",headerPath,"source",source.asMap(),"headerSources",headerSources.stream().map(Source::asMap).toList(),"headerComposition",!headerPath.isEmpty()&&!label.equals(headerPath.get(headerPath.size()-1))?"EXPLICIT_FRAGMENTS_JOINED":"EXACT_LABEL","inherited",inherited,"status",status);if(headerContinuation!=null)out.put("headerContinuation",headerContinuation.asMap());return out; }
    }
    public record Row(String tableId, int row, int page, String locationType, int start, int end, int line, String status, List<Association> associations, String reviewReason) {
        public Row { associations=List.copyOf(associations); }
    }
    public record Table(String id, int page, String locationType, int start, int end, String title, boolean continuationMarker,
                        int columns, int headerRows, String headerSignature, List<Cell> cells, List<Row> rows, String structure) {
        public Table { cells=List.copyOf(cells); rows=List.copyOf(rows); }
    }
    public record Issue(String code, String reason, Source source) {
        public Map<String,Object> asMap(){return map("code",code,"reason",reason,"source",source.asMap(),"page",source.page(),"locationType",source.locationType(),"requiresReview",true);}
    }
    public record Continuation(String previousTableId, String tableId, String status, String reason) { }

    private final List<Table> tables;
    private final List<Issue> issues;
    private final List<Continuation> continuations;
    private TableRelationIndex(List<Table> tables, List<Issue> issues, List<Continuation> continuations) {
        this.tables=List.copyOf(tables); this.issues=List.copyOf(issues); this.continuations=List.copyOf(continuations);
    }
    public List<Table> tables(){return tables;}
    public List<Row> rows(){return tables.stream().flatMap(t->t.rows().stream()).toList();}
    public List<Issue> issues(){return issues;}
    public List<Continuation> continuations(){return continuations;}
    public Map<String,Object> summary(){
        List<Row> rows=rows();long resolved=rows.stream().filter(r->"RESOLVED".equals(r.status())).count();
        long physical=tables.stream().mapToLong(t->t.cells().stream().mapToInt(c->c.row()+c.rowSpan()-1).max().orElse(0)).sum();
        long headers=tables.stream().mapToLong(Table::headerRows).sum();Map<String,Long> reasons=new TreeMap<>();
        rows.stream().filter(r->!"RESOLVED".equals(r.status())).forEach(r->reasons.merge(r.reviewReason(),1L,Long::sum));
        return map("version",VERSION,"tables",tables.size(),"rows",rows.size(),"resolvedRows",resolved,"unresolvedRows",rows.size()-resolved,
                "physicalRows",physical,"headerRowsExcluded",headers,"layoutRowsExcluded",physical-headers-rows.size(),"unresolvedRowsByReason",reasons,
                "restoredHeaderRows",rows.stream().filter(r->r.associations().stream().anyMatch(a->a.headerContinuation()!=null)).count(),
                "restoredHeaderAssociations",rows.stream().flatMap(r->r.associations().stream()).filter(a->a.headerContinuation()!=null).count(),
                "issues",issues.stream().map(Issue::asMap).toList(),"continuations",continuations,"linkedContinuations",continuations.stream().filter(c->"LINKED".equals(c.status())).count(),
                "requiresReview",rows.size()>resolved||!issues.isEmpty()||continuations.stream().anyMatch(c->!"LINKED".equals(c.status())),"geometryAvailable",false);
    }

    public static TableRelationIndex build(Document document) {
        if(document==null||document.pages()==null)return new TableRelationIndex(List.of(),List.of(),List.of());
        List<Table> tables=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        for(Page page:document.pages()) { if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();TableRelationIndex result=analyze(document,page);tables.addAll(result.tables);issues.addAll(result.issues); }
        List<Continuation> links=new ArrayList<>();
        restoreVerifiedItemContinuations(document,tables,issues,links);
        for(Table current:tables) if(current.continuationMarker()&&links.stream().noneMatch(link->link.tableId().equals(current.id()))) {
            List<Table> previous=tables.stream().filter(t->t.page()==current.page()-1&&Objects.equals(t.locationType(),current.locationType())
                    &&!current.title().isBlank()&&current.title().equals(t.title())).toList();
            if(previous.size()==1&&!current.headerSignature().isBlank()&&current.headerSignature().equals(previous.get(0).headerSignature()))
                links.add(new Continuation(previous.get(0).id(),current.id(),"LINKED","显式续表标记、相同表名和完整表头一致；仅关联表格，不跨页继承人员或空值。"));
            else links.add(new Continuation("",current.id(),"UNRESOLVED","续表的唯一前表、表名或完整表头证据不足，保留独立页面。"));
        }
        return new TableRelationIndex(tables,issues,links);
    }

    private record ItemSequence(List<Row> data, Row total, int first, int last) { }
    private record ItemAnchor(Table table, Source title, List<Association> headers, int depth) { }

    /** Borrow column labels only across a demonstrably uninterrupted, numbered item list. */
    private static void restoreVerifiedItemContinuations(Document document,List<Table> tables,List<Issue> issues,List<Continuation> links){
        Map<String,ItemAnchor> anchors=new HashMap<>();
        for(int i=0;i<tables.size();i++){
            Table current=tables.get(i);ItemAnchor own=itemAnchor(document,current);
            if(own!=null){anchors.put(current.id(),own);continue;}
            if(i==0||current.headerRows()!=0||!"UNRESOLVED".equals(current.structure())||current.rows().stream().anyMatch(r->!"NO_VERIFIABLE_LOCAL_HEADER".equals(r.reviewReason())))continue;
            Table previous=tables.get(i-1);ItemAnchor anchor=anchors.get(previous.id());
            if(anchor==null||anchor.depth()>=32||current.columns()!=4||previous.columns()!=4)continue;
            List<Source> boundary=uninterruptedBoundary(document,previous,current,anchor.table().title());if(boundary==null)continue;
            ItemSequence before=itemSequence(previous,false),after=itemSequence(current,true);
            if(before==null||after==null||before.total()!=null||after.first()!=before.last()+1||after.data().size()<2)continue;
            Source previousNumber=before.data().get(before.data().size()-1).associations().get(0).source();
            Source firstNumber=after.data().get(0).associations().get(0).source(),lastNumber=after.data().get(after.data().size()-1).associations().get(0).source();
            HeaderContinuation proof=new HeaderContinuation("VERIFIED_NUMBERED_ITEM_LIST",anchor.table().id(),previous.id(),anchor.title(),
                    List.of(previousNumber,firstNumber,lastNumber),boundary,after.first(),after.last());
            Set<Integer> restore=new HashSet<>();after.data().forEach(row->restore.add(row.row()));List<Row> rows=new ArrayList<>();
            for(Row row:current.rows()){
                if(!restore.contains(row.row())){rows.add(row);continue;}
                List<Association> associations=new ArrayList<>();
                for(int column=0;column<4;column++){
                    Association value=row.associations().get(column),header=anchor.headers().get(column);
                    associations.add(new Association(value.tableId(),value.row(),value.column(),value.cellId(),header.label(),header.headerPath(),
                            value.value(),value.rawValue(),value.source(),header.headerSources(),false,"RESOLVED",proof));
                }
                rows.add(new Row(row.tableId(),row.row(),row.page(),row.locationType(),row.start(),row.end(),row.line(),"RESOLVED",associations,""));
            }
            Table restored=new Table(current.id(),current.page(),current.locationType(),current.start(),current.end(),current.title(),current.continuationMarker(),
                    current.columns(),current.headerRows(),current.headerSignature(),current.cells(),rows,"VERIFIED_HEADER_CONTINUATION");
            tables.set(i,restored);anchors.put(restored.id(),new ItemAnchor(anchor.table(),anchor.title(),anchor.headers(),anchor.depth()+1));
            if(after.total()==null)issues.removeIf(issue->issue.code().equals("TABLE_HEADER_UNRESOLVED")&&issue.source().page()==current.page()
                    &&issue.source().locationType().equals(current.locationType())&&issue.source().start()==current.start()&&issue.source().end()==current.end());
            links.add(new Continuation(previous.id(),current.id(),"LINKED","设备或物料清单的明确标题、完整原始表头、连续序号、逐列类型及无新章节的原文边界共同验证；仅恢复表头，不继承单元格值。"));
        }
    }

    private static ItemAnchor itemAnchor(Document document,Table table){
        if(table.columns()!=4||table.headerRows()!=1||!"COLUMN_HEADERS".equals(table.structure())
                ||!table.title().matches(".*(?:设备|产品|物料|材料).*(?:清单|明细表).*"))return null;
        ItemSequence sequence=itemSequence(table,false);if(sequence==null||sequence.first()!=1||sequence.data().size()<2)return null;
        List<Association> headers=sequence.data().get(0).associations();List<String> labels=headers.stream().map(a->a.label().replaceAll("[\\s\\p{Z}]+","")).toList();
        // No person, company, project identity or monetary column is eligible for automatic restoration.
        if(!Set.of("序号","序列").contains(labels.get(0))||!Set.of("设备名称","产品名称","物料名称").contains(labels.get(1))
                ||!Set.of("数量","采购数量").contains(labels.get(2))||!Set.of("型号","规格型号").contains(labels.get(3)))return null;
        if(headers.stream().anyMatch(a->!"RESOLVED".equals(a.status())||a.headerSources().size()!=1||a.headerSources().get(0).page()!=table.page()))return null;
        Page page=uniquePage(document,table);if(page==null)return null;
        int title=page.text().lastIndexOf(table.title(),table.start());
        if(title<Math.max(0,table.start()-300)||title+table.title().length()>table.start())return null;
        String gap=page.text().substring(title+table.title().length(),table.start());if(!gap.isBlank())return null;
        return new ItemAnchor(table,source(document,page,title,title+table.title().length()),headers,0);
    }

    private static ItemSequence itemSequence(Table table,boolean allowFinalTotal){
        if(table.columns()!=4||table.rows().isEmpty())return null;List<Row> data=new ArrayList<>();Row total=null;int first=0,last=0;
        for(Row row:table.rows()){
            List<Association> values=row.associations();if(values.size()!=4)return null;
            for(int c=0;c<4;c++){
                Association value=values.get(c);final int column=c+1;
                if(value.column()!=column||value.row()!=row.row()||value.inherited()||table.cells().stream().noneMatch(cell->cell.id().equals(value.cellId())&&cell.rowSpan()==1&&cell.columnSpan()==1&&cell.column()==column))return null;
            }
            String number=values.get(0).value(),name=values.get(1).value(),quantity=values.get(2).value();
            if(allowFinalTotal&&row==table.rows().get(table.rows().size()-1)&&number.isBlank()&&name.matches("(?:合计|总计)[:：]?")
                    &&quantity.matches("[0-9]+(?:\\.[0-9]+)?")&&values.get(3).value().isBlank()){total=row;continue;}
            if(!number.matches("[1-9][0-9]{0,5}")||name.isBlank()||knownHeader(name)||name.matches("[0-9.,]+")||!quantity.matches("[0-9]+(?:\\.[0-9]+)?"))return null;
            int ordinal=Integer.parseInt(number);if(!data.isEmpty()&&ordinal!=last+1)return null;
            if(data.isEmpty())first=ordinal;last=ordinal;data.add(row);
        }
        return data.isEmpty()?null:new ItemSequence(List.copyOf(data),total,first,last);
    }

    private static Page uniquePage(Document document,Table table){
        List<Page> candidates=document.pages().stream().filter(p->p.number()==table.page()&&Objects.equals(Objects.toString(p.locationType(),"PAGE"),table.locationType())).toList();
        return candidates.size()==1?candidates.get(0):null;
    }
    private static List<Source> uninterruptedBoundary(Document document,Table previous,Table current,String title){
        if(!previous.locationType().equals(current.locationType())||current.page()!=previous.page()+1)return null;
        Page before=uniquePage(document,previous),after=uniquePage(document,current);if(before==null||after==null)return null;
        if(document.pages().indexOf(after)!=document.pages().indexOf(before)+1)return null;
        String suffix=before.text().substring(previous.end()),prefix=after.text().substring(0,current.start());
        if(!paginationOnly(suffix,before.number(),"",false)||!paginationOnly(prefix,after.number(),title,current.continuationMarker()&&current.title().equals(title)))return null;
        return List.of(source(document,before,previous.end(),before.text().length()),source(document,after,0,current.start()));
    }
    private static boolean paginationOnly(String text,int page,String title,boolean explicitContinuation){
        for(String line:text.lines().map(String::strip).filter(value->!value.isBlank()).toList()){
            if(line.equals(Integer.toString(page))||line.equals("【PDF识别文字】")||line.matches("第\\s*[0-9]+\\s*页(?:\\s*共\\s*[0-9]+\\s*页)?"))continue;
            String heading=line.replaceAll("^#{1,6}\\s*","").replaceAll("[（(]续[）)]|续表|接上表|(?i)continued","").strip();
            if(explicitContinuation&&heading.equals(title))continue;
            return false;
        }return true;
    }

    /** Analyze a single source page; used by consistency without changing the OCR preparation identity. */
    public static TableRelationIndex analyze(Document document, Page page) {
        if(page==null||page.text()==null||!page.text().toLowerCase(Locale.ROOT).contains("<table"))return new TableRelationIndex(List.of(),List.of(),List.of());
        List<Table> tables=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        if(document==null||document.pages()==null||document.pages().stream().noneMatch(p->p.number()==page.number()&&Objects.equals(p.locationType(),page.locationType())&&Objects.equals(p.text(),page.text())))
            return new TableRelationIndex(List.of(),List.of(new Issue("TABLE_SOURCE_NOT_BOUND","表格未绑定当前文档原文。",source(document,page,0,page.text().length()))),List.of());
        if(page.text().length()>MAX_PAGE)return new TableRelationIndex(List.of(),List.of(new Issue("TABLE_PAGE_LIMIT","表格页超出结构解析长度限制。",source(document,page,0,page.text().length()))),List.of());
        if(Objects.toString(page.warning(),"").matches("(?s).*(?:OCR未完成|OCR_DEFERRED).*"))
            return new TableRelationIndex(List.of(),List.of(new Issue("TABLE_SOURCE_INCOMPLETE","页面文字尚未完成识别，表格关系待核。",source(document,page,0,page.text().length()))),List.of());
        Matcher matcher=TABLE_TAG.matcher(page.text()); int start=-1,depth=0;boolean nested=false;
        while(matcher.find()){
            if(matcher.group(1).isEmpty()){if(depth==0)start=matcher.start();else nested=true;depth++;}
            else if(depth==0)issues.add(new Issue("TABLE_UNBALANCED","表格闭合标记缺少对应起始标记。",source(document,page,matcher.start(),matcher.end())));
            else if(--depth==0){
                int end=matcher.end();
                if(nested)issues.add(new Issue("TABLE_NESTED","嵌套表格边界待核，未猜测行列关系。",source(document,page,start,end)));
                else try{tables.add(parse(document,page,start,end,issues));}catch(Invalid e){issues.add(new Issue(e.code,e.getMessage(),source(document,page,start,end)));}
                start=-1;nested=false;
            }
        }
        if(depth>0)issues.add(new Issue("TABLE_UNCLOSED","表格未闭合，未将零散单元格关联到人员或项目。",source(document,page,start,page.text().length())));
        return new TableRelationIndex(tables,issues,List.of());
    }

    private static Table parse(Document document,Page page,int start,int end,List<Issue> issues){
        String html=page.text().substring(start,end);if(html.length()>MAX_TABLE)throw invalid("TABLE_LIMIT","单表超出结构解析长度限制。");
        List<List<MutableCell>> rawRows=new ArrayList<>();List<Integer> rowStarts=new ArrayList<>(),rowEnds=new ArrayList<>();
        Deque<String> stack=new ArrayDeque<>();Matcher tags=TAG.matcher(html);MutableCell pending=null;int at=0,cellCount=0,captionStart=-1;String caption="";
        while(tags.find()){
            String between=html.substring(at,tags.start());
            if(pending==null&&captionStart<0&&!between.isBlank()||between.contains("<")||between.contains(">"))throw invalid("TABLE_MARKUP_INVALID","表格结构中存在无法定位的内容。");
            String tag=tags.group(2).toLowerCase(Locale.ROOT),tail=tags.group(3);boolean closing=!tags.group(1).isEmpty();
            if(tag.equals("br")&&!closing&&pending!=null&&tail.matches("\\s*/?\\s*")){at=tags.end();continue;}
            if(!Set.of("table","caption","thead","tbody","tfoot","tr","td","th").contains(tag))throw invalid("TABLE_TAG_UNSUPPORTED","表格含不支持的内嵌标记，需回查原文。");
            if(closing){
                if(!tail.isBlank()||stack.isEmpty()||!stack.pop().equals(tag))throw invalid("TABLE_UNBALANCED","表格标签嵌套或闭合不完整。");
                if(tag.equals("td")||tag.equals("th")){if(pending==null)throw invalid("TABLE_UNBALANCED","单元格边界不完整。");pending.end=start+tags.start();pending.raw=page.text().substring(pending.start,pending.end);pending.value=visible(pending.raw);rawRows.get(rawRows.size()-1).add(pending);pending=null;}
                if(tag.equals("tr"))rowEnds.add(start+tags.end());
                if(tag.equals("caption")){caption=visible(html.substring(captionStart,tags.start()));captionStart=-1;}
            }else{
                Map<String,String> attributes=attributes(tail);String parent=Objects.toString(stack.peek(),"");
                switch(tag){
                    case "table" -> {if(!parent.isEmpty())throw invalid("TABLE_NESTED","嵌套表格未展开。");}
                    case "caption" -> {if(!parent.equals("table")||captionStart>=0||!caption.isBlank()||!rawRows.isEmpty())throw invalid("TABLE_CAPTION_INVALID","表名位置不明确。");captionStart=tags.end();}
                    case "thead","tbody","tfoot" -> {if(!parent.equals("table"))throw invalid("TABLE_UNBALANCED","行组标签位置不正确。");}
                    case "tr" -> {if(!Set.of("table","thead","tbody","tfoot").contains(parent)||rawRows.size()>=MAX_ROWS)throw invalid("TABLE_ROW_LIMIT","行组结构或行数超出可验证范围。");rawRows.add(new ArrayList<>());rowStarts.add(start+tags.start());}
                    case "td","th" -> {if(!parent.equals("tr")||pending!=null||++cellCount>MAX_CELLS)throw invalid("TABLE_CELL_LIMIT","单元格位置或数量超出可验证范围。");pending=new MutableCell(rawRows.size()-1,span(attributes,"rowspan",MAX_ROWS),span(attributes,"colspan",MAX_COLUMNS),start+tags.end(),tag.equals("th")||stack.contains("thead"));}
                }
                if(!Set.of("td","th").contains(tag)&&(attributes.containsKey("rowspan")||attributes.containsKey("colspan")))throw invalid("TABLE_SPAN_INVALID","跨度属性不在单元格上。");
                stack.push(tag);
            }
            at=tags.end();
        }
        if(!stack.isEmpty()||pending!=null||!html.substring(at).isBlank()||rawRows.isEmpty())throw invalid("TABLE_UNBALANCED","表格未完整闭合或没有行。");
        MutableCell[][] grid=new MutableCell[rawRows.size()][MAX_COLUMNS];int columns=0;
        for(int r=0;r<rawRows.size();r++){int c=0;for(MutableCell cell:rawRows.get(r)){
            while(c<MAX_COLUMNS&&grid[r][c]!=null)c++;
            if(c+cell.columnSpan>MAX_COLUMNS||r+cell.rowSpan>rawRows.size())throw invalid("TABLE_SPAN_OUTSIDE","合并单元格跨度越出表格，未沿用不完整身份。");
            cell.column=c;
            for(int rr=r;rr<r+cell.rowSpan;rr++)for(int cc=c;cc<c+cell.columnSpan;cc++){if(grid[rr][cc]!=null)throw invalid("TABLE_SPAN_COLLISION","合并单元格发生重叠，行列归属待核。");grid[rr][cc]=cell;}
            c+=cell.columnSpan;columns=Math.max(columns,c);
        }}
        if(columns==0)throw invalid("TABLE_EMPTY_ROW","表格没有可验证的单元格。");
        for(MutableCell[] row:grid)for(int c=0;c<columns;c++)if(row[c]==null)throw invalid("TABLE_GRID_GAP","表格行列缺格或列数不一致，未猜测空缺内容。");
        // Each locally explicit header starts a new section. No column meaning is borrowed from another table/page.
        Map<Integer,HeaderBlock> blocks=new LinkedHashMap<>();Set<MutableCell> headerCells=Collections.newSetFromMap(new IdentityHashMap<>());
        int headerRows=0;
        for(int r=0;r<grid.length;){HeaderBlock block=headerBlock(grid,r,columns);
            if(block==null){r++;continue;}blocks.put(r,block);headerRows+=block.end()-block.start();
            for(List<MutableCell> path:block.paths())headerCells.addAll(path);r=block.end();
        }
        String id="table-"+digest(document.id()+"|"+document.version()+"|"+document.sha256()+"|"+page.locationType()+"|"+page.number()+"|"+start);
        Map<MutableCell,Cell> converted=new IdentityHashMap<>();List<Cell> cells=new ArrayList<>();
        for(List<MutableCell> row:rawRows)for(MutableCell cell:row){Cell value=new Cell(id+":r"+(cell.row+1)+":c"+(cell.column+1),cell.row+1,cell.column+1,cell.rowSpan,cell.columnSpan,cell.raw,cell.value,headerCells.contains(cell),source(document,page,cell.start,cell.end));converted.put(cell,value);cells.add(value);}
        boolean keyValue=rawRows.stream().filter(TableRelationIndex::keyValueRow).count()>=2;
        if(headerRows==0&&!keyValue)issues.add(new Issue("TABLE_HEADER_UNRESOLVED","未识别完整表头；保留单元格，禁止猜测字段含义。",source(document,page,start,end)));
        String signature=headerRows==0?"":digest(blocks.values().stream().map(b->b.paths().stream().map(p->p.stream().map(c->c.value).toList()).toList()).toList().toString());
        List<Row> rows=new ArrayList<>();HeaderBlock active=null;int firstHeader=blocks.keySet().stream().findFirst().orElse(-1);
        for(int r=0;r<grid.length;r++){
            if(blocks.containsKey(r)){active=blocks.get(r);r=active.end()-1;continue;}
            List<MutableCell> physical=rawRows.get(r);
            if(keyValue&&keyValueRow(physical)){
                List<Association> fields=new ArrayList<>();
                for(int c=0;c<physical.size();c+=2){MutableCell label=physical.get(c),value=physical.get(c+1);Cell target=converted.get(value);fields.add(new Association(id,r+1,value.column+1,target.id(),label.value,List.of(label.value),value.value,value.raw,target.source(),List.of(converted.get(label).source()),false,"RESOLVED"));}
                rows.add(new Row(id,r+1,page.number(),page.locationType(),rowStarts.get(r),rowEnds.get(r),line(page.text(),rowStarts.get(r)),"RESOLVED",fields,""));continue;
            }
            if(active==null||keyValue){
                if(keyValue&&physical.size()==1&&physical.get(0).columnSpan==columns&&physical.get(0).value.matches("^(?:备注|说明)[：:].*"))continue;
                List<Association> fields=embeddedFields(document,page,id,r,rawRows.get(r),converted);
                if(!fields.isEmpty()){rows.add(new Row(id,r+1,page.number(),page.locationType(),rowStarts.get(r),rowEnds.get(r),line(page.text(),rowStarts.get(r)),"RESOLVED",fields,""));continue;}
                if(firstHeader>r&&metadataRow(physical,columns))continue;
            }
            List<List<MutableCell>> paths=active==null?Collections.nCopies(columns,List.of()):active.paths();
            Set<MutableCell> splitFields=splitFieldCells(grid[r],paths);
            List<Association> associations=new ArrayList<>();Set<MutableCell> seen=Collections.newSetFromMap(new IdentityHashMap<>());int rowStart=rowStarts.get(r);boolean identityAmbiguous=identitySpanConflict(grid,r,paths),resolved=active!=null&&!identityAmbiguous;
            for(int c=0;c<columns;c++){
                MutableCell cell=grid[r][c];if(!seen.add(cell))continue;Cell value=converted.get(cell);List<MutableCell> path=paths.get(c);boolean ambiguous=headerCells.contains(cell)||identityAmbiguous||splitFields.contains(cell);
                for(int cc=c;cc<c+cell.columnSpan;cc++)if(!paths.get(cc).equals(path))ambiguous=true;
                String status=ambiguous?"AMBIGUOUS":path.isEmpty()?"UNRESOLVED":"RESOLVED";resolved&=status.equals("RESOLVED");rowStart=Math.min(rowStart,cell.start);
                associations.add(new Association(id,r+1,c+1,value.id(),active==null?"":active.labels().get(c),path.stream().map(h->h.value).toList(),value.value(),value.rawValue(),value.source(),path.stream().map(h->converted.get(h).source()).toList(),cell.row<r,status));
            }
            String reason=resolved?"":identityAmbiguous?"IDENTITY_SPAN_CONFLICT":!splitFields.isEmpty()?"DATA_CELLS_SPLIT_ONE_FIELD":active!=null?"MERGED_FIELDS_OR_HEADER_BOUNDARY":columns==1?"SINGLE_COLUMN_LAYOUT":keyValue?"KEY_VALUE_STRUCTURE_UNRESOLVED":"NO_VERIFIABLE_LOCAL_HEADER";
            rows.add(new Row(id,r+1,page.number(),page.locationType(),rowStart,rowEnds.get(r),line(page.text(),rowStarts.get(r)),resolved?"RESOLVED":"UNRESOLVED",associations,reason));
        }
        if(rows.stream().anyMatch(r->!"RESOLVED".equals(r.status()))&&(headerRows>0||keyValue))issues.add(new Issue("TABLE_ASSOCIATION_AMBIGUOUS","存在跨字段合并、身份合并范围冲突或混入表头，相关关系待核。",source(document,page,start,end)));
        String preceding=page.text().substring(Math.max(0,start-300),start).lines().map(String::strip).filter(s->!s.isBlank()).reduce((a,b)->b).orElse("");
        String title=caption.isBlank()?preceding:caption;boolean continuation=title.matches("(?s).*(?:[（(]续[）)]|续表|接上表|continued).*?");
        title=title.replaceAll("[（(]续[）)]|续表|接上表|(?i)continued","").replaceAll("^#{1,6}\\s*","").strip();
        if(title.length()>120||title.contains("<")||!title.matches(".*(?:表|清单|名册|Table|TABLE).*"))title="";
        int width=columns;
        boolean keyValueOnly=keyValue&&(headerRows==0||blocks.values().stream().allMatch(b->width==2&&Set.of("字段","项目","信息项","属性","Field").contains(b.labels().get(0))&&Set.of("内容","信息","值","说明","Value","Details").contains(b.labels().get(1))));
        return new Table(id,page.number(),page.locationType(),start,end,title,continuation,columns,headerRows,signature,cells,rows,keyValueOnly?"KEY_VALUE_PAIRS":headerRows>0?"COLUMN_HEADERS":"UNRESOLVED");
    }
    private record HeaderBlock(int start,int end,List<List<MutableCell>> paths,List<String> labels) { }
    private static HeaderBlock headerBlock(MutableCell[][] grid,int start,int columns){
        int end=start;boolean split=false;
        if(headerRow(grid[start],columns)){while(end<grid.length&&headerRow(grid[end],columns))end++;}
        else if(start+1<grid.length&&splitHeader(grid,start,columns)){end=start+2;split=true;}
        else if(start+1<grid.length&&groupedHeader(grid,start,columns))end=start+2;
        if(end==start)return null;
        List<List<MutableCell>> paths=new ArrayList<>();List<String> labels=new ArrayList<>();
        for(int c=0;c<columns;c++){
            List<MutableCell> path=new ArrayList<>();
            for(int r=start;r<end;r++){MutableCell cell=grid[r][c];
                if(cell.row<start)return null; // A prior data-row span is not a new header.
                if(cell.row+cell.rowSpan>end)throw invalid("TABLE_HEADER_SPAN_AMBIGUOUS","表头合并范围进入数据区，字段归属待核。");
                if(!path.contains(cell)&&!cell.value.isBlank())path.add(cell);
            }
            paths.add(List.copyOf(path));
            labels.add(split?path.stream().map(cell->cell.value).reduce("",String::concat).replaceAll("[\\s\\p{Z}]+",""):path.isEmpty()?"":path.get(path.size()-1).value);
        }
        return new HeaderBlock(start,end,List.copyOf(paths),List.copyOf(labels));
    }
    private static boolean splitHeader(MutableCell[][] grid,int start,int columns){
        if(columns<2)return false;boolean fragment=false;
        for(int c=0;c<columns;c++){
            MutableCell upper=grid[start][c],lower=grid[start+1][c];
            // Only aligned physical fragments can be concatenated. Never repair a span or missing cell.
            if(upper.row!=start||lower.row!=start+1||upper.rowSpan!=1||lower.rowSpan!=1||upper.columnSpan!=1||lower.columnSpan!=1)return false;
            String joined=upper.value+lower.value;if(!knownHeader(joined))return false;
            if(!upper.value.isBlank()&&!lower.value.isBlank()){
                if(knownHeader(upper.value)||knownHeader(lower.value))return false;
                fragment=true;
            }
        }
        return fragment;
    }
    private static boolean groupedHeader(MutableCell[][] grid,int start,int columns){
        if(columns<2||!headerRow(grid[start+1],columns))return false;boolean group=false;
        for(int c=0;c<columns;c++){
            MutableCell cell=grid[start][c];
            if(cell.row!=start||cell.value.isBlank()||cell.value.length()>60)return false;
            if(cell.columnSpan>1&&cell.rowSpan==1){
                // A parent label is accepted only above independently explicit child headers.
                if(cell.value.matches("(?s).*[0-9:：=].*"))return false;
                group=true;
            }else if(!knownHeader(cell.value))return false;
        }
        return group;
    }
    private static boolean headerRow(MutableCell[] row,int columns){boolean explicit=true,known=columns>=2;Set<MutableCell> distinct=Collections.newSetFromMap(new IdentityHashMap<>());for(int c=0;c<columns;c++){explicit&=row[c].header;known&=knownHeader(row[c].value);distinct.add(row[c]);}return explicit||known&&distinct.size()>=2;}
    private static Set<MutableCell> splitFieldCells(MutableCell[] row,List<List<MutableCell>> paths){
        Map<MutableCell,Set<MutableCell>> byLeaf=new IdentityHashMap<>();
        for(int c=0;c<paths.size();c++)if(!paths.get(c).isEmpty())byLeaf.computeIfAbsent(paths.get(c).get(paths.get(c).size()-1),k->Collections.newSetFromMap(new IdentityHashMap<>())).add(row[c]);
        Set<MutableCell> ambiguous=Collections.newSetFromMap(new IdentityHashMap<>());byLeaf.values().stream().filter(cells->cells.size()>1).forEach(ambiguous::addAll);return ambiguous;
    }
    private static boolean keyValueRow(List<MutableCell> cells){if(cells.size()<2||cells.size()%2!=0)return false;for(int c=0;c<cells.size();c+=2)if(cells.get(c).rowSpan!=1||cells.get(c).columnSpan!=1||cells.get(c+1).rowSpan!=1||!knownHeader(cells.get(c).value)||knownHeader(cells.get(c+1).value))return false;return true;}
    private static boolean metadataRow(List<MutableCell> cells,int columns){
        List<MutableCell> nonblank=cells.stream().filter(c->!c.value.isBlank()).toList();if(nonblank.isEmpty())return true;
        if(nonblank.size()==1&&nonblank.get(0).columnSpan>1&&nonblank.get(0).value.length()<=120&&nonblank.get(0).value.matches(".*(?:表|清单)(?:[-—].*)?"))return true;
        return nonblank.stream().allMatch(c->{Matcher match=EMBEDDED_FIELD.matcher(c.raw);return c.rowSpan==1&&match.matches()&&knownHeader(match.group(1));});
    }
    private static List<Association> embeddedFields(Document document,Page page,String tableId,int row,List<MutableCell> cells,Map<MutableCell,Cell> converted){
        List<Association> out=new ArrayList<>();for(MutableCell cell:cells){Matcher match=EMBEDDED_FIELD.matcher(cell.raw);if(!match.matches()||!knownHeader(match.group(1)))continue;
            String label=match.group(1).strip(),raw=match.group(2);Source value=source(document,page,cell.start+match.start(2),cell.start+match.end(2)),header=source(document,page,cell.start+match.start(1),cell.start+match.end(1));
            out.add(new Association(tableId,row+1,cell.column+1,converted.get(cell).id(),label,List.of(label),visible(raw),raw,value,List.of(header),false,"RESOLVED"));
        }return out;
    }
    private static boolean identitySpanConflict(MutableCell[][] grid,int row,List<List<MutableCell>> paths){
        List<Integer> names=new ArrayList<>(),numbers=new ArrayList<>();
        for(int c=0;c<paths.size();c++)if(!paths.get(c).isEmpty()){
            String label=paths.get(c).get(paths.get(c).size()-1).value.replaceAll("[\\s\\p{Z}]+","");
            if(Set.of("姓名","人员姓名").contains(label))names.add(c);
            if(Set.of("身份证号码","身份证号","公民身份号码","公民身份证号码","社会保障号码","社会保障号").contains(label))numbers.add(c);
        }
        for(int name:names)for(int number:numbers){MutableCell n=grid[row][name],id=grid[row][number];
            if(n.rowSpan>1&&valuesWithin(grid,n,number).size()!=1||id.rowSpan>1&&valuesWithin(grid,id,name).size()!=1)return true;
        }
        return false;
    }
    private static Set<String> valuesWithin(MutableCell[][] grid,MutableCell owner,int column){Set<String> values=new HashSet<>();for(int r=owner.row;r<owner.row+owner.rowSpan;r++)values.add(grid[r][column].value.replaceAll("[\\s\\p{Z}]+",""));return values;}
    private static boolean knownHeader(String value){String normalized=value.replaceAll("[\\s\\p{Z}]+","").replaceAll("[：:]$","").replace('（','(').replace('）',')');String withoutUnits=normalized.replaceAll("[（(](?:亿元|万元|元|人民币元|含税|不含税)[）)]","");return HEADER_LABELS.contains(normalized)||HEADER_LABELS.contains(value)||ADDITIONAL_LABELS.contains(normalized)||HEADER_LABELS.contains(withoutUnits)||ADDITIONAL_LABELS.contains(withoutUnits)||PROCUREMENT_LABELS.contains(normalized)||PROCUREMENT_LABELS.contains(withoutUnits)||ENGINEERING_LABELS.contains(normalized)||ENGINEERING_LABELS.contains(withoutUnits);}
    private static Map<String,String> attributes(String text){Map<String,String> values=new HashMap<>();Matcher matcher=ATTRIBUTE.matcher(text);int at=0;while(matcher.find()){if(matcher.start()!=at)throw invalid("TABLE_ATTRIBUTE_INVALID","属性格式无法验证。");String key=matcher.group(1).toLowerCase(Locale.ROOT),value=Objects.toString(matcher.group(2),Objects.toString(matcher.group(3),Objects.toString(matcher.group(4),"")));if(values.putIfAbsent(key,value)!=null)throw invalid("TABLE_ATTRIBUTE_INVALID","重复属性无法确定跨度。");at=matcher.end();}if(!text.substring(at).isBlank())throw invalid("TABLE_ATTRIBUTE_INVALID","属性格式无法验证。");return values;}
    private static int span(Map<String,String> attributes,String key,int maximum){String value=attributes.getOrDefault(key,"1");if(!value.matches("[1-9][0-9]{0,3}"))throw invalid("TABLE_SPAN_INVALID","单元格跨度不是有效正整数。");int span=Integer.parseInt(value);if(span>maximum)throw invalid("TABLE_SPAN_LIMIT","单元格跨度超出限制。");return span;}
    private static String visible(String raw){String text=raw.replaceAll("(?i)<br\\s*/?>"," ");if(text.contains("<")||text.contains(">")||text.indexOf('\0')>=0)throw invalid("TABLE_CELL_MARKUP_UNSUPPORTED","单元格包含不能解释为纯文本的结构。");return text.replace("&nbsp;"," ").replace("&#160;"," ").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&apos;","'").replace("&amp;","&").replaceAll("[\\s\\p{Z}]+"," ").strip();}
    private static Source source(Document document,Page page,int start,int end){return new Source(document==null?"":Objects.toString(document.id(),""),document==null?0:document.version(),document==null?"":Objects.toString(document.sha256(),""),page.number(),Objects.toString(page.locationType(),"PAGE"),start,end);}
    private static int line(String text,int offset){int line=1;for(int i=0;i<offset;i++)if(text.charAt(i)=='\n')line++;return line;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int n=0;n<values.length;n+=2)out.put(values[n].toString(),values[n+1]);return out;}
    private static Invalid invalid(String code,String reason){return new Invalid(code,reason);}
    private static final class Invalid extends RuntimeException{final String code;Invalid(String code,String reason){super(reason);this.code=code;}}
    private static final class MutableCell{final int row,rowSpan,columnSpan,start;final boolean header;int column,end;String raw="",value="";MutableCell(int row,int rowSpan,int columnSpan,int start,boolean header){this.row=row;this.rowSpan=rowSpan;this.columnSpan=columnSpan;this.start=start;this.header=header;}}
}
