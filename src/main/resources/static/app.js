'use strict';

const $ = id => document.getElementById(id);
const state = {
  file: null, original: null, result: null, report: null, summaries: [],
  jobId: null, created: null, startedAt: 0, timer: null, poll: null, xhr: null,
  layers: {}, featureLayers: new Map(), violationsVisible: false
};

const number = (value, digits = 1) => Number(value || 0).toLocaleString('ru-RU', { maximumFractionDigits: digits });
const money = value => {
  const amount = Number(value || 0);
  if (Math.abs(amount) >= 1e9) return `${number(amount / 1e9, 2)} млрд ₽`;
  if (Math.abs(amount) >= 1e6) return `${number(amount / 1e6, 1)} млн ₽`;
  return `${number(amount, 0)} ₽`;
};
const bytes = value => {
  const units = ['Б', 'КБ', 'МБ', 'ГБ']; let size = Number(value || 0), unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit += 1; }
  return `${number(size, unit ? 1 : 0)} ${units[unit]}`;
};
const key = value => String(value);
const escapeHtml = value => String(value == null ? '' : value).replace(/[&<>"]/g, char => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'
})[char]);
const validCollection = data => data && data.type === 'FeatureCollection' && Array.isArray(data.features);
const palette = {
  brand: '#ff4b16', brandDark: '#d93608', charcoal: '#23272f', existing: '#65707c',
  special: '#ff9d1b', danger: '#d43b36', source: '#76558f'
};

const map = L.map('map', { preferCanvas: true, zoomControl: false, attributionControl: false }).setView([55.75, 37.62], 12);
L.control.zoom({ position: 'bottomright' }).addTo(map);
L.control.scale({ position: 'bottomright', imperial: false }).addTo(map);
L.control.attribution({ position: 'bottomright', prefix: 'Leaflet · офлайн' }).addTo(map);
for (const [name, z] of Object.entries({ restrictions: 210, existing: 310, route: 420, points: 520, violations: 620 })) {
  map.createPane(name).style.zIndex = z;
}

function toast(message, error = false) {
  const node = $('toast'); node.textContent = message; node.className = `toast show${error ? ' error' : ''}`;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => { node.className = 'toast'; }, 4200);
}

async function checkHealth() {
  try {
    const response = await fetch('/actuator/health', { cache: 'no-store' });
    const data = await response.json();
    if (!response.ok || data.status !== 'UP') throw new Error();
    $('service-state').className = 'service-state online'; $('service-state').innerHTML = '<i></i>Сервис готов';
  } catch (_) {
    $('service-state').className = 'service-state offline'; $('service-state').innerHTML = '<i></i>Сервис недоступен';
  }
}

function restrictionColor(type) {
  return ({ oks: '#747b83', water: '#4b90b8', railway: '#4f5660', road: '#89827c', tram_tracks: '#8d6b9c',
    gas_pipeline: '#c8912d', power_cable: '#d46a45', park: '#73a36c' })[type] || '#838a92';
}

function titleFor(properties) {
  if (properties.object_type === 'restriction') return properties.restriction_type === 'oks' ? 'Здание ОКС' : 'Ограничение';
  return ({ heat_network: 'Участок теплосети', heat_chamber: 'Тепловая камера', source: 'Источник',
    oks_connection_point: 'Точка подключения ОКС', technical_node: 'Технический узел', variant_summary: 'Сводка варианта' })[properties.object_type] || 'Объект';
}

function popup(feature) {
  const p = feature.properties || {};
  const rows = [
    ['ID', p.id], ['Тип', p.restriction_type], ['Вариант', p.variant_id],
    ['ДУ', p.diameter == null ? null : `${p.diameter} мм`],
    ['Расход', p.flow_tph == null ? null : `${number(p.flow_tph, 2)} т/ч`],
    ['Длина', p.length == null ? null : `${number(p.length, 2)} м`],
    ['Стоимость', p.cost == null ? null : money(p.cost)],
    ['Прокладка', p.laying_method === 'special' ? 'специальная' : p.laying_method === 'base' ? 'обычная' : null],
    ['Глубина', p.depth_start == null ? null : `${number(p.depth_start, 2)} → ${number(p.depth_end, 2)} м`],
    ['Обслуживает ОКС', Array.isArray(p.serves_oks) ? p.serves_oks.join(', ') : p.serves_oks],
    ['Пересечения', Array.isArray(p.crossed) ? p.crossed.join(', ') : p.crossed],
    ['Причина', p.reason], ['Подход', p.approach]
  ].filter(([, value]) => value !== null && value !== undefined && value !== '');
  return `<h4 class="popup-title">${escapeHtml(titleFor(p))}</h4><dl class="popup-grid">${rows.map(([name, value]) =>
    `<dt>${escapeHtml(name)}</dt><dd>${escapeHtml(value)}</dd>`).join('')}</dl>`;
}

