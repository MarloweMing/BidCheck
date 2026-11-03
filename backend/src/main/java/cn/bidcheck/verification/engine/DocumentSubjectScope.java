package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Server-derived document scope. A pronoun in a copied document never establishes the current project. */
final class DocumentSubjectScope {
    private static final Pattern LABEL=Pattern.compile("(?:项目名称|工程名称)\\s*[:：]\\s*([^\\n|;；。]{6,200})");
    private static final Pattern LETTER=Pattern.compile("(?:关于贵公司|参加贵公司|参加贵单位)\\s*([^\\n。；]{8,220}?)的(?:招标|采购)文件");
    private static final Pattern HISTORY=Pattern.compile("(?m)^[\\t ]*(?:[一二三四五六七八九十0-9.、（）()\\s]*)(?:主要工作经历|工作经历|工作履历|参加过的类似项目|历史项目|已完成项目|类似项目业绩|合同协议书)");
    private static final Pattern NAME=Pattern.compile("姓\\s*名\\s*[:：]?\\s*([\\p{IsHan}·]{2,12})(?=\\s|[|<]|$)");
    private static final Pattern CURRENT=Pattern.compile("本项目|本次投标|本次招标|拟派|拟投入|本工程|联合体协议书");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Context {
        private final String status;
        private final String projectName;
        private final String anchor;
        private final List<String> appointmentBlocks;
        public String status() { return status; }
        public String projectName() { return projectName; }
        public String anchor() { return anchor; }
        public List<String> appointmentBlocks() { return appointmentBlocks; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Context that = (Context) other; return java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.projectName, that.projectName) && java.util.Objects.equals(this.anchor, that.anchor) && java.util.Objects.equals(this.appointmentBlocks, that.appointmentBlocks); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.projectName); hash = 31 * hash + java.util.Objects.hashCode(this.anchor); hash = 31 * hash + java.util.Objects.hashCode(this.appointmentBlocks); return hash; }
        @Override public String toString() { return "Context[" + "status=" + status + ", projectName=" + projectName + ", anchor=" + anchor + ", appointmentBlocks=" + appointmentBlocks + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        Context(@com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("projectName") String projectName, @com.fasterxml.jackson.annotation.JsonProperty("anchor") String anchor, @com.fasterxml.jackson.annotation.JsonProperty("appointmentBlocks") List<String> appointmentBlocks) { appointmentBlocks=cn.bidcheck.compat.Java8Api.listCopyOf(appointmentBlocks);
            this.status = status; this.projectName = projectName; this.anchor = anchor; this.appointmentBlocks = appointmentBlocks;
        }
        static Context unspecified(){return new Context("UNSPECIFIED","","",cn.bidcheck.compat.Java8Api.listOf());}
        Map<String,Object> audit(){return linked("status",status,"projectName",projectName,"anchor",anchor,"appointmentBlockCount",appointmentBlocks.size(),"derivedBy","SERVER_DOCUMENT_SCOPE");}
        boolean blocked(){return cn.bidcheck.compat.Java8Api.setOf("OTHER_PROJECT", "HISTORICAL_DOCUMENT", "UNRESOLVED_DOCUMENT").contains(status);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Range {
        private final int start;
        private final int end;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Range(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end) { this.start = start; this.end = end; }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Range that = (Range) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); return hash; }
        @Override public String toString() { return "Range[" + "start=" + start + ", end=" + end + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class PageScope {
        private final String status;
        private final String project;
        private final String anchor;
        private final List<Range> ranges;
        @com.fasterxml.jackson.annotation.JsonCreator
        private PageScope(@com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("project") String project, @com.fasterxml.jackson.annotation.JsonProperty("anchor") String anchor, @com.fasterxml.jackson.annotation.JsonProperty("ranges") List<Range> ranges) { this.status = status; this.project = project; this.anchor = anchor; this.ranges = ranges; }
        public String status() { return status; }
        public String project() { return project; }
        public String anchor() { return anchor; }
        public List<Range> ranges() { return ranges; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PageScope that = (PageScope) other; return java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.project, that.project) && java.util.Objects.equals(this.anchor, that.anchor) && java.util.Objects.equals(this.ranges, that.ranges); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.project); hash = 31 * hash + java.util.Objects.hashCode(this.anchor); hash = 31 * hash + java.util.Objects.hashCode(this.ranges); return hash; }
        @Override public String toString() { return "PageScope[" + "status=" + status + ", project=" + project + ", anchor=" + anchor + ", ranges=" + ranges + "]"; }
}
    private final Map<String,PageScope> pages=new HashMap<>();

    DocumentSubjectScope(Document document) {
        String current=coverProject(document),state="",anchor="",named="";int age=0,previousPage=-1;Map<Integer,Set<String>> appointments=appointmentNavigation(document);
        for(Page page:document.pages()){
            String text=Objects.toString(page.text(),"");String prefix=beforeHistory(text);
            String explicit=outerProject(prefix);boolean foreign=!cn.bidcheck.compat.Java8Api.isBlank(explicit)&&!cn.bidcheck.compat.Java8Api.isBlank(current)&&!sameProject(current,explicit);
            // Only a named current project can reset a foreign subdocument. Generic 本项目 or a form heading cannot.
            if(!cn.bidcheck.compat.Java8Api.isBlank(explicit)){
                state=foreign?"OTHER_PROJECT":cn.bidcheck.compat.Java8Api.isBlank(current)?"UNRESOLVED_DOCUMENT":"CURRENT_PROJECT";
                named=explicit;anchor=page.locationType()+":"+page.number()+":outer-project";age=0;
            }else if(!cn.bidcheck.compat.Java8Api.isBlank(current)&&currentForm(page,text,appointments)){
                state="CURRENT_BID_SECTION";named=current;anchor=page.locationType()+":"+page.number()+":cover-navigation-section-form-printed-page";age=0;
            }else if(historyHeading(text)){
                state="HISTORICAL_DOCUMENT";named="";anchor=page.locationType()+":"+page.number()+":history-heading";age=0;
            }else if(state.equals("CURRENT_BID_SECTION")&&page.number()==previousPage+1&&proofAttachmentStart(page,text)){
                // The numbered, navigation-backed current appointment owns its explicit proof
                // attachment. A copied history form or the words 本项目 alone cannot open this scope.
                state="CURRENT_BID_ATTACHMENT";anchor=anchor+":proof-attachment:"+page.number();age=0;
            }else if(state.equals("CURRENT_BID_ATTACHMENT")&&(page.number()!=previousPage+1||!physicalPageMarker(page,text))){
                // Do not carry an attachment through an unlocated or reordered page.
                state="UNRESOLVED_DOCUMENT";age=0;
            }else if(cn.bidcheck.compat.Java8Api.setOf("OTHER_PROJECT", "HISTORICAL_DOCUMENT", "UNRESOLVED_DOCUMENT").contains(state)){
                // Retain the fact that scope has changed, but stop asserting a particular project after two pages.
                // A stale context is unresolved, not silently restored to the outer bid document.
                if(++age>2)state="UNRESOLVED_DOCUMENT";
            }else if(cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION").contains(state)){
                state="CURRENT_DOCUMENT";
            }
            String status=cn.bidcheck.compat.Java8Api.isBlank(state)?(cn.bidcheck.compat.Java8Api.isBlank(current)?"UNSPECIFIED":"CURRENT_DOCUMENT"):state;
            pages.put(key(page),new PageScope(status,cn.bidcheck.compat.Java8Api.isBlank(named)?current:named,anchor,appointmentRanges(text)));
            previousPage=page.number();
        }
    }
    Context context(Page page,int start,int end){
        PageScope scope=pages.get(key(page));if(scope==null)return Context.unspecified();String text=Objects.toString(page.text(),"");List<String> blocks=new ArrayList<>();
        for(Range range:scope.ranges()){int a=Math.max(start,range.start()),b=Math.min(end,range.end());if(a<b)blocks.add(text.substring(a,b));}
        return new Context(scope.status(),scope.project(),scope.anchor(),blocks);
    }
    private static String key(Page p){return p.locationType()+":"+p.number();}
    private static boolean proofAttachmentStart(Page page,String text){
        return physicalPageMarker(page,text)&&cn.bidcheck.compat.Java8Api.stripLeading(text).matches("(?s)^[（(]2[）)]证明文件扫描件(?:\\s|$).*");
    }
    private static boolean physicalPageMarker(Page page,String text){
        if(!"PAGE".equals(page.locationType()))return false;
        List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(text).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));
        String number=Integer.toString(page.number());
        return !lines.isEmpty()&&(lines.get(lines.size()-1).equals(number)||lines.stream().limit(3).anyMatch(number::equals));
    }
    private static Map<Integer,Set<String>> appointmentNavigation(Document document){
        Map<Integer,Set<String>> out=new HashMap<>();Pattern entry=Pattern.compile("(?m)^[\\t ]*\\d+[.、]\\s*(项目负责人|施工部分负责人|设计部分负责人|施工企业主要负责人|专职安全员)\\s*[.．…·]{3,}\\s*(\\d+)\\s*$");
        for(Page page:cn.bidcheck.compat.Java8Api.toList(document.pages().stream().limit(10))){
            String text=Objects.toString(page.text(),"");if(!text.contains("目录")&&cn.bidcheck.compat.Java8Extras.lines(text).filter(s->s.matches(".*\\.{3,}.*")).count()<2)continue;
            Matcher m=entry.matcher(text);while(m.find())out.computeIfAbsent(Integer.parseInt(m.group(2)),x->new HashSet<>()).add(m.group(1));
        }return out;
    }
    private static boolean currentForm(Page page,String text,Map<Integer,Set<String>> navigation){
        if(!"PAGE".equals(page.locationType())||!text.contains("拟在本合同任职"))return false;
        // A navigation page number is not a physical page number. Require the original footer to agree,
        // plus the numbered bid section heading and the matching appointment form. A copied bare form
        // or a reset page number inside a historical attachment cannot satisfy this boundary.
        List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(text).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));
        if(lines.isEmpty()||!lines.get(lines.size()-1).equals(Integer.toString(page.number())))return false;
        String prefix=beforeHistory(text);for(String role:navigation.getOrDefault(page.number(),cn.bidcheck.compat.Java8Api.setOf()))
            if(prefix.substring(0,Math.min(180,prefix.length())).matches("(?s).*(?:^|\\n)\\d+[.、]"+Pattern.quote(role)+"\\s*\\n.*")
                &&prefix.matches("(?s).*附表[^\\n]{0,15}"+Pattern.quote(role)+"情况表.*")&&(prefix.matches("(?s).*拟在本合同任职\\s*"+Pattern.quote(role)+".*")
                    ||role.equals("施工企业主要负责人")&&prefix.matches("(?s).*拟在本合同任职\\s*/(?:\\s|$).*")))return true;
        return false;
    }
    private static String beforeHistory(String text){Matcher m=HISTORY.matcher(text);return m.find()?text.substring(0,m.start()):text;}
    private static boolean historyHeading(String text){String stripped=text.replaceFirst("(?s)^\\s*\\d+\\s*(?:【PDF识别文字】)?\\s*","");Matcher m=HISTORY.matcher(stripped);return m.find()&&m.start()<120&&!stripped.substring(0,m.start()).contains("拟在本合同任职");}
    private static List<Range> appointmentRanges(String text){
        // Work history is a distinct region even when the current appointment is in the same source chunk.
        Matcher history=HISTORY.matcher(text);int end=history.find()?history.start():text.length();
        return end>0?cn.bidcheck.compat.Java8Api.listOf(new Range(0,end)):cn.bidcheck.compat.Java8Api.listOf();
    }
    private static String outerProject(String text){
        Matcher letter=LETTER.matcher(text);if(letter.find())return normalizeProject(letter.group(1));
        Matcher label=LABEL.matcher(text);if(label.find())return normalizeProject(label.group(1));
        // An actual consortium agreement names the tender it joins. This exact project name,
        // unlike the pronoun 本项目, can close a preceding historical attachment's scope.
        String prefix=cn.bidcheck.compat.Java8Api.stripLeading(text);
        if(prefix.matches("(?s)^(?:[0-9一二三四五六七八九十.、\\s]*)联合体协议书\\s*.*")&&!historyHeading(prefix)){
            Matcher joint=Pattern.compile("共同投标\\s*([^。；]{8,220}?)(?:/标段|[。；])").matcher(prefix);
            if(joint.find())return normalizeProject(joint.group(1));
        }
        if(text.contains("投标文件")&&!text.matches("(?s).*\\.{3,}.*"))for(String line:text.split("\\n")){
            String value=cn.bidcheck.compat.Java8Api.strip(line);if(value.matches("[（(].{8,200}(?:工程|项目)[）)]"))return normalizeProject(value);
        }return "";
    }
    private static String coverProject(Document document){
        for(Page page:cn.bidcheck.compat.Java8Api.toList(document.pages().stream().limit(3))){
            String text=Objects.toString(page.text(),"");if(!text.contains("投标文件")||text.matches("(?s).*\\.{3,}.*"))continue;
            String named=outerProject(text);if(!cn.bidcheck.compat.Java8Api.isBlank(named))return named;
            for(String line:text.split("\\n")){String s=cn.bidcheck.compat.Java8Api.strip(line);if(s.matches("[（(].{8,200}(?:工程|项目)[）)]"))return normalizeProject(s);}
        }return "";
    }
    private static String normalizeProject(String text){return clean(text).replaceAll("^[（(]|[）)]$","").replaceFirst("^招标项目名称","");}
    private static boolean sameProject(String a,String b){return normalizeProject(a).equals(normalizeProject(b));}

    static boolean appointment(Source source,Fact fact){
        if(source.index()||source.scopeContext().blocked())return false;
        // A page-level historical flag cannot override a server-confirmed current form; unspecified legacy
        // sources retain the old conservative behavior. Scope never comes from model attributes.
        if(source.historical()&&!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION").contains(source.scopeContext().status()))return false;
        String role=DomainBindings.sourceRole(fact),person=fact.kind().equals("PERSON_ROLE")?fact.value():DomainBindings.roleTarget(fact,role);
        if(!DomainBindings.known(role)||cn.bidcheck.compat.Java8Api.isBlank(person))return false;
        // An enterprise principal is not appointed to a construction post. Its explicitly named
        // current-bid form can legitimately use '/' in the project-appointment cell. Only the
        // navigation+numbered form+printed page boundary established above admits this exception.
        if(role.equals("施工企业主要负责人")&&source.scopeContext().status().equals("CURRENT_BID_SECTION")&&source.scopeContext().anchor().endsWith(":cover-navigation-section-form-printed-page")){
            for(String block:localBlocks(source))if(block.matches("(?s).*附表[^\\n]{0,15}施工企业主要负责人情况表.*")&&block.matches("(?s).*拟在本合同任职\\s*/(?:\\s|$).*")){
                Set<String> named=new LinkedHashSet<>();Matcher names=NAME.matcher(block);while(names.find())named.add(names.group(1));if(named.equals(cn.bidcheck.compat.Java8Api.setOf(person)))return true;
            }
        }
        for(String block:localBlocks(source))if(boundAppointment(block,role,person,fact.field()))return true;
        return false;
    }
    static List<Fact> nativeAppointments(Source source,String requestedRole){
        List<Fact> facts=new ArrayList<>();if(source.index()||source.scopeContext().blocked())return facts;
        for(String block:localBlocks(source)){
            String text=block.replaceAll("(?i)<[^>]+>"," ");Matcher names=NAME.matcher(text);
            while(names.find())for(String role:PersonnelRoles.labels(requestedRole)){
                String person=names.group(1);Fact fact=new Fact("N"+identity(source.id()+role+person+"appointment-form-v1").substring(0,20),"PERSON_ROLE",role,"姓名",person,person,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),cn.bidcheck.compat.Java8Api.mapOf("documentScope", source.scopeContext().audit(), "bindingMethod", "EXPLICIT_CURRENT_APPOINTMENT_FIELD"));
                if(appointment(source,fact)&&new ProofVerifier().issue(fact,cn.bidcheck.compat.Java8Api.mapOf(source.id(), source))==null)facts.add(fact);
            }
        }return cn.bidcheck.compat.Java8Api.toList(facts.stream().distinct());
    }
    static List<String> localBlocks(Source source){
        String quote=source.evidence().quote();Context context=source.scopeContext();
        if(context.status().equals("UNSPECIFIED")&&context.appointmentBlocks().isEmpty())return cn.bidcheck.compat.Java8Api.listOf(beforeHistory(quote));
        List<String> blocks=new ArrayList<>();for(String block:context.appointmentBlocks()){
            // Cached processing facts may cite a narrower original excerpt. Never widen that excerpt.
            if(quote.contains(block))blocks.add(block);else if(block.contains(quote))blocks.add(quote);
        }return blocks;
    }
    private static boolean boundAppointment(String text,String role,String person,String field){
        if(text.matches("(?s).*(?:填写示例|示范文本|仅供参考).*"))return false;
        String plain=text.replaceAll("(?i)<[^>]+>"," ");
        for(String line:plain.split("[。；;\\n]"))if(ProofVerifier.contains(line,role)&&ProofVerifier.contains(line,person)
            &&line.matches(".*(?:不担任|不再担任|未担任|不得担任|不是|并非|不作为|非本项目|非本次).*"))return false;
        // A single-person form binds its 姓名 field to its current appointment field before any work history.
        Matcher names=NAME.matcher(plain);while(names.find()){
            String found=names.group(1);int start=names.start(),end=plain.length();Matcher next=NAME.matcher(plain);if(next.find(names.end()))end=next.start();
            String record=plain.substring(start,end);if(found.equals(person)&&record.matches("(?s).*拟在本合同任职\\s*[:：]?\\s*"+Pattern.quote(role)+"(?:\\s|[|<。；;]|$).*"))return true;
        }
        // For a roster, the current-project header governs rows, but each row must bind the two endpoints.
        if(CURRENT.matcher(clean(plain)).find()&&text.contains("<tr")){
            Matcher rows=Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(text);while(rows.find()){
                String row=rows.group(1).replaceAll("<[^>]+>"," ");if(ProofVerifier.contains(row,role)&&ProofVerifier.contains(row,person)&&row.length()<700)return true;
            }
        }
        for(String sentence:plain.split("[。；;\\n]"))if(CURRENT.matcher(sentence).find()&&ProofVerifier.contains(sentence,role)&&ProofVerifier.contains(sentence,person)){
            if(cn.bidcheck.compat.Java8Api.setOf(":", "：").contains(field)&&!clean(sentence).contains(clean(role)+field+clean(person)))continue;
            return true;
        }
        if(plain.matches("(?s)^\\s*(?:本项目人员配置|本项目人员安排|拟投入本项目人员)[^\\n]*\\n.*"))
            for(String line:plain.split("\\n"))if(clean(line).contains(clean(role)+"："+clean(person))||clean(line).contains(clean(role)+":"+clean(person)))return true;
        if(!PersonnelRoles.personRole(role)&&plain.matches("(?s)^\\s*本项目(?:授权书|联合体协议书|投标函|投标文件|基本情况表)[^\\n]*\\n.*")){
            String company=Pattern.quote(clean(person)),companyRole=Pattern.quote(clean(role));
            Pattern relation=Pattern.compile(companyRole+"[:：]"+company+"(?=$|[，,。；;])|"+company+"(?:角色|身份|作为|担任)(?:为|是)?"+companyRole+"(?=$|[，,。；;])");
            for(String line:plain.split("\\n"))if(relation.matcher(clean(line)).find())return true;
        }
        // A separately headed consortium agreement can bind member roles, but cannot establish employment.
        return !PersonnelRoles.personRole(role)&&plain.contains("联合体协议书")&&ProofVerifier.near(plain,role,person);
    }
}
