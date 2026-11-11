package ru.southcoast.terrain;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.egl.*;

public class MainActivity extends Activity {
    private TerrainView terrain;
    private Navigation navigation;
    private Navigation.MapView map,miniMap;
    private LinearLayout mapScreen,walkScreen;
    private TextView location;
    private long lastLocationUpdate;
    private boolean hasWalked=false;
    private AlertDialog teleportDialog;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private Button button(String text,View.OnClickListener action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(action);return b;}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(dp(12),dp(5),dp(12),dp(5));return t;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void confirmTeleport(float[] point){
        if(point==null){Toast.makeText(this,"Выберите точку ближе к дороге — не дальше 100 м",Toast.LENGTH_LONG).show();return;}
        map.select(point);
        String description=navigation.location(point[0],point[1],point[2])+"\n\nПерейти к отмеченной точке на дороге?";
        teleportDialog=new AlertDialog.Builder(this).setTitle("Телепорт").setMessage(description)
            .setPositiveButton("Перейти",(d,which) -> teleport(point))
            .setNegativeButton("Отмена",(d,which) -> map.select(null))
            .setOnCancelListener(d -> map.select(null)).create();
        teleportDialog.setOnDismissListener(d -> {teleportDialog=null;map.select(null);});
        teleportDialog.show();
    }
    private void teleport(float[] p){
        terrain.queueEvent(() -> {
            WalkScene w=terrain.scene;
            if(w.blocked(p[0],p[1])){runOnUiThread(() -> Toast.makeText(this,"Точка недоступна",Toast.LENGTH_SHORT).show());return;}
            w.x=p[0];w.z=p[1];w.heading=p[2];w.pitch=0;w.forward=0;w.lastFrame=0;lastLocationUpdate=0;
            runOnUiThread(() -> {hasWalked=true;map.select(null);mapScreen.setVisibility(View.GONE);walkScreen.setVisibility(View.VISIBLE);terrain.requestRender();});
        });
    }
    private void showMap(){
        terrain.queueEvent(() -> {terrain.scene.forward=0;terrain.scene.lastFrame=0;});
        mapScreen.setVisibility(View.VISIBLE);walkScreen.setVisibility(View.GONE);
    }
    private void showStreets(){new AlertDialog.Builder(this).setTitle("Улицы пилотного района Ялты").setItems(navigation.placeNames,(d,i) -> confirmTeleport(navigation.places[i])).setNegativeButton("Закрыть",null).show();}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        FrameLayout root=new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((v,i) -> {v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        try {
            navigation=new Navigation(getAssets());terrain=new TerrainView();
            mapScreen=column();walkScreen=column();
            mapScreen.addView(text("Ялта · выберите место прогулки",20));
            mapScreen.addView(text("Два пальца — масштаб · один — перемещение\nТап по карте — телепорт с подтверждением",12));
            map=new Navigation.MapView(this,navigation,terrain.scene,(x,z) -> confirmTeleport(navigation.nearest(x,z)));
            map.position(terrain.scene.x,terrain.scene.z,terrain.scene.heading);
            mapScreen.addView(map,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout mapButtons=new LinearLayout(this);
            mapButtons.addView(button("Улицы",v -> showStreets()),new LinearLayout.LayoutParams(0,-2,1));
            mapButtons.addView(button("Весь район",v -> map.resetView()),new LinearLayout.LayoutParams(0,-2,1));
            mapButtons.addView(button("Продолжить",v -> {
                if(!hasWalked){Toast.makeText(this,"Сначала выберите точку на карте",Toast.LENGTH_SHORT).show();return;}
                mapScreen.setVisibility(View.GONE);walkScreen.setVisibility(View.VISIBLE);terrain.requestRender();
            }),new LinearLayout.LayoutParams(0,-2,1));mapScreen.addView(mapButtons);
            mapScreen.addView(text("Пилот Ялты · виртуальная позиция, не GPS\n© OpenStreetMap contributors · openstreetmap.org/copyright\nРельеф: Mapzen / Tilezen · NASA / USGS",10));
            location=text("Ялта · прогулка",12);walkScreen.addView(location);
            FrameLayout viewport=new FrameLayout(this);viewport.addView(terrain,new FrameLayout.LayoutParams(-1,-1));
            miniMap=new Navigation.MapView(this,navigation,terrain.scene,null);miniMap.setOnClickListener(v -> showMap());
            FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(112),dp(84),Gravity.TOP|Gravity.END);mp.setMargins(0,dp(5),dp(5),0);viewport.addView(miniMap,mp);
            walkScreen.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout controls=new LinearLayout(this);controls.addView(button("Карта",v -> showMap()),new LinearLayout.LayoutParams(0,-2,1));
            for(int direction:new int[]{-1,1}){
                Button move=button(direction==1?"Вперёд":"Назад",v -> {});
                move.setOnTouchListener((v,e) -> {
                    int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){float f=a==MotionEvent.ACTION_DOWN?direction:0;terrain.queueEvent(() -> {terrain.scene.forward=f;terrain.requestRender();});}
                    if(a==MotionEvent.ACTION_UP)v.performClick();return true;
                });controls.addView(move,new LinearLayout.LayoutParams(0,-2,1));
            }
            Button speed=button("Скорость: 5 м/с",null);
            speed.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Скорость перемещения").setItems(new String[]{"Прогулка · 3 м/с","Быстро · 5 м/с","Обзор · 8 м/с"},(d,i) -> {float value=new float[]{3,5,8}[i];terrain.queueEvent(() -> terrain.scene.moveSpeed=value);speed.setText("Скорость: "+(int)value+" м/с");}).show());
            walkScreen.addView(controls);walkScreen.addView(speed);
            walkScreen.addView(text("Взгляд — пальцем · движение — удерживайте кнопку\n© OpenStreetMap contributors · Mapzen / Tilezen",10));
            root.addView(walkScreen,new FrameLayout.LayoutParams(-1,-1));root.addView(mapScreen,new FrameLayout.LayoutParams(-1,-1));walkScreen.setVisibility(View.GONE);
        }catch(IOException e){root.addView(text("Не удалось открыть данные района. Установите APK заново.",16));}
        setContentView(root);root.requestApplyInsets();
    }
    @Override public void onBackPressed(){if(walkScreen!=null&&walkScreen.getVisibility()==View.VISIBLE)showMap();else super.onBackPressed();}
    @Override protected void onPause(){super.onPause();if(terrain!=null){terrain.queueEvent(() -> {terrain.scene.forward=0;terrain.scene.lastFrame=0;});terrain.onPause();}}
    @Override protected void onResume(){super.onResume();if(terrain!=null)terrain.onResume();}
    private void updateLocation(){
        long now=System.nanoTime();if(now-lastLocationUpdate<250000000L)return;lastLocationUpdate=now;
        float x=terrain.scene.x,z=terrain.scene.z,h=terrain.scene.heading;String label=navigation.location(x,z,h);
        runOnUiThread(() -> {location.setText(label);map.position(x,z,h);miniMap.position(x,z,h);});
    }
    class TerrainView extends GLSurfaceView implements GLSurfaceView.Renderer {
        final WalkScene scene;float aspect=1,lastX,lastY;
        volatile int glError=0;volatile int frames=0;
        TerrainView() throws IOException {
            super(MainActivity.this);scene=new WalkScene(getAssets());
            // Prefer 24-bit depth; fall back to 16-bit on older GLES devices.
            setEGLConfigChooser((egl,display) -> {
                for(int depth:new int[]{24,16}){
                    int[] spec={EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,EGL10.EGL_DEPTH_SIZE,depth,EGL10.EGL_RENDERABLE_TYPE,1,EGL10.EGL_NONE};
                    EGLConfig[] configs=new EGLConfig[1];int[] n=new int[1];
                    if(egl.eglChooseConfig(display,spec,configs,1,n)&&n[0]>0)return configs[0];
                }
                throw new IllegalArgumentException("No GLES depth buffer configuration");
            });
            setRenderer(this);setRenderMode(RENDERMODE_WHEN_DIRTY);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(),y=e.getY();
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1){float dx=(x-lastX)/Math.max(1,getWidth())*120,dy=(y-lastY)/Math.max(1,getHeight())*65;queueEvent(() -> {scene.heading+=dx;scene.pitch=Math.max(-50,Math.min(50,scene.pitch-dy));requestRender();});}
            lastX=x;lastY=y;return true;
        }
        public void onSurfaceCreated(GL10 gl,EGLConfig c){gl.glEnable(GL10.GL_DEPTH_TEST);}
        public void onSurfaceChanged(GL10 gl,int w,int h){gl.glViewport(0,0,w,h);aspect=(float)w/Math.max(1,h);}
        public void onDrawFrame(GL10 gl){scene.draw(gl,aspect);glError=gl.glGetError();frames++;updateLocation();if(scene.forward!=0)requestRender();}
    }
}
