import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

const source = await fs.readFile('backend/src/main/resources/static/app.js', 'utf8');
const checks = [];
const check = async (name, run) => {try {await run(); checks.push({name, pass:true});} catch(error) {checks.push({name, pass:false, error:String(error)});}};
const item = retryAt => ({id:'f',checkpoint:{id:'cp',category:'资格检查'},evaluation:{status:'UNDETERMINED',evidences:[]},assessment:{resolutionJob:{id:'job',status:'FAILED',retryNotBeforeEpochMillis:retryAt}}});
const report = (id, retryAt=0, canRecover=false) => ({run:{id,status:'COMPLETED'},results:retryAt?[item(retryAt)]:[],resolutionSummary:{version:'check-resolution-1',canRecover},reviews:[]});
function harness() {
  let now=10000, nextTimer=0, network=0;
  const timers=new Map(), element={addEventListener(){}};
  class Clock extends Date {static now(){return now;}}
  const context=vm.createContext({console,Map,Set,Date:Clock,window:{setTimeout(fn,delay){const id=++nextTimer;timers.set(id,{fn,delay});return id;},clearTimeout(id){timers.delete(id);}},document:{getElementById(){return element;},addEventListener(){}},fetch(){network++;throw Error('External network forbidden');}});
  vm.runInContext(source.replace(/init\(\);\s*$/,'')+`\n
    globalThis.calls=[];globalThis.responses=[];globalThis.paints=0;globalThis.notices=[];
    api=async(url,request)=>{calls.push({url,request});const response=responses.shift();if(response instanceof Error)throw response;return await response;};
    render=()=>{paints++;};toast=(message)=>notices.push(message);
    globalThis.qa={state,pollRun,loadRun,scheduleResolutionRefresh,isRunning,renderResolutionOverview,
      set(selected,runs){state.project={project:{id:'p'},runs};state.run=selected;state.selectedRunId=selected.run.id;state.tab='results';},
      reset(){resolutionRefreshes.clear();}};`,context);
  return {context,q:context.qa,timers,advance(ms){now+=ms;},network:()=>network,async fire(){const [id,timer]=timers.entries().next().value;timers.delete(id);return await timer.fn();}};
}

