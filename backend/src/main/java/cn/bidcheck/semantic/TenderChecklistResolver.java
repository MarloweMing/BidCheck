package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import java.nio.charset.StandardCharsets;

/** Expands explicit tender references using frozen headings and template boundaries, never model output. */
public final class TenderChecklistResolver {
    private static final Pattern REFERENCE=Pattern.compile("(?:详见|参见|见)\\s*(第[一二三四五六七八九十百0-9]+章)?\\s*(?:中|的)?\\s*《([^》]{2,60})》");
    private static final Pattern CHAPTER=Pattern.compile("^(第[一二三四五六七八九十百0-9]+章)\\s*[^。；;\\t]{0,60}$");
    private static final Pattern MATERIAL=Pattern.compile(".{1,55}(?:承诺书|承诺|协议书|授权书|证明书|证明|证书|报告|文件|材料|营业执照|报价表|投标函|偏离表)");
    private static final Pattern OPTIONAL=Pattern.compile("可选|自愿提交|自行选择|认为有必要|根据需要提供");
    record Line(int order,Page page,String text,int start,int end) { }
    record Reference(String chapter,String title,String text,Evidence source) { }
    record Section(int start,int end,List<Integer> headings,List<Reference> references) { }
    private final Document tender;
    private final List<Line> lines;
    private final List<Map<String,Object>> entries=new ArrayList<>(),unresolved=new ArrayList<>(),excluded=new ArrayList<>();
    private final List<Evidence> sources=new ArrayList<>();
    private final Set<String> expanded=new HashSet<>();
    private TenderChecklistResolver(Document tender){this.tender=tender;this.lines=lines(tender);}

    public static Map<String,Object> resolve(Checkpoint checkpoint,Document tender) {
        List<Reference> references=references(Objects.toString(checkpoint.expected(),""),checkpoint.source());
        if(references.isEmpty())return Map.of();
        if(tender==null||tender.pages()==null||!"TENDER".equals(tender.role())||!valid(checkpoint.source(),tender))
            return unresolvedContract(checkpoint,"引用来源尚未对应本次冻结招标文件，不能猜测引用清单");
        TenderChecklistResolver resolver=new TenderChecklistResolver(tender);
        resolver.sources.add(checkpoint.source());
        for(Reference reference:references) {
            boolean grounded=references(checkpoint.source().quote(),checkpoint.source()).stream().anyMatch(r->r.chapter().equals(reference.chapter())&&r.title().equals(reference.title()));
            if(!grounded)resolver.issue(reference.text(),"当前确认文本的引用未对应原始条款，不自动执行改写后的引用",checkpoint.source());
            else resolver.expand(reference,new LinkedHashSet<>(),0);
        }
        boolean closed=resolver.unresolved.isEmpty()&&!resolver.entries.isEmpty();
        List<String> items=resolver.entries.stream().map(e->Objects.toString(e.get("item"))).toList();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("required",true);result.put("inventoryResolved",closed);result.put("items",items);
        result.put("itemRequirements",resolver.entries);result.put("excludedItems",resolver.excluded);
        result.put("inventorySources",resolver.sources.stream().distinct().toList());result.put("unresolvedReferences",resolver.unresolved);
        result.put("inventoryId",id(tender.id()+":"+tender.version()+":"+tender.sha256()+":"+checkpoint.expected()+":"+resolver.entries+":"+resolver.unresolved));
        result.put("resolutionMethod","FROZEN_TENDER_REFERENCE_STRUCTURE");result.put("sourceQuote",checkpoint.expected());
        result.put("boundary","从明确引用的招标正文清单展开；每份材料分别核对正文，条件未明确不得认定不适用，未召回不等于缺失。签章真实性不在文字完整性结论内。");
        if(resolver.entries.isEmpty()&&resolver.unresolved.isEmpty())resolver.issue("引用清单","未取得任何适用的独立清单项，不能按空集合通过",checkpoint.source());
        return result;
    }

    /** Read-only replay entry; it does not retrieve remotely or call any model. */
    public static Map<String,Object> inspect(Checkpoint checkpoint,Document tender,Document bid) {
        Map<String,Object> contract=CompletenessProof.contract(checkpoint,tender);
        var recall=ChecklistBodyRecall.recall(contract,bid);
        return Map.of("contract",contract,"recall",recall.audit());
    }

