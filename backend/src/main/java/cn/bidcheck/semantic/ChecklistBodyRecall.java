package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import java.util.*;

/** Local, per-item body recall. Each excerpt retains its document location and its own section boundary. */
final class ChecklistBodyRecall {
    record Item(String item,List<Evidence> evidences,boolean bodyLocated,boolean currentScope,boolean excerpted,String reason,List<Map<String,Object>> scopeProofs,List<List<Evidence>> equivalentCopies) {
        Item(String item,List<Evidence> evidences,boolean bodyLocated,boolean currentScope,boolean excerpted,String reason){this(item,evidences,bodyLocated,currentScope,excerpted,reason,List.of());}
        Item(String item,List<Evidence> evidences,boolean bodyLocated,boolean currentScope,boolean excerpted,String reason,List<Map<String,Object>> scopeProofs){this(item,evidences,bodyLocated,currentScope,excerpted,reason,scopeProofs,List.of(evidences));}
        Map<String,Object> map(){return Map.of("item",item,"evidences",evidences,"bodyLocated",bodyLocated,"currentScope",currentScope,"excerpted",excerpted,"reason",reason,"scopeProofs",scopeProofs,"equivalentCopies",equivalentCopies);}
    }
    record Recall(List<Item> items,List<Map<String,Object>> navigationSources) {
        Recall(List<Item> items){this(items,List.of());}
        Map<String,Object> audit(){return Map.of("method","PER_ITEM_FROZEN_BODY_RECALL","items",items.stream().map(Item::map).toList(),
            "bodyLocatedCount",items.stream().filter(Item::bodyLocated).count(),"currentBodyCount",items.stream().filter(i->i.bodyLocated()&&i.currentScope()).count(),
            "navigationSources",navigationSources,
            "boundary","按每项独立标题及所在正文区间定位；片段用于材料正文存在核验，不代表全文、签章或所有内容均已验证。");}
        List<Evidence> evidences(){return items.stream().flatMap(i->i.evidences().stream()).distinct().toList();}
    }
    static Recall recall(Map<String,Object> inventory,Document bid) {
        List<?> required=inventory.get("items") instanceof List<?> list?list:List.of();
        if(required.isEmpty())return new Recall(List.of());
        List<TenderChecklistResolver.Line> allLines=TenderChecklistResolver.lines(bid);List<Item> out=new ArrayList<>();
        Map<Page,Integer> navigationAppendices=new IdentityHashMap<>();List<Map<String,Object>> navigationSources=new ArrayList<>();
        if(bid!=null)for(Page page:bid.pages()) {
            String text=Objects.toString(page.text(),"");int marker=text.indexOf("【PDF识别文字】");
            if(marker>=0&&recognitionNarration(text.substring(marker))) {
                navigationAppendices.put(page,marker);
                var line=new TenderChecklistResolver.Line(0,page,text.substring(marker),marker,text.length());
                if(text.lines().anyMatch(raw->required.stream().map(Object::toString).anyMatch(item->matches(raw,item))))
                    navigationSources.add(Map.of("evidence",source(bid,line,marker,Math.min(text.length(),marker+1200)),
                        "reason","附加OCR内容含图解或识别生成说明，仅用于导航；不能作为独立章节正文或额外版本。冻结原文保留。","classification","OCR_RECONSTRUCTION_NAVIGATION"));
            }
        }
        // Keep the literal boundary marker when it is its own line. It uniquely anchors a native excerpt
        // when the OCR append repeats that excerpt word for word, without admitting the generated append body.
        List<TenderChecklistResolver.Line> lines=allLines.stream().filter(line->line.start()<navigationAppendices.getOrDefault(line.page(),Integer.MAX_VALUE)
            ||line.start()==navigationAppendices.getOrDefault(line.page(),Integer.MAX_VALUE)&&line.text().strip().equals("【PDF识别文字】")).toList();
        var scope=DocumentScopeProof.forDocument(bid);
        for(Object value:required) {
            String item=String.valueOf(value);List<Item> choices=new ArrayList<>();
            for(int at=0;at<lines.size();at++)if(matches(lines.get(at).text(),item)) {
                if(at>0&&matches(lines.get(at-1).text(),item))continue;
                int end=lines.size();
                for(int i=at+1;i<lines.size();i++) {
                    String text=lines.get(i).text();
                    if(matches(text,item)&&i==at+1)continue;
                    if(TenderChecklistResolver.isMaterialHeading(text)||text.strip().matches("第[一二三四五六七八九十百0-9]+章.*")
                        ||required.stream().map(Object::toString).anyMatch(name->matches(text,name))){end=i;break;}
                }
                var heading=lines.get(at);List<Evidence> proof=new ArrayList<>();String text=heading.page().text();
                int pageEnd=heading.end();for(int i=at+1;i<end&&lines.get(i).page()==heading.page();i++)pageEnd=lines.get(i).end();
                int excerptEnd=Math.min(pageEnd,heading.start()+1200);Evidence first=source(bid,heading,heading.start(),excerptEnd);proof.add(first);
                boolean body=body(first.quote().substring(Math.min(first.quote().length(),heading.text().length())));
                boolean excerpted=excerptEnd<pageEnd;int found=-1;
                if(!body)for(int i=at+1;i<end;i++) {
                    var line=lines.get(i);if(line.page()==heading.page())continue;
                    String content=line.page().text();int stop=line.end();for(int n=i+1;n<end&&lines.get(n).page()==line.page();n++)stop=lines.get(n).end();
                    String portion=content.substring(line.start(),Math.min(stop,line.start()+1200));
                    if(body(portion)){proof.add(source(bid,line,line.start(),Math.min(stop,line.start()+1200)));body=true;excerpted|=stop-line.start()>1200;found=i;break;}
                }
                // Material boundaries can be inside a page or extend across pages. Mark every omitted line explicitly.
                for(int i=at;i<end;i++) {
                    var line=lines.get(i);
                    if(proof.stream().noneMatch(e->e.page()==line.page().number()&&Objects.equals(e.locationType(),line.page().locationType())&&e.quote().contains(line.text())))excerpted=true;
                }
                List<Map<String,Object>> scopeProofs=proof.stream().map(e->Map.<String,Object>of("evidence",e,"scope",scope.currentEvidence(e))).toList();
                boolean current=scopeProofs.stream().allMatch(p->Boolean.TRUE.equals(((Map<?,?>)p.get("scope")).get("verifiedCurrent")));
                if(ClaimGrounding.indexOnly(first.quote()))body=false;
                choices.add(new Item(item,List.copyOf(proof),body,current,excerpted,!body?"已定位标题，尚未取得该材料独立正文":!current?"正文位置已找到，当前项目文书归属尚未证明":"已定位当前项目该份材料的独立正文片段",scopeProofs));
            }
            // Exact, complete duplicate copies are not conflicting versions. Never merge truncated bodies by their common prefix.
            Map<String,List<Item>> versions=new LinkedHashMap<>();int copy=0;
            for(Item choice:choices)if(choice.bodyLocated()&&choice.currentScope()) {
                String identity=choice.excerpted()?"location:"+(copy++):choice.evidences().stream().map(Evidence::quote).collect(java.util.stream.Collectors.joining("\n"));
                versions.computeIfAbsent(identity,ignored->new ArrayList<>()).add(choice);
            }
            List<Item> current=versions.values().stream().map(group->group.get(0)).toList();
            if(current.size()==1) {
                List<Item> copies=versions.values().iterator().next();Item first=current.get(0);
                out.add(new Item(first.item(),first.evidences(),first.bodyLocated(),first.currentScope(),first.excerpted(),first.reason(),
                    copies.stream().flatMap(i->i.scopeProofs().stream()).toList(),copies.stream().map(Item::evidences).toList()));
            }
            else if(current.size()>1)out.add(new Item(item,current.stream().flatMap(i->i.evidences().stream()).distinct().limit(4).toList(),false,true,true,"同名材料对应多处独立正文，需先确定适用版本，不能任取一份证明",current.stream().flatMap(i->i.scopeProofs().stream()).distinct().toList()));
            else out.add(choices.stream().filter(Item::bodyLocated).findFirst().orElseGet(()->choices.isEmpty()?new Item(item,List.of(),false,false,false,"尚未定位独立材料正文；未召回不代表原文件缺失"):choices.get(0)));
        }
        return new Recall(List.copyOf(out),List.copyOf(navigationSources));
    }
    private static boolean matches(String raw,String item){String name=TenderChecklistResolver.heading(raw).replaceFirst("^第[一二三四五六七八九十百0-9]+章[、:：.．]?\\s*","").replaceFirst("^(?:对本项目的|本项目的)","");return name.equals(item)||item.endsWith("重难点")&&name.equals(item+"识别");}
    private static boolean recognitionNarration(String text){return text.matches("(?s).*(?:以下是根据.{0,60}(?:思维导图|图片|您提供)|此\\s*Markdown\\s*(?:文档|结构)|如需将此结构.{0,60}(?:导出|转换)).*")
        ||text.matches("(?s).*图标[:：].*内容说明[:：].*")||text.matches("(?s).*结构说明[:：].*(?:子章节|子节点|思维导图|主节点|图标).*" );}
    private static boolean body(String text){return text.length()>=40&&text.matches("(?s).*[。；;].*")&&!recognitionNarration(text)&&!text.matches("(?s).*(?:无相关内容|暂无内容|本章略|本节略|内容待补充|尚未编制).*");}
    private static Evidence source(Document document,TenderChecklistResolver.Line line,int start,int end){String quote=line.page().text().substring(start,end);return new Evidence(document.id(),document.name(),line.page().number(),line.page().locationType(),quote,"text:"+start+"-"+end,line.page().parser()!=null&&line.page().parser().contains("OCR")?0:1);}
}
