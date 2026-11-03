package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Conservative ownership proof for explicitly delimited source tables; no model-proposed columns or boxes. */
final class VehicleTableIdentity {
    private VehicleTableIdentity(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Binding{
        private final Map<String,Object> entity;
        private final List<Map<String,Object>> sources;
        @com.fasterxml.jackson.annotation.JsonCreator
        Binding(@com.fasterxml.jackson.annotation.JsonProperty("entity") Map<String,Object> entity, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Map<String,Object>> sources) { this.entity = entity; this.sources = sources; }
        public Map<String,Object> entity() { return entity; }
        public List<Map<String,Object>> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Binding that = (Binding) other; return java.util.Objects.equals(this.entity, that.entity) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.entity); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "Binding[" + "entity=" + entity + ", sources=" + sources + "]"; }
}

    static Optional<Binding> resolve(FactLedgerService.Scope scope,Map<String,Object> fact,List<Map<String,Object>> sources){
        if(!"VEHICLE".equals(text(map(fact.get("attributes")),"entityType")))return Optional.empty();
        String field=canonicalField(text(fact,"field")),raw=clean(text(fact,"rawValue"));
        if(cn.bidcheck.compat.Java8Api.isBlank(field)||cn.bidcheck.compat.Java8Api.isBlank(raw)||sources.size()>=16)return Optional.empty();
        List<Binding> matches=new ArrayList<>();
        for(Map<String,Object> source:sources){
            Map<String,Object> ev=map(source.get("evidence"));String quote=text(ev,"quote");
            Page page=scope.pages.get(FactLedgerService.Scope.pageKey(text(ev,"documentId"),text(ev,"locationType"),integer(ev.get("page"),-1)));
            if(page==null)continue;
            String[] lines=Objects.toString(page.text(),"").split("\\R",-1);
            for(int row=0;row<lines.length;row++){
                String rowText=lines[row];List<String> values=cells(rowText);
                if(values.size()<2||values.size()>30||!quote.contains(cn.bidcheck.compat.Java8Api.strip(rowText)))continue;
                for(int previous=row-1;previous>=0&&previous>=row-100;previous--){
                    String line=lines[previous];if(cn.bidcheck.compat.Java8Api.isBlank(line))break;
                    if(line.matches("[|｜:：\\-—\\s]+"))continue;
                    List<String> labels=cells(line);if(labels.size()!=values.size())break;
                    int vinColumn=-1,fieldColumn=-1;boolean duplicate=false;
                    for(int c=0;c<labels.size();c++){
                        String canonical=canonicalField(labels.get(c));
                        if("vin".equals(canonical)){if(vinColumn>=0)duplicate=true;vinColumn=c;}
                        if(field.equals(canonical)){if(fieldColumn>=0)duplicate=true;fieldColumn=c;}
                    }
                    if(vinColumn<0)continue;
                    if(duplicate||fieldColumn<0||!clean(values.get(fieldColumn)).equalsIgnoreCase(raw))break;
                    String vin=clean(values.get(vinColumn)).toUpperCase(Locale.ROOT);
                    if(!vin.matches("[A-HJ-NPR-Z0-9]{17}"))break;
                    // Two possible VIN cells/vehicles in a flattened row cannot establish ownership.
                    if(values.stream().filter(v->clean(v).matches("(?i)[A-HJ-NPR-Z0-9]{17}")).count()!=1)break;
                    String headerId="VH"+hash(page.number()+":"+FactLedgerService.parserFingerprint(page)+":"+line).substring(0,32);
                    Document doc=scope.documents.get(text(ev,"documentId"));
                    Map<String,Object> header=FactLedgerService.source(scope,of("id",headerId,"projectId",scope.project,"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",FactLedgerService.parserFingerprint(page),"evidence",new Evidence(doc.id(),doc.name(),page.number(),page.locationType(),line,"source-table-header",0)));
                    List<Map<String,Object>> refs=new ArrayList<>(sources);if(refs.stream().noneMatch(s->headerId.equals(s.get("id"))))refs.add(header);
                    Map<String,Object> identifier=of("field","VIN","value",vin,"valueFingerprint",hash(vin),"sourceIds",cn.bidcheck.compat.Java8Api.listOf(text(source,"id"), headerId));
                    Map<String,Object> proof=of("records",cn.bidcheck.compat.Java8Api.listOf(of("type","VEHICLE_TABLE_ROW","page",page.number(),"vinColumn",vinColumn,"fieldColumn",fieldColumn,"rowQuote",rowText,"headerQuote",line,"sourceIds",cn.bidcheck.compat.Java8Api.listOf(text(source,"id"), headerId),"parserFingerprint",FactLedgerService.parserFingerprint(page))),"validated",true);
                    matches.add(new Binding(of("id",dbKey("entity","VEHICLE:VIN:"+vin),"type","VEHICLE","label",vin,"displayName",vin,"identityStrength","STRONG","identifiers",cn.bidcheck.compat.Java8Api.listOf(identifier),"identityClaims",cn.bidcheck.compat.Java8Api.listOf(of("field","VIN","valueFingerprint",hash(vin),"sourceIds",identifier.get("sourceIds"))),"candidateName",vin,"structureProof",proof),refs));
                    break;
                }
            }
        }
        // Repeated/ambiguous row evidence remains source-local even if a model supplied a plausible recordKey.
        return matches.size()==1?Optional.of(matches.get(0)):Optional.empty();
    }
    static String canonicalField(String field){switch (clean(field).replace("**","")) {case "车牌":case "车牌号":case "车牌号码":case "号牌号码":case "plateNumber":return "plateNumber";case "VIN":case "vin":case "车架号":case "车架号码":case "车辆识别代号":case "车辆识别代码":return "vin";case "发动机号":case "发动机号码":case "engineNumber":return "engineNumber";case "车辆所有人":case "所有人":case "owner":return "owner";default:return "";}}
    private static List<String> cells(String line){
        if(!line.contains("|")&&!line.contains("｜")&&!line.contains("\t"))return cn.bidcheck.compat.Java8Api.listOf();
        String text=cn.bidcheck.compat.Java8Api.strip(line).replace('｜','|');if(text.startsWith("|"))text=text.substring(1);if(text.endsWith("|"))text=text.substring(0,text.length()-1);
        return cn.bidcheck.compat.Java8Api.toList(Arrays.stream(text.split("[|\\t]",-1)).map(cn.bidcheck.compat.Java8Api::strip));
    }
}
