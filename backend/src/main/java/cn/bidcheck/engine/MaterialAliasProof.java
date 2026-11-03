package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import java.util.*;

/** Closed vocabulary aliases identify candidates; structure and current-document provenance prove existence. */
public final class MaterialAliasProof {
    private MaterialAliasProof() {}
    public static Optional<Evaluation> evaluate(Checkpoint cp,Document doc) {
        return evaluate(cp,doc,null);
    }
    public static Optional<Evaluation> evaluate(Checkpoint cp,Document doc,Document tender) {
        if(!"MATERIAL_EXISTENCE".equals(cp.type())||!cn.bidcheck.compat.Java8Api.setOf("组成检查", "形式检查").contains(cp.category())||!cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(cp.unit(),"")))return Optional.empty();
        String requirement=compact(cp.expected());
        String kind;
switch (requirement) {case "第一分册商务投标文件":kind = "BUSINESS_VOLUME"; break;case "第二分册技术投标文件":kind = "TECHNICAL_VOLUME"; break;case "商务技术评审索引表":kind = "BUSINESS_INDEX"; break;case "技术投标索引表":kind = "TECHNICAL_INDEX"; break;default:kind = ""; break;}
        if(kind.isEmpty())return Optional.empty();
        if(kind.equals("BUSINESS_INDEX")&&!businessVolumeRequirement(cp,tender))return Optional.empty();
        List<String> names;
switch (kind) {case "BUSINESS_VOLUME":names = cn.bidcheck.compat.Java8Api.listOf("投标文件（商务部分）", "商务投标文件"); break;case "TECHNICAL_VOLUME":names = cn.bidcheck.compat.Java8Api.listOf("投标文件（技术部分）", "技术投标文件"); break;case "BUSINESS_INDEX":names = cn.bidcheck.compat.Java8Api.listOf("商务评审索引表", "商务技术评审索引表"); break;default:names = cn.bidcheck.compat.Java8Api.listOf("技术评审索引表", "技术投标索引表"); break;}
        List<Evidence> sources=new ArrayList<>();cn.bidcheck.verification.engine.DocumentScopeProof.Resolver scope=DocumentScopeProof.forDocument(doc);
        for(Page page:doc.pages()) {
            String text=Objects.toString(page.text(),"");List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(text).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));
            if(lines.stream().limit(4).noneMatch(line->names.stream().anyMatch(name->title(line).equals(compact(name)))))continue;
            Evidence evidence=EngineText.evidence(doc,page,1,text);
            if(!Boolean.TRUE.equals(scope.currentEvidence(evidence).get("verifiedCurrent")))continue;
            boolean body;
            if(kind.endsWith("VOLUME"))body=text.matches("(?s).*(?:招标项目编号|招标编号|项目编号)\\s*[:：].*[A-Za-z0-9].*")&&text.matches("(?s).*投标人(?:名称)?\\s*[（(]?[^\\n]{0,25}[:：].{4,}.*")&&text.length()>80;
            else body=text.contains("评审因素")&&text.contains("条款号")&&text.matches("(?s).*(?:对应页码|页码|投标文件页码).*")
                &&lines.stream().filter(line->line.matches(".*(?:P|p)?[0-9]+[-—~～至][0-9]+.*")||line.matches(".*(?:满足|投标函|承诺|方案|[Pp][0-9]+).*" )).count()>=2
                &&text.length()>100&&!text.matches("(?s).*(?:未提供|待补充|另行提供)(?:本|该)?(?:索引表|评审索引表).*" );
            if(body)sources.add(evidence);
        }
        if(sources.isEmpty())return Optional.empty();
        return Optional.of(new Evaluation("PASS","已在当前投标文书中找到“"+cp.expected()+"”对应的"+(kind.endsWith("INDEX")?"评审索引表正文及条目":"分册封面和项目、投标人信息")+"；按受控名称映射核验本项材料存在。","本项仅确认所要求材料存在，不代表分册全部内容或索引列出的各项证明已经通过。",sources,1,"MATERIAL_ALIAS_BODY_RULE"));
    }
    private static String compact(String text){return Objects.toString(text,"").replaceAll("\\s+","").replace('(', '（').replace(')', '）');}
    private static String title(String text){return compact(text).replaceFirst("^[0-9]+[.、．]","");}
    private static boolean businessVolumeRequirement(Checkpoint cp,Document tender){
        Evidence source=cp.source();if(tender==null||source==null||!tender.id().equals(source.documentId())||!"TENDER".equals(tender.role()))return false;
        List<Page> pages=tender.pages();int location=-1;for(int i=0;i<pages.size();i++)if(pages.get(i).number()==source.page()&&pages.get(i).locationType().equals(source.locationType())){if(location>=0)return false;location=i;}
        if(location<0)return false;String text=Objects.toString(pages.get(location).text(),"");int at=text.indexOf(source.quote());if(at<0||text.indexOf(source.quote(),at+1)>=0)return false;
        // The nearest structural volume governs this title. A similar label elsewhere does not.
        for(int i=location;i>=Math.max(0,location-24);i--){
            String part=i==location?text.substring(0,at):Objects.toString(pages.get(i).text(),"");List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(part));
            for(int j=lines.size()-1;j>=0;j--){String line=compact(lines.get(j));
                if(line.equals("第一分册商务投标文件"))return true;
                if(line.matches("第[一二三四五六七八九十0-9]+(?:分册|章).*"))return false;
            }
        }return false;
    }
}
