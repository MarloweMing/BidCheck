package cn.bidcheck.verification.engine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PersonnelQueryScreenshotTest {
    @Test void queryHostMustExactlyMatchAnApprovedService(){assertTrue(PersonnelQueryScreenshot.officialUrl("查询网址：https://cx.mem.gov.cn/query"));for(String url:new String[]{"https://cx.mem.gov.cn.evil.org/query","https://other.gov.cn/query","https://evil.org/?site=cx.mem.gov.cn","https://cx.mem.gov.cn@evil.org/query","https://cx.mem.gov.cn/query https://evil.org/query"})assertFalse(PersonnelQueryScreenshot.officialUrl(url),url);}
}
