"use strict";
const $ = id => document.getElementById(id);
const fmt = n => new Intl.NumberFormat("zh-CN").format(n);
const stateNames = {QUEUED:"排队中",RUNNING:"执行中",SUCCEEDED:"已完成",FAILED:"失败",CANCELLED:"已中断",WAITING_QUOTA:"等待额度"};
let currentTenant="", identity=null;
function authHeaders() { return {Authorization:`Bearer ${credential}`,"X-Tenant-ID":currentTenant}; }
let credential = "", pending = false, generation = 0, quotaPending = false;
function text(id, value) { $(id).textContent = value; }
function node(tag, value, className) { const el=document.createElement(tag); if(value!=null)el.textContent=value; if(className)el.className=className; return el; }
const navigation = [...document.querySelectorAll("aside .nav")];
const pages={overview:"工作台",tasks:"任务记录",accounts:"账号与渠道",skills:"技能管理",organization:"租户与成员"};
const openedPages=new Set(["overview"]);
let taskRows=[],taskPage=0,taskDetailVersion=0;
function selectNavigation(id) {
  navigation.forEach(link=>{const selected=link.hash===`#${id}`;link.classList.toggle("active",selected);if(selected)link.setAttribute("aria-current","page");else link.removeAttribute("aria-current");});
}
function followNavigationHash() {
  const requested=location.hash.slice(1),id=Object.hasOwn(pages,requested)?requested:"overview";
  if(requested!==id)history.replaceState(null,"",`#${id}`);
  for(const key of Object.keys(pages))$(key).hidden=key!==id;
  selectNavigation(id);text("page-name",pages[id]);document.title=`${pages[id]} · BunBird`;
  openedPages.add(id);$("page-tabs").replaceChildren(...[...openedPages].map(key=>{const link=node("a",pages[key]);link.href=`#${key}`;if(key===id){link.className="active";link.setAttribute("aria-current","page");}return link;}));
  $("page-tabs").hidden=!credential;
  $("skill-detail").close();$("task-detail").close();skillDetailVersion++;taskDetailVersion++;
  window.scrollTo({top:0});
}
window.addEventListener("hashchange",followNavigationHash);
function renderTasks() {
  const query=$("task-search").value.trim().toLowerCase(),status=$("task-filter").value;
  const rows=taskRows.filter(t=>(status==="all"||t.status===status)&&[t.id,t.model,t.account_id].some(v=>String(v||"").toLowerCase().includes(query)));
  taskPage=Math.min(taskPage,Math.max(0,Math.ceil(rows.length/10)-1));
  text("task-summary",`匹配 ${rows.length} / 已加载 ${taskRows.length} 条`);
  text("task-page-label",rows.length?`第 ${taskPage+1} / ${Math.ceil(rows.length/10)} 页 · 每页 10 条`:"暂无匹配任务");
  $("task-prev").disabled=taskPage===0;$("task-next").disabled=(taskPage+1)*10>=rows.length;
  $("task-list").replaceChildren();
  rows.slice(taskPage*10,taskPage*10+10).forEach(t=>{
    const row=node("tr"),id=node("td",t.id.slice(0,8));id.title=t.id;
    row.append(node("td",new Date(t.created_at).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"})),id,node("td",(t.model||"—")+(t.account_id?` / ${t.account_id}`:"")));
    const status=node("td");status.append(node("span",stateNames[t.status]||t.status,"state "+t.status));
    if(t.status==="WAITING_QUOTA"){
      const reasons={QUOTA_WEEKLY_EXHAUSTED:"周额度已耗尽",QUOTA_INTERVAL_EXHAUSTED:"短周期额度已耗尽",QUOTA_UNCONFIRMED:"暂时无法确认额度，保留等待",ACCOUNT_POOL_UNAVAILABLE:"所有账号暂不可用"};
      status.append(node("div",reasons[t.pause_reason]||"等待额度检查","note"),node("div",t.resume_at?`预计 ${new Date(t.resume_at).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"})} 恢复（北京时间）`:"恢复时间待确认","note"));
    }
    const action=node("td"),detail=node("button","查看详情","secondary");detail.onclick=()=>showTask(t.id);action.append(detail);
    row.append(status,node("td",t.usage?fmt(t.usage.total):"未上报"),action);$("task-list").append(row);
  });
  if(!rows.length){const row=node("tr"),cell=node("td",taskRows.length?"没有匹配的任务，试试其他筛选条件。":"这段时间还没有任务。可在飞书发送消息开始执行。");cell.colSpan=6;row.append(cell);$("task-list").append(row);}
}
async function showTask(id) {
  const version=++taskDetailVersion,loginVersion=generation;
  text("task-detail-meta","正在读取…");text("task-detail-prompt","");text("task-detail-result","");$("task-detail").showModal();
  try {
    const result=await identityRequest(`/api/tasks/${encodeURIComponent(id)}`,undefined,authHeaders(),"GET");
    if(version!==taskDetailVersion||loginVersion!==generation)return;
    text("task-detail-meta",`${result.id} · ${stateNames[result.status]||result.status} · ${result.model||"尚未记录模型"}`);
    text("task-detail-prompt",result.prompt||"无正文");text("task-detail-result",result.result||result.error_code||"尚未返回结果");
  }catch(error){if(version===taskDetailVersion&&loginVersion===generation)text("task-detail-meta",error.message);}
}
$("task-detail-close").onclick=()=>$("task-detail").close();
$("task-detail").addEventListener("close",()=>{taskDetailVersion++;});
for(const id of ["task-search","task-filter"])$(id).addEventListener("input",()=>{taskPage=0;renderTasks();});
$("task-prev").onclick=()=>{taskPage--;renderTasks();};$("task-next").onclick=()=>{taskPage++;renderTasks();};
function filterResources() {
  const query=$("account-search").value.trim().toLowerCase();
  const accounts=[...$("account-list").querySelectorAll("article")];let visibleAccounts=0;
  for(const card of accounts){card.hidden=!card.textContent.toLowerCase().includes(query);if(!card.hidden)visibleAccounts++;}
  text("account-summary",`显示 ${visibleAccounts} / ${accounts.length} 个账号`);
  const skills=[...$("skill-list").querySelectorAll("article")],skillQuery=$("skill-search").value.trim().toLowerCase(),filter=$("skill-filter").value;let visibleSkills=0;
  for(const card of skills){card.hidden=!card.dataset.search.includes(skillQuery)||(filter!=="all"&&card.dataset.state!==filter);if(!card.hidden)visibleSkills++;}
  text("skill-summary",`显示 ${visibleSkills} / ${skills.length} 个导入技能 · 内置 1 个`);
}
for(const id of ["account-search","skill-search","skill-filter"])$(id).addEventListener("input",filterResources);
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

  taskRows=data.recent;renderTasks();
  text("updated",`更新于 ${new Date(data.generatedAt).toLocaleTimeString("zh-CN")} · 每 15 秒自动刷新`);
}
async function refresh() {
  if(!credential||pending)return;pending=true;$("refresh").disabled=true;const version=generation;
  try { const res=await fetch(`/api/dashboard?days=${$("days").value}`,{headers:{...authHeaders()},cache:"no-store",signal:AbortSignal.timeout(10000)});
    if(version!==generation)return;
    if(res.status===401||res.status===403){$("content").hidden=true;$("login").hidden=false;}
    if(!res.ok)throw new Error(res.status===401?"登录已过期，请退出后重新登录。":"管理服务暂时不可用，请稍后刷新。");
    const data=await res.json();if(version!==generation)return;const firstConnection=$("content").hidden;render(data);refreshAccounts();refreshQuota();$("content").hidden=false;$("login").hidden=true;$("error").hidden=true;text("connection","● 已连接");$("connection").classList.add("online");
    if(firstConnection)followNavigationHash();
    refreshSkills();
  } catch(e) {if(version!==generation)return;text("error",e.name==="TimeoutError"?"请求超时，请检查本地服务。":e.message);$("error").hidden=false;text("connection","连接异常");$("connection").classList.remove("online");}
  finally {if(version===generation){pending=false;$("refresh").disabled=false;}}
}
$("refresh").addEventListener("click",refresh);$("days").addEventListener("change",refresh);
$("logout").addEventListener("click",()=>{fetch("/api/auth/logout",{method:"POST",headers:authHeaders()}).catch(()=>{});clearTenantView();credential="";identity=null;currentTenant="";$("content").hidden=true;$("login").hidden=false;$("error").hidden=true;text("connection","尚未连接");$("connection").classList.remove("online");});
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
    const res=await fetch("/api/quota",{headers:{...authHeaders()},cache:"no-store",signal:AbortSignal.timeout(12000)});
    if(!res.ok)throw new Error("unavailable");
    const data=await res.json();if(version===generation)renderQuota(data);
  } catch {
    if(version===generation)renderQuota({message:"套餐额度暂时无法查询，请稍后刷新。"});
  } finally {if(version===generation)quotaPending=false;}
}