await check('background child completion releases button without replacing selected historical report',async()=>{
  const h=harness(),old=report('old',0,true),child=report('child');h.q.set(old,[old.run,{id:'child',status:'RUNNING'}]);
  h.context.responses.push(child);await h.q.pollRun('child','p');
  assert.equal(h.q.state.project.runs[1].status,'COMPLETED');assert.equal(h.q.state.run,old);assert.equal(h.q.isRunning(),false);assert.equal(h.context.paints,1);
  assert.doesNotMatch(h.q.renderResolutionOverview([],[]),/data-action="recover-run"[^>]*disabled/);
});
await check('another project cannot change current project batch states',async()=>{
  const h=harness(),old=report('old');h.q.set(old,[old.run,{id:'child',status:'RUNNING'}]);h.context.responses.push(report('child'));await h.q.pollRun('child','other');
  assert.equal(h.q.state.project.runs[1].status,'RUNNING');assert.equal(h.context.paints,0);assert.equal(h.q.state.run,old);
});
await check('loading a terminal cooldown schedules exactly one lightweight read',async()=>{
  const h=harness(),old=report('old',12000);h.q.set(old,[old.run]);h.context.responses.push(old);await h.q.loadRun('old',false);
  h.q.scheduleResolutionRefresh(old,'p');assert.equal(h.timers.size,1);assert.equal([...h.timers.values()][0].delay,2250);assert.equal(h.context.calls.length,1);
  h.advance(2250);h.context.responses.push(report('old',12000,true));await h.fire();
  assert.equal(h.context.calls.length,2);assert.equal(h.q.state.run.resolutionSummary.canRecover,true);assert.equal(h.timers.size,0);
  assert.ok(h.context.calls.every(call=>call.request===undefined));assert.equal(h.network(),0);
});
await check('background completion never enables recovery while another batch is still running',async()=>{
  const h=harness(),old=report('old',0,true);h.q.set(old,[old.run,{id:'a',status:'RUNNING'},{id:'b',status:'RUNNING'}]);h.context.responses.push(report('a'));await h.q.pollRun('a','p');
  assert.equal(h.q.isRunning(),true);assert.match(h.q.renderResolutionOverview([],[]),/data-action="recover-run"[^>]*disabled/);
});
await check('unchanged cooldown response is not polled indefinitely',async()=>{
  const h=harness(),old=report('old',12000);h.q.set(old,[old.run]);h.q.scheduleResolutionRefresh(old,'p');h.advance(2250);h.context.responses.push(old);await h.fire();
  h.q.scheduleResolutionRefresh(old,'p');assert.equal(h.context.calls.length,1);assert.equal(h.timers.size,0);assert.equal(h.q.state.run.resolutionSummary.canRecover,false);
});
await check('leaving the report before cooldown skips its read',async()=>{
  const h=harness(),old=report('old',12000);h.q.set(old,[old.run]);h.q.scheduleResolutionRefresh(old,'p');h.q.state.selectedRunId='different';await h.fire();
  assert.equal(h.context.calls.length,0);assert.equal(h.timers.size,0);
});
await check('loading another report cancels the obsolete cooldown timer',async()=>{
  const h=harness(),old=report('old',12000),other=report('other');h.q.set(old,[old.run,other.run]);h.q.scheduleResolutionRefresh(old,'p');h.context.responses.push(other);await h.q.loadRun('other',false);
  assert.equal(h.timers.size,0);assert.equal(h.q.state.run,other);
});
await check('inflight cooldown response cannot overwrite a newer selection or request',async()=>{
  const h=harness(),old=report('old',12000);h.q.set(old,[old.run]);h.q.scheduleResolutionRefresh(old,'p');let finish;h.context.responses.push(new Promise(resolve=>finish=resolve));
  const waiting=h.fire();h.q.state.runRequest++;h.q.state.run=report('new');h.q.state.selectedRunId='new';finish(report('old',12000,true));await waiting;
  assert.equal(h.q.state.run.run.id,'new');assert.equal(h.context.paints,0);
});
await check('failed cooldown read stops and offers manual refresh',async()=>{
  const h=harness(),old=report('old',12000);h.q.set(old,[old.run]);h.q.scheduleResolutionRefresh(old,'p');h.context.responses.push(Promise.reject(Error('离线')));await h.fire();
  h.q.scheduleResolutionRefresh(old,'p');assert.equal(h.context.calls.length,1);assert.equal(h.timers.size,0);assert.ok(h.context.notices[0].includes('刷新项目重试'));
});
await check('earliest valid failed-job cooldown drives the read',async()=>{
  const h=harness(),old=report('old',16000);old.results.push(item(12000),item('bad'));h.q.set(old,[old.run]);h.q.scheduleResolutionRefresh(old,'p');
  assert.equal([...h.timers.values()][0].delay,2250);
});
await check('expired cooldown gets one deferred read, never a busy loop',async()=>{
  const h=harness(),old=report('old',9000);h.q.set(old,[old.run]);h.q.scheduleResolutionRefresh(old,'p');assert.equal([...h.timers.values()][0].delay,250);
});
await check('ordinary finished, active, or already recoverable reports add no timer',async()=>{
  for(const old of [report('old'),{...report('old',12000),run:{id:'old',status:'RUNNING'}},report('old',12000,true)]) {
    const h=harness();h.q.set(old,[old.run]);h.q.scheduleResolutionRefresh(old,'p');assert.equal(h.timers.size,0);assert.equal(h.context.calls.length,0);
  }
});

const out='artifacts/resolution-recovery-20261017/frontend/refresh-fix';await fs.mkdir(out,{recursive:true});
const receipt={mode:'OFFLINE_REAL_POLL_AND_COOLDOWN_HANDLERS_WITH_IN_MEMORY_API',passed:checks.filter(c=>c.pass).length,failed:checks.filter(c=>!c.pass).length,checks,sourceSha256:createHash('sha256').update(source).digest('hex'),externalRequests:0,businessWrites:0};
await fs.writeFile(out+'/targeted-tests.json',JSON.stringify(receipt,null,2));console.log(JSON.stringify(receipt));if(receipt.failed)process.exitCode=1;
