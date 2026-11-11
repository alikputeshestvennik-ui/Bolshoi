package ru.southcoast.terrain;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.*;
import java.io.*;
import java.nio.*;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.egl.EGLConfig;

public class MainActivity extends Activity {
    private TerrainView terrain;
    private Navigation navigation;
    private Navigation.MapView miniMap;
    private TextView location;
    private float mapX,mapZ,mapHeading;
    private long lastLocationUpdate;
    private boolean lastWalkMode;
    private final String[] cities={"Севастополь","Балаклава","Форос","Алупка","Ялта","Алушта"};
    private final double[][] cityCoordinates={{44.6167,33.525},{44.5,33.6},{44.391,33.788},{44.419,34.044},{44.495,34.164},{44.676,34.41}};
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void teleport(float[] point){
        if(point==null){Toast.makeText(this,"Рядом нет доступной точки. Выберите ближе к дороге.",Toast.LENGTH_LONG).show();return;}
        terrain.queueEvent(() -> {
            WalkScene w=terrain.render.walk;
            if(w.blocked(point[0],point[1])){runOnUiThread(() -> Toast.makeText(this,"Точка недоступна для прогулки",Toast.LENGTH_SHORT).show());return;}
            w.x=point[0];w.z=point[1];w.heading=point[2];w.pitch=0;w.forward=0;w.lastFrame=0;
            terrain.render.walkMode=true;lastLocationUpdate=0;terrain.requestRender();
        });
    }
    private void showMap(){
        terrain.queueEvent(() -> terrain.render.walk.forward=0);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),0,dp(12),0);
        TextView hint=new TextView(this);hint.setText("Север сверху. Нажмите на карту: переход к доступной дороге в радиусе 100 м. Голубая стрелка — ваша виртуальная позиция.");content.addView(hint);
        final AlertDialog[] dialog=new AlertDialog[1];
        Navigation.MapView map=new Navigation.MapView(this,navigation,terrain.render.walk,(x,z) -> {
            float[] point=navigation.nearest(x,z);
            if(point!=null)dialog[0].dismiss();teleport(point);
        });
        map.position(mapX,mapZ,mapHeading);
        content.addView(map,new LinearLayout.LayoutParams(-1,dp(280)));
        Button streets=new Button(this);streets.setText("Выбрать улицу по названию");
        streets.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Ялта · доступные улицы")
            .setItems(navigation.placeNames,(d,i) -> {dialog[0].dismiss();teleport(navigation.places[i]);}).setNegativeButton("Отмена",null).show());content.addView(streets);
        dialog[0]=new AlertDialog.Builder(this).setTitle("Карта прогулки · Ялта").setView(content).setNegativeButton("Закрыть",null).create();dialog[0].show();
    }
    private void showCities(){
        new AlertDialog.Builder(this).setTitle("Центр общего вида · города")
            .setItems(cities,(d,i) -> terrain.queueEvent(() -> {
                double lat=cityCoordinates[i][0],lon=cityCoordinates[i][1],phi=Math.toRadians(44.55),e=.00669437999014;
                double n=6378137/Math.sqrt(1-e*Math.sin(phi)*Math.sin(phi));
                double m=6378137*(1-e)/Math.pow(1-e*Math.sin(phi)*Math.sin(phi),1.5);
                TerrainRenderer r=terrain.render;r.focusX=(float)(Math.toRadians(lon-33.925)*n*Math.cos(phi)/1000);r.focusZ=(float)(-Math.toRadians(lat-44.55)*m/1000);
                r.overviewLabel=String.format(java.util.Locale.ROOT,"Центр обзора: %s · %.4f° N, %.4f° E\nДетальная прогулка доступна на карте района Ялты",cities[i],lat,lon);
                r.walkMode=false;r.walk.forward=0;r.zoom=6;r.yaw=0;r.tilt=55;lastLocationUpdate=0;terrain.requestRender();
            })).setNegativeButton("Отмена",null).show();
    }
    // Called on the GL thread; UI receives immutable position snapshots.
    private void updateLocation(){
        TerrainRenderer r=terrain.render;long now=System.nanoTime();
        if(r.walkMode==lastWalkMode && now-lastLocationUpdate<250000000L)return;
        lastLocationUpdate=now;lastWalkMode=r.walkMode;
        final boolean walking=r.walkMode;final float x=r.walk.x,z=r.walk.z,h=r.walk.heading;
        final String text=walking ? navigation.location(x,z,h) : r.overviewLabel;
        runOnUiThread(() -> {location.setText(text);mapX=x;mapZ=z;mapHeading=h;miniMap.setVisibility(walking?View.VISIBLE:View.GONE);miniMap.position(x,z,h);});
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setOnApplyWindowInsetsListener((v,insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("ЮБК · Севастополь — Алушта\nРеальный масштаб высот · 1:1");
        title.setTextSize(16); title.setPadding(20,12,20,12);
        layout.addView(title);
        try {
            navigation=new Navigation(getAssets());
            terrain = new TerrainView();
            location=new TextView(this);location.setTextSize(12);location.setPadding(dp(10),dp(4),dp(10),dp(4));layout.addView(location);
            FrameLayout viewport=new FrameLayout(this);viewport.addView(terrain,new FrameLayout.LayoutParams(-1,-1));
            miniMap=new Navigation.MapView(this,navigation,terrain.render.walk,null);miniMap.setOnClickListener(v -> showMap());
            FrameLayout.LayoutParams miniParams=new FrameLayout.LayoutParams(dp(148),dp(112),Gravity.TOP|Gravity.END);miniParams.setMargins(0,dp(6),dp(6),0);viewport.addView(miniMap,miniParams);miniMap.setVisibility(View.GONE);
            mapX=terrain.render.walk.x;mapZ=terrain.render.walk.z;mapHeading=terrain.render.walk.heading;
            layout.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout destinations=new LinearLayout(this);
            Button mapButton=new Button(this);mapButton.setText("Карта / телепорт");mapButton.setOnClickListener(v -> showMap());destinations.addView(mapButton,new LinearLayout.LayoutParams(0,-2,1));
            Button cityButton=new Button(this);cityButton.setText("Города ЮБК");cityButton.setOnClickListener(v -> showCities());destinations.addView(cityButton,new LinearLayout.LayoutParams(0,-2,1));layout.addView(destinations);
            Button reset = new Button(this); reset.setText("Вернуть общий вид");
            reset.setOnClickListener(v -> terrain.queueEvent(() -> { terrain.render.walkMode=false; terrain.render.walk.forward=0; terrain.render.yaw=0; terrain.render.tilt=55; terrain.render.zoom=1; terrain.render.focusX=0;terrain.render.focusZ=0;terrain.render.overviewLabel="ЮБК · общий вид · север сверху при сбросе";lastLocationUpdate=0; terrain.requestRender(); }));
            layout.addView(reset);
            LinearLayout controls = new LinearLayout(this);
            Button walk = new Button(this); walk.setText("Прогулка · Ялта");
            walk.setOnClickListener(v -> terrain.queueEvent(() -> {terrain.render.walk.reset();terrain.render.walkMode=true;lastLocationUpdate=0;terrain.requestRender();}));
            controls.addView(walk,new LinearLayout.LayoutParams(0,-2,2));
            for(int direction : new int[]{-1,1}) {
                Button move = new Button(this);move.setText(direction==1 ? "Вперёд" : "Назад");
                move.setOnTouchListener((v,e) -> {
                    int action=e.getActionMasked();
                    if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){
                        float speed=action==MotionEvent.ACTION_DOWN ? direction : 0;
                        terrain.queueEvent(() -> {terrain.render.walk.forward=speed;terrain.requestRender();});
                    }
                    return true;
                });
                controls.addView(move,new LinearLayout.LayoutParams(0,-2,1));
            }
            layout.addView(controls);
            TextView help = new TextView(this);
            help.setTextSize(11);
            help.setText("Прогулка: взгляд пальцем, движение — удерживайте кнопку.\nПилот Ялты: ≈ 1,6 × 1,1 км. Дома и деревья упрощены.\n© OpenStreetMap contributors · ODbL · openstreetmap.org/copyright\nРельеф: Mapzen / Tilezen · SRTM NASA / USGS");
            help.setPadding(20,8,20,8); layout.addView(help);
        } catch (IOException e) {
            TextView error = new TextView(this); error.setText("Не удалось загрузить рельеф. Пересоберите приложение с terrain.bin."); layout.addView(error);
        }
        setContentView(layout);
        layout.requestApplyInsets();
    }
    @Override protected void onPause(){ super.onPause(); if(terrain!=null){terrain.queueEvent(() -> {terrain.render.walk.forward=0;terrain.render.walk.lastFrame=0;});terrain.onPause();} }
    @Override protected void onResume(){ super.onResume(); if(terrain!=null)terrain.onResume(); }
    class TerrainView extends GLSurfaceView {
        final TerrainRenderer render;
        final ScaleGestureDetector scale;
        float lastX,lastY;
        TerrainView() throws IOException {
            super(MainActivity.this);
            render = new TerrainRenderer(); setRenderer(render); setRenderMode(RENDERMODE_WHEN_DIRTY);
            scale = new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){
                    float factor=d.getScaleFactor(); queueEvent(() -> {render.zoom=Math.max(.5f,Math.min(12,render.zoom*factor)); requestRender();}); return true;
                }
            });
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            scale.onTouchEvent(e);
            float x=e.getX(), y=e.getY();
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE && e.getPointerCount()==1 && !scale.isInProgress()){
                float dx=x-lastX,dy=y-lastY;
                float lookX=dx/Math.max(1,getWidth())*120f,lookY=dy/Math.max(1,getHeight())*65f;
                queueEvent(() -> {if(render.walkMode){render.walk.heading+=lookX;render.walk.pitch=Math.max(-50,Math.min(50,render.walk.pitch-lookY));}
                    else {render.yaw+=dx*.25f; render.tilt=Math.max(5,Math.min(85,render.tilt+dy*.25f));} requestRender();});
            }
            lastX=x;lastY=y;return true;
        }
    }
    class TerrainRenderer implements GLSurfaceView.Renderer {
        FloatBuffer vertices, colors;
        final WalkScene walk; boolean walkMode=false;
        int count; float yaw=0,tilt=55,zoom=1,aspect=1,focusX=0,focusZ=0;
        String overviewLabel="ЮБК · общий вид · выберите город или карту прогулки";
        TerrainRenderer() throws IOException {
            walk=new WalkScene(getAssets());
            try(DataInputStream in=new DataInputStream(getAssets().open("terrain.bin"))){
                int w=in.readInt(),h=in.readInt();
                if(w<2||h<2||w*h>1000000)throw new IOException("Invalid mesh");
                float[][] p=new float[w*h][3];
                for(float[] v:p)for(int k=0;k<3;k++)v[k]=in.readFloat()/1000f;
                count=(w-1)*(h-1)*6;
                vertices=ByteBuffer.allocateDirect(count*12).order(ByteOrder.nativeOrder()).asFloatBuffer();
                colors=ByteBuffer.allocateDirect(count*16).order(ByteOrder.nativeOrder()).asFloatBuffer();
                for(int r=0;r<h-1;r++)for(int c=0;c<w-1;c++){
                    int i=r*w+c; triangle(p[i],p[i+w],p[i+1]); triangle(p[i+1],p[i+w],p[i+w+1]);
                }
                vertices.position(0);colors.position(0);
            }
        }
        void triangle(float[] a,float[] b,float[] c){
            float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            float shade=.5f+.5f*Math.max(0,(-nx*.4f+ny*.8f+nz*.4f)/len);
            for(float[] p:new float[][]{a,b,c}){
                vertices.put(p); float t=Math.min(1,p[1]/1.6f);
                if(p[1]<=0)colors.put(new float[]{.07f,.28f,.42f,1});
                else colors.put(new float[]{(.2f+.55f*t)*shade,(.48f+.2f*t)*shade,(.25f+.4f*t)*shade,1});
            }
        }
        public void onSurfaceCreated(GL10 gl,EGLConfig config){gl.glClearColor(.035f,.065f,.09f,1);gl.glEnable(GL10.GL_DEPTH_TEST);}
        public void onSurfaceChanged(GL10 gl,int w,int h){gl.glViewport(0,0,w,h);aspect=(float)w/Math.max(1,h);}
        public void onDrawFrame(GL10 gl){
            if(walkMode){walk.draw(gl,aspect);updateLocation();if(walk.forward!=0)terrain.requestRender();return;}
            gl.glClearColor(.035f,.065f,.09f,1);
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT|GL10.GL_DEPTH_BUFFER_BIT);
            gl.glMatrixMode(GL10.GL_PROJECTION);gl.glLoadIdentity();
            float radius=55/zoom;float halfY=radius/Math.min(1,aspect);
            gl.glOrthof(-halfY*aspect,halfY*aspect,-halfY,halfY,1,500);
            gl.glMatrixMode(GL10.GL_MODELVIEW);gl.glLoadIdentity();
            GLU.gluLookAt(gl,0,0,200,0,0,0,0,1,0);
            gl.glRotatef(tilt,1,0,0);gl.glRotatef(yaw,0,1,0);gl.glTranslatef(-focusX,0,-focusZ);
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
            gl.glVertexPointer(3,GL10.GL_FLOAT,0,vertices);gl.glColorPointer(4,GL10.GL_FLOAT,0,colors);
            gl.glDrawArrays(GL10.GL_TRIANGLES,0,count);
            updateLocation();
        }
    }
}
