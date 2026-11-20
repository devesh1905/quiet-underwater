package app.quiet.launcher.feature.underwater.engine

/**
 * AGSL for Quiet Underwater (API 33+): one RenderEffect over the live world layer (`content`).
 * Water and glass are shaded in the same pass, so the glass refracts the water and the painting.
 *
 * Water (after Flood, lock/charge/ChargeShaders.kt FLOOD; see design/water-flood.md):
 *  refraction = -gradient x strength with per-channel dispersion (x1.00, 1.07, 1.15); caustics from the
 *  negative laplacian; absorption exp(-depth*(2.1,1.05,.7)) tinted by the painting's palette, with bright
 *  pixels (text) keeping most of their colour so they stay readable; the lens lift under the meniscus;
 *  a 1 dp meniscus line and glow; tight glints; a slow analytic swell; a shimmering reflection of what
 *  is above the surface. Rings (uRing) are analytic refraction waves that also cross the air.
 *
 * Glass (after the lock orb's GLASS, feature/player/OrbShaders.kt): an SDF union of rounded boxes
 * (uGA centre+half size, uGB corner/group/alpha/lens, uGC frost/rotation/squash/bevel, uGD cos/sin of
 * the rotation + bounding radius; uGBox the union of every body's reach), smooth-min'd
 * inside each merge group; a dome normal from the SDF gradient, chromatic refraction of the world
 * behind (water-tinted where that is under water), lens magnification, optional frost, fresnel, a
 * tight specular, a rim line, and a contact shadow plus a caustic line on the water under it.
 */
