package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.ReadableUsersAuthenticator
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.service.{IssueNoteRepository, IssuePeriodRepository, IssueReportService, IssueTableSettings, IssueTableSettingsRepository}

class IssueTableController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator {

  /** GET /:owner/:repository/issues/issue-table — フィルタ付きIssue一覧ページ */
  get("/:owner/:repository/issues/issue-table")(readableUsersOnly { repository =>
    contentType = "text/html; charset=UTF-8"
    val ctx = request.getContextPath

    val dataUrl     = s"$ctx/${HtmlUtil.escHtml(repository.owner)}/${HtmlUtil.escHtml(repository.name)}/issues/issue-table/data"
    val settingsUrl = s"$ctx/${HtmlUtil.escHtml(repository.owner)}/${HtmlUtil.escHtml(repository.name)}/issues/issue-table/settings"

    val content =
      s"""<style>
         |  #ir-table thead th { position:sticky; top:0; z-index:2; cursor:pointer; user-select:none; background:#f0f0f0; }
         |  #ir-table thead th:hover { background:#e0e0e0; }
         |  #ir-table thead th .sort-arrow { font-size:10px; color:#666; margin-left:3px; }
         |  #ir-tbody tr:nth-child(even) { background:#f9f9f9; }
         |  #ir-tbody tr:hover { background:#eef4ff !important; }
         |  .df-group { display:flex; align-items:center; gap:4px; font-size:12px; }
         |  .df-group label { margin:0; font-weight:bold; color:#555; white-space:nowrap; }
         |  .df-group input[type=date] { font-size:12px; height:28px; padding:2px 4px; border:1px solid #ccc; border-radius:3px; }
         |  .df-sep { color:#888; }
         |  #ir-settings-modal { display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.5); z-index:10000; justify-content:center; align-items:flex-start; padding-top:40px; box-sizing:border-box; }
         |  #ir-settings-dialog { background:#fff; border-radius:6px; padding:20px; width:620px; max-width:95vw; max-height:80vh; overflow-y:auto; box-shadow:0 4px 20px rgba(0,0,0,0.3); }
         |  .ir-stab-content { display:none; }
         |  .ir-stab-content.active { display:block; }
         |  #ir-col-order-table { width:100%; border-collapse:collapse; font-size:12px; }
         |  #ir-col-order-table th { background:#f5f5f5; padding:4px 6px; border:1px solid #ddd; }
         |  #ir-col-order-table td { padding:3px 6px; border-bottom:1px solid #f0f0f0; }
         |  #ir-highlight-rules-table { width:100%; border-collapse:collapse; font-size:12px; }
         |  #ir-highlight-rules-table th, #ir-highlight-rules-table td { padding:4px 6px; border:1px solid #ddd; }
         |  #ir-highlight-rules-table th { background:#f5f5f5; }
         |</style>
         |<!-- 行1: カテゴリフィルタ -->
         |<div style="margin-bottom:6px;display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
         |  <select id="f-assignee" class="form-control input-sm" style="width:auto;">
         |    <option value="">担当者（全員）</option>
         |  </select>
         |  <select id="f-status" class="form-control input-sm" style="width:auto;">
         |    <option value="">状態（全て）</option>
         |    <option value="open">open</option>
         |    <option value="closed">closed</option>
         |  </select>
         |  <select id="f-milestone" class="form-control input-sm" style="width:auto;">
         |    <option value="">マイルストーン（全て）</option>
         |  </select>
         |  <select id="f-label" class="form-control input-sm" style="width:auto;">
         |    <option value="">ラベル（全て）</option>
         |  </select>
         |  <select id="f-waiting" class="form-control input-sm" style="width:auto;">
         |    <option value="">確認待ち（全て）</option>
         |    <option value="yes">確認待ちのみ</option>
         |    <option value="no">確認待ちでない</option>
         |  </select>
         |  <input type="text" id="f-text" class="form-control input-sm" style="width:200px;" placeholder="全列インクリメンタル検索...">
         |  <button class="btn btn-sm btn-default" id="f-clear">クリア</button>
         |  <span id="ir-count" style="margin-left:4px;color:#666;font-size:12px;"></span>
         |  <button id="ir-settings-btn" class="btn btn-sm btn-default" style="margin-left:auto;">&#9881; 設定</button>
         |</div>
         |<!-- 行2: 日付フィルタ -->
         |<div id="ir-date-row" style="margin-bottom:10px;display:flex;flex-wrap:wrap;gap:12px;align-items:center;padding:7px 10px;background:#f7f7f7;border:1px solid #e0e0e0;border-radius:4px;">
         |  <span style="font-size:11px;font-weight:bold;color:#666;white-space:nowrap;">日付フィルタ</span>
         |  <div class="df-group"><label>作成日</label><input type="date" id="f-created-from"><span class="df-sep">〜</span><input type="date" id="f-created-to"></div>
         |  <div class="df-group"><label>更新日</label><input type="date" id="f-updated-from"><span class="df-sep">〜</span><input type="date" id="f-updated-to"></div>
         |  <div id="ir-gantt-dates" style="display:flex;gap:12px;flex-wrap:wrap;">
         |    <div class="df-group"><label>開始予定日</label><input type="date" id="f-start-from"><span class="df-sep">〜</span><input type="date" id="f-start-to"></div>
         |    <div class="df-group"><label>完了予定日</label><input type="date" id="f-end-from"><span class="df-sep">〜</span><input type="date" id="f-end-to"></div>
         |  </div>
         |</div>
         |<div id="ir-table-wrapper" style="overflow-x:auto; overflow-y:auto;">
         |  <table class="table table-bordered table-condensed" id="ir-table" style="font-size:13px;white-space:nowrap;width:100%;">
         |    <thead></thead>
         |    <tbody id="ir-tbody">
         |      <tr><td colspan="17" style="text-align:center;color:#888;">読み込み中...</td></tr>
         |    </tbody>
         |  </table>
         |</div>
         |<!-- 設定モーダル -->
         |<div id="ir-settings-modal">
         |  <div id="ir-settings-dialog">
         |    <h4 style="margin-top:0;">Issue一覧 表示設定</h4>
         |    <div style="display:flex;gap:4px;margin-bottom:12px;border-bottom:1px solid #ddd;padding-bottom:8px;">
         |      <button id="ir-tab-btn-col" class="btn btn-sm btn-primary">列設定</button>
         |      <button id="ir-tab-btn-vis" class="btn btn-sm btn-default">表示設定</button>
         |    </div>
         |    <div id="ir-stab-col" class="ir-stab-content active">
         |      <p style="font-size:12px;color:#666;margin-bottom:8px;">表示する列を選択し、↑↓で順序を変更できます。</p>
         |      <table id="ir-col-order-table">
         |        <thead><tr><th style="width:36px;">表示</th><th>列名</th><th style="width:70px;">順序</th></tr></thead>
         |        <tbody id="ir-col-order-body"></tbody>
         |      </table>
         |    </div>
         |    <div id="ir-stab-vis" class="ir-stab-content">
         |      <p style="font-size:12px;color:#666;margin-bottom:8px;">行の背景色を条件に応じて変更できます。上のルールが優先されます。</p>
         |      <table id="ir-highlight-rules-table">
         |        <thead>
         |          <tr>
         |            <th>有効</th><th>対象フィールド</th><th>条件</th><th>日数</th><th>色</th><th>順序</th><th>削除</th>
         |          </tr>
         |        </thead>
         |        <tbody id="ir-highlight-rules-body"></tbody>
         |      </table>
         |      <button id="ir-add-rule" class="btn btn-sm btn-default" style="margin-top:8px;">＋ ルール追加</button>
         |    </div>
         |    <div style="margin-top:16px;display:flex;gap:8px;justify-content:flex-end;">
         |      <button id="ir-settings-save" class="btn btn-sm btn-primary">保存</button>
         |      <button id="ir-settings-cancel" class="btn btn-sm btn-default">キャンセル</button>
         |    </div>
         |  </div>
         |</div>""".stripMargin

    val script =
      s"""(function () {
         |  var allIssues      = [];
         |  var sortColIdx     = 0;
         |  var sortDir        = 1; // 1=昇順, -1=降順
         |  var activeCols     = [];
         |  var currentSettings = null;
         |  var modalColRows   = [];
         |  var modalRules     = [];
         |
         |  /* ---- HTML エスケープ ---- */
         |  function esc(s) {
         |    return String(s == null ? '' : s)
         |      .replace(/&/g,'&amp;').replace(/</g,'&lt;')
         |      .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
         |  }
         |
         |  /* ---- 日付文字列を比較可能な "yyyy-MM-dd" に正規化 ---- */
         |  function normDate(str) {
         |    if (!str) return '';
         |    return str.substring(0, 10).replace(/\\//g, '-');
         |  }
         |
         |  /* ---- 日付を表示用 "yyyy/MM/dd" にフォーマット ---- */
         |  function fmtDate(str) {
         |    if (!str) return '';
         |    return str.substring(0, 10).replace(/-/g, '/');
         |  }
         |
         |  /* ---- COLUMNS 定義（全17列） ---- */
         |  var COLUMNS = [
         |    { key: 'issue_id',
         |      label: '#',
         |      val: function(i){ return i.issueId; },
         |      render: function(i){ return '<td><a href="'+esc(i.url)+'" target="_blank">#'+i.issueId+'</a></td>'; }
         |    },
         |    { key: 'title',
         |      label: 'タイトル',
         |      val: function(i){ return (i.title||'').toLowerCase(); },
         |      render: function(i){ return '<td style="white-space:normal;max-width:300px;">'+esc(i.title)+'</td>'; }
         |    },
         |    { key: 'status',
         |      label: '状態',
         |      val: function(i){ return i.closed ? 1 : 0; },
         |      render: function(i){
         |        var badge = i.closed
         |          ? '<span class="label" style="background:#6f42c1;color:#fff;font-size:11px;">closed</span>'
         |          : '<span class="label label-success" style="font-size:11px;">open</span>';
         |        return '<td>'+badge+'</td>';
         |      }
         |    },
         |    { key: 'creator',
         |      label: '作成者',
         |      val: function(i){ return (i.creator||'').toLowerCase(); },
         |      render: function(i){ return '<td>'+esc(i.creator)+'</td>'; }
         |    },
         |    { key: 'assignee',
         |      label: '担当者',
         |      val: function(i){ return (i.assignee||'').toLowerCase(); },
         |      render: function(i){ return '<td>'+esc(i.assignee)+'</td>'; }
         |    },
         |    { key: 'milestone',
         |      label: 'マイルストーン',
         |      val: function(i){ return (i.milestone||'').toLowerCase(); },
         |      render: function(i){ return '<td>'+esc(i.milestone)+'</td>'; }
         |    },
         |    { key: 'labels',
         |      label: 'ラベル',
         |      val: function(i){ return (i.labels||'').toLowerCase(); },
         |      render: function(i){ return '<td>'+esc(i.labels)+'</td>'; }
         |    },
         |    { key: 'waiting',
         |      label: '確認待ち',
         |      val: function(i){ return i.waitingForConfirmation ? 1 : 0; },
         |      render: function(i){ return '<td style="text-align:center;">'+(i.waitingForConfirmation ? '&#10003;' : '')+'</td>'; }
         |    },
         |    { key: 'conf_detail',
         |      label: '確認詳細',
         |      val: function(i){ return (i.confirmationDetail||'').toLowerCase(); },
         |      render: function(i){ return '<td style="white-space:normal;max-width:200px;">'+esc(i.confirmationDetail)+'</td>'; }
         |    },
         |    { key: 'ms_due',
         |      label: 'マイルストーン期日',
         |      val: function(i){ return (i.milestoneDueDate||'').toLowerCase(); },
         |      render: function(i){ return '<td>'+esc(i.milestoneDueDate)+'</td>'; }
         |    },
         |    { key: 'note',
         |      label: '備考',
         |      val: function(i){ return (i.note||'').toLowerCase(); },
         |      render: function(i){ return '<td style="white-space:normal;max-width:200px;">'+esc(i.note)+'</td>'; }
         |    },
         |    { key: 'comments',
         |      label: 'コメント',
         |      val: function(i){ return i.commentCount; },
         |      render: function(i){ return '<td style="text-align:right;">'+i.commentCount+'</td>'; }
         |    },
         |    { key: 'created_at',
         |      label: '作成日',
         |      val: function(i){ return i.createdAt||''; },
         |      render: function(i){ return '<td>'+fmtDate(i.createdAt)+'</td>'; }
         |    },
         |    { key: 'updated_at',
         |      label: '更新日',
         |      val: function(i){ return i.updatedAt||''; },
         |      render: function(i){ return '<td>'+fmtDate(i.updatedAt)+'</td>'; }
         |    },
         |    { key: 'start_date',
         |      label: '開始予定日',
         |      val: function(i){ return (i.period && i.period.startDate) || ''; },
         |      render: function(i){ var p=i.period||{}; return '<td>'+fmtDate(p.startDate||'')+'</td>'; }
         |    },
         |    { key: 'end_date',
         |      label: '完了予定日',
         |      val: function(i){ return (i.period && i.period.endDate) || ''; },
         |      render: function(i){ var p=i.period||{}; return '<td>'+fmtDate(p.endDate||'')+'</td>'; }
         |    },
         |    { key: 'progress',
         |      label: '進捗(%)',
         |      val: function(i){ return (i.period && i.period.progress != null) ? i.period.progress : -1; },
         |      render: function(i){ var p=i.period||{}; return '<td style="text-align:right;">'+(p.progress != null ? p.progress : '')+'</td>'; }
         |    },
         |    { key: 'estimated_hours',
         |      label: '見積工数(h)',
         |      val: function(i){ return (i.period && i.period.estimatedHours != null) ? i.period.estimatedHours : -1; },
         |      render: function(i){ var p=i.period||{}; return '<td style="text-align:right;">'+(p.estimatedHours != null ? p.estimatedHours : '')+'</td>'; }
         |    },
         |    { key: 'actual_hours',
         |      label: '実績工数(h)',
         |      val: function(i){ return (i.period && i.period.actualHours != null) ? i.period.actualHours : -1; },
         |      render: function(i){ var p=i.period||{}; return '<td style="text-align:right;">'+(p.actualHours != null ? p.actualHours : '')+'</td>'; }
         |    }
         |  ];
         |
         |  var DEFAULT_COL_ORDER = COLUMNS.map(function(c){ return c.key; }).join(',');
         |  var DEFAULT_HIGHLIGHT_RULES = [
         |    {enabled:true, field:'ms_due',   cond:'overdue', days:0, color:'#ffe0e0'},
         |    {enabled:true, field:'end_date', cond:'overdue', days:0, color:'#ffe0e0'}
         |  ];
         |
         |  /* ---- 列キーから activeCols を構築 ---- */
         |  function applyColumnOrder(colOrderStr) {
         |    var keys = (colOrderStr && colOrderStr.trim()) ? colOrderStr.split(',') : DEFAULT_COL_ORDER.split(',');
         |    var colMap = {};
         |    COLUMNS.forEach(function(c){ colMap[c.key] = c; });
         |    var cols = [];
         |    keys.forEach(function(k){ var c = colMap[k.trim()]; if (c) cols.push(c); });
         |    activeCols = cols.length ? cols : COLUMNS.slice();
         |  }
         |
         |  /* ---- thead を動的生成 ---- */
         |  function buildThead() {
         |    var html = '<tr id="ir-thead-row">';
         |    activeCols.forEach(function(col) {
         |      html += '<th>'+col.label+'<span class="sort-arrow"></span></th>';
         |    });
         |    html += '</tr>';
         |    document.querySelector('#ir-table thead').innerHTML = html;
         |    sortColIdx = Math.min(sortColIdx, Math.max(0, activeCols.length - 1));
         |    addSortHandlers();
         |  }
         |
         |  /* ---- issue の日付フィールド値を返す ---- */
         |  function getFieldDate(issue, field) {
         |    switch(field) {
         |      case 'ms_due':     return normDate(issue.milestoneDueDate);
         |      case 'end_date':   return normDate(issue.period && issue.period.endDate);
         |      case 'start_date': return normDate(issue.period && issue.period.startDate);
         |      case 'created_at': return normDate(issue.createdAt);
         |      case 'updated_at': return normDate(issue.updatedAt);
         |      default: return '';
         |    }
         |  }
         |
         |  /* ---- ハイライトルールにマッチする最初の色を返す ---- */
         |  function matchHighlightColor(issue, rules) {
         |    var today = new Date().toISOString().substring(0, 10);
         |    for (var i = 0; i < rules.length; i++) {
         |      var r = rules[i];
         |      if (!r.enabled) continue;
         |      var d = getFieldDate(issue, r.field);
         |      if (!d) continue;
         |      var matched = false;
         |      if (r.cond === 'overdue') {
         |        matched = d < today;
         |      } else if (r.cond === 'within') {
         |        var threshold = new Date();
         |        threshold.setDate(threshold.getDate() + (r.days || 0));
         |        matched = d <= threshold.toISOString().substring(0, 10);
         |      }
         |      if (matched) return r.color;
         |    }
         |    return null;
         |  }
         |
         |  /* ---- tbody 描画 ---- */
         |  function renderRows(issues) {
         |    var rules = (currentSettings && currentSettings.highlightRules) || DEFAULT_HIGHLIGHT_RULES;
         |    if (!issues.length) {
         |      document.getElementById('ir-tbody').innerHTML =
         |        '<tr><td colspan="'+activeCols.length+'" style="text-align:center;color:#888;">該当するIssueがありません</td></tr>';
         |      return;
         |    }
         |    var html = '';
         |    for (var i = 0; i < issues.length; i++) {
         |      var issue = issues[i];
         |      var color = matchHighlightColor(issue, rules);
         |      html += '<tr'+(color ? ' style="background:'+color+';"' : '')+'>';
         |      activeCols.forEach(function(col) { html += col.render(issue); });
         |      html += '</tr>';
         |    }
         |    document.getElementById('ir-tbody').innerHTML = html;
         |  }
         |
         |  /* ---- ソートインジケータ更新 ---- */
         |  function updateSortIndicators() {
         |    var spans = document.querySelectorAll('#ir-thead-row th .sort-arrow');
         |    for (var i = 0; i < spans.length; i++) spans[i].textContent = '';
         |    var ths = document.querySelectorAll('#ir-thead-row th');
         |    if (ths[sortColIdx]) {
         |      var sp = ths[sortColIdx].querySelector('.sort-arrow');
         |      if (sp) sp.textContent = sortDir === 1 ? ' ▲' : ' ▼';
         |    }
         |  }
         |
         |  /* ---- ヘッダクリックでソート ---- */
         |  function addSortHandlers() {
         |    var ths = document.querySelectorAll('#ir-thead-row th');
         |    for (var i = 0; i < ths.length; i++) {
         |      (function(col) {
         |        ths[col].addEventListener('click', function() {
         |          if (sortColIdx === col) { sortDir = -sortDir; }
         |          else { sortColIdx = col; sortDir = 1; }
         |          applyFilterAndSort();
         |        });
         |      })(i);
         |    }
         |    updateSortIndicators();
         |  }
         |
         |  /* ---- フィルタ＋ソート＋描画 ---- */
         |  function applyFilterAndSort() {
         |    var fAssignee    = document.getElementById('f-assignee').value;
         |    var fStatus      = document.getElementById('f-status').value;
         |    var fMilestone   = document.getElementById('f-milestone').value;
         |    var fLabel       = document.getElementById('f-label').value;
         |    var fWaiting     = document.getElementById('f-waiting').value;
         |    var fText        = document.getElementById('f-text').value.toLowerCase();
         |    var fCreatedFrom = document.getElementById('f-created-from').value;
         |    var fCreatedTo   = document.getElementById('f-created-to').value;
         |    var fUpdatedFrom = document.getElementById('f-updated-from').value;
         |    var fUpdatedTo   = document.getElementById('f-updated-to').value;
         |    var fStartFrom   = document.getElementById('f-start-from').value;
         |    var fStartTo     = document.getElementById('f-start-to').value;
         |    var fEndFrom     = document.getElementById('f-end-from').value;
         |    var fEndTo       = document.getElementById('f-end-to').value;
         |
         |    var filtered = allIssues.filter(function(issue) {
         |      if (fAssignee  && issue.assignee  !== fAssignee)  return false;
         |      if (fMilestone && issue.milestone !== fMilestone) return false;
         |      if (fLabel) {
         |        var lbls = issue.labels ? issue.labels.split(', ') : [];
         |        if (lbls.indexOf(fLabel) === -1) return false;
         |      }
         |      if (fStatus === 'open'   &&  issue.closed) return false;
         |      if (fStatus === 'closed' && !issue.closed) return false;
         |      if (fWaiting === 'yes' && !issue.waitingForConfirmation) return false;
         |      if (fWaiting === 'no'  &&  issue.waitingForConfirmation) return false;
         |
         |      var created = normDate(issue.createdAt);
         |      var updated = normDate(issue.updatedAt);
         |      if (fCreatedFrom && created < fCreatedFrom) return false;
         |      if (fCreatedTo   && created > fCreatedTo)   return false;
         |      if (fUpdatedFrom && updated < fUpdatedFrom) return false;
         |      if (fUpdatedTo   && updated > fUpdatedTo)   return false;
         |      if (issue.period) {
         |        var pStart = normDate(issue.period.startDate);
         |        var pEnd   = normDate(issue.period.endDate);
         |        if (fStartFrom && pStart && pStart < fStartFrom) return false;
         |        if (fStartTo   && pStart && pStart > fStartTo)   return false;
         |        if (fEndFrom   && pEnd   && pEnd   < fEndFrom)   return false;
         |        if (fEndTo     && pEnd   && pEnd   > fEndTo)     return false;
         |      }
         |
         |      if (fText) {
         |        var hay = [
         |          issue.issueId, issue.title, issue.assignee, issue.milestone,
         |          issue.labels, issue.confirmationDetail, issue.note,
         |          issue.createdAt, issue.updatedAt, issue.milestoneDueDate, issue.closedDate, issue.creator
         |        ].join(' ').toLowerCase();
         |        if (issue.period) {
         |          hay += ' ' + [issue.period.startDate, issue.period.endDate].join(' ').toLowerCase();
         |        }
         |        if (hay.indexOf(fText) === -1) return false;
         |      }
         |      return true;
         |    });
         |
         |    filtered.sort(function(a, b) {
         |      var col = activeCols[sortColIdx];
         |      if (!col) return 0;
         |      var av = col.val(a);
         |      var bv = col.val(b);
         |      if (av < bv) return -sortDir;
         |      if (av > bv) return  sortDir;
         |      return 0;
         |    });
         |
         |    renderRows(filtered);
         |    document.getElementById('ir-count').textContent = filtered.length + '件';
         |    updateSortIndicators();
         |  }
         |
         |  /* ---- セレクトに選択肢を追加 ---- */
         |  function populateSelect(id, values) {
         |    var sel   = document.getElementById(id);
         |    var first = sel.options[0];
         |    sel.innerHTML = '';
         |    sel.appendChild(first);
         |    values.forEach(function(v) {
         |      if (!v) return;
         |      var opt = document.createElement('option');
         |      opt.value = v; opt.textContent = v;
         |      sel.appendChild(opt);
         |    });
         |  }
         |
         |  /* ---- テーブル高さ動的設定 ---- */
         |  function updateTableHeight() {
         |    var wrapper = document.getElementById('ir-table-wrapper');
         |    if (!wrapper) return;
         |    var rect = wrapper.getBoundingClientRect();
         |    wrapper.style.maxHeight = Math.max(200, window.innerHeight - rect.top - 20) + 'px';
         |  }
         |  window.addEventListener('resize', updateTableHeight);
         |
         |  /* ---- 設定 API ---- */
         |  function loadSettings(callback) {
         |    var xhr = new XMLHttpRequest();
         |    xhr.open('GET', '$settingsUrl', true);
         |    xhr.onload = function() {
         |      var s = {columnOrder: DEFAULT_COL_ORDER, highlightRules: DEFAULT_HIGHLIGHT_RULES};
         |      if (xhr.status === 200) {
         |        try {
         |          var parsed = JSON.parse(xhr.responseText);
         |          s.columnOrder    = parsed.columnOrder    || DEFAULT_COL_ORDER;
         |          s.highlightRules = parsed.highlightRules || DEFAULT_HIGHLIGHT_RULES;
         |        } catch(e) {}
         |      }
         |      callback(s);
         |    };
         |    xhr.onerror = function() {
         |      callback({columnOrder: DEFAULT_COL_ORDER, highlightRules: DEFAULT_HIGHLIGHT_RULES});
         |    };
         |    xhr.send();
         |  }
         |
         |  function saveSettings(s, callback) {
         |    var xhr = new XMLHttpRequest();
         |    xhr.open('POST', '$settingsUrl', true);
         |    xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
         |    xhr.onload = function() { callback(xhr.status === 200); };
         |    xhr.onerror = function() { callback(false); };
         |    xhr.send(
         |      'columnOrder=' + encodeURIComponent(s.columnOrder) +
         |      '&highlightRules=' + encodeURIComponent(JSON.stringify(s.highlightRules))
         |    );
         |  }
         |
         |  /* ---- 設定モーダル ---- */
         |  function openSettingsModal() {
         |    var colOrder    = (currentSettings && currentSettings.columnOrder) || DEFAULT_COL_ORDER;
         |    var visibleKeys = colOrder.split(',').map(function(k){ return k.trim(); }).filter(Boolean);
         |    var visibleSet  = {};
         |    visibleKeys.forEach(function(k){ visibleSet[k] = true; });
         |    modalColRows = [];
         |    visibleKeys.forEach(function(k) {
         |      var col = null;
         |      COLUMNS.forEach(function(c){ if (c.key === k) col = c; });
         |      if (col) modalColRows.push({key: col.key, label: col.label, visible: true});
         |    });
         |    COLUMNS.forEach(function(c) {
         |      if (!visibleSet[c.key]) modalColRows.push({key: c.key, label: c.label, visible: false});
         |    });
         |    var rules = (currentSettings && currentSettings.highlightRules) || DEFAULT_HIGHLIGHT_RULES;
         |    modalRules = JSON.parse(JSON.stringify(rules));
         |    renderColOrderTable();
         |    renderHighlightRulesTable();
         |    showTab('col');
         |    document.getElementById('ir-settings-modal').style.display = 'flex';
         |  }
         |
         |  function closeSettingsModal() {
         |    document.getElementById('ir-settings-modal').style.display = 'none';
         |  }
         |
         |  function showTab(name) {
         |    document.getElementById('ir-stab-col').classList.remove('active');
         |    document.getElementById('ir-stab-vis').classList.remove('active');
         |    document.getElementById('ir-tab-btn-col').className = 'btn btn-sm btn-default';
         |    document.getElementById('ir-tab-btn-vis').className = 'btn btn-sm btn-default';
         |    document.getElementById('ir-stab-' + name).classList.add('active');
         |    document.getElementById('ir-tab-btn-' + name).className = 'btn btn-sm btn-primary';
         |  }
         |
         |  function renderColOrderTable() {
         |    var tbody = document.getElementById('ir-col-order-body');
         |    var html = '';
         |    modalColRows.forEach(function(row, idx) {
         |      html += '<tr>';
         |      html += '<td style="text-align:center;"><input type="checkbox" data-col-idx="'+idx+'"'+(row.visible ? ' checked' : '')+'></td>';
         |      html += '<td>'+esc(row.label)+'</td>';
         |      html += '<td>';
         |      if (idx > 0)                       html += '<button class="btn btn-xs btn-default" data-col-up="'+idx+'">↑</button>';
         |      if (idx < modalColRows.length - 1) html += '<button class="btn btn-xs btn-default" data-col-down="'+idx+'">↓</button>';
         |      html += '</td></tr>';
         |    });
         |    tbody.innerHTML = html;
         |
         |    var cbs = tbody.querySelectorAll('input[type=checkbox]');
         |    for (var i = 0; i < cbs.length; i++) {
         |      cbs[i].addEventListener('change', function() {
         |        modalColRows[parseInt(this.getAttribute('data-col-idx'))].visible = this.checked;
         |      });
         |    }
         |    var upBtns = tbody.querySelectorAll('[data-col-up]');
         |    for (var i = 0; i < upBtns.length; i++) {
         |      upBtns[i].addEventListener('click', function() {
         |        var idx = parseInt(this.getAttribute('data-col-up'));
         |        var tmp = modalColRows[idx-1]; modalColRows[idx-1] = modalColRows[idx]; modalColRows[idx] = tmp;
         |        renderColOrderTable();
         |      });
         |    }
         |    var dnBtns = tbody.querySelectorAll('[data-col-down]');
         |    for (var i = 0; i < dnBtns.length; i++) {
         |      dnBtns[i].addEventListener('click', function() {
         |        var idx = parseInt(this.getAttribute('data-col-down'));
         |        var tmp = modalColRows[idx+1]; modalColRows[idx+1] = modalColRows[idx]; modalColRows[idx] = tmp;
         |        renderColOrderTable();
         |      });
         |    }
         |  }
         |
         |  function renderHighlightRulesTable() {
         |    var tbody = document.getElementById('ir-highlight-rules-body');
         |    var fieldOpts =
         |      '<option value="ms_due">マイルストーン期日</option>' +
         |      '<option value="end_date">完了予定日</option>' +
         |      '<option value="start_date">開始予定日</option>' +
         |      '<option value="created_at">作成日</option>' +
         |      '<option value="updated_at">更新日</option>';
         |    var html = '';
         |    modalRules.forEach(function(rule, idx) {
         |      var daysDisabled = (rule.cond === 'overdue') ? ' disabled' : '';
         |      html += '<tr>';
         |      html += '<td style="text-align:center;"><input type="checkbox" data-re="'+idx+'"'+(rule.enabled ? ' checked' : '')+'></td>';
         |      html += '<td><select data-rf="'+idx+'">'+fieldOpts+'</select></td>';
         |      html += '<td><select data-rc="'+idx+'"><option value="overdue">超過</option><option value="within">N日以内</option></select></td>';
         |      html += '<td><input type="number" data-rd="'+idx+'" value="'+(rule.days||0)+'" min="0" style="width:52px;"'+daysDisabled+'></td>';
         |      html += '<td><input type="color" data-rk="'+idx+'" value="'+(rule.color||'#ffe0e0')+'"></td>';
         |      html += '<td>';
         |      if (idx > 0)                    html += '<button class="btn btn-xs btn-default" data-ru="'+idx+'">↑</button>';
         |      if (idx < modalRules.length - 1) html += '<button class="btn btn-xs btn-default" data-rw="'+idx+'">↓</button>';
         |      html += '</td>';
         |      html += '<td><button class="btn btn-xs btn-danger" data-rx="'+idx+'">&#10005;</button></td>';
         |      html += '</tr>';
         |    });
         |    tbody.innerHTML = html;
         |
         |    /* field select 初期値 + イベント */
         |    var rfSels = tbody.querySelectorAll('[data-rf]');
         |    for (var i = 0; i < rfSels.length; i++) {
         |      rfSels[i].value = modalRules[parseInt(rfSels[i].getAttribute('data-rf'))].field || 'ms_due';
         |      rfSels[i].addEventListener('change', function() {
         |        modalRules[parseInt(this.getAttribute('data-rf'))].field = this.value;
         |      });
         |    }
         |    /* cond select 初期値 + イベント */
         |    var rcSels = tbody.querySelectorAll('[data-rc]');
         |    for (var i = 0; i < rcSels.length; i++) {
         |      rcSels[i].value = modalRules[parseInt(rcSels[i].getAttribute('data-rc'))].cond || 'overdue';
         |      (function(sel) {
         |        sel.addEventListener('change', function() {
         |          var idx = parseInt(this.getAttribute('data-rc'));
         |          modalRules[idx].cond = this.value;
         |          var dInp = tbody.querySelector('[data-rd="'+idx+'"]');
         |          if (dInp) dInp.disabled = (this.value === 'overdue');
         |        });
         |      })(rcSels[i]);
         |    }
         |    /* enabled チェックボックス */
         |    var reCbs = tbody.querySelectorAll('[data-re]');
         |    for (var i = 0; i < reCbs.length; i++) {
         |      reCbs[i].addEventListener('change', function() {
         |        modalRules[parseInt(this.getAttribute('data-re'))].enabled = this.checked;
         |      });
         |    }
         |    /* days input */
         |    var rdInps = tbody.querySelectorAll('[data-rd]');
         |    for (var i = 0; i < rdInps.length; i++) {
         |      rdInps[i].addEventListener('input', function() {
         |        modalRules[parseInt(this.getAttribute('data-rd'))].days = parseInt(this.value) || 0;
         |      });
         |    }
         |    /* color input */
         |    var rkInps = tbody.querySelectorAll('[data-rk]');
         |    for (var i = 0; i < rkInps.length; i++) {
         |      rkInps[i].addEventListener('input', function() {
         |        modalRules[parseInt(this.getAttribute('data-rk'))].color = this.value;
         |      });
         |    }
         |    /* ↑ ボタン */
         |    var ruBtns = tbody.querySelectorAll('[data-ru]');
         |    for (var i = 0; i < ruBtns.length; i++) {
         |      ruBtns[i].addEventListener('click', function() {
         |        var idx = parseInt(this.getAttribute('data-ru'));
         |        syncRulesFromDOM(tbody);
         |        var tmp = modalRules[idx-1]; modalRules[idx-1] = modalRules[idx]; modalRules[idx] = tmp;
         |        renderHighlightRulesTable();
         |      });
         |    }
         |    /* ↓ ボタン */
         |    var rwBtns = tbody.querySelectorAll('[data-rw]');
         |    for (var i = 0; i < rwBtns.length; i++) {
         |      rwBtns[i].addEventListener('click', function() {
         |        var idx = parseInt(this.getAttribute('data-rw'));
         |        syncRulesFromDOM(tbody);
         |        var tmp = modalRules[idx+1]; modalRules[idx+1] = modalRules[idx]; modalRules[idx] = tmp;
         |        renderHighlightRulesTable();
         |      });
         |    }
         |    /* 削除ボタン */
         |    var rxBtns = tbody.querySelectorAll('[data-rx]');
         |    for (var i = 0; i < rxBtns.length; i++) {
         |      rxBtns[i].addEventListener('click', function() {
         |        syncRulesFromDOM(tbody);
         |        modalRules.splice(parseInt(this.getAttribute('data-rx')), 1);
         |        renderHighlightRulesTable();
         |      });
         |    }
         |  }
         |
         |  /* ---- DOM から modalRules を同期（↑↓・削除前に呼ぶ） ---- */
         |  function syncRulesFromDOM(tbody) {
         |    var rfSels = tbody.querySelectorAll('[data-rf]');
         |    for (var i = 0; i < rfSels.length; i++) {
         |      var idx = parseInt(rfSels[i].getAttribute('data-rf'));
         |      if (idx < modalRules.length) modalRules[idx].field = rfSels[i].value;
         |    }
         |    var rcSels = tbody.querySelectorAll('[data-rc]');
         |    for (var i = 0; i < rcSels.length; i++) {
         |      var idx = parseInt(rcSels[i].getAttribute('data-rc'));
         |      if (idx < modalRules.length) modalRules[idx].cond = rcSels[i].value;
         |    }
         |    var rdInps = tbody.querySelectorAll('[data-rd]');
         |    for (var i = 0; i < rdInps.length; i++) {
         |      var idx = parseInt(rdInps[i].getAttribute('data-rd'));
         |      if (idx < modalRules.length) modalRules[idx].days = parseInt(rdInps[i].value) || 0;
         |    }
         |    var rkInps = tbody.querySelectorAll('[data-rk]');
         |    for (var i = 0; i < rkInps.length; i++) {
         |      var idx = parseInt(rkInps[i].getAttribute('data-rk'));
         |      if (idx < modalRules.length) modalRules[idx].color = rkInps[i].value;
         |    }
         |    var reCbs = tbody.querySelectorAll('[data-re]');
         |    for (var i = 0; i < reCbs.length; i++) {
         |      var idx = parseInt(reCbs[i].getAttribute('data-re'));
         |      if (idx < modalRules.length) modalRules[idx].enabled = reCbs[i].checked;
         |    }
         |  }
         |
         |  /* ---- データ取得 ---- */
         |  function loadData() {
         |    var xhr = new XMLHttpRequest();
         |    xhr.open('GET', '$dataUrl', true);
         |    xhr.onload = function() {
         |      if (xhr.status !== 200) {
         |        document.getElementById('ir-tbody').innerHTML =
         |          '<tr><td colspan="'+activeCols.length+'" style="color:red;text-align:center;">データ取得に失敗しました (HTTP '+xhr.status+')</td></tr>';
         |        return;
         |      }
         |      var data;
         |      try { data = JSON.parse(xhr.responseText); }
         |      catch(e) {
         |        document.getElementById('ir-tbody').innerHTML =
         |          '<tr><td colspan="'+activeCols.length+'" style="color:red;text-align:center;">JSON解析エラー: '+e.message+'</td></tr>';
         |        return;
         |      }
         |
         |      allIssues = data.issues || [];
         |      populateSelect('f-assignee',  data.users      || []);
         |      populateSelect('f-milestone', data.milestones || []);
         |      populateSelect('f-label',     data.labels     || []);
         |
         |      ['f-assignee','f-status','f-milestone','f-label','f-waiting'].forEach(function(id) {
         |        document.getElementById(id).addEventListener('change', applyFilterAndSort);
         |      });
         |      document.getElementById('f-text').addEventListener('input', applyFilterAndSort);
         |      ['f-created-from','f-created-to','f-updated-from','f-updated-to',
         |       'f-start-from','f-start-to','f-end-from','f-end-to'].forEach(function(id) {
         |        document.getElementById(id).addEventListener('change', applyFilterAndSort);
         |      });
         |      document.getElementById('f-clear').addEventListener('click', function() {
         |        ['f-assignee','f-status','f-milestone','f-label','f-waiting'].forEach(function(id) {
         |          document.getElementById(id).value = '';
         |        });
         |        document.getElementById('f-text').value = '';
         |        ['f-created-from','f-created-to','f-updated-from','f-updated-to',
         |         'f-start-from','f-start-to','f-end-from','f-end-to'].forEach(function(id) {
         |          document.getElementById(id).value = '';
         |        });
         |        applyFilterAndSort();
         |      });
         |
         |      applyFilterAndSort();
         |      updateTableHeight();
         |    };
         |    xhr.onerror = function() {
         |      document.getElementById('ir-tbody').innerHTML =
         |        '<tr><td colspan="'+activeCols.length+'" style="color:red;text-align:center;">通信エラー</td></tr>';
         |    };
         |    xhr.send();
         |  }
         |
         |  /* ---- モーダルのボタンイベント登録 ---- */
         |  document.getElementById('ir-settings-btn').addEventListener('click', openSettingsModal);
         |  document.getElementById('ir-settings-cancel').addEventListener('click', closeSettingsModal);
         |  document.getElementById('ir-settings-modal').addEventListener('click', function(e) {
         |    if (e.target === this) closeSettingsModal();
         |  });
         |  document.getElementById('ir-tab-btn-col').addEventListener('click', function(){ showTab('col'); });
         |  document.getElementById('ir-tab-btn-vis').addEventListener('click', function(){ showTab('vis'); });
         |  document.getElementById('ir-add-rule').addEventListener('click', function() {
         |    modalRules.push({enabled:true, field:'ms_due', cond:'overdue', days:0, color:'#ffe0e0'});
         |    renderHighlightRulesTable();
         |  });
         |  document.getElementById('ir-settings-save').addEventListener('click', function() {
         |    var btn = this;
         |    btn.disabled = true; btn.textContent = '保存中...';
         |    var colOrder = modalColRows.filter(function(r){ return r.visible; })
         |                              .map(function(r){ return r.key; }).join(',');
         |    var newSettings = {
         |      columnOrder:    colOrder || DEFAULT_COL_ORDER,
         |      highlightRules: modalRules
         |    };
         |    saveSettings(newSettings, function(ok) {
         |      btn.disabled = false; btn.textContent = '保存';
         |      if (ok) {
         |        currentSettings = newSettings;
         |        applyColumnOrder(newSettings.columnOrder);
         |        buildThead();
         |        applyFilterAndSort();
         |        closeSettingsModal();
         |      } else {
         |        alert('設定の保存に失敗しました。');
         |      }
         |    });
         |  });
         |
         |  /* ---- 初期化フロー: 設定読込 → thead 構築 → データ取得 ---- */
         |  loadSettings(function(s) {
         |    currentSettings = s;
         |    applyColumnOrder(s.columnOrder || DEFAULT_COL_ORDER);
         |    buildThead();
         |    loadData();
         |  });
         |})();""".stripMargin

    HtmlUtil.pageShell(
      title       = s"Issue一覧 - ${repository.owner}/${repository.name}",
      owner       = repository.owner,
      repo        = repository.name,
      pageIcon    = "list-unordered",
      pageTitle   = s"Issue一覧 — ${HtmlUtil.escHtml(repository.owner)}/${HtmlUtil.escHtml(repository.name)}",
      content     = content,
      extraScript = script,
      wideLayout  = true,
      contextPath = ctx
    )
  })

