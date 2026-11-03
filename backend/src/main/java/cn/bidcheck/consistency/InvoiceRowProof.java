package cn.bidcheck.consistency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

/** Source-only invoice column proof. Neither a purchase invoice nor its enclosing section is a bid price. */
final class InvoiceRowProof {
    private static final Pattern TABLE=Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table\\s*>");
    private static final Pattern ROW=Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern CELL=Pattern.compile("(?is)<t[dh]\\b([^>]*)>(.*?)</t[dh]\\s*>");
    private static final Pattern SPAN=Pattern.compile("(?i)(rowspan|colspan)\\s*=\\s*['\"]?(\\d+)");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Cell {
        private final int start;
        private final int end;
        private final int column;
        private final int width;
        private final String text;
        @com.fasterxml.jackson.annotation.JsonCreator
        Cell(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("column") int column, @com.fasterxml.jackson.annotation.JsonProperty("width") int width, @com.fasterxml.jackson.annotation.JsonProperty("text") String text) { this.start = start; this.end = end; this.column = column; this.width = width; this.text = text; }
        public int start() { return start; }
        public int end() { return end; }
        public int column() { return column; }
        public int width() { return width; }
        public String text() { return text; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.column, that.column) && java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.text, that.text); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.column); hash = 31 * hash + java.util.Objects.hashCode(this.width); hash = 31 * hash + java.util.Objects.hashCode(this.text); return hash; }
        @Override public String toString() { return "Cell[" + "start=" + start + ", end=" + end + ", column=" + column + ", width=" + width + ", text=" + text + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Row {
        private final int start;
        private final int end;
        private final List<Cell> cells;
        @com.fasterxml.jackson.annotation.JsonCreator
        Row(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("cells") List<Cell> cells) { this.start = start; this.end = end; this.cells = cells; }
        public int start() { return start; }
        public int end() { return end; }
        public List<Cell> cells() { return cells; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Row that = (Row) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.cells, that.cells); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.cells); return hash; }
        @Override public String toString() { return "Row[" + "start=" + start + ", end=" + end + ", cells=" + cells + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Proof {
        private final int rowStart;
        private final int rowEnd;
        private final int headerStart;
        private final int headerEnd;
        private final String item;
        private final String itemKey;
        private final String fieldValue;
        private final String unit;
        private final String currency;
        private final String basis;
        private final boolean duplicateItem;
        private final Map<String,String> columns;
        private final Map<String,Object> arithmetic;
        @com.fasterxml.jackson.annotation.JsonCreator
        Proof(@com.fasterxml.jackson.annotation.JsonProperty("rowStart") int rowStart, @com.fasterxml.jackson.annotation.JsonProperty("rowEnd") int rowEnd, @com.fasterxml.jackson.annotation.JsonProperty("headerStart") int headerStart, @com.fasterxml.jackson.annotation.JsonProperty("headerEnd") int headerEnd, @com.fasterxml.jackson.annotation.JsonProperty("item") String item, @com.fasterxml.jackson.annotation.JsonProperty("itemKey") String itemKey, @com.fasterxml.jackson.annotation.JsonProperty("fieldValue") String fieldValue, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("currency") String currency, @com.fasterxml.jackson.annotation.JsonProperty("basis") String basis, @com.fasterxml.jackson.annotation.JsonProperty("duplicateItem") boolean duplicateItem, @com.fasterxml.jackson.annotation.JsonProperty("columns") Map<String,String> columns, @com.fasterxml.jackson.annotation.JsonProperty("arithmetic") Map<String,Object> arithmetic) { this.rowStart = rowStart; this.rowEnd = rowEnd; this.headerStart = headerStart; this.headerEnd = headerEnd; this.item = item; this.itemKey = itemKey; this.fieldValue = fieldValue; this.unit = unit; this.currency = currency; this.basis = basis; this.duplicateItem = duplicateItem; this.columns = columns; this.arithmetic = arithmetic; }
        public int rowStart() { return rowStart; }
        public int rowEnd() { return rowEnd; }
        public int headerStart() { return headerStart; }
        public int headerEnd() { return headerEnd; }
        public String item() { return item; }
        public String itemKey() { return itemKey; }
        public String fieldValue() { return fieldValue; }
        public String unit() { return unit; }
        public String currency() { return currency; }
        public String basis() { return basis; }
        public boolean duplicateItem() { return duplicateItem; }
        public Map<String,String> columns() { return columns; }
        public Map<String,Object> arithmetic() { return arithmetic; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Proof that = (Proof) other; return java.util.Objects.equals(this.rowStart, that.rowStart) && java.util.Objects.equals(this.rowEnd, that.rowEnd) && java.util.Objects.equals(this.headerStart, that.headerStart) && java.util.Objects.equals(this.headerEnd, that.headerEnd) && java.util.Objects.equals(this.item, that.item) && java.util.Objects.equals(this.itemKey, that.itemKey) && java.util.Objects.equals(this.fieldValue, that.fieldValue) && java.util.Objects.equals(this.unit, that.unit) && java.util.Objects.equals(this.currency, that.currency) && java.util.Objects.equals(this.basis, that.basis) && java.util.Objects.equals(this.duplicateItem, that.duplicateItem) && java.util.Objects.equals(this.columns, that.columns) && java.util.Objects.equals(this.arithmetic, that.arithmetic); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.rowStart); hash = 31 * hash + java.util.Objects.hashCode(this.rowEnd); hash = 31 * hash + java.util.Objects.hashCode(this.headerStart); hash = 31 * hash + java.util.Objects.hashCode(this.headerEnd); hash = 31 * hash + java.util.Objects.hashCode(this.item); hash = 31 * hash + java.util.Objects.hashCode(this.itemKey); hash = 31 * hash + java.util.Objects.hashCode(this.fieldValue); hash = 31 * hash + java.util.Objects.hashCode(this.unit); hash = 31 * hash + java.util.Objects.hashCode(this.currency); hash = 31 * hash + java.util.Objects.hashCode(this.basis); hash = 31 * hash + java.util.Objects.hashCode(this.duplicateItem); hash = 31 * hash + java.util.Objects.hashCode(this.columns); hash = 31 * hash + java.util.Objects.hashCode(this.arithmetic); return hash; }
        @Override public String toString() { return "Proof[" + "rowStart=" + rowStart + ", rowEnd=" + rowEnd + ", headerStart=" + headerStart + ", headerEnd=" + headerEnd + ", item=" + item + ", itemKey=" + itemKey + ", fieldValue=" + fieldValue + ", unit=" + unit + ", currency=" + currency + ", basis=" + basis + ", duplicateItem=" + duplicateItem + ", columns=" + columns + ", arithmetic=" + arithmetic + "]"; }
}

    static Proof locate(String text,int offset,String field) {
        Matcher tables=TABLE.matcher(text);String table="";int tableStart=-1,tableEnd=-1;
        while(tables.find())if(tables.start()<=offset&&offset<tables.end()){
            if(tableStart>=0||tables.group(1).toLowerCase(Locale.ROOT).contains("<table"))return null;
            table=tables.group();tableStart=tables.start();tableEnd=tables.end();
        }
        if(tableStart<0)return null;
        List<Row> rows=new ArrayList<>();Map<Integer,Integer> reserved=new HashMap<>();Matcher rm=ROW.matcher(table);int rowIndex=0;
        while(rm.find()){
            List<Cell> cells=new ArrayList<>();Matcher cm=CELL.matcher(rm.group(1));int col=0;
            while(cm.find()){
                while(reserved.getOrDefault(col,0)>rowIndex)col++;
                int width=1,height=1;Matcher span=SPAN.matcher(cm.group(1));
                while(span.find()){int n=Integer.parseInt(span.group(2));if(n<1||n>40)return null;if(span.group(1).equalsIgnoreCase("colspan"))width=n;else height=n;}
                for(int c=col;c<col+width;c++)if(reserved.getOrDefault(c,0)>rowIndex)return null;
                int start=tableStart+rm.start(1)+cm.start(2),end=tableStart+rm.start(1)+cm.end(2);
                cells.add(new Cell(start,end,col,width,plain(cm.group(2))));
                if(height>1)for(int c=col;c<col+width;c++)reserved.put(c,rowIndex+height);
                col+=width;
            }
            rows.add(new Row(tableStart+rm.start(),tableStart+rm.end(),cells));rowIndex++;
        }
        Row target=rows.stream().filter(r->r.start()<=offset&&offset<r.end()).findFirst().orElse(null);
        if(target==null)return null;
        Cell selected=target.cells().stream().filter(c->c.start()<=offset&&offset<c.end()).findFirst().orElse(null);
        if(selected==null||cn.bidcheck.compat.Java8Api.isBlank(selected.text()))return null;
        Row header=null;Map<String,Cell> headers=cn.bidcheck.compat.Java8Api.mapOf();
        for(Row row:rows){if(row.start()>=target.start())break;Map<String,Cell> possible=header(row);
            if(possible.containsKey("item")&&possible.containsKey("amount")&&possible.containsKey("taxAmount")){header=row;headers=possible;}}
        if(header==null)return null;
        String column=field.equals("totalPrice")?"amount":field;
        Cell required=headers.get(column);if(required==null||selected.column()!=required.column()||selected.width()!=required.width())return null;
        Map<String,String> values=columns(target,headers);String item=values.getOrDefault("item","");
        // A summary row is its own object. It must not borrow the nearest item's identity.
        boolean aggregate=item.matches("合计|小计");
        if(cn.bidcheck.compat.Java8Api.isBlank(item)||item.matches(".*(?:无法|模糊|未识别|详见).*"))return null;
        String model=field.equals("productModel")?"":values.getOrDefault("productModel","");
        String unit=values.getOrDefault("unit","");
        String itemKey=item+"|"+model+"|"+unit;
        int duplicates=0;
        for(Row row:rows)if(row.start()>header.start()){
            Map<String,String> v=columns(row,headers);
            String key=v.getOrDefault("item","")+"|"+(field.equals("productModel")?"":v.getOrDefault("productModel",""))+"|"+v.getOrDefault("unit","");
            if(itemKey.equals(key))duplicates++;
        }
        String currency=table.matches("(?s).*(?:人民币|CNY|RMB|¥|￥).*")&&!table.matches("(?is).*(?:USD|美元|EUR|欧元).*")?"CNY":"";
        String basis="PRINTED_INVOICE_COLUMN",label=required.text();Map<String,Object> arithmetic=cn.bidcheck.compat.Java8Api.mapOf();
        if(field.equals("unitPrice")){
            basis=label.contains("不含税")?"CNY_EXCLUSIVE":label.contains("含税")?"CNY_INCLUSIVE":"";
            if(cn.bidcheck.compat.Java8Api.isBlank(basis)&&table.contains("价税合计")){
                try {
                    BigDecimal quantity=decimal(values.get("quantity")),price=decimal(values.get("unitPrice"));
                    BigDecimal amount=decimal(values.get("amount")),tax=decimal(values.get("taxAmount"));
                    String rawRate=values.getOrDefault("taxRate","");if(!rawRate.endsWith("%"))throw new IllegalArgumentException();
                    BigDecimal rate=decimal(rawRate.substring(0,rawRate.length()-1)).movePointLeft(2);
                    if(quantity.signum()>0&&rate.signum()>0&&rate.compareTo(BigDecimal.ONE)<0&&price.signum()>=0
                        &&close(quantity.multiply(price),amount)&&close(amount.multiply(rate),tax)){
                        basis="CNY_EXCLUSIVE";arithmetic=values("basis","PRINTED_INVOICE_COLUMN_ARITHMETIC","quantity",quantity.toPlainString(),
                            "unitPrice",price.toPlainString(),"amount",amount.toPlainString(),"rate",rate.toPlainString(),"taxAmount",tax.toPlainString(),"rounding","0.01");
                    }
                }catch(RuntimeException incomplete){/* Unknown basis stays unknown. */}
            }
        }
        if(aggregate)basis="INVOICE_"+item+"_COLUMN";
        return new Proof(target.start(),target.end(),header.start(),header.end(),item,itemKey,selected.text(),unit,currency,basis,duplicates!=1,values,arithmetic);
    }
    private static Map<String,Cell> header(Row row){
        Map<String,Cell> result=new LinkedHashMap<>();for(Cell cell:row.cells()){
            String label=cell.text();String key;
switch (label) {case "项目名称":case "货物或应税劳务、服务名称":case "货物或应税劳务服务名称":key = "item"; break;case "规格型号":case "型号":key = "productModel"; break;case "单位":key = "unit"; break;case "数量":key = "quantity"; break;case "单价":case "不含税单价":case "含税单价":key = "unitPrice"; break;case "金额":case "不含税金额":key = "amount"; break;case "税率":case "税率/征收率":case "税率（征收率）":key = "taxRate"; break;case "税额":key = "taxAmount"; break;default:key = ""; break;}
            if(!cn.bidcheck.compat.Java8Api.isBlank(key)&&result.putIfAbsent(key,cell)!=null)return cn.bidcheck.compat.Java8Api.mapOf();
        }return result;
    }
    private static Map<String,String> columns(Row row,Map<String,Cell> headers){
        Map<String,String> result=new LinkedHashMap<>();for(java.util.Map.Entry<java.lang.String, cn.bidcheck.consistency.InvoiceRowProof.Cell> entry:headers.entrySet()){
            Cell head=entry.getValue();List<Cell> matching=cn.bidcheck.compat.Java8Api.toList(row.cells().stream().filter(c->c.column()<=head.column()&&c.column()+c.width()>=head.column()+head.width()));
            if(matching.size()==1)result.put(entry.getKey(),matching.get(0).text());
        }return result;
    }
    static boolean sameValue(String expected,String actual,String field){
        if(cn.bidcheck.compat.Java8Api.setOf("unitPrice", "taxAmount", "quantity", "totalPrice", "amount", "taxRate").contains(field))try{return decimal(expected).compareTo(decimal(actual))==0;}catch(RuntimeException invalid){return false;}
        return plain(expected).equals(plain(actual));
    }
    private static BigDecimal decimal(String raw){if(raw==null)throw new IllegalArgumentException();String clean=raw.replaceAll("[¥￥,，\\s]","");if(!clean.matches("[+-]?\\d+(?:\\.\\d+)?"))throw new IllegalArgumentException();return new BigDecimal(clean);}
    private static boolean close(BigDecimal a,BigDecimal b){return a.setScale(2,RoundingMode.HALF_UP).compareTo(b.setScale(2,RoundingMode.HALF_UP))==0;}
    private static String plain(String raw){return raw.replaceAll("<[^>]+>","").replace("&nbsp;","").replaceAll("\\s+","");}
}