internal object WaterShader {
    val SRC = """
uniform shader content;
uniform shader uHF;
uniform shader uSurf;
uniform float2 uRes;
uniform float2 uGrid;
uniform float uGridOn;
uniform float uSurfN;
uniform float uSurfAmp;
uniform float uWater;
uniform float uTime;
uniform float uDp;
uniform float uRefr;
uniform float uCaus;
uniform float uCalm;
uniform float uAmb;
uniform float4 uDrop[6];
uniform float uDrops;
uniform float4 uRing[4];
uniform float uRings;
uniform float4 uGA[16];
uniform float4 uGB[16];
uniform float4 uGC[16];
uniform float4 uGD[16];
uniform float4 uGBox;
uniform float uGN;
uniform float uDbg;
uniform float uMergeK;
uniform float uTopY;
uniform float3 uDk;
uniform float3 uAc;
uniform float3 uMd;
uniform float3 uLt;
uniform float3 uSheen;
float hash21(float2 p){ p=fract(p*float2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }
float lum(float3 c){ return dot(c,float3(.299,.587,.114)); }
half4 src(float2 p){ return content.eval(clamp(p,float2(.5),uRes-.5)); }
float3 src3(float2 p,float2 o){ return float3(src(p+o).r, src(p+o*1.07).g, src(p+o*1.15).b); }
float surfY(float x){
  float u=clamp(x/uRes.x,0.,1.)*(uSurfN-1.)+.5;
  return uWater-(uSurf.eval(float2(u,.5)).r-.5)*uSurfAmp;
}
float3 swell(float2 q){
  float3 r=float3(0.);
  float2 d1=float2(.8,.6); float k1=.449; float s1=.045; float p1=dot(d1,q)*k1-uTime*1.3;
  r+=float3(d1*s1*cos(p1), -s1*k1*sin(p1));
  float2 d2=float2(-.62,.78); float k2=.698; float s2=.03; float p2=dot(d2,q)*k2-uTime*1.75+1.7;
  r+=float3(d2*s2*cos(p2), -s2*k2*sin(p2));
  float2 d3=float2(.12,-.99); float k3=.3; float s3=.035; float p3=dot(d3,q)*k3-uTime*.9+4.;
  r+=float3(d3*s3*cos(p3), -s3*k3*sin(p3));
  float2 d4=float2(-.95,-.3); float k4=1.05; float s4=.018; float p4=dot(d4,q)*k4-uTime*2.3+2.2;
  r+=float3(d4*s4*cos(p4), -s4*k4*sin(p4));
  return r;
}
float3 smin3(float3 a,float3 b,float k){ float3 h=clamp(.5+.5*(b-a)/k,0.,1.); return mix(b,a,h)-k*h*(1.-h); }
float sdRR(float2 q,float2 b,float r){ float2 v=abs(q)-b+r; return length(max(v,0.))+min(max(v.x,v.y),0.)-r; }
float smin1(float a,float b,float k){ float h=clamp(.5+.5*(b-a)/k,0.,1.); return mix(b,a,h)-k*h*(1.-h); }
// The union's distance at p, and the parameters of the nearest body (alpha, lens, frost, bevel,
// centre). Bodies whose bounding circle (uGD.z) is more than 64 dp away are skipped: that is exact
// wherever the result is used (< 30 dp), since the smooth-min of anything that far is the near
// body's distance. One box distance per body: most pixels near glass are deep inside a slab or
// well outside it, where the gradient is not needed.
float glassDist(float2 p, out float4 bA, out float4 bB, out float4 bC, out float4 bD){
  float d0=1e4; float d1=1e4; float d2=1e4; float d3=1e4; float best=1e4;
  bA=float4(0.); bB=float4(0.); bC=float4(0.); bD=float4(0.);
  float T=64.*uDp;
  for(int i=0;i<16;i++){
    if(float(i)>=uGN) break;
    float4 B=uGB[i];
    if(B.z>.002){
      float4 A=uGA[i]; float4 D=uGD[i];
      float2 v=p-A.xy;
      if(length(v)-D.z<T){
        float4 C=uGC[i];
        float2 q=float2(D.x*v.x+D.y*v.y, -D.y*v.x+D.x*v.y);
        float2 b=max(A.zw*float2(1.+C.z,1./(1.+C.z)),float2(.5));
        float mb=min(b.x,b.y); float r=min(B.x,mb);
        float d=sdRR(q,b,r)+(1.-B.z)*mb*1.05;
        if(d<best){ best=d; bA=A; bB=B; bC=C; bD=D; }
        float g=B.y;
        if(g<.5) d0=min(d0,d); else if(g<1.5) d1=smin1(d1,d,uMergeK); else if(g<2.5) d2=smin1(d2,d,uMergeK); else d3=smin1(d3,d,uMergeK);
      }
    }
  }
  return min(min(d0,d1),min(d2,d3));
}
// The union at p, p+(1,0) and p+(0,1) (x, y, z): the gradient, only for pixels in the rim band.
float3 glassField(float2 p){
  float3 d0=float3(1e4); float3 d1=float3(1e4); float3 d2=float3(1e4); float3 d3=float3(1e4);
  float T=64.*uDp;
  for(int i=0;i<16;i++){
    if(float(i)>=uGN) break;
    float4 B=uGB[i];
    if(B.z>.002){
      float4 A=uGA[i]; float4 D=uGD[i];
      float2 v=p-A.xy;
      if(length(v)-D.z<T){
        float4 C=uGC[i];
        float2 q=float2(D.x*v.x+D.y*v.y, -D.y*v.x+D.x*v.y);
        float2 b=max(A.zw*float2(1.+C.z,1./(1.+C.z)),float2(.5));
        float mb=min(b.x,b.y); float r=min(B.x,mb);
        float3 d=float3(sdRR(q,b,r), sdRR(q+float2(D.x,-D.y),b,r), sdRR(q+float2(D.y,D.x),b,r))+(1.-B.z)*mb*1.05;
        float g=B.y;
        if(g<.5) d0=min(d0,d); else if(g<1.5) d1=smin3(d1,d,uMergeK); else if(g<2.5) d2=smin3(d2,d,uMergeK); else d3=smin3(d3,d,uMergeK);
      }
    }
  }
  return min(min(d0,d1),min(d2,d3));
}
// Home's ambient caustics: a drifting network of soft light ridges (p in dp).
float ambCaus(float2 p,float t){
  float2 q=p*.021;
  float a=sin(q.x*1.7+t*.42+sin(q.y*1.3-t*.31)*1.6);
  float b=sin(q.y*1.9-t*.37+sin(q.x*1.1+t*.27)*1.7);
  float c=sin((q.x-q.y)*1.2+t*.33+a*.9);
  float v=(a+b+c)*.34;
  return exp(-v*v*22.);
}
// Depth absorption tinted by the painting; bright pixels (type) keep most of their colour.
float3 absorb(float3 w,float d,float amt){
  float dz=max(d,0.)/uRes.y;
  float3 ab=exp(-dz*float3(2.1,1.05,.7));
  float3 water=mix(uDk,uMd,.45);
  float3 t=w*mix(float3(1.),ab,.8)+water*(1.-ab.g)*.42;
  float keep=smoothstep(.45,.85,lum(w))*.6;
  return mix(w,mix(t,w,keep),amt);
}
half4 main(float2 xyIn){
  float2 xy=xyIn;
  // Above the highest point of the surface (uTopY, from the CPU, with a margin), outside every glass
  // body's reach, with no ring or drop in flight, the pass below returns exactly this pixel: return
  // it at once (the clock and the sky above the water skip all of it).
  if(uRings<=0. && uDrops<=0. && xy.y<uTopY && !(xy.x>uGBox.x && xy.y>uGBox.y && xy.x<uGBox.z && xy.y<uGBox.w)) return half4(src(xy).rgb,1.);
  float dp=uDp;
  // ---- analytic rings: refraction waves through air and water alike
  float2 ro=float2(0.); float rl=0.;
  if(uRings>0.){
    for(int i=0;i<4;i++){
      float4 r=uRing[i];
      if(r.w>0.){
        float2 v=xy-r.xy; float L=length(v)+.001;
        float rw=(14.+r.z*.03)*dp; float e=(L-r.z)/rw; float k=exp(-e*e*.5);
        ro+=v/L*(-e*k)*r.w*8.*dp;
        rl+=k*r.w*max(-e,0.);
      }
    }
  }
  // ---- the glass under this pixel?
  if(uDbg==1.) return src(xy);
  float4 gA=float4(0.); float4 gB=float4(0.); float4 gC=float4(0.); float4 gD=float4(0.);
  float gd=1e4;
  float2 gn=float2(0.,-1.);
  // Only inside the bounding box of every body's reach (uGBox, from the CPU) is the glass looked at.
  if(uDbg!=2. && xy.x>uGBox.x && xy.y>uGBox.y && xy.x<uGBox.z && xy.y<uGBox.w){
    gd=glassDist(xy,gA,gB,gC,gD);
    // The normal only matters on the dome (within the bevel) and in the 30 dp halo outside it;
    // deeper inside the dome is flat, whatever the gradient.
    if(gd<30.*dp && gd>-max(gC.w,1.)-2.){ float3 gf=glassField(xy); float2 gg=gf.yz-gf.x; gn=gg/(length(gg)+1e-5); }
  }
  // ---- the water
  // Clear glass (uGD.w): only the bending at its edge shows.
  bool clr=gD.w>.5;
  // Clear glass with no frost is a pure lens (Home's touch lens, drag drops, the music raft): it only
  // bends where the content under it is looked up (lo). The water itself (its ripples, tint,
  // caustics and light) is shaded at this very pixel, exactly as around it, so the lens is never
  // darker than the water it sits in (it would otherwise magnify the trough of the finger's own
  // splash) and has no colour fringe: only the magnified, bent content shows, a hair brighter.
  bool lensOnly=clr && gC.x<.01 && gd<1.5;
  float2 lo=float2(0.);
  float lensK=0.;
  if(lensOnly){
    float bev=max(gC.w,1.);
    float s=clamp(-gd/bev,0.,1.);
    float hgt=sqrt(1.-(1.-s)*(1.-s));
    float3 n=normalize(float3(gn*(1.-hgt),hgt+.04));
    float inside=(1.-smoothstep(-.75,.75,gd))*clamp(gB.z*1.4,0.,1.);
    float2 loff=-n.xy*(7.+gB.w*18.)*dp*(.35+.65*hgt)*1.45-(xy-gA.xy)*gB.w*.42*hgt;
    lo=loff*inside; lensK=inside;
  }
  float sy=surfY(xy.x);
  float d=xy.y-sy;
  if(uDbg==3.) d=-1e4;
  float3 H=normalize(float3(-.35,-.75,.55)+float3(0.,0.,1.));
  // Deep inside glass at full alpha the glass replaces the pixel outright (mix weight 1 below), so
  // the water under it is not worth shading: big slabs (the dock) skip 4-5 taps per pixel.
  bool covered=!lensOnly && gd< -.8 && gB.z*1.4>=1.;
  // Fully under the water this is replaced below, so it is only sampled near and above the line.
  float3 col=float3(0.);
  if(d<1. && !covered) col=src(xy+ro+lo).rgb;
  if(d>-3. && !covered){
    float near=exp(-max(d,0.)/(uRes.y*.32));
    float cell=uRes.x/uGrid.x;
    float3 g=float3(0.);
    if(uGridOn>0.){ float4 t=uHF.eval(xy/uRes*uGrid); g=float3((t.r-.5)*2.,(t.g-.5)*2.,(t.b-.5)*2.); }
    g+=swell(xy/cell)*(.4+.6*near)*uCalm;
    float2 off=-g.xy*uRefr+ro;
    if(d<80.*dp) off.y-=9.*dp*exp(-max(d,0.)/(9.*dp));
    // Floating glass bends the water around its rim (the meniscus climbing the glass).
    if(gd>0. && gd<30.*dp) off+=gn*exp(-gd/(7.*dp))*6.*dp*gB.z;
    float3 w=src3(xy+lo,off);
    // What is above the surface, mirrored and shimmering just under it (the clock reads through).
    float my=sy-max(d,0.)-3.*dp+off.y*1.5;
    if(my>1. && d<140.*dp){
      float3 rc=src(float2(xy.x+off.x*1.4+lo.x,my-lo.y)).rgb;
      // Type (near-white, unsaturated) is not mirrored: only the painting and the wall are.
      float mx=max(rc.r,max(rc.g,rc.b)); float mn=min(rc.r,min(rc.g,rc.b));
      float type=smoothstep(.62,.8,mn)*(1.-smoothstep(.1,.25,mx-mn));
      float rf=exp(-max(d,0.)/(30.*dp))*.2*(1.-type);
      w=mix(w,rc,rf);
    }
    w=absorb(w,d,1.);
    float c=max(-g.z*uCaus,0.); c=c*c/(1.+c*.6);
    w+=mix(uSheen,uLt,.4)*c*(.42*near+.04);
    if(uAmb>0. && d>2.*dp){ w+=mix(uSheen,uLt,.5)*ambCaus(xy/dp,uTime)*uAmb*.085*smoothstep(2.*dp,34.*dp,d)*exp(-d/(380.*dp)); }
    float3 n=normalize(float3(-g.xy*2.4,1.));
    w+=uSheen*pow(max(dot(n,H),0.),300.)*1.3*(.25+.75*near);
    if(d<130.*dp) w+=uLt*.07*exp(-max(d,0.)/(22.*dp));
    float a=smoothstep(-1.,1.,d);
    col=mix(col,w,a);
    if(d<9.*dp){
      float men=exp(-d*d/(2.*1.15*dp*1.15*dp));
      col+=uSheen*men*.75+uSheen*.09*exp(-abs(d)/(6.*dp))*(1.-a);
    }
  }
  col+=uSheen*rl*.07;
  // ---- contact shadow and caustic on the water under the glass
  if(gd>0. && gd<30.*dp && !clr){
    float below=clamp(gn.y*.7+.45,0.,1.);
    float under=smoothstep(-2.,2.,d);
    col*=1.-exp(-gd/(9.*dp))*(.14+.12*under)*below*gB.z;
    float ce=(gd-5.*dp)/(2.4*dp);
    col+=uSheen*exp(-ce*ce*.5)*.2*below*under*gB.z;
  }
  // ---- glass
  if(gd<1.5 && !lensOnly){
    float bev=max(gC.w,1.);
    float s=clamp(-gd/bev,0.,1.);
    float hgt=sqrt(1.-(1.-s)*(1.-s));
    float3 n=normalize(float3(gn*(1.-hgt),hgt+.04));
    float lens=gB.w;
    float edgeK=clr ? 1.45 : 1.;
    float2 off=-n.xy*(7.+lens*18.)*dp*(.35+.65*hgt)*edgeK-(xy-gA.xy)*lens*.42*hgt+ro*.3;
    float fr=gC.x;
    // Fully frosted, the blur replaces it: one tap instead of three dispersed ones.
    float disp=1.;
    float3 bc=fr*1.2<1. ? float3(src(xy+off).r, src(xy+off*(1.+.08*disp)).g, src(xy+off*(1.+.17*disp)).b) : src(xy+off).rgb;
    if(fr>0.){
      float3 acc=bc; float rr=16.*dp*fr; float a0=hash21(xy)*6.283;
      for(int k=0;k<6;k++){ float an=a0+float(k)*1.047; float rk=rr*(.55+.45*fract(float(k)*.618+hash21(xy+3.))); acc+=src(xy+off+float2(cos(an),sin(an))*rk).rgb; }
      acc/=7.;
      bc=mix(bc,acc,clamp(fr*1.2,0.,1.));
      bc=mix(bc,bc*.72+uLt*.1+float3(.035),fr*.55);
    }
    // What the glass looks into is water where that point is under the surface.
    float2 q=xy+off;
    float dq=q.y-surfY(q.x);
    if(dq>0.) bc=absorb(bc,dq,.65);
    float3 gc=bc;
    if(!clr){
      gc=bc*.97+uLt*.022;
      gc+=pow(1.-n.z,3.)*mix(float3(1.),uLt,.4)*.55;
      float3 Hg=normalize(float3(-.45,-.6,.65)+float3(0.,0.,1.));
      gc+=pow(max(dot(n,Hg),0.),90.)*1.3;
      gc+=pow(max(dot(n,normalize(float3(.5,.55,.7)+float3(0.,0.,1.))),0.),30.)*.16;
      float rim=1.-smoothstep(0.,1.1*dp,abs(gd+1.2*dp));
      gc+=rim*(.16+.18*clamp(-gn.y,0.,1.))*mix(uLt,float3(1.),.5);
    }
    float inside=1.-smoothstep(-.75,.75,gd);
    col=mix(col,gc,inside*clamp(gB.z*1.4,0.,1.));
  }
  // ---- falling glass drops: tiny lenses, stretched along the fall
  if(uDrops>0.){
    for(int i=0;i<6;i++){
      float4 dr=uDrop[i];
      if(dr.z>0.){
        float2 v=(xy-dr.xy)/dr.z;
        v.y/=1.+dr.w; v.x*=1.+dr.w*.35;
        float rr=dot(v,v);
        if(rr<1.3){
          float z=sqrt(max(0.,1.-rr));
          float3 bn=normalize(float3(v*(1.-z),z+.04));
          float3 rc=src3(xy,-v*dr.z*.95);
          rc+=pow(1.-z,3.)*uSheen*.55;
          rc+=pow(max(dot(bn,H),0.),70.)*1.2;
          col=mix(col,rc,1.-smoothstep(.85,1.05,rr));
        }
      }
    }
  }
  col*=1.+.035*lensK;
  return half4(half3(col),1.);
}
"""
}
