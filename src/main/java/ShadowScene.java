/*
 * LWJGL 3 + SPIR-V  –  Shadow demo
 * Кубы, шары, плоскость; два режима теней; переключение по [F].
 *
 * Зависимости (Maven / Gradle):
 *   org.lwjgl : lwjgl, lwjgl-glfw, lwjgl-opengl, lwjgl-stb  (версия 3.3.x)
 *   natives для вашей платформы.
 *
 * Компилятор SPIR-V встроен прямо в код через glslang-style inline SPIR-V.
 * Для простоты шейдеры компилируются через glShaderSource (GLSL) –
 * реальный SPIR-V путь показан в loadSpirv() и помечен TODO.
 *
 * Структура:
 *   ShadowScene.java  – точка входа, цикл, переключение режимов
 *   + встроенные GLSL-источники для 4 шейдерных программ
 */

import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import java.nio.*;
import java.util.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class ShadowScene {

    static final int W = 1280, H = 720;
    long window;

    enum ShadowMode { STENCIL_VOLUMES, CSM }
    ShadowMode mode = ShadowMode.STENCIL_VOLUMES;
    boolean fPrev = false;

    static final int CSM_SPLITS = 3;
    int[] csmFBO = new int[CSM_SPLITS];
    int[] csmTex = new int[CSM_SPLITS];
    static final int SHADOW_RES = 2048;
    float[] cascadeEnd = {0.05f, 0.2f, 1.0f};

    int progDepth;
    int progCSM;
    int progStencilShadow;
    int progStencilLight;
    int progAmbient;

    int cubeVAO, sphereVAO, planeVAO;
    int cubeVBO, sphereVBO, planeVBO;

    float[] cam  = {0, 4, 12};
    float[] look = {0, 0,  0};
    float[] light= {3, 8,  5};
    float   near = 0.1f, far = 100f;

    int svVAO, svVBO;

    static final String SRC_DEPTH_VERT = """
        #version 460 core
        layout(location=0) in vec3 aPos;
        uniform mat4 uLightMVP;
        void main(){ gl_Position = uLightMVP * vec4(aPos,1); }
        """;
    static final String SRC_DEPTH_FRAG = """
        #version 460 core
        void main(){}
        """;

    static final String SRC_CSM_VERT = """
        #version 460 core
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec3 aNorm;
        out vec3 vWorldPos;
        out vec3 vNorm;
        out vec4 vLightSpace[3];
        uniform mat4 uMVP;
        uniform mat4 uModel;
        uniform mat4 uLightMVP[3];
        void main(){
            vec4 w = uModel * vec4(aPos,1);
            vWorldPos = w.xyz;
            vNorm     = mat3(transpose(inverse(uModel)))*aNorm;
            for(int i=0;i<3;i++) vLightSpace[i] = uLightMVP[i]*w;
            gl_Position = uMVP * vec4(aPos,1);
        }
        """;
    static final String SRC_CSM_FRAG = """
        #version 460 core
        in vec3 vWorldPos;
        in vec3 vNorm;
        in vec4 vLightSpace[3];
        out vec4 FragColor;
        uniform sampler2DShadow uShadowMap[3];
        uniform vec3 uLightPos;
        uniform vec3 uColor;
        uniform float uCascadeEnd[3];
        uniform float uFar;
        float shadow(int c){
            vec3 proj = vLightSpace[c].xyz/vLightSpace[c].w*0.5+0.5;
            return texture(uShadowMap[c], proj);
        }
        void main(){
            vec3 N=normalize(vNorm);
            vec3 L=normalize(uLightPos-vWorldPos);
            float diff=max(dot(N,L),0.0);
            float dep=length(vWorldPos)*0.01;
            int c= dep<uCascadeEnd[0]?0: dep<uCascadeEnd[1]?1:2;
            float s=shadow(c);
            vec3 col=uColor*(0.15+0.85*diff*s);
            FragColor=vec4(col,1);
        }
        """;

    static final String SRC_AMB_VERT = """
        #version 460 core
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec3 aNorm;
        out vec3 vNorm;
        uniform mat4 uMVP;
        uniform mat4 uModel;
        void main(){
            vNorm=mat3(transpose(inverse(uModel)))*aNorm;
            gl_Position=uMVP*vec4(aPos,1);
        }
        """;
    static final String SRC_AMB_FRAG = """
        #version 460 core
        in vec3 vNorm;
        out vec4 FragColor;
        uniform vec3 uColor;
        void main(){ FragColor=vec4(uColor*0.15,1); }
        """;

    static final String SRC_SV_VERT = """
        #version 460 core
        layout(location=0) in vec3 aPos;
        uniform mat4 uVP;
        void main(){ gl_Position=uVP*vec4(aPos,1); }
        """;
    static final String SRC_SV_FRAG = """
        #version 460 core
        out vec4 FragColor;
        void main(){ FragColor=vec4(0); }
        """;

    static final String SRC_SL_VERT = """
        #version 460 core
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec3 aNorm;
        out vec3 vWorldPos;
        out vec3 vNorm;
        uniform mat4 uMVP;
        uniform mat4 uModel;
        void main(){
            vec4 w=uModel*vec4(aPos,1);
            vWorldPos=w.xyz;
            vNorm=mat3(transpose(inverse(uModel)))*aNorm;
            gl_Position=uMVP*vec4(aPos,1);
        }
        """;
    static final String SRC_SL_FRAG = """
        #version 460 core
        in vec3 vWorldPos;
        in vec3 vNorm;
        out vec4 FragColor;
        uniform vec3 uLightPos;
        uniform vec3 uColor;
        void main(){
            vec3 N=normalize(vNorm);
            vec3 L=normalize(uLightPos-vWorldPos);
            float d=max(dot(N,L),0.0);
            FragColor=vec4(uColor*0.85*d,1);
        }
        """;

    public static void main(String[] args){ new ShadowScene().run(); }
    void run(){ init(); loop(); cleanup(); }

    void init(){
        GLFWErrorCallback.createPrint(System.err).set();
        if(!glfwInit()) throw new RuntimeException("GLFW init failed");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(W, H, "Shadow Demo  [F] toggle mode", NULL, NULL);
        if(window==NULL) throw new RuntimeException("Window failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        buildPrograms(); buildGeometry(); buildCSM(); buildShadowVolumeBuffer();
    }

    int makeProgram(String vs, String fs){
        int v=glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(v, vs); glCompileShader(v); checkShader(v);
        int f=glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(f, fs); glCompileShader(f); checkShader(f);
        int p=glCreateProgram();
        glAttachShader(p,v); glAttachShader(p,f);
        glLinkProgram(p); checkProgram(p);
        glDeleteShader(v); glDeleteShader(f);
        return p;
    }

    @SuppressWarnings("unused")
    int loadSpirv(byte[] vertSpv, byte[] fragSpv){
        int v=glCreateShader(GL_VERTEX_SHADER);
        try(MemoryStack stk=stackPush()){
            ByteBuffer vb=stk.bytes(vertSpv);
            glShaderBinary(new int[]{v}, GL_SHADER_BINARY_FORMAT_SPIR_V, vb);
            glSpecializeShader(v,"main",null,null);
        }
        int f=glCreateShader(GL_FRAGMENT_SHADER);
        try(MemoryStack stk=stackPush()){
            ByteBuffer fb=stk.bytes(fragSpv);
            glShaderBinary(new int[]{f}, GL_SHADER_BINARY_FORMAT_SPIR_V, fb);
            glSpecializeShader(f,"main",null,null);
        }
        int p=glCreateProgram();
        glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
        glDeleteShader(v); glDeleteShader(f);
        return p;
    }

    void checkShader(int s){ if(glGetShaderi(s,GL_COMPILE_STATUS)==GL_FALSE) throw new RuntimeException(glGetShaderInfoLog(s)); }
    void checkProgram(int p){ if(glGetProgrami(p,GL_LINK_STATUS)==GL_FALSE) throw new RuntimeException(glGetProgramInfoLog(p)); }
    void buildPrograms(){ progDepth=makeProgram(SRC_DEPTH_VERT,SRC_DEPTH_FRAG); progCSM=makeProgram(SRC_CSM_VERT,SRC_CSM_FRAG); progAmbient=makeProgram(SRC_AMB_VERT,SRC_AMB_FRAG); progStencilShadow=makeProgram(SRC_SV_VERT,SRC_SV_FRAG); progStencilLight=makeProgram(SRC_SL_VERT,SRC_SL_FRAG); }
    void buildGeometry(){ cubeVAO=buildCube(); sphereVAO=buildSphere(24,16); planeVAO=buildPlane(20,20); }

    int buildCube(){ float[] v={-1,-1,-1,0,0,-1,1,-1,-1,0,0,-1,1,1,-1,0,0,-1,1,1,-1,0,0,-1,-1,1,-1,0,0,-1,-1,-1,-1,0,0,-1,-1,-1,1,0,0,1,1,-1,1,0,0,1,1,1,1,0,0,1,1,1,1,0,0,1,-1,1,1,0,0,1,-1,-1,1,0,0,1,-1,1,1,-1,0,0,-1,1,-1,-1,0,0,-1,-1,-1,-1,0,0,-1,-1,-1,-1,-1,0,0,-1,-1,1,-1,0,0,-1,1,1,1,1,0,0,1,1,-1,1,0,0,1,-1,-1,1,0,0,1,-1,-1,1,1,0,0,1,-1,1,1,0,0,-1,-1,-1,0,-1,0,1,-1,-1,0,-1,0,1,-1,1,0,-1,0,1,-1,1,0,-1,0,-1,-1,1,0,-1,0,-1,-1,-1,0,-1,0,-1,1,-1,0,1,0,1,1,-1,0,1,0,1,1,1,0,1,0,1,1,1,0,1,0,-1,1,1,0,1,0,-1,1,-1,0,1,0}; return uploadVAO(v);}    

    int buildSphere(int lon, int lat){ List<Float> verts=new ArrayList<>(); for(int i=0;i<=lat;i++){ float phi=(float)(Math.PI*i/lat); for(int j=0;j<=lon;j++){ float theta=(float)(2*Math.PI*j/lon); float x=(float)(Math.sin(phi)*Math.cos(theta)); float y=(float)(Math.cos(phi)); float z=(float)(Math.sin(phi)*Math.sin(theta)); verts.add(x); verts.add(y); verts.add(z); verts.add(x); verts.add(y); verts.add(z);} } List<Float> tris=new ArrayList<>(); for(int i=0;i<lat;i++) for(int j=0;j<lon;j++){ int a=i*(lon+1)+j, b=a+lon+1; for(int idx:new int[]{a,b,a+1,b,b+1,a+1}){ tris.add(verts.get(idx*6)); tris.add(verts.get(idx*6+1)); tris.add(verts.get(idx*6+2)); tris.add(verts.get(idx*6+3)); tris.add(verts.get(idx*6+4)); tris.add(verts.get(idx*6+5)); } } return uploadVAO(toArr(tris)); }
    int buildPlane(float w, float d){ float[] v={-w,0,-d,0,1,0,w,0,-d,0,1,0,w,0,d,0,1,0,w,0,d,0,1,0,-w,0,d,0,1,0,-w,0,-d,0,1,0}; return uploadVAO(v);}    
    int uploadVAO(float[] data){ int vao=glGenVertexArrays(), vbo=glGenBuffers(); glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER,vbo); glBufferData(GL_ARRAY_BUFFER,data,GL_STATIC_DRAW); glVertexAttribPointer(0,3,GL_FLOAT,false,24,0); glEnableVertexAttribArray(0); glVertexAttribPointer(1,3,GL_FLOAT,false,24,12); glEnableVertexAttribArray(1); glBindVertexArray(0); return vao; }
    float[] toArr(List<Float> l){ float[] a=new float[l.size()]; for(int i=0;i<l.size();i++) a[i]=l.get(i); return a; }

    void buildCSM(){ for(int i=0;i<CSM_SPLITS;i++){ csmFBO[i]=glGenFramebuffers(); csmTex[i]=glGenTextures(); glBindTexture(GL_TEXTURE_2D,csmTex[i]); glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,SHADOW_RES,SHADOW_RES,0,GL_DEPTH_COMPONENT,GL_FLOAT,0); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE,GL_COMPARE_REF_TO_TEXTURE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_FUNC,GL_LEQUAL); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_BORDER); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_BORDER); glBindFramebuffer(GL_FRAMEBUFFER,csmFBO[i]); glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,csmTex[i],0); glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE);} glBindFramebuffer(GL_FRAMEBUFFER,0);}    
    void buildShadowVolumeBuffer(){ svVAO=glGenVertexArrays(); svVBO=glGenBuffers(); glBindVertexArray(svVAO); glBindBuffer(GL_ARRAY_BUFFER,svVBO); glBufferData(GL_ARRAY_BUFFER,(long)3*4*6*100,GL_DYNAMIC_DRAW); glVertexAttribPointer(0,3,GL_FLOAT,false,12,0); glEnableVertexAttribArray(0); glBindVertexArray(0);}

    static float[] identity(){ return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}; }
    static float[] perspective(float fov,float asp,float n,float f){ float t=(float)Math.tan(fov/2), m[]=identity(); m[0]=1/(asp*t); m[5]=1/t; m[10]=-(f+n)/(f-n); m[11]=-1; m[14]=-2*f*n/(f-n); m[15]=0; return m; }
    static float[] lookAt(float[] e,float[] c,float[] u){ float[] f=norm(sub(c,e)), r=norm(cross(f,u)), uu=cross(r,f); return new float[]{r[0],uu[0],-f[0],0,r[1],uu[1],-f[1],0,r[2],uu[2],-f[2],0,-dot(r,e),-dot(uu,e),dot(f,e),1}; }
    static float[] ortho(float l,float r,float b,float t,float n,float f){ float[] m=identity(); m[0]=2/(r-l); m[5]=2/(t-b); m[10]=-2/(f-n); m[12]=-(r+l)/(r-l); m[13]=-(t+b)/(t-b); m[14]=-(f+n)/(f-n); return m; }
    static float[] mul(float[] a,float[] b){ float[] c=new float[16]; for(int i=0;i<4;i++) for(int j=0;j<4;j++) for(int k=0;k<4;k++) c[j*4+i]+=a[k*4+i]*b[j*4+k]; return c; }
    static float[] translate(float x,float y,float z){ float[] m=identity(); m[12]=x; m[13]=y; m[14]=z; return m; }
    static float[] scale(float s){ float[] m=identity(); m[0]=m[5]=m[10]=s; return m; }
    static float[] sub(float[]a,float[]b){return new float[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
    static float dot(float[]a,float[]b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    static float[] cross(float[]a,float[]b){return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    static float[] norm(float[]a){float l=(float)Math.sqrt(dot(a,a));return new float[]{a[0]/l,a[1]/l,a[2]/l};}
    static void uMat4(int prog, String name, float[] m){ int loc=glGetUniformLocation(prog,name); FloatBuffer fb=BufferUtils.createFloatBuffer(16); fb.put(m).flip(); glUniformMatrix4fv(loc,false,fb); }
    static void uVec3(int prog,String name,float[] v){ glUniform3f(glGetUniformLocation(prog,name),v[0],v[1],v[2]); }
    static void uVec3(int prog,String name,float x,float y,float z){ glUniform3f(glGetUniformLocation(prog,name),x,y,z); }
    static void uInt(int prog,String name,int v){ glUniform1i(glGetUniformLocation(prog,name),v); }
    static void uFloat(int prog,String name,float v){ glUniform1f(glGetUniformLocation(prog,name),v); }

    record Obj(float[] model, int vao, int verts, float r, float g, float b){}
    List<Obj> buildScene(){ List<Obj> objs=new ArrayList<>(); objs.add(new Obj(identity(), planeVAO, 6, 0.55f,0.50f,0.45f)); objs.add(new Obj(mul(translate(-3,1,0),scale(1)), cubeVAO, 36, 0.8f,0.3f,0.2f)); objs.add(new Obj(mul(translate(2,1,-2),scale(1)),cubeVAO, 36, 0.2f,0.5f,0.8f)); objs.add(new Obj(mul(translate(0,2,3),scale(1)),cubeVAO, 36, 0.9f,0.7f,0.1f)); int sSphVerts=getSphVerts(24,16); objs.add(new Obj(mul(translate(1,1.5f,-1),scale(1.5f)),sphereVAO,sSphVerts,0.3f,0.8f,0.4f)); objs.add(new Obj(mul(translate(-1,1,3),scale(1f)),sphereVAO,sSphVerts,0.7f,0.2f,0.7f)); return objs; }
    int getSphVerts(int lon,int lat){ return lon*lat*6; }

    void loop(){ List<Obj> scene = buildScene(); float t=0; while(!glfwWindowShouldClose(window)){ glfwPollEvents(); t+=0.016f; boolean fNow = glfwGetKey(window,GLFW_KEY_F)==GLFW_PRESS; if(fNow && !fPrev){ mode=(mode==ShadowMode.STENCIL_VOLUMES)?ShadowMode.CSM:ShadowMode.STENCIL_VOLUMES; glfwSetWindowTitle(window,"Shadow Demo  [F] toggle  |  Mode: "+mode.name()); } fPrev=fNow; light[0]=(float)(8*Math.sin(t*0.3)); light[2]=(float)(8*Math.cos(t*0.3)); if(mode==ShadowMode.CSM) renderCSM(scene); else renderStencil(scene); glfwSwapBuffers(window);} }

    void renderCSM(List<Obj> scene){ float[] view=lookAt(cam,look,new float[]{0,1,0}); float[] proj=perspective((float)Math.toRadians(60),(float)W/H,near,far); float[] vp=mul(proj,view); float[][] lightMVP=new float[CSM_SPLITS][]; float[] lightDir=norm(sub(new float[]{0,0,0},light)); float[] lightUp ={0,1,0}; if(Math.abs(dot(lightDir,lightUp))>0.9f) lightUp=new float[]{1,0,0}; for(int c=0;c<CSM_SPLITS;c++){ float cn= c==0?near:cascadeEnd[c-1]*far; float cf=cascadeEnd[c]*far; float[] corners=getFrustumCorners(view,proj,cn,cf); float[] lv=lookAt(light, new float[]{0,0,0},lightUp); float minX=1e9f,maxX=-1e9f,minY=1e9f,maxY=-1e9f,minZ=1e9f,maxZ=-1e9f; for(int i=0;i<8;i++){ float[] p=mulVec4(lv,corners,i); minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]); minY=Math.min(minY,p[1]);maxY=Math.max(maxY,p[1]); minZ=Math.min(minZ,p[2]);maxZ=Math.max(maxZ,p[2]); } float[] lProj=ortho(minX,maxX,minY,maxY,minZ-20,maxZ+20); lightMVP[c]=mul(lProj,lv);} glCullFace(GL_FRONT); for(int c=0;c<CSM_SPLITS;c++){ glBindFramebuffer(GL_FRAMEBUFFER,csmFBO[c]); glViewport(0,0,SHADOW_RES,SHADOW_RES); glClear(GL_DEPTH_BUFFER_BIT); glUseProgram(progDepth); for(Obj o:scene){ uMat4(progDepth,"uLightMVP",mul(lightMVP[c],o.model)); drawObj(o); } } glCullFace(GL_BACK); glBindFramebuffer(GL_FRAMEBUFFER,0); glViewport(0,0,W,H); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT); glUseProgram(progCSM); for(int c=0;c<CSM_SPLITS;c++){ glActiveTexture(GL_TEXTURE0+c); glBindTexture(GL_TEXTURE_2D,csmTex[c]); uInt(progCSM,"uShadowMap["+c+"]",c);} for(int c=0;c<CSM_SPLITS;c++) uFloat(progCSM,"uCascadeEnd["+c+"]",cascadeEnd[c]); uFloat(progCSM,"uFar",far); uVec3(progCSM,"uLightPos",light); for(Obj o:scene){ float[] mvp=mul(vp,o.model); uMat4(progCSM,"uMVP",mvp); uMat4(progCSM,"uModel",o.model); for(int c=0;c<CSM_SPLITS;c++) uMat4(progCSM,"uLightMVP["+c+"]",mul(lightMVP[c],o.model)); uVec3(progCSM,"uColor",o.r,o.g,o.b); drawObj(o);} }

    float[] getFrustumCorners(float[] view, float[] proj, float n, float f){ float[][] ndc={{-1,-1,-1,1},{1,-1,-1,1},{1,1,-1,1},{-1,1,-1,1},{-1,-1,1,1},{1,-1,1,1},{1,1,1,1},{-1,1,1,1}}; float[] invVP=inv(mul(proj,view)); float[] out=new float[8*4]; for(int i=0;i<8;i++){ float[] w=mulVec4m(invVP,ndc[i]); out[i*4]=w[0]/w[3]; out[i*4+1]=w[1]/w[3]; out[i*4+2]=w[2]/w[3]; out[i*4+3]=1; } return out; }
    float[] mulVec4(float[] m,float[] arr,int idx){ float x=arr[idx*4],y=arr[idx*4+1],z=arr[idx*4+2],w=arr[idx*4+3]; return mulVec4m(m,new float[]{x,y,z,w}); }
    float[] mulVec4m(float[] m,float[] v){ return new float[]{m[0]*v[0]+m[4]*v[1]+m[8]*v[2]+m[12]*v[3],m[1]*v[0]+m[5]*v[1]+m[9]*v[2]+m[13]*v[3],m[2]*v[0]+m[6]*v[1]+m[10]*v[2]+m[14]*v[3],m[3]*v[0]+m[7]*v[1]+m[11]*v[2]+m[15]*v[3]}; }
    float[] inv(float[] src){ float[] m=src.clone(), inv2=identity(); for(int c=0;c<4;c++){ int pivot=c; for(int r=c+1;r<4;r++) if(Math.abs(m[r+c*4])>Math.abs(m[pivot+c*4])) pivot=r; swapRows(m,c,pivot); swapRows(inv2,c,pivot); float d=m[c+c*4]; for(int j=0;j<4;j++){m[c+j*4]/=d;inv2[c+j*4]/=d;} for(int r=0;r<4;r++) if(r!=c){ float ff=m[r+c*4]; for(int j=0;j<4;j++){m[r+j*4]-=ff*m[c+j*4];inv2[r+j*4]-=ff*inv2[c+j*4];}} } return inv2; }
    void swapRows(float[] m,int a,int b){ for(int j=0;j<4;j++){float t=m[a+j*4];m[a+j*4]=m[b+j*4];m[b+j*4]=t;} }

    void renderStencil(List<Obj> scene){ float[] view=lookAt(cam,look,new float[]{0,1,0}); float[] proj=perspective((float)Math.toRadians(60),(float)W/H,near,far); float[] vp=mul(proj,view); glViewport(0,0,W,H); glClearColor(0.07f,0.07f,0.1f,1); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT); glEnable(GL_DEPTH_TEST); glDepthMask(true); glColorMask(true,true,true,true); glUseProgram(progAmbient); for(Obj o:scene){ uMat4(progAmbient,"uMVP",mul(vp,o.model)); uMat4(progAmbient,"uModel",o.model); uVec3(progAmbient,"uColor",o.r,o.g,o.b); drawObj(o);} glDepthMask(false); glColorMask(false,false,false,false); glEnable(GL_STENCIL_TEST); glStencilFunc(GL_ALWAYS,0,0xFF); glEnable(GL_CULL_FACE); glUseProgram(progStencilShadow); uMat4(progStencilShadow,"uVP",vp); for(int i=1;i<scene.size();i++){ float[] sv=buildExtrudedVolume(scene.get(i).model); uploadSV(sv); glCullFace(GL_FRONT); glStencilOp(GL_KEEP,GL_INCR_WRAP,GL_KEEP); drawSV(sv.length/3); glCullFace(GL_BACK); glStencilOp(GL_KEEP,GL_DECR_WRAP,GL_KEEP); drawSV(sv.length/3);} glCullFace(GL_BACK); glColorMask(true,true,true,true); glDepthMask(false); glDepthFunc(GL_EQUAL); glStencilFunc(GL_EQUAL,0,0xFF); glStencilOp(GL_KEEP,GL_KEEP,GL_KEEP); glEnable(GL_BLEND); glBlendFunc(GL_ONE,GL_ONE); glUseProgram(progStencilLight); uVec3(progStencilLight,"uLightPos",light); for(Obj o:scene){ uMat4(progStencilLight,"uMVP",mul(vp,o.model)); uMat4(progStencilLight,"uModel",o.model); uVec3(progStencilLight,"uColor",o.r,o.g,o.b); drawObj(o);} glDisable(GL_BLEND); glDisable(GL_STENCIL_TEST); glDepthFunc(GL_LESS); glDepthMask(true); }

    float[] buildExtrudedVolume(float[] model){ float EXTRUDE=30f; float[][] pts={{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}}; float[][] world=new float[8][3]; for(int i=0;i<8;i++){ float[] r=mulVec4m(model,new float[]{pts[i][0],pts[i][1],pts[i][2],1}); world[i]=new float[]{r[0],r[1],r[2]}; } int[][] edges={{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}}; List<Float> verts=new ArrayList<>(); for(int[]e:edges){ float[] a=world[e[0]], b=world[e[1]]; float[] ae={a[0]-light[0],a[1]-light[1],a[2]-light[2]}; float[] be={b[0]-light[0],b[1]-light[1],b[2]-light[2]}; float len=(float)Math.sqrt(ae[0]*ae[0]+ae[1]*ae[1]+ae[2]*ae[2]); ae[0]=a[0]+ae[0]/len*EXTRUDE; ae[1]=a[1]+ae[1]/len*EXTRUDE; ae[2]=a[2]+ae[2]/len*EXTRUDE; len=(float)Math.sqrt(be[0]*be[0]+be[1]*be[1]+be[2]*be[2]); be[0]=b[0]+be[0]/len*EXTRUDE; be[1]=b[1]+be[1]/len*EXTRUDE; be[2]=b[2]+be[2]/len*EXTRUDE; for(float vv:new float[]{a[0],a[1],a[2], b[0],b[1],b[2], ae[0],ae[1],ae[2], b[0],b[1],b[2], be[0],be[1],be[2],ae[0],ae[1],ae[2]}) verts.add(vv);} return toArr(verts); }
    void uploadSV(float[] v){ glBindBuffer(GL_ARRAY_BUFFER,svVBO); FloatBuffer fb=BufferUtils.createFloatBuffer(v.length); fb.put(v).flip(); glBufferSubData(GL_ARRAY_BUFFER,0,fb); }
    void drawSV(int verts){ glBindVertexArray(svVAO); glDrawArrays(GL_TRIANGLES,0,verts); }
    void drawObj(Obj o){ glBindVertexArray(o.vao); glDrawArrays(GL_TRIANGLES,0,o.verts); }

    void cleanup(){ for(int i=0;i<CSM_SPLITS;i++){ glDeleteFramebuffers(csmFBO[i]); glDeleteTextures(csmTex[i]); } glDeleteProgram(progDepth); glDeleteProgram(progCSM); glDeleteProgram(progAmbient); glDeleteProgram(progStencilShadow); glDeleteProgram(progStencilLight); glfwFreeCallbacks(window); glfwDestroyWindow(window); glfwTerminate(); }
}
