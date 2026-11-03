package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckpointReconcilerTest {
    private final Map<String,Stored> rows=new LinkedHashMap<>();
    private final Store store;
    private final CheckpointReconciler reconciler;
    CheckpointReconcilerTest() {
        RecordMapper mapper=mock(RecordMapper.class);
        when(mapper.get(anyString())).thenAnswer(call->rows.get(call.getArgument(0)));
        doAnswer(call->{Stored row=call.getArgument(0);rows.put(row.id,row);return null;}).when(mapper).save(any());
        when(mapper.list(anyString(),nullable(String.class),nullable(String.class))).thenAnswer(call->cn.bidcheck.compat.Java8Api.toList(rows.values().stream()
                .filter(r->r.kind.equals(call.getArgument(0)))
                .filter(r->call.getArgument(1)==null||r.projectId.equals(call.getArgument(1)))
                .filter(r->call.getArgument(2)==null||r.parentId.equals(call.getArgument(2)))));
        store=new Store(mapper,new ObjectMapper());reconciler=new CheckpointReconciler(store);
    }
    @Test void reorderDoesNotDuplicateAndUnchangedConfirmationSurvives() {
        cn.bidcheck.domain.Domain.Checkpoint a=check("a","执照复印件","license",1,false,true);
        cn.bidcheck.domain.Domain.Checkpoint b=check("b","资质证书","qualification",2,false,true);
        refresh(cn.bidcheck.compat.Java8Api.listOf(a, b));
        cn.bidcheck.domain.Domain.Checkpoint first=active().get(0);save(copy(first,first.expected(),true,true));
        refresh(cn.bidcheck.compat.Java8Api.listOf(b, a));
        assertEquals(2,active().size());
        assertTrue(store.get("checkpoint",first.id(),Checkpoint.class).confirmed());
        assertEquals(2,store.list("checkpoint_baseline","p","t",Checkpoint.class).size());
    }
    @Test void newerDefinitionReplacesUneditedCandidateAndRequiresConfirmationWithoutChangingSnapshot() {
        cn.bidcheck.domain.Domain.Checkpoint old=check("legacy","提供人员方案","personnel",3,true,true);save(old);
        store.save("snapshot","snapshot:p:legacy","p","run",old);
        refresh(cn.bidcheck.compat.Java8Api.listOf(check("new","提供人员方案，内容完整、合理、风险分析全面、可行性强","personnel",3,false,true)));
        cn.bidcheck.domain.Domain.Checkpoint updated=active().get(0);
        assertEquals("legacy",updated.id());assertFalse(updated.confirmed());
        assertTrue(updated.expected().contains("风险分析全面"));
        assertEquals(old,store.get("snapshot","snapshot:p:legacy",Checkpoint.class));
    }
    @Test void editsDisabledItemsManualAdditionsAndDeletedItemsSurviveUpgrade() {
        cn.bidcheck.domain.Domain.Checkpoint edited=check("edited","用户核定执照要求","license",1,true,true);
        cn.bidcheck.domain.Domain.Checkpoint before=copy(edited,"旧执照要求",false,true);
        save(edited);
        store.save("audit","edit-audit","p","edited",cn.bidcheck.compat.Java8Api.mapOf("action", "CHECKPOINT_UPDATED", "before", before, "after", edited));
        cn.bidcheck.domain.Domain.Checkpoint disabled=check("disabled","用户停用的资质","qualification",2,true,false);save(disabled);
        store.save("checkpoint_baseline","checkpoint-baseline:disabled","p","t",copy(disabled,disabled.expected(),false,true));
        cn.bidcheck.domain.Domain.Checkpoint deleted=check("deleted","已删除的响应要求","response",4,true,true);
        store.save("deleted_checkpoint",deleted.id(),"p","t",deleted);
        cn.bidcheck.domain.Domain.Checkpoint manual=new Checkpoint("manual","手工新增","响应检查","MEDIUM","SEMANTIC_RESPONSE","manual","RESPONDS","自定义要求","",true,true,true,null);save(manual);
        store.save("audit","manual-audit","p","manual",cn.bidcheck.compat.Java8Api.mapOf("action", "CHECKPOINT_CREATED", "after", manual));
        refresh(cn.bidcheck.compat.Java8Api.listOf(check("new","新执照要求","license",1,false,true), check("new","新资质要求","qualification",2,false,true), check("new","新版已删除的响应要求","response",4,false,true)));
        assertEquals(3,active().size());
        assertEquals(copy(edited,edited.expected(),false,edited.enabled()),store.get("checkpoint","edited",Checkpoint.class));
        cn.bidcheck.domain.Domain.Checkpoint updatedDisabled=store.get("checkpoint","disabled",Checkpoint.class);
        assertEquals("新资质要求",updatedDisabled.expected());assertFalse(updatedDisabled.enabled());assertFalse(updatedDisabled.confirmed());
        assertEquals(manual,store.get("checkpoint","manual",Checkpoint.class));
        assertEquals("deleted_checkpoint",rows.get("deleted").kind);
        refresh(cn.bidcheck.compat.Java8Api.listOf(check("new","新执照要求","license",1,false,true), check("new","新资质要求","qualification",2,false,true)));
        assertEquals(3,active().size());
    }
    @Test void supersededAutomaticRowsAreArchivedAndDoNotRemainActive() {
        cn.bidcheck.domain.Domain.Checkpoint old=check("obsolete","非检查章节被错误提取","unrelated",8,true,true);save(old);
        refresh(cn.bidcheck.compat.Java8Api.listOf(check("new","正确要求","license",1,false,true)));
        assertEquals(1,active().size());assertEquals("superseded_checkpoint",rows.get("obsolete").kind);
        assertFalse(active().get(0).confirmed());
    }
    @Test void deletingACombinedRequirementDoesNotResurrectItWhenExtractionSplitsIt() {
        cn.bidcheck.domain.Domain.Checkpoint old=check("deleted","证书甲和证书乙","certificate",4,true,true);
        store.save("deleted_checkpoint",old.id(),"p","t",old);
        refresh(cn.bidcheck.compat.Java8Api.listOf(check("new1","证书甲","certificate",4,false,true), check("new2","证书乙","certificate",4,false,true)));
        assertTrue(active().isEmpty());
    }
    @Test void splittingEditedRequirementPreservesUserDefinitionWithVisibleWarning() {
        cn.bidcheck.domain.Domain.Checkpoint old=check("edited","用户核定组合要求","certificate",4,true,true);save(old);
        store.save("checkpoint_baseline","checkpoint-baseline:edited","p","t",copy(old,"旧组合要求",false,true));
        cn.bidcheck.service.CheckpointReconciler.Outcome outcome=reconciler.reconcile("p","t",cn.bidcheck.compat.Java8Api.listOf(check("new1","证书甲","certificate",4,false,true), check("new2","证书乙","certificate",4,false,true)),"new-rules");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(old),active());assertEquals(1,outcome.warnings().size());
        assertTrue(outcome.warnings().get(0).contains("已保留人工修改"));
    }
    @Test void splittingDisabledAutomaticRequirementKeepsDisabledChoiceOnAllParts() {
        cn.bidcheck.domain.Domain.Checkpoint old=check("disabled","证书甲和证书乙","certificate",4,true,false);save(old);
        store.save("checkpoint_baseline","checkpoint-baseline:disabled","p","t",copy(old,old.expected(),false,true));
        refresh(cn.bidcheck.compat.Java8Api.listOf(check("new1","证书甲","certificate",4,false,true), check("new2","证书乙","certificate",4,false,true)));
        assertEquals(2,active().size());assertTrue(active().stream().noneMatch(Checkpoint::enabled));
        assertTrue(active().stream().noneMatch(Checkpoint::confirmed));
    }
    @Test void correctedPhysicalAnchorAndExpandedQuoteKeepConfirmationForTheSameBusinessRule() {
        cn.bidcheck.domain.Domain.Checkpoint old=at(check("confirmed","提供有效营业执照复印件","license",2,true,true),2,"须提供有效营业执照复印件。");save(old);
        cn.bidcheck.domain.Domain.Checkpoint fresh=at(copy(old,old.expected(),false,true),4,"资格要求：须提供有效营业执照复印件。并加盖单位公章。");
        refresh(cn.bidcheck.compat.Java8Api.listOf(fresh));
        assertEquals(1,active().size());assertEquals("confirmed",active().get(0).id());
        assertTrue(active().get(0).confirmed());assertEquals("p1-l4",active().get(0).source().anchor());
        assertEquals(fresh.source().quote(),active().get(0).source().quote());
    }
    @Test void sourceMigrationKeepsManualDefinitionAndDisabledChoiceButCorrectsItsEvidence() {
        cn.bidcheck.domain.Domain.Checkpoint original=at(check("edited","提供有效营业执照复印件","license",2,false,true),2,"须提供有效营业执照复印件。");
        cn.bidcheck.domain.Domain.Checkpoint edited=copy(original,"用户核定：提供营业执照彩色扫描件",true,false);save(edited);
        store.save("audit","edit-source-audit","p",edited.id(),cn.bidcheck.compat.Java8Api.mapOf("action", "CHECKPOINT_UPDATED", "before", original, "after", edited));
        store.save("snapshot","snapshot:edited","p","run",edited);
        cn.bidcheck.domain.Domain.Checkpoint fresh=at(original,4,"资格要求：须提供有效营业执照复印件。并加盖单位公章。");
        refresh(cn.bidcheck.compat.Java8Api.listOf(fresh));refresh(cn.bidcheck.compat.Java8Api.listOf(fresh));
        assertEquals(1,active().size());cn.bidcheck.domain.Domain.Checkpoint actual=active().get(0);
        assertEquals(edited.expected(),actual.expected());assertEquals(edited.title(),actual.title());
        assertFalse(actual.enabled());assertTrue(actual.confirmed());assertEquals(fresh.source(),actual.source());
        assertEquals(edited,store.get("snapshot","snapshot:edited",Checkpoint.class));
    }
    @Test void sourceMigrationDoesNotResurrectDeletedRulesAndAdvancesTheirBaseline() {
        cn.bidcheck.domain.Domain.Checkpoint old=at(check("deleted","提供有效营业执照复印件","license",2,true,true),2,"须提供有效营业执照复印件。");
        store.save("deleted_checkpoint",old.id(),"p","t",old);
        cn.bidcheck.domain.Domain.Checkpoint fresh=at(copy(old,old.expected(),false,true),4,"资格要求：须提供有效营业执照复印件。并加盖单位公章。");
        refresh(cn.bidcheck.compat.Java8Api.listOf(fresh));refresh(cn.bidcheck.compat.Java8Api.listOf(fresh));
        assertTrue(active().isEmpty());assertEquals("deleted_checkpoint",rows.get(old.id()).kind);
        assertEquals(fresh.source(),store.get("checkpoint_baseline","checkpoint-baseline:"+old.id(),Checkpoint.class).source());
    }
    @Test void changedRuleUsesUniqueVerifiedQuoteOverlapAndRequiresConfirmation() {
        cn.bidcheck.domain.Domain.Checkpoint original=at(check("old","提供营业执照复印件","license",2,false,true),2,"须提供有效营业执照复印件。");
        save(copy(original,original.expected(),true,false));
        store.save("checkpoint_baseline","checkpoint-baseline:old","p","t",original);
        cn.bidcheck.domain.Domain.Checkpoint fresh=at(copy(original,"提供营业执照复印件，并加盖公章",false,true),4,"资格要求：须提供有效营业执照复印件。并加盖单位公章。");
        refresh(cn.bidcheck.compat.Java8Api.listOf(fresh));cn.bidcheck.domain.Domain.Checkpoint actual=active().get(0);
        assertEquals("old",actual.id());assertEquals(fresh.expected(),actual.expected());
        assertFalse(actual.confirmed());assertFalse(actual.enabled());assertEquals(fresh.source(),actual.source());
    }
    @Test void materiallyChangedExtractedRuleKeepsManualContentButRequiresReviewAndLeavesSnapshotIntact() {
        cn.bidcheck.domain.Domain.Checkpoint original=at(check("edited","提供营业执照复印件","license",2,false,true),2,"须提供有效营业执照复印件。");
        cn.bidcheck.domain.Domain.Checkpoint edited=copy(original,"用户核定：提供营业执照彩色扫描件",true,false);save(edited);
        store.save("checkpoint_baseline","checkpoint-baseline:edited","p","t",original);
        store.save("snapshot","snapshot:changed-manual","p","run",edited);
        cn.bidcheck.domain.Domain.Checkpoint fresh=at(copy(original,"提供营业执照复印件，并加盖单位公章",false,true),4,"资格要求：须提供有效营业执照复印件。并加盖单位公章。");
        cn.bidcheck.service.CheckpointReconciler.Outcome outcome=reconciler.reconcile("p","t",cn.bidcheck.compat.Java8Api.listOf(fresh),"new-rules");
        cn.bidcheck.domain.Domain.Checkpoint actual=store.get("checkpoint","edited",Checkpoint.class);
        assertEquals(edited.title(),actual.title());assertEquals(edited.expected(),actual.expected());
        assertFalse(actual.enabled());assertFalse(actual.confirmed());assertEquals(fresh.source(),actual.source());
        assertEquals(1,outcome.warnings().size());
        assertTrue(outcome.warnings().get(0).contains("已保留人工修改并取消确认"));
        assertTrue(outcome.warnings().get(0).contains(fresh.expected()));
        assertEquals(edited,store.get("snapshot","snapshot:changed-manual",Checkpoint.class));
        refresh(cn.bidcheck.compat.Java8Api.listOf(fresh));
        assertFalse(store.get("checkpoint","edited",Checkpoint.class).confirmed());
        assertEquals(1,active().size());
    }
    @Test void sameBusinessAtTwoLocationsIsMatchedByUniqueQuoteOverlapWithoutCrossingDeletion() {
        cn.bidcheck.domain.Domain.Checkpoint first=at(check("deleted","提供有效营业执照复印件","license",2,true,true),2,"甲单位须提供有效营业执照复印件。");
        cn.bidcheck.domain.Domain.Checkpoint second=at(check("confirmed","提供有效营业执照复印件","license",3,true,true),3,"乙单位须提供有效营业执照复印件。");
        store.save("deleted_checkpoint",first.id(),"p","t",first);save(second);
        cn.bidcheck.domain.Domain.Checkpoint a=at(first,4,"注册资料：甲单位须提供有效营业执照复印件。原件核查。");
        cn.bidcheck.domain.Domain.Checkpoint b=at(second,6,"注册资料：乙单位须提供有效营业执照复印件。原件核查。");
        refresh(cn.bidcheck.compat.Java8Api.listOf(b, a));
        assertEquals(1,active().size());assertEquals("confirmed",active().get(0).id());
        assertTrue(active().get(0).confirmed());assertEquals("p1-l6",active().get(0).source().anchor());
    }
    @Test void sourceExpansionWithMultipleDifferentChildrenDoesNotReplaceAManualRuleArbitrarily() {
        cn.bidcheck.domain.Domain.Checkpoint old=at(check("edited","提供证书甲和证书乙","certificate",2,false,true),2,"须提供有效证书甲和有效证书乙。");
        cn.bidcheck.domain.Domain.Checkpoint edited=copy(old,"用户核定组合要求",true,true);save(edited);
        store.save("checkpoint_baseline","checkpoint-baseline:edited","p","t",old);
        String quote="认证资料：须提供有效证书甲和有效证书乙。提供原件供核验。";
        cn.bidcheck.service.CheckpointReconciler.Outcome result=reconciler.reconcile("p","t",cn.bidcheck.compat.Java8Api.listOf(at(copy(old,"有效证书甲",false,true),4,quote), at(copy(old,"有效证书乙",false,true),4,quote)),"new-rules");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(edited),active());assertFalse(result.warnings().isEmpty());
    }
    private void refresh(List<Checkpoint> candidates) { reconciler.reconcile("p","t",candidates,"new-rules"); }
    private void save(Checkpoint c) {store.save("checkpoint",c.id(),"p","t",c);}
    private List<Checkpoint> active() {return store.list("checkpoint","p","t",Checkpoint.class);}
    private static Checkpoint check(String id,String expected,String field,int line,boolean confirmed,boolean enabled) {
        return new Checkpoint(id,field,"资格检查","HIGH","MATERIAL_EXISTENCE",field,"EXISTS",expected,"",true,confirmed,enabled,
                new Evidence("t","招标.pdf",1,"PAGE","原始要求第"+line+"条","p1-l"+line,1d));
    }
    private static Checkpoint copy(Checkpoint c,String expected,boolean confirmed,boolean enabled) {
        return new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),expected,c.unit(),c.required(),confirmed,enabled,c.source());
    }
    private static Checkpoint at(Checkpoint c,int line,String quote) {
        return new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),c.expected(),c.unit(),c.required(),c.confirmed(),c.enabled(),
                new Evidence("t","招标.pdf",1,"PAGE",quote,"p1-l"+line,1d));
    }
}
