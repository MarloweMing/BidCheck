package cn.bidcheck.semantic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AffirmativeAssertionsTest {
    @ParameterizedTest
    @ValueSource(strings={
        "投标人盖章并签字。",
        "投标函已签字盖章。",
        "授权书由法定代表人签署。",
        "投标文件加盖单位公章。",
        "签字盖章齐全，符合要求。",
        "投标人已经按照要求签字盖章。",
        "招标文件要求签字盖章；投标人盖章并签字。"
    })
    void recognizesAffirmativeVisualCompletion(String text) { assertTrue(AffirmativeAssertions.visualCompletion(text)); }

    @ParameterizedTest
    @ValueSource(strings={
        "招标文件要求投标人盖章并签字。",
        "投标人应当签字盖章。",
        "需要核对投标人是否盖章并签字。",
        "待核实签字盖章是否齐全。",
        "无法确认投标人已盖章，不能确认已签字。",
        "模板提示：投标人（盖章）、法定代表人（签字）。",
        "投标文件未签字，盖章不完整。",
        "投标文件无需盖章。",
        "签字盖章栏为空，需补充。"
    })
    void rejectsRequirementsUnknownTemplateAndNegativeVisualStatements(String text) { assertFalse(AffirmativeAssertions.visualCompletion(text)); }

    @ParameterizedTest
    @ValueSource(strings={
        "三体系证书均在有效期内。",
        "三体系证书均有效。",
        "企业持有有效的安全生产许可证。",
        "注册证书未过期。",
        "证书有效期覆盖投标截止日期。",
        "证书有效期至2028年9月3日；三体系证书均有效。"
    })
    void recognizesAffirmativeCertificateValidity(String text) { assertTrue(AffirmativeAssertions.certificateValidity(text)); }

    @ParameterizedTest
    @ValueSource(strings={
        "证书有效期至2028年9月3日。",
        "证书有效期：2025年9月4日至2028年9月3日。",
        "招标文件要求证书均在有效期内。",
        "应当提供有效的三体系证书。",
        "证书是否有效需要核对投标截止时点。",
        "无法确认三体系证书均有效。",
        "三体系证书均在有效期内的结论待核实。",
        "模板提示：证书均有效。",
        "证书已过期，不在有效期内。",
        "投标有效期为90天。"
    })
    void rejectsExpiryTranscriptionObligationsAndUnknownValidity(String text) { assertFalse(AffirmativeAssertions.certificateValidity(text)); }

    @Test void nullAndEmptyInputsDoNotAssertAnything() {
        assertFalse(AffirmativeAssertions.visualCompletion(null));assertFalse(AffirmativeAssertions.visualCompletion(""));
        assertFalse(AffirmativeAssertions.certificateValidity(null));assertFalse(AffirmativeAssertions.certificateValidity("  "));
    }
}
