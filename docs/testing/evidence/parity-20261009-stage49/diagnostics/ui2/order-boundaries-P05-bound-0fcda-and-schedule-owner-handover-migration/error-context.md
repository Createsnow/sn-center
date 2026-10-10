# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-boundaries.spec.ts >> P05 boundaries: Unicode blank bill, three-language validation and schedule owner handover
- Location: e2e\parity\order-boundaries.spec.ts:14:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('请输入单据编号', { exact: true })
Expected: visible
Error: strict mode violation: getByText('请输入单据编号', { exact: true }) resolved to 7 elements:
    1) <td title="请输入单据编号" class="ant-table-cell ant-table-cell-ellipsis ant-table-cell-row-hover">请输入单据编号</td> aka getByRole('cell', { name: '请输入单据编号' }).first()
    2) <span>请输入单据编号</span> aka locator('span').filter({ hasText: '请输入单据编号' })
    3) <td title="请输入单据编号" class="ant-table-cell ant-table-cell-ellipsis">请输入单据编号</td> aka getByRole('cell', { name: '请输入单据编号' }).nth(3)
    4) <td title="请输入单据编号" class="ant-table-cell ant-table-cell-ellipsis">请输入单据编号</td> aka getByRole('cell', { name: '请输入单据编号' }).nth(4)
    5) <td title="请输入单据编号" class="ant-table-cell ant-table-cell-ellipsis">请输入单据编号</td> aka getByRole('cell', { name: '请输入单据编号' }).nth(5)
    6) <td title="请输入单据编号" class="ant-table-cell ant-table-cell-ellipsis">请输入单据编号</td> aka getByRole('cell', { name: '请输入单据编号', description: '请输入单据编号' }).nth(4)
    7) <td title="请输入单据编号" class="ant-table-cell ant-table-cell-ellipsis">请输入单据编号</td> aka getByRole('cell', { name: '请输入单据编号', description: '请输入单据编号' }).nth(5)

Call log:
  - Expect "toBeVisible" getByText('请输入单据编号', { exact: true }) with timeout 15000ms
  - waiting for getByText('请输入单据编号', { exact: true })

