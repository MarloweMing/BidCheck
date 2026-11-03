package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Refresh generated requirements without discarding project edits, deletions or run snapshots. */
final class CheckpointReconciler {
    private final Store store;
    CheckpointReconciler(Store store) { this.store=store; }

    /** The caller holds the project lock and transaction. */
    Outcome reconcile(String project, String tender, List<Checkpoint> candidates, String version) {
        Set<String> warnings=new LinkedHashSet<>();
        Map<String,Checkpoint> initial=new LinkedHashMap<>();
        Set<String> manual=new HashSet<>();
        // Legacy installations have no extraction baseline. The first edit's before-image is it.
        for (Map<String,Object> audit:store.maps("audit",project,null)) {
            String action=BidService.text(audit,"action");
            if ("CHECKPOINT_CREATED".equals(action) && audit.get("after")!=null) {
                Checkpoint c=decode(audit.get("after")); manual.add(c.id());
            }
            if ("CHECKPOINT_UPDATED".equals(action) && audit.get("before")!=null) {
                Checkpoint c=decode(audit.get("before")); initial.putIfAbsent(c.id(),c);
            }
        }
        List<Entry> existing=new ArrayList<>();
        for (String kind:cn.bidcheck.compat.Java8Api.listOf("checkpoint", "deleted_checkpoint")) {
            for (Checkpoint current:store.list(kind,project,tender,Checkpoint.class)) {
                Stored baseline=store.mapper().get("checkpoint-baseline:"+current.id());
                Checkpoint original=baseline==null?initial.getOrDefault(current.id(),current):store.decode(baseline.body,Checkpoint.class);
                existing.add(new Entry(current,original,"deleted_checkpoint".equals(kind),manual.contains(current.id())));
            }
        }
        List<Checkpoint> fresh=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for (Checkpoint c:candidates) if (seen.add(definition(c))) fresh.add(c);
        Set<String> used=new HashSet<>();
        for (Checkpoint candidate:fresh) {
            String shape=definition(candidate), business=businessDefinition(candidate);
            List<Entry> exact=cn.bidcheck.compat.Java8Api.toList(existing.stream().filter(e->!e.manual&&!used.contains(e.current.id())&&definition(e.original).equals(shape)));
            Entry match=exact.size()==1?exact.get(0):null;
            // Line anchors and quote extents may be corrected without changing the obligation.
            // Only one-to-one business matches may bridge such a source migration.
            List<Entry> sameBusiness=cn.bidcheck.compat.Java8Api.toList(existing.stream().filter(e->!e.manual&&businessDefinition(e.original).equals(business)));
            if(match==null&&sameBusiness.size()==1&&fresh.stream().filter(c->businessDefinition(c).equals(business)).count()==1
                    &&!used.contains(sameBusiness.get(0).current.id()))match=sameBusiness.get(0);
            List<Entry> allRelated=cn.bidcheck.compat.Java8Api.toList(existing.stream().filter(e->!e.manual&&relatedSource(e.original,candidate)));
            Boolean inheritedEnabled=null;
            if(match==null&&allRelated.size()==1) {
                Entry old=allRelated.get(0);
                long freshRelated=fresh.stream().filter(c->relatedSource(old.original,c)).count();
                if(freshRelated==1&&!used.contains(old.current.id()))match=old;
                else if(freshRelated>1) {
                    if(old.deleted)continue;
                    if(!businessDefinition(old.current).equals(businessDefinition(old.original))) {
                        warnings.add("检查项“"+old.current.title()+"”的新规则已拆分，已保留人工修改；请对照原文核对是否需要手动增补。");
                        continue;
                    }
                    if(old.current.enabled()!=old.original.enabled())inheritedEnabled=old.current.enabled();
                }
            }
            if(match==null&&(allRelated.size()>1||sameBusiness.size()>1)) {
                boolean protectedMatch=java.util.stream.Stream.concat(allRelated.stream(),sameBusiness.stream())
                        .anyMatch(e->e.deleted||!businessDefinition(e.current).equals(businessDefinition(e.original)));
                if(protectedMatch) {
                    warnings.add("检查项“"+candidate.title()+"”与多条人工修改或删除记录的对应关系不唯一，已保留人工记录；请对照原文手动核对增补。");
                    continue;
                }
            }
            // A removed combined requirement must also stay removed if new rules split it.
            if (match==null && allRelated.stream().anyMatch(e->e.deleted)) continue;
            if (match!=null) {
                used.add(match.current.id());
                if (match.deleted) { saveBaseline(project,tender,match.current.id(),candidate);continue; }
                boolean edited=!businessDefinition(match.current).equals(businessDefinition(match.original));
                boolean unchanged=business.equals(businessDefinition(match.original));
                boolean enabled=match.current.enabled()!=match.original.enabled()?match.current.enabled():candidate.enabled();
                Checkpoint next=edited||unchanged?withSource(match.current,candidate.source()):copy(candidate,match.current.id(),false,enabled);
                if(edited&&!unchanged) {
                    next=copy(next,next.id(),false,next.enabled());
                    String requirement=Objects.toString(candidate.expected(),"");
                    if(requirement.length()>500)requirement=requirement.substring(0,500)+"…（完整要求请对照招标原文）";
                    warnings.add("检查项“"+match.current.title()+"”的新提取要求已变化，已保留人工修改并取消确认，请重新核对。新提取要求："+requirement);
                }
                store.save("checkpoint",next.id(),project,tender,next);
                saveBaseline(project,tender,next.id(),candidate);
            } else {
                String id=UUID.nameUUIDFromBytes(("checkpoint:"+tender+":"+shape).getBytes(StandardCharsets.UTF_8)).toString();
                Stored prior=store.mapper().get(id);
                if (prior!=null&&"deleted_checkpoint".equals(prior.kind)) continue;
                Checkpoint next=copy(candidate,id,false,inheritedEnabled==null?candidate.enabled():inheritedEnabled);
                store.save("checkpoint",id,project,tender,next);
                saveBaseline(project,tender,id,candidate);
            }
        }
        for (Entry entry:existing) {
            if (entry.deleted||entry.manual||used.contains(entry.current.id())) continue;
            if (!businessDefinition(entry.current).equals(businessDefinition(entry.original))) continue;
            // Preserve superseded automatic rows for audit; frozen snapshots remain untouched.
            store.save("superseded_checkpoint",entry.current.id(),project,tender,entry.current);
        }
        int count=store.list("checkpoint",project,tender,Checkpoint.class).size();
        String audit=Store.id();
        store.save("audit",audit,project,tender,BidService.fields("id",audit,"action","CHECKPOINTS_EXTRACTED",
                "ruleVersion",version,"candidateCount",fresh.size(),"activeCount",count,"createdAt",BidService.now()));
        return new Outcome(count,cn.bidcheck.compat.Java8Api.listCopyOf(warnings));
    }

