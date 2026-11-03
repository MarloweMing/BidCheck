package cn.bidcheck.verification.facts;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityIdentityAdjacentLabelsTest {
    private Map<String,Object> resolve(String quote){return EntityIdentity.resolvePerson("李明",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "source", "evidence", cn.bidcheck.compat.Java8Api.mapOf("quote", quote))));}
    @Test void adjacentExplicitLabelsBindAndKeepOriginalOffsets(){String text="姓名：李明\n身份证号：110101198001010010";java.util.Map<java.lang.String, java.lang.Object> result=resolve(text);assertEquals("STRONG",result.get("identityStrength"));List<?> identifiers=(List<?>)result.get("identifiers");java.util.Map<?, ?> id=(Map<?,?>)identifiers.get(0);assertEquals(text.indexOf("110101"),id.get("sourceStart"));assertEquals(text.length(),id.get("sourceEnd"));assertFalse(result.toString().contains("110101198001010010"));}
    @Test void personalCertificateAdmitsItsLabelledIdentityNumber(){assertEquals("STRONG",resolve("安全生产考核合格证书\n姓名：李明\n证件号码：110101198001010010").get("identityStrength"));assertEquals("SOURCE_LOCAL",resolve("一般说明\n姓名：李明\n证件号码：110101198001010010").get("identityStrength"));}
    @Test void anotherPersonOrPortraitCannotBeCrossed(){for(String text:cn.bidcheck.compat.Java8Api.listOf("姓名：李明\n姓名：张三\n身份证号：110101198001010010", "姓名：李明\n\n身份证号：110101198001010010", "李明\n身份证号：110101198001010010", "姓名：李明\n性别：男\n身份证号：110101198001010010", "姓名：李明\n人物肖像识别：年龄30岁\n身份证号：110101198001010010"))assertEquals("SOURCE_LOCAL",resolve(text).get("identityStrength"),text);}
    @Test void malformedNumericIdentifierDoesNotBecomeStrong(){for(String id:cn.bidcheck.compat.Java8Api.listOf("11010119800101001", "1101011980010100109", "11010119800101001A", "110101198001010010A"))assertEquals("SOURCE_LOCAL",resolve("姓名：李明\n身份证号："+id).get("identityStrength"));}
}
