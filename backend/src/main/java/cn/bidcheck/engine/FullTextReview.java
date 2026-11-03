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
    private static final Map<String,String> TYPOS=cn.bidcheck.compat.Java8Api.mapOf("投标文仵", "投标文件", "招标文仵", "招标文件", "授杈委托书", "授权委托书", "质保其", "质保期", "营叶执照", "营业执照");
    private static final String ITEM_UNITS="平方米|公斤|千克|用户|账号|许可|台|套|个|次|人|名|份|件|项|座|处|站|米|吨|箱|m²|m2|kg|m|t";
    private static final String DECIMAL="[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?";
    private static final String VALUE_END="(?=$|[\\s,，;；。|｜\\t）)])";
    private static final Pattern QUANTITY=Pattern.compile("(?<![\\p{L}0-9])数量\\s*[:：=]\\s*("+DECIMAL+")\\s*("+ITEM_UNITS+")?"+VALUE_END);
    private static final Pattern ROW_OBJECT=Pattern.compile("(?<![\\p{L}0-9])(?:品目名称|品目|设备名称|产品名称|商品名称|项目名称|标的名称|清单项)\\s*[:：=]\\s*([^;；。|｜\\t,，]{1,200})");
    private static final Pattern ROW_CURRENCY=Pattern.compile("(?:币种|货币|币别)\\s*[:：=]\\s*([^;；。|｜\\t,，]{1,40})");

    Evaluation evaluate(Checkpoint cp,Document doc,List<Fact> facts) {
        if(!"REVIEW".equals(cp.operator())) return unknown(cp,"全文检查运算符应为 REVIEW",cn.bidcheck.compat.Java8Api.listOf());
        switch (cp.field()) {case "fullTextConsistency":return consistency(cp,doc,facts);case "amountAccuracy":return amounts(cp,doc,facts);case "missingFields":return blanks(cp,doc);case "signatureDate":return signing(cp,doc,false);case "signingAfterOpening":return signing(cp,doc,true);case "typos":return typos(cp,doc);case "certificateCoverage":return certificates(cp,doc,facts);default:return unknown(cp,"不支持的全文检查字段",cn.bidcheck.compat.Java8Api.listOf());}
    }

    private Evaluation consistency(Checkpoint cp,Document doc,List<Fact> facts) {
        java.util.Map<java.lang.String, java.lang.Object> report=new cn.bidcheck.consistency.ConsistencyEngine().scan(doc,cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> groups=cn.bidcheck.consistency.ConsistencyRunSupport.maps(report.get("groups"));
        if(groups.isEmpty()) return unknown(cp,"已按全文一致性目录扫描可读文本，未发现可比较字段的差异；未识别到事实不代表全部信息均已核实",cn.bidcheck.compat.Java8Api.listOf());
        List<Evidence> evidences=cn.bidcheck.compat.Java8Api.toList(groups.stream().flatMap(g->((List<?>)g.get("evidences")).stream()).map(Evidence.class::cast).distinct());
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
        java.util.Map<java.lang.String, java.lang.Object> report=new cn.bidcheck.consistency.ConsistencyEngine().scan(doc,cn.bidcheck.compat.Java8Api.listOf());
        for(java.util.Map<java.lang.String, java.lang.Object> group:cn.bidcheck.consistency.ConsistencyRunSupport.maps(report.get("groups")))
            if(cn.bidcheck.compat.Java8Api.setOf("CONFLICT", "SUSPECTED").contains(Objects.toString(group.get("status"),""))
                    &&("totalPrice".equals(group.get("field"))||"ARITHMETIC".equals(group.get("relationType"))))
                for(Object evidence:(List<?>)group.get("evidences"))issues.add((Evidence)evidence);
        if(!issues.isEmpty()) return result("WARNING","发现数量乘单价与金额不符，或相同税额口径下的总报价出现不同值；需核对报价范围和计算规则",issues);
        return unknown(cp,"未发现明确的逐行乘算或同口径总价异常；金额大写、复杂汇总、分项报价与图片金额仍需复核",cn.bidcheck.compat.Java8Api.listOf());
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
        if(!cn.bidcheck.compat.Java8Api.isBlank(prefix)&&!currency(prefix).equals(currency)||!cn.bidcheck.compat.Java8Api.isBlank(denominator)&&!denominator.matches(ITEM_UNITS))return null;
        BigDecimal multiplier=unit.equals("亿元")?new BigDecimal("100000000"):unit.equals("万元")?new BigDecimal("10000"):BigDecimal.ONE;
        BigDecimal raw=new BigDecimal(m.group(2).replace(",",""));
        Money result=new Money(raw.multiply(multiplier),currency,denominator,BigDecimal.ONE.movePointLeft(raw.scale()).multiply(multiplier).divide(new BigDecimal("2")));
        return m.find()?null:result;
    }
    private boolean compatibleRow(String text,Quantity quantity,Money price,Money subtotal) {
        if(!price.currency().equals(subtotal.currency())||!cn.bidcheck.compat.Java8Api.isBlank(subtotal.denominator()))return false;
        if(!cn.bidcheck.compat.Java8Api.isBlank(price.denominator())&&!price.denominator().equals(quantity.unit()))return false;
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
        switch (value) {case "元":case "万元":case "亿元":case "人民币":case "CNY":case "￥":case "¥":return "CNY";case "美元":case "USD":return "USD";case "欧元":case "EUR":return "EUR";default:return "UNRESOLVED";}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Quantity {
        private final BigDecimal value;
        private final String unit;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Quantity(@com.fasterxml.jackson.annotation.JsonProperty("value") BigDecimal value, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit) { this.value = value; this.unit = unit; }
        public BigDecimal value() { return value; }
        public String unit() { return unit; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Quantity that = (Quantity) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.unit, that.unit); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.unit); return hash; }
        @Override public String toString() { return "Quantity[" + "value=" + value + ", unit=" + unit + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Money {
        private final BigDecimal value;
        private final String currency;
        private final String denominator;
        private final BigDecimal tolerance;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Money(@com.fasterxml.jackson.annotation.JsonProperty("value") BigDecimal value, @com.fasterxml.jackson.annotation.JsonProperty("currency") String currency, @com.fasterxml.jackson.annotation.JsonProperty("denominator") String denominator, @com.fasterxml.jackson.annotation.JsonProperty("tolerance") BigDecimal tolerance) { this.value = value; this.currency = currency; this.denominator = denominator; this.tolerance = tolerance; }
        public BigDecimal value() { return value; }
        public String currency() { return currency; }
        public String denominator() { return denominator; }
        public BigDecimal tolerance() { return tolerance; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Money that = (Money) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.currency, that.currency) && java.util.Objects.equals(this.denominator, that.denominator) && java.util.Objects.equals(this.tolerance, that.tolerance); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.currency); hash = 31 * hash + java.util.Objects.hashCode(this.denominator); hash = 31 * hash + java.util.Objects.hashCode(this.tolerance); return hash; }
        @Override public String toString() { return "Money[" + "value=" + value + ", currency=" + currency + ", denominator=" + denominator + ", tolerance=" + tolerance + "]"; }
}

    private Evaluation blanks(Checkpoint cp,Document doc) {
        List<Evidence> found=new ArrayList<>(); List<Located> lines=lines(doc);
        for(int i=0;i<lines.size();i++) {
            Located line=lines.get(i); String text=line.text();
            if(BLANK.matcher(text).find()) found.add(line.evidence());
            else if(EMPTY_LABEL.matcher(text).find()) {
                String next=i+1<lines.size()?lines.get(i+1).text():"";
                // A value in the next paragraph/table cell is not an empty field.
                if(cn.bidcheck.compat.Java8Api.isBlank(next) || EMPTY_LABEL.matcher(next).find() || next.matches("^.{1,18}[:：=].*")) found.add(line.evidence());
            }
        }
        if(!found.isEmpty()) return result("WARNING","发现空字段或未替换的填写占位符；请确认是否属于本次投标的必填内容",found);
        return unknown(cp,"未发现规则能明确识别的漏填占位符；错填、表格空单元格和图片内容仍需复核",cn.bidcheck.compat.Java8Api.listOf());
    }

    private Evaluation signing(Checkpoint cp,Document doc,boolean afterOpening) {
        LocalDate opening=null;
        ClockValue openingClock=new ClockValue(null,false,false);
        if(afterOpening) {
            opening=expectedDate(cp);
            if(opening==null) return unknown(cp,"尚未确认开标时间，无法比较签署及落款日期",cn.bidcheck.compat.Java8Api.listOf());
            openingClock=clock(cp.expected().substring(10));
            if(openingClock.invalid()) return unknown(cp,"开标时间格式无效，请核对后比较签署及落款时间",cn.bidcheck.compat.Java8Api.listOf());
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

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class ClockValue {
        private final LocalTime time;
        private final boolean seconds;
        private final boolean invalid;
        @com.fasterxml.jackson.annotation.JsonCreator
        private ClockValue(@com.fasterxml.jackson.annotation.JsonProperty("time") LocalTime time, @com.fasterxml.jackson.annotation.JsonProperty("seconds") boolean seconds, @com.fasterxml.jackson.annotation.JsonProperty("invalid") boolean invalid) { this.time = time; this.seconds = seconds; this.invalid = invalid; }
        public LocalTime time() { return time; }
        public boolean seconds() { return seconds; }
        public boolean invalid() { return invalid; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ClockValue that = (ClockValue) other; return java.util.Objects.equals(this.time, that.time) && java.util.Objects.equals(this.seconds, that.seconds) && java.util.Objects.equals(this.invalid, that.invalid); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.time); hash = 31 * hash + java.util.Objects.hashCode(this.seconds); hash = 31 * hash + java.util.Objects.hashCode(this.invalid); return hash; }
        @Override public String toString() { return "ClockValue[" + "time=" + time + ", seconds=" + seconds + ", invalid=" + invalid + "]"; }
}
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
        for(Located line:lines(doc)) for(java.util.Map.Entry<java.lang.String, java.lang.String> pair:TYPOS.entrySet()) if(line.text().contains(pair.getKey())) {
            found.add(line.evidence()); corrections.add(pair.getKey()+" → "+pair.getValue());
        }
        if(!found.isEmpty()) return result("WARNING","发现疑似错别字："+String.join("；",corrections)+"。需结合原件确认是否为识别错误",found);
        return unknown(cp,"基础易错词扫描未发现命中；尚不能证明全文无错别字，专有名称与图像文字需人工校对",cn.bidcheck.compat.Java8Api.listOf());
    }

    private Evaluation certificates(Checkpoint cp,Document doc,List<Fact> facts) {
        LocalDate deadline=expectedDate(cp);
        if(deadline==null) return unknown(cp,"尚未确认效期核验基准日期，请补充投标截止或开标日期",cn.bidcheck.compat.Java8Api.listOf());
        List<Evidence> expired=new ArrayList<>(),unresolved=new ArrayList<>(),read=new ArrayList<>();
        FactScopePolicy scope=FactScopePolicy.of(doc);
        for(Fact fact:facts) if("certificateExpiry".equals(fact.field())) {
            switch (scope.date(fact,deadline,false).status()) {case "EXPIRED":expired.add(fact.evidence()); break;case "VALID":read.add(fact.evidence()); break;case "UNRESOLVED":unresolved.add(fact.evidence()); break;default:{ /* Historical evidence is not a current credential expiry. */ } break;}
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
        for(Page page:doc.pages()==null?cn.bidcheck.compat.Java8Api.<Page>listOf():doc.pages()) {
            String[] rows=(page.text()==null?"":page.text()).split("\\R",-1);
            for(int i=0;i<rows.length;i++) if(!cn.bidcheck.compat.Java8Api.isBlank(rows[i])) lines.add(new Located(rows[i].trim(),EngineText.evidence(doc,page,i+1,rows[i])));
        }
        return lines;
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Located {
        private final String text;
        private final Evidence evidence;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Located(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence) { this.text = text; this.evidence = evidence; }
        public String text() { return text; }
        public Evidence evidence() { return evidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Located that = (Located) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.evidence, that.evidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); return hash; }
        @Override public String toString() { return "Located[" + "text=" + text + ", evidence=" + evidence + "]"; }
}
    private Evaluation unknown(Checkpoint cp,String reason,List<Evidence> evidence) {
        return result("UNDETERMINED",reason,evidence.isEmpty() && cp.source()!=null?cn.bidcheck.compat.Java8Api.listOf(cp.source()):evidence);
    }
    private Evaluation result(String status,String reason,List<Evidence> evidence) {
        return new Evaluation(status,reason,"请定位原文与图片，人工复核后记录结论。",cn.bidcheck.compat.Java8Api.toList(evidence.stream().filter(Objects::nonNull).distinct()),"FAIL".equals(status)?1:0,"DETERMINISTIC/FULL_TEXT_REVIEW");
    }
}
