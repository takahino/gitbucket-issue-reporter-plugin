package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.ReadableUsersAuthenticator
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.service.{IssuePeriod, IssuePeriodRepository}

class IssuePeriodController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator {

  /** GET /:owner/:repository/issues/:issueId/reporter-period — 期間・進捗情報を JSON で返す */
  get("/:owner/:repository/issues/:issueId/reporter-period")(readableUsersOnly { repo =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn    = session.conn
    val issueId = params("issueId").toIntOption.getOrElse(0)
    val p       = IssuePeriodRepository.findPeriod(conn, repo.owner, repo.name, issueId)
    val progressStr = p.progress.map(_.toString).getOrElse("null")
    s"""{"startDate":"${HtmlUtil.escJson(p.startDate.getOrElse(""))}","endDate":"${HtmlUtil.escJson(p.endDate.getOrElse(""))}","progress":$progressStr}"""
  })

  /** POST /:owner/:repository/issues/:issueId/reporter-period — 期間・進捗情報を保存する */
  post("/:owner/:repository/issues/:issueId/reporter-period")(readableUsersOnly { repo =>
    contentType = "application/json; charset=UTF-8"
    implicit val session = request2Session(request)
    val conn    = session.conn
    val issueId = params("issueId").toIntOption.getOrElse(0)
    val period  = IssuePeriod(
      issueId   = issueId,
      startDate = params.get("startDate").filter(_.nonEmpty),
      endDate   = params.get("endDate").filter(_.nonEmpty),
      progress  = params.get("progress").filter(_.nonEmpty).flatMap(s => scala.util.Try(s.toInt).toOption)
    )
    IssuePeriodRepository.upsertPeriod(conn, repo.owner, repo.name, issueId, period)
    """{"ok":true}"""
  })
}
