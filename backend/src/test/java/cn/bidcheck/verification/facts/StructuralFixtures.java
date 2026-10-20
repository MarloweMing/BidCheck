package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Entirely synthetic persons and identifiers. */
public final class StructuralFixtures {
    public static final String NAME="合成人甲", ID="110101199001010011", OTHER="110101199202020022", HASH="a".repeat(64);
    public record Fixture(List<Map<String,Object>> cells,List<Map<String,Object>> sources,String text) {}
    public static Fixture row(int page,boolean ocr,boolean labelledIdentity,String id,String months){
        String[] headings={"姓名","身份证号码","缴费单位","缴费月份"};String[] values={NAME,id,"合成公司",months};
        String text=String.join("\t",headings)+"\n"+String.join("\t",values)+(labelledIdentity?"\n"+NAME+" 身份证号码："+id:"");
        var source=of("id","S"+page,"documentHash",HASH,"documentVersion",1,"evidence",new Evidence("d","合成.pdf",page,"PAGE",text,"chars:0",ocr?0:1));
        List<Map<String,Object>> cells=new ArrayList<>();for(int column=1;column<=4;column++)for(int r=1;r<=2;r++){
            String cid="p"+page+"r"+r+"c"+column;var cell=of("kind","TABLE_CELL","cellId",cid,"spanId",cid,"documentId","d","documentHash",HASH,"documentVersion",1,"locationType","PAGE","location",page,"tableId","t"+page,"row",r,"column",column,"rowSpan",1,"columnSpan",1,"text",r==1?headings[column-1]:values[column-1],"source",ocr?"OCR_STRUCTURED":"OOXML_NATIVE","header",r==1,"headerCellIds",r==1?List.of():List.of("p"+page+"r1c"+column));
            if(ocr)cell.putAll(of("frameId","frame"+page,"imageHash","b".repeat(64),"imageWidth",1000,"imageHeight",1000,"bbox",List.of(column*100,r*100,column*100+80,r*100+60)));
            cells.add(cell);
        }return new Fixture(cells,List.of(source),text);
    }
    public static Fixture join(Fixture a,Fixture b){List<Map<String,Object>> cells=new ArrayList<>(a.cells);cells.addAll(b.cells);List<Map<String,Object>> sources=new ArrayList<>(a.sources);sources.addAll(b.sources);return new Fixture(cells,sources,a.text+"\n"+b.text);}
    public static List<String> ids(Fixture f){return f.cells.stream().filter(c->Integer.valueOf(2).equals(c.get("row"))).map(c->text(c,"cellId")).toList();}
}
