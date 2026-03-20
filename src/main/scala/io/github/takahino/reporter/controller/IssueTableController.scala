package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.ReadableUsersAuthenticator
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.service.{IssueNoteRepository, IssuePeriodRepository, IssueReportService}

class IssueTableController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator {

  /** GET /:owner/:repository/issues/issue-table — フィルタ付きIssue一覧ページ */
  get("/:owner/:repository/issues/issue-table")(readableUsersOnly { repository =>
    contentType = "text/html; charset=UTF-8"

    val dataUrl = s"/${HtmlUtil.escHtml(repository.owner)}/${HtmlUtil.escHtml(repository.name)}/issues/issue-table/data"

    val content =
      s"""<style>
         |  #ir-table thead th { cursor:pointer; user-select:none; background:#f0f0f0; }
         |  #ir-table thead th:hover { background:#e0e0e0; }
         |  #ir-table thead th .sort-arrow { font-size:10px; color:#666; margin-left:3px; }
         |  #ir-tbody tr:nth-child(even) td { background:#f9f9f9; }
         |  #ir-tbody tr:hover td { background:#eef4ff !important; }
         |  .df-group { display:flex; align-items:center; gap:4px; font-size:12px; }
         |  .df-group label { margin:0; font-weight:bold; color:#555; white-space:nowrap; }
         |  .df-group input[type=date] { font-size:12px; height:28px; padding:2px 4px; border:1px solid #ccc; border-radius:3px; }
         |  .df-sep { color:#888; }
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
         |<div style="overflow-x:auto;">
         |  <table class="table table-bordered table-condensed" id="ir-table" style="font-size:13px;white-space:nowrap;width:100%;">
         |    <thead>
         |      <tr id="ir-thead-row">
         |        <th>#</th>
         |        <th>タイトル</th>
         |        <th>状態</th>
         |        <th>担当者</th>
         |        <th>マイルストーン</th>
         |        <th>ラベル</th>
         |        <th>確認待ち</th>
         |        <th>確認詳細</th>
         |        <th>マイルストーン期日</th>
         |        <th>備考</th>
         |        <th>コメント</th>
         |        <th>作成日</th>
         |        <th>更新日</th>
         |        <th>開始予定日</th>
         |        <th>完了予定日</th>
         |        <th>進捗(%)</th>
         |      </tr>
         |    </thead>
         |    <tbody id="ir-tbody">
         |      <tr><td colspan="16" style="text-align:center;color:#888;">読み込み中...</td></tr>
         |    </tbody>
         |  </table>
         |</div>""".stripMargin

    val script =
      s"""(function () {
         |  var allIssues = [];
         |  var colCount  = 16;
         |  var sortCol   = 0;
         |  var sortDir   = 1; // 1=昇順, -1=降順
         |
         |  /* ---- HTML エスケープ ---- */
         |  function esc(s) {
         |    return String(s == null ? '' : s)
         |      .replace(/&/g,'&amp;').replace(/</g,'&lt;')
         |      .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
         |  }
         |
         |  /* ---- ソートキー取得（列インデックス → issue フィールド値） ---- */
         |  function colVal(issue, col) {
         |    switch (col) {
         |      case  0: return issue.issueId;
         |      case  1: return (issue.title               || '').toLowerCase();
         |      case  2: return issue.closed ? 1 : 0;
         |      case  3: return (issue.assignee            || '').toLowerCase();
         |      case  4: return (issue.milestone           || '').toLowerCase();
         |      case  5: return (issue.labels              || '').toLowerCase();
         |      case  6: return issue.waitingForConfirmation ? 1 : 0;
         |      case  7: return (issue.confirmationDetail  || '').toLowerCase();
         |      case  8: return (issue.milestoneDueDate || '').toLowerCase();
         |      case  9: return (issue.note                || '').toLowerCase();
         |      case 10: return issue.commentCount;
         |      case 11: return issue.createdAt || '';
         |      case 12: return issue.updatedAt || '';
         |      case 13: return (issue.period && issue.period.startDate)    || '';
         |      case 14: return (issue.period && issue.period.endDate)      || '';
         |      case 15: return (issue.period && issue.period.progress != null) ? issue.period.progress : -1;
         |      default: return '';
         |    }
         |  }
         |
         |  /* ---- 日付文字列を比較可能な "yyyy-MM-dd" に正規化 ---- */
         |  function normDate(str) {
         |    if (!str) return '';
         |    return str.substring(0, 10).replace(/\\//g, '-');
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
         |      /* 日付範囲フィルタ */
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
         |          hay += ' ' + [
         |            issue.period.startDate, issue.period.endDate
         |          ].join(' ').toLowerCase();
         |        }
         |        if (hay.indexOf(fText) === -1) return false;
         |      }
         |      return true;
         |    });
         |
         |    filtered.sort(function(a, b) {
         |      var av = colVal(a, sortCol);
         |      var bv = colVal(b, sortCol);
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
         |  /* ---- tbody 描画 ---- */
         |  function renderRows(issues) {
         |    if (!issues.length) {
         |      document.getElementById('ir-tbody').innerHTML =
         |        '<tr><td colspan="' + colCount + '" style="text-align:center;color:#888;">該当するIssueがありません</td></tr>';
         |      return;
         |    }
         |    var html = '';
         |    for (var i = 0; i < issues.length; i++) {
         |      var issue = issues[i];
         |      var statusBadge = issue.closed
         |        ? '<span class="label" style="background:#6f42c1;color:#fff;font-size:11px;">closed</span>'
         |        : '<span class="label label-success" style="font-size:11px;">open</span>';
         |      html += '<tr>';
         |      html += '<td><a href="' + esc(issue.url) + '" target="_blank">#' + issue.issueId + '</a></td>';
         |      html += '<td style="white-space:normal;max-width:300px;">' + esc(issue.title) + '</td>';
         |      html += '<td>' + statusBadge + '</td>';
         |      html += '<td>' + esc(issue.assignee) + '</td>';
         |      html += '<td>' + esc(issue.milestone) + '</td>';
         |      html += '<td>' + esc(issue.labels) + '</td>';
         |      html += '<td style="text-align:center;">' + (issue.waitingForConfirmation ? '&#10003;' : '') + '</td>';
         |      html += '<td style="white-space:normal;max-width:200px;">' + esc(issue.confirmationDetail) + '</td>';
         |      html += '<td>' + esc(issue.milestoneDueDate) + '</td>';
         |      html += '<td style="white-space:normal;max-width:200px;">' + esc(issue.note) + '</td>';
         |      html += '<td style="text-align:right;">' + issue.commentCount + '</td>';
         |      html += '<td>' + esc(issue.createdAt) + '</td>';
         |      html += '<td>' + esc(issue.updatedAt) + '</td>';
         |      var p = issue.period || {};
         |      html += '<td>' + esc(p.startDate || '') + '</td>';
         |      html += '<td>' + esc(p.endDate   || '') + '</td>';
         |      html += '<td style="text-align:right;">' + (p.progress != null ? p.progress : '') + '</td>';
         |      html += '</tr>';
         |    }
         |    document.getElementById('ir-tbody').innerHTML = html;
         |  }
         |
         |  /* ---- ソートインジケータ更新 ---- */
         |  function updateSortIndicators() {
         |    var ths = document.querySelectorAll('#ir-thead-row th .sort-arrow');
         |    for (var i = 0; i < ths.length; i++) ths[i].textContent = '';
         |    var arrows = document.querySelectorAll('#ir-thead-row th');
         |    if (arrows[sortCol]) {
         |      var span = arrows[sortCol].querySelector('.sort-arrow');
         |      if (span) span.textContent = sortDir === 1 ? ' ▲' : ' ▼';
         |    }
         |  }
         |
         |  /* ---- ヘッダクリックでソート ---- */
         |  function addSortHandlers() {
         |    var ths = document.querySelectorAll('#ir-thead-row th');
         |    for (var i = 0; i < ths.length; i++) {
         |      /* sort-arrow スパンを追加 */
         |      var span = document.createElement('span');
         |      span.className = 'sort-arrow';
         |      ths[i].appendChild(span);
         |      /* クリックハンドラ（クロージャで列番号を束縛） */
         |      (function(col) {
         |        ths[col].addEventListener('click', function() {
         |          if (sortCol === col) { sortDir = -sortDir; }
         |          else { sortCol = col; sortDir = 1; }
         |          applyFilterAndSort();
         |        });
         |      })(i);
         |    }
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
         |  /* ---- データ取得 ---- */
         |  var xhr = new XMLHttpRequest();
         |  xhr.open('GET', '$dataUrl', true);
         |  xhr.onload = function() {
         |    if (xhr.status !== 200) {
         |      document.getElementById('ir-tbody').innerHTML =
         |        '<tr><td colspan="' + colCount + '" style="color:red;text-align:center;">データ取得に失敗しました (HTTP ' + xhr.status + ')</td></tr>';
         |      return;
         |    }
         |    var data;
         |    try { data = JSON.parse(xhr.responseText); }
         |    catch(e) {
         |      document.getElementById('ir-tbody').innerHTML =
         |        '<tr><td colspan="' + colCount + '" style="color:red;text-align:center;">JSON解析エラー: ' + e.message + '</td></tr>';
         |      return;
         |    }
         |
         |    allIssues = data.issues || [];
         |
         |    populateSelect('f-assignee',  data.users      || []);
         |    populateSelect('f-milestone', data.milestones || []);
         |    populateSelect('f-label',     data.labels     || []);
         |
         |    /* フィルタイベント登録 */
         |    ['f-assignee','f-status','f-milestone','f-label','f-waiting'].forEach(function(id) {
         |      document.getElementById(id).addEventListener('change', applyFilterAndSort);
         |    });
         |    document.getElementById('f-text').addEventListener('input', applyFilterAndSort);
         |    /* 日付フィルタ（期間フィルタ含む） */
         |    ['f-created-from','f-created-to','f-updated-from','f-updated-to',
         |     'f-start-from','f-start-to','f-end-from','f-end-to'].forEach(function(id) {
         |      document.getElementById(id).addEventListener('change', applyFilterAndSort);
         |    });
         |    document.getElementById('f-clear').addEventListener('click', function() {
         |      ['f-assignee','f-status','f-milestone','f-label','f-waiting'].forEach(function(id) {
         |        document.getElementById(id).value = '';
         |      });
         |      document.getElementById('f-text').value = '';
         |      ['f-created-from','f-created-to','f-updated-from','f-updated-to',
         |       'f-start-from','f-start-to','f-end-from','f-end-to'].forEach(function(id) {
         |        document.getElementById(id).value = '';
         |      });
         |      applyFilterAndSort();
         |    });
         |
         |    addSortHandlers();
         |    applyFilterAndSort();
         |  };
         |  xhr.onerror = function() {
         |    document.getElementById('ir-tbody').innerHTML =
         |      '<tr><td colspan="' + colCount + '" style="color:red;text-align:center;">通信エラー</td></tr>';
         |  };
         |  xhr.send();
         |})();""".stripMargin

    HtmlUtil.pageShell(
      title       = s"Issue一覧 - ${repository.owner}/${repository.name}",
      owner       = repository.owner,
      repo        = repository.name,
      pageIcon    = "list-unordered",
      pageTitle   = s"Issue一覧 — ${HtmlUtil.escHtml(repository.owner)}/${HtmlUtil.escHtml(repository.name)}",
      content     = content,
      extraScript = script,
      wideLayout  = true
    )
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
      val startDate   = HtmlUtil.escJson(i.startDate.getOrElse(""))
      val endDate     = HtmlUtil.escJson(i.endDate.getOrElse(""))
      val progressStr = i.progress.map(_.toString).getOrElse("null")
      s"""{"issueId":${i.issueId},"title":"${HtmlUtil.escJson(i.title)}","closed":${i.closed},"assignee":"${HtmlUtil.escJson(i.assignee)}","milestone":"${HtmlUtil.escJson(i.milestone)}","labels":"${HtmlUtil.escJson(i.labels)}","createdAt":"${HtmlUtil.escJson(i.createdAt)}","updatedAt":"${HtmlUtil.escJson(i.updatedAt)}","milestoneDueDate":"${HtmlUtil.escJson(i.milestoneDueDate)}","closedDate":"${HtmlUtil.escJson(i.closedDate)}","creator":"${HtmlUtil.escJson(i.creator)}","commentCount":${i.commentCount},"url":"${HtmlUtil.escJson(i.url)}","note":"${HtmlUtil.escJson(i.note)}","waitingForConfirmation":${i.waitingForConfirmation},"confirmationDetail":"${HtmlUtil.escJson(i.confirmationDetail)}","period":{"startDate":"$startDate","endDate":"$endDate","progress":$progressStr}}"""
    }.mkString(",")

    def jsonArray(xs: Seq[String]) =
      xs.map(s => s""""${HtmlUtil.escJson(s)}"""").mkString("[", ",", "]")

    s"""{"issues":[$issueJsons],"users":${jsonArray(users)},"milestones":${jsonArray(milestones)},"labels":${jsonArray(labels)}}"""
  })
}
