package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.ReadableUsersAuthenticator
import gitbucket.core.util.Implicits._

class GanttController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator {

  /** GET /:owner/:repository/issues/gantt — HTML ページ */
  get("/:owner/:repository/issues/gantt")(readableUsersOnly { repository =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn  = session.conn
    val owner = repository.owner
    val repo  = repository.name
    val ctx   = request.getContextPath
    val ctxJs = HtmlUtil.escJs(ctx)

    // マイルストーン一覧
    val milestones: Seq[(Int, String)] = {
      val ps = conn.prepareStatement(
        "SELECT MILESTONE_ID, TITLE FROM MILESTONE " +
        "WHERE USER_NAME = ? AND REPOSITORY_NAME = ? " +
        "ORDER BY DUE_DATE NULLS LAST, MILESTONE_ID"
      )
      ps.setString(1, owner)
      ps.setString(2, repo)
      val rs  = ps.executeQuery()
      val buf = scala.collection.mutable.ArrayBuffer.empty[(Int, String)]
      while (rs.next()) buf += ((rs.getInt(1), rs.getString(2)))
      rs.close(); ps.close()
      buf.toSeq
    }

    // ラベル一覧
    val labels: Seq[(Int, String)] = {
      val ps = conn.prepareStatement(
        "SELECT LABEL_ID, LABEL_NAME FROM LABEL " +
        "WHERE USER_NAME = ? AND REPOSITORY_NAME = ? " +
        "ORDER BY LABEL_NAME"
      )
      ps.setString(1, owner)
      ps.setString(2, repo)
      val rs  = ps.executeQuery()
      val buf = scala.collection.mutable.ArrayBuffer.empty[(Int, String)]
      while (rs.next()) buf += ((rs.getInt(1), rs.getString(2)))
      rs.close(); ps.close()
      buf.toSeq
    }

    // 担当者一覧
    val assignees: Seq[String] = {
      val ps = conn.prepareStatement(
        "SELECT DISTINCT ASSIGNEE_USER_NAME FROM ISSUE_ASSIGNEE " +
        "WHERE USER_NAME = ? AND REPOSITORY_NAME = ? " +
        "ORDER BY ASSIGNEE_USER_NAME"
      )
      ps.setString(1, owner)
      ps.setString(2, repo)
      val rs  = ps.executeQuery()
      val buf = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) buf += rs.getString(1)
      rs.close(); ps.close()
      buf.toSeq
    }

    val ownerE = HtmlUtil.escHtml(owner)
    val repoE  = HtmlUtil.escHtml(repo)

    val msOptions = milestones.map { case (id, title) =>
      s"""<option value="$id">${HtmlUtil.escHtml(title)}</option>"""
    }.mkString("\n")

    val lblOptions = labels.map { case (id, name) =>
      s"""<option value="$id">${HtmlUtil.escHtml(name)}</option>"""
    }.mkString("\n")

    val asnOptions = assignees.map { name =>
      s"""<option value="${HtmlUtil.escHtml(name)}">${HtmlUtil.escHtml(name)}</option>"""
    }.mkString("\n")

    val dataApiBase = s"$ctxJs/$ownerE/$repoE/issues/gantt/data"

    val content =
      s"""<div id="gt-sticky-top" style="position:sticky;top:0;z-index:50;background:#fff;padding:8px 0 4px;">
         |  <div style="margin-bottom:8px;display:flex;gap:12px;flex-wrap:wrap;align-items:center;">
         |    <div>
         |      <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">マイルストーン</label>
         |      <select id="gt-milestone" class="form-control input-sm">
         |        <option value="">すべて</option>
         |        $msOptions
         |      </select>
         |    </div>
         |    <div>
         |      <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">ラベル</label>
         |      <select id="gt-label" class="form-control input-sm">
         |        <option value="">すべて</option>
         |        $lblOptions
         |      </select>
         |    </div>
         |    <div>
         |      <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">担当者</label>
         |      <select id="gt-assignee" class="form-control input-sm">
         |        <option value="">すべて</option>
         |        $asnOptions
         |      </select>
         |    </div>
         |    <div>
         |      <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">状態</label>
         |      <select id="gt-status-filter" class="form-control input-sm">
         |        <option value="">すべて</option>
         |        <option value="open">open</option>
         |        <option value="closed">closed</option>
         |      </select>
         |    </div>
         |    <div>
         |      <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">ソート</label>
         |      <div style="display:flex;gap:4px;align-items:center;">
         |        <select id="gt-sort-key" class="form-control input-sm">
         |          <option value="start_date">開始予定日</option>
         |          <option value="end_date">完了予定日</option>
         |          <option value="issue_id">Issue番号</option>
         |          <option value="progress">進捗(%)</option>
         |          <option value="assignee">担当者</option>
         |          <option value="title">タイトル</option>
         |        </select>
         |        <button id="gt-sort-dir" class="btn btn-sm btn-default" title="昇順/降順切替" style="white-space:nowrap;">&#9650; 昇順</button>
         |      </div>
         |    </div>
         |    <div>
         |      <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">未設定期限（日）</label>
         |      <input id="gt-default-days" type="number" min="1" max="365" value="7"
         |        class="form-control input-sm" style="width:70px;"
         |        title="完了予定日が未設定のIssueに適用する仮の期限（今日から何日後か）">
         |    </div>
         |    <div>
         |      <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">
         |        <input type="checkbox" id="gt-show-missing" style="margin-right:4px;">
         |        完了日も未設定を含む
         |      </label>
         |    </div>
         |    <span id="gt-status" style="font-size:12px;color:#888;align-self:flex-end;padding-bottom:4px;"></span>
         |  </div>
         |  <div style="display:flex;flex-wrap:wrap;gap:12px;align-items:center;padding:6px 10px;background:#f7f7f7;border:1px solid #e0e0e0;border-radius:4px;font-size:12px;">
         |    <span style="font-weight:bold;color:#555;white-space:nowrap;">バー色の凡例</span>
         |    <span style="display:inline-flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:14px;border-radius:3px;background:#ea4335;"></span>未着手 (0%)</span>
         |    <span style="display:inline-flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:14px;border-radius:3px;background:#ffa726;"></span>進行中 (1〜66%)</span>
         |    <span style="display:inline-flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:14px;border-radius:3px;background:#66bb6a;"></span>もうすぐ完了 (67〜99%)</span>
         |    <span style="display:inline-flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:14px;border-radius:3px;background:#26a69a;"></span>完了 (100%)</span>
         |    <span style="display:inline-flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:14px;border-radius:3px;background:#4285f4;"></span>進捗未設定</span>
         |    <span style="display:inline-flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:14px;border-radius:3px;background:#9e9e9e;"></span>クローズ済み</span>
         |    <span style="display:inline-flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:14px;border-radius:3px;border:2.5px solid #e53935;box-sizing:border-box;"></span>期限超過（未クローズ）</span>
         |  </div>
         |</div>
         |<div id="gt-chart-outer" style="overflow:auto;max-height:calc(100vh - 220px);margin-top:4px;">
         |  <div id="gt-axis-row" style="position:sticky;top:0;z-index:10;background:#fff;height:60px;">
         |    <canvas id="gt-axis-canvas" style="display:block;"></canvas>
         |  </div>
         |  <div id="gt-chart-inner" style="position:relative;">
         |    <canvas id="gt-chart"></canvas>
         |    <div id="gt-empty" style="display:none;text-align:center;color:#888;padding:60px 0;">
         |      表示するIssueがありません
         |    </div>
         |  </div>
         |</div>
         |""".stripMargin

    val extraScript =
      s"""
         |(function() {
         |  var CHART_JS_URL = '$ctxJs/ir-assets/chart.min.js';
         |  var DATA_API = '$dataApiBase';
         |  var chartInstance = null;
         |  var axisChartInstance = null;
         |
         |  function buildUrl() {
         |    var ms   = document.getElementById('gt-milestone').value;
         |    var lbl  = document.getElementById('gt-label').value;
         |    var asn  = document.getElementById('gt-assignee').value;
         |    var miss = document.getElementById('gt-show-missing').checked;
         |    var params = [];
         |    if (ms)   params.push('milestone='    + encodeURIComponent(ms));
         |    if (lbl)  params.push('label='         + encodeURIComponent(lbl));
         |    if (asn)  params.push('assignee='      + encodeURIComponent(asn));
         |    if (miss) params.push('showMissing=true');
         |    return DATA_API + (params.length ? '?' + params.join('&') : '');
         |  }
         |
         |  function statusFilter() {
         |    return document.getElementById('gt-status-filter').value;
         |  }
         |
         |  var sortAsc = true;
         |  function sortKey() { return document.getElementById('gt-sort-key').value; }
         |
         |  function sortIssues(issues) {
         |    var key = sortKey();
         |    var dir = sortAsc ? 1 : -1;
         |    return issues.slice().sort(function(a, b) {
         |      var av, bv;
         |      switch (key) {
         |        case 'start_date': av = a.startDate || '9999-99-99'; bv = b.startDate || '9999-99-99'; break;
         |        case 'end_date':   av = a.endDate   || '9999-99-99'; bv = b.endDate   || '9999-99-99'; break;
         |        case 'issue_id':   av = a.id;  bv = b.id;  break;
         |        case 'progress':   av = (a.progress !== null && a.progress !== undefined) ? a.progress : -1;
         |                           bv = (b.progress !== null && b.progress !== undefined) ? b.progress : -1; break;
         |        case 'assignee':   av = (a.assignee || '').toLowerCase(); bv = (b.assignee || '').toLowerCase(); break;
         |        case 'title':      av = (a.title    || '').toLowerCase(); bv = (b.title    || '').toLowerCase(); break;
         |        default:           av = a.id; bv = b.id;
         |      }
         |      if (av < bv) return -dir;
         |      if (av > bv) return  dir;
         |      return (a.id - b.id) * dir;
         |    });
         |  }
         |
         |  function barColor(issue) {
         |    if (issue.closed)                     return '#9e9e9e';
         |    if (issue.progress === null || issue.progress === undefined) return '#4285f4';
         |    if (issue.progress === 0)              return '#ea4335';
         |    if (issue.progress < 67)               return '#ffa726';
         |    if (issue.progress < 100)              return '#66bb6a';
         |    return '#26a69a';
         |  }
         |
         |  function dateDiffDays(a, b) {
         |    var da = new Date(a + 'T00:00:00');
         |    var db = new Date(b + 'T00:00:00');
         |    return Math.round((db - da) / 86400000);
         |  }
         |
         |  function offsetToDate(minDate, offset) {
         |    var d = new Date(minDate + 'T00:00:00');
         |    d.setDate(d.getDate() + offset);
         |    return d.toISOString().slice(0, 10);
         |  }
         |
         |  function loadChart() {
         |    var statusEl = document.getElementById('gt-status');
         |    statusEl.textContent = '読み込み中...';
         |    var xhr = new XMLHttpRequest();
         |    xhr.open('GET', buildUrl(), true);
         |    xhr.onload = function() {
         |      if (xhr.status !== 200) {
         |        statusEl.textContent = 'データ取得エラー (' + xhr.status + ')';
         |        return;
         |      }
         |      statusEl.textContent = '';
         |      var d;
         |      try { d = JSON.parse(xhr.responseText); }
         |      catch(e) { statusEl.textContent = 'JSONパースエラー: ' + e.message; return; }
         |      if (d.error) { statusEl.textContent = 'サーバーエラー: ' + d.error; return; }
         |      window._gtLastData = d;
         |      try { renderChart(d); }
         |      catch(e) { statusEl.textContent = 'チャートエラー: ' + e.message; console.error(e); }
         |    };
         |    xhr.onerror = function() { statusEl.textContent = '通信エラー'; };
         |    xhr.send();
         |  }
         |
         |  function renderChart(d) {
         |    var canvas     = document.getElementById('gt-chart');
         |    var axisCanvas = document.getElementById('gt-axis-canvas');
         |    var emptyEl    = document.getElementById('gt-empty');
         |    var inner      = document.getElementById('gt-chart-inner');
         |
         |    if (chartInstance)     { chartInstance.destroy();     chartInstance = null; }
         |    if (axisChartInstance) { axisChartInstance.destroy(); axisChartInstance = null; }
         |
         |    if (!d.issues || d.issues.length === 0) {
         |      canvas.style.display     = 'none';
         |      axisCanvas.style.display = 'none';
         |      emptyEl.style.display    = 'block';
         |      return;
         |    }
         |    canvas.style.display     = '';
         |    axisCanvas.style.display = '';
         |    emptyEl.style.display    = 'none';
         |
         |    var sf = statusFilter();
         |    var issues = sortIssues((d.issues || []).filter(function(iss) {
         |      if (sf === 'open')   return !iss.closed;
         |      if (sf === 'closed') return  iss.closed;
         |      return true;
         |    }));
         |    var minDate = issues.reduce(function(min, iss) {
         |      var sd = iss.startDate || iss.createdAt || null;
         |      if (!sd) return min;
         |      return (!min || sd < min) ? sd : min;
         |    }, d.minDate) || d.minDate;
         |
         |    var h = Math.max(200, issues.length * 32 + 50);
         |    inner.style.height = h + 'px';
         |
         |    var labels = issues.map(function(iss) {
         |      return '#' + iss.id + ' ' + iss.title.slice(0, 30) + (iss.title.length > 30 ? '…' : '');
         |    });
         |
         |    var todayStr = new Date().toISOString().slice(0, 10);
         |    function addDays(dateStr, n) {
         |      var d = new Date(dateStr + 'T00:00:00');
         |      d.setDate(d.getDate() + n);
         |      return d.toISOString().slice(0, 10);
         |    }
         |    function addMonths(dateStr, n) {
         |      var d = new Date(dateStr + 'T00:00:00');
         |      d.setMonth(d.getMonth() + n);
         |      return d.toISOString().slice(0, 10);
         |    }
         |
         |    var defaultDays = parseInt(document.getElementById('gt-default-days').value) || 7;
         |    var fallbackEnd = addDays(todayStr, defaultDays);
         |    var offsets = issues.map(function(iss) {
         |      var sd = iss.startDate || iss.createdAt || todayStr;
         |      return Math.max(0, dateDiffDays(minDate, sd));
         |    });
         |    var durations = issues.map(function(iss) {
         |      var sd  = iss.startDate || iss.createdAt || todayStr;
         |      var ed  = iss.endDate || fallbackEnd;
         |      return Math.max(1, dateDiffDays(sd, ed));
         |    });
         |    var colors = issues.map(barColor);
         |
         |    // x スケールの最大値を明示的に計算して両チャートで共有
         |    var xMax = 1;
         |    for (var i = 0; i < issues.length; i++) {
         |      var v = offsets[i] + durations[i];
         |      if (v > xMax) xMax = v;
         |    }
         |    xMax += 2;
         |
         |    var vlines = [
         |      { date: todayStr,                color: 'rgba(30,30,30,0.85)',   label: '今日' },
         |      { date: addDays(todayStr, 7),    color: 'rgba(210,100,0,0.80)', label: '1週間後' },
         |      { date: addMonths(todayStr, 1),  color: 'rgba(140,0,200,0.75)', label: '1ヶ月後' }
         |    ];
         |
         |    // ---- 軸専用チャートのプラグイン (縦線ラベルのみ描画) ----
         |    var axisVlinesPlugin = {
         |      id: 'axisVlines',
         |      afterDraw: function(chart) {
         |        var ctx    = chart.ctx;
         |        var xScale = chart.scales.x;
         |        vlines.forEach(function(vl) {
         |          var offset = dateDiffDays(minDate, vl.date);
         |          if (offset < xScale.min || offset > xScale.max) return;
         |          var px = xScale.getPixelForValue(offset);
         |          ctx.save();
         |          ctx.fillStyle = vl.color;
         |          ctx.font = 'bold 11px sans-serif';
         |          ctx.textAlign = 'center';
         |          ctx.fillText(vl.label, px, 14);
         |          ctx.restore();
         |        });
         |      }
         |    };
         |
         |    var xTickConfig = {
         |      callback: function(value) { return offsetToDate(minDate, value); },
         |      maxTicksLimit: 20,
         |      maxRotation: 45
         |    };
         |
         |    // ---- 軸専用チャート (x軸ラベル固定表示用) ----
         |    axisChartInstance = new Chart(axisCanvas, {
         |      type: 'bar',
         |      data: {
         |        labels: [''],
         |        datasets: [
         |          { data: [null], backgroundColor: 'transparent', borderColor: 'transparent', borderWidth: 0 },
         |          { data: [null], backgroundColor: 'transparent', borderColor: 'transparent', borderWidth: 0 }
         |        ]
         |      },
         |      options: {
         |        indexAxis: 'y',
         |        responsive: true,
         |        maintainAspectRatio: false,
         |        animation: false,
         |        layout: { padding: { top: 18 } },
         |        plugins: { legend: { display: false }, tooltip: { enabled: false } },
         |        scales: {
         |          x: { stacked: true, min: 0, max: xMax, ticks: xTickConfig },
         |          y: { stacked: true, display: false }
         |        }
         |      },
         |      plugins: [axisVlinesPlugin]
         |    });
         |
         |    // ---- メインチャートのプラグイン (縦線のみ、ラベルなし) ----
         |    var verticalLinesPlugin = {
         |      id: 'ganttVlines',
         |      afterDraw: function(chart) {
         |        var ctx    = chart.ctx;
         |        var xScale = chart.scales.x;
         |        var yScale = chart.scales.y;
         |        vlines.forEach(function(vl) {
         |          var offset = dateDiffDays(minDate, vl.date);
         |          if (offset < xScale.min || offset > xScale.max) return;
         |          var px = xScale.getPixelForValue(offset);
         |          ctx.save();
         |          ctx.beginPath();
         |          ctx.moveTo(px, yScale.top);
         |          ctx.lineTo(px, yScale.bottom);
         |          ctx.lineWidth = 2;
         |          ctx.strokeStyle = vl.color;
         |          ctx.stroke();
         |          ctx.restore();
         |        });
         |      }
         |    };
         |
         |    var barLabelPlugin = {
         |      id: 'barLabel',
         |      afterDatasetsDraw: function(chart) {
         |        var ctx = chart.ctx;
         |        var meta = chart.getDatasetMeta(1); // dataset 1 = タスク期間
         |        meta.data.forEach(function(bar, i) {
         |          var iss = issues[i];
         |          var bx   = bar.base;
         |          var rx   = bar.x;
         |          var y    = bar.y;
         |          var w    = rx - bx;
         |          var hh   = bar.height;
         |          var top  = y - hh / 2;
         |
         |          // 期限超過（未close かつ 実際の完了予定日 < 今日）なら赤枠（仮期限は対象外）
         |          if (!iss.closed && iss.endDate && iss.endDate < todayStr) {
         |            ctx.save();
         |            ctx.strokeStyle = '#e53935';
         |            ctx.lineWidth   = 2.5;
         |            ctx.setLineDash([]);
         |            ctx.strokeRect(bx, top, w, hh);
         |            ctx.restore();
         |          }
         |
         |          // パーセンテージラベル
         |          var text = iss.progress !== null && iss.progress !== undefined
         |            ? iss.progress + '%'
         |            : '';
         |          if (!text) return;
         |          if (w < 20) return;
         |          ctx.save();
         |          ctx.font = 'bold 11px sans-serif';
         |          ctx.textAlign = 'center';
         |          ctx.textBaseline = 'middle';
         |          var barCx = bx + w / 2;
         |          var textW = ctx.measureText(text).width;
         |          if (textW + 6 <= w) {
         |            ctx.fillStyle = 'rgba(255,255,255,0.95)';
         |            ctx.fillText(text, barCx, y);
         |          } else {
         |            ctx.fillStyle = '#444';
         |            ctx.fillText(text, rx + textW / 2 + 4, y);
         |          }
         |          ctx.restore();
         |        });
         |      }
         |    };
         |
         |    chartInstance = new Chart(canvas, {
         |      type: 'bar',
         |      data: {
         |        labels: labels,
         |        datasets: [
         |          {
         |            label: 'オフセット',
         |            data: offsets,
         |            backgroundColor: 'transparent',
         |            borderColor: 'transparent',
         |            borderWidth: 0
         |          },
         |          {
         |            label: 'タスク期間',
         |            data: durations,
         |            backgroundColor: colors,
         |            borderColor: colors,
         |            borderWidth: 1,
         |            borderRadius: 3
         |          }
         |        ]
         |      },
         |      options: {
         |        indexAxis: 'y',
         |        responsive: true,
         |        maintainAspectRatio: false,
         |        animation: false,
         |        onHover: function(event, elements) {
         |          var el = elements.find(function(e) { return e.datasetIndex === 1; });
         |          event.native.target.style.cursor = el ? 'pointer' : 'default';
         |        },
         |        onClick: function(event, elements) {
         |          var el = elements.find(function(e) { return e.datasetIndex === 1; });
         |          if (!el) return;
         |          var iss = issues[el.index];
         |          if (!iss) return;
         |          window.open('/$ownerE/$repoE/issues/' + iss.id, '_blank');
         |        },
         |        plugins: {
         |          legend: { display: false },
         |          tooltip: {
         |            callbacks: {
         |              label: function(ctx) {
         |                if (ctx.datasetIndex === 0) return null;
         |                var iss = issues[ctx.dataIndex];
         |                var lines = [];
         |                if (iss.assignee) lines.push('担当: ' + iss.assignee);
         |                if (iss.startDate) lines.push('開始: ' + iss.startDate);
         |                if (iss.endDate)   lines.push('完了: ' + iss.endDate);
         |                if (iss.progress !== null && iss.progress !== undefined)
         |                  lines.push('進捗: ' + iss.progress + '%');
         |                lines.push(iss.closed ? '状態: クローズ' : '状態: オープン');
         |                return lines;
         |              }
         |            }
         |          }
         |        },
         |        scales: {
         |          x: {
         |            stacked: true,
         |            display: false,
         |            min: 0,
         |            max: xMax
         |          },
         |          y: {
         |            stacked: true,
         |            ticks: { font: { size: 11 } }
         |          }
         |        }
         |      },
         |      plugins: [verticalLinesPlugin, barLabelPlugin]
         |    });
         |
         |    // y軸の幅を軸チャートの左パディングに同期してx軸を揃える
         |    requestAnimationFrame(function() {
         |      if (chartInstance && chartInstance.scales && chartInstance.scales.y && axisChartInstance) {
         |        var yW = chartInstance.scales.y.width || 0;
         |        axisChartInstance.options.layout.padding.left = yW;
         |        axisChartInstance.update('none');
         |      }
         |    });
         |  }
         |
         |  document.getElementById('gt-milestone').addEventListener('change', loadChart);
         |  document.getElementById('gt-label').addEventListener('change', loadChart);
         |  document.getElementById('gt-assignee').addEventListener('change', loadChart);
         |  document.getElementById('gt-show-missing').addEventListener('change', loadChart);
         |
         |  function rerender() {
         |    if (window._gtLastData) {
         |      try { renderChart(window._gtLastData); } catch(e) {}
         |    }
         |  }
         |  document.getElementById('gt-status-filter').addEventListener('change', rerender);
         |  document.getElementById('gt-default-days').addEventListener('change', rerender);
         |  document.getElementById('gt-sort-key').addEventListener('change', rerender);
         |  document.getElementById('gt-sort-dir').addEventListener('click', function() {
         |    sortAsc = !sortAsc;
         |    this.innerHTML = sortAsc ? '&#9650; 昇順' : '&#9660; 降順';
         |    rerender();
         |  });
         |
         |  if (typeof Chart !== 'undefined') {
         |    loadChart();
         |  } else {
         |    var s = document.createElement('script');
         |    s.src = CHART_JS_URL;
         |    s.onload = function() { loadChart(); };
         |    s.onerror = function() {
         |      document.getElementById('gt-status').textContent = 'Chart.js の読み込みに失敗しました: ' + CHART_JS_URL;
         |    };
         |    document.head.appendChild(s);
         |  }
         |})();
         |""".stripMargin

    HtmlUtil.pageShell(
      title       = s"Gantt Chart - $owner/$repo",
      owner       = owner,
      repo        = repo,
      pageIcon    = "graph",
      pageTitle   = s"Gantt Chart: $owner/$repo",
      content     = content,
      extraScript = extraScript,
      wideLayout  = true,
      contextPath = ctx
    )
  })

  /** GET /:owner/:repository/issues/gantt/data — JSON データ API */
  get("/:owner/:repository/issues/gantt/data")(readableUsersOnly { repository =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn  = session.conn
    val owner = repository.owner
    val repo  = repository.name

    try {
      val milestoneP   = params.get("milestone").filter(_.nonEmpty)
      val labelP       = params.get("label").filter(_.nonEmpty)
      val assigneeP    = params.get("assignee").filter(_.nonEmpty)
      val showMissing  = params.get("showMissing").exists(_ == "true")

      val sb = new StringBuilder(
        "SELECT i.ISSUE_ID, i.TITLE, i.CLOSED, " +
        "  COALESCE((SELECT ia.ASSIGNEE_USER_NAME FROM ISSUE_ASSIGNEE ia" +
        "    WHERE ia.USER_NAME = i.USER_NAME AND ia.REPOSITORY_NAME = i.REPOSITORY_NAME" +
        "    AND ia.ISSUE_ID = i.ISSUE_ID LIMIT 1), '') AS ASSIGNEE," +
        "  FORMATDATETIME(i.REGISTERED_DATE, 'yyyy-MM-dd') AS CREATED_DATE," +
        "  p.START_DATE, p.END_DATE, p.PROGRESS " +
        "FROM ISSUE i " +
        "LEFT JOIN REPORTER_ISSUE_PERIOD p ON p.OWNER = i.USER_NAME" +
        "  AND p.REPOSITORY_NAME = i.REPOSITORY_NAME AND p.ISSUE_ID = i.ISSUE_ID " +
        "WHERE i.USER_NAME = ? AND i.REPOSITORY_NAME = ? AND i.PULL_REQUEST = FALSE"
      )
      // showMissing=false: REPORTER_ISSUE_PERIOD レコードがあるもの or closed のみ表示
      // (完了日なしでも登録日+仮期限で表示するため END_DATE 条件は外す)
      if (!showMissing) sb.append(" AND (i.CLOSED = TRUE OR p.ISSUE_ID IS NOT NULL)")
      if (milestoneP.isDefined) sb.append(" AND i.MILESTONE_ID = ?")
      if (labelP.isDefined) sb.append(
        " AND EXISTS (SELECT 1 FROM ISSUE_LABEL il WHERE il.ISSUE_ID = i.ISSUE_ID" +
        " AND il.USER_NAME = i.USER_NAME AND il.REPOSITORY_NAME = i.REPOSITORY_NAME" +
        " AND il.LABEL_ID = ?)"
      )
      if (assigneeP.isDefined) sb.append(
        " AND EXISTS (SELECT 1 FROM ISSUE_ASSIGNEE ia WHERE ia.ISSUE_ID = i.ISSUE_ID" +
        " AND ia.USER_NAME = i.USER_NAME AND ia.REPOSITORY_NAME = i.REPOSITORY_NAME" +
        " AND ia.ASSIGNEE_USER_NAME = ?)"
      )
      sb.append(" ORDER BY COALESCE(p.START_DATE, '9999-99-99'), i.ISSUE_ID")

      val ps = conn.prepareStatement(sb.toString)
      var idx = 1
      ps.setString(idx, owner); idx += 1
      ps.setString(idx, repo);  idx += 1
      milestoneP.foreach { v => ps.setInt(idx, v.toInt); idx += 1 }
      labelP.foreach     { v => ps.setInt(idx, v.toInt); idx += 1 }
      assigneeP.foreach  { v => ps.setString(idx, v);    idx += 1 }

      val rs = ps.executeQuery()

      case class IssueEntry(
        id:             Int,
        title:          String,
        closed:         Boolean,
        assignee:       String,
        createdAt:      String,
        startDate:      Option[String],
        endDate:        Option[String],
        progress:       Option[Int],
        hasMissingDate: Boolean
      )

      val buf = scala.collection.mutable.ArrayBuffer.empty[IssueEntry]
      while (rs.next()) {
        val start = Option(rs.getString("START_DATE"))
        val end   = Option(rs.getString("END_DATE"))
        buf += IssueEntry(
          id             = rs.getInt("ISSUE_ID"),
          title          = rs.getString("TITLE"),
          closed         = rs.getBoolean("CLOSED"),
          assignee       = rs.getString("ASSIGNEE"),
          createdAt      = Option(rs.getString("CREATED_DATE")).getOrElse(""),
          startDate      = start,
          endDate        = end,
          progress       = { val v = rs.getInt("PROGRESS"); if (rs.wasNull()) None else Some(v) },
          hasMissingDate = start.isEmpty || end.isEmpty
        )
      }
      rs.close(); ps.close()

      if (buf.isEmpty) {
        """{"minDate":null,"issues":[]}"""
      } else {
        // minDate は開始日のある Issue から最小値を取る（なければ今日）
        val today = java.time.LocalDate.now().toString
        val minDate = buf.flatMap(_.startDate).minOption.getOrElse(today)

        def toJson(e: IssueEntry): String = {
          val titleEsc    = HtmlUtil.escJson(e.title)
          val assigneeEsc = HtmlUtil.escJson(e.assignee)
          val startStr    = e.startDate.map(v => s""""${HtmlUtil.escJson(v)}"""").getOrElse("null")
          val endStr      = e.endDate.map(v => s""""${HtmlUtil.escJson(v)}"""").getOrElse("null")
          val progressStr = e.progress.map(_.toString).getOrElse("null")
          s"""{"id":${e.id},"title":"$titleEsc","assignee":"$assigneeEsc","createdAt":"${HtmlUtil.escJson(e.createdAt)}","startDate":$startStr,"endDate":$endStr,"progress":$progressStr,"closed":${e.closed},"hasMissingDate":${e.hasMissingDate}}"""
        }

        val issuesJson = buf.map(toJson).mkString("[", ",", "]")
        s"""{"minDate":"$minDate","issues":$issuesJson}"""
      }
    } catch {
      case e: Exception =>
        val msg = HtmlUtil.escJson(Option(e.getMessage).getOrElse(e.getClass.getName))
        s"""{"error":"$msg"}"""
    }
  })
}
