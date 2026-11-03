package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Typed fields from one certificate or one query-table row. No adjacent-row or query-input inheritance. */
final class PersonnelCertificateRecords {
    private static final String DATE="(?:19|20)[0-9]{2}[年./-][0-9]{1,2}[月./-][0-9]{1,2}日?";
    private static final Pattern PERIOD=Pattern.compile("(?<![\\p{IsHan}])(使用有效期|使用期限|注册有效期|证书有效期|有效期限|有效期至|有效期)\\s*[:：]?\\s*(?:至\\s*)?("+DATE+")(?:\\s*[-至—~～]\\s*("+DATE+"))?");
    private static final Pattern PROFESSIONAL_PERIOD=Pattern.compile("^[\\t ]*(?:注册专业[：:][\\t ]*)?([\\p{IsHan}]{2,20})[（(]有效期[：:]\\s*("+DATE+")\\s*[-至—~～]\\s*("+DATE+")[）)][\\t ]*$");
    private static final Pattern SAFETY_NUMBER=Pattern.compile("[\\p{IsHan}]{1,8}安([AB]|C[123]?)\\s*[（(][0-9]{4}[）)][0-9]{4,}");
    private static final Pattern LABEL=Pattern.compile("^[\\t ]*(?:#{1,6}[\\t ]*)?(姓名|姓\\s+名|持证人|证件号码|身份证号|出生日期|出生年月|企业名称|聘用企业|聘用单位|注册单位|电子证书编号|证书编号|编号|注册编号|注册证号|执业印章号|性别|性\\s+别|注册专业)\\s*[:：]\\s*(.+?)\\s*$");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cell{
        private final String raw;
        private final int start;
        private final int end;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cell(@com.fasterxml.jackson.annotation.JsonProperty("raw") String raw, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end) { this.raw = raw; this.start = start; this.end = end; }
        public String raw() { return raw; }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.raw, that.raw) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.raw); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); return hash; }
        @Override public String toString() { return "Cell[" + "raw=" + raw + ", start=" + start + ", end=" + end + "]"; }
