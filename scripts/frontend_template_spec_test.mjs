import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = await fs.readFile('backend/src/main/resources/static/app.js', 'utf8');
const element = { addEventListener() {} };
const context = vm.createContext({
  console, Map, Set,
  window: {},
  document: { getElementById() { return element; }, addEventListener() {} },
  fetch() { throw new Error('offline test must not use the network'); },
});
vm.runInContext(source.replace(/init\(\);\s*$/, '') + `
globalThis.qa = {
  businessPresentation,
  templateBusinessPresentation,
  renderTemplateEditor,
  setCatalog(value) { state.templateCatalog = value; },
  setRun(value) { state.run = value; },
};`, context);

const checks = [];
const check = (name, fn) => {
  try { fn(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: String(error) }); }
};

check('template APIs and the publish action are wired', () => {
  for (const fragment of ['/api/check-templates', '/spec', '/check-sets/', '/configuration', 'idempotencyKey', 'manifestRevision', "action === 'confirm-template-set'", "action === 'confirm-template-set-submit'"]) assert.ok(source.includes(fragment), fragment);
});

const metadata = {
  id: 'PRICE_COMPARE', version: '1.0', title: '报价比较', evaluatorId: 'must-not-render', proofStandard: 'FACTUAL_CONDITION',
  requiredVerificationMode: 'DOCUMENT',
  parameters: {
    expected: { type: 'decimal', required: true, allowedValues: [], defaultValue: null },
    currency: { type: 'string', required: false, allowedValues: [], defaultValue: 'CNY' },
  }, fixedSubchecks: ['price'],
};
context.qa.setCatalog([metadata]);
const checkpoint = { id: 'c1', title: '最高限价', category: '响应检查', source: { documentId: 't1', locationType: 'PAGE', page: 8, quote: '最高限价100万元' } };
const spec = { templateId: 'PRICE_COMPARE', templateVersion: '1.0', purpose: 'MANDATORY', verificationMode: 'DOCUMENT', parameters: { expected: '1000000' }, sourceCoverage: { state: 'FULL' }, requirementRefs: [{ locationType: 'PAGE', location: 8, quote: '最高限价100万元' }], revision: 1, confirmed: false };
check('business template fields render without internal evaluator identity', () => {
  const html = context.qa.renderTemplateEditor(checkpoint, spec);
  assert.ok(html.includes('报价比较'));
  assert.ok(html.includes('检查目的'));
  assert.ok(html.includes('核验方式'));
  assert.ok(html.includes('id="cp-verification-mode" disabled'));
  assert.ok(html.includes('name="specVerificationMode" value="DOCUMENT"'));
  assert.ok(html.includes('招标来源覆盖'));
  assert.ok(html.includes('草稿确认前仍走兼容检查路线'));
  assert.ok(!html.includes('must-not-render'));
  assert.ok(!html.includes('evaluatorId'));
});

check('registry-required verification mode overrides a stale editable value', () => {
  const external = { ...metadata, id: 'EXTERNAL_CHECK', title: '外部平台记录', requiredVerificationMode: 'EXTERNAL' };
  context.qa.setCatalog([external]);
  const html = context.qa.renderTemplateEditor(checkpoint, { ...spec, templateId: 'EXTERNAL_CHECK', verificationMode: 'DOCUMENT' });
  assert.ok(html.includes('name="specVerificationMode" value="EXTERNAL"'));
  assert.ok(html.includes('由所选模板固定'));
  assert.ok(!html.includes('name="specVerificationMode" value="DOCUMENT"'));
});

const projection = {
  schemaVersion: 'template-result-v1', finalReconciled: true,
  conclusion: { state: 'SATISFIED', label: '满足要求', summary: '自动模板判断通过' },
  execution: { state: 'FINISHED' }, evidence: { state: 'SUFFICIENT' }, action: { owner: 'NONE' },
};
const finding = {
  id: 'r1', checkpoint: { id: 'c1', title: '最高限价', category: '响应检查' },
  evaluation: { status: 'PASS', reason: '自动判断通过', suggestion: '', evidences: [] },
  assessment: { resultProjection: projection, businessPresentation: { version: 'business-result-3', projectionVersion: 'business-dimensions-3', state: 'SATISFIED', finalStatus: 'PASS', label: '满足要求' } },
};
check('saved automatic projection is used when there is no review', () => {
  context.qa.setRun({ reviews: [] });
  assert.equal(context.qa.businessPresentation(finding, true).state, 'SATISFIED');
});
check('manual FAIL overrides automatic PASS and saved projection', () => {
  context.qa.setRun({ reviews: [{ resultId: 'r1', status: 'FAIL', reviewer: '复核人', note: '报价口径不符', createdAt: '2026-10-19T12:00:00Z' }] });
  const shown = context.qa.businessPresentation(finding, true);
  assert.equal(shown.state, 'RISK');
  assert.equal(shown.finalStatus, 'FAIL');
  assert.equal(shown.reason, '报价口径不符');
  assert.equal(shown.automaticPresentation.state, 'SATISFIED');
});

