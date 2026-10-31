/* Grably: marketing site behavior */
(function () {
  'use strict';

  /* Platforms Grably supports, matched by hostname. */
  var PLATFORMS = [
    { name: 'TikTok', cls: 'tiktok', hosts: ['tiktok.com'] },
    { name: 'Instagram', cls: 'instagram', hosts: ['instagram.com', 'instagr.am'] },
    { name: 'Facebook', cls: 'facebook', hosts: ['facebook.com', 'fb.com', 'fb.watch'] },
    { name: 'X', cls: 'x', hosts: ['x.com', 'twitter.com', 't.co'] },
    { name: 'Pinterest', cls: 'pinterest', hosts: ['pinterest.com', 'pin.it'], match: /(^|\.)pinterest\.[a-z.]+$/ }
  ];

  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- Toast ---------- */
  var toastEl = document.getElementById('toast');
  var toastTimer;
  function toast(message) {
    if (!toastEl) return;
    toastEl.textContent = message;
    toastEl.hidden = false;
    // next frame so the transition runs
    requestAnimationFrame(function () { toastEl.classList.add('is-shown'); });
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

  function onScroll() { header.classList.toggle('is-scrolled', window.scrollY > 8); }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

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

  /* ---------- Link checker ---------- */
  var form = document.getElementById('checker');
  var input = document.getElementById('checker-input');
  var result = document.getElementById('checker-result');
  var pasteBtn = document.getElementById('checker-paste');

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
    return s.replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function render(outcome) {
    result.classList.remove('is-ok', 'is-error');
    if (outcome.state === 'ok') {
      var p = outcome.platform;
      result.classList.add('is-ok');
      result.innerHTML =
        '<span class="pf pf--' + p.cls + '"><svg aria-hidden="true"><use href="#i-' + p.cls + '"/></svg></span>' +
        '<span><strong>' + p.name + ' is supported.</strong> Once Grably launches, copy a link like this, open the app, and it\'s filled in for you automatically.</span>';
    } else if (outcome.state === 'unsupported') {
      result.classList.add('is-error');
      result.innerHTML = '<span>Grably doesn\'t support <strong>' + escapeHtml(outcome.host) + '</strong> yet. It works with TikTok, Instagram, Facebook, X and Pinterest.</span>';
    } else if (outcome.state === 'invalid') {
      result.classList.add('is-error');
      result.innerHTML = '<span>That doesn\'t look like a link. In the social app, tap <strong>Share → Copy link</strong>, then paste it here.</span>';
    } else {
      result.classList.add('is-error');
      result.innerHTML = '<span>Paste a video link first. Try one from TikTok, Instagram, Facebook, X or Pinterest.</span>';
    }
  }

  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      render(detect(input.value));
    });
    input.addEventListener('paste', function () {
      // Check right after the pasted text lands in the field
      setTimeout(function () { render(detect(input.value)); }, 0);
    });
    input.addEventListener('input', function () {
      if (!input.value.trim()) { result.textContent = ''; result.classList.remove('is-ok', 'is-error'); }
    });

    if (pasteBtn) {
      if (!(navigator.clipboard && navigator.clipboard.readText)) {
        pasteBtn.hidden = true;
      } else {
        pasteBtn.addEventListener('click', function () {
          navigator.clipboard.readText().then(function (text) {
            input.value = text.trim();
            render(detect(input.value));
          }).catch(function () {
            input.focus();
            toast('Press ' + (/Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl') + '+V to paste your link.');
          });
        });
      }
    }
  }

  /* Buttons elsewhere on the page that jump to the checker also focus it */
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

  /* ---------- Download manager demo: filter tabs ---------- */
  var manager = document.getElementById('manager');
  if (manager) {
    var tabs = manager.querySelectorAll('[data-filter]');
    var items = manager.querySelectorAll('.dl-item');
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        var filter = tab.getAttribute('data-filter');
        tabs.forEach(function (t) {
          var active = t === tab;
          t.classList.toggle('is-active', active);
          t.setAttribute('aria-pressed', String(active));
        });
        items.forEach(function (item) {
          var status = item.getAttribute('data-status');
          item.hidden = !(filter === 'all' || status === filter);
        });
      });
    });
  }

  /* ---------- Download manager demo: live progress ---------- */
  var live = document.getElementById('live-item');
  if (live && !reduceMotion) {
    var bar = live.querySelector('[data-live-bar]');
    var pct = live.querySelector('[data-live-pct]');
    var size = live.querySelector('[data-live-size]');
    var total = 8.9;
    var progress = 58;
    var running = false;
    var timer;

    function paint() {
      bar.style.width = progress + '%';
      pct.textContent = progress + '%';
      size.textContent = (total * progress / 100).toFixed(1) + ' / ' + total + ' MB';
    }
    function tick() {
      progress = Math.min(100, progress + 2 + Math.round(Math.random() * 3));
      paint();
      if (progress >= 100) {
        pct.textContent = 'Done';
        timer = setTimeout(function () { progress = 4; paint(); timer = setTimeout(tick, 600); }, 1800);
      } else {
        timer = setTimeout(tick, 260);
      }
    }
    // Only animate while the demo is on screen
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        var visible = entries[0].isIntersecting;
        if (visible && !running) { running = true; tick(); }
        else if (!visible && running) { running = false; clearTimeout(timer); }
      }).observe(live);
    }
  }

  /* ---------- Hero phone: "auto-detect" typing effect ---------- */
  var typing = document.querySelector('[data-typing]');
  if (typing && !reduceMotion) {
    var placeholder = typing.textContent;
    var demoLink = 'tiktok.com/@ava.travels/video/7311';
    var cycle = function () {
      var i = 0;
      typing.classList.add('is-typed');
      (function type() {
        typing.textContent = demoLink.slice(0, ++i);
        if (i < demoLink.length) setTimeout(type, 45);
        else setTimeout(function () {
          typing.classList.remove('is-typed');
          typing.textContent = placeholder;
          setTimeout(cycle, 2600);
        }, 2600);
      })();
    };
    setTimeout(cycle, 1400);
  }

  /* ---------- Footer year ---------- */
  var year = document.querySelector('[data-year]');
  if (year) year.textContent = new Date().getFullYear();
})();
