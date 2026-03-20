package io.github.takahino.reporter.service

import java.sql.Connection
import javax.sql.DataSource
import scala.collection.mutable

case class IssuePeriod(
  issueId:   Int,
  startDate: Option[String],
  endDate:   Option[String],
  progress:  Option[Int]
)

object IssuePeriodRepository {

  def createTablesIfNotExists(ds: DataSource): Unit = {
    val conn = ds.getConnection()
    try createTablesIfNotExists(conn)
    finally conn.close()
  }

  def createTablesIfNotExists(conn: Connection): Unit = {
    conn.createStatement().execute(
      """CREATE TABLE IF NOT EXISTS REPORTER_ISSUE_PERIOD (
        |  OWNER           VARCHAR(100) NOT NULL,
        |  REPOSITORY_NAME VARCHAR(100) NOT NULL,
        |  ISSUE_ID        INTEGER      NOT NULL,
        |  START_DATE      VARCHAR(10),
        |  END_DATE        VARCHAR(10),
        |  PROGRESS        INTEGER,
        |  PRIMARY KEY (OWNER, REPOSITORY_NAME, ISSUE_ID)
        |)""".stripMargin
    )
  }

  def findAllPeriods(conn: Connection, owner: String, repo: String): Map[Int, IssuePeriod] = {
    val ps = conn.prepareStatement(
      "SELECT ISSUE_ID, START_DATE, END_DATE, PROGRESS FROM REPORTER_ISSUE_PERIOD WHERE OWNER = ? AND REPOSITORY_NAME = ?"
    )
    ps.setString(1, owner)
    ps.setString(2, repo)
    val rs  = ps.executeQuery()
    val buf = mutable.Map.empty[Int, IssuePeriod]
    while (rs.next()) {
      val id = rs.getInt("ISSUE_ID")
      buf(id) = IssuePeriod(
        issueId   = id,
        startDate = Option(rs.getString("START_DATE")),
        endDate   = Option(rs.getString("END_DATE")),
        progress  = { val v = rs.getInt("PROGRESS"); if (rs.wasNull()) None else Some(v) }
      )
    }
    rs.close(); ps.close()
    buf.toMap
  }

  def findPeriod(conn: Connection, owner: String, repo: String, issueId: Int): IssuePeriod = {
    val ps = conn.prepareStatement(
      "SELECT ISSUE_ID, START_DATE, END_DATE, PROGRESS FROM REPORTER_ISSUE_PERIOD WHERE OWNER = ? AND REPOSITORY_NAME = ? AND ISSUE_ID = ?"
    )
    ps.setString(1, owner)
    ps.setString(2, repo)
    ps.setInt(3, issueId)
    val rs     = ps.executeQuery()
    val result = if (rs.next()) IssuePeriod(
      issueId   = issueId,
      startDate = Option(rs.getString("START_DATE")),
      endDate   = Option(rs.getString("END_DATE")),
      progress  = { val v = rs.getInt("PROGRESS"); if (rs.wasNull()) None else Some(v) }
    ) else IssuePeriod(issueId, None, None, None)
    rs.close(); ps.close()
    result
  }

  def upsertPeriod(conn: Connection, owner: String, repo: String, issueId: Int, period: IssuePeriod): Unit = {
    val ps = conn.prepareStatement(
      """MERGE INTO REPORTER_ISSUE_PERIOD (OWNER, REPOSITORY_NAME, ISSUE_ID, START_DATE, END_DATE, PROGRESS)
        |KEY (OWNER, REPOSITORY_NAME, ISSUE_ID)
        |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin
    )
    ps.setString(1, owner)
    ps.setString(2, repo)
    ps.setInt(3, issueId)
    period.startDate match {
      case Some(v) => ps.setString(4, v)
      case None    => ps.setNull(4, java.sql.Types.VARCHAR)
    }
    period.endDate match {
      case Some(v) => ps.setString(5, v)
      case None    => ps.setNull(5, java.sql.Types.VARCHAR)
    }
    period.progress match {
      case Some(v) => ps.setInt(6, v)
      case None    => ps.setNull(6, java.sql.Types.INTEGER)
    }
    ps.executeUpdate()
    ps.close()
  }
}
