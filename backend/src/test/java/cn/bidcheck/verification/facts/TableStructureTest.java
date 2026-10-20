package cn.bidcheck.verification.facts;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.facts.StructuralFixtures.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

class TableStructureTest {
    TableStructure.Result analyze(Fixture f){return TableStructure.analyze(NAME,ids(f),f.cells(),f.sources());}
    Fixture samePageRows(boolean sameTable,boolean sameFrame){var a=row(1,true,true,ID,"2025-01");var b=row(2,true,true,ID,"2025-02、2025-03");String joined=a.text()+"\n"+b.text();List<Map<String,Object>> cells=new ArrayList<>(a.cells());for(var c:b.cells()){c.put("location",1);if(sameTable)c.put("tableId","t1");if(sameFrame)c.put("frameId","frame1");if(sameTable&&sameFrame){if(Boolean.TRUE.equals(c.get("header")))continue;c.put("row",3);c.put("bbox",List.of((Integer)c.get("column")*100,300,(Integer)c.get("column")*100+80,360));c.put("headerCellIds",List.of("p1r1c"+c.get("column")));}cells.add(c);}var s=new LinkedHashMap<>(a.sources().get(0));s.put("evidence",new cn.bidcheck.domain.Domain.Evidence("d","合成.pdf",1,"PAGE",joined,"chars:0",0));return new Fixture(cells,List.of(s),joined);}
    @Test void nativeCellsBindANameAndIdentifierWithoutInventingInlineText(){var f=row(1,false,false,ID,"2025-01、2025-02");var r=analyze(f);assertTrue(r.valid(),r.issue());assertTrue(r.rows().get(0).strongIdentity());assertEquals(List.of("2025-01","2025-02"),r.rows().get(0).months());assertEquals("合成公司",r.rows().get(0).employer());assertFalse(canonical(r.proof()).contains(ID));}
    @Test void ocrCoordinatesAloneDoNotEstablishStrongIdentity(){var f=row(1,true,false,ID,"2025-01");var r=analyze(f);assertTrue(r.valid());assertFalse(r.rows().get(0).strongIdentity());assertEquals("SOURCE_LOCAL",EntityIdentity.resolvePerson(NAME,f.sources(),f.cells(),ids(f)).get("identityStrength"));}
    @Test void independentOcrTranscriptionAndAdjacentRepeatedHeadersSupportMonthChain(){var f=join(row(1,true,true,ID,"2025-01"),row(2,true,true,ID,"2025-02、2025-03"));var r=analyze(f);assertTrue(r.valid(),r.issue());assertEquals(1,r.links().size());assertEquals(Set.of("S1","S2"),new HashSet<>(strings(r.links().get(0).get("sourceIds"))));assertEquals("STRONG",EntityIdentity.resolvePerson(NAME,f.sources(),f.cells(),ids(f)).get("identityStrength"));}
    @Test void sameNameAndMatchingGeometryAreNotCrossPageIdentity(){var f=join(row(1,true,false,ID,"2025-01"),row(2,true,false,ID,"2025-02"));assertEquals("STRUCTURE_CONTINUATION_UNPROVEN",analyze(f).issue());}
    @Test void skippedPhysicalPageCannotClaimContinuation(){assertEquals("STRUCTURE_CONTINUATION_UNPROVEN",analyze(join(row(1,true,true,ID,"2025-01"),row(3,true,true,ID,"2025-02"))).issue());}
    @Test void sameNameDifferentIdCannotMixMonths(){assertEquals("SUBJECT_IDENTITY_CONFLICT",analyze(join(row(1,true,true,ID,"2025-01"),row(2,true,true,OTHER,"2025-02"))).issue());}
    @Test void changedHeaderStructureBreaksContinuation(){var b=row(2,true,true,ID,"2025-02");b.cells().stream().filter(c->Integer.valueOf(4).equals(c.get("column"))).forEach(c->c.put("column",5));assertEquals("STRUCTURE_CONTINUATION_UNPROVEN",analyze(join(row(1,true,true,ID,"2025-01"),b)).issue());}
    @Test void oldVersionOrUncitedPageCannotSupplyARequiredCell(){var f=row(1,false,false,ID,"2025-01");f.cells().stream().filter(c->Integer.valueOf(2).equals(c.get("row"))&&Integer.valueOf(2).equals(c.get("column"))).forEach(c->c.put("documentVersion",2));assertFalse(analyze(f).valid());}
    @Test void inventedHeaderTextWithoutActualHeaderCellsHasNoAuthority(){var f=row(1,false,false,ID,"2025-01");f.cells().removeIf(c->Boolean.TRUE.equals(c.get("header")));f.cells().forEach(c->c.put("headerText","姓名 身份证号码 缴费单位 缴费月份"));assertFalse(analyze(f).valid());}
    @Test void otherPersonCannotLendTheirRow(){var f=row(1,false,false,ID,"2025-01");assertFalse(TableStructure.analyze("另一个人",ids(f),f.cells(),f.sources()).valid());}
    @Test void malformedOcrBoundsCannotBecomeARecord(){var f=row(1,true,true,ID,"2025-01");f.cells().get(0).put("bbox",List.of(Double.NaN,0,100,100));assertFalse(analyze(f).valid());}
    @Test void monthRangeNeverFabricatesIntermediatePayments(){var r=analyze(row(1,false,false,ID,"2025-01至2025-03"));assertTrue(r.valid());assertEquals(List.of("2025-01","2025-03"),r.rows().get(0).months());}
    @Test void certificateColumnDoesNotBorrowDifferentHeader(){var f=row(1,false,false,ID,"2025-01");f.cells().stream().filter(c->Integer.valueOf(2).equals(c.get("row"))&&Integer.valueOf(2).equals(c.get("column"))).forEach(c->c.put("headerCellIds",List.of("p1r1c3")));assertFalse(analyze(f).rows().get(0).strongIdentity());}
    @Test void samePageOcrRowsWithRepeatedStrongIdentityCombineActualMonths(){var f=samePageRows(true,true);var r=TableStructure.analyze(NAME,List.of(),f.cells(),f.sources());assertTrue(r.valid(),r.issue());assertEquals(Set.of("2025-01","2025-02","2025-03"),new HashSet<>(r.rows().stream().flatMap(row->row.months().stream()).toList()));assertEquals(1,r.links().size());assertEquals("SAME_TABLE_REPEATED_SUBJECT",r.links().get(0).get("type"));}
    @Test void differentFrameOrTableOnSamePageIsNotAContinuation(){for(var f:List.of(samePageRows(true,false),samePageRows(false,true)))assertEquals("STRUCTURE_CONTINUATION_UNPROVEN",TableStructure.analyze(NAME,List.of(),f.cells(),f.sources()).issue());}
}
