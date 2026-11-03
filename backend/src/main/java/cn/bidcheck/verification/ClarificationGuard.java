package cn.bidcheck.verification;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.domain.Domain.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.*;

/** Finds sourced amendment candidates. It never rewrites a confirmed requirement or asserts an amendment is final. */
public final class ClarificationGuard {
    private static final String VERSION="clarification-impact-1";
    private static final Pattern CHANGE=Pattern.compile("修改|调整|变更|更正|补充|删除|取消|废止|延期|延迟|顺延|改为|替换为|以[^。；;\\r\\n]{1,100}为准");
    private static final Pattern NEGATED=Pattern.compile("(?:未(?:作|做|予以|发生|进行|作出)?|不(?:作|做|予以|发生|进行)?|无需)(?:任何)?(?:修改|调整|变更|更正|补充)|其他(?:内容|条款)(?:均)?(?:不变|不作修改)");
    private static final List<String> TOPICS=cn.bidcheck.compat.Java8Api.listOf("投标截止", "递交截止", "开标时间", "投标有效期", "工期", "进度", "报价", "最高限价", "预算", "建安", "保证金", "资格", "资质", "证书", "注册", "社保", "项目负责人", "项目经理", "设计负责人", "技术负责人", "安全员", "业绩", "保修", "缺陷", "付款", "联合体", "股权", "人员", "评分", "签字", "签章", "盖章", "格式", "营业执照", "纳税", "诚信", "设备", "技术标准");
    private static final List<String> GENERIC=cn.bidcheck.compat.Java8Api.listOf("投标", "招标", "文件", "要求", "规定", "提供", "评审", "符合", "满足", "本项目", "本次", "相关", "内容", "条款", "应当", "不得", "原文", "修改", "调整", "变更", "补充", "更正", "提交", "核验", "检查", "工程");
    public String identity(){return VERSION;}
    public Map<String,Object> assess(Checkpoint checkpoint,List<Document> supplementalTenderDocuments){
        Objects.requireNonNull(checkpoint);String clause=String.join("\n",safe(checkpoint.title()),safe(checkpoint.field()),safe(checkpoint.expected()));
        if(checkpoint.source()!=null)clause+="\n"+safe(checkpoint.source().quote()).substring(0,Math.min(1200,safe(checkpoint.source().quote()).length()));
        Set<String> terms=terms(clause);List<Map<String,Object>> candidates=new ArrayList<>();Set<String> seen=new HashSet<>();long scanned=0;boolean incomplete=false;
        outer:for(Document doc:supplementalTenderDocuments==null?cn.bidcheck.compat.Java8Api.<Document>listOf():supplementalTenderDocuments){
            interrupted();if(doc==null||!"TENDER".equals(doc.role())||doc.pages()==null)continue;
            if(checkpoint.source()!=null&&doc.id().equals(checkpoint.source().documentId()))continue;
            for(Page page:doc.pages()){
                interrupted();String text=safe(page.text());scanned+=text.length();if(scanned>12_000_000){incomplete=true;break outer;}
                Matcher changes=CHANGE.matcher(text);
                while(changes.find()){
                    interrupted();int before=text.lastIndexOf('\n',changes.start())+1;int after=text.indexOf('\n',changes.end());if(after<0)after=text.length();
                    // Include one following line for a changed value in a table/paragraph continuation, but never another page.
                    int next=text.indexOf('\n',Math.min(text.length(),after+1));if(next>=0)after=next;else after=Math.min(text.length(),after+240);
                    int start=Math.max(before,changes.start()-180),end=Math.min(after,changes.end()+420);String quote=text.substring(start,end);
                    String changed=changeClause(text,changes.start(),changes.end());if(NEGATED.matcher(changed).find()&&!CHANGE.matcher(NEGATED.matcher(changed).replaceAll("")).find())continue;
                    String impact=TOPICS.stream().anyMatch(changed::contains)?changed:quote;
                    List<String> hits=cn.bidcheck.compat.Java8Api.toList(terms.stream().filter(impact::contains).sorted(Comparator.comparingInt(String::length).reversed()).limit(12));
                    boolean scopedElsewhere=TOPICS.stream().anyMatch(impact::contains);
                    if(hits.isEmpty()&&scopedElsewhere)continue;
                    String id=hash(VERSION+checkpoint.id()+doc.id()+doc.version()+doc.sha256()+page.number()+page.locationType()+start+end+quote);
                    if(!seen.add(doc.id()+":"+page.number()+":"+start+":"+end))continue;
                    if(candidates.size()>=24){incomplete=true;break outer;}
                    Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("id",id);candidate.put("documentId",doc.id());candidate.put("documentHash",doc.sha256());candidate.put("documentVersion",doc.version());
                    candidate.put("matchType",hits.isEmpty()?"IMPACT_SCOPE_UNRESOLVED":"CLAUSE_KEYWORD_MATCH");candidate.put("matchedKeywords",hits);
                    candidate.put("evidence",new Evidence(doc.id(),doc.name(),page.number(),page.locationType(),quote,"chars:"+start+"-"+end,0));candidates.add(candidate);
                }
            }
        }
        boolean required=incomplete||!candidates.isEmpty();Map<String,Object> result=new LinkedHashMap<>();result.put("version",VERSION);result.put("checkpointId",checkpoint.id());result.put("requiresConfirmation",required);result.put("status",required?"RECONFIRM_REQUIRED":"NO_CHANGE_CANDIDATE");result.put("candidates",candidates);result.put("scanIncomplete",incomplete);
        result.put("limitations",cn.bidcheck.compat.Java8Api.listOf("仅识别有原文出处的规则修改候选；不证明文件生效顺序，不自动覆盖原条款，也不把无命中解释为所有澄清均已理解。", "作用域不明或扫描预算不足时保守要求确认；确认修订必须绑定候选、原检查项和资料版本。"));return result;
    }
    private static String changeClause(String text,int start,int end){int a=start,b=end;while(a>0&&"。；;\n\r".indexOf(text.charAt(a-1))<0&&start-a<180)a--;while(b<text.length()&&"。；;\n\r".indexOf(text.charAt(b))<0&&b-end<420)b++;return text.substring(a,b);}
    private static Set<String> terms(String clause){Set<String> out=new LinkedHashSet<>();for(String t:TOPICS)if(clause.contains(t))out.add(t);Matcher words=Pattern.compile("[\\p{IsHan}]{3,40}|[A-Za-z][A-Za-z0-9_-]{2,40}").matcher(clause);while(words.find()&&out.size()<240){String w=words.group();if(w.matches("[A-Za-z].*")){out.add(w);continue;}for(int size=4;size>=3;size--)for(int i=0;i+size<=w.length()&&out.size()<240;i++){String term=w.substring(i,i+size);if(GENERIC.stream().noneMatch(term::contains))out.add(term);}}return out;}
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("澄清影响检查已中断");}
    private static String safe(String text){return text==null?"":text;}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