    private Checkpoint decode(Object value) { return store.decode(store.encode(value),Checkpoint.class); }
    private void saveBaseline(String project,String tender,String id,Checkpoint candidate) {
        store.save("checkpoint_baseline","checkpoint-baseline:"+id,project,tender,copy(candidate,id,false));
    }
    private String definition(Checkpoint c) { return store.encode(copy(c,"",false,true)); }
    private String businessDefinition(Checkpoint c) { return store.encode(withSource(copy(c,"",false,true),null)); }
    private static boolean relatedSource(Checkpoint a,Checkpoint b) {
        if(!Objects.equals(a.type(),b.type())||!Objects.equals(a.field(),b.field()))return false;
        if(lineage(a).equals(lineage(b)))return true;
        Evidence left=a.source(),right=b.source();
        if(left==null||right==null||!Objects.equals(left.documentId(),right.documentId())||left.page()!=right.page()
                ||!Objects.equals(left.locationType(),right.locationType()))return false;
        String l=Objects.toString(left.quote(),"").replaceAll("\\s+","");
        String r=Objects.toString(right.quote(),"").replaceAll("\\s+","");
        // Containment is verifiable against the same source page; avoid fuzzy text similarity.
        return Math.min(l.length(),r.length())>=8&&(l.contains(r)||r.contains(l));
    }
    private static Checkpoint withSource(Checkpoint c,Evidence source) {
        return new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),
                c.expected(),c.unit(),c.required(),c.confirmed(),c.enabled(),source);
    }
    private static String lineage(Checkpoint c) {
        Evidence source=c.source();
        String position=source==null?"generated":source.documentId()+":"+source.locationType()+":"+source.page()+":"+
                (source.anchor()==null||cn.bidcheck.compat.Java8Api.isBlank(source.anchor())?source.quote():source.anchor());
        return position+":"+c.type()+":"+c.field();
    }
    private static Checkpoint copy(Checkpoint c,String id,boolean confirmed) {
        return copy(c,id,confirmed,c.enabled());
    }
    private static Checkpoint copy(Checkpoint c,String id,boolean confirmed,boolean enabled) {
        return new Checkpoint(id,c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),
                c.expected(),c.unit(),c.required(),confirmed,enabled,c.source());
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Outcome {
        private final int count;
        private final List<String> warnings;
        @com.fasterxml.jackson.annotation.JsonCreator
        Outcome(@com.fasterxml.jackson.annotation.JsonProperty("count") int count, @com.fasterxml.jackson.annotation.JsonProperty("warnings") List<String> warnings) { this.count = count; this.warnings = warnings; }
        public int count() { return count; }
        public List<String> warnings() { return warnings; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Outcome that = (Outcome) other; return java.util.Objects.equals(this.count, that.count) && java.util.Objects.equals(this.warnings, that.warnings); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.count); hash = 31 * hash + java.util.Objects.hashCode(this.warnings); return hash; }
        @Override public String toString() { return "Outcome[" + "count=" + count + ", warnings=" + warnings + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Entry {
        private final Checkpoint current;
        private final Checkpoint original;
        private final boolean deleted;
        private final boolean manual;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Entry(@com.fasterxml.jackson.annotation.JsonProperty("current") Checkpoint current, @com.fasterxml.jackson.annotation.JsonProperty("original") Checkpoint original, @com.fasterxml.jackson.annotation.JsonProperty("deleted") boolean deleted, @com.fasterxml.jackson.annotation.JsonProperty("manual") boolean manual) { this.current = current; this.original = original; this.deleted = deleted; this.manual = manual; }
        public Checkpoint current() { return current; }
        public Checkpoint original() { return original; }
        public boolean deleted() { return deleted; }
        public boolean manual() { return manual; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Entry that = (Entry) other; return java.util.Objects.equals(this.current, that.current) && java.util.Objects.equals(this.original, that.original) && java.util.Objects.equals(this.deleted, that.deleted) && java.util.Objects.equals(this.manual, that.manual); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.current); hash = 31 * hash + java.util.Objects.hashCode(this.original); hash = 31 * hash + java.util.Objects.hashCode(this.deleted); hash = 31 * hash + java.util.Objects.hashCode(this.manual); return hash; }
        @Override public String toString() { return "Entry[" + "current=" + current + ", original=" + original + ", deleted=" + deleted + ", manual=" + manual + "]"; }
}
}