function makeInputLayer(features, pane) {
  return L.geoJSON({ type: 'FeatureCollection', features }, {
    pane,
    style: feature => {
      const p = feature.properties || {};
      if (p.object_type === 'restriction') {
        const color = restrictionColor(p.restriction_type);
        return { color, weight: p.restriction_type === 'oks' ? 1.5 : 2, opacity: .85, fillColor: color, fillOpacity: p.restriction_type === 'oks' ? .18 : .1 };
      }
      return { color: palette.existing, weight: 3, opacity: .9 };
    },
    pointToLayer: (feature, latlng) => {
      const type = feature.properties.object_type;
      const style = type === 'oks_connection_point'
          ? { radius: 6, color: '#fff', weight: 2, fillColor: palette.brand, fillOpacity: 1 }
        : type === 'source'
          ? { radius: 7, color: '#fff', weight: 2, fillColor: palette.source, fillOpacity: 1 }
          : { radius: 5, color: '#fff', weight: 2, fillColor: palette.charcoal, fillOpacity: 1 };
      return L.circleMarker(latlng, style);
    },
    onEachFeature: (feature, layer) => layer.bindPopup(popup(feature))
  });
}

function makeRouteLayer(features) {
  return L.geoJSON({ type: 'FeatureCollection', features }, {
    pane: 'route',
    style: feature => ({
      color: feature.properties.laying_method === 'special' ? palette.special : palette.brand,
      weight: feature.properties.laying_method === 'special' ? 6 : 4.5,
      opacity: .95,
      dashArray: feature.properties.laying_method === 'special' ? '8 5' : null,
      lineCap: 'round', lineJoin: 'round'
    }),
    pointToLayer: (feature, latlng) => L.circleMarker(latlng, {
      radius: feature.properties.object_type === 'heat_chamber' ? 6 : 4,
      color: '#fff', weight: 2,
      fillColor: feature.properties.object_type === 'heat_chamber' ? palette.charcoal : palette.brandDark, fillOpacity: 1
    }),
    onEachFeature: (feature, layer) => {
      layer.bindPopup(popup(feature));
      state.featureLayers.set(key(feature.properties.id), layer);
      if ($('layer-labels').checked && feature.properties.object_type === 'heat_network') {
        layer.bindTooltip(`ДУ ${feature.properties.diameter} · ${number(feature.properties.flow_tph, 1)} т/ч`, {
          permanent: true, direction: 'center', className: 'route-label'
        });
      }
    }
  });
}

function replaceLayer(name, layer) {
  if (state.layers[name] && map.hasLayer(state.layers[name])) map.removeLayer(state.layers[name]);
  state.layers[name] = layer;
  const toggle = $(`layer-${name}`);
  if (!toggle || toggle.checked) layer.addTo(map);
}

function clearLayer(name) {
  if (state.layers[name] && map.hasLayer(state.layers[name])) map.removeLayer(state.layers[name]);
  delete state.layers[name];
}

function renderOriginal() {
  clearLayer('restrictions'); clearLayer('existing'); clearLayer('points');
  if (!state.original) return;
  const features = state.original.features;
  replaceLayer('restrictions', makeInputLayer(features.filter(f => f.properties?.object_type === 'restriction'), 'restrictions'));
  replaceLayer('existing', makeInputLayer(features.filter(f => f.properties?.object_type === 'heat_network'), 'existing'));
  replaceLayer('points', makeInputLayer(features.filter(f => ['heat_chamber', 'source', 'oks_connection_point'].includes(f.properties?.object_type)), 'points'));
  $('empty-map').classList.add('hidden'); $('fit-all').disabled = false;
}

function selectedSummary() {
  const variantId = $('variant').value;
  return state.summaries.find(feature => key(feature.properties.variant_id) === key(variantId));
}

function variantFeatures() {
  const summary = selectedSummary();
  return summary ? state.result.features.filter(feature => key(feature.properties?.variant_id) === key(summary.properties.variant_id)) : [];
}

function renderRoute() {
  clearLayer('route'); clearLayer('violations'); state.featureLayers.clear();
  if (!state.result || !selectedSummary()) return;
  replaceLayer('route', makeRouteLayer(variantFeatures().filter(feature => feature.geometry)));
  $('empty-map').classList.add('hidden'); $('fit-route').disabled = false; $('fit-all').disabled = false;
  renderViolations(); renderAnalytics();
}