check('unknown saved presentation version is ignored and reprojected', () => {
  context.qa.setRun({ reviews: [] });
  const item = {
    ...finding,
    assessment: { businessPresentation: { version: 'business-result-99', state: 'RISK', finalStatus: 'PASS', reason: '未知缓存风险' } },
  };
  const shown = context.qa.businessPresentation(item, true);
  assert.equal(shown.state, 'SATISFIED');
  assert.equal(shown.finalStatus, 'PASS');
  assert.equal(shown.reason, '自动判断通过');
});

check('known legacy presentation is adapted only when its state agrees', () => {
  context.qa.setRun({ reviews: [] });
  const item = {
    ...finding,
    assessment: { businessPresentation: { version: 'business-result-2', state: 'SATISFIED', finalStatus: 'PASS', label: '旧版满足', reason: '旧版可追溯说明' } },
  };
  const shown = context.qa.businessPresentation(item, true);
  assert.equal(shown.state, 'SATISFIED');
  assert.equal(shown.label, '旧版满足');
  assert.equal(shown.reason, '旧版可追溯说明');
  assert.equal(shown.adaptedFrom, 'business-result-2');

  item.assessment.businessPresentation.state = 'RISK';
  assert.equal(context.qa.businessPresentation(item, true).state, 'SATISFIED');
  assert.equal(context.qa.businessPresentation(item, true).reason, '自动判断通过');
});

check('manual review is applied after unknown-version reprojection', () => {
  const item = {
    ...finding, id: 'r-unknown',
    assessment: { businessPresentation: { version: 'business-result-future', state: 'USER_ACTION', finalStatus: 'PASS' } },
  };
  context.qa.setRun({ reviews: [{ resultId: 'r-unknown', status: 'NOT_APPLICABLE', reviewer: '复核人', note: '条件不适用', createdAt: '2026-10-19T13:00:00Z' }] });
  const shown = context.qa.businessPresentation(item, true);
  assert.equal(shown.state, 'NOT_APPLICABLE');
  assert.equal(shown.finalStatus, 'NOT_APPLICABLE');
  assert.equal(shown.reason, '条件不适用');
  assert.equal(shown.automaticPresentation.state, 'SATISFIED');
});

const projectedFinding = (id, status, resultProjection) => ({
  id, checkpoint: { id: `cp-${id}`, title: id, category: '响应检查' },
  evaluation: { status, reason: '原始说明', suggestion: '', evidences: [] },
  assessment: { resultProjection },
});
const undeterminedProjection = overrides => ({
  schemaVersion: 'template-result-v1', finalReconciled: true, verificationMode: 'DOCUMENT',
  execution: { state: 'FINISHED', retryable: false },
  conclusion: { state: 'UNDETERMINED', summary: '尚未取得完整判断依据' },
  evidence: { state: 'NOT_LOCATED', refs: [] },
  action: { owner: 'SYSTEM', type: 'LOCATE_EVIDENCE' },
  missingInputs: ['material.body'], nextStep: '按缺失字段定向补查。',
  ...overrides,
});

check('undetermined template evidence is routed to evidence pending, never generic undetermined', () => {
  context.qa.setRun({ reviews: [] });
  for (const evidenceState of ['PARTIAL', 'CONFLICTING', 'NOT_LOCATED']) {
    const projection = undeterminedProjection({ evidence: { state: evidenceState, refs: [] } });
    const shown = context.qa.businessPresentation(projectedFinding(`evidence-${evidenceState}`, 'UNDETERMINED', projection));
    assert.equal(shown.state, 'EVIDENCE_PENDING');
    assert.equal(shown.label, '待补证据');
    assert.equal(shown.owner, 'SYSTEM');
    assert.equal(shown.userActionRequired, false);
    assert.equal(shown.nextStep, '按缺失字段定向补查。');
  }
});

