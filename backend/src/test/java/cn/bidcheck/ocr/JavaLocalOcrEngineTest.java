package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JavaLocalOcrEngineTest {
    @TempDir Path temp;
    @Test void realClosedRulesProduceCellsWithoutLoadingAnyModel()throws Exception {
        BufferedImage image=blank(600,500);Graphics2D g=image.createGraphics();g.setColor(Color.BLACK);g.setStroke(new BasicStroke(2));for(int x:new int[]{50,200,400,550})g.drawLine(x,100,x,400);for(int y:new int[]{100,200,300,400})g.drawLine(50,y,550,y);g.dispose();
        byte[] bytes=png(image);try(PixelGridClient client=new PixelGridClient(true,temp.resolve("absent-models").toString(),temp.toString(),1,5)){
            PixelGridClient.Detection result=client.detect(bytes,frame(bytes,600,500));assertEquals(1,result.grids().size(),result.warnings().toString());GridLayoutAssembler.Grid grid=result.grids().get(0);assertEquals(3,grid.rows());assertEquals(3,grid.columns());assertEquals(9,grid.cells().size());assertTrue(grid.cells().stream().noneMatch(GridLayoutAssembler.Region::header));
        }
    }
    @Test void paleRulesBesideColoredHeaderAndDenseTextRemainPhysicalBorders()throws Exception {
        BufferedImage image=blank(600,500);Graphics2D g=image.createGraphics();g.setColor(new Color(245,244,218));g.fillRect(50,100,500,70);g.setColor(new Color(213,213,213));for(int x:new int[]{50,200,400,550})g.drawLine(x,100,x,400);for(int y:new int[]{100,170,400})g.drawLine(50,y,550,y);g.setColor(Color.BLACK);g.drawString("HEADER HEADER",60,140);g.dispose();JavaRasterGrid.Result result=JavaRasterGrid.detect(image);assertEquals(1,result.grids.size(),result.warnings.toString());assertEquals(6,((List<?>)result.grids.get(0).get("cells")).size());
    }
    @Test void measuredAbsentDividerAllowsRectangularMergeButBrokenEdgeDoesNot()throws Exception {
        int w=320,h=220;boolean[] hm=new boolean[w*h],vm=new boolean[w*h];for(int y:new int[]{10,110,210})for(int x=10;x<=310;x++)hm[y*w+x]=true;for(int x:new int[]{10,310})for(int y=10;y<=210;y++)vm[y*w+x]=true;for(int y=110;y<=210;y++)vm[y*w+160]=true;
        Map<String,Object> grid=JavaRasterGrid.assemble(hm,vm,w,h,new int[]{10,160,310},new int[]{10,110,210});assertNotNull(grid);List<?> cells=(List<?>)grid.get("cells");assertEquals(3,cells.size());assertEquals(2,((Map<?,?>)cells.get(0)).get("columnSpan"));for(int y=120;y<150;y++)vm[y*w+160]=false;assertNull(JavaRasterGrid.assemble(hm,vm,w,h,new int[]{10,160,310},new int[]{10,110,210}));
    }
    @Test void borderlessWordsDoNotInventCellsAndImageBudgetIsCheckedBeforeDecoding()throws Exception {
        BufferedImage image=blank(500,300);Graphics2D g=image.createGraphics();g.setColor(Color.BLACK);g.drawString("A   123   B",40,80);g.drawString("C   456   D",40,150);g.dispose();assertTrue(JavaRasterGrid.detect(image).grids.isEmpty());
        BufferedImage large=blank(4000,4000);byte[] bytes=png(large);large.flush();assertEquals("IMAGE_PIXELS_LIMIT",assertThrows(IOException.class,()->JavaRasterGrid.decode(bytes)).getMessage());
    }
    @Test void regionsRoundInwardAndRejectOverlapOrNonfiniteCoordinates()throws Exception {
        List<JavaLocalOcrEngine.Region> result=JavaLocalOcrEngine.regions(Collections.singletonList(region("a",1.2,2.3,20.9,30.8)),100,100);assertEquals(2,result.get(0).x0);assertEquals(3,result.get(0).y0);assertEquals(20,result.get(0).x1);assertEquals(30,result.get(0).y1);
        assertThrows(IOException.class,()->JavaLocalOcrEngine.regions(Arrays.asList(region("a",0,0,10,10),region("b",5,5,15,15)),100,100));assertThrows(IOException.class,()->JavaLocalOcrEngine.regions(Collections.singletonList(region("a",0,0,Double.NaN,10)),100,100));
    }
    @Test void mismatchedSourceDimensionsFailBeforeDecodeAndDoNotLeakCapacity()throws Exception {
        byte[] bytes=png(blank(100,80));try(PixelGridClient client=new PixelGridClient(true,temp.resolve("missing").toString(),temp.toString(),1,2)){
            PixelGridClient.GridException invalid=assertThrows(PixelGridClient.GridException.class,()->client.detect(bytes,frame(bytes,101,80)));assertEquals("SOURCE_MISMATCH",invalid.code());assertTrue(client.detect(bytes,frame(bytes,100,80)).grids().isEmpty());
        }
    }
    @Test void ctcKeepsSpacesAndSeparatesRepeatedCharactersOnlyAcrossBlank()throws Exception {
        float[][] values={{.01f,.9f,.04f,.05f},{.01f,.9f,.04f,.05f},{.9f,.01f,.04f,.05f},{.01f,.9f,.04f,.05f},{.01f,.04f,.05f,.9f},{.01f,.04f,.9f,.05f}};
        OnnxCellOcr.Text result=OnnxCellOcr.decode(values,Arrays.asList("","甲","乙"," "));assertEquals("甲甲 乙",result.value);assertTrue(result.score>.89);
    }
    @Test void actualLocalModelsRecognizeChineseEnglishMultilineAndMapCropToOriginalPixels()throws Exception {
        Path models=models();assumeTrue(models!=null,"Install local OCR models to exercise Java ONNX inference.");BufferedImage image=blank(700,270);Graphics2D g=image.createGraphics();g.setColor(Color.BLACK);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,34));g.drawString("姓名 合成甲",55,100);g.drawString("TOTAL 12.50",55,175);g.dispose();byte[] bytes=png(image);OcrLayout.Frame frame=frame(bytes,700,270);
        OcrLayout text=new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,Collections.singletonList(frame),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList());
        GridLayoutAssembler.Grid grid=new GridLayoutAssembler.Grid(frame.id(),"PIXEL_XYXY",1,1,Collections.singletonList(new GridLayoutAssembler.Region(0,0,1,1,Arrays.asList(20.0,20.0,650.0,240.0),false)));
        try(PixelGridClient client=new PixelGridClient(true,models.toString(),temp.toString(),1,30)){
            OcrLayout local=client.localText(bytes,text,Collections.singletonList(grid));String recognized=String.join("\n",cn.bidcheck.compat.Java8Api.toList(local.textBoxes().stream().map(OcrLayout.TextBox::text)));assertTrue(recognized.contains("姓名"),recognized);assertTrue(recognized.contains("12.50"),recognized);assertTrue(recognized.toUpperCase(Locale.ROOT).contains("TOTAL"),recognized);assertTrue(local.textBoxes().size()>=2,recognized);assertEquals("CELL_CROP_MAPPED",local.frames().get(0).sourceKind());assertTrue(local.textBoxes().stream().allMatch(b->b.bbox().x()>=20&&b.bbox().y()>=20&&b.bbox().x()+b.bbox().width()<=650&&b.bbox().y()+b.bbox().height()<=240));
        }
    }
    @Test void actualLocalModelsReadUpsideDownAndVerticalText()throws Exception {
        Path models=models();assumeTrue(models!=null,"Install local OCR models to exercise Java ONNX inference.");
        BufferedImage image=blank(650,160);Graphics2D g=image.createGraphics();g.setColor(Color.BLACK);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,36));g.drawString("姓名 TOTAL 12.50",45,95);g.dispose();
        try(OnnxCellOcr ocr=new OnnxCellOcr(models)){
            for(int degrees:new int[]{180,90}){boolean half=degrees==180;BufferedImage rotated=blank(half?650:160,half?160:650);Graphics2D r=rotated.createGraphics();r.translate(rotated.getWidth()/2.0,rotated.getHeight()/2.0);r.rotate(Math.toRadians(degrees));r.translate(-image.getWidth()/2.0,-image.getHeight()/2.0);r.drawImage(image,0,0,null);r.dispose();
                List<OnnxCellOcr.Box> result=ocr.recognize(rotated,System.nanoTime()+30_000_000_000L);String text=String.join("\n",cn.bidcheck.compat.Java8Api.toList(result.stream().map(b->b.text)));assertTrue(text.contains("12.50"),degrees+": "+text);assertTrue(text.toUpperCase(Locale.ROOT).contains("TOTAL"),degrees+": "+text);rotated.flush();
            }
        }finally{image.flush();}
    }
    static Path models(){String configured=System.getProperty("bidcheck.test.ocr-models","");if(!configured.isEmpty())return Paths.get(configured).toAbsolutePath();for(String name:new String[]{".local/table-models","../.local/table-models"}){Path p=Paths.get(name).toAbsolutePath().normalize();if(Files.isRegularFile(p.resolve(OnnxCellOcr.MODEL_NAMES[0])))return p;}return null;}
    static Map<String,Object> region(String id,double a,double b,double c,double d){Map<String,Object> out=new LinkedHashMap<>();out.put("id",id);out.put("box",Arrays.asList(a,b,c,d));return out;}
    static BufferedImage blank(int w,int h){BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.dispose();return image;}
    static byte[] png(BufferedImage image)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"PNG",out);return out.toByteArray();}
    static OcrLayout.Frame frame(byte[] image,int w,int h){return new OcrLayout.Frame("native-image",w,h,JavaLocalOcrEngine.digest(image),"IMAGE",0,null,null,null);}
}
