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
    record Cell(int start,int end,int column,int width,String text) {}
    record Row(int start,int end,List<Cell> cells) {}
    record Proof(int rowStart,int rowEnd,int headerStart,int headerEnd,String item,String itemKey,
                 String fieldValue,String unit,String currency,String basis,boolean duplicateItem,
                 Map<String,String> columns,Map<String,Object> arithmetic) {}

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
        if(selected==null||selected.text().isBlank())return null;
        Row header=null;Map<String,Cell> headers=Map.of();
        for(Row row:rows){if(row.start()>=target.start())break;Map<String,Cell> possible=header(row);
            if(possible.containsKey("item")&&possible.containsKey("amount")&&possible.containsKey("taxAmount")){header=row;headers=possible;}}
        if(header==null)return null;
        String column=field.equals("totalPrice")?"amount":field;
        Cell required=headers.get(column);if(required==null||selected.column()!=required.column()||selected.width()!=required.width())return null;
        Map<String,String> values=columns(target,headers);String item=values.getOrDefault("item","");
        // A summary row is its own object. It must not borrow the nearest item's identity.
        boolean aggregate=item.matches("合计|小计");
        if(item.isBlank()||item.matches(".*(?:无法|模糊|未识别|详见).*"))return null;
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
        String basis="PRINTED_INVOICE_COLUMN",label=required.text();Map<String,Object> arithmetic=Map.of();
        if(field.equals("unitPrice")){
            basis=label.contains("不含税")?"CNY_EXCLUSIVE":label.contains("含税")?"CNY_INCLUSIVE":"";
            if(basis.isBlank()&&table.contains("价税合计")){
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
            String label=cell.text();String key=switch(label){
                case "项目名称","货物或应税劳务、服务名称","货物或应税劳务服务名称"->"item";
                case "规格型号","型号"->"productModel";case "单位"->"unit";case "数量"->"quantity";
                case "单价","不含税单价","含税单价"->"unitPrice";case "金额","不含税金额"->"amount";
                case "税率","税率/征收率","税率（征收率）"->"taxRate";case "税额"->"taxAmount";default->"";};
            if(!key.isBlank()&&result.putIfAbsent(key,cell)!=null)return Map.of();
        }return result;
    }
    private static Map<String,String> columns(Row row,Map<String,Cell> headers){
        Map<String,String> result=new LinkedHashMap<>();for(var entry:headers.entrySet()){
            Cell head=entry.getValue();List<Cell> matching=row.cells().stream().filter(c->c.column()<=head.column()&&c.column()+c.width()>=head.column()+head.width()).toList();
            if(matching.size()==1)result.put(entry.getKey(),matching.get(0).text());
        }return result;
    }
    static boolean sameValue(String expected,String actual,String field){
        if(Set.of("unitPrice","taxAmount","quantity","totalPrice","amount","taxRate").contains(field))try{return decimal(expected).compareTo(decimal(actual))==0;}catch(RuntimeException invalid){return false;}
        return plain(expected).equals(plain(actual));
    }
    private static BigDecimal decimal(String raw){if(raw==null)throw new IllegalArgumentException();String clean=raw.replaceAll("[¥￥,，\\s]","");if(!clean.matches("[+-]?\\d+(?:\\.\\d+)?"))throw new IllegalArgumentException();return new BigDecimal(clean);}
    private static boolean close(BigDecimal a,BigDecimal b){return a.setScale(2,RoundingMode.HALF_UP).compareTo(b.setScale(2,RoundingMode.HALF_UP))==0;}
    private static String plain(String raw){return raw.replaceAll("<[^>]+>","").replace("&nbsp;","").replaceAll("\\s+","");}
}
