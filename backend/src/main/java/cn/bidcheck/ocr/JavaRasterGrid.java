package cn.bidcheck.ocr;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** Image-only morphology. No model, transcript, or guessed table relationships. */
final class JavaRasterGrid {
    static final int MAX_PIXELS=12_000_000, MAX_SLOTS=3000;
    static BufferedImage decode(byte[] bytes)throws IOException {
        return decode(bytes,0,0);
    }
    static BufferedImage decode(byte[] bytes,int expectedWidth,int expectedHeight)throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>6*1024*1024)throw error("IMAGE_BYTES_LIMIT");
        try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            Iterator<ImageReader> readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw error("INVALID_IMAGE");
            ImageReader reader=readers.next();try{
                reader.setInput(in,false,true);String format=reader.getFormatName();if(!format.equalsIgnoreCase("PNG")&&!format.equalsIgnoreCase("JPEG"))throw error("IMAGE_FORMAT_UNSUPPORTED");
                int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>32768||h>32768||(long)w*h>MAX_PIXELS)throw error("IMAGE_PIXELS_LIMIT");
                if(expectedWidth>0&&(w!=expectedWidth||h!=expectedHeight))throw error("SOURCE_MISMATCH");
                if(reader.getNumImages(true)!=1)throw error("IMAGE_MULTIFRAME_UNSUPPORTED");
                check();BufferedImage result=reader.read(0);if(result==null||result.getWidth()!=w||result.getHeight()!=h)throw error("INVALID_IMAGE");return result;
            }finally{reader.dispose();}
        }
    }
    static Result detect(BufferedImage image)throws IOException {
        int w=image.getWidth(),h=image.getHeight(),n=w*h;byte[] gray=new byte[n];
        int[] row=new int[w];for(int y=0;y<h;y++){check();image.getRGB(0,y,w,1,row,0,w);for(int x=0;x<w;x++){int p=row[x];gray[y*w+x]=(byte)(((p>>16&255)*77+(p>>8&255)*150+(p&255)*29+128)>>8);}}
        byte[] hb=blackHat(gray,w,h,false),vb=blackHat(gray,w,h,true);
        boolean[] hm=open(hb,w,h,true,24),vm=open(vb,w,h,false,24);gray=null;hb=null;vb=null;
        boolean[] connected=new boolean[n];for(int y=0;y<h;y++){check();for(int x=0;x<w;x++){int i=y*w+x;if(!hm[i]&&!vm[i])continue;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)connected[yy*w+xx]=true;}}
        List<int[]> components=components(connected,w,h);components.sort(Comparator.<int[]>comparingInt(v->v[1]).thenComparingInt(v->v[0]));
        List<Map<String,Object>> grids=new ArrayList<>();Set<String> warnings=new TreeSet<>();int slots=0;
        for(int[] b:components){check();int x=b[0],y=b[1],cw=b[2]-x+1,ch=b[3]-y+1;if(cw<Math.max(60,w/20)||ch<30)continue;
            List<Integer> vx=new ArrayList<>(),hy=new ArrayList<>();
            for(int xx=x;xx<x+cw;xx++){int count=0;for(int yy=y;yy<y+ch;yy++)if(vm[yy*w+xx])count++;if(count>=Math.max(20,ch*.12))vx.add(xx);}
            for(int yy=y;yy<y+ch;yy++){int count=0;for(int xx=x;xx<x+cw;xx++)if(hm[yy*w+xx])count++;if(count>=Math.max(24,cw*.10))hy.add(yy);}
            int[] xs=clusters(vx),ys=clusters(hy);if(xs.length<3||ys.length<2)continue;
            Map<String,Object> grid=assemble(hm,vm,w,h,xs,ys);if(grid==null){warnings.add("GRID_GEOMETRY_AMBIGUOUS");continue;}
            int size=((List<?>)grid.get("cells")).size();if(grids.size()>=40||slots+size>MAX_SLOTS){warnings.add("GRID_BUDGET_EXCEEDED");break;}slots+=size;grids.add(grid);
        }
        return new Result(grids,new ArrayList<>(warnings));
    }
    private static byte[] blackHat(byte[] gray,int w,int h,boolean horizontal)throws IOException {
        byte[] max=extreme(gray,w,h,horizontal,7,true),closed=extreme(max,w,h,horizontal,7,false);for(int i=0;i<closed.length;i++)closed[i]=(byte)(((closed[i]&255)-(gray[i]&255))>5?255:0);return closed;
    }
    private static byte[] extreme(byte[] source,int w,int h,boolean horizontal,int k,boolean max)throws IOException {
        byte[] out=new byte[source.length];int before=k/2,after=k-before-1,length=horizontal?w:h,lines=horizontal?h:w;int[] deque=new int[length+k];
        for(int line=0;line<lines;line++){check();int head=0,tail=0;for(int end=-before;end<length+after;end++){
            int location=Math.max(0,Math.min(length-1,end)),value=source[horizontal?line*w+location:location*w+line]&255;
            while(head<tail){int prior=Math.max(0,Math.min(length-1,deque[tail-1])),q=source[horizontal?line*w+prior:prior*w+line]&255;if(max?value<q:value>q)break;tail--;}
            deque[tail++]=end;while(head<tail&&deque[head]<end-k+1)head++;int center=end-after;if(center>=0){int best=Math.max(0,Math.min(length-1,deque[head]));out[horizontal?line*w+center:center*w+line]=source[horizontal?line*w+best:best*w+line];}
        }}return out;
    }
    private static boolean[] open(byte[] mask,int w,int h,boolean horizontal,int k)throws IOException {
        byte[] eroded=extreme(mask,w,h,horizontal,k,false),opened=extreme(eroded,w,h,horizontal,k,true);boolean[] out=new boolean[mask.length];for(int i=0;i<out.length;i++)out[i]=opened[i]!=0;return out;
    }
    /** Destructively visits the temporary connectivity image; directional evidence stays intact. */
    private static List<int[]> components(boolean[] mask,int w,int h)throws IOException {
        List<int[]> out=new ArrayList<>();int[] queue=new int[mask.length];for(int i=0;i<mask.length;i++){if((i&4095)==0)check();if(!mask[i])continue;int start=0,end=0;queue[end++]=i;mask[i]=false;int minx=i%w,miny=i/w,maxx=minx,maxy=miny;
            while(start<end){if((start&4095)==0)check();int p=queue[start++],x=p%w,y=p/w;minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){int q=yy*w+xx;if(mask[q]){mask[q]=false;queue[end++]=q;}}}
            if(out.size()>=100_000)throw error("GRID_COUNT_LIMIT");out.add(new int[]{minx,miny,maxx,maxy});
        }return out;
    }
    private static int[] clusters(List<Integer> values){List<Integer> result=new ArrayList<>();long sum=0;int count=0,prior=-100;for(int v:values){if(v>prior+2&&count>0){result.add((int)Math.rint((double)sum/count));sum=0;count=0;}sum+=v;count++;prior=v;}if(count>0)result.add((int)Math.rint((double)sum/count));int[] out=new int[result.size()];for(int i=0;i<out.length;i++)out[i]=result.get(i);return out;}
    static Map<String,Object> assemble(boolean[] hm,boolean[] vm,int w,int h,int[] xs,int[] ys)throws IOException {
        int rows=ys.length-1,cols=xs.length-1;if(rows<1||cols<2||rows>500||cols>100||(long)rows*cols>MAX_SLOTS)return null;
        int[][] horizontal=new int[rows+1][cols],vertical=new int[rows][cols+1];for(int r=0;r<=rows;r++)for(int c=0;c<cols;c++){int v=edge(hm,w,h,true,ys[r],xs[c],xs[c+1]);if(v<0)return null;horizontal[r][c]=v;}for(int r=0;r<rows;r++)for(int c=0;c<=cols;c++){int v=edge(vm,w,h,false,xs[c],ys[r],ys[r+1]);if(v<0)return null;vertical[r][c]=v;}
        for(int c=0;c<cols;c++)if(horizontal[0][c]!=1||horizontal[rows][c]!=1)return null;for(int r=0;r<rows;r++)if(vertical[r][0]!=1||vertical[r][cols]!=1)return null;
        int[] parent=new int[rows*cols];for(int i=0;i<parent.length;i++)parent[i]=i;for(int r=0;r<rows;r++)for(int c=0;c<cols;c++){int i=r*cols+c;if(c+1<cols&&vertical[r][c+1]==0)parent[find(parent,i)]=find(parent,i+1);if(r+1<rows&&horizontal[r+1][c]==0)parent[find(parent,i)]=find(parent,i+cols);}
        Map<Integer,List<int[]>> groups=new LinkedHashMap<>();for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)groups.computeIfAbsent(find(parent,r*cols+c),q->new ArrayList<>()).add(new int[]{r,c});
        List<Map<String,Object>> cells=new ArrayList<>();for(List<int[]> group:groups.values()){check();int r0=rows,c0=cols,r1=0,c1=0;for(int[] p:group){r0=Math.min(r0,p[0]);r1=Math.max(r1,p[0]);c0=Math.min(c0,p[1]);c1=Math.max(c1,p[1]);}if(group.size()!=(r1-r0+1)*(c1-c0+1))return null;
            for(int c=c0;c<=c1;c++)if(horizontal[r0][c]!=1||horizontal[r1+1][c]!=1)return null;for(int r=r0;r<=r1;r++)if(vertical[r][c0]!=1||vertical[r][c1+1]!=1)return null;
            Map<String,Object> cell=new LinkedHashMap<>();cell.put("row",r0);cell.put("column",c0);cell.put("rowSpan",r1-r0+1);cell.put("columnSpan",c1-c0+1);cell.put("box",Arrays.asList(xs[c0],ys[r0],xs[c1+1],ys[r1+1]));cell.put("header",false);cells.add(cell);
        }
        cells.sort(Comparator.<Map<String,Object>>comparingInt(v->(Integer)v.get("row")).thenComparingInt(v->(Integer)v.get("column")));Map<String,Object> out=new LinkedHashMap<>();out.put("frameId","f1");out.put("coordinateSpace","PIXEL_XYXY");out.put("rows",rows);out.put("columns",cols);out.put("cells",cells);return out;
    }
    private static int find(int[] parent,int i){while(i!=parent[i]){parent[i]=parent[parent[i]];i=parent[i];}return i;}
    private static int edge(boolean[] mask,int w,int h,boolean horizontal,int axis,int start,int end){start+=3;end-=3;if(end<=start)return -1;int covered=0;for(int p=start;p<end;p++){boolean found=false;for(int a=Math.max(0,axis-2);a<=Math.min((horizontal?h:w)-1,axis+2);a++)if(mask[horizontal?a*w+p:p*w+a]){found=true;break;}if(found)covered++;}double ratio=(double)covered/(end-start);return ratio>=.8?1:ratio<=.08?0:-1;}
    static void check()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("本地图像解析已中断。");}
    static IOException error(String code){return new IOException(code);}
    static final class Result {final List<Map<String,Object>> grids;final List<String> warnings;Result(List<Map<String,Object>> grids,List<String>warnings){this.grids=grids;this.warnings=warnings;}}
}
