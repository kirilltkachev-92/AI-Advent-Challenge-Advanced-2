/**
 * Одностраничный веб-UI мотиватора — отдаётся с GET /. Полностью самодостаточный
 * HTML: инлайн CSS/JS, без внешних ресурсов и сборщиков. Общение с сервером —
 * fetch к тому же публичному API (POST /v1/motivate, GET /v1/history,
 * DELETE /v1/history).
 *
 * Контракт для автотестов UI — стабильные id элементов:
 * taskInput, motivateBtn, phraseBox, errorBox, historyList, clearHistoryBtn;
 * записи истории — li.history-item.
 */
object WebUi {
    val PAGE = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Мотиватор — день 1</title>
<style>
  :root { --bg:#11151c; --panel:#1a2029; --line:#2a3342; --text:#dfe6f0; --dim:#8b97a8;
          --accent:#5eb1ff; --err-bg:#4a2328; --err-line:#7c3a41; }
  * { box-sizing:border-box; margin:0; }
  body { background:var(--bg); color:var(--text);
         font:15px/1.5 -apple-system,'Segoe UI',Roboto,sans-serif;
         max-width:720px; margin:0 auto; padding:24px 16px; }
  h1 { font-size:20px; margin-bottom:4px; }
  .sub { color:var(--dim); font-size:13px; margin-bottom:20px; }
  form { display:flex; gap:8px; margin-bottom:12px; }
  #taskInput { flex:1; background:var(--panel); color:var(--text); border:1px solid var(--line);
               border-radius:8px; padding:10px 12px; font:inherit; }
  #motivateBtn { background:var(--accent); color:#06233f; border:0; border-radius:8px;
                 padding:0 20px; font:inherit; font-weight:600; cursor:pointer; }
  #motivateBtn:disabled { opacity:.5; cursor:wait; }
  #phraseBox { background:var(--panel); border:1px solid var(--line); border-radius:12px;
               padding:14px 16px; min-height:52px; white-space:pre-wrap; word-break:break-word;
               margin-bottom:8px; }
  #phraseBox:empty::before { content:'Здесь появится мотивационная фраза.'; color:var(--dim); }
  #errorBox { background:var(--err-bg); border:1px solid var(--err-line); border-radius:8px;
              padding:10px 14px; margin-bottom:8px; word-break:break-word; }
  #errorBox:empty { display:none; }
  h2 { font-size:15px; margin:0; color:var(--dim); font-weight:600; }
  .history-head { display:flex; align-items:center; justify-content:space-between;
                  margin:20px 0 8px; }
  #clearHistoryBtn { background:transparent; color:var(--dim); border:1px solid var(--line);
                     border-radius:8px; padding:4px 12px; font:inherit; font-size:13px;
                     cursor:pointer; }
  #clearHistoryBtn:hover { color:var(--text); border-color:var(--dim); }
  #clearHistoryBtn:disabled { opacity:.5; cursor:wait; }
  #historyList { list-style:none; display:flex; flex-direction:column; gap:8px; }
  .history-item { background:var(--panel); border:1px solid var(--line); border-radius:10px;
                  padding:10px 14px; }
  .history-item .task { font-weight:600; word-break:break-word; }
  .history-item .phrase { margin-top:4px; white-space:pre-wrap; word-break:break-word; }
  .history-item .time { margin-top:6px; font-size:12px; color:var(--dim); }
  .empty { color:var(--dim); }
</style>
</head>
<body>
<h1>Мотиватор</h1>
<div class="sub">Опишите задачу — DeepSeek подбодрит. Последние 10 обменов ниже.</div>
<form id="motivateForm" novalidate>
  <input id="taskInput" type="text" placeholder="Например: написать интеграционный тест" autocomplete="off" required>
  <button id="motivateBtn" type="submit">Мотивировать</button>
