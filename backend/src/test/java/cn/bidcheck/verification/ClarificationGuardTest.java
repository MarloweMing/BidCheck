package cn.bidcheck.verification;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClarificationGuardTest {
    final ClarificationGuard guard=new ClarificationGuard();
    Checkpoint cp(String title,String expected){return new Checkpoint("rule",title,"","","SEMANTIC_RESPONSE","","",expected,"",true,true,true,new Evidence("original","主招标.docx",3,"BLOCK",title+expected,"",1));}
    Document doc(String text){return new Document("addendum","project","TENDER","澄清.docx",2,"a".repeat(64),"",List.of(new Page(8,text,"BLOCK","WORD_NATIVE","")),List.of());}
    @Test void changedDurationIsSourcedAndRequiresExplicitReconfirmation(){var document=doc("关于工期的更正\n原工期45天调整为60天，其余内容不变。");var r=guard.assess(cp("总工期","不超过45天"),List.of(document));assertEquals(true,r.get("requiresConfirmation"));Map<?,?> c=(Map<?,?>)((List<?>)r.get("candidates")).get(0);Evidence e=(Evidence)c.get("evidence");assertTrue(document.pages().get(0).text().contains(e.quote()));assertEquals(8,e.page());assertEquals("BLOCK",e.locationType());assertEquals(document.sha256(),c.get("documentHash"));assertEquals("CLAUSE_KEYWORD_MATCH",c.get("matchType"));}
    @Test void ordinaryDeadlineAmendmentDoesNotBlockUnrelatedDuration(){var r=guard.assess(cp("总工期","45天"),List.of(doc("投标截止时间调整为2030年5月1日，其他内容不变。")));assertEquals(false,r.get("requiresConfirmation"));}
    @Test void deadlineAmendmentDoesBlockAnObligationDependingOnDeadline(){assertEquals(true,guard.assess(cp("证书有效性","证书应覆盖投标截止时间"),List.of(doc("投标截止时间延期至2030年5月1日。"))).get("requiresConfirmation"));}
    @Test void unknownScopeIsConservativeButNoAmendmentTextDoesNotBlock(){assertEquals(true,guard.assess(cp("总工期","45天"),List.of(doc("有关条款已作修改，以本通知为准。"))).get("requiresConfirmation"));assertEquals(false,guard.assess(cp("总工期","45天"),List.of(doc("本项目联系人联系方式如下。"))).get("requiresConfirmation"));}
    @Test void explicitUnchangedClauseDoesNotBecomeAnAmendment(){assertEquals(false,guard.assess(cp("总工期","45天"),List.of(doc("工期未作修改，其他内容不变。"))).get("requiresConfirmation"));}
    @Test void conditionalScopeDoesNotSilentlyAuthorizeKeepingOldRule(){assertEquals(true,guard.assess(cp("总工期","45天"),List.of(doc("若存在工期调整，以本通知为准。"))).get("requiresConfirmation"));}
    @Test void nonstandardTechnicalTermStillMatchesWithoutKnownTopicDictionary(){assertEquals(true,guard.assess(cp("冷水机组噪声值","噪声值应不超过55分贝"),List.of(doc("冷水机组噪声值由55分贝更正为50分贝。"))).get("requiresConfirmation"));}
    @Test void candidateIdentityChangesWithImmutableVersion(){Checkpoint c=cp("总工期","45天");Document one=doc("工期调整为60天。");Document two=new Document(one.id(),one.projectId(),one.role(),one.name(),3,"b".repeat(64),"",one.pages(),List.of());Object a=((Map<?,?>)((List<?>)guard.assess(c,List.of(one)).get("candidates")).get(0)).get("id"),b=((Map<?,?>)((List<?>)guard.assess(c,List.of(two)).get("candidates")).get(0)).get("id");assertNotEquals(a,b);}
    @Test void originalTenderAndBidderDeclarationsAreNotSupplementalChanges(){Checkpoint c=cp("总工期","45天");Document original=new Document("original","project","TENDER","",1,"hash","",List.of(new Page(1,"工期调整为60天","PAGE","PDF","")),List.of());Document bid=new Document("bid","project","BID","",1,"hash","",original.pages(),List.of());assertEquals(false,guard.assess(c,List.of(original,bid)).get("requiresConfirmation"));}
    @Test void cancellationIsNotSilentlyTreatedAsNoChange(){Thread.currentThread().interrupt();try{assertThrows(CancellationException.class,()->guard.assess(cp("总工期","45天"),List.of(doc("工期调整为60天"))));}finally{Thread.interrupted();}}
}