function fitLayers(names) {
  const layers = names.map(name => state.layers[name]).filter(Boolean).filter(layer => map.hasLayer(layer));
  if (!layers.length) return;
  const bounds = L.featureGroup(layers).getBounds(); if (bounds.isValid()) map.fitBounds(bounds.pad(.08));
}

async function selectFile(file) {
  if (!file) return;
  if (state.xhr || (state.jobId && state.timer)) {
    toast('Дождитесь завершения текущего расчёта или отмените его', true); return;
  }
  if (!/\.(geo)?json$/i.test(file.name) && !/json/i.test(file.type || '')) {
    toast('Выберите файл GeoJSON или JSON', true); return;
  }
  state.file = file; state.original = null;
  $('file-title').textContent = file.name; $('file-caption').textContent = `${bytes(file.size)} · готов к загрузке`;
  $('start').disabled = false; $('input-summary').hidden = false;
  $('input-summary').textContent = 'Файл выбран. Проверка структуры выполнится на сервере перед расчётом.';
  clearResults();
  if (file.size <= 50 * 1024 * 1024) {
    try {
      const parsed = JSON.parse(await file.text());
      if (!validCollection(parsed)) throw new Error('Ожидается GeoJSON FeatureCollection');
      state.original = parsed; renderOriginal(); fitLayers(['restrictions', 'existing', 'points']);
      const counts = parsed.features.reduce((acc, feature) => {
        const type = feature.properties?.object_type || 'unknown'; acc[type] = (acc[type] || 0) + 1; return acc;
      }, {});
      $('input-summary').textContent = `${parsed.features.length} объектов · ${counts.oks_connection_point || 0} точек ОКС · ${counts.heat_network || 0} участков существующей сети`;
    } catch (error) {
      $('input-summary').textContent = `Предпросмотр недоступен: ${error.message}. Сервер вернёт подробную диагностику.`;
    }
  } else {
    $('input-summary').textContent = `Файл ${bytes(file.size)} будет передан потоково. Предпросмотр карты отключён для файлов больше 50 МБ.`;
  }
}

function clearResults() {
  state.result = null; state.report = null; state.summaries = []; state.jobId = null; state.created = null;
  clearLayer('route'); clearLayer('violations');
  $('variant').disabled = true; $('variant').innerHTML = '<option>Сначала запустите расчёт</option>';
  $('validation-chip').className = 'validation-chip neutral'; $('validation-chip').textContent = 'Нет расчёта';
  for (const id of ['download-result', 'download-validation']) {
    $(id).classList.add('disabled'); $(id).removeAttribute('href'); $(id).setAttribute('aria-disabled', 'true');
  }
  resetAnalytics();
}

function queryParameters() {
  const params = new URLSearchParams();
  params.set('mode', document.querySelector('input[name="mode"]:checked').value);
  params.set('resource', $('resource').value);
  for (const [input, keyName] of [['pin-tie-in', 'pin_tie_in'], ['exclude-tie-in', 'exclude_tie_in'], ['exclude-restriction', 'exclude_restriction']]) {
    if ($(input).value.trim()) params.set(keyName, $(input).value.trim());
  }
  return params;
}

function setJob(status, title, message, progress) {
  $('job-card').hidden = false; $('job-status').textContent = status;
  $('job-status').className = `status-badge ${status === 'Готово' ? 'done' : status === 'Ошибка' || status === 'Отменено' ? 'failed' : 'running'}`;
  $('job-title').textContent = title; $('job-message').textContent = message;
  const bar = $('job-progress'); bar.classList.toggle('indeterminate', progress == null); if (progress != null) bar.style.width = `${Math.max(0, Math.min(100, progress))}%`;
}

function startTimer() {
  state.startedAt = Date.now(); clearInterval(state.timer);
  state.timer = setInterval(() => {
    const seconds = Math.floor((Date.now() - state.startedAt) / 1000);
    $('job-time').textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    if (state.created?.estimatedSeconds && state.jobId) {
      const approximate = Math.min(92, 20 + seconds / state.created.estimatedSeconds * 70);
      if (!$('job-progress').classList.contains('indeterminate')) $('job-progress').style.width = `${approximate}%`;
    }
  }, 1000);
}

function stopTimer() { clearInterval(state.timer); clearTimeout(state.poll); state.timer = null; state.poll = null; }