```

# Test source

```ts
  22  |   const fixture=(action:string,run=tag)=>JSON.parse(execFileSync(resolve('../../sn-center-python/.venv/Scripts/python.exe'),['-X','utf8',resolve('../tools/testing/parity-order-audit-fixture.py'),kind,action,run],{windowsHide:true,encoding:'utf8'}));
  23  |   fixture('prepare');fixture('prepare',otherTag);
  24  |   const password=readFileSync(resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),'utf8').trim();
  25  |   protectTestSecrets(password);
  26  |   const url=prototype?'http://127.0.0.1:8001':'http://127.0.0.1:15174';
  27  |   const otherContext=await browser.newContext({viewport:{width:1440,height:960}});
  28  |   const other=await otherContext.newPage();
  29  |   const pageErrors:string[]=[];
  30  |   for(const tab of [page,other])tab.on('pageerror',e=>pageErrors.push(e.message));
  31  |   const states:unknown[]=[];
  32  |   const writes:unknown[]=[];
  33  |   const postPaths:string[]=[];
  34  |   page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname.endsWith('/orders/sync'))postPaths.push(r.url());});
  35  |   const login=async(tab:Page,name:string)=>{
  36  |     await tab.goto(url+'/login');
  37  |     await tab.locator('input[autocomplete="username"]').fill(name);
  38  |     await tab.locator('input[autocomplete="current-password"]').fill(password);
  39  |     await tab.getByRole('button',{name:zh.login.submit,exact:true}).click();
  40  |     await tab.getByRole('menuitem',{name:zh.nav.orders,exact:true}).click();
  41  |     await tab.waitForLoadState('networkidle');
  42  |   };
  43  |   const language=async(label:string)=>{
  44  |     const control=page.locator(prototype?'.header .lang':'.prototype-language .ant-select');
  45  |     if((await control.innerText()).includes(label))return;
  46  |     const saved=page.waitForResponse(r=>r.request().method()===(prototype?'PUT':'POST')&&new URL(r.url()).pathname.endsWith(prototype?'/auth/lang':'/system/auth/language'));
  47  |     await control.click();await page.getByRole('option',{name:label,exact:true}).click();expect((await saved).ok()).toBe(true);
  48  |   };
  49  |   const capture=async(label:string,tab=page)=>{
  50  |     await tab.mouse.move(0,0);
  51  |     await expect(tab.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  52  |     states.push({label,body:await tab.locator('main').innerText(),messages:await tab.locator(prototype?'.el-message':'.ant-message-notice').allTextContents(),database:fixture('snapshot')});
  53  |     await tab.screenshot({path:info.outputPath(label+'.png'),animations:'disabled'});
  54  |   };
  55  |   const saveSchedule=async(tab:Page,enabled:boolean,minutes:number)=>{
  56  |     await tab.getByRole('button',{name:zh.orders.sched.settings,exact:true}).click();
  57  |     const dialog=tab.getByRole('dialog');
  58  |     await dialog.locator(prototype?'.el-select':'.ant-select').click();
  59  |     await tab.getByRole('option',{name:zh.orders.sched.unit.minute,exact:true}).click();
  60  |     await dialog.getByRole('spinbutton').fill(String(minutes));
  61  |     if(await dialog.getByRole('switch').getAttribute('aria-checked')!==String(enabled))await dialog.locator(prototype?'.el-switch':'.ant-switch').click();
  62  |     const response=tab.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname.endsWith('/orders/sync-schedule'));
  63  |     await dialog.getByRole('button',{name:zh.common.save,exact:true}).click();
  64  |     const result=await response;expect(result.ok()).toBe(true);const body=await result.json();await expect(dialog).toBeHidden();return prototype?body:body.data;
  65  |   };
  66  |   try {
  67  |     await login(page,username);
  68  |     const ordersBefore=fixture('snapshot').orders;
  69  |     const erpBefore=(await (await request.get('http://127.0.0.1:19091')).json()).queryRequests;
  70  |     for(const [locale,label,text,errors] of [['zh-CN','简体中文',zh,errZh],['en','English',en,errEn],['vi','Tiếng Việt',vi,errVi]] as const){
  71  |       await language(label);
  72  |       const input=page.getByPlaceholder(text.orders.billToSync,{exact:true});
  73  |       const button=page.getByRole('button',{name:text.orders.syncOne,exact:true});
  74  |       await input.fill('   ');await expect(button).toBeDisabled();const before=postPaths.length;await input.press('Enter');await page.waitForLoadState('networkidle');expect(postPaths).toHaveLength(before);
  75  |       await capture(locale+'-blank-disabled');
  76  |       for(const [suffix,value] of [['nel','\u0085'],['separator','\u001c']] as const){
  77  |         await input.fill(value);await expect(button).toBeEnabled();
  78  |         const pending=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/orders/sync'));
  79  |         await button.click();const response=await pending;expect(response.status()).toBe(400);writes.push({locale,suffix,body:await response.json()});
  80  |         const message=page.locator(prototype?'.el-message--error':'.sn-prototype-error-message');
  81  |         await expect(message).toHaveText(errors.ORDER_BILL_REQUIRED);
  82  |         expect(fixture('snapshot').orders).toEqual(ordersBefore);
  83  |         expect((await (await request.get('http://127.0.0.1:19091')).json()).queryRequests).toBe(erpBefore);
  84  |         await capture(locale+'-'+suffix);
  85  |         await message.locator(prototype?'.el-message__closeBtn':'button').click();
  86  |         await expect(message).toHaveCount(0);
  87  |       }
  88  |       await page.getByRole('button',{name:text.orders.sched.settings,exact:true}).click();
  89  |       const dialog=page.getByRole('dialog');
  90  |       await dialog.locator(prototype?'.el-select':'.ant-select').click();
  91  |       await page.getByRole('option',{name:text.orders.sched.unit.minute,exact:true}).click();
  92  |       await dialog.getByRole('spinbutton').fill('9');
  93  |       const beforeSettings=fixture('snapshot').schedule;
  94  |       await dialog.getByRole('button',{name:text.common.save,exact:true}).click();
  95  |       const intervalMessage=page.locator(prototype?'.el-message--error':'.ant-message-notice-error');
  96  |       await expect(intervalMessage).toHaveText(text.orders.sched.intervalHint.replace('{{min}}','10'));
  97  |       await expect(dialog).toBeVisible();expect(fixture('snapshot').schedule).toEqual(beforeSettings);
  98  |       await capture(locale+'-interval-nine');
  99  |       await dialog.getByRole('button',{name:text.common.cancel,exact:true}).click();
  100 |       await expect(intervalMessage).toHaveCount(0);
  101 |     }
  102 |     await language('简体中文');
  103 |     const initial=await saveSchedule(page,true,60);
  104 |     await capture('schedule-owner-a');
  105 |     await login(other,otherName);
  106 |     const changed=await saveSchedule(other,true,60);
  107 |     expect(changed[prototype?'next_run_at':'nextRunAt']).toBe(initial[prototype?'next_run_at':'nextRunAt']);
  108 |     expect(changed[prototype?'updated_by':'ownerName']).toBe(otherName);
  109 |     await capture('schedule-owner-b',other);
  110 |     await saveSchedule(other,false,1440);
  111 |     await page.getByRole('menuitem',{name:zh.nav.audit,exact:true}).click();
  112 |     await page.getByPlaceholder(zh.audit.operator,{exact:true}).fill(username);
  113 |     await page.locator(prototype?'.toolbar .el-select':'.sn-toolbar .ant-select').nth(0).click();
  114 |     await page.getByRole('option',{name:zh.actions.ORDER_SYNC,exact:true}).click();
  115 |     const pending=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname===(prototype?'/api/audits':'/api/v1/sn/audits/page')&&new URL(r.url()).searchParams.get('operator')===username);
  116 |     await page.getByRole('button',{name:zh.common.search,exact:true}).click();const body=await(await pending).json();const facts=prototype?body.items:body.data.items;
  117 |     expect(facts).toHaveLength(6);
  118 |     for(const fact of facts){expect(fact[prototype?'error_msg':'errorMessage']).toBe(errZh.ORDER_BILL_REQUIRED);expect(fact.detail).toBe('POST /api/orders/sync');expect(fact[prototype?'qty':'quantity']).toBeNull();}
  119 |     await capture('audit-six-rejections');
  120 |     const rows=page.locator(prototype?'.el-table__row':'.ant-table-row');
  121 |     await rows.first().locator(prototype?'.el-table__expand-icon':'.sn-audit-expand').click();
> 122 |     await expect(page.getByText(errZh.ORDER_BILL_REQUIRED,{exact:true})).toBeVisible();
      |                                                                          ^ Error: expect(locator).toBeVisible() failed
  123 |     await capture('audit-rejection-detail');
  124 |     expect(pageErrors).toEqual([]);
  125 |   } finally {
  126 |     await otherContext.close();
  127 |     writeFileSync(info.outputPath('states.json'),JSON.stringify(states,null,2));
  128 |     writeFileSync(info.outputPath('writes.json'),JSON.stringify({writes,pageErrors},null,2));
  129 |   }
  130 | });
  131 | 
```