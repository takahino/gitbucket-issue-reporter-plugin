package io.github.takahino.reporter.service

import io.github.takahino.reporter.model.DeadlineNotifySetting
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.util.Try

/**
 * 期日通知ロジック。
 *
 * 通知タイプ:
 *   dueDate == null              → NO_DEADLINE（期日なし）毎日
 *   daysUntilDue < 0             → OVERDUE（期日超過）毎日
 *   daysUntilDue <= dailyNotifyWithinDays（>0） → DAILY 毎日
 *   daysUntilDue == N（advanceNoticeDaysに含まれる） → BEFORE_N 1度だけ
 */
object DeadlineNotifyService {

  private val logger = LoggerFactory.getLogger(getClass)

  val TYPE_NO_DEADLINE  = "NO_DEADLINE"
  val TYPE_OVERDUE      = "OVERDUE"
  val TYPE_DAILY        = "DAILY"
  val TYPE_NOT_STARTED  = "NOT_STARTED"
  // 事前通知は動的: s"BEFORE_$n"

  /**
   * @param skipSentCheck true のとき送信済みチェックをスキップ（今すぐ送信用）
   * @return 送信したメール通数（宛先ベース）
   */
  def run(conn: Connection, setting: DeadlineNotifySetting, skipSentCheck: Boolean = false): Int = {
    val today  = LocalDate.now()
    val issues = loadOpenIssuesWithDueDate(conn, setting.owner, setting.repository)

    val sentKeys = if (skipSentCheck) Set.empty[(Int, String)]
                   else DeadlineNotifyRepository.findAlreadySentKeys(conn, setting.owner, setting.repository, today)

    val pendingBuf = mutable.Buffer.empty[(IssueWithDue, String)]
    issues.foreach { issue =>
      // 期日ベース通知
      determineNotifyType(issue.dueDate, today, setting).foreach { notifyType =>
        if (!sentKeys.contains((issue.issueId, notifyType))) pendingBuf += ((issue, notifyType))
      }
      // 着手漏れ通知（独立して評価）
      determineNotStartedType(issue, today, setting).foreach { notifyType =>
        if (!sentKeys.contains((issue.issueId, notifyType))) pendingBuf += ((issue, notifyType))
      }
    }
    val pending: Seq[(IssueWithDue, String)] = pendingBuf.toSeq

    if (pending.isEmpty) {
      Try(DeadlineNotifyRepository.cleanOldLogs(conn))
      return 0
    }

    val baseUrl = MailSendService.loadBaseUrl()

    val byRecipient = mutable.LinkedHashMap.empty[String, mutable.Buffer[(IssueWithDue, String)]]
    pending.foreach { case (issue, notifyType) =>
      resolveRecipients(conn, setting, issue).foreach { addr =>
        byRecipient.getOrElseUpdate(addr, mutable.Buffer.empty) += ((issue, notifyType))
      }
    }

    val successfulIssueKeys = mutable.Set.empty[(Int, String)]
    var emailsSent = 0

    byRecipient.foreach { case (addr, items) =>
      Try(sendConsolidatedNotification(setting, items.toSeq, today, addr, baseUrl)) match {
        case scala.util.Success(_) =>
          items.foreach { case (issue, notifyType) => successfulIssueKeys += ((issue.issueId, notifyType)) }
          emailsSent += 1
          logger.info(s"[DeadlineNotify] 送信完了: -> $addr (${items.size}件)")
        case scala.util.Failure(e) =>
          logger.error(s"[DeadlineNotify] 送信失敗: -> $addr", e)
      }
    }

    if (!skipSentCheck) {
      successfulIssueKeys.foreach { case (issueId, notifyType) =>
        DeadlineNotifyRepository.recordSent(conn, setting.owner, setting.repository, issueId, notifyType, today)
      }
    }

    Try(DeadlineNotifyRepository.cleanOldLogs(conn))
    emailsSent
  }

  /**
   * 通知タイプを決定する。通知対象外の場合は None を返す。
   *
   * 優先順位:
   *   1. 期日なし → NO_DEADLINE
   *   2. 超過     → OVERDUE
   *   3. N日以内  → DAILY（dailyNotifyWithinDays > 0 の場合）
   *   4. ぴったりN日前 → BEFORE_N（advanceNoticeDays に含まれる場合）
   */
  def determineNotifyType(
    dueDate: Option[LocalDate],
    today:   LocalDate,
    setting: DeadlineNotifySetting
  ): Option[String] = {
    dueDate match {
      case None =>
        if (setting.notifyNoDeadline) Some(TYPE_NO_DEADLINE) else None

      case Some(due) =>
        val days = ChronoUnit.DAYS.between(today, due).toInt  // 負 = 超過
        if (days < 0) {
          if (setting.notifyOverdue) Some(TYPE_OVERDUE) else None
        } else if (setting.dailyNotifyWithinDays > 0 && days <= setting.dailyNotifyWithinDays) {
          Some(TYPE_DAILY)
        } else {
          parseAdvanceNoticeDays(setting.advanceNoticeDays).find(_ == days).map(n => s"BEFORE_$n")
        }
    }
  }

