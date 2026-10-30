package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.engine.ScoringSourceSegments;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Deterministic migration of generator projections, always retaining individual original source locations. */
final class RequirementSourceRepair {
    static final String VERSION="requirement-source-repair-2-complete-legacy-scoring";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    record Resolution(RequirementScope.Selection scope,RequirementSourceContext.Context context) {}
    static Resolution resolve(Checkpoint cp,String original,Map<String,Object> input){
        var scope=RequirementScope.resolve(cp,original);var context=RequirementSourceContext.resolve(cp,original,input);
        if(scope.resolved())return new Resolution(scope,context);
        if(cp.source()==null||!Objects.equals(cp.source().quote(),original))return unresolved(scope,context,"SOURCE_QUOTE_NOT_ANCHORED");
        if("scoring".equals(cp.field())&&"SEMANTIC_RESPONSE".equals(cp.type())){
            Resolution legacy=legacyScoringProjection(cp,original,context,input);if(legacy!=null)return legacy;
            var matches=ScoringSourceSegments.certificates(original).stream().filter(c->c.expected().equals(cp.expected())||c.legacyExpected().equals(cp.expected())&&Objects.equals(cp.title(),cp.expected())).toList();
            if(matches.size()==1&&!context.identity().isEmpty()){
                var selected=matches.get(0);var clause=selected.clause();List<Map<String,Object>> sources=new ArrayList<>(context.sources());
                sources.add(relativeSource(context,"SCOPED_OBLIGATION",clause.quote(),clause.start(),clause.end()));
                if(selected.proof()!=null){var proof=selected.proof();sources.add(relativeSource(context,"SCOPED_PROOF",proof.quote(),proof.start(),proof.end()));}
                scope=new RequirementScope.Selection(original,clause.quote(),cp.expected(),clause.start(),clause.end(),"CERTIFICATE_BRANCH_SOURCE_RESTORED","");
                return new Resolution(scope,derived(context,sources,"NUMBERED_CERTIFICATE_AND_SHARED_PROOF"));
            }
            Resolution blocks=contiguousScoringSources(cp,original,scope,context,input);if(blocks!=null)return blocks;
            return unresolved(scope,context,"SCORING_PROJECTION_OR_CROSS_BLOCK_SOURCE_MISMATCH");
        }
        if("conditionalMaterial".equals(cp.field())&&"SEMANTIC_RESPONSE".equals(cp.type())){
            Resolution book=conditionalBook(cp,original,scope,context,input);if(book!=null)return book;
            return unresolved(scope,context,"CONDITIONAL_MATERIAL_SOURCE_MISMATCH");
        }
        return unresolved(scope,context,"EXPECTED_TO_SOURCE_NOT_UNIQUE");
    }
    /** Recover only byte-for-byte projections emitted by the old generator. The original
     * checkpoint is immutable: its ID/title/reviewed expected remain in the repair audit.
     * A manual edit, a different source, or an ambiguous continuation is not migrated. */
    private static Resolution legacyScoringProjection(Checkpoint cp,String original,RequirementSourceContext.Context context,Map<String,Object> input){
        if(context.identity().isEmpty())return null;Document document=document(cp,input);
        if(document==null||!generatedIdentity(cp,document))return null;
        String oldExpected=ScoringSourceSegments.legacyTitleAwardExpected(original);
        List<Map<String,Object>> sources=new ArrayList<>(context.sources());
        sources.add(relativeSource(context,"SCOPED_OBLIGATION",original,0,original.length()));
        String reason="",restored=original;List<Map<String,Object>> scoringTiers=new ArrayList<>();
        if(!oldExpected.isBlank()&&oldExpected.equals(cp.expected())&&ScoringSourceSegments.legacyTitle("响应评分：",original).equals(cp.title())){
            reason="EXACT_LEGACY_TITLE_AWARD_PROJECTION";
        }else if(ScoringSourceSegments.legacyTitle("方案最高档要求：",original).equals(cp.title())){
            int at=-1;for(int i=0;i<document.pages().size();i++){
                Page page=document.pages().get(i);if(page.number()==cp.source().page()&&page.locationType().equals(cp.source().locationType())){if(at>=0)return null;at=i;}
            }
            if(at<0||at+1>=document.pages().size())return null;Page first=document.pages().get(at),next=document.pages().get(at+1);
            // The legacy generator used full adjacent table blocks. Do not attach a
            // same-looking rubric found elsewhere in the document or beyond a paragraph.
            if(!"BLOCK".equals(first.locationType())||!first.locationType().equals(next.locationType())||next.number()!=first.number()+1
                    ||!first.text().equals(original))return null;
            if(document.pages().stream().filter(p->p.number()==next.number()&&p.locationType().equals(next.locationType())).count()!=1)return null;
            var rubric=ScoringSourceSegments.rubric(next.text());if(rubric.isEmpty())return null;
            var parsed=rubric.get();
            String projected=ScoringSourceSegments.body(original)+parsed.legacyTopDescription()+"。";
            if(!projected.equals(cp.expected()))return null;
            sources.add(source(document,next,0,next.text().length(),"SCOPED_SCORING_CONTINUATION"));
            for(var tier:parsed.tiers())scoringTiers.add(source(document,next,tier.start(),tier.end(),"SCORING_TIER"));
            restored=original+"\n"+next.text();reason="EXACT_LEGACY_TOP_TIER_WITH_ADJACENT_RUBRIC";
        }else return null;
        var scope=new RequirementScope.Selection(original,original,cp.expected(),0,original.length(),"LEGACY_SCORING_PROJECTION_RESTORED","");
        Map<String,Object> identity=new LinkedHashMap<>(context.identity());
        identity.put("sourceRepair",linked("version",VERSION,"stage","EXPECTED_TO_SOURCE","status","RESTORED","reasonCode",reason,
                "checkpointId",cp.id(),"previousExpected",cp.expected(),"previousTitle",cp.title(),
                "previousExpectedHash",VerificationTypes.identity(cp.expected()),"restoredSourceHash",VerificationTypes.identity(restored),
                "generatorIdentityVerified",true,"sourceCount",sources.stream().map(s->s.get("location")).distinct().count(),
                "businessPolicy","PRESERVE_ALL_SCORE_TIERS_AND_COMMON_PROOF"));
        identity.put("scoringSourceVersion",ScoringSourceSegments.VERSION);
        identity.put("scoringTiers",List.copyOf(scoringTiers));
        List<String> repeated=ScoringSourceSegments.repeatedPlanItems(original);
        if(!repeated.isEmpty())identity.put("sourceWarnings",List.of(linked("code","REPEATED_SOURCE_LIST_ITEMS","items",repeated,
                "message","招标原文清单存在重复项，保留原文及声明数量，不自动删项或重新解释评分总数")));
        return new Resolution(scope,new RequirementSourceContext.Context(restored,identity,List.copyOf(sources)));
    }
    private static boolean generatedIdentity(Checkpoint cp,Document document){
        if(!"响应检查".equals(cp.category())||!"RESPONDS".equals(cp.operator())||!Objects.toString(cp.unit(),"").isEmpty()||!cp.required())return false;
        String key=cp.category()+"|"+cp.type()+"|"+cp.field()+"|"+cp.operator()+"|"+cp.expected()+"|"+cp.unit();
        String id=UUID.nameUUIDFromBytes((document.id()+"|"+document.version()+"|"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        if(id.equals(cp.id()))return true;
        // Reconciler-owned IDs encode the full original generated definition rather
        // than the extractor key. Reproduce that stable shape exactly, including source.
        Checkpoint original=new Checkpoint("",cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),false,true,cp.source());
        try{return UUID.nameUUIDFromBytes(("checkpoint:"+document.id()+":"+JSON.writeValueAsString(original)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().equals(cp.id());}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return false;}
    }
    private static Resolution conditionalBook(Checkpoint cp,String original,RequirementScope.Selection scope,RequirementSourceContext.Context context,Map<String,Object> input){
        if(context.identity().isEmpty()||!Objects.equals(cp.title(),"适用时提供："+original.strip()))return null;
        String expected=cp.expected(),prefix=original.strip()+"；按招标文件确认适用条件。";
        String condition=expected.startsWith(prefix)?expected.substring(prefix.length()):expected.startsWith(original.strip()+"。")?expected.substring(original.strip().length()+1):"";
        if(condition.isBlank()||!condition.matches("[^。；;\\n]{1,120}(?:无需|无须|不需|免于)[^。；;\\n]{0,40}"))return null;
        Document document=document(cp,input);if(document==null)return null;
        List<Map<String,Object>> matches=new ArrayList<>();Pattern book=Pattern.compile("《([^》]+)》\\s*[（(]([^）)]*)[）)]");
        for(Page page:document.pages()){Matcher m=book.matcher(page.text());while(m.find())
            if(bookName(m.group(1)).equals(bookName(original.strip()))&&m.group(2).equals(condition))matches.add(source(document,page,m.start(),m.end(),"SCOPED_BOOK_CONDITION"));}
        if(matches.size()!=1)return null;
        Map<String,Object> location=matches.get(0);String quote=str(location,"sourceQuote");
        scope=new RequirementScope.Selection(original,quote,expected,((Number)location.get("start")).intValue(),((Number)location.get("end")).intValue(),"BOOK_CONDITION_SOURCE_RESTORED","",location);
        List<Map<String,Object>> sources=new ArrayList<>(context.sources());sources.add(location);
        var restored=derived(new RequirementSourceContext.Context(context.bindingSource()+"\n"+quote,context.identity(),context.sources()),sources,"EXACT_NAMED_BOOK_CONDITION");
        return new Resolution(scope,restored);
    }
    /** New candidates retain all rubric tiers as exact consecutive block text; no highest-tier projection. */
    private static Resolution contiguousScoringSources(Checkpoint cp,String original,RequirementScope.Selection scope,RequirementSourceContext.Context context,Map<String,Object> input){
        if(context.identity().isEmpty()||!cp.expected().startsWith(original+"\n"))return null;
        Document document=document(cp,input);if(document==null)return null;int first=-1;
        for(int i=0;i<document.pages().size();i++)if(document.pages().get(i).number()==cp.source().page()&&document.pages().get(i).locationType().equals(cp.source().locationType()))first=i;
        if(first<0)return null;StringBuilder reconstructed=new StringBuilder(original);List<Map<String,Object>> sources=new ArrayList<>(context.sources());
        sources.add(relativeSource(context,"SCOPED_OBLIGATION",original,0,original.length()));
        for(int i=first+1;i<document.pages().size()&&i<=first+32;i++){
            Page page=document.pages().get(i);String text=page.text();if(text.isBlank())continue;
            if(reconstructed.length()+text.length()>32000)return null;
            String remaining=cp.expected().substring(reconstructed.length()+1);int from=0,to=text.length();
            if(!remaining.startsWith(text)){
                // The final physical page may continue with the next scoring item. Only an
                // exact unique leading span ending on a line boundary is eligible; never skip a middle block.
                int offset=RequirementContextBindings.uniqueOffset(text,remaining);
                if(offset<0||!text.substring(0,offset).isBlank())return null;
                int end=offset+remaining.length();if(end<text.length()&&text.charAt(end)!='\r'&&text.charAt(end)!='\n')return null;
                from=offset;to=end;
            }
            String selected=text.substring(from,to);reconstructed.append('\n').append(selected);sources.add(source(document,page,from,to,"SCOPED_SCORING_CONTINUATION"));
            if(cp.expected().equals(reconstructed.toString())){
                scope=new RequirementScope.Selection(original,original,cp.expected(),0,original.length(),"CONTIGUOUS_SCORING_SOURCES","");
                return new Resolution(scope,derived(new RequirementSourceContext.Context(reconstructed.toString(),context.identity(),context.sources()),sources,"EXACT_CONTIGUOUS_SCORING_SOURCES"));
            }
            if(!cp.expected().startsWith(reconstructed.toString()+"\n"))return null;
        }
        return null;
    }
    private static String bookName(String text){return text.replaceAll("[（(]负责人[）)]","").replaceAll("\\s+","");}
    private static Document document(Checkpoint cp,Map<String,Object> input){List<Document> documents=new ArrayList<>();if(input.get("tenderDocuments") instanceof List<?> list)for(Object row:list)if(row instanceof Document doc&&"TENDER".equals(doc.role())&&doc.id().equals(cp.source().documentId()))documents.add(doc);return documents.size()==1?documents.get(0):null;}
    private static Map<String,Object> source(Document doc,Page page,int start,int end,String kind){return linked("kind",kind,"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"locationType",page.locationType(),"location",page.number(),"start",start,"end",end,"offsetBasis","DOCUMENT_TEXT_UTF16","sourceQuote",page.text().substring(start,end));}
    private static Map<String,Object> relativeSource(RequirementSourceContext.Context context,String kind,String quote,int start,int end){
        Map<String,Object> result=new LinkedHashMap<>(context.identity());int base=context.identity().get("start") instanceof Number n?n.intValue():0;
        result.putAll(linked("kind",kind,"sourceQuote",quote,"start",base+start,"end",base+end,"offsetBasis",context.identity().isEmpty()?"SOURCE_QUOTE_UTF16":"DOCUMENT_TEXT_UTF16"));return result;
    }
    private static RequirementSourceContext.Context derived(RequirementSourceContext.Context original,List<Map<String,Object>> sources,String reason){Map<String,Object> identity=new LinkedHashMap<>(original.identity());identity.put("sourceRepair",linked("version",VERSION,"stage","EXPECTED_TO_SOURCE","status","RESTORED","reasonCode",reason));identity.put("scoringSourceVersion",ScoringSourceSegments.VERSION);return new RequirementSourceContext.Context(original.bindingSource(),identity,List.copyOf(sources));}
    private static Resolution unresolved(RequirementScope.Selection scope,RequirementSourceContext.Context context,String reason){Map<String,Object> identity=new LinkedHashMap<>(context.identity());identity.put("sourceRepair",linked("version",VERSION,"stage","EXPECTED_TO_SOURCE","status","UNRESOLVED","reasonCode",reason));return new Resolution(scope,new RequirementSourceContext.Context(context.bindingSource(),identity,context.sources()));}
}
