package cn.bidcheck.verification.source;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VisualObservationPolicyTest {
    private Map<String,Object> field(String label,String value,String... notes){return new LinkedHashMap<>(Map.of("type","FIELD","label",label,"value",value,"readability","CLEAR","modelLimitations",List.of(notes)));}
    @Test void pageScopeDoesNotDeleteClearNameButCannotProveEmployment(){var a=VisualObservationPolicy.assess(field("姓名","张甲","仅依据当前图片内容识别，未见其他页或附件；无法确认该人是否实际担任该职务或文件整体有效性"));assertTrue(a.valueUsable());assertFalse(a.relationUsable());}
    @Test void unrelatedStampBlurDoesNotInvalidateCertificateNumber(){assertTrue(VisualObservationPolicy.assess(field("证书编号","A123456","发证机关印章文字部分被遮挡，完整印文未完全清晰")).valueUsable());}
    @Test void actualFieldBlurAndGlobalUnreadabilityRemainRejected(){assertFalse(VisualObservationPolicy.assess(field("证书编号","A123456","证书编号部分被遮挡")).valueUsable());assertFalse(VisualObservationPolicy.assess(field("姓名","张甲","整页图像模糊")).valueUsable());}
    @Test void mislabeledCertificateIsNotAdmittedAsRequestedCategory(){assertFalse(VisualObservationPolicy.assess(field("注册建造师执业资格证书","京建安B0001","该字段值系根据上下文推断，非注册建造师执业资格证书本身")).valueUsable());}
    @Test void unknownRestrictionIsNotSilentlyIgnored(){assertFalse(VisualObservationPolicy.assess(field("姓名","张甲","无法确定本次结果是否适用")).valueUsable());}
    @Test void typedMetadataCannotOverrideOriginalUnreadability(){var f=field("姓名","张甲","姓名模糊无法辨认");f.put("constraintAssessment",Map.of("valueUsable",true));assertFalse(VisualObservationPolicy.assess(f).valueUsable());}
    @Test void ordinarySealDoesNotNeedAuthenticityButMustBeVisible(){var f=field("印章","甲公司","无法确认印章真伪及法律效力");f.put("type","SEAL");assertTrue(VisualObservationPolicy.assess(f).valueUsable());f.put("modelLimitations",List.of("未见实际印章，无法确认真伪"));assertFalse(VisualObservationPolicy.assess(f).valueUsable());}
    @Test void wrongSignerIdentityRemainsBlocked(){var f=field("签名","张甲","无法确认签名属于投标人方签署");f.put("type","SIGNATURE");assertFalse(VisualObservationPolicy.assess(f).valueUsable());}
    @Test void finitePositiveBoundsAreRequired(){var f=field("姓名","张甲");f.put("sourceBbox",List.of(1,1,5,8));assertTrue(VisualObservationPolicy.validSourceBounds(f));f.put("sourceBbox",List.of(1,1,Double.NaN,8));assertFalse(VisualObservationPolicy.validSourceBounds(f));f.put("sourceBbox",List.of(1,1,5,8));f.put("source",Map.of("renderedWidth",4,"renderedHeight",10));assertFalse(VisualObservationPolicy.validSourceBounds(f));}
}
