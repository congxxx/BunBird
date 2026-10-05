// Run against an isolated, empty mock Control database, never the real service.
// NODE_PATH must expose Playwright. BUNBIRD_TEST_BROWSER optionally selects Chromium.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.BUNBIRD_TEST_BROWSER?{executablePath:process.env.BUNBIRD_TEST_BROWSER}:{channel:'msedge'})});
 try {
  const page=await browser.newPage({viewport:{width:1440,height:1050}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:18081/dashboard.html');
  await page.waitForFunction(()=>document.querySelector('#login-mode').value==='bootstrap');
  await page.locator('#username').fill('test-owner');await page.locator('#password').fill('synthetic-owner-password');
  await page.locator('#token').fill('admin-ui-test-012345678901234567890');await page.locator('#login-submit').click();
  await page.locator('#content').waitFor({state:'visible'});
  await page.locator('a[href="#organization"]').click();
  await page.locator('#tenant-name').fill('产品团队');await page.locator('#tenant-id').fill('test-team-b');
  await page.locator('#tenant-form button').click();
  await page.waitForFunction(()=>document.querySelector('#tenant-select').value==='test-team-b'&&!document.querySelector('#content').hidden);
  await page.waitForFunction(()=>document.querySelector('#account-list').textContent.includes('已登记 0 个账号'));
  await page.locator('#invite-form button').click();
  await page.waitForFunction(()=>document.querySelector('#invite-result').textContent.includes('邀请码：'));
  const code=(await page.locator('#invite-result').textContent()).match(/邀请码：([^（]+)/)[1];
  const member=await browser.newPage({viewport:{width:1440,height:1050}});member.on('pageerror',e=>errors.push(e.message));
  await member.goto('http://127.0.0.1:18081/dashboard.html');
  await member.locator('#login-mode').selectOption('register');await member.locator('#username').fill('test-member');await member.locator('#password').fill('synthetic-member-password');await member.locator('#invite-code').fill(code);await member.locator('#login-submit').click();
  await member.locator('#content').waitFor({state:'visible'});
  assert.equal(await member.locator('#tenant-select option').count(),1);
  assert.equal(await member.locator('#account-add').isVisible(),false);
  assert.equal(await member.locator('#skill-import').isVisible(),false);
  assert.equal(await member.locator('#tenant-form').isVisible(),false);
  assert.match(await member.locator('#tenant-role').textContent(),/只读/);
  await page.locator('#tenant-select').selectOption('ui-test');
  await page.waitForFunction(()=>document.querySelector('#account-list').textContent.includes('当前环境账号'));
  // Simulate a slow old-tenant response arriving after the user switches.
  let release,completed;const fulfilled=new Promise(r=>{completed=r;});
  await page.route('**/api/dashboard?*',async route=>{
    if(route.request().headers()['x-tenant-id']==='ui-test'){
      const response=await route.fetch();await new Promise(r=>{release=r;});await route.fulfill({response});completed();
    }else await route.continue();
  });
  await page.locator('#refresh').click();
  while(!release)await new Promise(r=>setTimeout(r,25));
  await page.locator('#tenant-select').selectOption('test-team-b');
  await page.waitForFunction(()=>document.querySelector('#account-list').textContent.includes('已登记 0 个账号'));
  release();await fulfilled;await page.unroute('**/api/dashboard?*');
  assert.equal(await page.locator('#account-list').getByText('当前环境账号',{exact:true}).count(),0);
  await page.locator('a[href="#organization"]').click();
  await page.locator('#member-list').getByRole('button',{name:'移除'}).click();
  await page.waitForFunction(()=>!document.querySelector('#member-list').textContent.includes('test-member'));
  await member.locator('#refresh').click();await member.locator('#error').waitFor({state:'visible'});
  fs.mkdirSync('output/playwright',{recursive:true});
  await page.screenshot({path:'output/playwright/tenants.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);
  await page.locator('#logout').click();await page.locator('#login').waitFor({state:'visible'});
  assert.deepEqual(errors,[]);
  console.log('PASS: bootstrap, create/switch tenant, invite/register member, readonly controls, stale response isolation, revoke membership, mobile layout, logout');
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
