package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DateTimeException;
import java.util.*;
import java.util.regex.*;

/** Finds bounded, cited text anomalies; absence of a hit never proves full-document coverage. */
final class FullTextReview {
    private static final String SIGNING_LABEL="(?:签署日期|签署时间|签订日期|签订时间|签字日期|签字时间|签章日期|签章时间|落款日期|落款时间)";
    private static final Pattern SIGNING=Pattern.compile(SIGNING_LABEL+"\\s*[:：=]?\\s*([^；;。\\t]*)");
    private static final Pattern CLOCK=Pattern.compile("^\\s*T?\\s*(\\d{1,2})\\s*[:：时]\\s*(\\d{1,2})(?:\\s*[:：分]\\s*(\\d{1,2}))?\\s*(?:分|秒)?(?=$|[\\s（(，,]|北京时间)");
    private static final Pattern BLANK=Pattern.compile("(?:项目名称|项目编号|招标编号|投标人(?:名称)?|应答人(?:名称)?|供应商名称|统一社会信用代码|开户银行|银行账号|签署日期|签章日期|落款日期|联系电话|联系人|投标报价)\\s*[:：=]\\s*(?:[_＿—－-]{2,}|待填写|待填|待补充|TBD|[XxＸｘ]{2,}|[（(]\\s*(?:请填写|填写|待填)[）)])(?=$|[\\s，,；;。])");
    private static final Pattern EMPTY_LABEL=Pattern.compile("(?:项目名称|项目编号|招标编号|投标人名称|应答人名称|供应商名称|统一社会信用代码|开户银行|银行账号|签署日期|签章日期|落款日期|联系电话|联系人|投标报价)\\s*[:：=]\\s*$");
    private static final Map<String,String> TYPOS=Map.of("投标文仵","投标文件","招标文仵","招标文件","授杈委托书","授权委托书","质保其","质保期","营叶执照","营业执照");
    private static final String ITEM_UNITS="平方米|公斤|千克|用户|账号|许可|台|套|个|次|人|名|份|件|项|座|处|站|米|吨|箱|m²|m2|kg|m|t";
    private static final String DECIMAL="[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?";
    private static final String VALUE_END="(?=$|[\\s,，;；。|｜\\t）)])";
    private static final Pattern QUANTITY=Pattern.compile("(?<![\\p{L}0-9])数量\\s*[:：=]\\s*("+DECIMAL+")\\s*("+ITEM_UNITS+")?"+VALUE_END);
    private static final Pattern ROW_OBJECT=Pattern.compile("(?<![\\p{L}0-9])(?:品目名称|品目|设备名称|产品名称|商品名称|项目名称|标的名称|清单项)\\s*[:：=]\\s*([^;；。|｜\\t,，]{1,200})");
    private static final Pattern ROW_CURRENCY=Pattern.compile("(?:币种|货币|币别)\\s*[:：=]\\s*([^;；。|｜\\t,，]{1,40})");

    Evaluation evaluate(Checkpoint cp,Document doc,List<Fact> facts) {
        if(!"REVIEW".equals(cp.operator())) return unknown(cp,"全文检查运算符应为 REVIEW",List.of());
        return switch(cp.field()) {
            case "fullTextConsistency" -> consistency(cp,doc,facts);
            case "amountAccuracy" -> amounts(cp,doc,facts);
            case "missingFields" -> blanks(cp,doc);
            case "signatureDate" -> signing(cp,doc,false);
            case "signingAfterOpening" -> signing(cp,doc,true);
            case "typos" -> typos(cp,doc);
            case "certificateCoverage" -> certificates(cp,doc,facts);
            default -> unknown(cp,"不支持的全文检查字段",List.of());
        };
    }

    private Evaluation consistency(Checkpoint cp,Document doc,List<Fact> facts) {
        var report=new cn.bidcheck.consistency.ConsistencyEngine().scan(doc,List.of());
        var groups=cn.bidcheck.consistency.ConsistencyRunSupport.maps(report.get("groups"));
        if(groups.isEmpty()) return unknown(cp,"已按全文一致性目录扫描可读文本，未发现可比较字段的差异；未识别到事实不代表全部信息均已核实",List.of());
        List<Evidence> evidences=groups.stream().flatMap(g->((List<?>)g.get("evidences")).stream()).map(Evidence.class::cast).distinct().toList();
        boolean conflict=groups.stream().anyMatch(g->"CONFLICT".equals(g.get("status")));
        if(groups.stream().allMatch(g->"EVIDENCE_INSUFFICIENT".equals(g.get("status"))))
            return unknown(cp,"已识别事实缺少比较所需的对象、单位或条件，请核对原文；这不表示相关材料不存在",evidences);
        return result(conflict?"FAIL":"WARNING","发现 "+groups.size()+" 组全文信息差异；需按主体、标段、口径逐组对照原文复核",evidences);
    }

