package io.github.takahino.reporter.scheduler

import io.github.takahino.reporter.service.{DeadlineNotifyRepository, DeadlineNotifyService, MailScheduleRepository, MailSendService}
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import javax.sql.DataSource
import scala.util.Try

object MailScheduler {

  private val logger = LoggerFactory.getLogger(getClass)
  private var executor: ScheduledExecutorService = _
  private var ds: DataSource = _

  def start(dataSource: DataSource): Unit = {
    ds = dataSource
    executor = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "issue-reporter-scheduler")
      t.setDaemon(true)
      t
    }
    // 次の分の00秒まで待ってから毎分実行
    val delaySec = 60 - LocalDateTime.now().getSecond
    executor.scheduleAtFixedRate(() => tick(), delaySec, 60, TimeUnit.SECONDS)
    logger.info("[MailScheduler] 起動しました。")
  }

  def stop(): Unit = {
    if (executor != null) {
      executor.shutdownNow()
      logger.info("[MailScheduler] 停止しました。")
    }
  }

  private def tick(): Unit = {
    val now = LocalDateTime.now()
    val conn = ds.getConnection()
    try {
      // Excel メール送信スケジュール
      val due = MailScheduleRepository.findDueSchedules(conn, now)
      due.foreach { schedule =>
        Try(MailSendService.send(schedule, conn)) match {
          case scala.util.Success(_) =>
            MailScheduleRepository.updateLastSentAt(conn, schedule.id, now)
          case scala.util.Failure(e) =>
            logger.error(s"[MailScheduler] 送信失敗: ${schedule.owner}/${schedule.repository}", e)
        }
      }

      // 期日通知スケジュール
      val enabledDeadlineSettings = DeadlineNotifyRepository.findAllEnabled(conn)
      enabledDeadlineSettings.foreach { s =>
        if (isDeadlineNotifyDue(s, now)) {
          Try(DeadlineNotifyService.run(conn, s)) match {
            case scala.util.Failure(e) =>
              logger.error(s"[MailScheduler] 期日通知失敗: ${s.owner}/${s.repository}", e)
            case _ =>
          }
        }
      }
    } catch {
      case e: Exception =>
        logger.error("[MailScheduler] tick 処理中にエラーが発生しました", e)
    } finally {
      conn.close()
    }
  }

  private def isDeadlineNotifyDue(s: io.github.takahino.reporter.model.DeadlineNotifySetting, now: LocalDateTime): Boolean = {
    val dow = now.getDayOfWeek.getValue // 1=Mon, 7=Sun
    now.getHour   == s.sendHour &&
    now.getMinute == s.sendMinute &&
    s.daysOfWeek.split(",").map(_.trim).contains(dow.toString)
  }
}