function showApiError(body, fallback) {
  const diagnostics = Array.isArray(body?.diagnostics) ? body.diagnostics : [];
  $('input-errors').hidden = false;
  $('input-errors').innerHTML = `<strong>${escapeHtml(body?.message || fallback)}</strong>${diagnostics.length ? `<ul>${diagnostics.slice(0, 12).map(item =>
    `<li>${escapeHtml([item.objectId, item.field, item.message].filter(Boolean).join(' · '))}</li>`).join('')}</ul>` : ''}`;
}

function startCalculation() {
  if (!state.file || state.xhr) return;
  $('input-errors').hidden = true; $('input-errors').innerHTML = ''; $('start').disabled = true;
  $('source-file').disabled = true; $('cancel').hidden = false;
  setJob('Загрузка', 'Передаём GeoJSON', 'После загрузки сервис проверит структуру и геометрию.', 2); startTimer();
  const form = new FormData(); form.append('file', state.file, state.file.name);
  const xhr = new XMLHttpRequest(); state.xhr = xhr;
  xhr.open('POST', `/api/v1/jobs?${queryParameters()}`);
  xhr.upload.onprogress = event => {
    if (!event.lengthComputable) { $('job-progress').classList.add('indeterminate'); return; }
    const progress = Math.max(2, event.loaded / event.total * 18);
    setJob('Загрузка', 'Передаём GeoJSON', `${bytes(event.loaded)} из ${bytes(event.total)}`, progress);
  };
  xhr.onerror = () => finishUploadError({ message: 'Не удалось связаться с сервисом' });
  xhr.onabort = () => { state.xhr = null; stopTimer(); setJob('Отменено', 'Загрузка отменена', 'Можно выбрать файл и запустить расчёт снова.', 0); $('start').disabled = false; $('source-file').disabled = false; };
  xhr.onload = () => {
    state.xhr = null;
    let body = {}; try { body = JSON.parse(xhr.responseText || '{}'); } catch (_) { body = {}; }
    if (xhr.status !== 202) { finishUploadError(body); return; }
    state.created = body; state.jobId = body.jobId; $('job-id').textContent = body.jobId;
    setJob('В очереди', 'Входные данные прошли проверку', `${body.connectionPoints} точек ОКС · оценка ${body.estimatedSeconds} с`, 20);
    pollJob();
  };
  xhr.send(form);
}

function finishUploadError(body) {
  state.xhr = null; stopTimer(); $('start').disabled = false; $('source-file').disabled = false;
  setJob('Ошибка', 'Расчёт не запущен', body?.message || 'Ошибка загрузки или проверки входа', 0);
  showApiError(body, 'Сервис отклонил входные данные'); toast(body?.message || 'Ошибка запуска расчёта', true);
}

async function pollJob() {
  if (!state.jobId) return;
  try {
    const response = await fetch(`/api/v1/jobs/${state.jobId}`, { cache: 'no-store' });
    const job = await response.json(); if (!response.ok) throw new Error(job.message || 'Не удалось получить статус');
    if (job.status === 'UPLOADED') {
      setJob('В очереди', 'Ожидаем свободный вычислительный поток', `Задание ${state.jobId}`, 22);
    } else if (job.status === 'RUNNING') {
      const elapsed = Math.floor((Date.now() - state.startedAt) / 1000);
      const estimate = state.created?.estimatedSeconds || 0;
      setJob('Расчёт', 'Строим и проверяем варианты', estimate ? `Прошло ${elapsed} с · оценка ${estimate} с` : `Прошло ${elapsed} с`, Math.min(92, 25 + elapsed / Math.max(estimate, 1) * 65));
    } else if (job.status === 'DONE') {
      await loadCompleted(); return;
    } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
      stopTimer(); $('start').disabled = false; $('source-file').disabled = false;
      setJob(job.status === 'FAILED' ? 'Ошибка' : 'Отменено', job.status === 'FAILED' ? 'Расчёт завершился с ошибкой' : 'Расчёт отменён', job.message || job.code || 'Нет подробностей', 0);
      if (job.diagnostics?.length) showApiError({ message: job.message, diagnostics: job.diagnostics }, 'Ошибка расчёта'); return;
    }
    state.poll = setTimeout(pollJob, 1000);
  } catch (error) {
    setJob('Связь', 'Ожидаем ответ сервиса', error.message, null); state.poll = setTimeout(pollJob, 1800);
  }
}

