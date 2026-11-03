package cn.bidcheck.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Server-classified proposition scope. Model prose about a capability is not a signing fact. */
final class VisualAssertionScope {
    private VisualAssertionScope() {}
    enum Kind { OUTSIDE_SCOPE, DOCUMENT_REQUIREMENT, PREVENTIVE_ADVICE, MATERIAL_SCOPE, PRINTED_FIELD, OBSERVATION_PRESENT, OBSERVATION_ABSENT, CHECK_PENDING, TEMPLATE_LABEL, OTHER }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Proposition {
        private final String text;
        private final Kind kind;
        @com.fasterxml.jackson.annotation.JsonCreator
        Proposition(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("kind") Kind kind) { this.text = text; this.kind = kind; }
        public String text() { return text; }
        public Kind kind() { return kind; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Proposition that = (Proposition) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.kind, that.kind); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.kind); return hash; }
        @Override public String toString() { return "Proposition[" + "text=" + text + ", kind=" + kind + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Assessment {
        private final List<Proposition> propositions;
        private final boolean required;
        @com.fasterxml.jackson.annotation.JsonCreator
        Assessment(@com.fasterxml.jackson.annotation.JsonProperty("propositions") List<Proposition> propositions, @com.fasterxml.jackson.annotation.JsonProperty("required") boolean required) { this.propositions = propositions; this.required = required; }
        public List<Proposition> propositions() { return propositions; }
        public boolean required() { return required; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Assessment that = (Assessment) other; return java.util.Objects.equals(this.propositions, that.propositions) && java.util.Objects.equals(this.required, that.required); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.propositions); hash = 31 * hash + java.util.Objects.hashCode(this.required); return hash; }
        @Override public String toString() { return "Assessment[" + "propositions=" + propositions + ", required=" + required + "]"; }

        boolean unsupportedObservation(){return propositions.stream().anyMatch(p->cn.bidcheck.compat.Java8Api.setOf(Kind.OBSERVATION_PRESENT, Kind.OBSERVATION_ABSENT).contains(p.kind()));}
        String decisionText(){return String.join("。",cn.bidcheck.compat.Java8Api.toList(propositions.stream().filter(p->!nonObservation(p.kind())).map(Proposition::text)));}
        Map<String,Object> audit(){return cn.bidcheck.compat.Java8Api.mapOf("derivedBy", "SERVER_ASSERTION_SCOPE", "signatureRequired", required, "outsideScope", cn.bidcheck.compat.Java8Api.toList(propositions.stream().filter(p->p.kind()==Kind.OUTSIDE_SCOPE).map(Proposition::text)), "documentRequirements", cn.bidcheck.compat.Java8Api.toList(propositions.stream().filter(p->p.kind()==Kind.DOCUMENT_REQUIREMENT).map(Proposition::text)), "preventiveAdvice", cn.bidcheck.compat.Java8Api.toList(propositions.stream().filter(p->p.kind()==Kind.PREVENTIVE_ADVICE).map(Proposition::text)), "materialScope", cn.bidcheck.compat.Java8Api.toList(propositions.stream().filter(p->p.kind()==Kind.MATERIAL_SCOPE).map(Proposition::text)), "printedFields", cn.bidcheck.compat.Java8Api.toList(propositions.stream().filter(p->p.kind()==Kind.PRINTED_FIELD).map(Proposition::text)), "observations", cn.bidcheck.compat.Java8Api.toList(propositions.stream().filter(p->cn.bidcheck.compat.Java8Api.setOf(Kind.OBSERVATION_PRESENT, Kind.OBSERVATION_ABSENT).contains(p.kind())).map(p->cn.bidcheck.compat.Java8Api.mapOf("kind", p.kind().name(), "text", p.text()))), "boundaryDoesNotProveSigning", true);}
    }
    private static final String TERM="(?:签字|签名|签署|签章|盖章|公章|印章|单位章|专用章)";
    private static final Pattern VISUAL=Pattern.compile(TERM);
    private static final Pattern ABSENCE=Pattern.compile("(?:未|未见|没有|并未|缺少|缺失|漏)(?:实际)?(?:加盖)?(?:"+TERM+"|章)|"+TERM+"(?:栏|位置|处)?(?:为|是)?(?:空白|缺失|为空|未填写)");
    private static final Pattern PRESENT=Pattern.compile("(?:已|已经)(?:经原图确认)?(?:实际)?(?:完成)?(?:加盖)?"+TERM+"|(?:可见|观察到|存在|带有|载有|附有).{0,12}"+TERM+"|"+TERM+"(?:均|已|全部|实际)?(?:齐全|完整|完备|清晰可见|真实有效|已完成)");
    private static final Pattern BOUNDARY=Pattern.compile(TERM+".{0,12}(?:不在|不属|不属于|不纳入).{0,22}(?:核验|核对|审查|检查|审阅|识别)?范围(?:内)?|"
        +"(?:本次|本轮).{0,12}(?:不核验|不检查|不鉴定).{0,12}"+TERM+"|"
        +"(?:不表示|不意味着|不代表|不等于|不证明).{0,30}(?:"+TERM+"|真伪|真实性)|"
        +"(?:无法|不能|未能|尚未|不足以).{0,25}(?:确认|验证|核实|证明|判断|鉴定|辨认|核验).{0,35}(?:"+TERM+"|真伪|真实性)");
    static Assessment assess(String reason,String suggestion,JsonNode claims,boolean required){
        List<Proposition> all=new ArrayList<>();all.addAll(classify(reason));all.addAll(classify(suggestion));
        if(claims!=null&&claims.isArray())for(JsonNode claim:claims){
            // Classify every proposition before recognising a printed field. A DATE/TEXT
            // claim can also contain a real visual assertion, including inside value.
            for(Proposition part:classify(claim.path("text").asText()))
                all.add(cn.bidcheck.compat.Java8Api.setOf(Kind.OTHER, Kind.TEMPLATE_LABEL).contains(part.kind())&&printedSigningField(claim,part.text())?new Proposition(part.text(),Kind.PRINTED_FIELD):part);
            all.addAll(classify(claim.path("value").asText()));}
        return new Assessment(cn.bidcheck.compat.Java8Api.listCopyOf(all),required);
    }
    static boolean boundaryClaim(JsonNode claim){
        List<Proposition> statement=classify(claim.path("text").asText());
        if(statement.stream().noneMatch(p->p.kind()==Kind.OUTSIDE_SCOPE))return false;
        List<Proposition> all=new ArrayList<>(statement);all.addAll(classify(claim.path("value").asText()));
        return all.stream().noneMatch(p->cn.bidcheck.compat.Java8Api.setOf(Kind.OBSERVATION_ABSENT, Kind.OBSERVATION_PRESENT).contains(p.kind()))
            &&all.stream().filter(p->VISUAL.matcher(p.text()).find()).allMatch(p->cn.bidcheck.compat.Java8Api.setOf(Kind.OUTSIDE_SCOPE, Kind.TEMPLATE_LABEL, Kind.CHECK_PENDING).contains(p.kind()));
    }
    private static boolean nonObservation(Kind kind){return cn.bidcheck.compat.Java8Api.setOf(Kind.OUTSIDE_SCOPE, Kind.DOCUMENT_REQUIREMENT, Kind.PREVENTIVE_ADVICE, Kind.MATERIAL_SCOPE, Kind.PRINTED_FIELD, Kind.CHECK_PENDING, Kind.TEMPLATE_LABEL).contains(kind);}
    static String withoutBoundaries(String text){return String.join("。",cn.bidcheck.compat.Java8Api.toList(classify(text).stream().filter(p->!nonObservation(p.kind())).map(Proposition::text)));}
    static List<Proposition> classify(String text){
        List<Proposition> parts=new ArrayList<>();boolean advisory=false,requirement=false;String input=Objects.toString(text,"");
        java.util.regex.Matcher separators=Pattern.compile("[。；;！？!?\\r\\n，,]|但是|然而|不过|但|并且|而且|且|并(?=已|未|缺|没有)").matcher(input);int start=0;
        while(start<=input.length()) {
            boolean hasSeparator=separators.find();String raw=input.substring(start,hasSeparator?separators.start():input.length());
            String separator=hasSeparator?separators.group():"";start=hasSeparator?separators.end():input.length()+1;
            String part=raw.replaceAll("[\\t \\u00a0\\u3000]+","");if(cn.bidcheck.compat.Java8Api.isBlank(part)){
                advisory=advisory&&cn.bidcheck.compat.Java8Api.setOf("，", ",").contains(separator);
                requirement=requirement&&cn.bidcheck.compat.Java8Api.setOf("，", ",", "并且", "而且", "且").contains(separator);
                continue;
            }
            Kind kind=Kind.OTHER;
            if(VISUAL.matcher(part).find()||BOUNDARY.matcher(part).find()||ABSENCE.matcher(part).find()) {
                // A scoped qualification cannot excuse a second affirmative/negative proposition.
                if(BOUNDARY.matcher(part).find())kind=Kind.OUTSIDE_SCOPE;
                else if(advisory&&part.matches("(?:确认|核对|核实|查验).*")&&!actualSigning(part))kind=Kind.CHECK_PENDING;
                else if(requirement&&part.matches("(?:并)?由.+(?:签字|签名|签署|签章|盖章).*")&&!actualSigning(part))kind=Kind.DOCUMENT_REQUIREMENT;
                else if(preventiveAdvice(part))kind=Kind.PREVENTIVE_ADVICE;
                else if(signingRequirement(part))kind=Kind.DOCUMENT_REQUIREMENT;
                else if(part.matches("(?:未见|未找到|未提供|没有)[^。；;]{0,120}(?:承诺书|声明书|授权书|投标函|材料|文件)[^。；;]{0,20}(?:联合签署|各方签署)版本[。]?$"))kind=Kind.MATERIAL_SCOPE;
                else if(part.matches("(?s).*(?:是否|需(?:要)?核对|待核|建议|请核对|尚待|尚需).*")&&!part.matches("(?s).*(?:已确认|经核验|已观察).*"))kind=Kind.CHECK_PENDING;
                else if(printedDateOnly(part))kind=Kind.PRINTED_FIELD;
                else if(printedSigningLabel(part))kind=Kind.TEMPLATE_LABEL;
                else if(part.matches("(?s).*(?:载有|包含|列有|设有).{0,30}(?:公章|签字|签章|盖章)(?:位置|栏|及签字栏).*")&&!part.matches("(?s).*(?:已盖|已签|实际加盖|实际签署|清晰可见|观察到|确认未|未盖章|未签字).*"))kind=Kind.TEMPLATE_LABEL;
                else if(PRESENT.matcher(part).find())kind=Kind.OBSERVATION_PRESENT;
                else if(ABSENCE.matcher(part).find())kind=Kind.OBSERVATION_ABSENT;
                else if(BOUNDARY.matcher(part).find())kind=Kind.OUTSIDE_SCOPE;
                else if(part.matches("(?s).*(?:投标人|法定代表人|委托代理人|授权代表)[（(](?:盖章|签字|签章)[）)].*")||part.matches("(?s).*(?:模板|格式|标注|注明|签字栏|签章栏|盖章栏).*"))kind=Kind.TEMPLATE_LABEL;
            }
            parts.add(new Proposition(cn.bidcheck.compat.Java8Api.strip(raw),kind));
            advisory=(advisory||part.matches("(?s).*(?:建议|请|务必).{0,20}(?:核对|确认|检查|复核|核实|查验).*")
                ||part.matches("(?:同时|并|另|还需)(?:核对|复核|核实|查验).*")&&!actualSigning(part))&&cn.bidcheck.compat.Java8Api.setOf("，", ",").contains(separator);
            requirement=kind==Kind.DOCUMENT_REQUIREMENT&&cn.bidcheck.compat.Java8Api.setOf("，", ",", "并且", "而且", "且").contains(separator);
        }return parts;
    }
    private static boolean signingRequirement(String part){
        if(part.matches("(?s).*(?:已发现|核验发现|实际未|观察到|已确认|确认未).*"))return false;
        boolean explicitDuty=part.matches("(?s).*(?:应当|必须|须|(?<!响)应|需要|需)(?:加盖|盖|签).*")&&!actualSigning(part);
        boolean permission=part.matches("(?s).*(?:可(?:以)?由|允许由|应由|须由|必须由|只需由).{1,60}(?:签署|签字|签章|盖章).*")
            &&!part.matches("(?s).*(?:已经|已由|已签|已盖|实际签|实际盖|确已|并未|尚未|没有|未签|未盖).*");
        boolean consequence=part.matches("(?s).*(?:未签章|未盖章|未签字|未签名).{0,45}(?:将|会|则|否则).{0,18}(?:否决|无效|不予受理).*" );
        boolean quotedRule=part.matches("(?s).*(?:注记|注释|模板|格式|招标文件|条款).{0,14}(?:提示|规定|写明|要求|关于).*(?:未签章|未盖章|未签字|未签名).*" );
        return explicitDuty||permission||quotedRule||consequence;
    }
    private static boolean actualSigning(String part){
        return PRESENT.matcher(part).find()||ABSENCE.matcher(part).find()
            ||part.matches("(?s).*(?:已确认|核验发现|观察到|已发现|确已|已经|已完成|已由|并未|尚未|没有|实际未|未由|已按|已经按|(?:核实|核验|核对|查验)(?:结果|后发现)).*" );
    }
    private static boolean preventiveAdvice(String part){
        // A prospective instruction does not report what an image contains. Do not
        // absorb a separately observed fact, even when it appears in an advice sentence.
        return part.matches("(?:避免|以免|防止|为避免|为防止).*(?:未签章|未盖章|未签字|未签名|漏盖|漏签).*(?:导致|造成|影响|被|否决|无效|退回).*")
            && !part.matches("(?s).*(?:已发现|核验发现|实际未|观察到|已确认|确认未|确实未|目前未|本页未|本文件未).*");
    }
    private static boolean printedDateOnly(String part){
        // “签署日期” names a printed field; it does not say that a signature was observed.
        // Remove only that noun phrase, then retain any separate signing observation.
        String remaining=part.replaceAll("(?:签署落款|签署|落款|签字)(?:日期|时间)","日期字段");
        return !remaining.equals(part)&&!VISUAL.matcher(remaining).find();
    }
    private static boolean printedSigningLabel(String part){
        if(!part.matches("(?s).*(?:载有|包含|列有|设有|印有).{0,35}(?:签字|签章|盖章|公章)(?:及日期)?(?:印刷)?(?:文字|字样).*"))return false;
        if(part.matches("(?s).*(?:已盖|已签|实际加盖|实际签署|实际盖章|实际签字|清晰可见|观察到|确认未|未盖章|未签字).*"))return false;
        // Only exempt the printed label itself, never another seal/signature in the clause.
        String remaining=part.replaceAll("(?:签字|签章|盖章|公章)(?:及日期)?(?:印刷)?(?:文字|字样)","文字字段");
        return !VISUAL.matcher(remaining).find();
    }
    private static boolean printedSigningField(JsonNode claim,String statement){
        String value=claim.path("value").asText(),kind=claim.path("kind").asText();
        if(statement.matches("(?s).*(?:已签|已盖|已经签|已经盖|实际签|实际盖|完成签|完成盖|确认签|确认盖|签章齐全|签字齐全).*"))return false;
        if("DATE".equals(kind))return statement.matches("(?s).*(?:签署|落款|签字)(?:日期|时间).*")&&cn.bidcheck.compat.Java8Api.setOf("签署日期", "落款日期", "签字日期", "落款时间", "签署时间", "签字时间").contains(claim.path("validityField").asText())
            &&value.matches("(?s).*\\d{4}\\s*(?:年|[-./]).*");
        return "TEXT".equals(kind)&&statement.matches("(?s).*(?:落款单位|落款投标人|签署主体|落款主体).*")
            &&value.matches("(?s).*(?:投标人|供应商|承诺人|声明人)[（(](?:盖章|签字|签章)[）)]\\s*[:：].*");
    }
}
