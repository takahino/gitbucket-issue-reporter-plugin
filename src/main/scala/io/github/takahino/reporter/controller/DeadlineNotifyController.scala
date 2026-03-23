package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.{OwnerAuthenticator, ReadableUsersAuthenticator}
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.model.DeadlineNotifySetting
import io.github.takahino.reporter.service.{DeadlineNotifyRepository, DeadlineNotifyService, MailScheduleRepository}
import org.slf4j.LoggerFactory

import scala.util.Try

class DeadlineNotifyController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator
    with OwnerAuthenticator {

  private val logger = LoggerFactory.getLogger(getClass)

  /** GET /:owner/:repository/issues/deadline-notify */
  get("/:owner/:repository/issues/deadline-notify")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn    = session.conn
    val setting = DeadlineNotifyRepository.findByRepo(conn, repo.owner, repo.name)
    renderPage(repo.owner, repo.name, setting, message = None, ctx = request.getContextPath)
  })

  /** POST /:owner/:repository/issues/deadline-notify — 設定保存 */
  post("/:owner/:repository/issues/deadline-notify")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn

    val existing = DeadlineNotifyRepository.findByRepo(conn, repo.owner, repo.name)
    val setting = DeadlineNotifySetting(
      id                    = existing.map(_.id).getOrElse(0),
      owner                 = repo.owner,
      repository            = repo.name,
      advanceNoticeDays     = {
        val raw = params.getOrElse("advanceNoticeDays", "")
        // 正の整数のみ受け付け、降順で正規化して保存
        raw.split(",").flatMap(_.trim.toIntOption).filter(_ > 0).sorted.reverse.mkString(",")
      },
      dailyNotifyWithinDays = params.getOrElse("dailyNotifyWithinDays", "0").toIntOption.getOrElse(0).max(0).min(365),
      notifyOverdue         = params.getOrElse("notifyOverdue", "false") == "true",
      notifyNoDeadline      = params.getOrElse("notifyNoDeadline", "false") == "true",
      notifyToCreator       = params.getOrElse("notifyToCreator", "false") == "true",
      notifyToAssignee      = params.getOrElse("notifyToAssignee", "false") == "true",
      sendHour              = params.getOrElse("hour", "9").toIntOption.getOrElse(9).max(0).min(23),
      sendMinute            = params.getOrElse("minute", "0").toIntOption.getOrElse(0).max(0).min(59),
      daysOfWeek            = {
        val raw = multiParams.getOrElse("daysOfWeek", Seq.empty)
        if (raw.isEmpty) "1,2,3,4,5" else raw.mkString(",")
      },
      enabled               = params.getOrElse("enabled", "false") == "true",
      sortOrder             = params.getOrElse("sortOrder", "NO_DEADLINE_FIRST") match {
        case v @ ("NO_DEADLINE_FIRST" | "DUE_DATE_ASC" | "ISSUE_ID_ASC") => v
        case _ => "NO_DEADLINE_FIRST"
      },
      notifyNotStarted      = params.get("notifyNotStarted").contains("true")
    )
    DeadlineNotifyRepository.upsert(conn, setting)

    val saved = DeadlineNotifyRepository.findByRepo(conn, repo.owner, repo.name)
    renderPage(repo.owner, repo.name, saved, message = Some("設定を保存しました。"), ctx = request.getContextPath)
  })

  /** POST /:owner/:repository/issues/deadline-notify/delete */
  post("/:owner/:repository/issues/deadline-notify/delete")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn = session.conn
    DeadlineNotifyRepository.delete(conn, repo.owner, repo.name)
    renderPage(repo.owner, repo.name, None, message = Some("設定を削除しました。"), ctx = request.getContextPath)
  })

  /** POST /:owner/:repository/issues/deadline-notify/send-now */
  post("/:owner/:repository/issues/deadline-notify/send-now")(ownerOnly { repo =>
    contentType = "text/html; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn    = session.conn
    val setting = DeadlineNotifyRepository.findByRepo(conn, repo.owner, repo.name)

    val ctx = request.getContextPath
    setting match {
      case None =>
        renderPage(repo.owner, repo.name, None,
          message = Some("設定が保存されていません。先に設定を保存してください。"), ctx = ctx)
      case Some(s) =>
        Try(DeadlineNotifyService.run(conn, s, skipSentCheck = true)) match {
          case scala.util.Success(sentCount) =>
            val msg = if (sentCount == 0)
              "通知条件に合うオープンIssueが0件でした。通知条件を確認してください。"
            else
              s"${sentCount}通の期日通知メールを送信しました。"
            renderPage(repo.owner, repo.name, Some(s), message = Some(msg), ctx = ctx)
          case scala.util.Failure(e) =>
            logger.error("期日通知 即時実行エラー", e)
            renderPage(repo.owner, repo.name, Some(s),
              message = Some(s"送信エラー: ${e.getMessage}"), ctx = ctx)
        }
    }
  })

  // -------------------------------------------------------------------------
  // HTML 生成
  // -------------------------------------------------------------------------

  private def renderPage(
    owner:   String,
    repo:    String,
    setting: Option[DeadlineNotifySetting],
    message: Option[String],
    ctx:     String = ""
  ): String = {

    val hour                  = setting.map(_.sendHour).getOrElse(9)
    val minute                = setting.map(_.sendMinute).getOrElse(0)
    val days                  = setting.map(_.daysOfWeek.split(",").map(_.trim).toSet)
                                        .getOrElse(Set("1","2","3","4","5"))
    val enabled               = setting.map(_.enabled).getOrElse(false)
    val advanceNoticeDays     = setting.map(_.advanceNoticeDays).getOrElse("30,7")
    val dailyNotifyWithinDays = setting.map(_.dailyNotifyWithinDays).getOrElse(7)
    val notifyOverdue         = setting.map(_.notifyOverdue).getOrElse(false)
    val notifyNoDeadline      = setting.map(_.notifyNoDeadline).getOrElse(false)
    val notifyNotStarted      = setting.map(_.notifyNotStarted).getOrElse(false)
    val notifyToCreator       = setting.map(_.notifyToCreator).getOrElse(true)
    val notifyToAssignee      = setting.map(_.notifyToAssignee).getOrElse(true)
    val sortOrder             = setting.map(_.sortOrder).getOrElse("NO_DEADLINE_FIRST")

    val msgHtml = HtmlUtil.alertHtml(message)

    val dayCheckboxes = HtmlUtil.dayCheckboxes(days)
    val hourOptions   = HtmlUtil.hourOptions(hour)
    val minuteOptions = HtmlUtil.minuteOptions(minute)

    def radioSortOrder(value: String, label: String) = {
      val checked = if (sortOrder == value) "checked" else ""
      s"""<label class="radio-inline" style="font-weight:normal;">
         |  <input type="radio" name="sortOrder" value="$value" $checked> $label
         |</label>""".stripMargin
    }

    val ctxE = HtmlUtil.escHtml(ctx)
    val deleteBtn = if (setting.isDefined)
      s"""<form method="post" action="$ctxE/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/deadline-notify/delete"
         |      style="display:inline;">
         |  <button type="submit" class="btn btn-danger btn-sm"
         |    onclick="return confirm('期日通知設定を削除しますか？')">削除</button>
         |</form>""".stripMargin
    else ""

    val sendNowBtn = if (setting.isDefined)
      s"""<form method="post" action="$ctxE/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/deadline-notify/send-now"
         |      style="display:inline;">
         |  <button type="submit" class="btn btn-default btn-sm">今すぐ送信</button>
         |</form>""".stripMargin
    else ""

    val content =
      s"""$msgHtml
         |<form method="post" action="$ctxE/${HtmlUtil.escHtml(owner)}/${HtmlUtil.escHtml(repo)}/issues/deadline-notify">
         |
         |  <div class="form-group">
         |    <label class="control-label"><strong>通知条件</strong></label>
         |
         |    <div style="margin-top:8px; margin-bottom:8px;">
         |      <label style="font-weight:normal; display:block; margin-bottom:4px;">
         |        期日のN日前に1度だけ通知（カンマ区切りで複数指定可）
         |      </label>
         |      <input type="text" name="advanceNoticeDays"
         |             value="${HtmlUtil.escHtml(advanceNoticeDays)}"
         |             class="form-control" style="width:220px; display:inline-block;"
         |             placeholder="例: 30,14,7">
         |      <span class="help-block" style="display:inline; margin-left:8px;">
         |        空欄の場合は事前通知なし
         |      </span>
         |    </div>
         |
         |    <div style="margin-bottom:8px;">
         |      <label style="font-weight:normal; display:block; margin-bottom:4px;">
         |        期日のN日以内を毎日通知（0 で無効）
         |      </label>
         |      <input type="number" name="dailyNotifyWithinDays"
         |             value="$dailyNotifyWithinDays" min="0" max="365"
         |             class="form-control" style="width:100px; display:inline-block;">
         |      <span class="help-block" style="display:inline; margin-left:8px;">日</span>
         |    </div>
         |
         |    <div class="checkbox">
         |      <label style="font-weight:normal;">
         |        <input type="checkbox" name="notifyOverdue" value="true" ${HtmlUtil.checked(notifyOverdue)}>
         |        期日超過は毎日通知
         |      </label>
         |    </div>
         |    <div class="checkbox">
         |      <label style="font-weight:normal;">
         |        <input type="checkbox" name="notifyNoDeadline" value="true" ${HtmlUtil.checked(notifyNoDeadline)}>
         |        期日なしのIssueも毎日通知
         |      </label>
         |    </div>
         |    <div class="checkbox">
         |      <label style="font-weight:normal;">
         |        <input type="checkbox" name="notifyNotStarted" value="true" ${HtmlUtil.checked(notifyNotStarted)}>
         |        開始予定日が過ぎているのに進捗が0%のIssueを毎日通知
         |      </label>
         |    </div>
         |  </div>
         |
         |  <div class="form-group">
         |    <label class="control-label"><strong>通知先</strong></label><br>
         |    <label class="checkbox-inline" style="font-weight:normal;">
         |      <input type="checkbox" name="notifyToCreator" value="true" ${HtmlUtil.checked(notifyToCreator)}>
         |      Issue作成者
         |    </label>
         |    <label class="checkbox-inline" style="font-weight:normal;">
         |      <input type="checkbox" name="notifyToAssignee" value="true" ${HtmlUtil.checked(notifyToAssignee)}>
         |      Issue担当者
         |    </label>
         |  </div>
         |
         |  <div class="form-group">
         |    <label class="control-label"><strong>メール内 Issue のソート順</strong></label><br>
         |    ${radioSortOrder("NO_DEADLINE_FIRST", "期日なし先頭 → 期日近い順")}
         |    ${radioSortOrder("DUE_DATE_ASC", "期日近い順（期日なし末尾）")}
         |    ${radioSortOrder("ISSUE_ID_ASC", "Issue ID 昇順")}
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
         |        <strong>期日通知を有効にする</strong>
         |      </label>
         |    </div>
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

    HtmlUtil.pageShell(
      title       = "期日通知設定",
      owner       = owner,
      repo        = repo,
      pageIcon    = "clock",
      pageTitle   = "期日通知設定",
      content     = content,
      contextPath = ctx
    )
  }

}
