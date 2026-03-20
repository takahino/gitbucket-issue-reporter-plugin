package io.github.takahino.reporter.service

import java.sql.Connection
import javax.sql.DataSource
import scala.collection.mutable

case class IssueNote(
  note:                   String,
  waitingForConfirmation: Boolean,
  confirmationDetail:     String
)

object IssueNoteRepository {

  def createTablesIfNotExists(ds: DataSource): Unit = {
    val conn = ds.getConnection()
    try createTablesIfNotExists(conn)
    finally conn.close()
  }

  def createTablesIfNotExists(conn: Connection): Unit = {
    conn.createStatement().execute(
      """CREATE TABLE IF NOT EXISTS REPORTER_ISSUE_NOTE (
        |  OWNER                    VARCHAR(100)  NOT NULL,
        |  REPOSITORY_NAME          VARCHAR(100)  NOT NULL,
        |  ISSUE_ID                 INTEGER       NOT NULL,
        |  NOTE                     VARCHAR(4000) NOT NULL DEFAULT '',
        |  WAITING_FOR_CONFIRMATION BOOLEAN       NOT NULL DEFAULT FALSE,
        |  CONFIRMATION_DETAIL      VARCHAR(1000) NOT NULL DEFAULT '',
        |  PRIMARY KEY (OWNER, REPOSITORY_NAME, ISSUE_ID)
        |)""".stripMargin
    )
    // 既存テーブルへの列追加（インストール済み環境向け）
    val st = conn.createStatement()
    try st.execute("ALTER TABLE REPORTER_ISSUE_NOTE ADD COLUMN IF NOT EXISTS WAITING_FOR_CONFIRMATION BOOLEAN NOT NULL DEFAULT FALSE")
    catch { case _: Exception => }
    try st.execute("ALTER TABLE REPORTER_ISSUE_NOTE ADD COLUMN IF NOT EXISTS CONFIRMATION_DETAIL VARCHAR(1000) NOT NULL DEFAULT ''")
    catch { case _: Exception => }
    st.close()
  }

  def findNote(conn: Connection, owner: String, repo: String, issueId: Int): IssueNote = {
    val ps = conn.prepareStatement(
      "SELECT NOTE, WAITING_FOR_CONFIRMATION, CONFIRMATION_DETAIL FROM REPORTER_ISSUE_NOTE WHERE OWNER = ? AND REPOSITORY_NAME = ? AND ISSUE_ID = ?"
    )
    ps.setString(1, owner)
    ps.setString(2, repo)
    ps.setInt(3, issueId)
    val rs = ps.executeQuery()
    val result = if (rs.next()) IssueNote(
      note                   = Option(rs.getString("NOTE")).getOrElse(""),
      waitingForConfirmation = rs.getBoolean("WAITING_FOR_CONFIRMATION"),
      confirmationDetail     = Option(rs.getString("CONFIRMATION_DETAIL")).getOrElse("")
    ) else IssueNote("", false, "")
    rs.close(); ps.close()
    result
  }

  def findAllNotes(conn: Connection, owner: String, repo: String): Map[Int, IssueNote] = {
    val ps = conn.prepareStatement(
      "SELECT ISSUE_ID, NOTE, WAITING_FOR_CONFIRMATION, CONFIRMATION_DETAIL FROM REPORTER_ISSUE_NOTE WHERE OWNER = ? AND REPOSITORY_NAME = ?"
    )
    ps.setString(1, owner)
    ps.setString(2, repo)
    val rs  = ps.executeQuery()
    val buf = mutable.Map.empty[Int, IssueNote]
    while (rs.next()) {
      buf(rs.getInt("ISSUE_ID")) = IssueNote(
        note                   = Option(rs.getString("NOTE")).getOrElse(""),
        waitingForConfirmation = rs.getBoolean("WAITING_FOR_CONFIRMATION"),
        confirmationDetail     = Option(rs.getString("CONFIRMATION_DETAIL")).getOrElse("")
      )
    }
    rs.close(); ps.close()
    buf.toMap
  }

  def upsertNote(conn: Connection, owner: String, repo: String, issueId: Int, note: IssueNote): Unit = {
    val ps = conn.prepareStatement(
      """MERGE INTO REPORTER_ISSUE_NOTE (OWNER, REPOSITORY_NAME, ISSUE_ID, NOTE, WAITING_FOR_CONFIRMATION, CONFIRMATION_DETAIL)
        |KEY (OWNER, REPOSITORY_NAME, ISSUE_ID)
        |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin
    )
    ps.setString(1, owner)
    ps.setString(2, repo)
    ps.setInt(3, issueId)
    ps.setString(4, note.note.take(4000))
    ps.setBoolean(5, note.waitingForConfirmation)
    ps.setString(6, note.confirmationDetail.take(1000))
    ps.executeUpdate()
    ps.close()
  }
}