    private Evaluation amounts(Checkpoint cp,Document doc,List<Fact> facts) {
        List<Evidence> issues=new ArrayList<>();
        for(Located line:lines(doc)) {
            String text=line.text();
            // A single unambiguous row is required. Never combine numbers from different rows or tax bases.
            if(text.matches(".*(?:折扣|优惠|税额|税率|含税|不含税|税前|税后).*"))continue;
            Quantity quantity=quantity(text);
            Money price=money(text,"单价"),subtotal=money(text,"(?:小计|行金额|金额|合价)");
            if(quantity==null||price==null||subtotal==null||!compatibleRow(text,quantity,price,subtotal))continue;
            if(quantity.value().multiply(price.value()).subtract(subtotal.value()).abs().compareTo(subtotal.tolerance())>0)issues.add(line.evidence());
        }
        var report=new cn.bidcheck.consistency.ConsistencyEngine().scan(doc,List.of());
        for(var group:cn.bidcheck.consistency.ConsistencyRunSupport.maps(report.get("groups")))
            if(Set.of("CONFLICT","SUSPECTED").contains(Objects.toString(group.get("status"),""))
                    &&("totalPrice".equals(group.get("field"))||"ARITHMETIC".equals(group.get("relationType"))))
                for(Object evidence:(List<?>)group.get("evidences"))issues.add((Evidence)evidence);
        if(!issues.isEmpty()) return result("WARNING","发现数量乘单价与金额不符，或相同税额口径下的总报价出现不同值；需核对报价范围和计算规则",issues);
        return unknown(cp,"未发现明确的逐行乘算或同口径总价异常；金额大写、复杂汇总、分项报价与图片金额仍需复核",List.of());
    }
    private Quantity quantity(String text) {
        if(!singleLabel(text,"数量"))return null;
        Matcher m=QUANTITY.matcher(text);if(!m.find())return null;
        Quantity result=new Quantity(new BigDecimal(m.group(1).replace(",","")),Objects.toString(m.group(2),""));
        return m.find()?null:result;
    }
    private Money money(String text,String label) {
        if(!singleLabel(text,label))return null;
        Matcher m=Pattern.compile("(?<![\\p{L}0-9])"+label+"\\s*[:：=]\\s*(人民币|CNY|USD|EUR|美元|欧元|[￥¥])?\\s*("+DECIMAL+")\\s*(亿元|万元|美元|欧元|CNY|USD|EUR|元)(?:\\s*[/／]\\s*([^\\s,，;；。|｜\\t）)]+))?"+VALUE_END).matcher(text);
        if(!m.find()) return null;
        String unit=m.group(3),currency=currency(unit),prefix=Objects.toString(m.group(1),""),denominator=Objects.toString(m.group(4),"");
        if(!prefix.isBlank()&&!currency(prefix).equals(currency)||!denominator.isBlank()&&!denominator.matches(ITEM_UNITS))return null;
        BigDecimal multiplier=unit.equals("亿元")?new BigDecimal("100000000"):unit.equals("万元")?new BigDecimal("10000"):BigDecimal.ONE;
        BigDecimal raw=new BigDecimal(m.group(2).replace(",",""));
        Money result=new Money(raw.multiply(multiplier),currency,denominator,BigDecimal.ONE.movePointLeft(raw.scale()).multiply(multiplier).divide(new BigDecimal("2")));
        return m.find()?null:result;
    }
    private boolean compatibleRow(String text,Quantity quantity,Money price,Money subtotal) {
        if(!price.currency().equals(subtotal.currency())||!subtotal.denominator().isBlank())return false;
        if(!price.denominator().isBlank()&&!price.denominator().equals(quantity.unit()))return false;
        Set<String> objects=new HashSet<>();Matcher object=ROW_OBJECT.matcher(text);
        while(object.find())objects.add(object.group(1).trim());
        if(objects.size()>1)return false;
        Matcher declaredCurrency=ROW_CURRENCY.matcher(text);
        while(declaredCurrency.find())if(!currency(declaredCurrency.group(1).trim()).equals(price.currency()))return false;
        return true;
    }
    private boolean singleLabel(String text,String label) {
        Matcher matcher=Pattern.compile("(?<![\\p{L}0-9])"+label+"\\s*[:：=]").matcher(text);
        return matcher.find()&&!matcher.find();
    }
    private static String currency(String value) {
        return switch(value){case "元","万元","亿元","人民币","CNY","￥","¥"->"CNY";case "美元","USD"->"USD";case "欧元","EUR"->"EUR";default->"UNRESOLVED";};
    }
    private record Quantity(BigDecimal value,String unit) {}
    private record Money(BigDecimal value,String currency,String denominator,BigDecimal tolerance) {}

