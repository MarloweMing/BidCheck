import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = await fs.readFile('backend/src/main/resources/static/app.js', 'utf8');
const listeners = {};
const app = {addEventListener() {}, querySelectorAll() { return []; }};
const modal = {open: false, className: '', innerHTML: '', addEventListener() {}, showModal() { this.open = true; }, close() { this.open = false; }};
class FakeFormData {
  constructor(form) { this.values = form.values || {}; }
  get(key) { return this.values[key] ?? null; }
  has(key) { return Object.hasOwn(this.values, key); }
}
const context = vm.createContext({
  console, Map, Set, FormData: FakeFormData,
  window: {setTimeout() {}, clearTimeout() {}, matchMedia() { return {matches: false}; }},
  document: {
    getElementById(id) { return id === 'app' ? app : id === 'modal' ? modal : null; },
    addEventListener(type, listener) { listeners[type] = listener; },
    querySelector() { return null; },
  },
  fetch() { throw new Error('offline fixture must not use fetch'); },
});
vm.runInContext(source.replace(/init\(\);\s*$/, '') + `
globalThis.calls=[];globalThis.notices=[];globalThis.reloads=0;
api=async(url,request)=>{calls.push({url,request});
  if(url.endsWith('/dependency-graph'))return {revision:3,reverseNeedIndex:{'spec-1:price':['cp-1']},nodes:[{type:'EVIDENCE_NEED',attributes:{needKey:'spec-1:price',kind:'PRICE'}}]};
  if(url.endsWith('/dependency-recalculations/plan'))return {sourceResultDigest:'source-digest',affectedCheckpointIds:['cp-1'],noOp:false};
  if(url.endsWith('/dependency-recalculations'))return {changedCheckpointCount:1};
  if(url.includes('/result-versions/'))return {revision:2,publishedAt:'2026-10-19T13:00:00Z',publishedBy:'发布审核员',publicationNote:'证据已确认',results:[]};
  return {decision:{publicationState:'PUBLISHED'}};};
loadRun=async()=>{reloads++;};render=()=>{};toast=(message)=>notices.push(message);
globalThis.qa={state,modal,renderIncrementalCandidates,handleAction,showIncrementalResultVersion,
  set(record){state.run={run:{id:'run-1'},results:[]};state.incrementalCandidates=[record];state.incrementalCandidateError='';},
  action(name,data){return handleAction(name,{dataset:data});}};
`, context);

const record = {
  id: 'candidate-1', publicationState: 'CANDIDATE_ONLY', decisionRevision: 0,
  sourceResultDigest: 'source-digest', evidenceSnapshotDigest: 'evidence-digest',
  changedCheckpointCount: 1, recalculatedCheckpointCount: 2, createdAt: '2026-10-19T12:00:00Z',
  results: [{checkpointId: 'cp-1', evaluationChanged: true,
    beforeFinding: {evaluation: {status: 'UNDETERMINED'}},
    candidateFinding: {checkpoint: {title: '<报价复核>'}, evaluation: {status: 'PASS'}}}],
};

const checks = [];
const check = async (name, run) => {
  try { await run(); checks.push({name, pass: true}); }
  catch (error) { checks.push({name, pass: false, error: String(error)}); }
};

context.qa.set(record);
await check('pending candidate shows key changes and immutable-history boundary', () => {
  const html = context.qa.renderIncrementalCandidates();
  assert.ok(html.includes('待审核 1'));
  assert.ok(html.includes('旧报告保持不变'));
  assert.ok(html.includes('审核并发布'));
  assert.ok(html.includes('&lt;报价复核&gt;'));
  assert.ok(!html.includes('<报价复核>'));
  assert.ok(!html.includes('<strong>审核人</strong>'));
});
await check('review modal binds the candidate snapshots', async () => {
  await context.qa.action('incremental-decision', {id: 'candidate-1', decision: 'APPROVE'});
  assert.ok(context.qa.modal.open);
  assert.ok(context.qa.modal.innerHTML.includes('data-source-digest="source-digest"'));
  assert.ok(context.qa.modal.innerHTML.includes('data-evidence-digest="evidence-digest"'));
  assert.ok(context.qa.modal.innerHTML.includes('生成新的不可变结果版本'));
});
await check('approval submits reviewer, note, revision and both expected digests', async () => {
  const button = {disabled: false};
  const form = {id: 'incremental-decision-form', dataset: {run: 'run-1', record: 'candidate-1', decision: 'APPROVE', revision: '0', sourceDigest: 'source-digest', evidenceDigest: 'evidence-digest'}, values: {reviewer: '审核员', note: '证据已核对'}, reportValidity() { return true; }, querySelector() { return button; }};
  await listeners.submit({target: form, preventDefault() {}});
  const call = context.calls.at(-1);
  assert.equal(call.url, '/api/runs/run-1/dependency-recalculations/candidate-1/decision');
  assert.equal(call.request.body.decision, 'APPROVE');
  assert.equal(call.request.body.expectedDecisionRevision, 0);
  assert.equal(call.request.body.expectedSourceResultDigest, 'source-digest');
  assert.equal(call.request.body.expectedEvidenceSnapshotDigest, 'evidence-digest');
  assert.equal(context.reloads, 1);
});
await check('candidate creation uses graph-owned keys and plan before recalculation', async () => {
  context.qa.set(record);
  await context.qa.action('create-incremental-candidate', {});
  assert.ok(context.qa.modal.innerHTML.includes('value="spec-1:price"'));
  const button = {disabled: false};
  const form = {id: 'incremental-recalculation-form', dataset: {run: 'run-1', revision: '3'}, values: {}, reportValidity() { return true; }, querySelector() { return button; }, querySelectorAll() { return [{value: 'spec-1:price'}]; }};
  await listeners.submit({target: form, preventDefault() {}});
  const planIndex = context.calls.findIndex(call => call.url.endsWith('/dependency-recalculations/plan'));
  const recalcIndex = context.calls.findIndex(call => call.url.endsWith('/dependency-recalculations'));
  assert.ok(planIndex >= 0 && recalcIndex > planIndex);
  assert.deepEqual(Array.from(context.calls[recalcIndex].request.body.changedNeedKeys), ['spec-1:price']);
  assert.equal(context.calls[recalcIndex].request.body.expectedSourceResultDigest, 'source-digest');
});
await check('published version viewer uses immutable publication fields', async () => {
  context.qa.set(record);
  await context.qa.showIncrementalResultVersion('version-2');
  assert.ok(context.qa.modal.innerHTML.includes('发布审核员'));
  assert.ok(context.qa.modal.innerHTML.includes('证据已确认'));
  assert.ok(!context.qa.modal.innerHTML.includes('未记录'));
});

const result = {status: checks.every(item => item.pass) ? 'PASSED' : 'FAILED',
  passed: checks.filter(item => item.pass).length, failed: checks.filter(item => !item.pass).length,
  checks, externalRequests: 0};
console.log(JSON.stringify(result, null, 2));
if (result.failed) process.exitCode = 1;
