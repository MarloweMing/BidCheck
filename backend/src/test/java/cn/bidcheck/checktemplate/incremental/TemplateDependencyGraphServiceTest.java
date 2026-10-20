package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.*;
import cn.bidcheck.persistence.Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:template-dependency;DB_CLOSE_DELAY=-1","bidcheck.ocr.enabled=false"})
class TemplateDependencyGraphServiceTest {
    static final Path DATA=temp();static Path temp(){try{return Files.createTempDirectory("template-dependency-");}catch(Exception failure){throw new IllegalStateException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",DATA::toString);}
    @Autowired Store store;@Autowired TemplateDependencyGraphService graphs;@Autowired CheckTemplateRegistry registry;

    @Test void publishesReverseIndexAndRejectsStalePlanRevision(){String project=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();store.save("project",project,project,"",Map.of("id",project,"name","dependency"));
        TemplateExecution execution=new TemplateCheckExecutor(registry).execute(spec(),EvidenceView.empty());Map<String,Object> graph=graphs.publish(project,run,List.of(graphs.fragment(execution)),"source-digest");
        assertThat(graph).containsEntry("schemaVersion",TemplateDependencyGraphService.SCHEMA).containsEntry("revision",1).containsEntry("sourceResultDigest","source-digest");String need=execution.evidenceNeeds().get(0).key();
        TemplateDependencyGraphService.RecalculationPlan plan=graphs.plan(run,1,List.of(need));assertThat(plan.checkpointIds()).containsExactly("checkpoint");assertThat(plan.noOp()).isFalse();
        assertThat(graphs.plan(run,1,List.of("other")).noOp()).isTrue();graphs.publish(project,run,List.of(graphs.fragment(execution)));
        assertThatThrownBy(()->graphs.plan(run,1,List.of(need))).hasMessage("DEPENDENCY_GRAPH_REVISION_CHANGED:2");
    }

    private static CheckSpec spec(){SourceRef ref=new SourceRef("tender",1,"a".repeat(64),"parser","source","PAGE",1,"应提供营业执照",null,null);Map<String,Object> parameters=new LinkedHashMap<>();parameters.put("materialType","营业执照");parameters.put("requireBody",true);List<ParameterBinding> bindings=parameters.entrySet().stream().map(entry->new ParameterBinding(entry.getKey(),entry.getValue(),"MANUAL_CONFIRMED","test",List.of(ref))).toList();
        return new CheckSpec("spec",1,"business","checkpoint",TemplateTypes.Category.QUALIFICATION,TemplateTypes.Purpose.MANDATORY,TemplateTypes.VerificationMode.DOCUMENT,"MATERIAL_PRESENT","1.0",SubjectSelector.bidder(),parameters,Applicability.applies(),TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,List.of(ref),bindings,Map.of(),Map.of(),Map.of("state","FULL","unsupportedFragments",List.of()),true,Confirmation.legacyConfirmed(),"sig");}
}
