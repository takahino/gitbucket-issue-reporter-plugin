package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.ReadableUsersAuthenticator
import gitbucket.core.util.Implicits._

import java.time.LocalDate
import java.time.format.DateTimeFormatter

class BurndownController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator {

  private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  /** GET /ir-assets/chart.min.js — Chart.js を JAR から直接配信 */
  get("/ir-assets/chart.min.js") {
    contentType = "application/javascript; charset=UTF-8"
    response.setHeader("Cache-Control", "max-age=86400")
    val stream = getClass.getClassLoader.getResourceAsStream("assets/issue-reporter/chart.min.js")
    if (stream != null) {
      try {
        val bytes = stream.readAllBytes()
        bytes
      } finally {
        stream.close()
      }
    } else {
      halt(404, "chart.min.js not found in JAR")
    }
  }

  private def toLocalDate(s: String): LocalDate = {
    if (s == null || s.isEmpty) LocalDate.now()
    else try LocalDate.parse(s.take(10), fmt)
         catch { case _: Exception => LocalDate.now() }
  }

  /** GET /:owner/:repository/issues/burnup — HTML ページ */
  get("/:owner/:repository/issues/burnup")(readableUsersOnly { repository =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn  = session.conn
    val owner = repository.owner
    val repo  = repository.name

    // マイルストーン一覧
    val milestones: Seq[(Int, String, Option[String])] = {
      val ps = conn.prepareStatement(
        "SELECT MILESTONE_ID, TITLE, DUE_DATE FROM MILESTONE " +
        "WHERE USER_NAME = ? AND REPOSITORY_NAME = ? " +
        "ORDER BY DUE_DATE NULLS LAST, MILESTONE_ID"
      )
      ps.setString(1, owner)
      ps.setString(2, repo)
      val rs  = ps.executeQuery()
      val buf = scala.collection.mutable.ArrayBuffer.empty[(Int, String, Option[String])]
      while (rs.next()) {
        buf += ((rs.getInt(1), rs.getString(2), Option(rs.getString(3))))
      }
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

    val msOptions = milestones.map { case (id, title, _) =>
      s"""<option value="$id">${HtmlUtil.escHtml(title)}</option>"""
    }.mkString("\n")

    val lblOptions = labels.map { case (id, name) =>
      s"""<option value="$id">${HtmlUtil.escHtml(name)}</option>"""
    }.mkString("\n")

    val asnOptions = assignees.map { name =>
      s"""<option value="${HtmlUtil.escHtml(name)}">${HtmlUtil.escHtml(name)}</option>"""
    }.mkString("\n")

    val dataApiBase = s"/$ownerE/$repoE/issues/burnup/data"

    val content =
      s"""<div style="margin-bottom:12px;display:flex;gap:12px;flex-wrap:wrap;align-items:center;">
         |  <div>
         |    <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">マイルストーン</label>
         |    <select id="bd-milestone" class="form-control input-sm">
         |      <option value="">すべて</option>
         |      $msOptions
         |    </select>
         |  </div>
         |  <div>
         |    <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">ラベル</label>
         |    <select id="bd-label" class="form-control input-sm">
         |      <option value="">すべて</option>
         |      $lblOptions
         |    </select>
         |  </div>
         |  <div>
         |    <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">担当者</label>
         |    <select id="bd-assignee" class="form-control input-sm">
         |      <option value="">すべて</option>
         |      $asnOptions
         |    </select>
         |  </div>
         |  <div>
         |    <label style="font-size:12px;color:#555;margin-bottom:2px;display:block;">未設定期限（日）</label>
         |    <input id="bd-default-days" type="number" min="1" max="365" value="7"
         |      class="form-control input-sm" style="width:70px;"
         |      title="完了予定日が未設定のIssueに適用する仮の期限（今日から何日後か）">
         |  </div>
         |  <span id="bd-status" style="font-size:12px;color:#888;align-self:flex-end;padding-bottom:4px;"></span>
         |</div>
         |<div style="position:relative;height:420px;">
         |  <canvas id="bd-chart"></canvas>
         |  <div id="bd-empty" style="display:none;text-align:center;color:#888;padding:60px 0;">
         |    表示するIssueがありません
         |  </div>
         |</div>
         |<table style="margin-top:16px;font-size:12px;color:#555;border-collapse:collapse;width:100%;">
         |  <thead>
         |    <tr style="background:#f5f5f5;border-bottom:1px solid #ddd;">
         |      <th style="padding:6px 12px;text-align:left;white-space:nowrap;">系列</th>
         |      <th style="padding:6px 12px;text-align:left;">導出条件</th>
         |    </tr>
         |  </thead>
         |  <tbody>
         |    <tr style="border-bottom:1px solid #eee;">
         |      <td style="padding:6px 12px;white-space:nowrap;font-weight:bold;color:#888;">登録数（累計）</td>
         |      <td style="padding:6px 12px;">その日までに登録されたIssueの累計数。作業スコープの上限を表す。</td>
         |    </tr>
         |    <tr style="border-bottom:1px solid #eee;">
         |      <td style="padding:6px 12px;white-space:nowrap;font-weight:bold;color:rgba(255,99,132,0.9);">計画完了数（累計）</td>
         |      <td style="padding:6px 12px;">その日までに完了予定だったIssueの累計数。完了予定日が未設定のIssueは「今日＋未設定期限（日）」を期限とみなす。</td>
         |    </tr>
         |    <tr>
         |      <td style="padding:6px 12px;white-space:nowrap;font-weight:bold;color:rgba(54,162,235,1);">完了数（累計）</td>
         |      <td style="padding:6px 12px;">その日までに実際にクローズされたIssueの累計数。</td>
         |    </tr>
         |  </tbody>
         |</table>
         |""".stripMargin

    val extraScript =
      s"""
         |(function() {
         |  var CHART_JS_URL = '/ir-assets/chart.min.js';
         |  var DATA_API = '$dataApiBase';
         |  var chartInstance = null;
         |
         |  function addDays(dateStr, n) {
         |    var dt = new Date(dateStr + 'T00:00:00');
         |    dt.setDate(dt.getDate() + n);
         |    return dt.toISOString().slice(0, 10);
         |  }
         |  function addMonths(dateStr, n) {
         |    var dt = new Date(dateStr + 'T00:00:00');
         |    dt.setMonth(dt.getMonth() + n);
         |    return dt.toISOString().slice(0, 10);
         |  }
         |
         |  function buildUrl() {
         |    var ms   = document.getElementById('bd-milestone').value;
         |    var lbl  = document.getElementById('bd-label').value;
         |    var asn  = document.getElementById('bd-assignee').value;
         |    var days = document.getElementById('bd-default-days').value;
         |    var params = [];
         |    if (ms)   params.push('milestone='   + encodeURIComponent(ms));
         |    if (lbl)  params.push('label='        + encodeURIComponent(lbl));
         |    if (asn)  params.push('assignee='     + encodeURIComponent(asn));
         |    if (days) params.push('defaultDays='  + encodeURIComponent(days));
         |    return DATA_API + (params.length ? '?' + params.join('&') : '');
         |  }
         |
         |  function loadChart() {
         |    var statusEl = document.getElementById('bd-status');
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
         |      try { renderChart(d); }
         |      catch(e) { statusEl.textContent = 'チャートエラー: ' + e.message; console.error(e); }
         |    };
         |    xhr.onerror = function() { statusEl.textContent = '通信エラー'; };
         |    xhr.send();
         |  }
         |
         |  function renderChart(d) {
         |    var canvas  = document.getElementById('bd-chart');
         |    var emptyEl = document.getElementById('bd-empty');
         |
         |    if (chartInstance) { chartInstance.destroy(); chartInstance = null; }
         |
         |    if (!d.labels || d.labels.length === 0 || d.totalIssues === 0) {
         |      canvas.style.display = 'none';
         |      emptyEl.style.display = 'block';
         |      return;
         |    }
         |    canvas.style.display = '';
         |    emptyEl.style.display = 'none';
         |
         |    var largeDataset = d.labels.length > 60;
         |    var datasets = [
         |      {
         |        label: '登録数（累計）',
         |        data: d.total,
         |        borderColor: 'rgba(150,150,150,0.8)',
         |        backgroundColor: 'transparent',
         |        fill: false,
         |        stepped: true,
         |        tension: 0,
         |        pointRadius: 0,
         |        borderWidth: 1.5,
         |        borderDash: [4, 4]
         |      },
         |      {
         |        label: '計画完了数（累計）',
         |        data: d.plannedCompleted,
         |        borderColor: 'rgba(255,99,132,0.9)',
         |        backgroundColor: 'transparent',
         |        fill: false,
         |        stepped: true,
         |        tension: 0,
         |        pointRadius: largeDataset ? 0 : 2,
         |        borderWidth: 2,
         |        borderDash: [6, 3]
         |      },
         |      {
         |        label: '完了数（累計）',
         |        data: d.completed,
         |        borderColor: 'rgba(54,162,235,1)',
         |        backgroundColor: 'rgba(54,162,235,0.15)',
         |        fill: 'origin',
         |        stepped: true,
         |        tension: 0,
         |        pointRadius: largeDataset ? 0 : 3,
         |        borderWidth: 2
         |      }
         |    ];
         |    var vlines = [
         |      { date: d.today,                color: 'rgba(30,30,30,0.85)',   label: '今日' },
         |      { date: addDays(d.today, 7),    color: 'rgba(210,100,0,0.80)', label: '1週間後' },
         |      { date: addMonths(d.today, 1),  color: 'rgba(140,0,200,0.75)', label: '1ヶ月後' }
         |    ];
         |    var verticalLinesPlugin = {
         |      id: 'verticalLines',
         |      afterDraw: function(chart) {
         |        var ctx    = chart.ctx;
         |        var xScale = chart.scales.x;
         |        var yScale = chart.scales.y;
         |        vlines.forEach(function(vl) {
         |          var idx = chart.data.labels.indexOf(vl.date);
         |          if (idx < 0) return;
         |          var x = xScale.getPixelForValue(idx);
         |          ctx.save();
         |          ctx.beginPath();
         |          ctx.moveTo(x, yScale.top);
         |          ctx.lineTo(x, yScale.bottom);
         |          ctx.lineWidth = 3;
         |          ctx.strokeStyle = vl.color;
         |          ctx.stroke();
         |          ctx.fillStyle = vl.color;
         |          ctx.font = 'bold 11px sans-serif';
         |          ctx.textAlign = 'center';
         |          ctx.fillText(vl.label, x, yScale.top - 5);
         |          ctx.restore();
         |        });
         |      }
         |    };
         |
         |    chartInstance = new Chart(canvas, {
         |      type: 'line',
         |      data: { labels: d.labels, datasets: datasets },
         |      options: {
         |        responsive: true,
         |        maintainAspectRatio: false,
         |        interaction: { mode: 'index', intersect: false },
         |        plugins: {
         |          legend: { position: 'top' }
         |        },
         |        scales: {
         |          x: {
         |            ticks: { maxTicksLimit: d.labels.length > 100 ? 15 : undefined, maxRotation: 45 }
         |          },
         |          y: {
         |            beginAtZero: true,
         |            title: { display: true, text: 'Issue件数' },
         |            ticks: { stepSize: 1 }
         |          }
         |        }
         |      },
         |      plugins: [verticalLinesPlugin]
         |    });
         |  }
         |
         |  document.getElementById('bd-milestone').addEventListener('change', loadChart);
         |  document.getElementById('bd-label').addEventListener('change', loadChart);
         |  document.getElementById('bd-assignee').addEventListener('change', loadChart);
         |  document.getElementById('bd-default-days').addEventListener('change', loadChart);
         |
         |  // Chart.js を動的ロードしてから初期化
         |  if (typeof Chart !== 'undefined') {
         |    loadChart();
         |  } else {
         |    var s = document.createElement('script');
         |    s.src = CHART_JS_URL;
         |    s.onload = function() { loadChart(); };
         |    s.onerror = function() {
         |      document.getElementById('bd-status').textContent = 'Chart.js の読み込みに失敗しました: ' + CHART_JS_URL;
         |    };
         |    document.head.appendChild(s);
         |  }
         |})();
         |""".stripMargin

    HtmlUtil.pageShell(
      title       = s"Burnup Chart - $owner/$repo",
      owner       = owner,
      repo        = repo,
      pageIcon    = "pulse",
      pageTitle   = s"Burnup Chart: $owner/$repo",
      content     = content,
      extraScript = extraScript,
      wideLayout  = true
    )
  })

  /** GET /:owner/:repository/issues/burnup/data — JSON データ API */
  get("/:owner/:repository/issues/burnup/data")(readableUsersOnly { repository =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn  = session.conn
    val owner = repository.owner
    val repo  = repository.name

    try {
      val milestoneP  = params.get("milestone").filter(_.nonEmpty)
      val labelP      = params.get("label").filter(_.nonEmpty)
      val assigneeP   = params.get("assignee").filter(_.nonEmpty)
      val defaultDays = params.get("defaultDays").filter(_.nonEmpty)
                          .flatMap(s => try Some(s.toInt) catch { case _: Exception => None })
                          .filter(n => n >= 1 && n <= 365)
                          .getOrElse(7)

      // イシュー取得クエリ
      // CLOSE_DATE: ISSUE_COMMENT の close/close_comment アクション日時（最新）を使用
      //             close アクションがなければ UPDATED_DATE にフォールバック
      val sb = new StringBuilder(
        "SELECT i.REGISTERED_DATE, i.CLOSED, " +
        "  COALESCE(" +
        "    (SELECT MAX(ic.REGISTERED_DATE) FROM ISSUE_COMMENT ic" +
        "     WHERE ic.USER_NAME = i.USER_NAME AND ic.REPOSITORY_NAME = i.REPOSITORY_NAME" +
        "       AND ic.ISSUE_ID = i.ISSUE_ID AND ic.ACTION IN ('close','close_comment'))," +
        "    i.UPDATED_DATE" +
        "  ) AS CLOSE_DATE," +
        "  p.END_DATE " +
        "FROM ISSUE i " +
        "LEFT JOIN REPORTER_ISSUE_PERIOD p ON p.OWNER = i.USER_NAME" +
        "  AND p.REPOSITORY_NAME = i.REPOSITORY_NAME AND p.ISSUE_ID = i.ISSUE_ID " +
        "WHERE i.USER_NAME = ? AND i.REPOSITORY_NAME = ? " +
        "AND i.PULL_REQUEST = FALSE"
      )
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

      val ps = conn.prepareStatement(sb.toString)
      var idx = 1
      ps.setString(idx, owner); idx += 1
      ps.setString(idx, repo);  idx += 1
      milestoneP.foreach { v => ps.setInt(idx, v.toInt); idx += 1 }
      labelP.foreach     { v => ps.setInt(idx, v.toInt); idx += 1 }
      assigneeP.foreach  { v => ps.setString(idx, v);    idx += 1 }

      val rs = ps.executeQuery()

      // (registeredDate, closeDate, closed, endDate)
      val issues = scala.collection.mutable.ArrayBuffer.empty[(LocalDate, LocalDate, Boolean, Option[LocalDate])]
      while (rs.next()) {
        val endDateOpt = Option(rs.getString(4)).flatMap { s =>
          try Some(LocalDate.parse(s.take(10), fmt)) catch { case _: Exception => None }
        }
        issues += ((toLocalDate(rs.getString(1)), toLocalDate(rs.getString(3)), rs.getBoolean(2), endDateOpt))
      }
      rs.close(); ps.close()

      if (issues.isEmpty) {
        """{"startDate":null,"endDate":null,"today":null,"totalIssues":0,"labels":[],"total":[],"completed":[],"plannedCompleted":[]}"""
      } else {
        val today     = LocalDate.now()
        val startDate = issues.map(_._1).minBy(_.toEpochDay)
        // END_DATE がないイシューは today + defaultDays を仮期限とする
        val fallbackEnd  = today.plusDays(defaultDays)
        val maxEndDate   = issues.flatMap(_._4).maxOption.getOrElse(fallbackEnd)
        val effectiveEnd = if (maxEndDate.isBefore(startDate)) startDate else maxEndDate
        val totalDays    = java.time.temporal.ChronoUnit.DAYS.between(startDate, effectiveEnd).toInt
        val totalIssues  = issues.size

        val days = (0L to totalDays).map(startDate.plusDays)

        // 1パスで3系列を同時集計: O(days × issues)
        val counts = days.map { d =>
          var total = 0; var completed = 0; var planned = 0
          issues.foreach { case (reg, closeDate, closed, endDateOpt) =>
            if (!reg.isAfter(d)) {
              total += 1
              if (closed && !closeDate.isAfter(d)) completed += 1
              val plannedEnd = endDateOpt.getOrElse(if (closed) closeDate else fallbackEnd)
              if (!plannedEnd.isAfter(d)) planned += 1
            }
          }
          (total, completed, planned)
        }
        val totalCounts            = counts.map(_._1)
        val completedCounts        = counts.map(_._2)
        val plannedCompletedCounts = counts.map(_._3)

        val labelsJson           = days.map(d => s""""${d.format(fmt)}"""").mkString("[", ",", "]")
        val totalJson            = totalCounts.mkString("[", ",", "]")
        val completedJson        = completedCounts.mkString("[", ",", "]")
        val plannedCompletedJson = plannedCompletedCounts.mkString("[", ",", "]")
        val startStr             = startDate.format(fmt)
        val endStr               = effectiveEnd.format(fmt)
        val todayStr             = today.format(fmt)

        s"""{"startDate":"$startStr","endDate":"$endStr","today":"$todayStr","totalIssues":$totalIssues,"labels":$labelsJson,"total":$totalJson,"completed":$completedJson,"plannedCompleted":$plannedCompletedJson}"""
      }
    } catch {
      case e: Exception =>
        val msg = HtmlUtil.escJson(Option(e.getMessage).getOrElse(e.getClass.getName))
        s"""{"error":"$msg"}"""
    }
  })
}
