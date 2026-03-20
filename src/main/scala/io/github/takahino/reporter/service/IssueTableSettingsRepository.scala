package io.github.takahino.reporter.service

import java.sql.Connection
import javax.sql.DataSource

case class IssueTableSettings(
  owner:          String,
  repository:     String,
  columnOrder:    String,
  highlightRules: String
)

object IssueTableSettingsRepository {

  def createTablesIfNotExists(ds: DataSource): Unit = {
    val conn = ds.getConnection()
    try createTablesIfNotExists(conn)
    finally conn.close()
  }

  def createTablesIfNotExists(conn: Connection): Unit = {
    conn.createStatement().execute(
      """CREATE TABLE IF NOT EXISTS REPORTER_TABLE_SETTINGS (
        |  OWNER            VARCHAR(100)  NOT NULL,
        |  REPOSITORY       VARCHAR(100)  NOT NULL,
        |  COLUMN_ORDER     VARCHAR(1000) NOT NULL DEFAULT '',
        |  HIGHLIGHT_RULES  VARCHAR(2000) NOT NULL DEFAULT '',
        |  PRIMARY KEY (OWNER, REPOSITORY)
        |)""".stripMargin
    )
  }

  def findByRepo(conn: Connection, owner: String, repository: String): Option[IssueTableSettings] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM REPORTER_TABLE_SETTINGS WHERE OWNER = ? AND REPOSITORY = ?"
    )
    ps.setString(1, owner)
    ps.setString(2, repository)
    val rs = ps.executeQuery()
    val result = if (rs.next()) Some(IssueTableSettings(
      owner          = rs.getString("OWNER"),
      repository     = rs.getString("REPOSITORY"),
      columnOrder    = Option(rs.getString("COLUMN_ORDER")).getOrElse(""),
      highlightRules = Option(rs.getString("HIGHLIGHT_RULES")).getOrElse("")
    )) else None
    rs.close(); ps.close()
    result
  }

  def upsert(conn: Connection, st: IssueTableSettings): Unit = {
    val existing = findByRepo(conn, st.owner, st.repository)
    if (existing.isDefined) {
      val ps = conn.prepareStatement(
        "UPDATE REPORTER_TABLE_SETTINGS SET COLUMN_ORDER=?, HIGHLIGHT_RULES=? WHERE OWNER=? AND REPOSITORY=?"
      )
      ps.setString(1, st.columnOrder)
      ps.setString(2, st.highlightRules)
      ps.setString(3, st.owner)
      ps.setString(4, st.repository)
      ps.executeUpdate(); ps.close()
    } else {
      val ps = conn.prepareStatement(
        "INSERT INTO REPORTER_TABLE_SETTINGS (OWNER, REPOSITORY, COLUMN_ORDER, HIGHLIGHT_RULES) VALUES (?,?,?,?)"
      )
      ps.setString(1, st.owner)
      ps.setString(2, st.repository)
      ps.setString(3, st.columnOrder)
      ps.setString(4, st.highlightRules)
      ps.executeUpdate(); ps.close()
    }
  }
}
