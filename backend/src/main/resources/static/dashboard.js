"use strict";
const $ = id => document.getElementById(id);
const fmt = n => new Intl.NumberFormat("zh-CN").format(n);
const stateNames = {QUEUED:"排队中",RUNNING:"执行中",SUCCEEDED:"已完成",FAILED:"失败",CANCELLED:"已中断"};
let credential = "", pending = false, generation = 0;
function text(id, value) { $(id).textContent = value; }
function node(tag, value, className) { const el=document.createElement(tag); if(value!=null)el.textContent=value; if(className)el.className=className; return el; }
function render(data) {
  text("total",fmt(data.usage.total)); text("io",`${fmt(data.usage.input)} / ${fmt(data.usage.output)}`);
  text("cache",`${fmt(data.usage.cacheRead)} / ${fmt(data.usage.cacheWrite)}`);
  text("count",fmt(data.tasks));text("coverage",`${data.reportedTasks} / ${data.tasks} 个任务返回了用量`);
  text("running",`执行中 ${data.statuses.RUNNING||0} · 完成 ${data.statuses.SUCCEEDED||0} · 中断 ${data.statuses.CANCELLED||0}`);
  $("bars").replaceChildren(); const max=Math.max(1,...data.daily.map(d=>d.usage.total));
  data.daily.forEach(d=>{const bar=node("div",null,"bar");bar.style.height=`${Math.max(d.usage.total?2:0,100*d.usage.total/max)}%`;bar.title=`${d.date}：${fmt(d.usage.total)} Token`;$("bars").append(bar);});
  text("start",data.daily[0]?.date||"");text("end",data.daily.at(-1)?.date||"");
  $("bars").setAttribute("aria-label",data.daily.map(d=>`${d.date} ${d.usage.total} Token`).join("；"));
  $("models").replaceChildren();data.models.forEach(m=>{const row=node("div",null,"model-row");row.append(node("span",m.model),node("b",fmt(m.usage.total)));$("models").append(row);});
  if(!data.models.length)$("models").append(node("p","暂无已上报的用量","muted"));
  $("account-list").replaceChildren();data.accounts.forEach(a=>{const item=node("article",null,"panel account");const main=node("div");main.append(node("h2",a.name),node("p",`${a.executor} · ${a.model}`));const meta=node("div",null,"account-meta");meta.append(node("span","环境配置","pill"),node("div","剩余额度：未知 · 自动切换：未启用"));item.append(main,meta);$("account-list").append(item);});
  $("task-list").replaceChildren();data.recent.forEach(t=>{const row=node("tr");row.append(node("td",new Date(t.created_at).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"})));const id=node("td",t.id.slice(0,8));id.title=t.id;row.append(id,node("td",t.model||"—"));const status=node("td");status.append(node("span",stateNames[t.status]||t.status,"state "+t.status));row.append(status,node("td",t.usage?fmt(t.usage.total):"未上报"));$("task-list").append(row);});
  if(!data.recent.length){const row=node("tr"),cell=node("td","这段时间还没有任务");cell.colSpan=5;row.append(cell);$("task-list").append(row);}
  text("updated",`更新于 ${new Date(data.generatedAt).toLocaleTimeString("zh-CN")} · 每 15 秒自动刷新`);
}
async function refresh() {
  if(!credential||pending)return;pending=true;$("refresh").disabled=true;const version=generation;
  try { const res=await fetch(`/api/dashboard?days=${$("days").value}`,{headers:{Authorization:`Bearer ${credential}`},cache:"no-store",signal:AbortSignal.timeout(10000)});
    if(!res.ok)throw new Error(res.status===401?"凭据无效，请检查管理员凭据。":"管理服务暂时不可用，请稍后刷新。");
    const data=await res.json();if(version!==generation)return;render(data);$("content").hidden=false;$("login").hidden=true;$("error").hidden=true;text("connection","● 已连接");$("connection").classList.add("online");
  } catch(e) {if(version!==generation)return;text("error",e.name==="TimeoutError"?"请求超时，请检查本地服务。":e.message);$("error").hidden=false;text("connection","连接异常");$("connection").classList.remove("online");}
  finally {pending=false;$("refresh").disabled=false;}
}
$("login").addEventListener("submit",e=>{e.preventDefault();credential=$("token").value.trim();$("token").value="";generation++;refresh();});
$("refresh").addEventListener("click",refresh);$("days").addEventListener("change",refresh);
$("logout").addEventListener("click",()=>{credential="";generation++;$("content").hidden=true;$("login").hidden=false;$("error").hidden=true;text("connection","尚未连接");$("connection").classList.remove("online");});
setInterval(()=>{if(!document.hidden)refresh();},15000);
