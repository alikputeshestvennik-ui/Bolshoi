package ru.southcoast.terrain;

import android.content.res.AssetManager;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Offline street names and prevalidated landing points. Coordinates are local metres. */
final class Navigation {
    final float[][] roads,points,places;
    final String[] roadNames,pointNames,placeNames;
    final double sx,sz;
    Navigation(AssetManager assets) throws IOException {
        try(InputStream in=assets.open("navigation.json"); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            JSONObject j=new JSONObject(out.toString("UTF-8"));sx=j.getDouble("sx");sz=j.getDouble("sz");
            JSONArray a=j.getJSONArray("roads");roads=new float[a.length()][4];roadNames=new String[a.length()];
            for(int i=0;i<a.length();i++){JSONArray p=a.getJSONArray(i);for(int k=0;k<4;k++)roads[i][k]=(float)p.getDouble(k);roadNames[i]=p.getString(4);}
            a=j.getJSONArray("points");points=new float[a.length()][3];pointNames=new String[a.length()];
            for(int i=0;i<a.length();i++){JSONArray p=a.getJSONArray(i);for(int k=0;k<3;k++)points[i][k]=(float)p.getDouble(k);pointNames[i]=p.getString(3);}
            a=j.getJSONArray("places");places=new float[a.length()][3];placeNames=new String[a.length()];
            for(int i=0;i<a.length();i++){JSONArray p=a.getJSONArray(i);for(int k=0;k<3;k++)places[i][k]=(float)p.getDouble(k);placeNames[i]=p.getString(3);}
        }catch(JSONException e){throw new IOException("Invalid navigation data",e);}
    }
    String location(float x,float z,float heading){
        float best=Float.MAX_VALUE;int index=0;
        for(int i=0;i<roads.length;i++){
            float[] r=roads[i];float vx=r[2]-r[0],vz=r[3]-r[1],l=vx*vx+vz*vz;
            float t=l==0?0:Math.max(0,Math.min(1,((x-r[0])*vx+(z-r[1])*vz)/l));
            float ex=x-r[0]-t*vx,ez=z-r[1]-t*vz,d=ex*ex+ez*ez;
            if(d<best){best=d;index=i;}
        }
        float bearing=(heading%360+360)%360;
        String[] compass={"С","СВ","В","ЮВ","Ю","ЮЗ","З","СЗ"};
        return String.format(Locale.ROOT,"Ялта · рядом: %s (%.0f м)\n%.5f° N, %.5f° E · взгляд %s %.0f°",roadNames[index],Math.sqrt(best),44.492-z/sz,34.155+x/sx,compass[Math.round(bearing/45)%8],bearing);
    }
    float[] nearest(float x,float z){
        float best=100*100;float[] found=null;
        for(float[] p:points){float d=(x-p[0])*(x-p[0])+(z-p[1])*(z-p[1]);if(d<best){best=d;found=p;}}
        return found;
    }
    interface Pick {void at(float x,float z);}
    static final class MapView extends View {
        final Navigation nav;final WalkScene scene;final Pick pick;
        final Paint paint=new Paint(3);final Path path=new Path();
        final ScaleGestureDetector pinch;
        final float density;final int slop;
        float x,z,heading,scale,left,top,zoom=1,panX,panY,lastX,lastY,downX,downY;
        boolean dragged,multi;float[] selection;
        MapView(Context context,Navigation nav,WalkScene scene,Pick pick){
            super(context);this.nav=nav;this.scene=scene;this.pick=pick;
            density=getResources().getDisplayMetrics().density;slop=ViewConfiguration.get(context).getScaledTouchSlop();
            setContentDescription("Карта Ялты с названиями улиц. Масштаб двумя пальцами, перемещение одним. Тап выбирает место.");
            pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){
                    float next=Math.max(1,Math.min(16,zoom*d.getScaleFactor()));
                    float ratio=next/zoom;
                    panX=d.getFocusX()-getWidth()/2f-(d.getFocusX()-getWidth()/2f-panX)*ratio;
                    panY=d.getFocusY()-getHeight()/2f-(d.getFocusY()-getHeight()/2f-panY)*ratio;
                    zoom=next;clampPan();invalidate();return true;
                }
            });
            pinch.setQuickScaleEnabled(false);
        }
        void position(float x,float z,float heading){this.x=x;this.z=z;this.heading=heading;invalidate();}
        void select(float[] p){selection=p;invalidate();}
        void resetView(){zoom=1;panX=panY=0;invalidate();}
        float baseScale(){return Math.max(.0001f,Math.min((getWidth()-24*density)/(scene.halfX*2),(getHeight()-24*density)/(scene.halfZ*2)));}
        void clampPan(){float s=baseScale()*zoom;float mx=Math.max(0,scene.halfX*s-getWidth()/2f+24*density),my=Math.max(0,scene.halfZ*s-getHeight()/2f+24*density);panX=Math.max(-mx,Math.min(mx,panX));panY=Math.max(-my,Math.min(my,panY));}
        void transform(){scale=baseScale()*zoom;left=getWidth()/2f+panX-scene.halfX*scale;top=getHeight()/2f+panY-scene.halfZ*scale;}
        float px(float x){return left+(x+scene.halfX)*scale;}
        float py(float z){return top+(z+scene.halfZ)*scale;}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);transform();c.drawColor(Color.rgb(24,38,40));
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(87,102,91));c.drawRect(px(-scene.halfX),py(-scene.halfZ),px(scene.halfX),py(scene.halfZ),paint);
            paint.setColor(Color.rgb(151,155,141));
            for(float[] poly:scene.polygons){path.reset();for(int i=0;i<poly.length;i+=2){if(i==0)path.moveTo(px(poly[i]),py(poly[i+1]));else path.lineTo(px(poly[i]),py(poly[i+1]));}path.close();c.drawPath(path,paint);}
            paint.setColor(Color.rgb(237,211,158));paint.setStrokeWidth(Math.max(density,4*scale));
            for(float[] r:nav.roads)c.drawLine(px(r[0]),py(r[1]),px(r[2]),py(r[3]),paint);
            if(pick!=null)labels(c);
            c.save();c.translate(px(x),py(z));c.rotate(heading);paint.setColor(Color.rgb(50,205,255));paint.setStyle(Paint.Style.FILL);
            path.reset();path.moveTo(0,-9*density);path.lineTo(6*density,7*density);path.lineTo(0,3*density);path.lineTo(-6*density,7*density);path.close();c.drawPath(path,paint);c.restore();
            if(selection!=null){paint.setColor(Color.rgb(255,171,60));c.drawCircle(px(selection[0]),py(selection[1]),8*density,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*density);c.drawCircle(px(selection[0]),py(selection[1]),13*density,paint);paint.setStyle(Paint.Style.FILL);}
            paint.setColor(Color.WHITE);paint.setTextSize(12*density);c.drawText("С ↑",8*density,18*density,paint);
            int meters=zoom<2?200:zoom<5?100:zoom<10?50:20;
            paint.setStrokeWidth(2*density);c.drawLine(10*density,getHeight()-16*density,10*density+meters*scale,getHeight()-16*density,paint);c.drawText(meters+" м",10*density,getHeight()-23*density,paint);
        }
        void labels(Canvas c){
            LinkedHashMap<String,Integer> visible=new LinkedHashMap<>();
            for(int i=0;i<nav.roads.length;i++){
                String name=nav.roadNames[i];if(name.startsWith("Безымянная"))continue;
                float[] r=nav.roads[i];float xx=px((r[0]+r[2])/2),yy=py((r[1]+r[3])/2);
                if(xx<16*density||xx>getWidth()-16*density||yy<32*density||yy>getHeight()-40*density)continue;
                Integer old=visible.get(name);
                if(old==null||length(r)>length(nav.roads[old]))visible.put(name,i);
            }
            ArrayList<RectF> occupied=new ArrayList<>();paint.setTextSize(12*density);paint.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
            for(Map.Entry<String,Integer> e:visible.entrySet()){
                float[] r=nav.roads[e.getValue()];String text=e.getKey();float width=paint.measureText(text);
                float xx=px((r[0]+r[2])/2)-width/2,yy=py((r[1]+r[3])/2)-5*density;
                RectF bounds=new RectF(xx-4*density,yy-14*density,xx+width+4*density,yy+4*density);
                if(bounds.left<4||bounds.right>getWidth()-4)continue;
                boolean overlap=false;for(RectF rect:occupied)if(RectF.intersects(rect,bounds)){overlap=true;break;}
                if(overlap)continue;occupied.add(bounds);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setColor(Color.rgb(30,43,39));c.drawText(text,xx,yy,paint);
                paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(255,248,224));c.drawText(text,xx,yy,paint);
            }
            paint.setTypeface(Typeface.DEFAULT);
        }
        float length(float[] r){return (r[2]-r[0])*(r[2]-r[0])+(r[3]-r[1])*(r[3]-r[1]);}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(pick==null)return super.onTouchEvent(e);
            pinch.onTouchEvent(e);int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){lastX=downX=e.getX();lastY=downY=e.getY();dragged=multi=false;getParent().requestDisallowInterceptTouchEvent(true);}
            else if(action==MotionEvent.ACTION_POINTER_DOWN){multi=true;dragged=true;}
            else if(action==MotionEvent.ACTION_MOVE){
                if(Math.hypot(e.getX()-downX,e.getY()-downY)>slop)dragged=true;
                if(e.getPointerCount()==1&&!pinch.isInProgress()&&!multi){panX+=e.getX()-lastX;panY+=e.getY()-lastY;clampPan();invalidate();}
                lastX=e.getX();lastY=e.getY();
            }else if(action==MotionEvent.ACTION_UP){
                getParent().requestDisallowInterceptTouchEvent(false);
                if(!dragged&&!multi){performClick();transform();float wx=(e.getX()-left)/scale-scene.halfX,wz=(e.getY()-top)/scale-scene.halfZ;if(Math.abs(wx)<=scene.halfX&&Math.abs(wz)<=scene.halfZ)pick.at(wx,wz);}
            }else if(action==MotionEvent.ACTION_CANCEL){dragged=true;getParent().requestDisallowInterceptTouchEvent(false);}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