  /** 着手漏れ通知タイプを決定する。対象外の場合は None を返す。 */
  private def determineNotStartedType(
    issue:   IssueWithDue,
    today:   LocalDate,
    setting: DeadlineNotifySetting
  ): Option[String] = {
    if (!setting.notifyNotStarted) return None
    issue.startDate match {
      case Some(start) if start.isBefore(today) && issue.progress.forall(_ == 0) =>
        Some(TYPE_NOT_STARTED)
      case _ => None
    }
  }

  /** "30,7,14" → Seq(30, 7, 14)（正の整数のみ） */
  private def parseAdvanceNoticeDays(s: String): Seq[Int] =
    if (s == null || s.trim.isEmpty) Seq.empty
    else s.split(",").flatMap(_.trim.toIntOption).filter(_ > 0).toSeq

  // ---------------------------------------------------------------------------
  // Issue 読み込み
  // ---------------------------------------------------------------------------

  case class IssueWithDue(
    issueId:   Int,
    title:     String,
    creator:   String,
    assignees: Seq[String],
    dueDate:   Option[LocalDate],
    startDate: Option[LocalDate] = None,
    progress:  Option[Int]       = None
  )

  def loadOpenIssuesWithDueDate(conn: Connection, owner: String, repository: String): Seq[IssueWithDue] = {
    val assigneeMap  = loadAssigneeMap(conn, owner, repository)
    val periodMap    = IssuePeriodRepository.findAllPeriods(conn, owner, repository)
    val periodEndMap = periodMap.flatMap { case (id, p) =>
      p.endDate.flatMap(parseDateStr).map(d => id -> d)
    }

    val sql =
      """SELECT i.ISSUE_ID, i.TITLE, i.USER_NAME AS CREATOR,
        |       COALESCE(m.DUE_DATE, NULL) AS MILESTONE_DUE_DATE
        |FROM ISSUE i
        |LEFT JOIN MILESTONE m ON m.MILESTONE_ID = i.MILESTONE_ID
        |  AND m.USER_NAME = i.USER_NAME
        |  AND m.REPOSITORY_NAME = i.REPOSITORY_NAME
        |WHERE i.USER_NAME = ?
        |  AND i.REPOSITORY_NAME = ?
        |  AND i.CLOSED = FALSE
        |  AND i.PULL_REQUEST = FALSE
        |ORDER BY i.ISSUE_ID""".stripMargin

    val ps  = conn.prepareStatement(sql)
    ps.setString(1, owner); ps.setString(2, repository)
    val rs  = ps.executeQuery()
    val buf = mutable.Buffer.empty[IssueWithDue]

    while (rs.next()) {
      val issueId = rs.getInt("ISSUE_ID")
      val dueDate: Option[LocalDate] = periodEndMap.get(issueId).orElse {
        val d = rs.getDate("MILESTONE_DUE_DATE")
        if (d == null) None else Some(d.toLocalDate)
      }
      val period    = periodMap.get(issueId)
      val startDate = period.flatMap(p => p.startDate.flatMap(s => Try(LocalDate.parse(s)).toOption))
      val progress  = period.flatMap(_.progress)
      buf += IssueWithDue(
        issueId   = issueId,
        title     = rs.getString("TITLE"),
        creator   = rs.getString("CREATOR"),
        assignees = assigneeMap.getOrElse(issueId, Seq.empty),
        dueDate   = dueDate,
        startDate = startDate,
        progress  = progress
      )
    }
    rs.close(); ps.close()
    buf.toSeq
  }

