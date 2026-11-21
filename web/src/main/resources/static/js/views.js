/* ============================================================
   views.js —— 业务视图层
   每个视图对应一个「业务环节」，数据全部来自真实后端接口
   ============================================================ */
(function (global) {
  'use strict';

  const FP = global.FP;
  const { get, post, put } = FP.api;
  const fmt = FP.fmt;
  const badge = FP.badge;
  const $ = FP.$;
  const el = FP.el;
  const toast = FP.toast;
  const chart = FP.chart;

  /* 缓存：避免每个页面重复拉取 */
  const cache = { dealers: null, vehicles: null, creditLines: null, alerts: null };

  async function loadDealers(force) {
    if (cache.dealers && !force) return cache.dealers;
    cache.dealers = await get('/api/dealers');
    return cache.dealers;
  }

  /** 车辆与额度接口都需要 dealerId 参数，这里聚合拉取全部经销商的数据 */
  async function loadAll(force) {
    if (cache.vehicles && cache.creditLines && !force) {
      return { dealers: cache.dealers, vehicles: cache.vehicles, creditLines: cache.creditLines };
    }
    const dealers = await loadDealers(force);
    const vehicleGroups = await Promise.all(dealers.map(function (d) {
      return get('/api/vehicles?dealerId=' + d.id).catch(function () { return []; });
    }));
    const creditGroups = await Promise.all(dealers.map(function (d) {
      return get('/api/credit-lines?dealerId=' + d.id).catch(function () { return []; });
    }));
    cache.vehicles = vehicleGroups.reduce(function (a, b) { return a.concat(b); }, []);
    cache.creditLines = creditGroups.reduce(function (a, b) { return a.concat(b); }, []);
    cache.dealers = dealers;
    return { dealers: dealers, vehicles: cache.vehicles, creditLines: cache.creditLines };
  }

  async function loadAlerts(force) {
    if (cache.alerts && !force) return cache.alerts;
    cache.alerts = await get('/api/alerts?limit=200');
    return cache.alerts;
  }

  function dealerName(id) {
    if (id === null || id === undefined) return '—';
    if (!cache.dealers) return '#' + id;
    const d = cache.dealers.filter(function (x) { return x.id === id; })[0];
    return d ? d.name : '#' + id;
  }

  function loading(text) {
    return '<div class="loading"><div class="spinner"></div>' + (text || '加载中…') + '</div>';
  }

  function errBox(msg) {
    return '<div class="empty">加载失败：' + msg + '<br><span style="font-size:11px">请确认后端已启动</span></div>';
  }

  /* ============================================================
     视图 1：经营总览 Dashboard
     ============================================================ */
  async function dashboard(root) {
    root.innerHTML = loading('正在汇总经营数据…');
    try {
      const [all, alerts] = await Promise.all([loadAll(), loadAlerts()]);
      const dealers = all.dealers, vehicles = all.vehicles, lines = all.creditLines;

      // ---- KPI 口径 ----
      const totalLimit = lines.reduce(function (a, l) { return a + Number(l.approvedLimit || 0); }, 0);
      const totalUsed = lines.reduce(function (a, l) { return a + Number(l.usedAmount || 0); }, 0);
      const totalFrozen = lines.reduce(function (a, l) { return a + Number(l.frozenAmount || 0); }, 0);
      const inStock = vehicles.filter(function (v) { return v.status === 'IN_STOCK'; });
      const principal = vehicles.reduce(function (a, v) { return a + Number(v.principalBalance || 0); }, 0);
      const overdue = vehicles.filter(function (v) { return Number(v.overdueDays || 0) > 0; });
      const openAlerts = alerts.filter(function (a) { return a.status === 'OPEN'; });
      const highAlerts = openAlerts.filter(function (a) { return a.severity === 'HIGH'; });
      const useRate = totalLimit > 0 ? (totalUsed / totalLimit * 100) : 0;

      // ---- 库龄分档（业务口径 90/30/10 天）----
      const agingBuckets = [
        { label: '0-30 天', min: 0, max: 30, color: '#0e9a6a' },
        { label: '31-60 天', min: 31, max: 60, color: '#4a7fd4' },
        { label: '61-90 天', min: 61, max: 90, color: '#c97a08' },
        { label: '90 天以上', min: 91, max: 99999, color: '#c0392b' }
      ].map(function (b) {
        b.value = vehicles.filter(function (v) {
          const d = Number(v.agingDays || 0);
          return d >= b.min && d <= b.max;
        }).length;
        return b;
      });

      // ---- 车辆状态分布 ----
      const statusSeg = [
        { label: '在库', value: vehicles.filter(function (v) { return v.status === 'IN_STOCK'; }).length, color: '#2a4a8f' },
        { label: '已售出', value: vehicles.filter(function (v) { return v.status === 'SOLD'; }).length, color: '#4a7fd4' },
        { label: '已结清', value: vehicles.filter(function (v) { return v.status === 'SETTLED'; }).length, color: '#0e9a6a' },
        { label: '已收回', value: vehicles.filter(function (v) { return v.status === 'REPOSSESSED'; }).length, color: '#c0392b' }
      ].filter(function (s) { return s.value > 0; });

      // ---- 告警严重度 ----
      const sevSeg = [
        { label: '高危', value: openAlerts.filter(function (a) { return a.severity === 'HIGH'; }).length, color: '#c0392b' },
        { label: '中危', value: openAlerts.filter(function (a) { return a.severity === 'MEDIUM'; }).length, color: '#c97a08' },
        { label: '低危', value: openAlerts.filter(function (a) { return a.severity === 'LOW'; }).length, color: '#0e9a6a' }
      ].filter(function (s) { return s.value > 0; });

      // ---- 经销商额度使用 Top ----
      const dealerUsage = dealers.map(function (d) {
        const lines4d = lines.filter(function (l) { return l.dealerId === d.id; });
        const limit = lines4d.reduce(function (a, l) { return a + Number(l.approvedLimit || 0); }, 0);
        const used = lines4d.reduce(function (a, l) { return a + Number(l.usedAmount || 0); }, 0);
        const cnt = vehicles.filter(function (v) { return v.dealerId === d.id; }).length;
        return { dealer: d, limit: limit, used: used, count: cnt, rate: limit ? used / limit * 100 : 0 };
      }).sort(function (a, b) { return b.rate - a.rate; }).slice(0, 8);

      const usageRows = dealerUsage.map(function (u) {
        const cls = u.rate >= 90 ? 'danger' : (u.rate >= 70 ? 'warn' : '');
        return '<tr>' +
          '<td><div style="font-weight:600;color:#1a2f5e">' + u.dealer.name + '</div>' +
          '<div class="muted" style="font-size:11px">' + u.dealer.dealerCode + ' · ' + badge(u.dealer.riskGrade) + '</div></td>' +
          '<td class="num">' + fmt.yen(u.used) + '</td>' +
          '<td class="num muted">' + fmt.yen(u.limit) + '</td>' +
          '<td style="width:150px">' + chart.meter(u.used, u.limit) + '</td>' +
          '<td class="num">' + u.count + '</td>' +
          '</tr>';
      }).join('');

      const alertRows = openAlerts.slice(0, 8).map(function (a) {
        return '<tr>' +
          '<td>' + badge(a.severity) + '</td>' +
          '<td class="mono">' + FP.alertTypeText(a.alertType) + '</td>' +
          '<td>' + dealerName(a.dealerId) + '</td>' +
          '<td class="mono">' + (a.vin || '-') + '</td>' +
          '<td style="color:#5b6678">' + (a.detail || '-') + '</td>' +
          '<td class="muted mono">' + fmt.datetime(a.createdAt) + '</td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="6" class="empty">当前无未处理告警 ✓</td></tr>';

      root.innerHTML =
        '<div class="kpi-grid">' +
        kpiYen('授信总额度', totalLimit, '覆盖 ' + dealers.length + ' 家经销商 · ' + lines.length + ' 条额度', '') +
        kpiYen('已用额度', totalUsed, '使用率 ' + useRate.toFixed(1) + '%', useRate >= 85 ? 'warn' : '') +
        kpiYen('冻结额度', totalFrozen, '解押 / 冻结管控', totalFrozen > 0 ? 'warn' : 'ok') +
        kpi('在库车辆', inStock.length, '台', '台账共 ' + vehicles.length + ' 台', '') +
        kpiYen('在贷本金余额', principal, '平均库龄 ' + avgAging(vehicles) + ' 天', '') +
        kpi('未处理告警', openAlerts.length, '条', '其中高危 ' + highAlerts.length + ' 条', highAlerts.length > 0 ? 'danger' : 'ok') +
        '</div>' +

        '<div class="chart-grid" style="margin-bottom:16px">' +
        '<div class="card" style="margin:0"><header><h2>库龄分布</h2><span class="tag">口径 90/30/10 天</span></header>' +
        '<div class="body">' + chart.bar(agingBuckets, { fmt: function (v) { return v + ' 台'; } }) +
        '<div class="hint" style="margin-top:12px;font-size:11px;color:#8a94a6">超过 90 天将触发违约处置（OOT）流程</div></div></div>' +

        '<div class="card" style="margin:0"><header><h2>车辆状态</h2></header><div class="body">' +
        chart.donut(statusSeg.length ? statusSeg : [{ label: '无数据', value: 0, color: '#e3e8f0' }], vehicles.length, '台总数') +
        '</div></div>' +

        '<div class="card" style="margin:0"><header><h2>告警严重度</h2></header><div class="body">' +
        chart.donut(sevSeg.length ? sevSeg : [{ label: '无告警', value: 1, color: '#0e9a6a' }], openAlerts.length, '未处理') +
        '</div></div>' +
        '</div>' +

        '<div class="card"><header><h2>经销商额度使用情况</h2><span class="tag">按使用率排序 Top 8</span></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>经销商</th><th class="num">已用额度</th><th class="num">授信总额</th><th>使用率</th><th class="num">在库车辆</th>' +
        '</tr></thead><tbody>' + usageRows + '</tbody></table></div></div></div>' +

        '<div class="card"><header><h2>最新风险告警</h2><span class="tag">未处理</span></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>级别</th><th>类型</th><th>经销商</th><th>VIN</th><th>说明</th><th>触发时间</th>' +
        '</tr></thead><tbody>' + alertRows + '</tbody></table></div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  function avgAging(vehicles) {
    const withAging = vehicles.filter(function (v) { return v.agingDays !== null && v.agingDays !== undefined; });
    if (!withAging.length) return 0;
    const sum = withAging.reduce(function (a, v) { return a + Number(v.agingDays || 0); }, 0);
    return Math.round(sum / withAging.length);
  }

  function kpi(label, value, unit, foot, cls) {
    return '<div class="kpi ' + (cls || '') + '">' +
      '<div class="label">' + label + '</div>' +
      '<div class="value">' + value + '<small>' + (unit || '') + '</small></div>' +
      '<div class="foot">' + (foot || '') + '</div></div>';
  }

  /** 金额类 KPI：自动拆数值与单位，避免"3496.5 万 亿日元"这种错配 */
  function kpiYen(label, amount, foot, cls) {
    const p = fmt.yenParts(amount);
    return kpi(label, p.v, p.u, foot, cls);
  }

  /* ============================================================
     视图 2：经销商（授信准入环节 + 后台档案管理）
     ============================================================ */
  async function dealers(root) {
    root.innerHTML = loading();
    try {
      // 用 loadAll 而不是 loadDealers：列表里要展示"名下车辆数 / 在贷本金 / 额度条数"，
      // 这些都要关联数据才能算出来（loadAll 已做缓存，不会每次都打后端）
      const all = await loadAll();
      const list = all.dealers;
      // 写操作入口按「权限点」显示，而不是按「是不是管理员」——
      // 一人多岗之后，能做什么由权限点决定（管理员反而不能代行业务审批）。
      const canMaintain = FP.auth.can('DEALER_MAINTAIN');   // 经销商主数据维护
      const canGrade = FP.auth.can('RISK_GRADE_ADJUST');     // 评级人工调整

      const rows = list.map(function (d) {
        const vehicles = all.vehicles.filter(function (v) { return v.dealerId === d.id; });
        const lines = all.creditLines.filter(function (l) { return l.dealerId === d.id; });
        const principal = vehicles.reduce(function (a, v) { return a + Number(v.principalBalance || 0); }, 0);

        // 写操作入口按权限点收敛——这是体验层的过滤，
        // 真正的拦截在后端 @RequirePermission：没有权限点直接调接口会拿到 403。
        const adminTools = canMaintain
          ? '<button class="btn sm ghost" onclick="FP.views.editDealer(' + d.id + ')">编辑</button> ' +
            '<button class="btn sm ghost" onclick="FP.views.deleteDealer(' + d.id + ')">删除</button> '
          : '<span class="muted" style="font-size:11px">无主数据维护权限</span>';

        return '<tr>' +
          '<td class="num">' + d.id + '</td>' +
          '<td><div style="font-weight:600;color:#1a2f5e">' + d.name + '</div>' +
          '<div class="muted mono" style="font-size:11px">' + d.dealerCode + '</div>' +
          (d.contactPerson
            ? '<div class="muted" style="font-size:11px">' + d.contactPerson +
              (d.contactPhone ? ' · ' + d.contactPhone : '') + '</div>'
            : '') + '</td>' +
          '<td>' + FP.dealerBadge(d.status) + '</td>' +
          '<td>' + badge(d.riskGrade) + '</td>' +
          '<td class="num">' + (d.businessYears === null || d.businessYears === undefined ? '-' : d.businessYears + ' 年') + '</td>' +
          '<td class="num"><button class="btn sm ghost" onclick="FP.views.dealerVehicles(' + d.id + ')">' +
          vehicles.length + ' 台</button></td>' +
          '<td class="num">' + (principal > 0 ? fmt.yen(principal) : '-') + '</td>' +
          '<td class="num">' + lines.length + '</td>' +
          '<td style="white-space:nowrap">' +
          '<button class="btn sm ghost" onclick="FP.views.dealerVehicles(' + d.id + ')">名下车辆</button> ' +
          (canGrade ? '<button class="btn sm ghost" onclick="FP.views.changeGrade(' + d.id + ')">调整评级</button> ' : '') +
          adminTools + '</td>' +
          '</tr>';
      }).join('');

      const roleText = (FP.auth.roleLabels() || [FP.auth.role()]).join('、');
      const adminHint = canMaintain
        ? ''
        : '<div class="card"><header><h2>权限说明</h2></header><div class="body">' +
          '<div style="font-size:12.5px;color:#5b6678;line-height:1.9">' +
          '当前账号角色：<b style="color:#1a2f5e">' + roleText + '</b>，可查看经销商档案与名下车辆台账；' +
          '<b>新增、编辑、删除经销商</b>需要「经销商主数据维护」权限点。' +
          '</div></div></div>';

      root.innerHTML =
        '<div class="card"><header><h2>经销商名录</h2><span class="tag">授信准入 · 风险等级决定执行利率加点</span>' +
        (canMaintain ? '<div class="tools"><button class="btn sm" onclick="FP.views.createDealer()">+ 新增经销商</button></div>' : '') +
        '</header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th class="num">ID</th><th>经销商</th><th>状态</th><th>授信评级</th><th class="num">经营年限</th>' +
        '<th class="num">名下车辆</th><th class="num">在贷本金</th><th class="num">额度条数</th><th>操作</th>' +
        '</tr></thead><tbody>' + (rows || '<tr><td colspan="9" class="empty">暂无经销商</td></tr>') + '</tbody></table></div></div></div>' +

        adminHint +

        '<div class="card"><header><h2>评级与利率的关系</h2></header><div class="body">' +
        '<div style="font-size:12.5px;color:#5b6678;line-height:1.9">' +
        '执行利率 = <b style="color:#1a2f5e">基准利率 + 评级加点</b>。评级加点在后台动态配置，改完即时生效，无需发版。<br>' +
        '调整经销商评级会写入审计日志（操作人 + 前后值），并在下一次垫款建账时按新加点计算。' +
        '</div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  /**
   * 新增经销商（授信准入入口）。
   * 后端 POST /api/dealers 会校验：编号唯一、评级必须在口径表中存在且未停用。
   */
  async function createDealer() {
    try {
      const configs = await get('/api/rating-configs');
      const gradeOpts = [{ value: '', label: '暂不评级（后续补）' }].concat(
        configs.filter(function (c) { return c.allowed; }).map(function (c) {
          return {
            value: c.grade,
            label: c.grade + ' · ' + c.gradeName + '（加点 ' + (c.rateSurcharge * 100).toFixed(2) + '%）'
          };
        })
      );
      FP.formModal({
        title: '新增经销商',
        okText: '创建',
        fields: [
          { key: 'dealerCode', label: '经销商编号', required: true, placeholder: '如 DL-2026-0001',
            hint: '系统内唯一，重复会返回 <b>CONFLICT_001</b>；建议用「DL-年份-序号」便于人工核对' },
          { key: 'name', label: '经销商名称', required: true, placeholder: '如 东京中央汽车販売株式会社' },
          { key: 'riskGrade', label: '授信评级', type: 'select', options: gradeOpts, value: '',
            hint: '留空表示暂不评级；一旦填写，必须是口径表中<b>存在且未停用</b>的等级，否则返回 RATING_001' },
          { key: 'businessYears', label: '经营年限', type: 'number', placeholder: '如 10' },
          { key: 'contactPerson', label: '联系人', placeholder: '如 佐藤 健' },
          { key: 'contactPhone', label: '联系电话', placeholder: '如 03-1234-5678' },
          { key: 'address', label: '营业地址', placeholder: '如 东京都品川区…' },
          { key: 'remark', label: '备注', type: 'textarea', placeholder: '如 客户确认口径 2026-10 版' }
        ],
        onOk: async function (v) {
          try {
            // 注意：不要用 String(v.x) 强转——v.x 为 undefined 时会得到字符串 "undefined"，
            // 它非空，能绕过后端校验并把垃圾写进库里。必须显式判空。
            const code = (v.dealerCode || '').trim();
            const dealerName = (v.name || '').trim();
            if (!code || !dealerName) {
              toast('经销商编号与名称不能为空', 'warn');
              return false;
            }
            const body = {
              dealerCode: code,
              name: dealerName,
              riskGrade: v.riskGrade === '' ? null : v.riskGrade,
              businessYears: v.businessYears === null || v.businessYears === '' ? null : Number(v.businessYears),
              contactPerson: v.contactPerson || null,
              contactPhone: v.contactPhone || null,
              address: v.address || null,
              remark: v.remark || null
            };
            const created = await post('/api/dealers', body);
            toast('经销商「' + created.name + '」已创建（ID ' + created.id + '）', 'ok');
            cache.dealers = null;
            if (global.FP.app) global.FP.app.refresh();
            return true;
          } catch (e) {
            toast('创建失败：' + e.message, 'err');
            return false;
          }
        }
      });
    } catch (e) {
      toast('加载评级配置失败：' + e.message, 'err');
    }
  }

  /**
   * 编辑经销商档案（后台 CRUD 的 U，仅管理员）。
   *
   * <p>后端 PUT /api/dealers/{id} 的校验：
   * 编号唯一（排除自身）、评级口径有效、状态流转合法（终止后不可回退）。
   * 字段留空表示"不改"，传空串表示"清空"。</p>
   */
  async function editDealer(id) {
    try {
      const [list, configs] = await Promise.all([loadDealers(), get('/api/rating-configs')]);
      const d = list.filter(function (x) { return x.id === id; })[0];
      if (!d) { toast('经销商不存在：' + id, 'err'); return; }

      const gradeOpts = [{ value: '', label: '暂不评级（清除评级）' }].concat(
        configs.map(function (c) {
          return {
            value: c.grade,
            label: c.grade + ' · ' + c.gradeName + (c.allowed ? '' : '（已停用）')
          };
        })
      );
      const statusOpts = [
        { value: '', label: '不变更' },
        { value: 'ACTIVE', label: '正常经营（可承接新业务）' },
        { value: 'SUSPENDED', label: '暂停合作（存量照常，禁止新增垫款）' },
        { value: 'TERMINATED', label: '终止合作（终态，不可回退）' }
      ];

      FP.formModal({
        title: '编辑经销商档案 · ' + d.name,
        okText: '保存',
        fields: [
          { key: 'dealerCode', label: '经销商编号', value: d.dealerCode, required: true,
            hint: '编号唯一；改成别的经销商已用的编号会返回 <b>CONFLICT_001</b>' },
          { key: 'name', label: '经销商名称', value: d.name, required: true },
          { key: 'riskGrade', label: '授信评级', type: 'select', options: gradeOpts, value: d.riskGrade || '',
            hint: '留空表示清除评级；选中的等级必须<b>存在且未停用</b>' },
          { key: 'businessYears', label: '经营年限', type: 'number', value: d.businessYears === null || d.businessYears === undefined ? '' : d.businessYears },
          { key: 'status', label: '状态', type: 'select', options: statusOpts, value: '',
            hint: '「终止合作」为终态，不可回退；名下有业务时应<b>终止</b>而不是<b>删除</b>' },
          { key: 'contactPerson', label: '联系人', value: d.contactPerson || '' },
          { key: 'contactPhone', label: '联系电话', value: d.contactPhone || '' },
          { key: 'address', label: '营业地址', value: d.address || '' },
          { key: 'remark', label: '备注', type: 'textarea', value: d.remark || '' }
        ],
        onOk: async function (v) {
          try {
            const code = (v.dealerCode || '').trim();
            const nm = (v.name || '').trim();
            if (!code || !nm) {
              toast('经销商编号与名称不能为空', 'warn');
              return false;
            }
            // 只提交有意义的字段：空串表示"清空"，null 表示"不改"。
            // 这里显式区分两者，避免误把不该清空的字段清掉。
            const body = {
              dealerCode: code,
              name: nm,
              riskGrade: v.riskGrade === '' ? '' : v.riskGrade,
              businessYears: v.businessYears === null || v.businessYears === '' ? null : Number(v.businessYears),
              status: v.status === '' ? null : v.status,
              contactPerson: v.contactPerson === '' ? '' : v.contactPerson,
              contactPhone: v.contactPhone === '' ? '' : v.contactPhone,
              address: v.address === '' ? '' : v.address,
              remark: v.remark === '' ? '' : v.remark
            };
            const updated = await put('/api/dealers/' + id, body);
            toast('经销商「' + updated.name + '」已更新', 'ok');
            cache.dealers = null;
            if (global.FP.app) global.FP.app.refresh();
            return true;
          } catch (e) {
            toast('保存失败：' + (e.forbidden ? '当前账号无编辑权限（仅管理员可操作）' : e.message), 'err');
            return false;
          }
        }
      });
    } catch (e) {
      toast('加载经销商档案失败：' + e.message, 'err');
    }
  }

  /**
   * 删除经销商（后台 CRUD 的 D，仅管理员）。
   *
   * <p>后端有护栏：名下存在车辆或授信额度时返回 422 / DEALER_001，
   * 这种情况下正确做法是把状态置为「终止合作」。</p>
   */
  async function deleteDealer(id) {
    try {
      const list = await loadDealers();
      const d = list.filter(function (x) { return x.id === id; })[0];
      if (!d) { toast('经销商不存在：' + id, 'err'); return; }

      if (!confirm('确认删除经销商「' + d.name + '」（' + d.dealerCode + '）？\n\n' +
        '注意：名下已有车辆或授信额度时无法删除，系统会拦截并建议改为「终止合作」。')) {
        return;
      }
      await FP.api.del('/api/dealers/' + id);
      toast('经销商「' + d.name + '」已删除', 'ok');
      cache.dealers = null;
      if (global.FP.app) global.FP.app.refresh();
    } catch (e) {
      toast('删除失败：' + (e.forbidden ? '当前账号无删除权限（仅管理员可操作）' : e.message), 'err');
    }
  }

  /**
   * 经销商名下车辆清单（抽屉）。
   *
   * <p>后端 GET /api/dealers/{id}/vehicles 一次返回档案 + 车辆列表 + 汇总统计，
   * 避免前端先拉经销商再逐台拉详情的 N+1 请求。</p>
   */
  async function dealerVehicles(id) {
    try {
      const d = await get('/api/dealers/' + id + '/vehicles');
      const s = d.summary || {};
      const vehicles = d.vehicles || [];

      const rows = vehicles.map(function (v) {
        const agingCls = Number(v.agingDays || 0) > 90 ? 'danger' : (Number(v.agingDays || 0) > 60 ? 'warn' : '');
        return '<tr>' +
          '<td class="mono" style="font-weight:600">' + v.vin + '</td>' +
          '<td>' + (v.brand || '-') + ' ' + (v.model || '') +
          '<div class="muted" style="font-size:11px">' + (v.modelYear || '-') + '年 · ' + (v.color || '-') + '</div></td>' +
          '<td>' + badge(v.status) + '</td>' +
          '<td class="num">' + fmt.yen(v.invoiceAmount) + '</td>' +
          '<td class="num">' + fmt.yen(v.principalBalance) + '</td>' +
          '<td class="num"><span class="badge ' + agingCls + '">' + (v.agingDays || 0) + ' 天</span></td>' +
          '<td class="num">' + (v.annualRate ? fmt.rate(v.annualRate) : '-') + '</td>' +
          '<td>' + (Number(v.overdueDays || 0) > 0
            ? '<span class="badge danger">逾期 ' + v.overdueDays + ' 天</span>'
            : (v.ootSuspected ? '<span class="badge danger">疑似违约</span>' : '<span class="muted">—</span>')) + '</td>' +
          '<td><button class="btn sm ghost" onclick="FP.views.vehicleDetail(\'' + v.vin + '\')">详情</button></td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="9" class="empty">该经销商名下暂无车辆</td></tr>';

      FP.drawer('名下车辆 · ' + d.dealer.name,
        '<div class="section-title">经销商档案</div>' +
        '<dl class="dl">' +
        '<dt>编号</dt><dd class="mono">' + d.dealer.dealerCode + '</dd>' +
        '<dt>状态</dt><dd>' + FP.dealerBadge(d.dealer.status) + '</dd>' +
        '<dt>授信评级</dt><dd>' + badge(d.dealer.riskGrade) + '</dd>' +
        '<dt>经营年限</dt><dd>' + (d.dealer.businessYears === null || d.dealer.businessYears === undefined ? '-' : d.dealer.businessYears + ' 年') + '</dd>' +
        '<dt>联系人</dt><dd>' + (d.dealer.contactPerson || '-') +
        (d.dealer.contactPhone ? ' · ' + d.dealer.contactPhone : '') + '</dd>' +
        '<dt>地址</dt><dd>' + (d.dealer.address || '-') + '</dd>' +
        '</dl>' +
        '<div class="section-title">汇总统计</div>' +
        '<div style="display:grid;grid-template-columns:repeat(2,1fr);gap:9px">' +
        statBox('车辆总数', s.vehicleCount + ' 台') +
        statBox('在库', s.inStockCount + ' 台') +
        statBox('在贷本金', fmt.yen(s.totalPrincipalBalance)) +
        statBox('已还本金', fmt.yen(s.totalPaidPrincipal)) +
        statBox('平均库龄', (s.avgAgingDays || 0) + ' 天') +
        statBox('最长库龄', (s.maxAgingDays || 0) + ' 天') +
        statBox('逾期台数', s.overdueCount + ' 台', s.overdueCount > 0 ? 'danger' : '') +
        statBox('疑似违约', s.ootCount + ' 台', s.ootCount > 0 ? 'danger' : '') +
        statBox('授信总额', fmt.yen(s.approvedLimit)) +
        statBox('已用额度', fmt.yen(s.usedAmount)) +
        '</div>' +
        '<div class="section-title">车辆明细</div>' +
        '<div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>VIN</th><th>车型</th><th>状态</th><th class="num">采购金额</th><th class="num">本金余额</th>' +
        '<th class="num">库龄</th><th class="num">执行利率</th><th>风险</th><th>操作</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div>',
        { wide: true });   // 宽抽屉：9 列明细表，窄抽屉会把金额折行
    } catch (e) {
      toast('加载名下车辆失败：' + e.message, 'err');
    }
  }

  /** 抽屉里的统计小方块 */
  function statBox(label, value, cls) {
    return '<div style="background:#f7f9fc;border:1px solid #e3e8f0;border-radius:7px;padding:9px 11px">' +
      '<div style="font-size:10.5px;color:#8a94a6">' + label + '</div>' +
      '<div style="font-size:14px;font-weight:600;font-family:Consolas,monospace;margin-top:3px;color:' +
      (cls === 'danger' ? '#c0392b' : '#1a2f5e') + '">' + value + '</div></div>';
  }

  /** 调整经销商风险评级 */
  async function changeGrade(id) {
    try {
      const [list, configs] = await Promise.all([loadDealers(), get('/api/rating-configs')]);
      const d = list.filter(function (x) { return x.id === id; })[0];
      const opts = configs.filter(function (c) { return c.allowed; }).map(function (c) {
        return { value: c.grade, label: c.grade + ' · ' + c.gradeName + '（加点 ' + (c.rateSurcharge * 100).toFixed(2) + '%）' };
      });
      FP.formModal({
        title: '调整授信评级 · ' + (d ? d.name : '#' + id),
        fields: [{ key: 'riskGrade', label: '新评级', type: 'select', options: opts, value: d ? d.riskGrade : '', required: true }],
        okText: '确认调整',
        onOk: async function (v) {
          try {
            await put('/api/dealers/' + id + '/risk-grade', { riskGrade: v.riskGrade });
            toast('评级已调整为 ' + v.riskGrade + '，即时生效', 'ok');
            cache.dealers = null;
            return true;
          } catch (e) { toast('调整失败：' + e.message, 'err'); return false; }
        }
      });
    } catch (e) {
      toast('加载评级配置失败：' + e.message, 'err');
    }
  }

  /* ============================================================
     视图 3：库存车辆（验车确权 → 在库计息）
     ============================================================ */
  async function vehicles(root) {
    root.innerHTML = loading();
    try {
      const all = await loadAll();
      const rows = all.vehicles.map(function (v) {
        const agingCls = v.agingDays > 90 ? 'danger' : (v.agingDays > 60 ? 'warn' : '');
        const ratioPct = v.advanceRatio ? (v.advanceRatio * 100).toFixed(0) : '-';
        return '<tr>' +
          '<td class="mono" style="font-weight:600">' + v.vin + '</td>' +
          '<td>' + dealerName(v.dealerId) + '</td>' +
          '<td>' + (v.brand || '-') + ' ' + (v.model || '') + '<div class="muted" style="font-size:11px">' +
          (v.modelYear || '-') + '年 · ' + (v.color || '-') + '</div></td>' +
          '<td class="num">' + fmt.yen(v.invoiceAmount) + '</td>' +
          '<td class="num">' + fmt.yen(v.advanceAmount) + '<div class="muted" style="font-size:11px">垫款 ' + ratioPct + '%</div></td>' +
          '<td class="num">' + fmt.yen(v.principalBalance) + '</td>' +
          '<td class="num"><span class="badge ' + agingCls + '">' + (v.agingDays || 0) + ' 天</span></td>' +
          '<td class="num">' + (v.annualRate ? fmt.rate(v.annualRate) : '-') + '</td>' +
          '<td>' + badge(v.status) + '</td>' +
          '<td>' + (Number(v.overdueDays || 0) > 0
            ? '<span class="badge danger">逾期 ' + v.overdueDays + ' 天</span>' : '<span class="muted">—</span>') + '</td>' +
          '<td><button class="btn sm ghost" onclick="FP.views.vehicleDetail(\'' + v.vin + '\')">详情</button></td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="11" class="empty">暂无车辆台账</td></tr>';

      root.innerHTML =
        '<div class="card"><header><h2>库存车辆台账</h2>' +
        '<span class="tag">共 ' + all.vehicles.length + ' 台</span>' +
        '<div class="tools"><button class="btn sm" onclick="FP.views.markSold()">标记售出</button></div></header>' +        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>VIN</th><th>经销商</th><th>车型</th><th class="num">采购金额</th><th class="num">垫款</th>' +
        '<th class="num">本金余额</th><th class="num">库龄</th><th class="num">执行利率</th><th>状态</th><th>逾期</th><th>操作</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  /** 车辆详情抽屉：展示后端 /api/vehicles/{vin} 的完整字段（含风险判定与下一步动作） */
  async function vehicleDetail(vin) {
    try {
      const d = await get('/api/vehicles/' + encodeURIComponent(vin));
      const risk = d.risk || {};
      const sec = d.securityType ? badge(d.securityType) : badge('NONE');
      FP.drawer('车辆详情 · ' + vin,
        '<div class="section-title">基本信息</div>' +
        '<dl class="dl">' +
        '<dt>VIN</dt><dd class="mono">' + d.vin + '</dd>' +
        '<dt>当前状态</dt><dd>' + badge(d.status) + '</dd>' +
        '<dt>采购金额</dt><dd>' + fmt.yenFull(d.invoiceAmount) + '</dd>' +
        '<dt>垫款金额</dt><dd>' + fmt.yenFull(d.advanceAmount) + '（' + fmt.pct(d.advanceRatio) + '）</dd>' +
        '<dt>本金余额</dt><dd>' + fmt.yenFull(d.principalBalance) + '</dd>' +
        '<dt>执行利率</dt><dd>' + fmt.rate(d.annualRate) + '</dd>' +
        '<dt>累计利息</dt><dd>' + fmt.yenFull(d.accruedInterest) + '</dd>' +
        '</dl>' +
        '<div class="section-title">库龄与结算</div>' +
        '<dl class="dl">' +
        '<dt>库龄</dt><dd>' + (d.agingDays || 0) + ' 天</dd>' +
        '<dt>入库日期</dt><dd>' + fmt.date(d.stockInDate) + '</dd>' +
        '<dt>售出日期</dt><dd>' + fmt.date(d.soldAt) + '</dd>' +
        '<dt>结算截止日</dt><dd>' + fmt.date(d.settleDueDate) + '</dd>' +
        '<dt>结清日期</dt><dd>' + fmt.date(d.settledAt) + '</dd>' +
        '</dl>' +
        '<div class="section-title">担保与风险</div>' +
        '<dl class="dl">' +
        '<dt>担保方式</dt><dd>' + sec + '</dd>' +
        '<dt>担保状态</dt><dd>' + (d.securityStatus ? badge(d.securityStatus) : '<span class="muted">—</span>') + '</dd>' +
        '<dt>逾期天数</dt><dd>' + (risk.overdueDays || 0) + ' 天</dd>' +
        '<dt>风险等级</dt><dd>' + badge(risk.riskLevel) + '</dd>' +
        '<dt>疑似违约</dt><dd>' + (risk.ootSuspected ? '<span class="badge danger">是</span>' : '否') + '</dd>' +
        '<dt>盘点不一致</dt><dd>' + (risk.auditMismatchCount || 0) + ' 处</dd>' +
        '<dt>最近盘点</dt><dd>' + fmt.date(risk.lastAuditDate) + '</dd>' +
        '</dl>' +
        '<div class="section-title">系统建议动作</div>' +
        '<div style="padding:11px 13px;background:#e8eefa;border-radius:7px;font-size:12.5px;color:#24407f">' +
        (d.nextAction || '暂无待办动作') + '</div>' +
        '<div class="section-title">可执行操作</div>' +
        '<div style="display:flex;gap:8px;flex-wrap:wrap">' +
        '<button class="btn sm" onclick="FP.views.markSold(\'' + d.vin + '\')">标记售出</button>' +
        '<button class="btn sm ghost" onclick="FP.views.setSecurity(\'' + d.vin + '\')">设定担保</button>' +
        '<button class="btn sm ghost" onclick="FP.views.releaseSecurity(\'' + d.vin + '\')">解除担保</button>' +
        '<button class="btn sm ghost" onclick="FP.views.lienCheck(\'' + d.vin + '\')">权属核对</button>' +
        '</div>');
    } catch (e) {
      toast('加载车辆详情失败：' + e.message, 'err');
    }
  }

  /** 标记售出（进入结算窗口） */
  function markSold(vin) {
    FP.formModal({
      title: '标记车辆售出',
      fields: [
        { key: 'vin', label: 'VIN', value: vin || '', placeholder: '输入 VIN（留空则手动输入）', required: true },
        { key: 'soldAt', label: '售出日期', type: 'date', value: new Date().toISOString().substring(0, 10),
          hint: '售出后进入结算窗口（2 营业日 + 5 营业日），窗口内未收到尾款则进入违约处置' }
      ],
      okText: '确认标记',
      onOk: async function (v) {
        try {
          await post('/api/vehicles/' + encodeURIComponent(v.vin) + '/sold', { soldAt: v.soldAt });
          toast('已标记售出，进入结算窗口', 'ok');
          cache.vehicles = null;
          return true;
        } catch (e) { toast('操作失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /** 设定担保（第 ④ 环节） */
  function setSecurity(vin) {
    FP.formModal({
      title: '设定担保 · ' + (vin || ''),
      fields: [
        { key: 'vin', label: 'VIN', value: vin || '', required: true },
        { key: 'securityType', label: '担保方式', type: 'select', value: 'RETENTION', options: [
          { value: 'RETENTION', label: '所有权保留（默认）' },
          { value: 'ASSIGNMENT', label: '让与担保' },
          { value: 'OTHER', label: '其他' },
          { value: 'NONE', label: '无担保' }
        ] },
        { key: 'regNo', label: '登记号', placeholder: '如 OBL-2026-0001' },
        { key: 'ownerName', label: '登记名义人', placeholder: '经销商名称' }
      ],
      okText: '确认设定',
      onOk: async function (v) {
        try {
          await post('/api/vehicles/' + encodeURIComponent(v.vin) + '/security', {
            securityType: v.securityType, regNo: v.regNo, ownerName: v.ownerName
          });
          toast('担保已登记', 'ok');
          cache.vehicles = null;
          return true;
        } catch (e) { toast('操作失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /** 解除担保（第 ⑦ 环节） */
  function releaseSecurity(vin) {
    FP.formModal({
      title: '解除担保 · ' + (vin || ''),
      fields: [{ key: 'vin', label: 'VIN', value: vin || '', required: true }],
      okText: '确认解除',
      onOk: async function (v) {
        try {
          await post('/api/vehicles/' + encodeURIComponent(v.vin) + '/security/release');
          toast('担保已解除', 'ok');
          cache.vehicles = null;
          return true;
        } catch (e) { toast('操作失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /** 权属核对（重复融资拦截的数据来源） */
  function lienCheck(vin) {
    FP.formModal({
      title: '权属 / 抵押 / 查封核对 · ' + (vin || ''),
      fields: [
        { key: 'vin', label: 'VIN', value: vin || '', required: true },
        { key: 'checkSource', label: '核对来源', type: 'select', value: 'MANUAL', options: [
          { value: 'MANUAL', label: '人工核对' },
          { value: 'OEM_PORTAL', label: '厂家门户' },
          { value: 'REGISTRY_API', label: '车管所接口' }
        ] },
        { key: 'encumbered', label: '是否在他处存在权属负担', type: 'select', value: 'false', options: [
          { value: 'false', label: '否（可正常建账）' },
          { value: 'true', label: '是（禁止重复融资）' }
        ] },
        { key: 'note', label: '备注', type: 'textarea', placeholder: '核对结论说明' }
      ],
      okText: '提交核对',
      onOk: async function (v) {
        try {
          await post('/api/vehicles/' + encodeURIComponent(v.vin) + '/lien-check', {
            checkSource: v.checkSource, encumbered: v.encumbered === 'true', note: v.note
          });
          toast('权属核对已录入', 'ok');
          cache.vehicles = null;
          return true;
        } catch (e) { toast('录入失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /* ============================================================
     视图 4：授信额度（额度恢复 / 冻结管控）
     ============================================================ */
  async function creditLines(root) {
    root.innerHTML = loading();
    try {
      const all = await loadAll();
      const rows = all.creditLines.map(function (l) {
        const available = Number(l.approvedLimit || 0) - Number(l.usedAmount || 0) - Number(l.frozenAmount || 0);
        return '<tr>' +
          '<td class="num">' + l.id + '</td>' +
          '<td>' + dealerName(l.dealerId) + '</td>' +
          '<td class="num">' + fmt.yen(l.approvedLimit) + '</td>' +
          '<td class="num">' + fmt.yen(l.usedAmount) + '</td>' +
          '<td class="num">' + fmt.yen(l.frozenAmount) + '</td>' +
          '<td class="num" style="font-weight:600;color:' + (available > 0 ? '#0e9a6a' : '#c0392b') + '">' + fmt.yen(available) + '</td>' +
          '<td style="width:140px">' + chart.meter(Number(l.usedAmount || 0), Number(l.approvedLimit || 0)) + '</td>' +
          '<td>' + badge(l.status) + '</td>' +
          '<td><button class="btn sm ghost" onclick="FP.views.freezeLine(' + l.id + ')">冻结</button> ' +
          '<button class="btn sm ghost" onclick="FP.views.unfreezeLine(' + l.id + ')">解冻</button></td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="9" class="empty">暂无授信额度</td></tr>';

      root.innerHTML =
        '<div class="card"><header><h2>授信额度台账</h2><span class="tag">可用 = 总额 − 已用 − 冻结</span>' +
        '<div class="tools"><button class="btn sm" onclick="FP.views.createCreditLine()">+ 新增额度</button></div></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th class="num">ID</th><th>经销商</th><th class="num">授信总额</th><th class="num">已用</th>' +
        '<th class="num">冻结</th><th class="num">可用</th><th>使用率</th><th>状态</th><th>操作</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  /**
   * 新增授信额度。
   * 后端 POST /api/credit-lines 校验：额度必须为正、经销商必须存在。
   */
  async function createCreditLine() {
    try {
      const dealers = await loadDealers();
      if (!dealers.length) {
        toast('尚无经销商，请先在「经销商」页新增', 'warn');
        return;
      }
      const opts = dealers.map(function (d) {
        return { value: d.id, label: '#' + d.id + ' ' + d.name + '（' + d.dealerCode + '）' };
      });
      FP.formModal({
        title: '新增授信额度',
        okText: '核定',
        fields: [
          { key: 'dealerId', label: '经销商', type: 'select', options: opts, required: true },
          { key: 'approvedLimit', label: '核定额度（日元）', type: 'number', required: true,
            placeholder: '如 50000000',
            hint: '必须为正整数日元；这是该经销商的<b>总额度</b>，已用与冻结均从中扣减' }
        ],
        onOk: async function (v) {
          try {
            const created = await post('/api/credit-lines', {
              dealerId: Number(v.dealerId),
              approvedLimit: Number(v.approvedLimit)
            });
            toast('额度已核定：' + fmt.yen(created.approvedLimit) + '（ID ' + created.id + '）', 'ok');
            cache.creditLines = null;
            if (global.FP.app) global.FP.app.refresh();
            return true;
          } catch (e) {
            toast('核定失败：' + e.message, 'err');
            return false;
          }
        }
      });
    } catch (e) {
      toast('加载经销商失败：' + e.message, 'err');
    }
  }

  function freezeLine(id) {
    FP.formModal({
      title: '冻结额度 · #' + id,
      fields: [
        { key: 'amount', label: '冻结金额（日元）', type: 'number', required: true, placeholder: '留空表示全额冻结' },
        { key: 'reason', label: '冻结原因', placeholder: '如 风险事件核查', required: true }
      ],
      okText: '确认冻结',
      danger: true,
      onOk: async function (v) {
        try {
          await post('/api/credit-lines/' + id + '/freeze', {
            amount: v.amount, reason: v.reason
          });
          toast('额度已冻结', 'ok');
          cache.creditLines = null;
          return true;
        } catch (e) { toast('冻结失败：' + e.message, 'err'); return false; }
      }
    });
  }

  function unfreezeLine(id) {
    FP.formModal({
      title: '解冻额度 · #' + id,
      fields: [
        { key: 'amount', label: '解冻金额（日元）', type: 'number', placeholder: '留空表示全部解冻' },
        { key: 'reason', label: '解冻原因', placeholder: '如 核查完成', required: true }
      ],
      okText: '确认解冻',
      onOk: async function (v) {
        try {
          await post('/api/credit-lines/' + id + '/unfreeze', {
            amount: v.amount, reason: v.reason
          });
          toast('额度已解冻', 'ok');
          cache.creditLines = null;
          return true;
        } catch (e) { toast('解冻失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /* ============================================================
     视图 5：风险告警
     ============================================================ */
  async function alerts(root) {
    root.innerHTML = loading();
    try {
      const list = await loadAlerts();
      const open = list.filter(function (a) { return a.status === 'OPEN'; });
      const rows = list.map(function (a) {
        return '<tr>' +
          '<td class="num">' + a.id + '</td>' +
          '<td>' + badge(a.severity) + '</td>' +
          '<td class="mono">' + FP.alertTypeText(a.alertType) + '</td>' +
          '<td>' + dealerName(a.dealerId) + '</td>' +
          '<td class="mono">' + (a.vin || '—') + '</td>' +
          '<td style="color:#5b6678;max-width:320px">' + (a.detail || '-') + '</td>' +
          '<td>' + FP.alertStatusBadge(a.status) + '</td>' +
          '<td class="muted mono">' + fmt.datetime(a.createdAt) + '</td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="8" class="empty">无告警记录</td></tr>';

      const byType = {};
      list.forEach(function (a) { byType[a.alertType] = (byType[a.alertType] || 0) + 1; });
      const typeItems = Object.keys(byType).map(function (k) {
        return { label: FP.alertTypeText(k), value: byType[k] };
      }).sort(function (a, b) { return b.value - a.value; });

      root.innerHTML =
        '<div class="kpi-grid">' +
        kpi('告警总数', list.length, '条', '全量历史', '') +
        kpi('未处理', open.length, '条', '需人工介入', open.length ? 'danger' : 'ok') +
        kpi('高危', open.filter(function (a) { return a.severity === 'HIGH'; }).length, '条', '优先级最高',
          open.filter(function (a) { return a.severity === 'HIGH'; }).length ? 'danger' : 'ok') +
        kpi('已处理', list.length - open.length, '条', '历史闭环', 'ok') +
        '</div>' +

        '<div class="chart-grid" style="margin-bottom:16px">' +
        '<div class="card" style="margin:0"><header><h2>告警类型分布</h2></header><div class="body">' +
        chart.bar(typeItems, { fmt: function (v) { return v + ' 条'; } }) + '</div></div>' +
        '<div class="card" style="margin:0"><header><h2>严重度构成</h2></header><div class="body">' +
        chart.donut([
          { label: '高危', value: list.filter(function (a) { return a.severity === 'HIGH'; }).length, color: '#c0392b' },
          { label: '中危', value: list.filter(function (a) { return a.severity === 'MEDIUM'; }).length, color: '#c97a08' },
          { label: '低危', value: list.filter(function (a) { return a.severity === 'LOW'; }).length, color: '#0e9a6a' }
        ], list.length, '总告警') + '</div></div>' +
        '</div>' +

        '<div class="card"><header><h2>告警明细</h2></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th class="num">ID</th><th>级别</th><th>类型</th><th>经销商</th><th>VIN</th><th>说明</th><th>状态</th><th>触发时间</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  /* ============================================================
     视图 6：评级配置（后台动态口径）
     ============================================================ */
  async function ratingConfigs(root) {
    root.innerHTML = loading();
    try {
      const list = await get('/api/rating-configs');
      const rows = list.map(function (c) {
        return '<tr>' +
          '<td><b style="color:#1a2f5e">' + c.grade + '</b></td>' +
          '<td>' + c.gradeName + '</td>' +
          '<td class="num">' + (c.rateSurcharge * 100).toFixed(2) + '%</td>' +
          '<td>' + (c.allowed ? '<span class="badge ok">准入</span>' : '<span class="badge neutral">停用</span>') + '</td>' +
          '<td class="num">' + (c.sortOrder || '-') + '</td>' +
          '<td style="color:#5b6678">' + (c.remark || '-') + '</td>' +
          '<td class="muted mono">' + fmt.datetime(c.updatedAt) + '</td>' +
          '<td><button class="btn sm ghost" onclick="FP.views.editRating(\'' + c.grade + '\')">修改</button></td>' +
          '</tr>';
      }).join('');

      root.innerHTML =
        '<div class="card"><header><h2>授信评级分级口径</h2><span class="tag">后台动态配置 · 改完即时生效</span></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>等级</th><th>名称</th><th class="num">利率加点</th><th>准入</th><th class="num">排序</th>' +
        '<th>备注</th><th>更新时间</th><th>操作</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div></div></div>' +

        '<div class="card"><header><h2>业务口径说明</h2></header><div class="body">' +
        '<div style="font-size:12.5px;color:#5b6678;line-height:2">' +
        '· 执行利率 = 基准利率 + 本表加点，<b style="color:#1a2f5e">改口径只改配置不改代码</b><br>' +
        '· 停用某等级后，<b>不能</b>把新经销商调入该等级（接口返回 RATING_001）<br>' +
        '· 每次修改写入审计日志（操作人 / 修改时间 / 备注）' +
        '</div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  function editRating(grade) {
    FP.formModal({
      title: '修改评级口径 · ' + grade,
      fields: [
        { key: 'gradeName', label: '等级名称', value: '', required: true, hint: '如 优质 / 标准 / 关注 / 观察' },
        { key: 'rateSurcharge', label: '年化利率加点（%）', type: 'number', required: true, hint: '小数形式，0.005 = +0.5%' },
        { key: 'allowed', label: '是否准入新业务', type: 'select', value: 'true', options: [
          { value: 'true', label: '准入' }, { value: 'false', label: '停用' }
        ] },
        { key: 'remark', label: '备注', placeholder: '如 客户确认口径 2026-10 版' }
      ],
      okText: '保存',
      onOk: async function (v) {
        try {
          await put('/api/rating-configs/' + grade, {
            gradeName: v.gradeName,
            rateSurcharge: Number(v.rateSurcharge) / 100,
            allowed: v.allowed === 'true',
            remark: v.remark
          });
          toast('评级口径已更新，即时生效', 'ok');
          return true;
        } catch (e) { toast('保存失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /* ============================================================
     视图 7：批量作业（定时任务）
     ============================================================ */
  const JOBS = [
    { name: 'DAILY_INTEREST', path: '/api/jobs/interest', label: '每日计息', desc: '按 365 天含当日四舍五入计提利息' },
    { name: 'AGING', path: '/api/jobs/aging', label: '库龄重算', desc: '重算每台车的库龄天数' },
    { name: 'AGING_REDUCTION', path: '/api/jobs/aging-reduction', label: '库龄递减还款', desc: '按库龄档位 90/30/10% 递减本金' },
    { name: 'SETTLE_WINDOW_SCAN', path: '/api/jobs/settlement-scan', label: '结算窗口扫描', desc: '扫结算窗口到期未回款车辆' },
    { name: 'DEFAULT_DISPOSAL', path: '/api/jobs/disposal', label: '违约处置扫描', desc: '超限车辆自动开违约处置工单' },
    { name: 'ANNUAL_REVIEW_SCAN', path: '/api/jobs/annual-review-scan', label: '额度年审扫描', desc: '到期额度冻结并生成年审工单' }
  ];

  async function jobs(root) {
    root.innerHTML = loading();
    try {
      const today = new Date().toISOString().substring(0, 10);
      const statuses = await Promise.all(JOBS.map(function (j) {
        return get('/api/jobs/status?jobName=' + j.name + '&bizDate=' + today)
          .then(function (d) { return { job: j, data: d }; })
          .catch(function () { return { job: j, data: null }; });
      }));

      const cards = statuses.map(function (s) {
        const ok = s.data && s.data.succeeded;
        return '<div class="card" style="margin:0"><header>' +
          '<h2>' + s.job.label + '</h2>' +
          '<span class="tag" title="' + s.job.name + '">' + s.job.name + '</span>' +
          '<div class="tools"><span class="job-status"><span class="dot ' + (ok ? 'ok' : 'idle') + '"></span>' +
          (ok ? '今日已执行' : '今日未执行') + '</span></div>' +
          '</header><div class="body">' +
          '<div style="font-size:12px;color:#5b6678;margin-bottom:12px;line-height:1.7">' + s.job.desc + '</div>' +
          '<button class="btn sm" onclick="FP.views.runJob(\'' + s.job.name + '\', \'' + s.job.path + '\')">立即执行</button>' +
          '<div class="mono" id="jobres-' + s.job.name + '" style="margin-top:11px;font-size:11px;color:#5b6678;white-space:pre-wrap"></div>' +
          '</div></div>';
      }).join('');

      root.innerHTML =
        '<div class="card"><header><h2>批量作业中心</h2><span class="tag">幂等：同作业同业务日重复执行不会重复记账</span></header>' +
        '<div class="body"><div style="font-size:12.5px;color:#5b6678;line-height:1.8">' +
        '这些作业平时由定时任务自动触发。这里可以手动补跑（如补算节假日、补跑失败批次）。' +
        '所有作业均按 <b style="color:#1a2f5e">(作业名, 业务日)</b> 幂等，重跑不会造成重复计息或重复扣本金。' +
        '</div></div></div>' +
        '<div class="chart-grid" style="grid-template-columns:repeat(auto-fit,minmax(380px,1fr))">' + cards + '</div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  async function runJob(name, path) {
    const box = document.getElementById('jobres-' + name);
    if (box) box.textContent = '执行中…';
    try {
      const d = await post(path);
      if (box) box.textContent = '✔ 完成：' + JSON.stringify(d).substring(0, 260);
      toast(name + ' 执行完成', 'ok');
    } catch (e) {
      if (box) box.textContent = '✘ 失败：' + e.message;
      toast(name + ' 执行失败：' + e.message, 'err');
    }
  }

  /* ============================================================
     视图 8：盘点监管（实物监管）
     ============================================================ */
  async function audits(root) {
    root.innerHTML = loading();
    try {
      const all = await loadAll();
      const mismatch = all.vehicles.filter(function (v) {
        return Number(v.auditMismatchCount || 0) > 0;
      });
      const oot = all.vehicles.filter(function (v) { return v.ootSuspected; });

      const rows = all.vehicles.map(function (v) {
        const m = Number(v.auditMismatchCount || 0);
        return '<tr>' +
          '<td class="mono">' + v.vin + '</td>' +
          '<td>' + dealerName(v.dealerId) + '</td>' +
          '<td>' + badge(v.status) + '</td>' +
          '<td class="num">' + (m > 0 ? '<span class="badge danger">' + m + ' 处</span>' : '<span class="badge ok">一致</span>') + '</td>' +
          '<td>' + (v.ootSuspected ? '<span class="badge danger">疑似违约</span>' : '<span class="muted">—</span>') + '</td>' +
          '<td class="num">' + (v.agingDays || 0) + '</td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="6" class="empty">暂无车辆</td></tr>';

      root.innerHTML =
        '<div class="kpi-grid">' +
        kpi('纳管车辆', all.vehicles.length, '台', '实物监管范围', '') +
        kpi('盘点不一致', mismatch.length, '台', '需现场复核', mismatch.length ? 'danger' : 'ok') +
        kpi('疑似违约', oot.length, '台', '超库龄上限', oot.length ? 'danger' : 'ok') +
        kpi('正常在库', all.vehicles.length - mismatch.length - oot.length, '台', '监管达标', 'ok') +
        '</div>' +

        '<div class="card"><header><h2>库存盘点</h2>' +
        '<div class="tools"><button class="btn sm" onclick="FP.views.submitAudit()">提交盘点结果</button></div></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>VIN</th><th>经销商</th><th>状态</th><th class="num">盘点一致性</th><th>违约判定</th><th class="num">库龄</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div></div></div>' +

        '<div class="card"><header><h2>监管口径</h2></header><div class="body">' +
        '<div style="font-size:12.5px;color:#5b6678;line-height:1.9">' +
        '· 日本本地监管以<b style="color:#1a2f5e">实地盘点</b>为主，不做钥匙 / GPS / 封签<br>' +
        '· 盘点不一致会累积到车辆上，超过阈值触发人工核查<br>' +
        '· 库龄超 90 天 → 疑似违约 → 违约处置（OOT）流程' +
        '</div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  function submitAudit() {
    FP.formModal({
      title: '提交库存盘点结果',
      fields: [
        { key: 'dealerId', label: '经销商 ID', type: 'number', required: true },
        { key: 'auditDate', label: '盘点日期', type: 'date', value: new Date().toISOString().substring(0, 10) },
        { key: 'scannedVins', label: '盘点到的 VIN（逗号分隔）', placeholder: '留空表示全部在库车辆均已盘点' }
      ],
      okText: '提交',
      onOk: async function (v) {
        try {
          const body = { dealerId: Number(v.dealerId), auditDate: v.auditDate };
          if (v.scannedVins) {
            body.scannedVins = v.scannedVins.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
          }
          const d = await post('/api/risk/inventory/audit', body);
          toast('盘点已提交：' + JSON.stringify(d).substring(0, 120), 'ok');
          cache.vehicles = null;
          return true;
        } catch (e) { toast('提交失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /* ============================================================
     视图 9：我的凭证（令牌信息 + 调试）
     ============================================================ */
  function account(root) {
    const token = localStorage.getItem('fp_token');
    let claims = {};
    try {
      const p = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      claims = JSON.parse(decodeURIComponent(atob(p + '='.repeat((4 - p.length % 4) % 4))
        .split('').map(function (c) { return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2); }).join('')));
    } catch (e) { claims = {}; }

    root.innerHTML =
      '<div class="card"><header><h2>登录凭证（JWT）</h2><span class="tag">载荷仅 Base64 编码，非加密</span></header>' +
      '<div class="body"><dl class="dl">' +
      '<dt>登录用户</dt><dd>' + (claims.sub || '-') + '</dd>' +
      '<dt>签发时间</dt><dd>' + fmt.datetime(new Date(claims.iat * 1000).toISOString()) + '</dd>' +
      '<dt>过期时间</dt><dd>' + fmt.datetime(new Date(claims.exp * 1000).toISOString()) + '</dd>' +
      '<dt>剩余有效</dt><dd id="leftBox">-</dd>' +
      '</dl>' +
      '<div class="section-title">令牌原文</div>' +
      '<div style="font-family:Consolas,monospace;font-size:11px;color:#5b6678;background:#f6f8fc;padding:11px;border-radius:6px;word-break:break-all">' +
      token + '</div></div></div>' +

      '<div class="card"><header><h2>接口调试</h2><span class="tag">替代 curl 的日常调试</span></header>' +
      '<div class="body">' +
      '<div style="display:flex;gap:8px;margin-bottom:11px">' +
      '<input class="input" id="dbgPath" value="/api/dealers" style="flex:1">' +
      '<button class="btn" onclick="FP.views.debugCall()">发送</button></div>' +
      '<pre id="dbgOut" class="mono" style="margin:0;padding:13px;background:#0f1b33;color:#9fd0ff;border-radius:8px;font-size:11.5px;line-height:1.6;max-height:340px;overflow:auto;min-height:60px"></pre>' +
      '</div></div>';

    const left = document.getElementById('leftBox');
    function tick() {
      if (!claims.exp) return;
      const ms = claims.exp * 1000 - Date.now();
      left.textContent = ms <= 0 ? '已过期，请重新登录' : Math.floor(ms / 60000) + ' 分钟';
    }
    tick();
    setInterval(tick, 30000);
  }

  async function debugCall() {
    const out = document.getElementById('dbgOut');
    const path = document.getElementById('dbgPath').value;
    out.textContent = '请求中…';
    try {
      const d = await get(path);
      out.textContent = JSON.stringify(d, null, 2);
    } catch (e) {
      out.textContent = '✘ ' + e.message + '\ncode: ' + (e.code || '-') + '\ntraceId: ' + (e.traceId || '-');
    }
  }

  global.FP.views = {
    dashboard: dashboard,
    dealers: dealers,
    vehicles: vehicles,
    creditLines: creditLines,
    alerts: alerts,
    ratingConfigs: ratingConfigs,
    jobs: jobs,
    audits: audits,
    account: account,
    // 操作
    changeGrade: changeGrade,
    vehicleDetail: vehicleDetail,
    markSold: markSold,
    setSecurity: setSecurity,
    releaseSecurity: releaseSecurity,
    lienCheck: lienCheck,
    freezeLine: freezeLine,
    unfreezeLine: unfreezeLine,
    createDealer: createDealer,
    editDealer: editDealer,
    deleteDealer: deleteDealer,
    dealerVehicles: dealerVehicles,
    createCreditLine: createCreditLine,
    editRating: editRating,
    runJob: runJob,
    submitAudit: submitAudit,
    debugCall: debugCall,
    invalidate: function () { cache.dealers = cache.vehicles = cache.creditLines = cache.alerts = null; }
  };
})(window);
