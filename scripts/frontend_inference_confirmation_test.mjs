import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = await fs.readFile('backend/src/main/resources/static/app.js', 'utf8');
const element = {addEventListener(){}, close(){}};
let captured, returned = {candidate: {status:'CONFIRMED'}, recomputeStatus:'NOT_REQUIRED'};
class FakeFormData { constructor(form){this.values=form.values;} *[Symbol.iterator](){yield* Object.entries(this.values);} }
const context = vm.createContext({console,Map,Set,FormData:FakeFormData,window:{},document:{getElementById(){return element;},addEventListener(){}}});
vm.runInContext(source.replace(/init\(\);\s*$/, '') + '\nglobalThis.qa={state,renderInferenceConfirmations,businessPresentation,submitInferenceDecision};', context);
context.mockApi = async (path, options) => {captured={path,options};return returned;};
vm.runInContext('api=globalThis.mockApi;loadRun=async()=>{};toast=()=>{};',context);
const {state,renderInferenceConfirmations,submitInferenceDecision,businessPresentation}=context.qa;
state.run={run:{id:'run',status:'COMPLETED'}};state.selectedRunId='run';state.project={project:{id:'p'}};
const f={checkpoint:{id:'cp',title:'项目负责人',source:{quote:'应提交资格证明'}},evaluation:{status:'UNDETERMINED',evidences:[],reason:'未完成'}};
const claim={id:'claim',runId:'run',checkpointId:'cp',status:'NEEDS_CONFIRMATION',revision:2,claim:'张*超对应张某超',reasoning:'同一证书编号',risks:['姓名打码'],proposedFact:{subject:'张某超',field:'personRole',value:'项目负责人'},evidences:[{documentId:'bid',page:31,quote:'<script>证书原文</script>'}]};
const cases=[];
async function test(name,fn){try{await fn();cases.push({name,passed:true});}catch(error){cases.push({name,passed:false,error:String(error)});}}
await test('Pending card shows the claim, requirement, source and explicit decision without passing the finding',()=>{
  state.inferenceConfirmations=[claim];const before=JSON.stringify(f),old=businessPresentation(f).state,html=renderInferenceConfirmations(f);
  assert.match(html,/是，确认关联/);assert.match(html,/应提交资格证明/);assert.match(html,/查看原文/);assert.match(html,/补充材料/);assert.match(html,/确认后采用的内容/);assert.match(html,/人员岗位：项目负责人/);
  assert.ok(!html.includes('<script>'));assert.ok(html.includes('&lt;script&gt;'));assert.equal(JSON.stringify(f),before);assert.equal(businessPresentation(f).state,old);
});
await test('Claims from a different item or run are not rendered',()=>{
  state.inferenceConfirmations=[{...claim,checkpointId:'other'},{...claim,runId:'old'}];assert.equal(renderInferenceConfirmations(f),'');
});
await test('Confirmed record has reviewer/history and no confirmation buttons',()=>{
  state.inferenceConfirmations=[{...claim,status:'CONFIRMED',actor:'审核人',decidedAt:'2026-11-02T02:00:00Z',note:'已核对证号'}];const html=renderInferenceConfirmations(f);
  assert.ok(html.includes('审核人'));assert.ok(html.includes('已核对证号'));assert.ok(!html.includes('data-action="inference-decision"'));assert.ok(html.includes('/inference-confirmations/export'));
});
await test('Loading failure is visible and retryable instead of silently empty',()=>{
  state.inferenceConfirmationError='连接失败';const html=renderInferenceConfirmations(f);assert.ok(html.includes('重新加载'));assert.ok(html.includes('连接失败'));state.inferenceConfirmationError='';
});
await test('Decision posts an object once with frozen revision and never sends proposed fact overrides',async()=>{
  const form={dataset:{run:'run',record:'claim',decision:'CONFIRMED',revision:'2'},values:{reviewer:' 审核人 ',note:' 对照原文 '},querySelector(){return {disabled:false};}};
  await submitInferenceDecision(form);assert.equal(captured.path,'/api/runs/run/inference-confirmations/claim/decision');
  assert.equal(typeof captured.options.body,'object');assert.equal(captured.options.body.expectedRevision,2);assert.equal(captured.options.body.reviewer,'审核人');assert.ok(!('proposedFact' in captured.options.body));assert.equal(state.busy.size,0);
});
await test('Stale dialog cannot submit into a different selected run',async()=>{
  captured=null;await submitInferenceDecision({dataset:{run:'old',record:'claim'}});assert.equal(captured,null);
});
await test('Confirmation status stays user action unless a real technical failure remains',()=>{
  const item={...f,evaluation:{...f.evaluation,status:'NEEDS_CONFIRMATION'},assessment:{atomicCheck:{workflowState:'NEEDS_CONFIRMATION',gaps:[{code:'INFERENCE_CONFIRMATION_REQUIRED'}]}}};
  assert.equal(businessPresentation(item).state,'USER_ACTION');
  item.assessment.atomicCheck.gaps.push({code:'MODEL_REQUEST_FAILED'});
  assert.equal(businessPresentation(item).state,'SYSTEM_INCOMPLETE');
});
const result={scope:'confirmation-interface-offline',passed:cases.filter(v=>v.passed).length,failed:cases.filter(v=>!v.passed).length,cases};
const outputDirectory=process.env.BIDCHECK_TEST_OUTPUT||'artifacts/agent-plan-completion-20261102';
await fs.mkdir(outputDirectory,{recursive:true});
await fs.writeFile(`${outputDirectory}/frontend-confirmation-tests.json`,JSON.stringify(result,null,2));
console.log(JSON.stringify(result,null,2));if(result.failed)process.exitCode=1;
