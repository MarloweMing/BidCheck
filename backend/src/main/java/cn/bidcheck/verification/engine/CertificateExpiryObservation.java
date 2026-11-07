package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.source.DateFieldValues;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Independent, printed end field on a single certificate already read for this target.
 * The submitted range retains its original rejection. The caller must submit this new
 * observation through the complete source, scope, target and fact-publication checks. */
final class CertificateExpiryObservation {
    private static final Pattern HOLDER=Pattern.compile("(?m)^[ \\t]*(?:企业名称|单位名称|持证单位|持证人|姓\\s*名)[ \\t]*[:：][ \\t]*([^\\r\\n|]+)");
    private static final String DATE="(?:19|20)[0-9]{2}\\s*[年./-]\\s*[0-9]{1,2}\\s*[月./-]\\s*[0-9]{1,2}\\s*日?";
    private CertificateExpiryObservation(){}
    static Map<String,Object> extract(Rule target,Map<String,Object> candidate,Map<String,Source> disclosed,Set<String> read){
        if(target==null||!"VALIDITY".equals(target.predicate())||!Boolean.TRUE.equals(target.parameters().get("allApplicableCertificates"))
                ||!"CERTIFICATE".equals(str(candidate,"kind"))||!"CURRENT_PROJECT".equals(str(candidate,"scope"))
                ||!(candidate.get("attributes") instanceof Map))return Collections.emptyMap();
        Map<String,Object> attrs=(Map<String,Object>)candidate.get("attributes");
        String field=str(attrs,"validityField"),holder=str(candidate,"subject"),number=str(attrs,"number"),numberField=str(attrs,"numberField");
        LocalDate end=ProofVerifier.date(attrs.get("end"));
        List<String> ids=ProofVerifier.strings(candidate.get("sourceIds"));
        if(ids.size()!=1||!read.contains(ids.get(0))||ProofVerifier.date(attrs.get("start"))==null||end==null
                ||!end.toString().equals(str(candidate,"value"))||!DomainBindings.validityField(field)
                ||!field.equals(str(target.parameters(),"validityField")))return Collections.emptyMap();
        Source source=disclosed.get(ids.get(0));if(!CertificateEvidenceRecords.usable(source))return Collections.emptyMap();
        Fact original=new Fact("", "CERTIFICATE",holder,str(candidate,"field"),str(candidate,"rawValue"),str(candidate,"value"),
            str(candidate,"unit"),str(candidate,"basis"),str(candidate,"scope"),ids,attrs);
        if(!DomainBindings.certificateIdentity(original))return Collections.emptyMap();
        String block=ProofVerifier.certificateBlock(source.evidence().quote(),original);
        Set<String> holders=new LinkedHashSet<>();Matcher names=HOLDER.matcher(block);
        while(names.find())holders.add(clean(names.group(1)));
        if(!holders.equals(Collections.singleton(clean(holder)))||!ProofVerifier.contains(block,original.rawValue())
                ||!DateFieldValues.read(block,field,2).equals(Collections.singletonList(end)))return Collections.emptyMap();
        Matcher printedNumber=Pattern.compile("(?m)^[ \\t]*"+Pattern.quote(numberField)+"[ \\t]*[:：][ \\t]*"+Pattern.quote(number)+"[ \\t]*\\r?$").matcher(block);
        if(!printedNumber.find()||printedNumber.find())return Collections.emptyMap();
        Matcher expiry=Pattern.compile("(?m)^[ \\t]*"+Pattern.quote(field)+"[ \\t]*[:：]?[ \\t]*(?:截止至|截至|至)[ \\t]*("+DATE+")[ \\t]*\\r?$").matcher(block);
        if(!expiry.find())return Collections.emptyMap();String raw=expiry.group().trim();
        if(expiry.find()||!DateFieldValues.read(raw,field,1).equals(Collections.singletonList(end)))return Collections.emptyMap();
        return linked("kind","CERTIFICATE","subject",holder,"field",original.field(),"rawValue",raw,"value",end.toString(),
            "scope",original.scope(),"unit",original.unit(),"basis",original.basis(),"sourceIds",ids,"attributes",
            linked("category",str(attrs,"category"),"numberField",numberField,"number",number,"validityField",field,"end",end.toString()));
    }
}
