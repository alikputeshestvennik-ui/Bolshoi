package ru.southcoast.terrain;

import android.content.res.AssetManager;
import android.opengl.GLU;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.zip.GZIPInputStream;
import javax.microedition.khronos.opengles.GL10;

/** Metric local pilot scene. All state is owned by the GL thread. */
final class WalkScene {
    final FloatBuffer mesh;
    final int count,w,h;
    int parts,baseVertices;
    float[][] windowBatches;
    final float dx,dz,halfX,halfZ;
    final float[] heights,spawn;
    final float[][] polygons;
    final String tileId,name;
    final float originLat,originLon;
    float x,z,heading,pitch=0,forward=0,moveSpeed=5;
    long lastFrame;
    WalkScene(AssetManager assets) throws IOException {
        this(assets,"walk");
    }
    WalkScene(AssetManager assets,String prefix) throws IOException {
        tileId=prefix.substring(prefix.lastIndexOf('/')+1);
        try(InputStream in=assets.open(prefix+".json"); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int size;
            while((size=in.read(buffer))!=-1)out.write(buffer,0,size);
            JSONObject json=new JSONObject(out.toString("UTF-8"));
            name=json.optString("name","Южнобережное шоссе");
            JSONArray origin=json.optJSONArray("origin");
            originLat=origin==null?44.55f:(float)origin.getDouble(0);
            originLon=origin==null?33.95f:(float)origin.getDouble(1);
            baseVertices=json.getInt("base_vertices");
            JSONArray batches=json.getJSONArray("window_batches");windowBatches=new float[batches.length()][4];
            for(int i=0;i<windowBatches.length;i++)for(int k=0;k<4;k++)windowBatches[i][k]=(float)batches.getJSONArray(i).getDouble(k);
            parts=json.getInt("mesh_parts");w=json.getInt("w");h=json.getInt("h");dx=(float)json.getDouble("dx");dz=(float)json.getDouble("dz");
            halfX=(w-1)*dx/2;halfZ=(h-1)*dz/2;
            JSONArray values=json.getJSONArray("heights");heights=new float[w*h];
            if(values.length()>0&&values.optJSONArray(0)!=null){
                for(int r=0;r<h;r++){JSONArray row=values.getJSONArray(r);for(int c=0;c<w;c++)heights[r*w+c]=(float)row.getDouble(c);}
            }else{
                for(int i=0;i<heights.length;i++)heights[i]=(float)values.getDouble(i);
            }
            values=json.getJSONArray("spawn");spawn=new float[3];for(int i=0;i<3;i++)spawn[i]=(float)values.getDouble(i);
            JSONArray buildings=json.getJSONArray("buildings");polygons=new float[buildings.length()][];
            for(int i=0;i<polygons.length;i++){
                JSONArray p=buildings.getJSONArray(i);polygons[i]=new float[p.length()*2];
                for(int k=0;k<p.length();k++){polygons[i][k*2]=(float)p.getJSONArray(k).getDouble(0);polygons[i][k*2+1]=(float)p.getJSONArray(k).getDouble(1);}
            }
        }catch(JSONException e){throw new IOException("Invalid walk metadata",e);}
        java.util.Vector<InputStream> streams=new java.util.Vector<>();
        try {
            for(int i=0;i<parts;i++)streams.add(assets.open(String.format(java.util.Locale.ROOT,prefix+".mesh.%03d.gzpart",i)));
        }catch(IOException e){for(InputStream stream:streams)stream.close();throw e;}
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(new SequenceInputStream(streams.elements())),65536))){
            count=in.readInt();if(count<3||count>4000000||count%3!=0)throw new IOException("Invalid walk mesh");
            mesh=ByteBuffer.allocateDirect(count*28).order(ByteOrder.nativeOrder()).asFloatBuffer();
            // Assets contain XYZ + RGB. GLES 1.1 requires RGBA in the color array.
            for(int i=0;i<count;i++){
                for(int k=0;k<6;k++)mesh.put(in.readFloat());
                mesh.put(1f);
            }
            mesh.position(0);
        }
        reset();
    }
    void reset(){x=spawn[0];z=spawn[1];heading=spawn[2];pitch=0;forward=0;lastFrame=0;}
    float globalX(){return x+(originLon-Region.REGION_ORIGIN_LON)*Region.REGION_SX;}
    float globalZ(){return z+(Region.REGION_ORIGIN_LAT-originLat)*Region.REGION_SZ;}
    boolean nearEdge(){return Math.abs(x)>halfX-45||Math.abs(z)>halfZ-45;}
    float ground(float x,float z){
        float u=Math.max(0,Math.min(w-1.00001f,(x+halfX)/dx)),v=Math.max(0,Math.min(h-1.00001f,(z+halfZ)/dz));
        int c=(int)u,r=(int)v;u-=c;v-=r;
        float a=heights[r*w+c],b=heights[r*w+c+1],cc=heights[(r+1)*w+c],d=heights[(r+1)*w+c+1];
        return u+v<=1 ? a+(b-a)*u+(cc-a)*v : d+(cc-d)*(1-u)+(b-d)*(1-v);
    }
    boolean blocked(float px,float pz){
        if(Math.abs(px)>halfX-3||Math.abs(pz)>halfZ-3||ground(px,pz)<.7f)return true;
        for(float[] p:polygons){
            boolean hit=false;
            for(int i=0,j=p.length-2;i<p.length;j=i,i+=2){
                float ax=p[i],az=p[i+1],bx=p[j],bz=p[j+1];
                if((az>pz)!=(bz>pz)&&px<(bx-ax)*(pz-az)/(bz-az)+ax)hit=!hit;
                float vx=bx-ax,vz=bz-az,len=vx*vx+vz*vz;
                float t=len==0?0:Math.max(0,Math.min(1,((px-ax)*vx+(pz-az)*vz)/len));
                float ex=px-ax-t*vx,ez=pz-az-t*vz;
                if(ex*ex+ez*ez<.25f)return true;
            }
            if(hit)return true;
        }
        return false;
    }
    void draw(GL10 gl,float aspect){
        long now=System.nanoTime();float dt=lastFrame==0?0:Math.min(.15f,(now-lastFrame)/1e9f);lastFrame=now;
        float rad=(float)Math.toRadians(heading),look=(float)Math.toRadians(pitch);
        // Small collision substeps preserve speed at low FPS without tunnelling through walls.
        int steps=Math.max(1,(int)Math.ceil(moveSpeed*dt/.2f));
        if(forward!=0)for(int i=0;i<steps;i++){
            float nx=x+(float)Math.sin(rad)*forward*moveSpeed*dt/steps,nz=z-(float)Math.cos(rad)*forward*moveSpeed*dt/steps;
            if(blocked(nx,nz))break;x=nx;z=nz;
        }
        gl.glClearColor(.62f,.77f,.86f,1);gl.glClear(GL10.GL_COLOR_BUFFER_BIT|GL10.GL_DEPTH_BUFFER_BIT);
        gl.glMatrixMode(GL10.GL_PROJECTION);gl.glLoadIdentity();GLU.gluPerspective(gl,65,aspect,.5f,2200);
        gl.glMatrixMode(GL10.GL_MODELVIEW);gl.glLoadIdentity();
        float eye=ground(x,z)+1.9f;
        GLU.gluLookAt(gl,x,eye,z,x+(float)(Math.sin(rad)*Math.cos(look)),eye+(float)Math.sin(look),z-(float)(Math.cos(rad)*Math.cos(look)),0,1,0);
        gl.glEnable(GL10.GL_FOG);gl.glFogf(GL10.GL_FOG_MODE,GL10.GL_LINEAR);
        gl.glFogf(GL10.GL_FOG_START,700);gl.glFogf(GL10.GL_FOG_END,2200);
        gl.glFogfv(GL10.GL_FOG_COLOR,new float[]{.62f,.77f,.86f,1},0);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
        mesh.position(0);gl.glVertexPointer(3,GL10.GL_FLOAT,28,mesh);
        mesh.position(3);gl.glColorPointer(4,GL10.GL_FLOAT,28,mesh);
        gl.glDrawArrays(GL10.GL_TRIANGLES,0,baseVertices);
        // Decals use depth bias, and small window details are omitted at distance.
        gl.glEnable(GL10.GL_POLYGON_OFFSET_FILL);gl.glPolygonOffset(-1f,-2f);gl.glDepthMask(false);
        for(float[] batch:windowBatches){
            float xx=batch[2]-x,zz=batch[3]-z;
            if(xx*xx+zz*zz<180*180)gl.glDrawArrays(GL10.GL_TRIANGLES,(int)batch[0],(int)batch[1]);
        }
        gl.glDepthMask(true);gl.glDisable(GL10.GL_POLYGON_OFFSET_FILL);
        mesh.position(0);gl.glDisable(GL10.GL_FOG);
    }
}
