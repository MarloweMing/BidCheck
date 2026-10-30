import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const source=await fs.readFile('backend/src/main/resources/static/app.js','utf8');
let calls=0;const element={addEventListener(){}};
const context=vm.createContext({console,Map,Set,window:{},document:{getElementById(){return element;},addEventListener(){}},fetch(){calls++;throw Error('Network forbidden');}});
vm.runInContext(source.replace(/init\(\);\s*$/,'')+`\nglobalThis.api={atomicPresentation,businessPresentation,renderAtomicCheck};`,context);
const {api}=context,checks=[];
const check=(name,fn)=>{try{fn();checks.push({name,pass:true});}catch(e){checks.push({name,pass:false,error:String(e)});}};
const finding=(code,status='UNDETERMINED')=>({id:'f',checkpoint:{id:'c',title:'证书查询对应',category:'资格检查',severity:'HIGH',type:'SEMANTIC_RESPONSE'},evaluation:{status,reason:'原始检查说明',suggestion:'',method:'FOCUSED_ATOMIC_RULES',evidences:[]},assessment:{ocrReview:{required:true},atomicCheck:{businessDecision:status,workflowState:'NEEDS_REVIEW',complete:false,gaps:[{code,description:'证书单位与任职单位尚未对应'}],conditions:[]}}});
for(const code of ['COMPARISON_FIELD_MAPPING_REQUIRED','COMPARISON_RECORD_IDENTITY_UNPROVEN','REGISTRATION_EMPLOYER_BINDING_REQUIRED']){
 check(`${code}: system-owned mapping, not user work`,()=>{const f=finding(code),before=JSON.stringify(f),p=api.businessPresentation(f);assert.equal(p.state,'SYSTEM_INCOMPLETE');assert.equal(p.owner,'SYSTEM');assert.equal(p.userActionRequired,false);assert.equal(p.finalStatus,'UNDETERMINED');assert.equal(p.reason,'证书单位与任职单位尚未对应');assert.ok(p.nextStep.includes('系统需继续'));assert.equal(JSON.stringify(f),before);});
 check(`${code}: exact gap and safe instruction retained`,()=>{const p=api.atomicPresentation(finding(code).assessment.atomicCheck);assert.equal(p.state,'RELATION_UNCONFIRMED');assert.equal(p.issues[0].code,code);assert.equal(p.issues[0].description,'证书单位与任职单位尚未对应');assert.ok(p.nextSteps[0].includes('不要求补交材料'));});
 check(`${code}: existing completed business result is not reclassified`,()=>{assert.equal(api.businessPresentation(finding(code,'PASS')).state,'SATISFIED');});
}
check('registration certificate not located remains evidence pending',()=>{const f=finding('REGISTRATION_CERTIFICATE_REQUIRED');assert.equal(api.atomicPresentation(f.assessment.atomicCheck).state,'EVIDENCE_NOT_FOUND');const p=api.businessPresentation(f);assert.equal(p.state,'EVIDENCE_PENDING');assert.equal(p.userActionRequired,false);});
check('stopped recovery does not promise ongoing system work',()=>{const f=finding('COMPARISON_FIELD_MAPPING_REQUIRED');f.assessment.resolutionJob={id:'job',status:'BLOCKED_NO_PROGRESS'};assert.ok(api.businessPresentation(f).nextStep.includes('已停止'));});
check('legacy cached labels cannot override known mapping gap',()=>{const f=finding('COMPARISON_FIELD_MAPPING_REQUIRED');f.assessment.businessPresentation={version:'business-result-3',state:'USER_ACTION',finalStatus:'UNDETERMINED',userActionRequired:true};f.assessment.atomicCheck.presentation={state:'NEEDS_REVIEW',reason:'旧说明',issues:[{category:'NEEDS_REVIEW'}]};assert.equal(api.businessPresentation(f).state,'SYSTEM_INCOMPLETE');assert.equal(api.atomicPresentation(f.assessment.atomicCheck).state,'RELATION_UNCONFIRMED');});
check('unknown code is not invented into a mapping success',()=>assert.equal(api.atomicPresentation(finding('FUTURE_UNKNOWN').assessment.atomicCheck).state,'NEEDS_REVIEW'));
check('processing failures retain priority over mapping gaps',()=>{const f=finding('COMPARISON_FIELD_MAPPING_REQUIRED');f.assessment.atomicCheck.workflowState='ENGINE_FAILURE';assert.equal(api.atomicPresentation(f.assessment.atomicCheck).state,'PROCESSING_FAILED');assert.equal(api.businessPresentation(f).reason,'原始检查说明');});
check('network calls remain zero',()=>assert.equal(calls,0));
const out={status:checks.every(x=>x.pass)?'PASSED':'FAILED',passed:checks.filter(x=>x.pass).length,failed:checks.filter(x=>!x.pass).length,checks,networkCalls:calls,sourceSha256:createHash('sha256').update(source).digest('hex')};
const dir=process.argv[2]||'artifacts/requirement-repair-20261018/comparison-ui-tests';await fs.mkdir(dir,{recursive:true});await fs.writeFile(`${dir}/result.json`,JSON.stringify(out,null,2),{flag:'w'});console.log(JSON.stringify(out,null,2));if(out.failed)process.exitCode=1;
