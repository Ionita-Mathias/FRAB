/*
 * TV File Server web interface.
 *
 * Vanilla ES2017, no dependencies and no build step: the whole thing is served straight out
 * of the APK's assets, and a phone on a TV's network often cannot reach a CDN.
 *
 * Security note: every value that comes from the server is inserted with textContent or
 * through esc(). No user-controlled string is ever passed to innerHTML.
 */
'use strict';

(function () {

  var CSRF = 'TvFileServer';
  var MAX_PARALLEL_UPLOADS = 3;
  var PREVIEW_TEXT_LIMIT = 512 * 1024;

  var state = {
    path: '/',
    entries: [],
    roots: [],
    selection: new Set(),
    lastClickedIndex: -1,
    sortKey: 'name',
    sortAsc: true,
    clipboard: null,
    info: null,
    readOnly: false,
    writable: false,
  };

  var uploads = { queue: [], active: 0, items: new Map(), nextId: 1 };
  var eventSource = null;
  var pollTimer = null;

  var el = {};

  // ───────────────────────────────────────────── bootstrap

  document.addEventListener('DOMContentLoaded', function () {
    cacheElements();
    wireEvents();
    bootstrap();
  });

  function cacheElements() {
    var ids = [
      'login-view', 'login-form', 'login-username', 'login-password', 'login-error',
      'login-submit', 'login-device', 'app-view', 'server-name', 'server-sub',
      'badge-readonly', 'badge-space', 'btn-logout', 'rootbar', 'btn-upload',
      'btn-upload-folder', 'btn-mkdir', 'selection-actions', 'selection-count',
      'btn-download', 'btn-rename', 'btn-cut', 'btn-copy', 'btn-delete', 'btn-paste',
      'btn-refresh', 'breadcrumb', 'check-all', 'filelist', 'empty-message', 'loading',
      'upload-list', 'upload-empty', 'upload-total', 'upload-total-bar', 'upload-total-text',
      'activity-list', 'activity-empty', 'drop-overlay', 'drop-path', 'modal', 'modal-title',
      'modal-message', 'modal-input', 'modal-cancel', 'modal-confirm', 'preview',
      'preview-name', 'preview-body', 'preview-close', 'preview-download', 'toasts',
      'file-input', 'folder-input',
    ];
    ids.forEach(function (id) { el[camel(id)] = document.getElementById(id); });
  }

  function camel(id) {
    return id.replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); });
  }

  function bootstrap() {
    api('GET', '/api/session').then(function (session) {
      if (session.authenticated) {
        showApp();
      } else {
        showLogin();
      }
    }).catch(function () {
      showLogin();
    });
  }

  function showLogin(message) {
    el.appView.hidden = true;
    el.loginView.hidden = false;
    stopActivityFeed();
    if (message) {
      el.loginError.textContent = message;
      el.loginError.hidden = false;
    } else {
      el.loginError.hidden = true;
    }
    el.loginPassword.focus();
  }

  function showApp() {
    el.loginView.hidden = true;
    el.appView.hidden = false;
    loadInfo();
    loadRoots().then(function () { navigate(state.path); });
    startActivityFeed();
  }

  // ───────────────────────────────────────────── networking

  function api(method, url, options) {
    options = options || {};
    var init = {
      method: method,
      credentials: 'same-origin',
      headers: Object.assign({ 'X-Requested-With': CSRF }, options.headers || {}),
    };
    if (options.body !== undefined) init.body = options.body;

    return fetch(url, init).then(function (response) {
      if (response.status === 401) {
        showLogin('Your session expired. Sign in again.');
        throw new Error('unauthorized');
      }
      var isJson = (response.headers.get('Content-Type') || '').indexOf('application/json') >= 0;
      return (isJson ? response.json() : response.text()).then(function (payload) {
        if (!response.ok) {
          var message = (payload && payload.error) || ('Request failed (' + response.status + ')');
          throw new Error(message);
        }
        return payload;
      });
    });
  }

  function form(fields) {
    var body = new URLSearchParams();
    Object.keys(fields).forEach(function (key) { body.append(key, fields[key]); });
    return {
      body: body.toString(),
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
    };
  }

  // ───────────────────────────────────────────── events

  function wireEvents() {
    el.loginForm.addEventListener('submit', onLogin);
    el.btnLogout.addEventListener('click', onLogout);

    el.btnRefresh.addEventListener('click', function () { navigate(state.path); });
    el.btnUpload.addEventListener('click', function () { el.fileInput.click(); });
    el.btnUploadFolder.addEventListener('click', function () { el.folderInput.click(); });
    el.fileInput.addEventListener('change', function () {
      queueFileList(el.fileInput.files);
      el.fileInput.value = '';
    });
    el.folderInput.addEventListener('change', function () {
      queueFileList(el.folderInput.files);
      el.folderInput.value = '';
    });

    el.btnMkdir.addEventListener('click', onMkdir);
    el.btnDelete.addEventListener('click', onDelete);
    el.btnRename.addEventListener('click', onRename);
    el.btnDownload.addEventListener('click', onDownload);
    el.btnCut.addEventListener('click', function () { setClipboard('move'); });
    el.btnCopy.addEventListener('click', function () { setClipboard('copy'); });
    el.btnPaste.addEventListener('click', onPaste);

    el.checkAll.addEventListener('change', function () {
      state.selection.clear();
      if (el.checkAll.checked) {
        state.entries.forEach(function (entry) { state.selection.add(entry.path); });
      }
      renderList();
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sortable'), function (header) {
      header.addEventListener('click', function () {
        var key = header.getAttribute('data-sort');
        if (state.sortKey === key) {
          state.sortAsc = !state.sortAsc;
        } else {
          state.sortKey = key;
          state.sortAsc = true;
        }
        renderList();
      });
    });

    el.previewClose.addEventListener('click', closePreview);
    el.modalCancel.addEventListener('click', closeModal);

    document.addEventListener('keydown', onKeyDown);
    wireDragAndDrop();
  }

  function onLogin(event) {
    event.preventDefault();
    el.loginSubmit.disabled = true;
    var payload = form({
      username: el.loginUsername.value,
      password: el.loginPassword.value,
    });
    fetch('/api/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: Object.assign({ 'X-Requested-With': CSRF }, payload.headers),
      body: payload.body,
    }).then(function (response) {
      return response.json().then(function (data) { return { ok: response.ok, data: data }; });
    }).then(function (result) {
      if (result.ok) {
        el.loginPassword.value = '';
        showApp();
      } else {
        showLogin(result.data.error || 'Sign-in failed');
      }
    }).catch(function () {
      showLogin('Cannot reach the server');
    }).then(function () {
      el.loginSubmit.disabled = false;
    });
  }

  function onLogout() {
    api('POST', '/api/logout').catch(function () { /* signing out locally is enough */ })
      .then(function () { showLogin(); });
  }

  function onKeyDown(event) {
    if (!el.modal.hidden) {
      if (event.key === 'Escape') closeModal();
      if (event.key === 'Enter' && !el.modalInput.hidden) el.modalConfirm.click();
      return;
    }
    if (!el.preview.hidden) {
      if (event.key === 'Escape') closePreview();
      return;
    }
    if (el.appView.hidden) return;
    var typing = document.activeElement && /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName);
    if (typing) return;

    if (event.key === 'Delete') { onDelete(); }
    else if (event.key === 'F2') { onRename(); }
    else if (event.key === 'Escape') { state.selection.clear(); renderList(); }
    else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a') {
      event.preventDefault();
      state.entries.forEach(function (entry) { state.selection.add(entry.path); });
      renderList();
    }
  }

  // ───────────────────────────────────────────── data loading

  function loadInfo() {
    api('GET', '/api/info').then(function (info) {
      state.info = info;
      state.readOnly = !!info.readOnly;
      el.serverName.textContent = info.serverName || 'TV File Server';
      var parts = [];
      if (info.deviceName) parts.push(info.deviceName);
      if (info.webdavEnabled) parts.push('WebDAV ' + info.webdavMount);
      if (info.ftpEnabled && info.ftpPort > 0) parts.push('FTP ' + info.ftpPort);
      el.serverSub.textContent = parts.join(' · ');
      el.badgeReadonly.hidden = !state.readOnly;
      el.loginDevice.textContent = info.deviceName
        ? 'Sign in to browse ' + info.deviceName
        : 'Sign in to browse this device';
    }).catch(function (error) { toast(error.message, 'error'); });
  }

  function loadRoots() {
    return api('GET', '/api/roots').then(function (roots) {
      state.roots = roots;
      renderRoots();
      if (state.path === '/' && roots.length > 0) state.path = roots[0].path;
    }).catch(function (error) {
      toast(error.message, 'error');
    });
  }

  function navigate(path) {
    el.loading.hidden = false;
    api('GET', '/api/list?path=' + encodeURIComponent(path)).then(function (listing) {
      state.path = listing.path;
      state.entries = listing.entries || [];
      state.writable = !!listing.writable;
      state.selection.clear();
      state.lastClickedIndex = -1;
      renderBreadcrumb();
      renderList();
      renderSpace(listing);
      renderRoots();
    }).catch(function (error) {
      toast(error.message, 'error');
    }).then(function () {
      el.loading.hidden = true;
    });
  }

  // ───────────────────────────────────────────── rendering

  function renderRoots() {
    el.rootbar.textContent = '';
    state.roots.forEach(function (root) {
      var chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'root-chip' + (state.path.indexOf(root.path) === 0 ? ' active' : '');
      chip.textContent = root.name + (root.writable ? '' : ' (read-only)');
      chip.addEventListener('click', function () { navigate(root.path); });
      el.rootbar.appendChild(chip);
    });
  }

  function renderSpace(listing) {
    if (listing.total > 0) {
      el.badgeSpace.textContent = formatBytes(listing.free) + ' free of ' + formatBytes(listing.total);
      el.badgeSpace.hidden = false;
    } else {
      el.badgeSpace.hidden = true;
    }
  }

  function renderBreadcrumb() {
    el.breadcrumb.textContent = '';
    var segments = state.path.split('/').filter(Boolean);
    appendCrumb('All storage', '/', segments.length === 0);
    var accumulated = '';
    segments.forEach(function (segment, index) {
      accumulated += '/' + segment;
      el.breadcrumb.appendChild(separator());
      appendCrumb(segment, accumulated, index === segments.length - 1);
    });
  }

  function appendCrumb(label, path, current) {
    var node = document.createElement('span');
    node.className = current ? 'crumb-current' : 'crumb';
    node.textContent = label;
    if (!current) node.addEventListener('click', function () { navigate(path); });
    el.breadcrumb.appendChild(node);
  }

  function separator() {
    var sep = document.createElement('span');
    sep.className = 'crumb-sep';
    sep.textContent = '/';
    return sep;
  }

  function renderList() {
    var entries = state.entries.slice().sort(compareEntries);
    el.filelist.textContent = '';

    entries.forEach(function (entry, index) {
      var row = document.createElement('tr');
      row.className = entry.dir ? 'row-dir' : 'row-file';
      if (state.selection.has(entry.path)) row.classList.add('selected');

      var checkCell = document.createElement('td');
      var check = document.createElement('input');
      check.type = 'checkbox';
      check.checked = state.selection.has(entry.path);
      check.setAttribute('aria-label', 'Select ' + entry.name);
      check.addEventListener('click', function (event) {
        onSelectionClick(event, entries, index, entry);
      });
      checkCell.appendChild(check);
      row.appendChild(checkCell);

      var nameCell = document.createElement('td');
      var wrap = document.createElement('div');
      wrap.className = 'name-cell';
      var icon = document.createElement('span');
      icon.className = 'name-icon';
      icon.textContent = iconFor(entry);
      var label = document.createElement('span');
      label.className = 'name-text';
      label.textContent = entry.name;
      label.addEventListener('click', function () { open(entry); });
      wrap.appendChild(icon);
      wrap.appendChild(label);
      nameCell.appendChild(wrap);
      row.appendChild(nameCell);

      var sizeCell = document.createElement('td');
      sizeCell.className = 'cell-size';
      sizeCell.textContent = entry.dir ? '—' : formatBytes(entry.size);
      row.appendChild(sizeCell);

      var dateCell = document.createElement('td');
      dateCell.className = 'cell-date';
      dateCell.textContent = formatDate(entry.modified);
      row.appendChild(dateCell);

      el.filelist.appendChild(row);
    });

    el.emptyMessage.hidden = entries.length > 0;
    el.checkAll.checked = entries.length > 0 && state.selection.size === entries.length;
    renderSelection();
  }

  function onSelectionClick(event, entries, index, entry) {
    if (event.shiftKey && state.lastClickedIndex >= 0) {
      var from = Math.min(state.lastClickedIndex, index);
      var to = Math.max(state.lastClickedIndex, index);
      for (var i = from; i <= to; i++) state.selection.add(entries[i].path);
    } else if (state.selection.has(entry.path)) {
      state.selection.delete(entry.path);
    } else {
      state.selection.add(entry.path);
    }
    state.lastClickedIndex = index;
    renderList();
  }

  function renderSelection() {
    var count = state.selection.size;
    el.selectionActions.hidden = count === 0;
    el.selectionCount.textContent = count === 1 ? '1 item selected' : count + ' items selected';

    var canWrite = state.writable && !state.readOnly;
    el.btnDelete.disabled = !canWrite;
    el.btnRename.disabled = !canWrite || count !== 1;
    el.btnCut.disabled = !canWrite;
    el.btnMkdir.disabled = !canWrite;
    el.btnUpload.disabled = !canWrite;
    el.btnUploadFolder.disabled = !canWrite;
    el.btnPaste.hidden = !state.clipboard;
    el.btnPaste.disabled = !canWrite;
  }

  function compareEntries(a, b) {
    if (a.dir !== b.dir) return a.dir ? -1 : 1;
    var result;
    if (state.sortKey === 'size') result = a.size - b.size;
    else if (state.sortKey === 'modified') result = a.modified - b.modified;
    else result = a.name.localeCompare(b.name, undefined, { sensitivity: 'base', numeric: true });
    return state.sortAsc ? result : -result;
  }

  function iconFor(entry) {
    if (entry.dir) return '📁';
    var mime = entry.mime || '';
    if (mime.indexOf('image/') === 0) return '🖼';
    if (mime.indexOf('video/') === 0) return '🎬';
    if (mime.indexOf('audio/') === 0) return '🎵';
    if (mime === 'application/pdf') return '📕';
    if (mime.indexOf('zip') >= 0 || mime.indexOf('compressed') >= 0 || mime.indexOf('tar') >= 0) return '🗜';
    if (mime.indexOf('text/') === 0 || mime.indexOf('json') >= 0 || mime.indexOf('xml') >= 0) return '📄';
    return '📦';
  }

  // ───────────────────────────────────────────── actions

  function open(entry) {
    if (entry.dir) {
      navigate(entry.path);
      return;
    }
    var mime = entry.mime || '';
    if (mime.indexOf('image/') === 0 || mime.indexOf('video/') === 0 ||
        mime.indexOf('audio/') === 0 || isTextual(mime)) {
      openPreview(entry);
    } else {
      downloadPaths([entry.path]);
    }
  }

  function onMkdir() {
    prompt('New folder', 'Name of the folder to create in ' + state.path, '', function (name) {
      if (!name) return;
      api('POST', '/api/mkdir', form({ path: state.path, name: name }))
        .then(function () { toast('Folder created', 'success'); navigate(state.path); })
        .catch(function (error) { toast(error.message, 'error'); });
    });
  }

  function onRename() {
    if (state.selection.size !== 1) return;
    var path = Array.from(state.selection)[0];
    var current = path.split('/').pop();
    prompt('Rename', 'New name for ' + current, current, function (name) {
      if (!name || name === current) return;
      api('POST', '/api/rename', form({ path: path, name: name }))
        .then(function () { toast('Renamed', 'success'); navigate(state.path); })
        .catch(function (error) { toast(error.message, 'error'); });
    });
  }

  function onDelete() {
    if (state.selection.size === 0) return;
    var paths = Array.from(state.selection);
    var label = paths.length === 1 ? paths[0].split('/').pop() : paths.length + ' items';
    confirm('Delete', 'Permanently delete ' + label + '? This cannot be undone.', function () {
      api('POST', '/api/delete', form({ paths: JSON.stringify(paths) }))
        .then(function (result) {
          if (result.failed && result.failed.length) {
            toast(result.failed[0].error, 'error');
          } else {
            toast('Deleted', 'success');
          }
          navigate(state.path);
        })
        .catch(function (error) { toast(error.message, 'error'); });
    });
  }

  function onDownload() {
    downloadPaths(Array.from(state.selection));
  }

  function downloadPaths(paths) {
    if (paths.length === 0) return;
    if (paths.length === 1) {
      var entry = state.entries.filter(function (item) { return item.path === paths[0]; })[0];
      if (entry && !entry.dir) {
        window.location.href = '/api/download?path=' + encodeURIComponent(paths[0]);
        return;
      }
    }
    var url = '/api/zip?path=' + encodeURIComponent(state.path) +
      '&paths=' + encodeURIComponent(JSON.stringify(paths));
    window.location.href = url;
  }

  function setClipboard(mode) {
    if (state.selection.size === 0) return;
    state.clipboard = { mode: mode, paths: Array.from(state.selection) };
    toast(state.clipboard.paths.length + ' item(s) ready to paste', 'info');
    renderSelection();
  }

  function onPaste() {
    if (!state.clipboard) return;
    var endpoint = state.clipboard.mode === 'move' ? '/api/move' : '/api/copy';
    api('POST', endpoint, form({
      paths: JSON.stringify(state.clipboard.paths),
      destination: state.path,
    })).then(function (result) {
      if (result.failed && result.failed.length) {
        toast(result.failed[0].error, 'error');
      } else {
        toast(state.clipboard.mode === 'move' ? 'Moved' : 'Copied', 'success');
      }
      state.clipboard = null;
      navigate(state.path);
    }).catch(function (error) { toast(error.message, 'error'); });
  }

  // ───────────────────────────────────────────── uploads

  function wireDragAndDrop() {
    var depth = 0;
    window.addEventListener('dragenter', function (event) {
      if (!hasFiles(event)) return;
      event.preventDefault();
      depth++;
      el.dropPath.textContent = state.path;
      el.dropOverlay.hidden = false;
    });
    window.addEventListener('dragover', function (event) {
      if (!hasFiles(event)) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = 'copy';
    });
    window.addEventListener('dragleave', function () {
      depth = Math.max(0, depth - 1);
      if (depth === 0) el.dropOverlay.hidden = true;
    });
    window.addEventListener('drop', function (event) {
      if (!hasFiles(event)) return;
      event.preventDefault();
      depth = 0;
      el.dropOverlay.hidden = true;
      if (!state.writable || state.readOnly) {
        toast('This location is read-only', 'error');
        return;
      }
      collectDropped(event.dataTransfer).then(queueFiles);
    });
  }

  function hasFiles(event) {
    var transfer = event.dataTransfer;
    if (!transfer) return false;
    var types = transfer.types || [];
    return Array.prototype.indexOf.call(types, 'Files') >= 0;
  }

  /**
   * Walks a drop, descending into directories when the browser exposes the entry API.
   * Chrome, Edge and Safari all support webkitGetAsEntry; Firefox falls back to flat files.
   */
  function collectDropped(transfer) {
    var items = transfer.items;
    if (!items || !items.length || typeof items[0].webkitGetAsEntry !== 'function') {
      return Promise.resolve(toArray(transfer.files).map(function (file) {
        return { file: file, relativePath: file.name };
      }));
    }
    var walks = [];
    for (var i = 0; i < items.length; i++) {
      var entry = items[i].webkitGetAsEntry();
      if (entry) walks.push(walkEntry(entry, ''));
    }
    return Promise.all(walks).then(function (groups) {
      return groups.reduce(function (all, group) { return all.concat(group); }, []);
    });
  }

  function walkEntry(entry, prefix) {
    if (entry.isFile) {
      return new Promise(function (resolve) {
        entry.file(function (file) {
          resolve([{ file: file, relativePath: prefix + entry.name }]);
        }, function () { resolve([]); });
      });
    }
    if (!entry.isDirectory) return Promise.resolve([]);

    var reader = entry.createReader();
    var collected = [];
    return new Promise(function (resolve) {
      // readEntries returns at most 100 children per call, so it must be drained in a loop.
      function readBatch() {
        reader.readEntries(function (children) {
          if (!children.length) {
            Promise.all(collected).then(function (groups) {
              resolve(groups.reduce(function (all, group) { return all.concat(group); }, []));
            });
            return;
          }
          for (var i = 0; i < children.length; i++) {
            collected.push(walkEntry(children[i], prefix + entry.name + '/'));
          }
          readBatch();
        }, function () { resolve([]); });
      }
      readBatch();
    });
  }

  function queueFileList(fileList) {
    queueFiles(toArray(fileList).map(function (file) {
      return { file: file, relativePath: file.webkitRelativePath || file.name };
    }));
  }

  function queueFiles(items) {
    if (!items.length) return;
    if (!state.writable || state.readOnly) {
      toast('This location is read-only', 'error');
      return;
    }
    var destination = state.path;
    items.forEach(function (item) {
      uploads.queue.push({
        id: uploads.nextId++,
        file: item.file,
        relativePath: item.relativePath,
        destination: destination,
        transferred: 0,
        status: 'pending',
      });
    });
    renderUploads();
    pumpUploads();
  }

  function pumpUploads() {
    while (uploads.active < MAX_PARALLEL_UPLOADS && uploads.queue.length > 0) {
      startUpload(uploads.queue.shift());
    }
    if (uploads.active === 0 && uploads.queue.length === 0) {
      navigate(state.path);
    }
  }

  function startUpload(job) {
    uploads.active++;
    job.status = 'uploading';
    job.startedAt = Date.now();
    uploads.items.set(job.id, job);
    renderUploads();

    // XMLHttpRequest rather than fetch: it is still the only way to get real upload
    // progress events in every browser this has to run on.
    var xhr = new XMLHttpRequest();
    job.xhr = xhr;
    var body = new FormData();
    body.append('file', job.file, job.relativePath);

    xhr.open('POST', '/api/upload?path=' + encodeURIComponent(job.destination));
    xhr.withCredentials = true;
    xhr.setRequestHeader('X-Requested-With', CSRF);
    xhr.setRequestHeader('X-File-Size', String(job.file.size));

    xhr.upload.addEventListener('progress', function (event) {
      job.transferred = event.loaded;
      renderUploads();
    });
    xhr.addEventListener('load', function () {
      if (xhr.status === 401) {
        job.status = 'failed';
        job.error = 'Session expired';
        showLogin('Your session expired. Sign in again.');
      } else if (xhr.status >= 200 && xhr.status < 300) {
        job.status = 'done';
        job.transferred = job.file.size;
      } else {
        job.status = 'failed';
        job.error = errorFrom(xhr);
      }
      finishUpload(job);
    });
    xhr.addEventListener('error', function () {
      job.status = 'failed';
      job.error = 'Network error';
      finishUpload(job);
    });
    xhr.addEventListener('abort', function () {
      job.status = 'cancelled';
      finishUpload(job);
    });
    xhr.send(body);
  }

  function errorFrom(xhr) {
    try {
      var parsed = JSON.parse(xhr.responseText);
      if (parsed && parsed.error) return parsed.error;
    } catch (ignored) { /* not JSON */ }
    return 'Upload failed (' + xhr.status + ')';
  }

  function finishUpload(job) {
    uploads.active--;
    if (job.status === 'failed') toast(job.relativePath + ': ' + job.error, 'error');
    renderUploads();
    pumpUploads();
  }

  function renderUploads() {
    var jobs = Array.from(uploads.items.values()).concat(uploads.queue);
    el.uploadEmpty.hidden = jobs.length > 0;
    el.uploadList.textContent = '';

    var totalBytes = 0;
    var doneBytes = 0;
    var running = 0;

    jobs.slice(-40).forEach(function (job) {
      totalBytes += job.file.size;
      doneBytes += job.transferred;
      if (job.status === 'uploading' || job.status === 'pending') running++;

      var item = document.createElement('div');
      item.className = 'upload-item' + (job.status === 'failed' ? ' failed' : '') +
        (job.status === 'done' ? ' done' : '');

      var head = document.createElement('div');
      head.className = 'upload-head';
      var name = document.createElement('span');
      name.className = 'upload-name';
      name.textContent = job.relativePath;
      var meta = document.createElement('span');
      meta.className = 'upload-meta';
      meta.textContent = uploadMeta(job);
      head.appendChild(name);
      head.appendChild(meta);

      if (job.status === 'uploading') {
        var cancel = document.createElement('button');
        cancel.className = 'btn-cancel';
        cancel.type = 'button';
        cancel.textContent = '✕';
        cancel.title = 'Cancel';
        cancel.addEventListener('click', function () { if (job.xhr) job.xhr.abort(); });
        head.appendChild(cancel);
      }

      var progress = document.createElement('div');
      progress.className = 'progress';
      var bar = document.createElement('div');
      bar.className = 'progress-bar';
      bar.style.width = job.file.size > 0
        ? Math.min(100, (job.transferred / job.file.size) * 100) + '%'
        : '100%';
      progress.appendChild(bar);

      item.appendChild(head);
      if (job.status === 'uploading' || job.status === 'pending') item.appendChild(progress);
      el.uploadList.appendChild(item);
    });

    if (running > 0 && totalBytes > 0) {
      el.uploadTotal.hidden = false;
      el.uploadTotalBar.style.width = Math.min(100, (doneBytes / totalBytes) * 100) + '%';
      el.uploadTotalText.textContent =
        formatBytes(doneBytes) + ' of ' + formatBytes(totalBytes) + ' · ' + running + ' remaining';
    } else {
      el.uploadTotal.hidden = true;
    }
  }

  function uploadMeta(job) {
    if (job.status === 'done') return 'done';
    if (job.status === 'failed') return job.error || 'failed';
    if (job.status === 'cancelled') return 'cancelled';
    if (job.status === 'pending') return 'queued';
    var elapsed = (Date.now() - job.startedAt) / 1000;
    var rate = elapsed > 0 ? job.transferred / elapsed : 0;
    var remaining = rate > 0 ? (job.file.size - job.transferred) / rate : -1;
    return formatBytes(rate) + '/s · ' + (remaining >= 0 ? formatDuration(remaining) : '--');
  }

  // ───────────────────────────────────────────── activity feed

  function startActivityFeed() {
    stopActivityFeed();
    if (typeof EventSource === 'function') {
      try {
        eventSource = new EventSource('/api/events', { withCredentials: true });
        eventSource.onmessage = function (event) {
          try { renderActivity(JSON.parse(event.data)); } catch (ignored) { /* keep the stream */ }
        };
        eventSource.onerror = function () {
          // Fall back to polling rather than hammering a reconnect the server may refuse.
          stopActivityFeed();
          startPolling();
        };
        return;
      } catch (ignored) { /* fall through to polling */ }
    }
    startPolling();
  }

  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(function () {
      api('GET', '/api/transfers').then(renderActivity).catch(function () { /* transient */ });
    }, 2000);
  }

  function stopActivityFeed() {
    if (eventSource) { eventSource.close(); eventSource = null; }
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function renderActivity(payload) {
    var active = (payload && payload.active) || [];
    el.activityEmpty.hidden = active.length > 0;
    el.activityList.textContent = '';

    active.forEach(function (transfer) {
      var item = document.createElement('div');
      item.className = 'activity-item';

      var head = document.createElement('div');
      head.className = 'upload-head';
      var name = document.createElement('span');
      name.className = 'upload-name';
      name.textContent = (transfer.direction === 'UPLOAD' ? '↑ ' : '↓ ') + transfer.name;
      var meta = document.createElement('span');
      meta.className = 'upload-meta';
      meta.textContent = transfer.protocol + ' · ' + formatBytes(transfer.bps) + '/s';
      head.appendChild(name);
      head.appendChild(meta);

      var progress = document.createElement('div');
      progress.className = 'progress';
      var bar = document.createElement('div');
      bar.className = 'progress-bar';
      if (transfer.total > 0) {
        bar.style.width = Math.min(100, (transfer.transferred / transfer.total) * 100) + '%';
      } else {
        bar.className = 'progress-bar indeterminate';
      }
      progress.appendChild(bar);

      item.appendChild(head);
      item.appendChild(progress);
      el.activityList.appendChild(item);
    });
  }

  // ───────────────────────────────────────────── preview

  function openPreview(entry) {
    var url = '/api/raw?path=' + encodeURIComponent(entry.path);
    el.previewName.textContent = entry.name;
    el.previewDownload.href = '/api/download?path=' + encodeURIComponent(entry.path);
    el.previewBody.textContent = '';

    var mime = entry.mime || '';
    if (mime.indexOf('image/') === 0) {
      var image = document.createElement('img');
      image.src = url;
      image.alt = entry.name;
      el.previewBody.appendChild(image);
    } else if (mime.indexOf('video/') === 0) {
      var video = document.createElement('video');
      video.src = url;
      video.controls = true;
      video.autoplay = true;
      el.previewBody.appendChild(video);
    } else if (mime.indexOf('audio/') === 0) {
      var audio = document.createElement('audio');
      audio.src = url;
      audio.controls = true;
      audio.autoplay = true;
      el.previewBody.appendChild(audio);
    } else {
      var pre = document.createElement('pre');
      pre.textContent = 'Loading…';
      el.previewBody.appendChild(pre);
      fetch(url, { credentials: 'same-origin', headers: { 'Range': 'bytes=0-' + PREVIEW_TEXT_LIMIT } })
        .then(function (response) { return response.text(); })
        .then(function (text) { pre.textContent = text; })
        .catch(function () { pre.textContent = 'Cannot preview this file.'; });
    }
    el.preview.hidden = false;
  }

  function closePreview() {
    // Clearing the body stops any media element still streaming from the server.
    el.previewBody.textContent = '';
    el.preview.hidden = true;
  }

  // ───────────────────────────────────────────── modal and toasts

  function prompt(title, message, initial, onConfirm) {
    openModal(title, message, true, initial, onConfirm);
  }

  function confirm(title, message, onConfirm) {
    openModal(title, message, false, '', onConfirm);
  }

  function openModal(title, message, withInput, initial, onConfirm) {
    el.modalTitle.textContent = title;
    el.modalMessage.textContent = message;
    el.modalInput.hidden = !withInput;
    el.modalInput.value = initial || '';
    el.modal.hidden = false;

    var confirmHandler = function () {
      var value = withInput ? el.modalInput.value.trim() : null;
      closeModal();
      onConfirm(value);
    };
    el.modalConfirm.onclick = confirmHandler;
    if (withInput) {
      el.modalInput.focus();
      el.modalInput.select();
    } else {
      el.modalConfirm.focus();
    }
  }

  function closeModal() {
    el.modal.hidden = true;
    el.modalConfirm.onclick = null;
  }

  function toast(message, kind) {
    var node = document.createElement('div');
    node.className = 'toast ' + (kind || 'info');
    node.textContent = message;
    el.toasts.appendChild(node);
    setTimeout(function () {
      if (node.parentNode) node.parentNode.removeChild(node);
    }, kind === 'error' ? 6000 : 3200);
  }

  // ───────────────────────────────────────────── helpers

  function toArray(list) { return Array.prototype.slice.call(list || []); }

  function isTextual(mime) {
    return mime.indexOf('text/') === 0 ||
      mime.indexOf('json') >= 0 ||
      mime.indexOf('xml') >= 0 ||
      mime.indexOf('x-subrip') >= 0;
  }

  function formatBytes(bytes) {
    if (bytes === null || bytes === undefined || bytes < 0) return '—';
    if (bytes < 1024) return bytes + ' B';
    var units = ['KB', 'MB', 'GB', 'TB', 'PB'];
    var value = bytes / 1024;
    var index = 0;
    while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
    return value.toFixed(1) + ' ' + units[index];
  }

  function formatDuration(seconds) {
    seconds = Math.max(0, Math.round(seconds));
    var hours = Math.floor(seconds / 3600);
    var minutes = Math.floor((seconds % 3600) / 60);
    var rest = seconds % 60;
    var pad = function (n) { return n < 10 ? '0' + n : String(n); };
    return hours > 0 ? hours + ':' + pad(minutes) + ':' + pad(rest) : pad(minutes) + ':' + pad(rest);
  }

  function formatDate(millis) {
    if (!millis) return '—';
    var date = new Date(millis);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

})();