async function loadCompleted() {
  const [resultResponse, validationResponse] = await Promise.all([
    fetch(`/api/v1/jobs/${state.jobId}/result`, { cache: 'no-store' }),
    fetch(`/api/v1/jobs/${state.jobId}/validation`, { cache: 'no-store' })
  ]);
  if (!resultResponse.ok || !validationResponse.ok) throw new Error('Результат готов, но не скачался');
  state.result = await resultResponse.json(); state.report = await validationResponse.json();
  state.summaries = state.result.features.filter(feature => feature.properties?.object_type === 'variant_summary')
    .sort((a, b) => Number(a.properties.rank) - Number(b.properties.rank));
  populateVariants(); stopTimer(); $('start').disabled = false; $('source-file').disabled = false;
  setJob('Готово', `Построено вариантов: ${state.summaries.length}`, `Результат проверен независимым валидатором.`, 100);
  $('cancel').hidden = true;
  $('download-result').href = `/api/v1/jobs/${state.jobId}/result`;
  $('download-validation').href = `/api/v1/jobs/${state.jobId}/validation`;
  for (const id of ['download-result', 'download-validation']) { $(id).classList.remove('disabled'); $(id).setAttribute('aria-disabled', 'false'); }
  renderRoute(); renderQuality(); fitLayers(['restrictions', 'existing', 'points', 'route']);
  toast(`Расчёт готов: ${state.summaries.length} варианта, ${state.report.errorCount} ошибок`);
}

async function cancelCalculation() {
  if (state.xhr) { state.xhr.abort(); return; }
  if (!state.jobId) return;
  try {
    const response = await fetch(`/api/v1/jobs/${state.jobId}`, { method: 'DELETE' });
    if (!response.ok && response.status !== 204) throw new Error('Задание уже завершено');
    stopTimer(); setJob('Отменено', 'Расчёт отменён', 'Можно запустить новый расчёт.', 0); $('start').disabled = false; $('source-file').disabled = false;
  } catch (error) { toast(error.message, true); }
}

function populateVariants() {
  $('variant').replaceChildren();
  for (const summary of state.summaries) {
    const p = summary.properties; const option = document.createElement('option');
    option.value = p.variant_id; option.textContent = `Вариант ${p.rank} · score ${number(p.score, 4)} · ${money(p.calculated_cost)}`;
    $('variant').append(option);
  }
  $('variant').disabled = !state.summaries.length;
}

function resetAnalytics() {
  for (const id of ['metric-score', 'metric-cost', 'metric-length', 'metric-coverage', 'quality-errors', 'quality-warnings', 'quality-checks']) $(id).textContent = '—';
  $('metric-penalty').textContent = 'без штрафов'; $('metric-special').textContent = '— спецпроходов'; $('metric-unconnected').textContent = 'нет данных';
  $('route-stats').className = 'stat-list empty-content'; $('route-stats').textContent = 'Выполните расчёт';
  $('diameter-chart').className = 'bar-chart empty-content'; $('diameter-chart').textContent = 'Нет данных';
  $('variant-explanation').textContent = 'После расчёта здесь появится объяснение ранжирования.';
  $('comparison-body').innerHTML = '<tr><td colspan="5">Нет данных</td></tr>';
  $('overlap-list').className = 'stat-list empty-content'; $('overlap-list').textContent = 'Нет данных';
  $('rules-body').innerHTML = '<tr><td colspan="4">Нет отчёта</td></tr>';
  $('diagnostic-list').className = 'diagnostic-list empty-content'; $('diagnostic-list').textContent = 'Нет отчёта';
  $('limitations').innerHTML = '<li>Нет отчёта</li>'; $('depth-block').hidden = true;
}

function statRow(label, value) { return `<div class="stat-row"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`; }

