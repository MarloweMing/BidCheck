package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.ContractRecordAnchorTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class AmountSourceBindingTest {
    @Test void enumTaxBasisIsOnlyAnAliasForThePrintedQualification(){
        assertTrue(ProofVerifier.taxBound("含税协议金额：2031300元","协议金额","2031300","TAX_INCLUSIVE"));
        assertFalse(ProofVerifier.taxBound("含税协议金额：2031300元","协议金额","2031300","TAX_EXCLUSIVE"));
        assertTrue(ProofVerifier.taxBound("不含税协议金额：1863577.98元","协议金额","1863577.98","TAX_EXCLUSIVE"));
        assertFalse(ProofVerifier.taxBound("协议金额：2031300元 税率9%","协议金额","2031300","TAX_INCLUSIVE"));
        assertFalse(ProofVerifier.taxBound("含税协议金额：2031300元","协议金额","2031300","UNSPECIFIED"));
    }
    @Test void twoAmountsOnOneLineCannotBorrowEachOthersTaxLabel(){
        String text="含税合同金额：2031300元 不含税合同金额：1863577.98元";
        assertTrue(ProofVerifier.taxBound(text,"含税合同金额","2031300","TAX_INCLUSIVE"));
        assertFalse(ProofVerifier.taxBound(text,"含税合同金额","2031300","TAX_EXCLUSIVE"));
        assertTrue(ProofVerifier.taxBound(text,"不含税合同金额","1863577.98","TAX_EXCLUSIVE"));
        assertFalse(ProofVerifier.taxBound(text,"不含税合同金额","1863577.98","TAX_INCLUSIVE"));
        assertFalse(ProofVerifier.taxBound("含税价格100元 合同金额200元","合同金额","200","TAX_INCLUSIVE"));
    }
    @Test void pairedPrintedAmountBracketsAreTransparentButNotTheUnitOrValue(){
        for(String text:Arrays.asList("协议金额为：含税【3,488,000】元","协议金额：含税[3488000]元","协议金额：(3488000)元"))
            assertTrue(ProofVerifier.unitBound(text,"3488000","元"),text);
        assertFalse(ProofVerifier.unitBound("【3488000】万元","3488000","元"));
        assertFalse(ProofVerifier.unitBound("【3488000】元","3488001","元"));
        assertFalse(ProofVerifier.unitBound("【3488000]元","3488000","元"));
        assertFalse(ProofVerifier.unitBound("【3488000元","3488000","元"));
        assertFalse(ProofVerifier.unitBound("【3488000】 | 元","3488000","元"));
    }
    @Test void hostAcceptsOnlyTheSourceBoundBracketedAmountAndPrintedTax() throws Exception {
        Fixture f=new Fixture(identityBody(),"协议金额为：含税【3488000】元");
        Map<String,Object> atom=linked("field","amount","rawValue","3488000","sourceIds",Collections.singletonList(source(f.corpus,5).id()),
            "attributes",linked("amount","3488000","amountBasis","协议金额","unit","元","taxBasis","TAX_INCLUSIVE"));
        Map<String,Object> result=submit(f,atom);
        assertTrue(RuleEvaluator.maps(result.get("rejections")).isEmpty(),result::toString);
        Map<String,Object> bad=new LinkedHashMap<>(atom);Map<String,Object> attrs=new LinkedHashMap<>((Map<String,Object>)atom.get("attributes"));
        attrs.put("taxBasis","TAX_EXCLUSIVE");bad.put("attributes",attrs);
        assertFalse(RuleEvaluator.maps(submit(f,bad).get("rejections")).isEmpty());
    }
}
