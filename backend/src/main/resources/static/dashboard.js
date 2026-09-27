"use strict";
const $ = id => document.getElementById(id);
const fmt = n => new Intl.NumberFormat("zh-CN").format(n);
const stateNames = {QUEUED:"排队中",RUNNING:"执行中",SUCCEEDED:"已完成",FAILED:"失败",CANCELLED:"已中断",WAITING_QUOTA:"等待额度"};
let credential = "", pending = false, generation = 0, quotaPending = false;
function text(id, value) { $(id).textContent = value; }
function node(tag, value, className) { const el=document.createElement(tag); if(value!=null)el.textContent=value; if(className)el.className=className; return el; }
const navigation = [...document.querySelectorAll("aside .nav")];
const sections = navigation.map(link => $(link.hash.slice(1)));
let navigationTarget = null, navigationFrame = 0;
function selectNavigation(id) {
  navigation.forEach(link => {
    const selected = link.hash === `#${id}`;
    link.classList.toggle("active", selected);
    if(selected)link.setAttribute("aria-current", "location");
    else link.removeAttribute("aria-current");
  });
}
function followNavigationHash() {
  navigationTarget = sections.find(section => `#${section.id}` === location.hash) || sections[0];
  selectNavigation(navigationTarget.id);
  if(!$("content").hidden)navigationTarget.scrollIntoView({block:"start"});
}
function syncNavigation() {
  if(navigationFrame)return;
  navigationFrame = requestAnimationFrame(() => {
    navigationFrame = 0;
    if($("content").hidden || navigationTarget)return;
    let current = sections[0];
    for(const section of sections)if(section.getBoundingClientRect().top <= 80)current = section;
    // The final section can be too short to reach the top of the viewport.
    if(window.scrollY > 0 && window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2)current = sections.at(-1);
    selectNavigation(current.id);
  });
}
navigation.forEach(link => link.addEventListener("click", event => {
  if(event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)return;
  navigationTarget = $(link.hash.slice(1));
  selectNavigation(navigationTarget.id);
}));
// Keep an explicit anchor selection even when scrolling is clamped at the page bottom.
// Resume scroll tracking when the user starts scrolling themselves.
for(const type of ["wheel", "touchstart", "pointerdown"])window.addEventListener(type, () => {navigationTarget = null;}, {passive:true});
window.addEventListener("keydown", event => {
  if(["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "].includes(event.key) && !event.target.closest("input,select,textarea,[contenteditable]"))navigationTarget = null;
});
window.addEventListener("scroll", syncNavigation, {passive:true});
window.addEventListener("resize", syncNavigation);
window.addEventListener("hashchange", followNavigationHash);
new ResizeObserver(syncNavigation).observe($("content"));
followNavigationHash();
function render(data) {
  text("total",fmt(data.usage.total)); text("io",`${fmt(data.usage.input)} / ${fmt(data.usage.output)}`);
  text("cache",`${fmt(data.usage.cacheRead)} / ${fmt(data.usage.cacheWrite)}`);
  text("count",fmt(data.tasks));text("coverage",`${data.reportedTasks} / ${data.tasks} 个任务返回了用量`);
  text("running",`执行中 ${data.statuses.RUNNING||0} · 完成 ${data.statuses.SUCCEEDED||0} · 等待额度 ${data.statuses.WAITING_QUOTA||0} · 中断 ${data.statuses.CANCELLED||0}`);
  $("bars").replaceChildren(); const max=Math.max(1,...data.daily.map(d=>d.usage.total));
  data.daily.forEach(d=>{const bar=node("div",null,"bar");bar.style.height=`${Math.max(d.usage.total?2:0,100*d.usage.total/max)}%`;bar.title=`${d.date}：${fmt(d.usage.total)} Token`;$("bars").append(bar);});
  text("start",data.daily[0]?.date||"");text("end",data.daily.at(-1)?.date||"");
  $("bars").setAttribute("aria-label",data.daily.map(d=>`${d.date} ${d.usage.total} Token`).join("；"));
  $("models").replaceChildren();data.models.forEach(m=>{const row=node("div",null,"model-row");row.append(node("span",m.model),node("b",fmt(m.usage.total)));$("models").append(row);});
  if(!data.models.length)$("models").append(node("p","暂无已上报的用量","muted"));

  $("task-list").replaceChildren();data.recent.forEach(t=>{const row=node("tr");row.append(node("td",new Date(t.created_at).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"})));const id=node("td",t.id.slice(0,8));id.title=t.id;row.append(id,node("td",(t.model||"—")+(t.account_id?` / ${t.account_id}`:"")));const status=node("td");status.append(node("span",stateNames[t.status]||t.status,"state "+t.status));if(t.status==="WAITING_QUOTA") {
    const reasons={QUOTA_WEEKLY_EXHAUSTED:"周额度已耗尽",QUOTA_INTERVAL_EXHAUSTED:"短周期额度已耗尽",QUOTA_UNCONFIRMED:"暂时无法确认额度，保留等待",ACCOUNT_POOL_UNAVAILABLE:"所有账号暂不可用"};
    status.append(node("div",reasons[t.pause_reason]||"等待额度检查","note"));
    status.append(node("div",t.resume_at?`预计 ${new Date(t.resume_at).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"})} 恢复（北京时间）`:"恢复时间待确认","note"));
  }row.append(status,node("td",t.usage?fmt(t.usage.total):"未上报"));$("task-list").append(row);});
  if(!data.recent.length){const row=node("tr"),cell=node("td","这段时间还没有任务");cell.colSpan=5;row.append(cell);$("task-list").append(row);}
  text("updated",`更新于 ${new Date(data.generatedAt).toLocaleTimeString("zh-CN")} · 每 15 秒自动刷新`);
}
async function refresh() {
  if(!credential||pending)return;pending=true;$("refresh").disabled=true;const version=generation;
  try { const res=await fetch(`/api/dashboard?days=${$("days").value}`,{headers:{Authorization:`Bearer ${credential}`},cache:"no-store",signal:AbortSignal.timeout(10000)});
    if(!res.ok)throw new Error(res.status===401?"凭据无效，请检查管理员凭据。":"管理服务暂时不可用，请稍后刷新。");
    const data=await res.json();if(version!==generation)return;const firstConnection=$("content").hidden;render(data);refreshAccounts();refreshQuota();$("content").hidden=false;$("login").hidden=true;$("error").hidden=true;text("connection","● 已连接");$("connection").classList.add("online");
    if(firstConnection && location.hash)followNavigationHash();
  } catch(e) {if(version!==generation)return;text("error",e.name==="TimeoutError"?"请求超时，请检查本地服务。":e.message);$("error").hidden=false;text("connection","连接异常");$("connection").classList.remove("online");}
  finally {pending=false;$("refresh").disabled=false;}
}
$("login").addEventListener("submit",e=>{e.preventDefault();credential=$("token").value.trim();$("token").value="";generation++;refresh();});
$("refresh").addEventListener("click",refresh);$("days").addEventListener("change",refresh);
$("logout").addEventListener("click",()=>{credential="";generation++;$("content").hidden=true;$("login").hidden=false;$("error").hidden=true;text("connection","尚未连接");$("connection").classList.remove("online");});
setInterval(()=>{if(!document.hidden)refresh();},15000);

function renderQuota(data) {
  const panel=$("quota");panel.replaceChildren(node("h2","套餐额度 · "+(data.plan||"官方查询")));
  if(data.message)panel.append(node("p",data.message,"note"));
  if(data.checkedAt)panel.append(node("p",`官方数据更新于 ${new Date(data.checkedAt).toLocaleString("zh-CN")}${data.status==="STALE"?" · 数据已过期":""}`,"muted"));
  const grid=node("div",null,"quota-grid");
  (data.windows||[]).forEach(w=>{
    const card=node("article",null,"quota-window");
    card.append(node("p",`${w.model} · ${w.kind==="weekly"?"周额度":"短周期额度"}`,"muted"));
    card.append(node("strong",`剩余 ${w.remainingPercent}%`));
    const progress=node("progress");progress.max=100;progress.value=w.remainingPercent;
    progress.setAttribute("aria-label",`${w.model} 剩余额度 ${w.remainingPercent}%`);card.append(progress);
    if(w.resetsAt)card.append(node("p",`重置：${new Date(w.resetsAt).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"})}（北京时间）`,"note"));
    if(w.resetsAt&&w.startsAt&&w.resetsAt>w.startsAt)card.append(node("small",`实际窗口 ${Math.round((w.resetsAt-w.startsAt)/3600000*10)/10} 小时`,"muted"));
    if(data.status==="AVAILABLE"&&w.model==="general"&&w.remainingPercent<=10)card.append(node("p",w.remainingPercent===0?"该账号额度已耗尽，将尝试其他可用账号。":"额度偏低，建议留意后续任务。","quota-warning"));
    grid.append(card);
  });panel.append(grid);
}
async function refreshQuota() {
  if(!credential||quotaPending)return;quotaPending=true;const version=generation;
  try {
    const res=await fetch("/api/quota",{headers:{Authorization:`Bearer ${credential}`},cache:"no-store",signal:AbortSignal.timeout(12000)});
    if(!res.ok)throw new Error("unavailable");
    const data=await res.json();if(version===generation)renderQuota(data);
  } catch {
    if(version===generation)renderQuota({message:"套餐额度暂时无法查询，请稍后刷新。"});
  } finally {quotaPending=false;}
}

let accountsPending=false, accountEditing=null, accountExecutor="model", accountSaving=false;
const accountReasons={ACCOUNT_AUTH:"认证失败，请更新密钥后重置",ACCOUNT_QUOTA:"额度或余额不足",ACCOUNT_RATE_LIMIT:"服务商限流，冷却中",ACCOUNT_DISABLED:"管理员已停用",ACCOUNT_UNCONFIGURED:"未启用或缺少凭据",QUOTA_INTERVAL_EXHAUSTED:"短周期额度耗尽",QUOTA_WEEKLY_EXHAUSTED:"周额度耗尽",QUOTA_UNCONFIRMED:"额度暂未确认"};
async function accountAction(id,action,button) {
  button.disabled=true;
  try {
    const response=await fetch(`/api/accounts/${encodeURIComponent(id)}/${action}`,{method:"POST",headers:{Authorization:`Bearer ${credential}`},signal:AbortSignal.timeout(10000)});
    if(!response.ok)throw new Error();
    await refreshAccounts();
  } catch {text("error","账号状态更新失败，请刷新后重试。");$("error").hidden=false;}
  finally {button.disabled=false;}
}
async function refreshAccounts() {
  if(!credential||accountsPending)return;accountsPending=true;const version=generation;
  try {
    const response=await fetch("/api/accounts",{headers:{Authorization:`Bearer ${credential}`},cache:"no-store",signal:AbortSignal.timeout(15000)});
    if(!response.ok)throw new Error();
    const data=await response.json();if(version!==generation)return;
    accountExecutor=data.executor;
    const list=$("account-list");list.replaceChildren(node("p",`自动切换已启用 · 已登记 ${data.configuredCount} 个账号 · 按配置顺序选择` ,"note"));
    if(data.configuredCount<2)list.append(node("p","当前只有一个账号，添加备用账号后才能实际切换。","note"));
    data.accounts.forEach(a=>{
      const item=node("article",null,"panel account"),main=node("div"),meta=node("div",null,"account-meta");
      main.append(node("h2",a.name),node("p",`${a.id} · ${a.executor} · ${a.model}`));
      meta.append(node("span",({ALLOW:a.quota.status==="UNSUPPORTED"?"可尝试":"可用",UNKNOWN:"额度未知",BLOCK:"暂不可用"})[a.status]||a.status,"pill"));
      if(a.reason)meta.append(node("p",accountReasons[a.reason]||a.reason,"note"));
      if(a.retryAt)meta.append(node("p",`下次检查：${new Date(a.retryAt).toLocaleString("zh-CN")}`,"note"));
      meta.append(node("p",`已记录 ${a.failures} 次账号异常`,"note"));
      (a.quota.windows||[]).filter(w=>w.model==="general"||w.model===a.model).forEach(w=>meta.append(node("p",`${w.kind==="weekly"?"周":"短周期"}额度：${w.remainingPercent}%${a.quota.status==="STALE"?"（旧数据）":""}`,"note")));
      if(a.quota.status!=="AVAILABLE")meta.append(node("p",a.quota.message||"该渠道未接入额度查询，按实际调用结果切换。","note"));
      const toggle=node("button",a.disabled?"启用":"停用","secondary"),reset=node("button","清除冷却 / 重试认证","secondary");
      toggle.onclick=()=>accountAction(a.id,a.disabled?"enable":"disable",toggle);
      reset.onclick=()=>accountAction(a.id,"reset",reset);
      const edit=node("button","编辑","secondary");edit.onclick=()=>openAccountForm(a);
      meta.append(edit,toggle,reset);item.append(main,meta);list.append(item);
    });
    if(data.switches.length) {
      list.append(node("h3","最近自动切换"));
      data.switches.forEach(s=>list.append(node("p",`${new Date(s.created_at).toLocaleString("zh-CN")} · ${s.detail} · 任务 ${s.task_id.slice(0,8)}`,"note")));
    }
  } catch {if(version===generation)$("account-list").replaceChildren(node("p","账号池状态暂时不可用，请刷新重试。","note"));}
  finally {accountsPending=false;}
}

function closeAccountForm() {
  $("account-key").value="";$("account-form").hidden=true;accountEditing=null;
}
function openAccountForm(account=null) {
  if(accountSaving)return;
  accountEditing=account;
  $("account-form").reset();$("account-form").hidden=false;$("account-form-error").hidden=true;$("account-saved").hidden=true;
  text("account-form-title",account?"编辑账号":"添加账号");
  $("account-name").value=account?.name||"";
  $("account-mode").value=account?.mode==="compatible"?"compatible":"anthropic";
  $("account-mode").querySelector('option[value="compatible"]').disabled=accountExecutor==="claude";
  $("account-url").value=account?.baseUrl||"https://api.minimaxi.com/anthropic";
  $("account-model").value=account?.model==="mock-demo"?"":account?.model||"MiniMax-M3";
  $("account-priority").value=account?.priority??100;
  $("account-key").required=!account?.hasKey;
  $("account-key").placeholder=account?.hasKey?"已配置；留空保留原密钥":"粘贴 API 密钥";
  text("account-form-note",`${accountExecutor==="claude"?"当前代码助手需要 Anthropic 兼容接口。":""}优先级数字越小越优先。${account?"密钥留空保留原值；更换接口地址时请重新填写密钥。":""}`);
  $("account-name").focus();$("account-form").scrollIntoView({block:"nearest",behavior:"smooth"});
}
$("account-add").addEventListener("click",()=>openAccountForm());
$("account-cancel").addEventListener("click",closeAccountForm);
$("logout").addEventListener("click",closeAccountForm);
$("account-form").addEventListener("submit",async event=>{
  event.preventDefault();if(accountSaving||!credential)return;
  const payload={name:$("account-name").value.trim(),mode:$("account-mode").value,model:$("account-model").value.trim(),baseUrl:$("account-url").value.trim(),apiKey:$("account-key").value.trim(),priority:Number($("account-priority").value)};
  const id=accountEditing?.id,version=generation;
  if(accountEditing&&payload.baseUrl.replace(/\/+$/,"")!==accountEditing.baseUrl.replace(/\/+$/,"")&&!payload.apiKey) {
    text("account-form-error","更换接口地址时，请重新填写 API 密钥。");$("account-form-error").hidden=false;return;
  }
  accountSaving=true;$("account-save").disabled=true;$("account-cancel").disabled=true;$("account-key").value="";
  try {
    const request=fetch(id?`/api/accounts/${encodeURIComponent(id)}`:"/api/accounts",{method:id?"PUT":"POST",headers:{Authorization:`Bearer ${credential}`,"Content-Type":"application/json"},body:JSON.stringify(payload),signal:AbortSignal.timeout(15000)});
    payload.apiKey="";
    const response=await request;if(version!==generation)return;
    if(!response.ok)throw new Error(response.status===401?"登录凭据已失效，请重新连接。":response.status===409?"账号池已满，最多支持 20 个账号。":"保存失败，请检查接口地址、模型和密钥是否填写完整，协议是否兼容。");
    closeAccountForm();text("account-saved","账号已保存，将用于后续账号选择；正在执行的任务不受影响。");$("account-saved").hidden=false;
    await refreshAccounts();
  } catch(error) {
    if(version===generation){text("account-form-error",error.name==="TimeoutError"?"保存请求超时，请先刷新账号列表确认是否已保存，再决定是否重试。":error.message);$("account-form-error").hidden=false;}
  } finally {payload.apiKey="";accountSaving=false;$("account-save").disabled=false;$("account-cancel").disabled=false;}
});