    private void expand(Reference reference,Set<String> path,int depth) {
        String key=reference.chapter()+"|"+reference.title();
        if(depth>8||path.contains(key)){issue(reference.text(),"引用存在循环或超过八层，完整集合未展开",reference.source());return;}
        if(expanded.contains(key))return;
        List<Section> candidates=new ArrayList<>();
        for(int i=0;i<lines.size();i++)if(heading(lines.get(i).text()).equals(reference.title())&&withinChapter(i,reference.chapter())) {
            Section section=section(i,reference.title());
            // A heading in a contents list cannot establish the template collection below the real heading.
            if(!section.headings().isEmpty()||!section.references().isEmpty())candidates.add(section);
        }
        if(candidates.size()!=1){issue(reference.text(),candidates.isEmpty()?"未定位到具有清单正文的引用标题；目录不作为展开依据":"引用标题对应多处正文，尚未确定适用版本或章节",reference.source());return;}
        Section section=candidates.get(0);Line root=lines.get(section.start());sources.add(evidence(root));
        Set<String> next=new LinkedHashSet<>(path);next.add(key);
        for(Reference nested:section.references())expand(nested,next,depth+1);
        for(int n=0;n<section.headings().size();n++) {
            int at=section.headings().get(n),end=n+1<section.headings().size()?section.headings().get(n+1):section.end();
            Line line=lines.get(at);String title=heading(line.text());
            String conditions=condition(line,end);
            String applicability=OPTIONAL.matcher(line.text()).find()?"OPTIONAL":explicitNotApplicable(conditions)?"NOT_APPLICABLE":!conditions.isBlank()?"CONDITIONAL":"REQUIRED";
            Evidence source=evidence(line);List<Evidence> requirementSources=new ArrayList<>(List.of(source));
            for(int j=at+1;j<Math.min(end,at+5);j++)if(!conditionText(lines.get(j).text()).isBlank())requirementSources.add(evidence(lines.get(j)));
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("id",id(tender.id()+":"+line.page().number()+":"+line.start()+":"+title));entry.put("item",title);
            entry.put("applicability",applicability);entry.put("condition",conditions);entry.put("requirementSources",List.copyOf(requirementSources));
            entry.put("sectionSource",evidence(root));entry.put("startLocation",line.page().number());entry.put("endLocation",lines.get(Math.max(at,end-1)).page().number());
            entry.put("reason",applicability.equals("REQUIRED")?"引用集合内的独立材料模板":applicability.equals("CONDITIONAL")?"须先确定该提交条件是否适用":"招标原文明确为可选或本项目不适用");
            if(Set.of("OPTIONAL","NOT_APPLICABLE").contains(applicability))excluded.add(entry);else entries.add(entry);
        }
        expanded.add(key);
    }
    private Section section(int start,String title) {
        int end=lines.size();
        for(int i=start+1;i<lines.size();i++) {
            String raw=lines.get(i).text(),name=heading(raw);
            if(CHAPTER.matcher(raw.strip()).matches()||raw.strip().matches("第[一二三四五六七八九十0-9]+分册.*")
                ||name.matches("其他承诺(?:书)?|其他投标人认为有必要的资料")&&!title.matches("其他承诺(?:书)?")
                ||name.equals(title)&&i>start+1){end=i;break;}
        }
        List<Integer> headings=new ArrayList<>();List<Reference> nested=new ArrayList<>();
        for(int i=start+1;i<end;i++) {
            Line line=lines.get(i);String name=heading(line.text());
            if(isMaterialHeading(line.text())&&!name.equals(title)) {
                if(!headings.isEmpty()&&name.equals(heading(lines.get(headings.get(headings.size()-1)).text()))&&i-headings.get(headings.size()-1)==1)continue;
                headings.add(i);
            }
            // Only explicit checklist redirects at the collection level are followed; laws cited inside a form are not material lists.
            if(headings.isEmpty()&&line.text().matches("(?s).*(?:清单|全部|材料|文件).*(?:详见|参见|见).*"))nested.addAll(references(line.text(),evidence(line)));
        }
        // Navigation headings are followed immediately by another collection/volume, without a form body or numbered list.
        boolean bodies=false;
        for(int at:headings)if(hasTemplateBody(at,endOfHeading(at,headings,end))||lines.get(at).text().strip().matches("(?:[0-9]+[.、)]|[一二三四五六七八九十]+、).*")){bodies=true;break;}
        if(!bodies)headings=List.of();
        return new Section(start,end,headings,nested);
    }
    private int endOfHeading(int at,List<Integer> headings,int end){return headings.stream().filter(i->i>at).findFirst().orElse(end);}
    private boolean hasTemplateBody(int at,int end){for(int i=at+1;i<end;i++){String text=lines.get(i).text();if(text.length()>=35&&text.matches("(?s).*[。；;：:].*"))return true;}return false;}
    private String condition(Line line,int end){List<String> values=new ArrayList<>();String own=headingCondition(line.text());if(!own.isBlank())values.add(own);for(int i=line.order()+1;i<Math.min(end,line.order()+5);i++){String found=conditionText(lines.get(i).text());if(!found.isBlank())values.add(found);}return String.join("；",values);}
    // A parenthetical on the material title qualifies that material. Inside its body it may qualify another noun.
    private static String headingCondition(String text){String t=text.strip();return t.matches("(?s).*[（(](?:如有|可选|适用时|如适用)[）)].*")?t:conditionText(t);}
    private static String conditionText(String text){String t=text.strip();return t.matches("(?s).*本项目(?:不适用|无需提交|不要求提交).*")
        ||t.matches("^(?:如为|如有|如采用|若为|若有|若采用|仅适用于|不使用|未使用|采用|使用|联合体投标).{0,90}(?:须提交|应提交|需提交|无需提交|不需提交|须提供|应提供|需提供|时提供).*" )?t:"";}
    private static boolean explicitNotApplicable(String conditions){return Arrays.stream(conditions.split("[；\\r\\n]+"))
        .map(String::strip).anyMatch(text->text.matches("^(?:说明[:：]\\s*)?本项目(?:不适用|无需提交|不要求提交)(?:本项|本材料|本承诺书|该材料|该承诺书)?[。.]?$"));}
    private boolean withinChapter(int at,String required){if(required.isBlank())return true;for(int i=at;i>=0;i--){Matcher chapter=CHAPTER.matcher(lines.get(i).text().strip());if(chapter.matches())return chapter.group(1).equals(required);}return false;}
    private void issue(String text,String reason,Evidence source){Map<String,Object> item=new LinkedHashMap<>();item.put("text",text);item.put("reason",reason);item.put("sources",source==null?List.of():List.of(source));unresolved.add(item);}
    private Evidence evidence(Line line){return new Evidence(tender.id(),tender.name(),line.page().number(),line.page().locationType(),line.text(),"text:"+line.start()+"-"+line.end(),1);}
    static List<Line> lines(Document document){List<Line> out=new ArrayList<>();if(document==null||document.pages()==null)return out;for(Page page:document.pages()){String text=Objects.toString(page.text(),"");Matcher m=Pattern.compile("[^\\r\\n]+").matcher(text);while(m.find())if(!m.group().isBlank())out.add(new Line(out.size(),page,m.group(),m.start(),m.end()));}return out;}
    static String heading(String value){return value.strip().replaceFirst("^[★*#]+\\s*","").replaceFirst("^(?:[0-9]+(?:\\.[0-9]+)*[.、)．]?|[一二三四五六七八九十]+、)\\s*","").replaceAll("[（(](?:参考格式|格式|如有|可选|适用时|如适用)[）)]","").strip();}
    static boolean isMaterialHeading(String raw){String title=heading(raw);return title.length()<=60&&!title.matches("(?s).*[。；;：:《》\\t].*")&&!title.matches("^(?:须|应|需|请|注|提供|提交|如|若|本|不).*" )&&MATERIAL.matcher(title).matches();}
    private static List<Reference> references(String text,Evidence source){List<Reference> out=new ArrayList<>();Matcher m=REFERENCE.matcher(text);while(m.find())out.add(new Reference(Objects.toString(m.group(1),""),m.group(2),m.group(),source));return out;}
    private static boolean valid(Evidence source,Document tender){return source!=null&&tender.id().equals(source.documentId())&&tender.pages().stream().anyMatch(p->p.number()==source.page()&&Objects.equals(p.locationType(),source.locationType())&&Objects.toString(p.text(),"").contains(source.quote()));}
    private static Map<String,Object> unresolvedContract(Checkpoint cp,String reason){return Map.of("required",true,"inventoryResolved",false,"items",List.of(),"itemRequirements",List.of(),"inventorySources",cp.source()==null?List.of():List.of(cp.source()),"unresolvedReferences",List.of(Map.of("text",cp.expected(),"reason",reason,"sources",cp.source()==null?List.of():List.of(cp.source()))),"inventoryId",id(cp.id()+":"+reason));}
    static String id(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();}
}
