package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.{OwnerAuthenticator, ReadableUsersAuthenticator}
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.model.MailSchedule
import io.github.takahino.reporter.service.{MailScheduleRepository, MailSendService}
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
    renderPage(repo.owner, repo.name, schedule, allUsers, message = None)
  })

  /** POST /:owner/:repository/issues/mail-schedule — 設定保存 */
  post("/:owner/:repository/issues/mail-schedule")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn

    val recipients = params.getOrElse("recipients", "")
      .split(",").map(_.trim).filter(_.nonEmpty).mkString(",")
    val hour    = params.getOrElse("hour", "9").toIntOption.getOrElse(9)
    val minute  = params.getOrElse("minute", "0").toIntOption.getOrElse(0)
    val daysRaw = multiParams.getOrElse("daysOfWeek", Seq.empty)
    val days    = if (daysRaw.isEmpty) "1,2,3,4,5" else daysRaw.mkString(",")
    val enabled = params.getOrElse("enabled", "false") == "true"

    val existing = MailScheduleRepository.findByRepo(conn, repo.owner, repo.name)
    val schedule = MailSchedule(
      id         = existing.map(_.id).getOrElse(0),
      owner      = repo.owner,
      repository = repo.name,
      recipients = recipients,
      hour       = hour.max(0).min(23),
      minute     = minute.max(0).min(59),
      daysOfWeek = days,
      enabled    = enabled,
      lastSentAt = existing.flatMap(_.lastSentAt)
    )
    MailScheduleRepository.upsert(conn, schedule)

    val allUsers = MailScheduleRepository.findAllUsers(conn)
    val saved    = MailScheduleRepository.findByRepo(conn, repo.owner, repo.name)
    renderPage(repo.owner, repo.name, saved, allUsers, message = Some("設定を保存しました。"))
  })

  /** POST /:owner/:repository/issues/mail-schedule/delete */
  post("/:owner/:repository/issues/mail-schedule/delete")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn
    MailScheduleRepository.delete(conn, repo.owner, repo.name)
    val allUsers = MailScheduleRepository.findAllUsers(conn)
    renderPage(repo.owner, repo.name, None, allUsers, message = Some("設定を削除しました。"))
  })

  /** POST /:owner/:repository/issues/mail-schedule/send-now */
  post("/:owner/:repository/issues/mail-schedule/send-now")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn     = session.conn
    val schedule = MailScheduleRepository.findByRepo(conn, repo.owner, repo.name)
    val allUsers = MailScheduleRepository.findAllUsers(conn)

    schedule match {
      case None =>
        renderPage(repo.owner, repo.name, None, allUsers,
          message = Some("スケジュールが設定されていません。先に設定を保存してください。"))
      case Some(s) =>
        Try(MailSendService.send(s, conn)) match {
          case scala.util.Success(_) =>
            renderPage(repo.owner, repo.name, Some(s), allUsers,
              message = Some("テスト送信が完了しました。"))
          case scala.util.Failure(e) =>
            logger.error("即時送信エラー", e)
            renderPage(repo.owner, repo.name, Some(s), allUsers,
              message = Some(s"送信エラー: ${e.getMessage}"))
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
    message:  Option[String]
  ): String = {

    val selectedNames = schedule.map(
      _.recipients.split(",").map(_.trim).filter(_.nonEmpty).toSet
    ).getOrElse(Set.empty[String])

    val hour    = schedule.map(_.hour).getOrElse(9)
    val minute  = schedule.map(_.minute).getOrElse(0)
    val days    = schedule.map(_.daysOfWeek.split(",").map(_.trim).toSet)
                           .getOrElse(Set("1","2","3","4","5"))
    val enabled = schedule.map(_.enabled).getOrElse(false)

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

    val deleteBtn = if (schedule.isDefined)
      s"""<form method="post" action="/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/mail-schedule/delete"
         |      style="display:inline;">
         |  <button type="submit" class="btn btn-danger btn-sm"
         |    onclick="return confirm('スケジュール設定を削除しますか？')">削除</button>
         |</form>""".stripMargin
    else ""

    val sendNowBtn = if (schedule.isDefined)
      s"""<form method="post" action="/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/mail-schedule/send-now"
         |      style="display:inline;">
         |  <button type="submit" class="btn btn-default btn-sm">今すぐ送信</button>
         |</form>""".stripMargin
    else ""

    val content =
      s"""$msgHtml
         |<form method="post" action="/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/mail-schedule">
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
         |  <div class="form-actions" style="margin-top:20px;">
         |    <button type="submit" class="btn btn-primary">保存</button>
         |    $deleteBtn
         |    $sendNowBtn
         |    <a href="/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues"
         |       class="btn btn-default">戻る</a>
         |  </div>
         |
         |</form>""".stripMargin

    val script =
      """function updateRecipients() {
        |  var checks = document.querySelectorAll('input[name="dummyUser"]:checked');
        |  var names  = Array.from(checks).map(function(c){ return c.value; });
        |  document.getElementById('recipientsField').value = names.join(',');
        |}""".stripMargin

    HtmlUtil.pageShell(
      title      = "Issue Excel 定期送信設定",
      owner      = owner,
      repo       = repo,
      pageIcon   = "mail",
      pageTitle  = "Issue Excel 定期送信設定",
      content    = content,
      extraScript = script
    )
  }

}