let accountsPending=false, accountEditing=null, accountExecutor="model", accountSaving=false;
const accountReasons={ACCOUNT_AUTH:"认证失败，请更新密钥后重置",ACCOUNT_QUOTA:"额度或余额不足",ACCOUNT_RATE_LIMIT:"服务商限流，冷却中",ACCOUNT_DISABLED:"管理员已停用",ACCOUNT_UNCONFIGURED:"未启用或缺少凭据",QUOTA_INTERVAL_EXHAUSTED:"短周期额度耗尽",QUOTA_WEEKLY_EXHAUSTED:"周额度耗尽",QUOTA_UNCONFIRMED:"额度暂未确认"};
async function accountAction(id,action,button) {
  button.disabled=true;const version=generation;
  try {
    const response=await fetch(`/api/accounts/${encodeURIComponent(id)}/${action}`,{method:"POST",headers:{...authHeaders()},signal:AbortSignal.timeout(10000)});
    if(!response.ok)throw new Error();
    if(version===generation)await refreshAccounts();
  } catch {if(version===generation){text("error","账号状态更新失败，请刷新后重试。");$("error").hidden=false;}}
  finally {button.disabled=false;}
}
async function refreshAccounts() {
  if(!credential||accountsPending)return;accountsPending=true;const version=generation;
  try {
    const response=await fetch("/api/accounts",{headers:{...authHeaders()},cache:"no-store",signal:AbortSignal.timeout(15000)});
    if(!response.ok)throw new Error();
    const data=await response.json();if(version!==generation)return;
    accountExecutor=data.executor;
    const list=$("account-list");list.replaceChildren(node("p",`自动切换已启用 · 已登记 ${data.configuredCount} 个账号 · 按配置顺序选择` ,"note"));
    if(data.configuredCount===1)list.append(node("p","当前只有一个账号，添加备用账号后才能实际切换。","note"));
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
      if(identity?.role==="ADMIN")meta.append(edit,toggle,reset);item.append(main,meta);list.append(item);
    });
    if(data.switches.length) {
      list.append(node("h3","最近自动切换"));
      data.switches.forEach(s=>list.append(node("p",`${new Date(s.created_at).toLocaleString("zh-CN")} · ${s.detail} · 任务 ${s.task_id.slice(0,8)}`,"note")));
    }
  } catch {if(version===generation)$("account-list").replaceChildren(node("p","账号池状态暂时不可用，请刷新重试。","note"));}
  finally {if(version===generation){accountsPending=false;filterResources();}}
}

