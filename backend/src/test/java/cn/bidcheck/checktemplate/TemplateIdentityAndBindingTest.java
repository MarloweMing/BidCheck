package cn.bidcheck.checktemplate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateIdentityAndBindingTest {
    private final SourceRef source=new SourceRef("tender",2,"document-sha","parser-v1","source-sha","PAGE",3,"检查要求原文",0,6);

    @Test void registryIdentityCoversMetadataSchemaEvaluatorAndIsOrderIndependent() {
        TemplateMetadata material=metadata("MATERIAL_PRESENT","材料","material-present",Map.of(
                "materialType",definition("string",true),
                "requireBody",new TemplateMetadata.ParameterDefinition("boolean",false,List.of(),true)));
        TemplateMetadata price=metadata("PRICE_COMPARE","报价","price-compare",Map.of(
                "field",definition("string",true),"operator",definition("string",true),"expected",definition("decimal",true),
                "currency",definition("string",true),"unit",definition("string",true)));
        String original=new CheckTemplateRegistry(List.of(material,price)).identity();
        assertEquals(original,new CheckTemplateRegistry(List.of(price,material)).identity());

        TemplateMetadata renamed=metadata("MATERIAL_PRESENT","材料正文（修订）","material-present",material.parameters());
        CheckTemplateRegistry materialRegistry=new CheckTemplateRegistry(List.of(material));
        CheckTemplateRegistry renamedRegistry=new CheckTemplateRegistry(List.of(renamed));
        assertNotEquals(materialRegistry.identity(),renamedRegistry.identity());
        assertNotEquals(new TemplateCheckExecutor(materialRegistry).identity(),new TemplateCheckExecutor(renamedRegistry).identity());
        assertNotEquals(new LegacyTemplateBridge(materialRegistry).identity(),new LegacyTemplateBridge(renamedRegistry).identity());

        Map<String,TemplateMetadata.ParameterDefinition> revisedSchema=new java.util.LinkedHashMap<>(material.parameters());
        revisedSchema.put("newOptional",new TemplateMetadata.ParameterDefinition("string",false,List.of(),null));
        TemplateMetadata schemaChanged=metadata("MATERIAL_PRESENT","材料","material-present",revisedSchema);
        assertNotEquals(new CheckTemplateRegistry(List.of(material)).identity(),new CheckTemplateRegistry(List.of(schemaChanged)).identity());

        TemplateMetadata evaluatorChanged=metadata("MATERIAL_PRESENT","材料","material-alternative",material.parameters());
        assertNotEquals(new CheckTemplateRegistry(List.of(material)).identity(),new CheckTemplateRegistry(List.of(evaluatorChanged)).identity());
    }

    @Test void executorBridgeAndPublishedProjectionCarryTheActualRegistryIdentity() {
        CheckTemplateRegistry registry=CheckTemplateRegistry.defaultRegistry();
        TemplateCheckExecutor first=new TemplateCheckExecutor(registry);
        TemplateCheckExecutor second=new TemplateCheckExecutor(CheckTemplateRegistry.defaultRegistry());
        assertEquals(first.identity(),second.identity());
        assertTrue(first.identity().matches("[0-9a-f]{64}"));

        LegacyTemplateBridge bridge=new LegacyTemplateBridge(registry);
        assertTrue(bridge.identity().matches("[0-9a-f]{64}"));
        CheckSpec spec=materialSpec(validBindings("营业执照"));
        ResultProjection projection=first.execute(spec,EvidenceView.empty()).projection();
        assertTrue(projection.evaluatorVersion().contains(first.identity()));
        assertEquals(first.identity(),((Map<?,?>)bridge.bridge(first.execute(spec,EvidenceView.empty()),List.of()).assessment().get("templateTrace")).get("executor"));
    }

    @Test void parameterBindingsMustBeUniqueMatchTheParameterValueAndNameARealParameter() {
        CheckTemplateRegistry registry=CheckTemplateRegistry.defaultRegistry();
        CheckSpec valid=materialSpec(validBindings("营业执照"));
        assertTrue(registry.validate(valid).valid());

        List<ParameterBinding> duplicate=List.of(binding("materialType","营业执照"),binding("parameters.materialType","营业执照"),binding("requireBody",true));
        ValidationResult duplicateResult=registry.validate(materialSpec(duplicate));
        assertTrue(duplicateResult.errors().stream().anyMatch(error->"DUPLICATE_PARAMETER_BINDING".equals(error.code())));

        ValidationResult mismatch=registry.validate(materialSpec(List.of(binding("materialType","资质证书"),binding("requireBody",true))));
        assertTrue(mismatch.errors().stream().anyMatch(error->"PARAMETER_BINDING_VALUE_MISMATCH".equals(error.code())));

        List<ParameterBinding> unknown=new java.util.ArrayList<>(validBindings("营业执照"));
        unknown.add(binding("notAParameter","值"));
        ValidationResult unknownResult=registry.validate(materialSpec(unknown));
        assertTrue(unknownResult.errors().stream().anyMatch(error->"UNKNOWN_PARAMETER_BINDING".equals(error.code())));

        CheckSpec missing=materialSpec(List.of(binding("materialType","营业执照")));
        assertTrue(registry.validate(missing).errors().stream().anyMatch(error->"PARAMETER_SOURCE_REQUIRED".equals(error.code())));
        CheckSpec unknownOrigin=materialSpec(List.of(binding("materialType","营业执照"),
                new ParameterBinding("requireBody",true,"UNKNOWN","",List.of(source))));
        assertTrue(registry.validate(unknownOrigin).errors().stream().anyMatch(error->"PARAMETER_SOURCE_REQUIRED".equals(error.code())));
        CheckSpec noSource=materialSpec(List.of(binding("materialType","营业执照"),
                new ParameterBinding("requireBody",true,"TENDER_SOURCE","",List.of())));
        assertTrue(registry.validate(noSource).errors().stream().anyMatch(error->"PARAMETER_SOURCE_REQUIRED".equals(error.code())));
    }

    private TemplateMetadata metadata(String id,String title,String evaluator,Map<String,TemplateMetadata.ParameterDefinition> parameters) {
        return new TemplateMetadata(id,"1.0",title,evaluator,TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,parameters,List.of("material"));
    }

    private TemplateMetadata.ParameterDefinition definition(String type,boolean required) {
        return new TemplateMetadata.ParameterDefinition(type,required,List.of(),null);
    }

    private List<ParameterBinding> validBindings(String materialType) {
        return List.of(binding("materialType",materialType),binding("requireBody",true));
    }

    private ParameterBinding binding(String name,Object value) {
        return new ParameterBinding(name,value,"TENDER_SOURCE","由招标原文确认",List.of(source));
    }

    private CheckSpec materialSpec(List<ParameterBinding> bindings) {
        return new CheckSpec("spec-material",1,"item-material","checkpoint-material",TemplateTypes.Category.QUALIFICATION,
                TemplateTypes.Purpose.MANDATORY,TemplateTypes.VerificationMode.DOCUMENT,"MATERIAL_PRESENT","1.0",SubjectSelector.bidder(),
                Map.of("materialType","营业执照","requireBody",true),Applicability.applies(),TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                List.of(source),bindings,Map.of(),Map.of(),Map.of("state","FULL"),true,Confirmation.legacyConfirmed(),"signature");
    }
}