function renderAnalytics() {
  const summary = selectedSummary(); if (!summary) return; const p = summary.properties;
  const features = variantFeatures(); const pipes = features.filter(feature => feature.properties?.object_type === 'heat_network');
  const chambers = features.filter(feature => feature.properties?.object_type === 'heat_chamber');
  const nodes = features.filter(feature => feature.properties?.object_type === 'technical_node');
  const special = pipes.filter(feature => feature.properties?.laying_method === 'special');
  const specialLength = special.reduce((sum, feature) => sum + Number(feature.properties.length || 0), 0);
  const totalOks = state.original?.features.filter(feature => feature.properties?.object_type === 'oks_connection_point').length
    || state.created?.connectionPoints || 0;
  const unconnected = Array.isArray(p.unconnected_oks_ids) ? p.unconnected_oks_ids : [];
  const connected = Math.max(0, totalOks - unconnected.length);
  $('metric-score').textContent = number(p.score, 4); $('metric-cost').textContent = money(p.calculated_cost);
  $('metric-penalty').textContent = Number(p.unconnected_penalty || 0) ? `штраф ${money(p.unconnected_penalty)}` : 'без штрафов';
  $('metric-length').textContent = `${number(p.new_network_length, 1)} м`; $('metric-special').textContent = `${number(specialLength, 1)} м спецпроходов`;
  $('metric-coverage').textContent = totalOks ? `${connected} из ${totalOks}` : unconnected.length ? `−${unconnected.length}` : 'все';
  $('metric-unconnected').textContent = unconnected.length ? `не подключены: ${unconnected.join(', ')}` : 'все точки подключены';
  const metrics = p.route_metrics || {};
  $('route-stats').className = 'stat-list';
  $('route-stats').innerHTML = [
    ['Участки новой сети', pipes.length], ['Новые камеры', chambers.length], ['Технические узлы', nodes.length],
    ['Точки врезки', metrics.tie_in_points ?? p.existing_chamber_tie_in_count ?? '—'], ['Камеры-разветвления', metrics.branch_chambers ?? '—'],
    ['Повороты', metrics.turns ?? '—'], ['Самый длинный путь', metrics.longest_path_m == null ? '—' : `${number(metrics.longest_path_m, 1)} м`]
  ].map(([label, value]) => statRow(label, value)).join('');
  renderDiameterChart(p.cost_breakdown?.pipes_by_diameter || {}, p.new_network_length);
  $('variant-explanation').textContent = p.explanation || p.description || 'Алгоритм не добавил пояснение для этого варианта.';
  renderComparison(); renderDepth(features); renderQuality();
}

function renderDiameterChart(items, totalLength) {
  const rows = Object.entries(items).sort((a, b) => Number(a[0].replace(/\D/g, '')) - Number(b[0].replace(/\D/g, '')));
  if (!rows.length) { $('diameter-chart').className = 'bar-chart empty-content'; $('diameter-chart').textContent = 'Нет разбивки'; return; }
  const max = Math.max(...rows.map(([, value]) => Number(value.length_m || 0)), 1);
  $('diameter-chart').className = 'bar-chart';
  $('diameter-chart').innerHTML = rows.map(([diameter, value]) => `<div class="bar-row"><span>${escapeHtml(diameter)}</span><div class="bar-track"><i style="width:${Number(value.length_m || 0) / max * 100}%"></i></div><span>${number(value.length_m, 1)} м</span></div>`).join('')
    + `<div class="block-caption">Стоимость труб: ${money(rows.reduce((sum, [, value]) => sum + Number(value.cost || 0), 0))} · длина ${number(totalLength, 1)} м</div>`;
}

function renderComparison() {
  if (!state.summaries.length) return;
  const totalOks = state.original?.features.filter(feature => feature.properties?.object_type === 'oks_connection_point').length
    || state.created?.connectionPoints || 0;
  $('comparison-body').innerHTML = state.summaries.map(summary => {
    const p = summary.properties; const missed = p.unconnected_oks_ids?.length || 0;
    return `<tr data-variant="${escapeHtml(p.variant_id)}" class="${key(p.variant_id) === key($('variant').value) ? 'selected' : ''}"><td>${p.rank}</td><td>${number(p.score, 4)}</td><td>${money(p.calculated_cost)}</td><td>${number(p.new_network_length, 0)} м</td><td>${totalOks ? totalOks - missed : '—'}</td></tr>`;
  }).join('');
  for (const row of $('comparison-body').querySelectorAll('tr[data-variant]')) row.addEventListener('click', () => {
    $('variant').value = row.dataset.variant; renderRoute(); fitLayers(['route']);
  });
  $('overlap-list').className = 'stat-list';
  $('overlap-list').innerHTML = state.summaries.map(summary => {
    const p = summary.properties; const overlap = Number(p.route_overlap_with_better || 0);
    return statRow(`Вариант ${p.rank}`, p.rank === 1 ? 'базовый лучший' : `${number(overlap * 100, 1)}% совпадения с лучшими`);
  }).join('');
}

function rootIds(features) {
  const roots = new Set();
  (state.original?.features || []).filter(feature => feature.properties?.object_type === 'heat_chamber').forEach(feature => roots.add(key(feature.properties.id)));
  features.filter(feature => feature.properties?.object_type === 'heat_chamber' && feature.properties.tie_in_pipe_id != null).forEach(feature => roots.add(key(feature.properties.id)));
  return roots;
}