const modelAliases=["haiku","sonnet","opus","fable"];
let modelDiscoveryRequest=null, modelDiscoveryVersion=0;
function resetModelDiscovery() {
  modelDiscoveryVersion++;modelDiscoveryRequest?.abort();modelDiscoveryRequest=null;
  $("account-fetch-models").disabled=false;
  $("account-model-options").replaceChildren();
  const option=node("option","先获取模型列表");option.value="";
  $("account-model-choice").replaceChildren(option);$("account-model-choice").disabled=true;
  text("account-model-status","从当前渠道读取模型，也可以直接填写模型 ID。");
}
async function discoverAccountModels() {
  if(!credential||accountSaving)return;
  resetModelDiscovery();
  const version=modelDiscoveryVersion, loginVersion=generation;
  const controller=new AbortController();modelDiscoveryRequest=controller;
  const timer=setTimeout(()=>controller.abort(),30000);
  $("account-fetch-models").disabled=true;text("account-model-status","正在获取渠道支持的模型…");
  const payload={accountId:accountEditing?.id,baseUrl:$("account-url").value.trim(),mode:$("account-mode").value,apiKey:$("account-key").value.trim()};
  try {
    const request=fetch("/api/accounts/models",{method:"POST",headers:{...authHeaders(),"Content-Type":"application/json"},body:JSON.stringify(payload),signal:controller.signal});
    payload.apiKey="";
    const response=await request, data=await response.json();
    if(version!==modelDiscoveryVersion||loginVersion!==generation)return;
    if(!response.ok)throw new Error(response.status===401?"登录凭据已失效，请重新连接。":data.message||"无法获取模型列表，请检查配置或手动填写模型 ID。");
    const placeholder=node("option","选择一个模型用于默认会话");placeholder.value="";
    $("account-model-choice").replaceChildren(placeholder);
    for(const id of data.models) {
      const choice=node("option",id);choice.value=id;$("account-model-choice").append(choice);
      const suggestion=node("option");suggestion.value=id;$("account-model-options").append(suggestion);
    }
    $("account-model-choice").disabled=!data.models.length;
    $("account-model-choice").value=data.models.includes($("account-model").value)?$("account-model").value:"";
    text("account-model-status",data.models.length?`已获取 ${data.models.length} 个模型。映射输入框也可从列表选择；模型列表不代表工具或图片能力均兼容。`:"渠道返回了空列表，请手动填写模型 ID。");
  } catch(error) {
    if(version===modelDiscoveryVersion&&loginVersion===generation)text("account-model-status",error.name==="AbortError"?"获取超时，可重试或手动填写模型 ID。":error.message);
  } finally {
    clearTimeout(timer);payload.apiKey="";
    if(version===modelDiscoveryVersion){modelDiscoveryRequest=null;$("account-fetch-models").disabled=false;}
  }
}
$("account-fetch-models").addEventListener("click",discoverAccountModels);
$("account-model-choice").addEventListener("change",()=>{if($("account-model-choice").value)$("account-model").value=$("account-model-choice").value;});
for(const id of ["account-url","account-mode","account-key"])$(id).addEventListener("input",resetModelDiscovery);
function closeAccountForm() {
  resetModelDiscovery();
  $("account-key").value="";$("account-form").hidden=true;accountEditing=null;
}
function openAccountForm(account=null) {
  if(accountSaving)return;
  if(location.hash!=="#accounts")location.hash="accounts";
  accountEditing=account;
  resetModelDiscovery();
  $("account-form").reset();$("account-form").hidden=false;$("account-form-error").hidden=true;$("account-saved").hidden=true;
  text("account-form-title",account?"编辑账号":"添加账号");
  $("account-name").value=account?.name||"";
  $("account-mode").value=account?.mode==="compatible"?"compatible":"anthropic";
  $("account-mode").querySelector('option[value="compatible"]').disabled=accountExecutor==="claude";
  $("account-url").value=account?.baseUrl||"https://api.minimaxi.com/anthropic";
  $("account-model").value=account?.model==="mock-demo"?"":account?.model||"MiniMax-M3";
  $("account-priority").value=account?.priority??100;
  $("account-mapping").hidden=accountExecutor!=="claude";
  modelAliases.forEach(alias=>{$(`account-model-${alias}`).value=account?.modelMapping?.[alias]||"";});
  $("account-key").required=!account?.hasKey;
  $("account-key").placeholder=account?.hasKey?"已配置；留空保留原密钥":"粘贴 API 密钥";
  text("account-form-note",`${accountExecutor==="claude"?"当前代码助手需要 Anthropic 兼容接口。":""}优先级数字越小越优先。${account?"密钥留空保留原值；更换接口地址时请重新填写密钥。":""}`);
  $("account-name").focus();$("account-form").scrollIntoView({block:"nearest",behavior:"smooth"});
  if(account?.hasKey)discoverAccountModels();
}
$("account-add").addEventListener("click",()=>openAccountForm());
$("account-cancel").addEventListener("click",closeAccountForm);
$("logout").addEventListener("click",closeAccountForm);
$("account-form").addEventListener("submit",async event=>{
  event.preventDefault();if(accountSaving||!credential)return;
  const payload={name:$("account-name").value.trim(),mode:$("account-mode").value,model:$("account-model").value.trim(),baseUrl:$("account-url").value.trim(),apiKey:$("account-key").value.trim(),priority:Number($("account-priority").value),modelMapping:Object.fromEntries(modelAliases.map(alias=>[alias,$(`account-model-${alias}`).value.trim()]))};
  const id=accountEditing?.id,version=generation;
  if(accountEditing&&payload.baseUrl.replace(/\/+$/,"")!==accountEditing.baseUrl.replace(/\/+$/,"")&&!payload.apiKey) {
    text("account-form-error","更换接口地址时，请重新填写 API 密钥。");$("account-form-error").hidden=false;return;
  }
  accountSaving=true;$("account-save").disabled=true;$("account-cancel").disabled=true;$("account-key").value="";
  resetModelDiscovery();
  try {
    const request=fetch(id?`/api/accounts/${encodeURIComponent(id)}`:"/api/accounts",{method:id?"PUT":"POST",headers:{...authHeaders(),"Content-Type":"application/json"},body:JSON.stringify(payload),signal:AbortSignal.timeout(15000)});
    payload.apiKey="";
    const response=await request;if(version!==generation)return;
    if(!response.ok)throw new Error(response.status===401?"登录凭据已失效，请重新连接。":response.status===409?"账号池已满，最多支持 20 个账号。":"保存失败，请检查接口地址、模型和密钥是否填写完整，协议是否兼容。");
    closeAccountForm();text("account-saved","账号已保存，将用于后续账号选择；正在执行的任务不受影响。");$("account-saved").hidden=false;
    await refreshAccounts();
  } catch(error) {
    if(version===generation){text("account-form-error",error.name==="TimeoutError"?"保存请求超时，请先刷新账号列表确认是否已保存，再决定是否重试。":error.message);$("account-form-error").hidden=false;}
  } finally {payload.apiKey="";accountSaving=false;$("account-save").disabled=false;$("account-cancel").disabled=false;}
});

