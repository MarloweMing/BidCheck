import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = await fs.readFile('backend/src/main/resources/static/app.js', 'utf8');
const element = {addEventListener() {}};
const context = vm.createContext({
  console, Map, Set,
  window: {},
  document: {getElementById() { return element; }, addEventListener() {}},
  fetch() { throw new Error('offline fixture must not use fetch'); },
});
vm.runInContext(source.replace(/init\(\);\s*$/, '') + `
globalThis.calls=[];globalThis.notices=[];globalThis.paints=0;globalThis.nextMapping=null;
api=async(url,request)=>{calls.push({url,request});return nextMapping;};
render=()=>{paints++;};toast=(message)=>notices.push(message);
globalThis.qa={state,renderRequirementMappingSummary,
  set(mapping){state.project={project:{id:'p',activeTenderId:'t'},documents:[{id:'t',projectId:'p',role:'TENDER',name:'招标文件.pdf',pageCount:2}],checkpoints:[],runs:[],requirementMapping:mapping};},
  response(value){nextMapping=value;},
  refresh(){return handleAction('refresh-requirement-mapping',{dataset:{}});}};`, context);

const checks = [];
const check = async (name, run) => {
  try { await run(); checks.push({name, pass: true}); }
  catch (error) { checks.push({name, pass: false, error: String(error)}); }
};
const fixture = {
  status: 'READY', executionState: 'DRAFT_ONLY', affectsBusinessConclusion: false,
  summary: {checkingFragments: 16, mapped: 9, needsConfirmation: 3, externalRequired: 2, unsupported: 2, nonChecking: 5},
  reviewItemCount: 7, reviewItemsTruncated: false,
  reviewItems: [
    {status: 'NEEDS_CONFIRMATION', locationType: 'PAGE', location: 3, text: '<script>待确认</script>', explanation: '参数 <需核对>'},
    {status: 'EXTERNAL_REQUIRED', locationType: 'PAGE', location: 8, text: '信用网站查询', explanation: '需要外部平台'},
  ],
};

context.qa.set(fixture);
await check('summary exposes only the four decision-relevant counters', () => {
  const html = context.qa.renderRequirementMappingSummary();
  assert.equal((html.match(/<div><span>/g) || []).length, 4);
  for (const label of ['已映射', '待确认', '需外部核验', '暂不支持']) assert.ok(html.includes(label));
  assert.ok(!html.includes('<span>非检查内容</span>'));
});
await check('review items are collapsed by default and the draft boundary is explicit', () => {
  const html = context.qa.renderRequirementMappingSummary();
  assert.match(html, /<details class="requirement-mapping-details"[^>]*>/);
  assert.doesNotMatch(html, /<details class="requirement-mapping-details"[^>]*\sopen(?:\s|>)/);
  assert.ok(html.includes('不会自动进入检查或改变业务结论'));
});
await check('source text and explanations are HTML escaped', () => {
  const html = context.qa.renderRequirementMappingSummary();
  assert.ok(html.includes('&lt;script&gt;待确认&lt;/script&gt;'));
  assert.ok(html.includes('参数 &lt;需核对&gt;'));
  assert.ok(!html.includes('<script>待确认</script>'));
});
await check('refresh calls the project mapping endpoint and replaces only the mapping view', async () => {
  const refreshed = structuredClone(fixture); refreshed.summary.mapped = 11;
  context.qa.response(refreshed);
  await context.qa.refresh();
  assert.equal(context.calls.length, 1);
  assert.equal(context.calls[0].url, '/api/projects/p/requirement-mapping/refresh');
  assert.equal(context.calls[0].request.method, 'POST');
  assert.equal(context.calls[0].request.body.documentId, 't');
  assert.equal(context.qa.state.project.requirementMapping.summary.mapped, 11);
  assert.ok(context.notices.at(-1).includes('重新整理'));
});

const result = {status: checks.every(item => item.pass) ? 'PASSED' : 'FAILED',
  passed: checks.filter(item => item.pass).length, failed: checks.filter(item => !item.pass).length,
  checks, externalRequests: 0, businessWrites: 0};
console.log(JSON.stringify(result, null, 2));
if (result.failed) process.exitCode = 1;
