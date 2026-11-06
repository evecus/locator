//! /admin 与 /user 网页：HTML/JS 以字符串常量内嵌在源码里，无外部模板/静态文件。
//! 前端依赖仅 Leaflet（CDN）；地图默认高德瓦片（GCJ-02，JS 侧做 WGS-84 偏移转换）。

/// WGS-84 -> GCJ-02 转换的 JS 实现（与客户端 GeoConvert.kt 同算法）
const JS_GEO: &str = r##"
function outOfChina(lat, lng){return lng<72.004||lng>137.8347||lat<0.8293||lat>55.8271;}
function wgs2gcj(lat,lng){
  if(outOfChina(lat,lng))return [lat,lng];
  var a=6378245.0,ee=0.00669342162296594323;
  function tL(x,y){var r=-100+2*x+3*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x));
    r+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
    r+=(20*Math.sin(y*Math.PI)+40*Math.sin(y/3*Math.PI))*2/3;
    r+=(160*Math.sin(y/12*Math.PI)+320*Math.sin(y*Math.PI/30))*2/3;return r;}
  function tG(x,y){var r=300+x+2*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x));
    r+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
    r+=(20*Math.sin(x*Math.PI)+40*Math.sin(x/3*Math.PI))*2/3;
    r+=(150*Math.sin(x/12*Math.PI)+300*Math.sin(x/30*Math.PI))*2/3;return r;}
  var dL=tL(lng-105,lat-35),dG=tG(lng-105,lat-35);
  var rL=lat/180*Math.PI,m=1-ee*Math.sin(rL)*Math.sin(rL),sm=Math.sqrt(m);
  dL=(dL*180)/((a*(1-ee))/(m*sm)*Math.PI);
  dG=(dG*180)/(a/sm*Math.cos(rL)*Math.PI);
  return [lat+dL,lng+dG];
}
"##;