let skillsPending=false, skillImporting=false, skillDetailVersion=0;
async function refreshSkills() {
  if(!credential||skillsPending)return;
  skillsPending=true;const version=generation;
  try {
    const response=await fetch("/api/skills",{headers:{...authHeaders()},cache:"no-store",signal:AbortSignal.timeout(10000)});
    if(!response.ok)throw new Error();
    const skills=await response.json();if(version!==generation)return;
    const list=$("skill-list");list.replaceChildren();
    for(const skill of skills) {
      const card=node("article",null,"panel account"),info=node("div"),actions=node("div",null,"account-meta");
      card.dataset.search=`${skill.name} ${skill.source}`.toLowerCase();card.dataset.state=skill.enabled?"enabled":"disabled";
      info.append(node("h2",skill.name),node("p",`${skill.source_type==="git"?"Git 仓库":"本地目录"} · ${skill.source}`),node("p",`版本 ${skill.revision.slice(0,12)}`,"note"));
      const view=node("button","查看 SKILL.md","secondary"),toggle=node("button",skill.enabled?"停用":"启用","secondary");
      view.type=toggle.type="button";
      view.onclick=()=>showSkill(skill.id);
      toggle.onclick=async()=>{
        toggle.disabled=true;const loginVersion=generation;
        try {
          const result=await fetch(`/api/skills/${encodeURIComponent(skill.id)}/state`,{method:"POST",headers:{...authHeaders(),"Content-Type":"application/json"},body:JSON.stringify({enabled:!skill.enabled}),signal:AbortSignal.timeout(10000)});
          if(!result.ok)throw new Error();
          if(loginVersion===generation){text("skill-message","状态已更新，将用于后续任务。");await refreshSkills();}
        } catch {if(loginVersion===generation)text("skill-message","状态更新失败，请刷新后重试。");}
        finally {toggle.disabled=false;}
      };
      actions.append(node("span",skill.enabled?"已启用":"已停用","pill"),view);if(identity?.role==="ADMIN")actions.append(toggle);card.append(info,actions);list.append(card);
    }
    if(!skills.length)list.append(node("p","尚未导入额外技能。","note"));
  } catch {if(version===generation)$("skill-list").replaceChildren(node("p","技能列表读取失败，请刷新重试。","note"));}
  finally {if(version===generation){skillsPending=false;filterResources();}}
}
async function showSkill(id) {
  const version=++skillDetailVersion,loginVersion=generation;
  try {
    const response=await fetch(`/api/skills/${encodeURIComponent(id)}`,{headers:{...authHeaders()},cache:"no-store",signal:AbortSignal.timeout(10000)});
    if(!response.ok)throw new Error();
    const skill=await response.json();if(version!==skillDetailVersion||loginVersion!==generation)return;
    text("skill-detail-title",skill.name+" · SKILL.md");text("skill-instructions",skill.instructions);$("skill-detail").hidden=false;
    $("skill-detail").showModal();
  } catch {if(loginVersion===generation)text("skill-message","技能说明读取失败，请重试。");}
}
$("skill-detail-close").onclick=()=>$("skill-detail").close();
$("skill-detail").addEventListener("close",()=>{skillDetailVersion++;$("skill-detail").hidden=true;});
$("skill-type").onchange=()=>{
  const git=$("skill-type").value==="git";
  $("skill-directory-label").hidden=!git;$("skill-directory").value="";
  $("skill-source").placeholder=git?"https://github.com/组织/仓库.git":"服务器上的技能绝对目录路径";
};
$("logout").addEventListener("click",()=>{skillDetailVersion++;$("skill-detail").hidden=true;$("skill-instructions").textContent="";$("skill-import").reset();$("skill-type").onchange();});
$("skill-import").addEventListener("submit",async event=>{
  event.preventDefault();if(skillImporting||!credential)return;
  const version=generation;
  const payload={type:$("skill-type").value,source:$("skill-source").value.trim(),directory:$("skill-directory").value.trim(),name:$("skill-name").value.trim(),enabled:$("skill-enabled").checked};
  skillImporting=true;$("skill-import-submit").disabled=true;text("skill-message","正在导入并检查技能文件…");
  try {
    const response=await fetch("/api/skills/import",{method:"POST",headers:{...authHeaders(),"Content-Type":"application/json"},body:JSON.stringify(payload),signal:AbortSignal.timeout(75000)});
    const result=await response.json();if(version!==generation)return;
    if(!response.ok)throw new Error(result.message||"导入失败，请检查来源目录或仓库地址。");
    text("skill-message",`「${result.name}」已导入${result.enabled?"并启用":"，当前为停用状态"}。`);await refreshSkills();
  } catch(error) {if(version===generation)text("skill-message",error.name==="TimeoutError"?"请求超时，请先刷新技能列表确认导入结果。":error.message);}
  finally {skillImporting=false;$("skill-import-submit").disabled=false;}
});

