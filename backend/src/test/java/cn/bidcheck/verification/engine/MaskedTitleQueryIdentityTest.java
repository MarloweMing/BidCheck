package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class MaskedTitleQueryIdentityTest {
    static final String form="本项目任职表\n姓名 李明扬\n拟在本合同任职 项目负责人", row="| 姓名 | 李*扬 |", number="202***12";
    static final String query="全国职称评审信息查询\n"+row+"\n| 证书编号 | "+number+" |\n| 职称名称级别 | 工程师 |";
    static Document document(String page){return new Document("b","p","BID","投标.pdf",1,"hash","",Arrays.asList(new Page(1,form,"PAGE","TEXT",""),new Page(2,page,"PAGE","OCR","")),Collections.emptyList());}
    static Map<String,Object> ref(int page,String id,String quote){return linked("documentId","b","documentHash","hash","documentVersion",1,"locationType","PAGE","page",page,"sourceId",id,"quote",quote);}
    static Map<String,Object> fact(String number){return linked("kind","RELATION","subject","李明扬","value","李*扬","field","同一人","attributes",linked("object","李*扬","number",number));}
    String key(String text,String id,String quote,String number){return MaskedTitleQueryIdentity.key("cp",fact(number),Arrays.asList(ref(1,"role",form),ref(2,id,quote)),document(text));}
    @Test void overlapsResolveOnlyInsideTheSameImmutableNameCellAndCertificate(){
        String a=key(query,"windowA","职称评审信息查询\n"+row,""),b=key(query,"windowB","李*扬 |\n| 证书编号",number);
        assertFalse(a.isEmpty());assertEquals(a,b);
        assertEquals("",key(query,"windowB","李*扬 |\n| 证书编号","DIFFERENT"));
        assertEquals("",key(query,"windowB","全国职称评审信息查询",""));
    }
    @Test void extraRowsAndNearbyCertificatesAreNotSubstitutesForASingleQueryRecord(){
        assertEquals("",key(query+"\n| 姓名 | 李*扬 |","windowA",row,""));
        assertEquals("",key(query+"\n| 姓名 | 李*华 |","windowA",row,""));
        assertEquals("",key(query+"\n| 证书编号 | OTHER-4321 |","windowA",row,""));
        assertEquals("",key(query.replace("| 证书编号","\n另附证书\n| 证书编号"),"windowA",row,""));
        assertEquals("",key(query.replace("职称评审信息查询","无关网页"),"windowA",row,""));
    }
    @Test void mismatchedVersionsAndOtherAppointmentsStaySeparate(){
        List<Map<String,Object>> refs=Arrays.asList(ref(1,"role",form),ref(2,"query",row));
        String a=MaskedTitleQueryIdentity.key("cp",fact(""),refs,document(query));
        refs.get(0).put("sourceId","another-role");assertNotEquals(a,MaskedTitleQueryIdentity.key("cp",fact(""),refs,document(query)));
        refs.get(1).put("documentVersion",2);assertEquals("",MaskedTitleQueryIdentity.key("cp",fact(""),refs,document(query)));
    }
}
