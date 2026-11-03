package cn.bidcheck.document;
import cn.bidcheck.compat.HexFormat;

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
    private static final Set<String> PROCUREMENT_LABELS = cn.bidcheck.compat.Java8Api.setOf("商品", "物料编码", "物料名称", "计量单位", "采购数量", "净价", "含税价", "净价小计", "价税小计", "价税合计", "订单号", "型号", "品牌", "区域", "项目描述", "性能质量要求", "条款名称", "编列内容", "参数", "不含税单价", "不含税价合计", "最高不含税单价限价", "不含税价合计最高限价", "不含税单价基价", "统一折扣(cm)报价", "税率/征收率", "建筑服务发生地", "建筑项目名称", "说明", "在本项目担任职务");
    private static final Set<String> ENGINEERING_LABELS = cn.bidcheck.compat.Java8Api.setOf("工程名称", "建筑面积", "层数/建筑面积", "工程地点", "建设地点", "结构类型", "工程造价", "开竣工日期", "完工日期", "施工单位", "设计单位", "监理单位", "工程内容及检查情况", "验收意见", "竣工验收内容", "验收小组意见", "项目", "验收记录", "验收结论", "综合验收结论", "项目负责人", "技术负责人", "项目技术负责人", "企业名称", "详细地址", "建立时间", "注册资本金", "统一社会信用代码(或营业执照注册号)", "经济性质", "法定代表人", "单位负责人", "职称或执业资格");
    private static final Set<String> ADDITIONAL_LABELS = cn.bidcheck.compat.Java8Api.setOf("在本项目中担任职务", "拟任职务", "担任职务", "本项目职务", "标段号", "注册建造师证", "《安全生产考核合格证书》(B/C证)", "社保证明", "姓名/名称", "初领日期", "应复审日期", "作业类别", "操作项目", "有效期开始日期", "有效期结束日期", "实际复审日期", "签发机关", "招标编号", "分项名称", "不含增值税总价", "含增值税总价", "不含增值税合价", "含增值税合价", "不含增值税单价", "含增值税单价", "内容", "信息", "字段", "信息项");
    private static final Set<String> HEADER_LABELS = cn.bidcheck.compat.Java8Api.setOf("序号", "序列", "姓名", "人员姓名", "岗位", "职务", "岗位名称", "身份证号码", "身份证号", "证件号码", "证书编号", "证书名称", "资格证书名称", "证书等级", "有效期", "有效期至", "有效期限", "有效起始日期", "有效截止日期", "性别", "出生日期", "年龄", "学历", "专业", "所学专业", "职称", "技术职称", "工作单位", "受聘单位", "毕业院校", "社会保障号码", "险种", "起始年月", "截止年月", "本单位实际缴费月数", "缴费情况", "缴费基数", "缴费年月", "人员信息", "基本信息", "证件信息", "证书信息", "项目名称", "项目编号", "项目经理", "建设单位", "合同名称", "合同编号", "合同金额", "签订日期", "开工日期", "竣工日期", "项目地点", "标段", "标段名称", "规格型号", "设备名称", "产品名称", "单位", "数量", "单价", "合价", "金额", "含税金额", "不含税金额", "税率", "税额", "投标报价", "投标总价", "工期", "质量标准", "备注", "联系电话", "驾驶证号码", "档案编号", "准驾车型", "护照号码", "Name", "Role", "Position", "ID Number", "Certificate Number", "Certificate Type", "Valid Until", "No.");

    /** Offsets use Java UTF-16 indices into the unchanged page text; no geometry is invented. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Source {
        private final String documentId;
        private final int documentVersion;
        private final String documentHash;
        private final int page;
        private final String locationType;
        private final int start;
        private final int end;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Source(@com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId, @com.fasterxml.jackson.annotation.JsonProperty("documentVersion") int documentVersion, @com.fasterxml.jackson.annotation.JsonProperty("documentHash") String documentHash, @com.fasterxml.jackson.annotation.JsonProperty("page") int page, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end) { this.documentId = documentId; this.documentVersion = documentVersion; this.documentHash = documentHash; this.page = page; this.locationType = locationType; this.start = start; this.end = end; }
        public String documentId() { return documentId; }
        public int documentVersion() { return documentVersion; }
        public String documentHash() { return documentHash; }
        public int page() { return page; }
        public String locationType() { return locationType; }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Source that = (Source) other; return java.util.Objects.equals(this.documentId, that.documentId) && java.util.Objects.equals(this.documentVersion, that.documentVersion) && java.util.Objects.equals(this.documentHash, that.documentHash) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.documentId); hash = 31 * hash + java.util.Objects.hashCode(this.documentVersion); hash = 31 * hash + java.util.Objects.hashCode(this.documentHash); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); return hash; }
        @Override public String toString() { return "Source[" + "documentId=" + documentId + ", documentVersion=" + documentVersion + ", documentHash=" + documentHash + ", page=" + page + ", locationType=" + locationType + ", start=" + start + ", end=" + end + "]"; }

        public Map<String,Object> asMap() { return map("documentId",documentId,"documentVersion",documentVersion,"documentHash",documentHash,"page",page,"locationType",locationType,"start",start,"end",end); }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Cell {
        private final String id;
        private final int row;
        private final int column;
        private final int rowSpan;
        private final int columnSpan;
        private final String rawValue;
        private final String value;
        private final boolean header;
        private final Source source;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Cell(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("column") int column, @com.fasterxml.jackson.annotation.JsonProperty("rowSpan") int rowSpan, @com.fasterxml.jackson.annotation.JsonProperty("columnSpan") int columnSpan, @com.fasterxml.jackson.annotation.JsonProperty("rawValue") String rawValue, @com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("header") boolean header, @com.fasterxml.jackson.annotation.JsonProperty("source") Source source) { this.id = id; this.row = row; this.column = column; this.rowSpan = rowSpan; this.columnSpan = columnSpan; this.rawValue = rawValue; this.value = value; this.header = header; this.source = source; }
        public String id() { return id; }
        public int row() { return row; }
        public int column() { return column; }
        public int rowSpan() { return rowSpan; }
        public int columnSpan() { return columnSpan; }
        public String rawValue() { return rawValue; }
        public String value() { return value; }
        public boolean header() { return header; }
        public Source source() { return source; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.column, that.column) && java.util.Objects.equals(this.rowSpan, that.rowSpan) && java.util.Objects.equals(this.columnSpan, that.columnSpan) && java.util.Objects.equals(this.rawValue, that.rawValue) && java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.header, that.header) && java.util.Objects.equals(this.source, that.source); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.column); hash = 31 * hash + java.util.Objects.hashCode(this.rowSpan); hash = 31 * hash + java.util.Objects.hashCode(this.columnSpan); hash = 31 * hash + java.util.Objects.hashCode(this.rawValue); hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.header); hash = 31 * hash + java.util.Objects.hashCode(this.source); return hash; }
        @Override public String toString() { return "Cell[" + "id=" + id + ", row=" + row + ", column=" + column + ", rowSpan=" + rowSpan + ", columnSpan=" + columnSpan + ", rawValue=" + rawValue + ", value=" + value + ", header=" + header + ", source=" + source + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class HeaderContinuation {
        private final String method;
        private final String anchorTableId;
        private final String previousTableId;
        private final Source anchorTitleSource;
        private final List<Source> sequenceSources;
        private final List<Source> boundarySources;
        private final int firstSequence;
        private final int lastSequence;
        public String method() { return method; }
        public String anchorTableId() { return anchorTableId; }
        public String previousTableId() { return previousTableId; }
        public Source anchorTitleSource() { return anchorTitleSource; }
        public List<Source> sequenceSources() { return sequenceSources; }
        public List<Source> boundarySources() { return boundarySources; }
        public int firstSequence() { return firstSequence; }
        public int lastSequence() { return lastSequence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; HeaderContinuation that = (HeaderContinuation) other; return java.util.Objects.equals(this.method, that.method) && java.util.Objects.equals(this.anchorTableId, that.anchorTableId) && java.util.Objects.equals(this.previousTableId, that.previousTableId) && java.util.Objects.equals(this.anchorTitleSource, that.anchorTitleSource) && java.util.Objects.equals(this.sequenceSources, that.sequenceSources) && java.util.Objects.equals(this.boundarySources, that.boundarySources) && java.util.Objects.equals(this.firstSequence, that.firstSequence) && java.util.Objects.equals(this.lastSequence, that.lastSequence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.method); hash = 31 * hash + java.util.Objects.hashCode(this.anchorTableId); hash = 31 * hash + java.util.Objects.hashCode(this.previousTableId); hash = 31 * hash + java.util.Objects.hashCode(this.anchorTitleSource); hash = 31 * hash + java.util.Objects.hashCode(this.sequenceSources); hash = 31 * hash + java.util.Objects.hashCode(this.boundarySources); hash = 31 * hash + java.util.Objects.hashCode(this.firstSequence); hash = 31 * hash + java.util.Objects.hashCode(this.lastSequence); return hash; }
        @Override public String toString() { return "HeaderContinuation[" + "method=" + method + ", anchorTableId=" + anchorTableId + ", previousTableId=" + previousTableId + ", anchorTitleSource=" + anchorTitleSource + ", sequenceSources=" + sequenceSources + ", boundarySources=" + boundarySources + ", firstSequence=" + firstSequence + ", lastSequence=" + lastSequence + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public HeaderContinuation(@com.fasterxml.jackson.annotation.JsonProperty("method") String method, @com.fasterxml.jackson.annotation.JsonProperty("anchorTableId") String anchorTableId, @com.fasterxml.jackson.annotation.JsonProperty("previousTableId") String previousTableId, @com.fasterxml.jackson.annotation.JsonProperty("anchorTitleSource") Source anchorTitleSource, @com.fasterxml.jackson.annotation.JsonProperty("sequenceSources") List<Source> sequenceSources, @com.fasterxml.jackson.annotation.JsonProperty("boundarySources") List<Source> boundarySources, @com.fasterxml.jackson.annotation.JsonProperty("firstSequence") int firstSequence, @com.fasterxml.jackson.annotation.JsonProperty("lastSequence") int lastSequence) { sequenceSources=cn.bidcheck.compat.Java8Api.listCopyOf(sequenceSources);boundarySources=cn.bidcheck.compat.Java8Api.listCopyOf(boundarySources);
            this.method = method; this.anchorTableId = anchorTableId; this.previousTableId = previousTableId; this.anchorTitleSource = anchorTitleSource; this.sequenceSources = sequenceSources; this.boundarySources = boundarySources; this.firstSequence = firstSequence; this.lastSequence = lastSequence;
        }
        public Map<String,Object> asMap(){return map("method",method,"anchorTableId",anchorTableId,"previousTableId",previousTableId,
                "anchorTitleSource",anchorTitleSource.asMap(),"sequenceSources",cn.bidcheck.compat.Java8Api.toList(sequenceSources.stream().map(Source::asMap)),
                "boundarySources",cn.bidcheck.compat.Java8Api.toList(boundarySources.stream().map(Source::asMap)),"firstSequence",firstSequence,"lastSequence",lastSequence);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Association {
        private final String tableId;
        private final int row;
        private final int column;
        private final String cellId;
        private final String label;
        private final List<String> headerPath;
        private final String value;
        private final String rawValue;
        private final Source source;
        private final List<Source> headerSources;
        private final boolean inherited;
        private final String status;
        private final HeaderContinuation headerContinuation;
        public String tableId() { return tableId; }
        public int row() { return row; }
        public int column() { return column; }
        public String cellId() { return cellId; }
        public String label() { return label; }
        public List<String> headerPath() { return headerPath; }
        public String value() { return value; }
        public String rawValue() { return rawValue; }
        public Source source() { return source; }
        public List<Source> headerSources() { return headerSources; }
        public boolean inherited() { return inherited; }
        public String status() { return status; }
        public HeaderContinuation headerContinuation() { return headerContinuation; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Association that = (Association) other; return java.util.Objects.equals(this.tableId, that.tableId) && java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.column, that.column) && java.util.Objects.equals(this.cellId, that.cellId) && java.util.Objects.equals(this.label, that.label) && java.util.Objects.equals(this.headerPath, that.headerPath) && java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.rawValue, that.rawValue) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.headerSources, that.headerSources) && java.util.Objects.equals(this.inherited, that.inherited) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.headerContinuation, that.headerContinuation); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.tableId); hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.column); hash = 31 * hash + java.util.Objects.hashCode(this.cellId); hash = 31 * hash + java.util.Objects.hashCode(this.label); hash = 31 * hash + java.util.Objects.hashCode(this.headerPath); hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.rawValue); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.headerSources); hash = 31 * hash + java.util.Objects.hashCode(this.inherited); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.headerContinuation); return hash; }
        @Override public String toString() { return "Association[" + "tableId=" + tableId + ", row=" + row + ", column=" + column + ", cellId=" + cellId + ", label=" + label + ", headerPath=" + headerPath + ", value=" + value + ", rawValue=" + rawValue + ", source=" + source + ", headerSources=" + headerSources + ", inherited=" + inherited + ", status=" + status + ", headerContinuation=" + headerContinuation + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Association(@com.fasterxml.jackson.annotation.JsonProperty("tableId") String tableId, @com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("column") int column, @com.fasterxml.jackson.annotation.JsonProperty("cellId") String cellId, @com.fasterxml.jackson.annotation.JsonProperty("label") String label, @com.fasterxml.jackson.annotation.JsonProperty("headerPath") List<String> headerPath, @com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("rawValue") String rawValue, @com.fasterxml.jackson.annotation.JsonProperty("source") Source source, @com.fasterxml.jackson.annotation.JsonProperty("headerSources") List<Source> headerSources, @com.fasterxml.jackson.annotation.JsonProperty("inherited") boolean inherited, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("headerContinuation") HeaderContinuation headerContinuation) { headerPath=cn.bidcheck.compat.Java8Api.listCopyOf(headerPath); headerSources=cn.bidcheck.compat.Java8Api.listCopyOf(headerSources);
            this.tableId = tableId; this.row = row; this.column = column; this.cellId = cellId; this.label = label; this.headerPath = headerPath; this.value = value; this.rawValue = rawValue; this.source = source; this.headerSources = headerSources; this.inherited = inherited; this.status = status; this.headerContinuation = headerContinuation;
        }
        public Association(String tableId,int row,int column,String cellId,String label,List<String> headerPath,String value,String rawValue,Source source,List<Source> headerSources,boolean inherited,String status){this(tableId,row,column,cellId,label,headerPath,value,rawValue,source,headerSources,inherited,status,null);}
        public Map<String,Object> asMap() { Map<String,Object> out=map("version",VERSION,"tableId",tableId,"row",row,"column",column,"cellId",cellId,"label",label,"headerPath",headerPath,"source",source.asMap(),"headerSources",cn.bidcheck.compat.Java8Api.toList(headerSources.stream().map(Source::asMap)),"headerComposition",!headerPath.isEmpty()&&!label.equals(headerPath.get(headerPath.size()-1))?"EXPLICIT_FRAGMENTS_JOINED":"EXACT_LABEL","inherited",inherited,"status",status);if(headerContinuation!=null)out.put("headerContinuation",headerContinuation.asMap());return out; }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Row {
        private final String tableId;
        private final int row;
        private final int page;
        private final String locationType;
        private final int start;
        private final int end;
        private final int line;
        private final String status;
        private final List<Association> associations;
        private final String reviewReason;
        public String tableId() { return tableId; }
        public int row() { return row; }
        public int page() { return page; }
        public String locationType() { return locationType; }
        public int start() { return start; }
        public int end() { return end; }
        public int line() { return line; }
        public String status() { return status; }
        public List<Association> associations() { return associations; }
        public String reviewReason() { return reviewReason; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Row that = (Row) other; return java.util.Objects.equals(this.tableId, that.tableId) && java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.line, that.line) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.associations, that.associations) && java.util.Objects.equals(this.reviewReason, that.reviewReason); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.tableId); hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.line); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.associations); hash = 31 * hash + java.util.Objects.hashCode(this.reviewReason); return hash; }
        @Override public String toString() { return "Row[" + "tableId=" + tableId + ", row=" + row + ", page=" + page + ", locationType=" + locationType + ", start=" + start + ", end=" + end + ", line=" + line + ", status=" + status + ", associations=" + associations + ", reviewReason=" + reviewReason + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Row(@com.fasterxml.jackson.annotation.JsonProperty("tableId") String tableId, @com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("page") int page, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("line") int line, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("associations") List<Association> associations, @com.fasterxml.jackson.annotation.JsonProperty("reviewReason") String reviewReason) { associations=cn.bidcheck.compat.Java8Api.listCopyOf(associations);
            this.tableId = tableId; this.row = row; this.page = page; this.locationType = locationType; this.start = start; this.end = end; this.line = line; this.status = status; this.associations = associations; this.reviewReason = reviewReason;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Table {
        private final String id;
        private final int page;
        private final String locationType;
        private final int start;
        private final int end;
        private final String title;
        private final boolean continuationMarker;
        private final int columns;
        private final int headerRows;
        private final String headerSignature;
        private final List<Cell> cells;
        private final List<Row> rows;
        private final String structure;
        public String id() { return id; }
        public int page() { return page; }
        public String locationType() { return locationType; }
        public int start() { return start; }
        public int end() { return end; }
        public String title() { return title; }
        public boolean continuationMarker() { return continuationMarker; }
        public int columns() { return columns; }
        public int headerRows() { return headerRows; }
        public String headerSignature() { return headerSignature; }
        public List<Cell> cells() { return cells; }
        public List<Row> rows() { return rows; }
        public String structure() { return structure; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Table that = (Table) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.continuationMarker, that.continuationMarker) && java.util.Objects.equals(this.columns, that.columns) && java.util.Objects.equals(this.headerRows, that.headerRows) && java.util.Objects.equals(this.headerSignature, that.headerSignature) && java.util.Objects.equals(this.cells, that.cells) && java.util.Objects.equals(this.rows, that.rows) && java.util.Objects.equals(this.structure, that.structure); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.continuationMarker); hash = 31 * hash + java.util.Objects.hashCode(this.columns); hash = 31 * hash + java.util.Objects.hashCode(this.headerRows); hash = 31 * hash + java.util.Objects.hashCode(this.headerSignature); hash = 31 * hash + java.util.Objects.hashCode(this.cells); hash = 31 * hash + java.util.Objects.hashCode(this.rows); hash = 31 * hash + java.util.Objects.hashCode(this.structure); return hash; }
        @Override public String toString() { return "Table[" + "id=" + id + ", page=" + page + ", locationType=" + locationType + ", start=" + start + ", end=" + end + ", title=" + title + ", continuationMarker=" + continuationMarker + ", columns=" + columns + ", headerRows=" + headerRows + ", headerSignature=" + headerSignature + ", cells=" + cells + ", rows=" + rows + ", structure=" + structure + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Table(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("page") int page, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("continuationMarker") boolean continuationMarker, @com.fasterxml.jackson.annotation.JsonProperty("columns") int columns, @com.fasterxml.jackson.annotation.JsonProperty("headerRows") int headerRows, @com.fasterxml.jackson.annotation.JsonProperty("headerSignature") String headerSignature, @com.fasterxml.jackson.annotation.JsonProperty("cells") List<Cell> cells, @com.fasterxml.jackson.annotation.JsonProperty("rows") List<Row> rows, @com.fasterxml.jackson.annotation.JsonProperty("structure") String structure) { cells=cn.bidcheck.compat.Java8Api.listCopyOf(cells); rows=cn.bidcheck.compat.Java8Api.listCopyOf(rows);
            this.id = id; this.page = page; this.locationType = locationType; this.start = start; this.end = end; this.title = title; this.continuationMarker = continuationMarker; this.columns = columns; this.headerRows = headerRows; this.headerSignature = headerSignature; this.cells = cells; this.rows = rows; this.structure = structure;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Issue {
        private final String code;
        private final String reason;
        private final Source source;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Issue(@com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("source") Source source) { this.code = code; this.reason = reason; this.source = source; }
        public String code() { return code; }
        public String reason() { return reason; }
        public Source source() { return source; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Issue that = (Issue) other; return java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.source, that.source); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.source); return hash; }
        @Override public String toString() { return "Issue[" + "code=" + code + ", reason=" + reason + ", source=" + source + "]"; }

        public Map<String,Object> asMap(){return map("code",code,"reason",reason,"source",source.asMap(),"page",source.page(),"locationType",source.locationType(),"requiresReview",true);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Continuation {
        private final String previousTableId;
        private final String tableId;
        private final String status;
        private final String reason;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Continuation(@com.fasterxml.jackson.annotation.JsonProperty("previousTableId") String previousTableId, @com.fasterxml.jackson.annotation.JsonProperty("tableId") String tableId, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) { this.previousTableId = previousTableId; this.tableId = tableId; this.status = status; this.reason = reason; }
        public String previousTableId() { return previousTableId; }
        public String tableId() { return tableId; }
        public String status() { return status; }
        public String reason() { return reason; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Continuation that = (Continuation) other; return java.util.Objects.equals(this.previousTableId, that.previousTableId) && java.util.Objects.equals(this.tableId, that.tableId) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.reason, that.reason); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.previousTableId); hash = 31 * hash + java.util.Objects.hashCode(this.tableId); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.reason); return hash; }
        @Override public String toString() { return "Continuation[" + "previousTableId=" + previousTableId + ", tableId=" + tableId + ", status=" + status + ", reason=" + reason + "]"; }
 }

    private final List<Table> tables;
    private final List<Issue> issues;
    private final List<Continuation> continuations;
    private TableRelationIndex(List<Table> tables, List<Issue> issues, List<Continuation> continuations) {
        this.tables=cn.bidcheck.compat.Java8Api.listCopyOf(tables); this.issues=cn.bidcheck.compat.Java8Api.listCopyOf(issues); this.continuations=cn.bidcheck.compat.Java8Api.listCopyOf(continuations);
    }
    public List<Table> tables(){return tables;}
    public List<Row> rows(){return cn.bidcheck.compat.Java8Api.toList(tables.stream().flatMap(t->t.rows().stream()));}
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
                "issues",cn.bidcheck.compat.Java8Api.toList(issues.stream().map(Issue::asMap)),"continuations",continuations,"linkedContinuations",continuations.stream().filter(c->"LINKED".equals(c.status())).count(),
                "requiresReview",rows.size()>resolved||!issues.isEmpty()||continuations.stream().anyMatch(c->!"LINKED".equals(c.status())),"geometryAvailable",false);
    }

    public static TableRelationIndex build(Document document) {
        if(document==null||document.pages()==null)return new TableRelationIndex(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        List<Table> tables=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        for(Page page:document.pages()) { if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();TableRelationIndex result=analyze(document,page);tables.addAll(result.tables);issues.addAll(result.issues); }
        List<Continuation> links=new ArrayList<>();
        restoreVerifiedItemContinuations(document,tables,issues,links);
        for(Table current:tables) if(current.continuationMarker()&&links.stream().noneMatch(link->link.tableId().equals(current.id()))) {
            List<Table> previous=cn.bidcheck.compat.Java8Api.toList(tables.stream().filter(t->t.page()==current.page()-1&&Objects.equals(t.locationType(),current.locationType())
                    &&!cn.bidcheck.compat.Java8Api.isBlank(current.title())&&current.title().equals(t.title())));
            if(previous.size()==1&&!cn.bidcheck.compat.Java8Api.isBlank(current.headerSignature())&&current.headerSignature().equals(previous.get(0).headerSignature()))
                links.add(new Continuation(previous.get(0).id(),current.id(),"LINKED","显式续表标记、相同表名和完整表头一致；仅关联表格，不跨页继承人员或空值。"));
            else links.add(new Continuation("",current.id(),"UNRESOLVED","续表的唯一前表、表名或完整表头证据不足，保留独立页面。"));
        }
        return new TableRelationIndex(tables,issues,links);
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class ItemSequence {
        private final List<Row> data;
        private final Row total;
        private final int first;
        private final int last;
        @com.fasterxml.jackson.annotation.JsonCreator
        private ItemSequence(@com.fasterxml.jackson.annotation.JsonProperty("data") List<Row> data, @com.fasterxml.jackson.annotation.JsonProperty("total") Row total, @com.fasterxml.jackson.annotation.JsonProperty("first") int first, @com.fasterxml.jackson.annotation.JsonProperty("last") int last) { this.data = data; this.total = total; this.first = first; this.last = last; }
        public List<Row> data() { return data; }
        public Row total() { return total; }
        public int first() { return first; }
        public int last() { return last; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ItemSequence that = (ItemSequence) other; return java.util.Objects.equals(this.data, that.data) && java.util.Objects.equals(this.total, that.total) && java.util.Objects.equals(this.first, that.first) && java.util.Objects.equals(this.last, that.last); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.data); hash = 31 * hash + java.util.Objects.hashCode(this.total); hash = 31 * hash + java.util.Objects.hashCode(this.first); hash = 31 * hash + java.util.Objects.hashCode(this.last); return hash; }
        @Override public String toString() { return "ItemSequence[" + "data=" + data + ", total=" + total + ", first=" + first + ", last=" + last + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class ItemAnchor {
        private final Table table;
        private final Source title;
        private final List<Association> headers;
        private final int depth;
        @com.fasterxml.jackson.annotation.JsonCreator
        private ItemAnchor(@com.fasterxml.jackson.annotation.JsonProperty("table") Table table, @com.fasterxml.jackson.annotation.JsonProperty("title") Source title, @com.fasterxml.jackson.annotation.JsonProperty("headers") List<Association> headers, @com.fasterxml.jackson.annotation.JsonProperty("depth") int depth) { this.table = table; this.title = title; this.headers = headers; this.depth = depth; }
        public Table table() { return table; }
        public Source title() { return title; }
        public List<Association> headers() { return headers; }
        public int depth() { return depth; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ItemAnchor that = (ItemAnchor) other; return java.util.Objects.equals(this.table, that.table) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.headers, that.headers) && java.util.Objects.equals(this.depth, that.depth); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.table); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.headers); hash = 31 * hash + java.util.Objects.hashCode(this.depth); return hash; }
        @Override public String toString() { return "ItemAnchor[" + "table=" + table + ", title=" + title + ", headers=" + headers + ", depth=" + depth + "]"; }
 }

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
                    cn.bidcheck.compat.Java8Api.listOf(previousNumber, firstNumber, lastNumber),boundary,after.first(),after.last());
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
        List<Association> headers=sequence.data().get(0).associations();List<String> labels=cn.bidcheck.compat.Java8Api.toList(headers.stream().map(a->a.label().replaceAll("[\\s\\p{Z}]+","")));
        // No person, company, project identity or monetary column is eligible for automatic restoration.
        if(!cn.bidcheck.compat.Java8Api.setOf("序号", "序列").contains(labels.get(0))||!cn.bidcheck.compat.Java8Api.setOf("设备名称", "产品名称", "物料名称").contains(labels.get(1))
                ||!cn.bidcheck.compat.Java8Api.setOf("数量", "采购数量").contains(labels.get(2))||!cn.bidcheck.compat.Java8Api.setOf("型号", "规格型号").contains(labels.get(3)))return null;
        if(headers.stream().anyMatch(a->!"RESOLVED".equals(a.status())||a.headerSources().size()!=1||a.headerSources().get(0).page()!=table.page()))return null;
        Page page=uniquePage(document,table);if(page==null)return null;
        int title=page.text().lastIndexOf(table.title(),table.start());
        if(title<Math.max(0,table.start()-300)||title+table.title().length()>table.start())return null;
        String gap=page.text().substring(title+table.title().length(),table.start());if(!cn.bidcheck.compat.Java8Api.isBlank(gap))return null;
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
            if(allowFinalTotal&&row==table.rows().get(table.rows().size()-1)&&cn.bidcheck.compat.Java8Api.isBlank(number)&&name.matches("(?:合计|总计)[:：]?")
                    &&quantity.matches("[0-9]+(?:\\.[0-9]+)?")&&cn.bidcheck.compat.Java8Api.isBlank(values.get(3).value())){total=row;continue;}
            if(!number.matches("[1-9][0-9]{0,5}")||cn.bidcheck.compat.Java8Api.isBlank(name)||knownHeader(name)||name.matches("[0-9.,]+")||!quantity.matches("[0-9]+(?:\\.[0-9]+)?"))return null;
            int ordinal=Integer.parseInt(number);if(!data.isEmpty()&&ordinal!=last+1)return null;
            if(data.isEmpty())first=ordinal;last=ordinal;data.add(row);
        }
        return data.isEmpty()?null:new ItemSequence(cn.bidcheck.compat.Java8Api.listCopyOf(data),total,first,last);
    }

    private static Page uniquePage(Document document,Table table){
        List<Page> candidates=cn.bidcheck.compat.Java8Api.toList(document.pages().stream().filter(p->p.number()==table.page()&&Objects.equals(Objects.toString(p.locationType(),"PAGE"),table.locationType())));
        return candidates.size()==1?candidates.get(0):null;
    }
    private static List<Source> uninterruptedBoundary(Document document,Table previous,Table current,String title){
        if(!previous.locationType().equals(current.locationType())||current.page()!=previous.page()+1)return null;
        Page before=uniquePage(document,previous),after=uniquePage(document,current);if(before==null||after==null)return null;
        if(document.pages().indexOf(after)!=document.pages().indexOf(before)+1)return null;
        String suffix=before.text().substring(previous.end()),prefix=after.text().substring(0,current.start());
        if(!paginationOnly(suffix,before.number(),"",false)||!paginationOnly(prefix,after.number(),title,current.continuationMarker()&&current.title().equals(title)))return null;
        return cn.bidcheck.compat.Java8Api.listOf(source(document,before,previous.end(),before.text().length()), source(document,after,0,current.start()));
    }
    private static boolean paginationOnly(String text,int page,String title,boolean explicitContinuation){
        for(String line:cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(text).map(cn.bidcheck.compat.Java8Api::strip).filter(value->!cn.bidcheck.compat.Java8Api.isBlank(value)))){
            if(line.equals(Integer.toString(page))||line.equals("【PDF识别文字】")||line.matches("第\\s*[0-9]+\\s*页(?:\\s*共\\s*[0-9]+\\s*页)?"))continue;
            String heading=cn.bidcheck.compat.Java8Api.strip(line.replaceAll("^#{1,6}\\s*","").replaceAll("[（(]续[）)]|续表|接上表|(?i)continued",""));
            if(explicitContinuation&&heading.equals(title))continue;
            return false;
        }return true;
    }

    /** Analyze a single source page; used by consistency without changing the OCR preparation identity. */
    public static TableRelationIndex analyze(Document document, Page page) {
        if(page==null||page.text()==null||!page.text().toLowerCase(Locale.ROOT).contains("<table"))return new TableRelationIndex(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        List<Table> tables=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        if(document==null||document.pages()==null||document.pages().stream().noneMatch(p->p.number()==page.number()&&Objects.equals(p.locationType(),page.locationType())&&Objects.equals(p.text(),page.text())))
            return new TableRelationIndex(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(new Issue("TABLE_SOURCE_NOT_BOUND","表格未绑定当前文档原文。",source(document,page,0,page.text().length()))),cn.bidcheck.compat.Java8Api.listOf());
        if(page.text().length()>MAX_PAGE)return new TableRelationIndex(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(new Issue("TABLE_PAGE_LIMIT","表格页超出结构解析长度限制。",source(document,page,0,page.text().length()))),cn.bidcheck.compat.Java8Api.listOf());
        if(Objects.toString(page.warning(),"").matches("(?s).*(?:OCR未完成|OCR_DEFERRED).*"))
            return new TableRelationIndex(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(new Issue("TABLE_SOURCE_INCOMPLETE","页面文字尚未完成识别，表格关系待核。",source(document,page,0,page.text().length()))),cn.bidcheck.compat.Java8Api.listOf());
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
        return new TableRelationIndex(tables,issues,cn.bidcheck.compat.Java8Api.listOf());
    }

    private static Table parse(Document document,Page page,int start,int end,List<Issue> issues){
        String html=page.text().substring(start,end);if(html.length()>MAX_TABLE)throw invalid("TABLE_LIMIT","单表超出结构解析长度限制。");
        List<List<MutableCell>> rawRows=new ArrayList<>();List<Integer> rowStarts=new ArrayList<>(),rowEnds=new ArrayList<>();
        Deque<String> stack=new ArrayDeque<>();Matcher tags=TAG.matcher(html);MutableCell pending=null;int at=0,cellCount=0,captionStart=-1;String caption="";
        while(tags.find()){
            String between=html.substring(at,tags.start());
            if(pending==null&&captionStart<0&&!cn.bidcheck.compat.Java8Api.isBlank(between)||between.contains("<")||between.contains(">"))throw invalid("TABLE_MARKUP_INVALID","表格结构中存在无法定位的内容。");
            String tag=tags.group(2).toLowerCase(Locale.ROOT),tail=tags.group(3);boolean closing=!tags.group(1).isEmpty();
            if(tag.equals("br")&&!closing&&pending!=null&&tail.matches("\\s*/?\\s*")){at=tags.end();continue;}
            if(!cn.bidcheck.compat.Java8Api.setOf("table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th").contains(tag))throw invalid("TABLE_TAG_UNSUPPORTED","表格含不支持的内嵌标记，需回查原文。");
            if(closing){
                if(!cn.bidcheck.compat.Java8Api.isBlank(tail)||stack.isEmpty()||!stack.pop().equals(tag))throw invalid("TABLE_UNBALANCED","表格标签嵌套或闭合不完整。");
                if(tag.equals("td")||tag.equals("th")){if(pending==null)throw invalid("TABLE_UNBALANCED","单元格边界不完整。");pending.end=start+tags.start();pending.raw=page.text().substring(pending.start,pending.end);pending.value=visible(pending.raw);rawRows.get(rawRows.size()-1).add(pending);pending=null;}
                if(tag.equals("tr"))rowEnds.add(start+tags.end());
                if(tag.equals("caption")){caption=visible(html.substring(captionStart,tags.start()));captionStart=-1;}
            }else{
                Map<String,String> attributes=attributes(tail);String parent=Objects.toString(stack.peek(),"");
                switch (tag) {case "table":{if(!parent.isEmpty())throw invalid("TABLE_NESTED","嵌套表格未展开。");} break;case "caption":{if(!parent.equals("table")||captionStart>=0||!cn.bidcheck.compat.Java8Api.isBlank(caption)||!rawRows.isEmpty())throw invalid("TABLE_CAPTION_INVALID","表名位置不明确。");captionStart=tags.end();} break;case "thead":case "tbody":case "tfoot":{if(!parent.equals("table"))throw invalid("TABLE_UNBALANCED","行组标签位置不正确。");} break;case "tr":{if(!cn.bidcheck.compat.Java8Api.setOf("table", "thead", "tbody", "tfoot").contains(parent)||rawRows.size()>=MAX_ROWS)throw invalid("TABLE_ROW_LIMIT","行组结构或行数超出可验证范围。");rawRows.add(new ArrayList<>());rowStarts.add(start+tags.start());} break;case "td":case "th":{if(!parent.equals("tr")||pending!=null||++cellCount>MAX_CELLS)throw invalid("TABLE_CELL_LIMIT","单元格位置或数量超出可验证范围。");pending=new MutableCell(rawRows.size()-1,span(attributes,"rowspan",MAX_ROWS),span(attributes,"colspan",MAX_COLUMNS),start+tags.end(),tag.equals("th")||stack.contains("thead"));} break;}
                if(!cn.bidcheck.compat.Java8Api.setOf("td", "th").contains(tag)&&(attributes.containsKey("rowspan")||attributes.containsKey("colspan")))throw invalid("TABLE_SPAN_INVALID","跨度属性不在单元格上。");
                stack.push(tag);
            }
            at=tags.end();
        }
        if(!stack.isEmpty()||pending!=null||!cn.bidcheck.compat.Java8Api.isBlank(html.substring(at))||rawRows.isEmpty())throw invalid("TABLE_UNBALANCED","表格未完整闭合或没有行。");
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
        String signature=headerRows==0?"":digest(cn.bidcheck.compat.Java8Api.toList(blocks.values().stream().map(b->cn.bidcheck.compat.Java8Api.toList(b.paths().stream().map(p->cn.bidcheck.compat.Java8Api.toList(p.stream().map(c->c.value)))))).toString());
        List<Row> rows=new ArrayList<>();HeaderBlock active=null;int firstHeader=blocks.keySet().stream().findFirst().orElse(-1);
        for(int r=0;r<grid.length;r++){
            if(blocks.containsKey(r)){active=blocks.get(r);r=active.end()-1;continue;}
            List<MutableCell> physical=rawRows.get(r);
            if(keyValue&&keyValueRow(physical)){
                List<Association> fields=new ArrayList<>();
                for(int c=0;c<physical.size();c+=2){MutableCell label=physical.get(c),value=physical.get(c+1);Cell target=converted.get(value);fields.add(new Association(id,r+1,value.column+1,target.id(),label.value,cn.bidcheck.compat.Java8Api.listOf(label.value),value.value,value.raw,target.source(),cn.bidcheck.compat.Java8Api.listOf(converted.get(label).source()),false,"RESOLVED"));}
                rows.add(new Row(id,r+1,page.number(),page.locationType(),rowStarts.get(r),rowEnds.get(r),line(page.text(),rowStarts.get(r)),"RESOLVED",fields,""));continue;
            }
            if(active==null||keyValue){
                if(keyValue&&physical.size()==1&&physical.get(0).columnSpan==columns&&physical.get(0).value.matches("^(?:备注|说明)[：:].*"))continue;
                List<Association> fields=embeddedFields(document,page,id,r,rawRows.get(r),converted);
                if(!fields.isEmpty()){rows.add(new Row(id,r+1,page.number(),page.locationType(),rowStarts.get(r),rowEnds.get(r),line(page.text(),rowStarts.get(r)),"RESOLVED",fields,""));continue;}
                if(firstHeader>r&&metadataRow(physical,columns))continue;
            }
            List<List<MutableCell>> paths=active==null?Collections.nCopies(columns,cn.bidcheck.compat.Java8Api.listOf()):active.paths();
            Set<MutableCell> splitFields=splitFieldCells(grid[r],paths);
            List<Association> associations=new ArrayList<>();Set<MutableCell> seen=Collections.newSetFromMap(new IdentityHashMap<>());int rowStart=rowStarts.get(r);boolean identityAmbiguous=identitySpanConflict(grid,r,paths),resolved=active!=null&&!identityAmbiguous;
            for(int c=0;c<columns;c++){
                MutableCell cell=grid[r][c];if(!seen.add(cell))continue;Cell value=converted.get(cell);List<MutableCell> path=paths.get(c);boolean ambiguous=headerCells.contains(cell)||identityAmbiguous||splitFields.contains(cell);
                for(int cc=c;cc<c+cell.columnSpan;cc++)if(!paths.get(cc).equals(path))ambiguous=true;
                String status=ambiguous?"AMBIGUOUS":path.isEmpty()?"UNRESOLVED":"RESOLVED";resolved&=status.equals("RESOLVED");rowStart=Math.min(rowStart,cell.start);
                associations.add(new Association(id,r+1,c+1,value.id(),active==null?"":active.labels().get(c),cn.bidcheck.compat.Java8Api.toList(path.stream().map(h->h.value)),value.value(),value.rawValue(),value.source(),cn.bidcheck.compat.Java8Api.toList(path.stream().map(h->converted.get(h).source())),cell.row<r,status));
            }
            String reason=resolved?"":identityAmbiguous?"IDENTITY_SPAN_CONFLICT":!splitFields.isEmpty()?"DATA_CELLS_SPLIT_ONE_FIELD":active!=null?"MERGED_FIELDS_OR_HEADER_BOUNDARY":columns==1?"SINGLE_COLUMN_LAYOUT":keyValue?"KEY_VALUE_STRUCTURE_UNRESOLVED":"NO_VERIFIABLE_LOCAL_HEADER";
            rows.add(new Row(id,r+1,page.number(),page.locationType(),rowStart,rowEnds.get(r),line(page.text(),rowStarts.get(r)),resolved?"RESOLVED":"UNRESOLVED",associations,reason));
        }
        if(rows.stream().anyMatch(r->!"RESOLVED".equals(r.status()))&&(headerRows>0||keyValue))issues.add(new Issue("TABLE_ASSOCIATION_AMBIGUOUS","存在跨字段合并、身份合并范围冲突或混入表头，相关关系待核。",source(document,page,start,end)));
        String preceding=cn.bidcheck.compat.Java8Extras.lines(page.text().substring(Math.max(0,start-300),start)).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)).reduce((a,b)->b).orElse("");
        String title=cn.bidcheck.compat.Java8Api.isBlank(caption)?preceding:caption;boolean continuation=title.matches("(?s).*(?:[（(]续[）)]|续表|接上表|continued).*?");
        title=cn.bidcheck.compat.Java8Api.strip(title.replaceAll("[（(]续[）)]|续表|接上表|(?i)continued","").replaceAll("^#{1,6}\\s*",""));
        if(title.length()>120||title.contains("<")||!title.matches(".*(?:表|清单|名册|Table|TABLE).*"))title="";
        int width=columns;
        boolean keyValueOnly=keyValue&&(headerRows==0||blocks.values().stream().allMatch(b->width==2&&cn.bidcheck.compat.Java8Api.setOf("字段", "项目", "信息项", "属性", "Field").contains(b.labels().get(0))&&cn.bidcheck.compat.Java8Api.setOf("内容", "信息", "值", "说明", "Value", "Details").contains(b.labels().get(1))));
        return new Table(id,page.number(),page.locationType(),start,end,title,continuation,columns,headerRows,signature,cells,rows,keyValueOnly?"KEY_VALUE_PAIRS":headerRows>0?"COLUMN_HEADERS":"UNRESOLVED");
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class HeaderBlock {
        private final int start;
        private final int end;
        private final List<List<MutableCell>> paths;
        private final List<String> labels;
        @com.fasterxml.jackson.annotation.JsonCreator
        private HeaderBlock(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("paths") List<List<MutableCell>> paths, @com.fasterxml.jackson.annotation.JsonProperty("labels") List<String> labels) { this.start = start; this.end = end; this.paths = paths; this.labels = labels; }
        public int start() { return start; }
        public int end() { return end; }
        public List<List<MutableCell>> paths() { return paths; }
        public List<String> labels() { return labels; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; HeaderBlock that = (HeaderBlock) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.paths, that.paths) && java.util.Objects.equals(this.labels, that.labels); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.paths); hash = 31 * hash + java.util.Objects.hashCode(this.labels); return hash; }
        @Override public String toString() { return "HeaderBlock[" + "start=" + start + ", end=" + end + ", paths=" + paths + ", labels=" + labels + "]"; }
 }
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
                if(!path.contains(cell)&&!cn.bidcheck.compat.Java8Api.isBlank(cell.value))path.add(cell);
            }
            paths.add(cn.bidcheck.compat.Java8Api.listCopyOf(path));
            labels.add(split?path.stream().map(cell->cell.value).reduce("",String::concat).replaceAll("[\\s\\p{Z}]+",""):path.isEmpty()?"":path.get(path.size()-1).value);
        }
        return new HeaderBlock(start,end,cn.bidcheck.compat.Java8Api.listCopyOf(paths),cn.bidcheck.compat.Java8Api.listCopyOf(labels));
    }
    private static boolean splitHeader(MutableCell[][] grid,int start,int columns){
        if(columns<2)return false;boolean fragment=false;
        for(int c=0;c<columns;c++){
            MutableCell upper=grid[start][c],lower=grid[start+1][c];
            // Only aligned physical fragments can be concatenated. Never repair a span or missing cell.
            if(upper.row!=start||lower.row!=start+1||upper.rowSpan!=1||lower.rowSpan!=1||upper.columnSpan!=1||lower.columnSpan!=1)return false;
            String joined=upper.value+lower.value;if(!knownHeader(joined))return false;
            if(!cn.bidcheck.compat.Java8Api.isBlank(upper.value)&&!cn.bidcheck.compat.Java8Api.isBlank(lower.value)){
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
            if(cell.row!=start||cn.bidcheck.compat.Java8Api.isBlank(cell.value)||cell.value.length()>60)return false;
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
        List<MutableCell> nonblank=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(c->!cn.bidcheck.compat.Java8Api.isBlank(c.value)));if(nonblank.isEmpty())return true;
        if(nonblank.size()==1&&nonblank.get(0).columnSpan>1&&nonblank.get(0).value.length()<=120&&nonblank.get(0).value.matches(".*(?:表|清单)(?:[-—].*)?"))return true;
        return nonblank.stream().allMatch(c->{Matcher match=EMBEDDED_FIELD.matcher(c.raw);return c.rowSpan==1&&match.matches()&&knownHeader(match.group(1));});
    }
    private static List<Association> embeddedFields(Document document,Page page,String tableId,int row,List<MutableCell> cells,Map<MutableCell,Cell> converted){
        List<Association> out=new ArrayList<>();for(MutableCell cell:cells){Matcher match=EMBEDDED_FIELD.matcher(cell.raw);if(!match.matches()||!knownHeader(match.group(1)))continue;
            String label=cn.bidcheck.compat.Java8Api.strip(match.group(1)),raw=match.group(2);Source value=source(document,page,cell.start+match.start(2),cell.start+match.end(2)),header=source(document,page,cell.start+match.start(1),cell.start+match.end(1));
            out.add(new Association(tableId,row+1,cell.column+1,converted.get(cell).id(),label,cn.bidcheck.compat.Java8Api.listOf(label),visible(raw),raw,value,cn.bidcheck.compat.Java8Api.listOf(header),false,"RESOLVED"));
        }return out;
    }
    private static boolean identitySpanConflict(MutableCell[][] grid,int row,List<List<MutableCell>> paths){
        List<Integer> names=new ArrayList<>(),numbers=new ArrayList<>();
        for(int c=0;c<paths.size();c++)if(!paths.get(c).isEmpty()){
            String label=paths.get(c).get(paths.get(c).size()-1).value.replaceAll("[\\s\\p{Z}]+","");
            if(cn.bidcheck.compat.Java8Api.setOf("姓名", "人员姓名").contains(label))names.add(c);
            if(cn.bidcheck.compat.Java8Api.setOf("身份证号码", "身份证号", "公民身份号码", "公民身份证号码", "社会保障号码", "社会保障号").contains(label))numbers.add(c);
        }
        for(int name:names)for(int number:numbers){MutableCell n=grid[row][name],id=grid[row][number];
            if(n.rowSpan>1&&valuesWithin(grid,n,number).size()!=1||id.rowSpan>1&&valuesWithin(grid,id,name).size()!=1)return true;
        }
        return false;
    }
    private static Set<String> valuesWithin(MutableCell[][] grid,MutableCell owner,int column){Set<String> values=new HashSet<>();for(int r=owner.row;r<owner.row+owner.rowSpan;r++)values.add(grid[r][column].value.replaceAll("[\\s\\p{Z}]+",""));return values;}
    private static boolean knownHeader(String value){String normalized=value.replaceAll("[\\s\\p{Z}]+","").replaceAll("[：:]$","").replace('（','(').replace('）',')');String withoutUnits=normalized.replaceAll("[（(](?:亿元|万元|元|人民币元|含税|不含税)[）)]","");return HEADER_LABELS.contains(normalized)||HEADER_LABELS.contains(value)||ADDITIONAL_LABELS.contains(normalized)||HEADER_LABELS.contains(withoutUnits)||ADDITIONAL_LABELS.contains(withoutUnits)||PROCUREMENT_LABELS.contains(normalized)||PROCUREMENT_LABELS.contains(withoutUnits)||ENGINEERING_LABELS.contains(normalized)||ENGINEERING_LABELS.contains(withoutUnits);}
    private static Map<String,String> attributes(String text){Map<String,String> values=new HashMap<>();Matcher matcher=ATTRIBUTE.matcher(text);int at=0;while(matcher.find()){if(matcher.start()!=at)throw invalid("TABLE_ATTRIBUTE_INVALID","属性格式无法验证。");String key=matcher.group(1).toLowerCase(Locale.ROOT),value=Objects.toString(matcher.group(2),Objects.toString(matcher.group(3),Objects.toString(matcher.group(4),"")));if(values.putIfAbsent(key,value)!=null)throw invalid("TABLE_ATTRIBUTE_INVALID","重复属性无法确定跨度。");at=matcher.end();}if(!cn.bidcheck.compat.Java8Api.isBlank(text.substring(at)))throw invalid("TABLE_ATTRIBUTE_INVALID","属性格式无法验证。");return values;}
    private static int span(Map<String,String> attributes,String key,int maximum){String value=attributes.getOrDefault(key,"1");if(!value.matches("[1-9][0-9]{0,3}"))throw invalid("TABLE_SPAN_INVALID","单元格跨度不是有效正整数。");int span=Integer.parseInt(value);if(span>maximum)throw invalid("TABLE_SPAN_LIMIT","单元格跨度超出限制。");return span;}
    private static String visible(String raw){String text=raw.replaceAll("(?i)<br\\s*/?>"," ");if(text.contains("<")||text.contains(">")||text.indexOf('\0')>=0)throw invalid("TABLE_CELL_MARKUP_UNSUPPORTED","单元格包含不能解释为纯文本的结构。");return cn.bidcheck.compat.Java8Api.strip(text.replace("&nbsp;"," ").replace("&#160;"," ").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&apos;","'").replace("&amp;","&").replaceAll("[\\s\\p{Z}]+"," "));}
    private static Source source(Document document,Page page,int start,int end){return new Source(document==null?"":Objects.toString(document.id(),""),document==null?0:document.version(),document==null?"":Objects.toString(document.sha256(),""),page.number(),Objects.toString(page.locationType(),"PAGE"),start,end);}
    private static int line(String text,int offset){int line=1;for(int i=0;i<offset;i++)if(text.charAt(i)=='\n')line++;return line;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int n=0;n<values.length;n+=2)out.put(values[n].toString(),values[n+1]);return out;}
    private static Invalid invalid(String code,String reason){return new Invalid(code,reason);}
    private static final class Invalid extends RuntimeException{final String code;Invalid(String code,String reason){super(reason);this.code=code;}}
    private static final class MutableCell{final int row,rowSpan,columnSpan,start;final boolean header;int column,end;String raw="",value="";MutableCell(int row,int rowSpan,int columnSpan,int start,boolean header){this.row=row;this.rowSpan=rowSpan;this.columnSpan=columnSpan;this.start=start;this.header=header;}}
}
