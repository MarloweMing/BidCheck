package cn.bidcheck.document;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** One-based source location. Empty imageIndexes selects all OCR units at this location. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class OcrSelection {
        private final String locationType;
        private final int position;
        private final List<Integer> imageIndexes;
        private final List<String> reasons;
        public String locationType() { return locationType; }
        public int position() { return position; }
        public List<Integer> imageIndexes() { return imageIndexes; }
        public List<String> reasons() { return reasons; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; OcrSelection that = (OcrSelection) other; return java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.position, that.position) && java.util.Objects.equals(this.imageIndexes, that.imageIndexes) && java.util.Objects.equals(this.reasons, that.reasons); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.position); hash = 31 * hash + java.util.Objects.hashCode(this.imageIndexes); hash = 31 * hash + java.util.Objects.hashCode(this.reasons); return hash; }
        @Override public String toString() { return "OcrSelection[" + "locationType=" + locationType + ", position=" + position + ", imageIndexes=" + imageIndexes + ", reasons=" + reasons + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public OcrSelection(@com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("position") int position, @com.fasterxml.jackson.annotation.JsonProperty("imageIndexes") List<Integer> imageIndexes, @com.fasterxml.jackson.annotation.JsonProperty("reasons") List<String> reasons) {
        locationType=cn.bidcheck.compat.Java8Api.strip(Objects.requireNonNull(locationType)).toUpperCase(Locale.ROOT);
        if(!cn.bidcheck.compat.Java8Api.setOf("PAGE", "PARAGRAPH", "BLOCK", "IMAGE").contains(locationType)||position<1||position>20_000)
            throw new IllegalArgumentException("OCR 选择位置无效。");
        if(imageIndexes!=null&&imageIndexes.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("OCR 图片选择无效。");
        imageIndexes=imageIndexes==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.toList(imageIndexes.stream().distinct().sorted());
        if(imageIndexes.size()>1000||imageIndexes.stream().anyMatch(i->i==null||i<1||i>1000)
            ||cn.bidcheck.compat.Java8Api.setOf("PAGE", "IMAGE").contains(locationType)&&imageIndexes.stream().anyMatch(i->i!=1))
            throw new IllegalArgumentException("OCR 图片选择无效。");
        reasons=reasons==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.toList(reasons.stream().map(s->cn.bidcheck.compat.Java8Api.strip(Objects.requireNonNull(s))).filter(s->!s.isEmpty()).distinct());
        if(reasons.size()>20||reasons.stream().anyMatch(s->s.length()>300||s.indexOf('\0')>=0))throw new IllegalArgumentException("OCR 选择说明超限。");

            this.locationType = locationType; this.position = position; this.imageIndexes = imageIndexes; this.reasons = reasons;
        }
    public static OcrSelection position(String type,int position,String reason){return new OcrSelection(type,position,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(reason));}
}
