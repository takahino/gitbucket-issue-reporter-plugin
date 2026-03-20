package io.github.takahino.reporter.service

import io.github.takahino.reporter.model.MailSchedule
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import java.time.LocalDateTime

class MailScheduleRepositorySpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  private var conn: Connection = _

  override def beforeEach(): Unit = {
    conn = DriverManager.getConnection("jdbc:h2:mem:test_mail_schedule;DB_CLOSE_DELAY=-1", "sa", "")
    MailScheduleRepository.createTablesIfNotExists(conn)
    MailScheduleRepository.alterTablesIfNeeded(conn)
  }

  override def afterEach(): Unit = {
    conn.createStatement().execute("DROP TABLE IF EXISTS REPORTER_MAIL_SCHEDULE")
    conn.close()
  }

  // 月曜日 2026-03-23 10:30 (getDayOfWeek.getValue = 1)
  private val mondayAt1030: LocalDateTime = LocalDateTime.of(2026, 3, 23, 10, 30)

  private def insertSchedule(
    owner:       String                = "owner",
    repo:        String                = "repo",
    recipients:  String                = "alice",
    hour:        Int                   = 10,
    minute:      Int                   = 30,
    daysOfWeek:  String                = "1",        // 月曜日
    enabled:     Boolean               = true,
    lastSentAt:  Option[LocalDateTime] = None,
    columnOrder: String                = ""
  ): Unit = {
    // upsert は LAST_SENT_AT を保存しないため、insert後に別途更新する
    val s = MailSchedule(0, owner, repo, recipients, hour, minute, daysOfWeek, enabled, None, columnOrder)
    MailScheduleRepository.upsert(conn, s)
    lastSentAt.foreach { at =>
      val id = MailScheduleRepository.findByRepo(conn, owner, repo).get.id
      MailScheduleRepository.updateLastSentAt(conn, id, at)
    }
  }

  // -------------------------------------------------------------------------
  // findDueSchedules (isDue ロジックの間接テスト)
  // -------------------------------------------------------------------------

  test("時刻・曜日が一致する → スケジュールを返す") {
    insertSchedule()
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) should have size 1
  }

  test("時刻（分）が不一致 → 返さない") {
    insertSchedule()
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030.withMinute(31)) shouldBe empty
  }

  test("時刻（時）が不一致 → 返さない") {
    insertSchedule()
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030.withHour(11)) shouldBe empty
  }

  test("曜日が不一致（火曜日設定を月曜日に実行） → 返さない") {
    insertSchedule(daysOfWeek = "2")  // 火曜日
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) shouldBe empty
  }

  test("曜日が複数設定で一致 → 返す") {
    insertSchedule(daysOfWeek = "1,2,3,4,5")  // 平日
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) should have size 1
  }

  test("enabled=false → 返さない") {
    insertSchedule(enabled = false)
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) shouldBe empty
  }

  test("lastSentAt が null（未送信）→ 返す") {
    insertSchedule(lastSentAt = None)
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) should have size 1
  }

  test("lastSentAt が59分前 → 返す（境界値）") {
    insertSchedule(lastSentAt = Some(mondayAt1030.minusMinutes(59)))
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) should have size 1
  }

  test("lastSentAt が58分前 → 返さない") {
    insertSchedule(lastSentAt = Some(mondayAt1030.minusMinutes(58)))
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) shouldBe empty
  }

  test("lastSentAt が60分前 → 返す") {
    insertSchedule(lastSentAt = Some(mondayAt1030.minusMinutes(60)))
    MailScheduleRepository.findDueSchedules(conn, mondayAt1030) should have size 1
  }

  // -------------------------------------------------------------------------
  // upsert
  // -------------------------------------------------------------------------

  test("upsert: 新規スケジュールを登録できる") {
    insertSchedule(owner = "myorg", repo = "myrepo", hour = 14, minute = 0)
    val result = MailScheduleRepository.findByRepo(conn, "myorg", "myrepo")
    result shouldBe defined
    result.get.hour   shouldBe 14
    result.get.minute shouldBe 0
  }

  test("upsert: 既存スケジュールを更新できる") {
    insertSchedule(hour = 9, minute = 0)
    val original = MailScheduleRepository.findByRepo(conn, "owner", "repo").get
    MailScheduleRepository.upsert(conn, original.copy(hour = 14, minute = 30))
    val updated = MailScheduleRepository.findByRepo(conn, "owner", "repo").get
    updated.hour   shouldBe 14
    updated.minute shouldBe 30
  }

  test("upsert: 同じリポジトリへの重複登録でレコードが増えない") {
    insertSchedule()
    insertSchedule(hour = 11)  // 2回目（更新になるはず）
    MailScheduleRepository.findAll(conn) should have size 1
  }

  // -------------------------------------------------------------------------
  // delete
  // -------------------------------------------------------------------------

  test("delete: スケジュールを削除できる") {
    insertSchedule()
    MailScheduleRepository.delete(conn, "owner", "repo")
    MailScheduleRepository.findByRepo(conn, "owner", "repo") shouldBe None
  }

  test("delete: 存在しないリポジトリでもエラーにならない") {
    noException should be thrownBy {
      MailScheduleRepository.delete(conn, "no-owner", "no-repo")
    }
  }

  // -------------------------------------------------------------------------
  // updateLastSentAt
  // -------------------------------------------------------------------------

  test("updateLastSentAt: lastSentAtを更新できる") {
    insertSchedule()
    val id  = MailScheduleRepository.findByRepo(conn, "owner", "repo").get.id
    val now = LocalDateTime.of(2026, 3, 23, 10, 30)
    MailScheduleRepository.updateLastSentAt(conn, id, now)
    val updated = MailScheduleRepository.findByRepo(conn, "owner", "repo").get
    updated.lastSentAt shouldBe defined
    updated.lastSentAt.get.getHour   shouldBe 10
    updated.lastSentAt.get.getMinute shouldBe 30
  }

  // -------------------------------------------------------------------------
  // alterTablesIfNeeded
  // -------------------------------------------------------------------------

  test("alterTablesIfNeeded: 冪等性 — 2回呼び出してもエラーにならない") {
    noException should be thrownBy {
      MailScheduleRepository.alterTablesIfNeeded(conn)
    }
  }

  // -------------------------------------------------------------------------
  // columnOrder
  // -------------------------------------------------------------------------

  test("upsert: columnOrderを保存・取得できる") {
    insertSchedule(columnOrder = "issue_id,title,status")
    val result = MailScheduleRepository.findByRepo(conn, "owner", "repo")
    result shouldBe defined
    result.get.columnOrder shouldBe "issue_id,title,status"
  }

  test("upsert: columnOrderが空文字列でも保存できる") {
    insertSchedule(columnOrder = "")
    val result = MailScheduleRepository.findByRepo(conn, "owner", "repo")
    result shouldBe defined
    result.get.columnOrder shouldBe ""
  }

  test("upsert: 既存スケジュールのcolumnOrderを更新できる") {
    insertSchedule(columnOrder = "issue_id,title")
    val original = MailScheduleRepository.findByRepo(conn, "owner", "repo").get
    MailScheduleRepository.upsert(conn, original.copy(columnOrder = "status,creator,assignee"))
    val updated = MailScheduleRepository.findByRepo(conn, "owner", "repo").get
    updated.columnOrder shouldBe "status,creator,assignee"
  }
}
