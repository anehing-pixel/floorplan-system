/* ============================================================
   app.js —— 控制台主程序：路由 / 导航 / 令牌守卫
   ============================================================ */
(function (global) {
  'use strict';

  const FP = global.FP;
  const $ = FP.$;
  const $$ = FP.$$;

  /* ---------- 令牌守卫 ---------- */
  const TOKEN = localStorage.getItem('fp_token');
  if (!TOKEN) {
    location.href = 'login.html';
    return;
  }

  function parseClaims(token) {
    try {
      const p = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(decodeURIComponent(atob(p + '='.repeat((4 - p.length % 4) % 4))
        .split('').map(function (c) { return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2); }).join('')));
    } catch (e) { return null; }
  }

  const claims = parseClaims(TOKEN);
  if (!claims) {
    localStorage.removeItem('fp_token');
    location.href = 'login.html';
    return;
  }

  /* ---------- 路由表 ---------- */
  const ROUTES = [
    { id: 'dashboard', label: '经营总览', icon: '◱', group: '经营看板', view: 'dashboard' },
    { id: 'dealers', label: '经销商', icon: '☰', group: '经营看板', view: 'dealers' },
    { id: 'credit-lines', label: '授信额度', icon: '¥', group: '经营看板', view: 'creditLines' },
    { id: 'vehicles', label: '库存车辆', icon: '▤', group: '库存与放款', view: 'vehicles' },
    { id: 'audits', label: '盘点监管', icon: '◎', group: '库存与放款', view: 'audits' },
    // ADR-007：采购订单是放款前置主单，单独成组
    { id: 'purchase-orders', label: '采购订单', icon: '⛁', group: '采购与收款', view: 'purchaseOrders' },
    { id: 'suppliers', label: '上游与账户', icon: '⌂', group: '采购与收款', view: 'suppliers' },
    { id: 'alerts', label: '风险告警', icon: '⚠', group: '风险与配置', view: 'alerts' },
    { id: 'jobs', label: '批量作业', icon: '⟳', group: '风险与配置', view: 'jobs' },
    { id: 'ratings', label: '评级口径', icon: '⚙', group: '风险与配置', view: 'ratingConfigs' },
    { id: 'account', label: '我的凭证', icon: '◉', group: '风险与配置', view: 'account' }
  ];

  /* ---------- 渲染导航 ---------- */
  function renderNav() {
    const nav = $('#nav');
    const groups = [];
    ROUTES.forEach(function (r) {
      if (!groups.length || groups[groups.length - 1].name !== r.group) {
        groups.push({ name: r.group, items: [] });
      }
      groups[groups.length - 1].items.push(r);
    });

    let html = '';
    groups.forEach(function (g) {
      html += '<div class="nav-group-title">' + g.name + '</div>';
      g.items.forEach(function (r) {
        html += '<div class="nav-item" data-route="' + r.id + '">' +
          '<span class="ico">' + r.icon + '</span><span>' + r.label + '</span>' +
          '<span class="badge" id="navbadge-' + r.id + '" style="display:none"></span></div>';
      });
    });
    nav.innerHTML = html;

    $$('.nav-item').forEach(function (item) {
      item.addEventListener('click', function () {
        location.hash = '#/' + item.dataset.route;
        $('#sidebar').classList.remove('open');
      });
    });
  }

  /* ---------- 视图切换 ---------- */
  let currentRoute = null;

  async function navigate(routeId) {
    const route = ROUTES.filter(function (r) { return r.id === routeId; })[0] || ROUTES[0];

    $$('.nav-item').forEach(function (i) {
      i.classList.toggle('active', i.dataset.route === route.id);
    });
    $('#crumbTitle').textContent = route.label;
    $('#crumbSub').textContent = route.group;

    $$('.view').forEach(function (v) { v.classList.remove('active'); });
    const root = $('#view-' + route.view);
    root.classList.add('active');

    currentRoute = route.id;
    try {
      // 视图可能挂在 FP.views（既有）或 FP.poViews（ADR-007 新增）上，两处都能取到。
      const registry = FP.views || {};
      const extra = global.FP.poViews || {};
      const fn = registry[route.view] || extra[route.view];
      if (typeof fn !== 'function') {
        throw new Error('视图未注册：' + route.view);
      }
      await fn(root);
    } catch (e) {
      root.innerHTML = '<div class="card"><div class="body"><div class="empty">页面加载失败：' +
        (e && e.message ? e.message : e) + '</div></div></div>';
    }
  }

  /* ---------- 顶栏：用户信息与令牌倒计时 ---------- */
  function renderTopbar() {
    const username = FP.auth.username() || claims.sub || 'user';
    const role = FP.auth.role();
    $('#whoami').textContent = username;
    $('#avatar').textContent = username.substring(0, 1).toUpperCase();

    // 角色徽章：一眼看出当前账号是谁、能做什么
    // 一人多岗时显示"主岗 + 兼岗数"，点击展开全部角色
    const roleChip = $('#roleChip');
    if (roleChip) {
      const labels = FP.auth.roleLabels();
      const roles = FP.auth.roles();
      const main = labels.length ? labels[0] : FP.auth.role();
      const extra = labels.length > 1 ? (' +' + (labels.length - 1) + '兼岗') : '';
      roleChip.textContent = main + extra;
      roleChip.className = 'role-chip ' + (FP.auth.isAdmin() ? 'admin' : 'user');
      roleChip.title = roles.length
        ? ('当前角色：' + labels.join('、') + '\n可执行写操作：' + (FP.auth.permissions().length || '无'))
        : '当前账号无业务写权限，仅可查询';
    }

    const chip = $('#tokenChip');
    function tick() {
      if (!claims.exp) return;
      const left = claims.exp * 1000 - Date.now();
      if (left <= 0) {
        chip.classList.add('expiring');
        chip.innerHTML = '令牌已过期';
        return;
      }
      const m = Math.floor(left / 60000);
      chip.innerHTML = '令牌剩余 <b>' + (m >= 60 ? Math.floor(m / 60) + ' 小时 ' + (m % 60) + ' 分' : m + ' 分钟') + '</b>';
      chip.classList.toggle('expiring', left < 10 * 60000);
    }
    tick();
    setInterval(tick, 30000);
  }

  /* ---------- 全局刷新 ---------- */
  async function refreshAll() {
    FP.views.invalidate();
    if (global.FP.poViews && global.FP.poViews.invalidate) {
      global.FP.poViews.invalidate();
    }
    FP.toast('正在刷新数据…', '');
    await navigate(currentRoute);
    FP.toast('数据已刷新', 'ok');
  }

  /* ---------- 侧栏告警角标 ---------- */
  async function refreshBadge() {
    try {
      const alerts = await FP.api.get('/api/alerts?limit=200');
      const open = alerts.filter(function (a) { return a.status === 'OPEN'; }).length;
      const badge = $('#navbadge-alerts');
      if (open > 0) {
        badge.style.display = 'inline-block';
        badge.textContent = open;
      } else {
        badge.style.display = 'none';
      }
    } catch (e) { /* 角标失败不影响主流程 */ }
  }

  /* ---------- 初始化 ---------- */
  document.addEventListener('DOMContentLoaded', function () {
    renderNav();
    renderTopbar();

    $('#logoutBtn').addEventListener('click', function () {
      if (confirm('确认退出登录？')) {
        localStorage.removeItem('fp_token');
        localStorage.removeItem('fp_username');
        location.href = 'login.html';
      }
    });
    $('#refreshBtn').addEventListener('click', refreshAll);
    $('#menuToggle').addEventListener('click', function () {
      $('#sidebar').classList.toggle('open');
    });

    window.addEventListener('hashchange', function () {
      const id = (location.hash || '').replace('#/', '') || ROUTES[0].id;
      navigate(id);
    });

    const initial = (location.hash || '').replace('#/', '') || ROUTES[0].id;
    navigate(initial);
    refreshBadge();
  });

  /* ---------- 对外暴露：供业务视图在写操作后重绘当前页 ---------- */
  global.FP.app = {
    /** 清缓存并重绘当前路由的视图（写操作成功后调用，让新数据立刻可见） */
    refresh: async function () {
      FP.views.invalidate();
      if (global.FP.poViews && global.FP.poViews.invalidate) {
        global.FP.poViews.invalidate();
      }
      if (currentRoute) await navigate(currentRoute);
      refreshBadge();
    },
    /** 当前路由 ID */
    current: function () { return currentRoute; }
  };
})(window);
