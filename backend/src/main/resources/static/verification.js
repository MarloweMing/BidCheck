const $ = id => document.getElementById(id);
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const enc = value => encodeURIComponent(String(value ?? ''));
const array = value => Array.isArray(value) ? value : [];
const text = value => typeof value === 'string' || typeof value === 'number' ? String(value) : '';
const options = (map, selected = '') => Object.entries(map).map(([value, label]) => `<option value="${escape(value)}" ${value === selected ? 'selected' : ''}>${escape(label)}</option>`).join('');
const statuses = { NEEDS_REVIEW: '需要补证或澄清', ENGINE_FAILURE: '核验服务异常', BUDGET_EXHAUSTED: '本轮预算已用尽', PENDING: '等待处理', RUNNING: '正在核验', COMPLETED: '本轮处理结束', FAILED: '处理失败', SUCCEEDED: '解析完成', WAIT_INPUT: '等待补充资料', WAIT_STAGE: '等待适用阶段', ERROR: '技术故障', CANCELLED: '任务已取消', WAIT_PARSE: '等待文件解析与续跑', PARSE_FAILED: '资料解析失败', INPUT_REJECTED: '补充资料未被采用', RESUMED: '已建立续跑修订' };
const decisions = { PASS: '符合', FAIL: '不符合', UNDETERMINED: '尚未判定', UNKNOWN: '尚未判定', NOT_APPLICABLE: '不适用', WARNING: '存在风险', SATISFIED: '已满足', NOT_SATISFIED: '未满足', PROVEN: '已证明', DISPROVEN: '已证明不满足', PREASSESSED: '预评已生成', PREASSESSMENT: '质量预评', INCOMPLETE: '尚未完成', UNPROVEN: '尚未证明', WAIT_INPUT: '等待资料', WAIT_STAGE: '等待阶段', ERROR: '核验异常' };
const bases = { RULE_COMPUTED: '规则计算', EXTERNAL_RECORD: '外部记录核验', SOURCE_GROUNDED_RULES: '有来源的规则核验', RULE_POLICY: '规则适用性核验', OBJECTIVE: '客观条件核验', OBJECTIVE_RULE: '客观条件核验', DETERMINISTIC: '规则计算', RULE: '规则计算', RULE_CALCULATION: '规则计算', QUALITY: '方案质量预评', QUALITY_PREASSESSMENT: '方案质量预评', SEMANTIC_PREASSESSMENT: '方案质量预评', AI_PREASSESSMENT: '方案质量预评', PREASSESSMENT: '方案质量预评', EXTERNAL: '外部事实核验', EXTERNAL_VERIFICATION: '外部事实核验', VISUAL: '原图核验', VISUAL_OBSERVATION: '原图可见形式核验', MATERIAL: '材料要求核验', SEMANTIC: '语义辅助核验', FORMAL: '正式评审记录', MIXED: '多条件综合核验' };
const kinds = { SECTION: '章节正文', PERSON_ROLE: '人员与岗位', CERTIFICATE: '证书要求', VALIDITY: '有效期', MONTHS: '月份范围', RELATION: '主体关系', NUMBER: '数值要求', SAME_FIELD: '同字段一致性', CONTRACT: '合同条件', SCORE_COUNT: '合同去重计分', REFERENCE_TIME: '参考时点', INPUT: '资料依赖', UNSUPPORTED: '规则解释待明确', MATERIAL: '材料要求', FACT: '客观事实', CONSISTENCY: '信息一致性', FORMULA: '规则计算', QUALITY: '质量预评', EXTERNAL: '外部事实', VISUAL: '原图核验', DECLARATION: '声明与承诺' };
const stages = { PRE_SUBMISSION: '提交前自检', EVALUATION: '评标阶段', POST_SUBMISSION: '提交后核验' };
const purposes = { QUOTATION: '报价分册', CLARIFICATION: '澄清文件', ATTACHMENT: '补充附件', OTHER: '其他补充资料' };
const externalKinds = { REGISTRATION: '企业或人员注册状态', CREDIT: '信用查询', BLACKLIST: '失信或禁入名单', RESTRICTED_SUPPLIER: '供应商限制名单', SUBMISSION: '投标递交记录', PAYMENT: '到账或支付回执', BIDDER_ROSTER: '有效投标主体名单', PRIOR_SERVICE: '历史服务记录', OTHER: '其他查询事项' };
const resumeLabels = { INPUT_OR_EVIDENCE_CHANGED: '资料或证据更新后续跑', INPUT_CHANGED: '补充对应资料后续跑', STAGE_CHANGED: '到达适用阶段后续跑', EXTERNAL_RECORD_ADDED: '补充对应来源记录后续跑', CONFIGURATION_CHANGED: '核验能力恢复后续跑', RETRY: '服务恢复后重试' };
const gapLabels = { OCR_PLAN_COVERAGE_REQUIRED: '识别范围待补齐', OCR_COVERAGE_DEFERRED: '待按计划补识别', OCR_SOURCE_DEFERRED: '引用位置待按计划补识别', RULE_AMENDMENT_UNCONFIRMED: '需要确认澄清后的完整规则', MISSING_INPUT: '等待资料', INPUT_MISSING: '等待资料', WAIT_INPUT: '等待资料', WAIT_STAGE: '等待阶段', ENGINE_FAILURE: '核验服务异常', BUDGET_EXHAUSTED: '本轮核验预算已用尽', RULE_AMBIGUITY: '规则解释待明确', EVIDENCE_SCOPE_INCOMPLETE: '证据未覆盖检查范围', EXTERNAL_RECORD_REQUIRED: '需要外部核验记录', VISUAL_REVIEW_REQUIRED: '需要原图核验', EVIDENCE_MISSING: '证据尚不充分', REFERENCE_DATE_REQUIRED: '需要明确参考日期' };
const terminal = new Set(['COMPLETED', 'FAILED', 'ERROR', 'SUCCEEDED', 'CANCELLED']);
const params = new URLSearchParams(location.search);
const state = {
  config: { maxFileSizeMb: 256, formats: ['pdf', 'docx', 'txt', 'md', 'png', 'jpg', 'jpeg'] }, projects: [], projectId: '', project: null,
  runs: [], runId: '', detail: null, selected: '', filter: 'ALL', query: '', projectEpoch: 0, runEpoch: 0, controller: null,
  timer: null, pollCount: 0, pollPaused: false, polling: false, tasks: new Map(), awaitingRevision: null,
  cache: new Map(), loads: new Map(), dialogEpoch: 0, evidenceRefs: [], preview: null, refreshBusy: false, amendmentDrafts: new Map(), view: 'results', facts: null, factsLoading: false, factsError: '', factsEpoch: 0, factsLoadedAt: 0, factsLoadedStatus: '', factKind: 'ALL', factQuery: '', factPage: 0, factFocus: [], factEvidenceRefs: [], factLimits: { entities: 20, relations: 20, conflicts: 20 },
};
const date = value => { if (!value) return '未记录'; const d = new Date(value); return Number.isNaN(d.valueOf()) ? text(value) : d.toLocaleString('zh-CN', { hour12: false }); };
const locationLabel = evidence => `第 ${Number(evidence?.page) || 1} ${evidence?.locationType === 'PAGE' ? '页' : evidence?.locationType === 'PARAGRAPH' ? '段' : evidence?.locationType === 'IMAGE' ? '张图' : '内容块'}`;
const label = (map, value, fallback) => map[value] || (/[\u3400-\u9fff]/.test(text(value)) ? text(value) : fallback);
const decisionLabel = value => label(decisions, value, '尚未判定');
const statusLabel = value => label(statuses, value, '状态待确认');
const style = value => ['PASS', 'SATISFIED', 'PROVEN'].includes(value) ? 'pass' : ['FAIL', 'NOT_SATISFIED', 'DISPROVEN', 'ERROR', 'FAILED'].includes(value) ? 'fail' : ['RUNNING', 'PENDING'].includes(value) ? 'active' : ['PREASSESSED', 'PREASSESSMENT'].includes(value) ? 'quality' : 'wait';
const pill = (value, title) => `<span class="pill ${style(value)}">${escape(title || decisionLabel(value))}</span>`;
const docName = doc => `${doc.name || '未命名文件'} · V${doc.version || 1}`;
const documents = () => array(state.project?.documents);
const runDocuments = () => array(state.detail?.documents);
const currentRun = () => state.detail?.run;
const results = () => array(state.detail?.results);
const roleLabel = value => value === 'TENDER' ? '招标依据' : value === 'BID' ? '投标资料' : '补充资料';
const maxSize = () => Number(state.config.maxFileSizeMb) > 0 ? Number(state.config.maxFileSizeMb) : 256;
const allowedFormats = () => array(state.config.formats).map(value => String(value).replace(/^\./, '').toLowerCase()).filter(value => ['pdf', 'docx', 'txt', 'md', 'png', 'jpg', 'jpeg'].includes(value));
const priceGaps = result => [...array(result?.gaps), ...array(result?.obligations).flatMap(item => array(item.gaps))].some(gap => /^PRICING_/.test(text(gap.code)));
const priceCalculation = result => result?.decisionBasis === 'RULE_CALCULATION' || array(result?.obligations).some(item => item.proof?.decisionBasis === 'RULE_CALCULATION');
const quality = result => /QUALITY|PREASSESS/i.test(text(result.decisionBasis));
const obligationKind = item => item.kind || item.proof?.kind || item.proof?.predicate || ({ VISUAL_OBSERVATION: 'VISUAL', EXTERNAL_VERIFICATION: 'EXTERNAL', EXTERNAL_RECORD: 'EXTERNAL', SEMANTIC_PREASSESSMENT: 'QUALITY' }[item.proof?.decisionBasis]);
const resultDecision = result => quality(result) && result.businessDecision === 'PASS' ? '预评已生成' : decisionLabel(result.businessDecision);
const sortedRuns = value => [...array(value)].sort((a, b) => (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0) || Number(b.revision || 0) - Number(a.revision || 0));
const runTitle = run => `修订 ${run.revision ?? 1} · ${statusLabel(run.status)} · ${date(run.createdAt)}`;