</form>
<div id="errorBox"></div>
<div id="phraseBox"></div>
<div class="history-head">
  <h2>История</h2>
  <button id="clearHistoryBtn" type="button">Очистить историю</button>
</div>
<ul id="historyList"><li class="empty">Загружаю историю…</li></ul>
<script>
(function () {
  var form = document.getElementById('motivateForm');
  var input = document.getElementById('taskInput');
  var btn = document.getElementById('motivateBtn');
  var phraseBox = document.getElementById('phraseBox');
  var errorBox = document.getElementById('errorBox');
  var historyList = document.getElementById('historyList');
  var clearBtn = document.getElementById('clearHistoryBtn');

  function showError(text) { errorBox.textContent = text; }
  function clearError() { errorBox.textContent = ''; }

  // Ошибки API (400/413/502/…) показываем текстом из error.message.
  function apiMessage(status, json) {
    if (json && json.error && json.error.message) return json.error.message;
    return 'HTTP ' + status;
  }

  function renderHistory(entries) {
    historyList.textContent = '';
    if (!entries || entries.length === 0) {
      var li = document.createElement('li');
      li.className = 'empty';
      li.textContent = 'История пуста — задайте первую задачу.';
      historyList.appendChild(li);
      return;
    }
    entries.forEach(function (e) {
      var li = document.createElement('li');
      li.className = 'history-item';
      var task = document.createElement('div');
      task.className = 'task';
      task.textContent = e.task;
      var phrase = document.createElement('div');
      phrase.className = 'phrase';
      phrase.textContent = e.phrase;
      var time = document.createElement('div');
      time.className = 'time';
      time.textContent = e.at;
      li.appendChild(task); li.appendChild(phrase); li.appendChild(time);
      historyList.appendChild(li);
    });
  }

  function loadHistory() {
    fetch('/v1/history')
      .then(function (r) { return r.json().then(function (j) { return { s: r.status, j: j }; }); })
      .then(function (res) {
        if (res.s !== 200) { showError(apiMessage(res.s, res.j)); return; }
        renderHistory(res.j.entries);
      })
      .catch(function (err) { showError('Сеть: ' + err); });
  }

  // Очистка истории — без confirm(): действие обратимо по смыслу (история и так
  // кольцевая в памяти), ошибки показываем в #errorBox как и остальные.
  clearBtn.addEventListener('click', function () {
    clearError();
    clearBtn.disabled = true;
    fetch('/v1/history', { method: 'DELETE' })
      .then(function (r) { return r.json().then(function (j) { return { s: r.status, j: j }; }); })
      .then(function (res) {
        if (res.s !== 200) { showError(apiMessage(res.s, res.j)); return; }
        loadHistory();
      })
      .catch(function (err) { showError('Сеть: ' + err); })
      .finally(function () { clearBtn.disabled = false; });
  });

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var task = input.value.trim();
    // Пустая/пробельная задача — та же человекочитаемая ошибка в #errorBox,
    // что и у API (без запроса на сервер). Форма с novalidate, чтобы нативный
    // тултип required не перехватывал пустой submit мимо errorBox.
    if (!task) {
      phraseBox.textContent = '';
      showError('Поле задачи пустое — опишите, что нужно сделать.');
      input.focus();
      return;
    }
    clearError();
    btn.disabled = true;
    phraseBox.textContent = '…думаю';

    fetch('/v1/motivate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task: task })
    }).then(function (r) { return r.json().then(function (j) { return { s: r.status, j: j }; }); })
      .then(function (res) {
        if (res.s !== 200) {
          phraseBox.textContent = '';
          showError(apiMessage(res.s, res.j));
          return;
        }
        phraseBox.textContent = res.j.phrase;
        input.value = '';
        loadHistory();
      })
      .catch(function (err) { phraseBox.textContent = ''; showError('Сеть: ' + err); })
      .finally(function () { btn.disabled = false; input.focus(); });
  });

  loadHistory();
})();
</script>
</body>
</html>
""".trimIndent()
}
