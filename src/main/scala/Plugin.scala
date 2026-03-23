import gitbucket.core.plugin.PluginRegistry
import gitbucket.core.service.SystemSettingsService.SystemSettings
import io.github.gitbucket.solidbase.model.Version
import io.github.takahino.reporter.controller.{BurnupController, DeadlineNotifyController, GanttController, IssueNoteController, IssuePeriodController, IssueReportController, IssueTableController, MailScheduleController}
import io.github.takahino.reporter.scheduler.MailScheduler
import io.github.takahino.reporter.service.{DeadlineNotifyRepository, IssueNoteRepository, IssuePeriodRepository, IssueTableSettingsRepository, MailScheduleRepository}
import org.slf4j.LoggerFactory

import javax.servlet.ServletContext

class Plugin extends gitbucket.core.plugin.Plugin {
  private val logger = LoggerFactory.getLogger(classOf[Plugin])
  override val pluginId    = "issue-reporter"
  override val pluginName  = "Issue Reporter"
  override val description = "Export issues to Excel, send scheduled reports, and notify deadlines"

  override val versions = List(new Version("0.1.0"))

  override def controllers(
    registry: PluginRegistry,
    context:  ServletContext,
    settings: SystemSettings
  ) = Seq(
    "/*" -> new IssueReportController(),
    "/*" -> new IssueTableController(),
    "/*" -> new MailScheduleController(),
    "/*" -> new DeadlineNotifyController(),
    "/*" -> new IssueNoteController(),
    "/*" -> new IssuePeriodController(),
    "/*" -> new BurnupController(),
    "/*" -> new GanttController()
  )

  override def initialize(
    registry: PluginRegistry,
    context:  ServletContext,
    settings: SystemSettings
  ): Unit = {
    // 親クラスの initialize を必ず呼ぶ（controllers・javaScripts の登録処理が入っている）
    super.initialize(registry, context, settings)

    // スケジューラ起動（エラーが発生してもコントローラ/JSの登録を妨げないよう try-catch で保護）
    try {
      val slickDb = gitbucket.core.servlet.Database()
      val ds = new javax.sql.DataSource {
        def getConnection()                     = slickDb.source.createConnection()
        def getConnection(u: String, p: String) = slickDb.source.createConnection()
        def getLogWriter                         = null
        def setLogWriter(pw: java.io.PrintWriter): Unit = ()
        def setLoginTimeout(s: Int): Unit        = ()
        def getLoginTimeout                      = 0
        def unwrap[T](i: Class[T])               = throw new java.sql.SQLException("unwrap not supported")
        def isWrapperFor(i: Class[_])            = false
        def getParentLogger                      = java.util.logging.Logger.getLogger("issue-reporter-ds")
      }
      MailScheduleRepository.createTablesIfNotExists(ds)
      MailScheduleRepository.alterTablesIfNeeded(ds)
      DeadlineNotifyRepository.createTablesIfNotExists(ds)
      DeadlineNotifyRepository.alterTablesIfNeeded(ds)
      IssueNoteRepository.createTablesIfNotExists(ds)
      IssuePeriodRepository.createTablesIfNotExists(ds)
      IssuePeriodRepository.alterTablesIfNeeded(ds)
      IssueTableSettingsRepository.createTablesIfNotExists(ds)
      MailScheduler.start(ds)
    } catch {
      case e: Exception =>
        logger.error("[IssueReporterPlugin] スケジューラ初期化失敗（コントローラ/JSは引き続き有効）", e)
    }
  }

  override def shutdown(
    registry: PluginRegistry,
    context:  ServletContext,
    settings: SystemSettings
  ): Unit = {
    MailScheduler.stop()
  }

  override def assetsMappings(
    registry: PluginRegistry,
    context:  ServletContext,
    settings: SystemSettings
  ) = Seq("/plugin-assets/issue-reporter" -> "/assets/issue-reporter")

