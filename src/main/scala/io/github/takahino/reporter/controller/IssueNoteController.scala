package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.ReadableUsersAuthenticator
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.service.{IssueNote, IssueNoteRepository}

class IssueNoteController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator {

  /** GET /:owner/:repository/issues/:issueId/note — 備考・確認待ち情報を JSON で返す */
  get("/:owner/:repository/issues/:issueId/note")(readableUsersOnly { repo =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn    = session.conn
    val issueId = params("issueId").toIntOption.getOrElse(0)
    val n       = IssueNoteRepository.findNote(conn, repo.owner, repo.name, issueId)
    s"""{"note":"${HtmlUtil.escJson(n.note)}","waitingForConfirmation":${n.waitingForConfirmation},"confirmationDetail":"${HtmlUtil.escJson(n.confirmationDetail)}"}"""
  })

  /** POST /:owner/:repository/issues/:issueId/note — 備考・確認待ち情報を保存する */
  post("/:owner/:repository/issues/:issueId/note")(readableUsersOnly { repo =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn    = session.conn
    val issueId = params("issueId").toIntOption.getOrElse(0)
    val note    = IssueNote(
      note                   = params.getOrElse("note", ""),
      waitingForConfirmation = params.getOrElse("waitingForConfirmation", "false") == "true",
      confirmationDetail     = params.getOrElse("confirmationDetail", "")
    )
    IssueNoteRepository.upsertNote(conn, repo.owner, repo.name, issueId, note)
    """{"ok":true}"""
  })
}
