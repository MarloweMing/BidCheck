package cn.bidcheck.verification.graph;

import cn.bidcheck.domain.Domain.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import cn.bidcheck.compat.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

import static cn.bidcheck.verification.graph.EvidenceGraph.*;

/**
 * Builds and queries a bounded evidence graph from a revalidated FactLedger snapshot.  It never
 * calls a model and never upgrades a candidate relationship to SUPPORTED without local source
 * text.  A revision id is content-addressed by document versions and fact occurrence ids, making
 * it reusable across check items and across runs.
 */
@Service
public final class EvidenceGraphService {
    public static final String VERSION="evidence-graph-1";
    private static final Pattern COMPANY=Pattern.compile(".*(?:公司|集团|事务所|合作社|合伙企业|研究院|设计院)$");
    private final EvidenceGraphRepository repository;
    private final ObjectMapper json;

    public EvidenceGraphService(EvidenceGraphRepository repository,ObjectMapper json) {
        this.repository=Objects.requireNonNull(repository);this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public BuildResult build(String projectId,String engineIdentity,List<Document> documents,
                             Map<String,Object> ledgerSnapshot) {
        return build(projectId,engineIdentity,documents,ledgerSnapshot,"");
    }

    public BuildResult build(String projectId,String engineIdentity,List<Document> documents,
                             Map<String,Object> ledgerSnapshot,String previousRevisionId) {
        required(projectId,"projectId");required(engineIdentity,"engineIdentity");
        List<DocumentRevision> manifest=manifest(projectId,documents);String manifestDigest=digest(canonical(manifest));
        List<Map<String,Object>> occurrences=cn.bidcheck.compat.Java8Api.toList(maps(ledgerSnapshot==null?null:ledgerSnapshot.get("occurrences")).stream()
                .sorted(Comparator.comparing(row->text(row,"occurrenceId"))));
        String occurrenceDigest=digest(canonical(cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(this::occurrenceIdentity))));
        String revisionId="egraph:"+digest(canonical(cn.bidcheck.compat.Java8Api.listOf(projectId, engineIdentity, manifestDigest, occurrenceDigest, VERSION))).substring(0,40);
        Optional<Snapshot> cached=repository.find(projectId,revisionId);
        if(cached.isPresent())return new BuildResult(cached.get(),true,previousRevisionId,
                changed(repository.find(projectId,previousRevisionId).orElse(null),cached.get()));

        BuildState state=new BuildState(revisionId,projectId,manifestDigest,manifest);
        // Resolve all primary strong identities first.  Relation objects may then bind to a unique
        // strong company/person key regardless of checkpoint or occurrence ordering.
        for(Map<String,Object> occurrence:occurrences)state.register(occurrence);
        for(Map<String,Object> occurrence:occurrences)state.accept(occurrence);
        List<Entity> entities=state.entities();List<Relation> relations=state.relations();
        String contentDigest=digest(canonical(cn.bidcheck.compat.Java8Api.listOf(entities, relations)));
        Revision revision=new Revision(revisionId,projectId,engineIdentity,manifestDigest,occurrenceDigest,
                VERSION,contentDigest,manifest,Instant.now().toString());
        Map<String,Object> diagnostics=cn.bidcheck.compat.Java8Api.mapOf("inputOccurrences", occurrences.size(), "acceptedOccurrences", state.acceptedOccurrences, "skippedOccurrences", state.skippedOccurrences, "entities", entities.size(), "relations", relations.size(), "supportedRelations", relations.stream().filter(r->r.status()==RelationStatus.SUPPORTED).count(), "candidateRelations", relations.stream().filter(r->r.status()==RelationStatus.CANDIDATE).count(), "builderVersion", VERSION, "modelCalls", 0);
        Snapshot proposed=new Snapshot(revision,entities,relations,diagnostics);
        Snapshot stored=repository.publish(proposed);
        return new BuildResult(stored,false,previousRevisionId,
                changed(repository.find(projectId,previousRevisionId).orElse(null),stored));
    }

    public Optional<Snapshot> find(String projectId,String revisionId){return repository.find(projectId,revisionId);}