  override def javaScripts(
    registry: PluginRegistry,
    context:  ServletContext,
    settings: SystemSettings
  ) = Seq(".*/.+/.+/issues.*" -> """
(function () {
  function insertBtn(btn, refEl) {
    refEl.parentNode.insertBefore(btn, refEl);
  }

  function addFixedBtn(btn, topPx) {
    var s = 'position:fixed;top:' + topPx + 'px;right:16px;z-index:9999;background:#fff;border:1px solid #ccc;padding:4px 10px;border-radius:4px;text-decoration:none;color:#333;font-size:12px;box-shadow:0 1px 3px rgba(0,0,0,.2)';
    btn.style.cssText = s;
    document.body.appendChild(btn);
  }

  function makeBtn(href, icon, label) {
    var btn = document.createElement('a');
    btn.href = href;
    btn.className = 'btn btn-sm btn-default';
    btn.innerHTML = '<i class="octicon octicon-' + icon + '"></i> ' + label;
    btn.style.cssText = 'margin-left:6px;vertical-align:middle;';
    return btn;
  }

  // Issue一覧ページのボタン注入
  function initIssueList() {
    var m = location.pathname.match(/^(.*\/([^\/]+)\/([^\/]+))\/issues\/?$/);
    if (!m) return;
    var base = m[1], owner = m[2], repo = m[3];

    // btn0: Issue一覧GUIページ（新しいタブで開く）
    var btn0 = makeBtn(base + '/issues/issue-table',
      'list-unordered', 'Issue\u4E00\u89A7');
    btn0.target = '_blank';

    // btn1: Issue一覧をExcel形式でダウンロード
    var btn1 = makeBtn(base + '/issues/export-excel',
      'cloud-download', 'Excel\u30C0\u30A6\u30F3\u30ED\u30FC\u30C9');

    var targets = [
      'form.pull-right.form-inline',
      '.pull-right > a.btn-success',
      '.nav.nav-pills'
    ];

    function findTarget() {
      for (var i = 0; i < targets.length; i++) {
        var el = document.querySelector(targets[i]);
        if (el) return el;
      }
      return null;
    }

    // btn4: Burnup チャート（新しいタブで開く）
    var btn4 = makeBtn(base + '/issues/burnup',
      'pulse', 'Burnup');
    btn4.target = '_blank';

    // btn5: Gantt チャート（新しいタブで開く）
    var btn5 = makeBtn(base + '/issues/gantt',
      'graph', 'Gantt');
    btn5.target = '_blank';

    var refEl = findTarget();
    if (refEl) {
      insertBtn(btn0, refEl);
      insertBtn(btn1, refEl);
      insertBtn(btn4, refEl);
      insertBtn(btn5, refEl);
    } else {
      addFixedBtn(btn0, 20);
      addFixedBtn(btn1, 60);
      addFixedBtn(btn4, 180);
      addFixedBtn(btn5, 220);
    }

    // 書き込み権限チェック — Manager以上のみ btn2・btn3 を表示
    var xhrPerm = new XMLHttpRequest();
    xhrPerm.open('GET', base + '/issues/reporter-writable-check', true);
    xhrPerm.onload = function() {
      if (xhrPerm.status !== 200) return;
      try {
        var perm = JSON.parse(xhrPerm.responseText);
        if (!perm.writable) return;
      } catch(e) { return; }

      // btn2: 定期Excel送信設定
      var btn2 = makeBtn(base + '/issues/mail-schedule',
        'mail', '\u5B9A\u671FExcel\u9001\u4FE1');
      var ref2 = findTarget();
      if (ref2) {
        insertBtn(btn2, ref2);
      } else {
        addFixedBtn(btn2, 100);
      }

      // btn3: 期日通知設定（Gantt Plugin 未インストール時はマイルストーン期日にフォールバック）
      var btn3 = makeBtn(base + '/issues/deadline-notify',
        'clock', '\u671F\u65E5\u901A\u77E5\u8A2D\u5B9A');
      var ref3 = findTarget();
      if (ref3) {
        insertBtn(btn3, ref3);
      } else {
        addFixedBtn(btn3, 140);
      }
    };
    xhrPerm.send();
  }

  // Issue個別ページの備考UI注入
  function initIssueDetail() {
    var m = location.pathname.match(/^(.*\/([^\/]+)\/([^\/]+))\/issues\/(\d+)$/);
    if (!m) return;
    var base = m[1], owner = m[2], repo = m[3], issueId = m[4];

    // 備考フォームを作成
    var noteDiv = document.createElement('div');
    noteDiv.id = 'ir-note-panel';
    noteDiv.style.cssText = 'margin:16px 0;padding:12px;border:1px solid #e1e4e8;border-radius:6px;background:#fafafa;';
    noteDiv.innerHTML =
      // 確認待ちチェックボックス
      '<div style="margin-bottom:8px;padding:8px;border:1px solid #ddd;border-radius:4px;background:#f5f5f5;">' +
      '<label style="display:flex;align-items:center;gap:8px;cursor:pointer;font-size:13px;margin:0;">' +
      '<input type="checkbox" id="ir-waiting-check" style="width:16px;height:16px;cursor:pointer;">' +
      '<span style="font-weight:bold;">\u78ba\u8a8d\u5f85\u3061</span>' +
      '<span style="color:#888;font-size:12px;">\uff08\u4f5c\u696d\u8005\u5b8c\u4e86\u30fb\u95a2\u4fc2\u8005\u306e\u78ba\u8a8d\u5f85\u3061\uff09</span>' +
      '</label>' +
      '<textarea id="ir-confirmation-detail" rows="2" maxlength="1000" style="margin-top:6px;width:100%;box-sizing:border-box;border:1px solid #ccc;border-radius:3px;padding:6px;font-size:12px;" placeholder="\u78ba\u8a8d\u5f85\u3061\u306e\u8a73\u7d30\uff08\u78ba\u8a8d\u8005\u30fb\u671f\u9650\u306a\u3069\uff09..."></textarea>' +
      // 備考欄
      '<div style="margin-top:8px;margin-bottom:4px;font-size:12px;color:#555;">\u5099\u8003</div>' +
      '<textarea id="ir-note-text" rows="3" maxlength="4000" style="width:100%;box-sizing:border-box;border:1px solid #ccc;border-radius:3px;padding:6px;font-size:13px;" placeholder="\u5099\u8003\u3092\u5165\u529b..."></textarea>' +
      '</div>' +
      '<div style="margin-top:6px;">' +
      '<button id="ir-note-save" class="btn btn-sm btn-primary" style="font-size:12px;">\u4fdd\u5b58</button>' +
      '<span id="ir-note-status" style="margin-left:8px;font-size:12px;color:#666;"></span>' +
      '</div>';

    // Issue本文パネル（最初の .issue-comment-box）の直後に挿入
    var issueBox = document.querySelector('.issue-comment-box');
    if (issueBox) {
      issueBox.parentNode.insertBefore(noteDiv, issueBox.nextSibling);
    } else {
      document.body.appendChild(noteDiv);
    }

    // 現在の備考・確認待ち情報をロード
    var xhr = new XMLHttpRequest();
    xhr.open('GET', base + '/issues/' + issueId + '/note', true);
    xhr.onload = function() {
      if (xhr.status === 200) {
        try {
          var data = JSON.parse(xhr.responseText);
          document.getElementById('ir-note-text').value = data.note || '';
          document.getElementById('ir-waiting-check').checked = !!data.waitingForConfirmation;
          document.getElementById('ir-confirmation-detail').value = data.confirmationDetail || '';
        } catch(e) {}
      }
    };
    xhr.send();

    // 保存ボタン
    document.getElementById('ir-note-save').onclick = function() {
      var note               = document.getElementById('ir-note-text').value;
      var waiting            = document.getElementById('ir-waiting-check').checked;
      var confirmationDetail = document.getElementById('ir-confirmation-detail').value;
      var statusEl           = document.getElementById('ir-note-status');
      statusEl.textContent = '\u4fdd\u5b58\u4e2d...';
      statusEl.style.color = '#666';
      var xhr2 = new XMLHttpRequest();
      xhr2.open('POST', base + '/issues/' + issueId + '/note', true);
      xhr2.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr2.onload = function() {
        if (xhr2.status === 200) {
          statusEl.textContent = '\u4fdd\u5b58\u3057\u307e\u3057\u305f';
          statusEl.style.color = '#2da44e';
        } else {
          statusEl.textContent = '\u4fdd\u5b58\u306b\u5931\u6557\u3057\u307e\u3057\u305f';
          statusEl.style.color = '#d73a49';
        }
        setTimeout(function() { statusEl.textContent = ''; }, 3000);
      };
      xhr2.onerror = function() {
        statusEl.textContent = '\u901a\u4fe1\u30a8\u30e9\u30fc';
        statusEl.style.color = '#d73a49';
      };
      xhr2.send(
        'note=' + encodeURIComponent(note) +
        '&waitingForConfirmation=' + (waiting ? 'true' : 'false') +
        '&confirmationDetail=' + encodeURIComponent(confirmationDetail)
      );
    };

    // 期間・進捗パネルを作成
    var periodDiv = document.createElement('div');
    periodDiv.id = 'ir-period-panel';
    periodDiv.style.cssText = 'margin:16px 0;padding:12px;border:1px solid #e1e4e8;border-radius:6px;background:#fafafa;';
    periodDiv.innerHTML =
      '<div style="margin-bottom:8px;font-size:12px;color:#555;font-weight:bold;">\u671f\u9593\u30fb\u9032\u6357</div>' +
      '<div style="display:flex;flex-wrap:wrap;gap:12px;align-items:center;">' +
      '<div><label style="font-size:12px;color:#555;">\u958b\u59cb\u4e88\u5b9a\u65e5</label><br>' +
      '<input type="date" id="ir-start-date" style="border:1px solid #ccc;border-radius:3px;padding:4px;font-size:12px;"></div>' +
      '<div><label style="font-size:12px;color:#555;">\u5b8c\u4e86\u4e88\u5b9a\u65e5</label><br>' +
      '<input type="date" id="ir-end-date" style="border:1px solid #ccc;border-radius:3px;padding:4px;font-size:12px;"></div>' +
      '<div><label style="font-size:12px;color:#555;">\u9032\u6357(%)</label><br>' +
      '<input type="number" id="ir-progress" min="0" max="100" style="width:70px;border:1px solid #ccc;border-radius:3px;padding:4px;font-size:12px;"></div>' +
      '<div style="font-size:11px;color:#555;line-height:1.6;border-left:3px solid #ddd;padding-left:8px;">' +
      '<div>0%\uff1a\u672a\u7740\u624b</div>' +
      '<div>1\uff5e66%\uff1a\u9032\u884c\u4e2d</div>' +
      '<div>67\uff5e99%\uff1a\u3082\u3046\u3059\u3050\u5b8c\u4e86</div>' +
      '<div>100%\uff1a\u5b8c\u4e86</div>' +
      '</div>' +
      '<div><label style="font-size:12px;color:#555;">\u898b\u7a4d\u5de5\u6570(h)</label><br>' +
      '<input type="number" id="ir-estimated-hours" min="0" step="0.5" style="width:80px;border:1px solid #ccc;border-radius:3px;padding:4px;font-size:12px;"></div>' +
      '<div><label style="font-size:12px;color:#555;">\u5b9f\u7e3e\u5de5\u6570(h)</label><br>' +
      '<input type="number" id="ir-actual-hours" min="0" step="0.5" style="width:80px;border:1px solid #ccc;border-radius:3px;padding:4px;font-size:12px;"></div>' +
      '</div>' +
      '<div style="margin-top:8px;">' +
      '<button id="ir-period-save" class="btn btn-sm btn-primary" style="font-size:12px;">\u4fdd\u5b58</button>' +
      '<span id="ir-period-status" style="margin-left:8px;font-size:12px;color:#666;"></span>' +
      '</div>';

    // 備考パネルの後に挿入
    var notePanel = document.getElementById('ir-note-panel');
    if (notePanel) {
      notePanel.parentNode.insertBefore(periodDiv, notePanel.nextSibling);
    } else {
      var issueBox2 = document.querySelector('.issue-comment-box');
      if (issueBox2) {
        issueBox2.parentNode.insertBefore(periodDiv, issueBox2.nextSibling);
      } else {
        document.body.appendChild(periodDiv);
      }
    }

    // 期間データをロード
    var xhrP = new XMLHttpRequest();
    xhrP.open('GET', base + '/issues/' + issueId + '/reporter-period', true);
    xhrP.onload = function() {
      if (xhrP.status === 200) {
        try {
          var pd = JSON.parse(xhrP.responseText);
          if (pd.startDate) document.getElementById('ir-start-date').value = pd.startDate;
          if (pd.endDate)   document.getElementById('ir-end-date').value   = pd.endDate;
          if (pd.progress != null) document.getElementById('ir-progress').value = pd.progress;
          if (pd.estimatedHours != null) document.getElementById('ir-estimated-hours').value = pd.estimatedHours;
          if (pd.actualHours    != null) document.getElementById('ir-actual-hours').value    = pd.actualHours;
        } catch(e) {}
      }
    };
    xhrP.send();

    // 期間保存ボタン
    document.getElementById('ir-period-save').onclick = function() {
      var startDate      = document.getElementById('ir-start-date').value;
      var endDate        = document.getElementById('ir-end-date').value;
      var progress       = document.getElementById('ir-progress').value;
      var estimatedHours = document.getElementById('ir-estimated-hours').value;
      var actualHours    = document.getElementById('ir-actual-hours').value;
      var statusEl2 = document.getElementById('ir-period-status');
      statusEl2.textContent = '\u4fdd\u5b58\u4e2d...';
      statusEl2.style.color = '#666';
      var xhrP2 = new XMLHttpRequest();
      xhrP2.open('POST', base + '/issues/' + issueId + '/reporter-period', true);
      xhrP2.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhrP2.onload = function() {
        if (xhrP2.status === 200) {
          statusEl2.textContent = '\u4fdd\u5b58\u3057\u307e\u3057\u305f';
          statusEl2.style.color = '#2da44e';
        } else {
          statusEl2.textContent = '\u4fdd\u5b58\u306b\u5931\u6557\u3057\u307e\u3057\u305f';
          statusEl2.style.color = '#d73a49';
        }
        setTimeout(function() { statusEl2.textContent = ''; }, 3000);
      };
      xhrP2.onerror = function() {
        statusEl2.textContent = '\u901a\u4fe1\u30a8\u30e9\u30fc';
        statusEl2.style.color = '#d73a49';
      };
      xhrP2.send(
        'startDate=' + encodeURIComponent(startDate) +
        '&endDate=' + encodeURIComponent(endDate) +
        '&progress=' + encodeURIComponent(progress) +
        '&estimatedHours=' + encodeURIComponent(estimatedHours) +
        '&actualHours=' + encodeURIComponent(actualHours)
      );
    };
  }

  function init() {
    initIssueList();
    initIssueDetail();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
""")
}
