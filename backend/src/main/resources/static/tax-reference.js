// The existing review form owns manual decisions. This action prepares a cited draft for the reviewer.
document.addEventListener('click', event => {
  const button = event.target.closest?.('button[data-tax-review]');
  if (!button) return;
  const details = button.closest('.finding-detail')?.querySelector('.business-manual-details');
  const note = details?.querySelector('textarea[name="note"]');
  if (!note) return;
  details.open = true;
  if (!note.value.trim()) {
    note.value = '税务截图 ' + button.dataset.taxReview + '。采用该截图作为时点依据的理由：';
    note.dispatchEvent(new Event('input', {bubbles:true}));
  }
  note.focus();
});

document.addEventListener('submit', async event => {
  const form = event.target;
  if (!form.matches?.('form[data-tax-attachment]')) return;
  event.preventDefault();
  const button = form.querySelector('button[type="submit"]');
  if (button.disabled) return;
  button.disabled = true;
  const message = form.querySelector('[role="status"]');
  try {
    const prefix = '/api/runs/' + encodeURIComponent(form.dataset.run) + '/checkpoints/' + encodeURIComponent(form.dataset.checkpoint);
    const response = await fetch(prefix + '/tax-reference-attachments', {method:'POST',body:new FormData(form)});
    const record = await response.json();
    if (!response.ok) throw new Error(record.message || '附件提交失败');
    message.textContent = record.status === 'FAILED' ? '附件解析失败，请查看文件处理记录。' : '附件已关联到本检查项。解析及重算状态：' + (record.recomputeStatus || record.status || '待处理');
    if(record.recomputeRunId){
      const link = document.createElement('button'); link.type='button';link.className='text-action';
      link.dataset.action='open-run'; link.dataset.id=record.recomputeRunId; link.textContent='查看补查';message.appendChild(link);
    }
    form.reset();
  } catch (error) {
    message.textContent = error.message || '提交失败，请重试。';
  } finally {
    button.disabled = false;
  }
});
