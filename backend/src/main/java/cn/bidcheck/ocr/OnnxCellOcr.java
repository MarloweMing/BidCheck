package cn.bidcheck.ocr;

import ai.onnxruntime.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Local PP-OCR detection/classification/CTC recognition via the Java ONNX binding. */
final class OnnxCellOcr implements AutoCloseable {
    static final String[] MODEL_NAMES={"PP-OCRv6_det_small.onnx","PP-OCRv6_rec_small.onnx","ch_ppocr_mobile_v2.0_cls_mobile.onnx"};
    private static final Semaphore INFERENCE=new Semaphore(2,true);
    private final Path directory;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"local-ocr-watchdog");t.setDaemon(true);return t;});
    private OrtEnvironment environment;private OrtSession detector,recognizer,classifier;private List<String> characters;
    OnnxCellOcr(Path directory){this.directory=directory;}
    private synchronized void initialize()throws IOException {
        if(detector!=null)return;for(String name:MODEL_NAMES){Path file=directory.resolve(name);if(!Files.isRegularFile(file)||Files.size(file)>128L*1024*1024)throw JavaRasterGrid.error("LOCAL_OCR_MODELS_MISSING");}
        OrtSession d=null,r=null,c=null;try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);environment=OrtEnvironment.getEnvironment();
            d=environment.createSession(directory.resolve(MODEL_NAMES[0]).toString(),options);r=environment.createSession(directory.resolve(MODEL_NAMES[1]).toString(),options);c=environment.createSession(directory.resolve(MODEL_NAMES[2]).toString(),options);
            // Read UTF-8 protobuf metadata directly: older JNI GetStringUTF truncates supplementary glyphs.
            String values=metadata(directory.resolve(MODEL_NAMES[1]),"character");if(values==null||values.length()>1024*1024)throw JavaRasterGrid.error("LOCAL_OCR_DICTIONARY_MISSING");
            List<String> alphabet=new ArrayList<>();alphabet.add("");alphabet.addAll(Arrays.asList(values.split("\\r\\n|[\\n\\r\\u000B\\u000C\\u001C-\\u001E\\u0085\\u2028\\u2029]")));alphabet.add(" ");characters=Collections.unmodifiableList(alphabet);detector=d;recognizer=r;classifier=c;
        }catch(OrtException|IOException e){closeQuietly(d);closeQuietly(r);closeQuietly(c);throw JavaRasterGrid.error(e instanceof IOException?e.getMessage():"LOCAL_OCR_MODEL_INVALID");}
    }
    List<Box> recognize(BufferedImage image,long deadline)throws IOException {
        checkDeadline(deadline);boolean acquired=false;try{
        acquired=INFERENCE.tryAcquire(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(!acquired)throw JavaRasterGrid.error("LOCAL_OCR_CAPACITY_TIMEOUT");
        initialize();List<Box> boxes=detect(image,deadline),accepted=new ArrayList<>();
        for(Box box:boxes){checkDeadline(deadline);BufferedImage crop=extract(image,box);try{
            if(crop.getHeight()/(double)crop.getWidth()>=1.5){BufferedImage rotated=rotate(crop,-Math.PI/2);crop.flush();crop=rotated;}
            if(classify(crop,deadline)){BufferedImage rotated=rotate(crop,Math.PI);crop.flush();crop=rotated;}
            Text text=recognizeLine(crop,deadline);if(!text.value.trim().isEmpty()&&text.score>=.5)accepted.add(new Box(box.points,text.value,text.score));
        }finally{crop.flush();}}
        return accepted;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("本地OCR资源等待已中断。");}finally{if(acquired)INFERENCE.release();}
    }
    private List<Box> detect(BufferedImage image,long deadline)throws IOException {
        int originalW=image.getWidth(),originalH=image.getHeight(),max=Math.max(originalW,originalH);double scale=Math.min(1,1600.0/max);int w=Math.max(32,(int)Math.rint(originalW*scale/32)*32),h=Math.max(32,(int)Math.rint(originalH*scale/32)*32);
        float[][][][] output=(float[][][][])infer(detector,tensor(image,w,h,w),new long[]{1,3,h,w},deadline);
        if(output.length!=1||output[0].length!=1)throw JavaRasterGrid.error("LOCAL_OCR_OUTPUT_INVALID");float[][] probability=output[0][0];h=probability.length;w=probability[0].length;
        if((long)w*h>4_000_000)throw JavaRasterGrid.error("LOCAL_OCR_OUTPUT_LIMIT");boolean[] mask=new boolean[w*h];for(int y=0;y<h;y++){checkDeadline(deadline);for(int x=0;x<w;x++){float p=probability[y][x];if(!Float.isFinite(p))throw JavaRasterGrid.error("LOCAL_OCR_OUTPUT_INVALID");if(p>.3){mask[y*w+x]=true;if(x+1<w)mask[y*w+x+1]=true;if(y+1<h)mask[(y+1)*w+x]=true;if(x+1<w&&y+1<h)mask[(y+1)*w+x+1]=true;}}}
        int[] queue=new int[mask.length];List<Box> out=new ArrayList<>();int candidates=0;
        for(int index=0;index<mask.length;index++){if((index&4095)==0)checkDeadline(deadline);if(!mask[index])continue;if(++candidates>1000)break;int start=0,end=0;queue[end++]=index;mask[index]=false;List<Point> contour=new ArrayList<>();
            while(start<end){if((start&4095)==0)checkDeadline(deadline);int q=queue[start++],x=q%w,y=q/w;boolean boundary=false;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){int p=yy*w+xx;if(mask[p]){mask[p]=false;queue[end++]=p;}if(probability[yy][xx]<=.3)boundary=true;}if(boundary||x==0||y==0||x==w-1||y==h-1)contour.add(new Point(x,y));}
            if(contour.size()<4)continue;double[][] rect=minimumRectangle(contour);double rw=distance(rect[0],rect[1]),rh=distance(rect[1],rect[2]);if(Math.min(rw,rh)<3)continue;
            double sum=0;int count=0;int minx=w,miny=h,maxx=0,maxy=0;for(double[] p:rect){minx=Math.min(minx,(int)Math.floor(p[0]));miny=Math.min(miny,(int)Math.floor(p[1]));maxx=Math.max(maxx,(int)Math.ceil(p[0]));maxy=Math.max(maxy,(int)Math.ceil(p[1]));}
            for(int y=Math.max(0,miny);y<=Math.min(h-1,maxy);y++)for(int x=Math.max(0,minx);x<=Math.min(w-1,maxx);x++)if(inside(rect,x,y)){sum+=probability[y][x];count++;}if(count==0||sum/count<.5)continue;
            double padding=rw*rh*1.6/(2*(rw+rh));double cx=0,cy=0;for(double[] p:rect){cx+=p[0]/4;cy+=p[1]/4;}double cos=(rect[1][0]-rect[0][0])/rw,sin=(rect[1][1]-rect[0][1])/rw;
            double ex=rw/2+padding,ey=rh/2+padding;double[][] points=new double[4][2];int k=0;for(int[] corner:new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}}){points[k][0]=Math.max(0,Math.min(originalW,Math.rint((cx+corner[0]*ex*cos-corner[1]*ey*sin)/w*originalW)));points[k][1]=Math.max(0,Math.min(originalH,Math.rint((cy+corner[0]*ex*sin+corner[1]*ey*cos)/h*originalH)));k++;}
            if(distance(points[0],points[1])>=5&&distance(points[1],points[2])>=5)out.add(new Box(points,"",0));if(out.size()>1500)throw JavaRasterGrid.error("OCR_BOXES_LIMIT");
        }
        out.sort(Comparator.comparingDouble((Box b)->b.points[0][1]).thenComparingDouble(b->b.points[0][0]));return out;
    }
    private boolean classify(BufferedImage image,long deadline)throws IOException {
        int content=Math.min(192,(int)Math.ceil(48.0*image.getWidth()/image.getHeight()));float[][] output=(float[][])infer(classifier,tensor(image,content,48,192),new long[]{1,3,48,192},deadline);return output.length==1&&output[0].length==2&&output[0][1]>.9&&output[0][1]>output[0][0];
    }
    private Text recognizeLine(BufferedImage image,long deadline)throws IOException {
        int width=Math.max(320,(int)Math.ceil(48.0*image.getWidth()/image.getHeight()));if(width>8192)throw JavaRasterGrid.error("LOCAL_OCR_LINE_LIMIT");int content=Math.min(width,(int)Math.ceil(48.0*image.getWidth()/image.getHeight()));
        float[][][] output=(float[][][])infer(recognizer,tensor(image,content,48,width),new long[]{1,3,48,width},deadline);if(output.length!=1)throw JavaRasterGrid.error("LOCAL_OCR_OUTPUT_INVALID");return decode(output[0],characters);
    }
    static Text decode(float[][] sequence,List<String> alphabet)throws IOException {
        StringBuilder value=new StringBuilder();double sum=0;int count=0,previous=-1;for(float[] scores:sequence){if(scores.length!=alphabet.size())throw JavaRasterGrid.error("LOCAL_OCR_DICTIONARY_MISMATCH");int best=0;for(int i=0;i<scores.length;i++){if(!Float.isFinite(scores[i])||scores[i]<0||scores[i]>1)throw JavaRasterGrid.error("LOCAL_OCR_OUTPUT_INVALID");if(scores[i]>scores[best])best=i;}if(best!=0&&best!=previous){value.append(alphabet.get(best));sum+=scores[best];count++;if(value.length()>8000)throw JavaRasterGrid.error("OCR_OUTPUT_INVALID");}previous=best;}return new Text(value.toString(),count==0?0:sum/count);
    }
    private Object infer(OrtSession session,float[] values,long[] shape,long deadline)throws IOException {
        checkDeadline(deadline);Thread caller=Thread.currentThread();try(OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),shape)){
            OrtSession.RunOptions options=new OrtSession.RunOptions();java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();ScheduledFuture<?> stop=null;
            try{
                stop=watchdog.scheduleAtFixedRate(()->{synchronized(options){if(!finished.get()&&(caller.isInterrupted()||System.nanoTime()>=deadline))try{options.setTerminate(true);}catch(OrtException ignored){}}},0,20,TimeUnit.MILLISECONDS);
                try(OrtSession.Result result=session.run(Collections.singletonMap(session.getInputNames().iterator().next(),input),options)){checkDeadline(deadline);return result.get(0).getValue();}
            }finally{synchronized(options){finished.set(true);if(stop!=null)stop.cancel(false);options.close();}}
        }catch(OrtException e){checkDeadline(deadline);throw JavaRasterGrid.error("LOCAL_OCR_ENGINE_ERROR");}
    }
    private static float[] tensor(BufferedImage image,int width,int height,int paddedWidth)throws IOException {
        BufferedImage resized=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=resized.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,0,0,width,height,null);g.dispose();float[] out=new float[3*height*paddedWidth];try{for(int y=0;y<height;y++){JavaRasterGrid.check();for(int x=0;x<width;x++){int rgb=resized.getRGB(x,y),i=y*paddedWidth+x;out[i]=(rgb&255)/127.5f-1;out[height*paddedWidth+i]=(rgb>>8&255)/127.5f-1;out[2*height*paddedWidth+i]=(rgb>>16&255)/127.5f-1;}}return out;}finally{resized.flush();}
    }
    private static BufferedImage extract(BufferedImage image,Box box){double[][] p=box.points;int w=Math.max(1,(int)Math.round(distance(p[0],p[1]))),h=Math.max(1,(int)Math.round(distance(p[0],p[3])));BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=out.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);double a=(p[1][0]-p[0][0])/w,b=(p[1][1]-p[0][1])/w,c=(p[3][0]-p[0][0])/h,d=(p[3][1]-p[0][1])/h;try{g.drawImage(image,new AffineTransform(a,b,c,d,p[0][0],p[0][1]).createInverse(),null);}catch(java.awt.geom.NoninvertibleTransformException ignored){}finally{g.dispose();}return out;}
    private static BufferedImage rotate(BufferedImage source,double angle){boolean half=Math.abs(angle)>3;int w=half?source.getWidth():source.getHeight(),h=half?source.getHeight():source.getWidth();BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=out.createGraphics();g.translate(w/2.0,h/2.0);g.rotate(angle);g.translate(-source.getWidth()/2.0,-source.getHeight()/2.0);g.drawImage(source,0,0,null);g.dispose();return out;}
    private static double distance(double[] a,double[] b){return Math.hypot(a[0]-b[0],a[1]-b[1]);}
    private static boolean inside(double[][] p,double x,double y){for(int i=0;i<4;i++){double[] a=p[i],b=p[(i+1)%4];if((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0])<-.01)return false;}return true;}
    private static double[][] minimumRectangle(List<Point> values){
        values.sort(Comparator.comparingInt((Point p)->p.x).thenComparingInt(p->p.y));List<Point> hull=new ArrayList<>();for(Point p:values){while(hull.size()>1&&cross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}int lower=hull.size();for(int i=values.size()-2;i>=0;i--){Point p=values.get(i);while(hull.size()>lower&&cross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}if(hull.size()>1)hull.remove(hull.size()-1);double best=Double.POSITIVE_INFINITY,angle=0,left=0,right=0,top=0,bottom=0;
        for(int i=0;i<hull.size();i++){Point a=hull.get(i),b=hull.get((i+1)%hull.size());double theta=Math.atan2(b.y-a.y,b.x-a.x);while(theta>Math.PI/4)theta-=Math.PI/2;while(theta< -Math.PI/4)theta+=Math.PI/2;double cos=Math.cos(theta),sin=Math.sin(theta),minx=Double.POSITIVE_INFINITY,maxx=-minx,miny=minx,maxy=-minx;for(Point p:hull){double x=p.x*cos+p.y*sin,y=-p.x*sin+p.y*cos;minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);}double area=(maxx-minx)*(maxy-miny);if(area<best){best=area;angle=theta;left=minx;right=maxx;top=miny;bottom=maxy;}}
        double cos=Math.cos(angle),sin=Math.sin(angle);double[][] out={{left,top},{right,top},{right,bottom},{left,bottom}};for(double[] p:out){double x=p[0],y=p[1];p[0]=x*cos-y*sin;p[1]=x*sin+y*cos;}return out;
    }
    private static long cross(Point a,Point b,Point c){return (long)(b.x-a.x)*(c.y-a.y)-(long)(b.y-a.y)*(c.x-a.x);}
    private static String metadata(Path file,String name)throws IOException {
        Wire model=new Wire(Files.readAllBytes(file));while(model.remaining()){long tag=model.varint();if((tag>>>3)==14&&(tag&7)==2){Wire entry=new Wire(model.bytes(1024*1024));String key=null,value=null;while(entry.remaining()){long field=entry.varint();if((field&7)==2&&((field>>>3)==1||(field>>>3)==2)){byte[] bytes=entry.bytes(1024*1024);String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if((field>>>3)==1)key=text;else value=text;}else entry.skip(field&7);}if(name.equals(key))return value;}else model.skip(tag&7);}return null;
    }
    private static final class Wire {
        private final byte[] data;private int position;
        Wire(byte[] data){this.data=data;}
        boolean remaining(){return position<data.length;}
        long varint()throws IOException{long value=0;for(int shift=0;shift<64;shift+=7){if(position>=data.length)throw JavaRasterGrid.error("LOCAL_OCR_MODEL_INVALID");int b=data[position++]&255;if(shift==63&&(b&254)!=0)throw JavaRasterGrid.error("LOCAL_OCR_MODEL_INVALID");value|=(long)(b&127)<<shift;if((b&128)==0)return value;}throw JavaRasterGrid.error("LOCAL_OCR_MODEL_INVALID");}
        int length(long max)throws IOException{long n=varint();if(n<0||n>max||n>data.length-position)throw JavaRasterGrid.error("LOCAL_OCR_MODEL_INVALID");return (int)n;}
        byte[] bytes(int max)throws IOException{int n=length(max);byte[] out=Arrays.copyOfRange(data,position,position+n);position+=n;return out;}
        void skip(long wire)throws IOException{int n;if(wire==0){varint();return;}else if(wire==1)n=8;else if(wire==5)n=4;else if(wire==2)n=length(data.length);else throw JavaRasterGrid.error("LOCAL_OCR_MODEL_INVALID");if(n>data.length-position)throw JavaRasterGrid.error("LOCAL_OCR_MODEL_INVALID");position+=n;}
    }
    static void checkDeadline(long deadline)throws InterruptedIOException{JavaRasterGrid.check();if(System.nanoTime()>=deadline)throw new InterruptedIOException("本地 OCR 处理超时。");}
    static final class Text {final String value;final double score;Text(String value,double score){this.value=value;this.score=score;}}
    static final class Box {final double[][] points;final String text;final double confidence;Box(double[][] points,String text,double confidence){this.points=points;this.text=text;this.confidence=confidence;}}
    private static void closeQuietly(OrtSession session){if(session!=null)try{session.close();}catch(OrtException ignored){}}
    @Override public synchronized void close(){watchdog.shutdownNow();closeQuietly(detector);closeQuietly(recognizer);closeQuietly(classifier);detector=null;recognizer=null;classifier=null;}
}