  /** GET /:owner/:repository/issues/issue-table/settings — 設定を JSON で返す */
  get("/:owner/:repository/issues/issue-table/settings")(readableUsersOnly { repository =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn

    val defaultColOrder = "issue_id,title,status,creator,assignee,milestone,labels,waiting,conf_detail,ms_due,note,comments,created_at,updated_at,start_date,end_date,progress,estimated_hours,actual_hours"
    val defaultHlRules  = """[{"enabled":true,"field":"ms_due","cond":"overdue","days":0,"color":"#ffe0e0"},{"enabled":true,"field":"end_date","cond":"overdue","days":0,"color":"#ffe0e0"}]"""

    IssueTableSettingsRepository.findByRepo(conn, repository.owner, repository.name) match {
      case Some(st) =>
        val colOrder = if (st.columnOrder.nonEmpty) st.columnOrder else defaultColOrder
        val hlRules  = if (st.highlightRules.nonEmpty) st.highlightRules else defaultHlRules
        s"""{"columnOrder":"${HtmlUtil.escJson(colOrder)}","highlightRules":$hlRules}"""
      case None =>
        s"""{"columnOrder":"$defaultColOrder","highlightRules":$defaultHlRules}"""
    }
  })

  /** POST /:owner/:repository/issues/issue-table/settings — 設定を DB に保存 */
  post("/:owner/:repository/issues/issue-table/settings")(readableUsersOnly { repository =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn

    val colOrder       = params.getOrElse("columnOrder", "")
    val highlightRules = params.getOrElse("highlightRules", "")
    IssueTableSettingsRepository.upsert(conn, IssueTableSettings(
      owner          = repository.owner,
      repository     = repository.name,
      columnOrder    = colOrder,
      highlightRules = highlightRules
    ))
    """{"ok":true}"""
  })

