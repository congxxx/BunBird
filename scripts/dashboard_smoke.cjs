// Run with Playwright available in NODE_PATH. All API responses below are synthetic.
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const base = path.resolve(__dirname, '../backend/src/main/resources/static');
(async () => {
  const browser = await chromium.launch({headless:true,...(process.env.BUNBIRD_TEST_BROWSER?{executablePath:process.env.BUNBIRD_TEST_BROWSER}:{channel:'msedge'})});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1100}});
    const errors=[]; page.on('pageerror', e=>errors.push(e.message));
    const usage={input:12000,output:3000,cacheRead:6000,cacheWrite:1000,total:22000};
    let requestedDays;
    await page.route('http://bunbird.test/**', async route => {
      const url=new URL(route.request().url());
      if(url.pathname==='/api/auth/status')return route.fulfill({json:{initialized:true}});
      if(url.pathname==='/api/auth/login')return route.request().postDataJSON().password==='synthetic-password'?route.fulfill({json:{token:'synthetic-admin',tenants:[{id:'test',name:'Test',role:'ADMIN'}]}}):route.fulfill({status:401,json:{}});
      if(url.pathname==='/api/auth/me')return route.fulfill({json:{userId:'test-user',tenantId:'test',role:'ADMIN',platformAdmin:true,tenants:[{id:'test',name:'Test',role:'ADMIN'}]}});
      if(url.pathname==='/api/auth/logout')return route.fulfill({json:{loggedOut:true}});
      if(url.pathname==='/api/organization/members'||url.pathname==='/api/skills')return route.fulfill({json:[]});
      if(url.pathname==='/api/accounts')return route.fulfill({json:{accounts:[],switches:[],configuredCount:0,executor:'claude'}});
      if(url.pathname==='/api/quota') return route.fulfill({json:{status:'AVAILABLE',plan:'Token Plan',checkedAt:Date.now(),windows:[{model:'general',kind:'interval',remainingPercent:83,startsAt:1789905600000,resetsAt:1789920000000},{model:'general',kind:'weekly',remainingPercent:97}]}});
      if(url.pathname==='/api/dashboard') {
        if(route.request().headers().authorization!=='Bearer synthetic-admin')
          return route.fulfill({status:401,json:{error:'unauthorized'}});
        requestedDays=url.searchParams.get('days');
        return route.fulfill({json:{days:30,tasks:4,reportedTasks:3,usage,
          statuses:{SUCCEEDED:3,CANCELLED:1},generatedAt:Date.now(),
          daily:Array.from({length:30},(_,i)=>({date:`2026-09-${String(i+1).padStart(2,'0')}`,usage:{...usage,total:i%5*4200}})),
          models:[{model:'MiniMax-M3',usage}],
          accounts:[{name:'当前环境配置',model:'MiniMax-M3',executor:'claude'}],
          recent:[{id:'synthetic-task',status:'SUCCEEDED',model:'<img src=x onerror=alert(1)>',created_at:Date.now(),usage},
            {id:'missing-usage',status:'WAITING_QUOTA',pause_reason:'QUOTA_WEEKLY_EXHAUSTED',resume_at:Date.now()+3600000,created_at:Date.now()}]}});
      }
      const filename=path.basename(url.pathname);
      if(!['dashboard.html','dashboard.css','dashboard.js'].includes(filename))return route.fulfill({status:404});
      return route.fulfill({body:fs.readFileSync(path.join(base,filename)),
        contentType:filename.endsWith('.html')?'text/html; charset=utf-8':filename.endsWith('.css')?'text/css':'text/javascript; charset=utf-8'});
    });
    await page.goto('http://bunbird.test/dashboard.html');
    await page.locator('#username').fill('test-user');await page.locator('#password').fill('wrong'); await page.locator('#login button').click();
    await page.waitForFunction(()=>document.querySelector('#login-note').textContent.length>0);
    await page.locator('#password').fill('synthetic-password'); await page.locator('#login button').click();
    await page.locator('#content').waitFor({state:'visible'});
    assert.equal(await page.locator('#total').textContent(),'22,000');
    await page.locator('aside a[href="#accounts"]').click();
    await page.locator('#accounts').waitFor({state:'visible'});
    assert.equal(await page.locator('#overview').isVisible(),false);
    await page.getByText('剩余 83%',{exact:true}).waitFor();
    assert.match(await page.locator('#quota').textContent(),/实际窗口 4 小时/);
    await page.locator('aside a[href="#tasks"]').click();
    await page.locator('#tasks').waitFor({state:'visible'});
    assert.equal(await page.locator('#accounts').isVisible(),false);
    assert.equal(await page.locator('#task-list img').count(),0);
    assert.match(await page.locator('#task-list').textContent(),/未上报/);
    assert.match(await page.locator('#task-list').textContent(),/周额度已耗尽/);
    assert.match(await page.locator('#task-list').textContent(),/恢复（北京时间）/);
    await page.locator('#days').selectOption('7');
    await page.waitForFunction(()=>!document.querySelector('#refresh').disabled);
    assert.equal(requestedDays,'7');
    assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);
    fs.mkdirSync(path.resolve(__dirname,'../data'),{recursive:true});
    await page.screenshot({path:path.resolve(__dirname,'../data/dashboard-preview.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.locator('#logout').click();
    assert.equal(await page.locator('#login').isVisible(),true);
    assert.deepEqual(errors,[]);
    console.log('PASS: login, auth failure, usage rendering, date filter, XSS escaping, mobile layout, logout');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
