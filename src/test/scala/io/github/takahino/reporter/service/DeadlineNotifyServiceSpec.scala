package io.github.takahino.reporter.service

import io.github.takahino.reporter.model.DeadlineNotifySetting
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class DeadlineNotifyServiceSpec extends AnyFunSuite with Matchers {

  private val today = LocalDate.of(2026, 3, 20)

  /**
   * テスト用設定ファクトリ。指定パラメータだけ変更し、それ以外はデフォルト無効。
   */
  private def setting(
    advanceNoticeDays:     String  = "",
    dailyNotifyWithinDays: Int     = 0,
    notifyOverdue:         Boolean = false,
    notifyNoDeadline:      Boolean = false
  ): DeadlineNotifySetting = DeadlineNotifySetting(
    id                    = 1,
    owner                 = "owner",
    repository            = "repo",
    advanceNoticeDays     = advanceNoticeDays,
    dailyNotifyWithinDays = dailyNotifyWithinDays,
    notifyOverdue         = notifyOverdue,
    notifyNoDeadline      = notifyNoDeadline,
    notifyToCreator       = true,
    notifyToAssignee      = false,
    sendHour              = 9,
    sendMinute            = 0,
    daysOfWeek            = "1,2,3,4,5",
    enabled               = true,
    sortOrder             = "NO_DEADLINE_FIRST",
    notifyNotStarted      = false
  )

  // -------------------------------------------------------------------------
  // 期日なし (NO_DEADLINE)
  // -------------------------------------------------------------------------

  test("期日なし: notifyNoDeadline=true → NO_DEADLINE") {
    DeadlineNotifyService.determineNotifyType(None, today, setting(notifyNoDeadline = true)) shouldBe
      Some(DeadlineNotifyService.TYPE_NO_DEADLINE)
  }

  test("期日なし: notifyNoDeadline=false → None") {
    DeadlineNotifyService.determineNotifyType(None, today, setting()) shouldBe None
  }

  // -------------------------------------------------------------------------
  // 期日超過 (OVERDUE)
  // -------------------------------------------------------------------------

  test("期日超過1日: notifyOverdue=true → OVERDUE") {
    DeadlineNotifyService.determineNotifyType(Some(today.minusDays(1)), today, setting(notifyOverdue = true)) shouldBe
      Some(DeadlineNotifyService.TYPE_OVERDUE)
  }

  test("期日超過30日: notifyOverdue=true → OVERDUE") {
    DeadlineNotifyService.determineNotifyType(Some(today.minusDays(30)), today, setting(notifyOverdue = true)) shouldBe
      Some(DeadlineNotifyService.TYPE_OVERDUE)
  }

  test("期日超過: notifyOverdue=false → None") {
    DeadlineNotifyService.determineNotifyType(Some(today.minusDays(1)), today, setting()) shouldBe None
  }

  // -------------------------------------------------------------------------
  // N日以内毎日通知 (DAILY)
  // -------------------------------------------------------------------------

  test("残り0日: dailyNotifyWithinDays=7 → DAILY") {
    DeadlineNotifyService.determineNotifyType(Some(today), today, setting(dailyNotifyWithinDays = 7)) shouldBe
      Some(DeadlineNotifyService.TYPE_DAILY)
  }

  test("残り3日: dailyNotifyWithinDays=7 → DAILY") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(3)), today, setting(dailyNotifyWithinDays = 7)) shouldBe
      Some(DeadlineNotifyService.TYPE_DAILY)
  }

  test("残り7日: dailyNotifyWithinDays=7 → DAILY") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(7)), today, setting(dailyNotifyWithinDays = 7)) shouldBe
      Some(DeadlineNotifyService.TYPE_DAILY)
  }

  test("残り8日: dailyNotifyWithinDays=7 → None（しきい値外）") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(8)), today, setting(dailyNotifyWithinDays = 7)) shouldBe None
  }

  test("残り14日: dailyNotifyWithinDays=14 → DAILY") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(14)), today, setting(dailyNotifyWithinDays = 14)) shouldBe
      Some(DeadlineNotifyService.TYPE_DAILY)
  }

  test("dailyNotifyWithinDays=0 → 毎日通知無効") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(3)), today, setting(dailyNotifyWithinDays = 0)) shouldBe None
  }

  // -------------------------------------------------------------------------
  // N日前1度のみ通知 (BEFORE_N)
  // -------------------------------------------------------------------------

  test("残り30日: advanceNoticeDays='30' → BEFORE_30") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(30)), today, setting(advanceNoticeDays = "30")) shouldBe
      Some("BEFORE_30")
  }

  test("残り7日: advanceNoticeDays='7' → BEFORE_7") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(7)), today, setting(advanceNoticeDays = "7")) shouldBe
      Some("BEFORE_7")
  }

  test("残り14日: advanceNoticeDays='30,14,7' → BEFORE_14") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(14)), today, setting(advanceNoticeDays = "30,14,7")) shouldBe
      Some("BEFORE_14")
  }

  test("残り29日: advanceNoticeDays='30' → None（ぴったりでない）") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(29)), today, setting(advanceNoticeDays = "30")) shouldBe None
  }

  test("残り6日: advanceNoticeDays='7' → None（ぴったりでない）") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(6)), today, setting(advanceNoticeDays = "7")) shouldBe None
  }

  // -------------------------------------------------------------------------
  // DAILY が BEFORE_N より優先
  // -------------------------------------------------------------------------

  test("残り7日: dailyNotifyWithinDays=7 かつ advanceNoticeDays='7' → DAILY が優先") {
    DeadlineNotifyService.determineNotifyType(
      Some(today.plusDays(7)), today,
      setting(dailyNotifyWithinDays = 7, advanceNoticeDays = "7")
    ) shouldBe Some(DeadlineNotifyService.TYPE_DAILY)
  }

  // -------------------------------------------------------------------------
  // 全条件無効
  // -------------------------------------------------------------------------

  test("全条件無効: 期日なし → None") {
    DeadlineNotifyService.determineNotifyType(None, today, setting()) shouldBe None
  }

  test("全条件無効: 期日超過 → None") {
    DeadlineNotifyService.determineNotifyType(Some(today.minusDays(1)), today, setting()) shouldBe None
  }

  test("全条件無効: 残り3日 → None") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(3)), today, setting()) shouldBe None
  }

  test("全条件無効: 残り30日 → None") {
    DeadlineNotifyService.determineNotifyType(Some(today.plusDays(30)), today, setting()) shouldBe None
  }
}
