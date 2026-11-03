package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Executes the former image-worker contract entirely inside the JVM. */
final class JavaLocalOcrEngine implements PixelGridClient.Engine {
    static final String VERSION="java-raster-onnx-grid-1";
    private static final Semaphore PIXELS=new Semaphore(JavaRasterGrid.MAX_PIXELS,true);
    private final ObjectMapper json=new ObjectMapper();
    private final OnnxCellOcr ocr;
    private final String identity;
    JavaLocalOcrEngine(String modelDirectory){
        Path directory=Paths.get(modelDirectory==null||modelDirectory.trim().isEmpty()?".local/table-models":modelDirectory).toAbsolutePath().normalize();this.ocr=new OnnxCellOcr(directory);
        StringBuilder fingerprint=new StringBuilder(VERSION+"|onnxruntime-1.18.0");for(String name:OnnxCellOcr.MODEL_NAMES){Path file=directory.resolve(name);fingerprint.append('|').append(name).append(':');try{if(Files.isRegularFile(file)&&Files.size(file)<=128L*1024*1024)fingerprint.append(digest(Files.readAllBytes(file)));else fingerprint.append("missing");}catch(IOException invalid){throw new IllegalArgumentException("本地OCR模型不可读。");}}identity=digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Override public String identity(){return identity;}
    @Override public JsonNode process(byte[] bytes,OcrLayout.Frame frame,boolean withText,List<Map<String,Object>> regions,long deadline)throws IOException {
        long pixels=(long)frame.width()*frame.height();if(frame.width()<1||frame.height()<1||pixels>JavaRasterGrid.MAX_PIXELS)throw JavaRasterGrid.error("IMAGE_PIXELS_LIMIT");
        boolean acquired=false;BufferedImage image=null;try{
            acquired=PIXELS.tryAcquire((int)pixels,Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(!acquired)throw JavaRasterGrid.error("CAPACITY_TIMEOUT");
            image=JavaRasterGrid.decode(bytes,frame.width(),frame.height());
            if(image.getWidth()!=frame.width()||image.getHeight()!=frame.height())throw JavaRasterGrid.error("SOURCE_MISMATCH");OnnxCellOcr.checkDeadline(deadline);
            JavaRasterGrid.Result detected=JavaRasterGrid.detect(image);List<String> warnings=new ArrayList<>(detected.warnings);List<Map<String,Object>> boxes=new ArrayList<>(),sourceRegions=new ArrayList<>();
            if(withText){List<Region> validated=regions(regions,image.getWidth(),image.getHeight());
                for(Region region:validated){OnnxCellOcr.checkDeadline(deadline);int w=region.x1-region.x0,h=region.y1-region.y0;double scale=Math.min(4,Math.min(2400.0/Math.max(w,h),Math.sqrt(4_000_000.0/((long)w*h))));int outw=Math.max(1,(int)Math.floor(w*scale)),outh=Math.max(1,(int)Math.floor(h*scale));double sx=(double)outw/w,sy=(double)outh/h;
                    Map<String,Object> provenance=new LinkedHashMap<>();provenance.put("id",region.id);provenance.put("box",Arrays.asList(region.x0,region.y0,region.x1,region.y1));provenance.put("scale",scale);provenance.put("scaleX",sx);provenance.put("scaleY",sy);provenance.put("inputWidth",outw);provenance.put("inputHeight",outh);sourceRegions.add(provenance);
                    BufferedImage crop=new BufferedImage(outw,outh,BufferedImage.TYPE_INT_RGB);Graphics2D g=crop.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(image,0,0,outw,outh,region.x0,region.y0,region.x1,region.y1,null);g.dispose();
                    List<OnnxCellOcr.Box> recognized;try{recognized=ocr.recognize(crop,deadline);}finally{crop.flush();}if(recognized.isEmpty())warnings.add("CELL_OCR_NO_TEXT");
                    for(OnnxCellOcr.Box b:recognized){double left=Double.POSITIVE_INFINITY,top=left,right=-left,bottom=-left;List<List<Double>> polygon=new ArrayList<>();for(double[] p:b.points){double x=region.x0+p[0]/sx,y=region.y0+p[1]/sy;left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);polygon.add(Arrays.asList(x,y));}
                        Map<String,Object> value=new LinkedHashMap<>();value.put("id","b"+(boxes.size()+1));value.put("frameId","f1");value.put("text",b.text);value.put("polygon",polygon);Map<String,Double> bbox=new LinkedHashMap<>();bbox.put("x",left);bbox.put("y",top);bbox.put("width",right-left);bbox.put("height",bottom-top);value.put("bbox",bbox);value.put("confidence",b.confidence);value.put("sourceRegion",provenance);boxes.add(value);if(boxes.size()>1500)throw JavaRasterGrid.error("OCR_BOXES_LIMIT");
                    }
                }
            }
            if(withText&&boxes.isEmpty())warnings.add("OCR_NO_TEXT");if(detected.grids.isEmpty())warnings.add("NO_CLOSED_GRID");
            ObjectNode response=json.createObjectNode();response.put("status","OK");response.put("schemaVersion","local-ocr-grid-1");response.put("policy",VERSION);response.put("coordinateSystem",OcrLayout.COORDINATES);response.put("width",image.getWidth());response.put("height",image.getHeight());response.put("imageHash",digest(bytes));response.put("gridOnly",!withText);response.set("textBoxes",json.valueToTree(boxes));response.set("sourceRegions",json.valueToTree(sourceRegions));response.set("gridCandidates",json.valueToTree(detected.grids));response.set("warnings",json.valueToTree(new TreeSet<>(warnings)));return response;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("本地OCR资源等待已中断。");}
        finally{if(image!=null)image.flush();if(acquired)PIXELS.release((int)pixels);}
    }
    static List<Region> regions(List<Map<String,Object>> values,int width,int height)throws IOException {
        if(values==null||values.isEmpty()||values.size()>64)throw JavaRasterGrid.error("CELL_REGIONS_LIMIT");List<Region> out=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Map<String,Object> value:values){if(value==null||value.size()!=2||!value.containsKey("id")||!value.containsKey("box")||!(value.get("id") instanceof String)||!(value.get("box") instanceof List<?>))throw JavaRasterGrid.error("CELL_REGION_INVALID");String id=(String)value.get("id");List<?> box=(List<?>)value.get("box");if(!id.matches("[A-Za-z0-9_-]{1,96}")||!ids.add(id)||box.size()!=4)throw JavaRasterGrid.error("CELL_REGION_INVALID");double[] p=new double[4];for(int i=0;i<4;i++){if(!(box.get(i) instanceof Number))throw JavaRasterGrid.error("CELL_REGION_INVALID");p[i]=((Number)box.get(i)).doubleValue();if(!Double.isFinite(p[i]))throw JavaRasterGrid.error("CELL_REGION_INVALID");}if(!(p[0]>=0&&p[0]<p[2]&&p[2]<=width&&p[1]>=0&&p[1]<p[3]&&p[3]<=height))throw JavaRasterGrid.error("CELL_REGION_OUT_OF_BOUNDS");Region region=new Region(id,(int)Math.ceil(p[0]),(int)Math.ceil(p[1]),(int)Math.floor(p[2]),(int)Math.floor(p[3]),p);if(region.x1-region.x0<2||region.y1-region.y0<2)throw JavaRasterGrid.error("CELL_REGION_EMPTY");for(Region prior:out)if(Math.min(p[2],prior.raw[2])>Math.max(p[0],prior.raw[0])&&Math.min(p[3],prior.raw[3])>Math.max(p[1],prior.raw[1]))throw JavaRasterGrid.error("CELL_REGIONS_OVERLAP");out.add(region);
        }return out;
    }
    static String digest(byte[] bytes){try{return cn.bidcheck.compat.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static final class Region {final String id;final int x0,y0,x1,y1;final double[] raw;Region(String id,int x0,int y0,int x1,int y1,double[] raw){this.id=id;this.x0=x0;this.y0=y0;this.x1=x1;this.y1=y1;this.raw=raw;}}
    @Override public void close(){ocr.close();}
}
