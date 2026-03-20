package io.github.takahino.reporter.service

import io.github.takahino.reporter.model.DeadlineNotifySetting

import java.sql.{Connection, Date}
import java.time.LocalDate
import javax.sql.DataSource
import scala.collection.mutable

object DeadlineNotifyRepository {

  def createTablesIfNotExists(ds: DataSource): Unit = {
    val conn = ds.getConnection()
    try createTablesIfNotExists(conn)
    finally conn.close()
  }

  def createTablesIfNotExists(conn: Connection): Unit = {
    conn.createStatement().execute(
      """CREATE TABLE IF NOT EXISTS REPORTER_DEADLINE_NOTIFY_SETTING (
        |  ID                       INTEGER       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        |  OWNER                    VARCHAR(100)  NOT NULL,
        |  REPOSITORY               VARCHAR(100)  NOT NULL,
        |  ADVANCE_NOTICE_DAYS      VARCHAR(100)  NOT NULL DEFAULT '30,7',
        |  DAILY_NOTIFY_WITHIN_DAYS SMALLINT      NOT NULL DEFAULT 7,
        |  NOTIFY_OVERDUE           BOOLEAN       NOT NULL DEFAULT FALSE,
        |  NOTIFY_NO_DEADLINE       BOOLEAN       NOT NULL DEFAULT FALSE,
        |  NOTIFY_NOT_STARTED       BOOLEAN       NOT NULL DEFAULT FALSE,
        |  NOTIFY_TO_CREATOR        BOOLEAN       NOT NULL DEFAULT TRUE,
        |  NOTIFY_TO_ASSIGNEE       BOOLEAN       NOT NULL DEFAULT TRUE,
        |  SEND_HOUR                SMALLINT      NOT NULL DEFAULT 9,
        |  SEND_MINUTE              SMALLINT      NOT NULL DEFAULT 0,
        |  DAYS_OF_WEEK             VARCHAR(20)   NOT NULL DEFAULT '1,2,3,4,5',
        |  ENABLED                  BOOLEAN       NOT NULL DEFAULT FALSE,
        |  SORT_ORDER               VARCHAR(30)   NOT NULL DEFAULT 'NO_DEADLINE_FIRST'
        |)""".stripMargin
    )
    conn.createStatement().execute(
      """CREATE TABLE IF NOT EXISTS REPORTER_DEADLINE_NOTIFY_LOG (
        |  ID           INTEGER       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        |  OWNER        VARCHAR(100)  NOT NULL,
        |  REPOSITORY   VARCHAR(100)  NOT NULL,
        |  ISSUE_ID     INTEGER       NOT NULL,
        |  NOTIFY_TYPE  VARCHAR(30)   NOT NULL,
        |  SENT_DATE    DATE          NOT NULL
        |)""".stripMargin
    )
  }

  def alterTablesIfNeeded(ds: DataSource): Unit = {
    val conn = ds.getConnection()
    try {
      val st = conn.createStatement()
      st.execute(
        "ALTER TABLE REPORTER_DEADLINE_NOTIFY_SETTING " +
        "ADD COLUMN IF NOT EXISTS NOTIFY_NOT_STARTED BOOLEAN NOT NULL DEFAULT FALSE"
      )
      conn.commit()
    } finally { conn.close() }
  }

