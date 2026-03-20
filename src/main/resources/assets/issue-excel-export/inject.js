(function () {
  'use strict';

  // issue 一覧ページ以外では何もしない
  var m = location.pathname.match(/^\/([^/]+)\/([^/]+)\/issues\/?$/);
  if (!m) return;

  var owner = m[1];
  var repo  = m[2];
  var href  = '/' + owner + '/' + repo + '/issues/export-excel';

  var btn = document.createElement('a');
  btn.href      = href;
  btn.className = 'btn btn-sm btn-default';
  btn.title     = 'Excelエクスポート';
  btn.innerHTML = '<i class="octicon octicon-cloud-download"></i> Excelエクスポート';
  btn.style.cssText = 'margin-left:6px; vertical-align:middle;';

  // GitBucket 4.x の issue ページに存在するセレクタを優先順で試行
  var selectors = [
    '.issues-list-options',       // 古い GitBucket
    '.issues-actions',
    '.issue-form-options',
    '.btn-group.filter-group',
    '.subnav',
    '.tabnav',
    'form .float-right',
    '.d-flex.flex-justify-between', // 新しめのテンプレート
    '.repository-title-actions',
    '.clearfix .right',
    '.col-md-3.text-right',
  ];

  function tryAppend() {
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) {
        el.appendChild(btn);
        console.log('[excel-export] ボタンを追加: ' + selectors[i]);
        return true;
      }
    }
    return false;
  }

  // DOM が描画されてから実行（DOMContentLoaded 後に呼ばれることが多いが念のため）
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      if (!tryAppend()) fallbackAppend();
    });
  } else {
    if (!tryAppend()) fallbackAppend();
  }

  // どのセレクタにも一致しない場合: ページ右上に固定ボタンを配置
  function fallbackAppend() {
    console.warn('[excel-export] ツールバーが見つかりません。固定ボタンを使用します。');
    btn.style.cssText = [
      'position:fixed',
      'top:60px',
      'right:16px',
      'z-index:9999',
      'background:#fff',
      'border:1px solid #ccc',
      'padding:4px 10px',
      'border-radius:4px',
      'text-decoration:none',
      'color:#333',
      'font-size:12px',
      'box-shadow:0 1px 3px rgba(0,0,0,.2)'
    ].join(';');
    document.body.appendChild(btn);
  }
})();