  /** GET /:owner/:repository/issues/issue-table/data — Issue一覧をJSONで返す */
  get("/:owner/:repository/issues/issue-table/data")(readableUsersOnly { repository =>
    contentType = "application/json; charset=UTF-8"
    val owner    = repository.owner
    val repoName = repository.name

    implicit val session = request2Session(request)
    val conn = session.conn

    val scheme  = request.getScheme
    val host    = request.getServerName
    val port    = request.getServerPort
    val baseUrl = if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443))
      s"$scheme://$host"
    else
      s"$scheme://$host:$port"

    val notes   = IssueNoteRepository.findAllNotes(conn, owner, repoName)
    val periods = IssuePeriodRepository.findAllPeriods(conn, owner, repoName)
    val issues  = IssueReportService.mergeWithPeriods(
      IssueReportService.mergeWithNotes(
        IssueReportService.loadIssues(conn, owner, repoName, baseUrl), notes),
      periods)

    val users      = issues.map(_.assignee).filter(_.nonEmpty).distinct.sorted
    val milestones = issues.map(_.milestone).filter(_.nonEmpty).distinct.sorted
    val labels     = issues.flatMap(_.labels.split(", ").map(_.trim)).filter(_.nonEmpty).distinct.sorted

    val issueJsons = issues.map { i =>
      val startDate      = HtmlUtil.escJson(i.startDate.getOrElse(""))
      val endDate        = HtmlUtil.escJson(i.endDate.getOrElse(""))
      val progressStr    = i.progress.map(_.toString).getOrElse("null")
      val estHoursStr    = i.estimatedHours.map(_.toString).getOrElse("null")
      val actHoursStr    = i.actualHours.map(_.toString).getOrElse("null")
      s"""{"issueId":${i.issueId},"title":"${HtmlUtil.escJson(i.title)}","closed":${i.closed},"assignee":"${HtmlUtil.escJson(i.assignee)}","milestone":"${HtmlUtil.escJson(i.milestone)}","labels":"${HtmlUtil.escJson(i.labels)}","createdAt":"${HtmlUtil.escJson(i.createdAt)}","updatedAt":"${HtmlUtil.escJson(i.updatedAt)}","milestoneDueDate":"${HtmlUtil.escJson(i.milestoneDueDate)}","closedDate":"${HtmlUtil.escJson(i.closedDate)}","creator":"${HtmlUtil.escJson(i.creator)}","commentCount":${i.commentCount},"url":"${HtmlUtil.escJson(i.url)}","note":"${HtmlUtil.escJson(i.note)}","waitingForConfirmation":${i.waitingForConfirmation},"confirmationDetail":"${HtmlUtil.escJson(i.confirmationDetail)}","period":{"startDate":"$startDate","endDate":"$endDate","progress":$progressStr,"estimatedHours":$estHoursStr,"actualHours":$actHoursStr}}"""
    }.mkString(",")

    def jsonArray(xs: Seq[String]) =
      xs.map(s => s""""${HtmlUtil.escJson(s)}"""").mkString("[", ",", "]")

    s"""{"issues":[$issueJsons],"users":${jsonArray(users)},"milestones":${jsonArray(milestones)},"labels":${jsonArray(labels)}}"""
  })
}
