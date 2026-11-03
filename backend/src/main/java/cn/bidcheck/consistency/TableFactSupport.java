package cn.bidcheck.consistency;

import cn.bidcheck.document.TableRelationIndex.*;
import cn.bidcheck.domain.Domain.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Bind interpreted cells back to exact input spans after the existing field normalizers run. */
final class TableFactSupport {
    private TableFactSupport() { }
    static String quote(Page page,Source source){
        if(page==null||source==null||page.text()==null||page.number()!=source.page()||!Objects.equals(page.locationType(),source.locationType())
                ||source.start()<0||source.end()<source.start()||source.end()>page.text().length())throw unbound("page or source offsets");
        return page.text().substring(source.start(),source.end());
    }
    private static Page sourcePage(Document doc,Source source){
        if(doc==null||source==null||doc.pages()==null||!Objects.equals(doc.id(),source.documentId())||doc.version()!=source.documentVersion()||!Objects.equals(doc.sha256(),source.documentHash()))throw unbound("document identity");
        List<Page> matches=cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().filter(p->p.number()==source.page()&&Objects.equals(p.locationType(),source.locationType())));
        if(matches.size()!=1)throw unbound("missing or ambiguous page");Page actual=matches.get(0);
        if(actual.text()==null||source.start()<0||source.end()<source.start()||source.end()>actual.text().length())throw unbound("source offsets");
        return actual;
    }
    static Evidence evidence(Document doc,Page ignoredCurrentPage,Source source){
        Page actual=sourcePage(doc,source);
        return new Evidence(doc.id(),doc.name(),actual.number(),actual.locationType(),quote(actual,source),"p"+actual.number()+"-s"+source.start(),0);
    }
    private static IllegalArgumentException unbound(String reason){return new IllegalArgumentException("TABLE_SOURCE_NOT_BOUND: "+reason);}
    private static void requireBoundRow(Document doc,Page page,Row row){
        if(page==null||row==null||!"RESOLVED".equals(row.status())||page.number()!=row.page()||!Objects.equals(page.locationType(),row.locationType())||row.associations().isEmpty())throw unbound("row identity or state");
        for(Association association:row.associations()){
            Page actual=sourcePage(doc,association.source());
            if(!actual.equals(page)||!"RESOLVED".equals(association.status())||!association.rawValue().equals(quote(actual,association.source()))
                    ||association.headerSources().isEmpty()||association.headerSources().size()!=association.headerPath().size())throw unbound("cell or header path");
            for(int i=0;i<association.headerSources().size();i++){
                Source ref=association.headerSources().get(i);Page header=sourcePage(doc,ref);
                if(!visible(quote(header,ref)).equals(association.headerPath().get(i)))throw unbound("header text");
            }
        }
    }
    private static String visible(String text){
        return cn.bidcheck.compat.Java8Api.strip(text.replaceAll("(?i)<br\\s*/?>"," ").replace("&nbsp;"," ").replace("&#160;"," ").replace("&lt;","<").replace("&gt;",">")
                .replace("&quot;","\"").replace("&#39;","'").replace("&apos;","'").replace("&amp;","&").replaceAll("[\\s\\p{Z}]+"," "));
    }
    static String semanticHeader(Association association){
        String label=association.label();
        if(!Pattern.compile("亿元|万元|元").matcher(label).find())for(int i=association.headerPath().size()-1;i>=0;i--){Matcher unit=Pattern.compile("亿元|万元|元").matcher(association.headerPath().get(i));if(unit.find())return label+"（"+unit.group()+"）";}
        return label;
    }
    static String semanticHeader(Row row,Association association){
        Set<String> labels=new HashSet<>();for(Association cell:row.associations())labels.add(cell.label().replaceAll("[\\s\\p{Z}]+",""));
        boolean explicitUnitPrice=labels.stream().anyMatch(label->label.matches("(?:最高)?(?:不含(?:增值)?税|含(?:增值)?税)?单价(?:基价|限价)?(?:[（(](?:亿元|万元|元|人民币元)[）)])?"));
        boolean engineeringItems=labels.containsAll(cn.bidcheck.compat.Java8Api.setOf("项目描述", "单位"))&&(labels.contains("数量")||labels.contains("物料名称")&&explicitUnitPrice);
        boolean invoiceItems=labels.contains("税率/征收率")&&(labels.containsAll(cn.bidcheck.compat.Java8Api.setOf("规格型号", "数量"))||labels.containsAll(cn.bidcheck.compat.Java8Api.setOf("建筑服务发生地", "建筑项目名称")));
        // The literal source header remains in tableAssociation. Only the extraction interpretation changes.
        // A line-item name must not become the identity of the procurement project or historical contract.
        if(association.label().replaceAll("[\\s\\p{Z}]+","").equals("项目名称")&&(engineeringItems||invoiceItems))return "品目名称";
        return semanticHeader(association);
    }
    static Evidence header(Document doc,Page page,Row row){
        requireBoundRow(doc,page,row);
        List<Source> refs=cn.bidcheck.compat.Java8Api.toList(row.associations().stream().flatMap(a->a.headerSources().stream()).distinct());Source first=refs.get(0);
        // An aggregate cannot combine offsets from different physical pages; bind() retains every individual path.
        if(refs.stream().anyMatch(ref->ref.page()!=first.page()||!ref.locationType().equals(first.locationType())))return evidence(doc,page,first);
        int start=refs.stream().mapToInt(Source::start).min().orElseThrow(java.util.NoSuchElementException::new),end=refs.stream().mapToInt(Source::end).max().orElseThrow(java.util.NoSuchElementException::new);
        // A wide multi-level header need not be one oversized ledger quote; each fact also cites its own header path.
        if(end-start>10000)return evidence(doc,page,first);
        return evidence(doc,page,new Source(first.documentId(),first.documentVersion(),first.documentHash(),first.page(),first.locationType(),start,end));
    }
    static void bind(Document doc,Page page,Row row,List<Map<String,Object>> facts,int first) {
        requireBoundRow(doc,page,row);
        for(int i=first;i<facts.size();i++) {
            Map<String,Object> fact=facts.get(i);String raw=Objects.toString(fact.get("rawValue"),"");
            List<Association> matches=cn.bidcheck.compat.Java8Api.toList(row.associations().stream().filter(a->cn.bidcheck.compat.Java8Api.strip(a.rawValue()).equals(raw)&&matches(Objects.toString(fact.get("field"),""),Objects.toString(fact.get("fieldLabel"),""),a.label())));
            if(fact.get("sourceColumn") instanceof Number&&((Number) (fact.get("sourceColumn"))).intValue()>0&&((Number) (fact.get("sourceColumn"))).intValue()<=row.associations().size()) {
                Association exact=row.associations().get(((Number) (fact.get("sourceColumn"))).intValue()-1);if(matches.contains(exact))matches=cn.bidcheck.compat.Java8Api.listOf(exact);
            }
            if(matches.size()!=1){fact.put("comparability","INCOMPLETE");fact.put("scopeReason","表格字段不能唯一绑定到原始单元格");continue;}
            Association association=matches.get(0);Evidence value=evidence(doc,page,association.source());
            List<Evidence> refs=new ArrayList<>();
            for(Source source:association.headerSources())refs.add(evidence(doc,page,source));
            if(fact.get("evidence") instanceof Evidence&&!((Evidence) (fact.get("evidence"))).equals(value))refs.add(((Evidence) (fact.get("evidence"))));
            if(fact.get("contextEvidences") instanceof List<?>)for(Object entry:((List<?>) (fact.get("contextEvidences"))))if(entry instanceof Evidence)refs.add(((Evidence) (entry)));
            refs=cn.bidcheck.compat.Java8Api.toList(refs.stream().filter(e->!cn.bidcheck.compat.Java8Api.isBlank(e.quote())&&!e.equals(value)).distinct());
            if(refs.size()>15||refs.stream().anyMatch(e->e.quote().length()>10000)){fact.put("comparability","INCOMPLETE");fact.put("scopeReason","表格与章节引用超出核对上限，需人工复核");}
            fact.put("evidence",value);fact.put("sourceOffset",association.source().start());fact.put("sourceRowLine",row.line());
            fact.put("contextEvidences",cn.bidcheck.compat.Java8Api.toList(refs.stream().filter(e->e.quote().length()<=10000).limit(15)));fact.put("tableAssociation",association.asMap());
            fact.put("sourceColumn",association.column());
            // rawValue remains the literal source slice; only the comparison value uses the verified HTML text decoding.
            if("companyName".equals(fact.get("field"))||cn.bidcheck.compat.Java8Api.setOf("IDENTIFIER", "TEXT").contains(Objects.toString(fact.get("valueType"),"")))
                fact.put("value",association.value().replace('（','(').replace('）',')').replaceAll("[\\s\\u3000]","").toUpperCase(Locale.ROOT));
            if("totalPrice".equals(fact.get("field"))||"MONEY".equals(fact.get("valueType"))) {
                String tax=headerTax(association);
                if(!cn.bidcheck.compat.Java8Api.isBlank(tax)) {
                    String[] previous=Objects.toString(fact.get("basis"),"|").split("\\|",-1);fact.put("basis",tax+"|"+(previous.length>1?previous[1]:""));
                    if(fact.get("dimensions") instanceof Map<?,?>){Map<String,Object> dimensions=new LinkedHashMap<>();((Map<?,?>) (fact.get("dimensions"))).forEach((k,v)->dimensions.put(k.toString(),v));dimensions.put("taxBasis",tax);fact.put("dimensions",dimensions);}
                    if(tax.equals("UNRESOLVED")){fact.put("comparability","INCOMPLETE");fact.put("scopeReason","同一表头层级包含多个税额口径，不能确认本单元格归属");}
                }
            }
            fact.put("rowId",row.tableId()+":row:"+row.row());
            fact.put("id","occ-"+UUID.nameUUIDFromBytes((fact.get("id")+"|"+row.tableId()+"|"+row.row()+"|"+association.cellId()).getBytes(StandardCharsets.UTF_8)));
        }
    }
    private static String headerTax(Association association){for(int i=association.headerPath().size()-1;i>=0;i--){String header=association.headerPath().get(i);boolean exclusive=header.contains("不含税")||header.contains("税前"),inclusive=header.replace("不含税","").contains("含税")||header.contains("税后");if(exclusive||inclusive)return exclusive&&inclusive?"UNRESOLVED":exclusive?"EXCLUSIVE":"INCLUSIVE";}return "";}
    private static boolean matches(String field,String label,String header){
        String plain=cn.bidcheck.compat.Java8Api.strip(header.replaceAll("[（(][^）)]*[）)]",""));
        if(!cn.bidcheck.compat.Java8Api.isBlank(label))return label.equals(plain);
        switch (field) {case "companyName":return plain.matches("(?:投标人|投标单位|响应人|响应单位|供应商)(?:名称)?");case "totalPrice":return plain.matches("投标总报价|投标报价|投标总价|总报价|总价");case "openingTime":return plain.matches("开标时间|开标日期");case "personName":return plain.matches("项目负责人|项目经理|技术负责人|授权代表|被授权人|法定代表人|安全负责人|质量负责人|财务负责人|姓名|人员姓名");case "personRole":return plain.matches("岗位|职务|拟任职务|担任职务|本项目职务|在本项目中担任职务");default:return false;}
    }
    static boolean html(String line){return line.matches("(?is).*<\\/?(?:table|thead|tbody|tfoot|tr|td|th)\\b.*");}
}
