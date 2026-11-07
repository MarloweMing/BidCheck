package cn.bidcheck.verification.engine;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.VerificationEngineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CertificateSegmentUseLimitsTest {
    private static final String OLD="甲某多证照，旧A证安全证书编号：A001，有效期2020年1月1日至2028年1月1日";
    private static final String REG="注册证书编号：R001，有效期2024年1月1日至2027年1月1日";
    private static final String USE="；使用期限：2024年1月1日至2024年12月31日";
    private CertificateValidityDates.Result result(String text){
        EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(doc(text)));
        Fact proof=fact("CERTIFICATE","甲某","","注册证","","",linked("category","注册证书","number","R001","numberField","注册证书编号",
            "validityField","有效期","start","2024-01-01","end","2027-01-01"),corpus);
        return CertificateValidityDates.fact(proof,corpus,"有效期",LocalDate.of(2025,8,1));
    }
    @Test void selectedCertificateRetainsItsOwnUsePeriod(){assertEquals(Boolean.FALSE,result(OLD+"；"+REG+USE).valid());}
    @Test void usePeriodOfAnotherCertificateCannotVetoSelectedOne(){assertEquals(Boolean.TRUE,result(OLD+USE+"；"+REG).valid());}
}