  private def loadAssigneeMap(conn: Connection, owner: String, repository: String): Map[Int, Seq[String]] = {
    val sql =
      """SELECT ISSUE_ID, ASSIGNEE_USER_NAME
        |FROM ISSUE_ASSIGNEE
        |WHERE USER_NAME = ? AND REPOSITORY_NAME = ?""".stripMargin
    val ps  = conn.prepareStatement(sql)
    ps.setString(1, owner); ps.setString(2, repository)
    val rs  = ps.executeQuery()
    val map = mutable.Map.empty[Int, mutable.Buffer[String]]
    while (rs.next()) {
      val id   = rs.getInt("ISSUE_ID")
      val user = rs.getString("ASSIGNEE_USER_NAME")
      map.getOrElseUpdate(id, mutable.Buffer.empty) += user
    }
    rs.close(); ps.close()
    map.view.mapValues(_.toSeq).toMap
  }

  private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

  private def parseDateStr(s: String): Option[LocalDate] = {
    if (s == null || s.length < 10) None
    else Try(LocalDate.parse(s.take(10), dateFmt)).toOption
  }

  // ---------------------------------------------------------------------------
  // 宛先解決
  // ---------------------------------------------------------------------------

  private def resolveRecipients(conn: Connection, setting: DeadlineNotifySetting, issue: IssueWithDue): Seq[String] = {
    val userNames = mutable.Buffer.empty[String]
    if (setting.notifyToCreator)  userNames += issue.creator
    if (setting.notifyToAssignee) userNames ++= issue.assignees
    MailScheduleRepository.findMailAddresses(conn, userNames.distinct.toSeq)
  }

  // ---------------------------------------------------------------------------
  // メール送信
  // ---------------------------------------------------------------------------

  private def sendConsolidatedNotification(
    setting:   DeadlineNotifySetting,
    items:     Seq[(IssueWithDue, String)],
    today:     LocalDate,
    recipient: String,
    baseUrl:   String
  ): Unit = {
    val repoRef = s"${setting.owner}/${setting.repository}"

    val subject =
      if (items.size == 1) {
        val (issue, notifyType) = items.head
        s"[GitBucket] 期日通知: $repoRef #${issue.issueId} ${notifyTypeLabel(notifyType, issue.dueDate, today)}"
      } else {
        s"[GitBucket] 期日通知: $repoRef (${items.size}件)"
      }

    val sorted = setting.sortOrder match {
      case "DUE_DATE_ASC" =>
        items.sortWith { case ((a, _), (b, _)) =>
          (a.dueDate, b.dueDate) match {
            case (Some(da), Some(db)) => da.isBefore(db)
            case (Some(_),  None)     => true
            case (None,     Some(_))  => false
            case (None,     None)     => a.issueId < b.issueId
          }
        }
      case "ISSUE_ID_ASC" =>
        items.sortBy { case (issue, _) => issue.issueId }
      case _ => // "NO_DEADLINE_FIRST"
        items.sortWith { case ((a, _), (b, _)) =>
          (a.dueDate, b.dueDate) match {
            case (Some(da), Some(db)) => da.isBefore(db)
            case (Some(_),  None)     => false
            case (None,     Some(_))  => true
            case (None,     None)     => a.issueId < b.issueId
          }
        }
    }

    val issueLines = sorted.map { case (issue, notifyType) =>
      val dueDateStr = issue.dueDate.map(_.format(dateFmt)).getOrElse("（期日なし）")
      val typeLabel  = notifyTypeLabel(notifyType, issue.dueDate, today)
      val issuePath  = s"/$repoRef/issues/${issue.issueId}"
      val issueUrl   = if (baseUrl.nonEmpty) baseUrl + issuePath else issuePath
      s"  #${issue.issueId} ${issue.title}\n  期日: $dueDateStr  状態: $typeLabel\n  URL: $issueUrl"
    }.mkString("\n\n")

    val body = s"$repoRef の以下の Issue について期日通知をお送りします。\n\n$issueLines\n"

    val email = MailSendService.buildEmail(subject, body)
    try email.addTo(recipient)
    catch { case e: Exception => throw new Exception(s"無効なメールアドレス: $recipient", e) }
    email.send()
  }

  private def notifyTypeLabel(notifyType: String, dueDate: Option[LocalDate], today: LocalDate): String =
    notifyType match {
      case TYPE_NOT_STARTED => "【未着手（開始予定日超過）】"
      case TYPE_NO_DEADLINE => "期日未設定"
      case TYPE_OVERDUE =>
        val days = ChronoUnit.DAYS.between(dueDate.getOrElse(today), today).toInt
        s"期日超過（${days}日経過）"
      case TYPE_DAILY =>
        val days = ChronoUnit.DAYS.between(today, dueDate.getOrElse(today)).toInt
        s"期日まで${days}日"
      case t if t.startsWith("BEFORE_") =>
        val n = t.drop(7)
        s"期日${n}日前"
      case other => other
    }
}