String text(){String value=cn.bidcheck.compat.Java8Api.strip(raw);if(value.startsWith("**")&&value.endsWith("**")&&value.length()>4)value=value.substring(2,value.length()-2);if(value.startsWith("`")&&value.endsWith("`")&&value.length()>2)value=value.substring(1,value.length()-1);return clean(value);}}
    private PersonnelCertificateRecords(){}

    static List<CertificateSourceComparison.Record> read(List<Source> sources,String original){
        if(original.matches("(?s).*(?:填写示例|示范文本|证书模板|未取得|拟取得).*"))return cn.bidcheck.compat.Java8Api.listOf();
        boolean query=original.matches("(?s).*(?:查询截图|查询结果|执业注册信息|证书查询).*" );
        if(query)return queryRows(sources,original);
        if(!clean(original).matches(".*(?:注册证书|安全生产考核合格证书?).*"))return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,CertificateSourceComparison.Field> fields=new LinkedHashMap<>();List<String> issues=new ArrayList<>();int offset=0;
        boolean professionSection=false;Set<String> professionalFields=new LinkedHashSet<>();
        for(String line:original.split("\n",-1)){
            Matcher m=LABEL.matcher(line);if(m.matches()){
                String label=clean(m.group(1)),value=m.group(2),key;
switch (label) {case "姓名":case "持证人":key = "NAME"; break;case "证件号码":case "身份证号":key = "PERSON_ID"; break;case "出生日期":case "出生年月":key = "BIRTH_DATE"; break;case "企业名称":case "聘用企业":case "聘用单位":case "注册单位":key = "EMPLOYER"; break;case "电子证书编号":case "证书编号":case "编号":case "注册证号":key = "CERTIFICATE_NUMBER"; break;case "注册编号":key = "REGISTRATION_NUMBER"; break;case "执业印章号":key = "PRACTICE_SEAL_NUMBER"; break;case "性别":key = "GENDER"; break;case "注册专业":key = "PROFESSION"; break;default:key = ""; break;}
                // A property shown in a portrait description is not a credential field. Only full,
                // unadorned label lines are accepted; HTML, bullets and inferred ages are ignored.
                if(key.equals("PROFESSION")){int end=value.indexOf('（');if(end<0)end=value.indexOf('(');if(end>=0)value=value.substring(0,end);}
                if(!cn.bidcheck.compat.Java8Api.isBlank(key)&&!cn.bidcheck.compat.Java8Api.isBlank(value)&&!(key.equals("PROFESSION")&&PROFESSIONAL_PERIOD.matcher(line).matches()))add(fields,issues,field(key,label,new Cell(value,offset+m.start(2),offset+m.start(2)+value.length()),sources,original));
            }
            Matcher issue=Pattern.compile("^[\\t ]*(发证日期|签发日期|颁发日期|核发日期|批准日期|生效日期)\\s*[:：]\\s*("+DATE+")[\\t ]*$").matcher(line);
            if(issue.matches())add(fields,issues,field("ISSUE_DATE",issue.group(1),new Cell(issue.group(2),offset+issue.start(2),offset+issue.end(2)),sources,original));
            Matcher professional=PROFESSIONAL_PERIOD.matcher(line);
            if(professional.matches()&&(professionSection||clean(line).startsWith("注册专业"))){professionSection=true;String profession=professional.group(1);professionalFields.add(profession);
                add(fields,issues,field("PROFESSION:"+profession,"注册专业",new Cell(profession,offset+professional.start(1),offset+professional.end(1)),sources,original));
                for(int group=2;group<=3;group++)add(fields,issues,field((group==2?"VALIDITY_START:":"VALIDITY_END:")+profession,"注册专业有效期",new Cell(professional.group(group),offset+professional.start(group),offset+professional.end(group)),sources,original));
            }else{if(!cn.bidcheck.compat.Java8Api.isBlank(line))professionSection=false;Matcher period=PERIOD.matcher(line);while(period.find()){
                String prefix=cn.bidcheck.compat.Java8Api.setOf("使用有效期", "使用期限").contains(period.group(1))?"VALIDITY_USE_":"VALIDITY_";
                if(period.group(3)!=null)add(fields,issues,field(prefix+"START",period.group(1),new Cell(period.group(2),offset+period.start(2),offset+period.end(2)),sources,original));
                int group=period.group(3)==null?2:3;add(fields,issues,field(prefix+"END",period.group(1),new Cell(period.group(group),offset+period.start(group),offset+period.end(group)),sources,original));
            }
            }
            offset+=line.length()+1;
        }
        String number=value(fields,"CERTIFICATE_NUMBER"),category=category(clean(original),number);
        if(cn.bidcheck.compat.Java8Api.isBlank(category)||!fields.containsKey("NAME"))return cn.bidcheck.compat.Java8Api.listOf();
        if(!professionalFields.isEmpty()){
            fields.remove("PROFESSION");
            if(professionalFields.size()==1){String profession=professionalFields.iterator().next();
                for(String key:cn.bidcheck.compat.Java8Api.listOf("PROFESSION", "VALIDITY_START", "VALIDITY_END")){cn.bidcheck.verification.engine.CertificateSourceComparison.Field f=fields.get(key+":"+profession);if(f!=null)add(fields,issues,new CertificateSourceComparison.Field(key,f.label(),f.raw(),f.value(),f.sourceId(),f.evidence()));}
            }
        }
        // The architect register labels its credential number 注册编号; builder registration and
        // practice-seal numbers remain independent typed fields.
        if(category.contains("建筑师")&&!fields.containsKey("CERTIFICATE_NUMBER")&&fields.containsKey("REGISTRATION_NUMBER")){
            cn.bidcheck.verification.engine.CertificateSourceComparison.Field f=fields.remove("REGISTRATION_NUMBER");fields.put("CERTIFICATE_NUMBER",new CertificateSourceComparison.Field("CERTIFICATE_NUMBER",f.label(),f.raw(),f.value(),f.sourceId(),f.evidence()));
        }
        return cn.bidcheck.compat.Java8Api.listOf(record("CERTIFICATE",category,fields,sources,issues));
    }

    private static List<CertificateSourceComparison.Record> queryRows(List<Source> sources,String original){
        List<CertificateSourceComparison.Record> records=new ArrayList<>();List<Cell> headers=cn.bidcheck.compat.Java8Api.listOf();int offset=0;
        for(String line:original.split("\n",-1)){
            List<Cell> row=cells(line,offset);offset+=line.length()+1;
            if(row.isEmpty()){headers=cn.bidcheck.compat.Java8Api.listOf();continue;}
            if(column(row,"姓名")>=0&&column(row,"人员类型","证书类别","证书名称")>=0&&(column(row,"证书编号","电子证书编号")>=0||column(row,"注册号","注册编号")>=0)){headers=row;continue;}
            if(headers.isEmpty()||row.size()!=headers.size()||row.stream().allMatch(c->c.text().matches("[-:—]+")))continue;
            int name=column(headers,"姓名"),type=column(headers,"人员类型","证书类别","证书名称"),number=column(headers,"证书编号","电子证书编号");
            String category=category(row.get(type).text(),number<0?"":row.get(number).text());if(cn.bidcheck.compat.Java8Api.isBlank(category))continue;
            Map<String,CertificateSourceComparison.Field> fields=new LinkedHashMap<>();List<String> issues=new ArrayList<>();
            for(int i=0;i<headers.size();i++){
                String label=headers.get(i).text(),key;
switch (label) {case "姓名":key = "NAME"; break;case "企业名称":case "单位名称":case "聘用单位":case "注册单位":key = "EMPLOYER"; break;case "证书编号":case "电子证书编号":key = "CERTIFICATE_NUMBER"; break;case "注册号":case "注册编号":key = "REGISTRATION_NUMBER"; break;case "执业印章号":key = "PRACTICE_SEAL_NUMBER"; break;case "有效期结束日期":case "有效期至":case "有效期截止日期":key = "VALIDITY_END"; break;case "有效期开始日期":key = "VALIDITY_START"; break;case "注册专业":key = "PROFESSION"; break;case "身份证号":case "证件号码":key = "PERSON_ID"; break;default:key = ""; break;}
                if(!cn.bidcheck.compat.Java8Api.isBlank(key))add(fields,issues,field(key,label,row.get(i),sources,original));
            }
            if(fields.containsKey("NAME")&&fields.get("NAME").value().equals(row.get(name).text()))records.add(record("QUERY",category,fields,sources,issues));
        }return records;
    }

    static String category(String text,String number){
        String normalized=clean(text).replace('壹','一').replace('贰','二');
        Matcher safety=SAFETY_NUMBER.matcher(normalNumber(number));if(safety.matches()&&normalized.matches(".*(?:安全生产|企业主要负责人|项目负责人|专职安全生产管理人员).*"))return "安全生产考核合格证书("+safety.group(1)+")";
        Set<String> found=new LinkedHashSet<>();Matcher m=Pattern.compile("([一二]级)(?:注册)?(建筑师|建造师)").matcher(normalized);while(m.find())found.add(m.group(1)+"注册"+m.group(2));
        if(normalized.contains("注册监理工程师"))found.add("注册监理工程师");if(normalized.contains("注册造价工程师"))found.add("注册造价工程师");
        return found.size()==1?found.iterator().next():"";
    }
    static String normalNumber(String text){return clean(text).replace('（','(').replace('）',')').toUpperCase(Locale.ROOT);}
    private static String value(Map<String,CertificateSourceComparison.Field> fields,String key){cn.bidcheck.verification.engine.CertificateSourceComparison.Field f=fields.get(key);return f==null?"":f.value();}
    private static CertificateSourceComparison.Record record(String side,String category,Map<String,CertificateSourceComparison.Field> fields,List<Source> sources,List<String> issues){String owner=value(fields,"NAME");return new CertificateSourceComparison.Record("PR"+identity(side+category+owner+cn.bidcheck.compat.Java8Api.toList(fields.values().stream().map(f->f.sourceId()+f.raw()))).substring(0,20),side,category,owner,cn.bidcheck.compat.Java8Api.listCopyOf(sources),cn.bidcheck.compat.Java8Api.mapCopyOf(fields),cn.bidcheck.compat.Java8Api.listCopyOf(issues));}
    private static void add(Map<String,CertificateSourceComparison.Field> fields,List<String> issues,CertificateSourceComparison.Field f){if(f==null)return;cn.bidcheck.verification.engine.CertificateSourceComparison.Field old=fields.putIfAbsent(f.key(),f);if(old!=null&&!old.value().equals(f.value()))issues.add("MULTIPLE_VALUES:"+f.key());}
    private static CertificateSourceComparison.Field field(String key,String label,Cell cell,List<Source> sources,String original){
        String value=cell.text();if(cn.bidcheck.compat.Java8Api.isBlank(value)||value.matches("[-—_/]+"))return null;
        // Preserve a masked holder as its literal source value; only an explicit confirmed
        // identity edge can connect that record to a fully named current person.
        if(key.equals("NAME")&&!value.matches("[\\p{IsHan}·*＊×●]{2,12}"))return null;
        if(key.equals("PERSON_ID")&&!value.matches("[0-9]{17}[0-9Xx]"))return null;
        if(key.contains("NUMBER")){value=normalNumber(value);if(!value.matches("[\\p{IsHan}A-Z0-9()/-]{4,80}"))return null;}
        if(key.startsWith("VALIDITY_")||key.equals("BIRTH_DATE")||key.equals("ISSUE_DATE")){Matcher date=Pattern.compile("([0-9]{4})[年./-]([0-9]{1,2})[月./-]([0-9]{1,2})日?").matcher(value);if(!date.matches())return null;try{value=LocalDate.of(Integer.parseInt(date.group(1)),Integer.parseInt(date.group(2)),Integer.parseInt(date.group(3))).toString();}catch(Exception ignored){return null;}}
        for(Source source:sources){Matcher range=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(source.evidence().anchor());if(!range.matches()||cell.start()<Integer.parseInt(range.group(1))||cell.end()>Integer.parseInt(range.group(2)))continue;Evidence e=source.evidence();String raw=original.substring(cell.start(),cell.end());return new CertificateSourceComparison.Field(key,label,raw,value,source.id(),new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),raw,"chars:"+cell.start()+"-"+cell.end(),e.confidence()));}return null;
    }
    private static int column(List<Cell> row,String... labels){Set<String> allowed=cn.bidcheck.compat.Java8Api.setOf(labels);int found=-1;for(int i=0;i<row.size();i++)if(allowed.contains(row.get(i).text())){if(found>=0)return -1;found=i;}return found;}
    private static List<Cell> cells(String line,int offset){if(!cn.bidcheck.compat.Java8Api.strip(line).startsWith("|"))return cn.bidcheck.compat.Java8Api.listOf();List<Cell> result=new ArrayList<>();int start=line.indexOf('|')+1,end;while((end=line.indexOf('|',start))>=0){result.add(new Cell(line.substring(start,end),offset+start,offset+end));start=end+1;}return result;}
}
