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
        float x,z,heading,scale,left,top;final float density;
        MapView(Context context,Navigation nav,WalkScene scene,Pick pick){super(context);this.nav=nav;this.scene=scene;this.pick=pick;density=getResources().getDisplayMetrics().density;setContentDescription("Карта Ялты. Север сверху. Стрелка — виртуальная позиция.");}
        void position(float x,float z,float heading){this.x=x;this.z=z;this.heading=heading;invalidate();}
        float px(float x){return left+(x+scene.halfX)*scale;}
        float py(float z){return top+(z+scene.halfZ)*scale;}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.drawColor(Color.rgb(24,38,40));
            float pad=12*density;scale=Math.min((getWidth()-pad*2)/(scene.halfX*2),(getHeight()-pad*2)/(scene.halfZ*2));
            left=(getWidth()-scene.halfX*2*scale)/2;top=(getHeight()-scene.halfZ*2*scale)/2;
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(87,102,91));c.drawRect(left,top,getWidth()-left,getHeight()-top,paint);
            paint.setColor(Color.rgb(151,155,141));
            for(float[] poly:scene.polygons){path.reset();for(int i=0;i<poly.length;i+=2){if(i==0)path.moveTo(px(poly[i]),py(poly[i+1]));else path.lineTo(px(poly[i]),py(poly[i+1]));}path.close();c.drawPath(path,paint);}
            paint.setColor(Color.rgb(237,211,158));paint.setStrokeWidth(Math.max(1,3*scale));
            for(float[] r:nav.roads)c.drawLine(px(r[0]),py(r[1]),px(r[2]),py(r[3]),paint);
            c.save();c.translate(px(x),py(z));c.rotate(heading);paint.setColor(Color.rgb(50,205,255));
            path.reset();path.moveTo(0,-9*density);path.lineTo(6*density,7*density);path.lineTo(0,3*density);path.lineTo(-6*density,7*density);path.close();c.drawPath(path,paint);c.restore();
            paint.setColor(Color.WHITE);paint.setTextSize(12*density);c.drawText("С ↑",8*density,16*density,paint);
            c.drawLine(10*density,getHeight()-16*density,10*density+200*scale,getHeight()-16*density,paint);c.drawText("200 м",10*density,getHeight()-20*density,paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(pick==null)return super.onTouchEvent(e);
            if(e.getActionMasked()==MotionEvent.ACTION_UP){performClick();if(scale>0&&e.getX()>=left&&e.getX()<=getWidth()-left&&e.getY()>=top&&e.getY()<=getHeight()-top)pick.at((e.getX()-left)/scale-scene.halfX,(e.getY()-top)/scale-scene.halfZ);}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
