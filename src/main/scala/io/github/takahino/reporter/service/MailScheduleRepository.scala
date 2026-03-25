package io.github.takahino.reporter.service

import io.github.takahino.reporter.model.MailSchedule

import java.sql.{Connection, Timestamp}
import java.time.LocalDateTime
import javax.sql.DataSource
import scala.collection.mutable

object MailScheduleRepository {

  def createTablesIfNotExists(ds: DataSource): Unit = {
    val conn = ds.getConnection()
    try createTablesIfNotExists(conn)
    finally conn.close()
  }

  def createTablesIfNotExists(conn: Connection): Unit = {
    // HOUR/MINUTE は H2 予約語のため SEND_HOUR/SEND_MINUTE を使用
    conn.createStatement().execute(
      """CREATE TABLE IF NOT EXISTS REPORTER_MAIL_SCHEDULE (
        |  ID           INTEGER       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        |  OWNER        VARCHAR(100)  NOT NULL,
        |  REPOSITORY   VARCHAR(100)  NOT NULL,
        |  RECIPIENTS   VARCHAR(2000) NOT NULL,
        |  SEND_HOUR    SMALLINT      NOT NULL,
        |  SEND_MINUTE  SMALLINT      NOT NULL,
        |  DAYS_OF_WEEK VARCHAR(20)   NOT NULL,
        |  ENABLED      BOOLEAN       NOT NULL DEFAULT TRUE,
        |  LAST_SENT_AT TIMESTAMP
        |)""".stripMargin
    )
  }

  def alterTablesIfNeeded(ds: DataSource): Unit = {
    val conn = ds.getConnection()
    try alterTablesIfNeeded(conn)
    finally conn.close()
  }

  def alterTablesIfNeeded(conn: Connection): Unit = {
    conn.createStatement().execute(
      "ALTER TABLE REPORTER_MAIL_SCHEDULE " +
      "ADD COLUMN IF NOT EXISTS COLUMN_ORDER VARCHAR(500) NOT NULL DEFAULT ''"
    )
  }

