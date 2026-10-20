package cn.bidcheck.document;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** One-based source location. Empty imageIndexes selects all OCR units at this location. */
public record OcrSelection(String locationType,int position,List<Integer> imageIndexes,List<String> reasons) {
    public OcrSelection {
        locationType=Objects.requireNonNull(locationType).strip().toUpperCase(Locale.ROOT);
        if(!Set.of("PAGE","PARAGRAPH","BLOCK","IMAGE").contains(locationType)||position<1||position>20_000)
            throw new IllegalArgumentException("OCR 选择位置无效。");
        if(imageIndexes!=null&&imageIndexes.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("OCR 图片选择无效。");
        imageIndexes=imageIndexes==null?List.of():imageIndexes.stream().distinct().sorted().toList();
        if(imageIndexes.size()>1000||imageIndexes.stream().anyMatch(i->i==null||i<1||i>1000)
            ||Set.of("PAGE","IMAGE").contains(locationType)&&imageIndexes.stream().anyMatch(i->i!=1))
            throw new IllegalArgumentException("OCR 图片选择无效。");
        reasons=reasons==null?List.of():reasons.stream().map(s->Objects.requireNonNull(s).strip()).filter(s->!s.isEmpty()).distinct().toList();
        if(reasons.size()>20||reasons.stream().anyMatch(s->s.length()>300||s.indexOf('\0')>=0))throw new IllegalArgumentException("OCR 选择说明超限。");
    }
    public static OcrSelection position(String type,int position,String reason){return new OcrSelection(type,position,List.of(),List.of(reason));}
}