  def findByRepo(conn: Connection, owner: String, repository: String): Option[DeadlineNotifySetting] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM REPORTER_DEADLINE_NOTIFY_SETTING WHERE OWNER = ? AND REPOSITORY = ? LIMIT 1"
    )
    ps.setString(1, owner)
    ps.setString(2, repository)
    val rs = ps.executeQuery()
    val result = if (rs.next()) Some(fromRow(rs)) else None
    rs.close(); ps.close()
    result
  }

  def findAllEnabled(conn: Connection): Seq[DeadlineNotifySetting] = {
    val ps  = conn.prepareStatement("SELECT * FROM REPORTER_DEADLINE_NOTIFY_SETTING WHERE ENABLED = TRUE")
    val rs  = ps.executeQuery()
    val buf = mutable.Buffer.empty[DeadlineNotifySetting]
    while (rs.next()) buf += fromRow(rs)
    rs.close(); ps.close()
    buf.toSeq
  }

  def upsert(conn: Connection, s: DeadlineNotifySetting): Unit = {
    val existing = findByRepo(conn, s.owner, s.repository)
    if (existing.isDefined) {
      val ps = conn.prepareStatement(
        """UPDATE REPORTER_DEADLINE_NOTIFY_SETTING
          |SET ADVANCE_NOTICE_DAYS=?, DAILY_NOTIFY_WITHIN_DAYS=?,
          |    NOTIFY_OVERDUE=?, NOTIFY_NO_DEADLINE=?, NOTIFY_NOT_STARTED=?,
          |    NOTIFY_TO_CREATOR=?, NOTIFY_TO_ASSIGNEE=?,
          |    SEND_HOUR=?, SEND_MINUTE=?, DAYS_OF_WEEK=?, ENABLED=?, SORT_ORDER=?
          |WHERE OWNER=? AND REPOSITORY=?""".stripMargin
      )
      ps.setString(1, s.advanceNoticeDays)
      ps.setInt(2, s.dailyNotifyWithinDays)
      ps.setBoolean(3, s.notifyOverdue)
      ps.setBoolean(4, s.notifyNoDeadline)
      ps.setBoolean(5, s.notifyNotStarted)
      ps.setBoolean(6, s.notifyToCreator)
      ps.setBoolean(7, s.notifyToAssignee)
      ps.setInt(8, s.sendHour)
      ps.setInt(9, s.sendMinute)
      ps.setString(10, s.daysOfWeek)
      ps.setBoolean(11, s.enabled)
      ps.setString(12, s.sortOrder)
      ps.setString(13, s.owner)
      ps.setString(14, s.repository)
      ps.executeUpdate(); ps.close()
    } else {
      val ps = conn.prepareStatement(
        """INSERT INTO REPORTER_DEADLINE_NOTIFY_SETTING
          |(OWNER, REPOSITORY, ADVANCE_NOTICE_DAYS, DAILY_NOTIFY_WITHIN_DAYS,
          | NOTIFY_OVERDUE, NOTIFY_NO_DEADLINE, NOTIFY_NOT_STARTED,
          | NOTIFY_TO_CREATOR, NOTIFY_TO_ASSIGNEE,
          | SEND_HOUR, SEND_MINUTE, DAYS_OF_WEEK, ENABLED, SORT_ORDER)
          |VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".stripMargin
      )
      ps.setString(1, s.owner)
      ps.setString(2, s.repository)
      ps.setString(3, s.advanceNoticeDays)
      ps.setInt(4, s.dailyNotifyWithinDays)
      ps.setBoolean(5, s.notifyOverdue)
      ps.setBoolean(6, s.notifyNoDeadline)
      ps.setBoolean(7, s.notifyNotStarted)
      ps.setBoolean(8, s.notifyToCreator)
      ps.setBoolean(9, s.notifyToAssignee)
      ps.setInt(10, s.sendHour)
      ps.setInt(11, s.sendMinute)
      ps.setString(12, s.daysOfWeek)
      ps.setBoolean(13, s.enabled)
      ps.setString(14, s.sortOrder)
      ps.executeUpdate(); ps.close()
    }
  }

  def delete(conn: Connection, owner: String, repository: String): Unit = {
    val ps = conn.prepareStatement(
      "DELETE FROM REPORTER_DEADLINE_NOTIFY_SETTING WHERE OWNER = ? AND REPOSITORY = ?"
    )
    ps.setString(1, owner); ps.setString(2, repository)
    ps.executeUpdate(); ps.close()
  }

  def findAlreadySentKeys(
    conn:       Connection,
    owner:      String,
    repository: String,
    date:       LocalDate
  ): Set[(Int, String)] = {
    val ps = conn.prepareStatement(
      """SELECT ISSUE_ID, NOTIFY_TYPE FROM REPORTER_DEADLINE_NOTIFY_LOG
        |WHERE OWNER=? AND REPOSITORY=? AND SENT_DATE=?""".stripMargin
    )
    ps.setString(1, owner)
    ps.setString(2, repository)
    ps.setDate(3, Date.valueOf(date))
    val rs  = ps.executeQuery()
    val buf = mutable.Buffer.empty[(Int, String)]
    while (rs.next()) buf += ((rs.getInt("ISSUE_ID"), rs.getString("NOTIFY_TYPE")))
    rs.close(); ps.close()
    buf.toSet
  }

  def isAlreadySent(
    conn:       Connection,
    owner:      String,
    repository: String,
    issueId:    Int,
    notifyType: String,
    date:       LocalDate
  ): Boolean = {
    val ps = conn.prepareStatement(
      """SELECT COUNT(*) FROM REPORTER_DEADLINE_NOTIFY_LOG
        |WHERE OWNER=? AND REPOSITORY=? AND ISSUE_ID=? AND NOTIFY_TYPE=? AND SENT_DATE=?""".stripMargin
    )
    ps.setString(1, owner)
    ps.setString(2, repository)
    ps.setInt(3, issueId)
    ps.setString(4, notifyType)
    ps.setDate(5, Date.valueOf(date))
    val rs = ps.executeQuery()
    val result = rs.next() && rs.getInt(1) > 0
    rs.close(); ps.close()
    result
  }

  def recordSent(
    conn:       Connection,
    owner:      String,
    repository: String,
    issueId:    Int,
    notifyType: String,
    date:       LocalDate
  ): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO REPORTER_DEADLINE_NOTIFY_LOG (OWNER, REPOSITORY, ISSUE_ID, NOTIFY_TYPE, SENT_DATE)
        |VALUES (?,?,?,?,?)""".stripMargin
    )
    ps.setString(1, owner)
    ps.setString(2, repository)
    ps.setInt(3, issueId)
    ps.setString(4, notifyType)
    ps.setDate(5, Date.valueOf(date))
    ps.executeUpdate(); ps.close()
  }

  def cleanOldLogs(conn: Connection): Unit = {
    val cutoff = LocalDate.now().minusDays(90)
    val ps = conn.prepareStatement(
      "DELETE FROM REPORTER_DEADLINE_NOTIFY_LOG WHERE SENT_DATE < ?"
    )
    ps.setDate(1, Date.valueOf(cutoff))
    ps.executeUpdate(); ps.close()
  }

  private def fromRow(rs: java.sql.ResultSet): DeadlineNotifySetting =
    DeadlineNotifySetting(
      id                    = rs.getInt("ID"),
      owner                 = rs.getString("OWNER"),
      repository            = rs.getString("REPOSITORY"),
      advanceNoticeDays     = Option(rs.getString("ADVANCE_NOTICE_DAYS")).getOrElse(""),
      dailyNotifyWithinDays = rs.getInt("DAILY_NOTIFY_WITHIN_DAYS"),
      notifyOverdue         = rs.getBoolean("NOTIFY_OVERDUE"),
      notifyNoDeadline      = rs.getBoolean("NOTIFY_NO_DEADLINE"),
      notifyToCreator       = rs.getBoolean("NOTIFY_TO_CREATOR"),
      notifyToAssignee      = rs.getBoolean("NOTIFY_TO_ASSIGNEE"),
      sendHour              = rs.getInt("SEND_HOUR"),
      sendMinute            = rs.getInt("SEND_MINUTE"),
      daysOfWeek            = rs.getString("DAYS_OF_WEEK"),
      enabled               = rs.getBoolean("ENABLED"),
      sortOrder             = Option(rs.getString("SORT_ORDER")).filter(_.nonEmpty).getOrElse("NO_DEADLINE_FIRST"),
      notifyNotStarted      = rs.getBoolean("NOTIFY_NOT_STARTED")
    )
}
