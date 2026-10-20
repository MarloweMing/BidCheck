package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TemplateDependencyGraphTest {
    @Test void fragmentConnectsEvidenceConditionsResultAndBusinessItem(){TemplateExecution execution=executor().execute(spec("cp-a","material-a"),EvidenceView.empty());
        TemplateDependencyGraph.Fragment fragment=TemplateDependencyGraph.from(execution);
        assertThat(fragment.evidenceNeedKeys()).containsExactly("spec-cp-a:material");
        assertThat(fragment.nodes()).extracting(TemplateDependencyGraph.Node::type).contains(TemplateDependencyGraph.NodeType.CHECK_SPEC,TemplateDependencyGraph.NodeType.EVIDENCE_NEED,TemplateDependencyGraph.NodeType.RESULT,TemplateDependencyGraph.NodeType.BUSINESS_ITEM);
        assertThat(fragment.edges()).anyMatch(edge->edge.type()==TemplateDependencyGraph.EdgeType.REQUIRES).anyMatch(edge->edge.type()==TemplateDependencyGraph.EdgeType.PUBLISHES);
    }

    @Test void incrementalEngineRecalculatesOnlySubscribersOfChangedNeed(){TemplateCheckExecutor executor=executor();CheckSpec a=spec("cp-a","material-a"),b=spec("cp-b","material-b");
        Map<String,TemplateExecution> executions=Map.of("cp-a",executor.execute(a,EvidenceView.empty()),"cp-b",executor.execute(b,EvidenceView.empty()));
        IncrementalTemplateEngine engine=new IncrementalTemplateEngine(executor);Map<String,CheckSpec> specs=Map.of("cp-a",a,"cp-b",b);
        IncrementalTemplateEngine.Batch batch=engine.recompute(new IncrementalTemplateEngine.State(4,executions),4,Set.of("spec-cp-a:material"),specs,ignored->EvidenceView.empty());
        assertThat(batch.recalculatedCheckpointIds()).containsExactly("cp-a");assertThat(batch.retainedCheckpointIds()).containsExactly("cp-b");assertThat(batch.state().revision()).isEqualTo(5);
        assertThatThrownBy(()->engine.recompute(batch.state(),4,Set.of("spec-cp-a:material"),specs,ignored->EvidenceView.empty())).hasMessage("RESULT_REVISION_CHANGED:5");
    }

    @Test void unrelatedEvidenceIsANoOpAndKeepsRevision(){TemplateCheckExecutor executor=executor();CheckSpec spec=spec("cp-a","material-a");
        IncrementalTemplateEngine engine=new IncrementalTemplateEngine(executor);IncrementalTemplateEngine.State state=new IncrementalTemplateEngine.State(2,Map.of("cp-a",executor.execute(spec,EvidenceView.empty())));
        IncrementalTemplateEngine.Batch batch=engine.recompute(state,2,Set.of("unrelated"),Map.of("cp-a",spec),ignored->EvidenceView.empty());
        assertThat(batch.noOp()).isTrue();assertThat(batch.state().revision()).isEqualTo(2);assertThat(batch.retainedCheckpointIds()).containsExactly("cp-a");
    }

    private static TemplateCheckExecutor executor(){return new TemplateCheckExecutor(CheckTemplateRegistry.defaultRegistry());}
    private static CheckSpec spec(String checkpoint,String material){SourceRef ref=new SourceRef("tender",1,"a".repeat(64),"parser","source","PAGE",1,"应提供"+material,null,null);Map<String,Object> parameters=new LinkedHashMap<>();parameters.put("materialType",material);parameters.put("requireBody",true);
        List<ParameterBinding> bindings=parameters.entrySet().stream().map(entry->new ParameterBinding(entry.getKey(),entry.getValue(),"MANUAL_CONFIRMED","test",List.of(ref))).toList();
        return new CheckSpec("spec-"+checkpoint,1,"business-"+checkpoint,checkpoint,TemplateTypes.Category.QUALIFICATION,TemplateTypes.Purpose.MANDATORY,TemplateTypes.VerificationMode.DOCUMENT,
            "MATERIAL_PRESENT","1.0",SubjectSelector.bidder(),parameters,Applicability.applies(),TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,List.of(ref),bindings,Map.of(),Map.of(),Map.of("state","FULL","unsupportedFragments",List.of()),true,Confirmation.legacyConfirmed(),"sig-"+checkpoint);}
}
