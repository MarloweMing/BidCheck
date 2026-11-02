package cn.bidcheck.verification.facts;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Reconstructs bounded table records from source-backed cells. A model's row or continuation claim is never an identity. */
public final class TableStructure {
    public static final String VERSION="table-source-binding-1";
    private static final Set<String> NATIVE=Set.of("OOXML_NATIVE","ASPOSE_WORDS_NATIVE","PDFBOX_NATIVE_TABLE");
    private TableStructure(){}
    public record Row(String documentId,String hash,int version,String locationType,int location,String tableId,String frameId,int row,
                      String name,String identifierFingerprint,boolean strongIdentity,boolean nativeStructure,String employer,
                      List<String> months,Map<String,String> fields,List<String> cellIds,List<String> sourceIds,String headerSignature,String recordKey){
        public Map<String,Object> proof(){return of("documentId",documentId,"documentHash",hash,"documentVersion",version,"locationType",locationType,"location",location,"tableId",tableId,"frameId",frameId,"row",row,"subject",name,"identityStrength",strongIdentity?"STRONG":"SOURCE_LOCAL","identifierFingerprint",identifierFingerprint,"employer",employer,"months",months,"cellIds",cellIds,"sourceIds",sourceIds,"headerSignature",headerSignature,"recordKey",recordKey,"structureOrigin",nativeStructure?"NATIVE_TABLE":"OCR_SOURCE_LOCAL");}
    }
    public record Result(List<Row> rows,String issue,List<Map<String,Object>> links){
        public boolean valid(){return issue.isBlank()&&!rows.isEmpty();}
        public Map<String,Object> proof(){return of("version",VERSION,"status",valid()?"SOURCE_BOUND":"UNDETERMINED","issue",issue,"records",rows.stream().map(Row::proof).toList(),"links",links);}
    }
    private record Cell(Map<String,Object> data,List<String> sourceIds){
        String id(){return TableStructure.value(data,"cellId");}String doc(){return TableStructure.value(data,"documentId");}String table(){return TableStructure.value(data,"tableId");}
        String text(){return TableStructure.value(data,"text");}int row(){return integer(data.get("row"),0);}int col(){return integer(data.get("column"),0);}
        int rowSpan(){return integer(data.get("rowSpan"),1);}int colSpan(){return integer(data.get("columnSpan"),1);}
        String group(){return canonical(List.of(doc(),TableStructure.value(data,"documentHash"),TableStructure.value(data,"documentVersion"),table(),TableStructure.value(data,"frameId"),nativeCell()&&!"PDFBOX_NATIVE_TABLE".equals(TableStructure.value(data,"source"))?"":TableStructure.value(data,"locationType")+":"+TableStructure.value(data,"location")));}
        boolean nativeCell(){return NATIVE.contains(TableStructure.value(data,"source"));}
        boolean keyValue(){return "KEY_VALUE".equals(TableStructure.value(data,"tableType"));}
    }
    private static String value(Map<String,Object> data,String key){return text(data,key);}
    public static Result analyze(String name,List<String> requestedCellIds,List<Map<String,Object>> layout,List<Map<String,Object>> sources){
        if(name==null||name.isBlank())return new Result(List.of(),"STRUCTURE_SUBJECT_REQUIRED",List.of());
        List<Cell> cells=new ArrayList<>();for(Map<String,Object> raw:validatedCells(layout,sources))cells.add(new Cell(raw,strings(raw.get("_sourceIds"))));
        Set<String> requested=new HashSet<>(requestedCellIds==null?List.of():requestedCellIds);List<Row> records=new ArrayList<>();
        for(Cell owner:cells){if(!clean(owner.text()).equals(clean(name))||owner.colSpan()!=1&&!owner.keyValue())continue;List<Cell> nameHeaders=headers(owner,cells);if(nameHeaders.size()!=1||!label(nameHeaders.get(0).text()).equals("NAME"))continue;
            boolean keyValue=owner.keyValue()&&nameHeaders.get(0).row()<=owner.row()&&nameHeaders.get(0).col()<owner.col();
            if(keyValue&&cells.stream().filter(c->c.group().equals(owner.group())&&!isHeader(c)).filter(c->{List<Cell> h=headers(c,cells);return h.size()==1&&label(h.get(0).text()).equals("NAME");}).count()!=1)return new Result(records,"STRUCTURE_KEY_VALUE_OWNER_AMBIGUOUS",List.of());
            for(int row=owner.row();row<(keyValue?owner.row()+1:owner.row()+owner.rowSpan())&&row<owner.row()+100;row++){
                final int recordRow=row;List<Cell> members=cells.stream().filter(c->c.group().equals(owner.group())&&(keyValue||c.row()<=recordRow&&c.row()+c.rowSpan()>recordRow)&&!isHeader(c)).toList();
                if(!requested.isEmpty()&&members.stream().noneMatch(c->requested.contains(c.id())))continue;
                Map<String,String> fields=new LinkedHashMap<>();Map<String,Cell> fieldsAt=new LinkedHashMap<>();List<Cell> used=new ArrayList<>(List.of(owner,nameHeaders.get(0)));boolean ambiguous=false;
                for(Cell cell:members){if(cell.colSpan()!=1&&!keyValue)continue;List<Cell> heading=headers(cell,cells);if(heading.size()!=1)continue;String key=label(heading.get(0).text());if(key.isBlank()||key.equals("NAME"))continue;
                    if(fields.containsKey(key)&&!clean(fields.get(key)).equals(clean(cell.text()))){ambiguous=true;break;}fields.put(key,cell.text().strip());fieldsAt.put(key,cell);used.add(cell);used.add(heading.get(0));
                    fields.put("label:"+heading.get(0).text().strip(),cell.text().strip());
                }
                if(ambiguous)return new Result(records,"STRUCTURE_COLUMN_MAPPING_AMBIGUOUS",List.of());
                String rawId=fields.getOrDefault("CN_ID","");String idValue=rawId.replaceAll("[\\s\\p{Z}]+","").toUpperCase(Locale.ROOT);
                // Only fragments inside this one source-bound identity cell may be joined.
                // Never read an adjacent role cell, strip arbitrary punctuation or borrow digits.
                if(!rawId.isBlank()&&(!idValue.matches("[0-9]{17}[0-9X]")||!rawId.strip().equalsIgnoreCase(idValue)&&!validIdentifier(idValue)))return new Result(records,"STRUCTURE_IDENTITY_FIELD_INVALID",List.of());
                String idFingerprint=idValue.matches("[0-9]{17}[0-9X]")?hash(idValue):"";
                boolean nativeRow=used.stream().allMatch(Cell::nativeCell),strong=nativeRow&&!idFingerprint.isBlank();
                if(!nativeRow&&!idFingerprint.isBlank()){
                    Set<String> textualIds=new HashSet<>();for(Map<String,Object> source:sources)if(used.stream().anyMatch(c->c.sourceIds.contains(text(source,"id"))))for(Map<String,Object> claim:maps(EntityIdentity.resolvePerson(name,List.of(source)).get("identityClaims")))if("CN_ID".equals(text(claim,"field")))textualIds.add(text(claim,"valueFingerprint"));
                    strong=textualIds.size()==1&&textualIds.contains(idFingerprint);
                }
                List<String> months=new ArrayList<>();Matcher matcher=Pattern.compile("(?<![0-9])(20[0-9]{2})[年./-]\\s*(0?[1-9]|1[0-2])(?:月|(?![0-9]))").matcher(fields.getOrDefault("MONTHS",""));while(matcher.find())months.add(matcher.group(1)+"-"+String.format(Locale.ROOT,"%02d",Integer.parseInt(matcher.group(2))));
                List<Cell> tableHeaders=cells.stream().filter(c->c.group().equals(owner.group())&&isHeader(c)).sorted(Comparator.comparingInt(Cell::col)).toList();
                String headerSignature=hash(canonical(tableHeaders.stream().map(c->List.of(c.col(),c.colSpan(),clean(c.text()))).toList()));
                String recordKey=hash(canonical(List.of(owner.group(),owner.id(),owner.row())));
                List<String> refs=used.stream().flatMap(c->c.sourceIds.stream()).distinct().toList();
                records.add(new Row(owner.doc(),text(owner.data,"documentHash"),integer(owner.data.get("documentVersion"),-1),text(owner.data,"locationType"),integer(owner.data.get("location"),0),owner.table(),text(owner.data,"frameId"),row,name,idFingerprint,strong,nativeRow,fields.getOrDefault("EMPLOYER",""),months,fields,used.stream().map(Cell::id).distinct().toList(),refs,headerSignature,recordKey));
            }
        }
        if(!requested.isEmpty()&&!records.stream().flatMap(r->r.cellIds().stream()).collect(java.util.stream.Collectors.toSet()).containsAll(requested))return new Result(records,"STRUCTURE_CELL_SOURCE_UNPROVEN",List.of());
        if(records.isEmpty())return new Result(List.of(),"STRUCTURE_ROW_BINDING_UNPROVEN",List.of());
        Set<String> ids=new HashSet<>();records.stream().map(Row::identifierFingerprint).filter(v->!v.isBlank()).forEach(ids::add);if(ids.size()>1)return new Result(records,"SUBJECT_IDENTITY_CONFLICT",List.of());
        Set<String> employers=new HashSet<>();records.stream().map(Row::employer).filter(v->!v.isBlank()).forEach(employers::add);if(employers.size()>1)return new Result(records,"STRUCTURE_EMPLOYER_CONFLICT",List.of());
        List<Map<String,Object>> links=new ArrayList<>();List<Row> ordered=records.stream().sorted(Comparator.comparing(Row::documentId).thenComparingInt(Row::location).thenComparingInt(Row::row)).toList();
        for(int i=1;i<ordered.size();i++){Row a=ordered.get(i-1),b=ordered.get(i);if(a.recordKey.equals(b.recordKey))continue;
            boolean sameVersion=a.documentId.equals(b.documentId)&&a.hash.equals(b.hash)&&a.version==b.version;
            boolean identity=a.strongIdentity&&b.strongIdentity&&!a.identifierFingerprint.isBlank()&&a.identifierFingerprint.equals(b.identifierFingerprint);
            boolean sameTable=a.tableId.equals(b.tableId)&&a.frameId.equals(b.frameId)&&(a.locationType.equals(b.locationType)&&a.location==b.location||a.nativeStructure&&b.nativeStructure&&!a.locationType.equals("PAGE")&&!b.locationType.equals("PAGE"));
            boolean pageChain=a.locationType.equals("PAGE")&&b.locationType.equals("PAGE")&&b.location==a.location+1&&a.headerSignature.equals(b.headerSignature);
            if(!sameVersion||!identity||!sameTable&&!pageChain)return new Result(records,"STRUCTURE_CONTINUATION_UNPROVEN",links);
            links.add(of("type",sameTable?"SAME_TABLE_REPEATED_SUBJECT":"ADJACENT_PAGES_REPEATED_SUBJECT","fromCellIds",a.cellIds,"toCellIds",b.cellIds,"sourceIds",java.util.stream.Stream.concat(a.sourceIds.stream(),b.sourceIds.stream()).distinct().toList(),"identifierFingerprint",a.identifierFingerprint,"headerSignature",a.headerSignature));
        }
        return new Result(records,"",links);
    }
    /** Shared admission for source-bound record consumers. Geometry never authorizes borrowing
     * a neighbouring cell; callers must still use explicit headers and row-span coverage. */
    public static List<Map<String,Object>> validatedCells(List<Map<String,Object>> layout,List<Map<String,Object>> sources){
        List<Map<String,Object>> cells=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Map<String,Object> raw:layout){if(cells.size()>=3000)break;String id=text(raw,"cellId");if(id.isBlank())id=text(raw,"tableCellId");if(id.isBlank()||!seen.add(id)||!"TABLE_CELL".equals(text(raw,"kind"))||!NATIVE.contains(text(raw,"source"))&&!Set.of("STRUCTURED_OCR","OCR_STRUCTURED").contains(text(raw,"source")))continue;
            if(text(raw,"tableId").isBlank()||integer(raw.get("row"),0)<1||integer(raw.get("column"),0)<1||integer(raw.get("rowSpan"),1)<1||integer(raw.get("columnSpan"),1)<1||Boolean.TRUE.equals(raw.get("mergeUnresolved"))||Set.of("DELETED","INSERTED","CONTAINS_REVISIONS").contains(text(raw,"revision")))continue;
            if(!NATIVE.contains(text(raw,"source"))&&!validGeometry(raw))continue;
            if("PDFBOX_NATIVE_TABLE".equals(text(raw,"source"))&&!validPdfGeometry(raw))continue;
            List<String> refs=sourceMatches(raw,sources);if(refs.isEmpty())continue;Map<String,Object> cell=new LinkedHashMap<>(raw);cell.put("cellId",id);cell.put("_sourceIds",refs);cells.add(cell);
        }return List.copyOf(cells);
    }
    private static boolean validGeometry(Map<String,Object> cell){
        Object raw=cell.get("bbox");if(!(raw instanceof List<?> box)||box.size()!=4||text(cell,"frameId").isBlank()||text(cell,"imageHash").isBlank())return false;
        try{double x=((Number)box.get(0)).doubleValue(),y=((Number)box.get(1)).doubleValue(),right=((Number)box.get(2)).doubleValue(),bottom=((Number)box.get(3)).doubleValue(),width=((Number)cell.get("imageWidth")).doubleValue(),height=((Number)cell.get("imageHeight")).doubleValue();return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(right)&&Double.isFinite(bottom)&&Double.isFinite(width)&&Double.isFinite(height)&&x>=0&&y>=0&&right>x&&bottom>y&&right<=width&&bottom<=height;}catch(RuntimeException invalid){return false;}
    }
    private static boolean validPdfGeometry(Map<String,Object> cell){
        if(!"PDF_DISPLAY_POINTS_TOP_LEFT".equals(text(cell,"coordinateSpace"))||!(cell.get("bbox") instanceof List<?> box)||box.size()!=4)return false;
        try{double x=((Number)box.get(0)).doubleValue(),y=((Number)box.get(1)).doubleValue(),right=((Number)box.get(2)).doubleValue(),bottom=((Number)box.get(3)).doubleValue(),width=((Number)cell.get("pageWidth")).doubleValue(),height=((Number)cell.get("pageHeight")).doubleValue();return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(right)&&Double.isFinite(bottom)&&Double.isFinite(width)&&Double.isFinite(height)&&x>=0&&y>=0&&right>x&&bottom>y&&right<=width&&bottom<=height;}catch(RuntimeException invalid){return false;}
    }
    private static boolean isHeader(Cell c){return c.data.containsKey("header")?Boolean.TRUE.equals(c.data.get("header")):c.row()<=2&&!label(c.text()).isBlank();}
    private static List<Cell> headers(Cell cell,List<Cell> cells){List<String> explicit=strings(cell.data.get("headerCellIds"));return cells.stream().filter(h->h.group().equals(cell.group())&&isHeader(h)&&(!explicit.isEmpty()?explicit.contains(h.id()):true))
        .filter(h->cell.keyValue()
            ?!explicit.isEmpty()&&h.row()<=cell.row()&&h.row()+h.rowSpan()>=cell.row()+cell.rowSpan()&&h.col()+h.colSpan()==cell.col()
            :h.row()+h.rowSpan()<=cell.row()&&h.col()<=cell.col()&&h.col()+h.colSpan()>cell.col())
        .filter(h->!label(h.text()).isBlank()).toList();}
    private static List<String> sourceMatches(Map<String,Object> cell,List<Map<String,Object>> sources){String content=text(cell,"text");if(content.isBlank()||text(cell,"documentHash").isBlank()||integer(cell.get("documentVersion"),0)<1||text(cell,"locationType").isBlank()||integer(cell.get("location"),0)<1)return List.of();
        if("PDFBOX_NATIVE_TABLE".equals(text(cell,"source"))||cell.containsKey("sourceFragments"))return fragmentSources(cell,sources);
        return sources.stream().filter(s->{Map<String,Object> e=evidence(s.get("evidence"));return text(e,"documentId").equals(text(cell,"documentId"))&&text(s,"documentHash").equals(text(cell,"documentHash"))&&integer(s.get("documentVersion"),-1)==integer(cell.get("documentVersion"),-2)&&text(e,"locationType").equals(text(cell,"locationType"))&&integer(e.get("page"),-1)==integer(cell.get("location"),-2)&&text(e,"quote").contains(content);}).map(s->text(s,"id")).filter(id->!id.isBlank()).toList();
    }
    private static List<String> fragmentSources(Map<String,Object> cell,List<Map<String,Object>> sources){
        List<Map<String,Object>> fragments=maps(cell.get("sourceFragments"));if(fragments.isEmpty()||fragments.size()>1000||!clean(text(cell,"text")).equals(fragments.stream().map(f->clean(text(f,"text"))).reduce("",String::concat)))return List.of();
        Set<String> refs=new LinkedHashSet<>();int previous=-1;
        for(var fragment:fragments){int from=integer(fragment.get("start"),-1),to=integer(fragment.get("end"),-1);String raw=text(fragment,"text");if(from<previous||from<0||to<=from||raw.length()!=to-from)return List.of();previous=to;boolean found=false;
            for(Map<String,Object> source:sources){Map<String,Object> e=evidence(source.get("evidence"));if(!text(e,"documentId").equals(text(cell,"documentId"))||!text(source,"documentHash").equals(text(cell,"documentHash"))||integer(source.get("documentVersion"),-1)!=integer(cell.get("documentVersion"),-2)||!text(e,"locationType").equals(text(cell,"locationType"))||integer(e.get("page"),-1)!=integer(cell.get("location"),-2))continue;
                Matcher range=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(text(e,"anchor"));if(!range.matches())continue;int start=Integer.parseInt(range.group(1)),end=Integer.parseInt(range.group(2));String quote=text(e,"quote");if(from<start||to>end||to-start>quote.length()||!quote.substring(from-start,to-start).equals(raw))continue;String id=text(source,"id");if(!id.isBlank()){refs.add(id);found=true;}
            }if(!found)return List.of();
        }return List.copyOf(refs);
    }
    private static String label(String raw){String value=clean(raw).replaceAll("[：:]$","");return switch(value){
        case "姓名","人员姓名","参保人","参保人姓名","持证人","员工姓名"->"NAME";
        case "身份证号","身份证号码","公民身份号码","公民身份证号码"->"CN_ID";
        case "缴费单位","参保单位","单位名称","工作单位","聘用单位"->"EMPLOYER";
        case "缴费月份","缴费年月","缴费所属期","所属年月","月份","缴纳月份"->"MONTHS";
        case "证书编号","注册编号","执业印章号","证件编号"->value;
        case "证书名称","证书类别","证书类型"->"CATEGORY";case "等级","级别","证书等级"->"GRADE";case "专业","注册专业"->"PROFESSION";
        case "拟任岗位","本项目任职","拟在本合同任职","在本项目担任职务","岗位","职务"->"ROLE";case "有效期","注册有效期","使用有效期","证书有效期"->"DATE:"+value;default->"";
    };}
    private static boolean validIdentifier(String id){try{java.time.LocalDate.of(Integer.parseInt(id.substring(6,10)),Integer.parseInt(id.substring(10,12)),Integer.parseInt(id.substring(12,14)));}catch(RuntimeException invalid){return false;}int[] weights={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};int sum=0;for(int i=0;i<17;i++)sum+=(id.charAt(i)-'0')*weights[i];return "10X98765432".charAt(sum%11)==id.charAt(17);}
}
