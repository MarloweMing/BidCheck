package cn.bidcheck.verification.engine;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Links a named monetary ceiling to its explicit comparison, retaining both original spans. */
final class PriceRequirementBinding {
    private static final String LABEL="最高(?:投标)?限价";
    private static final Pattern DEFINITION=Pattern.compile("("+LABEL+")\\s*(?:为|[:：])\\s*(人民币|CNY|RMB|美元|USD)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(亿元|万元|元)\\s*(人民币|CNY|RMB|美元|USD)?(?:[（(](不含税|含税)[）)])?");
    private static final Pattern COMPARISON=Pattern.compile("(投标人|投标单位)?\\s*((?:(?:不含税|含税))?投标(?:总)?报价)\\s*(不得超过|不超过|不高于|≤|<=|高于|超过)\\s*("+LABEL+")(的[，,]?(?:其)?投标(?:将)?(?:被)?否决|的[，,]?其投标将被否决)?");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Constraint {
        private final String label;
        private final String number;
        private final String unit;
        private final String currency;
        private final String tax;
        private final String subject;
        private final String basis;
        private final String op;
        private final int definitionStart;
        private final int definitionEnd;
        private final int comparisonStart;
        private final int comparisonEnd;
        @com.fasterxml.jackson.annotation.JsonCreator
        Constraint(@com.fasterxml.jackson.annotation.JsonProperty("label") String label, @com.fasterxml.jackson.annotation.JsonProperty("number") String number, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("currency") String currency, @com.fasterxml.jackson.annotation.JsonProperty("tax") String tax, @com.fasterxml.jackson.annotation.JsonProperty("subject") String subject, @com.fasterxml.jackson.annotation.JsonProperty("basis") String basis, @com.fasterxml.jackson.annotation.JsonProperty("op") String op, @com.fasterxml.jackson.annotation.JsonProperty("definitionStart") int definitionStart, @com.fasterxml.jackson.annotation.JsonProperty("definitionEnd") int definitionEnd, @com.fasterxml.jackson.annotation.JsonProperty("comparisonStart") int comparisonStart, @com.fasterxml.jackson.annotation.JsonProperty("comparisonEnd") int comparisonEnd) { this.label = label; this.number = number; this.unit = unit; this.currency = currency; this.tax = tax; this.subject = subject; this.basis = basis; this.op = op; this.definitionStart = definitionStart; this.definitionEnd = definitionEnd; this.comparisonStart = comparisonStart; this.comparisonEnd = comparisonEnd; }
        public String label() { return label; }
        public String number() { return number; }
        public String unit() { return unit; }
        public String currency() { return currency; }
        public String tax() { return tax; }
        public String subject() { return subject; }
        public String basis() { return basis; }
        public String op() { return op; }
        public int definitionStart() { return definitionStart; }
        public int definitionEnd() { return definitionEnd; }
        public int comparisonStart() { return comparisonStart; }
        public int comparisonEnd() { return comparisonEnd; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Constraint that = (Constraint) other; return java.util.Objects.equals(this.label, that.label) && java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.unit, that.unit) && java.util.Objects.equals(this.currency, that.currency) && java.util.Objects.equals(this.tax, that.tax) && java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.basis, that.basis) && java.util.Objects.equals(this.op, that.op) && java.util.Objects.equals(this.definitionStart, that.definitionStart) && java.util.Objects.equals(this.definitionEnd, that.definitionEnd) && java.util.Objects.equals(this.comparisonStart, that.comparisonStart) && java.util.Objects.equals(this.comparisonEnd, that.comparisonEnd); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.label); hash = 31 * hash + java.util.Objects.hashCode(this.number); hash = 31 * hash + java.util.Objects.hashCode(this.unit); hash = 31 * hash + java.util.Objects.hashCode(this.currency); hash = 31 * hash + java.util.Objects.hashCode(this.tax); hash = 31 * hash + java.util.Objects.hashCode(this.subject); hash = 31 * hash + java.util.Objects.hashCode(this.basis); hash = 31 * hash + java.util.Objects.hashCode(this.op); hash = 31 * hash + java.util.Objects.hashCode(this.definitionStart); hash = 31 * hash + java.util.Objects.hashCode(this.definitionEnd); hash = 31 * hash + java.util.Objects.hashCode(this.comparisonStart); hash = 31 * hash + java.util.Objects.hashCode(this.comparisonEnd); return hash; }
        @Override public String toString() { return "Constraint[" + "label=" + label + ", number=" + number + ", unit=" + unit + ", currency=" + currency + ", tax=" + tax + ", subject=" + subject + ", basis=" + basis + ", op=" + op + ", definitionStart=" + definitionStart + ", definitionEnd=" + definitionEnd + ", comparisonStart=" + comparisonStart + ", comparisonEnd=" + comparisonEnd + "]"; }
 }

    static Rule resolve(Rule rule,RequirementContextBindings bindings){
        Map<String,Object> clean=new LinkedHashMap<>(rule.parameters());clean.remove("priceConstraintSources");
        Rule original=copy(rule,rule.subject(),rule.operator(),rule.expected(),rule.unit(),clean);
        if(!rule.predicate().equals("NUMBER")||!rule.children().isEmpty())return original;
        Constraint c=find(rule,bindings.source());if(c==null)return original;
        Map<String,Object> params=new LinkedHashMap<>(clean);
        params.put("amountBasis",c.basis());if(!cn.bidcheck.compat.Java8Api.isBlank(c.tax()))params.put("taxBasis",c.tax());
        if(!cn.bidcheck.compat.Java8Api.isBlank(c.currency()))params.put("currency",c.currency());
        String source=bindings.source();
        Map<String,Object> definition=bindings.reference(c.number(),source.substring(c.definitionStart(),c.definitionEnd()),c.definitionStart(),"NAMED_LIMIT_DEFINITION","");
        Map<String,Object> comparison=bindings.reference(c.op(),source.substring(c.comparisonStart(),c.comparisonEnd()),c.comparisonStart(),"NAMED_LIMIT_COMPARISON","");
        params.put("priceConstraintSources",linked("definition",definition,"comparison",comparison,"referenceName",c.label(),"operator",c.op(),"amountBasis",c.basis()));
        Map<String,Object> fields=new LinkedHashMap<>();if(params.get("requirementBindings") instanceof Map<?,?>)((Map<?,?>) (params.get("requirementBindings"))).forEach((k,v)->fields.put(k.toString(),v));
        fields.put("amountBasis",bindings.reference(c.basis(),source.substring(c.comparisonStart(),c.comparisonEnd()),c.comparisonStart(),"LOCAL",""));
        if(!cn.bidcheck.compat.Java8Api.isBlank(c.subject())&&source.substring(c.comparisonStart(),c.comparisonEnd()).contains(c.subject()))fields.put("subject",bindings.reference(c.subject(),source.substring(c.comparisonStart(),c.comparisonEnd()),c.comparisonStart(),"LOCAL",""));
        if(!cn.bidcheck.compat.Java8Api.isBlank(c.tax()))fields.put("taxBasis",bindings.reference(c.tax(),source.substring(c.definitionStart(),c.definitionEnd()),c.definitionStart(),"CLAUSE",""));
        if(!cn.bidcheck.compat.Java8Api.isBlank(c.currency()))fields.put("currency",bindings.reference(c.currency(),source.substring(c.definitionStart(),c.definitionEnd()),c.definitionStart(),"CLAUSE",""));
        params.put("requirementBindings",fields);
        return copy(rule,cn.bidcheck.compat.Java8Api.isBlank(c.subject())?rule.subject():c.subject(),c.op(),c.number(),c.unit(),params);
    }

    static boolean bound(Rule rule,String source){
        if(!(rule.parameters().get("priceConstraintSources") instanceof Map<?,?>)
            ||!(((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("definition") instanceof Map<?,?>)||!(((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("comparison") instanceof Map<?,?>)
            ||!RequirementContextBindings.validReference(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("definition"))),source)||!RequirementContextBindings.validReference(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("comparison"))),source))return false;
        Constraint found=find(rule,source);if(found==null)return false;
        return found.op().equals(rule.operator())&&found.unit().equals(rule.unit())&&sameNumber(found.number(),rule.expected())
            &&found.basis().equals(str(rule.parameters(),"amountBasis"))&&found.tax().equals(str(rule.parameters(),"taxBasis"))
            &&found.currency().equals(str(rule.parameters(),"currency"))
            &&found.definitionStart()==((Number)((Map<?,?>) (((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("definition"))).get("start")).intValue()&&found.definitionEnd()==((Number)((Map<?,?>) (((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("definition"))).get("end")).intValue()
            &&found.comparisonStart()==((Number)((Map<?,?>) (((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("comparison"))).get("start")).intValue()&&found.comparisonEnd()==((Number)((Map<?,?>) (((Map<?,?>) (rule.parameters().get("priceConstraintSources"))).get("comparison"))).get("end")).intValue();
    }

    private static Constraint find(Rule rule,String source){
        if(RequirementContextBindings.differentLots(source)||source.matches("(?s).*(?:如果|若|否则|除非).*"))return null;
        List<Constraint> found=new ArrayList<>();Matcher comparison=COMPARISON.matcher(source);
        while(comparison.find()){
            String op=cn.bidcheck.compat.Java8Api.setOf("高于", "超过").contains(comparison.group(3))?(comparison.group(5)==null?"":"<="):"<=";
            if(cn.bidcheck.compat.Java8Api.isBlank(op))continue;
            String subject=Objects.toString(comparison.group(1),"");
            if(!cn.bidcheck.compat.Java8Api.isBlank(rule.subject())&&!subject.equals(rule.subject())){
                if(!cn.bidcheck.compat.Java8Api.isBlank(subject)||RequirementContextBindings.validatedReference(rule,"subject",rule.subject(),source)==null)continue;
                subject=rule.subject();
            }
            Matcher definition=DEFINITION.matcher(source);List<int[]> candidates=new ArrayList<>();List<String[]> values=new ArrayList<>();
            while(definition.find())if(definition.group(1).equals(comparison.group(4))){
                String before=Objects.toString(definition.group(2),""),after=Objects.toString(definition.group(5),"");
                if(!cn.bidcheck.compat.Java8Api.isBlank(before)&&!cn.bidcheck.compat.Java8Api.isBlank(after)&&!before.equals(after))continue;
                candidates.add(new int[]{definition.start(),definition.end()});values.add(new String[]{definition.group(1),definition.group(3),definition.group(4),cn.bidcheck.compat.Java8Api.isBlank(before)?after:before,Objects.toString(definition.group(6),"")});
            }
            if(candidates.size()!=1)continue;
            String[] v=values.get(0);int[] position=candidates.get(0);
            if(RequirementContextBindings.subjectScopeStart(source,position[0])!=RequirementContextBindings.subjectScopeStart(source,comparison.start()))continue;
            int sectionStart=Math.max(0,position[0]-60);for(int i=position[0]-1;i>=sectionStart;i--)if("。；;\\n".indexOf(source.charAt(i))>=0){sectionStart=i+1;break;}
            String prefix=source.substring(sectionStart,position[0]);
            if(prefix.matches("(?s).*(?:设计费|施工费|建安费|设备费|暂列金|分项|分部)\\s*$"))continue;
            if(RequirementContextBindings.hasUnboundCompany(source.substring(sectionStart,Math.max(position[1],comparison.end())),subject))continue;
            if(!sameNumber(v[1],rule.expected()))continue;
            if(!rule.sourceQuote().contains(comparison.group(4)))continue;
            String basis=comparison.group(2),tax=v[4];
            String comparisonTax=basis.startsWith("不含税")?"不含税":basis.startsWith("含税")?"含税":"";
            if(!cn.bidcheck.compat.Java8Api.isBlank(comparisonTax)&&!cn.bidcheck.compat.Java8Api.isBlank(tax)&&!comparisonTax.equals(tax))continue;
            // A comparison's tax label cannot be assigned to an unqualified monetary definition.
            if(!cn.bidcheck.compat.Java8Api.isBlank(comparisonTax)&&!comparisonTax.equals(tax))continue;
            for(String field:cn.bidcheck.compat.Java8Api.listOf("lot", "taxBasis", "currency")){
                String requested=str(rule.parameters(),field),actual=field.equals("taxBasis")?tax:field.equals("currency")?v[3]:"";
                if(!cn.bidcheck.compat.Java8Api.isBlank(requested)&&!requested.equals(actual))return null;
            }
            found.add(new Constraint(v[0],v[1],v[2],v[3],tax,subject,basis,op,position[0],position[1],comparison.start(),comparison.end()));
        }
        return found.size()==1?found.get(0):null;
    }
    private static boolean sameNumber(String a,String b){try{return new BigDecimal(a).compareTo(new BigDecimal(b))==0;}catch(Exception ignored){return false;}}
    private static Rule copy(Rule r,String subject,String operator,String expected,String unit,Map<String,Object> parameters){return new Rule(r.id(),r.description(),r.predicate(),subject,r.field(),operator,expected,unit,parameters,r.sourceQuote(),r.logic(),r.children());}
}
