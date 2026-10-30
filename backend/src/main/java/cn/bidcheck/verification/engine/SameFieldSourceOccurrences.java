package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Counts original value locations, never extraction records or source aliases.
 * One source can contain two occurrences; overlapping sources can describe only one. */
final class SameFieldSourceOccurrences {
    static final String VERSION="same-field-original-occurrence-1";
    private static final Pattern RANGE=Pattern.compile("chars:(\\d+)-(\\d+)");
    private SameFieldSourceOccurrences(){}

    record Result(int distinctCount,Map<String,Object> audit){}

    static Result inspect(List<Fact> facts,EvidenceCorpus corpus){
        Map<String,Occurrence> occurrences=new LinkedHashMap<>();Set<String> unlocated=new LinkedHashSet<>();
        for(Fact fact:facts){boolean located=false;
            for(String sourceId:fact.sourceIds()){
                Source source=corpus.byId.get(sourceId);if(source==null)continue;
                Document document=corpus.documents.stream().filter(d->d.id().equals(source.evidence().documentId())).findFirst().orElse(null);
                if(document==null||!source.documentHash().isBlank()&&!source.documentHash().equals(document.sha256())
                        ||source.documentVersion()>0&&source.documentVersion()!=document.version())continue;
                Page page=corpus.pages.get(EvidenceCorpus.pageKey(document.id(),source.evidence().page(),source.evidence().locationType()));
                if(page==null)continue;String text=Objects.toString(page.text(),""),quote=Objects.toString(source.evidence().quote(),"");
                int base=sourceOffset(source,text,quote);if(base<0)continue;
                List<int[]> spans=spans(quote,fact.value());
                // Some validated facts retain a normalized value and its original spelling.
                // Only an exact original value is usable here; no inferred or fuzzy span.
                if(spans.isEmpty()&&!clean(fact.rawValue()).equals(clean(fact.value())))spans=spans(quote,fact.rawValue());
                for(int[] span:spans){int start=base+span[0],end=base+span[1];
                    String key=identity(document.id()+"\0"+document.sha256()+"\0"+document.version()+"\0"+page.locationType()+"\0"+page.number()+"\0"+start+"\0"+end);
                    Occurrence occurrence=occurrences.computeIfAbsent(key,ignored->new Occurrence(document,page,start,end,text.substring(start,end)));
                    occurrence.factIds.add(fact.id());occurrence.sourceIds.add(source.id());occurrence.values.add(clean(fact.value()));located=true;
                }
            }
            if(!located)unlocated.add(fact.id());
        }
        return new Result(occurrences.size(),linked("version",VERSION,"inputFactCount",facts.size(),"distinctOccurrenceCount",occurrences.size(),
                "occurrences",occurrences.values().stream().map(Occurrence::audit).toList(),"unlocatedFactIds",new ArrayList<>(unlocated),
                "identityBasis","DOCUMENT_VERSION_LOCATION_AND_EXACT_VALUE_SPAN","fullDocumentCoverageProven",false));
    }

    private static int sourceOffset(Source source,String text,String quote){
        if(quote.isBlank()||source.evidence().anchor().startsWith("image:"))return -1;
        Matcher range=RANGE.matcher(source.evidence().anchor());
        if(range.matches())try{int start=Integer.parseInt(range.group(1)),end=Integer.parseInt(range.group(2));
            return start>=0&&end>=start&&end<=text.length()&&end-start==quote.length()&&text.regionMatches(start,quote,0,quote.length())?start:-1;
        }catch(NumberFormatException ignored){return -1;}
        int offset=text.indexOf(quote);
        // Legacy anchors can be located only when the complete quote is unique on that page.
        return offset>=0&&text.indexOf(quote,offset+1)<0?offset:-1;
    }

    private static List<int[]> spans(String text,String raw){
        String value=clean(raw);if(value.isBlank()||value.length()>512)return List.of();
        StringBuilder expression=new StringBuilder();
        if(asciiIdentifier(value.charAt(0)))expression.append("(?<![A-Za-z0-9])");
        for(int i=0;i<value.length();i++){if(i>0)expression.append("\\s*");expression.append(Pattern.quote(value.substring(i,i+1)));}
        if(asciiIdentifier(value.charAt(value.length()-1)))expression.append("(?![A-Za-z0-9])");
        Matcher matcher=Pattern.compile(expression.toString()).matcher(text);List<int[]> spans=new ArrayList<>();
        while(matcher.find()){
            // Identifier OCR can insert horizontal spaces. An 18-character value
            // embedded in a longer spaced identifier is still only a substring.
            if(value.matches("[A-Za-z0-9]{8,}")&&(identifierContinues(text,matcher.start()-1,-1)||identifierContinues(text,matcher.end(),1)))continue;
            spans.add(new int[]{matcher.start(),matcher.end()});
        }return spans;
    }
    private static boolean identifierContinues(String text,int position,int direction){
        while(position>=0&&position<text.length()&&(text.charAt(position)=='\t'||Character.getType(text.charAt(position))==Character.SPACE_SEPARATOR))position+=direction;
        return position>=0&&position<text.length()&&asciiIdentifier(text.charAt(position));
    }
    private static boolean asciiIdentifier(char value){return value>='0'&&value<='9'||value>='A'&&value<='Z'||value>='a'&&value<='z';}

    private static final class Occurrence{
        final Document document;final Page page;final int start,end;final String quote;
        final Set<String> sourceIds=new LinkedHashSet<>(),factIds=new LinkedHashSet<>(),values=new LinkedHashSet<>();
        Occurrence(Document document,Page page,int start,int end,String quote){this.document=document;this.page=page;this.start=start;this.end=end;this.quote=quote;}
        Map<String,Object> audit(){return linked("documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),
                "page",page.number(),"locationType",page.locationType(),"anchor","chars:"+start+"-"+end,"quote",quote,
                "values",new ArrayList<>(values),"factIds",new ArrayList<>(factIds),"sourceIds",new ArrayList<>(sourceIds));}
    }
}
