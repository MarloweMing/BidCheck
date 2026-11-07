package cn.bidcheck.verification.facts;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Reconstructs bounded table records from source-backed cells. A model's row or continuation claim is never an identity. */
public final class TableStructure {
    public static final String VERSION="table-source-binding-1";
    private static final Set<String> NATIVE=cn.bidcheck.compat.Java8Api.setOf("OOXML_NATIVE", "ASPOSE_WORDS_NATIVE", "PDFBOX_NATIVE_TABLE");
    private TableStructure(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Row{
        private final String documentId;
        private final String hash;
        private final int version;
        private final String locationType;
        private final int location;
        private final String tableId;
        private final String frameId;
        private final int row;
        private final String name;
        private final String identifierFingerprint;
        private final boolean strongIdentity;
        private final boolean nativeStructure;
        private final String employer;
        private final List<String> months;
        private final Map<String,String> fields;
        private final List<String> cellIds;
        private final List<String> sourceIds;
        private final String headerSignature;
        private final String recordKey;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Row(@com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId, @com.fasterxml.jackson.annotation.JsonProperty("hash") String hash, @com.fasterxml.jackson.annotation.JsonProperty("version") int version, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("location") int location, @com.fasterxml.jackson.annotation.JsonProperty("tableId") String tableId, @com.fasterxml.jackson.annotation.JsonProperty("frameId") String frameId, @com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("identifierFingerprint") String identifierFingerprint, @com.fasterxml.jackson.annotation.JsonProperty("strongIdentity") boolean strongIdentity, @com.fasterxml.jackson.annotation.JsonProperty("nativeStructure") boolean nativeStructure, @com.fasterxml.jackson.annotation.JsonProperty("employer") String employer, @com.fasterxml.jackson.annotation.JsonProperty("months") List<String> months, @com.fasterxml.jackson.annotation.JsonProperty("fields") Map<String,String> fields, @com.fasterxml.jackson.annotation.JsonProperty("cellIds") List<String> cellIds, @com.fasterxml.jackson.annotation.JsonProperty("sourceIds") List<String> sourceIds, @com.fasterxml.jackson.annotation.JsonProperty("headerSignature") String headerSignature, @com.fasterxml.jackson.annotation.JsonProperty("recordKey") String recordKey) { this.documentId = documentId; this.hash = hash; this.version = version; this.locationType = locationType; this.location = location; this.tableId = tableId; this.frameId = frameId; this.row = row; this.name = name; this.identifierFingerprint = identifierFingerprint; this.strongIdentity = strongIdentity; this.nativeStructure = nativeStructure; this.employer = employer; this.months = months; this.fields = fields; this.cellIds = cellIds; this.sourceIds = sourceIds; this.headerSignature = headerSignature; this.recordKey = recordKey; }
        public String documentId() { return documentId; }
        public String hash() { return hash; }
        public int version() { return version; }
        public String locationType() { return locationType; }
        public int location() { return location; }
        public String tableId() { return tableId; }
        public String frameId() { return frameId; }
        public int row() { return row; }
        public String name() { return name; }
        public String identifierFingerprint() { return identifierFingerprint; }
        public boolean strongIdentity() { return strongIdentity; }
        public boolean nativeStructure() { return nativeStructure; }
        public String employer() { return employer; }
        public List<String> months() { return months; }
        public Map<String,String> fields() { return fields; }
        public List<String> cellIds() { return cellIds; }
        public List<String> sourceIds() { return sourceIds; }
        public String headerSignature() { return headerSignature; }
        public String recordKey() { return recordKey; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Row that = (Row) other; return java.util.Objects.equals(this.documentId, that.documentId) && java.util.Objects.equals(this.hash, that.hash) && java.util.Objects.equals(this.version, that.version) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.location, that.location) && java.util.Objects.equals(this.tableId, that.tableId) && java.util.Objects.equals(this.frameId, that.frameId) && java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.identifierFingerprint, that.identifierFingerprint) && java.util.Objects.equals(this.strongIdentity, that.strongIdentity) && java.util.Objects.equals(this.nativeStructure, that.nativeStructure) && java.util.Objects.equals(this.employer, that.employer) && java.util.Objects.equals(this.months, that.months) && java.util.Objects.equals(this.fields, that.fields) && java.util.Objects.equals(this.cellIds, that.cellIds) && java.util.Objects.equals(this.sourceIds, that.sourceIds) && java.util.Objects.equals(this.headerSignature, that.headerSignature) && java.util.Objects.equals(this.recordKey, that.recordKey); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.documentId); hash = 31 * hash + java.util.Objects.hashCode(this.hash); hash = 31 * hash + java.util.Objects.hashCode(this.version); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.location); hash = 31 * hash + java.util.Objects.hashCode(this.tableId); hash = 31 * hash + java.util.Objects.hashCode(this.frameId); hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.name); hash = 31 * hash + java.util.Objects.hashCode(this.identifierFingerprint); hash = 31 * hash + java.util.Objects.hashCode(this.strongIdentity); hash = 31 * hash + java.util.Objects.hashCode(this.nativeStructure); hash = 31 * hash + java.util.Objects.hashCode(this.employer); hash = 31 * hash + java.util.Objects.hashCode(this.months); hash = 31 * hash + java.util.Objects.hashCode(this.fields); hash = 31 * hash + java.util.Objects.hashCode(this.cellIds); hash = 31 * hash + java.util.Objects.hashCode(this.sourceIds); hash = 31 * hash + java.util.Objects.hashCode(this.headerSignature); hash = 31 * hash + java.util.Objects.hashCode(this.recordKey); return hash; }
        @Override public String toString() { return "Row[" + "documentId=" + documentId + ", hash=" + hash + ", version=" + version + ", locationType=" + locationType + ", location=" + location + ", tableId=" + tableId + ", frameId=" + frameId + ", row=" + row + ", name=" + name + ", identifierFingerprint=" + identifierFingerprint + ", strongIdentity=" + strongIdentity + ", nativeStructure=" + nativeStructure + ", employer=" + employer + ", months=" + months + ", fields=" + fields + ", cellIds=" + cellIds + ", sourceIds=" + sourceIds + ", headerSignature=" + headerSignature + ", recordKey=" + recordKey + "]"; }

        public Map<String,Object> proof(){return of("documentId",documentId,"documentHash",hash,"documentVersion",version,"locationType",locationType,"location",location,"tableId",tableId,"frameId",frameId,"row",row,"subject",name,"identityStrength",strongIdentity?"STRONG":"SOURCE_LOCAL","identifierFingerprint",identifierFingerprint,"employer",employer,"months",months,"cellIds",cellIds,"sourceIds",sourceIds,"headerSignature",headerSignature,"recordKey",recordKey,"structureOrigin",nativeStructure?"NATIVE_TABLE":"OCR_SOURCE_LOCAL");}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Result{
        private final List<Row> rows;
        private final String issue;
        private final List<Map<String,Object>> links;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Result(@com.fasterxml.jackson.annotation.JsonProperty("rows") List<Row> rows, @com.fasterxml.jackson.annotation.JsonProperty("issue") String issue, @com.fasterxml.jackson.annotation.JsonProperty("links") List<Map<String,Object>> links) { this.rows = rows; this.issue = issue; this.links = links; }
        public List<Row> rows() { return rows; }
        public String issue() { return issue; }
        public List<Map<String,Object>> links() { return links; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.rows, that.rows) && java.util.Objects.equals(this.issue, that.issue) && java.util.Objects.equals(this.links, that.links); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.rows); hash = 31 * hash + java.util.Objects.hashCode(this.issue); hash = 31 * hash + java.util.Objects.hashCode(this.links); return hash; }
        @Override public String toString() { return "Result[" + "rows=" + rows + ", issue=" + issue + ", links=" + links + "]"; }

        public boolean valid(){return cn.bidcheck.compat.Java8Api.isBlank(issue)&&!rows.isEmpty();}
        public Map<String,Object> proof(){return of("version",VERSION,"status",valid()?"SOURCE_BOUND":"UNDETERMINED","issue",issue,"records",cn.bidcheck.compat.Java8Api.toList(rows.stream().map(Row::proof)),"links",links);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cell{
        private final Map<String,Object> data;
        private final List<String> sourceIds;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cell(@com.fasterxml.jackson.annotation.JsonProperty("data") Map<String,Object> data, @com.fasterxml.jackson.annotation.JsonProperty("sourceIds") List<String> sourceIds) { this.data = data; this.sourceIds = sourceIds; }
        public Map<String,Object> data() { return data; }
        public List<String> sourceIds() { return sourceIds; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.data, that.data) && java.util.Objects.equals(this.sourceIds, that.sourceIds); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.data); hash = 31 * hash + java.util.Objects.hashCode(this.sourceIds); return hash; }
        @Override public String toString() { return "Cell[" + "data=" + data + ", sourceIds=" + sourceIds + "]"; }

        String id(){return TableStructure.value(data,"cellId");}String doc(){return TableStructure.value(data,"documentId");}String table(){return TableStructure.value(data,"tableId");}
        String text(){return TableStructure.value(data,"text");}int row(){return integer(data.get("row"),0);}int col(){return integer(data.get("column"),0);}
        int rowSpan(){return integer(data.get("rowSpan"),1);}int colSpan(){return integer(data.get("columnSpan"),1);}
        String group(){return canonical(cn.bidcheck.compat.Java8Api.listOf(doc(), TableStructure.value(data,"documentHash"), TableStructure.value(data,"documentVersion"), table(), TableStructure.value(data,"frameId"), nativeCell()&&!"PDFBOX_NATIVE_TABLE".equals(TableStructure.value(data,"source"))?"":TableStructure.value(data,"locationType")+":"+TableStructure.value(data,"location")));}
        boolean nativeCell(){return NATIVE.contains(TableStructure.value(data,"source"));}
        boolean keyValue(){return "KEY_VALUE".equals(TableStructure.value(data,"tableType"));}
    }
    private static String value(Map<String,Object> data,String key){return text(data,key);}
    public static Result analyze(String name,List<String> requestedCellIds,List<Map<String,Object>> layout,List<Map<String,Object>> sources){
        if(name==null||cn.bidcheck.compat.Java8Api.isBlank(name))return new Result(cn.bidcheck.compat.Java8Api.listOf(),"STRUCTURE_SUBJECT_REQUIRED",cn.bidcheck.compat.Java8Api.listOf());
        List<Cell> cells=new ArrayList<>();for(Map<String,Object> raw:validatedCells(layout,sources))cells.add(new Cell(raw,strings(raw.get("_sourceIds"))));
        Set<String> requested=new HashSet<>(requestedCellIds==null?cn.bidcheck.compat.Java8Api.listOf():requestedCellIds);List<Row> records=new ArrayList<>();
        for(Cell owner:cells){if(!clean(owner.text()).equals(clean(name))||owner.colSpan()!=1&&!owner.keyValue())continue;List<Cell> nameHeaders=headers(owner,cells);if(nameHeaders.size()!=1||!label(nameHeaders.get(0).text()).equals("NAME"))continue;
            boolean keyValue=owner.keyValue()&&nameHeaders.get(0).row()<=owner.row()&&nameHeaders.get(0).col()<owner.col();
            if(keyValue&&cells.stream().filter(c->c.group().equals(owner.group())&&!isHeader(c)).filter(c->{List<Cell> h=headers(c,cells);return h.size()==1&&label(h.get(0).text()).equals("NAME");}).count()!=1)return new Result(records,"STRUCTURE_KEY_VALUE_OWNER_AMBIGUOUS",cn.bidcheck.compat.Java8Api.listOf());
            for(int row=owner.row();row<(keyValue?owner.row()+1:owner.row()+owner.rowSpan())&&row<owner.row()+100;row++){
                final int recordRow=row;List<Cell> members=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(c->c.group().equals(owner.group())&&(keyValue||c.row()<=recordRow&&c.row()+c.rowSpan()>recordRow)&&!isHeader(c)));
                if(!requested.isEmpty()&&members.stream().noneMatch(c->requested.contains(c.id())))continue;
                Map<String,String> fields=new LinkedHashMap<>();Map<String,Cell> fieldsAt=new LinkedHashMap<>();List<Cell> used=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(owner, nameHeaders.get(0)));boolean ambiguous=false;
                for(Cell cell:members){if(cell.colSpan()!=1&&!keyValue)continue;List<Cell> heading=headers(cell,cells);if(heading.size()!=1)continue;String key=label(heading.get(0).text());if(cn.bidcheck.compat.Java8Api.isBlank(key)||key.equals("NAME"))continue;
                    if(fields.containsKey(key)&&!clean(fields.get(key)).equals(clean(cell.text()))){ambiguous=true;break;}fields.put(key,cn.bidcheck.compat.Java8Api.strip(cell.text()));fieldsAt.put(key,cell);used.add(cell);used.add(heading.get(0));
                    fields.put("label:"+cn.bidcheck.compat.Java8Api.strip(heading.get(0).text()),cn.bidcheck.compat.Java8Api.strip(cell.text()));
                }
                if(ambiguous)return new Result(records,"STRUCTURE_COLUMN_MAPPING_AMBIGUOUS",cn.bidcheck.compat.Java8Api.listOf());
                String rawId=fields.getOrDefault("CN_ID","");String idValue=rawId.replaceAll("[\\s\\p{Z}]+","").toUpperCase(Locale.ROOT);
                // Only fragments inside this one source-bound identity cell may be joined.
                // Never read an adjacent role cell, strip arbitrary punctuation or borrow digits.
                if(!cn.bidcheck.compat.Java8Api.isBlank(rawId)&&!cn.bidcheck.verification.source.CitizenId.valid(idValue))return new Result(records,"STRUCTURE_IDENTITY_FIELD_INVALID",cn.bidcheck.compat.Java8Api.listOf());
                String idFingerprint=idValue.matches("[0-9]{17}[0-9X]")?hash(idValue):"";
                boolean nativeRow=used.stream().allMatch(Cell::nativeCell),strong=nativeRow&&!cn.bidcheck.compat.Java8Api.isBlank(idFingerprint);
                if(!nativeRow&&!cn.bidcheck.compat.Java8Api.isBlank(idFingerprint)){
                    Set<String> textualIds=new HashSet<>();for(Map<String,Object> source:sources)if(used.stream().anyMatch(c->c.sourceIds.contains(text(source,"id"))))for(Map<String,Object> claim:maps(EntityIdentity.resolvePerson(name,cn.bidcheck.compat.Java8Api.listOf(source)).get("identityClaims")))if("CN_ID".equals(text(claim,"field")))textualIds.add(text(claim,"valueFingerprint"));
                    strong=textualIds.size()==1&&textualIds.contains(idFingerprint);
                }
                List<String> months=new ArrayList<>();Matcher matcher=Pattern.compile("(?<![0-9])(20[0-9]{2})[年./-]\\s*(0?[1-9]|1[0-2])(?:月|(?![0-9]))").matcher(fields.getOrDefault("MONTHS",""));while(matcher.find())months.add(matcher.group(1)+"-"+String.format(Locale.ROOT,"%02d",Integer.parseInt(matcher.group(2))));
                List<Cell> tableHeaders=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(c->c.group().equals(owner.group())&&isHeader(c)).sorted(Comparator.comparingInt(Cell::col)));
                String headerSignature=hash(canonical(cn.bidcheck.compat.Java8Api.toList(tableHeaders.stream().map(c->cn.bidcheck.compat.Java8Api.listOf(c.col(), c.colSpan(), clean(c.text()))))));
                String recordKey=hash(canonical(cn.bidcheck.compat.Java8Api.listOf(owner.group(), owner.id(), owner.row())));
                List<String> refs=cn.bidcheck.compat.Java8Api.toList(used.stream().flatMap(c->c.sourceIds.stream()).distinct());
                records.add(new Row(owner.doc(),text(owner.data,"documentHash"),integer(owner.data.get("documentVersion"),-1),text(owner.data,"locationType"),integer(owner.data.get("location"),0),owner.table(),text(owner.data,"frameId"),row,name,idFingerprint,strong,nativeRow,fields.getOrDefault("EMPLOYER",""),months,fields,cn.bidcheck.compat.Java8Api.toList(used.stream().map(Cell::id).distinct()),refs,headerSignature,recordKey));
            }
        }
        if(!requested.isEmpty()&&!records.stream().flatMap(r->r.cellIds().stream()).collect(java.util.stream.Collectors.toSet()).containsAll(requested))return new Result(records,"STRUCTURE_CELL_SOURCE_UNPROVEN",cn.bidcheck.compat.Java8Api.listOf());
        if(records.isEmpty())return new Result(cn.bidcheck.compat.Java8Api.listOf(),"STRUCTURE_ROW_BINDING_UNPROVEN",cn.bidcheck.compat.Java8Api.listOf());
        Set<String> ids=new HashSet<>();records.stream().map(Row::identifierFingerprint).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).forEach(ids::add);if(ids.size()>1)return new Result(records,"SUBJECT_IDENTITY_CONFLICT",cn.bidcheck.compat.Java8Api.listOf());
        Set<String> employers=new HashSet<>();records.stream().map(Row::employer).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).forEach(employers::add);if(employers.size()>1)return new Result(records,"STRUCTURE_EMPLOYER_CONFLICT",cn.bidcheck.compat.Java8Api.listOf());
        List<Map<String,Object>> links=new ArrayList<>();List<Row> ordered=cn.bidcheck.compat.Java8Api.toList(records.stream().sorted(Comparator.comparing(Row::documentId).thenComparingInt(Row::location).thenComparingInt(Row::row)));
        for(int i=1;i<ordered.size();i++){Row a=ordered.get(i-1),b=ordered.get(i);if(a.recordKey.equals(b.recordKey))continue;
            boolean sameVersion=a.documentId.equals(b.documentId)&&a.hash.equals(b.hash)&&a.version==b.version;
            boolean identity=a.strongIdentity&&b.strongIdentity&&!cn.bidcheck.compat.Java8Api.isBlank(a.identifierFingerprint)&&a.identifierFingerprint.equals(b.identifierFingerprint);
            boolean sameTable=a.tableId.equals(b.tableId)&&a.frameId.equals(b.frameId)&&(a.locationType.equals(b.locationType)&&a.location==b.location||a.nativeStructure&&b.nativeStructure&&!a.locationType.equals("PAGE")&&!b.locationType.equals("PAGE"));
            boolean pageChain=a.locationType.equals("PAGE")&&b.locationType.equals("PAGE")&&b.location==a.location+1&&a.headerSignature.equals(b.headerSignature);
            if(!sameVersion||!identity||!sameTable&&!pageChain)return new Result(records,"STRUCTURE_CONTINUATION_UNPROVEN",links);
            links.add(of("type",sameTable?"SAME_TABLE_REPEATED_SUBJECT":"ADJACENT_PAGES_REPEATED_SUBJECT","fromCellIds",a.cellIds,"toCellIds",b.cellIds,"sourceIds",cn.bidcheck.compat.Java8Api.toList(java.util.stream.Stream.concat(a.sourceIds.stream(),b.sourceIds.stream()).distinct()),"identifierFingerprint",a.identifierFingerprint,"headerSignature",a.headerSignature));
        }
        return new Result(records,"",links);
    }
    /** Shared admission for source-bound record consumers. Geometry never authorizes borrowing
     * a neighbouring cell; callers must still use explicit headers and row-span coverage. */
    public static List<Map<String,Object>> validatedCells(List<Map<String,Object>> layout,List<Map<String,Object>> sources){
        List<Map<String,Object>> cells=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Map<String,Object> raw:layout){if(cells.size()>=3000)break;String id=text(raw,"cellId");if(cn.bidcheck.compat.Java8Api.isBlank(id))id=text(raw,"tableCellId");if(cn.bidcheck.compat.Java8Api.isBlank(id)||!seen.add(id)||!"TABLE_CELL".equals(text(raw,"kind"))||!NATIVE.contains(text(raw,"source"))&&!cn.bidcheck.compat.Java8Api.setOf("STRUCTURED_OCR", "OCR_STRUCTURED").contains(text(raw,"source")))continue;
            if(cn.bidcheck.compat.Java8Api.isBlank(text(raw,"tableId"))||integer(raw.get("row"),0)<1||integer(raw.get("column"),0)<1||integer(raw.get("rowSpan"),1)<1||integer(raw.get("columnSpan"),1)<1||Boolean.TRUE.equals(raw.get("mergeUnresolved"))||cn.bidcheck.compat.Java8Api.setOf("DELETED", "INSERTED", "CONTAINS_REVISIONS").contains(text(raw,"revision")))continue;
            if(!NATIVE.contains(text(raw,"source"))&&!validGeometry(raw))continue;
            if("PDFBOX_NATIVE_TABLE".equals(text(raw,"source"))&&!validPdfGeometry(raw))continue;
            List<String> refs=sourceMatches(raw,sources);if(refs.isEmpty())continue;Map<String,Object> cell=new LinkedHashMap<>(raw);cell.put("cellId",id);cell.put("_sourceIds",refs);cells.add(cell);
        }return cn.bidcheck.compat.Java8Api.listCopyOf(cells);
    }
    private static boolean validGeometry(Map<String,Object> cell){
        Object raw=cell.get("bbox");if(!(raw instanceof List<?>)||((List<?>) (raw)).size()!=4||cn.bidcheck.compat.Java8Api.isBlank(text(cell,"frameId"))||cn.bidcheck.compat.Java8Api.isBlank(text(cell,"imageHash")))return false;
        try{double x=((Number)((List<?>) (raw)).get(0)).doubleValue(),y=((Number)((List<?>) (raw)).get(1)).doubleValue(),right=((Number)((List<?>) (raw)).get(2)).doubleValue(),bottom=((Number)((List<?>) (raw)).get(3)).doubleValue(),width=((Number)cell.get("imageWidth")).doubleValue(),height=((Number)cell.get("imageHeight")).doubleValue();return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(right)&&Double.isFinite(bottom)&&Double.isFinite(width)&&Double.isFinite(height)&&x>=0&&y>=0&&right>x&&bottom>y&&right<=width&&bottom<=height;}catch(RuntimeException invalid){return false;}
    }
    private static boolean validPdfGeometry(Map<String,Object> cell){
        if(!"PDF_DISPLAY_POINTS_TOP_LEFT".equals(text(cell,"coordinateSpace"))||!(cell.get("bbox") instanceof List<?>)||((List<?>) (cell.get("bbox"))).size()!=4)return false;
        try{double x=((Number)((List<?>) (cell.get("bbox"))).get(0)).doubleValue(),y=((Number)((List<?>) (cell.get("bbox"))).get(1)).doubleValue(),right=((Number)((List<?>) (cell.get("bbox"))).get(2)).doubleValue(),bottom=((Number)((List<?>) (cell.get("bbox"))).get(3)).doubleValue(),width=((Number)cell.get("pageWidth")).doubleValue(),height=((Number)cell.get("pageHeight")).doubleValue();return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(right)&&Double.isFinite(bottom)&&Double.isFinite(width)&&Double.isFinite(height)&&x>=0&&y>=0&&right>x&&bottom>y&&right<=width&&bottom<=height;}catch(RuntimeException invalid){return false;}
    }
    private static boolean isHeader(Cell c){return c.data.containsKey("header")?Boolean.TRUE.equals(c.data.get("header")):c.row()<=2&&!cn.bidcheck.compat.Java8Api.isBlank(label(c.text()));}
    private static List<Cell> headers(Cell cell,List<Cell> cells){List<String> explicit=strings(cell.data.get("headerCellIds"));return cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(h->h.group().equals(cell.group())&&isHeader(h)&&(!explicit.isEmpty()?explicit.contains(h.id()):true))
        .filter(h->cell.keyValue()
            ?!explicit.isEmpty()&&h.row()<=cell.row()&&h.row()+h.rowSpan()>=cell.row()+cell.rowSpan()&&h.col()+h.colSpan()==cell.col()
            :h.row()+h.rowSpan()<=cell.row()&&h.col()<=cell.col()&&h.col()+h.colSpan()>cell.col())
        .filter(h->!cn.bidcheck.compat.Java8Api.isBlank(label(h.text()))));}
    private static List<String> sourceMatches(Map<String,Object> cell,List<Map<String,Object>> sources){String content=text(cell,"text");if(cn.bidcheck.compat.Java8Api.isBlank(content)||cn.bidcheck.compat.Java8Api.isBlank(text(cell,"documentHash"))||integer(cell.get("documentVersion"),0)<1||cn.bidcheck.compat.Java8Api.isBlank(text(cell,"locationType"))||integer(cell.get("location"),0)<1)return cn.bidcheck.compat.Java8Api.listOf();
        if("PDFBOX_NATIVE_TABLE".equals(text(cell,"source"))||cell.containsKey("sourceFragments"))return fragmentSources(cell,sources);
        return cn.bidcheck.compat.Java8Api.toList(sources.stream().filter(s->{Map<String,Object> e=evidence(s.get("evidence"));return text(e,"documentId").equals(text(cell,"documentId"))&&text(s,"documentHash").equals(text(cell,"documentHash"))&&integer(s.get("documentVersion"),-1)==integer(cell.get("documentVersion"),-2)&&text(e,"locationType").equals(text(cell,"locationType"))&&integer(e.get("page"),-1)==integer(cell.get("location"),-2)&&text(e,"quote").contains(content);}).map(s->text(s,"id")).filter(id->!cn.bidcheck.compat.Java8Api.isBlank(id)));
    }
    private static List<String> fragmentSources(Map<String,Object> cell,List<Map<String,Object>> sources){
        List<Map<String,Object>> fragments=maps(cell.get("sourceFragments"));if(fragments.isEmpty()||fragments.size()>1000||!clean(text(cell,"text")).equals(fragments.stream().map(f->clean(text(f,"text"))).reduce("",String::concat)))return cn.bidcheck.compat.Java8Api.listOf();
        Set<String> refs=new LinkedHashSet<>();int previous=-1;
        for(java.util.Map<java.lang.String, java.lang.Object> fragment:fragments){int from=integer(fragment.get("start"),-1),to=integer(fragment.get("end"),-1);String raw=text(fragment,"text");if(from<previous||from<0||to<=from||raw.length()!=to-from)return cn.bidcheck.compat.Java8Api.listOf();previous=to;boolean found=false;
            for(Map<String,Object> source:sources){Map<String,Object> e=evidence(source.get("evidence"));if(!text(e,"documentId").equals(text(cell,"documentId"))||!text(source,"documentHash").equals(text(cell,"documentHash"))||integer(source.get("documentVersion"),-1)!=integer(cell.get("documentVersion"),-2)||!text(e,"locationType").equals(text(cell,"locationType"))||integer(e.get("page"),-1)!=integer(cell.get("location"),-2))continue;
                Matcher range=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(text(e,"anchor"));if(!range.matches())continue;int start=Integer.parseInt(range.group(1)),end=Integer.parseInt(range.group(2));String quote=text(e,"quote");if(from<start||to>end||to-start>quote.length()||!quote.substring(from-start,to-start).equals(raw))continue;String id=text(source,"id");if(!cn.bidcheck.compat.Java8Api.isBlank(id)){refs.add(id);found=true;}
            }if(!found)return cn.bidcheck.compat.Java8Api.listOf();
        }return cn.bidcheck.compat.Java8Api.listCopyOf(refs);
    }
    private static String label(String raw){String value=clean(raw).replaceAll("[：:]$","");switch (value) {case "姓名":case "人员姓名":case "参保人":case "参保人姓名":case "持证人":case "员工姓名":return "NAME";case "身份证号":case "身份证号码":case "公民身份号码":case "公民身份证号码":return "CN_ID";case "缴费单位":case "参保单位":case "单位名称":case "工作单位":case "聘用单位":return "EMPLOYER";case "缴费月份":case "缴费年月":case "缴费所属期":case "所属年月":case "月份":case "缴纳月份":return "MONTHS";case "证书编号":case "注册编号":case "执业印章号":case "证件编号":return value;case "证书名称":case "证书类别":case "证书类型":return "CATEGORY";case "等级":case "级别":case "证书等级":return "GRADE";case "专业":case "注册专业":return "PROFESSION";case "拟任岗位":case "本项目任职":case "拟在本合同任职":case "在本项目担任职务":case "岗位":case "职务":return "ROLE";case "有效期":case "注册有效期":case "使用有效期":case "证书有效期":return "DATE:"+value;default:return "";}}
}