async function api(path, { method = 'GET', body, signal } = {}) {
  const multipart = body instanceof FormData;
  let response;
  try { response = await fetch(path, { method, signal, headers: multipart || body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : multipart ? body : JSON.stringify(body) }); }
  catch (error) { if (error.name === 'AbortError') throw error; throw new Error('暂时无法连接服务，请检查连接后重试。'); }
  if (!response.ok) {
    let message = response.status === 413 ? `文件超过服务允许的上传大小，当前上限为 ${maxSize()} MB。` : `请求未完成（${response.status}），请稍后重试。`;
    try { message = (await response.json()).message || message; } catch { /* Preserve the HTTP error when no JSON is returned. */ }
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}
function errorMessage(message = '') { $('page-error').textContent = message; $('page-error').hidden = !message; }
function notify(message, error = false) {
  const item = document.createElement('div'); item.className = `toast${error ? ' error' : ''}`;
  const body = document.createElement('span'); body.textContent = message;
  const close = document.createElement('button'); close.type = 'button'; close.textContent = '×'; close.setAttribute('aria-label', '关闭提示'); close.addEventListener('click', () => item.remove());
  item.append(body, close); $('notifications').append(item); setTimeout(() => item.remove(), error ? 15000 : 7500);
  while ($('notifications').children.length > 3) $('notifications').firstElementChild.remove();
}
function updateAddress() {
  const next = new URLSearchParams(); if (state.projectId) next.set('project', state.projectId); if (state.runId) next.set('run', state.runId);
  history.replaceState(null, '', `/verification.html${next.size ? `?${next}` : ''}`);
}
function stopPolling() { clearTimeout(state.timer); state.timer = null; }
function resetPolling() { stopPolling(); state.pollCount = 0; state.pollPaused = false; }
function needsPolling() { return (currentRun() && !terminal.has(currentRun().status)) || [...state.tasks.values()].some(task => !terminal.has(task.status)) || state.awaitingRevision; }
function schedulePoll() {
  stopPolling(); if (!state.projectId || state.pollPaused || !needsPolling() || document.hidden) return;
  if (state.pollCount >= 180) { state.pollPaused = true; renderActivity(); return; }
  state.timer = setTimeout(poll, state.pollCount < 12 ? 2000 : 5000);
}
async function poll() {
  if (state.polling) { schedulePoll(); return; }
  const epoch = state.projectEpoch, selectedId = state.runId, runEpoch = state.runEpoch;
  state.polling = true; state.pollCount++;
  try {
    let refreshHistory = !!state.awaitingRevision;
    for (const task of [...state.tasks.values()].filter(task => !terminal.has(task.status))) {
      const updated = await api(`/api/tasks/${enc(task.id)}`, { signal: state.controller?.signal });
      if (epoch !== state.projectEpoch) return;
      state.tasks.set(task.id, { ...task, ...updated });
      if (updated.status === 'SUCCEEDED') { refreshHistory = true; state.awaitingRevision = { baseRunId: task.baseRunId, requestId: updated.inputRequestId || task.inputRequestId, autoSelect: task.autoSelect === true, attempts: 0 }; }
    }
    if (refreshHistory) {
      const [project, runs] = await Promise.all([api(`/api/projects/${enc(state.projectId)}?includeContent=false`, { signal: state.controller?.signal }), api(`/api/projects/${enc(state.projectId)}/verification-runs`, { signal: state.controller?.signal })]);
      if (epoch !== state.projectEpoch) return;
      state.project = project; state.runs = sortedRuns(runs); renderSelectors();
      if (state.awaitingRevision) {
        const waiting = state.awaitingRevision;
        const newer = state.runs.find(run => run.parentRunId === waiting.baseRunId || (run.previousRunId === waiting.baseRunId));
        if (newer) {
          state.awaitingRevision = null;
          notify('补充资料已解析，新修订已建立。原核验记录继续保留。');
          if (waiting.autoSelect && !$('dialog').open && state.runId === waiting.baseRunId && runEpoch === state.runEpoch) { await selectRun(newer.id); return; }
        } else if (++waiting.attempts >= 24) { state.awaitingRevision = null; notify('资料已解析，自动续跑记录仍在准备。可稍后刷新核验记录；请勿重复上传。'); }
      }
    }
    if (selectedId && selectedId === state.runId && runEpoch === state.runEpoch) {
      const detail = await api(`/api/verification-runs/${enc(selectedId)}`, { signal: state.controller?.signal });
      if (epoch !== state.projectEpoch || runEpoch !== state.runEpoch || selectedId !== state.runId) return;
      state.detail = detail; mergeRun(detail.run); renderRun();
    }
    renderActivity(); renderHistory();
  } catch (error) {
    if (epoch === state.projectEpoch && error.name !== 'AbortError') { state.pollPaused = true; errorMessage(`${error.message} 自动刷新已暂停，当前记录已保留。`); renderActivity(); }
  } finally { state.polling = false; if (epoch === state.projectEpoch) schedulePoll(); }
}
function mergeRun(run) { if (!run?.id) return; const index = state.runs.findIndex(item => item.id === run.id); if (index < 0) state.runs.unshift(run); else state.runs[index] = { ...state.runs[index], ...run }; renderSelectors(); }
function recoverInputTasks() {
  for (const input of array(state.detail?.inputs)) {
    if (input.status === 'WAIT_PARSE' && input.taskId && !state.tasks.has(input.taskId)) {
      state.tasks.set(input.taskId, { id: input.taskId, status: 'PENDING', baseRunId: input.runId, inputRequestId: input.id, name: purposes[input.purpose] || '补充资料' });
    }
  }
}
function renderSelectors() {
  $('project-select').innerHTML = state.projects.length ? state.projects.map(project => `<option value="${escape(project.id)}" ${project.id === state.projectId ? 'selected' : ''}>${escape(project.name || '未命名项目')}</option>`).join('') : '<option value="">暂无项目</option>';
  $('project-select').disabled = !state.projects.length;
  $('run-select').innerHTML = state.runs.length ? `<option value="" ${!state.runId ? 'selected' : ''}>选择核验记录</option>${state.runs.map(run => `<option value="${escape(run.id)}" ${run.id === state.runId ? 'selected' : ''}>${escape(runTitle(run))}</option>`).join('')}` : '<option value="">尚无自动核验记录</option>';
  $('run-select').disabled = !state.runs.length; $('create-run').disabled = !state.project; $('refresh').disabled = !state.projectId || state.refreshBusy;
}
async function selectProject(id, requestedRun = '') {
  state.view = 'results'; resetFacts();
  const epoch = ++state.projectEpoch; ++state.runEpoch; state.controller?.abort(); state.controller = new AbortController(); resetPolling();
  closeDialog(); state.projectId = id; state.project = null; state.runId = ''; state.runs = []; state.detail = null; state.selected = ''; state.filter = 'ALL'; state.query = ''; state.tasks.clear(); state.awaitingRevision = null; state.cache.clear(); state.loads.clear(); errorMessage();
  $('run-overview').innerHTML = ''; $('history').innerHTML = ''; $('activity').innerHTML = ''; $('workbench').innerHTML = '<div class="empty"><span class="spinner"></span>正在读取项目资料…</div>'; renderSelectors(); updateAddress();
  if (!id) { renderRun(); return; }
  try {
    const [project, runs] = await Promise.all([api(`/api/projects/${enc(id)}?includeContent=false`, { signal: state.controller.signal }), api(`/api/projects/${enc(id)}/verification-runs`, { signal: state.controller.signal })]);
    if (epoch !== state.projectEpoch) return;
    state.project = project; state.runs = sortedRuns(runs); renderSelectors();
    const target = state.runs.find(run => run.id === requestedRun)?.id || state.runs[0]?.id;
    if (requestedRun && !state.runs.some(run => run.id === requestedRun)) notify('该核验记录不在当前项目中，已显示本项目记录。', true);
    if (target) await selectRun(target); else renderRun();
  } catch (error) { if (epoch === state.projectEpoch && error.name !== 'AbortError') { errorMessage(error.message); $('workbench').innerHTML = '<div class="empty"><h2>项目暂时无法读取</h2><p>请点击上方“刷新”重试。</p></div>'; } }
  finally { if (epoch === state.projectEpoch) renderSelectors(); }
}
async function selectRun(id) {
  resetFacts();
  const epoch = state.projectEpoch, sequence = ++state.runEpoch; resetPolling(); state.runId = id; state.detail = null; state.selected = ''; state.filter = 'ALL'; state.query = ''; errorMessage(); renderSelectors(); updateAddress();
  $('run-overview').innerHTML = ''; $('activity').innerHTML = ''; $('workbench').innerHTML = '<div class="empty"><span class="spinner"></span>正在读取核验记录…</div>';
  if (!id) { renderRun(); return; }
  try {
    const detail = await api(`/api/verification-runs/${enc(id)}`, { signal: state.controller?.signal });
    if (epoch !== state.projectEpoch || sequence !== state.runEpoch) return;
    if (detail.run?.projectId && detail.run.projectId !== state.projectId) throw new Error('核验记录与当前项目不一致，请重新选择。');
    state.detail = detail; recoverInputTasks(); mergeRun(detail.run); renderRun(); renderActivity(); schedulePoll();
  } catch (error) { if (epoch === state.projectEpoch && sequence === state.runEpoch && error.name !== 'AbortError') { errorMessage(error.message); $('workbench').innerHTML = '<div class="empty"><h2>核验记录暂时无法读取</h2><p>请刷新后重试。</p></div>'; } }
}
async function refresh() {
  if (state.refreshBusy || !state.projectId) return;
  const epoch = state.projectEpoch, sequence = state.runEpoch, id = state.runId;
  state.refreshBusy = true; resetPolling(); errorMessage(); renderSelectors();
  try {
    const [project, runs, detail] = await Promise.all([api(`/api/projects/${enc(state.projectId)}?includeContent=false`, { signal: state.controller?.signal }), api(`/api/projects/${enc(state.projectId)}/verification-runs`, { signal: state.controller?.signal }), id ? api(`/api/verification-runs/${enc(id)}`, { signal: state.controller?.signal }) : null]);
    if (epoch !== state.projectEpoch || sequence !== state.runEpoch) return;
    state.project = project; state.runs = sortedRuns(runs); state.detail = detail; recoverInputTasks(); renderSelectors(); renderRun(); renderActivity(); schedulePoll();
  } catch (error) { if (epoch === state.projectEpoch && error.name !== 'AbortError') errorMessage(error.message); }
  finally { state.refreshBusy = false; renderSelectors(); }
}
function renderActivity() {
  const run = currentRun(), tasks = [...state.tasks.values()], active = tasks.filter(task => task.status !== 'SUCCEEDED');
  const progress = Number(run?.progress);
  $('activity').innerHTML = `${run && !terminal.has(run.status) ? `<div class="notice"><strong>${escape(statusLabel(run.status))}</strong><p>${escape(run.stage || '逐个核验条件并保存证据，已得到的结果会陆续显示。')}</p>${Number.isFinite(progress) ? `<progress class="progress" value="${Math.max(0, Math.min(100, progress))}" max="100" aria-label="核验进度">${progress}%</progress>` : ''}</div>` : ''}${active.length ? `<div class="notice">${active.map(task => `<div class="task-line"><strong>${escape(task.name || '补充资料')} · ${escape(statusLabel(task.status))}</strong><small>${escape(task.error || task.stage || '正在解析文件，完成后自动建立新修订。')}</small>${!terminal.has(task.status) && Number.isFinite(Number(task.progress)) ? `<progress class="progress" value="${Math.max(0, Math.min(100, Number(task.progress)))}" max="100" aria-label="补充资料解析进度"></progress>` : ''}</div>`).join('')}</div>` : ''}${state.awaitingRevision ? '<div class="notice">资料已解析，正在等待自动续跑的新修订。原记录仍可查看。</div>' : ''}${state.pollPaused ? '<div class="notice warning notice-row"><span>自动刷新已暂停。后台任务仍按服务端状态继续，点击刷新可获取最新记录。</span><button class="button secondary small" data-action="refresh">继续刷新</button></div>' : ''}${run?.status === 'FAILED' ? `<div class="notice danger"><strong>本轮任务未正常结束</strong><p>${escape(run.error || '处理遇到技术故障，已保存的结果仍可查看。')}</p><p>任务故障不代表投标资料不符合。可通过“修订与续跑”重新处理。</p></div>` : ''}`;
  const inputFailures = array(state.detail?.inputs).filter(input => ['PARSE_FAILED', 'INPUT_REJECTED'].includes(input.status));
  if (inputFailures.length) $('activity').insertAdjacentHTML('beforeend', `<div class="notice danger"><strong>部分补充资料未能用于续跑</strong>${inputFailures.map(input => `<p>${escape(purposes[input.purpose] || '补充资料')}：${escape(input.error || statusLabel(input.status))}</p>`).join('')}<p>当前修订的结论保留，请核对资料后再提交。</p></div>`);
}
function renderRun() {
  const run = currentRun(); syncViews();
  if (!run) {
    $('run-overview').innerHTML = ''; $('workbench').innerHTML = `<div class="empty"><h2>${state.projectId ? '建立第一份自动核验记录' : '先选择一个项目'}</h2><p>${state.projectId ? '选择招标依据与投标资料集合。核验会保留每个条件的证据、结论和继续处理所需资料。' : '请先在项目工作台建立项目并上传文件，再进入自动核验。'}</p>${state.project ? '<button class="button primary" data-action="create">选择资料并开始</button>' : '<a class="button secondary" href="/">前往项目工作台</a>'}</div>`; renderHistory(); return;
  }
  const items = results(), objective = items.filter(item => !quality(item)), preassessments = items.filter(quality);
  const decided = objective.filter(item => ['PASS', 'FAIL', 'NOT_APPLICABLE', 'SATISFIED', 'NOT_SATISFIED'].includes(item.businessDecision)).length;
  const closed = items.filter(item => item.complete === true).length;
  $('run-overview').innerHTML = `<div class="run-head"><div><h2>修订 ${escape(run.revision ?? 1)} ${pill(run.status, statusLabel(run.status))}</h2><p>${escape(stages[run.evaluationStage] || '阶段未记录')} · 建立于 ${escape(date(run.createdAt))}${run.referenceDate ? ` · 参考日期 ${escape(run.referenceDate)}` : ' · 参考日期未指定'}</p></div><div class="actions"><button class="button secondary" data-action="supplement">补充资料</button><button class="button secondary" data-action="revision">修订与续跑</button>${run.status === 'COMPLETED' ? `<a class="button soft" href="/api/verification-runs/${enc(run.id)}/report?format=html" download>下载 HTML 报告</a><a class="button secondary" href="/api/verification-runs/${enc(run.id)}/report?format=json" download>JSON</a>` : '<span class="download-disabled"><small>本轮处理结束后可导出报告</small></span>'}</div></div><div class="metrics"><div class="metric"><div class="metric-label">已生成核验结果</div><div class="metric-value">${items.length}<small>项</small></div><div class="metric-note">结果产生不等于全部通过</div></div><div class="metric"><div class="metric-label">客观条件已判定</div><div class="metric-value">${decided}<small>/ ${objective.length} 项</small></div><div class="metric-note">符合、不符合或不适用</div></div><div class="metric"><div class="metric-label">方案质量预评</div><div class="metric-value">${preassessments.filter(item => item.complete === true).length}<small>/ ${preassessments.length} 项</small></div><div class="metric-note">独立于正式评审结果</div></div><div class="metric"><div class="metric-label">全部条件已完成</div><div class="metric-value">${closed}<small>/ ${items.length} 项</small></div><div class="metric-note">仍可含已证实的不符合</div></div></div>${renderDocumentManifest()}`;
  const existing = $('result-list');
  if (!existing) $('workbench').innerHTML = `<div class="filters"><label>结果范围<select id="result-filter"><option value="ALL">全部结果</option><option value="PASS">符合</option><option value="FAIL">不符合</option><option value="OPEN">未完成条件</option><option value="QUALITY">质量预评</option><option value="ERROR">技术故障</option></select></label><label class="search"><input type="search" id="result-search" placeholder="搜索要求、结论或资料缺口" aria-label="搜索核验结果"></label><span class="muted" id="filtered-count"></span></div><div class="result-layout"><div id="result-list" class="results-list" aria-label="核验结果"></div><section id="result-detail" class="detail" aria-label="逐条件核验详情"></section></div>`;
  renderResults(); renderHistory();
}
function renderDocumentManifest() {
  return `${renderParsingCoverage()}${renderPersonCredentialCoverage()}<details class="meta-details"><summary>本次使用的资料与输入 · ${runDocuments().length} 份文件</summary><div class="doc-list">${runDocuments().map(doc => `<div class="doc-line">${pill('PENDING', roleLabel(doc.role))}<div class="doc-info"><strong>${escape(docName(doc))}</strong><small>${Number(doc.pageCount ?? doc.pages?.length) || 0} 个原文位置${doc.createdAt ? ` · ${escape(date(doc.createdAt))}` : ''}</small></div><a class="text-button" href="/api/documents/${enc(doc.id)}/raw" download>原文件</a></div>`).join('') || '<p class="muted">未返回资料清单，请查看报告中的冻结输入。</p>'}</div>${renderInputs()}</details>`;
}
function renderParsingCoverage() {
  const run = currentRun() || {}, prepared = array(run.preparedManifest);
  const evidenceIds = new Set(Array.isArray(run.evidenceDocumentIds) ? [...run.evidenceDocumentIds, run.tenderDocumentId] : prepared.length ? [...prepared.map(item => item.documentId), run.tenderDocumentId] : runDocuments().map(doc => doc.id));
  const sections = runDocuments().filter(doc => evidenceIds.has(doc.id)).map(doc => {
    const coverage = array(state.detail?.ocrContext?.documents).find(item => item?.documentId === doc.id) || doc.ocrCoverage;
    if (!coverage || typeof coverage !== 'object') {
      const deferred = array(doc.warnings).filter(warning => String(warning).includes('[OCR_DEFERRED]'));
      return deferred.length ? `<div><strong>${escape(docName(doc))}</strong><p>本版本有 ${deferred.length} 条待按需补识别记录；具体范围尚未返回。计划延期不表示识别服务失败。</p><details class="meta-details"><summary>查看计划延期记录</summary>${deferred.map(warning => `<p>${escape(warning)}</p>`).join('')}</details></div>` : '';
    }
    const locations = (values, label) => {
      const items = array(values).filter(item => item?.documentId === doc.id && Number.isInteger(item.page) && item.page > 0);
      return items.length ? `<details class="meta-details"><summary>查看 ${items.length} 处${label}</summary>${items.map(item => `<p><button class="text-button" data-action="coverage-location" data-document="${escape(doc.id)}" data-page="${item.page}" data-location="${escape(item.locationType || '')}">${escape(item.label || locationLabel(item))}</button> · ${escape(item.warning || '')}</p>`).join('')}</details>` : '';
    };
    const details = Array.isArray(coverage.deferredLocations) ? locations(coverage.deferredLocations, '待按需补识别位置') + locations(coverage.failedLocations, '识别未完成位置') : locations(coverage.incompleteLocations, '识别未完成位置');
    return `<div><strong>${escape(docName(doc))}</strong><p>${escape(coverage.notice || '未提供本版本解析范围说明。')}</p>${details}</div>`;
  }).filter(Boolean);
  const preparedNote = prepared.length ? `<details class="meta-details"><summary>本轮实际用于证据的识别版本 · ${prepared.length} 份</summary><p>原始资料集合继续保留。以下版本是在当前检查计划下准备的证据版本，新识别内容不覆盖历史结论。</p>${prepared.map(item => `<p>${escape(item.name || '文件')} · V${escape(item.version ?? '?')}</p>`).join('')}</details>` : '';
  return sections.length || prepared.length ? `<section class="notice" aria-label="本次文档解析范围"><strong>本次文档解析范围</strong><p>范围仅对应当前修订实际用于证据的文件版本。局部文字证明与全文完整性分别核验，未读取范围不能直接当作缺材料或全文通过。</p>${preparedNote}${sections.join('')}</section>` : '';
}
function personCredentialIssueRequiresReview(issue) {
  if (typeof issue?.requiresReview === 'boolean') return issue.requiresReview;
  if (issue?.severity === 'INFO') return false;
  if (issue?.severity === 'WARNING') return true;
  return /^(?:SOURCE_|PAGE_|DOCUMENT_INCOMPLETE$|COMPARISON_BUDGET$|PERSON_(?:PAGE_LIMIT|RECORD_LIMIT)|.*(?:NOT_PERSISTED|PERSISTENCE|BUDGET))/.test(String(issue?.code || ''));
}
function personCredentialIssueSections(coverage) {
  const issues = array(coverage?.issues).filter(item => item && typeof item === 'object');
  const actionable = Array.isArray(coverage?.actionableIssues) ? coverage.actionableIssues : issues.filter(personCredentialIssueRequiresReview);
  const informational = Array.isArray(coverage?.informationalIssues) ? coverage.informationalIssues : issues.filter(issue => !personCredentialIssueRequiresReview(issue));
  const requiresReview = typeof coverage?.requiresReview === 'boolean' ? coverage.requiresReview : actionable.length > 0 || Number(coverage?.pagesUnparsed) > 0 || Number(coverage?.pagesScanned) < Number(coverage?.pagesTotal);
  return { actionable, informational, requiresReview };
}
function renderPersonCredentialCoverage(coverage = state.detail?.personCredentialConsistency?.coverage, informationalGroups = state.detail?.personCredentialConsistency?.informationalGroups) {
  if (!coverage || typeof coverage !== 'object' || Array.isArray(coverage) || !Object.keys(coverage).length) return '';
  const report = state.detail?.personCredentialConsistency || {}, docs = runDocuments();
  const count = value => Number.isInteger(Number(value)) && Number(value) >= 0 && value !== null && value !== undefined ? Number(value) : '—';
  const sources = array(report.sourceManifest).filter(item => item && typeof item === 'object');
  const sourceName = item => text(item.documentName) || docs.find(doc => doc.id === item.documentId)?.name || (item.documentId ? `来源文件 ${text(item.documentId)}` : '本轮资料');
  const issueLocation = item => {
    const page = Number(item.page), documentId = text(item.documentId), document = docs.find(doc => doc.id === documentId);
    const name = escape(sourceName(item));
    if (!Number.isInteger(page) || page <= 0) return `${name} · 范围提示`;
    const locationType = text(item.locationType), caption = `第 ${page} ${locationType === 'PAGE' || !locationType ? '页' : locationType === 'PARAGRAPH' ? '段' : locationType === 'IMAGE' ? '张图' : '处原文位置'}`;
    return document ? `${name} · <button class="text-button" data-action="coverage-location" data-document="${escape(documentId)}" data-page="${page}" data-location="${escape(locationType)}">${escape(caption)}</button>` : `${name} · ${escape(caption)}`;
  };
  const { actionable, informational, requiresReview } = personCredentialIssueSections(coverage);
  const missing = array(coverage.missingCandidateLocations).filter(item => item && typeof item === 'object');
  const groupCount = Array.isArray(report.groups) ? report.groups.filter(group => group.status === 'CONFLICT' || group.status === 'SUSPECTED').length : coverage.comparisonGroupsCount;
  const details = (items, caption) => items.length ? `<details class="meta-details"><summary>${caption} · ${items.length} 条</summary>${items.slice(0, 100).map(item => `<p>${issueLocation(item)}：${escape(item.reason || item.message || '部分字段未参与比较。')}</p>`).join('')}${items.length > 100 ? '<p>此处展示前 100 条说明；完整位置见 JSON 报告。</p>' : ''}</details>` : '';
  const associations = array(informationalGroups).filter(item => item && typeof item === 'object');
  const associationDetails = associations.length ? `<details class="person-credential-information meta-details"><summary>关联说明 · ${associations.length} 组，不计入检查问题</summary>${associations.map(group => `<details><summary>${escape(group.fieldLabel || '人员与证件记录')}</summary><p>${escape(group.reason || '部分字段未参与比较。')}</p>${array(group.evidences).map(item => `<p>${issueLocation(item)}</p><blockquote class="evidence-quote">${escape(item.quote || '')}</blockquote>`).join('')}</details>`).join('')}</details>` : '';
  return `<section class="notice ${requiresReview ? 'warning' : ''}" aria-label="人员与证件事实覆盖"><strong>人员与证件事实范围</strong><p>已扫描 ${count(coverage.pagesScanned)} / ${count(coverage.pagesTotal)} 个原文位置；发现人员材料 ${count(coverage.candidatePages)} 处；提取事实 ${count(coverage.occurrencesCount)} 条；信息差异 ${count(groupCount)} 组。</p>${sources.length ? `<p>来源文件：${sources.map(item => `${escape(sourceName(item))} · V${escape(item.documentVersion ?? '—')}`).join('；')}</p>` : ''}${requiresReview ? '<p>部分扫描或来源验证未完成，可查看对应位置。</p>' : ''}${coverage.status === 'NO_FACTS' ? '<p>当前未提取到可比较的人员证件字段，不代表原文件没有相关材料。</p>' : ''}${details(actionable, '查看未完成的处理')}${details(informational, '提取说明（不计入检查问题）')}${missing.length ? `<details class="meta-details"><summary>尚无明确字段的候选位置 · ${missing.length} 处</summary>${missing.slice(0, 100).map(item => `<p>${issueLocation(item)}</p>`).join('')}${missing.length > 100 ? '<p>此处展示前 100 处；完整位置见 JSON 报告。</p>' : ''}</details>` : ''}${associationDetails}<p>比较范围为已提取且能定位原文的字段；未列出差异不等于全文一致。差异页码和原文证据见检查结果与事实关联。</p></section>`;
}
function renderInputs() {
  const input = state.detail?.inputs;
  const records = [...(Array.isArray(input) ? input : [...array(input?.externalRecords), ...array(input?.requests), ...array(input?.documents)]), ...array(state.detail?.externalRecords)];
  return `${records.length ? `<h3 class="section-heading">补充与登记记录</h3>${records.map(record => `<div class="saved-input"><strong>${escape(record.subject || record.name || record.fileName || purposes[record.purpose] || '补充输入')}</strong><small>${escape(record.error || record.source || record.stage || statusLabel(record.status))}${record.createdAt ? ` · ${escape(date(record.createdAt))}` : ''}</small>${record.quote ? `<p class="evidence-quote">${escape(record.quote)}</p>` : ''}</div>`).join('')}` : ''}<div class="data-note">仅使用当前修订的资料。新增文件和外部记录需建立新修订后才影响结论。</div><button class="text-button" data-action="external">登记外部查询记录</button>`;
}
function filteredResults() {
  return results().filter(result => {
    if (state.filter === 'PASS' && result.businessDecision !== 'PASS') return false;
    if (state.filter === 'FAIL' && result.businessDecision !== 'FAIL') return false;
    if (state.filter === 'OPEN' && result.complete === true) return false;
    if (state.filter === 'QUALITY' && !quality(result)) return false;
    if (state.filter === 'ERROR' && !['ERROR', 'ENGINE_FAILURE'].includes(result.workflowState)) return false;
    const query = state.query.trim().toLowerCase();
    return !query || `${result.title || ''} ${result.reason || ''} ${array(result.gaps).map(gap => gap.description).join(' ')}`.toLowerCase().includes(query);
  });
}
function renderResults() {
  const list = $('result-list'); if (!list) return;
  const scrollTop = list.scrollTop;
  const visible = filteredResults();
  if (!visible.some(result => result.checkpointId === state.selected)) state.selected = visible[0]?.checkpointId || '';
  $('filtered-count').textContent = `${visible.length} / ${results().length} 项`;
  list.innerHTML = visible.map(result => `<button type="button" class="result-button ${result.checkpointId === state.selected ? 'selected' : ''}" data-action="select-result" data-id="${escape(result.checkpointId)}" aria-pressed="${result.checkpointId === state.selected}"><span class="result-title"><span class="result-index">${results().indexOf(result) + 1}</span>${escape(result.title || '未命名检查项')}</span><span class="result-meta">${pill(result.businessDecision, resultDecision(result))}<span>${result.complete === true ? '条件已完成' : '仍有待核条件'}</span>${quality(result) ? '<span class="pill quality">质量预评</span>' : ''}</span><span class="result-preview">${escape(result.reason || '')}</span></button>`).join('') || `<div class="empty"><p>${results().length ? '没有匹配的结果，调整筛选后重试。' : '正在等待逐项核验结果。'}</p></div>`;
  list.scrollTop = scrollTop; renderDetail();
}
function evidenceButtons(evidences, ledger = false) {
  return array(evidences).map(evidence => {
    const doc = runDocuments().find(item => item.id === evidence.documentId);
    if (!doc) return '<p class="data-note">该引用未匹配本次冻结资料，请核对报告来源。</p>';
    const index = (ledger ? state.factEvidenceRefs : state.evidenceRefs).push(evidence) - 1;
    return `<button class="evidence" data-action="${ledger ? 'fact-evidence' : 'evidence'}" data-index="${index}"><strong>${escape(roleLabel(doc.role))} · ${escape(doc.name || '原文')}</strong><small>${escape(locationLabel(evidence))} · 查看原文</small><span class="evidence-quote">${escape(evidence.quote || '打开对应原文位置')}</span></button>`;
  }).join('');
}
function gaps(gapItems) {
  return `<div class="gap-list">${array(gapItems).map(gap => `<div class="gap ${/ENGINE|ERROR|FAILURE|TIMEOUT/.test(text(gap.code)) ? 'error' : ''}"><strong>${escape(gap.description || gapLabels[gap.code] || '尚有待核条件')}</strong>${gap.requiredInput ? `<p>所需输入：${escape(text(gap.requiredInput) || array(gap.requiredInput).map(text).join('、') || '请核对资料要求')}</p>` : ''}${gap.resumeTrigger ? `<p>继续核验条件：${escape(label(resumeLabels, gap.resumeTrigger, '相关输入或核验条件变化后重新核验'))}</p>` : ''}${gapLabels[gap.code] && gap.description ? `<p>${escape(gapLabels[gap.code])}</p>` : ''}</div>`).join('')}</div>`;
}
function proofDetails(proof) {
  if (!proof || typeof proof !== 'object') return '';
  const fields = [['证明方式', label(bases, proof.decisionBasis, kinds[proof.predicate] || text(proof.method || proof.type))], ['规则原文', proof.requirementQuote], ['核验范围', proof.scope], ['参考时点', proof.referenceDate], ['计算结果', proof.value ?? proof.result], [proof.decisionBasis === 'SEMANTIC_PREASSESSMENT' ? '预评建议分' : '规则计算得分', proof.score], ['已证得分下限', proof.scoreLower], ['可能得分上限', proof.scoreUpper], ['预评档次', proof.band], ['评分量表', proof.rubric], ['去重合同数', proof.deduplicatedContracts], ['限制说明', proof.limitation || proof.limitations]];
  const values = fields.filter(([, value]) => text(value));
  return values.length ? `<details class="proof"><summary>判断依据与计算过程</summary><dl>${values.map(([name, value]) => `<dt>${escape(name)}</dt><dd>${escape(text(value))}</dd>`).join('')}</dl></details>` : '';
}
function renderDetail() {
  const pane = $('result-detail'); if (!pane) return;
  const focused = document.activeElement?.closest('#amendment-form') ? document.activeElement : null;
  const focusName = focused?.form?.dataset.checkpoint === state.selected ? focused?.name : null, selectionStart = focused?.selectionStart, selectionEnd = focused?.selectionEnd;
  const result = results().find(item => item.checkpointId === state.selected); state.evidenceRefs = [];
  if (!result) { pane.innerHTML = '<div class="empty"><p>选择一条结果查看条件、依据和缺口。</p></div>'; return; }
  const obligations = array(result.obligations);
  const visual = obligations.filter(item => obligationKind(item) === 'VISUAL');
  const external = obligations.filter(item => obligationKind(item) === 'EXTERNAL');
  pane.innerHTML = `<div class="detail-heading"><span class="eyebrow">REQUIREMENT & EVIDENCE</span><h2>${escape(result.title)}</h2><div class="actions">${pill(result.businessDecision, resultDecision(result))}<span class="pill">${escape(label(bases, result.decisionBasis, '逐条件核验'))}</span>${quality(result) ? '<span class="pill quality">非正式评分</span>' : ''}</div></div><div class="detail-content"><div class="decision-grid"><div class="decision-box"><small>${quality(result) ? '质量预评结论' : '业务结论'}</small><strong>${escape(resultDecision(result))}</strong></div><div class="decision-box"><small>自动处理与完成状态</small><strong>${escape(statusLabel(result.workflowState))} · ${result.complete === true ? '全部条件已完成' : '仍有未完成条件'}</strong></div></div><p class="reason" data-result-reason>${escape(result.reason || '该项尚未返回完整结论。')}</p>${renderAmendment(result)}${priceGaps(result) ? `<div class="notice warning"><strong>价格评分资料待补充</strong><p>需提供全部有效投标人的不含税评标价及原文来源。未登记完整数据时不计算价格分。</p><button class="button secondary small" data-action="pricing" ${!['COMPLETED', 'FAILED'].includes(currentRun()?.status) ? 'disabled' : ''}>登记有效评标价</button></div>` : ''}${priceCalculation(result) ? '<div class="subtle-box">规则计算分仅用于核验与参考，不代表正式评审得分。</div>' : ''}${quality(result) ? '<div class="subtle-box">这里展示方案质量预评。规则计算得分与正式评审结果分别记录，不能将预评作为正式得分。</div>' : ''}${visual.length || external.length ? `<div class="subtle-box">${visual.length ? `<p>原图核验：${visual.filter(item => ['PASS', 'PROVEN', 'SATISFIED'].includes(item.status)).length} / ${visual.length} 个条件已证明；文本识别不等于签章真实性核验。</p>` : ''}${external.length ? `<p>外部事实：${external.filter(item => ['PASS', 'PROVEN', 'SATISFIED'].includes(item.status)).length} / ${external.length} 个条件已证明；记录登记不等于来源已通过核验。</p>` : ''}</div>` : ''}<h3 class="section-heading">逐条件核验 <small>${obligations.length} 项</small></h3>${obligations.map(item => `<article class="obligation">${kinds[obligationKind(item)] ? `<div class="kind">${escape(kinds[obligationKind(item)])}</div>` : ''}<div class="obligation-top"><h4>${escape(item.description || '核验条件')}</h4>${pill(item.status)}</div>${item.reason ? `<p>${escape(item.reason)}</p>` : ''}${evidenceButtons(item.evidences)}${array(item.gaps).length ? `<h4 class="section-heading">该条件仍需</h4>${gaps(item.gaps)}` : ''}${proofDetails(item.proof)}</article>`).join('') || '<p class="muted">该项尚未返回细分条件，不能据此推定全部条件成立。</p>'}${array(result.gaps).length ? `<h3 class="section-heading">继续核验所需条件</h3>${gaps(result.gaps)}<div class="actions section-heading"><button class="button secondary small" data-action="supplement">补充资料</button><button class="button secondary small" data-action="external">登记外部记录</button></div>` : ''}${array(result.evidences).length ? `<h3 class="section-heading">本项引用依据</h3>${evidenceButtons(result.evidences)}` : ''}${renderMetrics(result.metrics)}</div>`;
  if (focusName) {
    const next = pane.querySelector(`#amendment-form [name="${focusName}"]`); next?.focus({ preventScroll: true });
    if (selectionStart != null && next?.setSelectionRange) next.setSelectionRange(selectionStart, selectionEnd);
  }
}

const factKinds = { ...kinds, DATE_RANGE: '日期范围', FIELD: '字段信息', ENTITY: '主体信息', COMPANY: '企业', PERSON: '人员', VEHICLE: '车辆', MONEY: '金额', BIDDER: '投标主体' };
const factScopes = { CURRENT_PROJECT: '本次项目', HISTORICAL_PROJECT: '历史项目', UNRESOLVED: '适用范围待明确' };
function factValue(value) { if (value == null || value === '') return '未记录'; if (typeof value === 'object') { try { return JSON.stringify(value); } catch { return '内容待核对'; } } return String(value); }
function factKindName(value) { return label(factKinds, value, '其他事实'); }
function identityLabel(value) { return value === 'STRONG' ? '按身份标识关联' : value === 'SOURCE_LOCAL' ? '仅限原文局部身份' : '身份关联待明确'; }
function resetFacts() {
  state.factsEpoch++; state.facts = null; state.factsLoading = false; state.factsError = ''; state.factsLoadedAt = 0; state.factsLoadedStatus = ''; state.factKind = 'ALL'; state.factQuery = ''; state.factPage = 0; state.factFocus = []; state.factEvidenceRefs = []; state.factLimits = { entities: 20, relations: 20, conflicts: 20 };
  $('facts-workbench').innerHTML = ''; $('facts-workbench').hidden = true; $('verification-views').hidden = true; $('workbench').hidden = false;
}
function syncViews() {
  const run = currentRun(); $('verification-views').hidden = !run;
  const facts = !!run && state.view === 'facts'; $('workbench').hidden = facts; $('facts-workbench').hidden = !facts;
  $('verification-views').querySelector('[data-action="show-results"]').setAttribute('aria-pressed', String(!facts));
  $('verification-views').querySelector('[data-action="show-facts"]').setAttribute('aria-pressed', String(facts));
  if (facts) { renderFacts(); loadFacts(); }
}
async function loadFacts(force = false) {
  const run = currentRun(); if (!run || state.view !== 'facts' || state.factsLoading) return;
  if (!force && state.factsError) return;
  if (!force && state.facts && state.factsLoadedStatus === run.status && (terminal.has(run.status) || Date.now() - state.factsLoadedAt < 10000)) return;
  const epoch = state.projectEpoch, sequence = state.runEpoch, factEpoch = state.factsEpoch, id = run.id, status = run.status;
  state.factsLoading = true; state.factsError = ''; renderFactsStatus();
  try {
    const response = await api('/api/verification-runs/' + enc(id) + '/facts', { signal: state.controller?.signal });
    if (epoch !== state.projectEpoch || sequence !== state.runEpoch || factEpoch !== state.factsEpoch || state.runId !== id) return;
    if (response?.runId !== id) throw new Error('事实账本与所选核验记录不一致，请重新读取。');
    state.facts = { ...response, occurrences: array(response.occurrences).filter(item => item && typeof item === 'object'), entities: array(response.entities).filter(item => item && typeof item === 'object'), relations: array(response.relations).filter(item => item && typeof item === 'object'), conflicts: array(response.conflicts).filter(item => item && typeof item === 'object') };
    state.factsLoadedAt = Date.now(); state.factsLoadedStatus = status; renderFacts();
  } catch (error) { if (epoch === state.projectEpoch && sequence === state.runEpoch && factEpoch === state.factsEpoch && error.name !== 'AbortError') { state.factsError = error.message; renderFactsStatus(); } }
  finally { if (epoch === state.projectEpoch && sequence === state.runEpoch && factEpoch === state.factsEpoch) { state.factsLoading = false; renderFactsStatus(); } }
}
function renderFactsStatus() {
  const area = $('facts-state'); if (!area) return;
  area.innerHTML = state.factsError ? '<div class="notice danger" role="alert">' + escape(state.factsError) + (state.facts ? '<p>保留下方上次读取的本次账本；未将读取失败解释成没有事实。</p>' : '<p>账本暂时无法读取，仍可返回核验结果查看原结论。</p>') + '<button class="button secondary small" data-action="refresh-facts">重试读取</button></div>' : state.factsLoading ? '<p class="data-note"><span class="spinner"></span>正在读取本次核验的事实账本…</p>' : '';
  const button = $('facts-refresh'); if (button) button.disabled = state.factsLoading;
}
function renderFacts() {
  if (!$('facts-controls')) $('facts-workbench').innerHTML = '<div class="facts-heading"><div><h2>事实与关系</h2><p>查看本次核验实际使用的事实、来源与关联，不把提取记录直接等同于业务通过。</p></div><button id="facts-refresh" class="button secondary small" data-action="refresh-facts">刷新事实</button></div><div id="facts-scope" class="subtle-box"></div><div id="facts-state" aria-live="polite"></div><div id="facts-limit"></div><div id="facts-person-coverage"></div><div id="facts-summary" class="facts-summary"></div><div id="facts-controls" class="filters"><label>事实种类<select id="fact-kind"><option value="ALL">全部种类</option></select></label><label class="search"><input id="fact-search" type="search" placeholder="搜索主体、字段、原值或规范值" aria-label="搜索事实"></label></div><div id="facts-content"></div>';
  $('facts-scope').textContent = terminal.has(currentRun()?.status) ? '这是当前核验记录使用并冻结的事实范围；其他记录后来发现的事实不会并入。身份标识关联不代表本项业务条件已经成立。' : '核验仍在进行，仅展示本次运行截至当前已使用的事实。尚未出现的记录不能解释为原文件不存在相应事实。';
  renderFactsStatus(); const ledger = state.facts; if (!ledger) return;
  $('facts-person-coverage').innerHTML = renderPersonCredentialCoverage(ledger.personCredentialCoverage, ledger.personCredentialInformationalGroups);
  const total = Number(ledger.totalOccurrences ?? ledger.summary?.occurrences ?? ledger.occurrences.length);
  $('facts-limit').innerHTML = ledger.truncated === true || total > ledger.occurrences.length ? '<div class="notice warning">当前仅载入 ' + ledger.occurrences.length + ' / ' + (Number.isFinite(total) ? total : '未知总数') + ' 条事实。筛选和关联对照仅作用于已载入记录，不代表账本全部；完整明细请查看本次 JSON 报告。</div>' : '';
  const summary = ledger.summary || {}, fields = [['occurrences', '事实记录', ledger.occurrences.length], ['entities', '主体记录', ledger.entities.length], ['relations', '关系记录', ledger.relations.length], ['conflicts', '待核冲突', ledger.conflicts.length], ['sharedOccurrences', '多项共用事实', ledger.occurrences.filter(item => array(item.usedByCheckpoints).length > 1).length]];
  $('facts-summary').innerHTML = fields.map(([key, title, fallback]) => '<div><small>' + title + '</small><strong>' + escape(Number.isFinite(Number(summary[key])) && summary[key] != null ? Number(summary[key]) : fallback) + '</strong></div>').join('');
  const kindsPresent = [...new Set(ledger.occurrences.map(item => text(item.kind)).filter(Boolean))]; if (state.factKind !== 'ALL' && !kindsPresent.includes(state.factKind)) state.factKind = 'ALL';
  $('fact-kind').innerHTML = '<option value="ALL">全部种类</option>' + kindsPresent.map(kind => '<option value="' + escape(kind) + '"' + (state.factKind === kind ? ' selected' : '') + '>' + escape(factKindName(kind)) + '</option>').join('');
  renderFactsContent();
}
function factUses(item) {
  return array(item.usedByCheckpoints).map(use => { const id = typeof use === 'string' ? use : use.id, result = results().find(row => row.checkpointId === id), title = text(use?.title) || result?.title || '关联检查项'; return result ? '<button type="button" class="fact-use" data-action="fact-checkpoint" data-id="' + escape(id) + '">' + escape(title) + '</button>' : '<span class="fact-use unavailable">' + escape(title) + ' · 结果尚未返回</span>'; }).join('') || '<span class="muted">未返回使用检查项</span>';
}
function factCard(item) {
  return '<article class="fact-card" data-fact-id="' + escape(item.id) + '"><div class="fact-card-head"><h3>' + escape(item.subject || '未明确主体') + '</h3><span class="pill">' + escape(factKindName(item.kind)) + '</span></div><div class="fact-tags"><span>' + escape(identityLabel(item.identityStrength)) + '</span><span>' + escape(label(factScopes, item.scope, '适用范围待明确')) + '</span></div><dl class="fact-values"><dt>字段</dt><dd>' + escape(item.field || '未指定') + '</dd><dt>原文值</dt><dd class="fact-raw">' + escape(factValue(item.rawValue)) + '</dd><dt>规范值</dt><dd>' + escape(factValue(item.value)) + (item.unit ? ' ' + escape(item.unit) : '') + '</dd>' + (item.basis ? '<dt>解释口径</dt><dd>' + escape(item.basis) + '</dd>' : '') + '</dl><div class="fact-used"><span>使用检查项</span><div>' + factUses(item) + '</div></div><details class="fact-sources"><summary>原文来源 · ' + array(item.sourceRefs).length + ' 处' + (array(item.origins).length ? ' · ' + array(item.origins).length + ' 条提取记录' : '') + '</summary>' + (array(item.sourceRefs).length ? evidenceButtons(item.sourceRefs, true) : '<p class="data-note">未返回可打开的来源，不能据此确认该事实。</p>') + '</details></article>';
}
function maskedIdentifiers(value) {
  const entries = Array.isArray(value) ? value.map((item, index) => [item?.type || item?.kind || '标识 ' + (index + 1), typeof item === 'object' ? item.maskedValue || item.value || item.masked || '' : item]) : value && typeof value === 'object' ? Object.entries(value) : value ? [['标识', value]] : [];
  return entries.map(([key, raw]) => { const value = text(raw); if (!value) return ''; const safe = /[*＊•]/.test(value) ? value : value.length <= 4 ? '****' : value.slice(0, 2) + '****' + value.slice(-2); return '<span>' + escape(label({ CREDIT_CODE: '统一信用代码', CERTIFICATE_NUMBER: '证书编号', ID_NUMBER: '身份号码', VIN: '车辆识别号' }, key, '身份标识')) + '：' + escape(safe) + '</span>'; }).join('');
}
function factEntityCard(entity) {
  const ids = array(entity.occurrenceIds); return '<article class="ledger-row"><div class="fact-card-head"><h4>' + escape(entity.displayName || '未命名主体') + '</h4><span class="pill">' + escape(identityLabel(entity.identityStrength)) + '</span></div><p>' + (entity.identityStrength === 'STRONG' ? '依据身份标识关联，仍需分别核对其事实与适用范围。' : entity.identityStrength === 'SOURCE_LOCAL' ? '仅表示这一原文范围的主体；即使名称相同，也未确认是同一个人或企业。' : '尚未明确身份关联依据，不能按名称推定为同一主体。') + '</p><div class="fact-identifiers">' + maskedIdentifiers(entity.identifiers) + '</div><button type="button" class="text-button" data-action="fact-entity" data-id="' + escape(entity.id) + '">查看关联事实（' + ids.length + ' 条）</button></article>';
}
function renderFactsContent() {
  const ledger = state.facts, target = $('facts-content'); if (!ledger || !target) return;
  const opened = new Set([...target.querySelectorAll('details[open][data-ledger-section]')].map(item => item.dataset.ledgerSection)), sourceOpened = new Set([...target.querySelectorAll('.fact-card')].filter(item => item.querySelector('.fact-sources')?.open).map(item => item.dataset.factId)); state.factEvidenceRefs = [];
  const query = state.factQuery.trim().toLowerCase(), focused = new Set(state.factFocus), filtered = ledger.occurrences.filter(item => (state.factKind === 'ALL' || item.kind === state.factKind) && (!focused.size || focused.has(item.id)) && (!query || [item.subject, item.field, item.rawValue, item.value, item.unit, item.basis].map(factValue).join(' ').toLowerCase().includes(query)));
  const size = 20, lastPage = Math.max(0, Math.ceil(filtered.length / size) - 1); state.factPage = Math.max(0, Math.min(state.factPage, lastPage));
  const shown = filtered.slice(state.factPage * size, (state.factPage + 1) * size), entityMap = new Map(ledger.entities.map(item => [item.id, item])), subjectName = value => entityMap.get(value)?.displayName || text(value) || '未明确主体';
  const groups = (name, title, rows, render) => '<details class="ledger-section" data-ledger-section="' + name + '"><summary>' + title + ' · ' + rows.length + '</summary><div class="ledger-rows">' + rows.slice(0, state.factLimits[name]).map(render).join('') + '</div>' + (rows.length > state.factLimits[name] ? '<button type="button" class="button secondary small" data-action="more-fact-group" data-group="' + name + '">再显示 20 条</button>' : '') + (rows.length ? '' : '<p class="data-note">当前账本未记录此类信息，不能解释为原文件不存在。</p>') + '</details>';
  target.innerHTML = '<div class="facts-count"><span>' + filtered.length + ' / ' + ledger.occurrences.length + ' 条事实' + (filtered.length > size ? ' · 第 ' + (state.factPage + 1) + ' / ' + (lastPage + 1) + ' 页' : '') + '</span>' + (focused.size ? '<button type="button" class="text-button" data-action="clear-fact-focus">清除关联范围筛选</button>' : '') + '</div><div class="fact-cards">' + (shown.map(factCard).join('') || '<div class="empty"><h3>' + (ledger.occurrences.length ? '没有符合筛选的事实记录' : '本次账本尚无事实记录') + '</h3><p>' + (ledger.occurrences.length ? '请调整事实种类、关键词或关联范围。' : '可能尚未提取、尚未用于本次核验，或该历史记录未保存账本；这不代表原文件中不存在相关事实。') + '</p></div>') + '</div>' + (filtered.length > size ? '<div class="facts-pagination"><button class="button secondary small" data-action="facts-prev" ' + (state.factPage === 0 ? 'disabled' : '') + '>上一页</button><button class="button secondary small" data-action="facts-next" ' + (state.factPage === lastPage ? 'disabled' : '') + '>下一页</button></div>' : '') + groups('conflicts', '待复核冲突', ledger.conflicts, item => '<article class="ledger-row conflict"><div class="fact-card-head"><h4>' + escape(subjectName(item.entityId)) + ' · ' + escape(item.field || '字段待明确') + '</h4><span class="pill wait">待复核</span></div><p>' + escape(item.reason || '相关事实存在差异，需结合原文和适用范围核对。') + '</p><button type="button" class="text-button" data-action="fact-conflict" data-id="' + escape(item.id) + '">对照相关事实（' + array(item.occurrenceIds).length + ' 条）</button></article>') + groups('entities', '主体与身份范围', ledger.entities, factEntityCard) + groups('relations', '关系及证明状态', ledger.relations, item => '<article class="ledger-row"><div class="fact-card-head"><h4>' + escape(subjectName(item.subject)) + ' → ' + escape(subjectName(item.object)) + '</h4><span class="pill ' + (item.resolution === 'PROVEN' ? 'pass' : 'wait') + '">' + (item.resolution === 'PROVEN' ? '来源已证明此关系' : item.resolution === 'CANDIDATE' ? '候选关系 · 尚待核实' : item.resolution === 'SOURCE_BOUND' ? '来源已定位·关系待核' : '关系状态待明确') + '</span></div><p>' + escape(label({ EMPLOYED_BY: '任职于', HAS_CERTIFICATE: '持有证照', APPOINTED_TO: '项目任命', PERSON_ROLE_APPOINTMENT: '项目岗位任命', PERSON_CORPORATE_ROLE: '企业岗位关系', CONTRACT_PARTY: '合同主体' }, item.predicate, '关系类型待核对')) + '</p>' + evidenceButtons(item.sourceRefs, true) + '<p class="data-note">关系记录与具体检查项的业务结论分别核验。</p></article>');
  target.querySelectorAll('details[data-ledger-section]').forEach(item => { item.open = opened.has(item.dataset.ledgerSection); });
  target.querySelectorAll('.fact-card').forEach(item => { item.querySelector('.fact-sources').open = sourceOpened.has(item.dataset.factId); });
}
function amendmentKey(runId, checkpointId) { return `${runId}:${checkpointId}`; }
function amendmentChange(checkpointId) { return array(currentRun()?.requirementChanges).find(change => change.checkpointId === checkpointId && change.requiresConfirmation === true); }
function amendmentDraft(result, change) {
  const key = amendmentKey(state.runId, result.checkpointId), fingerprint = array(change.candidates).map(candidate => candidate.id).sort().join('|');
  let draft = state.amendmentDrafts.get(key);
  if (!draft) {
    draft = { expected: text(result.checkpoint?.expected), candidateId: '', confirmed: false, submitting: false, error: '', fingerprint };
    state.amendmentDrafts.set(key, draft);
    while (state.amendmentDrafts.size > 30) state.amendmentDrafts.delete(state.amendmentDrafts.keys().next().value);
  } else if (draft.fingerprint !== fingerprint) {
    draft.candidateId = ''; draft.confirmed = false; draft.fingerprint = fingerprint; draft.error = '候选原文范围有变化，请重新核对并选择来源。';
  }
  return draft;
}
function renderAmendment(result) {
  const change = amendmentChange(result.checkpointId); if (!change) return '';
  const run = currentRun(), candidates = array(change.candidates), original = text(result.checkpoint?.expected), draft = amendmentDraft(result, change);
  const newer = state.runs.find(item => (item.rootRunId || item.id) === (run.rootRunId || run.id) && Number(item.revision) > Number(run.revision));
  const canSubmit = original && candidates.length && ['COMPLETED', 'FAILED'].includes(run.status) && !newer;
  return `<section class="amendment-panel" aria-labelledby="amendment-title"><h3 id="amendment-title">确认澄清后的规则</h3><p>检测到可能影响本项要求的澄清。请对照全部候选原文填写变更后的完整要求，保留未变更的其他条件。确认后仍需重新核验投标资料。</p><h4>改前规则 · 当前修订冻结的要求</h4><pre class="amendment-before">${escape(original || '尚未读取到本项冻结规则，请等待结果或刷新后再确认。')}</pre><form id="amendment-form" data-run="${escape(run.id)}" data-checkpoint="${escape(result.checkpointId)}" data-revision="${escape(run.revision)}"><fieldset><legend>候选澄清原文 · 选择本次修改的来源</legend>${candidates.map(candidate => { const ev = candidate.evidence || {}, id = candidate.documentId || ev.documentId, doc = runDocuments().find(item => item.id === id); return `<label class="amendment-candidate"><input type="radio" name="candidateId" value="${escape(candidate.id)}" required ${draft.candidateId === candidate.id ? 'checked' : ''} ${draft.submitting ? 'disabled' : ''}><span><strong>${escape(ev.documentName || doc?.name || '候选澄清文件')}</strong><small>${escape(locationLabel(ev))} · V${escape(candidate.documentVersion || doc?.version || 1)}</small><span class="amendment-quote">${escape(ev.quote || '未提供候选原文，暂不能确认。')}</span></span></label>`; }).join('') || '<p>未返回可确认的候选原文，请刷新后重试。</p>'}</fieldset><label class="amendment-expected">改后完整要求<textarea name="expected" rows="6" maxlength="8000" required ${draft.submitting ? 'disabled' : ''}>${escape(draft.expected)}</textarea><small>最多 8000 字。请写完整规则，不能只写“通过”或删除澄清未涉及的条件。</small></label><label class="amendment-confirm"><input type="checkbox" name="confirmed" required ${draft.confirmed ? 'checked' : ''} ${draft.submitting ? 'disabled' : ''}><span>我已核对全部 ${candidates.length} 条候选原文，并在完整要求中保留其他仍适用条件。</span></label>${newer ? `<div class="notice warning">已有较新修订，请在最新版本确认。<button type="button" class="text-button" data-action="open-run" data-id="${escape(newer.id)}">查看较新修订</button></div>` : !['COMPLETED', 'FAILED'].includes(run.status) ? '<p class="data-note">可先核对和填写，当前任务结束后才能建立新修订。</p>' : ''}<div class="amendment-error" role="alert">${escape(draft.error)}</div><button type="submit" class="button primary" ${!canSubmit || draft.submitting ? 'disabled' : ''}>${draft.submitting ? '正在提交规则修订…' : '确认新规则并创建修订'}</button><small class="amendment-footnote">沿用当前资料集合、参考日期和阶段，保留原修订及其结论。</small></form></section>`;
}
function captureAmendmentDraft(form) {
  if (!form || form.id !== 'amendment-form') return;
  const draft = state.amendmentDrafts.get(amendmentKey(form.dataset.run, form.dataset.checkpoint)); if (!draft || draft.submitting) return;
  draft.expected = form.querySelector('[name="expected"]').value;
  draft.candidateId = form.querySelector('[name="candidateId"]:checked')?.value || '';
  draft.confirmed = form.querySelector('[name="confirmed"]').checked;
}
async function submitAmendment(event) {
  const form = event.target; if (!(form instanceof HTMLFormElement) || form.id !== 'amendment-form') return;
  event.preventDefault(); captureAmendmentDraft(form);
  const originRun = form.dataset.run, checkpointId = form.dataset.checkpoint, key = amendmentKey(originRun, checkpointId), draft = state.amendmentDrafts.get(key);
  if (!draft || draft.submitting) return;
  const epoch = state.projectEpoch, change = amendmentChange(checkpointId), result = results().find(item => item.checkpointId === checkpointId);
  try {
    if (originRun !== state.runId || !change || !text(result?.checkpoint?.expected)) throw new Error('当前规则快照尚未读取完整，请刷新后重试。');
    const candidates = array(change.candidates), candidate = candidates.find(item => item.id === draft.candidateId), evidence = candidate?.evidence;
    if (!candidate || !evidence?.documentId || !evidence.quote || !runDocuments().some(doc => doc.id === evidence.documentId)) throw new Error('请选择本次冻结资料中有完整原文的候选来源。');
    if (evidence.quote.length > 4000) throw new Error('候选原文超过可确认范围，请联系项目维护人员核对规则来源。');
    if (!draft.expected.trim() || draft.expected.trim().length > 8000) throw new Error('请填写不超过 8000 字的完整要求。');
    if (!draft.confirmed) throw new Error('请核对全部候选原文，并确认保留其他适用条件。');
    const expectedRevision = Number(form.dataset.revision); if (!Number.isInteger(expectedRevision) || expectedRevision < 1) throw new Error('修订编号未完整读取，请刷新后重试。');
    const body = { expectedRevision, acceptedCheckpointOverrides: [{ checkpointId, expected: draft.expected.trim(), source: { documentId: evidence.documentId, page: Number(evidence.page), quote: evidence.quote }, candidateIds: candidates.map(item => item.id), confirmed: true }] };
    draft.submitting = true; draft.error = ''; renderDetail();
    const response = await api(`/api/verification-runs/${enc(originRun)}/revisions`, { method: 'POST', body });
    state.amendmentDrafts.delete(key);
    if (epoch !== state.projectEpoch) { notify('规则修订已提交，请返回原项目查看新核验记录。'); return; }
    const run = response.run || response; if (!run.id) throw new Error('规则修订已提交但未返回编号，请刷新记录，不要重复提交。');
    mergeRun(run); if (state.runId === originRun) await selectRun(run.id);
    notify('规则变更已确认并建立新修订，业务结论由重新核验产生。');
  } catch (error) {
    draft.error = error.message; if (epoch === state.projectEpoch && state.runId === originRun) { state.amendmentDrafts.set(key, draft); renderDetail(); } else notify(error.message, true);
  } finally { draft.submitting = false; if (epoch === state.projectEpoch && state.runId === originRun) renderDetail(); }
}
function renderMetrics(metrics) {
  if (!metrics || typeof metrics !== 'object') return '';
  const entries = [['规则计算得分', metrics.calculatedScore ?? metrics.ruleScore], ['质量预评建议分', metrics.preassessmentScore ?? metrics.suggestedScore], ['预评分下限', metrics.scoreMin], ['预评分上限', metrics.scoreMax], ['正式评审结果', metrics.formalScore]];
  const present = entries.filter(([, value]) => text(value));
  return present.length ? `<h3 class="section-heading">评分信息</h3><dl class="meta-grid">${present.map(([name, value]) => `<dt>${escape(name)}</dt><dd>${escape(value)}</dd>`).join('')}</dl>` : '';
}
function renderHistory() {
  const open = $('history').querySelector('details')?.open;
  $('history').innerHTML = state.runs.length ? `<details class="history"><summary>核验修订与历史记录 · ${state.runs.length} 次</summary>${state.runs.map(run => `<div class="history-row"><div><strong>${escape(runTitle(run))}</strong><small>${escape(stages[run.evaluationStage] || '阶段未记录')}${run.parentRunId ? ' · 基于前次记录续跑' : ''}${run.id === state.runId ? ' · 当前查看' : ''}</small></div><button class="button secondary small" data-action="open-run" data-id="${escape(run.id)}" ${run.id === state.runId ? 'disabled' : ''}>查看</button></div>`).join('')}</details>` : '';
  const details = $('history').querySelector('details'); if (open && details) details.open = true;
}

function openDialog(title, description, body, footer = '', className = '') {
  state.dialogEpoch++; state.preview = null;
  const dialog = $('dialog'); dialog.className = className;
  dialog.innerHTML = `<div class="dialog-header"><div><h2 id="dialog-title">${escape(title)}</h2><p>${escape(description)}</p></div><button class="dialog-close" data-action="close-dialog" aria-label="关闭对话框">×</button></div>${body}<div class="form-error" role="alert"></div>${footer}`;
  if (!dialog.open) dialog.showModal();
}
function closeDialog() { if ($('dialog').open) $('dialog').close(); state.dialogEpoch++; state.preview = null; }
function formError(error) { const target = $('dialog').querySelector('.form-error'); if (target) target.textContent = error.message || String(error); else notify(error.message || String(error), true); }
function footer(formId, title) { return `<div class="dialog-footer"><button type="button" class="button secondary" data-action="close-dialog">取消</button><button type="submit" form="${formId}" class="button primary">${escape(title)}</button></div>`; }
function documentOptions(list, selected, empty = '不指定') { return `<option value="">${escape(empty)}</option>${list.map(doc => `<option value="${escape(doc.id)}" ${doc.id === selected ? 'selected' : ''}>${escape(docName(doc))}</option>`).join('')}`; }
function runForm(revision = false) {
  if (!state.project) return;
  const run = revision ? currentRun() : null; if (revision && !run) return;
  const id = revision ? 'revision-form' : 'create-form';
  const tenderId = run?.tenderDocumentId || state.project.project?.activeTenderId || documents().filter(doc => doc.role === 'TENDER').at(-1)?.id;
  const selectedDocs = new Set(array(run?.documentIds).length ? run.documentIds : runDocuments().filter(doc => doc.role !== 'TENDER').map(doc => doc.id));
  const bidDocs = documents().filter(doc => doc.id !== tenderId);
  if (!revision && !selectedDocs.size) { const lastBid = bidDocs.filter(doc => doc.role === 'BID').at(-1); if (lastBid) selectedDocs.add(lastBid.id); }
  openDialog(revision ? '修订资料并继续核验' : '新建自动核验', revision ? '建立新修订，保留当前结论和引用。请选择本轮要使用的完整资料集合。' : '确认资料与核验阶段后开始。此操作会启动已配置的核验服务。', `<form id="${id}" class="dialog-body" data-run="${escape(run?.id || '')}" data-revision="${escape(run?.revision ?? '')}"><div class="subtle-box">${revision ? '未选中的资料将不进入新修订。已上传的补充资料可与原投标文件一起选入。' : '多个投标分册应同时选入。系统按冻结的资料集合逐条件核验，不会用今天替代未知的招标截止日期。'}${state.config.semanticEnabled ? '<p>相关候选原文会发送至已配置的模型服务。</p>' : ''}</div>${!revision ? `<label>采用已有检查快照（可选）<select name="sourceRunId"><option value="">采用当前已确认检查项</option>${array(state.project.runs).filter(item => item.status === 'COMPLETED').map(item => `<option value="${escape(item.id)}" ${item.id === params.get('sourceRunId') ? 'selected' : ''}>检查于 ${escape(date(item.createdAt))}</option>`).join('')}</select></label><label>招标依据<select name="tenderDocumentId" required>${documentOptions(documents().filter(doc => doc.role === 'TENDER'), tenderId, '选择招标文件')}</select></label>` : `<div class="subtle-box">招标依据：${escape(documents().find(doc => doc.id === tenderId)?.name || '沿用当前修订的招标依据')}<p>本表单沿用冻结的招标版本。变更招标版本请新建核验，或上传招标澄清文件。</p></div>`}<strong>投标与补充资料集合</strong><small>可同时选择投标分册与招标澄清文件，主招标依据单独冻结。</small><div class="check-list">${bidDocs.map(doc => `<label><input type="checkbox" name="documentIds" value="${escape(doc.id)}" ${selectedDocs.has(doc.id) ? 'checked' : ''}><span>${escape(docName(doc))}<small>${escape(roleLabel(doc.role))} · ${Number(doc.pageCount ?? doc.pages?.length) || 0} 个原文位置 · ${escape(date(doc.createdAt))}</small></span></label>`).join('') || '<p class="data-note">暂无投标资料。请先返回项目工作台上传文件。</p>'}</div><div class="form-grid"><label>核验阶段<select name="evaluationStage">${options(stages, run?.evaluationStage || 'PRE_SUBMISSION')}</select></label><label>参考日期（可选）<input type="date" name="referenceDate" value="${escape(run?.referenceDate || '')}"></label><label class="field-full">参考日期的来源文件（填日期时必选）<select name="referenceDateSourceDocumentId">${documentOptions(documents().filter(doc => doc.role === 'TENDER'), run?.referenceDateSourceDocumentId, '选择日期依据')}</select><small>日期应来自原条款或有效澄清；系统会核验出处。</small></label></div></form>`, footer(id, revision ? '保存新修订并续跑' : '开始自动核验'));
  const sourceSelector = $('dialog').querySelector('[name="sourceRunId"]'); if (sourceSelector?.value) sourceSelector.dispatchEvent(new Event('change', { bubbles: true }));
}
function supplementForm() {
  if (!currentRun()) return;
  openDialog('补充核验资料', '文件解析完成后，服务端自动建立新修订并继续核验；原报告保留。', `<form id="supplement-form" class="dialog-body" data-run="${escape(state.runId)}"><label>选择文件<input type="file" name="file" required accept="${allowedFormats().map(ext => `.${ext}`).join(',')}"><small>支持 ${escape(allowedFormats().map(ext => ext.toUpperCase()).join(' / '))}，单文件不超过 ${maxSize()} MB。</small></label><div class="form-grid"><label>文件归属<select name="role">${options({ BID: '投标资料', TENDER: '招标依据' }, 'BID')}</select></label><label>资料用途<select name="purpose">${options(purposes, 'ATTACHMENT')}</select></label></div><div class="subtle-box">请上传与当前项目、主体和时点对应的资料。技术故障或规则歧义不一定能通过补文件解决。${state.config.ocrEnabled ? '<p>含图像的文件会使用 OCR，待识别图像将发送至已配置的识别服务。</p>' : ''}</div></form>`, footer('supplement-form', '上传并自动续跑'));
}
function externalForm() {
  if (!currentRun()) return;
  openDialog('登记外部查询记录', '登记来源与查询事实，由服务端核验；此操作不能直接设定业务通过。', `<form id="external-form" class="dialog-body" data-run="${escape(state.runId)}"><div class="notice warning">请先上传对应的官方查询导出、平台回执或其他可追溯证明，再填写来源。普通声明不等于官方记录。</div><div class="form-grid"><label>核验主体<input name="subject" required maxlength="200" placeholder="企业、人员或项目名称"></label><label>查询事项<select name="kind" required>${options(externalKinds, 'REGISTRATION')}</select></label><label class="field-full">来源机构或系统<input name="source" required maxlength="500" placeholder="填写实际来源名称或可追溯地址"></label><label>查询时间<input type="datetime-local" name="queriedAt" required></label><label>记录适用时间<input type="date" name="effectiveAt" required><small>查询时间不一定等于事实适用时间。</small></label><label class="field-full">证明文件<select name="documentId" required>${documentOptions(documents(), '', '选择已上传的证明文件')}</select></label><label>原文位置编号<input name="page" type="number" min="1" step="1" value="1" required><small>PDF 填页号，Word 填内容块或段落号。</small></label><label class="field-full">与核验有关的原文<textarea name="quote" required maxlength="4000" placeholder="摘录可在所选文件对应位置找到的原文"></textarea></label></div></form>`, footer('external-form', '登记并交由系统核验'));
}

function pricingRow(index, list) {
  return '<fieldset class="pricing-entry"><legend>有效投标人 <span class="pricing-row-number">' + index + '</span></legend><div class="form-grid"><label class="field-full">企业完整名称<input name="bidder" required maxlength="300" autocomplete="off"></label><label>算术修正后的不含税评标价<input name="price" required inputmode="decimal" maxlength="100" placeholder="如 1000000"></label><label>金额单位<select name="unit">' + options({ 元: '元', 万元: '万元' }, '元') + '</select></label><label class="field-full">该价格的来源文件<select name="documentId" required>' + documentOptions(list, '', '选择本轮资料中的证明文件') + '</select></label><label>原文位置编号<input name="page" type="number" min="1" step="1" value="1" required></label><label class="field-full">企业名称、评标价与单位的原文<textarea name="quote" required maxlength="4000" placeholder="如：甲公司 不含税评标价：1000000元"></textarea><small>逐字摘录同一明确行或紧邻标签中的企业、评标价及单位。名义报价不能代替经确认的评标价。</small></label></div><button type="button" class="text-button" data-action="remove-price-row">删除此投标人</button></fieldset>';
}
function pricingForm() {
  const run = currentRun(), result = results().find(item => item.checkpointId === state.selected);
  if (!run || !priceGaps(result)) return;
  const newer = state.runs.find(item => (item.rootRunId || item.id) === (run.rootRunId || run.id) && Number(item.revision) > Number(run.revision));
  if (newer) { notify('已有较新修订，请先选择最新核验记录再登记价格。', true); return; }
  const ids = new Set([...array(run.documentIds), run.tenderDocumentId].filter(Boolean)), list = runDocuments().filter(doc => ids.has(doc.id));
  openDialog('登记有效评标价', '登记计算输入后创建新修订；系统核对来源并按规则计算，不直接输入业务结论或正式得分。', '<form id="pricing-form" class="dialog-body" data-run="' + escape(run.id) + '" data-revision="' + escape(run.revision) + '"><div class="notice warning">仅可引用本轮资料集合中的原件。缺少有效名单、算术修正或评标价依据时，请先关闭此表单并“补充资料”，等待新修订后再登记。<p>应登记全部有效投标人，不能只填目标投标人。未提供完整数据时不计算价格分。</p></div><label>目标投标人的完整名称<input name="targetBidder" required maxlength="300" placeholder="必须与下方有效投标人名称完全一致"></label><fieldset class="pricing-roster"><legend>全部有效评标名单的原文依据</legend><label>名单来源文件<select name="rosterDocumentId" required>' + documentOptions(list, '', '选择本轮资料中的有效名单') + '</select></label><label>原文位置编号<input name="rosterPage" type="number" min="1" step="1" value="1" required><small>PDF 填页号，Word 填内容块或段落号。</small></label><label>完整有效名单原文<textarea name="rosterQuote" required maxlength="4000" placeholder="逐字摘录全部有效评标名单及其适用范围"></textarea><small>保留“有效评标名单”等范围表述，名单中的企业须与下方逐行登记一致。</small></label></fieldset><h3 class="section-heading">全部有效投标人的评标价</h3><div id="pricing-entries">' + pricingRow(1, list) + pricingRow(2, list) + '</div><button type="button" class="button secondary small" data-action="add-price-row">添加投标人</button><div class="pricing-confirmations"><label><input type="checkbox" name="rosterComplete" required><span>已登记原文中的全部有效投标人，无遗漏或无效主体。</span></label><label><input type="checkbox" name="arithmeticCorrectionsConfirmed" required><span>各评标价均已完成适用的算术修正，并有原文依据。</span></label><label><input type="checkbox" name="excludingTax" required><span>所有评标价均为不含税口径，来源可核对。</span></label><label><input type="checkbox" name="confirmed" required><span>已核对名单、金额、单位及出处，同意以这些资料创建新修订并进行规则计算。</span></label></div><div class="subtle-box">页面不提供正式得分或业务结论输入。规则计算分不代表正式评审结果；原核验记录继续保留。</div></form>', footer('pricing-form', '确认输入并创建修订'), 'pricing-dialog');
  $('pricing-form').pricingDocuments = list;
}
async function submitPricing(event) {
  const form = event.target; if (!(form instanceof HTMLFormElement) || form.id !== 'pricing-form') return;
  event.preventDefault(); if (form.dataset.busy === 'true') return;
  const epoch = state.projectEpoch, dialogEpoch = state.dialogEpoch, originRun = form.dataset.run, values = new FormData(form), button = $('dialog').querySelector('button[form="pricing-form"]');
  try {
    if (originRun !== state.runId || !['COMPLETED', 'FAILED'].includes(currentRun()?.status)) throw new Error('请在当前核验记录处理结束后登记评标价。');
    const expectedRevision = Number(form.dataset.revision); if (!Number.isInteger(expectedRevision) || expectedRevision < 1) throw new Error('修订编号未读取完整，请刷新后重试。');
    const list = array(form.pricingDocuments), source = (documentId, pageValue, quoteValue) => {
      const doc = list.find(item => item.id === documentId), page = Number(pageValue), quote = String(quoteValue || '').trim();
      if (!doc || !Number.isInteger(page) || page < 1 || (Number(doc.pageCount) > 0 && page > Number(doc.pageCount))) throw new Error('请选择本轮资料中的文件，并填写该文件有效的原文位置。');
      if (!quote || quote.length > 4000) throw new Error('每处来源需填写不超过 4000 字的原文。');
      return { documentId, page, quote };
    };
    const targetBidder = String(values.get('targetBidder') || '').trim();
    const rosterSource = source(String(values.get('rosterDocumentId') || ''), values.get('rosterPage'), values.get('rosterQuote'));
    const entries = [...form.querySelectorAll('.pricing-entry')].map(row => {
      const value = name => row.querySelector('[name="' + name + '"]').value.trim(), bidder = value('bidder'), price = value('price'), unit = value('unit');
      if (!bidder || !/^\d+(?:\.\d+)?$/.test(price) || !/[1-9]/.test(price) || !['元', '万元'].includes(unit)) throw new Error('请逐行填写企业名称、正数评标价和元或万元单位；金额无需千分位分隔符。');
      return { bidder, price, unit, source: source(value('documentId'), value('page'), value('quote')) };
    });
    if (!entries.length || new Set(entries.map(entry => entry.bidder)).size !== entries.length) throw new Error('有效投标人不能为空，也不能重复登记同一企业。');
    if (!targetBidder || !entries.some(entry => entry.bidder === targetBidder)) throw new Error('目标投标人的名称须与已登记的有效投标人名称完全一致。');
    if (!['confirmed', 'rosterComplete', 'arithmeticCorrectionsConfirmed', 'excludingTax'].every(name => values.get(name) === 'on')) throw new Error('请逐项核对并确认全部有效名单、算术修正、不含税口径和原文来源。');
    const body = { expectedRevision, pricingData: { confirmed: true, rosterComplete: true, arithmeticCorrectionsConfirmed: true, taxBasis: 'EXCLUDING_TAX', targetBidder, rosterSource, entries } };
    form.dataset.busy = 'true'; if (button) button.disabled = true; formError('');
    const response = await api('/api/verification-runs/' + enc(originRun) + '/revisions', { method: 'POST', body });
    if (epoch !== state.projectEpoch) { notify('价格资料已提交，请返回原项目查看新修订。'); return; }
    const run = response.run || response; if (!run.id) throw new Error('价格资料已提交但未返回修订编号，请刷新记录，不要重复提交。');
    if (dialogEpoch === state.dialogEpoch) closeDialog(); mergeRun(run); if (state.runId === originRun) await selectRun(run.id);
    notify('价格资料已登记，系统将核对来源并计算规则分；这不代表正式评审得分。');
  } catch (error) { if (epoch === state.projectEpoch && dialogEpoch === state.dialogEpoch && $('dialog').open) formError(error); else notify(error.message, true); }
  finally { form.dataset.busy = 'false'; if (button?.isConnected) button.disabled = false; }
}
async function submitForm(event) {
  const form = event.target;
  if (!(form instanceof HTMLFormElement) || !['create-form', 'revision-form', 'supplement-form', 'external-form'].includes(form.id)) return;
  event.preventDefault(); if (form.dataset.busy === 'true') return;
  const epoch = state.projectEpoch, dialogEpoch = state.dialogEpoch, projectId = state.projectId, originRun = form.dataset.run, values = new FormData(form);
  const button = $('dialog').querySelector(`button[form="${form.id}"]`);
  form.dataset.busy = 'true'; if (button) button.disabled = true; const error = $('dialog').querySelector('.form-error'); if (error) error.textContent = '';
  try {
    if (form.id === 'create-form' || form.id === 'revision-form') {
      const documentIds = values.getAll('documentIds').map(String);
      if (!documentIds.length) throw new Error('请至少选择一份投标资料。');
      if (!documentIds.some(id => documents().find(doc => doc.id === id)?.role === 'BID')) throw new Error('资料集合中至少需要一份投标文件，招标澄清不能代替投标文件。');
      if (documentIds.length > 24) throw new Error('单次核验最多选择 24 份资料，请按项目分批。');
      const referenceDate = String(values.get('referenceDate') || ''), source = String(values.get('referenceDateSourceDocumentId') || '');
      if (referenceDate && !source) throw new Error('填写参考日期时，请选择其原文来源文件。');
      if (source && !referenceDate) throw new Error('选择日期来源文件时，请同时填写对应参考日期。');
      const tenderId = values.get('tenderDocumentId') || currentRun()?.tenderDocumentId;
      if (referenceDate && source !== tenderId && !documentIds.includes(source)) throw new Error('日期来源的澄清文件需同时选入本轮资料集合。');
      const sourceRun = array(state.project.runs).find(run => run.id === values.get('sourceRunId'));
      if (sourceRun?.tenderDocumentId && sourceRun.tenderDocumentId !== tenderId) throw new Error('采用历史检查快照时，招标依据须使用该快照冻结的版本。');
      const body = { documentIds, evaluationStage: values.get('evaluationStage'), referenceDate: referenceDate || null, referenceDateSourceDocumentId: source || null };
      if (form.id === 'create-form') { body.tenderDocumentId = values.get('tenderDocumentId'); if (values.get('sourceRunId')) body.sourceRunId = values.get('sourceRunId'); }
      else { const revision = Number(form.dataset.revision); if (!Number.isInteger(revision) || revision < 0) throw new Error('当前修订版本未读取完整，请刷新记录后重试。'); body.expectedRevision = revision; }
      const created = await api(form.id === 'create-form' ? `/api/projects/${enc(projectId)}/verification-runs` : `/api/verification-runs/${enc(originRun)}/revisions`, { method: 'POST', body });
      if (epoch !== state.projectEpoch) { notify('核验请求已提交。请返回原项目查看新记录。'); return; }
      if (dialogEpoch === state.dialogEpoch) closeDialog();
      const run = created.run || created; if (!run.id) throw new Error('请求已提交，但未返回核验编号。请刷新记录，不要重复提交。');
      mergeRun(run); await selectRun(run.id); notify('核验已开始，新记录会逐项显示证据与结论。');
    } else if (form.id === 'supplement-form') {
      const file = values.get('file');
      if (!(file instanceof File) || !file.size) throw new Error('请选择非空文件。');
      const extension = file.name.split('.').at(-1).toLowerCase(); if (!allowedFormats().includes(extension)) throw new Error('此文件格式暂不支持，请选择页面列出的格式。');
      if (file.size > maxSize() * 1024 * 1024) throw new Error(`文件超过 ${maxSize()} MB，请拆分后上传。`);
      values.set('runId', originRun); const task = await api(`/api/projects/${enc(projectId)}/verification-inputs`, { method: 'POST', body: values });
      if (epoch !== state.projectEpoch) { notify('文件已提交解析。请返回原项目查看续跑记录。'); return; }
      if (dialogEpoch === state.dialogEpoch) closeDialog();
      if (!task.id) throw new Error('上传已提交，但未返回解析任务编号。请刷新记录，不要重复上传。');
      state.tasks.set(task.id, { ...task, baseRunId: originRun, autoSelect: true, name: file.name });
      if (task.status === 'SUCCEEDED') state.awaitingRevision = { baseRunId: originRun, requestId: task.inputRequestId, autoSelect: true, attempts: 0 };
      resetPolling(); renderActivity(); schedulePoll(); notify('文件已上传，解析完成后将自动续跑。');
    } else {
      const body = Object.fromEntries([...values].map(([key, value]) => [key, String(value).trim()])); body.runId = originRun; body.page = Number(body.page);
      const queriedAt = new Date(body.queriedAt); if (Number.isNaN(queriedAt.valueOf()) || !/^\d{4}-\d{2}-\d{2}$/.test(body.effectiveAt)) throw new Error('请填写有效的查询时间和适用日期。'); body.queriedAt = queriedAt.toISOString();
      const saved = await api(`/api/projects/${enc(projectId)}/verification-external-records`, { method: 'POST', body });
      if (epoch !== state.projectEpoch) { notify('外部记录已登记，请返回原项目查看。'); return; }
      if (dialogEpoch === state.dialogEpoch) closeDialog();
      if (saved.run?.id) { mergeRun(saved.run); if (state.runId === originRun) await selectRun(saved.run.id); }
      else { state.awaitingRevision = { baseRunId: originRun, attempts: 0 }; resetPolling(); renderActivity(); schedulePoll(); }
      notify('记录已登记，来源与适用范围仍需核验。');
    }
  } catch (error) { if (epoch === state.projectEpoch && dialogEpoch === state.dialogEpoch && $('dialog').open) formError(error); else notify(error.message, true); }
  finally { form.dataset.busy = 'false'; if (button?.isConnected) button.disabled = false; }
}

async function loadDocument(id) {
  if (state.cache.has(id)) { const doc = state.cache.get(id); state.cache.delete(id); state.cache.set(id, doc); return doc; }
  if (state.loads.has(id)) return state.loads.get(id);
  const epoch = state.projectEpoch;
  const request = api(`/api/documents/${enc(id)}`, { signal: state.controller?.signal }).then(doc => {
    if (epoch !== state.projectEpoch) return doc;
    state.cache.set(id, doc);
    const size = () => [...state.cache.values()].reduce((sum, item) => sum + array(item.pages).reduce((total, page) => total + String(page.text || '').length, 0), 0);
    while (state.cache.size > 4 || (state.cache.size > 1 && size() > 2000000)) state.cache.delete(state.cache.keys().next().value);
    return doc;
  }).finally(() => { if (state.loads.get(id) === request) state.loads.delete(id); });
  state.loads.set(id, request); return request;
}
async function openEvidence(evidence) {
  if (!evidence || !runDocuments().some(doc => doc.id === evidence.documentId)) return;
  openDialog('查看原文依据', '文档位置与引用原文一起展示，识别文字需结合原件核对。', '<div class="dialog-body"><div id="preview-content"><span class="spinner"></span>正在读取原文…</div></div>', '', 'preview');
  const epoch = state.dialogEpoch, projectEpoch = state.projectEpoch;
  state.preview = { evidence, documentId: evidence.documentId, page: Number(evidence.page) || 1 };
  try {
    const doc = await loadDocument(evidence.documentId); if (epoch !== state.dialogEpoch || projectEpoch !== state.projectEpoch || !$('dialog').open) return;
    state.preview.document = doc; renderPreview();
  } catch (error) { if (epoch === state.dialogEpoch && error.name !== 'AbortError') $('preview-content').innerHTML = `<div class="notice danger">${escape(error.message)}</div><button class="button secondary" data-action="retry-evidence">重试加载原文</button>`; }
}
function renderPreview() {
  const preview = state.preview; if (!preview?.document || !$('preview-content')) return;
  const doc = preview.document, page = array(doc.pages).find(item => Number(item.number ?? item.page) === preview.page);
  const pages = array(doc.pages), hasImage = page && ((page.locationType === 'PAGE' && /\.pdf$/i.test(doc.name || '')) || /\.(png|jpe?g)$/i.test(doc.name || '') || page.locationType === 'IMAGE' || /WORD.*OCR|IMAGE_OCR/i.test(page.parser || ''));
  $('preview-content').innerHTML = `<div class="preview-controls"><strong>${escape(docName(doc))}</strong><label>原文位置<select id="preview-page">${pages.map(item => { const number = Number(item.number ?? item.page); return `<option value="${number}" ${number === preview.page ? 'selected' : ''}>${escape(locationLabel({ ...item, page: number }))}</option>`; }).join('')}</select></label><a class="text-button" href="/api/documents/${enc(doc.id)}/raw" download>下载原文件</a></div>${preview.evidence.quote ? `<div class="preview-label">所选证据摘录</div><div class="quote-focus">${escape(preview.evidence.quote)}</div>` : ''}${page ? `<div class="preview-layout${hasImage ? '' : ' text-only'}">${hasImage ? '<div><div class="preview-label">原始图像</div><div id="image-holder"></div></div>' : ''}<div><div class="preview-label">${/OCR/i.test(page.parser || '') ? 'OCR 转录文本 · 不代表签章已验证' : '原文文本'}</div><div class="source-text">${escape(page.text || '此位置没有可读文本，请核对原图或原文件。')}</div>${page.warning || array(page.warnings).length ? `<div class="notice warning">${[page.warning, ...array(page.warnings)].filter(Boolean).map(escape).join('<br>')}</div>` : ''}</div></div>` : '<div class="notice warning">未找到该原文位置。请核对文档版本或下载原文件。</div>'}`;
  if (hasImage) loadPreviewImage();
  const extra = document.createElement('section'); extra.className = 'source-structure'; extra.id = 'source-structure';
  const isWord = /\.docx$/i.test(doc.name || '');
  extra.innerHTML = `<div class="preview-controls">${isWord ? '<button class="button secondary small" data-action="word-layout">查看 Word 整页渲染</button>' : ''}${page && /OCR/i.test(page.parser || '') ? '<button class="button secondary small" data-action="ocr-layout">查看表格与文字位置</button>' : ''}</div><div id="structure-content"></div>`;
  $('preview-content').append(extra);
  if (preview.structureKind === 'ocr' && preview.structure?.location === preview.page) renderStructure(preview.structure);
  if (preview.structureKind === 'word' && preview.rendering) renderWordPages(preview.rendering);
}

const renderStates = { LICENSE_REQUIRED: 'Word 整页渲染需要有效许可证，当前仍使用原生段落与表格解析。', LICENSE_INVALID: '许可证未通过验证，当前保留原生解析。', FONT_SUBSTITUTION: '渲染发生字体替换，页面映射尚不能作为确定依据。', RESOURCE_BLOCKED: '文件包含无法安全加载的外部资源，当前保留原生解析。', TIMEOUT: '整页渲染超过本次处理时限，可稍后重试。', DISABLED: 'Word 整页渲染尚未启用。' };
async function loadSourceStructure(kind) {
  const preview = state.preview, holder = $('structure-content'); if (!preview || !holder) return;
  const epoch = state.dialogEpoch, page = preview.page, docId = preview.documentId;
  preview.structureKind = kind;
  const requestId = preview.structureRequest = (preview.structureRequest || 0) + 1;
  holder.innerHTML = '<p class="data-note"><span class="spinner"></span>正在读取文档结构…</p>';
  try {
    const data = await api(kind === 'word' ? `/api/documents/${enc(docId)}/word-layout` : `/api/documents/${enc(docId)}/pages/${page}/layout`);
    if (epoch !== state.dialogEpoch || state.preview !== preview || preview.page !== page || preview.structureKind !== kind || preview.structureRequest !== requestId || holder !== $('structure-content')) return;
    if (data.documentId !== docId || (data.documentHash && data.documentHash !== preview.document.sha256)) throw new Error('文档结构与当前文件版本不一致，请重新读取。');
    if (kind === 'word') { preview.rendering = data; renderWordPages(data); }
    else { if (Number(data.location) !== page) throw new Error('文档结构位置不一致，请重新读取。'); preview.structure = data; renderStructure(data); }
  } catch (error) { if (epoch === state.dialogEpoch && state.preview === preview && preview.page === page && preview.structureRequest === requestId && holder === $('structure-content')) holder.innerHTML = `<div class="notice danger">${escape(error.message)}</div>`; }
}
function formatStructureBox(box) { return box && ['x', 'y', 'width', 'height'].every(key => Number.isFinite(box[key])) ? `左 ${box.x}，上 ${box.y}，宽 ${box.width}，高 ${box.height}` : '无独立位置框'; }
function renderStructure(data) {
  const holder = $('structure-content'); if (!holder) return;
  const layout = data.layout || {}, frames = array(layout.frames), tables = array(layout.tables), boxes = array(layout.textBoxes);
  const allCells = tables.flatMap(table => array(table.cells).map(cell => ({ ...cell, table })));
  holder.innerHTML = `<h3>当前原文位置的 OCR 结构</h3><p class="data-note">${layout.status === 'COMPLETE' ? '结构识别已返回' : '结构识别未完整完成'} · ${frames.length} 个图像区域 · ${tables.length} 个表格 · ${boxes.length} 个文字框。结构返回不等于文字或关联已核验正确。</p>${array(layout.warnings).length ? `<div class="notice warning">${array(layout.warnings).map(escape).join('<br>')}</div>` : ''}${allCells.length ? `<div class="structure-table-scroll"><table class="structure-table"><thead><tr><th>表格 / 行 / 列</th><th>单元格原文</th><th>表头引用</th><th>图像坐标</th></tr></thead><tbody>${allCells.slice(0, 120).map(cell => `<tr><td>${escape(cell.table.id)}<br>${escape(Number(cell.row) + 1)} / ${escape(Number(cell.column) + 1)}</td><td>${escape(cell.text || '')}</td><td>${escape(array(cell.headerCellIds).join('、') || '未建立表头关联')}</td><td>${escape(formatStructureBox(cell.bbox))}</td></tr>`).join('')}</tbody></table></div><p class="data-note">显示 ${Math.min(120, allCells.length)} / ${allCells.length} 个单元格。坐标属于对应送识别图像，不能直接当作 Word 页码。</p>` : '<p class="data-note">该位置没有可展示的结构化表格。仍可查看原文与原图。</p>'}${boxes.length ? `<details><summary>文字框与阅读顺序（最多展示 80 条）</summary>${boxes.slice(0, 80).map(box => `<div class="structure-box"><small>${escape(box.id)} · ${escape(box.frameId)} · ${escape(formatStructureBox(box.bbox))}</small><div>${escape(box.text)}</div></div>`).join('')}</details>` : ''}`;
}
function renderWordPages(data) {
  const preview = state.preview, holder = $('structure-content'); if (!preview || !holder) return;
  if (data.usableForEvidence !== true) { holder.innerHTML = `<div class="notice warning">${escape(renderStates[data.status] || 'Word 整页渲染尚不可用，当前保留原生段落、表格与原文件。')}</div>`; return; }
  const count = Number(data.pageCount);
  if (!Number.isInteger(count) || count < 1 || count > 2000) { holder.innerHTML = '<div class="notice warning">渲染页范围无效，未加载页面。</div>'; return; }
  const matches = array(data.spans).filter(span => Number(span.documentBlock) === preview.page);
  const mapped = [...new Set(matches.flatMap(span => { const start = Number(span.startRenderedPage), end = Number(span.endRenderedPage); return Number.isInteger(start) && Number.isInteger(end) && start > 0 && end >= start && end <= count ? Array.from({ length: end - start + 1 }, (_, i) => start + i) : []; }))];
  preview.renderedPage = mapped.includes(preview.renderedPage) ? preview.renderedPage : mapped[0] || 1;
  holder.innerHTML = `<h3>Word 渲染页面</h3><p class="data-note">${mapped.length ? `当前内容块映射到渲染第 ${mapped.join('、')} 页。` : '当前内容块尚无唯一页面映射，可以浏览渲染副本，但不能把所选页视为该证据的确定位置。'}渲染页与原内容块编号分别保存。</p><div class="preview-controls"><label>渲染页<select id="rendered-page">${Array.from({ length: count }, (_, i) => `<option value="${i + 1}" ${i + 1 === preview.renderedPage ? 'selected' : ''}>第 ${i + 1} 页</option>`).join('')}</select></label><a class="text-button" href="/api/documents/${enc(preview.documentId)}/rendered-pdf" download>下载渲染副本</a></div><div id="rendered-image-holder"></div>`;
  loadRenderedImage();
}
function loadRenderedImage() {
  const preview = state.preview, holder = $('rendered-image-holder'); if (!preview?.rendering || !holder) return;
  const epoch = state.dialogEpoch, page = preview.renderedPage;
  holder.innerHTML = '<p class="data-note"><span class="spinner"></span>正在加载渲染页面…</p>';
  const img = new Image(); img.className = 'source-image'; img.alt = `Word 渲染副本第 ${page} 页`;
  const valid = () => epoch === state.dialogEpoch && state.preview === preview && preview.renderedPage === page && holder === $('rendered-image-holder');
  img.onload = () => { if (valid()) holder.replaceChildren(img); };
  img.onerror = () => { if (valid()) holder.innerHTML = '<div class="notice warning">渲染页面暂时不可用，请重新打开整页渲染或下载原文件。</div>'; };
  img.src = `/api/documents/${enc(preview.documentId)}/rendered-pages/${page}/image`;
}
async function loadPreviewImage() {
  const preview = state.preview, holder = $('image-holder'); if (!preview || !holder) return;
  const epoch = state.dialogEpoch, page = preview.page;
  holder.innerHTML = '<p class="data-note"><span class="spinner"></span>正在加载原图…</p>';
  const image = new Image(); image.className = 'source-image'; image.alt = `${preview.document.name} ${locationLabel({ ...array(preview.document.pages).find(item => Number(item.number ?? item.page) === page), page })}原图`;
  image.addEventListener('load', () => { if (epoch === state.dialogEpoch && state.preview?.page === page && $('image-holder') === holder) holder.replaceChildren(image); });
  image.addEventListener('error', () => { if (epoch === state.dialogEpoch && state.preview?.page === page && $('image-holder') === holder) holder.innerHTML = '<div class="notice danger">原图暂时无法读取，可能是服务繁忙或该位置没有独立图像。请重试或下载原文件。</div><button class="button secondary small" data-action="retry-image">重试原图</button>'; });
  image.src = `/api/documents/${enc(preview.documentId)}/pages/${page}/image`;
}

document.addEventListener('click', event => {
  const target = event.target.closest('[data-action]'); if (!target || target.disabled) return;
  const action = target.dataset.action;
  if (action === 'create') runForm();
  else if (action === 'revision') runForm(true);
  else if (action === 'supplement') supplementForm();
  else if (action === 'external') externalForm();
  else if (action === 'pricing') pricingForm();
  else if (action === 'add-price-row') { const form = $('pricing-form'); if (form && form.dataset.busy !== 'true') { $('pricing-entries').insertAdjacentHTML('beforeend', pricingRow(form.querySelectorAll('.pricing-entry').length + 1, array(form.pricingDocuments))); $('pricing-entries').lastElementChild.querySelector('input').focus(); } }
  else if (action === 'remove-price-row') { const form = $('pricing-form'); if (form && form.dataset.busy !== 'true') { if (form.querySelectorAll('.pricing-entry').length < 2) { notify('至少保留一名有效投标人。', true); return; } target.closest('.pricing-entry').remove(); form.querySelectorAll('.pricing-row-number').forEach((item, index) => { item.textContent = index + 1; }); } }
  else if (action === 'refresh') refresh();
  else if (action === 'show-facts') { state.view = 'facts'; syncViews(); }
  else if (action === 'show-results') { state.view = 'results'; syncViews(); }
  else if (action === 'refresh-facts') loadFacts(true);
  else if (action === 'fact-evidence') openEvidence(state.factEvidenceRefs[Number(target.dataset.index)]);
  else if (action === 'fact-checkpoint') { state.selected = target.dataset.id; state.filter = 'ALL'; state.query = ''; if ($('result-filter')) $('result-filter').value = 'ALL'; if ($('result-search')) $('result-search').value = ''; state.view = 'results'; syncViews(); renderResults(); $('result-detail')?.scrollIntoView({ block: 'start' }); }
  else if (action === 'facts-prev' || action === 'facts-next') { state.factPage += action === 'facts-prev' ? -1 : 1; renderFactsContent(); $('facts-controls')?.scrollIntoView({ block: 'start' }); }
  else if (action === 'fact-entity' || action === 'fact-conflict') { const list = action === 'fact-entity' ? array(state.facts?.entities) : array(state.facts?.conflicts); state.factFocus = array(list.find(item => item.id === target.dataset.id)?.occurrenceIds); state.factPage = 0; state.factKind = 'ALL'; state.factQuery = ''; $('fact-kind').value = 'ALL'; $('fact-search').value = ''; renderFactsContent(); $('facts-controls')?.scrollIntoView({ block: 'start' }); }
  else if (action === 'clear-fact-focus') { state.factFocus = []; state.factPage = 0; renderFactsContent(); }
  else if (action === 'more-fact-group' && ['entities', 'relations', 'conflicts'].includes(target.dataset.group)) { state.factLimits[target.dataset.group] += 20; renderFactsContent(); }
  else if (action === 'close-dialog') closeDialog();
  else if (action === 'open-run') selectRun(target.dataset.id);
  else if (action === 'select-result') { state.selected = target.dataset.id; renderResults(); }
  else if (action === 'evidence') openEvidence(state.evidenceRefs[Number(target.dataset.index)]);
  else if (action === 'coverage-location') {
    const doc = runDocuments().find(item => item.id === target.dataset.document), page = Number(target.dataset.page);
    if (doc && Number.isInteger(page) && page > 0) openEvidence({ documentId: doc.id, page, locationType: target.dataset.location, quote: '' });
  }
  else if (action === 'retry-evidence') openEvidence(state.preview?.evidence);
  else if (action === 'retry-image') loadPreviewImage();
  else if (action === 'word-layout') loadSourceStructure('word');
  else if (action === 'ocr-layout') loadSourceStructure('ocr');
});
document.addEventListener('change', event => {
  captureAmendmentDraft(event.target.closest('#amendment-form'));
  if (event.target.id === 'fact-kind') { state.factKind = event.target.value; state.factPage = 0; renderFactsContent(); }
  else if (event.target.id === 'project-select') selectProject(event.target.value);
  else if (event.target.id === 'run-select') selectRun(event.target.value);
  else if (event.target.id === 'result-filter') { state.filter = event.target.value; renderResults(); }
  else if (event.target.id === 'preview-page' && state.preview) { state.preview.page = Number(event.target.value); renderPreview(); }
  else if (event.target.id === 'rendered-page' && state.preview) { state.preview.renderedPage = Number(event.target.value); loadRenderedImage(); }
  else if (event.target.name === 'sourceRunId') {
    const sourceRun = array(state.project?.runs).find(run => run.id === event.target.value), tender = $('dialog').querySelector('[name="tenderDocumentId"]');
    if (tender && sourceRun?.tenderDocumentId) tender.value = sourceRun.tenderDocumentId;
  } else if (event.target.name === 'purpose' && event.target.value === 'CLARIFICATION') {
    const role = $('dialog').querySelector('[name="role"]'); if (role) role.value = 'TENDER';
  }
});
document.addEventListener('input', event => { captureAmendmentDraft(event.target.closest('#amendment-form')); if (event.target.id === 'fact-search') { state.factQuery = event.target.value; state.factPage = 0; renderFactsContent(); } else if (event.target.id === 'result-search') { state.query = event.target.value; renderResults(); } });
document.addEventListener('submit', submitForm);
document.addEventListener('submit', submitAmendment);
document.addEventListener('submit', submitPricing);
$('create-run').addEventListener('click', () => runForm()); $('refresh').addEventListener('click', refresh);
$('dialog').addEventListener('close', () => { state.dialogEpoch++; state.preview = null; });
$('dialog').addEventListener('click', event => { if (event.target !== $('dialog')) return; const rect = $('dialog').getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeDialog(); });
document.addEventListener('visibilitychange', () => { if (document.hidden) stopPolling(); else schedulePoll(); });
window.addEventListener('pagehide', () => { stopPolling(); state.controller?.abort(); });
async function init() {
  try {
    const [config, projects, verification] = await Promise.all([api('/api/config'), api('/api/projects'), api('/api/verification-config')]);
    state.config = { ...state.config, ...config, verification }; state.projects = array(projects);
    $('capabilities').hidden = false;
    $('capabilities').textContent = `${verification.visualEnabled ? '原图核验已启用，待核图像会发送至已配置的视觉服务；可见形式核验不证明签章真伪。' : '原图核验服务尚未启用，需要图像核验的条件将保留具体缺口。'}${verification.externalLiveConnected ? '外部信息按已配置来源核验，结果仍受主体、时点及范围约束。' : verification.externalConfigured ? '已配置外部查询适配器；每次返回仍需通过来源、主体和时点核验，配置完成不等于本项已核实。' : '外部信息采用来源记录登记，尚未连接实时官方查询。'}补充输入后的新修订保留原历史记录。`;
    const requestedProject = params.get('project') || params.get('projectId');
    const selected = state.projects.find(project => project.id === requestedProject)?.id || state.projects[0]?.id || '';
    if (requestedProject && requestedProject !== selected) notify('未找到请求的项目，请选择可用项目。', true);
    await selectProject(selected, params.get('run') || params.get('runId') || '');
  } catch (error) { errorMessage(error.message); $('workbench').innerHTML = '<div class="empty"><h2>工作台暂时无法连接</h2><p>请检查服务是否启动，或重新加载页面。</p><button class="button secondary" id="retry-init">重新连接</button></div>'; $('retry-init').addEventListener('click', init); }
}
init();
