package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Original labelled observations within one validated contract. Never changes an accepted fact. */
final class ContractParticipants {
    final Set<String> clients=new LinkedHashSet<>(),performers=new LinkedHashSet<>(),unassigned=new LinkedHashSet<>();
    final List<Map<String,Object>> sides=new ArrayList<>(),appointments=new ArrayList<>();
    final List<Fact> appointedPeople=new ArrayList<>();

    ContractParticipants(Set<String> parties,List<Source> sources) {
        for(String party:parties) {
            boolean client=false,performer=false;
            for(Source source:sources) {
                for(String side:Arrays.asList("CLIENT","PERFORMER")) {
                    String labels="CLIENT".equals(side)?"甲方|发包人|委托人|建设单位|采购人":"乙方|承包人|承包方|施工单位|设计单位|设计人";
                    Matcher m=Pattern.compile("(?:^|[\\s|#，,。；;])("+labels+")[ \\t]*(?:[（(][ \\t]*(?:全称|盖章|签章)[ \\t]*[）)])?[ \\t:：|_＿]*"
                        +Pattern.quote(party)+"(?=[\\s|，,。；;（）()]|$)").matcher(source.evidence().quote());
                    if(m.find()) {
                        sides.add(linked("party",party,"side",side,"label",m.group(1),"sourceId",source.id(),"quote",m.group().trim()));
                        if("CLIENT".equals(side)) client=true; else performer=true;
                    }
                }
            }
            if(client==performer) unassigned.add(party);
            else if(client) clients.add(party); else performers.add(party);
        }
    }

    void linkAppointment(String wanted,String person,List<Fact> facts,List<Source> sources) {
        for(Fact name:facts) {
            if(!person.equals(str(name.attributes(),"person"))) continue;
            for(Fact role:facts) {
                String label=str(role.attributes(),"personRole");
                if(!roleMatches(wanted,label))continue;
                boolean sameFact=name.id().equals(role.id());
                String record=str(role.attributes(),"contractRecordId");
                if(!sameFact && (record.isEmpty() || !record.equals(str(name.attributes(),"contractRecordId")))) continue;
                Set<String> common=ContractEvidenceRecord.fieldSourceIds(name);
                common.retainAll(ContractEvidenceRecord.fieldSourceIds(role));
                for(Source source:sources) if(common.contains(source.id())) {
                    String quote=appointmentQuote(label,person,source.evidence().quote());
                    if(quote.isEmpty()) continue;
                    appointments.add(linked("recordId",record,"nameFactId",name.id(),"roleFactId",role.id(),"sourceId",source.id(),
                        "person",person,"personRole",label,"quote",quote,"currentPersonIdentityVerified",false));
                    if(!appointedPeople.contains(name))appointedPeople.add(name);
                }
            }
        }
    }

    private static boolean roleMatches(String wanted,String printed) {
        String role=PersonnelRoles.canonical(wanted);
        return !role.isEmpty() && (role.equals(PersonnelRoles.canonical(printed))
            || "设计负责人".equals(role) && ("承包人设计负责人".equals(printed)||"设计人项目负责人".equals(printed)));
    }

    /** Permit the role heading followed by a labelled name, within the same local entry.
     * Do not jump another person's name, a second role, an unrelated sentence or another page. */
    private static String appointmentQuote(String role,String name,String text) {
        Pattern entry=Pattern.compile("(?<![\\p{IsHan}])"+Pattern.quote(role)
            +"[ \\t]*[:：][\\s|>*#]*(?:[Xx×][\\s|>*#]*)?(?:姓名[ \\t]*[:：][ \\t]*)?([\\p{IsHan}·]{2,12}?)(?=身份证|[\\s|，,。；;（）()]|$)");
        Matcher m=entry.matcher(text);
        String quote="";Set<String> names=new HashSet<>();
        while(m.find()){names.add(m.group(1));if(name.equals(m.group(1)))quote=m.group();}
        return names.size()==1?quote:"";
    }
}