    /** Directed proof path over source-supported edges only. */
    public ProofPath proofPath(String projectId,String revisionId,String subjectEntityKey,
                               String objectEntityKey,Set<String> allowedPredicates,int maxDepth) {
        // Business checks are current-project checks by default.  Historical edges remain
        // queryable only through the overload that names HISTORICAL_PROJECT explicitly.
        return proofPath(projectId,revisionId,subjectEntityKey,objectEntityKey,allowedPredicates,
                cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT"),maxDepth);
    }

    /** The optional scope fence prevents a historical relationship from satisfying a current-project proof. */
    public ProofPath proofPath(String projectId,String revisionId,String subjectEntityKey,
                               String objectEntityKey,Set<String> allowedPredicates,
                               Set<String> allowedScopes,int maxDepth) {
        if(maxDepth<0||maxDepth>6)throw new IllegalArgumentException("GRAPH_PATH_DEPTH_INVALID");
        Snapshot graph=repository.find(projectId,revisionId).orElseThrow(()->new IllegalArgumentException("GRAPH_REVISION_NOT_FOUND"));
        Set<String> nodes=graph.entities().stream().map(Entity::entityKey).collect(java.util.stream.Collectors.toSet());
        if(!nodes.contains(subjectEntityKey)||!nodes.contains(objectEntityKey))return new ProofPath(false,revisionId,
                subjectEntityKey,objectEntityKey,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),"ENTITY_NOT_FOUND");
        if(subjectEntityKey.equals(objectEntityKey))return new ProofPath(true,revisionId,subjectEntityKey,objectEntityKey,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),"SAME_ENTITY");
        Set<String> predicates=allowedPredicates==null?cn.bidcheck.compat.Java8Api.setOf():cn.bidcheck.compat.Java8Api.setCopyOf(allowedPredicates);
        if(!PREDICATES.containsAll(predicates))throw new IllegalArgumentException("GRAPH_PREDICATE_INVALID");
        Set<String> scopes=allowedScopes==null?cn.bidcheck.compat.Java8Api.setOf():cn.bidcheck.compat.Java8Api.setCopyOf(allowedScopes);
        if(!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "HISTORICAL_PROJECT", "UNRESOLVED").containsAll(scopes))throw new IllegalArgumentException("GRAPH_SCOPE_INVALID");
        Map<String,List<Relation>> outgoing=new HashMap<>();
        graph.relations().stream().filter(r->r.status()==RelationStatus.SUPPORTED)
                .filter(r->predicates.isEmpty()||predicates.contains(r.predicate()))
                .filter(r->scopes.isEmpty()||scopes.contains(r.scope()))
                .sorted(Comparator.comparing(Relation::id)).forEach(r->outgoing.computeIfAbsent(r.subjectEntityKey(),unused->new ArrayList<>()).add(r));
        @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    final class Step{
        private final String node;
        private final List<Relation> path;
        @com.fasterxml.jackson.annotation.JsonCreator
        Step(@com.fasterxml.jackson.annotation.JsonProperty("node") String node, @com.fasterxml.jackson.annotation.JsonProperty("path") List<Relation> path) { this.node = node; this.path = path; }
        public String node() { return node; }
        public List<Relation> path() { return path; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Step that = (Step) other; return java.util.Objects.equals(this.node, that.node) && java.util.Objects.equals(this.path, that.path); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.node); hash = 31 * hash + java.util.Objects.hashCode(this.path); return hash; }
        @Override public String toString() { return "Step[" + "node=" + node + ", path=" + path + "]"; }
}
        ArrayDeque<Step> queue=new ArrayDeque<>();queue.add(new Step(subjectEntityKey,cn.bidcheck.compat.Java8Api.listOf()));
        Set<String> seen=new HashSet<>();seen.add(subjectEntityKey);
        while(!queue.isEmpty()){
            Step current=queue.removeFirst();if(current.path().size()>=maxDepth)continue;
            for(Relation edge:outgoing.getOrDefault(current.node(),cn.bidcheck.compat.Java8Api.listOf())){
                List<Relation> path=new ArrayList<>(current.path());path.add(edge);
                if(edge.objectEntityKey().equals(objectEntityKey))return path(revisionId,subjectEntityKey,objectEntityKey,path);
                if(seen.add(edge.objectEntityKey()))queue.addLast(new Step(edge.objectEntityKey(),cn.bidcheck.compat.Java8Api.listCopyOf(path)));
            }
        }
        return new ProofPath(false,revisionId,subjectEntityKey,objectEntityKey,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),"NO_SUPPORTED_PATH");
    }

    public List<Relation> relations(String projectId,String revisionId,String subjectEntityKey,String predicate,
                                    Set<RelationStatus> statuses) {
        return relations(projectId,revisionId,subjectEntityKey,predicate,statuses,cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT"));
    }

    /** Historical or unresolved relations require an explicit scope selection. */
    public List<Relation> relations(String projectId,String revisionId,String subjectEntityKey,String predicate,
                                    Set<RelationStatus> statuses,Set<String> allowedScopes) {
        Snapshot graph=repository.find(projectId,revisionId).orElseThrow(()->new IllegalArgumentException("GRAPH_REVISION_NOT_FOUND"));
        Set<RelationStatus> accepted=statuses==null||statuses.isEmpty()?cn.bidcheck.compat.Java8Api.setOf(RelationStatus.SUPPORTED):cn.bidcheck.compat.Java8Api.setCopyOf(statuses);
        Set<String> scopes=allowedScopes==null?cn.bidcheck.compat.Java8Api.setOf():cn.bidcheck.compat.Java8Api.setCopyOf(allowedScopes);
        if(!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "HISTORICAL_PROJECT", "UNRESOLVED").containsAll(scopes))
            throw new IllegalArgumentException("GRAPH_SCOPE_INVALID");
        return cn.bidcheck.compat.Java8Api.toList(graph.relations().stream().filter(r->subjectEntityKey==null||cn.bidcheck.compat.Java8Api.isBlank(subjectEntityKey)||subjectEntityKey.equals(r.subjectEntityKey()))
                .filter(r->predicate==null||cn.bidcheck.compat.Java8Api.isBlank(predicate)||predicate.equals(r.predicate())).filter(r->accepted.contains(r.status()))
                .filter(r->scopes.isEmpty()||scopes.contains(r.scope())));
    }

    private static ProofPath path(String revision,String from,String to,List<Relation> edges) {
        LinkedHashMap<String,SourceRef> sources=new LinkedHashMap<>();
        edges.forEach(edge->edge.sources().forEach(source->sources.putIfAbsent(source.sourceFingerprint(),source)));
        return new ProofPath(true,revision,from,to,cn.bidcheck.compat.Java8Api.listCopyOf(edges),cn.bidcheck.compat.Java8Api.listCopyOf(sources.values()),"PATH_FOUND");
    }

    private Set<String> changed(Snapshot before,Snapshot after) {
        if(before!=null&&before.revision().id().equals(after.revision().id()))return cn.bidcheck.compat.Java8Api.setOf();
        Map<String,Relation> old=relationSignatures(before),now=relationSignatures(after);Set<String> signatures=new HashSet<>(old.keySet());signatures.addAll(now.keySet());
        LinkedHashSet<String> keys=new LinkedHashSet<>();for(String signature:signatures)if(!old.containsKey(signature)||!now.containsKey(signature)){
            Relation edge=now.getOrDefault(signature,old.get(signature));keys.addAll(edge.dependencyKeys());
        }
        Map<String,Entity> oldEntities=entities(before),newEntities=entities(after);Set<String> entityKeys=new HashSet<>(oldEntities.keySet());entityKeys.addAll(newEntities.keySet());
        for(String key:entityKeys)if(!Objects.equals(oldEntities.get(key),newEntities.get(key)))keys.add("graph:entity:"+key);
        return cn.bidcheck.compat.Java8Api.setCopyOf(keys);
    }
    private Map<String,Relation> relationSignatures(Snapshot snapshot){Map<String,Relation> out=new HashMap<>();if(snapshot!=null)for(Relation r:snapshot.relations())out.put(relationSignature(r),r);return out;}
    private static Map<String,Entity> entities(Snapshot snapshot){Map<String,Entity> out=new HashMap<>();if(snapshot!=null)for(Entity e:snapshot.entities())out.put(e.entityKey(),e);return out;}
    private String relationSignature(Relation r){return digest(canonical(cn.bidcheck.compat.Java8Api.listOf(r.subjectEntityKey(), r.predicate(), r.objectEntityKey(), r.sourceOccurrenceId(), r.scope(), r.lot(), r.effectiveAt(), r.status(), r.resolution(), r.attributes(), r.sources())));}

    private final class BuildState {
        final String revisionId,projectId,projectKey;final Map<String,DocumentRevision> documents=new HashMap<>();
        final Map<String,NodeDraft> nodes=new LinkedHashMap<>();final List<Relation> edges=new ArrayList<>();
        int acceptedOccurrences,skippedOccurrences;
        BuildState(String revisionId,String projectId,String manifestDigest,List<DocumentRevision> manifest){this.revisionId=revisionId;this.projectId=projectId;
            manifest.forEach(d->documents.put(d.documentId(),d));projectKey=localKey(EntityType.PROJECT,projectId,manifestDigest);node(projectKey,EntityType.PROJECT,"PROJECT_KEY",projectId,"",cn.bidcheck.compat.Java8Api.mapOf("projectId", projectId));}
        void register(Map<String,Object> occurrence){String occurrenceId=text(occurrence,"occurrenceId");Map<String,Object> fact=map(occurrence.get("fact"));if(fact.isEmpty())fact=occurrence;if(cn.bidcheck.compat.Java8Api.isBlank(occurrenceId)||sources(occurrence).isEmpty())return;entity(map(occurrence.get("entity")),fact,occurrenceId);}
        void accept(Map<String,Object> occurrence) {
            String occurrenceId=text(occurrence,"occurrenceId");Map<String,Object> fact=map(occurrence.get("fact"));
            if(fact.isEmpty())fact=occurrence;List<SourceRef> sources=sources(occurrence);
            if(cn.bidcheck.compat.Java8Api.isBlank(occurrenceId)||sources.isEmpty()){skippedOccurrences++;return;}acceptedOccurrences++;
            Map<String,Object> identity=map(occurrence.get("entity"));String kind=text(fact,"kind"),scope=scope(text(fact,"scope"));
            String subjectKey=entity(identity,fact,occurrenceId);Map<String,Object> attrs=cleanMap(map(fact.get("attributes")));
            switch (kind) {case "RELATION":direct(occurrenceId,subjectKey,fact,attrs,sources,scope); break;case "PERSON_ROLE":personnel(occurrenceId,subjectKey,fact,attrs,sources,scope); break;case "CERTIFICATE":certificate(occurrenceId,subjectKey,identity,fact,attrs,sources,scope); break;case "MONTHS":insurance(occurrenceId,subjectKey,fact,attrs,sources,scope); break;case "CONTRACT":contract(occurrenceId,subjectKey,fact,attrs,sources,scope); break;case "PERSON":case "COMPANY":case "FIELD":attributed(occurrenceId,subjectKey,fact,attrs,sources,scope); break;default:{ /* Scalar facts remain available in FactLedger; graph stores relationships only. */ } break;}
        }
        void direct(String occurrence,String subject,Map<String,Object> fact,Map<String,Object> attrs,List<SourceRef> sources,String scope){
            String original=text(fact,"field"),object=text(attrs,"object");if(cn.bidcheck.compat.Java8Api.isBlank(object))object=text(fact,"value");if(cn.bidcheck.compat.Java8Api.isBlank(original)||cn.bidcheck.compat.Java8Api.isBlank(object))return;
            String predicate=predicate(original,scope),subjectName=text(fact,"subject");EntityType objectType=objectType(predicate,object);
            String objectKey=endpoint(objectType,object,occurrence,attrs);
            if("MEMBER_OF".equals(predicate)&&subjectName.contains("联合体")&&looksCompany(object)){
                String company=objectKey;String consortium=endpoint(EntityType.CONSORTIUM,subjectName,occurrence,attrs);objectKey=consortium;subject=company;
            }
            Map<String,Object> details=new LinkedHashMap<>(attrs);details.put("originalPredicate",original);details.put("subjectText",subjectName);details.put("objectText",object);
            edge(occurrence,subject,predicate,objectKey,sources,scope,RelationStatus.SUPPORTED,"SOURCE_BOUND",details);
        }
        void personnel(String occurrence,String person,Map<String,Object> fact,Map<String,Object> attrs,List<SourceRef> sources,String scope){
            String role=text(fact,"subject"),name=text(fact,"value");if(cn.bidcheck.compat.Java8Api.isBlank(role)||cn.bidcheck.compat.Java8Api.isBlank(name))return;
            String roleKey=endpoint(EntityType.ROLE,role,occurrence,attrs);RelationStatus state=bound(sources,name,role)?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE;
            edge(occurrence,person,"SERVED_AS",roleKey,sources,scope,state,state==RelationStatus.SUPPORTED?"LOCAL_TEXT_BOUND":"LOCAL_TEXT_CANDIDATE",merge(attrs,cn.bidcheck.compat.Java8Api.mapOf("role", role, "personName", name)));
            if("CURRENT_PROJECT".equals(scope))edge(occurrence,person,"APPOINTED_TO",projectKey,sources,scope,state,state==RelationStatus.SUPPORTED?"CURRENT_APPOINTMENT_BOUND":"CURRENT_APPOINTMENT_CANDIDATE",merge(attrs,cn.bidcheck.compat.Java8Api.mapOf("role", role, "personName", name)));
            String employer=first(attrs,"employerKey","employer","employerName","companyName","所属单位");
            if(!cn.bidcheck.compat.Java8Api.isBlank(employer))edge(occurrence,person,"EMPLOYED_BY",endpoint(EntityType.COMPANY,employer,occurrence,attrs),sources,scope,
                    bound(sources,name,employer)?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE,"PERSON_EMPLOYER_BINDING",merge(attrs,cn.bidcheck.compat.Java8Api.mapOf("personName", name, "employer", employer)));
        }
        void certificate(String occurrence,String certificate,Map<String,Object> identity,Map<String,Object> fact,Map<String,Object> attrs,List<SourceRef> sources,String scope){
            Map<String,Object> holder=map(identity.get("holderIdentity"));String holderName=text(fact,"subject");String holderKey=holder.isEmpty()?localKey(EntityType.PERSON,holderName,occurrence):entity(holder,cn.bidcheck.compat.Java8Api.mapOf("subject", holderName, "kind", "PERSON", "attributes", cn.bidcheck.compat.Java8Api.mapOf("entityType", "PERSON")),occurrence);
            String number=first(attrs,"number","certificateNumber","registrationNumber"),category=first(attrs,"category","certificateType","type");
            RelationStatus status=bound(sources,holderName,cn.bidcheck.compat.Java8Api.isBlank(number)?category:number)?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE;
            Map<String,Object> details=merge(attrs,cn.bidcheck.compat.Java8Api.mapOf("holderName", holderName, "certificateNumber", number, "certificateType", category));
            edge(occurrence,holderKey,"HOLDS_CERTIFICATE",certificate,sources,scope,status,status==RelationStatus.SUPPORTED?"HOLDER_CERTIFICATE_BOUND":"HOLDER_CERTIFICATE_CANDIDATE",details);
            String registered=first(attrs,"registeredUnit","registrationUnit","registeredWith","employer","employerName");
            if(!cn.bidcheck.compat.Java8Api.isBlank(registered))edge(occurrence,certificate,"REGISTERED_WITH",endpoint(EntityType.COMPANY,registered,occurrence,attrs),sources,scope,
                    bound(sources,registered,cn.bidcheck.compat.Java8Api.isBlank(number)?category:number)?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE,"CERTIFICATE_REGISTRATION_BINDING",details);
        }
        void insurance(String occurrence,String person,Map<String,Object> fact,Map<String,Object> attrs,List<SourceRef> sources,String scope){
            String field=text(fact,"field"),employer=first(attrs,"insuredBy","socialInsuranceUnit","employer","employerName","companyName");
            if(!field.matches("(?s).*(?:社保|社会保险|缴纳月份|养老保险).*")||cn.bidcheck.compat.Java8Api.isBlank(employer))return;String name=first(attrs,"person", "personName");if(cn.bidcheck.compat.Java8Api.isBlank(name))name=text(fact,"subject");
            edge(occurrence,person,"INSURED_BY",endpoint(EntityType.COMPANY,employer,occurrence,attrs),sources,scope,
                    bound(sources,name,employer)?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE,"SOCIAL_INSURANCE_BINDING",merge(attrs,cn.bidcheck.compat.Java8Api.mapOf("personName", name, "employer", employer)));
        }
        void contract(String occurrence,String contract,Map<String,Object> fact,Map<String,Object> attrs,List<SourceRef> sources,String scope){
            String project=first(attrs,"projectName","contractProject","historicalProject");if(cn.bidcheck.compat.Java8Api.isBlank(project))project="CURRENT_PROJECT".equals(scope)?projectId:first(attrs,"name","contractName");
            if(!cn.bidcheck.compat.Java8Api.isBlank(project)){String target="CURRENT_PROJECT".equals(scope)?projectKey:endpoint(EntityType.PROJECT,project,occurrence,attrs);edge(occurrence,contract,"CONTRACT_FOR",target,sources,scope,
                    bound(sources,project,text(fact,"subject"))?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE,"CONTRACT_PROJECT_BINDING",attrs);}
            LinkedHashSet<String> parties=new LinkedHashSet<>();String party=first(attrs,"party","company","contractParty");if(!cn.bidcheck.compat.Java8Api.isBlank(party))parties.add(party);
            Object raw=attrs.get("parties");if(raw instanceof Collection<?>)((Collection<?>) (raw)).stream().filter(Objects::nonNull).map(Object::toString).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).forEach(parties::add);
            for(String value:parties)edge(occurrence,endpoint(EntityType.COMPANY,value,occurrence,attrs),"PARTY_TO",contract,sources,scope,
                    bound(sources,value,text(fact,"subject"))?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE,"CONTRACT_PARTY_BINDING",attrs);
        }
        void attributed(String occurrence,String subject,Map<String,Object> fact,Map<String,Object> attrs,List<SourceRef> sources,String scope){
            String field=text(fact,"field"),value=text(fact,"value"),name=text(fact,"subject");
            if(field.matches("(?s).*(?:所属单位|任职单位|劳动关系|聘用单位).*")&&!cn.bidcheck.compat.Java8Api.isBlank(value))edge(occurrence,subject,"EMPLOYED_BY",endpoint(EntityType.COMPANY,value,occurrence,attrs),sources,scope,
                    bound(sources,name,value)?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE,"ATTRIBUTED_EMPLOYER_BINDING",attrs);
            if(field.matches("(?s).*(?:联合体成员|成员单位).*")&&!cn.bidcheck.compat.Java8Api.isBlank(value))edge(occurrence,endpoint(EntityType.COMPANY,name,occurrence,attrs),"MEMBER_OF",endpoint(EntityType.CONSORTIUM,value,occurrence,attrs),sources,scope,
                    bound(sources,name,value)?RelationStatus.SUPPORTED:RelationStatus.CANDIDATE,"ATTRIBUTED_CONSORTIUM_BINDING",attrs);
        }
        String entity(Map<String,Object> identity,Map<String,Object> fact,String occurrence){
            String typeName=text(identity,"type"),key=text(identity,"id"),name=first(identity,"displayName","label","candidateName");if(cn.bidcheck.compat.Java8Api.isBlank(name))name=text(fact,"subject");
            EntityType type=type(typeName);if(cn.bidcheck.compat.Java8Api.isBlank(key))key=localKey(type,name,occurrence);String strength=first(identity,"identityStrength");if(cn.bidcheck.compat.Java8Api.isBlank(strength))strength="SOURCE_LOCAL";
            node(key,type,strength,name,occurrence,identity);return key;
        }
        String endpoint(EntityType type,String label,String occurrence,Map<String,Object> attrs){
            String normalized=normalize(label);List<NodeDraft> strong=cn.bidcheck.compat.Java8Api.toList(nodes.values().stream().filter(n->n.type==type&&"STRONG".equals(n.strength)&&normalize(n.label).equals(normalized)));
            if(strong.size()==1){strong.get(0).occurrences.add(occurrence);return strong.get(0).key;}
            String key=(type==EntityType.PROJECT&&projectId.equals(label))?projectKey:localKey(type,label,occurrence);node(key,type,"SOURCE_LOCAL",label,occurrence,attrs);return key;
        }
        void edge(String occurrence,String subject,String predicate,String object,List<SourceRef> sources,String scope,RelationStatus status,String resolution,Map<String,Object> attrs){
            if(cn.bidcheck.compat.Java8Api.isBlank(subject)||cn.bidcheck.compat.Java8Api.isBlank(object)||!nodes.containsKey(subject)||!nodes.containsKey(object))return;String lot=first(attrs,"lot","lotKey","section"),effective=first(attrs,"effectiveAt","referenceDate","period","asOfDate");
            String id="gedge:"+digest(canonical(cn.bidcheck.compat.Java8Api.listOf(revisionId, subject, predicate, object, occurrence, scope, lot, effective, status, resolution, attrs, sources))).substring(0,40);
            edges.add(new Relation(id,revisionId,subject,predicate,object,occurrence,sources,scope,lot,effective,status,resolution,cleanMap(attrs)));
        }
        void node(String key,EntityType type,String strength,String label,String occurrence,Map<String,Object> attrs){
            NodeDraft draft=nodes.computeIfAbsent(key,unused->new NodeDraft(key,type,strength,label));if(!cn.bidcheck.compat.Java8Api.isBlank(occurrence))draft.occurrences.add(occurrence);draft.attributes.putAll(cleanMap(attrs));
        }
        List<SourceRef> sources(Map<String,Object> occurrence){
            List<SourceRef> out=new ArrayList<>();for(Map<String,Object> source:maps(occurrence.get("sources"))){try{
                Map<String,Object> evidence=map(source.get("evidence"));String documentId=text(evidence,"documentId");DocumentRevision document=documents.get(documentId);
                int version=integer(source.get("documentVersion"),-1);String hash=text(source,"documentHash");
                if(document==null||version!=document.version()||!hash.equalsIgnoreCase(document.sha256()))continue;
                out.add(new SourceRef(text(source,"id"),text(source,"sourceFingerprint"),documentId,version,hash,
                        text(source,"parserFingerprint"),text(evidence,"locationType"),integer(evidence.get("page"),-1),
                        text(evidence,"quote"),text(evidence,"anchor"),decimal(evidence.get("confidence"))));
            }catch(IllegalArgumentException ignored){/* invalid provenance is not adopted into the graph */}}
            return cn.bidcheck.compat.Java8Api.listCopyOf(out);
        }
        List<Entity> entities(){return cn.bidcheck.compat.Java8Api.toList(nodes.values().stream().map(n->new Entity(revisionId,n.key,n.type,n.strength,n.label,cn.bidcheck.compat.Java8Api.listCopyOf(n.occurrences),cleanMap(n.attributes))).sorted(Comparator.comparing(Entity::entityKey)));}
        List<Relation> relations(){return cn.bidcheck.compat.Java8Api.toList(edges.stream().collect(java.util.stream.Collectors.toMap(Relation::id,r->r,(a,b)->a,LinkedHashMap::new)).values().stream().sorted(Comparator.comparing(Relation::id)));}
    }

    private static final class NodeDraft {final String key;EntityType type;String strength,label;final Set<String> occurrences=new LinkedHashSet<>();final Map<String,Object> attributes=new LinkedHashMap<>();NodeDraft(String key,EntityType type,String strength,String label){this.key=key;this.type=type;this.strength=strength;this.label=label;}}

    private Map<String,Object> occurrenceIdentity(Map<String,Object> row){Map<String,Object> fact=map(row.get("fact"));if(fact.isEmpty())fact=row;
        List<Map<String,Object>> sources=cn.bidcheck.compat.Java8Api.toList(maps(row.get("sources")).stream().sorted(Comparator.comparing(source->text(source,"sourceFingerprint")+"\0"+text(source,"id"))));
        return cn.bidcheck.compat.Java8Api.mapOf("occurrenceId", text(row,"occurrenceId"), "fact", ordered(fact), "entity", ordered(map(row.get("entity"))), "sources", ordered(sources));}
    private static List<DocumentRevision> manifest(String project,List<Document> documents){if(documents==null||documents.isEmpty())throw new IllegalArgumentException("GRAPH_MANIFEST_REQUIRED");Map<String,DocumentRevision> out=new LinkedHashMap<>();for(Document d:documents){if(d==null||!project.equals(d.projectId())||out.putIfAbsent(d.id(),new DocumentRevision(d.id(),d.version(),d.sha256()))!=null)throw new IllegalArgumentException("GRAPH_MANIFEST_INVALID");}return cn.bidcheck.compat.Java8Api.toList(out.values().stream().sorted(Comparator.comparing(DocumentRevision::documentId)));}
    private static String predicate(String field,String scope){String v=normalize(field);if(v.matches(".*(?:联合体成员|成员单位|成员关系).*") )return "MEMBER_OF";if(v.contains("牵头"))return "LEADS";if(v.matches(".*(?:职责|负责|分工).*") )return "RESPONSIBLE_FOR";if(v.matches(".*(?:所属单位|任职单位|劳动关系|聘用单位).*") )return "EMPLOYED_BY";if(v.contains("注册单位"))return "REGISTERED_WITH";if(v.matches(".*(?:社保|社会保险|缴纳单位).*") )return "INSURED_BY";if(v.matches(".*(?:持有证书|资格证书|证书持有人).*") )return "HOLDS_CERTIFICATE";if(v.matches(".*(?:合同当事人|合同甲方|合同乙方|发包人|承包人).*") )return "PARTY_TO";if(v.matches(".*(?:任职|拟任|岗位|职务).*") )return "CURRENT_PROJECT".equals(scope)?"APPOINTED_TO":"SERVED_AS";if(v.matches(".*(?:投标人|投标单位).*") )return "BIDDER_FOR";return "RELATED_TO";}
    private static EntityType objectType(String predicate,String value){switch (predicate) {case "MEMBER_OF":case "LEADS":return EntityType.CONSORTIUM;case "RESPONSIBLE_FOR":return EntityType.RESPONSIBILITY;case "APPOINTED_TO":case "BIDDER_FOR":case "CONTRACT_FOR":return EntityType.PROJECT;case "EMPLOYED_BY":case "REGISTERED_WITH":case "INSURED_BY":return EntityType.COMPANY;case "HOLDS_CERTIFICATE":return EntityType.CERTIFICATE;case "PARTY_TO":return EntityType.CONTRACT;case "SERVED_AS":return EntityType.ROLE;default:return looksCompany(value)?EntityType.COMPANY:EntityType.UNKNOWN;}}
    private static EntityType type(String raw){try{return EntityType.valueOf(raw);}catch(Exception ignored){return EntityType.UNKNOWN;}}
    private static boolean looksCompany(String value){return COMPANY.matcher(value==null?"":cn.bidcheck.compat.Java8Api.strip(value)).matches();}
    private static boolean bound(List<SourceRef> sources,String left,String right){if(left==null||right==null||cn.bidcheck.compat.Java8Api.isBlank(left)||cn.bidcheck.compat.Java8Api.isBlank(right))return false;String a=normalize(left),b=normalize(right);return sources.stream().map(SourceRef::quote).map(EvidenceGraphService::normalize).anyMatch(q->{int x=q.indexOf(a),y=q.indexOf(b);return x>=0&&y>=0&&Math.abs(x-y)<=320;});}
    private static String scope(String value){return cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "HISTORICAL_PROJECT", "UNRESOLVED").contains(value)?value:"UNRESOLVED";}
    private static String localKey(EntityType type,String label,String discriminator){return "gentity:"+digest(type+"\0"+normalize(label)+"\0"+discriminator).substring(0,40);}
    private static String first(Map<String,Object> map,String... keys){for(String key:keys){String value=cn.bidcheck.compat.Java8Api.strip(Objects.toString(map.get(key),""));if(!cn.bidcheck.compat.Java8Api.isBlank(value))return value;}return "";}
    private static Map<String,Object> merge(Map<String,Object> left,Map<String,Object> right){Map<String,Object> out=new LinkedHashMap<>(cleanMap(left));out.putAll(cleanMap(right));return out;}
    private static Map<String,Object> cleanMap(Map<String,Object> value){Map<String,Object> out=new LinkedHashMap<>();value.forEach((k,v)->{if(k!=null&&v!=null)out.put(k,v);});return java.util.Collections.unmodifiableMap(out);}
    private static Object ordered(Object value){if(value instanceof Map<?,?>){Map<String,Object> out=new TreeMap<>();((Map<?,?>) (value)).forEach((k,v)->out.put(String.valueOf(k),ordered(v)));return out;}if(value instanceof Collection<?>)return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(EvidenceGraphService::ordered));return value;}
    private String canonical(Object value){try{return json.writeValueAsString(ordered(value));}catch(Exception failure){throw new IllegalArgumentException("GRAPH_VALUE_INVALID",failure);}}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String normalize(String value){return value==null?"":value.replaceAll("[\\s，,。；;：:（）()【】\\[\\]·・]","").toUpperCase(Locale.ROOT);}
    private static String required(String value,String field){if(value==null||cn.bidcheck.compat.Java8Api.isBlank(value))throw new IllegalArgumentException("GRAPH_"+field.toUpperCase()+"_REQUIRED");return cn.bidcheck.compat.Java8Api.strip(value);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))return cn.bidcheck.compat.Java8Api.mapOf();Map<String,Object> out=new LinkedHashMap<>();((Map<?,?>) (value)).forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    private static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(Map.class::isInstance).map(EvidenceGraphService::map)):cn.bidcheck.compat.Java8Api.listOf();}
    private static String text(Map<String,Object> map,String key){return cn.bidcheck.compat.Java8Api.strip(Objects.toString(map.get(key),""));}
    private static int integer(Object value,int fallback){try{return value instanceof Number?((Number) (value)).intValue():Integer.parseInt(Objects.toString(value,""));}catch(Exception ignored){return fallback;}}
    private static double decimal(Object value){try{double result=value instanceof Number?((Number) (value)).doubleValue():Double.parseDouble(Objects.toString(value,""));return Double.isFinite(result)?result:0;}catch(Exception ignored){return 0;}}
}