check('template execution and repair failures are system incomplete before evidence or external routing', () => {
  context.qa.setRun({ reviews: [] });
  for (const executionState of ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'FAILED', 'BLOCKED', 'PAUSED', 'CANCELLED']) {
    const projection = undeterminedProjection({
      verificationMode: 'EXTERNAL', execution: { state: executionState, retryable: true },
      action: { owner: 'EXTERNAL', type: 'WAIT_EXTERNAL', actionId: 'external:1' },
    });
    const shown = context.qa.businessPresentation(projectedFinding(`execution-${executionState}`, 'UNDETERMINED', projection));
    assert.equal(shown.state, 'SYSTEM_INCOMPLETE');
    assert.equal(shown.owner, 'SYSTEM');
    assert.equal(shown.userActionRequired, false);
  }
  const repair = undeterminedProjection({ evidence: { state: 'SUFFICIENT', refs: ['p1'] }, action: { owner: 'SYSTEM', type: 'FIX_CHECK_SPEC' } });
  assert.equal(context.qa.businessPresentation(projectedFinding('repair', 'UNDETERMINED', repair)).state, 'SYSTEM_INCOMPLETE');
});

check('user and external template actions become explicit user actions', () => {
  context.qa.setRun({ reviews: [] });
  for (const [owner, mode] of [['USER', 'HUMAN_JUDGMENT'], ['EXTERNAL', 'EXTERNAL'], ['NONE', 'EXTERNAL']]) {
    const projection = undeterminedProjection({
      verificationMode: mode, evidence: { state: 'SUFFICIENT', refs: ['p1'] },
      action: { owner, type: owner === 'USER' ? 'REVIEW' : 'WAIT_EXTERNAL', actionId: 'action:1' },
    });
    const shown = context.qa.businessPresentation(projectedFinding(`action-${owner}-${mode}`, 'UNDETERMINED', projection));
    assert.equal(shown.state, 'USER_ACTION');
    assert.equal(shown.label, '需你确认');
    assert.equal(shown.owner, 'USER');
    assert.equal(shown.userActionRequired, true);
  }
});

check('scope and unclassified system work do not leak a generic undetermined state', () => {
  context.qa.setRun({ reviews: [] });
  const scope = undeterminedProjection({ evidence: { state: 'SCOPE_UNRESOLVED', refs: [] }, action: { owner: 'NONE', type: 'NONE' } });
  assert.equal(context.qa.businessPresentation(projectedFinding('scope', 'UNDETERMINED', scope)).state, 'SYSTEM_INCOMPLETE');
  const fallback = undeterminedProjection({ evidence: { state: 'SUFFICIENT', refs: ['p1'] }, action: { owner: 'NONE', type: 'NONE' }, missingInputs: [] });
  assert.equal(context.qa.businessPresentation(projectedFinding('fallback', 'UNDETERMINED', fallback)).state, 'EVIDENCE_PENDING');
});

check('determined template conclusions keep their business states', () => {
  context.qa.setRun({ reviews: [] });
  for (const [conclusion, status, expected] of [['SATISFIED', 'PASS', 'SATISFIED'], ['NOT_SATISFIED', 'FAIL', 'RISK'], ['SUSPECTED_RISK', 'WARNING', 'RISK'], ['NOT_APPLICABLE', 'NOT_APPLICABLE', 'NOT_APPLICABLE']]) {
    const projection = undeterminedProjection({ conclusion: { state: conclusion, summary: '已形成结论' }, evidence: { state: 'SUFFICIENT', refs: ['p1'] }, action: { owner: 'NONE', type: 'NONE' }, missingInputs: [] });
    assert.equal(context.qa.businessPresentation(projectedFinding(`determined-${conclusion}`, status, projection)).state, expected);
  }
});

const result = { status: checks.every(item => item.pass) ? 'PASSED' : 'FAILED', passed: checks.filter(item => item.pass).length, failed: checks.filter(item => !item.pass).length, checks };
console.log(JSON.stringify(result, null, 2));
if (result.failed) process.exitCode = 1;
