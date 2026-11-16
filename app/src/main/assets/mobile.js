/*
 * WhazzApp — WhatsApp Web'i telefon düzenine çeviren enjeksiyon betiği.
 *
 * Fikir: WhatsApp'ın sınıf adları her sürümde değişir (obfuscated), ama #side (sohbet listesi)
 * ve #main (açık sohbet) id'leri uzun zamandır sabit. Bu yüzden:
 *   1. #side'dan yukarı çıkıp, yanında başka bir geniş sütun olan "satır" (row) elemanını buluruz.
 *   2. Satırın çocuklarını data-whazz="side|main|rail|drawer|other" ile etiketleriz.
 *   3. #main varsa <html>'e "whazz-chat" sınıfı ekleriz. Gerisini mobile.css halleder.
 */
(function () {
  if (window.top !== window || window.__whazzLoaded) return;
  window.__whazzLoaded = true;

  function html() { return document.documentElement; }
  var state = { row: null, sideCol: null, forceList: false };

  // ---------------------------------------------------------------- Bildirim köprüsü
  // Android WebView, Web Notification API'yi desteklemez. WhatsApp'ın "new Notification(...)"
  // çağrılarını yakalayıp yerel Android bildirimine çeviriyoruz. Her bildirime bir id verilir;
  // Android bildirimine dokunulunca __whazzNotifClick(id) WhatsApp'ın kendi onclick'ini çalıştırır
  // (ilgili sohbeti açar), WhatsApp close() derse Android bildirimi de kaldırılır.
  if (window.WhazzAndroid) {
    var notifSeq = 0, notifs = {}, notifOrder = [];
    var WhazzNotification = function (title, options) {
      options = options || {};
      this.title = String(title || '');
      this.body = options.body || '';
      this.tag = options.tag || '';
      this.data = options.data;
      this.icon = options.icon || '';
      this.onclick = null; this.onclose = null; this.onshow = null; this.onerror = null;
      this._listeners = {};
      this._id = ++notifSeq;
      notifs[this._id] = this;
      notifOrder.push(this._id);
      if (notifOrder.length > 200) delete notifs[notifOrder.shift()];
      try { window.WhazzAndroid.notify(this._id, this.title, String(this.body), String(this.tag)); } catch (e) {}
      var self = this;
      setTimeout(function () { self._fire('show'); }, 0);
    };
    WhazzNotification.permission = 'granted';
    WhazzNotification.maxActions = 0;
    WhazzNotification.requestPermission = function (cb) {
      if (typeof cb === 'function') cb('granted');
      return Promise.resolve('granted');
    };
    WhazzNotification.prototype.addEventListener = function (type, fn) {
      (this._listeners[type] = this._listeners[type] || []).push(fn);
    };
    WhazzNotification.prototype.removeEventListener = function (type, fn) {
      var l = this._listeners[type] || [];
      var i = l.indexOf(fn);
      if (i >= 0) l.splice(i, 1);
    };
    WhazzNotification.prototype._fire = function (type) {
      var self = this;
      var ev = { type: type, target: self, currentTarget: self, preventDefault: function () {} };
      if (typeof self['on' + type] === 'function') { try { self['on' + type].call(self, ev); } catch (e) {} }
      (self._listeners[type] || []).slice().forEach(function (fn) { try { fn.call(self, ev); } catch (e) {} });
    };
    WhazzNotification.prototype.close = function () {
      if (!notifs[this._id]) return;
      delete notifs[this._id];
      try { window.WhazzAndroid.closeNotification(this._id); } catch (e) {}
      this._fire('close');
    };
    try {
      Object.defineProperty(window, 'Notification', { value: WhazzNotification, configurable: true, writable: true });
    } catch (e) { window.Notification = WhazzNotification; }

    window.__whazzNotifClick = function (id) {
      var n = notifs[id];
      if (!n) return false;
      n._fire('click');
      return true;
    };

    // WhatsApp service worker üzerinden bildirim göstermeye çalışırsa da aynı köprüye düşsün.
    if (window.ServiceWorkerRegistration) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, options) {
        new WhazzNotification(title, options);
        return Promise.resolve();
      };
      ServiceWorkerRegistration.prototype.getNotifications = function () { return Promise.resolve([]); };
    }
    // İzin sorgusu da "granted" desin (WebView'da bu sorgu "denied" dönebilir).
    if (navigator.permissions && navigator.permissions.query) {
      var origQuery = navigator.permissions.query.bind(navigator.permissions);
      navigator.permissions.query = function (desc) {
        if (desc && desc.name === 'notifications') {
          return Promise.resolve({ name: 'notifications', state: 'granted', status: 'granted', onchange: null,
            addEventListener: function () {}, removeEventListener: function () {} });
        }
        return origQuery(desc);
      };
    }
  }

  // ---------------------------------------------------------------- blob: indirmeleri
  // WebView blob: URL'lerini kendisi indiremez. <a download href="blob:..."> tıklamalarını
  // yakalayıp içeriği base64 olarak Android'e gönderiyoruz.
  function saveBlobUrl(href, name) {
    fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
      var reader = new FileReader();
      reader.onloadend = function () {
        var b64 = String(reader.result).split(',')[1] || '';
        window.WhazzAndroid.saveFile(name || '', blob.type || '', b64);
      };
      reader.readAsDataURL(blob);
    }).catch(function () {});
  }
  function maybeHandleDownload(a) {
    if (!window.WhazzAndroid || !a || !a.href) return false;
    if (a.href.indexOf('blob:') !== 0 && a.href.indexOf('data:') !== 0) return false;
    if (!a.hasAttribute('download')) return false;
    saveBlobUrl(a.href, a.getAttribute('download'));
    return true;
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (maybeHandleDownload(this)) return;
    return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (a && maybeHandleDownload(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  // ---------------------------------------------------------------- CSS + viewport
  function ensureStyle() {
    if (document.getElementById('whazz-style') || !window.__WHAZZ_CSS) return;
    var s = document.createElement('style');
    s.id = 'whazz-style';
    s.textContent = window.__WHAZZ_CSS;
    (document.head || html()).appendChild(s);
  }
  function ensureViewport() {
    if (!document.head) return;
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); }
    var want = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, interactive-widget=resizes-content';
    if (m.content !== want) m.content = want;
  }

  // ---------------------------------------------------------------- Düzen tespiti
  function rectsSideBySide(a, b) {
    var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
    return rb.left >= ra.right - 2 || rb.right <= ra.left + 2;
  }

  function richness(el) { return el.getElementsByTagName('*').length; }

  function isFloating(el) {
    var pos = getComputedStyle(el).position;
    return pos === 'absolute' || pos === 'fixed';
  }

  function findRow(side, main) {
    var child = side;
    // Sohbet açıksa kesin yol: satır, #side ile #main'in en yakın ortak atasıdır.
    if (main) {
      while (child.parentElement && child.parentElement !== document.body) {
        if (child.parentElement.contains(main)) return { row: child.parentElement, sideCol: child };
        child = child.parentElement;
      }
      child = side;
    }
    // Sohbet kapalıyken tahmin: yanında normal akışta (absolute/fixed olmayan) geniş bir sütun olan
    // ilk ata. Sol sütundaki ekran dışı kayar paneller (Ayarlar, Yeni sohbet...) böylece elenir.
    while (child.parentElement && child.parentElement !== document.body) {
      var p = child.parentElement;
      if (!isFloating(child)) {
        for (var i = 0; i < p.children.length; i++) {
          var sib = p.children[i];
          if (sib === child || isFloating(sib)) continue;
          if (sib.offsetWidth > 20 && sib.offsetHeight > 200 && rectsSideBySide(child, sib)) {
            return { row: p, sideCol: child };
          }
        }
      }
      child = p;
    }
    return null;
  }

  /*
   * Gerçek sohbet listesi #side'ı. Aramalar sekmesi kendi listesinde de id="side" kullanıyor
   * (2 numaralı katmanın içinde, DOM'da önce geliyor), getElementById onu döndürür.
   * Gerçek olan, absolute/fixed bir katmanın içinde olmayandır.
   */
  function findSide() {
    var all = document.querySelectorAll('[id="side"]');
    if (all.length <= 1) return all[0] || null;
    var i;
    if (state.sideCol && state.sideCol.isConnected) {
      for (i = 0; i < all.length; i++) if (state.sideCol.contains(all[i])) return all[i];
    }
    for (i = 0; i < all.length; i++) {
      var floating = false;
      for (var e = all[i].parentElement; e && e.id !== 'app' && e !== document.body; e = e.parentElement) {
        if (e.hasAttribute('data-whazz') ? e.getAttribute('data-whazz') === 'drawer' || e.getAttribute('data-whazz') === 'slot' : isFloating(e)) {
          floating = true; break;
        }
      }
      if (!floating) return all[i];
    }
    return all[all.length - 1];
  }

  function tag() {
    var side = findSide();
    var main = document.getElementById('main');
    if (side) subScreenTries = 0;

    if (!state.row || !state.row.isConnected || (side && !state.row.contains(side)) ||
        (main && side && !state.row.contains(main))) {
      if (state.row) {
        state.row.removeAttribute('data-whazz-row');
        for (var j = 0; j < state.row.children.length; j++) state.row.children[j].removeAttribute('data-whazz');
      }
      state.row = null; state.sideCol = null;
      if (side) {
        var found = findRow(side, main);
        if (found) { state.row = found.row; state.sideCol = found.sideCol; }
      }
    }

    var row = state.row;
    var detailOpen = false;
    if (row) {
      if (!row.hasAttribute('data-whazz-row')) row.setAttribute('data-whazz-row', '');
      // Sütun yerinden çıktıysa (#side yeniden oluşturulduysa) sütun referansını güncelle
      if (side && (state.sideCol.parentElement !== row || !state.sideCol.contains(side))) {
        for (var k = 0; k < row.children.length; k++) {
          if (row.children[k].contains(side)) { state.sideCol = row.children[k]; break; }
        }
      }
      // Gerçek yapı (2026-11): [rail, katman, katman, side, mainCol, katman, sağ-panel, boş, #toast]
      // "Katman"lar WhatsApp'ın boş yer tutucularıdır; bir panel açılınca içleri dolar.
      var mainCol = state.sideCol.nextElementSibling;
      var passedSide = false;
      for (var i = 0; i < row.children.length; i++) {
        var c = row.children[i];
        var role;
        if (c === state.sideCol) { role = 'side'; passedSide = true; }
        else if (c === mainCol || (main && c.contains(main))) role = 'main';
        else if (c.id) role = 'keep';                       // ör. #wds-toast-container: dokunma
        else if (c.getAttribute('data-whazz') === 'rail') role = 'rail';
        else if (!passedSide && c.childElementCount > 0 && c.offsetWidth > 0 && c.offsetWidth < 100) role = 'rail';
        else if (richness(c) > 15) role = 'drawer';         // açık panel (Yeni sohbet, Grup bilgisi...)
        else role = 'slot';                                 // boş yer tutucu
        setAttr(c, 'data-whazz', role);
        if (role === 'drawer') { if (tagDrawer(c) === 'detail') detailOpen = true; }
        else if (c.hasAttribute('data-whazz-mode')) c.removeAttribute('data-whazz-mode');
        if (role === 'rail' && c.offsetWidth > 0) html().style.setProperty('--whazz-rail-w', c.offsetWidth + 'px');
      }
    }

    // "Zorla liste" yalnızca zorlandığı sohbet için geçerli: sohbet kapanınca ya da başka bir
    // sohbet/kanal açılınca (ray butonu, bildirim, arama sonucu...) kendiliğinden kalkar.
    if (state.forceList && (!main || chatTitle(main) !== state.forcedTitle)) state.forceList = false;
    var chatOpen = !!main && !state.forceList;
    html().classList.toggle('whazz-chat', chatOpen);
    html().classList.toggle('whazz-ready', !!row);
    // Android tarafı sohbet veya tam ekran detay açıkken hesap şeridini gizler.
    // Geri tuşunun kapatmaya çalıştığı katman kapandıysa kaydı sıfırla (aynı yuva yeniden kullanılır).
    if (lastEscaped && !(isShown(lastEscaped) &&
        (lastEscaped.getAttribute('data-whazz') === 'drawer' || lastEscaped.matches('[role="dialog"], [role="menu"]')))) {
      lastEscaped = null; lastEscapedKey = ''; escTries = 0;
    }
    var fullOpen = chatOpen || detailOpen;
    if (fullOpen !== state.lastFullOpen) {
      state.lastFullOpen = fullOpen;
      try { window.WhazzAndroid && window.WhazzAndroid.onChatMode(fullOpen); } catch (e) {}
    }
  }

  /** Açık sohbetin başlığı (kişi/grup/kanal adı): hangi sohbetin açık olduğunu ayırt etmek için. */
  function chatTitle(main) {
    // Başlığın tamamı değil yalnızca ad: alt satırdaki "çevrimiçi / yazıyor..." değişip durur.
    var h = main && main.querySelector('header');
    if (!h) return '';
    var name = h.querySelector('[dir="auto"]') || h;
    return (name.textContent || '').slice(0, 80);
  }

  function setAttr(el, name, value) {
    if (el.getAttribute(name) !== value) el.setAttribute(name, value);
  }

  /*
   * Dolu bir katmanı etiketler, modunu döndürür.
   * İki bölmeli katman (2 numaralı yuva) satırın bir kopyası gibidir: [liste | detay].
   * Aramalar, Durum, Kanallar, Topluluklar, Ayarlar, Yeni sohbet... sol bölmede liste olarak açılır;
   * sağ bölme ya boştur, ya başlıksız bir tanıtım ekranıdır, ya da başlıklı gerçek bir detaydır.
   *   list   → sol bölme, rayın sağında tam alan (ray görünür, sekmeler arası geçilebilir)
   *   detail → sağ bölme tam ekran
   * Tek bölmeli katmanlar (kişi/grup bilgisi, durum görüntüleyici...) her zaman tam ekran detaydır.
   */
  function tagDrawer(d) {
    var panes = d.children, mode = 'detail';
    if (panes.length >= 2) {
      var r0 = richness(panes[0]) > 15 ? 'list' : 'empty';
      var r1 = richness(panes[1]) <= 15 ? 'empty' : (panes[1].querySelector('header') ? 'detail' : 'placeholder');
      if (r0 === 'empty' && r1 === 'placeholder') r1 = 'list'; // yalnızca tanıtım ekranı varsa onu göster
      setAttr(panes[0], 'data-whazz-pane', r0);
      setAttr(panes[1], 'data-whazz-pane', r1);
      for (var p = 2; p < panes.length; p++) setAttr(panes[p], 'data-whazz-pane', richness(panes[p]) > 15 ? 'full' : 'empty');
      mode = r1 === 'detail' ? 'detail' : 'list';
    } else if (panes.length === 1) {
      setAttr(panes[0], 'data-whazz-pane', 'full');
    }
    setAttr(d, 'data-whazz-mode', mode);
    return mode;
  }

  // ---------------------------------------------------------------- Gözlemci
  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      ensureStyle();
      ensureViewport();
      try { tag(); } catch (e) {}
    });
  }

  // ---------------------------------------------------------------- Klavye: kendiliğinden açılmasın
  // WhatsApp masaüstü alışkanlığıyla yazı alanlarına kendisi odaklanıyor (ayarlardan dönünce arama
  // kutusu, sohbete girince mesaj kutusu...). Telefonda bu klavyeyi istemeden açar ve sonraki geri
  // basışını yutar. Odağa dokunmayız (blur, WhatsApp'ın yeniden odaklanmasıyla sonsuz döngüye
  // giriyordu); kullanıcı dokunmadan gelen odakta alana geçici inputmode="none" veririz: odak yerinde
  // kalır ama klavye açılmaz. Kullanıcı alana dokununca eski haline döner ve klavye normal açılır.
  var lastUserPointer = 0;
  function isEditable(t) {
    return !!t && (t.isContentEditable || t.tagName === 'INPUT' || t.tagName === 'TEXTAREA');
  }
  function restoreInputMode(t) {
    if (!t || !t.hasAttribute || !t.hasAttribute('data-whazz-im')) return;
    var prev = t.getAttribute('data-whazz-im');
    t.removeAttribute('data-whazz-im');
    if (prev) t.setAttribute('inputmode', prev); else t.removeAttribute('inputmode');
  }
  ['pointerdown', 'touchstart', 'mousedown'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (!e.isTrusted) return;
      lastUserPointer = Date.now();
      var t = e.target && e.target.closest ? e.target.closest('[data-whazz-im]') : null;
      restoreInputMode(t);
    }, true);
  });
  document.addEventListener('focusin', function (e) {
    var t = e.target;
    if (!isEditable(t) || t.hasAttribute('data-whazz-im')) return;
    if (Date.now() - lastUserPointer < 1000) return;
    t.setAttribute('data-whazz-im', t.getAttribute('inputmode') || '');
    t.setAttribute('inputmode', 'none');
  }, true);
  document.addEventListener('focusout', function (e) { restoreInputMode(e.target); }, true);

  // ---------------------------------------------------------------- Klavye: listeyi en altta tut
  // Klavye açılınca mesaj listesi kısalır; kullanıcı en alttaysa son mesajlar görünür kalsın
  // (WhatsApp kaydırmayı yalnızca kısmen düzeltiyor). Kullanıcı yukarı kaydırmışsa dokunmayız.
  var stick = { el: null, atBottom: true, quietUntil: 0 };
  function messageScroller() {
    var m = document.getElementById('main');
    if (!m) return null;
    if (stick.el && m.contains(stick.el)) return stick.el;
    var best = null, nodes = m.querySelectorAll('div');
    for (var i = 0; i < nodes.length; i++) {
      var e = nodes[i];
      if (e.closest('header') || e.closest('footer')) continue;
      var oy = getComputedStyle(e).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && (!best || e.clientHeight > best.clientHeight)) best = e;
    }
    if (best !== stick.el) { stick.el = best; stick.atBottom = true; }
    return best;
  }
  function gapOf(el) { return el.scrollHeight - el.scrollTop - el.clientHeight; }
  document.addEventListener('scroll', function (e) {
    var t = e.target;
    if (t !== stick.el) {
      if (!t || !t.closest || !t.closest('#main') || t.closest('header') || t.closest('footer')) return;
      stick.el = t;
    }
    if (Date.now() < stick.quietUntil) return;
    stick.atBottom = gapOf(t) < 60;
  }, true);
  function keepAtBottom() {
    var el = messageScroller();
    if (!el || !stick.atBottom) return;
    // Yeniden boyutlanma sırasında WhatsApp'ın kendi kaydırmaları atBottom'u bozmasın.
    stick.quietUntil = Date.now() + 400;
    var fix = function () { el.scrollTop = el.scrollHeight; };
    requestAnimationFrame(function () { fix(); requestAnimationFrame(fix); });
    setTimeout(fix, 250);
  }

  function start() {
    ensureStyle();
    ensureViewport();
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('resize', function () { schedule(); keepAtBottom(); });
    schedule();
  }

  // Listeden bir sohbete / kanala dokununca "zorla liste" modunu kaldır.
  // (Sohbet listesi ya da Kanallar gibi bir sekme listesindeki bir satır.)
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t || !t.closest) return;
    var inSide = state.sideCol && state.sideCol.contains(t);
    var inTabList = !!t.closest('[data-whazz="drawer"][data-whazz-mode="list"]') &&
      !!t.closest('[role="listitem"], [role="row"], [role="gridcell"]');
    if (inSide || inTabList) {
      state.forceList = false;
      setTimeout(schedule, 0);
    }
  }, true);

  function pressedTab() {
    return state.row ? state.row.querySelector(':scope > [data-whazz="rail"] button[aria-pressed="true"]') : null;
  }

  // ---------------------------------------------------------------- Geri tuşu
  function pressEscape() {
    var target = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function (type) {
      target.dispatchEvent(new KeyboardEvent(type, {
        key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true
      }));
    });
  }

  function isShown(el) {
    return el.isConnected && el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
  }

  function openOverlay() {
    // Önce görünür diyalog/menü, sonra tam ekran detay, sonra liste katmanı (Yeni sohbet, Aramalar...).
    // Sohbet açıkken liste katmanları CSS ile gizlidir; isShown onları eler.
    var dialogs = document.querySelectorAll('[role="dialog"], [role="menu"]');
    for (var i = 0; i < dialogs.length; i++) {
      if (isShown(dialogs[i])) return dialogs[i];
    }
    if (state.row) {
      var ds = state.row.querySelectorAll(':scope > [data-whazz="drawer"]');
      var modes = ['detail', 'list'];
      for (var m = 0; m < modes.length; m++) {
        for (var k = ds.length - 1; k >= 0; k--) {
          if (ds[k].getAttribute('data-whazz-mode') === modes[m] && isShown(ds[k])) return ds[k];
        }
      }
    }
    return null;
  }

  function overlayScope(el) {
    var mode = el.getAttribute('data-whazz-mode');
    return (mode && el.querySelector(':scope > [data-whazz-pane="' + mode + '"]')) || el;
  }

  /** Katmanın o anki "ekranı": mod + başlık. Alt sayfadan ana listeye dönmek de ilerleme sayılır. */
  function overlayKey(el) {
    if (!el) return '';
    var h = overlayScope(el).querySelector('header, h1, h2, [role="heading"]');
    return (el.getAttribute('data-whazz-mode') || 'dialog') + '|' + (h ? (h.textContent || '').slice(0, 60) : '');
  }

  // WhatsApp ikonlarında dil bağımsız işaret yok; panel başlığındaki geri/kapat butonunu etiketinden buluruz.
  var CLOSE_LABELS = ['Geri', 'Kapat', 'Back', 'Close'];
  function closeButtonIn(el) {
    var btns = el.querySelectorAll('button[aria-label], [role="button"][aria-label]');
    for (var i = 0; i < btns.length; i++) {
      if (CLOSE_LABELS.indexOf(btns[i].getAttribute('aria-label')) >= 0 && btns[i].offsetParent !== null) return btns[i];
    }
    return null;
  }

  // Son kapatmaya çalıştığımız katman ve o anki ekranı (overlayKey). Aynı ekran hâlâ açıksa
  // kapatma işe yaramamıştır: önce başka yolu deneriz, iki denemeden sonra takılı kalmamak için geçeriz.
  var lastEscaped = null, lastEscapedKey = '', escTries = 0;
  var subScreenTries = 0;

  /** Android geri tuşu çağırır. true = sayfa içinde bir şey kapattık; false = uygulamadan çık. */
  window.__whazzBack = function () {
    // Açık menü / panel varsa önce onu kapat. Escape tek başına hem paneli hem sohbeti
    // kapatabildiği için önce panelin kendi geri/kapat butonunu deneriz.
    var overlay = openOverlay();
    if (overlay) {
      var key = overlayKey(overlay);
      if (overlay === lastEscaped && key === lastEscapedKey) escTries++;
      else { lastEscaped = overlay; lastEscapedKey = key; escTries = 0; }
      if (escTries < 2) {
        var mode = overlay.getAttribute('data-whazz-mode');
        var btn = escTries === 0 ? closeButtonIn(overlayScope(overlay)) : null;
        if (btn) { btn.click(); return true; }
        if (mode === 'list') {
          // Sekme kök listesi (Aramalar, Durum, Kanallar, Siz...): kapatma butonu yok → Sohbetler sekmesine dön.
          var chats = state.row && state.row.querySelector(':scope > [data-whazz="rail"] button');
          if (chats && chats.getAttribute('aria-pressed') !== 'true') { chats.click(); return true; }
        }
        pressEscape();
        return true;
      }
    }
    lastEscaped = null; lastEscapedKey = ''; escTries = 0;
    if (document.getElementById('main') && !state.forceList) {
      var mainBefore = document.getElementById('main');
      var tabBefore = pressedTab();
      pressEscape();
      setTimeout(function () {
        // Kanal görünümünde Escape sohbeti kapatmıyor, Sohbetler sekmesine geçiyor: önceki sekmeye dön.
        var tabNow = pressedTab();
        if (tabBefore && tabNow !== tabBefore && tabBefore.isConnected) tabBefore.click();
        // WhatsApp Escape ile sohbeti kapatmadıysa, sadece görünümü listeye çevir.
        if (document.getElementById('main') === mainBefore) {
          state.forceList = true;
          state.forcedTitle = chatTitle(mainBefore);
          schedule();
        }
      }, 250);
      return true;
    }
    // Sol paneldeki bir alt ekran (Ayarlar, Yeni sohbet...) açıksa Escape ile geri dön.
    if (state.sideCol && !document.getElementById('side') && subScreenTries < 2) {
      subScreenTries++;
      pressEscape();
      return true;
    }
    subScreenTries = 0;
    return false;
  };

  // ---------------------------------------------------------------- Teşhis
  // chrome://inspect konsolunda: copy(__whazzDebug()) → panoya kopyalar.
  window.__whazzDebug = function () {
    function desc(el) {
      if (!el) return null;
      var r = el.getBoundingClientRect(), cs = getComputedStyle(el);
      return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') +
        ' role=' + (el.getAttribute('data-whazz') || '-') +
        ' pos=' + cs.position + ' disp=' + cs.display +
        ' x=' + Math.round(r.left) + ' w=' + Math.round(r.width) + ' h=' + Math.round(r.height) +
        ' desc=' + richness(el) +
        ' hasSide=' + !!el.querySelector('#side') + ' hasMain=' + !!el.querySelector('#main');
    }
    function chain(el) {
      var out = [];
      for (; el && el !== document.documentElement; el = el.parentElement) out.push(desc(el));
      return out;
    }
    var row = state.row;
    return JSON.stringify({
      htmlClass: html().className,
      viewport: innerWidth + 'x' + innerHeight,
      forceList: state.forceList,
      row: desc(row),
      rowChildren: row ? Array.prototype.map.call(row.children, desc) : [],
      sideChain: chain(document.getElementById('side')),
      mainChain: chain(document.getElementById('main'))
    }, null, 2);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
