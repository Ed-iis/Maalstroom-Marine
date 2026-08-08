const marineData={connected:false,statusText:'Wachten op NMEA…',latitudeDisplay:'—',longitudeDisplay:'—',positionAvailable:false,depthRaw:null,depthOffset:0,depthAvailable:false,trueWindDirection:null,trueWindSpeed:null,windAvailable:false,lastUpdateMillis:null};
const HOUR=3600000,history=[];let speedMax=32;
function signed(v,d=1){return `${v>=0?'+':''}${v.toFixed(d)}`}
function updateDashboard(d){
  latitude.textContent=d.positionAvailable?d.latitudeDisplay:'—';longitude.textContent=d.positionAvailable?d.longitudeDisplay:'—';
  if(d.depthAvailable&&Number.isFinite(d.depthRaw)){const c=d.depthRaw+(Number(d.depthOffset)||0);depthValue.textContent=c.toFixed(1);depthRaw.textContent=`${d.depthRaw.toFixed(1)} m`;depthOffset.textContent=`${signed(Number(d.depthOffset)||0)} m`}else{depthValue.textContent=depthRaw.textContent=depthOffset.textContent='—'}
  if(d.windAvailable&&Number.isFinite(d.trueWindDirection)&&Number.isFinite(d.trueWindSpeed)){trueWindDirection.textContent=`${Math.round(d.trueWindDirection).toString().padStart(3,'0')}°`;trueWindSpeed.textContent=d.trueWindSpeed.toFixed(1)}else{trueWindDirection.textContent=trueWindSpeed.textContent='—'}
  status.classList.toggle('status--connected',!!d.connected);status.classList.toggle('status--error',d.connected===false&&String(d.statusText||'').toLowerCase().includes('fout'));statusText.textContent=d.statusText||(d.connected?'Live NMEA':'Wachten op NMEA…');
  if(d.lastUpdateMillis)lastUpdate.textContent=`Laatst bijgewerkt: ${new Date(d.lastUpdateMillis).toLocaleTimeString('nl-NL')}`
}
window.updateMarineData=n=>{Object.assign(marineData,n);updateDashboard(marineData)};
window.addWindHistoryPoint=p=>{if(!Number.isFinite(p.timestamp)||!Number.isFinite(p.direction)||!Number.isFinite(p.speed))return;history.push(p);const cut=Date.now()-HOUR;while(history.length&&history[0].timestamp<cut)history.shift();speedMax=history.some(x=>x.speed>32)?62:32;draw()};
function css(n){return getComputedStyle(document.documentElement).getPropertyValue(n).trim()}
function draw(){
  const c=windChart,w=c.parentElement.clientWidth||1,h=c.parentElement.clientHeight||1,r=devicePixelRatio||1;c.width=Math.round(w*r);c.height=Math.round(h*r);const x=c.getContext('2d');x.setTransform(r,0,0,r,0,0);x.clearRect(0,0,w,h);
  const m={l:48,r:48,t:12,b:34},L=m.l,R=w-m.r,T=m.t,B=h-m.b,W=R-L,H=B-T;x.font='11px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';x.textBaseline='middle';
  for(let i=0;i<=16;i++){const f=i/16,y=B-f*H,strong=i%4===0;x.strokeStyle=css(strong?'--grid2':'--grid');x.lineWidth=1;x.beginPath();x.moveTo(L,y);x.lineTo(R,y);x.stroke();if(strong){x.fillStyle=css('--muted');x.textAlign='right';x.fillText(`${Math.round(i*22.5)}°`,L-8,y);x.textAlign='left';const sv=speedMax*f;x.fillText(`${speedMax===32?Math.round(sv):(Math.round(sv*10)/10).toLocaleString('nl-NL')} kn`,R+8,y)}}
  for(let min=0;min<=60;min+=5){const f=min/60,xx=R-f*W;x.strokeStyle=css(min%15===0?'--grid2':'--grid');x.beginPath();x.moveTo(xx,T);x.lineTo(xx,B);x.stroke();if(min%15===0){x.fillStyle=css('--muted');x.textAlign=min===0?'right':min===60?'left':'center';x.fillText(min===0?'nu':`-${min} min`,xx,B+20)}}
  series(x,'direction',css('--direction'),360,true,L,R,T,B,W,H);series(x,'speed',css('--speed'),speedMax,false,L,R,T,B,W,H)
}
function series(x,key,color,max,wrap,L,R,T,B,W,H){if(!history.length)return;const now=Date.now(),start=now-HOUR;x.strokeStyle=color;x.lineWidth=2;x.lineJoin='round';x.lineCap='round';x.beginPath();let begun=false,prev=null;for(const p of history){if(p.timestamp<start||p.timestamp>now)continue;const v=p[key],xx=L+(p.timestamp-start)/HOUR*W,yy=B-Math.max(0,Math.min(max,v))/max*H,br=wrap&&prev!==null&&Math.abs(v-prev)>180;if(!begun||br){x.moveTo(xx,yy);begun=true}else x.lineTo(xx,yy);prev=v}x.stroke()}
addEventListener('resize',draw);updateDashboard(marineData);draw();