const COMMON_CSS: &str = r##"
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f5f6fa;color:#24292f}
.card{background:#fff;border-radius:12px;box-shadow:0 2px 10px rgba(0,0,0,.08);padding:20px;width:min(420px,calc(100vw - 28px));margin:8vh auto}
h1{font-size:20px;margin-bottom:16px;text-align:center}
input,button{font-size:14px;padding:10px 12px;border-radius:8px;border:1px solid #d0d7de;outline:none;width:100%}
input:focus{border-color:#d32f2f}
button{background:#d32f2f;color:#fff;border:none;cursor:pointer;margin-top:10px;font-weight:600}
button:hover{background:#b71c1c}
button.ghost{background:#fff;color:#24292f;border:1px solid #d0d7de}
.msg{color:#d32f2f;font-size:13px;margin-top:8px;min-height:18px}
.chips{display:flex;gap:8px;margin:12px 0}
.chip{flex:1;text-align:center;padding:8px;border-radius:8px;border:1px solid #d0d7de;cursor:pointer}
.chip.on{background:#d32f2f;color:#fff;border-color:#d32f2f}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#9e9e9e;margin-right:6px}
.dot.on{background:#2e7d32}
.dot.wait{background:#f9a825}
@media (min-width:900px){
  .card{margin-top:16vh}
}
"##;

/// /user 用户页：注册/登录 + 设备列表 + 地图（与 APK 同一套 WS 协议）
/// 响应式：手机上下堆叠；≥900px 桌面端左侧设备栏 + 右侧大地图
pub const USER_PAGE: &str = r##"<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>位置共享</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css">
<style>
__CSS__
html,body{height:100%}
#app{display:flex;flex-direction:column;height:100dvh;max-width:560px;margin:0 auto;padding:10px 12px}
#side{display:flex;flex-direction:column;min-height:0;flex:1}
header{display:flex;align-items:center;justify-content:space-between;padding:4px 2px 10px}
header b{font-size:16px}
#devices{list-style:none;margin:8px 0;overflow-y:auto}
#devices li{display:flex;align-items:center;background:#fff;border-radius:10px;padding:12px 14px;margin-bottom:8px;box-shadow:0 1px 4px rgba(0,0,0,.06)}
#devices li .nm{flex:1}
#devices li .nm small{display:block;color:#8a8f98}
#devices li button{width:auto;margin:0;padding:8px 14px;font-size:13px}
#info{font-size:13px;color:#57606a;padding:2px 2px 8px;min-height:20px}
#map{flex:1;min-height:42vh;border-radius:12px;overflow:hidden}
@media (min-width:900px){
  #app{max-width:1200px;flex-direction:row;gap:16px;padding:16px}
  #side{width:320px;flex-shrink:0;flex:0 0 320px}
  #map{flex:1;min-height:0;height:auto}
}
</style></head><body>
<div id="auth"><div class="card">
<h1>位置共享 · 网页端</h1>
<div class="chips"><div class="chip on" id="cR" onclick="setMode(1)">注册</div><div class="chip" id="cL" onclick="setMode(0)">登录</div></div>
<input id="u" placeholder="用户名" autocomplete="username">
<input id="p" type="password" placeholder="密码" autocomplete="current-password">
<input id="i" placeholder="邀请码">
<button onclick="doAuth()">确定</button>
<div class="msg" id="msg"></div>
</div></div>

<div id="app" style="display:none">
<div id="side">
<header><b><span class="dot" id="connDot"></span><span id="connTxt">未连接</span></b>
<button class="ghost" style="width:auto" onclick="logout()">退出</button></header>
<ul id="devices"></ul>
<div id="info"></div>
</div>
<div id="map"></div>
</div>

<script src="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
__GEO__
var $=function(s){return document.querySelector(s)};
var token=localStorage.getItem('locator_token')||'';
var ws=null,fails=0,openedAt=0,devices=[],myId=0,myName='';
var map=null,marker=null,useGaode=true;

function gaodeLayer(){return L.tileLayer('https://wprd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scl=1&style=7&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:18});}
function osmLayer(){return L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19});}
function ensureMap(){
  if(map){setTimeout(function(){map.invalidateSize();},50);return;}
  map=L.map('map',{zoomControl:true}).setView([35,105],4);
  gaodeLayer().addTo(map);
  L.control.layers({'高德':gaodeLayer(),'OSM':osmLayer()},null,{collapsed:true}).addTo(map);
  map.on('baselayerchange',function(e){useGaode=(e.name==='高德');if(lastFix)placeMarker(lastFix);});
  window.addEventListener('resize',function(){map.invalidateSize();});
}
var lastFix=null;
function placeMarker(f){
  ensureMap();
  var ll=useGaode?wgs2gcj(f.lat,f.lng):[f.lat,f.lng];
  if(!marker){marker=L.marker(ll).addTo(map);}else{marker.setLatLng(ll);}
  map.setView(ll,16);
  $('#info').textContent='纬度 '+f.lat.toFixed(5)+'  经度 '+f.lng.toFixed(5)+(f.acc?('  ±'+f.acc.toFixed(0)+'m'):'')+'  更新于 '+new Date(f.ts*1000).toLocaleTimeString();
}
function setConn(on){
  $('#connDot').className='dot'+(on?' on':'');
  $('#connTxt').textContent=on?('已连接 · '+myName):'未连接，重连中…';
}
function send(o){if(ws&&ws.readyState===1)ws.send(JSON.stringify(o));}
function connect(){
  if(ws){ws.onclose=null;ws.close();ws=null;}
  var u=new URL((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws');
  u.searchParams.set('token',token);
  ws=new WebSocket(u);
  ws.onopen=function(){openedAt=Date.now();fails=0;setConn(true);};
  ws.onclose=function(){
    setConn(false);
    if(Date.now()-openedAt<2500&&openedAt>0){fails++;if(fails>=3){token='';localStorage.removeItem('locator_token');showAuth();return;}}
    setTimeout(connect,2500);
  };
  ws.onmessage=function(e){
    var m=JSON.parse(e.data);
    if(m.type==='auth_ok'){myId=m.device_id;myName=m.name;setConn(true);}
    else if(m.type==='device_list'){devices=m.devices;renderDevices();}
    else if(m.type==='location_result'){lastFix=m;placeMarker(m);}
    else if(m.type==='location_error'){$('#info').textContent='错误：'+m.reason;}
    else if(m.type==='location_request'){
      // 被请求：用浏览器定位现场取一次
      if(!navigator.geolocation){send({type:'location_error',request_id:m.request_id,reason:'网页端不支持定位'});return;}
      navigator.geolocation.getCurrentPosition(function(p){
        send({type:'location_report',request_id:m.request_id,lat:p.coords.latitude,lng:p.coords.longitude,accuracy:p.coords.accuracy,ts:Math.floor(Date.now()/1000)});
      },function(err){send({type:'location_error',request_id:m.request_id,reason:'浏览器定位失败:'+err.message});},{timeout:10000});
    }
  };
}
function renderDevices(){
  var ul=$('#devices');ul.innerHTML='';
  devices.forEach(function(d){
    var li=document.createElement('li');
    var self=d.id===myId;
    li.innerHTML='<span class="dot'+(d.online?' on':'')+'"></span><span class="nm">'+d.name+
      '<small>'+(self?'本机':(d.online?'在线':'离线'))+'</small></span>';
    if(d.online&&!self){var b=document.createElement('button');b.textContent='请求定位';
      b.onclick=function(){lastFix=null;$('#info').textContent='等待 '+d.name+' 上报位置…';send({type:'request_location',target_id:d.id});};
      li.appendChild(b);}
    ul.appendChild(li);
  });
}
function showAuth(){$('#auth').style.display='';$('#app').style.display='none';}
function showApp(){$('#auth').style.display='none';$('#app').style.display='flex';}
function setMode(reg){$('#cR').className='chip'+(reg?' on':'');$('#cL').className='chip'+(reg?'':' on');$('#i').style.display=reg?'':'none';}
async function doAuth(){
  var reg=$('#cR').className.indexOf('on')>=0;
  var body=reg?{username:$('#u').value,password:$('#p').value,invite_code:$('#i').value}
              :{username:$('#u').value,password:$('#p').value};
  try{
    var r=await fetch(reg?'/api/register':'/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    var j=await r.json();if(!r.ok)throw new Error(j.error||('HTTP '+r.status));
    token=j.token;localStorage.setItem('locator_token',token);
    $('#msg').textContent='';showApp();connect();
  }catch(e){$('#msg').textContent=e.message;}
}
function logout(){token='';localStorage.removeItem('locator_token');if(ws){ws.onclose=null;ws.close();}showAuth();}
window.onload=function(){if(token){showApp();connect();}else showAuth();};
</script></body></html>
"##;

/// /admin 管理页：密码登录 + 设备管理 + 邀请码
/// 响应式：桌面宽表格；移动端表格横向滚动、触控友好
pub const ADMIN_PAGE: &str = r##"<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>位置共享 · 管理后台</title>
<style>
__CSS__
#app{max-width:900px;margin:0 auto;padding:12px 14px 40px}
h2{font-size:15px;margin:18px 0 8px;color:#57606a}
.twrap{overflow-x:auto;-webkit-overflow-scrolling:touch;background:#fff;border-radius:10px;box-shadow:0 1px 4px rgba(0,0,0,.06)}
table{width:100%;border-collapse:collapse;min-width:520px}
th,td{padding:12px;white-space:nowrap;font-size:13px;border-bottom:1px solid #f0f1f3}
th{background:#fafbfc;color:#57606a;position:sticky;top:0}
td button{width:auto;margin:0;padding:6px 12px;font-size:12px;background:#d32f2f}
.row{display:flex;gap:8px;align-items:center;background:#fff;border-radius:10px;padding:12px;box-shadow:0 1px 4px rgba(0,0,0,.06)}
.row input{width:90px}
#codes{margin-top:8px;font-family:ui-monospace,monospace;font-size:14px;background:#fff;border-radius:10px;padding:12px;box-shadow:0 1px 4px rgba(0,0,0,.06)}
#codes div{display:flex;justify-content:space-between;padding:5px 0}
header{display:flex;justify-content:space-between;align-items:center;padding:4px 0 10px}
@media (min-width:900px){
  #app{padding:20px}
  th,td{font-size:14px}
}
</style></head><body>
<div id="auth"><div class="card">
<h1>管理后台</h1>
<input id="pw" type="password" placeholder="管理员密码">
<button onclick="adminLogin()">登录</button>
<div class="msg" id="msg"></div>
</div></div>

<div id="app" style="display:none">
<header><b>位置共享 · 管理后台</b><button class="ghost" style="width:auto" onclick="location.reload()">退出</button></header>

<h2>设备（<span id="onlineN">0</span>/<span id="totalN">0</span> 在线，5 秒自动刷新）</h2>
<div class="twrap">
<table id="devT"><thead><tr><th>ID</th><th>用户名</th><th>状态</th><th>最后在线</th><th></th></tr></thead><tbody></tbody></table>
</div>

<h2>邀请码</h2>
<div class="row">
<input id="cnt" type="number" value="5" min="1" max="50" style="flex:1">
<button style="width:auto;margin:0" onclick="genInvites()">生成</button>
</div>
<div id="codes"></div>
</div>

<script>
var $=function(s){return document.querySelector(s)};
var t=localStorage.getItem('locator_admin')||'';
function fmtTs(ts){return ts>0?new Date(ts*1000).toLocaleString():'-';}
async function api(path,opts){
  opts=opts||{};opts.headers=Object.assign({'Content-Type':'application/json','Authorization':'Bearer '+t},opts.headers||{});
  var r=await fetch(path,opts);
  if(r.status===401){localStorage.removeItem('locator_admin');showAuth();throw new Error('未授权');}
  var j=await r.json().catch(function(){return {}});if(!r.ok)throw new Error(j.error||('HTTP '+r.status));return j;
}
function showAuth(){$('#auth').style.display='';$('#app').style.display='none';}
async function loadUsers(){
  var j=await api('/admin/api/users');
  var tb=$('#devT tbody');tb.innerHTML='';
  var on=0;
  j.users.forEach(function(u){if(u.online)on++;
    var tr=document.createElement('tr');
    tr.innerHTML='<td>'+u.id+'</td><td>'+u.name+'</td><td><span class="dot'+(u.online?' on':'')+'"></span>'+(u.online?'在线':'离线')+'</td><td>'+fmtTs(u.last_seen)+'</td>';
    var td=document.createElement('td');var b=document.createElement('button');b.textContent='删除';
    b.onclick=async function(){if(!confirm('删除设备 '+u.name+'？'))return;await api('/admin/api/users/'+u.id,{method:'DELETE'});loadUsers();};
    td.appendChild(b);tr.appendChild(td);tb.appendChild(tr);});
  $('#onlineN').textContent=on;$('#totalN').textContent=j.users.length;
}
async function loadInvites(){
  var j=await api('/admin/api/invites');
  var box=$('#codes');box.innerHTML='';
  j.invites.forEach(function(c){
    var d=document.createElement('div');
    d.innerHTML='<span>'+c.code+'</span><span style="color:'+(c.used?'#d32f2f':'#2e7d32')+'">'+(c.used?'已使用':'未使用')+'</span>';
    box.appendChild(d);});
}
async function genInvites(){
  var n=parseInt($('#cnt').value)||1;
  var j=await api('/admin/api/invites',{method:'POST',body:JSON.stringify({count:n})});
  loadInvites();alert('已生成：\n'+j.codes.join('\n'));
}
async function adminLogin(){
  try{
    var r=await fetch('/admin/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:$('#pw').value})});
    var j=await r.json();if(!r.ok)throw new Error(j.error||('HTTP '+r.status));
    t=j.token;localStorage.setItem('locator_admin',t);
    $('#msg').textContent='';boot();
  }catch(e){$('#msg').textContent=e.message;}
}
function boot(){
  $('#auth').style.display='none';$('#app').style.display='';
  loadUsers();loadInvites();setInterval(loadUsers,5000);
}
window.onload=function(){if(t)boot();else showAuth();};
</script></body></html>
"##;

/// 拼装页面（替换公共 CSS / GEO 片段占位符）
pub fn render(page: &str) -> String {
    page.replace("__CSS__", COMMON_CSS).replace("__GEO__", JS_GEO)
}