async function identityRequest(url,body,headers=authHeaders(),method="POST") {
  const response=await fetch(url,{method,headers:{...headers,"Content-Type":"application/json"},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(15000)});
  const data=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(data.message || (response.status===429?"尝试过于频繁，请一分钟后重试。":response.status===401?"用户名、密码或登录凭据无效。":response.status===403?"没有执行此操作的权限。":"操作失败，请检查输入后重试。"));
  return data;
}
async function loadIdentity() {
  const version=generation;
  const nextIdentity=await identityRequest("/api/auth/me",undefined,authHeaders(),"GET");
  if(version!==generation)return;identity=nextIdentity;
  currentTenant=identity.tenantId;
  $("tenant-select").replaceChildren(...identity.tenants.map(t=>{const option=node("option",t.name);option.value=t.id;return option;}));
  $("tenant-select").value=currentTenant;
  const admin=identity.role==="ADMIN";
  text("tenant-role",admin?"租户管理员":"成员 · 只读");text("tenant-identity",currentTenant);
  $("account-add").hidden=!admin;$("skill-import").hidden=!admin;$("skill-import-wrap").hidden=!admin;$("invite-form").hidden=!admin;
  $("tenant-form").hidden=!identity.platformAdmin;$("feishu-form").hidden=!identity.platformAdmin;
  $("skill-type").querySelector('option[value="local"]').disabled=!identity.platformAdmin;
  if(!identity.platformAdmin){$("skill-type").value="git";$("skill-type").onchange();}
  await loadMembers();
}
async function loadMembers() {
  const version=generation;
  const members=await identityRequest("/api/organization/members",undefined,authHeaders(),"GET");
  if(version!==generation)return;
  $("member-list").replaceChildren(...members.map(m=>{
    const row=node("p",`${m.username} · ${m.role==="ADMIN"?"管理员":"成员"}`);
    if(identity.role==="ADMIN" && m.id!==identity.userId){const remove=node("button","移除","secondary");remove.type="button";remove.onclick=()=>organizationAction(async()=>{await identityRequest(`/api/organization/members/${encodeURIComponent(m.id)}`,undefined,authHeaders(),"DELETE");await loadMembers();});row.append(" ",remove);}
    return row;
  }));
}
function clearTenantView() {
  taskRows=[];taskPage=0;taskDetailVersion++;$("task-detail").close();$("skill-detail").close();$("page-tabs").hidden=true;
  for(const id of ["task-search","account-search","skill-search"])$(id).value="";$("task-filter").value="all";$("skill-filter").value="all";
  generation++;pending=false;quotaPending=false;accountsPending=false;skillsPending=false;$("content").hidden=true;closeAccountForm();skillDetailVersion++;$("skill-detail").hidden=true;
  for(const id of ["account-list","skill-list","member-list","task-list","invite-result","feishu-result","organization-message","account-saved","skill-message","quota","models","bars","task-detail-prompt","task-detail-result","task-detail-meta","skill-instructions"])$(id).replaceChildren();
  $("skill-import").reset();$("skill-type").onchange();
}
async function useSession(session,preferred) {
  clearTenantView();credential=session.token;currentTenant=preferred||session.tenants[0]?.id||"";
  await loadIdentity();await refresh();
}
$("login-mode").onchange=()=>{
  const mode=$("login-mode").value;$("invite-code-label").hidden=mode!=="register";$("invite-code").required=mode==="register";
  $("bootstrap-token-label").hidden=mode!=="bootstrap";$("token").required=mode==="bootstrap";
  $("password").minLength=mode==="login"?1:12;text("login-submit",mode==="login"?"登录":mode==="register"?"注册并加入":"创建管理员");
};
$("login").addEventListener("submit",async event=>{
  event.preventDefault();$("login-submit").disabled=true;
  try {
    const mode=$("login-mode").value;
    const session=await identityRequest(`/api/auth/${mode}`,{username:$("username").value,password:$("password").value,code:$("invite-code").value},mode==="bootstrap"?{Authorization:`Bearer ${$("token").value.trim()}`} : {});
    $("password").value="";$("token").value="";$("invite-code").value="";await useSession(session);
  } catch(error){text("login-note",error.message);}
  finally {$("login-submit").disabled=false;}
});
$("tenant-select").onchange=async()=>{
  clearTenantView();currentTenant=$("tenant-select").value;
  try {await loadIdentity();await refresh();}catch(error){text("error",error.message);$("error").hidden=false;$("login").hidden=false;}
};
async function organizationAction(action) {
  const version=generation;
  try {await action();}catch(error){if(version===generation)text("organization-message",error.message);}
}
$("invite-form").onsubmit=event=>{event.preventDefault();organizationAction(async()=>{
  const version=generation;const result=await identityRequest("/api/organization/invitations",{role:$("invite-role").value});
  if(version===generation)text("invite-result",`邀请码：${result.code}（72 小时有效，仅可使用一次，请私下发给成员）`);
});};
$("join-form").onsubmit=event=>{event.preventDefault();organizationAction(async()=>{const result=await identityRequest("/api/auth/accept",{code:$("join-code").value});$("join-code").value="";await useSession(result,currentTenant);});};
$("tenant-form").onsubmit=event=>{event.preventDefault();organizationAction(async()=>{const result=await identityRequest("/api/organizations",{name:$("tenant-name").value,id:$("tenant-id").value});clearTenantView();currentTenant=result.id;await loadIdentity();await refresh();$("tenant-form").reset();});};
$("feishu-form").onsubmit=event=>{event.preventDefault();organizationAction(async()=>{
  const version=generation;const result=await identityRequest("/api/organization/feishu",{appId:$("feishu-app").value});
  if(version===generation)text("feishu-result",`FEISHU_TENANT_KEY=${result.tenantId}\nFEISHU_APP_ID=${result.appId}\nBUNBIRD_BRIDGE_TOKEN=${result.bridgeToken}\n请另外配置该应用的 FEISHU_APP_SECRET。连接凭据仅本次显示。`);
});};
fetch("/api/auth/status").then(r=>r.json()).then(status=>{if(!status.initialized){$("login-mode").value="bootstrap";$("login-mode").onchange();text("login-note","首次使用：用本机管理员凭据验证后创建登录账号，原有数据会保留。");}}).catch(()=>{});

followNavigationHash();