  def findByRepo(conn: Connection, owner: String, repository: String): Option[MailSchedule] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM REPORTER_MAIL_SCHEDULE WHERE OWNER = ? AND REPOSITORY = ? LIMIT 1"
    )
    ps.setString(1, owner)
    ps.setString(2, repository)
    val rs = ps.executeQuery()
    val result = if (rs.next()) Some(fromRow(rs)) else None
    rs.close(); ps.close()
    result
  }

  def findAll(conn: Connection): Seq[MailSchedule] = {
    val ps  = conn.prepareStatement("SELECT * FROM REPORTER_MAIL_SCHEDULE")
    val rs  = ps.executeQuery()
    val buf = mutable.Buffer.empty[MailSchedule]
    while (rs.next()) buf += fromRow(rs)
    rs.close(); ps.close()
    buf.toSeq
  }

  def findDueSchedules(conn: Connection, now: LocalDateTime): Seq[MailSchedule] =
    findAll(conn).filter(isDue(_, now))

  def upsert(conn: Connection, s: MailSchedule): Unit = {
    val existing = findByRepo(conn, s.owner, s.repository)
    if (existing.isDefined) {
      val ps = conn.prepareStatement(
        """UPDATE REPORTER_MAIL_SCHEDULE
          |SET RECIPIENTS=?, SEND_HOUR=?, SEND_MINUTE=?, DAYS_OF_WEEK=?, ENABLED=?, COLUMN_ORDER=?
          |WHERE OWNER=? AND REPOSITORY=?""".stripMargin
      )
      ps.setString(1, s.recipients)
      ps.setInt(2, s.hour)
      ps.setInt(3, s.minute)
      ps.setString(4, s.daysOfWeek)
      ps.setBoolean(5, s.enabled)
      ps.setString(6, s.columnOrder)
      ps.setString(7, s.owner)
      ps.setString(8, s.repository)
      ps.executeUpdate(); ps.close()
    } else {
      val ps = conn.prepareStatement(
        """INSERT INTO REPORTER_MAIL_SCHEDULE
          |(OWNER, REPOSITORY, RECIPIENTS, SEND_HOUR, SEND_MINUTE, DAYS_OF_WEEK, ENABLED, COLUMN_ORDER)
          |VALUES (?,?,?,?,?,?,?,?)""".stripMargin
      )
      ps.setString(1, s.owner)
      ps.setString(2, s.repository)
      ps.setString(3, s.recipients)
      ps.setInt(4, s.hour)
      ps.setInt(5, s.minute)
      ps.setString(6, s.daysOfWeek)
      ps.setBoolean(7, s.enabled)
      ps.setString(8, s.columnOrder)
      ps.executeUpdate(); ps.close()
    }
  }

  def delete(conn: Connection, owner: String, repository: String): Unit = {
    val ps = conn.prepareStatement(
      "DELETE FROM REPORTER_MAIL_SCHEDULE WHERE OWNER = ? AND REPOSITORY = ?"
    )
    ps.setString(1, owner); ps.setString(2, repository)
    ps.executeUpdate(); ps.close()
  }

  def updateLastSentAt(conn: Connection, id: Int, at: LocalDateTime): Unit = {
    val ps = conn.prepareStatement(
      "UPDATE REPORTER_MAIL_SCHEDULE SET LAST_SENT_AT = ? WHERE ID = ?"
    )
    ps.setTimestamp(1, Timestamp.valueOf(at))
    ps.setInt(2, id)
    ps.executeUpdate(); ps.close()
  }

  /** リポジトリのIssueを更新できるユーザーのみを返す。
   *  対象：システム管理者、リポジトリオーナー（個人）、コラボレーター、グループメンバー
   *  COLLABORATORテーブルが存在しない環境でも動作する。
   */
  def findWritableUsers(conn: Connection, owner: String, repository: String): Seq[(String, String)] = {
    val hasCollaborator = tableExists(conn, "COLLABORATOR")
    val collaboratorCondition =
      if (hasCollaborator)
        """  OR EXISTS (
          |    SELECT 1 FROM COLLABORATOR c
          |    WHERE c.USER_NAME = ? AND c.REPOSITORY_NAME = ?
          |    AND c.COLLABORATOR_NAME = a.USER_NAME
          |  )""".stripMargin
      else ""

    val sql =
      s"""SELECT DISTINCT a.USER_NAME, a.FULL_NAME FROM ACCOUNT a
         |WHERE a.GROUP_ACCOUNT = FALSE AND a.REMOVED = FALSE
         |AND (
         |  a.ADMINISTRATOR = TRUE
         |  OR a.USER_NAME = ?
         |$collaboratorCondition
         |  OR EXISTS (
         |    SELECT 1 FROM GROUP_MEMBER gm
         |    WHERE gm.GROUP_NAME = ?
         |    AND gm.USER_NAME = a.USER_NAME
         |  )
         |)
         |ORDER BY a.USER_NAME""".stripMargin

    val ps = conn.prepareStatement(sql)
    var idx = 1
    ps.setString(idx, owner); idx += 1
    if (hasCollaborator) {
      ps.setString(idx, owner);      idx += 1
      ps.setString(idx, repository); idx += 1
    }
    ps.setString(idx, owner)

    val rs  = ps.executeQuery()
    val buf = mutable.Buffer.empty[(String, String)]
    while (rs.next()) buf += ((rs.getString("USER_NAME"), rs.getString("FULL_NAME")))
    rs.close(); ps.close()
    buf.toSeq
  }

  private def tableExists(conn: Connection, tableName: String): Boolean = {
    val ps = conn.prepareStatement(
      "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(?)"
    )
    ps.setString(1, tableName)
    val rs     = ps.executeQuery()
    val exists = rs.next() && rs.getInt(1) > 0
    rs.close(); ps.close()
    exists
  }

  def findMailAddresses(conn: Connection, userNames: Seq[String]): Seq[String] = {
    if (userNames.isEmpty) return Seq.empty
    val placeholders = userNames.map(_ => "?").mkString(",")
    val ps = conn.prepareStatement(
      s"SELECT MAIL_ADDRESS FROM ACCOUNT WHERE USER_NAME IN ($placeholders) AND GROUP_ACCOUNT = FALSE AND REMOVED = FALSE"
    )
    userNames.zipWithIndex.foreach { case (name, i) => ps.setString(i + 1, name) }
    val rs  = ps.executeQuery()
    val buf = mutable.Buffer.empty[String]
    while (rs.next()) {
      val addr = rs.getString("MAIL_ADDRESS")
      if (addr != null && addr.trim.nonEmpty) buf += addr.trim
    }
    rs.close(); ps.close()
    buf.toSeq
  }

  private def isDue(s: MailSchedule, now: LocalDateTime): Boolean = {
    val dow = now.getDayOfWeek.getValue // 1=Mon, 7=Sun
    s.enabled &&
      now.getHour   == s.hour &&
      now.getMinute == s.minute &&
      s.daysOfWeek.split(",").map(_.trim).contains(dow.toString) &&
      s.lastSentAt.forall(t => java.time.Duration.between(t, now).toMinutes >= 59)
  }

  private def fromRow(rs: java.sql.ResultSet): MailSchedule = {
    val ts = rs.getTimestamp("LAST_SENT_AT")
    MailSchedule(
      id          = rs.getInt("ID"),
      owner       = rs.getString("OWNER"),
      repository  = rs.getString("REPOSITORY"),
      recipients  = rs.getString("RECIPIENTS"),
      hour        = rs.getInt("SEND_HOUR"),
      minute      = rs.getInt("SEND_MINUTE"),
      daysOfWeek  = rs.getString("DAYS_OF_WEEK"),
      enabled     = rs.getBoolean("ENABLED"),
      lastSentAt  = if (ts == null) None else Some(ts.toLocalDateTime),
      columnOrder = Option(rs.getString("COLUMN_ORDER")).getOrElse("")
    )
  }
}
