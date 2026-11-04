package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Validates document presence against the immutable BID pages and returns original source spans. */
final class SubmittedMaterialStructure {
    private static final Pattern INDEX_ROW = Pattern.compile("(?m)^\\s*(?:\\d+[.、\\s]|\\|).{1,100}(?:[Pp第]\\s*\\d+|\\d+\\s*[-至]\\s*\\d+|\\d+\\s*[页|]).*");
    private static final Pattern DIRECTORY = Pattern.compile("[.．…·]{2,}\\s*\\d+|^\\s*目录\\s*$",Pattern.MULTILINE);
    private static final Pattern FILLED_COVER = Pattern.compile("(?:投标人(?:名称)?|供应商名称|项目名称)\\s*[:：]\\s*(?:[（(](?:盖章|公章|全称|单位盖章)[）)]\\s*)?([\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9·（）() -]{2,})");
    private SubmittedMaterialStructure() {}

    static List<Source> locate(String title, EvidenceCorpus corpus) {
        String volume=MaterialTitleAliases.volume(title);
        for (Document doc : corpus.documents) {
            if (!"BID".equals(doc.role())) continue;
            List<Page> pages=doc.pages();
            for (int i=0;i<pages.size();i++) {
                Page page=pages.get(i);
                String text=Objects.toString(page.text(),"");
                if (!volume.isEmpty()) {
                    if (!volume.equals(coverType(text))) continue;
                    List<Source> cover=sources(corpus,doc,page);
                    if (cover.isEmpty()) continue;
                    if (MaterialTitleAliases.canonical(title).endsWith("封面")) return cover;
                    for (int j=i+1;j<pages.size();j++) {
                        String body=Objects.toString(pages.get(j).text(),"");
                        if (!coverType(body).isEmpty()) break;
                        if (DIRECTORY.matcher(body).find()) continue;
                        boolean present=volume.equals("报价文件")
                            ? body.matches("(?s).*(?:报价汇总表|已标价工程量清单|投标报价表).*") && body.matches("(?s).*(?:金额|报价|单价|合价).{0,150}\\d+[.,]\\d+.*")
                            : body.matches("(?s).*(?:条款号.{0,80}对应页码|投标函|技术建议书|技术条款偏离表).*") && (INDEX_ROW.matcher(body).find() || substantive(body));
                        if (present) {
                            List<Source> evidence=sources(corpus,doc,pages.get(j));
                            if (!evidence.isEmpty()) {List<Source> joined=new ArrayList<>(cover);joined.addAll(evidence);return joined;}
                        }
                    }
                    continue;
                }
                String[] lines=text.split("\\r?\\n");
                for (int n=0;n<lines.length;n++) {
                    if (!MaterialTitleAliases.matches(title,lines[n]) || DIRECTORY.matcher(lines[n]).find()) continue;
                    StringBuilder body=new StringBuilder();
                    boolean indexMaterial=MaterialTitleAliases.canonical(title).endsWith("索引表");
                    boolean nested=false,closed=false;
                    for(int k=n+1;k<lines.length;k++){
                        String line=lines[k].trim();
                        if(line.startsWith("【PDF识别文字】")) break;
                        if(line.matches("^\\d+[.．]\\d+.*")) nested=true;
                        else if(SectionCoverage.level(line)==1 && !(nested && line.startsWith("第"))
                                && !(indexMaterial && INDEX_ROW.matcher(line).find())){closed=true;break;}
                        body.append(line).append('\n');
                    }
                    if (indexMaterial) {
                        if (body.toString().matches("(?s).*(?:评审因素|条款号|评审内容|序号).*") && INDEX_ROW.matcher(body).find()) return sources(corpus,doc,page);
                    } else {
                        if (substantive(body.toString())) return sources(corpus,doc,page);
                        if (nested && !closed && i+1<pages.size() && pages.get(i+1).number()==page.number()+1) {
                            String next=Objects.toString(pages.get(i+1).text(),"");
                            if (coverType(next).isEmpty() && !next.trim().matches("(?s)^\\d+[.、．](?!\\d).*")
                                && !DIRECTORY.matcher(next).find() && substantive(next)) {
                                List<Source> a=sources(corpus,doc,page), b=sources(corpus,doc,pages.get(i+1));
                                if(!a.isEmpty()&&!b.isEmpty()){a.addAll(b);return a;}
                            }
                        }
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    static boolean volumeNotProvided(String title,EvidenceCorpus corpus) {
        String wanted=MaterialTitleAliases.volume(title);
        if(wanted.isEmpty())return false;
        boolean other=false;
        for(Document d:corpus.documents) if("BID".equals(d.role())){
            if(d.warnings()!=null && d.warnings().stream().anyMatch(w->w.contains("OCR未完成")||w.contains("OCR_DEFERRED")))return false;
            for(Page p:d.pages()){
                String t=Objects.toString(p.text(),"");
                if(t.trim().isEmpty()||Objects.toString(p.warning(),"").matches("(?s).*(?:OCR未完成|OCR_DEFERRED).*"))return false;
                String cover=coverType(t);
                if(wanted.equals(cover))return false;
                if(!cover.isEmpty())other=true;
                for(String line:t.split("\\r?\\n")) if(MaterialTitleAliases.volume(line).equals(wanted))return false;
            }
        }
        return other;
    }

    private static String coverType(String text) {
        if(DIRECTORY.matcher(text).find() || text.matches("(?s).*(?:填写说明|投标文件模板|填写示例).*"))return "";
        java.util.regex.Matcher identity=FILLED_COVER.matcher(text);
        boolean filled=false;
        while(identity.find())if(!identity.group(1).matches(".*(?:示例|待填|填写|[×Xx]{2,}|某某).*")){filled=true;break;}
        if(!filled)return "";
        for(String line:text.split("\\r?\\n")){
            String type=MaterialTitleAliases.volume(line);
            if(!type.isEmpty())return type;
        }
        return "";
    }

    private static boolean substantive(String text) {
        for(String line:text.split("\\r?\\n")) if(!line.matches(".*(?:填写说明|承诺提供|拟提供|详见附件|模板|示例).*")
            && SectionCoverage.level(line.trim())==0 && line.replaceAll("\\s","").length()>=14
            && line.matches(".*[，。；：].*"))return true;
        return false;
    }

    private static List<Source> sources(EvidenceCorpus corpus,Document doc,Page page) {
        List<Source> sources=new ArrayList<>();
        for(Source s:corpus.all) if(doc.id().equals(s.evidence().documentId()) && page.number()==s.evidence().page()
                && Objects.equals(page.locationType(),s.evidence().locationType()) && !s.id().startsWith("V")&&!s.historical())sources.add(s);
        return sources;
    }
}