function depthPath(pipes, features) {
  const adjacent = new Map();
  for (const pipe of pipes) for (const id of [pipe.properties.start_node_id, pipe.properties.end_node_id]) {
    const idKey = key(id); if (!adjacent.has(idKey)) adjacent.set(idKey, []); adjacent.get(idKey).push(pipe);
  }
  const roots = rootIds(features); const targets = (state.original?.features || []).filter(feature => feature.properties?.object_type === 'oks_connection_point').map(feature => key(feature.properties.id));
  let best = [];
  function walk(node, seen, path) {
    if (roots.has(node) && path.length) { if (path.length > best.length) best = path.slice(); return; }
    for (const pipe of adjacent.get(node) || []) {
      if (seen.has(pipe)) continue; seen.add(pipe);
      const forward = key(pipe.properties.start_node_id) === node;
      path.push({ pipe, forward }); walk(key(forward ? pipe.properties.end_node_id : pipe.properties.start_node_id), seen, path); path.pop(); seen.delete(pipe);
    }
  }
  targets.forEach(target => walk(target, new Set(), [])); return best.length ? best : pipes.map(pipe => ({ pipe, forward: true }));
}

function renderDepth(features) {
  const pipes = features.filter(feature => feature.properties?.object_type === 'heat_network' && feature.properties.depth_start != null && feature.properties.depth_end != null);
  $('depth-block').hidden = !pipes.length; if (!pipes.length) return;
  const path = depthPath(pipes, features); let distance = 0; const points = [];
  for (const { pipe, forward } of path) {
    const p = pipe.properties; const length = Number(p.length || 0);
    points.push([distance, Number(forward ? p.depth_start : p.depth_end)]); distance += length;
    points.push([distance, Number(forward ? p.depth_end : p.depth_start)]);
  }
  const values = points.map(point => point[1]); let min = Math.min(...values), max = Math.max(...values); if (max - min < .1) { min -= .05; max += .05; }
  const sx = value => 38 + value / Math.max(distance, 1) * 300; const sy = value => 14 + (value - min) / (max - min) * 125;
  const svg = $('depth-profile'); svg.replaceChildren(); const ns = 'http://www.w3.org/2000/svg';
  const make = (name, attrs, text) => { const node = document.createElementNS(ns, name); for (const [attr, value] of Object.entries(attrs)) node.setAttribute(attr, value); if (text) node.textContent = text; svg.append(node); return node; };
  make('line', { x1: 38, y1: 14, x2: 38, y2: 139, stroke: '#b5c3bf' }); make('line', { x1: 38, y1: 139, x2: 338, y2: 139, stroke: '#b5c3bf' });
  make('polyline', { points: points.map(point => `${sx(point[0])},${sy(point[1])}`).join(' '), fill: 'none', stroke: palette.brand, 'stroke-width': 3, 'stroke-linejoin': 'round' });
  make('text', { x: 3, y: 19, fill: '#6d7c80', 'font-size': 10 }, `${max.toFixed(2)} м`); make('text', { x: 3, y: 139, fill: '#6d7c80', 'font-size': 10 }, `${min.toFixed(2)} м`);
  make('text', { x: 278, y: 160, fill: '#6d7c80', 'font-size': 10 }, `${number(distance, 0)} м`);
  $('depth-caption').textContent = `Самый длинный путь: ${path.length} участков. Глубина указана до верха габарита сети.`;
}

function renderQuality() {
  if (!state.report) return;
  const report = state.report; const checks = Object.values(report.ruleSummary || {}).reduce((sum, item) => sum + Number(item.checks || 0), 0);
  $('validation-chip').className = `validation-chip ${report.valid ? 'valid' : 'invalid'}`;
  $('validation-chip').textContent = report.valid ? `Проверено · 0 ошибок` : `${report.errorCount} ошибок`;
  $('quality-errors').textContent = number(report.errorCount, 0); $('quality-warnings').textContent = number(report.warningCount, 0); $('quality-checks').textContent = number(checks, 0);
  const rules = Object.entries(report.ruleSummary || {}).sort(([a], [b]) => a.localeCompare(b, 'ru', { numeric: true }));
  $('rules-body').innerHTML = rules.length ? rules.map(([rule, item]) => `<tr><td>${escapeHtml(rule)}</td><td>${number(item.checks, 0)}</td><td>${number(item.errors, 0)}</td><td>${number(item.warnings, 0)}</td></tr>`).join('') : '<tr><td colspan="4">Нет сводки</td></tr>';
  const diagnostics = report.diagnostics || []; $('diagnostic-list').className = 'diagnostic-list';
  $('diagnostic-list').innerHTML = diagnostics.length ? diagnostics.map((item, index) => `<article class="diagnostic-item ${item.severity === 'ERROR' ? 'error' : ''}"><strong>${escapeHtml(item.severity)} · ${escapeHtml(item.rule || 'без правила')} · ${escapeHtml(item.objectId || 'общая')}</strong><div>${escapeHtml(item.message)}</div>${item.objectId != null ? `<button data-object="${escapeHtml(item.objectId)}" data-index="${index}">Показать объект</button>` : ''}</article>`).join('') : '<div class="empty-content">Диагностика пуста: нарушения не найдены.</div>';
  for (const button of $('diagnostic-list').querySelectorAll('button[data-object]')) button.addEventListener('click', () => focusObject(button.dataset.object));
  $('limitations').innerHTML = (report.limitations || []).map(item => `<li>${escapeHtml(item)}</li>`).join('') || '<li>Ограничения проверки не указаны.</li>';
  renderViolations();
}

