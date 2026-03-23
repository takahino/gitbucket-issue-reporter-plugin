package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.{OwnerAuthenticator, ReadableUsersAuthenticator}
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.model.MailSchedule
import io.github.takahino.reporter.service.{IssueReportService, MailScheduleRepository, MailSendService}
import org.slf4j.LoggerFactory

import scala.util.Try

class MailScheduleController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator
    with OwnerAuthenticator {

  /** GET /:owner/:repository/issues/reporter-writable-check — Managerのみ writable:true を返す */
  get("/:owner/:repository/issues/reporter-writable-check")(readableUsersOnly { repo =>
    contentType = "application/json; charset=UTF-8"
    val isManager = context.loginAccount.exists(a => a.isAdmin || repo.managers.contains(a.userName))
    s"""{"writable":$isManager}"""
  })

  private val logger = LoggerFactory.getLogger(getClass)

  /** GET /:owner/:repository/issues/mail-schedule */
  get("/:owner/:repository/issues/mail-schedule")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn     = session.conn
    val schedule = MailScheduleRepository.findByRepo(conn, repo.owner, repo.name)
    val allUsers = MailScheduleRepository.findAllUsers(conn)
    renderPage(repo.owner, repo.name, schedule, allUsers, message = None, ctx = request.getContextPath)
  })

  /** POST /:owner/:repository/issues/mail-schedule — 設定保存 */
  post("/:owner/:repository/issues/mail-schedule")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn

    val recipients = params.getOrElse("recipients", "")
      .split(",").map(_.trim).filter(_.nonEmpty).mkString(",")
    val hour        = params.getOrElse("hour", "9").toIntOption.getOrElse(9)
    val minute      = params.getOrElse("minute", "0").toIntOption.getOrElse(0)
    val daysRaw     = multiParams.getOrElse("daysOfWeek", Seq.empty)
    val days        = if (daysRaw.isEmpty) "1,2,3,4,5" else daysRaw.mkString(",")
    val enabled     = params.getOrElse("enabled", "false") == "true"
    val columnOrder = params.getOrElse("columnOrder", "").trim

    val existing = MailScheduleRepository.findByRepo(conn, repo.owner, repo.name)
    val schedule = MailSchedule(
      id          = existing.map(_.id).getOrElse(0),
      owner       = repo.owner,
      repository  = repo.name,
      recipients  = recipients,
      hour        = hour.max(0).min(23),
      minute      = minute.max(0).min(59),
      daysOfWeek  = days,
      enabled     = enabled,
      lastSentAt  = existing.flatMap(_.lastSentAt),
      columnOrder = columnOrder
    )
    MailScheduleRepository.upsert(conn, schedule)

    val allUsers = MailScheduleRepository.findAllUsers(conn)
    val saved    = MailScheduleRepository.findByRepo(conn, repo.owner, repo.name)
    renderPage(repo.owner, repo.name, saved, allUsers, message = Some("設定を保存しました。"), ctx = request.getContextPath)
  })

  /** POST /:owner/:repository/issues/mail-schedule/delete */
  post("/:owner/:repository/issues/mail-schedule/delete")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn
    MailScheduleRepository.delete(conn, repo.owner, repo.name)
    val allUsers = MailScheduleRepository.findAllUsers(conn)
    renderPage(repo.owner, repo.name, None, allUsers, message = Some("設定を削除しました。"), ctx = request.getContextPath)
  })

  /** POST /:owner/:repository/issues/mail-schedule/send-now */
  post("/:owner/:repository/issues/mail-schedule/send-now")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn     = session.conn
    val schedule = MailScheduleRepository.findByRepo(conn, repo.owner, repo.name)
    val allUsers = MailScheduleRepository.findAllUsers(conn)

    val ctx = request.getContextPath
    schedule match {
      case None =>
        renderPage(repo.owner, repo.name, None, allUsers,
          message = Some("スケジュールが設定されていません。先に設定を保存してください。"), ctx = ctx)
      case Some(s) =>
        Try(MailSendService.send(s, conn)) match {
          case scala.util.Success(_) =>
            renderPage(repo.owner, repo.name, Some(s), allUsers,
              message = Some("テスト送信が完了しました。"), ctx = ctx)
          case scala.util.Failure(e) =>
            logger.error("即時送信エラー", e)
            renderPage(repo.owner, repo.name, Some(s), allUsers,
              message = Some(s"送信エラー: ${e.getMessage}"), ctx = ctx)
        }
    }
  })

  // -----------------------------------------------------------------------
  // HTML 生成
  // -----------------------------------------------------------------------

  private def renderPage(
    owner:    String,
    repo:     String,
    schedule: Option[MailSchedule],
    allUsers: Seq[(String, String)],
    message:  Option[String],
    ctx:      String = ""
  ): String = {

    val selectedNames = schedule.map(
      _.recipients.split(",").map(_.trim).filter(_.nonEmpty).toSet
    ).getOrElse(Set.empty[String])

    val hour    = schedule.map(_.hour).getOrElse(9)
    val minute  = schedule.map(_.minute).getOrElse(0)
    val days    = schedule.map(_.daysOfWeek.split(",").map(_.trim).toSet)
                           .getOrElse(Set("1","2","3","4","5"))
    val enabled = schedule.map(_.enabled).getOrElse(false)

    val savedOrder  = schedule.map(_.columnOrder).getOrElse("")
    val savedKeys   = if (savedOrder.trim.isEmpty) IssueReportService.DefaultColumnOrder
                      else savedOrder.split(",").map(_.trim).filter(_.nonEmpty).toSeq
    val savedSet    = if (savedOrder.trim.isEmpty) IssueReportService.DefaultColumnOrder.toSet
                      else savedKeys.toSet
    // 保存済みキーを先頭に置き、未登録キーを末尾に追加
    val orderedKeys = savedKeys.filter(IssueReportService.ColumnDefByKey.contains) ++
                      IssueReportService.DefaultColumnOrder.filterNot(savedSet.contains)

    val msgHtml = HtmlUtil.alertHtml(message)

    val userCheckboxes = allUsers.map { case (userName, fullName) =>
      s"""<label class="checkbox-inline" style="margin-right:12px;margin-bottom:4px;">
         |  <input type="checkbox" name="dummyUser" value="${HtmlUtil.escHtml(userName)}" ${HtmlUtil.checked(selectedNames.contains(userName))}
         |    onchange="updateRecipients()">
         |  ${HtmlUtil.escHtml(userName)}${if (fullName.nonEmpty) s" (${HtmlUtil.escHtml(fullName)})" else ""}
         |</label>""".stripMargin
    }.mkString("\n")

    val dayCheckboxes  = HtmlUtil.dayCheckboxes(days)
    val hourOptions    = HtmlUtil.hourOptions(hour)
    val minuteOptions  = HtmlUtil.minuteOptions(minute)

    val recipientsValue = HtmlUtil.escHtml(selectedNames.mkString(","))

    val ctxE = HtmlUtil.escHtml(ctx)
    val deleteBtn = if (schedule.isDefined)
      s"""<form method="post" action="$ctxE/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/mail-schedule/delete"
         |      style="display:inline;">
         |  <button type="submit" class="btn btn-danger btn-sm"
         |    onclick="return confirm('スケジュール設定を削除しますか？')">削除</button>
         |</form>""".stripMargin
    else ""

    val sendNowBtn = if (schedule.isDefined)
      s"""<form method="post" action="$ctxE/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/mail-schedule/send-now"
         |      style="display:inline;">
         |  <button type="submit" class="btn btn-default btn-sm">今すぐ送信</button>
         |</form>""".stripMargin
    else ""

    val colOrderRows = orderedKeys.map { key =>
      val colDef  = IssueReportService.ColumnDefByKey(key)
      val checked = if (savedSet.contains(key)) "checked" else ""
      s"""<tr data-key="${HtmlUtil.escHtml(key)}">
         |  <td style="padding:2px 6px;"><input type="checkbox" class="col-check" $checked></td>
         |  <td style="padding:2px 6px;">${HtmlUtil.escHtml(colDef.header)}</td>
         |  <td style="padding:2px 4px;">
         |    <button type="button" class="btn btn-xs btn-default col-up">↑</button>
         |    <button type="button" class="btn btn-xs btn-default col-down">↓</button>
         |  </td>
         |</tr>""".stripMargin
    }.mkString("\n")

    val content =
      s"""$msgHtml
         |<form method="post" action="$ctxE/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/mail-schedule">
         |
         |  <div class="form-group">
         |    <label class="control-label"><strong>送信先ユーザー</strong>（複数選択可）</label>
         |    <input type="hidden" name="recipients" id="recipientsField" value="$recipientsValue">
         |    <div class="panel panel-default" style="max-height:220px;overflow-y:auto;margin-bottom:0;">
         |      <div class="panel-body" style="padding:10px 14px;">
         |        $userCheckboxes
         |      </div>
         |    </div>
         |  </div>
         |
         |  <div class="form-group">
         |    <label class="control-label"><strong>送信曜日</strong></label><br>
         |    $dayCheckboxes
         |  </div>
         |
         |  <div class="form-group">
         |    <label class="control-label"><strong>送信時刻</strong></label><br>
         |    <select name="hour" class="form-control" style="width:80px;display:inline-block;">$hourOptions</select>
         |    &nbsp;時&nbsp;
         |    <select name="minute" class="form-control" style="width:80px;display:inline-block;">$minuteOptions</select>
         |    &nbsp;分
         |  </div>
         |
         |  <div class="form-group">
         |    <div class="checkbox">
         |      <label>
         |        <input type="checkbox" name="enabled" value="true" ${HtmlUtil.checked(enabled)}>
         |        <strong>スケジュール送信を有効にする</strong>
         |      </label>
         |    </div>
         |  </div>
         |
         |  <div class="form-group">
         |    <label class="control-label"><strong>出力列の選択・並び順</strong></label>
         |    <p class="help-block" style="margin-bottom:6px;">チェックした列のみExcelに出力されます。↑↓で順序を変更できます。</p>
         |    <input type="hidden" name="columnOrder" id="columnOrderField" value="${HtmlUtil.escHtml(savedOrder)}">
         |    <table id="colOrderTable" class="table table-condensed" style="width:auto;max-width:400px;">
         |      <thead><tr><th style="padding:2px 6px;">出力</th><th style="padding:2px 6px;">列名</th><th style="padding:2px 4px;">順序</th></tr></thead>
         |      <tbody>
         |        $colOrderRows
         |      </tbody>
         |    </table>
         |  </div>
         |
         |  <div class="form-actions" style="margin-top:20px;">
         |    <button type="submit" class="btn btn-primary">保存</button>
         |    $deleteBtn
         |    $sendNowBtn
         |    <a href="$ctxE/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues"
         |       class="btn btn-default">戻る</a>
         |  </div>
         |
         |</form>""".stripMargin

    val script =
      """function updateRecipients() {
        |  var checks = document.querySelectorAll('input[name="dummyUser"]:checked');
        |  var names  = Array.from(checks).map(function(c){ return c.value; });
        |  document.getElementById('recipientsField').value = names.join(',');
        |}
        |
        |function updateColumnOrder() {
        |  var rows = document.querySelectorAll('#colOrderTable tbody tr');
        |  var keys = [];
        |  for (var i = 0; i < rows.length; i++) {
        |    var cb = rows[i].querySelector('.col-check');
        |    if (cb && cb.checked) keys.push(rows[i].getAttribute('data-key'));
        |  }
        |  document.getElementById('columnOrderField').value = keys.join(',');
        |}
        |
        |(function() {
        |  var tbody = document.querySelector('#colOrderTable tbody');
        |  if (!tbody) return;
        |
        |  function moveRow(row, direction) {
        |    if (direction === 'up') {
        |      var prev = row.previousElementSibling;
        |      if (prev) tbody.insertBefore(row, prev);
        |    } else {
        |      var next = row.nextElementSibling;
        |      if (next) tbody.insertBefore(next, row);
        |    }
        |    updateColumnOrder();
        |  }
        |
        |  tbody.addEventListener('change', function(e) {
        |    if (e.target && e.target.classList.contains('col-check')) updateColumnOrder();
        |  });
        |
        |  tbody.addEventListener('click', function(e) {
        |    var btn = e.target;
        |    if (!btn || btn.tagName.toLowerCase() !== 'button') return;
        |    var row = btn.closest ? btn.closest('tr') : (function(el){ while(el && el.tagName!=='TR') el=el.parentNode; return el; })(btn);
        |    if (!row) return;
        |    if (btn.classList.contains('col-up'))   moveRow(row, 'up');
        |    if (btn.classList.contains('col-down')) moveRow(row, 'down');
        |  });
        |
        |  updateColumnOrder(); // ページ読み込み時に初期化
        |})();""".stripMargin

    HtmlUtil.pageShell(
      title       = "Issue Excel 定期送信設定",
      owner       = owner,
      repo        = repo,
      pageIcon    = "mail",
      pageTitle   = "Issue Excel 定期送信設定",
      content     = content,
      extraScript = script,
      contextPath = ctx
    )
  }

}