    private Evaluation blanks(Checkpoint cp,Document doc) {
        List<Evidence> found=new ArrayList<>(); List<Located> lines=lines(doc);
        for(int i=0;i<lines.size();i++) {
            Located line=lines.get(i); String text=line.text();
            if(BLANK.matcher(text).find()) found.add(line.evidence());
            else if(EMPTY_LABEL.matcher(text).find()) {
                String next=i+1<lines.size()?lines.get(i+1).text():"";
                // A value in the next paragraph/table cell is not an empty field.
                if(next.isBlank() || EMPTY_LABEL.matcher(next).find() || next.matches("^.{1,18}[:：=].*")) found.add(line.evidence());
            }
        }
        if(!found.isEmpty()) return result("WARNING","发现空字段或未替换的填写占位符；请确认是否属于本次投标的必填内容",found);
        return unknown(cp,"未发现规则能明确识别的漏填占位符；错填、表格空单元格和图片内容仍需复核",List.of());
    }

    private Evaluation signing(Checkpoint cp,Document doc,boolean afterOpening) {
        LocalDate opening=null;
        ClockValue openingClock=new ClockValue(null,false,false);
        if(afterOpening) {
            opening=expectedDate(cp);
            if(opening==null) return unknown(cp,"尚未确认开标时间，无法比较签署及落款日期",List.of());
            openingClock=clock(cp.expected().substring(10));
            if(openingClock.invalid()) return unknown(cp,"开标时间格式无效，请核对后比较签署及落款时间",List.of());
        }
        List<Evidence> invalid=new ArrayList<>(),late=new ArrayList<>(),read=new ArrayList<>();
        for(Located line:lines(doc)) {
            Matcher signing=SIGNING.matcher(line.text());
            while(signing.find()) {
                String value=signing.group(1); Matcher date=EngineText.DATE.matcher(value);
                if(!date.find()) {
                    if(value.matches(".*(?:[0-9]|[_＿]|待填|[Xx]{2,}|年\\s*月\\s*日).*")) invalid.add(line.evidence());
                    continue;
                }
                List<LocalDate> parsed=EngineText.dates(date.group());
                if(parsed.isEmpty()) { invalid.add(line.evidence()); continue; }
                ClockValue signingClock=clock(value.substring(date.end()));
                if(signingClock.invalid()) { invalid.add(line.evidence()); continue; }
                read.add(line.evidence());
                if(opening!=null && (parsed.get(0).isAfter(opening)
                        || parsed.get(0).equals(opening) && later(signingClock,openingClock))) late.add(line.evidence());
            }
        }
        if(!late.isEmpty()) return result("WARNING","发现签署或落款时间晚于开标时间 "+cp.expected()+"；请确认属于本次投标签署内容并修正",late);
        if(!invalid.isEmpty()) return result("WARNING","签署或落款日期存在无效日期、未填写值或无法解析的格式",invalid);
        return unknown(cp,afterOpening?"已按双方明确提供的日期及时间精度比较，未发现晚于开标时间；缺少具体时刻、未标注日期及图片落款仍需复核":"已识别签署日期和时间未发现格式异常；日期真实性、缺失日期及图片签署内容仍需复核",read);
    }

