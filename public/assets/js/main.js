/* Grabbly: website behavior and the web downloader */
(function () {
  'use strict';
  window.__grably = true; // tells the fallback in index.html that this script loaded

  /* Platforms Grabbly supports, matched by hostname. */
  var PLATFORMS = [
    { name: 'TikTok', cls: 'tiktok', hosts: ['tiktok.com'] },
    { name: 'Instagram', cls: 'instagram', hosts: ['instagram.com', 'instagr.am'] },
    { name: 'Facebook', cls: 'facebook', hosts: ['facebook.com', 'fb.com', 'fb.watch'] },
    { name: 'X', cls: 'x', hosts: ['x.com', 'twitter.com', 't.co'] },
    { name: 'Pinterest', cls: 'pinterest', hosts: ['pinterest.com', 'pin.it'], match: /(^|\.)pinterest\.[a-z.]+$/ },
    { name: 'Twitch', cls: 'twitch', hosts: ['twitch.tv'] },
    { name: 'Tumblr', cls: 'tumblr', hosts: ['tumblr.com', 'tmblr.co'] }
  ];
  var SUPPORTED = PLATFORMS.slice(0, -1).map(function (p) { return p.name; }).join(', ') + ' and ' + PLATFORMS[PLATFORMS.length - 1].name;

  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- Toast ---------- */
  var toastEl = document.getElementById('toast');
  var toastTimer;
  function toast(message) {
    if (!toastEl) return;
    toastEl.textContent = message;
    toastEl.hidden = false;
    // wait two frames so the starting state is rendered and the transition actually runs
    requestAnimationFrame(function () { requestAnimationFrame(function () { toastEl.classList.add('is-shown'); }); });
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toastEl.classList.remove('is-shown');
      setTimeout(function () { toastEl.hidden = true; }, 300);
    }, 3200);
  }

  /* ---------- Header: shadow on scroll + mobile menu ---------- */
  var header = document.querySelector('.site-header');
  var nav = document.getElementById('site-nav');
  var toggle = document.querySelector('.nav-toggle');

  // Header shadow once the page is scrolled. Watching a tiny marker at the top of the page
  // means no code runs on every scroll event and nothing forces an extra layout.
  var marker = document.createElement('div');
  marker.setAttribute('aria-hidden', 'true');
  marker.style.cssText = 'position:absolute;top:0;left:0;width:1px;height:8px;pointer-events:none;';
  document.body.prepend(marker);
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(function (entries) {
      header.classList.toggle('is-scrolled', !entries[entries.length - 1].isIntersecting);
    }).observe(marker);
  }

  function setMenu(open) {
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
    nav.classList.toggle('is-open', open);
  }
  toggle.addEventListener('click', function () {
    setMenu(toggle.getAttribute('aria-expanded') !== 'true');
  });
  nav.addEventListener('click', function (e) {
    if (e.target.closest('a')) setMenu(false);
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && nav.classList.contains('is-open')) { setMenu(false); toggle.focus(); }
  });
  document.addEventListener('click', function (e) {
    if (nav.classList.contains('is-open') && !e.target.closest('.site-header')) setMenu(false);
  });

  /* ---------- Web downloader ----------
   * Paste a link -> POST /api/info -> show title, thumbnail and qualities ->
   * GET /api/download -> save the file. If the API can't be reached (for example
   * when the page is opened as a plain file), it falls back to checking links only.
   */
  var form = document.getElementById('checker');
  var input = document.getElementById('checker-input');
  var result = document.getElementById('checker-result');
  var pasteBtn = document.getElementById('checker-paste');
  var card = document.getElementById('dl-card');
  var submitBtn = form && form.querySelector('.checker__submit');
  var label = form && form.querySelector('.checker__label');

  var apiMeta = document.querySelector('meta[name="grably-api"]');
  var API = ((apiMeta && apiMeta.content) || '').trim().replace(/\/+$/, '');

  function ApiError(message) { this.message = message; }

  function timeoutFetch(url, options, ms) {
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, ms);
    return fetch(url, Object.assign({}, options, { signal: controller.signal }))
      .finally(function () { clearTimeout(timer); });
  }

  function readError(res) {
    return res.json().catch(function () { return null; }).then(function (body) {
      if (body && body.error) return body.error;
      if (res.status === 429) return 'You\'re going a bit fast. Wait a minute and try again.';
      if (res.status === 503) return 'Grabbly is busy right now. Try again in a moment.';
      return 'Something went wrong. Please try again.';
    });
  }

  var noServer = location.protocol === 'file:' && !API;
  var defaultLabel = label ? label.textContent : '';

  function checkApi() {
    if (noServer) return Promise.resolve(false);
    return timeoutFetch(API + '/api/health', {}, 6000)
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (body) { return !!(body && body.ok); })
      .catch(function () { return false; });
  }

  function setApiState(ok) {
    if (label) label.textContent = ok ? defaultLabel : 'Check a link: does Grabbly support it?';
    return ok;
  }

  var apiReady = checkApi().then(setApiState);

  function detect(raw) {
    var text = raw.trim();
    if (!text) return { state: 'empty' };
    // Pull the first URL-looking token out of pasted text such as "Check this out! https://..."
    var found = text.match(/(https?:\/\/[^\s]+)|((?:[a-z0-9-]+\.)+[a-z]{2,}\/?[^\s]*)/i);
    if (!found) return { state: 'invalid' };
    var candidate = found[0];
    if (!/^https?:\/\//i.test(candidate)) candidate = 'https://' + candidate;
    var url;
    try { url = new URL(candidate); } catch (err) { return { state: 'invalid' }; }
    var host = url.hostname.toLowerCase().replace(/^(www|m|mobile|vm|vt)\./, '');
    for (var i = 0; i < PLATFORMS.length; i++) {
      var p = PLATFORMS[i];
      var hit = p.hosts.some(function (h) { return host === h || host.endsWith('.' + h); }) || (p.match && p.match.test(host));
      if (hit) return { state: 'ok', platform: p };
    }
    return { state: 'unsupported', host: host };
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function icon(id) { return '<svg aria-hidden="true"><use href="#i-' + id + '"/></svg>'; }
  function platformIcon(cls) { return '<span class="pf pf--' + cls + '">' + icon(cls) + '</span>'; }

  function setMessage(html, kind) {
    result.classList.remove('is-ok', 'is-error');
    if (kind) result.classList.add(kind);
    result.innerHTML = html || '';
  }

  function explain(outcome) {
    if (outcome.state === 'unsupported') {
      setMessage('<span>Grabbly doesn\'t support <strong>' + escapeHtml(outcome.host) + '</strong> yet. It works with ' + SUPPORTED + '.</span>', 'is-error');
    } else if (outcome.state === 'invalid') {
      setMessage('<span>That doesn\'t look like a link. In the social app, tap <strong>Share → Copy link</strong>, then paste it here.</span>', 'is-error');
    } else if (outcome.state === 'empty') {
      setMessage('<span>Paste a video link first. Try one from TikTok, Instagram, Facebook or X.</span>', 'is-error');
    } else {
      // Supported link, but the download server isn't reachable
      setMessage(platformIcon(outcome.platform.cls) + '<span><strong>' + outcome.platform.name + ' is supported</strong>, but downloads aren\'t available right now. Please try again later.</span>');
    }
  }

  function fmtDuration(s) {
    if (s == null) return '';
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = Math.floor(s % 60);
    var mm = h ? String(m).padStart(2, '0') : String(m);
    return (h ? h + ':' : '') + mm + ':' + String(sec).padStart(2, '0');
  }

  function fmtSize(bytes) {
    if (!bytes) return '';
    var mb = bytes / 1048576;
    if (mb < 1) return Math.max(1, Math.round(bytes / 1024)) + ' KB';
    if (mb >= 1000) return (mb / 1024).toFixed(1) + ' GB';
    if (mb >= 10) return Math.round(mb) + ' MB';
    return mb.toFixed(1) + ' MB';
  }

  var busy = false;
  var current = null;
  var activeDownload = null; // AbortController of the download in progress

  function setLoading(on) {
    busy = on;
    submitBtn.classList.toggle('is-loading', on);
    submitBtn.disabled = on;
    submitBtn.setAttribute('aria-label', on ? 'Finding video' : 'Get video');
    input.readOnly = on;
  }

  function hideCard() {
    // Closing or replacing the card cancels its download, so an old file can't save later
    if (activeDownload) { activeDownload.abort(); activeDownload = null; }
    current = null;
    card.hidden = true;
    card.className = 'dl-card';
    card.innerHTML = '';
  }

  function showCard(data) {
    current = data;
    var duration = fmtDuration(data.duration);
    var options = (data.options || []).map(function (o, i) {
      return '<button type="button" class="dl-opt' + (i === 0 ? ' dl-opt--primary' : '') + '" data-option="' + escapeHtml(o.id) + '">' +
        icon(o.kind === 'audio' ? 'music' : 'download') +
        '<span>' + escapeHtml(o.format + ' · ' + o.quality) + '</span>' +
        (o.size ? '<small>~' + fmtSize(o.size) + '</small>' : '') +
        '</button>';
    }).join('');

    card.innerHTML =
      '<button type="button" class="dl-card__close" aria-label="Clear and start over">' + icon('close') + '</button>' +
      '<div class="dl-card__media">' +
        (data.thumbnail ? '<img src="' + escapeHtml(API + data.thumbnail) + '" alt="" loading="lazy" decoding="async">' : '') +
        (duration ? '<span class="dl-card__dur">' + duration + '</span>' : '') +
      '</div>' +
      '<div class="dl-card__body">' +
        '<p class="dl-card__meta">' + platformIcon(escapeHtml(data.platform)) +
          '<span>' + escapeHtml(data.platform_name) + (data.uploader ? ' · ' + escapeHtml(data.uploader) : '') + '</span></p>' +
        '<h2 class="dl-card__title">' + escapeHtml(data.title) + '</h2>' +
        '<div class="dl-card__options" role="group" aria-label="Choose a quality to download">' + options + '</div>' +
        '<div class="dl-card__status" role="status" aria-live="polite"><span class="bar"><i class="is-scaled"></i></span><small></small></div>' +
        '<p class="dl-card__note">Only download videos you have the right to save.</p>' +
      '</div>';
    card.className = 'dl-card';
    card.hidden = false;

    var img = card.querySelector('img');
    if (img) img.addEventListener('error', function () { img.remove(); });
    var first = card.querySelector('.dl-opt');
    if (first) first.focus({ preventScroll: true });
    if (card.getBoundingClientRect().bottom > window.innerHeight) {
      card.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'nearest' });
    }
  }

  function lookup(text) {
    setLoading(true);
    hideCard();
    setMessage('<span>Finding your video…</span>');
    timeoutFetch(API + '/api/info', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: text })
    }, 60000)
      .then(function (res) {
        if (!res.ok) return readError(res).then(function (msg) { throw new ApiError(msg); });
        return res.json();
      })
      .then(function (data) {
        setMessage('');
        showCard(data);
      })
      .catch(function (err) {
        var msg = err instanceof ApiError ? err.message
          : err && err.name === 'AbortError' ? 'That took too long. Please try again.'
          : 'Couldn\'t reach Grabbly. Check your connection and try again.';
        setMessage('<span>' + escapeHtml(msg) + '</span>', 'is-error');
      })
      .finally(function () { setLoading(false); });
  }

  function filenameFrom(disposition) {
    if (!disposition) return null;
    var star = /filename\*\s*=\s*utf-8''([^;]+)/i.exec(disposition);
    if (star) { try { return decodeURIComponent(star[1].trim()); } catch (e) { /* fall through */ } }
    var plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition);
    return plain ? plain[1] : null;
  }

  function saveBlob(blob, name) {
    var href = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = href;
    a.download = name;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(href); }, 60000);
  }

  function startDownload(btn) {
    if (!current || activeDownload) return;
    var controller = new AbortController();
    activeDownload = controller;
    function isCurrent() { return activeDownload === controller; }

    var buttons = card.querySelectorAll('.dl-opt');
    var bar = card.querySelector('.dl-card__status .bar');
    var fill = bar.querySelector('i');
    var text = card.querySelector('.dl-card__status small');
    function setProgress(fraction) { fill.style.transform = 'scaleX(' + fraction + ')'; }

    buttons.forEach(function (b) { b.disabled = true; });
    btn.classList.add('is-active');
    card.classList.remove('is-done', 'is-failed');
    card.classList.add('is-busy');
    bar.classList.add('is-indeterminate');
    setProgress(0);
    text.textContent = 'Preparing your video… this can take a few seconds.';

    var params = new URLSearchParams({ url: current.url, option: btn.getAttribute('data-option') });
    fetch(API + '/api/download?' + params.toString(), { signal: controller.signal })
      .then(function (res) {
        if (!res.ok) return readError(res).then(function (msg) { throw new ApiError(msg); });
        var total = Number(res.headers.get('Content-Length')) || 0;
        var type = res.headers.get('Content-Type') || 'application/octet-stream';
        var name = filenameFrom(res.headers.get('Content-Disposition')) || 'grably-video.mp4';
        bar.classList.remove('is-indeterminate');
        if (!res.body || !res.body.getReader) {
          return res.blob().then(function (blob) { return { blob: blob, name: name }; });
        }
        var reader = res.body.getReader();
        var chunks = [];
        var received = 0;
        var lastUpdate = 0;
        return (function pump() {
          return reader.read().then(function (step) {
            if (step.done) return { blob: new Blob(chunks, { type: type }), name: name };
            chunks.push(step.value);
            received += step.value.length;
            // Chunks can arrive hundreds of times a second; update the screen at most 10 times a second
            var now = performance.now();
            if (now - lastUpdate > 100) {
              lastUpdate = now;
              if (total) {
                var fraction = Math.min(1, received / total);
                setProgress(fraction);
                text.textContent = 'Downloading… ' + Math.round(fraction * 100) + '% (' + fmtSize(received) + ' of ' + fmtSize(total) + ')';
              } else {
                text.textContent = 'Downloading… ' + fmtSize(received);
              }
            }
            return pump();
          });
        })();
      })
      .then(function (out) {
        if (!isCurrent()) return;
        saveBlob(out.blob, out.name);
        setProgress(1);
        card.classList.add('is-done');
        text.innerHTML = icon('check') + '<span>Saved to your Downloads folder as “' + escapeHtml(out.name) + '”.</span>';
      })
      .catch(function (err) {
        if (!isCurrent()) return; // cancelled: the card was closed or replaced
        card.classList.add('is-failed');
        bar.classList.remove('is-indeterminate');
        setProgress(0);
        text.textContent = err instanceof ApiError ? err.message : 'The download was interrupted. Please try again.';
      })
      .finally(function () {
        if (!isCurrent()) return;
        activeDownload = null;
        card.classList.remove('is-busy');
        btn.classList.remove('is-active');
        buttons.forEach(function (b) { b.disabled = false; });
      });
  }

  function handle(text) {
    var outcome = detect(text);
    if (outcome.state !== 'ok') { hideCard(); explain(outcome); return; }
    if (busy) return;
    // If the server was unreachable earlier (say, a network blip), ask again before giving up
    apiReady = apiReady.then(function (ok) { return ok || checkApi().then(setApiState); });
    apiReady.then(function (ok) {
      if (ok) lookup(text.trim());
      else { hideCard(); explain(outcome); }
    });
  }

  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      handle(input.value);
    });
    input.addEventListener('paste', function () {
      // Start right after the pasted text lands in the field
      setTimeout(function () { if (detect(input.value).state === 'ok') handle(input.value); }, 0);
    });
    input.addEventListener('input', function () {
      if (!input.value.trim()) { setMessage(''); hideCard(); }
    });
    card.addEventListener('click', function (e) {
      var opt = e.target.closest('.dl-opt');
      if (opt && !opt.disabled) { startDownload(opt); return; }
      if (e.target.closest('.dl-card__close')) {
        hideCard();
        setMessage('');
        input.value = '';
        input.focus();
      }
    });

    if (pasteBtn) {
      if (!(navigator.clipboard && navigator.clipboard.readText)) {
        pasteBtn.hidden = true;
      } else {
        pasteBtn.addEventListener('click', function () {
          navigator.clipboard.readText().then(function (text) {
            input.value = text.trim();
            handle(input.value);
          }).catch(function () {
            input.focus();
            toast('Press ' + (/Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl') + '+V to paste your link.');
          });
        });
      }
    }
  }

  /* Buttons elsewhere on the page that jump to the downloader also focus it */
  document.querySelectorAll('[data-focus-checker]').forEach(function (link) {
    link.addEventListener('click', function () {
      if (input) setTimeout(function () { input.focus({ preventScroll: true }); }, reduceMotion ? 0 : 450);
    });
  });

  /* ---------- Reveal on scroll ---------- */
  var revealEls = document.querySelectorAll('[data-reveal]');
  if ('IntersectionObserver' in window && !reduceMotion) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          io.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -10% 0px', threshold: 0.12 });
    revealEls.forEach(function (el) { io.observe(el); });
  } else {
    revealEls.forEach(function (el) { el.classList.add('is-visible'); });
  }

  /* ---------- Pause endless animations while their section is off screen ---------- */
  if ('IntersectionObserver' in window) {
    var scopeObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) { entry.target.classList.toggle('is-offscreen', !entry.isIntersecting); });
    });
    document.querySelectorAll('.hero, .site-footer').forEach(function (el) { scopeObserver.observe(el); });
  }

  /* ---------- Hero phone: "auto-detect" typing effect ----------
   * Only runs while the phone is on screen and the tab is visible. */
  var typing = document.querySelector('[data-typing]');
  if (typing && !reduceMotion) {
    var placeholder = typing.textContent;
    var demoLink = 'tiktok.com/@ava.travels/video/7311';
    var typed = 0;
    var inView = false;
    var typingTimer = null;
    var typingActive = function () { return inView && !document.hidden; };
    var typeStep = function () {
      typingTimer = null;
      if (!typingActive()) return;
      if (typed < demoLink.length) {
        typed += 1;
        typing.textContent = demoLink.slice(0, typed);
        typing.classList.add('is-typed');
        typingTimer = setTimeout(typeStep, typed === demoLink.length ? 2600 : 45);
      } else {
        typed = 0;
        typing.classList.remove('is-typed');
        typing.textContent = placeholder;
        typingTimer = setTimeout(typeStep, 2600);
      }
    };
    var updateTyping = function () {
      if (typingActive() && !typingTimer) typingTimer = setTimeout(typeStep, 900);
      if (!typingActive() && typingTimer) { clearTimeout(typingTimer); typingTimer = null; }
    };
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        inView = entries[entries.length - 1].isIntersecting;
        updateTyping();
      }).observe(typing.closest('.phone') || typing);
    } else {
      inView = true;
      updateTyping();
    }
    document.addEventListener('visibilitychange', updateTyping);
  }

  /* ---------- Google AdSense ----------
   * Ads appear only when a publisher ID and ad unit ID are set (meta tags in index.html, filled in by
   * the server from GRABLY_ADSENSE_CLIENT / GRABLY_ADSENSE_SLOT). Google's script loads after the page
   * has finished loading, and each ad is requested only when it's about to scroll into view, so ads
   * never slow down the first paint or scrolling.
   */
  var metaContent = function (name) {
    var m = document.querySelector('meta[name="' + name + '"]');
    return m ? m.content.trim() : '';
  };
  var adClient = metaContent('grably-ads-client');
  var adUnit = metaContent('grably-ads-slot');
  var adSpaces = [].slice.call(document.querySelectorAll('[data-ad]'));
  var hideAds = function () {
    document.documentElement.classList.remove('has-ads');
    adSpaces.forEach(function (el) { el.remove(); });
  };

  if (!/^ca-pub-\d{10,20}$/.test(adClient) || !/^\d{5,20}$/.test(adUnit)) {
    hideAds();
  } else {
    var adScript = null;
    var loadAdScript = function () {
      if (adScript) return;
      adScript = document.createElement('script');
      adScript.async = true;
      adScript.crossOrigin = 'anonymous';
      adScript.src = 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=' + encodeURIComponent(adClient);
      adScript.onerror = hideAds; // blocked by an ad blocker or offline: don't leave empty boxes
      document.head.appendChild(adScript);
    };
    var fillAd = function (space) {
      var ins = document.createElement('ins');
      ins.className = 'adsbygoogle';
      ins.style.display = 'block';
      ins.setAttribute('data-ad-client', adClient);
      ins.setAttribute('data-ad-slot', adUnit);
      ins.setAttribute('data-ad-format', 'auto');
      ins.setAttribute('data-full-width-responsive', 'true');
      space.querySelector('.ad-slot__unit').appendChild(ins);
      loadAdScript();
      (window.adsbygoogle = window.adsbygoogle || []).push({});
    };
    var startAds = function () {
      if (!('IntersectionObserver' in window)) { adSpaces.forEach(fillAd); return; }
      var adObserver = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          adObserver.unobserve(entry.target);
          fillAd(entry.target);
        });
      }, { rootMargin: '600px 0px' });
      adSpaces.forEach(function (el) { adObserver.observe(el); });
    };
    if (document.readyState === 'complete') startAds();
    else window.addEventListener('load', startAds, { once: true });
  }

  /* ---------- Footer year ---------- */
  var year = document.querySelector('[data-year]');
  if (year) year.textContent = new Date().getFullYear();
})();
