const app = document.getElementById('app');
const modal = document.getElementById('modal');
const state = {
  config: { appName: 'BidCheck', version: '', storageMode: 'local', ocrEnabled: false, ocrModel: '', ocrMaxImages: 100, semanticEnabled: false, maxFileSizeMb: 1024 },
  projects: [], project: null, run: null, page: 'projects', tab: 'documents', projectQuery: '',
  filter: 'ALL', category: 'ALL', purposeFilter: 'ALL', externalOnly: false, resultQuery: '', selectedFinding: null, tasks: new Map(),
  reportView: 'checks', selectedConsistencyGroup: null, consistencyFilter: 'ALL', consistencyField: 'ALL', consistencyCategory: 'ALL', consistencyRelation: 'ALL', consistencyReview: 'ALL', consistencyQuery: '', consistencyFactsMode: 'ALL', consistencyFactsPage: 1, consistencyEvidenceGroup: null, consistencyEvidencePage: 1,
  busy: new Set(), loading: true, error: '', request: 0, runRequest: 0, profiles: new Map(),
  sourcePage: 1, sourceDoc: null, profileDoc: null, profileLoading: false, profileErrors: new Map(),
  documentCache: new Map(), documentLoads: new Map(), documentErrors: new Map(), visibleSourceId: null,
  selectedRunId: null, reviewDrafts: new Map(), disclosures: new Map(), confirmation: null, modalVersion: 0,
  runDocumentId: null, selectedCheckpoint: null, checkpointGroup: 'ALL', projectDrafts: new Map(), uploadErrors: new Map(), tenderSelection: new Map(),
  templateCatalog: [], checkpointSpecs: new Map(), checkpointSpecLoads: new Map(), checkpointSpecErrors: new Map(), templateSetConfirmation: null,
  incrementalCandidates: [], incrementalCandidateError: '', incrementalRecalculationContext: null,
  resultVersionView: 'source', resultVersionRunId: null,
};
const statuses = { PASS: '符合', FAIL: '高风险', WARNING: '有风险', UNDETERMINED: '待核实', NOT_APPLICABLE: '不适用' };
const businessStates = { SATISFIED: '满足要求', RISK: '存在风险', SYSTEM_INCOMPLETE: '系统未完成', EVIDENCE_PENDING: '待补证据', USER_ACTION: '需你确认', UNDETERMINED: '未形成结论', NOT_APPLICABLE: '不适用' };
const purposeLabels = { MANDATORY: '必备要求', SCORING: '评分优化', INFORMATIONAL: '信息提示' };
const verificationModeLabels = { DOCUMENT: '文件核验', EXTERNAL: '外部核验', HUMAN_JUDGMENT: '需专业判断' };
const templateParameterLabels = {
  materialType: '材料名称', requireBody: '必须包含正文', requiredCommitmentPoints: '必须响应的承诺要点', issuerRole: '出具主体', sealLocation: '盖章位置',
  alternatives: '可接受的替代材料', atLeast: '至少提供几项', certificateType: '证书类型', comparisonDate: '比较日期', inclusive: '到期日当天仍有效',
  field: '报价字段', operator: '比较方式', expected: '限价或目标值', currency: '币种', unit: '单位', taxBasis: '含税口径', lot: '标段', priceLayer: '报价层级', tolerance: '允许误差',
  role: '岗位名称', minCount: '最少人数', maxCount: '最多人数', allowConcurrent: '允许兼任', disallowedRoles: '禁止兼任的岗位', consortiumRole: '联合体成员角色', employerKey: '所属单位',
  startEvent: '起算事件', calendarBasis: '日历口径', minimumOccurrences: '最少可比记录数', caseSensitive: '区分大小写',
  formType: '表单名称', requiredFields: '必填字段', expectedSubject: '适用主体', leftRole: '左侧日期角色', rightRole: '右侧日期角色', maxDifferenceDays: '最大相差天数', relationKey: '日期关系范围',
  textScope: '文字检查范围', anomalyTypes: '异常类型', criterion: '评分条件', aggregation: '计分方式', maxScore: '最高分', scoreUnit: '分值单位', deduplicateBySubject: '同一主体去重',
  dimensions: '评审维度', highestTierDescription: '最高档要求', source: '外部数据来源', queryObject: '查询对象', asOfDate: '查询时点', adapterId: '来源适配器', evaluationStage: '核验阶段',
};
const sourceCoverageLabels = { FULL: '来源完整', PARTIAL: '部分来源', UNSUPPORTED: '暂不支持', LEGACY_DRAFT: '兼容草稿', UNKNOWN: '待确认' };
const conclusionLabels = { SATISFIED: '满足要求', NOT_SATISFIED: '不满足要求', SUSPECTED_RISK: '存在风险', UNDETERMINED: '未形成结论', NOT_APPLICABLE: '不适用' };
const runStatuses = { PENDING: '等待处理', QUEUED: '等待处理', RUNNING: '处理中', COMPLETED: '检查完成', SUCCEEDED: '已完成', FAILED: '处理失败', ERROR: '处理失败', NONE: '待开始', NEW: '待开始', CONNECTION_LOST: '连接中断' };
const types = { VALUE_COMPARE: '数值要求比对', ENTITY_FIELD_CONSISTENCY: '信息一致性', MATERIAL_EXISTENCE: '材料完整性', DATE_VALIDITY: '证件有效期', SEMANTIC_RESPONSE: '语义响应', SIGNATURE_SEAL: '签字与盖章', FULL_TEXT_REVIEW: '全文核对' };
const fields = { bidValidity: '投标有效期', duration: '工期 / 服务期', warranty: '质保期', totalPrice: '报价总额', projectName: '项目名称', projectNumber: '项目编号', companyName: '投标人名称', creditCode: '统一社会信用代码', plateNumber: '车牌号', material: '材料名称', certificateExpiry: '证件有效期', vin: '车架号', engineNumber: '发动机号', owner: '所有人', vehicleType: '车辆类型' };
Object.assign(fields, { openingTime: '开标时间', personName: '人员姓名', personRole: '人员岗位', manualRequirement: '自定义要求', fullTextConsistency: '全文信息一致性', amountAccuracy: '金额计算', missingFields: '漏填错填', signatureDate: '签署日期', signingAfterOpening: '落款与开标时间', typos: '错别字', certificateCoverage: '全文证件有效期' });
const groupNames = { '形式检查': '形式检查', '资格检查': '资格检查', '响应检查': '响应检查', '全文信息检查': '全文信息检查', '效期检查': '效期检查' };
const categories = { ...groupNames, FORMAL: '形式检查', QUALIFICATION: '资格检查', COMMERCIAL: '响应检查', TECHNICAL: '响应检查', RESPONSE: '响应检查', RESPONSIVENESS: '响应检查', CONSISTENCY: '全文信息检查', FULL_TEXT: '全文信息检查', COMPLETENESS: '形式检查', VALIDITY: '效期检查' };
const severities = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' };
const operatorLabel = value => ({ RESPONDS: '按要求响应', REVIEW: '逐项核对', EXISTS: '应包含', CONSISTENT: '前后一致', NOT_EXPIRED: '有效期覆盖', '>=': '不少于', '<=': '不超过', '=': '等于' }[value] || value || '');
const unitLabel = value => ({ DAY: '天', MONTH: '月', YEAR: '年', HOUR: '小时', MINUTE: '分钟', CNY: '元', PERCENT: '%' }[value]) || (String(value || '').startsWith('YEAR:') ? `${String(value).slice(5)} 年` : value || '');
const engineLabel = () => state.config.checkMode === 'RULES_RETRIEVAL' ? '规则扫描 · 按需核验' : state.config.semanticEnabled ? '规则 + 通义千问' : '本地规则引擎';
const supportedExtensions = ['pdf', 'docx', 'txt', 'md', 'png', 'jpg', 'jpeg'];
function uploadExtensions() {
  const configured = Array.isArray(state.config.formats) ? state.config.formats.map(value => String(value).replace(/^\./, '').toLowerCase()).filter(value => supportedExtensions.includes(value)) : [];
  return configured.length ? configured : supportedExtensions;
}
const formatLabel = () => uploadExtensions().map(value => value.toUpperCase()).join(' / ');
function maxFileSizeMb() {
  const configured = Number(state.config.maxFileSizeMb);
  return Number.isFinite(configured) && configured > 0 ? configured : 1024;
}
const ocrEligible = document => /\.(pdf|docx|png|jpe?g)$/i.test(document?.name || '');
const ocrPage = page => /OCR/i.test(page?.parser || '');
const imagePage = (document, page) => !!page && ((page.locationType === 'PAGE' && /\.pdf$/i.test(document.name)) || /\.(png|jpe?g)$/i.test(document.name) || page.locationType === 'IMAGE' || /WORD.*OCR|IMAGE_OCR/i.test(page.parser || ''));
const ocrLimit = () => Math.max(1, Number(state.config.ocrMaxImages) || 100);
const methodLabel = method => String(method || '').startsWith('FOCUSED_ATOMIC_RULES') ? '逐条件核验' : String(method || '').includes('/OCR_REVIEW') ? 'OCR辅助·待复核' : method === 'DETERMINISTIC' ? '本地规则' : method === 'FULL_TEXT_CONSISTENCY' ? '全文一致性' : String(method || '').startsWith('DASHSCOPE:') ? '模型辅助' : method || '本地规则';
const textOnlyConfigured = () => state.config.documentProcessingMode === 'TEXT_ONLY' && state.config.automaticVisualProcessingEnabled === false;
const textOnlyProcessing = processing => processing?.processingMode === 'TEXT_ONLY' && processing.visualAnalysisStatus === 'NOT_REQUESTED' && processing.automaticVisualRequested !== true;
const textProcessingBoundary = '图片仅用于文字识别；保留可识别的表格行列和原文页码，再进行全文一致性、检索与检查。未执行图像字段分析、签章核验或图片比对。';
const rulesRetrievalConfigured = () => state.config.checkMode === 'RULES_RETRIEVAL';
const rulesRetrievalBoundary = '本地规则扫描全文，混合检索补充候选证据，仅复杂疑点按需调用模型。检索候选和疑点核验不代表全文模型扫描。';
function rulesRetrievalReport(data = state.run?.consistency) {
  const modes = [data?.coverage?.checkMode, state.run?.run?.checkMode].filter(mode => mode != null);
  return modes.length > 0 && modes.every(mode => mode === 'RULES_RETRIEVAL');
}
function fullModelNotRequested(data = state.run?.consistency) {
  const model = data?.coverage?.model;
  return rulesRetrievalReport(data) && model?.requested === false && model.status === 'NOT_REQUESTED' && model.scope === 'FULL_DOCUMENT_MODEL';
}
function onDemandModelCoverage() {
  const value = state.run?.summary?.onDemandModel;
  return rulesRetrievalReport() && value?.scope === 'RELATED_EVIDENCE_DOUBTS' ? value : null;
}
function onDemandModelAlerts() {
  const model = onDemandModelCoverage();
  if (!model) return [];
  return [countValue(model, 'failedAttempts') > 0 ? `${model.failedAttempts} 次疑点模型核验未完成` : '',
    countValue(model, 'budgetDeferredCheckpoints') > 0 ? `${model.budgetDeferredCheckpoints} 项疑点因本次记录的旧调用次数上限导致暂缓` : '',
    countValue(model, 'consistencyUnknownRequestGroups') > 0 ? `${model.consistencyUnknownRequestGroups} 组一致性复核的模型发送情况未知` : '',
    countValue(model, 'unknownCheckpoints') > 0 ? `${model.unknownCheckpoints} 项核验范围未完整记录` : ''].filter(Boolean);
}
function onDemandRequestCountText(model) {
  return model && Object.prototype.hasOwnProperty.call(model, 'consistencyModelRequestedGroups')
    ? `招标检查 ${(countValue(model, 'modelRequestedCheckpoints') ?? '—')} 项＋一致性复核 ${(countValue(model, 'consistencyModelRequestedGroups') ?? '—')} 组，合计实发 ${(countValue(model, 'httpAttempts') ?? '—')} 次`
    : `${(countValue(model, 'modelRequestedCheckpoints') ?? '—')} 项请求核验 · 实发 ${(countValue(model, 'httpAttempts') ?? '—')} 次`;
}
function retrievalCapability(detail = false) {
  if (!state.config.ragEnabled) return '';
  const models = [state.config.ragEmbeddingModel && `向量检索：${state.config.ragEmbeddingModel}`, state.config.ragRerankModel && `排序：${state.config.ragRerankModel}`].filter(Boolean);
  return `已启用混合证据检索，候选片段仍需核对项目、人员与时点；检索结果不表示全文覆盖。${detail && models.length ? `${e(models.join('；'))}。` : ''}`;
}
function ocrCapability() {
  if (!state.config.ocrEnabled) return 'OCR 尚未启用，扫描页与图片文字不能自动识别，请核对原图。';
  if (textOnlyConfigured()) return `${textProcessingBoundary}PDF 优先读取原生文字，扫描页和嵌图通过 OCR 提取文字。识别缺口、行列关系或定位不可靠的位置会保留复核提示。百炼将接收需要识别的页面与图像。`;
  const models = `OCR 已启用${state.config.ocrModel ? `（${e(state.config.ocrModel)}）` : ''}。`;
  const scope = state.config.ocrPlanningMode === 'CHECKPOINT_DRIVEN'
    ? '招标文件和独立图片按完整范围处理；投标 PDF、Word 嵌图按检查计划识别，计划外位置保留为待补识别范围。'
    : '按全文范围识别 PDF 扫描页、Word 嵌图和上传图片；超过预算或失败的位置保留为未完成。';
  if (!state.config.pdfOcrEnabled) return `${models}${scope}百炼将接收待识别图像，单个文件本轮最多新识别 ${ocrLimit()} 张图像。`;
  const pdfPages = Math.max(1, Number(state.config.pdfOcrMaxPages) || 1000);
  const batchPages = Math.max(1, Number(state.config.pdfOcrBatchPages) || 10);
  const concurrency = Math.max(1, Number(state.config.pdfOcrConcurrency) || 5);
  return `${models}PDF 优先读取原生文字，单个文件本轮最多新识别 ${pdfPages} 页，每批最多 ${batchPages} 页、最多 ${concurrency} 批并行，识别异常页按需单页补识别。Word 嵌图和独立图片沿用图像识别，单个文件本轮最多新识别 ${ocrLimit()} 张图像。${scope}百炼将接收待识别的 PDF 页面和图像；缺少可靠坐标的表格仍需对照原图核对行列关系。`;
}
const terminal = new Set(['COMPLETED', 'SUCCEEDED', 'SUCCESS', 'DONE', 'FAILED', 'ERROR', 'CANCELLED']);
const failed = new Set(['FAILED', 'ERROR', 'CANCELLED']);
const icons = {
  document: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
  logo: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 15l3 3 6-7"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  back: '<path d="M20 12H4m6 6-6-6 6-6"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  upload: '<path d="M12 16V3m-5 5 5-5 5 5M4 15v5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5"/>',
  download: '<path d="M12 3v13m-5-5 5 5 5-5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
  play: '<path d="m8 4 12 8-12 8z"/>',
  spark: '<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5zM20 3v4M18 5h4"/>',
  external: '<path d="M14 3h7v7M10 14 21 3M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5"/>',
  edit: '<path d="m15 4 5 5M4 16l-1 5 5-1L21 7a2.1 2.1 0 0 0-5-3z"/>',
  alert: '<path d="M10.3 3.9 1.8 18.5A1.8 1.8 0 0 0 3.4 21h17.2a1.8 1.8 0 0 0 1.6-2.5L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v5M12 17h.01"/>',
  refresh: '<path d="M20 8a8 8 0 0 0-14-3L3 8m0-5v5h5M4 16a8 8 0 0 0 14 3l3-3m0 5v-5h-5"/>',
  pin: '<path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0z"/><circle cx="12" cy="10" r="2.5"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
};
const icon = (name, small = false) => `<svg class="icon${small ? ' icon-sm' : ''}" viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.document}</svg>`;
const e = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const enc = value => encodeURIComponent(String(value ?? ''));
const date = value => { if (!value) return '—'; const d = new Date(value); return Number.isNaN(d.valueOf()) ? String(value) : d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }); };
const dateShort = value => date(value).split(' ')[0];
const badge = (status, label) => `<span class="pill ${e(status)}">${e(label || statuses[status] || runStatuses[status] || status || '待开始')}</span>`;
const docs = () => state.project?.documents || [];
const checkpoints = () => state.project?.checkpoints || [];
const projectId = () => state.project?.project?.id;
function group(cp) {
  const category=String(cp.category||'').trim();
  if(Object.hasOwn(groupNames,category))return category;
  if(category==='VALIDITY'&&cp.field==='bidValidity')return '响应检查';
  const aliases={...categories,'资格评审':'资格检查','资格审查':'资格检查','形式评审':'形式检查','文件组成':'形式检查','响应评审':'响应检查','商务评审':'响应检查','技术评审':'响应检查','评分项':'响应检查','全文一致性':'全文信息检查','一致性检查':'全文信息检查','有效期检查':'效期检查'};
  if(Object.hasOwn(aliases,category))return aliases[category];
  if(cp.type==='DATE_VALIDITY'||['certificateExpiry','certificateCoverage'].includes(cp.field))return '效期检查';
  if(['ENTITY_FIELD_CONSISTENCY','FULL_TEXT_REVIEW'].includes(cp.type))return '全文信息检查';
  if(['SIGNATURE_SEAL','MATERIAL_EXISTENCE'].includes(cp.type))return '形式检查';
  if(cp.type==='VALUE_COMPARE')return '响应检查';
  if(cp.field==='qualification')return '资格检查';
  if(['compliance','scoring','technicalResponse','designDuration','constructionDuration'].includes(cp.field))return '响应检查';
  return 'UNKNOWN';
}
const categoryLabel=key=>groupNames[key]||'未分类';
function categoryDefinitions(keys) {return {...groupNames,...(keys.includes('UNKNOWN')?{UNKNOWN:'未分类'}:{})};}
function renderCategoryNavigation(counts,selected,action,unit) {
  const entries={ALL:'全部',...categoryDefinitions(Object.keys(counts))},total=Object.values(counts).reduce((sum,count)=>sum+count,0);
  return `<nav class="check-category-nav" aria-label="${unit==='检查项'?'检查项分类':'业务事项分类'}">${Object.entries(entries).map(([key,label])=>{const count=key==='ALL'?total:counts[key]||0;return `<button class="${selected===key?'active':''}" data-action="${action}" data-category="${e(key)}" data-group="${e(key)}" aria-pressed="${selected===key}" aria-label="${e(label)}，${count} 个${unit}"><span>${e(label)}</span><b>${count}</b></button>`;}).join('')}</nav>`;
}
function businessGroupCategory(item) {
  const keys=[...new Set(item.members.map(findingCategory))];
  return keys.length===1?keys[0]:'UNKNOWN';
}
const options = (values, selected) => Object.entries(values).map(([v, label]) => `<option value="${e(v)}" ${v === selected ? 'selected' : ''}>${e(label)}</option>`).join('');
const locationUnit = type => type === 'PAGE' ? '页' : type === 'PARAGRAPH' ? '段' : type === 'IMAGE' ? '张图' : '内容块';
const locationLabel = ev => `第 ${ev?.page || 1} ${locationUnit(ev?.locationType)}`;
const activeTasks = id => [...state.tasks.values()].filter(t => t.projectId === id && ['PENDING', 'RUNNING', 'QUEUED'].includes(t.status));
const selectedDoc = role => docs().filter(d => d.role === role).at(-1);
const activeTender = () => docs().find(d => d.id === state.project?.project?.activeTenderId) || selectedDoc('TENDER');
const isRunning = () => state.project?.runs?.some(r => !terminal.has(r.status));
const maxDocuments = () => Math.max(1, Number(state.config.maxDocumentsPerProject) || 100);
const currentTender = () => docs().find(d => d.id === state.tenderSelection.get(projectId()) && d.role === 'TENDER') || activeTender();
const usableTender = () => { const d = currentTender(); return !!d && d.readableText !== false && Number(d.pageCount ?? d.pages?.length ?? 0) > 0; };
const checksReady = () => usableTender() && !state.project?.project?.checkpointRulesOutdated && state.project?.project?.activeTenderId === currentTender()?.id && checkpoints().some(cp => cp.enabled && cp.confirmed) && !checkpoints().some(cp => cp.enabled && !cp.confirmed);
const processing = () => activeTasks(projectId()).length > 0 || state.busy.has('upload-TENDER') || state.busy.has('upload-BID') || [...state.busy].some(key => key.startsWith('extract-'));
const projectValue = key => { const p = state.project?.project || {}; return key === 'projectNumber' ? p.projectNumber || p.code || '' : key === 'openingTime' ? String(p.openingTime || '').slice(0, 16).replace('T', ' ') : p[key] || ''; };
const checkpointSpecKey = (checkpointId, pid = projectId()) => `${pid || 'none'}:${checkpointId}`;
const checkpointSpec = checkpointId => state.checkpointSpecs.get(checkpointSpecKey(checkpointId));
const templateMetadata = (id, version) => state.templateCatalog.find(item => item.id === id && (!version || item.version === version)) || state.templateCatalog.find(item => item.id === id);
const templateTitle = spec => templateMetadata(spec?.templateId, spec?.templateVersion)?.title || spec?.templateId || '兼容检查';
const specCategory = checkpoint => ({ '形式检查': 'FORMAL', '资格检查': 'QUALIFICATION', '响应检查': 'RESPONSIVENESS', '全文信息检查': 'FULL_TEXT', '效期检查': 'VALIDITY' }[group(checkpoint)] || 'RESPONSIVENESS');
function randomKey(prefix) {
  const value = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}-${value}`;
}
async function loadCheckpointSpec(checkpointId, { force = false, quiet = false } = {}) {
  const pid = projectId(), key = checkpointSpecKey(checkpointId, pid);
  if (!force && state.checkpointSpecs.has(key)) return state.checkpointSpecs.get(key);
  if (!force && state.checkpointSpecLoads.has(key)) return state.checkpointSpecLoads.get(key);
  const request = api(`/api/checkpoints/${enc(checkpointId)}/spec`).then(spec => {
    if (pid === projectId()) { state.checkpointSpecs.set(key, spec); state.checkpointSpecErrors.delete(key); }
    return spec;
  }).catch(error => {
    if (pid === projectId()) {
      state.checkpointSpecs.set(key, null);
      if (error.status !== 404) state.checkpointSpecErrors.set(key, error.message);
    }
    if (error.status === 404 || quiet) return null;
    throw error;
  }).finally(() => state.checkpointSpecLoads.delete(key));
  state.checkpointSpecLoads.set(key, request);
  return request;
}
async function mapLimited(items, limit, work) {
  const results = new Array(items.length); let cursor = 0;
  async function worker() { while (cursor < items.length) { const index = cursor++; results[index] = await work(items[index], index); } }
  await Promise.all(Array.from({ length: Math.min(Math.max(1, limit), items.length) }, worker));
  return results;
}
function checkpointRequirementRefs(checkpoint, existing = []) {
  if (Array.isArray(existing) && existing.length) return existing;
  const tender = currentTender(), source = checkpoint?.source;
  if (!tender) return [];
  return [{
    documentId: source?.documentId || tender.id, documentVersion: Number(tender.version) || 0, documentHash: tender.sha256 || '', parserFingerprint: source?.anchor || '', sourceHash: '',
    locationType: source?.locationType || 'PAGE', location: Math.max(0, Number(source?.page) || 1), quote: source?.quote || checkpoint?.expected || checkpoint?.title || '', startOffset: null, endOffset: null,
  }];
}
function sourceCoverageSummary(spec) {
  const coverage = spec?.sourceCoverage || {}, stateName = coverage.state || (spec?.requirementRefs?.length ? 'PARTIAL' : 'UNKNOWN');
  return `${sourceCoverageLabels[stateName] || stateName} · ${spec?.requirementRefs?.length || 0} 处招标依据`;
}
function renderCheckpointSpecSummary(checkpoint) {
  const key = checkpointSpecKey(checkpoint.id), loading = state.checkpointSpecLoads.has(key), loaded = state.checkpointSpecs.has(key), spec = state.checkpointSpecs.get(key), issue = state.checkpointSpecErrors.get(key);
  if (!state.templateCatalog.length) return '';
  if (!loaded) return `<div class="checkpoint-template-summary pending"><div><strong>核验模板</strong><span>尚未读取模板配置</span></div><button class="text-action" data-action="load-checkpoint-spec" data-id="${e(checkpoint.id)}" ${loading ? 'disabled' : ''}>${loading ? '读取中…' : '查看配置'}</button></div>`;
  if (!spec) return `<div class="checkpoint-template-summary legacy"><div><strong>兼容检查路线</strong><span>${e(issue || '此项尚未配置模板，仍按原检查方式执行。')}</span></div><button class="text-action" data-action="edit-checkpoint" data-id="${e(checkpoint.id)}">配置模板</button></div>`;
  return `<div class="checkpoint-template-summary ${spec.confirmed ? 'confirmed' : 'draft'}"><div><strong>${e(templateTitle(spec))} <small>V${e(spec.templateVersion)}</small></strong><span>${e(purposeLabels[spec.purpose] || spec.purpose)} · ${e(verificationModeLabels[spec.verificationMode] || spec.verificationMode)} · ${e(sourceCoverageSummary(spec))}</span>${spec.confirmed ? '' : '<em>草稿尚未确认，本项运行时仍走兼容路线。</em>'}</div><button class="text-action" data-action="edit-checkpoint" data-id="${e(checkpoint.id)}">${spec.confirmed ? '查看 / 修订' : '完善草稿'}</button></div>`;
}
function reviewOf(finding) { return (state.run?.reviews || []).filter(r => r.resultId === finding.id).sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))[0]; }
function effectiveStatus(finding) { return reviewOf(finding)?.status || finding.evaluation.status; }
function disclosureKey(name) { return `${projectId() || 'workspace'}:${state.run?.run?.id || 'project'}:${name}`; }
function disclosureAttributes(name, defaultOpen = false) {
  const key = disclosureKey(name);
  const open = state.disclosures.has(key) ? state.disclosures.get(key) : defaultOpen;
  return `data-disclosure-key="${e(key)}" data-disclosure-open="${open}" ${open ? 'open' : ''}`;
}
function rememberDisclosureState(root = app) {
  root.querySelectorAll('details[data-disclosure-key]').forEach(details => {
    if (details.open !== (details.dataset.disclosureOpen === 'true')) {
      state.disclosures.set(details.dataset.disclosureKey, details.open);
      details.dataset.disclosureOpen = String(details.open);
    }
  });
}
function setDisclosureOpen(name, open) {
  const key = disclosureKey(name);
  state.disclosures.set(key, open);
  app.querySelectorAll('details[data-disclosure-key]').forEach(details => {
    if (details.dataset.disclosureKey === key) { details.open = open; details.dataset.disclosureOpen = String(open); }
  });
}

async function api(path, { method = 'GET', body, ...rest } = {}) {
  const multipart = body instanceof FormData;
  let response;
  try { response = await fetch(path, { method, headers: multipart ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : multipart ? body : JSON.stringify(body), ...rest }); }
  catch { throw new Error('无法连接服务。请检查服务是否已启动，然后重试。'); }
  if (!response.ok) {
    let message = response.status === 413 ? `上传被服务器拒绝：文件大小超过服务允许范围（413）。当前页面上限为 ${maxFileSizeMb()} MB。` : `请求失败（${response.status}）`;
    let detail = {};
    try { detail = await response.json(); message = detail.message || message; } catch { /* Keep the HTTP status when the server returns no JSON. */ }
    const error = new Error(message); error.status = response.status; error.code = detail.code || ''; error.currentRevision = detail.currentRevision; error.details = detail.errors || detail.details || [];
    throw error;
  }
  if (response.status === 204) return null;
  return response.json();
}
function toast(message, error = false) {
  const el = document.createElement('div'); el.className = `toast${error ? ' error' : ''}`;
  el.innerHTML = `${icon(error ? 'alert' : 'check')}<span>${e(message)}</span><button aria-label="关闭提示">×</button>`;
  const region = document.getElementById('toast-region'); region.append(el); while (region.children.length > 3) region.firstElementChild.remove(); el.querySelector('button').onclick = () => el.remove();
  window.setTimeout(() => el.remove(), error ? 12000 : 5500);
}
function modalError(error) { const el = modal.querySelector('.modal-error'); if (el) { el.textContent = error.message || String(error); el.setAttribute('role', 'alert'); } else toast(error.message || String(error), true); }
function openModal(title, subtitle, body, footer = '', className = '') {
  ++state.modalVersion;
  modal.className = className;
  modal.innerHTML = `<div class="modal-header"><div><h2 id="modal-title">${e(title)}</h2>${subtitle ? `<p>${e(subtitle)}</p>` : ''}</div><button type="button" class="modal-close" data-action="close-modal" aria-label="关闭对话框">×</button></div>${body}<div class="modal-error"></div>${footer}`;
  if (!modal.open) modal.showModal();
}
function capabilityNote() {
  return `<div class="capability-note">${icon('info')}<div><strong>${e(engineLabel())}</strong><span>${rulesRetrievalConfigured() ? '全文规则扫描，检索补证，复杂疑点按需模型核验。' : textOnlyConfigured() ? '文字识别 → 全文检查。' : ''}${state.config.semanticEnabled ? '模型结论需结合原文复核。' : '语义服务未配置，相关内容需人工核实。'}${state.config.ocrEnabled ? 'OCR 文字需对照原图。' : '扫描页与图片文字需人工核对。'}</span><button data-action="capabilities">检查范围与说明 ${icon('arrow', true)}</button></div></div>`;
}
function shell(content) {
  const p = state.project?.project;
  return `<div class="shell"><aside class="sidebar" aria-label="主导航"><button class="brand" data-action="home" aria-label="BidCheck 首页"><span class="brand-symbol">${icon('logo')}</span><div><div class="brand-name">BidCheck</div><div class="brand-subtitle">招投标文件质检</div></div></button><div class="nav-caption">工作空间</div><nav><button class="side-link active" data-action="home" aria-label="项目工作台" title="项目工作台">${icon('grid')}<span>项目工作台</span></button><button class="side-link" data-action="capabilities" aria-label="检查能力说明" title="检查能力说明">${icon('shield')}<span>检查能力说明</span></button></nav>${p ? `<div class="sidebar-project"><small>当前项目</small><strong title="${e(p.name)}">${e(p.name)}</strong></div>` : ''}<div class="sidebar-bottom"><span><i class="status-dot"></i>${e(engineLabel())}</span><small>BidCheck ${e(state.config.version || '')}<br>检查结论可溯源 · 支持人工复核</small></div></aside><div class="workspace"><header class="topbar"><div class="breadcrumbs">${icon('grid', true)}<button data-action="home">项目工作台</button>${p ? `${icon('chevron', true)}<span>${e(p.name)}</span>` : ''}</div><div class="topbar-right"><button class="local-badge" data-action="capabilities">${icon('shield', true)}${e(engineLabel())}</button><div class="avatar" title="本地工作区">BC</div></div></header><main id="main" class="main-content" tabindex="-1">${content}</main></div></div>`;
}
function render() {
  rememberDisclosureState();
  const focused = document.activeElement, focusId = focused?.closest('#review-form, #project-info-form') ? focused.id : null;
  const selectionStart = focusId ? focused.selectionStart : null, selectionEnd = focusId ? focused.selectionEnd : null;
  state.visibleSourceId = null;
  const content = state.loading ? `<div class="initial-loading" style="min-height:60vh"><span class="spinner"></span>正在加载项目…</div>` : state.error ? `<div class="error-panel" role="alert"><h2>暂时无法加载工作台</h2><p>${e(state.error)}</p><button class="button button-secondary" data-action="reload">${icon('refresh', true)}重新连接</button></div>` : state.page === 'projects' ? renderProjects() : renderProject();
  app.innerHTML = shell(content);
  bindDragZones();
  if (focusId) { const next = document.getElementById(focusId); next?.focus({ preventScroll: true }); if (selectionStart != null && next?.setSelectionRange) { try { next.setSelectionRange(selectionStart, selectionEnd); } catch { /* Date inputs do not support selection ranges. */ } } }
  app.querySelectorAll('img[data-preview]').forEach(img => img.addEventListener('error', () => { const el = document.createElement('p'); el.className = 'preview-error'; el.textContent = '原始页面暂时无法预览，请下载原文件复核。'; img.replaceWith(el); }, { once: true }));
}
function renderProjects() {
  const projects = [...state.projects].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).filter(p => `${p.name} ${p.projectNumber || p.code || ''}`.toLowerCase().includes(state.projectQuery.toLowerCase()));
  const docCount = state.projects.reduce((sum, p) => sum + Number(p.documentCount || 0), 0);
  const complete = state.projects.filter(p => ['COMPLETED', 'SUCCEEDED'].includes(p.latestStatus)).length;
  return `<div class="page-heading"><div><h1>项目工作台</h1><p>管理标书检查，跟进风险与复核。</p></div><div class="heading-actions"><button class="button button-secondary" data-action="demo" ${state.busy.has('demo') ? 'disabled' : ''}>${state.busy.has('demo') ? '<span class="spinner"></span>' : icon('play', true)}体验示例</button><button class="button button-primary" data-action="new-project">${icon('plus', true)}新建项目</button></div></div><div class="metrics"><div class="metric"><div class="metric-icon">${icon('folder')}</div><div><div class="metric-label">全部项目</div><div class="metric-value">${state.projects.length}<small>个</small></div></div></div><div class="metric"><div class="metric-icon">${icon('document')}</div><div><div class="metric-label">已上传文件</div><div class="metric-value">${docCount}<small>份</small></div></div></div><div class="metric"><div class="metric-icon">${icon('shield')}</div><div><div class="metric-label">最近检查已完成</div><div class="metric-value">${complete}<small>个项目</small></div></div></div></div><div class="section-toolbar"><h2 class="section-title">项目列表</h2><div class="search-wrap">${icon('search')}<input type="search" id="project-search" aria-label="搜索项目名称或编号" placeholder="搜索项目名称或编号" value="${e(state.projectQuery)}"></div></div><div id="projects-content">${projects.length ? `<div class="projects-grid projects-list"><div class="project-list-header" aria-hidden="true"><span>项目名称 / 编号</span><span>检查状态</span><span>文件 / 创建日期</span><span>操作</span></div>${projects.map(renderProjectCard).join('')}</div>` : state.projects.length ? `<div class="empty-state"><h2>没有匹配的项目</h2><p>请调整搜索关键词。</p></div>` : `<div class="empty-state"><div class="empty-illustration">${icon('folder')}</div><h2>还没有检查项目</h2><p>点击右上角“新建项目”，上传文件开始检查。</p><div class="workflow-hint"><span><b>1</b>上传招标文件</span>${icon('chevron', true)}<span><b>2</b>确认检查项</span>${icon('chevron', true)}<span><b>3</b>上传投标文件</span>${icon('chevron', true)}<span><b>4</b>检查与复核</span></div></div>`}</div>${capabilityNote()}`;
}
function renderProjectCard(p) {
  return `<article class="project-card"><div class="project-row-main"><div class="project-icon">${icon('folder')}</div><div><h3>${e(p.name)}</h3><p class="project-code">${e(p.projectNumber || p.code || '未设置项目编号')}</p></div></div><div class="project-row-status">${badge(p.latestStatus || 'NONE')}</div><div class="project-card-meta project-row-meta"><span>${Number(p.documentCount || 0)} 份文件</span><span>${e(dateShort(p.createdAt))}</span></div><div class="project-row-action"><button class="card-link" data-action="open-project" data-id="${e(p.id)}" aria-label="进入项目：${e(p.name)}"><span>进入项目</span>${icon('arrow', true)}</button></div></article>`;
}
function renderProject() {
  if (!state.project) return '<div class="empty-inline">正在加载项目…</div>';
  const p = state.project.project, runs = state.project.runs || [];
  return `<div class="project-heading"><button class="breadcrumb-back" data-action="home">${icon('back', true)}返回项目列表</button><div class="page-heading"><div><h1>${e(p.name)}</h1><div class="project-meta"><span>${e(p.projectNumber || p.code || '待补录项目编号')}</span><i></i><span>${p.openingTime ? `开标 ${e(p.openingTime.replace('T', ' '))}` : '待补录开标时间'}</span><i></i><span>${docs().length} 份文档 · ${runs.length} 次检查</span></div></div><div class="heading-actions"><a class="button button-secondary" href="/verification.html?project=${enc(p.id)}">${icon('shield', true)}自动核验</a><button class="button button-secondary" data-action="refresh-project">${icon('refresh', true)}刷新</button></div></div></div>${renderSteps()}${state.tab === 'documents' ? renderDocuments() : state.tab === 'checkpoints' ? renderCheckpoints() : state.tab === 'profile' ? renderProfile() : renderResults()}`;
}
function renderSteps() {
  const runs = state.project.runs || [], complete = runs.some(r => r.status === 'COMPLETED'), cp = checkpoints();
  const list = [{ tab: 'documents', label: '招标文件', sub: '上传文件与基础信息', done: usableTender() }, { tab: 'checkpoints', label: '确认检查项', sub: `${cp.length} 项招标要求`, done: checksReady() }, { tab: 'profile', label: '投标文件', sub: '上传文件与核心信息', done: !!selectedDoc('BID') }, { tab: 'results', label: '检查与复核', sub: runs.length ? `${runs.length} 次检查记录` : '报告与原文对照', done: complete }];
  return `<div class="tabs workflow-nav steps" role="tablist" aria-label="项目检查流程">${list.map((item, i) => `<button class="tab step ${item.tab === state.tab ? 'active current' : item.done ? 'done' : ''}" role="tab" aria-selected="${item.tab === state.tab}" ${item.tab === state.tab ? 'aria-current="step"' : ''} data-action="tab" data-tab="${item.tab}" ${item.tab === 'checkpoints' && !usableTender() || item.tab === 'profile' && !checksReady() ? 'disabled' : ''}><span class="step-circle">${item.done && item.tab !== state.tab ? icon('check', true) : i + 1}</span><span class="step-text"><strong>${item.label}</strong><small>${item.sub}</small></span></button>`).join('')}</div>`;
}
function renderProjectInformation() {
  const p = state.project.project, draft = state.projectDrafts.get(p.id) || {}, manual = p.manualFields || [];
  const fieldsToEdit = [['name', '项目名称', 'text'], ['projectNumber', '项目编号', 'text'], ['openingTime', '开标时间', 'text']];
  return `<section class="panel"><div class="panel-header"><div><h2>${icon('info')}项目基础信息</h2><p>招标解析后自动填充，未识别的信息可补录。修改后请保存。</p></div>${Object.keys(draft).length ? badge('WARNING', '有未保存修改') : ''}</div><form id="project-info-form" class="panel-body"><div class="project-info-fields">${fieldsToEdit.map(([key, label, type]) => { const source = p.fieldSources?.[key], value = draft[key] ?? projectValue(key); return `<div class="field ${key === 'name' ? 'wide-field' : ''}"><label class="field-label" for="project-info-${key}">${label} ${manual.includes(key) ? '<span class="manual-tag">手动修改</span>' : source ? '<span class="auto-tag">自动提取</span>' : ''}</label><input id="project-info-${key}" name="${key}" type="${type}" value="${e(value)}" maxlength="${key === 'name' ? 160 : 100}" ${key === 'name' ? 'required' : ''} placeholder="${key === 'name' ? '填写项目名称' : key === 'openingTime' ? '如 2026-11-20 09:30；也可仅填日期' : '未提取，请补录'}">${p.fieldWarnings?.[key] && !manual.includes(key) ? `<small class="field-warning">${e(p.fieldWarnings[key])}</small>` : ''}${source?.documentId ? `<button type="button" class="text-action field-source" data-action="page-preview" data-id="${e(source.documentId)}" data-page="${Number(source.page) || 1}">${icon('pin', true)}查看来源 · ${e(locationLabel(source))}</button>` : ''}</div>`; }).join('')}</div><div class="project-info-footer"><p>基础信息用于之后的检查。开标时间未提供时，相关时间比对会提示待核实。</p><button class="button button-secondary" type="submit" ${state.busy.has('project-info') || !Object.keys(draft).length ? 'disabled' : ''}>保存基础信息</button></div></form></section>`;
}
function renderDocuments() {
  const nextReady = usableTender() && !processing(), tender = currentTender();
  return `<div class="work-layout"><div class="stack"><section class="panel"><div class="panel-header"><div><h2>${icon('document')}上传招标文件</h2>${state.project.project.checkpointRulesOutdated ? '<p class="inline-warning">提取规则已更新，请点击下一步重新提取并确认。已保存的人工调整和历史报告会保留。</p>' : ''}<p>原始文件与每次上传版本均保留；每项目最多 ${maxDocuments()} 份文件。</p></div><span class="pill">${docs().filter(d => d.role === 'TENDER').length} 份招标</span></div><div class="panel-body"><div class="ocr-upload-note ${state.config.ocrEnabled ? 'enabled' : 'disabled'}">${icon('info', true)}<div><strong>${state.config.ocrEnabled ? (textOnlyConfigured() ? '扫描件与图片仅提取文字' : '扫描件支持 OCR 辅助识别') : '优先上传可读取文字的 PDF 或 DOCX'}</strong><p>${ocrCapability()}</p></div></div>${renderDocumentBox('TENDER')}</div></section>${renderProjectInformation()}<div class="workflow-footer"><p>${processing() ? '文件正在处理中，完成后可继续。' : !tender ? '请先上传招标文件，解析成功后可进入下一步。' : !usableTender() ? '当前文件未提取到有效文字，请重新识别或替换为可读取的文件。' : '下一步将提取并展示五类检查项，请逐项核对招标要求。'}</p><button class="button button-primary" data-action="next-tender" ${!nextReady ? 'disabled' : ''}>下一步，确认检查项 ${icon('arrow', true)}</button></div></div><aside class="stack work-side"><div id="task-region">${renderTasks()}</div><section class="panel"><div class="panel-header"><h2>${icon('clock')}当前进度</h2></div><div class="panel-body"><p class="run-condition">${usableTender() ? '招标已解析，可核对基础信息和检查项。' : '等待招标文件解析成功。上传失败后项目仍保留，可直接重试。'}</p>${state.project.runs?.length ? `<button class="button button-secondary" data-action="tab" data-tab="results">查看已有报告</button>` : ''}</div></section><div class="side-help"><h3>上传和替换文件</h3><ol><li>系统提取项目名称、项目编号和开标时间。</li><li>上传新的招标版本后，需重新核对对应检查项。</li><li>替换投标文件请在第三步上传新版本，再开始检查。</li></ol></div></aside></div>`;
}
function renderRunPanel() {
  const bids = docs().filter(d => d.role === 'BID'), enabled = checkpoints().filter(cp => cp.enabled && cp.confirmed).length;
  const documentId = state.runDocumentId || state.profileDoc || bids.at(-1)?.id;
  const selected = bids.find(d => d.id === documentId) || bids.at(-1);
  const previewed = selected && state.profiles.has(selected.id), ready = checksReady() && selected && previewed && !processing() && !isRunning();
  return `<section class="panel run-panel ${selected ? 'prepared' : ''}"><div class="panel-header"><h2>${icon('play')}${textOnlyConfigured() ? '全文检查' : '开始检查'}</h2><span class="pill">${enabled} 项已启用</span></div><div class="panel-body"><label class="field-label" for="run-document">本次检查的投标版本</label><select id="run-document" ${!bids.length ? 'disabled' : ''}>${bids.length ? [...bids].reverse().map(d => `<option value="${e(d.id)}" ${d.id === selected?.id ? 'selected' : ''}>V${Number(d.version)} · ${e(d.name)}</option>`).join('') : '<option>请先上传投标文件</option>'}</select><p class="run-condition">${!checksReady() ? '请先确认当前招标文件的检查项。' : !selected ? '上传并预览投标文件核心信息后可开始检查。' : !previewed ? '正在准备核心信息预览，请核对提取结果后开始。' : processing() ? '请等待文件处理完成。' : `核对下方核心信息后，按 ${enabled} 项已确认要求检查此版本。`}</p><button class="button button-primary run-button" data-action="start-run" ${!ready || state.busy.has('run') ? 'disabled' : ''}>${state.busy.has('run') || isRunning() ? '<span class="spinner"></span>' : icon('shield', true)}${isRunning() ? '检查正在进行' : textOnlyConfigured() ? '开始全文检查' : '开始检查'}</button><div class="run-info">${rulesRetrievalConfigured() ? `${e(rulesRetrievalBoundary)}` : ''}${textOnlyConfigured() ? '检查识别后的全文、表格行列与原文位置；签章和图片比对仍需人工核对。' : ''}替换文件时上传新版本并重新开始检查；每次报告和复核记录独立留存。</div></div></section>`;
}
function renderDocumentBox(role) {
  const list = docs().filter(d => d.role === role), uploading = state.busy.has(`upload-${role}`), issue = state.uploadErrors.get(`${projectId()}:${role}`), tasks = [...state.tasks.values()].filter(t => t.projectId === projectId() && (t.type === 'PARSE' || t.taskType === 'PARSE') && t.role === role && !['SUCCEEDED', 'COMPLETED', 'SUCCESS', 'DONE'].includes(t.status));
  return `<div class="document-box ${role === 'BID' ? 'bid' : ''}"><div class="document-box-heading"><div class="document-type-icon">${icon('document', true)}</div><div><h3>${role === 'TENDER' ? '招标文件' : '投标文件'}</h3><p>${role === 'TENDER' ? '检查依据 · 提取招标要求' : '检查对象 · 核验响应内容'}</p></div></div><label class="dropzone ${uploading ? 'disabled' : ''}" data-role="${role}" tabindex="0" role="button" aria-label="上传${role === 'TENDER' ? '招标' : '投标'}文件">${uploading ? '<span class="spinner"></span>' : icon('upload')}<strong>${uploading ? '正在上传文件…' : '<b>点击上传 / 替换</b>，或将文件拖到此处'}</strong><small>${e(formatLabel())}，单个文件 ≤ ${maxFileSizeMb()} MB · 项目上限 ${maxDocuments()} 份</small><input class="visually-hidden" type="file" data-upload="${role}" accept="${uploadExtensions().map(extension => `.${extension}`).join(',')}" ${uploading ? 'disabled' : ''}>${uploading ? '<small class="upload-state" role="status">正在发送原文件，请保持页面打开。上传完成后将转入后台解析。</small>' : ''}</label>${issue ? `<div class="upload-error" role="alert">上传失败：${e(issue)}<br>项目已保留，点击上方上传区即可重试。</div>` : ''}${tasks.length ? `<div class="document-list">${tasks.map(t => `<div class="document-item"><strong>${e(t.fileName || t.label || '待解析文件')}</strong><div class="document-item-meta">${badge(t.status, failed.has(t.status) ? '解析失败' : t.status === 'RUNNING' ? '解析中' : t.status === 'CONNECTION_LOST' ? '连接中断' : '待解析')}<span>${e(t.stage || '')}</span></div>${failed.has(t.status) ? `<p class="task-error">${e(t.error || '请重试或替换文件')}</p><button class="text-action" data-action="retry-task" data-id="${e(t.id)}">重试解析</button>` : ''}</div>`).join('')}</div>` : ''}${list.length ? `<div class="document-list">${[...list].reverse().map(d => `<div class="document-item"><div class="document-item-top">${icon('document')}<div class="document-item-name">${e(d.name)} <span class="document-version">V${Number(d.version)}</span>${role === 'TENDER' && currentTender()?.id === d.id ? ' <span class="pill RUNNING">当前选择</span>' : ''}</div></div><div class="document-item-meta">${badge(d.readableText === false ? 'WARNING' : 'PASS', d.readableText === false ? '解析完成 · 未提取到文字' : '解析成功')}<span>${e(date(d.createdAt))}</span><span>· ${d.pageCount ?? d.pages?.length ?? 0} ${locationUnit(d.locationType || d.pages?.[0]?.locationType)}</span></div>${renderOcrDocumentStatus(d)}<div class="document-item-actions"><button class="text-action" data-action="document-preview" data-id="${e(d.id)}">查看原文</button><a class="text-action" href="/api/documents/${enc(d.id)}/raw" download>下载文件</a>${ocrEligible(d) && role === 'BID' && !textOnlyConfigured() ? `<button class="text-action" data-action="ocr-plan-document" data-id="${e(d.id)}" title="按当前已确认检查项提取图像文字并生成新版本；计划外文字保留待按需补识别" ${!state.config.ocrEnabled || state.busy.has(`ocr-${d.id}`) || activeTasks(projectId()).length ? 'disabled' : ''}>${icon('spark', true)}按检查项识别文字</button>` : ''}${renderContinueOcr(d)}${ocrEligible(d) ? `<button class="text-action" data-action="ocr-document" data-id="${e(d.id)}" title="${state.config.ocrEnabled ? '全量识别图像文字并生成新版本；旧文件与旧报告保持不变' : 'OCR 尚未启用'}" ${!state.config.ocrEnabled || state.busy.has(`ocr-${d.id}`) || activeTasks(projectId()).length ? 'disabled' : ''}>${icon('refresh', true)}${state.busy.has(`ocr-${d.id}`) ? '正在提交…' : '重新识别文字（全文）'}</button>` : ''}${role === 'TENDER' ? `<button class="text-action" data-action="extract" data-id="${e(d.id)}" ${state.busy.has(`extract-${d.id}`) || activeTasks(projectId()).length ? 'disabled' : ''}>${icon('spark', true)}${currentTender()?.id === d.id ? '提取检查项' : '选用此版本并提取'}</button>` : ''}</div>${renderDocumentWarnings(d)}<details class="document-hash"><summary>文件 SHA-256</summary><code>${e(d.sha256 || '未提供')}</code></details></div>`).join('')}</div>` : ''}</div>`;
}
function documentWarningStats(document) {
  const warnings = [...new Set((document.warnings || []).map(String).filter(warning => warning.trim()))];
  const deferred = warning => warning.includes('[OCR_DEFERRED]');
  const failed = warning => warning.replaceAll(/\[OCR_DEFERRED\]\s*\[OCR未完成\]/g, '').includes('[OCR未完成]');
  const incomplete = warnings.filter(warning => deferred(warning) || failed(warning));
  const location = warning => warning.match(/^第\s*(\d+)\s*(页|个内容块|张图像|个文本分页)/)?.slice(1).join(':');
  const incompleteLocations = new Set(incomplete.map(location).filter(Boolean));
  const partialLocations = new Set(incomplete.filter(warning => warning.includes('[OCR已识别]')).map(location).filter(Boolean));
  const coverage = document.ocrCoverage || {};
  const count = items => new Set(items.map(location).filter(Boolean)).size + items.filter(warning => !location(warning)).length;
  const recognized = Math.max(0, Number(coverage.ocrLocations ?? document.ocrPages ?? document.pages?.filter(ocrPage).length) || 0);
  return {
    warnings,
    attention: warnings.filter(warning => warning.includes('[OCR结构未完成]') || failed(warning) || !deferred(warning) && !warning.includes('[OCR已识别]')),
    structure: countValue(coverage, 'structureIncompleteCount') ?? count(warnings.filter(warning => warning.includes('[OCR结构未完成]'))),
    deferred: Math.max(0, Number(coverage.deferredCount ?? count(warnings.filter(deferred))) || 0),
    failed: Math.max(0, Number(coverage.failedCount ?? count(warnings.filter(failed))) || 0),
    recognized,
    complete: Math.max(0, recognized - partialLocations.size),
    partial: partialLocations.size,
    incomplete: incompleteLocations.size,
    unlocated: incomplete.filter(warning => !location(warning)).length,
    hasIncomplete: coverage.ocrCoverageComplete === false || !!document.ocrIncomplete || incomplete.length > 0,
    unit: locationUnit(document.locationType || document.pages?.[0]?.locationType),
  };
}
function renderContinueOcr(document) {
  if (!ocrEligible(document) || !documentWarningStats(document).hasIncomplete) return '';
  return `<button class="text-action" data-action="ocr-continue-document" data-id="${e(document.id)}" title="复用同一原件与识别配置下的成功结果，按本轮预算继续未完成位置；生成新版本" ${!state.config.ocrEnabled || state.busy.has(`ocr-${document.id}`) || activeTasks(projectId()).length ? 'disabled' : ''}>${icon('refresh', true)}继续识别未完成位置</button>`;
}
function renderOcrTaskProgress(task) {
  const progress = task.ocrProgress, counts = progress?.counts;
  if (!counts || !progress.unitCount) return '';
  const count = key => Math.max(0, Number(counts[key]) || 0);
  const parts = [`已识别 ${count('SUCCEEDED')} 处`];
  if (count('RUNNING')) parts.push(task.status === 'RUNNING' ? `处理中 ${count('RUNNING')} 处` : `未完成 ${count('RUNNING')} 处，待继续处理`);
  if (count('PARTIAL_SUCCEEDED') + count('FAILED')) parts.push(`待重试或复核 ${count('PARTIAL_SUCCEEDED') + count('FAILED')} 处`);
  if (count('DEFERRED')) parts.push(`待补识别 ${count('DEFERRED')} 处`);
  if (progress.cachedUnitCount) parts.push(`已复用 ${Math.max(0, Number(progress.cachedUnitCount) || 0)} 处`);
  if (progress.batchCounts) {
    const completed = ['SUCCEEDED', 'RECOVERED', 'SPLIT_COMPLETED']
      .reduce((total, status) => total + Math.max(0, Number(progress.batchCounts[status]) || 0), 0);
    const splitting = Math.max(0, Number(progress.batchCounts.SPLITTING) || 0);
    if (completed) parts.push(`已完成 ${completed} 批`);
    if (splitting && task.status === 'RUNNING') parts.push(`拆分重试 ${splitting} 批`);
  }
  return `<p class="document-warning-caption">${e(parts.join(' · '))}</p>`;
}
function structuredCoverageLimitations(processing, maximum = null) {
  const pages = [...new Set((Array.isArray(processing?.contextLimitedPages) ? processing.contextLimitedPages : [])
    .filter(page => Number.isInteger(page) && page > 0 && (maximum == null || page <= maximum)))].sort((a, b) => a - b);
  const reported = processing?.contextLimitedPageCount;
  const count = Number.isInteger(reported) && reported >= 0 ? reported : pages.length;
  const codes = [...new Set((Array.isArray(processing?.contextCoverageLimitations) ? processing.contextCoverageLimitations : []).filter(value => typeof value === 'string' && value))];
  const labels = {
    STRUCTURED_FIELD_LIMIT_REACHED: '单页字段数量达到提取上限',
    STRUCTURED_FIELDS_REMAINING: '原页仍有字段未提取',
    STRUCTURED_SOURCE_TRUNCATED: '本次仅处理了部分原文内容',
    STRUCTURED_FIELDS_REJECTED: '未通过来源校验的字段未采纳，不代表原文没有材料',
    STRUCTURED_OUTPUT_TARGET_EXCEEDED: '字段数量超过建议范围，已接受字段仍保留，需核对完整性',
  };
  return { count, pages, reasons: [...new Set(codes.map(code => labels[code] || '部分字段未完整提取，请核对原文'))] };
}
function processingPageLink(documentId, page, previewAvailable) {
  if (!documentId) return `第 ${page} 页`;
  return previewAvailable
    ? `<button class="text-action" data-action="page-preview" data-id="${e(documentId)}" data-page="${page}">第 ${page} 页${icon('external', true)}</button>`
    : `<button type="button" class="text-action" disabled aria-disabled="true" aria-label="第 ${page} 页，解析完成后可预览" title="解析完成后可预览">第 ${page} 页</button>`;
}
function renderStructuredCoverageLimitations(processing, documentId, maximum = null, previewAvailable = true) {
  if (textOnlyProcessing(processing)) return '';
  const limited = structuredCoverageLimitations(processing, maximum);
  if (!limited.count && !limited.pages.length && !limited.reasons.length) return '';
  const notice = limited.count ? `${limited.count} 页字段提取范围有限，需核对原文` : '部分字段提取范围有限，需核对原文';
  return `<div class="document-warning-summary structured-coverage-limited"><p class="document-warning-caption"><strong>${e(notice)}</strong></p><details class="document-warning-details" ${disclosureAttributes(`structured-limited-${documentId}`)}><summary>查看对应原页与范围说明</summary><p>已提取的有效字段仍可查看，不代表该页全部字段均已提取。</p>${documentId && !previewAvailable ? '<p>解析完成后可预览原页，当前可先记录页码核对。</p>' : ''}${limited.reasons.length ? `<ul>${limited.reasons.map(reason => `<li>${e(reason)}</li>`).join('')}</ul>` : ''}${limited.pages.length ? `<ul class="document-warning-list">${limited.pages.map(page => `<li>${processingPageLink(documentId, page, previewAvailable)}</li>`).join('')}</ul>` : '<p>未提供可定位的页码，请结合原文件核对。</p>'}</details></div>`;
}
function processingCount(value) { return Number.isInteger(value) && value >= 0 ? value : null; }
function renderNoFactProgress(processing, documentId, maximum = null, previewAvailable = true) {
  if (textOnlyProcessing(processing)) return '';
  const windows = processingCount(processing?.contextNoFacts);
  const suppliedPages = Array.isArray(processing?.contextNoFactPages);
  const pages = [...new Set((suppliedPages ? processing.contextNoFactPages : []).filter(page => Number.isInteger(page) && page > 0 && (maximum == null || page <= maximum)))].sort((a, b) => a - b);
  let count = processingCount(processing?.contextNoFactPageCount) ?? (suppliedPages ? pages.length : null);
  if (windows > 0 && count === 0) count = null;
  if (!(windows > 0 || count > 0 || pages.length)) return '';
  const caption = `${windows > 0 ? `${windows} 组分析` : '有分析组'}含未提取到可用事实的页面${count == null ? '（去重页数待确认）' : `（${count} 个去重页）`}`;
  return `<div class="document-warning-summary no-fact-coverage"><p class="document-warning-caption"><strong>${e(caption)}</strong></p><p>未提取到可用事实不代表原文没有相关材料，请对照原页核对。</p><details class="document-warning-details" ${disclosureAttributes(`no-fact-${documentId}`)}><summary>查看需核对的页码</summary>${documentId && !previewAvailable ? '<p>解析完成后可预览原页，当前可先记录页码核对。</p>' : ''}${pages.length ? `<ul class="document-warning-list">${pages.map(page => `<li>${processingPageLink(documentId, page, previewAvailable)}</li>`).join('')}</ul>` : '<p>未提供可定位的页码，请结合原文件核对。</p>'}</details></div>`;
}
function renderIncrementalProgress(task) {
  const p = task.processingProgress;
  if (!p || !p.processedPages) return '';
  const textOnly = textOnlyProcessing(p);
  const incompletePages = Math.max(0, Number(p.incompletePages) || 0);
  const count = key => Math.max(0, Number(p.checkStates?.[key]) || 0);
  const parts = textOnly ? [`已整理文字与表格 ${Number(p.processedPages)} 个位置`] : [`已分流 ${Number(p.processedPages)} 个位置`, `已提取 ${Number(p.factCount) || 0} 条事实`];
  const windows = processingCount(p.contextWindows), pending = processingCount(p.contextPending), completed = processingCount(p.contextCompleted), review = processingCount(p.contextReviewRequired);
  const source = docs().find(document => document.id === p.documentId && (!document.projectId || document.projectId === projectId()));
  const maximum = countValue(source, 'pageCount');
  if (!textOnly && windows > 0) {
    if (pending != null && pending <= windows) parts.push(`本轮分析已结束 ${windows - pending} 组`);
    else parts.push(`已记录 ${windows} 组分析，结束数量待确认`);
    if (completed > 0) parts.push(`来源校验完成 ${completed} 组`);
  }
  if (!textOnly && pending > 0) parts.push(`${pending} 组${incompletePages ? '等待补齐文字' : '待继续处理'}`);
  if (!textOnly && review > 0) parts.push(`${review} 组仍需来源复核`);
  if (textOnly && incompletePages > 0) parts.push(`${incompletePages} 处文字识别未完成`);
  if (Number(p.pageReviewRequired) > 0) parts.push(`${Number(p.pageReviewRequired)} 页表格需核对`);
  if (!textOnly && count('EVIDENCE_FOUND')) parts.push(`${count('EVIDENCE_FOUND')} 项已找到证据`);
  if (!textOnly && count('REVIEW_REQUIRED')) parts.push(`${count('REVIEW_REQUIRED')} 项发现待核对问题`);
  const labels = { REVIEW_REQUIRED: '需核对', EVIDENCE_FOUND: '已找到证据', WAITING_EVIDENCE: '等待证据', WAITING_ANALYSIS: '等待进一步核验' };
  const preview = !textOnly && Array.isArray(p.preview) ? p.preview : [];
  const pageIssues = Array.isArray(p.pageReviewIssues) ? p.pageReviewIssues.slice(0, 5) : [];
  let continuation = '';
  if (task.status === 'SUCCEEDED') {
    if (incompletePages) {
      const source = docs().find(document => document.id === p.documentId);
      continuation = `<p class="document-warning-caption">仍有 ${e(incompletePages)} 处文字未完整。请先继续识别未完成位置，或在资料区全量重新识别，${textOnly ? '再进入全文检查' : '再处理跨页与证件材料'}。</p>${source ? renderContinueOcr(source) : ''}`;
    } else if (!textOnly && p.contextPending) {
      continuation = `<button class="text-action" data-action="continue-processing" data-id="${e(p.documentId)}" ${activeTasks(projectId()).length ? 'disabled' : ''}>继续处理未完成材料</button>`;
    }
  }
  return `<div class="incremental-progress"><p class="document-warning-caption">${e(parts.join(' · '))}</p>${textOnly ? `<p class="document-warning-caption text-processing-scope">${e(textProcessingBoundary)}</p>` : ''}${!textOnly && windows > 0 ? '<p class="document-warning-caption">分析结束包含待复核结果，不代表业务检查通过；待复核与待补可能重叠，组数不可相加；拆分记录分别统计。</p>' : ''}${renderNoFactProgress(p, p.documentId, maximum, !!source)}${renderStructuredCoverageLimitations(p, p.documentId, maximum, !!source)}${pageIssues.map(row => `<p class="document-warning-caption">来源位置 ${e(Number.isFinite(Number(row.page)) ? Number(row.page) : '待定位')}：${e((Array.isArray(row.issues) ? row.issues.slice(0, 3) : []).join('；'))}</p>`).join('')}${textOnly ? '' : renderPersonCredentialProgress(p)}${preview.length ? `<details ${disclosureAttributes(`incremental-task-${task.id}`)}><summary>查看增量预检（随页面处理更新）</summary><p class="document-warning-caption">${e(p.boundary || '完整业务结论以正式检查为准。')}</p>${preview.map(r => `<div class="task-stage"><strong>${e(labels[r.state] || '待核实')} · ${e(r.title)}</strong><p>${e(r.reason)}${r.sourcePages?.length ? ` 来源位置：${r.sourcePages.map(Number).filter(Number.isFinite).join('、')}` : ''}</p></div>`).join('')}</details>` : ''}${continuation}</div>`;
}
function personCredentialIssueRequiresReview(issue) {
  if (typeof issue?.requiresReview === 'boolean') return issue.requiresReview;
  if (issue?.severity === 'INFO') return false;
  if (issue?.severity === 'WARNING') return true;
  return /^(?:SOURCE_|PAGE_|DOCUMENT_INCOMPLETE$|COMPARISON_BUDGET$|PERSON_(?:PAGE_LIMIT|RECORD_LIMIT)|.*(?:NOT_PERSISTED|PERSISTENCE|BUDGET))/.test(String(issue?.code || ''));
}
function personCredentialIssueSections(coverage) {
  const issues = Array.isArray(coverage?.issues) ? coverage.issues : [];
  const actionable = Array.isArray(coverage?.actionableIssues) ? coverage.actionableIssues : issues.filter(personCredentialIssueRequiresReview);
  const informational = Array.isArray(coverage?.informationalIssues) ? coverage.informationalIssues : issues.filter(issue => !personCredentialIssueRequiresReview(issue));
  const requiresReview = typeof coverage?.requiresReview === 'boolean' ? coverage.requiresReview : actionable.length > 0 || Number(coverage?.pagesUnparsed) > 0 || Number(coverage?.pagesScanned) < Number(coverage?.pagesTotal);
  return { actionable, informational, requiresReview };
}
function personCredentialDifference(group) { return group && (group.status === 'CONFLICT' || group.status === 'SUSPECTED'); }
function renderPersonCredentialProgress(p) {
  const coverage = p.personCredentialCoverage;
  if (!coverage || typeof coverage !== 'object') return '';
  const total = Math.max(0, Number(coverage.pagesTotal) || 0), scanned = Math.max(0, Number(coverage.pagesScanned) || 0);
  const groups = (Array.isArray(p.personCredentialGroups) ? p.personCredentialGroups : []).filter(personCredentialDifference);
  const { actionable, informational, requiresReview } = personCredentialIssueSections(coverage);
  return `<details ${disclosureAttributes(`person-credentials-${p.documentId}`)}><summary>人员与证件核对 · ${scanned} / ${total} 处已扫描</summary><p>${coverage.status === 'NO_FACTS' ? '当前未提取到可比较的人员证件字段，不代表原文件没有相关材料。' : '按已提取且能定位原文的明确字段比较。'}</p>${groups.map(g => `<p class="document-warning-caption"><strong>${e(g.fieldLabel || '人员与证件差异')}</strong>：${e(g.reason || '需核对原文')}${g.sourcePages?.length ? ` · 来源位置 ${g.sourcePages.map(Number).filter(Number.isFinite).join('、')}` : ''}</p>`).join('')}${requiresReview ? `<p class="document-warning-caption">部分扫描或来源验证未完成。</p>${actionable.slice(0, 3).map(issue => `<p class="document-warning-caption">${Number.isInteger(Number(issue.page)) && Number(issue.page) > 0 ? `位置 ${Number(issue.page)}：` : ''}${e(issue.message || issue.reason || '该位置处理尚未完成')}</p>`).join('')}` : ''}${informational.length ? `<details><summary>提取说明 · ${informational.length} 条，不计入检查问题</summary>${informational.slice(0, 3).map(issue => `<p>${e(issue.message || issue.reason || '部分字段未参与比较')}</p>`).join('')}<p>完整说明与来源位置可在详情中查看。</p></details>` : ''}<button class="text-action" data-action="person-credentials" data-id="${e(p.documentId)}">查看核对范围与原文证据</button></details>`;
}
function renderPersonCredentialCoverage(coverage, documentId = '') {
  if (!coverage || typeof coverage !== 'object') return '';
  const number = key => Math.max(0, Number(coverage[key]) || 0);
  const { actionable, informational, requiresReview } = personCredentialIssueSections(coverage);
  const detail = (items, caption) => items.length ? `<details><summary>${caption} · ${items.length} 条</summary><ul>${items.map(issue => { const page = Number(issue.page), doc = issue.documentId || documentId; return `<li>${doc && Number.isInteger(page) && page > 0 ? `<button class="text-action" data-action="page-preview" data-id="${e(doc)}" data-page="${page}">${e(locationLabel({ page, locationType: issue.locationType || 'PAGE' }))}</button>：` : ''}${e(issue.message || issue.reason || '部分字段未参与比较')}</li>`; }).join('')}</ul></details>` : '';
  return `<section class="person-credential-coverage"><h3>人员与证件事实范围</h3><p>已扫描 ${number('pagesScanned')} / ${number('pagesTotal')} 处 · 证件或人员候选 ${number('candidatePages')} 处 · 已提取 ${number('occurrencesCount')} 条字段事实</p>${requiresReview ? '<p class="document-warning-caption">部分扫描或来源验证未完成，可查看对应位置。</p>' : ''}<p>${coverage.status === 'NO_FACTS' ? '未提取到可比较的人员证件字段，不代表原文件没有相关材料。' : '比较范围为已提取且能定位原文的字段；未列出差异不等于全文一致。'}</p>${detail(actionable, '查看未完成的处理')}${detail(informational, '提取说明（不计入检查问题）')}</section>`;
}
async function showPersonCredentials(id) {
  const pid = projectId();
  openModal('人员与证件核对', '正在读取已保存的来源事实', '<div class="modal-body"><span class="spinner"></span></div>');
  const version = state.modalVersion;
  let data;
  try { data = await api(`/api/documents/${enc(id)}/processing-facts`); }
  catch (error) {
    if (version === state.modalVersion && modal.open && pid === projectId()) openModal('人员与证件核对', '读取未完成', `<div class="modal-body"><p class="document-warning-caption">${e(error.message || '事实库暂时无法读取，请重试。')}</p></div>`);
    return;
  }
  if (version !== state.modalVersion || !modal.open || pid !== projectId()) return;
  const allGroups = Array.isArray(data.personCredentialGroups) ? data.personCredentialGroups : [];
  const groups = allGroups.filter(personCredentialDifference);
  const informationalGroups = [...(Array.isArray(data.personCredentialInformationalGroups) ? data.personCredentialInformationalGroups : Array.isArray(data.informationalGroups) ? data.informationalGroups : []), ...allGroups.filter(group => group.status === 'EVIDENCE_INSUFFICIENT')];
  const occurrences = Array.isArray(data.personCredentialOccurrences) ? data.personCredentialOccurrences : [];
  const renderGroup = group => {
    const ids = new Set(Array.isArray(group.occurrenceIds) ? group.occurrenceIds : []);
    const members = occurrences.filter(o => ids.has(o.id));
    const evidence = members.length ? members : (Array.isArray(group.evidences) ? group.evidences : []).map(evidence => ({ evidence, fieldLabel: group.fieldLabel }));
    return `<details class="consistency-group"><summary>${e(group.fieldLabel || group.title || '人员证件记录')} · ${e(group.status === 'CONFLICT' ? '信息冲突' : group.status === 'EVIDENCE_INSUFFICIENT' ? '关联说明' : '疑似不一致')}</summary><p>${e(group.reason || '')}</p>${evidence.map(renderConsistencyOccurrence).join('')}</details>`;
  };
  const body = groups.map(renderGroup).join('');
  openModal('人员与证件核对', '各处记录分别保留，点击来源位置可核对原文', `<div class="modal-body">${data.configurationCurrent === false ? '<p class="document-warning-caption">该记录使用旧版处理规则，请继续处理或重新检查以更新事实。</p>' : ''}${renderPersonCredentialCoverage(data.personCredentialCoverage, id)}${body || '<p>当前可比较字段中未列出差异。</p>'}${informationalGroups.length ? `<details class="person-credential-information"><summary>关联说明 · ${informationalGroups.length} 组，不计入检查问题</summary>${informationalGroups.map(renderGroup).join('')}</details>` : ''}<details><summary>查看已提取人员与证件字段 · ${occurrences.length} 条</summary>${occurrences.map(renderConsistencyOccurrence).join('')}</details></div>`, '', 'preview-modal');
}
function renderOcrDocumentStatus(document) {
  const stats = documentWarningStats(document);
  if (!stats.complete && !stats.partial && !stats.hasIncomplete && !stats.structure) return '';
  const layout = stats.structure ? `<span class="pill WARNING">${stats.structure} 处文字或表格布局待复核</span>` : '';
  if (stats.deferred) return `<div class="document-ocr-status"><span class="pill ocr-pill">按检查计划已识别 ${stats.recognized} 处</span><span class="pill WARNING">${stats.deferred} 处待按需补识别</span>${stats.failed ? `<span class="pill WARNING">另有 ${stats.failed} 处识别未完成</span>` : ''}${layout}</div><p class="document-warning-caption">计划延期不表示识别服务失败，未读取范围不能证明材料缺失或全文通过。</p>`;
  return `<div class="document-ocr-status">${stats.complete ? `<span class="pill ocr-pill">OCR 已识别 ${stats.complete} ${e(stats.unit)} · 待复核</span>` : ''}${stats.partial ? `<span class="pill WARNING">部分识别 ${stats.partial} ${e(stats.unit)}</span>` : ''}${stats.hasIncomplete ? `<span class="pill WARNING">OCR 未完成${stats.incomplete ? ` ${stats.incomplete} ${e(stats.unit)}` : ''}${stats.unlocated ? ` · 另有 ${stats.unlocated} 条未定位提示` : ''}</span>` : ''}${layout}</div>`;
}
function renderDocumentWarnings(document) {
  const stats = documentWarningStats(document);
  if (!stats.warnings.length) return '';
  return `<div class="document-warning-summary">${stats.attention.length ? `<div class="document-attention"><strong>需核对 ${stats.attention.length} 条提示</strong><ul class="document-warning-list" tabindex="0" aria-label="需核对的解析提示">${stats.attention.map(warning => `<li>${e(warning)}</li>`).join('')}</ul></div>` : '<p class="document-warning-caption">已识别文字仍需对照原图核实，签字盖章未核验。</p>'}<details class="document-warning-details"><summary>查看全部 ${stats.warnings.length} 条解析提示</summary><ul class="document-warning-list" tabindex="0" aria-label="全部解析提示">${stats.warnings.map(warning => `<li>${e(warning)}</li>`).join('')}</ul></details></div>`;
}
function renderTasks() {
  const jobs = [...state.tasks.values()].filter(t => t.projectId === projectId()).sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || ''))).slice(0, 8);
  if (!jobs.length) return '';
  const completed = jobs.filter(t => terminal.has(t.status) && !failed.has(t.status) && !t.error), visible = jobs.filter(t => !completed.includes(t));
  const content = list => `<section class="panel"><div class="panel-header"><h2>${icon('clock')}处理任务</h2></div><div class="panel-body task-list">${list.map(t => `<div class="task-item"><div class="task-top"><span class="task-title">${e(t.label || '文档处理')}</span>${badge(t.status, runStatuses[t.status] || t.status)}</div><div class="task-stage">${e(t.stage || '')}${typeof t.progress === 'number' ? ` · ${Math.round(t.progress)}%` : ''}</div>${renderOcrTaskProgress(t)}${renderIncrementalProgress(t)}${!terminal.has(t.status) && typeof t.progress === 'number' ? `<div class="progress-track" role="progressbar" aria-label="${e(t.label)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.max(0, Math.min(100, t.progress))}"><div class="progress-fill" style="width:${Math.max(0, Math.min(100, t.progress))}%"></div></div>` : ''}${t.error ? `<div class="task-error">${e(t.error)}</div>` : ''}${failed.has(t.status) ? `<button class="text-action" data-action="retry-task" data-id="${e(t.id)}">${icon('refresh', true)}重试任务</button>` : ''}</div>`).join('')}</div></section>`;
  return `${visible.length ? content(visible) : ''}${completed.length ? `<details class="task-history" ${disclosureAttributes('completed-tasks')}><summary>${icon('check', true)}${completed.length} 项处理已完成 · 展开任务记录</summary>${content(completed)}</details>` : ''}`;
}
function cachedDocument(id) {
  const value = state.documentCache.get(id);
  if (value) { state.documentCache.delete(id); state.documentCache.set(id, value); }
  return value;
}
async function loadDocument(id) {
  const cached = cachedDocument(id); if (cached) return cached;
  if (state.documentLoads.has(id)) return state.documentLoads.get(id);
  const promise = api(`/api/documents/${enc(id)}`).then(document => {
    state.documentCache.set(id, document); state.documentErrors.delete(id);
    while (state.documentCache.size > 4) {
      const victim = [...state.documentCache.keys()].find(key => key !== state.visibleSourceId && key !== id);
      state.documentCache.delete(victim || state.documentCache.keys().next().value);
    }
    return document;
  }).catch(error => { state.documentErrors.set(id, error.message); throw error; }).finally(() => state.documentLoads.delete(id));
  state.documentLoads.set(id, promise); return promise;
}
function sourceText(text, empty = '此位置未提取到文本。') {
  const value = String(text || empty), limit = 50000;
  return e(value.slice(0, limit)) + (value.length > limit ? '<p class="preview-caption">此内容块较长，当前显示前 50,000 字符。请下载原文件查看完整内容。</p>' : '');
}
function pageCaption(page) {
  if (page?.locationType === 'PAGE') return '以下按原始文件的真实页码定位。';
  if (page?.locationType === 'PARAGRAPH') return 'Word 原文按段落定位；此位置不是 PDF 页码。';
  if (page?.locationType === 'IMAGE') return '以下为上传图片与此图像的识别内容。';
  return '原文按内容块定位；内容块可能包含段落、表格或嵌入图像，此位置不是 PDF 页码。';
}
function renderPageContent(document, page, enlarged = false) {
  if (!page) return '<div class="empty-inline">当前文件没有可读取的文字，请下载原文件复核。</div>';
  const recognized = ocrPage(page), visual = imagePage(document, page);
  const warning = page.warning ? `<p class="document-warnings">${e(page.warning)}</p>` : '';
  const note = recognized ? '<div class="ocr-review-note">OCR 图像文字 · 待复核。请对照原图确认数字、日期与证照信息；文字识别不能验证签字或印章。</div>' : '';
  const text = `<div class="${enlarged ? 'text-preview' : 'source-text'}">${sourceText(page.text, recognized ? '此位置未获得可用的识别文字，请对照原图人工核对。' : '此位置未提取到文本。')}</div>`;
  if (!visual) return `${note}${warning}${text}`;
  const picture = `<img ${enlarged ? 'id="modal-preview-image"' : 'data-preview'} src="/api/documents/${enc(document.id)}/pages/${Number(page.number)}/image" class="${enlarged ? 'preview-page' : 'source-page-image'}" alt="${e(document.name)} ${e(locationLabel({ page: page.number, locationType: page.locationType }))}原图" loading="lazy">`;
  const original = enlarged ? picture : `<button class="source-image-button" data-action="page-preview" data-id="${e(document.id)}" data-page="${Number(page.number)}" aria-label="放大查看${e(locationLabel({ page: page.number, locationType: page.locationType }))}原图">${picture}</button>`;
  if (recognized) return `${note}${warning}${enlarged ? `<div class="ocr-preview-grid"><section><h3>识别文字</h3>${text}</section><section><h3>原图对照</h3>${original}</section></div>` : `${text}<details class="source-text-details source-original"><summary>查看原图 · 对照识别文字</summary>${original}</details>`}`;
  return `${warning}${original}${enlarged ? text : `<details class="source-text-details"><summary>查看提取文字</summary>${text}</details>`}`;
}
function positionInput(document, page, id) {
  const pages = document.pages || [];
  return `<input id="${id}" class="source-position" type="number" min="${Number(pages[0]?.number) || 1}" max="${Number(pages.at(-1)?.number) || 1}" step="1" value="${Number(page?.number) || 1}" data-document="${e(document.id)}" aria-label="输入原文位置编号"><span>${locationUnit(page?.locationType)} / 共 ${pages.length} ${locationUnit(page?.locationType)}</span>`;
}
function validatePosition(id, value) {
  const number = Number(value), document = cachedDocument(id);
  if (!Number.isInteger(number) || !document?.pages?.some(page => page.number === number)) { toast('该原文位置不存在，请输入有效编号。', true); return null; }
  return number;
}
function renderSourcePane(document, pageNumber = 1, label = '文件原文') {
  if (!document) return `<section class="panel source-pane"><div class="panel-header"><h2>${icon('document')}${label}</h2></div><div class="empty-inline">上传文件后，可在此对照原文。</div></section>`;
  state.visibleSourceId = document.id;
  document = cachedDocument(document.id) || document;
  if (!Array.isArray(document.pages)) {
    const id = document.id, pid = projectId(), error = state.documentErrors.get(id);
    if (!error && !state.documentLoads.has(id)) loadDocument(id).catch(() => {}).finally(() => { if (pid === projectId() && state.visibleSourceId === id) render(); });
    return `<section class="panel source-pane"><div class="panel-header"><h2>${icon('document')}${e(label)}</h2><a class="text-action" href="/api/documents/${enc(id)}/raw" download>下载原文件</a></div><div class="source-toolbar"><div class="source-name">${e(document.name)}</div></div><div class="empty-inline">${error ? `${e(error)}<br><button class="button button-secondary button-small" data-action="retry-document" data-id="${e(id)}">重新加载原文</button>` : '<span class="spinner"></span> 正在加载原文…'}</div></section>`;
  }
  const pages = document.pages || [], page = pages.find(p => p.number === Number(pageNumber)) || pages[0];
  return `<section class="panel source-pane"><div class="panel-header"><h2>${icon('document')}${e(label)}</h2><a class="text-action" href="/api/documents/${enc(document.id)}/raw" download aria-label="下载原文件">${icon('download', true)}</a></div><div class="source-toolbar"><div class="source-name" title="${e(document.name)}">${e(document.name)} <span class="document-version">V${Number(document.version)}</span></div>${pages.length ? `<label class="source-selector">原文位置${positionInput(document, page, 'source-page')}</label>` : ''}</div><div class="source-content">${renderPageContent(document, page)}</div></section>`;
}

function renderCheckpointCard(c) {
  const requirement = c.expected || '对照全文核对信息一致性', shortened = requirement.length > 180;
  return `<article class="checkpoint-card ${state.selectedCheckpoint === c.id ? 'selected' : ''}"><div class="checkpoint-card-top"><div><h3 class="checkpoint-title"><button class="checkpoint-title-button" data-action="select-checkpoint" data-id="${e(c.id)}">${e(c.title)}</button></h3><p class="checkpoint-requirement checkpoint-requirement-summary">${e(shortened ? `${requirement.slice(0, 180)}…` : requirement)}</p></div>${badge(c.severity, severities[c.severity] || c.severity)}</div><details class="checkpoint-card-details" ${disclosureAttributes(`checkpoint-${c.id}`)}><summary>${shortened ? '完整要求与检查依据' : '检查方式与依据'}</summary>${shortened ? `<p class="checkpoint-requirement">${e(requirement)}</p>` : ''}<p class="checkpoint-rule">${e(types[c.type] || c.type)} · ${e(fields[c.field] || c.field)} ${e(operatorLabel(c.operator))} ${e(unitLabel(c.unit))}</p>${c.source ? `<button class="checkpoint-source" data-action="select-checkpoint" data-id="${e(c.id)}">${icon('pin', true)}招标依据 · ${e(locationLabel(c.source))}<span>${e(c.source.quote)}</span></button>` : `<p class="checkpoint-rule">${c.type === 'FULL_TEXT_REVIEW' ? '系统内置检查 · 基于全文和项目基础信息核对' : '手动或通用检查项，无独立招标原文。'}</p>`}${renderCheckpointSpecSummary(c)}</details><div class="checkpoint-card-actions"><label class="checkbox-label"><input type="checkbox" data-toggle-checkpoint="${e(c.id)}" ${c.enabled ? 'checked' : ''} ${state.busy.has(`checkpoint-${c.id}`) ? 'disabled' : ''}>启用</label>${c.confirmed ? `<span class="confirmed-tag">${icon('check', true)}已确认</span>` : '<span class="pending-tag">待确认</span>'}<div class="checkpoint-action-buttons"><button class="button button-ghost button-small" data-action="edit-checkpoint" data-id="${e(c.id)}">修改</button><button class="button button-ghost button-small danger-text" data-action="delete-checkpoint" data-id="${e(c.id)}">删除</button>${!c.confirmed ? `<button class="button button-subtle button-small" data-action="confirm-checkpoint" data-id="${e(c.id)}" ${state.busy.has(`checkpoint-${c.id}`) ? 'disabled' : ''}>确认</button>` : ''}</div></div></article>`;
}
function renderRequirementMappingSummary() {
  const mapping = state.project?.requirementMapping;
  if (!mapping || mapping.status !== 'READY') {
    const warning = mapping?.reason || state.project?.project?.requirementMappingWarning;
    return warning ? `<section class="panel requirement-mapping-summary"><div class="panel-body"><div class="requirement-mapping-heading"><div><h3>条款模板覆盖</h3><p>${e(warning)}</p></div><button class="button button-secondary button-small" data-action="refresh-requirement-mapping" ${processing() || state.busy.has('requirement-mapping') ? 'disabled' : ''}>${icon('refresh', true)}重新整理</button></div></div></section>` : '';
  }
  const summary = mapping.summary || {}, number = key => Math.max(0, Number(summary[key]) || 0);
  const labels = { NEEDS_CONFIRMATION: '待确认', EXTERNAL_REQUIRED: '需外部核验', UNSUPPORTED: '暂不支持' };
  const items = Array.isArray(mapping.reviewItems) ? mapping.reviewItems : [];
  const reviewCount = Math.max(items.length, Number(mapping.reviewItemCount) || 0);
  return `<section class="panel requirement-mapping-summary" aria-label="招标条款模板覆盖"><div class="panel-body"><div class="requirement-mapping-heading"><div><h3>条款模板覆盖</h3><p>系统已整理可转成固定核验模板的条款范围。这里是映射草稿，不会自动进入检查或改变业务结论。</p></div><button class="button button-secondary button-small" data-action="refresh-requirement-mapping" ${processing() || state.busy.has('requirement-mapping') ? 'disabled' : ''}>${state.busy.has('requirement-mapping') ? '<span class="spinner"></span>' : icon('refresh', true)}重新整理</button></div><div class="requirement-mapping-counts"><div><span>已映射</span><strong>${number('mapped')}</strong></div><div><span>待确认</span><strong>${number('needsConfirmation')}</strong></div><div><span>需外部核验</span><strong>${number('externalRequired')}</strong></div><div><span>暂不支持</span><strong>${number('unsupported')}</strong></div></div>${reviewCount ? `<details class="requirement-mapping-details" ${disclosureAttributes('requirement-mapping-details')}><summary>查看需处理条款 · ${reviewCount} 条</summary><div class="requirement-mapping-items">${items.map(item => `<article><div><span class="pill ${e(item.status === 'UNSUPPORTED' ? 'NOT_APPLICABLE' : 'WARNING')}">${e(labels[item.status] || item.status)}</span><span>${e(item.locationType === 'PAGE' ? `第 ${item.location} 页` : `位置 ${item.location}`)}</span></div><p>${e(item.text || '未保留条款摘录')}</p><small>${e(item.explanation || '')}</small></article>`).join('')}${mapping.reviewItemsTruncated ? '<p class="checkpoint-rule">其余条款已保存在完整覆盖清单中，页面仅展示前 30 条。</p>' : ''}</div></details>` : '<p class="requirement-mapping-complete">当前没有待确认、外部核验或暂不支持的条款片段。</p>'}<p class="requirement-mapping-footnote">本地整理 ${number('checkingFragments')} 个检查片段，另识别 ${number('nonChecking')} 个标题或说明片段。</p></div></section>`;
}
function renderCheckpoints() {
  const cp = checkpoints(), pending = cp.filter(c => !c.confirmed), tender = docs().find(d => d.id === state.sourceDoc) || activeTender(), selected = cp.find(c => c.id === state.selectedCheckpoint && (state.checkpointGroup==='ALL'||group(c)===state.checkpointGroup));
  const counts=cp.reduce((out,c)=>(out[group(c)]=(out[group(c)]||0)+1,out),{}),definitions=categoryDefinitions(Object.keys(counts)),visible=cp.filter(c=>state.checkpointGroup==='ALL'||group(c)===state.checkpointGroup);
  const emptyGroups = Object.entries(groupNames).filter(([key]) => !cp.some(c => group(c) === key));
  const emptySummary = state.checkpointGroup === 'ALL' && emptyGroups.length ? `<p class="checkpoint-empty-summary checkpoint-rule">${processing() ? '以下类别正在提取：' : '以下类别未提取到要求：'}${emptyGroups.map(([key, label]) => `<button class="text-action" data-action="checkpoint-group" data-group="${e(key)}">${e(label)}</button>`).join('、')}。可核对原文后<button class="text-action" data-action="add-checkpoint" ${!usableTender() || processing() ? 'disabled' : ''}>新增检查项</button>。</p>` : '';
  return `<div class="checkpoint-toolbar"><div><h2 class="section-title">逐项核对招标要求</h2><p>点击检查项定位招标原文。可修改名称与要求、手动新增或删除，保存到当前项目。</p></div><div class="checkpoint-toolbar-actions"><button class="button button-secondary button-small" data-action="add-checkpoint" ${!usableTender() || processing() ? 'disabled' : ''}>${icon('plus', true)}新增检查项</button>${state.templateCatalog.length ? `<button class="button button-secondary button-small" data-action="confirm-template-set" ${state.busy.has('confirm-template-set') || processing() ? 'disabled' : ''}>${icon('shield', true)}确认模板检查集</button>` : ''}${pending.length ? `<button class="button button-primary button-small" data-action="confirm-all" ${state.busy.has('confirm-all') || processing() ? 'disabled' : ''}>${icon('check', true)}确认全部 ${pending.length} 项</button>` : ''}</div></div>${pending.length ? `<div class="inline-warning">${pending.length} 项要求待核对；启用项全部确认后可上传投标文件。</div>` : ''}${state.project.project.checkpointExtractionWarnings?.length ? `<div class="inline-warning">${state.project.project.checkpointExtractionWarnings.map(e).join('<br>')}</div>` : ''}<div id="task-region">${renderTasks()}</div>${renderRequirementMappingSummary()}${renderCategoryNavigation(counts,state.checkpointGroup,'checkpoint-group','检查项')}<div class="checkpoint-layout"><div class="stack checkpoint-source-column">${selected ? `<section class="panel selected-requirement"><div class="panel-header"><h2>${icon('pin')}当前检查项来源</h2></div><div class="panel-body"><strong>${e(selected.title)}</strong>${selected.source ? `<p class="source-location">${e(selected.source.documentName || tender?.name || '招标文件')} · ${e(locationLabel(selected.source))}</p><blockquote class="evidence-quote">${e(selected.source.quote)}</blockquote>` : '<p class="checkpoint-rule">此项无对应招标原文位置，请按已保存的检查要求核对。</p>'}${renderCheckpointSpecSummary(selected)}</div></section>` : ''}${renderSourcePane(tender, state.sourcePage, '招标原文')}</div><div class="stack"><section class="panel"><div class="panel-header"><h2>${icon('list')}检查项清单</h2><span class="pill">${visible.length} 项${state.checkpointGroup==='ALL'?'':` · ${e(categoryLabel(state.checkpointGroup))}`}</span></div><div class="checkpoint-cards">${Object.entries(definitions).filter(([key]) => state.checkpointGroup === 'ALL' ? cp.some(c => group(c) === key) : state.checkpointGroup === key).map(([key, label]) => { const list = cp.filter(c => group(c) === key); return `<div class="checkpoint-group-title">${e(label)}<span>${list.length}</span></div>${list.length ? list.map(renderCheckpointCard).join('') : `<div class="checkpoint-group-empty">${processing() ? '正在提取，请稍候。' : '未提取到此类要求，可核对原文后手动新增。'}<button class="text-action" data-action="add-checkpoint" data-group="${e(key)}" ${processing() ? 'disabled' : ''}>新增</button></div>`}`; }).join('')}${emptySummary}</div></section></div></div><div class="workflow-footer"><button class="button button-secondary" data-action="tab" data-tab="documents">${icon('back', true)}基础信息</button><p>${checksReady() ? '检查项已确认，可上传投标文件。' : '请确认或停用所有启用的候选项，并保留至少一项用于检查。'}</p><button class="button button-primary" data-action="tab" data-tab="profile" ${!checksReady() || processing() ? 'disabled' : ''}>下一步，上传投标文件 ${icon('arrow', true)}</button></div>`;
}
function findingEvidence(finding) {
  const run = state.run?.run || {}, documents = state.run?.documents || docs();
  const belongs = (id, role) => documents.some(document => document.id === id && document.projectId === run.projectId && document.role === role);
  const tenderId = run.tenderDocumentId, original = finding.checkpoint.source;
  const source = original && belongs(original.documentId, 'TENDER') && (!tenderId || original.documentId === tenderId) ? original : null;
  const key = reference => JSON.stringify([reference.documentId, reference.page, reference.locationType, reference.quote]);
  const seen = new Set(source ? [key(source)] : []);
  const references = (finding.evaluation.evidences || []).filter(Boolean);
  const tender = tenderId && belongs(tenderId, 'TENDER') ? references.filter(reference => {
    if (reference.documentId !== tenderId || seen.has(key(reference))) return false;
    seen.add(key(reference)); return true;
  }) : [];
  const bid = belongs(run.documentId, 'BID') ? references.filter(reference => reference.documentId === run.documentId) : [];
  return { source, tender, bid };
}
function findingPresentation(finding) {
  const ev = finding.evaluation, value = state.run?.ocrContext?.findings?.[finding.id];
  const text = (key, fallback = '') => typeof value?.[key] === 'string' ? value[key] : String(fallback || '');
  const atomic = atomicCheckOf(finding), display = atomic && atomicPresentation(atomic), review = finding.assessment?.ocrReview;
  const useDisplay = display && ev.status === 'UNDETERMINED' && atomic.businessDecision === ev.status && review?.required !== true && review?.incomplete !== true;
  return {
    reason: useDisplay ? display.reason : atomic ? atomicReadable(text('reason', ev.reason), atomic.conditions) : text('reason', ev.reason), suggestion: useDisplay ? display.nextSteps.slice(0, 2).join('；') : atomic ? atomicReadable(text('suggestion', ev.suggestion), atomic.conditions) : text('suggestion', ev.suggestion),
    note: text('note', String(ev.method || '').includes('/OCR_REVIEW') ? '此结果带有 OCR 复核标记。请对照原图核对识别文字；文字识别不等于签章验证。' : ''),
    legacyClarified: value?.legacyClarified === true,
    originalReason: text('originalReason', ev.reason), originalSuggestion: text('originalSuggestion', ev.suggestion),
  };
}
function assessmentOf(finding) {
  return finding.assessment && typeof finding.assessment === 'object' && !Array.isArray(finding.assessment) ? finding.assessment : null;
}
function templateResultOf(finding) {
  const assessment = assessmentOf(finding) || {};
  const values = [finding?.resultProjection, finding?.projection, assessment.resultProjection, assessment.templateResult];
  return values.find(value => value && typeof value === 'object' && !Array.isArray(value) && value.schemaVersion === 'template-result-v1') || null;
}
function finalResultProjectionOf(finding) {
  const assessment = assessmentOf(finding) || {};
  const direct = [finding?.resultProjection, finding?.projection, assessment.resultProjection]
    .find(value => value && typeof value === 'object' && !Array.isArray(value) && value.schemaVersion === 'template-result-v1' && value.finalReconciled !== false);
  if (direct) return direct;
  const template = assessment.templateResult;
  return template && template.schemaVersion === 'template-result-v1' && template.finalReconciled === true ? template : null;
}
function templateMetaValue(finding, key) {
  const assessment = assessmentOf(finding) || {}, template = templateResultOf(finding), checkpoint = finding?.checkpoint || {};
  return template?.[key] || assessment.checkSpec?.[key] || assessment.templateSpec?.[key] || checkpoint.spec?.[key] || checkpoint[key] || '';
}
function findingPurpose(finding) {
  const purpose = String(templateMetaValue(finding, 'purpose') || '').toUpperCase();
  return Object.hasOwn(purposeLabels, purpose) ? purpose : '';
}
function findingVerificationMode(finding) {
  const mode = String(templateMetaValue(finding, 'verificationMode') || '').toUpperCase();
  return Object.hasOwn(verificationModeLabels, mode) ? mode : '';
}
function findingCategory(finding) {
  const category = String(templateMetaValue(finding, 'category') || '').trim();
  return Object.hasOwn(categories, category) ? categories[category] : group(finding?.checkpoint || {});
}
function findingMatchesResultScope(finding) {
  return (state.purposeFilter === 'ALL' || findingPurpose(finding) === state.purposeFilter)
    && (!state.externalOnly || findingVerificationMode(finding) === 'EXTERNAL');
}
function templateBusinessState(conclusion, projection = {}) {
  const state = String(conclusion || '').toUpperCase();
  if (state === 'SATISFIED' || state === 'PASS') return 'SATISFIED';
  if (['NOT_SATISFIED','SUSPECTED_RISK','FAIL','WARNING'].includes(state)) return 'RISK';
  if (state === 'NOT_APPLICABLE') return 'NOT_APPLICABLE';

  // UNDETERMINED is a conclusion, not a useful business queue. Route it by the
  // typed template contract so the UI agrees with the persisted presentation.
  const executionState = String(projection.execution?.state || '').toUpperCase();
  const evidenceState = String(projection.evidence?.state || '').toUpperCase();
  const actionOwner = String(projection.action?.owner || 'NONE').toUpperCase();
  const actionType = String(projection.action?.type || '').toUpperCase();
  const verificationMode = String(projection.verificationMode || '').toUpperCase();
  const missingInputs = Array.isArray(projection.missingInputs) ? projection.missingInputs.map(value => String(value || '')) : [];
  const executionIncomplete = ['QUEUED','RUNNING','RETRY_WAIT','FAILED','BLOCKED','CANCELLED','PAUSED'].includes(executionState);
  const systemRepair = actionOwner === 'SYSTEM' && ['FIX_CHECK_SPEC','RETRY_TEMPLATE','CLARIFY_REQUIREMENT','RESOLVE_FINAL_GUARD'].includes(actionType);
  if (executionIncomplete || systemRepair || missingInputs.some(value => value.startsWith('system.'))) return 'SYSTEM_INCOMPLETE';
  if (actionOwner === 'USER' || actionOwner === 'EXTERNAL' || verificationMode === 'EXTERNAL') return 'USER_ACTION';
  if (['PARTIAL','CONFLICTING','NOT_LOCATED'].includes(evidenceState) || actionOwner === 'SYSTEM' && actionType === 'LOCATE_EVIDENCE') return 'EVIDENCE_PENDING';
  if (actionOwner === 'SYSTEM' || evidenceState === 'SCOPE_UNRESOLVED') return 'SYSTEM_INCOMPLETE';
  return 'EVIDENCE_PENDING';
}
function templateBusinessPresentation(finding, projection) {
  const conclusion = projection.conclusion && typeof projection.conclusion === 'object' ? projection.conclusion : {};
  const action = projection.action && typeof projection.action === 'object' ? projection.action : {};
  const evidence = projection.evidence && typeof projection.evidence === 'object' ? projection.evidence : {};
  const conclusionState = String(conclusion.state || 'UNDETERMINED').toUpperCase();
  const resultState = templateBusinessState(conclusionState, projection), actionOwner = String(action.owner || 'NONE').toUpperCase();
  const missing = Array.isArray(projection.missingInputs) ? projection.missingInputs.filter(Boolean) : [];
  const nextStep = [projection.nextStep, action.nextStep, action.summary, action.message, action.instruction].find(value => typeof value === 'string' && value.trim())
    || ({
      SYSTEM_INCOMPLETE: '需继续处理模板、要求范围或失败请求；当前不要求补交材料，也不计入人工核对待办。',
      EVIDENCE_PENDING: '需继续检索并关联现有文件中的证据；只有确认具体缺口后才要求补充材料。',
      USER_ACTION: actionOwner === 'EXTERNAL' || String(projection.verificationMode || '').toUpperCase() === 'EXTERNAL'
        ? '等待条款指定的外部记录或核验阶段；现有文件不能替代该记录。'
        : '请按本项列出的缺口补充或确认具体信息。',
      RISK: '对照所列原文确认风险，并记录复核结论。',
      SATISFIED: '本项已形成满足要求的结论，依据保留供追溯。',
      NOT_APPLICABLE: '本项不适用于当前条件，适用依据保留供追溯。',
    })[resultState] || (missing.length ? `本项还缺少 ${missing.length} 项明确输入，请查看关键子项。` : '本项已形成当前结论，依据保留供追溯。');
  const userActionRequired = resultState === 'USER_ACTION' || resultState === 'RISK';
  const owner = ['SYSTEM_INCOMPLETE','EVIDENCE_PENDING'].includes(resultState) ? 'SYSTEM' : userActionRequired ? 'USER' : 'NONE';
  return {
    state: resultState,
    label: ['SYSTEM_INCOMPLETE','EVIDENCE_PENDING','USER_ACTION'].includes(resultState)
      ? businessStates[resultState]
      : typeof conclusion.label === 'string' && conclusion.label.trim() ? conclusion.label : businessStates[resultState] || conclusionLabels[conclusionState] || '未形成结论',
    finalStatus: finding.evaluation?.status || conclusionState,
    userActionRequired,
    actionId: typeof action.actionId === 'string' ? action.actionId : '', owner, actionOwner,
    reason: typeof conclusion.summary === 'string' && conclusion.summary.trim() ? conclusion.summary : typeof projection.summary === 'string' ? projection.summary : '服务端未提供结论摘要。',
    nextStep,
    proofScope: conclusion.proofScope || '', evidenceState: evidence.state || '', executionState: projection.execution?.state || '', projection,
  };
}
const currentBusinessResultVersion = 'business-result-3';
const legacyBusinessResultVersions = new Set(['business-result-1','business-result-2']);
function adaptLegacyBusinessPresentation(saved, computed, status) {
  const version = saved?.version;
  if (!legacyBusinessResultVersions.has(version) || saved.finalStatus !== status || !Object.hasOwn(businessStates, saved.state)) return computed;
  // Old projections may contribute copy only when their business state still agrees with the
  // current projection. Ownership, actions and next steps are always recalculated.
  if (saved.state !== computed.state) return computed;
  return {
    ...computed,
    label: typeof saved.label === 'string' && saved.label.trim() ? saved.label : computed.label,
    reason: typeof saved.reason === 'string' && saved.reason.trim() ? saved.reason : computed.reason,
    adaptedFrom: version,
  };
}
function applyManualReviewPresentation(automatic, review) {
  if (!review || !Object.hasOwn(statuses, review.status)) return automatic;
  const reviewState = ({PASS:'SATISFIED',FAIL:'RISK',WARNING:'RISK',NOT_APPLICABLE:'NOT_APPLICABLE'})[review.status] || 'EVIDENCE_PENDING';
  const nextStep = ({
    SATISFIED:'人工复核已确认满足，自动结论与复核记录均保留供追溯。',
    RISK:'人工复核已记录风险结论，请按复核说明处理并保留依据。',
    NOT_APPLICABLE:'人工复核已确认不适用，适用依据与复核记录均保留。',
    EVIDENCE_PENDING:'人工复核尚未形成确定结论，请按复核说明继续补齐判断依据。',
  })[reviewState];
  return {
    ...automatic,
    state: reviewState,
    label: businessStates[reviewState],
    finalStatus: review.status,
    userActionRequired: reviewState === 'RISK',
    owner: reviewState === 'RISK' ? 'USER' : reviewState === 'EVIDENCE_PENDING' ? 'SYSTEM' : 'NONE',
    reason: typeof review.note === 'string' && review.note.trim() ? review.note : automatic.reason,
    nextStep,
    automaticPresentation: automatic,
  };
}
function businessPresentation(finding, includeReview = false) {
  const assessment = assessmentOf(finding) || {}, atomic = atomicCheckOf(finding), ocr = assessment.ocrReview || {};
  const status = finding.evaluation.status, saved = assessment.businessPresentation;
  const job=resolutionJobOf(finding),expectedExecution=job?.status==='SUCCEEDED'?'FINISHED':job?.status;
  const savedResolutionCurrent=!job||saved?.dimensions?.execution?.jobId===job.id&&saved?.dimensions?.execution?.state===expectedExecution;
  const stopped=['BLOCKED_NO_PROGRESS','FAILED'].includes(resolutionJobOf(finding)?.status);
  const recoveryFailed=resolutionJobOf(finding)?.status==='FAILED',fieldIncomplete=(atomic?.fieldCoverage||[]).some(row=>['FAILED','UNREAD'].includes(row.status));
  const mappingGap=(atomic?.gaps||[]).find(gap=>atomicMappingPending(gap.code));
  const uniquePriceScope=(atomic?.gaps||[]).some(gap=>gap.code==='UNIQUE_PRICE_SCOPE_REQUIRED');
  const currentSaved=saved?.version===currentBusinessResultVersion&&saved.finalStatus===status&&savedResolutionCurrent&&Object.hasOwn(businessStates,saved.state)
    &&!(status==='UNDETERMINED'&&(recoveryFailed||fieldIncomplete||mappingGap||uniquePriceScope)&&saved.state!=='SYSTEM_INCOMPLETE')&&!(stopped&&status==='UNDETERMINED');
  let automatic;
  if(currentSaved)automatic=saved;
  const projection = finalResultProjectionOf(finding);
  if(!automatic&&projection)automatic=templateBusinessPresentation(finding, projection);
  if(automatic)return includeReview?applyManualReviewPresentation(automatic,reviewOf(finding)):automatic;
  const codes = [...(assessment.adjustments || []), ...(assessment.guards || []), ...(atomic?.gaps || [])].map(item => item?.code || '');
  const gap=assessment.coverage?.semanticGap||{},gapKind=gap.validated===true?gap.kind:'';
  const completeness = assessment.coverage?.completeness || {}, resolvedInventory = codes.includes('REQUIREMENT_COMPLETENESS_UNPROVEN') && completeness.inventoryResolved === true && completeness.coreRequirementDeferred !== true;
  const finalMismatch = status === 'UNDETERMINED' && atomic?.workflowState === 'COMPLETED' && atomic.complete === true;
  const processing = recoveryFailed || codes.includes('UNIQUE_PRICE_SCOPE_REQUIRED') || codes.some(atomicMappingPending) || ['SYSTEM_REQUIREMENT','SYSTEM_RETRIEVAL','SYSTEM_RELATION'].includes(gapKind) || fieldIncomplete || assessment.model?.decision==='RULE_UNSUPPORTED' || finalMismatch || (completeness.itemResults||[]).some(item=>item.status==='REQUIREMENT_UNRESOLVED') || ['PROCESSING_FAILED','REQUIREMENT_UNPARSED'].includes(atomic && atomicPresentation(atomic).state) || ['ENGINE_FAILURE','ERROR'].includes(atomic?.workflowState) || assessment.model?.state === 'UNAVAILABLE' || ocr.incomplete === true || codes.filter(code=>!resolvedInventory||code!=='REQUIREMENT_COMPLETENESS_UNPROVEN').some(code => /^MODEL_|^HTTP_|COMPILATION|^REQUIREMENT_|GROUNDING|UNGROUNDED$/.test(code) || ['CONTEXT_BUDGET_EXCEEDED','CHECKLIST_BATCH_REQUIRED','INPUT_INVALID','EVIDENCE_SCOPE_INCOMPLETE','OCR_COVERAGE_DEFERRED','OCR_SOURCE_DEFERRED','OUTPUT_SCHEMA_INVALID','OUTPUT_JSON_INVALID'].includes(code));
  const external = gapKind==='EXTERNAL_INPUT' || (atomic?.externalRequests || []).length > 0 || atomic?.workflowState === 'WAIT_STAGE' || codes.some(code => ['EXTERNAL_RECORD_REQUIRED','EXTERNAL_VERIFICATION_REQUIRED','USER_INPUT_REQUIRED','REFERENCE_TIME_REQUIRED','APPLICABILITY_TIME_PENDING'].includes(code));
  const beforeVisual = (assessment.preOcrEvaluation || assessment.rawEvaluation)?.status;
  const evidencePending = assessment.model?.decision==='EXTERNAL_INPUT_REQUIRED' || resolvedInventory || beforeVisual === 'UNDETERMINED' || ['RELATION_UNCONFIRMED','EVIDENCE_NOT_FOUND'].includes(atomic && atomicPresentation(atomic).state) || codes.some(code=>['EVIDENCE_MISSING','PERSONNEL_COVERAGE_INCOMPLETE','NO_CANDIDATE_EVIDENCE','EVIDENCE_NOT_FOUND'].includes(code));
  const visual = ocr.required === true;
  const resultState = ({PASS:'SATISFIED',FAIL:'RISK',WARNING:'RISK',NOT_APPLICABLE:'NOT_APPLICABLE'})[status] || (processing ? 'SYSTEM_INCOMPLETE' : external ? 'USER_ACTION' : evidencePending ? 'EVIDENCE_PENDING' : visual ? 'USER_ACTION' : 'EVIDENCE_PENDING');
  const userActionRequired = resultState === 'USER_ACTION' || resultState === 'RISK' || resultState === 'SATISFIED' && visual;
  let nextStep = ({SYSTEM_INCOMPLETE:'需继续处理条款、证据校验或失败请求；当前不要求补交材料，也不计入人工核对待办。',EVIDENCE_PENDING:'需继续检索并关联现有文件中的证据；只有确认具体缺口后才要求补充材料。',USER_ACTION:'按下方说明补充外部信息、确认核验时点或核对原图。',RISK:'对照所列原文确认风险，并记录复核结论。',SATISFIED:visual?'文字要求已得到支持；另有原图复核事项，请核对下方指定位置。':'本项已形成满足要求的结论，依据保留供追溯。',NOT_APPLICABLE:'本项不适用于当前条件，适用依据保留供追溯。'})[resultState];
  if(stopped&&['SYSTEM_INCOMPLETE','EVIDENCE_PENDING'].includes(resultState))nextStep='本轮自动补查已停止，需修复处理环节或补足检索条件后再启动；当前不据此要求补交材料。';
  if(resultState==='USER_ACTION'&&gapKind==='EXTERNAL_INPUT')nextStep='需取得招标条款要求的外部记录或现场核验结果；现有文件检索不能替代该记录。';
  if(resultState==='SYSTEM_INCOMPLETE'&&!stopped&&codes.includes('UNIQUE_PRICE_SCOPE_REQUIRED'))nextStep='系统尚未核全本项目的报价与备选方案范围；已识别报价一致仅作局部结果，当前不要求补交材料。';
  const scope=resultState==='SATISFIED'?findingProofScope(finding):{};
  if(scope.state==='WRITTEN_RESPONSE')nextStep='已核对所引文件的书面响应；这不等于实际资格状态或外部查询结果已核实。';
  if(scope.state==='OBSERVED_OCCURRENCES')nextStep='已识别的同对象同口径记录未见差异；结论范围仅为所列出现记录。';
  if(scope.state==='ROLE_CONFIGURATION_ONLY')nextStep='已确认要求岗位有明确的本次任职人员；该结论不代表人数上限、兼任关系、人员资质或跨材料身份已核实。';
  let reason=finalMismatch?'已完成的辅助条件不足以证明整项业务要求；最终结果仍未确定。':finding.evaluation.reason;
  if(resultState==='SYSTEM_INCOMPLETE'&&!finalMismatch&&!recoveryFailed&&atomicPresentation(atomic).state==='RELATION_UNCONFIRMED'&&mappingGap){
    if(typeof mappingGap.description==='string'&&mappingGap.description.trim())reason=mappingGap.description;
    if(!stopped)nextStep='系统需继续完成证据字段或人员单位对应；当前不要求补交材料，也不计入人工核对待办。';
  }
  automatic={state:resultState,label:scope.label||businessStates[resultState],finalStatus:status,userActionRequired,owner:['SYSTEM_INCOMPLETE','EVIDENCE_PENDING'].includes(resultState)?'SYSTEM':userActionRequired?'USER':'NONE',reason,nextStep};
  automatic=adaptLegacyBusinessPresentation(saved,automatic,status);
  return includeReview?applyManualReviewPresentation(automatic,reviewOf(finding)):automatic;
}
function resultMatchesFilter(finding) {
  return state.filter === 'ALL' || state.filter === 'MY_ACTIONS' && businessPresentation(finding, true).userActionRequired && !reviewOf(finding) || businessPresentation(finding, true).state === state.filter || effectiveStatus(finding) === state.filter;
}
function resolutionJobOf(finding) {
  const job=finding.resolutionJob || finding.assessment?.resolutionJob;
  return job && typeof job==='object' && typeof job.id==='string' && job.id ? job : null;
}
function findingProofScope(finding) {
  const unknown={state:'UNSPECIFIED',label:''};if(finding.evaluation.status!=='PASS')return unknown;
  const a=assessmentOf(finding)||{},derived=a.derivedConclusion||{},semantic=a.coverage?.semanticProof||{};
  if(derived.status==='PASS'&&derived.proofScope==='OBSERVED_OCCURRENCES'&&derived.comparisonCoverageComplete===true&&Array.isArray(derived.unresolvedOccurrenceIds)&&!derived.unresolvedOccurrenceIds.length&&derived.originalOccurrenceIds?.length)return {state:'OBSERVED_OCCURRENCES',label:'已识别记录一致'};
  if(finding.evaluation.evidences?.length&&semantic.validated===true&&semantic.scope==='WRITTEN_RESPONSE'&&semantic.evidenceIds?.length)return {state:'WRITTEN_RESPONSE',label:'书面要求已满足'};
  const conditions=(atomicCheckOf(finding)?.conditions||[]).filter(c=>c.conditionRole==='REQUIREMENT'&&c.status!=='NOT_APPLICABLE');
  if(!conditions.length||conditions.some(c=>c.status!=='PASS'||c.evaluated!==true||!c.proof||!Object.keys(c.proof).length||!Array.isArray(c.evidences)||!c.evidences.length||(c.gaps||[]).length))return unknown;
  const atomic=atomicCheckOf(finding);
  if(atomic.complete===true&&!(atomic.gaps||[]).length&&conditions.every(c=>c.predicate==='PERSON_COUNT'&&c.proof.proofScope==='ROLE_CONFIGURATION_ONLY'))return {state:'ROLE_CONFIGURATION_ONLY',label:'岗位配置已确认'};
  if(conditions.every(c=>['MATERIAL','DECLARATION','SECTION','VISUAL'].includes(c.predicate)))return {state:'WRITTEN_RESPONSE',label:'书面要求已满足'};
  if(conditions.every(c=>['NUMBER','CERTIFICATE','VALIDITY','DATE_RANGE','MONTHS','PERSON_ROLE','RELATION','FIELD','SAME_FIELD','CONTRACT'].includes(c.predicate)))return {state:'BUSINESS_CONDITIONS',label:'业务条件已验证'};
  return unknown;
}
function findingDimensions(finding) {
  const projection = finalResultProjectionOf(finding);
  if (projection) {
    const execution = projection.execution && typeof projection.execution === 'object' ? projection.execution : {};
    const evidence = projection.evidence && typeof projection.evidence === 'object' ? projection.evidence : {};
    const conclusion = projection.conclusion && typeof projection.conclusion === 'object' ? projection.conclusion : {};
    const executionState = String(execution.state || 'FINISHED').toUpperCase();
    const evidenceState = String(evidence.state || 'NOT_LOCATED').toUpperCase();
    const conclusionState = String(conclusion.state || 'UNDETERMINED').toUpperCase();
    const executionLabels = { QUEUED: '等待执行', RUNNING: '正在执行', RETRY_WAIT: '等待重试', FINISHED: '本轮处理结束', BLOCKED: '处理受阻', FAILED: '本轮处理失败', PAUSED: '已暂停', CANCELLED: '已取消' };
    const evidenceLabels = { SUFFICIENT: '证据充分', PARTIAL: '证据部分覆盖', CONFLICTING: '证据存在冲突', NOT_LOCATED: '尚未定位证据', SCOPE_UNRESOLVED: '证据范围待明确', NOT_APPLICABLE: '不适用' };
    const proofScope = String(conclusion.proofScope || 'UNSPECIFIED');
    return {
      execution: { state: executionState, label: executionLabels[executionState] || executionState, active: ['QUEUED','RUNNING','RETRY_WAIT'].includes(executionState), jobId: execution.jobId || '', reason: execution.reason || '', nextAction: execution.nextAction || '' },
      evidence: { state: evidenceState, label: evidenceLabels[evidenceState] || evidenceState, refs: Array.isArray(evidence.refs) ? evidence.refs : [] },
      conclusion: { state: conclusionState, label: conclusionLabels[conclusionState] || conclusionState, determined: ['SATISFIED','NOT_SATISFIED','NOT_APPLICABLE'].includes(conclusionState) },
      proofScope: { state: proofScope, label: proofScope === 'UNSPECIFIED' ? '' : proofScope },
    };
  }
  const a=assessmentOf(finding)||{},saved=a.businessPresentation,job=resolutionJobOf(finding),status=finding.evaluation.status;
  const executionStates={QUEUED:'补查已排队',RUNNING:'自动补查中',RETRY_WAIT:'等待重试',BLOCKED_NO_PROGRESS:'补查已停止：无新进展',WAITING_USER:'等待必要信息',WAITING_EXTERNAL:'等待外部条件',FAILED:'本轮补查失败',FINISHED:'本轮已结束'};
  const execution=job && Object.hasOwn(executionStates,job.status)?job.status:'FINISHED';
  const c=a.coverage?.completeness||{},unknown=c.inventoryResolved===false||c.coreRequirementDeferred===true||(c.itemResults||[]).some(item=>item.status==='REQUIREMENT_UNRESOLVED');
  const references=(finding.evaluation.evidences||[]).length>0,body=(c.itemResults||[]).some(item=>item.status==='VERIFIED');
  const evidence=status==='NOT_APPLICABLE'?'NOT_APPLICABLE':unknown?'SCOPE_UNRESOLVED':['PASS','FAIL'].includes(status)&&references&&a.ocrReview?.required!==true?'SUFFICIENT':references||body?'PARTIAL':'NOT_RETRIEVED';
  const conclusion=({PASS:'SATISFIED',FAIL:'NOT_SATISFIED',WARNING:'SUSPECTED_RISK',NOT_APPLICABLE:'NOT_APPLICABLE'})[status]||'UNDETERMINED';
  const computed={execution:{state:execution,label:executionStates[execution],active:['QUEUED','RUNNING','RETRY_WAIT'].includes(execution),jobId:job?.id||'',reason:job?.stopReason||job?.reason||'',nextAction:job?.nextAction||''},evidence:{state:evidence,label:({NOT_APPLICABLE:'不适用',SCOPE_UNRESOLVED:'要求范围尚未明确',SUFFICIENT:'判断依据已记录',PARTIAL:body?'部分义务已有证明':'证据尚未核全',NOT_RETRIEVED:'尚未取得充分依据'})[evidence]},conclusion:{state:conclusion,label:({SATISFIED:'满足要求',NOT_SATISFIED:'不满足要求',SUSPECTED_RISK:'疑似风险，待确认',NOT_APPLICABLE:'不适用',UNDETERMINED:'尚不能判断'})[conclusion],determined:['SATISFIED','NOT_SATISFIED','NOT_APPLICABLE'].includes(conclusion)}};
  computed.proofScope=findingProofScope(finding);if(computed.proofScope.label)computed.conclusion.label=computed.proofScope.label;
  // Execution is always read from the durable current job, never from a cached display label.
  if(['business-dimensions-1','business-dimensions-2','business-dimensions-3'].includes(saved?.projectionVersion)&&saved.finalStatus===status&&saved.dimensions?.evidence&&saved.dimensions?.conclusion){const currentScope=computed.proofScope.state!=='UNSPECIFIED';return {...computed,evidence:saved.dimensions.evidence,conclusion:currentScope?computed.conclusion:saved.dimensions.conclusion,proofScope:currentScope?computed.proofScope:saved.dimensions.proofScope||computed.proofScope};}
  return computed;
}
function groupUnresolvedCount(item) {
  return item.members.filter(f=>!findingDimensions(f).conclusion.determined).length;
}
function groupPendingMemberCount(item) {
  return item.members.filter(f=>findingDimensions(f).conclusion.state==='UNDETERMINED').length;
}
function renderResolutionOverview(groups,results) {
  const run=state.run.run,summary=state.run.resolutionSummary||{},dimensions=results.map(findingDimensions),unresolved=dimensions.filter(d=>!d.conclusion.determined).length;
  const activeJobs=results.filter(f=>findingDimensions(f).execution.active).length,finished=terminal.has(run.status),failedRun=failed.has(run.status);
  const determined=dimensions.filter(d=>d.conclusion.determined&&d.conclusion.state!=='NOT_APPLICABLE').length,applicable=dimensions.filter(d=>d.conclusion.state!=='NOT_APPLICABLE').length;
  const sufficient=dimensions.filter(d=>d.evidence.state==='SUFFICIENT').length,unresolvedGroups=groups.filter(g=>groupUnresolvedCount(g)>0).length;
  const title=activeJobs?`自动补查正在进行 · 当前范围 ${activeJobs} 条原项` : !finished ? '本轮检查正在执行' : failedRun ? '本轮执行中断' : '本轮执行结束';
  const recoveryLabel=summary.canRecover===true?'继续自动补查缺口':summary.blockReason||'当前没有可启动的补查任务';
  return `<section class="resolution-overview" aria-label="执行与业务结论"><div class="resolution-heading"><div><strong>${e(title)}</strong><p>${!results.length?'当前范围没有返回原项，不表示该类已通过。':unresolved?`当前范围 ${unresolvedGroups} 个事项、${unresolved} 条原项尚未形成确定结论。`:'当前已返回的原项均有结论；请查看对应依据。'}</p></div>${summary.version?`<button class="button button-primary button-small" data-action="recover-run" title="${e(recoveryLabel)}" ${summary.canRecover!==true||state.busy.has('run')||isRunning()?'disabled':''}>${icon('refresh',true)}继续自动补查缺口</button>`:''}</div><div class="resolution-counts">${Number.isInteger(summary.unresolvedOriginalCount)?`<span>全报告未完成核验 <b>${summary.unresolvedOriginalCount}</b> 条原项</span>`:''}<span>自动形成确定结论 <b>${determined} / ${applicable}</b> 条原项</span><span>判断依据已记录 <b>${sufficient} / ${applicable}</b></span>${results.length-applicable?`<span>不适用 ${results.length-applicable} 条</span>`:''}</div><details><summary>统计范围与补查说明</summary><p>以当前分类与搜索结果中的原项计数，分母仅排除已确认不适用项；疑似风险不计为确定结论。上述数量不代表准确率，证据是否足以证明完整要求仍需按各项依据判断。</p><p>${summary.canRecover===true?'补查范围为整份报告的可恢复缺口，与当前筛选无关；生成关联的新批次，历史报告及人工记录保留。':e(summary.blockReason||'当前报告没有已启动的补查任务。系统负责处理未完成事项；只有明确需要的信息才列入用户待办。')}</p>${run.parentRunId?'<p>这是补查批次；可从历史报告列表查看原批次。</p>':''}</details>${renderInheritedReviews()}</section>`;
}
function renderInheritedReviews(finding=null) {
  const all=state.run?.inheritedReviews||state.run?.run?.priorReviewRecords||[],parentId=finding?.assessment?.inheritedFrom?.findingId;
  const rows=finding?all.filter(r=>parentId&&r.resultId===parentId):all;
  if(!rows.length)return '';
  return `<details class="inherited-reviews"><summary>前版复核记录 ${rows.length} 条 · 未自动套用</summary><p>以下为原批次人工记录，仅供对照；新证据可能改变适用性，当前批次结论及待办单独保留。</p>${rows.map(r=>`<div><strong>${e(r.reviewer||'未署名')}</strong> · ${e(statuses[r.status]||r.status)} · ${e(date(r.createdAt))}<p>${e(r.note||'未填写说明')}</p><small>来源批次 ${e(r.runId||state.run.run.parentRunId||'未记录')}</small></div>`).join('')}</details>`;
}
function renderFindingDimensions(finding) {
  const d=findingDimensions(finding),job=resolutionJobOf(finding);
  return `<div class="finding-dimensions"><span>执行 <b>${e(d.execution.label)}</b></span><span>证据 <b>${e(d.evidence.label)}</b></span><span>自动结论 <b>${e(d.conclusion.label)}</b></span></div>${job?`<div class="resolution-job"><strong>${e(d.execution.label)}</strong>${d.execution.nextAction?`<p>下一步：${e(d.execution.nextAction)}</p>`:''}${d.execution.reason?`<p>${e(d.execution.reason)}</p>`:''}<details><summary>补查记录</summary><p>已尝试 ${Number(job.attempts)||0} 次 · 新增证据 ${Number(job.newEvidenceCount)||0} 条${job.updatedAt?` · ${e(date(job.updatedAt))}`:''}</p></details></div>`:''}`;
}
function renderFieldCoverage(finding) {
  const rows=atomicCheckOf(finding)?.fieldCoverage;if(!Array.isArray(rows)||!rows.length)return '';
  const labels={UNREAD:'尚未读取',READ_NOT_FOUND:'已读未见',EXTRACTED:'已提取',FAILED:'处理失败'},counts={};for(const row of rows)if(Object.hasOwn(labels,row.status))counts[row.status]=(counts[row.status]||0)+1;
  if(!Object.keys(counts).length)return '';
  return `<details class="field-observation-details"><summary>字段读取记录 · ${e(Object.entries(counts).map(([key,n])=>`${labels[key]} ${n}`).join(' · '))}</summary><p class="preview-caption">记录只覆盖本项已选原文。已读未见不表示文件缺少材料；已提取字段仍须按本项要求判断。</p><ul>${rows.filter(row=>Object.hasOwn(labels,row.status)).map(row=>{const target=row.target||{},name=[target.subject,fields[target.field]||({amount:'金额',role:'岗位',certificate:'证书',license:'资格证照',employer:'任职单位'})[target.field]||(/[^\x00-\x7F]/.test(target.field||'')?target.field:'本项字段')].filter(Boolean).join(' · ')||'本项字段';return `<li><strong>${e(name)}</strong><span>${e(labels[row.status])}${row.status==='EXTRACTED'&&row.sourceScopeComplete!==true?' · 本字段仍有未完成范围':''}</span></li>`;}).join('')}</ul></details>`;
}
function businessGroups(results) {
  const index = new Map(results.map(f => [f.id, f])), seen = new Set(), groups = [];
  for (const item of state.run?.businessGroups || []) {
    const members = [...new Set(Array.isArray(item.resultIds) ? item.resultIds : [])].filter(id => index.has(id) && !seen.has(id)).map(id => index.get(id));
    if (!members.length) continue;
    members.forEach(f => seen.add(f.id)); groups.push({ ...item, members });
  }
  for (const f of results) if (!seen.has(f.id)) groups.push({id:f.id,title:f.checkpoint.title,members:[f],sourceCount:f.checkpoint.source?1:0});
  return groups;
}
function businessGroupState(item) {
  const priority = ['RISK','SYSTEM_INCOMPLETE','EVIDENCE_PENDING','USER_ACTION','UNDETERMINED','SATISFIED','NOT_APPLICABLE'];
  const states = new Set(item.members.map(f=>businessPresentation(f,true).state));
  if (states.size===2 && states.has('SATISFIED') && states.has('NOT_APPLICABLE')) return 'SYSTEM_INCOMPLETE';
  return priority.find(value => states.has(value)) || 'EVIDENCE_PENDING';
}
function businessGroupDifference(item) {
  return new Set(item.members.map(f=>businessPresentation(f,true).state)).size > 1;
}
function businessGroupPrimaryMember(item) {
  if(!item)return null;
  const scoped=item.members.filter(findingMatchesResultScope), candidates=scoped.length?scoped:item.members;
  if(state.filter==='MY_ACTIONS')return candidates.find(f=>businessPresentation(f,true).userActionRequired&&!reviewOf(f))||candidates[0];
  return candidates.find(f=>businessPresentation(f,true).state===businessGroupState({...item,members:candidates}))||candidates[0];
}
function businessMemberSource(item,finding) {
  return (item.occurrences || []).find(row=>row.resultId===finding.id)?.source || finding.checkpoint.source;
}
function renderBusinessMembers(item, selected) {
  if (!item || item.members.length < 2) return '';
  const difference=businessGroupDifference(item);
  return `${groupUnresolvedCount(item)>0?`<p class="group-unresolved">本事项 ${groupUnresolvedCount(item)} 条原项尚未形成确定结论；风险和已通过记录分别保留。</p>`:''}${difference?'<div class="business-group-difference">各原项结论尚未统一，不能用其中一项的通过代替整个事项。</div>':''}<details class="business-members" ${disclosureAttributes(`business-members-${item.id}`,difference)}><summary>同一事项的 ${item.members.length} 条原项 · ${Number(item.sourceCount)||0} 处招标来源</summary><p>${e(item.explanation || '已合并同义要求的展示，原项结论与人工复核分别保留。')}</p><div class="business-member-list">${item.members.map((f,index) => {const source=businessMemberSource(item,f),review=reviewOf(f),display=businessPresentation(f,true);return `<button class="business-member-select ${f.id===selected.id?'selected':''}" data-action="select-finding" data-id="${e(f.id)}" aria-pressed="${f.id===selected.id}"><span><strong>记录 ${index+1} · ${e(f.checkpoint.title)}</strong><small>${source?`${e(source.documentName||'招标文件')} · ${e(locationLabel(source))}${source.anchor?` · ${e(source.anchor)}`:''}`:'独立招标出处未记录'}</small></span><span class="pill ${e(display.state)}">${e(display.label)}</span>${review?`<small>人工：${e(statuses[review.status]||review.status)} · 原自动结论：${e(statuses[f.evaluation.status]||f.evaluation.status)}</small>`:''}</button>`;}).join('')}</div><p class="preview-caption">点击原项查看各自证据；保存复核只作用于所选原项。</p></details>`;
}
function renderResultScopeTags(finding) {
  const purpose=findingPurpose(finding),mode=findingVerificationMode(finding);
  return `${purpose?`<span class="result-scope-tag purpose-${e(purpose)}">${e(purposeLabels[purpose])}</span>`:''}${mode?`<span class="result-scope-tag mode-${e(mode)}">${e(verificationModeLabels[mode])}</span>`:''}`;
}
function templateSubcheckState(value) {
  const state=String(value?.conclusion?.state||value?.state||value?.status||'UNDETERMINED').toUpperCase();
  return {state,label:conclusionLabels[state]||({PASS:'满足',FAIL:'不满足',WARNING:'存在风险'})[state]||'待核验',pill:templateBusinessState(state)};
}
function renderTemplateSubchecks(finding) {
  const projection=templateResultOf(finding),rows=Array.isArray(projection?.subchecks)?projection.subchecks.filter(row=>row&&typeof row==='object'):[];
  if(!rows.length)return '';
  return `<section class="template-subchecks" aria-label="关键子项"><div class="template-subchecks-heading"><h3>关键子项</h3><span>${rows.length} 项</span></div><div class="template-subcheck-list">${rows.map((row,index)=>{const result=templateSubcheckState(row),title=row.title||row.name||row.label||`子项 ${index+1}`,summary=row.conclusion?.summary||row.summary||row.reason||'';return `<div class="template-subcheck-row"><div><strong>${e(title)}</strong>${summary?`<p>${e(summary)}</p>`:''}</div><span class="pill ${e(result.pill)}">${e(result.label)}</span></div>`;}).join('')}</div></section>`;
}
function renderTemplateDiagnostics(finding) {
  const projection=templateResultOf(finding);if(!projection)return '';
  const conclusion=projection.conclusion||{},missing=Array.isArray(projection.missingInputs)?projection.missingInputs.filter(Boolean):[],dependencies=Array.isArray(projection.dependencies)?projection.dependencies.filter(Boolean):[];
  const values=[conclusion.reasonCode?`<span>原因编码 <b>${e(conclusion.reasonCode)}</b></span>`:'',projection.evaluatorVersion?`<span>判定器 <b>${e(projection.evaluatorVersion)}</b></span>`:'',Number.isInteger(projection.resultRevision)?`<span>结果版本 <b>R${projection.resultRevision}</b></span>`:''].filter(Boolean).join('');
  return `${values?`<div class="template-diagnostic-meta">${values}</div>`:''}${missing.length?`<div class="template-diagnostic-list"><strong>未满足输入</strong><ul>${missing.map(value=>`<li>${e(typeof value==='string'?value:value.label||value.name||value.code||'未命名输入')}</li>`).join('')}</ul></div>`:''}${dependencies.length?`<div class="template-diagnostic-list"><strong>依赖项</strong><ul>${dependencies.map(value=>`<li>${e(typeof value==='string'?value:value.label||value.name||value.id||'未命名依赖')}</li>`).join('')}</ul></div>`:''}`;
}
function uniqueUserActions(results) {
  const actions=new Map();
  for(const finding of results){const display=businessPresentation(finding,true),templateAction=templateResultOf(finding)?.action||{};if(!display.userActionRequired||reviewOf(finding))continue;const key=display.actionId||templateAction.actionId||`legacy:${finding.id}`;if(!actions.has(key))actions.set(key,{id:key,findings:[]});actions.get(key).findings.push(finding);}
  return [...actions.values()];
}
function renderBusinessListItem(item, selected) {
  const selectedMember = item.members.find(f => f.id===selected?.id), finding = selectedMember || businessGroupPrimaryMember(item), display = businessPresentation(finding,true), groupState = businessGroupState(item);
  return `<button class="finding-item ${selectedMember?'selected':''}" data-action="select-finding" data-id="${e(finding.id)}" aria-pressed="${!!selectedMember}"><div class="finding-title-row"><span class="finding-indicator ${e(groupState)}"></span><span class="finding-title">${e(item.title || finding.checkpoint.title)}</span></div><div class="finding-meta"><span class="pill ${e(groupState)}">${e(display.label||businessStates[groupState])}</span>${renderResultScopeTags(finding)}${item.members.length>1?`<span>${item.members.length} 条原项 · ${Number(item.sourceCount)||0} 处出处</span>`:''}${groupPendingMemberCount(item)>0&&groupState==='RISK'?`<span class="group-unresolved">另有 ${groupPendingMemberCount(item)} 条原项尚无确定结论</span>`:''}${item.members.every(f=>reviewOf(f))?'<span>已复核</span>':''}</div><p class="finding-subtext">${e(businessGroupDifference(item)?'各原项结论尚未统一，请查看分项记录。':display.reason || findingPresentation(finding).reason)}</p></button>`;
}
function completenessOf(finding) {
  const value=assessmentOf(finding)?.coverage?.completeness;
  return value && typeof value==='object' && value.required!==false ? value : null;
}
function completenessItems(value) {
  if(Array.isArray(value?.itemResults)) return value.itemResults.filter(row=>row && typeof row==='object').map(row=>({...row,status:['VERIFIED','EVIDENCE_PENDING','REQUIREMENT_UNRESOLVED'].includes(row.status)?row.status:'REQUIREMENT_UNRESOLVED'}));
  const verified=new Set((value?.verified||[]).map(row=>row.item));
  return (Array.isArray(value?.items)?value.items:[]).filter(item=>typeof item==='string').map((item,index)=>({id:`legacy-${index}`,item,status:value.inventoryResolved!==true?'REQUIREMENT_UNRESOLVED':verified.has(item)?'RECORDED':'EVIDENCE_PENDING',requirementSources:[],evidences:[],reason:verified.has(item)?'历史记录已有依据编号，未提供逐项定位，不能在此补造来源。':'尚未取得逐项正文依据；这不表示原文件缺少材料。'}));
}
function renderCompletenessSources(sources,title) {
  const list=Array.isArray(sources)?sources.filter(value=>value&&typeof value==='object'):[];
  return list.length?`<div class="completeness-sources"><h4>${e(title)}</h4>${list.map(renderAtomicEvidence).join('')}</div>`:'';
}
function renderCompleteness(finding) {
  const value=completenessOf(finding);if(!value)return '';
  const items=completenessItems(value),references=Array.isArray(value.unresolvedReferences)?value.unresolvedReferences:[],excluded=Array.isArray(value.excludedItems)?value.excludedItems:[];
  const bodyOnly=value.proofScope==='BODY_PRESENCE_ONLY';
  const names={VERIFIED:bodyOnly?'已定位正文':'已证实',EVIDENCE_PENDING:'待补证据',REQUIREMENT_UNRESOLVED:'要求未展开',RECORDED:'已记录依据'};
  const count=status=>items.filter(row=>row.status===status).length;
  const resolved=value.inventoryResolved===true && references.length===0;
  const summary=resolved?`已展开 ${items.length} 项要求`:`清单尚未完整展开${items.length?` · 已识别 ${items.length} 项要求`:''}`;
  return `<section class="completeness-check" aria-label="逐项清单核对"><div class="completeness-heading"><h3>${bodyOnly?'清单正文核对':'清单核对'}</h3><span>${e(summary)}</span></div><p class="completeness-counts">${Object.keys(names).filter(status=>count(status)>0).map(status=>`${names[status]} ${count(status)} 项`).map(e).join(' · ')||'还不能确认完整清单'}</p>${!resolved?'<p class="completeness-notice">需先完成招标清单及引用章节的展开，当前不能认定投标材料缺失。</p>':''}${value.reason?`<p class="preview-caption">${e(value.reason)}</p>`:''}<details class="completeness-details" ${disclosureAttributes(`completeness-${finding.id}`)}><summary>查看分项与章节来源</summary>${references.length?`<div class="completeness-unresolved"><h4>尚未展开的引用</h4>${references.map(reference=>`<p><strong>${e(reference.text||'未定位引用')}</strong> · ${e(reference.reason||'所指范围尚未明确')}</p>${renderCompletenessSources(reference.sources,'引用原文')}`).join('')}</div>`:''}${items.map((item,index)=>`<details class="completeness-item"><summary><span>${index+1}. ${e(item.item||'未命名要求')}</span><span class="pill ${item.status==='VERIFIED'?'SATISFIED':item.status==='REQUIREMENT_UNRESOLVED'?'SYSTEM_INCOMPLETE':'EVIDENCE_PENDING'}">${e(names[item.status])}</span></summary><div class="completeness-item-body"><p>${e(item.reason||'请结合下列逐项依据判断。')}</p>${renderCompletenessSources(item.requirementSources,'招标要求所在章节')}${renderCompletenessSources(item.evidences,'对应投标正文')}${!(item.evidences||[]).length?'<p class="preview-caption">尚未记录可定位的投标正文依据，不据此认定缺少材料。</p>':''}</div></details>`).join('')}${excluded.length?`<div class="completeness-excluded"><h4>不纳入必检清单 · ${excluded.length} 项</h4>${excluded.map(item=>`<p><strong>${e(item.item||'条件要求')}</strong> · ${e(item.reason||item.condition||'依据适用条件排除')}</p>${renderCompletenessSources(item.requirementSources,'适用依据')}`).join('')}</div>`:''}${renderCompletenessSources(value.inventorySources,'清单展开所用招标章节')}<p class="preview-caption">${bodyOnly?'盖章按对应项展示。':'分项证明仅覆盖本清单要求；整个事项仍以最终结论为准。'}</p></details></section>`;
}
function originalReview(finding, presentation = findingPresentation(finding)) {
  const assessment = assessmentOf(finding), review = assessment?.ocrReview;
  if (!review || typeof review !== 'object') return { required: !!presentation.note, incomplete: false, reason: presentation.note, locations: [], legacy: true };
  const run = state.run?.run || {}, metadata = state.run?.documents || [];
  const locations = Array.isArray(review.locations) ? review.locations.filter(item => item && Number.isInteger(item.page) && item.page > 0 &&
    [run.documentId, run.tenderDocumentId].includes(item.documentId) && metadata.some(document => document.id === item.documentId && document.projectId === run.projectId)) : [];
  return { required: review.required === true || review.incomplete === true, incomplete: review.incomplete === true,
    deferred: review.deferred === true, failed: review.failed === true,
    reason: typeof review.reason === 'string' ? review.reason : '', locations, legacy: false };
}
function retrievalLabel(mode) {
  return ({ HYBRID_RERANKED: '混合检索与证据重排', HYBRID_RRF_DEGRADED: '融合检索（排序降级）', BM25_DEGRADED: '关键词检索（服务降级）', BM25_DISABLED: '关键词检索（未启用增强检索）', OFFLINE_LEXICAL: '离线关键词检索', LEXICAL: '关键词检索', KEYWORD: '关键词检索', HYBRID: '混合证据检索', VECTOR: '语义检索', PERSONNEL_LINKED: '岗位与人员证据关联', DETERMINISTIC: '规则直接核对' })[mode] || '未记录';
}
function assessmentState(finding) {
  const assessment = assessmentOf(finding); if (!assessment) return '';
  if (atomicCheckOf(finding)) return businessPresentation(finding).label;
  const codes = new Set((Array.isArray(assessment.adjustments) ? assessment.adjustments : []).map(item => item?.code));
  if (codes.has('MODEL_NOT_CONFIGURED')) return '语义服务未配置';
  if (['MODEL_HTTP_ERROR', 'MODEL_TIMEOUT', 'MODEL_COMMUNICATION_ERROR', 'MODEL_INTERRUPTED'].some(code => codes.has(code))) return '模型核验未完成';
  if (['MODEL_COMPLETION_TRUNCATED', 'MODEL_JSON_INVALID', 'MODEL_SCHEMA_INVALID', 'MODEL_CITATION_INVALID'].some(code => codes.has(code))) return '模型输出未通过校验';
  if (codes.has('CONTEXT_BUDGET_EXCEEDED')) return '证据上下文超限';
  if (codes.has('INPUT_INVALID')) return '检查输入不完整';
  if (codes.has('OCR_COVERAGE_DEFERRED') || codes.has('OCR_SOURCE_DEFERRED')) return '待按计划补识别';
  if (codes.has('EVIDENCE_SCOPE_INCOMPLETE')) return '证据未覆盖检查范围';
  if (['EVIDENCE_MISSING', 'PERSONNEL_COVERAGE_INCOMPLETE'].some(code => codes.has(code))) return '证据覆盖不足';
  if (['DATE_ASSERTION_UNGROUNDED', 'VISUAL_ASSERTION_UNGROUNDED', 'CLAIM_GROUNDING_INVALID'].some(code => codes.has(code))) return '推断缺少可靠依据';
  return assessment.model?.state === 'UNAVAILABLE' ? '模型核验未完成' : assessment.model?.state === 'GUARDED' ? '模型结论已调整' : '';
}
function originalReviewIsSourceRecord(finding) {
  const business=businessPresentation(finding,true);
  return ['SYSTEM_INCOMPLETE','EVIDENCE_PENDING'].includes(business.state)&&!business.userActionRequired;
}
function renderOriginalReview(finding, presentation) {
  const review = originalReview(finding, presentation);
  if (!review.required) return '';
  if(originalReviewIsSourceRecord(finding))return `<details class="recognition-source-record"><summary>识别来源记录${review.locations.length?` · ${review.locations.length} 处位置`:''}</summary><p>保留本项使用的识别来源与处理说明，不计入人工核对待办。</p>${review.reason?`<details><summary>原始识别说明</summary><p>${e(review.reason)}</p></details>`:''}${review.locations.length?`<ul class="document-warning-list" tabindex="0" aria-label="识别来源位置">${review.locations.map(item=>`<li><button class="text-action" data-action="page-preview" data-id="${e(item.documentId)}" data-page="${item.page}">${e(item.label||locationLabel(item))}${icon('external',true)}</button></li>`).join('')}</ul>`:''}</details>`;
  return `<div class="ocr-review-note original-review-note"><strong>原图复核提示${review.deferred ? ' · 相关位置待按需补识别' : review.incomplete ? ' · 相关位置识别未完成' : ''}${review.deferred && review.failed ? ' · 另有识别未完成位置' : ''}</strong><p>${e(review.reason || '请对照相关原图核对文字准确性；文字识别不等于签章验证。')}</p>${review.locations.length ? `<details class="document-warning-details"><summary>查看 ${review.locations.length} 处原图复核位置</summary><ul class="document-warning-list" tabindex="0" aria-label="原图复核位置">${review.locations.map(item => `<li><button class="text-action" data-action="page-preview" data-id="${e(item.documentId)}" data-page="${item.page}">${e(item.label || locationLabel(item))}${icon('external', true)}</button></li>`).join('')}</ul></details>` : ''}</div>`;
}
function renderAssessmentDetails(finding) {
  const assessment = assessmentOf(finding); if (!assessment) return '';
  const evaluationText = evaluation => evaluation && typeof evaluation === 'object' ? `${statuses[evaluation.status] || evaluation.status || '未记录'}${evaluation.reason ? ` · ${evaluation.reason}` : ''}` : '未记录';
  const adjustments = Array.isArray(assessment.adjustments) ? assessment.adjustments.filter(item => item && typeof item.reason === 'string') : [];
  const decisionLabel = ({ RULE_DECIDED: '规则确定为待核', EVIDENCE_INSUFFICIENT: '证据不足，保留待核实', MODEL_NOT_NEEDED: '未调用模型、仍待核', MODEL_ESCALATED: '疑点已请求模型核验', BUDGET_DEFERRED: '本次记录的旧调用次数上限导致暂缓，保留待核实' })[assessment.model?.decision];
  const modelState = ({ COMPLETED: '已完成', REPAIRED: '输出纠正后完成', UNAVAILABLE: '未能完成', GUARDED: '输出经证据校验调整' })[assessment.model?.state];
  const retrieval = assessment.retrieval || {};
  return `<details class="document-warning-details assessment-details"><summary>查看原始判断与调整说明</summary><ul class="document-warning-list" tabindex="0" aria-label="判断过程"><li><strong>原始判断</strong><p>${e(evaluationText(assessment.rawEvaluation))}</p></li><li><strong>原图策略前判断</strong><p>${e(evaluationText(assessment.preOcrEvaluation))}</p></li><li><strong>结论调整</strong>${adjustments.length ? adjustments.map(item => `<p>${e(item.reason)}</p>`).join('') : '<p>未记录额外调整。</p>'}</li><li><strong>初次检索</strong><p>${e(retrievalLabel(retrieval.initial?.mode || retrieval.mode))}</p></li>${retrieval.followUp?.attempted === true ? `<li><strong>缺口补充检索</strong><p>${e(retrievalLabel(retrieval.followUp.mode))}</p></li>` : ''}${decisionLabel ? `<li><strong>核验方式</strong><p>${e(decisionLabel)}${assessment.model.requested === false ? '；本项未调用模型。' : ''}</p></li>` : ''}${modelState && assessment.model.requested !== false ? `<li><strong>模型核验</strong><p>${e(modelState)}${Array.isArray(assessment.model.attempts) ? ` · ${assessment.model.attempts.length} 次尝试记录` : ''}</p></li>` : ''}</ul></details>`;
}
function renderReportOcrContext() {
  const context = state.run?.ocrContext, run = state.run?.run || {}, metadata = state.run?.documents || [];
  const tenderIds = new Set(run.tenderDocumentId ? [run.tenderDocumentId] : (state.run?.results || []).map(finding => finding.checkpoint?.source?.documentId).filter(Boolean));
  const list = Array.isArray(context?.documents) ? context.documents.filter(document => document &&
    ((document.documentId === run.documentId && document.role === 'BID') || (tenderIds.has(document.documentId) && document.role === 'TENDER')) &&
    metadata.some(item => item.id === document.documentId && item.projectId === run.projectId && item.role === document.role)) : [];
  const coverageCounts = list.map(document => {
    const length = key => Array.isArray(document[key]) ? document[key].length : 0;
    const failed = countValue(document, 'failedCount') ?? length('failedLocations');
    const deferred = countValue(document, 'deferredCount') ?? length('deferredLocations');
    const unlocated = countValue(document, 'unlocatedIncompleteCount') ?? 0;
    const incomplete = countValue(document, 'incompleteCount') ?? (length('incompleteLocations') + unlocated);
    const structure = countValue(document, 'structureIncompleteCount') ?? (length('structureIncompleteLocations') + length('unlocatedStructureWarnings'));
    return { failed, deferred, unlocated, structure, unresolved: document.ocrCoverageComplete === false || incomplete > failed + deferred || unlocated > 0 };
  });
  const incomplete = coverageCounts.reduce((sum, item) => sum + item.failed, 0);
  const deferred = coverageCounts.reduce((sum, item) => sum + item.deferred, 0);
  const unlocated = coverageCounts.reduce((sum, item) => sum + item.unlocated, 0);
  const incompleteCoverage = coverageCounts.some(item => item.unresolved);
  const structure = coverageCounts.reduce((sum, item) => sum + item.structure, 0);
  const originalPending = (state.run?.results || []).filter(finding => originalReview(finding).required && businessPresentation(finding,true).userActionRequired && !reviewOf(finding)).length;
  const contextPending = list.filter(document => !textOnlyProcessing(document.processing)).reduce((sum, document) => sum + (countValue(document.processing, 'contextPending') ?? 0), 0);
  const contextLimitedPages = list.filter(document => !textOnlyProcessing(document.processing)).reduce((sum, document) => sum + structuredCoverageLimitations(document.processing).count, 0);
  const visualReview = list.some(document => !textOnlyProcessing(document.processing) && ((countValue(document.processing, 'contextReviewRequired') ?? 0) > 0 || (countValue(document.processing, 'contextNoFacts') ?? 0) > 0));
  const retrievalDegraded = context?.retrieval?.denseCoverageComplete === false;
  const scan = state.run?.consistency?.coverage?.model;
  const scanIncomplete = !fullModelNotRequested() && scan?.enabled === true && scan.complete !== true;
  const alerts = [incomplete ? `${incomplete} 处未完成识别` : '', deferred ? `${deferred} 处待按需补识别` : '', incompleteCoverage ? '文档识别覆盖不完整' : '', unlocated ? `其中 ${unlocated} 处缺口未定位` : '', structure ? `${structure} 处文字或表格布局处理提示` : '', contextPending ? `${contextPending} 个图像分析窗口未完成` : '', visualReview ? '图像分析仍有待复核结果' : '', contextLimitedPages ? `${contextLimitedPages} 页字段提取范围有限，需核对原文` : '', scanIncomplete ? '全文模型扫描或来源核验存在缺口' : '', ...onDemandModelAlerts(), retrievalDegraded ? '全文向量索引未完整建立，已降级检索' : '', originalPending ? `${originalPending} 项需原图复核` : ''].filter(Boolean);
  if (!list.length) return alerts.length ? `<div class="report-ocr-alert">${icon('alert', true)}<span>${e(alerts.join(' · '))}。未提供本次文件的解析范围，请核对报告记录。</span></div>` : '';
  return `<details class="panel report-ocr-context" id="report-ocr-context" aria-label="本次文档解析范围" ${disclosureAttributes('report-ocr-context')}><summary>文档识别与处理记录 <span>${list.length} 份文件</span></summary><div class="panel-body">${alerts.length ? `<p class="report-context-note">${e(alerts.join(' · '))}。这些记录不等于独立问题或人工待办，具体动作以各事项为准。</p>` : ''}<p class="report-context-note">范围对应本次报告的文件版本；文字识别不等于验证签字、印章或证照真伪。</p>${list.map(document => {
    const group = (items, label) => {
      const meta = metadata.find(item => item.id === document.documentId);
      const maximum = countValue(meta, 'pageCount') ?? (Array.isArray(meta?.pages) && meta.pages.length ? Math.max(...meta.pages.map(page => Number(page.number) || 0)) : countValue(document, 'totalLocations'));
      const seen = new Set();
      const locations = Array.isArray(items) ? items.filter(item => {
        if (!item || item.documentId !== document.documentId || !Number.isInteger(item.page) || item.page <= 0 || maximum != null && item.page > maximum) return false;
        const key = `${item.locationType || ''}:${item.page}`;
        if (seen.has(key)) return false;
        seen.add(key); return true;
      }) : [];
      return locations.length ? `<details class="document-warning-details" ${disclosureAttributes(`ocr-${document.documentId}-${label}`)}><summary>查看 ${locations.length} 处${label}</summary><ul class="document-warning-list" tabindex="0" aria-label="${label}">${locations.map(item => `<li><button class="text-action" data-action="page-preview" data-id="${e(document.documentId)}" data-page="${item.page}">${e(item.label || locationLabel(item))}${icon('external', true)}</button><p>${e(item.warning || '')}</p></li>`).join('')}</ul></details>` : '';
    };
    const textLocations = Array.isArray(document.deferredLocations) ? group(document.deferredLocations, '待按需补识别位置') + group(document.failedLocations, '未完成位置') : group(document.incompleteLocations, '未完成位置');
    const unlocatedStructure = Array.isArray(document.unlocatedStructureWarnings) ? document.unlocatedStructureWarnings : [];
    const locations = textLocations + group(document.structureIncompleteLocations, '文字或表格布局待复核位置') + (unlocatedStructure.length ? `<details class="document-warning-details"><summary>${unlocatedStructure.length} 处布局提示未定位</summary><ul class="document-warning-list">${unlocatedStructure.map(warning => `<li>${e(warning)}</li>`).join('')}</ul></details>` : '');
    const processing = document.processing;
    const processingNote = processing ? (textOnlyProcessing(processing) ? `<p class="text-processing-scope">本次处理范围：文字识别与表格、页码整理；未执行图像字段分析、签章核验或图片比对。此范围在检查开始时保存。</p>` : `<p>图像分析：${e(processing.contextWindows ?? 0)} 个窗口，${e(processing.contextPending ?? 0)} 个未完成，${e(processing.contextReviewRequired ?? 0)} 个需复核，${e(processing.contextNoFacts ?? 0)} 个窗口含未提取到可用事实的页面${countValue(processing, 'contextNoFactPageCount') != null ? `（${e(processing.contextNoFactPageCount)} 页）` : ''}。此范围在检查开始时保存。</p>`) : '';
    return `<div class="document-warning-summary" data-ocr-document="${e(document.documentId)}"><strong class="evidence-document-name">${document.role === 'TENDER' ? '招标文件' : '投标文件'} · ${e(document.name)} · V${e(document.version)}</strong><p class="ocr-document-notice">${e(document.notice || '未提供解析范围说明。')}</p>${processingNote}${renderStructuredCoverageLimitations(processing, document.documentId, countValue(metadata.find(item => item.id === document.documentId), 'pageCount') ?? countValue(document, 'totalLocations'))}${locations}</div>`;
  }).join('')}</div></details>`;
}
function renderOriginalExplanation(presentation) {
  return presentation.legacyClarified ? `<div class="document-warning-summary legacy-clarification"><p>解析提示已按范围澄清，历史结论未修改</p><details class="document-warning-details"><summary>查看历史原始说明</summary><ul class="document-warning-list" tabindex="0" aria-label="历史原始说明"><li>${e(presentation.originalReason)}</li>${presentation.originalSuggestion ? `<li>原始建议：${e(presentation.originalSuggestion)}</li>` : ''}</ul></details></div>` : '';
}

const consistencyGroupLabels = { CONFLICT: '冲突', SUSPECTED: '疑点', EVIDENCE_INSUFFICIENT: '需补核依据' };
const consistencyRelationLabels = { VALUE: '取值核对', ARITHMETIC: '计算关系', TEMPORAL: '时间关系', SEMANTIC: '承诺关系', EVIDENCE: '证据关联' };
const consistencyDimensionLabels = { object: '对象', subject: '主体', scope: '范围', lot: '标段', basis: '口径', stage: '阶段', trigger: '起算条件', condition: '适用条件', version: '版本', range: '覆盖范围', period: '期间', currency: '币种', taxBasis: '税价口径', unitBasis: '计量口径', dayType: '日期口径', time: '时点', fieldLabel: '核对事项', parameterName: '参数名称' };
function consistencyData() { const data = state.run?.consistency; return data && typeof data === 'object' && (data.version || data.coverage || Array.isArray(data.groups)) ? data : null; }
function consistencyGroups() { const data = consistencyData(); return Array.isArray(data?.groups) ? data.groups : []; }
function consistencyFinding(item) { return item?.findingId ? (state.run?.results || []).find(finding => finding.id === item.findingId) : null; }
function consistencyReview(item) { const finding = consistencyFinding(item); return finding ? reviewOf(finding) : null; }
function consistencyCategories(data = consistencyData()) { return Array.isArray(data?.coverage?.categories) ? data.coverage.categories : []; }
function consistencyCategoryLabel(id) { return consistencyCategories().find(item => item.id === id)?.title || (id ? '其他事项' : '未提供类别'); }
const consistencyFieldIndexes = new WeakMap();
function consistencyFieldLabel(field, item = {}) {
  const data = consistencyData();
  if (data && !consistencyFieldIndexes.has(data)) {
    const index = new Map();
    for (const record of Array.isArray(data.occurrences) ? data.occurrences : []) if (!index.has(record.field) && (record.fieldLabel || record.title)) index.set(record.field, record.fieldLabel || record.title);
    consistencyFieldIndexes.set(data, index);
  }
  return item.fieldLabel || fields[field] || (data && consistencyFieldIndexes.get(data).get(field)) || (field && !/^(dynamic\.|[A-Za-z0-9_]+$)/.test(field) ? field : '其他信息');
}
function consistencyGroupStatus(item) { return item.evaluationStatus || (item.status === 'EVIDENCE_INSUFFICIENT' ? 'UNDETERMINED' : 'WARNING'); }
function countValue(object, ...keys) {
  for (const key of keys) { const value = object?.[key]; if (typeof value === 'number' && Number.isFinite(value) && value >= 0) return value; }
  return null;
}
function displayValue(value) {
  if (value == null || value === '') return '';
  if (Array.isArray(value)) return value.map(displayValue).filter(Boolean).join('；');
  if (typeof value === 'object') return Object.entries(value).map(([key, item]) => `${fields[key] || key}：${displayValue(item)}`).join('；');
  return String(value);
}
const consistencyContextLabels = { CURRENT_PROJECT: '本次项目', HISTORICAL_PROJECT: '历史项目', THIRD_PARTY: '第三方', UNRESOLVED: '未明确', TEMPLATE: '示例 / 模板', BIDDER: '投标人', BID_TOTAL: '本次投标总报价', PROJECT_OPENING: '本次项目开标', INCLUSIVE: '含税', EXCLUSIVE: '不含税', UNSPECIFIED: '含税口径未明确', TOTAL: '总额', YEAR: '每年', MONTH: '每月', CURRENT: '当前表述', CNY: '人民币', USD: '美元', CALENDAR_DAY: '日历天', CALENDAR: '日历天', WORKING: '工作日', WORKING_DAY: '工作日', WORKDAY: '工作日', object: '对象', stage: '阶段', trigger: '起算条件', condition: '适用条件', version: '版本', range: '覆盖范围', period: '期间', currency: '币种', taxBasis: '税价口径', unitBasis: '计量口径', dayType: '日期口径' };
const consistencyModelReasons = { MODEL_DISABLED: '未启用模型补充提取。', NO_READABLE_TEXT: '没有可读取的文字。', UNREADABLE_LOCATIONS: '部分位置没有可读取的文字。', INTERRUPTED: '扫描中断，仍有内容未覆盖。', INPUT_BUDGET: '输入内容超过本次处理额度。', INPUT_INVALID: '部分输入未能完成核验。', MODEL_REQUEST_FAILED: '部分模型请求失败。', INVALID_RESPONSE: '部分模型响应无法用于核对。', FACT_BUDGET: '达到本次信息记录额度，剩余信息需人工核对。', UNGROUNDED_FACTS_REJECTED: '部分提取信息无法用原文核实，已排除。', CALL_BUDGET: '达到该次扫描的调用次数上限，剩余位置尚未覆盖。', PENDING: '该位置尚未扫描。' };
function consistencyContextValue(value) { return String(value || '').split(/[|；]/).map(part => part.trim()).filter(Boolean).map(part => part.startsWith('BIDDER_MEMBER:') ? `联合体成员 · ${part.slice('BIDDER_MEMBER:'.length).trim() || '名称未提供'}` : consistencyContextLabels[part] || part).join(' · '); }
function consistencyModelReason(value) { return consistencyModelReasons[value] || (/^[A-Z_]+$/.test(String(value || '')) ? '部分内容尚未完成核对。' : displayValue(value)); }
const consistencyOccurrenceIndexes = new WeakMap();
function consistencyOccurrences(item) {
  const data = consistencyData(); if (!data) return [];
  if (!consistencyOccurrenceIndexes.has(data)) consistencyOccurrenceIndexes.set(data, new Map((Array.isArray(data.occurrences) ? data.occurrences : []).map(occurrence => [occurrence.id, occurrence])));
  const index = consistencyOccurrenceIndexes.get(data);
  return (Array.isArray(item?.occurrenceIds) ? item.occurrenceIds : []).map(id => index.get(id)).filter(Boolean);
}
function renderReportViewTabs() {
  if(state.category!=='全文信息检查')return '';
  const count=consistencyGroups().length;
  return `<div class="report-detail-switch" aria-label="全文信息检查详情"><button class="${state.reportView==='checks'?'active':''}" aria-pressed="${state.reportView==='checks'}" data-action="report-view" data-view="checks">检查事项</button><button class="${state.reportView==='consistency'?'active':''}" aria-pressed="${state.reportView==='consistency'}" data-action="report-view" data-view="consistency">一致性详情${count?` <span>${count} 组差异</span>`:''}</button></div>`;
}
function renderConsistencyCatalog(data) {
  const categories = consistencyCategories(data);
  if (!categories.length) return '';
  const observed = categories.filter(item => Number(item.occurrences) > 0).length, needsContext = categories.filter(item => item.status === 'NEEDS_CONTEXT').length;
  const capabilities = { RULES_AND_MODEL: '明确标签规则 + 模型补充', RULES: '明确标签规则', RULE_ONLY: '明确标签规则', MODEL: '模型补充提取', MODEL_ONLY: '模型补充提取', MODEL_ASSISTED: '模型辅助核对', LIMITED: '有限规则核对', UNSUPPORTED: '暂未覆盖' };
  const categoryStatuses = { OBSERVED: '已提取到事实', NEEDS_CONTEXT: '比较依据待补充', NO_FACTS: '未提取到可用事实' };
  const model = data.coverage?.model;
  const limitationSets = categories.map(item => new Set((Array.isArray(item.limitations) ? item.limitations : []).map(consistencyModelReason).filter(Boolean)));
  const sharedLimitations = [...limitationSets[0]].filter(value => limitationSets.every(items => items.has(value)));
  const sharedCapability = categories.every(item => item.capability === categories[0].capability) ? categories[0].capability : null;
  return `<details class="panel consistency-catalog" id="consistency-catalog" ${disclosureAttributes('consistency-catalog')}><summary>核对目录 <strong>${categories.length}</strong> 类 <span>${observed} 类有事实${needsContext ? ` · ${needsContext} 类依据待补充` : ''}</span></summary><div class="panel-body"><p class="consistency-catalog-note">${sharedCapability ? `${e(capabilities[sharedCapability] || '核对能力待确认')}。` : ''}未提取到事实、未列出问题，均不表示该类通过。${rulesRetrievalReport(data) ? '本次按全文规则扫描，仅复杂疑点按需模型核验；候选证据不代表全文覆盖。' : model?.enabled === false ? '本次未启用模型，模型补充提取未执行。' : '实际覆盖范围以扫描记录为准。'}</p><div class="consistency-catalog-grid consistency-catalog-list">${categories.map((item, index) => { const specificLimitations = [...limitationSets[index]].filter(value => !sharedLimitations.includes(value)); return `<article class="consistency-category-card category-row ${item.status === 'NEEDS_CONTEXT' ? 'needs-context' : ''}"><div class="category-row-title"><h3>${e(item.title || '其他事项')}</h3><span class="consistency-category-status">${e(categoryStatuses[item.status] || '覆盖状态待确认')}</span></div><div class="consistency-category-counts"><span>事实 <strong>${countValue(item, 'occurrences') ?? 0}</strong></span><button class="text-action" data-action="consistency-category" data-category="${e(item.id)}">问题 <strong>${countValue(item, 'groups') ?? 0}</strong></button></div>${specificLimitations.length || !sharedCapability ? `<details class="category-row-details" ${disclosureAttributes(`category-${item.id}`)}><summary>说明</summary>${!sharedCapability ? `<p>${e(capabilities[item.capability] || '核对能力待确认')}</p>` : ''}${specificLimitations.length ? `<ul>${specificLimitations.map(value => `<li>${e(value)}</li>`).join('')}</ul>` : ''}</details>` : ''}</article>`; }).join('')}</div>${sharedLimitations.length ? `<details class="consistency-catalog-limits" ${disclosureAttributes('catalog-limits')}><summary>共用检查边界 · ${sharedLimitations.length} 项</summary><ul>${sharedLimitations.map(value => `<li>${e(value)}</li>`).join('')}</ul></details>` : ''}</div></details>`;
}
function renderConsistencyComparison(item) {
  const context = item.comparisonContext && typeof item.comparisonContext === 'object' && !Array.isArray(item.comparisonContext) ? item.comparisonContext : {};
  const dimensions = context.dimensions && typeof context.dimensions === 'object' ? context.dimensions : {};
  const calculation = item.calculation && typeof item.calculation === 'object' ? item.calculation : {};
  const unit = calculation.unit ? ` ${unitLabel(calculation.unit)}` : '';
  const arithmetic = Object.fromEntries(Object.entries(calculation).filter(([key]) => ['formula', 'expected', 'actual', 'tolerance'].includes(key)).map(([key, value]) => [key, key === 'formula' ? (value === 'unitPrice * quantity = lineAmount' ? '单价 × 数量 = 行金额' : displayValue(value)) : `${displayValue(value)}${unit}`]));
  const entries = Object.entries({ ...context, ...dimensions, ...arithmetic }).filter(([key, value]) => key !== 'dimensions' && value != null && value !== '');
  if (!item.relationType && !entries.length) return '';
  const explanations = { VALUE: '核对同一事项在多处的取值，先确认对象与口径一致。', ARITHMETIC: '对照参与计算的原文数值，以及本组说明中的运算关系。', TEMPORAL: '对照起算条件、阶段与日期口径，再核对时间关系。', SEMANTIC: '对照承诺、适用条件和例外，确认两处表述能否同时成立。', EVIDENCE: '核对事实与所关联材料的对象及依据是否对应。' };
  return `<div class="consistency-comparison"><h3>为什么比较 · ${e(consistencyRelationLabels[item.relationType] || '上下文核对')}</h3><p>${e(explanations[item.relationType] || '请结合下列比较前提核对原文。')}</p>${entries.length ? `<dl>${entries.map(([key, value]) => `<div><dt>${e(consistencyDimensionLabels[key] || ({ requiredDimensions: '所需依据', missingDimensions: '缺少依据', formula: '计算关系', expected: '计算结果', actual: '原文结果', tolerance: '允许误差', comparator: '比较方式', reason: '比较说明' }[key]) || '补充前提')}</dt><dd>${e(consistencyContextValue(displayValue(value)))}</dd></div>`).join('')}</dl>` : '<p class="consistency-comparison-missing">此组没有保存完整比较前提，请结合原文补充核对。</p>'}</div>`;
}
function renderRulesRetrievalCoverage(data) {
  const coverage = data.coverage || {}, rules = coverage.ruleScan && typeof coverage.ruleScan === 'object' ? coverage.ruleScan : coverage;
  const total = countValue(rules, 'pagesTotal'), scanned = countValue(rules, 'pagesScanned'), unparsed = countValue(rules, 'pagesUnparsed'), skipped = countValue(rules, 'pagesSkipped');
  const document = (state.run.documents || []).find(item => item.id === state.run.run.documentId && item.projectId === state.run.run.projectId);
  const unit = document?.locationType ? locationUnit(document.locationType) : '处', groups = consistencyGroups();
  const model = onDemandModelCoverage(), retrieval = state.run?.summary?.retrievalEvidence;
  const displayCount = (value, key) => countValue(value, key) ?? '—';
  const structuredModelCounts = countValue(model, 'atomicCheckpoints') > 0 ? `<span>条件核验模型尝试 ${displayCount(model, 'structuredModelAttempts')} 次 · 处理完成 ${displayCount(model, 'structuredModelCompleted')} 次 · 失败 ${displayCount(model, 'structuredModelFailed')} 次 · 状态未知 ${displayCount(model, 'structuredModelUnknown')} 次</span><span>此项按条件核验的处理记录单独统计，不代表实际发送次数或业务通过数。</span>` : '';
  const modelNotRequested = fullModelNotRequested(data);
  const alerts = [unparsed > 0 ? `${unparsed} ${unit}未取得可比对内容` : '', scanned == null || total == null ? '规则扫描范围未完整提供' : scanned < total ? '规则扫描未覆盖全部位置' : '', skipped > 0 ? `${skipped} ${unit}未进入规则扫描` : '', coverage.occurrencesTruncated ? '事实提取达到上限' : '', ...onDemandModelAlerts()];
  if (!modelNotRequested) alerts.push('全量模型扫描的请求范围待核实');
  const limitations = [...new Set((Array.isArray(data.limitations) ? data.limitations : []).map(consistencyModelReason).filter(Boolean))];
  const fieldsInScope = [...new Set((Array.isArray(coverage.fields) ? coverage.fields : []).map(field => consistencyFieldLabel(field)))];
  return `<section class="panel consistency-coverage rules-retrieval-coverage" aria-label="全文一致性检查覆盖范围"><div class="panel-header"><h2>${icon('document')}全文一致性</h2><div class="consistency-model-status"><span>全文规则 · 检索补证 · 按需核验</span>${badge('UNDETERMINED', '需人工核对')}</div></div><div class="panel-body"><div class="consistency-coverage-grid"><div><span>规则全文扫描</span><strong>${scanned ?? '—'}<small> / ${total ?? '—'} ${e(unit)}</small></strong></div><div class="${unparsed > 0 ? 'coverage-gap' : ''}"><span>未解析位置</span><strong>${unparsed ?? '—'}<small>${e(unit)}</small></strong></div><div><span>识别事实</span><strong>${countValue(coverage, 'occurrencesCount', 'occurrenceCount') ?? (Array.isArray(data.occurrences) ? data.occurrences.length : '—')}<small>处</small></strong></div><div><span>冲突 / 疑点 / 依据不足</span><strong>${groups.filter(item => item.status === 'CONFLICT').length}<small> / ${groups.filter(item => item.status === 'SUSPECTED').length} / ${groups.filter(item => item.status === 'EVIDENCE_INSUFFICIENT').length}</small></strong></div></div><div class="document-warning-summary evidence-model-scope"><p><strong>检索补证</strong> · ${displayCount(retrieval, 'checkpointsWithRetrieval')} 项使用检索 · ${displayCount(retrieval, 'candidateCount')} 条候选 · ${displayCount(retrieval, 'followUpCheckpoints')} 项补充检索</p><p><strong>按需模型</strong> · ${model?.attemptsUnlimited === true ? '模型调用次数不限 · ' : ''}${e(onDemandRequestCountText(model))} · ${displayCount(model, 'failedAttempts')} 次未完成</p><p class="document-warning-caption">${modelNotRequested ? '本次未请求全量模型扫描。' : '全量模型扫描范围待确认。'}检索候选不代表全页覆盖，疑点核验不代表全文模型扫描；未列出问题也不表示全文一致。</p>${!model ? '<p class="document-warning-caption">按需模型统计未完整提供，不能确认核验范围。</p>' : ''}${!retrieval || countValue(retrieval, 'candidateCount') == null ? '<p class="document-warning-caption">检索候选数量未完整提供。</p>' : ''}</div>${alerts.filter(Boolean).length ? `<p class="consistency-coverage-alert">${icon('alert', true)}<span>${e(alerts.filter(Boolean).join('；'))}。</span></p>` : ''}${renderPersonCredentialCoverage(coverage.personCredentials || (coverage.personCredentialVersion ? coverage : null), document?.id || '')}<details class="consistency-scan-details" id="consistency-scan-details" ${disclosureAttributes('consistency-scan-details')}><summary>扫描记录与检查边界</summary>${fieldsInScope.length ? `<p class="consistency-field-scope">规则核对字段：${fieldsInScope.map(e).join('、')}</p>` : ''}<div class="consistency-model-counts"><span>规则确定为待核 ${displayCount(model, 'ruleDecidedCheckpoints')} 项 · 未调用模型、仍待核 ${displayCount(model, 'modelNotNeededCheckpoints')} 项 · 证据不足 ${displayCount(model, 'evidenceInsufficientCheckpoints')} 项</span><span>输出通过校验 ${displayCount(model, 'validOutputs')} 次 · 输出经保护调整 ${displayCount(model, 'guardedOutputs')} 次</span><span>${countValue(model, 'budgetDeferredCheckpoints') > 0 ? `本次记录的旧调用次数上限导致暂缓 ${model.budgetDeferredCheckpoints} 项 · ` : ''}范围未记录 ${displayCount(model, 'unknownCheckpoints')} 项</span>${structuredModelCounts}</div><p class="document-warning-caption">输出按请求次数统计，不是业务通过数；未调用模型的规则判断仍需结合原文复核。统计对应本次报告保存的范围。</p>${limitations.length ? `<details class="consistency-limitations" ${disclosureAttributes('consistency-limitations', unparsed > 0)}><summary>范围与限制 · ${limitations.length} 项</summary><ul>${limitations.map(item => `<li>${e(item)}</li>`).join('')}</ul></details>` : ''}</details></div></section>`;
}
function renderConsistencyCoverage(data) {
  if (rulesRetrievalReport(data)) return renderRulesRetrievalCoverage(data);
  const coverage = data.coverage || {}, total = countValue(coverage, 'pagesTotal', 'totalPages'), scanned = countValue(coverage, 'pagesScanned', 'scannedPages'), unparsed = countValue(coverage, 'pagesUnparsed', 'unreadablePages', 'unparsedPages');
  const occurrences = Array.isArray(data.occurrences) ? data.occurrences : [], groups = consistencyGroups();
  const document = (state.run.documents || []).find(item => item.id === state.run.run.documentId), unit = document?.locationType ? locationUnit(document.locationType) : '处';
  const model = coverage.model && typeof coverage.model === 'object' ? coverage.model : {};
  const inferredModelStatus = model.enabled === false ? 'DISABLED' : model.complete === true ? 'COMPLETED' : model.enabled === true && (model.scannedChunks > 0 || model.scannedPages > 0) ? 'PARTIAL' : model.enabled === true && model.failedChunks > 0 ? 'FAILED' : model.enabled === true ? 'NOT_RUN' : 'UNKNOWN';
  const modelStatus = String(model.status || coverage.modelStatus || (typeof coverage.model === 'string' ? coverage.model : '') || inferredModelStatus);
  const modelLabels = { NOT_CONFIGURED: '未配置', DISABLED: '未启用', NOT_ENABLED: '未启用', NOT_REQUESTED: '未执行', NOT_RUN: '未执行', SKIPPED: '未执行', PENDING: '待执行', QUEUED: '等待处理', RUNNING: '核对中', COMPLETED: '已完成文本扫描', SUCCEEDED: '已完成文本扫描', SUCCESS: '已完成文本扫描', PARTIAL: '部分完成', PARTIAL_FAILURE: '部分失败', PARTIALLY_COMPLETED: '部分完成', FAILED: '失败', ERROR: '失败', UNAVAILABLE: '暂不可用', UNKNOWN: '未提供状态' };
  const limitations = [...new Set([...(Array.isArray(data.limitations) ? data.limitations : []), ...(Array.isArray(model.reasons) ? model.reasons : [])].map(consistencyModelReason).filter(Boolean))];
  const modelCounts = [['模型覆盖', countValue(model, 'scannedPages', 'completedPages', 'pagesCompleted')], ['部分覆盖', countValue(model, 'partialPages')], ['未覆盖', countValue(model, 'unscannedPages')]].filter(([, value]) => value != null);
  const modelLocations = Array.isArray(model.locations) ? model.locations : [];
  const modelDocumentId = document?.id || state.run.run.documentId;
  const fieldLabels = [...new Set(data.catalogVersion ? occurrences.map(item => consistencyFieldLabel(item.field, item)) : (Array.isArray(coverage.fields) ? coverage.fields : []).map(field => consistencyFieldLabel(field)))];
  const modelWasEnabled = model.enabled !== false && !['DISABLED', 'NOT_CONFIGURED', 'NOT_ENABLED'].includes(modelStatus) && (model.enabled === true || ['RUNNING', 'COMPLETED', 'SUCCEEDED', 'SUCCESS', 'FAILED', 'ERROR', 'UNAVAILABLE', 'PARTIAL', 'PARTIAL_FAILURE', 'PARTIALLY_COMPLETED'].includes(modelStatus));
  const modelIncomplete = modelWasEnabled && (['FAILED', 'ERROR', 'UNAVAILABLE', 'PARTIAL', 'PARTIAL_FAILURE', 'PARTIALLY_COMPLETED'].includes(modelStatus) || Number(model.failedChunks) > 0 || Number(model.unscannedPages) > 0 || Number(model.partialPages) > 0);
  const coverageAlerts = [unparsed > 0 ? `${unparsed} ${unit}未取得可比对内容` : '', scanned == null || total == null ? '扫描范围未完整提供' : scanned < total ? '规则扫描未覆盖全部位置' : '', coverage.occurrencesTruncated ? '事实提取达到上限' : '', modelIncomplete ? '模型覆盖不完整，需核对未覆盖位置' : ''].filter(Boolean);
  const locationDetails = modelLocations.length ? `<details class="consistency-model-locations" ${disclosureAttributes('model-locations')}><summary>模型覆盖位置 · ${modelLocations.length} 处</summary><ul>${modelLocations.map(location => { const page = Number(location.page ?? location.pageNumber ?? location.number), status = location.status || location.coverage || ''; return `<li>${Number.isInteger(page) && page > 0 ? `<button class="text-action" data-action="page-preview" data-id="${e(location.documentId || modelDocumentId)}" data-page="${page}">${e(locationLabel({ page, locationType: location.locationType || document?.locationType }))}</button>` : '<span>位置未提供</span>'}<span>${e(({ SCANNED: '已覆盖', COMPLETE: '已覆盖', COMPLETED: '已覆盖', PARTIAL: '部分覆盖', UNSCANNED: '未覆盖', NOT_SCANNED: '未覆盖', FAILED: '核对失败' }[status]) || displayValue(location.label || status) || '范围待确认')}</span>${countValue(location, 'scannedCharacters') != null && countValue(location, 'totalCharacters') != null ? `<span>已核对 ${location.scannedCharacters} / ${location.totalCharacters} 字</span>` : ''}${(location.reasons?.length || location.reason) ? `<p>${e((Array.isArray(location.reasons) ? location.reasons : [location.reason]).map(consistencyModelReason).filter(Boolean).join(' '))}</p>` : ''}</li>`; }).join('')}</ul></details>` : '';
  return `<section class="panel consistency-coverage" aria-label="全文一致性检查覆盖范围"><div class="panel-header"><h2>${icon('document')}全文一致性</h2><div class="consistency-model-status"><span>模型核对：<strong>${e(modelLabels[modelStatus] || '状态待确认')}</strong></span>${badge('UNDETERMINED', '需人工核对')}</div></div><div class="panel-body"><div class="consistency-coverage-grid"><div><span>规则扫描</span><strong>${scanned == null ? '—' : scanned}<small> / ${total == null ? '—' : total} ${e(unit)}</small></strong></div><div class="${unparsed > 0 ? 'coverage-gap' : ''}"><span>未解析位置</span><strong>${unparsed == null ? '—' : unparsed}<small>${e(unit)}</small></strong></div><div><span>识别事实</span><strong>${countValue(coverage, 'occurrencesCount', 'occurrenceCount') ?? occurrences.length}<small>处</small></strong></div><div><span>冲突 / 疑点 / 依据不足</span><strong>${groups.filter(item => item.status === 'CONFLICT').length}<small> / ${groups.filter(item => item.status === 'SUSPECTED').length} / ${groups.filter(item => item.status === 'EVIDENCE_INSUFFICIENT').length}</small></strong></div></div>${renderPersonCredentialCoverage(coverage.personCredentials || (coverage.personCredentialVersion ? coverage : null), modelDocumentId)}${coverageAlerts.length ? `<p class="consistency-coverage-alert">${icon('alert', true)}<span>${e(coverageAlerts.join('；'))}。</span></p>` : ''}<div class="consistency-coverage-footer"><p class="consistency-coverage-note">未列出问题不表示全文一致，仍需结合原文复核。</p></div><details class="consistency-scan-details" id="consistency-scan-details" ${disclosureAttributes('consistency-scan-details')}><summary>扫描记录与检查边界</summary>${fieldLabels.length ? `<p class="consistency-field-scope">${data.catalogVersion ? '本次识别字段' : '核对字段'}：${fieldLabels.map(field => e(field)).join('、')}</p>` : ''}<div class="consistency-model-counts">${modelCounts.length ? `<span>${modelCounts.map(([label, value]) => `${e(label)} ${value} ${e(unit)}`).join(' · ')}</span>` : ''}${countValue(model, 'scannedChunks') != null && countValue(model, 'totalChunks') != null ? `<span>已核对 ${model.scannedChunks} / ${model.totalChunks} 个片段</span>` : ''}${countValue(model, 'failedChunks') != null && model.failedChunks > 0 ? `<span class="coverage-model-failed">${Number(model.failedChunks)} 个片段核对失败</span>` : ''}</div>${model.reason ? `<p class="consistency-model-reason">${e(consistencyModelReason(model.reason))}</p>` : ''}${locationDetails}${limitations.length ? `<details class="consistency-limitations" ${disclosureAttributes('consistency-limitations', unparsed > 0 || modelIncomplete)}><summary>范围与限制 · ${limitations.length} 项</summary><ul>${limitations.map(item => `<li>${e(item)}</li>`).join('')}</ul></details>` : ''}</details></div></section>`;
}
function consistencyEvidenceKey(evidence) { return evidence ? [evidence.documentId, evidence.page, evidence.anchor, evidence.quote].join('|') : ''; }
const targetedReviewLabels = { IN_PROGRESS: '正在补证与复核', RULE_CONFIRMED: '规则已确认差异', EVIDENCE_AUGMENTED: '已补证据 · 待人工核对', MODEL_REVIEWED: '已取得模型建议 · 待人工核对', EVIDENCE_INSUFFICIENT: '比较依据仍不足', BUDGET_DEFERRED: '本次记录的旧调用次数上限导致暂缓', GROUP_LIMIT_DEFERRED: '本次记录的旧组数或时限限制导致暂缓', MODEL_UNAVAILABLE: '模型暂不可用', ERROR: '定向复核未完成' };
function consistencySourceLinks(evidences, title = '归属来源', quoteRequired = true) {
  const records = Array.isArray(evidences) ? evidences.filter(item => item && typeof item === 'object') : [];
  if (!records.length) return '';
  return `<details class="consistency-source-references"><summary>${e(title)} · ${records.length} 处</summary>${records.map(item => { const page = Number(item.page), located = typeof item.documentId === 'string' && item.documentId.trim() && Number.isInteger(page) && page > 0; const documentName = item.documentName || (state.run?.documents || []).find(document => document.id === item.documentId)?.name || '来源文件'; return `<div><p>${item.referenceLabel ? `${e(item.referenceLabel)}：` : ''}${e(documentName)}${located ? ` · <button class="text-action" data-action="page-preview" data-id="${e(item.documentId)}" data-page="${page}">${e(locationLabel(item))}</button>` : ' · 位置未提供'}${Number.isInteger(item.documentVersion) && item.documentVersion > 0 ? ` · V${item.documentVersion}` : ''}</p>${item.quote || quoteRequired ? `<blockquote>${e(item.quote || '未保存引用文字，请结合原文核对。')}</blockquote>` : ''}</div>`; }).join('')}</details>`;
}
function renderContextAssignment(assignment) {
  if (!assignment || typeof assignment !== 'object' || Array.isArray(assignment) || !Object.keys(assignment).length) return '';
  const labels = { EXPLICIT: '明确标记归属', INHERITED: '沿用章节归属', DOCUMENT_DEFAULT: '按文档角色暂定', UNRESOLVED: '上下文归属待明确' };
  const path = Array.isArray(assignment.sectionPath) ? assignment.sectionPath.filter(item => typeof item === 'string').join(' / ') : '';
  return `<div class="consistency-assignment" data-context-assignment><h4>上下文归属 · ${e(labels[assignment.status] || '归属状态未提供')}</h4>${path ? `<p>所属章节：${e(path)}</p>` : ''}${assignment.reason ? `<p>${e(assignment.reason)}</p>` : ''}${assignment.status === 'DOCUMENT_DEFAULT' ? '<p class="consistency-review-boundary">按文档角色暂定，尚未找到明确章节依据。</p>' : ''}${assignment.crossPage === true ? '<p>此处承接前页章节；归属依据见下方原文。</p>' : ''}${consistencySourceLinks(assignment.evidences, '章节与归属依据')}</div>`;
}
function renderSubjectAssignment(assignment) {
  if (!assignment || typeof assignment !== 'object' || Array.isArray(assignment) || !Object.keys(assignment).length) return '';
  const declared = assignment.status === 'DECLARED_CONSORTIUM_MEMBER' && assignment.projectMatched === true;
  return `<div class="consistency-assignment" data-subject-assignment><h4>主体归属 · ${declared ? '协议明确声明的联合体成员' : '成员声明待核对'}</h4>${assignment.memberName ? `<p>联合体成员 · ${e(assignment.memberName)}</p>` : ''}${declared ? '<p>协议项目名称与本次项目匹配。</p>' : ''}<p class="consistency-review-boundary">本项仅核对文档中的成员声明，未核验签章及法律身份。</p>${consistencySourceLinks(assignment.evidences, '协议与项目封面依据')}</div>`;
}
function renderTargetedReviewDetails(item) {
  const review = item.targetedReview;
  if (!review || typeof review !== 'object' || Array.isArray(review) || !Object.keys(review).length) return '<p class="consistency-review-legacy">本报告未记录组级定向复核，不能确认该组已检索补证或模型核验。</p>';
  const missing = Array.isArray(review.missingDimensions) ? review.missingDimensions : [];
  const guards = Array.isArray(review.guards) ? review.guards : [];
  const recommendation = review.recommendation && typeof review.recommendation === 'object' ? review.recommendation : {};
  return `<section class="consistency-targeted-review" data-targeted-review><div class="consistency-review-heading"><h3>定向补证与复核</h3><strong>${e(targetedReviewLabels[review.status] || '复核状态待确认')}</strong></div><p>${e(review.reason || '请结合原文和本组比较前提继续复核。')}</p><div class="consistency-review-counts"><span>检索候选 <strong>${countValue(review, 'candidateCount') ?? '—'}</strong> 条</span><span>来源校验通过 <strong>${countValue(review, 'acceptedEvidenceCount') ?? '—'}</strong> 条</span><span>排除 <strong>${countValue(review, 'rejectedEvidenceCount') ?? '—'}</strong> 条</span>${countValue(review, 'deduplicatedEvidenceCount') != null ? `<span>去重 <strong>${review.deduplicatedEvidenceCount}</strong> 条</span>` : ''}<span>模型请求：${review.requestOutcomeUnknown === true ? '发送情况未知' : review.status === 'IN_PROGRESS' ? '处理中，等待回执' : review.modelRequested === true ? '已请求' : review.modelRequested === false ? '未请求' : '未记录'}</span></div>${missing.length ? `<p class="consistency-review-gap">待明确：${missing.map(value => e(consistencyDimensionLabels[value] || displayValue(value))).join('、')}</p>` : ''}${guards.length ? `<details><summary>比较保护与限制 · ${guards.length} 项</summary><ul>${guards.map(value => `<li>${e(value && typeof value === 'object' && value.reason ? value.reason : displayValue(value))}</li>`).join('')}</ul></details>` : ''}${recommendation.reason || recommendation.suggestion || recommendation.status ? `<div class="consistency-review-recommendation"><h4>复核建议${recommendation.status ? ` · ${e(statuses[recommendation.status] || '待确认')}（供人工确认）` : ''}</h4>${recommendation.reason ? `<p>${e(recommendation.reason)}</p>` : ''}${recommendation.suggestion ? `<p>建议：${e(recommendation.suggestion)}</p>` : ''}</div>` : ''}${consistencySourceLinks(review.evidences, '补充原文证据')}<p class="consistency-review-boundary">补充证据与模型建议均保留来源；候选数量不代表全文覆盖，模型建议不自动替代本组结论或人工复核。</p></section>`;
}

function renderConsistencyOverview(data) {
  const groups = consistencyGroups(), coverage = data.coverage || {}, scan = coverage.ruleScan || coverage;
  const review = data.doubtReview || {}, counts = review.statusCounts || {}, model = onDemandModelCoverage();
  const reviewed = groups.filter(item => !!consistencyReview(item)).length;
  const notices = [];
  if (countValue(scan, 'pagesUnparsed') > 0) notices.push(`${scan.pagesUnparsed} 处原文未解析`);
  if (countValue(scan, 'pagesScanned') == null || countValue(scan, 'pagesTotal') == null) notices.push('扫描范围待确认');
  else if (scan.pagesScanned < scan.pagesTotal || countValue(scan, 'pagesSkipped') > 0) notices.push('部分原文尚未扫描');
  if (coverage.occurrencesTruncated) notices.push('部分信息尚未完成提取');
  if (countValue(review, 'deferredGroups') > 0) notices.push(`${review.deferredGroups} 组按本次记录的旧处理限制暂缓`);
  if (countValue(model, 'budgetDeferredCheckpoints') > 0) notices.push(`${model.budgetDeferredCheckpoints} 项疑点因本次记录的旧调用次数上限导致暂缓`);
  const failed = (countValue(counts, 'ERROR') || 0) + (countValue(counts, 'MODEL_UNAVAILABLE') || 0);
  if (failed) notices.push(`${failed} 组补充核验未完成`);
  if (countValue(counts, 'IN_PROGRESS') > 0) notices.push(`${counts.IN_PROGRESS} 组仍在处理`);
  if (countValue(review, 'unknownRequestGroups') > 0 || countValue(model, 'consistencyUnknownRequestGroups') > 0) notices.push('部分模型请求发送情况未知');
  if (countValue(model, 'failedAttempts') > 0 && !failed) notices.push('补充核验存在失败请求');
  if (coverage.tableRelations?.requiresReview || countValue(coverage.contextAssignments, 'UNRESOLVED') > 0 || countValue(coverage.contextAssignments, 'DOCUMENT_DEFAULT') > 0) notices.push('部分归属或表格关系仍需确认');
  const fullModel = coverage.model || {};
  if (!rulesRetrievalReport(data) && (['FAILED','ERROR','PARTIAL','PARTIAL_FAILURE'].includes(fullModel.status) || countValue(fullModel,'unscannedPages') > 0 || countValue(fullModel,'failedChunks') > 0)) notices.push('模型扫描尚未完整完成');
  const chips = [['ALL','全部',groups.length], ['CONFLICT','明确差异',groups.filter(item => item.status === 'CONFLICT').length], ['SUSPECTED','疑似差异',groups.filter(item => item.status === 'SUSPECTED').length], ['EVIDENCE_INSUFFICIENT','需补核依据',groups.filter(item => item.status === 'EVIDENCE_INSUFFICIENT').length]];
  return `<section class="consistency-overview" aria-label="全文检查概览"><div class="consistency-overview-heading"><div><h2>全文信息检查</h2><p>对照原文确认差异；依据不足不代表内容矛盾。${model?.attemptsUnlimited === true ? '模型调用次数不限。' : ''}</p></div><span>已人工复核 <strong>${reviewed}</strong> / ${groups.length} 组</span></div><div class="consistency-filter-chips" aria-label="问题分类">${chips.filter(([key,,count]) => key !== 'CONFLICT' || count).map(([key,label,count]) => `<button type="button" class="${state.consistencyFilter === key ? 'active' : ''}" data-action="consistency-kind" data-kind="${key}" aria-pressed="${state.consistencyFilter === key}">${e(label)}<strong>${count}</strong></button>`).join('')}</div>${notices.length ? `<p class="consistency-unfinished" role="status">${icon('alert',true)}<span>${e(notices.join('；'))}，请继续人工核对。</span></p>` : ''}${!groups.length ? '<p class="consistency-unfinished">未列出问题不表示全文一致或全部通过。</p>' : ''}</section>`;
}
function renderTargetedReview(item) {
  const review = item.targetedReview || {};
  const conciseStatuses = { IN_PROGRESS: '正在补充核验', RULE_CONFIRMED: '存在明确差异 · 请确认', EVIDENCE_AUGMENTED: '已补充原文 · 待人工核对', MODEL_REVIEWED: '已取得建议 · 待人工核对', EVIDENCE_INSUFFICIENT: '依据仍不足 · 待补核', BUDGET_DEFERRED: '旧调用次数上限导致暂缓（本次记录）', GROUP_LIMIT_DEFERRED: '旧组数或时限限制暂缓（本次记录）', MODEL_UNAVAILABLE: '补充核验不可用 · 待人工核对', ERROR: '补充复核未完成 · 待人工核对' };
  const status = review.requestOutcomeUnknown ? '发送情况未知 · 待人工核对' : conciseStatuses[review.status] || '补充核验未记录';
  const subjectSuggestion = { personName: '核对姓名、岗位与所属单位的对应关系，并确认是否属于同一项目和标段。', personRole: '核对人员与岗位的对应关系，并确认是否存在兼任或多人配置。', contractNumber: '核对编号是否属于同一份合同，历史业绩合同应分别比较。', totalPrice: '核对币种、含税口径及总价范围，区分本次报价与历史合同金额。', companyName: '核对名称对应的单位身份，区分投标人、联合体成员和历史项目单位。' }[item.field];
  const suggestion = item.status === 'EVIDENCE_INSUFFICIENT' ? '先确认项目、对象和适用范围，再补充能相互对照的原文；当前不能认定存在矛盾。' : subjectSuggestion || '核对各处原文是否属于同一项目、标段和对象，确认后记录复核结论。';
  return `<section class="consistency-action-card" data-review-action><div class="consistency-review-heading"><h3>建议操作</h3><strong>${e(status)}</strong></div><p>${e(suggestion)}</p><details class="consistency-analysis-details" ${disclosureAttributes(`analysis-${item.id}`)}><summary>核对依据与处理记录</summary>${renderConsistencyComparison(item)}${renderTargetedReviewDetails(item)}</details></section>`;
}

function renderTableAssociation(association) {
  if (!association || typeof association !== 'object' || Array.isArray(association) || !Object.keys(association).length) return '';
  const headers = Array.isArray(association.headerPath) ? association.headerPath.filter(value => typeof value === 'string') : [];
  const sources = [association.source ? { ...association.source, referenceLabel: '单元格原文' } : null, ...(Array.isArray(association.headerSources) ? association.headerSources.map(source => ({ ...source, referenceLabel: '表头原文' })) : [])].filter(value => value && typeof value === 'object');
  const row = countValue(association, 'row'), column = countValue(association, 'column');
  return `<div class="consistency-table-association" data-table-association><h4>表格关联 · ${association.status === 'RESOLVED' ? '行列已关联' : '关联待确认'}</h4>${headers.length ? `<p>对应表头：${headers.map(e).join(' / ')}</p>` : '<p>表头路径未提供，需对照原表核对。</p>'}${row > 0 && column > 0 ? `<p>识别表格：第 ${row} 行 · 第 ${column} 列</p>` : ''}${association.inherited === true ? '<p>本行沿用同页合并单元格中的信息。</p>' : ''}${consistencySourceLinks(sources, '单元格与表头来源', false)}<p class="consistency-review-boundary">关联基于已识别文字与行列结构，仍需核对原图布局；相邻页不自动认定为同一人员记录。</p></div>`;
}
function renderDoubtReviewSummary(data) {
  const review = data?.doubtReview;
  if (!review || typeof review !== 'object' || Array.isArray(review)) return '';
  const triage = countValue(review, 'triagedGroups') != null ? `<p>本地初步分类 ${review.triagedGroups} 组 · 仅本地处理 ${countValue(review, 'localOnlyGroups') ?? '—'} 组。初步分类和机器处理均不代表人工已复核。</p>` : '';
  return `<section class="panel consistency-doubt-summary" aria-label="全文疑点定向复核范围"><div class="panel-body"><h3>疑点处理记录</h3>${triage}<p>机器处理 ${countValue(review, 'reviewedGroups') ?? '—'} / ${countValue(review, 'totalGroups') ?? '—'} 组 · 暂缓 ${countValue(review, 'deferredGroups') ?? '—'} 组 · 检索补证 ${countValue(review, 'retrievalGroups') ?? '—'} 组 · 请求模型 ${countValue(review, 'modelRequestedGroups') ?? '—'} 组 · 模型尝试 ${countValue(review, 'modelAttempts') ?? '—'} 次${countValue(review, 'unknownRequestGroups') != null ? ` · 发送情况未知 ${review.unknownRequestGroups} 组` : ''}</p><p class="consistency-review-boundary">${e(review.boundary || '已处理不代表疑点已确认；请查看每组证据、限制和人工复核记录。')}</p></div></section>`;
}
function renderContextTableCoverage(data) {
  const assignment = data?.coverage?.contextAssignments, table = data?.coverage?.tableRelations;
  if ((!assignment || typeof assignment !== 'object') && (!table || typeof table !== 'object')) return '';
  const issues = Array.isArray(table?.issues) ? table.issues : [];
  return `<section class="panel consistency-doubt-summary" data-context-table-coverage><div class="panel-body"><h3>上下文与表格整理</h3>${assignment && typeof assignment === 'object' ? `<p>事实归属：明确标记 ${countValue(assignment, 'EXPLICIT') ?? '—'} 条 · 沿用章节 ${countValue(assignment, 'INHERITED') ?? '—'} 条 · 文档暂定 ${countValue(assignment, 'DOCUMENT_DEFAULT') ?? '—'} 条 · 未明确 ${countValue(assignment, 'UNRESOLVED') ?? '—'} 条</p>` : ''}${table && typeof table === 'object' ? `<p>识别表格 ${countValue(table, 'tables') ?? '—'} 个 · 行关系已关联 ${countValue(table, 'resolvedRows') ?? '—'} / ${countValue(table, 'rows') ?? '—'} 行 · 待核对 ${countValue(table, 'unresolvedRows') ?? '—'} 行 · 续表结构关联 ${countValue(table, 'linkedContinuations') ?? '—'} 组</p>${table.requiresReview === true ? '<p class="consistency-review-gap">部分表格关系仍需核对原文，未确认的关联不参与确定性判断。</p>' : ''}${issues.length ? `<details class="consistency-source-references"><summary>表格关联提示 · ${issues.length} 项</summary><ul>${issues.map(issue => { const page = Number(issue.source?.page ?? issue.page), documentId = issue.source?.documentId; return `<li>${Number.isInteger(page) && page > 0 && typeof documentId === 'string' && documentId ? `<button class="text-action" data-action="page-preview" data-id="${e(documentId)}" data-page="${page}">${e(locationLabel({ page, locationType: issue.source?.locationType || issue.locationType || 'PAGE' }))}</button> · ` : ''}${e(issue.reason || '该处表格关联依据不足，请核对原文。')}</li>`; }).join('')}</ul></details>` : ''}` : ''}<p class="consistency-review-boundary">未明确的事实、待核对的表格行不等于人工待办数；请按问题列表集中复核。已关联表示结构依据可追溯，不代表 OCR 或业务判断准确；续表不自动继承人员身份。</p></div></section>`;
}
function renderConsistencyOccurrence(occurrence, index) {
  const evidence = occurrence.evidence, raw = displayValue(occurrence.rawValue || occurrence.value), normalized = displayValue(occurrence.value);
  const meta = [['主体', occurrence.subject], ['范围', occurrence.scope], ['标段', occurrence.lot], ['口径', occurrence.basis], ['时点', occurrence.time], ['表述', occurrence.revision], ...Object.entries(occurrence.dimensions && typeof occurrence.dimensions === 'object' ? occurrence.dimensions : {}).filter(([, value]) => value != null && value !== '').map(([key, value]) => [consistencyDimensionLabels[key] || '补充条件', displayValue(value)])];
  const comparability = { STRONG: '', WEAK: '比对依据仍需人工核对', INVALID: '该信息未取得足够比对依据', INCOMPLETE: '比较所需的对象、单位或条件尚不完整', COMPARABLE: '可按同一口径比对', UNRESOLVED: '比对范围待确认', INCOMPARABLE: '口径不同，需分别核对', AMBIGUOUS: '比对范围待确认', UNKNOWN: '比对范围待确认' }[occurrence.comparability];
  return `<article class="consistency-occurrence"><div class="consistency-occurrence-header"><strong>原文 ${String(index + 1).padStart(2, '0')}</strong>${evidence ? `<span>${e(locationLabel(evidence))}</span>` : '<span>位置未提供</span>'}</div>${raw ? `<div class="consistency-value"><strong>${e(raw)}</strong>${normalized && normalized !== raw ? `<small>用于比对的值：${e(normalized)}${occurrence.unit ? ` ${e(unitLabel(occurrence.unit))}` : ''}</small>` : occurrence.unit ? `<small>比对单位：${e(unitLabel(occurrence.unit))}</small>` : ''}</div>` : ''}${occurrence.subject ? `<p class="consistency-source-subject">${e(consistencyContextValue(occurrence.subject))}</p>` : ''}${evidence ? `<div class="consistency-original"><p>${e(evidence.documentName || '来源文件')}</p><blockquote>${e(evidence.quote || '该位置未保存引用文字，请打开原文核对。')}</blockquote><button class="text-action" data-action="page-preview" data-id="${e(evidence.documentId)}" data-page="${Number.isInteger(Number(evidence.page)) && Number(evidence.page) > 0 ? Number(evidence.page) : ''}" ${!evidence.documentId || !Number.isInteger(Number(evidence.page)) || Number(evidence.page) <= 0 ? 'disabled' : ''}>${icon('pin', true)}查看原文 · ${e(locationLabel(evidence))}</button></div>` : '<p class="consistency-evidence-missing">此处未提供可定位的原文，暂不能据此完成复核。</p>'}<details class="consistency-occurrence-details"><summary>来源与归属详情</summary><dl class="consistency-context">${meta.filter(([, value]) => value != null && value !== '').map(([label, value]) => `<div><dt>${label}</dt><dd>${e(consistencyContextValue(value) || '未明确')}</dd></div>`).join('')}</dl>${renderSubjectAssignment(occurrence.subjectAssignment)}${renderContextAssignment(occurrence.contextAssignment)}${renderTableAssociation(occurrence.tableAssociation)}${occurrence.scopeReason || comparability ? `<p class="consistency-scope-note">${e([occurrence.scopeReason, comparability].filter(Boolean).join('；'))}</p>` : ''}</details></article>`;
}
function renderConsistencyGroup(item) {
  const occurrences = consistencyOccurrences(item), finding = consistencyFinding(item), review = finding && reviewOf(finding);
  const linked = new Set(occurrences.map(occurrence => consistencyEvidenceKey(occurrence.evidence)).filter(Boolean));
  const extras = (Array.isArray(item.evidences) ? item.evidences : []).filter(evidence => { const key = consistencyEvidenceKey(evidence); if (!key || linked.has(key)) return false; linked.add(key); return true; });
  const original = [...occurrences, ...extras.map(evidence => ({ evidence, field: item.field }))];
  const valueKey = occurrence => occurrence.rawValue || occurrence.value || occurrence.evidence?.quote || '';
  const alternate = original.findIndex((occurrence, index) => index > 0 && valueKey(occurrence) !== valueKey(original[0]));
  const all = alternate > 1 ? [original[0], original[alternate], ...original.slice(1, alternate), ...original.slice(alternate + 1)] : original, pageSize = 2, pages = Math.max(1, Math.ceil(all.length / pageSize));
  if (state.consistencyEvidenceGroup !== item.id) { state.consistencyEvidenceGroup = item.id; state.consistencyEvidencePage = 1; }
  const page = Math.min(Math.max(1, state.consistencyEvidencePage), pages), offset = (page - 1) * pageSize;
  const cards = all.slice(offset, offset + pageSize).map((occurrence, index) => renderConsistencyOccurrence(occurrence, offset + index)).join('');
  return `<section class="panel consistency-detail" aria-label="全文一致性问题组详情"><div class="panel-header"><div><h2>${e(item.title || fields[item.field] || '信息差异')}</h2><p>${e(consistencyFieldLabel(item.field, item))}${item.categoryId ? ` · ${e(consistencyCategoryLabel(item.categoryId))}` : ''} · ${occurrences.length + extras.length} 处原文</p></div><div class="consistency-detail-tags">${badge(consistencyGroupStatus(item), `自动：${consistencyGroupLabels[item.status] || '待核实'}`)}${review ? badge(review.status, `人工：${statuses[review.status] || review.status}`) : ''}</div></div><div class="panel-body"><div class="consistency-reason ${item.status === 'EVIDENCE_INSUFFICIENT' ? 'insufficient-evidence' : ''}"><strong>${item.status === 'EVIDENCE_INSUFFICIENT' ? '比较依据不足' : '判断依据'}</strong><p>${e(item.reason || '请对照以下原文及上下文，确认是否属于同一对象和口径。')}</p>${item.status === 'EVIDENCE_INSUFFICIENT' ? '<p>这是比较依据缺口，不能据此认定材料缺失或内容矛盾。</p>' : ''}</div><div class="consistency-evidence-heading"><h3>原文对照</h3><span>优先展示不同取值 · 可查看全部 ${all.length} 处来源</span></div>${pages > 1 ? `<div class="consistency-evidence-pagination"><span>第 ${offset + 1}–${Math.min(offset + pageSize, all.length)} 处，共 ${all.length} 处原文</span><button class="button button-secondary button-small" data-action="consistency-evidence-page" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>上一页</button><span>${page} / ${pages}</span><button class="button button-secondary button-small" data-action="consistency-evidence-page" data-page="${page + 1}" ${page >= pages ? 'disabled' : ''}>下一页</button></div>` : ''}${cards ? `<div class="consistency-evidence-grid">${cards}</div>` : '<div class="empty-inline">此组没有可显示的原文证据，请结合原文件核对。</div>'}${renderTargetedReview(item)}${finding ? `<div class="consistency-review-actions"><button class="button button-primary button-small" data-action="consistency-review-open">人工复核</button><button class="button button-secondary button-small" data-action="consistency-review-open" data-ignore="true">不适用 / 忽略</button><span>需填写依据并保存，自动结论始终保留</span></div><details class="consistency-manual-details" ${disclosureAttributes(`manual-${item.id}`, !!review || state.reviewDrafts.has(finding.id))}><summary>填写复核结论${review ? ' · 已有复核记录' : ''}</summary>${renderManualReview(finding, '本组人工复核')}</details>` : '<div class="inline-warning consistency-no-review">此组暂未关联可复核的检查结果；原文对照仍可查看。</div>'}</div></section>`;
}
function renderConsistencyFacts(data) {
  const all = Array.isArray(data.occurrences) ? data.occurrences : [], grouped = new Set(consistencyGroups().flatMap(item => Array.isArray(item.occurrenceIds) ? item.occurrenceIds : []));
  const ungrouped = all.filter(item => !grouped.has(item.id)), visible = state.consistencyFactsMode === 'UNGROUPED' ? ungrouped : all;
  const pageSize = 6, pages = Math.max(1, Math.ceil(visible.length / pageSize)), page = Math.min(Math.max(1, state.consistencyFactsPage), pages), offset = (page - 1) * pageSize;
  state.consistencyFactsPage = page;
  return `<details class="panel consistency-facts" id="consistency-facts" ${disclosureAttributes('consistency-facts')}><summary>事实记录 <strong>${all.length}</strong> 处 <span>未列入差异组 ${ungrouped.length} 处 · 可逐页查看全部来源</span></summary><div class="panel-body"><p class="consistency-facts-note">未列入差异组的记录可能涉及历史项目、第三方、口径未明确、单次出现或取值相同。没有成组差异不表示该信息已核实。</p>${data.coverage?.occurrencesTruncated ? '<p class="consistency-facts-note coverage-model-failed">本次提取记录达到数量上限，尚有原文未形成记录，请继续人工核对。</p>' : ''}<div class="consistency-facts-toolbar"><select id="consistency-facts-mode" aria-label="筛选事实记录">${options({ ALL: `全部事实（${all.length}）`, UNGROUPED: `未列入差异组（${ungrouped.length}）` }, state.consistencyFactsMode)}</select><span>${visible.length ? `显示第 ${offset + 1}–${Math.min(offset + pageSize, visible.length)} 条，共 ${visible.length} 条` : '没有符合条件的记录'}</span><div><button class="button button-secondary button-small" data-action="consistency-facts-page" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>上一页</button><span>${page} / ${pages}</span><button class="button button-secondary button-small" data-action="consistency-facts-page" data-page="${page + 1}" ${page >= pages ? 'disabled' : ''}>下一页</button></div></div>${visible.length ? `<div class="consistency-evidence-grid">${visible.slice(offset, offset + pageSize).map((item, index) => `<div class="consistency-fact-record"><p class="consistency-fact-membership">${grouped.has(item.id) ? '已列入差异组' : '未列入差异组'}</p>${renderConsistencyOccurrence(item, offset + index)}</div>`).join('')}</div>` : '<div class="empty-inline">本次未提取到此类记录，请结合原文和扫描覆盖范围核对。</div>'}</div></details>`;
}
function renderConsistency() {
  const data = consistencyData(), run = state.run.run;
  if (!data) return `<section class="panel consistency-empty"><div class="empty-inline"><h2>${terminal.has(run.status) ? '此报告尚未保存全文一致性分析' : '正在生成全文一致性分析'}</h2><p>${terminal.has(run.status) ? '已有检查项报告仍可查看。重新检查后，将按新流程展示冲突组和检查覆盖范围。' : '生成后会展示各组差异和多处原文，当前没有可确认的全文结论。'}</p><button class="button button-secondary" data-action="report-view" data-view="checks">查看检查项报告</button></div></section>`;
  const groups = consistencyGroups(), fieldOptions = Object.fromEntries([...new Set(groups.map(item => item.field).filter(Boolean))].map(field => [field, consistencyFieldLabel(field, groups.find(item => item.field === field))]));
  const categoryOptions = Object.fromEntries(consistencyCategories(data).map(item => [item.id, item.title || '其他事项']));
  const relationOptions = Object.fromEntries([...new Set(groups.map(item => item.relationType).filter(Boolean))].map(relation => [relation, consistencyRelationLabels[relation] || '其他关系']));
  const filtered = groups.filter(item => (state.consistencyFilter === 'ALL' || item.status === state.consistencyFilter) && (state.consistencyField === 'ALL' || item.field === state.consistencyField) && (state.consistencyCategory === 'ALL' || item.categoryId === state.consistencyCategory) && (state.consistencyRelation === 'ALL' || item.relationType === state.consistencyRelation) && (state.consistencyReview === 'ALL' || (state.consistencyReview === 'REVIEWED') === !!consistencyReview(item)) && `${item.title || ''} ${item.reason || ''} ${consistencyFieldLabel(item.field, item)} ${consistencyCategoryLabel(item.categoryId)} ${consistencyRelationLabels[item.relationType] || ''} ${displayValue(item.comparisonContext)} ${consistencyOccurrences(item).map(occurrence => [occurrence.rawValue, occurrence.value, occurrence.subject, occurrence.scope, occurrence.lot, occurrence.dimensions].map(displayValue).join(' ')).join(' ')}`.toLowerCase().includes(state.consistencyQuery.toLowerCase()));
  const selected = filtered.find(item => item.id === state.selectedConsistencyGroup) || filtered[0];
  state.selectedConsistencyGroup = selected?.id || null;
  const reviewed = groups.filter(item => !!consistencyReview(item)).length;
  const moreFilterCount = [state.consistencyCategory, state.consistencyRelation, state.consistencyField].filter(value => value !== 'ALL').length;
  const moreFilters = `<details class="consistency-filter-disclosure" id="consistency-more-filters" ${disclosureAttributes('consistency-more-filters')}><summary>更多筛选${moreFilterCount ? `<span class="tab-count">${moreFilterCount}</span>` : ''}</summary><div class="consistency-more-filter-fields">${Object.keys(categoryOptions).length ? `<label>核对类别<select id="consistency-category" aria-label="筛选核对类别">${options({ ALL: '全部类别', ...categoryOptions }, state.consistencyCategory)}</select></label>` : ''}${Object.keys(relationOptions).length ? `<label>比较关系<select id="consistency-relation" aria-label="筛选核对关系">${options({ ALL: '全部关系', ...relationOptions }, state.consistencyRelation)}</select></label>` : ''}<label>比对字段<select id="consistency-field" aria-label="筛选比对字段">${options({ ALL: '全部字段', ...fieldOptions }, state.consistencyField)}</select></label>${moreFilterCount ? '<button class="text-action" data-action="clear-consistency-filters">清空筛选</button>' : ''}</div></details>`;
  return `${renderConsistencyOverview(data)}<details class="panel consistency-processing-details" id="consistency-processing-details" ${disclosureAttributes("consistency-processing-details")}><summary>检查范围与处理详情</summary><div class="panel-body">${renderConsistencyCoverage(data)}${renderDoubtReviewSummary(data)}${renderContextTableCoverage(data)}${renderConsistencyCatalog(data)}</div></details><div class="consistency-toolbar" aria-label="筛选全文一致性问题"><div class="search-wrap">${icon('search')}<input type="search" id="consistency-search" aria-label="搜索全文差异或原文取值" placeholder="搜索问题、取值或主体" value="${e(state.consistencyQuery)}"></div><select id="consistency-review" aria-label="筛选组级复核状态">${options({ ALL: '全部复核状态', PENDING: '待复核', REVIEWED: '已复核' }, state.consistencyReview)}</select>${moreFilters}<span>${filtered.length} / ${groups.length} 组 · 已复核 ${reviewed} 组</span></div>${groups.length ? `<div class="consistency-layout"><section class="panel consistency-group-list" aria-label="全文一致性问题组">${filtered.length ? filtered.map(item => { const review = consistencyReview(item); return `<button class="consistency-group-button ${item.id === selected?.id ? 'selected' : ''}" data-action="select-consistency-group" data-id="${e(item.id)}" aria-pressed="${item.id === selected?.id}"><strong>${e(item.title || fields[item.field] || '信息差异')}</strong><div>${badge(consistencyGroupStatus(item), consistencyGroupLabels[item.status] || '待核实')}<span>${e(consistencyFieldLabel(item.field, item))}</span>${review ? `<span class="consistency-reviewed">已复核 · ${e(statuses[review.status] || review.status)}</span>` : '<span>待复核</span>'}</div><p>${e(item.reason || '')}</p></button>`; }).join('') : '<div class="empty-inline">没有符合筛选条件的问题组。<br><button class="text-action" data-action="clear-consistency-filters">清空筛选</button></div>'}</section>${selected ? renderConsistencyGroup(selected) : '<section class="panel empty-inline">调整筛选条件后可查看原文对照。</section>'}</div>` : '<section class="panel consistency-empty"><div class="empty-inline"><h2>本次未列出待核对的问题组</h2><p>请结合扫描范围和原文件继续核对。此处不表示全文一致或全部通过。</p></div></section>'}${renderConsistencyFacts(data)}`;
}
function incrementalDecision(record) {
  const decision = record?.decision && typeof record.decision === 'object' && !Array.isArray(record.decision) ? record.decision : null;
  return decision && (decision.decision || decision.publicationState && decision.publicationState !== 'CANDIDATE_ONLY') ? decision : null;
}
function incrementalPublicationState(record) {
  return incrementalDecision(record)?.publicationState || record?.publicationState || 'CANDIDATE_ONLY';
}
function incrementalChangedResults(record) {
  return (Array.isArray(record?.results) ? record.results : []).filter(item => item?.evaluationChanged === true);
}
function incrementalStatusLabel(value) {
  return ({ CANDIDATE_ONLY: '待审核', PUBLISHED: '已发布新版本', REJECTED: '已驳回' })[value] || '状态待确认';
}
function renderIncrementalCandidates() {
  const records = Array.isArray(state.incrementalCandidates) ? state.incrementalCandidates : [];
  const createAction = `<button class="button button-secondary button-small" data-action="create-incremental-candidate">根据新增证据生成候选</button>`;
  if (!records.length) return state.incrementalCandidateError
    ? `<details class="panel incremental-candidate-load-error"><summary>增量结果状态暂未加载</summary><div class="panel-body"><p>${e(state.incrementalCandidateError)}</p><button class="button button-secondary button-small" data-action="refresh-incremental-candidates">重新加载</button></div></details>`
    : `<details class="panel incremental-candidates"><summary><span><strong>增量结论</strong><small>补充证据后，仅重新计算依赖这些证据的检查项</small></span><span>尚无候选</span></summary><div class="panel-body"><p class="preview-caption">原始检查报告保持不变。请选择实际发生变化的证据范围，系统会先计算影响范围，再生成待审核候选。</p>${createAction}</div></details>`;
  const pending = records.filter(item => incrementalPublicationState(item) === 'CANDIDATE_ONLY' && Number(item.changedCheckpointCount || 0) > 0).length;
  const published = records.filter(item => incrementalPublicationState(item) === 'PUBLISHED').length;
  const rejected = records.filter(item => incrementalPublicationState(item) === 'REJECTED').length;
  return `<details class="panel incremental-candidates" ${pending ? 'open' : disclosureAttributes('incremental-candidates')}><summary><span><strong>增量结论候选</strong><small>新证据只影响关联检查项，旧报告保持不变</small></span><span class="incremental-candidate-counts">${pending ? `待审核 ${pending}` : '无待审核'}${published ? ` · 已发布 ${published}` : ''}${rejected ? ` · 已驳回 ${rejected}` : ''}</span></summary><div class="panel-body"><div class="incremental-candidate-toolbar">${createAction}<small>仅可选择本次依赖图中已登记的证据范围</small></div><div class="incremental-candidate-list">${records.slice().reverse().map(record => {
    const publication = incrementalPublicationState(record), decision = incrementalDecision(record), changed = incrementalChangedResults(record), changedCount = Number(record.changedCheckpointCount ?? changed.length), affected = Number(record.recalculatedCheckpointCount ?? record.affectedCheckpointIds?.length ?? 0);
    const names = changed.slice(0, 5).map(item => { const before = item.beforeFinding?.evaluation?.status, after = item.candidateFinding?.evaluation?.status; return `<li><span>${e(item.candidateFinding?.checkpoint?.title || item.checkpointId || '检查项')}</span><small>${e(statuses[before] || before || '原结论')} → ${e(statuses[after] || after || '候选结论')}</small></li>`; }).join('');
    const canDecide = publication === 'CANDIDATE_ONLY' && changedCount > 0;
    return `<article class="incremental-candidate-card ${e(publication)}"><div class="incremental-candidate-head"><div><strong>${changedCount ? `${changedCount} 项结论发生变化` : '本次没有结论变化'}</strong><small>${e(date(record.createdAt))} · 重算 ${affected} 项</small></div><span class="pill ${e(publication)}">${e(incrementalStatusLabel(publication))}</span></div>${names ? `<ul>${names}</ul>${changed.length > 5 ? `<p class="preview-caption">另有 ${changed.length - 5} 项变化，可在审核时查看。</p>` : ''}` : '<p class="preview-caption">证据已重新计算，没有产生需要发布的结论变化。</p>'}${decision ? `<p class="incremental-decision-note"><strong>${e(decision.reviewer || '审核人')}</strong>：${e(decision.note || '未填写说明')}</p>` : ''}<div class="incremental-candidate-actions">${canDecide ? `<button class="button button-primary button-small" data-action="incremental-decision" data-id="${e(record.id)}" data-decision="APPROVE">审核并发布</button><button class="button button-secondary button-small" data-action="incremental-decision" data-id="${e(record.id)}" data-decision="REJECT">驳回候选</button>` : ''}${publication === 'PUBLISHED' && (decision?.publishedResultVersionId || record.publishedResultVersionId) ? `<button class="text-action" data-action="incremental-result-version" data-id="${e(decision?.publishedResultVersionId || record.publishedResultVersionId)}">查看发布版本</button>` : ''}</div></article>`;
  }).join('')}</div></div></details>`;
}
function publishedRunVersion() {
  const value = state.run?.publishedResultVersion;
  return value && typeof value === 'object' && !Array.isArray(value) && value.id ? value : null;
}
function displayedRunResults() {
  const source = Array.isArray(state.run?.results) ? state.run.results : [], version = publishedRunVersion();
  if (state.resultVersionView !== 'current' || !version || !Array.isArray(version.results)) return source;
  const overlays = new Map(version.results.map(entry => [entry?.checkpointId, entry?.finding]).filter(([id, finding]) => id && finding));
  return source.map(finding => overlays.get(finding?.checkpoint?.id) || finding);
}
function renderResults() {
  const runs = [...(state.project.runs || [])].reverse();
  if (!runs.length) return `<div class="empty-state"><div class="empty-illustration">${icon('shield')}</div><h2>检查报告将在这里生成</h2><p>确认检查项并上传投标文件后，启动一次检查。<br>每一条问题都保留检查依据、投标证据与人工复核记录。</p><button class="button button-primary" data-action="tab" data-tab="documents">前往准备检查 ${icon('arrow', true)}</button></div>`;
  if (!state.run) return '<div class="empty-inline"><span class="spinner"></span> 正在加载检查结果…</div>';
  const run = state.run.run, results = displayedRunResults(), complete = terminal.has(run.status) && !failed.has(run.status);
  const allGroups=businessGroups(results),categoryCounts=allGroups.reduce((counts,item)=>{const key=businessGroupCategory(item);counts[key]=(counts[key]||0)+1;return counts;},{});
  const categoryGroups=allGroups.filter(item=>state.category==='ALL'||businessGroupCategory(item)===state.category);
  const searchedGroups=categoryGroups.filter(item=>item.members.some(f=>`${f.checkpoint.title} ${businessPresentation(f,true).reason||findingPresentation(f).reason} ${f.checkpoint.expected||''}`.toLowerCase().includes(state.resultQuery.toLowerCase())));
  const groups=searchedGroups.map(item=>({...item,members:item.members.filter(findingMatchesResultScope)})).filter(item=>item.members.length);
  const scopedResults=groups.flatMap(item=>item.members),shownGroups=groups.filter(item=>state.filter==='ALL'||businessGroupState(item)===state.filter||state.filter==='MY_ACTIONS'&&item.members.some(f=>businessPresentation(f,true).userActionRequired&&!reviewOf(f)));
  const selected = shownGroups.flatMap(item=>item.members).find(f => f.id === state.selectedFinding) || businessGroupPrimaryMember(shownGroups[0]), selectedGroup = shownGroups.find(item => item.members.some(f => f.id === selected?.id));
  if (selected && selected.id !== state.selectedFinding) { state.selectedFinding = selected.id; state.sourceDoc = null; state.sourcePage = 0; }
  const references = selected ? findingEvidence(selected) : { source: null, tender: [], bid: [] };
  const evidence = references.bid[0] || references.source || references.tender[0];
  const document = (state.run.documents || docs()).find(d => d.id === (state.sourceDoc || evidence?.documentId)) || (state.run.documents || docs()).find(d => d.id === run.documentId);
  const publishedVersion = publishedRunVersion();
  const versionSwitch = publishedVersion ? `<div class="result-version-switch"><label for="result-version-view">查看结论版本</label><select id="result-version-view"><option value="current" ${state.resultVersionView === 'current' ? 'selected' : ''}>当前发布版本 V${e(publishedVersion.revision)}</option><option value="source" ${state.resultVersionView === 'source' ? 'selected' : ''}>原始检查报告</option></select><small>${state.resultVersionView === 'current' ? `由 ${e(publishedVersion.publishedBy || '审核人')} 发布：${e(publishedVersion.publicationNote || '未填写发布说明')}` : '历史原始结论只读保留，未被增量发布覆盖'}</small></div>` : '';
  const reportHeader = `<div class="result-topline"><select id="run-select" aria-label="选择历史检查报告">${runs.map(r => `<option value="${e(r.id)}" ${r.id === run.id ? 'selected' : ''}>${e(date(r.createdAt))} · ${e(r.recoveryOnly?'补查批次 · ':'')}${e(terminal.has(r.status)&&!failed.has(r.status)?'本轮执行结束':runStatuses[r.status] || r.status)}</option>`).join('')}</select><div class="report-actions"><button class="button button-primary button-small" data-action="retry-run" ${!complete || state.busy.has('run') || isRunning() || !checksReady() || processing() ? 'disabled' : ''}>${icon('refresh', true)}重新检查</button><button class="button button-secondary button-small" data-action="tab" data-tab="${checksReady() ? 'profile' : 'checkpoints'}">${icon('upload', true)}替换投标文件</button><span>下载报告</span>${['html', 'csv', 'json'].map(format => `<button class="button button-secondary button-small" data-action="download-report" data-format="${format}" ${!complete ? 'disabled' : ''}>${icon('download', true)}${format.toUpperCase()}</button>`).join('')}</div></div>${versionSwitch}${!complete ? `<div class="run-status-banner ${e(run.status)}">${failed.has(run.status) ? icon('alert') : '<span class="spinner"></span>'}<div><strong>${e(runStatuses[run.status] || run.status)}</strong><div>${e(run.error || run.stage || '正在处理检查任务')}</div>${typeof run.progress === 'number' ? `<div class="progress-track" role="progressbar" aria-label="检查进度" aria-valuenow="${Math.max(0, Math.min(100, run.progress))}" aria-valuemin="0" aria-valuemax="100"><div class="progress-fill" style="width:${Math.max(0, Math.min(100, run.progress))}%"></div></div>` : ''}${failed.has(run.status) && run.taskId ? `<button class="text-action" data-action="retry-task" data-id="${e(run.taskId)}">重试检查任务</button>` : ''}</div></div>` : ''}`;
  const categoryNav=renderCategoryNavigation(categoryCounts,state.category,'result-category','业务事项');
  if (state.reportView === 'consistency'&&state.category==='全文信息检查') return reportHeader + renderIncrementalCandidates() + categoryNav + renderResolutionOverview(groups,scopedResults) + renderReportViewTabs() + renderConsistency();
  const actions = uniqueUserActions(scopedResults), mandatoryCount=searchedGroups.filter(item=>item.members.some(f=>findingPurpose(f)==='MANDATORY')).length, scoringCount=searchedGroups.filter(item=>item.members.some(f=>findingPurpose(f)==='SCORING')).length, externalCount=searchedGroups.filter(item=>item.members.some(f=>findingVerificationMode(f)==='EXTERNAL')).length;
  const scopeFilters=`<div class="result-scope-filters" aria-label="按检查目的和核验方式筛选"><div class="scope-filter-group" role="group" aria-label="检查目的">${[['ALL','全部'],['MANDATORY',`必备要求 ${mandatoryCount}`],['SCORING',`评分优化 ${scoringCount}`]].map(([value,label])=>`<button class="${state.purposeFilter===value?'active':''}" data-action="result-purpose" data-purpose="${value}" aria-pressed="${state.purposeFilter===value}">${e(label)}</button>`).join('')}</div><button class="scope-filter-toggle ${state.externalOnly?'active':''}" data-action="result-external" aria-pressed="${state.externalOnly}">外部核验 ${externalCount}</button></div>`;
  return reportHeader + renderIncrementalCandidates() + categoryNav + renderResolutionOverview(groups,scopedResults) + renderReportViewTabs() + `<div class="result-metrics business-metrics">${Object.entries(businessStates).map(([status,label]) => `<button class="result-metric ${status} ${state.filter===status?'active':''}" data-action="filter-status" data-status="${status}" aria-pressed="${state.filter===status}"><span>${label}</span><strong>${groups.filter(item=>businessGroupState(item)===status).length}</strong></button>`).join('')}</div><div class="business-summary"><span>${groups.length} 个业务事项 · ${scopedResults.length} 条检查记录</span><button class="text-action" data-action="filter-status" data-status="MY_ACTIONS">需要我处理 ${actions.length} 个动作</button><small>同一 actionId 只计一个待办；系统与外部动作单独归属。</small></div>${scopeFilters}<div class="result-filters"><div class="search-wrap">${icon('search')}<input id="result-search" type="search" aria-label="搜索检查项或问题" placeholder="搜索事项、问题描述" value="${e(state.resultQuery)}"></div><select id="result-status" aria-label="筛选业务结论">${options({ALL:'全部事项',...businessStates,MY_ACTIONS:'需要我处理'},state.filter)}</select><span class="filter-total">${shownGroups.length} / ${groups.length} 个事项 · 保留人工复核结论</span></div>${renderReportOcrContext()}${results.length?`<div class="results-layout results-focus-layout"><section class="panel finding-list" aria-label="检查结果列表">${shownGroups.length?shownGroups.map(item=>renderBusinessListItem(item,selected)).join(''):categoryGroups.length===0&&state.category!=='ALL'?`<div class="empty-inline category-empty">${complete?`本次没有生成${e(categoryLabel(state.category))}事项，不表示该类要求已通过。`:'此类检查结果尚未生成，请等待本次检查完成。'}</div>`:'<div class="empty-inline">没有匹配的事项。<br><button class="text-action" data-action="clear-filters">清空筛选</button></div>'}</section><section class="panel finding-detail">${selected?renderBusinessMembers(selectedGroup,selected)+renderFinding(selected,`<details class="report-source-disclosure" id="report-source-disclosure" ${disclosureAttributes('report-source-disclosure')}><summary>查看完整原文 <span>${e(document?.name||'本次报告文件')}</span></summary>${renderSourcePane(document,state.sourcePage||evidence?.page||1,'原文对照')}</details>`):'<div class="empty-inline">选择事项查看证据与分析。</div>'}</section></div>`:`<div class="panel empty-inline">${complete?'本次检查没有返回检查项。请检查启用的检查项后重新运行。':'检查完成后，结果会在此自动显示。'}</div>`}`;
}
function renderFinding(f, sourcePanel = '') {
  const cp=f.checkpoint,ev=f.evaluation,presentation=findingPresentation(f),business=businessPresentation(f),review=reviewOf(f),references=findingEvidence(f),evidences=references.bid,isConsistency=cp.type==='ENTITY_FIELD_CONSISTENCY';
  const evidenceMissing=findingDimensions(f).evidence.state!=='SUFFICIENT',analysis=business.reason||presentation.reason||'未提供分析说明。';
  return `<div class="finding-detail-header"><div class="detail-kicker">${e(findingCategory(f))} / ${e(types[cp.type]||cp.type)}</div><h2>${e(cp.title)}</h2><div class="detail-tags"><span class="pill ${e(business.state)}">${e(business.label)}</span>${renderResultScopeTags(f)}${review?badge(review.status,`人工：${statuses[review.status]||review.status}`):''}</div></div><div class="panel-body">${renderFindingDimensions(f)}<div class="result-reason ${e(business.state)}"><strong>检查结论</strong><p>${e(analysis)}</p></div><p class="suggestion result-next-step"><b>下一步</b><span>${e(business.nextStep)}</span></p>${renderTemplateSubchecks(f)}${renderCompleteness(f)}${originalReviewIsSourceRecord(f)?'':renderOriginalReview(f,presentation)}<div class="evidence-title"><span>${isConsistency?'多处信息与依据对照':'招标要求与投标证据'}</span><small>原文可追溯</small></div><div class="evidence-grid">${renderEvidence(references.source,'招标依据',cp.source?'未取得本次招标文件的有效依据。':'该项为手动或全文通用检查，无独立招标条款。')}${renderEvidence(evidences[0],isConsistency?'证据位置 1':'投标证据',evidenceMissing?'本项尚未取得足够原文依据；当前不据此认定投标材料缺失。':'未返回可展示的投标原文，请按证据引用继续追溯。')}</div>${references.tender.length?`<div class="more-evidence">${references.tender.map(item=>renderEvidence(item,'补充招标依据')).join('')}</div>`:''}${evidences.length>1?`<div class="more-evidence">${evidences.slice(1).map((item,i)=>renderEvidence(item,`证据位置 ${i+2}`)).join('')}</div>`:''}${sourcePanel}${renderInheritedReviews(f)}<details class="business-processing-details result-diagnostics"><summary>处理详情与诊断</summary><div class="result-diagnostics-body">${renderTemplateDiagnostics(f)}<p class="preview-caption">条款风险等级：${e(({HIGH:'高',MEDIUM:'中',LOW:'低'})[cp.severity]||'未设置')} · ${e(methodLabel(ev.method))}</p>${renderAtomicCheck(f)}${renderFieldCoverage(f)}${originalReviewIsSourceRecord(f)?renderOriginalReview(f,presentation):''}${renderOriginalExplanation(presentation)}${renderAssessmentDetails(f)}</div></details><details class="business-manual-details" ${disclosureAttributes(`manual-finding-${f.id}`,!!review||state.reviewDrafts.has(f.id))}><summary>记录人工复核${review?' · 已有记录':''}</summary>${renderManualReview(f)}</details></div>`;
}
function atomicCheckOf(finding) {
  const value = assessmentOf(finding)?.atomicCheck;
  return value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length ? { ...value, finalBusinessStatus: finding.evaluation?.status || value.finalBusinessStatus } : null;
}
function atomicWorkflow(check) {
  const presentation = atomicPresentation(check);
  return [presentation.label, presentation.state === 'PROCESSING_FAILED' ? 'error' : presentation.state === 'COMPLETED' ? 'complete' : 'pending'];
}
function atomicConditionLabel(status, role, display, predicate) {
  if (status === 'PASS' && predicate === 'REFERENCE_TIME') return '参考时点已确认';
  if (role === 'APPLICABILITY') return status === 'PASS' ? '适用判断：成立' : status === 'FAIL' ? '适用判断：不成立' : status === 'NOT_APPLICABLE' ? '适用判断：本分支未执行' : '适用判断：待确认';
  if (status === 'UNDETERMINED' && typeof display?.label === 'string') return display.label;
  const label = ({ PASS: '条件满足', FAIL: '条件不满足', UNDETERMINED: '待核实', NOT_APPLICABLE: '不适用', WARNING: '存在疑点' })[typeof status === 'string' ? status : ''];
  return typeof label === 'string' ? label : '需核实';
}
function atomicReadable(value, conditions = []) {
  let text = typeof value === 'string' ? value : '';
  for (const item of (Array.isArray(conditions) ? conditions : []).filter(item => typeof item?.id === 'string' && item.id && typeof item.title === 'string' && item.title).sort((a, b) => b.id.length - a.id.length)) {
    const id = item.id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    text = text.replace(new RegExp(`(^|[^A-Za-z0-9_])${id}(?![A-Za-z0-9_])`, 'g'), (_, prefix) => `${prefix}条件「${item.title}」`);
  }
  return text.replace(/(?<![A-Za-z0-9_])(?:rule|condition|r)[_-]\d+(?:[_-]\d+)*(?![A-Za-z0-9_])/gi, '本条件');
}
function atomicMappingPending(code) { return ['COMPARISON_FIELD_MAPPING_REQUIRED','COMPARISON_RECORD_IDENTITY_UNPROVEN','REGISTRATION_EMPLOYER_BINDING_REQUIRED'].includes(code); }
function atomicCategory(code) {
  if (typeof code !== 'string') return 'NEEDS_REVIEW';
  if (atomicMappingPending(code)) return 'RELATION_UNCONFIRMED';
  if (code.startsWith('MODEL_') || code.startsWith('HTTP_') || ['RULE_COMPILATION_UNAVAILABLE','FACT_EXTRACTION_UNAVAILABLE','TRANSPORT_FAILURE','REQUEST_TIMEOUT','CAPACITY_TIMEOUT','OUTPUT_JSON_INVALID','OUTPUT_SCHEMA_INVALID','OUTPUT_INCOMPLETE','OUTPUT_LIMIT','INPUT_LIMIT'].includes(code)) return 'PROCESSING_FAILED';
  if (code.includes('COMPILATION') || code.startsWith('REQUIREMENT_') || ['UNSUPPORTED_RULE','OPERATOR_UNSUPPORTED','SUBJECT_REQUIRED','ATOMIC_CONDITION_NOT_EVALUATED','PRICE_REQUIREMENT_DIMENSIONS_REQUIRED','PERSON_COUNT_ROLE_REQUIRED','PERSON_COUNT_COMPARISON_REQUIRED'].includes(code)) return 'REQUIREMENT_UNPARSED';
  if (/RELATION|IDENTITY|APPOINTMENT|EMPLOYER|SCOPE|DIMENSION|SELECTION|VERSION/.test(code) || ['REFERENCE_TIME_UNCONFIRMED','CERTIFICATE_CATEGORY_REQUIRED','VALIDITY_FIELD_REQUIRED','CONFLICTING_EVIDENCE','PRICE_SUBJECT_REQUIRED'].includes(code)) return 'RELATION_UNCONFIRMED';
  if (['CERTIFICATE_EVIDENCE_REQUIRED','REGISTRATION_CERTIFICATE_REQUIRED','FIELD_EVIDENCE_REQUIRED','PRICE_EVIDENCE_REQUIRED','COMPARISON_SOURCE_REQUIRED','MATERIAL_EVIDENCE_REQUIRED','DECLARATION_EVIDENCE_REQUIRED','CERTIFICATE_FIELD_REQUIRED','VALIDITY_RANGE_REQUIRED','CONTRACT_PROOF_REQUIRED','FULL_SECTION_REQUIRED','EXTERNAL_RECORD_REQUIRED','EVIDENCE_NOT_FOUND','NO_CANDIDATE_EVIDENCE'].includes(code)) return 'EVIDENCE_NOT_FOUND';
  return 'NEEDS_REVIEW';
}
function atomicPresentation(check, conditions = check?.conditions || []) {
  const labels = { PROCESSING_FAILED: '处理失败', REQUIREMENT_UNPARSED: '条款未解析完整', RELATION_UNCONFIRMED: '对应关系待确认', EVIDENCE_NOT_FOUND: '证据未找到', WAIT_STAGE: '等待适用时点', COMPLETED: '条件计算已结束', NEEDS_REVIEW: '尚未形成结论' };
  const reasons = { PROCESSING_FAILED: '本项处理未完成，已得到的条件和依据予以保留；不能据此判断材料缺失或不符合。', REQUIREMENT_UNPARSED: '尚未完整明确本条款的核验要求，暂不能据此判断投标是否符合。', RELATION_UNCONFIRMED: '要求对象与文件中的单位、人员、证件或报价口径尚未完整对应，需要结合原文确认。', EVIDENCE_NOT_FOUND: '本次检查尚未定位到足以支持判断的相关证据，不代表原文件缺少材料。', WAIT_STAGE: '本条件尚未到适用的核验时点。', COMPLETED: '条件处理已完成，整项结论及原图复核要求以上方结果为准。', NEEDS_REVIEW: '仍有需要人工确认的范围，请结合条件说明和原文核对。' };
  const actions = { PROCESSING_FAILED: '查看处理说明；系统需继续处理失败请求，本提示不要求补交材料，不计入人工核对待办。', REQUIREMENT_UNPARSED: '先对照招标原句明确要求对象、比较条件与适用范围。', RELATION_UNCONFIRMED: '核对相关原文中的人员、单位、证件及项目口径，确认它们是否对应同一要求。', EVIDENCE_NOT_FOUND: '先核对本次文件中的相关材料和定位范围，再确认是否确需补充材料。', WAIT_STAGE: '核对条款规定的核验时点，当前保留待核。' };
  const remap=(check?.gaps||[]).some(gap=>atomicMappingPending(gap?.code)||gap?.code==='REGISTRATION_CERTIFICATE_REQUIRED');
  const raw = !remap && check?.presentation && typeof check.presentation === 'object' ? check.presentation : {};
  const publishedPending = check?.finalBusinessStatus && !['PASS','FAIL','WARNING','NOT_APPLICABLE'].includes(check.finalBusinessStatus);
  const businessCompleted = !publishedPending && check?.complete === true && check?.workflowState === 'COMPLETED';
  const issues = Array.isArray(raw.issues) ? raw.issues.filter(item => item && typeof item === 'object') : (Array.isArray(check?.gaps) ? check.gaps : []).filter(item => item && typeof item === 'object').map(gap => { const category = atomicCategory(gap.code); return { code: gap.code, category, label: labels[category], description: atomicReadable(gap.description, conditions), nextStep: atomicMappingPending(gap.code)?'系统需继续完成证据字段或人员单位对应；当前不要求补交材料，也不计入人工核对待办。':actions[category] || '请结合条件说明和原文人工核对。' }; });
  if (publishedPending && check?.complete === true && check?.workflowState === 'COMPLETED') issues.push({category:'REQUIREMENT_UNPARSED',label:'整项要求尚未证明',description:'辅助条件的完成不足以证明整项要求；以守卫后的业务结论为准。',nextStep:'需补齐主条件及其证据，不能用参考日期或处理完成替代业务证明。'});
  const categories = issues.map(item => item.category);
  if (['ENGINE_FAILURE','ERROR'].includes(check?.workflowState) || Number(check?.modelDiagnostics?.unresolvedFailures) > 0) { categories.unshift('PROCESSING_FAILED'); if (!issues.some(issue => issue.category === 'PROCESSING_FAILED')) issues.push({category: 'PROCESSING_FAILED', label: businessCompleted ? '候选处理记录' : labels.PROCESSING_FAILED, description: businessCompleted ? '部分候选处理曾未完成；其他证据已支持当前完整结论，详见处理记录。' : reasons.PROCESSING_FAILED, nextStep: actions.PROCESSING_FAILED}); }
  const priority = ['PROCESSING_FAILED','REQUIREMENT_UNPARSED','RELATION_UNCONFIRMED','EVIDENCE_NOT_FOUND','NEEDS_REVIEW'];
  issues.sort((a, b) => priority.indexOf(a.category) - priority.indexOf(b.category));
  const inferred = ['PROCESSING_FAILED','REQUIREMENT_UNPARSED','RELATION_UNCONFIRMED','EVIDENCE_NOT_FOUND','NEEDS_REVIEW'].find(item => categories.includes(item)) || (check?.workflowState === 'WAIT_STAGE' ? 'WAIT_STAGE' : !publishedPending && (check?.complete === true && check?.workflowState === 'COMPLETED' || ['PASS','FAIL','NOT_APPLICABLE'].includes(check?.status)) ? 'COMPLETED' : 'NEEDS_REVIEW');
  const state = businessCompleted ? 'COMPLETED' : !publishedPending && typeof raw.state === 'string' && Object.hasOwn(labels, raw.state) ? raw.state : inferred;
  const next = businessCompleted ? [] : !publishedPending && Array.isArray(raw.nextSteps) ? raw.nextSteps.filter(value => typeof value === 'string' && value.trim()) : [...new Set(issues.map(item => item.nextStep).filter(value => typeof value === 'string' && value.trim()))];
  if (!next.length) next.push(actions[state] || '核对整项结论及引用原文；如有原图复核提示，先对照原图确认。');
  return { state, label: labels[state], reason: businessCompleted ? reasons.COMPLETED : publishedPending ? reasons[state] : typeof raw.reason === 'string' && raw.reason ? atomicReadable(raw.reason, conditions) : reasons[state], nextSteps: next.map(value => atomicReadable(value, conditions)), issues };
}
function renderAtomicRequirement(condition) {
  const source = condition.requirementContext, quote = typeof condition.sourceQuote === 'string' ? condition.sourceQuote : '';
  const valid = source && typeof source === 'object' && ['ORIGINAL_REQUIREMENT','REVIEWED_REQUIREMENT'].includes(source.sourceKind) && ['LOCAL','PARENT','CLAUSE'].includes(source.bindingKind) && typeof source.value === 'string' && source.value && typeof source.sourceQuote === 'string' && source.sourceQuote;
  if (!quote && !valid) return '';
  return `<details class="atomic-requirement"><summary>查看条件原句与要求对象</summary>${quote ? `<p>条件原句</p><blockquote class="evidence-quote">${e(quote)}</blockquote>` : ''}${valid ? `<p>条款要求对象：${e(source.value)}</p><p>${source.bindingKind === 'PARENT' ? `沿用上级条件：${e(source.parentTitle || '上级条件')}` : source.bindingKind === 'CLAUSE' ? '来源：本条款上下文' : '来源：本条件原文'}</p><blockquote class="evidence-quote">${e(source.sourceQuote)}</blockquote><p class="atomic-source-missing">仅说明招标要求对象的来源，不代表已确认投标人员、单位或证件归属。</p>` : ''}</details>`;
}
function atomicGapLines(gaps) {
  if (!Array.isArray(gaps)) return [];
  return [...new Set(gaps.filter(gap => gap && typeof gap === 'object' && !Array.isArray(gap)).map(gap => {
    const description = typeof gap.description === 'string' ? gap.description.trim() : '';
    const required = typeof gap.requiredInput === 'string' ? gap.requiredInput.trim() : '';
    return description && required && description !== required ? `${description}；需补充：${required}` : description || required || '待补信息尚未明确，请人工核实。';
  }))];
}
function renderAtomicEvidence(evidence, index) {
  if (!evidence || typeof evidence !== 'object' || Array.isArray(evidence)) return '';
  const run = state.run?.run || {}, documents = state.run?.documents || [];
  const document = documents.find(item => item.id === evidence.documentId && item.projectId === run.projectId);
  const bound = document && [run.documentId, run.tenderDocumentId].includes(evidence.documentId);
  if (bound && Number.isInteger(evidence.page) && evidence.page > 0) return renderEvidence(evidence, `原文依据 ${index + 1}`);
  return `<div class="atomic-unlocated-source"><strong>原文依据 ${index + 1}</strong>${typeof evidence.quote === 'string' && evidence.quote ? `<blockquote class="evidence-quote">${e(evidence.quote)}</blockquote>` : ''}<p>原文位置未完整记录，请核对本次文件。</p></div>`;
}
function renderAtomicCheck(finding) {
  const check = atomicCheckOf(finding); if (!check) return '';
  const conditions = Array.isArray(check.conditions) ? check.conditions.filter(item => item && typeof item === 'object' && !Array.isArray(item)) : [];
  const [workflow, tone] = atomicWorkflow(check), display = atomicPresentation(check);
  const domains = [...new Set((Array.isArray(check.domains) ? check.domains : []).filter(domain => typeof domain === 'string').map(domain => ({ PERSONNEL_CONSORTIUM: '人员与联合体', CERTIFICATE_VALIDITY: '证书有效期', PRICE_BASIS: '报价口径' })[domain]).filter(label => typeof label === 'string'))];
  const summary = check.summary && typeof check.summary === 'object' && !Array.isArray(check.summary) ? check.summary : {};
  const referenceCount = conditions.filter(c=>c.predicate==='REFERENCE_TIME').length;
  const businessConditions = conditions.filter(c=>c.predicate!=='REFERENCE_TIME'&&c.conditionRole==='REQUIREMENT');
  const counts = referenceCount ? [`参考时点 ${referenceCount} 项（辅助信息）`,businessConditions.length?`业务条件 ${businessConditions.length} 项`:'业务主条件尚未完整记录'] : [['passed', '满足'], ['failed', '不满足'], ['pending', '待核实'], ['notApplicable', '不适用']].filter(([key]) => countValue(summary, key) != null).map(([key, label]) => `${label} ${countValue(summary, key)} 项`);
  const next = display.nextSteps.slice(0, 2);
  const pending = items => `<ul class="atomic-gap-list">${items.map(line => `<li>${e(line)}</li>`).join('')}</ul>`;
  return `<section class="atomic-check" data-atomic-check><div class="atomic-check-heading"><h3>条件核验${domains.length ? `<small>${domains.map(e).join(' · ')}</small>` : ''}</h3><span class="atomic-workflow ${tone}">${e(workflow)}</span></div><div class="atomic-next-step"><strong>下一步</strong>${pending(next)}${display.issues.length ? `<details><summary>查看处理说明 · ${display.issues.length} 项</summary><ul class="atomic-gap-list">${display.issues.map(issue => `<li><strong>${e(typeof issue.label === 'string' ? issue.label : '待确认')}</strong> · ${e(atomicReadable(issue.description, conditions))}</li>`).join('')}</ul></details>` : ''}</div><details class="atomic-conditions" ${disclosureAttributes(`atomic-conditions-${finding.id}`)}><summary>查看 ${conditions.length} 个条件与依据</summary>${counts.length ? `<p class="atomic-condition-counts">${counts.map(e).join(' · ')}</p>` : ''}${conditions.length ? `<ol>${conditions.map((condition, index) => {
    const status = condition.conditionRole === 'APPLICABILITY' ? 'NOT_APPLICABLE' : ['PASS', 'FAIL', 'UNDETERMINED', 'NOT_APPLICABLE', 'WARNING'].includes(condition.status) ? condition.status : 'UNDETERMINED';
    const title = typeof condition.title === 'string' && condition.title.trim() ? atomicReadable(condition.title, conditions) : `条件 ${index + 1}`, detail = atomicPresentation(condition, conditions);
    const sources = Array.isArray(condition.evidences) ? condition.evidences.filter(item => item && typeof item === 'object' && !Array.isArray(item)) : [];
    const reason = condition.status === 'UNDETERMINED' ? detail.reason : atomicReadable(condition.reason, conditions);
    return `<li><details class="atomic-condition"><summary><span>${e(title)}</span>${badge(status, atomicConditionLabel(condition.status, condition.conditionRole, detail, condition.predicate))}</summary><div class="atomic-condition-body"><p>${e(reason || '此条件的判断说明未完整记录，请核对原文。')}</p>${detail.issues.length ? `<div class="atomic-condition-gaps"><strong>待处理事项</strong>${pending(detail.issues.map(issue => atomicReadable(issue.description, conditions)))}</div>` : ''}${renderAtomicRequirement(condition)}${sources.length ? `<div class="atomic-evidence-list">${sources.map(renderAtomicEvidence).join('')}</div>` : `<p class="atomic-source-missing">${['PROCESSING_FAILED','REQUIREMENT_UNPARSED'].includes(detail.state) ? '本条件处理尚未完成，未形成可定位的事实依据；不能据此判断材料缺失。' : '未记录可定位的事实依据，请先核对本次文件。'}</p>`}</div></details></li>`;
  }).join('')}</ol>` : '<p class="atomic-source-missing">条件清单尚未完整记录，请人工核实。</p>'}<p class="atomic-condition-boundary">条件是否需要同时满足，以招标条款为准。分项结果不替代整项检查结论、原图复核或人工决定。</p></details></section>`;
}
function renderManualReview(f, title = '人工复核') {
  const ev = f.evaluation, review = reviewOf(f), draft = state.reviewDrafts.get(f.id);
  return `<div class="manual-section"><div class="manual-heading"><h3>${icon('user', true)}${e(title)}</h3><span>复核保留自动结论与历史记录</span></div>${review ? `<div class="review-summary"><strong>${e(review.reviewer || '未署名复核人')}</strong> · ${e(date(review.createdAt))} · ${e(statuses[review.status] || review.status)}${review.note ? `<p>${e(review.note)}</p>` : ''}<button class="text-action" data-action="review-history" data-id="${e(f.id)}">查看全部复核记录</button></div>` : ''}<form class="review-form" id="review-form" data-result="${e(f.id)}"><div class="review-fields"><div><label class="field-label" for="review-status">复核结论</label><select id="review-status" name="status">${options(statuses, draft?.status || review?.status || ev.status)}</select></div><div><label class="field-label" for="reviewer">复核人</label><input id="reviewer" name="reviewer" maxlength="80" placeholder="请输入姓名" value="${e(draft?.reviewer ?? review?.reviewer ?? '')}" required></div></div><label class="field-label" for="review-note">复核说明</label><textarea id="review-note" name="note" maxlength="3000" placeholder="记录判断依据、补充说明或需整改事项" required>${e(draft?.note || '')}</textarea><div class="review-submit-row"><p>提交后可再次复核，所有记录均保留。</p><button class="button button-primary button-small" type="submit" ${state.busy.has(`review-${f.id}`) ? 'disabled' : ''}>${icon('check', true)}保存复核</button></div></form></div>`;
}
function renderEvidence(ev, title, empty = '暂无原文证据。') {
  if (!ev) return `<article class="evidence-card"><div class="evidence-card-header"><strong>${icon('document', true)}${e(title)}</strong></div><div class="evidence-card-body"><p class="evidence-empty">${e(empty)}</p></div></article>`;
  return `<article class="evidence-card"><div class="evidence-card-header"><strong>${icon('document', true)}${e(title)}</strong><span>${e(locationLabel(ev))}</span></div><div class="evidence-card-body"><p class="evidence-document-name">${e(ev.documentName || '来源文档')}</p><blockquote class="evidence-quote">${e(ev.quote || '未提供引用文本，请打开原文查看。')}</blockquote><div class="evidence-location">${icon('pin', true)}<span>${e(ev.anchor || locationLabel(ev))}</span><button class="text-action" data-action="page-preview" data-id="${e(ev.documentId)}" data-page="${Number(ev.page) || 1}">查看原文 ${icon('external', true)}</button></div></div></article>`;
}

function profileItems(items) {
  return items?.length ? items.map(item => `<div class="profile-item"><span>${e(item.label)}</span><strong>${e(item.value || '未提取')}</strong>${item.evidence ? `<button class="text-action" data-action="locate-profile-source" data-id="${e(item.evidence.documentId)}" data-page="${Number(item.evidence.page) || 1}">${icon('pin', true)}${e(locationLabel(item.evidence))}</button>` : ''}</div>`).join('') : '<div class="empty-inline">未提取到相关信息，请人工核对。</div>';
}
function profileContent(profile) {
  return `${profile.warnings?.length ? `<div class="inline-warning">${profile.warnings.map(e).join('<br>')}</div>` : ''}${profile.groups?.length ? profile.groups.map(g => `<section class="panel profile-group"><div class="panel-header"><h2>${icon('list')}${e(g.title)}</h2><span class="pill">${g.records?.length ? `${g.records.length} 个项目` : `${g.items?.length || 0} 条`}</span></div>${g.records?.length ? `<div class="performance-records">${g.records.map((record, index) => `<article class="performance-record"><h3><span>${index + 1}</span>${e(record.title || `待确认业绩 ${index + 1}`)}</h3><div class="profile-items">${profileItems(record.items)}</div></article>`).join('')}</div>` : `<div class="profile-items">${profileItems(g.items)}</div>`}</section>`).join('') : '<div class="panel empty-inline">当前文件未提取到核心信息。请结合原文检查，避免将未识别的内容判定为缺失。</div>'}`;
}
function renderProfile() {
  const document = docs().find(d => d.id === state.profileDoc && d.role === 'BID') || selectedDoc('BID');
  const profile = document && state.profiles.get(document.id), error = document && state.profileErrors.get(document.id);
  return `<div class="checkpoint-toolbar"><div><h2 class="section-title">上传投标文件并预览核心信息</h2><p>按投标人、业绩和其他信息分类预览；点击位置可核对原文。业绩按项目分别展示。</p></div><button class="button button-secondary" data-action="tab" data-tab="checkpoints">返回检查项</button></div><div class="bid-preparation ${document ? 'has-bid' : ''}"><section class="panel"><div class="panel-header"><h2>${icon('upload')}投标文件</h2><span class="pill">上传新文件可替换本次检查版本</span></div><div class="panel-body">${document ? `<div class="bid-current-file">${icon('document', true)}<strong>${e(document.name)}</strong><span class="pill PASS">V${Number(document.version)} · 解析成功</span><p>上传新版本后可重新检查，每次报告独立保留。</p></div><details class="bid-upload-options" ${state.busy.has('upload-BID') || state.uploadErrors.has(`${projectId()}:BID`) || activeTasks(projectId()).some(t => t.role === 'BID') ? 'open' : ''}><summary>${icon('upload', true)}上传 / 替换文件，或查看历史版本</summary>${renderDocumentBox('BID')}</details>` : renderDocumentBox('BID')}</div></section>${renderRunPanel()}</div><div id="task-region">${renderTasks()}</div>${document ? `<div class="section-toolbar"><h2 class="section-title">当前预览：${e(document.name)}</h2><select id="profile-document" aria-label="选择投标信息预览版本" style="max-width:330px">${[...docs()].filter(d => d.role === 'BID').reverse().map(d => `<option value="${e(d.id)}" ${d.id === document.id ? 'selected' : ''}>V${Number(d.version)} · ${e(d.name)}</option>`).join('')}</select></div><div class="profile-layout">${renderSourcePane(document, state.sourcePage, '投标原文')}<div class="stack">${profile ? profileContent(profile) : error ? `<div class="error-panel"><h2>核心信息暂时无法加载</h2><p>${e(error)}</p><button class="button button-secondary button-small" data-action="retry-profile" data-id="${e(document.id)}">重新加载</button></div>` : '<div class="panel empty-inline"><span class="spinner"></span> 正在提取核心信息…</div>'}</div></div>` : '<div class="panel empty-inline">上传投标文件后，将在这里展示投标人信息和各个业绩项目。</div>'}`;
}

async function loadProject(id, { showLoading = false, selectLatest = false } = {}) {
  const token = ++state.request;
  if (showLoading) { ++state.runRequest; state.project = null; state.run = null; state.selectedRunId = null; state.loading = true; state.page = 'project'; state.error = ''; render(); }
  let detail;
  try { detail = await api(`/api/projects/${enc(id)}?includeContent=false`); }
  catch (error) { if (token !== state.request) return; state.loading = false; state.error = error.message; render(); return; }
  if (token !== state.request) return;
  if (state.project?.project?.activeTenderId !== detail.project.activeTenderId) { state.sourceDoc = null; state.sourcePage = 1; }
  if (detail.requirementMapping?.status === 'READY') {
    try { detail.requirementMapping = await api(`/api/projects/${enc(id)}/requirement-mapping`); }
    catch { /* The compact project summary remains usable when optional detail loading fails. */ }
  }
  if (token !== state.request) return;
  state.project = detail; state.page = 'project'; state.loading = false; state.error = '';
  for (const task of detail.tasks || []) {
    const previous = state.tasks.get(task.id);
    const label = previous?.label || ({ PARSE: '解析项目文件', EXTRACT: '提取候选检查项', CHECK: '检查投标文件' }[task.type || task.taskType]) || task.stage || '项目处理任务';
    state.tasks.set(task.id, { ...previous, ...task, projectId: id, label });
    if (['PENDING', 'RUNNING', 'QUEUED'].includes(task.status)) pollTask(task.id, id, label);
  }
  const currentRunId = !selectLatest && detail.runs?.some(r => r.id === state.selectedRunId) ? state.selectedRunId : detail.runs?.at(-1)?.id;
  if (currentRunId) await loadRun(currentRunId, false); else { state.run = null; state.incrementalCandidates = []; state.incrementalCandidateError = ''; }
  if (token !== state.request) return;
  render();
  const running = detail.runs?.find(r => !terminal.has(r.status));
  if (running) pollRun(running.id, id);
}
async function loadRun(id, paint = true) {
  const request = ++state.runRequest, expectedProject = projectId();
  state.selectedRunId = id;
  let detail, candidates = [], candidateError = '';
  try {
    [detail, candidates] = await Promise.all([
      api(`/api/runs/${enc(id)}?includeContent=false`),
      api(`/api/runs/${enc(id)}/dependency-recalculations`).catch(error => { candidateError = error.message || '增量候选状态加载失败。'; return []; })
    ]);
  }
  catch (error) { if (request !== state.runRequest || expectedProject !== projectId()) return; throw error; }
  if (request !== state.runRequest || expectedProject !== projectId()) return;
  state.run = detail;
  if (state.resultVersionRunId !== id) {
    state.resultVersionRunId = id;
    state.resultVersionView = detail?.publishedResultVersion?.id ? 'current' : 'source';
  } else if (state.resultVersionView === 'current' && !detail?.publishedResultVersion?.id) state.resultVersionView = 'source';
  state.incrementalCandidates = Array.isArray(candidates) ? candidates : [];
  state.incrementalCandidateError = candidateError;
  scheduleResolutionRefresh(detail, expectedProject);
  if (paint) render();
}
async function refreshProject() { const id = projectId(); if (id) await loadProject(id); }
const sleep = ms => new Promise(resolve => window.setTimeout(resolve, ms));
const pollingTasks = new Set(), pollingRuns = new Set();
const resolutionRefreshes = new Set();
let resolutionRefreshTimer = null;
function updateProjectRun(run, pid) {
  if (projectId() !== pid) return false;
  const index = state.project.runs.findIndex(item => item.id === run.id);
  if (index < 0) return false;
  const changed = state.project.runs[index].status !== run.status;
  state.project.runs[index] = run;
  return changed;
}
function scheduleResolutionRefresh(detail, pid) {
  if (projectId() !== pid || state.selectedRunId !== detail.run.id) return;
  const retryTimes = terminal.has(detail.run.status) && detail.resolutionSummary?.canRecover === false
    ? (detail.results || []).map(resolutionJobOf).filter(job => job?.status === 'FAILED')
      .map(job => Number(job.retryNotBeforeEpochMillis)).filter(time => Number.isFinite(time) && time > 0) : [];
  const retryAt = retryTimes.length ? Math.min(...retryTimes) : 0;
  const key = retryAt ? `${pid}:${detail.run.id}:${retryAt}` : '';
  if (resolutionRefreshTimer?.key === key) return;
  if (resolutionRefreshTimer) window.clearTimeout(resolutionRefreshTimer.timer);
  resolutionRefreshTimer = null;
  if (!key || resolutionRefreshes.has(key)) return;
  const id = detail.run.id;
  // Read availability once after this cooldown. This never starts a check or retries a model call.
  const timer = window.setTimeout(async () => {
    resolutionRefreshTimer = null;
    if (projectId() !== pid || state.selectedRunId !== id || !terminal.has(state.run?.run.status)) return;
    resolutionRefreshes.add(key);
    const request = state.runRequest;
    try {
      const latest = await api(`/api/runs/${enc(id)}?includeContent=false`);
      if (projectId() !== pid || state.selectedRunId !== id || request !== state.runRequest) return;
      updateProjectRun(latest.run, pid); state.run = latest; render();
    } catch (error) {
      if (projectId() === pid && state.selectedRunId === id) toast(`补查状态刷新未完成：${error.message}可刷新项目重试。`, true);
    }
  }, Math.min(2147483647, Math.max(250, retryAt - Date.now() + 250)));
  resolutionRefreshTimer = {key, timer};
}
async function pollTask(id, pid, label) {
  if (pollingTasks.has(id)) return;
  pollingTasks.add(id);
  try {
    while (true) {
      const task = await api(`/api/tasks/${enc(id)}`);
      state.tasks.set(id, { ...state.tasks.get(id), ...task, projectId: pid, label });
      if (projectId() === pid) { const region = document.getElementById('task-region'); if (region) { rememberDisclosureState(region); region.innerHTML = renderTasks(); } }
      if (terminal.has(task.status)) {
        if (failed.has(task.status)) toast(`${label}失败：${task.error || '请重试或检查文件。'}`, true); else toast(`${label}完成`);
        const parsedId = !failed.has(task.status) && task.type === 'PARSE' ? task.documentId : null;
        if (parsedId) {
          if ((task.role || state.tasks.get(id)?.role) === 'TENDER') state.tenderSelection.set(pid, parsedId);
          // A version may have been opened before parsing completed; discard only that version's cached result.
          if (state.documentLoads.has(parsedId)) await state.documentLoads.get(parsedId).catch(() => {});
          state.documentCache.delete(parsedId); state.documentErrors.delete(parsedId);
          state.profiles.delete(parsedId); state.profileErrors.delete(parsedId);
        }
        if (projectId() === pid) {
          await loadProject(pid);
          if (projectId() === pid && parsedId) {
            const document = docs().find(item => item.id === parsedId);
            if (document) state.profileDoc = parsedId;
            if (document?.role === 'TENDER') state.tenderSelection.set(pid, parsedId);
            if (document?.role === 'BID') state.runDocumentId = parsedId;
            if (document && state.tab === 'profile') {
              state.sourceDoc = null; state.sourcePage = 1;
              render();
              try { await loadProfile(parsedId); } catch (error) { toast(error.message, true); }
            }
            if (projectId() === pid) render();
          }
        }
        break;
      }
      await sleep(1100);
    }
  } catch (error) {
    const existing = state.tasks.get(id) || {};
    state.tasks.set(id, { ...existing, projectId: pid, label, status: 'CONNECTION_LOST', error: error.message });
    toast(`${label}状态更新中断：${error.message}刷新项目可查看最新状态。`, true);
    if (projectId() === pid) render();
  } finally { pollingTasks.delete(id); }
}
async function pollRun(id, pid) {
  if (pollingRuns.has(id)) return;
  pollingRuns.add(id);
  try {
    while (true) {
      const detail = await api(`/api/runs/${enc(id)}?includeContent=false`);
      if (projectId() === pid) {
        const statusChanged = updateProjectRun(detail.run, pid), selected = state.selectedRunId === id;
        if (selected) { state.run = detail; scheduleResolutionRefresh(detail, pid); }
        if (selected && state.tab === 'results' || statusChanged || terminal.has(detail.run.status)) render();
      }
      if (terminal.has(detail.run.status)) {
        if (failed.has(detail.run.status)) toast(`检查失败：${detail.run.error || '请查看任务详情。'}`, true); else toast('检查已完成，可以查看逐项证据与分析。');
        break;
      }
      await sleep(1200);
    }
  } catch (error) { toast(`检查状态更新中断：${error.message}刷新项目可重新连接。`, true); }
  finally { pollingRuns.delete(id); }
}
async function upload(file, role) {
  if (!file || state.busy.has(`upload-${role}`)) return;
  const limitMb = maxFileSizeMb(), max = limitMb * 1024 * 1024;
  if (!uploadExtensions().includes(file.name.split('.').at(-1).toLowerCase())) { toast(`暂不支持该文件类型。请上传 ${formatLabel()} 文件。`, true); return; }
  if (file.size > max) { toast(`文件大小 ${(file.size / (1024 * 1024)).toFixed(1)} MB，超过当前 ${limitMb} MB 上限，请压缩后重试。`, true); return; }
  if (file.size === 0) { toast('不能上传空文件，请选择包含内容的文件。', true); return; }
  const pid = projectId(); if (!pid) return;
  if (role === 'BID' && !checksReady()) { toast('请先核对并确认招标检查项，再上传投标文件。', true); return; }
  if (docs().length >= maxDocuments()) { toast(`当前项目已达到 ${maxDocuments()} 份文件上限，请新建项目继续。`, true); return; }
  if (processing()) { toast('请等待当前文件处理完成后再上传。', true); return; }
  state.uploadErrors.delete(`${pid}:${role}`);
  state.busy.add(`upload-${role}`); render();
  try {
    const body = new FormData(); body.append('file', file); body.append('role', role);
    const task = await api(`/api/projects/${enc(pid)}/documents`, { method: 'POST', body });
    state.tasks.set(task.id, { ...task, projectId: pid, label: `解析 ${file.name}`, role, fileName: file.name });
    pollTask(task.id, pid, `解析 ${file.name}`);
    toast('文件已上传，已提交后台解析。可在“处理任务”查看页码和处理进度。');
  } catch (error) { state.uploadErrors.set(`${pid}:${role}`, error.message); toast(`上传失败，项目已保留：${error.message}`, true); }
  finally { state.busy.delete(`upload-${role}`); if (pid === projectId()) render(); }
}
function bindDragZones() {
  app.querySelectorAll('.dropzone').forEach(zone => {
    zone.addEventListener('dragover', event => { event.preventDefault(); zone.classList.add('dragover'); });
    zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
    zone.addEventListener('drop', event => { event.preventDefault(); zone.classList.remove('dragover'); const files = event.dataTransfer.files; if (files.length > 1) toast('一次上传一份文件。已选择第一份文件。'); upload(files[0], zone.dataset.role); });
    zone.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); zone.querySelector('input').click(); } });
  });
}
function newProject() {
  openModal('新建检查项目', '以项目为单位管理招标要求、投标文件和检查记录。', `<form id="new-project-form"><div class="modal-body"><div class="field"><label for="project-name">项目名称 <span aria-hidden="true">*</span></label><input id="project-name" name="name" maxlength="160" placeholder="例如：园区物业服务采购项目" required autofocus></div><div class="field"><label for="project-code">项目编号</label><input id="project-code" name="code" maxlength="100" placeholder="可选，填写招标或内部项目编号"></div><div class="modal-note">创建后可上传招标文件，提取检查要求并人工确认。</div></div><div class="modal-footer"><button class="button button-secondary" type="button" data-action="close-modal">取消</button><button class="button button-primary" type="submit">${icon('plus', true)}创建项目</button></div></form>`);
}

function templateParameterValue(parameters, name, definition) {
  return Object.prototype.hasOwnProperty.call(parameters || {}, name) ? parameters[name] : definition.defaultValue;
}
function renderTemplateParameterControl(name, definition, value) {
  const label = templateParameterLabels[name] || name, required = definition.required ? 'required' : '', inputId = `spec-param-${name}`;
  if (definition.type === 'boolean') return `<label class="template-boolean"><input id="${e(inputId)}" type="checkbox" data-template-parameter="${e(name)}" data-parameter-type="boolean" ${value === true || value === 'true' ? 'checked' : ''}><span>${e(label)}</span></label>`;
  if (definition.type === 'string-array') {
    const text = Array.isArray(value) ? value.join('\n') : value || '';
    return `<div class="field"><label for="${e(inputId)}">${e(label)}${definition.required ? ' *' : ''}</label><textarea id="${e(inputId)}" rows="3" data-template-parameter="${e(name)}" data-parameter-type="string-array" ${required} placeholder="每行填写一项">${e(text)}</textarea></div>`;
  }
  if (Array.isArray(definition.allowedValues) && definition.allowedValues.length) {
    return `<div class="field"><label for="${e(inputId)}">${e(label)}${definition.required ? ' *' : ''}</label><select id="${e(inputId)}" data-template-parameter="${e(name)}" data-parameter-type="${e(definition.type)}" ${required}>${definition.required ? '' : '<option value="">未设置</option>'}${definition.allowedValues.map(option => `<option value="${e(option)}" ${String(value ?? '') === String(option) ? 'selected' : ''}>${e(operatorLabel(option) || option)}</option>`).join('')}</select></div>`;
  }
  const type = definition.type === 'date' ? 'date' : ['integer', 'decimal'].includes(definition.type) ? 'number' : 'text';
  const step = definition.type === 'integer' ? ' step="1"' : definition.type === 'decimal' ? ' step="any"' : '';
  return `<div class="field"><label for="${e(inputId)}">${e(label)}${definition.required ? ' *' : ''}</label><input id="${e(inputId)}" type="${type}"${step} data-template-parameter="${e(name)}" data-parameter-type="${e(definition.type)}" value="${e(value ?? '')}" ${required}></div>`;
}
function renderTemplateParameterFields(metadata, parameters = {}) {
  if (!metadata) return '<p class="modal-note">选择核验模板后，系统只展示该模板需要的业务参数。</p>';
  const entries = Object.entries(metadata.parameters || {}), required = entries.filter(([, definition]) => definition.required), optional = entries.filter(([, definition]) => !definition.required);
  const controls = list => `<div class="template-parameter-grid">${list.map(([name, definition]) => renderTemplateParameterControl(name, definition, templateParameterValue(parameters, name, definition))).join('')}</div>`;
  return `${required.length ? controls(required) : '<p class="modal-note">此模板没有必填业务参数。</p>'}${optional.length ? `<details class="template-optional"><summary>可选参数</summary>${controls(optional)}</details>` : ''}`;
}
function renderTemplateEditor(checkpoint, spec) {
  if (!state.templateCatalog.length) return '';
  const selectedId = spec?.templateId || '', metadata = templateMetadata(selectedId, spec?.templateVersion), parameters = spec?.parameters || {};
  const requiredVerificationMode = metadata?.requiredVerificationMode || spec?.verificationMode || 'DOCUMENT';
  const coverage = spec?.sourceCoverage || {}, coverageState = coverage.state === 'LEGACY_DRAFT' ? 'PARTIAL' : coverage.state || 'PARTIAL', coverageOptions = { FULL: '来源完整', PARTIAL: '部分来源，仍需补充', UNSUPPORTED: '当前模板不支持' };
  if (coverageState && !Object.hasOwn(coverageOptions, coverageState)) coverageOptions[coverageState] = sourceCoverageLabels[coverageState] || coverageState;
  const refs = checkpointRequirementRefs(checkpoint, spec?.requirementRefs);
  return `<section class="template-editor"><div class="template-editor-heading"><div><h3>核验模板</h3><p>把检查要求转换成固定模板和业务参数。页面不会展示内部实现或模型提示。</p></div>${spec ? badge(spec.confirmed ? 'PASS' : 'WARNING', spec.confirmed ? '已发布' : '草稿') : badge('NOT_APPLICABLE', '未配置')}</div>${spec && !spec.confirmed ? '<div class="template-draft-note">草稿确认前仍走兼容检查路线，不会静默切换运行方式。</div>' : ''}${spec?.confirmed ? '<div class="template-confirmed-note">修改已发布配置会生成新草稿，需要重新确认模板检查集后才会用于新检查。</div>' : ''}<div class="form-grid template-main-fields"><div class="field"><label for="cp-template">核验模板</label><select id="cp-template" name="specTemplateId">${spec ? '' : '<option value="">沿用兼容检查</option>'}${state.templateCatalog.map(item => `<option value="${e(item.id)}" ${item.id === selectedId ? 'selected' : ''}>${e(item.title)} · V${e(item.version)}</option>`).join('')}</select></div><div class="field"><label>模板版本</label><input id="cp-template-version" value="${e(metadata?.version || '')}" readonly placeholder="选择模板后自动确定"></div><div class="field"><label for="cp-purpose">检查目的</label><select id="cp-purpose" name="specPurpose">${options(purposeLabels, spec?.purpose || 'MANDATORY')}</select></div><div class="field"><label for="cp-verification-mode">核验方式</label><select id="cp-verification-mode" disabled aria-describedby="cp-verification-mode-note">${options(verificationModeLabels, requiredVerificationMode)}</select><input id="cp-verification-mode-value" type="hidden" name="specVerificationMode" value="${e(requiredVerificationMode)}"><small id="cp-verification-mode-note">由所选模板固定，防止把外部核验或专业判断误设为文件自动核验。</small></div></div><div id="template-parameter-fields" data-template-id="${e(selectedId)}">${renderTemplateParameterFields(metadata, parameters)}</div><div class="template-source-coverage"><div class="field"><label for="cp-source-coverage">招标来源覆盖</label><select id="cp-source-coverage" name="specCoverageState">${options(coverageOptions, coverageState)}</select></div><div class="field"><label for="cp-source-note">来源说明</label><input id="cp-source-note" name="specCoverageNote" value="${e(coverage.reason || coverage.note || '')}" maxlength="500" placeholder="例如：已覆盖资格条件正文，附件格式待补"></div><span>${refs.length} 处来源</span></div><details class="template-advanced"><summary>来源与适用范围</summary><p>适用对象：${selectedId === 'PERSONNEL_ROSTER' ? '当前项目拟投入人员' : '当前项目投标人'}。来源位置会随规格版本保存，便于之后追溯。</p>${refs.length ? `<ul>${refs.slice(0, 5).map(ref => `<li>${e(ref.locationType === 'PAGE' ? `第 ${ref.location} 页` : `位置 ${ref.location}`)} · ${e(String(ref.quote || '').slice(0, 100) || '未摘录原文')}</li>`).join('')}</ul>` : '<p>当前没有可用来源，保存前请确认招标文件与检查要求。</p>'}</details><input type="hidden" name="specExpectedRevision" value="${Number(spec?.revision) || 0}"></section>`;
}
function templateParametersFromForm(form, metadata) {
  const parameters = {};
  for (const [name, definition] of Object.entries(metadata?.parameters || {})) {
    const control = form.querySelector(`[data-template-parameter="${name}"]`); if (!control) continue;
    if (definition.type === 'boolean') { parameters[name] = control.checked; continue; }
    const raw = String(control.value || '').trim(); if (!raw && !definition.required) continue;
    if (definition.type === 'string-array') parameters[name] = raw.split(/[\n;；]+/).map(value => value.trim()).filter(Boolean);
    else if (definition.type === 'integer') parameters[name] = Number.parseInt(raw, 10);
    else parameters[name] = raw;
  }
  return parameters;
}
async function saveCheckpointSpec(form, checkpoint, { prepareOnly = false } = {}) {
  const templateId = String(new FormData(form).get('specTemplateId') || '').trim(), existing = checkpointSpec(checkpoint.id);
  if (!templateId) return prepareOnly ? null : existing;
  const metadata = templateMetadata(templateId); if (!metadata) throw new Error('所选核验模板已不可用，请刷新页面后重试。');
  const values = new FormData(form), parameters = templateParametersFromForm(form, metadata), refs = checkpointRequirementRefs(checkpoint, existing?.requirementRefs);
  const verificationMode = metadata.requiredVerificationMode || 'DOCUMENT';
  if (!refs.length) throw new Error('当前检查项缺少招标来源，暂时不能保存模板规格。');
  const coverageState = String(values.get('specCoverageState') || 'PARTIAL'), coverageNote = String(values.get('specCoverageNote') || '').trim();
  const subjectSelector = templateId === 'PERSONNEL_ROSTER'
    ? { entityType: 'PERSON', role: String(parameters.role || ''), scope: 'CURRENT_PROJECT', constraints: {} }
    : { entityType: 'ORGANIZATION', role: 'BIDDER', scope: 'CURRENT_PROJECT', constraints: {} };
  const sameJson = (left, right) => JSON.stringify(left ?? null) === JSON.stringify(right ?? null);
  if (existing && existing.templateId === templateId && existing.templateVersion === metadata.version && existing.category === specCategory(checkpoint)
      && existing.purpose === String(values.get('specPurpose') || 'MANDATORY') && existing.verificationMode === verificationMode
      && existing.proofStandard === metadata.proofStandard && sameJson(existing.parameters, parameters)
      && (existing.sourceCoverage?.state || 'PARTIAL') === coverageState && String(existing.sourceCoverage?.reason || existing.sourceCoverage?.note || '') === coverageNote
      && sameJson(existing.subjectSelector, subjectSelector)) return prepareOnly ? null : existing;
  const body = {
    expectedRevision: Number(values.get('specExpectedRevision')) || 0, businessItemId: existing?.businessItemId || checkpoint.id, category: specCategory(checkpoint),
    purpose: String(values.get('specPurpose') || 'MANDATORY'), verificationMode, templateId, templateVersion: metadata.version,
    proofStandard: metadata.proofStandard, subjectSelector, parameters, applicability: existing?.applicability || { state: 'APPLIES', reason: '', sources: [] }, requirementRefs: refs,
    parameterBindings: Object.entries(parameters).map(([parameter, value]) => ({ parameter, value, origin: 'MANUAL', reason: '用户在检查项模板配置中确认', sources: refs })),
    requiredFields: existing?.requiredFields || {}, evidenceContract: existing?.evidenceContract || {}, sourceCoverage: { ...existing?.sourceCoverage, state: coverageState, reason: coverageNote, requirementRefCount: refs.length },
  };
  if (prepareOnly) return body;
  try {
    const saved = await api(`/api/checkpoints/${enc(checkpoint.id)}/spec`, { method: 'PUT', body });
    state.checkpointSpecs.set(checkpointSpecKey(checkpoint.id), saved); state.checkpointSpecErrors.delete(checkpointSpecKey(checkpoint.id)); return saved;
  } catch (error) {
    if (error.status === 409) { await loadCheckpointSpec(checkpoint.id, { force: true, quiet: true }); throw new Error(`${error.message}已刷新到最新模板配置，请关闭窗口后重新修改。`); }
    throw error;
  }
}

async function editCheckpoint(id, category = '响应检查') {
  const cp = id ? checkpoints().find(item => item.id === id) : { title: '', category: groupNames[category] ? category : '响应检查', severity: 'MEDIUM', type: 'SEMANTIC_RESPONSE', field: 'manualRequirement', operator: 'RESPONDS', expected: '', unit: '', required: true, enabled: true, confirmed: false };
  if (!cp) return;
  const spec = id ? await loadCheckpointSpec(id, { quiet: true }) : null;
  openModal(id ? '修改检查项' : '新增检查项', '保存到当前项目，用于之后创建的检查。', `<form id="checkpoint-form" data-id="${e(id || '')}"><div class="modal-body"><div class="field"><label for="cp-title">检查项名称</label><input id="cp-title" name="title" value="${e(cp.title)}" maxlength="200" required autofocus></div><div class="field"><label for="cp-expected">检查要求</label><textarea id="cp-expected" name="expected" maxlength="4000" rows="5" placeholder="填写需要提供的材料、响应标准或核对要求">${e(cp.expected)}</textarea><small>数值或日期规则请填写对应要求值，并在下方核对检查方式。全文一致性项可留空。</small></div><div class="form-grid"><div class="field"><label for="cp-category">检查分类</label><select id="cp-category" name="category">${group(cp)==='UNKNOWN'?options({...groupNames,[cp.category||'UNKNOWN']:'未分类'},cp.category||'UNKNOWN'):options(groupNames,group(cp))}</select></div><div class="field"><label for="cp-severity">风险等级</label><select id="cp-severity" name="severity">${options(severities, cp.severity)}</select></div></div>${id ? renderTemplateEditor(cp, spec) : '<div class="modal-note">先保存检查项，再配置固定核验模板与业务参数。</div>'}<details class="checkpoint-advanced"><summary>兼容检查方式与数值规则</summary><div class="form-grid"><div class="field"><label for="cp-type">检查方式</label><select id="cp-type" name="type">${options(types, cp.type)}</select></div><div class="field"><label for="cp-field">检查字段</label><select id="cp-field" name="field">${options({ ...fields, ...(fields[cp.field] ? {} : { [cp.field]: cp.field }) }, cp.field)}</select></div><div class="field"><label for="cp-operator">比较方式</label><input id="cp-operator" name="operator" value="${e(cp.operator)}" maxlength="40" placeholder="如 >=、<=、=、RESPONDS"></div><div class="field"><label for="cp-unit">单位 / 年度范围</label><input id="cp-unit" name="unit" value="${e(cp.unit)}" maxlength="100" placeholder="DAY、MONTH、CNY 或 YEAR:2023,2024"></div></div></details><div class="field"><label class="checkbox-label"><input type="checkbox" name="required" ${cp.required ? 'checked' : ''}>必需项</label>　<label class="checkbox-label"><input type="checkbox" name="enabled" ${cp.enabled ? 'checked' : ''}>启用检查</label>　<label class="checkbox-label"><input type="checkbox" name="confirmed" ${cp.confirmed ? 'checked' : ''}>已核对并确认</label></div><div class="modal-note">${state.config.semanticEnabled ? (rulesRetrievalConfigured() ? '先核对规则与候选证据，复杂疑点按需调用模型；证据不足保留待核实。' : '自定义语义要求将结合模型和原文检查。') : '语义服务尚未配置，自定义语义要求将标记为“待核实”。'}请结合引用证据复核。</div>${cp.source ? `<blockquote class="evidence-quote">${e(locationLabel(cp.source))} · ${e(cp.source.quote)}</blockquote>` : `<p class="checkpoint-rule">${cp.type === 'FULL_TEXT_REVIEW' ? '系统内置检查，核对全文信息，无独立招标来源。' : '手动或通用检查项，无独立招标原文来源。'}</p>`}</div><div class="modal-footer"><button class="button button-secondary" type="button" data-action="close-modal">取消</button><button class="button button-primary" type="submit">保存检查项${id && spec?.confirmed ? '并创建修订' : ''}</button></div></form>`);
}
async function saveProjectInformation() {
  const pid = projectId(), changes = state.projectDrafts.get(pid);
  if (!changes || !Object.keys(changes).length) return;
  if (state.busy.has('project-info')) throw new Error('基础信息正在保存，请稍候。');
  const submitted = { ...changes };
  if ('name' in submitted && !submitted.name.trim()) throw new Error('请填写项目名称。');
  state.busy.add('project-info');
  try {
    await api(`/api/projects/${enc(pid)}`, { method: 'PUT', body: submitted });
    const remaining = { ...(state.projectDrafts.get(pid) || {}) };
    for (const key of Object.keys(submitted)) if (remaining[key] === submitted[key]) delete remaining[key];
    if (Object.keys(remaining).length) state.projectDrafts.set(pid, remaining); else state.projectDrafts.delete(pid);
    if (pid === projectId()) await loadProject(pid);
    toast('项目基础信息已保存，修改字段已标记。');
  } finally { state.busy.delete('project-info'); if (pid === projectId()) render(); }
}
async function updateCheckpoint(cp, changes) {
  const pid = projectId();
  state.busy.add(`checkpoint-${cp.id}`);
  try {
    const updated = await api(`/api/checkpoints/${enc(cp.id)}`, { method: 'PUT', body: { ...cp, ...changes } });
    if (pid === projectId()) { const i = state.project.checkpoints.findIndex(c => c.id === cp.id); if (i >= 0) state.project.checkpoints[i] = updated; }
    return updated;
  } finally { state.busy.delete(`checkpoint-${cp.id}`); }
}
async function confirmAll() {
  const pending = checkpoints().filter(cp => !cp.confirmed); if (!pending.length) return;
  state.confirmation = { projectId: projectId(), tenderId: state.project.project.activeTenderId, checkpoints: pending.map(cp => ({ ...cp })) };
  openModal('确认候选检查项', '请先对照招标原文核对提取结果。', `<div class="modal-body"><p class="modal-note">即将确认 ${pending.length} 项候选要求。其中 ${pending.filter(cp => cp.enabled).length} 项已启用，将用于之后的检查；已停用的项目保持停用。确认意味着你已核对检查要求、数值、运算符及单位。</p></div>`, `<div class="modal-footer"><button class="button button-secondary" data-action="close-modal">返回核对</button><button class="button button-primary" data-action="confirm-all-submit">已核对，确认这些检查项</button></div>`);
}
async function prepareTemplateSetConfirmation() {
  if (state.busy.has('confirm-template-set')) return;
  const pid = projectId(), tender = currentTender(), enabled = checkpoints().filter(checkpoint => checkpoint.enabled);
  if (!pid || !tender || !enabled.length) { toast('当前没有可加入模板检查集的启用项。', true); return; }
  state.busy.add('confirm-template-set'); render();
  try {
    const loaded = await mapLimited(enabled, 6, checkpoint => loadCheckpointSpec(checkpoint.id, { quiet: true }));
    if (pid !== projectId() || tender.id !== currentTender()?.id) throw new Error('项目或招标版本已变化，请重新操作。');
    const ready = enabled.map((checkpoint, index) => ({ checkpoint, spec: loaded[index] })).filter(item => item.spec?.validation?.valid !== false && item.spec?.id);
    const invalid = enabled.map((checkpoint, index) => ({ checkpoint, spec: loaded[index] })).filter(item => item.spec?.validation?.valid === false);
    const legacy = enabled.filter((checkpoint, index) => !loaded[index]);
    if (!ready.length) throw new Error('当前启用项中没有可确认的模板草稿。请先打开检查项并配置核验模板。');
    const operation = {
      projectId: pid, tenderId: tender.id, ready, invalid, legacy, setId: randomKey(`ui-${tender.id}`).replace(/[^A-Za-z0-9._:-]/g, ''),
      idempotencyKey: randomKey('confirm'), draftRevision: null,
    };
    state.templateSetConfirmation = operation;
    openModal('确认模板检查集', '发布后，新检查会使用已确认的模板版本。', `<div class="modal-body template-set-review"><div class="template-set-counts"><div><strong>${ready.length}</strong><span>可确认模板</span></div><div><strong>${legacy.length}</strong><span>继续兼容检查</span></div><div><strong>${invalid.length}</strong><span>需完善草稿</span></div></div><p class="modal-note">本次只发布已启用且参数有效的模板。没有模板或参数未完成的检查项不会丢失，仍保留原检查路线。</p><details><summary>查看本次模板</summary><ul>${ready.map(({ checkpoint, spec }) => `<li><strong>${e(checkpoint.title)}</strong><span>${e(templateTitle(spec))} · V${e(spec.templateVersion)} · ${e(purposeLabels[spec.purpose] || spec.purpose)}</span></li>`).join('')}</ul></details>${legacy.length || invalid.length ? `<details><summary>查看未切换项目</summary><ul>${legacy.map(checkpoint => `<li><strong>${e(checkpoint.title)}</strong><span>尚未配置模板</span></li>`).join('')}${invalid.map(({ checkpoint }) => `<li><strong>${e(checkpoint.title)}</strong><span>模板参数或来源不完整</span></li>`).join('')}</ul></details>` : ''}</div>`, `<div class="modal-footer"><button class="button button-secondary" data-action="close-modal">返回核对</button><button class="button button-primary" data-action="confirm-template-set-submit">确认并发布 ${ready.length} 项</button></div>`);
  } finally { state.busy.delete('confirm-template-set'); if (pid === projectId()) render(); }
}
function sameSpecIds(set, operation) {
  const expected = operation.ready.map(item => item.spec.id).sort(), actual = Array.isArray(set?.specIds) ? [...set.specIds].sort() : [];
  return expected.length === actual.length && expected.every((value, index) => value === actual[index]);
}
async function submitTemplateSetConfirmation(button) {
  const operation = state.templateSetConfirmation;
  if (!operation || operation.projectId !== projectId() || operation.tenderId !== currentTender()?.id) throw new Error('项目或招标版本已变化，请重新生成模板检查集。');
  if (state.busy.has('confirm-template-set-submit')) return;
  state.busy.add('confirm-template-set-submit'); button.disabled = true;
  const setPath = `/api/projects/${enc(operation.projectId)}/check-sets/${enc(operation.setId)}`;
  try {
    if (operation.draftRevision == null) {
      try {
        const draft = await api(setPath, { method: 'PUT', body: {
          expectedRevision: 0, tenderDocumentId: operation.tenderId, specIds: operation.ready.map(item => item.spec.id),
          sourceCoverage: { templateSpecs: operation.ready.length, compatibilityItems: operation.legacy.length, invalidDrafts: operation.invalid.length },
          unsupportedRequirements: operation.invalid.map(item => ({ checkpointId: item.checkpoint.id, title: item.checkpoint.title, reason: '模板参数或来源不完整' })),
          legacyMappings: operation.legacy.map(checkpoint => ({ checkpointId: checkpoint.id, title: checkpoint.title, route: 'LEGACY' })),
        } });
        operation.draftRevision = Number(draft.revision);
      } catch (error) {
        if (error.status !== 409) throw error;
        const current = await api(setPath).catch(() => null);
        if (!current || current.state !== 'DRAFT' || !sameSpecIds(current, operation)) throw error;
        operation.draftRevision = Number(current.revision);
      }
    }
    let confirmed;
    try {
      confirmed = await api(`${setPath}/confirm`, { method: 'POST', body: {
        expectedRevision: operation.draftRevision, idempotencyKey: operation.idempotencyKey, confirmedBy: '项目检查用户', revisionReason: '确认当前启用检查项的模板与业务参数',
      } });
    } catch (error) {
      if (error.status !== 409) throw error;
      const current = await api(setPath).catch(() => null);
      if (current?.state === 'CONFIRMED' && sameSpecIds(current, operation)) confirmed = current;
      else {
        await mapLimited(operation.ready, 6, item => loadCheckpointSpec(item.checkpoint.id, { force: true, quiet: true }));
        state.templateSetConfirmation = null;
        throw new Error(`${error.message}已刷新模板状态，请核对后重新确认。`);
      }
    }
    for (const spec of confirmed?.specs || []) if (spec.checkpointId) state.checkpointSpecs.set(checkpointSpecKey(spec.checkpointId), spec);
    if (state.project?.project && confirmed?.id && Number.isInteger(Number(confirmed.manifestRevision))) {
      state.project.project.activeCheckSetId = confirmed.id;
      state.project.project.activeCheckSetManifestRevision = Number(confirmed.manifestRevision);
      state.project.project.activeCheckSetTenderId = confirmed.tenderDocumentId;
    }
    state.templateSetConfirmation = null; modal.close(); render(); toast(`已发布 ${operation.ready.length} 项模板检查，新检查将冻结使用本次版本。`);
  } finally { state.busy.delete('confirm-template-set-submit'); button.disabled = false; }
}
function showCapabilities() {
  openModal('检查能力与边界', '清楚知道哪些已核验，哪些仍需要你的判断。', `<div class="modal-body"><div class="capability-list">${textOnlyConfigured() ? `<div class="capability-item">${icon('document')}<div><h3>文字识别与全文检查</h3><p>${e(textProcessingBoundary)}表格语法可解析不代表行列归属正确；有布局提示时仍需核对原页。</p></div></div>` : ''}<div class="capability-item">${icon('check')}<div><h3>${e(rulesRetrievalConfigured() ? '全文规则与按需核验' : state.config.semanticEnabled ? '规则 + 通义千问' : '本地规则检查')}</h3><p>${rulesRetrievalConfigured() ? e(rulesRetrievalBoundary) : ''}支持可识别文本中的数值要求、项目与主体信息一致性、明确材料证明、证件日期，以及通过车架号或发动机号关联的车辆信息一致性。</p></div></div><div class="capability-item">${icon('document')}<div><h3>原文定位与文件版本</h3><p>PDF 使用真实页码和原始页面预览；Word 使用段落或内容块位置，表格与嵌图也按内容块定位；这些位置不是 PDF 页码。上传图片可对照原图与识别文字。重新识别会生成新版本，检查新版本需重新提取确认招标要求或重新启动投标检查，旧报告不被覆盖。上传版本、文件校验值、结论和复核历史分别保留。${state.config.storageMode === 'minio' ? '当前原文件存储于 MinIO，并保留本机缓存。' : '当前原文件保存在本机数据目录。'}</p></div></div><div class="capability-item">${icon('alert')}<div><h3>需要人工判断的内容</h3><p>${ocrCapability()}${state.config.semanticEnabled ? '已配置通义千问语义服务，候选原文会发送至已配置的模型服务，以提供语义响应要求的辅助分析；请逐项核对模型引用的原文证据。' : '语义服务尚未配置，语义响应与评分要求需人工核实。'}${retrievalCapability(true)}业务结论与原图复核分别显示，证据充分的文字判断可以符合；原图复核提示仍须处理。OCR 识别文字不等于验证签字、印章或证照真伪。识别不完整、超过图像上限或无法读取的相关内容需人工核对。字段提取基于有限文本规则，不能替代完整审阅。</p></div></div><div class="capability-item">${icon('user')}<div><h3>人工复核与报告</h3><p>复核可更新最终展示结论，自动原结论始终保留。报告包含原文证据、检查方法与复核记录，支持 HTML、CSV、JSON 格式。</p></div></div></div></div>`, '<div class="modal-footer"><button class="button button-primary" data-action="close-modal">知道了</button></div>');
}
async function showPreview(id, pageNumber = 1) {
  let document = [...docs(), ...(state.run?.documents || [])].find(d => d.id === id);
  if (!document) { toast('当前报告没有该来源文档，请刷新项目后重试。', true); return; }
  const pid = projectId();
  if (!cachedDocument(id)) {
    openModal(document.name, '正在加载原文', '<div class="modal-body"><span class="spinner"></span> 正在加载文件内容…</div>', '', 'preview-modal');
    const version = state.modalVersion;
    try { document = await loadDocument(id); }
    catch (error) {
      if (version === state.modalVersion && modal.open && pid === projectId()) openModal(document.name, '原文加载失败', `<div class="modal-body"><p>${e(error.message)}</p><button class="button button-secondary" data-action="document-preview" data-id="${e(id)}" data-page="${Number(pageNumber) || 1}">重新加载</button><a class="text-action" href="/api/documents/${enc(id)}/raw" download>下载原文件</a></div>`, '', 'preview-modal');
      return;
    }
    if (version !== state.modalVersion || !modal.open || pid !== projectId()) return;
  } else document = cachedDocument(id);
  const page = document.pages?.find(p => p.number === Number(pageNumber)) || document.pages?.[0];
  openModal(document.name, `版本 V${Number(document.version)} · ${page ? locationLabel({ page: page.number, locationType: page.locationType }) : '原文件'}`, `<div class="modal-body"><p class="preview-caption">${e(pageCaption(page))}<a href="/api/documents/${enc(id)}/raw" download style="margin-left:10px">下载原文件</a></p>${renderPageContent(document, page, true)}${document.pages?.length > 1 ? `<div class="preview-navigation"><label for="modal-page-select">原文位置</label>${positionInput(document, page, 'modal-page-select')}</div>` : ''}</div>`, '', 'preview-modal');
  const image = modal.querySelector('#modal-preview-image');
  image?.addEventListener('error', () => { const warning = window.document.createElement('div'); warning.className = 'preview-error'; warning.textContent = '无法加载原始页面，请下载原文件复核。'; image.replaceWith(warning); }, { once: true });
}
async function loadProfile(id) {
  if (state.profiles.has(id)) return state.profiles.get(id);
  state.profileErrors.delete(id);
  try { const profile = await api(`/api/documents/${enc(id)}/profile`); state.profiles.set(id, profile); while (state.profiles.size > 4) state.profiles.delete(state.profiles.keys().next().value); return profile; }
  catch (error) { state.profileErrors.set(id, error.message); throw error; }
}

async function switchTab(tab) {
  if (!['documents', 'checkpoints', 'profile', 'results'].includes(tab)) return;
  if (tab === 'checkpoints' && !usableTender()) { toast('请先上传并成功解析有效的招标文件。', true); return; }
  if (tab === 'profile' && !checksReady()) { toast('请先核对并确认当前招标的检查项。', true); return; }
  if (state.tab === 'documents' && tab !== 'documents') await saveProjectInformation();
  state.tab = tab; state.sourceDoc = null; state.sourcePage = 1; render(); window.scrollTo({ top: 0, behavior: 'auto' });
  if (tab === 'results' && state.project?.runs?.length && !state.run) await loadRun(state.project.runs.at(-1).id);
  if (tab === 'profile') {
    const doc = docs().find(d => d.id === state.profileDoc && d.role === 'BID') || selectedDoc('BID');
    if (doc) { state.profileDoc = doc.id; state.runDocumentId = doc.id; try { await loadProfile(doc.id); } catch (error) { toast(error.message, true); } finally { if (state.tab === 'profile') render(); } }
  }
}
async function downloadReport(format) {
  if (!state.run || !['html', 'csv', 'json'].includes(format)) return;
  try {
    const runId = state.run.run.id;
    const resultVersion = state.resultVersionView === 'current' && publishedRunVersion() ? 'current' : 'source';
    const response = await fetch(`/api/runs/${enc(runId)}/report?format=${format}&resultVersion=${resultVersion}`);
    if (!response.ok) { let message = '报告下载失败，请重试。'; try { message = (await response.json()).message || message; } catch { /* Preserve default message. */ } throw new Error(message); }
    const blob = await response.blob(), url = URL.createObjectURL(blob), anchor = document.createElement('a');
    anchor.href = url; anchor.download = `BidCheck-${runId}.${format}`; document.body.append(anchor); anchor.click(); anchor.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 5000);
    toast(`${format.toUpperCase()} ${resultVersion === 'current' ? '当前发布版本' : '原始检查'}报告已生成。`);
  } catch (error) { toast(error.message, true); }
}
const evidenceNeedKindLabels = { MATERIAL: '材料正文', SEAL: '签章位置', CERTIFICATE: '证书字段', PRICE: '报价或数值', PERSONNEL: '人员信息', ROSTER_SCOPE: '人员清单范围' };
async function openIncrementalRecalculation() {
  const runId = state.run?.run?.id; if (!runId) return;
  const graph = await api(`/api/runs/${enc(runId)}/dependency-graph`);
  if (state.run?.run?.id !== runId) return;
  const reverse = graph?.reverseNeedIndex && typeof graph.reverseNeedIndex === 'object' ? graph.reverseNeedIndex : {};
  const keys = Object.keys(reverse).filter(key => key && Array.isArray(reverse[key]) && reverse[key].length).sort();
  if (!Number.isInteger(Number(graph?.revision)) || Number(graph.revision) < 1 || !keys.length) throw new Error('当前报告没有可增量重算的模板证据依赖，请重新执行完整检查。');
  const nodes = Array.isArray(graph.nodes) ? graph.nodes : [], nodeByKey = new Map();
  for (const node of nodes) if (node?.type === 'EVIDENCE_NEED' && node.attributes?.needKey) nodeByKey.set(node.attributes.needKey, node);
  const resultTitles = new Map((state.run.results || []).map(finding => [finding?.checkpoint?.id, finding?.checkpoint?.title]).filter(([id]) => id));
  const latest = [...state.incrementalCandidates].reverse().find(record => Array.isArray(record.changedNeedKeys));
  const suggested = new Set((latest?.changedNeedKeys || []).filter(key => keys.includes(key)));
  state.incrementalRecalculationContext = { runId, revision: Number(graph.revision), allowedNeedKeys: new Set(keys) };
  const rows = keys.map(key => {
    const node = nodeByKey.get(key), kind = evidenceNeedKindLabels[node?.attributes?.kind] || '检查证据';
    const titles = reverse[key].map(id => resultTitles.get(id)).filter(Boolean);
    const affected = titles.length ? titles.slice(0, 2).join('、') + (titles.length > 2 ? `等 ${titles.length} 项` : '') : `影响 ${reverse[key].length} 个检查项`;
    return `<label class="incremental-need-option"><input type="checkbox" name="changedNeedKeys" value="${e(key)}" ${suggested.has(key) ? 'checked' : ''}><span><strong>${e(kind)}</strong><small>${e(affected)}</small></span></label>`;
  }).join('');
  openModal('根据新增证据生成候选', '只选择本次确实新增、替换或重新识别的证据范围。系统先计算受影响检查项，再生成候选结论。', `<form id="incremental-recalculation-form" class="modal-body" data-run="${e(runId)}" data-revision="${Number(graph.revision)}"><div class="incremental-need-list">${rows}</div><p class="modal-note">可选项来自本次检查冻结的依赖图，不能在此新增或修改证据键。原始报告不会被改写。</p><div class="modal-footer"><button type="button" class="button button-secondary" data-action="close-modal">取消</button><button type="submit" class="button button-primary">计算影响并生成候选</button></div></form>`, '', 'incremental-recalculation-modal');
}
function openIncrementalDecision(record, decision) {
  if (!record || !['APPROVE', 'REJECT'].includes(decision) || incrementalPublicationState(record) !== 'CANDIDATE_ONLY') return;
  const changed = incrementalChangedResults(record), approve = decision === 'APPROVE', revision = Number(record.decisionRevision || 0);
  const rows = changed.map(item => `<li><strong>${e(item.candidateFinding?.checkpoint?.title || item.checkpointId || '检查项')}</strong><span>${e(statuses[item.beforeFinding?.evaluation?.status] || item.beforeFinding?.evaluation?.status || '原结论')} → ${e(statuses[item.candidateFinding?.evaluation?.status] || item.candidateFinding?.evaluation?.status || '候选结论')}</span></li>`).join('');
  openModal(approve ? '审核并发布增量结论' : '驳回增量结论候选', approve ? '批准后生成新的不可变结果版本，旧检查报告保持不变。' : '驳回只记录本次审核，不改变旧检查报告。', `<form id="incremental-decision-form" class="modal-body incremental-decision-form" data-run="${e(state.run?.run?.id || '')}" data-record="${e(record.id)}" data-decision="${decision}" data-revision="${revision}" data-source-digest="${e(record.sourceResultDigest || '')}" data-evidence-digest="${e(record.evidenceSnapshotDigest || '')}"><div class="incremental-decision-summary"><strong>${changed.length} 项结论变化</strong><ul>${rows || '<li><span>没有需要发布的结论变化。</span></li>'}</ul></div><div class="review-fields"><div><label class="field-label" for="incremental-reviewer">审核人</label><input id="incremental-reviewer" name="reviewer" maxlength="80" required placeholder="请输入姓名"></div></div><label class="field-label" for="incremental-note">审核说明</label><textarea id="incremental-note" name="note" maxlength="3000" required placeholder="说明批准或驳回的依据"></textarea><p class="modal-note">系统会再次校验证据快照、来源结果和依赖图版本；数据已变化时不会发布旧候选。</p><div class="modal-footer"><button type="button" class="button button-secondary" data-action="close-modal">取消</button><button type="submit" class="button ${approve ? 'button-primary' : 'button-danger'}" ${changed.length ? '' : 'disabled'}>${approve ? '确认发布新版本' : '确认驳回'}</button></div></form>`, '', 'incremental-decision-modal');
}
async function showIncrementalResultVersion(versionId) {
  const runId = state.run?.run?.id; if (!runId || !versionId) return;
  const version = await api(`/api/runs/${enc(runId)}/result-versions/${enc(versionId)}`);
  if (state.run?.run?.id !== runId) return;
  const rows = Array.isArray(version.results) ? version.results : Array.isArray(version.changedResults) ? version.changedResults : [];
  const items = rows.map(item => { const finding = item.candidateFinding || item.finding || item; return `<li><strong>${e(finding.checkpoint?.title || item.checkpointId || '检查项')}</strong><span>${e(statuses[finding.evaluation?.status] || finding.evaluation?.status || '已发布')}</span></li>`; }).join('');
  openModal('增量结果版本', `发布时间：${date(version.publishedAt || version.createdAt || version.decidedAt)}`, `<div class="modal-body incremental-version-detail"><p>该版本由增量候选审核发布，来源检查报告保持不变。</p><dl><div><dt>审核人</dt><dd>${e(version.publishedBy || '未记录')}</dd></div><div><dt>审核说明</dt><dd>${e(version.publicationNote || '未记录')}</dd></div></dl>${items ? `<h3>发布的结论</h3><ul>${items}</ul>` : '<p>该版本未返回可展示的结论明细，请下载 JSON 报告核对。</p>'}</div>`, '<div class="modal-footer"><button class="button button-primary" data-action="close-modal">关闭</button></div>');
}

async function handleAction(action, el) {
  const id = el.dataset.id;
  if (action === 'close-modal') { ++state.modalVersion; return modal.close(); }
  if (action === 'capabilities') return showCapabilities();
  if (action === 'new-project') return newProject();
  if (action === 'home') { ++state.request; ++state.runRequest; state.page = 'projects'; state.project = null; state.run = null; state.selectedRunId = null; state.loading = false; state.error = ''; state.projects = await api('/api/projects'); render(); return; }
  if (action === 'reload') return init();
  if (action === 'open-project') { state.tab = 'documents'; state.sourceDoc = null; state.sourcePage = 1; state.profileDoc = null; state.runDocumentId = null; state.selectedCheckpoint = null; state.checkpointGroup = 'ALL'; state.category='ALL'; state.filter='ALL'; state.purposeFilter='ALL'; state.externalOnly=false; state.resultQuery=''; state.reportView = 'checks'; state.selectedConsistencyGroup = null; state.consistencyFilter = 'ALL'; state.consistencyField = 'ALL'; state.consistencyCategory = 'ALL'; state.consistencyRelation = 'ALL'; state.consistencyReview = 'ALL'; state.consistencyQuery = ''; state.consistencyFactsPage = 1; state.consistencyFactsMode = 'ALL'; return loadProject(id, { showLoading: true, selectLatest: true }); }
  if (action === 'refresh-project') { await refreshProject(); toast('项目已刷新。'); return; }
  if (action === 'refresh-incremental-candidates') { const runId = state.run?.run?.id; if (runId) await loadRun(runId); return; }
  if (action === 'create-incremental-candidate') { await openIncrementalRecalculation(); return; }
  if (action === 'incremental-decision') {
    const record = state.incrementalCandidates.find(item => item.id === id);
    if (!record) { toast('候选记录已变化，请刷新后重试。', true); return; }
    openIncrementalDecision(record, el.dataset.decision);
    return;
  }
  if (action === 'incremental-result-version') { await showIncrementalResultVersion(id); return; }
  if (action === 'refresh-requirement-mapping') {
    const pid = projectId(), tender = activeTender();
    if (!pid || !tender || state.busy.has('requirement-mapping')) return;
    state.busy.add('requirement-mapping'); render();
    try {
      const mapping = await api(`/api/projects/${enc(pid)}/requirement-mapping/refresh`, { method: 'POST', body: { documentId: tender.id } });
      if (pid === projectId()) state.project.requirementMapping = mapping;
      toast('条款模板覆盖已重新整理。');
    } finally { state.busy.delete('requirement-mapping'); if (pid === projectId()) render(); }
    return;
  }

  if (action === 'report-view') { if (!['checks','consistency'].includes(el.dataset.view)) return; state.reportView=el.dataset.view; state.category='全文信息检查'; render(); document.querySelector('.report-detail-switch')?.scrollIntoView({block:'start'}); return; }
  if (action === 'result-category') { state.category=el.dataset.category||'ALL'; state.reportView='checks'; state.filter='ALL'; state.resultQuery=''; state.selectedFinding=null; state.sourceDoc=null; state.sourcePage=0; render(); return; }
  if (action === 'result-purpose') { const purpose=el.dataset.purpose||'ALL'; if(!['ALL','MANDATORY','SCORING'].includes(purpose))return; state.purposeFilter=purpose; state.selectedFinding=null; state.sourceDoc=null; state.sourcePage=0; render(); return; }
  if (action === 'result-external') { state.externalOnly=!state.externalOnly; state.selectedFinding=null; state.sourceDoc=null; state.sourcePage=0; render(); return; }
  if (action === 'consistency-evidence-page') { state.consistencyEvidencePage = Math.max(1, Number(el.dataset.page) || 1); render(); document.querySelector('.consistency-detail .consistency-evidence-heading')?.scrollIntoView({ block: 'start' }); return; }
  if (action === 'consistency-facts-page') { state.consistencyFactsPage = Math.max(1, Number(el.dataset.page) || 1); setDisclosureOpen('consistency-facts', true); render(); document.querySelector('#consistency-facts')?.scrollIntoView({ block: 'start' }); return; }
  if (action === 'consistency-kind') { state.consistencyFilter = el.dataset.kind || 'ALL'; state.selectedConsistencyGroup = null; render(); return; }
  if (action === 'consistency-review-open') { const details = document.querySelector('.consistency-manual-details'); if (!details) return; details.open = true; const input = details.querySelector('[name=status]'); if (el.dataset.ignore === 'true' && input) { input.value = 'NOT_APPLICABLE'; rememberReviewDraft(input); } details.scrollIntoView({block:'center'}); details.querySelector(el.dataset.ignore === 'true' ? '[name=note]' : '[name=reviewer]')?.focus(); return; }
  if (action === 'select-consistency-group') { state.selectedConsistencyGroup = id; render(); if (window.matchMedia('(max-width: 800px)').matches) document.querySelector('.consistency-detail')?.scrollIntoView({ block: 'start' }); return; }
  if (action === 'clear-consistency-filters') { state.consistencyFilter = 'ALL'; state.consistencyField = 'ALL'; state.consistencyCategory = 'ALL'; state.consistencyRelation = 'ALL'; state.consistencyReview = 'ALL'; state.consistencyQuery = ''; render(); return; }
  if (action === 'consistency-category') { state.consistencyCategory = el.dataset.category || 'ALL'; state.consistencyFilter = 'ALL'; state.consistencyField = 'ALL'; state.consistencyRelation = 'ALL'; state.consistencyReview = 'ALL'; state.consistencyQuery = ''; render(); document.querySelector('.consistency-toolbar')?.scrollIntoView({ block: 'start' }); return; }
  if (action === 'tab') { if (el.dataset.tab === 'checkpoints' && usableTender() && !processing() && (state.project.project.activeTenderId !== currentTender()?.id || state.project.project.checkpointRulesOutdated || !checkpoints().length)) return handleAction('next-tender', el); return switchTab(el.dataset.tab); }
  if (action === 'next-tender') {
    if (!usableTender() || processing()) return;
    const pid = projectId(), tender = currentTender();
    await saveProjectInformation();
    if (projectId() !== pid) return;
    if (state.project.project.activeTenderId !== tender.id || state.project.project.checkpointRulesOutdated || !checkpoints().length) {
      state.busy.add(`extract-${tender.id}`);
      try {
        const task = await api(`/api/projects/${enc(pid)}/checkpoints/extract`, { method: 'POST', body: { documentId: tender.id } });
        state.tasks.set(task.id, { ...task, projectId: pid, label: '提取候选检查项' });
        pollTask(task.id, pid, '提取候选检查项');
      } finally { state.busy.delete(`extract-${tender.id}`); }
    }
    return switchTab('checkpoints');
  }
  if (action === 'checkpoint-group') { state.checkpointGroup=el.dataset.group; state.selectedCheckpoint=null; render(); return; }
  if (action === 'select-checkpoint') {
    const cp = checkpoints().find(item => item.id === id); if (!cp) return;
    state.selectedCheckpoint = id; state.sourceDoc = cp.source?.documentId || null; state.sourcePage = cp.source?.page || 1; render();
    await loadCheckpointSpec(id, { quiet: true }); render();
    if (window.matchMedia('(max-width: 1040px)').matches) document.querySelector('.selected-requirement')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    return;
  }
  if (action === 'load-checkpoint-spec') { await loadCheckpointSpec(id, { force: true, quiet: true }); render(); return; }
  if (action === 'add-checkpoint') return editCheckpoint(null, el.dataset.group || state.checkpointGroup);
  if (action === 'delete-checkpoint') {
    const cp = checkpoints().find(item => item.id === id); if (!cp) return;
    return openModal('删除检查项', '删除后不再参与之后的检查。', `<div class="modal-body"><strong>${e(cp.title)}</strong><p class="modal-note">已有报告与复核记录仍可查看。</p></div>`, `<div class="modal-footer"><button class="button button-secondary" data-action="close-modal">取消</button><button class="button button-danger" data-action="delete-checkpoint-submit" data-id="${e(id)}">删除此项</button></div>`);
  }
  if (action === 'delete-checkpoint-submit') {
    const pid = projectId(); el.disabled = true;
    try { await api(`/api/checkpoints/${enc(id)}`, { method: 'DELETE' }); if (pid === projectId()) { state.project.checkpoints = checkpoints().filter(cp => cp.id !== id); if (state.selectedCheckpoint === id) state.selectedCheckpoint = null; } modal.close(); render(); toast('检查项已删除。'); }
    finally { el.disabled = false; }
    return;
  }
  if (action === 'locate-profile-source') {
    if (modal.open) return showPreview(id, Number(el.dataset.page) || 1);
    state.profileDoc = id; state.sourceDoc = id; state.sourcePage = Number(el.dataset.page) || 1; render();
    if (window.matchMedia('(max-width: 1040px)').matches) document.querySelector('.profile-layout .source-pane')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    return;
  }

  if (action === 'demo') {
    if (state.busy.has('demo')) return;
    state.busy.add('demo'); render();
    try { const project = await api('/api/demo', { method: 'POST' }); state.projects = await api('/api/projects'); state.tab = 'results'; await loadProject(project.id, { showLoading: true, selectLatest: true }); toast('示例项目已就绪。示例文件与信息均为演示用途。'); }
    finally { state.busy.delete('demo'); render(); } return;
  }
  if (action === 'extract') {
    const pid = projectId(); state.tenderSelection.set(pid, id); state.busy.add(`extract-${id}`); render();
    try { const task = await api(`/api/projects/${enc(pid)}/checkpoints/extract`, { method: 'POST', body: { documentId: id } }); state.tasks.set(task.id, { ...task, projectId: pid, label: '提取候选检查项' }); pollTask(task.id, pid, '提取候选检查项'); toast('正在从招标文件提取候选检查项。'); }
    finally { state.busy.delete(`extract-${id}`); render(); } return;
  }
  if (action === 'ocr-document' || action === 'ocr-plan-document' || action === 'ocr-continue-document') {
    const planned = action === 'ocr-plan-document', continuing = action === 'ocr-continue-document';
    if (!state.config.ocrEnabled) { toast('OCR 尚未启用，暂时不能重新识别。请核对原图或启用 OCR 后重试。', true); return; }
    const document = docs().find(item => item.id === id), pid = projectId();
    if (!document || !ocrEligible(document) || planned && document.role !== 'BID' || state.busy.has(`ocr-${id}`)) return;
    if (activeTasks(pid).length) { toast('请等待当前文档处理任务完成后再重新识别。', true); return; }
    state.busy.add(`ocr-${id}`); render();
    try {
      const task = await api(`/api/documents/${enc(id)}/${continuing ? 'ocr-continue' : planned ? 'ocr-plan' : 'ocr'}`, { method: 'POST', body: {} });
      const label = `${continuing ? '继续识别未完成位置' : planned ? '按检查项识别' : '全量重新识别'} ${document.name}`;
      state.tasks.set(task.id, { ...task, projectId: pid, label, ocrSourceId: id });
      pollTask(task.id, pid, label);
      toast(continuing ? '已提交继续识别：校验并复用成功结果，按本轮预算处理剩余位置；结果保存为新版本。' : planned ? '已按当前已确认检查项提交识别，将生成新版本；计划外图像保留待按需补识别。' : '已提交 OCR 全量重新识别，将生成新版本；旧文件与旧报告保持不变。');
    } finally { state.busy.delete(`ocr-${id}`); if (pid === projectId()) render(); }
    return;
  }
  if (action === 'confirm-all') return confirmAll();
  if (action === 'confirm-template-set') return prepareTemplateSetConfirmation();
  if (action === 'confirm-template-set-submit') return submitTemplateSetConfirmation(el);
  if (action === 'confirm-all-submit') {
    if (state.busy.has('confirm-all')) return;
    const confirmation = state.confirmation;
    if (!confirmation || confirmation.projectId !== projectId() || confirmation.tenderId !== state.project.project.activeTenderId) throw new Error('招标版本已变化，请重新核对当前检查项后确认。');
    state.busy.add('confirm-all'); el.disabled = true; let count = 0;
    try { for (const cp of confirmation.checkpoints) {
      if (confirmation.projectId !== projectId() || confirmation.tenderId !== state.project.project.activeTenderId) throw new Error('项目或招标版本已变化，请重新核对。');
      const current = checkpoints().find(item => item.id === cp.id);
      if (!current || JSON.stringify(current) !== JSON.stringify(cp)) throw new Error('候选检查项已变化，请关闭对话框后重新核对。');
      await updateCheckpoint(cp, { confirmed: true }); count++;
    } state.confirmation = null; modal.close(); toast(`已确认 ${count} 项检查要求。`); }
    catch (error) { modalError(new Error(`已确认 ${count} 项，其余未完成：${error.message}`)); }
    finally { state.busy.delete('confirm-all'); el.disabled = false; render(); } return;
  }
  if (action === 'confirm-checkpoint') { const cp = checkpoints().find(c => c.id === id); if (!cp) return; el.disabled = true; try { await updateCheckpoint(cp, { confirmed: true }); toast('检查项已确认。'); } finally { render(); } return; }
  if (action === 'edit-checkpoint') return editCheckpoint(id);
  if (action === 'recover-run') {
    if(state.busy.has('run')||state.run?.resolutionSummary?.canRecover!==true)return;
    const pid=projectId(),parentRunId=state.run.run.id;
    state.busy.add('run');render();
    try {
      const response=await api(`/api/projects/${enc(pid)}/runs/${enc(parentRunId)}/recover`,{method:'POST',body:{}}),run=response.run||response;
      if(!run.id)throw new Error('补查任务已提交，但未返回报告编号，请刷新项目查看。');
      if(pid!==projectId()){pollRun(run.id,pid);return;}
      await loadProject(pid);if(pid!==projectId())return;
      await loadRun(run.id,false);if(pid!==projectId())return;
      state.tab='results';state.filter='ALL';state.category='ALL';state.selectedFinding=null;state.selectedConsistencyGroup=null;state.sourceDoc=null;state.sourcePage=1;
      toast('已创建缺口补查批次，原报告和人工记录保留。');pollRun(run.id,pid);
    } finally {state.busy.delete('run');render();}return;
  }
  if (action === 'start-run' || action === 'retry-run') {
    if (state.busy.has('run')) return;
    if (!checksReady() || processing()) { toast('请确认当前招标的检查项，并等待文件处理完成。', true); return; }
    const documentId = action === 'retry-run' ? state.run?.run.documentId : document.getElementById('run-document')?.value, pid = projectId(); if (!documentId) return;
    state.busy.add('run'); render();
    try {
      const frozen = action === 'retry-run' ? state.run?.run : state.project?.project;
      const checkSetId = frozen?.checkSetId || frozen?.activeCheckSetId || '';
      const manifestRevision = frozen?.manifestRevision ?? frozen?.activeCheckSetManifestRevision;
      const body = { documentId, idempotencyKey: randomKey('start-run'), ...(action === 'retry-run' ? { force: true } : {}) };
      if (checkSetId && Number.isInteger(Number(manifestRevision))) { body.checkSetId = checkSetId; body.manifestRevision = Number(manifestRevision); }
      const run = await api(`/api/projects/${enc(pid)}/runs`, { method: 'POST', body }); if (pid !== projectId()) { pollRun(run.id, pid); return; } await loadProject(pid); if (pid !== projectId()) return; await loadRun(run.id, false); if (pid !== projectId()) return; state.tab = 'results'; state.filter = 'ALL'; state.category = 'ALL'; state.selectedFinding = null; state.selectedConsistencyGroup = null; state.sourceDoc = null; state.sourcePage = 1; window.scrollTo({ top: 0, behavior: 'auto' }); pollRun(run.id, pid);
    }
    finally { state.busy.delete('run'); render(); } return;
  }
  if (action === 'retry-task') {
    el.disabled = true;
    const pid = projectId(), old = state.tasks.get(id), task = await api(`/api/tasks/${enc(id)}/retry`, { method: 'POST' });
    state.tasks.set(task.id, { ...old, ...task, projectId: pid, label: old?.label || '重试任务' }); pollTask(task.id, pid, old?.label || '重试任务'); await refreshProject(); toast('已重新提交任务。'); return;
  }
  if (action === 'continue-processing') {
    el.disabled = true; const pid = projectId();
    const task = await api(`/api/documents/${enc(id)}/processing/continue`, { method: 'POST' });
    state.tasks.set(task.id, { ...task, projectId: pid, label: '继续跨页理解与增量检查' });
    pollTask(task.id, pid, '继续跨页理解与增量检查'); await refreshProject(); toast('已继续处理，已完成的识别结果会复用。'); return;
  }
  if (action === 'filter-status') { state.filter = state.filter === el.dataset.status ? 'ALL' : el.dataset.status; state.sourceDoc = null; state.sourcePage = 1; render(); return; }
  if (action === 'select-finding') { state.selectedFinding = id; const finding = displayedRunResults().find(f => f.id === id); const source = finding?.evaluation?.evidences?.find(item => item.documentId === state.run.run.documentId) || finding?.checkpoint?.source; state.sourceDoc = source?.documentId || null; state.sourcePage = source?.page || 1; render(); return; }
  if (action === 'clear-filters') { state.filter='ALL'; state.purposeFilter='ALL'; state.externalOnly=false; state.resultQuery=''; state.selectedFinding=null; render(); return; }
  if (action === 'person-credentials') return showPersonCredentials(id);
  if (action === 'document-preview' || action === 'page-preview') return showPreview(id, Number(el.dataset.page) || 1);
  if (action === 'retry-document') { state.documentErrors.delete(id); render(); return; }
  if (action === 'locate-source') { state.sourceDoc = id; state.sourcePage = Number(el.dataset.page) || 1; if (state.tab === 'results') setDisclosureOpen('report-source-disclosure', true); render(); if (state.tab === 'results') document.querySelector('#report-source-disclosure')?.scrollIntoView({ block: 'nearest' }); return; }
  if (action === 'download-report') return downloadReport(el.dataset.format);
  if (action === 'retry-profile') { state.profileErrors.delete(id); render(); try { await loadProfile(id); } finally { render(); } return; }
  if (action === 'review-history') {
    const list = (state.run.reviews || []).filter(r => r.resultId === id).sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
    return openModal('人工复核记录', '按照提交时间倒序展示，自动检查结论独立保留。', `<div class="modal-body">${list.map(r => `<div class="history-item">${badge(r.status)}${e(r.reviewer || '未署名')} · ${e(date(r.createdAt))}<p>${e(r.note || '无补充说明')}</p></div>`).join('')}</div>`);
  }
  if (action === 'show-profile') {
    const pid = projectId(), version = state.modalVersion, profile = await loadProfile(id), document = docs().find(d => d.id === id);
    if (pid !== projectId() || version !== state.modalVersion || !document) return;
    return openModal('提取信息预览', document?.name || '', `<div class="modal-body"><div class="stack">${profileContent(profile)}</div></div>`, '', 'preview-modal');
  }
}
document.addEventListener('click', event => {
  const el = event.target.closest('[data-action]'); if (!el || el.disabled) return;
  handleAction(el.dataset.action, el).catch(error => { state.loading = false; if (modal.open) modalError(error); else toast(error.message, true); render(); });
});
document.addEventListener('change', async event => {
  const el = event.target;
  rememberReviewDraft(el);
  try {
    if (el.id === 'cp-template') {
      const metadata = templateMetadata(el.value), region = document.getElementById('template-parameter-fields'), version = document.getElementById('cp-template-version');
      const mode = document.getElementById('cp-verification-mode'), modeValue = document.getElementById('cp-verification-mode-value'), requiredMode = metadata?.requiredVerificationMode || 'DOCUMENT';
      if (region) { region.dataset.templateId = el.value; region.innerHTML = renderTemplateParameterFields(metadata, {}); }
      if (version) version.value = metadata?.version || '';
      if (mode) mode.value = requiredMode;
      if (modeValue) modeValue.value = requiredMode;
      return;
    }
    if (el.dataset.upload) { await upload(el.files[0], el.dataset.upload); return; }
    if (el.dataset.toggleCheckpoint) { const cp = checkpoints().find(c => c.id === el.dataset.toggleCheckpoint); if (cp) { el.disabled = true; await updateCheckpoint(cp, { enabled: el.checked }); render(); } return; }
    if (el.id === 'run-document') { state.runDocumentId = el.value; state.profileDoc = el.value; state.sourceDoc = null; state.sourcePage = 1; render(); try { await loadProfile(el.value); } finally { render(); } return; }
    if (el.id === 'run-select') { state.selectedFinding = null; state.purposeFilter='ALL'; state.externalOnly=false; state.selectedConsistencyGroup = null; state.consistencyFilter = 'ALL'; state.consistencyField = 'ALL'; state.consistencyCategory = 'ALL'; state.consistencyRelation = 'ALL'; state.consistencyReview = 'ALL'; state.consistencyQuery = ''; state.consistencyFactsPage = 1; state.consistencyFactsMode = 'ALL'; state.sourceDoc = null; state.sourcePage = 1; await loadRun(el.value); return; }
    if (el.id === 'result-version-view') { state.resultVersionView = el.value === 'current' && publishedRunVersion() ? 'current' : 'source'; state.selectedFinding = null; state.sourceDoc = null; state.sourcePage = 1; render(); return; }
    if (el.id === 'consistency-facts-mode') { state.consistencyFactsMode = el.value; state.consistencyFactsPage = 1; setDisclosureOpen('consistency-facts', true); render(); return; }
    if (el.id === 'consistency-status') { state.consistencyFilter = el.value; render(); return; }
    if (el.id === 'consistency-field') { state.consistencyField = el.value; render(); return; }
    if (el.id === 'consistency-category') { state.consistencyCategory = el.value; render(); return; }
    if (el.id === 'consistency-relation') { state.consistencyRelation = el.value; render(); return; }
    if (el.id === 'consistency-review') { state.consistencyReview = el.value; render(); return; }
    if (el.id === 'result-status') { state.filter = el.value; render(); return; }
    if (el.id === 'result-category') { state.category = el.value; render(); return; }
    if (el.id === 'source-page') { const value = validatePosition(el.dataset.document, el.value); if (value !== null) { state.sourceDoc = el.dataset.document; state.sourcePage = value; } render(); return; }
    if (el.id === 'modal-page-select') { const value = validatePosition(el.dataset.document, el.value); if (value !== null) await showPreview(el.dataset.document, value); return; }
    if (el.id === 'profile-document') { state.profileDoc = el.value; state.runDocumentId = el.value; state.sourceDoc = null; state.sourcePage = 1; render(); await loadProfile(el.value); if (state.tab === 'profile') render(); }
  } catch (error) { toast(error.message, true); render(); }
});
function renderKeepFocus(id, update) {
  const active = document.getElementById(id), start = active?.selectionStart, end = active?.selectionEnd;
  update(); render(); const next = document.getElementById(id); next?.focus();
  if (start != null && next?.setSelectionRange) { try { next.setSelectionRange(start, end); } catch { /* Some search inputs do not support a selection range. */ } }
}
function rememberReviewDraft(target) {
  const form = target.closest?.('#review-form'); if (!form) return;
  const values = new FormData(form);
  state.reviewDrafts.set(form.dataset.result, { status: values.get('status'), reviewer: values.get('reviewer'), note: values.get('note') });
}
document.addEventListener('input', event => {
  rememberReviewDraft(event.target);
  if (event.target.closest('#project-info-form')) {
    const key = event.target.name, pid = projectId();
    if (['name', 'projectNumber', 'openingTime'].includes(key)) {
      const draft = { ...(state.projectDrafts.get(pid) || {}) };
      if (event.target.value === projectValue(key)) delete draft[key]; else draft[key] = event.target.value;
      state.projectDrafts.set(pid, draft);
      const submit = event.target.form.querySelector('button[type="submit"]'); if (submit) submit.disabled = !Object.keys(draft).length;
    }
  }
  if (event.target.id === 'project-search') renderKeepFocus('project-search', () => { state.projectQuery = event.target.value; });
  if (event.target.id === 'consistency-search') renderKeepFocus('consistency-search', () => { state.consistencyQuery = event.target.value; });
  if (event.target.id === 'result-search') renderKeepFocus('result-search', () => { state.resultQuery = event.target.value; });
});
document.addEventListener('submit', async event => {
  const form = event.target; if (!['new-project-form', 'checkpoint-form', 'review-form', 'project-info-form', 'incremental-decision-form', 'incremental-recalculation-form'].includes(form.id)) return;
  event.preventDefault(); if (!form.reportValidity()) return;
  const button = form.querySelector('button[type="submit"]'); if (button.disabled) return; button.disabled = true;
  const values = new FormData(form);
  try {
    if (form.id === 'project-info-form') {
      await saveProjectInformation();
    } else if (form.id === 'new-project-form') {
      const name = String(values.get('name')).trim(); if (!name) throw new Error('请填写项目名称。');
      const project = await api('/api/projects', { method: 'POST', body: { name, code: String(values.get('code')).trim() } });
      modal.close(); state.projects = await api('/api/projects'); state.tab = 'documents'; await loadProject(project.id, { showLoading: true, selectLatest: true }); toast('项目已创建，可以上传招标文件。');
    } else if (form.id === 'checkpoint-form') {
      const cp = checkpoints().find(c => c.id === form.dataset.id); if (form.dataset.id && !cp) throw new Error('未找到当前检查项，请刷新项目。');
      const changes = Object.fromEntries(['title', 'category', 'severity', 'type', 'field', 'operator', 'unit', 'expected'].map(key => [key, String(values.get(key) || '').trim()]));
      if (!changes.title) throw new Error('检查项名称不能为空。');
      for (const key of ['required', 'enabled', 'confirmed']) changes[key] = values.has(key);
      if (cp) {
        const candidate = { ...cp, ...changes }, spec = await saveCheckpointSpec(form, candidate, { prepareOnly: true });
        state.busy.add(`checkpoint-${cp.id}`);
        try {
          const response = await api(`/api/checkpoints/${enc(cp.id)}/configuration`, { method: 'PUT', body: { expectedCheckpoint: cp, checkpoint: candidate, spec } });
          const updated = response.checkpoint; if (projectId() === state.project?.project?.id) { const index = state.project.checkpoints.findIndex(item => item.id === cp.id); if (index >= 0) state.project.checkpoints[index] = updated; }
          if (response.spec?.checkpointId) { state.checkpointSpecs.set(checkpointSpecKey(response.spec.checkpointId), response.spec); state.checkpointSpecErrors.delete(checkpointSpecKey(response.spec.checkpointId)); }
        } catch (error) {
          if (error.status === 409) { await loadProject(projectId()); await loadCheckpointSpec(cp.id, { force: true, quiet: true }); throw new Error(`${error.message}已刷新到最新配置，请重新修改。`); }
          throw error;
        } finally { state.busy.delete(`checkpoint-${cp.id}`); }
      }
      else { const pid = projectId(), created = await api(`/api/projects/${enc(pid)}/checkpoints`, { method: 'POST', body: { ...changes, source: null } }); if (pid === projectId()) { state.project.checkpoints.push(created); state.selectedCheckpoint = created.id; state.checkpointGroup = 'ALL'; } }
      modal.close(); render(); toast('检查项已保存。');
    } else if (form.id === 'review-form') {
      const reviewer = String(values.get('reviewer')).trim(), note = String(values.get('note')).trim();
      if (!reviewer || !note) throw new Error('请填写复核人和复核说明。');
      const resultId = form.dataset.result, runId = state.run.run.id;
      if (state.busy.has(`review-${resultId}`)) return;
      state.busy.add(`review-${resultId}`);
      try {
        await api(`/api/results/${enc(resultId)}/review`, { method: 'POST', body: { status: values.get('status'), reviewer, note } });
        state.reviewDrafts.delete(resultId); if (state.run?.run.id === runId) await loadRun(runId); toast('人工复核已保存，自动结论和历史记录均已保留。');
      } finally { state.busy.delete(`review-${resultId}`); if (state.run?.run.id === runId) render(); }
    } else if (form.id === 'incremental-recalculation-form') {
      const runId = form.dataset.run, revision = Number(form.dataset.revision), context = state.incrementalRecalculationContext;
      if (!runId || state.run?.run?.id !== runId || context?.runId !== runId || context.revision !== revision) throw new Error('依赖范围已变化，请关闭窗口后重新选择。');
      const selected = [...form.querySelectorAll('input[name="changedNeedKeys"]:checked')].map(input => input.value);
      const changedNeedKeys = [...new Set(selected)].filter(key => context.allowedNeedKeys.has(key));
      if (!changedNeedKeys.length || changedNeedKeys.length !== new Set(selected).size) throw new Error('请至少选择一个当前依赖图中的证据范围。');
      const plan = await api(`/api/runs/${enc(runId)}/dependency-recalculations/plan`, { method: 'POST', body: { expectedRevision: revision, changedNeedKeys } });
      if (plan.noOp || !Array.isArray(plan.affectedCheckpointIds) || !plan.affectedCheckpointIds.length) throw new Error('所选证据范围没有关联检查项，无需生成候选。');
      const candidate = await api(`/api/runs/${enc(runId)}/dependency-recalculations`, { method: 'POST', body: { expectedRevision: revision, changedNeedKeys, expectedSourceResultDigest: plan.sourceResultDigest } });
      modal.close(); state.incrementalRecalculationContext = null; await loadRun(runId, false); render();
      toast(Number(candidate.changedCheckpointCount || 0) > 0 ? `已生成候选：${candidate.changedCheckpointCount} 项结论变化，等待审核发布。` : '已完成关联项重算，结论没有变化，无需发布。');
    } else if (form.id === 'incremental-decision-form') {
      const runId = form.dataset.run, recordId = form.dataset.record, decision = form.dataset.decision;
      const reviewer = String(values.get('reviewer') || '').trim(), note = String(values.get('note') || '').trim();
      if (!runId || !recordId || state.run?.run?.id !== runId) throw new Error('当前报告已变化，请关闭窗口后重新审核。');
      if (!['APPROVE', 'REJECT'].includes(decision) || !reviewer || !note) throw new Error('请填写审核人和审核说明。');
      await api(`/api/runs/${enc(runId)}/dependency-recalculations/${enc(recordId)}/decision`, { method: 'POST', body: {
        decision, reviewer, note, expectedDecisionRevision: Number(form.dataset.revision || 0),
        expectedSourceResultDigest: form.dataset.sourceDigest || '', expectedEvidenceSnapshotDigest: form.dataset.evidenceDigest || ''
      } });
      if (decision === 'APPROVE') { state.resultVersionRunId = runId; state.resultVersionView = 'current'; }
      modal.close(); await loadRun(runId, false); render();
      toast(decision === 'APPROVE' ? '增量结论已发布为新的结果版本，旧报告保持不变。' : '候选结论已驳回并保留审核记录。');
    }
  } catch (error) { if (modal.open) modalError(error); else toast(error.message, true); }
  finally { button.disabled = false; }
});
app.addEventListener('toggle', event => { if (!event.target.isConnected) return; if (event.target.dataset.disclosureKey) state.disclosures.set(event.target.dataset.disclosureKey, event.target.open); }, true);
modal.addEventListener('click', event => { if (event.target === modal) { const rect = modal.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) modal.close(); } });
modal.addEventListener('close', () => { ++state.modalVersion; });
async function init() {
  state.loading = true; state.error = ''; render();
  try {
    const [config, projects, templates] = await Promise.all([api('/api/config'), api('/api/projects'), api('/api/check-templates').catch(() => [])]);
    state.config = { ...state.config, ...config }; state.projects = projects; state.templateCatalog = Array.isArray(templates) ? templates : [];
  }
  catch (error) { state.error = error.message; }
  finally { state.loading = false; render(); }
}
init();
