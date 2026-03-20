package io.github.takahino.reporter.service

import org.apache.commons.mail.HtmlEmail
import io.github.takahino.reporter.model.MailSchedule
import org.slf4j.LoggerFactory

import java.io.{ByteArrayOutputStream, FileInputStream}
import java.sql.Connection
import java.util.Properties
import scala.util.Try

/**
 * gitbucket.conf を直接読んで HtmlEmail を組み立て、Excel を添付送信する。
 * GitBucket バージョンに依存した Mailer.createMail() を使わないことで
 * 4.36 / 4.46 どちらでも動作する。
 */
object MailSendService {

  private val logger = LoggerFactory.getLogger(getClass)

  def send(schedule: MailSchedule, conn: Connection): Unit = {
    // 宛先ユーザー名からメールアドレスを解決
    val userNames = schedule.recipients.split(",").map(_.trim).filter(_.nonEmpty).toSeq
    logger.info(s"[MailSendService] 宛先ユーザー: ${userNames.mkString(", ")}")
    val addresses = MailScheduleRepository.findMailAddresses(conn, userNames)
    logger.info(s"[MailSendService] 解決済みメールアドレス: ${addresses.mkString(", ")}")

    if (addresses.isEmpty) {
      throw new Exception(
        s"有効な宛先メールアドレスが見つかりません（ユーザー: ${userNames.mkString(", ")}）。" +
        "GitBucket の各ユーザープロフィールにメールアドレスが設定されているか確認してください。"
      )
    }

    // Excel を ByteArray として生成
    val periods = IssuePeriodRepository.findAllPeriods(conn, schedule.owner, schedule.repository)
    val issues  = IssueReportService.mergeWithPeriods(
      IssueReportService.loadIssues(conn, schedule.owner, schedule.repository, loadBaseUrl()),
      periods)
    val baos    = new ByteArrayOutputStream()
    IssueReportService.generateExcel(issues, baos, schedule.columnOrder)
    val excelBytes = baos.toByteArray

    val filename = s"issues-${schedule.owner}-${schedule.repository}.xlsx"
    val subject  = s"[GitBucket] Issue レポート ${schedule.owner}/${schedule.repository}"
    val textBody = s"${schedule.owner}/${schedule.repository} の Issue レポートを添付します。\n" +
                   s"Issue 件数: ${issues.size}"

    val email = buildEmail(subject, textBody)

    addresses.foreach { addr =>
      try email.addTo(addr)
      catch { case e: Exception => logger.warn(s"[MailSendService] 無効なメールアドレス: $addr", e) }
    }

    // Excel ファイルを添付
    val ds = new javax.mail.util.ByteArrayDataSource(
      excelBytes,
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    email.attach(ds, filename, "Issue Report")

    email.send()
    logger.info(s"[MailSendService] 送信完了: ${schedule.owner}/${schedule.repository} -> ${addresses.mkString(", ")}")
  }

  /**
   * gitbucket.conf から SMTP 設定を読んで HtmlEmail を構築して返す。
   * Mailer.createMail() の代替として GitBucket バージョンに非依存で動作する。
   */
  def buildEmail(subject: String, textBody: String): HtmlEmail = {
    val props = loadGitBucketConf()

    if (props.getProperty("useSMTP", "false") != "true")
      throw new Exception("SMTP が無効です（gitbucket.conf の useSMTP=false）。管理画面で SMTP を有効にしてください。")

    val smtpHost = props.getProperty("smtp.host", "")
    if (smtpHost.isEmpty)
      throw new Exception("SMTP ホストが設定されていません（gitbucket.conf の smtp.host が空）。")

    val smtpPort = Option(props.getProperty("smtp.port")).flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(25)
    val smtpUser = Option(props.getProperty("smtp.user")).filter(_.nonEmpty)
    val smtpPass = Option(props.getProperty("smtp.password")).filter(_.nonEmpty)
    val smtpSsl  = props.getProperty("smtp.ssl", "false") == "true"
    val starttls = props.getProperty("smtp.starttls", "false") == "true"
    val fromAddr = Option(props.getProperty("smtp.fromAddress")).filter(_.nonEmpty)
                     .getOrElse(s"gitbucket@$smtpHost")
    val fromName = Option(props.getProperty("smtp.fromName")).filter(_.nonEmpty)
                     .getOrElse("GitBucket")

    logger.info(s"[MailSendService] SMTP: $smtpHost:$smtpPort ssl=$smtpSsl starttls=$starttls from=$fromAddr")

    val email = new HtmlEmail()
    email.setHostName(smtpHost)
    email.setSmtpPort(smtpPort)
    smtpUser.foreach { u => email.setAuthentication(u, smtpPass.getOrElse("")) }
    if (smtpSsl)  email.setSSLOnConnect(true)
    if (starttls) email.setStartTLSEnabled(true)
    email.setFrom(fromAddr, fromName)
    email.setSubject(subject)
    email.setTextMsg(textBody)
    email
  }

  /**
   * gitbucket.conf の baseUrl を返す。
   * 管理画面で未設定の場合は空文字。末尾スラッシュは除去する。
   */
  def loadBaseUrl(): String = {
    Try {
      val props = loadGitBucketConf()
      Option(props.getProperty("base_url", "")).map(_.stripSuffix("/")).getOrElse("")
    }.getOrElse("")
  }

  private def loadGitBucketConf(): Properties = {
    val home = Option(System.getProperty("gitbucket.home"))
      .orElse(Option(System.getenv("GITBUCKET_HOME")))
      .getOrElse(System.getProperty("user.home") + "/.gitbucket")
    val confFile = new java.io.File(home, "gitbucket.conf")
    logger.info(s"[MailSendService] gitbucket.conf 読み込み: ${confFile.getAbsolutePath}")
    val props = new Properties()
    val fis   = new FileInputStream(confFile)
    try props.load(fis) finally fis.close()
    props
  }
}