    private record ClockValue(LocalTime time,boolean seconds,boolean invalid) {}
    private ClockValue clock(String suffix) {
        Matcher matcher=CLOCK.matcher(suffix);
        if(!matcher.find()) return new ClockValue(null,false,suffix.matches("(?s)^\\s*T?\\s*\\d{1,2}\\s*[:：时].*"));
        try {
            return new ClockValue(LocalTime.of(Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2)),
                    matcher.group(3)==null?0:Integer.parseInt(matcher.group(3))),matcher.group(3)!=null,false);
        } catch(DateTimeException invalid) { return new ClockValue(null,false,true); }
    }
    private boolean later(ClockValue signing,ClockValue opening) {
        if(signing.time()==null || opening.time()==null) return false;
        // Do not invent seconds when either original only states hours and minutes.
        return signing.seconds() && opening.seconds()?signing.time().isAfter(opening.time())
                :signing.time().withSecond(0).isAfter(opening.time().withSecond(0));
    }

    private Evaluation typos(Checkpoint cp,Document doc) {
        List<Evidence> found=new ArrayList<>(); Set<String> corrections=new LinkedHashSet<>();
        for(Located line:lines(doc)) for(var pair:TYPOS.entrySet()) if(line.text().contains(pair.getKey())) {
            found.add(line.evidence()); corrections.add(pair.getKey()+" → "+pair.getValue());
        }
        if(!found.isEmpty()) return result("WARNING","发现疑似错别字："+String.join("；",corrections)+"。需结合原件确认是否为识别错误",found);
        return unknown(cp,"基础易错词扫描未发现命中；尚不能证明全文无错别字，专有名称与图像文字需人工校对",List.of());
    }

    private Evaluation certificates(Checkpoint cp,Document doc,List<Fact> facts) {
        LocalDate deadline=expectedDate(cp);
        if(deadline==null) return unknown(cp,"尚未确认效期核验基准日期，请补充投标截止或开标日期",List.of());
        List<Evidence> expired=new ArrayList<>(),unresolved=new ArrayList<>(),read=new ArrayList<>();
        FactScopePolicy scope=FactScopePolicy.of(doc);
        for(Fact fact:facts) if("certificateExpiry".equals(fact.field())) {
            switch(scope.date(fact,deadline,false).status()) {
                case "EXPIRED" -> expired.add(fact.evidence());
                case "VALID" -> read.add(fact.evidence());
                case "UNRESOLVED" -> unresolved.add(fact.evidence());
                default -> { /* Historical evidence is not a current credential expiry. */ }
            }
        }
        if(!expired.isEmpty()) return result("FAIL","发现证件在核验日期 "+deadline+" 已过期或尚未生效",expired);
        if(!unresolved.isEmpty()) return unknown(cp,"存在不能确定的证件效期或无效日期，无法确认全部证件有效",unresolved);
        return unknown(cp,"已提取的证件日期未发现失效；证件清单、主体对应、缺少有效期的证照及图片尚未逐一核全，不能确认全部有效",read);
    }
    private LocalDate expectedDate(Checkpoint cp) {
        String value=cp.expected();
        if(value==null || value.length()<10) return null;
        try { return LocalDate.parse(value.substring(0,10)); } catch(DateTimeException invalid) { return null; }
    }
    private List<Located> lines(Document doc) {
        List<Located> lines=new ArrayList<>();
        for(Page page:doc.pages()==null?List.<Page>of():doc.pages()) {
            String[] rows=(page.text()==null?"":page.text()).split("\\R",-1);
            for(int i=0;i<rows.length;i++) if(!rows[i].isBlank()) lines.add(new Located(rows[i].trim(),EngineText.evidence(doc,page,i+1,rows[i])));
        }
        return lines;
    }
    private record Located(String text,Evidence evidence) {}
    private Evaluation unknown(Checkpoint cp,String reason,List<Evidence> evidence) {
        return result("UNDETERMINED",reason,evidence.isEmpty() && cp.source()!=null?List.of(cp.source()):evidence);
    }
    private Evaluation result(String status,String reason,List<Evidence> evidence) {
        return new Evaluation(status,reason,"请定位原文与图片，人工复核后记录结论。",evidence.stream().filter(Objects::nonNull).distinct().toList(),"FAIL".equals(status)?1:0,"DETERMINISTIC/FULL_TEXT_REVIEW");
    }
}
