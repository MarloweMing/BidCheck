package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import java.util.*;

/** Local, per-item body recall. Each excerpt retains its document location and its own section boundary. */
final class ChecklistBodyRecall {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Item {
        private final String item;
        private final List<Evidence> evidences;
        private final boolean bodyLocated;
        private final boolean currentScope;
        private final boolean excerpted;
        private final String reason;
        private final List<Map<String,Object>> scopeProofs;
        private final List<List<Evidence>> equivalentCopies;
        @com.fasterxml.jackson.annotation.JsonCreator
        Item(@com.fasterxml.jackson.annotation.JsonProperty("item") String item, @com.fasterxml.jackson.annotation.JsonProperty("evidences") List<Evidence> evidences, @com.fasterxml.jackson.annotation.JsonProperty("bodyLocated") boolean bodyLocated, @com.fasterxml.jackson.annotation.JsonProperty("currentScope") boolean currentScope, @com.fasterxml.jackson.annotation.JsonProperty("excerpted") boolean excerpted, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("scopeProofs") List<Map<String,Object>> scopeProofs, @com.fasterxml.jackson.annotation.JsonProperty("equivalentCopies") List<List<Evidence>> equivalentCopies) { this.item = item; this.evidences = evidences; this.bodyLocated = bodyLocated; this.currentScope = currentScope; this.excerpted = excerpted; this.reason = reason; this.scopeProofs = scopeProofs; this.equivalentCopies = equivalentCopies; }
        public String item() { return item; }
        public List<Evidence> evidences() { return evidences; }
        public boolean bodyLocated() { return bodyLocated; }
        public boolean currentScope() { return currentScope; }
        public boolean excerpted() { return excerpted; }
        public String reason() { return reason; }
        public List<Map<String,Object>> scopeProofs() { return scopeProofs; }
        public List<List<Evidence>> equivalentCopies() { return equivalentCopies; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Item that = (Item) other; return java.util.Objects.equals(this.item, that.item) && java.util.Objects.equals(this.evidences, that.evidences) && java.util.Objects.equals(this.bodyLocated, that.bodyLocated) && java.util.Objects.equals(this.currentScope, that.currentScope) && java.util.Objects.equals(this.excerpted, that.excerpted) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.scopeProofs, that.scopeProofs) && java.util.Objects.equals(this.equivalentCopies, that.equivalentCopies); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.item); hash = 31 * hash + java.util.Objects.hashCode(this.evidences); hash = 31 * hash + java.util.Objects.hashCode(this.bodyLocated); hash = 31 * hash + java.util.Objects.hashCode(this.currentScope); hash = 31 * hash + java.util.Objects.hashCode(this.excerpted); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.scopeProofs); hash = 31 * hash + java.util.Objects.hashCode(this.equivalentCopies); return hash; }
        @Override public String toString() { return "Item[" + "item=" + item + ", evidences=" + evidences + ", bodyLocated=" + bodyLocated + ", currentScope=" + currentScope + ", excerpted=" + excerpted + ", reason=" + reason + ", scopeProofs=" + scopeProofs + ", equivalentCopies=" + equivalentCopies + "]"; }

        Item(String item,List<Evidence> evidences,boolean bodyLocated,boolean currentScope,boolean excerpted,String reason){this(item,evidences,bodyLocated,currentScope,excerpted,reason,cn.bidcheck.compat.Java8Api.listOf());}
        Item(String item,List<Evidence> evidences,boolean bodyLocated,boolean currentScope,boolean excerpted,String reason,List<Map<String,Object>> scopeProofs){this(item,evidences,bodyLocated,currentScope,excerpted,reason,scopeProofs,cn.bidcheck.compat.Java8Api.listOf(evidences));}
        Map<String,Object> map(){return cn.bidcheck.compat.Java8Api.mapOf("item", item, "evidences", evidences, "bodyLocated", bodyLocated, "currentScope", currentScope, "excerpted", excerpted, "reason", reason, "scopeProofs", scopeProofs, "equivalentCopies", equivalentCopies);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Recall {
        private final List<Item> items;
        private final List<Map<String,Object>> navigationSources;
        @com.fasterxml.jackson.annotation.JsonCreator
        Recall(@com.fasterxml.jackson.annotation.JsonProperty("items") List<Item> items, @com.fasterxml.jackson.annotation.JsonProperty("navigationSources") List<Map<String,Object>> navigationSources) { this.items = items; this.navigationSources = navigationSources; }
        public List<Item> items() { return items; }
        public List<Map<String,Object>> navigationSources() { return navigationSources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Recall that = (Recall) other; return java.util.Objects.equals(this.items, that.items) && java.util.Objects.equals(this.navigationSources, that.navigationSources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.items); hash = 31 * hash + java.util.Objects.hashCode(this.navigationSources); return hash; }
        @Override public String toString() { return "Recall[" + "items=" + items + ", navigationSources=" + navigationSources + "]"; }

        Recall(List<Item> items){this(items,cn.bidcheck.compat.Java8Api.listOf());}
        Map<String,Object> audit(){return cn.bidcheck.compat.Java8Api.mapOf("method", "PER_ITEM_FROZEN_BODY_RECALL", "items", cn.bidcheck.compat.Java8Api.toList(items.stream().map(Item::map)), "bodyLocatedCount", items.stream().filter(Item::bodyLocated).count(), "currentBodyCount", items.stream().filter(i->i.bodyLocated()&&i.currentScope()).count(), "navigationSources", navigationSources, "boundary", "按每项独立标题及所在正文区间定位；片段用于材料正文存在核验，不代表全文、签章或所有内容均已验证。");}
        List<Evidence> evidences(){return cn.bidcheck.compat.Java8Api.toList(items.stream().flatMap(i->i.evidences().stream()).distinct());}
    }
    static Recall recall(Map<String,Object> inventory,Document bid) {
        List<?> required=inventory.get("items") instanceof List<?>?((List<?>) (inventory.get("items"))):cn.bidcheck.compat.Java8Api.listOf();
        if(required.isEmpty())return new Recall(cn.bidcheck.compat.Java8Api.listOf());
        List<TenderChecklistResolver.Line> allLines=TenderChecklistResolver.lines(bid);List<Item> out=new ArrayList<>();
        Map<Page,Integer> navigationAppendices=new IdentityHashMap<>();List<Map<String,Object>> navigationSources=new ArrayList<>();
        if(bid!=null)for(Page page:bid.pages()) {
            String text=Objects.toString(page.text(),"");int marker=text.indexOf("【PDF识别文字】");
            if(marker>=0&&recognitionNarration(text.substring(marker))) {
                navigationAppendices.put(page,marker);
                cn.bidcheck.semantic.TenderChecklistResolver.Line line=new TenderChecklistResolver.Line(0,page,text.substring(marker),marker,text.length());
                if(cn.bidcheck.compat.Java8Extras.lines(text).anyMatch(raw->required.stream().map(Object::toString).anyMatch(item->matches(raw,item))))
                    navigationSources.add(cn.bidcheck.compat.Java8Api.mapOf("evidence", source(bid,line,marker,Math.min(text.length(),marker+1200)), "reason", "附加OCR内容含图解或识别生成说明，仅用于导航；不能作为独立章节正文或额外版本。冻结原文保留。", "classification", "OCR_RECONSTRUCTION_NAVIGATION"));
            }
        }
        // Keep the literal boundary marker when it is its own line. It uniquely anchors a native excerpt
        // when the OCR append repeats that excerpt word for word, without admitting the generated append body.
        List<TenderChecklistResolver.Line> lines=cn.bidcheck.compat.Java8Api.toList(allLines.stream().filter(line->line.start()<navigationAppendices.getOrDefault(line.page(),Integer.MAX_VALUE)
            ||line.start()==navigationAppendices.getOrDefault(line.page(),Integer.MAX_VALUE)&&cn.bidcheck.compat.Java8Api.strip(line.text()).equals("【PDF识别文字】")));
        cn.bidcheck.verification.engine.DocumentScopeProof.Resolver scope=DocumentScopeProof.forDocument(bid);
        for(Object value:required) {
            String item=String.valueOf(value);List<Item> choices=new ArrayList<>();
            for(int at=0;at<lines.size();at++)if(matches(lines.get(at).text(),item)) {
                if(at>0&&matches(lines.get(at-1).text(),item))continue;
                int end=lines.size();
                for(int i=at+1;i<lines.size();i++) {
                    String text=lines.get(i).text();
                    if(matches(text,item)&&i==at+1)continue;
                    if(TenderChecklistResolver.isMaterialHeading(text)||cn.bidcheck.compat.Java8Api.strip(text).matches("第[一二三四五六七八九十百0-9]+章.*")
                        ||required.stream().map(Object::toString).anyMatch(name->matches(text,name))){end=i;break;}
                }
                cn.bidcheck.semantic.TenderChecklistResolver.Line heading=lines.get(at);List<Evidence> proof=new ArrayList<>();String text=heading.page().text();
                int pageEnd=heading.end();for(int i=at+1;i<end&&lines.get(i).page()==heading.page();i++)pageEnd=lines.get(i).end();
                int excerptEnd=Math.min(pageEnd,heading.start()+1200);Evidence first=source(bid,heading,heading.start(),excerptEnd);proof.add(first);
                boolean body=body(first.quote().substring(Math.min(first.quote().length(),heading.text().length())));
                boolean excerpted=excerptEnd<pageEnd;int found=-1;
                if(!body)for(int i=at+1;i<end;i++) {
                    cn.bidcheck.semantic.TenderChecklistResolver.Line line=lines.get(i);if(line.page()==heading.page())continue;
                    String content=line.page().text();int stop=line.end();for(int n=i+1;n<end&&lines.get(n).page()==line.page();n++)stop=lines.get(n).end();
                    String portion=content.substring(line.start(),Math.min(stop,line.start()+1200));
                    if(body(portion)){proof.add(source(bid,line,line.start(),Math.min(stop,line.start()+1200)));body=true;excerpted|=stop-line.start()>1200;found=i;break;}
                }
                // Material boundaries can be inside a page or extend across pages. Mark every omitted line explicitly.
                for(int i=at;i<end;i++) {
                    cn.bidcheck.semantic.TenderChecklistResolver.Line line=lines.get(i);
                    if(proof.stream().noneMatch(e->e.page()==line.page().number()&&Objects.equals(e.locationType(),line.page().locationType())&&e.quote().contains(line.text())))excerpted=true;
                }
                List<Map<String,Object>> scopeProofs=cn.bidcheck.compat.Java8Api.toList(proof.stream().map(e->cn.bidcheck.compat.Java8Api.<String, Object>mapOf("evidence", e, "scope", scope.currentEvidence(e))));
                boolean current=scopeProofs.stream().allMatch(p->Boolean.TRUE.equals(((Map<?,?>)p.get("scope")).get("verifiedCurrent")));
                if(ClaimGrounding.indexOnly(first.quote()))body=false;
                choices.add(new Item(item,cn.bidcheck.compat.Java8Api.listCopyOf(proof),body,current,excerpted,!body?"已定位标题，尚未取得该材料独立正文":!current?"正文位置已找到，当前项目文书归属尚未证明":"已定位当前项目该份材料的独立正文片段",scopeProofs));
            }
            // Exact, complete duplicate copies are not conflicting versions. Never merge truncated bodies by their common prefix.
            Map<String,List<Item>> versions=new LinkedHashMap<>();int copy=0;
            for(Item choice:choices)if(choice.bodyLocated()&&choice.currentScope()) {
                String identity=choice.excerpted()?"location:"+(copy++):choice.evidences().stream().map(Evidence::quote).collect(java.util.stream.Collectors.joining("\n"));
                versions.computeIfAbsent(identity,ignored->new ArrayList<>()).add(choice);
            }
            List<Item> current=cn.bidcheck.compat.Java8Api.toList(versions.values().stream().map(group->group.get(0)));
            if(current.size()==1) {
                List<Item> copies=versions.values().iterator().next();Item first=current.get(0);
                out.add(new Item(first.item(),first.evidences(),first.bodyLocated(),first.currentScope(),first.excerpted(),first.reason(),
                    cn.bidcheck.compat.Java8Api.toList(copies.stream().flatMap(i->i.scopeProofs().stream())),cn.bidcheck.compat.Java8Api.toList(copies.stream().map(Item::evidences))));
            }
            else if(current.size()>1)out.add(new Item(item,cn.bidcheck.compat.Java8Api.toList(current.stream().flatMap(i->i.evidences().stream()).distinct().limit(4)),false,true,true,"同名材料对应多处独立正文，需先确定适用版本，不能任取一份证明",cn.bidcheck.compat.Java8Api.toList(current.stream().flatMap(i->i.scopeProofs().stream()).distinct())));
            else out.add(choices.stream().filter(Item::bodyLocated).findFirst().orElseGet(()->choices.isEmpty()?new Item(item,cn.bidcheck.compat.Java8Api.listOf(),false,false,false,"尚未定位独立材料正文；未召回不代表原文件缺失"):choices.get(0)));
        }
        return new Recall(cn.bidcheck.compat.Java8Api.listCopyOf(out),cn.bidcheck.compat.Java8Api.listCopyOf(navigationSources));
    }
    private static boolean matches(String raw,String item){String name=TenderChecklistResolver.heading(raw).replaceFirst("^第[一二三四五六七八九十百0-9]+章[、:：.．]?\\s*","").replaceFirst("^(?:对本项目的|本项目的)","");return name.equals(item)||item.endsWith("重难点")&&name.equals(item+"识别");}
    private static boolean recognitionNarration(String text){return text.matches("(?s).*(?:以下是根据.{0,60}(?:思维导图|图片|您提供)|此\\s*Markdown\\s*(?:文档|结构)|如需将此结构.{0,60}(?:导出|转换)).*")
        ||text.matches("(?s).*图标[:：].*内容说明[:：].*")||text.matches("(?s).*结构说明[:：].*(?:子章节|子节点|思维导图|主节点|图标).*" );}
    private static boolean body(String text){return text.length()>=40&&text.matches("(?s).*[。；;].*")&&!recognitionNarration(text)&&!text.matches("(?s).*(?:无相关内容|暂无内容|本章略|本节略|内容待补充|尚未编制).*");}
    private static Evidence source(Document document,TenderChecklistResolver.Line line,int start,int end){String quote=line.page().text().substring(start,end);return new Evidence(document.id(),document.name(),line.page().number(),line.page().locationType(),quote,"text:"+start+"-"+end,line.page().parser()!=null&&line.page().parser().contains("OCR")?0:1);}
}