function renderViolations() {
  clearLayer('violations'); if (!state.report || !$('show-violations').checked) return;
  const ids = new Set((state.report.diagnostics || []).map(item => key(item.objectId)));
  const features = [...(state.original?.features || []), ...variantFeatures()].filter(feature => feature.geometry && ids.has(key(feature.properties?.id)));
  if (!features.length) return;
  const layer = L.geoJSON({ type: 'FeatureCollection', features }, {
    pane: 'violations', style: () => ({ color: palette.danger, weight: 8, opacity: .85, fillOpacity: .28 }),
    pointToLayer: (feature, latlng) => L.circleMarker(latlng, { radius: 11, color: palette.danger, weight: 4, fillOpacity: .28 }),
    onEachFeature: (feature, featureLayer) => {
      const issues = state.report.diagnostics.filter(item => key(item.objectId) === key(feature.properties.id));
      featureLayer.bindPopup(`<h4 class="popup-title">Диагностика</h4>${issues.map(item => `<p>${escapeHtml(item.severity)} ${escapeHtml(item.rule)}: ${escapeHtml(item.message)}</p>`).join('')}`);
      state.featureLayers.set(key(feature.properties.id), featureLayer);
    }
  });
  replaceLayer('violations', layer);
}

function focusObject(objectId) {
  if (!$('show-violations').checked) { $('show-violations').checked = true; renderViolations(); }
  const layer = state.featureLayers.get(key(objectId)); if (!layer) { toast(`Объект ${objectId} не входит в выбранный вариант`, true); return; }
  if (layer.getBounds) map.fitBounds(layer.getBounds().pad(.35)); else if (layer.getLatLng) map.setView(layer.getLatLng(), 18);
  layer.openPopup();
}

function switchTab(name) {
  for (const button of document.querySelectorAll('.tabs button')) button.classList.toggle('active', button.dataset.tab === name);
  for (const content of document.querySelectorAll('.tab-content')) content.classList.toggle('active', content.id === `tab-${name}`);
}

$('source-file').addEventListener('change', event => selectFile(event.target.files[0]));
for (const eventName of ['dragenter', 'dragover']) $('drop-zone').addEventListener(eventName, event => { event.preventDefault(); $('drop-zone').classList.add('dragging'); });
for (const eventName of ['dragleave', 'drop']) $('drop-zone').addEventListener(eventName, event => { event.preventDefault(); $('drop-zone').classList.remove('dragging'); });
$('drop-zone').addEventListener('drop', event => selectFile(event.dataTransfer.files[0]));
$('start').addEventListener('click', startCalculation); $('cancel').addEventListener('click', cancelCalculation);
$('variant').addEventListener('change', () => { renderRoute(); fitLayers(['route']); });
$('fit-all').addEventListener('click', () => fitLayers(['restrictions', 'existing', 'points', 'route', 'violations']));
$('fit-route').addEventListener('click', () => fitLayers(['route', 'violations']));
for (const name of ['restrictions', 'existing', 'route', 'points']) $(`layer-${name}`).addEventListener('change', event => {
  const layer = state.layers[name]; if (!layer) return; if (event.target.checked) layer.addTo(map); else map.removeLayer(layer);
});
$('layer-labels').addEventListener('change', renderRoute); $('show-violations').addEventListener('change', renderViolations);
for (const button of document.querySelectorAll('.tabs button')) button.addEventListener('click', () => switchTab(button.dataset.tab));
window.addEventListener('resize', () => map.invalidateSize());

checkHealth(); resetAnalytics();
